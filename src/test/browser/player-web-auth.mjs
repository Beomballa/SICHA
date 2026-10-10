import puppeteer from "puppeteer-core";
import assert from "node:assert/strict";
import https from "node:https";
import { randomUUID } from "node:crypto";

// 자격 증명은 stdin으로만 받고 stdout에는 안전한 카운터 영수증만 남긴다.
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const fixture = JSON.parse(Buffer.concat(chunks).toString("utf8"));
const origin = new URL(fixture.origin);
assert.equal(origin.protocol, "https:");
assert.equal(origin.hostname, "localhost");
assert.notEqual(origin.port, "18443");
assert.equal(origin.origin, fixture.origin);
const BASE = "/api/member/browser-auth";
const MARKER = "sicha.player.auth.v1";
const COOKIE = "__Secure-sicha_player_refresh";
const UUID4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const counters = {
  login: 0,
  refresh: 0,
  logout: 0,
  me: 0,
  sentinel: 0,
  reloadRefresh: 0,
  twoTabRefresh: 0,
  unknownReloadRefresh: 0,
  boundaryProbes: 0,
  redirectRefused: 0,
  droppedResponse: 0,
  serviceWorkerBlocked: 0,
};
let phase = "launch";
let browser;
const pending = [];
const failures = [];
const accesses = new Set();
const families = [];
const epochs = [];
let redirectNext = false;
const statuses = [];
const runtimeClasses = [];
const inputBindings = [];
let observedFetch200 = 0;
let checkedFetch200 = 0;
const deadline = setTimeout(() => {
  process.stderr.write(
    JSON.stringify({ ok: false, phase, failure: "deadline" }),
  );
  process.exit(1);
}, 135000);

async function settle() {
  await Promise.all(pending);
  assert.equal(
    checkedFetch200,
    observedFetch200,
    "Every delivered Fetch200 must be checked",
  );
  assert.ok(checkedFetch200 > 0, "Access response observation is required");
  assert.equal(failures.length, 0, "Transport contract failed");
  assert.equal(runtimeClasses.length, 0, "Compiled Flutter runtime error");
}

async function instrument(page) {
  const observations = new Map();
  let sequence = 0;
  await page.exposeFunction("__playerAuthObservationBegin", () => {
    const id = ++sequence;
    observedFetch200++;
    let finish;
    pending.push(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const timer = setTimeout(() => {
      failures.push("access-response-observer-deadline");
      observations.delete(id);
      finish();
    }, 12000);
    observations.set(id, { finish, timer });
    return id;
  });
  await page.exposeFunction("__playerAuthObservationEnd", (id, receipt) => {
    const observation = observations.get(id);
    if (!observation) {
      failures.push("access-response-observer-unmatched");
      return;
    }
    try {
      assert.equal(receipt.ok, true, "Native Fetch clone body read failed");
      assert.ok(typeof receipt.text === "string");
      assert.ok(Buffer.byteLength(receipt.text, "utf8") <= 16 * 1024);
      const value = JSON.parse(receipt.text);
      assert.deepEqual(
        Object.keys(value).sort(),
        [
          "tokenType",
          "accessToken",
          "accessExpiresAt",
          "sessionKey",
          "sessionAbsoluteExpiresAt",
          "webEpoch",
          "requestId",
        ].sort(),
      );
      assert.equal(value.tokenType, "Bearer");
      assert.match(value.accessToken, /^[A-Za-z0-9_-]{43}$/);
      assert.match(value.webEpoch, UUID4);
      assert.match(value.sessionKey, UUID4);
      assert.match(value.requestId, UUID4);
      assert.equal(receipt.cacheControl, "no-store");
      accesses.add(value.accessToken);
      families.push(value.sessionKey);
      epochs.push(value.webEpoch);
      checkedFetch200++;
    } catch {
      // 원문 응답이나 assert 비교값을 오류 로그로 내보내지 않는다.
      failures.push("access-response-clone-validation");
    } finally {
      clearTimeout(observation.timer);
      observations.delete(id);
      observation.finish();
    }
  });
  await page.evaluateOnNewDocument(() => {
    const nativeFetch = window.fetch;
    window.fetch = async function (...args) {
      // 원래 인자·옵션을 그대로 전달하고 원래 Response 객체를 그대로 돌려준다.
      const response = await Reflect.apply(nativeFetch, this, args);
      const url = new URL(response.url);
      if (
        url.origin === location.origin &&
        response.status === 200 &&
        [
          "/api/member/browser-auth/login/local",
          "/api/member/browser-auth/refresh",
        ].includes(url.pathname)
      ) {
        const id = await window.__playerAuthObservationBegin();
        let clone;
        try {
          clone = response.clone();
        } catch {
          await window.__playerAuthObservationEnd(id, { ok: false });
          return response;
        }
        // 복제 스트림만 읽고 모든 비밀은 이 시험 프로세스의 메모리에만 둔다.
        void (async () => {
          const reader = clone.body.getReader();
          const chunks = [];
          let length = 0;
          try {
            for (;;) {
              const { done, value } = await reader.read();
              if (done) break;
              length += value.byteLength;
              if (length > 16 * 1024) {
                void reader.cancel();
                throw new Error("Auth clone exceeds bound");
              }
              chunks.push(value);
            }
            const bytes = new Uint8Array(length);
            let offset = 0;
            for (const chunk of chunks) {
              bytes.set(chunk, offset);
              offset += chunk.length;
            }
            await window.__playerAuthObservationEnd(id, {
              ok: true,
              text: new TextDecoder("utf-8", { fatal: true }).decode(bytes),
              cacheControl: response.headers.get("cache-control"),
            });
          } finally {
            reader.releaseLock();
          }
        })().catch(async () => {
          await window.__playerAuthObservationEnd(id, { ok: false });
        });
      }
      return response;
    };
  });
  page.setDefaultTimeout(12000);
  page.on(
    "pageerror",
    (error) =>
      runtimeClasses.length < 16 &&
      runtimeClasses.push({
        name: error.name,
        detail: String(error.message)
          .split(fixture.email)
          .join("[synthetic-email]")
          .split(fixture.password)
          .join("[synthetic-password]")
          .replace(/[A-Za-z0-9_-]{43}/g, "[opaque-value]")
          .replace(/https?:\/\/[^\s)]+/g, "[url]")
          .slice(0, 300),
        frames: [
          ...String(error.stack).matchAll(
            /\/(main\.dart\.js|flutter\.js|flutter_bootstrap\.js):(\d+):(\d+)/g,
          ),
        ]
          .slice(0, 8)
          .map((match) => ({
            source: match[1],
            line: Number(match[2]),
            column: Number(match[3]),
          })),
      }),
  );
  page.setDefaultNavigationTimeout(20000);
  await page.setRequestInterception(true);
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== fixture.origin) {
      if (request.isNavigationRequest() && url.href === "about:blank")
        return void request.continue();
      failures.push("off-origin-resource");
      return void request.abort();
    }
    if (url.pathname === "/player/redirect-sentinel") {
      counters.sentinel++;
      return void request.respond({ status: 500, body: "Unexpected redirect" });
    }
    const auth =
      url.pathname.startsWith(BASE) || url.pathname === "/api/member/auth/me";
    if (auth) {
      const headers = request.headers();
      if (
        url.search ||
        url.username ||
        url.password ||
        headers["x-sicha-player-web"] !== "1"
      )
        failures.push("request-boundary");
      if (url.pathname === BASE + "/login/local") counters.login++;
      else if (url.pathname === BASE + "/refresh") counters.refresh++;
      else if (url.pathname === BASE + "/logout") counters.logout++;
      else if (url.pathname === "/api/member/auth/me") counters.me++;
      else failures.push("unexpected-auth-route");
      if (url.pathname.startsWith(BASE)) {
        if (
          request.method() !== "POST" ||
          !UUID4.test(headers["x-sicha-player-web-epoch"] || "") ||
          headers.authorization ||
          headers.origin !== fixture.origin
        )
          failures.push("cookie-request-boundary");
        const body = JSON.parse(request.postData() || "null");
        const fields = Object.keys(body || {}).sort();
        if (url.pathname.endsWith("/refresh") && fields.length !== 0)
          failures.push("refresh-body");
        if (
          url.pathname.endsWith("/login/local") &&
          fields.join() !== "email,password"
        )
          failures.push("login-body");
        if (
          url.pathname.endsWith("/login/local") &&
          body.email !== fixture.email
        )
          failures.push("email-input-binding");
        if (
          url.pathname.endsWith("/login/local") &&
          inputBindings.length < 16
        ) {
          inputBindings.push({
            emailActualLength:
              typeof body.email === "string" ? body.email.length : null,
            emailExpectedLength: fixture.email.length,
            emailMatches: body.email === fixture.email,
            passwordMatches: body.password === fixture.password,
          });
        }
        if (
          url.pathname.endsWith("/login/local") &&
          body.password !== fixture.password
        )
          failures.push("password-input-binding");
        if (
          url.pathname.endsWith("/logout") &&
          (fields.join() !== "requestKey" || !UUID4.test(body.requestKey))
        )
          failures.push("logout-body");
      } else if (
        request.method() !== "GET" ||
        !headers.authorization?.startsWith("Bearer ")
      ) {
        failures.push("bearer-request-boundary");
      }
      if (redirectNext && url.pathname === BASE + "/login/local") {
        redirectNext = false;
        return void request.respond({
          status: 307,
          headers: { Location: fixture.origin + "/player/redirect-sentinel" },
          body: "",
        });
      }
    }
    void request.continue();
  });
  // ExtraInfo로 실제 전송 Cookie와 fetch metadata를 검증하고 본문은 보관하지 않는다.
  const client = await page.createCDPSession();
  await client.send("Network.enable", {
    maxTotalBufferSize: 64 * 1024,
    maxResourceBufferSize: 16 * 1024,
    maxPostDataSize: 0,
  });
  const routes = new Map();
  const extra = new Map();
  const inspect = (id) => {
    const path = routes.get(id),
      headers = extra.get(id);
    if (
      !path ||
      !headers ||
      !(path.startsWith(BASE) || path === "/api/member/auth/me")
    )
      return;
    const normalized = Object.fromEntries(
      Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]),
    );
    if (normalized["sec-fetch-site"] !== "same-origin")
      failures.push("fetch-site");
    if (path === "/api/member/auth/me" && normalized.cookie !== undefined)
      failures.push("bearer-cookie");
  };
  client.on("Network.requestWillBeSent", ({ requestId, request }) => {
    routes.set(requestId, new URL(request.url).pathname);
    inspect(requestId);
  });
  client.on("Network.requestWillBeSentExtraInfo", ({ requestId, headers }) => {
    extra.set(requestId, headers);
    inspect(requestId);
  });
  client.on("Network.responseReceived", ({ requestId, response }) => {
    const path = new URL(response.url).pathname;
    if (
      path.startsWith(BASE) ||
      path === "/api/member/auth/me" ||
      response.status >= 400
    ) {
      statuses.push({
        kind: path.startsWith(BASE)
          ? "cookie-auth"
          : path === "/api/member/auth/me"
            ? "bearer-me"
            : "asset",
        status: response.status,
      });
    }
  });
  return client;
}

async function semantics(page) {
  await page.waitForSelector("flutter-view");
  const placeholder = await page.$("flt-semantics-placeholder");
  if (placeholder) await placeholder.evaluate((element) => element.click());
  await page.waitForSelector("flt-semantics");
}
async function route(page, expected) {
  await page.bringToFront();
  await page.waitForFunction(
    (value) =>
      location.hash === "#/" + value || location.pathname.endsWith("/" + value),
    {},
    expected,
  );
  await semantics(page);
}
async function button(page, label) {
  await page.bringToFront();
  await page.locator(`aria/${label}[role="button"]`).click();
}
async function input(page, label, value) {
  const field = await page.waitForSelector(`aria/${label}[role="textbox"]`);
  assert.ok(field);
  // DOM focus는 Flutter semantics 작업을 보내며 입력 핸들러는 다음 갱신에 붙는다.
  await field.focus();
  await field.evaluate(async (element) => {
    await new Promise((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(resolve)),
    );
    if (
      !(
        element instanceof HTMLInputElement ||
        element instanceof HTMLTextAreaElement
      ) ||
      document.activeElement !== element ||
      element.disabled ||
      element.readOnly
    ) {
      throw new Error(
        "Semantic textbox did not become the active editable field",
      );
    }
    // 네이티브 선택만 사용하고 DOM 값이나 Dart 컨트롤러 상태는 대입하지 않는다.
    element.select();
  });
  // Chromium의 실제 입력 이벤트를 사용하며 글자별 합성 키 이벤트는 만들지 않는다.
  await page.keyboard.sendCharacter(value);
  await page.waitForFunction(
    (element, expected) =>
      document.activeElement === element && element.value === expected,
    {},
    field,
    value,
  );
  await field.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  assert.equal(
    runtimeClasses.length,
    0,
    "Compiled Flutter text editing runtime error",
  );
}
async function login(page) {
  await route(page, "login");
  await page.waitForSelector("aria/LOCAL 로그인");
  await input(page, "이메일", fixture.email);
  await input(page, "비밀번호", fixture.password);
  await button(page, "로그인");
}
async function logout(page) {
  const [response] = await Promise.all([
    page.waitForResponse(
      (value) => new URL(value.url()).pathname === BASE + "/logout",
    ),
    button(page, "로그아웃"),
  ]);
  assert.equal(response.status(), 200);
  await route(page, "login");
}
async function account(page) {
  await route(page, "account");
  await page.waitForFunction(
    (nickname) => document.body.textContent.includes("현재 계정: " + nickname),
    {},
    fixture.nickname,
  );
  await settle();
}
async function cookie() {
  const values = await browser.defaultBrowserContext().cookies();
  const result = values.filter((value) => value.name === COOKIE);
  assert.equal(result.length, 1);
  const value = result[0];
  assert.equal(value.httpOnly, true);
  assert.equal(value.secure, true);
  assert.equal(value.sameSite, "Strict");
  assert.equal(value.domain, "localhost");
  assert.equal(value.path, BASE);
  assert.match(value.value, /^[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/);
  assert.match(value.value.slice(0, 36), UUID4);
  assert.equal(value.value.slice(0, 36), epochs.at(-1));
  return value;
}
async function storage(page, cookieValue) {
  const value = await page.evaluate(() => ({
    cookie: document.cookie,
    local: Object.fromEntries(Object.entries(localStorage)),
    session: Object.fromEntries(Object.entries(sessionStorage)),
    controlled: navigator.serviceWorker.controller !== null,
  }));
  assert.equal(value.cookie.includes(COOKIE), false);
  assert.deepEqual(value.session, {});
  assert.deepEqual(Object.keys(value.local), [MARKER]);
  const marker = JSON.parse(value.local[MARKER]);
  assert.equal(marker.format, 1);
  assert.match(marker.epoch, UUID4);
  assert.ok(
    ["ready", "login", "refresh", "logout", "blocked"].includes(
      marker.operation,
    ),
  );
  assert.deepEqual(
    Object.keys(marker).sort(),
    (marker.operation === "ready"
      ? ["format", "epoch", "operation"]
      : ["format", "epoch", "operation", "attempt"]
    ).sort(),
  );
  if (marker.operation !== "ready") assert.match(marker.attempt, UUID4);
  const serialized = JSON.stringify(value);
  for (const secret of accesses)
    assert.equal(serialized.includes(secret), false);
  if (cookieValue) {
    assert.equal(serialized.includes(cookieValue.slice(37)), false);
    assert.equal(marker.epoch, cookieValue.slice(0, 36));
  }
  return marker;
}
async function tombstone(page) {
  await page.waitForFunction(
    (key) => {
      const value = JSON.parse(localStorage.getItem(key) || "null");
      return value && value.operation === "blocked";
    },
    {},
    MARKER,
  );
  await route(page, "login");
  assert.equal((await storage(page)).operation, "blocked");
}

// 아래 HTTPS 경계 탐침은 컴파일된 Fetch 어댑터 검증 증거와 구별한다.
async function probe(path, method, headers, body = "{}") {
  counters.boundaryProbes++;
  const payload = method === "GET" ? undefined : Buffer.from(body, "utf8");
  return new Promise((resolve, reject) => {
    const request = https.request(
      new URL(path, fixture.origin),
      {
        method,
        hostname: "127.0.0.1",
        servername: "localhost",
        rejectUnauthorized: false,
        headers: {
          Host: origin.host,
          "Content-Type": "application/json",
          "Content-Length": payload?.length ?? 0,
          ...headers,
        },
        timeout: 8000,
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("error", reject);
        response.on("end", () => {
          try {
            resolve({
              status: response.statusCode,
              body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
            });
          } catch {
            reject(
              new Error(
                `probe JSON required: ${method} ${response.statusCode}`,
              ),
            );
          }
        });
      },
    );
    request.on("timeout", () => request.destroy(new Error("probe deadline")));
    request.on("error", reject);
    request.end(payload);
  });
}

try {
  browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    acceptInsecureCerts: true,
    protocolTimeout: 15000,
    // 위에서 확인한 시험 전용 루프백 출처이며 운영 TLS 정책이 아니다.
    args: ["--no-sandbox"],
  });
  // 비밀 없는 루트 쿠키로 실제 /me 전송의 credentials:omit을 확인한다.
  await browser.defaultBrowserContext().setCookie({
    name: "player_test_nonsecret",
    value: "probe",
    domain: "localhost",
    path: "/",
    secure: true,
    sameSite: "Strict",
  });
  const page = await browser.newPage();
  const client = await instrument(page);
  phase = "compiled-ui-login";
  await page.goto(fixture.origin + "/player/", { waitUntil: "networkidle0" });
  await semantics(page);
  await login(page);
  await account(page);
  const initial = await cookie();
  assert.equal((await storage(page, initial.value)).operation, "ready");
  assert.equal(
    await page.evaluate(
      async () => (await navigator.serviceWorker.getRegistrations()).length,
    ),
    0,
  );
  assert.equal(
    await page.evaluate(() => navigator.serviceWorker.controller),
    null,
  );
  assert.ok(counters.me >= 1);
  const family = families.at(-1),
    epoch = epochs.at(-1),
    access = [...accesses].at(-1);

  phase = "reload-rotation";
  let before = counters.refresh;
  const meBefore = counters.me;
  await page.reload({ waitUntil: "networkidle0" });
  await account(page);
  counters.reloadRefresh = counters.refresh - before;
  assert.equal(counters.reloadRefresh, 1);
  assert.ok(counters.me > meBefore);
  assert.notEqual((await cookie()).value, initial.value);
  assert.equal(families.at(-1), family);
  assert.equal(epochs.at(-1), epoch);
  assert.notEqual([...accesses].at(-1), access);

  phase = "two-tabs-serialized";
  const tab = await browser.newPage();
  await instrument(tab);
  before = counters.refresh;
  await Promise.all([
    page.reload({ waitUntil: "networkidle0" }),
    tab.goto(fixture.origin + "/player/", { waitUntil: "networkidle0" }),
  ]);
  // 회전 시작은 병렬로 유지하되 화면 검증은 실제 활성 탭에서 각각 수행한다.
  await account(page);
  await account(tab);
  counters.twoTabRefresh = counters.refresh - before;
  assert.equal(counters.twoTabRefresh, 2);
  assert.ok(families.slice(-2).every((value) => value === family));
  assert.ok(epochs.slice(-2).every((value) => value === epoch));
  await storage(tab, (await cookie()).value);

  phase = "endpoint-boundary-probes";
  const proof = await cookie();
  const headers = {
    Origin: fixture.origin,
    "X-Sicha-Player-Web": "1",
    "X-Sicha-Player-Web-Epoch": proof.value.slice(0, 36),
    "Sec-Fetch-Site": "same-origin",
    Cookie: COOKIE + "=" + proof.value,
  };
  const probes = [
    await probe(BASE + "/refresh", "POST", {
      ...headers,
      Origin: "https://wrong.example.invalid",
    }),
    await probe(BASE + "/refresh", "POST", {
      ...headers,
      "X-Sicha-Player-Web": "0",
    }),
    await probe(BASE + "/refresh", "OPTIONS", headers),
    await probe("/api/member/auth/me", "GET", headers),
    await probe("/api/member/auth/me", "GET", {
      ...headers,
      Authorization: "Bearer " + [...accesses].at(-1),
    }),
  ];
  for (const result of probes) {
    assert.equal(result.status, 403);
    assert.deepEqual(Object.keys(result.body).sort(), [
      "code",
      "message",
      "requestId",
    ]);
    assert.equal(result.body.code, "FORBIDDEN");
    assert.equal(result.body.message, "FORBIDDEN");
    assert.match(result.body.requestId, UUID4);
  }

  phase = "logout-cross-tab";
  await logout(page);
  await route(page, "login");
  await route(tab, "login");
  assert.equal(
    (await browser.defaultBrowserContext().cookies()).some(
      (value) => value.name === COOKIE,
    ),
    false,
  );
  const logoutMe = counters.me;
  await Promise.all([
    page.reload({ waitUntil: "networkidle0" }),
    tab.reload({ waitUntil: "networkidle0" }),
  ]);
  await route(page, "login");
  await route(tab, "login");
  assert.equal(counters.me, logoutMe);
  assert.equal(
    (await browser.defaultBrowserContext().cookies()).some(
      (value) => value.name === COOKIE,
    ),
    false,
  );
  await Promise.all([storage(page), storage(tab)]);
  await tab.close();

  phase = "actual-fetch-redirect-refusal";
  redirectNext = true;
  before = counters.login;
  await login(page);
  await tombstone(page);
  assert.equal(counters.login - before, 1);
  assert.equal(counters.sentinel, 0);
  const redirectRefresh = counters.refresh;
  await page.reload({ waitUntil: "networkidle0" });
  await tombstone(page);
  assert.equal(counters.refresh, redirectRefresh);
  counters.redirectRefused = 1;
  const cleanup = counters.logout;
  await login(page);
  await account(page);
  assert.equal(counters.logout - cleanup, 1);

  phase = "unknown-refresh-tombstone";
  await client.send("Fetch.enable", {
    patterns: [
      {
        urlPattern: fixture.origin + BASE + "/refresh",
        requestStage: "Response",
      },
    ],
  });
  const dropped = new Promise((resolve, reject) => {
    client.once("Fetch.requestPaused", (event) => {
      (async () => {
        assert.equal(event.responseStatusCode, 200);
        await client.send("Fetch.failRequest", {
          requestId: event.requestId,
          errorReason: "Failed",
        });
        await client.send("Fetch.disable");
        resolve();
      })().catch(reject);
    });
  });
  await page.reload({ waitUntil: "networkidle0" });
  await dropped;
  counters.droppedResponse = 1;
  await tombstone(page);
  before = counters.refresh;
  await page.reload({ waitUntil: "networkidle0" });
  await tombstone(page);
  counters.unknownReloadRefresh = counters.refresh - before;
  assert.equal(counters.unknownReloadRefresh, 0);
  await login(page);
  await account(page);

  phase = "controlling-worker-fail-closed";
  await page.evaluate(async () => {
    await navigator.serviceWorker.register("/player/test-worker.js", {
      scope: "/player/",
    });
    await navigator.serviceWorker.ready;
  });
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
  before = counters.refresh;
  await page.reload({ waitUntil: "networkidle0" });
  await route(page, "session");
  assert.equal(counters.refresh, before);
  const authBefore =
    counters.login + counters.logout + counters.refresh + counters.me;
  await button(page, "인증 다시 확인");
  await page.waitForFunction(() =>
    document.body.textContent.includes("계정 확인에 실패했습니다"),
  );
  assert.equal(
    counters.login + counters.logout + counters.refresh + counters.me,
    authBefore,
  );
  counters.serviceWorkerBlocked = 1;
  // 시험이 만든 워커만 제거하며 제품 코드는 이 동작을 수행하지 않는다.
  await page.evaluate(async () => {
    for (const registration of await navigator.serviceWorker.getRegistrations())
      await registration.unregister();
  });
  await page.close();
  const finalPage = await browser.newPage();
  await instrument(finalPage);
  await finalPage.goto(fixture.origin + "/player/", {
    waitUntil: "networkidle0",
  });
  await account(finalPage);
  await logout(finalPage);
  await settle();
  assert.equal(counters.sentinel, 0);
  assert.equal(
    (await browser.defaultBrowserContext().cookies()).some(
      (value) => value.name === COOKIE,
    ),
    false,
  );
  phase = "complete";
  process.stdout.write(
    JSON.stringify({
      ok: true,
      counters,
      responseObservation: {
        deliveredFetch200: observedFetch200,
        checkedFetch200,
      },
      evidence: {
        ui: "compiled-Flutter-semantics",
        transport: "compiled-Fetch",
        accessResponse:
          "nativeFetch Response.clone observation of untouched compiled client",
        boundaries: "explicit-HTTPS-endpoint-probes",
        nativeIos: "not-tested",
      },
    }),
  );
} catch (error) {
  // 고정 진단 단계만 기록하며 자격 증명·쿠키·URL·응답 원문은 남기지 않는다.
  const pages = browser ? await browser.pages() : [];
  const views = await Promise.all(
    pages.map(async (page) => {
      try {
        return await page.evaluate(() => ({
          flutterViews: document.querySelectorAll("flutter-view").length,
          placeholders: document.querySelectorAll("flt-semantics-placeholder")
            .length,
          semanticNodes: document.querySelectorAll("flt-semantics").length,
          textboxes: document.querySelectorAll('[role="textbox"]').length,
          editableInputs: document.querySelectorAll(
            'input:not([type="hidden"]),textarea',
          ).length,
          buttons: document.querySelectorAll('[role="button"]').length,
          markerState:
            JSON.parse(localStorage.getItem("sicha.player.auth.v1") || "null")
              ?.operation ?? "absent",
        }));
      } catch {
        return { unavailable: true };
      }
    }),
  );
  const line = error.stack?.match(/player-web-auth\.mjs:(\d+):(\d+)/)?.slice(1);
  const detail = String(error.message)
    .split(fixture.email)
    .join("[synthetic-email]")
    .split(fixture.password)
    .join("[synthetic-password]")
    .replace(/[A-Za-z0-9_-]{43}/g, "[opaque-value]")
    .replace(/https?:\/\/[^\s)]+/g, "[url]")
    .slice(0, 600);
  process.stderr.write(
    JSON.stringify({
      ok: false,
      phase,
      failure: error.name,
      detail,
      line,
      views,
      statuses,
      runtimeClasses,
      inputBindings,
      counters,
      responseObservation: {
        deliveredFetch200: observedFetch200,
        checkedFetch200,
      },
      contractFailures: failures,
    }),
  );
  process.exitCode = 1;
} finally {
  clearTimeout(deadline);
  if (browser) await browser.close();
}
