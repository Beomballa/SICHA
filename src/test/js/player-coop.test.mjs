import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { test } from "node:test";
import {
  parseState,
  parseMaterials,
  parseCoopReport,
  parseReceipt,
  SoloFormatError,
} from "../../main/resources/static/player-common/game-model.mjs";
import { soloFixture } from "../browser/solo-fixture.mjs";

/** 기존 합성 사본에서 협동의 고정 슬롯/역할 경계만 구성한다. */
function coop() {
  const fixture = soloFixture();
  const state = {
    ...fixture.state,
    mode: "COOP",
    state: "RUNNING",
    canStart: false,
    canReady: false,
    startedAt: new Date().toISOString(),
    playDeadline: fixture.materials.playDeadline,
    attemptsRemaining: 5,
    hintsRemaining: 3,
    self: { slot: 2, ready: true, effectiveReady: true, roleCode: "R2" },
    host: false,
    partner: { joined: true, ready: true, effectiveReady: true, online: true },
  };
  return { ...fixture, state };
}

test("coop projections accept only the fixed current role and never both roles or the partner role", () => {
  const fixture = coop();
  assert.equal(parseState(fixture.state, fixture.gameKey, "COOP").self.slot, 2);
  const material = {
    ...fixture.materials,
    roles: [fixture.materials.roles[1]],
  };
  assert.equal(
    parseMaterials(material, fixture.gameKey, "COOP", "R2").roles.length,
    1,
  );
  for (const roles of [
    fixture.materials.roles,
    [fixture.materials.roles[0]],
    [],
    [fixture.materials.roles[1], fixture.materials.roles[1]],
  ])
    assert.throws(
      () =>
        parseMaterials({ ...material, roles }, fixture.gameKey, "COOP", "R2"),
      SoloFormatError,
    );
  assert.throws(
    () => parseState(fixture.state, fixture.gameKey),
    SoloFormatError,
  );
});

test("fixed proposals bind draft identity, payload and proposer/opponent permissions", () => {
  const fixture = coop();
  const report = {
    culpritCode: "P1",
    method: "방법",
    time: "시간",
    motive: "동기",
    evidence: "증거",
  };
  fixture.state.submissionState = "PROPOSED";
  const view = {
    gameKey: fixture.gameKey,
    draftRev: "0",
    report,
    submissionState: "PROPOSED",
    proposal: {
      reportKey: randomUUID(),
      sourceRev: "0",
      report: { ...report },
      proposerSlot: 1,
      state: "PROPOSED",
      canAccept: true,
      canReject: true,
      canWithdraw: false,
    },
    pending: null,
    requestId: randomUUID(),
  };
  assert.equal(parseCoopReport(view, fixture.state).proposal.canAccept, true);
  for (const change of [
    { sourceRev: "1" },
    { canWithdraw: true },
    { proposerSlot: 2 },
    { report: { ...report, method: "다른 원문" } },
  ])
    assert.throws(
      () =>
        parseCoopReport(
          { ...view, proposal: { ...view.proposal, ...change } },
          fixture.state,
        ),
      SoloFormatError,
    );
});

test("invitation replay cannot return the original secret and leave does not restore a current room", () => {
  const fixture = coop();
  fixture.state.state = "WAITING";
  fixture.state.startedAt =
    fixture.state.playDeadline =
    fixture.state.attemptsRemaining =
    fixture.state.hintsRemaining =
      null;
  fixture.state.self.roleCode = null;
  fixture.state.submissionState = "NONE";
  const key = randomUUID();
  const receipt = {
    action: "PUBLIC_INVITE",
    replayed: true,
    changed: false,
    original: { gameKey: fixture.gameKey, rev: "0", inviteKey: key },
    current: fixture.state,
    requestId: randomUUID(),
    requiresReissue: true,
    blockedReason: null,
  };
  assert.equal(
    parseReceipt(receipt, "PUBLIC_INVITE", fixture.gameKey, "COOP")
      .requiresReissue,
    true,
  );
  assert.throws(
    () =>
      parseReceipt(
        {
          ...receipt,
          inviteKey: key,
          inviteSecret: "A".repeat(43),
          expiresAt: fixture.materials.playDeadline,
        },
        "PUBLIC_INVITE",
        fixture.gameKey,
        "COOP",
      ),
    SoloFormatError,
  );
  const leave = {
    action: "PUBLIC_LEAVE",
    replayed: false,
    changed: true,
    original: { gameKey: fixture.gameKey, rev: "0" },
    current: null,
    requestId: randomUUID(),
  };
  assert.equal(
    parseReceipt(leave, "PUBLIC_LEAVE", fixture.gameKey, "COOP").current,
    null,
  );
});

/** 실제 classic bootstrap만 합성 주소 환경에서 실행하고 호출 순서를 관측한다. */
function capture(hash, query = "", fail = false) {
  const location = { pathname: "/coop-invite", hash, search: query };
  const events = [];
  const window = {
    location,
    history: {
      replaceState(state, unused, path) {
        events.push(path);
        if (fail) throw new Error("synthetic");
        location.hash = location.search = "";
      },
    },
    addEventListener() {},
  };
  const document = { addEventListener() {} };
  const script = readFileSync(
    new URL(
      "../../main/resources/static/player-common/invite-bootstrap.js",
      import.meta.url,
    ),
    "utf8",
  );
  vm.runInNewContext(script, { window, document });
  return { window, events };
}

test("invitation bootstrap strips history before one-time access, including malformed links", () => {
  const key = randomUUID();
  const raw = "#inviteKey=" + key + "&inviteSecret=" + "A".repeat(43);
  const valid = capture(raw);
  assert.deepEqual(valid.events, ["/coop-invite"]);
  assert.equal(valid.window.location.hash, "");
  assert.equal(valid.window.takeCoopInvitation().inviteKey, key);
  assert.equal(valid.window.takeCoopInvitation(), null);
  for (const [hash, query] of [
    [raw + "&extra=1", ""],
    [raw, "?extra=1"],
    [raw.replace(/A$/, "B"), ""],
    ["#invalid", ""],
  ]) {
    const invalid = capture(hash, query);
    assert.equal(invalid.window.location.hash, "");
    assert.equal(invalid.window.takeCoopInvitation().invalid, true);
  }
  assert.throws(() => capture(raw, "", true).window.takeCoopInvitation());
});
