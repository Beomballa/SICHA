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

/** 제품 HTML/JS·실제 Chrome HTTPS를 검사하되 공개/채점 서버는 명시적 합성이다. */
test(
  "solo browser binds consent, preserves lost-save intent and displays only server result",
  { timeout: 60000 },
  async () => {
    const directory = mkdtempSync(join(tmpdir(), "sicha-solo-"));
    const fixture = soloFixture();
    let server,
      browser,
      loseSave = true;
    const errors = [];
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
            /^\/player-common\/(?:web-api|web-session|solo-model|solo-client|solo-page)\.mjs$/.test(
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
        args: ["--no-sandbox"],
      });
      const page = await browser.newPage();
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
      await page.select("#culprit", "P1");
      for (const field of ["method", "time", "motive", "evidence"])
        await page.type(
          "#" + field,
          field === "method" ? "  합성 원문\n🙂  " : "합성 " + field,
        );
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
      await page.click("#hint-2");
      await page.waitForSelector("main[aria-busy='false']");
      assert.equal(await page.$eval("#save", (node) => node.disabled), true);
      assert.match(
        await page.$eval("#evidence", (node) => node.value),
        /추가 입력 보존/,
      );
      await page.click("#accept-latest");
      await page.click("#save");
      await page.waitForSelector("main[aria-busy='false']");
      assert.match(fixture.report.evidence, /추가 입력 보존/);
      for (const width of [360, 768, 900, 1200, 1440]) {
        await page.setViewport({ width, height: 900 });
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
