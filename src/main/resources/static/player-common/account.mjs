import { createWebSession, WebSessionBlocked } from "./web-session.mjs";
import { UUID4, WebApiError } from "./web-api.mjs";

const element = (id) => document.getElementById(id);
let session;
let view = ["login", "signup", "recover", "security", "erasure"].includes(
  location.hash.slice(1),
)
  ? location.hash.slice(1)
  : "login";
let busy = false;
let member = null;
let emailStage = 0;
let recentVerified = false;

/** 비밀번호·코드·닉네임·고지를 DOM과 기본값에서 함께 제거한다. */
function purge() {
  for (const input of document.querySelectorAll("input")) {
    input.value = "";
    input.defaultValue = "";
    input.checked = false;
    input.defaultChecked = false;
  }
  for (const id of [
    "member-name",
    "notice-version",
    "notice-body",
    "notice-contact",
    "erasure-expiry",
    "erasure-status",
  ])
    element(id).textContent = "";
  member = null;
  emailStage = 0;
  recentVerified = false;
  render();
}

/** 서버 고정 오류만 사용자 안내로 바꾸고 원문 비밀·주소·서버 메시지는 반사하지 않는다. */
function errorText(failure) {
  if (failure instanceof WebApiError && failure.status === 429)
    return "요청이 많습니다. 잠시 후 직접 다시 시도해 주세요.";
  if (
    failure instanceof WebApiError &&
    [400, 401, 409, 422].includes(failure.status)
  )
    return "입력 또는 현재 확인 상태가 유효하지 않습니다. 로그인하거나 메일 절차를 다시 시작해 주세요.";
  if (failure instanceof WebSessionBlocked)
    return "인증 결과를 확인할 수 없습니다. 자동 재전송하지 않습니다. 명시적으로 로그인하거나 메일 절차를 다시 시작해 주세요.";
  return "요청을 완료하지 못했습니다. 환경 또는 연결을 확인하고 직접 다시 시도해 주세요.";
}

/** 실제 현재 회원의 최소 응답만 화면 상태로 채택한다. */
async function loadMember() {
  const value = await session.getCurrentMember();
  const fields = [
    "memberKey",
    "nickname",
    "identities",
    "sessionKey",
    "requestId",
  ];
  if (
    !value ||
    Object.keys(value).length !== fields.length ||
    !fields.every((key) => Object.hasOwn(value, key)) ||
    !["memberKey", "sessionKey", "requestId"].every((key) =>
      UUID4.test(value[key]),
    ) ||
    typeof value.nickname !== "string" ||
    !Array.isArray(value.identities) ||
    !value.identities.every(
      (identity) =>
        identity &&
        Object.keys(identity).length === 2 &&
        UUID4.test(identity.identityKey) &&
        typeof identity.provider === "string",
    )
  )
    throw new WebSessionBlocked();
  member = value;
}

/** 현재 작업·서버 인증 상태에 따라 필요한 입력만 표시한다. */
function render() {
  if (location.pathname === "/account" && location.hash !== "#" + view) {
    history.replaceState(null, "", "/account#" + view);
  }
  const authenticated = session?.current() && member !== null;
  element("login").hidden = view !== "login";
  element("email").hidden = !["signup", "recover"].includes(view);
  element("security").hidden = view !== "security" || !authenticated;
  element("erasure").hidden = view !== "erasure";
  element("email-title").textContent =
    view === "recover" ? "계정 복구" : "회원가입";
  element("email-start").hidden = emailStage !== 0;
  element("email-verify").hidden = emailStage !== 1;
  element("email-complete").hidden = emailStage !== 2;
  element("nickname-label").hidden = view !== "signup";
  element("nickname").required = view === "signup" && emailStage === 2;
  element("signup-notice").hidden = view !== "signup" || emailStage !== 2;
  element("signup-consent").required = view === "signup" && emailStage === 2;
  element("complete-email-button").textContent =
    view === "recover" ? "비밀번호 복구 완료" : "가입 완료";
  element("recent-form").hidden = recentVerified;
  element("security-complete").hidden = !recentVerified;
  const changing = element("security-purpose").value === "PASSWORD_CHANGE";
  const deleting = element("security-purpose").value === "DELETE_ACCOUNT";
  element("deletion-consents").hidden = !deleting;
  element("deletion-shared").required = element(
    "deletion-irreversible",
  ).required = deleting && recentVerified;
  element("new-password-fields").hidden = !changing;
  element("new-password").required = changing && recentVerified;
  element("new-confirm").required = changing && recentVerified;
  element("member-name").textContent = authenticated
    ? member.nickname + " · 현재 계정"
    : "";
  for (const button of document.querySelectorAll("button"))
    button.disabled = busy || !session;
  element("erasure-read").disabled ||= !session?.getErasureReceipt();
  for (const input of document.querySelectorAll("input, select"))
    input.disabled = busy || !session;
  for (const button of document.querySelectorAll("[data-view]"))
    button.setAttribute("aria-pressed", String(button.dataset.view === view));
  element("main").setAttribute("aria-busy", String(busy));
}

/** 화면 변경은 이전 메일·본인 확인 증명과 입력을 폐기하며 서버 변경을 자동 요청하지 않는다. */
function selectView(next) {
  if (busy) return;
  session.cancelPending();
  const currentMember = member;
  purge();
  member = currentMember;
  view = next === "security" && !session.current() ? "login" : next;
  element("error").hidden = true;
  element("status").textContent =
    next === "security" && view === "login"
      ? "계정 보안을 사용하려면 로그인해 주세요."
      : "선택한 작업을 직접 실행해 주세요.";
  render();
  const title = element(
    view === "signup" || view === "recover" ? "email-title" : view + "-title",
  );
  title.focus();
}

/** 사용자 명시 동작만 한 번 실행하며 재시도는 사용자에게 남긴다. */
async function run(action, success, deleting = false) {
  if (busy || !session) return;
  busy = true;
  element("error").hidden = true;
  element("status").textContent = "요청을 처리하는 중입니다.";
  render();
  try {
    await action();
    element("status").textContent =
      typeof success === "function" ? success() : success;
  } catch (failure) {
    element("error").textContent = errorText(failure);
    element("error").hidden = false;
    element("status").textContent = "완료를 확인하지 못했습니다.";
    if (!session.current()) {
      member = null;
      if (view === "security") view = "login";
    }
    if (view === "signup" || view === "recover") emailStage = 0;
    if (deleting) {
      view = "erasure";
      element("error").textContent =
        "탈퇴 접수 결과를 확인하지 못했습니다. 자동 재전송하거나 로그인 결과로 탈퇴를 추정하지 않습니다. 받은 영수증이 없어 이 문서에서 파기 상태를 조회할 수 없습니다.";
      element("erasure-status").textContent =
        "접수 여부와 영수증 수신을 확인하지 못했습니다. 파기 완료가 아닙니다.";
    }
    recentVerified = false;
  } finally {
    busy = false;
    render();
  }
}

/** 새 비밀번호의 코드포인트 길이와 확인값을 검사하며 정규화하지 않는다. */
function newPassword(passwordId, confirmId) {
  const password = element(passwordId).value;
  if (
    [...password].length < 15 ||
    [...password].length > 128 ||
    password !== element(confirmId).value
  )
    throw new WebApiError(422, "INVALID_INPUT");
  element(passwordId).value = "";
  element(confirmId).value = "";
  return password;
}

/** 폼 제출은 브라우저 기본 전송을 막고 현재 선택 작업에만 연결한다. */
function submit(id, handler, success) {
  element(id).addEventListener("submit", (event) => {
    event.preventDefault();
    const deleting =
      id === "security-complete" &&
      element("security-purpose").value === "DELETE_ACCOUNT";
    void run(handler, success, deleting);
  });
}

for (const button of document.querySelectorAll("[data-view]"))
  button.addEventListener("click", () => selectView(button.dataset.view));
submit(
  "login-form",
  async () => {
    const email = element("login-email").value;
    const password = element("login-password").value;
    element("login-password").value = "";
    await session.login(email, password);
    await loadMember();
    view = "security";
  },
  "로그인했습니다. 현재 계정의 보안 작업을 선택할 수 있습니다.",
);
submit(
  "email-start",
  async () => {
    await session.startEmail(
      view === "signup" ? "signup" : "reset",
      element("flow-email").value,
    );
    emailStage = 1;
  },
  "메일에 있는 8자리 확인 코드를 입력해 주세요. 계정 존재 여부는 안내하지 않습니다.",
);
submit(
  "email-verify",
  async () => {
    const code = element("flow-code").value;
    element("flow-code").value = "";
    await session.verifyEmail(code);
    emailStage = 2;
    const notice = session.getSignupNotice();
    if (view === "signup" && !notice) throw new WebSessionBlocked();
    if (notice) {
      element("notice-version").textContent = notice.version;
      element("notice-body").textContent = notice.body;
      element("notice-contact").textContent = notice.contact;
    }
  },
  "메일 확인을 마쳤습니다. 새 비밀번호와 필요한 동의를 확인해 주세요.",
);
submit(
  "email-complete",
  async () => {
    const password = newPassword("flow-password", "flow-confirm");
    const nickname = element("nickname").value;
    const consented = element("signup-consent").checked;
    const loggedIn = await session.completeEmail(password, nickname, consented);
    if (loggedIn) {
      await loadMember();
      view = "security";
    } else view = "login";
  },
  "절차를 완료했습니다. 복구한 경우 새 비밀번호로 다시 로그인해 주세요.",
);
submit(
  "recent-form",
  async () => {
    const identity = member?.identities.find(
      (value) => value.provider === "LOCAL",
    );
    if (!identity) throw new WebSessionBlocked();
    const password = element("recent-password").value;
    element("recent-password").value = "";
    await session.verifyRecent(
      element("security-purpose").value,
      identity.identityKey,
      password,
    );
    recentVerified = true;
  },
  "선택한 목적의 본인 확인을 마쳤습니다. 작업 내용을 확인하고 동의해 주세요.",
);
submit(
  "security-complete",
  async () => {
    const purpose = element("security-purpose").value;
    if (purpose === "DELETE_ACCOUNT") {
      await session.deleteAccount(
        element("security-consent").checked,
        element("deletion-shared").checked,
        element("deletion-irreversible").checked,
      );
      view = "erasure";
      element("erasure-expiry").textContent =
        "영수증 조회 가능 기한: " + session.getErasureReceipt().expiresAt;
      element("erasure-status").textContent =
        "접근이 회수되고 탈퇴가 접수되었습니다. 주 저장소·외부·백업 파기는 아직 조회하지 않았습니다.";
      return;
    }
    const password =
      purpose === "PASSWORD_CHANGE"
        ? newPassword("new-password", "new-confirm")
        : undefined;
    await session.completeRecent(
      purpose,
      password,
      element("security-consent").checked,
    );
    view = "login";
  },
  () =>
    element("security-purpose").value === "DELETE_ACCOUNT"
      ? "접근이 회수되고 탈퇴가 접수되었습니다. 전체 파기 완료는 별도 상태 조회로 확인합니다."
      : "선택한 작업을 완료하고 모든 기기의 로그인을 회수했습니다. 다시 로그인해 주세요.",
);
element("logout").addEventListener(
  "click",
  () =>
    void run(async () => {
      await session.logout();
      view = "login";
    }, "현재 로그인을 회수했습니다."),
);
element("cancel-email").addEventListener("click", () => selectView(view));
element("cancel-recent").addEventListener("click", () =>
  selectView("security"),
);
element("security-purpose").addEventListener("change", () => {
  session.cancelPending();
  recentVerified = false;
  for (const id of ["recent-password", "new-password", "new-confirm"])
    element(id).value = "";
  element("security-consent").checked = false;
  element("deletion-shared").checked = element(
    "deletion-irreversible",
  ).checked = false;
  render();
});
element("erasure-read").addEventListener(
  "click",
  () =>
    void run(async () => {
      element("erasure-status").textContent =
        "현재 파기 상태를 아직 확인하지 못했습니다.";
      const value = await session.getErasureStatus();
      element("erasure-status").textContent =
        "서버 상태: " +
        value.state +
        "\n접근 회수: " +
        value.accessRevoked +
        "\n주 저장소 파기: " +
        value.primaryDone +
        "\n외부 파기: " +
        value.externalDone +
        "\n백업 파기: " +
        value.backupDone +
        "\n" +
        (value.state === "COMPLETE"
          ? "서버가 전체 파기 완료를 확인했습니다."
          : "전체 파기 완료가 아닙니다. 미완료 대상은 별도 확인이 필요합니다.");
    }, "서버가 제공한 파기 상태를 조회했습니다."),
);
element("erasure-close").addEventListener("click", () => {
  session.forgetErasureReceipt();
  element("erasure-status").textContent =
    "이 문서의 영수증을 폐기했습니다. 새로고침이나 이전 주소로 복원할 수 없습니다.";
});
for (const id of ["new-password", "new-confirm"])
  element(id).addEventListener("input", () => {
    element("security-consent").checked = false;
  });
window.addEventListener("hashchange", () => {
  const next = location.hash.slice(1);
  if (["login", "signup", "recover", "security", "erasure"].includes(next))
    selectView(next);
});

try {
  session = createWebSession();
  session.onClear(() => {
    purge();
    if (view === "security") {
      view = "login";
      render();
    }
    element("status").textContent =
      "이 문서의 자격과 입력을 비웠습니다. 필요한 작업을 다시 확인해 주세요.";
  });
  await run(async () => {
    if (await session.restore()) {
      await loadMember();
      if (view === "login" || view === "security") view = "security";
    } else if (view === "security") view = "login";
  }, "계정 상태를 확인했습니다. 필요한 작업을 선택해 주세요.");
} catch (failure) {
  purge();
  element("error").textContent = errorText(failure);
  element("error").hidden = false;
  element("status").textContent = "계정 기능을 사용할 수 없는 환경입니다.";
}
render();
