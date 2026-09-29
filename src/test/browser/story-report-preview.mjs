import assert from "node:assert/strict";

/**
 * 공개 가능한 합성 원고를 폐기형 HTTPS 시험 사건에 입력해 보고서 레이아웃을 검증한다.
 * 제외된 문서·비공개 콘텐츠에 의존하지 않으며 실제 서비스 DB는 변경하지 않는다.
 * @param {object} flow 인증된 browser, 서버가 반환한 시험 사건 url과 기존 layout/edit/save/notice 헬퍼.
 */
export async function captureReportPreview({
  browser,
  url,
  layout,
  edit,
  save,
  notice,
}) {
  const title = "합성 검증 사건 · 기록실의 시차";
  const intro = [
    "이 사건은 공개 소스의 화면 시험만을 위해 만든 합성 자료입니다.",
    "서로 다른 시각을 가리키는 두 기록과 관찰 내용을 비교합니다.",
    "참여자는 자신의 역할에 배정된 단서를 읽고 기록 사이의 차이를 확인합니다.",
    "이 원고에는 실제 인물이나 장소, 비공개 사건 문서의 내용이 포함되어 있지 않습니다.",
  ].join("\n");
  const persons = [
    { code: "P01", name: "합성 기록원" },
    { code: "P02", name: "합성 관찰자" },
    { code: "P03", name: "합성 점검원" },
  ];
  const answer = {
    culpritCode: "P01",
    method: "합성 시계의 표시를 바꾸어 기록 순서가 다른 것처럼 보이게 했다.",
    time: "합성 시간 기준으로 첫 점검과 두 번째 기록 사이.",
    motive: "연습용 기록 오류를 감추기 위한 가상의 동기.",
    evidence: "두 합성 기록의 관찰 순서와 시계 점검 흔적을 함께 비교한다.",
  };
  const roles = [
    { code: "A", name: "기록 확인자" },
    { code: "B", name: "시계 확인자" },
  ];
  const roleGuide = "배정된 합성 단서를 읽고 상대 역할의 관찰과 비교합니다.";
  const clues = [
    {
      code: "A1",
      roleCode: "A",
      title: "합성 출입 기록",
      body: "첫 번째 합성 점검 기록에는 출입 순서와 시계 표시가 함께 남아 있습니다.",
    },
    {
      code: "B1",
      roleCode: "B",
      title: "합성 시계 점검표",
      body: "두 번째 합성 점검표에는 시계의 표시를 변경한 흔적이 기록되어 있습니다.",
    },
  ];

  const page = await browser.newPage();
  const peer = await browser.newPage();
  const errors = [];
  for (const tab of [page, peer]) {
    tab.setDefaultTimeout(15000);
    tab.on("dialog", (dialog) => dialog.accept());
    tab.on("pageerror", (error) => errors.push(error.message));
  }

  /** 지정 탭을 활성화해 열고 실패 시 원고 대신 고정 상태 문구와 런타임 오류만 진단한다. */
  async function openReport(tab) {
    await tab.bringToFront();
    const response = await tab.goto(url);
    assert.equal(response.status(), 200);
    await tab.waitForSelector("#editor:not([hidden])").catch(async (error) => {
      console.error(
        "보고서 탭 로드 실패",
        await tab.evaluate(() => ({
          title: document.title,
          notice: document.getElementById("notice")?.textContent,
          unavailable: document.getElementById("unavailable-message")
            ?.textContent,
        })),
        errors,
      );
      throw error;
    });
  }

  try {
    await openReport(page);
    for (const [field, value] of Object.entries({
      title,
      intro,
      setting: "실재하지 않는 시험용 기록실",
      difficulty: 3,
      estMin: 20,
      estMax: 30,
      limitSec: 2100,
    })) {
      await edit(page, "basic", field, value);
    }
    await save(page, "basic");
    assert.match(
      await page.$eval("#policy", (e) => e.textContent),
      /인당 힌트/,
    );
    assert.doesNotMatch(
      await page.$eval("#policy", (e) => e.textContent),
      /hintsPerPerson|hintsPerChild/,
    );
    for (const child of persons) {
      await page.click("#child-new");
      await page.waitForFunction(
        () => document.getElementById("child-code")?.value === "",
      );
      for (const [field, value] of Object.entries({
        code: child.code,
        name: child.name,
        publicText: `${child.name}의 합성 공개 관찰 기록입니다.`,
        secretText: `${child.name}의 합성 비공개 검증 기록입니다.`,
      })) {
        await edit(page, "child", field, value);
      }
      await save(page, "child");
    }
    for (const [field, value] of Object.entries({
      culpritCode: answer.culpritCode,
      methodAnswer: answer.method,
      timeAnswer: answer.time,
      motiveAnswer: answer.motive,
    })) {
      await edit(page, "answer", field, value);
    }
    await save(page, "answer");
    await edit(page, "reveal", "revealText", answer.evidence);
    await save(page, "reveal");
    await page.select("#child-resource", "roles");
    for (const { code, name } of roles) {
      await page.click("#child-new");
      for (const [field, value] of Object.entries({
        code,
        name,
        brief: roleGuide,
      }))
        await edit(page, "child", field, value);
      await save(page, "child");
    }
    await layout(page, "child-role-report");
    await page.select("#child-resource", "pairs");
    await page.click("#child-new");
    await edit(page, "child", "roleA", roles[1].code);
    await edit(page, "child", "roleB", roles[0].code);
    await save(page, "child");
    await layout(page, "child-pair-report");
    await page.select("#child-resource", "clues");
    for (const { code, roleCode, title: clueTitle, body } of clues) {
      await page.click("#child-new");
      for (const [field, value] of Object.entries({
        code,
        title: clueTitle,
        body,
      }))
        await edit(page, "child", field, value);
      await save(page, "child");
      assert.equal(
        await page.$eval("#child-body-record", (e) => e.textContent),
        body,
      );
      await layout(page, `child-clue-${code}-report`);
      await page.select("#child-resource", "clue-roles");
      await page.click("#child-new");
      await edit(page, "child", "clueCode", code);
      await edit(page, "child", "roleCode", roleCode);
      await save(page, "child");
      assert.equal(
        await page.$eval("#pair-key-preview", (e) => e.hidden),
        true,
      );
      await layout(page, `child-clue-${code}-assignment-report`);
      await page.select("#child-resource", "clues");
    }
    await page.select("#child-resource", "persons");
    await page.click("#child-list");
    await page.waitForSelector('[data-child-key="P01"]');
    assert.equal(await page.$eval("#basic-intro", (e) => e.hidden), true);
    assert.equal(
      await page.$eval("#basic-intro-record", (e) => e.textContent),
      intro,
    );
    assert.equal(
      await page.$eval("#basic-intro-record", (e) => e.hidden),
      false,
    );
    await layout(page, "report-editor");

    await page.click('[data-child-key="P01"]');
    await page.waitForFunction(
      (name) =>
        document.getElementById("child-name-record")?.textContent === name,
      {},
      persons[0].name,
    );
    assert.equal(
      await page.$eval("#child-code-record", (e) => e.textContent),
      "P01",
    );
    assert.equal(await page.$eval("#child-code", (e) => e.hidden), true);
    assert.equal(
      await page.$eval(
        '[data-section="child"] [data-save-status]',
        (e) => e.textContent,
      ),
      "저장할 변경 없음",
    );
    await layout(page, "child-report");

    const draft = `${intro}\n\n${answer.time}`;
    await edit(page, "basic", "intro", draft);
    assert.equal(
      await page.$eval("#basic-intro-record", (e) => e.hidden),
      true,
    );
    await layout(page, "report-editing");
    await page.select("#basic-intro-mode", "keep");
    assert.equal(
      await page.$eval("#basic-intro-record", (e) => e.textContent),
      intro,
    );
    assert.equal(
      await page.$eval("#basic-intro-record", (e) => e.hidden),
      false,
    );
    await page.select("#basic-intro-mode", "value");
    assert.equal(await page.$eval("#basic-intro", (e) => e.value), draft);
    assert.equal(await page.$eval("#basic-intro", (e) => e.hidden), false);
    await page.select("#basic-intro-mode", "clear");
    assert.equal(await page.$eval("#basic-intro", (e) => e.hidden), true);
    assert.equal(
      await page.$eval("#basic-intro-record", (e) => e.hidden),
      true,
    );
    assert.equal(await page.$eval("#basic-intro", (e) => e.value), draft);
    assert.equal(
      await page.$eval(
        "#basic-intro",
        (e) => e.closest(".field").querySelector(".clear-record").hidden,
      ),
      false,
    );
    await layout(page, "report-clearing");
    await page.select("#basic-intro-mode", "value");

    await openReport(peer);
    await edit(peer, "basic", "intro", `${intro}\n\n${answer.evidence}`);
    await save(peer, "basic");
    await page.bringToFront();
    await page.click("#basic-save");
    await notice(page, "검토 전 저장은 차단");
    assert.equal(await page.$eval("#basic-intro", (e) => e.value), draft);
    await layout(page, "report-conflict");

    await peer.bringToFront();
    await peer.goto(`${new URL(url).origin}/admin/stories`);
    await notice(peer, "현재 페이지");
    await peer.type("#code", new URL(url).pathname.split("/")[3]);
    await peer.click("#filter-form button");
    await notice(peer, "현재 페이지");
    await peer.waitForFunction(
      () => document.querySelectorAll("#rows .story-row").length === 1,
    );
    assert.equal(
      await peer.$eval("#rows .story-name", (e) => e.textContent),
      title,
    );
    assert.doesNotMatch(
      await peer.$eval("#rows", (e) => e.textContent),
      /<script>|local-answer/,
    );
    await layout(peer, "report-list");
    assert.deepEqual(errors, []);
    console.log(
      "PASS synthetic report reading, explicit editing/clearing, draft preservation and layouts without private documents",
    );
  } finally {
    if (browser.connected) {
      await page.close();
      await peer.close();
    }
  }
}
