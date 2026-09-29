import assert from "node:assert/strict";

/** 실제 HTTPS 관리자 두 세션에서 수신자 지정·최근 세대·수락·원본 아닌 현재 소유권을 검증한다. */
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
  await page.click("#access-submit");
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
  await page.click("#ownership-request-form button[type=submit]");
  await page.waitForFunction(() =>
    document.getElementById("ownership-result").textContent.includes("PENDING"),
  );
  const state = await api(page, `/admin/api/stories/${code}/ownership`);
  assert.equal(state.status, 200);
  assert.equal(state.body.pending.effectiveState, "PENDING");
  assert.equal(state.body.pending.toAccountKey, fixture.receiverKey);

  const receiverContext = await browser.createBrowserContext();
  const receiver = await receiverContext.newPage();
  receiver.on("dialog", (dialog) => dialog.accept());
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
    await receiver.click("#ownership-accept");
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
  }
  console.log(
    "PASS HTTPS two-admin owner request, pending status and acceptance",
  );
}
