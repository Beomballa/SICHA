import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(new URL("./package.json", import.meta.url));
const puppeteer = require("puppeteer-core");
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const input = JSON.parse(Buffer.concat(chunks).toString("utf8"));
let browser;
let stage = "launch";
let observedStatus, observedCode, observation;
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
  // CDP 본문 회수 실패 대신 원래 Fetch Promise/Response를 유지한 채 고정 오류 코드만 관측한다.
  await page.evaluateOnNewDocument(() => {
    window.__soloGateCode = null;
    window.__soloGateObservation = null;
    const original = window.fetch;
    window.fetch = function (...args) {
      const promise = Reflect.apply(original, this, args);
      void promise
        .then(async (response) => {
          if (new URL(response.url).pathname !== "/api/stories") return;
          window.__soloGateObservation = {
            contentType: response.headers.get("content-type"),
            bodyUsed: response.bodyUsed,
          };
          const value = await response.clone().json();
          window.__soloGateCode =
            typeof value.code === "string" &&
            /^[A-Z0-9_]{1,64}$/.test(value.code)
              ? value.code
              : "UNRECOGNIZED";
        })
        .catch((failure) => {
          window.__soloGateCode = "READ_FAILED";
          window.__soloGateObservation = {
            ...window.__soloGateObservation,
            errorName: failure.name,
          };
        });
      return promise;
    };
  });
  const errors = [];
  page.on("pageerror", () => errors.push("PAGE_ERROR"));
  stage = "login";
  await page.goto(input.origin + "/account#login");
  await page.waitForSelector("main[aria-busy='false']");
  await page.type("#login-email", input.email);
  await page.type("#login-password", input.password);
  await page.click("#login-form button[type=submit]");
  await page.waitForSelector("#security:not([hidden])");
  await page.waitForSelector("main[aria-busy='false']");
  stage = "collection-gate";
  const response = page.waitForResponse(
    (result) => new URL(result.url()).pathname === "/api/stories",
  );
  await page.click("a[href='/play']");
  const result = await response;
  observedStatus = result.status();
  stage = "collection-http-status";
  assert.equal(result.status(), 503);
  stage = "collection-http-code";
  await page.waitForFunction(() => window.__soloGateCode !== null);
  observedCode = await page.evaluate(() => window.__soloGateCode);
  observation = await page.evaluate(() => window.__soloGateObservation);
  assert.equal(observedCode, "FRIENDLY_COLLECTION_NOT_READY");
  stage = "collection-ui";
  await page.waitForSelector("#error:not([hidden])");
  await page.waitForSelector("main[aria-busy='false']");
  assert.match(
    await page.$eval("#error", (node) => node.textContent),
    /공개·수집 준비/,
  );
  assert.equal(await page.$eval("#empty", (node) => node.hidden), true);
  assert.equal(
    await page.$eval("#story-list", (node) => node.childElementCount),
    0,
  );
  assert.equal(await page.$eval("#create", (node) => node.disabled), true);
  assert.deepEqual(errors, []);
  process.stdout.write(JSON.stringify({ ok: true }));
} catch (failure) {
  process.stderr.write(
    JSON.stringify({
      stage,
      name: failure.name,
      code: "SOLO_GATE_FAILED",
      observedStatus,
      observedCode,
      observation,
    }),
  );
  process.exitCode = 1;
} finally {
  await browser?.close();
}
