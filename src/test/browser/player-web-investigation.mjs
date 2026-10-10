import puppeteer from "puppeteer-core";
import assert from "node:assert/strict";
import https from "node:https";
import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";

// 합성 계정·자격은 stdin과 프로세스 메모리에만 두며 stdout에는 정수 증거만 남긴다.
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const fixture = JSON.parse(Buffer.concat(chunks).toString("utf8"));
const origin = new URL(fixture.origin);
assert.equal(origin.protocol, "https:");
assert.equal(origin.hostname, "localhost");
assert.equal(origin.origin, fixture.origin);
assert.notEqual(origin.port, "18443");
assert.ok(Number(origin.port) >= 1024 && Number(origin.port) <= 65535);
assert.equal(fixture.accounts.length, 2);
const UUID4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
assert.match(fixture.testKey, UUID4);
const AUTH = "/api/member/browser-auth";
const BASE = "/api/playtests/" + fixture.testKey;
const MARKER = "sicha.player.auth.v1";
const counters = {
  login: 0,
  logout: 0,
  accept: 0,
  ready: 0,
  start: 0,
  hint: 0,
  heartbeat: 0,
  materials: 0,
  validatedMaterials: 0,
  staleMaterialsDenied: 0,
  droppedAccept: 0,
  originalReplay: 0,
  boundaryProbes: 0,
  backgroundHidden: 0,
  sameRoleRecovered: 0,
  accountPurged: 0,
  runtimeErrors: 0,
  excludedCalls: 0,
};
let phase = "launch";
let browser;
const memberBrowsers = [];
const failures = [];
const pending = new Set();
const viewStates = [];
const signals = new EventEmitter();
const deadline = setTimeout(() => {
  process.stderr.write(
    JSON.stringify({ ok: false, phase, failure: "deadline" }),
  );
  process.exit(1);
}, 140000);

/** 관측 상태가 바뀔 때만 조건을 확인하고 최대 12초 뒤 고정 오류로 종료한다. */
function signalState(predicate) {
  return new Promise((resolve, reject) => {
    const check = () => {
      if (!predicate()) return;
      clearTimeout(timer);
      signals.off("change", check);
      resolve();
    };
    const timer = setTimeout(() => {
      signals.off("change", check);
      reject(new Error("Observation deadline"));
    }, 12000);
    signals.on("change", check);
    check();
  });
}

/** 비밀 원문 없이 관측 작업의 실패를 수집하고 즉시 rejection을 처리한다. */
function track(work, kind) {
  const task = work.catch(() => failures.push("observation-" + kind));
  pending.add(task);
  void task.then(() => {
    pending.delete(task);
    signals.emit("change");
  });
  return task;
}

/** Flutter 요청과 별개의 실제 loopback HTTPS 경계·현재 투영 조회다. */
function probe(path, method, headers, body) {
  counters.boundaryProbes++;
  const payload = body === undefined ? undefined : Buffer.from(body, "utf8");
  return new Promise((resolve, reject) => {
    const request = https.request(
      new URL(path, fixture.origin),
      {
        method,
        hostname: "127.0.0.1",
        servername: "localhost",
        rejectUnauthorized: false,
        timeout: 8000,
        headers: {
          Host: origin.host,
          "Content-Type": "application/json",
          "Content-Length": payload?.length ?? 0,
          ...headers,
        },
      },
      (response) => {
        const parts = [];
        let bytes = 0;
        response.on("data", (part) => {
          bytes += part.length;
          if (bytes > 1024 * 1024) request.destroy(new Error("Probe bound"));
          else parts.push(part);
        });
        response.on("error", () => reject(new Error("Probe response failed")));
        response.on("end", () => {
          try {
            assert.match(
              response.headers["content-type"] ?? "",
              /^application\/json(?:;|$)/i,
            );
            const value = JSON.parse(Buffer.concat(parts).toString("utf8"));
            assert.ok(
              value && typeof value === "object" && !Array.isArray(value),
            );
            resolve({ status: response.statusCode, value });
          } catch {
            reject(new Error("Probe JSON object required"));
          }
        });
      },
    );
    request.on("timeout", () => request.destroy(new Error("Probe deadline")));
    request.on("error", () => reject(new Error("Probe transport failed")));
    request.end(payload);
  });
}

function bearerHeaders(state) {
  return {
    Authorization: "Bearer " + state.access,
    "X-Sicha-Player-Web": "1",
    "Sec-Fetch-Site": "same-origin",
  };
}

/** 자격·판정 원문이 섞이지 않은 실제 역할 투영을 서버의 현재 투영과 비교한다. */
async function validateMaterials(state, value, owner) {
  assert.deepEqual(
    Object.keys(value).sort(),
    [
      "basic",
      "role",
      "persons",
      "clues",
      "openedHints",
      "requiredNotices",
      "requestId",
    ].sort(),
  );
  assert.match(value.requestId, UUID4);
  const headers = bearerHeaders({ access: owner.access });
  const current = await probe(BASE, "GET", headers);
  // 응답 뒤 실제 로그아웃이 완료되면 원래 발급 자격의 현재 거절을 검증하고 화면 관측으로 재사용하지 않는다.
  if (current.status === 401 && phase === "logout-account-change-purge") {
    assert.equal(value.role.code, owner.roleCode);
    counters.staleMaterialsDenied++;
    return;
  }
  assert.equal(current.status, 200);
  assert.equal(value.role.code, current.value.self.roleCode);
  assert.ok(["R1", "R2"].includes(value.role.code));
  assert.equal(value.clues.length, 1);
  assert.equal(value.clues[0].code, value.role.code === "R1" ? "C1" : "C2");
  assert.deepEqual(Object.keys(value.role).sort(), ["brief", "code", "name"]);
  for (const clue of value.clues)
    assert.deepEqual(Object.keys(clue).sort(), [
      "body",
      "code",
      "personCode",
      "title",
    ]);
  for (const person of value.persons)
    assert.deepEqual(Object.keys(person).sort(), [
      "code",
      "name",
      "publicText",
    ]);
  for (const hint of value.openedHints) {
    assert.deepEqual(Object.keys(hint).sort(), ["body", "level"]);
    assert.ok(
      Number.isInteger(hint.level) && hint.level >= 1 && hint.level <= 3,
    );
    assert.equal(typeof hint.body, "string");
  }
  const serialized = JSON.stringify(value);
  for (const excluded of [
    "secretText",
    "sourceText",
    "gradeSamples",
    "ruleData",
    "facts",
    "rubrics",
  ])
    assert.equal(serialized.includes('"' + excluded + '"'), false);
  const projection = await probe(BASE + "/materials", "GET", headers);
  if (projection.status === 401 && phase === "logout-account-change-purge") {
    assert.equal(value.role.code, owner.roleCode);
    counters.staleMaterialsDenied++;
    return;
  }
  assert.equal(projection.status, 200);
  // 힌트는 관측 사이 새로 열릴 수 있으므로 역할 원문을 엄격히 비교하고 기존 힌트는 부분집합으로 검사한다.
  for (const key of ["basic", "role", "persons", "clues", "requiredNotices"])
    assert.deepEqual(value[key], projection.value[key]);
  for (const hint of value.openedHints)
    assert.deepEqual(
      hint,
      projection.value.openedHints.find((item) => item.level === hint.level),
    );
  state.material = value;
  state.materialCount++;
  counters.validatedMaterials++;
}

/** 원래 Fetch 인자·옵션·Response를 바꾸지 않고 복제 스트림만 제한해 관측한다. */
async function instrument(page, account) {
  const state = {
    page,
    account,
    access: null,
    detail: null,
    notice: null,
    material: null,
    materialCount: 0,
    requests: [],
    receipts: [],
    observations: 0,
  };
  const observations = new Map();
  let sequence = 0;
  page.setDefaultTimeout(12000);
  page.setDefaultNavigationTimeout(20000);
  page.on("pageerror", () => {
    counters.runtimeErrors++;
  });
  page.on("response", (response) => {
    if (
      new URL(response.url()).pathname === BASE + "/heartbeat" &&
      response.status() !== 204
    )
      failures.push("heartbeat-response-contract");
  });
  page.on("request", (request) => {
    let check = "origin";
    try {
      const url = new URL(request.url());
      if (url.protocol === "about:") return;
      assert.equal(url.origin, fixture.origin);
      if (!url.pathname.startsWith("/api/")) return;
      const headers = request.headers();
      const path = url.pathname;
      check = "web-header";
      assert.equal(headers["x-sicha-player-web"], "1");
      assert.equal(url.search, "");
      const method = request.method();
      if (path.startsWith(AUTH + "/")) {
        assert.equal(method, "POST");
        assert.match(headers["x-sicha-player-web-epoch"], UUID4);
        assert.equal(headers.authorization, undefined);
        // 자동 Origin은 Puppeteer의 초기 헤더가 아니라 CDP extraInfo에서 검사한다.
        check = "cookie-body";
        const value = JSON.parse(request.postData() ?? "null");
        if (path === AUTH + "/login/local") {
          counters.login++;
          assert.deepEqual(value, {
            email: state.account.email,
            password: fixture.password,
          });
        } else if (path === AUTH + "/logout") {
          counters.logout++;
          assert.deepEqual(Object.keys(value), ["requestKey"]);
          assert.match(value.requestKey, UUID4);
        } else {
          assert.equal(path, AUTH + "/refresh");
          assert.deepEqual(value, {});
        }
        return;
      }
      assert.match(headers.authorization ?? "", /^Bearer [A-Za-z0-9_-]{43}$/);
      if (state.access !== null)
        assert.equal(headers.authorization, "Bearer " + state.access);
      const reads = [
        "/api/member/auth/me",
        "/api/playtests/identity",
        "/api/playtests/invitations",
        BASE,
        BASE + "/policy-notice",
        BASE + "/materials",
      ];
      const mutations = [
        BASE + "/accept",
        BASE + "/ready",
        BASE + "/start",
        BASE + "/heartbeat",
        BASE + "/hints/1/open",
        BASE + "/hints/2/open",
        BASE + "/hints/3/open",
      ];
      if (
        !(
          (method === "GET" && reads.includes(path)) ||
          (method === "POST" && mutations.includes(path))
        )
      ) {
        counters.excludedCalls++;
        throw new Error("Excluded Flutter route");
      }
      assert.equal(headers.cookie, undefined);
      if (method === "POST") {
        const body = request.postData();
        assert.ok(Buffer.byteLength(body ?? "", "utf8") <= 16 * 1024);
        const value = JSON.parse(body);
        state.requests.push({ url: request.url(), path, body, value });
        if (path === BASE + "/accept") counters.accept++;
        if (path === BASE + "/ready") counters.ready++;
        if (path === BASE + "/start") counters.start++;
        if (path === BASE + "/heartbeat") {
          counters.heartbeat++;
          assert.deepEqual(value, {});
        }
        if (path.includes("/hints/")) counters.hint++;
      }
    } catch {
      failures.push("flutter-request-" + check);
    }
    signals.emit("change");
  });
  await page.exposeFunction("__investigationBegin", (path, options) => {
    const id = ++sequence;
    let finish;
    const completion = new Promise((resolve) => {
      finish = resolve;
    });
    track(completion);
    const timer = setTimeout(() => {
      failures.push("clone-observation-deadline");
      observations.delete(id);
      finish();
    }, 12000);
    observations.set(id, {
      finish,
      timer,
      owner: { access: state.access, roleCode: state.detail?.self?.roleCode },
    });
    if (path === BASE + "/materials") counters.materials++;
    try {
      assert.equal(options.mode, "same-origin");
      assert.equal(options.redirect, "error");
      assert.equal(options.cache, "no-store");
      assert.equal(
        options.credentials,
        path.startsWith(AUTH + "/") ? "same-origin" : "omit",
      );
    } catch {
      failures.push("native-fetch-options");
    }
    return id;
  });
  await page.exposeFunction("__investigationClone", (id, observation) => {
    const entry = observations.get(id);
    if (!entry) {
      failures.push("clone-observation-unmatched");
      return;
    }
    return track(
      (async () => {
        assert.equal(observation.ok, true);
        assert.ok(Buffer.byteLength(observation.text, "utf8") <= 1024 * 1024);
        assert.equal(observation.cacheControl, "no-store");
        const value = JSON.parse(observation.text);
        assert.ok(value && typeof value === "object" && !Array.isArray(value));
        const path = observation.path;
        if (path === AUTH + "/login/local" || path === AUTH + "/refresh") {
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
          state.access = value.accessToken;
        } else if (path === "/api/playtests/identity") {
          assert.equal(value.memberKey, state.account.memberKey);
        } else if (path === "/api/playtests/invitations") {
          if (phase === "logout-account-change-purge") {
            // 실제 목록 계약은 WAITING만 반환하며 이미 RUNNING인 초대는 없다.
            assert.equal(value.items.length, 0);
          } else {
            assert.equal(value.items.length, 1);
            assert.equal(value.items[0].testKey, fixture.testKey);
          }
        } else if (path === BASE) {
          assert.equal(value.testKey, fixture.testKey);
          assert.equal(typeof value.self.accepted, "boolean");
          assert.deepEqual(Object.keys(value.partner).sort(), [
            "accepted",
            "online",
            "ready",
          ]);
          state.detail = value;
        } else if (path === BASE + "/policy-notice") {
          assert.equal(value.testKey, fixture.testKey);
          assert.equal(typeof value.revision, "string");
          state.notice = value;
        } else if (path === BASE + "/materials") {
          await validateMaterials(state, value, entry.owner);
        } else if (
          [
            BASE + "/accept",
            BASE + "/ready",
            BASE + "/start",
            BASE + "/hints/1/open",
          ].includes(path)
        ) {
          assert.deepEqual(
            Object.keys(value).sort(),
            [
              "action",
              "changed",
              "replayed",
              "original",
              "current",
              "requestId",
            ].sort(),
          );
          assert.equal(typeof value.changed, "boolean");
          assert.equal(typeof value.replayed, "boolean");
          assert.match(value.requestId, UUID4);
          const action = path.endsWith("/accept")
            ? "INVITATION_ACCEPT"
            : path.endsWith("/ready")
              ? "TEST_READY"
              : path.endsWith("/start")
                ? "TEST_START"
                : "HINT_OPEN";
          assert.equal(value.action, action);
          assert.equal(value.current.testKey, fixture.testKey);
          assert.equal(value.original.testKey, fixture.testKey);
          if (path !== BASE + "/accept") {
            assert.equal(value.changed, true);
            assert.equal(value.replayed, false);
          }
          state.receipts.push({ path, value });
        }
        state.observations++;
        signals.emit("change");
      })().finally(() => {
        clearTimeout(entry.timer);
        observations.delete(id);
        entry.finish();
      }),
      observation.path.startsWith(AUTH + "/")
        ? "cookie"
        : observation.path === "/api/playtests/identity"
          ? "identity"
          : observation.path === "/api/playtests/invitations"
            ? "list"
            : observation.path === BASE
              ? "state"
              : observation.path.endsWith("/policy-notice")
                ? "notice"
                : observation.path.endsWith("/materials")
                  ? "materials"
                  : "receipt",
    );
  });
  await page.evaluateOnNewDocument(() => {
    const nativeFetch = window.fetch;
    window.fetch = async function (...args) {
      const response = await Reflect.apply(nativeFetch, this, args);
      const url = new URL(response.url);
      if (
        url.origin === location.origin &&
        url.pathname.startsWith("/api/") &&
        response.status === 200
      ) {
        const options = args[1];
        const id = await window.__investigationBegin(url.pathname, {
          mode: options?.mode,
          redirect: options?.redirect,
          cache: options?.cache,
          credentials: options?.credentials,
        });
        let clone;
        try {
          clone = response.clone();
        } catch {
          await window.__investigationClone(id, { ok: false });
          return response;
        }
        // 본래 응답은 그대로 반환하고 관측 복제본만 별도 제한 스트림으로 읽는다.
        const observation = (async () => {
          const reader = clone.body.getReader();
          const parts = [];
          let length = 0;
          try {
            for (;;) {
              const { done, value } = await reader.read();
              if (done) break;
              length += value.byteLength;
              if (length > 1024 * 1024) {
                void reader.cancel().catch(() => {});
                throw new Error("Clone bound");
              }
              parts.push(value);
            }
            const bytes = new Uint8Array(length);
            let offset = 0;
            for (const part of parts) {
              bytes.set(part, offset);
              offset += part.length;
            }
            await window.__investigationClone(id, {
              ok: true,
              path: url.pathname,
              cacheControl: response.headers.get("cache-control"),
              text: new TextDecoder("utf-8", { fatal: true }).decode(bytes),
            });
          } finally {
            reader.releaseLock();
          }
        })().catch(() =>
          window.__investigationClone(id, { ok: false }).catch(() => {}),
        );
        // 발급 관측은 다음 /me 전에 완료하고 자료 복제 관측은 비동기로 유지한다.
        if (url.pathname.startsWith("/api/member/browser-auth/"))
          await observation;
      }
      return response;
    };
  });
  const client = await page.createCDPSession();
  await client.send("Network.enable", {
    maxTotalBufferSize: 65536,
    maxResourceBufferSize: 16384,
    maxPostDataSize: 0,
  });
  const routes = new Map();
  const extras = new Map();
  const inspect = (id) => {
    const path = routes.get(id);
    const headers = extras.get(id);
    if (!path?.startsWith("/api/") || !headers) return;
    const normalized = Object.fromEntries(
      Object.entries(headers).map(([key, value]) => [key.toLowerCase(), value]),
    );
    if (normalized["sec-fetch-site"] !== "same-origin")
      failures.push("actual-fetch-site");
    if (normalized["x-sicha-player-web"] !== "1")
      failures.push("actual-web-header");
    if (path.startsWith(AUTH + "/") && normalized.origin !== fixture.origin)
      failures.push("actual-cookie-origin");
    if (!path.startsWith(AUTH + "/") && normalized.cookie !== undefined)
      failures.push("actual-bearer-cookie");
    routes.delete(id);
    extras.delete(id);
  };
  client.on("Network.requestWillBeSent", ({ requestId, request }) => {
    routes.set(requestId, new URL(request.url).pathname);
    inspect(requestId);
  });
  client.on("Network.requestWillBeSentExtraInfo", ({ requestId, headers }) => {
    extras.set(requestId, headers);
    inspect(requestId);
  });
  state.client = client;
  return state;
}

/** 조작은 foreground의 실제 Flutter semantics에만 전달한다. */
async function semantics(page) {
  await page.bringToFront();
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
  await reveal(page, `aria/${label}[role="button"]`);
  await page.locator(`aria/${label}[role="button"]`).click();
}

/** 가상화된 화면은 실제 휠 입력으로 찾아야 하며 보이지 않는 요소를 만들어 내지 않는다. */
async function reveal(page, selector) {
  if (await page.$(selector)) return;
  const viewport = page.viewport();
  await page.mouse.move(viewport.width / 2, viewport.height / 2);
  for (const direction of [1, -1]) {
    for (let step = 0; step < 12; step++) {
      await page.mouse.wheel({ deltaY: direction * 350 });
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      if (await page.$(selector)) return;
    }
  }
  throw new Error("Visible semantic control unavailable");
}

/** 실제 CDP 입력과 네이티브 선택만 사용하며 DOM·Dart 값을 대입하지 않는다. */
async function input(page, label, value) {
  await page.bringToFront();
  // SDK의 multiline native 편집기는 textarea이며 단일행과 AX 노출 방식이 다르다.
  const selector =
    label === "개인 조사 기록 (임시 메모)"
      ? 'textarea[aria-label*="개인 조사 기록"]'
      : `aria/${label}[role="textbox"]`;
  await reveal(page, selector);
  if (label === "개인 조사 기록 (임시 메모)") {
    // DOM focus만으로 이전 semantic 편집기를 쓰지 않고 실제 TextField tap을 먼저 수행한다.
    await page.locator(selector).click();
  }
  const field = await page.waitForSelector(selector);
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
    )
      throw new Error("Semantic input binding unavailable");
    element.select();
  });
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
  assert.equal(counters.runtimeErrors, 0);
}

/** 응답 대기는 action과 같은 Promise.all에 즉시 등록하여 미처리 rejection을 방지한다. */
async function actionResponse(page, path, action, expected = 200) {
  const [response] = await Promise.all([
    page.waitForResponse((value) => new URL(value.url()).pathname === path),
    action(),
  ]);
  assert.equal(response.status(), expected);
}

async function login(state, account = state.account, destination = "account") {
  state.account = account;
  await route(state.page, "login");
  await input(state.page, "이메일", account.email);
  await input(state.page, "비밀번호", fixture.password);
  await actionResponse(state.page, AUTH + "/login/local", () =>
    button(state.page, "로그인"),
  );
  await route(state.page, destination);
  await signalState(() => state.access !== null);
}

async function invitation(state) {
  await button(state.page, "초대로 이동");
  await route(state.page, "invitations");
  await button(state.page, "초대 확인");
  await route(state.page, "invitations/" + fixture.testKey);
  await signalState(() => state.notice !== null);
}

/** 고지를 실제 화면에서 읽고 checkbox를 직접 선택한 뒤에만 최초 동의를 보낸다. */
async function agree(state) {
  await state.page.bringToFront();
  await state.page.waitForFunction(
    (body) => document.body.textContent.includes(body),
    {},
    state.notice.body,
  );
  const checkbox = await state.page.waitForSelector(
    'aria/위 고지를 읽고 기능 테스트 참여에 동의합니다.[role="checkbox"]',
  );
  assert.equal(
    await checkbox.evaluate((element) => element.getAttribute("aria-checked")),
    "false",
  );
  await checkbox.click();
  await state.page.waitForFunction(
    (element) => element.getAttribute("aria-checked") === "true",
    {},
    checkbox,
  );
}

async function investigation(state) {
  await button(state.page, "조사 화면으로");
  await route(state.page, "investigation/" + fixture.testKey);
  await state.page.waitForSelector('aria/준비하기[role="button"]');
}

async function materials(state) {
  const before = state.materialCount;
  const hidden = await state.page.evaluate(() => document.hidden);
  await state.page.bringToFront();
  await signalState(
    () => state.material !== null && (!hidden || state.materialCount > before),
  );
  // 복귀 때 기록 편집기로 스크롤될 수 있으므로 실제 휠로 역할 자료를 다시 읽는다.
  const viewport = state.page.viewport();
  await state.page.mouse.move(viewport.width / 2, viewport.height / 2);
  await state.page.mouse.wheel({ deltaY: -3000 });
  await state.page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  await state.page.waitForFunction(
    (role, clues) => {
      // ListTile 병합 semantics는 실제 접근성 이름을 aria-label에 제공한다.
      const visibleText =
        document.body.textContent +
        "\n" +
        Array.from(document.querySelectorAll("[aria-label]"))
          .map((element) => element.getAttribute("aria-label"))
          .join("\n");
      return (
        visibleText.includes(role.name) &&
        clues.every(
          (clue) =>
            visibleText.includes(clue.title) &&
            (clue.body === null || visibleText.includes(clue.body)),
        )
      );
    },
    { polling: "mutation" },
    state.material.role,
    state.material.clues,
  );
}

/** 저장소는 실제 epoch 표식만 허용하고 개인 원문·메모·자격은 허용하지 않는다. */
async function storage(state, notes) {
  const stored = await state.page.evaluate(() => ({
    local: Object.fromEntries(Object.entries(localStorage)),
    session: Object.fromEntries(Object.entries(sessionStorage)),
    cookie: document.cookie,
  }));
  assert.deepEqual(Object.keys(stored.local), [MARKER]);
  assert.deepEqual(stored.session, {});
  const marker = JSON.parse(stored.local[MARKER]);
  assert.equal(marker.format, 1);
  assert.match(marker.epoch, UUID4);
  const serialized = JSON.stringify(stored);
  for (const secret of [state.access, ...notes])
    assert.equal(serialized.includes(secret), false);
  for (const clue of state.material.clues)
    if (clue.body) assert.equal(serialized.includes(clue.body), false);
}

async function logout(state) {
  await signalState(() => pending.size === 0);
  await actionResponse(state.page, AUTH + "/logout", () =>
    button(state.page, "로그아웃"),
  );
  await route(state.page, "login");
}

try {
  const launchOptions = {
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    acceptInsecureCerts: true,
    protocolTimeout: 15000,
    args: ["--no-sandbox"],
  };
  browser = await puppeteer.launch(launchOptions);
  memberBrowsers.push(browser);
  const states = viewStates;
  for (const account of fixture.accounts) {
    // 두 회원의 실제 별도 기기를 모델링하며 incognito 창 간 focus 경합을 공유하지 않는다.
    const memberBrowser =
      states.length === 0 ? browser : await puppeteer.launch(launchOptions);
    if (memberBrowser !== browser) memberBrowsers.push(memberBrowser);
    const context = await memberBrowser.createBrowserContext();
    await context.setCookie({
      name: "player_test_nonsecret",
      value: "probe",
      domain: "localhost",
      path: "/",
      secure: true,
      sameSite: "Strict",
    });
    const page = await context.newPage();
    const state = await instrument(page, account);
    state.context = context;
    await page.goto(fixture.origin + "/player/", { waitUntil: "networkidle0" });
    await semantics(page);
    await login(state);
    states.push(state);
  }
  const [a, b] = states;
  phase = "explicit-consent-unknown-response";
  await invitation(a);
  await agree(a);
  const originalNotice = structuredClone(a.notice);
  await a.client.send("Fetch.enable", {
    patterns: [
      {
        urlPattern: fixture.origin + BASE + "/accept",
        requestStage: "Response",
      },
    ],
  });
  const dropped = new Promise((resolve, reject) => {
    a.client.once("Fetch.requestPaused", (event) => {
      void (async () => {
        assert.equal(event.responseStatusCode, 200);
        await a.client.send("Fetch.failRequest", {
          requestId: event.requestId,
          errorReason: "Failed",
        });
        await a.client.send("Fetch.disable");
        counters.droppedAccept++;
        resolve();
      })().catch(() => reject(new Error("Accept response drop failed")));
    });
  });
  await Promise.all([dropped, button(a.page, "동의하고 초대 수락")]);
  await a.page.waitForSelector(
    'aria/원본 동의 요청 다시 보내기[role="button"]',
  );
  assert.equal(counters.accept, 1);
  const original = a.requests.find(
    (request) => request.path === BASE + "/accept",
  );
  assert.deepEqual(original.value, {
    expectedRev: originalNotice.revision,
    inviteGen: originalNotice.generation,
    blindDeclared: false,
    policyCode: originalNotice.policyCode,
    noticeHash: originalNotice.noticeHash,
    requestKey: original.value.requestKey,
  });
  assert.match(original.value.requestKey, UUID4);
  await actionResponse(a.page, BASE, () => button(a.page, "서버 상태 확인"));
  await signalState(
    () => a.detail?.self.accepted === true && a.detail.rev === "1",
  );
  assert.equal(counters.accept, 1);
  assert.equal(await a.page.$('aria/조사 화면으로[role="button"]'), null);
  await a.page.waitForSelector(
    'aria/원본 동의 요청 다시 보내기[role="button"]',
  );
  await a.page.waitForFunction(
    (notice) =>
      document.body.textContent.includes(notice.body) &&
      document.body.textContent.includes(notice.contact) &&
      document.body.textContent.includes(
        "개인정보 처리 고지 " + notice.version,
      ),
    {},
    originalNotice,
  );

  await invitation(b);
  await agree(b);
  await actionResponse(b.page, BASE + "/accept", () =>
    button(b.page, "동의하고 초대 수락"),
  );
  await b.page.waitForSelector('aria/조사 화면으로[role="button"]');
  await signalState(() =>
    b.receipts.some((receipt) => receipt.path === BASE + "/accept"),
  );
  const secondConsent = b.receipts.find(
    (receipt) => receipt.path === BASE + "/accept",
  ).value;
  assert.equal(secondConsent.changed, true);
  assert.equal(secondConsent.replayed, false);
  assert.equal(secondConsent.current.rev, "2");
  await a.page.bringToFront();
  await actionResponse(a.page, BASE + "/accept", () =>
    button(a.page, "원본 동의 요청 다시 보내기"),
  );
  await a.page.waitForSelector('aria/조사 화면으로[role="button"]');
  const accepts = a.requests.filter(
    (request) => request.path === BASE + "/accept",
  );
  assert.equal(accepts.length, 2);
  assert.equal(accepts[1].url, original.url);
  assert.equal(accepts[1].body, original.body);
  await signalState(() =>
    a.receipts.some((receipt) => receipt.path === BASE + "/accept"),
  );
  const replay = a.receipts.find(
    (receipt) => receipt.path === BASE + "/accept",
  ).value;
  assert.equal(replay.action, "INVITATION_ACCEPT");
  assert.equal(replay.replayed, true);
  assert.equal(replay.changed, false);
  assert.equal(replay.original.rev, "1");
  assert.equal(replay.current.rev, "2");
  assert.equal(counters.accept, 3);
  counters.originalReplay = 1;

  phase = "paired-readiness-start";
  await investigation(a);
  await actionResponse(a.page, BASE + "/ready", () =>
    button(a.page, "준비하기"),
  );
  await a.page.waitForSelector('aria/준비 해제[role="button"]');
  await investigation(b);
  await actionResponse(b.page, BASE + "/ready", () =>
    button(b.page, "준비하기"),
  );
  await b.page.waitForSelector('aria/조사 시작[role="button"]');
  assert.ok(a.requests.some((request) => request.path === BASE + "/heartbeat"));
  assert.ok(b.requests.some((request) => request.path === BASE + "/heartbeat"));
  await actionResponse(b.page, BASE + "/start", () =>
    button(b.page, "조사 시작"),
  );
  await materials(b);
  await a.page.bringToFront();
  await materials(a);
  assert.notEqual(a.material.role.code, b.material.role.code);
  assert.notDeepEqual(
    a.material.clues.map((clue) => clue.code),
    b.material.clues.map((clue) => clue.code),
  );

  phase = "private-notes-visibility-recovery";
  const noteA = "합성 메모 A " + randomUUID();
  const noteB = "합성 메모 B " + randomUUID();
  await input(a.page, "개인 조사 기록 (임시 메모)", noteA);
  const retiredEditor = await a.page.waitForSelector(
    'textarea[aria-label*="개인 조사 기록"]',
  );
  const originalRole = a.material.role.code;
  const materialBefore = a.materialCount;
  // 별도 incognito 창은 둘 다 visible일 수 있으므로 같은 창의 실제 다른 탭으로 전환한다.
  const cover = await a.context.newPage();
  await cover.goto("about:blank");
  await cover.bringToFront();
  await a.page.waitForFunction(
    (note) =>
      document.hidden &&
      !Array.from(document.querySelectorAll("input,textarea")).some((element) =>
        element.value.includes(note),
      ),
    { polling: 100 },
    noteA,
  );
  assert.equal(
    await retiredEditor.evaluate(
      (element) => element.value === "" && element.defaultValue === "",
    ),
    true,
  );
  await retiredEditor.dispose();
  counters.backgroundHidden = 1;
  await materials(b);
  await input(b.page, "개인 조사 기록 (임시 메모)", noteB);
  await a.page.bringToFront();
  await cover.close();
  await signalState(() => a.materialCount > materialBefore);
  await materials(a);
  assert.equal(a.material.role.code, originalRole);
  await reveal(a.page, 'textarea[aria-label*="개인 조사 기록"]');
  await a.page.locator('textarea[aria-label*="개인 조사 기록"]').click();
  await a.page.waitForFunction(
    (note) =>
      Array.from(
        document.querySelectorAll('textarea[aria-label*="개인 조사 기록"]'),
      ).some(
        (element) =>
          element.value === note &&
          !element.readOnly &&
          /^sicha-private-note-[0-9a-f-]{36}$/.test(
            element
              .closest("[flt-semantics-identifier]")
              ?.getAttribute("flt-semantics-identifier") ?? "",
          ),
      ),
    {},
    noteA,
  );
  await input(a.page, "개인 조사 기록 (임시 메모)", noteA + " 추가 입력");
  counters.sameRoleRecovered = 1;
  await storage(a, [noteA, noteB]);
  await storage(b, [noteA, noteB]);

  phase = "allowed-hint-isolated-scope";
  await actionResponse(a.page, BASE + "/hints/1/open", () =>
    button(a.page, "1단계 힌트 열기"),
  );
  await signalState(() =>
    a.material?.openedHints.some((hint) => hint.level === 1),
  );
  await reveal(a.page, "text/1단계 힌트:");
  await a.page.waitForFunction(
    (body) =>
      (
        document.body.textContent +
        Array.from(document.querySelectorAll("[aria-label]"), (element) =>
          element.getAttribute("aria-label"),
        ).join("\n")
      ).includes("1단계 힌트: " + body),
    {},
    a.material.openedHints.find((hint) => hint.level === 1).body,
  );
  await materials(b);
  assert.equal(b.material.openedHints.length, 0);

  phase = "separate-https-negative-boundaries";
  const headers = bearerHeaders(a);
  for (const [path, method, additions, expected] of [
    [BASE + "/report", "GET", {}, 403],
    [BASE + "/result", "GET", {}, 403],
    [BASE + "/forfeit", "POST", {}, 403],
    [BASE + "/feedback", "POST", {}, 403],
    [BASE + "/report", "PATCH", {}, 403],
    [BASE + "/report/proposals", "POST", {}, 403],
    [BASE + "/hints/4/open", "POST", {}, 403],
    [BASE + "/materials", "POST", {}, 403],
    ["/api/playtests/" + fixture.testKey.toUpperCase(), "GET", {}, 403],
    [BASE + "/materials?size=20", "GET", {}, 400],
    ["/api/playtests/invitations?size=101", "GET", {}, 400],
    ["/api/playtests/invitations?size=0", "GET", {}, 400],
    ["/api/playtests/invitations?size=20&size=20", "GET", {}, 400],
    ["/api/playtests/invitations?cursor=%31", "GET", {}, 400],
    ["/api/playtests/invitations?cursor=9223372036854775808", "GET", {}, 400],
    [BASE, "GET", { Cookie: "nonsecret=probe" }, 403],
    [BASE, "GET", { Origin: "https://foreign.example.invalid" }, 403],
  ]) {
    const result = await probe(
      path,
      method,
      { ...headers, ...additions },
      method === "GET" ? undefined : "{}",
    );
    assert.equal(result.status, expected);
    assert.deepEqual(Object.keys(result.value).sort(), [
      "code",
      "message",
      "requestId",
    ]);
    assert.match(result.value.requestId, UUID4);
  }

  for (const query of [
    "size=1",
    "size=100",
    "cursor=1&size=100",
    "size=20&cursor=9223372036854775807",
  ]) {
    const result = await probe(
      "/api/playtests/invitations?" + query,
      "GET",
      headers,
    );
    assert.equal(result.status, 200);
    assert.deepEqual(result.value.items, []);
  }

  phase = "logout-account-change-purge";
  const oldClueTitles = a.material.clues.map((clue) => clue.title);
  await logout(a);
  await a.page.waitForFunction(
    (titles) =>
      titles.every((title) => !document.body.textContent.includes(title)),
    {},
    oldClueTitles,
  );
  await a.page.waitForFunction(
    (notes) =>
      !Array.from(document.querySelectorAll("input,textarea")).some((element) =>
        notes.some((note) => element.value.includes(note)),
      ),
    {},
    [noteA, noteB],
  );
  // 다른 실제 계정으로 같은 페이지를 재인증한 뒤 기존 역할 메모가 되살아나지 않는지 확인한다.
  a.access = null;
  a.detail = null;
  a.notice = null;
  a.material = null;
  // 조사 화면 로그아웃은 초대 목록으로 복귀하며 이미 동의한 회원의 실제 이동만 수행한다.
  await login(a, fixture.accounts[1], "invitations");
  // RUNNING 목록 제외를 우회해 동의를 만들지 않는다. 실제 내부 deep-link를 같은 문서에서 연다.
  const documentOrigin = await a.page.evaluate(() => performance.timeOrigin);
  await a.page.goto(
    origin.origin + "/player/#/investigation/" + fixture.testKey,
  );
  assert.equal(
    await a.page.evaluate(() => performance.timeOrigin),
    documentOrigin,
  );
  await route(a.page, "investigation/" + fixture.testKey);
  await materials(a);
  assert.equal(a.material.role.code, b.material.role.code);
  await a.page.waitForFunction(
    (titles) =>
      titles.every((title) => !document.body.textContent.includes(title)),
    {},
    oldClueTitles,
  );
  await reveal(a.page, 'textarea[aria-label*="개인 조사 기록"]');
  const freshNotes = await a.page.waitForSelector(
    'textarea[aria-label*="개인 조사 기록"]',
  );
  await a.page.waitForFunction(
    (element) => element.value === "",
    {},
    freshNotes,
  );
  counters.accountPurged = 1;
  await logout(a);
  await logout(b);
  for (const state of states) {
    const cookies = await state.context.cookies();
    assert.equal(
      cookies.some((cookie) => cookie.name === "__Secure-sicha_player_refresh"),
      false,
    );
  }
  await signalState(() => pending.size === 0);
  assert.equal(failures.length, 0);
  assert.equal(counters.runtimeErrors, 0);
  assert.equal(counters.excludedCalls, 0);
  assert.equal(counters.materials, counters.validatedMaterials);
  assert.ok(counters.materials >= 3);
  assert.equal(counters.accept, 3);
  assert.equal(counters.ready, 2);
  assert.equal(counters.start, 1);
  assert.equal(counters.hint, 1);
  for (const value of Object.values(counters))
    assert.ok(Number.isSafeInteger(value) && value >= 0);
  phase = "complete";
  process.stdout.write(JSON.stringify({ ok: true, counters }));
} catch (error) {
  // assert 비교값·본문·쿠키·토큰·역할 원문은 진단에 반사하지 않는다.
  const views = await Promise.all(
    viewStates.map(async (state) => {
      if (!state.material)
        return {
          materialObserved: false,
          routeFlags: await state.page.evaluate(() => ({
            login: location.hash === "#/login",
            account: location.hash === "#/account",
            invitations: location.hash === "#/invitations",
            investigation: location.hash.startsWith("#/investigation/"),
            session: location.hash === "#/session",
          })),
        };
      return state.page
        .evaluate(
          ({ role, clues }) => {
            const text = document.body.textContent;
            const names = Array.from(document.querySelectorAll("[aria-label]"))
              .map((element) => element.getAttribute("aria-label"))
              .join("\n");
            const normalize = (value) => value.replace(/\s+/g, " ").trim();
            return {
              materialObserved: true,
              roleInText: text.includes(role.name),
              roleInAccessibleNames: names.includes(role.name),
              clueTitlesInText: clues.map((clue) => text.includes(clue.title)),
              clueTitlesInAccessibleNames: clues.map((clue) =>
                names.includes(clue.title),
              ),
              clueBodiesInText: clues.map(
                (clue) => clue.body === null || text.includes(clue.body),
              ),
              clueBodiesInAccessibleNames: clues.map(
                (clue) => clue.body === null || names.includes(clue.body),
              ),
              normalizedClueBodies: clues.map(
                (clue) =>
                  clue.body === null ||
                  normalize(text + names).includes(normalize(clue.body)),
              ),
              runningRendered: text.includes("진행 상태: RUNNING"),
              notesLabelRendered: text.includes("개인 조사 기록 (임시 메모)"),
              privateIdentifierCount: document.querySelectorAll(
                '[flt-semantics-identifier^="sicha-private-note-"]',
              ).length,
              semanticTextareaCount: document.querySelectorAll(
                'textarea[data-semantics-role="text-field"]',
              ).length,
              editableCount: document.querySelectorAll(
                'input,textarea,[role="textbox"]',
              ).length,
              editableStructure: Array.from(
                document.querySelectorAll('input,textarea,[role="textbox"]'),
              ).map((element) => ({
                tag: element.tagName,
                role: element.getAttribute("role"),
                multiline: element.getAttribute("aria-multiline"),
                labelPresent: element.hasAttribute("aria-label"),
                labelIncludesExpected: (
                  element.getAttribute("aria-label") ?? ""
                ).includes("개인 조사 기록"),
                labelledByPresent: element.hasAttribute("aria-labelledby"),
                privateOwnerAncestor:
                  element.closest(
                    '[flt-semantics-identifier^="sicha-private-note-"]',
                  ) !== null,
                visible: element.getBoundingClientRect().height > 0,
              })),
              hidden: document.hidden,
              materialFormatError: text.includes(
                "역할 자료 형식이 올바르지 않습니다.",
              ),
              textLength: text.length,
              accessibleNameCount:
                document.querySelectorAll("[aria-label]").length,
            };
          },
          { role: state.material.role, clues: state.material.clues },
        )
        .catch(() => ({ unavailable: true }));
    }),
  );
  process.stderr.write(
    JSON.stringify({
      ok: false,
      phase,
      failure: "browser-investigation-contract",
      errorClass: [
        "AssertionError",
        "TimeoutError",
        "ProtocolError",
        "TypeError",
      ].includes(error.name)
        ? error.name
        : "Error",
      sourceLines: [
        ...String(error.stack).matchAll(
          /player-web-investigation\.mjs:(\d+):(\d+)/g,
        ),
      ]
        .slice(0, 6)
        .map((match) => [Number(match[1]), Number(match[2])]),
      contractFailures: [...new Set(failures)].slice(0, 12),
      views,
      counters,
    }),
  );
  process.exitCode = 1;
} finally {
  clearTimeout(deadline);
  await Promise.all(
    memberBrowsers.map((memberBrowser) => memberBrowser.close()),
  );
}
