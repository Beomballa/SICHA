import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const assets = new URL("../../main/resources/static/admin/", import.meta.url);
const template = readFileSync(
  new URL(
    "../../main/resources/templates/admin/story-editor.html",
    import.meta.url,
  ),
  "utf8",
);
const sharedStyles = readFileSync(new URL("ui.css", assets), "utf8");
const root = "/admin/api/stories/ST_REVIEW_TEST/versions/1";
const rev = "9007199254740993123";
const nextRev = "9007199254740993124";
const precheckPath = `${root}/review-precheck`;
const checkPath = `${root}/grade-samples/check`;
const hostile = '<img src=x onerror="window.injected=true">';
const requestPath = `${root}/review-requests`;
const returnPath = `${root}/return-to-draft`;
const mePath = "/admin/api/auth/me";

/**
 * Me의 UUID·고정 절대 기한과 갱신 가능한 두 기한을 분리한 합성 HTTP 응답이다.
 * 전역 permissions는 사건 권한이 아니며 실제 인증·고유 세션 증명을 주장하지 않는다.
 * @param {object} overrides 계정·고정 기한 교체 또는 rolling 기한의 합성 변화.
 * @returns {object} AuthModels.Me와 같은 다섯 필드이며 기본 전역 권한은 빈 목록이다.
 */
function identityResponse(overrides = {}) {
  return {
    accountKey: "11111111-1111-4111-8111-111111111111",
    permissions: [],
    absoluteExpiresAt: "2026-10-04T10:00:00Z",
    idleExpiresAt: "2026-10-04T03:00:00Z",
    reauthExpiresAt: "2026-10-04T02:35:00Z",
    ...overrides,
  };
}

/**
 * 원고·수정번호와 권한은 명시적 합성 전송이며 실제 서버 인가·품질 판정 근거가 아니다.
 * @param {object} permissions 기본은 일반 소유자의 edit만 true이며 검수 시나리오는 별도 지정한다.
 */
function source(permissions = { edit: true, review: false, publish: false }) {
  return {
    storyCode: "ST_REVIEW_TEST",
    versionNo: 1,
    editRev: rev,
    status: "DRAFT",
    currentSnapshotId: null,
    permissions,
    storyActiveYn: true,
    activeYn: true,
    ownerAccountKey: "synthetic-owner",
    updatedAt: "2026-10-01T00:00:00Z",
    warnings: [],
    policy: {},
    sections: {
      basic: {
        title: "합성 사건",
        intro: "저장 도입",
        setting: null,
        difficulty: 3,
        limitSec: 2100,
        estMin: 20,
        estMax: 30,
        timelineOrigin: null,
      },
      answer: {
        culpritCode: null,
        methodAnswer: null,
        timeAnswer: null,
        motiveAnswer: null,
      },
      reveal: { revealText: null },
    },
  };
}

/** 실제 PrecheckResult 필드와 전체 수/표시 제한을 사용하는 합성 전송 응답이다. */
function diagnostics(editRev = rev) {
  return {
    editRev,
    eligible: false,
    errors: [
      {
        code: "MISSING_CONTENT",
        resource: "basic",
        itemKey: "",
        field: "intro",
      },
      {
        code: "MISSING_CONTENT",
        resource: "gradeSamples",
        itemKey: "SAMPLE",
        field: "checkedBy",
      },
      ...Array.from({ length: 198 }, () => ({
        code: hostile,
        resource: "unknown",
        itemKey: "9007199254740993999",
        field: "unknown",
      })),
    ],
    warnings: [
      {
        code: "POLICY_TIME_RANGE",
        resource: "basic",
        itemKey: "",
        field: "estMin",
      },
    ],
    errorCount: 247,
    warningCount: 1,
    truncated: true,
    requestId: "synthetic-request",
  };
}

/**
 * 실제 HTML/JS/CSS를 실행하되 모든 HTTP는 명시적인 합성 전송으로 격리한다.
 * @param {function} scenario 페이지·정확한 원문 응답·지연 응답 헬퍼를 받는 검사.
 * @throws {Error} 제품 오류·합성 전송 오류·검사 실패를 전파한다.
 */
async function isolated(scenario, initialDetail = source()) {
  const browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage();
  page.setDefaultTimeout(5000);
  const errors = [];
  const requests = [];
  // 기존 49개 검사의 업무 전송 수를 유지하고 신원 관측은 별도로 빠짐없이 수집한다.
  const identityRequests = [];
  const queued = new Map();
  const state = {
    detail: initialDetail,
    me: identityResponse(),
    meStatus: 200,
    csrfReads: 0,
    externalRequests: [],
  };
  const json = (request, body, status = 200) =>
    request.respond({
      status,
      contentType: "application/json",
      headers: { "Cache-Control": "no-store" },
      body: JSON.stringify(body),
    });
  /**
   * 이미 작성된 합성 JSON 원문을 그대로 전송한다.
   * @param {object} request 보류 중인 합성 HTTP 요청.
   * @param {string} body 파싱·재직렬화하지 않을 JSON 원문.
   * @param {number} status 기본200이며 실패 전송도 허용한다.
   * @throws {Error} 요청이 종료됐거나 전송에 실패하면 전파한다.
   */
  const raw = (request, body, status = 200) =>
    request.respond({
      status,
      contentType: "application/json",
      headers: { "Cache-Control": "no-store" },
      body,
    });
  const queue = (path, method, handler) => {
    const key = `${method} ${path}`;
    queued.set(key, [...(queued.get(key) || []), handler]);
  };
  const hold = (path, method = "POST") => {
    let ready;
    const waiting = new Promise((resolve) => {
      ready = resolve;
    });
    queue(path, method, (request) => {
      ready(request);
    });
    return {
      ready: waiting,
      async respond(body, status = 200) {
        await json(await waiting, body, status);
      },
      /**
       * 보류된 합성 응답에 원문을 보내며 파싱 오류는 제품에 맡긴다.
       * @param {string} body 정확한 JSON 문자열.
       * @param {number} status 기본200인 합성 HTTP 상태.
       */
      async respondRaw(body, status = 200) {
        await raw(await waiting, body, status);
      },
      async lose() {
        await (await waiting).abort("connectionclosed");
      },
    };
  };
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("dialog", async (dialog) => {
    if (dialog.type() !== "beforeunload")
      errors.push(`네이티브 대화상자 ${dialog.type()}`);
    await dialog.dismiss();
  });
  await page.setRequestInterception(true);
  page.on("request", (request) => {
    void (async () => {
      const url = new URL(request.url());
      // 공통 CSS에 등록된 data 이미지에는 외부 네트워크 전송이 없다. 다른 출처는 계속 차단한다.
      if (url.protocol === "data:" && sharedStyles.includes(request.url()))
        return request.continue();
      if (url.origin !== "https://review.test") {
        state.externalRequests.push(request.url());
        return request.abort();
      }
      if (url.pathname === "/admin/stories/ST_REVIEW_TEST/versions/1")
        return request.respond({
          contentType: "text/html; charset=utf-8",
          body: template,
        });
      if (/^\/admin\/(stories|ui)\.(js|css)$/.test(url.pathname))
        return request.respond({
          contentType: url.pathname.endsWith("js")
            ? "text/javascript"
            : "text/css",
          body: readFileSync(
            new URL(url.pathname.split("/").pop(), assets),
            "utf8",
          ),
        });
      (url.pathname === mePath ? identityRequests : requests).push({
        path: url.pathname,
        query: url.search,
        method: request.method(),
        body: request.postData(),
        headers: request.headers(),
      });
      const handler = queued
        .get(`${request.method()} ${url.pathname}`)
        ?.shift();
      if (handler) return handler(request);
      if (url.pathname === mePath && request.method() === "GET")
        return json(request, state.me, state.meStatus);
      if (url.pathname === "/admin/api/auth/csrf") {
        state.csrfReads++;
        return json(request, {
          headerName: "X-CSRF-TOKEN",
          token: "synthetic-csrf",
        });
      }
      if (url.pathname === "/admin/api/history/navigation")
        return json(request, {});
      if (url.pathname === root) return json(request, state.detail);
      if (url.pathname === precheckPath)
        return json(request, diagnostics(state.detail.editRev));
      if (url.pathname === checkPath) {
        state.detail.editRev = nextRev;
        return json(request, {
          editRev: nextRev,
          checkedCount: 5,
          changed: true,
          requestId: "synthetic-receipt",
        });
      }
      if (/\/(persons|grade-samples)$/.test(url.pathname))
        return json(request, {
          editRev: state.detail.editRev,
          items: [],
          hasNext: false,
          nextAfterKey: null,
        });
      return json(request, { code: "NOT_FOUND" }, 404);
    })().catch((error) => errors.push(error.message));
  });
  try {
    const navigation = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/admin/api/history/navigation" &&
        response.request().method() === "POST",
    );
    await page.goto(
      "https://review.test/admin/stories/ST_REVIEW_TEST/versions/1",
    );
    await navigation;
    await page.waitForFunction(() => !document.getElementById("editor").hidden);
    await scenario({
      page,
      state,
      requests,
      identityRequests,
      queue,
      json,
      raw,
      hold,
    });
    assert.deepEqual(errors, [], "제품 런타임 및 합성 전송 오류");
  } finally {
    await browser.close();
  }
}

/** 숨은 장도 기존 목차를 통해 열고 실제 DOM 입력 이벤트로 변경한다. */
async function edit(page, section, key, value) {
  await page.click(`#editor-nav a[href="#${section}-heading"]`);
  await page.select(`#${section}-${key}-mode`, "value");
  await page.$eval(
    `#${section}-${key}`,
    (node, text) => {
      node.value = text;
      node.dispatchEvent(new Event("input", { bubbles: true }));
    },
    value,
  );
}

/** 확인은 공통 창의 실제 버튼으로만 수행한다. */
async function confirm(page, selector = "#review-samples-check") {
  await page.click(selector);
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await page.click("#ui-confirm-accept");
  await page.waitForFunction(
    () => !document.getElementById("ui-confirm-dialog"),
  );
}

/** 관찰 가능한 패널 문구가 비동기 완료를 알릴 때까지 기다린다. */
async function message(page, fragment) {
  await page.waitForFunction(
    (text) =>
      document
        .getElementById("review-check-status")
        ?.textContent.includes(text),
    {},
    fragment,
  );
}

/** 비활성 버튼에 프로그램 이벤트를 보내도 제품의 요청 경계가 차단하는지 검사한다. */
async function forceActions(page) {
  await page.evaluate(() => {
    for (const id of ["review-precheck", "review-samples-check"])
      document
        .getElementById(id)
        .dispatchEvent(new Event("click", { bubbles: true }));
  });
}

const lanePosts = (requests) =>
  requests.filter(
    (request) =>
      request.method === "POST" &&
      [precheckPath, checkPath].includes(request.path),
  );

test("all four sections, clear intents and hidden keep buffers block both POSTs", async () => {
  await isolated(async ({ page, requests }) => {
    for (const [section, key] of [
      ["basic", "intro"],
      ["answer", "methodAnswer"],
      ["reveal", "revealText"],
    ]) {
      const original = await page.$eval(
        `#${section}-${key}`,
        (node) => node.value,
      );
      await edit(page, section, key, "미저장 합성 입력");
      await forceActions(page);
      await page.select(`#${section}-${key}-mode`, "clear");
      await forceActions(page);
      await page.select(`#${section}-${key}-mode`, "keep");
      await forceActions(page);
      assert.equal(
        await page.$eval("#review-precheck", (node) => node.disabled),
        true,
        "유지 뒤 수정 버퍼도 미저장",
      );
      await page.$eval(
        `#${section}-${key}`,
        (node, text) => {
          node.value = text;
          node.dispatchEvent(new Event("input", { bubbles: true }));
        },
        original,
      );
    }
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.click("#child-new");
    await edit(page, "child", "name", "새 자료 입력");
    await forceActions(page);
    assert.equal(lanePosts(requests).length, 0);
    assert.equal(await page.$("#ui-confirm-dialog"), null);
    assert.equal(
      await page.$$eval("form[data-section]", (nodes) => nodes.length),
      4,
    );
  });
});

test("large revisions, safe full-count diagnostics, repair focus and narrow invalidation", async () => {
  await isolated(async ({ page, requests }) => {
    await page.click("#review-precheck");
    await message(page, "구조 진단입니다");
    const request = lanePosts(requests)[0];
    assert.deepEqual(JSON.parse(request.body), { expectedRev: rev });
    assert.equal(request.headers["x-csrf-token"], "synthetic-csrf");
    assert.equal(request.headers["content-type"], "application/json");
    const text = await page.$eval(
      "#review-check-diagnostics",
      (node) => node.textContent,
    );
    assert.match(text, /247건.*경고 1건/);
    assert.ok(text.includes(hostile) && text.includes("9007199254740993999"));
    assert.match(text, /진단 잘림/);
    assert.match(text, /품질 통과 아님/);
    assert.equal(
      await page
        .$$("#review-check-diagnostics img")
        .then((nodes) => nodes.length),
      0,
    );
    await page.click("#review-check-diagnostics button");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "basic-intro-mode",
    );
    await page.select("#basic-intro-mode", "value");
    assert.equal(
      await page.$eval(
        "#review-check-diagnostics",
        (node) => node.childElementCount,
      ),
      0,
    );
    await page.select("#basic-intro-mode", "keep");
    await page.click("#review-precheck");
    await message(page, "구조 진단입니다");
    await page.click("#review-samples-open");
    await page.waitForFunction(
      () => document.getElementById("child-resource").value === "grade-samples",
    );
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "child-resource",
    );
    assert.equal(
      await page.$eval(
        "#review-check-diagnostics",
        (node) => node.childElementCount,
      ),
      0,
    );
    assert.equal(await page.evaluate(() => window.injected), undefined);
  });
});

test("late diagnostics and stale finally cannot restore results or unlock a newer request", async () => {
  await isolated(async ({ page, hold }) => {
    const first = hold(precheckPath);
    await page.click("#review-precheck");
    await first.ready;
    await edit(page, "basic", "intro", "응답 대기 중 입력");
    await first.respond(diagnostics());
    await page.waitForFunction(
      () => !document.getElementById("review-samples-open").disabled,
    );
    assert.equal(
      await page.$eval(
        "#review-check-diagnostics",
        (node) => node.childElementCount,
      ),
      0,
    );
    await edit(page, "basic", "intro", "저장 도입");
    await page.select("#basic-intro-mode", "keep");
    const old = hold(precheckPath);
    await page.click("#review-precheck");
    await old.ready;
    // 보이는 자료 종류 선택으로 작업 세대를 교체하며 새 작성 입력을 만들지 않는다.
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.select("#child-resource", "grade-samples");
    await page.waitForFunction(
      () => !document.getElementById("review-precheck").disabled,
    );
    const newer = hold(precheckPath);
    await page.click("#review-precheck");
    await newer.ready;
    await old.respond({ code: "EDIT_CONFLICT" }, 409);
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(
      await page.$eval("#review-precheck", (node) => node.disabled),
      true,
    );
    assert.equal(await page.$eval("#comparison", (node) => node.hidden), true);
    await newer.respond(diagnostics());
    await message(page, "구조 진단입니다");
  });
});

test("Cancel, Escape, focus trap, edit during CSRF acquisition and disabled activation probes", async () => {
  await isolated(async ({ page, requests, hold, queue, json }) => {
    for (const cancel of ["button", "escape"]) {
      await page.focus("#review-samples-check");
      await page.keyboard.press("Enter");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-accept",
      );
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      if (cancel === "button") await page.click("#ui-confirm-cancel");
      else await page.keyboard.press("Escape");
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "review-samples-check",
      );
      // 즉시 포커스 복귀를 검사한 뒤 취소 작업의 소유권 해제를 기다린다.
      await page.waitForFunction(
        () => !document.getElementById("review-samples-open").disabled,
      );
    }
    queue(precheckPath, "POST", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await page.click("#review-precheck");
    await message(page, "요청에 실패했습니다");
    const beforeConsent = lanePosts(requests).length;
    const csrf = hold("/admin/api/auth/csrf", "GET");
    await confirm(page);
    await csrf.ready;
    await edit(page, "basic", "intro", "동의 후 전송 전 새 입력");
    await csrf.respond({ headerName: "X-CSRF-TOKEN", token: "synthetic-csrf" });
    await page.waitForFunction(
      () => !document.getElementById("review-samples-open").disabled,
    );
    assert.equal(lanePosts(requests).length, beforeConsent);
    await edit(page, "basic", "intro", "저장 도입");
    await page.select("#basic-intro-mode", "keep");
    const pending = hold(checkPath);
    await page.click("#review-samples-check");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    assert.equal(
      await page.$$eval("#ui-confirm-dialog", (nodes) => nodes.length),
      1,
    );
    await page.click("#ui-confirm-accept");
    await pending.ready;
    await forceActions(page);
    assert.equal(lanePosts(requests).length, beforeConsent + 1);
    assert.deepEqual(JSON.parse(lanePosts(requests).at(-1).body), {
      expectedRev: rev,
    });
    await pending.respond({
      editRev: rev,
      checkedCount: 7,
      changed: false,
      requestId: "no-change",
    });
    await page.waitForFunction(
      () => !document.getElementById("review-precheck").disabled,
    );
    assert.match(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      /확인 7건 · 변경 없음/,
    );
  });
});

test("receipt revision GET preserves edits including keep buffers and requires fresh explicit SR01", async () => {
  await isolated(async ({ page, state, hold, requests }) => {
    await page.click("#review-precheck");
    await message(page, "구조 진단입니다");
    const post = hold(checkPath);
    await confirm(page);
    await post.ready;
    await edit(page, "answer", "methodAnswer", "전송 중 새 원고");
    await page.select("#answer-methodAnswer-mode", "keep");
    const read = hold(root, "GET");
    state.detail.editRev = nextRev;
    await post.respond({
      editRev: nextRev,
      checkedCount: 5,
      changed: true,
      requestId: "receipt",
    });
    await read.ready;
    await edit(page, "reveal", "revealText", "조회 중 해설");
    await read.respond(state.detail);
    await page.waitForFunction(
      (value) =>
        document.getElementById("version-summary").textContent.includes(value),
      {},
      nextRev,
    );
    assert.equal(
      await page.$eval("#answer-methodAnswer", (node) => node.value),
      "전송 중 새 원고",
    );
    assert.equal(
      await page.$eval("#answer-methodAnswer-mode", (node) => node.value),
      "keep",
    );
    assert.equal(
      await page.$eval("#reveal-revealText", (node) => node.value),
      "조회 중 해설",
    );
    assert.equal(
      await page.$eval(
        "#review-check-diagnostics",
        (node) => node.childElementCount,
      ),
      0,
    );
    assert.equal(lanePosts(requests).length, 2, "확인 뒤 자동 사전 검사 없음");
    assert.match(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      new RegExp(nextRev),
    );
  });
});

test("lost response and failed GET never resend or infer completion from a changed revision", async () => {
  await isolated(async ({ page, state, hold, requests }) => {
    const lost = hold(checkPath);
    await confirm(page);
    await lost.ready;
    state.detail.editRev = nextRev;
    await lost.lose();
    await message(page, "결과 미확인");
    assert.equal(
      requests.filter((request) => request.path === root).length,
      1,
      "유실 후 자동 GET도 없음",
    );
    const failed = hold(root, "GET");
    await page.click("#review-check-refresh");
    await failed.ready;
    await failed.respond({ code: "STORY_UNAVAILABLE" }, 503);
    await message(page, "조회 실패");
    await forceActions(page);
    assert.equal(lanePosts(requests).length, 1);
    await page.click("#review-check-refresh");
    await page.waitForFunction(
      () => !document.getElementById("accept-latest").disabled,
    );
    assert.match(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      /미확인/,
    );
    await confirm(page, "#accept-latest");
    assert.equal(lanePosts(requests).length, 1);
    await page.click("#review-samples-check");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.keyboard.press("Escape");
    assert.equal(
      lanePosts(requests).length,
      1,
      "새 사람 동의 없이 재전송 없음",
    );
  });
});

test("confirmed receipt survives failed refresh; CSRF and revision conflicts require explicit recovery", async () => {
  await isolated(async ({ page, state, queue, json, requests, hold }) => {
    queue(precheckPath, "POST", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await page.click("#review-precheck");
    await page.waitForFunction(
      () => !document.getElementById("review-precheck").disabled,
    );
    assert.equal(lanePosts(requests).length, 1);
    const tokenReads = state.csrfReads;
    await page.click("#review-precheck");
    await message(page, "구조 진단입니다");
    assert.equal(state.csrfReads, tokenReads + 1);
    queue(precheckPath, "POST", (request) =>
      json(request, { code: "EDIT_CONFLICT" }, 409),
    );
    await page.click("#review-precheck");
    await page.waitForSelector("#comparison:not([hidden])");
    await forceActions(page);
    assert.equal(lanePosts(requests).length, 3);
    await page.click("#refresh-latest");
    await page.waitForFunction(
      () => !document.getElementById("accept-latest").disabled,
    );
    await confirm(page, "#accept-latest");
    const failedRead = hold(root, "GET");
    await confirm(page);
    await failedRead.ready;
    await failedRead.respond({ code: "STORY_UNAVAILABLE" }, 503);
    await message(page, " 영수증은".trim());
    assert.match(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      /확인 5건 · 변경 있음/,
    );
    assert.equal(lanePosts(requests).length, 4);
    await forceActions(page);
    assert.equal(lanePosts(requests).length, 4);
  });
});

test("auth revocation clears diagnostics, receipt, confirmation and ignores late responses", async () => {
  await isolated(async ({ page, queue, json, hold }) => {
    await page.click("#review-precheck");
    await message(page, "구조 진단입니다");
    await page.click('#editor-nav a[href="#child-heading"]');
    queue(`${root}/persons`, "GET", (request) =>
      json(request, {
        editRev: rev,
        items: [{ code: "P", name: "합성 인물", activeYn: true }],
        hasNext: false,
        nextAfterKey: null,
      }),
    );
    await page.click("#child-list");
    await page.waitForSelector('[data-child-key="P"]');
    const delayed = hold(checkPath);
    await confirm(page);
    await delayed.ready;
    queue(root, "GET", (request) =>
      json(request, { code: "AUTH_REQUIRED" }, 401),
    );
    await page.click('[data-child-key="P"]');
    await page.waitForSelector("#editor[hidden]");
    await delayed.respond({
      editRev: nextRev,
      checkedCount: 5,
      changed: true,
      requestId: "late",
    });
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(
      await page.$eval("#editor", (node) => node.childElementCount),
      0,
    );
    assert.equal(await page.$("#ui-confirm-dialog"), null);
    assert.equal(await page.$("#review-check-receipt"), null);
  });
});

test("saved check panel uses shared layout at 360/768/1440 with labelled keyboard targets", async () => {
  await isolated(async ({ page }) => {
    for (const width of [360, 768, 1440]) {
      await page.setViewport({ width, height: 900 });
      const layout = await page.$eval("#review-check", (panel) => ({
        named: Boolean(
          document.getElementById(panel.getAttribute("aria-labelledby"))
            ?.textContent,
        ),
        overflow: document.documentElement.scrollWidth > innerWidth,
        buttons: [...panel.querySelectorAll("button")]
          .filter((node) => !node.hidden)
          .map((node) => ({
            text: node.textContent.trim(),
            width: node.getBoundingClientRect().width,
            height: node.getBoundingClientRect().height,
          })),
      }));
      assert.equal(layout.named, true);
      assert.equal(layout.overflow, false, `${width}px 가로 넘침`);
      for (const button of layout.buttons)
        assert.ok(button.text && button.width >= 48 && button.height >= 48);
      await page.focus("#review-precheck");
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "review-samples-open",
      );
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "review-samples-check",
      );
    }
  });
});

test("saved-example navigation retains the existing dirty-child discard confirmation", async () => {
  await isolated(async ({ page, requests }) => {
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.click("#child-new");
    await edit(page, "child", "name", "버리지 않을 인물 입력");
    await page.select("#child-name-mode", "keep");
    await page.click("#review-samples-open");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    assert.match(
      await page.$eval("#ui-confirm-message", (node) => node.textContent),
      /미저장 입력/,
    );
    await page.click("#ui-confirm-cancel");
    assert.equal(
      await page.$eval("#child-resource", (node) => node.value),
      "persons",
    );
    assert.equal(
      await page.$eval("#child-name", (node) => node.value),
      "버리지 않을 인물 입력",
    );
    await confirm(page, "#review-samples-open");
    await page.waitForFunction(
      () => document.getElementById("child-resource").value === "grade-samples",
    );
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "child-resource",
    );
    assert.equal(lanePosts(requests).length, 0);
  });
});

test("pending visible list revision or access loss cancels consent without a POST", async () => {
  for (const status of [200, 403, 404]) {
    await isolated(async ({ page, requests, hold }) => {
      await page.click('#editor-nav a[href="#child-heading"]');
      const list = hold(`${root}/persons`, "GET");
      await page.click("#child-list");
      await list.ready;
      await page.click("#review-samples-check");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      await list.respond(
        status === 200
          ? { editRev: nextRev, items: [], hasNext: false, nextAfterKey: null }
          : { code: "FORBIDDEN" },
        status,
      );
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      if (status === 200) {
        await page.waitForSelector("#comparison:not([hidden])");
        assert.equal(
          await page.$eval("#review-samples-check", (node) => node.disabled),
          true,
        );
      } else {
        await page.waitForSelector("#editor[hidden]");
        assert.equal(
          await page.$eval("#editor", (node) => node.childElementCount),
          0,
        );
      }
      assert.equal(lanePosts(requests).length, 0);
    });
  }
});

test("ordinary save CSRF rejection retains its original revocation recovery", async () => {
  await isolated(async ({ page, queue, json, requests }) => {
    await edit(page, "basic", "intro", "기존 저장 CSRF 회귀");
    queue(`${root}/sections/basic`, "PATCH", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await page.click('form[data-section="basic"] button[type="submit"]');
    await page.waitForSelector("#editor[hidden]");
    assert.equal(
      await page.$eval("#editor", (node) => node.childElementCount),
      0,
    );
    assert.equal(
      requests.filter((request) => request.method === "PATCH").length,
      1,
    );
    assert.equal(lanePosts(requests).length, 0);
  });
});

test("a changed target ignores a late result, and 503 requires GET rather than automatic resend", async () => {
  await isolated(async ({ page, hold, requests }) => {
    const old = hold(precheckPath);
    await page.click("#review-precheck");
    await old.ready;
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.select("#child-resource", "grade-samples");
    await page.waitForFunction(
      () => document.getElementById("child-resource").value === "grade-samples",
    );
    await old.respond(diagnostics());
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(
      await page.$eval(
        "#review-check-diagnostics",
        (node) => node.childElementCount,
      ),
      0,
    );
    const unavailable = hold(checkPath);
    await confirm(page);
    await unavailable.ready;
    await unavailable.respond({ code: "STORY_UNAVAILABLE" }, 503);
    await message(page, "결과 미확인");
    await forceActions(page);
    assert.equal(lanePosts(requests).length, 2);
    assert.equal(
      await page.$eval("#review-check-refresh", (node) => node.hidden),
      false,
    );
  });
});

const historyPath = `${root}/review-snapshots`;
const previewPath = `${root}/preview`;
const savedId = "9007199254740993999";
const currentId = "9007199254740993888";
const recordId = "9007199254740993777";
const savedRev = "9007199254740993001";

/**
 * 현재 포인터와 최신 ID의 순서를 일부러 다르게 만든 전송 경계용 합성 메타다.
 * @param {string} snapshotId 정확한 십진 문자열 ID.
 * @param {boolean} current 서버가 명시한 현재 여부이며 기본 false.
 */
function snapshotSummary(snapshotId = savedId, current = false) {
  return {
    snapshotId,
    sourceRev: savedRev,
    formatNo: 1,
    current,
    createdAt: "2026-10-01T00:00:00Z",
    createdBy: "synthetic-historical-creator",
  };
}

/**
 * SR04 합성 응답의 원래 정수·null·빈 문자열·배열 순서를 보존한다.
 * @param {object} snapshot 여섯 필드 메타. 실제 저장·모델 근거가 아니다.
 */
function savedPayloadText(snapshot = snapshotSummary()) {
  const payload = {
    formatNo: 1,
    storyCode: "ST_REVIEW_TEST",
    versionNo: 1,
    sourceRev: savedRev,
    policy: { marker: "synthetic-policy-only" },
    sections: {
      basic: { title: hostile, intro: "", setting: null },
      answer: { methodAnswer: "PRIVATE_ANSWER" },
      reveal: { revealText: "PRIVATE_REVEAL" },
    },
    resources: Object.fromEntries(
      [
        "persons",
        "roles",
        "pairs",
        "clues",
        "clueRoles",
        "hints",
        "events",
        "facts",
        "rubrics",
        "rubricClues",
        "gradeSamples",
      ].map((key) => [
        key,
        key === "gradeSamples"
          ? [
              {
                inputData: {
                  sourceRev: "EXACT_INTEGER",
                  references: ["https://private.invalid/never-fetch", hostile],
                  order: [3, 1, 2],
                },
              },
            ]
          : [],
      ]),
    ),
  };
  return JSON.stringify({ snapshot, payload }).replace(
    '"EXACT_INTEGER"',
    "9007199254740993555",
  );
}

/**
 * 수동 MODEL/FAIL은 합성 전송일 뿐이며 실제 모델 실행 결과를 주장하지 않는다.
 * @param {object} options ID·현재 여부·종료 여부를 바꾸는 합성 페이지 조건.
 */
function savedRecordsText({
  snapshotId = savedId,
  current = false,
  next = true,
  empty = false,
} = {}) {
  return JSON.stringify({
    snapshotId,
    current,
    items: empty
      ? []
      : [
          {
            recordId,
            kind: "MODEL",
            result: "FAIL",
            reviewerAccountKey: "synthetic-reviewer",
            modelId: null,
            effort: "",
            evidence: hostile + "\nhttps://private.invalid/evidence",
            evidenceData: {
              criticalOpenCount: "EXACT_INTEGER",
              order: [3, 1, 2],
              nullable: null,
              empty: "",
            },
            selfReviewYn: true,
            createdAt: "2026-10-01T00:00:00Z",
          },
        ],
    hasNext: next && !empty,
    nextAfterId: next && !empty ? recordId : null,
  }).replace('"EXACT_INTEGER"', "9007199254740993667");
}

/**
 * 서버 허용 필드 외 합성 누출 표식을 넣어 렌더러의 닫힌 허용 목록을 검사한다.
 * @param {object} options 응답 출처/모드/역할/수정번호 덮어쓰기이며 실제 인가 증거가 아니다.
 */
function previewResponse(options = {}) {
  const value = {
    source: "DRAFT",
    sourceRev: rev,
    snapshotId: null,
    mode: "ROLE",
    roleCode: "A",
    previewOnly: true,
    requestId: "synthetic-preview",
    ...options,
  };
  value.data =
    value.mode === "REVEAL"
      ? { title: hostile, revealText: "", secretText: "NEVER_RENDER" }
      : {
          basic: {
            title: hostile,
            intro: "",
            setting: null,
            difficulty: 3,
            estMin: 20,
            estMax: 30,
            limitSec: 2100,
            timelineOrigin: "NEVER_RENDER",
          },
          role: {
            code: value.roleCode,
            name: "합성 역할",
            brief: null,
            secretText: "NEVER_RENDER",
          },
          persons: [
            {
              code: "P",
              name: "합성 인물",
              publicText: "https://private.invalid/person",
              secretText: "NEVER_RENDER",
            },
          ],
          clues: [
            {
              code: "C",
              title: "합성 단서",
              body: "",
              personCode: null,
              sourceText: "NEVER_RENDER",
            },
          ],
          answer: { methodAnswer: "NEVER_RENDER" },
          payload: "NEVER_RENDER",
        };
  return value;
}

/**
 * 목록을 명시 조회하고 하나의 메타만 선택한다. 원문 호출은 이 헬퍼가 만들지 않는다.
 * @param {object} context 격리 전송의 페이지·응답 큐·JSON 응답기.
 * @param {boolean} more 다음20건 커서를 제공할지 여부.
 */
async function selectSnapshot({ page, queue, json }, more = false) {
  queue(historyPath, "GET", (request) =>
    json(request, {
      items: [snapshotSummary(), snapshotSummary(currentId, true)],
      hasNext: more,
      nextAfterId: more ? currentId : null,
    }),
  );
  await page.click("#saved-history-first");
  await savedState(page, "history", "success-populated");
  await page.click(`[data-snapshot-id="${savedId}"]`);
}

/**
 * 고정 영역의 관측 가능한 상태만 기다린다.
 * @param {object} page 제품 DOM이 있는 격리 페이지.
 * @param {string} lane 저장 조회 영역.
 * @param {string} state 기대하는 명시 상태.
 */
async function savedState(page, lane, state) {
  await page.waitForFunction(
    ({ lane, state }) =>
      document.getElementById(`saved-${lane}-status`)?.dataset.state === state,
    {},
    { lane, state },
  );
}

/** 저장 조회 요청만 추려 자동 원문 조회·POST가 없음을 확인한다. */
function savedRequests(requests) {
  return requests.filter(
    (request) =>
      request.path.startsWith(historyPath) || request.path === previewPath,
  );
}

test("SR03 is explicit payload-free bounded DESC history with exact independent cursor and current provenance", async () => {
  await isolated(async (context) => {
    const { page, requests, state, queue, json } = context;
    assert.equal(
      savedRequests(requests).length,
      0,
      "최초 편집기 진입에 저장 자료 prefetch 없음",
    );
    state.detail.currentSnapshotId = currentId;
    await page.reload();
    await page.waitForFunction(
      (id) =>
        document
          .getElementById("saved-review-current")
          .textContent.includes(id),
      {},
      currentId,
    );
    await selectSnapshot(context, true);
    assert.equal(
      savedRequests(requests).length,
      1,
      "메타 선택은 원문·근거·미리보기 fanout 없음",
    );
    assert.equal(savedRequests(requests)[0].query, "?size=20");
    const rows = await page.$$eval("[data-snapshot-id]", (nodes) =>
      nodes.map((node) => node.dataset.snapshotId),
    );
    assert.deepEqual(
      rows,
      [savedId, currentId],
      "서버 순서를 로컬 숫자 정렬로 바꾸지 않음",
    );
    assert.match(
      await page.$eval("#saved-review-selected", (node) => node.textContent),
      /과거 사본/,
    );
    assert.ok(
      (
        await page.$eval("#saved-review-current", (node) => node.textContent)
      ).includes(rev),
    );
    assert.ok(
      (
        await page.$eval("#saved-review-selected", (node) => node.textContent)
      ).includes(savedRev),
    );
    queue(historyPath, "GET", (request) =>
      json(request, { items: [], hasNext: false, nextAfterId: null }),
    );
    await page.click("#saved-history-next");
    await savedState(page, "history", "success-empty");
    assert.equal(
      savedRequests(requests).at(-1).query,
      `?size=20&afterId=${currentId}`,
    );
    assert.equal(
      await page.$eval(
        "#saved-review-selected",
        (node) => node.childElementCount,
      ),
      0,
    );
    assert.equal(
      await page.$$eval("[data-snapshot-id]", (nodes) => nodes.length),
      0,
    );
    assert.equal(
      await page.$eval("#saved-history-next", (node) => node.disabled),
      true,
    );
    assert.equal(savedRequests(requests).length, 2);
  });
});

test("SR04/SR06 preserve original private JSON integers, literal references, old FAIL and one-page ownership", async () => {
  await isolated(async (context) => {
    const { page, queue, raw, json, requests, state } = context;
    await selectSnapshot(context, true);
    const payload = savedPayloadText();
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, payload),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      payload,
    );
    assert.match(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      /9007199254740993555/,
    );
    assert.equal(savedRequests(requests).length, 2);
    const records = savedRecordsText();
    queue(`${historyPath}/${savedId}/records`, "GET", (request) =>
      raw(request, records),
    );
    await page.click("#saved-records-first");
    await savedState(page, "records", "success-populated");
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      records,
    );
    assert.match(
      records,
      /"result":"FAIL".*"criticalOpenCount":9007199254740993667.*"selfReviewYn":true/,
    );
    assert.doesNotMatch(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      /APPROVAL|PLAYTEST|GRADE/,
    );
    assert.equal(
      await page.$$eval(
        "#saved-review img, #saved-review script, #saved-review a",
        (nodes) => nodes.length,
      ),
      0,
    );
    assert.equal(await page.evaluate(() => window.injected), undefined);
    assert.equal(
      requests.some((request) => request.path.includes("private.invalid")),
      false,
    );
    assert.deepEqual(state.externalRequests, []);
    assert.equal(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
      0,
    );
    queue(`${historyPath}/${savedId}/records`, "GET", (request) =>
      raw(request, savedRecordsText({ empty: true })),
    );
    await page.click("#saved-records-next");
    await savedState(page, "records", "success-empty");
    assert.equal(
      savedRequests(requests).at(-1).query,
      `?size=20&afterId=${recordId}`,
    );
    assert.doesNotMatch(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      /criticalOpenCount|FAIL/,
    );
    assert.equal(
      await page.$eval("#saved-history-next", (node) => node.disabled),
      false,
      "근거 커서와 목록 커서는 독립",
    );
    queue(historyPath, "GET", (request) =>
      json(request, { items: [], hasNext: false, nextAfterId: null }),
    );
    await page.click("#saved-history-next");
    await savedState(page, "history", "success-empty");
    assert.equal(
      savedRequests(requests).at(-1).query,
      `?size=20&afterId=${currentId}`,
    );
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      "",
    );
  });
});

test("SR08 uses server-only allowlists, exclusive exact source params, null versus empty and local role validation", async () => {
  await isolated(async (context) => {
    const { page, queue, json, requests } = context;
    await page.type("#saved-preview-role", "bad-role");
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "failure");
    assert.equal(savedRequests(requests).length, 0);
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "saved-preview-role",
    );
    await page.$eval("#saved-preview-role", (node) => {
      node.value = "A";
      node.dispatchEvent(new Event("input", { bubbles: true }));
    });
    queue(previewPath, "GET", (request) => json(request, previewResponse()));
    await edit(page, "basic", "intro", "미저장 투영 금지");
    await page.select("#basic-intro-mode", "keep");
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "success-populated");
    let query = new URLSearchParams(savedRequests(requests).at(-1).query);
    assert.deepEqual(Object.fromEntries(query), {
      source: "DRAFT",
      mode: "ROLE",
      expectedRev: rev,
      roleCode: "A",
    });
    const projection = JSON.parse(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
    );
    assert.deepEqual(Object.keys(projection), [
      "basic",
      "role",
      "persons",
      "clues",
    ]);
    assert.equal(projection.basic.intro, "");
    assert.equal(projection.basic.setting, null);
    assert.equal(projection.basic.title, hostile);
    assert.equal(projection.role.brief, null);
    assert.doesNotMatch(
      JSON.stringify(projection),
      /NEVER_RENDER|미저장 투영 금지/,
    );
    assert.equal(
      await page.$$eval(
        "#saved-preview-result img, #saved-preview-result a",
        (nodes) => nodes.length,
      ),
      0,
    );
    await selectSnapshot(context);
    await page.select("#saved-preview-source", "SNAPSHOT");
    queue(previewPath, "GET", (request) =>
      json(
        request,
        previewResponse({
          source: "SNAPSHOT",
          sourceRev: savedRev,
          snapshotId: savedId,
        }),
      ),
    );
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "success-populated");
    query = new URLSearchParams(savedRequests(requests).at(-1).query);
    assert.deepEqual(Object.fromEntries(query), {
      source: "SNAPSHOT",
      mode: "ROLE",
      snapshotId: savedId,
      roleCode: "A",
    });
    await page.select("#saved-preview-mode", "REVEAL");
    assert.equal(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
      "",
    );
    queue(previewPath, "GET", (request) =>
      json(
        request,
        previewResponse({
          source: "SNAPSHOT",
          sourceRev: savedRev,
          snapshotId: savedId,
          mode: "REVEAL",
          roleCode: null,
        }),
      ),
    );
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "success-populated");
    query = new URLSearchParams(savedRequests(requests).at(-1).query);
    assert.equal(query.has("roleCode"), false);
    assert.equal(query.has("expectedRev"), false);
    assert.deepEqual(
      JSON.parse(
        await page.$eval("#saved-preview-result", (node) => node.textContent),
      ),
      { title: hostile, revealText: "" },
    );
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "미저장 투영 금지",
    );
    assert.equal(
      await page.$eval("#basic-intro-mode", (node) => node.value),
      "keep",
    );
    assert.equal(lanePosts(requests).length, 0);
    assert.equal(
      savedRequests(requests).some(
        (request) => request.path === `${historyPath}/${savedId}`,
      ),
      false,
    );
  });
});

test("selection and source/mode/role changes immediately purge every private buffer without discarding manuscript", async () => {
  await isolated(async (context) => {
    const { page, queue, raw, hold, requests } = context;
    await selectSnapshot(context);
    await edit(page, "answer", "methodAnswer", "선택 전환에도 보존");
    await page.select("#answer-methodAnswer-mode", "keep");
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, savedPayloadText()),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    for (const [selector, value] of [
      ["#saved-preview-source", "SNAPSHOT"],
      ["#saved-preview-mode", "REVEAL"],
    ]) {
      await page.select(selector, value);
      assert.equal(
        await page.$eval("#saved-payload-result", (node) => node.textContent),
        "",
      );
      queue(`${historyPath}/${savedId}`, "GET", (request) =>
        raw(request, savedPayloadText()),
      );
      await page.click("#saved-payload-read");
      await savedState(page, "payload", "success-populated");
    }
    await page.select("#saved-preview-mode", "ROLE");
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, savedPayloadText()),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    await page.type("#saved-preview-role", "A");
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      "",
    );
    const oldPayload = hold(`${historyPath}/${savedId}`, "GET");
    const oldRecords = hold(`${historyPath}/${savedId}/records`, "GET");
    await page.click("#saved-payload-read");
    await oldPayload.ready;
    await page.click("#saved-records-first");
    await oldRecords.ready;
    const before = savedRequests(requests).length;
    await page.click(`[data-snapshot-id="${currentId}"]`);
    assert.equal(
      savedRequests(requests).length,
      before,
      "선택만으로 새 원문 요청 없음",
    );
    await oldPayload.respondRaw(savedPayloadText());
    await oldRecords.respondRaw(savedRecordsText());
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    for (const lane of ["payload", "records", "preview"])
      assert.equal(
        await page.$eval(`#saved-${lane}-result`, (node) => node.textContent),
        "",
      );
    assert.equal(
      await page.$eval("#saved-records-next", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#answer-methodAnswer", (node) => node.value),
      "선택 전환에도 보존",
    );
    assert.equal(
      await page.$eval("#answer-methodAnswer-mode", (node) => node.value),
      "keep",
    );
    assert.equal(
      await page.$("#ui-confirm-dialog"),
      null,
      "원고 대상을 바꾸거나 입력을 버리지 않는 선택",
    );
  });
});

test("preview late responses cannot cross source, mode, role or a newer in-flight ownership", async () => {
  for (const change of ["source", "mode", "role"]) {
    await isolated(async (context) => {
      const { page, hold } = context;
      await selectSnapshot(context);
      await page.type("#saved-preview-role", "A");
      const old = hold(previewPath, "GET");
      await page.click("#saved-preview-read");
      await old.ready;
      if (change === "role") await page.type("#saved-preview-role", "B");
      else
        await page.select(
          `#saved-preview-${change}`,
          change === "source" ? "SNAPSHOT" : "REVEAL",
        );
      assert.equal(
        await page.$eval("#saved-preview-result", (node) => node.textContent),
        "",
      );
      const newer = hold(previewPath, "GET");
      await page.click("#saved-preview-read");
      await newer.ready;
      await old.respond({ code: "AUTH_REQUIRED" }, 401);
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      assert.equal(
        await page.$eval("#editor", (node) => node.hidden),
        false,
        "이전 조건의 거절로 현재 화면을 회수하지 않음",
      );
      assert.equal(
        await page.$eval("#saved-preview-read", (node) => node.disabled),
        true,
      );
      await newer.respond(
        previewResponse(
          change === "source"
            ? { source: "SNAPSHOT", sourceRev: savedRev, snapshotId: savedId }
            : change === "mode"
              ? { mode: "REVEAL", roleCode: null }
              : { roleCode: "AB" },
        ),
      );
      await savedState(page, "preview", "success-populated");
    });
  }
});

test("read audit 503 and mismatching echoes are failures, purge private data and never retry or claim empty", async () => {
  for (const mismatch of [
    "audit",
    "source",
    "mode",
    "role",
    "revision",
    "snapshot",
    "previewOnly",
  ]) {
    await isolated(async (context) => {
      const { page, queue, json, raw, requests } = context;
      await selectSnapshot(context);
      await page.type("#saved-preview-role", "A");
      queue(`${historyPath}/${savedId}`, "GET", (request) =>
        raw(request, savedPayloadText()),
      );
      await page.click("#saved-payload-read");
      await savedState(page, "payload", "success-populated");
      const response = previewResponse();
      if (mismatch === "source") response.source = "SNAPSHOT";
      if (mismatch === "mode") response.mode = "REVEAL";
      if (mismatch === "role") response.roleCode = "B";
      if (mismatch === "revision") response.sourceRev = savedRev;
      if (mismatch === "snapshot") response.snapshotId = savedId;
      if (mismatch === "previewOnly") response.previewOnly = false;
      queue(previewPath, "GET", (request) =>
        json(
          request,
          mismatch === "audit" ? { code: "STORY_UNAVAILABLE" } : response,
          mismatch === "audit" ? 503 : 200,
        ),
      );
      await page.click("#saved-preview-read");
      await savedState(page, "preview", "failure");
      assert.equal(
        await page.$eval("#saved-payload-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-review-selected", (node) => node.textContent),
        "",
      );
      assert.equal(savedRequests(requests).length, 3);
      assert.equal(
        await page.$eval("#saved-history-next", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#saved-records-next", (node) => node.disabled),
        true,
      );
      assert.match(
        await page.$eval("#saved-preview-status", (node) => node.textContent),
        /빈 결과나 검수 통과가 아닙니다/,
      );
    });
  }
});

test("late private reads are purged across revocation, comparison, unavailability and editor generation replacement", async () => {
  for (const transition of [
    "revoke",
    "comparison",
    "unavailable",
    "generation",
    "replacement",
  ]) {
    await isolated(async (context) => {
      const { page, queue, json, hold, state } = context;
      await selectSnapshot(context);
      const pending = hold(`${historyPath}/${savedId}`, "GET");
      await page.click("#saved-payload-read");
      await pending.ready;
      if (transition === "unavailable") {
        queue(`${historyPath}/${savedId}/records`, "GET", (request) =>
          json(request, { code: "STORY_UNAVAILABLE" }, 503),
        );
        await page.click("#saved-records-first");
        await savedState(page, "records", "failure");
      } else if (transition === "replacement") {
        await edit(page, "basic", "intro", "새 저장본");
        queue(`${root}/sections/basic`, "PATCH", (request) => {
          state.detail = { ...source(), editRev: nextRev };
          state.detail.sections.basic.intro = "새 저장본";
          return json(request, { editRev: nextRev, changed: true });
        });
        await page.click("#basic-save");
        await page.waitForFunction(
          (revision) =>
            document
              .getElementById("version-summary")
              .textContent.includes(revision),
          {},
          nextRev,
        );
      } else {
        await page.click('#editor-nav a[href="#child-heading"]');
        if (transition === "generation")
          await page.select("#child-resource", "grade-samples");
        else {
          queue(`${root}/persons`, "GET", (request) =>
            json(
              request,
              transition === "revoke"
                ? { code: "FORBIDDEN" }
                : {
                    editRev: nextRev,
                    items: [],
                    hasNext: false,
                    nextAfterKey: null,
                  },
              transition === "revoke" ? 403 : 200,
            ),
          );
          await page.click("#child-list");
          await page.waitForSelector(
            transition === "revoke"
              ? "#editor[hidden]"
              : "#comparison:not([hidden])",
          );
        }
      }
      await pending.respondRaw(savedPayloadText());
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      if (transition === "revoke") {
        assert.equal(
          await page.$eval("#editor", (node) => node.childElementCount),
          0,
        );
      } else {
        assert.equal(
          await page.$eval("#saved-payload-result", (node) => node.textContent),
          "",
        );
        assert.equal(
          await page.$eval(
            "#saved-review-selected",
            (node) => node.textContent,
          ),
          "",
        );
        if (transition === "comparison")
          assert.equal(
            await page.$eval("#saved-history-first", (node) => node.disabled),
            true,
          );
      }
    });
  }
});

test("saved-review ordinary controls stay labelled and within all five widths", async () => {
  await isolated(async (context) => {
    const { page, queue, raw } = context;
    await selectSnapshot(context);
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, savedPayloadText()),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900 });
      const metrics = await page.$eval("#saved-review", (panel) => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        reading: panel.querySelector(".reading").getBoundingClientRect().width,
        small: [...panel.querySelectorAll("button,input,select")].filter(
          (node) =>
            node.getClientRects().length &&
            node.getBoundingClientRect().height < 48,
        ).length,
        labelled: [...panel.querySelectorAll("input,select")].every(
          (node) => node.labels.length > 0,
        ),
      }));
      assert.equal(metrics.overflow, false);
      assert.ok(metrics.reading <= 720);
      assert.equal(metrics.small, 0);
      assert.equal(metrics.labelled, true);
      await page.focus("#saved-history-first");
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.dataset.snapshotId),
        savedId,
      );
      assert.equal(
        await page.evaluate(
          () => getComputedStyle(document.activeElement).outlineStyle,
        ),
        "solid",
      );
    }
  });
});

test("private read audit failures and mismatching snapshot envelopes never expose a success body", async () => {
  for (const lane of ["payload", "records"]) {
    for (const failure of ["audit", "snapshot", "revision"]) {
      await isolated(async (context) => {
        const { page, queue, raw, json, requests } = context;
        await selectSnapshot(context, true);
        const path = `${historyPath}/${savedId}${lane === "records" ? "/records" : ""}`;
        if (failure === "audit") {
          queue(path, "GET", (request) =>
            json(request, { code: "STORY_UNAVAILABLE" }, 503),
          );
        } else {
          const body =
            lane === "payload"
              ? savedPayloadText(
                  failure === "snapshot"
                    ? snapshotSummary(currentId)
                    : { ...snapshotSummary(), sourceRev: rev },
                )
              : failure === "snapshot"
                ? savedRecordsText({ snapshotId: currentId })
                : savedRecordsText().replace(
                    `"nextAfterId":"${recordId}"`,
                    '"nextAfterId":9007199254740993777',
                  );
          queue(path, "GET", (request) => raw(request, body));
        }
        await page.click(
          lane === "payload" ? "#saved-payload-read" : "#saved-records-first",
        );
        await savedState(page, lane, "failure");
        assert.equal(
          await page.$eval(`#saved-${lane}-result`, (node) => node.textContent),
          "",
        );
        assert.equal(
          await page.$eval(
            "#saved-review-selected",
            (node) => node.textContent,
          ),
          "",
        );
        assert.equal(savedRequests(requests).length, 2);
        assert.equal(
          savedRequests(requests).every((request) => request.method === "GET"),
          true,
        );
        assert.equal(
          await page.$eval("#saved-history-next", (node) => node.disabled),
          true,
        );
      });
    }
  }
});

test("history generation fence discards late metadata and does not unlock a newer page", async () => {
  await isolated(async ({ page, hold, requests }) => {
    const first = hold(historyPath, "GET");
    await page.click("#saved-history-first");
    await first.ready;
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.select("#child-resource", "grade-samples");
    const second = hold(historyPath, "GET");
    await page.click("#saved-history-first");
    await second.ready;
    await first.respond({
      items: [snapshotSummary()],
      hasNext: false,
      nextAfterId: null,
    });
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(
      await page.$$eval("[data-snapshot-id]", (nodes) => nodes.length),
      0,
    );
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      true,
    );
    await second.respond({
      items: [snapshotSummary(currentId, true)],
      hasNext: false,
      nextAfterId: null,
    });
    await savedState(page, "history", "success-populated");
    assert.deepEqual(
      await page.$$eval("[data-snapshot-id]", (nodes) =>
        nodes.map((node) => node.dataset.snapshotId),
      ),
      [currentId],
    );
    assert.equal(savedRequests(requests).length, 2);
  });
});

test("ordinary saved reads do not infer write or execution capabilities from REVIEW status", async () => {
  await isolated(async (context) => {
    const { page, state, requests, queue, json } = context;
    state.detail.status = "REVIEW";
    state.detail.currentSnapshotId = currentId;
    await page.reload();
    await page.waitForFunction(
      () => !document.getElementById("saved-history-first").disabled,
    );
    assert.equal(
      await page.$eval("#review-precheck", (node) => node.disabled),
      true,
    );
    await selectSnapshot(context);
    await page.select("#saved-preview-source", "SNAPSHOT");
    await page.select("#saved-preview-mode", "REVEAL");
    queue(previewPath, "GET", (request) =>
      json(
        request,
        previewResponse({
          source: "SNAPSHOT",
          snapshotId: savedId,
          sourceRev: savedRev,
          mode: "REVEAL",
          roleCode: null,
        }),
      ),
    );
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "success-populated");
    assert.equal(
      savedRequests(requests).every((request) => request.method === "GET"),
      true,
    );
    assert.equal(
      requests.some((request) =>
        /review-requests|return-to-draft|execution-issues|publish|attest/.test(
          request.path,
        ),
      ),
      false,
    );
    assert.equal(
      await page.$$eval("form[data-section]", (nodes) => nodes.length),
      4,
    );
  });
});

test("known non-DRAFT and inactive versions reject DRAFT preview without locking saved reads", async () => {
  for (const stateChange of [
    { status: "REVIEW", currentSnapshotId: currentId },
    { status: "READY", currentSnapshotId: currentId },
    { status: "PUBLISHED", currentSnapshotId: currentId },
    {
      storyActiveYn: false,
      permissions: { edit: false, review: false, publish: false },
    },
    {
      activeYn: false,
      permissions: { edit: false, review: false, publish: false },
    },
  ]) {
    await isolated(async (context) => {
      const { page, state, requests } = context;
      Object.assign(state.detail, stateChange);
      await page.reload();
      await page.waitForFunction(
        () => !document.getElementById("saved-history-first").disabled,
      );
      await page.select("#saved-preview-mode", "REVEAL");
      assert.equal(
        await page.$eval("#saved-preview-source", (node) => node.value),
        "DRAFT",
      );
      assert.equal(
        await page.$eval("#saved-preview-read", (node) => node.disabled),
        true,
      );
      // 비활성 진입점의 음성 검사이며 사용자 클릭·실제 서버 인가 증거가 아니다.
      await page.evaluate(() =>
        document
          .getElementById("saved-preview-read")
          .dispatchEvent(new Event("click")),
      );
      assert.equal(savedRequests(requests).length, 0);
      assert.equal(
        await page.$eval("#saved-history-first", (node) => node.disabled),
        false,
      );
      await selectSnapshot(context);
      await page.select("#saved-preview-source", "SNAPSHOT");
      assert.equal(
        await page.$eval("#saved-preview-read", (node) => node.disabled),
        false,
      );
    });
  }
});

test("DRAFT with a current snapshot also rejects preview at the UI and request boundary", async () => {
  await isolated(async (context) => {
    const { page, state, requests } = context;
    state.detail.currentSnapshotId = currentId;
    await page.reload();
    await page.waitForFunction(
      () => !document.getElementById("saved-history-first").disabled,
    );
    await page.select("#saved-preview-mode", "REVEAL");
    assert.equal(
      await page.$eval("#saved-preview-read", (node) => node.disabled),
      true,
    );
    // 비활성 버튼의 합성 음성 검사이며 실제 사용자 행동 증거가 아니다.
    await page.$eval("#saved-preview-read", (node) =>
      node.dispatchEvent(new Event("click")),
    );
    assert.equal(savedRequests(requests).length, 0);
    await selectSnapshot(context);
    assert.equal(
      await page.$eval("#saved-payload-read", (node) => node.disabled),
      false,
    );
  });
});

test("initial DRAFT racing REVIEW recovers explicit saved reads without adopting the write baseline or discarding buffers", async () => {
  await isolated(async (context) => {
    const { page, state, requests, queue, json, raw, hold } = context;
    await selectSnapshot(context);
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, savedPayloadText()),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.click("#child-new");
    await edit(page, "child", "name", "열람 복구 중 보존할 새 인물");
    await page.select("#child-name-mode", "keep");
    await edit(page, "basic", "intro", "열람 복구 중 보존할 도입");
    await edit(page, "answer", "methodAnswer", "유지 뒤에 남은 정답 수정");
    await page.select("#answer-methodAnswer-mode", "keep");
    await edit(page, "reveal", "revealText", "비우기 뒤 보존할 해설");
    await page.select("#reveal-revealText-mode", "clear");
    await selectSnapshot(context);
    await page.select("#saved-preview-mode", "REVEAL");
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, savedPayloadText()),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    const late = hold(`${historyPath}/${savedId}/records`, "GET");
    await page.click("#saved-records-first");
    await late.ready;
    const buffers = await page.$$eval("[data-field]", (nodes) =>
      nodes.map((node) => ({
        id: node.id,
        mode: node.value,
        value: node.closest(".field").querySelector("[data-value]").value,
      })),
    );
    const start = requests.length;
    const failed = hold(previewPath, "GET");
    await page.click("#saved-preview-read");
    await failed.ready;
    state.detail = {
      ...source(),
      editRev: nextRev,
      status: "REVIEW",
      currentSnapshotId: currentId,
    };
    await failed.respond({ code: "STATE_CONFLICT" }, 409);
    await savedState(page, "preview", "failure");
    assert.equal(
      requests.length,
      start + 1,
      "409 뒤 자동 현재 GET·저장·재시도 없음",
    );
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      "",
    );
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval(
        "#saved-metadata-refresh",
        (node) => node.hidden || node.disabled,
      ),
      false,
    );
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    assert.equal(
      requests.length,
      start + 2,
      "명시 현재 GET 한 번만, 자식·이력 자동 fanout 없음",
    );
    assert.deepEqual(
      requests.slice(start).map(({ method, path }) => ({ method, path })),
      [
        { method: "GET", path: previewPath },
        { method: "GET", path: root },
      ],
    );
    assert.equal(
      new URLSearchParams(requests[start].query).get("expectedRev"),
      rev,
    );
    const current = await page.$eval(
      "#saved-review-current",
      (node) => node.textContent,
    );
    assert.ok(
      current.includes("REVIEW") &&
        current.includes(nextRev) &&
        current.includes(currentId),
    );
    assert.match(current, /명시적 현재 GET · 쓰기 기준과 별도/);
    assert.match(
      await page.$eval("#saved-metadata-status", (node) => node.textContent),
      /영수증이 아닙니다/,
    );
    assert.equal(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      "",
    );
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    assert.equal(
      await page.$eval("#accept-latest", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#saved-preview-read", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      false,
    );
    await late.respondRaw(savedRecordsText());
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      "",
    );
    await selectSnapshot(context);
    await page.select("#saved-preview-source", "SNAPSHOT");
    queue(`${historyPath}/${savedId}`, "GET", (request) =>
      raw(request, savedPayloadText()),
    );
    await page.click("#saved-payload-read");
    await savedState(page, "payload", "success-populated");
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      savedPayloadText(),
    );
    queue(`${historyPath}/${savedId}/records`, "GET", (request) =>
      raw(request, savedRecordsText()),
    );
    await page.click("#saved-records-first");
    await savedState(page, "records", "success-populated");
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      savedRecordsText(),
    );
    queue(previewPath, "GET", (request) =>
      json(
        request,
        previewResponse({
          source: "SNAPSHOT",
          sourceRev: savedRev,
          snapshotId: savedId,
          mode: "REVEAL",
          roleCode: null,
        }),
      ),
    );
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "success-populated");
    assert.deepEqual(
      JSON.parse(
        await page.$eval("#saved-preview-result", (node) => node.textContent),
      ),
      { title: hostile, revealText: "" },
    );
    await forceActions(page);
    // 잠긴 쓰기 폼의 음성 진입 검사이며 사용자 저장 증거가 아니다.
    await page.$eval('form[data-section="basic"]', (node) =>
      node.dispatchEvent(
        new Event("submit", { bubbles: true, cancelable: true }),
      ),
    );
    assert.deepEqual(
      requests.slice(start).map(({ method, path }) => ({ method, path })),
      [
        { method: "GET", path: previewPath },
        { method: "GET", path: root },
        { method: "GET", path: historyPath },
        { method: "GET", path: `${historyPath}/${savedId}` },
        { method: "GET", path: `${historyPath}/${savedId}/records` },
        { method: "GET", path: previewPath },
      ],
      "실패 preview 하나·현재 GET 하나·명시 열람 네 번 외 POST/PATCH/새 의도/재전송 없음",
    );
    assert.deepEqual(
      await page.$$eval("[data-field]", (nodes) =>
        nodes.map((node) => ({
          id: node.id,
          mode: node.value,
          value: node.closest(".field").querySelector("[data-value]").value,
        })),
      ),
      buffers,
      "value/clear/keep 뒤 변경 버퍼와 자식 입력까지 그대로 보존",
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    assert.equal(await page.$("#ui-confirm-dialog"), null);
  });
});

test("current DRAFT read revision can advance while old write revision stays locked until comparison consent", async () => {
  await isolated(async ({ page, state, queue, json, requests }) => {
    await edit(page, "basic", "intro", "쓰기 번호는 자동 승계하지 않음");
    await page.select("#saved-preview-mode", "REVEAL");
    queue(previewPath, "GET", (request) =>
      json(request, { code: "EDIT_CONFLICT" }, 409),
    );
    const start = requests.length;
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "failure");
    state.detail.editRev = nextRev;
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    queue(previewPath, "GET", (request) =>
      json(
        request,
        previewResponse({
          sourceRev: nextRev,
          mode: "REVEAL",
          roleCode: null,
        }),
      ),
    );
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "success-populated");
    const reads = requests.slice(start);
    assert.deepEqual(
      reads.map(({ method, path }) => ({ method, path })),
      [
        { method: "GET", path: previewPath },
        { method: "GET", path: root },
        { method: "GET", path: previewPath },
      ],
    );
    assert.equal(new URLSearchParams(reads[0].query).get("expectedRev"), rev);
    assert.equal(
      new URLSearchParams(reads[2].query).get("expectedRev"),
      nextRev,
    );
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    await page.$eval('form[data-section="basic"]', (node) =>
      node.dispatchEvent(
        new Event("submit", { bubbles: true, cancelable: true }),
      ),
    );
    assert.equal(requests.length, start + 3);
    await page.click("#refresh-latest");
    await page.waitForFunction(
      () => !document.getElementById("accept-latest").disabled,
    );
    assert.ok(
      (await page.$eval("#latest-values", (node) => node.textContent)).includes(
        `기존 수정번호 ${rev} → 서버 최신 수정번호 ${nextRev}`,
      ),
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    await confirm(page, "#accept-latest");
    // 확인창 종료가 아니라 명시적으로 수락한 쓰기 기준의 반영을 기다린다.
    await page.waitForFunction(
      () => document.getElementById("comparison").hidden,
    );
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(nextRev),
    );
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "쓰기 번호는 자동 승계하지 않음",
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      false,
    );
    assert.equal(
      requests.length,
      start + 4,
      "기존 비교·수락 역시 변경 요청을 자동 생성하지 않음",
    );
  });
});

test("read metadata recovery retains confirmed versus unknown SR09 receipts and uncertainty locks", async () => {
  for (const receiptKnown of [true, false]) {
    await isolated(async (context) => {
      const { page, state, hold, requests } = context;
      const post = hold(checkPath);
      await confirm(page);
      await post.ready;
      state.detail = {
        ...source(),
        editRev: nextRev,
        status: "REVIEW",
        currentSnapshotId: currentId,
      };
      if (receiptKnown) {
        const read = hold(root, "GET");
        await post.respond({
          editRev: nextRev,
          checkedCount: 5,
          changed: true,
          requestId: "confirmed-only",
        });
        await read.ready;
        await read.respond({ code: "STORY_UNAVAILABLE" }, 503);
        await message(page, "영수증은 받았으나");
      } else {
        await post.lose();
        await message(page, "결과 미확인");
      }
      const receipt = await page.$eval(
        "#review-check-receipt",
        (node) => node.textContent,
      );
      assert.match(
        receipt,
        receiptKnown ? /사람 확인 영수증.*확인 5건/ : /결과 미확인/,
      );
      await edit(page, "answer", "methodAnswer", "미확인 중 수정 버퍼");
      await page.select("#answer-methodAnswer-mode", "keep");
      const start = requests.length;
      await page.click("#saved-metadata-refresh");
      await savedState(page, "metadata", "success");
      assert.deepEqual(
        requests.slice(start).map(({ method, path }) => ({ method, path })),
        [{ method: "GET", path: root }],
      );
      assert.equal(
        await page.$eval("#review-check-receipt", (node) => node.textContent),
        receipt,
      );
      assert.equal(
        await page.$eval("#review-check-refresh", (node) => node.hidden),
        false,
      );
      assert.equal(
        await page.$eval("#review-samples-check", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#basic-save", (node) => node.disabled),
        true,
      );
      assert.ok(
        (
          await page.$eval("#version-summary", (node) => node.textContent)
        ).includes(rev),
      );
      assert.equal(
        await page.$eval("#answer-methodAnswer-mode", (node) => node.value),
        "keep",
      );
      assert.equal(
        await page.$eval("#answer-methodAnswer", (node) => node.value),
        "미확인 중 수정 버퍼",
      );
      await selectSnapshot(context);
      await forceActions(page);
      assert.equal(
        lanePosts(requests).length,
        1,
        "현재 GET은 SR09 완료 추정·재전송이 아님",
      );
      assert.equal(requests.length, start + 2);
    });
  }
});

test("failed or invalid metadata recovery remains a visible failure with no automatic reads", async () => {
  for (const failure of ["unavailable", "revision", "pointer", "target"]) {
    await isolated(async ({ page, state, queue, json, requests }) => {
      await edit(page, "basic", "intro", "실패에도 보존");
      await page.select("#basic-intro-mode", "keep");
      await page.select("#saved-preview-mode", "REVEAL");
      queue(previewPath, "GET", (request) =>
        json(request, { code: "STATE_CONFLICT" }, 409),
      );
      await page.click("#saved-preview-read");
      await savedState(page, "preview", "failure");
      const invalid = { ...source(), editRev: nextRev };
      if (failure === "revision") invalid.editRev = 42;
      if (failure === "pointer") delete invalid.currentSnapshotId;
      if (failure === "target") invalid.storyCode = "ST_OTHER";
      queue(root, "GET", (request) =>
        json(
          request,
          failure === "unavailable" ? { code: "STORY_UNAVAILABLE" } : invalid,
          failure === "unavailable" ? 503 : 200,
        ),
      );
      const start = requests.length;
      await page.click("#saved-metadata-refresh");
      await savedState(page, "metadata", "failure");
      assert.equal(requests.length, start + 1);
      assert.equal(
        await page.$eval("#saved-review-current", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-history-first", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#basic-save", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#basic-intro", (node) => node.value),
        "실패에도 보존",
      );
      assert.equal(
        await page.$eval("#basic-intro-mode", (node) => node.value),
        "keep",
      );
      state.detail = {
        ...source(),
        editRev: nextRev,
        status: "REVIEW",
        currentSnapshotId: currentId,
      };
      await page.click("#saved-metadata-refresh");
      await savedState(page, "metadata", "success");
      assert.equal(requests.length, start + 2);
      assert.equal(
        await page.$eval("#saved-history-first", (node) => node.disabled),
        false,
      );
      assert.equal(
        await page.$eval("#basic-save", (node) => node.disabled),
        true,
      );
    });
  }
});

test("late metadata cannot cross comparison ownership or unlock a newer read; current access loss purges buffers", async () => {
  await isolated(async ({ page, state, queue, json, hold, requests }) => {
    await edit(page, "basic", "intro", "회수 전 미저장");
    await page.select("#saved-preview-mode", "REVEAL");
    queue(previewPath, "GET", (request) =>
      json(request, { code: "STATE_CONFLICT" }, 409),
    );
    await page.click("#saved-preview-read");
    await savedState(page, "preview", "failure");
    state.detail = {
      ...source(),
      editRev: nextRev,
      status: "REVIEW",
      currentSnapshotId: currentId,
    };
    const old = hold(root, "GET");
    await page.click("#saved-metadata-refresh");
    await old.ready;
    const comparison = hold(root, "GET");
    await page.click("#refresh-latest");
    await comparison.ready;
    await comparison.respond(state.detail);
    await page.waitForFunction(
      (revision) =>
        document.getElementById("latest-values").textContent.includes(revision),
      {},
      nextRev,
    );
    assert.equal(
      await page.$eval("#accept-latest", (node) => node.disabled),
      true,
    );
    const newer = hold(root, "GET");
    await page.click("#saved-metadata-refresh");
    await newer.ready;
    const start = requests.length;
    await old.respond({ code: "AUTH_REQUIRED" }, 401);
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
    assert.equal(
      await page.$eval("#saved-metadata-refresh", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      true,
    );
    await newer.respond(state.detail);
    await savedState(page, "metadata", "success");
    assert.equal(requests.length, start);
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      false,
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    queue(root, "GET", (request) => json(request, { code: "FORBIDDEN" }, 403));
    await page.click("#saved-metadata-refresh");
    await page.waitForSelector("#editor[hidden]");
    assert.equal(
      await page.$eval("#editor", (node) => node.childElementCount),
      0,
    );
    assert.equal(requests.length, start + 1);
  });
});

/**
 * 실제 ReviewResult 필드만 사용하는 합성 전송 영수증이다. 저장·품질 판정을 주장하지 않는다.
 * @param {object} overrides 행동별 원래 번호·현재 포인터·재생 여부.
 */
function lifecycleResponse(overrides = {}) {
  return {
    snapshotId: currentId,
    sourceRev: rev,
    editRev: nextRev,
    status: "REVIEW",
    currentSnapshotId: currentId,
    replayed: false,
    requestId: "00000000-0000-4000-8000-000000000002",
    ...overrides,
  };
}

/** 현재 전환 패널의 실제 비동기 안내만 기다린다. */
async function lifecycleMessage(page, fragment) {
  await page.waitForFunction(
    (text) =>
      document
        .getElementById("review-lifecycle-status")
        ?.textContent.includes(text),
    {},
    fragment,
  );
}

/**
 * 보이는 현재 GET·비교·공통 확인으로 쓰기 기준을 수락하고 비교 패널이 닫힐 때까지 기다린다.
 * @param {object} page 격리한 실제 제품 페이지. 확인창 종료 뒤의 신원 재확인·기준 반영도 기다린다.
 * @throws {Error} 조회·확인·비교 수락이 완료되지 않으면 기존 대기 제한으로 실패한다.
 */
async function acceptLifecycle(page) {
  await page.click("#review-lifecycle-refresh");
  await page.waitForFunction(
    () => !document.getElementById("accept-latest").disabled,
  );
  await confirm(page, "#accept-latest");
  await page.waitForFunction(
    () => document.getElementById("comparison").hidden,
  );
}

/** SR02/SR07의 실제 전송만 추리며 화면의 비활성 음성 탐침은 성공으로 세지 않는다. */
function lifecyclePosts(requests) {
  return requests.filter(
    ({ method, path }) =>
      method === "POST" && [requestPath, returnPath].includes(path),
  );
}

test("authoritative permissions reject missing null nonboolean extra and array shapes without losing producer reads", async () => {
  for (const permissions of [
    undefined,
    null,
    {},
    [],
    { edit: true, review: false },
    { edit: "true", review: false, publish: false },
    { edit: true, review: 1, publish: false },
    { edit: true, review: false, publish: null },
    { edit: true, review: false, publish: false, owner: true },
  ]) {
    const data = source();
    if (permissions === undefined) delete data.permissions;
    else data.permissions = permissions;
    await isolated(async ({ page, requests }) => {
      assert.match(
        await page.$eval("#review-permissions", (node) => node.textContent),
        /권한 응답 미확인/,
      );
      for (const id of [
        "basic-save",
        "review-precheck",
        "review-samples-check",
        "review-request",
        "review-withdraw",
        "review-changes-required",
      ])
        assert.equal(
          await page.$eval(`#${id}`, (node) => node.disabled),
          true,
          id,
        );
      assert.equal(
        await page.$eval("#saved-history-first", (node) => node.disabled),
        false,
      );
      // 비활성 행동의 요청 경계만 확인하는 음성 탐침이다.
      await page.evaluate(() => {
        document
          .getElementById("review-request")
          .dispatchEvent(new Event("click"));
        for (const id of ["review-withdraw-form", "review-changes-form"])
          document
            .getElementById(id)
            .dispatchEvent(new Event("submit", { cancelable: true }));
      });
      assert.equal(lifecyclePosts(requests).length, 0);
    }, data);
  }
});

test("SR02 owner request freezes a UUID v4 and revision and confirmed receipt survives failed refresh with keep buffers", async () => {
  await isolated(async ({ page, requests, hold, queue, json }) => {
    assert.equal(
      await page.$eval("#review-changes-required", (node) => node.disabled),
      true,
    );
    const post = hold(requestPath);
    queue(root, "GET", (request) =>
      json(request, { code: "STORY_UNAVAILABLE" }, 503),
    );
    await confirm(page, "#review-request");
    await post.ready;
    const sent = lifecyclePosts(requests);
    assert.equal(sent.length, 1);
    const body = JSON.parse(sent[0].body);
    assert.deepEqual(Object.keys(body).sort(), ["expectedRev", "requestKey"]);
    assert.equal(body.expectedRev, rev);
    assert.match(
      body.requestKey,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    assert.ok(Buffer.byteLength(sent[0].body) < 8192);
    await edit(page, "basic", "intro", "전송 뒤 유지할 입력");
    await page.select("#basic-intro-mode", "keep");
    await post.respond(lifecycleResponse(), 201);
    await lifecycleMessage(page, "영수증은 확정됐으나");
    assert.equal(
      await page.$eval(
        "#review-lifecycle-receipt",
        (node) => node.dataset.state,
      ),
      "confirmed",
    );
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "전송 뒤 유지할 입력",
    );
    assert.equal(
      await page.$eval("#basic-intro-mode", (node) => node.value),
      "keep",
    );
    assert.equal(
      await page.$eval("#review-request-replay", (node) => node.hidden),
      true,
    );
    assert.equal(lifecyclePosts(requests).length, 1);
    assert.equal(
      requests.filter(({ path }) => path.startsWith(historyPath)).length,
      0,
    );
  });
});

test("SR02 response loss replays only the original exact body and historical receipt never becomes current", async () => {
  await isolated(async ({ page, state, requests, hold }) => {
    const first = hold(requestPath);
    await confirm(page, "#review-request");
    await first.ready;
    await first.lose();
    await lifecycleMessage(page, "결과 미확인");
    const original = lifecyclePosts(requests)[0].body;
    state.detail = { ...source(), editRev: "9007199254740993129" };
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    const repeated = hold(requestPath);
    await confirm(page, "#review-request-replay");
    await repeated.ready;
    assert.equal(lifecyclePosts(requests)[1].body, original);
    await repeated.respond(
      lifecycleResponse({
        snapshotId: savedId,
        editRev: state.detail.editRev,
        status: "DRAFT",
        currentSnapshotId: null,
        replayed: true,
      }),
    );
    await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
    const receipt = await page.$eval(
      "#review-lifecycle-receipt",
      (node) => node.textContent,
    );
    assert.match(receipt, /원래 요청 재생/);
    assert.ok(receipt.includes(savedId));
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    await confirm(page, "#accept-latest");
    assert.match(
      await page.$eval("#review-lifecycle-target", (node) => node.textContent),
      /currentSnapshotId null/,
    );
    assert.equal(lifecyclePosts(requests).length, 2);
    assert.equal(
      requests.filter(({ path }) => path.startsWith(historyPath)).length,
      0,
    );
  });
});

test("SR07 reviewer-only changes-required uses current pointer not selected history and exact reason/reference wire", async () => {
  for (const reasonCode of [
    "CONTENT_DEFECT",
    "FAIRNESS_ISSUE",
    "GRADING_ISSUE",
  ]) {
    const data = {
      ...source({ edit: false, review: true, publish: false }),
      status: "REVIEW",
      currentSnapshotId: currentId,
    };
    await isolated(async (context) => {
      const { page, state, requests, hold } = context;
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#review-withdraw", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#basic-intro-mode", (node) => node.disabled),
        true,
      );
      await selectSnapshot(context);
      await page.select("#review-changes-reason", reasonCode);
      await page.type("#review-changes-ref", "review_ref_123");
      const post = hold(returnPath);
      await confirm(page, "#review-changes-required");
      await post.ready;
      assert.deepEqual(JSON.parse(lifecyclePosts(requests)[0].body), {
        expectedRev: rev,
        expectedSnapshotId: currentId,
        action: "CHANGES_REQUIRED",
        reasonCode,
        verificationRef: "review_ref_123",
      });
      state.detail = {
        ...data,
        status: "DRAFT",
        currentSnapshotId: null,
        editRev: nextRev,
      };
      await post.respond(
        lifecycleResponse({ status: "DRAFT", currentSnapshotId: null }),
      );
      await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
      assert.equal(
        await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.dataset.state,
        ),
        "confirmed",
      );
      assert.equal(
        requests.filter(({ path }) => path.startsWith(`${historyPath}/`))
          .length,
        0,
      );
    }, data);
  }
});

test("SR07 owner withdraw works in READY without review authority and return never changes manuscript buffers", async () => {
  await isolated(async ({ page, state, requests, hold }) => {
    await edit(page, "basic", "intro", "반환까지 보존할 원고");
    await page.select("#basic-intro-mode", "keep");
    state.detail = {
      ...source(),
      status: "READY",
      currentSnapshotId: currentId,
    };
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval("#review-withdraw", (node) => node.disabled),
      false,
    );
    assert.equal(
      await page.$eval("#review-changes-required", (node) => node.disabled),
      true,
    );
    await page.type("#review-withdraw-ref", "author_ref_123");
    const post = hold(returnPath);
    await confirm(page, "#review-withdraw");
    await post.ready;
    assert.deepEqual(JSON.parse(lifecyclePosts(requests)[0].body), {
      expectedRev: rev,
      expectedSnapshotId: currentId,
      action: "WITHDRAW",
      reasonCode: "AUTHOR_REVISION",
      verificationRef: "author_ref_123",
    });
    state.detail = { ...source(), editRev: nextRev };
    await post.respond(
      lifecycleResponse({ status: "DRAFT", currentSnapshotId: null }),
    );
    await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
    await confirm(page, "#accept-latest");
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "반환까지 보존할 원고",
    );
    assert.equal(
      await page.$eval("#basic-intro-mode", (node) => node.value),
      "keep",
    );
    assert.equal(
      await page.$eval("#review-request", (node) => node.disabled),
      true,
    );
    assert.equal(lifecyclePosts(requests).length, 1);
  });
});

test("SR07 reason and caller reference are required and never synthesized or fetched", async () => {
  const data = {
    ...source({ edit: false, review: true, publish: false }),
    status: "REVIEW",
    currentSnapshotId: currentId,
  };
  await isolated(async ({ page, requests, state }) => {
    await page.click("#review-changes-required");
    assert.equal(await page.$("#ui-confirm-dialog"), null);
    await page.select("#review-changes-reason", "CONTENT_DEFECT");
    for (const value of ["short", "invalid reference", "a".repeat(65)]) {
      await page.$eval(
        "#review-changes-ref",
        (node, text) => {
          node.value = text;
          node.dispatchEvent(new Event("input", { bubbles: true }));
        },
        value,
      );
      await page.click("#review-changes-required");
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    }
    assert.equal(lifecyclePosts(requests).length, 0);
    assert.deepEqual(state.externalRequests, []);
  }, data);
});

test("SR07 unknown outcome only offers current read and comparison and DRAFT cannot fabricate a receipt", async () => {
  const data = { ...source(), status: "REVIEW", currentSnapshotId: currentId };
  await isolated(async ({ page, state, requests, hold }) => {
    await page.type("#review-withdraw-ref", "unknown_return");
    const post = hold(returnPath);
    await confirm(page, "#review-withdraw");
    await post.ready;
    await post.lose();
    await lifecycleMessage(page, "결과 미확인");
    assert.equal(
      await page.$eval("#review-request-replay", (node) => node.hidden),
      true,
    );
    // 동일 현재 사본을 다시 읽어도 불확실 반환을 재전송하지 않는다.
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval("#review-withdraw", (node) => node.disabled),
      true,
    );
    state.detail = { ...source(), editRev: nextRev };
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval(
        "#review-lifecycle-receipt",
        (node) => node.dataset.state,
      ),
      "unknown",
    );
    assert.equal(lifecyclePosts(requests).length, 1);
  }, data);
});

test("permission-only loss survives metadata reset and true reobservation until explicit same-revision baseline acceptance", async () => {
  await isolated(async ({ page, state, requests }) => {
    await edit(page, "basic", "intro", "권한 상실에도 보존");
    await page.select("#basic-intro-mode", "keep");
    state.detail.permissions = { edit: false, review: true, publish: false };
    await page.click("#review-lifecycle-refresh");
    await page.waitForFunction(() =>
      document
        .getElementById("review-permissions")
        .textContent.includes("편집 없음"),
    );
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    state.detail.permissions = { edit: true, review: false, publish: false };
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#accept-latest", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      false,
    );
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval("#basic-save", (node) => node.disabled),
      false,
    );
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "권한 상실에도 보존",
    );
    assert.equal(
      await page.$eval("#basic-intro-mode", (node) => node.value),
      "keep",
    );
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    assert.equal(lifecyclePosts(requests).length, 0);
  });
});

test("malformed current metadata denies old positive authority across resets without adopting a revision", async () => {
  await isolated(async ({ page, state }) => {
    await page.click("#review-lifecycle-refresh");
    await page.waitForFunction(
      () => !document.getElementById("accept-latest").disabled,
    );
    state.detail = { ...source(null), editRev: nextRev };
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "failure");
    assert.match(
      await page.$eval("#review-permissions", (node) => node.textContent),
      /권한 응답 미확인/,
    );
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    assert.equal(
      await page.$eval("#review-request", (node) => node.disabled),
      true,
    );
    state.detail = source();
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    assert.equal(
      await page.$eval("#review-request", (node) => node.disabled),
      true,
    );
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval("#review-request", (node) => node.disabled),
      false,
    );
  });
});

test("pending list replacement and same-revision permission loss cancel lifecycle consent and CSRF-waiting intents", async () => {
  for (const duringCsrf of [false, true]) {
    await isolated(async ({ page, state, requests, hold, queue, json }) => {
      if (duringCsrf) {
        queue(precheckPath, "POST", (request) =>
          json(request, { code: "CSRF_INVALID" }, 403),
        );
        await page.click("#review-precheck");
        await message(page, "요청에 실패");
      }
      await page.click('#editor-nav a[href="#child-heading"]');
      const list = hold(`${root}/persons`, "GET");
      await page.click("#child-list");
      await list.ready;
      const csrf = duringCsrf ? hold("/admin/api/auth/csrf", "GET") : null;
      await page.click("#review-request");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      if (duringCsrf) {
        await page.click("#ui-confirm-accept");
        await csrf.ready;
      }
      state.detail.permissions = { edit: false, review: false, publish: false };
      await list.respond({
        editRev: nextRev,
        items: [],
        hasNext: false,
        nextAfterKey: null,
      });
      await page.waitForFunction(() =>
        document
          .getElementById("review-permissions")
          .textContent.includes("편집 없음"),
      );
      if (duringCsrf)
        await csrf.respond({
          headerName: "X-CSRF-TOKEN",
          token: "new-synthetic",
        });
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      assert.equal(lifecyclePosts(requests).length, 0);
      assert.equal(
        await page.$eval("#review-request-replay", (node) => node.hidden),
        true,
      );
    });
  }
});

test("lifecycle CSRF exceptions retain original request and return fields while ordinary FORBIDDEN still purges", async () => {
  for (const action of ["REQUEST", "WITHDRAW"]) {
    const data =
      action === "REQUEST"
        ? source()
        : { ...source(), status: "REVIEW", currentSnapshotId: currentId };
    await isolated(async ({ page, requests, queue, json }) => {
      const path = action === "REQUEST" ? requestPath : returnPath;
      const selector =
        action === "REQUEST" ? "#review-request" : "#review-withdraw";
      if (action === "WITHDRAW")
        await page.type("#review-withdraw-ref", "csrf_reference");
      queue(path, "POST", (request) =>
        json(request, { code: "CSRF_INVALID" }, 403),
      );
      await confirm(page, selector);
      await lifecycleMessage(page, "CSRF 확인 거절");
      assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
      assert.equal(lifecyclePosts(requests).length, 1);
      if (action === "WITHDRAW")
        assert.equal(
          await page.$eval("#review-withdraw-ref", (node) => node.value),
          "csrf_reference",
        );
      else {
        const original = lifecyclePosts(requests)[0].body;
        queue(path, "POST", (request) =>
          json(request, { code: "FORBIDDEN" }, 403),
        );
        await confirm(page, "#review-request-replay");
        await page.waitForSelector("#editor[hidden]");
        assert.equal(lifecyclePosts(requests)[1].body, original);
        assert.equal(
          await page.$eval("#editor", (node) => node.childElementCount),
          0,
        );
      }
    }, data);
  }
});

test("lifecycle current 401 ordinary403 and404 purge pending receipts fields and late callback ownership", async () => {
  for (const code of [401, 403, 404]) {
    await isolated(async ({ page, requests, hold, queue, json }) => {
      await page.click('#editor-nav a[href="#child-heading"]');
      const list = hold(`${root}/persons`, "GET");
      await page.click("#child-list");
      await list.ready;
      const post = hold(requestPath);
      await confirm(page, "#review-request");
      await post.ready;
      await list.respond({ code: "ACCESS_LOST" }, code);
      await page.waitForSelector("#editor[hidden]");
      await post.respond(lifecycleResponse(), 201);
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(lifecyclePosts(requests).length, 1);
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    });
  }
});

test("lifecycle Cancel Escape keyboard focus and shared layout retain real visible controls at five widths", async () => {
  await isolated(async ({ page, requests }) => {
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      await page.focus("#review-request");
      await page.keyboard.press("Enter");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-accept",
      );
      await page.keyboard.press("Escape");
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "review-request",
      );
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      assert.ok(
        await page.$eval(
          "#review-request",
          (node) => node.getBoundingClientRect().height >= 48,
        ),
      );
      // 즉시 포커스·배치를 검사한 뒤 다음 키보드 실행 전에 취소 작업 해제를 기다린다.
      await page.waitForFunction(
        () => !document.getElementById("review-lifecycle-refresh").disabled,
      );
    }
    assert.equal(lifecyclePosts(requests).length, 0);
  });
});

test("DRAFT reviewer-only and publish-only grants never authorize edit actions while owner edit does", async () => {
  for (const permissions of [
    { edit: true, review: false, publish: false },
    { edit: false, review: true, publish: false },
    { edit: false, review: false, publish: true },
    { edit: false, review: false, publish: false },
  ]) {
    await isolated(async ({ page, requests }) => {
      for (const id of [
        "basic-save",
        "review-precheck",
        "review-samples-check",
        "review-request",
      ])
        assert.equal(
          await page.$eval(`#${id}`, (node) => node.disabled),
          !permissions.edit,
          id,
        );
      assert.equal(
        await page.$eval("#saved-history-first", (node) => node.disabled),
        false,
      );
      assert.equal(
        await page.$eval("#review-changes-required", (node) => node.disabled),
        true,
      );
      assert.equal(lifecyclePosts(requests).length, 0);
    }, source(permissions));
  }
});

test("SR02 rejects all manuscript child and hidden keep buffers before opening consent", async () => {
  await isolated(async ({ page, requests }) => {
    for (const [section, key] of [
      ["basic", "intro"],
      ["answer", "methodAnswer"],
      ["reveal", "revealText"],
    ]) {
      const original = await page.$eval(
        `#${section}-${key}`,
        (node) => node.value,
      );
      await edit(page, section, key, "검수 전 미저장");
      for (const mode of ["value", "clear", "keep"]) {
        await page.select(`#${section}-${key}-mode`, mode);
        assert.equal(
          await page.$eval("#review-request", (node) => node.disabled),
          true,
        );
        // 비활성 버튼의 음성 경계 검사이며 긍정 클릭 증거가 아니다.
        await page.$eval("#review-request", (node) =>
          node.dispatchEvent(new Event("click")),
        );
        assert.equal(await page.$("#ui-confirm-dialog"), null);
      }
      await edit(page, section, key, original);
      await page.select(`#${section}-${key}-mode`, "keep");
    }
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.click("#child-new");
    await edit(page, "child", "name", "새 자료 미저장");
    await page.select("#child-name-mode", "keep");
    assert.equal(
      await page.$eval("#review-request", (node) => node.disabled),
      true,
    );
    assert.equal(lifecyclePosts(requests).length, 0);
  });
});

test("SR07 confirmed receipt survives refresh failure or edit-permission loss without becoming unknown", async () => {
  for (const refreshFails of [false, true]) {
    const data = {
      ...source(),
      status: "REVIEW",
      currentSnapshotId: currentId,
    };
    await isolated(async ({ page, state, requests, hold, queue, json }) => {
      await page.type("#review-withdraw-ref", "confirmed_return");
      const post = hold(returnPath);
      if (refreshFails)
        queue(root, "GET", (request) =>
          json(request, { code: "STORY_UNAVAILABLE" }, 503),
        );
      else
        state.detail = {
          ...source({ edit: false, review: true, publish: false }),
          status: "DRAFT",
          currentSnapshotId: null,
          editRev: nextRev,
        };
      await confirm(page, "#review-withdraw");
      await post.ready;
      await post.respond(
        lifecycleResponse({ status: "DRAFT", currentSnapshotId: null }),
      );
      await lifecycleMessage(
        page,
        refreshFails ? "영수증은 확정됐으나" : "영수증은 확정됐습니다",
      );
      assert.equal(
        await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.dataset.state,
        ),
        "confirmed",
      );
      assert.equal(
        await page.$eval("#review-withdraw-ref", (node) => node.value),
        "confirmed_return",
      );
      assert.equal(
        await page.$eval("#review-request-replay", (node) => node.hidden),
        true,
      );
      assert.equal(
        await page.$eval("#basic-save", (node) => node.disabled),
        true,
      );
      assert.equal(lifecyclePosts(requests).length, 1);
    }, data);
  }
});

test("SR02 invalid full JSON is unknown and late successful JSON cannot cross a replacement generation", async () => {
  for (const replacement of [false, true]) {
    await isolated(async ({ page, requests, hold }) => {
      await page.click('#editor-nav a[href="#child-heading"]');
      const list = hold(`${root}/persons`, "GET");
      await page.click("#child-list");
      await list.ready;
      const post = hold(requestPath);
      await confirm(page, "#review-request");
      await post.ready;
      if (replacement) {
        await list.respond({
          editRev: nextRev,
          items: [],
          hasNext: false,
          nextAfterKey: null,
        });
        await page.waitForFunction(
          () => !document.getElementById("accept-latest").disabled,
        );
        await post.respond(lifecycleResponse(), 201);
        await page.evaluate(
          () =>
            new Promise((resolve) =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
        );
      } else {
        await post.respondRaw('{"snapshotId":', 201);
        await lifecycleMessage(page, "결과 미확인");
        await list.respond({
          editRev: rev,
          items: [],
          hasNext: false,
          nextAfterKey: null,
        });
      }
      assert.equal(
        await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.dataset.state,
        ),
        "unknown",
      );
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        true,
      );
      assert.equal(lifecyclePosts(requests).length, 1);
    });
  }
});

test("SR07 reference edits during CSRF acquisition invalidate consent without a second POST", async () => {
  const data = { ...source(), status: "REVIEW", currentSnapshotId: currentId };
  await isolated(async ({ page, requests, hold, queue, json }) => {
    await page.type("#review-withdraw-ref", "original_reference");
    queue(returnPath, "POST", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await confirm(page, "#review-withdraw");
    await lifecycleMessage(page, "CSRF 확인 거절");
    await acceptLifecycle(page);
    const csrf = hold("/admin/api/auth/csrf", "GET");
    await confirm(page, "#review-withdraw");
    await csrf.ready;
    await page.type("#review-withdraw-ref", "_changed");
    await csrf.respond({
      headerName: "X-CSRF-TOKEN",
      token: "synthetic-ref-token",
    });
    await lifecycleMessage(page, "전송하지 않았습니다");
    assert.equal(lifecyclePosts(requests).length, 1);
    assert.equal(
      await page.$eval("#review-withdraw-ref", (node) => node.value),
      "original_reference_changed",
    );
    assert.equal(
      await page.$eval(
        "#review-lifecycle-receipt",
        (node) => node.dataset.state,
      ),
      "rejected",
    );
  }, data);
});

/**
 * 보이는 입력을 클릭하고 방향키·Shift로 원래 값 전체를 선택해 비운 뒤 키보드로 교체한다.
 * @param {object} page 실제 키보드 입력을 지원하는 격리된 Puppeteer 제품 페이지.
 * @param {string} selector 길이가 제한된 일반 ASCII 참조 값을 가진 표시된 텍스트 입력 선택자.
 * @param {string} value 키보드로 입력할 일반 ASCII 참조 값. 빈 문자열이면 비우기만 한다.
 * @throws {Error} 입력 표시 대기·클릭·키보드 조작이 실패하거나 비운 값·최종 값 검증이 실패한다.
 */
async function replaceVisibleInput(page, selector, value) {
  await page.waitForSelector(selector, { visible: true });
  await page.click(selector);
  const originalLength = await page.$eval(
    selector,
    (node) => node.value.length,
  );
  for (let index = 0; index < originalLength; index++) {
    await page.keyboard.press("ArrowLeft");
  }

  await page.keyboard.down("Shift");
  try {
    for (let index = 0; index < originalLength; index++) {
      await page.keyboard.press("ArrowRight");
    }
  } finally {
    await page.keyboard.up("Shift");
  }
  await page.keyboard.press("Backspace");
  assert.equal(
    await page.$eval(selector, (node) => node.value),
    "",
    `${selector} 전체 비우기`,
  );
  if (value) await page.type(selector, value);
  assert.equal(
    await page.$eval(selector, (node) => node.value),
    value,
    `${selector} 입력값 일치`,
  );
}

/**
 * 제품의 기존 native beforeunload 리스너가 합성 취소 가능 이벤트를 막는지 관측한다.
 * 실제 탐색·브라우저 경고창 표시 증거가 아닌 음성 이탈 술어 검사다.
 * @param {object} page 제품 리스너를 변경하지 않은 격리 페이지.
 * @returns {Promise<boolean>} 실제 리스너의 preventDefault/returnValue 처리 결과.
 */
async function leavePrevented(page) {
  return page.evaluate(() => {
    const event = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  });
}

test("H3 identity establishes explicit Me without private prefetch or global capability inference", async () => {
  for (const editAllowed of [true, false]) {
    await isolated(
      async ({ page, state, requests, identityRequests, queue, json }) => {
        assert.equal(
          await page.$eval("#review-request", (node) => node.disabled),
          !editAllowed,
        );
        assert.equal(
          savedRequests(requests).length,
          0,
          "신원 관측은 비공개 사본 prefetch가 아님",
        );
        assert.equal(requests.filter(({ path }) => path === root).length, 1);
        assert.ok(
          identityRequests.length > 0,
          "편집기 최초 소유권에 명시적 GET Me가 필요",
        );
        assert.ok(
          identityRequests.every(
            ({ path, method, body }) =>
              path === mePath && method === "GET" && body === undefined,
          ),
        );
        assert.notEqual(state.me.accountKey, state.detail.ownerAccountKey);
        assert.deepEqual(
          state.me.permissions,
          [],
          "전역 권한이 없어도 사건 edit는 독립",
        );
        state.me.permissions = ["MANAGE", "CREATE", "REVIEW", "PUBLISH"];
        queue(historyPath, "GET", (request) =>
          json(request, { items: [], hasNext: false, nextAfterId: null }),
        );
        await page.click("#saved-history-first");
        await savedState(page, "history", "success-empty");
        assert.equal(
          await page.$eval("#review-request", (node) => node.disabled),
          !editAllowed,
        );
        assert.equal(
          await page.$eval("#review-changes-required", (node) => node.disabled),
          true,
        );
        assert.equal(lifecyclePosts(requests).length, 0);
      },
      source({ edit: editAllowed, review: false, publish: false }),
    );
  }
});

test("H3 identity same permissions account or absolute anchor change during consent cancels the old intent", async () => {
  for (const replacement of [
    { accountKey: "22222222-2222-4222-8222-222222222222" },
    { absoluteExpiresAt: "2026-10-04T10:00:01Z" },
  ]) {
    await isolated(async ({ page, state, requests, queue, json }) => {
      queue(requestPath, "POST", (request) =>
        json(request, lifecycleResponse(), 201),
      );
      await page.click("#review-request");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      const permissions = { ...state.detail.permissions };
      state.me = identityResponse(replacement);
      await page.click("#ui-confirm-accept");
      await page.waitForFunction(
        () =>
          document.getElementById("editor").hidden ||
          document.getElementById("review-lifecycle-receipt")?.dataset.state ===
            "confirmed",
      );
      assert.deepEqual(
        state.detail.permissions,
        permissions,
        "사건 권한 변화가 아닌 신원 변화",
      );
      assert.equal(
        lifecyclePosts(requests).length,
        0,
        "다른 신원으로 옛 동의를 전송하지 않음",
      );
      assert.equal(await page.$eval("#editor", (node) => node.hidden), true);
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    });
  }
});

test("H3 identity same permissions account or absolute anchor change during CSRF blocks mutation send", async () => {
  for (const replacement of [
    { accountKey: "22222222-2222-4222-8222-222222222222" },
    { absoluteExpiresAt: "2026-10-04T10:00:01Z" },
  ]) {
    await isolated(async ({ page, state, requests, hold, queue, json }) => {
      // 기존 자격을 거절시켜 다음 명시 전환의 실제 CSRF 획득 경계를 사용한다.
      queue(precheckPath, "POST", (request) =>
        json(request, { code: "CSRF_INVALID" }, 403),
      );
      await page.click("#review-precheck");
      await message(page, "요청에 실패");
      const csrf = hold("/admin/api/auth/csrf", "GET");
      queue(requestPath, "POST", (request) =>
        json(request, lifecycleResponse(), 201),
      );
      await confirm(page, "#review-request");
      await csrf.ready;
      state.me = identityResponse(replacement);
      await csrf.respond({
        headerName: "X-CSRF-TOKEN",
        token: "synthetic-other-identity-csrf",
      });
      await page.waitForFunction(
        () =>
          document.getElementById("editor").hidden ||
          document.getElementById("review-lifecycle-receipt")?.dataset.state ===
            "confirmed",
      );
      assert.equal(
        lifecyclePosts(requests).length,
        0,
        "새 CSRF가 옛 신원 동의를 유효하게 만들지 않음",
      );
      assert.equal(
        lanePosts(requests).length,
        1,
        "자격 비우기용 명시 SR01 이외 전송 없음",
      );
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$eval("#editor", (node) => node.hidden), true);
    });
  }
});

test("H3 identity late SR02 and SR07 receipts cannot restore prior actor buffers or private DOM", async () => {
  for (const action of ["REQUEST", "WITHDRAW"]) {
    const data =
      action === "REQUEST"
        ? source()
        : { ...source(), status: "REVIEW", currentSnapshotId: currentId };
    await isolated(async ({ page, state, requests, hold }) => {
      if (action === "WITHDRAW")
        await page.type("#review-withdraw-ref", "prior_actor_reference");
      const post = hold(action === "REQUEST" ? requestPath : returnPath);
      await confirm(
        page,
        action === "REQUEST" ? "#review-request" : "#review-withdraw",
      );
      await post.ready;
      if (action === "REQUEST") {
        await edit(page, "basic", "intro", "PRIOR_ACTOR_KEEP_BUFFER");
        await page.select("#basic-intro-mode", "keep");
        await page.type("#review-withdraw-ref", "prior_actor_reference");
      }
      state.me = identityResponse({
        accountKey: "22222222-2222-4222-8222-222222222222",
      });
      state.detail =
        action === "REQUEST"
          ? {
              ...source(),
              editRev: nextRev,
              status: "REVIEW",
              currentSnapshotId: currentId,
            }
          : { ...source(), editRev: nextRev };
      await post.respond(
        lifecycleResponse(
          action === "REQUEST"
            ? {}
            : { status: "DRAFT", currentSnapshotId: null },
        ),
        action === "REQUEST" ? 201 : 200,
      );
      await page.waitForSelector("#editor[hidden]");
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$("#review-lifecycle-receipt"), null);
      assert.equal(await page.$("#review-request-replay"), null);
      assert.equal(await page.$("#ui-confirm-dialog"), null);
      assert.equal(
        lifecyclePosts(requests).length,
        1,
        "이미 전송된 한 건을 새 신원으로 재전송하지 않음",
      );
    }, data);
  }
});

test("H3 identity late metadata payload records and preview cannot adopt across an actor switch", async () => {
  for (const lane of ["metadata", "payload", "records", "preview"]) {
    await isolated(async (context) => {
      const { page, state, requests, hold, queue, raw } = context;
      await edit(page, "answer", "methodAnswer", "PRIOR_PRIVATE_KEEP_BUFFER");
      await page.select("#answer-methodAnswer-mode", "keep");
      await page.type("#review-changes-ref", "prior_private_reference");
      await selectSnapshot(context);
      queue(`${historyPath}/${savedId}`, "GET", (request) =>
        raw(request, savedPayloadText()),
      );
      await page.click("#saved-payload-read");
      await savedState(page, "payload", "success-populated");
      assert.match(
        await page.$eval("#saved-payload-result", (node) => node.textContent),
        /PRIVATE_ANSWER/,
      );
      if (lane === "preview")
        await page.select("#saved-preview-mode", "REVEAL");
      if (lane === "metadata") {
        // 실제 현재 GET·비교로 쓰기가 잠긴 뒤 공개되는 열람 복구 버튼을 사용한다.
        await page.click("#review-lifecycle-refresh");
        await page.waitForFunction(
          () => !document.getElementById("accept-latest").disabled,
        );
        await page.waitForSelector("#saved-metadata-refresh", {
          visible: true,
        });
      }
      const path = {
        metadata: root,
        payload: `${historyPath}/${savedId}`,
        records: `${historyPath}/${savedId}/records`,
        preview: previewPath,
      }[lane];
      const pending = hold(path, "GET");
      await page.click(
        lane === "metadata"
          ? "#saved-metadata-refresh"
          : lane === "records"
            ? "#saved-records-first"
            : `#saved-${lane}-read`,
      );
      await pending.ready;
      state.me = identityResponse({
        accountKey: "22222222-2222-4222-8222-222222222222",
      });
      if (lane === "metadata") await pending.respond(state.detail);
      else if (lane === "payload") await pending.respondRaw(savedPayloadText());
      else if (lane === "records") await pending.respondRaw(savedRecordsText());
      else
        await pending.respond(
          previewResponse({ mode: "REVEAL", roleCode: null }),
        );
      await page.waitForSelector("#editor[hidden]");
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$("#saved-payload-result"), null);
      assert.equal(await page.$("#answer-methodAnswer"), null);
      assert.equal(await page.$("#review-changes-ref"), null);
      assert.equal(lifecyclePosts(requests).length, 0);
      assert.deepEqual(state.externalRequests, []);
    });
  }
});

test("H3 identity malformed or failed Me is fail closed at a protected read boundary", async () => {
  for (const [body, status] of [
    [null, 200],
    [[], 200],
    [identityResponse({ accountKey: null }), 200],
    [identityResponse({ accountKey: "not-a-uuid" }), 200],
    [identityResponse({ absoluteExpiresAt: null }), 200],
    [identityResponse({ absoluteExpiresAt: "not-an-instant" }), 200],
    [{ permissions: [], idleExpiresAt: "2026-10-04T03:00:00Z" }, 200],
    [{ code: "AUTH_REQUIRED" }, 401],
    [{ code: "AUTH_UNAVAILABLE" }, 503],
  ]) {
    await isolated(async ({ page, state, requests }) => {
      await edit(page, "basic", "intro", "UNVERIFIED_IDENTITY_BUFFER");
      await page.select("#basic-intro-mode", "keep");
      state.me = body;
      state.meStatus = status;
      // 신선한 상세에서는 복구 버튼이 숨겨져 있으므로 보이는 현재 GET을 사용한다.
      await page.click("#review-lifecycle-refresh");
      await page.waitForSelector("#editor[hidden]");
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(lifecyclePosts(requests).length, 0);
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    });
  }
});

test("H3 identity rolling idle and reauth changes preserve same-context CSRF recovery and original body", async () => {
  await isolated(
    async ({ page, state, requests, identityRequests, queue, json, hold }) => {
      const before = identityRequests.length;
      queue(requestPath, "POST", (request) =>
        json(request, { code: "CSRF_INVALID" }, 403),
      );
      await confirm(page, "#review-request");
      await lifecycleMessage(page, "CSRF 확인 거절");
      const original = lifecyclePosts(requests)[0].body;
      await edit(page, "basic", "intro", "ROLLING_IDENTITY_KEEP_BUFFER");
      await page.select("#basic-intro-mode", "keep");
      state.me = identityResponse({
        idleExpiresAt: "2026-10-04T03:10:00Z",
        reauthExpiresAt: "2026-10-04T02:45:00Z",
      });
      await page.click("#saved-metadata-refresh");
      await savedState(page, "metadata", "success");
      assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
      assert.equal(
        await page.$eval("#basic-intro", (node) => node.value),
        "ROLLING_IDENTITY_KEEP_BUFFER",
      );
      assert.equal(
        await page.$eval("#basic-intro-mode", (node) => node.value),
        "keep",
      );
      assert.equal(
        await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.dataset.state,
        ),
        "rejected",
      );
      assert.equal(
        lifecyclePosts(requests).length,
        1,
        "rolling 기한 갱신은 자동 재시도가 아님",
      );
      await edit(page, "basic", "intro", "저장 도입");
      await page.select("#basic-intro-mode", "keep");
      const post = hold(requestPath);
      await confirm(page, "#review-request-replay");
      await post.ready;
      assert.equal(lifecyclePosts(requests).length, 2);
      assert.equal(lifecyclePosts(requests)[1].body, original);
      state.detail = {
        ...source(),
        editRev: nextRev,
        status: "REVIEW",
        currentSnapshotId: currentId,
      };
      await post.respond(lifecycleResponse(), 201);
      await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
      assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
      assert.equal(
        await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.dataset.state,
        ),
        "confirmed",
      );
      assert.ok(
        identityRequests.length > before,
        "같은 계정·절대 기한을 실제 Me 경계에서 재관측",
      );
      assert.equal(
        state.me.absoluteExpiresAt,
        identityResponse().absoluteExpiresAt,
      );
      assert.equal(state.me.accountKey, identityResponse().accountKey);
    },
  );
});

test("H3 rejection explicit reconcile correction save and new SR02 use a fresh key and revision exactly once", async () => {
  for (const [code, status] of [
    ["REVIEW_NOT_READY", 422],
    ["INVALID_REQUEST", 400],
    ["SNAPSHOT_TOO_LARGE", 413],
    ["REQUEST_KEY_CONFLICT", 409],
    ["EDIT_CONFLICT", 409],
  ]) {
    await isolated(async ({ page, state, requests, queue, json, hold }) => {
      queue(requestPath, "POST", (request) => json(request, { code }, status));
      await confirm(page, "#review-request");
      await page.waitForFunction(
        () =>
          document.getElementById("review-lifecycle-receipt").dataset.state ===
          "rejected",
      );
      const original = JSON.parse(lifecyclePosts(requests)[0].body);
      assert.equal(original.expectedRev, rev);
      assert.equal(lifecyclePosts(requests).length, 1);
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#review-request-replay", (node) => node.hidden),
        true,
        "확정 거절 본문은 재전송 복구 경로가 아님",
      );
      await page.click("#saved-metadata-refresh");
      await savedState(page, "metadata", "success");
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        true,
        "열람 메타 GET만으로 거절 의도를 종료하지 않음",
      );
      await acceptLifecycle(page);
      assert.equal(
        lifecyclePosts(requests).length,
        1,
        "명시 비교 수락도 새 전환을 보내지 않음",
      );
      await edit(page, "basic", "intro", "거절 뒤 실제 수정 저장");
      queue(`${root}/sections/basic`, "PATCH", (request) => {
        state.detail = source();
        state.detail.editRev = nextRev;
        state.detail.sections.basic.intro = "거절 뒤 실제 수정 저장";
        return json(request, { editRev: nextRev, changed: true });
      });
      await page.click("#basic-save");
      await page.waitForFunction(() =>
        document
          .getElementById("notice")
          .textContent.includes("저장을 확인하고"),
      );
      const patches = requests.filter(({ method }) => method === "PATCH");
      assert.equal(patches.length, 1);
      assert.equal(patches[0].path, `${root}/sections/basic`);
      assert.deepEqual(JSON.parse(patches[0].body), {
        expectedRev: rev,
        changes: { intro: "거절 뒤 실제 수정 저장" },
      });
      assert.equal(
        lifecyclePosts(requests).length,
        1,
        "수정 저장도 SR02를 자동 생성하지 않음",
      );
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        false,
        "확정 거절을 비교·수정·저장한 뒤 새 요청이 영구 차단되면 안 됨",
      );
      const post = hold(requestPath);
      await confirm(page, "#review-request");
      await post.ready;
      const sent = lifecyclePosts(requests);
      assert.equal(sent.length, 2);
      const fresh = JSON.parse(sent[1].body);
      assert.deepEqual(Object.keys(fresh).sort(), [
        "expectedRev",
        "requestKey",
      ]);
      assert.equal(fresh.expectedRev, nextRev);
      assert.notEqual(fresh.requestKey, original.requestKey);
      assert.match(
        fresh.requestKey,
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
      state.detail = {
        ...state.detail,
        editRev: "9007199254740993125",
        status: "REVIEW",
        currentSnapshotId: currentId,
      };
      await post.respond(
        lifecycleResponse({
          sourceRev: nextRev,
          editRev: state.detail.editRev,
        }),
        201,
      );
      await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
      await confirm(page, "#accept-latest");
      assert.equal(
        lifecyclePosts(requests).length,
        2,
        "거절 1회·새 의도 1회만 전송",
      );
      assert.equal(lanePosts(requests).length, 0);
      assert.equal(
        requests.filter(({ method }) => method === "PATCH").length,
        1,
      );
    });
  }
});

test("H3 rejection unknown SR02 remains frozen through reconcile correction and save instead of minting a new intent", async () => {
  for (const failure of [
    "transport",
    "malformed-success",
    "malformed-rejection",
  ]) {
    await isolated(async ({ page, state, requests, queue, json, hold }) => {
      const first = hold(requestPath);
      await confirm(page, "#review-request");
      await first.ready;
      const original = lifecyclePosts(requests)[0].body;
      if (failure === "transport") await first.lose();
      else
        await first.respondRaw(
          '{"code":',
          failure === "malformed-success" ? 201 : 422,
        );
      await lifecycleMessage(page, "결과 미확인");
      await page.click("#saved-metadata-refresh");
      await savedState(page, "metadata", "success");
      await acceptLifecycle(page);
      assert.equal(lifecyclePosts(requests).length, 1);
      assert.equal(
        await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.dataset.state,
        ),
        "unknown",
      );
      await edit(page, "basic", "intro", "미확인 뒤 별도 수정");
      queue(`${root}/sections/basic`, "PATCH", (request) => {
        state.detail = source();
        state.detail.editRev = nextRev;
        state.detail.sections.basic.intro = "미확인 뒤 별도 수정";
        return json(request, { editRev: nextRev, changed: true });
      });
      await page.click("#basic-save");
      await page.waitForFunction(() =>
        document
          .getElementById("notice")
          .textContent.includes("저장을 확인하고"),
      );
      assert.equal(
        requests.filter(({ method }) => method === "PATCH").length,
        1,
      );
      assert.equal(lifecyclePosts(requests).length, 1);
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#review-request-replay", (node) => node.hidden),
        false,
      );
      const replay = hold(requestPath);
      await confirm(page, "#review-request-replay");
      await replay.ready;
      assert.equal(lifecyclePosts(requests).length, 2);
      assert.equal(
        lifecyclePosts(requests)[1].body,
        original,
        "새 수정번호를 옛 키 아래 섞지 않음",
      );
      await replay.respond(
        lifecycleResponse({
          snapshotId: savedId,
          status: "DRAFT",
          currentSnapshotId: null,
          replayed: true,
        }),
      );
      await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
      assert.equal(lifecyclePosts(requests).length, 2);
      assert.equal(lanePosts(requests).length, 0);
    });
  }
});

test("H3 rejection replay preserves prior unknown intent but terminates a definitive same-identity CSRF recovery rejection", async () => {
  for (const unknown of [false, true]) {
    await isolated(async ({ page, requests, hold }) => {
      const first = hold(requestPath);
      await confirm(page, "#review-request");
      await first.ready;
      const original = lifecyclePosts(requests)[0].body;
      if (unknown) await first.lose();
      else await first.respond({ code: "CSRF_INVALID" }, 403);
      await lifecycleMessage(page, unknown ? "결과 미확인" : "CSRF 확인 거절");
      const replay = hold(requestPath);
      await confirm(page, "#review-request-replay");
      await replay.ready;
      assert.equal(lifecyclePosts(requests)[1].body, original);
      await replay.respond({ code: "REVIEW_NOT_READY" }, 422);
      await page.waitForFunction(
        () =>
          document.getElementById("review-lifecycle-receipt").dataset.state ===
          "rejected",
      );
      await acceptLifecycle(page);
      assert.equal(lifecyclePosts(requests).length, 2);
      assert.equal(
        await page.$eval("#review-request", (node) => node.disabled),
        unknown,
        "재확인 거절만으로 앞선 전송 유실을 종료하거나 CSRF 확정 거절을 영구 잠그지 않음",
      );
      assert.equal(
        await page.$eval("#review-request-replay", (node) => node.hidden),
        !unknown,
      );
      assert.equal(
        await leavePrevented(page),
        unknown,
        "현재 GET·수락 뒤에도 실제 미확정 전송만 이탈 보호에 남음",
      );
    });
  }
});

test("H3 beforeunload synthetic predicate covers dirty return reason and refs with explicit clearing", async () => {
  const data = {
    ...source({ edit: true, review: true, publish: false }),
    status: "REVIEW",
    currentSnapshotId: currentId,
  };
  await isolated(async ({ page, requests }) => {
    assert.equal(await leavePrevented(page), false, "깨끗한 최초 화면");
    await page.select("#review-changes-reason", "CONTENT_DEFECT");
    assert.equal(
      await leavePrevented(page),
      true,
      "참조 없이 사유만 바꿔도 미저장 반환 입력",
    );
    await page.select("#review-changes-reason", "");
    assert.equal(await leavePrevented(page), false, "최초 값으로 명시 복원");
    for (const selector of ["#review-withdraw-ref", "#review-changes-ref"]) {
      await replaceVisibleInput(page, selector, "dirty_reference");
      assert.equal(await leavePrevented(page), true, selector);
      await replaceVisibleInput(page, selector, "");
      assert.equal(await leavePrevented(page), false, selector);
    }
    assert.equal(lifecyclePosts(requests).length, 0);
  }, data);
});

test("H3 beforeunload synthetic predicate blocks inflight SR02 then clears after confirmed clean acceptance", async () => {
  await isolated(async ({ page, state, requests, hold }) => {
    assert.equal(await leavePrevented(page), false);
    const post = hold(requestPath);
    await confirm(page, "#review-request");
    await post.ready;
    assert.equal(
      await leavePrevented(page),
      true,
      "원고 수정 없이 전환 전송 중",
    );
    state.detail = {
      ...source(),
      editRev: nextRev,
      status: "REVIEW",
      currentSnapshotId: currentId,
    };
    await post.respond(lifecycleResponse(), 201);
    await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
    await confirm(page, "#accept-latest");
    await page.waitForFunction(
      () => document.getElementById("comparison").hidden,
    );
    assert.equal(
      await leavePrevented(page),
      false,
      "확정 영수증·비교 수락 뒤 깨끗한 상태",
    );
    assert.equal(lifecyclePosts(requests).length, 1);
  });
});

test("H3 beforeunload synthetic predicate retains unknown SR02 across metadata and comparison until receipt", async () => {
  await isolated(async ({ page, requests, hold }) => {
    const post = hold(requestPath);
    await confirm(page, "#review-request");
    await post.ready;
    const original = lifecyclePosts(requests)[0].body;
    await post.lose();
    await lifecycleMessage(page, "결과 미확인");
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    await acceptLifecycle(page);
    assert.equal(
      await leavePrevented(page),
      true,
      "같은 DRAFT 조회·수락은 미확인 의도의 영수증이 아님",
    );
    assert.equal(lifecyclePosts(requests).length, 1);
    const replay = hold(requestPath);
    await confirm(page, "#review-request-replay");
    await replay.ready;
    assert.equal(lifecyclePosts(requests)[1].body, original);
    await replay.respond(
      lifecycleResponse({
        status: "DRAFT",
        currentSnapshotId: null,
        editRev: rev,
        replayed: true,
      }),
    );
    await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
    await confirm(page, "#accept-latest");
    await page.waitForFunction(
      () => document.getElementById("comparison").hidden,
    );
    assert.equal(await leavePrevented(page), false);
    assert.equal(lifecyclePosts(requests).length, 2);
  });
});

test("H3 beforeunload synthetic predicate retains unknown SR07 after clearing fields and observing DRAFT", async () => {
  const data = { ...source(), status: "REVIEW", currentSnapshotId: currentId };
  await isolated(async ({ page, state, requests, hold }) => {
    await replaceVisibleInput(
      page,
      "#review-withdraw-ref",
      "unknown_return_ref",
    );
    const post = hold(returnPath);
    await confirm(page, "#review-withdraw");
    await post.ready;
    await post.lose();
    await lifecycleMessage(page, "결과 미확인");
    await replaceVisibleInput(page, "#review-withdraw-ref", "");
    state.detail = { ...source(), editRev: nextRev };
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval(
        "#review-lifecycle-receipt",
        (node) => node.dataset.state,
      ),
      "unknown",
    );
    assert.equal(
      await leavePrevented(page),
      true,
      "반환 입력을 비워도 미확인 SR07 자체는 남음",
    );
    assert.equal(
      await page.$eval("#review-request-replay", (node) => node.hidden),
      true,
    );
    assert.equal(
      await page.$eval("#review-request", (node) => node.disabled),
      true,
      "미확정 반환을 새 검수 회차로 덮어쓰지 않음",
    );
    assert.equal(
      lifecyclePosts(requests).length,
      1,
      "SR07 재전송·새 키 생성 없음",
    );
  }, data);
});

test("H3 beforeunload synthetic predicate acknowledges confirmed SR07 fields but guards later edits", async () => {
  const data = {
    ...source({ edit: false, review: true, publish: false }),
    status: "REVIEW",
    currentSnapshotId: currentId,
  };
  await isolated(async ({ page, state, requests, hold }) => {
    await page.select("#review-changes-reason", "FAIRNESS_ISSUE");
    await replaceVisibleInput(
      page,
      "#review-changes-ref",
      "confirmed_return_ref",
    );
    const post = hold(returnPath);
    await confirm(page, "#review-changes-required");
    await post.ready;
    state.detail = {
      ...data,
      editRev: nextRev,
      status: "DRAFT",
      currentSnapshotId: null,
    };
    await post.respond(
      lifecycleResponse({ status: "DRAFT", currentSnapshotId: null }),
    );
    await lifecycleMessage(page, "전환 영수증은 확정됐습니다");
    await confirm(page, "#accept-latest");
    await page.waitForFunction(
      () => document.getElementById("comparison").hidden,
    );
    assert.equal(
      await page.$eval("#review-changes-ref", (node) => node.value),
      "confirmed_return_ref",
    );
    assert.equal(
      await leavePrevented(page),
      false,
      "확정·수락된 반환 입력 때문에 영구 경고하지 않음",
    );
    await replaceVisibleInput(page, "#review-changes-ref", "later_return_ref");
    assert.equal(
      await leavePrevented(page),
      true,
      "영수증 이후의 새 입력은 별도 미저장",
    );
    await replaceVisibleInput(
      page,
      "#review-changes-ref",
      "confirmed_return_ref",
    );
    assert.equal(await leavePrevented(page), false);
    assert.equal(lifecyclePosts(requests).length, 1);
  }, data);
});

test("H3 beforeunload synthetic predicate preserves manuscript and hidden keep buffer protection", async () => {
  await isolated(async ({ page, requests }) => {
    assert.equal(await leavePrevented(page), false);
    await edit(page, "basic", "intro", "기존 원고 이탈 보호");
    assert.equal(await leavePrevented(page), true);
    await page.select("#basic-intro-mode", "keep");
    assert.equal(
      await leavePrevented(page),
      true,
      "숨은 keep 입력도 기존처럼 보호",
    );
    await edit(page, "basic", "intro", "저장 도입");
    await page.select("#basic-intro-mode", "keep");
    assert.equal(await leavePrevented(page), false);
    assert.equal(lifecyclePosts(requests).length, 0);
    assert.equal(requests.filter(({ method }) => method === "PATCH").length, 0);
  });
});

test("H3 child consent resource-kind change preserves edits made during the second Me await", async () => {
  for (const variant of ["unchanged", "value", "keep"]) {
    await isolated(
      async ({ page, state, requests, identityRequests, hold }) => {
        await page.click('#editor-nav a[href="#child-heading"]');
        await page.click("#child-new");
        await replaceVisibleInput(page, "#child-name", "BEFORE_CONSENT");
        assert.equal(
          await page.$eval("#child-resource", (node) => node.value),
          "persons",
        );
        assert.equal(await leavePrevented(page), true);
        const originalInput = await page.$("#child-name");
        const beforeRequests = requests.length;
        const beforeMe = identityRequests.length;
        const sameMe = structuredClone(state.me);
        const samePermissions = structuredClone(state.detail.permissions);

        await page.select("#child-resource", "grade-samples");
        await page.waitForSelector("#ui-confirm-dialog[open]");
        assert.equal(identityRequests.length, beforeMe + 1);
        assert.match(
          await page.$eval("#ui-confirm-message", (node) => node.textContent),
          /미저장 입력/,
        );
        // 첫 Me가 끝나 실제 확인창이 열린 뒤에만 두 번째 Me를 보류한다.
        const held = hold(mePath, "GET");
        await page.click("#ui-confirm-accept");
        await held.ready;
        await page.waitForFunction(
          () => !document.getElementById("ui-confirm-dialog"),
        );
        assert.equal(identityRequests.length, beforeMe + 2);
        if (variant !== "unchanged") {
          await replaceVisibleInput(page, "#child-name", "AFTER_CONSENT");
          if (variant === "keep") await page.select("#child-name-mode", "keep");
        }
        await held.respond(sameMe);

        // 원래 입력의 분리 또는 수정 후 고정 안내로 비동기 작업 재개를 확인한다.
        await page.waitForFunction(
          (node) =>
            !node.isConnected ||
            document.getElementById("notice")?.textContent ===
              "미저장 입력이 바뀌었습니다. 다시 확인해 주세요.",
          {},
          originalInput,
        );
        if (variant === "unchanged") {
          assert.equal(
            await originalInput.evaluate((node) => node.isConnected),
            false,
          );
          await page.waitForFunction(
            () =>
              document.getElementById("child-list-status").textContent ===
              "이 상태의 판정 검증 예시이 없습니다.",
          );
          assert.equal(
            await page.$eval("#child-resource", (node) => node.value),
            "grade-samples",
          );
          assert.equal(
            await page.$eval("#child-edit", (node) => node.hidden),
            true,
          );
          assert.deepEqual(
            requests.slice(beforeRequests).map(({ path, method, query }) => ({
              path,
              method,
              query,
            })),
            [
              {
                path: `${root}/grade-samples`,
                method: "GET",
                query: "?size=20&activeYn=true",
              },
            ],
            "변하지 않은 동의만 요청한 대상 목록을 한 번 조회",
          );
        } else {
          assert.equal(
            await originalInput.evaluate((node) => node.isConnected),
            true,
            `${variant}: 동의 후 바뀐 원래 입력 DOM을 교체하지 않음`,
          );
          assert.equal(
            await page.$eval("#child-name", (node) => node.value),
            "AFTER_CONSENT",
          );
          assert.equal(
            await page.$eval("#child-name-mode", (node) => node.value),
            variant,
          );
          assert.equal(
            await page.$eval("#child-name", (node) => node.hidden),
            variant === "keep",
          );
          assert.equal(
            await page.$eval("#child-resource", (node) => node.value),
            "persons",
          );
          assert.equal(await leavePrevented(page), true);
          assert.deepEqual(
            requests.slice(beforeRequests),
            [],
            "낡은 동의로 변경·비공개 선조회·대상 목록 조회를 보내지 않음",
          );
        }
        assert.deepEqual(state.me, sameMe);
        assert.deepEqual(state.detail.permissions, samePermissions);
        assert.equal(savedRequests(requests).length, 0);
        assert.equal(
          requests
            .slice(beforeRequests)
            .filter(({ method }) => method !== "GET").length,
          0,
        );
        await originalInput.dispose();
      },
    );
  }
});

test("H3 child consent new-child selection preserves edits made during the second Me await", async () => {
  for (const variant of ["unchanged", "value", "keep"]) {
    await isolated(
      async ({ page, state, requests, identityRequests, hold }) => {
        await page.click('#editor-nav a[href="#child-heading"]');
        await page.click("#child-new");
        await replaceVisibleInput(page, "#child-name", "BEFORE_CONSENT");
        assert.equal(
          await page.$eval("#child-resource", (node) => node.value),
          "persons",
        );
        assert.equal(await leavePrevented(page), true);
        const originalInput = await page.$("#child-name");
        const beforeRequests = requests.length;
        const beforeMe = identityRequests.length;
        const sameMe = structuredClone(state.me);
        const samePermissions = structuredClone(state.detail.permissions);

        await page.click("#child-new");
        await page.waitForSelector("#ui-confirm-dialog[open]");
        assert.equal(identityRequests.length, beforeMe + 1);
        assert.match(
          await page.$eval("#ui-confirm-message", (node) => node.textContent),
          /미저장 입력/,
        );
        // 첫 Me가 끝나 실제 확인창이 열린 뒤에만 두 번째 Me를 보류한다.
        const held = hold(mePath, "GET");
        await page.click("#ui-confirm-accept");
        await held.ready;
        await page.waitForFunction(
          () => !document.getElementById("ui-confirm-dialog"),
        );
        assert.equal(identityRequests.length, beforeMe + 2);
        if (variant !== "unchanged") {
          await replaceVisibleInput(page, "#child-name", "AFTER_CONSENT");
          if (variant === "keep") await page.select("#child-name-mode", "keep");
        }
        await held.respond(sameMe);

        // 새 빈 폼도 이탈 보호가 참일 수 있으므로 원래 DOM 보존을 먼저 검사한다.
        await page.waitForFunction(
          (node) =>
            !node.isConnected ||
            document.getElementById("notice")?.textContent ===
              "미저장 입력이 바뀌었습니다. 다시 확인해 주세요.",
          {},
          originalInput,
        );
        if (variant === "unchanged") {
          assert.equal(
            await originalInput.evaluate((node) => node.isConnected),
            false,
          );
          assert.equal(
            await page.$eval("#child-name", (node) => node.value),
            "",
          );
          assert.equal(
            await page.$eval("#child-name-mode", (node) => node.value),
            "value",
          );
          assert.equal(
            await page.$eval("#child-edit", (node) => node.hidden),
            false,
          );
          assert.equal(
            await page.evaluate(() => document.activeElement.id),
            "child-code",
          );
        } else {
          assert.equal(
            await originalInput.evaluate((node) => node.isConnected),
            true,
            `${variant}: 동의 후 바뀐 원래 입력 DOM을 교체하지 않음`,
          );
          assert.equal(
            await page.$eval("#child-name", (node) => node.value),
            "AFTER_CONSENT",
          );
          assert.equal(
            await page.$eval("#child-name-mode", (node) => node.value),
            variant,
          );
          assert.equal(
            await page.$eval("#child-name", (node) => node.hidden),
            variant === "keep",
          );
          assert.equal(await leavePrevented(page), true);
        }
        assert.equal(
          await page.$eval("#child-resource", (node) => node.value),
          "persons",
        );
        assert.deepEqual(state.me, sameMe);
        assert.deepEqual(state.detail.permissions, samePermissions);
        assert.equal(savedRequests(requests).length, 0);
        assert.deepEqual(
          requests.slice(beforeRequests),
          [],
          "새 작성 선택은 변경·비공개 선조회·요청하지 않은 목록 조회를 보내지 않음",
        );
        await originalInput.dispose();
      },
    );
  }
});

const manualPath = `${root}/review-snapshots/${currentId}/records`;

/** 실제 실행 근거가 아닌 합성 전송 시나리오의 REVIEW 상세다. */
function manualSource() {
  return {
    ...source({ edit: true, review: true, publish: false }),
    status: "REVIEW",
    currentSnapshotId: currentId,
  };
}

/** 합성 최소 영수증은 다섯 필드만 갖는다. 서버 저장·품질을 증명하지 않는다. */
function manualReceipt(overrides = {}) {
  return {
    recordId,
    snapshotId: currentId,
    current: true,
    replayed: false,
    requestId: "33333333-3333-4333-8333-333333333333",
    ...overrides,
  };
}

/** 기존 이력 버튼·현재 사본 선택·실제 확인창으로 SR05 쓰기 기준을 수락한다. */
async function prepareManual(context) {
  const { page, queue, json } = context;
  queue(historyPath, "GET", (request) =>
    json(request, {
      items: [snapshotSummary(), snapshotSummary(currentId, true)],
      hasNext: false,
      nextAfterId: null,
    }),
  );
  await page.click("#saved-history-first");
  await page.waitForSelector(`[data-snapshot-id="${currentId}"]`);
  await page.click(`[data-snapshot-id="${currentId}"]`);
  await confirm(page, "#manual-baseline");
  await page.waitForFunction(
    () => !document.getElementById("manual-submit").disabled,
  );
}

/**
 * 보이는 필드와 명시 null 선택만 사용해 합성 입력을 작성한다.
 * @param {object} page 격리 제품 페이지.
 * @param {object} values 기본은 실제 미실시를 설명하는 MODEL/INCOMPLETE이며 실행 실적이 아니다.
 */
async function fillManual(page, values = {}) {
  const fields = {
    kind: "MODEL",
    result: "INCOMPLETE",
    modelId: null,
    effort: null,
    evidence: "Synthetic unperformed review; no model or human quality claim.",
    evidenceRef: null,
    checkedAt: null,
    criticalOpenCount: null,
    notes: "Not performed. Synthetic transport fixture only.",
    runRef: null,
    separateContext: "null",
    formatNo: "1",
    ...values,
  };
  for (const [key, value] of Object.entries(fields)) {
    if (["kind", "result", "separateContext", "formatNo"].includes(key))
      await page.select(`#manual-${key}`, value);
    else if (["evidence", "notes"].includes(key))
      await replaceVisibleInput(page, `#manual-${key}`, value);
    else {
      await page.select(
        `#manual-${key}-mode`,
        value === null ? "null" : "value",
      );
      if (value !== null)
        await replaceVisibleInput(page, `#manual-${key}`, value);
    }
  }
}

/** 合成 이탈 술어와 별도로 완료 문구·공개 활성 버튼으로 비동기 종료를 기다린다. */
async function manualMessage(page, fragment) {
  await page.waitForFunction(
    (text) =>
      document.getElementById("manual-status")?.textContent.includes(text) &&
      !document.getElementById("manual-refresh").disabled,
    {},
    fragment,
  );
}

/** 합성 시나리오에서 실제 전송된 SR05 POST만 센다. */
function manualPosts(requests) {
  return requests.filter(
    ({ path, method }) => path === manualPath && method === "POST",
  );
}

/** 합성 present 근거는 입력 조합 검증 전용이며 모델 실행이나 사람 판정 증거가 아니다. */
function presentManual(overrides = {}) {
  return {
    modelId: "synthetic-model",
    effort: "synthetic",
    evidenceRef: "synthetic/evidence",
    checkedAt: "2020-01-02T03:04:05.123456789+00:00",
    criticalOpenCount: "900719925474099312345678901234567890",
    runRef: "synthetic/run",
    separateContext: "true",
    result: "FAIL",
    ...overrides,
  };
}

test("SR05 visible field errors prevent POST and focus the actual kind and nullable choices", async () => {
  await isolated(async (context) => {
    const { page, requests } = context;
    await prepareManual(context);
    await page.click("#manual-submit");
    await manualMessage(page, "입력 오류");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "manual-kind",
    );
    assert.match(
      await page.$eval("#manual-kind-error", (node) => node.textContent),
      /MODEL/,
    );
    await page.select("#manual-kind", "MODEL");
    await page.select("#manual-result", "INCOMPLETE");
    await page.click("#manual-submit");
    await manualMessage(page, "입력 오류");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "manual-modelId-mode",
    );
    assert.equal(manualPosts(requests).length, 0);
    assert.equal(await page.$("#ui-confirm-dialog"), null);
  }, manualSource());
});

test("SR05 exact bigint NUMBER wire and eight keys use accepted editRev not snapshot sourceRev", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json } = context;
    await prepareManual(context);
    await fillManual(page, presentManual());
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    const sent = manualPosts(requests)[0];
    assert.match(
      sent.body,
      /"criticalOpenCount":900719925474099312345678901234567890[,}]/,
    );
    assert.deepEqual(
      Object.keys(JSON.parse(sent.body)).sort(),
      [
        "expectedRev",
        "requestKey",
        "kind",
        "result",
        "modelId",
        "effort",
        "evidence",
        "evidenceData",
      ].sort(),
    );
    assert.equal(JSON.parse(sent.body).expectedRev, rev);
    assert.notEqual(JSON.parse(sent.body).expectedRev, savedRev);
    assert.match(
      JSON.parse(sent.body).requestKey,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    assert.equal(sent.headers["x-csrf-token"], "synthetic-csrf");
    assert.equal(
      await leavePrevented(page),
      false,
      "합성 술어: 확정 입력은 깨끗함",
    );
    assert.equal(
      requests.filter(({ path }) => path === manualPath && !path.includes("?"))
        .length,
      1,
    );
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      "",
    );
  }, manualSource());
});

test("SR05 honest unperformed MODEL and APPROVAL require explicit null metadata and nonblank notes", async () => {
  for (const kind of ["MODEL", "APPROVAL"]) {
    await isolated(async (context) => {
      const { page, requests, queue, json } = context;
      await prepareManual(context);
      await fillManual(page, { kind, notes: "" });
      await page.click("#manual-submit");
      await manualMessage(page, "입력 오류");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "manual-notes",
      );
      assert.equal(manualPosts(requests).length, 0);
      await replaceVisibleInput(
        page,
        "#manual-notes",
        "Not performed; synthetic absence only.",
      );
      queue(manualPath, "POST", (request) =>
        json(request, manualReceipt(), 201),
      );
      await confirm(page, "#manual-submit");
      await manualMessage(page, "기록 영수증을 확인");
      const body = JSON.parse(manualPosts(requests)[0].body);
      assert.equal(body.kind, kind);
      assert.equal(body.result, "INCOMPLETE");
      assert.equal(body.modelId, null);
      assert.equal(body.effort, null);
      assert.deepEqual(body.evidenceData, {
        formatNo: 1,
        evidenceRef: null,
        checkedAt: null,
        criticalOpenCount: null,
        notes: "Not performed; synthetic absence only.",
        runRef: null,
        separateContext: null,
      });
    }, manualSource());
  }
});

test("SR05 MODEL APPROVAL policy combinations expose specific user errors before any append", async () => {
  for (const [values, field] of [
    [{ result: "PASS" }, "result"],
    [presentManual({ separateContext: "false" }), "separateContext"],
    [presentManual({ result: "PASS", criticalOpenCount: "0" }), "modelId"],
    [
      presentManual({
        result: "PASS",
        modelId: "gpt-6-astra",
        criticalOpenCount: "0",
      }),
      "effort",
    ],
    [
      presentManual({
        result: "PASS",
        modelId: "gpt-6-astra",
        effort: "medium",
      }),
      "criticalOpenCount",
    ],
    [presentManual({ kind: "APPROVAL" }), "modelId"],
    [presentManual({ checkedAt: "2026-02-30T00:00:00Z" }), "checkedAt"],
    [presentManual({ criticalOpenCount: "1.5" }), "criticalOpenCount"],
    [
      presentManual({ evidenceRef: "https://not-fetched.invalid" }),
      "evidenceRef",
    ],
  ]) {
    await isolated(async (context) => {
      const { page, requests } = context;
      await prepareManual(context);
      await fillManual(page, values);
      await page.click("#manual-submit");
      await manualMessage(page, "입력 오류");
      assert.ok(
        await page.$eval(
          `#manual-${field}-error`,
          (node) => node.textContent.length > 0,
        ),
      );
      assert.equal(manualPosts(requests).length, 0);
    }, manualSource());
  }
});

test("SR05 format2 fieldwise resolutions preserve closed rows and reject duplicates and nonPASS targets", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json } = context;
    await prepareManual(context);
    await fillManual(
      page,
      presentManual({
        kind: "APPROVAL",
        modelId: null,
        effort: null,
        runRef: null,
        separateContext: "null",
        result: "PASS",
        criticalOpenCount: "0",
        formatNo: "2",
      }),
    );
    for (let index = 0; index < 2; index++) {
      await page.click("#manual-resolution-add");
      const row = `[data-manual-resolution]:nth-child(${index + 1})`;
      await page.type(`${row} [name="recordId"]`, recordId);
      await page.select(`${row} [name="reasonCode"]`, "ISSUE_VERIFIED");
      await page.type(`${row} [name="verificationRef"]`, "synthetic_fix");
    }
    await page.click("#manual-submit");
    await manualMessage(page, "입력 오류");
    assert.match(
      await page.$eval("#manual-resolves-error", (node) => node.textContent),
      /중복/,
    );
    await page.click("[data-manual-resolution]:last-child button");
    await page.select("#manual-result", "FAIL");
    await page.click("#manual-submit");
    await manualMessage(page, "입력 오류");
    assert.equal(manualPosts(requests).length, 0);
    await page.select("#manual-result", "PASS");
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    const body = JSON.parse(manualPosts(requests)[0].body);
    assert.deepEqual(body.evidenceData.resolves, [
      {
        recordId,
        reasonCode: "ISSUE_VERIFIED",
        verificationRef: "synthetic_fix",
      },
    ]);
    assert.equal(Object.keys(body.evidenceData).length, 8);
  }, manualSource());
});

test("SR05 transport unknown and later rejected reconfirm retain the original body key and newer draft", async () => {
  await isolated(async (context) => {
    const { page, requests, hold, queue, json } = context;
    await prepareManual(context);
    await fillManual(page);
    const lost = hold(manualPath);
    await confirm(page, "#manual-submit");
    await lost.ready;
    await lost.lose();
    await manualMessage(page, "결과 미확인");
    const original = manualPosts(requests)[0].body;
    await replaceVisibleInput(page, "#manual-notes", "Newer unsent notes.");
    queue(manualPath, "POST", (request) =>
      json(request, { code: "EDIT_CONFLICT" }, 409),
    );
    await confirm(page, "#manual-reconfirm");
    await manualMessage(page, "요청 거절");
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.dataset.state),
      "unknown",
    );
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval("#manual-submit", (node) => node.disabled),
      true,
    );
    queue(manualPath, "POST", (request) =>
      json(request, manualReceipt({ replayed: true, current: false }), 200),
    );
    await confirm(page, "#manual-reconfirm");
    await manualMessage(page, "기록 영수증을 확인");
    assert.deepEqual(
      manualPosts(requests).map(({ body }) => body),
      [original, original, original],
    );
    assert.match(
      await page.$eval("#manual-receipt", (node) => node.textContent),
      /current=false.*replayed=true/,
    );
    assert.equal(
      await page.$eval("#manual-notes", (node) => node.value),
      "Newer unsent notes.",
    );
    assert.equal(await leavePrevented(page), true, "합성 술어: 새 입력은 보호");
    assert.equal(
      requests.filter(
        ({ path, method }) => path === manualPath && method === "GET",
      ).length,
      0,
    );
  }, manualSource());
});

test("SR05 definite initial rejection reconciles through a real GET and permits a corrected new key", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json } = context;
    await prepareManual(context);
    await fillManual(page);
    queue(manualPath, "POST", (request) =>
      json(request, { code: "REVIEW_NOT_READY" }, 422),
    );
    await confirm(page, "#manual-submit");
    await manualMessage(page, "요청 거절");
    assert.equal(
      await page.$eval("#manual-reconfirm", (node) => node.hidden),
      true,
    );
    const reads = requests.filter(
      ({ path, method }) => path === root && method === "GET",
    ).length;
    await acceptLifecycle(page);
    assert.equal(
      requests.filter(({ path, method }) => path === root && method === "GET")
        .length,
      reads + 1,
    );
    // 실제 비교 수락 뒤에도 원래 사본의 수정 입력은 버리지 않고 새 의도로 사용할 수 있다.
    await prepareManual(context);
    await fillManual(page, {
      notes: "Corrected honest unperformed explanation.",
    });
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    const posts = manualPosts(requests).map(({ body }) => JSON.parse(body));
    assert.notEqual(posts[0].requestKey, posts[1].requestKey);
    assert.equal(
      posts[1].evidenceData.notes,
      "Corrected honest unperformed explanation.",
    );
  }, manualSource());
});

test("SR05 CSRF_INVALID clears only the token and explicit reconfirm sends identical JSON once", async () => {
  await isolated(async (context) => {
    const { page, requests, state, queue, json } = context;
    await prepareManual(context);
    await fillManual(page);
    queue(manualPath, "POST", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await confirm(page, "#manual-submit");
    await manualMessage(page, "CSRF 확인 거절");
    assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
    const reads = state.csrfReads;
    assert.equal(manualPosts(requests).length, 1);
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-reconfirm");
    await manualMessage(page, "기록 영수증을 확인");
    assert.equal(state.csrfReads, reads + 1);
    assert.equal(manualPosts(requests)[1].body, manualPosts(requests)[0].body);
  }, manualSource());
});

test("SR05 newer input during post-consent Me or CSRF cancels an unsent intent without POST", async () => {
  for (const boundary of ["me", "csrf"]) {
    await isolated(async (context) => {
      const { page, requests, hold, queue, json } = context;
      await prepareManual(context);
      await fillManual(page);
      if (boundary === "csrf") {
        queue(manualPath, "POST", (request) =>
          json(request, { code: "CSRF_INVALID" }, 403),
        );
        await confirm(page, "#manual-submit");
        await manualMessage(page, "CSRF 확인 거절");
      }
      const count = manualPosts(requests).length;
      const button =
        boundary === "csrf" ? "#manual-reconfirm" : "#manual-submit";
      await page.click(button);
      await page.waitForSelector("#ui-confirm-dialog[open]");
      const waiting = hold(
        boundary === "me" ? mePath : "/admin/api/auth/csrf",
        "GET",
      );
      await page.click("#ui-confirm-accept");
      await waiting.ready;
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      await replaceVisibleInput(
        page,
        "#manual-notes",
        "Changed after consent.",
      );
      await waiting.respond(
        boundary === "me"
          ? identityResponse()
          : { headerName: "X-CSRF-TOKEN", token: "synthetic-new-token" },
      );
      await manualMessage(page, "전송하지 않았습니다");
      assert.equal(manualPosts(requests).length, count);
      assert.equal(
        await page.$eval("#manual-notes", (node) => node.value),
        "Changed after consent.",
      );
    }, manualSource());
  }
});

test("SR05 actor change and ordinary access loss purge new drafts intents private DOM and late receipts", async () => {
  for (const variant of ["actor", "absolute", "401", "403", "404"]) {
    await isolated(async (context) => {
      const { page, requests, state, hold } = context;
      await prepareManual(context);
      await fillManual(page);
      const post = hold(manualPath);
      await confirm(page, "#manual-submit");
      await post.ready;
      if (variant === "actor")
        state.me.accountKey = "22222222-2222-4222-8222-222222222222";
      if (variant === "absolute")
        state.me.absoluteExpiresAt = "2026-10-05T10:00:00Z";
      if (["401", "403", "404"].includes(variant))
        await post.respond(
          {
            code:
              variant === "403"
                ? "FORBIDDEN"
                : variant === "401"
                  ? "AUTH_REQUIRED"
                  : "NOT_FOUND",
          },
          Number(variant),
        );
      else await post.respond(manualReceipt(), 201);
      await page.waitForSelector("#editor[hidden]");
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$("#manual-evidence"), null);
      assert.equal(manualPosts(requests).length, 1);
    }, manualSource());
  }
});

test("SR05 rolling identity preserves sent ownership and old receipt cannot erase newer unsent input", async () => {
  await isolated(async (context) => {
    const { page, requests, state, hold } = context;
    await prepareManual(context);
    await fillManual(page);
    const post = hold(manualPath);
    await confirm(page, "#manual-submit");
    await post.ready;
    await replaceVisibleInput(page, "#manual-evidence", "New draft evidence.");
    state.me.idleExpiresAt = "2026-10-04T04:00:00Z";
    state.me.reauthExpiresAt = "2026-10-04T03:00:00Z";
    await post.respond(manualReceipt(), 201);
    await manualMessage(page, "기록 영수증을 확인");
    assert.equal(
      await page.$eval("#manual-evidence", (node) => node.value),
      "New draft evidence.",
    );
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.dataset.state),
      "confirmed",
    );
    assert.equal(await leavePrevented(page), true);
    assert.equal(manualPosts(requests).length, 1);
  }, manualSource());
});

test("SR05 confirmed receipt survives explicit GET failure and permission-only drift without restoring write eligibility", async () => {
  await isolated(async (context) => {
    const { page, requests, state, queue, json } = context;
    await prepareManual(context);
    await fillManual(page);
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    const receipt = await page.$eval(
      "#manual-receipt",
      (node) => node.textContent,
    );
    queue(root, "GET", (request) =>
      json(request, { code: "STORY_UNAVAILABLE" }, 503),
    );
    await page.click("#manual-refresh");
    await manualMessage(page, "현재 GET 조회 실패");
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.textContent),
      receipt,
    );
    state.detail.permissions = { edit: true, review: false, publish: false };
    await acceptLifecycle(page);
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.textContent),
      receipt,
    );
    assert.equal(
      await page.$eval("#manual-submit", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#manual-reconfirm", (node) => node.disabled),
      false,
      "과거 replay 인가는 서버 책임",
    );
    assert.equal(manualPosts(requests).length, 1);
  }, manualSource());
});

test("SR05 malformed success and 503 remain unknown and SR06 raw history never repairs the intent", async () => {
  for (const variant of ["503", "malformed", "extra", "mismatch"]) {
    await isolated(async (context) => {
      const { page, requests, hold, queue, raw } = context;
      await prepareManual(context);
      await fillManual(page);
      const post = hold(manualPath);
      await confirm(page, "#manual-submit");
      await post.ready;
      if (variant === "503") await post.respond({ code: "STORY_BUSY" }, 503);
      else if (variant === "malformed")
        await post.respondRaw('{"recordId":', 201);
      else
        await post.respond(
          manualReceipt(
            variant === "extra"
              ? { evidence: "not allowed in receipt" }
              : { snapshotId: savedId },
          ),
          201,
        );
      await manualMessage(page, "결과 미확인");
      const original = savedRecordsText({
        snapshotId: currentId,
        current: true,
      });
      queue(manualPath, "GET", (request) => raw(request, original));
      await page.click("#saved-records-first");
      await page.waitForFunction(
        () =>
          document.getElementById("saved-records-status").dataset.state ===
          "success-populated",
      );
      assert.equal(
        await page.$eval("#saved-records-result", (node) => node.textContent),
        original,
      );
      assert.equal(
        await page.$eval("#manual-receipt", (node) => node.dataset.state),
        "unknown",
      );
      assert.equal(manualPosts(requests).length, 1);
      assert.equal(await leavePrevented(page), true);
    }, manualSource());
  }
});

test("SR05 selection history and Cancel protect drafts while explicit discard releases leave predicate", async () => {
  await isolated(async (context) => {
    const { page, requests } = context;
    await prepareManual(context);
    await fillManual(page);
    const reads = savedRequests(requests).length;
    await page.click(`[data-snapshot-id="${savedId}"]`);
    await manualMessage(page, "보호했습니다");
    assert.ok(
      (
        await page.$eval("#saved-review-selected", (node) => node.textContent)
      ).includes(currentId),
    );
    await page.click("#saved-history-first");
    await manualMessage(page, "명시 폐기");
    assert.equal(savedRequests(requests).length, reads);
    await page.click("#manual-discard");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-cancel");
    await page.waitForFunction(
      () => !document.getElementById("ui-confirm-dialog"),
    );
    await manualMessage(page, "폐기를 취소했습니다");
    assert.equal(await leavePrevented(page), true);
    assert.equal(
      await page.$eval("#manual-notes", (node) => node.value),
      "Not performed. Synthetic transport fixture only.",
    );
    await confirm(page, "#manual-discard");
    await manualMessage(page, "명시 폐기했습니다");
    assert.equal(
      await leavePrevented(page),
      false,
      "합성 술어이며 실제 native 경고 증거 아님",
    );
    await page.click(`[data-snapshot-id="${savedId}"]`);
    assert.equal(
      await page.$eval("#manual-submit", (node) => node.disabled),
      true,
    );
    assert.equal(manualPosts(requests).length, 0);
  }, manualSource());
});

test("SR05 permission drift cancels unsent consent but cannot discard a still-owned sent receipt", async () => {
  for (const sent of [false, true]) {
    await isolated(async (context) => {
      const { page, requests, state, hold } = context;
      await prepareManual(context);
      await fillManual(page);
      let waiting;
      if (sent) {
        waiting = hold(manualPath);
        await confirm(page, "#manual-submit");
      } else {
        await page.click("#manual-submit");
        await page.waitForSelector("#ui-confirm-dialog[open]");
        waiting = hold(mePath, "GET");
        await page.click("#ui-confirm-accept");
      }
      await waiting.ready;
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      state.detail.permissions = { edit: true, review: false, publish: false };
      await page.click("#review-lifecycle-refresh");
      await page.waitForFunction(
        () => !document.getElementById("comparison").hidden,
      );
      assert.match(
        await page.$eval("#review-permissions", (node) => node.textContent),
        /검수 없음/,
      );
      await waiting.respond(
        sent ? manualReceipt() : identityResponse(),
        sent ? 201 : 200,
      );
      await manualMessage(
        page,
        sent ? "기록 영수증을 확인" : "전송하지 않았습니다",
      );
      assert.equal(manualPosts(requests).length, sent ? 1 : 0);
      assert.equal(
        await page.$eval("#manual-submit", (node) => node.disabled),
        true,
      );
      if (sent)
        assert.equal(
          await page.$eval("#manual-receipt", (node) => node.dataset.state),
          "confirmed",
        );
      assert.equal(
        await page.$eval("#manual-notes", (node) => node.value),
        "Not performed. Synthetic transport fixture only.",
      );
    }, manualSource());
  }
});

test("SR05 read-only metadata cannot adopt a newer revision or unlock a new write baseline", async () => {
  await isolated(async (context) => {
    const { page, requests, state, queue, json } = context;
    queue(root, "GET", (request) =>
      json(request, { code: "STORY_UNAVAILABLE" }, 503),
    );
    await page.click("#manual-refresh");
    await manualMessage(page, "현재 GET 조회 실패");
    state.detail = { ...manualSource(), editRev: nextRev };
    await page.click("#saved-metadata-refresh");
    await page.waitForFunction(
      () =>
        document.getElementById("saved-metadata-status").dataset.state ===
        "success",
    );
    queue(historyPath, "GET", (request) =>
      json(request, {
        items: [snapshotSummary(currentId, true)],
        hasNext: false,
        nextAfterId: null,
      }),
    );
    await page.click("#saved-history-first");
    await savedState(page, "history", "success-populated");
    await page.click(`[data-snapshot-id="${currentId}"]`);
    assert.equal(
      await page.$eval("#manual-baseline", (node) => node.disabled),
      true,
    );
    assert.ok(
      (
        await page.$eval("#version-summary", (node) => node.textContent)
      ).includes(rev),
    );
    assert.ok(
      (
        await page.$eval("#saved-review-current", (node) => node.textContent)
      ).includes(nextRev),
    );
    assert.equal(manualPosts(requests).length, 0);
    await acceptLifecycle(page);
    await prepareManual(context);
    await fillManual(page);
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    assert.equal(
      JSON.parse(manualPosts(requests)[0].body).expectedRev,
      nextRev,
    );
  }, manualSource());
});

test("SR05 present MODEL PASS and APPROVAL FAIL INCOMPLETE keep their distinct closed metadata", async () => {
  for (const [kind, result] of [
    ["MODEL", "PASS"],
    ["APPROVAL", "FAIL"],
    ["APPROVAL", "INCOMPLETE"],
  ]) {
    await isolated(async (context) => {
      const { page, requests, queue, json } = context;
      await prepareManual(context);
      await fillManual(
        page,
        presentManual({
          kind,
          result,
          modelId: kind === "MODEL" ? "gpt-6-astra" : null,
          effort: kind === "MODEL" ? "medium" : null,
          runRef: kind === "MODEL" ? "synthetic/run" : null,
          separateContext: kind === "MODEL" ? "true" : "null",
          criticalOpenCount: result === "PASS" ? "0" : "1",
          formatNo: "2",
        }),
      );
      queue(manualPath, "POST", (request) =>
        json(request, manualReceipt(), 201),
      );
      await confirm(page, "#manual-submit");
      await manualMessage(page, "기록 영수증을 확인");
      const body = JSON.parse(manualPosts(requests)[0].body);
      assert.equal(body.kind, kind);
      assert.equal(body.result, result);
      assert.equal(body.modelId, kind === "MODEL" ? "gpt-6-astra" : null);
      assert.equal(body.effort, kind === "MODEL" ? "medium" : null);
      assert.equal(
        body.evidenceData.separateContext,
        kind === "MODEL" ? true : null,
      );
      assert.deepEqual(body.evidenceData.resolves, []);
    }, manualSource());
  }
});

test("SR05 Unicode field limits and total UTF8 payload bound fail visibly before dispatch", async () => {
  await isolated(async (context) => {
    const { page, requests } = context;
    await prepareManual(context);
    await fillManual(page);
    // 표시된 실제 textarea의 입력 이벤트를 사용한다. 숨은 필드·제품 메모리 훅은 없다.
    await page.$eval("#manual-evidence", (input) => {
      input.value = "가".repeat(20001);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await page.click("#manual-submit");
    await manualMessage(page, "입력 오류");
    assert.match(
      await page.$eval("#manual-evidence-error", (node) => node.textContent),
      /20000/,
    );
    await page.$eval("#manual-evidence", (input) => {
      input.value = "A" + "\u0001".repeat(19999);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await page.$eval("#manual-notes", (input) => {
      input.value = "A" + "\u0001".repeat(3999);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await page.click("#manual-submit");
    await manualMessage(page, "입력 오류");
    assert.match(
      await page.$eval("#manual-evidence-error", (node) => node.textContent),
      /128KiB/,
    );
    assert.equal(manualPosts(requests).length, 0);
  }, manualSource());
});

test("SR05 integral decimal exponent tokens stay exact on wire without Number expansion or safe-integer caps", async () => {
  for (const token of [
    "90071992547409931234567890.00e2",
    "1e999",
    "-0.00e-999",
  ]) {
    await isolated(async (context) => {
      const { page, requests, queue, json } = context;
      await prepareManual(context);
      await fillManual(page, presentManual({ criticalOpenCount: token }));
      queue(manualPath, "POST", (request) =>
        json(request, manualReceipt(), 201),
      );
      await confirm(page, "#manual-submit");
      await manualMessage(page, "기록 영수증을 확인");
      assert.ok(
        manualPosts(requests)[0].body.includes(`"criticalOpenCount":${token},`),
      );
      assert.equal(manualPosts(requests).length, 1);
    }, manualSource());
  }
});

test("SR05 mismatched replay record ID cannot overwrite a previously confirmed minimal receipt", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json } = context;
    await prepareManual(context);
    await fillManual(page);
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    const receipt = await page.$eval(
      "#manual-receipt",
      (node) => node.textContent,
    );
    queue(manualPath, "POST", (request) =>
      json(
        request,
        manualReceipt({ recordId: "9007199254740993778", replayed: true }),
        200,
      ),
    );
    await confirm(page, "#manual-reconfirm");
    await manualMessage(page, "결과 미확인");
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.textContent),
      receipt,
    );
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.dataset.state),
      "confirmed",
    );
    assert.equal(manualPosts(requests)[0].body, manualPosts(requests)[1].body);
    assert.equal(
      await leavePrevented(page),
      false,
      "이미 확정한 원래 기록은 새 미확인 기록이 아님",
    );
  }, manualSource());
});

test("SR05 first snapshot binding preserves preselection evidence and notes without private reads or POST", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json } = context;
    const start = requests.length;
    const evidence =
      "Synthetic evidence entered before selecting any snapshot.";
    const notes = "Unperformed review notes entered before first binding.";
    assert.equal(
      await page.$$eval(
        '[data-snapshot-id][aria-pressed="true"]',
        (nodes) => nodes.length,
      ),
      0,
    );
    assert.match(
      await page.$eval("#manual-target", (node) => node.textContent),
      /선택 없음/,
    );
    await replaceVisibleInput(page, "#manual-evidence", evidence);
    await replaceVisibleInput(page, "#manual-notes", notes);
    assert.equal(await leavePrevented(page), true);
    const draft = await page.$$eval(
      "#manual-form input, #manual-form textarea, #manual-form select",
      (nodes) => nodes.map((node) => [node.id, node.value]),
    );
    queue(historyPath, "GET", (request) =>
      json(request, {
        items: [snapshotSummary(), snapshotSummary(currentId, true)],
        hasNext: false,
        nextAfterId: null,
      }),
    );
    await page.click("#saved-history-first");
    await savedState(page, "history", "success-populated");
    await page.waitForFunction(
      (id) => !document.querySelector(`[data-snapshot-id="${id}"]`).disabled,
      {},
      currentId,
    );
    await page.click(`[data-snapshot-id="${currentId}"]`);
    // 선택 성공 또는 보호 안내가 표시되어야 클릭 처리의 완료로 본다.
    await page.waitForFunction(
      (id) =>
        document
          .querySelector(`[data-snapshot-id="${id}"]`)
          .getAttribute("aria-pressed") === "true" ||
        document
          .getElementById("manual-status")
          .textContent.includes("보호했습니다"),
      {},
      currentId,
    );
    assert.equal(
      await page.$eval(`[data-snapshot-id="${currentId}"]`, (node) =>
        node.getAttribute("aria-pressed"),
      ),
      "true",
      "아직 대상이 없는 보호 입력은 첫 현재 사본에 명시 결속할 수 있어야 한다",
    );
    assert.ok(
      (
        await page.$eval("#saved-review-selected", (node) => node.textContent)
      ).includes(currentId),
    );
    assert.deepEqual(
      await page.$$eval(
        "#manual-form input, #manual-form textarea, #manual-form select",
        (nodes) => nodes.map((node) => [node.id, node.value]),
      ),
      draft,
    );
    assert.equal(
      await page.$eval("#manual-submit", (node) => node.disabled),
      true,
      "사본 선택만으로 상세 쓰기 기준을 수락하지 않는다",
    );
    await confirm(page, "#manual-baseline");
    await manualMessage(page, "기록 쓰기 기준을 수락했습니다");
    assert.equal(
      await page.$eval("#manual-submit", (node) => node.disabled),
      false,
    );
    const target = await page.$eval(
      "#manual-target",
      (node) => node.textContent,
    );
    assert.ok(target.includes(`상세 쓰기 번호 ${rev}`));
    assert.ok(target.includes(`선택 ${currentId}`));
    assert.match(target, /명시 수락됨/);
    await page.click(`[data-snapshot-id="${savedId}"]`);
    await manualMessage(page, "보호했습니다");
    assert.equal(
      await page.$eval(`[data-snapshot-id="${currentId}"]`, (node) =>
        node.getAttribute("aria-pressed"),
      ),
      "true",
      "첫 결속 뒤 보호 입력의 다른 사본 선택은 여전히 차단한다",
    );
    assert.equal(
      await page.$eval(`[data-snapshot-id="${savedId}"]`, (node) =>
        node.getAttribute("aria-pressed"),
      ),
      "false",
    );
    assert.deepEqual(
      await page.$$eval(
        "#manual-form input, #manual-form textarea, #manual-form select",
        (nodes) => nodes.map((node) => [node.id, node.value]),
      ),
      draft,
    );
    assert.equal(await leavePrevented(page), true);
    assert.deepEqual(
      requests.slice(start).map(({ path, method }) => ({ path, method })),
      [{ path: historyPath, method: "GET" }],
      "명시 이력 한 번 외 자동 상세·민감 원문·기록·미리보기 조회와 POST는 없다",
    );
  }, manualSource());
});

for (const boundary of [
  "Cancel",
  "Escape",
  "post-consent input drift",
  "post-consent permission drift",
]) {
  test(`SR05 confirmed A survives unsent B ${boundary} with exact replay and preserved draft`, async () => {
    await isolated(async (context) => {
      const { page, requests, state, queue, json, hold } = context;
      await prepareManual(context);
      await fillManual(
        page,
        presentManual({
          evidence: "Synthetic record A evidence; not a real model result.",
          notes: "Synthetic record A notes.",
        }),
      );
      queue(manualPath, "POST", (request) =>
        json(request, manualReceipt(), 201),
      );
      await confirm(page, "#manual-submit");
      await manualMessage(page, "기록 영수증을 확인");
      assert.equal(manualPosts(requests).length, 1);
      const original = manualPosts(requests)[0];
      const originalKey = JSON.parse(original.body).requestKey;
      const receipt = await page.$eval("#manual-receipt", (node) => ({
        state: node.dataset.state,
        text: node.textContent,
      }));
      assert.equal(receipt.state, "confirmed");
      assert.ok(receipt.text.includes(recordId));
      assert.ok(receipt.text.includes(currentId));
      assert.equal(
        await page.$eval(
          "#manual-reconfirm",
          (node) => node.hidden || node.disabled,
        ),
        false,
      );
      await fillManual(page, {
        evidence: "Synthetic draft B evidence; never dispatched.",
        notes: "Synthetic draft B notes; never dispatched.",
      });
      let draft = await page.$$eval(
        "#manual-form input, #manual-form textarea, #manual-form select",
        (nodes) => nodes.map((node) => [node.id, node.value]),
      );
      const start = requests.length;
      await page.click("#manual-submit");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      assert.equal(
        await page.$eval("#ui-confirm-title", (node) => node.textContent),
        "검수 의견 추가",
        "원래 A 재확인이 아닌 새 B 추가 확인창이다",
      );
      // 취소에도 뒤따르는 Me를 보류하여 창 닫힘과 미전송 정리를 구분한다.
      const waiting = hold(mePath, "GET");
      if (boundary === "Cancel") await page.click("#ui-confirm-cancel");
      else if (boundary === "Escape") await page.keyboard.press("Escape");
      else await page.click("#ui-confirm-accept");
      await waiting.ready;
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      assert.equal(
        await page.$eval("#manual-refresh", (node) => node.disabled),
        true,
        "확인창이 닫혀도 보류된 Me 이후 정리는 아직 끝나지 않았다",
      );
      if (boundary === "post-consent input drift") {
        const notes = "Synthetic draft B notes changed during post-consent Me.";
        await replaceVisibleInput(page, "#manual-notes", notes);
        draft = draft.map(([id, value]) => [
          id,
          id === "manual-notes" ? notes : value,
        ]);
      }
      if (boundary === "post-consent permission drift") {
        state.detail.permissions = {
          edit: true,
          review: false,
          publish: false,
        };
        await page.click("#review-lifecycle-refresh");
        await page.waitForFunction(
          () =>
            !document.getElementById("comparison").hidden &&
            !document.getElementById("refresh-latest").disabled &&
            document
              .getElementById("review-permissions")
              .textContent.includes("검수 없음"),
        );
      }
      await waiting.respond(identityResponse());
      // 새 미전송 안내와 공개 버튼 복구를 함께 기다린다. 권한 조회는 세대 교체로 정리한다.
      await manualMessage(page, "전송하지 않았습니다");
      assert.equal(
        await page.$eval("#manual-discard", (node) => node.disabled),
        false,
      );
      assert.deepEqual(
        requests.slice(start).map(({ path, method }) => ({ path, method })),
        boundary === "post-consent permission drift"
          ? [{ path: root, method: "GET" }]
          : [],
        "명시 권한 관측 외 자동 조회나 B POST는 없다",
      );
      assert.deepEqual(
        await page.$eval("#manual-receipt", (node) => ({
          state: node.dataset.state,
          text: node.textContent,
        })),
        receipt,
      );
      assert.equal(
        await page.$eval(
          "#manual-reconfirm",
          (node) => node.hidden || node.disabled,
        ),
        false,
        "B 미전송 정리 뒤 확정 A의 원래 본문 재확인 행동이 남아야 한다",
      );
      assert.ok(
        (
          await page.$eval("#manual-target", (node) => node.textContent)
        ).includes(`보관된 원래 대상 사본 ${currentId}`),
      );
      assert.deepEqual(
        await page.$$eval(
          "#manual-form input, #manual-form textarea, #manual-form select",
          (nodes) => nodes.map((node) => [node.id, node.value]),
        ),
        draft,
      );
      assert.equal(await leavePrevented(page), true);
      if (boundary === "post-consent permission drift") {
        assert.equal(
          await page.$eval("#manual-submit", (node) => node.disabled),
          true,
        );
        const restoreStart = requests.length;
        state.detail.permissions = { edit: true, review: true, publish: false };
        await acceptLifecycle(page);
        assert.match(
          await page.$eval("#review-permissions", (node) => node.textContent),
          /검수 있음/,
        );
        assert.deepEqual(
          requests
            .slice(restoreStart)
            .map(({ path, method }) => ({ path, method })),
          [{ path: root, method: "GET" }],
          "권한 복구는 기존 현재 GET·비교 수락만 사용한다",
        );
        assert.deepEqual(
          await page.$eval("#manual-receipt", (node) => ({
            state: node.dataset.state,
            text: node.textContent,
          })),
          receipt,
        );
      }
      await page.waitForSelector("#manual-reconfirm", { visible: true });
      assert.equal(
        await page.$eval("#manual-reconfirm", (node) => node.disabled),
        false,
      );
      assert.deepEqual(
        await page.$$eval(
          "#manual-form input, #manual-form textarea, #manual-form select",
          (nodes) => nodes.map((node) => [node.id, node.value]),
        ),
        draft,
      );
      const replayStart = requests.length;
      queue(manualPath, "POST", (request) =>
        json(request, manualReceipt({ replayed: true }), 200),
      );
      await confirm(page, "#manual-reconfirm");
      await manualMessage(page, "기록 영수증을 확인");
      const posts = manualPosts(requests);
      assert.equal(posts.length, 2);
      assert.deepEqual(
        posts.map(({ path, body }) => ({
          path,
          body,
          requestKey: JSON.parse(body).requestKey,
        })),
        Array.from({ length: 2 }, () => ({
          path: original.path,
          body: original.body,
          requestKey: originalKey,
        })),
        "현재 B 폼으로 재구성하지 않고 정확한 A 대상·키·숫자 원문을 재전송한다",
      );
      assert.deepEqual(
        requests
          .slice(replayStart)
          .map(({ path, method }) => ({ path, method })),
        [{ path: manualPath, method: "POST" }],
        "명시 재확인 A 한 번 외 자동 조회·추가 전송은 없다",
      );
      assert.equal(
        await page.$eval("#manual-receipt", (node) => node.dataset.state),
        "confirmed",
      );
      const replayReceipt = await page.$eval(
        "#manual-receipt",
        (node) => node.textContent,
      );
      assert.ok(replayReceipt.includes(`recordId ${recordId}`));
      assert.ok(replayReceipt.includes(`사본 ${currentId}`));
      assert.match(replayReceipt, /current=true.*replayed=true/);
      assert.deepEqual(
        await page.$$eval(
          "#manual-form input, #manual-form textarea, #manual-form select",
          (nodes) => nodes.map((node) => [node.id, node.value]),
        ),
        draft,
      );
      assert.equal(await leavePrevented(page), true);
    }, manualSource());
  });
}

const issuesPath = `${root}/execution-issues`;
const issueCursor = "9223372036854775806";

/**
 * 닫힌 BATCH DTO의 합성 전송 항목이다. 실제 실행·해소·모델 판정을 주장하지 않는다.
 * @param {object} overrides 실패 형식·선택·필터를 검사하는 명시 덮어쓰기.
 */
function issueItem(overrides = {}) {
  return {
    issueKey: "44444444-4444-4444-8444-444444444444",
    snapshotId: savedId,
    runtimeConfigId: `SYNTHETIC_RUNTIME_${hostile}`,
    sourceBatchKey: "55555555-5555-4555-8555-555555555555",
    sourceReviewId: null,
    kind: "GRADING",
    severity: "CRITICAL",
    state: "OPEN",
    createdAt: "2026-10-01T00:00:00Z",
    resolvedAt: null,
    resolvedBy: null,
    targetBatchKey: null,
    targetReviewId: null,
    resolution: null,
    ...overrides,
  };
}

/** 해소 메타의 합성 응답일 뿐 PT-A12 호출·실행 성공 자료가 아니다. */
function resolvedIssue(overrides = {}) {
  return issueItem({
    state: "RESOLVED",
    resolvedAt: "2026-10-02T00:00:00Z",
    resolvedBy: "66666666-6666-4666-8666-666666666666",
    targetBatchKey: "77777777-7777-4777-8777-777777777777",
    resolution: {
      reasonCode: "GRADING_FIX_VERIFIED",
      verificationRef: "synthetic_verified",
    },
    ...overrides,
  });
}

/** 한 페이지 응답을 합성하며 내부 정렬 ID와 공개 issueKey는 혼동하지 않는다. */
function issuePage(items = [], nextCursor = null) {
  return {
    items,
    nextCursor,
    requestId: "88888888-8888-4888-8888-888888888888",
  };
}

/** PT-A11 전송만 추려 필터·선택·페이지에 숨은 요청이 없는지 검사한다. */
function issueRequests(requests) {
  return requests.filter(({ path }) => path === issuesPath);
}

/**
 * 보이는 명시 버튼이 활성화된 뒤 합성 한 페이지 응답과 공개 완료 상태를 기다린다.
 * @param {object} context 기존 격리 페이지와 전송 큐.
 * @param {object} data 합성 응답 본문.
 * @param {string} selector 첫 페이지 또는 다음 페이지 버튼.
 * @param {string} state 성공 또는 실패 상태.
 */
async function readIssuePage(
  context,
  data,
  selector = "#saved-issues-first",
  state,
) {
  const { page, queue, json } = context;
  await page.waitForSelector(`${selector}:not(:disabled)`, { visible: true });
  queue(issuesPath, "GET", (request) => json(request, data));
  await page.click(selector);
  await savedState(
    page,
    "issues",
    state || (data.items.length ? "success-populated" : "success-empty"),
  );
  await page.waitForFunction(
    () => !document.getElementById("saved-issues-first").disabled,
  );
}

test("PT-A11 requires selected positive BIGINT and exact observed REVIEW without owner or global inference", async () => {
  for (const permissions of [
    { edit: true, review: false, publish: false },
    { edit: false, review: false, publish: true },
    null,
    { edit: true, review: "true", publish: false },
    { edit: false, review: true, publish: false },
  ]) {
    await isolated(async (context) => {
      const { page, requests, state } = context;
      state.me.permissions = ["REVIEW", "EDIT", "PUBLISH"];
      await page.evaluate(() =>
        document
          .getElementById("saved-issues-first")
          .dispatchEvent(new Event("click")),
      );
      assert.equal(issueRequests(requests).length, 0, "사본 없는 음성 진입");
      await selectSnapshot(context);
      const allowed = permissions?.review === true;
      assert.equal(
        await page.$eval("#saved-issues-first", (node) => node.disabled),
        !allowed,
      );
      if (allowed) await readIssuePage(context, issuePage());
      else {
        await page.evaluate(() =>
          document
            .getElementById("saved-issues-first")
            .dispatchEvent(new Event("click")),
        );
        assert.match(
          await page.$eval(
            "#saved-issues-eligibility",
            (node) => node.textContent,
          ),
          /조회 불가/,
        );
      }
      assert.equal(issueRequests(requests).length, allowed ? 1 : 0);
    }, source(permissions));
  }
  await isolated(async (context) => {
    const { page, queue, json, requests } = context;
    queue(historyPath, "GET", (request) =>
      json(request, {
        items: [snapshotSummary("9223372036854775808")],
        hasNext: false,
        nextAfterId: null,
      }),
    );
    await page.click("#saved-history-first");
    await savedState(page, "history", "success-populated");
    await page.click('[data-snapshot-id="9223372036854775808"]');
    await page.evaluate(() =>
      document
        .getElementById("saved-issues-first")
        .dispatchEvent(new Event("click")),
    );
    assert.equal(
      issueRequests(requests).length,
      0,
      "BIGINT 상한 초과 선택은 전송 금지",
    );
  }, manualSource());
});

test("PT-A11 synthetic populated metadata is literal allowlisted one-page history with exact cursor and explicit filters", async () => {
  await isolated(async (context) => {
    const { page, requests } = context;
    await selectSnapshot(context);
    assert.equal(issueRequests(requests).length, 0);
    const items = Array.from({ length: 20 }, (_, index) =>
      issueItem({
        issueKey: `44444444-4444-4444-8444-${String(index).padStart(12, "0")}`,
        kind: ["CONTENT", "GRADING", "INFRA", "OBSERVATION"][index % 4],
        severity: index % 2 ? "MINOR" : "CRITICAL",
        report: "NEVER_RENDER_ISSUE",
        answer: "NEVER_RENDER_ISSUE",
        modelOutput: "NEVER_RENDER_ISSUE",
        sourceUnavailable: true,
        resolutionUnavailable: true,
      }),
    );
    await readIssuePage(context, {
      ...issuePage(items, issueCursor),
      raw: "NEVER_RENDER_ISSUE",
    });
    const text = await page.$eval(
      "#saved-issues-result",
      (node) => node.textContent,
    );
    const rendered = JSON.parse(text);
    assert.equal(rendered.length, 20);
    assert.equal(rendered[0].runtimeConfigId, items[0].runtimeConfigId);
    assert.equal(rendered[0].sourceReviewId, null);
    assert.equal(rendered[0].resolution, null);
    assert.deepEqual(
      Object.keys(rendered[0]).sort(),
      Object.keys(issueItem()).sort(),
    );
    assert.ok(rendered[0].runtimeConfigId.includes(hostile));
    assert.doesNotMatch(
      text,
      /NEVER_RENDER_ISSUE|sourceUnavailable|resolutionUnavailable/,
    );
    assert.equal(await page.$("#saved-issues-result img"), null);
    assert.equal(await page.evaluate(() => window.injected), undefined);
    assert.equal(
      issueRequests(requests).length,
      1,
      "다음 페이지 prefetch 없음",
    );
    assert.equal(
      issueRequests(requests)[0].query,
      `?snapshotId=${savedId}&size=20`,
    );
    await readIssuePage(
      context,
      issuePage([resolvedIssue()]),
      "#saved-issues-next",
    );
    assert.equal(
      issueRequests(requests)[1].query,
      `?snapshotId=${savedId}&size=20&cursor=${issueCursor}`,
    );
    assert.deepEqual(
      JSON.parse(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
      ),
      [resolvedIssue()],
      "append가 아닌 페이지 대체",
    );
    assert.equal(
      await page.$eval("#saved-issues-next", (node) => node.disabled),
      true,
    );
    for (const filter of ["OPEN", "RESOLVED", "ALL"]) {
      const count = issueRequests(requests).length;
      await page.select("#saved-issues-filter", filter);
      await savedState(page, "issues", "stale");
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(issueRequests(requests).length, count);
      await readIssuePage(
        context,
        issuePage([filter === "RESOLVED" ? resolvedIssue() : issueItem()]),
      );
      assert.equal(
        issueRequests(requests).at(-1).query,
        `?snapshotId=${savedId}&size=20${filter === "ALL" ? "" : `&state=${filter}`}`,
      );
    }
    assert.ok(
      issueRequests(requests).every(
        ({ method, body }) => method === "GET" && body === undefined,
      ),
    );
    assert.equal(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
      0,
    );
    for (const width of [360, 768, 1440]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      for (const selector of [
        "#saved-issues-filter",
        "#saved-issues-first",
        "#saved-issues-next",
      ])
        assert.ok(
          await page.$eval(
            selector,
            (node) => node.getBoundingClientRect().height >= 48,
          ),
        );
      await page.focus("#saved-issues-filter");
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "saved-issues-first",
      );
    }
  }, manualSource());
});

test("PT-A11 malformed envelopes items cursors and filter echoes fail closed without replacing missing values with null", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, raw } = context;
    await selectSnapshot(context);
    const malformed = [
      null,
      {},
      issuePage(null),
      { ...issuePage(), requestId: null },
      { items: [], requestId: issuePage().requestId },
      ...[
        "0",
        "01",
        "-1",
        "1e3",
        "9223372036854775808",
        9007199254740992,
        "",
      ].map((cursor) => issuePage([], cursor)),
      issuePage([issueItem()], issueCursor),
      issuePage(Array.from({ length: 21 }, () => issueItem())),
      issuePage([issueItem(), issueItem()]),
      ...[
        { snapshotId: currentId },
        { snapshotId: 9007199254740992 },
        { sourceReviewId: undefined },
        { targetReviewId: undefined },
        { resolution: undefined },
        { resolvedBy: undefined },
        { targetBatchKey: undefined },
        { resolvedAt: undefined },
        { runtimeConfigId: 123 },
        { runtimeConfigId: "" },
        { sourceBatchKey: null },
        { issueKey: null },
        { createdAt: null },
        { createdAt: "2026-02-30T00:00:00Z" },
        { kind: "UNKNOWN" },
        { severity: "MAJOR" },
        { state: "UNKNOWN" },
        {
          resolution: {
            reasonCode: "INFRA_RECOVERED",
            verificationRef: "synthetic_ref",
          },
        },
      ].map((overrides) => issuePage([issueItem(overrides)])),
      ...[
        { resolvedAt: null },
        { resolvedBy: null },
        { targetBatchKey: null },
        { targetReviewId: "1" },
        { resolution: null },
        {
          resolution: { reasonCode: "OTHER", verificationRef: "synthetic_ref" },
        },
        { resolution: { reasonCode: "INFRA_RECOVERED", verificationRef: "" } },
        {
          resolution: {
            reasonCode: "INFRA_RECOVERED",
            verificationRef: "synthetic_ref",
            raw: hostile,
          },
        },
      ].map((overrides) => issuePage([resolvedIssue(overrides)])),
    ];
    for (const data of malformed) {
      await readIssuePage(context, data, "#saved-issues-first", "failure");
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
    }
    for (const [filter, item] of [
      ["OPEN", resolvedIssue()],
      ["RESOLVED", issueItem()],
    ]) {
      await page.select("#saved-issues-filter", filter);
      await readIssuePage(
        context,
        issuePage([item]),
        "#saved-issues-first",
        "failure",
      );
    }
    queue(issuesPath, "GET", (request) => raw(request, '{"items":'));
    await page.click("#saved-issues-first");
    await savedState(page, "issues", "failure");
    assert.equal(issueRequests(requests).length, malformed.length + 3);
    assert.ok(
      (
        await page.$eval("#saved-review-selected", (node) => node.textContent)
      ).includes(savedId),
    );
    await page.$eval("#saved-issues-filter", (node) => {
      node.value = "";
      node.dispatchEvent(new Event("change", { bubbles: true }));
      document
        .getElementById("saved-issues-first")
        .dispatchEvent(new Event("click"));
    });
    assert.equal(
      issueRequests(requests).length,
      malformed.length + 3,
      "알 수 없는 필터는 전송 금지",
    );
  }, manualSource());
});

test("PT-A11 late selection filter and post-body identity boundaries cannot overwrite a newer owned page", async () => {
  for (const boundary of ["selection", "filter", "body-parsed"]) {
    await isolated(async (context) => {
      const { page, requests, hold } = context;
      await selectSnapshot(context);
      const old = hold(issuesPath, "GET");
      await page.click("#saved-issues-first");
      await old.ready;
      await savedState(page, "issues", "loading");
      let afterBody;
      if (boundary === "body-parsed") {
        afterBody = hold(mePath, "GET");
        await old.respond(issuePage([issueItem()], null));
        await afterBody.ready;
      }
      if (boundary === "selection")
        await page.click(`[data-snapshot-id="${currentId}"]`);
      else await page.select("#saved-issues-filter", "RESOLVED");
      await savedState(page, "issues", "stale");
      assert.equal(issueRequests(requests).length, 1);
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      if (afterBody) await afterBody.respond(identityResponse());
      else await old.respond(issuePage([issueItem()]));
      const item =
        boundary === "selection"
          ? issueItem({
              snapshotId: currentId,
              runtimeConfigId: "NEW_SELECTION",
            })
          : resolvedIssue({ runtimeConfigId: "NEW_FILTER" });
      await readIssuePage(context, issuePage([item]));
      assert.deepEqual(
        JSON.parse(
          await page.$eval("#saved-issues-result", (node) => node.textContent),
        ),
        [item],
      );
      assert.equal(issueRequests(requests).length, 2);
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
    }, manualSource());
  }
});

test("PT-A11 current detail metadata permission and generation replacement purge pending history without adopting read revisions", async () => {
  for (const boundary of ["detail", "metadata", "permission", "generation"]) {
    await isolated(async (context) => {
      const { page, state, hold } = context;
      await selectSnapshot(context);
      const old = hold(issuesPath, "GET");
      await page.click("#saved-issues-first");
      await old.ready;
      state.detail = {
        ...manualSource(),
        editRev: nextRev,
        permissions: {
          edit: true,
          review: boundary !== "permission",
          publish: false,
        },
      };
      await page.click("#review-lifecycle-refresh");
      await page.waitForFunction(
        () => !document.getElementById("comparison").hidden,
      );
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
      if (boundary === "metadata") {
        await page.click("#saved-metadata-refresh");
        await savedState(page, "metadata", "success");
        assert.ok(
          (
            await page.$eval("#version-summary", (node) => node.textContent)
          ).includes(rev),
        );
        assert.ok(
          (
            await page.$eval(
              "#saved-review-current",
              (node) => node.textContent,
            )
          ).includes(nextRev),
        );
        assert.equal(
          await page.$eval("#manual-baseline", (node) => node.disabled),
          true,
        );
      }
      if (boundary === "generation") {
        await confirm(page, "#accept-latest");
        await page.waitForFunction(
          () => document.getElementById("comparison").hidden,
        );
      }
      await old.respond(issuePage([issueItem()]));
      if (["metadata", "generation"].includes(boundary)) {
        await selectSnapshot(context);
        await readIssuePage(context, issuePage());
        assert.equal(
          await page.$eval(
            "#saved-issues-status",
            (node) => node.dataset.state,
          ),
          "success-empty",
        );
      } else {
        await page.waitForFunction(
          () => !document.getElementById("review-lifecycle-refresh").disabled,
        );
        assert.equal(
          await page.$eval("#saved-issues-result", (node) => node.textContent),
          "",
        );
      }
    }, manualSource());
  }
});

test("PT-A11 historical reads do not require current REVIEW pointer runtime availability or operational validity", async () => {
  for (const status of ["DRAFT", "REVIEW", "READY", "PUBLISHED"]) {
    await isolated(
      async (context) => {
        const { page, requests } = context;
        await selectSnapshot(context);
        await readIssuePage(context, issuePage([issueItem()]));
        assert.equal(issueRequests(requests).length, 1);
        assert.equal(
          issueRequests(requests)[0].query,
          `?snapshotId=${savedId}&size=20`,
        );
        assert.match(
          await page.$eval("#saved-issues-status", (node) => node.textContent),
          /목록 조회 당시 과거/,
        );
        assert.equal(
          requests.filter(({ method }) => method !== "GET" && method !== "POST")
            .length,
          0,
        );
        assert.equal(lifecyclePosts(requests).length, 0);
      },
      { ...manualSource(), status, currentSnapshotId: null },
    );
  }
});

test("PT-A11 401 403 404 audit503 and lost responses remain failures without retry or fabricated empty success", async () => {
  for (const failure of [401, 403, 404, 503, "lost"]) {
    await isolated(async (context) => {
      const { page, hold, requests } = context;
      await selectSnapshot(context);
      await readIssuePage(context, issuePage([issueItem()]));
      const pending = hold(issuesPath, "GET");
      await page.click("#saved-issues-first");
      await pending.ready;
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      if (failure === "lost") await pending.lose();
      else
        await pending.respond(
          {
            code: {
              401: "UNAUTHENTICATED",
              403: "FORBIDDEN",
              404: "NOT_FOUND",
              503: "STORY_UNAVAILABLE",
            }[failure],
          },
          failure,
        );
      if ([401, 403, 404].includes(failure)) {
        await page.waitForSelector("#editor[hidden]");
        assert.equal(
          await page.$eval("#editor", (node) => node.childElementCount),
          0,
        );
        assert.match(
          await page.$eval("#notice", (node) => node.textContent),
          { 401: /세션이 만료/, 403: /접근 권한/, 404: /사건이 없거나/ }[
            failure
          ],
        );
      } else {
        await savedState(page, "issues", "failure");
        assert.equal(
          await page.$eval("#saved-issues-next", (node) => node.disabled),
          true,
        );
        assert.equal(
          await page.$eval("#saved-issues-result", (node) => node.textContent),
          "",
        );
        assert.match(
          await page.$eval("#saved-issues-status", (node) => node.textContent),
          /빈 성공이 아닙니다/,
        );
      }
      assert.equal(issueRequests(requests).length, 2);
    }, manualSource());
  }
});

test("PT-A11 actor or absolute auth-context drift after parsed history purges all old sensitive state", async () => {
  for (const change of [
    { accountKey: "99999999-9999-4999-8999-999999999999" },
    { absoluteExpiresAt: "2026-10-05T10:00:00Z" },
  ]) {
    await isolated(async (context) => {
      const { page, hold, requests } = context;
      await selectSnapshot(context);
      await page.type("#manual-evidence", "Old actor sensitive draft");
      const pending = hold(issuesPath, "GET");
      await page.click("#saved-issues-first");
      await pending.ready;
      const identity = hold(mePath, "GET");
      await pending.respond(issuePage([issueItem()]));
      await identity.ready;
      await identity.respond(identityResponse(change));
      await page.waitForSelector("#editor[hidden]");
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$("#manual-evidence"), null);
      assert.equal(await page.$("#saved-issues-result"), null);
      assert.equal(issueRequests(requests).length, 1);
    }, manualSource());
  }
});

test("PT-A11 filter and failure preserve manuscript child keep buffers and native leave protection", async () => {
  await isolated(
    async (context) => {
      const { page, requests, hold } = context;
      await edit(page, "basic", "intro", "ISSUE_HISTORY_KEEP");
      await page.select("#basic-intro-mode", "keep");
      await page.click('#editor-nav a[href="#child-heading"]');
      await page.click("#child-new");
      await page.waitForSelector("#child-name");
      await page.type("#child-name", "ISSUE_CHILD_BUFFER");
      await page.select("#child-name-mode", "keep");
      const before = await page.$$eval("[data-field], [data-value]", (nodes) =>
        nodes.map((node) => [node.id, node.value]),
      );
      // 새 자료 작성이 초기화한 사본 선택은 버퍼 준비 뒤 공개 이력 조회로 다시 설정한다.
      await selectSnapshot(context);
      await page.select("#saved-issues-filter", "OPEN");
      await page.waitForSelector("#saved-issues-first:not(:disabled)", {
        visible: true,
      });
      const pending = hold(issuesPath, "GET");
      await page.click("#saved-issues-first");
      await pending.ready;
      await pending.lose();
      await savedState(page, "issues", "failure");
      await page.select("#saved-issues-filter", "ALL");
      await readIssuePage(context, issuePage());
      assert.deepEqual(
        await page.$$eval("[data-field], [data-value]", (nodes) =>
          nodes.map((node) => [node.id, node.value]),
        ),
        before,
      );
      assert.equal(await leavePrevented(page), true);
      assert.equal(
        manualPosts(requests).length + lifecyclePosts(requests).length,
        0,
      );
    },
    source({ edit: true, review: true, publish: false }),
  );
});

test("PT-A11 preserves manual A receipt exact replay and newer B draft across filter page and lost history", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json, hold } = context;
    await prepareManual(context);
    await fillManual(page);
    queue(manualPath, "POST", (request) => json(request, manualReceipt(), 201));
    await confirm(page, "#manual-submit");
    await manualMessage(page, "기록 영수증을 확인");
    const original = manualPosts(requests)[0].body;
    const receipt = await page.$eval(
      "#manual-receipt",
      (node) => node.textContent,
    );
    await replaceVisibleInput(page, "#manual-notes", "Newer B remains unsent.");
    await page.select("#saved-issues-filter", "RESOLVED");
    await readIssuePage(
      context,
      issuePage([resolvedIssue({ snapshotId: currentId })]),
    );
    const lost = hold(issuesPath, "GET");
    await page.click("#saved-issues-first");
    await lost.ready;
    await lost.lose();
    await savedState(page, "issues", "failure");
    await page.select("#saved-issues-filter", "ALL");
    assert.equal(
      await page.$eval("#manual-receipt", (node) => node.textContent),
      receipt,
    );
    assert.equal(
      await page.$eval("#manual-notes", (node) => node.value),
      "Newer B remains unsent.",
    );
    queue(manualPath, "POST", (request) =>
      json(request, manualReceipt({ replayed: true })),
    );
    await confirm(page, "#manual-reconfirm");
    await manualMessage(page, "기록 영수증을 확인");
    assert.deepEqual(
      manualPosts(requests).map(({ body }) => body),
      [original, original],
    );
    assert.equal(
      await page.$eval("#manual-notes", (node) => node.value),
      "Newer B remains unsent.",
    );
    assert.equal(await leavePrevented(page), true);
  }, manualSource());
});

test("PT-A11 reads keep unknown SR02 and SR07 intents separate from empty history and read metadata", async () => {
  for (const action of ["REQUEST", "WITHDRAW"]) {
    await isolated(
      async (context) => {
        const { page, hold, requests } = context;
        const path = action === "REQUEST" ? requestPath : returnPath;
        if (action === "WITHDRAW")
          await page.type("#review-withdraw-ref", "synthetic_unknown");
        const pending = hold(path);
        await confirm(
          page,
          action === "REQUEST" ? "#review-request" : "#review-withdraw",
        );
        await pending.ready;
        await pending.lose();
        await lifecycleMessage(page, "결과 미확인");
        const original = lifecyclePosts(requests)[0].body;
        await page.click("#saved-metadata-refresh");
        await savedState(page, "metadata", "success");
        await selectSnapshot(context);
        await page.select("#saved-issues-filter", "OPEN");
        await readIssuePage(context, issuePage());
        assert.equal(
          await page.$eval(
            "#review-lifecycle-receipt",
            (node) => node.dataset.state,
          ),
          "unknown",
        );
        assert.equal(await leavePrevented(page), true);
        assert.deepEqual(
          lifecyclePosts(requests).map(({ body }) => body),
          [original],
        );
        assert.ok(
          (
            await page.$eval("#version-summary", (node) => node.textContent)
          ).includes(rev),
        );
      },
      action === "REQUEST"
        ? source({ edit: true, review: true, publish: false })
        : manualSource(),
    );
  }
});

test("PT-A11 next cursor must descend and a lost next page never reuses its cursor", async () => {
  await isolated(async (context) => {
    const { page, requests, hold } = context;
    await selectSnapshot(context);
    const items = Array.from({ length: 20 }, (_, index) =>
      issueItem({
        issueKey: `44444444-4444-4444-8444-${String(index).padStart(12, "0")}`,
      }),
    );
    for (const cursor of [issueCursor, "9223372036854775807"]) {
      await readIssuePage(context, issuePage(items, issueCursor));
      await readIssuePage(
        context,
        issuePage(items, cursor),
        "#saved-issues-next",
        "failure",
      );
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
    }
    await readIssuePage(context, issuePage(items, issueCursor));
    const pending = hold(issuesPath, "GET");
    await page.click("#saved-issues-next");
    await pending.ready;
    assert.equal(
      await page.$eval("#saved-issues-result", (node) => node.textContent),
      "",
    );
    await pending.lose();
    await savedState(page, "issues", "failure");
    const count = issueRequests(requests).length;
    await page.evaluate(() =>
      document
        .getElementById("saved-issues-next")
        .dispatchEvent(new Event("click")),
    );
    assert.equal(issueRequests(requests).length, count);
    await readIssuePage(context, issuePage());
    assert.equal(
      issueRequests(requests).at(-1).query,
      `?snapshotId=${savedId}&size=20`,
    );
  }, manualSource());
});

test("PT-A11 path replacement purges displayed and pending pages without crossing selected parent", async () => {
  for (const pending of [false, true]) {
    await isolated(async (context) => {
      const { page, requests, hold } = context;
      await selectSnapshot(context);
      await readIssuePage(context, issuePage([issueItem()]));
      let old;
      if (pending) {
        old = hold(issuesPath, "GET");
        await page.click("#saved-issues-first");
        await old.ready;
      }
      // 공개 History API로 다른 문서 경로를 관측한다. 제품 메모리·전송 함수를 바꾸지 않는다.
      await page.evaluate(() => {
        history.pushState(null, "", "/admin/stories/ST_OTHER/versions/2");
        window.dispatchEvent(new PopStateEvent("popstate"));
      });
      await savedState(page, "issues", "stale");
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-issues-first", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
      if (old) await old.respond(issuePage([issueItem()]));
      const before = issueRequests(requests).length;
      await page.evaluate(() =>
        document
          .getElementById("saved-issues-first")
          .dispatchEvent(new Event("click")),
      );
      assert.equal(issueRequests(requests).length, before);
      await page.evaluate(() => {
        history.pushState(null, "", "/admin/stories/ST_REVIEW_TEST/versions/1");
        window.dispatchEvent(new PopStateEvent("popstate"));
      });
      await readIssuePage(context, issuePage());
      assert.equal(
        issueRequests(requests).at(-1).query,
        `?snapshotId=${savedId}&size=20`,
      );
    }, manualSource());
  }
});

test("PT-A11 permission drift purges history and unsent consent but retains owned in-flight SR05 receipts", async () => {
  for (const sent of [false, true]) {
    await isolated(async (context) => {
      const { page, state, requests, hold } = context;
      await prepareManual(context);
      await readIssuePage(
        context,
        issuePage([issueItem({ snapshotId: currentId })]),
      );
      await fillManual(page);
      let waiting;
      if (sent) {
        waiting = hold(manualPath);
        await confirm(page, "#manual-submit");
      } else {
        await page.click("#manual-submit");
        await page.waitForSelector("#ui-confirm-dialog[open]");
        waiting = hold(mePath, "GET");
        await page.click("#ui-confirm-accept");
      }
      await waiting.ready;
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      state.detail.permissions = { edit: true, review: false, publish: false };
      await page.click("#review-lifecycle-refresh");
      await page.waitForFunction(
        () => !document.getElementById("comparison").hidden,
      );
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
      assert.match(
        await page.$eval(
          "#saved-issues-eligibility",
          (node) => node.textContent,
        ),
        /조회 불가/,
      );
      await waiting.respond(
        sent ? manualReceipt() : identityResponse(),
        sent ? 201 : 200,
      );
      await manualMessage(
        page,
        sent ? "기록 영수증을 확인" : "전송하지 않았습니다",
      );
      assert.equal(manualPosts(requests).length, sent ? 1 : 0);
      if (sent)
        assert.equal(
          await page.$eval("#manual-receipt", (node) => node.dataset.state),
          "confirmed",
        );
      assert.equal(
        await page.$eval("#manual-notes", (node) => node.value),
        "Not performed. Synthetic transport fixture only.",
      );
      assert.equal(issueRequests(requests).length, 1);
    }, manualSource());
  }
});

const clonePath = "/admin/api/stories/ST_REVIEW_TEST/drafts";

/** 공개 품질·실제 공개 승인이 아닌 SP05 전송 전용 합성 VersionDetail이다. */
function cloneSource(overrides = {}) {
  return {
    ...source(),
    storyRev: rev,
    editRev: "11",
    status: "PUBLISHED",
    currentSnapshotId: currentId,
    ...overrides,
  };
}

/**
 * 실제 StoryCloneService DTO의 필드만 사용한다. requestId·release·blocked는 만들지 않는다.
 * @param {object} overrides 원래 결과와 현재 결과를 독립적으로 바꾸는 합성 응답.
 */
function cloneResponse(overrides = {}) {
  return {
    actionId: "9223372036854775806",
    action: "CLONE",
    replayed: false,
    changed: true,
    original: {
      versionNo: 2,
      storyRev: nextRev,
      editRev: "0",
      playRev: "7",
      publishedVersionNo: 1,
      viewYn: false,
      sourceVersionNo: 1,
      sourceSnapshotId: currentId,
      createdAt: "2026-10-05T12:00:00.123456+00:00",
    },
    current: {
      storyRev: nextRev,
      playRev: "7",
      viewYn: false,
      publishedVersionNo: 1,
      versionNo: 2,
      status: "DRAFT",
      editRev: "0",
      activeYn: true,
    },
    draftPath: "/admin/stories/ST_REVIEW_TEST/versions/2",
    sourcePolicyCode: "RULE_20260924",
    policyCode: "RULE_20260924",
    policyDifferences: [],
    warnings: [{ code: "POLICY_TIME_RANGE", field: "basic.estMin" }],
    ...overrides,
  };
}

/** 공개 DOM의 완료 문구·활성 조회 버튼으로만 복제 작업 종료를 기다린다. */
async function cloneMessage(page, text) {
  await page.waitForFunction(
    (value) =>
      document.getElementById("clone-status")?.textContent.includes(value) &&
      !document.getElementById("clone-refresh").disabled,
    {},
    text,
  );
}

/** 명시 복제 GET의 완료를 기다리며 원고 비교 수락을 대신하지 않는다. */
async function refreshCloneCandidate(page) {
  await page.click("#clone-refresh");
  await cloneMessage(page, "후보만 갱신");
}

/** 합성 요청 관측에서 SP05의 정확한 POST만 추린다. */
function clonePosts(requests) {
  return requests.filter(
    ({ path, method }) => path === clonePath && method === "POST",
  );
}

/** 닫힌 작업본 충돌을 만들며 선택 notice 외 본문을 추가하지 않는다. */
function workConflict(notice) {
  return {
    code: "WORK_VERSION_EXISTS",
    message: "현재 상태를 다시 확인해 주세요.",
    requestId: "44444444-4444-4444-8444-444444444444",
    ...(notice === undefined ? {} : { notice }),
  };
}

test("SP05 candidate is honest about the absent current pointer and uses detail storyRev rather than selected history", async () => {
  await isolated(async (context) => {
    const { page, requests, queue, json } = context;
    assert.equal(
      await page.$eval("#clone-submit", (node) => node.disabled),
      false,
    );
    assert.match(
      await page.$eval("#clone-target", (node) => node.textContent),
      new RegExp(`후보 버전 1.*${currentId}.*${rev}.*요청 시 서버`),
    );
    assert.equal(requests.filter(({ path }) => path === root).length, 1);
    assert.equal(clonePosts(requests).length, 0);
    await selectSnapshot(context);
    const beforeReads = requests.filter(
      ({ method }) => method === "GET",
    ).length;
    queue(clonePath, "POST", (request) => json(request, cloneResponse()));
    const url = page.url();
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "영수증을 확인");
    const sent = clonePosts(requests);
    assert.equal(sent.length, 1);
    const body = JSON.parse(sent[0].body);
    assert.deepEqual(
      Object.keys(body).sort(),
      [
        "expectedStoryRev",
        "sourceVersionNo",
        "sourceSnapshotId",
        "requestKey",
      ].sort(),
    );
    assert.equal(body.expectedStoryRev, rev);
    assert.equal(body.sourceVersionNo, 1);
    assert.equal(body.sourceSnapshotId, currentId);
    assert.notEqual(body.sourceSnapshotId, savedId);
    assert.match(
      body.requestKey,
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    assert.ok(Buffer.byteLength(sent[0].body) <= 8192);
    assert.equal(sent[0].headers["x-csrf-token"], "synthetic-csrf");
    assert.equal(
      requests.filter(({ method }) => method === "GET").length,
      beforeReads,
    );
    assert.equal(page.url(), url, "성공은 자동 탐색·GET이 아님");
    assert.match(
      await page.$eval("#clone-receipt", (node) => node.textContent),
      /9223372036854775806/,
    );
    assert.match(
      await page.$eval("#clone-policy", (node) => node.textContent),
      /정책 차이 없음.*POLICY_TIME_RANGE/,
    );
    assert.equal(
      await leavePrevented(page),
      false,
      "확정된 깨끗한 영수증은 영구 이탈 차단하지 않음",
    );
  }, cloneSource());
});

test("SP05 gates new intent only on canonical active PUBLISHED edit candidates", async () => {
  for (const overrides of [
    { status: "DRAFT", currentSnapshotId: null },
    { status: "REVIEW" },
    { status: "READY" },
    { storyActiveYn: false },
    { activeYn: false },
    { currentSnapshotId: null },
    { currentSnapshotId: "01" },
    { currentSnapshotId: "9223372036854775808" },
    { storyRev: 3 },
    { storyRev: "00" },
    { permissions: { edit: false, review: true, publish: true } },
  ]) {
    await isolated(async ({ page, requests }) => {
      assert.equal(
        await page.$eval("#clone-submit", (node) => node.disabled),
        true,
      );
      assert.equal(clonePosts(requests).length, 0);
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    }, cloneSource(overrides));
  }
});

test("SP05 Cancel and Escape preserve input and restore shared keyboard focus without POST", async () => {
  await isolated(async ({ page, requests }) => {
    await replaceVisibleInput(
      page,
      "#review-withdraw-ref",
      "clone_cancel_buffer",
    );
    for (const escape of [false, true]) {
      await page.focus("#clone-submit");
      await page.keyboard.press("Enter");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      if (escape) await page.keyboard.press("Escape");
      else await page.click("#ui-confirm-cancel");
      await cloneMessage(page, "취소");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "clone-submit",
      );
      assert.equal(
        await page.$eval("#review-withdraw-ref", (node) => node.value),
        "clone_cancel_buffer",
      );
      assert.equal(clonePosts(requests).length, 0);
    }
  }, cloneSource());
});

test("SP05 rechecks input and observed permission drift after consent while preserving unsent buffers", async () => {
  for (const drift of ["input", "permission"]) {
    await isolated(async ({ page, state, requests, hold }) => {
      await page.click("#clone-submit");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      const me = hold(mePath, "GET");
      await page.click("#ui-confirm-accept");
      await me.ready;
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      if (drift === "input")
        await replaceVisibleInput(
          page,
          "#review-withdraw-ref",
          "changed_after_consent",
        );
      else {
        state.detail.permissions = { edit: false, review: true, publish: true };
        await page.click("#review-lifecycle-refresh");
        await page.waitForFunction(
          () => !document.getElementById("comparison").hidden,
        );
      }
      await me.respond(identityResponse());
      await cloneMessage(page, "전송하지 않았습니다");
      assert.equal(clonePosts(requests).length, 0);
      if (drift === "input")
        assert.equal(
          await page.$eval("#review-withdraw-ref", (node) => node.value),
          "changed_after_consent",
        );
    }, cloneSource());
  }
});

test("SP05 fixed actor or absolute deadline drift purges all before consent and on late response", async () => {
  for (const [stage, changed] of [
    ["before", { accountKey: "22222222-2222-4222-8222-222222222222" }],
    ["after", { absoluteExpiresAt: "2026-10-04T11:00:00Z" }],
    ["inflight", { accountKey: "22222222-2222-4222-8222-222222222222" }],
  ]) {
    await isolated(async ({ page, state, requests, hold }) => {
      await replaceVisibleInput(page, "#review-withdraw-ref", "private_buffer");
      if (stage === "before") {
        state.me = identityResponse(changed);
        await page.click("#clone-submit");
      } else if (stage === "after") {
        await page.click("#clone-submit");
        await page.waitForSelector("#ui-confirm-dialog[open]");
        state.me = identityResponse(changed);
        await page.click("#ui-confirm-accept");
      } else {
        const pending = hold(clonePath);
        await confirm(page, "#clone-submit");
        await pending.ready;
        state.me = identityResponse(changed);
        await pending.respond(cloneResponse());
      }
      await page.waitForFunction(
        () => document.getElementById("editor").hidden,
      );
      assert.equal(clonePosts(requests).length, stage === "inflight" ? 1 : 0);
      assert.equal(await page.$("#clone-receipt"), null);
      assert.equal(await page.$("#review-withdraw-ref"), null);
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    }, cloneSource());
  }
});

test("SP05 CSRF_INVALID keeps the exact command and requires new consent plus Me after fresh CSRF", async () => {
  await isolated(async ({ page, requests, queue, json, hold, state }) => {
    queue(clonePath, "POST", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "확인하지 못했습니다");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "csrf",
    );
    assert.equal(await leavePrevented(page), true);
    const original = clonePosts(requests)[0].body;
    const token = hold("/admin/api/auth/csrf", "GET");
    const pending = hold(clonePath);
    await confirm(page, "#clone-reconfirm");
    await token.ready;
    state.me = identityResponse({ idleExpiresAt: "2026-10-04T04:00:00Z" });
    await token.respond({
      headerName: "X-CSRF-TOKEN",
      token: "fresh-clone-csrf",
    });
    await pending.ready;
    assert.equal(clonePosts(requests).at(-1).body, original);
    assert.equal(
      clonePosts(requests).at(-1).headers["x-csrf-token"],
      "fresh-clone-csrf",
    );
    await pending.respond(cloneResponse());
    await cloneMessage(page, "영수증을 확인");
    assert.equal(await leavePrevented(page), false);
    assert.equal(clonePosts(requests).length, 2);
  }, cloneSource());
});

test("SP05 identity drift while acquiring replacement CSRF prevents the second POST", async () => {
  await isolated(async ({ page, state, requests, queue, json, hold }) => {
    queue(clonePath, "POST", (request) =>
      json(request, { code: "CSRF_INVALID" }, 403),
    );
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "확인하지 못했습니다");
    const token = hold("/admin/api/auth/csrf", "GET");
    await confirm(page, "#clone-reconfirm");
    await token.ready;
    state.me = identityResponse({ absoluteExpiresAt: "2026-10-04T11:00:00Z" });
    await token.respond({
      headerName: "X-CSRF-TOKEN",
      token: "unused-clone-csrf",
    });
    await page.waitForFunction(() => document.getElementById("editor").hidden);
    assert.equal(clonePosts(requests).length, 1);
  }, cloneSource());
});

test("SP05 ordinary 401 403 and 404 retain global private-data purge behavior", async () => {
  for (const status of [401, 403, 404]) {
    await isolated(async ({ page, requests, queue, json }) => {
      queue(clonePath, "POST", (request) =>
        json(request, { code: "FORBIDDEN" }, status),
      );
      await confirm(page, "#clone-submit");
      await page.waitForFunction(
        () => document.getElementById("editor").hidden,
      );
      assert.equal(clonePosts(requests).length, 1);
      assert.equal(await page.$("#clone-link"), null);
      assert.equal(await page.$("#basic-intro"), null);
    }, cloneSource());
  }
});

test("SP05 prior unknown survives a later rejection and current GET with only frozen same-body replay", async () => {
  await isolated(async ({ page, requests, hold, queue, json, state }) => {
    const pending = hold(clonePath);
    await confirm(page, "#clone-submit");
    await pending.ready;
    assert.equal(await leavePrevented(page), true);
    await pending.lose();
    await cloneMessage(page, "확인하지 못했습니다");
    const original = clonePosts(requests)[0].body;
    queue(clonePath, "POST", (request) =>
      json(request, { code: "STORY_UNAVAILABLE" }, 503),
    );
    await confirm(page, "#clone-reconfirm");
    await cloneMessage(page, "확인하지 못했습니다");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "unknown",
    );
    queue(clonePath, "POST", (request) =>
      json(request, { code: "STATE_CONFLICT" }, 409),
    );
    await confirm(page, "#clone-reconfirm");
    await cloneMessage(page, "확인하지 못했습니다");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "unknown",
    );
    state.detail = cloneSource({
      storyRev: nextRev,
      status: "REVIEW",
      currentSnapshotId: savedId,
    });
    await refreshCloneCandidate(page);
    assert.equal(
      await page.$eval("#clone-submit", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#clone-reconfirm", (node) => node.disabled),
      false,
    );
    assert.equal(await leavePrevented(page), true);
    queue(clonePath, "POST", (request) =>
      json(
        request,
        cloneResponse({
          replayed: true,
          changed: false,
          current: {
            storyRev: nextRev,
            playRev: "9",
            viewYn: false,
            publishedVersionNo: null,
          },
          draftPath: undefined,
        }),
      ),
    );
    await page.click("#clone-reconfirm");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    assert.match(
      await page.$eval("#ui-confirm-dialog", (node) => node.textContent),
      /확정되지 않았다면 새 초안을 실제로 생성/,
    );
    await page.click("#ui-confirm-accept");
    await cloneMessage(page, "영수증을 확인");
    assert.equal(clonePosts(requests).length, 4);
    assert.ok(
      clonePosts(requests).every((request) => request.body === original),
    );
    assert.equal(await page.$eval("#clone-link", (node) => node.hidden), true);
    assert.equal(await leavePrevented(page), false);
  }, cloneSource());
});

test("SP05 initially definitive conflict permits a new key only after explicit candidate refresh", async () => {
  await isolated(async ({ page, state, requests, queue, json }) => {
    queue(clonePath, "POST", (request) =>
      json(request, { code: "EDIT_CONFLICT" }, 409),
    );
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "확인하지 못했습니다");
    const original = JSON.parse(clonePosts(requests)[0].body);
    assert.equal(
      await page.$eval("#clone-submit", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$eval("#clone-reconfirm", (node) => node.hidden),
      true,
    );
    assert.equal(await leavePrevented(page), false);
    state.detail = cloneSource({ storyRev: nextRev });
    await refreshCloneCandidate(page);
    assert.equal(clonePosts(requests).length, 1);
    queue(clonePath, "POST", (request) =>
      json(request, { code: "WORK_VERSION_EXISTS" }, 409),
    );
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "작업본이 있습니다");
    const current = JSON.parse(clonePosts(requests)[1].body);
    assert.equal(current.expectedStoryRev, nextRev);
    assert.notEqual(current.requestKey, original.requestKey);
    assert.equal(
      await page.$eval("#version-summary", (node) => node.textContent),
      "버전 1 · 수정번호 11 · 공개 · 활성",
    );
  }, cloneSource());
});

test("SP05 malformed closed DTOs and unsafe success paths remain unknown without leaking arbitrary text", async () => {
  await isolated(async ({ page, requests, queue, json }) => {
    const invalid = [
      (value) => {
        value.requestId = "invented";
      },
      (value) => {
        value.actionId = 9007199254740993;
      },
      (value) => {
        value.actionId = "9223372036854775808";
      },
      (value) => {
        value.action = "PUBLISH";
      },
      (value) => {
        value.original.sourceSnapshotId = savedId;
      },
      (value) => {
        value.original.storyRev = rev;
      },
      (value) => {
        value.original.editRev = "1";
      },
      (value) => {
        value.original.createdAt = "2026-02-30T12:00:00Z";
      },
      (value) => {
        value.current.blocked = false;
      },
      (value) => {
        value.current.playRev = 7;
      },
      (value) => {
        value.draftPath =
          "https://evil.invalid/admin/stories/ST_REVIEW_TEST/versions/2";
      },
      (value) => {
        value.draftPath = "/admin/stories/ST_OTHER/versions/2";
      },
      (value) => {
        value.draftPath = "/admin/stories/ST_REVIEW_TEST/versions/3";
      },
      (value) => {
        value.draftPath += "?unsafe=1";
      },
      (value) => {
        value.policyDifferences = [
          { field: "answer", before: hostile, after: hostile },
        ];
      },
      (value) => {
        value.warnings = [{ code: "MISSING_CONTENT", field: hostile }];
      },
    ];
    for (let index = 0; index < invalid.length; index++) {
      const result = cloneResponse();
      invalid[index](result);
      queue(clonePath, "POST", (request) => json(request, result));
      await confirm(page, index ? "#clone-reconfirm" : "#clone-submit");
      await cloneMessage(page, "확인하지 못했습니다");
      assert.equal(
        await page.$eval("#clone-receipt", (node) => node.dataset.state),
        "unknown",
      );
      assert.equal(
        await page.$eval("#clone-link", (node) => node.hasAttribute("href")),
        false,
      );
      assert.equal(
        await page.$eval("#clone-policy", (node) => node.textContent),
        "",
      );
      assert.equal(await page.$("#clone-panel img"), null);
    }
    queue(clonePath, "POST", (request) => json(request, cloneResponse(), 201));
    await confirm(page, "#clone-reconfirm");
    await cloneMessage(page, "확인하지 못했습니다");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "unknown",
    );
    assert.equal(clonePosts(requests).length, invalid.length + 1);
    assert.equal(
      new Set(clonePosts(requests).map((request) => request.body)).size,
      1,
    );
    assert.equal(await leavePrevented(page), true);
  }, cloneSource());
});

test("SP05 historical replay accepts changed current statuses inactivity and policy metadata as literal text", async () => {
  await isolated(async ({ page, queue, json, requests }) => {
    for (const [index, status] of [
      "DRAFT",
      "REVIEW",
      "READY",
      "PUBLISHED",
    ].entries()) {
      const result = cloneResponse({
        replayed: index > 0,
        changed: index === 0,
        current: {
          storyRev: "9223372036854775807",
          playRev: "99",
          publishedVersionNo: 3,
          viewYn: true,
          versionNo: 2,
          status,
          editRev: "8",
          activeYn: index === 0,
        },
        sourcePolicyCode: "SYNTHETIC_OLD",
        policyCode: "SYNTHETIC_NEW",
        policyDifferences: [
          {
            field: "policyCode",
            before: "SYNTHETIC_OLD",
            after: "SYNTHETIC_NEW",
          },
        ],
      });
      queue(clonePath, "POST", (request) => json(request, result));
      await confirm(page, index ? "#clone-reconfirm" : "#clone-submit");
      await cloneMessage(page, "영수증을 확인");
      assert.match(
        await page.$eval("#clone-receipt", (node) => node.textContent),
        new RegExp(status),
      );
      assert.equal(
        await page.$eval("#clone-link", (node) => node.getAttribute("href")),
        "/admin/stories/ST_REVIEW_TEST/versions/2",
      );
      if (index === 1)
        assert.match(
          await page.$eval("#clone-policy", (node) => node.textContent),
          /policyCode: SYNTHETIC_OLD → SYNTHETIC_NEW/,
        );
    }
    assert.equal(
      new Set(clonePosts(requests).map((request) => request.body)).size,
      1,
    );
    assert.equal(
      await page.$eval("#clone-policy", (node) =>
        node.innerHTML.includes("<script"),
      ),
      false,
    );
    const historical = await page.$eval(
      "#clone-receipt",
      (node) => node.textContent,
    );
    queue(clonePath, "POST", (request) =>
      json(
        request,
        cloneResponse({
          actionId: "9223372036854775805",
          replayed: true,
          changed: false,
          sourcePolicyCode: "SYNTHETIC_OLD",
          policyCode: "SYNTHETIC_NEW",
          policyDifferences: [
            {
              field: "policyCode",
              before: "SYNTHETIC_OLD",
              after: "SYNTHETIC_NEW",
            },
          ],
        }),
      ),
    );
    await confirm(page, "#clone-reconfirm");
    await cloneMessage(page, "확인하지 못했습니다");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.textContent),
      historical,
      "다른 행동 ID 응답이 이미 확정된 원래 영수증을 교체하지 않음",
    );
  }, cloneSource());
});

test("SP05 only closed WORK_VERSION_EXISTS notices offer a safe same-story existing-work link", async () => {
  await isolated(async ({ page, queue, json, requests }) => {
    const variants = [
      workConflict({
        versionNo: 4,
        draftPath: "/admin/stories/ST_REVIEW_TEST/versions/4",
      }),
      workConflict(),
      workConflict({
        versionNo: 4,
        draftPath: "/admin/stories/ST_OTHER/versions/4",
      }),
      workConflict({
        versionNo: 4,
        draftPath: "/admin/stories/ST_REVIEW_TEST/versions/4",
        answer: hostile,
      }),
      {
        ...workConflict({
          versionNo: 4,
          draftPath: "/admin/stories/ST_REVIEW_TEST/versions/4",
        }),
        code: "STATE_CONFLICT",
      },
      {
        ...workConflict({
          versionNo: 4,
          draftPath: "/admin/stories/ST_REVIEW_TEST/versions/4",
        }),
        raw: hostile,
      },
    ];
    for (let index = 0; index < variants.length; index++) {
      if (index) await refreshCloneCandidate(page);
      queue(clonePath, "POST", (request) =>
        json(request, variants[index], 409),
      );
      await confirm(page, "#clone-submit");
      await cloneMessage(
        page,
        variants[index].code === "WORK_VERSION_EXISTS"
          ? "작업본이 있습니다"
          : "확인하지 못했습니다",
      );
      assert.equal(
        await page.$eval("#clone-link", (node) => node.hidden),
        index !== 0,
      );
      if (!index)
        assert.equal(
          await page.$eval("#clone-link", (node) => node.getAttribute("href")),
          "/admin/stories/ST_REVIEW_TEST/versions/4",
        );
      assert.equal(await page.$("#clone-panel img"), null);
    }
    assert.equal(clonePosts(requests).length, variants.length);
  }, cloneSource());
});

test("SP05 owned inflight receipt survives current permission and generation drift", async () => {
  await isolated(async ({ page, state, requests, hold }) => {
    const pending = hold(clonePath);
    await confirm(page, "#clone-submit");
    await pending.ready;
    state.detail.permissions = { edit: false, review: false, publish: false };
    await page.click("#review-lifecycle-refresh");
    await page.waitForFunction(
      () => !document.getElementById("comparison").hidden,
    );
    assert.equal(await leavePrevented(page), true);
    await pending.respond(cloneResponse());
    await cloneMessage(page, "영수증을 확인");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "confirmed",
    );
    assert.equal(
      await page.$eval("#clone-reconfirm", (node) => node.disabled),
      true,
    );
    assert.equal(clonePosts(requests).length, 1);
  }, cloneSource());
});

test("SP05 candidate-only GET and success preserve manuscript child hidden keep and manual inputs", async () => {
  await isolated(
    async ({ page, state, requests, queue, json }) => {
      await edit(page, "basic", "intro", "원고 미저장");
      await page.select("#basic-intro-mode", "keep");
      await page.click('#editor-nav a[href="#child-heading"]');
      await page.click("#child-new");
      await edit(page, "child", "name", "자식 미저장");
      await page.select("#child-name-mode", "keep");
      await fillManual(page, { notes: "별도 기록 입력 B" });
      const before = await page.$eval(
        "#version-summary",
        (node) => node.textContent,
      );
      state.detail = cloneSource();
      const gets = requests.filter(({ path }) => path === root).length;
      await refreshCloneCandidate(page);
      assert.equal(
        requests.filter(({ path }) => path === root).length,
        gets + 1,
      );
      assert.equal(
        await page.$eval("#version-summary", (node) => node.textContent),
        before,
      );
      queue(clonePath, "POST", (request) => json(request, cloneResponse()));
      await confirm(page, "#clone-submit");
      await cloneMessage(page, "영수증을 확인");
      assert.equal(
        await page.$eval("#basic-intro", (node) => node.value),
        "원고 미저장",
      );
      assert.equal(
        await page.$eval("#basic-intro-mode", (node) => node.value),
        "keep",
      );
      assert.equal(
        await page.$eval("#child-name", (node) => node.value),
        "자식 미저장",
      );
      assert.equal(
        await page.$eval("#child-name-mode", (node) => node.value),
        "keep",
      );
      assert.equal(
        await page.$eval("#manual-notes", (node) => node.value),
        "별도 기록 입력 B",
      );
      assert.equal(await leavePrevented(page), true);
    },
    { ...source(), storyRev: "0" },
  );
});

test("SP05 clone GET preserves manual receipt A and new input B without adopting a write baseline", async () => {
  await isolated(
    async (context) => {
      const { page, state, queue, json, requests } = context;
      await prepareManual(context);
      await fillManual(page);
      queue(manualPath, "POST", (request) =>
        json(request, manualReceipt(), 201),
      );
      await confirm(page, "#manual-submit");
      await manualMessage(page, "기록 영수증을 확인");
      const receipt = await page.$eval(
        "#manual-receipt",
        (node) => node.textContent,
      );
      await fillManual(page, { notes: "입력 B 보존" });
      state.detail = cloneSource({
        storyRev: nextRev,
        permissions: { edit: true, review: true, publish: false },
      });
      await refreshCloneCandidate(page);
      assert.equal(
        await page.$eval("#manual-receipt", (node) => node.textContent),
        receipt,
      );
      assert.equal(
        await page.$eval("#manual-notes", (node) => node.value),
        "입력 B 보존",
      );
      assert.match(
        await page.$eval("#version-summary", (node) => node.textContent),
        /검수 중/,
      );
      assert.equal(manualPosts(requests).length, 1);
      assert.equal(
        await page.$eval("#manual-reconfirm", (node) => node.hidden),
        false,
      );
    },
    { ...manualSource(), storyRev: rev },
  );
});

test("SP05 candidate GET never reconciles unknown SR02 or SR07", async () => {
  for (const action of ["REQUEST", "WITHDRAW"]) {
    await isolated(
      async ({ page, state, requests, hold }) => {
        const path = action === "REQUEST" ? requestPath : returnPath;
        const pending = hold(path);
        if (action === "WITHDRAW")
          await replaceVisibleInput(
            page,
            "#review-withdraw-ref",
            "sp05_return_ref",
          );
        await confirm(
          page,
          action === "REQUEST" ? "#review-request" : "#review-withdraw",
        );
        await pending.ready;
        await pending.lose();
        await lifecycleMessage(page, "미확인");
        const receipt = await page.$eval(
          "#review-lifecycle-receipt",
          (node) => node.textContent,
        );
        state.detail = cloneSource();
        await refreshCloneCandidate(page);
        assert.equal(
          await page.$eval(
            "#review-lifecycle-receipt",
            (node) => node.textContent,
          ),
          receipt,
        );
        assert.equal(lifecyclePosts(requests).length, 1);
        assert.equal(await leavePrevented(page), true);
        if (action === "WITHDRAW")
          assert.equal(
            await page.$eval("#review-withdraw-ref", (node) => node.value),
            "sp05_return_ref",
          );
      },
      {
        ...source(),
        storyRev: rev,
        ...(action === "WITHDRAW"
          ? { status: "REVIEW", currentSnapshotId: currentId }
          : {}),
      },
    );
  }
});

test("SP05 explicit safe link preserves the real native unsaved-navigation guard", async () => {
  await isolated(async ({ page, queue, json, requests }) => {
    await replaceVisibleInput(
      page,
      "#review-withdraw-ref",
      "unsaved_return_ref",
    );
    queue(clonePath, "POST", (request) => json(request, cloneResponse()));
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "영수증을 확인");
    const url = page.url();
    assert.equal(
      await page.$eval("#clone-link", (node) => node.getAttribute("href")),
      "/admin/stories/ST_REVIEW_TEST/versions/2",
    );
    const dialog = new Promise((resolve) =>
      page.once("dialog", (value) => resolve(value.type())),
    );
    await page.click("#clone-link");
    assert.equal(await dialog, "beforeunload");
    assert.equal(page.url(), url, "기존 핸들러가 네이티브 이탈을 취소함");
    assert.equal(clonePosts(requests).length, 1);
    assert.equal(
      await page.$eval("#review-withdraw-ref", (node) => node.value),
      "unsaved_return_ref",
    );
  }, cloneSource());
});

test("SP05 path and pagehide dispose frozen intent and ignore owned-page late responses", async () => {
  for (const exit of ["path", "pagehide"]) {
    await isolated(async ({ page, requests, hold }) => {
      const pending = hold(clonePath);
      await confirm(page, "#clone-submit");
      await pending.ready;
      await page.evaluate((kind) => {
        if (kind === "path") {
          history.pushState(null, "", "/admin/stories/ST_OTHER/versions/2");
          window.dispatchEvent(new PopStateEvent("popstate"));
        } else window.dispatchEvent(new Event("pagehide"));
      }, exit);
      const reobserved = page.waitForResponse(
        (response) => new URL(response.url()).pathname === mePath,
      );
      await pending.respond(cloneResponse());
      await reobserved;
      // 응답 뒤 신원 관측과 이미 폐기된 공개 제어 상태를 함께 대조한다.
      await page.waitForFunction(
        () => document.getElementById("clone-reconfirm").hidden,
      );
      assert.equal(
        await page.$eval("#clone-receipt", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#clone-link", (node) => node.hasAttribute("href")),
        false,
      );
      assert.equal(clonePosts(requests).length, 1);
    }, cloneSource());
  }
});

for (const status of [401, 403, 404]) {
  for (const prior of ["initial", "unknown", "confirmed"]) {
    test(`SP05 safety access ${status} REAUTH_REQUIRED purges ${prior} ownership with unchanged Me`, async () => {
      await isolated(
        async ({ page, state, requests, identityRequests, hold }) => {
          const identity = { ...state.me };
          // 편집 가능한 실제 DRAFT에서 입력한 뒤 복제 후보만 PUBLISHED로 조회한다.
          await edit(page, "basic", "intro", "접근 종료 전 비공개 원고");
          await page.select("#basic-intro-mode", "keep");
          await edit(
            page,
            "answer",
            "methodAnswer",
            "접근 종료 전 비공개 정답",
          );
          await page.select("#answer-methodAnswer-mode", "keep");
          await page.click('#editor-nav a[href="#child-heading"]');
          await page.click("#child-new");
          await edit(page, "child", "name", "접근 종료 전 비공개 자식");
          await page.select("#child-name-mode", "keep");
          await replaceVisibleInput(
            page,
            "#manual-notes",
            "접근 종료 전 비공개 수동 입력",
          );
          state.detail = cloneSource();
          await refreshCloneCandidate(page);
          const buffers = [];
          for (const [selector, value] of [
            ["#basic-intro", "접근 종료 전 비공개 원고"],
            ["#answer-methodAnswer", "접근 종료 전 비공개 정답"],
            ["#child-name", "접근 종료 전 비공개 자식"],
            ["#manual-notes", "접근 종료 전 비공개 수동 입력"],
          ]) {
            const node = await page.$(selector);
            assert.ok(node, `${selector} 실제 입력 존재`);
            assert.equal(await node.evaluate((input) => input.value), value);
            buffers.push(node);
          }
          for (const selector of [
            "#basic-intro-mode",
            "#answer-methodAnswer-mode",
            "#child-name-mode",
          ])
            assert.equal(
              await page.$eval(selector, (node) => node.value),
              "keep",
            );

          if (prior !== "initial") {
            const first = hold(clonePath);
            await confirm(page, "#clone-submit");
            await first.ready;
            if (prior === "unknown") {
              await first.lose();
              await cloneMessage(page, "확인하지 못했습니다");
            } else {
              await first.respond(cloneResponse());
              await cloneMessage(page, "영수증을 확인");
              assert.match(
                await page.$eval("#clone-receipt", (node) => node.textContent),
                /9223372036854775806/,
              );
            }
            assert.equal(
              await page.$eval("#clone-receipt", (node) => node.dataset.state),
              prior,
            );
          }
          const denied = hold(clonePath);
          await confirm(
            page,
            prior === "initial" ? "#clone-submit" : "#clone-reconfirm",
          );
          const ownedRequest = await denied.ready;
          assert.equal(ownedRequest.method(), "POST");
          assert.equal(new URL(ownedRequest.url()).pathname, clonePath);
          assert.equal(await leavePrevented(page), true);
          const sent = clonePosts(requests);
          assert.equal(sent.length, prior === "initial" ? 1 : 2);
          assert.ok(sent.every((request) => request.body === sent[0].body));
          const command = JSON.parse(sent[0].body);
          assert.equal(command.expectedStoryRev, rev);
          assert.equal(command.sourceSnapshotId, currentId);
          assert.equal(command.sourceVersionNo, 1);
          assert.match(
            command.requestKey,
            /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
          );

          // 소유 중인 복제 응답이 접근을 종료한 뒤, 이미 전송한 상세 GET도 폐기해야 한다.
          const late = hold(root, "GET");
          await page.click("#review-lifecycle-refresh");
          await late.ready;
          const identityReads = identityRequests.length;
          const deniedResponse = page.waitForResponse(
            (response) => response.request() === ownedRequest,
          );
          await denied.respond({ code: "REAUTH_REQUIRED" }, status);
          assert.equal((await deniedResponse).status(), status);
          await page.waitForFunction(() => {
            const editor = document.getElementById("editor");
            return (
              editor.hidden ||
              document
                .getElementById("clone-panel")
                ?.getAttribute("aria-busy") === "false"
            );
          });
          assert.deepEqual(
            state.me,
            identity,
            "행위자·절대 기한을 바꾸지 않음",
          );
          assert.ok(
            identityRequests.length > identityReads,
            "응답 뒤 동일 Me 재관측",
          );
          assert.equal(
            await page.$eval("#editor", (node) => node.hidden),
            true,
            `소유한 SP05 ${status}+REAUTH_REQUIRED는 재인증 예외가 아닌 접근 종료`,
          );
          for (const node of buffers)
            assert.equal(
              await node.evaluate((input) => input.value),
              "",
              "DOM 제거만이 아니라 분리된 입력 노드의 비공개 값도 비움",
            );
          assert.equal(
            await leavePrevented(page),
            false,
            "미확인 복제 동결도 폐기",
          );
          assert.equal(await page.$("#ui-confirm-dialog"), null);
          for (const selector of [
            "#clone-receipt",
            "#clone-policy",
            "#clone-link",
            "#clone-submit",
            "#clone-reconfirm",
            "#basic-intro",
            "#answer-methodAnswer",
            "#child-name",
            "#manual-notes",
          ])
            assert.equal(await page.$(selector), null);

          const afterLoss = requests.length;
          const lateResponse = page.waitForResponse(
            (response) =>
              new URL(response.url()).pathname === root &&
              response.request().method() === "GET",
          );
          await late.respond(state.detail);
          await (await lateResponse).text();
          assert.equal(
            await page.$eval("#editor", (node) => node.childElementCount),
            0,
            "늦은 상세 응답은 원고·입력·복제 제어를 복원하지 않음",
          );
          assert.equal(
            await page.$eval("#editor", (node) => node.hidden),
            true,
          );
          assert.equal(await leavePrevented(page), false);
          assert.equal(
            requests.length,
            afterLoss,
            "자동 조회·재전송·탐색 없음",
          );
          assert.equal(clonePosts(requests).length, sent.length);
          assert.ok(
            clonePosts(requests).every(
              (request) => request.body === sent[0].body,
            ),
            "새 키나 다른 본문을 만들지 않음",
          );
          assert.deepEqual(state.me, identity);
        },
        { ...source(), storyRev: rev },
      );
    });
  }
}

test("SP05 safety confirmed replay cannot be overwritten by NEW on the same exact command", async () => {
  await isolated(async ({ page, requests, queue, json }) => {
    // 첫 응답부터 서버의 과거 성공 재생을 확인하여 두 POST만으로 표시 역행을 관찰한다.
    const confirmedA = cloneResponse({ replayed: true, changed: false });
    const start = requests.length;
    const url = page.url();
    queue(clonePath, "POST", (request) => json(request, confirmedA));
    await confirm(page, "#clone-submit");
    await cloneMessage(page, "영수증을 확인");
    const receiptA = await page.$eval(
      "#clone-receipt",
      (node) => node.textContent,
    );
    const policyA = await page.$eval(
      "#clone-policy",
      (node) => node.textContent,
    );
    const linkA = await page.$eval("#clone-link", (node) =>
      node.getAttribute("href"),
    );
    assert.match(receiptA, /9223372036854775806.*과거 성공 재생/);
    assert.match(
      receiptA,
      new RegExp(`생성 당시 버전 2 / 사건 수정번호 ${nextRev}`),
    );
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "confirmed",
    );
    assert.equal(await leavePrevented(page), false);
    const first = clonePosts(requests)[0];
    const bodyA = JSON.parse(first.body);
    queue(clonePath, "POST", (request) =>
      json(request, { ...confirmedA, replayed: false, changed: true }),
    );
    await confirm(page, "#clone-reconfirm");
    await cloneMessage(page, "");
    const sent = clonePosts(requests);
    assert.equal(sent.length, 2);
    assert.equal(sent[1].path, first.path);
    assert.equal(sent[1].body, first.body);
    assert.equal(JSON.parse(sent[1].body).requestKey, bodyA.requestKey);
    assert.equal(
      requests.slice(start).filter(({ method }) => method === "GET").length,
      0,
      "Me 외 추가 업무 GET 없이 원래 명령만 재확인",
    );
    assert.deepEqual(
      requests.slice(start),
      sent,
      "두 POST 외 자동 탐색·요청 없음",
    );
    assert.equal(page.url(), url);
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.textContent),
      receiptA,
      "같은 actionId·original·정책이라도 확정 키의 새 생성 주장은 재생 영수증을 교체할 수 없음",
    );
    assert.equal(
      await page.$eval("#clone-policy", (node) => node.textContent),
      policyA,
    );
    assert.equal(
      await page.$eval("#clone-link", (node) => node.getAttribute("href")),
      linkA,
    );
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "confirmed",
    );
    assert.match(
      await page.$eval("#clone-status", (node) => node.textContent),
      /확인하지 못했습니다/,
    );
    assert.equal(await leavePrevented(page), false);
  }, cloneSource());
});

test("SP05 safety confirmed boundary still allows unconfirmed unknown to initial NEW", async () => {
  await isolated(async ({ page, requests, hold }) => {
    const first = hold(clonePath);
    await confirm(page, "#clone-submit");
    await first.ready;
    await first.lose();
    await cloneMessage(page, "확인하지 못했습니다");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "unknown",
    );
    assert.equal(await leavePrevented(page), true);
    const original = clonePosts(requests)[0];
    const start = requests.length;
    const url = page.url();
    const pending = hold(clonePath);
    await confirm(page, "#clone-reconfirm");
    await pending.ready;
    await pending.respond(cloneResponse());
    await cloneMessage(page, "영수증을 확인");
    assert.equal(
      await page.$eval("#clone-receipt", (node) => node.dataset.state),
      "confirmed",
    );
    assert.match(
      await page.$eval("#clone-receipt", (node) => node.textContent),
      /9223372036854775806.*새 생성/,
    );
    assert.equal(clonePosts(requests).length, 2);
    assert.equal(clonePosts(requests)[1].path, original.path);
    assert.equal(clonePosts(requests)[1].body, original.body);
    assert.deepEqual(requests.slice(start), [clonePosts(requests)[1]]);
    assert.equal(page.url(), url);
    assert.equal(await leavePrevented(page), false);
  }, cloneSource());
});

test("SP05 panel and real shared confirmation keep five-width accessibility without new styles", async () => {
  await isolated(async ({ page }) => {
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900 });
      const layout = await page.$eval("#clone-panel", (panel) => ({
        labelled: Boolean(
          document.getElementById(panel.getAttribute("aria-labelledby")),
        ),
        overflow: document.documentElement.scrollWidth > innerWidth,
        reading: panel.querySelector(".reading").getBoundingClientRect().width,
        buttons: [...panel.querySelectorAll("button")]
          .filter((node) => !node.hidden)
          .map((node) => ({
            width: node.getBoundingClientRect().width,
            height: node.getBoundingClientRect().height,
            text: node.textContent.trim(),
          })),
      }));
      assert.equal(layout.labelled, true);
      assert.equal(layout.overflow, false, `${width}px 복제 패널 넘침`);
      assert.ok(layout.reading <= 720);
      assert.ok(
        layout.buttons.every(
          (button) => button.text && button.width >= 48 && button.height >= 48,
        ),
      );
      await page.focus("#clone-submit");
      await page.keyboard.press("Enter");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      const dialog = await page.$eval("#ui-confirm-dialog", (node) => ({
        overflow: node.scrollWidth > node.clientWidth,
        modal: node.getAttribute("aria-modal"),
        left: node.getBoundingClientRect().left,
        right: node.getBoundingClientRect().right,
      }));
      assert.equal(dialog.overflow, false);
      assert.ok(dialog.left >= 0 && dialog.right <= width);
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-accept",
      );
      assert.equal(
        await page.$eval("#ui-confirm-accept", (node) =>
          node.matches(":focus-visible"),
        ),
        true,
      );
      await page.keyboard.press("Escape");
      await cloneMessage(page, "취소");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "clone-submit",
      );
    }
  }, cloneSource());
});

const resolutionIssueKey = "44444444-4444-4444-8444-444444444444";
const resolutionBatchKey = "77777777-7777-4777-8777-777777777777";
const resolutionPath = `${issuesPath}/${resolutionIssueKey}/resolve`;
const resolutionBatchPath = `${root}/regressions/${resolutionBatchKey}`;

/** PT-A12 실제 API 성공을 주장하지 않는 닫힌 합성 영수증이다. */
function resolutionReceipt(replayed = false, overrides = {}) {
  const original = {
    issueKey: resolutionIssueKey, snapshotId: currentId, editRev: rev,
    state: "RESOLVED", targetBatchKey: resolutionBatchKey, resolvedAt: "2026-10-02T00:00:00Z",
  };
  return { action: "ISSUE_RESOLVE", replayed, changed: !replayed,
    original, current: { ...original }, requestId: "88888888-8888-4888-8888-888888888888", ...overrides };
}

/** 실제 실행·운영 유효성을 만들지 않는 전체 비민감 BatchDetail 합성 전송이다. */
function resolutionBatchFixture() {
  return {
    batchKey: resolutionBatchKey, purpose: "REVIEW", snapshotId: currentId,
    runtimeConfigId: "SYNTHETIC_REVIEW", runtimeEpoch: "9007199254740993000",
    datasetHash: "a".repeat(64), state: "COMPLETED", passed: true,
    repeatCount: 3, totalJobs: 3, completedJobs: 3, failedComparisons: 0, unresolvedJobs: 0,
    createdAt: "2026-10-01T00:00:00Z", batchDeadline: "2026-10-02T00:00:00Z",
    completedAt: "2026-10-01T00:01:00Z", validUntil: null,
    items: [1, 2, 3].map((repeatNo) => ({
      sampleCode: "SAMPLE", repeatNo, jobKey: `99999999-9999-4999-8999-99999999999${repeatNo}`,
      state: "COMPLETED", comparison: "PASS", errorCode: null,
    })),
    requestId: "88888888-8888-4888-8888-888888888888",
  };
}

/** 실제 입력 이벤트로 비개인 해소 버퍼만 변경한다. */
async function resolutionInput(page, id, value) {
  await page.$eval(`#issue-resolution-${id}`, (node, text) => {
    node.value = text;
    node.dispatchEvent(new Event("input", { bubbles: true }));
  }, value);
}

/** 실제 이력 조회·선택·보이는 지적 버튼만으로 현재 새 해소 후보를 준비한다. */
async function prepareResolution(context, kind = "GRADING") {
  await selectSnapshot(context);
  await context.page.click(`[data-snapshot-id="${currentId}"]`);
  await readIssuePage(context, issuePage([issueItem({ snapshotId: currentId, kind })]));
  await context.page.click(`[data-resolution-issue="${resolutionIssueKey}"]`);
  await resolutionInput(context.page, "batch", resolutionBatchKey);
  await resolutionInput(context.page, "ref", "synthetic_verified");
}

/** 공개 상태와 작업 종료를 함께 기다려 후속 재확인과 경쟁하지 않는다. */
async function resolutionMessage(page, text) {
  await page.waitForFunction((fragment) =>
    document.getElementById("issue-resolution-status")?.textContent.includes(fragment) &&
    document.getElementById("issue-resolution").getAttribute("aria-busy") === "false",
  {}, text);
}

/** PT-A12 전송만 분리하며 신원 관측은 업무 자동 조회로 세지 않는다. */
function resolutionPosts(requests) {
  return requests.filter(({ method, path }) => method === "POST" && path.endsWith("/resolve"));
}

test("PT-A12 explicit detail and NEW use exact six fields without revision adoption, prefetch or XSS", async () => {
  for (const kind of ["GRADING", "INFRA"]) {
    await isolated(async (context) => {
      const { page, queue, json, requests } = context;
      const initial = requests.length;
      await prepareResolution(context, kind);
      assert.equal(requests.filter(({ path }) => path.includes("/regressions/")).length, 0);
      assert.equal(await page.evaluate(() => window.injected), undefined);
      queue(resolutionBatchPath, "GET", (request) => json(request, resolutionBatchFixture()));
      await page.click("#issue-resolution-batch-read");
      await page.waitForFunction(() =>
        document.getElementById("issue-resolution-batch-status").textContent.includes("관측 완료"));
      assert.deepEqual(JSON.parse(await page.$eval("#issue-resolution-batch-detail", (node) => node.textContent)), resolutionBatchFixture());
      const before = await page.$eval("#saved-review-current", (node) => node.textContent);
      queue(resolutionPath, "POST", (request) => json(request, resolutionReceipt()));
      await confirm(page, "#issue-resolution-submit");
      await resolutionMessage(page, "영수증을 확인");
      const posts = resolutionPosts(requests);
      assert.equal(posts.length, 1);
      const body = JSON.parse(posts[0].body);
      assert.deepEqual(Object.keys(body).sort(), ["expectedRev", "requestKey", "reasonCode", "verificationRef", "targetBatchKey", "targetReviewId"].sort());
      assert.equal(body.expectedRev, rev);
      assert.equal(body.targetBatchKey, resolutionBatchKey);
      assert.equal(body.targetReviewId, null);
      assert.equal(body.reasonCode, kind === "INFRA" ? "INFRA_RECOVERED" : "GRADING_FIX_VERIFIED");
      assert.match(body.requestKey, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      assert.equal(await page.$eval("#saved-review-current", (node) => node.textContent), before);
      assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "confirmed");
      assert.equal(await leavePrevented(page), false);
      assert.deepEqual(requests.slice(initial).filter(({ path }) => /preview|\/records|\/regressions$/.test(path)), []);
      assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
    }, manualSource());
  }
});

test("PT-A12 unknown A retains immutable replay through B edits, vanished page, deterministic rejection and DRAFT replacement", async () => {
  await isolated(async (context) => {
    const { page, hold, queue, json, state, requests } = context;
    await prepareResolution(context);
    const lost = hold(resolutionPath);
    await confirm(page, "#issue-resolution-submit");
    await lost.lose();
    await resolutionMessage(page, "결과 미확인");
    const first = resolutionPosts(requests)[0];
    await resolutionInput(page, "batch", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    await resolutionInput(page, "ref", "buffer_B_verified");
    await page.$eval("#manual-notes", (node) => {
      node.value = "보존된 별도 수동 입력";
      node.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await page.select("#saved-issues-filter", "RESOLVED");
    await readIssuePage(context, issuePage());
    assert.match(await page.$eval("#issue-resolution-source", (node) => node.textContent), new RegExp(resolutionIssueKey));
    queue(resolutionPath, "POST", (request) => json(request, { code: "EDIT_CONFLICT" }, 409));
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "요청 거절");
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "unknown");
    state.detail = { ...state.detail, status: "DRAFT", editRev: nextRev, currentSnapshotId: null };
    await acceptLifecycle(page);
    const replayed = resolutionReceipt(true);
    replayed.current.editRev = nextRev;
    queue(resolutionPath, "POST", (request) => json(request, replayed));
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "영수증을 확인");
    assert.ok(resolutionPosts(requests).every((post) => post.path === first.path && post.body === first.body));
    assert.match(await page.$eval("#issue-resolution-receipt", (node) => node.textContent), new RegExp(`최초 수정번호 ${rev} / 현재 관측 수정번호 ${nextRev}`));
    assert.equal(await page.$eval("#issue-resolution-batch", (node) => node.value), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    assert.equal(await page.$eval("#issue-resolution-ref", (node) => node.value), "buffer_B_verified");
    assert.equal(await page.$eval("#manual-notes", (node) => node.value), "보존된 별도 수동 입력");
    assert.equal(await leavePrevented(page), true);
  }, manualSource());
});

test("PT-A12 malformed successes never become receipts and confirmed original cannot regress", async () => {
  await isolated(async (context) => {
    const { page, queue, json, requests } = context;
    await prepareResolution(context);
    const valid = resolutionReceipt();
    const invalid = [
      { ...valid, extra: "PRIVATE" }, { ...valid, requestId: undefined },
      { ...valid, changed: false }, { ...valid, action: "BATCH_CREATE" },
      { ...valid, original: { ...valid.original, editRev: 9007199254740992 } },
      { ...valid, original: { ...valid.original, snapshotId: "01" } },
      { ...valid, current: { ...valid.current, editRev: "9223372036854775808" } },
      { ...valid, current: { ...valid.current, extra: "PRIVATE" } },
      { ...valid, current: { ...valid.current, state: "OPEN" } },
      { ...valid, original: { ...valid.original, resolvedAt: "2026-02-30T00:00:00Z" } },
      { ...valid, current: { ...valid.current, targetBatchKey: "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA" } },
    ];
    for (const [index, data] of invalid.entries()) {
      queue(resolutionPath, "POST", (request) => json(request, data));
      await confirm(page, index ? "#issue-resolution-replay" : "#issue-resolution-submit");
      await resolutionMessage(page, "결과 미확인");
      assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "unknown");
      assert.doesNotMatch(await page.$eval("#issue-resolution-receipt", (node) => node.textContent), /PRIVATE/);
    }
    queue(resolutionPath, "POST", (request) => json(request, valid));
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "영수증을 확인");
    const receipt = await page.$eval("#issue-resolution-receipt", (node) => node.textContent);
    queue(resolutionPath, "POST", (request) => json(request, valid));
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "결과 미확인");
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.textContent), receipt);
    assert.ok(resolutionPosts(requests).every((post) => post.body === resolutionPosts(requests)[0].body));
  }, manualSource());
});

test("PT-A12 unsupported kinds, historical and resolved rows stay read-only while populated text stays literal", async () => {
  await isolated(async (context) => {
    const { page, requests } = context;
    await selectSnapshot(context);
    await readIssuePage(context, issuePage([issueItem()]));
    assert.equal(await page.$eval("[data-resolution-issue]", (node) => node.disabled), true);
    await page.click(`[data-snapshot-id="${currentId}"]`);
    for (const item of [
      issueItem({ snapshotId: currentId, kind: "CONTENT" }),
      issueItem({ snapshotId: currentId, kind: "OBSERVATION" }),
      resolvedIssue({ snapshotId: currentId }),
    ]) {
      await readIssuePage(context, issuePage([item]));
      assert.equal(await page.$("[data-resolution-issue]"), null);
      assert.equal(await page.$eval("#issue-resolution-submit", (node) => node.disabled), true);
      const stored = JSON.parse(await page.$eval("#saved-issues-result", (node) => node.textContent));
      assert.ok(stored[0].runtimeConfigId.includes(hostile));
      assert.equal(await page.$("#saved-issues-result img"), null);
    }
    assert.equal(await page.evaluate(() => window.injected), undefined);
    assert.deepEqual(resolutionPosts(requests), []);
    assert.equal(requests.some(({ path }) => path.includes("/regressions/")), false);
  }, manualSource());
});

test("PT-A12 cancel, form mutation and CSRF acquisition mutation never authorize stale intent", async () => {
  await isolated(async (context) => {
    const { page, hold, queue, json, requests } = context;
    await prepareResolution(context);
    await page.focus("#issue-resolution-submit");
    await page.keyboard.press("Enter");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.keyboard.press("Escape");
    await resolutionMessage(page, "전송하지 않았");
    assert.equal(await page.evaluate(() => document.activeElement.id), "issue-resolution-submit");
    await page.click("#issue-resolution-submit");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await resolutionInput(page, "ref", "changed_in_consent");
    await resolutionMessage(page, "전송하지 않았");
    assert.deepEqual(resolutionPosts(requests), []);
    queue(resolutionPath, "POST", (request) => json(request, { code: "CSRF_INVALID" }, 403));
    await confirm(page, "#issue-resolution-submit");
    await resolutionMessage(page, "CSRF 거절");
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "csrf");
    const token = hold("/admin/api/auth/csrf", "GET");
    await confirm(page, "#issue-resolution-replay");
    await token.ready;
    await resolutionInput(page, "ref", "changed_during_csrf");
    await token.respond({ headerName: "X-CSRF-TOKEN", token: "new-synthetic" });
    await resolutionMessage(page, "전송하지 않았");
    assert.equal(resolutionPosts(requests).length, 1);
    queue(resolutionPath, "POST", (request) => json(request, resolutionReceipt()));
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "영수증을 확인");
    assert.equal(resolutionPosts(requests)[1].body, resolutionPosts(requests)[0].body);
    assert.equal(await page.$eval("#issue-resolution-ref", (node) => node.value), "changed_during_csrf");
  }, manualSource());
});

test("PT-A12 generation replacement rejects late success while keeping original replay and B buffers", async () => {
  await isolated(async (context) => {
    const { page, hold, queue, json, requests } = context;
    await prepareResolution(context);
    const pending = hold(resolutionPath);
    await confirm(page, "#issue-resolution-submit");
    await pending.ready;
    await resolutionInput(page, "ref", "buffer_after_send");
    await acceptLifecycle(page);
    await pending.respond(resolutionReceipt());
    queue(resolutionPath, "POST", (request) => json(request, resolutionReceipt(true)));
    await page.waitForFunction(() => !document.getElementById("issue-resolution-replay").disabled);
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "unknown");
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "영수증을 확인");
    assert.equal(resolutionPosts(requests)[1].body, resolutionPosts(requests)[0].body);
    assert.equal(await page.$eval("#issue-resolution-ref", (node) => node.value), "buffer_after_send");
  }, manualSource());
});

for (const changed of ["account", "absoluteExpiry", "permission"]) {
  test(`PT-A12 ${changed} change cancels consent without source or key substitution`, async () => {
    await isolated(async (context) => {
      const { page, state, requests } = context;
      await prepareResolution(context);
      await page.click("#issue-resolution-submit");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      if (changed === "permission") {
        state.detail = { ...state.detail, permissions: { edit: true, review: false, publish: false } };
        await page.$eval("#review-lifecycle-refresh", (node) => node.click());
        await page.waitForFunction(() => document.getElementById("review-permissions").textContent.includes("검수 없음"));
        assert.equal(await page.$eval("#issue-resolution-submit", (node) => node.disabled), true);
        assert.equal(await page.$eval("#issue-resolution-ref", (node) => node.value), "synthetic_verified");
      } else {
        state.me = { ...state.me, ...(changed === "account"
          ? { accountKey: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }
          : { absoluteExpiresAt: "2026-10-05T10:00:00Z" }) };
        await page.click("#ui-confirm-accept");
        await page.waitForFunction(() => document.getElementById("editor").hidden);
        assert.equal(await page.$("#issue-resolution-form"), null);
      }
      assert.deepEqual(resolutionPosts(requests), []);
    }, manualSource());
  });
}

test("PT-A12 late JSON identity change never restores receipt or input", async () => {
  await isolated(async (context) => {
    const { page, hold, state } = context;
    await prepareResolution(context);
    const pending = hold(resolutionPath);
    await confirm(page, "#issue-resolution-submit");
    await pending.ready;
    state.me = { ...state.me, accountKey: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" };
    await pending.respond(resolutionReceipt());
    await page.waitForFunction(() => document.getElementById("editor").hidden);
    assert.equal(await page.$("#issue-resolution-receipt"), null);
    assert.equal(await leavePrevented(page), false);
  }, manualSource());
});

test("PT-A12 explicit STAGED pending detail is observation only without POST or write revision adoption", async () => {
  await isolated(async (context) => {
    const { page, queue, json, requests } = context;
    await prepareResolution(context);
    const detail = resolutionBatchFixture();
    Object.assign(detail, { state: "RUNNING", passed: null, completedJobs: 0,
      unresolvedJobs: detail.totalJobs, completedAt: null });
    for (const item of detail.items) Object.assign(item, { state: "STAGED", comparison: "PENDING" });
    const start = requests.length;
    const before = await page.$eval("#saved-review-current", (node) => node.textContent);
    const receipt = await page.$eval("#issue-resolution-receipt", (node) => node.textContent);
    queue(resolutionBatchPath, "GET", (request) => json(request, detail));
    await page.click("#issue-resolution-batch-read");
    await page.waitForFunction(() => document.getElementById("issue-resolution-batch-status").textContent.includes("관측 완료"));
    const observed = JSON.parse(await page.$eval("#issue-resolution-batch-detail", (node) => node.textContent));
    assert.equal(observed.state, "RUNNING");
    assert.equal(observed.passed, null);
    assert.ok(observed.items.every((item) => item.state === "STAGED" && item.comparison === "PENDING"));
    assert.equal(await page.$eval("#saved-review-current", (node) => node.textContent), before);
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.textContent), receipt);
    assert.deepEqual(resolutionPosts(requests), []);
    assert.deepEqual(requests.slice(start).map(({ method, path }) => ({ method, path })),
      [{ method: "GET", path: resolutionBatchPath }]);
  }, manualSource());
});

test("PT-A12 explicit batch detail rejects missing extra unsafe and hostile fields without fallback", async () => {
  await isolated(async (context) => {
    const { page, queue, json, requests } = context;
    await prepareResolution(context);
    const valid = resolutionBatchFixture();
    for (const bad of [
      { ...valid, extra: hostile }, { ...valid, validUntil: undefined },
      { ...valid, runtimeEpoch: 9007199254740992 }, { ...valid, totalJobs: 1.5 },
      { ...valid, items: [{ ...valid.items[0], extra: hostile }, ...valid.items.slice(1)] },
      { ...valid, items: [{ ...valid.items[0], sampleCode: hostile }, ...valid.items.slice(1)] },
    ]) {
      queue(resolutionBatchPath, "GET", (request) => json(request, bad));
      await page.click("#issue-resolution-batch-read");
      await page.waitForFunction(() =>
        document.getElementById("issue-resolution-batch-status").textContent.includes("조회 실패") &&
        document.getElementById("issue-resolution").getAttribute("aria-busy") === "false");
      assert.equal(await page.$eval("#issue-resolution-batch-detail", (node) => node.textContent), "");
    }
    assert.deepEqual(resolutionPosts(requests), []);
    assert.equal(await page.evaluate(() => window.injected), undefined);
  }, manualSource());
});

test("PT-A12 read metadata and replacement preserve parent child buffers and unknown original without adopting write rev", async () => {
  await isolated(async (context) => {
    const { page, state, queue, json, hold, requests } = context;
    await page.click('#editor-nav a[href="#child-heading"]');
    await page.click("#child-new");
    await edit(page, "child", "name", "보존할 자식 입력");
    await edit(page, "basic", "intro", "보존할 부모 입력");
    await page.select("#basic-intro-mode", "keep");
    state.detail = manualSource();
    await acceptLifecycle(page);
    await prepareResolution(context);
    const pending = hold(resolutionPath);
    await confirm(page, "#issue-resolution-submit");
    await pending.lose();
    await resolutionMessage(page, "결과 미확인");
    const command = resolutionPosts(requests)[0].body;
    await resolutionInput(page, "ref", "replacement_buffer_B");
    queue(root, "GET", (request) => json(request, { code: "STORY_UNAVAILABLE" }, 503));
    await page.click("#review-lifecycle-refresh");
    await page.waitForSelector("#saved-metadata-refresh:not(:disabled)", { visible: true });
    state.detail = { ...manualSource(), editRev: nextRev, currentSnapshotId: savedId };
    await page.click("#saved-metadata-refresh");
    await savedState(page, "metadata", "success");
    const text = await page.$eval("#saved-review-current", (node) => node.textContent);
    assert.match(text, new RegExp(`현재 상세 수정번호${nextRev}`));
    assert.match(text, new RegExp(`원고 쓰기 기준 수정번호 \\(자동 변경 없음\\)${rev}`));
    assert.equal(await page.$eval("#issue-resolution-submit", (node) => node.disabled), true);
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "unknown");
    const receipt = resolutionReceipt(true);
    receipt.current.editRev = nextRev;
    queue(resolutionPath, "POST", (request) => json(request, receipt));
    await confirm(page, "#issue-resolution-replay");
    await resolutionMessage(page, "영수증을 확인");
    assert.equal(resolutionPosts(requests)[1].body, command);
    assert.equal(await page.$eval("#basic-intro", (node) => node.value), "보존할 부모 입력");
    assert.equal(await page.$eval("#child-name", (node) => node.value), "보존할 자식 입력");
    assert.equal(await page.$eval("#issue-resolution-ref", (node) => node.value), "replacement_buffer_B");
    assert.equal(await page.$eval("#saved-review-current", (node) => node.textContent), text);
  }, source({ edit: true, review: true, publish: false }));
});

test("PT-A12 foreign evidence is a deterministic rejection, not editor revocation or inferred resolution", async () => {
  await isolated(async (context) => {
    const { page, queue, json, requests } = context;
    await prepareResolution(context);
    queue(resolutionBatchPath, "GET", (request) => json(request, { code: "NOT_FOUND" }, 404));
    await page.click("#issue-resolution-batch-read");
    await page.waitForFunction(() =>
      document.getElementById("issue-resolution-batch-status").textContent.includes("조회 실패"));
    assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
    queue(resolutionPath, "POST", (request) => json(request, { code: "NOT_FOUND" }, 404));
    await confirm(page, "#issue-resolution-submit");
    await resolutionMessage(page, "요청 거절");
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.dataset.state), "rejected");
    assert.equal(await page.$eval("#editor", (node) => node.hidden), false);
    assert.equal(resolutionPosts(requests).length, 1);
    assert.equal(await page.$eval("#issue-resolution-replay", (node) => node.hidden), true);
    await confirm(page, "#issue-resolution-discard");
    await resolutionMessage(page, "명시 폐기");
    assert.equal(await page.$eval("#issue-resolution-receipt", (node) => node.textContent), "");
    assert.equal(resolutionPosts(requests).length, 1);
  }, manualSource());
});

test("PT-A12 rejects noncanonical target keys and references before consent or POST", async () => {
  await isolated(async (context) => {
    const { page, requests } = context;
    await prepareResolution(context);
    for (const [batch, ref] of [
      ["AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "synthetic_verified"],
      ["77777777-7777-1777-8777-777777777777", "synthetic_verified"],
      [resolutionBatchKey, "짧은참조"],
      [resolutionBatchKey, "with/slash"],
    ]) {
      await resolutionInput(page, "batch", batch);
      await resolutionInput(page, "ref", ref);
      await page.click("#issue-resolution-submit");
      await resolutionMessage(page, "전송하지 않았");
      assert.equal(await page.$("#ui-confirm-dialog"), null);
    }
    assert.deepEqual(resolutionPosts(requests), []);
  }, manualSource());
});
