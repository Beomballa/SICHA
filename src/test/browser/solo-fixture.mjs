import { randomUUID } from "node:crypto";

/** 합성 HTTP/모델 시험 전용 자료다. 실제 공개·자격·모델 품질을 증명하지 않는다. */
export function soloFixture() {
  const gameKey = randomUUID();
  const now = () => new Date().toISOString();
  const deadline = new Date(Date.now() + 3600000).toISOString();
  const summary = {
    storyCode: "SYNTHETIC_CASE",
    title: "합성 <script> 사건",
    difficulty: 2,
    estMin: 10,
    estMax: 20,
    limitSec: 3600,
    playerCount: 2,
    publishedVersionNo: 1,
    playRev: "9007199254740993",
  };
  const notice = {
    policyCode: "SYNTHETIC_FRIENDLY",
    noticeHash: "a".repeat(64),
    version: "합성",
    body: "합성 고지 <img src=x onerror=alert(1)>",
    contact: "synthetic@example.invalid",
    requestId: randomUUID(),
  };
  const story = {
    ...summary,
    intro: "합성 소개",
    setting: "합성 배경",
    policyCode: notice.policyCode,
    noticeHash: notice.noticeHash,
    noticeSummary: notice.body,
  };
  const state = {
    gameKey,
    mode: "SOLO",
    title: summary.title,
    state: "WAITING",
    outcome: null,
    endReason: null,
    rev: "0",
    draftRev: "0",
    host: true,
    self: { slot: 1, ready: false, effectiveReady: false, roleCode: null },
    partner: {
      joined: false,
      ready: false,
      effectiveReady: false,
      online: false,
    },
    publishedVersionNo: 1,
    playRev: summary.playRev,
    needsRefresh: false,
    lobbyExpiresAt: deadline,
    startedAt: null,
    playDeadline: null,
    serverTime: now(),
    submissionState: "NONE",
    attemptsRemaining: null,
    hintsRemaining: null,
    canReady: false,
    canStart: true,
    blockedReason: null,
    requestId: randomUUID(),
  };
  const report = {
    culpritCode: null,
    method: "",
    time: "",
    motive: "",
    evidence: "",
  };
  const materials = {
    gameKey,
    state: "RUNNING",
    rev: "1",
    playDeadline: deadline,
    basic: {
      title: summary.title,
      intro: story.intro,
      setting: story.setting,
      difficulty: 2,
      estMin: 10,
      estMax: 20,
      limitSec: 3600,
    },
    roles: [
      { code: "R1", name: "합성 역할 A", brief: "역할 A 원문" },
      { code: "R2", name: "합성 역할 B", brief: "역할 B 원문" },
    ],
    persons: [{ code: "P1", name: "합성 인물", publicText: "인물 소개" }],
    clues: [
      {
        code: "C1",
        title: "합성 단서",
        body: "<script>합성 원문</script>",
        personCode: "P1",
        read: false,
        readAt: null,
      },
    ],
    openedHints: [],
    requiredNotices: [{ rubricCode: "RULE1", text: "합성 판정 안내" }],
    hintsRemaining: 3,
    requestId: randomUUID(),
  };
  const originalReports = new Map();
  const calls = [];
  let result = null;
  const copy = (value) => JSON.parse(JSON.stringify(value));
  const bump = () => {
    state.rev = String(BigInt(state.rev) + 1n);
    state.serverTime = now();
    materials.rev = state.rev;
  };

  /** 고정 원본 영수증을 저장하여 같은 키의 replay가 새 동작을 만들지 않게 한다. */
  function receipt(action, key, extra = {}) {
    const previous = originalReports.get(key);
    if (previous)
      return {
        ...copy(previous),
        replayed: true,
        changed: false,
        current: copy(state),
        requestId: randomUUID(),
      };
    const value = {
      action,
      replayed: false,
      changed: true,
      original: { gameKey, state: state.state, rev: state.rev, ...extra },
      current: copy(state),
      requestId: randomUUID(),
    };
    originalReports.set(key, copy(value));
    return value;
  }

  /** 호출자가 명시적으로 합성 채점을 완료해야만 종료 결과를 만든다. */
  function complete() {
    bump();
    state.state = "ENDED";
    state.outcome = "SUCCESS";
    state.endReason = "SUCCESS";
    state.submissionState = "NONE";
    const reportKey = randomUUID();
    result = {
      recordKey: randomUUID(),
      rawState: "NORMAL",
      story: { code: summary.storyCode, title: summary.title },
      selfRole: { code: "ALL", name: "두 역할 단독" },
      mode: "SOLO",
      difficulty: 2,
      durationSec: 12,
      outcome: "SUCCESS",
      finalScore: 90,
      teamAttempts: 1,
      ownHintsUsed: 0,
      timeBasisAt: now(),
      startedAt: state.startedAt,
      endedAt: now(),
      gameKey,
      scoreSummary: {
        baseScore: 90,
        wrongCount: 0,
        penalty: 0,
        finalScore: 90,
        categories: [{ category: "LOGIC", score: 90, maxScore: 100 }],
      },
      reports: [
        {
          reportKey,
          submitNo: 1,
          state: "GRADED",
          baseScore: 90,
          success: true,
        },
      ],
      lastReportKey: reportKey,
      revealText: "합성 해설 <script>원문</script>",
      rawDeletedReason: null,
      requestId: randomUUID(),
    };
  }

  /** 경로별 합성 응답만 제공하고 미지원 요청은 거절한다. */
  function respond(method, raw, body = {}) {
    calls.push({ method, path: raw, body: copy(body) });
    const path = raw.split("?")[0];
    const reply = (value, status = 200) => ({ value: copy(value), status });
    if (method === "GET") {
      if (path === "/api/stories")
        return reply({
          items: [summary],
          nextCursor: null,
          requestId: randomUUID(),
        });
      if (path === "/api/stories/" + summary.storyCode) return reply(story);
      if (path === "/api/member/privacy/policies/friendly")
        return reply(notice);
      if (path === "/api/games/current")
        return reply({
          game: state.state === "ENDED" ? null : state,
          requestId: randomUUID(),
        });
      if (path === "/api/games/" + gameKey) return reply(state);
      if (path.endsWith("/materials")) return reply(materials);
      if (path.endsWith("/report"))
        return reply({
          gameKey,
          rev: state.rev,
          draftRev: state.draftRev,
          report,
          submissionState: state.submissionState,
          requestId: randomUUID(),
        });
      if (path.endsWith("/result") && result) return reply(result);
    }
    if (originalReports.has(body.requestKey))
      return reply(
        receipt(originalReports.get(body.requestKey).action, body.requestKey),
        path === "/api/games" ? 201 : 200,
      );
    if (path === "/api/games" && method === "POST")
      return reply(
        receipt("PUBLIC_CREATE", body.requestKey, { lobbyExpiresAt: deadline }),
        201,
      );
    if (path.endsWith("/start")) {
      bump();
      state.state = "RUNNING";
      state.canStart = false;
      state.startedAt = now();
      state.playDeadline = deadline;
      state.attemptsRemaining = 5;
      state.hintsRemaining = 3;
      state.self.roleCode = "ALL";
      return reply(
        receipt("PUBLIC_START", body.requestKey, { lobbyExpiresAt: deadline }),
      );
    }
    if (path.endsWith("/read")) {
      bump();
      materials.clues[0].read = true;
      materials.clues[0].readAt = now();
      return reply(
        receipt("PUBLIC_CLUE_READ", body.requestKey, { changed: true }),
      );
    }
    if (path.endsWith("/open")) {
      bump();
      const level = Number(path.split("/").at(-2));
      materials.openedHints.push({
        level,
        code: "H" + level,
        body: "합성 힌트",
      });
      materials.hintsRemaining -= 1;
      state.hintsRemaining -= 1;
      return reply(
        receipt("PUBLIC_HINT_OPEN", body.requestKey, { changed: true }),
      );
    }
    if (method === "PATCH" && path.endsWith("/report")) {
      bump();
      state.draftRev = String(BigInt(state.draftRev) + 1n);
      Object.assign(report, body.report);
      return reply(
        receipt("PUBLIC_REPORT_EDIT", body.requestKey, {
          draftRev: state.draftRev,
          changed: true,
        }),
      );
    }
    if (path.endsWith("/report/submit")) {
      bump();
      state.submissionState = "PENDING";
      return reply(
        receipt("PUBLIC_REPORT_SUBMIT", body.requestKey, {
          draftRev: state.draftRev,
          reportKey: randomUUID(),
          jobKey: randomUUID(),
          ordinal: 1,
          acceptedAt: now(),
          gradeDeadline: deadline,
        }),
      );
    }
    if (path.endsWith("/heartbeat")) return { status: 204, value: null };
    return reply(
      { code: "SYNTHETIC_UNSUPPORTED", requestId: randomUUID() },
      403,
    );
  }
  return {
    respond,
    complete,
    state,
    materials,
    report,
    story,
    notice,
    calls,
    gameKey,
    result: () => result,
  };
}
