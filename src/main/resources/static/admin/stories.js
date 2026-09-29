(() => {
  "use strict";
  const api = "/admin/api/stories";
  const notice = document.getElementById("notice");
  const editor = document.getElementById("editor");
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

  /** 원문을 넣지 않은 상태 문구와 오류 강조 여부를 표시한다. */
  function status(text, error = false) {
    notice.textContent = text;
    notice.classList.toggle("error", error);
  }

  /** 접근 종료 시 원고·생성 의도·자격 메모리를 지우고 늦은 응답을 무효화한다. */
  function revoke(text, code) {
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
    document.getElementById("create-form")?.reset();
    document.getElementById("create-check")?.setAttribute("hidden", "");
    if (editor) {
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

  /** 같은 출처 API만 호출한다. missing 허용 GET은 호출자가 부모를 다시 인가한 뒤 부재를 사용해야 한다. */
  async function request(method, path, body, missing = false, rawJsonFields) {
    const headers = { Accept: "application/json" };
    if (method !== "GET") {
      if (!csrf) {
        const response = await fetch("/admin/api/auth/csrf", {
          credentials: "same-origin",
          cache: "no-store",
        });
        if (!response.ok) {
          if ([401, 403, 404].includes(response.status))
            revoke("현재 작업 자격을 확인할 수 없습니다.", response.status);
          throw { status: response.status };
        }
        csrf = await response.json();
      }
      headers[csrf.headerName] = csrf.token;
      headers["Content-Type"] = "application/json";
    }
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
                rawJsonFields === undefined
                  ? JSON.stringify(body)
                  : jsonFieldBody(body, rawJsonFields),
            }
          : {}),
      });
    } catch {
      throw { status: 503 };
    }
    let data;
    try {
      data = await response.json();
    } catch {
      if (response.ok) throw { status: 503 };
    }
    if (!response.ok) {
      if (missing && method === "GET" && response.status === 404) return null;
      if (
        [401, 403, 404].includes(response.status) &&
        data?.code !== "REAUTH_REQUIRED" &&
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
      if (data?.code === "CSRF_INVALID") csrf = undefined;
      throw { status: response.status, code: data?.code };
    }
    return data;
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

  /** 사건 경로나 원고 대신 고정된 화면 코드만 기록한다. */
  async function navigation() {
    try {
      if (!csrf) {
        const response = await fetch("/admin/api/auth/csrf", {
          credentials: "same-origin",
          cache: "no-store",
        });
        if (!response.ok) return;
        csrf = await response.json();
      }
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

  /** 실제 선택된 영역·필드 수만 표시하며 저장 여부를 추정하지 않는다. */
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

  /** 고정 오류 문구를 해당 필드에 연결하고, 입력이 숨겨졌으면 표시된 저장 동작 선택기로 포커스를 옮긴다. */
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

  /** 선택한 입력만 복사하며 브라우저 영구 저장소에는 보관하지 않는다. */
  function captureDrafts() {
    const drafts = {};
    for (const form of editor.querySelectorAll("[data-section]")) {
      const section = form.dataset.section;
      drafts[section] = {};
      for (const select of form.querySelectorAll("[data-field]")) {
        if (select.value === "keep") continue;
        const input = form.querySelector(
          `[data-value="${select.dataset.field}"]`,
        );
        drafts[section][select.dataset.field] = {
          mode: select.value,
          value: input.value,
        };
      }
    }
    return drafts;
  }

  /** 확인된 서버본을 반영하되 확정된 제출 외 미저장 입력과 포커스를 보존한다. */
  function fillDetail(data, { preserve = false, confirmed } = {}) {
    ++comparisonRequest;
    const focusedId = document.activeElement?.id;
    const selection = document.activeElement?.selectionStart;
    const drafts = preserve && detail ? captureDrafts() : {};
    detail = data;
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
    if (preserve && focusedId) {
      const target = document.getElementById(focusedId);
      if (target && !target.closest("[hidden]")) {
        target.focus({ preventScroll: true });
        if (selection != null && ["text", "textarea"].includes(target.type))
          target.setSelectionRange(selection, selection);
      } else {
        document.getElementById("basic-heading").focus();
      }
    }
    status(
      data.status === "DRAFT" && data.storyActiveYn && data.activeYn
        ? "현재 원고를 조회했습니다. 바꿀 항목만 저장 동작을 선택하세요."
        : "현재 버전은 읽기 전용입니다.",
    );
  }

  /** 부모 상태와 선택 자료의 활성 상태를 함께 확인하며 버튼만으로 인가를 대신하지 않는다. */
  function writableSection(section, data = detail) {
    return Boolean(
      data?.storyActiveYn &&
      data.activeYn &&
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

  /** 편집 작업의 소유 세대를 교체해 이전 목록·선택·비교 응답과 수락을 모두 무효화한다. */
  function beginEditorOperation() {
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

  /** 자료 종류 변경은 현재 자식의 입력 폐기만 확인하고 다른 영역과 작성 순서는 건드리지 않는다. */
  function selectChildResource(resource) {
    const select = document.getElementById("child-resource");
    if (
      !Object.hasOwn(childTypes, resource) ||
      saveLocked ||
      !detail ||
      (Object.keys(captureDrafts().child).length &&
        !confirm(
          "현재 자료의 미저장 입력을 버리고 종류를 바꿉니까? 다른 영역 입력은 유지됩니다.",
        ))
    ) {
      select.value = childResource;
      return;
    }
    beginEditorOperation();
    childResource = resource;
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

  /** 호출 시 자원·키를 고정한다. 부모와 자식의 수정번호가 같아야 하며 부재는 부모 재인가로 확인한다. */
  async function readEditor(key = childKey, resource = childResource) {
    const data = await request("GET", editorPath());
    let item = null;
    if (key) {
      const child = await request(
        "GET",
        `${editorPath()}/${resource}/${encodeURIComponent(key)}`,
        undefined,
        true,
      );
      const revision =
        child?.editRev ?? (await request("GET", editorPath())).editRev;
      if (revision !== data.editRev)
        throw { status: 503, code: "READ_REVISION_CHANGED" };
      item = child?.item ?? null;
    }
    return {
      ...data,
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

  /** 불확실한 다중 조회는 과거 비교 수락까지 무효화하고 명시적 GET 재시도만 허용한다. */
  function lockForReview(message) {
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

  /** key는 현재 자원의 같은 버전 키이며 생략하면 새 작성이다. 다른 영역의 미저장 원고는 유지한다. */
  async function selectChild(key) {
    if (!detail || saveLocked) return;
    const previous = captureDrafts().child;
    if (
      Object.keys(previous).length &&
      !confirm(
        "현재 자료의 미저장 입력을 버리고 다른 항목을 작성·조회합니까? 다른 영역 입력은 유지됩니다.",
      )
    )
      return;
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
      document.getElementById("child-edit-heading").focus();
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
        button.addEventListener("click", () => selectChild(key));
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

  /** 현재 허용된 버전을 읽고 변경 영수증으로 편집 입력을 채우지 않는다. */
  async function loadDetail(options) {
    const current = beginEditorOperation();
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

  /** 충돌 시 현재 입력을 메모리에 유지하고 서버 최신본을 별도로 표시한다. */
  function showComparison(data) {
    saveLocked = true;
    syncChildControls();
    for (const button of editor.querySelectorAll("form[data-section] button"))
      button.disabled = true;
    latest = data;
    const panel = document.getElementById("comparison");
    const values = document.getElementById("latest-values");
    values.replaceChildren();
    const summary = document.createElement("p");
    summary.textContent = `기존 수정번호 ${detail.editRev} → 서버 최신 수정번호 ${data.editRev}. 선택한 입력은 아래 폼에 그대로 남아 있습니다.`;
    values.append(summary);
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
      !data.storyActiveYn || !data.activeYn || data.status !== "DRAFT";
    const heading = document.getElementById("compare-heading");
    heading.focus();
    status(
      "최신 자료와 남은 입력을 비교하세요. 검토 전 저장은 차단됩니다.",
      true,
    );
  }

  /** 재조회 중 과거 비교본의 수락을 막고 가장 최근 읽기 결과만 표시한다. */
  async function compare() {
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
        showComparison(data);
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

  /** 선택 필드만 저장한다. 자식 상태 변경을 구분하고 새 조합만 ASCII 정순으로 확정한다. 단서 배정은 입력의 의미 순서를 유지한다. */
  async function save(form, operation) {
    if (!detail || saveLocked) {
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

  /** 인증된 소유자·운영자의 관계 목록을 읽고 복원 영향의 전체 수를 명확히 표시한다. */
  async function loadAccess(append = false) {
    if (!selectedStory) return;
    const epoch = manageEpoch;
    const code = selectedStory.storyCode;
    const after = append ? manageAfterKey : undefined;
    const query = new URLSearchParams({ size: "20" });
    if (after) query.set("afterKey", after);
    const data = await request(
      "GET",
      `/${encodeURIComponent(code)}/access?${query}`,
    );
    if (epoch !== manageEpoch) return;
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

  /** 목록 행의 현재 상태를 직접 조회하며 쓰기 충돌 뒤 자동 재전송하지 않는다. */
  async function openManage(item) {
    selectedStory = item;
    manageSnapshot = undefined;
    manageAfterKey = undefined;
    ++manageEpoch;
    document.getElementById("manage-panel").hidden = true;
    document.getElementById("manage-result").textContent = "";
    try {
      await loadAccess();
    } catch (error) {
      if (error.code === "REAUTH_REQUIRED")
        document.getElementById("story-reauth").showModal();
      status(errorMessage(error), true);
    }
  }

  /** 현재 수정번호와 검토한 관계 영향을 이용해 단일 사건 상태 변경을 명시적으로 제출한다. */
  async function changeStoryState(button) {
    if (!selectedStory || !manageSnapshot || button.disabled) return;
    const input = document.getElementById("state-reference");
    if (!input.reportValidity()) return;
    const active = manageSnapshot.activeYn;
    if (!active && !document.getElementById("state-impact-reviewed").checked) {
      status("복원 전 남은 활성 관계 수와 목록을 확인하세요.", true);
      return;
    }
    if (
      !confirm(
        active
          ? "최초 초안 사건만 논리 삭제합니까? 원고와 관계는 유지되며 자동 재전송하지 않습니다."
          : `활성 접근 관계 ${manageSnapshot.activeRelationCount}건의 접근이 다시 가능해집니다. 사건을 복원합니까?`,
      )
    )
      return;
    button.disabled = true;
    const operation = active ? "deactivate" : "reactivate";
    try {
      const result = await request(
        "POST",
        `/${encodeURIComponent(selectedStory.storyCode)}/${operation}`,
        {
          expectedStoryRev: manageSnapshot.storyRev,
          expectedRev: selectedStory.editRev,
          reasonCode: active ? "DRAFT_WITHDRAWN" : "WORK_RESUMED",
          verificationRef: input.value,
        },
      );
      input.value = "";
      document.getElementById("manage-panel").hidden = true;
      selectedStory = manageSnapshot = undefined;
      ++manageEpoch;
      await loadList();
      status(
        `현재 사건 ${result.activeYn ? "복원" : "논리 삭제"} ${result.changed ? "확정" : "무변경"} · 사건 수정번호 ${result.storyRev}.`,
      );
    } catch (error) {
      if (error.code === "REAUTH_REQUIRED")
        document.getElementById("story-reauth").showModal();
      document.getElementById("manage-result").textContent =
        `${errorMessage(error)} 현재 사건·관계를 다시 조회한 뒤 직접 결정하세요.`;
      status(errorMessage(error), true);
    } finally {
      button.disabled = false;
    }
  }

  /** 관계 변경은 서버 현재 사건 수정번호 한 번만 사용하고 결과가 불확실하면 조회로 멈춘다. */
  async function changeAccess(form) {
    if (!selectedStory || !manageSnapshot) return;
    const button = document.getElementById("access-submit");
    if (button.disabled) return;
    const input = Object.fromEntries(new FormData(form));
    const operation = input.operation;
    const reason = input.reasonCode;
    if (operation === "grant" && reason !== "ASSIGNMENT_CHANGE") {
      status("부여 사유는 담당 변경만 선택할 수 있습니다.", true);
      return;
    }
    if (
      !confirm(
        `${input.permission} 관계를 ${operation === "grant" ? "부여" : "회수"}합니까? 자동 재전송하지 않습니다.`,
      )
    )
      return;
    button.disabled = true;
    try {
      const result = await request(
        "POST",
        `/${encodeURIComponent(selectedStory.storyCode)}/access/${operation}`,
        {
          expectedStoryRev: manageSnapshot.storyRev,
          accountKey: input.accountKey,
          permission: input.permission,
          reasonCode: reason,
          verificationRef: input.verificationRef,
        },
      );
      form.elements.verificationRef.value = "";
      await loadAccess();
      const message =
        result.auditStatus === "UNCONFIRMED"
          ? "접근 차단은 확정됐으나 업무 감사가 미확정입니다. 운영 점검이 필요합니다."
          : result.changed
            ? `관계 변경 확정 · 사건 수정번호 ${result.storyRev}.`
            : "관계는 이미 요청한 상태입니다. 변경하지 않았습니다.";
      document.getElementById("manage-result").textContent = message;
      status(message, result.auditStatus === "UNCONFIRMED");
    } catch (error) {
      if (error.code === "REAUTH_REQUIRED")
        document.getElementById("story-reauth").showModal();
      document.getElementById("manage-result").textContent =
        `${errorMessage(error)} 현재 관계를 다시 조회하고 직접 결정하세요.`;
      status(errorMessage(error), true);
    } finally {
      button.disabled = false;
    }
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

  /** 현재 CREATE 권한을 확인한 뒤에만 생성 폼을 공개한다. */
  async function configureCreate() {
    try {
      const response = await fetch("/admin/api/auth/me", {
        credentials: "same-origin",
        cache: "no-store",
      });
      if (response.status === 401) {
        revoke("세션이 만료되었습니다.", 401);
        return;
      }
      if (!response.ok) throw { status: response.status };
      const me = await response.json();
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

  /** 최초 조회 실패는 입력을 전송하지 않는 재조회 행동으로 안내한다. */
  function detailFailure(error) {
    if ([401, 403, 404].includes(error.status)) return;
    const panel = document.getElementById("unavailable");
    panel.hidden = false;
    document.getElementById("unavailable-message").textContent =
      "사건 자료 조회에 실패했습니다. 입력을 보내지 않고 다시 조회할 수 있습니다.";
    status(errorMessage(error), true);
  }

  if (editor) {
    document
      .getElementById("child-resource")
      .addEventListener("change", (event) => {
        selectChildResource(event.currentTarget.value);
      });
    document
      .getElementById("child-new")
      .addEventListener("click", () => selectChild());
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
    document.getElementById("child-active").addEventListener("click", () => {
      if (saveLocked || !detail?.childItem) return;
      const operation = detail.childItem.activeYn ? "deactivate" : "reactivate";
      if (
        !confirm(
          `${childTypes[childResource].label}을 ${operation === "deactivate" ? "논리 삭제" : "복원"}합니까? 미저장 원고는 저장하지 않으며 다른 연결을 자동으로 바꾸지 않습니다.`,
        )
      )
        return;
      save(editor.querySelector('[data-section="child"]'), operation);
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
    document.getElementById("accept-latest").addEventListener("click", () => {
      if (
        !latest ||
        latest.childKey !== childKey ||
        latest.childResource !== childResource ||
        !latest.storyActiveYn ||
        !latest.activeYn ||
        latest.status !== "DRAFT"
      )
        return;
      if (
        !confirm(
          "최신 저장값과 남은 입력을 비교했습니까? 저장은 현재 입력에서 선택한 필드만 최신 수정번호를 기준으로 적용합니다.",
        )
      )
        return;
      const accepted = latest;
      beginEditorOperation();
      fillDetail(accepted, { preserve: true });
      status(
        "서버 최신값과 수정번호를 화면에 반영했습니다. 남은 입력을 확인하고 영역별로 저장하세요.",
      );
    });
    document.getElementById("retry-detail").addEventListener("click", () => {
      loadDetail().catch(detailFailure);
    });
    loadDetail().then(navigation).catch(detailFailure);
  } else {
    document
      .getElementById("manage-refresh")
      .addEventListener("click", async () => {
        if (!selectedStory) return;
        try {
          const state = await request(
            "GET",
            `?${new URLSearchParams({ code: selectedStory.storyCode, activeYn: String(manageSnapshot?.activeYn ?? selectedStory.activeYn) })}`,
          );
          selectedStory = state.items[0];
          if (!selectedStory) {
            document.getElementById("manage-panel").hidden = true;
            status(
              "사건 상태를 확인할 수 없습니다. 목록을 다시 조회하세요.",
              true,
            );
            return;
          }
          await loadAccess();
          document.getElementById("manage-result").textContent =
            "현재 사건과 접근 관계를 조회했습니다. 입력을 확인한 뒤 직접 실행하세요.";
        } catch (error) {
          if (error.code === "REAUTH_REQUIRED")
            document.getElementById("story-reauth").showModal();
          status(errorMessage(error), true);
        }
      });
    document.getElementById("manage-close").addEventListener("click", () => {
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
    document.getElementById("new-create").addEventListener("click", () => {
      if (
        !confirm(
          "이전 생성 결과가 불확실할 수 있습니다. 새 사건 생성 의도를 시작합니까?",
        )
      )
        return;
      createKey = createTitle = undefined;
      document.getElementById("create-form").reset();
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
    if (event.persisted) location.reload();
  });
  window.addEventListener("beforeunload", (event) => {
    if (
      editor &&
      [...editor.querySelectorAll("[data-field]")].some(
        (select) => select.value !== "keep",
      )
    ) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
})();
