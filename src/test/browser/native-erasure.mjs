import assert from "node:assert/strict";
import puppeteer from "puppeteer-core";
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const input = JSON.parse(Buffer.concat(chunks).toString("utf8"));
let browser,
  stage = "start";
try {
  browser = await puppeteer.launch({
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    acceptInsecureCerts: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage();
  page.setDefaultTimeout(10000);
  if (input.loseReceipt) {
    const interceptor = await page.createCDPSession();
    await interceptor.send("Fetch.enable", {
      patterns: [
        {
          urlPattern: input.origin + "/api/member/auth/account/delete",
          requestStage: "Response",
        },
      ],
    });
    interceptor.on("Fetch.requestPaused", async (event) => {
      try {
        assert.equal(
          event.responseStatusCode,
          202,
          "실제 서버 접수 이후에만 응답 유실 재현",
        );
        await interceptor.send("Fetch.fulfillRequest", {
          requestId: event.requestId,
          responseCode: 202,
          responseHeaders: [
            { name: "Content-Type", value: "application/json" },
            { name: "Cache-Control", value: "no-store" },
          ],
          body: Buffer.from('{"state":').toString("base64"),
        });
        await interceptor.send("Fetch.disable");
      } catch (_) {
        process.exitCode = 1;
      }
    });
  }
  const errors = [],
    commands = [],
    statusHeaders = [];
  page.on("pageerror", (failure) => errors.push(failure.name));
  page.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (path === "/api/member/auth/account/delete")
      commands.push(request.method());
    if (path === "/api/member/privacy/erase/status")
      statusHeaders.push(request.headers());
  });
  const idle = () => page.waitForSelector("main[aria-busy='false']");
  await page.goto(input.origin + "/account");
  await idle();
  await page.type("#login-email", input.email);
  await page.type("#login-password", input.password);
  await page.click("#login-form button[type=submit]");
  await page.waitForSelector("#security:not([hidden])");
  await idle();
  stage = "delete-proof";
  await page.select("#security-purpose", "DELETE_ACCOUNT");
  await page.type("#recent-password", input.password);
  await page.click("#recent-form button[type=submit]");
  await page.waitForSelector("#security-complete:not([hidden])");
  await idle();
  await page.click("#security-consent");
  await page.click("#security-complete button[type=submit]");
  assert.equal(commands.length, 0, "원문 공동/불가역 동의 없이 탈퇴 전송0");
  await page.click("#deletion-shared");
  await page.click("#deletion-irreversible");
  stage = "delete-accepted";
  await page.click("#security-complete button[type=submit]");
  await page.waitForSelector("#erasure:not([hidden])");
  await idle();
  assert.equal(commands.length, 1);
  assert.match(
    await page.$eval("#erasure-status", (node) => node.textContent),
    /접수|회수/,
  );
  assert.equal(await page.$eval("#security", (node) => node.hidden), true);
  assert.equal(
    await page.$$eval("input", (nodes) =>
      nodes.every(
        (node) =>
          node.value === "" && node.defaultValue === "" && !node.checked,
      ),
    ),
    true,
  );
  assert.doesNotMatch(
    await page.evaluate(() =>
      JSON.stringify({ ...localStorage, ...sessionStorage }),
    ),
    /receiptToken|eraseKey|reauthGrant|Synthetic/,
  );
  assert.equal(new URL(page.url()).hash, "#erasure");
  stage = "receipt-status";
  if (input.loseReceipt) {
    assert.equal(
      await page.$eval("#erasure-read", (node) => node.disabled),
      true,
    );
    assert.match(
      await page.$eval("#error", (node) => node.textContent),
      /영수증|자동 재전송/,
    );
    assert.equal(statusHeaders.length, 0);
  } else {
    await page.click("#erasure-read");
    await idle();
    assert.match(
      await page.$eval("#erasure-status", (node) => node.textContent),
      /전체 파기 완료가 아닙니다/,
    );
    assert.equal(statusHeaders.length, 1);
    assert.equal(statusHeaders[0].authorization, undefined);
    assert.equal(statusHeaders[0].cookie, undefined);
  }
  for (const width of [360, 768, 900, 1200, 1440]) {
    await page.setViewport({ width, height: 900 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
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
  stage = "background-receipt-purge";
  const other = await browser.newPage();
  await other.goto("about:blank");
  await other.bringToFront();
  await page.bringToFront();
  await idle();
  assert.equal(
    await page.$eval("#erasure-read", (node) => node.disabled),
    true,
  );
  assert.equal(
    await page.$eval("#erasure-status", (node) => node.textContent),
    "",
  );
  await other.close();
  await page.reload();
  await idle();
  assert.equal(
    await page.$eval("#erasure-read", (node) => node.disabled),
    true,
  );
  assert.equal(commands.length, 1);
  assert.equal(statusHeaders.length, input.loseReceipt ? 0 : 1);
  assert.deepEqual(errors, []);
  process.stdout.write(
    JSON.stringify({
      ok: true,
      deletionPosts: commands.length,
      statusPosts: statusHeaders.length,
    }),
  );
} catch (failure) {
  // 영수증/자격/서버 원문은 출력하지 않고 고정 단계/종류만 남긴다.
  process.stderr.write(
    JSON.stringify({
      stage,
      name: failure.name,
      code: "ERASURE_BROWSER_FAILED",
    }),
  );
  process.exitCode = 1;
} finally {
  await browser?.close();
}
