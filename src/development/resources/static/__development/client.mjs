/** 합성 회원 자격은 현재 호출 안에서만 소비하고 기존 인증 모듈에 세션 발급을 맡긴다. */
const status = document.querySelector("#development-status");
const error = document.querySelector("#error");
let sessionPromise;
let selection = 0;

/** 모듈·브라우저 설정 오류도 호출자의 오류 처리로 전달한다. */
function session() {
  sessionPromise ??= import("/player-common/web-session.mjs").then(
    ({ createWebSession }) => createWebSession(window),
  );
  return sessionPromise;
}

/** 서버·인증 실패를 비밀이나 원시 예외 없이 정직하게 표시한다. */
function showFailure(message) {
  if (!error) return;
  error.textContent = message;
  error.hidden = false;
}

/**
 * 정확한 POST 응답만 일회 소비한다. 실패 시 현재 화면과 다른 입력을 유지한다.
 * @param {HTMLButtonElement} button 선택한 합성 회원 버튼.
 */
async function enterMember(button) {
  const attempt = ++selection;
  button.disabled = true;
  button.setAttribute("aria-busy", "true");
  let credentials;
  try {
    if (!status || !error || status.dataset.ready !== "true") {
      showFailure(
        "개발 계정 준비 상태를 확인할 수 없습니다. 서버 준비 상태를 확인하세요.",
      );
      return;
    }
    error.hidden = true;
    const auth = await session();
    if (attempt !== selection) return;
    const response = await fetch("/__development/member-credentials", {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      headers: {
        "Content-Type": "application/json",
        "X-Sicha-Development": "1",
      },
      body: JSON.stringify({ account: button.dataset.account }),
    });
    if (attempt !== selection) return;
    if (!response.ok) {
      showFailure(
        `개발 자격 요청이 거절되었습니다 (HTTP ${response.status}). 서버 준비·동일 출처 설정을 확인하세요.`,
      );
      return;
    }
    if (
      !response.headers
        .get("Content-Type")
        ?.toLowerCase()
        .startsWith("application/json")
    ) {
      throw new Error("DEVELOPMENT_RESPONSE_REJECTED");
    }
    credentials = await response.json();
    if (attempt !== selection) return;
    if (
      !credentials ||
      typeof credentials !== "object" ||
      Array.isArray(credentials) ||
      Object.keys(credentials).length !== 2 ||
      typeof credentials.email !== "string" ||
      typeof credentials.password !== "string" ||
      !credentials.email ||
      !credentials.password
    ) {
      throw new Error("DEVELOPMENT_RESPONSE_REJECTED");
    }
    try {
      await auth.login(credentials.email, credentials.password);
    } finally {
      credentials = null;
    }
    if (attempt !== selection) return;
    await auth.getCurrentMember();
    if (attempt !== selection) return;
    window.location.assign("/play");
  } catch (_) {
    if (attempt === selection) {
      showFailure(
        "회원 인증 또는 브라우저 설정을 확인할 수 없습니다. 자동 재시도하지 않았습니다. HTTPS·Web Locks·저장소 설정과 서버 상태를 확인하거나 원래 회원 계정 화면을 이용하세요.",
      );
    }
  } finally {
    credentials = null;
    button.disabled = false;
    button.removeAttribute("aria-busy");
  }
}

for (const button of document.querySelectorAll("button[data-account]")) {
  button.addEventListener("click", () => {
    void enterMember(button);
  });
}

// 관리자는 fetch로 흉내 내지 않고 브라우저의 실제 폼·세션 쿠키 흐름을 사용한다.
document
  .querySelector("#administrator-entry")
  ?.addEventListener("submit", (event) => {
    const button = event.submitter;
    if (button instanceof HTMLButtonElement) {
      button.disabled = true;
      button.setAttribute("aria-busy", "true");
    }
  });

// 뒤로 가기 캐시 복원 시 완료된 폼 요청의 버튼을 영구 잠금하지 않는다.
window.addEventListener("pageshow", () => {
  const button = document.querySelector("#administrator-entry button");
  if (button) {
    button.disabled = false;
    button.removeAttribute("aria-busy");
  }
});
