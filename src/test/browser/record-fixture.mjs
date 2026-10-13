import { randomUUID } from "node:crypto";
import { soloFixture } from "./solo-fixture.mjs";

/** 합성 브라우저/모델 시험 자료이며 실제 탈퇴나 파기 완료의 증거가 아니다. */
export function recordFixture() {
  const game = soloFixture();
  game.respond("POST", "/api/games/" + game.gameKey + "/start", {
    requestKey: randomUUID(),
  });
  game.complete();
  const detail = game.result();
  detail.ownHintsUsed = null;
  const summaryFields = [
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
  ];
  const summary = Object.fromEntries(
    summaryFields.map((field) => [field, detail[field]]),
  );
  const purged = {
    recordKey: randomUUID(),
    rawState: "PURGED",
    story: detail.story,
    selfRole: detail.selfRole,
    difficulty: detail.difficulty,
    durationSec: detail.durationSec,
    outcome: detail.outcome,
    finalScore: detail.finalScore,
    rawDeletedReason: "JOINT_REPORT_DELETED",
  };
  const graded = detail.reports[0];
  const report = {
    ...graded,
    report: {
      culpritCode: "P1",
      method: "PRIVATE_RECORD_BODY <script>alert(1)</script>\n  🙂  ",
      time: "시간",
      motive: "동기",
      evidence: "근거",
    },
    scoreBasis: { categories: detail.scoreSummary.categories, wrongPenalty: 0 },
    acceptedAt: new Date().toISOString(),
    gradedAt: new Date().toISOString(),
    requestId: randomUUID(),
  };
  let erased = false;
  const calls = [];
  function respond(raw) {
    calls.push(raw);
    const path = raw.split("?")[0];
    if (path === "/api/member/records")
      return {
        status: 200,
        value: {
          items: erased
            ? [purged]
            : raw.includes("cursor=")
              ? [purged]
              : [summary],
          nextCursor: erased || raw.includes("cursor=") ? null : "opaque/token",
          requestId: randomUUID(),
        },
      };
    if (path === "/api/member/records/" + purged.recordKey)
      return { status: 200, value: purged };
    if (!erased && path === "/api/member/records/" + detail.recordKey)
      return { status: 200, value: detail };
    if (
      !erased &&
      path ===
        "/api/member/records/" +
          detail.recordKey +
          "/reports/" +
          report.reportKey
    )
      return { status: 200, value: report };
    return {
      status: 404,
      value: { code: "RECORD_NOT_FOUND", requestId: randomUUID() },
    };
  }
  return {
    summary,
    detail,
    purged,
    report,
    respond,
    calls,
    erase() {
      erased = true;
    },
  };
}
