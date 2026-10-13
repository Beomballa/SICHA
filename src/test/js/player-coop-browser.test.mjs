import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import https from "node:https";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { coopFixture } from "../browser/coop-fixture.mjs";

const require = createRequire(
  new URL("../browser/package.json", import.meta.url),
);
const puppeteer = require("puppeteer-core");
const root = new URL("../../main/resources/", import.meta.url);

/** 실제 제품 문서/Fetch/저장소/독립 브라우저 문맥을 사용하며 회원·공개·채점 서버만 합성이다. */
test(
  "two cooperative actors strip invite secrets, see only own roles and explicitly accept a fixed proposal",
  { timeout: 60000 },
  async () => {
    const directory = mkdtempSync(join(tmpdir(), "sicha-coop-"));
    const fixture = coopFixture();
    let server, browser;
    const requests = [],
      errors = [];
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
          requests.push(request.url);
          response.setHeader("Cache-Control", "no-store");
          if (["/play", "/coop-invite"].includes(request.url)) {
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
          request.on("data", (value) => chunks.push(value));
          request.on("end", () => {
            const body = chunks.length
              ? JSON.parse(Buffer.concat(chunks).toString("utf8"))
              : {};
            response.setHeader("Content-Type", "application/json");
            if (request.url.startsWith("/api/member/browser-auth/")) {
              if (request.url.endsWith("/logout"))
                return response.end(
                  JSON.stringify({
                    state: "NO_SESSION",
                    requestId: randomUUID(),
                  }),
                );
              const actor = request.url.endsWith("/login/local")
                ? "guest"
                : (request.headers.cookie ?? "").includes(
                      "synthetic_actor=host",
                    )
                  ? "host"
                  : (request.headers.cookie ?? "").includes(
                        "synthetic_actor=guest",
                      )
                    ? "guest"
                    : null;
              if (!actor) {
                response.statusCode = 204;
                return response.end();
              }
              response.setHeader(
                "Set-Cookie",
                "synthetic_actor=" +
                  actor +
                  "; Path=/api/member/browser-auth; Secure; HttpOnly; SameSite=Strict",
              );
              return response.end(
                JSON.stringify({
                  tokenType: "Bearer",
                  accessToken: (actor === "host" ? "A" : "B").repeat(42) + "A",
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
            const actor = (request.headers.authorization ?? "").startsWith(
              "Bearer A",
            )
              ? "host"
              : "guest";
            const result = fixture.respond(
              actor,
              request.method,
              request.url,
              body,
            );
            response.statusCode = result.status;
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
      const hostContext = await browser.createBrowserContext(),
        guestContext = await browser.createBrowserContext();
      await hostContext.setCookie({
        name: "synthetic_actor",
        value: "host",
        url: origin + "/api/member/browser-auth",
        path: "/api/member/browser-auth",
        secure: true,
        httpOnly: true,
      });
      const host = await hostContext.newPage(),
        guest = await guestContext.newPage();
      for (const page of [host, guest]) {
        page.setDefaultTimeout(8000);
        page.on("pageerror", (error) => errors.push(error.message));
      }
      const idle = (page) => page.waitForSelector("main[aria-busy='false']");
      await host.goto(origin + "/play");
      await host.waitForSelector("#story-SYNTHETIC_CASE");
      await host.click("#story-SYNTHETIC_CASE");
      await idle(host);
      await host.select("#mode", "COOP");
      await host.click("#create-consent");
      await host.click("#create");
      await host.waitForSelector("#lobby:not([hidden])");
      await idle(host);
      await host.click("#issue-invite");
      await idle(host);
      const link = await host.$eval("#issued-link", (node) => node.value);
      assert.match(link, /#inviteKey=.*&inviteSecret=/);
      await guest.goto(link);
      await guest.waitForSelector("#invite-login:not([hidden])");
      await idle(guest);
      assert.equal(new URL(guest.url()).hash, "");
      await guest.type("#invite-email", "synthetic-guest@example.invalid");
      await guest.type("#invite-password", "Synthetic-guest-password-123!");
      await guest.click("#invite-login-button");
      await guest.waitForSelector("#invite-preview:not([hidden])");
      await idle(guest);
      assert.equal(await guest.$eval("#join", (node) => node.disabled), true);
      await guest.click("#join-consent");
      await guest.click("#join");
      await guest.waitForSelector("#lobby:not([hidden])");
      await idle(guest);
      await host.click("#refresh");
      await idle(host);
      await host.click("#ready-consent");
      await host.click("#ready");
      await idle(host);
      await guest.click("#refresh");
      await idle(guest);
      await guest.click("#ready-consent");
      await guest.click("#ready");
      await idle(guest);
      assert.equal(await guest.$eval("#start", (node) => node.disabled), true);
      await host.click("#refresh");
      await idle(host);
      await host.click("#start");
      await host.waitForSelector("#report-section:not([hidden])");
      await idle(host);
      await guest.click("#refresh");
      await guest.waitForSelector("#report-section:not([hidden])");
      await idle(guest);
      assert.match(
        await host.$eval("#clues", (node) => node.textContent),
        /PRIVATE_HOST_ONLY/,
      );
      assert.doesNotMatch(
        await host.$eval("#clues", (node) => node.textContent),
        /PRIVATE_GUEST_ONLY/,
      );
      assert.match(
        await guest.$eval("#clues", (node) => node.textContent),
        /PRIVATE_GUEST_ONLY/,
      );
      assert.doesNotMatch(
        await guest.$eval("#clues", (node) => node.textContent),
        /PRIVATE_HOST_ONLY/,
      );
      await host.select("#culprit", "P1");
      for (const field of ["method", "time", "motive", "evidence"])
        await host.type("#" + field, "합성 고정 " + field);
      await host.click("#save");
      await idle(host);
      await host.click("#submit-consent");
      await host.click("#submit");
      await host.waitForSelector("#proposal-section:not([hidden])");
      await idle(host);
      assert.equal(
        await host.$eval("#accept-proposal", (node) => node.disabled),
        true,
      );
      await guest.click("#refresh");
      await guest.waitForSelector("#proposal-section:not([hidden])");
      await idle(guest);
      await guest.click("#proposal-consent");
      await guest.click("#refresh");
      await idle(guest);
      assert.equal(
        await guest.$eval("#proposal-consent", (node) => node.checked),
        true,
        "같은 고정 제안의 재조회는 동의를 버리지 않는다",
      );
      await guest.type("#evidence", "새 입력");
      assert.equal(
        await guest.$eval("#proposal-consent", (node) => node.checked),
        false,
      );
      assert.equal(
        await guest.$eval("#accept-proposal", (node) => node.disabled),
        true,
      );
      await guest.click("#revert");
      await idle(guest);
      await guest.click("#proposal-consent");
      await guest.click("#accept-proposal");
      await idle(guest);
      assert.match(
        await guest.$eval("#game-counts", (node) => node.textContent),
        /PENDING/,
      );
      assert.equal(
        await guest.$eval("#roles", (node) => node.childElementCount),
        0,
      );
      assert.equal(
        fixture.calls.filter(
          (call) =>
            call.path.endsWith("/respond") && call.body.decision === "ACCEPT",
        ).length,
        1,
      );
      assert.equal(
        requests.some(
          (url) => url.includes("inviteSecret") || url.includes("#"),
        ),
        false,
      );
      for (const page of [host, guest])
        assert.doesNotMatch(
          await page.evaluate(() =>
            JSON.stringify({ ...localStorage, ...sessionStorage }),
          ),
          /inviteSecret|PRIVATE_|합성 고정/,
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
