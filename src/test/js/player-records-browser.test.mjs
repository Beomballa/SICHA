import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import https from "node:https";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { recordFixture } from "../browser/record-fixture.mjs";
const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const root = new URL("../../main/resources/", import.meta.url);

/** 실제 제품 문서/Chrome HTTPS를 사용하되 기록/인증 응답은 명시적인 합성이다. */
test(
  "records browser explicitly reads owned raw reports, drops old erased keys and fences late responses",
  { timeout: 60000 },
  async () => {
    const directory = mkdtempSync(join(tmpdir(), "sicha-records-"));
    const fixture = recordFixture();
    let server,
      browser,
      delay = false,
      release;
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
          if (request.url === "/records") {
            response.setHeader("Content-Type", "text/html");
            response.setHeader(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; form-action 'none'",
            );
            return response.end(
              readFileSync(new URL("templates/player/records.html", root)),
            );
          }
          if (
            request.url === "/player-public.css" ||
            /^\/player-common\/(?:web-api|web-session|game-client|game-model|records-page)\.mjs$/.test(
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
          response.setHeader("Content-Type", "application/json");
          if (request.url === "/api/member/browser-auth/refresh")
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
          if (!request.url.startsWith("/api/member/records")) {
            response.statusCode = 404;
            return response.end();
          }
          const result = fixture.respond(request.url);
          const send = () => {
            if (response.writableEnded) return;
            response.statusCode = result.status;
            response.end(JSON.stringify(result.value));
          };
          if (delay && request.url.includes("/reports/")) {
            delay = false;
            release = send;
          } else send();
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
      page.on("pageerror", (failure) => errors.push(failure.message));
      const idle = () => page.waitForSelector("main[aria-busy='false']");
      await page.goto(origin + "/records");
      await page.waitForSelector("#record-" + fixture.detail.recordKey);
      await idle();
      assert.equal(
        fixture.calls.some((path) => path.includes("/reports/")),
        false,
      );
      assert.match(
        await page.$eval("#records", (node) => node.textContent),
        /미측정/,
      );
      await page.click("#next");
      await idle();
      assert.equal(
        fixture.calls.filter((path) => path.includes("cursor=opaque%2Ftoken"))
          .length,
        1,
      );
      await page.click("#record-" + fixture.detail.recordKey);
      await page.waitForSelector("#detail:not([hidden])");
      await idle();
      assert.equal(
        fixture.calls.some((path) => path.includes("/reports/")),
        false,
      );
      assert.equal(
        await page.$eval("#reveal", (node) => node.querySelector("script")),
        null,
      );
      await page.click("#report-" + fixture.report.reportKey);
      await page.waitForSelector("#raw:not([hidden])");
      await idle();
      assert.match(
        await page.$eval("#report-body", (node) => node.textContent),
        /PRIVATE_RECORD_BODY/,
      );
      assert.equal(
        await page.$eval("#report-body", (node) =>
          node.querySelector("script"),
        ),
        null,
      );
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
        assert.ok(
          (
            await page.$$eval("button", (nodes) =>
              nodes
                .filter((node) => node.getClientRects().length)
                .map((node) => node.getBoundingClientRect().height),
            )
          ).every((height) => height >= 48),
        );
      }
      await page.click("#close-raw");
      await idle();
      assert.equal(
        await page.$eval("#report-body", (node) => node.textContent),
        "",
      );
      delay = true;
      await page.click("#report-" + fixture.report.reportKey);
      await page.waitForSelector("main[aria-busy='true']");
      const other = await browser.newPage();
      await other.goto("about:blank");
      await other.bringToFront();
      assert.ok(release);
      release();
      await page.bringToFront();
      await idle();
      assert.equal(
        await page.$eval("#report-body", (node) => node.textContent),
        "",
      );
      assert.equal(await page.$eval("#detail", (node) => node.hidden), true);
      assert.doesNotMatch(
        await page.evaluate(() =>
          JSON.stringify({ ...localStorage, ...sessionStorage }),
        ),
        /PRIVATE_RECORD_BODY|revealText|reportKey/,
      );
      await other.close();
      fixture.erase();
      await page.click("#connect");
      await idle();
      // 이전 URL의 RAW 키는404로 남고 새 PURGED 키로 목록부터 직접 조회한다.
      assert.equal(await page.$eval("#error", (node) => node.hidden), false);
      assert.equal(await page.$eval("#empty", (node) => node.hidden), true);
      await page.click("#list");
      await idle();
      await page.click("#record-" + fixture.purged.recordKey);
      await idle();
      assert.equal(await page.$eval("#purged", (node) => node.hidden), false);
      assert.equal(await page.$eval("#normal", (node) => node.hidden), true);
      for (const id of ["timing", "reports", "reveal", "report-body", "score"])
        assert.equal(
          await page.$eval("#" + id, (node) => node.textContent),
          "",
        );
      assert.deepEqual(errors, []);
    } finally {
      if (release) release();
      await browser?.close();
      if (server) {
        server.closeAllConnections();
        await new Promise((resolve) => server.close(resolve));
      }
      rmSync(directory, { recursive: true, force: true });
    }
  },
);
