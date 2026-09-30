import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { clickWithConfirmation } from "./confirmation.mjs";

/**
 * 최초 초안의 관계 영향·논리 삭제 확정 뒤 목록 조회 실패·명시 복원을 실제 HTTPS 화면에서 확인한다.
 * @param {object} flow 인증된 page와 실제 서버 baseUrl, API·안내·배치 시험 헬퍼. null은 허용하지 않는다.
 * @returns {Promise<string>} 관계 변경과 삭제·복원을 확인한 합성 사건 코드.
 * @throws {Error} 실제 응답 차단이나 기존 상태·확정 영수증·조회 복구 검증에 실패하면 전파한다.
 */
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
  await clickWithConfirmation(page, "#filter-form button");
  await notice(page, "현재 페이지");
  await clickWithConfirmation(page, ".manage-open");
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
  await clickWithConfirmation(page, "#access-submit");
  await page.waitForFunction(() =>
    document
      .getElementById("manage-result")
      .textContent.includes("관계 변경 확정"),
  );
  assert.match(await page.$eval("#access-impact", (e) => e.textContent), /1건/);
  await layout(page, "story-access-state");
  await page.type("#state-reference", "CHECK_UI_01");

  // 이전 준비 조회는 건드리지 않고, 실제 삭제 POST 성공 뒤의 목록 GET 응답만 한 번 끊는다.
  let withdrawalPosts = 0;
  const countWithdrawal = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname ===
        `/admin/api/stories/${code}/deactivate`
    )
      withdrawalPosts++;
  };
  const failedList = await page.createCDPSession();
  const interrupted = new Promise((resolve) => {
    failedList.on("Fetch.requestPaused", async (event) => {
      try {
        if (event.request.method !== "GET") {
          await failedList.send("Fetch.continueResponse", {
            requestId: event.requestId,
          });
          return;
        }
        await failedList.send("Fetch.failRequest", {
          requestId: event.requestId,
          errorReason: "ConnectionClosed",
        });
        await failedList.send("Fetch.disable");
        resolve({ status: event.responseStatusCode });
      } catch (error) {
        resolve({ error });
      }
    });
  });
  page.on("request", countWithdrawal);
  try {
    await failedList.send("Fetch.enable", {
      patterns: [
        {
          urlPattern: `${baseUrl}/admin/api/stories?*`,
          requestStage: "Response",
        },
      ],
    });
    const [withdrawn] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === "POST" &&
          new URL(response.url()).pathname ===
            `/admin/api/stories/${code}/deactivate`,
      ),
      clickWithConfirmation(page, "#state-change"),
    ]);
    assert.equal(withdrawn.status(), 200);
    await notice(page, "논리 삭제 확정");
    await notice(page, "목록 새로 조회에 실패했습니다");
    const interruption = await interrupted;
    assert.equal(interruption.error, undefined);
    assert.equal(interruption.status, 200);
    const receipt = await page.$eval("#notice", (e) => e.textContent);
    assert.match(receipt, /논리 삭제 확정/);
    assert.match(receipt, /목록 새로 조회에 실패했습니다/);
    assert.equal(withdrawalPosts, 1);
    assert.equal(
      (await api(page, `/admin/api/stories?code=${code}&activeYn=true`)).body
        .items.length,
      0,
    );
    const inactive = await api(
      page,
      `/admin/api/stories?code=${code}&activeYn=false`,
    );
    assert.equal(inactive.status, 200);
    assert.equal(inactive.body.items.length, 1);
    assert.equal(inactive.body.items[0].storyCode, code);
    assert.equal(inactive.body.items[0].activeYn, false);

    // 명시적인 필터 GET으로만 목록을 복구하고 이미 확정된 변경을 다시 제출하지 않는다.
    const [recovered] = await Promise.all([
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return (
          response.request().method() === "GET" &&
          url.pathname === "/admin/api/stories" &&
          url.searchParams.get("code") === code &&
          url.searchParams.get("activeYn") === "true"
        );
      }),
      clickWithConfirmation(page, "#filter-form button"),
    ]);
    assert.equal(recovered.status(), 200);
    await notice(page, "현재 페이지");
    assert.equal(
      await page.$$eval("#rows .story-row", (rows) => rows.length),
      0,
    );
    assert.equal(withdrawalPosts, 1);
  } finally {
    page.off("request", countWithdrawal);
    await failedList.detach();
  }

  await page.select("#active", "false");
  await clickWithConfirmation(page, "#filter-form button");
  await notice(page, "현재 페이지");
  await clickWithConfirmation(page, ".manage-open");
  await page.waitForSelector("#manage-panel:not([hidden])");
  assert.match(await page.$eval("#access-impact", (e) => e.textContent), /1건/);
  assert.match(
    await page.$eval("#access-rows", (e) => e.textContent),
    /REVIEW · 활성/,
  );
  await page.type("#state-reference", "CHECK_UI_02");
  await clickWithConfirmation(page, "#state-change");
  await notice(page, "복원 전 남은 활성 관계 수와 목록");
  assert.equal(
    (await api(page, `/admin/api/stories?code=${code}&activeYn=false`)).body
      .items.length,
    1,
  );
  await clickWithConfirmation(page, "#state-impact-reviewed");
  await clickWithConfirmation(page, "#state-change");
  await notice(page, "복원 확정");
  const current = await api(
    page,
    `/admin/api/stories?code=${code}&activeYn=true`,
  );
  assert.equal(current.body.items.length, 1);
  assert.equal(current.body.items[0].storyRev, "3");
  await page.select("#active", "true");
  await clickWithConfirmation(page, "#filter-form button");
  await notice(page, "현재 페이지");
  await clickWithConfirmation(page, ".manage-open");
  await page.waitForSelector("#manage-panel:not([hidden])");
  await page.select("#access-permission", "REVIEW");
  await page.select("#access-operation", "revoke");
  await page.select("#access-reason", "ACCESS_REVIEW");
  await page.type("#access-reference", "CHECK_UI_REVOKE");
  await clickWithConfirmation(page, "#access-submit");
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
