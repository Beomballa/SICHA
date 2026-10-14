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
  { timeout: 120000 },
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
      // 기본 문맥의 host 탭만 확장에서 확대하며 guest는 독립 문맥을 유지한다.
      const hostContext = browser.defaultBrowserContext(),
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
        await page.setViewport({
          width: 360,
          height: 900,
          deviceScaleFactor: 1,
        });
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
      for (const [page, forbidden] of [
        [host, "PRIVATE_GUEST_ONLY"],
        [guest, "PRIVATE_HOST_ONLY"],
      ]) {
        const editor = await page.$("#method");
        for (const width of [360, 768, 899, 900, 1200, 1440]) {
          await page.setViewport({ width, height: 900 });
          for (const task of ["investigation", "report"]) {
            await page.click(`[data-play-task="${task}"]`);
            const layout = await page.evaluate(() => {
              const areas = ["investigation-area", "report-area"].map((id) => {
                const node = document.getElementById(id),
                  box = node.getBoundingClientRect();
                return {
                  visible: !!node.getClientRects().length,
                  x: box.x,
                  width: box.width,
                };
              });
              return {
                overflow:
                  document.documentElement.scrollWidth >
                  document.documentElement.clientWidth,
                areas,
                font: getComputedStyle(document.getElementById("method"))
                  .fontSize,
                focusVisible: !!document.activeElement.getClientRects().length,
                heights: [
                  ...document.querySelectorAll(
                    "button,textarea,select,label.check-label",
                  ),
                ]
                  .filter((node) => node.getClientRects().length)
                  .map((node) => node.getBoundingClientRect().height),
              };
            });
            assert.equal(layout.overflow, false);
            assert.equal(layout.font, "16px");
            assert.equal(layout.focusVisible, true);
            assert.ok(layout.heights.every((height) => height >= 48));
            if (width >= 900) {
              assert.ok(
                layout.areas.every((area) => area.visible && area.width >= 320),
              );
              assert.ok(layout.areas[1].x > layout.areas[0].x);
            } else
              assert.equal(
                layout.areas.filter((area) => area.visible).length,
                1,
              );
            assert.doesNotMatch(
              await page.$eval("#investigation", (node) => node.textContent),
              new RegExp(forbidden),
            );
            assert.equal(
              await page.$eval(
                "#method",
                (node, original) => node === original,
                editor,
              ),
              true,
            );
          }
        }
      }
      const zoom = await zoomWorker.evaluate(async (url) => {
        const tab = (await chrome.tabs.query({})).find(
          (tab) => tab.url === url && tab.active,
        );
        await chrome.tabs.setZoom(tab.id, 2);
        return chrome.tabs.getZoom(tab.id);
      }, host.url());
      assert.equal(zoom, 2);
      await host.waitForFunction(() => innerWidth === 720);
      await host.click('[data-play-task="report"]');
      assert.equal(
        await host.$eval("#investigation-area", (node) => node.hidden),
        true,
      );
      assert.equal(
        await host.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await zoomWorker.evaluate(async (url) => {
        const tab = (await chrome.tabs.query({})).find(
          (tab) => tab.url === url && tab.active,
        );
        await chrome.tabs.setZoom(tab.id, 1);
      }, host.url());
      await host.waitForFunction(() => innerWidth === 1440);
      await host.setViewport({ width: 360, height: 900 });
      await host.click('[data-play-task="report"]');
      await host.select("#culprit", "P1");
      for (const field of ["method", "time", "motive", "evidence"])
        await host.type("#" + field, "합성 고정 " + field);
      await host.click("#refresh");
      await idle(host);
      const hostInput = await host.$("#method");
      const roomRevision = await host.$eval(
        "#game-state",
        (node) => node.textContent,
      );
      const localRequests = [];
      const observeLocalRequest = (request) => {
        if (new URL(request.url()).pathname.startsWith("/api/"))
          localRequests.push(request.url());
      };
      host.on("request", observeLocalRequest);
      await host.click('[data-play-task="investigation"]');
      await host.setViewport({ width: 900, height: 900 });
      await host.waitForFunction(
        () =>
          document.getElementById("play-workspace").dataset.parallel ===
            "true" &&
          document.getElementById("investigation-area").checkVisibility() &&
          document.getElementById("report-area").checkVisibility(),
      );
      await host.focus("#report-title");
      assert.equal(
        await host.evaluate(() => document.activeElement.id),
        "report-title",
      );
      await host.setViewport({ width: 899, height: 400 });
      await host.waitForFunction(() =>
        document.activeElement.matches('[data-play-task="investigation"]'),
      );
      await host.click('[data-play-task="report"]');
      host.off("request", observeLocalRequest);
      assert.deepEqual(
        localRequests,
        [],
        "로컬 전환은 HTTP·저장·동의를 만들지 않는다",
      );
      assert.equal(
        await host.$eval(
          "#method",
          (node, original) => node === original,
          hostInput,
        ),
        true,
      );
      assert.equal(
        await host.$eval("#method", (node) => node.value),
        "합성 고정 method",
      );
      assert.equal(await host.$eval("#culprit", (node) => node.value), "P1");
      assert.equal(
        await host.$eval("#game-state", (node) => node.textContent),
        roomRevision,
      );
      assert.equal(
        await host.$eval("#submit-consent", (node) => node.checked),
        false,
      );
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
      await guest.click('[data-play-task="report"]');
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
        await guest.$eval("#play-task-nav", (node) => node.hidden),
        true,
      );
      assert.equal(
        await guest.$eval("#report-area", (node) => node.hidden),
        true,
      );
      assert.equal(await guest.$eval("#evidence", (node) => node.value), "");
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
