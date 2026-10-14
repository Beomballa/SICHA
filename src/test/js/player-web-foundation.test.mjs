import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import {
  createWebApi,
  WebApiError,
} from "../../main/resources/static/player-common/web-api.mjs";
import {
  createWebSession,
  parseAccess,
  parseMarker,
  WebSessionBlocked,
} from "../../main/resources/static/player-common/web-session.mjs";

const BASE = "/api/member/browser-auth";
const KEY = "sicha.player.auth.v1";
const TOKEN = "A".repeat(43);

/** 합성 브라우저를 공유 저장소·직렬 Promise·실제 이벤트 경계로 구성한다. */
function environment(handler, shared = { raw: null, tail: Promise.resolve() }) {
  const listeners = new Map();
  const documentListeners = new Map();
  const calls = [];
  const browser = {
    isSecureContext: true,
    location: { protocol: "https:", origin: "https://player.example.invalid" },
    navigator: {
      serviceWorker: { controller: null },
      locks: {
        request(name, action) {
          assert.equal(name, "sicha-player-auth-v1");
          const work = shared.tail.then(action);
          shared.tail = work.catch(() => {});
          return work;
        },
      },
    },
    crypto: { randomUUID },
    localStorage: {
      getItem(key) {
        assert.equal(key, KEY);
        return shared.raw;
      },
      setItem(key, value) {
        assert.equal(key, KEY);
        shared.raw = value;
      },
    },
    document: {
      visibilityState: "visible",
      addEventListener(name, listener) {
        documentListeners.set(name, listener);
      },
      removeEventListener(name) {
        documentListeners.delete(name);
      },
    },
    addEventListener(name, listener) {
      listeners.set(name, listener);
    },
    removeEventListener(name) {
      listeners.delete(name);
    },
    async fetch(path, options) {
      calls.push({ path, options });
      return handler(path, options);
    },
  };
  return {
    browser,
    calls,
    shared,
    background() {
      browser.document.visibilityState = "hidden";
      documentListeners.get("visibilitychange")?.();
    },
    foreground() {
      browser.document.visibilityState = "visible";
    },
    storage() {
      listeners.get("storage")?.({
        key: KEY,
        storageArea: browser.localStorage,
      });
    },
    pagehide() {
      listeners.get("pagehide")?.();
    },
  };
}

/** 합성 HTTP 응답을 구성하며 실제 자격이나 개인정보를 사용하지 않는다. */
function json(value, status = 200, headers = {}) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      ...headers,
    },
  });
}

function credentials(epoch) {
  return {
    tokenType: "Bearer",
    accessToken: TOKEN,
    accessExpiresAt: new Date(Date.now() + 60000).toISOString(),
    sessionAbsoluteExpiresAt: new Date(Date.now() + 120000).toISOString(),
    sessionKey: randomUUID(),
    webEpoch: epoch,
    requestId: randomUUID(),
  };
}

function success(path, options) {
  if (path.endsWith("/logout"))
    return json({ state: "LOGGED_OUT", requestId: randomUUID() });
  if (path === "/api/member/auth/me") return json({ nickname: "합성 회원" });
  return json(credentials(options.headers["X-Sicha-Player-Web-Epoch"]));
}

test("transport separates cookie commands, public notice and Bearer lookup", async () => {
  const env = environment(success);
  const api = createWebApi(env.browser);
  const epoch = randomUUID();
  await api.request("POST", BASE + "/refresh", { epoch, body: {} });
  await api.request("GET", BASE + "/notice", { epoch });
  await api.request("GET", "/api/member/auth/me", { token: TOKEN });
  assert.deepEqual(
    env.calls.map(({ options }) => options.credentials),
    ["same-origin", "omit", "omit"],
  );
  for (const { options } of env.calls) {
    assert.equal(options.mode, "same-origin");
    assert.equal(options.redirect, "error");
    assert.equal(options.cache, "no-store");
    assert.equal(options.headers.Cookie, undefined);
  }
  assert.equal(
    env.calls[2].options.headers["X-Sicha-Player-Web-Epoch"],
    undefined,
  );
  assert.equal(env.calls[0].options.headers.Authorization, undefined);
});

test("unknown routes, query, method, mixed credentials and oversized body never dispatch", async () => {
  const env = environment(success);
  const api = createWebApi(env.browser);
  const epoch = randomUUID();
  for (const args of [
    ["GET", "https://foreign.invalid/api/member/auth/me", { token: TOKEN }],
    ["GET", "/api/member/auth/me?token=x", { token: TOKEN }],
    ["POST", "/api/member/auth/me", { token: TOKEN, body: {} }],
    ["POST", BASE + "/refresh", { epoch, token: TOKEN, body: {} }],
    ["POST", BASE + "/refresh", { epoch, body: [] }],
    [
      "POST",
      BASE + "/login/local",
      { epoch, body: { password: "가".repeat(6000) } },
    ],
  ])
    await assert.rejects(api.request(...args), WebApiError);
  assert.equal(env.calls.length, 0);
});

test("HTTP, origin change, service worker and missing Web Locks fail closed", async () => {
  const env = environment(success);
  const api = createWebApi(env.browser);
  env.browser.location.origin = "https://changed.invalid";
  await assert.rejects(
    api.request("GET", "/api/member/auth/me", { token: TOKEN }),
    WebApiError,
  );
  env.browser.location.protocol = "http:";
  assert.throws(() => createWebApi(env.browser), WebApiError);
  env.browser.location.protocol = "https:";
  env.browser.navigator.serviceWorker.controller = {};
  assert.throws(() => createWebApi(env.browser), WebApiError);
  env.browser.navigator.serviceWorker.controller = null;
  env.browser.navigator.locks = undefined;
  assert.throws(() => createWebSession(env.browser), WebSessionBlocked);
  assert.equal(env.calls.length, 0);
});

test("response rejects cacheable, wrong MIME, malformed UTF-8, array and oversized streams", async () => {
  for (const response of [
    json({}, 200, { "Cache-Control": "public" }),
    json({}, 200, { "Content-Type": "text/html" }),
    new Response(new Uint8Array([0xff]), {
      headers: {
        "Cache-Control": "no-store",
        "Content-Type": "application/json",
      },
    }),
    json([]),
    json({ data: "x".repeat(1048576) }),
  ]) {
    const env = environment(() => response);
    await assert.rejects(
      createWebApi(env.browser).request("GET", BASE + "/notice", {
        epoch: randomUUID(),
      }),
      WebApiError,
    );
    assert.equal(env.calls[0].options.signal.aborted, true);
  }
});

test("server errors expose only fixed code and status, not original body", async () => {
  const env = environment(() =>
    json({ code: "RATE_LIMITED", message: "synthetic-secret" }, 429),
  );
  await assert.rejects(
    createWebApi(env.browser).request("GET", BASE + "/notice", {
      epoch: randomUUID(),
    }),
    (failure) => {
      assert.equal(failure.status, 429);
      assert.equal(failure.code, "RATE_LIMITED");
      assert.doesNotMatch(failure.message, /synthetic-secret/);
      return true;
    },
  );
});

test("markers and access reject extra secrets, mismatched generation and invalid expiry", () => {
  const epoch = randomUUID();
  assert.throws(
    () =>
      parseMarker(
        JSON.stringify({ format: 1, epoch, operation: "ready", token: TOKEN }),
      ),
    WebSessionBlocked,
  );
  assert.throws(
    () =>
      parseMarker(JSON.stringify({ format: 1, epoch, operation: "refresh" })),
    WebSessionBlocked,
  );
  assert.throws(
    () => parseAccess(credentials(epoch), randomUUID()),
    WebSessionBlocked,
  );
  assert.throws(
    () => parseAccess({ ...credentials(epoch), refreshToken: TOKEN }, epoch),
    WebSessionBlocked,
  );
  assert.throws(
    () =>
      parseAccess(
        { ...credentials(epoch), accessExpiresAt: "2000-01-01T00:00:00Z" },
        epoch,
      ),
    WebSessionBlocked,
  );
  assert.throws(
    () =>
      parseAccess(
        { ...credentials(epoch), accessExpiresAt: "2099-01-01T00:00:00Z" },
        epoch,
      ),
    WebSessionBlocked,
  );
});

test("login revokes first and persists only non-secret marker; logout clears current access", async () => {
  const env = environment(success);
  const session = createWebSession(env.browser);
  assert.equal(
    await session.login("synthetic@example.invalid", "synthetic-password"),
    true,
  );
  assert.deepEqual(
    env.calls.map(({ path }) => path),
    [BASE + "/logout", BASE + "/login/local"],
  );
  assert.equal(session.current(), true);
  assert.deepEqual(await session.getCurrentMember(), { nickname: "합성 회원" });
  assert.doesNotMatch(env.shared.raw, /synthetic|AAAA|token|password/i);
  await session.logout();
  assert.equal(session.current(), false);
  session.dispose();
});

/** 탈퇴 접수/조회만 합성하며 실제 회원 삭제나 전체 파기 완료를 만들지 않는다. */
function erasureResponses(overrides = {}) {
  const receipt = {
    state: "ACCESS_REVOKED",
    eraseKey: randomUUID(),
    receiptToken: "B".repeat(42) + "A",
    receiptExpiresAt: new Date(Date.now() + 86400000).toISOString(),
    requestId: randomUUID(),
  };
  return (path, options) => {
    if (overrides[path]) return overrides[path](options, receipt);
    if (path === "/api/member/auth/reauth/local")
      return json({
        purpose: JSON.parse(options.body).purpose,
        reauthGrant: randomUUID() + "." + TOKEN,
        expiresAt: new Date(Date.now() + 120000).toISOString(),
        requestId: randomUUID(),
      });
    if (path === "/api/member/auth/account/delete") return json(receipt, 202);
    if (path === "/api/member/privacy/erase/status")
      return json({
        state: "WAITING",
        accessRevoked: true,
        primaryDone: true,
        externalDone: false,
        backupDone: false,
        completedAt: null,
        requestId: randomUUID(),
      });
    return success(path, options);
  };
}

test("deletion requires its own purpose and all three explicit consents before any POST", async () => {
  const env = environment(erasureResponses());
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent("LOGOUT_ALL", randomUUID(), "synthetic-password");
  await assert.rejects(
    session.deleteAccount(true, true, true),
    WebSessionBlocked,
  );
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  for (const flags of [
    [false, true, true],
    [true, false, true],
    [true, true, false],
  ])
    await assert.rejects(session.deleteAccount(...flags), WebSessionBlocked);
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/account/delete")).length,
    0,
  );
  await session.deleteAccount(true, true, true);
  assert.equal(session.current(), false);
  assert.ok(session.getErasureReceipt());
  assert.doesNotMatch(env.shared.raw, /receiptToken|eraseKey/);
  assert.equal(Object.keys(session.getErasureReceipt()).join(), "expiresAt");
  session.dispose();
});

test("receipt status is cookie-free without Bearer and partial erasure is not full completion", async () => {
  const env = environment(erasureResponses());
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  await session.deleteAccount(true, true, true);
  const status = await session.getErasureStatus();
  assert.equal(status.state, "WAITING");
  assert.equal(status.backupDone, false);
  const call = env.calls.find(({ path }) => path.endsWith("/erase/status"));
  assert.equal(call.options.credentials, "omit");
  assert.equal(call.options.headers.Authorization, undefined);
  const value = JSON.parse(call.options.body);
  assert.equal(Object.keys(value).sort().join(), "eraseKey,receiptToken");
  session.forgetErasureReceipt();
  assert.equal(session.getErasureReceipt(), null);
  await assert.rejects(session.getErasureStatus(), WebSessionBlocked);
  session.dispose();
});

test("lost accepted deletion never replays or recovers an absent receipt after reload", async () => {
  const env = environment(
    erasureResponses({
      "/api/member/auth/account/delete": () => {
        throw new TypeError("synthetic lost acceptance");
      },
    }),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  await assert.rejects(session.deleteAccount(true, true, true));
  await assert.rejects(
    session.deleteAccount(true, true, true),
    WebSessionBlocked,
  );
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/account/delete")).length,
    1,
  );
  assert.equal(session.getErasureReceipt(), null);
  const reloaded = createWebSession(env.browser);
  await assert.rejects(reloaded.restore(), WebSessionBlocked);
  session.dispose();
  reloaded.dispose();
});

test("background after deletion destroys receipt and a late status cannot restore it", async () => {
  let finish;
  const env = environment(
    erasureResponses({
      "/api/member/privacy/erase/status": () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    }),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  await session.deleteAccount(true, true, true);
  const pending = session.getErasureStatus();
  const rejected = assert.rejects(pending, WebSessionBlocked);
  env.background();
  env.foreground();
  finish(
    json({
      state: "COMPLETE",
      accessRevoked: true,
      primaryDone: true,
      externalDone: true,
      backupDone: true,
      completedAt: new Date().toISOString(),
      requestId: randomUUID(),
    }),
  );
  await rejected;
  assert.equal(session.getErasureReceipt(), null);
  session.dispose();
});

test("changing account views destroys a received receipt instead of restoring it on return", async () => {
  const env = environment(erasureResponses());
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  await session.deleteAccount(true, true, true);
  session.cancelPending();
  assert.equal(session.getErasureReceipt(), null);
  await assert.rejects(session.getErasureStatus(), WebSessionBlocked);
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/erase/status")).length,
    0,
  );
  session.dispose();
});

test("90-day absolute receipt expiry re-arms capped timers rather than expiring at 24 days", async (context) => {
  const now = Date.now();
  context.mock.timers.enable({ apis: ["setTimeout", "Date"], now });
  const lifetime = 90 * 86400000;
  const env = environment(
    erasureResponses({
      "/api/member/auth/account/delete": (_, receipt) =>
        json(
          {
            ...receipt,
            receiptExpiresAt: new Date(now + lifetime).toISOString(),
          },
          202,
        ),
    }),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  await session.deleteAccount(true, true, true);
  context.mock.timers.tick(2147483647);
  assert.ok(session.getErasureReceipt());
  context.mock.timers.tick(lifetime - 2147483647);
  assert.equal(session.getErasureReceipt(), null);
  session.dispose();
});

test("inconsistent COMPLETE is rejected and dependency failure does not invent a completed status", async () => {
  let failed = false;
  const env = environment(
    erasureResponses({
      "/api/member/privacy/erase/status": () =>
        failed
          ? json(
              { code: "DEPENDENCY_UNAVAILABLE", requestId: randomUUID() },
              503,
            )
          : json({
              state: "COMPLETE",
              accessRevoked: true,
              primaryDone: true,
              externalDone: false,
              backupDone: false,
              completedAt: new Date().toISOString(),
              requestId: randomUUID(),
            }),
    }),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "DELETE_ACCOUNT",
    randomUUID(),
    "synthetic-password",
  );
  await session.deleteAccount(true, true, true);
  await assert.rejects(session.getErasureStatus(), WebSessionBlocked);
  failed = true;
  await assert.rejects(session.getErasureStatus(), WebApiError);
  assert.ok(session.getErasureReceipt());
  session.dispose();
});

test("lost refresh response leaves durable blocker and reload does not refresh again", async () => {
  const epoch = randomUUID();
  const env = environment(() => {
    throw new Error("lost synthetic response");
  });
  env.shared.raw = JSON.stringify({ format: 1, epoch, operation: "ready" });
  const session = createWebSession(env.browser);
  await assert.rejects(session.restore(), WebSessionBlocked);
  assert.equal(parseMarker(env.shared.raw).operation, "blocked");
  const reloaded = createWebSession(env.browser);
  await assert.rejects(reloaded.restore(), WebSessionBlocked);
  assert.equal(env.calls.length, 1);
  session.dispose();
  reloaded.dispose();
});

test("empty refresh is signed-out while malformed non-refresh 204 is rejected", async () => {
  const env = environment(
    () =>
      new Response(null, {
        status: 204,
        headers: { "Cache-Control": "no-store" },
      }),
  );
  const session = createWebSession(env.browser);
  assert.equal(await session.restore(), false);
  assert.equal(session.current(), false);
  await assert.rejects(
    createWebApi(env.browser).request("POST", BASE + "/logout", {
      epoch: randomUUID(),
      body: { requestKey: randomUUID() },
    }),
    WebApiError,
  );
  session.dispose();
});

test("two tabs serialize refresh without reusing a consumed proof", async () => {
  let concurrent = 0;
  let maximum = 0;
  const first = environment(async (path, options) => {
    concurrent += 1;
    maximum = Math.max(maximum, concurrent);
    await Promise.resolve();
    concurrent -= 1;
    return success(path, options);
  });
  const second = environment(
    first.browser.fetch.bind(first.browser),
    first.shared,
  );
  const a = createWebSession(first.browser);
  const b = createWebSession(second.browser);
  assert.deepEqual(await Promise.all([a.restore(), b.restore()]), [true, true]);
  assert.equal(maximum, 1);
  assert.equal(a.current(), true);
  assert.equal(b.current(), true);
  a.dispose();
  b.dispose();
});

test("account replacement rejects previous account response and synchronously purges consumers", async () => {
  let finish;
  const env = environment((path, options) =>
    path === "/api/member/auth/me"
      ? new Promise((resolve) => {
          finish = resolve;
        })
      : success(path, options),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  let purges = 0;
  session.onClear(() => {
    purges += 1;
  });
  const pending = session.getCurrentMember();
  env.shared.raw = JSON.stringify({
    format: 1,
    epoch: randomUUID(),
    operation: "ready",
  });
  env.storage();
  finish(json({ nickname: "옛 계정" }));
  await assert.rejects(pending, WebSessionBlocked);
  assert.equal(purges, 1);
  assert.equal(session.current(), false);
  session.dispose();
});

test("background during refresh cannot revive credentials when foreground returns", async () => {
  let finish;
  const env = environment(
    (path, options) =>
      new Promise((resolve) => {
        finish = () => resolve(success(path, options));
      }),
  );
  const session = createWebSession(env.browser);
  const pending = session.restore();
  await Promise.resolve();
  env.background();
  env.foreground();
  finish();
  await assert.rejects(pending, WebSessionBlocked);
  assert.equal(session.current(), false);
  assert.equal(parseMarker(env.shared.raw).operation, "blocked");
  session.dispose();
});

test("explicit login can recover confirmed 401 but cannot ignore uncertain 503 revoke", async () => {
  for (const status of [401, 503]) {
    const env = environment((path, options) =>
      path.endsWith("/logout")
        ? json({ code: "REFRESH_UNAVAILABLE" }, status)
        : success(path, options),
    );
    const session = createWebSession(env.browser);
    if (status === 401)
      assert.equal(
        await session.login("synthetic@example.invalid", "synthetic-password"),
        true,
      );
    else {
      await assert.rejects(
        session.login("synthetic@example.invalid", "synthetic-password"),
        WebSessionBlocked,
      );
      assert.equal(env.calls.length, 1);
      assert.equal(parseMarker(env.shared.raw).operation, "blocked");
    }
    session.dispose();
  }
});

test("queued login is discarded after background transition even when foreground resumes", async () => {
  let release;
  const shared = {
    raw: null,
    tail: new Promise((resolve) => {
      release = resolve;
    }),
  };
  const env = environment(success, shared);
  const session = createWebSession(env.browser);
  const pending = session.login(
    "synthetic@example.invalid",
    "synthetic-password",
  );
  env.background();
  env.foreground();
  release();
  await assert.rejects(pending, WebSessionBlocked);
  assert.equal(env.calls.length, 0);
  session.dispose();
});

test("background during revoke cannot continue with a fresh login", async () => {
  let finish;
  const env = environment(
    (path, options) =>
      new Promise((resolve) => {
        finish = () => resolve(success(path, options));
      }),
  );
  const session = createWebSession(env.browser);
  const pending = session.login(
    "synthetic@example.invalid",
    "synthetic-password",
  );
  await Promise.resolve();
  env.background();
  env.foreground();
  finish();
  await assert.rejects(pending, WebSessionBlocked);
  assert.equal(env.calls.length, 1);
  assert.equal(session.current(), false);
  session.dispose();
});

test("revoked Bearer clears consumers without retrying the request", async () => {
  const env = environment((path, options) =>
    path === "/api/member/auth/me"
      ? json({ code: "MEMBER_AUTH_REQUIRED" }, 401)
      : success(path, options),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  let purges = 0;
  session.onClear(() => {
    purges += 1;
  });
  await assert.rejects(session.getCurrentMember(), WebApiError);
  assert.equal(session.current(), false);
  assert.equal(purges, 1);
  assert.equal(
    env.calls.filter(({ path }) => path === "/api/member/auth/me").length,
    1,
  );
  session.dispose();
});

test("transport timeout aborts the request instead of silently retrying", async (context) => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const env = environment(
    (path, options) =>
      new Promise((resolve, reject) => {
        options.signal.addEventListener("abort", () =>
          reject(new Error("synthetic timeout")),
        );
      }),
  );
  const pending = createWebApi(env.browser).request("POST", BASE + "/refresh", {
    epoch: randomUUID(),
    body: {},
  });
  context.mock.timers.tick(15000);
  await assert.rejects(pending, WebApiError);
  assert.equal(env.calls.length, 1);
  assert.equal(env.calls[0].options.signal.aborted, true);
  context.mock.timers.reset();
});

test("a failing purge subscriber cannot stop other consumers from clearing", async () => {
  const env = environment(success);
  const reports = [];
  env.browser.reportError = (failure) => reports.push(failure.message);
  const session = createWebSession(env.browser);
  await session.restore();
  let purged = false;
  session.onClear(() => {
    throw new Error("synthetic-private-payload");
  });
  session.onClear(() => {
    purged = true;
  });
  env.pagehide();
  assert.equal(purged, true);
  assert.equal(session.current(), false);
  assert.equal(reports.length, 1);
  assert.doesNotMatch(reports[0], /synthetic-private-payload/);
  session.dispose();
});

test("access expiry clears subscribers without dispatching automatic refresh", async (context) => {
  context.mock.timers.enable({ apis: ["setTimeout"] });
  const env = environment(success);
  const session = createWebSession(env.browser);
  await session.restore();
  let purged = false;
  session.onClear(() => {
    purged = true;
  });
  context.mock.timers.tick(61000);
  assert.equal(purged, true);
  assert.equal(session.current(), false);
  assert.equal(env.calls.length, 1);
  session.dispose();
  context.mock.timers.reset();
});

/** 고정 메일 flow와 목적별 증명을 실제 전송 형식으로 제공하는 합성 서버다. */
function accountResponses() {
  const flowKey = randomUUID();
  const expiresAt = new Date(Date.now() + 120000).toISOString();
  return (path, options) => {
    if (path === BASE + "/notice")
      return json({
        policyCode: "SYNTHETIC",
        noticeHash: "a".repeat(64),
        notice: { version: "합성 고지", body: "<script>합성 원문</script>" },
        contact: "합성 연락처",
        requestId: randomUUID(),
      });
    if ([BASE + "/email/signup", BASE + "/password/reset"].includes(path))
      return json(
        { flowKey, flowBinder: TOKEN, expiresAt, requestId: randomUUID() },
        202,
      );
    if (path === BASE + "/email/verify")
      return json({
        flowKey,
        state: "VERIFIED",
        expiresAt,
        requestId: randomUUID(),
      });
    if (path === BASE + "/email/signup/complete")
      return json(
        credentials(options.headers["X-Sicha-Player-Web-Epoch"]),
        201,
      );
    if (path === "/api/member/auth/reauth/local")
      return json({
        purpose: JSON.parse(options.body).purpose,
        reauthGrant: randomUUID() + "." + TOKEN,
        expiresAt,
        requestId: randomUUID(),
      });
    if (
      [
        BASE + "/password/reset/complete",
        "/api/member/auth/password/change",
        "/api/member/auth/logout-all",
      ].includes(path)
    )
      return json({ state: "LOGIN_REQUIRED", requestId: randomUUID() });
    return success(path, options);
  };
}

test("signup binds verified flow and notice consent without persisting binder or policy body", async () => {
  const env = environment(accountResponses());
  const session = createWebSession(env.browser);
  await session.startEmail("signup", "synthetic@example.invalid");
  await assert.rejects(
    session.completeEmail("synthetic-password", "합성 회원", true),
    WebSessionBlocked,
  );
  await session.verifyEmail("12345678");
  assert.equal(session.getSignupNotice().body, "<script>합성 원문</script>");
  await assert.rejects(
    session.completeEmail("synthetic-password", "합성 회원", false),
    WebSessionBlocked,
  );
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/email/signup/complete"))
      .length,
    0,
  );
  assert.equal(
    await session.completeEmail("synthetic-password", "합성 회원", true),
    true,
  );
  const complete = env.calls.find(({ path }) =>
    path.endsWith("/email/signup/complete"),
  );
  assert.equal(JSON.parse(complete.options.body).noticeHash, "a".repeat(64));
  assert.equal(complete.options.credentials, "same-origin");
  assert.doesNotMatch(env.shared.raw, /flowBinder|policyCode|script|password/);
  assert.equal(session.getSignupNotice(), null);
  session.dispose();
});

test("reset success has no automatic login and leaves a durable blocker", async () => {
  const env = environment(accountResponses());
  const session = createWebSession(env.browser);
  await session.startEmail("reset", "synthetic@example.invalid");
  await session.verifyEmail("12345678");
  assert.equal(await session.completeEmail("synthetic-new-password"), false);
  assert.equal(session.current(), false);
  assert.equal(parseMarker(env.shared.raw).operation, "blocked");
  await assert.rejects(session.restore(), WebSessionBlocked);
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/login/local")).length,
    0,
  );
  session.dispose();
});

test("canceled email flow and backgrounded verification cannot be consumed", async () => {
  const env = environment(accountResponses());
  const session = createWebSession(env.browser);
  await session.startEmail("signup", "synthetic@example.invalid");
  await session.verifyEmail("12345678");
  session.cancelPending();
  await assert.rejects(
    session.completeEmail("synthetic-password", "합성 회원", true),
    WebSessionBlocked,
  );
  await session.startEmail("signup", "synthetic@example.invalid");
  env.background();
  env.foreground();
  await assert.rejects(session.verifyEmail("12345678"), WebSessionBlocked);
  assert.equal(session.getSignupNotice(), null);
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/email/signup/complete"))
      .length,
    0,
  );
  session.dispose();
});

test("security grant is purpose-bound, canceled on view change, and requires explicit consent", async () => {
  const env = environment(accountResponses());
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent(
    "PASSWORD_CHANGE",
    randomUUID(),
    "synthetic-password",
  );
  await assert.rejects(
    session.completeRecent("LOGOUT_ALL", undefined, true),
    WebSessionBlocked,
  );
  await assert.rejects(
    session.completeRecent("PASSWORD_CHANGE", "synthetic-new-password", false),
    WebSessionBlocked,
  );
  session.cancelPending();
  await assert.rejects(
    session.completeRecent("PASSWORD_CHANGE", "synthetic-new-password", true),
    WebSessionBlocked,
  );
  await session.verifyRecent(
    "PASSWORD_CHANGE",
    randomUUID(),
    "synthetic-password",
  );
  await session.completeRecent(
    "PASSWORD_CHANGE",
    "synthetic-new-password",
    true,
  );
  assert.equal(session.current(), false);
  const change = env.calls.find(({ path }) =>
    path.endsWith("/password/change"),
  );
  assert.equal(change.options.credentials, "omit");
  assert.equal(change.options.headers.Authorization, "Bearer " + TOKEN);
  assert.equal(change.options.headers["X-Sicha-Player-Web-Epoch"], undefined);
  assert.equal(parseMarker(env.shared.raw).operation, "blocked");
  await assert.rejects(session.restore(), WebSessionBlocked);
  session.dispose();
});

test("lost security command is not automatically repeated or refreshed", async () => {
  const respond = accountResponses();
  const env = environment((path, options) => {
    if (path.endsWith("/logout-all"))
      throw new Error("synthetic response loss");
    return respond(path, options);
  });
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent("LOGOUT_ALL", randomUUID(), "synthetic-password");
  await assert.rejects(
    session.completeRecent("LOGOUT_ALL", undefined, true),
    WebApiError,
  );
  await assert.rejects(
    session.completeRecent("LOGOUT_ALL", undefined, true),
    WebSessionBlocked,
  );
  await assert.rejects(session.restore(), WebSessionBlocked);
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/logout-all")).length,
    1,
  );
  assert.equal(session.current(), false);
  session.dispose();
});

test("expired recent proof is rejected before dispatch while access is still current", async (context) => {
  const respond = accountResponses();
  const start = Date.now();
  const env = environment((path, options) =>
    path.endsWith("/reauth/local")
      ? json({
          purpose: "LOGOUT_ALL",
          reauthGrant: randomUUID() + "." + TOKEN,
          expiresAt: new Date(start + 10000).toISOString(),
          requestId: randomUUID(),
        })
      : respond(path, options),
  );
  const session = createWebSession(env.browser);
  await session.restore();
  await session.verifyRecent("LOGOUT_ALL", randomUUID(), "synthetic-password");
  context.mock.method(Date, "now", () => start + 11000);
  assert.equal(session.current(), true);
  await assert.rejects(
    session.completeRecent("LOGOUT_ALL", undefined, true),
    WebSessionBlocked,
  );
  assert.equal(
    env.calls.filter(({ path }) => path.endsWith("/logout-all")).length,
    0,
  );
  session.dispose();
});
