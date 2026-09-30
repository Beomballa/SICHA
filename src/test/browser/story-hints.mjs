import assert from "node:assert/strict";
import { clickWithConfirmation, selectChildResource } from "./confirmation.mjs";

/** 폐기형 HTTPS 사건에서 힌트 원고의 경계, 단계 충돌과 비교 복구를 검증한다. */
export async function exerciseHints({
  page,
  openSection,
  apiPath,
  api,
  edit,
  save,
  notice,
  layout,
  loseResponse,
  baseUrl,
}) {
  /** 자원 선택 뒤 키 목록을 열고 단건 원고 조회까지 기다린다. */
  async function open(key, active = true) {
    await openSection(page, "child");
    await selectChildResource(page, "hints");
    await page.select("#child-filter", String(active));
    await page.waitForSelector(`[data-child-key="${key}"]`);
    await clickWithConfirmation(page, `[data-child-key="${key}"]`);
    await page.waitForFunction(
      () => !document.getElementById("child-resource").disabled,
    );
  }

  /** 편집 가능한 부모 수정번호를 서버 단건 조회에서 확인한다. */
  async function revision() {
    return (await api(page, apiPath)).body.editRev;
  }

  await openSection(page, "child");
  await selectChildResource(page, "hints");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "HINT_A");
  assert.equal(await page.$eval("#child-level", (input) => input.max), "3");
  assert.match(
    await page.$eval("#child-guide", (e) => e.textContent),
    /논리 삭제된 힌트도 단계와 코드를 예약/,
  );
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력을 확인하세요");
  assert.equal((await api(page, `${apiPath}/hints/HINT_A`)).status, 404);
  await edit(page, "child", "level", "4");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력을 확인하세요");
  await edit(page, "child", "level", "1");
  await page.select("#child-body-mode", "value");
  await page.$eval("#child-body", (input) => {
    input.value = "😀".repeat(4001);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "4000자를 넘을 수 없습니다");
  await edit(page, "child", "body", "<script>실행 금지</script>\n첫 힌트");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/hints/HINT_A`)).body.item.body,
    "<script>실행 금지</script>\n첫 힌트",
  );
  assert.equal(
    await page.$$eval("#child-body-record script", (nodes) => nodes.length),
    0,
  );
  await layout(page, "child-hint-record");

  // 힌트 목록을 재진입해도 선택 전에는 원고 단건을 미리 읽지 않는다.
  await selectChildResource(page, "clues");
  let reads = 0;
  const countRead = (request) => {
    if (
      request.method() === "GET" &&
      new URL(request.url()).pathname.endsWith("/hints/HINT_A")
    )
      reads++;
  };
  page.on("request", countRead);
  await selectChildResource(page, "hints");
  await page.waitForSelector('[data-child-key="HINT_A"]');
  assert.equal(reads, 0);
  const list = await api(page, `${apiPath}/hints?size=1&activeYn=true`);
  assert.deepEqual(Object.keys(list.body.items[0]).sort(), [
    "activeYn",
    "code",
    "updatedAt",
  ]);
  assert.doesNotMatch(JSON.stringify(list.body), /첫 힌트|level|body/);
  await open("HINT_A");
  assert.equal(reads, 1);
  page.off("request", countRead);

  await clickWithConfirmation(page, "#child-active");
  await notice(page, "최신 원고를 조회했습니다");
  assert.equal(
    (await api(page, `${apiPath}/hints/HINT_A`)).body.item.activeYn,
    false,
  );
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "HINT_B");
  await edit(page, "child", "level", "1");
  await edit(page, "child", "body", "충돌해도 남길 원고");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.match(await page.$eval("#notice", (e) => e.textContent), /예약/);
  assert.equal(
    await page.$eval("#child-body", (e) => e.value),
    "충돌해도 남길 원고",
  );
  assert.equal((await api(page, `${apiPath}/hints/HINT_B`)).status, 404);
  await layout(page, "child-hint-slot-conflict");
  await clickWithConfirmation(page, "#accept-latest");
  assert.equal(await page.$eval("#child-level", (e) => e.value), "1");
  assert.equal(
    await page.$eval("#child-body", (e) => e.value),
    "충돌해도 남길 원고",
  );
  await edit(page, "child", "level", "2");
  await save(page, "child");
  assert.equal((await api(page, `${apiPath}/hints/HINT_B`)).body.item.level, 2);
  assert.equal(
    (await api(page, `${apiPath}/hints/HINT_B`)).body.item.body,
    "충돌해도 남길 원고",
  );
  assert.equal(
    (
      await api(page, `${apiPath}/hints/HINT_B`, "PATCH", {
        expectedRev: await revision(),
        changes: { level: 1 },
      })
    ).body.code,
    "SLOT_CONFLICT",
  );
  await clickWithConfirmation(page, "#child-list");
  await open("HINT_B");
  await page.select("#child-body-mode", "clear");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/hints/HINT_B`)).body.item.body,
    null,
  );
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "HINT_C");
  await edit(page, "child", "level", "3");
  await edit(page, "child", "body", "응답 유실도 원고 유지");
  let posts = 0;
  const countWrite = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname === `${apiPath}/hints`
    )
      posts++;
  };
  page.on("request", countWrite);
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/hints`);
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(posts, 1);
  assert.equal(
    await page.$eval("#child-body", (e) => e.value),
    "응답 유실도 원고 유지",
  );
  await lost.detach();
  page.off("request", countWrite);
  await clickWithConfirmation(page, "#accept-latest");
  assert.equal(posts, 1);
  assert.equal((await api(page, `${apiPath}/hints/HINT_C`)).body.item.level, 3);
  await layout(page, "child-hint-recovered");
  console.log(
    "PASS HTTPS hints: audited detail boundary, strict level/body, reserved inactive slot, draft conflict and response-loss reconciliation",
  );
}
