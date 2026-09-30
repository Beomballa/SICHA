import assert from "node:assert/strict";
import {
  installConfirmationDriver,
  clickWithConfirmation,
  waitForConfirmation,
} from "./confirmation.mjs";

/**
 * 실제 HTTPS 두 세션의 수신자 지정·수락과 관리/인계 동의 취소를 검증한다.
 * @param {object} flow 인증된 페이지, 폐기 계정, 실제 API/응답 보류 헬퍼와 해당 페이지의 confirmation 운전자. null은 허용하지 않는다.
 * @returns {Promise<void>} 원래 소유권 검증과 취소/오래된 동의의 무쓰기 검증을 완료한다.
 * @throws {Error} 실제 UI/API 결과가 계약과 다르거나 제한 시간 안에 응답하지 않으면 실패한다.
 */
export async function exerciseStoryOwnership({
  browser,
  page,
  baseUrl,
  code,
  fixture,
  otp,
  api,
  notice,
  layout,
  confirmation,
  holdResponse,
}) {
  await page.select("#access-operation", "grant");
  await page.select("#access-permission", "EDIT");
  await page.$eval("#access-account", (element) => {
    element.value = "";
  });
  await page.type("#access-account", fixture.receiverKey);
  await page.$eval("#access-reference", (element) => {
    element.value = "";
  });
  await page.type("#access-reference", "CHECK_UI_OWNER");
  confirmation.automatic = false;
  let writes = 0;
  const countWrites = (request) => {
    if (["POST", "PATCH"].includes(request.method())) writes++;
  };
  page.on("request", countWrites);
  const accessBefore = await page.$eval(
    "#access-rows",
    (node) => node.textContent,
  );
  await page.click("#access-submit");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  assert.equal(writes, 0);
  await page.click("#ui-confirm-cancel");
  await waitForConfirmation(page);
  assert.equal(
    await page.$eval("#access-rows", (node) => node.textContent),
    accessBefore,
  );
  assert.equal(writes, 0);
  const managementRead = await holdResponse(
    page,
    `${baseUrl}/admin/api/stories?*`,
  );
  await page.click("#manage-refresh");
  await managementRead.ready;
  await page.click("#access-submit");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await managementRead.release();
  await waitForConfirmation(page);
  await page.waitForFunction(() =>
    document
      .getElementById("manage-result")
      .textContent.includes("현재 사건과 접근 관계를 조회했습니다"),
  );
  assert.equal(writes, 0);
  assert.equal(
    await page.$eval("#access-account", (node) => node.value),
    fixture.receiverKey,
  );
  page.off("request", countWrites);
  confirmation.automatic = true;
  await clickWithConfirmation(page, "#access-submit");
  await page.waitForFunction(() =>
    document.getElementById("access-rows").textContent.includes("EDIT · 활성"),
  );
  await page.click(".ownership-open");
  await page.waitForSelector("#ownership-panel:not([hidden])");
  assert.equal(
    await page.$eval("#ownership-request-form", (e) => e.hidden),
    false,
  );
  assert.equal(await page.$eval("#ownership-pending", (e) => e.hidden), true);
  assert.doesNotMatch(
    await page.$eval("#ownership-panel", (e) => e.textContent),
    /인물 UI 전용 비밀/,
  );
  await layout(page, "story-ownership-request");
  await page.type("#ownership-recipient", fixture.receiverKey);
  await page.click("#ownership-keep-editor");
  await page.type("#ownership-reference", "CHECK_UI_OWNER");
  confirmation.automatic = false;
  writes = 0;
  page.on("request", countWrites);
  await page.click("#ownership-request-form button[type=submit]");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  assert.equal(writes, 0);
  await page.click("#ui-confirm-cancel");
  await waitForConfirmation(page);
  assert.equal(
    (await api(page, `/admin/api/stories/${code}/ownership`)).body.pending,
    null,
  );
  const ownershipRead = await holdResponse(
    page,
    `${baseUrl}/admin/api/stories/${code}/ownership`,
  );
  await page.click("#ownership-refresh");
  await ownershipRead.ready;
  await page.click("#ownership-request-form button[type=submit]");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await ownershipRead.release();
  await waitForConfirmation(page);
  assert.equal(writes, 0);
  assert.equal(
    await page.$eval("#ownership-recipient", (node) => node.value),
    fixture.receiverKey,
  );
  assert.equal(
    (await api(page, `/admin/api/stories/${code}/ownership`)).body.pending,
    null,
  );
  page.off("request", countWrites);
  confirmation.automatic = true;
  await clickWithConfirmation(
    page,
    "#ownership-request-form button[type=submit]",
  );
  await page.waitForFunction(() =>
    document.getElementById("ownership-result").textContent.includes("PENDING"),
  );
  const state = await api(page, `/admin/api/stories/${code}/ownership`);
  assert.equal(state.status, 200);
  assert.equal(state.body.pending.effectiveState, "PENDING");
  assert.equal(state.body.pending.toAccountKey, fixture.receiverKey);

  const receiverContext = await browser.createBrowserContext();
  const receiver = await receiverContext.newPage();
  const receiverConfirmation = await installConfirmationDriver(receiver);
  const runtimeErrors = [];
  receiver.on("pageerror", (error) => runtimeErrors.push(error.message));
  try {
    await receiver.goto(`${baseUrl}/admin/login`);
    await receiver.waitForSelector("[name=loginId]");
    await receiver.type("[name=loginId]", fixture.receiverLoginId);
    await receiver.type("[name=password]", fixture.receiverPassword);
    await Promise.all([
      receiver.waitForNavigation(),
      receiver.click("form[data-action=login] button"),
    ]);
    await receiver.waitForSelector("[name=totp]");
    await receiver.type("[name=totp]", otp(fixture.receiverSecret));
    await Promise.all([
      receiver.waitForNavigation(),
      receiver.click("form[data-action=mfa] button"),
    ]);
    await receiver.goto(`${baseUrl}/admin/stories`);
    await notice(receiver, "현재 페이지");
    await receiver.type("#code", code);
    await receiver.click("#filter-form button");
    await notice(receiver, "현재 페이지");
    await receiver.click(".ownership-open");
    await receiver.waitForSelector("#ownership-accept:not([hidden])");
    assert.match(
      await receiver.$eval("#ownership-pending-detail", (e) => e.textContent),
      /PENDING/,
    );
    await clickWithConfirmation(receiver, "#ownership-accept");
    await receiver.waitForFunction(() =>
      document
        .getElementById("ownership-result")
        .textContent.includes("ACCEPTED"),
    );
    await receiver.click("#ownership-refresh");
    await receiver.waitForFunction(
      (key) =>
        document.getElementById("ownership-summary").textContent.includes(key),
      {},
      fixture.receiverKey,
    );
    const owned = await api(receiver, `/admin/api/stories/${code}/ownership`);
    assert.equal(owned.body.ownerAccountKey, fixture.receiverKey);
    assert.equal(owned.body.pending, null);
    assert.doesNotMatch(
      await receiver.$eval("#ownership-panel", (e) => e.textContent),
      /인물 UI 전용 비밀/,
    );
  } finally {
    await receiverContext.close();
    assert.deepEqual(receiverConfirmation.errors, []);
    assert.deepEqual(runtimeErrors, []);
  }
  console.log(
    "PASS HTTPS two-admin owner request, pending status and acceptance",
  );
}
