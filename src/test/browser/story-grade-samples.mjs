import assert from "node:assert/strict";

/** 폐기형 HTTPS에서 구조화 예시의 보호 조회·수정·복구를 확인한다. */
export async function exerciseGradeSamples({
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
  const root = `${apiPath}/grade-samples/UI_SAMPLE`;
  const rev = async () => (await api(page, apiPath)).body.editRev;
  await page.select("#child-resource", "grade-samples");
  await page.click("#child-new");
  await edit(page, "child", "code", "UI_SAMPLE");
  await save(page, "child");
  assert.deepEqual(
    Object.keys(
      (await api(page, `${apiPath}/grade-samples`)).body.items[0],
    ).sort(),
    ["activeYn", "code", "updatedAt"],
  );
  assert.equal((await api(page, root)).body.item.inputData, null);
  assert.equal((await api(page, root)).body.item.checkedBy, null);
  await layout(page, "grade-sample-draft");

  await edit(page, "child", "inputData", '{"formatNo":1,"report":{');
  await page.click("#child-save");
  await notice(page, "올바른 JSON 객체");
  await edit(
    page,
    "child",
    "inputData",
    JSON.stringify({ formatNo: 1, report: { culpritCode: 47, method: null } }),
  );
  await edit(
    page,
    "child",
    "expectData",
    JSON.stringify({
      formatNo: 1,
      kind: "INPUT_ERROR",
      error: {
        code: "INVALID_REPORT",
        state: "REJECTED",
        score: null,
        attemptDelta: 0,
      },
    }),
  );
  await edit(page, "child", "reason", "<script>실행 금지</script> 합성 오류");
  await save(page, "child");
  const sample = (await api(page, root)).body.item;
  assert.equal(sample.inputData.report.culpritCode, 47);
  assert.equal(sample.expectData.kind, "INPUT_ERROR");
  assert.equal(sample.reason, "<script>실행 금지</script> 합성 오류");
  assert.equal(
    await page.$$eval("#child-reason-record script", (nodes) => nodes.length),
    0,
  );

  let detailReads = 0;
  const countDetail = (request) => {
    if (request.method() === "GET" && new URL(request.url()).pathname === root)
      detailReads++;
  };
  page.on("request", countDetail);
  await page.select("#child-resource", "facts");
  await page.select("#child-resource", "grade-samples");
  await page.waitForSelector('[data-child-key="UI_SAMPLE"]');
  assert.equal(detailReads, 0);
  await page.click('[data-child-key="UI_SAMPLE"]');
  await page.waitForFunction(
    () => !document.getElementById("child-resource").disabled,
  );
  assert.equal(detailReads, 1);
  page.off("request", countDetail);

  await page.select("#child-expectedScore-mode", "value");
  await page.$eval("#child-expectedScore", (node) => {
    node.value = "101";
  });
  await page.click("#child-save");
  await notice(page, "입력을 확인하세요");
  await page.select("#child-expectedScore-mode", "keep");
  const body =
    '{"expectedRev":"' +
    (await rev()) +
    '","changes":{"reason":"중복","reason":"거절"}}';
  const duplicated = await page.evaluate(
    async ({ path, body }) => {
      const csrf = await (await fetch("/admin/api/auth/csrf")).json();
      const response = await fetch(path, {
        method: "PATCH",
        headers: {
          [csrf.headerName]: csrf.token,
          "Content-Type": "application/json",
        },
        body,
      });
      return response.status;
    },
    { path: root, body },
  );
  assert.equal(duplicated, 400);

  let posts = 0;
  const count = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname === `${apiPath}/grade-samples`
    )
      posts++;
  };
  page.on("request", count);
  await page.click("#child-new");
  await edit(page, "child", "code", "SAMPLE_LOST");
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/grade-samples`);
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(posts, 1);
  await lost.detach();
  await page.click("#accept-latest");
  assert.equal(posts, 1);
  page.off("request", count);
  assert.equal(
    (await api(page, `${apiPath}/grade-samples/SAMPLE_LOST`)).status,
    200,
  );
  assert.equal(
    (
      await api(
        page,
        `${apiPath}/grade-samples/SAMPLE_LOST/deactivate`,
        "POST",
        {
          expectedRev: await rev(),
        },
      )
    ).status,
    200,
  );
  assert.equal(
    (
      await api(page, `${apiPath}/grade-samples`, "POST", {
        expectedRev: await rev(),
        item: { code: "SAMPLE_LOST" },
      })
    ).status,
    409,
  );
  assert.equal(
    (
      await api(
        page,
        `${apiPath}/grade-samples/SAMPLE_LOST/reactivate`,
        "POST",
        {
          expectedRev: await rev(),
        },
      )
    ).status,
    200,
  );
  console.log(
    "PASS HTTPS grade-samples: draft JSON, guarded detail, XSS, duplicate keys and lost response",
  );
}
