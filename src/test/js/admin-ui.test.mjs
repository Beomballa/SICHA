import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const assetRoot = new URL(
  "../../main/resources/static/admin/",
  import.meta.url,
);
const shared = readFileSync(new URL("ui.css", assetRoot), "utf8");
const stories = readFileSync(new URL("stories.css", assetRoot), "utf8");
const auth = readFileSync(new URL("auth.css", assetRoot), "utf8");

/** 모든 화면 CSS의 공통 원본 사용을 검사해 신규 화면의 토큰 복제를 차단한다. */
test("all first-party admin stylesheets import shared UI exactly once without root token copies", () => {
  const primitives = new Set(
    [...shared.matchAll(/(--[\w-]+)\s*:/g)].map((match) => match[1]),
  );
  const screens = readdirSync(assetRoot, { recursive: true }).filter(
    (path) => path.endsWith(".css") && path !== "ui.css",
  );
  assert.ok(screens.includes("auth.css") && screens.includes("stories.css"));
  for (const path of screens) {
    const css = readFileSync(new URL(path, assetRoot), "utf8").replace(
      /\/\*[\s\S]*?\*\//g,
      "",
    );
    const imports = [
      ...css.matchAll(/@import\s+(?:url\(\s*)?["']([^"']+)["']\s*\)?\s*;/g),
    ];
    assert.equal(
      imports.filter((match) => match[1] === "/admin/ui.css").length,
      1,
      `${path} 공통 import`,
    );
    for (const root of css.matchAll(/:root\b[^{}]*\{([^}]*)\}/g)) {
      for (const declaration of root[1].matchAll(/(--[\w-]+)\s*:/g)) {
        assert.equal(
          primitives.has(declaration[1]),
          false,
          `${path} 복제 토큰 ${declaration[1]}`,
        );
      }
    }
  }
});

/** 실제 공통·화면 CSS만 쓰는 합성 컴포넌트를 제공하며 서버·계정 자료는 사용하지 않는다. */
function gallery() {
  const long = "길고줄바꿈없는합성라벨".repeat(30);
  return `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/admin/stories.css"><title>합성 UI 회귀</title>
    <main class="shell"><header class="page-header"><h1>컴포넌트 검증</h1><p>합성 문구만 사용합니다.</p></header>
    <section><h2>입력 상태</h2><form>
      <div class="field" id="first-group"><div class="field-head"><label for="long-input">${long}</label><select id="mode" aria-label="입력 모드"><option>직접 수정</option></select></div><input id="long-input" value="${long}"><p class="error" id="helper">합성 도움말</p></div>
      <div class="field" id="second-group"><label for="long-select">긴 선택 항목</label><select id="long-select"><option>${long}</option></select></div>
      <div class="field"><label for="disabled-input">사용 불가 입력</label><input id="disabled-input" disabled value="합성 사용 불가 값"></div>
      <div class="field"><label for="readonly-input">읽기 전용 입력</label><input id="readonly-input" readonly value="합성 원본"></div>
      <label class="impact-confirm"><input type="checkbox" id="approval">민감 변경 영향을 확인했습니다.</label>
      <div class="form-actions" id="actions"><button id="primary" type="button">${long}</button><button id="secondary" class="secondary" type="button">보조 행동</button><button id="disabled-button" type="button" disabled>사용 불가</button><a href="#first-group">실제 링크</a></div>
    </form><details><summary>접기와 펼치기</summary><p>합성 내용</p></details></section></main></html>`;
}

/**
 * 계산 색상과 실제 배치를 읽으며 disabled 상태도 대비 검사에 포함한다.
 * @param {object} page 합성 갤러리와 실제 CSS가 로드된 Puppeteer 페이지. null은 허용하지 않는다.
 * @returns {Promise<object>} CSS 픽셀 단위 배치와 각 컴포넌트의 대비·불투명도 측정값.
 */
async function geometry(page) {
  return page.evaluate(() => {
    const rect = (selector) =>
      document.querySelector(selector).getBoundingClientRect();
    const luminance = (color) =>
      color
        .slice(0, 3)
        .map((value) => {
          const channel = value / 255;
          return channel <= 0.04045
            ? channel / 12.92
            : ((channel + 0.055) / 1.055) ** 2.4;
        })
        .reduce(
          (sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index],
          0,
        );
    const rgb = (color) => color.match(/[\d.]+/g).map(Number);
    const contrast = (node) => {
      let parent = node;
      let background;
      while (parent) {
        const color = rgb(getComputedStyle(parent).backgroundColor);
        if (color.length === 3 || color[3] === 1) {
          background = color;
          break;
        }
        parent = parent.parentElement;
      }
      const values = [
        luminance(rgb(getComputedStyle(node).color)),
        luminance(background),
      ].sort((a, b) => b - a);
      return (values[0] + 0.05) / (values[1] + 0.05);
    };
    const targets = [
      ...document.querySelectorAll("button,a,input,select,summary"),
    ];
    const select = getComputedStyle(document.getElementById("long-select"));
    return {
      width: innerWidth,
      scroll: document.documentElement.scrollWidth,
      targets: targets.map((node) => ({
        id: node.id || node.tagName,
        height: node.getBoundingClientRect().height,
        width: node.getBoundingClientRect().width,
        opacity: getComputedStyle(node).opacity,
        contrast: contrast(node),
      })),
      textContrast: [...document.querySelectorAll("h1,h2,p,label")].map(
        contrast,
      ),
      labelGap:
        rect("#long-select").top - rect('label[for="long-select"]').bottom,
      helperGap: rect("#helper").top - rect("#long-input").bottom,
      groupGap: rect("#second-group").top - rect("#first-group").bottom,
      actionGap: parseFloat(
        getComputedStyle(document.getElementById("actions")).columnGap,
      ),
      sectionGap: rect("main > section").top - rect(".page-header").bottom,
      arrow: select.backgroundPositionX,
      arrowSize: select.backgroundSize,
      endPadding: parseFloat(select.paddingRight),
      bounds: [
        ...document.querySelectorAll(".field,label,input,select,button"),
      ].map((node) => ({
        left: node.getBoundingClientRect().left,
        right: node.getBoundingClientRect().right,
      })),
    };
  });
}

/**
 * 고정 선택자의 관찰 가능한 색·테두리·포커스를 반환한다.
 * @param {object} page 합성 갤러리가 준비된 Puppeteer 페이지. null은 허용하지 않는다.
 * @param {string} selector 갤러리에 존재하는 단일 컴포넌트 CSS 선택자. 빈 값·null은 허용하지 않는다.
 * @returns {Promise<object>} 현재 컴포넌트의 계산 색상과 포커스·불투명도 상태.
 * @throws {Error} 선택자에 대응하는 컴포넌트가 없으면 실패한다.
 */
async function state(page, selector) {
  return page.$eval(selector, (node) => {
    const style = getComputedStyle(node);
    return {
      background: style.backgroundColor,
      border: style.borderColor,
      color: style.color,
      opacity: style.opacity,
      outline: style.outlineStyle,
      outlineWidth: parseFloat(style.outlineWidth),
      focusVisible: node.matches(":focus-visible"),
    };
  });
}

/** 불투명 버튼 상태의 실제 전경·배경 대비를 WCAG 상대 휘도로 계산한다. */
function assertButtonContrast(value) {
  const luminance = (color) =>
    color
      .match(/[\d.]+/g)
      .slice(0, 3)
      .map(Number)
      .map((channel) => {
        const normalized = channel / 255;
        return normalized <= 0.04045
          ? normalized / 12.92
          : ((normalized + 0.055) / 1.055) ** 2.4;
      })
      .reduce(
        (sum, channel, index) =>
          sum + channel * [0.2126, 0.7152, 0.0722][index],
        0,
      );
  const colors = [luminance(value.color), luminance(value.background)].sort(
    (a, b) => b - a,
  );
  assert.ok(
    (colors[0] + 0.05) / (colors[1] + 0.05) >= 4.5,
    "행동 상태 문자 대비",
  );
  assert.equal(value.opacity, "1");
}

/** 외부 요청을 전부 차단한 브라우저에서 다섯 폭과 실제 입력 상태를 검증한다. */
test("shared UI gallery geometry, interaction, contrast and native forced colors", async () => {
  const browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--no-sandbox"],
  });
  try {
    const page = await browser.newPage();
    await page.setRequestInterception(true);
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.origin !== "https://ui.test") return request.abort();
      if (url.pathname === "/gallery")
        return request.respond({
          contentType: "text/html; charset=utf-8",
          body: gallery(),
        });
      if (url.pathname === "/admin/stories.css")
        return request.respond({ contentType: "text/css", body: stories });
      if (url.pathname === "/admin/ui.css")
        return request.respond({ contentType: "text/css", body: shared });
      return request.abort();
    });
    await page.goto("https://ui.test/gallery");
    await page.waitForSelector("#primary");
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900 });
      const result = await geometry(page);
      assert.ok(result.scroll <= width, `${width}px 문서 넘침`);
      for (const target of result.targets) {
        assert.ok(
          target.height >= 48 && target.width >= 48,
          `${width}px ${target.id} 대상 크기`,
        );
        assert.equal(target.opacity, "1", `${target.id} 불투명 상태`);
        assert.ok(
          target.contrast >= 4.5,
          `${target.id} 대비 ${target.contrast}`,
        );
      }
      assert.ok(
        result.textContrast.every((ratio) => ratio >= 4.5),
        `${width}px 본문 대비`,
      );
      assert.equal(result.labelGap, 8);
      assert.equal(result.helperGap, 8);
      assert.equal(result.groupGap, 24);
      assert.equal(result.actionGap, 16);
      assert.equal(result.sectionGap, 32);
      assert.equal(result.endPadding, 48);
      assert.match(result.arrow, /16px/);
      assert.equal(result.arrowSize, "16px 16px");
      assert.ok(
        result.bounds.every((box) => box.left >= 0 && box.right <= width),
        `${width}px 긴 내용 경계`,
      );
    }
    await page.mouse.move(0, 0);
    const normal = await state(page, "#primary");
    await page.hover("#primary");
    const hover = await state(page, "#primary");
    assert.notEqual(hover.background, normal.background);
    assertButtonContrast(hover);
    await page.mouse.down();
    const active = await state(page, "#primary");
    assert.notEqual(active.background, hover.background);
    assertButtonContrast(active);
    await page.mouse.up();
    const disabled = await state(page, "#disabled-button");
    await page.hover("#disabled-button");
    assert.deepEqual(await state(page, "#disabled-button"), disabled);
    assert.notEqual(disabled.background, normal.background);
    assert.notEqual(disabled.color, normal.color);
    assertButtonContrast(disabled);
    await page.mouse.move(0, 0);
    const secondary = await state(page, "#secondary");
    await page.hover("#secondary");
    const secondaryHover = await state(page, "#secondary");
    assert.notEqual(secondaryHover.background, secondary.background);
    assertButtonContrast(secondaryHover);
    await page.mouse.down();
    const secondaryActive = await state(page, "#secondary");
    assert.notEqual(secondaryActive.background, secondaryHover.background);
    assertButtonContrast(secondaryActive);
    await page.mouse.up();
    await page.hover("#long-input");
    const inputHover = await state(page, "#long-input");
    await page.mouse.move(0, 0);
    assert.notEqual(
      (await state(page, "#long-input")).border,
      inputHover.border,
    );
    await page.goto("https://ui.test/gallery");
    await page.keyboard.press("Tab");
    assert.equal(await page.evaluate(() => document.activeElement.id), "mode");
    const focused = await state(page, "#mode");
    assert.equal(focused.focusVisible, true);
    assert.equal(focused.outline, "solid");
    assert.ok(focused.outlineWidth >= 2);
    const cdp = await page.createCDPSession();
    try {
      await cdp.send("Emulation.setEmulatedMedia", {
        features: [{ name: "forced-colors", value: "active" }],
      });
      assert.equal(
        await page.evaluate(
          () => matchMedia("(forced-colors: active)").matches,
        ),
        true,
      );
      const forced = await page.$eval("#long-select", (node) => ({
        appearance: getComputedStyle(node).appearance,
        image: getComputedStyle(node).backgroundImage,
        padding: getComputedStyle(node).paddingRight,
      }));
      assert.equal(forced.appearance, "auto");
      assert.equal(forced.image, "none");
      assert.equal(forced.padding, "16px");
      assert.equal((await state(page, "#mode")).outline, "solid");
    } finally {
      await cdp.send("Emulation.setEmulatedMedia", { features: [] });
      await cdp.detach();
    }
  } finally {
    await browser.close();
  }
});

/** 인증·계정의 실제 구성 CSS로 긴 식별자와 체크박스 라벨 전체의 조작 영역을 확인한다. */
test("authentication composition wraps long identifiers and keeps whole permission labels clickable", async () => {
  const browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--no-sandbox"],
  });
  try {
    const page = await browser.newPage();
    const markup = `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <link rel="stylesheet" href="/admin/auth.css"><main class="shell"><h1>합성 계정 구성 검증</h1>
      <div id="content"><section><h2>자격과 영향</h2><dl class="account-fields"><dt>합성 영향 해시</dt><dd>${"a".repeat(128)}</dd></dl>
      <fieldset><legend>작업 자격</legend><div class="permission-choices"><label class="permission-choice" id="check-choice"><input type="checkbox" id="permission">사건 생성 자격</label><label class="permission-choice"><input type="checkbox">검수 자격</label></div></fieldset>
      <div class="actions"><button type="button">변경 확인</button><button type="button" class="secondary">취소</button></div></section></div></main></html>`;
    await page.setRequestInterception(true);
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.origin !== "https://ui.test") return request.abort();
      const body = {
        "/auth-gallery": markup,
        "/admin/auth.css": auth,
        "/admin/ui.css": shared,
      }[url.pathname];
      if (!body) return request.abort();
      return request.respond({
        contentType: url.pathname.endsWith(".css")
          ? "text/css"
          : "text/html; charset=utf-8",
        body,
      });
    });
    await page.goto("https://ui.test/auth-gallery");
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900 });
      const layout = await page.evaluate(() => {
        const shell = document.querySelector(".shell");
        const style = getComputedStyle(shell);
        const label = document
          .getElementById("check-choice")
          .getBoundingClientRect();
        return {
          overflow: document.documentElement.scrollWidth > innerWidth,
          contentWidth:
            shell.clientWidth -
            parseFloat(style.paddingLeft) -
            parseFloat(style.paddingRight),
          label: { width: label.width, height: label.height },
          checked: document.getElementById("permission").checked,
        };
      });
      assert.equal(layout.overflow, false, `${width}px 인증·계정 넘침`);
      assert.ok(layout.contentWidth <= 720, `${width}px 인증 읽기 폭`);
      assert.ok(
        layout.label.width >= 48 && layout.label.height >= 48,
        `${width}px 체크박스 라벨 터치 영역`,
      );
      await page.click("#check-choice", {
        offset: { x: layout.label.width - 8, y: layout.label.height / 2 },
      });
      assert.equal(
        await page.$eval("#permission", (input) => input.checked),
        !layout.checked,
        "입력 밖 라벨을 눌러 자격 선택",
      );
    }
  } finally {
    await browser.close();
  }
});
