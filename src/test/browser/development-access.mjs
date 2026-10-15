import assert from "node:assert/strict";
import puppeteer from "puppeteer-core";
import { existsSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { isBrowserFaviconRequest } from "./editor-workspace.mjs";

// 입력·출력 영수증에는 비밀번호·쿠키·access 토큰을 싣지 않는다.
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const { origin } = JSON.parse(Buffer.concat(chunks).toString("utf8"));
const uri = new URL(origin);
assert.equal(uri.protocol, "https:");
assert.equal(uri.hostname, "localhost");
assert.equal(uri.origin, origin);
assert.ok(uri.port);
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const executablePath =
  process.env.CHROME_BIN ||
  [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
  ].find(existsSync);
assert.ok(executablePath, "Chrome executable required");
const errors = [];
const browserResourceResponses = [];
let browserResourceErrors = 0;
const pending = [];
const memberKeys = [];
const ephemeralPasswords = [];
const ephemeralAccess = [];
const memberSessions = [];
const refreshSessions = [];
const loginEpochs = [];
const counts = { login: 0, refresh: 0, me: 0, records: 0, admin: 0, create: 0 };
let browser;
const observedBodies = new Map();
let phase = "launch";
const deadline = setTimeout(() => {
  process.stderr.write(
    JSON.stringify({ ok: false, phase, failure: "deadline" }),
  );
  process.exit(1);
}, 155000);

async function drain() {
  for (let offset = 0; offset < pending.length; ) {
    const batch = pending.slice(offset);
    offset = pending.length;
    await Promise.all(batch);
  }
  assert.ok(
    browserResourceErrors <= browserResourceResponses.length,
    "Every favicon console error needs a proven browser-resource response",
  );
  assert.equal(errors.length, 0, "Runtime or observable API contract failure");
}

/** 요청·응답은 변경하지 않고 실제 응답 사본과 네트워크 관측을 순서대로 결속한다. */
function bodyQueue(key) {
  if (!observedBodies.has(key))
    observedBodies.set(key, { values: [], waiters: [] });
  return observedBodies.get(key);
}

/** 제품이 실제로 받은 JSON 사본을 문서 이탈 전에 관측한다. 원래 Response는 그대로 소비한다. */
async function observedJson(response) {
  const queue = bodyQueue(
    `${response.request().method()} ${new URL(response.url()).pathname}`,
  );
  if (queue.values.length) return queue.values.shift();
  return new Promise((resolve) => queue.waiters.push(resolve));
}

try {
  browser = await puppeteer.launch({
    executablePath,
    headless: true,
    acceptInsecureCerts: true,
    protocolTimeout: 35000,
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });
  const page = await browser.newPage();
  await page.exposeFunction(
    "__observeDevelopmentBody",
    ({ path, method, body }) => {
      const queue = bodyQueue(`${method} ${path}`);
      if (queue.waiters.length) queue.waiters.shift()(body);
      else queue.values.push(body);
    },
  );
  await page.evaluateOnNewDocument(() => {
    const originalFetch = window.fetch.bind(window);
    const paths = new Set([
      "/__development/member-credentials",
      "/api/member/browser-auth/login/local",
      "/api/member/browser-auth/refresh",
      "/api/member/auth/me",
    ]);
    window.fetch = async (input, options) => {
      const response = await originalFetch(input, options);
      const url = new URL(
        input instanceof Request ? input.url : input,
        location.href,
      );
      if (
        url.origin === location.origin &&
        paths.has(url.pathname) &&
        response.status === 200
      ) {
        await window.__observeDevelopmentBody({
          path: url.pathname,
          method: (
            options?.method || (input instanceof Request ? input.method : "GET")
          ).toUpperCase(),
          body: await response.clone().json(),
        });
      }
      return response;
    };
  });
  page.setDefaultTimeout(20000);
  page.on("pageerror", () => errors.push("pageerror"));
  page.on("console", (message) => {
    if (message.type() === "error") {
      const path = new URL(message.location().url || origin, origin).pathname;
      if (path === "/favicon.ico") browserResourceErrors++;
      else errors.push(`console-error:${path}`);
    }
  });
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (isBrowserFaviconRequest(response.request(), origin)) {
      browserResourceResponses.push(response.status());
      return;
    }
    const watched = [
      "/api/member/browser-auth/login/local",
      "/api/member/browser-auth/refresh",
      "/api/member/auth/me",
      "/api/member/records",
      "/admin/api/auth/me",
      "/admin/api/stories",
      "/__development/member-credentials",
    ];
    if (!watched.includes(path)) return;
    let contractStep = "status";
    let observedShape = "";
    pending.push(
      (async () => {
        assert.equal(
          response.status(),
          path === "/admin/api/stories" &&
            response.request().method() === "POST"
            ? 201
            : 200,
        );
        const headers = Object.fromEntries(
          Object.entries(response.request().headers()).map(([name, value]) => [
            name.toLowerCase(),
            value,
          ]),
        );
        observedShape = `epoch:${typeof headers["x-sicha-player-web-epoch"]}/${headers["x-sicha-player-web-epoch"]?.length ?? 0};authorization:${typeof headers.authorization}/${headers.authorization?.length ?? 0}`;
        if (path.startsWith("/api/member/")) {
          contractStep = "web-header";
          assert.equal(headers["x-sicha-player-web"], "1");
          if (path.startsWith("/api/member/browser-auth/")) {
            contractStep = "epoch";
            assert.match(headers["x-sicha-player-web-epoch"], uuid);
            contractStep = "issued-body";
            const value = await observedJson(response);
            contractStep = "issued-projection";
            assert.equal(value.webEpoch, headers["x-sicha-player-web-epoch"]);
            assert.ok(typeof value.accessToken === "string");
            assert.equal(value.tokenType, "Bearer");
            ephemeralAccess.push(value.accessToken);
            if (path.endsWith("/refresh"))
              refreshSessions.push(value.sessionKey);
            else loginEpochs.push(value.webEpoch);
            counts[path.endsWith("/refresh") ? "refresh" : "login"]++;
          } else {
            contractStep = "bearer";
            assert.ok(/^Bearer [A-Za-z0-9_-]{43}$/.test(headers.authorization));
            if (path.endsWith("/me")) {
              contractStep = "member-body";
              const value = await observedJson(response);
              contractStep = "member-key";
              assert.match(value.memberKey, uuid);
              memberKeys.push(value.memberKey);
              memberSessions.push(value.sessionKey);
              counts.me++;
            } else counts.records++;
          }
        } else if (path === "/admin/api/auth/me") counts.admin++;
        else if (
          path === "/admin/api/stories" &&
          response.request().method() === "POST"
        ) {
          // 실제 제품 생성은 CSRF 헤더와 세션 쿠키를 함께 사용해야 한다.
          assert.ok(headers["x-csrf-token"] || headers["x-xsrf-token"]);
          counts.create++;
        } else if (path === "/__development/member-credentials") {
          const value = await observedJson(response);
          assert.deepEqual(Object.keys(value).sort(), ["email", "password"]);
          assert.ok(
            typeof value.password === "string" && value.password.length > 0,
          );
          ephemeralPasswords.push(value.password);
        }
      })().catch((failure) =>
        errors.push(
          `api-contract:${path}:${response.status()}:${contractStep}:${observedShape}:${failure.name}`,
        ),
      ),
    );
  });

  phase = "development-layout";
  assert.equal((await page.goto(`${origin}/__development`)).status(), 200);
  await page.waitForSelector('#development-status[data-ready="true"]');
  for (const width of [360, 768, 1440]) {
    await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
    assert.ok(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      "Horizontal overflow",
    );
    for (const selector of [
      'button[data-account="a"]',
      'button[data-account="b"]',
      "#administrator-entry button",
    ]) {
      await page.focus(selector);
      assert.ok(
        await page.$eval(
          selector,
          (button) =>
            document.activeElement === button &&
            !button.disabled &&
            button.checkVisibility(),
        ),
      );
    }
    // 화면에는 자격 원문이 없으며 캡처는 메모리에서만 검사한다.
    assert.ok((await page.screenshot()).length > 0);
  }

  for (const slot of ["a", "b"]) {
    phase = `member-${slot}`;
    await page.goto(`${origin}/__development`);
    const before = memberKeys.length;
    await page.focus(`button[data-account="${slot}"]`);
    const authenticated = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/member/auth/me" &&
        response.status() === 200,
    );
    const playRestored = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
          "/api/member/browser-auth/refresh" && response.status() === 200,
    );
    await Promise.all([
      page.waitForNavigation(),
      page.keyboard.press("Enter"),
      authenticated,
      playRestored,
    ]);
    assert.equal(new URL(page.url()).pathname, "/play");
    await page.waitForFunction(
      () =>
        document.querySelector("#main")?.getAttribute("aria-busy") ===
          "false" &&
        document.querySelector("#current-button")?.disabled === false,
    );
    phase = `member-${slot}-initial-session`;
    await drain();
    assert.ok(memberKeys.length > before);
    const family = memberSessions.at(-1);
    assert.equal(refreshSessions.at(-1), family);
    const refreshes = counts.refresh;
    const restored = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
          "/api/member/browser-auth/refresh" && response.status() === 200,
    );
    await Promise.all([page.reload(), restored]);
    await page.waitForFunction(
      () =>
        document.querySelector("#main")?.getAttribute("aria-busy") ===
          "false" &&
        document.querySelector("#current-button")?.disabled === false,
    );
    phase = `member-${slot}-reload-session`;
    await drain();
    assert.ok(
      counts.refresh > refreshes,
      "Product web-session must restore after navigation",
    );
    assert.equal(refreshSessions.at(-1), family);
    const records = page.waitForResponse(
      (response) => new URL(response.url()).pathname === "/api/member/records",
    );
    await page.goto(`${origin}/records`);
    await records;
    await page.waitForSelector("main[aria-busy='false']");
    phase = `member-${slot}-credential-storage`;
    await drain();
    const clean = await page.evaluate(
      (passwords) => {
        const values = [
          location.href,
          document.documentElement.outerHTML,
          ...Object.values(localStorage),
          ...Object.values(sessionStorage),
        ];
        return passwords.every((password) =>
          values.every((text) => !text.includes(password)),
        );
      },
      [...ephemeralPasswords, ...ephemeralAccess],
    );
    assert.ok(clean, "Credential escaped ephemeral authentication flow");
  }
  phase = "member-switch-validation";
  const distinct = [...new Set(memberKeys)];
  assert.equal(distinct.length, 2, "A/B must be distinct real members");
  assert.equal(counts.login, 2);
  assert.equal(
    new Set(loginEpochs).size,
    2,
    "Account replacement must fence old web epochs",
  );
  assert.ok(counts.refresh >= 4);
  assert.ok(counts.records >= 2);

  phase = "administrator";
  await page.goto(`${origin}/__development`);
  const me = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/admin/api/auth/me",
  );
  const homeAudit = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/admin/api/history/navigation",
  );
  await page.focus("#administrator-entry button");
  await Promise.all([page.waitForNavigation(), page.keyboard.press("Enter")]);
  await me;
  assert.equal(new URL(page.url()).pathname, "/admin");
  await page.waitForFunction(() =>
    document
      .querySelector("#notice")
      ?.textContent.includes("현재 세션을 확인했습니다"),
  );
  assert.equal((await homeAudit).status(), 204);
  // 기존 행위자별 1초 감사 전송 한도를 바꾸지 않고 실제 응답 완료 뒤 다음 화면을 확인한다.
  await delay(1100);
  const listAudit = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/admin/api/history/navigation",
  );
  await page.goto(`${origin}/admin/stories`);
  await page.waitForSelector("#create-panel:not([hidden])");
  assert.equal((await listAudit).status(), 204);
  await delay(1100);
  const title = `합성 개발 회귀 ${randomUUID()}`;
  await page.type("#title", title);
  const editorAudit = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/admin/api/history/navigation",
  );
  await Promise.all([
    page.waitForNavigation(),
    page.click("#create-form button"),
  ]);
  await page.waitForSelector("#editor:not([hidden])");
  assert.equal(
    await page.$eval("#basic-title-record", (node) => node.textContent.trim()),
    title,
  );
  assert.equal((await editorAudit).status(), 204);
  const match = new URL(page.url()).pathname.match(
    /^\/admin\/stories\/([A-Z0-9_]{1,40})\//,
  );
  assert.ok(match, "Product editor route must contain generated story code");
  await drain();
  assert.ok(counts.admin > 0);
  assert.equal(counts.create, 1, "Never retry a mutation automatically");
  process.stdout.write(
    JSON.stringify({
      ok: true,
      memberKeys: distinct,
      storyCode: match[1],
      runtimeErrors: errors.length,
      browserResourceErrors,
      browserResourceResponses,
    }),
  );
} catch (_) {
  // Assertion 상세에는 응답·헤더·쿠키가 들어갈 수 있으므로 고정 실패 분류만 쓴다.
  process.stderr.write(
    JSON.stringify({
      ok: false,
      phase,
      failure: "development-browser-contract",
      counts,
      observedMembers: memberKeys.length,
      observedRefreshes: refreshSessions.length,
      familyMatches: refreshSessions.at(-1) === memberSessions.at(-1),
      failures: errors.slice(0, 20),
      browserResourceErrors,
      browserResourceResponses,
    }),
  );
  process.exitCode = 1;
} finally {
  clearTimeout(deadline);
  if (browser) await browser.close();
}
