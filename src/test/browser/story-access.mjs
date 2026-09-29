import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

/** 최초 초안의 관계 영향 검토·논리 삭제·명시 복원을 실제 HTTPS 관리자 화면에서 확인한다. */
export async function exerciseStoryAccess({
  page,
  baseUrl,
  api,
  notice,
  layout,
}) {
  const created = await api(page, "/admin/api/stories", "POST", {
    createKey: randomUUID(),
    title: "접근 영향 검증 초안",
  });
  assert.equal(created.status, 201);
  const code = created.body.storyCode;
  await page.goto(`${baseUrl}/admin/stories`);
  await notice(page, "현재 페이지");
  await page.waitForSelector(".manage-open:not([hidden])");
  await page.type("#code", code);
  await page.click("#filter-form button");
  await notice(page, "현재 페이지");
  await page.click(".manage-open");
  await page.waitForSelector("#manage-panel:not([hidden])");
  assert.match(await page.$eval("#access-impact", (e) => e.textContent), /0건/);
  assert.equal(
    await page.$eval("#story-state-actions", (e) => e.hidden),
    false,
  );
  const me = await api(page, "/admin/api/auth/me");
  assert.equal(me.status, 200);
  await page.type("#access-account", me.body.accountKey);
  await page.select("#access-permission", "REVIEW");
  await page.type("#access-reference", "CHECK_UI_GRANT");
  await page.click("#access-submit");
  await page.waitForFunction(() =>
    document
      .getElementById("manage-result")
      .textContent.includes("관계 변경 확정"),
  );
  assert.match(await page.$eval("#access-impact", (e) => e.textContent), /1건/);
  await layout(page, "story-access-state");
  await page.type("#state-reference", "CHECK_UI_01");
  await page.click("#state-change");
  await notice(page, "논리 삭제 확정");
  assert.equal(
    (await api(page, `/admin/api/stories?code=${code}&activeYn=true`)).body
      .items.length,
    0,
  );
  await page.select("#active", "false");
  await page.click("#filter-form button");
  await notice(page, "현재 페이지");
  await page.click(".manage-open");
  await page.waitForSelector("#manage-panel:not([hidden])");
  assert.match(await page.$eval("#access-impact", (e) => e.textContent), /1건/);
  assert.match(
    await page.$eval("#access-rows", (e) => e.textContent),
    /REVIEW · 활성/,
  );
  await page.type("#state-reference", "CHECK_UI_02");
  await page.click("#state-change");
  await notice(page, "복원 전 남은 활성 관계 수와 목록");
  assert.equal(
    (await api(page, `/admin/api/stories?code=${code}&activeYn=false`)).body
      .items.length,
    1,
  );
  await page.click("#state-impact-reviewed");
  await page.click("#state-change");
  await notice(page, "복원 확정");
  const current = await api(
    page,
    `/admin/api/stories?code=${code}&activeYn=true`,
  );
  assert.equal(current.body.items.length, 1);
  assert.equal(current.body.items[0].storyRev, "3");
  await page.select("#active", "true");
  await page.click("#filter-form button");
  await notice(page, "현재 페이지");
  await page.click(".manage-open");
  await page.waitForSelector("#manage-panel:not([hidden])");
  await page.select("#access-permission", "REVIEW");
  await page.select("#access-operation", "revoke");
  await page.select("#access-reason", "ACCESS_REVIEW");
  await page.type("#access-reference", "CHECK_UI_REVOKE");
  await page.click("#access-submit");
  await page.waitForFunction(() =>
    document
      .getElementById("manage-result")
      .textContent.includes("관계 변경 확정"),
  );
  assert.match(await page.$eval("#access-impact", (e) => e.textContent), /0건/);
  console.log(
    "PASS HTTPS access grant/revoke, withdrawal, impact review and restore",
  );
  return code;
}
