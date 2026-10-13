export const UUID4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const BASE = "/api/member/browser-auth";
const COOKIE_POSTS = new Set(
  ["/login/local", "/refresh", "/logout", "/email/signup/complete"].map(
    (path) => BASE + path,
  ),
);
const PUBLIC_POSTS = new Set(
  [
    "/email/signup",
    "/email/verify",
    "/password/reset",
    "/password/reset/complete",
  ].map((path) => BASE + path),
);
const SECURITY_POSTS = new Set([
  "/api/member/auth/reauth/local",
  "/api/member/auth/password/change",
  "/api/member/auth/logout-all",
]);

/** 기존 SOLO/COOP 고정 경로만 열고 query는 사건 목록의 단일 cursor/size에만 허용한다. */
function gameRoute(method, raw) {
  const key =
    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
  const path = raw.split("?")[0];
  if (raw.includes("?")) {
    if (method !== "GET" || path !== "/api/stories" || raw.length > 4096)
      return false;
    const fields = raw.slice(path.length + 1).split("&");
    const seen = new Set();
    if (!fields.length || fields.length > 2) return false;
    for (const field of fields) {
      const equals = field.indexOf("=");
      const name = field.slice(0, equals);
      const value = field.slice(equals + 1);
      if (equals < 1 || seen.has(name)) return false;
      seen.add(name);
      if (name === "size") {
        if (!/^[1-9][0-9]{0,2}$/.test(value) || Number(value) > 100)
          return false;
      } else if (name === "cursor") {
        try {
          const decoded = decodeURIComponent(value);
          if (
            !decoded ||
            decoded.length > 2048 ||
            encodeURIComponent(decoded) !== value
          )
            return false;
        } catch (_) {
          return false;
        }
      } else return false;
    }
  }
  if (method === "GET")
    return (
      [
        "/api/stories",
        "/api/games/current",
        "/api/member/privacy/policies/friendly",
      ].includes(path) ||
      /^\/api\/stories\/[A-Z0-9_]{1,40}$/.test(path) ||
      new RegExp(
        "^/api/games/" + key + "(?:/materials|/report|/result)?$",
      ).test(path)
    );
  if (method === "PATCH")
    return new RegExp("^/api/games/" + key + "/report$").test(path);
  return (
    method === "POST" &&
    (path === "/api/games" ||
      path === "/api/games/invitations/inspect" ||
      path === "/api/games/invitations/join" ||
      new RegExp(
        "^/api/games/" +
          key +
          "/(?:start|heartbeat|forfeit|report/submit|hints/[1-3]/open|clues/[A-Z0-9_]{1,32}/read|invitations|leave|ready|selection/refresh|report/proposals(?:/" +
          key +
          "/respond)?)$",
      ).test(path))
  );
}

/** 원문이나 URL을 오류 메시지에 포함하지 않는 고정 전송 오류다. */
export class WebApiError extends Error {
  constructor(status = 0, code = "WEB_REQUEST_FAILED") {
    super("웹 요청을 완료하지 못했습니다.");
    this.status = status;
    this.code = code;
  }
}

/** HTTPS 원점·서비스 워커 제어를 검사한다. 미지원 환경에는 우회 전송을 제공하지 않는다. */
export function validateBrowser(browser) {
  if (
    !browser.isSecureContext ||
    browser.location.protocol !== "https:" ||
    browser.navigator.serviceWorker?.controller
  ) {
    throw new WebApiError(0, "WEB_CONFIGURATION_INVALID");
  }
}

/**
 * 정확한 인증 경로와 현재 회원 조회만 전송하는 공통 경계다.
 * @param {Window} browser 실제 브라우저이며 테스트에서만 합성 경계를 전달한다.
 * @returns {object} 쿠키 인증과 쿠키 없는 업무 전송 함수.
 */
export function createWebApi(browser = globalThis.window) {
  validateBrowser(browser);
  const origin = browser.location.origin;

  /** 본문은 JSON 객체만 허용하며 타임아웃·응답 크기·리다이렉트를 엄격히 제한한다. */
  async function request(method, path, { epoch, token, body } = {}) {
    validateBrowser(browser);
    const cookie = method === "POST" && COOKIE_POSTS.has(path);
    const publicAuth =
      (method === "GET" && path === BASE + "/notice") ||
      (method === "POST" && PUBLIC_POSTS.has(path));
    const bearer =
      (method === "GET" && path === "/api/member/auth/me") ||
      (method === "POST" && SECURITY_POSTS.has(path)) ||
      gameRoute(method, path);
    if (
      browser.location.origin !== origin ||
      (!cookie && !publicAuth && !bearer) ||
      (bearer
        ? typeof token !== "string" || !TOKEN.test(token) || epoch !== undefined
        : typeof epoch !== "string" ||
          !UUID4.test(epoch) ||
          token !== undefined) ||
      (method === "POST" || method === "PATCH"
        ? !body || typeof body !== "object" || Array.isArray(body)
        : body !== undefined)
    ) {
      throw new WebApiError(0, "WEB_REQUEST_INVALID");
    }
    const headers = { Accept: "application/json", "X-Sicha-Player-Web": "1" };
    if (bearer) headers.Authorization = `Bearer ${token}`;
    else headers["X-Sicha-Player-Web-Epoch"] = epoch;
    const encoded =
      method === "POST" || method === "PATCH"
        ? JSON.stringify(body)
        : undefined;
    const limit =
      method === "PATCH"
        ? 131072
        : path.startsWith("/api/games")
          ? 8192
          : 16384;
    if (encoded && new TextEncoder().encode(encoded).length > limit)
      throw new WebApiError(0, "WEB_REQUEST_TOO_LARGE");
    if (encoded) headers["Content-Type"] = "application/json";
    const controller = new AbortController();
    let responseConsumed = false;
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await browser.fetch(path, {
        method,
        headers,
        body: encoded,
        credentials: cookie ? "same-origin" : "omit",
        mode: "same-origin",
        redirect: "error",
        cache: "no-store",
        signal: controller.signal,
      });
      if (
        response.redirected ||
        response.status < 200 ||
        response.status > 599 ||
        !response.headers
          .get("cache-control")
          ?.split(",")
          .some((value) => value.trim().toLowerCase() === "no-store")
      )
        throw new WebApiError();
      const reader = response.body?.getReader();
      const chunks = [];
      let size = 0;
      if (reader) {
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            size += value.byteLength;
            if (size > 1048576) {
              await reader.cancel();
              throw new WebApiError(0, "WEB_RESPONSE_TOO_LARGE");
            }
            chunks.push(value);
          }
        } finally {
          reader.releaseLock();
        }
      }
      if (
        response.status === 204 &&
        size === 0 &&
        (path === BASE + "/refresh" ||
          (method === "POST" && path.endsWith("/heartbeat")))
      )
        return null;
      if (
        !size ||
        response.headers
          .get("content-type")
          ?.split(";")[0]
          .trim()
          .toLowerCase() !== "application/json"
      )
        throw new WebApiError();
      const bytes = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {
        bytes.set(chunk, offset);
        offset += chunk.byteLength;
      }
      const value = JSON.parse(
        new TextDecoder("utf-8", { fatal: true }).decode(bytes),
      );
      if (!value || typeof value !== "object" || Array.isArray(value))
        throw new WebApiError();
      responseConsumed = true;
      if (!response.ok) {
        const code =
          typeof value.code === "string" && /^[A-Z0-9_]{1,64}$/.test(value.code)
            ? value.code
            : "WEB_REQUEST_FAILED";
        throw new WebApiError(response.status, code);
      }
      if (
        response.status !==
        (path === BASE + "/email/signup/complete" ||
        (method === "POST" &&
          (path === "/api/games" ||
            path.endsWith("/invitations") ||
            path.endsWith("/report/proposals")))
          ? 201
          : PUBLIC_POSTS.has(path) &&
              ["/email/signup", "/password/reset"].some((suffix) =>
                path.endsWith(suffix),
              )
            ? 202
            : 200)
      )
        throw new WebApiError();
      return value;
    } catch (failure) {
      if (!responseConsumed) controller.abort();
      if (failure instanceof WebApiError) throw failure;
      throw new WebApiError();
    } finally {
      clearTimeout(timeout);
    }
  }

  return Object.freeze({ request });
}
