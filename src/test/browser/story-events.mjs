import assert from "node:assert/strict";
import { clickWithConfirmation, selectChildResource } from "./confirmation.mjs";

/**
 * 폐기형 HTTPS 편집기에서 시간선의 nullable·분 경계와 충돌·응답 유실을 확인한다.
 * @param {object} flow 인증된 page, 시험 API 경로와 실제 목차를 여는 openSection 및 기존 시험 헬퍼. null은 허용하지 않는다.
 */
export async function exerciseEvents({
  openSection,
  page,
  apiPath,
  api,
  edit,
  save,
  notice,
  layout,
  loseResponse,
  baseUrl,
}) {
  await openSection(page, "child");
  await selectChildResource(page, "events");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "EVENT_A");
  assert.equal(await page.$eval("#child-startMin", (e) => e.min), "0");
  assert.equal(await page.$eval("#child-endMin", (e) => e.max), "2147483647");
  await edit(page, "child", "endMin", "1");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "종료는 시작과 함께");
  assert.equal((await api(page, `${apiPath}/events/EVENT_A`)).status, 404);
  await edit(page, "child", "startMin", "0");
  await page.select("#child-actualText-mode", "value");
  await page.$eval("#child-actualText", (e) => {
    e.value = "𐐀".repeat(8001);
    e.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "8000자를 넘을 수 없습니다");
  await edit(
    page,
    "child",
    "actualText",
    "<script>실행 금지</script>\n합성 시간선",
  );
  await save(page, "child");
  const first = (await api(page, `${apiPath}/events/EVENT_A`)).body.item;
  assert.equal(first.startMin, 0);
  assert.equal(first.endMin, 1);
  assert.equal(first.apparentText, null);
  assert.equal(
    await page.$$eval("#child-actualText-record script", (e) => e.length),
    0,
  );
  await layout(page, "child-event-record");

  await page.select("#child-startMin-mode", "clear");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "종료는 시작과 함께");
  await page.select("#child-endMin-mode", "clear");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/events/EVENT_A`)).body.item.startMin,
    null,
  );
  await edit(page, "child", "startMin", "2147483647");
  await edit(page, "child", "endMin", "2147483647");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/events/EVENT_A`)).body.item.endMin,
    2147483647,
  );

  await openSection(page, "child");
  await selectChildResource(page, "hints");
  let reads = 0;
  const countRead = (r) => {
    if (
      r.method() === "GET" &&
      new URL(r.url()).pathname.endsWith("/events/EVENT_A")
    )
      reads++;
  };
  page.on("request", countRead);
  await openSection(page, "child");
  await selectChildResource(page, "events");
  await page.waitForSelector('[data-child-key="EVENT_A"]');
  assert.equal(reads, 0);
  const list = (await api(page, `${apiPath}/events`)).body;
  assert.deepEqual(Object.keys(list.items[0]).sort(), [
    "activeYn",
    "code",
    "updatedAt",
  ]);
  await clickWithConfirmation(page, '[data-child-key="EVENT_A"]');
  await page.waitForFunction(
    () => !document.getElementById("child-resource").disabled,
  );
  assert.equal(reads, 1);
  page.off("request", countRead);

  await edit(page, "answer", "methodAnswer", "시간선과 독립된 미저장 입력");
  await edit(page, "child", "apparentText", "충돌 중 남길 표면 기록");
  const rev = (await api(page, apiPath)).body.editRev;
  assert.equal(
    (
      await api(page, `${apiPath}/events/EVENT_A`, "PATCH", {
        expectedRev: rev,
        changes: { apparentText: "서버 합성 변경" },
      })
    ).status,
    200,
  );
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(
    await page.$eval("#child-apparentText", (e) => e.value),
    "충돌 중 남길 표면 기록",
  );
  await layout(page, "child-event-conflict");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await save(page, "child");
  assert.equal(
    await page.$eval("#answer-methodAnswer", (e) => e.value),
    "시간선과 독립된 미저장 입력",
  );
  await save(page, "answer");
  await openSection(page, "child");
  await clickWithConfirmation(page, "#child-active");
  await notice(page, "최신 원고를 조회했습니다");
  await page.waitForFunction(() => {
    const action = document.getElementById("child-active");
    return (
      action.textContent === "시간선 복원" &&
      !action.closest("[hidden]") &&
      !action.disabled &&
      !document.getElementById("child-resource").disabled &&
      !document.getElementById("child-list").disabled
    );
  });
  assert.equal(
    (await api(page, `${apiPath}/events/EVENT_A`)).body.item.activeYn,
    false,
  );
  assert.equal(await page.$eval("#child-save", (e) => e.disabled), true);
  await openSection(page, "child");
  await clickWithConfirmation(page, "#child-active");
  await notice(page, "최신 원고를 조회했습니다");
  await page.waitForFunction(() => {
    const action = document.getElementById("child-active");
    return (
      action.textContent === "시간선 논리 삭제" &&
      !action.closest("[hidden]") &&
      !action.disabled &&
      !document.getElementById("child-resource").disabled &&
      !document.getElementById("child-list").disabled
    );
  });
  assert.equal(
    (await api(page, `${apiPath}/events/EVENT_A`)).body.item.activeYn,
    true,
  );

  await openSection(page, "child");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "EVENT_LOST");
  await edit(page, "child", "actualText", "응답 유실 합성 기록");
  let posts = 0;
  const countWrite = (r) => {
    if (
      r.method() === "POST" &&
      new URL(r.url()).pathname === `${apiPath}/events`
    )
      posts++;
  };
  page.on("request", countWrite);
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/events`);
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(posts, 1);
  assert.equal(
    await page.$eval("#child-actualText", (e) => e.value),
    "응답 유실 합성 기록",
  );
  await lost.detach();
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  assert.equal(posts, 1);
  page.off("request", countWrite);
  assert.equal(
    (await api(page, `${apiPath}/events/EVENT_LOST`)).body.item.actualText,
    "응답 유실 합성 기록",
  );
  await layout(page, "child-event-recovered");
  console.log(
    "PASS HTTPS events: nullable times, integer and codepoint bounds, metadata-only list, conflict, state and response-loss recovery",
  );
}
