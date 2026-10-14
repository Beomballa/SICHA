import {
  createWebApi,
  UUID4,
  TOKEN,
  validateBrowser,
  WebApiError,
} from "./web-api.mjs";

const KEY = "sicha.player.auth.v1";
const LOCK = "sicha-player-auth-v1";
const BASE = "/api/member/browser-auth";
const PURPOSES = new Set(["PASSWORD_CHANGE", "LOGOUT_ALL", "DELETE_ACCOUNT"]);

/** 닫힌 응답 필드와 서버 요청 식별자를 확인한다. */
function fields(value, names) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).length !== names.length ||
    !names.every((name) => Object.hasOwn(value, name)) ||
    typeof value.requestId !== "string" ||
    !UUID4.test(value.requestId)
  )
    throw new WebSessionBlocked();
}

/** 서버 UTC 기한만 채택하며 과거·해석 불가 시각은 거절한다. */
function expiry(value) {
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value) ||
    !Number.isFinite(Date.parse(value)) ||
    Date.parse(value) <= Date.now()
  )
    throw new WebSessionBlocked();
  return Date.parse(value);
}

/** 불확실한 인증을 자동 재전송하지 않고 명시 로그인에서만 회복하는 고정 오류다. */
export class WebSessionBlocked extends Error {
  constructor() {
    super("인증 결과를 확인할 수 없습니다. 다시 로그인해 주세요.");
  }
}

/** 비밀 없는 표지의 정확한 필드·세대·명령만 수락한다. */
export function parseMarker(raw) {
  if (raw === null) return null;
  try {
    const value = JSON.parse(raw);
    const operations = [
      "ready",
      "login",
      "refresh",
      "logout",
      "signup",
      "blocked",
    ];
    const fields =
      value?.operation === "ready"
        ? ["format", "epoch", "operation"]
        : ["format", "epoch", "operation", "attempt"];
    if (
      !value ||
      typeof value !== "object" ||
      Array.isArray(value) ||
      value.format !== 1 ||
      typeof value.epoch !== "string" ||
      !UUID4.test(value.epoch) ||
      !operations.includes(value.operation) ||
      Object.keys(value).length !== fields.length ||
      !fields.every((field) => Object.hasOwn(value, field)) ||
      (value.operation !== "ready" &&
        (typeof value.attempt !== "string" || !UUID4.test(value.attempt)))
    )
      throw new WebSessionBlocked();
    return value;
  } catch (_) {
    throw new WebSessionBlocked();
  }
}

/** 서버의 일곱 access 필드만 검사하며 refresh 원문과 만료된 자격은 거절한다. */
export function parseAccess(value, epoch, now = Date.now()) {
  const fields = [
    "tokenType",
    "accessToken",
    "accessExpiresAt",
    "sessionKey",
    "sessionAbsoluteExpiresAt",
    "webEpoch",
    "requestId",
  ];
  if (
    !value ||
    Object.keys(value).length !== fields.length ||
    !fields.every((field) => Object.hasOwn(value, field)) ||
    value.tokenType !== "Bearer" ||
    value.webEpoch !== epoch ||
    typeof value.accessToken !== "string" ||
    !TOKEN.test(value.accessToken) ||
    !["sessionKey", "webEpoch", "requestId"].every(
      (field) => typeof value[field] === "string" && UUID4.test(value[field]),
    ) ||
    !["accessExpiresAt", "sessionAbsoluteExpiresAt"].every(
      (field) =>
        typeof value[field] === "string" &&
        /^\d{4}-\d{2}-\d{2}T.*Z$/.test(value[field]),
    )
  )
    throw new WebSessionBlocked();
  const expires = Date.parse(value.accessExpiresAt);
  const absolute = Date.parse(value.sessionAbsoluteExpiresAt);
  if (
    !Number.isFinite(expires) ||
    !Number.isFinite(absolute) ||
    expires <= now ||
    expires > absolute
  )
    throw new WebSessionBlocked();
  return { token: value.accessToken, expires, epoch };
}

/**
 * 탭 간 잠금·불확실 표지를 공유하고 access와 계정 응답은 현재 문서 메모리에서만 보관한다.
 * @param {Window} browser 실제 브라우저. Web Locks가 없으면 인증을 차단한다.
 * @returns {object} 명시 로그인·복구·로그아웃·현재 회원 조회와 폐기 경계.
 */
export function createWebSession(browser = globalThis.window) {
  validateBrowser(browser);
  if (!browser.navigator.locks?.request || !browser.crypto?.randomUUID)
    throw new WebSessionBlocked();
  const api = createWebApi(browser);
  let access = null;
  let expiryTimer = null;
  let generation = 0;
  let disposed = false;
  let emailFlow = null;
  let notice = null;
  let grant = null;
  let erasure = null;
  let erasureTimer = null;
  const listeners = new Set();
  const uuid = () => browser.crypto.randomUUID();
  const read = () => browser.localStorage.getItem(KEY);

  /** 계정 변경·배경 전환 시 원문 소비자에게 동기 폐기를 통지한다. */
  function clear(reason = "AUTH_CHANGED") {
    clearTimeout(expiryTimer);
    clearTimeout(erasureTimer);
    erasureTimer = null;
    erasure = null;
    expiryTimer = null;
    access = null;
    emailFlow = null;
    notice = null;
    grant = null;
    generation += 1;
    for (const listener of listeners) {
      try {
        listener(reason);
      } catch (_) {
        browser.reportError?.(
          new Error("회원 자료 폐기 처리 중 오류가 발생했습니다."),
        );
      }
    }
  }

  /** 긴 절대 기한은 플랫폼 타이머 한도마다 재예약하며 실제 만료 때만 영수증을 폐기한다. */
  function scheduleErasureExpiry() {
    clearTimeout(erasureTimer);
    if (!erasure) {
      erasureTimer = null;
      return;
    }
    const remaining = Date.parse(erasure.receiptExpiresAt) - Date.now();
    if (remaining <= 0) {
      clear("RECEIPT_EXPIRED");
      return;
    }
    erasureTimer = setTimeout(
      scheduleErasureExpiry,
      Math.min(remaining, 2147483647),
    );
  }

  /** 저장 실패나 다른 명령의 표지 변경을 정상 인증으로 취급하지 않는다. */
  function write(marker) {
    const raw = JSON.stringify(marker);
    browser.localStorage.setItem(KEY, raw);
    if (read() !== raw) throw new WebSessionBlocked();
    return raw;
  }

  /** 현재 탭은 같은 세대의 정상 회전만 인정하며 만료·차단·계정 전환은 거절한다. */
  function current() {
    try {
      const marker = parseMarker(read());
      return (
        !disposed &&
        browser.document.visibilityState !== "hidden" &&
        access !== null &&
        access.expires > Date.now() &&
        marker?.epoch === access.epoch &&
        ["ready", "refresh"].includes(marker.operation)
      );
    } catch (_) {
      return false;
    }
  }

  /** 불확실 명령은 반복하지 않으며 해당 명령 소유 표지만 차단 상태로 바꾼다. */
  async function command(epoch, operation, path, body) {
    clear(operation === "refresh" ? "REFRESH" : "LOGIN");
    const fence = generation;
    const marker = { format: 1, epoch, operation, attempt: uuid() };
    let pending;
    try {
      pending = write(marker);
      const value = await api.request("POST", path, { epoch, body });
      if (
        disposed ||
        generation !== fence ||
        browser.document.visibilityState === "hidden" ||
        read() !== pending
      )
        throw new WebSessionBlocked();
      const credentials =
        value === null && operation === "refresh"
          ? null
          : parseAccess(value, epoch);
      write({ format: 1, epoch, operation: "ready" });
      access = credentials;
      if (credentials) {
        expiryTimer = setTimeout(
          () => clear("EXPIRED"),
          Math.min(credentials.expires - Date.now(), 2147483647),
        );
      }
      return credentials !== null;
    } catch (_) {
      clear();
      if (pending !== undefined && read() === pending)
        write({ ...marker, operation: "blocked" });
      throw new WebSessionBlocked();
    }
  }

  /** 모든 쿠키 명령을 같은 원점 잠금으로 직렬화하며 미지원 환경은 거절한다. */
  function exclusive(action) {
    const fence = generation;
    return browser.navigator.locks.request(LOCK, async () => {
      validateBrowser(browser);
      if (
        disposed ||
        generation !== fence ||
        browser.document.visibilityState === "hidden"
      )
        throw new WebSessionBlocked();
      return action();
    });
  }

  /** 쿠키 없는 메일 요청도 현재 문서·공유 표지에 결속하여 늦은 비밀 응답을 버린다. */
  async function publicRequest(method, path, body) {
    const observed = read();
    const marker = parseMarker(observed);
    if (!marker || marker.operation !== "ready") throw new WebSessionBlocked();
    const fence = generation;
    const result = await api.request(method, path, {
      epoch: marker.epoch,
      body,
    });
    if (
      disposed ||
      fence !== generation ||
      read() !== observed ||
      browser.document.visibilityState === "hidden"
    )
      throw new WebSessionBlocked();
    return result;
  }

  /** 명시 로그인만 손상·불확실 표지를 기존 쿠키 회수 후 회복할 수 있다. */
  async function revoke(epoch, recovery = false) {
    clear("LOGOUT");
    const fence = generation;
    const marker = { format: 1, epoch, operation: "logout", attempt: uuid() };
    const pending = write(marker);
    try {
      const result = await api.request("POST", BASE + "/logout", {
        epoch,
        body: { requestKey: uuid() },
      });
      if (
        !result ||
        Object.keys(result).length !== 2 ||
        !["LOGGED_OUT", "NO_SESSION"].includes(result.state) ||
        !UUID4.test(result.requestId ?? "")
      )
        throw new WebSessionBlocked();
    } catch (failure) {
      if (
        !(
          recovery &&
          failure instanceof WebApiError &&
          failure.status === 401 &&
          ["MEMBER_AUTH_REQUIRED", "REFRESH_UNAVAILABLE"].includes(failure.code)
        )
      ) {
        if (read() === pending) write({ ...marker, operation: "blocked" });
        throw new WebSessionBlocked();
      }
    }
    if (
      disposed ||
      generation !== fence ||
      browser.document.visibilityState === "hidden" ||
      read() !== pending
    )
      throw new WebSessionBlocked();
  }

  const storage = (event) => {
    if (
      (event.key === KEY || event.key === null) &&
      event.storageArea === browser.localStorage &&
      !current()
    )
      clear("ACCOUNT_CHANGED");
  };
  const visibility = () => {
    if (browser.document.visibilityState === "hidden") clear("BACKGROUND");
  };
  const pagehide = () => clear("PAGE_HIDDEN");
  browser.addEventListener("storage", storage);
  browser.addEventListener("pagehide", pagehide);
  browser.document.addEventListener("visibilitychange", visibility);

  /** 현재 문서 자격으로만 업무를 전송하고 계정·배경 전환 뒤 응답은 폐기한다. 자동 재전송하지 않는다. */
  async function authorizedRequest(method, path, body) {
    if (!current()) throw new WebSessionBlocked();
    const fence = generation;
    const token = access.token;
    try {
      const result = await api.request(method, path, { token, body });
      if (generation !== fence || !current() || access.token !== token)
        throw new WebSessionBlocked();
      return result;
    } catch (failure) {
      if (
        failure instanceof WebApiError &&
        ([401, 403].includes(failure.status) ||
          (path === "/api/member/auth/me" && failure.status === 409))
      )
        clear();
      throw failure;
    }
  }

  return Object.freeze({
    current,
    authorizedRequest,
    /** 자격 회전 결과가 불확실하면 다음 복구도 차단하며 자동 재전송하지 않는다. */
    restore() {
      return exclusive(() => {
        const marker = parseMarker(read());
        if (marker && marker.operation !== "ready")
          throw new WebSessionBlocked();
        return command(
          marker?.epoch ?? uuid(),
          "refresh",
          BASE + "/refresh",
          {},
        );
      });
    },
    /** 이메일·비밀번호는 호출 동안만 전달하고 저장하지 않는다. */
    login(email, password) {
      return exclusive(async () => {
        let epoch;
        try {
          epoch = parseMarker(read())?.epoch;
        } catch (_) {
          /* 명시 로그인에서만 손상 표지를 회복한다. */
        }
        await revoke(epoch ?? uuid(), true);
        return command(uuid(), "login", BASE + "/login/local", {
          email,
          password,
        });
      });
    },
    /** 성공 회수 후 비밀 없는 새 세대만 남기며 실패는 차단 상태로 유지한다. */
    logout() {
      clear();
      return exclusive(async () => {
        const epoch = uuid();
        await revoke(epoch);
        write({ format: 1, epoch, operation: "ready" });
      });
    },
    /** 현재 회원 응답은 발급 세대에 결속하며 계정 교체 후 늦게 도착하면 폐기한다. */
    getCurrentMember() {
      return authorizedRequest("GET", "/api/member/auth/me");
    },
    /** 목적 변경이나 화면 이탈 시 미완료 메일·최근 확인 증명을 폐기한다. */
    cancelPending() {
      generation += 1;
      emailFlow = null;
      notice = null;
      grant = null;
      clearTimeout(erasureTimer);
      erasureTimer = null;
      erasure = null;
    },
    /** 명시 메일 시작은 이전 쿠키를 회수하며 고지·flow 비밀은 문서 메모리로만 관리한다. */
    startEmail(purpose, email) {
      if (!["signup", "reset"].includes(purpose)) throw new WebSessionBlocked();
      return exclusive(async () => {
        let epoch;
        try {
          epoch = parseMarker(read())?.epoch;
        } catch (_) {
          /* 명시 시작에서만 회복한다. */
        }
        await revoke(epoch ?? uuid(), true);
        write({ format: 1, epoch: uuid(), operation: "ready" });
        if (purpose === "signup") {
          const result = await publicRequest("GET", BASE + "/notice");
          fields(result, [
            "policyCode",
            "noticeHash",
            "notice",
            "contact",
            "requestId",
          ]);
          if (
            typeof result.policyCode !== "string" ||
            !result.policyCode.trim() ||
            typeof result.noticeHash !== "string" ||
            !/^[0-9a-f]{64}$/.test(result.noticeHash) ||
            typeof result.contact !== "string" ||
            !result.contact.trim() ||
            !result.notice ||
            Object.keys(result.notice).length !== 2 ||
            !["version", "body"].every(
              (key) =>
                typeof result.notice[key] === "string" &&
                result.notice[key].trim(),
            )
          )
            throw new WebSessionBlocked();
          notice = result;
        }
        const result = await publicRequest(
          "POST",
          BASE + (purpose === "signup" ? "/email/signup" : "/password/reset"),
          { email },
        );
        fields(result, ["flowKey", "flowBinder", "expiresAt", "requestId"]);
        if (
          typeof result.flowKey !== "string" ||
          !UUID4.test(result.flowKey) ||
          typeof result.flowBinder !== "string" ||
          !TOKEN.test(result.flowBinder)
        )
          throw new WebSessionBlocked();
        expiry(result.expiresAt);
        emailFlow = { ...result, purpose, verified: false };
      });
    },
    /** 현재 flow의 코드만 확인하며 불확실 실패 후에는 처음부터 다시 시작한다. */
    verifyEmail(code) {
      return exclusive(async () => {
        const flow = emailFlow;
        if (!flow) throw new WebSessionBlocked();
        expiry(flow.expiresAt);
        try {
          const result = await publicRequest("POST", BASE + "/email/verify", {
            flowKey: flow.flowKey,
            flowBinder: flow.flowBinder,
            code,
          });
          fields(result, ["flowKey", "state", "expiresAt", "requestId"]);
          if (
            result.flowKey !== flow.flowKey ||
            result.state !== "VERIFIED" ||
            result.expiresAt !== flow.expiresAt ||
            emailFlow !== flow
          )
            throw new WebSessionBlocked();
          emailFlow = { ...flow, verified: true };
        } catch (failure) {
          emailFlow = null;
          notice = null;
          throw failure;
        }
      });
    },
    /** 고지 원문만 UI에 전달하며 flow binder나 requestKey는 노출하지 않는다. */
    getSignupNotice() {
      return notice
        ? {
            version: notice.notice.version,
            body: notice.notice.body,
            contact: notice.contact,
          }
        : null;
    },
    /** 확인된 메일과 명시 고지 동의만 단회 소비한다. 복구는 자동 로그인하지 않는다. */
    completeEmail(password, nickname, consented = false) {
      return exclusive(async () => {
        const flow = emailFlow;
        const policy = notice;
        if (!flow?.verified) throw new WebSessionBlocked();
        expiry(flow.expiresAt);
        if (flow.purpose === "signup") {
          if (!policy || consented !== true) throw new WebSessionBlocked();
          return command(uuid(), "signup", BASE + "/email/signup/complete", {
            flowKey: flow.flowKey,
            flowBinder: flow.flowBinder,
            password,
            nickname,
            policyCode: policy.policyCode,
            noticeHash: policy.noticeHash,
            requestKey: uuid(),
          });
        }
        const epoch = uuid();
        const pending = write({
          format: 1,
          epoch,
          operation: "blocked",
          attempt: uuid(),
        });
        clear();
        const fence = generation;
        const result = await api.request(
          "POST",
          BASE + "/password/reset/complete",
          {
            epoch,
            body: {
              flowKey: flow.flowKey,
              flowBinder: flow.flowBinder,
              newPassword: password,
              requestKey: uuid(),
            },
          },
        );
        fields(result, ["state", "requestId"]);
        if (
          result.state !== "LOGIN_REQUIRED" ||
          disposed ||
          fence !== generation ||
          read() !== pending ||
          browser.document.visibilityState === "hidden"
        )
          throw new WebSessionBlocked();
        return false;
      });
    },
    /** 현재 LOCAL 수단과 목적에만 결속된 최대 5분 증명을 문서 메모리에 보관한다. */
    verifyRecent(purpose, identityKey, password) {
      if (!PURPOSES.has(purpose) || !UUID4.test(identityKey))
        throw new WebSessionBlocked();
      return exclusive(async () => {
        if (!current()) throw new WebSessionBlocked();
        grant = null;
        const fence = generation;
        const observed = read();
        const result = await api.request(
          "POST",
          "/api/member/auth/reauth/local",
          { token: access.token, body: { purpose, identityKey, password } },
        );
        fields(result, ["reauthGrant", "purpose", "expiresAt", "requestId"]);
        if (
          fence !== generation ||
          read() !== observed ||
          !current() ||
          result.purpose !== purpose ||
          typeof result.reauthGrant !== "string" ||
          result.reauthGrant.length !== 80 ||
          !UUID4.test(result.reauthGrant.slice(0, 36)) ||
          result.reauthGrant[36] !== "." ||
          !/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(
            result.reauthGrant.slice(37),
          ) ||
          expiry(result.expiresAt) > Date.now() + 300000
        )
          throw new WebSessionBlocked();
        grant = result;
      });
    },
    /** 확인 목적·현재 자격·명시 동의를 검증하고 전체 회수 명령을 한 번만 전송한다. */
    completeRecent(purpose, newPassword, consented) {
      return exclusive(async () => {
        if (
          !["PASSWORD_CHANGE", "LOGOUT_ALL"].includes(purpose) ||
          !current() ||
          !grant ||
          grant.purpose !== purpose ||
          consented !== true
        )
          throw new WebSessionBlocked();
        expiry(grant.expiresAt);
        const token = access.token;
        const proof = grant.reauthGrant;
        const marker = {
          format: 1,
          epoch: access.epoch,
          operation: "blocked",
          attempt: uuid(),
        };
        const pending = write(marker);
        clear();
        const fence = generation;
        const path =
          purpose === "PASSWORD_CHANGE"
            ? "/api/member/auth/password/change"
            : "/api/member/auth/logout-all";
        const body = { reauthGrant: proof, requestKey: marker.attempt };
        if (purpose === "PASSWORD_CHANGE") body.newPassword = newPassword;
        const result = await api.request("POST", path, { token, body });
        fields(result, ["state", "requestId"]);
        if (
          result.state !== "LOGIN_REQUIRED" ||
          disposed ||
          generation !== fence ||
          read() !== pending ||
          browser.document.visibilityState === "hidden"
        )
          throw new WebSessionBlocked();
      });
    },
    /** 탈퇴 전용 단회 증명과 세 동의를 소비하고 접근 회수/접수만 채택한다. 유실 명령은 재전송하지 않는다. */
    deleteAccount(consented, sharedConfirmed, irreversibleConfirmed) {
      return exclusive(async () => {
        if (
          !current() ||
          !grant ||
          grant.purpose !== "DELETE_ACCOUNT" ||
          consented !== true ||
          sharedConfirmed !== true ||
          irreversibleConfirmed !== true
        )
          throw new WebSessionBlocked();
        expiry(grant.expiresAt);
        const token = access.token,
          proof = grant.reauthGrant;
        const pending = write({
          format: 1,
          epoch: access.epoch,
          operation: "blocked",
          attempt: uuid(),
        });
        const marker = parseMarker(pending);
        clear("DELETE_COMMAND");
        const fence = generation;
        const result = await api.request(
          "POST",
          "/api/member/auth/account/delete",
          { token, body: { reauthGrant: proof, requestKey: marker.attempt } },
        );
        fields(result, [
          "state",
          "eraseKey",
          "receiptToken",
          "receiptExpiresAt",
          "requestId",
        ]);
        if (
          result.state !== "ACCESS_REVOKED" ||
          !UUID4.test(result.eraseKey) ||
          typeof result.receiptToken !== "string" ||
          !/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(result.receiptToken) ||
          expiry(result.receiptExpiresAt) > Date.now() + 90 * 86400000 ||
          disposed ||
          generation !== fence ||
          read() !== pending ||
          browser.document.visibilityState === "hidden"
        )
          throw new WebSessionBlocked();
        erasure = result;
        scheduleErasureExpiry();
      });
    },
    /** 조회 기한만 UI에 제공하며 별도 영수증 비밀과 회원 자격은 노출하지 않는다. */
    getErasureReceipt() {
      if (!erasure) return null;
      if (Date.parse(erasure.receiptExpiresAt) <= Date.now()) return null;
      return { expiresAt: erasure.receiptExpiresAt };
    },
    /** 현재 문서의 단회 영수증을 명시 폐기하며 주소나 저장소로 복원하지 않는다. */
    forgetErasureReceipt() {
      clear("RECEIPT_CLOSED");
    },
    /** 무쿠키·무Bearer 영수증 조회만 수행하며 부분 파기를 완료로 추정하지 않는다. */
    async getErasureStatus() {
      const receipt = erasure;
      if (!receipt) throw new WebSessionBlocked();
      expiry(receipt.receiptExpiresAt);
      const fence = generation,
        observed = read();
      try {
        const value = await api.request(
          "POST",
          "/api/member/privacy/erase/status",
          {
            epoch: uuid(),
            body: {
              eraseKey: receipt.eraseKey,
              receiptToken: receipt.receiptToken,
            },
          },
        );
        fields(value, [
          "state",
          "accessRevoked",
          "primaryDone",
          "externalDone",
          "backupDone",
          "completedAt",
          "requestId",
        ]);
        if (
          erasure !== receipt ||
          disposed ||
          fence !== generation ||
          read() !== observed ||
          browser.document.visibilityState === "hidden" ||
          ![
            "REQUESTED",
            "PROCESSING",
            "WAITING",
            "COMPLETE",
            "FAILED",
          ].includes(value.state) ||
          value.accessRevoked !== true ||
          ["primaryDone", "externalDone", "backupDone"].some(
            (key) => typeof value[key] !== "boolean",
          ) ||
          (value.completedAt !== null &&
            (typeof value.completedAt !== "string" ||
              !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(
                value.completedAt,
              ) ||
              !Number.isFinite(Date.parse(value.completedAt)))) ||
          (value.state === "COMPLETE" &&
            (!value.primaryDone ||
              !value.externalDone ||
              !value.backupDone ||
              value.completedAt === null)) ||
          (value.state !== "COMPLETE" && value.completedAt !== null)
        )
          throw new WebSessionBlocked();
        return value;
      } catch (failure) {
        if (
          failure instanceof WebApiError &&
          [401, 403, 404, 410].includes(failure.status)
        )
          clear("RECEIPT_UNAVAILABLE");
        throw failure;
      }
    },
    /** 원문 소비자의 폐기 함수를 등록한다. 반환 함수는 해당 구독만 해제한다. */
    onClear(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    /** 문서 종료 시 이벤트·자격·원문 소비자를 정리한다. */
    dispose() {
      disposed = true;
      clear();
      browser.removeEventListener("storage", storage);
      browser.removeEventListener("pagehide", pagehide);
      browser.document.removeEventListener("visibilitychange", visibility);
      listeners.clear();
    },
  });
}
