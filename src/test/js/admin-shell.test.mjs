import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const root = new URL("../../main/resources/", import.meta.url);
const fragment = readFileSync(
  new URL("templates/admin/fragments.html", root),
  "utf8",
).match(/<aside[\s\S]*?<\/aside>/)[0];

/** 합성 필드와 실제 공통 탐색 컴포넌트만 사용한다. 인증·관리 API는 실행하지 않는다. */
test("shared sidebar folds accessibly without draft mutation at five widths and semantic badges retain contrast", async () => {
  const browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    args: ["--no-sandbox"],
  });
  try {
    const page = await browser.newPage();
    const unexpected = [];
    await page.setRequestInterception(true);
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (["/admin/ui.css", "/admin/shell.js"].includes(path)) {
        request.respond({
          status: 200,
          contentType: path.endsWith(".css")
            ? "text/css"
            : "application/javascript",
          body: readFileSync(new URL(`static${path}`, root), "utf8"),
        });
      } else if (path === "/favicon.ico") {
        request.respond({ status: 204 });
      } else if (request.url() === "https://ui-synthetic.example/") {
        request.respond({
          status: 200,
          contentType: "text/html",
          body: `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/admin/ui.css"><script defer src="/admin/shell.js"></script><body class="admin-app">${fragment}<main class="admin-workspace"><label for="draft">합성 미저장 원고</label><input id="draft" value="합성 보존 버퍼"><p><span class="ui-badge ui-badge-info" id="info">합성 안내 상태</span><span class="ui-badge ui-badge-success" id="success">합성 확인 상태</span></p></main></body></html>`,
        });
      } else {
        unexpected.push(path);
        request.abort();
      }
    });
    await page.goto("https://ui-synthetic.example/");
    await page.waitForNetworkIdle();
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900 });
      await page.waitForFunction(
        (desktop) => document.getElementById("admin-menu").hidden === !desktop,
        {},
        width >= 1200,
      );
      if (width < 1200) {
        await page.click("#admin-nav-toggle");
        await page.focus('.admin-nav-list a[href="/admin/stories"]');
        await page.keyboard.press("Escape");
        assert.equal(
          await page.evaluate(() => document.activeElement.id),
          "admin-nav-toggle",
        );
        assert.equal(
          await page.$eval("#admin-nav-toggle", (node) =>
            node.getAttribute("aria-expanded"),
          ),
          "false",
        );
        await page.click("#admin-nav-toggle");
      }
      assert.equal(
        await page.$eval("#draft", (input) => input.value),
        "합성 보존 버퍼",
      );
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      );
      const ratios = await page.$$eval("#info,#success", (nodes) => {
        const luminance = (color) =>
          color
            .match(/[\d.]+/g)
            .slice(0, 3)
            .map(Number)
            .map((value) => value / 255)
            .reduce(
              (sum, value, index) =>
                sum +
                [0.2126, 0.7152, 0.0722][index] *
                  (value <= 0.04045
                    ? value / 12.92
                    : ((value + 0.055) / 1.055) ** 2.4),
              0,
            );
        return nodes.map((node) => {
          const style = getComputedStyle(node);
          const [a, b] = [
            luminance(style.color),
            luminance(style.backgroundColor),
          ].sort((a, b) => b - a);
          return (a + 0.05) / (b + 0.05);
        });
      });
      assert.ok(ratios.every((ratio) => ratio >= 4.5));
      if (width < 1200) {
        await page.click("#admin-nav-toggle");
        assert.equal(
          await page.$eval("#admin-menu", (node) => node.hidden),
          true,
        );
      }
    }
    assert.deepEqual(unexpected, []);
  } finally {
    await browser.close();
  }
});
