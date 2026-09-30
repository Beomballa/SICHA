import assert from "node:assert/strict";
import { clickWithConfirmation, selectChildResource } from "./confirmation.mjs";

/**
 * 폐기형 HTTPS 편집기에서 소항목·단서 연결·규칙 원문과 수동 복구를 확인한다.
 * @param {object} flow 인증된 page, 시험 API 경로와 실제 목차를 여는 openSection 및 기존 시험 헬퍼. null은 허용하지 않는다.
 */
export async function exerciseRubrics({
  openSection,
  page,
  apiPath,
  baseUrl,
  api,
  edit,
  save,
  notice,
  layout,
  loseResponse,
}) {
  const rev = async () => (await api(page, apiPath)).body.editRev;
  const change = async (path, changes) =>
    api(page, `${apiPath}/${path}`, "PATCH", {
      expectedRev: await rev(),
      changes,
    });
  const create = async (path, item) =>
    api(page, `${apiPath}/${path}`, "POST", {
      expectedRev: await rev(),
      item,
    });
  /** 외부 API 준비 후 새 화면 조회로 현재 수정번호를 먼저 읽는다. */
  async function latest() {
    await page.reload({ waitUntil: "domcontentloaded" });
    await notice(page, "현재 원고를 조회했습니다");
  }

  /**
   * 실제 목차와 선택된 자료 종류의 키 목록을 거쳐 상세 원고를 연다.
   * @param {string} resource 기존 하위 자료 종류. null은 허용하지 않는다.
   * @param {string} key 목록에 존재하는 합성 자료 키. null은 허용하지 않는다.
   */
  async function open(resource, key) {
    await openSection(page, "child");
    await selectChildResource(page, resource);
    await page.waitForSelector(`[data-child-key="${key}"]`);
    await clickWithConfirmation(page, `[data-child-key="${key}"]`);
    await page.waitForFunction(
      () => !document.getElementById("child-resource").disabled,
    );
  }

  /**
   * 표시된 하위 원고 영역에 규칙 검증용 원문을 입력한다.
   * @param {string} value 검증에 사용할 JSON 원문. 잘못된 JSON도 허용하며 null은 허용하지 않는다.
   */
  const json = async (value) => {
    await openSection(page, "child");
    await page.select("#child-ruleData-mode", "value");
    await page.$eval(
      "#child-ruleData",
      (e, text) => {
        e.value = text;
        e.dispatchEvent(new Event("input", { bubbles: true }));
      },
      value,
    );
  };

  await openSection(page, "child");
  await selectChildResource(page, "rubrics");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "UI_RUBRIC");
  await page.select("#child-category", "METHOD");
  await page.select("#child-category", "");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력을 확인하세요");
  assert.equal((await api(page, `${apiPath}/rubrics/UI_RUBRIC`)).status, 404);
  await page.select("#child-category", "METHOD");
  await save(page, "child");
  const root = `${apiPath}/rubrics/UI_RUBRIC`;
  let item = (await api(page, root)).body.item;
  assert.equal(item.category, "METHOD");
  assert.equal(item.maxScore, null);
  assert.equal(item.passScore, null);
  assert.equal(item.ruleData, null);
  assert.equal(item.requiredYn, false);
  assert.match(
    await page.$eval("#warnings", (e) => e.textContent),
    /UI_RUBRIC.*최대 점수/,
  );
  assert.match(
    await page.$eval("#warnings", (e) => e.textContent),
    /METHOD.*분류별 점수/,
  );
  assert.equal(
    await page.$$eval(
      '#warnings a[href="#child-resource"]',
      (nodes) => nodes.length > 0,
    ),
    true,
  );
  let detailReads = 0;
  const countDetail = (request) => {
    if (request.method() === "GET" && new URL(request.url()).pathname === root)
      detailReads++;
  };
  page.on("request", countDetail);
  await openSection(page, "child");
  await selectChildResource(page, "facts");
  await openSection(page, "child");
  await selectChildResource(page, "rubrics");
  await page.waitForSelector('[data-child-key="UI_RUBRIC"]');
  assert.equal(detailReads, 0);
  assert.deepEqual(
    Object.keys((await api(page, `${apiPath}/rubrics`)).body.items[0]).sort(),
    ["activeYn", "code", "updatedAt"],
  );
  await clickWithConfirmation(page, '[data-child-key="UI_RUBRIC"]');
  await page.waitForFunction(
    () => !document.getElementById("child-resource").disabled,
  );
  assert.equal(detailReads, 1);
  page.off("request", countDetail);
  await layout(page, "rubric-null-record");
  await page.select("#child-maxScore-mode", "value");
  await page.$eval("#child-maxScore", (e) => {
    e.value = "-1";
  });
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력을 확인하세요");
  await edit(page, "child", "maxScore", 0);
  await save(page, "child");
  assert.equal((await api(page, root)).body.item.maxScore, 0);
  await edit(page, "child", "passScore", 1);
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "통과 점수는 필수 여부");
  await page.select("#child-requiredYn-mode", "value");
  await page.select("#child-requiredYn", "true");
  await edit(page, "child", "maxScore", 20);
  await save(page, "child");
  item = (await api(page, root)).body.item;
  assert.equal(item.passScore, 1);
  assert.equal(item.requiredYn, true);
  await page.select("#child-requiredYn-mode", "value");
  await page.select("#child-requiredYn", "false");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "통과 점수는 필수 여부");
  await page.select("#child-requiredYn", "true");
  await page.select("#child-requiredYn-mode", "keep");
  await page.select("#child-rejectText-mode", "value");
  await page.$eval("#child-rejectText", (e) => {
    e.value = "가".repeat(8001);
    e.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "8000자를 넘을 수 없습니다");
  await page.select("#child-rejectText-mode", "clear");
  await edit(
    page,
    "child",
    "acceptedText",
    "<script>비실행</script> 허용 문구",
  );
  await save(page, "child");
  assert.equal((await api(page, root)).body.item.rejectText, null);
  assert.equal(
    await page.$$eval(
      "#child-acceptedText-record script",
      (nodes) => nodes.length,
    ),
    0,
  );

  assert.equal(
    (await create("facts", { code: "RUBRIC_FACT", statement: "합성 사실" }))
      .status,
    201,
  );
  assert.equal(
    (
      await create("clues", {
        code: "RUBRIC_CLUE",
        title: "합성 단서",
        scope: "COMMON",
      })
    ).status,
    201,
  );
  await latest();
  await openSection(page, "child");
  await selectChildResource(page, "rubric-clues");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "rubricCode", "UI_RUBRIC");
  await edit(page, "child", "clueCode", "RUBRIC_CLUE");
  await edit(page, "child", "linkText", "<script>비실행</script> 근거 연결");
  await save(page, "child");
  const link = `${apiPath}/rubric-clues/UI_RUBRIC~RUBRIC_CLUE`;
  assert.equal(
    (await api(page, link)).body.item.linkText,
    "<script>비실행</script> 근거 연결",
  );
  assert.equal(
    await page.$$eval("#child-linkText-record script", (nodes) => nodes.length),
    0,
  );
  let linkReads = 0;
  const countLink = (request) => {
    if (request.method() === "GET" && new URL(request.url()).pathname === link)
      linkReads++;
  };
  page.on("request", countLink);
  await openSection(page, "child");
  await selectChildResource(page, "rubrics");
  await openSection(page, "child");
  await selectChildResource(page, "rubric-clues");
  await page.waitForSelector('[data-child-key="UI_RUBRIC~RUBRIC_CLUE"]');
  assert.equal(linkReads, 0);
  await clickWithConfirmation(page, '[data-child-key="UI_RUBRIC~RUBRIC_CLUE"]');
  await page.waitForFunction(
    () => !document.getElementById("child-resource").disabled,
  );
  assert.equal(linkReads, 1);
  page.off("request", countLink);
  await edit(page, "child", "linkText", "수정한 연결 설명");
  await save(page, "child");
  assert.equal((await api(page, link)).body.item.linkText, "수정한 연결 설명");
  await page.select("#child-linkText-mode", "clear");
  await save(page, "child");
  assert.equal((await api(page, link)).body.item.linkText, null);
  await layout(page, "rubric-clue-record");
  const linkList = (await api(page, `${apiPath}/rubric-clues`)).body;
  assert.deepEqual(Object.keys(linkList.items[0]).sort(), [
    "activeYn",
    "itemKey",
    "updatedAt",
  ]);
  assert.equal(
    (
      await create("rubric-clues", {
        rubricCode: "MISSING",
        clueCode: "RUBRIC_CLUE",
      })
    ).status,
    422,
  );
  assert.equal(
    (await change("rubric-clues/UI_RUBRIC~RUBRIC_CLUE", { clueCode: "OTHER" }))
      .status,
    400,
  );
  assert.equal(
    (
      await api(
        page,
        `${apiPath}/rubric-clues/UI_RUBRIC~RUBRIC_CLUE/deactivate`,
        "POST",
        { expectedRev: await rev() },
      )
    ).status,
    200,
  );
  assert.equal(
    (
      await api(
        page,
        `${apiPath}/rubric-clues/UI_RUBRIC~RUBRIC_CLUE/reactivate`,
        "POST",
        { expectedRev: await rev() },
      )
    ).status,
    200,
  );
  await latest();
  await open("rubrics", "UI_RUBRIC");
  const rule = {
    formatNo: 1,
    requiredNotice: "근거를 기록하세요.",
    claims: [
      {
        code: "CLAIM",
        meaning: "합성 명제 $$ $& $` $' 원문",
        factCodes: ["RUBRIC_FACT"],
        exampleClueRoutes: [["RUBRIC_CLUE"]],
      },
    ],
    levels: [
      { code: "ZERO", score: 0, routes: [] },
      { code: "FULL", score: 20, routes: [["CLAIM"]] },
    ],
    contradictions: [],
  };
  await json("{");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "올바른 JSON 객체");
  await json("[]");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "JSON 객체");
  await json(JSON.stringify({ oversized: "가".repeat(44000) }));
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "128 KiB");
  await json('{"formatNo":1,"unknown":true}');
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력 형식이나 크기");
  await json('{"formatNo":1,"formatNo":1}');
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력 형식이나 크기");
  assert.equal((await api(page, root)).body.item.ruleData, null);
  await json(JSON.stringify(rule, null, 2));
  await save(page, "child");
  assert.deepEqual((await api(page, root)).body.item.ruleData, rule);
  assert.match(
    await page.$eval("#child-ruleData-record", (e) => e.textContent),
    /"CLAIM"/,
  );
  assert.doesNotMatch(
    await page.$eval("#child-ruleData-record", (e) => e.textContent),
    /\[object Object\]/,
  );
  await layout(page, "rubric-json-record");
  assert.equal(
    (
      await api(page, `${apiPath}/facts/RUBRIC_FACT/deactivate`, "POST", {
        expectedRev: await rev(),
      })
    ).status,
    409,
  );
  assert.equal(
    (
      await api(
        page,
        `${apiPath}/rubric-clues/UI_RUBRIC~RUBRIC_CLUE/deactivate`,
        "POST",
        { expectedRev: await rev() },
      )
    ).status,
    409,
  );
  assert.equal(
    (
      await api(page, `${apiPath}/rubrics/UI_RUBRIC/deactivate`, "POST", {
        expectedRev: await rev(),
      })
    ).status,
    409,
  );
  await edit(
    page,
    "answer",
    "motiveAnswer",
    "소항목 충돌 동안 보존할 다른 영역",
  );
  await edit(page, "child", "acceptedText", "충돌에도 보존할 정답 안내");
  assert.equal(
    (await change("rubrics/UI_RUBRIC", { partialText: "원격 변경" })).status,
    200,
  );
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(
    await page.$eval("#child-acceptedText", (e) => e.value),
    "충돌에도 보존할 정답 안내",
  );
  assert.equal(
    await page.$eval("#answer-motiveAnswer", (e) => e.value),
    "소항목 충돌 동안 보존할 다른 영역",
  );
  await layout(page, "rubric-conflict");
  const failedRead = await loseResponse(page, `${baseUrl}${root}`);
  await clickWithConfirmation(page, "#refresh-latest");
  await notice(page, "불확실");
  assert.equal(await page.$eval("#accept-latest", (e) => e.disabled), true);
  assert.equal(
    await page.$eval("#child-acceptedText", (e) => e.value),
    "충돌에도 보존할 정답 안내",
  );
  await failedRead.detach();
  await clickWithConfirmation(page, "#refresh-latest");
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await save(page, "child");
  assert.equal(
    (await api(page, root)).body.item.acceptedText,
    "충돌에도 보존할 정답 안내",
  );

  await openSection(page, "child");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "RUBRIC_CULPRIT");
  await page.select("#child-category", "CULPRIT");
  assert.equal(
    await page.$eval("#child-ruleData-mode", (e) => e.disabled),
    true,
  );
  assert.equal(
    await page.$eval("#child-maxScore-mode", (e) => e.disabled),
    true,
  );
  await save(page, "child");
  const culprit = `${apiPath}/rubrics/RUBRIC_CULPRIT`;
  item = (await api(page, culprit)).body.item;
  assert.equal(item.maxScore, 25);
  assert.equal(item.passScore, 25);
  assert.equal(item.requiredYn, true);
  assert.equal(item.ruleData.claims[0].code, "SELECTED_CULPRIT");
  assert.equal(
    (await change("rubrics/RUBRIC_CULPRIT", { maxScore: 20 })).status,
    422,
  );
  await page.select("#child-category-mode", "value");
  await page.select("#child-category", "TIME");
  assert.equal(
    await page.$eval("#child-ruleData-mode", (e) => e.disabled),
    false,
  );
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "규칙 교체 또는 비우기");
  await page.select("#child-ruleData-mode", "clear");
  await save(page, "child");
  assert.equal((await api(page, culprit)).body.item.ruleData, null);
  assert.equal((await api(page, culprit)).body.item.category, "TIME");
  await layout(page, "rubric-category-exit");
  assert.equal(
    (await create("rubrics", { code: "RUBRIC_CULPRIT", category: "TIME" }))
      .status,
    409,
  );

  await openSection(page, "child");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "RUBRIC_LOST");
  await page.select("#child-category", "EVIDENCE");
  let posts = 0;
  const count = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname === `${apiPath}/rubrics`
    )
      posts++;
  };
  page.on("request", count);
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/rubrics`);
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(posts, 1);
  await lost.detach();
  await clickWithConfirmation(page, "#accept-latest");
  assert.equal(posts, 1);
  page.off("request", count);
  assert.equal(
    (await api(page, `${apiPath}/rubrics/RUBRIC_LOST`)).body.item.category,
    "EVIDENCE",
  );
  await layout(page, "rubric-reconciled");
  await save(page, "answer");
  assert.equal(
    (
      await api(page, `${apiPath}/rubrics/RUBRIC_LOST/deactivate`, "POST", {
        expectedRev: await rev(),
      })
    ).status,
    200,
  );
  assert.equal(
    (await create("rubrics", { code: "RUBRIC_LOST", category: "EVIDENCE" }))
      .status,
    409,
  );
  assert.equal(
    (
      await api(page, `${apiPath}/rubrics/RUBRIC_LOST/reactivate`, "POST", {
        expectedRev: await rev(),
      })
    ).status,
    200,
  );
  await latest();
  console.log(
    "PASS HTTPS rubrics: JSON syntax/duplicates, scores, fixed culprit, links, guards, conflict and lost response",
  );
}
