import assert from "node:assert/strict";

/** 실제 폐기 계정의 UI와 기존 API를 확인한다. 변경 동의는 취소하고 사업 쓰기는 실행하지 않는다. */
export async function exerciseAdminAccounts(page, fixture, helpers) {
  const { notice, layout, inspectLayout, text, confirmation, loseResponse } =
    helpers;
  async function accountLayout(name) {
    await layout(page, name);
    for (const width of [900, 1200]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      await inspectLayout(page, name, String(width));
    }
  }
  const writes = [];
  const observe = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname.startsWith("/admin/api/accounts/")
    )
      writes.push(request.url());
  };
  page.on("request", observe);
  try {
    await page.goto(`${fixture.url}/admin/accounts`);
    await notice(page, "현재 페이지를 조회했습니다");
    const ownKey = await page.evaluate(
      async () =>
        (
          await (
            await fetch("/admin/api/auth/me", {
              cache: "no-store",
              credentials: "same-origin",
            })
          ).json()
        ).accountKey,
    );
    assert.match(ownKey, /^[0-9a-f-]{36}$/);
    assert.equal(await text(page, "#page-title"), "관리자 계정");
    assert.ok(await page.$$(".account-row").then((rows) => rows.length >= 3));
    assert.equal(
      await page.$eval("#account-rows", (node) =>
        node.getAttribute("aria-busy"),
      ),
      "false",
    );
    await accountLayout("accounts-list");
    await page.select(
      '[data-action="account-filter"] [name="activeYn"]',
      "false",
    );
    await page.click('[data-action="account-filter"] button');
    await notice(page, "현재 페이지를 조회했습니다");
    assert.equal(await page.$$(".account-row").then((rows) => rows.length), 1);
    assert.equal(
      await page.$eval(".account-key-link", (link) =>
        link.getAttribute("href"),
      ),
      `/admin/accounts/${fixture.inactiveKey}`,
    );
    assert.match(await text(page, ".account-badges"), /비활성/);

    await page.type(
      '[data-action="account-filter"] [name="accountKey"]',
      "00000000-0000-4000-8000-000000000000",
    );
    await page.click('[data-action="account-filter"] button');
    await notice(page, "현재 페이지를 조회했습니다");
    assert.equal(await page.$$(".account-row").then((rows) => rows.length), 0);
    assert.match(
      await text(page, ".account-empty"),
      /조건에 맞는 계정이 없습니다/,
    );
    await accountLayout("accounts-empty");
    const loss = await loseResponse(
      page,
      `${fixture.url}/admin/api/accounts?*`,
    );
    await page.click('[data-action="account-filter"] button');
    await page.waitForFunction(() =>
      document.getElementById("notice").classList.contains("error"),
    );
    assert.equal(
      await page.$eval("#account-rows", (node) =>
        node.getAttribute("aria-busy"),
      ),
      "false",
    );
    await accountLayout("accounts-read-error");
    await loss.detach();

    await page.goto(`${fixture.url}/admin/accounts/${ownKey}`);
    await notice(page, "최신 계정 상태입니다");
    assert.equal(
      await page
        .$$(".account-mutation-grid > fieldset")
        .then((nodes) => nodes.length),
      2,
    );
    assert.match(await text(page, "#account-state"), /관리자 운영 \(MANAGE\)/);
    await accountLayout("accounts-active-detail");
    confirmation.automatic = false;
    const grant = '[data-action="account-grant"]';
    const permissionLabel = `${grant} label:has([value="CREATE"])`;
    const labelBounds = await page.$eval(permissionLabel, (label) => ({
      width: label.getBoundingClientRect().width,
      height: label.getBoundingClientRect().height,
    }));
    assert.ok(labelBounds.width >= 48 && labelBounds.height >= 48);
    await page.click(permissionLabel, {
      offset: { x: labelBounds.width - 4, y: labelBounds.height - 4 },
    });
    assert.equal(
      await page.$eval(`${grant} [value="CREATE"]`, (input) => input.checked),
      true,
      "glyph 밖의 native label도 실제 체크박스를 선택한다",
    );
    await page.type(`${grant} [name="verificationRef"]`, "verified_ui_cancel");
    await page.click(`${grant} button`);
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-cancel");
    await page.waitForFunction(
      () => !document.querySelector("#ui-confirm-dialog[open]"),
    );
    assert.equal(
      await page.$eval(
        `${grant} [name="verificationRef"]`,
        (input) => input.value,
      ),
      "verified_ui_cancel",
    );
    assert.equal(
      await page.$eval(`${grant} [value="CREATE"]`, (input) => input.checked),
      true,
    );
    const deactivate = '[data-action="account-deactivate"]';
    await page.type(
      `${deactivate} [name="verificationRef"]`,
      "verified_ui_cancel",
    );
    await page.click(`${deactivate} button`);
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.keyboard.press("Escape");
    await page.waitForFunction(
      () => !document.querySelector("#ui-confirm-dialog[open]"),
    );
    assert.equal(
      await page.$eval(
        `${deactivate} [name="verificationRef"]`,
        (input) => input.value,
      ),
      "verified_ui_cancel",
    );

    await page.goto(`${fixture.url}/admin/accounts/${fixture.inactiveKey}`);
    await notice(page, "최신 계정 상태입니다");
    assert.equal(
      await page.$eval(
        '[data-action="account-grant"]',
        (form) => form.closest("fieldset").disabled,
      ),
      true,
    );
    assert.equal(await page.$eval(deactivate, (form) => form.hidden), true);
    await page.click('[data-action="account-preview"] button');
    await notice(page, "현재 전체 영향의 해시");
    assert.match(
      await text(page, "#reactivation-impact"),
      /재활성 후 자격증명 조치: REISSUE_ENROLLMENT/,
    );
    assert.equal(
      await page.$eval('[name="confirmImpact"]', (input) => input.checked),
      false,
    );
    await accountLayout("accounts-inactive-preview");
    assert.deepEqual(
      writes,
      [],
      "취소와 읽기/영향 확인은 계정 변경 POST를 실행하지 않는다",
    );
    console.log(
      "PASS HTTPS account UI: actual filters/empty/read loss, active/inactive sections, native disabled grants, consent cancellation with preserved input, actual impact preview, responsive layouts/200%; no account changes",
    );
    await page.goto(`${fixture.url}/admin`);
    await notice(page, "현재 세션을 확인했습니다");
  } finally {
    confirmation.automatic = true;
    page.off("request", observe);
  }
}
