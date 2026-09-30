import assert from "node:assert/strict";

/**
 * 폐기형 HTTPS 편집기의 사실 분류·문서 참조·원고 경계와 명시적 복구를 확인한다.
 * @param {object} flow 인증된 page, 시험 API 경로와 실제 목차를 여는 openSection 및 기존 시험 헬퍼. null은 허용하지 않는다.
 */
export async function exerciseFacts({
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
  await page.select("#child-resource", "facts");
  await page.click("#child-new");
  await edit(page, "child", "code", "FACT_A");
  await save(page, "child");
  const blank = (await api(page, `${apiPath}/facts/FACT_A`)).body.item;
  assert.equal(blank.statement, null);
  assert.equal(blank.truth, null);
  assert.equal(blank.basis, null);
  assert.deepEqual(
    await page.$$eval("#child-truth option", (opts) =>
      opts.map((e) => e.value),
    ),
    ["", "TRUE", "FALSE", "MISREAD"],
  );
  await page.select("#child-truth-mode", "value");
  await page.click("#child-save");
  await notice(page, "TRUE / FALSE / MISREAD");
  await page.select("#child-truth", "MISREAD");
  await page.select("#child-statement-mode", "value");
  await page.$eval("#child-statement", (e) => {
    e.value = "𐐀".repeat(4001);
    e.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await page.click("#child-save");
  await notice(page, "4000자를 넘을 수 없습니다");
  await edit(
    page,
    "child",
    "statement",
    "<script>실행 금지</script>\n합성 명제",
  );
  await page.select("#child-basis-mode", "value");
  await page.$eval("#child-basis", (e) => {
    e.value = "𐐀".repeat(8001);
    e.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await page.click("#child-save");
  await notice(page, "8000자를 넘을 수 없습니다");
  await edit(
    page,
    "child",
    "basis",
    "MISSING_CLUE는 문서상 언급이며 관계가 아니다",
  );
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/facts/FACT_A`)).body.item.truth,
    "MISREAD",
  );
  assert.equal(
    await page.$$eval(
      "#child-statement-record script",
      (nodes) => nodes.length,
    ),
    0,
  );
  await layout(page, "child-fact-record");
  for (const truth of ["TRUE", "FALSE"]) {
    await page.select("#child-truth-mode", "value");
    await page.select("#child-truth", truth);
    await save(page, "child");
    assert.equal(
      (await api(page, `${apiPath}/facts/FACT_A`)).body.item.truth,
      truth,
    );
  }
  await page.select("#child-truth-mode", "clear");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/facts/FACT_A`)).body.item.truth,
    null,
  );

  await openSection(page, "child");
  await page.select("#child-resource", "events");
  let reads = 0;
  const countRead = (r) => {
    if (
      r.method() === "GET" &&
      new URL(r.url()).pathname.endsWith("/facts/FACT_A")
    )
      reads++;
  };
  page.on("request", countRead);
  await openSection(page, "child");
  await page.select("#child-resource", "facts");
  await page.waitForSelector('[data-child-key="FACT_A"]');
  assert.equal(reads, 0);
  const list = (await api(page, `${apiPath}/facts`)).body;
  assert.deepEqual(Object.keys(list.items[0]).sort(), [
    "activeYn",
    "code",
    "updatedAt",
  ]);
  await page.click('[data-child-key="FACT_A"]');
  await page.waitForFunction(
    () => !document.getElementById("child-resource").disabled,
  );
  assert.equal(reads, 1);
  page.off("request", countRead);
  await edit(page, "answer", "timeAnswer", "사실 원장과 독립된 미저장 정답");
  await edit(page, "child", "basis", "충돌 중 보존할 근거");
  const rev = (await api(page, apiPath)).body.editRev;
  assert.equal(
    (
      await api(page, `${apiPath}/facts/FACT_A`, "PATCH", {
        expectedRev: rev,
        changes: { basis: "서버 합성 변경" },
      })
    ).status,
    200,
  );
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(
    await page.$eval("#child-basis", (e) => e.value),
    "충돌 중 보존할 근거",
  );
  await layout(page, "child-fact-conflict");
  await page.click("#accept-latest");
  await save(page, "child");
  assert.equal(
    await page.$eval("#answer-timeAnswer", (e) => e.value),
    "사실 원장과 독립된 미저장 정답",
  );
  await save(page, "answer");
  await openSection(page, "child");
  await page.click("#child-active");
  await notice(page, "최신 원고를 조회했습니다");
  assert.equal(
    (await api(page, `${apiPath}/facts/FACT_A`)).body.item.activeYn,
    false,
  );
  assert.equal(await page.$eval("#child-save", (e) => e.disabled), true);
  await openSection(page, "child");
  await page.click("#child-active");
  await notice(page, "최신 원고를 조회했습니다");
  assert.equal(
    (await api(page, `${apiPath}/facts/FACT_A`)).body.item.activeYn,
    true,
  );

  await openSection(page, "child");
  await page.click("#child-new");
  await edit(page, "child", "code", "FACT_LOST");
  await edit(page, "child", "statement", "응답 유실 합성 명제");
  let posts = 0;
  const countWrite = (r) => {
    if (
      r.method() === "POST" &&
      new URL(r.url()).pathname === `${apiPath}/facts`
    )
      posts++;
  };
  page.on("request", countWrite);
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/facts`);
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(posts, 1);
  assert.equal(
    await page.$eval("#child-statement", (e) => e.value),
    "응답 유실 합성 명제",
  );
  await lost.detach();
  await page.click("#accept-latest");
  assert.equal(posts, 1);
  page.off("request", countWrite);
  assert.equal(
    (await api(page, `${apiPath}/facts/FACT_LOST`)).body.item.statement,
    "응답 유실 합성 명제",
  );
  await layout(page, "child-fact-recovered");
  console.log(
    "PASS HTTPS facts: nullable enum, statement/basis bounds, document-only references, conflict and response-loss recovery",
  );
}
