import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test } from "node:test";
import { soloFixture } from "../browser/solo-fixture.mjs";
import {
  createWebApi,
  WebApiError,
} from "../../main/resources/static/player-common/web-api.mjs";
import { createGameClient } from "../../main/resources/static/player-common/game-client.mjs";
import {
  parseStory,
  parseNotice,
  parseState,
  parseMaterials,
  parseReportBody,
  parseReport,
  parseReceipt,
  parseResult,
  revision,
  SoloFormatError,
} from "../../main/resources/static/player-common/game-model.mjs";

/** 실제 인터페이스만 구현한 합성 세션이며 제품 자격 대체 경로로 사용하지 않는다. */
function owner(request) {
  const listeners = new Set();
  let valid = true;
  return {
    current: () => valid,
    authorizedRequest: request,
    onClear(callback) {
      listeners.add(callback);
      return () => listeners.delete(callback);
    },
    revoke() {
      valid = false;
      for (const listener of listeners) listener();
    },
  };
}

function start(fixture) {
  fixture.respond("POST", "/api/games/" + fixture.gameKey + "/start", {
    requestKey: randomUUID(),
  });
}

/** 브라우저 전송의 쿠키·경로·본문 계약만 검사하는 합성 환경이다. */
function transport() {
  const calls = [];
  const browser = {
    isSecureContext: true,
    location: { protocol: "https:", origin: "https://synthetic.invalid" },
    navigator: { serviceWorker: { controller: null } },
    async fetch(path, options) {
      calls.push({ path, options });
      if (path.endsWith("/heartbeat"))
        return new Response(null, {
          status: 204,
          headers: { "Cache-Control": "no-store" },
        });
      return new Response("{}", {
        status: path === "/api/games" ? 201 : 200,
        headers: {
          "Cache-Control": "no-store",
          "Content-Type": "application/json",
        },
      });
    },
  };
  return { calls, api: createWebApi(browser) };
}

test("solo transport allows only exact verbs, canonical keys and bounded opaque query", async () => {
  const { api, calls } = transport();
  const key = randomUUID();
  const token = "A".repeat(43);
  for (const [method, path, body] of [
    ["GET", "/api/stories?size=20&cursor=opaque%2Ftoken", undefined],
    ["POST", "/api/games", {}],
    ["PATCH", "/api/games/" + key + "/report", { text: "가".repeat(10000) }],
    ["POST", "/api/games/" + key + "/heartbeat", {}],
  ])
    await api.request(method, path, { token, body });
  for (const call of calls) {
    assert.equal(call.options.credentials, "omit");
    assert.equal(call.options.headers["X-Sicha-Player-Web-Epoch"], undefined);
  }
  for (const path of [
    "/api/stories?size=101",
    "/api/stories?cursor=x&cursor=y",
    "/api/stories?cursor=%2ftoken",
    "/api/stories?size=20#secret",
    "/api/games/" + key + "/result?token=x",
    "/api/games/" + key + "/ready",
    "https://other.invalid/api/stories",
  ])
    await assert.rejects(api.request("GET", path, { token }), WebApiError);
  assert.equal(calls.length, 4);
  await assert.rejects(
    api.request("POST", "/api/games", {
      token,
      body: { note: "a".repeat(8192) },
    }),
    WebApiError,
  );
});

test("published revisions preserve greater-than-safe-integer values and reject malformed domains", () => {
  const fixture = soloFixture();
  assert.equal(parseStory(fixture.story, true).playRev, "9007199254740993");
  assert.equal(revision("9223372036854775806"), "9223372036854775806");
  for (const value of [9007199254740993, "01", "-1", "9223372036854775807"])
    assert.throws(() => revision(value), SoloFormatError);
  assert.throws(
    () => parseStory({ ...fixture.story, answer: "private" }, true),
    SoloFormatError,
  );
  assert.throws(
    () =>
      parseNotice(
        { ...fixture.notice, noticeHash: "b".repeat(64) },
        fixture.story,
      ),
    SoloFormatError,
  );
});

test("solo state rejects coop, foreign keys, forged ready flags and unknown fields", () => {
  const fixture = soloFixture();
  assert.equal(parseState(fixture.state, fixture.gameKey).mode, "SOLO");
  for (const value of [
    { ...fixture.state, mode: "COOP" },
    { ...fixture.state, canReady: true },
    { ...fixture.state, gameKey: randomUUID() },
    { ...fixture.state, extra: true },
    { ...fixture.state, partner: { ...fixture.state.partner, joined: true } },
  ])
    assert.throws(() => parseState(value, fixture.gameKey), SoloFormatError);
});

test("materials require exactly two distinct fixed roles and strict private projections", () => {
  const fixture = soloFixture();
  start(fixture);
  assert.equal(
    parseMaterials(fixture.materials, fixture.gameKey).roles.length,
    2,
  );
  for (const change of [
    { roles: [fixture.materials.roles[0]] },
    { roles: [fixture.materials.roles[0], fixture.materials.roles[0]] },
    { answer: "secret" },
    { gameKey: randomUUID() },
    { playDeadline: "2000-01-01T00:00:00Z" },
  ])
    assert.throws(
      () =>
        parseMaterials({ ...fixture.materials, ...change }, fixture.gameKey),
      SoloFormatError,
    );
});

test("REPORT-1 preserves Unicode and whitespace and rejects too-large or changed wire fields", () => {
  const report = {
    culpritCode: "P1",
    method: "  원문\n🙂  ",
    time: "시간",
    motive: "동기",
    evidence: "근거",
  };
  assert.equal(parseReportBody(report).method, report.method);
  for (const change of [
    { method: "🙂".repeat(5001) },
    { evidence: "a\r\nb" },
    { time: "\x00" },
    { culpritCode: "../P1" },
    { extra: true },
  ])
    assert.throws(
      () => parseReportBody({ ...report, ...change }),
      SoloFormatError,
    );
});

test("unknown committed save retains exact body/key through reads and manual replay", async () => {
  const fixture = soloFixture();
  start(fixture);
  let lost = true;
  const bodies = [];
  const session = owner(async (method, path, body) => {
    if (body) bodies.push(JSON.stringify(body));
    const response = fixture.respond(method, path, body);
    if (method === "PATCH" && lost) {
      lost = false;
      throw new WebApiError();
    }
    return response.value;
  });
  const client = createGameClient(session, () => {});
  const report = {
    culpritCode: "P1",
    method: "원본",
    time: "시간",
    motive: "동기",
    evidence: "근거",
  };
  const body = { expectedRev: "1", expectedDraftRev: "0", report };
  await assert.rejects(
    client.send(
      "PATCH",
      "/api/games/" + fixture.gameKey + "/report",
      "PUBLIC_REPORT_EDIT",
      body,
      fixture.gameKey,
    ),
    WebApiError,
  );
  report.method = "나중 입력";
  await client.read("/api/games/" + fixture.gameKey, (value) =>
    parseState(value, fixture.gameKey),
  );
  assert.equal(client.pending().uncertain, true);
  assert.throws(() =>
    client.send(
      "POST",
      "/api/games/" + fixture.gameKey + "/forfeit",
      "PUBLIC_FORFEIT",
      { expectedRev: "2" },
      fixture.gameKey,
    ),
  );
  assert.equal((await client.replay()).replayed, true);
  assert.equal(bodies[0], bodies[1]);
  assert.equal(JSON.parse(bodies[1]).report.method, "원본");
  assert.equal(client.pending(), null);
  client.dispose();
});

test("later rejection cannot discard an already unknown intent", async () => {
  let count = 0;
  const client = createGameClient(
    owner(async () => {
      count += 1;
      throw new WebApiError(count === 1 ? 503 : 409);
    }),
    () => {},
  );
  await assert.rejects(
    client.send("POST", "/api/games", "PUBLIC_CREATE", {}),
    WebApiError,
  );
  await assert.rejects(client.replay(), WebApiError);
  assert.equal(client.pending().uncertain, true);
  assert.equal(count, 2);
  client.dispose();
});

test("account revocation rejects a late receipt and purges intent without restoring private response", async () => {
  const fixture = soloFixture();
  let finish;
  let purges = 0;
  const session = owner(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const client = createGameClient(session, () => {
    purges += 1;
  });
  const pending = client.send("POST", "/api/games", "PUBLIC_CREATE", {});
  session.revoke();
  finish(
    fixture.respond("POST", "/api/games", { requestKey: randomUUID() }).value,
  );
  await assert.rejects(pending);
  assert.equal(purges, 1);
  assert.equal(client.pending(), null);
  client.dispose();
});

test("result validates canonical record identity, real scoring and ungradable report metadata", () => {
  const fixture = soloFixture();
  start(fixture);
  fixture.complete();
  const result = fixture.result();
  assert.equal(parseResult(result, fixture.gameKey).finalScore, 90);
  for (const change of [
    { gameKey: randomUUID() },
    { rawState: "PURGED" },
    { mode: "COOP" },
    { ownHintsUsed: -1 },
    { scoreSummary: { ...result.scoreSummary, penalty: 10 } },
    { lastReportKey: randomUUID() },
  ])
    assert.throws(
      () => parseResult({ ...result, ...change }, fixture.gameKey),
      SoloFormatError,
    );
  const report = result.reports[0];
  const ungradable = {
    ...result,
    reports: [
      { ...report, state: "UNGRADABLE", baseScore: null, success: null },
    ],
  };
  assert.equal(
    parseResult(ungradable, fixture.gameKey).reports[0].state,
    "UNGRADABLE",
  );
  const forfeited = {
    ...result,
    outcome: "FORFEIT",
    finalScore: null,
    scoreSummary: null,
    revealText: null,
  };
  assert.equal(parseResult(forfeited, fixture.gameKey).finalScore, null);
});

test("submit receipts bind the action and original report/job to the same current game", () => {
  const fixture = soloFixture();
  start(fixture);
  const response = fixture.respond(
    "POST",
    "/api/games/" + fixture.gameKey + "/report/submit",
    { requestKey: randomUUID() },
  ).value;
  assert.equal(
    parseReceipt(response, "PUBLIC_REPORT_SUBMIT", fixture.gameKey).current
      .submissionState,
    "PENDING",
  );
  assert.throws(
    () => parseReceipt(response, "PUBLIC_START", fixture.gameKey),
    SoloFormatError,
  );
  assert.throws(
    () =>
      parseReceipt(
        { ...response, original: { ...response.original, jobKey: "invalid" } },
        "PUBLIC_REPORT_SUBMIT",
        fixture.gameKey,
      ),
    SoloFormatError,
  );
  assert.throws(
    () =>
      parseReport(
        {
          gameKey: randomUUID(),
          rev: "1",
          draftRev: "0",
          report: fixture.report,
          submissionState: "NONE",
          requestId: randomUUID(),
        },
        fixture.gameKey,
      ),
    SoloFormatError,
  );
});

test("completed HTTP-error JSON remains an error without aborting its consumed stream", async () => {
  let signal;
  const browser = {
    isSecureContext: true,
    location: { protocol: "https:", origin: "https://synthetic.invalid" },
    navigator: { serviceWorker: { controller: null } },
    async fetch(path, options) {
      signal = options.signal;
      return new Response(
        JSON.stringify({ code: "FRIENDLY_COLLECTION_NOT_READY" }),
        {
          status: 503,
          headers: {
            "Content-Type": "application/json",
            "Cache-Control": "no-store",
          },
        },
      );
    },
  };
  await assert.rejects(
    createWebApi(browser).request("GET", "/api/stories", {
      token: "A".repeat(43),
    }),
    (failure) =>
      failure instanceof WebApiError &&
      failure.status === 503 &&
      failure.code === "FRIENDLY_COLLECTION_NOT_READY",
  );
  assert.equal(signal.aborted, false);
});

test("private read dependency failure purges displayed data but does not discard an unknown command", async () => {
  let purges = 0;
  const client = createGameClient(
    owner(async () => {
      throw new WebApiError(503);
    }),
    () => {
      purges += 1;
    },
  );
  await assert.rejects(
    client.send("POST", "/api/games", "PUBLIC_CREATE", {}),
    WebApiError,
  );
  await assert.rejects(
    client.read("/api/games/current", (value) => value),
    WebApiError,
  );
  assert.equal(purges, 1);
  assert.equal(client.pending().uncertain, true);
  client.dispose();
});
