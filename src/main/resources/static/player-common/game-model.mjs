import { UUID4 } from "./web-api.mjs";

const CODE = /^[A-Z0-9_]{1,40}$/;
const ITEM_CODE = /^[A-Z0-9_]{1,32}$/;
const HASH = /^[0-9a-f]{64}$/;
const UTC = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/;

/** 닫힌 서버 응답이 아니면 원문을 표시하지 않는다. */
export class SoloFormatError extends Error {
  constructor() {
    super("사건 응답 형식을 확인할 수 없습니다.");
  }
}

/** 허용 필드 집합을 정확히 확인한다. */
function shape(value, fields) {
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).length !== fields.length ||
    !fields.every((key) => Object.hasOwn(value, key))
  )
    throw new SoloFormatError();
  return value;
}

/** 문자열은 임의 정규화하거나 빈 원문을 다른 값으로 대체하지 않는다. */
function text(value, allowEmpty = false) {
  if (typeof value !== "string" || (!allowEmpty && !value.length))
    throw new SoloFormatError();
  return value;
}

function id(value) {
  if (typeof value !== "string" || !UUID4.test(value))
    throw new SoloFormatError();
  return value;
}
function integer(value, min = 0, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < min || value > max)
    throw new SoloFormatError();
  return value;
}
function timestamp(value, nullable = false) {
  if (nullable && value === null) return value;
  if (
    typeof value !== "string" ||
    !UTC.test(value) ||
    !Number.isFinite(Date.parse(value))
  )
    throw new SoloFormatError();
  return value;
}
function code(value, item = false) {
  if (typeof value !== "string" || !(item ? ITEM_CODE : CODE).test(value))
    throw new SoloFormatError();
  return value;
}
function boolean(value) {
  if (typeof value !== "boolean") throw new SoloFormatError();
}
function request(value) {
  id(value.requestId);
}

/** DB 수정번호는 정규 십진 문자열로 유지하고 Number로 변환하지 않는다. */
export function revision(value) {
  if (
    typeof value !== "string" ||
    !/^(0|[1-9][0-9]{0,18})$/.test(value) ||
    BigInt(value) >= 9223372036854775807n
  )
    throw new SoloFormatError();
  return value;
}

const SUMMARY = [
  "storyCode",
  "title",
  "difficulty",
  "estMin",
  "estMax",
  "limitSec",
  "playerCount",
  "publishedVersionNo",
  "playRev",
];

/** 안전 소개와 게시 선택의 실제 버전/수정번호를 확인한다. */
export function parseStory(value, detail = false) {
  shape(value, [
    ...SUMMARY,
    ...(detail
      ? ["intro", "setting", "policyCode", "noticeHash", "noticeSummary"]
      : []),
  ]);
  code(value.storyCode);
  text(value.title);
  integer(value.difficulty, 1, 5);
  integer(value.estMin, 1);
  integer(value.estMax, value.estMin);
  integer(value.limitSec, 1);
  integer(value.publishedVersionNo, 1, 2147483647);
  revision(value.playRev);
  if (value.playerCount !== 2) throw new SoloFormatError();
  if (detail) {
    for (const field of ["intro", "setting", "policyCode", "noticeSummary"])
      text(value[field]);
    if (!HASH.test(text(value.noticeHash))) throw new SoloFormatError();
  }
  return value;
}

export function parseCatalog(value) {
  shape(value, ["items", "nextCursor", "requestId"]);
  request(value);
  if (
    !Array.isArray(value.items) ||
    value.items.length > 20 ||
    (value.nextCursor !== null &&
      (typeof value.nextCursor !== "string" ||
        !value.nextCursor.length ||
        value.nextCursor.length > 2048))
  )
    throw new SoloFormatError();
  value.items.forEach((item) => parseStory(item));
  if (
    new Set(value.items.map((item) => item.storyCode)).size !==
    value.items.length
  )
    throw new SoloFormatError();
  return value;
}

export function parseNotice(value, story) {
  shape(value, [
    "policyCode",
    "noticeHash",
    "version",
    "body",
    "contact",
    "requestId",
  ]);
  request(value);
  for (const key of ["policyCode", "version", "body", "contact"])
    text(value[key]);
  if (
    !HASH.test(text(value.noticeHash)) ||
    value.policyCode !== story.policyCode ||
    value.noticeHash !== story.noticeHash ||
    value.body !== story.noticeSummary
  )
    throw new SoloFormatError();
  return value;
}

const STATE = [
  "gameKey",
  "mode",
  "title",
  "state",
  "outcome",
  "endReason",
  "rev",
  "draftRev",
  "host",
  "self",
  "partner",
  "publishedVersionNo",
  "playRev",
  "needsRefresh",
  "lobbyExpiresAt",
  "startedAt",
  "playDeadline",
  "serverTime",
  "submissionState",
  "attemptsRemaining",
  "hintsRemaining",
  "canReady",
  "canStart",
  "blockedReason",
  "requestId",
];

/** 서버 모드·고정 참가자·제출 상태를 확인하고 다른 모드로 강등하지 않는다. */
export function parseState(value, expectedKey, mode = "SOLO") {
  shape(value, STATE);
  request(value);
  id(value.gameKey);
  if (
    (expectedKey !== undefined && value.gameKey !== expectedKey) ||
    !["SOLO", "COOP"].includes(mode) ||
    value.mode !== mode ||
    !["WAITING", "RUNNING", "ENDED", "CANCELLED", "EXPIRED"].includes(
      value.state,
    ) ||
    !(
      mode === "COOP" ? ["NONE", "PROPOSED", "PENDING"] : ["NONE", "PENDING"]
    ).includes(value.submissionState)
  )
    throw new SoloFormatError();
  text(value.title);
  revision(value.rev);
  revision(value.draftRev);
  revision(value.playRev);
  integer(value.publishedVersionNo, 1, 2147483647);
  for (const field of ["host", "needsRefresh", "canReady", "canStart"])
    boolean(value[field]);
  if (
    (mode === "SOLO" && (!value.host || value.canReady)) ||
    (value.canStart && (value.state !== "WAITING" || value.needsRefresh))
  )
    throw new SoloFormatError();
  shape(value.self, ["slot", "ready", "effectiveReady", "roleCode"]);
  shape(value.partner, ["joined", "ready", "effectiveReady", "online"]);
  if (
    mode === "SOLO" &&
    (value.self.slot !== 1 ||
      value.self.ready !== false ||
      value.self.effectiveReady !== false ||
      Object.values(value.partner).some((flag) => flag !== false))
  )
    throw new SoloFormatError();
  if (mode === "COOP") {
    if (
      ![1, 2].includes(value.self.slot) ||
      value.host !== (value.self.slot === 1)
    )
      throw new SoloFormatError();
    boolean(value.self.ready);
    boolean(value.self.effectiveReady);
    Object.values(value.partner).forEach(boolean);
    if (
      (value.self.effectiveReady && !value.self.ready) ||
      (value.partner.effectiveReady && !value.partner.ready) ||
      (!value.partner.joined &&
        (value.partner.ready ||
          value.partner.effectiveReady ||
          value.partner.online)) ||
      (value.state === "WAITING" &&
        (value.self.roleCode !== null ||
          value.submissionState !== "NONE" ||
          value.startedAt !== null ||
          value.playDeadline !== null ||
          value.attemptsRemaining !== null ||
          value.hintsRemaining !== null)) ||
      (value.state === "RUNNING" &&
        (value.self.roleCode === null || !value.partner.joined)) ||
      (value.canStart &&
        (!value.host ||
          !value.self.effectiveReady ||
          !value.partner.effectiveReady ||
          !value.partner.online))
    )
      throw new SoloFormatError();
  }
  if (value.self.roleCode !== null) code(value.self.roleCode);
  for (const field of ["lobbyExpiresAt", "serverTime"]) timestamp(value[field]);
  for (const field of ["startedAt", "playDeadline"])
    timestamp(value[field], true);
  for (const field of ["attemptsRemaining", "hintsRemaining"])
    if (value[field] !== null) integer(value[field]);
  for (const field of ["outcome", "endReason", "blockedReason"])
    if (value[field] !== null) text(value[field]);
  return value;
}

/** REPORT-1의 다섯 필드와 원문 길이·개행 규칙을 그대로 검사한다. */
export function parseReportBody(value) {
  shape(value, ["culpritCode", "method", "time", "motive", "evidence"]);
  if (value.culpritCode !== null) code(value.culpritCode, true);
  for (const key of ["method", "time", "motive", "evidence"]) {
    text(value[key], true);
    if (
      [...value[key]].length > 5000 ||
      /[\r\x00]/.test(value[key]) ||
      [...value[key]].some((part) => {
        const point = part.codePointAt(0);
        return point >= 0xd800 && point <= 0xdfff;
      })
    )
      throw new SoloFormatError();
  }
  return value;
}

export function parseReport(value, expectedKey) {
  shape(value, [
    "gameKey",
    "rev",
    "draftRev",
    "report",
    "submissionState",
    "requestId",
  ]);
  request(value);
  id(value.gameKey);
  if (
    value.gameKey !== expectedKey ||
    !["NONE", "PENDING"].includes(value.submissionState)
  )
    throw new SoloFormatError();
  revision(value.rev);
  revision(value.draftRev);
  parseReportBody(value.report);
  return value;
}

/** 활성 제안은 고정 원문·초안 수정번호·현재 역할 권한을 함께 검증한다. */
export function parseCoopReport(value, state) {
  shape(value, [
    "gameKey",
    "draftRev",
    "report",
    "proposal",
    "pending",
    "submissionState",
    "requestId",
  ]);
  request(value);
  id(value.gameKey);
  revision(value.draftRev);
  parseReportBody(value.report);
  if (
    value.gameKey !== state.gameKey ||
    value.draftRev !== state.draftRev ||
    value.submissionState !== state.submissionState ||
    state.mode !== "COOP"
  )
    throw new SoloFormatError();
  if (value.proposal !== null) {
    const item = shape(value.proposal, [
      "reportKey",
      "sourceRev",
      "report",
      "proposerSlot",
      "state",
      "canAccept",
      "canReject",
      "canWithdraw",
    ]);
    id(item.reportKey);
    revision(item.sourceRev);
    parseReportBody(item.report);
    integer(item.proposerSlot, 1, 2);
    for (const key of ["canAccept", "canReject", "canWithdraw"])
      boolean(item[key]);
    const self = item.proposerSlot === state.self.slot;
    if (
      item.state !== "PROPOSED" ||
      item.sourceRev !== value.draftRev ||
      item.canWithdraw !== self ||
      item.canAccept === self ||
      item.canReject !== item.canAccept ||
      !["culpritCode", "method", "time", "motive", "evidence"].every(
        (key) => item.report[key] === value.report[key],
      )
    )
      throw new SoloFormatError();
  }
  if (value.pending !== null) {
    const item = shape(value.pending, [
      "reportKey",
      "submitNo",
      "report",
      "acceptedAt",
      "gradeDeadline",
    ]);
    id(item.reportKey);
    integer(item.submitNo, 1);
    parseReportBody(item.report);
    timestamp(item.acceptedAt);
    timestamp(item.gradeDeadline);
  }
  if (
    (value.proposal !== null && value.pending !== null) ||
    value.submissionState !==
      (value.proposal ? "PROPOSED" : value.pending ? "PENDING" : "NONE")
  )
    throw new SoloFormatError();
  return value;
}

/** 초대 조회는 고지·현재 수명만 반환하며 참가나 준비 상태로 해석하지 않는다. */
export function parseInvitePreview(value, inviteKey) {
  shape(value, [
    "inviteKey",
    "title",
    "difficulty",
    "limitSec",
    "playerCount",
    "expiresAt",
    "lobbyExpiresAt",
    "policyCode",
    "noticeHash",
    "noticeSummary",
    "requestId",
  ]);
  request(value);
  id(value.inviteKey);
  text(value.title);
  integer(value.difficulty, 1, 5);
  integer(value.limitSec, 1);
  timestamp(value.expiresAt);
  timestamp(value.lobbyExpiresAt);
  text(value.policyCode);
  text(value.noticeSummary);
  if (
    value.inviteKey !== inviteKey ||
    value.playerCount !== 2 ||
    !HASH.test(text(value.noticeHash)) ||
    Date.parse(value.expiresAt) <= Date.now() ||
    Date.parse(value.lobbyExpiresAt) < Date.parse(value.expiresAt)
  )
    throw new SoloFormatError();
  return value;
}

/** SOLO는 고정 두 역할, COOP는 현재 참가자의 단일 고정 역할만 표시한다. */
export function parseMaterials(
  value,
  expectedKey,
  mode = "SOLO",
  roleCode = null,
) {
  shape(value, [
    "gameKey",
    "state",
    "rev",
    "playDeadline",
    "basic",
    "roles",
    "persons",
    "clues",
    "openedHints",
    "requiredNotices",
    "hintsRemaining",
    "requestId",
  ]);
  request(value);
  id(value.gameKey);
  revision(value.rev);
  timestamp(value.playDeadline);
  integer(value.hintsRemaining, 0, 3);
  if (
    value.gameKey !== expectedKey ||
    value.state !== "RUNNING" ||
    Date.parse(value.playDeadline) <= Date.now()
  )
    throw new SoloFormatError();
  shape(value.basic, [
    "title",
    "intro",
    "setting",
    "difficulty",
    "estMin",
    "estMax",
    "limitSec",
  ]);
  text(value.basic.title);
  integer(value.basic.difficulty, 1, 5);
  integer(value.basic.limitSec, 1);
  for (const field of ["intro", "setting"])
    if (value.basic[field] !== null) text(value.basic[field], true);
  for (const field of ["estMin", "estMax"])
    if (value.basic[field] !== null) integer(value.basic[field], 1);
  for (const [field, keys, strings] of [
    ["roles", ["code", "name", "brief"], ["brief"]],
    ["persons", ["code", "name", "publicText"], ["publicText"]],
    [
      "clues",
      ["code", "title", "body", "personCode", "read", "readAt"],
      ["body", "personCode"],
    ],
  ]) {
    if (!Array.isArray(value[field])) throw new SoloFormatError();
    const seen = new Set();
    for (const item of value[field]) {
      shape(item, keys);
      code(item.code, true);
      text(item[field === "clues" ? "title" : "name"]);
      if (seen.has(item.code)) throw new SoloFormatError();
      seen.add(item.code);
      for (const key of strings) if (item[key] !== null) text(item[key], true);
      if (field === "clues") {
        boolean(item.read);
        timestamp(item.readAt, true);
        if (item.read !== (item.readAt !== null)) throw new SoloFormatError();
      }
    }
  }
  if (
    mode === "SOLO"
      ? value.roles.length !== 2
      : mode !== "COOP" ||
        value.roles.length !== 1 ||
        value.roles[0].code !== roleCode
  )
    throw new SoloFormatError();
  if (
    !Array.isArray(value.openedHints) ||
    !Array.isArray(value.requiredNotices)
  )
    throw new SoloFormatError();
  const levels = new Set();
  for (const hint of value.openedHints) {
    shape(hint, ["level", "code", "body"]);
    integer(hint.level, 1, 3);
    code(hint.code, true);
    text(hint.body);
    if (levels.has(hint.level)) throw new SoloFormatError();
    levels.add(hint.level);
  }
  for (const notice of value.requiredNotices) {
    shape(notice, ["rubricCode", "text"]);
    code(notice.rubricCode, true);
    text(notice.text);
  }
  return value;
}

/** 명령 영수증의 실제 action·원본 방·현재 방을 모두 결속한다. */
export function parseReceipt(value, action, expectedKey, mode = "SOLO") {
  const invite = action === "PUBLIC_INVITE";
  const secret = Object.hasOwn(value, "inviteSecret");
  shape(value, [
    "action",
    "replayed",
    "changed",
    "original",
    "current",
    "requestId",
    ...(invite
      ? [
          "requiresReissue",
          "blockedReason",
          ...(secret ? ["inviteKey", "inviteSecret", "expiresAt"] : []),
        ]
      : []),
  ]);
  request(value);
  if (value.action !== action) throw new SoloFormatError();
  boolean(value.replayed);
  boolean(value.changed);
  const original = value.original;
  const allowed = [
    "gameKey",
    "state",
    "rev",
    "draftRev",
    "changed",
    "reportKey",
    "jobKey",
    "ordinal",
    "acceptedAt",
    "gradeDeadline",
    "lobbyExpiresAt",
    "inviteKey",
  ];
  if (
    !original ||
    typeof original !== "object" ||
    Array.isArray(original) ||
    Object.keys(original).some((key) => !allowed.includes(key))
  )
    throw new SoloFormatError();
  id(original.gameKey);
  if (mode === "SOLO" || original.state !== undefined) text(original.state);
  revision(original.rev);
  if (original.draftRev !== undefined) revision(original.draftRev);
  if (expectedKey !== undefined && original.gameKey !== expectedKey)
    throw new SoloFormatError();
  if (action === "PUBLIC_LEAVE") {
    if (value.current !== null) throw new SoloFormatError();
  } else {
    const state = parseState(value.current, expectedKey, mode);
    if (state.gameKey !== original.gameKey) throw new SoloFormatError();
  }
  if (invite) {
    boolean(value.requiresReissue);
    if (value.blockedReason !== null) text(value.blockedReason);
    if (secret) {
      id(value.inviteKey);
      timestamp(value.expiresAt);
      if (
        value.replayed ||
        value.requiresReissue ||
        original.inviteKey !== value.inviteKey ||
        typeof value.inviteSecret !== "string" ||
        !/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(value.inviteSecret) ||
        Date.parse(value.expiresAt) <= Date.now()
      )
        throw new SoloFormatError();
    } else if (!value.replayed) throw new SoloFormatError();
  }
  if (
    ["PUBLIC_REPORT_SUBMIT", "PUBLIC_REPORT_ACCEPT"].includes(action) &&
    !value.replayed
  ) {
    id(original.reportKey);
    id(original.jobKey);
    integer(original.ordinal, 1);
    timestamp(original.acceptedAt);
    timestamp(original.gradeDeadline);
  }
  return value;
}

/** 정식 개인 기록 상세만 결과로 채택하고 파기·미측정 값을 복구하지 않는다. */
export function parseResult(value, expectedKey, mode = "SOLO") {
  shape(value, [
    "recordKey",
    "rawState",
    "story",
    "selfRole",
    "mode",
    "difficulty",
    "durationSec",
    "outcome",
    "finalScore",
    "teamAttempts",
    "ownHintsUsed",
    "timeBasisAt",
    "startedAt",
    "endedAt",
    "gameKey",
    "scoreSummary",
    "reports",
    "lastReportKey",
    "revealText",
    "rawDeletedReason",
    "requestId",
  ]);
  request(value);
  id(value.recordKey);
  id(value.gameKey);
  if (
    value.gameKey !== expectedKey ||
    !["SOLO", "COOP"].includes(mode) ||
    value.mode !== mode ||
    value.rawState !== "NORMAL" ||
    value.rawDeletedReason !== null
  )
    throw new SoloFormatError();
  shape(value.story, ["code", "title"]);
  code(value.story.code);
  text(value.story.title);
  shape(value.selfRole, ["code", "name"]);
  code(value.selfRole.code);
  text(value.selfRole.name);
  integer(value.difficulty, 1, 5);
  integer(value.durationSec);
  integer(value.teamAttempts);
  if (value.ownHintsUsed !== null) integer(value.ownHintsUsed);
  for (const key of ["timeBasisAt", "startedAt", "endedAt"])
    timestamp(value[key]);
  const scored = ["SUCCESS", "TIME_LIMIT", "ATTEMPTS_EXHAUSTED"].includes(
    value.outcome,
  );
  if (
    ![
      "SUCCESS",
      "TIME_LIMIT",
      "ATTEMPTS_EXHAUSTED",
      "FORFEIT",
      "SYSTEM_ERROR",
      "ACCOUNT_DELETED",
    ].includes(value.outcome) ||
    scored !== (value.scoreSummary !== null)
  )
    throw new SoloFormatError();
  if (scored) {
    integer(value.finalScore, 0, 100);
    const score = shape(value.scoreSummary, [
      "baseScore",
      "wrongCount",
      "penalty",
      "finalScore",
      "categories",
    ]);
    integer(score.baseScore, 0, 100);
    integer(score.wrongCount);
    integer(score.penalty);
    integer(score.finalScore, 0, 100);
    if (
      score.finalScore !== value.finalScore ||
      score.penalty !== score.wrongCount * 10 ||
      !Array.isArray(score.categories)
    )
      throw new SoloFormatError();
    for (const category of score.categories) {
      shape(category, ["category", "score", "maxScore"]);
      code(category.category);
      integer(category.maxScore);
      integer(category.score, 0, category.maxScore);
    }
  } else if (value.finalScore !== null || value.revealText !== null)
    throw new SoloFormatError();
  if (value.revealText !== null) text(value.revealText, true);
  if (!Array.isArray(value.reports)) throw new SoloFormatError();
  let previous = 0;
  for (const report of value.reports) {
    shape(report, ["reportKey", "submitNo", "state", "baseScore", "success"]);
    id(report.reportKey);
    integer(report.submitNo, previous + 1, 5);
    previous = report.submitNo;
    if (report.state === "GRADED") {
      integer(report.baseScore, 0, 100);
      boolean(report.success);
    } else if (
      report.state !== "UNGRADABLE" ||
      report.baseScore !== null ||
      report.success !== null
    )
      throw new SoloFormatError();
  }
  if (
    value.lastReportKey !==
    (value.reports.length ? value.reports.at(-1).reportKey : null)
  )
    throw new SoloFormatError();
  return value;
}
