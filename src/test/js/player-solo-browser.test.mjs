import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import https from "node:https";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { soloFixture } from "../browser/solo-fixture.mjs";

const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const root = new URL("../../main/resources/", import.meta.url);

/** 표시된 실제 배경을 합성해 문자·조작·포커스 대비와 읽기 폭을 검사한다. */
async function inspectPlayer(page) {
  const metrics = await page.evaluate(() => {
    const visible = (node) =>
      node.getClientRects().length && !node.closest("[hidden]");
    const rgba = (value) => value.match(/[\d.]+/g).map(Number);
    const blend = (front, back) =>
      front
        .slice(0, 3)
        .map(
          (channel, index) =>
            channel * (front[3] ?? 1) + back[index] * (1 - (front[3] ?? 1)),
        );
    const background = (node) => {
      const chain = [];
      for (let parent = node; parent; parent = parent.parentElement)
        chain.unshift(rgba(getComputedStyle(parent).backgroundColor));
      return chain.reduce((back, front) => blend(front, back), [255, 255, 255]);
    };
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
    const contrast = (front, back) => {
      const lights = [luminance(front), luminance(back)].sort((a, b) => b - a);
      return (lights[0] + 0.05) / (lights[1] + 0.05);
    };
    // 체크박스는 글자를 표시하지 않는다. 관련 라벨의 글자·48px 조작 영역은 별도로 측정한다.
    const nodes = [
      ...document.querySelectorAll(
        "h1,h2,h3,h4,p,label,button,input:not([type=checkbox]),textarea,select,a,.notice-body",
      ),
    ].filter(visible);
    const controls = nodes.filter((node) =>
      node.matches("button,textarea,select,input:not([type=checkbox])"),
    );
    const focus = document.activeElement;
    return {
      width: innerWidth,
      overflow:
        document.documentElement.scrollWidth >
        document.documentElement.clientWidth,
      minFont: Math.min(
        ...nodes.map((node) => parseFloat(getComputedStyle(node).fontSize)),
      ),
      minTextContrast: Math.min(
        ...nodes.map((node) =>
          contrast(
            blend(rgba(getComputedStyle(node).color), background(node)),
            background(node),
          ),
        ),
      ),
      minControlContrast: Math.min(
        ...controls.map((node) => {
          const border = rgba(getComputedStyle(node).borderTopColor);
          return contrast(border, background(node.parentElement));
        }),
      ),
      minHeight: Math.min(
        ...[...controls, ...document.querySelectorAll("label.check-label")]
          .filter(visible)
          .map((node) => node.getBoundingClientRect().height),
      ),
      maxReading: Math.max(
        ...[...document.querySelectorAll("p,.notice-body,form")]
          .filter(visible)
          .map((node) => node.getBoundingClientRect().width),
      ),
      clipped: nodes.some(
        (node) =>
          ["hidden", "clip"].includes(getComputedStyle(node).overflowY) &&
          node.scrollHeight > node.clientHeight + 1,
      ),
      areas: ["investigation-area", "report-area"].map((id) => {
        const node = document.getElementById(id),
          rect = node.getBoundingClientRect();
        return { visible: !!visible(node), x: rect.x, width: rect.width };
      }),
      focusVisible: !!visible(focus),
      focusOutline: getComputedStyle(focus).outlineStyle,
      focusWidth: parseFloat(getComputedStyle(focus).outlineWidth),
      focusContrast: contrast(
        rgba(getComputedStyle(focus).outlineColor),
        background(focus.parentElement),
      ),
    };
  });
  assert.equal(metrics.overflow, false);
  assert.equal(metrics.clipped, false);
  assert.ok(metrics.minFont >= 16);
  assert.ok(metrics.minTextContrast >= 4.5, JSON.stringify(metrics));
  assert.ok(metrics.minControlContrast >= 3, JSON.stringify(metrics));
  assert.ok(metrics.minHeight >= 48);
  assert.ok(metrics.maxReading <= 721);
  assert.equal(metrics.focusVisible, true);
  assert.equal(metrics.focusOutline, "solid");
  assert.equal(metrics.focusWidth, 3);
  assert.ok(metrics.focusContrast >= 3);
  if (metrics.width >= 900) {
    assert.ok(metrics.areas.every((area) => area.visible && area.width >= 320));
    assert.ok(metrics.areas[1].x > metrics.areas[0].x);
  } else assert.equal(metrics.areas.filter((area) => area.visible).length, 1);
}

/** 확장 API로 실제 탭 확대를 적용한다. CSS zoom·핀치는 사용하지 않는다. */
async function setChromeZoom(worker, page, factor) {
  await page.bringToFront();
  const actual = await worker.evaluate(
    async (url, zoom) => {
      const tab = (await chrome.tabs.query({})).find(
        (tab) => tab.url === url && tab.active,
      );
      await chrome.tabs.setZoom(tab.id, zoom);
      return chrome.tabs.getZoom(tab.id);
    },
    page.url(),
    factor,
  );
  assert.equal(actual, factor);
}

/** 제품 HTML/JS·실제 Chrome HTTPS를 검사하되 공개/채점 서버는 명시적 합성이다. */
test(
  "solo browser binds consent, preserves lost-save intent and displays only server result",
  { timeout: 120000 },
  async () => {
    const directory = mkdtempSync(join(tmpdir(), "sicha-solo-"));
    const fixture = soloFixture();
    fixture.materials.basic.intro =
      "합성 긴 조사 원문 ".repeat(160) + "LONG_IDENTIFIER_".repeat(20);
    let server,
      browser,
      loseSave = true;
    const errors = [],
      apiRequests = [];
    try {
      execFileSync(
        "openssl",
        [
          "req",
          "-x509",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-days",
          "1",
          "-subj",
          "/CN=localhost",
          "-keyout",
          join(directory, "key.pem"),
          "-out",
          join(directory, "cert.pem"),
        ],
        { timeout: 10000 },
      );
      server = https.createServer(
        {
          key: readFileSync(join(directory, "key.pem")),
          cert: readFileSync(join(directory, "cert.pem")),
        },
        (request, response) => {
          if (request.url.startsWith("/api/")) apiRequests.push(request.url);
          response.setHeader("Cache-Control", "no-store");
          if (request.url === "/play") {
            response.setHeader("Content-Type", "text/html");
            response.setHeader(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; form-action 'none'",
            );
            return response.end(
              readFileSync(new URL("templates/player/play.html", root)),
            );
          }
          if (
            request.url === "/player-public.css" ||
            /^\/player-common\/(?:web-api|web-session|game-model|game-client|play-page|invite-bootstrap)\.(?:mjs|js)$/.test(
              request.url,
            )
          ) {
            response.setHeader(
              "Content-Type",
              request.url.endsWith(".css") ? "text/css" : "text/javascript",
            );
            return response.end(
              readFileSync(new URL("static" + request.url, root)),
            );
          }
          const chunks = [];
          request.on("data", (chunk) => chunks.push(chunk));
          request.on("end", () => {
            if (request.url === "/api/member/browser-auth/refresh") {
              response.setHeader("Content-Type", "application/json");
              return response.end(
                JSON.stringify({
                  tokenType: "Bearer",
                  accessToken: "A".repeat(43),
                  accessExpiresAt: new Date(Date.now() + 600000).toISOString(),
                  sessionAbsoluteExpiresAt: new Date(
                    Date.now() + 1200000,
                  ).toISOString(),
                  sessionKey: randomUUID(),
                  webEpoch: request.headers["x-sicha-player-web-epoch"],
                  requestId: randomUUID(),
                }),
              );
            }
            if (!request.url.startsWith("/api/")) {
              response.statusCode = 404;
              return response.end();
            }
            const body = chunks.length
              ? JSON.parse(Buffer.concat(chunks).toString("utf8"))
              : {};
            const result = fixture.respond(request.method, request.url, body);
            // 헤더 전 소켓 종료는 Chrome이 네트워크에서 재전송하므로 확정 처리 뒤 잘린 JSON 영수증을 보낸다.
            if (request.method === "PATCH" && loseSave) {
              loseSave = false;
              response.statusCode = 200;
              response.setHeader("Content-Type", "application/json");
              response.end("{");
              return;
            }
            response.statusCode = result.status;
            if (result.status !== 204)
              response.setHeader("Content-Type", "application/json");
            response.end(
              result.status === 204 ? undefined : JSON.stringify(result.value),
            );
          });
        },
      );
      await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
      });
      const origin = "https://127.0.0.1:" + server.address().port;
      browser = await puppeteer.launch({
        executablePath:
          process.env.CHROME_BIN ||
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        headless: true,
        acceptInsecureCerts: true,
        enableExtensions: [
          join(process.cwd(), "src/test/browser/zoom-extension"),
        ],
        args: ["--no-sandbox", "--enable-unsafe-extension-debugging"],
      });
      const zoomWorker = await (
        await browser.waitForTarget(
          (target) => target.type() === "service_worker",
        )
      ).worker();
      const page = await browser.newPage();
      await page.setViewport({ width: 360, height: 900, deviceScaleFactor: 1 });
      page.setDefaultTimeout(8000);
      page.on("pageerror", () => errors.push("PAGE_ERROR"));
      await page.goto(origin + "/play");
      await page.waitForSelector("#story-SYNTHETIC_CASE");
      await page.click("#story-SYNTHETIC_CASE");
      await page.waitForSelector("#story:not([hidden])");
      assert.equal(await page.$eval("#create", (node) => node.disabled), true);
      assert.equal(
        await page.$eval("#notice-body", (node) => node.querySelector("img")),
        null,
      );
      await page.click("#create-consent");
      await page.click("#create");
      await page.waitForSelector("#start:not([hidden]):not(:disabled)");
      const create = fixture.calls.find((call) => call.path === "/api/games");
      assert.equal(create.body.mode, "SOLO");
      assert.equal(create.body.expectedPlayRev, "9007199254740993");
      await page.click("#start");
      await page.waitForSelector("#report-section:not([hidden])");
      await page.waitForSelector("main[aria-busy='false']");
      assert.equal(
        await page.$eval("#report-area", (node) => node.hidden),
        true,
      );
      assert.equal(
        await page.$eval("#clues", (node) => node.querySelector("script")),
        null,
      );
      assert.equal(
        await page.$eval("#clues", (node) =>
          node.textContent.includes("<script>합성"),
        ),
        true,
      );
      assert.equal(await page.$eval("#submit", (node) => node.disabled), true);
      await page.click("#clue-C1");
      await page.waitForFunction(
        () =>
          document.getElementById("clues").textContent.includes("읽음:") &&
          document.getElementById("main").getAttribute("aria-busy") === "false",
      );
      await page.click("#hint-1");
      await page.waitForFunction(
        () =>
          document.getElementById("hints").textContent.includes("합성 힌트") &&
          document.getElementById("main").getAttribute("aria-busy") === "false",
      );
      await page.click('[data-play-task="report"]');
      await page.select("#culprit", "P1");
      for (const field of ["method", "time", "motive", "evidence"])
        await page.type(
          "#" + field,
          field === "method" ? "  합성 원문\n🙂  " : "합성 " + field,
        );
      await page.$eval("#method", (node) => node.setSelectionRange(2, 7));
      await page.click('[data-play-task="investigation"]');
      await page.click('[data-play-task="report"]');
      assert.deepEqual(
        await page.$eval("#method", (node) => [
          node.selectionStart,
          node.selectionEnd,
        ]),
        [2, 7],
      );
      assert.equal(await page.$eval("#culprit", (node) => node.value), "P1");
      await page.click("#save");
      await page.waitForSelector("main[aria-busy='false']");
      assert.equal(
        fixture.calls.filter((call) => call.method === "PATCH").length,
        1,
        JSON.stringify(
          await page.evaluate(() => ({
            error: document.getElementById("error").textContent,
            disabled: document.getElementById("save").disabled,
          })),
        ),
      );
      await page.waitForSelector("#uncertain:not([hidden])");
      await page.waitForSelector("#replay:not(:disabled)");
      assert.equal(
        fixture.calls.filter((call) => call.method === "PATCH").length,
        1,
      );
      const first = fixture.calls.find((call) => call.method === "PATCH");
      const methodNode = await page.$("#method");
      const roomRevision = await page.$eval(
        "#game-state",
        (node) => node.textContent,
      );
      const beforeSwitch = apiRequests.length;
      await page.click('[data-play-task="investigation"]');
      assert.equal(
        await page.$eval(
          "#uncertain",
          (node) => !!node.getClientRects().length,
        ),
        true,
      );
      assert.equal(
        await page.$eval(
          "#report-edit-state",
          (node) => !!node.getClientRects().length,
        ),
        true,
      );
      await page.setViewport({ width: 900, height: 900 });
      await page.waitForFunction(
        () =>
          document.getElementById("play-workspace").dataset.parallel ===
            "true" &&
          document.getElementById("investigation-area").checkVisibility() &&
          document.getElementById("report-area").checkVisibility(),
      );
      await page.focus("#report-title");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "report-title",
      );
      await page.setViewport({ width: 899, height: 400 });
      await page.waitForFunction(() =>
        document.activeElement.matches('[data-play-task="investigation"]'),
      );
      await page.click('[data-play-task="report"]');
      assert.equal(
        await page.$eval(
          "#method",
          (node, original) => node === original,
          methodNode,
        ),
        true,
      );
      assert.equal(
        await page.$eval("#game-state", (node) => node.textContent),
        roomRevision,
      );
      assert.equal(
        apiRequests.length,
        beforeSwitch,
        "작업·크기 변경은 HTTP를 실행하지 않는다",
      );
      assert.equal(
        await page.$eval("#submit-consent", (node) => node.checked),
        false,
      );
      await page.click("#refresh");
      await page.waitForSelector("main[aria-busy='false']");
      assert.equal(
        await page.$eval("#uncertain", (node) => node.hidden),
        false,
      );
      assert.equal(
        await page.$eval("#method", (node) => node.value),
        "  합성 원문\n🙂  ",
      );
      await page.click("#replay");
      await page.waitForSelector("#uncertain[hidden]");
      await page.waitForSelector("main[aria-busy='false']");
      const saves = fixture.calls.filter((call) => call.method === "PATCH");
      assert.deepEqual(saves[1].body, first.body);
      await page.type("#evidence", " · 추가 입력 보존");
      await page.click('[data-play-task="investigation"]');
      await page.click("#hint-2");
      await page.waitForSelector("main[aria-busy='false']");
      assert.equal(await page.$eval("#save", (node) => node.disabled), true);
      assert.match(
        await page.$eval("#evidence", (node) => node.value),
        /추가 입력 보존/,
      );
      await page.click('[data-play-task="report"]');
      await page.click("#accept-latest");
      await page.click("#save");
      await page.waitForSelector("main[aria-busy='false']");
      assert.match(fixture.report.evidence, /추가 입력 보존/);
      const evidenceNode = await page.$("#evidence");
      const evidenceValue = await page.$eval("#evidence", (node) => node.value);
      for (const width of [360, 768, 899, 900, 1200, 1440]) {
        await page.setViewport({ width, height: 900 });
        for (const task of ["investigation", "report"]) {
          await page.click(`[data-play-task="${task}"]`);
          await page.focus(`[data-play-task="${task}"]`);
          await page.keyboard.press("Tab");
          await inspectPlayer(page);
        }
        assert.equal(
          await page.$eval(
            "#evidence",
            (node, original) => node === original,
            evidenceNode,
          ),
          true,
        );
        assert.equal(
          await page.$eval("#evidence", (node) => node.value),
          evidenceValue,
        );
        assert.equal(
          await page.evaluate(
            () =>
              document.documentElement.scrollWidth <=
              document.documentElement.clientWidth,
          ),
          true,
        );
        const sizes = await page.$$eval(
          "button, label.check-label, textarea, select",
          (elements) =>
            elements
              .filter((node) => node.getClientRects().length)
              .map((node) => node.getBoundingClientRect().height),
        );
        assert.ok(sizes.every((height) => height >= 48));
      }
      await setChromeZoom(zoomWorker, page, 2);
      await page.waitForFunction(() => innerWidth === 720);
      await page.click('[data-play-task="report"]');
      await page.focus('[data-play-task="report"]');
      await page.keyboard.press("Tab");
      await inspectPlayer(page);
      await setChromeZoom(zoomWorker, page, 1);
      await page.waitForFunction(() => innerWidth === 1440);
      await page.setViewport({ width: 360, height: 400 });
      await page.click('[data-play-task="report"]');
      await page.focus("#method");
      await page.$eval("#save", (node) => node.scrollIntoView());
      await page.waitForFunction(
        () =>
          document.getElementById("save").getBoundingClientRect().bottom <=
          visualViewport.height,
      );
      assert.equal(
        await page.$eval(
          "#save",
          (node) =>
            node.getBoundingClientRect().bottom <= visualViewport.height,
        ),
        true,
      );
      // 짧은 viewport는 물리 OS 키보드의 표시·safe-area 증거가 아니다.
      await page.setViewport({ width: 1440, height: 900 });
      await page.click("#submit-consent");
      await page.click("#submit");
      await page.waitForFunction(
        () =>
          document
            .getElementById("game-counts")
            .textContent.includes("PENDING") &&
          document.getElementById("main").getAttribute("aria-busy") === "false",
      );
      assert.equal(await page.$eval("#result", (node) => node.hidden), true);
      assert.equal(
        await page.$eval("#forfeit-section", (node) => node.hidden),
        true,
      );
      assert.equal(
        await page.$eval("#play-workspace", (node) => node.hidden),
        true,
      );
      assert.equal(
        await page.$eval("#play-task-nav", (node) => node.hidden),
        true,
      );
      assert.equal(await page.$eval("#method", (node) => node.value), "");
      const reads = fixture.calls.filter((call) =>
        call.path.endsWith("/materials"),
      ).length;
      fixture.state.playDeadline = new Date(Date.now() - 1).toISOString();
      fixture.materials.playDeadline = fixture.state.playDeadline;
      await page.click("#refresh");
      await page.waitForSelector("main[aria-busy='false']");
      assert.equal(
        fixture.calls.filter((call) => call.path.endsWith("/materials")).length,
        reads,
      );
      assert.equal(
        await page.$eval("#roles", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$eval("#result", (node) => node.hidden), true);
      fixture.complete();
      await page.click("#refresh");
      await page.waitForSelector("#result:not([hidden])");
      assert.match(
        await page.$eval("#result-summary", (node) => node.textContent),
        /SUCCESS.*90/,
      );
      assert.equal(
        await page.$eval("#reveal", (node) => node.querySelector("script")),
        null,
      );
      assert.equal(await page.evaluate(() => sessionStorage.length), 0);
      assert.doesNotMatch(
        await page.evaluate(() => JSON.stringify({ ...localStorage })),
        /합성 원문|accessToken|flowBinder/,
      );
      const other = await browser.newPage();
      await other.goto("about:blank");
      await other.bringToFront();
      await page.bringToFront();
      assert.equal(await page.$eval("#reveal", (node) => node.textContent), "");
      assert.equal(
        await page.$$eval("textarea", (elements) =>
          elements.every(
            (node) => node.value === "" && node.defaultValue === "",
          ),
        ),
        true,
      );
      assert.deepEqual(errors, []);
    } finally {
      await browser?.close();
      if (server) {
        server.closeAllConnections();
        await new Promise((resolve) => server.close(resolve));
      }
      rmSync(directory, { recursive: true, force: true });
    }
  },
);
