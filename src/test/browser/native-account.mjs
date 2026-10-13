import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { createInterface } from "node:readline";

const require = createRequire(new URL("./package.json", import.meta.url));
const puppeteer = require("puppeteer-core");
const lines = createInterface({ input: process.stdin });
const iterator = lines[Symbol.asyncIterator]();
const input = JSON.parse((await iterator.next()).value);
let stage = "launch";
let browser;
const runtimeErrors = [];
let unexpectedRequests = 0;
const counts = new Map();

/** 실제 키보드로 현재 폼의 입력을 채운다. 제품 변수나 DOM 값에 대입하지 않는다. */
async function type(page, selector, value) {
  await page.waitForSelector(selector, { visible: true });
  await page.click(selector);
  await page.type(selector, value);
}

/** 실제 명시 클릭 이후 현재 요청 종료와 오류 없음까지 확인한다. */
async function click(page, selector) {
  await page.click(selector);
  await page.waitForSelector("main[aria-busy='false']");
  assert.equal(
    await page.$eval("#error", (element) => element.hidden),
    true,
    stage,
  );
}

/** 코드 원문은 격리 SMTP 시험 stdin으로만 받아 실제 폼에 입력한다. */
async function verifyMail(page) {
  const message = JSON.parse((await iterator.next()).value);
  await type(page, "#flow-code", message.code);
  await click(page, "#email-verify button[type=submit]");
  await page.waitForSelector("#email-complete:not([hidden])");
}

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
  page.on("pageerror", () => runtimeErrors.push("RUNTIME_ERROR"));
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== input.origin) unexpectedRequests += 1;
    if (url.pathname.startsWith("/api/"))
      counts.set(url.pathname, (counts.get(url.pathname) || 0) + 1);
  });
  page.setDefaultTimeout(12000);
  stage = "introduction";
  await page.goto(input.origin + "/");
  assert.equal(
    await page.$eval(
      "a[href='/account#signup']",
      (element) => element.textContent,
    ),
    "회원가입",
  );
  await page.click("a[href='/account#signup']");
  await page.waitForSelector("main[aria-busy='false']");
  stage = "signup-start";
  await type(page, "#flow-email", input.email);
  await click(page, "#email-start button[type=submit]");
  stage = "signup-verify";
  await verifyMail(page);
  stage = "signup-complete";
  assert.notEqual(
    await page.$eval("#notice-body", (element) => element.textContent.trim()),
    "",
  );
  await type(page, "#nickname", input.nickname);
  await type(page, "#flow-password", input.password);
  await type(page, "#flow-confirm", input.password);
  await page.click("#signup-consent");
  await click(page, "#email-complete button[type=submit]");
  await page.waitForSelector("#security:not([hidden])");
  assert.match(
    await page.$eval("#member-name", (element) => element.textContent),
    new RegExp(input.nickname),
  );

  stage = "responsive";
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
      "button:not(:disabled), input:not([type=checkbox]), select, label.check-label",
      (elements) =>
        elements
          .filter((element) => element.getClientRects().length)
          .map((element) => element.getBoundingClientRect().height),
    );
    assert.ok(
      sizes.every((height) => height >= 48),
      `width ${width} accessible targets`,
    );
  }
  stage = "reload";
  const beforeRefresh = counts.get("/api/member/browser-auth/refresh");
  await page.reload();
  await page.waitForSelector("#security:not([hidden])");
  await page.waitForSelector("main[aria-busy='false']");
  assert.equal(
    counts.get("/api/member/browser-auth/refresh"),
    beforeRefresh + 1,
  );
  assert.equal(await page.evaluate(() => sessionStorage.length), 0);
  assert.doesNotMatch(
    await page.evaluate(() => JSON.stringify({ ...localStorage })),
    /accessToken|refreshToken|flowBinder|reauthGrant|Synthetic-member/,
  );

  stage = "logout-and-recover";
  await click(page, "#logout");
  await click(page, "[data-view=recover]");
  await type(page, "#flow-email", input.email);
  await click(page, "#email-start button[type=submit]");
  stage = "reset-verify";
  await verifyMail(page);
  const resetPassword = "Synthetic-reset-password-456!";
  stage = "reset-complete";
  await type(page, "#flow-password", resetPassword);
  await type(page, "#flow-confirm", resetPassword);
  await click(page, "#email-complete button[type=submit]");
  await page.waitForSelector("#login:not([hidden])");
  assert.equal(
    await page.$eval("#security", (element) => element.hidden),
    true,
  );

  stage = "reset-login";
  await type(page, "#login-email", input.email);
  await type(page, "#login-password", resetPassword);
  await click(page, "#login-form button[type=submit]");
  stage = "password-reauth";
  await type(page, "#recent-password", resetPassword);
  await click(page, "#recent-form button[type=submit]");
  await page.waitForSelector("#security-complete:not([hidden])");
  const newPassword = "Synthetic-changed-password-789!";
  await type(page, "#new-password", newPassword);
  await type(page, "#new-confirm", newPassword);
  await page.click("#security-consent");
  stage = "password-change";
  await click(page, "#security-complete button[type=submit]");
  await page.waitForSelector("#login:not([hidden])");
  stage = "changed-login";
  await type(page, "#login-email", input.email);
  await type(page, "#login-password", newPassword);
  await click(page, "#login-form button[type=submit]");

  stage = "purpose-invalidation";
  await type(page, "#recent-password", newPassword);
  await click(page, "#recent-form button[type=submit]");
  await page.select("#security-purpose", "LOGOUT_ALL");
  assert.equal(
    await page.$eval("#security-complete", (element) => element.hidden),
    true,
  );
  assert.equal(
    await page.$eval("#security-consent", (element) => element.checked),
    false,
  );
  stage = "logout-all";
  await type(page, "#recent-password", newPassword);
  await click(page, "#recent-form button[type=submit]");
  await page.click("#security-consent");
  await click(page, "#security-complete button[type=submit]");
  await page.waitForSelector("#login:not([hidden])");
  assert.equal(
    await page.$$eval("input[type=password]", (elements) =>
      elements.every(
        (element) => element.value === "" && element.defaultValue === "",
      ),
    ),
    true,
  );
  assert.equal(counts.get("/api/member/browser-auth/email/signup/complete"), 1);
  assert.equal(
    counts.get("/api/member/browser-auth/password/reset/complete"),
    1,
  );
  assert.equal(counts.get("/api/member/auth/password/change"), 1);
  assert.equal(counts.get("/api/member/auth/logout-all"), 1);
  stage = "background-input-purge";
  await type(page, "#login-password", "Synthetic-unsent-password-123!");
  const other = await browser.newPage();
  await other.goto("about:blank");
  await other.bringToFront();
  await page.bringToFront();
  assert.equal(
    await page.$eval(
      "#login-password",
      (element) => element.value === "" && element.defaultValue === "",
    ),
    true,
  );
  await other.close();
  assert.equal(unexpectedRequests, 0);
  assert.equal(runtimeErrors.length, 0);
  process.stdout.write(
    JSON.stringify({
      ok: true,
      unexpectedRequests,
      runtimeErrors: runtimeErrors.length,
    }),
  );
} catch (failure) {
  // 진단에는 고정 단계·오류 종류만 남기며 실제 응답·자격·메일 코드는 출력하지 않는다.
  process.stderr.write(
    JSON.stringify({
      stage,
      name: failure.name,
      code: "ACCOUNT_BROWSER_FAILED",
    }),
  );
  process.exitCode = 1;
} finally {
  lines.close();
  await browser?.close();
}
