import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { clickWithConfirmation } from "./confirmation.mjs";

/** SR09의 실제 구조 계약만 충족하는 폐기형 예시를 API로 만든다. 사람 품질 승인 자료가 아니다. */
async function createCheckFixture(page, api) {
  const created = await api(page, "/admin/api/stories", "POST", {
    createKey: randomUUID(),
    title: "저장 초안 확인 합성 회귀",
  });
  assert.equal(created.status, 201);
  const root = `/admin/api/stories/${created.body.storyCode}/versions/1`;
  const change = async (path, method, payload) => {
    const detail = await api(page, root);
    assert.equal(detail.status, 200);
    const result = await api(page, `${root}/${path}`, method, {
      expectedRev: detail.body.editRev,
      ...payload,
    });
    assert.ok(
      [200, 201].includes(result.status),
      `${path}: ${result.status} ${result.body.code || ""}`,
    );
  };
  await change("persons", "POST", { item: { code: "P", name: "합성 인물" } });
  await change("facts", "POST", {
    item: {
      code: "F",
      statement: "합성 명제",
      truth: "TRUE",
      basis: "합성 근거",
    },
  });
  await change("clues", "POST", {
    item: { code: "C", scope: "COMMON", title: "합성 단서", body: "합성 본문" },
  });
  const weights = {
    CULPRIT: 25,
    METHOD: 20,
    TIME: 15,
    MOTIVE: 10,
    EVIDENCE: 30,
  };
  for (const [category, max] of Object.entries(weights)) {
    const required = ["CULPRIT", "METHOD", "EVIDENCE"].includes(category);
    await change("rubrics", "POST", {
      item:
        category === "CULPRIT"
          ? { code: category, category }
          : {
              code: category,
              category,
              maxScore: max,
              requiredYn: required,
              passScore: required ? max : null,
            },
    });
    if (category === "CULPRIT") continue;
    await change("rubric-clues", "POST", {
      item: { rubricCode: category, clueCode: "C", linkText: "합성 연결" },
    });
    await change(`rubrics/${category}`, "PATCH", {
      changes: {
        ruleData: {
          formatNo: 1,
          requiredNotice: required ? "합성 필수 목표" : null,
          claims: [
            {
              code: "CLAIM",
              meaning: "합성 명제",
              factCodes: ["F"],
              exampleClueRoutes: [["C"]],
            },
          ],
          levels: [
            { code: "ZERO", score: 0, routes: [] },
            { code: "FULL", score: max, routes: [["CLAIM"]] },
          ],
          contradictions: [],
        },
      },
    });
  }
  for (const code of ["ZERO", "FULL", "INPUT", "ENGINE"]) {
    const graded = ["ZERO", "FULL"].includes(code);
    const full = code === "FULL";
    const kind = graded
      ? "GRADED"
      : code === "INPUT"
        ? "INPUT_ERROR"
        : "ENGINE_ERROR";
    const inputData = {
      formatNo: 1,
      report:
        code === "INPUT"
          ? null
          : {
              culpritCode: "P",
              method: "합성 제출",
              time: "",
              motive: "",
              evidence: "",
            },
      fault: code === "ENGINE" ? { type: "TIMEOUT", failRuns: 3 } : null,
    };
    const expectData = {
      formatNo: 1,
      kind,
      items: graded
        ? Object.entries(weights).map(([rubricCode, max]) => ({
            rubricCode,
            score: full ? max : 0,
            requiredMet: ["CULPRIT", "METHOD", "EVIDENCE"].includes(rubricCode)
              ? full
              : null,
            reason: "합성 항목 근거",
          }))
        : null,
      error: graded
        ? null
        : {
            code: code === "INPUT" ? "INVALID_REPORT" : "GRADING_UNAVAILABLE",
            state: code === "INPUT" ? "REJECTED" : "SYSTEM_ERROR",
            score: null,
            attemptDelta: 0,
          },
    };
    await change("grade-samples", "POST", {
      item: {
        code,
        inputData,
        expectData,
        expectedScore: graded ? (full ? 100 : 0) : null,
        expectedSuccess: graded ? full : null,
        reason: "사람 확인 동작용 합성 근거",
      },
    });
  }
  return root;
}

/** 패널의 실제 비동기 상태만 관찰하며 제품 메모리에 시험 훅을 추가하지 않는다. */
async function reviewMessage(page, fragment) {
  await page.waitForFunction(
    (text) =>
      document
        .getElementById("review-check-status")
        ?.textContent.includes(text),
    {},
    fragment,
  );
}

/** 남겨 둔 합성 입력만 원래 버퍼로 되돌린 뒤 기존 기록 유지 동작을 선택한다. */
async function cancelFixtureInput(page, openSection, section, key, original) {
  await openSection(page, section);
  await page.select(`#${section}-${key}-mode`, "value");
  await page.$eval(
    `#${section}-${key}`,
    (node, value) => {
      node.value = value;
      node.dispatchEvent(new Event("input", { bubbles: true }));
    },
    original,
  );
  await page.select(`#${section}-${key}-mode`, "keep");
}

/**
 * 기존 폐기형 SR09 초안에 실제 구조 검사 필수 원고·역할·힌트를 API로 채운다.
 * @param {object} page 인증된 폐기형 페이지.
 * @param {function} api 기존 same-origin 실제 API 헬퍼.
 * @param {string} root 이 시험에서 생성한 버전 API 경로.
 * @returns {Promise<object>} 현재 상세와 합성 원문 표식. 실제 사건43·품질·모델 실행 근거가 아니다.
 * @throws {Error} 실제 저장 계약이나 전체 구조 검사를 충족하지 못하면 중단한다.
 */
async function completeSavedReadFixture(page, api, root) {
  const before = await api(page, root);
  assert.equal(before.status, 200);
  let revision = before.body.editRev;
  /**
   * 현재 영수증 수정번호만 이어서 쓰며 실패한 변경은 재전송하지 않는다.
   * @param {string} path 기존 초안 자원 또는 사람 확인의 상대 경로.
   * @param {string} method POST/PATCH 중 해당 API의 동작.
   * @param {object} payload 실제 계약의 item/changes 또는 빈 객체.
   * @throws {Error} 200/201 영수증이 없으면 fixture 구성을 중단한다.
   */
  async function change(path, method, payload) {
    const result = await api(page, `${root}/${path}`, method, {
      expectedRev: revision,
      ...payload,
    });
    assert.ok(
      [200, 201].includes(result.status),
      `${path}: ${result.status} ${result.body.code || ""}`,
    );
    revision = result.body.editRev;
  }
  const literal =
    '<img src="https://saved-read.invalid/never-fetch" onerror="window.savedReadInjected=true">';
  const intro =
    `불변 사본 합성 도입 · ${literal}\n` +
    "역할별 저장 원고의 줄바꿈과 읽기 폭을 확인합니다. ".repeat(12);
  await change("sections/basic", "PATCH", {
    changes: {
      intro,
      setting: "실제 콘텐츠가 아닌 폐기형 구조 시험",
      timelineOrigin: "합성 기준점",
      difficulty: 3,
      estMin: 20,
      estMax: 30,
      limitSec: 2100,
    },
  });
  await change("sections/answer", "PATCH", {
    changes: {
      culpritCode: "P",
      methodAnswer: "SAVED_PRIVATE_ANSWER",
      timeAnswer: "합성 시간 정답",
      motiveAnswer: "합성 동기 정답",
    },
  });
  await change("sections/reveal", "PATCH", {
    changes: { revealText: "합성 종료 해설 · SAVED_REVEAL_ONLY" },
  });
  await change("persons/P", "PATCH", {
    changes: {
      publicText: "합성 공개 소개",
      secretText: "SAVED_PRIVATE_PERSON",
    },
  });
  await change("clues/C", "PATCH", {
    changes: { sourceText: "", personCode: null },
  });
  for (const code of ["A", "B"])
    await change("roles", "POST", {
      item: { code, name: `합성 역할 ${code}`, brief: `합성 관점 ${code}` },
    });
  await change("pairs", "POST", { item: { roleA: "A", roleB: "B" } });
  for (const level of [1, 2, 3])
    await change("hints", "POST", {
      item: { code: `H${level}`, level, body: `합성 단계 ${level} 안내` },
    });
  await change("events", "POST", {
    item: {
      code: "E",
      startMin: 0,
      endMin: 1,
      actualText: "합성 실제 시간선",
      apparentText: "합성 표면 시간선",
    },
  });
  for (const code of ["CULPRIT", "METHOD", "TIME", "MOTIVE", "EVIDENCE"])
    await change(`rubrics/${code}`, "PATCH", {
      changes: {
        acceptedText: "합성 정답 조건",
        partialText: "합성 부분 조건",
        rejectText: "합성 오답 조건",
      },
    });
  // 자동화의 합성 확인 기록이며 사람이 실제 원고 품질을 승인했다는 증거로 사용하지 않는다.
  await change("grade-samples/check", "POST", {});
  const inspected = await api(page, `${root}/review-precheck`, "POST", {
    expectedRev: revision,
  });
  assert.equal(inspected.status, 200);
  assert.equal(
    inspected.body.eligible,
    true,
    JSON.stringify(inspected.body.errors),
  );
  const detail = await api(page, root);
  assert.equal(detail.status, 200);
  return { detail: detail.body, intro, literal };
}

/**
 * 보이는 실제 행동과 공통 확인창으로만 전환하며 서버의 원래 영수증을 반환한다.
 * @param {object} page 현재 폐기형 편집기.
 * @param {string} root 이 시험이 API로 생성한 버전 경로.
 * @param {string} selector 실제 활성 요청·반환 버튼.
 * @param {string} suffix review-requests 또는 return-to-draft.
 * @returns {Promise<object>} 실제 HTTP status/body와 전송 본문.
 * @throws {Error} 확인·서버 응답·후속 현재 GET이 실패하면 단언을 중단한다.
 */
async function submitLifecycleControl(page, root, selector, suffix) {
  const identityReads = [];
  /** 제품이 실제 경계에서 받은 Me만 관찰하며 응답이나 자격은 대체하지 않는다. */
  const identity = (response) => {
    if (
      new URL(response.url()).pathname === "/admin/api/auth/me" &&
      response.request().method() === "GET"
    )
      identityReads.push(
        response.json().then((body) => ({
          status: response.status(),
          body,
        })),
      );
  };
  page.on("response", identity);
  try {
    const response = page.waitForResponse(
      (value) =>
        new URL(value.url()).pathname === `${root}/${suffix}` &&
        value.request().method() === "POST",
    );
    await page.click(selector);
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    const received = await response;
    const result = {
      status: received.status(),
      body: await received.json(),
      sent: JSON.parse(received.request().postData()),
    };
    assert.ok([200, 201].includes(result.status), JSON.stringify(result.body));
    await page.waitForFunction(() =>
      document
        .getElementById("review-lifecycle-status")
        .textContent.includes("전환 영수증은 확정됐습니다"),
    );
    assert.equal(
      await page.$eval(
        "#review-lifecycle-receipt",
        (node) => node.dataset.state,
      ),
      "confirmed",
    );
    const observations = await Promise.all(identityReads);
    assert.ok(
      observations.length >= 6,
      "동의 전후·CSRF 뒤·영수증 채택 전·후속 GET 전후의 실제 Me 관측",
    );
    const anchor = observations[0].body;
    assert.match(
      anchor.accountKey,
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
    assert.ok(Number.isFinite(Date.parse(anchor.absoluteExpiresAt)));
    for (const observation of observations) {
      assert.equal(observation.status, 200);
      assert.equal(observation.body.accountKey, anchor.accountKey);
      assert.equal(
        observation.body.absoluteExpiresAt,
        anchor.absoluteExpiresAt,
      );
    }
    return result;
  } finally {
    page.off("response", identity);
  }
}

/** 현재 실제 GET 비교 결과를 명시적으로 수락하며 저장·전환을 추가 실행하지 않는다. */
async function acceptLifecycleBaseline(page) {
  await page.waitForFunction(
    () => !document.getElementById("accept-latest").disabled,
  );
  await page.click("#accept-latest");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await page.click("#ui-confirm-accept");
  await page.waitForFunction(
    () => document.getElementById("comparison").hidden,
  );
}

/**
 * 실제 링크 탐색에서 브라우저의 beforeunload 대화상자 발생 여부를 관찰한다.
 * @param {object} page 기존 확인 운전자가 네이티브 경고를 승인하는 실제 HTTPS 페이지.
 * @param {boolean} expected 미저장 반환 입력이면 true, 확정·수락된 깨끗한 화면이면 false.
 * @throws {Error} 탐색 실패·경고 횟수 불일치는 그대로 전파한다. 합성 이벤트를 사용하지 않는다.
 */
async function navigateLifecycleLeave(page, expected) {
  const dialogs = [];
  /** 기존 운전자의 승인 처리를 건드리지 않고 실제 네이티브 경고 종류만 기록한다. */
  const observe = (dialog) => dialogs.push(dialog.type());
  page.on("dialog", observe);
  try {
    await Promise.all([
      page.waitForNavigation(),
      page.click('.top-nav a[href="/admin/stories"]'),
    ]);
    assert.deepEqual(dialogs, expected ? ["beforeunload"] : []);
    assert.equal(new URL(page.url()).pathname, "/admin/stories");
    await page.waitForFunction(() =>
      document
        .getElementById("notice")
        .textContent.includes("현재 페이지를 조회했습니다"),
    );
  } finally {
    page.off("dialog", observe);
  }
}

/**
 * 실제 SR05 UI에 정직한 미실시 메타만 입력한다. 합성 시험이며 모델·사람 판정을 만들지 않는다.
 * @param {object} page 현재 사본 기준을 수락한 실제 HTTPS 편집기.
 * @param {string} kind MODEL 또는 APPROVAL.
 */
async function fillUnperformedManual(page, kind) {
  await page.select("#manual-kind", kind);
  await page.select("#manual-result", "INCOMPLETE");
  for (const key of [
    "modelId",
    "effort",
    "evidenceRef",
    "checkedAt",
    "criticalOpenCount",
    "runRef",
  ])
    await page.select(`#manual-${key}-mode`, "null");
  await page.select("#manual-separateContext", "null");
  await page.select("#manual-formatNo", kind === "MODEL" ? "1" : "2");
  for (const [key, text] of [
    [
      "evidence",
      "Synthetic HTTPS fixture: review not performed. No model execution or human quality approval.",
    ],
    [
      "notes",
      "Not performed. Explicit absence metadata for UI transport verification only.",
    ],
  ]) {
    // 실제 입력 요소에 키보드 선택·입력으로 교체하며 제품 상태나 숨은 필드는 조작하지 않는다.
    await page.focus(`#manual-${key}`);
    const length = await page.$eval(
      `#manual-${key}`,
      (input) => input.value.length,
    );
    for (let index = 0; index < length; index++)
      await page.keyboard.press("ArrowLeft");
    await page.keyboard.down("Shift");
    for (let index = 0; index < length; index++)
      await page.keyboard.press("ArrowRight");
    await page.keyboard.up("Shift");
    await page.keyboard.press("Backspace");
    await page.type(`#manual-${key}`, text);
  }
}

/** 실제 확인창으로 선택한 현재 사본과 기존 상세 쓰기 기준만 수락한다. */
async function acceptManualControl(page, snapshotId) {
  await page.click(`[data-snapshot-id="${snapshotId}"]`);
  await page.click("#manual-baseline");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await page.click("#ui-confirm-accept");
  await page.waitForSelector("#manual-submit:enabled", { visible: true });
}

/**
 * 명시 SR05 POST의 실제 영수증만 확인한다. 민감 이력 GET이나 변경 재시도를 만들지 않는다.
 * @param {object} page 실제 HTTPS 페이지.
 * @param {string} path 현재 시험의 records API.
 * @param {string} selector 새 기록 또는 원래 본문 재확인 버튼.
 * @returns {Promise<object>} 정확한 전송 문자열과 실제 영수증·상태.
 */
async function submitManualControl(page, path, selector) {
  const waiting = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path &&
      response.request().method() === "POST",
  );
  await page.click(selector);
  await page.waitForSelector("#ui-confirm-dialog[open]");
  await page.click("#ui-confirm-accept");
  const response = await waiting;
  const result = {
    status: response.status(),
    body: await response.json(),
    sent: response.request().postData(),
  };
  assert.ok([200, 201].includes(result.status), "실제 SR05 성공 상태");
  await page.waitForFunction(
    () =>
      document
        .getElementById("manual-status")
        .textContent.includes("기록 영수증을 확인") &&
      !document.getElementById("manual-refresh").disabled,
  );
  assert.deepEqual(
    Object.keys(result.body).sort(),
    ["recordId", "snapshotId", "current", "replayed", "requestId"].sort(),
  );
  assert.equal(
    await page.$eval("#manual-receipt", (node) => node.dataset.state),
    "confirmed",
  );
  return result;
}

/**
 * 기존 두 번째 사본에서만 MODEL/APPROVAL 미실시를 기록하고 실제 유실·동일 본문 replay를 확인한다.
 * @param {object} flow 기존 실제 서버·레이아웃·전송 유실 헬퍼와 두 번째 사본 정보.
 * @returns {Promise<object>} 이후 역사 replay를 대조할 마지막 APPROVAL 영수증.
 */
async function exerciseManualRecords({
  page,
  root,
  second,
  baseUrl,
  loseResponse,
  layout,
}) {
  const path = `${root}/review-snapshots/${second.body.snapshotId}/records`;
  const posts = [];
  const reads = [];
  const observe = (request) => {
    const url = new URL(request.url());
    if (url.pathname === path) {
      if (request.method() === "POST") posts.push(request.postData());
      else reads.push(url.search);
    }
  };
  page.on("request", observe);
  try {
    await acceptManualControl(page, second.body.snapshotId);
    await fillUnperformedManual(page, "MODEL");
    await page.focus("#manual-submit");
    await page.keyboard.press("Enter");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "ui-confirm-cancel",
    );
    await page.keyboard.press("Escape");
    await page.waitForFunction(
      () =>
        document
          .getElementById("manual-status")
          .textContent.includes("전송하지 않았습니다") &&
        !document.getElementById("manual-refresh").disabled,
    );
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "manual-submit",
    );
    assert.equal(posts.length, 0);
    await layout(page, "manual-review-unperformed-fields");

    // 실제 서버 처리 이후 응답 전송만 유실시킨다. 허구의 성공 응답을 주입하지 않는다.
    const lost = await loseResponse(page, `${baseUrl}${path}`);
    try {
      await page.click("#manual-submit");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      await page.click("#ui-confirm-accept");
      await page.waitForFunction(
        () =>
          document.getElementById("manual-receipt").dataset.state ===
            "unknown" && !document.getElementById("manual-refresh").disabled,
      );
    } finally {
      await lost.detach();
    }
    assert.equal(posts.length, 1, "유실 후 자동 재전송 없음");
    assert.deepEqual(reads, [], "유실 후 민감 이력 자동 조회 없음");
    const model = await submitManualControl(page, path, "#manual-reconfirm");
    assert.equal(model.status, 200);
    assert.equal(model.body.replayed, true);
    assert.equal(model.sent, posts[0], "원래 정확한 본문으로만 replay");
    const modelBody = JSON.parse(model.sent);
    assert.equal(modelBody.expectedRev, second.body.editRev);
    assert.notEqual(modelBody.expectedRev, second.body.sourceRev);
    assert.deepEqual(
      Object.keys(modelBody).sort(),
      [
        "expectedRev",
        "requestKey",
        "kind",
        "result",
        "modelId",
        "effort",
        "evidence",
        "evidenceData",
      ].sort(),
    );
    assert.equal(modelBody.kind, "MODEL");
    assert.equal(modelBody.result, "INCOMPLETE");
    assert.equal(modelBody.modelId, null);
    assert.equal(modelBody.effort, null);
    for (const key of [
      "evidenceRef",
      "checkedAt",
      "criticalOpenCount",
      "runRef",
      "separateContext",
    ])
      assert.equal(modelBody.evidenceData[key], null);

    await fillUnperformedManual(page, "APPROVAL");
    const approval = await submitManualControl(page, path, "#manual-submit");
    assert.equal(approval.status, 201);
    assert.equal(approval.body.replayed, false);
    assert.notEqual(approval.body.recordId, model.body.recordId);
    const approvalBody = JSON.parse(approval.sent);
    assert.equal(approvalBody.kind, "APPROVAL");
    assert.equal(approvalBody.result, "INCOMPLETE");
    assert.equal(approvalBody.modelId, null);
    assert.equal(approvalBody.effort, null);
    assert.equal(approvalBody.evidenceData.formatNo, 2);
    assert.deepEqual(approvalBody.evidenceData.resolves, []);
    for (const key of [
      "evidenceRef",
      "checkedAt",
      "criticalOpenCount",
      "runRef",
      "separateContext",
    ])
      assert.equal(approvalBody.evidenceData[key], null);
    assert.notEqual(approvalBody.requestKey, modelBody.requestKey);
    assert.equal(posts.length, 3);
    assert.deepEqual(reads, []);

    const waiting = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === path &&
        response.request().method() === "GET",
    );
    await page.click("#saved-records-first");
    const response = await waiting;
    assert.equal(response.status(), 200);
    const original = await response.text();
    await page.waitForFunction(
      () =>
        document.getElementById("saved-records-status").dataset.state ===
        "success-populated",
    );
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      original,
    );
    const stored = JSON.parse(original);
    assert.deepEqual(
      stored.items.map(({ kind, result }) => [kind, result]),
      [
        ["APPROVAL", "INCOMPLETE"],
        ["MODEL", "INCOMPLETE"],
        ["STRUCTURE", "PASS"],
      ],
    );
    assert.equal(stored.items[0].recordId, approval.body.recordId);
    assert.equal(stored.items[1].recordId, model.body.recordId);
    return approval;
  } finally {
    page.off("request", observe);
  }
}

/**
 * 이미 인가된 실제 두 사본의 빈 PT-A11 이력만 확인한다. 지적·실행·해소 행은 만들지 않는다.
 * @param {object} flow 기존 HTTPS 페이지·두 사본·전송 유실·공통 배치 검사.
 * @throws {Error} 실제 빈 응답 계약·명시 조회·키보드·유실 보호가 다르면 검사 실패.
 */
async function exerciseExecutionIssueHistory({
  page,
  root,
  first,
  second,
  baseUrl,
  loseResponse,
  layout,
}) {
  const reads = [];
  const observe = (request) => {
    if (new URL(request.url()).pathname === `${root}/execution-issues`)
      reads.push(request);
  };
  page.on("request", observe);
  try {
    for (const snapshot of [first, second]) {
      const beforeSelection = reads.length;
      await page.click(`[data-snapshot-id="${snapshot.body.snapshotId}"]`);
      assert.equal(
        reads.length,
        beforeSelection,
        "선택은 이력을 미리 읽지 않음",
      );
      for (const filter of ["ALL", "OPEN", "RESOLVED"]) {
        const beforeFilter = reads.length;
        await page.select("#saved-issues-filter", filter);
        assert.equal(reads.length, beforeFilter, "필터 변경은 GET이 아님");
        await page.focus("#saved-issues-filter");
        await page.keyboard.press("Tab");
        assert.equal(
          await page.evaluate(() => document.activeElement.id),
          "saved-issues-first",
        );
        const waiting = page.waitForResponse(
          (response) =>
            new URL(response.url()).pathname === `${root}/execution-issues`,
        );
        await page.keyboard.press("Enter");
        const response = await waiting;
        assert.equal(response.status(), 200);
        const body = await response.json();
        assert.deepEqual(Object.keys(body).sort(), [
          "items",
          "nextCursor",
          "requestId",
        ]);
        assert.deepEqual(
          body.items,
          [],
          "실제 integration DB에는 실행 지적이 없음",
        );
        assert.equal(body.nextCursor, null);
        assert.match(body.requestId, /^[0-9a-f-]{36}$/i);
        await page.waitForFunction(
          () =>
            document.getElementById("saved-issues-status").dataset.state ===
              "success-empty" &&
            !document.getElementById("saved-issues-first").disabled,
        );
        const query = new URL(reads.at(-1).url()).searchParams;
        assert.deepEqual(
          [...query.entries()],
          [
            ["snapshotId", snapshot.body.snapshotId],
            ["size", "20"],
            ...(filter === "ALL" ? [] : [["state", filter]]),
          ],
        );
        assert.equal(reads.at(-1).method(), "GET");
        assert.equal(
          reads.length,
          beforeFilter + 1,
          "명시 한 페이지 외 요청 없음",
        );
        assert.equal(
          await page.$eval("#saved-issues-next", (node) => node.disabled),
          true,
        );
        assert.match(
          await page.$eval("#saved-issues-status", (node) => node.textContent),
          snapshot === first ? /목록 조회 당시 과거/ : /목록 조회 당시 현재/,
        );
      }
    }
    await layout(page, "execution-issue-history-empty");
    const beforeLoss = reads.length;
    const lost = await loseResponse(
      page,
      `${baseUrl}${root}/execution-issues?*`,
    );
    try {
      await page.click("#saved-issues-first");
      await page.waitForFunction(
        () =>
          document.getElementById("saved-issues-status").dataset.state ===
            "failure" &&
          !document.getElementById("saved-issues-first").disabled,
      );
      assert.equal(
        reads.length,
        beforeLoss + 1,
        "실제 응답 유실 뒤 자동 재전송 없음",
      );
      assert.equal(
        await page.$eval("#saved-issues-result", (node) => node.textContent),
        "",
      );
      assert.equal(
        await page.$eval("#saved-issues-next", (node) => node.disabled),
        true,
      );
      assert.ok(
        (
          await page.$eval("#saved-review-selected", (node) => node.textContent)
        ).includes(second.body.snapshotId),
      );
    } finally {
      await lost.detach();
    }
    await page.select("#saved-issues-filter", "ALL");
    assert.equal(reads.length, beforeLoss + 1);
    console.log(
      "PASS HTTPS PT-A11: existing two snapshots, actual empty ALL/OPEN/RESOLVED, explicit GET and transport loss; no populated execution issue, execution or resolution claim",
    );
  } finally {
    page.off("request", observe);
  }
}

/**
 * 실제 현재 사본 경로의 UI만 합성 전송으로 검사한다. terminal PG 행·실행·해소 API 성공을 만들지 않는다.
 * @param {object} flow 실제 HTTPS 페이지·현재 사본·수정번호와 기존 배치 검사기.
 * @throws {Error} 원래 요청 재생·입력 보존·닫힌 화면·기존 배치 단언 실패를 전파한다.
 */
async function exerciseIssueResolutionUi({ page, root, second, revision, layout }) {
  const issueKey = "a1212121-1212-4212-8212-121212121212";
  const batchKey = "b1212121-1212-4212-8212-121212121212";
  const sourceKey = "c1212121-1212-4212-8212-121212121212";
  const requestId = "d1212121-1212-4212-8212-121212121212";
  const issuePath = `${root}/execution-issues`;
  const path = `${issuePath}/${issueKey}/resolve`;
  const detailPath = `${root}/regressions/${batchKey}`;
  const literal = '<img src=x onerror="window.resolutionInjected=true">';
  const calls = [];
  const failures = [];
  const client = await page.createCDPSession();
  await client.send("Fetch.enable", { patterns: [
    { urlPattern: `*${issuePath}*`, requestStage: "Request" },
    { urlPattern: `*${detailPath}`, requestStage: "Request" },
  ] });
  /** 고정 세 경로만 합성 응답하며 다른 경로는 기존 실제 서버로 보낸다. */
  const intercept = async (event) => {
    const url = new URL(event.request.url);
    if (![issuePath, path, detailPath].includes(url.pathname)) {
      await client.send("Fetch.continueRequest", { requestId: event.requestId });
      return;
    }
    calls.push({ path: url.pathname, method: event.request.method, body: event.request.postData });
    let data;
    if (url.pathname === issuePath) {
      data = { items: [{
        issueKey, snapshotId: second.body.snapshotId, runtimeConfigId: `SYNTHETIC_${literal}`,
        sourceBatchKey: sourceKey, sourceReviewId: null, kind: "INFRA", severity: "CRITICAL",
        state: "OPEN", createdAt: "2026-10-01T00:00:00Z", resolvedAt: null, resolvedBy: null,
        targetBatchKey: null, targetReviewId: null, resolution: null,
      }], nextCursor: null, requestId };
    } else if (url.pathname === detailPath) {
      data = {
        batchKey, purpose: "REVIEW", snapshotId: second.body.snapshotId,
        runtimeConfigId: "SYNTHETIC_UI_ONLY", runtimeEpoch: "1", datasetHash: "a".repeat(64),
        state: "COMPLETED", passed: true, repeatCount: 3, totalJobs: 3, completedJobs: 3,
        failedComparisons: 0, unresolvedJobs: 0, createdAt: "2026-10-01T00:01:00Z",
        batchDeadline: "2026-10-02T00:01:00Z", completedAt: "2026-10-01T00:02:00Z",
        validUntil: null, items: [1, 2, 3].map((repeatNo) => ({
          sampleCode: "SYNTHETIC", repeatNo, jobKey: `e1212121-1212-4212-8212-12121212121${repeatNo}`,
          state: "COMPLETED", comparison: "PASS", errorCode: null,
        })), requestId,
      };
    } else {
      assert.equal(event.request.method, "POST");
      const posts = calls.filter((call) => call.path === path);
      if (posts.length === 1) {
        await client.send("Fetch.failRequest", { requestId: event.requestId, errorReason: "ConnectionClosed" });
        return;
      }
      assert.equal(event.request.postData, posts[0].body, "합성 A의 정확한 원래 본문만 재생");
      const body = JSON.parse(event.request.postData);
      const original = { issueKey, snapshotId: second.body.snapshotId, editRev: revision,
        state: "RESOLVED", targetBatchKey: batchKey, resolvedAt: "2026-10-01T00:03:00Z" };
      assert.equal(body.expectedRev, revision);
      data = { action: "ISSUE_RESOLVE", replayed: true, changed: false,
        original, current: { ...original }, requestId };
    }
    await client.send("Fetch.fulfillRequest", {
      requestId: event.requestId, responseCode: 200,
      responseHeaders: [{ name: "Content-Type", value: "application/json" }, { name: "Cache-Control", value: "no-store" }],
      body: Buffer.from(JSON.stringify(data)).toString("base64"),
    });
  };
  client.on("Fetch.requestPaused", (event) => void intercept(event).catch((error) => failures.push(error)));
  try {
    await page.click(`[data-snapshot-id="${second.body.snapshotId}"]`);
    await page.select("#saved-issues-filter", "ALL");
    assert.equal(calls.length, 0, "선택·필터 변경은 자동 요청이 아님");
    await page.click("#saved-issues-first");
    await page.waitForSelector(`[data-resolution-issue="${issueKey}"]:not(:disabled)`);
    await page.click(`[data-resolution-issue="${issueKey}"]`);
    await page.type("#issue-resolution-batch", batchKey);
    await page.type("#issue-resolution-ref", "synthetic_ui_only");
    assert.equal(calls.length, 1, "입력 시 후속 근거 자동 조회 없음");
    assert.equal(await page.evaluate(() => window.resolutionInjected), undefined);
    const baseline = await page.$eval("#saved-review-current", (node) => node.textContent);
    await page.click("#issue-resolution-batch-read");
    await page.waitForFunction(() => document.getElementById("issue-resolution-batch-status").textContent.includes("관측 완료"));
    assert.equal(JSON.parse(await page.$eval("#issue-resolution-batch-detail", (node) => node.textContent)).items.length, 3);
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      const metrics = await page.$eval("#issue-resolution", (panel) => ({
        labelled: Boolean(document.getElementById(panel.getAttribute("aria-labelledby"))),
        overflow: document.documentElement.scrollWidth > innerWidth,
        controls: [...panel.querySelectorAll("button,input")].filter((node) => node.getClientRects().length)
          .map((node) => ({ width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height })),
      }));
      assert.equal(metrics.labelled, true);
      assert.equal(metrics.overflow, false, `${width}px PT-A12 가로 넘침`);
      assert.ok(metrics.controls.every((control) => control.width >= 48 && control.height >= 48));
    }
    await page.focus("#issue-resolution-submit");
    await page.keyboard.press("Enter");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    assert.equal(await page.evaluate(() => document.activeElement.id), "ui-confirm-cancel");
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.getElementById("ui-confirm-dialog"));
    assert.equal(await page.evaluate(() => document.activeElement.id), "issue-resolution-submit");
    // 이 상위 흐름은 automatic=false다. 실제 창을 기다린 뒤 직접 동의 버튼을 누른다.
    await page.click("#issue-resolution-submit");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    try {
      await page.waitForFunction(() =>
        document.getElementById("issue-resolution-receipt").dataset.state === "unknown" &&
        document.getElementById("issue-resolution").getAttribute("aria-busy") === "false");
    } catch (error) {
      console.error("PT-A12 synthetic unknown boundary", await page.evaluate(() => ({
        state: document.getElementById("issue-resolution-receipt").dataset.state,
        busy: document.getElementById("issue-resolution").getAttribute("aria-busy"),
        status: document.getElementById("issue-resolution-status").textContent,
      })), calls.map(({ path, method }) => ({ path, method })), failures.map((failure) => failure.message));
      throw error;
    }
    await page.$eval("#issue-resolution-ref", (node) => {
      node.value = "synthetic_new_buffer_B";
      node.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await page.click("#issue-resolution-replay");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    await page.waitForFunction(() =>
      document.getElementById("issue-resolution-receipt").dataset.state === "confirmed" &&
      document.getElementById("issue-resolution").getAttribute("aria-busy") === "false");
    assert.equal(await page.$eval("#issue-resolution-ref", (node) => node.value), "synthetic_new_buffer_B");
    assert.equal(await page.$eval("#saved-review-current", (node) => node.textContent), baseline);
    const posts = calls.filter((call) => call.path === path);
    assert.equal(posts.length, 2);
    const body = JSON.parse(posts[0].body);
    assert.deepEqual(Object.keys(body).sort(), ["expectedRev", "requestKey", "reasonCode", "verificationRef", "targetBatchKey", "targetReviewId"].sort());
    assert.equal(body.reasonCode, "INFRA_RECOVERED");
    assert.equal(body.targetReviewId, null);
    assert.equal(calls.length, 4, "명시 이력·상세 GET과 원래 두 POST 이외 자동 요청 없음");
    await layout(page, "issue-resolution-synthetic-transport-only");
    await page.click("#issue-resolution-discard");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    await page.waitForFunction(() => document.getElementById("issue-resolution-receipt").textContent === "");
    assert.deepEqual(failures, []);
    console.log("PASS PT-A12 UI synthetic intercepted transport only: five widths/200%, explicit detail, unknown/replay/B buffer; NOT actual resolution API, runtime or terminal PG evidence");
  } finally {
    await client.send("Fetch.disable");
    await client.detach();
  }
}

/**
 * 실제 저장 사본·서버 STRUCTURE의 열람과 검수 요청·철회·수정 요구 수명주기를 검사한다.
 * @param {object} flow 기존 HTTPS 흐름과 이 시험의 폐기형 root.
 * @throws {Error} 실제 API·원문·출처·신원 관측·이탈·배치 단언이 실패하면 그대로 전파한다.
 */
async function exerciseSavedReviewReads({
  page,
  baseUrl,
  api,
  root,
  editorUrl,
  edit,
  openSection,
  notice,
  layout,
  holdResponse,
  loseResponse,
}) {
  const fixture = await completeSavedReadFixture(page, api, root);
  await page.goto(editorUrl);
  await notice(page, "현재 원고를 조회했습니다");
  await page.type("#saved-preview-role", "A");
  const draftResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `${root}/preview` &&
      response.request().method() === "GET",
  );
  await page.click("#saved-preview-read");
  const draft = await draftResponse;
  assert.equal(draft.status(), 200);
  const draftBody = await draft.json();
  assert.equal(draftBody.source, "DRAFT");
  assert.equal(draftBody.sourceRev, fixture.detail.editRev);
  assert.equal(draftBody.snapshotId, null);
  await page.waitForFunction(
    () =>
      document.getElementById("saved-preview-status").dataset.state ===
      "success-populated",
  );
  assert.equal(
    JSON.parse(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
    ).basic.intro,
    fixture.intro,
  );
  assert.doesNotMatch(
    await page.$eval("#saved-preview-result", (node) => node.textContent),
    /SAVED_PRIVATE_|SAVED_REVEAL_ONLY/,
  );
  await edit(page, "basic", "intro", "미저장 입력은 서버 투영에 포함되지 않음");
  await page.select("#basic-intro-mode", "keep");
  await page.select("#saved-preview-mode", "REVEAL");
  assert.equal(
    await page.$eval("#saved-preview-result", (node) => node.textContent),
    "",
  );
  await page.click("#saved-preview-read");
  await page.waitForFunction(
    () =>
      document.getElementById("saved-preview-status").dataset.state ===
      "success-populated",
  );
  assert.equal(
    await page.$eval("#basic-intro", (node) => node.value),
    "미저장 입력은 서버 투영에 포함되지 않음",
  );
  await cancelFixtureInput(page, openSection, "basic", "intro", fixture.intro);

  // 실제 소유자 권한 응답과 보이는 SR02/SR07 행동으로 기존 두 사본 fixture를 만든다.
  assert.deepEqual(fixture.detail.permissions, {
    edit: true,
    review: false,
    publish: false,
  });
  await page.focus("#review-request");
  await page.keyboard.press("Enter");
  await page.waitForSelector("#ui-confirm-dialog[open]");
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "ui-confirm-cancel",
  );
  await page.keyboard.press("Escape");
  await page.waitForFunction(
    () => !document.getElementById("ui-confirm-dialog"),
  );
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "review-request",
  );
  await layout(page, "review-lifecycle-request-keyboard");
  const first = await submitLifecycleControl(
    page,
    root,
    "#review-request",
    "review-requests",
  );
  assert.equal(first.status, 201);
  assert.equal(first.sent.expectedRev, fixture.detail.editRev);
  assert.deepEqual(Object.keys(first.sent).sort(), [
    "expectedRev",
    "requestKey",
  ]);
  assert.match(
    first.sent.requestKey,
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
  await acceptLifecycleBaseline(page);
  assert.equal(
    await page.$eval("#review-changes-required", (node) => node.disabled),
    true,
  );
  await page.type("#review-withdraw-ref", "synthetic_saved_read");
  const returned = await submitLifecycleControl(
    page,
    root,
    "#review-withdraw",
    "return-to-draft",
  );
  assert.equal(returned.status, 200);
  assert.deepEqual(returned.sent, {
    expectedRev: first.body.editRev,
    expectedSnapshotId: first.body.snapshotId,
    action: "WITHDRAW",
    reasonCode: "AUTHOR_REVISION",
    verificationRef: "synthetic_saved_read",
  });
  await acceptLifecycleBaseline(page);
  const changed = await api(page, `${root}/sections/basic`, "PATCH", {
    expectedRev: returned.body.editRev,
    changes: { intro: "두 번째 합성 회차의 저장 도입" },
  });
  assert.equal(changed.status, 200);
  const checked = await api(page, `${root}/grade-samples/check`, "POST", {
    expectedRev: changed.body.editRev,
  });
  assert.equal(checked.status, 200);
  await page.click("#review-lifecycle-refresh");
  await acceptLifecycleBaseline(page);
  const second = await submitLifecycleControl(
    page,
    root,
    "#review-request",
    "review-requests",
  );
  assert.equal(second.status, 201);
  assert.equal(second.sent.expectedRev, checked.body.editRev);
  assert.notEqual(second.sent.requestKey, first.sent.requestKey);
  assert.notEqual(first.body.snapshotId, second.body.snapshotId);
  const reads = [];
  const external = [];
  /** 실제 조회 요청만 관찰하며 응답·제품 상태를 조작하지 않는다. */
  const count = (request) => {
    const url = new URL(request.url());
    if (url.hostname === "saved-read.invalid") external.push(url.href);
    if (
      request.method() === "GET" &&
      (url.pathname.startsWith(`${root}/review-snapshots`) ||
        url.pathname === `${root}/preview`)
    )
      reads.push(url);
  };
  page.on("request", count);
  try {
    await page.goto(editorUrl);
    await notice(page, "현재 버전은 읽기 전용");
    assert.equal(reads.length, 0, "진입 시 사본·근거·미리보기 prefetch 없음");
    assert.equal(
      await page.$eval("#clone-submit", (node) => node.disabled),
      true,
      "실제 REVIEW는 공개본 복제 후보가 아님",
    );
    assert.match(
      await page.$eval("#clone-target", (node) => node.textContent),
      /REVIEW.*요청 시 서버/,
    );
    const current = await page.$eval(
      "#saved-review-current",
      (node) => node.textContent,
    );
    assert.ok(
      current.includes(second.body.editRev) &&
        current.includes(second.body.snapshotId),
    );
    assert.equal(
      await page.$eval("#saved-preview-source", (node) => node.value),
      "DRAFT",
    );
    await page.select("#saved-preview-mode", "REVEAL");
    assert.equal(
      await page.$eval("#saved-preview-read", (node) => node.disabled),
      true,
      "실제 REVIEW에서는 기본 DRAFT 출처를 조회할 수 없음",
    );
    assert.equal(reads.length, 0, "알려진 부적격 DRAFT는 요청하지 않음");
    assert.equal(
      await page.$eval("#saved-history-first", (node) => node.disabled),
      false,
    );
    await page.select("#saved-preview-mode", "ROLE");
    await page.click("#saved-history-first");
    await page.waitForSelector(`[data-snapshot-id="${first.body.snapshotId}"]`);
    assert.equal(reads.length, 1);
    const ids = await page.$$eval("[data-snapshot-id]", (nodes) =>
      nodes.map((node) => node.dataset.snapshotId),
    );
    assert.deepEqual(ids, [second.body.snapshotId, first.body.snapshotId]);
    await page.click(`[data-snapshot-id="${first.body.snapshotId}"]`);
    assert.equal(reads.length, 1, "이력 선택만으로 민감 원문을 읽지 않음");
    assert.match(
      await page.$eval("#saved-review-selected", (node) => node.textContent),
      /과거 사본/,
    );
    assert.ok(
      (
        await page.$eval("#saved-review-selected", (node) => node.textContent)
      ).includes(first.body.sourceRev),
    );
    const payloadResponse = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `${root}/review-snapshots/${first.body.snapshotId}`,
    );
    await page.click("#saved-payload-read");
    const payload = await payloadResponse;
    assert.equal(payload.status(), 200);
    const original = await payload.text();
    await page.waitForFunction(
      () =>
        document.getElementById("saved-payload-status").dataset.state ===
        "success-populated",
    );
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      original,
    );
    const stored = JSON.parse(original);
    assert.equal(stored.snapshot.current, false);
    assert.equal(
      stored.payload.sections.basic.intro,
      fixture.intro,
      "현재 테이블의 두 번째 도입이 아닌 실제 고정 원문",
    );
    assert.equal(stored.payload.resources.clues[0].sourceText, "");
    assert.equal(stored.payload.resources.clues[0].personCode, null);
    assert.equal(
      stored.payload.sections.answer.methodAnswer,
      "SAVED_PRIVATE_ANSWER",
    );
    assert.equal(reads.length, 2);
    const evidenceResponse = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `${root}/review-snapshots/${first.body.snapshotId}/records`,
    );
    await page.click("#saved-records-first");
    const evidence = await evidenceResponse;
    assert.equal(evidence.status(), 200);
    const originalEvidence = await evidence.text();
    await page.waitForFunction(
      () =>
        document.getElementById("saved-records-status").dataset.state ===
        "success-populated",
    );
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      originalEvidence,
    );
    const records = JSON.parse(originalEvidence);
    assert.equal(records.current, false);
    assert.equal(records.items.length, 1);
    assert.equal(records.items[0].kind, "STRUCTURE");
    assert.equal(records.items[0].result, "PASS");
    assert.equal(records.items[0].modelId, null);
    assert.equal(records.items[0].effort, null);
    assert.equal(
      records.items[0].evidenceData.checkedRev,
      first.body.sourceRev,
    );
    assert.equal(typeof records.items[0].selfReviewYn, "boolean");
    assert.equal(reads.length, 3);
    assert.equal(
      await page.$eval("#saved-records-next", (node) => node.disabled),
      true,
    );
    assert.equal(
      await page.$$eval(
        "#saved-review img, #saved-review script, #saved-review a",
        (nodes) => nodes.length,
      ),
      0,
    );
    await layout(page, "saved-snapshot-private-history");
    for (const width of [360, 768, 900, 1200, 1440]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
        false,
      );
      await page.focus("#saved-preview-source");
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "saved-preview-mode",
      );
      assert.equal(
        await page.evaluate(
          () => getComputedStyle(document.activeElement).outlineStyle,
        ),
        "solid",
      );
    }
    await page.select("#saved-preview-source", "SNAPSHOT");
    assert.equal(
      await page.$eval("#saved-preview-read", (node) => node.disabled),
      false,
      "DRAFT 출처 제한은 실제 과거 사본 투영을 잠그지 않음",
    );
    assert.equal(
      await page.$eval("#saved-payload-result", (node) => node.textContent),
      "",
    );
    assert.equal(
      await page.$eval("#saved-records-result", (node) => node.textContent),
      "",
    );
    await page.type("#saved-preview-role", "A");
    const roleResponse = page.waitForResponse(
      (response) => new URL(response.url()).pathname === `${root}/preview`,
    );
    await page.click("#saved-preview-read");
    const role = await roleResponse;
    assert.equal(role.status(), 200);
    const roleBody = await role.json();
    await page.waitForFunction(
      () =>
        document.getElementById("saved-preview-status").dataset.state ===
        "success-populated",
    );
    assert.deepEqual(
      JSON.parse(
        await page.$eval("#saved-preview-result", (node) => node.textContent),
      ),
      roleBody.data,
    );
    assert.equal(roleBody.data.basic.intro, fixture.intro);
    assert.equal(roleBody.sourceRev, first.body.sourceRev);
    assert.equal(roleBody.snapshotId, first.body.snapshotId);
    assert.deepEqual(Object.keys(roleBody.data).sort(), [
      "basic",
      "clues",
      "persons",
      "role",
    ]);
    assert.doesNotMatch(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
      /SAVED_PRIVATE_|SAVED_REVEAL_ONLY/,
    );
    assert.equal(reads.at(-1).searchParams.has("expectedRev"), false);
    await layout(page, "saved-snapshot-role-projection");
    const held = await holdResponse(page, `${baseUrl}${root}/preview?*`);
    await page.click("#saved-preview-read");
    await held.ready;
    await page.select("#saved-preview-mode", "REVEAL");
    assert.equal(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
      "",
    );
    await held.release();
    assert.equal(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
      "",
    );
    await page.click("#saved-preview-read");
    await page.waitForFunction(
      () =>
        document.getElementById("saved-preview-status").dataset.state ===
        "success-populated",
    );
    assert.deepEqual(
      JSON.parse(
        await page.$eval("#saved-preview-result", (node) => node.textContent),
      ),
      {
        title: stored.payload.sections.basic.title,
        revealText: stored.payload.sections.reveal.revealText,
      },
    );
    assert.equal(reads.at(-1).searchParams.has("roleCode"), false);
    const beforeSwitch = reads.length;
    await page.click(`[data-snapshot-id="${second.body.snapshotId}"]`);
    assert.equal(reads.length, beforeSwitch);
    assert.equal(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
      "",
    );
    assert.match(
      await page.$eval("#saved-review-selected", (node) => node.textContent),
      /현재 사본/,
    );
    await page.click("#saved-preview-read");
    await page.waitForFunction(
      () =>
        document.getElementById("saved-preview-status").dataset.state ===
        "success-populated",
    );
    assert.ok(
      (
        await page.$eval("#saved-preview-status", (node) => node.textContent)
      ).includes(second.body.sourceRev),
    );
    const lost = await loseResponse(
      page,
      `${baseUrl}${root}/review-snapshots/${second.body.snapshotId}/records?*`,
    );
    const beforeLoss = reads.length;
    await page.click("#saved-records-first");
    await page.waitForFunction(
      () =>
        document.getElementById("saved-records-status").dataset.state ===
        "failure",
    );
    await lost.detach();
    assert.equal(
      reads.length,
      beforeLoss + 1,
      "실제 근거 응답 유실 뒤 자동 재시도 없음",
    );
    assert.equal(
      await page.$eval("#saved-preview-result", (node) => node.textContent),
      "",
    );
    assert.equal(
      await page.$eval("#saved-review-selected", (node) => node.textContent),
      "",
    );
    assert.equal(
      await page.evaluate(() => window.savedReadInjected),
      undefined,
    );
    assert.equal(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
      0,
    );
    assert.deepEqual(external, []);
    console.log(
      "PASS HTTPS saved-review reads: real immutable snapshots/server STRUCTURE; explicit SR03/04/06/08, provenance, literal private JSON, projection, transport loss; not model quality or publication",
    );
    // 같은 합성 사건에 실제 현재 전역 REVIEW와 사건 REVIEW를 결합한다. DB나 응답을 꾸미지 않는다.
    const me = await api(page, "/admin/api/auth/me");
    assert.equal(me.status, 200);
    assert.ok(me.body.permissions.includes("REVIEW"));
    const beforeReturn = await api(page, root);
    assert.equal(beforeReturn.status, 200);
    assert.equal(beforeReturn.body.permissions.review, false);
    const granted = await api(
      page,
      `${root.split("/versions/")[0]}/access/grant`,
      "POST",
      {
        expectedStoryRev: beforeReturn.body.storyRev,
        accountKey: me.body.accountKey,
        permission: "REVIEW",
        reasonCode: "ASSIGNMENT_CHANGE",
        verificationRef: "synthetic_lifecycle_review",
      },
    );
    assert.equal(granted.status, 200);
    const qualified = await api(page, root);
    assert.equal(qualified.status, 200);
    assert.deepEqual(qualified.body.permissions, {
      edit: true,
      review: true,
      publish: false,
    });
    await page.click("#review-lifecycle-refresh");
    await acceptLifecycleBaseline(page);
    await page.click("#saved-history-first");
    await page.waitForSelector(`[data-snapshot-id="${first.body.snapshotId}"]`);
    await exerciseExecutionIssueHistory({
      page,
      root,
      first,
      second,
      baseUrl,
      loseResponse,
      layout,
    });
    await exerciseIssueResolutionUi({
      page, root, second, revision: qualified.body.editRev, layout,
    });
    const manualApproval = await exerciseManualRecords({
      page,
      root,
      second,
      baseUrl,
      loseResponse,
      layout,
    });
    const afterManual = await api(page, root);
    assert.equal(afterManual.status, 200);
    assert.equal(afterManual.body.editRev, qualified.body.editRev);
    assert.equal(afterManual.body.status, "REVIEW");
    assert.equal(afterManual.body.currentSnapshotId, second.body.snapshotId);
    assert.deepEqual(afterManual.body.sections, qualified.body.sections);
    await page.click(`[data-snapshot-id="${first.body.snapshotId}"]`);
    await page.select("#review-changes-reason", "FAIRNESS_ISSUE");
    await page.type("#review-changes-ref", "synthetic_changes_required");
    await page.focus("#review-changes-reason");
    await page.keyboard.press("Tab");
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "review-changes-ref",
    );
    await layout(page, "review-lifecycle-qualified-return");
    const requestedChanges = await submitLifecycleControl(
      page,
      root,
      "#review-changes-required",
      "return-to-draft",
    );
    assert.equal(requestedChanges.status, 200);
    assert.deepEqual(requestedChanges.sent, {
      expectedRev: qualified.body.editRev,
      expectedSnapshotId: second.body.snapshotId,
      action: "CHANGES_REQUIRED",
      reasonCode: "FAIRNESS_ISSUE",
      verificationRef: "synthetic_changes_required",
    });
    assert.equal(requestedChanges.body.status, "DRAFT");
    assert.equal(requestedChanges.body.currentSnapshotId, null);
    const afterReturn = await api(page, root);
    assert.equal(afterReturn.status, 200);
    assert.deepEqual(afterReturn.body.sections, beforeReturn.body.sections);
    const snapshots = await api(page, `${root}/review-snapshots?size=20`);
    assert.equal(snapshots.status, 200);
    assert.deepEqual(
      snapshots.body.items.map((item) => item.snapshotId),
      [second.body.snapshotId, first.body.snapshotId],
    );
    assert.ok(snapshots.body.items.every((item) => item.current === false));
    await acceptLifecycleBaseline(page);
    const historical = await submitManualControl(
      page,
      `${root}/review-snapshots/${second.body.snapshotId}/records`,
      "#manual-reconfirm",
    );
    assert.equal(historical.status, 200);
    assert.equal(historical.sent, manualApproval.sent);
    assert.equal(historical.body.recordId, manualApproval.body.recordId);
    assert.equal(historical.body.current, false);
    assert.equal(historical.body.replayed, true);
    assert.equal(
      await page.$eval("#manual-submit", (node) => node.disabled),
      true,
    );
    // 확정·수락된 반환 필드를 남겨도 영구 경고하지 않음을 실제 링크 탐색으로 확인한다.
    await navigateLifecycleLeave(page, false);
    await page.goto(editorUrl);
    await notice(page, "현재 원고를 조회했습니다");
    await page.type(
      "#manual-evidence",
      "Native leave: new unsent manual evidence.",
    );
    await navigateLifecycleLeave(page, true);
    await page.goto(editorUrl);
    await notice(page, "현재 원고를 조회했습니다");
    assert.equal(
      await page.$eval("#manual-evidence", (node) => node.value),
      "",
    );
    await page.type("#review-withdraw-ref", "native_leave_dirty_reference");
    await navigateLifecycleLeave(page, true);
    await page.goto(editorUrl);
    await notice(page, "현재 원고를 조회했습니다");
    assert.equal(
      await page.$eval("#review-withdraw-ref", (node) => node.value),
      "",
      "네이티브 이탈 승인으로 폐기한 반환 입력은 복원하지 않음",
    );

    // 실제 Me 응답의 전송만 유실시킨다. 가짜 행위자·권한 응답이나 새 사건을 만들지 않는다.
    await edit(page, "basic", "intro", "HTTPS_IDENTITY_KEEP_BUFFER");
    await page.select("#basic-intro-mode", "keep");
    await page.type("#review-changes-ref", "https_identity_reference");
    const mutations = [];
    /** 신원 실패 경계에서 업무 변경 전송이 생기지 않는지 관찰한다. */
    const mutation = (request) => {
      if (
        new URL(request.url()).pathname.startsWith(root) &&
        request.method() !== "GET"
      )
        mutations.push(request.method());
    };
    page.on("request", mutation);
    const failedIdentity = await loseResponse(
      page,
      `${baseUrl}/admin/api/auth/me`,
    );
    try {
      await page.click("#review-lifecycle-refresh");
      await page.waitForSelector("#editor[hidden]");
      assert.equal(
        await page.$eval("#editor", (node) => node.childElementCount),
        0,
      );
      assert.equal(await page.$("#review-changes-ref"), null);
      assert.equal(await page.$("#basic-intro"), null);
      assert.deepEqual(mutations, []);
    } finally {
      await failedIdentity.detach();
      page.off("request", mutation);
    }
    console.log(
      "PASS HTTPS lifecycle UI: real SR02 UUID/body, owner WITHDRAW, qualified same-story CHANGES_REQUIRED, current pointer not selected history; real Me boundaries/fail-closed transport loss, native dirty/clean link navigation; existing two snapshots only, no cross-actor session attestation or quality claim",
    );
  } finally {
    page.off("request", count);
  }
}

/**
 * 실제 HTTPS/MFA·CSRF·저장 서비스로 SR01→열람→SR09→새 SR01을 실행한다.
 * 응답 보류/유실만 CDP 전송 시뮬레이션이며 서버의 응답 내용은 조작하지 않는다.
 * 기존 검사 뒤 저장 사본·서버 STRUCTURE의 명시적 열람 분기도 소비한다.
 * @param {object} flow 기존 폐기형 workflow의 페이지·API·배치·확인·전송 헬퍼.
 * @throws {Error} 원래 검사 또는 추가 저장 자료 검사의 실제 API·DOM 단언 실패.
 */
export async function exerciseReviewChecks({
  page,
  baseUrl,
  api,
  edit,
  openSection,
  notice,
  layout,
  confirmation,
  holdResponse,
  loseResponse,
}) {
  const previousUrl = page.url();
  const automatic = confirmation.automatic;
  const root = await createCheckFixture(page, api);
  const editorUrl = `${baseUrl}${root.replace("/admin/api/stories/", "/admin/stories/")}`;
  const posts = [];
  const clonePosts = [];
  const count = (request) => {
    const path = new URL(request.url()).pathname;
    if (
      request.method() === "POST" &&
      path === `${root.replace(/\/versions\/[1-9][0-9]*$/, "")}/drafts`
    )
      clonePosts.push(request);
    if (
      request.method() === "POST" &&
      [root + "/review-precheck", root + "/grade-samples/check"].includes(path)
    )
      posts.push({
        path,
        body: JSON.parse(request.postData()),
        headers: request.headers(),
      });
  };
  page.on("request", count);
  try {
    await page.goto(editorUrl);
    await notice(page, "현재 원고를 조회했습니다");
    assert.equal(
      await page.$eval("#clone-submit", (node) => node.disabled),
      true,
      "실제 DRAFT는 공개본 복제 후보가 아님",
    );
    assert.match(
      await page.$eval("#clone-target", (node) => node.textContent),
      /DRAFT.*요청 시 서버/,
    );
    const before = (await api(page, root)).body.editRev;
    const response = page.waitForResponse(
      (value) =>
        value.url() === `${baseUrl}${root}/review-precheck` &&
        value.request().method() === "POST",
    );
    await page.click("#review-precheck");
    const checked = await response;
    assert.equal(checked.status(), 200);
    const result = await checked.json();
    await reviewMessage(page, "구조 진단입니다");
    assert.equal(result.editRev, before);
    assert.equal(
      result.eligible,
      false,
      "구조 미완성 초안은 200이어도 통과 아님",
    );
    assert.ok(result.errorCount > 0);
    assert.deepEqual(posts[0].body, { expectedRev: before });
    const displayed = await page.$eval(
      "#review-check-diagnostics",
      (node) => node.textContent,
    );
    assert.ok(displayed.includes(`차단 오류 ${result.errorCount}건`));
    assert.ok(displayed.includes(`경고 ${result.warningCount}건`));
    assert.match(displayed, /품질 통과 아님/);
    // 기존 layout은 다섯 폭과 확장 프로그램의 실제 Chrome 200% 확대·캡처를 그대로 수행한다.
    await layout(page, "saved-draft-precheck");
    await page.click("#review-samples-open");
    await page.waitForSelector('[data-child-key="FULL"]');
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "child-resource",
    );
    await clickWithConfirmation(page, '[data-child-key="FULL"]');
    await page.waitForFunction(
      () => !document.getElementById("child-resource").disabled,
    );
    assert.match(
      await page.$eval("#child-reason-record", (node) => node.textContent),
      /합성 근거/,
    );

    const countBeforeDirty = posts.length;
    for (const [section, key] of [
      ["basic", "intro"],
      ["answer", "methodAnswer"],
      ["reveal", "revealText"],
      ["child", "reason"],
    ]) {
      const original = await page.$eval(
        `#${section}-${key}`,
        (node) => node.value,
      );
      await edit(page, section, key, "아직 저장하지 않은 확인 시험 입력");
      for (const mode of ["value", "clear", "keep"]) {
        await page.select(`#${section}-${key}-mode`, mode);
        assert.equal(
          await page.$eval("#review-precheck", (node) => node.disabled),
          true,
        );
        assert.equal(
          await page.$eval("#review-samples-check", (node) => node.disabled),
          true,
        );
        await page.evaluate(() => {
          for (const id of ["review-precheck", "review-samples-check"])
            document.getElementById(id).dispatchEvent(new Event("click"));
        });
      }
      await cancelFixtureInput(page, openSection, section, key, original);
    }
    assert.equal(
      posts.length,
      countBeforeDirty,
      "모든 영역 미저장 시 POST 없음",
    );

    // 공개 조회 버튼의 활성 상태를 취소 후 작업 해제 기준으로 사용한다.
    await page.waitForSelector("#review-lifecycle-refresh:enabled", {
      visible: true,
    });
    await page.waitForSelector("#review-samples-check:enabled", {
      visible: true,
    });

    confirmation.automatic = false;
    for (const escape of [false, true]) {
      await page.focus("#review-samples-check");
      await page.keyboard.press("Enter");
      await page.waitForSelector("#ui-confirm-dialog[open]");
      assert.equal(
        await page.$eval("#review-lifecycle-refresh", (node) => node.disabled),
        true,
      );
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-accept",
      );
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "ui-confirm-cancel",
      );
      if (escape) await page.keyboard.press("Escape");
      else await page.click("#ui-confirm-cancel");
      await page.waitForFunction(
        () => !document.getElementById("ui-confirm-dialog"),
      );
      assert.equal(
        await page.evaluate(() => document.activeElement.id),
        "review-samples-check",
      );
      await page.waitForSelector("#review-lifecycle-refresh:enabled", {
        visible: true,
      });
    }
    assert.equal(posts.length, countBeforeDirty);
    await page.click("#review-samples-check");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-cancel");
    await page.waitForFunction(
      () => !document.getElementById("ui-confirm-dialog"),
    );
    await page.waitForSelector("#review-lifecycle-refresh:enabled", {
      visible: true,
    });
    await openSection(page, "basic");
    await page.select("#basic-intro-mode", "clear");
    assert.equal(
      await page.$eval("#review-samples-check", (node) => node.disabled),
      true,
    );
    await cancelFixtureInput(page, openSection, "basic", "intro", "");
    assert.equal(posts.length, countBeforeDirty);

    const held = await holdResponse(
      page,
      `${baseUrl}${root}/grade-samples/check`,
    );
    await page.click("#review-samples-check");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    await held.ready;
    // 모달이 닫힌 뒤 비활성 진입점의 합성 음성 검사다. 사용자 클릭 증거가 아니다.
    await page.evaluate(() =>
      document
        .getElementById("review-samples-check")
        .dispatchEvent(new Event("click")),
    );
    assert.equal(
      posts.length,
      countBeforeDirty + 1,
      "동의·중복 클릭은 단 한 번 전송",
    );
    assert.deepEqual(posts.at(-1).body, { expectedRev: before });
    assert.ok(
      Object.keys(posts.at(-1).headers).some((key) => /csrf/i.test(key)),
    );
    const receiptRevision = (await api(page, root)).body.editRev;
    assert.notEqual(receiptRevision, before);
    await edit(page, "basic", "intro", "사람 확인 전송 중 보존할 입력");
    await held.release();
    await page.waitForFunction(
      (revision) =>
        document
          .getElementById("version-summary")
          .textContent.includes(revision),
      {},
      receiptRevision,
    );
    assert.equal(
      await page.$eval("#basic-intro", (node) => node.value),
      "사람 확인 전송 중 보존할 입력",
    );
    assert.match(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      /확인 4건 · 변경 있음/,
    );
    assert.equal(
      await page.$eval(
        "#review-check-diagnostics",
        (node) => node.childElementCount,
      ),
      0,
    );
    assert.equal(
      posts.length,
      countBeforeDirty + 1,
      "영수증 뒤 자동 SR01 없음",
    );
    const saved = await api(page, `${root}/grade-samples/FULL`);
    assert.equal(typeof saved.body.item.checkedBy, "string");
    await cancelFixtureInput(page, openSection, "basic", "intro", "");
    await page.click("#review-precheck");
    await reviewMessage(page, "구조 진단입니다");
    assert.deepEqual(posts.at(-1).body, { expectedRev: receiptRevision });

    // 같은 행위자의 무변경 성공 응답을 실제 서버 처리 뒤 유실시킨다. UI는 성공을 추정하지 않는다.
    const lost = await loseResponse(
      page,
      `${baseUrl}${root}/grade-samples/check`,
    );
    await page.click("#review-samples-check");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    await reviewMessage(page, "결과 미확인");
    await lost.detach();
    const sent = posts.length;
    const failedGet = await loseResponse(page, `${baseUrl}${root}`);
    await page.click("#review-check-refresh");
    await reviewMessage(page, "조회 실패");
    await failedGet.detach();
    assert.equal(posts.length, sent);
    await page.click("#review-check-refresh");
    await page.waitForFunction(
      () => !document.getElementById("accept-latest").disabled,
    );
    assert.match(
      await page.$eval("#review-check-receipt", (node) => node.textContent),
      /결과 미확인/,
    );
    await page.click("#accept-latest");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.click("#ui-confirm-accept");
    await page.waitForFunction(
      () => !document.getElementById("review-samples-check").disabled,
    );
    assert.equal(posts.length, sent, "조회·비교는 SR09를 재전송하지 않음");
    await page.click("#review-samples-check");
    await page.waitForSelector("#ui-confirm-dialog[open]");
    await page.keyboard.press("Escape");
    assert.equal(posts.length, sent);
    await page.waitForFunction(
      () => !document.getElementById("ui-confirm-dialog"),
    );
    await page.waitForSelector("#review-lifecycle-refresh:enabled", {
      visible: true,
    });
    assert.equal(
      (await api(page, root)).body.status,
      "DRAFT",
      "검수 상태 전환 없음",
    );
    console.log(
      "PASS HTTPS saved-draft checks: real SR01/SR09, dirty guards, consent, receipt preservation; CDP transport loss/failed GET only",
    );
    await exerciseSavedReviewReads({
      page,
      baseUrl,
      api,
      root,
      editorUrl,
      edit,
      openSection,
      notice,
      layout,
      holdResponse,
      loseResponse,
    });
    assert.equal(clonePosts.length, 0, "실제 DRAFT/REVIEW에서 복제 POST 없음");
  } finally {
    confirmation.automatic = automatic;
    page.off("request", count);
    await page.goto(previousUrl);
    await notice(page, "현재 원고를 조회했습니다");
  }
}
