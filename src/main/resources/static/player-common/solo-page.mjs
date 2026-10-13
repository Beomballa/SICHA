import { createWebSession, WebSessionBlocked } from "./web-session.mjs";
import { createSoloClient } from "./solo-client.mjs";
import { UUID4, WebApiError } from "./web-api.mjs";
import {
  parseCatalog,
  parseStory,
  parseNotice,
  parseState,
  parseMaterials,
  parseReport,
  parseReportBody,
  parseResult,
  SoloFormatError,
} from "./solo-model.mjs";

const element = (id) => document.getElementById(id);
const labels = {
  method: "방법",
  time: "시간",
  motive: "동기",
  evidence: "근거",
};
let session, client;
let busy = false,
  state = null,
  story = null,
  notice = null,
  materials = null,
  saved = null,
  result = null;
let cursor = null,
  gameKey = UUID4.test(location.hash.slice(1)) ? location.hash.slice(1) : null;
let dirty = false,
  stale = false;
let heartbeatTimer = null,
  pollTimer = null,
  deadlineTimer = null;
let heartbeatBusy = false;

/** 원문·DOM 입력·기본값·동의·타이머를 함께 폐기한다. 서버 상태를 추정하지 않는다. */
function purge() {
  clearInterval(heartbeatTimer);
  clearTimeout(pollTimer);
  clearTimeout(deadlineTimer);
  heartbeatTimer = pollTimer = deadlineTimer = null;
  state = story = notice = materials = saved = result = null;
  cursor = null;
  dirty = stale = false;
  for (const id of [
    "story-list",
    "roles",
    "persons",
    "clues",
    "hints",
    "required-notices",
    "saved-report",
    "score-details",
    "report-results",
    "reveal",
    "story-title",
    "story-intro",
    "story-setting",
    "story-details",
    "notice-version",
    "notice-body",
    "notice-contact",
    "game-title",
    "game-state",
    "game-counts",
    "deadline",
    "basic-intro",
    "basic-setting",
    "result-summary",
    "saved-revision",
  ])
    element(id).replaceChildren();
  for (const input of document.querySelectorAll("input, textarea")) {
    input.value = "";
    input.defaultValue = "";
    input.checked = false;
    input.defaultChecked = false;
  }
  element("culprit").replaceChildren(new Option("미선택", ""));
  render();
}

/** 서버 원문과 임의 오류 주소를 반사하지 않는 상태 안내다. */
function message(failure) {
  if (failure instanceof SoloFormatError)
    return "응답 형식을 확인할 수 없어 자료를 표시하지 않습니다.";
  if (failure instanceof WebSessionBlocked)
    return "회원 또는 화면 상태가 변경되었습니다. 입력을 비우고 회원 연결을 다시 확인해 주세요.";
  if (failure instanceof WebApiError && failure.status === 503)
    return "공개·수집 준비 또는 서버 연결을 확인할 수 없습니다. 다른 자료로 대신하지 않습니다.";
  if (failure instanceof WebApiError && failure.status === 409)
    return "현재 서버 상태와 요청이 충돌합니다. 원본 명령과 현재 방을 확인해 주세요.";
  return "요청 완료를 확인하지 못했습니다. 자동 재전송하지 않습니다. 서버 상태를 직접 조회해 주세요.";
}

/** 데이터는 항상 textContent로 출력하며 사건 원문을 HTML로 해석하지 않는다. */
function paragraph(parent, value, tag = "p") {
  const node = document.createElement(tag);
  if (tag === "p") node.className = "notice-body";
  node.textContent = value;
  parent.append(node);
  return node;
}

function action(parent, id, label, handler, disabled = false) {
  const button = document.createElement("button");
  button.id = id;
  button.type = "button";
  button.className = "secondary-action";
  button.textContent = label;
  button.disabled = busy || disabled || !!client.pending();
  button.dataset.denied = String(disabled);
  button.addEventListener("click", () => void run(handler));
  parent.append(button);
}

function sameSaved() {
  return (
    state &&
    saved &&
    state.rev === saved.rev &&
    state.draftRev === saved.draftRev &&
    saved.submissionState === "NONE" &&
    state.submissionState === "NONE"
  );
}

/** 표시와 버튼 권한은 확인된 서버 상태·저장본·원본 명령만 사용한다. */
function render() {
  const authenticated = !!session?.current();
  const pending = client?.pending();
  const running = state?.state === "RUNNING";
  for (const button of document.querySelectorAll("button"))
    button.disabled =
      busy || !authenticated || !!pending || button.dataset.denied === "true";
  element("connect").disabled = busy || !!pending || !session;
  element("replay").disabled = busy || !authenticated || !pending?.uncertain;
  element("refresh").disabled = busy || !authenticated || !gameKey;
  element("current-button").disabled = busy || !authenticated || !!pending;
  element("main").setAttribute("aria-busy", String(busy));
  element("catalog").hidden =
    !element("story-list").childElementCount && element("empty").hidden;
  element("next").hidden = cursor === null;
  element("story").hidden = story === null || notice === null;
  element("game").hidden = state === null;
  element("start").hidden = state?.state !== "WAITING";
  element("start").disabled ||= !state?.canStart || state?.needsRefresh;
  element("create").disabled ||=
    !story || !notice || !element("create-consent").checked;
  element("investigation").hidden = !running || !materials;
  element("report-section").hidden =
    !running || !saved || !materials || state.submissionState !== "NONE";
  element("forfeit-section").hidden =
    !running || state.submissionState !== "NONE";
  element("forfeit").disabled ||= !element("forfeit-consent").checked;
  element("save").disabled ||= !dirty || stale || !sameSaved();
  element("revert").disabled ||= !saved || !dirty;
  element("accept-latest").disabled ||= !stale || !dirty || !sameSaved();
  const complete =
    saved?.report.culpritCode !== null &&
    saved &&
    Object.keys(labels).every((key) => saved.report[key].trim());
  element("submit").disabled ||=
    dirty ||
    stale ||
    !complete ||
    !sameSaved() ||
    !element("submit-consent").checked;
  for (const input of document.querySelectorAll(
    "#report-form textarea, #report-form select",
  ))
    input.disabled =
      busy || !authenticated || !!pending || stale || !sameSaved();
  element("submit-consent").disabled =
    busy || !!pending || dirty || stale || !sameSaved() || !complete;
  element("create-consent").disabled = busy || !!pending || !story || !notice;
  element("forfeit-consent").disabled =
    busy || !!pending || !running || state.submissionState !== "NONE";
  element("result").hidden = result === null;
  element("uncertain").hidden = !pending?.uncertain;
  element("intent-name").textContent = pending?.action ?? "";
  if (state) {
    element("game-title").textContent = state.title;
    element("game-state").textContent =
      `${state.state} · 방 수정번호 ${state.rev} · 초안 수정번호 ${state.draftRev}`;
    element("game-counts").textContent =
      `제출 상태 ${state.submissionState} · 남은 시도 ${state.attemptsRemaining ?? "미제공"} · 남은 힌트 ${state.hintsRemaining ?? "미제공"}`;
    element("deadline").textContent = state.playDeadline
      ? "서버 마감 시각: " + state.playDeadline
      : "대기실 서버 만료: " + state.lobbyExpiresAt;
  }
}

/** 독립 사용자 동작을 한 번 실행하며 실패해도 같은 명령을 자동 반복하지 않는다. */
async function run(work) {
  if (busy || !client) return;
  const focus = document.activeElement?.id;
  busy = true;
  element("error").hidden = true;
  element("status").textContent = "서버에서 확인하는 중입니다.";
  render();
  try {
    await work();
    element("status").textContent = client.pending()?.uncertain
      ? "원본 명령 확인이 필요합니다."
      : "서버 응답을 확인했습니다.";
  } catch (failure) {
    if (failure instanceof SoloFormatError) purge();
    element("error").textContent = message(failure);
    element("error").hidden = false;
    element("status").textContent = "완료를 확인하지 못했습니다.";
    clearInterval(heartbeatTimer);
    clearTimeout(pollTimer);
    heartbeatTimer = pollTimer = null;
  } finally {
    busy = false;
    render();
    const node = focus && element(focus);
    if (node && !node.disabled && node.getClientRects().length) node.focus();
  }
}

/** 목록은 서버 cursor를 그대로 전달하며 정상 빈 목록만 빈 상태로 표시한다. */
async function loadCatalog(more = false) {
  if (client.pending()) throw new WebSessionBlocked();
  if (more && cursor === null) throw new SoloFormatError();
  if (!more) {
    client.clear();
    gameKey = null;
    history.replaceState(null, "", "/play");
  }
  const previous = more ? cursor : null;
  const path =
    "/api/stories?size=20" +
    (previous === null ? "" : "&cursor=" + encodeURIComponent(previous));
  const page = await client.read(path, parseCatalog);
  if (previous !== null && page.nextCursor === previous)
    throw new SoloFormatError();
  if (!more) element("story-list").replaceChildren();
  for (const item of page.items) {
    const panel = document.createElement("article");
    panel.className = "panel";
    paragraph(panel, item.title, "h3");
    paragraph(
      panel,
      `난이도 ${item.difficulty} · 예상 ${item.estMin}~${item.estMax}분`,
    );
    const controls = document.createElement("div");
    controls.className = "actions";
    action(controls, "story-" + item.storyCode, "사건 소개 확인", () =>
      selectStory(item.storyCode),
    );
    panel.append(controls);
    element("story-list").append(panel);
  }
  cursor = page.nextCursor;
  element("empty").hidden = element("story-list").childElementCount !== 0;
}

/** 소개와 별도 현재 고지가 일치할 때만 참여 동의를 받는다. */
async function selectStory(code) {
  client.clear();
  gameKey = null;
  const detail = await client.read("/api/stories/" + code, (value) => {
    const parsed = parseStory(value, true);
    if (parsed.storyCode !== code) throw new SoloFormatError();
    return parsed;
  });
  const currentNotice = await client.read(
    "/api/member/privacy/policies/friendly",
    (value) => parseNotice(value, detail),
  );
  story = detail;
  notice = currentNotice;
  for (const [target, value] of [
    ["story-title", story.title],
    ["story-intro", story.intro],
    ["story-setting", story.setting],
    ["notice-version", notice.version],
    ["notice-body", notice.body],
    ["notice-contact", notice.contact],
  ])
    element(target).textContent = value;
  element("story-details").textContent =
    `게시 버전 ${story.publishedVersionNo} · 수정번호 ${story.playRev} · 제한 ${story.limitSec}초 · 고정 두 역할 단독 조사`;
}

function editorReport() {
  return parseReportBody({
    culpritCode: element("culprit").value || null,
    ...Object.fromEntries(
      Object.keys(labels).map((field) => [field, element(field).value]),
    ),
  });
}

/** 저장본 A와 편집 B를 분리하며 재조회는 미저장 B를 덮지 않는다. */
function paintReport(previous) {
  if (
    dirty &&
    previous &&
    (previous.rev !== saved.rev || previous.draftRev !== saved.draftRev)
  )
    stale = true;
  element("saved-revision").textContent =
    `저장본 방 ${saved.rev} · 초안 ${saved.draftRev}${stale ? " · 편집 입력은 보존 중입니다. 최신 저장본 확인 후 쓰기 기준을 직접 수락하세요." : ""}`;
  element("saved-report").textContent =
    "지목 인물: " +
    (saved.report.culpritCode ?? "미선택") +
    "\n" +
    Object.entries(labels)
      .map(([key, label]) => label + ": " + saved.report[key])
      .join("\n");
  if (!dirty) {
    element("culprit").replaceChildren(
      new Option("미선택", ""),
      ...materials.persons.map(
        (person) =>
          new Option(person.name + " (" + person.code + ")", person.code),
      ),
    );
    element("culprit").value = saved.report.culpritCode ?? "";
    for (const key of Object.keys(labels))
      element(key).value = saved.report[key];
  }
}

function paintMaterials() {
  element("basic-intro").textContent = materials.basic.intro ?? "";
  element("basic-setting").textContent = materials.basic.setting ?? "";
  for (const [field, body] of [
    ["roles", "brief"],
    ["persons", "publicText"],
    ["clues", "body"],
  ]) {
    element(field).replaceChildren();
    for (const item of materials[field]) {
      const panel = document.createElement("article");
      panel.className = "panel";
      paragraph(panel, item.name ?? item.title, "h4");
      paragraph(panel, item[body] ?? "");
      if (field === "clues") {
        paragraph(
          panel,
          item.read ? "읽음: " + item.readAt : "아직 읽음 표시하지 않음",
        );
        if (!item.read) {
          const controls = document.createElement("div");
          controls.className = "actions";
          action(
            controls,
            "clue-" + item.code,
            "이 단서 읽음 표시",
            () =>
              mutate(
                "POST",
                "/api/games/" + gameKey + "/clues/" + item.code + "/read",
                "PUBLIC_CLUE_READ",
                { expectedRev: state.rev },
              ),
            materials.rev !== state.rev,
          );
          panel.append(controls);
        }
      }
      element(field).append(panel);
    }
  }
  element("hints").replaceChildren();
  for (const level of [1, 2, 3]) {
    const hint = materials.openedHints.find((item) => item.level === level);
    if (hint) paragraph(element("hints"), "힌트 " + level + ": " + hint.body);
    else {
      const controls = document.createElement("div");
      controls.className = "actions";
      action(
        controls,
        "hint-" + level,
        "힌트 " + level + " 명시 열람",
        () =>
          mutate(
            "POST",
            "/api/games/" + gameKey + "/hints/" + level + "/open",
            "PUBLIC_HINT_OPEN",
            { expectedRev: state.rev },
          ),
        materials.hintsRemaining === 0 ||
          state.submissionState !== "NONE" ||
          materials.rev !== state.rev,
      );
      element("hints").append(controls);
    }
  }
  element("required-notices").replaceChildren();
  for (const notice of materials.requiredNotices)
    paragraph(element("required-notices"), notice.text);
}

function paintResult() {
  element("result-summary").textContent =
    `${result.outcome} · 최종 점수 ${result.finalScore ?? "미제공"} · 채점 횟수 ${result.teamAttempts} · 본인 힌트 ${result.ownHintsUsed ?? "미측정"}`;
  element("score-details").replaceChildren();
  if (result.scoreSummary) {
    paragraph(
      element("score-details"),
      `기본 ${result.scoreSummary.baseScore} · 오답 ${result.scoreSummary.wrongCount} · 감점 ${result.scoreSummary.penalty}`,
    );
    for (const item of result.scoreSummary.categories)
      paragraph(
        element("score-details"),
        `${item.category}: ${item.score}/${item.maxScore}`,
      );
  }
  element("report-results").replaceChildren();
  for (const report of result.reports)
    paragraph(
      element("report-results"),
      `제출 ${report.submitNo} · ${report.state}${report.state === "GRADED" ? " · 기본 " + report.baseScore + " · 성공 " + report.success : ""}`,
    );
  element("reveal").textContent = result.revealText ?? "";
}

/** 상태부터 읽고 현재 RUNNING 자료 또는 실제 ENDED 기록만 채택한다. */
async function loadGame() {
  if (!gameKey) throw new SoloFormatError();
  const key = gameKey;
  const next = await client.read("/api/games/" + key, (value) =>
    parseState(value, key),
  );
  let nextMaterials = null,
    nextReport = null,
    nextResult = null;
  if (next.state === "RUNNING" && next.submissionState === "NONE") {
    nextMaterials = await client.read(
      "/api/games/" + key + "/materials",
      (value) => parseMaterials(value, key),
    );
    nextReport = await client.read("/api/games/" + key + "/report", (value) =>
      parseReport(value, key),
    );
    if (
      nextMaterials.rev !== next.rev ||
      nextReport.rev !== next.rev ||
      nextReport.draftRev !== next.draftRev
    )
      throw new SoloFormatError();
  } else if (next.state === "ENDED")
    nextResult = await client.read("/api/games/" + key + "/result", (value) =>
      parseResult(value, key),
    );
  if (gameKey !== key || !session.current()) throw new WebSessionBlocked();
  if (!state || state.rev !== next.rev || state.draftRev !== next.draftRev) {
    element("submit-consent").checked = false;
    element("forfeit-consent").checked = false;
  }
  const previous = saved;
  state = next;
  materials = nextMaterials;
  saved = nextReport;
  result = nextResult;
  if (materials) {
    paintMaterials();
    if (saved) paintReport(previous);
  }
  if (state.state !== "RUNNING" || state.submissionState === "PENDING") {
    for (const field of Object.keys(labels)) {
      element(field).value = "";
      element(field).defaultValue = "";
    }
    element("culprit").value = "";
    dirty = stale = false;
    for (const field of [
      "roles",
      "persons",
      "clues",
      "hints",
      "saved-report",
      "basic-intro",
      "basic-setting",
      "required-notices",
    ])
      element(field).replaceChildren();
  }
  if (result) paintResult();
  schedule();
}

/** 접속 증명과 판정 조회는 현재 문서에서만 예약하며 오류 이후 반복하지 않는다. */
function schedule() {
  clearInterval(heartbeatTimer);
  clearTimeout(pollTimer);
  clearTimeout(deadlineTimer);
  heartbeatTimer = pollTimer = deadlineTimer = null;
  if (["WAITING", "RUNNING"].includes(state?.state)) {
    heartbeatTimer = setInterval(async () => {
      if (
        heartbeatBusy ||
        busy ||
        !session.current() ||
        document.visibilityState === "hidden"
      )
        return;
      heartbeatBusy = true;
      try {
        await client.heartbeat(gameKey);
      } catch (failure) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
        element("error").textContent = message(failure);
        element("error").hidden = false;
      } finally {
        heartbeatBusy = false;
      }
    }, 15000);
  }
  if (state?.submissionState === "PENDING")
    pollTimer = setTimeout(() => {
      if (!busy && session.current()) void run(loadGame);
    }, 5000);
  if (materials && state?.submissionState !== "PENDING") {
    const remaining =
      Date.parse(materials.playDeadline) - Date.parse(state.serverTime);
    deadlineTimer = setTimeout(
      () => {
        materials = saved = null;
        dirty = false;
        for (const id of [
          "roles",
          "persons",
          "clues",
          "hints",
          "required-notices",
          "saved-report",
          "basic-intro",
          "basic-setting",
        ])
          element(id).replaceChildren();
        for (const key of Object.keys(labels)) {
          element(key).value = "";
          element(key).defaultValue = "";
        }
        element("submit-consent").checked = false;
        render();
        element("status").textContent =
          "표시 자료의 기한이 지났습니다. 종료 여부는 서버 상태를 다시 조회해 확인하세요.";
      },
      Math.max(0, Math.min(remaining, 2147483647)),
    );
  }
}

/** 확정 영수증 뒤 조회 실패를 다시 명령 유실로 바꾸지 않는다. */
async function mutate(method, path, actionName, body) {
  const receipt = await client.send(
    method,
    path,
    actionName,
    body,
    path === "/api/games" ? undefined : gameKey,
  );
  state = receipt.current;
  gameKey = state.gameKey;
  story = notice = null;
  element("create-consent").checked = false;
  history.replaceState(null, "", "/play#" + gameKey);
  if (actionName === "PUBLIC_REPORT_EDIT") {
    dirty = stale = false;
  }
  element("submit-consent").checked = element("forfeit-consent").checked =
    false;
  await loadGame();
}

/** 연결 복구는 쿠키 회전만 하고 게임 명령은 자동 재전송하지 않는다. */
async function connect() {
  if (!(await session.restore())) {
    element("status").textContent =
      "로그인이 필요합니다. 회원 계정 화면에서 로그인해 주세요.";
    return;
  }
  if (gameKey) await loadGame();
  else await loadCatalog();
}

for (const [id, handler] of [
  ["connect", connect],
  ["catalog-button", () => loadCatalog()],
  ["next", () => loadCatalog(true)],
  ["refresh", loadGame],
  [
    "current-button",
    async () => {
      const current = await client.read("/api/games/current", (value) => {
        if (
          !value ||
          Object.keys(value).length !== 2 ||
          !UUID4.test(value.requestId) ||
          !Object.hasOwn(value, "game")
        )
          throw new SoloFormatError();
        return value.game === null ? null : parseState(value.game);
      });
      if (!current) {
        element("status").textContent = "점유 중인 단독 방이 없습니다.";
        return;
      }
      gameKey = current.gameKey;
      history.replaceState(null, "", "/play#" + gameKey);
      await loadGame();
    },
  ],
  [
    "start",
    () => {
      if (!state?.canStart || state.needsRefresh) throw new SoloFormatError();
      return mutate(
        "POST",
        "/api/games/" + gameKey + "/start",
        "PUBLIC_START",
        {
          expectedRev: state.rev,
          expectedPublishedVersionNo: state.publishedVersionNo,
          expectedPlayRev: state.playRev,
        },
      );
    },
  ],
  [
    "create",
    () => {
      if (!story || !notice || !element("create-consent").checked)
        throw new SoloFormatError();
      return mutate("POST", "/api/games", "PUBLIC_CREATE", {
        mode: "SOLO",
        storyCode: story.storyCode,
        expectedPublishedVersionNo: story.publishedVersionNo,
        expectedPlayRev: story.playRev,
        policyCode: notice.policyCode,
        noticeHash: notice.noticeHash,
      });
    },
  ],
  [
    "submit",
    () => {
      if (!sameSaved() || dirty || stale || !element("submit-consent").checked)
        throw new SoloFormatError();
      return mutate(
        "POST",
        "/api/games/" + gameKey + "/report/submit",
        "PUBLIC_REPORT_SUBMIT",
        { expectedRev: saved.rev, expectedDraftRev: saved.draftRev },
      );
    },
  ],
  [
    "forfeit",
    () => {
      if (
        !element("forfeit-consent").checked ||
        state?.state !== "RUNNING" ||
        state.submissionState !== "NONE"
      )
        throw new SoloFormatError();
      return mutate(
        "POST",
        "/api/games/" + gameKey + "/forfeit",
        "PUBLIC_FORFEIT",
        { expectedRev: state.rev },
      );
    },
  ],
  [
    "accept-latest",
    () => {
      if (!saved || !dirty || !stale || !sameSaved())
        throw new SoloFormatError();
      stale = false;
      element("submit-consent").checked = false;
    },
  ],
  [
    "revert",
    () => {
      if (!saved) throw new SoloFormatError();
      dirty = stale = false;
      element("submit-consent").checked = false;
      paintReport(null);
    },
  ],
  [
    "replay",
    async () => {
      const receipt = await client.replay();
      state = receipt.current;
      gameKey = state.gameKey;
      dirty = stale = false;
      history.replaceState(null, "", "/play#" + gameKey);
      await loadGame();
    },
  ],
])
  element(id).addEventListener("click", () => void run(handler));
for (const id of ["create-consent", "submit-consent", "forfeit-consent"])
  element(id).addEventListener("change", render);
for (const input of document.querySelectorAll(
  "#report-form textarea, #report-form select",
))
  input.addEventListener("input", () => {
    dirty = true;
    element("submit-consent").checked = false;
    render();
  });
element("report-form").addEventListener("submit", (event) => {
  event.preventDefault();
  void run(() => {
    if (!sameSaved() || stale || !dirty) throw new SoloFormatError();
    return mutate(
      "PATCH",
      "/api/games/" + gameKey + "/report",
      "PUBLIC_REPORT_EDIT",
      {
        expectedRev: saved.rev,
        expectedDraftRev: saved.draftRev,
        report: editorReport(),
      },
    );
  });
});
window.addEventListener("beforeunload", (event) => {
  if (dirty || client?.pending()) {
    event.preventDefault();
    event.returnValue = "";
  }
});
window.addEventListener("hashchange", () => {
  const next = location.hash.slice(1);
  if (!UUID4.test(next) || next === gameKey || !client) return;
  client.clear();
  gameKey = next;
  if (!busy && session.current()) void run(loadGame);
});
try {
  session = createWebSession();
  client = createSoloClient(session, purge);
  await run(connect);
} catch (failure) {
  purge();
  element("error").textContent = message(failure);
  element("error").hidden = false;
}
render();
