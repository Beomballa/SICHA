(() => {
  "use strict";
  const api = "/admin/api/stories";
  const notice = document.getElementById("notice");
  const editor = document.getElementById("editor");
  let editorFragmentApplied = false;
  const versionStates = {
    DRAFT: "초안",
    REVIEW: "검수 중",
    READY: "공개 가능",
    PUBLISHED: "공개",
  };
  const childTypes = {
    persons: {
      label: "인물",
      keys: ["code"],
      required: ["code", "name"],
      guide:
        "코드는 영문 대문자·숫자·밑줄 1~32자이며 생성 뒤 고정됩니다. 이름은 80자, 공개 소개·비밀 원고는 각각 8,000자까지 작성할 수 있습니다.",
      fields: [
        ["code", "인물 코드", "text", false],
        ["name", "인물 이름", "text", false],
        ["publicText", "플레이어용 공개 소개", "textarea", true],
        ["secretText", "제작자 전용 비밀 원고", "textarea", true],
      ],
    },
    roles: {
      label: "역할",
      keys: ["code"],
      required: ["code", "name"],
      guide:
        "역할은 조사 관점이며 인물과 일대일 대응하지 않습니다. 코드는 영문 대문자·숫자·밑줄 1~32자, 이름은 80자, 소개는 4,000자까지입니다.",
      fields: [
        ["code", "역할 코드", "text", false],
        ["name", "역할 이름", "text", false],
        ["brief", "역할 소개", "textarea", true],
      ],
    },
    pairs: {
      label: "역할 조합",
      keys: ["roleA", "roleB"],
      required: ["roleA", "roleB"],
      guide:
        "같은 버전의 서로 다른 활성 역할 코드 둘을 입력하세요. 화면에 표시한 ASCII 정순으로 생성하며 이후 키는 변경할 수 없습니다. 활성 조합은 검수 후보이지 검수 통과가 아닙니다.",
      fields: [
        ["roleA", "첫 번째 역할 코드", "text", false],
        ["roleB", "두 번째 역할 코드", "text", false],
      ],
    },
    clues: {
      label: "단서",
      keys: ["code"],
      required: ["code", "title"],
      options: {
        scope: [
          ["", "공개 범위 선택"],
          ["ROLE", "ROLE · 지정 역할에게 공개"],
          ["COMMON", "COMMON · 모든 역할에게 공개"],
        ],
      },
      guide:
        "코드와 제목은 필수입니다. 본문은 플레이어에게 표시되며 12,000자까지, 제작자용 출처 메모는 400자까지입니다. ROLE 단서는 배정이 없어도 초안으로 저장할 수 있습니다.",
      fields: [
        ["code", "단서 코드", "text", false],
        ["title", "플레이어용 단서 제목", "text", false],
        ["body", "플레이어용 단서 본문", "textarea", true],
        ["personCode", "관련 활성 인물 코드", "text", true],
        ["scope", "공개 범위 (COMMON 공통 / ROLE 역할별)", "select", false],
        [
          "sourceText",
          "제작자용 출처 메모 (플레이어 비공개)",
          "textarea",
          true,
        ],
      ],
    },
    "clue-roles": {
      label: "단서 역할 배정",
      keys: ["clueCode", "roleCode"],
      required: ["clueCode", "roleCode"],
      guide:
        "같은 버전의 활성 ROLE 단서와 활성 역할을 연결합니다. 단서 코드~역할 코드 순서로 저장하며 같은 문자열이어도 서로 다른 종류의 자료입니다. 기존 배정은 논리 삭제·복원만 가능합니다.",
      fields: [
        ["clueCode", "활성 ROLE 단서 코드", "text", false],
        ["roleCode", "활성 역할 코드", "text", false],
      ],
    },
    hints: {
      label: "힌트",
      keys: ["code"],
      required: ["code", "level"],
      guide:
        "코드는 생성 뒤 고정됩니다. 단계는 1~3의 정수이며 원고는 선택 입력으로 4,000자까지입니다. 논리 삭제된 힌트도 단계와 코드를 예약하므로 기존 행을 복원·수정하세요.",
      fields: [
        ["code", "힌트 코드", "text", false],
        ["level", "힌트 단계 (1~3)", "number", false],
        ["body", "힌트 원고", "textarea", true],
      ],
    },
    events: {
      label: "시간선",
      keys: ["code"],
      required: ["code"],
      guide:
        "코드는 생성 뒤 고정됩니다. 시작·종료는 이야기 기준점 이후 0~2147483647분이며 미정 시 비울 수 있습니다. 종료만 지정하거나 시작보다 이른 종료는 저장할 수 없습니다. 실제·표면 원고는 제작자 전용으로 각각 8,000자까지입니다.",
      fields: [
        ["code", "시간선 코드", "text", false],
        ["startMin", "시작 (기준점 이후 분)", "number", true],
        ["endMin", "종료 (기준점 이후 분)", "number", true],
        ["actualText", "제작자용 실제 사건", "textarea", true],
        ["apparentText", "제작자용 표면 사건·오해", "textarea", true],
      ],
    },
    facts: {
      label: "사실 원장",
      keys: ["code"],
      required: ["code"],
      guide:
        "코드는 생성 뒤 고정됩니다. 명제는 4,000자, 근거는 8,000자까지이며 모두 제작자 전용입니다. 분류는 참·거짓·오해 또는 미정입니다. 근거의 단서 언급은 문서 기록이며 자동 연결·수정되지 않습니다.",
      options: {
        truth: [
          ["", "분류 선택"],
          ["TRUE", "TRUE · 참"],
          ["FALSE", "FALSE · 거짓"],
          ["MISREAD", "MISREAD · 오해"],
        ],
      },
      fields: [
        ["code", "사실 코드", "text", false],
        ["statement", "제작자용 명제", "textarea", true],
        ["truth", "명제 분류", "select", true],
        ["basis", "제작자용 근거·단서 언급", "textarea", true],
      ],
    },
    rubrics: {
      label: "채점 소항목",
      keys: ["code"],
      required: ["code", "category"],
      guide:
        "분류와 코드는 필수입니다. 점수는 0~100의 정수 또는 미정이며, 통과 점수는 필수 여부와 최대 점수가 지정된 경우에만 설정합니다. 범인 분류를 만들면 최대·통과 25점, 필수 여부와 규칙이 서버에서 고정됩니다. 범인 분류를 떠나려면 규칙을 명시적으로 교체하거나 비워야 합니다. 규칙은 JSON 객체이며 128 KiB까지 허용됩니다. 전체 규칙의 의미와 참조는 서버가 검증합니다.",
      options: {
        category: [
          ["", "분류 선택"],
          ["CULPRIT", "CULPRIT · 범인"],
          ["METHOD", "METHOD · 수법"],
          ["TIME", "TIME · 시간"],
          ["MOTIVE", "MOTIVE · 동기"],
          ["EVIDENCE", "EVIDENCE · 근거"],
        ],
        requiredYn: [
          ["false", "아니요"],
          ["true", "예"],
        ],
      },
      fields: [
        ["code", "소항목 코드", "text", false],
        ["category", "분류", "select", false],
        ["maxScore", "최대 점수 (0~100)", "number", true],
        ["requiredYn", "필수 여부", "boolean", false],
        ["passScore", "통과 점수 (0~100)", "number", true],
        ["acceptedText", "정답 안내", "textarea", true],
        ["partialText", "부분 정답 안내", "textarea", true],
        ["rejectText", "오답 안내", "textarea", true],
        ["ruleData", "구조화 채점 규칙 JSON", "json", true],
      ],
    },
    "rubric-clues": {
      label: "소항목 단서 연결",
      keys: ["rubricCode", "clueCode"],
      required: ["rubricCode", "clueCode"],
      guide:
        "같은 버전의 활성 소항목과 활성 단서를 연결합니다. 소항목 코드~단서 코드의 의미 순서를 유지합니다. 연결 설명은 생성 후에도 수정하거나 비울 수 있습니다.",
      fields: [
        ["rubricCode", "활성 소항목 코드", "text", false],
        ["clueCode", "활성 단서 코드", "text", false],
        ["linkText", "제작자용 연결 설명", "textarea", true],
      ],
    },
    "grade-samples": {
      label: "판정 검증 예시",
      keys: ["code"],
      required: ["code"],
      guide:
        "코드는 생성 뒤 고정됩니다. 실제 제출할 전체 입력 JSON과 기대 결과 JSON을 별도로 작성하세요. 입력은 128 KiB, 기대값은 64 KiB, 설명은 8,000자까지입니다. 이 화면의 저장은 사람 확인이나 판정 실행이 아니며 원고 변경 시 이전 확인은 무효화됩니다. 입력 오류 예시는 잘못된 보고서도 그대로 기록할 수 있습니다.",
      options: {
        expectedSuccess: [
          ["false", "실패"],
          ["true", "성공"],
        ],
      },
      fields: [
        ["code", "예시 코드", "text", false],
        ["inputData", "제출 입력 JSON", "json", true],
        ["expectData", "기대 결과 JSON", "json", true],
        ["expectedScore", "기대 점수 (0~100)", "number", true],
        ["expectedSuccess", "기대 성공 여부", "boolean", true],
        ["reason", "사람 확인을 위한 근거", "textarea", true],
      ],
    },
  };
  const fields = {
    basic: [
      ["title", "사건명", "text", false],
      ["intro", "도입", "textarea", true],
      ["setting", "배경", "textarea", true],
      ["difficulty", "난이도", "number", true],
      ["limitSec", "제한 시간 (초)", "number", true],
      ["estMin", "예상 시간 (분)", "number", true],
      ["estMax", "최대 시간 (분)", "number", true],
      ["timelineOrigin", "시간선 기준", "text", true],
    ],
    answer: [
      ["culpritCode", "범인 코드", "text", true],
      ["methodAnswer", "수법 정답", "textarea", true],
      ["timeAnswer", "시간 정답", "textarea", true],
      ["motiveAnswer", "동기 정답", "textarea", true],
    ],
    reveal: [["revealText", "종료 후 해설", "textarea", true]],
    get child() {
      return childTypes[childResource].fields;
    },
  };
  const maxLength = {
    title: 160,
    intro: 12000,
    setting: 4000,
    timelineOrigin: 120,
    culpritCode: 32,
    methodAnswer: 12000,
    timeAnswer: 8000,
    motiveAnswer: 8000,
    revealText: 20000,
    code: 32,
    name: 80,
    publicText: 8000,
    secretText: 8000,
    brief: 4000,
    roleA: 32,
    roleB: 32,
    body: 12000,
    personCode: 32,
    sourceText: 400,
    clueCode: 32,
    roleCode: 32,
    actualText: 8000,
    apparentText: 8000,
    statement: 4000,
    basis: 8000,
    rubricCode: 32,
    acceptedText: 12000,
    partialText: 12000,
    rejectText: 8000,
    linkText: 4000,
    reason: 8000,
  };

  /** 자원에 따라 공통 필드명의 원고 길이 계약을 구분한다. */
  function fieldLimit(section, key) {
    return section === "child" && childResource === "hints" && key === "body"
      ? 4000
      : maxLength[key];
  }

  /** 숫자 항목의 동일한 상한을 입력 속성과 저장 검사에 제공한다. */
  function numberLimit(section, key) {
    if (
      section === "child" &&
      ["rubrics", "grade-samples"].includes(childResource)
    )
      return 100;
    if (section === "child" && childResource === "hints" && key === "level")
      return 3;
    if (section === "child" && childResource === "events") return 2147483647;
    return key === "difficulty" ? 5 : key === "limitSec" ? 2147483647 : 32767;
  }

  /** 시간선의 기준점 0분과 다른 숫자 필드의 양수 계약을 구분한다. */
  function numberMinimum(section) {
    return section === "child" &&
      ["events", "rubrics", "grade-samples"].includes(childResource)
      ? 0
      : 1;
  }
  let csrf;
  let generation = 0;
  let comparisonRequest = 0;
  let listRequest = 0;
  let filters = { activeYn: "true" };
  let afterId;
  let createKey;
  let createTitle;
  let detail;
  let latest;
  let saveLocked = false;
  let childResource = "persons";
  let childKey;
  let childEditing = false;
  let childRequest = 0;
  let childAfter;
  let viewer;
  let selectedStory;
  let manageSnapshot;
  let manageAfterKey;
  let manageEpoch = 0;
  let ownershipStory;
  let ownershipSnapshot;
  let ownershipIntent;
  let ownershipEpoch = 0;
  let reviewInputEpoch = 0;
  let reviewOperation;
  let reviewNeedsRead = false;
  let inputBaselines = new WeakMap();
  let savedSelection;
  let savedHistoryCursor;
  let savedRecordsCursor;
  let savedIssuesPage;
  let savedReadEpoch = 0;
  let savedReads = {};
  let savedDetailPending = false;
  let savedCurrent;
  let savedMetadataOperation;
  let permissionEpoch = 0;
  let observedPermissions;
  let permissionDenied = { edit: false, review: false, publish: false };
  let lifecycleOperation;
  let lifecycleIntent;
  let lifecycleNeedsRead = false;
  let unknownReturnSnapshot;
  let lifecycleComparison = false;
  let lifecycleSourceEpoch = 0;
  let editorIdentity;
  let editorIdentityRevoked = false;
  let acknowledgedReturnFields = {};
  let manualBaseline;
  let manualIntent;
  let manualOperation;
  let manualAcknowledged = "";
  let manualInputEpoch = 0;
  let manualDraftSnapshot;
  let cloneCandidate;
  let cloneIntent;
  let cloneOperation;
  let cloneReceipt;
  let cloneState = "idle";
  let cloneSent = false;
  let resolutionSource;
  let resolutionIntent;
  let resolutionOperation;
  let resolutionInputEpoch = 0;
  let resolutionAcknowledged = '["","",""]';

  /**
   * 소문자 정규 UUID v4만 경로·요청·영수증 식별자로 허용한다.
   * @param {unknown} value null·비문자열·대문자·다른 UUID 버전은 거절한다.
   * @returns {boolean} 정규 식별자 여부.
   */
  function resolutionUuid(value) {
    return typeof value === "string" &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value);
  }

  /**
   * 명시 시차와 실제 달력이 있는 서버 시각만 허용하며 값을 보정하지 않는다.
   * @param {unknown} value 필수 ISO 시각 문자열이며 nullable 필드는 호출자가 별도 검사한다.
   * @returns {boolean} 실제 달력·시차를 확인한 문자열 여부.
   */
  function resolutionTime(value) {
    return typeof value === "string" &&
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
      Number.isFinite(Date.parse(value)) &&
      new Date(`${value.slice(0, 19)}Z`).toISOString().slice(0, 19) === value.slice(0, 19);
  }

  /** 선택과 두 비개인 입력의 메모리 비교값만 반환한다. 저장소에는 기록하지 않는다. */
  function resolutionFingerprint() {
    return JSON.stringify([
      resolutionSource?.issueKey || "",
      document.getElementById("issue-resolution-batch")?.value || "",
      document.getElementById("issue-resolution-ref")?.value || "",
    ]);
  }

  /** 입력 B와 결과 미확인 A를 독립적으로 이탈 보호한다. 확정 거절도 명시 폐기 전까지 보존한다. */
  function resolutionProtected() {
    return Boolean(resolutionOperation ||
      (resolutionIntent && !resolutionIntent.receipt) ||
      resolutionFingerprint() !== resolutionAcknowledged);
  }

  /** 다른 변경 요청과 겹치지 않게 하되 각 흐름의 보관 의도는 변경하지 않는다. */
  function resolutionOtherBusy() {
    return Boolean(reviewOperation || lifecycleOperation || manualOperation ||
      cloneOperation || savedMetadataOperation || savedDetailPending);
  }

  /** 같은 문서 신원·경로를 확인하며 로컬 명시 폐기에는 쓰기 권한을 요구하지 않는다. */
  function resolutionContext() {
    return Boolean(detail && !editor.hidden && editorIdentity && !editorIdentityRevoked &&
      document.getElementById("unavailable").hidden &&
      location.pathname === `/admin/stories${editorPath()}` &&
      editorPath() === `/${detail.storyCode}/versions/${detail.versionNo}`);
  }

  /** 재생은 같은 신원·경로의 현재 REVIEW 권한만 요구하며 과거 상태·runtime을 요구하지 않는다. */
  function resolutionAuthority() {
    return Boolean(resolutionContext() &&
      observedPermissions?.review === true && !resolutionOtherBusy());
  }

  /**
   * 새 해소만 현재 쓰기 상세·선택 사본·실제 관측 권한에 결속한다. 열람 메타로 쓰기 기준을 교체하지 않는다.
   * @param {object|undefined} source 기본은 선택된 지적이며 목록 버튼은 그 행의 캡처를 전달한다.
   * @returns {boolean} 새 요청의 로컬 후보 여부이며 서버 인가·해소 적격을 보증하지 않는다.
   */
  function resolutionEligible(source = resolutionSource) {
    const read = savedReadMetadata();
    return Boolean(resolutionAuthority() && hasPermission("review") &&
      !saveLocked && !reviewNeedsRead && !lifecycleNeedsRead &&
      document.getElementById("comparison").hidden &&
      detail.status === "REVIEW" && detail.storyActiveYn === true && detail.activeYn === true &&
      read?.status === "REVIEW" && read.storyActiveYn === true && read.activeYn === true &&
      read.editRev === detail.editRev &&
      read.currentSnapshotId === detail.currentSnapshotId &&
      cloneDecimal(detail.editRev) && cloneVersion(detail.versionNo) &&
      savedIssueDecimal(detail.currentSnapshotId) &&
      savedSelection?.snapshotId === detail.currentSnapshotId &&
      source?.snapshotId === detail.currentSnapshotId &&
      source.path === editorPath() && source.identity === editorIdentity &&
      source.state === "OPEN" && ["INFRA", "GRADING"].includes(source.kind) &&
      source.sourceReviewId === null && resolutionUuid(source.issueKey) &&
      resolutionUuid(source.sourceBatchKey));
  }

  /** 제어만 갱신하며 입력·영수증·필터·커서는 그대로 보존한다. */
  function syncResolutionControls() {
    const panel = document.getElementById("issue-resolution");
    if (!panel) return;
    const busy = Boolean(resolutionOperation);
    panel.setAttribute("aria-busy", String(busy));
    document.getElementById("issue-resolution-submit").disabled =
      !resolutionEligible() || Boolean(resolutionIntent) ||
      (busy && (resolutionOperation.kind !== "write" || resolutionOperation.sent || resolutionOperation.replay));
    const replay = document.getElementById("issue-resolution-replay");
    replay.hidden = !resolutionIntent || resolutionIntent.state === "rejected";
    replay.disabled = !resolutionAuthority() || !resolutionIntent ||
      resolutionIntent.identity !== editorIdentity || resolutionIntent.editorPath !== editorPath() ||
      (busy && (!resolutionOperation.replay || resolutionOperation.sent));
    document.getElementById("issue-resolution-batch-read").disabled =
      busy || !resolutionAuthority() ||
      !resolutionUuid(document.getElementById("issue-resolution-batch").value);
    document.getElementById("issue-resolution-discard").disabled =
      busy || !resolutionContext() || resolutionOtherBusy();
    for (const button of document.querySelectorAll("[data-resolution-issue]")) {
      const item = savedIssuesPage?.items?.find((row) => row.issueKey === button.dataset.resolutionIssue);
      button.disabled = busy || !item || !resolutionEligible({
        ...item, path: savedIssuesPage.path, identity: savedIssuesPage.identity,
      });
    }
    document.getElementById("issue-resolution-source").textContent = resolutionSource
      ? `선택 지적 ${resolutionSource.issueKey} · 사본 ${resolutionSource.snapshotId} · ${resolutionSource.kind} / ${resolutionSource.state} · 원본 BATCH ${resolutionSource.sourceBatchKey}. 쓰기 수정번호 ${detail?.editRev ?? "미확인"} · ${resolutionEligible() ? "새 요청 후보 (서버 최종 판단)" : "현재 새 요청 불가"}.`
      : "선택한 지적 없음";
    document.getElementById("issue-resolution-reason").textContent = resolutionSource
      ? `reasonCode=${resolutionSource.kind === "INFRA" ? "INFRA_RECOVERED" : "GRADING_FIX_VERIFIED"} · targetReviewId=null`
      : "사유는 선택한 INFRA / GRADING 종류에 결속됩니다. targetReviewId는 null입니다.";
    for (const button of editor.querySelectorAll("form[data-section] button"))
      button.disabled = busy || saveLocked || !writableSection(button.closest("form").dataset.section);
  }

  /** 페이지의 닫힌 허용 메타만 선택하며 목록 교체는 이미 보관한 원래 의도를 지우지 않는다. */
  function renderResolutionChoices() {
    const choices = document.getElementById("issue-resolution-choices");
    if (!choices) return;
    choices.replaceChildren();
    const observed = savedIssuesPage?.items?.find((item) => item.issueKey === resolutionSource?.issueKey);
    if (observed) resolutionSource = Object.freeze({
      ...observed, path: savedIssuesPage.path, identity: savedIssuesPage.identity,
    });
    for (const item of savedIssuesPage?.items || []) {
      if (item.state !== "OPEN" || !["INFRA", "GRADING"].includes(item.kind)) continue;
      const source = Object.freeze({ ...item, path: savedIssuesPage.path, identity: savedIssuesPage.identity });
      const button = document.createElement("button");
      button.type = "button";
      button.className = "secondary";
      button.dataset.resolutionIssue = item.issueKey;
      button.textContent = `${item.kind} · ${item.issueKey} 선택`;
      button.addEventListener("click", () => {
        if (resolutionOperation || !resolutionEligible(source)) return;
        resolutionSource = source;
        resolutionChanged();
      });
      choices.append(button);
    }
    syncResolutionControls();
  }

  /** 새 입력은 동의·후속 상세 관측만 무효화하며 이미 전송한 A의 본문과 B 입력은 교체하지 않는다. */
  function resolutionChanged() {
    ++resolutionInputEpoch;
    if (resolutionOperation && !resolutionOperation.sent) AdminUI.cancelConfirmation();
    const output = document.getElementById("issue-resolution-batch-detail");
    output.replaceChildren();
    output.hidden = true;
    document.getElementById("issue-resolution-batch-status").textContent =
      "입력·선택이 바뀌었습니다. 상세는 직접 조회하세요. 원래 보관 의도는 유지됩니다.";
    syncResolutionControls();
  }

  /**
   * 실제 BatchDetail의 모든 비민감 필드와 하위 항목을 닫힌 계약으로 검사한다.
   * @param {object} data 서버 상세. 누락·추가 키나 안전하지 않은 수는 거절한다.
   * @param {string} key 직접 입력한 정규 UUID v4.
   * @returns {object} 검증된 상세이며 적격 판정이나 쓰기 권한은 아니다.
   */
  function resolutionBatch(data, key) {
    const keys = ["batchKey", "purpose", "snapshotId", "runtimeConfigId", "runtimeEpoch",
      "datasetHash", "state", "passed", "repeatCount", "totalJobs", "completedJobs",
      "failedComparisons", "unresolvedJobs", "createdAt", "batchDeadline", "completedAt",
      "validUntil", "items", "requestId"];
    const integer = (value) => Number.isSafeInteger(value) && value >= 0 && value <= 2147483647;
    if (!cloneKeys(data, keys) || data.batchKey !== key || !resolutionUuid(data.batchKey) ||
      !resolutionUuid(data.requestId) || !savedIssueDecimal(data.snapshotId) ||
      !cloneDecimal(data.runtimeEpoch) || !/^[A-Z0-9_]{1,80}$/.test(data.runtimeConfigId) ||
      typeof data.runtimeConfigId !== "string" || typeof data.datasetHash !== "string" ||
      !/^[0-9a-f]{64}$/.test(data.datasetHash) || !["REVIEW", "AVAILABILITY"].includes(data.purpose) ||
      !["RUNNING", "COMPLETED", "FAILED", "CANCELLED"].includes(data.state) ||
      !(data.passed === null || typeof data.passed === "boolean") ||
      !["repeatCount", "totalJobs", "completedJobs", "failedComparisons", "unresolvedJobs"].every((name) => integer(data[name])) ||
      data.repeatCount < 1 || data.completedJobs > data.totalJobs ||
      data.failedComparisons > data.totalJobs || data.unresolvedJobs > data.totalJobs ||
      !resolutionTime(data.createdAt) || !resolutionTime(data.batchDeadline) ||
      !(data.completedAt === null || resolutionTime(data.completedAt)) ||
      !(data.validUntil === null || resolutionTime(data.validUntil)) ||
      !Array.isArray(data.items) || data.items.length !== data.totalJobs)
      throw { status: 503 };
    const seen = new Set();
    for (const item of data.items) {
      if (!cloneKeys(item, ["sampleCode", "repeatNo", "jobKey", "state", "comparison", "errorCode"]) ||
        typeof item.sampleCode !== "string" || !/^[A-Z0-9_]{1,32}$/.test(item.sampleCode) ||
        !integer(item.repeatNo) || item.repeatNo < 1 || item.repeatNo > data.repeatCount ||
        !resolutionUuid(item.jobKey) || seen.has(`${item.sampleCode}:${item.repeatNo}`) ||
        !["STAGED", "QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"].includes(item.state) ||
        !["PASS", "FAIL", "PENDING"].includes(item.comparison) ||
        !(item.errorCode === null || (typeof item.errorCode === "string" && /^[A-Z0-9_]{1,80}$/.test(item.errorCode))))
        throw { status: 503 };
      seen.add(`${item.sampleCode}:${item.repeatNo}`);
    }
    return data;
  }

  /** 명시한 키의 상세 한 건만 조회한다. 실패·형식 오류는 이전 영수증이나 쓰기 기준을 변경하지 않는다. */
  async function readResolutionBatch() {
    const key = document.getElementById("issue-resolution-batch").value;
    if (resolutionOperation || !resolutionAuthority() || !resolutionUuid(key)) return;
    const op = { kind: "read", identity: editorIdentity, path: editorPath(), generation,
      permissions: permissionEpoch, epoch: resolutionInputEpoch, input: resolutionFingerprint(),
      selection: savedSelection, readEpoch: savedReadEpoch };
    resolutionOperation = op;
    const owns = () => resolutionOperation === op && op.identity === editorIdentity &&
      !editorIdentityRevoked && !editor.hidden && op.path === editorPath() &&
      op.generation === generation && op.permissions === permissionEpoch &&
      op.selection === savedSelection && op.readEpoch === savedReadEpoch &&
      op.epoch === resolutionInputEpoch && op.input === resolutionFingerprint();
    const output = document.getElementById("issue-resolution-batch-detail");
    output.replaceChildren();
    output.hidden = true;
    document.getElementById("issue-resolution-batch-status").textContent = "후속 BATCH 상세 조회 중";
    syncResolutionControls();
    syncReviewControls();
    try {
      const data = await request("GET", `${op.path}/regressions/${key}`, undefined, false, undefined, owns);
      if (!owns()) return;
      output.textContent = JSON.stringify(resolutionBatch(data, key), null, 2);
      output.hidden = false;
      document.getElementById("issue-resolution-batch-status").textContent =
        "전체 비민감 상세 관측 완료 · 해소 적격·현재 runtime·품질·운영 효력은 추론하지 않습니다. POST만 최종 판단합니다.";
    } catch {
      if (owns()) document.getElementById("issue-resolution-batch-status").textContent =
        "후속 BATCH 상세 조회 실패 · 다른 사건의 근거·접근 불가·형식 오류를 성공으로 보정하지 않습니다.";
    } finally {
      if (resolutionOperation === op) resolutionOperation = undefined;
      syncResolutionControls();
      syncReviewControls();
    }
  }

  /**
   * PT-A12 여섯 필드와 두 여섯 필드 투영만 허용하며 역사 수정번호를 최신본으로 승계하지 않는다.
   * @param {object} data 서버 성공 응답. 잘못된 성공은 미확인으로 남긴다.
   * @param {object} intent 같은 신원·경로의 불변 원래 본문과 사본.
   * @returns {object} 검증된 영수증. 현재 GET은 이 함수의 입력이 아니다.
   */
  function resolutionResult(data, intent) {
    const keys = ["issueKey", "snapshotId", "editRev", "state", "targetBatchKey", "resolvedAt"];
    if (!cloneKeys(data, ["action", "replayed", "changed", "original", "current", "requestId"]) ||
      data.action !== "ISSUE_RESOLVE" || typeof data.replayed !== "boolean" ||
      data.changed !== !data.replayed || !resolutionUuid(data.requestId))
      throw { status: 503 };
    for (const value of [data.original, data.current]) {
      if (!cloneKeys(value, keys) || value.issueKey !== intent.issueKey ||
        !resolutionUuid(value.issueKey) || value.snapshotId !== intent.snapshotId ||
        !savedIssueDecimal(value.snapshotId) || !cloneDecimal(value.editRev) ||
        value.state !== "RESOLVED" || value.targetBatchKey !== intent.body.targetBatchKey ||
        !resolutionUuid(value.targetBatchKey) || !resolutionTime(value.resolvedAt))
        throw { status: 503 };
    }
    if (data.original.editRev !== intent.body.expectedRev ||
      BigInt(data.current.editRev) < BigInt(data.original.editRev) ||
      data.original.resolvedAt !== data.current.resolvedAt ||
      (!data.replayed && keys.some((key) => data.original[key] !== data.current[key])) ||
      (intent.receipt && (!data.replayed ||
        keys.some((key) => data.original[key] !== intent.receipt.original[key]))))
      throw { status: 503 };
    return Object.freeze({ ...data, original: Object.freeze({ ...data.original }), current: Object.freeze({ ...data.current }) });
  }

  /** 보관 중인 미확인·거절·CSRF 상태를 표시하되 확정 영수증은 덮어쓰지 않는다. */
  function renderResolutionReceipt() {
    const node = document.getElementById("issue-resolution-receipt");
    if (!node || !resolutionIntent) return;
    const intent = resolutionIntent;
    const result = intent.receipt;
    node.dataset.state = intent.state;
    node.textContent = result
      ? `해소 영수증 확정 · ${result.replayed ? "과거 성공 재생" : "새 해소"} · ${result.original.issueKey} · 사본 ${result.original.snapshotId} · 최초 수정번호 ${result.original.editRev} / 현재 관측 수정번호 ${result.current.editRev} · BATCH ${result.original.targetBatchKey} · ${displayTime(result.original.resolvedAt)} · requestId ${result.requestId}. 원고 수정번호·품질·공개 기준은 변경하지 않습니다.`
      : `원래 지적 ${intent.issueKey} · 사본 ${intent.snapshotId} · 수정번호 ${intent.body.expectedRev} · BATCH ${intent.body.targetBatchKey} · requestKey ${intent.body.requestKey} · ${intent.state === "unknown" ? "결과 미확인. 현재 GET·이후 거절로 이전 요청 실패를 확정하지 않습니다." : intent.state === "csrf" ? "CSRF 거절. 원래 키·본문을 유지합니다." : "이번 요청 확정 거절. 새 의도는 명시 폐기 후 결정하세요."}`;
  }

  /**
   * 새 요청 또는 보관한 원래 요청만 명시 동의 후 전송한다. 입력 B로 재생 A를 재구성하지 않는다.
   * @param {boolean} replay true이면 원래 경로·키·본문으로만 재확인한다.
   */
  async function submitResolution(replay = false) {
    if (resolutionOperation || !resolutionAuthority()) return;
    if (replay ? !resolutionIntent || resolutionIntent.state === "rejected" ||
      resolutionIntent.identity !== editorIdentity || resolutionIntent.editorPath !== editorPath()
      : resolutionIntent || !resolutionEligible()) return;
    const message = document.getElementById("issue-resolution-status");
    const batch = document.getElementById("issue-resolution-batch").value;
    const ref = document.getElementById("issue-resolution-ref").value;
    if (!replay && (!resolutionUuid(batch) || !/^[A-Za-z0-9_-]{8,64}$/.test(ref))) {
      message.textContent = "소문자 UUID v4와 비개인 ASCII 확인 참조 8~64자를 입력하세요. 전송하지 않았습니다.";
      document.getElementById(!resolutionUuid(batch) ? "issue-resolution-batch" : "issue-resolution-ref").focus();
      return;
    }
    const intent = replay ? resolutionIntent : {
      identity: editorIdentity, editorPath: editorPath(), issueKey: resolutionSource.issueKey,
      snapshotId: resolutionSource.snapshotId, fingerprint: resolutionFingerprint(),
      path: `${editorPath()}/execution-issues/${resolutionSource.issueKey}/resolve`,
      body: Object.freeze({ expectedRev: detail.editRev, requestKey: crypto.randomUUID(),
        reasonCode: resolutionSource.kind === "INFRA" ? "INFRA_RECOVERED" : "GRADING_FIX_VERIFIED",
        verificationRef: ref, targetBatchKey: batch, targetReviewId: null }),
    };
    const op = { kind: "write", intent, replay, sent: false, priorUnknown: intent.state === "unknown", generation, permissions: permissionEpoch,
      detail, source: resolutionSource, selection: savedSelection, readEpoch: savedReadEpoch,
      epoch: resolutionInputEpoch, inputs: cloneInputs(), drafts: cloneDraftState() };
    resolutionOperation = op;
    const owns = () => resolutionOperation === op && intent.identity === editorIdentity &&
      !editorIdentityRevoked && !editor.hidden && intent.editorPath === editorPath() &&
      op.generation === generation && op.permissions === permissionEpoch;
    const fresh = () => owns() && resolutionAuthority() && op.detail === detail &&
      op.source === resolutionSource && op.selection === savedSelection && op.readEpoch === savedReadEpoch &&
      op.epoch === resolutionInputEpoch && op.inputs === cloneInputs() && op.drafts === cloneDraftState() &&
      (replay ? resolutionIntent === intent : !resolutionIntent && resolutionEligible());
    syncResolutionControls();
    syncReviewControls();
    try {
      if (!(await observeEditorIdentity()) || !fresh()) return;
      const accepted = await AdminUI.confirm({
        title: replay ? "원래 해소 요청 재확인" : "현재 BATCH 지적 해소",
        message: `지적 ${intent.issueKey} · 사본 ${intent.snapshotId} · 수정번호 ${intent.body.expectedRev} · 후속 BATCH ${intent.body.targetBatchKey}. ${replay ? "현재 입력은 보내지 않고 원래 키·본문만 보냅니다. 최초 요청이 저장되지 않았다면 지금 해소될 수 있습니다." : "실제 후속 근거와 현재 REVIEW 권한은 서버가 최종 판단합니다."} 원고·다른 입력은 보존하며 품질·공개 승인이 아닙니다.`,
        confirmLabel: replay ? "원래 본문으로 재확인" : "지적 해소 요청",
      });
      if (!(await observeEditorIdentity()) || !accepted || !fresh()) return;
      const data = await request("POST", intent.path, intent.body, false, undefined, (phase) => {
        if (phase === "response") return owns() && op.sent && resolutionIntent === intent;
        if (!fresh()) return false;
        resolutionIntent = intent;
        op.sent = true;
        // 전송 이후 세대가 교체되어도 확인 전에는 원래 의도가 미확인으로 남는다.
        if (!intent.receipt && intent.state !== "unknown") intent.state = "unknown";
        message.textContent = "해소 요청 전송 중 · 원래 키·본문 보관. 자동 재전송하지 않습니다.";
        renderResolutionReceipt();
        syncResolutionControls();
        return true;
      });
      if (!owns()) return;
      intent.receipt = resolutionResult(data, intent);
      intent.state = "confirmed";
      resolutionAcknowledged = intent.fingerprint;
      renderResolutionReceipt();
      message.textContent = "해소 영수증을 확인했습니다. 새 입력·쓰기 기준·필터·커서는 유지하며 자동 조회하지 않습니다.";
    } catch (error) {
      if (!owns() || !op.sent) return;
      const rejected = {
        400: ["INVALID_REQUEST"], 409: ["REQUEST_KEY_CONFLICT", "EDIT_CONFLICT", "STATE_CONFLICT", "RUNTIME_UNAVAILABLE"],
        422: ["EVIDENCE_INCOMPLETE", "ISSUE_NOT_RESOLVABLE"], 404: ["NOT_FOUND"],
      }[error.status]?.includes(error.code);
      if (!intent.receipt) intent.state = op.priorUnknown ? "unknown" :
        error.status === 403 && error.code === "CSRF_INVALID" ? "csrf" : rejected ? "rejected" : "unknown";
      renderResolutionReceipt();
      message.textContent = error.status === 403 && error.code === "CSRF_INVALID"
        ? "CSRF 거절 · 토큰만 폐기했습니다. 사용자 동의로 원래 키·본문을 재확인할 수 있습니다."
        : `이번 해소 ${rejected ? "요청 거절" : "결과 미확인"}${rejected ? ` · ${error.code}` : ""}. 이전 미확인·확정 영수증과 입력은 유지하며 자동 재전송하지 않습니다.`;
    } finally {
      if (resolutionOperation === op) {
        resolutionOperation = undefined;
        if (!op.sent) message.textContent = "동의 취소 또는 대상·입력·권한 변경으로 전송하지 않았습니다. 원래 의도와 입력은 유지됩니다.";
      }
      syncResolutionControls();
      syncReviewControls();
    }
  }

  /** 명시 폐기 또는 문서 소유권 종료에서만 원래 의도·입력·영수증을 제거한다. 서버 결과는 취소하지 않는다. */
  function clearResolution() {
    resolutionSource = resolutionIntent = resolutionOperation = undefined;
    ++resolutionInputEpoch;
    document.getElementById("issue-resolution-form")?.reset();
    document.getElementById("issue-resolution-receipt")?.replaceChildren();
    document.getElementById("issue-resolution-receipt")?.removeAttribute("data-state");
    document.getElementById("issue-resolution-batch-detail")?.replaceChildren();
    resolutionAcknowledged = resolutionFingerprint();
    syncResolutionControls();
  }

  /** 같은 신원·세대·선택·입력에 대한 명시 동의만 메모리 폐기에 사용한다. */
  async function discardResolution() {
    if (resolutionOperation || !resolutionContext() || resolutionOtherBusy()) return;
    const identity = editorIdentity;
    const current = generation;
    const permissions = permissionEpoch;
    const input = cloneInputs();
    const epoch = resolutionInputEpoch;
    const intent = resolutionIntent;
    const path = editorPath();
    const selection = savedSelection;
    const readEpoch = savedReadEpoch;
    const drafts = cloneDraftState();
    const fresh = () => !resolutionOperation && resolutionContext() && !resolutionOtherBusy() &&
      identity === editorIdentity && current === generation && permissions === permissionEpoch &&
      selection === savedSelection && readEpoch === savedReadEpoch && drafts === cloneDraftState() &&
      input === cloneInputs() && epoch === resolutionInputEpoch && intent === resolutionIntent && path === editorPath();
    if (!(await observeEditorIdentity()) || !fresh()) return;
    const accepted = await AdminUI.confirm({
      title: "해소 입력·원래 의도 폐기",
      message: "입력과 원래 키·본문을 메모리에서 버립니까? 서버 해소는 취소되지 않으며 미확인도 실패로 확정되지 않습니다. 원래 요청 재확인은 불가능해집니다.",
      confirmLabel: "해소 입력·의도 폐기",
    });
    if (!(await observeEditorIdentity()) || !accepted || !fresh()) return;
    clearResolution();
    document.getElementById("issue-resolution-status").textContent = "해소 입력·보관 의도를 명시 폐기했습니다. 서버 결과는 변경하지 않았습니다.";
  }

  /**
   * BIGINT를 Number로 바꾸지 않고 정규 문자열과 PostgreSQL 상한을 검사한다.
   * @param {unknown} value 서버 ID·수정번호.
   * @param {boolean} positive ID이면 true이며 수정번호는 0을 허용한다.
   * @returns {boolean} 정규 BIGINT 범위 여부.
   */
  function cloneDecimal(value, positive = false) {
    return (
      savedDecimal(value, positive) &&
      (value.length < 19 ||
        (value.length === 19 && value <= "9223372036854775807"))
    );
  }

  /** 양의 int 버전 번호만 허용하며 문자열·소수·범위 초과는 거절한다. */
  function cloneVersion(value) {
    return Number.isInteger(value) && value > 0 && value <= 2147483647;
  }

  /**
   * 닫힌 DTO의 필수·선택 키를 검사한다. 선택 키의 null 허용은 각 필드에서 따로 결정한다.
   * @param {unknown} value 검사할 객체.
   * @param {string[]} required 빠짐없이 필요한 키.
   * @param {string[]} optional NON_NULL 응답에서 생략할 수 있는 키.
   * @returns {boolean} 추가·누락 키 없는 객체인지 여부.
   */
  function cloneKeys(value, required, optional = []) {
    return Boolean(
      value &&
      typeof value === "object" &&
      !Array.isArray(value) &&
      required.every((key) => Object.hasOwn(value, key)) &&
      Object.keys(value).every(
        (key) => required.includes(key) || optional.includes(key),
      ),
    );
  }

  /**
   * 전체 VersionDetail에서 복제 후보 메타만 복사한다. 현재 공개 포인터나 작업본 존재는 추정하지 않는다.
   * @param {object} data 명시 상세 GET의 서버 응답.
   * @returns {object|undefined} 원고 없는 불변 후보이며 계약 불일치는 사용 불가다.
   */
  function cloneMetadata(data) {
    const permissions = copyPermissions(data?.permissions);
    if (
      !data ||
      !permissions ||
      !/^ST_[A-Z0-9_]+$/.test(data.storyCode) ||
      !cloneVersion(data.versionNo) ||
      !cloneDecimal(data.storyRev) ||
      typeof data.storyActiveYn !== "boolean" ||
      typeof data.activeYn !== "boolean" ||
      !Object.hasOwn(versionStates, data.status) ||
      !(
        data.currentSnapshotId === null ||
        cloneDecimal(data.currentSnapshotId, true)
      )
    )
      return undefined;
    return Object.freeze({
      storyCode: data.storyCode,
      versionNo: data.versionNo,
      storyRev: data.storyRev,
      storyActiveYn: data.storyActiveYn,
      activeYn: data.activeYn,
      status: data.status,
      currentSnapshotId: data.currentSnapshotId,
      permissions: Object.freeze(permissions),
      path: `/${data.storyCode}/versions/${data.versionNo}`,
    });
  }

  /** 같은 편집 경로·신원에서 관측한 편집 권한만 요구한다. 과거 재생에는 옛 상태·수정번호를 요구하지 않는다. */
  function cloneAuthority() {
    return Boolean(
      detail &&
      !resolutionOperation &&
      !editor.hidden &&
      editorIdentity &&
      !editorIdentityRevoked &&
      document.getElementById("unavailable").hidden &&
      cloneCandidate &&
      location.pathname === `/admin/stories${cloneCandidate.path}` &&
      cloneCandidate.permissions.edit === true &&
      observedPermissions?.edit === true,
    );
  }

  /** 새 복제만 활성 부모·PUBLISHED 후보·정규 사본을 요구하며 DRAFT 편집 조건은 사용하지 않는다. */
  function cloneEligible() {
    return (
      cloneAuthority() &&
      cloneCandidate.storyActiveYn &&
      cloneCandidate.activeYn &&
      cloneCandidate.status === "PUBLISHED" &&
      cloneDecimal(cloneCandidate.currentSnapshotId, true)
    );
  }

  /** 메모리에 남은 전송 중·미확인·CSRF 복구 의도만 이탈에서 보호한다. */
  function cloneProtected() {
    return Boolean(
      cloneOperation?.kind === "write" ||
      (cloneIntent && ["inflight", "unknown", "csrf"].includes(cloneState)),
    );
  }

  /** 현재 입력·숨은 keep 버퍼·반환·수동 입력을 저장 없이 동의 신선도에 결속한다. */
  function cloneInputs() {
    return JSON.stringify(
      [...editor.querySelectorAll("input, textarea, select")].map((node) => [
        node.id,
        node.value,
        node.checked,
        node.disabled,
      ]),
    );
  }

  /** 동의 중 다른 작업의 확정·유실·쓰기 잠금 변화를 감지하며 그 의도를 변경하지 않는다. */
  function cloneDraftState() {
    return JSON.stringify({
      saveLocked,
      reviewNeedsRead,
      lifecycleNeedsRead,
      savedDetailPending,
      reviewBusy: Boolean(reviewOperation),
      lifecycleBusy: Boolean(lifecycleOperation),
      manualBusy: Boolean(manualOperation),
      metadataBusy: Boolean(savedMetadataOperation),
      lifecycleSent: lifecycleIntent?.sent,
      lifecycleUnknown: lifecycleIntent?.unknown,
      lifecycleRejected: lifecycleIntent?.rejected,
      manualSent: manualIntent?.sent,
      manualUnknown: manualIntent?.unknown,
      manualRejected: manualIntent?.rejected,
      manualReceipt: manualIntent?.receipt,
    });
  }

  /** 복제만의 안내·제어를 갱신한다. 다른 원고·영수증·쓰기 잠금은 바꾸지 않는다. */
  function syncCloneControls() {
    if (!document.getElementById("clone-panel")) return;
    const busy = Boolean(cloneOperation);
    document
      .getElementById("clone-panel")
      .setAttribute("aria-busy", String(busy));
    document.getElementById("clone-submit").disabled =
      Boolean(cloneIntent) ||
      (busy &&
        (cloneOperation.kind !== "write" ||
          cloneOperation.replay ||
          cloneSent)) ||
      !cloneEligible();
    document.getElementById("clone-refresh").disabled =
      !detail || editor.hidden || busy || editorIdentityRevoked;
    const replay = document.getElementById("clone-reconfirm");
    replay.hidden = !cloneIntent || cloneState === "rejected";
    replay.disabled =
      !cloneAuthority() || (busy && (!cloneOperation.replay || cloneSent));
    document.getElementById("clone-target").textContent = cloneCandidate
      ? `후보 버전 ${cloneCandidate.versionNo} · 사본 ${cloneCandidate.currentSnapshotId ?? "없음"} · 사건 수정번호 ${cloneCandidate.storyRev} · ${cloneCandidate.status} · ${cloneCandidate.storyActiveYn && cloneCandidate.activeYn ? "활성" : "비활성"}. 현재 공개본 여부·기존 작업본 확인은 요청 시 서버에서 수행합니다.`
      : "복제 후보 미확인 · 이 상세에는 현재 공개 포인터가 제공되지 않습니다.";
  }

  /**
   * 서버가 실제 제공한 같은 사건·응답 버전의 정확한 내부 경로만 허용한다.
   * @param {unknown} path draftPath 응답 값이며 경로를 추측하지 않는다.
   * @param {string} code 검증된 사건 코드.
   * @param {number} version 응답의 실제 양의 버전.
   * @returns {boolean} 쿼리·fragment·외부 출처·다른 버전 없는 경로 여부.
   */
  function clonePath(path, code, version) {
    return (
      cloneVersion(version) &&
      typeof path === "string" &&
      path === `/admin/stories/${code}/versions/${version}`
    );
  }

  /**
   * 실제 NON_NULL CLONE DTO를 닫힌 계약으로 읽는다. 현재 상태는 과거 생성 영수증과 다를 수 있다.
   * 이미 확정한 영수증이 있으면 같은 불변 생성 정보의 재생 응답만 허용한다.
   * @param {unknown} data 서버 성공 DTO.
   * @param {object} intent 원래 경로·키·본문을 가진 불변 의도.
   * @returns {object} 원문·임의 필드 없는 불변 영수증.
   * @throws {object} 손상된 성공은 확정 거절이 아닌 503 미확인으로 처리한다.
   */
  function cloneResult(data, intent) {
    const fail = () => {
      throw { status: 503 };
    };
    const policy = (value) =>
      typeof value === "string" && /^[A-Z0-9_]{1,40}$/.test(value);
    if (
      !cloneKeys(
        data,
        [
          "actionId",
          "action",
          "replayed",
          "changed",
          "original",
          "current",
          "sourcePolicyCode",
          "policyCode",
          "policyDifferences",
          "warnings",
        ],
        ["draftPath"],
      ) ||
      !cloneDecimal(data.actionId, true) ||
      data.action !== "CLONE" ||
      typeof data.replayed !== "boolean" ||
      typeof data.changed !== "boolean" ||
      data.changed === data.replayed
    )
      fail();
    const original = data.original;
    const current = data.current;
    const body = intent.body;
    if (
      !cloneKeys(original, [
        "versionNo",
        "storyRev",
        "editRev",
        "playRev",
        "publishedVersionNo",
        "viewYn",
        "sourceVersionNo",
        "sourceSnapshotId",
        "createdAt",
      ]) ||
      !cloneVersion(original.versionNo) ||
      original.versionNo <= body.sourceVersionNo ||
      !cloneDecimal(original.storyRev) ||
      BigInt(original.storyRev) !== BigInt(body.expectedStoryRev) + 1n ||
      original.editRev !== "0" ||
      !cloneDecimal(original.playRev) ||
      original.publishedVersionNo !== body.sourceVersionNo ||
      original.sourceVersionNo !== body.sourceVersionNo ||
      original.sourceSnapshotId !== body.sourceSnapshotId ||
      typeof original.viewYn !== "boolean" ||
      typeof original.createdAt !== "string" ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-](?:0\d|1[0-7]):[0-5]\d|[+-]18:00)$/.test(
        original.createdAt,
      ) ||
      !Number.isFinite(Date.parse(original.createdAt)) ||
      !Number.isFinite(Date.parse(`${original.createdAt.slice(0, 19)}Z`)) ||
      new Date(`${original.createdAt.slice(0, 19)}Z`)
        .toISOString()
        .slice(0, 19) !== original.createdAt.slice(0, 19)
    )
      fail();
    if (
      !cloneKeys(
        current,
        ["storyRev", "playRev", "viewYn", "publishedVersionNo"],
        ["versionNo", "status", "editRev", "activeYn"],
      ) ||
      !cloneDecimal(current.storyRev) ||
      !cloneDecimal(current.playRev) ||
      typeof current.viewYn !== "boolean" ||
      !(
        current.publishedVersionNo === null ||
        cloneVersion(current.publishedVersionNo)
      )
    )
      fail();
    const versionKeys = ["versionNo", "status", "editRev", "activeYn"];
    const accessible = versionKeys.some((key) => Object.hasOwn(current, key));
    if (
      accessible &&
      (versionKeys.some((key) => !Object.hasOwn(current, key)) ||
        current.versionNo !== original.versionNo ||
        !Object.hasOwn(versionStates, current.status) ||
        !cloneDecimal(current.editRev) ||
        typeof current.activeYn !== "boolean")
    )
      fail();
    if (
      Object.hasOwn(data, "draftPath") &&
      (!accessible ||
        !clonePath(data.draftPath, intent.storyCode, current.versionNo))
    )
      fail();
    if (!data.replayed && (!accessible || !Object.hasOwn(data, "draftPath")))
      fail();
    if (
      !policy(data.sourcePolicyCode) ||
      !policy(data.policyCode) ||
      !Array.isArray(data.policyDifferences) ||
      !Array.isArray(data.warnings) ||
      data.policyDifferences.some(
        (item) =>
          !cloneKeys(item, ["field", "before", "after"]) ||
          item.field !== "policyCode" ||
          !policy(item.before) ||
          !policy(item.after),
      ) ||
      data.warnings.some(
        (item) =>
          !cloneKeys(item, ["code", "field"]) ||
          typeof item.code !== "string" ||
          !/^[A-Z0-9_]{1,80}$/.test(item.code) ||
          typeof item.field !== "string" ||
          !/^[A-Za-z0-9_.]{1,100}$/.test(item.field),
      )
    )
      fail();
    if (
      cloneReceipt &&
      (data.replayed !== true ||
        cloneReceipt.actionId !== data.actionId ||
        Object.keys(original).some(
          (key) => cloneReceipt.original[key] !== original[key],
        ) ||
        cloneReceipt.sourcePolicyCode !== data.sourcePolicyCode ||
        cloneReceipt.policyCode !== data.policyCode ||
        cloneReceipt.policyDifferences.length !==
          data.policyDifferences.length ||
        cloneReceipt.policyDifferences.some((item, index) =>
          ["field", "before", "after"].some(
            (key) => item[key] !== data.policyDifferences[index][key],
          ),
        ) ||
        cloneReceipt.warnings.length !== data.warnings.length ||
        cloneReceipt.warnings.some((item, index) =>
          ["code", "field"].some(
            (key) => item[key] !== data.warnings[index][key],
          ),
        ))
    )
      fail();
    return Object.freeze({
      ...data,
      original: Object.freeze({ ...original }),
      current: Object.freeze({ ...current }),
      policyDifferences: Object.freeze(
        data.policyDifferences.map((item) => Object.freeze({ ...item })),
      ),
      warnings: Object.freeze(
        data.warnings.map((item) => Object.freeze({ ...item })),
      ),
    });
  }

  /** 원문 없는 영수증과 정책 메타만 textContent로 표시하고 명시 내부 링크만 제공한다. */
  function renderCloneReceipt() {
    const result = cloneReceipt;
    const node = document.getElementById("clone-receipt");
    node.dataset.state = "confirmed";
    node.textContent = `복제 영수증 ${result.actionId} · ${result.replayed ? "과거 성공 재생" : "새 생성"} · 생성 당시 버전 ${result.original.versionNo} / 사건 수정번호 ${result.original.storyRev} / 원고 수정번호 ${result.original.editRev} · ${displayTime(result.original.createdAt)}. 현재 사건 수정번호 ${result.current.storyRev} · 현재 작업 버전 ${result.current.versionNo ?? "접근 가능한 정보 없음"} / ${result.current.status ?? "미제공"}. 현재 공개·노출 또는 품질 승인을 뜻하지 않습니다.`;
    const list = document.getElementById("clone-policy");
    list.replaceChildren();
    for (const text of [
      `원본 정책 ${result.sourcePolicyCode} → 적용 정책 ${result.policyCode}`,
      ...(result.policyDifferences.length
        ? result.policyDifferences.map(
            (item) => `${item.field}: ${item.before} → ${item.after}`,
          )
        : ["정책 차이 없음"]),
      ...result.warnings.map((item) => `${item.code} · ${item.field}`),
    ]) {
      const row = document.createElement("li");
      row.textContent = text;
      list.append(row);
    }
    showCloneLink(result.draftPath);
  }

  /** 검증을 마친 서버 경로만 링크에 넣는다. 생략된 경로는 추측하거나 자동 이동하지 않는다. */
  function showCloneLink(path) {
    const link = document.getElementById("clone-link");
    link.hidden = !path;
    if (path) link.href = path;
    else link.removeAttribute("href");
  }

  /**
   * 명시 GET 한 번으로 복제 후보만 바꾼다. 이전 unknown·다른 요청 의도·원고 쓰기 기준은 유지한다.
   * @returns {Promise<void>} 실패는 후보 사용을 막고 자동 조회·재전송하지 않는다.
   */
  async function refreshClone() {
    if (
      !detail ||
      editor.hidden ||
      resolutionOperation ||
      cloneOperation ||
      editorIdentityRevoked ||
      location.pathname !==
        `/admin/stories/${detail.storyCode}/versions/${detail.versionNo}`
    )
      return;
    AdminUI.cancelConfirmation();
    const op = Object.freeze({
      kind: "read",
      path: editorPath(),
      identity: editorIdentity,
      generation,
    });
    cloneOperation = op;
    const owns = () =>
      cloneOperation === op &&
      location.pathname === `/admin/stories${op.path}` &&
      op.identity === editorIdentity &&
      op.generation === generation &&
      !editor.hidden;
    document.getElementById("clone-status").textContent =
      "복제 후보만 조회 중입니다.";
    syncCloneControls();
    try {
      const data = await request(
        "GET",
        op.path,
        undefined,
        false,
        undefined,
        owns,
      );
      if (!owns()) return;
      const candidate = cloneMetadata(data);
      if (!candidate || candidate.path !== op.path) throw { status: 503 };
      cloneCandidate = candidate;
      observePermissions(data.permissions);
      if (cloneState === "rejected") {
        cloneIntent = undefined;
        cloneState = "idle";
        showCloneLink();
      }
      document.getElementById("clone-status").textContent =
        "복제 후보만 갱신했습니다. 원고·쓰기 기준·다른 의도는 유지됩니다. 현재 조회는 이전 미확인 요청의 영수증이 아닙니다.";
    } catch {
      if (owns()) {
        cloneCandidate = undefined;
        document.getElementById("clone-status").textContent =
          "복제 후보 조회 실패 · 이전 의도와 입력은 유지됩니다. 자동 재시도하지 않습니다.";
      }
    } finally {
      if (cloneOperation === op) cloneOperation = undefined;
      syncCloneControls();
    }
  }

  /**
   * 정확한 후보·입력·권한·신원을 동의와 Me/CSRF 뒤 다시 확인해 한 번만 전송한다.
   * @param {boolean} replay true이면 원래 경로·키·본문만 재확인하며 옛 PUBLISHED 조건은 요구하지 않는다.
   * @returns {Promise<void>} 유실·503·손상 성공은 unknown으로 남기고 새 키 복구를 금지한다.
   */
  async function submitClone(replay = false) {
    if (
      cloneOperation ||
      (replay
        ? !cloneIntent || cloneState === "rejected" || !cloneAuthority()
        : cloneIntent || !cloneEligible())
    )
      return;
    const candidate = cloneCandidate;
    const intent = replay
      ? cloneIntent
      : Object.freeze({
          storyCode: candidate.storyCode,
          path: `/${candidate.storyCode}/drafts`,
          editorPath: candidate.path,
          identity: editorIdentity,
          body: Object.freeze({
            expectedStoryRev: candidate.storyRev,
            sourceVersionNo: candidate.versionNo,
            sourceSnapshotId: candidate.currentSnapshotId,
            requestKey: crypto.randomUUID(),
          }),
        });
    if (
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
        intent.body.requestKey,
      ) ||
      new TextEncoder().encode(JSON.stringify(intent.body)).length > 8192
    )
      return;
    const op = Object.freeze({
      kind: "write",
      intent,
      replay,
      candidate,
      identity: editorIdentity,
      generation,
      permissions: permissionEpoch,
      inputs: cloneInputs(),
      drafts: cloneDraftState(),
      selection: savedSelection,
      lifecycleIntent,
      manualIntent,
      unknownReturnSnapshot,
      reviewInputEpoch,
      manualInputEpoch,
    });
    const priorState = cloneState;
    cloneOperation = op;
    cloneSent = false;
    // 실제 전송 뒤에는 입력·권한 관측 교체가 원래 영수증의 소유권을 빼앗지 않는다.
    const owns = () =>
      cloneOperation === op &&
      intent.identity === editorIdentity &&
      location.pathname === `/admin/stories${intent.editorPath}` &&
      !editor.hidden &&
      !editorIdentityRevoked;
    const fresh = () =>
      owns() &&
      op.generation === generation &&
      candidate === cloneCandidate &&
      op.permissions === permissionEpoch &&
      op.inputs === cloneInputs() &&
      op.drafts === cloneDraftState() &&
      op.selection === savedSelection &&
      op.lifecycleIntent === lifecycleIntent &&
      op.manualIntent === manualIntent &&
      op.unknownReturnSnapshot === unknownReturnSnapshot &&
      op.reviewInputEpoch === reviewInputEpoch &&
      op.manualInputEpoch === manualInputEpoch &&
      (replay
        ? cloneIntent === intent && cloneAuthority()
        : !cloneIntent && cloneEligible());
    syncCloneControls();
    try {
      if (!(await observeEditorIdentity()) || !fresh()) return;
      const accepted = await AdminUI.confirm({
        title: replay ? "원래 복제 요청 재확인" : "공개 사본에서 새 초안 생성",
        message: `원래 대상 버전 ${intent.body.sourceVersionNo} · 사본 ${intent.body.sourceSnapshotId} · 사건 수정번호 ${intent.body.expectedStoryRev}. ${replay ? "같은 키·본문으로만 재확인합니다. 원래 요청이 확정되지 않았다면 새 초안을 실제로 생성할 수 있습니다." : "현재 공개본과 기존 작업본 여부는 요청 시 서버가 확인합니다."} 검수·실행·사람 확인은 승계하지 않으며 현재 원고 입력은 보존합니다. 자동 이동하지 않습니다.`,
        confirmLabel: replay ? "원래 요청 재확인" : "새 초안 생성",
      });
      if (!(await observeEditorIdentity()) || !fresh()) return;
      if (!accepted) {
        document.getElementById("clone-status").textContent =
          "복제 동의를 취소했습니다. 입력과 원래 의도는 유지됩니다.";
        return;
      }
      const data = await request(
        "POST",
        intent.path,
        intent.body,
        false,
        undefined,
        (phase) => {
          if (phase === "response")
            return owns() && cloneIntent === intent && cloneSent;
          if (!fresh()) return false;
          cloneIntent = intent;
          cloneState = "inflight";
          cloneSent = true;
          if (!cloneReceipt) showCloneLink();
          document.getElementById("clone-status").textContent =
            "복제 요청 전송 중 · 원래 키·본문을 보관합니다.";
          syncCloneControls();
          return true;
        },
      );
      if (!owns()) return;
      cloneReceipt = cloneResult(data, intent);
      cloneState = "confirmed";
      renderCloneReceipt();
      document.getElementById("clone-status").textContent =
        "복제 영수증을 확인했습니다. 원고·다른 입력은 유지됩니다. 이동은 아래 링크에서 직접 선택하세요.";
    } catch (error) {
      if (!owns()) return;
      if (cloneSent) {
        const rejected =
          error.status >= 400 &&
          error.status < 500 &&
          error.code !== "CSRF_INVALID";
        cloneState =
          priorState === "confirmed"
            ? "confirmed"
            : priorState === "unknown"
              ? "unknown"
              : error.code === "CSRF_INVALID"
                ? "csrf"
                : rejected
                  ? "rejected"
                  : "unknown";
        if (!cloneReceipt) {
          const receipt = document.getElementById("clone-receipt");
          receipt.dataset.state = cloneState;
          receipt.textContent =
            cloneState === "unknown"
              ? "이전 복제 결과 미확인 · 현재 GET이나 이후 거절로 이전 요청의 실패를 확정하지 않습니다."
              : cloneState === "csrf"
                ? "CSRF 자격 거절 · 토큰만 폐기했습니다. 원래 키·본문 재확인만 가능합니다."
                : "이번 복제 요청 확정 거절 · 후보를 명시 조회한 뒤 새 의도를 결정하세요.";
        }
        if (!cloneReceipt)
          showCloneLink(
            error.code === "WORK_VERSION_EXISTS"
              ? error.notice?.draftPath
              : undefined,
          );
        document.getElementById("clone-status").textContent =
          error.code === "WORK_VERSION_EXISTS"
            ? "진행 중인 작업본이 있습니다. 서버가 접근 가능한 경로를 제공한 경우에만 링크를 표시합니다. 이전 미확인 결과는 그대로 유지됩니다."
            : "복제 요청을 확인하지 못했습니다. 자동 재전송하지 않으며 원래 의도와 입력을 유지합니다.";
      }
    } finally {
      if (cloneOperation === op) {
        if (
          !cloneSent &&
          document.getElementById("clone-status") &&
          !document.getElementById("clone-status").textContent.includes("취소")
        )
          document.getElementById("clone-status").textContent =
            "복제를 전송하지 않았습니다. 대상·입력·권한을 다시 확인하세요.";
        cloneOperation = undefined;
        cloneSent = false;
      }
      syncCloneControls();
    }
  }

  /** 문서 소유권 종료 시 복제 메모리와 안전 링크만 폐기하며 서버 요청을 취소한 것으로 표시하지 않는다. */
  function clearClone() {
    AdminUI.cancelConfirmation();
    cloneCandidate = cloneIntent = cloneOperation = cloneReceipt = undefined;
    cloneSent = false;
    cloneState = "idle";
    document.getElementById("clone-receipt")?.replaceChildren();
    document.getElementById("clone-receipt")?.removeAttribute("data-state");
    document.getElementById("clone-policy")?.replaceChildren();
    if (document.getElementById("clone-link")) showCloneLink();
    syncCloneControls();
  }

  /**
   * 수동 의견의 실제 필드를 읽는다. 비활성 nullable 입력 버퍼도 의도 변경 검사에 포함한다.
   * @returns {string} 원문을 저장소·로그에 남기지 않는 현재 메모리 비교값.
   */
  function manualFingerprint() {
    const form = document.getElementById("manual-form");
    return JSON.stringify(
      [...(form?.querySelectorAll("input, textarea, select") || [])].map(
        (input) => [input.id, input.value],
      ),
    );
  }

  /** 새 입력·전송 중·미확인 원래 의도만 이탈과 대상 교체에서 보호한다. */
  function manualProtected() {
    return Boolean(
      manualOperation ||
      (manualIntent && !manualIntent.receipt) ||
      (document.getElementById("manual-form") &&
        manualFingerprint() !== manualAcknowledged),
    );
  }

  /**
   * 권한·활성 부모·수락한 상세와 선택 사본을 검사한다. 열람 메타를 쓰기 번호로 채택하지 않는다.
   * @returns {boolean} 새 기록의 로컬 사전 조건. 서버 인가·품질 판정은 별도다.
   */
  function manualEligible() {
    return Boolean(
      detail &&
      !resolutionOperation &&
      !editor.hidden &&
      !saveLocked &&
      !savedDetailPending &&
      !reviewNeedsRead &&
      !lifecycleNeedsRead &&
      !reviewOperation &&
      !lifecycleOperation &&
      !savedMetadataOperation &&
      document.getElementById("comparison").hidden &&
      document.getElementById("unavailable").hidden &&
      hasPermission("review") &&
      detail.status === "REVIEW" &&
      detail.storyActiveYn === true &&
      detail.activeYn === true &&
      savedDecimal(detail.editRev) &&
      savedDecimal(detail.currentSnapshotId, true) &&
      savedSelection?.snapshotId === detail.currentSnapshotId,
    );
  }

  /** 공개 상태·버튼을 현재 관측에 맞춘다. 보낸 영수증은 권한만 바뀌어도 유지한다. */
  function syncManualControls() {
    if (!document.getElementById("manual-form")) return;
    const busy = Boolean(manualOperation);
    const eligible = manualEligible();
    document.getElementById("manual-baseline").disabled = busy || !eligible;
    document.getElementById("manual-submit").disabled =
      (busy && manualOperation.sent) ||
      !eligible ||
      manualBaseline?.detail !== detail ||
      manualBaseline?.expectedRev !== detail?.editRev ||
      manualBaseline?.permissionEpoch !== permissionEpoch ||
      manualBaseline?.snapshotId !== savedSelection?.snapshotId ||
      Boolean(manualIntent && !manualIntent.receipt && !manualOperation);
    document.getElementById("manual-refresh").disabled =
      busy ||
      Boolean(resolutionOperation) ||
      !detail ||
      editor.hidden ||
      Boolean(reviewOperation || lifecycleOperation || savedMetadataOperation);
    const replay = document.getElementById("manual-reconfirm");
    replay.hidden = !manualIntent?.sent || Boolean(manualIntent.rejected);
    // 과거 replay는 현재 REVIEW/활성/permissions 관측을 서버 인가 규칙으로 오인하지 않는다.
    replay.disabled =
      (busy && (!manualOperation.replay || manualOperation.sent)) ||
      Boolean(resolutionOperation) ||
      !manualIntent?.sent ||
      !detail ||
      editor.hidden ||
      Boolean(reviewOperation || lifecycleOperation || savedMetadataOperation);
    document.getElementById("manual-discard").disabled = busy;
    document.getElementById("manual-target").textContent = detail
      ? `상세 쓰기 번호 ${detail.editRev} · ${detail.status} · 현재 사본 ${detail.currentSnapshotId ?? "null"} · 선택 ${savedSelection?.snapshotId ?? "없음"} · ${manualBaseline?.detail === detail && manualBaseline?.expectedRev === detail.editRev && manualBaseline?.snapshotId === savedSelection?.snapshotId && manualBaseline?.permissionEpoch === permissionEpoch ? "명시 수락됨" : "새 기록 기준 수락 필요"}${manualIntent ? ` · 보관된 원래 대상 사본 ${manualIntent.command.snapshotId}` : manualDraftSnapshot ? ` · 보존 입력의 대상 사본 ${manualDraftSnapshot}` : ""}`
      : "현재 쓰기 기준 미확인";
    syncLifecycleControls();
  }

  /**
   * 필드 오류를 해당 실제 입력에 연결하고 첫 오류로 포커스를 이동한다.
   * @param {string} key 고정 수동 필드명 또는 resolves.
   * @param {string} message 원문을 포함하지 않는 한글 오류.
   * @throws {object} 전송하지 않은 입력 오류.
   */
  function manualError(key, message) {
    document.getElementById(`manual-${key}-error`).textContent = message;
    const value = document.getElementById(`manual-${key}`);
    const input = value?.disabled
      ? document.getElementById(`manual-${key}-mode`)
      : value;
    if (input) {
      input.setAttribute("aria-invalid", "true");
      input.focus();
    } else document.getElementById("manual-resolution-add").focus();
    throw { code: "MANUAL_INPUT" };
  }

  /**
   * JSON 숫자 토큰의 정확한 비음수 정수 여부만 검사하며 값을 Number나 확장 문자열로 만들지 않는다.
   * @param {string} token 사용자 숫자 원문. 정수값인 소수·지수 표기도 허용한다.
   * @returns {boolean} 정확한 값이 0인지 여부. 파서·저장소 숫자 길이 한도는 서버가 검사한다.
   * @throws {object} JSON 숫자 문법·음수·소수값 오류는 해당 필드에 표시한다.
   */
  function manualCountZero(token) {
    const match =
      /^(-?)(0|[1-9][0-9]*)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/.exec(token);
    if (!match)
      manualError(
        "criticalOpenCount",
        "문자열·공백이 아닌 JSON 숫자로 정확한 비음수 정수를 입력하세요.",
      );
    const digits = match[2] + (match[3] || "");
    const significant = digits.replace(/0+$/, "");
    if (!significant) return true;
    const scale = BigInt((match[3] || "").length) - BigInt(match[4] || "0");
    if (match[1] === "-" || scale > BigInt(digits.length - significant.length))
      manualError(
        "criticalOpenCount",
        "음수나 소수값은 허용하지 않습니다. 정확한 비음수 정수를 입력하세요.",
      );
    return false;
  }

  /**
   * 필드별 입력만으로 닫힌 SR05 본문을 만든다. 정수 토큰은 Number를 거치지 않는다.
   * @param {string} expectedRev 명시 수락한 상세의 정규 수정번호.
   * @param {string} requestKey 이번 의도의 UUID v4.
   * @returns {string} 여덟 키와 검증한 단일 숫자 토큰을 포함하는 정확한 JSON 본문.
   */
  function manualBody(expectedRev, requestKey) {
    for (const node of document.querySelectorAll("#manual-form [aria-invalid]"))
      node.removeAttribute("aria-invalid");
    for (const node of document.querySelectorAll("#manual-form .error"))
      node.textContent = "";
    const value = (key) => document.getElementById(`manual-${key}`).value;
    const text = (key, limit, required = true) => {
      const result = value(key).replace(/\r\n?/g, "\n");
      if (
        (required && !result.trim()) ||
        Array.from(result).length > limit ||
        /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(
          result,
        )
      )
        manualError(
          key,
          `올바른 문자로 ${required ? "공백이 아닌 " : ""}${limit}자 이하를 입력하세요.`,
        );
      return result;
    };
    const nullable = (key, limit) => {
      const mode = value(`${key}-mode`);
      if (mode === "null") return null;
      if (mode !== "value")
        manualError(key, "값 입력 또는 명시적 null을 선택하세요.");
      return text(key, limit);
    };
    const kind = value("kind");
    const result = value("result");
    if (!["MODEL", "APPROVAL"].includes(kind))
      manualError("kind", "MODEL 또는 APPROVAL을 선택하세요.");
    if (!["PASS", "FAIL", "INCOMPLETE"].includes(result))
      manualError("result", "결과를 직접 선택하세요.");
    const modelId = nullable("modelId", 80);
    const effort = nullable("effort", 16);
    const evidence = text("evidence", 20000);
    const evidenceRef = nullable("evidenceRef", 160);
    const checkedAt = nullable("checkedAt", 40);
    const count = nullable("criticalOpenCount", 131072);
    const notes = text("notes", 4000, false);
    const runRef = nullable("runRef", 160);
    const separate = value("separateContext");
    if (!["null", "true", "false"].includes(separate))
      manualError(
        "separateContext",
        "null / true / false를 명시적으로 선택하세요.",
      );
    const separateContext = separate === "null" ? null : separate === "true";
    const format = value("formatNo");
    if (!["1", "2"].includes(format))
      manualError("formatNo", "저장 형식 1 또는 2를 선택하세요.");
    const refPattern = /^[A-Za-z0-9_./-]{8,160}$/;
    for (const [key, ref] of [
      ["evidenceRef", evidenceRef],
      ["runRef", runRef],
    ])
      if (ref !== null && !refPattern.test(ref))
        manualError(key, "비개인 영문·숫자·_ . / - 8~160자를 입력하세요.");
    const countZero = count !== null && manualCountZero(count);
    if (
      checkedAt !== null &&
      (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|\+00:00)$/.test(
        checkedAt,
      ) ||
        !Number.isFinite(Date.parse(checkedAt)) ||
        new Date(checkedAt).toISOString().slice(0, 19) !==
          checkedAt.slice(0, 19))
    )
      manualError(
        "checkedAt",
        "실제 달력의 UTC 시각을 Z 또는 +00:00으로 입력하세요. 미래 여부는 서버가 검증합니다.",
      );
    if (evidenceRef === null) {
      if (result !== "INCOMPLETE")
        manualError("result", "근거 없음은 INCOMPLETE만 가능합니다.");
      for (const [key, entry] of Object.entries({
        modelId,
        effort,
        checkedAt,
        criticalOpenCount: count,
        runRef,
        separateContext,
      }))
        if (entry !== null)
          manualError(key, "미실시 근거에서는 명시적 null이어야 합니다.");
      if (!notes.trim())
        manualError("notes", "실시하지 못한 이유를 정직하게 입력하세요.");
    } else {
      if (checkedAt === null)
        manualError("checkedAt", "근거 확인 시각이 필요합니다.");
      if (count === null)
        manualError("criticalOpenCount", "실제 미해결 건수가 필요합니다.");
      if (kind === "MODEL") {
        if (modelId === null)
          manualError("modelId", "실제 실행한 모델을 입력하세요.");
        if (effort === null)
          manualError("effort", "실제 실행 effort를 입력하세요.");
        if (runRef === null)
          manualError("runRef", "실제 실행 참조가 필요합니다.");
        if (separateContext !== true)
          manualError(
            "separateContext",
            "MODEL 근거는 별도 문맥 true가 필요합니다.",
          );
        if (result === "PASS" && modelId !== "gpt-6-astra")
          manualError("modelId", "새 MODEL PASS 정책은 gpt-6-astra입니다.");
        if (result === "PASS" && effort !== "medium")
          manualError("effort", "새 MODEL PASS 검수 정책은 medium입니다.");
      } else {
        for (const [key, entry] of Object.entries({
          modelId,
          effort,
          runRef,
          separateContext,
        }))
          if (entry !== null)
            manualError(key, "APPROVAL에서는 명시적 null이어야 합니다.");
      }
      if (result === "PASS" && !countZero)
        manualError("criticalOpenCount", "새 PASS는 미해결 0건이어야 합니다.");
    }
    const resolves = [
      ...document.querySelectorAll("[data-manual-resolution]"),
    ].map((row) =>
      Object.fromEntries(
        [...row.querySelectorAll("input, select")].map((input) => [
          input.name,
          input.value,
        ]),
      ),
    );
    if (
      resolves.length > 100 ||
      ((format === "1" || result !== "PASS") && resolves.length)
    )
      manualError(
        "resolves",
        "해결 항목은 형식 2의 PASS에서만 최대 100개까지 입력하세요.",
      );
    const ids = new Set();
    for (const row of resolves) {
      if (
        !savedDecimal(row.recordId, true) ||
        row.recordId.length > 19 ||
        BigInt(row.recordId) > 9223372036854775807n ||
        ids.has(row.recordId) ||
        !["RECORD_CORRECTION", "ISSUE_VERIFIED"].includes(row.reasonCode) ||
        !/^[A-Za-z0-9_-]{8,64}$/.test(row.verificationRef)
      )
        manualError(
          "resolves",
          "중복 없는 양의 bigint 기록 ID·해결 사유·비개인 확인 참조 8~64자를 입력하세요.",
        );
      ids.add(row.recordId);
    }
    const evidenceData = {
      formatNo: Number(format),
      evidenceRef,
      checkedAt,
      criticalOpenCount: null,
      notes,
      runRef,
      separateContext,
    };
    if (format === "2") evidenceData.resolves = resolves;
    // 사용자 JSON은 받지 않는다. 고정 키의 필드별 직렬화로 중복/추가 키를 생성할 수 없다.
    const details = `{${Object.entries(evidenceData)
      .map(
        ([key, entry]) =>
          `${JSON.stringify(key)}:${key === "criticalOpenCount" && count !== null ? count : JSON.stringify(entry)}`,
      )
      .join(",")}}`;
    const body = {
      expectedRev,
      requestKey,
      kind,
      result,
      modelId,
      effort,
      evidence,
    };
    const raw = `${JSON.stringify(body).slice(0, -1)},"evidenceData":${details}}`;
    if (new TextEncoder().encode(raw).length > 131072)
      manualError(
        "evidence",
        "SR05 전체 요청은 UTF-8 128KiB 이하여야 합니다. 저장 wrapper 한도는 서버가 별도로 검사합니다.",
      );
    return raw;
  }

  /**
   * 수동 필드를 공통 컴포넌트로 한 번 구성한다. 실행·시각·null 선택을 대신 채우지 않는다.
   * @returns {void} 실제 라벨·오류·nullable 선택과 입력 이벤트를 연결한다.
   */
  function initializeManualForm() {
    const container = document.getElementById("manual-fields");
    if (!container) return;
    const definitions = [
      ["kind", "기록 종류", ["MODEL", "APPROVAL"]],
      ["result", "검수 결과", ["PASS", "FAIL", "INCOMPLETE"]],
      ["modelId", "실제 모델 ID", null, true],
      ["effort", "실제 effort", null, true],
      ["evidence", "근거 설명 · 최대 20,000자", "textarea"],
      ["evidenceRef", "근거 참조", null, true],
      ["checkedAt", "실제 확인 시각 · UTC", null, true],
      [
        "criticalOpenCount",
        "치명적 미해결 건수 · 정확한 비음수 정수",
        null,
        true,
      ],
      ["notes", "비고 · 최대 4,000자", "textarea"],
      ["runRef", "실제 모델 실행 참조", null, true],
      ["separateContext", "별도 문맥", ["null", "true", "false"]],
      ["formatNo", "근거 저장 형식", ["1", "2"]],
    ];
    for (const [key, title, type, nullable] of definitions) {
      const field = document.createElement("div");
      field.className = "field";
      if (nullable) {
        const label = document.createElement("label");
        label.htmlFor = `manual-${key}-mode`;
        label.textContent = `${title} · 값 또는 null 선택`;
        const mode = document.createElement("select");
        mode.id = label.htmlFor;
        mode.setAttribute("aria-describedby", `manual-${key}-error`);
        for (const [entry, name] of [
          ["", "직접 선택"],
          ["null", "명시적 null"],
          ["value", "값 직접 입력"],
        ])
          mode.add(new Option(name, entry));
        field.append(label, mode);
      }
      const label = document.createElement("label");
      label.htmlFor = `manual-${key}`;
      label.textContent = title;
      const input = document.createElement(
        Array.isArray(type)
          ? "select"
          : type === "textarea"
            ? "textarea"
            : "input",
      );
      input.id = label.htmlFor;
      if (Array.isArray(type))
        for (const entry of ["", ...type])
          input.add(new Option(entry || "직접 선택", entry));
      else input.autocomplete = "off";
      if (nullable) input.disabled = true;
      input.setAttribute("aria-describedby", `manual-${key}-error`);
      const error = document.createElement("p");
      error.id = `manual-${key}-error`;
      error.className = "error";
      error.setAttribute("role", "status");
      field.append(label, input, error);
      container.append(field);
    }
    manualAcknowledged = manualFingerprint();
  }

  /** 해결 항목의 세 필드만 추가한다. 기록 ID나 확인 참조는 자동 생성하지 않는다. */
  function addManualResolution() {
    const container = document.getElementById("manual-resolutions");
    if (container.childElementCount >= 100) return;
    const row = document.createElement("fieldset");
    row.dataset.manualResolution = "";
    const prefix = `manual-resolution-${crypto.randomUUID()}`;
    for (const [name, title] of [
      ["recordId", "해결 대상 기록 ID"],
      ["reasonCode", "해결 사유"],
      ["verificationRef", "해결 확인 참조"],
    ]) {
      const label = document.createElement("label");
      label.htmlFor = `${prefix}-${name}`;
      label.textContent = title;
      const input = document.createElement(
        name === "reasonCode" ? "select" : "input",
      );
      input.id = label.htmlFor;
      input.name = name;
      input.setAttribute("aria-describedby", "manual-resolves-error");
      if (name === "reasonCode")
        for (const entry of ["", "RECORD_CORRECTION", "ISSUE_VERIFIED"])
          input.add(new Option(entry || "직접 선택", entry));
      row.append(label, input);
    }
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "secondary";
    remove.textContent = "이 해결 입력 제거";
    remove.addEventListener("click", () => {
      row.remove();
      manualChanged();
      document.getElementById("manual-resolution-add").focus();
    });
    row.append(remove);
    container.append(row);
    manualChanged();
    row.querySelector("input").focus();
  }

  /** 새 입력은 동의만 취소하며 이미 보낸 정확한 본문과 최소 영수증은 바꾸지 않는다. */
  function manualChanged() {
    if (!manualDraftSnapshot && savedSelection)
      manualDraftSnapshot = savedSelection.snapshotId;
    ++manualInputEpoch;
    if (manualOperation && !manualOperation.sent) AdminUI.cancelConfirmation();
    for (const mode of document.querySelectorAll(
      '#manual-fields select[id$="-mode"]',
    )) {
      const input = document.getElementById(mode.id.slice(0, -5));
      input.disabled = mode.value !== "value";
    }
    syncManualControls();
  }

  /** 선택·입력·신원·권한이 동의 이후에도 같을 때만 상세 쓰기 기준을 명시 수락한다. */
  async function acceptManualBaseline() {
    if (manualOperation || !manualEligible()) return;
    const source = detail;
    const selection = savedSelection;
    const epoch = permissionEpoch;
    const input = manualInputEpoch;
    const rev = detail.editRev;
    const fingerprint = manualFingerprint();
    const fresh = () =>
      source === detail &&
      rev === detail.editRev &&
      selection === savedSelection &&
      epoch === permissionEpoch &&
      input === manualInputEpoch &&
      fingerprint === manualFingerprint() &&
      manualEligible() &&
      !manualOperation;
    if (!(await observeEditorIdentity())) return;
    if (!fresh()) return;
    const accepted = await AdminUI.confirm({
      title: "기록 쓰기 기준 수락",
      message:
        "현재 상세의 수정번호·REVIEW 상태·현재 선택 사본을 확인했습니까? 열람 사본의 sourceRev는 쓰기 번호로 사용하지 않습니다.",
      confirmLabel: "기록 기준 수락",
    });
    if (!(await observeEditorIdentity())) return;
    if (!accepted || !fresh()) return;
    manualBaseline = Object.freeze({
      detail,
      expectedRev: rev,
      snapshotId: selection.snapshotId,
      permissionEpoch,
    });
    document.getElementById("manual-status").textContent =
      "기록 쓰기 기준을 수락했습니다.";
    syncManualControls();
  }

  /**
   * 미전송 후보는 작업이 소유하며 재확인·전송 이후에는 보관 의도도 일치해야 한다.
   * @param {object} op 후보 또는 원래 의도와 현재 논리 신원·경로를 캡처한 단일 작업.
   */
  function ownsManual(op) {
    return (
      manualOperation === op &&
      ((!op.sent && !op.replay) || manualIntent === op.intent) &&
      (op.sent || op.generation === generation) &&
      op.identity === editorIdentity &&
      !editorIdentityRevoked &&
      op.path === editorPath() &&
      !editor.hidden &&
      document.getElementById("unavailable").hidden
    );
  }

  /**
   * 새 후보는 실제 전송 직전에만 보관 의도를 교체하고 원래 정확한 키·본문 재확인은 한 번만 수행한다.
   * @param {boolean} replay true이면 현재 폼으로 원래 본문을 재구성하지 않는다.
   */
  async function submitManual(replay = false) {
    if (manualOperation || resolutionOperation || !detail || editor.hidden) return;
    if (
      replay
        ? !manualIntent?.sent || manualIntent.rejected
        : !manualEligible() ||
          manualBaseline?.detail !== detail ||
          manualBaseline?.expectedRev !== detail.editRev ||
          manualBaseline?.permissionEpoch !== permissionEpoch ||
          manualBaseline?.snapshotId !== savedSelection?.snapshotId ||
          Boolean(manualIntent && !manualIntent.receipt)
    )
      return;
    const message = document.getElementById("manual-status");
    const receipt = document.getElementById("manual-receipt");
    let intent = manualIntent;
    if (!replay) {
      try {
        const key = crypto.randomUUID();
        intent = {
          command: Object.freeze({
            path: editorPath(),
            snapshotId: savedSelection.snapshotId,
            expectedRev: manualBaseline.expectedRev,
            requestKey: key,
            body: manualBody(manualBaseline.expectedRev, key),
            fingerprint: manualFingerprint(),
          }),
          sent: false,
        };
      } catch (error) {
        if (error.code === "MANUAL_INPUT")
          message.textContent = "입력 오류를 확인하세요. 전송하지 않았습니다.";
        return;
      }
    }
    const command = intent.command;
    const op = {
      intent,
      path: editorPath(),
      identity: editorIdentity,
      source: detail,
      rev: detail.editRev,
      selection: savedSelection,
      input: manualFingerprint(),
      epoch: manualInputEpoch,
      permissionEpoch,
      generation,
      replay,
      sent: false,
    };
    manualOperation = op;
    const fresh = () =>
      ownsManual(op) &&
      op.source === detail &&
      op.rev === detail.editRev &&
      op.generation === generation &&
      op.selection === savedSelection &&
      op.epoch === manualInputEpoch &&
      op.input === manualFingerprint() &&
      op.permissionEpoch === permissionEpoch &&
      (replay || (manualEligible() && manualBaseline?.detail === detail));
    syncManualControls();
    try {
      if (!(await observeEditorIdentity()) || !fresh()) return;
      const accepted = await AdminUI.confirm({
        title: replay ? "원래 기록 추가·재확인" : "검수 의견 추가",
        message: replay
          ? "원래 대상·수정번호·키·정확한 본문으로 POST합니다. 처음 요청이 저장되지 않았다면 지금 추가될 수 있습니다. 현재 폼은 전송하지 않습니다."
          : "입력한 실제 근거로 기록 한 건을 추가합니까? 원고·수정번호·상태·현재 사본은 변경하지 않으며 품질 인증이나 공개가 아닙니다.",
        confirmLabel: replay ? "원래 본문 추가·재확인" : "검수 의견 추가",
      });
      if (!(await observeEditorIdentity()) || !accepted || !fresh()) return;
      const response = await request(
        "POST",
        `${command.path}/review-snapshots/${command.snapshotId}/records`,
        undefined,
        false,
        undefined,
        (phase) => {
          if (!ownsManual(op)) return false;
          if (phase === "send") {
            if (!fresh()) return false;
            manualIntent = intent;
            op.sent = intent.sent = true;
            message.textContent =
              "검수 의견 전송 중 · 자동 재전송하지 않습니다.";
            syncManualControls();
          }
          return true;
        },
        false,
        command.body,
      );
      if (!ownsManual(op)) return;
      const result = response.data;
      const keys = [
        "recordId",
        "snapshotId",
        "current",
        "replayed",
        "requestId",
      ];
      if (
        !result ||
        Object.keys(result).length !== keys.length ||
        keys.some((key) => !Object.hasOwn(result, key)) ||
        !savedDecimal(result.recordId, true) ||
        result.recordId.length > 19 ||
        BigInt(result.recordId) > 9223372036854775807n ||
        result.snapshotId !== command.snapshotId ||
        typeof result.current !== "boolean" ||
        typeof result.replayed !== "boolean" ||
        typeof result.requestId !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          result.requestId,
        ) ||
        (intent.receipt &&
          (result.recordId !== intent.receipt.recordId || !result.replayed)) ||
        (result.replayed
          ? response.status !== 200
          : response.status !== 201 || !result.current)
      )
        throw { status: 503 };
      intent.receipt = Object.freeze({ ...result });
      intent.unknown = false;
      manualAcknowledged = command.fingerprint;
      receipt.dataset.state = "confirmed";
      receipt.textContent = `기록 영수증 확정 · recordId ${result.recordId} · 사본 ${result.snapshotId} · current=${result.current} · replayed=${result.replayed} · requestId ${result.requestId}. 현재 자격·품질 판정이 아닙니다.`;
      message.textContent =
        "기록 영수증을 확인했습니다. 새 입력은 유지하며 민감 이력·현재 기준을 자동 조회하지 않습니다.";
    } catch (error) {
      if (!ownsManual(op)) return;
      if (op.sent) {
        const rejected = error.status >= 400 && error.status < 500;
        if (!rejected && !intent.receipt) intent.unknown = true;
        if (
          rejected &&
          !intent.unknown &&
          !intent.receipt &&
          error.code !== "CSRF_INVALID"
        )
          intent.rejected = true;
        if (!intent.receipt) {
          receipt.dataset.state = intent.unknown ? "unknown" : "rejected";
          receipt.textContent = intent.unknown
            ? "원래 기록 결과 미확인 · 이후 거절·SR06 조회로 이전 결과를 확정하지 않습니다. 원래 키·본문을 유지합니다."
            : "이번 기록 요청 거절 · 저장 성공이 아닙니다. 현재 GET·비교 수락 후 입력을 고쳐 새 의도를 결정하세요.";
        }
        message.textContent =
          error.code === "CSRF_INVALID"
            ? "CSRF 확인 거절 · 원래 키·본문 유지. 사용자 재확인 때만 새 토큰을 얻으며 자동 전송하지 않습니다."
            : `이번 기록 ${rejected ? "요청 거절" : "결과 미확인"} · ${errorMessage(error)} 원래 영수증·미확인 결과와 별개입니다.`;
      }
    } finally {
      if (manualOperation === op) {
        manualOperation = undefined;
        if (!op.sent) {
          message.textContent =
            "전송하지 않았습니다. 현재 입력·선택·권한으로 다시 결정하세요.";
        }
        syncManualControls();
      }
    }
  }

  /** 미확인 결과를 실패로 바꾸지 않고 원래 키를 잃는 영향을 명시 동의한 경우에만 폐기한다. */
  async function discardManual() {
    if (manualOperation) return;
    const intent = manualIntent;
    const input = manualFingerprint();
    const epoch = manualInputEpoch;
    const current = generation;
    const selection = savedSelection;
    const permissions = permissionEpoch;
    const fresh = () =>
      !manualOperation &&
      intent === manualIntent &&
      input === manualFingerprint() &&
      epoch === manualInputEpoch &&
      current === generation &&
      selection === savedSelection &&
      permissions === permissionEpoch;
    if (!(await observeEditorIdentity())) return;
    if (!fresh()) return;
    const accepted = await AdminUI.confirm({
      title: "기록 입력·보관 의도 폐기",
      message:
        "입력과 원래 키·본문을 이 페이지에서 버립니까? 서버 기록은 취소되지 않습니다. 미확인 결과도 실패로 확정되지 않으며 원래 요청 재확인은 불가능해집니다.",
      confirmLabel: "기록 입력·의도 폐기",
    });
    if (!(await observeEditorIdentity())) return;
    if (!fresh()) return;
    if (!accepted) {
      document.getElementById("manual-status").textContent =
        "기록 폐기를 취소했습니다. 입력·원래 의도는 유지됩니다.";
      return;
    }
    manualIntent = manualBaseline = undefined;
    manualDraftSnapshot = undefined;
    document.getElementById("manual-form").reset();
    document.getElementById("manual-resolutions").replaceChildren();
    for (const node of document.querySelectorAll("#manual-form .error"))
      node.textContent = "";
    document.getElementById("manual-receipt").textContent = "";
    document.getElementById("manual-receipt").removeAttribute("data-state");
    manualChanged();
    manualAcknowledged = manualFingerprint();
    document.getElementById("manual-status").textContent =
      "기록 입력·보관 의도를 명시 폐기했습니다. 서버 결과는 변경하지 않았습니다.";
  }

  /**
   * 실제 Me의 UUID와 고정 절대 기한만 편집기 소유 문맥으로 관측한다.
   * 전역 권한·rolling 기한·프레임워크 세션 ID는 비교하지 않으며 고유 세션 증명도 아니다.
   * @param {boolean} initialize 최초 상세 조회 전에만 true. 폐기한 문맥은 다시 결속하지 않는다.
   * @returns {Promise<boolean>} 같은 소유 문맥이면 true, 실패·변경이면 민감 입력을 폐기하고 false.
   */
  async function observeEditorIdentity(initialize = false) {
    if (editorIdentityRevoked) return false;
    const previous = editorIdentity;
    try {
      const response = await fetch("/admin/api/auth/me", {
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
      if (!response.ok) throw new Error("신원 조회 실패");
      const me = await response.json();
      if (editorIdentityRevoked) return false;
      if (
        !me ||
        typeof me !== "object" ||
        Array.isArray(me) ||
        typeof me.accountKey !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
          me.accountKey,
        ) ||
        typeof me.absoluteExpiresAt !== "string" ||
        !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(
          me.absoluteExpiresAt,
        ) ||
        !Number.isFinite(Date.parse(me.absoluteExpiresAt)) ||
        new Date(me.absoluteExpiresAt).toISOString().slice(0, 19) !==
          me.absoluteExpiresAt.slice(0, 19)
      )
        throw new Error("신원 형식 미확인");
      if (!previous && initialize && !editorIdentity) {
        editorIdentity = Object.freeze({
          accountKey: me.accountKey,
          absoluteExpiresAt: me.absoluteExpiresAt,
        });
        return true;
      }
      if (
        !previous ||
        previous !== editorIdentity ||
        previous.accountKey !== me.accountKey ||
        previous.absoluteExpiresAt !== me.absoluteExpiresAt
      )
        throw new Error("편집기 소유 문맥 변경");
      return true;
    } catch {
      if (!editorIdentityRevoked)
        revoke(
          "편집기 신원이 바뀌었거나 확인되지 않아 이전 입력과 검수 의도를 폐기했습니다.",
          401,
        );
      return false;
    }
  }

  /**
   * 반환 입력의 명시 비우기와 실제 확정 본문을 구분하여 메모리의 새 입력만 보호한다.
   * @returns {boolean} 비어 있지 않고 확정 본문과도 다른 사유·참조가 있는지 여부.
   */
  function returnHasDrafts() {
    return [
      "review-withdraw-ref",
      "review-changes-ref",
      "review-changes-reason",
    ].some((id) => {
      const value = document.getElementById(id)?.value;
      return Boolean(value && value !== acknowledgedReturnFields[id]);
    });
  }

  /**
   * 서버의 정확한 세 boolean만 복사하며 누락·추가·잘못된 형식은 권한 미확인으로 처리한다.
   * @param {unknown} value VersionDetail.permissions. 대체 권한이나 기본 승인은 없다.
   * @returns {object|null} 검증된 복사본 또는 쓰기 차단을 뜻하는 null.
   */
  function copyPermissions(value) {
    const keys = ["edit", "review", "publish"];
    if (
      !value ||
      typeof value !== "object" ||
      Array.isArray(value) ||
      Object.keys(value).length !== keys.length ||
      keys.some(
        (key) => !Object.hasOwn(value, key) || typeof value[key] !== "boolean",
      )
    )
      return null;
    return Object.fromEntries(keys.map((key) => [key, value[key]]));
  }

  /**
   * 현재 응답의 권한 상실은 읽기 메타 폐기 뒤에도 유지하고 오래된 동의만 취소한다.
   * @param {unknown} value 현재 소유권을 확인한 상세 GET의 권한 객체.
   * @returns {object|null} 원본 응답과 분리한 검증 결과.
   */
  function observePermissions(value) {
    const permissions = copyPermissions(value);
    if (JSON.stringify(permissions) !== JSON.stringify(observedPermissions)) {
      ++permissionEpoch;
      AdminUI.cancelConfirmation();
      clearSavedIssues();
      if (lifecycleIntent && !lifecycleIntent.sent) lifecycleIntent = undefined;
    }
    observedPermissions = permissions;
    for (const key of ["edit", "review", "publish"])
      if (permissions?.[key] !== true) permissionDenied[key] = true;
    if (latest && latest.permissionEpoch !== permissionEpoch)
      document.getElementById("accept-latest").disabled = true;
    syncPermissionControls();
    syncSavedIssueControls();
    syncCloneControls();
    return permissions;
  }

  /**
   * 현재 관측과 명시적으로 수락한 기준의 권한을 함께 요구하며 상태를 권한으로 추정하지 않는다.
   * @param {string} action edit/review/publish 중 행동 권한.
   * @returns {boolean} 현재 관측과 수락한 기준 모두에서 허용되고 거절 울타리가 없는지 여부.
   */
  function hasPermission(action) {
    return (
      detail?.permissions?.[action] === true &&
      observedPermissions?.[action] === true &&
      !permissionDenied[action]
    );
  }

  /** 권한만 바뀌어도 기존 원고 버퍼를 유지한 채 수정 도구와 안내를 갱신한다. */
  function syncPermissionControls() {
    const message = document.getElementById("review-permissions");
    if (!message) return;
    message.textContent = !observedPermissions
      ? "서버 권한 응답 미확인 · 모든 쓰기가 차단됩니다. 현재 상세를 다시 조회·비교하세요."
      : `서버 현재 권한 · 편집 ${observedPermissions.edit ? "있음" : "없음"} · 검수 ${observedPermissions.review ? "있음" : "없음"} · 공개 ${observedPermissions.publish ? "있음" : "없음"}. 권한은 품질 판정이나 실행 허가가 아닙니다.${Object.values(permissionDenied).some(Boolean) ? " 관측한 권한 거절은 최신 기준을 명시적으로 수락하기 전까지 유지됩니다." : ""}`;
    if (!detail) return;
    for (const form of editor.querySelectorAll("[data-section]")) {
      const writable = writableSection(form.dataset.section);
      for (const mode of form.querySelectorAll("[data-field]")) {
        const input = mode.closest(".field").querySelector("[data-value]");
        const fixed = mode.closest(".field").dataset.fixed === "true";
        mode.disabled = !writable || fixed;
        input.readOnly = !writable || fixed || mode.value !== "value";
        if (input.tagName === "SELECT") input.disabled = input.readOnly;
      }
    }
    if (editor.querySelector('[data-section="child"] button'))
      syncChildControls();
  }

  /** 쓰기 기준과 분리해 명시적으로 재확인한 읽기 메타를 우선하며 원고·권한은 추정하지 않는다. */
  function savedReadMetadata() {
    return savedCurrent || detail;
  }

  /** 매개변수 없이 출처의 구조 조건만 확인한다. 서버 인가를 대신하지 않는다. */
  function savedDraftEligible() {
    const data = savedReadMetadata();
    return Boolean(
      data?.status === "DRAFT" &&
      data.storyActiveYn === true &&
      data.activeYn === true &&
      data.currentSnapshotId === null,
    );
  }

  /** 명시적 읽기 재확인은 쓰기 잠금·비교·영수증 미확인을 해제하지 않고 열람만 복구한다. */
  function savedReadReady() {
    return Boolean(
      detail &&
      !editor.hidden &&
      !savedMetadataOperation &&
      !reviewOperation &&
      !lifecycleOperation &&
      (savedCurrent ||
        (!saveLocked &&
          !savedDetailPending &&
          !reviewNeedsRead &&
          document.getElementById("comparison").hidden)) &&
      document.getElementById("unavailable").hidden,
    );
  }

  /**
   * 읽기 메타 재확인의 진행·실패를 표시하며 원문이나 쓰기 영수증을 덮어쓰지 않는다.
   * @param {string} state stale/loading/success/failure 중 현재 조회 상태.
   * @param {string} message 원문을 포함하지 않는 안내.
   */
  function savedMetadataStatus(state, message) {
    const node = document.getElementById("saved-metadata-status");
    if (!node) return;
    node.dataset.state = state;
    node.textContent = message;
    node.classList.toggle("error", state === "failure");
    document
      .getElementById("saved-review-current")
      .setAttribute("aria-busy", String(state === "loading"));
  }

  /**
   * 현재 버전 GET 한 번으로 열람용 메타만 재확인하며 원고·쓰기 수정번호·영수증은 유지한다.
   * @returns {Promise<void>} 실패는 명시적으로 표시하고 재전송·자식 조회·자동 열람은 하지 않는다.
   */
  async function reconcileSavedMetadata() {
    if (
      !detail ||
      editor.hidden ||
      reviewOperation ||
      lifecycleOperation ||
      savedMetadataOperation ||
      !(saveLocked || reviewNeedsRead) ||
      !document.getElementById("unavailable").hidden
    )
      return;
    lockForReview(
      "열람 기준만 다시 확인합니다. 원고 입력과 쓰기 잠금은 유지됩니다.",
    );
    const op = {
      generation,
      detail,
      path: editorPath(),
      epoch: savedReadEpoch,
    };
    savedMetadataOperation = op;
    /** 현재 상세·경로·세대가 일치하는 재확인만 반영한다. 입력 수정은 버퍼를 교체하지 않으므로 허용한다. */
    const owns = () =>
      savedMetadataOperation === op &&
      op.generation === generation &&
      op.detail === detail &&
      op.path === editorPath() &&
      op.epoch === savedReadEpoch &&
      !editor.hidden &&
      document.getElementById("unavailable").hidden;
    savedMetadataStatus(
      "loading",
      "현재 버전의 열람 기준을 조회 중입니다. 쓰기 기준은 변경하지 않습니다.",
    );
    syncSavedReviewControls();
    try {
      const data = await request(
        "GET",
        op.path,
        undefined,
        false,
        undefined,
        owns,
      );
      if (!owns()) return;
      const permissions = observePermissions(data.permissions);
      if (
        !permissions ||
        data.storyCode !== op.detail.storyCode ||
        data.versionNo !== op.detail.versionNo ||
        !savedDecimal(data.editRev) ||
        !Object.hasOwn(versionStates, data.status) ||
        typeof data.storyActiveYn !== "boolean" ||
        typeof data.activeYn !== "boolean" ||
        !(
          data.currentSnapshotId === null ||
          savedDecimal(data.currentSnapshotId, true)
        )
      )
        throw { status: 503 };
      savedCurrent = {
        storyCode: data.storyCode,
        versionNo: data.versionNo,
        editRev: data.editRev,
        status: data.status,
        storyActiveYn: data.storyActiveYn,
        activeYn: data.activeYn,
        currentSnapshotId: data.currentSnapshotId,
        permissions,
      };
      savedMetadataStatus(
        "success",
        "현재 GET의 열람 기준만 확인했습니다. 원고·쓰기 수정번호·잠금은 유지됩니다. 이 조회는 사람 확인 영수증이 아닙니다.",
      );
    } catch (error) {
      if (!owns()) return;
      savedMetadataStatus(
        "failure",
        "현재 열람 기준 조회 실패. 저장 자료는 미확인이며 쓰기는 계속 잠겨 있습니다. 명시적으로 다시 조회하세요.",
      );
    } finally {
      op.detail = undefined;
      if (savedMetadataOperation === op) savedMetadataOperation = undefined;
      syncReviewControls();
    }
  }

  /**
   * 한 조회 영역의 명시 상태와 접근성 안내를 표시한다.
   * @param {string} lane history/payload/records/preview/issues 중 고정 영역.
   * @param {string} state unopened/loading/success-empty/success-populated/stale/failure.
   * @param {string} message 원문을 포함하지 않는 상태 안내.
   */
  function savedReadStatus(lane, state, message) {
    const node = document.getElementById(`saved-${lane}-status`);
    if (!node) return;
    node.dataset.state = state;
    node.textContent = message;
    node.classList.toggle("error", state === "failure");
    document
      .getElementById(`saved-${lane}-result`)
      .setAttribute("aria-busy", String(state === "loading"));
  }

  /**
   * 선택·투영 조건 교체 때 민감 DOM과 진행 중 응답 소유권을 즉시 폐기한다.
   * @param {string} state 기본 stale이며 최초 상세에서는 unopened를 사용한다.
   */
  function clearSavedPrivate(state = "stale") {
    ++lifecycleSourceEpoch;
    if (lifecycleOperation) AdminUI.cancelConfirmation();
    clearSavedIssues(state);
    savedRecordsCursor = undefined;
    for (const lane of ["payload", "records", "preview"]) {
      if (savedReads[lane])
        savedReads[lane].detail =
          savedReads[lane].metadata =
          savedReads[lane].selection =
            undefined;
      delete savedReads[lane];
      const node = document.getElementById(`saved-${lane}-result`);
      if (!node) continue;
      node.replaceChildren();
      node.hidden = lane !== "preview";
      savedReadStatus(
        lane,
        state,
        state === "unopened"
          ? "아직 조회하지 않았습니다."
          : "이전 열람 내용은 폐기했습니다. 현재 선택으로 명시적으로 다시 조회하세요.",
      );
    }
    document
      .getElementById("saved-preview-role")
      ?.removeAttribute("aria-invalid");
  }

  /**
   * 편집 기준 교체·비교·접근 종료는 목록과 선택까지 폐기하며 원고 입력은 건드리지 않는다.
   * @param {string} state 기본 stale이며 실패 또는 새 상세에서는 해당 상태를 명시한다.
   * @param {boolean} metadata 기본 true. 이력 페이징만 false로 열람 메타를 유지한다.
   */
  function resetSavedReview(state = "stale", metadata = true) {
    manualBaseline = undefined;
    if (metadata) {
      if (savedMetadataOperation) savedMetadataOperation.detail = undefined;
      savedCurrent = savedMetadataOperation = undefined;
      savedMetadataStatus(
        "stale",
        "쓰기 비교와 별도로 현재 열람 기준만 다시 확인할 수 있습니다.",
      );
    }
    ++savedReadEpoch;
    for (const op of Object.values(savedReads))
      op.detail = op.metadata = op.selection = undefined;
    savedReads = {};
    savedSelection = savedHistoryCursor = undefined;
    clearSavedPrivate(state);
    document.getElementById("saved-history-result")?.replaceChildren();
    document.getElementById("saved-review-selected")?.replaceChildren();
    document.getElementById("saved-review-current")?.replaceChildren();
    savedReadStatus(
      "history",
      state,
      state === "unopened"
        ? "아직 조회하지 않았습니다."
        : "이전 이력과 선택은 폐기했습니다. 처음 20건부터 다시 조회하세요.",
    );
    syncSavedReviewControls();
  }

  /** 매개변수 없이 확인한 현재 버전과 별도 선택에 맞춰 조회만 제어한다. 서버 인가를 대신하지 않는다. */
  function syncSavedReviewControls() {
    if (!document.getElementById("saved-review")) return;
    const ready = savedReadReady();
    const data = savedReadMetadata();
    const refresh = document.getElementById("saved-metadata-refresh");
    refresh.hidden = !(saveLocked || reviewNeedsRead);
    refresh.disabled =
      !detail ||
      editor.hidden ||
      Boolean(
        reviewOperation || lifecycleOperation || savedMetadataOperation,
      ) ||
      !document.getElementById("unavailable").hidden;
    const current = document.getElementById("saved-review-current");
    current.replaceChildren();
    if (ready) {
      for (const [label, value] of [
        [
          "열람 기준 출처",
          savedCurrent
            ? "명시적 현재 GET · 쓰기 기준과 별도"
            : "편집기 상세 GET",
        ],
        ["현재 상세 수정번호", data.editRev],
        ["현재 상세 상태", data.status],
        [
          "현재 상세 currentSnapshotId",
          data.currentSnapshotId ?? "null (현재 사본 없음)",
        ],
        ["사건 / 버전 활성", `${data.storyActiveYn} / ${data.activeYn}`],
        ["원고 쓰기 기준 수정번호 (자동 변경 없음)", detail.editRev],
      ])
        labelValue(current, label, value);
    }
    document.getElementById("saved-history-first").disabled =
      !ready || Boolean(savedReads.history);
    document.getElementById("saved-history-next").disabled =
      !ready || Boolean(savedReads.history) || !savedHistoryCursor;
    for (const button of document.querySelectorAll("[data-snapshot-id]"))
      button.disabled = !ready || Boolean(savedReads.history);
    document.getElementById("saved-payload-read").disabled =
      !ready || !savedSelection || Boolean(savedReads.payload);
    document.getElementById("saved-records-first").disabled =
      !ready || !savedSelection || Boolean(savedReads.records);
    document.getElementById("saved-records-next").disabled =
      !ready ||
      !savedSelection ||
      Boolean(savedReads.records) ||
      !savedRecordsCursor;
    const source = document.getElementById("saved-preview-source");
    const mode = document.getElementById("saved-preview-mode");
    source.disabled = mode.disabled = !ready;
    document.getElementById("saved-preview-role").disabled =
      !ready || mode.value !== "ROLE";
    document.getElementById("saved-preview-eligibility").textContent =
      ready && !savedDraftEligible()
        ? "현재 열람 기준은 DRAFT 출처 조건을 충족하지 않습니다. 사본 이력·선택 사본 열람은 별도로 이용하세요."
        : "DRAFT 출처는 활성 사건·활성 DRAFT 버전이며 현재 사본이 없을 때만 조회합니다.";
    document.getElementById("saved-preview-read").disabled =
      !ready ||
      Boolean(savedReads.preview) ||
      (source.value === "SNAPSHOT" && !savedSelection) ||
      (source.value === "DRAFT" && !savedDraftEligible());
    syncSavedIssueControls();
    syncManualControls();
  }

  /**
   * 이력의 관측 권한만 검사한다. 쓰기 거절 울타리·현재 상태·runtime을 조회 조건으로 쓰지 않는다.
   * @returns {boolean} 정확한 서버 읽기 메타와 최근 관측에서 REVIEW가 모두 true인지 여부.
   */
  function savedIssuePermission() {
    return (
      copyPermissions(savedReadMetadata()?.permissions)?.review === true &&
      observedPermissions?.review === true
    );
  }

  /**
   * 이력 한 영역만 폐기한다. 수동 기록·해소의 영수증·보관 의도·새 입력과 전환 의도는 유지한다.
   * @param {string} state 기본 stale, 새 상세는 unopened.
   */
  function clearSavedIssues(state = "stale") {
    if (resolutionOperation && !resolutionOperation.sent) AdminUI.cancelConfirmation();
    const op = savedReads.issues;
    if (op) op.detail = op.metadata = op.selection = op.identity = undefined;
    delete savedReads.issues;
    savedIssuesPage = undefined;
    document.getElementById("issue-resolution-choices")?.replaceChildren();
    const output = document.getElementById("saved-issues-result");
    if (!output) return;
    output.replaceChildren();
    output.hidden = true;
    savedReadStatus(
      "issues",
      state,
      state === "unopened"
        ? "아직 조회하지 않았습니다."
        : "이전 실행 지적 페이지를 폐기했습니다. 현재 선택·조건으로 직접 조회하세요.",
    );
  }

  /** 선택·필터·관측 권한에 결속된 조회 버튼만 제어하며 자동 요청은 만들지 않는다. */
  function syncSavedIssueControls() {
    const filter = document.getElementById("saved-issues-filter");
    if (!filter) return;
    const owner = savedReads.issues || savedIssuesPage;
    if (owner && owner.pathname !== location.pathname) clearSavedIssues();
    const ready =
      savedReadReady() &&
      location.pathname ===
        `/admin/stories/${detail.storyCode}/versions/${detail.versionNo}`;
    const permitted = savedIssuePermission();
    const selected = savedIssueDecimal(savedSelection?.snapshotId);
    const validFilter = ["ALL", "OPEN", "RESOLVED"].includes(filter.value);
    const busy = Boolean(savedReads.issues);
    filter.disabled = !ready || !permitted || !selected;
    document.getElementById("saved-issues-first").disabled =
      !ready || !permitted || !selected || !validFilter || busy;
    document.getElementById("saved-issues-next").disabled =
      !ready ||
      !permitted ||
      !selected ||
      !validFilter ||
      busy ||
      !savedIssuesPage?.cursor ||
      savedIssuesPage.selection !== savedSelection ||
      savedIssuesPage.filter !== filter.value;
    document.getElementById("saved-issues-eligibility").textContent = !permitted
      ? "실행 지적 조회 불가 · 서버 REVIEW 권한이 없거나 미확인입니다. 현재 상세를 다시 확인하세요. 매 페이지 서버가 실제 자격을 재검증합니다."
      : !ready
        ? "현재 열람 기준이 미확인입니다. 기존 상세 또는 열람 기준을 명시적으로 다시 확인하세요."
        : !selected
          ? "사본 이력에서 조회할 불변 사본을 먼저 선택하세요. 선택만으로 요청하지 않습니다."
          : `선택 사본 ${savedSelection.snapshotId} · 고정 sourceRev ${savedSelection.sourceRev} · 목록 조회 당시 ${savedSelection.current ? "현재" : "과거"} 사본 · ${filter.value} 조건. 관측 권한은 서버 인가나 품질 판정을 대신하지 않습니다.`;
    syncResolutionControls();
  }

  /**
   * 양의 정규 BIGINT 십진 문자열만 허용하고 Number 변환을 하지 않는다.
   * @param {unknown} value 선택 ID 또는 서버 커서.
   * @returns {boolean} 1부터 9223372036854775807까지의 문자열 여부.
   */
  function savedIssueDecimal(value) {
    return (
      savedDecimal(value, true) &&
      (value.length < 19 ||
        (value.length === 19 && value <= "9223372036854775807"))
    );
  }

  /**
   * BATCH 이력의 필수 nullable 필드를 검사한 뒤 닫힌 메타만 복사한다.
   * @param {object} data 서버의 한 페이지. 추가 원문 필드는 표시·보관하지 않는다.
   * @param {object} op 선택 사본·필터·배타 커서를 고정한 요청 소유권.
   * @returns {object[]} 검증된 허용 항목이며 null과 누락을 구분한다.
   * @throws {object} 필수 필드·관계·커서 오류는 빈 성공이 아닌 조회 실패.
   */
  function savedIssueItems(data, op) {
    /** 서버 UUID 필드가 문자열인지 검사하며 숫자나 누락 값을 보정하지 않는다. */
    const uuid = (value) =>
      typeof value === "string" &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
        value,
      );
    /** 명시 시차가 있는 실제 달력 시각만 허용하고 원래 문자열은 그대로 보존한다. */
    const timestamp = (value) =>
      typeof value === "string" &&
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(
        value,
      ) &&
      Number.isFinite(Date.parse(value)) &&
      new Date(`${value.slice(0, 19)}Z`).toISOString().slice(0, 19) ===
        value.slice(0, 19);
    if (
      !data ||
      !Array.isArray(data.items) ||
      data.items.length > 20 ||
      !uuid(data.requestId) ||
      !(
        data.nextCursor === null ||
        (savedIssueDecimal(data.nextCursor) &&
          data.items.length === 20 &&
          (!op.cursor || BigInt(data.nextCursor) < BigInt(op.cursor)))
      )
    )
      throw { status: 503 };
    const keys = [
      "issueKey",
      "snapshotId",
      "runtimeConfigId",
      "sourceBatchKey",
      "sourceReviewId",
      "kind",
      "severity",
      "state",
      "createdAt",
      "resolvedAt",
      "resolvedBy",
      "targetBatchKey",
      "targetReviewId",
    ];
    const seen = new Set();
    return data.items.map((item) => {
      if (
        !item ||
        !uuid(item.issueKey) ||
        seen.has(item.issueKey.toLowerCase()) ||
        item.snapshotId !== op.selection.snapshotId ||
        typeof item.runtimeConfigId !== "string" ||
        !item.runtimeConfigId ||
        !uuid(item.sourceBatchKey) ||
        item.sourceReviewId !== null ||
        item.targetReviewId !== null ||
        !["CONTENT", "GRADING", "INFRA", "OBSERVATION"].includes(item.kind) ||
        !["CRITICAL", "MINOR"].includes(item.severity) ||
        !["OPEN", "RESOLVED"].includes(item.state) ||
        (op.filter !== "ALL" && item.state !== op.filter) ||
        !timestamp(item.createdAt)
      )
        throw { status: 503 };
      seen.add(item.issueKey.toLowerCase());
      let resolution = null;
      if (item.state === "OPEN") {
        if (
          item.resolvedAt !== null ||
          item.resolvedBy !== null ||
          item.targetBatchKey !== null ||
          item.resolution !== null
        )
          throw { status: 503 };
      } else {
        const value = item.resolution;
        if (
          !timestamp(item.resolvedAt) ||
          !uuid(item.resolvedBy) ||
          !uuid(item.targetBatchKey) ||
          !value ||
          Array.isArray(value) ||
          Object.keys(value).length !== 2 ||
          !Object.hasOwn(value, "reasonCode") ||
          !Object.hasOwn(value, "verificationRef") ||
          ![
            "GRADING_FIX_VERIFIED",
            "INFRA_RECOVERED",
            "OBSERVATION_COMPLETED",
          ].includes(value.reasonCode) ||
          typeof value.verificationRef !== "string" ||
          !/^[A-Za-z0-9_-]{8,64}$/.test(value.verificationRef)
        )
          throw { status: 503 };
        resolution = {
          reasonCode: value.reasonCode,
          verificationRef: value.verificationRef,
        };
      }
      return {
        ...Object.fromEntries(keys.map((key) => [key, item[key]])),
        resolution,
      };
    });
  }

  /**
   * PT-A11 한 페이지를 명시적으로 대체한다. 읽기 실패는 쓰기·수동 영수증을 변경하지 않는다.
   * @param {boolean} next 기본 false, true는 같은 선택·조건에 반환된 커서만 사용한다.
   * @returns {Promise<void>} 유실·감사 실패·형식 오류는 자동 재전송 없이 표시한다.
   */
  async function readSavedIssues(next = false) {
    const filter = document.getElementById("saved-issues-filter").value;
    if (
      !savedReadReady() ||
      !savedIssuePermission() ||
      savedReads.issues ||
      location.pathname !==
        `/admin/stories/${detail.storyCode}/versions/${detail.versionNo}` ||
      !savedIssueDecimal(savedSelection?.snapshotId) ||
      !["ALL", "OPEN", "RESOLVED"].includes(filter)
    )
      return;
    const cursor = next ? savedIssuesPage?.cursor : undefined;
    if (
      next &&
      (!cursor ||
        savedIssuesPage.selection !== savedSelection ||
        savedIssuesPage.filter !== filter)
    )
      return;
    clearSavedIssues();
    const op = {
      lane: "issues",
      epoch: savedReadEpoch,
      generation,
      detail,
      path: editorPath(),
      metadata: savedReadMetadata(),
      selection: savedSelection,
      filter,
      cursor,
      permissionEpoch,
      identity: editorIdentity,
      pathname: location.pathname,
    };
    savedReads.issues = op;
    savedReadStatus(
      "issues",
      "loading",
      "선택 사본의 실행 지적 한 페이지를 조회 중입니다.",
    );
    syncSavedIssueControls();
    try {
      const query = new URLSearchParams({
        snapshotId: op.selection.snapshotId,
        size: "20",
      });
      if (filter !== "ALL") query.set("state", filter);
      if (cursor) query.set("cursor", cursor);
      const data = await request(
        "GET",
        `${op.path}/execution-issues?${query}`,
        undefined,
        false,
        undefined,
        () => ownsSavedRead(op),
      );
      if (!ownsSavedRead(op)) return;
      const items = savedIssueItems(data, op);
      const output = document.getElementById("saved-issues-result");
      output.textContent = JSON.stringify(items, null, 2);
      output.hidden = items.length === 0;
      savedIssuesPage = {
        cursor: data.nextCursor,
        selection: op.selection,
        filter,
        pathname: op.pathname,
        path: op.path,
        identity: op.identity,
        items,
      };
      renderResolutionChoices();
      savedReadStatus(
        "issues",
        items.length ? "success-populated" : "success-empty",
        `조회 성공 · 사본 ${op.selection.snapshotId} · sourceRev ${op.selection.sourceRev} · 목록 조회 당시 ${op.selection.current ? "현재" : "과거"} · ${filter} · 이번 페이지 ${items.length}건 · ${data.nextCursor ? "다음 페이지 있음" : "다음 페이지 없음"}. 전체 이력·통과·치명 지적 없음·해소·READY·공개 가능의 증거가 아닙니다.`,
      );
    } catch (error) {
      if (!ownsSavedRead(op)) return;
      clearSavedIssues("failure");
      savedReadStatus(
        "issues",
        "failure",
        "실행 지적 조회 실패 · 필수 열람 감사 또는 응답을 확인하지 못했습니다. 빈 성공이 아닙니다. 자동 재시도하지 않으며 직접 처음 20건을 다시 조회할 수 있습니다.",
      );
    } finally {
      op.detail = op.metadata = op.selection = op.identity = undefined;
      if (savedReads.issues === op) delete savedReads.issues;
      syncSavedIssueControls();
    }
  }

  /**
   * 수·ID를 반올림하지 않고 정규 십진 문자열 여부만 검사한다.
   * @param {unknown} value 서버 ID 또는 수정번호.
   * @param {boolean} positive ID/커서는 true, 수정번호는 기본 false.
   * @returns {boolean} 문자열 계약 충족 여부.
   */
  function savedDecimal(value, positive = false) {
    return (
      typeof value === "string" &&
      (positive ? /^[1-9][0-9]*$/ : /^(0|[1-9][0-9]*)$/).test(value)
    );
  }

  /**
   * 목록에서 보관할 여섯 메타 필드만 복사하며 payload는 보관하지 않는다.
   * @param {object} item SnapshotSummary 서버 응답.
   * @throws {object} 계약 불일치는 원문 없는 조회 실패.
   */
  function savedSummary(item) {
    if (
      !item ||
      !savedDecimal(item.snapshotId, true) ||
      !savedDecimal(item.sourceRev) ||
      !Number.isInteger(item.formatNo) ||
      typeof item.current !== "boolean" ||
      typeof item.createdAt !== "string" ||
      typeof item.createdBy !== "string"
    )
      throw { status: 503 };
    return {
      snapshotId: item.snapshotId,
      sourceRev: item.sourceRev,
      formatNo: item.formatNo,
      createdAt: item.createdAt,
      createdBy: item.createdBy,
      current: item.current,
    };
  }

  /**
   * 단일 서버 페이지를 검사하고 정확한 배타 커서만 반환한다.
   * @param {object} data 최대20건의 items/hasNext/nextAfterId 응답.
   * @param {string} id snapshotId 또는 recordId.
   * @throws {object} 형식 오류는 빈 성공으로 처리하지 않는다.
   */
  function savedPageCursor(data, id) {
    if (
      !Array.isArray(data.items) ||
      data.items.length > 20 ||
      typeof data.hasNext !== "boolean" ||
      data.items.some((item) => !savedDecimal(item[id], true)) ||
      (data.hasNext
        ? !savedDecimal(data.nextAfterId, true) ||
          data.nextAfterId !== data.items.at(-1)?.[id]
        : data.nextAfterId !== null)
    )
      throw { status: 503 };
    return data.hasNext ? data.nextAfterId : undefined;
  }

  /**
   * 대상 없는 보호 입력만 첫 선택에 결속하며 네트워크·편집 버퍼·자식 선택은 변경하지 않는다.
   * @param {object} summary 현재 표시 페이지의 검증된 여섯 메타 필드.
   * @param {number} epoch 목록을 소유한 조회 세대.
   */
  function selectSavedSnapshot(summary, epoch) {
    if (!savedReadReady() || savedReads.history || epoch !== savedReadEpoch)
      return;
    const protectedDraft = manualProtected();
    const target =
      savedSelection?.snapshotId ||
      manualDraftSnapshot ||
      manualOperation?.intent.command.snapshotId ||
      manualIntent?.command.snapshotId;
    if (protectedDraft && target && summary.snapshotId !== target) {
      document.getElementById("manual-status").textContent =
        "기록 입력·전송·미확인 의도를 보호했습니다. 사본을 바꾸려면 먼저 기록 입력·보관 의도를 명시 폐기하세요.";
      return;
    }
    if (summary.snapshotId !== savedSelection?.snapshotId) {
      manualBaseline = undefined;
      if (!protectedDraft) manualDraftSnapshot = undefined;
      else if (!target) manualDraftSnapshot = summary.snapshotId;
    }
    clearSavedPrivate();
    savedSelection = summary;
    const selected = document.getElementById("saved-review-selected");
    selected.replaceChildren();
    for (const [label, value] of [
      ["선택 snapshotId", summary.snapshotId],
      ["고정 sourceRev", summary.sourceRev],
      ["저장 형식", summary.formatNo],
      [
        "목록 조회 당시 current",
        summary.current ? "현재 사본 (서버 true)" : "과거 사본 (서버 false)",
      ],
      ["생성 시각", displayTime(summary.createdAt)],
      ["생성 계정", summary.createdBy],
    ])
      labelValue(selected, label, value);
    for (const button of document.querySelectorAll("[data-snapshot-id]"))
      button.setAttribute(
        "aria-pressed",
        String(button.dataset.snapshotId === summary.snapshotId),
      );
    syncSavedReviewControls();
  }

  /**
   * 대기 중 요청은 세대·기준 객체·선택·투영 조건이 모두 같을 때만 반영한다.
   * @param {object} op 해당 명시 조회가 캡처한 소유권. 원문은 보관하지 않는다.
   */
  function ownsSavedRead(op) {
    if (op.lane === "issues" && op.pathname !== location.pathname) {
      if (savedReads.issues === op) clearSavedIssues();
      return false;
    }
    return (
      savedReads[op.lane] === op &&
      op.epoch === savedReadEpoch &&
      op.generation === generation &&
      op.detail === detail &&
      op.path === editorPath() &&
      op.metadata === savedReadMetadata() &&
      savedReadReady() &&
      (op.lane === "history" || op.selection === savedSelection) &&
      (op.lane !== "issues" ||
        (op.permissionEpoch === permissionEpoch &&
          op.identity === editorIdentity &&
          savedIssuePermission() &&
          op.filter ===
            document.getElementById("saved-issues-filter").value)) &&
      (op.lane !== "preview" ||
        (op.source === document.getElementById("saved-preview-source").value &&
          op.mode === document.getElementById("saved-preview-mode").value &&
          op.role === document.getElementById("saved-preview-role").value))
    );
  }

  /**
   * 서버 투영의 허용 필드만 새 객체로 선택한다. 제작자 payload는 입력으로 받지 않는다.
   * @param {object} data SR08의 data.
   * @param {string} mode ROLE 또는 REVEAL.
   * @returns {object} null·빈 문자열·서버 배열 순서를 보존한 허용 투영.
   * @throws {object} 누락·중첩 비허용 값은 조회 실패.
   */
  function savedPreviewData(data, mode) {
    /**
     * 허용된 스칼라만 복사하여 추가 비밀 필드의 유입을 막는다.
     * @param {object} row 서버 투영의 단일 객체.
     * @param {string[]} names 해당 객체의 허용 필드명.
     * @throws {object} 필드 누락·객체 값은 조회 실패.
     */
    function pick(row, names) {
      if (!row || typeof row !== "object" || Array.isArray(row))
        throw { status: 503 };
      return Object.fromEntries(
        names.map((key) => {
          const value = row[key];
          if (
            value !== null &&
            typeof value !== "string" &&
            !(typeof value === "number" && Number.isSafeInteger(value))
          )
            throw { status: 503 };
          return [key, value];
        }),
      );
    }
    if (mode === "REVEAL") return pick(data, ["title", "revealText"]);
    if (!Array.isArray(data?.persons) || !Array.isArray(data?.clues))
      throw { status: 503 };
    return {
      basic: pick(data.basic, [
        "title",
        "intro",
        "setting",
        "difficulty",
        "estMin",
        "estMax",
        "limitSec",
      ]),
      role: pick(data.role, ["code", "name", "brief"]),
      persons: data.persons.map((row) =>
        pick(row, ["code", "name", "publicText"]),
      ),
      clues: data.clues.map((row) =>
        pick(row, ["code", "title", "body", "personCode"]),
      ),
    };
  }

  /**
   * SR03/04/06/08을 사용자의 명시 조회 한 번에 한 페이지만 요청한다.
   * @param {string} lane history/payload/records/preview 중 해당 버튼의 영역.
   * @param {boolean} next 기본 false, 목록/근거에서만 반환 커서의 다음20건을 읽는다.
   * @returns {Promise<void>} 실패·감사 불가·오래된 응답은 원문을 남기지 않고 자동 재시도하지 않는다.
   */
  async function readSavedReview(lane, next = false) {
    if (!savedReadReady() || savedReads[lane]) return;
    if (
      lane === "history" &&
      manualProtected() &&
      (savedSelection ||
        manualOperation ||
        (manualIntent && !manualIntent.receipt))
    ) {
      document.getElementById("manual-status").textContent =
        "이력 교체는 선택을 초기화합니다. 기록 입력·보관 의도를 먼저 명시 폐기하세요. 미확인 결과는 실패로 확정되지 않습니다.";
      return;
    }
    const source = document.getElementById("saved-preview-source").value;
    const mode = document.getElementById("saved-preview-mode").value;
    const role = document.getElementById("saved-preview-role").value;
    if (
      (["payload", "records"].includes(lane) ||
        (lane === "preview" && source === "SNAPSHOT")) &&
      !savedSelection
    )
      return;
    const cursor = lane === "history" ? savedHistoryCursor : savedRecordsCursor;
    if (next && !cursor) return;
    if (
      lane === "preview" &&
      (!["DRAFT", "SNAPSHOT"].includes(source) ||
        !["ROLE", "REVEAL"].includes(mode))
    )
      return;
    if (lane === "preview" && source === "DRAFT" && !savedDraftEligible())
      return;
    if (
      lane === "preview" &&
      mode === "ROLE" &&
      !/^[A-Z0-9_]{1,32}$/.test(role)
    ) {
      clearSavedPrivate();
      savedReadStatus(
        "preview",
        "failure",
        "역할 코드는 영문 대문자·숫자·밑줄 1~32자로 입력하세요.",
      );
      document
        .getElementById("saved-preview-role")
        .setAttribute("aria-invalid", "true");
      document.getElementById("saved-preview-role").focus();
      syncSavedReviewControls();
      return;
    }
    if (lane === "history") {
      resetSavedReview("stale", false);
    } else {
      const node = document.getElementById(`saved-${lane}-result`);
      node.replaceChildren();
      node.hidden = lane !== "preview";
      if (lane === "records") savedRecordsCursor = undefined;
    }
    const op = {
      lane,
      epoch: savedReadEpoch,
      generation,
      detail,
      path: editorPath(),
      metadata: savedReadMetadata(),
      selection: savedSelection,
      source,
      mode,
      role,
    };
    savedReads[lane] = op;
    savedReadStatus(lane, "loading", "명시한 저장 자료를 조회 중입니다.");
    syncSavedReviewControls();
    try {
      let path;
      if (lane === "history")
        path = `${op.path}/review-snapshots?${pageParams({}, "afterId", next ? cursor : undefined)}`;
      else if (lane === "payload")
        path = `${op.path}/review-snapshots/${op.selection.snapshotId}`;
      else if (lane === "records")
        path = `${op.path}/review-snapshots/${op.selection.snapshotId}/records?${pageParams({}, "afterId", next ? cursor : undefined)}`;
      else {
        const query = new URLSearchParams({ source, mode });
        if (source === "DRAFT") {
          if (!savedDecimal(op.metadata.editRev)) throw { status: 503 };
          query.set("expectedRev", op.metadata.editRev);
        } else query.set("snapshotId", op.selection.snapshotId);
        if (mode === "ROLE") query.set("roleCode", role);
        path = `${op.path}/preview?${query}`;
      }
      const privateText = lane === "payload" || lane === "records";
      const response = await request(
        "GET",
        path,
        undefined,
        false,
        undefined,
        () => ownsSavedRead(op),
        privateText,
      );
      if (!ownsSavedRead(op)) return;
      const data = privateText ? response.data : response;
      const output = document.getElementById(`saved-${lane}-result`);
      if (lane === "history") {
        const after = savedPageCursor(data, "snapshotId");
        const summaries = data.items.map(savedSummary);
        for (const summary of summaries) {
          const row = document.createElement("div");
          row.className = "story-row";
          const button = document.createElement("button");
          button.type = "button";
          button.className = "secondary";
          button.dataset.snapshotId = summary.snapshotId;
          button.setAttribute("aria-pressed", "false");
          button.textContent = `사본 ${summary.snapshotId} 선택 · sourceRev ${summary.sourceRev} · ${summary.current ? "현재" : "과거"} (서버)`;
          const epoch = op.epoch;
          button.addEventListener("click", () =>
            selectSavedSnapshot(summary, epoch),
          );
          const meta = document.createElement("p");
          meta.textContent = `형식 ${summary.formatNo} · ${displayTime(summary.createdAt)} · 생성 계정 ${summary.createdBy}`;
          row.append(button, meta);
          output.append(row);
        }
        savedHistoryCursor = after;
        savedReadStatus(
          lane,
          summaries.length ? "success-populated" : "success-empty",
          summaries.length
            ? `이번 페이지 ${summaries.length}건 · 서버 ID 내림차순 · ${after ? "다음 페이지 있음" : "다음 페이지 없음"}`
            : "조회 성공 · 이번 페이지에 사본이 없습니다.",
        );
      } else if (lane === "payload") {
        const summary = savedSummary(data.snapshot);
        if (
          summary.snapshotId !== op.selection.snapshotId ||
          summary.sourceRev !== op.selection.sourceRev ||
          summary.formatNo !== op.selection.formatNo ||
          data.payload?.storyCode !== op.detail.storyCode ||
          data.payload?.versionNo !== op.detail.versionNo ||
          data.payload?.sourceRev !== summary.sourceRev ||
          data.payload?.formatNo !== summary.formatNo
        )
          throw { status: 503 };
        output.textContent = response.originalJson;
        output.hidden = false;
        savedReadStatus(
          lane,
          "success-populated",
          `제작자 민감 저장 원문 · 사본 ${summary.snapshotId} · sourceRev ${summary.sourceRev} · 이번 응답 current=${summary.current}. 목록 조회 당시 상태와 구분하세요.`,
        );
      } else if (lane === "records") {
        const after = savedPageCursor(data, "recordId");
        if (
          data.snapshotId !== op.selection.snapshotId ||
          typeof data.current !== "boolean"
        )
          throw { status: 503 };
        output.textContent = response.originalJson;
        output.hidden = false;
        savedRecordsCursor = after;
        savedReadStatus(
          lane,
          data.items.length ? "success-populated" : "success-empty",
          `조회 성공 · 사본 ${data.snapshotId} · 고정 sourceRev ${op.selection.sourceRev} · 이번 응답 current=${data.current} · 이번 페이지 ${data.items.length}건 · ${after ? "다음 페이지 있음" : "다음 페이지 없음"}. 전체 종류의 완료 판정이 아닙니다.`,
        );
      } else {
        const expectedSourceRev =
          source === "DRAFT" ? op.metadata.editRev : op.selection.sourceRev;
        if (
          data.source !== source ||
          data.mode !== mode ||
          data.previewOnly !== true ||
          data.sourceRev !== expectedSourceRev ||
          data.snapshotId !==
            (source === "DRAFT" ? null : op.selection.snapshotId) ||
          data.roleCode !== (mode === "ROLE" ? role : null)
        )
          throw { status: 503 };
        const projection = savedPreviewData(data.data, mode);
        if (mode === "ROLE" && projection.role.code !== role)
          throw { status: 503 };
        const text = document.createElement("p");
        text.className = "report-value";
        text.textContent = JSON.stringify(projection, null, 2);
        output.append(text);
        savedReadStatus(
          lane,
          "success-populated",
          `${source === "DRAFT" ? "저장 DRAFT 서버 투영" : `선택 사본 ${op.selection.snapshotId} 서버 투영`} · sourceRev ${data.sourceRev} · ${mode}${mode === "ROLE" ? ` / ${role}` : ""} · previewOnly=true. 로컬 미저장 입력이 아닙니다.`,
        );
      }
    } catch (error) {
      if (!ownsSavedRead(op)) return;
      resetSavedReview("failure");
      if (error.status === 409) {
        lockForReview(errorMessage(error));
        savedMetadataStatus(
          "stale",
          "저장 자료의 상태가 바뀌었습니다. 현재 열람 기준만 다시 조회하면 REVIEW에서도 사본을 읽을 수 있습니다. 쓰기 잠금은 유지됩니다.",
        );
      }
      savedReadStatus(
        lane,
        "failure",
        "저장 자료 조회 실패 · 민감 조회 감사 또는 응답을 확인하지 못했습니다. 빈 결과나 검수 통과가 아닙니다. 자동 재시도하지 않습니다.",
      );
    } finally {
      op.detail = op.metadata = op.selection = undefined;
      if (savedReads[lane] === op) delete savedReads[lane];
      syncSavedReviewControls();
    }
  }

  /** 저장 모드뿐 아니라 유지 모드 뒤에 남아 있는 수정 버퍼도 검사한다. */
  function reviewHasDrafts() {
    return [...editor.querySelectorAll("[data-field]")].some((mode) => {
      const input = mode.closest(".field").querySelector("[data-value]");
      return mode.value !== "keep" || input.value !== inputBaselines.get(input);
    });
  }

  /** 현재 초안·비교·전체 입력 상태를 기준으로 두 명시 행동을 제한한다. */
  function reviewReady() {
    return Boolean(
      detail &&
      !resolutionOperation &&
      !editor.hidden &&
      writableSection("basic") &&
      !saveLocked &&
      document.getElementById("comparison").hidden &&
      !reviewNeedsRead &&
      !lifecycleNeedsRead &&
      !lifecycleOperation &&
      detail.currentSnapshotId === null &&
      !reviewHasDrafts(),
    );
  }

  /** 패널만 갱신하며 기존 원고 조작이나 저장 잠금을 변경하지 않는다. */
  function syncReviewControls() {
    if (!document.getElementById("review-check")) return;
    document.getElementById("review-precheck").disabled =
      Boolean(reviewOperation) || !reviewReady();
    // 동의 중 실행 버튼은 포커스 복귀 대상으로 남기고 중복 실행은 작업 소유권으로 막는다.
    document.getElementById("review-samples-check").disabled =
      Boolean(
        reviewOperation && (!reviewOperation.human || reviewOperation.sent),
      ) || !reviewReady();
    document.getElementById("review-samples-open").disabled =
      Boolean(reviewOperation) || !detail || saveLocked;
    const refresh = document.getElementById("review-check-refresh");
    refresh.hidden = !reviewNeedsRead;
    refresh.disabled = Boolean(reviewOperation) || !detail;
    syncSavedReviewControls();
    syncLifecycleControls();
  }

  /**
   * 입력·기준본 변경은 이전 진단과 열린 사람 동의를 무효화한다.
   * @param {boolean} supersede 편집 작업 교체이면 지연 응답의 소유권도 제거한다.
   */
  function invalidateReview(supersede = false) {
    ++reviewInputEpoch;
    if (
      cloneOperation ||
      lifecycleOperation ||
      reviewOperation ||
      document.getElementById("review-check-diagnostics")?.childElementCount
    )
      AdminUI.cancelConfirmation();
    if (supersede) {
      if (reviewOperation) reviewOperation.source = undefined;
      reviewOperation = undefined;
    }
    document.getElementById("review-check-diagnostics")?.replaceChildren();
    const message = document.getElementById("review-check-status");
    if (message)
      message.textContent = reviewNeedsRead
        ? "사람 확인 결과의 현재 상태를 조회·비교해야 합니다. 수정번호 변경만으로 완료를 추정하지 않습니다."
        : "현재 저장본은 새 사전 검사가 필요합니다. 모든 영역의 미저장 선택과 남은 수정 입력을 먼저 저장하거나 취소하세요.";
    syncReviewControls();
  }

  /** 응답 소유권은 입력 세대와 별도로 검사하여 전송 중 새 입력을 보존한다. */
  function ownsReview(op) {
    return (
      reviewOperation === op &&
      generation === op.generation &&
      detail === op.source &&
      detail?.editRev === op.rev &&
      childResource === op.resource &&
      childKey === op.key &&
      editorPath() === op.path &&
      !editor.hidden
    );
  }

  /**
   * 새 명시 행동의 대상·기준본·입력 세대를 모든 await 전에 고정한다.
   * @param {boolean} human 전체 예시 사람 동의이면 true이며 동의 중 실행 버튼의 포커스를 유지한다.
   */
  function beginReview(human) {
    resetSavedReview();
    const op = {
      generation,
      source: detail,
      rev: detail.editRev,
      path: editorPath(),
      resource: childResource,
      key: childKey,
      inputEpoch: reviewInputEpoch,
      permissionEpoch,
      sent: false,
      human,
    };
    reviewOperation = op;
    syncReviewControls();
    return op;
  }

  /**
   * 서버가 실제로 반환한 위치만 기존 편집기로 안내한다.
   * @param {object} diagnostic 서버 진단의 resource/itemKey/field이며 알 수 없는 위치는 이동하지 않는다.
   */
  async function openReviewLocation(diagnostic) {
    const resource =
      {
        gradeSamples: "grade-samples",
        rubricClues: "rubric-clues",
        clueRoles: "clue-roles",
      }[diagnostic.resource] || diagnostic.resource;
    if (["basic", "answer", "reveal"].includes(resource)) {
      const known = fields[resource].some(([key]) => key === diagnostic.field);
      focusEditorFragment(
        `#${resource}-${known ? `${diagnostic.field}-mode` : "heading"}`,
      );
      return;
    }
    if (resource === "policy") {
      focusEditorFragment("#policy-heading");
      return;
    }
    if (!Object.hasOwn(childTypes, resource)) return;
    focusEditorFragment("#child-resource");
    if (resource !== childResource) await selectChildResource(resource);
    if (!detail || childResource !== resource || saveLocked) return;
    // 기존 선택기의 폐기 확인을 우회하지 않고 실제 목록/필드에서 사용자가 계속한다.
    focusEditorFragment("#child-resource");
    if (diagnostic.itemKey && diagnostic.itemKey === childKey) {
      const field = childTypes[resource].fields.find(
        ([key]) => key === diagnostic.field,
      );
      if (field) focusEditorFragment(`#child-${field[0]}-mode`);
    }
  }

  /** 코드·위치·전체 건수는 textContent로만 표시하며 200 응답을 통과로 번역하지 않는다. */
  function renderPrecheck(result) {
    const container = document.getElementById("review-check-diagnostics");
    container.replaceChildren();
    const summary = document.createElement("p");
    summary.textContent = `수정번호 ${result.editRev} · 차단 오류 ${result.errorCount}건 · 경고 ${result.warningCount}건 · 검수 요청 구조 조건 ${result.eligible ? "충족" : "미충족"} (품질 통과 아님)${result.truncated ? " · 진단 잘림: 오류·경고 각각 최대 200건 표시, 건수는 전체" : ""}`;
    container.append(summary);
    for (const [name, diagnostics] of [
      ["차단 오류", result.errors],
      ["경고", result.warnings],
    ]) {
      const heading = document.createElement("h3");
      heading.textContent = `${name} · 표시 ${diagnostics.length}건`;
      const list = document.createElement("ul");
      for (const diagnostic of diagnostics) {
        const row = document.createElement("li");
        const text = document.createElement("p");
        text.textContent = [
          diagnostic.code,
          diagnostic.resource,
          diagnostic.itemKey,
          diagnostic.field,
        ]
          .filter(Boolean)
          .join(" · ");
        row.append(text);
        const resource =
          {
            gradeSamples: "grade-samples",
            rubricClues: "rubric-clues",
            clueRoles: "clue-roles",
          }[diagnostic.resource] || diagnostic.resource;
        if (
          ["basic", "answer", "reveal", "policy"].includes(resource) ||
          Object.hasOwn(childTypes, resource)
        ) {
          const button = document.createElement("button");
          button.type = "button";
          button.className = "secondary";
          button.textContent = `수정 위치 확인 · ${diagnostic.resource}${diagnostic.itemKey ? ` · ${diagnostic.itemKey}` : ""}`;
          button.addEventListener("click", () =>
            openReviewLocation(diagnostic),
          );
          row.append(button);
        }
        list.append(row);
      }
      container.append(heading, list);
    }
  }

  /**
   * SR01/SR09는 정확히 expectedRev 하나만 보내며 실패한 변경을 재전송하지 않는다.
   * @param {boolean} human true이면 전체 활성 저장 예시를 사람이 읽었다는 별도 동의를 요구한다.
   */
  async function runReviewCheck(human) {
    if (reviewOperation || !reviewReady()) return;
    invalidateReview();
    const op = beginReview(human);
    let receipt;
    try {
      if (!(await observeEditorIdentity()) || !ownsReview(op)) return;
      if (human) {
        const accepted = await AdminUI.confirm({
          title: "전체 활성 저장 예시 사람 확인",
          message:
            "선택한 행만이 아니라 이 수정번호의 모든 활성 저장 예시의 제출 입력·기대 결과·근거를 사람이 읽고 확인했습니까? 저장이나 모델 품질 판정이 아니며 검수·공개를 실행하지 않습니다.",
          confirmLabel: "전체 저장 예시 확인 기록",
        });
        if (!(await observeEditorIdentity())) return;
        if (
          !accepted ||
          !ownsReview(op) ||
          op.inputEpoch !== reviewInputEpoch ||
          op.permissionEpoch !== permissionEpoch ||
          !reviewReady()
        )
          return;
      }
      const result = await request(
        "POST",
        `${op.path}/${human ? "grade-samples/check" : "review-precheck"}`,
        { expectedRev: op.rev },
        false,
        undefined,
        (phase) => {
          if (!ownsReview(op)) return false;
          if (phase === "send") {
            if (
              op.inputEpoch !== reviewInputEpoch ||
              op.permissionEpoch !== permissionEpoch ||
              !reviewReady()
            )
              return false;
            op.sent = true;
            if (human) {
              reviewNeedsRead = true;
              document.getElementById("review-check-receipt").textContent = "";
            }
            syncReviewControls();
            document.getElementById("review-check-status").textContent = human
              ? "전체 저장 예시 사람 확인 기록 중입니다."
              : "저장본 사전 검사 중입니다.";
          }
          return true;
        },
      );
      if (!ownsReview(op)) return;
      if (!human) {
        if (
          op.inputEpoch !== reviewInputEpoch ||
          op.permissionEpoch !== permissionEpoch ||
          !reviewReady()
        )
          return;
        if (result.editRev !== op.rev) throw { status: 409 };
        renderPrecheck(result);
        document.getElementById("review-check-status").textContent =
          "저장본의 구조 진단입니다. 오류·경고의 실제 위치를 확인하세요.";
        return;
      }
      receipt = result;
      document.getElementById("review-check-receipt").textContent =
        `사람 확인 영수증 · 확인 ${result.checkedCount}건 · 변경 ${result.changed ? "있음" : "없음"} · 수정번호 ${result.editRev}. 품질 판정이 아닙니다.`;
      const data = await readEditor(op.key, op.resource, () => ownsReview(op));
      if (!ownsReview(op)) return;
      reviewOperation = undefined;
      if (
        data.editRev === result.editRev &&
        data.status === op.source.status &&
        data.activeYn === op.source.activeYn &&
        data.storyActiveYn === op.source.storyActiveYn
      ) {
        reviewNeedsRead = false;
        fillDetail(data, { preserve: true });
      } else {
        showComparison(data);
      }
    } catch (error) {
      if (!ownsReview(op)) return;
      if (human && op.sent) {
        reviewNeedsRead = true;
        const rejected = !receipt && error.status >= 400 && error.status < 500;
        if (!receipt)
          document.getElementById("review-check-receipt").textContent = rejected
            ? `이번 사람 확인 요청 거절 · ${error.code || error.status}. 현재 저장본을 조회·비교하세요.`
            : "이전 사람 확인 결과 미확인 · 현재 수정번호나 예시 조회는 이전 요청의 영수증이 아닙니다.";
        document.getElementById("review-check-status").textContent = receipt
          ? "영수증은 받았으나 현재 저장본 조회는 미확인입니다. 입력을 유지합니다. 현재 저장본을 조회·비교하세요."
          : `${rejected ? "사람 확인 요청이 거절되었습니다." : "사람 확인 결과 미확인."} ${error.code === "SAMPLE_NOT_READY" ? "전체 저장 예시의 구조 조건이 충족되지 않았습니다." : errorMessage(error)} 현재 저장본을 조회·비교한 뒤 새로 판단하세요.`;
      } else if (op.inputEpoch === reviewInputEpoch) {
        document.getElementById("review-check-status").textContent =
          errorMessage(error);
        if (error.status === 409) lockForReview(errorMessage(error));
      }
    } finally {
      op.source = undefined;
      if (reviewOperation === op) {
        reviewOperation = undefined;
        syncReviewControls();
      }
    }
  }

  /**
   * 검수 전환의 상태·권한·잠금을 함께 확인하며 반환에는 DRAFT 전용 편집 조건을 사용하지 않는다.
   * @param {string} action REQUEST/WITHDRAW/CHANGES_REQUIRED 중 명시 행동.
   * @param {boolean} replay 원래 SR02 본문 재확인만 상태·쓰기 번호 잠금과 구분한다.
   * @returns {boolean} 현재 화면의 전송 전 조건이며 서버 인가를 대신하지 않는다.
   */
  function lifecycleReady(action, replay = false) {
    if (
      !detail ||
      editor.hidden ||
      resolutionOperation ||
      reviewOperation ||
      manualOperation ||
      savedMetadataOperation ||
      savedDetailPending ||
      !document.getElementById("unavailable").hidden
    )
      return false;
    const permission = action === "CHANGES_REQUIRED" ? "review" : "edit";
    if (!hasPermission(permission) || !detail.storyActiveYn || !detail.activeYn)
      return false;
    if (replay)
      return (
        action === "REQUEST" &&
        lifecycleIntent?.path === editorPath() &&
        lifecycleIntent.sent &&
        !lifecycleIntent.rejected &&
        !reviewHasDrafts()
      );
    if (unknownReturnSnapshot || lifecycleIntent?.sent) return false;
    if (
      saveLocked ||
      reviewNeedsRead ||
      lifecycleNeedsRead ||
      !document.getElementById("comparison").hidden
    )
      return false;
    if (action === "REQUEST")
      return (
        (!lifecycleIntent ||
          (lifecycleOperation?.body === lifecycleIntent.body &&
            !lifecycleIntent.sent)) &&
        detail.status === "DRAFT" &&
        detail.currentSnapshotId === null &&
        !reviewHasDrafts()
      );
    return (
      ["REVIEW", "READY"].includes(detail.status) &&
      savedDecimal(detail.currentSnapshotId, true)
    );
  }

  /** 현재 쓰기 기준과 선택한 역사 사본을 구분하고 명시 행동만 제공한다. */
  function syncLifecycleControls() {
    syncCloneControls();
    syncResolutionControls();
    if (!document.getElementById("review-lifecycle")) return;
    const busy = Boolean(lifecycleOperation);
    for (const [id, action] of [
      ["review-request", "REQUEST"],
      ["review-withdraw", "WITHDRAW"],
      ["review-changes-required", "CHANGES_REQUIRED"],
    ]) {
      document.getElementById(id).disabled =
        (busy &&
          (lifecycleOperation.action !== action || lifecycleOperation.sent)) ||
        !lifecycleReady(action);
    }
    const replay = document.getElementById("review-request-replay");
    replay.hidden = !lifecycleIntent?.sent || Boolean(lifecycleIntent.rejected);
    replay.disabled =
      (busy && (!lifecycleOperation.replay || lifecycleOperation.sent)) ||
      !lifecycleReady("REQUEST", true);
    document.getElementById("review-lifecycle-refresh").disabled =
      !detail ||
      busy ||
      Boolean(reviewOperation || savedMetadataOperation) ||
      editor.hidden ||
      !document.getElementById("unavailable").hidden;
    document.getElementById("review-lifecycle-target").textContent = detail
      ? `쓰기 기준 · 수정번호 ${detail.editRev} · ${detail.status} · currentSnapshotId ${detail.currentSnapshotId ?? "null"}. 반환은 이 현재 포인터만 대상으로 하며 아래에서 선택한 과거 사본은 대상이 아닙니다.`
      : "현재 쓰기 기준 미확인";
  }

  /**
   * 입력·권한 신선도와 별도로 실제 전송 영수증의 대상 소유권을 확인한다.
   * @param {object} op 요청 직전에 고정한 대상·세대·쓰기 기준.
   * @returns {boolean} 응답을 현재 작업에 반영할 소유권이 유지되는지 여부.
   */
  function ownsLifecycle(op) {
    return (
      lifecycleOperation === op &&
      generation === op.generation &&
      detail === op.source &&
      detail?.editRev === op.rev &&
      editorPath() === op.path &&
      childResource === op.resource &&
      childKey === op.key &&
      !editor.hidden &&
      document.getElementById("unavailable").hidden
    );
  }

  /**
   * 반환 폼의 닫힌 사유와 사용자가 입력한 비개인 참조만 읽는다.
   * @param {string} action WITHDRAW 또는 CHANGES_REQUIRED.
   * @returns {object|null} 유효한 두 필드 또는 입력 오류. 참조를 생성하거나 정규화하지 않는다.
   */
  function returnFields(action) {
    const withdraw = action === "WITHDRAW";
    const verificationRef = document.getElementById(
      withdraw ? "review-withdraw-ref" : "review-changes-ref",
    ).value;
    const reasonCode = withdraw
      ? "AUTHOR_REVISION"
      : document.getElementById("review-changes-reason").value;
    if (
      !/^[A-Za-z0-9_-]{8,64}$/.test(verificationRef) ||
      !(
        withdraw ||
        ["CONTENT_DEFECT", "FAIRNESS_ISSUE", "GRADING_ISSUE"].includes(
          reasonCode,
        )
      )
    )
      return null;
    return { reasonCode, verificationRef };
  }

  /**
   * 정확한 실제 ReviewResult만 영수증으로 인정하고 과거 replay를 현재 기준으로 설치하지 않는다.
   * @param {object} result 서버 SR02/SR07 응답.
   * @param {object} op 원래 전송 본문과 행동.
   * @throws {object} 형식·출처 불일치는 확정 여부 미확인으로 처리한다.
   */
  function validateLifecycleReceipt(result, op) {
    if (
      !result ||
      !savedDecimal(result.snapshotId, true) ||
      !savedDecimal(result.sourceRev) ||
      !savedDecimal(result.editRev) ||
      !Object.hasOwn(versionStates, result.status) ||
      !(
        result.currentSnapshotId === null ||
        savedDecimal(result.currentSnapshotId, true)
      ) ||
      typeof result.replayed !== "boolean" ||
      typeof result.requestId !== "string" ||
      (op.action === "REQUEST"
        ? result.sourceRev !== op.body.expectedRev
        : result.snapshotId !== op.body.expectedSnapshotId ||
          result.status !== "DRAFT" ||
          result.currentSnapshotId !== null ||
          result.replayed !== false) ||
      (!result.replayed &&
        BigInt(result.editRev) !== BigInt(op.body.expectedRev) + 1n) ||
      (op.action === "REQUEST" &&
        !result.replayed &&
        (result.status !== "REVIEW" ||
          result.currentSnapshotId !== result.snapshotId))
    )
      throw { status: 503 };
  }

  /**
   * SR02는 원래 UUID/본문을, SR07은 현재 포인터·사유·참조를 동의 전후에 고정한다.
   * @param {string} action REQUEST/WITHDRAW/CHANGES_REQUIRED 중 명시 행동.
   * @param {boolean} replay 기본 false. true는 미확인 SR02의 같은 본문 재확인에만 허용한다.
   * @returns {Promise<void>} 자동 저장·검사·재전송 없이 영수증과 조회 실패를 분리해 표시한다.
   */
  async function runLifecycle(action, replay = false) {
    if (lifecycleOperation || !lifecycleReady(action, replay)) return;
    const fields = action === "REQUEST" ? null : returnFields(action);
    const message = document.getElementById("review-lifecycle-status");
    const receipt = document.getElementById("review-lifecycle-receipt");
    if (action !== "REQUEST" && !fields) {
      message.textContent =
        "행동에 맞는 사유와 영문·숫자·밑줄·하이픈 8~64자의 확인 참조를 입력하세요.";
      document
        .getElementById(
          action === "WITHDRAW" ? "review-withdraw-ref" : "review-changes-ref",
        )
        .focus();
      return;
    }
    resetSavedReview();
    const body = replay
      ? lifecycleIntent.body
      : Object.freeze(
          action === "REQUEST"
            ? { expectedRev: detail.editRev, requestKey: crypto.randomUUID() }
            : {
                expectedRev: detail.editRev,
                expectedSnapshotId: detail.currentSnapshotId,
                action,
                ...fields,
              },
        );
    if (new TextEncoder().encode(JSON.stringify(body)).length > 8192) {
      message.textContent =
        "전환 요청은 UTF-8 8KiB를 넘을 수 없습니다. 전송하지 않았습니다.";
      return;
    }
    const op = {
      action,
      replay,
      body,
      generation,
      source: detail,
      rev: detail.editRev,
      path: editorPath(),
      resource: childResource,
      key: childKey,
      inputEpoch: reviewInputEpoch,
      permissionEpoch,
      sourceEpoch: lifecycleSourceEpoch,
      sent: false,
    };
    lifecycleOperation = op;
    if (action === "REQUEST" && !replay)
      lifecycleIntent = { path: op.path, body, sent: false };
    // 새 의도는 동의 동안 보관하되 같은 의도를 차단 조건으로 오인하지 않는다.
    const fresh = () =>
      ownsLifecycle(op) &&
      op.inputEpoch === reviewInputEpoch &&
      op.permissionEpoch === permissionEpoch &&
      op.sourceEpoch === lifecycleSourceEpoch &&
      (action === "REQUEST"
        ? lifecycleIntent?.body === body
        : JSON.stringify(returnFields(action)) === JSON.stringify(fields)) &&
      (replay
        ? lifecycleReady(action, true)
        : hasPermission(action === "CHANGES_REQUIRED" ? "review" : "edit") &&
          !saveLocked &&
          !reviewNeedsRead &&
          !lifecycleNeedsRead &&
          !savedMetadataOperation &&
          !reviewOperation &&
          document.getElementById("comparison").hidden &&
          (action !== "REQUEST" || !reviewHasDrafts()));
    syncReviewControls();
    let confirmed = false;
    try {
      if (!(await observeEditorIdentity()) || !fresh()) return;
      const accepted = await AdminUI.confirm({
        title: replay
          ? "원래 검수 요청 재확인"
          : action === "REQUEST"
            ? "저장 초안 검수 요청"
            : action === "WITHDRAW"
              ? "작성자 철회 · 초안 반환"
              : "검수자 수정 요구 · 초안 반환",
        message: replay
          ? "원래 수정번호와 같은 요청 키·본문으로만 결과를 재확인합니다. 과거 사본 영수증은 현재 회차가 아닐 수 있습니다."
          : action === "REQUEST"
            ? "저장된 초안을 불변 사본으로 고정하고 검수를 요청합니까? 서버가 구조 조건을 다시 검사하며 품질 승인이나 공개가 아닙니다."
            : "현재 쓰기 기준의 최신 사본을 초안으로 반환합니까? 원고와 미저장 입력·과거 사본은 보존하며 새 회차 검수가 필요합니다.",
        confirmLabel: replay
          ? "같은 요청 재확인"
          : action === "REQUEST"
            ? "검수 요청"
            : "초안 반환",
      });
      if (!(await observeEditorIdentity())) return;
      if (!accepted || !fresh()) return;
      const result = await request(
        "POST",
        `${op.path}/${action === "REQUEST" ? "review-requests" : "return-to-draft"}`,
        body,
        false,
        undefined,
        (phase) => {
          if (!ownsLifecycle(op)) return false;
          if (phase === "send") {
            if (!fresh()) return false;
            op.sent = true;
            lifecycleNeedsRead = true;
            saveLocked = true;
            if (action === "REQUEST") lifecycleIntent.sent = true;
            else unknownReturnSnapshot = body.expectedSnapshotId;
            receipt.dataset.state = "unknown";
            receipt.textContent =
              "이번 전환 결과 미확인 · 전송 중이며 현재 조회는 영수증이 아닙니다.";
            message.textContent =
              "명시한 전환을 처리 중입니다. 자동 재전송하지 않습니다.";
            syncChildControls();
          }
          return true;
        },
      );
      if (!ownsLifecycle(op)) return;
      validateLifecycleReceipt(result, op);
      confirmed = true;
      if (action === "REQUEST") lifecycleIntent = undefined;
      else {
        unknownReturnSnapshot = undefined;
        const withdraw = action === "WITHDRAW";
        acknowledgedReturnFields[
          withdraw ? "review-withdraw-ref" : "review-changes-ref"
        ] = body.verificationRef;
        if (!withdraw)
          acknowledgedReturnFields["review-changes-reason"] = body.reasonCode;
      }
      receipt.dataset.state = "confirmed";
      receipt.textContent = `전환 영수증 확정 · ${action} · 사본 ${result.snapshotId} · 고정 sourceRev ${result.sourceRev} · 응답 당시 수정번호 ${result.editRev} · ${result.status} · 응답 당시 currentSnapshotId ${result.currentSnapshotId ?? "null"} · ${result.replayed ? "원래 요청 재생 (과거 사본일 수 있음)" : "신규 처리"}. 현재 기준은 별도 GET으로 비교하세요.`;
      const data = await readEditor(op.key, op.resource, () =>
        ownsLifecycle(op),
      );
      if (!ownsLifecycle(op)) return;
      lifecycleOperation = undefined;
      showComparison(data, true);
      message.textContent =
        "전환 영수증은 확정됐습니다. 현재 GET을 비교·수락해야 새 쓰기 기준을 사용합니다. 미저장 입력은 유지됩니다.";
    } catch (error) {
      if (!ownsLifecycle(op)) return;
      if (op.sent) {
        const rejected =
          !confirmed && error.status >= 400 && error.status < 500;
        const terminal =
          rejected &&
          error.code !== "CSRF_INVALID" &&
          ([400, 413, 422].includes(error.status) ||
            ["REQUEST_KEY_CONFLICT", "EDIT_CONFLICT"].includes(error.code));
        // CSRF 복구의 확정 거절은 종료할 수 있지만 과거 전송 유실은 재확인 거절로 지우지 않는다.
        const terminalIntentRejection =
          action === "REQUEST" && terminal && !lifecycleIntent?.unknown;
        if (terminalIntentRejection && lifecycleIntent?.body === body)
          lifecycleIntent.rejected = true;
        if (rejected) {
          receipt.dataset.state = "rejected";
          receipt.textContent = terminalIntentRejection
            ? "이번 검수 요청은 확정 거절됐습니다. 현재 GET·비교를 명시적으로 수락하면 거절된 의도를 종료하고 수정·저장 뒤 새 요청을 결정할 수 있습니다."
            : replay
              ? "이번 재확인 요청은 거절됐습니다. 이전 요청의 미확정 결과와 원래 키·본문은 계속 유지됩니다."
              : "이번 전환 요청은 서버에서 거절됐습니다. 입력과 원래 요청 본문은 유지됩니다.";
          if (action !== "REQUEST") unknownReturnSnapshot = undefined;
        } else if (!confirmed) {
          if (action === "REQUEST" && lifecycleIntent?.body === body)
            lifecycleIntent.unknown = true;
          receipt.dataset.state = "unknown";
          receipt.textContent =
            "이번 전환 결과 미확인 · 응답을 확인하지 못했습니다. 현재 상태 조회만으로 성공을 추정하지 않습니다.";
        }
        message.textContent = confirmed
          ? "전환 영수증은 확정됐으나 현재 GET 조회 실패. 영수증과 입력을 유지하며 자동 재전송하지 않습니다."
          : error.code === "CSRF_INVALID"
            ? "CSRF 확인 거절 · 입력과 원래 의도를 유지합니다. 현재 기준을 조회하거나 원래 검수 요청만 명시적으로 재확인하세요."
            : replay && lifecycleIntent?.unknown
              ? "재확인 뒤에도 원래 전송 결과는 미확정입니다. 현재 GET으로 의도를 종료하지 않으며 원래 키·본문만 유지합니다."
              : error.code === "REVIEW_NOT_READY"
                ? "서버 구조 조건 미충족 · 현재 기준을 비교·수락한 뒤 저장본 사전 검사로 진단을 확인하세요."
                : error.code === "SNAPSHOT_TOO_LARGE"
                  ? "서버 사본 크기 한도 초과 · 원고를 확인하세요. 원문은 오류에 표시하지 않습니다."
                  : `전환 ${rejected ? "거절" : "결과 미확인"} · ${errorMessage(error)} ${action === "REQUEST" ? (terminalIntentRejection ? "현재 GET·비교 수락 뒤 수정·저장하고 새 요청을 결정하세요." : "원래 키·본문 재확인 또는 현재 조회만 가능합니다.") : "반환은 재전송하지 않습니다. 현재 GET·비교만 가능합니다."}`;
        lifecycleOperation = undefined;
        lockForReview("검수 전환 이후 현재 쓰기 기준을 조회·비교하세요.");
      } else
        message.textContent =
          "전환을 전송하지 않았습니다. 현재 입력·권한·기준을 확인하고 명시적으로 다시 결정하세요.";
    } finally {
      op.source = undefined;
      if (lifecycleOperation === op) lifecycleOperation = undefined;
      if (lifecycleIntent?.body === op.body && !lifecycleIntent.sent)
        lifecycleIntent = undefined;
      syncReviewControls();
    }
  }

  /** 원문을 넣지 않은 상태 문구와 오류 강조 여부를 표시한다. */
  function status(text, error = false) {
    notice.textContent = text;
    notice.classList.toggle("error", error);
  }

  /**
   * 접근 종료 시 동의를 거절하고 원고·생성 의도·자격 메모리와 늦은 응답을 무효화한다.
   * @param {string} text 원고·비밀값을 포함하지 않는 고정 접근 종료 안내.
   * @param {number} code 기존 HTTP 접근 실패 상태. 401이면 로그인 안내를 추가한다.
   * @returns {void} 현재 화면의 민감 메모리와 입력을 폐기한다.
   */
  function revoke(text, code) {
    AdminUI.cancelConfirmation();
    clearResolution();
    clearClone();
    manualBaseline = manualIntent = manualOperation = undefined;
    manualDraftSnapshot = undefined;
    manualAcknowledged = "";
    ++manualInputEpoch;
    editorIdentityRevoked = true;
    editorIdentity = undefined;
    acknowledgedReturnFields = {};
    resetSavedReview();
    if (lifecycleOperation) lifecycleOperation.source = undefined;
    lifecycleOperation = lifecycleIntent = unknownReturnSnapshot = undefined;
    lifecycleNeedsRead = false;
    observedPermissions = undefined;
    permissionDenied = { edit: true, review: true, publish: true };
    ++permissionEpoch;
    if (reviewOperation) reviewOperation.source = undefined;
    reviewOperation = undefined;
    reviewNeedsRead = false;
    inputBaselines = new WeakMap();
    ++reviewInputEpoch;
    ++generation;
    detail = latest = undefined;
    childKey = undefined;
    childEditing = false;
    childAfter = undefined;
    ++childRequest;
    csrf = undefined;
    createKey = createTitle = undefined;
    selectedStory = manageSnapshot = viewer = undefined;
    ++manageEpoch;
    ownershipStory = ownershipSnapshot = ownershipIntent = undefined;
    ++ownershipEpoch;
    document.getElementById("create-form")?.reset();
    document.getElementById("create-check")?.setAttribute("hidden", "");
    if (editor) {
      for (const input of editor.querySelectorAll("input, textarea, select"))
        input.value = "";
      for (const form of editor.querySelectorAll("[data-section]"))
        form.replaceChildren();
      editor.replaceChildren();
      editor.hidden = true;
      document.getElementById("unavailable").hidden = false;
      document.getElementById("unavailable-message").textContent = text;
      document.getElementById("retry-detail").hidden = true;
    } else {
      document.getElementById("rows").replaceChildren();
      document.getElementById("create-panel").hidden = true;
      document.getElementById("create-jump").hidden = true;
      document.getElementById("filter-form").hidden = true;
      document.getElementById("next").hidden = true;
      document.getElementById("manage-panel").hidden = true;
      document.getElementById("ownership-panel").hidden = true;
    }
    status(text + (code === 401 ? " 로그인 화면으로 이동하세요." : ""), true);
  }

  /** HTTP 실패를 자동 재전송 없는 고정 안내 문구로 변환한다. */
  function errorMessage(error) {
    if (error.code === "REAUTH_REQUIRED")
      return "최근 재인증이 필요합니다. 입력은 유지되며 재인증 뒤 현재 상태를 다시 조회하고 직접 실행하세요.";
    if (error.code === "REFERENCE_IN_USE")
      return "참조 중인 자료입니다. 범인·역할 조합·단서 배정 등 활성 연결을 먼저 직접 해제하세요.";
    if (error.code === "ITEM_EXISTS")
      return "이미 예약된 자료 키입니다. 논리 삭제된 자료도 포함해 기존 원고와 상태를 확인하세요.";
    if (error.code === "SLOT_CONFLICT")
      return "힌트 단계가 이미 예약돼 있습니다. 논리 삭제된 힌트까지 확인해 기존 행을 복원·수정하세요. 입력은 유지되며 자동 재전송하지 않습니다.";
    if (error.status === 409)
      return "현재 상태와 충돌합니다. 최신본을 조회해 입력과 비교하고 직접 결정하세요.";
    if (error.status === 503 || !error.status)
      return "처리 결과가 불확실합니다. 상태를 조회해 확인하세요. 변경 요청은 자동 재전송하지 않습니다.";
    if (error.status === 400 || error.status === 422 || error.status === 413)
      return "입력 형식이나 크기를 확인하세요. 입력 원문은 오류에 표시하지 않습니다.";
    return "요청에 실패했습니다. 서버 상태를 확인하세요.";
  }

  /**
   * 같은 출처 API만 호출하며 missing 허용 GET의 부재는 호출자가 부모 재인가로 확인한다.
   * CSRF 거절의 입력 보존은 별도 복구를 제공하는 SR01/SR02/SR05/SR07/SR09·SP05·PT-A12에만 적용한다.
   * 소유 중인 정확한 SP05 복제는 재인증 대상이 아니므로 REAUTH_REQUIRED도 접근 실패 시 폐기한다.
   * 편집기에서는 전송 직전(CSRF 획득 뒤)과 응답 채택 전에 Me 소유 문맥을 별도로 재관측한다.
   * @param {string} method 기존 GET/POST/PATCH 요청 동작.
   * @param {string} path 사건 API 기준의 검증된 상대 경로.
   * @param {object|undefined} body 변경 요청 객체이며 GET은 생략한다.
   * @param {boolean} missing 기본 false. 기존 선택 단건 GET만 404를 null로 반환한다.
   * @param {object|undefined} rawJsonFields 기존 원고 변경에서만 사용하는 JSON 필드 원문.
   * @param {function|undefined} guard 선택적 전송/응답 소유권 검사. false이면 요청·늦은 응답을 반영하지 않는다.
   * @param {boolean} privateReadText 기본 false. SR04/SR06 GET만 원래 응답 JSON 텍스트와 파싱 메타를 함께 반환한다.
   * @param {string|undefined} manualJson SR05 필드별 검증으로 만든 현재 작업 후보·재확인 의도의 정확한 원문만 허용한다.
   * @throws {object} 조회 실패·잘못된 원문 요청·만료된 소유권은 원문 없이 거절한다.
   */
  async function request(
    method,
    path,
    body,
    missing = false,
    rawJsonFields,
    guard,
    privateReadText = false,
    manualJson,
  ) {
    if (
      manualJson !== undefined &&
      (method !== "POST" ||
        body !== undefined ||
        rawJsonFields !== undefined ||
        privateReadText ||
        !manualOperation ||
        !ownsManual(manualOperation) ||
        manualJson !== manualOperation.intent.command.body ||
        path !==
          `${manualOperation.intent.command.path}/review-snapshots/${manualOperation.intent.command.snapshotId}/records` ||
        !/^\/[A-Z0-9_]{1,40}\/versions\/[1-9][0-9]*\/review-snapshots\/[1-9][0-9]*\/records$/.test(
          path,
        ) ||
        new TextEncoder().encode(manualJson).length > 131072)
    )
      throw { status: 400 };
    if (
      privateReadText &&
      (method !== "GET" ||
        !/^\/ST_[A-Z0-9_]+\/versions\/[1-9][0-9]*\/review-snapshots\/[1-9][0-9]*(?:\/records\?size=20(?:&afterId=[1-9][0-9]*)?)?$/.test(
          path,
        ))
    )
      throw { status: 400 };
    const headers = { Accept: "application/json" };
    let credential = csrf;
    if (method !== "GET") {
      if (!credential) {
        const response = await fetch("/admin/api/auth/csrf", {
          credentials: "same-origin",
          cache: "no-store",
        });
        if (!response.ok) {
          if (editor && !(await observeEditorIdentity()))
            throw { status: 401, code: "EDITOR_IDENTITY_UNCONFIRMED" };
          if ([401, 403, 404].includes(response.status))
            revoke("현재 작업 자격을 확인할 수 없습니다.", response.status);
          throw { status: response.status };
        }
        credential = await response.json();
      }
    }
    if (editor && !(await observeEditorIdentity()))
      throw { status: 401, code: "EDITOR_IDENTITY_UNCONFIRMED" };
    if (method !== "GET") {
      headers[credential.headerName] = credential.token;
      headers["Content-Type"] = "application/json";
    }
    if (guard && !guard("send")) throw { code: "STALE_OPERATION" };
    if (method !== "GET") csrf = credential;
    let response;
    try {
      response = await fetch(api + path, {
        method,
        headers,
        credentials: "same-origin",
        cache: "no-store",
        ...(method !== "GET"
          ? {
              body:
                manualJson !== undefined
                  ? manualJson
                  : rawJsonFields === undefined
                    ? JSON.stringify(body)
                    : jsonFieldBody(body, rawJsonFields),
            }
          : {}),
      });
    } catch {
      if (editor && !(await observeEditorIdentity()))
        throw { status: 401, code: "EDITOR_IDENTITY_UNCONFIRMED" };
      throw { status: 503 };
    }
    let data;
    let originalJson;
    // 오류 상태 코드만으로 훼손된 응답을 확정 거절 영수증으로 해석하지 않는다.
    let malformed = false;
    try {
      if (privateReadText && response.ok) {
        originalJson = await response.text();
        data = JSON.parse(originalJson);
      } else data = await response.json();
    } catch {
      malformed = true;
    }
    if (editor && !(await observeEditorIdentity()))
      throw { status: 401, code: "EDITOR_IDENTITY_UNCONFIRMED" };
    if (guard && !guard("response")) throw { code: "STALE_OPERATION" };
    const clonePost =
      method === "POST" &&
      /^\/ST_[A-Z0-9_]+\/drafts$/.test(path) &&
      cloneOperation?.kind === "write" &&
      cloneOperation.intent === cloneIntent &&
      cloneIntent.path === path &&
      cloneIntent.body === body &&
      cloneSent;
    const resolutionPost =
      method === "POST" && resolutionOperation?.kind === "write" &&
      resolutionOperation.sent && resolutionOperation.intent === resolutionIntent &&
      resolutionIntent.path === path && resolutionIntent.body === body &&
      /^\/ST_[A-Z0-9_]+\/versions\/[1-9][0-9]*\/execution-issues\/[0-9a-f-]{36}\/resolve$/.test(path);
    const resolutionRead =
      method === "GET" && resolutionOperation?.kind === "read" &&
      path === `${resolutionOperation.path}/regressions/${document.getElementById("issue-resolution-batch")?.value}`;
    if (malformed) {
      if ([401, 403, 404].includes(response.status)) {
        revoke("현재 작업 자격을 확인할 수 없습니다.", response.status);
        throw { status: response.status };
      }
      throw { status: 503 };
    }
    if (!response.ok) {
      if (missing && method === "GET" && response.status === 404) return null;
      const reviewCsrfRejection =
        data?.code === "CSRF_INVALID" &&
        response.status === 403 &&
        method === "POST" &&
        (/^\/ST_[A-Z0-9_]+\/versions\/[1-9][0-9]*\/(?:review-precheck|grade-samples\/check|review-requests|return-to-draft)$/.test(
          path,
        ) ||
          (manualJson !== undefined &&
            /^\/[A-Z0-9_]{1,40}\/versions\/[1-9][0-9]*\/review-snapshots\/[1-9][0-9]*\/records$/.test(
              path,
            )));
      const cloneCsrfRejection =
        clonePost && response.status === 403 && data?.code === "CSRF_INVALID";
      const resolutionCsrfRejection =
        resolutionPost && response.status === 403 && data?.code === "CSRF_INVALID";
      const resolutionMissing =
        (resolutionPost || resolutionRead) && response.status === 404 && data?.code === "NOT_FOUND";
      if (
        [401, 403, 404].includes(response.status) &&
        (clonePost || data?.code !== "REAUTH_REQUIRED") &&
        !reviewCsrfRejection &&
        !cloneCsrfRejection &&
        !resolutionCsrfRejection &&
        !resolutionMissing &&
        !(
          method === "POST" &&
          path === "" &&
          response.status === 403 &&
          data?.code === "FORBIDDEN"
        )
      )
        revoke(
          response.status === 401
            ? "세션이 만료되었습니다."
            : response.status === 403
              ? "접근 권한이 없습니다."
              : "사건이 없거나 접근할 수 없습니다.",
          response.status,
        );
      if (!data || typeof data.code !== "string") throw { status: 503 };
      if (data?.code === "CSRF_INVALID") csrf = undefined;
      if (
        clonePost &&
        response.status === 409 &&
        data.code === "WORK_VERSION_EXISTS" &&
        cloneKeys(data, ["code", "message", "requestId"], ["notice"]) &&
        data.message === "현재 상태를 다시 확인해 주세요." &&
        typeof data.requestId === "string" &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
          data.requestId,
        )
      ) {
        const safeNotice =
          cloneKeys(data.notice, ["versionNo", "draftPath"]) &&
          clonePath(
            data.notice.draftPath,
            cloneIntent.storyCode,
            data.notice.versionNo,
          );
        throw {
          status: 409,
          code: data.code,
          message: data.message,
          requestId: data.requestId,
          ...(safeNotice
            ? {
                notice: Object.freeze({
                  versionNo: data.notice.versionNo,
                  draftPath: data.notice.draftPath,
                }),
              }
            : {}),
        };
      }
      throw { status: response.status, code: data?.code };
    }
    if ((clonePost || resolutionPost || resolutionRead) && response.status !== 200) throw { status: 503 };
    return manualJson !== undefined
      ? { data, status: response.status }
      : privateReadText
        ? { data, originalJson }
        : data;
  }

  /** JSON 입력 원문을 삽입해 중복 키를 서버의 엄격 파서까지 전달한다. */
  function jsonFieldBody(body, rawFields) {
    const part = body.item ? "item" : "changes";
    const markers = Object.fromEntries(
      Object.keys(rawFields).map((field) => [field, crypto.randomUUID()]),
    );
    let json = JSON.stringify({
      ...body,
      [part]: { ...body[part], ...markers },
    });
    for (const [field, marker] of Object.entries(markers)) {
      const token = `${JSON.stringify(field)}:${JSON.stringify(marker)}`;
      if (!json.includes(token)) throw new Error("JSON 전송 형식 오류");
      json = json.replace(
        token,
        () => `${JSON.stringify(field)}:${rawFields[field]}`,
      );
    }
    return json;
  }

  /** 사건 경로나 원고 대신 고정 화면 코드만 기록하며 폐기된 세대의 자격은 복원하지 않는다. */
  async function navigation() {
    const current = generation;
    try {
      if (!csrf) {
        const response = await fetch("/admin/api/auth/csrf", {
          credentials: "same-origin",
          cache: "no-store",
        });
        if (!response.ok) return;
        const token = await response.json();
        if (current !== generation || (editor && editorIdentityRevoked)) return;
        csrf = token;
      }
      if (current !== generation || (editor && editorIdentityRevoked)) return;
      await fetch("/admin/api/history/navigation", {
        method: "POST",
        credentials: "same-origin",
        cache: "no-store",
        headers: {
          "Content-Type": "application/json",
          [csrf.headerName]: csrf.token,
        },
        body: JSON.stringify({
          eventKey: crypto.randomUUID(),
          screenCode: editor ? "ADMIN_STORY_EDITOR" : "ADMIN_STORIES",
        }),
      });
    } catch {
      /* 화면 이동 기록 실패가 원고나 저장 상태를 바꾸지 않도록 한다. */
    }
  }

  /** 라벨과 nullable 값을 HTML 해석 없이 정의 목록에 추가한다. */
  function labelValue(list, label, value) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent =
      value == null
        ? "미작성"
        : typeof value === "object"
          ? JSON.stringify(value, null, 2)
          : String(value);
    list.append(dt, dd);
  }

  /** 서버의 명시적 UTC 시각만 한국 표준시로 표시하고 오프셋 포함 표기는 유지한다. */
  function displayTime(value) {
    if (!value || !value.endsWith("Z")) return value || "수정 시각 없음";
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? value
      : new Intl.DateTimeFormat("ko-KR", {
          timeZone: "Asia/Seoul",
          dateStyle: "short",
          timeStyle: "medium",
        }).format(date) + " KST";
  }

  /** 현재 편집 경로에서 검증된 사건 코드와 양의 버전 번호만 추출한다. */
  function editorPath() {
    const match =
      /^\/admin\/stories\/(ST_[A-Z0-9_]+)\/versions\/([1-9][0-9]*)$/.exec(
        location.pathname,
      );
    if (!match || !Number.isSafeInteger(Number(match[2])))
      throw { status: 404 };
    return `/${encodeURIComponent(match[1])}/versions/${match[2]}`;
  }

  /** 한 영역의 저장 결과를 보조기기에 알리고 실제 미저장 선택을 요약한다. */
  function formStatus(form, text, error = false) {
    const message = form.querySelector("[data-save-status]");
    message.textContent = text;
    message.classList.toggle("error", error);
    updateDraftSummary();
  }

  /** 매개변수 없이 실제 선택된 영역·필드 수와 목차의 변경 여부만 표시한다. 원고 값은 노출하지 않는다. */
  function updateDraftSummary() {
    if (!editor) return;
    let sections = 0;
    let count = 0;
    for (const form of editor.querySelectorAll("[data-section]")) {
      const selected = [...form.querySelectorAll("[data-field]")].filter(
        (field) => field.value !== "keep",
      );
      if (selected.length) sections++;
      count += selected.length;
      editor
        .querySelector(`#editor-nav a[href="#${form.dataset.section}-heading"]`)
        ?.setAttribute("data-dirty", String(selected.length > 0));
      for (const field of form.querySelectorAll("[data-field]")) {
        field.closest(".field").dataset.dirty = String(field.value !== "keep");
        syncRecordView(field);
      }
    }
    document.getElementById("draft-summary").textContent = count
      ? `${sections}개 영역 · ${count}개 필드 미저장 선택 — 페이지를 떠나면 입력이 사라질 수 있습니다.`
      : "저장할 변경 없음";
    const preview = document.getElementById("pair-key-preview");
    preview.hidden =
      childResource !== "pairs" || !childEditing || Boolean(detail?.childItem);
    if (!preview.hidden) {
      const keys = ["roleA", "roleB"].map(
        (key) => editor.querySelector(`[data-value="${key}"]`)?.value || "",
      );
      preview.textContent =
        keys.every((key) => /^[A-Z0-9_]{1,32}$/.test(key)) &&
        keys[0] !== keys[1]
          ? `저장될 조합: ${keys.sort().join("~")} · 생성 뒤 변경 불가`
          : "서로 다른 활성 역할 코드 둘을 입력하면 저장될 정규 키를 표시합니다.";
    }
    if (childResource === "clue-roles" && childEditing && !detail?.childItem) {
      const keys = ["clueCode", "roleCode"].map(
        (key) => editor.querySelector(`[data-value="${key}"]`)?.value || "",
      );
      preview.hidden = false;
      preview.textContent = keys.every((key) => /^[A-Z0-9_]{1,32}$/.test(key))
        ? `저장될 배정: ${keys.join("~")} · 생성 뒤 변경 불가`
        : "활성 ROLE 단서 코드와 역할 코드를 입력하면 저장될 배정을 표시합니다.";
    }
  }

  /**
   * 보고서 본문과 입력 도구의 표시만 전환한다. 값·저장 동작·수정번호는 바꾸지 않는다.
   * @param {HTMLSelectElement} mode 기존 keep/value/clear 저장 동작 선택기.
   */
  function syncRecordView(mode) {
    const group = mode.closest(".field");
    const input = group.querySelector("[data-value]");
    const record = group.querySelector(".report-value");
    const fixed = group.dataset.fixed === "true";
    input.hidden = mode.value !== "value" || fixed;
    record.hidden = !input.hidden || mode.value === "clear";
    group.querySelector(".clear-record").hidden = mode.value !== "clear";
    if (fixed) {
      record.textContent = input.value;
      record.dataset.empty = "false";
    }
  }

  /** 매개변수 없이 현재 표시된 원고 영역의 제목을 반환하며 원고 DOM은 교체하지 않는다. */
  function activeEditorHeading() {
    return editor.querySelector(".manuscript > section:not([hidden]) h2");
  }

  /**
   * 대상이 속한 원고 영역의 표시와 목차 위치만 갱신한다. 요청·입력·세대는 변경하지 않는다.
   * @param {Element|null} target 기존 DOM 대상. 원고 밖이거나 null이면 표시를 바꾸지 않는다.
   */
  function revealEditorPanel(target) {
    const panel = target?.closest(".manuscript > section");
    if (!panel) return;
    for (const section of editor.querySelectorAll(".manuscript > section")) {
      section.hidden = section !== panel;
    }
    for (const link of editor.querySelectorAll("#editor-nav a")) {
      if (
        link.getAttribute("href") ===
        `#${panel.getAttribute("aria-labelledby")}`
      ) {
        link.setAttribute("aria-current", "location");
      } else {
        link.removeAttribute("aria-current");
      }
    }
  }

  /**
   * 알려진 문서 조각의 영역과 접힌 참고를 먼저 표시한 뒤 대상에 초점을 옮긴다.
   * @param {string} hash 같은 문서의 #id. 빈 값·잘못된 인코딩·없는 대상은 무시한다.
   * @returns {boolean} 표시된 대상으로 이동했는지 여부.
   */
  function focusEditorFragment(hash) {
    if (!editor || editor.hidden || !hash.startsWith("#")) return false;
    let id;
    try {
      id = decodeURIComponent(hash.slice(1));
    } catch {
      return false;
    }
    let target = document.getElementById(id);
    if (!target) return false;
    revealEditorPanel(target);
    const disclosure = target.closest("details");
    if (disclosure) disclosure.open = true;
    if (target.hidden && target.matches("[data-value]")) {
      target = target.closest(".field").querySelector("[data-field]");
    }
    if (target?.matches(":disabled")) target = activeEditorHeading();
    if (!target || target.closest("[hidden]")) return false;
    target.focus({ preventScroll: true });
    target.scrollIntoView({ block: "start" });
    return true;
  }

  /**
   * 고정 오류를 필드에 연결하고 숨겨진 영역을 먼저 표시한다.
   * @param {HTMLFormElement} form 기존 영역 폼. null은 허용하지 않는다.
   * @param {string} key 해당 폼에 존재하는 기존 필드 키.
   * @param {string} message 원고 값을 포함하지 않는 검증 안내.
   */
  function fieldError(form, key, message) {
    const input = form.querySelector(`[data-value="${key}"]`);
    const previous = document.getElementById(`${input.id}-error`);
    previous?.remove();
    const error = document.createElement("p");
    error.id = `${input.id}-error`;
    error.className = "error";
    error.setAttribute("role", "alert");
    error.textContent = message;
    input.after(error);
    input.setAttribute("aria-invalid", "true");
    input.setAttribute("aria-describedby", error.id);
    const target = input.hidden
      ? form.querySelector(`[data-field="${key}"]`)
      : input;
    target.setAttribute("aria-invalid", "true");
    target.setAttribute("aria-describedby", error.id);
    revealEditorPanel(target);
    target.focus();
  }

  /**
   * 저장값과 명시적 동작을 같은 필드 머리글로 묶으며 저장 계약을 유지한다.
   * @param {HTMLFormElement} form 교체할 영역 폼. null은 허용하지 않는다.
   * @param {"basic"|"answer"|"reveal"|"child"} section 기존 저장 영역.
   * @param {object|null|undefined} values 서버 값. 없으면 미작성 입력으로 표시한다.
   */
  function populateForm(form, section, values) {
    form.replaceChildren();
    form.noValidate = true;
    let timing;
    for (const [key, label, kind, nullable] of fields[section]) {
      const group = document.createElement("div");
      group.className = "field";
      if (
        section === "child" &&
        ((childResource === "persons" && key === "secretText") ||
          (childResource === "clues" && key === "sourceText"))
      ) {
        group.dataset.boundary = "private";
      }
      const id = `${section}-${key}`;
      const head = document.createElement("div");
      head.className = "field-head";
      const name = document.createElement("label");
      name.htmlFor = id;
      name.id = `${id}-label`;
      name.textContent = label;
      const modeLabel = document.createElement("label");
      modeLabel.className = "visually-hidden";
      modeLabel.htmlFor = `${id}-mode`;
      modeLabel.textContent = `${label} 저장 동작`;
      const mode = document.createElement("select");
      mode.id = `${id}-mode`;
      mode.dataset.field = key;
      for (const [value, text] of [
        ["keep", "기록 유지"],
        ["value", "직접 수정"],
        ...(nullable ? [["clear", "기록 비우기"]] : []),
      ]) {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = text;
        mode.append(option);
      }
      const input = document.createElement(
        kind === "textarea" || kind === "json"
          ? "textarea"
          : kind === "select" || kind === "boolean"
            ? "select"
            : "input",
      );
      if (!["textarea", "json", "select", "boolean"].includes(kind))
        input.type = kind;
      if (kind === "select" || kind === "boolean") {
        for (const [value, text] of childTypes[childResource].options[key]) {
          const option = document.createElement("option");
          option.value = value;
          option.textContent = text;
          input.append(option);
        }
      }
      input.id = id;
      input.dataset.value = key;
      input.autocomplete = "off";
      if (kind === "number") {
        input.step = "1";
        input.min = String(numberMinimum(section));
        input.max = String(numberLimit(section, key));
      }
      input.value =
        values?.[key] == null
          ? kind === "boolean"
            ? "false"
            : ""
          : typeof values[key] === "object"
            ? JSON.stringify(values[key], null, 2)
            : String(values[key]);
      inputBaselines.set(input, input.value);
      const record = document.createElement("p");
      record.className = "report-value";
      record.id = `${id}-record`;
      record.setAttribute("aria-labelledby", name.id);
      record.dataset.empty = String(
        values?.[key] == null || values[key] === "",
      );
      record.textContent =
        values?.[key] == null
          ? "아직 작성된 기록이 없습니다."
          : values[key] === ""
            ? "빈 문자열로 저장된 기록입니다."
            : typeof values[key] === "object"
              ? JSON.stringify(values[key], null, 2)
              : String(values[key]);
      const clearing = document.createElement("p");
      clearing.className = "clear-record";
      clearing.textContent =
        "이 기록을 미작성 상태로 비웁니다. 아직 저장하지 않았습니다.";
      clearing.hidden = true;
      input.hidden = true;
      head.append(name, modeLabel, mode);
      group.append(head, record, input, clearing);
      if (section === "basic" && kind === "number") {
        if (!timing) {
          timing = document.createElement("fieldset");
          timing.className = "timing-fields";
          const legend = document.createElement("legend");
          legend.textContent = "난이도·진행 시간";
          timing.append(legend);
          form.append(timing);
        }
        timing.append(group);
      } else {
        form.append(group);
      }
      mode.addEventListener("change", () => {
        input.readOnly = mode.value !== "value";
        if (kind === "select" || kind === "boolean")
          input.disabled = mode.value !== "value";
        input.required = mode.value === "value" && !nullable;
        document.getElementById(`${input.id}-error`)?.remove();
        input.removeAttribute("aria-invalid");
        input.removeAttribute("aria-describedby");
        mode.removeAttribute("aria-describedby");
        mode.removeAttribute("aria-invalid");
        const selected = [...form.querySelectorAll("[data-field]")].some(
          (field) => field.value !== "keep",
        );
        formStatus(
          form,
          selected ? "이 영역에 미저장 선택이 있습니다." : "저장할 변경 없음",
        );
      });
      input.readOnly = true;
      if (kind === "select" || kind === "boolean") input.disabled = true;
      input.addEventListener("input", () => {
        document.getElementById(`${input.id}-error`)?.remove();
        input.removeAttribute("aria-invalid");
        input.removeAttribute("aria-describedby");
        mode.removeAttribute("aria-describedby");
        mode.removeAttribute("aria-invalid");
        formStatus(form, "이 영역에 미저장 입력이 있습니다.");
      });
    }
    const button = document.createElement("button");
    button.type = "submit";
    button.id = `${section}-save`;
    button.textContent = {
      basic: "기본 정보 저장",
      answer: "정답 원고 저장",
      reveal: "해설 저장",
      child: `${childTypes[childResource].label} 저장`,
    }[section];
    const actions = document.createElement("div");
    actions.className = "form-actions";
    actions.append(button);
    const feedback = document.createElement("p");
    feedback.dataset.saveStatus = "";
    feedback.className = "muted";
    feedback.setAttribute("role", "status");
    feedback.setAttribute("aria-live", "polite");
    feedback.textContent = "저장할 변경 없음";
    const back = document.createElement("a");
    back.href = "#editor-nav";
    back.className = "section-return";
    back.textContent = "영역 이동";
    actions.append(feedback, back);
    form.append(actions);
  }

  /**
   * 입력은 메모리에만 복사하며 기본값에서는 실제 저장 선택만 반환한다.
   * @param {boolean} includeBuffers true이면 유지 모드 뒤의 변경 버퍼도 보존·폐기 확인에 포함한다.
   */
  function captureDrafts(includeBuffers = false) {
    const drafts = {};
    for (const form of editor.querySelectorAll("[data-section]")) {
      const section = form.dataset.section;
      drafts[section] = {};
      for (const select of form.querySelectorAll("[data-field]")) {
        const input = form.querySelector(
          `[data-value="${select.dataset.field}"]`,
        );
        if (
          select.value === "keep" &&
          (!includeBuffers || input.value === inputBaselines.get(input))
        )
          continue;
        drafts[section][select.dataset.field] = {
          mode: select.value,
          value: input.value,
        };
      }
    }
    return drafts;
  }

  /**
   * 확인된 서버본을 반영하되 확정된 제출 외 입력과 현재 영역을 보존한다.
   * @param {object} data 기존 상세 조회 계약의 서버본.
   * @param {object} options preserve는 입력 보존, confirmed는 제출 영수증, acceptPermissions는 명시적 최신 기준 수락이다.
   * @returns {void} 이전 동의를 거절하고 현재 서버본과 보존 가능한 입력을 표시한다.
   */
  function fillDetail(
    data,
    { preserve = false, confirmed, acceptPermissions = false } = {},
  ) {
    AdminUI.cancelConfirmation();
    if (!detail || acceptPermissions) {
      const permissions = copyPermissions(data.permissions);
      permissionDenied = Object.fromEntries(
        ["edit", "review", "publish"].map((key) => [
          key,
          permissions?.[key] !== true,
        ]),
      );
    }
    savedDetailPending = false;
    resetSavedReview("unopened");
    invalidateReview(true);
    ++comparisonRequest;
    const focusedId = document.activeElement?.id;
    const selection = document.activeElement?.selectionStart;
    const drafts = preserve && detail ? captureDrafts(true) : {};
    detail = data;
    cloneCandidate = cloneMetadata(data);
    document.getElementById("state-heading").textContent =
      data.sections.basic.title;
    document.getElementById("version-summary").textContent =
      `버전 ${data.versionNo} · 수정번호 ${data.editRev} · ${versionStates[data.status] || data.status} · ${data.storyActiveYn && data.activeYn ? "활성" : "비활성 · 읽기 전용"}`;
    const state = document.getElementById("version-state");
    state.replaceChildren();
    for (const [label, value] of [
      ["사건 코드", data.storyCode],
      ["버전", data.versionNo],
      ["수정번호", data.editRev],
      ["상태", data.status],
      ["사건 활성", data.storyActiveYn ? "예" : "아니요"],
      ["버전 활성", data.activeYn ? "예" : "아니요"],
      ["소유 계정", data.ownerAccountKey],
      ["수정 시각", displayTime(data.updatedAt)],
    ])
      labelValue(state, label, value);
    const warning = document.getElementById("warnings");
    document.getElementById("warning-count").textContent =
      `작성 확인 ${(data.warnings || []).length}건`;
    warning.replaceChildren();
    const heading = document.createElement("h3");
    heading.textContent = "저장 경고 (검수 통과가 아님)";
    warning.append(heading);
    const list = document.createElement("ul");
    for (const item of data.warnings || []) {
      const row = document.createElement("li");
      const [section, key] = item.field.split(".");
      const label = fields[section]?.find((field) => field[0] === key)?.[1];
      const message =
        {
          MISSING_CONTENT: "미작성",
          SCORE_TOTAL: "분류별 최대 점수 합계를 확인하세요",
          POLICY_TIME_RANGE: "권장 시간 범위 확인",
          REFERENCE_UNASSIGNED: "역할 배정 없음 · 초안 저장 가능",
        }[item.code] || item.code;
      if (item.code === "REFERENCE_UNASSIGNED" && section === "clues") {
        const link = document.createElement("a");
        link.href = "#child-resource";
        link.textContent = `단서 ${key} · 역할 배정 없음 (초안 저장 가능). 자료 종류에서 단서 역할 배정을 선택하세요.`;
        row.append(link);
        list.append(row);
        continue;
      }
      if (section === "rubrics") {
        const [, target, property] = item.field.split(".");
        const link = document.createElement("a");
        link.href = "#child-resource";
        const field = childTypes.rubrics.fields.find(
          ([name]) => name === property,
        );
        link.textContent = `${target} · ${field?.[1] || "분류별 점수"} · ${message}. 자료 종류에서 채점 소항목을 선택하고 해당 코드를 여세요.`;
        row.append(link);
        list.append(row);
        continue;
      }
      if (label) {
        const link = document.createElement("a");
        link.href = `#${section}-${key}-mode`;
        link.textContent = `${label} · ${message}`;
        row.append(link);
      } else {
        row.textContent = `${item.field} · ${message}`;
      }
      list.append(row);
    }
    if (!list.childElementCount) {
      const row = document.createElement("li");
      row.textContent = "현재 경고 없음";
      list.append(row);
    }
    warning.append(list);
    const policy = document.getElementById("policy");
    policy.replaceChildren();
    const policyLabels = {
      policyCode: "정책 코드",
      attemptLimit: "제출 한도",
      hintsPerPerson: "인당 힌트",
      wrongPenalty: "오답 감점",
      categoryScores: "분류별 배점",
    };
    for (const [key, value] of Object.entries(data.policy || {})) {
      const scores = {
        CULPRIT: "범인",
        METHOD: "수법",
        TIME: "시간",
        MOTIVE: "동기",
        EVIDENCE: "근거",
      };
      labelValue(
        policy,
        policyLabels[key] || key,
        key === "categoryScores"
          ? Object.entries(value)
              .map(
                ([category, score]) =>
                  `${scores[category] || category} ${score}점`,
              )
              .join(" · ")
          : value,
      );
    }
    for (const section of Object.keys(fields)) {
      const form = editor.querySelector(`[data-section="${section}"]`);
      populateForm(form, section, data.sections?.[section]);
      const writable = writableSection(section, data);
      for (const [key, draft] of Object.entries(drafts[section] || {})) {
        const sent =
          confirmed?.section === section ? confirmed.drafts[key] : undefined;
        if (sent && sent.mode === draft.mode && sent.value === draft.value)
          continue;
        const mode = form.querySelector(`[data-field="${key}"]`);
        const input = form.querySelector(`[data-value="${key}"]`);
        mode.value = draft.mode;
        input.value = draft.value;
        input.readOnly = !writable || draft.mode !== "value";
        if (input.tagName === "SELECT")
          input.disabled = !writable || draft.mode !== "value";
        input.required =
          writable &&
          draft.mode === "value" &&
          fields[section].some(
            ([name, , , nullable]) => name === key && !nullable,
          );
      }
      for (const mode of form.querySelectorAll("[data-field]"))
        mode.disabled = !writable;
      form.querySelector("button").disabled = !writable;
    }
    editor.hidden = false;
    document.getElementById("unavailable").hidden = true;
    document.getElementById("comparison").hidden = true;
    saveLocked = false;
    resetChildren();
    syncChildControls();
    // 고정 자식 키를 유지 모드로 확정한 뒤 실제 남은 선택만 안내한다.
    for (const form of editor.querySelectorAll("[data-section]")) {
      if (
        [...form.querySelectorAll("[data-field]")].some(
          (field) => field.value !== "keep",
        )
      )
        formStatus(form, "이 영역에 미저장 입력이 남아 있습니다.");
    }
    updateDraftSummary();
    syncPermissionControls();
    if (preserve && focusedId) {
      const target = document.getElementById(focusedId);
      if (target && !target.closest("[hidden]")) {
        target.focus({ preventScroll: true });
        if (selection != null && ["text", "textarea"].includes(target.type))
          target.setSelectionRange(selection, selection);
      } else {
        activeEditorHeading()?.focus();
      }
    }
    if (!editorFragmentApplied) {
      editorFragmentApplied = true;
      focusEditorFragment(window.location.hash);
    }
    status(
      writableSection("basic", data)
        ? "현재 원고를 조회했습니다. 바꿀 항목만 저장 동작을 선택하세요."
        : "현재 버전은 읽기 전용입니다.",
    );
  }

  /**
   * 서버 편집 권한·거절 울타리·부모와 선택 자료의 상태를 함께 확인한다.
   * @param {string} section basic/answer/reveal/child 중 원고 영역.
   * @param {object} data 기본은 현재 쓰기 기준 상세이며 원고 표시 때 새 상세도 받는다.
   * @returns {boolean} 해당 DRAFT 원고의 수정 도구 허용 여부. 서버 인가를 대신하지 않는다.
   */
  function writableSection(section, data = detail) {
    return Boolean(
      data?.storyActiveYn &&
      data.activeYn &&
      data.permissions?.edit === true &&
      hasPermission("edit") &&
      !lifecycleNeedsRead &&
      data.status === "DRAFT" &&
      (section !== "child" ||
        (childEditing && (!data.childItem || data.childItem.activeYn))),
    );
  }

  /** 자료 부재·활성·논리 삭제를 원고 없이 비교 안내에 표시한다. */
  function childState(item) {
    return !item
      ? "저장된 동일 코드 없음"
      : item.activeYn
        ? "활성"
        : "논리 삭제";
  }

  /**
   * 편집 작업의 소유 세대를 교체해 이전 동의·목록·선택·비교 응답을 모두 무효화한다.
   * 미전송 수동 후보와 해소 응답 소유권을 종료하며 보관된 전송 의도와 새 입력은 복원 없이 유지한다.
   * @returns {number} 새 편집 작업이 소유하는 세대 번호.
   */
  function beginEditorOperation() {
    AdminUI.cancelConfirmation();
    if (resolutionOperation) {
      resolutionOperation = undefined;
      document.getElementById("issue-resolution-status").textContent =
        "기준이 교체되어 이전 응답은 채택하지 않습니다. 이미 보낸 의도와 새 입력은 유지됩니다.";
      syncResolutionControls();
    }
    // 같은 경로·논리 신원의 이미 전송한 SR05 영수증은 상세/권한 관측 교체와 별개다.
    if (manualOperation && !manualOperation.sent) {
      manualOperation = undefined;
      document.getElementById("manual-status").textContent =
        "기준이 교체되어 전송하지 않았습니다. 기록 입력과 이미 보낸 원래 의도는 유지됩니다.";
    }
    if (lifecycleOperation?.sent) {
      if (lifecycleIntent?.body === lifecycleOperation.body)
        lifecycleIntent.unknown = true;
      const receipt = document.getElementById("review-lifecycle-receipt");
      if (receipt?.dataset.state === "unknown")
        receipt.textContent =
          "이번 전환 결과 미확인 · 기준 교체로 이전 지연 응답은 반영하지 않습니다. 현재 GET은 영수증이 아닙니다.";
    }
    if (lifecycleOperation) lifecycleOperation.source = undefined;
    lifecycleOperation = undefined;
    if (lifecycleIntent && !lifecycleIntent.sent) lifecycleIntent = undefined;
    resetSavedReview();
    invalidateReview(true);
    ++childRequest;
    ++comparisonRequest;
    latest = undefined;
    document.getElementById("accept-latest").disabled = true;
    const rows = document.getElementById("child-rows");
    if (rows.hasAttribute("aria-busy")) {
      rows.removeAttribute("aria-busy");
      document.getElementById("child-list-status").textContent =
        "이전 목록 응답은 반영하지 않습니다. 현재 작업 완료 후 다시 조회하세요.";
    }
    return ++generation;
  }

  /** 검증한 ASCII 키를 고정 자원의 구성 필드로 분해하며 새로운 저장값을 만들지 않는다. */
  function childKeyValues(key, resource = childResource) {
    const parts = key.split("~");
    return Object.fromEntries(
      childTypes[resource].keys.map((field, index) => [field, parts[index]]),
    );
  }

  /**
   * 자료 종류 변경 동의를 기존 상세와 선택에 결속하고 대기 중 선택 표시를 유지한다.
   * 확인창을 열 때의 자료 모드·입력 버퍼가 바뀌면 입력을 보존하고 새 동의를 요구한다.
   * @param {string} resource childTypes에 등록된 요청 종류. 잘못된 값은 변경하지 않는다.
   * @returns {Promise<void>} 같은 신원의 유효한 동의만 종류를 바꾼다. 신원 미확인은 입력을 폐기한다.
   */
  async function selectChildResource(resource) {
    const select = document.getElementById("child-resource");
    const current = generation;
    const previousDetail = detail;
    const previousResource = childResource;
    const previousKey = childKey;
    select.value = childResource;
    if (!Object.hasOwn(childTypes, resource) || saveLocked || !detail) return;
    let consentedDraft;
    if (Object.keys(captureDrafts(true).child).length) {
      if (!(await observeEditorIdentity())) return;
      consentedDraft = JSON.stringify(captureDrafts(true).child);
      if (
        !(await AdminUI.confirm({
          title: "자료 종류 변경",
          message:
            "현재 자료의 미저장 입력을 버리고 종류를 바꿉니까? 다른 영역 입력은 유지됩니다.",
          confirmLabel: "종류 변경",
        }))
      )
        return;
      if (!(await observeEditorIdentity())) return;
    }
    if (
      current !== generation ||
      detail !== previousDetail ||
      childResource !== previousResource ||
      childKey !== previousKey ||
      saveLocked ||
      !select.isConnected ||
      !Object.hasOwn(childTypes, resource)
    )
      return;
    if (
      consentedDraft !== undefined &&
      consentedDraft !== JSON.stringify(captureDrafts(true).child)
    ) {
      status("미저장 입력이 바뀌었습니다. 다시 확인해 주세요.", true);
      return;
    }
    beginEditorOperation();
    childResource = resource;
    select.value = resource;
    childKey = undefined;
    childEditing = false;
    detail = {
      ...detail,
      childResource: resource,
      childKey: undefined,
      childItem: null,
      sections: { ...detail.sections, child: {} },
    };
    populateForm(editor.querySelector('[data-section="child"]'), "child", {});
    document.getElementById("child-filter").value = "true";
    resetChildren();
    syncChildControls();
    loadChildren();
  }

  /**
   * 호출 시 자원·키를 고정하며 부모/자식 수정번호와 부재의 부모 재인가를 확인한다.
   * @param {string|undefined} key 기본은 선택한 자식 키이며 새 작성은 undefined.
   * @param {string} resource 기본은 현재 childTypes 자원 이름.
   * @param {function|undefined} guard 선택적 조회 소유권 검사. 만료되면 후속 조회와 응답 반영을 중단한다.
   * @returns {Promise<object>} 검증한 권한 복사본과 같은 번호의 자식만 포함한 상세.
   * @throws {object} 현재 자격·조회·수정번호·소유권 실패는 호출자에게 전달한다.
   */
  async function readEditor(key = childKey, resource = childResource, guard) {
    const current = generation;
    const source = detail;
    const path = editorPath();
    const owns = (phase) =>
      current === generation &&
      source === detail &&
      path === editorPath() &&
      (!guard || guard(phase));
    const data = await request("GET", path, undefined, false, undefined, owns);
    data.permissions = observePermissions(data.permissions);
    let item = null;
    if (key) {
      const child = await request(
        "GET",
        `${path}/${resource}/${encodeURIComponent(key)}`,
        undefined,
        true,
        undefined,
        owns,
      );
      let revision = child?.editRev;
      if (revision === undefined) {
        const parent = await request(
          "GET",
          path,
          undefined,
          false,
          undefined,
          owns,
        );
        data.permissions = observePermissions(parent.permissions);
        revision = parent.editRev;
      }
      if (revision !== data.editRev)
        throw { status: 503, code: "READ_REVISION_CHANGED" };
      item = child?.item ?? null;
    }
    return {
      ...data,
      permissionEpoch,
      childResource: resource,
      childKey: key,
      childItem: item,
      sections: {
        ...data.sections,
        child: item || (key ? childKeyValues(key, resource) : {}),
      },
    };
  }

  /** 새 부모 기준을 받으면 과거 커서와 목록을 폐기한다. 조회만으로 입력 기준을 승계하지 않는다. */
  function resetChildren() {
    ++childRequest;
    childAfter = undefined;
    document.getElementById("child-rows").replaceChildren();
    document.getElementById("child-rows").removeAttribute("aria-busy");
    document.getElementById("child-next").hidden = true;
    document.getElementById("child-list-status").textContent =
      "현재 수정번호의 자료 목록을 조회하세요.";
  }

  /** 생성 키·비활성 원고·공통 저장 잠금에 맞춰 현재 자원의 조작을 제한한다. */
  function syncChildControls() {
    if (!document.getElementById("child-edit")) return;
    for (const button of editor.querySelectorAll("form[data-section] button"))
      button.disabled =
        saveLocked || !writableSection(button.closest("form").dataset.section);
    document.getElementById("child-edit").hidden = !childEditing;
    for (const id of ["child-list", "child-next", "child-filter"])
      document.getElementById(id).disabled = saveLocked || !detail;
    document.getElementById("child-resource").disabled = saveLocked || !detail;
    const type = childTypes[childResource];
    document.getElementById("child-guide").textContent = type.guide;
    document.getElementById("child-new").textContent = `새 ${type.label} 작성`;
    document.getElementById("child-list").textContent =
      `${type.label} 목록 조회`;
    document.getElementById("child-next").textContent = `다음 ${type.label}`;
    document.getElementById("child-edit-heading").textContent =
      `선택한 ${type.label}의 기록`;
    document.getElementById("child-new").disabled =
      saveLocked || !writableSection("basic");
    for (const button of document.querySelectorAll("[data-child-key]"))
      button.disabled = saveLocked;
    const action = document.getElementById("child-active");
    action.hidden = !childEditing || !detail?.childItem;
    action.disabled = saveLocked || !writableSection("basic");
    action.textContent = `${type.label} ${detail?.childItem?.activeYn ? "논리 삭제" : "복원"}`;
    const form = editor.querySelector('[data-section="child"]');
    form.querySelector("button").hidden =
      ["pairs", "clue-roles"].includes(childResource) &&
      Boolean(detail?.childItem);
    for (const key of type.keys) {
      const input = form.querySelector(`[data-value="${key}"]`);
      const mode = form.querySelector(`[data-field="${key}"]`);
      const fixed = Boolean(childKey);
      if (fixed) {
        input.readOnly = true;
        mode.disabled = true;
        mode.value = detail?.childItem ? "keep" : "value";
        input.value = childKeyValues(childKey)[key];
      }
      input.closest(".field").dataset.fixed = String(fixed);
    }
    syncRubricFixedFields();
    if (childEditing)
      document.getElementById("child-state").textContent =
        `${childKey || `새 ${type.label}`} · ${childState(detail?.childItem)} · 수정번호 ${detail?.editRev}${detail?.childItem ? ` · ${displayTime(detail.childItem.updatedAt)}` : ""}`;
    updateDraftSummary();
    syncReviewControls();
  }

  /** 범인 분류의 서버 고정 입력을 잠그되 분류를 되돌리면 미저장 입력을 그대로 복원한다. */
  function syncRubricFixedFields() {
    if (childResource !== "rubrics") return;
    const form = editor.querySelector('[data-section="child"]');
    const categoryMode = form.querySelector('[data-field="category"]');
    if (!categoryMode) return;
    const category =
      categoryMode.value === "value"
        ? form.querySelector('[data-value="category"]').value
        : detail?.childItem?.category;
    const fixed = category === "CULPRIT";
    for (const key of ["maxScore", "requiredYn", "passScore", "ruleData"]) {
      const mode = form.querySelector(`[data-field="${key}"]`);
      const input = form.querySelector(`[data-value="${key}"]`);
      mode.disabled = fixed || saveLocked || !writableSection("child");
      input.readOnly =
        fixed ||
        mode.value !== "value" ||
        saveLocked ||
        !writableSection("child");
      if (input.tagName === "SELECT") input.disabled = input.readOnly;
      input.setAttribute("aria-readonly", String(input.readOnly));
    }
  }

  /**
   * 불확실한 다중 조회는 동의와 과거 비교 수락을 무효화하고 명시적 GET 재시도만 허용한다.
   * @param {string} message 원고를 포함하지 않는 고정 조회 실패 안내.
   * @returns {void} 입력은 유지하고 쓰기를 잠근다. 상세가 없으면 아무것도 바꾸지 않는다.
   */
  function lockForReview(message) {
    AdminUI.cancelConfirmation();
    resetSavedReview();
    invalidateReview(true);
    if (!detail) return;
    saveLocked = true;
    latest = undefined;
    document.getElementById("comparison").hidden = false;
    document.getElementById("latest-values").textContent =
      "입력은 유지됩니다. 같은 수정번호의 사건·선택 자료를 다시 조회해야 합니다.";
    document.getElementById("accept-latest").disabled = true;
    document.getElementById("refresh-latest").disabled = false;
    for (const button of editor.querySelectorAll("form[data-section] button"))
      button.disabled = true;
    syncChildControls();
    status(message, true);
  }

  /**
   * 같은 버전 자료를 조회하며 다른 영역의 입력과 탐색 위치를 보존한다.
   * 확인창을 열 때의 자료 모드·입력 버퍼가 바뀌면 입력을 보존하고 새 동의를 요구한다.
   * @param {string|undefined} key 현재 자원의 키. 생략하면 새 작성이며 비동기 응답은 다른 영역의 초점을 빼앗지 않는다.
   * @param {HTMLButtonElement} button 요청한 목록·새 작성 버튼. 분리되거나 비활성화되면 동의는 무효다.
   * @returns {Promise<void>} 같은 신원의 동의만 선택을 바꾸며 신원 미확인은 입력을 폐기한다.
   */
  async function selectChild(key, button) {
    if (!detail || saveLocked) return;
    const generationBefore = generation;
    const detailBefore = detail;
    const resourceBefore = childResource;
    const keyBefore = childKey;
    if (
      key !== undefined &&
      !/^[A-Z0-9_]{1,32}(?:~[A-Z0-9_]{1,32})?$/.test(key)
    )
      return;
    let consentedDraft;
    if (Object.keys(captureDrafts(true).child).length) {
      if (!(await observeEditorIdentity())) return;
      consentedDraft = JSON.stringify(captureDrafts(true).child);
      if (
        !(await AdminUI.confirm({
          title: "자료 열기",
          message:
            "현재 자료의 미저장 입력을 버리고 다른 항목을 작성·조회합니까? 다른 영역 입력은 유지됩니다.",
          confirmLabel: "입력 버리고 열기",
        }))
      )
        return;
      if (!(await observeEditorIdentity())) return;
    }
    if (
      generationBefore !== generation ||
      detailBefore !== detail ||
      resourceBefore !== childResource ||
      keyBefore !== childKey ||
      saveLocked ||
      !button.isConnected ||
      button.disabled ||
      button.closest("[hidden]") ||
      (key !== undefined &&
        key.split("~").length !== childTypes[resourceBefore].keys.length)
    )
      return;
    if (
      consentedDraft !== undefined &&
      consentedDraft !== JSON.stringify(captureDrafts(true).child)
    ) {
      status("미저장 입력이 바뀌었습니다. 다시 확인해 주세요.", true);
      return;
    }
    const current = beginEditorOperation();
    childEditing = true;
    childKey = key;
    detail = {
      ...detail,
      childItem: null,
      sections: { ...detail.sections, child: {} },
    };
    const form = editor.querySelector('[data-section="child"]');
    populateForm(form, "child", key ? childKeyValues(key) : {});
    if (!key) {
      for (const field of childTypes[childResource].required) {
        const mode = form.querySelector(`[data-field="${field}"]`);
        mode.value = "value";
        mode.dispatchEvent(new Event("change"));
      }
      syncChildControls();
      document
        .getElementById(`child-${childTypes[childResource].keys[0]}`)
        .focus();
      return;
    }
    saveLocked = true;
    syncChildControls();
    try {
      const data = await readEditor(key);
      if (current !== generation || key !== childKey) return;
      if (data.editRev !== detail.editRev) showComparison(data);
      else fillDetail(data, { preserve: true });
      if (
        !document.getElementById("child-heading").closest("section").hidden &&
        document.getElementById("comparison").hidden
      ) {
        document.getElementById("child-edit-heading").focus();
      }
    } catch (error) {
      if (current === generation && ![401, 403, 404].includes(error.status))
        lockForReview(
          "자료 조회 결과가 불확실합니다. 최신본 다시 조회로 확인하세요.",
        );
    }
  }

  /** 고정 filters와 afterId/afterKey를 조립한다. cursor가 없으면 첫 페이지이며 크기는 20이다. */
  function pageParams(filters, key, cursor) {
    const params = new URLSearchParams({ size: "20", ...filters });
    if (cursor) params.set(key, cursor);
    return params;
  }

  /** append=true일 때 다음 키 페이지를 붙인다. 현재 편집 수정번호와 다르면 비교 전까지 차단한다. */
  async function loadChildren(append = false) {
    if (!detail || saveLocked) return;
    const current = generation;
    const serial = ++childRequest;
    const rows = document.getElementById("child-rows");
    const message = document.getElementById("child-list-status");
    const params = pageParams(
      { activeYn: document.getElementById("child-filter").value },
      "afterKey",
      append ? childAfter : undefined,
    );
    document.getElementById("child-list").disabled = true;
    document.getElementById("child-next").disabled = true;
    rows.setAttribute("aria-busy", "true");
    const type = childTypes[childResource];
    message.textContent = `${type.label} 키 목록 조회 중입니다.`;
    try {
      const data = await request(
        "GET",
        `${editorPath()}/${childResource}?${params}`,
      );
      if (current !== generation || serial !== childRequest) return;
      if (data.editRev !== detail.editRev) {
        lockForReview(
          "목록의 수정번호가 바뀌었습니다. 원고와 같은 기준인지 비교해야 합니다.",
        );
        await compare();
        return;
      }
      if (!append) rows.replaceChildren();
      for (const item of data.items) {
        const key = type.keys.length === 2 ? item.itemKey : item.code;
        const row = document.createElement("div");
        row.className = "story-row";
        const button = document.createElement("button");
        button.type = "button";
        button.className = "secondary";
        button.dataset.childKey = key;
        button.textContent = `${key} · ${item.activeYn ? "활성" : "논리 삭제"} ${type.label} 열기`;
        button.addEventListener("click", (event) =>
          selectChild(key, event.currentTarget),
        );
        const time = document.createElement("p");
        time.className = "muted";
        time.textContent = displayTime(item.updatedAt);
        row.append(button, time);
        rows.append(row);
      }
      childAfter = data.nextAfterKey;
      document.getElementById("child-next").hidden = !data.hasNext;
      message.textContent = rows.childElementCount
        ? `현재 페이지까지 ${type.label} 키를 조회했습니다.`
        : `이 상태의 ${type.label}이 없습니다.`;
    } catch (error) {
      if (current === generation && ![401, 403, 404].includes(error.status)) {
        message.textContent =
          "자료 목록을 조회하지 못했습니다. 목록 조회로 다시 확인하세요.";
        status(errorMessage(error), true);
      }
    } finally {
      if (current === generation && serial === childRequest) {
        rows.removeAttribute("aria-busy");
        syncChildControls();
      }
    }
  }

  /**
   * 현재 허용된 버전을 읽는 동안 저장 검수 열람을 폐기·잠그며 변경 영수증으로 입력을 채우지 않는다.
   * @param {object|undefined} options 기존 preserve/confirmed/expectedRev 조건. 생략하면 새 상세를 표시한다.
   * @returns {Promise<boolean|null>} 반영 true, 비교 필요 false, 교체된 작업 null.
   * @throws {object} 기존 상세 조회 오류를 호출자의 비교·재조회 안내로 전달한다.
   */
  async function loadDetail(options) {
    const current = beginEditorOperation();
    savedDetailPending = true;
    syncSavedReviewControls();
    status("현재 사건 자료를 조회하고 있습니다.");
    const data = await readEditor();
    if (
      current !== generation ||
      data.childKey !== childKey ||
      data.childResource !== childResource
    )
      return null;
    if (
      options?.expectedRev !== undefined &&
      (data.editRev !== options.expectedRev ||
        data.status !== detail.status ||
        data.storyActiveYn !== detail.storyActiveYn ||
        data.activeYn !== detail.activeYn)
    ) {
      showComparison(data);
      return false;
    }
    fillDetail(data, options);
    return true;
  }

  /**
   * 충돌 시 기존 동의를 거절하고 현재 입력과 서버 최신본을 별도로 표시한다.
   * @param {object} data 현재 선택 자료를 포함한 기존 상세 조회 계약의 서버본. null은 허용하지 않는다.
   * @param {boolean} forLifecycle 기본 false. 명시적 전환 비교만 REVIEW/READY 기준 수락을 제공한다.
   * @returns {void} 입력은 유지하고 검토 전 쓰기를 잠근다.
   */
  function showComparison(data, forLifecycle = false) {
    AdminUI.cancelConfirmation();
    resetSavedReview();
    invalidateReview(true);
    saveLocked = true;
    syncChildControls();
    for (const button of editor.querySelectorAll("form[data-section] button"))
      button.disabled = true;
    latest = data;
    lifecycleComparison = forLifecycle;
    const panel = document.getElementById("comparison");
    const values = document.getElementById("latest-values");
    values.replaceChildren();
    const summary = document.createElement("p");
    summary.textContent = `기존 수정번호 ${detail.editRev} → 서버 최신 수정번호 ${data.editRev}. 선택한 입력은 아래 폼에 그대로 남아 있습니다.`;
    values.append(summary);
    const provenance = document.createElement("dl");
    for (const [label, value] of [
      [
        "기존 쓰기 상태 / 현재 사본",
        `${detail.status} / ${detail.currentSnapshotId ?? "null"}`,
      ],
      [
        "이번 GET 상태 / 현재 사본",
        `${data.status} / ${data.currentSnapshotId ?? "null"}`,
      ],
      ["이번 GET 사건 / 버전 활성", `${data.storyActiveYn} / ${data.activeYn}`],
      [
        "이번 GET 검증된 행동 권한",
        copyPermissions(data.permissions) ?? "미확인 · 쓰기 차단",
      ],
    ])
      labelValue(provenance, label, value);
    values.append(provenance);
    if (childEditing) {
      const state = document.createElement("p");
      state.textContent = `선택 ${childTypes[childResource].label} ${childKey || "(새 키 입력 전)"} · 이전: ${childState(detail.childItem)} → 최신: ${childState(data.childItem)}. 키가 이미 있으면 새 생성 대신 기존 자료를 확인합니다.`;
      values.append(state);
    }
    const readable = (value) =>
      value === null || value === undefined
        ? "null (미작성)"
        : value === ""
          ? "(빈 문자열)"
          : typeof value === "object"
            ? JSON.stringify(value, null, 2)
            : value;
    let shown = 0;
    for (const [section, definitions] of Object.entries(fields)) {
      if (section === "child" && !childEditing) continue;
      let group;
      for (const [key, label] of definitions) {
        const mode = editor.querySelector(
          `[data-section="${section}"] [data-field="${key}"]`,
        ).value;
        const previous = detail.sections?.[section]?.[key];
        const remote = data.sections?.[section]?.[key];
        if (mode === "keep" && previous === remote) continue;
        if (!group) {
          group = document.createElement("div");
          const heading = document.createElement("h3");
          heading.textContent = {
            basic: "기본 정보",
            answer: "정답 원고",
            reveal: "해설",
            child: `${childTypes[childResource].label} 기록`,
          }[section];
          group.append(heading);
          values.append(group);
        }
        const block = document.createElement("div");
        block.className = "compare-field";
        const heading = document.createElement("h4");
        heading.textContent = label;
        const dl = document.createElement("dl");
        labelValue(dl, "이전에 확인한 값", readable(previous));
        labelValue(
          dl,
          "현재 선택",
          mode === "keep"
            ? "변경 없음 (서버 최신값 유지)"
            : mode === "clear"
              ? "null로 비우기"
              : readable(
                  editor.querySelector(
                    `[data-section="${section}"] [data-value="${key}"]`,
                  ).value,
                ),
        );
        labelValue(dl, "서버 최신값", readable(remote));
        for (const value of dl.querySelectorAll("dd")) value.tabIndex = 0;
        block.append(heading, dl);
        group.append(block);
        shown++;
      }
    }
    if (!shown) {
      const message = document.createElement("p");
      message.textContent =
        "필드 차이는 확인되지 않았습니다. 수정번호와 저장 결과를 확인하세요.";
      values.append(message);
    }
    panel.hidden = false;
    document.getElementById("accept-latest").disabled =
      !copyPermissions(data.permissions) ||
      !data.storyActiveYn ||
      !data.activeYn ||
      !(
        data.status === "DRAFT" ||
        (forLifecycle && ["REVIEW", "READY"].includes(data.status))
      );
    syncLifecycleControls();
    const heading = document.getElementById("compare-heading");
    heading.focus();
    status(
      "최신 자료와 남은 입력을 비교하세요. 검토 전 저장은 차단됩니다.",
      true,
    );
  }

  /**
   * 재조회 중 과거 비교본의 수락을 막고 가장 최근 읽기 결과만 표시한다.
   * @param {boolean} forLifecycle 기본 false. 전환 패널의 명시 조회이면 true.
   * @returns {Promise<boolean>} 현재 소유권의 비교본 표시 여부.
   * @throws {object} 조회 실패는 호출자의 고정 오류 안내로 전파한다.
   */
  async function compare(forLifecycle = false) {
    saveLocked = true;
    syncChildControls();
    for (const button of editor.querySelectorAll("form[data-section] button"))
      button.disabled = true;
    const current = beginEditorOperation();
    const serial = comparisonRequest;
    document.getElementById("accept-latest").disabled = true;
    document.getElementById("refresh-latest").disabled = true;
    try {
      const data = await readEditor();
      if (
        current === generation &&
        serial === comparisonRequest &&
        data.childKey === childKey &&
        data.childResource === childResource
      ) {
        showComparison(data, forLifecycle);
        return true;
      }
      return false;
    } finally {
      const refresh = document.getElementById("refresh-latest");
      if (refresh && current === generation && serial === comparisonRequest)
        refresh.disabled = false;
    }
  }

  /** 생략·null·정수 범위를 구분해 선택 필드만 PATCH 값으로 만들며 잘못된 입력은 거절한다. */
  function changes(form, section) {
    const result = {};
    for (const [key, , kind, nullable] of fields[section]) {
      const mode = form.querySelector(`[data-field="${key}"]`).value;
      if (mode === "keep") continue;
      if (mode === "clear") {
        if (!nullable)
          throw {
            status: 422,
            field: key,
            message: "필수 항목은 비울 수 없습니다.",
          };
        result[key] = null;
        continue;
      }
      const input = form.querySelector(`[data-value="${key}"]`);
      if (!input.reportValidity())
        throw { status: 422, field: key, message: `${key} 입력을 확인하세요.` };
      if (kind === "select" || kind === "boolean") {
        const allowed = childTypes[childResource].options[key]
          .map(([value]) => value)
          .filter(Boolean);
        if (!allowed.includes(input.value))
          throw {
            status: 422,
            field: key,
            message: `${key} 값은 ${allowed.join(" / ")} 중에서 선택하세요.`,
          };
        result[key] = kind === "boolean" ? input.value === "true" : input.value;
      } else if (kind === "json") {
        const jsonLimit = key === "expectData" ? 65536 : 131072;
        if (
          new TextEncoder().encode(
            input.value.replace(/^[ \t\r\n]+|[ \t\r\n]+$/g, ""),
          ).length > jsonLimit
        )
          throw {
            status: 422,
            field: key,
            message: `${key} JSON은 UTF-8 ${jsonLimit / 1024} KiB를 넘을 수 없습니다.`,
          };
        let parsed;
        try {
          parsed = JSON.parse(input.value);
        } catch {
          throw {
            status: 422,
            field: key,
            message: "올바른 JSON 객체를 입력하세요.",
          };
        }
        if (
          parsed === null ||
          Array.isArray(parsed) ||
          typeof parsed !== "object"
        )
          throw {
            status: 422,
            field: key,
            message: "JSON 객체를 입력하세요.",
          };
        result[key] = parsed;
      } else if (kind === "number") {
        const value = Number(input.value);
        const high = numberLimit(section, key);
        const low = numberMinimum(section);
        if (
          !/^(0|[1-9][0-9]*)$/.test(input.value) ||
          !Number.isSafeInteger(value) ||
          value < low ||
          value > high
        )
          throw {
            status: 422,
            field: key,
            message: `${key} 값은 ${low}~${high} 범위의 정수여야 합니다.`,
          };
        result[key] = value;
      } else {
        if (!nullable && !input.value.trim())
          throw {
            status: 422,
            field: key,
            message: "필수 입력은 공백일 수 없습니다.",
          };
        if (Array.from(input.value).length > fieldLimit(section, key))
          throw {
            status: 422,
            field: key,
            message: `${key} 값은 ${fieldLimit(section, key)}자를 넘을 수 없습니다.`,
          };
        if (
          [
            "culpritCode",
            "code",
            "roleA",
            "roleB",
            "personCode",
            "clueCode",
            "roleCode",
            "rubricCode",
          ].includes(key) &&
          (key === "personCode" || input.value || !nullable) &&
          !/^[A-Z0-9_]{1,32}$/.test(input.value)
        )
          throw {
            status: 422,
            field: key,
            message: "코드는 영문 대문자·숫자·밑줄로 1~32자여야 합니다.",
          };
        result[key] = input.value;
      }
    }
    if (!Object.keys(result).length)
      throw { status: 400, message: "저장할 필드를 선택하세요." };
    if (section === "basic") {
      const basic = detail.sections.basic;
      const min = Object.hasOwn(result, "estMin")
        ? result.estMin
        : basic.estMin;
      const max = Object.hasOwn(result, "estMax")
        ? result.estMax
        : basic.estMax;
      if (min != null && max != null && max < min)
        throw {
          status: 422,
          field: "estMax",
          message: "최대 시간은 예상 시간보다 작을 수 없습니다.",
        };
    }
    if (section === "child" && childResource === "events") {
      const start = Object.hasOwn(result, "startMin")
        ? result.startMin
        : detail.childItem?.startMin;
      const end = Object.hasOwn(result, "endMin")
        ? result.endMin
        : detail.childItem?.endMin;
      if (end != null && (start == null || end < start))
        throw {
          status: 422,
          field: "endMin",
          message: "종료는 시작과 함께 지정하고 시작 이상이어야 합니다.",
        };
    }
    if (section === "child" && childResource === "rubrics") {
      const previous = detail.childItem;
      const max = Object.hasOwn(result, "maxScore")
        ? result.maxScore
        : previous?.maxScore;
      const pass = Object.hasOwn(result, "passScore")
        ? result.passScore
        : previous?.passScore;
      const required = Object.hasOwn(result, "requiredYn")
        ? result.requiredYn
        : (previous?.requiredYn ?? false);
      const rule = Object.hasOwn(result, "ruleData")
        ? result.ruleData
        : previous?.ruleData;
      const category = Object.hasOwn(result, "category")
        ? result.category
        : previous?.category;
      if (category !== "CULPRIT") {
        if (pass != null && (!required || max == null || pass > max))
          throw {
            status: 422,
            field: "passScore",
            message:
              "통과 점수는 필수 여부와 최대 점수가 지정되고 최대 점수 이하여야 합니다.",
          };
        if (rule != null && (max == null || max === 0))
          throw {
            status: 422,
            field: "ruleData",
            message: "규칙을 작성하려면 1 이상의 최대 점수가 필요합니다.",
          };
        if (
          previous?.category === "CULPRIT" &&
          !Object.hasOwn(result, "ruleData")
        )
          throw {
            status: 422,
            field: "ruleData",
            message:
              "범인 분류를 떠나려면 규칙 교체 또는 비우기를 명시적으로 선택하세요.",
          };
      }
    }
    return result;
  }

  /**
   * 선택 필드만 저장하며 자식 상태 변경·새 조합 ASCII 순서·단서 배정 의미 순서를 구분한다.
   * @param {HTMLFormElement} form 현재 연결된 data-section 원고 폼.
   * @param {string|undefined} operation 자식 deactivate/reactivate이며 일반 원고 저장은 생략한다.
   * @returns {Promise<void>} 현재 권한과 기준의 요청만 전송하며 실패는 원문 없는 안내와 비교로 처리한다.
   */
  async function save(form, operation) {
    if (!detail || saveLocked || resolutionOperation) {
      status("먼저 최신 상태를 확인하고 입력과 비교하세요.", true);
      return;
    }
    const section = form.dataset.section;
    if (
      !writableSection(section) &&
      !(section === "child" && operation && writableSection("basic"))
    )
      return;
    if (
      section === "child" &&
      ["pairs", "clue-roles"].includes(childResource) &&
      detail.childItem &&
      !operation
    ) {
      status(
        "기존 관계의 키는 수정할 수 없습니다. 명시적으로 삭제·복원하거나 새 관계를 작성하세요.",
        true,
      );
      return;
    }
    let patch;
    try {
      patch = operation ? {} : changes(form, section);
      if (section === "child" && !detail.childItem && !operation) {
        for (const key of childTypes[childResource].required) {
          if (!patch[key])
            throw {
              status: 422,
              field: key,
              message: `새 ${childTypes[childResource].label}의 필수 항목을 직접 수정으로 선택하세요.`,
            };
        }
        if (childResource === "pairs") {
          if (patch.roleA === patch.roleB)
            throw {
              status: 422,
              field: "roleB",
              message: "서로 다른 역할을 선택하세요.",
            };
          [patch.roleA, patch.roleB] = [patch.roleA, patch.roleB].sort();
          for (const key of ["roleA", "roleB"])
            form.querySelector(`[data-value="${key}"]`).value = patch[key];
        }
        const key = childTypes[childResource].keys
          .map((name) => patch[name])
          .join("~");
        if (childKey && key !== childKey)
          throw {
            status: 409,
            message: "생성 결과 확인 중에는 코드를 바꿀 수 없습니다.",
          };
        childKey = key;
      }
    } catch (error) {
      if (error.field) fieldError(form, error.field, error.message);
      status(error.message || errorMessage(error), true);
      formStatus(form, error.message || errorMessage(error), true);
      return;
    }
    const submitted = captureDrafts()[section];
    const current = beginEditorOperation();
    const source = detail;
    const path = editorPath();
    const permissionsAtSend = permissionEpoch;
    saveLocked = true;
    syncChildControls();
    for (const pending of editor.querySelectorAll("form[data-section] button"))
      pending.disabled = true;
    status("저장 중입니다. 응답이 사라져도 자동으로 다시 제출하지 않습니다.");
    formStatus(form, "이 영역을 저장하고 최신본을 확인하고 있습니다.");
    try {
      const child = section === "child";
      const creating = child && !detail.childItem;
      const rawJsonFields = child
        ? Object.fromEntries(
            childTypes[childResource].fields
              .filter(([key, , kind]) => kind === "json" && patch[key] != null)
              .map(([key]) => [
                key,
                form.querySelector(`[data-value="${key}"]`).value,
              ]),
          )
        : undefined;
      const result = await request(
        child && (creating || operation) ? "POST" : "PATCH",
        editorPath() +
          (child
            ? `/${childResource}${creating ? "" : `/${encodeURIComponent(childKey)}`}${operation ? `/${operation}` : ""}`
            : `/sections/${section}`),
        {
          expectedRev: detail.editRev,
          ...(operation ? {} : creating ? { item: patch } : { changes: patch }),
        },
        false,
        rawJsonFields && Object.keys(rawJsonFields).length
          ? rawJsonFields
          : undefined,
        (phase) =>
          current === generation &&
          detail === source &&
          editorPath() === path &&
          (phase !== "send" ||
            (permissionEpoch === permissionsAtSend &&
              writableSection(operation ? "basic" : section))),
      );
      if (current !== generation) return;
      document.getElementById("comparison").hidden = false;
      document.getElementById("accept-latest").disabled = true;
      document.getElementById("refresh-latest").disabled = true;
      latest = undefined;
      try {
        const reconciled = await loadDetail({
          preserve: true,
          confirmed: operation ? undefined : { section, drafts: submitted },
          expectedRev: result.editRev,
        });
        if (reconciled === false) {
          status(
            "저장 후 다른 수정이 확인됐습니다. 남은 입력과 최신본을 비교하기 전에는 다시 저장할 수 없습니다.",
            true,
          );
          formStatus(
            form,
            "다른 수정이 확인됐습니다. 최신본과 비교하세요.",
            true,
          );
        } else if (reconciled) {
          status(
            result.changed
              ? "저장을 확인하고 최신 원고를 조회했습니다."
              : "저장값에 변화가 없습니다. 최신 원고를 조회했습니다.",
          );
          const currentForm = editor.querySelector(
            `[data-section="${section}"]`,
          );
          const pending = Object.keys(captureDrafts()[section]).length > 0;
          formStatus(
            currentForm,
            pending
              ? "이 영역에 새 미저장 입력이 남아 있습니다."
              : result.changed
                ? "이 영역의 저장을 확인했습니다."
                : "서버 저장값과 같습니다.",
          );
        }
      } catch (error) {
        if (![401, 403, 404].includes(error.status)) {
          status(
            "저장 응답을 받았지만 최신 원고 조회에 실패했습니다. 입력은 유지되며 최신본 다시 조회 전에는 저장할 수 없습니다.",
            true,
          );
          formStatus(
            form,
            "저장 후 확인이 실패했습니다. 최신본 다시 조회 전에는 저장할 수 없습니다.",
            true,
          );
        }
      } finally {
        const refresh = document.getElementById("refresh-latest");
        if (refresh) refresh.disabled = false;
      }
    } catch (error) {
      if (current !== generation || [401, 403, 404].includes(error.status))
        return;
      if (error.status === 409 || error.status === 503 || !error.status) {
        saveLocked = true;
        document.getElementById("comparison").hidden = false;
        document.getElementById("accept-latest").disabled = true;
        latest = undefined;
        const reason = errorMessage(error);
        status(reason + " 최신본 조회 중입니다.", true);
        try {
          if (await compare())
            status(reason + " 검토 전 저장은 차단됩니다.", true);
        } catch (readError) {
          if (![401, 403, 404].includes(readError.status))
            status(
              "최신본 조회도 실패했습니다. 입력은 유지되고 저장은 차단됩니다. 최신본 다시 조회를 누르세요.",
              true,
            );
        }
      } else {
        saveLocked = false;
        status(errorMessage(error), true);
        formStatus(form, errorMessage(error), true);
        if (
          [400, 422].includes(error.status) &&
          Object.keys(patch).length === 1
        )
          fieldError(form, Object.keys(patch)[0], errorMessage(error));
      }
    } finally {
      for (const pending of editor.querySelectorAll(
        "form[data-section] button",
      ))
        pending.disabled =
          saveLocked ||
          !writableSection(pending.closest("form").dataset.section);
      syncChildControls();
    }
  }

  /**
   * 인증된 소유자·운영자의 관계 목록을 읽고 도착 시 기존 동의를 무효화한다.
   * @param {boolean} append 다음 페이지 추가 여부. 기본 false이며 수정번호가 다르면 목록 기준을 폐기한다.
   * @returns {Promise<void>} 현재 사건의 관계와 복원 영향을 표시한다. 오래된 응답은 무시한다.
   * @throws {object} 기존 API 조회 오류를 호출자의 재조회 안내로 전달한다.
   */
  async function loadAccess(append = false) {
    if (!selectedStory) return;
    AdminUI.cancelConfirmation();
    const current = generation;
    const story = selectedStory;
    const epoch = manageEpoch;
    const code = selectedStory.storyCode;
    const after = append ? manageAfterKey : undefined;
    const query = new URLSearchParams({ size: "20" });
    if (after) query.set("afterKey", after);
    const data = await request(
      "GET",
      `/${encodeURIComponent(code)}/access?${query}`,
    );
    if (
      current !== generation ||
      epoch !== manageEpoch ||
      story !== selectedStory
    )
      return;
    AdminUI.cancelConfirmation();
    if (append && manageSnapshot && data.storyRev !== manageSnapshot.storyRev) {
      manageSnapshot = undefined;
      manageAfterKey = undefined;
      document.getElementById("state-impact-reviewed").checked = false;
      document.getElementById("access-next").hidden = true;
      status(
        "관계 목록을 읽는 동안 사건 수정번호가 바뀌었습니다. 현재 관계를 처음부터 다시 조회하세요.",
        true,
      );
      return;
    }
    manageSnapshot = data;
    if (!append) {
      document.getElementById("access-rows").replaceChildren();
      document.getElementById("state-impact-reviewed").checked = false;
    }
    document.getElementById("manage-code").textContent = data.storyCode;
    document.getElementById("manage-summary").textContent =
      `사건 수정번호 ${data.storyRev} · ${data.activeYn ? "활성" : "비활성"} · 소유자 ${data.ownerAccountKey}`;
    document.getElementById("access-impact").textContent =
      `활성 접근 관계 ${data.activeRelationCount}건. 비활성 관계도 아래 목록에 표시되며, 복원하면 남은 활성 관계가 다시 접근할 수 있습니다.`;
    const rows = document.getElementById("access-rows");
    for (const item of data.items) {
      const entry = document.createElement("li");
      entry.textContent = `${item.accountKey} · ${item.permission} · ${item.activeYn ? "활성" : "회수됨"}`;
      rows.append(entry);
    }
    if (!rows.childElementCount) {
      const empty = document.createElement("li");
      empty.textContent = "기록된 접근 관계가 없습니다.";
      rows.append(empty);
    }
    manageAfterKey = data.nextAfterKey;
    document.getElementById("access-next").hidden = !data.hasNext;
    const owner = viewer?.accountKey === data.ownerAccountKey;
    const state = document.getElementById("story-state-actions");
    state.hidden =
      !owner ||
      selectedStory.versionNo !== 1 ||
      selectedStory.status !== "DRAFT" ||
      !selectedStory.versionActiveYn;
    document.getElementById("state-change").textContent = data.activeYn
      ? "사건 논리 삭제"
      : "사건 복원";
    const permission = document.getElementById("access-permission");
    for (const option of permission.options)
      option.disabled =
        option.value === "EDIT"
          ? !owner
          : !viewer?.permissions?.includes("MANAGE");
    if (permission.selectedOptions[0]?.disabled)
      permission.value = owner ? "EDIT" : "REVIEW";
    document.getElementById("manage-panel").hidden = false;
    if (!append) document.getElementById("manage-heading").focus();
  }

  /**
   * 목록 행의 관리 문맥을 바꾸고 이전 동의를 거절한다.
   * @param {object} item 서버 목록에서 선택한 사건 행. null은 허용하지 않는다.
   * @returns {Promise<void>} 현재 관계를 조회하며 오류는 고정 안내로 표시한다. 쓰기는 재전송하지 않는다.
   */
  async function openManage(item) {
    AdminUI.cancelConfirmation();
    selectedStory = item;
    manageSnapshot = undefined;
    manageAfterKey = undefined;
    ++manageEpoch;
    const current = generation;
    const epoch = manageEpoch;
    document.getElementById("manage-panel").hidden = true;
    document.getElementById("manage-result").textContent = "";
    try {
      await loadAccess();
    } catch (error) {
      if (
        current !== generation ||
        epoch !== manageEpoch ||
        selectedStory !== item
      )
        return;
      if (error.code === "REAUTH_REQUIRED") openStoryReauth();
      status(errorMessage(error), true);
    }
  }

  /**
   * 현재 수정번호와 검토한 관계 영향에 결속한 상태 변경만 한 번 제출한다.
   * @param {HTMLButtonElement} button 현재 상태 변경 버튼. 비활성·숨김·분리 상태는 제출하지 않는다.
   * @returns {Promise<void>} 취소·오래된 동의는 입력을 보존하고 API 오류는 기존 안내로 처리한다.
   */
  async function changeStoryState(button) {
    if (!selectedStory || !manageSnapshot || button.disabled) return;
    const current = generation;
    const epoch = manageEpoch;
    const story = selectedStory;
    const snapshot = manageSnapshot;
    const code = story.storyCode;
    const editRev = story.editRev;
    const storyRev = snapshot.storyRev;
    const input = document.getElementById("state-reference");
    if (!input.reportValidity()) return;
    const reference = input.value;
    const active = snapshot.activeYn;
    const impact = document.getElementById("state-impact-reviewed").checked;
    if (!active && !impact) {
      status("복원 전 남은 활성 관계 수와 목록을 확인하세요.", true);
      return;
    }
    if (
      !(await AdminUI.confirm({
        title: active ? "사건 논리 삭제" : "사건 복원",
        message: active
          ? "최초 초안 사건만 논리 삭제합니까? 원고와 관계는 유지되며 자동 재전송하지 않습니다."
          : `활성 접근 관계 ${snapshot.activeRelationCount}건의 접근이 다시 가능해집니다. 사건을 복원합니까?`,
        confirmLabel: active ? "논리 삭제" : "복원",
      }))
    )
      return;
    if (
      current !== generation ||
      epoch !== manageEpoch ||
      selectedStory !== story ||
      manageSnapshot !== snapshot ||
      story.storyCode !== code ||
      story.editRev !== editRev ||
      snapshot.storyRev !== storyRev ||
      snapshot.activeYn !== active ||
      button.disabled ||
      !button.isConnected ||
      button.closest("[hidden]") ||
      !input.isConnected ||
      input.value !== reference ||
      document.getElementById("state-impact-reviewed").checked !== impact
    )
      return;
    button.disabled = true;
    const operation = active ? "deactivate" : "reactivate";
    try {
      const result = await request(
        "POST",
        `/${encodeURIComponent(code)}/${operation}`,
        {
          expectedStoryRev: storyRev,
          expectedRev: editRev,
          reasonCode: active ? "DRAFT_WITHDRAWN" : "WORK_RESUMED",
          verificationRef: reference,
        },
      );
      if (
        current !== generation ||
        epoch !== manageEpoch ||
        selectedStory !== story ||
        manageSnapshot !== snapshot
      )
        return;
      AdminUI.cancelConfirmation();
      input.value = "";
      document.getElementById("manage-panel").hidden = true;
      selectedStory = manageSnapshot = undefined;
      ++manageEpoch;
      const receipt = `현재 사건 ${result.activeYn ? "복원" : "논리 삭제"} ${result.changed ? "확정" : "무변경"} · 사건 수정번호 ${result.storyRev}.`;
      try {
        await loadList();
        if (
          current !== generation ||
          manageEpoch !== epoch + 1 ||
          selectedStory
        )
          return;
        status(receipt);
      } catch (error) {
        if (
          current !== generation ||
          manageEpoch !== epoch + 1 ||
          selectedStory
        )
          return;
        status(
          `${receipt} 목록 새로 조회에 실패했습니다. 변경을 다시 보내지 말고 목록을 직접 조회하세요.`,
          true,
        );
      }
    } catch (error) {
      if (
        current !== generation ||
        epoch !== manageEpoch ||
        selectedStory !== story
      )
        return;
      if (error.code === "REAUTH_REQUIRED") openStoryReauth();
      document.getElementById("manage-result").textContent =
        `${errorMessage(error)} 현재 사건·관계를 다시 조회한 뒤 직접 결정하세요.`;
      status(errorMessage(error), true);
    } finally {
      button.disabled = false;
    }
  }

  /**
   * 확인 전에 복사한 관계 변경과 사건 수정번호를 한 번만 사용한다.
   * @param {HTMLFormElement} form 현재 접근 관계 폼. null은 허용하지 않으며 분리·숨김 뒤에는 제출하지 않는다.
   * @returns {Promise<void>} 성공만 참조 입력을 지우고 취소·오래된 동의·오류는 자동 재전송하지 않는다.
   */
  async function changeAccess(form) {
    if (!selectedStory || !manageSnapshot) return;
    const button = document.getElementById("access-submit");
    if (button.disabled) return;
    const current = generation;
    const epoch = manageEpoch;
    const story = selectedStory;
    const snapshot = manageSnapshot;
    const code = story.storyCode;
    const editRev = story.editRev;
    const storyRev = snapshot.storyRev;
    const input = Object.fromEntries(new FormData(form));
    const operation = input.operation;
    const reason = input.reasonCode;
    if (operation === "grant" && reason !== "ASSIGNMENT_CHANGE") {
      status("부여 사유는 담당 변경만 선택할 수 있습니다.", true);
      return;
    }
    if (
      !(await AdminUI.confirm({
        title: operation === "grant" ? "접근 관계 부여" : "접근 관계 회수",
        message: `${input.permission} 관계를 ${operation === "grant" ? "부여" : "회수"}합니까? 자동 재전송하지 않습니다.`,
        confirmLabel: operation === "grant" ? "관계 부여" : "관계 회수",
      }))
    )
      return;
    if (
      current !== generation ||
      epoch !== manageEpoch ||
      selectedStory !== story ||
      manageSnapshot !== snapshot ||
      story.storyCode !== code ||
      story.editRev !== editRev ||
      snapshot.storyRev !== storyRev ||
      button.disabled ||
      !button.isConnected ||
      !form.isConnected ||
      form.closest("[hidden]")
    )
      return;
    button.disabled = true;
    try {
      const result = await request(
        "POST",
        `/${encodeURIComponent(code)}/access/${operation}`,
        {
          expectedStoryRev: storyRev,
          accountKey: input.accountKey,
          permission: input.permission,
          reasonCode: reason,
          verificationRef: input.verificationRef,
        },
      );
      if (
        current !== generation ||
        epoch !== manageEpoch ||
        selectedStory !== story ||
        manageSnapshot !== snapshot
      )
        return;
      form.elements.verificationRef.value = "";
      await loadAccess();
      if (
        current !== generation ||
        epoch !== manageEpoch ||
        selectedStory !== story
      )
        return;
      const message =
        result.auditStatus === "UNCONFIRMED"
          ? "접근 차단은 확정됐으나 업무 감사가 미확정입니다. 운영 점검이 필요합니다."
          : result.changed
            ? `관계 변경 확정 · 사건 수정번호 ${result.storyRev}.`
            : "관계는 이미 요청한 상태입니다. 변경하지 않았습니다.";
      document.getElementById("manage-result").textContent = message;
      status(message, result.auditStatus === "UNCONFIRMED");
    } catch (error) {
      if (
        current !== generation ||
        epoch !== manageEpoch ||
        selectedStory !== story
      )
        return;
      if (error.code === "REAUTH_REQUIRED") openStoryReauth();
      document.getElementById("manage-result").textContent =
        `${errorMessage(error)} 현재 관계를 다시 조회하고 직접 결정하세요.`;
      status(errorMessage(error), true);
    } finally {
      button.disabled = false;
    }
  }

  /** 기존 확인을 거절하고 별도 재인증 창만 연다. 반환값은 없고 이전 변경은 재실행하지 않는다. */
  function openStoryReauth() {
    AdminUI.cancelConfirmation();
    document.getElementById("story-reauth").showModal();
  }

  /** 정확한 인증 입력만 별도 인증 API에 보내고 이전 변경 요청을 재실행하지 않는다. */
  async function reauthenticate(form) {
    if (!csrf) {
      const response = await fetch("/admin/api/auth/csrf", {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (!response.ok) throw { status: response.status };
      csrf = await response.json();
    }
    const response = await fetch("/admin/api/auth/reauth", {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        [csrf.headerName]: csrf.token,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        password: form.elements.password.value,
        totp: form.elements.totp.value,
      }),
    });
    const payload = await response.json();
    if (!response.ok) throw { status: response.status, code: payload?.code };
    csrf = undefined;
    form.reset();
    document.getElementById("story-reauth").close();
    status(
      "재인증했습니다. 현재 사건·관계를 다시 조회한 뒤 변경을 직접 실행하세요.",
    );
  }

  /**
   * 현재 인계 문맥의 원고 없는 효력을 조회하고 응답 반영 전에 기존 동의를 거절한다.
   * @returns {Promise<void>} 현재 소유자·지정 수신자·MANAGE에게만 인계 메타를 표시한다. 오래된 응답은 무시한다.
   * @throws {object} 기존 API 조회 오류를 호출자의 재조회 안내로 전달한다.
   */
  async function loadOwnership() {
    if (!ownershipStory) return;
    AdminUI.cancelConfirmation();
    const current = generation;
    const story = ownershipStory;
    const epoch = ownershipEpoch;
    const result = await request(
      "GET",
      `/${encodeURIComponent(ownershipStory.storyCode)}/ownership`,
      undefined,
      true,
    );
    if (
      current !== generation ||
      epoch !== ownershipEpoch ||
      story !== ownershipStory
    )
      return;
    AdminUI.cancelConfirmation();
    if (!result) {
      ownershipSnapshot = undefined;
      document.getElementById("ownership-panel").hidden = !ownershipIntent;
      status("현재 소유권 인계 조회 자격이 없거나 사건이 없습니다.", true);
      return;
    }
    ownershipSnapshot = result;
    document.getElementById("ownership-summary").textContent =
      `${result.storyCode} · 사건 수정번호 ${result.storyRev} · ${result.activeYn ? "활성" : "비활성"} · 현재 소유자 ${result.ownerAccountKey}`;
    const pending = result.pending;
    const owner = viewer?.accountKey === result.ownerAccountKey;
    const receiver = viewer?.accountKey === pending?.toAccountKey;
    const effective = pending?.effectiveState === "PENDING";
    document.getElementById("ownership-pending").hidden = !pending;
    document.getElementById("ownership-pending-detail").textContent = pending
      ? `수신자 ${pending.toAccountKey} · ${pending.effectiveState} · 기한 ${displayTime(pending.expiresAt)} · 요청 후 사건 수정번호 ${pending.storyRev}. 만료·무효 요청은 수락하지 않습니다.`
      : "";
    document.getElementById("ownership-accept").hidden =
      !receiver || !effective;
    document.getElementById("ownership-cancel").hidden = !owner || !effective;
    document.getElementById("ownership-decline").hidden =
      !receiver || !effective;
    document.getElementById("ownership-request-form").hidden =
      !owner || !result.activeYn || effective;
    document.getElementById("ownership-override-form").hidden =
      !viewer?.permissions?.includes("MANAGE");
    document.getElementById("ownership-reconcile").hidden = !ownershipIntent;
    document.getElementById("ownership-panel").hidden = false;
    document.getElementById("ownership-heading").focus();
  }

  /**
   * 목록 선택 시 이전 동의와 인계 응답을 폐기하고 최소 인계 메타만 표시한다.
   * @param {object} item 서버 목록에서 선택한 사건 행. null은 허용하지 않는다.
   * @returns {Promise<void>} 현재 인계를 표시하며 오류는 고정 안내로 처리한다.
   */
  async function openOwnership(item) {
    AdminUI.cancelConfirmation();
    ownershipStory = item;
    ownershipSnapshot = ownershipIntent = undefined;
    ++ownershipEpoch;
    const current = generation;
    const epoch = ownershipEpoch;
    document.getElementById("manage-panel").hidden = true;
    selectedStory = manageSnapshot = undefined;
    ++manageEpoch;
    document.getElementById("ownership-result").textContent = "";
    try {
      await loadOwnership();
    } catch (error) {
      if (
        current !== generation ||
        epoch !== ownershipEpoch ||
        ownershipStory !== item
      )
        return;
      if (error.code === "REAUTH_REQUIRED") openStoryReauth();
      status(errorMessage(error), true);
    }
  }

  /**
   * 현재 수정번호에 결속된 단일 의도와 UUID v4를 생성해 결과 확정 전 유지한다.
   * @param {string} path 기존 인계 API의 상대 접미사. 확인한 동작만 허용한다.
   * @param {object} body 확인 전에 복사한 기존 인계 요청 필드. null은 허용하지 않는다.
   * @returns {void} 기존 의도가 있으면 새 의도를 만들지 않으며 제출 오류는 별도 안내로 처리한다.
   */
  function ownerIntent(path, body) {
    if (!ownershipStory || !ownershipSnapshot || ownershipIntent) return;
    AdminUI.cancelConfirmation();
    ownershipIntent = {
      path: `/${encodeURIComponent(ownershipStory.storyCode)}/ownership${path}`,
      body: {
        expectedStoryRev: ownershipSnapshot.storyRev,
        ...body,
        requestKey: crypto.randomUUID(),
      },
    };
    submitOwnership();
  }

  /**
   * 결과 유실 때 원래 요청만 사용자의 명시 행동으로 다시 보내며 자동 재시도하지 않는다.
   * @returns {Promise<void>} 동일 문맥에만 영수증을 표시하며 API 오류는 의도를 유지한 안내로 처리한다.
   */
  async function submitOwnership() {
    if (!ownershipIntent) return;
    const intent = ownershipIntent;
    const current = generation;
    const epoch = ownershipEpoch;
    const story = ownershipStory;
    const panel = document.getElementById("ownership-panel");
    panel.setAttribute("aria-busy", "true");
    try {
      const result = await request("POST", intent.path, intent.body);
      if (
        current !== generation ||
        epoch !== ownershipEpoch ||
        story !== ownershipStory ||
        intent !== ownershipIntent
      )
        return;
      AdminUI.cancelConfirmation();
      status(
        result.replayed
          ? "원래 확정된 인계 영수증을 확인했습니다. 현재 소유권과 구분하세요."
          : "인계 행동이 확정됐습니다. 현재 소유권은 별도로 조회하세요.",
      );
      document.getElementById("ownership-result").textContent =
        `${result.original.state} · 인계 ${result.original.transferKey} · 확정 당시 사건 수정번호 ${result.original.storyRev} · ${result.replayed ? "기존 영수증 재생" : "새 확정"}. 현재 소유권은 다시 조회하세요.`;
      ownershipIntent = undefined;
      ownershipSnapshot = undefined;
      document.getElementById("ownership-pending").hidden = true;
      document.getElementById("ownership-request-form").hidden = true;
      document.getElementById("ownership-override-form").hidden = true;
      document.getElementById("ownership-reconcile").hidden = true;
      // 수락 후 소유권 조회 자격을 잃어도 원래 영수증을 실패로 번역하지 않는다.
    } catch (error) {
      if (
        current !== generation ||
        epoch !== ownershipEpoch ||
        story !== ownershipStory ||
        intent !== ownershipIntent
      )
        return;
      AdminUI.cancelConfirmation();
      document.getElementById("ownership-reconcile").hidden = false;
      document.getElementById("ownership-result").textContent =
        `${errorMessage(error)} 원래 의도 키를 유지합니다. 자동으로 새 요청을 만들지 않습니다.`;
      if (error.code === "REAUTH_REQUIRED") openStoryReauth();
      status(errorMessage(error), true);
    } finally {
      if (
        current === generation &&
        epoch === ownershipEpoch &&
        story === ownershipStory
      )
        panel.setAttribute("aria-busy", "false");
    }
  }

  /**
   * 표시된 인계와 수정번호에만 수신 수락·취소·거절 동의를 적용한다.
   * @param {string} decision ACCEPT/CANCEL/DECLINE 중 기존 버튼의 결정.
   * @param {HTMLButtonElement} button 현재 결정 버튼. 분리되거나 숨겨지면 동의는 무효다.
   * @returns {Promise<void>} 유효한 동의만 단일 의도를 생성하며 오류는 기존 제출 경로에서 안내한다.
   */
  async function closeOwnership(decision, button) {
    const current = generation;
    const epoch = ownershipEpoch;
    const story = ownershipStory;
    const snapshot = ownershipSnapshot;
    const intent = ownershipIntent;
    const pending = ownershipSnapshot?.pending;
    if (
      !story ||
      intent ||
      !pending ||
      pending.effectiveState !== "PENDING" ||
      button.disabled
    )
      return;
    const transferKey = pending.transferKey;
    const storyRev = snapshot.storyRev;
    const verb =
      decision === "ACCEPT" ? "수락" : decision === "CANCEL" ? "취소" : "거절";
    if (
      !(await AdminUI.confirm({
        title: `소유권 인계 ${verb}`,
        message: `현재 사건 수정번호로 인계를 ${verb}합니까? 결과 유실 시 같은 의도를 확인하세요.`,
        confirmLabel: `인계 ${verb}`,
      }))
    )
      return;
    if (
      current !== generation ||
      epoch !== ownershipEpoch ||
      story !== ownershipStory ||
      snapshot !== ownershipSnapshot ||
      intent !== ownershipIntent ||
      snapshot.storyRev !== storyRev ||
      snapshot.pending !== pending ||
      pending.transferKey !== transferKey ||
      pending.effectiveState !== "PENDING" ||
      !button.isConnected ||
      button.closest("[hidden]") ||
      button.disabled
    )
      return;
    ownerIntent(
      `/requests/${encodeURIComponent(transferKey)}/${decision === "ACCEPT" ? "accept" : "close"}`,
      decision === "ACCEPT" ? {} : { decision },
    );
  }

  /** 정확한 코드와 활성 조건으로 ID 역순 커서 목록 한 페이지를 조회한다. */
  async function loadList(append = false) {
    const current = generation;
    const serial = ++listRequest;
    const params = pageParams(filters, "afterId", append ? afterId : undefined);
    const rows = document.getElementById("rows");
    rows.setAttribute("aria-busy", "true");
    status("사건 목록을 조회하고 있습니다.");
    try {
      const data = await request("GET", `?${params}`);
      if (current !== generation || serial !== listRequest) return;
      if (!append) rows.replaceChildren();
      for (const item of data.items) {
        const row = document.createElement("article");
        row.className = "story-row";
        row.dataset.ownerKey = item.ownerAccountKey;
        const heading = document.createElement("h3");
        const link = document.createElement("a");
        link.href = `/admin/stories/${encodeURIComponent(item.storyCode)}/versions/${item.versionNo}`;
        const name = document.createElement("span");
        name.className = "story-name";
        name.textContent = item.title || item.storyCode;
        const action = document.createElement("span");
        action.className = "row-action";
        action.textContent = "원고 열기";
        action.setAttribute("aria-hidden", "true");
        link.append(name, action);
        heading.append(link);
        const code = document.createElement("p");
        code.className = "story-code";
        code.textContent = item.storyCode;
        const badges = document.createElement("div");
        badges.className = "story-badges";
        for (const value of [
          versionStates[item.status] || item.status,
          `버전 ${item.versionNo}`,
          item.activeYn && item.versionActiveYn ? "활성" : "비활성 · 읽기 전용",
        ]) {
          const badge = document.createElement("span");
          badge.textContent = value;
          badges.append(badge);
        }
        const summary = document.createElement("p");
        summary.className = "muted";
        summary.textContent = `난이도 ${item.difficulty ?? "미정"} · 수정번호 ${item.editRev} · ${displayTime(item.updatedAt)}`;
        row.append(badges, heading, code, summary);
        const manage = document.createElement("button");
        manage.type = "button";
        manage.className = "secondary manage-open";
        manage.textContent = "접근·상태 관리";
        manage.hidden =
          item.ownerAccountKey !== viewer?.accountKey &&
          !viewer?.permissions?.includes("MANAGE");
        manage.addEventListener("click", () => openManage(item));
        row.append(manage);
        const ownership = document.createElement("button");
        ownership.type = "button";
        ownership.className = "secondary ownership-open";
        ownership.textContent = "소유권 인계 확인";
        ownership.addEventListener("click", () => openOwnership(item));
        row.append(ownership);
        rows.append(row);
      }
      if (!rows.childElementCount) {
        const empty = document.createElement("p");
        empty.className = "empty-state";
        empty.textContent =
          "조건에 맞는 접근 가능 사건이 없습니다. 사건 코드와 상태 조건을 확인하세요.";
        rows.append(empty);
      }
      afterId = data.hasNext ? data.nextAfterId : undefined;
      document.getElementById("next").hidden = !afterId;
      status("현재 페이지를 조회했습니다.");
    } finally {
      rows.setAttribute("aria-busy", "false");
    }
  }

  /** 생성 의도의 UUID를 계약에 정해진 사건 코드로 변환한다. */
  function derivedCode(key) {
    return "ST_" + key.replaceAll("-", "").toUpperCase();
  }

  /** POST를 재전송하지 않고 메모리의 UUID에서 만든 코드를 활성·비활성 목록에서 찾는다. */
  async function checkCreate() {
    if (!createKey) return;
    const code = derivedCode(createKey);
    const found = [];
    for (const activeYn of ["true", "false"]) {
      const result = await request(
        "GET",
        `?${new URLSearchParams({ size: "20", code, activeYn })}`,
      );
      found.push(...result.items);
    }
    const message = document.getElementById("create-check-message");
    if (found.length) {
      const link = document.createElement("a");
      link.href = `/admin/stories/${encodeURIComponent(code)}/versions/${found[0].versionNo}`;
      link.textContent = "확인된 사건 상세로 이동";
      message.replaceChildren(
        "접근 가능한 기존 사건이 확인되었습니다. 제목과 상태를 확인하세요. ",
        link,
      );
      status("기존 사건을 확인했습니다. 생성 요청을 재전송하지 않습니다.");
    } else {
      message.textContent =
        "활성·비활성 목록에서 접근 가능한 사건을 확인하지 못했습니다. 생성이 실패했다는 증거는 아닙니다. 필요한 경우 관리자에게 확인하세요.";
      status(
        "생성 상태가 여전히 불확실합니다. 자동 재전송하지 않습니다.",
        true,
      );
    }
  }

  /** 생성 결과를 확인하거나 새 의도를 명시할 때까지 의도별 UUID 하나만 보존한다. */
  async function create(form) {
    if (createKey) {
      status(
        "기존 생성 의도의 상태를 먼저 확인하거나 새 생성 의도를 명시적으로 시작하세요.",
        true,
      );
      return;
    }
    createKey = crypto.randomUUID();
    createTitle = form.elements.title.value;
    const button = form.querySelector("button");
    button.disabled = true;
    status("초안을 생성 중입니다. 자동 재전송하지 않습니다.");
    try {
      const result = await request("POST", "", {
        createKey,
        title: createTitle,
      });
      location.assign(
        `/admin/stories/${encodeURIComponent(result.storyCode)}/versions/${result.versionNo}`,
      );
    } catch (error) {
      if (error.status === 403 && error.code === "FORBIDDEN") {
        createKey = createTitle = undefined;
        document.getElementById("create-panel").hidden = true;
        document.getElementById("create-jump").hidden = true;
        status(
          "현재 CREATE 권한이 없어 새 사건을 생성할 수 없습니다. 접근 가능한 사건 목록은 계속 사용할 수 있습니다.",
          true,
        );
        return;
      }
      if ([401, 403, 404].includes(error.status)) return;
      document.getElementById("create-check").hidden = false;
      document.getElementById("create-check-message").textContent =
        error.status === 409
          ? "생성 키가 이미 사용되었습니다. 현재 상태를 확인하세요."
          : "이 생성 의도의 키를 현재 화면 메모리에 유지합니다. 목록으로 현재 상태를 확인하세요.";
      status(errorMessage(error), true);
    } finally {
      button.disabled = false;
    }
  }

  /**
   * 현재 CREATE 권한을 확인한 뒤 기존 동의를 거절하고 생성 폼을 공개한다.
   * @returns {Promise<void>} 현재 세대의 자격만 반영하며 오류는 고정 안내로 처리한다.
   */
  async function configureCreate() {
    const current = generation;
    try {
      const response = await fetch("/admin/api/auth/me", {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (current !== generation) return;
      if (response.status === 401) {
        revoke("세션이 만료되었습니다.", 401);
        return;
      }
      if (!response.ok) throw { status: response.status };
      const me = await response.json();
      if (current !== generation) return;
      AdminUI.cancelConfirmation();
      viewer = me;
      for (const button of document.querySelectorAll(".manage-open")) {
        const row = button.closest(".story-row");
        const owner = row.dataset.ownerKey;
        button.hidden =
          owner !== me.accountKey && !me.permissions?.includes("MANAGE");
      }
      const allowed = me.permissions?.includes("CREATE") === true;
      document.getElementById("create-panel").hidden = !allowed;
      document.getElementById("create-jump").hidden = !allowed;
    } catch {
      if (current !== generation) return;
      status(
        "생성 권한을 확인할 수 없어 생성 기능을 숨겼습니다. 사건 목록은 계속 사용할 수 있습니다.",
        true,
      );
    }
  }

  /** 목록 GET 오류만 조회 행동으로 안내한다. error의 status가 없거나 503이면 전송 결과 대신 조회 실패로 표시한다. */
  function listFailure(error) {
    if ([401, 403, 404].includes(error.status)) return;
    status(
      error.status === 503 || !error.status
        ? "사건 목록을 조회하지 못했습니다. 조회 버튼으로 다시 확인하세요."
        : errorMessage(error),
      true,
    );
  }

  /**
   * 최초 조회 실패는 저장 검수 내용을 폐기하고 입력을 전송하지 않는 재조회로 안내한다.
   * @param {object} error 기존 조회 오류의 HTTP 상태. 접근 종료는 revoke에서 이미 처리한다.
   */
  function detailFailure(error) {
    savedDetailPending = true;
    resetSavedReview("failure");
    if ([401, 403, 404].includes(error.status)) return;
    const panel = document.getElementById("unavailable");
    panel.hidden = false;
    document.getElementById("unavailable-message").textContent =
      "사건 자료 조회에 실패했습니다. 입력을 보내지 않고 다시 조회할 수 있습니다.";
    status(errorMessage(error), true);
  }

  if (editor) {
    document.getElementById("issue-resolution-form")?.addEventListener("submit", (event) => {
      event.preventDefault();
      submitResolution();
    });
    for (const eventName of ["input", "change"]) {
      document.getElementById("issue-resolution-form")?.addEventListener(eventName, resolutionChanged);
      editor.addEventListener(eventName, () => {
        if (resolutionOperation && !resolutionOperation.sent) AdminUI.cancelConfirmation();
      });
    }
    document.getElementById("issue-resolution-batch-read")?.addEventListener("click", readResolutionBatch);
    document.getElementById("issue-resolution-replay")?.addEventListener("click", () => submitResolution(true));
    document.getElementById("issue-resolution-discard")?.addEventListener("click", discardResolution);
    resolutionAcknowledged = resolutionFingerprint();
    document
      .getElementById("clone-submit")
      .addEventListener("click", () => submitClone(false));
    document
      .getElementById("clone-reconfirm")
      .addEventListener("click", () => submitClone(true));
    document
      .getElementById("clone-refresh")
      .addEventListener("click", refreshClone);
    for (const eventName of ["input", "change"]) {
      editor.addEventListener(eventName, () => {
        if (cloneOperation?.kind === "write" && !cloneSent)
          AdminUI.cancelConfirmation();
      });
    }
    initializeManualForm();
    document
      .getElementById("manual-form")
      ?.addEventListener("submit", (event) => {
        event.preventDefault();
        submitManual();
      });
    for (const eventName of ["input", "change"])
      document
        .getElementById("manual-form")
        ?.addEventListener(eventName, manualChanged);
    document
      .getElementById("manual-baseline")
      ?.addEventListener("click", acceptManualBaseline);
    document
      .getElementById("manual-reconfirm")
      ?.addEventListener("click", () => submitManual(true));
    document
      .getElementById("manual-discard")
      ?.addEventListener("click", discardManual);
    document
      .getElementById("manual-resolution-add")
      ?.addEventListener("click", addManualResolution);
    document.getElementById("manual-refresh")?.addEventListener("click", () => {
      if (manualOperation) return;
      const pending = compare(true);
      const current = generation;
      pending.catch(() => {
        const message = document.getElementById("manual-status");
        if (message && detail && current === generation && !editor.hidden)
          message.textContent =
            "현재 GET 조회 실패 · 기록 영수증·입력·원래 키와 본문은 유지됩니다. 자동 재전송하지 않습니다.";
      });
    });
    document
      .getElementById("review-request")
      ?.addEventListener("click", () => runLifecycle("REQUEST"));
    document
      .getElementById("review-request-replay")
      ?.addEventListener("click", () => runLifecycle("REQUEST", true));
    for (const [id, action] of [
      ["review-withdraw-form", "WITHDRAW"],
      ["review-changes-form", "CHANGES_REQUIRED"],
    ]) {
      document.getElementById(id)?.addEventListener("submit", (event) => {
        event.preventDefault();
        runLifecycle(action);
      });
    }
    document
      .getElementById("review-lifecycle-refresh")
      ?.addEventListener("click", () => {
        const pending = compare(true);
        const current = generation;
        pending.catch((error) => {
          if (detail && current === generation) {
            document.getElementById("review-lifecycle-status").textContent =
              "현재 GET 조회 실패 · 기존 영수증과 입력은 유지됩니다. 자동 재전송하지 않습니다.";
            status(errorMessage(error), true);
          }
        });
      });
    document
      .getElementById("saved-metadata-refresh")
      ?.addEventListener("click", reconcileSavedMetadata);
    document
      .getElementById("saved-issues-filter")
      ?.addEventListener("change", () => {
        clearSavedIssues();
        syncSavedIssueControls();
      });
    for (const [id, next] of [
      ["saved-issues-first", false],
      ["saved-issues-next", true],
    ]) {
      document
        .getElementById(id)
        ?.addEventListener("click", () => readSavedIssues(next));
    }
    for (const [id, lane, next] of [
      ["saved-history-first", "history", false],
      ["saved-history-next", "history", true],
      ["saved-payload-read", "payload", false],
      ["saved-records-first", "records", false],
      ["saved-records-next", "records", true],
      ["saved-preview-read", "preview", false],
    ]) {
      document
        .getElementById(id)
        ?.addEventListener("click", () => readSavedReview(lane, next));
    }
    for (const [id, eventName] of [
      ["saved-preview-source", "change"],
      ["saved-preview-mode", "change"],
      ["saved-preview-role", "input"],
      ["saved-preview-role", "change"],
    ]) {
      document.getElementById(id)?.addEventListener(eventName, () => {
        clearSavedPrivate();
        syncSavedReviewControls();
      });
    }
    for (const eventName of ["input", "change"]) {
      editor.addEventListener(eventName, (event) => {
        if (
          event.target.matches(
            "[data-field], [data-value], #review-withdraw-ref, #review-changes-ref, #review-changes-reason",
          )
        )
          invalidateReview();
      });
    }
    document
      .getElementById("review-precheck")
      .addEventListener("click", () => runReviewCheck(false));
    document
      .getElementById("review-samples-check")
      .addEventListener("click", () => runReviewCheck(true));
    document
      .getElementById("review-samples-open")
      .addEventListener("click", () =>
        openReviewLocation({ resource: "gradeSamples" }),
      );
    document
      .getElementById("review-check-refresh")
      .addEventListener("click", () => {
        const pending = compare();
        const current = generation;
        pending.catch((error) => {
          if (detail && current === generation) {
            document.getElementById("review-check-status").textContent =
              "현재 저장본 조회 실패. 결과는 미확인이며 자동 재전송하지 않습니다.";
            status(errorMessage(error), true);
          }
        });
      });
    revealEditorPanel(document.getElementById("basic-heading"));
    // 같은 문서의 일반 왼쪽 클릭만 처리하며 보조키·외부 링크의 브라우저 동작은 유지한다.
    document.addEventListener("click", (event) => {
      const link = event.target.closest("a[href]");
      if (
        !link ||
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      )
        return;
      const url = new URL(link.href, window.location.href);
      if (
        url.origin !== window.location.origin ||
        url.pathname !== window.location.pathname ||
        url.search !== window.location.search ||
        !url.hash
      )
        return;
      if (focusEditorFragment(url.hash)) {
        event.preventDefault();
        // 이력만 갱신해 지연된 hashchange가 새 입력 중 포커스를 다시 옮기지 않게 한다.
        if (window.location.hash !== url.hash)
          window.history.pushState(null, "", url.hash);
      }
    });
    // 뒤로/앞으로 이동과 직접 변경한 문서 조각도 현재 표시된 입력으로 안내한다.
    window.addEventListener("hashchange", () => {
      focusEditorFragment(window.location.hash);
    });
    document
      .getElementById("child-resource")
      .addEventListener("change", (event) => {
        selectChildResource(event.currentTarget.value);
      });
    document
      .getElementById("child-new")
      .addEventListener("click", (event) =>
        selectChild(undefined, event.currentTarget),
      );
    document
      .getElementById("child-list")
      .addEventListener("click", () => loadChildren());
    document
      .getElementById("child-next")
      .addEventListener("click", () => loadChildren(true));
    document.getElementById("child-filter").addEventListener("change", () => {
      resetChildren();
      loadChildren();
    });
    document
      .getElementById("child-active")
      .addEventListener("click", async (event) => {
        if (saveLocked || !detail?.childItem) return;
        const button = event.currentTarget;
        const current = generation;
        const previousDetail = detail;
        const resource = childResource;
        const key = childKey;
        const item = detail.childItem;
        const form = editor.querySelector('[data-section="child"]');
        const operation = detail.childItem.activeYn
          ? "deactivate"
          : "reactivate";
        if (!(await observeEditorIdentity())) return;
        if (
          !(await AdminUI.confirm({
            title: `${childTypes[resource].label} ${operation === "deactivate" ? "논리 삭제" : "복원"}`,
            message: `${childTypes[resource].label}을 ${operation === "deactivate" ? "논리 삭제" : "복원"}합니까? 미저장 원고는 저장하지 않으며 다른 연결을 자동으로 바꾸지 않습니다.`,
            confirmLabel: operation === "deactivate" ? "논리 삭제" : "복원",
          }))
        )
          return;
        if (!(await observeEditorIdentity())) return;
        if (
          current !== generation ||
          detail !== previousDetail ||
          resource !== childResource ||
          key !== childKey ||
          detail.childItem !== item ||
          saveLocked ||
          !writableSection("basic") ||
          (item.activeYn ? "deactivate" : "reactivate") !== operation ||
          !form.isConnected ||
          form !== editor.querySelector('[data-section="child"]') ||
          !button.isConnected ||
          button.disabled
        )
          return;
        save(form, operation);
      });
    document.getElementById("refresh-latest").addEventListener("click", () => {
      compare().catch((error) => {
        if (![401, 403, 404].includes(error.status))
          status(errorMessage(error), true);
      });
    });
    document.getElementById("editor").addEventListener("submit", (event) => {
      const form = event.target.closest("[data-section]");
      if (!form) return;
      event.preventDefault();
      save(form);
    });
    document
      .getElementById("editor")
      .addEventListener("change", syncRubricFixedFields);
    document
      .getElementById("accept-latest")
      .addEventListener("click", async (event) => {
        const accepted = latest;
        const current = generation;
        const serial = comparisonRequest;
        const previousDetail = detail;
        const resource = childResource;
        const key = childKey;
        const lifecycle = lifecycleComparison;
        const button = event.currentTarget;
        if (
          !latest ||
          latest.childKey !== childKey ||
          latest.childResource !== childResource ||
          !copyPermissions(latest.permissions) ||
          latest.permissionEpoch !== permissionEpoch ||
          !latest.storyActiveYn ||
          !latest.activeYn ||
          !(
            latest.status === "DRAFT" ||
            (lifecycle && ["REVIEW", "READY"].includes(latest.status))
          )
        )
          return;
        if (!(await observeEditorIdentity())) return;
        if (
          !(await AdminUI.confirm({
            title: "서버 최신본 수락",
            message:
              "최신 상태·현재 사본·권한과 남은 입력을 비교했습니까? 이 기준을 수락해도 저장·전환은 실행하지 않으며 미확인 영수증을 성공으로 바꾸지 않습니다.",
            confirmLabel: "최신본 수락",
          }))
        )
          return;
        if (!(await observeEditorIdentity())) return;
        if (
          accepted !== latest ||
          current !== generation ||
          serial !== comparisonRequest ||
          previousDetail !== detail ||
          resource !== childResource ||
          key !== childKey ||
          accepted.childResource !== resource ||
          accepted.childKey !== key ||
          !copyPermissions(accepted.permissions) ||
          accepted.permissionEpoch !== permissionEpoch ||
          lifecycle !== lifecycleComparison ||
          !button.isConnected ||
          button.disabled
        )
          return;
        beginEditorOperation();
        reviewNeedsRead = false;
        lifecycleNeedsRead = false;
        // 확정 거절만 명시 비교 수락으로 종료한다. 미확정/CSRF 복구 키는 GET으로 폐기하지 않는다.
        if (lifecycleIntent?.rejected) lifecycleIntent = undefined;
        if (manualIntent?.rejected && !manualIntent.unknown)
          manualIntent = undefined;
        fillDetail(accepted, { preserve: true, acceptPermissions: true });
        status(
          "서버 최신값과 수정번호를 화면에 반영했습니다. 남은 입력을 확인하고 영역별로 저장하세요.",
        );
      });
    document.getElementById("retry-detail").addEventListener("click", () => {
      loadDetail().catch(detailFailure);
    });
    observeEditorIdentity(true)
      .then((owned) => {
        if (!owned) throw { status: 401, code: "EDITOR_IDENTITY_UNCONFIRMED" };
        return loadDetail();
      })
      .then(navigation)
      .catch(detailFailure);
  } else {
    document
      .getElementById("manage-refresh")
      .addEventListener("click", async () => {
        if (!selectedStory) return;
        AdminUI.cancelConfirmation();
        const current = generation;
        const epoch = manageEpoch;
        const story = selectedStory;
        try {
          const state = await request(
            "GET",
            `?${new URLSearchParams({ code: selectedStory.storyCode, activeYn: String(manageSnapshot?.activeYn ?? selectedStory.activeYn) })}`,
          );
          if (
            current !== generation ||
            epoch !== manageEpoch ||
            selectedStory !== story
          )
            return;
          AdminUI.cancelConfirmation();
          selectedStory = state.items[0];
          if (!selectedStory) {
            document.getElementById("manage-panel").hidden = true;
            status(
              "사건 상태를 확인할 수 없습니다. 목록을 다시 조회하세요.",
              true,
            );
            return;
          }
          const refreshedStory = selectedStory;
          await loadAccess();
          if (
            current !== generation ||
            epoch !== manageEpoch ||
            selectedStory !== refreshedStory
          )
            return;
          document.getElementById("manage-result").textContent =
            "현재 사건과 접근 관계를 조회했습니다. 입력을 확인한 뒤 직접 실행하세요.";
        } catch (error) {
          if (current !== generation || epoch !== manageEpoch) return;
          if (error.code === "REAUTH_REQUIRED") openStoryReauth();
          status(errorMessage(error), true);
        }
      });
    document.getElementById("manage-close").addEventListener("click", () => {
      AdminUI.cancelConfirmation();
      document.getElementById("manage-panel").hidden = true;
      selectedStory = manageSnapshot = undefined;
      ++manageEpoch;
    });
    document
      .getElementById("access-next")
      .addEventListener("click", async (event) => {
        const button = event.currentTarget;
        button.disabled = true;
        try {
          await loadAccess(true);
        } catch (error) {
          status(errorMessage(error), true);
        } finally {
          button.disabled = false;
        }
      });
    document
      .getElementById("access-operation")
      .addEventListener("change", (event) => {
        if (event.currentTarget.value === "grant")
          document.getElementById("access-reason").value = "ASSIGNMENT_CHANGE";
        for (const option of document.getElementById("access-reason").options)
          option.disabled =
            event.currentTarget.value === "grant" &&
            option.value !== "ASSIGNMENT_CHANGE";
      });
    document
      .getElementById("access-form")
      .addEventListener("submit", (event) => {
        event.preventDefault();
        changeAccess(event.currentTarget);
      });
    document
      .getElementById("state-change")
      .addEventListener("click", (event) => {
        changeStoryState(event.currentTarget);
      });
    document
      .getElementById("story-reauth-close")
      .addEventListener("click", () => {
        document.getElementById("story-reauth").close();
      });
    document.getElementById("story-reauth").addEventListener("close", () => {
      document.getElementById("story-reauth-form").reset();
      document.getElementById("story-reauth-error").textContent = "";
    });
    document
      .getElementById("story-reauth-form")
      .addEventListener("submit", async (event) => {
        event.preventDefault();
        const button = event.currentTarget.querySelector('[type="submit"]');
        button.disabled = true;
        try {
          await reauthenticate(event.currentTarget);
        } catch (error) {
          document.getElementById("story-reauth-error").textContent =
            errorMessage(error);
        } finally {
          button.disabled = false;
        }
      });
    document
      .getElementById("ownership-refresh")
      .addEventListener("click", () =>
        loadOwnership().catch((error) => status(errorMessage(error), true)),
      );
    document
      .getElementById("ownership-close")
      .addEventListener("click", async (event) => {
        const button = event.currentTarget;
        const current = generation;
        const epoch = ownershipEpoch;
        const story = ownershipStory;
        const snapshot = ownershipSnapshot;
        const intent = ownershipIntent;
        if (
          intent &&
          !(await AdminUI.confirm({
            title: "인계 의도 버리고 닫기",
            message:
              "인계 결과가 불확실합니다. 원래 의도 키를 버리고 닫습니까?",
            confirmLabel: "의도 버리고 닫기",
          }))
        )
          return;
        if (
          current !== generation ||
          epoch !== ownershipEpoch ||
          story !== ownershipStory ||
          snapshot !== ownershipSnapshot ||
          intent !== ownershipIntent ||
          !button.isConnected ||
          button.closest("[hidden]")
        )
          return;
        AdminUI.cancelConfirmation();
        ownershipStory = ownershipSnapshot = ownershipIntent = undefined;
        ++ownershipEpoch;
        document.getElementById("ownership-panel").hidden = true;
      });
    document
      .getElementById("ownership-request-form")
      .addEventListener("submit", async (event) => {
        event.preventDefault();
        const form = event.currentTarget;
        const button = form.querySelector('[type="submit"]');
        const current = generation;
        const epoch = ownershipEpoch;
        const story = ownershipStory;
        const snapshot = ownershipSnapshot;
        const intent = ownershipIntent;
        if (!story || !snapshot || intent || button.disabled) return;
        const storyRev = snapshot.storyRev;
        const body = {
          toAccountKey: form.elements.toAccountKey.value.trim(),
          keepEditor: form.elements.keepEditor.checked,
          reasonCode: "HANDOVER",
          verificationRef: form.elements.verificationRef.value.trim(),
        };
        if (
          !(await AdminUI.confirm({
            title: "소유권 인계 요청",
            message:
              "활성 공동 EDIT 관리자에게 24시간 소유권 수락 요청을 만듭니까?",
            confirmLabel: "인계 요청",
          }))
        )
          return;
        if (
          current !== generation ||
          epoch !== ownershipEpoch ||
          story !== ownershipStory ||
          snapshot !== ownershipSnapshot ||
          intent !== ownershipIntent ||
          snapshot.storyRev !== storyRev ||
          !form.isConnected ||
          form.closest("[hidden]") ||
          button.disabled
        )
          return;
        ownerIntent("/requests", body);
      });
    document
      .getElementById("ownership-override-form")
      .addEventListener("submit", async (event) => {
        event.preventDefault();
        const form = event.currentTarget;
        const button = form.querySelector('[type="submit"]');
        const current = generation;
        const epoch = ownershipEpoch;
        const story = ownershipStory;
        const snapshot = ownershipSnapshot;
        const intent = ownershipIntent;
        if (!story || !snapshot || intent || button.disabled) return;
        const storyRev = snapshot.storyRev;
        const body = {
          toAccountKey: form.elements.toAccountKey.value.trim(),
          keepEditor: false,
          reasonCode: form.elements.reasonCode.value,
          verificationRef: form.elements.verificationRef.value.trim(),
        };
        if (
          !(await AdminUI.confirm({
            title: "소유권 운영 인계",
            message:
              "현재 소유자의 실제 비활성/복구 제한을 별도 확인했고 새 소유자에게 인계합니까?",
            confirmLabel: "운영 인계",
          }))
        )
          return;
        if (
          current !== generation ||
          epoch !== ownershipEpoch ||
          story !== ownershipStory ||
          snapshot !== ownershipSnapshot ||
          intent !== ownershipIntent ||
          snapshot.storyRev !== storyRev ||
          !form.isConnected ||
          form.closest("[hidden]") ||
          button.disabled
        )
          return;
        ownerIntent("/override", body);
      });
    for (const [button, decision] of [
      ["ownership-accept", "ACCEPT"],
      ["ownership-cancel", "CANCEL"],
      ["ownership-decline", "DECLINE"],
    ])
      document
        .getElementById(button)
        .addEventListener("click", (event) =>
          closeOwnership(decision, event.currentTarget),
        );
    document
      .getElementById("ownership-replay")
      .addEventListener("click", submitOwnership);
    document
      .getElementById("ownership-new-intent")
      .addEventListener("click", async (event) => {
        const button = event.currentTarget;
        const current = generation;
        const epoch = ownershipEpoch;
        const story = ownershipStory;
        const snapshot = ownershipSnapshot;
        const intent = ownershipIntent;
        if (!story || !intent || button.disabled) return;
        if (
          !(await AdminUI.confirm({
            title: "새 인계 의도 시작",
            message:
              "이전 인계 확정 여부를 확인했습니까? 새 의도는 원래 요청을 되돌리지 않습니다.",
            confirmLabel: "새 의도 시작",
          }))
        )
          return;
        if (
          current !== generation ||
          epoch !== ownershipEpoch ||
          story !== ownershipStory ||
          snapshot !== ownershipSnapshot ||
          intent !== ownershipIntent ||
          !button.isConnected ||
          button.closest("[hidden]") ||
          button.disabled
        )
          return;
        try {
          await loadOwnership();
          if (
            current !== generation ||
            epoch !== ownershipEpoch ||
            story !== ownershipStory ||
            intent !== ownershipIntent ||
            !ownershipSnapshot ||
            !button.isConnected ||
            button.closest("[hidden]")
          )
            return;
          AdminUI.cancelConfirmation();
          ownershipIntent = undefined;
          document.getElementById("ownership-reconcile").hidden = true;
        } catch (error) {
          if (
            current !== generation ||
            epoch !== ownershipEpoch ||
            story !== ownershipStory ||
            intent !== ownershipIntent
          )
            return;
          status(errorMessage(error), true);
        }
      });
    document
      .getElementById("filter-form")
      .addEventListener("submit", (event) => {
        event.preventDefault();
        filters = { activeYn: event.currentTarget.elements.activeYn.value };
        const code = event.currentTarget.elements.code.value;
        if (code) filters.code = code;
        afterId = undefined;
        ++listRequest;
        document.getElementById("rows").replaceChildren();
        document.getElementById("next").hidden = true;
        loadList().catch(listFailure);
      });
    document.getElementById("next").addEventListener("click", async (event) => {
      const button = event.currentTarget;
      button.disabled = true;
      try {
        await loadList(true);
      } catch (error) {
        listFailure(error);
      } finally {
        button.disabled = false;
      }
    });
    document
      .getElementById("create-form")
      .addEventListener("submit", (event) => {
        event.preventDefault();
        create(event.currentTarget);
      });
    document.getElementById("check-create").addEventListener("click", () => {
      checkCreate().catch((error) => {
        if (![401, 403, 404].includes(error.status))
          status(errorMessage(error), true);
      });
    });
    document
      .getElementById("new-create")
      .addEventListener("click", async (event) => {
        const button = event.currentTarget;
        const current = generation;
        const key = createKey;
        const title = createTitle;
        const form = document.getElementById("create-form");
        if (
          !(await AdminUI.confirm({
            title: "새 사건 생성 의도",
            message:
              "이전 생성 결과가 불확실할 수 있습니다. 새 사건 생성 의도를 시작합니까?",
            confirmLabel: "새 생성 의도 시작",
          }))
        )
          return;
        if (
          current !== generation ||
          key !== createKey ||
          title !== createTitle ||
          !form.isConnected ||
          form.closest("[hidden]") ||
          !button.isConnected ||
          button.disabled
        )
          return;
        AdminUI.cancelConfirmation();
        createKey = createTitle = undefined;
        form.reset();
        document.getElementById("create-check").hidden = true;
        status(
          "새 생성 의도입니다. 이전 생성의 완료 여부는 자동 확인되지 않습니다.",
        );
      });
    loadList()
      .then(() => {
        navigation();
        configureCreate();
      })
      .catch(listFailure);
  }
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) {
      clearClone();
      location.reload();
    }
  });
  // 문서 경로 변경은 이력 출처만 폐기하며 같은 문서의 목차 이동은 유지한다.
  window.addEventListener("popstate", syncSavedIssueControls);
  window.addEventListener("popstate", () => {
    if ((resolutionIntent && editorPath() !== resolutionIntent.editorPath) ||
      (resolutionSource && editorPath() !== resolutionSource.path)) {
      AdminUI.cancelConfirmation();
      clearResolution();
    }
    if (
      (cloneCandidate &&
        location.pathname !== `/admin/stories${cloneCandidate.path}`) ||
      (cloneIntent &&
        location.pathname !== `/admin/stories${cloneIntent.editorPath}`)
    )
      clearClone();
  });
  // 실제 문서 이탈은 새 기록 메모리와 늦은 콜백 소유권을 폐기한다. bfcache 복귀도 재전송하지 않는다.
  window.addEventListener("pagehide", () => {
    clearResolution();
    clearClone();
    clearSavedIssues();
    manualBaseline =
      manualIntent =
      manualOperation =
      manualDraftSnapshot =
        undefined;
    ++manualInputEpoch;
    document.getElementById("manual-form")?.reset();
    document.getElementById("manual-resolutions")?.replaceChildren();
    document.getElementById("manual-receipt")?.replaceChildren();
    manualAcknowledged = manualFingerprint();
  });
  // 브라우저의 실제 이탈 승인은 메모리 폐기이며 조회·비우기로 미확정 전환을 성공 처리하지 않는다.
  window.addEventListener("beforeunload", (event) => {
    if (
      editor &&
      (reviewHasDrafts() ||
        resolutionProtected() ||
        cloneProtected() ||
        manualProtected() ||
        returnHasDrafts() ||
        lifecycleOperation ||
        lifecycleNeedsRead ||
        lifecycleIntent?.sent ||
        unknownReturnSnapshot)
    ) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
})();
