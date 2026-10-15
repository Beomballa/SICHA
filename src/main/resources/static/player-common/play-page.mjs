import { createWebSession, WebSessionBlocked } from "./web-session.mjs";
import { createGameClient } from "./game-client.mjs";
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
  parseCoopReport,
  parseInvitePreview,
  SoloFormatError,
} from "./game-model.mjs";

const element = (id) => document.getElementById(id);
const labels = {
  method: "방법",
  time: "시간",
  motive: "동기",
  evidence: "근거",
};
// 검증된 계약의 고정 값만 표시 이름으로 바꾸며 서버 필드와 명령은 유지한다.
const modeLabels = { SOLO: "단독 조사", COOP: "협동 조사" };
const stateLabels = {
  WAITING: "조사 시작 대기",
  RUNNING: "조사 중",
  ENDED: "조사 종료",
  CANCELLED: "방 취소",
  EXPIRED: "대기 기한 만료",
};
const submissionLabels = {
  NONE: "처리 중인 제출 없음",
  PROPOSED: "공동 보고서 수락 대기",
  PENDING: "제출 완료 · 채점 대기",
};
const reportStateLabels = { GRADED: "채점 완료", UNGRADABLE: "채점 불가" };
const outcomeLabels = {
  SUCCESS: "사건 해결",
  TIME_LIMIT: "조사 시간 종료",
  ATTEMPTS_EXHAUSTED: "제출 기회 소진",
  FORFEIT: "조사 포기",
  SYSTEM_ERROR: "시스템 오류로 종료",
  ACCOUNT_DELETED: "계정 삭제로 종료",
};
const operationLabels = {
  PUBLIC_CREATE: "조사 방 만들기",
  PUBLIC_START: "조사 시작",
  PUBLIC_CLUE_READ: "단서 읽음 표시",
  PUBLIC_HINT_OPEN: "힌트 열기",
  PUBLIC_REPORT_EDIT: "보고서 저장",
  PUBLIC_REPORT_SUBMIT: "보고서 제출",
  PUBLIC_REPORT_PROPOSE: "공동 보고서 제안",
  PUBLIC_REPORT_ACCEPT: "공동 보고서 수락",
  PUBLIC_REPORT_REJECT: "공동 보고서 거절",
  PUBLIC_REPORT_WITHDRAW: "공동 보고서 제안 철회",
  PUBLIC_FORFEIT: "조사 포기",
  PUBLIC_JOIN: "협동 초대 참가",
  PUBLIC_INVITE: "협동 초대 발급",
  PUBLIC_READY: "협동 준비 상태 변경",
  PUBLIC_SELECTION_REFRESH: "사건 선택 갱신",
  PUBLIC_LEAVE: "대기실 나가기",
};
let session, client;
let invitation = null,
  bootstrapFailed = false;
try {
  invitation = window.takeCoopInvitation?.() ?? null;
} catch (_) {
  bootstrapFailed = true;
}
let gameMode = invitation ? "COOP" : "SOLO";
let invitePreview = null,
  inviteNotice = null,
  issuedLink = null,
  issuedUntil = null;
let proposalIdentity = null,
  authHandoff = null;
let inviteTimer = null;
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
let playTask = "investigation";
const parallelTasks = window.matchMedia("(min-width: 900px)");

/** 로컬 작업 선택은 외곽 표시만 바꾸며 서버 권위의 section.hidden·입력·명령은 건드리지 않는다. */
function renderTasks() {
  const focus = document.activeElement;
  const available = {
    investigation: !element("investigation").hidden,
    report: !element("report-section").hidden,
  };
  const any = available.investigation || available.report;
  if (any && !available[playTask])
    playTask = available.investigation ? "investigation" : "report";
  const parallel =
    parallelTasks.matches && available.investigation && available.report;
  element("play-workspace").hidden = !any;
  element("play-workspace").dataset.parallel = String(parallel);
  element("play-task-nav").hidden = !any;
  element("play-task-summary").hidden = !available.report;
  element("required-notices").hidden = !available.investigation;
  element("report-edit-state").textContent = available.report
    ? stale
      ? "미저장 입력 보존 중 · 최신 저장본과 쓰기 기준 확인이 필요합니다."
      : dirty
        ? "미저장 입력이 있습니다. 작업 전환은 저장이나 제출이 아닙니다."
        : "서버 저장본과 편집 입력을 구분해 확인하세요. 작업 전환은 동의가 아닙니다."
    : "";
  for (const button of document.querySelectorAll("[data-play-task]")) {
    const task = button.dataset.playTask;
    button.hidden = !available[task];
    button.disabled = !available[task];
    button.setAttribute("aria-pressed", String(task === playTask));
    element(task + "-area").hidden =
      !available[task] || (!parallel && task !== playTask);
  }
  if (focus?.closest("[hidden]")) {
    const button = document.querySelector(`[data-play-task="${playTask}"]`);
    (any ? button : element("main")).focus();
  }
}

for (const button of document.querySelectorAll("[data-play-task]"))
  button.addEventListener("click", () => {
    playTask = button.dataset.playTask;
    renderTasks();
    element(
      playTask === "report" ? "report-title" : "investigation-title",
    ).focus();
  });
parallelTasks.addEventListener("change", renderTasks);

/** 원문·DOM 입력·기본값·동의·타이머를 함께 폐기한다. 서버 상태를 추정하지 않는다. */
function purge(reason = "SCREEN_CHANGED") {
  const handoff =
    (authHandoff === "RESTORE" && reason === "REFRESH") ||
    (authHandoff === "LOGIN" && ["LOGOUT", "LOGIN"].includes(reason));
  if (!handoff) invitation = null;
  invitePreview =
    inviteNotice =
    issuedLink =
    issuedUntil =
    proposalIdentity =
      null;
  clearTimeout(inviteTimer);
  inviteTimer = null;
  clearInterval(heartbeatTimer);
  clearTimeout(pollTimer);
  clearTimeout(deadlineTimer);
  heartbeatTimer = pollTimer = deadlineTimer = null;
  state = story = notice = materials = saved = result = null;
  cursor = null;
  dirty = stale = false;
  playTask = "investigation";
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
    "game-revision",
    "game-counts",
    "deadline",
    "basic-intro",
    "basic-setting",
    "result-summary",
    "saved-revision",
    "participants",
    "proposal-revision",
    "proposal-report",
    "invite-description",
    "invite-notice",
    "invite-expiry",
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
    saved.submissionState === state.submissionState &&
    (state.submissionState === "NONE" ||
      (gameMode === "COOP" && state.submissionState === "PROPOSED"))
  );
}

/** 표시와 버튼 권한은 확인된 서버 상태·저장본·원본 명령만 사용한다. */
function render() {
  const authenticated = !!session?.current();
  const pending = client?.pending();
  const running = state?.state === "RUNNING";
  const coop = gameMode === "COOP";
  element("mode").value = gameMode;
  element("mode").disabled = busy || !!state || !!pending;
  element("create").textContent =
    "동의한 " + modeLabels[gameMode] + " 방 만들기";
  element("start").textContent = coop
    ? "두 계정 협동 조사 시작"
    : "단독 조사 시작";
  element("roles-title").textContent = coop
    ? "내 고정 역할 자료"
    : "고정된 두 역할";
  for (const button of document.querySelectorAll(
    "button:not([data-play-task])",
  ))
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
    !running ||
    !saved ||
    !materials ||
    !["NONE", "PROPOSED"].includes(state.submissionState);
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
    !(!coop || state?.submissionState === "NONE") ||
    dirty ||
    stale ||
    !complete ||
    !sameSaved() ||
    !element("submit-consent").checked;
  element("submit").textContent = coop
    ? "동의한 저장본 고정 제안"
    : "동의한 서버 저장본 제출";
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
  element("intent-name").textContent = pending
    ? operationLabels[pending.action]
    : "";
  renderCoop(authenticated, pending);
  if (state) {
    element("game-title").textContent = state.title;
    element("game-state").textContent =
      `${modeLabels[state.mode]} · ${stateLabels[state.state]}`;
    element("game-revision").textContent =
      `방 수정번호 ${state.rev} · 초안 수정번호 ${state.draftRev}`;
    element("game-counts").textContent =
      `${submissionLabels[state.submissionState]} · 남은 시도 ${state.attemptsRemaining ?? "미제공"} · 남은 힌트 ${state.hintsRemaining ?? "미제공"}`;
    element("deadline").textContent = state.playDeadline
      ? "서버 마감 시각: " + state.playDeadline
      : "대기실 서버 만료: " + state.lobbyExpiresAt;
  }
  renderTasks();
}

/** 협동 권한은 서버의 고정 역할/제안 권한만 따르고 초대 비밀을 조회로 복원하지 않는다. */
function renderCoop(authenticated, pending) {
  const waiting = state?.mode === "COOP" && state.state === "WAITING";
  const proposal = saved?.proposal;
  element("lobby").hidden = !waiting;
  element("invite-login").hidden =
    !invitation || invitation.invalid || authenticated;
  element("invite-preview").hidden =
    !invitation || invitation.invalid || !authenticated;
  element("invite-login-button").disabled =
    busy || !invitation || invitation.invalid;
  element("invite-email").disabled = element("invite-password").disabled = busy;
  element("join").disabled ||=
    !invitePreview || !inviteNotice || !element("join-consent").checked;
  element("join-consent").disabled =
    busy || !!pending || !invitePreview || !inviteNotice;
  element("issue-invite").disabled ||=
    !waiting || !state.host || state.partner.joined || state.needsRefresh;
  element("ready").disabled ||=
    !waiting || !state.canReady || !element("ready-consent").checked;
  element("unready").disabled ||= !waiting || !state.self.ready;
  element("leave").disabled ||= !waiting || !element("leave-consent").checked;
  element("selection-refresh").disabled ||=
    !waiting || !state.host || !state.needsRefresh;
  element("issued").hidden = !issuedLink;
  element("copy-invite").disabled ||= !issuedLink;
  element("issued-link").value = issuedLink ?? "";
  element("invite-expiry").textContent = issuedUntil
    ? "초대 만료: " + issuedUntil
    : "";
  if (waiting)
    element("participants").textContent =
      `내 자리 ${state.self.slot} · ${state.host ? "방장" : "참가자"} · 내 준비 ${state.self.effectiveReady ? "완료" : "대기"} · 상대 ${state.partner.joined ? "참가함" : "참가 대기"} · 상대 준비 ${state.partner.effectiveReady ? "완료" : "대기"} · 상대 접속 ${state.partner.online ? "연결됨" : "연결 안 됨"}`;
  element("proposal-section").hidden = !proposal || state?.mode !== "COOP";
  if (proposal) {
    element("proposal-revision").textContent =
      `고정 제안 ${proposal.reportKey} · 원본 초안 ${proposal.sourceRev} · 방 ${state.rev}`;
    element("proposal-report").textContent =
      "지목 인물: " +
      proposal.report.culpritCode +
      "\n" +
      Object.entries(labels)
        .map(([key, label]) => label + ": " + proposal.report[key])
        .join("\n");
  } else {
    element("proposal-report").replaceChildren();
    element("proposal-revision").replaceChildren();
  }
  const agreed = element("proposal-consent").checked && !dirty && !stale;
  element("proposal-consent").disabled =
    busy || !!pending || !proposal || dirty || stale;
  element("accept-proposal").disabled ||= !proposal?.canAccept || !agreed;
  element("reject-proposal").disabled ||= !proposal?.canReject || !agreed;
  element("withdraw-proposal").disabled ||= !proposal?.canWithdraw || !agreed;
}

/** 초대와 별도 현행 고지를 같은 문서·자격에서 읽고 일치하는 경우에만 동의한다. */
async function inspectInvitation() {
  if (!invitation || invitation.invalid) throw new SoloFormatError();
  const candidate = invitation;
  const preview = await client.inspect(
    "/api/games/invitations/inspect",
    candidate,
    (value) => parseInvitePreview(value, candidate.inviteKey),
  );
  const policy = await client.read(
    "/api/member/privacy/policies/friendly",
    (value) => parseNotice(value, preview),
  );
  if (invitation !== candidate) throw new WebSessionBlocked();
  invitePreview = preview;
  inviteNotice = policy;
  element("join-consent").checked = false;
  element("invite-description").textContent =
    `${preview.title} · 난이도 ${preview.difficulty} · 초대 만료 ${preview.expiresAt}`;
  element("invite-notice").textContent =
    policy.body + "\n문의: " + policy.contact;
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
    `게시 버전 ${story.publishedVersionNo} · 수정번호 ${story.playRev} · 제한 ${story.limitSec}초`;
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
    `${outcomeLabels[result.outcome]} · 최종 점수 ${result.finalScore ?? "미제공"} · 채점 횟수 ${result.teamAttempts} · 본인 힌트 ${result.ownHintsUsed ?? "미측정"}`;
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
      `제출 ${report.submitNo} · ${reportStateLabels[report.state]}${report.state === "GRADED" ? " · 기본 " + report.baseScore + " · " + (report.success ? "사건 해결" : "해결하지 못함") : ""}`,
    );
  element("reveal").textContent = result.revealText ?? "";
}

/** 상태부터 읽고 현재 RUNNING 자료 또는 실제 ENDED 기록만 채택한다. */
async function loadGame() {
  if (!gameKey) throw new SoloFormatError();
  const key = gameKey;
  const next = await client.read("/api/games/" + key, (value) =>
    parseState(value, key, value.mode),
  );
  gameMode = next.mode;
  let nextMaterials = null,
    nextReport = null,
    nextResult = null;
  if (next.state === "RUNNING" && next.submissionState !== "PENDING") {
    nextMaterials = await client.read(
      "/api/games/" + key + "/materials",
      (value) => parseMaterials(value, key, next.mode, next.self.roleCode),
    );
    nextReport = await client.read("/api/games/" + key + "/report", (value) => {
      if (
        next.mode === "COOP" &&
        value?.gameKey === key &&
        ((typeof value.draftRev === "string" &&
          /^(0|[1-9][0-9]{0,18})$/.test(value.draftRev) &&
          value.draftRev !== next.draftRev) ||
          (["NONE", "PROPOSED", "PENDING"].includes(value.submissionState) &&
            value.submissionState !== next.submissionState))
      )
        throw new WebApiError(409, "EDIT_CONFLICT");
      return next.mode === "SOLO"
        ? parseReport(value, key)
        : { ...parseCoopReport(value, next), rev: next.rev };
    });
    if (
      nextMaterials.rev !== next.rev ||
      nextReport.rev !== next.rev ||
      nextReport.draftRev !== next.draftRev
    )
      throw new WebApiError(409, "EDIT_CONFLICT");
    if (next.mode === "COOP") {
      const confirmed = await client.read("/api/games/" + key, (value) =>
        parseState(value, key, "COOP"),
      );
      if (confirmed.rev !== next.rev || confirmed.draftRev !== next.draftRev)
        throw new WebApiError(409, "EDIT_CONFLICT");
    }
  } else if (next.state === "ENDED")
    nextResult = await client.read("/api/games/" + key + "/result", (value) =>
      parseResult(value, key, next.mode),
    );
  if (gameKey !== key || !session.current()) throw new WebSessionBlocked();
  if (!state || state.rev !== next.rev || state.draftRev !== next.draftRev) {
    element("submit-consent").checked = false;
    element("forfeit-consent").checked = false;
    element("ready-consent").checked = element("leave-consent").checked = false;
  }
  const fingerprint = nextReport?.proposal
    ? JSON.stringify([
        key,
        next.rev,
        nextReport.draftRev,
        next.self.slot,
        nextReport.proposal,
      ])
    : null;
  if (fingerprint !== proposalIdentity)
    element("proposal-consent").checked = false;
  proposalIdentity = fingerprint;
  const previous = saved;
  state = next;
  materials = nextMaterials;
  saved = nextReport;
  result = nextResult;
  if (
    issuedLink &&
    (state.state !== "WAITING" ||
      state.partner.joined ||
      Date.parse(issuedUntil) <= Date.now())
  ) {
    issuedLink = issuedUntil = null;
    element("issued-link").value = "";
  }
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
  if (
    state?.submissionState === "PENDING" ||
    (gameMode === "COOP" && ["WAITING", "RUNNING"].includes(state?.state))
  )
    pollTimer = setTimeout(
      () => {
        if (!busy && session.current()) void run(loadGame);
      },
      gameMode === "COOP" ? 3000 : 5000,
    );
  if (materials && state?.submissionState !== "PENDING") {
    const remaining = Math.min(
      Date.parse(materials.playDeadline) - Date.parse(state.serverTime),
      Date.parse(materials.playDeadline) - Date.now(),
    );
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
  await adoptReceipt(receipt);
}

/** 확정/재전송 영수증을 동일하게 채택하며 leave의 null과 비밀 없는 초대 replay를 처리한다. */
async function adoptReceipt(receipt) {
  const actionName = receipt.action;
  state = receipt.current;
  if (state === null) {
    client.clear();
    gameKey = null;
    gameMode = "SOLO";
    history.replaceState(null, "", "/play");
    await loadCatalog();
    return;
  }
  gameKey = state.gameKey;
  gameMode = state.mode;
  if (actionName === "PUBLIC_JOIN")
    invitation = invitePreview = inviteNotice = null;
  if (actionName === "PUBLIC_INVITE") {
    issuedLink = receipt.inviteSecret
      ? location.origin +
        "/coop-invite#inviteKey=" +
        receipt.inviteKey +
        "&inviteSecret=" +
        receipt.inviteSecret
      : null;
    issuedUntil = receipt.expiresAt ?? null;
    element("issued-link").value = issuedLink ?? "";
    element("reissue-notice").hidden = !receipt.requiresReissue;
  }
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
  authHandoff = "RESTORE";
  let restored;
  try {
    restored = await session.restore();
  } finally {
    authHandoff = null;
  }
  if (!restored) {
    element("status").textContent =
      "로그인이 필요합니다. 회원 계정 화면에서 로그인해 주세요.";
    return;
  }
  if (invitation) {
    if (invitation.invalid) throw new SoloFormatError();
    await inspectInvitation();
  } else if (gameKey) await loadGame();
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
        return value.game === null
          ? null
          : parseState(value.game, undefined, value.game.mode);
      });
      if (!current) {
        element("status").textContent = "점유 중인 단독 방이 없습니다.";
        return;
      }
      gameKey = current.gameKey;
      gameMode = current.mode;
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
        mode: gameMode,
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
        "/api/games/" +
          gameKey +
          (gameMode === "COOP" ? "/report/proposals" : "/report/submit"),
        gameMode === "COOP" ? "PUBLIC_REPORT_PROPOSE" : "PUBLIC_REPORT_SUBMIT",
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
      await adoptReceipt(receipt);
    },
  ],
])
  element(id).addEventListener("click", () => void run(handler));
for (const id of ["create-consent", "submit-consent", "forfeit-consent"])
  element(id).addEventListener("change", render);
for (const id of [
  "join-consent",
  "ready-consent",
  "leave-consent",
  "proposal-consent",
])
  element(id).addEventListener("change", render);
element("mode").addEventListener("change", () => {
  gameMode = element("mode").value;
  element("create-consent").checked = false;
  render();
});
for (const [id, handler] of [
  ["inspect-invite", inspectInvitation],
  [
    "join",
    async () => {
      if (
        !invitation ||
        !invitePreview ||
        !inviteNotice ||
        !element("join-consent").checked
      )
        throw new SoloFormatError();
      const receipt = await client.send(
        "POST",
        "/api/games/invitations/join",
        "PUBLIC_JOIN",
        {
          ...invitation,
          policyCode: invitePreview.policyCode,
          noticeHash: invitePreview.noticeHash,
        },
      );
      await adoptReceipt(receipt);
    },
  ],
  [
    "issue-invite",
    async () => {
      issuedLink = issuedUntil = null;
      element("issued-link").value = "";
      clearTimeout(inviteTimer);
      await mutate(
        "POST",
        "/api/games/" + gameKey + "/invitations",
        "PUBLIC_INVITE",
        { expectedRev: state.rev },
      );
      clearTimeout(inviteTimer);
      if (issuedUntil)
        inviteTimer = setTimeout(
          () => {
            issuedLink = issuedUntil = null;
            element("issued-link").value = "";
            render();
          },
          Math.max(0, Date.parse(issuedUntil) - Date.now()),
        );
    },
  ],
  [
    "copy-invite",
    async () => {
      if (!issuedLink || !navigator.clipboard?.writeText)
        throw new WebSessionBlocked();
      await navigator.clipboard.writeText(issuedLink);
    },
  ],
  [
    "ready",
    () => {
      if (!state?.canReady || !element("ready-consent").checked)
        throw new SoloFormatError();
      return mutate(
        "POST",
        "/api/games/" + gameKey + "/ready",
        "PUBLIC_READY",
        {
          expectedRev: state.rev,
          expectedPublishedVersionNo: state.publishedVersionNo,
          expectedPlayRev: state.playRev,
          ready: true,
        },
      );
    },
  ],
  [
    "unready",
    () =>
      mutate("POST", "/api/games/" + gameKey + "/ready", "PUBLIC_READY", {
        expectedRev: state.rev,
        expectedPublishedVersionNo: state.publishedVersionNo,
        expectedPlayRev: state.playRev,
        ready: false,
      }),
  ],
  [
    "selection-refresh",
    () =>
      mutate(
        "POST",
        "/api/games/" + gameKey + "/selection/refresh",
        "PUBLIC_SELECTION_REFRESH",
        { expectedRev: state.rev },
      ),
  ],
  [
    "leave",
    () => {
      if (!element("leave-consent").checked) throw new SoloFormatError();
      return mutate(
        "POST",
        "/api/games/" + gameKey + "/leave",
        "PUBLIC_LEAVE",
        { expectedRev: state.rev },
      );
    },
  ],
  ...[
    ["accept-proposal", "ACCEPT", "canAccept"],
    ["reject-proposal", "REJECT", "canReject"],
    ["withdraw-proposal", "WITHDRAW", "canWithdraw"],
  ].map(([id, decision, permission]) => [
    id,
    () => {
      const proposal = saved?.proposal;
      if (
        !proposal?.[permission] ||
        !element("proposal-consent").checked ||
        dirty ||
        stale
      )
        throw new SoloFormatError();
      return mutate(
        "POST",
        "/api/games/" +
          gameKey +
          "/report/proposals/" +
          proposal.reportKey +
          "/respond",
        "PUBLIC_REPORT_" + decision,
        { expectedRev: state.rev, decision },
      );
    },
  ]),
])
  element(id).addEventListener("click", () => void run(handler));
element("invite-login-form").addEventListener("submit", (event) => {
  event.preventDefault();
  void run(async () => {
    if (!invitation || invitation.invalid) throw new SoloFormatError();
    const email = element("invite-email").value,
      password = element("invite-password").value;
    element("invite-password").value = "";
    authHandoff = "LOGIN";
    try {
      await session.login(email, password);
    } finally {
      authHandoff = null;
    }
    if (!invitation) throw new WebSessionBlocked();
    await inspectInvitation();
  });
});
for (const input of document.querySelectorAll(
  "#report-form textarea, #report-form select",
))
  input.addEventListener("input", () => {
    dirty = true;
    element("submit-consent").checked = false;
    element("proposal-consent").checked = false;
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
  if (bootstrapFailed) throw new WebSessionBlocked();
  session = createWebSession();
  client = createGameClient(
    session,
    purge,
    () => crypto.randomUUID(),
    () => gameMode,
  );
  await run(connect);
} catch (failure) {
  purge();
  element("error").textContent = message(failure);
  element("error").hidden = false;
}
render();
