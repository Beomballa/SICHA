import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import https from "node:https";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const assets = Object.fromEntries(
  ["web-api.mjs", "web-session.mjs"].map((name) => [
    "/player-common/" + name,
    readFileSync(
      new URL(
        "../../main/resources/static/player-common/" + name,
        import.meta.url,
      ),
      "utf8",
    ),
  ]),
);

/** 실제 루프백 HTTPS·Chrome·Fetch·Web Locks를 사용하며 회원 응답만 합성으로 격리한다. */
test(
  "native web modules run in Chrome without Flutter and purge access across tabs",
  { timeout: 60000 },
  async () => {
    const directory = mkdtempSync(join(tmpdir(), "sicha-web-foundation-"));
    let browser;
    let server;
    const calls = [];
    const errors = [];
    try {
      // 폐기형 루프백 시험 전용 자격이며 운영 키나 실제 계정을 사용하지 않는다.
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
          if (request.url === "/foundation") {
            response.setHeader("Content-Type", "text/html");
            response.setHeader(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'",
            );
            return response.end(
              "<!doctype html><html lang=ko><title>합성 웹 기반 검증</title><main>공통 모듈 시험</main></html>",
            );
          }
          if (assets[request.url]) {
            response.setHeader("Content-Type", "text/javascript");
            return response.end(assets[request.url]);
          }
          if (request.url.startsWith("/api/")) {
            calls.push({
              path: request.url,
              method: request.method,
              headers: request.headers,
            });
            let value;
            if (request.url.endsWith("/logout"))
              value = { state: "LOGGED_OUT", requestId: randomUUID() };
            else if (request.url === "/api/member/auth/me")
              value = { nickname: "합성 회원" };
            else
              value = {
                tokenType: "Bearer",
                accessToken: "A".repeat(43),
                accessExpiresAt: new Date(Date.now() + 60000).toISOString(),
                sessionAbsoluteExpiresAt: new Date(
                  Date.now() + 120000,
                ).toISOString(),
                sessionKey: randomUUID(),
                webEpoch: request.headers["x-sicha-player-web-epoch"],
                requestId: randomUUID(),
              };
            response.setHeader("Content-Type", "application/json");
            return response.end(JSON.stringify(value));
          }
          response.statusCode = 404;
          response.end();
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
        // 자체 서명 수락은 독립된 폐기형 브라우저에만 적용하고 제품 TLS 검증은 바꾸지 않는다.
        acceptInsecureCerts: true,
        args: ["--no-sandbox"],
      });

      /** 루프백 원점 외의 요청을 막고 실제 제품 모듈을 로드한다. */
      async function page() {
        const tab = await browser.newPage();
        tab.on("pageerror", (failure) => errors.push(failure.message));
        await tab.setRequestInterception(true);
        tab.on("request", (request) =>
          new URL(request.url()).origin === origin
            ? request.continue()
            : request.abort(),
        );
        await tab.goto(origin + "/foundation");
        await tab.evaluate(async () => {
          const { createWebSession } =
            await import("/player-common/web-session.mjs");
          window.session = createWebSession();
          window.purges = 0;
          window.session.onClear(() => {
            window.purges += 1;
          });
        });
        return tab;
      }

      const a = await page();
      await browser.defaultBrowserContext().setCookie({
        name: "synthetic-wide-cookie",
        value: "synthetic-only",
        url: origin,
        secure: true,
      });
      assert.equal(
        await a.evaluate(() =>
          window.session.login(
            "synthetic@example.invalid",
            "synthetic-password",
          ),
        ),
        true,
      );
      assert.deepEqual(
        await a.evaluate(() => window.session.getCurrentMember()),
        { nickname: "합성 회원" },
      );
      const b = await page();
      await b.bringToFront();
      assert.equal(await b.evaluate(() => window.session.restore()), true);
      const marker = await b.evaluate(() =>
        localStorage.getItem("sicha.player.auth.v1"),
      );
      assert.doesNotMatch(
        marker,
        /AAAA|synthetic-password|accessToken|refreshToken/,
      );
      await b.evaluate(() => window.session.logout());
      await a.bringToFront();
      await a.waitForFunction(
        () => !window.session.current() && window.purges > 0,
      );
      const me = calls.find((call) => call.path === "/api/member/auth/me");
      const login = calls.find((call) => call.path.endsWith("/login/local"));
      assert.equal(me.headers.cookie, undefined);
      assert.equal(me.headers["x-sicha-player-web-epoch"], undefined);
      assert.equal(me.headers.authorization, "Bearer " + "A".repeat(43));
      assert.match(
        login.headers.cookie,
        /synthetic-wide-cookie=synthetic-only/,
      );
      assert.equal(login.headers.authorization, undefined);
      assert.equal(login.headers["sec-fetch-site"], "same-origin");
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
