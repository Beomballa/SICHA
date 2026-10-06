import puppeteer from "puppeteer-core";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { captureReportPreview } from "./story-report-preview.mjs";
import { exerciseRolePairs } from "./story-role-pair.mjs";
import { exerciseClueAssignments } from "./story-clue-assignment.mjs";
import { exerciseHints } from "./story-hints.mjs";
import { exerciseEvents } from "./story-events.mjs";
import { exerciseFacts } from "./story-facts.mjs";
import { exerciseRubrics } from "./story-rubrics.mjs";
import { exerciseGradeSamples } from "./story-grade-samples.mjs";
import { exerciseReviewChecks } from "./story-review-check.mjs";
import { exerciseStoryAccess } from "./story-access.mjs";
import { exerciseStoryOwnership } from "./story-ownership.mjs";
import {
  installConfirmationDriver,
  clickWithConfirmation,
  selectChildResource,
  waitForConfirmation,
} from "./confirmation.mjs";

const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const fixture = JSON.parse(Buffer.concat(chunks).toString());
let output = "build/browser-evidence/security";
await mkdir(output, { recursive: true });
const browser = await puppeteer.launch({
  executablePath:
    process.env.CHROME_BIN ||
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  headless: true,
  acceptInsecureCerts: true,
  protocolTimeout: 15000,
  enableExtensions: [resolve("src/test/browser/zoom-extension")],
  args: ["--no-sandbox", "--enable-unsafe-extension-debugging"],
});
const zoomWorker = await (
  await browser.waitForTarget((target) => target.type() === "service_worker")
).worker();

/** 폐기 계정의 Base32 비밀에서 현재 30초 단계의 RFC 6238 코드를 계산한다. */
function otp(secret) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const bits = [...secret.replace(/=+$/, "")]
    .map((c) => alphabet.indexOf(c).toString(2).padStart(5, "0"))
    .join("");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const digest = createHmac("sha1", key).update(counter).digest();
  const offset = digest[19] & 15;
  return ((digest.readUInt32BE(offset) & 0x7fffffff) % 1000000)
    .toString()
    .padStart(6, "0");
}

/** 현재 DOM의 고정 선택자 값만 읽으며 비밀값은 로그에 남기지 않는다. */
async function text(page, selector) {
  return page.$eval(selector, (element) => element.textContent);
}

/** 비동기 상태 전이가 끝날 때까지 고정 상태 문구를 확인한다. */
async function notice(page, fragment) {
  await page.waitForFunction(
    (value) => document.getElementById("notice").textContent.includes(value),
    { polling: 100 },
    fragment,
  );
}

/**
 * 실제 목차로 원고 영역을 표시하고 패널 준비를 기다린다.
 * @param {object} page 편집기가 로드된 인증된 Puppeteer 페이지. null은 허용하지 않는다.
 * @param {"basic"|"child"|"answer"|"reveal"} section 표시할 기존 원고 영역. 생략·null·다른 값은 허용하지 않는다.
 * @throws {Error} 영역 값이 잘못되거나 실제 목차·표시 패널을 제한 시간 안에 찾지 못하면 실패한다.
 */
async function openSection(page, section) {
  assert.ok(["basic", "child", "answer", "reveal"].includes(section));
  await page.bringToFront();
  const selector = `#editor-nav a[href="#${section}-heading"]`;
  await page.waitForSelector(selector);
  if (
    !(await page.$eval(
      selector,
      (link) => link.getAttribute("aria-current") === "location",
    ))
  ) {
    await clickWithConfirmation(page, selector);
  }
  await page.waitForSelector(
    `.manuscript > section[aria-labelledby="${section}-heading"]`,
    { visible: true },
  );
}

/**
 * 명시적 저장 동작을 선택하고 실제 필드에 텍스트·정수를 입력한다.
 * @param {object} page 인증된 Puppeteer 페이지. null은 허용하지 않는다.
 * @param {"basic"|"child"|"answer"|"reveal"} section 기존 원고 영역. null은 허용하지 않는다.
 * @param {string} field 해당 영역의 실제 필드명. null은 허용하지 않는다.
 * @param {string|number} value 입력할 원고 또는 해당 필드 범위의 수. null은 허용하지 않는다.
 * @returns {Promise<void>} 모드 선택의 확인 종료 뒤 실제 입력을 완료한다.
 * @throws {Error} 영역·필드가 없거나 실제 선택/입력이 실패하면 전파한다.
 */
async function edit(page, section, field, value) {
  await openSection(page, section);
  await page.select(`#${section}-${field}-mode`, "value");
  await waitForConfirmation(page);
  await page.$eval(`#${section}-${field}`, (element) => {
    element.value = "";
  });
  await page.type(`#${section}-${field}`, String(value));
}

/**
 * 영역별 실제 저장 버튼과 확인 창 종료, 저장 후 조회 완료를 기다린다.
 * @param {object} page 인증된 Puppeteer 페이지. null은 허용하지 않는다.
 * @param {"basic"|"child"|"answer"|"reveal"} section 저장할 기존 원고 영역. null은 허용하지 않는다.
 * @returns {Promise<void>} 기존 저장 완료 안내를 확인한다.
 * @throws {Error} 실제 클릭/확인 또는 저장 안내가 제한 시간 안에 완료되지 않으면 실패한다.
 */
async function save(page, section) {
  await openSection(page, section);
  await clickWithConfirmation(page, `#${section}-save`);
  await notice(page, "최신 원고를 조회했습니다");
}

/** 실제 로그인 세션의 same-origin 요청만 실행해 후속 인물 API를 시험한다. */
async function childApi(page, path, method = "GET", body) {
  return page.evaluate(
    async ({ path, method, body }) => {
      const headers = { Accept: "application/json" };
      if (method !== "GET") {
        const csrf = await (await fetch("/admin/api/auth/csrf")).json();
        headers[csrf.headerName] = csrf.token;
        headers["Content-Type"] = "application/json";
      }
      const response = await fetch(path, {
        method,
        headers,
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      return { status: response.status, body: await response.json() };
    },
    { path, method, body },
  );
}

/** 실제 서버가 응답한 뒤 한 번만 전송을 끊어 확정된 쓰기의 응답 유실을 재현한다. */
async function loseResponse(page, pattern) {
  const cdp = await page.createCDPSession();
  await cdp.send("Fetch.enable", {
    patterns: [{ urlPattern: pattern, requestStage: "Response" }],
  });
  cdp.once("Fetch.requestPaused", async (event) => {
    await cdp.send("Fetch.failRequest", {
      requestId: event.requestId,
      errorReason: "ConnectionClosed",
    });
    await cdp.send("Fetch.disable");
  });
  return cdp;
}

/** 특정 응답 하나를 서버 처리 뒤 멈추고 시험이 지정한 순서에만 전달한다. */
async function holdResponse(page, pattern) {
  const cdp = await page.createCDPSession();
  let event;
  const ready = new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error(`응답 보류 진입 실패: ${pattern}`)),
      15000,
    );
    cdp.once("Fetch.requestPaused", (value) => {
      clearTimeout(timeout);
      event = value;
      resolve();
    });
  });
  await cdp.send("Fetch.enable", {
    patterns: [{ urlPattern: pattern, requestStage: "Response" }],
  });
  return {
    ready,
    async release() {
      await ready;
      await cdp.send("Fetch.continueResponse", { requestId: event.requestId });
      await cdp.send("Fetch.disable");
      await cdp.detach();
      await page.evaluate(
        () =>
          new Promise((resolve) =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
    },
  };
}

const layoutEvidence = [];

/**
 * 실제 편집 목차·모드 왕복·경고·문서 조각을 합성 폐기 사건에서 확인한다.
 * @param {object} page 합성 사건 편집기가 준비된 Puppeteer 페이지. null은 허용하지 않는다.
 */
async function inspectEditorNavigation(page) {
  for (const width of [360, 768, 900, 1200, 1440]) {
    await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
    const widths = [];
    for (const section of ["basic", "child", "answer", "reveal"]) {
      await openSection(page, section);
      const visibleCount = await page.$$eval(
        ".manuscript > section",
        (nodes) => nodes.filter((node) => node.getClientRects().length).length,
      );
      assert.equal(visibleCount, 1, `${width}px ${section} 단일 표시`);
      widths.push(
        await page.$eval(
          `.manuscript > section[aria-labelledby="${section}-heading"]`,
          (node) => node.getBoundingClientRect().width,
        ),
      );
      await inspectLayout(page, `panel-${section}`, `${width}px`);
    }
    assert.ok(
      Math.max(...widths) - Math.min(...widths) <= 1,
      `${width}px 영역 외곽 폭 동일`,
    );
  }
  assert.equal(await page.$eval("#policy-details", (node) => node.open), false);
  await page.click('#editor-nav a[href="#policy-heading"]');
  assert.equal(await page.$eval("#policy-details", (node) => node.open), true);
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "policy-heading",
  );
  assert.equal(
    await page.$eval('#editor-nav a[href="#reveal-heading"]', (node) =>
      node.getAttribute("aria-current"),
    ),
    "location",
  );
  await page.click("#policy-heading");
  assert.equal(await page.$eval("#policy-details", (node) => node.open), false);
  await openSection(page, "basic");
  const original = await text(page, "#basic-intro-record");
  await edit(page, "basic", "intro", "모드 왕복 보존 입력");
  assert.equal(
    await page.$eval(
      '#editor-nav a[href="#basic-heading"]',
      (node) => node.dataset.dirty,
    ),
    "true",
  );
  for (const mode of ["keep", "clear"]) {
    await page.select("#basic-intro-mode", mode);
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "모드 왕복 보존 입력",
    );
    assert.equal(await text(page, "#basic-intro-record"), original);
    assert.equal(
      await page.$eval(
        '#editor-nav a[href="#basic-heading"]',
        (node) => node.dataset.dirty,
      ),
      String(mode !== "keep"),
    );
    await page.select("#basic-intro-mode", "value");
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "모드 왕복 보존 입력",
    );
  }
  await page.select("#basic-intro-mode", "keep");
  await page.click("#warning-details summary");
  assert.equal(await page.$eval("#warning-details", (node) => node.open), true);
  const count = await page.$$eval("#warnings li", (nodes) => nodes.length);
  assert.equal(await text(page, "#warning-count"), `작성 확인 ${count}건`);
  const warning = await page.$eval('#warnings a[href$="-mode"]', (node) =>
    node.getAttribute("href"),
  );
  await page.click(`#warnings a[href="${warning}"]`);
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    warning.slice(1),
  );
  assert.equal(
    await page.$eval(warning, (node) => Boolean(node.getClientRects().length)),
    true,
  );
  await openSection(page, "reveal");
  await page.focus(".skip-link");
  await page.keyboard.press("Enter");
  await page.waitForFunction(
    () => document.activeElement.id === "basic-heading",
  );
  await page.goto(`${page.url().split("#")[0]}#reveal-heading`);
  await page.reload();
  await page.waitForSelector("#editor:not([hidden])");
  await page.waitForFunction(
    () => document.activeElement.id === "reveal-heading",
  );
  await page.evaluate(() => {
    location.hash = "#answer-heading";
  });
  await page.waitForFunction(
    () => document.activeElement.id === "answer-heading",
  );
  await openSection(page, "basic");
  await page.goBack();
  await page.waitForFunction(
    () => document.activeElement.id === "answer-heading",
  );
  await page.goForward();
  await page.waitForFunction(
    () => document.activeElement.id === "basic-heading",
  );
  await page.click("#warning-details summary");
}

/** 표시된 실제 DOM의 크기·계산 색상을 검사한다. 원고나 식별자 값은 측정 파일에 기록하지 않는다. */
async function inspectLayout(page, name, viewport) {
  const state = await page.evaluate(() => {
    const visible = (e) =>
      e.getClientRects().length && e.getBoundingClientRect().width > 1;
    const luminance = (rgb) =>
      rgb
        .slice(0, 3)
        .map((v) => {
          const s = v / 255;
          return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        })
        .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
    const rgb = (value) => value.match(/[\d.]+/g).map(Number);
    const ratios = [
      ...document.querySelectorAll(
        "h1,h2,h3,h4,p,label,dt,dd,a,button,input,select,textarea,summary,legend,.story-badges span,.row-action",
      ),
    ]
      .filter(
        (e) => visible(e) && !e.disabled && !e.closest(".visually-hidden"),
      )
      .map((e) => {
        let node = e;
        let background;
        while (node) {
          const value = rgb(getComputedStyle(node).backgroundColor);
          if (value.length === 3 || value[3] === 1) {
            background = value;
            break;
          }
          node = node.parentElement;
        }
        const style = getComputedStyle(e);
        const colors = [
          luminance(rgb(style.color)),
          luminance(background),
        ].sort((a, b) => b - a);
        return {
          ratio: (colors[0] + 0.05) / (colors[1] + 0.05),
          size: parseFloat(style.fontSize),
        };
      });
    return {
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight,
      small: [
        ...document.querySelectorAll("button,a,input,select,textarea,summary"),
      ].filter((e) => visible(e) && e.getBoundingClientRect().height < 48)
        .length,
      minContrast: Math.min(...ratios.map((e) => e.ratio)),
      minFont: Math.min(...ratios.map((e) => e.size)),
    };
  });
  assert.ok(state.scroll <= state.width, `${name} ${viewport} 가로 넘침`);
  assert.equal(state.small, 0, `${name} ${viewport} 터치 영역`);
  assert.ok(
    state.minContrast >= 4.5,
    `${name} ${viewport} 문자 대비 ${state.minContrast}`,
  );
  assert.ok(state.minFont >= 14, `${name} ${viewport} 보조 글자 크기`);
  layoutEvidence.push({ name, viewport, ...state });
}

/** 실제 DOM의 넘침·터치 크기·대비와 확대 시 재배치를 확인한 뒤 화면을 보존한다. */
async function layout(page, name) {
  await page.bringToFront();
  for (const width of [360, 768, 1440]) {
    await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
    await page.evaluate(() => scrollTo(0, 0));
    await inspectLayout(page, name, `${width}px`);
    await page.screenshot({
      path: `${output}/${name}-${width}.png`,
      fullPage: true,
    });
    if (name.startsWith("child-")) {
      await (
        await page.$("#child-edit")
      ).screenshot({
        path: `${output}/${name}-form-${width}.png`,
      });
    }
  }
  // 테스트 전용 확장에서 실제 Chrome 탭 확대를 사용하며 CSS 확대나 핀치로 대체하지 않는다.
  const zoom = await zoomWorker.evaluate(async (url) => {
    const [tab] = (await chrome.tabs.query({})).filter(
      (tab) => tab.url === url && tab.active,
    );
    await chrome.tabs.setZoom(tab.id, 2);
    return chrome.tabs.getZoom(tab.id);
  }, page.url());
  assert.equal(zoom, 2);
  await page.waitForFunction(() => innerWidth === 720);
  await page.evaluate(() => scrollTo(0, 0));
  await inspectLayout(page, name, "200%");
  // Chrome 탭 확대에서는 CSS 폭과 캡처 DIP 폭이 다르다. CSS 폭을 clip에 넣어 오른쪽을 자르지 않는다.
  const capture = await page.createCDPSession();
  try {
    const { contentSize, cssContentSize } = await capture.send(
      "Page.getLayoutMetrics",
    );
    assert.equal(cssContentSize.width, 720);
    assert.equal(contentSize.width, 1440);
    const image = await capture.send("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: true,
      clip: { ...contentSize, scale: 1 },
    });
    const png = Buffer.from(image.data, "base64");
    assert.equal(png.readUInt32BE(16), 1440, "200% 전체 폭을 실제 캡처");
    await writeFile(`${output}/${name}-200-percent.png`, png);
    if (name.startsWith("child-")) {
      const clip = await page.$eval("#child-edit", (e) => {
        const r = e.getBoundingClientRect();
        return {
          x: (r.x + scrollX) * 2,
          y: (r.y + scrollY) * 2,
          width: r.width * 2,
          height: r.height * 2,
          scale: 1,
        };
      });
      const child = await capture.send("Page.captureScreenshot", {
        format: "png",
        captureBeyondViewport: true,
        clip,
      });
      await writeFile(
        `${output}/${name}-form-200-percent.png`,
        Buffer.from(child.data, "base64"),
      );
    }
  } finally {
    await capture.detach();
  }
  await zoomWorker.evaluate(async (url) => {
    const [tab] = (await chrome.tabs.query({})).filter(
      (tab) => tab.url === url && tab.active,
    );
    await chrome.tabs.setZoom(tab.id, 1);
  }, page.url());
  await page.waitForFunction(() => innerWidth === 1440);
  await writeFile(
    `${output}/layout-metrics.json`,
    JSON.stringify(layoutEvidence, null, 2),
  );
}

try {
  const page = await browser.newPage();
  page.setDefaultTimeout(15000);
  const confirmation = await installConfirmationDriver(page);
  const runtimeErrors = [];
  page.on("pageerror", (error) => runtimeErrors.push(error.name));
  page.on("response", async (response) => {
    if (
      response.status() >= 400 &&
      new URL(response.url()).pathname.startsWith("/admin/api/auth/")
    ) {
      const body = await response.json().catch(() => ({}));
      console.log("AUTH response", response.status(), body.code);
    }
  });
  await page.goto(`${fixture.url}/admin/login`);
  await page.bringToFront();
  await page.waitForNetworkIdle();
  await page.waitForSelector("[name=loginId]");
  assert.equal(await page.title(), "시차 · 관리자 인증");
  assert.equal(await text(page, ".eyebrow"), "시차 · 관리자 보안");
  await page.type("[name=loginId]", fixture.loginId);
  await page.type("[name=password]", fixture.password);
  await Promise.all([
    page.waitForNavigation(),
    page.click("form[data-action=login] button"),
  ]);
  await page.waitForSelector("[name=totp]");
  await page.waitForNetworkIdle();
  await page.type("[name=totp]", otp(fixture.secret));
  try {
    await Promise.all([
      page.waitForNavigation(),
      page.click("form[data-action=mfa] button"),
    ]);
  } catch (error) {
    console.log(
      "MFA diagnostic",
      await text(page, "#notice"),
      await page.$eval("[name=totp]", (e) => ({
        valid: e.checkValidity(),
        length: e.value.length,
      })),
      runtimeErrors,
    );
    throw error;
  }
  assert.equal(new URL(page.url()).pathname, "/admin");
  const cookies = await page.cookies();
  const session = cookies.find((c) => c.name === "__Host-admin-session");
  assert.ok(session.secure && session.httpOnly, "실제 HTTPS 세션 쿠키");
  console.log("PASS HTTPS password + MFA login");

  await page.goto(`${fixture.url}/admin/stories`);
  await notice(page, "현재 페이지");
  assert.equal(await page.title(), "시차 · 관리자 사건 목록");
  assert.equal(await text(page, ".workspace-label"), "시차 · 관리자 제작실");
  assert.match(await text(page, "#rows"), /접근 가능 사건이 없습니다/);
  await layout(page, "empty-list");
  const failedList = await loseResponse(
    page,
    `${fixture.url}/admin/api/stories?*`,
  );
  await page.click("#filter-form button");
  await notice(page, "사건 목록을 조회하지 못했습니다");
  await layout(page, "list-error");
  await failedList.detach();
  await page.click("#filter-form button");
  await notice(page, "현재 페이지");
  await page.waitForSelector("#create-panel:not([hidden])");
  await page.type("#title", "합성 검증 사건 <script>확인</script>");
  await Promise.all([
    page.waitForNavigation(),
    page.click("#create-form button"),
  ]);
  await page.waitForSelector("#editor:not([hidden])");
  assert.equal(await page.title(), "시차 · 관리자 사건 보고서");
  assert.equal(await text(page, ".workspace-label"), "시차 · 관리자 제작실");
  const editorUrl = page.url();
  const apiPath = new URL(editorUrl).pathname.replace(
    "/admin/stories/",
    "/admin/api/stories/",
  );
  await inspectEditorNavigation(page);
  assert.match(
    await text(page, "#basic-title-record"),
    /<script>확인<\/script>/,
  );
  assert.equal(
    await page.$$eval("#basic-title-record script", (nodes) => nodes.length),
    0,
  );
  assert.equal(await page.$eval("#basic-title", (e) => e.hidden), true);
  await edit(page, "answer", "methodAnswer", "local-answer 보존할 미저장 원고");
  await edit(page, "basic", "difficulty", 3);
  await edit(page, "basic", "estMin", 20);
  await edit(page, "basic", "estMax", 30);
  await edit(page, "basic", "limitSec", 2100);
  await save(page, "basic");
  assert.equal(await page.$eval("#warning-details", (node) => node.open), true);
  assert.equal(
    await text(page, "#warning-count"),
    `작성 확인 ${await page.$$eval("#warnings li", (nodes) => nodes.length)}건`,
  );
  assert.equal(
    await page.$eval("#answer-methodAnswer", (e) => e.value),
    "local-answer 보존할 미저장 원고",
  );
  assert.match(await text(page, "#draft-summary"), /1개 영역 · 1개 필드/);
  await save(page, "answer");
  assert.match(await text(page, "#draft-summary"), /변경 없음/);
  assert.equal(await page.$eval("#basic-difficulty", (e) => e.value), "3");
  await page.focus("#answer-save");
  await page.keyboard.press("Tab");
  assert.notEqual(
    await page.evaluate(() => document.activeElement.tagName),
    "BODY",
  );
  assert.equal(
    await page.evaluate(
      () => getComputedStyle(document.activeElement).outlineStyle,
    ),
    "solid",
  );
  await layout(page, "editor");
  await page.focus(".version-panel summary");
  await page.keyboard.press("Enter");
  assert.equal(await page.$eval(".version-panel details", (e) => e.open), true);
  await layout(page, "version-details");
  await page.keyboard.press("Enter");
  assert.equal(
    await page.$eval(".version-panel details", (e) => e.open),
    false,
  );
  for (const width of [360, 1440]) {
    await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
    await page.click('.section-nav a[href="#answer-heading"]');
    await page.keyboard.press("Tab");
    const focus = await page.evaluate(() => {
      const e = document.activeElement;
      const nav = document.querySelector(".section-nav");
      return {
        id: e.id,
        outline: getComputedStyle(e).outlineStyle,
        top: e.getBoundingClientRect().top,
        bottom: e.getBoundingClientRect().bottom,
        ceiling:
          getComputedStyle(nav).position === "sticky"
            ? nav.getBoundingClientRect().bottom
            : 0,
      };
    });
    assert.equal(focus.id, "answer-culpritCode-mode");
    assert.equal(focus.outline, "solid");
    assert.ok(
      focus.top >= focus.ceiling && focus.bottom <= 900,
      "영역 이동 후 키보드 포커스 가림 없음",
    );
    await page.screenshot({ path: `${output}/keyboard-focus-${width}.png` });
  }
  console.log(
    "PASS numeric save, unsaved answer revision, layout and keyboard focus",
  );

  await edit(page, "basic", "estMax", 1);
  await page.click("#basic-save");
  await page.waitForSelector("#basic-estMax[aria-invalid=true]");
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "basic-estMax",
  );
  await page.select("#basic-estMax-mode", "keep");
  await edit(page, "basic", "estMin", 40);
  await page.click("#basic-save");
  await page.waitForSelector("#basic-estMax-mode[aria-invalid=true]");
  assert.equal(await page.$eval("#basic-estMax", (e) => e.hidden), true);
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "basic-estMax-mode",
  );
  await layout(page, "input-error");
  await page.select("#basic-estMin-mode", "keep");
  await page.select("#basic-estMax-mode", "value");
  assert.equal(
    await page.$eval("#basic-estMax-mode", (e) =>
      e.hasAttribute("aria-invalid"),
    ),
    false,
  );
  await page.select("#basic-estMax-mode", "keep");
  console.log("PASS input error and focus");

  const other = await browser.newPage();
  console.log("Conflict second tab opened");
  const otherConfirmation = await installConfirmationDriver(other);
  other.on("pageerror", (error) => runtimeErrors.push(error.name));
  await other.goto(editorUrl);
  await other.waitForSelector("#editor:not([hidden])");
  await edit(page, "answer", "methodAnswer", "local-answer 충돌 후 보존");
  await edit(other, "answer", "methodAnswer", "다른 탭 서버 최신 원고");
  await save(other, "answer");
  console.log("Conflict second tab saved");
  await page.bringToFront();
  await page.click("#answer-save");
  await notice(page, "검토 전 저장은 차단");
  assert.match(await text(page, "#latest-values"), /다른 탭 서버 최신 원고/);
  assert.match(await text(page, "#latest-values"), /local-answer 충돌 후 보존/);
  assert.equal(await page.$eval("#answer-save", (e) => e.disabled), true);
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "compare-heading",
  );
  await layout(page, "conflict");

  const failedRead = await loseResponse(page, `${fixture.url}${apiPath}`);
  await page.click("#refresh-latest");
  await notice(page, "불확실");
  assert.equal(await page.$eval("#accept-latest", (e) => e.disabled), true);
  await failedRead.detach();
  await page.click("#refresh-latest");
  await notice(page, "검토 전 저장은 차단");
  await openSection(page, "reveal");
  assert.equal(await page.$eval("#comparison", (node) => node.hidden), false);
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  assert.equal(
    await page.$eval('#editor-nav a[href="#reveal-heading"]', (node) =>
      node.getAttribute("aria-current"),
    ),
    "location",
  );
  assert.equal(
    await page.evaluate(
      () =>
        Boolean(document.activeElement.getClientRects().length) &&
        !document.activeElement.closest("[hidden]") &&
        document.activeElement !== document.body,
    ),
    true,
  );
  assert.match(await text(page, "#notice"), /영역별로 저장/);
  await save(page, "answer");
  assert.deepEqual(otherConfirmation.errors, []);
  await other.close();
  console.log(
    "PASS real DB conflict, failed comparison refresh, explicit revision acceptance",
  );

  let patches = 0;
  page.on("request", (request) => {
    if (request.method() === "PATCH") patches++;
  });
  const lostPatch = await loseResponse(
    page,
    `${fixture.url}${apiPath}/sections/reveal`,
  );
  await edit(
    page,
    "reveal",
    "revealText",
    "응답이 사라져도 서버에 한 번 저장되는 해설",
  );
  await page.click("#reveal-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(patches, 1);
  assert.match(await text(page, "#latest-values"), /응답이 사라져도/);
  assert.equal(await page.$eval("#reveal-save", (e) => e.disabled), true);
  await layout(page, "uncertain-save");
  await lostPatch.detach();
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await save(page, "reveal");
  assert.match(await text(page, "#notice"), /변화가 없습니다/);

  const childPath = `${apiPath}/persons`;
  const current = (await childApi(page, apiPath)).body.editRev;
  const created = await childApi(page, childPath, "POST", {
    expectedRev: current,
    item: {
      code: "P_BROWSER",
      name: "합성 인물",
      publicText: "공개 소개",
      secretText: "합성 인물 비밀",
    },
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.itemKey, "P_BROWSER");
  assert.equal(
    (await childApi(page, childPath)).body.items[0].code,
    "P_BROWSER",
  );
  assert.equal(
    (await childApi(page, `${childPath}/P_BROWSER`)).body.item.secretText,
    "합성 인물 비밀",
  );
  const updated = await childApi(page, `${childPath}/P_BROWSER`, "PATCH", {
    expectedRev: created.body.editRev,
    changes: { publicText: null },
  });
  assert.equal(updated.status, 200);
  await page.reload();
  await page.waitForSelector("#editor:not([hidden])");
  await edit(page, "answer", "culpritCode", "P_BROWSER");
  await save(page, "answer");
  const linked = (await childApi(page, apiPath)).body.editRev;
  const blocked = await childApi(
    page,
    `${childPath}/P_BROWSER/deactivate`,
    "POST",
    { expectedRev: linked },
  );
  assert.equal(blocked.status, 409);
  assert.equal(blocked.body.code, "REFERENCE_IN_USE");
  await page.select("#answer-culpritCode-mode", "clear");
  await save(page, "answer");
  const unlinked = (await childApi(page, apiPath)).body.editRev;
  const removed = await childApi(
    page,
    `${childPath}/P_BROWSER/deactivate`,
    "POST",
    { expectedRev: unlinked },
  );
  assert.equal(removed.status, 200);
  assert.equal(
    (await childApi(page, `${childPath}?activeYn=false`)).body.items[0]
      .activeYn,
    false,
  );
  const restored = await childApi(
    page,
    `${childPath}/P_BROWSER/reactivate`,
    "POST",
    { expectedRev: removed.body.editRev },
  );
  assert.equal(restored.status, 200);
  assert.equal(
    (await childApi(page, `${childPath}/P_BROWSER`)).body.item.activeYn,
    true,
  );
  console.log(
    "PASS authenticated persons API CRUD and editor culprit reference protection",
  );

  // 실제 키셋 경계를 만들되 제품 목록에 원고나 가상 통계는 넣지 않는다.
  let pageRev = restored.body.editRev;
  for (let index = 0; index < 21; index++) {
    const result = await childApi(page, childPath, "POST", {
      expectedRev: pageRev,
      item: {
        code: `PAGE_${String(index).padStart(2, "0")}`,
        name: `목록 비노출 원고 ${index}`,
      },
    });
    assert.equal(result.status, 201);
    pageRev = result.body.editRev;
  }
  await openSection(page, "child");
  await page.click("#child-list");
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await page.select("#child-filter", "false");
  await page.waitForFunction(() =>
    document
      .getElementById("child-list-status")
      .textContent.includes("이 상태의 인물이 없습니다"),
  );
  await layout(page, "empty-child-list");
  await page.select("#child-filter", "true");
  await page.waitForSelector('[data-child-key="PAGE_00"]');
  assert.equal(
    await page.$$eval("[data-child-key]", (nodes) => nodes.length),
    20,
  );
  assert.doesNotMatch(await text(page, "#child-rows"), /목록 비노출 원고/);
  await layout(page, "key-list");
  await page.click("#child-next");
  await page.waitForSelector('[data-child-key="P_BROWSER"]');
  assert.equal(
    await page.$$eval("[data-child-key]", (nodes) => nodes.length),
    22,
  );
  await clickWithConfirmation(page, '[data-child-key="P_BROWSER"]');
  await page.waitForFunction(
    () => document.getElementById("child-name").value === "합성 인물",
  );
  assert.equal(await page.$eval("#child-code", (e) => e.readOnly), true);
  assert.equal(
    await page.$eval("#child-code-mode", (e) => e.getClientRects().length),
    0,
  );
  await edit(page, "answer", "methodAnswer", "인물 저장 중 보존할 미저장 정답");
  await edit(page, "child", "name", " ");
  await page.click("#child-save");
  await notice(page, "공백일 수 없습니다");
  assert.equal(
    await page.$eval("#child-name", (e) => e.getAttribute("aria-invalid")),
    "true",
  );
  await edit(page, "child", "name", "<script>실행 금지</script>");
  await edit(page, "child", "secretText", "인물 UI 전용 비밀");
  await save(page, "child");
  assert.equal(
    await page.$eval("#answer-methodAnswer", (e) => e.value),
    "인물 저장 중 보존할 미저장 정답",
  );
  await edit(page, "child", "name", "취소할 때 남는 인물 입력");
  /** 현재 키·종류·모드·입력만 읽으며 원고를 로그로 내보내지 않는다. */
  const childDraftState = () =>
    page.evaluate(() => ({
      resource: document.getElementById("child-resource").value,
      key: document.getElementById("child-code").value,
      mode: document.getElementById("child-name-mode").value,
      name: document.getElementById("child-name").value,
      answerMode: document.getElementById("answer-methodAnswer-mode").value,
      answer: document.getElementById("answer-methodAnswer").value,
    }));
  const draftBeforeConsent = await childDraftState();
  let consentWrites = 0;
  const countConsentWrites = (request) => {
    if (["POST", "PATCH"].includes(request.method())) consentWrites++;
  };
  page.on("request", countConsentWrites);
  confirmation.automatic = false;
  for (const action of ["cancel", "escape"]) {
    await page.select("#child-resource", "roles");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    assert.deepEqual(await childDraftState(), draftBeforeConsent);
    assert.equal(consentWrites, 0);
    if (action === "cancel") await page.click("#ui-confirm-cancel");
    else await page.keyboard.press("Escape");
    await waitForConfirmation(page);
    assert.deepEqual(await childDraftState(), draftBeforeConsent);
    assert.equal(consentWrites, 0);
  }
  await page.select("#child-resource", "roles");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await page.click("#ui-confirm-accept");
  await waitForConfirmation(page);
  await page.waitForFunction(
    () => document.getElementById("child-resource").value === "roles",
  );
  const acceptedDraft = await childDraftState();
  assert.equal(acceptedDraft.resource, "roles");
  assert.equal(acceptedDraft.key, "");
  assert.equal(acceptedDraft.name, "");
  assert.equal(acceptedDraft.mode, "keep");
  assert.equal(acceptedDraft.answer, draftBeforeConsent.answer);
  assert.equal(acceptedDraft.answerMode, draftBeforeConsent.answerMode);
  assert.equal(consentWrites, 0);
  page.off("request", countConsentWrites);
  confirmation.automatic = true;
  await selectChildResource(page, "persons");
  await page.click("#child-list");
  await page.waitForSelector('[data-child-key="PAGE_00"]');
  await page.click("#child-next");
  await page.waitForSelector('[data-child-key="P_BROWSER"]');
  await clickWithConfirmation(page, '[data-child-key="P_BROWSER"]');
  await page.waitForFunction(
    () => document.getElementById("child-code").value === "P_BROWSER",
  );
  await layout(page, "child-editor");
  await page.click("#child-name-mode");
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "child-name-mode",
  );
  // 네이티브 선택 팝업을 닫은 뒤 별개 행동을 실행한다. 팝업 바깥 첫 클릭은 닫기에 소비될 수 있다.
  await page.keyboard.press("Escape");

  await clickWithConfirmation(page, "#child-new");
  await page.waitForFunction(
    () => document.getElementById("child-code").value === "",
  );
  assert.ok(
    await page.$eval("#child-code-mode", (e) => e.getClientRects().length > 0),
  );
  await edit(page, "child", "code", "UI_PERSON");
  await edit(page, "child", "name", "응답 유실 인물");
  let childPosts = 0;
  const countChildPosts = (request) => {
    if (
      request.method() === "POST" &&
      request.url() === `${fixture.url}${childPath}`
    )
      childPosts++;
  };
  page.on("request", countChildPosts);
  const lostChild = await loseResponse(page, `${fixture.url}${childPath}`);
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(childPosts, 1);
  assert.match(await text(page, "#latest-values"), /응답 유실 인물/);
  assert.equal(await page.$eval("#child-save", (e) => e.disabled), true);
  await lostChild.detach();
  page.off("request", countChildPosts);
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await save(page, "child");
  assert.match(await text(page, "#notice"), /변화가 없습니다/);
  await clickWithConfirmation(page, "#child-active");
  await notice(page, "최신 원고를 조회했습니다");
  await page.waitForFunction(
    () => document.getElementById("child-active").textContent === "인물 복원",
  );
  assert.equal(await page.$eval("#child-save", (e) => e.disabled), true);
  await page.select("#child-filter", "false");
  await page.waitForSelector('[data-child-key="UI_PERSON"]');
  await clickWithConfirmation(page, "#child-active");
  await page.waitForFunction(
    () =>
      document.getElementById("child-active").textContent === "인물 논리 삭제",
  );

  await edit(page, "child", "name", "남겨 둘 로컬 인물명");
  let remoteRev = (await childApi(page, apiPath)).body.editRev;
  const remoteChild = await childApi(page, `${childPath}/UI_PERSON`, "PATCH", {
    expectedRev: remoteRev,
    changes: { name: "서버에서 바꾼 인물명" },
  });
  assert.equal(remoteChild.status, 200);
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.match(await text(page, "#latest-values"), /서버에서 바꾼 인물명/);
  assert.equal(
    await page.$eval("#child-name", (e) => e.value),
    "남겨 둘 로컬 인물명",
  );
  const failedChildRead = await loseResponse(
    page,
    `${fixture.url}${childPath}/UI_PERSON`,
  );
  await page.click("#refresh-latest");
  await notice(page, "불확실");
  assert.equal(await page.$eval("#accept-latest", (e) => e.disabled), true);
  await failedChildRead.detach();
  await page.click("#refresh-latest");
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await save(page, "child");
  assert.equal(
    (await childApi(page, `${childPath}/UI_PERSON`)).body.item.name,
    "남겨 둘 로컬 인물명",
  );

  // 부모 응답 후 인물 변경을 끼워 넣어 서로 다른 GET 수정번호의 수락을 차단한다.
  remoteRev = (await childApi(page, apiPath)).body.editRev;
  await childApi(page, `${childPath}/UI_PERSON`, "PATCH", {
    expectedRev: remoteRev,
    changes: { publicText: "비교를 여는 변경" },
  });
  await edit(page, "child", "secretText", "분할 조회에도 남겨 둘 입력");
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  const splitRead = await page.createCDPSession();
  await splitRead.send("Fetch.enable", {
    patterns: [
      { urlPattern: `${fixture.url}${apiPath}`, requestStage: "Response" },
    ],
  });
  const changedDuringRead = new Promise((resolve, reject) => {
    splitRead.once("Fetch.requestPaused", async (event) => {
      try {
        const revision = (await childApi(page, `${childPath}/UI_PERSON`)).body
          .editRev;
        const result = await childApi(page, `${childPath}/UI_PERSON`, "PATCH", {
          expectedRev: revision,
          changes: { name: "부모 조회 직후 변경" },
        });
        assert.equal(result.status, 200);
        await splitRead.send("Fetch.continueResponse", {
          requestId: event.requestId,
        });
        await splitRead.send("Fetch.disable");
        resolve();
      } catch (error) {
        reject(error);
      }
    });
  });
  await page.click("#refresh-latest");
  await changedDuringRead;
  await notice(page, "불확실");
  assert.equal(await page.$eval("#accept-latest", (e) => e.disabled), true);
  assert.equal(await page.$eval("#child-save", (e) => e.disabled), true);
  assert.equal(
    await page.$eval("#child-secretText", (e) => e.value),
    "분할 조회에도 남겨 둘 입력",
  );
  await splitRead.detach();
  await page.click("#refresh-latest");
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  assert.equal(
    await page.$eval("#child-name", (e) => e.value),
    "부모 조회 직후 변경",
  );

  // 오래된 목록 응답은 진행 중인 인물 선택이나 저장의 잠금을 풀 수 없다.
  await page.select("#child-filter", "true");
  await page.waitForSelector('[data-child-key="PAGE_00"]');
  remoteRev = (await childApi(page, apiPath)).body.editRev;
  await childApi(page, `${childPath}/UI_PERSON`, "PATCH", {
    expectedRev: remoteRev,
    changes: { publicText: "선택 경합 직전 변경" },
  });
  const delayedList = await holdResponse(
    page,
    `${fixture.url}${childPath}?size=20&activeYn=true`,
  );
  await page.click("#child-list");
  await delayedList.ready;
  const delayedA = await holdResponse(
    page,
    `${fixture.url}${childPath}/PAGE_00`,
  );
  await clickWithConfirmation(page, '[data-child-key="PAGE_00"]');
  await delayedA.ready;
  await delayedList.release();
  assert.equal(await page.$eval("#comparison", (e) => e.hidden), true);
  assert.equal(
    await page.$eval('[data-child-key="PAGE_01"]', (e) => e.disabled),
    true,
  );
  assert.equal(await page.$eval("#accept-latest", (e) => e.disabled), true);
  await delayedA.release();
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await page.click("#child-list");
  await page.waitForSelector('[data-child-key="PAGE_01"]');
  const selectedResponse = await holdResponse(
    page,
    `${fixture.url}${childPath}/PAGE_01`,
  );
  await clickWithConfirmation(page, '[data-child-key="PAGE_01"]');
  await selectedResponse.ready;
  await openSection(page, "answer");
  await page.focus("#answer-methodAnswer");
  const pendingInput = await page.$eval(
    "#answer-methodAnswer",
    (node) => node.value,
  );
  const pendingComparison = await page.$eval(
    "#comparison",
    (node) => node.hidden,
  );
  await selectedResponse.release();
  await page.waitForFunction(
    () => document.getElementById("child-name").value === "목록 비노출 원고 1",
  );
  assert.equal(
    await page.$eval('#editor-nav a[href="#answer-heading"]', (node) =>
      node.getAttribute("aria-current"),
    ),
    "location",
  );
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "answer-methodAnswer",
  );
  assert.equal(
    await page.$eval("#answer-methodAnswer", (node) => node.value),
    pendingInput,
  );
  assert.equal(
    await page.$eval("#comparison", (node) => node.hidden),
    pendingComparison,
  );
  await openSection(page, "child");
  assert.equal(await page.$eval("#child-code", (e) => e.value), "PAGE_01");
  console.log("PASS delayed list cannot replace selected child identity");

  await page.click("#child-list");
  await page.waitForSelector('[data-child-key="PAGE_00"]');
  remoteRev = (await childApi(page, apiPath)).body.editRev;
  await childApi(page, `${childPath}/UI_PERSON`, "PATCH", {
    expectedRev: remoteRev,
    changes: { publicText: "저장 경합 직전 변경" },
  });
  const listDuringSave = await holdResponse(
    page,
    `${fixture.url}${childPath}?size=20&activeYn=true`,
  );
  await page.click("#child-list");
  await listDuringSave.ready;
  const pendingWrite = await holdResponse(
    page,
    `${fixture.url}${apiPath}/sections/basic`,
  );
  await edit(page, "basic", "intro", "잠금 소유권 확인 본문");
  await page.click("#basic-save");
  await pendingWrite.ready;
  await listDuringSave.release();
  assert.equal(await page.$eval("#comparison", (e) => e.hidden), true);
  assert.equal(await page.$eval("#basic-save", (e) => e.disabled), true);
  assert.equal(await page.$eval("#child-new", (e) => e.disabled), true);
  await pendingWrite.release();
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await save(page, "basic");
  assert.equal(
    (await childApi(page, apiPath)).body.sections.basic.intro,
    "잠금 소유권 확인 본문",
  );
  await openSection(page, "child");
  await layout(page, "child-restored");
  console.log(
    "PASS persons UI paging, validation, draft preservation, response loss, soft delete/restore and conflicts",
  );

  await exerciseRolePairs({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    holdResponse,
    loseResponse,
  });
  await exerciseClueAssignments({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    holdResponse,
    loseResponse,
  });
  await exerciseHints({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    loseResponse,
  });
  await exerciseEvents({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    loseResponse,
  });
  await exerciseFacts({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    loseResponse,
  });
  await exerciseRubrics({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    loseResponse,
  });
  await exerciseGradeSamples({
    openSection,
    page,
    apiPath,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    save,
    notice,
    layout,
    loseResponse,
  });
  await exerciseReviewChecks({
    page,
    baseUrl: fixture.url,
    api: childApi,
    edit,
    openSection,
    notice,
    layout,
    confirmation,
    holdResponse,
    loseResponse,
  });

  const ownershipCode = await exerciseStoryAccess({
    page,
    baseUrl: fixture.url,
    api: childApi,
    notice,
    layout,
  });
  await exerciseStoryOwnership({
    browser,
    page,
    baseUrl: fixture.url,
    code: ownershipCode,
    fixture,
    otp,
    api: childApi,
    notice,
    layout,
    confirmation,
    holdResponse,
  });

  await page.goto(`${fixture.url}/admin/stories`);
  await notice(page, "현재 페이지");
  await layout(page, "list");
  assert.match(await text(page, "#rows"), /<script>확인<\/script>/);
  const lostCreate = await loseResponse(
    page,
    `${fixture.url}/admin/api/stories`,
  );
  await page.type("#title", "생성 응답 유실 합성 사건");
  await page.click("#create-form button");
  await page.waitForSelector("#create-check:not([hidden])");
  await page.click("#check-create");
  await notice(page, "기존 사건을 확인");
  const previewUrl = await page.$eval("#create-check-message a", (e) => e.href);
  await lostCreate.detach();
  const failedDetail = await loseResponse(page, `${fixture.url}${apiPath}`);
  await page.goto(editorUrl);
  await notice(page, "불확실");
  await page.waitForSelector("#unavailable:not([hidden])");
  await layout(page, "detail-error");
  await failedDetail.detach();
  await page.click("#retry-detail");
  await page.waitForSelector("#editor:not([hidden])");
  await openSection(page, "child");
  await page.click("#child-list");
  await page.waitForSelector('[data-child-key="PAGE_00"]');
  await page.click("#child-next");
  await page.waitForSelector('[data-child-key="UI_PERSON"]');
  await clickWithConfirmation(page, '[data-child-key="UI_PERSON"]');
  await page.waitForFunction(
    () => document.getElementById("child-name").value === "부모 조회 직후 변경",
  );
  output = "build/browser-evidence/design";
  await mkdir(output, { recursive: true });
  layoutEvidence.length = 0;
  await captureReportPreview({
    openSection,
    browser,
    url: previewUrl,
    layout,
    edit,
    save,
    notice,
  });
  await edit(page, "child", "secretText", "로그아웃 시 제거할 인물 원고");
  await edit(page, "answer", "methodAnswer", "접근 상실 시 제거할 원고");
  const logout = await page.evaluate(async () => {
    const csrf = await (await fetch("/admin/api/auth/csrf")).json();
    const response = await fetch("/admin/api/auth/logout", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      body: "{}",
    });
    return response.status;
  });
  assert.equal(logout, 204);
  await page.click("#answer-save");
  await page.waitForSelector("#editor[hidden]");
  assert.equal(await page.$eval("#editor", (e) => e.childElementCount), 0);
  assert.doesNotMatch(await text(page, "body"), /접근 상실 시 제거할 원고/);
  assert.doesNotMatch(await text(page, "body"), /로그아웃 시 제거할 인물 원고/);
  assert.doesNotMatch(await text(page, "body"), /합성 검증 사건/);
  assert.equal(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
    0,
  );
  assert.deepEqual(runtimeErrors, []);
  assert.deepEqual(confirmation.errors, []);
  console.log(
    "PASS committed PATCH/POST response loss, no auto retry, memory-only drafts",
  );
} finally {
  fixture.password = fixture.secret = undefined;
  await browser.close();
}
