import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import {
  parseRecord,
  parseRecordPage,
  parseRecordReport,
  SoloFormatError,
} from "../../main/resources/static/player-common/game-model.mjs";
import {
  createWebApi,
  WebApiError,
} from "../../main/resources/static/player-common/web-api.mjs";
import { recordFixture } from "../browser/record-fixture.mjs";

test("normal record summaries do not prefetch raw reports and preserve unmeasured null", () => {
  const fixture = recordFixture();
  assert.equal(parseRecord(fixture.summary).ownHintsUsed, null);
  assert.equal(Object.hasOwn(parseRecord(fixture.summary), "reports"), false);
  assert.equal(
    parseRecord(fixture.detail, true, fixture.detail.recordKey).lastReportKey,
    fixture.report.reportKey,
  );
  assert.throws(
    () => parseRecord(fixture.detail, true, randomUUID()),
    SoloFormatError,
  );
  assert.throws(
    () => parseRecord({ ...fixture.summary, ownHintsUsed: -1 }),
    SoloFormatError,
  );
});

test("purged list and detail accept only the same minimal nine fields", () => {
  const fixture = recordFixture();
  assert.equal(
    Object.keys(parseRecord(fixture.purged, true, fixture.purged.recordKey))
      .length,
    9,
  );
  for (const field of [
    "mode",
    "startedAt",
    "endedAt",
    "timeBasisAt",
    "gameKey",
    "reports",
    "revealText",
    "scoreSummary",
    "requestId",
    "ownHintsUsed",
    "teamAttempts",
  ])
    assert.throws(
      () => parseRecord({ ...fixture.purged, [field]: null }, true),
      SoloFormatError,
    );
  assert.throws(
    () => parseRecord({ ...fixture.purged, rawDeletedReason: "UNKNOWN" }),
    SoloFormatError,
  );
});

test("record pages reject duplicate identities, extra fields and oversized or empty cursors", () => {
  const fixture = recordFixture();
  const page = {
    items: [fixture.summary, fixture.purged],
    nextCursor: null,
    requestId: randomUUID(),
  };
  assert.equal(parseRecordPage(page).items.length, 2);
  for (const change of [
    { items: [fixture.summary, fixture.summary] },
    { items: Array(21).fill(fixture.summary) },
    { nextCursor: "" },
    { nextCursor: "a".repeat(2049) },
    { privateReport: fixture.report.report },
  ])
    assert.throws(
      () => parseRecordPage({ ...page, ...change }),
      SoloFormatError,
    );
});

test("report detail binds metadata to the selected permitted report and preserves literal raw text", () => {
  const fixture = recordFixture();
  const summary = fixture.detail.reports[0];
  assert.equal(
    parseRecordReport(fixture.report, summary).report.method,
    fixture.report.report.method,
  );
  for (const change of [
    { reportKey: randomUUID() },
    { submitNo: 2 },
    { baseScore: 91 },
    { gradedAt: null },
    { scoreBasis: { ...fixture.report.scoreBasis, wrongPenalty: 20 } },
  ])
    assert.throws(
      () => parseRecordReport({ ...fixture.report, ...change }, summary),
      SoloFormatError,
    );
});

test("ungradable reports and non-score termination never invent grading evidence", () => {
  const fixture = recordFixture();
  const summary = {
    ...fixture.detail.reports[0],
    state: "UNGRADABLE",
    baseScore: null,
    success: null,
  };
  const report = {
    ...fixture.report,
    ...summary,
    scoreBasis: null,
    gradedAt: null,
  };
  assert.equal(parseRecordReport(report, summary).scoreBasis, null);
  assert.throws(
    () =>
      parseRecordReport(
        { ...report, gradedAt: new Date().toISOString() },
        summary,
      ),
    SoloFormatError,
  );
  const record = {
    ...fixture.detail,
    outcome: "FORFEIT",
    finalScore: null,
    scoreSummary: null,
    revealText: null,
  };
  assert.equal(parseRecord(record, true).finalScore, null);
  assert.throws(
    () => parseRecord({ ...record, finalScore: 0 }, true),
    SoloFormatError,
  );
});

test("record transport is read-only, cookie-free and accepts only bounded canonical cursor query", async () => {
  const calls = [];
  const browser = {
    isSecureContext: true,
    location: { protocol: "https:", origin: "https://synthetic.invalid" },
    navigator: { serviceWorker: { controller: null } },
    async fetch(path, options) {
      calls.push({ path, options });
      return new Response("{}", {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "no-store",
        },
      });
    },
  };
  const api = createWebApi(browser);
  const token = "A".repeat(43);
  const key = randomUUID(),
    reportKey = randomUUID();
  for (const path of [
    "/api/member/records?size=20&cursor=opaque%2Ftoken",
    "/api/member/records/" + key,
    "/api/member/records/" + key + "/reports/" + reportKey,
  ])
    await api.request("GET", path, { token });
  for (const call of calls) {
    assert.equal(call.options.credentials, "omit");
    assert.equal(call.options.headers["X-Sicha-Player-Web-Epoch"], undefined);
  }
  for (const path of [
    "/api/member/records?size=101",
    "/api/member/records?cursor=x&cursor=y",
    "/api/member/records/" + key + "?secret=x",
    "/api/member/records/" + key + "/reports/invalid",
  ])
    await assert.rejects(api.request("GET", path, { token }), WebApiError);
  await assert.rejects(
    api.request("POST", "/api/member/records", { token, body: {} }),
    WebApiError,
  );
  assert.equal(calls.length, 3);
});
