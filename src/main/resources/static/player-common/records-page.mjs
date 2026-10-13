import { createWebSession, WebSessionBlocked } from "./web-session.mjs";
import { createGameClient } from "./game-client.mjs";
import { UUID4, WebApiError } from "./web-api.mjs";
import {
  parseRecordPage,
  parseRecord,
  parseRecordReport,
  SoloFormatError,
} from "./game-model.mjs";

const element = (id) => document.getElementById(id);
let session, reader;
let items = [],
  cursor = null,
  current = null,
  loaded = false,
  busy = false;
let selectedKey = UUID4.test(location.hash.slice(1))
  ? location.hash.slice(1)
  : null;
const consumed = new Set();

/** 읽은 원문과 최소 요약도 계정/배경/권한 경계에서 함께 폐기한다. */
function purge() {
  items = [];
  cursor = current = null;
  loaded = false;
  consumed.clear();
  for (const id of [
    "records",
    "detail-title",
    "summary",
    "timing",
    "score",
    "reports",
    "reveal",
    "report-state",
    "report-body",
    "report-basis",
  ])
    element(id).replaceChildren();
  element("raw").hidden = true;
  render();
}

/** 원문은 현재 문서에서만 표시하고 닫기/선택 변경 즉시 텍스트 노드를 제거한다. */
function closeRaw() {
  for (const id of ["report-state", "report-body", "report-basis"])
    element(id).replaceChildren();
  element("raw").hidden = true;
}

/** 모든 기록·보고서 문자열을 HTML이 아닌 텍스트로 표시한다. */
function paragraph(parent, value, tag = "p") {
  const node = document.createElement(tag);
  node.textContent = value;
  if (tag === "p") node.className = "notice-body";
  parent.append(node);
  return node;
}

function errorText(failure) {
  if (
    failure instanceof WebSessionBlocked ||
    (failure instanceof WebApiError && [401, 403].includes(failure.status))
  )
    return "회원 자격이 변경되었습니다. 이전 기록과 원문을 비웠습니다. 회원 연결을 다시 확인해 주세요.";
  if (failure instanceof WebApiError && [404, 410].includes(failure.status))
    return "현재 본인에게 허용된 기록/원문이 아닙니다. 이전 연결로 파기된 원문을 복구하지 않습니다.";
  if (failure instanceof SoloFormatError)
    return "기록 응답의 키나 형식을 확인할 수 없어 표시하지 않습니다.";
  return "기록 조회를 완료하지 못했습니다. 정상 빈 목록이나 파기 완료로 대신 표시하지 않습니다. 직접 다시 조회해 주세요.";
}

function render() {
  const authenticated = !!session?.current();
  for (const button of document.querySelectorAll("button"))
    button.disabled = busy || !authenticated;
  element("connect").disabled = busy || !session;
  element("next").hidden = cursor === null;
  element("empty").hidden = !loaded || items.length !== 0;
  element("detail").hidden = current === null;
  element("purged").hidden = current?.rawState !== "PURGED";
  element("normal").hidden = current?.rawState !== "NORMAL";
  element("refresh").disabled ||= !selectedKey;
  element("main").setAttribute("aria-busy", String(busy));
}

/** 조회 실패나 화면 교체는 성공으로 취급하지 않으며 동일 cursor를 자동 재사용하지 않는다. */
async function run(work) {
  if (busy || !reader) return;
  const focus = document.activeElement?.id;
  busy = true;
  element("error").hidden = true;
  element("status").textContent = "서버에서 본인 기록을 확인하는 중입니다.";
  render();
  try {
    await work();
    element("status").textContent = session.current()
      ? "서버에서 허용한 조회 결과를 확인했습니다."
      : "로그인이 필요합니다. 회원 계정에서 로그인해 주세요.";
  } catch (failure) {
    if (failure instanceof SoloFormatError) purge();
    closeRaw();
    element("error").textContent = errorText(failure);
    element("error").hidden = false;
    element("status").textContent = "조회 완료를 확인하지 못했습니다.";
  } finally {
    busy = false;
    render();
    const node = focus && element(focus);
    if (node && !node.disabled && node.getClientRects().length) node.focus();
  }
}

function paintList() {
  element("records").replaceChildren();
  for (const record of items) {
    const panel = document.createElement("article");
    panel.className = "panel";
    paragraph(panel, record.story.title, "h3");
    paragraph(
      panel,
      `${record.outcome} · 최종 ${record.finalScore ?? "미제공"} · 내 역할 ${record.selfRole.name} · ${record.durationSec}초`,
    );
    paragraph(
      panel,
      record.rawState === "PURGED"
        ? "공동 원문 파기 · 최소 요약"
        : `${record.mode} · 본인 힌트 ${record.ownHintsUsed ?? "미측정"}`,
    );
    const actions = document.createElement("div");
    actions.className = "actions";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary-action";
    button.id = "record-" + record.recordKey;
    button.textContent = "이 기록 명시 조회";
    button.addEventListener(
      "click",
      () => void run(() => selectRecord(record.recordKey)),
    );
    actions.append(button);
    panel.append(actions);
    element("records").append(panel);
  }
}

/** cursor는 회원/크기에 결속된 서버 원문으로만 전달하고 실패한 cursor는 다시 소비하지 않는다. */
async function list(more = false) {
  if (!more) {
    reader.clear();
    selectedKey = null;
    history.replaceState(null, "", "/records");
  }
  const seek = more ? cursor : null;
  if (more && (seek === null || consumed.has(seek)))
    throw new SoloFormatError();
  if (seek !== null) consumed.add(seek);
  cursor = null;
  const page = await reader.read(
    "/api/member/records?size=20" +
      (seek === null ? "" : "&cursor=" + encodeURIComponent(seek)),
    parseRecordPage,
  );
  if (
    page.nextCursor !== null &&
    (page.nextCursor === seek || consumed.has(page.nextCursor))
  )
    throw new SoloFormatError();
  const keys = new Set(items.map((item) => item.recordKey));
  if (page.items.some((item) => keys.has(item.recordKey)))
    throw new SoloFormatError();
  items = more ? [...items, ...page.items] : page.items;
  cursor = page.nextCursor;
  loaded = true;
  paintList();
}

function paintDetail() {
  element("detail-title").textContent = current.story.title;
  element("summary").textContent =
    `${current.outcome} · 최종 점수 ${current.finalScore ?? "미제공"} · 내 역할 ${current.selfRole.name} · 난이도 ${current.difficulty} · ${current.durationSec}초`;
  for (const id of ["timing", "score", "reports", "reveal"])
    element(id).replaceChildren();
  if (current.rawState === "PURGED") return;
  paragraph(
    element("timing"),
    `${current.mode} · 채점 ${current.teamAttempts}회 · 본인 힌트 ${current.ownHintsUsed ?? "미측정"}`,
  );
  const displayTime = (raw) =>
    new Date(raw).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" }) + " KST";
  paragraph(
    element("timing"),
    "서버 시작: " +
      displayTime(current.startedAt) +
      " · 서버 종료: " +
      displayTime(current.endedAt),
  );
  if (current.scoreSummary) {
    paragraph(
      element("score"),
      `기본 ${current.scoreSummary.baseScore} · 오답 ${current.scoreSummary.wrongCount}회 · 감점 ${current.scoreSummary.penalty}`,
    );
    for (const item of current.scoreSummary.categories)
      paragraph(
        element("score"),
        `${item.category}: ${item.score}/${item.maxScore}`,
      );
  }
  element("reveal").textContent = current.revealText ?? "";
  for (const report of current.reports) {
    const panel = document.createElement("article");
    panel.className = "panel";
    paragraph(
      panel,
      `제출 ${report.submitNo} · ${report.state}${report.state === "GRADED" ? " · 기본 " + report.baseScore + " · 성공 " + report.success : " · 점수/성공 미제공"}`,
    );
    const actions = document.createElement("div");
    actions.className = "actions";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary-action";
    button.id = "report-" + report.reportKey;
    button.textContent = "이 제출본 원문 명시 조회";
    button.addEventListener("click", () => void run(() => openReport(report)));
    actions.append(button);
    panel.append(actions);
    element("reports").append(panel);
  }
}

/** 새 키/파기 투영은 선택 응답 자체의 키로 확인하며 정상 상세의 시각/원문을 남기지 않는다. */
async function selectRecord(key) {
  if (!UUID4.test(key)) throw new SoloFormatError();
  reader.clear();
  selectedKey = key;
  history.replaceState(null, "", "/records#" + key);
  current = await reader.read("/api/member/records/" + key, (value) =>
    parseRecord(value, true, key),
  );
  if (selectedKey !== key || !session.current()) throw new WebSessionBlocked();
  paintDetail();
}

/** 보고서 조회는 현재 NORMAL 상세의 보고서 키/순서/판정에 결속되고 다른 선택 뒤 원문은 버린다. */
async function openReport(summary) {
  if (
    !current ||
    current.rawState !== "NORMAL" ||
    !current.reports.some((item) => item.reportKey === summary.reportKey)
  )
    throw new SoloFormatError();
  closeRaw();
  const record = current,
    key = selectedKey;
  const report = await reader.read(
    "/api/member/records/" + key + "/reports/" + summary.reportKey,
    (value) => parseRecordReport(value, summary),
  );
  if (current !== record || key !== selectedKey || !session.current())
    throw new WebSessionBlocked();
  element("report-state").textContent =
    `제출 ${report.submitNo} · ${report.state} · 기본 ${report.baseScore ?? "미제공"} · 성공 ${report.success ?? "미제공"}`;
  element("report-body").textContent =
    "지목 인물: " +
    (report.report.culpritCode ?? "미선택") +
    "\n" +
    [
      ["method", "방법"],
      ["time", "시간"],
      ["motive", "동기"],
      ["evidence", "근거"],
    ]
      .map(([field, title]) => title + ": " + report.report[field])
      .join("\n");
  if (report.scoreBasis) {
    paragraph(
      element("report-basis"),
      "해당 오답 감점: " + report.scoreBasis.wrongPenalty,
    );
    for (const item of report.scoreBasis.categories)
      paragraph(
        element("report-basis"),
        `${item.category}: ${item.score}/${item.maxScore}`,
      );
  }
  element("raw").hidden = false;
}

async function connect() {
  if (!(await session.restore())) return;
  if (selectedKey) await selectRecord(selectedKey);
  else await list();
}
for (const [id, handler] of [
  ["connect", connect],
  ["list", () => list()],
  ["next", () => list(true)],
  ["refresh", () => selectRecord(selectedKey)],
  ["close-raw", closeRaw],
])
  element(id).addEventListener("click", () => void run(handler));
window.addEventListener("hashchange", () => {
  const key = location.hash.slice(1);
  if (!UUID4.test(key) || key === selectedKey || !reader) return;
  reader.clear();
  selectedKey = key;
  if (!busy && session.current()) void run(() => selectRecord(key));
});
try {
  session = createWebSession();
  reader = createGameClient(session, purge);
  await run(connect);
} catch (failure) {
  purge();
  element("error").textContent = errorText(failure);
  element("error").hidden = false;
}
render();
