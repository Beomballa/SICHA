(() => {
  "use strict";
  const screen = document.body.dataset.screen;
  const content = document.getElementById("content");
  const notice = document.getElementById("notice");
  const title = document.getElementById("page-title");
  const dialog = document.getElementById("reauth-dialog");
  const base = "/admin/api/auth";
  const accountsBase = "/admin/api/accounts";
  let epoch = 0;
  let csrf;
  let me;
  let stage;
  let lastButton;
  let navigationSent = false;
  let account;
  let reactivationPreview;
  let accountFilters = {};
  let nextAccountId;
  let accountListRequest = 0;
  let submitOwner;
  let cancelOwner;

  const names = {
    AUTH_LOGIN: "관리자 로그인",
    AUTH_MFA: "로그인 MFA 확인",
    AUTH_ENROLL: "관리자 등록",
    AUTH_PASSWORD_RECOVERY: "비밀번호 복구",
    AUTH_MFA_RECOVERY: "MFA 복구",
    ADMIN_HOME: "대시보드",
    ADMIN_AUTH_MANAGE: "초대와 복구 발급",
    ADMIN_ACCOUNTS: "관리자 계정",
    ADMIN_ACCOUNT_DETAIL: "관리자 계정 상세",
    FORBIDDEN: "접근 권한 없음",
  };
  title.textContent = names[screen];
  const field = (name, label, type = "text", attrs = "") =>
    `<label>${label}<input name="${name}" type="${type}" ${attrs} required></label>`;
  const password = (label = "새 비밀번호") =>
    field("password", label, "password", 'autocomplete="new-password"');
  const otp = () =>
    field(
      "totp",
      "인증 앱 6자리 코드",
      "text",
      'inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code"',
    );
  const section = (heading, body) =>
    `<section><h2 tabindex="-1">${heading}</h2>${body}</section>`;
  const form = (action, fields, label, extra = "") =>
    `<form data-action="${action}">${fields}<div class="actions"><button type="submit">${label}</button>${extra}</div></form>`;
  const cancel = (action, text) =>
    `<button type="button" class="secondary" data-cancel="${action}">${text}</button>`;
  const loginLink = '<a href="/admin/login">로그인 화면</a>';
  function status(message, error = false) {
    notice.textContent = message;
    notice.classList.toggle("error", error);
  }
  /** 확인을 거절하고 화면·제한 단계·영향 메모리를 폐기한다. 반환값은 없으며 기존 응답도 세대로 무효화한다. */
  function clear() {
    AdminUI.cancelConfirmation();
    ++epoch;
    content.replaceChildren();
    stage = undefined;
    reactivationPreview = undefined;
    content.setAttribute("aria-busy", "true");
    if (dialog.open) dialog.close();
  }
  /**
   * 확인을 거절한 뒤 기존 화면 마크업을 교체한다. 사용자 값은 이 인자로 삽입하지 않는다.
   * @param {string} html 기존 내부 화면 생성 함수의 마크업. null은 허용하지 않는다.
   * @param {boolean} heading 첫 제목으로 초점을 옮길지 여부. 기본 true.
   * @returns {void} 현재 콘텐츠와 로딩 표시만 갱신한다.
   */
  function render(html, heading = true) {
    AdminUI.cancelConfirmation();
    content.innerHTML = html;
    content.setAttribute("aria-busy", "false");
    if (heading) content.querySelector("h2")?.focus();
  }
  function codes(values) {
    render(
      section(
        "새 복구 코드 — 이번 화면에서만 표시",
        '<p>안전한 곳에 별도로 보관하세요. 화면을 떠나면 다시 조회할 수 없습니다. 잃어버리면 로그인 후 재인증하여 전체 묶음을 교체해야 합니다.</p><ol id="codes"></ol><a href="/admin/login">보관을 확인하고 로그인</a>',
      ),
    );
    const list = document.getElementById("codes");
    for (const value of values) {
      const item = document.createElement("li");
      item.className = "secret";
      item.textContent = value;
      list.append(item);
    }
    status("완료되었습니다. 복구 코드를 지금 보관하세요.");
  }
  function expiry(value) {
    if (value)
      status(
        `현재 단계 만료: ${new Date(value).toLocaleString("ko-KR")} (서버 상태가 우선합니다).`,
      );
    else status("현재 단계가 확인되었습니다.");
  }
  async function request(method, path, body, apiBase = base) {
    const currentEpoch = epoch;
    const headers = { Accept: "application/json" };
    if (method !== "GET") {
      if (!csrf) await getCsrf();
      headers[csrf.headerName] = csrf.token;
      headers["Content-Type"] = "application/json";
    }
    const response = await fetch(apiBase + path, {
      method,
      headers,
      credentials: "same-origin",
      cache: "no-store",
      ...(method !== "GET" ? { body: JSON.stringify(body ?? {}) } : {}),
    });
    const data = response.status === 204 ? null : await response.json();
    if (currentEpoch !== epoch) throw { stale: true };
    if (!response.ok)
      throw {
        status: response.status,
        code: data?.code,
        retry: response.headers.get("Retry-After"),
      };
    return data;
  }
  async function getCsrf() {
    const response = await fetch(base + "/csrf", {
      credentials: "same-origin",
      cache: "no-store",
    });
    if (!response.ok) throw { status: response.status, code: "CSRF_INVALID" };
    csrf = await response.json();
  }
  async function probe(path) {
    try {
      return await request("GET", path);
    } catch (e) {
      if (e.status === 401) return null;
      throw e;
    }
  }
  function errorText(e) {
    if (!e.status)
      return "통신이 끊겼습니다. 변경 결과는 확인되지 않았습니다. 자동으로 다시 제출하지 않습니다.";
    if (e.code === "AUTH_FAILED")
      return "로그인할 수 없습니다. 입력한 정보를 확인하세요.";
    if (e.code === "INVALID_TOTP")
      return "인증 코드가 올바르지 않거나 이미 사용되었습니다. 새 코드를 확인하세요.";
    if (e.code === "REAUTH_REQUIRED" || e.code === "MANAGE_REAUTH_REQUIRED")
      return "현재 세션에서 재인증이 필요합니다. 재인증 뒤 상태를 확인하고 작업을 다시 실행하세요.";
    if (e.code === "CSRF_INVALID")
      return "요청 확인이 만료되었습니다. 새 확인값을 받은 뒤 직접 다시 실행하세요.";
    if (e.status === 429)
      return `요청이 제한되었습니다. ${e.retry ? `${e.retry}초 뒤` : "잠시 뒤"} 직접 다시 시도하세요.`;
    if (e.status === 409)
      return "서버 상태가 변경되었습니다. 현재 상태를 다시 확인하고 직접 결정하세요.";
    if (e.status === 422 || e.status === 400)
      return "입력값을 확인하세요. 비밀값은 오류 메시지에 표시하지 않습니다.";
    if (e.code === "REVOCATION_UNCONFIRMED")
      return "서버에서 회수가 완료됐는지 확인할 수 없습니다. 관리자에게 확인하세요.";
    if (e.status === 503)
      return "서비스 오류로 결과를 확인할 수 없습니다. 조회 가능한 현재 상태를 확인하고 변경 요청을 자동 재전송하지 마세요.";
    if (e.status === 403) return "이 작업에 접근할 수 없습니다.";
    if (e.status === 401)
      return "인증이 없거나 만료되었습니다. 로그인 또는 새 발급이 필요합니다.";
    return "요청을 완료하지 못했습니다. 현재 상태를 확인하세요.";
  }
  async function restore() {
    clear();
    const id = epoch;
    status("현재 서버 상태를 확인하고 있습니다.");
    try {
      await getCsrf();
      if (id !== epoch) return;
      if (screen === "FORBIDDEN") {
        render(
          section(
            "권한이 없습니다",
            '<p>이 화면은 현재 관리 자격이 필요합니다.</p><a href="/admin">관리자 시작 화면</a>',
          ),
        );
        status("접근 권한이 없습니다.", true);
        return;
      }
      if (screen === "AUTH_LOGIN") {
        me = await probe("/me");
        if (id !== epoch) return;
        if (me) {
          location.replace("/admin");
          return;
        }
        const pending = await probe("/login/status");
        if (id !== epoch) return;
        if (pending) {
          location.replace("/admin/login/mfa");
          return;
        }
        render(
          section(
            "ID와 비밀번호",
            form(
              "login",
              field(
                "loginId",
                "로그인 ID",
                "text",
                'autocomplete="username" minlength="4" maxlength="40"',
              ) +
                field(
                  "password",
                  "현재 비밀번호",
                  "password",
                  'autocomplete="current-password"',
                ),
              "로그인",
            ),
          ),
        );
        status("로그인 정보를 입력하세요.");
      } else if (screen === "AUTH_MFA") {
        const pending = await probe("/login/status");
        if (id !== epoch) return;
        if (!pending) {
          me = await probe("/me");
          if (id !== epoch) return;
          location.replace(me ? "/admin" : "/admin/login");
          return;
        }
        expiry(pending.expiresAt);
        render(
          section(
            "인증 앱 코드",
            "<p>앞자리 0을 포함해 6자리를 입력하세요.</p>" +
              form("mfa", otp(), "확인", cancel("login", "현재 로그인 취소")),
          ),
        );
      } else if (screen === "AUTH_ENROLL") {
        const state = await probe("/enrollment");
        if (id !== epoch) return;
        enrollment(state);
      } else if (
        screen === "AUTH_PASSWORD_RECOVERY" ||
        screen === "AUTH_MFA_RECOVERY"
      ) {
        const state = await probe("/recovery");
        if (id !== epoch) return;
        recovery(state);
      } else {
        me = await request("GET", "/me");
        if (id !== epoch) return;
        if (
          [
            "ADMIN_AUTH_MANAGE",
            "ADMIN_ACCOUNTS",
            "ADMIN_ACCOUNT_DETAIL",
          ].includes(screen) &&
          !me.permissions.includes("MANAGE")
        ) {
          render(
            section("권한이 없습니다", '<a href="/admin">관리자 시작 화면</a>'),
          );
          status("관리 자격이 없습니다.", true);
          return;
        }
        if (screen === "ADMIN_HOME") home();
        else if (screen === "ADMIN_AUTH_MANAGE") manage();
        else if (
          !me.reauthExpiresAt ||
          Date.parse(me.reauthExpiresAt) <= Date.now()
        ) {
          render(
            section(
              "재인증 필요",
              '<p>계정 정보 조회와 변경에는 최근 재인증이 필요합니다.</p><button type="button" data-reauth>재인증</button>',
            ),
          );
          status("재인증 후 현재 상태를 조회하세요.");
        } else if (screen === "ADMIN_ACCOUNTS") await accountList();
        else await accountDetail();
        if (!navigationSent) {
          navigationSent = true;
          fetch("/admin/api/history/navigation", {
            method: "POST",
            credentials: "same-origin",
            cache: "no-store",
            headers: {
              "Content-Type": "application/json",
              [csrf.headerName]: csrf.token,
            },
            body: JSON.stringify({
              eventKey: crypto.randomUUID(),
              screenCode: screen,
            }),
          }).catch(() => {});
        }
      }
    } catch (e) {
      if (id !== epoch) return;
      if (
        e.status === 401 &&
        [
          "ADMIN_HOME",
          "ADMIN_AUTH_MANAGE",
          "ADMIN_ACCOUNTS",
          "ADMIN_ACCOUNT_DETAIL",
        ].includes(screen)
      ) {
        me = undefined;
        account = undefined;
        render(
          section(
            "로그인이 필요합니다",
            "<p>현재 세션으로 변경 결과를 확인할 수 없습니다. 변경 요청을 다시 보내지 말고 재로그인 후 현재 상태를 조회하세요. 관리 자격을 잃었다면 다른 운영자에게 확인하세요.</p>" +
              loginLink,
          ),
        );
        status(errorText(e), true);
        return;
      }
      if (
        ["ADMIN_ACCOUNTS", "ADMIN_ACCOUNT_DETAIL"].includes(screen) &&
        ["REAUTH_REQUIRED", "MANAGE_REAUTH_REQUIRED"].includes(e.code)
      ) {
        render(
          section(
            "재인증 필요",
            '<p>계정 정보 조회와 변경에는 최근 재인증이 필요합니다.</p><button type="button" data-reauth>재인증</button>',
          ),
        );
        status(errorText(e), true);
        return;
      }
      render(
        section(
          "상태를 확인하지 못했습니다",
          '<p>현재 절차를 완료하거나 실패한 것으로 단정하지 않습니다.</p><button type="button" data-refresh>현재 상태 다시 확인</button>',
        ),
      );
      status(errorText(e), true);
    }
  }
  function enrollment(state) {
    stage = state?.stage;
    if (!state) {
      render(
        section(
          "등록 코드 교환",
          "<p>운영자에게 전달받은 코드를 입력하세요. 코드는 이 브라우저에 저장되지 않습니다.</p>" +
            form("enroll-exchange", field("code", "등록 코드"), "코드 확인"),
        ),
      );
      status("유효한 등록 진행이 없습니다. 새 코드를 입력하세요.");
      return;
    }
    const abort = cancel("enrollment", "등록 취소");
    if (stage === "PASSWORD_REQUIRED")
      render(
        section(
          "새 비밀번호",
          form("enroll-password", password(), "비밀번호 설정", abort),
        ),
      );
    else if (stage === "MFA_SETUP_REQUIRED")
      render(
        section(
          "인증 앱 등록",
          "<p>설정 요청 뒤 수동 입력키를 인증 앱에 입력하세요. 외부 QR 서비스는 사용하지 않습니다.</p>" +
            form("enroll-setup", "", "수동 입력키 표시", abort),
        ),
      );
    else if (stage === "MFA_VERIFY_REQUIRED")
      render(
        section(
          "MFA 검증",
          "<p>수동 입력키를 잃었다면 설정 요청으로 미검증 비밀을 다시 확인할 수 있습니다.</p>" +
            form(
              "enroll-verify",
              otp(),
              "코드 검증",
              cancel("enrollment", "등록 취소"),
            ) +
            '<button type="button" class="secondary" data-action-button="enroll-setup">수동 입력키 다시 확인</button>',
        ),
      );
    else if (stage === "READY_TO_COMPLETE")
      render(
        section(
          "등록 완료",
          "<p>완료하면 복구 코드가 한 번만 표시됩니다. 별도로 보관할 준비가 되었는지 확인하세요.</p>" +
            form("enroll-complete", "", "등록 완료", abort),
        ),
      );
    else {
      status("등록 상태를 확인할 수 없습니다.", true);
      return;
    }
    expiry(state.expiresAt);
  }
  function recovery(state) {
    const isMfa = screen === "AUTH_MFA_RECOVERY";
    if (
      state &&
      state.purpose !== (isMfa ? "MFA_RECOVERY" : "PASSWORD_RESET")
    ) {
      render(
        section(
          "다른 복구 절차가 진행 중입니다",
          "<p>기존 복구 목적을 마치거나 명시적으로 취소한 뒤 새 절차를 시작하세요.</p>" +
            cancel("recovery", "진행 중인 복구 취소"),
        ),
      );
      status("기존 복구 자격과 이 화면의 목적이 다릅니다.", true);
      return;
    }
    stage = state?.stage;
    if (!state) {
      if (!isMfa)
        render(
          section(
            "비밀번호 재설정 코드",
            form(
              "password-exchange",
              field("code", "운영 발급 코드"),
              "코드 확인",
            ),
          ),
        );
      else
        render(
          section(
            "MFA 복구 진입",
            "<p>교체가 시작되면 기존 MFA와 세션이 무효화됩니다. 만료하거나 취소해도 이전 MFA로 돌아가지 않으며 운영자 재발급이 필요합니다.</p>" +
              form(
                "mfa-start",
                field(
                  "loginId",
                  "로그인 ID",
                  "text",
                  'autocomplete="username"',
                ) +
                  field(
                    "password",
                    "현재 비밀번호",
                    "password",
                    'autocomplete="current-password"',
                  ) +
                  field("recoveryCode", "보관한 복구 코드"),
                "자가 복구 시작",
              ) +
              "<p>또는 운영자에게 발급받은 코드 사용</p>" +
              form(
                "mfa-exchange",
                field("code", "운영 발급 코드"),
                "운영 코드 확인",
              ),
          ),
        );
      status("진입 방법을 선택하세요.");
      return;
    }
    const abort = cancel("recovery", "복구 취소");
    if (!isMfa)
      render(
        section(
          "새 비밀번호 설정",
          form("password-reset", password(), "비밀번호 재설정", abort),
        ),
      );
    else if (stage === "MFA_SETUP_REQUIRED")
      render(
        section(
          "새 인증 앱 등록",
          "<p>수동 입력키를 인증 앱에 등록하세요. QR 이미지는 제공되지 않습니다.</p>" +
            form("recovery-setup", "", "수동 입력키 표시", abort),
        ),
      );
    else if (stage === "MFA_VERIFY_REQUIRED")
      render(
        section(
          "새 MFA 확인",
          form("recovery-verify", otp(), "코드 검증", abort) +
            '<button type="button" class="secondary" data-action-button="recovery-setup">수동 입력키 다시 확인</button>',
        ),
      );
    else if (stage === "READY_TO_COMPLETE")
      render(
        section(
          "MFA 교체 완료",
          "<p>완료하면 새 복구 코드가 한 번만 표시됩니다. 이전 복구 코드는 무효화됩니다.</p>" +
            form("recovery-complete", "", "새 MFA로 교체", abort),
        ),
      );
    else {
      status("복구 단계가 확인되지 않았습니다.", true);
      return;
    }
    expiry(state.expiresAt);
  }
  function home() {
    const category = document.body.dataset.category || "dashboard";
    const labels = {
      dashboard: "대시보드",
      members: "회원 관리",
      statistics: "통계 관리",
      security: "보안 관리",
      payments: "유료 결제 관리",
    };
    title.textContent = labels[category];
    const descriptions = {
      dashboard: "사건 제작과 운영 업무를 한 곳에서 확인하세요.",
      members: "회원용 인증과 관리자 회원 운영의 연결 범위를 구분합니다.",
      statistics:
        "실제 집계가 연결되기 전에는 수치와 그래프를 표시하지 않습니다.",
      security: "현재 기기와 전체 세션, 복구 자격을 안전하게 관리하세요.",
      payments: "결제 제공자와 거래 API 연결 전의 관리 영역입니다.",
    };
    document.getElementById("page-description").textContent =
      descriptions[category];
    const card = (
      index,
      heading,
      badge,
      body,
      href,
      action,
      connected = false,
    ) =>
      `<article class="workspace-card"><span class="workspace-index">${index}</span><h3>${heading}</h3><span class="ui-badge ${connected ? "ui-badge-success" : "ui-badge-info"}">${badge}</span><p>${body}</p>${href ? `<a class="secondary-link" href="${href}">${action}</a>` : ""}</article>`;
    const permissions =
      '<section class="workspace-note"><h2>나의 작업 자격</h2><p id="permission-list"></p><p class="muted">작업마다 서버가 현재 권한과 사건 접근을 다시 확인합니다.</p><p id="manage-link"></p></section>';
    if (category === "dashboard") {
      render(
        '<div class="workspace-hero"><div><span class="ui-badge ui-badge-info">사건 제작 워크스페이스</span><h2>다음 사건의 시작을,<br>이곳에서 준비하세요.</h2><p class="muted">원고 작성부터 자료 구성, 저장본 확인까지. 플레이어에게 공개되기 전의 제작 흐름을 차분하게 관리합니다.</p><div class="workspace-actions"><a class="primary-link" href="/admin/stories">게임 관리 열기</a><a class="secondary-link" href="/admin?tab=security">내 보안 설정</a></div></div><div class="workspace-context"><h3>오늘의 작업 흐름</h3><p>01 · 사건 목록에서 작업할 초안 선택</p><p>02 · 원고와 자료를 영역별로 명시 저장</p><p>03 · 저장본 확인 후 검수 요청</p><p class="muted">저장과 검수 요청만으로 공개되지 않습니다.</p></div></div>' +
          '<div class="workspace-section-heading"><h2>관리 영역</h2><span class="muted">연결 상태를 기준으로 안내합니다.</span></div><div class="workspace-grid">' +
          card(
            "01 / CONTENT",
            "게임 관리",
            "기존 기능 연결",
            "사건 목록·초안 생성·원고 편집·검수 근거를 관리합니다.",
            "/admin/stories",
            "사건 기록부",
            true,
          ) +
          card(
            "02 / MEMBERS",
            "회원 관리",
            "관리 API 미연동",
            "LOCAL 회원 인증은 구현되어 있으며 회원 목록·운영 제어는 별도 연결이 필요합니다.",
            "/admin?tab=members",
            "회원 영역 확인",
          ) +
          card(
            "03 / ANALYTICS",
            "통계 관리",
            "집계 미연동",
            "회원 활동과 게임 결과 집계는 아직 연결되지 않았습니다.",
            "/admin?tab=statistics",
            "통계 영역 확인",
          ) +
          card(
            "04 / SECURITY",
            "보안 관리",
            "기존 기능 연결",
            "관리자 세션 회수와 복구 코드 교체를 실제 기존 기능으로 처리합니다.",
            "/admin?tab=security",
            "보안 설정",
            true,
          ) +
          card(
            "05 / BILLING",
            "유료 결제 관리",
            "결제 미연동",
            "거래·상품·환불 처리는 제공자 및 API 연결 전입니다.",
            "/admin?tab=payments",
            "결제 영역 확인",
          ) +
          card(
            "06 / EXPERIENCE",
            "플레이어 메인",
            "HTML 디자인 시안",
            "회원 수집이나 게임 실행 없이 사용자 메인 화면의 구성을 확인합니다.",
            "/admin/preview/player-home",
            "메인 시안 보기",
          ) +
          "</div>" +
          permissions,
      );
    } else if (category === "security") {
      render(
        '<div class="security-grid">' +
          section(
            "현재 기기 세션",
            '<span class="ui-badge ui-badge-success">기존 기능 연결</span><p>현재 기기의 관리자 세션을 종료합니다.</p>' +
              form("logout", "", "현재 기기 로그아웃"),
          ) +
          section(
            "전체 기기 회수",
            '<span class="ui-badge ui-badge-info">최근 재인증 필요</span><p>다른 기기를 포함한 모든 관리자 세션을 회수합니다.</p>' +
              form("logout-all", "", "모든 기기 로그아웃"),
          ) +
          section(
            "복구 코드 교체",
            '<span class="ui-badge ui-badge-info">최근 재인증 필요</span><p>기존 미사용 복구 코드를 전부 무효화하고 새 코드를 발급합니다.</p>' +
              form("codes-replace", "", "복구 코드 전체 교체"),
          ) +
          "</div>" +
          permissions,
      );
    } else {
      const areas = {
        members: [
          "회원 운영의 연결 범위",
          "실제 회원 목록과 개인 정보를 이 화면에서 조회하지 않습니다. 관리자 회원 API가 연결되기 전까지 회원 수·활성 상태·가입 추이를 표시하지 않습니다.",
          [
            "01 / DIRECTORY",
            "회원 조회",
            "회원 목록·검색·상태 조회를 위한 관리 데이터 연결 전입니다.",
          ],
          [
            "02 / LIFECYCLE",
            "계정 생애주기",
            "복구·탈퇴·파기 상태는 인증 및 개인정보 처리 계약과 함께 연결해야 합니다.",
          ],
        ],
        statistics: [
          "집계 없는 숫자를 만들지 않습니다",
          "가입·활동·플레이 결과의 집계 원본과 기준 기간이 연결되면 통계를 표시합니다. 미연동 상태를 0건이나 정상 추이로 해석하지 않습니다.",
          [
            "01 / ACTIVITY",
            "활동 집계",
            "집계 기간·대상·갱신 시각을 확인할 원본 API가 필요합니다.",
          ],
          [
            "02 / GAME RESULTS",
            "게임 결과",
            "실제 플레이와 결과 데이터 연결 전입니다. 검수 근거를 사용자 성과로 표시하지 않습니다.",
          ],
        ],
        payments: [
          "결제 운영은 별도 연결이 필요합니다",
          "결제 제공자와 거래 API가 연결되지 않았습니다. 실제 상품 판매·승인·환불을 실행하거나 결제 상태를 추정하지 않습니다.",
          [
            "01 / TRANSACTIONS",
            "거래 내역",
            "승인·취소·환불의 실제 제공자 기록과 운영 권한 연결 전입니다.",
          ],
          [
            "02 / PRODUCTS",
            "유료 상품",
            "상품과 이용 권한의 저장·검증 기준이 연결되기 전입니다.",
          ],
        ],
      };
      const area = areas[category];
      render(
        `<div class="workspace-hero"><div><span class="ui-badge ui-badge-info">관리 데이터 미연동</span><h2>${area[0]}</h2><p class="muted">${area[1]}</p><div class="workspace-actions"><a class="secondary-link" href="/admin">대시보드로 돌아가기</a></div></div><div class="workspace-context"><h3>화면과 기능의 경계</h3><p>탐색과 화면 구성이 적용되어 있습니다.</p><p>운영 데이터·변경 작업은 연결되지 않았습니다.</p><p class="muted">가짜 데이터·성공 처리·자동 요청을 제공하지 않습니다.</p></div></div><div class="workspace-grid">` +
          area
            .slice(2)
            .map(([index, heading, body]) =>
              card(index, heading, "미연동", body),
            )
            .join("") +
          "</div>" +
          permissions,
      );
    }
    document.getElementById("permission-list").textContent = me.permissions
      .length
      ? "현재 작업 자격: " + me.permissions.join(", ")
      : "현재 작업 자격이 없습니다. 담당 운영자에게 권한을 요청하세요.";
    if (me.permissions.includes("MANAGE")) {
      const links = document.getElementById("manage-link");
      for (const [href, label] of [
        ["/admin/auth/manage", "초대와 복구 발급"],
        ["/admin/accounts", "관리자 계정"],
      ]) {
        const link = document.createElement("a");
        link.href = href;
        link.textContent = label;
        links.append(link);
      }
    }
    status("현재 세션을 확인했습니다.");
  }
  function manage() {
    render(
      section(
        "초대 생성",
        "<p>대상 본인을 별도 확인하고 비개인 확인 참조를 입력하세요. registrationKey는 UUID v4를 직접 생성합니다.</p>" +
          form(
            "invite",
            field("registrationKey", "새 registrationKey (UUID v4)") +
              field("loginId", "새 로그인 ID", "text", 'autocomplete="off"') +
              field("verificationRef", "비개인 확인 참조"),
            "초대 생성",
          ),
      ) +
        section(
          "초대 상태 및 변경",
          form(
            "inspect",
            field("registrationKey", "기존 registrationKey"),
            "상태 조회",
          ) +
            "<p>재발급과 취소는 현재 세대를 조회하고 별도로 확인해야 합니다.</p>" +
            form(
              "reissue",
              field("registrationKey", "기존 registrationKey") +
                field("expectedGeneration", "현재 세대", "number", 'min="1"') +
                field("verificationRef", "새 비개인 확인 참조"),
              "확인 후 재발급",
            ) +
            form(
              "revoke",
              field("registrationKey", "취소할 registrationKey") +
                field("expectedGeneration", "현재 세대", "number", 'min="1"'),
              "확인 후 초대 취소",
            ),
        ) +
        section(
          "복구 코드 발급",
          "<p>별도로 확인한 accountKey만 사용하세요. 코드 원문은 이 화면에 한 번만 표시됩니다.</p>" +
            form(
              "issue",
              field("accountKey", "대상 accountKey") +
                '<label for="f-purpose">복구 목적</label><select id="f-purpose" name="purpose"><option value="PASSWORD_RESET">비밀번호 재설정</option><option value="MFA_RECOVERY">MFA 복구</option></select>' +
                field("verificationRef", "비개인 확인 참조"),
              "복구 코드 발급",
            ),
        ),
    );
    status("운영 작업은 최근 재인증과 서버의 현재 자격 검사가 필요합니다.");
  }
  function accountPath() {
    const value = decodeURIComponent(
      location.pathname.slice("/admin/accounts/".length),
    );
    return `/${accountKey(value)}`;
  }
  function accountKey(value) {
    if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value))
      throw { status: 400 };
    return value;
  }
  function accountFields(view) {
    const labels = {
      accountKey: "계정 키",
      activeYn: "활성",
      enrolled: "등록 완료",
      mfaState: "MFA 상태",
      permissions: "설정된 작업 자격",
      editRev: "수정번호",
      createdAt: "생성 시각",
      updatedAt: "수정 시각",
    };
    const list = document.createElement("dl");
    list.className = "account-fields";
    for (const [fieldName, label] of Object.entries(labels)) {
      const term = document.createElement("dt");
      term.textContent = label;
      const value = document.createElement("dd");
      const raw = view[fieldName];
      value.textContent =
        fieldName === "permissions"
          ? raw?.join(", ") || "없음"
          : typeof raw === "boolean"
            ? raw
              ? "예"
              : "아니요"
            : (raw ?? "없음");
      list.append(term, value);
    }
    return list;
  }
  async function accountList() {
    render(
      section(
        "계정 검색",
        form(
          "account-filter",
          '<label>정확한 accountKey (선택)<input name="accountKey" type="text" autocomplete="off"></label>' +
            '<label>활성 상태<select name="activeYn"><option value="">전체</option><option value="true">활성</option><option value="false">비활성</option></select></label>' +
            '<label>등록 상태<select name="enrolled"><option value="">전체</option><option value="true">완료</option><option value="false">미완료</option></select></label>' +
            '<label>작업 자격<select name="permission"><option value="">전체</option><option>CREATE</option><option>REVIEW</option><option>PUBLISH</option><option>MANAGE</option></select></label>',
          "검색",
        ),
      ) +
        section(
          "검색 결과",
          '<p>최근 생성 순서입니다. 페이지 사이에 상태가 변경될 수 있습니다.</p><div id="account-rows"></div><button type="button" class="secondary" data-next hidden>다음 페이지</button>',
        ) +
        '<p><a href="/admin/auth/manage">초대와 복구 발급</a></p>',
    );
    const filters = content.querySelector('[data-action="account-filter"]');
    for (const [name, value] of Object.entries(accountFilters))
      filters.elements[name].value = value;
    await loadAccounts(false);
  }
  async function loadAccounts(append) {
    const id = epoch;
    const requestId = ++accountListRequest;
    const params = new URLSearchParams({ size: "20", ...accountFilters });
    if (append && nextAccountId) params.set("afterId", nextAccountId);
    status("계정 목록을 조회하고 있습니다.");
    const data = await request("GET", `?${params}`, undefined, accountsBase);
    if (id !== epoch || requestId !== accountListRequest) return;
    const rows = document.getElementById("account-rows");
    if (!append) rows.replaceChildren();
    for (const view of data.items) {
      const row = document.createElement("article");
      row.className = "account-row";
      const link = document.createElement("a");
      link.href = `/admin/accounts/${encodeURIComponent(view.accountKey)}`;
      link.textContent = view.accountKey;
      const summary = document.createElement("p");
      summary.textContent = `${view.activeYn ? "활성" : "비활성"} · ${view.enrolled ? "등록 완료" : "미등록"} · ${view.mfaState} · 자격: ${view.permissions.join(", ") || "없음"}`;
      row.append(link, summary);
      rows.append(row);
    }
    if (!rows.childElementCount) {
      const empty = document.createElement("p");
      empty.textContent = "조건에 맞는 계정이 없습니다.";
      rows.append(empty);
    }
    nextAccountId = data.hasNext ? data.nextAfterId : null;
    content.querySelector("[data-next]").hidden = !nextAccountId;
    status(
      "현재 페이지를 조회했습니다. 변경 승인 전에는 계정 상세의 최신 상태를 확인하세요.",
    );
  }
  /**
   * 이전 영향 동의를 거절하고 경로의 계정 최신 상태를 표시한다.
   * @returns {Promise<void>} 계정 상태와 현재 폼을 교체한다.
   * @throws {object} 잘못된 계정 경로나 기존 API 조회 오류는 호출자가 처리한다.
   */
  async function accountDetail() {
    const path = accountPath();
    AdminUI.cancelConfirmation();
    reactivationPreview = undefined;
    account = await request("GET", path, undefined, accountsBase);
    render(
      section(
        "현재 계정 상태",
        '<div id="account-state"></div><p>비활성·미등록 계정에 설정된 자격은 현재 실행할 수 없습니다. 변경 시 기존 인증 자격은 회수될 수 있습니다.</p><p><a href="/admin/accounts">계정 목록</a></p>',
      ) +
        section(
          "작업 자격 변경",
          "<p>부여는 활성·등록 완료·MFA READY 계정에만 가능합니다. 회수는 별도 작업입니다.</p>" +
            accountMutation("account-grant", "작업 자격 부여", [
              "ASSIGNMENT_CHANGE",
              "ACCESS_REVIEW",
            ]) +
            accountMutation("account-revoke", "작업 자격 회수", [
              "ASSIGNMENT_CHANGE",
              "ACCESS_REVIEW",
              "OFFBOARDING",
              "INCIDENT",
            ]),
        ) +
        section(
          "활성 상태 변경",
          "<p>비활성화해도 자격과 사건 관계는 삭제되지 않습니다.</p>" +
            form(
              "account-deactivate",
              reasonFields(["OFFBOARDING", "INCIDENT"]),
              "계정 비활성화",
            ),
        ) +
        (!account.activeYn
          ? section(
              "재활성화 영향 확인",
              "<p>기존 작업 자격과 사건 소유·접근 관계가 다시 적용될 수 있습니다. 전체 범위를 조회하고 별도로 확인하세요.</p>" +
                form("account-preview", "", "현재 영향 조회") +
                '<div id="reactivation-impact" aria-live="polite"></div>' +
                `<div id="reactivation-confirm" hidden>${form(
                  "account-reactivate",
                  reasonFields(["RETURN_TO_WORK"]) +
                    '<label><input type="checkbox" name="confirmImpact" required> 현재 자격과 전체 관계 영향을 확인했습니다.</label>',
                  "계정 재활성화",
                )}</div>`,
            )
          : ""),
    );
    document.getElementById("account-state").append(accountFields(account));
    content
      .querySelector('[data-action="account-grant"]')
      .closest("fieldset").disabled =
      !account.activeYn || !account.enrolled || account.mfaState !== "READY";
    content.querySelector('[data-action="account-deactivate"]').hidden =
      !account.activeYn;
    status("최신 계정 상태입니다. 수정번호는 변경 요청에 그대로 사용합니다.");
  }
  /**
   * 이전 동의를 거절하고 새 재활성 영향과 계정 기준을 표시한다.
   * @param {object} preview 기존 서버 계약의 계정·영향 해시·관계 표본. null은 허용하지 않는다.
   * @returns {void} 영향 확인 체크를 초기화하며 조회만으로 변경하지 않는다.
   */
  function showReactivationPreview(preview) {
    AdminUI.cancelConfirmation();
    reactivationPreview = preview;
    account = preview.account;
    document
      .getElementById("account-state")
      .replaceChildren(accountFields(account));
    const container = document.getElementById("reactivation-impact");
    const summary = document.createElement("p");
    summary.textContent = `소유 사건 ${preview.ownedCount}건, 활성 사건 접근 ${preview.accessCount}건. 재활성 후 자격증명 조치: ${preview.nextCredentialAction}.`;
    const scope = document.createElement("p");
    scope.textContent = preview.truncated
      ? "표본은 최대 20건입니다. 아래에 없는 관계를 포함한 전체 영향 범위를 확인해야 합니다."
      : "현재 잔존 관계 전체를 표시합니다.";
    const digest = document.createElement("p");
    digest.className = "impact-hash";
    digest.textContent = `전체 영향 해시: ${preview.impactHash}`;
    const list = document.createElement("ul");
    for (const relation of preview.relationSample) {
      const item = document.createElement("li");
      item.textContent = `${relation.storyCode} · ${relation.relation} · ${relation.activeYn ? "활성" : "비활성"}`;
      list.append(item);
    }
    container.replaceChildren(summary, scope, digest, list);
    const confirmation = document.getElementById("reactivation-confirm");
    confirmation.querySelector('[name="confirmImpact"]').checked = false;
    confirmation.hidden = false;
    status(
      "현재 전체 영향의 해시와 제한된 표본을 조회했습니다. 변동 시 새 미리보기를 확인하세요.",
    );
  }
  function reasonFields(reasons) {
    return (
      `<label>변경 사유<select name="reasonCode" required>${reasons.map((value) => `<option value="${value}">${value}</option>`).join("")}</select></label>` +
      field(
        "verificationRef",
        "별도 확인한 비개인 참조",
        "text",
        'pattern="[A-Za-z0-9_-]{8,64}" minlength="8" maxlength="64" autocomplete="off"',
      )
    );
  }
  function accountMutation(action, label, reasons) {
    const choices = ["CREATE", "REVIEW", "PUBLISH", "MANAGE"]
      .map(
        (value) =>
          `<label class="permission-choice"><input type="checkbox" name="permissions" value="${value}">${value}</label>`,
      )
      .join("");
    return `<fieldset><legend>${label}</legend>${form(action, `<fieldset><legend>작업 자격 (1개 이상)</legend><div class="permission-choices">${choices}</div></fieldset>` + reasonFields(reasons), label)}</fieldset>`;
  }
  function showSetup(data) {
    if (stage !== "MFA_SETUP_REQUIRED" && stage !== "MFA_VERIFY_REQUIRED")
      return;
    stage = "MFA_VERIFY_REQUIRED";
    const target =
      screen === "AUTH_ENROLL" ? "enroll-verify" : "recovery-verify";
    const cancelAction = screen === "AUTH_ENROLL" ? "enrollment" : "recovery";
    render(
      section(
        "인증 앱에 수동 등록",
        '<p>이 키는 현재 미검증 단계에서만 볼 수 있습니다. QR 이미지는 제공되지 않습니다.</p><p id="setup-secret" class="secret"></p>' +
          form(target, otp(), "코드 검증", cancel(cancelAction, "절차 취소")),
      ),
    );
    document.getElementById("setup-secret").textContent = data.secret;
    expiry(data.expiresAt);
  }
  function payload(formElement) {
    return Object.fromEntries(new FormData(formElement));
  }
  function validKey(value) {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      value,
    );
  }
  /**
   * 확인된 폼 행동을 기존 API 계약대로 실행하며 이전 쓰기를 자동 재시도하지 않는다.
   * @param {string} action 현재 폼의 기존 행동 코드. 계정 행동은 호출자가 계정·영향 기준을 검증한다.
   * @param {object} values 첫 await 전에 복사한 폼 값과 선택 자격 배열. null은 허용하지 않는다.
   * @returns {Promise<void>} 기존 성공 표시·후속 조회·화면 이동을 수행한다.
   * @throws {object} 입력 검사와 기존 API 오류를 호출자의 고정 안내로 전달한다.
   */
  async function execute(action, values) {
    switch (action) {
      case "account-filter": {
        if (values.accountKey) accountKey(values.accountKey);
        accountFilters = Object.fromEntries(
          Object.entries(values).filter(([, value]) => value !== ""),
        );
        nextAccountId = undefined;
        await loadAccounts(false);
        return;
      }
      case "account-preview": {
        AdminUI.cancelConfirmation();
        reactivationPreview = undefined;
        document.getElementById("reactivation-confirm").hidden = true;
        showReactivationPreview(
          await request(
            "GET",
            accountPath() + "/reactivation-preview",
            undefined,
            accountsBase,
          ),
        );
        return;
      }
      case "account-grant":
      case "account-revoke":
      case "account-deactivate":
      case "account-reactivate": {
        if (
          (action === "account-grant" || action === "account-revoke") &&
          !values.permissions?.length
        )
          throw { status: 400 };
        if (
          action === "account-reactivate" &&
          (!reactivationPreview || values.confirmImpact !== "on")
        )
          throw { status: 400 };
        const suffix = {
          "account-grant": "/permissions/grant",
          "account-revoke": "/permissions/revoke",
          "account-deactivate": "/deactivate",
          "account-reactivate": "/reactivate",
        }[action];
        const body = {
          expectedRev:
            action === "account-reactivate"
              ? reactivationPreview.account.editRev
              : account.editRev,
          reasonCode: values.reasonCode,
          verificationRef: values.verificationRef,
        };
        if (values.permissions) body.permissions = values.permissions;
        if (action === "account-reactivate") {
          body.impactHash = reactivationPreview.impactHash;
          reactivationPreview = undefined;
          document.getElementById("reactivation-confirm").hidden = true;
        }
        const result = await request(
          "POST",
          accountPath() + suffix,
          body,
          accountsBase,
        );
        account = undefined;
        if (result.nextAction === "LOGIN") {
          me = undefined;
          csrf = undefined;
          accountFilters = {};
          nextAccountId = undefined;
          clear();
          location.assign("/admin/login");
          return;
        }
        try {
          await accountDetail();
          status(
            result.changed
              ? `변경 완료. 후속 자격증명 조치: ${result.credentialAction}. 감사 상태: ${result.auditStatus}.`
              : "현재 상태에 이미 반영되어 변경되지 않았습니다.",
          );
        } catch (e) {
          render(
            section(
              "변경 응답을 받았습니다",
              '<p>최신 계정 상태 조회에 실패했습니다. 변경 요청은 다시 보내지 마세요.</p><button type="button" data-refresh>현재 상태 다시 확인</button>',
            ),
          );
          status(
            `변경 응답: ${result.changed ? "변경됨" : "변경 없음"}, 감사 상태: ${result.auditStatus}. ${errorText(e)}`,
            true,
          );
        }
        return;
      }
      case "login":
        await request("POST", "/login", values);
        location.assign("/admin/login/mfa");
        return;
      case "mfa":
        await request("POST", "/login/mfa", values);
        location.assign("/admin");
        return;
      case "enroll-exchange":
        enrollment(await request("POST", "/enrollment/exchange", values));
        return;
      case "enroll-password":
        enrollment(await request("PUT", "/enrollment/password", values));
        return;
      case "enroll-setup":
        showSetup(await request("POST", "/enrollment/mfa/setup", {}));
        return;
      case "enroll-verify":
        enrollment(await request("POST", "/enrollment/mfa/verify", values));
        return;
      case "enroll-complete":
        codes(
          (await request("POST", "/enrollment/complete", {})).recoveryCodes,
        );
        return;
      case "password-exchange":
        recovery(
          await request("POST", "/recovery/exchange", {
            ...values,
            purpose: "PASSWORD_RESET",
          }),
        );
        return;
      case "mfa-exchange":
        recovery(
          await request("POST", "/recovery/exchange", {
            ...values,
            purpose: "MFA_RECOVERY",
          }),
        );
        return;
      case "mfa-start":
        recovery(
          await request("POST", "/recovery/mfa/start", {
            ...values,
            recoveryCode: values.recoveryCode.replace(/[-\s]/g, ""),
          }),
        );
        return;
      case "password-reset":
        await request("POST", "/recovery/password", values);
        render(
          section(
            "비밀번호 재설정 완료",
            `<p>기존 MFA는 변경되지 않았습니다. ${loginLink}에서 로그인하세요.</p>`,
          ),
        );
        status("재설정이 완료되었습니다.");
        return;
      case "recovery-setup":
        showSetup(await request("POST", "/recovery/mfa/setup", {}));
        return;
      case "recovery-verify":
        recovery({
          ...(await request("POST", "/recovery/mfa/verify", values)),
          purpose: "MFA_RECOVERY",
        });
        return;
      case "recovery-complete":
        codes(
          (await request("POST", "/recovery/mfa/complete", {})).recoveryCodes,
        );
        return;
      case "logout":
        await request("POST", "/logout", {});
        location.assign("/admin/login");
        return;
      case "logout-all":
        await request("POST", "/logout-all", {});
        location.assign("/admin/login");
        return;
      case "codes-replace":
        codes((await request("POST", "/mfa/recovery-codes", {})).recoveryCodes);
        return;
      case "inspect":
        await inspect(values.registrationKey);
        return;
      case "invite":
        if (!validKey(values.registrationKey)) throw { status: 400 };
        issueResult(
          "새 초대 코드",
          await request("POST", "/invitations", values),
        );
        return;
      case "reissue":
        issueResult(
          "재발급 코드",
          await request(
            "POST",
            `/invitations/${key(values.registrationKey)}/reissue`,
            {
              expectedGeneration: Number(values.expectedGeneration),
              verificationRef: values.verificationRef,
            },
          ),
        );
        return;
      case "revoke":
        await request("DELETE", `/invitations/${key(values.registrationKey)}`, {
          expectedGeneration: Number(values.expectedGeneration),
        });
        status("초대 취소 요청이 확인되었습니다.");
        return;
      case "issue":
        issueResult(
          "복구 발급 코드",
          await request("POST", "/recovery-codes", values),
        );
        return;
    }
  }
  function key(value) {
    if (!validKey(value)) throw { status: 400 };
    return value;
  }
  async function inspect(value) {
    const data = await request("GET", `/invitations/${key(value)}`);
    status(
      `초대 상태: ${data.status}, 현재 세대: ${data.generation}. 코드 원문은 재조회할 수 없습니다.`,
    );
  }
  function issueResult(label, data) {
    status(`${label}가 발급되었습니다. 원문은 다시 조회할 수 없습니다.`);
    content.querySelectorAll(".secret").forEach((node) => node.remove());
    const result = document.createElement("section");
    const heading = document.createElement("h2");
    heading.textContent = "이번 화면에서만 표시되는 코드";
    const value = document.createElement("p");
    value.className = "secret";
    value.textContent = `${label}: ${data.code}`;
    result.append(heading, value);
    content.append(result);
  }
  function needReauth(action) {
    return (
      [
        "logout-all",
        "codes-replace",
        "invite",
        "reissue",
        "revoke",
        "issue",
        "inspect",
        "account-grant",
        "account-revoke",
        "account-deactivate",
        "account-preview",
        "account-reactivate",
        "account-filter",
      ].includes(action) &&
      (!me?.reauthExpiresAt || Date.parse(me.reauthExpiresAt) <= Date.now())
    );
  }
  /**
   * 기존 변경 동의를 거절한 뒤 별도 재인증을 연다. 이전 변경을 자동 실행하지 않는다.
   * @param {HTMLButtonElement} button 재인증 뒤 돌아갈 현재 행동 버튼. null은 허용하지 않는다.
   * @returns {void} 비밀번호 입력으로 초점을 이동한다.
   */
  function openReauth(button) {
    AdminUI.cancelConfirmation();
    lastButton = button;
    document.getElementById("reauth-error").textContent = "";
    dialog.showModal();
    document.getElementById("reauth-password").focus();
  }
  dialog.addEventListener("close", () => {
    document.getElementById("reauth-form").reset();
    document.getElementById("reauth-error").textContent = "";
    lastButton?.focus();
    lastButton = null;
  });
  document
    .getElementById("reauth-close")
    .addEventListener("click", () => dialog.close());
  document
    .getElementById("reauth-form")
    .addEventListener("submit", async (event) => {
      event.preventDefault();
      const button = dialog.querySelector('[type="submit"]');
      button.disabled = true;
      try {
        const data = await request(
          "POST",
          "/reauth",
          payload(event.currentTarget),
        );
        csrf = undefined;
        me = await request("GET", "/me");
        if (
          [
            "ADMIN_AUTH_MANAGE",
            "ADMIN_ACCOUNTS",
            "ADMIN_ACCOUNT_DETAIL",
          ].includes(screen) &&
          !me.permissions.includes("MANAGE")
        ) {
          dialog.close();
          await restore();
          return;
        }
        dialog.close();
        if (screen === "ADMIN_ACCOUNTS" || screen === "ADMIN_ACCOUNT_DETAIL") {
          await restore();
          return;
        }
        status(
          `재인증 완료 (${new Date(data.reauthExpiresAt).toLocaleString("ko-KR")}). 현재 상태를 다시 확인하고 작업 버튼을 직접 누르세요.`,
        );
      } catch (e) {
        document.getElementById("reauth-error").textContent = errorText(e);
        if (e.status === 401) {
          dialog.close();
          clear();
          status("세션이 만료되었습니다. 로그인하세요.", true);
          render(section("로그인이 필요합니다", loginLink));
        }
      } finally {
        button.disabled = false;
      }
    });
  /**
   * 현재 폼·계정·영향에 결속한 단일 제출만 처리하며 중복 호출은 소유 잠금을 해제하지 않는다.
   * @param {SubmitEvent} event 현재 콘텐츠의 제출 이벤트. 폼·값·버튼은 비동기 대기 전에 복사한다.
   * @returns {Promise<void>} 취소·오래된 동의는 무변경, 오류는 기존 고정 안내로 처리한다.
   */
  content.addEventListener("submit", async (event) => {
    const formElement = event.target.closest("form[data-action]");
    if (!formElement) return;
    event.preventDefault();
    const action = formElement.dataset.action;
    const button = formElement.querySelector('[type="submit"]');
    if (submitOwner || cancelOwner || button.disabled) return;
    if (needReauth(action)) {
      openReauth(button);
      return;
    }
    const id = epoch;
    const currentAccount = account;
    const accountRev = account?.editRev;
    const preview = reactivationPreview;
    const previewRev = preview?.account.editRev;
    const impactHash = preview?.impactHash;
    const currentStage = stage;
    const values = payload(formElement);
    if (action === "account-grant" || action === "account-revoke") {
      values.permissions = [
        ...formElement.querySelectorAll('[name="permissions"]:checked'),
      ].map((input) => input.value);
    }
    const owner = {};
    submitOwner = owner;
    try {
      if (
        [
          "logout-all",
          "codes-replace",
          "reissue",
          "revoke",
          "invite",
          "issue",
          "enroll-complete",
          "recovery-complete",
          "account-grant",
          "account-revoke",
          "account-deactivate",
          "account-reactivate",
        ].includes(action) &&
        !(await AdminUI.confirm({
          title: button.textContent.trim(),
          message:
            "현재 계정 상태와 변경 영향을 확인했습니까? 변경 요청은 자동으로 다시 보내지 않습니다.",
          confirmLabel: button.textContent.trim(),
        }))
      )
        return;
      if (
        id !== epoch ||
        submitOwner !== owner ||
        stage !== currentStage ||
        !formElement.isConnected ||
        !content.contains(formElement) ||
        formElement.dataset.action !== action ||
        formElement.closest("[hidden]") ||
        button.disabled ||
        !button.isConnected ||
        (action.startsWith("account-") &&
          (account !== currentAccount || account?.editRev !== accountRev)) ||
        (action === "account-reactivate" &&
          (reactivationPreview !== preview ||
            !preview ||
            preview.account.editRev !== previewRev ||
            preview.impactHash !== impactHash))
      )
        return;
      if (needReauth(action)) {
        openReauth(button);
        return;
      }
      if (screen === "ADMIN_AUTH_MANAGE")
        content
          .querySelectorAll(".secret")
          .forEach((node) => node.closest("section")?.remove());
      button.disabled = true;
      status(
        "요청 처리 중입니다. 응답이 사라져도 변경을 자동 반복하지 않습니다.",
      );
      await execute(action, values);
      if (id !== epoch) return;
      if (!action.startsWith("account-")) formElement.reset();
    } catch (e) {
      if (id !== epoch) return;
      formElement
        .querySelectorAll(
          'input[type="password"], input[name="totp"], input[name="code"], input[name="recoveryCode"]',
        )
        .forEach((input) => {
          input.value = "";
        });
      status(errorText(e), true);
      if (
        (!e.status || e.status === 503 || e.status === 409) &&
        !content.querySelector("[data-refresh]")
      ) {
        const refresh = document.createElement("button");
        refresh.type = "button";
        refresh.className = "secondary";
        refresh.dataset.refresh = "";
        refresh.textContent = "현재 서버 상태 조회";
        content.append(refresh);
      }
      if (e.code === "CSRF_INVALID") csrf = undefined;
      if (
        e.status === 401 &&
        [
          "ADMIN_HOME",
          "ADMIN_AUTH_MANAGE",
          "ADMIN_ACCOUNTS",
          "ADMIN_ACCOUNT_DETAIL",
        ].includes(screen)
      ) {
        me = undefined;
        account = undefined;
        clear();
        render(section("로그인이 필요합니다", loginLink));
      }
    } finally {
      if (submitOwner === owner) {
        submitOwner = undefined;
        if (button.isConnected) button.disabled = false;
      }
    }
  });
  /**
   * 제한 자격 취소 동의를 현재 단계·버튼·경로에 결속하고 별도 소유 잠금으로 중복을 거절한다.
   * @param {MouseEvent} event 기존 조회·재인증·설정·취소 버튼의 클릭. 대상은 대기 전에 복사한다.
   * @returns {Promise<void>} 취소 성공만 상태를 복원하며 오래된 응답은 현재 화면을 바꾸지 않는다.
   */
  content.addEventListener("click", async (event) => {
    if (event.target.matches("[data-refresh]")) {
      await restore();
      return;
    }
    if (event.target.matches("[data-reauth]")) {
      openReauth(event.target);
      return;
    }
    if (event.target.matches("[data-next]")) {
      const button = event.target;
      button.disabled = true;
      try {
        await loadAccounts(true);
      } catch (e) {
        status(errorText(e), true);
      } finally {
        button.disabled = false;
      }
      return;
    }
    const action = event.target.dataset.cancel;
    const setup = event.target.dataset.actionButton;
    if (setup) {
      const button = event.target;
      if (submitOwner || cancelOwner || button.disabled) return;
      const id = epoch;
      const owner = {};
      submitOwner = owner;
      button.disabled = true;
      try {
        await execute(setup, {});
      } catch (e) {
        if (id !== epoch) return;
        status(errorText(e), true);
      } finally {
        if (submitOwner === owner) {
          submitOwner = undefined;
          if (button.isConnected) button.disabled = false;
        }
      }
      return;
    }
    if (!action || cancelOwner || submitOwner || event.target.disabled) return;
    const path =
      action === "login"
        ? "/login"
        : action === "enrollment"
          ? "/enrollment"
          : "/recovery";
    const button = event.target;
    const id = epoch;
    const currentStage = stage;
    const container = button.closest("form") || content;
    const owner = {};
    cancelOwner = owner;
    try {
      if (
        !(await AdminUI.confirm({
          title: button.textContent.trim(),
          message: "현재 제한 자격을 취소합니다. 계속하시겠습니까?",
          confirmLabel: "제한 자격 취소",
        }))
      )
        return;
      if (
        id !== epoch ||
        currentStage !== stage ||
        cancelOwner !== owner ||
        !button.isConnected ||
        !content.contains(button) ||
        !container.isConnected ||
        !container.contains(button) ||
        button.dataset.cancel !== action ||
        button.closest("[hidden]") ||
        button.disabled
      )
        return;
      button.disabled = true;
      await request("DELETE", path, {});
      if (id !== epoch || currentStage !== stage || !button.isConnected) return;
      await restore();
    } catch (e) {
      if (id !== epoch) return;
      status(errorText(e), true);
    } finally {
      if (cancelOwner === owner) {
        cancelOwner = undefined;
        if (button.isConnected) button.disabled = false;
      }
    }
  });
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) restore();
  });
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) restore();
  });
  restore();
})();
