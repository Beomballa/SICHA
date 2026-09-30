import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
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
const css = readFileSync(new URL("ui.css", assetRoot), "utf8");
const script = readFileSync(new URL("ui.js", assetRoot), "utf8");
const options = {
  title: "합성 변경 확인",
  message: "합성 입력은 변경하지 않습니다.",
  confirmLabel: "합성 변경 확인",
};

/** 제품 확인 호출과 세 화면의 순서 보장 로딩을 검사하며 로컬 설계 문서에 의존하지 않는다. */
test("first-party confirmations use shared UI with ordered deferred loading and no native fallback", () => {
  for (const name of ["auth.js", "stories.js", "ui.js"]) {
    const source = readFileSync(new URL(name, assetRoot), "utf8");
    assert.doesNotMatch(
      source,
      /\b(?:window\s*\.\s*)?(?:alert|prompt)\s*\(/,
      name,
    );
    const calls =
      name === "ui.js"
        ? source.replace(
            "function confirm(options)",
            "function sharedConfirmation(options)",
          )
        : source;
    assert.doesNotMatch(
      calls,
      /(?:^|[^\w.])(?:window\s*\.\s*)?confirm\s*\(/m,
      name,
    );
    if (name !== "ui.js")
      assert.equal(
        [...source.matchAll(/\bAdminUI\.confirm\s*\(/g)].length,
        name === "auth.js" ? 2 : 12,
        `${name} 기존 확인 전체 교체`,
      );
  }
  for (const [name, screen] of [
    ["auth", "auth"],
    ["stories", "stories"],
    ["story-editor", "stories"],
  ]) {
    const html = readFileSync(
      new URL(
        `../../main/resources/templates/admin/${name}.html`,
        import.meta.url,
      ),
      "utf8",
    );
    const scripts = [...html.matchAll(/<script\b([^>]*)>/g)].map(
      (match) => match[1],
    );
    const common = scripts.findIndex((attrs) =>
      /src="\/admin\/ui\.js"/.test(attrs),
    );
    const caller = scripts.findIndex((attrs) =>
      new RegExp(`src="/admin/${screen}\\.js"`).test(attrs),
    );
    assert.ok(common >= 0 && caller > common, `${name} 공통 자산 선행`);
    assert.ok(
      /\bdefer\b/.test(scripts[common]) && /\bdefer\b/.test(scripts[caller]),
    );
    assert.equal(
      scripts.filter((attrs) => /src="\/admin\/ui\.js"/.test(attrs)).length,
      1,
    );
    assert.doesNotMatch(html, /\b(?:confirm|alert|prompt)\s*\(/);
  }
});

/**
 * 실제 공통 자산만 가로채며 외부 요청·실제 계정·서버를 사용하지 않는다.
 * @param {function} scenario 준비된 합성 페이지를 받는 비동기 검사 함수.
 * @returns {Promise<void>} 검사 종료 후 브라우저를 닫는다.
 * @throws {Error} Chrome 시작 또는 시나리오 실패를 그대로 보고한다.
 */
async function isolated(scenario) {
  const browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--no-sandbox"],
  });
  try {
    const page = await browser.newPage();
    const nativeDialogs = [];
    page.on("dialog", (dialog) => {
      nativeDialogs.push(dialog.type());
      void dialog.dismiss();
    });
    await page.setRequestInterception(true);
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.origin !== "https://ui.test") return request.abort();
      const assets = {
        "/confirm": {
          contentType: "text/html; charset=utf-8",
          body: '<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/admin/ui.css"><script defer src="/admin/ui.js"></script><title>합성 확인 검증</title><main><button type="button" id="trigger">합성 확인 열기</button><input id="draft" value="합성 입력 보존"><button type="button" id="outside">외부 행동</button></main></html>',
        },
        "/admin/ui.css": { contentType: "text/css", body: css },
        "/admin/ui.js": { contentType: "text/javascript", body: script },
      };
      const asset = assets[url.pathname];
      return asset ? request.respond(asset) : request.abort();
    });
    await page.goto("https://ui.test/confirm");
    await page.waitForFunction(() => Boolean(window.AdminUI));
    await scenario(page);
    assert.deepEqual(nativeDialogs, [], "네이티브 확인 사용 금지");
  } finally {
    await browser.close();
  }
}

/**
 * 결과를 메모리에만 기록하며 확인 Promise를 기다리지 않고 모달을 연다.
 * @param {object} page 공통 자산을 로드한 합성 페이지.
 * @param {object} text 비어 있지 않은 title/message/confirmLabel. 기본값은 합성 문구다.
 * @returns {Promise<void>} 모달이 열린 시점까지 기다린다.
 */
async function open(page, text = options) {
  await page.focus("#trigger");
  await page.evaluate((value) => {
    window.results = [];
    window.AdminUI.confirm(value).then((accepted) =>
      window.results.push(accepted),
    );
  }, text);
  await page.waitForSelector("#ui-confirm-dialog[open]");
}

/**
 * 현재 모달이 제거되고 정확히 한 번 결과가 전달됐음을 관찰한다.
 * @param {object} page 확인 결과를 메모리에 기록한 합성 페이지.
 * @param {boolean} expected 명시 확인이면 true, 취소 경로면 false.
 * @returns {Promise<void>} DOM 제거와 입력 보존까지 확인한다.
 * @throws {Error} 결과·정리·입력 보존 계약이 다르면 검사에 실패한다.
 */
async function settled(page, expected) {
  await page.waitForFunction(() => window.results.length === 1);
  assert.deepEqual(await page.evaluate(() => window.results), [expected]);
  assert.equal(await page.$("#ui-confirm-dialog"), null);
  assert.equal(
    await page.$eval("#draft", (node) => node.value),
    "합성 입력 보존",
  );
}

/** 공격성 합성 문자열이 마크업이 아니라 접근 가능한 문구로만 표시되는지 검사한다. */
test("confirmation safely renders hostile text, validates programmer contracts and accepts explicitly once", async () => {
  await isolated(async (page) => {
    const hostile =
      '<img src=x onerror="window.injected=true"><script>window.injected=true</script>& 합성';
    await open(page, {
      title: hostile,
      message: hostile,
      confirmLabel: hostile,
    });
    const dom = await page.evaluate(() => ({
      role: document.getElementById("ui-confirm-dialog").getAttribute("role"),
      labelledby: document
        .getElementById("ui-confirm-dialog")
        .getAttribute("aria-labelledby"),
      describedby: document
        .getElementById("ui-confirm-dialog")
        .getAttribute("aria-describedby"),
      values: [
        "ui-confirm-title",
        "ui-confirm-message",
        "ui-confirm-accept",
      ].map((id) => document.getElementById(id).textContent),
      markup: document.querySelectorAll(
        "#ui-confirm-dialog img,#ui-confirm-dialog script",
      ).length,
      injected: Boolean(window.injected),
      focus: document.activeElement.id,
    }));
    assert.equal(dom.role, "alertdialog");
    assert.equal(dom.labelledby, "ui-confirm-title");
    assert.equal(dom.describedby, "ui-confirm-message");
    assert.deepEqual(dom.values, [hostile, hostile, hostile]);
    assert.equal(dom.markup, 0);
    assert.equal(dom.injected, false);
    assert.equal(dom.focus, "ui-confirm-cancel");
    await page.evaluate(() => {
      window.oldDialog = document.getElementById("ui-confirm-dialog");
      window.oldAccept = document.getElementById("ui-confirm-accept");
      window.oldAccept.click();
      window.oldAccept.click();
    });
    await settled(page, true);
    assert.equal(await page.evaluate(() => window.oldDialog.textContent), "");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "trigger",
    );
    assert.deepEqual(
      await page.evaluate(() =>
        [
          undefined,
          {},
          { title: " ", message: "값", confirmLabel: "값" },
          { title: "값", message: null, confirmLabel: "값" },
          { title: "값", message: "값", confirmLabel: 1 },
        ].map((value) => {
          try {
            window.AdminUI.confirm(value);
            return false;
          } catch (error) {
            return error instanceof TypeError;
          }
        }),
      ),
      [true, true, true, true, true],
    );
  });
});

/** Cancel 우선 초점과 양방향 Tab 순환, Enter·Escape·명시 취소의 무변경을 검사한다. */
test("confirmation confines keyboard focus and every cancellation preserves inputs", async () => {
  await isolated(async (page) => {
    await open(page);
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
    await page.keyboard.down("Shift");
    await page.keyboard.press("Tab");
    await page.keyboard.up("Shift");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "ui-confirm-accept",
    );
    await page.keyboard.down("Shift");
    await page.keyboard.press("Tab");
    await page.keyboard.up("Shift");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "ui-confirm-cancel",
    );
    await page.keyboard.press("Enter");
    await settled(page, false);
    await open(page);
    await page.keyboard.press("Escape");
    await settled(page, false);
    await open(page);
    await page.click("#ui-confirm-cancel");
    await settled(page, false);
    await open(page);
    await page.evaluate(() =>
      document.getElementById("ui-confirm-dialog").close(),
    );
    await settled(page, false);
  });
});

/** 중복 요청을 큐에 넣지 않고 낡은 닫힘이나 분리된 버튼이 다음 동의에 영향을 주지 않는지 검사한다. */
test("confirmation rejects concurrency, removes cancelled DOM and isolates stale close events", async () => {
  await isolated(async (page) => {
    await open(page);
    const second = await page.evaluate(async (value) => {
      window.firstDialog = document.getElementById("ui-confirm-dialog");
      window.firstAccept = document.getElementById("ui-confirm-accept");
      window.duplicateWrites = 0;
      const accepted = await window.AdminUI.confirm({
        ...value,
        title: "중복 합성 요청",
      });
      if (accepted) window.duplicateWrites++;
      return accepted;
    }, options);
    assert.equal(second, false);
    assert.equal(
      await page.$eval("#ui-confirm-title", (node) => node.textContent),
      options.title,
    );
    await page.evaluate(() => window.AdminUI.cancelConfirmation());
    await settled(page, false);
    assert.equal(
      await page.evaluate(() => window.firstDialog.isConnected),
      false,
    );
    assert.equal(await page.evaluate(() => window.firstDialog.textContent), "");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "trigger",
    );
    await open(page);
    await page.evaluate(() => {
      window.firstDialog.dispatchEvent(new Event("close"));
      window.firstAccept.click();
    });
    assert.deepEqual(await page.evaluate(() => window.results), []);
    assert.equal(
      await page.$eval("#ui-confirm-title", (node) => node.textContent),
      options.title,
    );
    await page.click("#ui-confirm-accept");
    await settled(page, true);
    await page.evaluate(() =>
      window.firstDialog.dispatchEvent(new Event("close")),
    );
    assert.equal(
      await page.$("#ui-confirm-dialog"),
      null,
      "중복 요청은 나중에도 표시되지 않는다",
    );
    assert.equal(await page.evaluate(() => window.duplicateWrites), 0);
    for (const state of ["disabled", "hidden"]) {
      await open(page);
      await page.evaluate((property) => {
        document.getElementById("trigger")[property] = true;
        window.AdminUI.cancelConfirmation();
      }, state);
      await settled(page, false);
      assert.notEqual(
        await page.evaluate(() => document.activeElement.id),
        "trigger",
      );
      await page.evaluate((property) => {
        document.getElementById("trigger")[property] = false;
      }, state);
    }
    await open(page);
    await page.evaluate(() => {
      window.detachedTrigger = document.getElementById("trigger");
      window.detachedFocus = 0;
      window.detachedTrigger.addEventListener(
        "focus",
        () => window.detachedFocus++,
      );
      window.detachedTrigger.remove();
      window.AdminUI.cancelConfirmation();
    });
    await settled(page, false);
    assert.equal(await page.evaluate(() => window.detachedFocus), 0);
    await page.evaluate((value) => {
      window.results = [];
      window.AdminUI.confirm(value).then((accepted) =>
        window.results.push(accepted),
      );
      window.dispatchEvent(new Event("pagehide"));
    }, options);
    await settled(page, false);
  });
});

/** 세 대표 폭의 긴 합성 문구, 24/12 간격, 40px 확인 행동, 어두운 표면·붉은 행동·키보드 초점을 검사한다. */
test("confirmation uses responsive shared dark/red geometry and visible keyboard focus", async () => {
  await isolated(async (page) => {
    for (const width of [360, 768, 1440]) {
      await page.setViewport({ width, height: 900 });
      await open(page, {
        ...options,
        title: "합성긴제목".repeat(20),
        message: "합성긴문구".repeat(80),
        confirmLabel: "합성확인".repeat(20),
      });
      const geometry = await page.evaluate(() => {
        const dialog = document.getElementById("ui-confirm-dialog");
        const style = getComputedStyle(dialog);
        const rect = dialog.getBoundingClientRect();
        return {
          scroll: document.documentElement.scrollWidth,
          left: rect.left,
          right: rect.right,
          dialogOverflow: dialog.scrollWidth > dialog.clientWidth,
          padding: parseFloat(style.paddingLeft),
          background: style.backgroundColor,
          gap: parseFloat(
            getComputedStyle(dialog.querySelector(".ui-confirm-actions")).gap,
          ),
          buttons: [...dialog.querySelectorAll("button")].map((node) => {
            const box = node.getBoundingClientRect();
            const computed = getComputedStyle(node);
            return {
              width: box.width,
              height: box.height,
              minHeight: computed.minHeight,
              fontSize: computed.fontSize,
              padding: computed.paddingLeft,
              background: computed.backgroundColor,
              color: computed.color,
            };
          }),
        };
      });
      assert.ok(
        geometry.scroll <= width &&
          geometry.left >= 0 &&
          geometry.right <= width,
        `${width}px 문서 경계`,
      );
      assert.equal(geometry.dialogOverflow, false, `${width}px 긴 확인 문구`);
      assert.equal(geometry.padding, 24);
      assert.equal(geometry.gap, 12);
      assert.equal(geometry.background, "rgb(30, 34, 40)");
      assert.ok(
        geometry.buttons.every(
          (button) => button.width >= 40 && button.height >= 40,
        ),
      );
      for (const button of geometry.buttons) {
        assert.equal(button.minHeight, "40px");
        assert.equal(button.fontSize, "14px");
        assert.equal(button.padding, "12px");
      }
      assert.equal(geometry.buttons[0].background, "rgba(0, 0, 0, 0)");
      assert.equal(geometry.buttons[1].background, "rgb(201, 26, 26)");
      assert.ok(
        geometry.buttons.every(
          (button) => button.color === "rgb(229, 232, 235)",
        ),
      );
      await page.keyboard.press("Tab");
      const focus = await page.$eval("#ui-confirm-accept", (node) => ({
        visible: node.matches(":focus-visible"),
        outline: getComputedStyle(node).outlineStyle,
        width: parseFloat(getComputedStyle(node).outlineWidth),
        color: getComputedStyle(node).outlineColor,
      }));
      assert.equal(focus.visible, true);
      assert.equal(focus.outline, "solid");
      assert.ok(focus.width >= 2);
      assert.equal(focus.color, "rgb(255, 199, 0)");
      await page.keyboard.press("Escape");
      await settled(page, false);
      await open(page);
      assert.deepEqual(
        await page.$$eval(".ui-confirm-actions button", (buttons) =>
          buttons.map((button) => button.getBoundingClientRect().height),
        ),
        [40, 40],
      );
      assert.ok(
        await page.$eval(
          "#trigger",
          (button) => button.getBoundingClientRect().height >= 48,
        ),
      );
      await page.keyboard.press("Escape");
      await settled(page, false);
    }
  });
});
