import puppeteer from "puppeteer-core";
import assert from "node:assert/strict";
import https from "node:https";
import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";

// 합성 계정·자격은 stdin과 프로세스 메모리에만 두며 stdout에는 정수 증거만 남긴다.
const chunks = [];
for await (const chunk of process.stdin) chunks.push(chunk);
const fixture = JSON.parse(Buffer.concat(chunks).toString("utf8"));
assert.ok(fixture.mode === undefined || fixture.mode === "report");
const reportMode = fixture.mode === "report";
const origin = new URL(fixture.origin);
assert.equal(origin.protocol, "https:");
assert.equal(origin.hostname, "localhost");
assert.equal(origin.origin, fixture.origin);
assert.notEqual(origin.port, "18443");
assert.ok(Number(origin.port) >= 1024 && Number(origin.port) <= 65535);
assert.equal(fixture.accounts.length, 2);
const UUID4 =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
assert.match(fixture.testKey, UUID4);
const AUTH = "/api/member/browser-auth";
const BASE = "/api/playtests/" + fixture.testKey;
const MARKER = "sicha.player.auth.v1";
const counters = {
  login: 0,
  logout: 0,
  accept: 0,
  ready: 0,
  start: 0,
  hint: 0,
  heartbeat: 0,
  materials: 0,
  validatedMaterials: 0,
  staleMaterialsDenied: 0,
  droppedAccept: 0,
  originalReplay: 0,
  boundaryProbes: 0,
  backgroundHidden: 0,
  sameRoleRecovered: 0,
  accountPurged: 0,
  runtimeErrors: 0,
  excludedCalls: 0,
};
if (reportMode)
  Object.assign(counters, {
    reportEdit: 0,
    reportPropose: 0,
    reportAccept: 0,
    feedback: 0,
    reportHidden: 0,
    feedbackHidden: 0,
    reportReplay: 0,
    mutationDropStatus: 0,
    mutationDropPaused: 0,
    feedbackReplay: 0,
    ownFeedbackIsolation: 0,
    freshResult: 0,
    oldOwnerDenied: 0,
    revokedResult: 0,
    revokedMutation: 0,
  });
let phase = "launch";
let browser;
const memberBrowsers = [];
const failures = [];
const pending = new Set();
const viewStates = [];
const signals = new EventEmitter();
const deadline = setTimeout(
  () => {
    process.stderr.write(
      JSON.stringify({ ok: false, phase, failure: "deadline" }),
    );
    process.exit(1);
  },
  reportMode ? 240000 : 140000,
);

/** 보고서 모드의 추가 허용은 정확한 일곱 경로·동사 조합뿐이다. */
function reportRoute(path, method) {
  if (!reportMode) return null;
  if (path === BASE + "/report" && method === "GET") return "reportRead";
  if (path === BASE + "/report" && method === "PATCH") return "reportEdit";
  if (path === BASE + "/report/proposals" && method === "POST")
    return "reportPropose";
  if (
    method === "POST" &&
    path.startsWith(BASE + "/report/proposals/") &&
    path.endsWith("/respond") &&
    UUID4.test(path.slice((BASE + "/report/proposals/").length, -8))
  )
    return "reportAccept";
  if (path === BASE + "/forfeit" && method === "POST") return "forfeit";
  if (path === BASE + "/result" && method === "GET") return "resultRead";
  if (path === BASE + "/feedback" && method === "POST") return "feedback";
  return null;
}

/** 정규 십진 revision은 JS 숫자로 축약하지 않고 Long 범위를 검사한다. */
function revision(value) {
  assert.equal(typeof value, "string");
  assert.match(value, /^(0|[1-9][0-9]*)$/);
  assert.ok(BigInt(value) <= 9223372036854775807n);
}

/** body는 닫힌 필드·원문 바이트·Unicode 글자 상한을 모두 만족해야 한다. */
function reportBody(kind, body) {
  const fields = {
    reportEdit: ["expectedRev", "expectedDraftRev", "report", "requestKey"],
    reportPropose: ["expectedRev", "expectedDraftRev", "requestKey"],
    reportAccept: ["expectedRev", "decision", "requestKey"],
    forfeit: ["expectedRev", "requestKey"],
    feedback: ["requestKey", "feedback"],
  };
  assert.deepEqual(Object.keys(body).sort(), fields[kind].sort());
  assert.match(body.requestKey, UUID4);
  if (kind !== "feedback") revision(body.expectedRev);
  if (kind === "reportEdit" || kind === "reportPropose")
    revision(body.expectedDraftRev);
  if (kind === "reportAccept")
    assert.ok(["ACCEPT", "REJECT", "WITHDRAW"].includes(body.decision));
  if (kind === "reportEdit") {
    reportData(body.report);
    assert.ok(
      Buffer.byteLength(JSON.stringify(body.report), "utf8") <= 128 * 1024,
    );
  }
  if (kind === "feedback") {
    assert.deepEqual(
      Object.keys(body.feedback).sort(),
      ["blockedAt", "roleContribution", "fairness", "gradingConcern"].sort(),
    );
    for (const value of Object.values(body.feedback)) {
      assert.equal(typeof value, "string");
      assert.ok([...value].length <= 2000);
    }
  }
}

/** REPORT-1의 다섯 필드와 원문 상한을 검사한다. */
function reportData(value) {
  assert.deepEqual(
    Object.keys(value).sort(),
    ["culpritCode", "method", "time", "motive", "evidence"].sort(),
  );
  assert.ok(
    value.culpritCode === null || typeof value.culpritCode === "string",
  );
  let total = 0;
  for (const field of ["method", "time", "motive", "evidence"]) {
    assert.equal(typeof value[field], "string");
    assert.ok([...value[field]].length <= 5000);
    total += [...value[field]].length;
  }
  assert.ok(total <= 20000);
}

/** 피드백 전 투영에는 점수·제출본·해설 값이 하나도 없어야 한다. */
function protectedResult(value) {
  assert.equal(value.resultPhase, "AWAITING_FEEDBACK");
  assert.equal(value.feedbackSubmitted, false);
  for (const name of ["finalScore", "scoreSummary", "reports", "revealText"])
    assert.equal(value[name], null);
}

/** 현재 서비스가 허용한 결과만 검증하며 점수는 caller가 만들지 않는다. */
function resultView(value) {
  assert.deepEqual(
    Object.keys(value).sort(),
    [
      "testKey",
      "state",
      "outcome",
      "endedAt",
      "feedbackUntil",
      "resultPhase",
      "feedbackSubmitted",
      "finalScore",
      "scoreSummary",
      "reports",
      "revealText",
      "requestId",
    ].sort(),
  );
  assert.equal(value.testKey, fixture.testKey);
  assert.equal(value.state, "ENDED");
  assert.equal(value.outcome, "SUCCESS");
  assert.match(value.requestId, UUID4);
  if (value.resultPhase === "AWAITING_FEEDBACK") return protectedResult(value);
  assert.equal(value.resultPhase, "AVAILABLE");
  assert.equal(value.feedbackSubmitted, true);
  const summary = value.scoreSummary;
  assert.deepEqual(
    Object.keys(summary).sort(),
    ["baseScore", "wrongCount", "penalty", "finalScore", "categories"].sort(),
  );
  for (const field of ["baseScore", "wrongCount", "penalty", "finalScore"])
    assert.ok(Number.isInteger(summary[field]) && summary[field] >= 0);
  assert.equal(value.finalScore, summary.finalScore);
  assert.ok(value.finalScore <= 100);
  for (const category of summary.categories) {
    assert.deepEqual(Object.keys(category).sort(), [
      "category",
      "maxScore",
      "score",
    ]);
    assert.ok(Number.isInteger(category.score) && category.score >= 0);
    assert.ok(
      Number.isInteger(category.maxScore) &&
        category.maxScore >= category.score,
    );
  }
  assert.equal(value.reports.length, 1);
  const submitted = value.reports[0];
  assert.deepEqual(
    Object.keys(submitted).sort(),
    ["reportKey", "submitNo", "report", "baseScore", "success"].sort(),
  );
  assert.match(submitted.reportKey, UUID4);
  assert.equal(submitted.submitNo, 1);
  assert.equal(submitted.baseScore, summary.baseScore);
  assert.equal(submitted.success, true);
  assert.deepEqual(submitted.report, fixture.report);
  assert.equal(typeof value.revealText, "string");
  assert.ok(value.revealText.length > 0);
}

/** 관측 상태가 바뀔 때만 조건을 확인하고 최대 12초 뒤 고정 오류로 종료한다. */
function signalState(predicate) {
  return new Promise((resolve, reject) => {
    const check = () => {
      if (!predicate()) return;
      clearTimeout(timer);
      signals.off("change", check);
      resolve();
    };
    const timer = setTimeout(() => {
      signals.off("change", check);
      reject(new Error("Observation deadline"));
    }, 12000);
    signals.on("change", check);
    check();
  });
}

/** 비밀 원문 없이 관측 작업의 실패를 수집하고 즉시 rejection을 처리한다. */
function track(work, kind) {
  const task = work.catch(() => failures.push("observation-" + kind));
  pending.add(task);
  void task.then(() => {
    pending.delete(task);
    signals.emit("change");
  });
  return task;
}

/** Flutter 요청과 별개의 실제 loopback HTTPS 경계·현재 투영 조회다. */
function probe(path, method, headers, body) {
  counters.boundaryProbes++;
  const payload = body === undefined ? undefined : Buffer.from(body, "utf8");
  return new Promise((resolve, reject) => {
    const request = https.request(
      new URL(path, fixture.origin),
      {
        method,
        hostname: "127.0.0.1",
        servername: "localhost",
        rejectUnauthorized: false,
        timeout: 8000,
        headers: {
          Host: origin.host,
          "Content-Type": "application/json",
          "Content-Length": payload?.length ?? 0,
          ...headers,
        },
      },
      (response) => {
        const parts = [];
        let bytes = 0;
        response.on("data", (part) => {
          bytes += part.length;
          if (bytes > 1024 * 1024) request.destroy(new Error("Probe bound"));
          else parts.push(part);
        });
        response.on("error", () => reject(new Error("Probe response failed")));
        response.on("end", () => {
          try {
            assert.match(
              response.headers["content-type"] ?? "",
              /^application\/json(?:;|$)/i,
            );
            const value = JSON.parse(Buffer.concat(parts).toString("utf8"));
            assert.ok(
              value && typeof value === "object" && !Array.isArray(value),
            );
            resolve({ status: response.statusCode, value });
          } catch {
            reject(new Error("Probe JSON object required"));
          }
        });
      },
    );
    request.on("timeout", () => request.destroy(new Error("Probe deadline")));
    request.on("error", () => reject(new Error("Probe transport failed")));
    request.end(payload);
  });
}

function bearerHeaders(state) {
  return {
    Authorization: "Bearer " + state.access,
    "X-Sicha-Player-Web": "1",
    "Sec-Fetch-Site": "same-origin",
  };
}

/** 자격·판정 원문이 섞이지 않은 실제 역할 투영을 서버의 현재 투영과 비교한다. */
async function validateMaterials(state, value, owner) {
  assert.deepEqual(
    Object.keys(value).sort(),
    [
      "basic",
      "role",
      "persons",
      "clues",
      "openedHints",
      "requiredNotices",
      "requestId",
    ].sort(),
  );
  assert.match(value.requestId, UUID4);
  const headers = bearerHeaders({ access: owner.access });
  const current = await probe(BASE, "GET", headers);
  // 응답 뒤 실제 로그아웃이 완료되면 원래 발급 자격의 현재 거절을 검증하고 화면 관측으로 재사용하지 않는다.
  if (current.status === 401 && phase === "logout-account-change-purge") {
    assert.equal(value.role.code, owner.roleCode);
    counters.staleMaterialsDenied++;
    return;
  }
  assert.equal(current.status, 200);
  assert.equal(value.role.code, current.value.self.roleCode);
  assert.ok(["R1", "R2"].includes(value.role.code));
  assert.equal(value.clues.length, 1);
  assert.equal(value.clues[0].code, value.role.code === "R1" ? "C1" : "C2");
  assert.deepEqual(Object.keys(value.role).sort(), ["brief", "code", "name"]);
  for (const clue of value.clues)
    assert.deepEqual(Object.keys(clue).sort(), [
      "body",
      "code",
      "personCode",
      "title",
    ]);
  for (const person of value.persons)
    assert.deepEqual(Object.keys(person).sort(), [
      "code",
      "name",
      "publicText",
    ]);
  for (const hint of value.openedHints) {
    assert.deepEqual(Object.keys(hint).sort(), ["body", "level"]);
    assert.ok(
      Number.isInteger(hint.level) && hint.level >= 1 && hint.level <= 3,
    );
    assert.equal(typeof hint.body, "string");
  }
  const serialized = JSON.stringify(value);
  for (const excluded of [
    "secretText",
    "sourceText",
    "gradeSamples",
    "ruleData",
    "facts",
    "rubrics",
  ])
    assert.equal(serialized.includes('"' + excluded + '"'), false);
  const projection = await probe(BASE + "/materials", "GET", headers);
  if (projection.status === 401 && phase === "logout-account-change-purge") {
    assert.equal(value.role.code, owner.roleCode);
    counters.staleMaterialsDenied++;
    return;
  }
  assert.equal(projection.status, 200);
  // 힌트는 관측 사이 새로 열릴 수 있으므로 역할 원문을 엄격히 비교하고 기존 힌트는 부분집합으로 검사한다.
  for (const key of ["basic", "role", "persons", "clues", "requiredNotices"])
    assert.deepEqual(value[key], projection.value[key]);
  for (const hint of value.openedHints)
    assert.deepEqual(
      hint,
      projection.value.openedHints.find((item) => item.level === hint.level),
    );
  state.material = value;
  state.materialCount++;
  counters.validatedMaterials++;
}

/** 원래 Fetch 인자·옵션·Response를 바꾸지 않고 복제 스트림만 제한해 관측한다. */
async function instrument(page, account) {
  const state = {
    page,
    account,
    access: null,
    detail: null,
    detailCount: 0,
    notice: null,
    material: null,
    materialCount: 0,
    requests: [],
    receipts: [],
    observations: 0,
    report: null,
    result: null,
    resultCount: 0,
    network: new Set(),
  };
  const observations = new Map();
  let sequence = 0;
  page.setDefaultTimeout(12000);
  page.setDefaultNavigationTimeout(20000);
  page.on("pageerror", () => {
    counters.runtimeErrors++;
  });
  for (const event of ["requestfinished", "requestfailed"])
    page.on(event, (request) => {
      state.network.delete(request);
      signals.emit("change");
    });
  page.on("response", (response) => {
    if (
      new URL(response.url()).pathname === BASE + "/heartbeat" &&
      response.status() !== 204
    )
      failures.push("heartbeat-response-contract");
  });
  page.on("request", (request) => {
    let check = "origin";
    try {
      const url = new URL(request.url());
      if (url.protocol === "about:") return;
      assert.equal(url.origin, fixture.origin);
      if (!url.pathname.startsWith("/api/")) return;
      state.network.add(request);
      const headers = request.headers();
      const path = url.pathname;
      check = "web-header";
      assert.equal(headers["x-sicha-player-web"], "1");
      assert.equal(url.search, "");
      const method = request.method();
      const reportKind = reportRoute(path, method);
      if (path.startsWith(AUTH + "/")) {
        assert.equal(method, "POST");
        assert.match(headers["x-sicha-player-web-epoch"], UUID4);
        assert.equal(headers.authorization, undefined);
        // 자동 Origin은 Puppeteer의 초기 헤더가 아니라 CDP extraInfo에서 검사한다.
        check = "cookie-body";
        const value = JSON.parse(request.postData() ?? "null");
        if (path === AUTH + "/login/local") {
          counters.login++;
          assert.deepEqual(value, {
            email: state.account.email,
            password: fixture.password,
          });
        } else if (path === AUTH + "/logout") {
          counters.logout++;
          assert.deepEqual(Object.keys(value), ["requestKey"]);
          assert.match(value.requestKey, UUID4);
        } else {
          assert.equal(path, AUTH + "/refresh");
          assert.deepEqual(value, {});
        }
        return;
      }
      assert.match(headers.authorization ?? "", /^Bearer [A-Za-z0-9_-]{43}$/);
      if (state.access !== null)
        assert.equal(headers.authorization, "Bearer " + state.access);
      const reads = [
        "/api/member/auth/me",
        "/api/playtests/identity",
        "/api/playtests/invitations",
        BASE,
        BASE + "/policy-notice",
        BASE + "/materials",
        BASE + "/report",
        BASE + "/result",
      ];
      const mutations = [
        BASE + "/accept",
        BASE + "/ready",
        BASE + "/start",
        BASE + "/heartbeat",
        BASE + "/hints/1/open",
        BASE + "/hints/2/open",
        BASE + "/hints/3/open",
      ];
      if (
        !(
          (method === "GET" && reads.includes(path)) ||
          (method === "POST" && mutations.includes(path)) ||
          reportKind !== null
        )
      ) {
        counters.excludedCalls++;
        throw new Error("Excluded Flutter route");
      }
      assert.equal(headers.cookie, undefined);
      if (method === "GET") assert.equal(request.postData(), undefined);
      if (method === "POST" || method === "PATCH") {
        const body = request.postData();
        const cap =
          reportKind === "reportEdit"
            ? 512 * 1024
            : reportKind === "feedback"
              ? 32 * 1024
              : reportKind
                ? 8 * 1024
                : 16 * 1024;
        assert.ok(Buffer.byteLength(body ?? "", "utf8") <= cap);
        const value = JSON.parse(body);
        state.requests.push({ url: request.url(), path, method, body, value });
        if (reportKind) {
          assert.match(
            headers["content-type"] ?? "",
            /^application\/json(?:;|$)/i,
          );
          reportBody(reportKind, value);
          if (Object.hasOwn(counters, reportKind)) counters[reportKind]++;
        }
        if (path === BASE + "/accept") counters.accept++;
        if (path === BASE + "/ready") counters.ready++;
        if (path === BASE + "/start") counters.start++;
        if (path === BASE + "/heartbeat") {
          counters.heartbeat++;
          assert.deepEqual(value, {});
        }
        if (path.includes("/hints/")) counters.hint++;
      }
    } catch {
      failures.push("flutter-request-" + check);
    }
    signals.emit("change");
  });
  await page.exposeFunction("__investigationBegin", (path, options) => {
    const id = ++sequence;
    let finish;
    const completion = new Promise((resolve) => {
      finish = resolve;
    });
    track(completion);
    const timer = setTimeout(() => {
      failures.push("clone-observation-deadline");
      observations.delete(id);
      finish();
    }, 12000);
    observations.set(id, {
      finish,
      timer,
      owner: { access: state.access, roleCode: state.detail?.self?.roleCode },
    });
    if (path === BASE + "/materials") counters.materials++;
    try {
      assert.equal(options.mode, "same-origin");
      assert.equal(options.redirect, "error");
      assert.equal(options.cache, "no-store");
      assert.equal(
        options.credentials,
        path.startsWith(AUTH + "/") ? "same-origin" : "omit",
      );
    } catch {
      failures.push("native-fetch-options");
    }
    return id;
  });
  await page.exposeFunction("__investigationClone", (id, observation) => {
    const entry = observations.get(id);
    if (!entry) {
      failures.push("clone-observation-unmatched");
      return;
    }
    return track(
      (async () => {
        assert.equal(observation.ok, true);
        assert.ok(Buffer.byteLength(observation.text, "utf8") <= 1024 * 1024);
        assert.equal(observation.cacheControl, "no-store");
        const value = JSON.parse(observation.text);
        assert.ok(value && typeof value === "object" && !Array.isArray(value));
        const path = observation.path;
        if (path === AUTH + "/login/local" || path === AUTH + "/refresh") {
          assert.deepEqual(
            Object.keys(value).sort(),
            [
              "tokenType",
              "accessToken",
              "accessExpiresAt",
              "sessionKey",
              "sessionAbsoluteExpiresAt",
              "webEpoch",
              "requestId",
            ].sort(),
          );
          assert.equal(value.tokenType, "Bearer");
          assert.match(value.accessToken, /^[A-Za-z0-9_-]{43}$/);
          assert.match(value.webEpoch, UUID4);
          assert.match(value.sessionKey, UUID4);
          state.access = value.accessToken;
        } else if (path === "/api/playtests/identity") {
          assert.equal(value.memberKey, state.account.memberKey);
        } else if (path === "/api/playtests/invitations") {
          if (
            phase === "logout-account-change-purge" ||
            phase.startsWith("report-account-")
          ) {
            // 실제 목록 계약은 WAITING만 반환하며 이미 RUNNING인 초대는 없다.
            assert.equal(value.items.length, 0);
          } else {
            assert.equal(value.items.length, 1);
            assert.equal(value.items[0].testKey, fixture.testKey);
          }
        } else if (path === BASE) {
          assert.equal(value.testKey, fixture.testKey);
          assert.equal(typeof value.self.accepted, "boolean");
          assert.deepEqual(Object.keys(value.partner).sort(), [
            "accepted",
            "online",
            "ready",
          ]);
          state.detail = value;
          state.detailCount++;
        } else if (path === BASE + "/policy-notice") {
          assert.equal(value.testKey, fixture.testKey);
          assert.equal(typeof value.revision, "string");
          state.notice = value;
        } else if (path === BASE + "/materials") {
          await validateMaterials(state, value, entry.owner);
        } else if (
          reportMode &&
          path === BASE + "/report" &&
          observation.method === "GET"
        ) {
          assert.deepEqual(
            Object.keys(value).sort(),
            [
              "testKey",
              "draftRev",
              "report",
              "proposal",
              "submissionState",
              "requestId",
            ].sort(),
          );
          assert.equal(value.testKey, fixture.testKey);
          revision(value.draftRev);
          reportData(value.report);
          assert.match(value.requestId, UUID4);
          if (value.proposal !== null) {
            assert.deepEqual(
              Object.keys(value.proposal).sort(),
              [
                "reportKey",
                "sourceRev",
                "proposerSlot",
                "state",
                "canAccept",
                "canReject",
                "canWithdraw",
              ].sort(),
            );
            assert.match(value.proposal.reportKey, UUID4);
            revision(value.proposal.sourceRev);
          }
          state.report = value;
        } else if (reportMode && path === BASE + "/result") {
          resultView(value);
          state.result = value;
          state.resultCount++;
        } else if (reportRoute(path, observation.method) !== null) {
          const kind = reportRoute(path, observation.method);
          assert.deepEqual(
            Object.keys(value).sort(),
            [
              "action",
              "changed",
              "replayed",
              "original",
              "current",
              "requestId",
            ].sort(),
          );
          const expected = {
            reportEdit: "REPORT_EDIT",
            reportPropose: "REPORT_PROPOSE",
            reportAccept:
              "REPORT_" +
              state.requests.findLast((item) => item.path === path)?.value
                .decision,
            feedback: "TEST_FEEDBACK",
            forfeit: "TEST_FORFEIT",
          }[kind];
          assert.equal(value.action, expected);
          assert.equal(typeof value.changed, "boolean");
          assert.equal(typeof value.replayed, "boolean");
          assert.match(value.requestId, UUID4);
          for (const safe of [value.original, value.current]) {
            for (const field of Object.keys(safe))
              assert.ok(
                [
                  "testKey",
                  "rev",
                  "state",
                  "outcome",
                  "startedAt",
                  "playDeadline",
                  "draftRev",
                  "reportKey",
                  "submitNo",
                  "acceptedAt",
                  "gradeDeadline",
                  "jobKey",
                  "feedbackSubmitted",
                ].includes(field),
              );
            assert.equal(safe.testKey, fixture.testKey);
            revision(safe.rev);
            if (safe.submitNo !== undefined) {
              assert.equal(typeof safe.submitNo, "string");
              assert.match(safe.submitNo, /^[1-9][0-9]*$/);
              assert.ok(BigInt(safe.submitNo) <= 2147483647n);
            }
          }
          state.receipts.push({ path, value });
        } else if (
          [
            BASE + "/accept",
            BASE + "/ready",
            BASE + "/start",
            BASE + "/hints/1/open",
          ].includes(path)
        ) {
          assert.deepEqual(
            Object.keys(value).sort(),
            [
              "action",
              "changed",
              "replayed",
              "original",
              "current",
              "requestId",
            ].sort(),
          );
          assert.equal(typeof value.changed, "boolean");
          assert.equal(typeof value.replayed, "boolean");
          assert.match(value.requestId, UUID4);
          const action = path.endsWith("/accept")
            ? "INVITATION_ACCEPT"
            : path.endsWith("/ready")
              ? "TEST_READY"
              : path.endsWith("/start")
                ? "TEST_START"
                : "HINT_OPEN";
          assert.equal(value.action, action);
          assert.equal(value.current.testKey, fixture.testKey);
          assert.equal(value.original.testKey, fixture.testKey);
          if (path !== BASE + "/accept") {
            assert.equal(value.changed, true);
            assert.equal(value.replayed, false);
          }
          state.receipts.push({ path, value });
        }
        state.observations++;
        signals.emit("change");
      })().finally(() => {
        clearTimeout(entry.timer);
        observations.delete(id);
        entry.finish();
      }),
      observation.path.startsWith(AUTH + "/")
        ? "cookie"
        : observation.path === "/api/playtests/identity"
          ? "identity"
          : observation.path === "/api/playtests/invitations"
            ? "list"
            : observation.path === BASE
              ? "state"
              : observation.path.endsWith("/policy-notice")
                ? "notice"
                : observation.path.endsWith("/materials")
                  ? "materials"
                  : "receipt",
    );
  });
  await page.evaluateOnNewDocument(() => {
    const nativeFetch = window.fetch;
    window.fetch = async function (...args) {
      const response = await Reflect.apply(nativeFetch, this, args);
      const url = new URL(response.url);
      if (
        url.origin === location.origin &&
        url.pathname.startsWith("/api/") &&
        (response.status === 200 || response.status === 201)
      ) {
        const options = args[1];
        const id = await window.__investigationBegin(url.pathname, {
          mode: options?.mode,
          redirect: options?.redirect,
          cache: options?.cache,
          credentials: options?.credentials,
        });
        let clone;
        try {
          clone = response.clone();
        } catch {
          await window.__investigationClone(id, { ok: false });
          return response;
        }
        // 본래 응답은 그대로 반환하고 관측 복제본만 별도 제한 스트림으로 읽는다.
        const observation = (async () => {
          const reader = clone.body.getReader();
          const parts = [];
          let length = 0;
          try {
            for (;;) {
              const { done, value } = await reader.read();
              if (done) break;
              length += value.byteLength;
              if (length > 1024 * 1024) {
                void reader.cancel().catch(() => {});
                throw new Error("Clone bound");
              }
              parts.push(value);
            }
            const bytes = new Uint8Array(length);
            let offset = 0;
            for (const part of parts) {
              bytes.set(part, offset);
              offset += part.length;
            }
            await window.__investigationClone(id, {
              ok: true,
              path: url.pathname,
              method: options?.method ?? "GET",
              cacheControl: response.headers.get("cache-control"),
              text: new TextDecoder("utf-8", { fatal: true }).decode(bytes),
            });
          } finally {
            reader.releaseLock();
          }
        })().catch(() =>
          window.__investigationClone(id, { ok: false }).catch(() => {}),
        );
        // 발급 관측은 다음 /me 전에 완료하고 자료 복제 관측은 비동기로 유지한다.
        if (url.pathname.startsWith("/api/member/browser-auth/"))
          await observation;
      }
      return response;
    };
  });
  const client = await page.createCDPSession();
  await client.send("Network.enable", {
    maxTotalBufferSize: 65536,
    maxResourceBufferSize: 16384,
    maxPostDataSize: 0,
  });
  const routes = new Map();
  const extras = new Map();
  const inspect = (id) => {
    const path = routes.get(id);
    const headers = extras.get(id);
    if (!path?.startsWith("/api/") || !headers) return;
    const normalized = Object.fromEntries(
      Object.entries(headers).map(([key, value]) => [key.toLowerCase(), value]),
    );
    if (normalized["sec-fetch-site"] !== "same-origin")
      failures.push("actual-fetch-site");
    if (normalized["x-sicha-player-web"] !== "1")
      failures.push("actual-web-header");
    if (path.startsWith(AUTH + "/") && normalized.origin !== fixture.origin)
      failures.push("actual-cookie-origin");
    if (!path.startsWith(AUTH + "/") && normalized.cookie !== undefined)
      failures.push("actual-bearer-cookie");
    routes.delete(id);
    extras.delete(id);
  };
  client.on("Network.requestWillBeSent", ({ requestId, request }) => {
    routes.set(requestId, new URL(request.url).pathname);
    inspect(requestId);
  });
  client.on("Network.requestWillBeSentExtraInfo", ({ requestId, headers }) => {
    extras.set(requestId, headers);
    inspect(requestId);
  });
  state.client = client;
  return state;
}

/** 조작은 foreground의 실제 Flutter semantics에만 전달한다. */
async function semantics(page) {
  await page.bringToFront();
  await page.waitForSelector("flutter-view");
  const placeholder = await page.$("flt-semantics-placeholder");
  if (placeholder) await placeholder.evaluate((element) => element.click());
  await page.waitForSelector("flt-semantics");
}

async function route(page, expected) {
  await page.bringToFront();
  await page.waitForFunction(
    (value) =>
      location.hash === "#/" + value || location.pathname.endsWith("/" + value),
    {},
    expected,
  );
  await semantics(page);
}

async function button(page, label) {
  const recovery = [
    "서버 상태 다시 조회",
    "원본 요청 다시 보내기",
    "현재 초안 제안",
    "제안 수락 및 판정 접수",
  ].includes(label);
  const ownerPhase = phase;
  if (recovery) phase = ownerPhase + "-foreground";
  await page.bringToFront();
  if (recovery) phase = ownerPhase + "-reveal";
  await reveal(page, `aria/${label}[role="button"]`, true);
  if (recovery) phase = ownerPhase + "-enabled-locator";
  const control = await page
    .locator(`aria/${label}[role="button"]`)
    .filterHandle((handle) =>
      handle.evaluate(
        (element) =>
          element.isConnected &&
          element.getAttribute("aria-disabled") !== "true",
      ),
    )
    .waitHandle();
  try {
    if (recovery) phase = ownerPhase + "-native-click";
    await control.click();
    if (recovery) phase = ownerPhase + "-activated";
  } finally {
    await control.dispose();
  }
}

/** 가상화된 화면은 실제 휠 입력으로 찾아야 하며 보이지 않는 요소를 만들어 내지 않는다. */
async function reveal(page, selector, requireEnabled = false) {
  const viewport = page.viewport();
  const visible = async () => {
    let element = await page.$(selector);
    if (element === null && selector.startsWith("text/")) {
      const candidate = await page.evaluateHandle((text) => {
        const matches = [...document.querySelectorAll("[aria-label]")]
          .filter((current) =>
            current.getAttribute("aria-label").includes(text),
          )
          .sort((left, right) => {
            const a = left.getBoundingClientRect();
            const b = right.getBoundingClientRect();
            return a.width * a.height - b.width * b.height;
          });
        return matches[0] ?? null;
      }, selector.slice(5));
      element = candidate.asElement();
      if (element === null) await candidate.dispose();
    }
    if (element === null) return false;
    try {
      if (
        requireEnabled &&
        !(await element.evaluate(
          (current) =>
            current.isConnected &&
            current.getAttribute("aria-disabled") !== "true",
        ))
      )
        return false;
      const bounds = await element.boundingBox();
      if (bounds === null || bounds.width <= 0 || bounds.height <= 0)
        return false;
      const x = bounds.x + bounds.width / 2;
      const y = bounds.y + bounds.height / 2;
      const inViewport =
        x >= 0 &&
        x < viewport.width &&
        (selector.startsWith("text/")
          ? bounds.y < viewport.height && bounds.y + bounds.height > 0
          : bounds.y >= 0 && bounds.y + bounds.height <= viewport.height);
      if (!inViewport) return false;
      if (selector.startsWith("text/")) return true;
      return await element.evaluate((current) => {
        const bounds = current.getBoundingClientRect();
        const hit = document.elementFromPoint(
          bounds.x + bounds.width / 2,
          bounds.y + bounds.height / 2,
        );
        return hit !== null && current.contains(hit);
      });
    } finally {
      await element.dispose();
    }
  };
  if (await visible()) return;
  // PlayerPage의 최대 720px ListView 오른쪽 16px 여백에서 스크롤한다.
  // 가운데의 native textarea가 휠을 소비해 바깥 화면이 멈추는 것을 피한다.
  await page.mouse.move(
    viewport.width / 2 + Math.min(viewport.width, 720) / 2 - 8,
    viewport.height / 2,
  );
  const deadline = performance.now() + 12000;
  for (let step = 0; performance.now() < deadline; step++) {
    const direction = Math.floor(step / 12) % 2 === 0 ? 1 : -1;
    await page.mouse.wheel({ deltaY: direction * 350 });
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(resolve)),
        ),
    );
    if (await visible()) return;
  }
  throw new Error("Visible semantic control unavailable");
}

/** 실제 CDP 입력과 네이티브 선택만 사용하며 DOM·Dart 값을 대입하지 않는다. */
async function input(page, label, value) {
  await page.bringToFront();
  // SDK의 multiline native 편집기는 textarea이며 단일행과 AX 노출 방식이 다르다.
  const privateMultiline =
    reportMode &&
    [
      "방법",
      "시간",
      "동기",
      "근거",
      "막힌 지점",
      "역할 기여",
      "공정성",
      "채점 우려 (선택)",
    ].includes(label);
  const selector = privateMultiline
    ? `textarea[aria-label*="${label}"]`
    : label === "개인 조사 기록 (임시 메모)"
      ? 'textarea[aria-label*="개인 조사 기록"]'
      : `aria/${label}[role="textbox"]`;
  await reveal(page, selector);
  if (privateMultiline || label === "개인 조사 기록 (임시 메모)") {
    // DOM focus만으로 이전 semantic 편집기를 쓰지 않고 실제 TextField tap을 먼저 수행한다.
    await page.locator(selector).click();
  }
  const field = await page.waitForSelector(selector);
  await field.focus();
  await field.evaluate(async (element) => {
    await new Promise((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(resolve)),
    );
    if (
      !(
        element instanceof HTMLInputElement ||
        element instanceof HTMLTextAreaElement
      ) ||
      document.activeElement !== element ||
      element.disabled ||
      element.readOnly
    )
      throw new Error("Semantic input binding unavailable");
    element.select();
  });
  await page.keyboard.sendCharacter(value);
  await page.waitForFunction(
    (element, expected) =>
      document.activeElement === element && element.value === expected,
    {},
    field,
    value,
  );
  await field.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  assert.equal(counters.runtimeErrors, 0);
}

/** 응답 대기는 action과 같은 Promise.all에 즉시 등록하여 미처리 rejection을 방지한다. */
async function actionResponse(page, path, action, expected = 200, method) {
  const [response] = await Promise.all([
    page.waitForResponse(
      (value) =>
        new URL(value.url()).pathname === path &&
        (method === undefined || value.request().method() === method),
    ),
    action(),
  ]);
  assert.equal(response.status(), expected);
}

async function login(state, account = state.account, destination = "account") {
  state.account = account;
  await route(state.page, "login");
  await input(state.page, "이메일", account.email);
  await input(state.page, "비밀번호", fixture.password);
  await actionResponse(state.page, AUTH + "/login/local", () =>
    button(state.page, "로그인"),
  );
  if (destination !== null) {
    await route(state.page, destination);
  } else {
    await state.page.waitForFunction(
      (testKey) =>
        ["#/account", "#/invitations", "#/investigation/" + testKey].includes(
          location.hash,
        ),
      {},
      fixture.testKey,
    );
    await semantics(state.page);
  }
  await signalState(() => state.access !== null);
}

async function invitation(state) {
  await button(state.page, "초대로 이동");
  await route(state.page, "invitations");
  await button(state.page, "초대 확인");
  await route(state.page, "invitations/" + fixture.testKey);
  await signalState(() => state.notice !== null);
}

/** 고지를 실제 화면에서 읽고 checkbox를 직접 선택한 뒤에만 최초 동의를 보낸다. */
async function agree(state) {
  await state.page.bringToFront();
  await state.page.waitForFunction(
    (body) => document.body.textContent.includes(body),
    {},
    state.notice.body,
  );
  const checkbox = await state.page.waitForSelector(
    'aria/위 고지를 읽고 기능 테스트 참여에 동의합니다.[role="checkbox"]',
  );
  assert.equal(
    await checkbox.evaluate((element) => element.getAttribute("aria-checked")),
    "false",
  );
  await checkbox.click();
  await state.page.waitForFunction(
    (element) => element.getAttribute("aria-checked") === "true",
    {},
    checkbox,
  );
}

async function investigation(state) {
  await button(state.page, "조사 화면으로");
  await route(state.page, "investigation/" + fixture.testKey);
  await state.page.waitForSelector('aria/준비하기[role="button"]');
}

async function materials(state) {
  const before = state.materialCount;
  const hidden = await state.page.evaluate(() => document.hidden);
  await state.page.bringToFront();
  await signalState(
    () => state.material !== null && (!hidden || state.materialCount > before),
  );
  // 복귀 때 기록 편집기로 스크롤될 수 있으므로 실제 휠로 역할 자료를 다시 읽는다.
  const viewport = state.page.viewport();
  await state.page.mouse.move(viewport.width / 2, viewport.height / 2);
  await state.page.mouse.wheel({ deltaY: -3000 });
  await state.page.evaluate(
    () =>
      new Promise((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(resolve)),
      ),
  );
  await state.page.waitForFunction(
    (role, clues) => {
      // ListTile 병합 semantics는 실제 접근성 이름을 aria-label에 제공한다.
      const visibleText =
        document.body.textContent +
        "\n" +
        Array.from(document.querySelectorAll("[aria-label]"))
          .map((element) => element.getAttribute("aria-label"))
          .join("\n");
      return (
        visibleText.includes(role.name) &&
        clues.every(
          (clue) =>
            visibleText.includes(clue.title) &&
            (clue.body === null || visibleText.includes(clue.body)),
        )
      );
    },
    { polling: "mutation" },
    state.material.role,
    state.material.clues,
  );
}

/** 저장소는 실제 epoch 표식만 허용하고 개인 원문·메모·자격은 허용하지 않는다. */
async function storage(state, notes) {
  const stored = await state.page.evaluate(() => ({
    local: Object.fromEntries(Object.entries(localStorage)),
    session: Object.fromEntries(Object.entries(sessionStorage)),
    cookie: document.cookie,
  }));
  assert.deepEqual(Object.keys(stored.local), [MARKER]);
  assert.deepEqual(stored.session, {});
  const marker = JSON.parse(stored.local[MARKER]);
  assert.equal(marker.format, 1);
  assert.match(marker.epoch, UUID4);
  const serialized = JSON.stringify(stored);
  for (const secret of [state.access, ...notes])
    assert.equal(serialized.includes(secret), false);
  for (const clue of state.material.clues)
    if (clue.body) assert.equal(serialized.includes(clue.body), false);
}

async function logout(state) {
  await signalState(() => pending.size === 0);
  await actionResponse(state.page, AUTH + "/logout", () =>
    button(state.page, "로그아웃"),
  );
  await route(state.page, "login");
}

/** 실제 새 탭으로 숨기고 분리된 옛 편집기까지 원문이 지워지는지 검사한다. */
async function hideEditor(state, label, value, kind) {
  const selector = `textarea[aria-label*="${label}"]`;
  await reveal(state.page, selector);
  await state.page.locator(selector).click();
  const old = await state.page.waitForSelector(selector);
  assert.equal(await old.evaluate((element) => element.value), value);
  assert.equal(
    await old.evaluate(
      (element, prefix) =>
        element.closest(`[flt-semantics-identifier^="${prefix}"]`) !== null,
      `sicha-private-${kind}-`,
    ),
    true,
  );
  const cover = await state.context.newPage();
  await cover.goto("about:blank");
  await cover.bringToFront();
  await state.page.waitForFunction(
    (text) =>
      document.hidden &&
      !Array.from(document.querySelectorAll("input,textarea")).some(
        (element) =>
          element.value.includes(text) || element.defaultValue.includes(text),
      ),
    { polling: 100 },
    value,
  );
  assert.equal(
    await old.evaluate(
      (element) => element.value === "" && element.defaultValue === "",
    ),
    true,
  );
  await old.dispose();
  counters[kind + "Hidden"]++;
  await state.page.bringToFront();
  await cover.close();
  await semantics(state.page);
}

/** 응답만 유실시키며 실제 서버 변경·원본 요청을 수정하거나 합성하지 않는다. */
async function dropMutation(state, path, click, status = 200) {
  phase =
    path === BASE + "/report" ? "report-drop-write" : "feedback-drop-write";
  await state.client.send("Fetch.enable", {
    patterns: [{ urlPattern: fixture.origin + path, requestStage: "Response" }],
  });
  const dropped = new Promise((resolve, reject) => {
    const intercept = (event) => {
      void (async () => {
        const method = path === BASE + "/report" ? "PATCH" : "POST";
        if (event.request.method !== method) {
          await state.client.send("Fetch.continueRequest", {
            requestId: event.requestId,
          });
          return;
        }
        counters.mutationDropStatus = event.responseStatusCode;
        counters.mutationDropPaused++;
        assert.equal(event.responseStatusCode, status);
        await state.client.send("Fetch.failRequest", {
          requestId: event.requestId,
          errorReason: "Failed",
        });
        await state.client.send("Fetch.disable");
        state.client.off("Fetch.requestPaused", intercept);
        resolve();
      })().catch(() => reject(new Error("Report response drop failed")));
    };
    state.client.on("Fetch.requestPaused", intercept);
  });
  await Promise.all([dropped, click()]);
  phase =
    path === BASE + "/report"
      ? "report-unknown-control"
      : "feedback-unknown-control";
  await reveal(state.page, 'aria/원본 요청 다시 보내기[role="button"]');
}

/** 명시적 상태 재조회는 변경을 재전송하지 않으며 원본 의도를 지우지도 않는다. */
async function unknownRead(state, path, expectedCount) {
  phase =
    path === BASE + "/report" ? "report-recovery-get" : "feedback-recovery-get";
  const before = state.detailCount;
  await button(state.page, "서버 상태 다시 조회");
  await signalState(() => state.detailCount > before);
  phase =
    path === BASE + "/report"
      ? "report-recovery-observers"
      : "feedback-recovery-observers";
  await signalState(() => pending.size === 0);
  assert.equal(
    state.requests.filter((request) => request.path === path).length,
    expectedCount,
  );
  phase =
    path === BASE + "/report"
      ? "report-recovery-original-control"
      : "feedback-recovery-original-control";
  await reveal(state.page, 'aria/원본 요청 다시 보내기[role="button"]');
}

/** 원본 바이트·동사·키를 명시 재전송한 영수증만 replay로 인정한다. */
async function replayMutation(state, original, counter) {
  const before = state.receipts.length;
  await actionResponse(
    state.page,
    original.path,
    () => button(state.page, "원본 요청 다시 보내기"),
    200,
    original.method,
  );
  const sent = state.requests
    .filter((request) => request.path === original.path)
    .at(-1);
  assert.equal(sent.method, original.method);
  assert.equal(sent.url, original.url);
  assert.equal(sent.body, original.body);
  await signalState(() => state.receipts.length > before);
  const receipt = state.receipts.at(-1).value;
  assert.equal(receipt.replayed, true);
  assert.equal(receipt.changed, false);
  counters[counter]++;
}

/** 현재 DOM과 분리된 편집기에서 민감 문구가 남지 않았음을 읽기만 해서 확인한다. */
async function absentPrivate(state, values) {
  await state.page.waitForFunction(
    (texts) => {
      const content =
        document.body.textContent +
        Array.from(document.querySelectorAll("[aria-label]"), (element) =>
          element.getAttribute("aria-label"),
        ).join("\n");
      return (
        texts.every((text) => !content.includes(text)) &&
        !Array.from(document.querySelectorAll("input,textarea")).some(
          (element) =>
            texts.some(
              (text) =>
                element.value.includes(text) ||
                element.defaultValue.includes(text),
            ),
        )
      );
    },
    {},
    values,
  );
}

/** 보호된 서버 투영뿐 아니라 실제 화면에서도 점수와 공개 행동이 없어야 한다. */
async function protectedScreen(state) {
  protectedResult(state.result);
  await absentPrivate(state, Object.values(fixture.report));
  await state.page.waitForFunction(
    () =>
      !document.body.textContent.includes("최종 점수:") &&
      !document.body.textContent.includes("기본 점수") &&
      !document.body.textContent.includes("제출본과 해설 열기"),
  );
  assert.equal(
    await state.page.$('aria/제출본과 해설 열기[role="button"]'),
    null,
  );
}

/** 종료 결과는 반드시 새 GET으로 얻고 캐시된 원문/점수를 기대값으로 대체하지 않는다. */
async function refreshResult(state) {
  const before = state.resultCount;
  await actionResponse(state.page, BASE + "/result", () =>
    button(state.page, "서버 상태 다시 조회"),
  );
  await signalState(() => state.resultCount > before);
  const server = await probe(BASE + "/result", "GET", bearerHeaders(state));
  assert.equal(server.status, 200);
  resultView(server.value);
  const withoutTrace = ({ requestId, ...value }) => value;
  assert.deepEqual(withoutTrace(state.result), withoutTrace(server.value));
  return state.result;
}

/** 피드백 네 필드는 실제 편집기에 입력하며 보낸 원문은 프로세스 밖에 기록하지 않는다. */
async function feedbackInput(state, marker) {
  const feedback = {
    blockedAt: "합성 막힌 지점 " + marker,
    roleContribution: "합성 역할 기여 " + marker,
    fairness: "합성 공정성 " + marker,
    gradingConcern: "합성 채점 우려 " + marker,
  };
  for (const [field, label] of [
    ["blockedAt", "막힌 지점"],
    ["roleContribution", "역할 기여"],
    ["fairness", "공정성"],
    ["gradingConcern", "채점 우려 (선택)"],
  ])
    await input(state.page, label, feedback[field]);
  return feedback;
}

/** 같은 문서에서 계정을 바꾼 뒤 현재 인증으로 결과를 새로 조회한다. */
async function accountResult(state, account, secrets) {
  state.access = null;
  state.detail = null;
  state.notice = null;
  state.report = null;
  state.result = null;
  const before = state.resultCount;
  // 인증 뒤 복귀 대상은 실제 라우터가 결정한다. 아래에서 같은 문서의 종료 방을 새로 조회한다.
  await login(state, account, null);
  const documentOrigin = await state.page.evaluate(
    () => performance.timeOrigin,
  );
  await state.page.goto(
    origin.origin + "/player/#/investigation/" + fixture.testKey,
  );
  assert.equal(
    await state.page.evaluate(() => performance.timeOrigin),
    documentOrigin,
  );
  await route(state.page, "investigation/" + fixture.testKey);
  await signalState(() => state.resultCount > before);
  assert.equal(state.result.resultPhase, "AVAILABLE");
  await absentPrivate(state, secrets);
  assert.equal(
    await state.page.$('aria/원본 요청 다시 보내기[role="button"]'),
    null,
  );
  await refreshResult(state);
  counters.freshResult++;
}

/** 조사에서 공동 고정본 접수·실제 worker 결과·본인 피드백·회수까지 이어지는 별도 모드다. */
async function reportLifecycle(a, b) {
  phase = "report-native-edit";
  await a.page.bringToFront();
  reportData(fixture.report);
  phase = "report-observed-projection";
  assert.ok(a.report !== null);
  assert.equal(a.report.draftRev, a.detail.draftRev);
  assert.equal(a.report.submissionState, a.detail.submissionState);
  assert.equal(a.material.persons[0].code, fixture.report.culpritCode);
  const picker = '[flt-semantics-identifier^="sicha-report-culprit-"]';
  await reveal(a.page, picker);
  phase = "report-select-culprit";
  const pickerBounds = await a.page.$eval(picker, (element) => {
    const bounds = element.getBoundingClientRect();
    return {
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
    };
  });
  const pointerX = pickerBounds.x + pickerBounds.width / 2;
  const pointerY = pickerBounds.y + pickerBounds.height / 2;
  assert.ok(pickerBounds.width > 0 && pickerBounds.height > 0);
  assert.ok(pointerX >= 0 && pointerX < a.page.viewport().width);
  assert.ok(pointerY >= 0 && pointerY < a.page.viewport().height);
  await a.page.mouse.click(pointerX, pointerY);
  phase = "report-menu-open";
  await a.page.waitForSelector('[role="menu"]');
  phase = "report-menu-option";
  const person = a.material.persons[0];
  await a.page.locator(`aria/${person.name} (${person.code})`).click();
  await a.page.waitForSelector('[role="menu"]', { hidden: true });
  for (const [field, label] of [
    ["method", "방법"],
    ["time", "시간"],
    ["motive", "동기"],
    ["evidence", "근거"],
  ]) {
    phase = "report-input-" + field;
    await input(a.page, label, fixture.report[field]);
  }
  phase = "report-hide-editor";
  await hideEditor(a, "근거", fixture.report.evidence, "report");
  await reveal(a.page, 'textarea[aria-label*="근거"]');
  await a.page.locator('textarea[aria-label*="근거"]').click();
  await a.page.waitForFunction(
    (text) =>
      Array.from(
        document.querySelectorAll('textarea[aria-label*="근거"]'),
      ).some((element) => element.value === text),
    {},
    fixture.report.evidence,
  );
  assert.equal(counters.reportEdit, 0);
  await storage(a, Object.values(fixture.report));
  const oldReport = await a.page.waitForSelector(
    'textarea[aria-label*="근거"]',
  );
  await dropMutation(a, BASE + "/report", () =>
    button(a.page, "공동 초안 저장"),
  );
  phase = "report-original-body";
  const edit = a.requests.find((request) => request.method === "PATCH");
  assert.deepEqual(edit.value.report, fixture.report);
  assert.equal(
    await oldReport.evaluate(
      (element) => element.value === "" && element.defaultValue === "",
    ),
    true,
  );
  await oldReport.dispose();
  phase = "report-unknown-read";
  await unknownRead(a, edit.path, 1);
  phase = "report-original-replay";
  await replayMutation(a, edit, "reportReplay");
  await signalState(() => a.report?.draftRev !== edit.value.expectedDraftRev);
  assert.deepEqual(a.report.report, fixture.report);

  phase = "report-fixed-proposal";
  await actionResponse(
    a.page,
    BASE + "/report/proposals",
    () => button(a.page, "현재 초안 제안"),
    201,
  );
  await signalState(() => a.report?.proposal?.state === "PROPOSED");
  const proposal = structuredClone(a.report.proposal);
  assert.equal(proposal.canAccept, false);
  assert.equal(proposal.proposerSlot, a.detail.self.slot);
  assert.equal(proposal.sourceRev, a.report.draftRev);
  phase = "report-partner-current-proposal";
  await b.page.bringToFront();
  await actionResponse(b.page, BASE + "/report", () =>
    button(b.page, "서버 상태 다시 조회"),
  );
  await signalState(() => b.report?.proposal?.reportKey === proposal.reportKey);
  assert.deepEqual(b.report.report, fixture.report);
  assert.equal(b.report.draftRev, proposal.sourceRev);
  assert.equal(b.report.proposal.canAccept, true);
  const respond = BASE + "/report/proposals/" + proposal.reportKey + "/respond";
  phase = "report-explicit-partner-consent";
  await actionResponse(b.page, respond, () =>
    button(b.page, "제안 수락 및 판정 접수"),
  );
  await signalState(() => b.receipts.some((item) => item.path === respond));
  const accepted = b.receipts.find((item) => item.path === respond).value;
  assert.equal(accepted.action, "REPORT_ACCEPT");
  assert.equal(accepted.original.reportKey, proposal.reportKey);
  assert.equal(accepted.original.submitNo, "1");
  assert.match(accepted.original.jobKey, UUID4);
  assert.equal(
    Date.parse(accepted.original.gradeDeadline) -
      Date.parse(accepted.original.acceptedAt),
    120000,
  );
  assert.equal(
    b.requests.find((item) => item.path === respond).value.decision,
    "ACCEPT",
  );

  phase = "report-worker-result-protected";
  // 마지막 자료 관측을 끝낸 실제 숨김 탭에서만 소비를 시작해 종료와 자료 조회의 경합을 분리한다.
  const covers = [];
  for (const state of [a, b]) {
    const cover = await state.context.newPage();
    await cover.goto("about:blank");
    await cover.bringToFront();
    await state.page.waitForFunction(() => document.hidden, { polling: 100 });
    covers.push(cover);
  }
  await signalState(
    () => pending.size === 0 && a.network.size === 0 && b.network.size === 0,
  );
  process.stdout.write('{"checkpoint":"grade"}\n');
  const gradingUntil = Date.now() + 60000;
  let graded;
  do {
    graded = await probe(BASE + "/result", "GET", bearerHeaders(b));
    if (graded.status === 200) break;
    assert.equal(graded.status, 409);
    assert.equal(graded.value.code, "RESULT_NOT_READY");
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < gradingUntil);
  assert.equal(graded.status, 200);
  protectedResult(graded.value);
  for (const [index, state] of [a, b].entries()) {
    await state.page.bringToFront();
    await covers[index].close();
  }
  await signalState(() => a.result !== null && b.result !== null);
  protectedResult(await refreshResult(a));
  protectedResult(await refreshResult(b));
  for (const state of [a, b]) await protectedScreen(state);

  phase = "report-feedback-hide";
  const erased = await feedbackInput(a, "숨김 " + randomUUID());
  await hideEditor(a, "채점 우려 (선택)", erased.gradingConcern, "feedback");
  await refreshResult(a);
  await absentPrivate(a, Object.values(erased));
  const feedbackA = await feedbackInput(a, "A " + randomUUID());
  const oldFeedback = await a.page.waitForSelector(
    'textarea[aria-label*="채점 우려 (선택)"]',
  );
  await storage(a, Object.values(feedbackA));
  await dropMutation(a, BASE + "/feedback", () =>
    button(a.page, "본인 피드백 제출"),
  );
  const originalA = a.requests.find((item) => item.path === BASE + "/feedback");
  assert.deepEqual(originalA.value.feedback, feedbackA);
  await unknownRead(a, originalA.path, 1);
  assert.equal(
    await oldFeedback.evaluate(
      (element) => element.value === "" && element.defaultValue === "",
    ),
    true,
  );
  await oldFeedback.dispose();
  await replayMutation(a, originalA, "feedbackReplay");
  const own = await refreshResult(a);
  assert.equal(own.resultPhase, "AVAILABLE");
  assert.equal(own.reports[0].reportKey, proposal.reportKey);
  protectedResult(await refreshResult(b));
  await protectedScreen(b);
  await absentPrivate(b, [
    ...Object.values(fixture.report),
    ...Object.values(feedbackA),
  ]);
  counters.ownFeedbackIsolation++;
  await button(a.page, "제출본과 해설 열기");
  await reveal(a.page, "text/" + own.revealText);
  await a.page.waitForFunction(
    (text) =>
      document.body.textContent.includes(text) ||
      [...document.querySelectorAll("[aria-label]")].some((element) =>
        element.getAttribute("aria-label").includes(text),
      ),
    {},
    own.revealText,
  );

  phase = "report-feedback-owner-unknown";
  const feedbackB = await feedbackInput(b, "B " + randomUUID());
  await dropMutation(b, BASE + "/feedback", () =>
    button(b.page, "본인 피드백 제출"),
  );
  const originalB = b.requests.find((item) => item.path === BASE + "/feedback");
  assert.deepEqual(originalB.value.feedback, feedbackB);
  await unknownRead(b, originalB.path, 1);
  const oldAccess = b.access;
  await logout(b);
  await absentPrivate(b, [...Object.values(feedbackB), own.revealText]);
  const loggedOut = await probe(
    BASE + "/result",
    "GET",
    bearerHeaders({ access: oldAccess }),
  );
  assert.equal(loggedOut.status, 401);
  const loggedOutMutation = await probe(
    originalB.path,
    originalB.method,
    bearerHeaders({ access: oldAccess }),
    originalB.body,
  );
  assert.equal(loggedOutMutation.status, 401);

  phase = "report-account-other-owner";
  await accountResult(b, fixture.accounts[0], [
    ...Object.values(feedbackB),
    own.revealText,
  ]);
  const conflict = await probe(
    originalB.path,
    originalB.method,
    bearerHeaders(b),
    originalB.body,
  );
  assert.equal(conflict.status, 409);
  assert.equal(conflict.value.code, "REQUEST_KEY_CONFLICT");
  assert.equal(counters.feedback, 3);
  counters.oldOwnerDenied++;
  await logout(b);
  phase = "report-account-original-owner";
  await accountResult(b, fixture.accounts[1], [
    ...Object.values(feedbackB),
    own.revealText,
  ]);
  assert.equal(counters.feedback, 3);
  counters.accountPurged = 1;
  await button(b.page, "제출본과 해설 열기");
  await reveal(b.page, "text/" + own.revealText);

  phase = "report-revoke-current-access";
  await signalState(() => pending.size === 0);
  // 원문·식별자 없는 제어 영수증으로 Java의 실제 관리자 회수를 요청한다.
  process.stdout.write('{"checkpoint":"revoke"}\n');
  const until = Date.now() + 12000;
  let revoked;
  do {
    revoked = await probe(BASE + "/result", "GET", bearerHeaders(b));
    if (revoked.status !== 200) break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < until);
  assert.equal(revoked.status, 409);
  assert.equal(revoked.value.code, "INVITATION_INVALIDATED");
  assert.deepEqual(Object.keys(revoked.value).sort(), [
    "code",
    "message",
    "requestId",
  ]);
  counters.revokedResult++;
  const denied = await probe(
    originalB.path,
    originalB.method,
    bearerHeaders(b),
    originalB.body,
  );
  assert.equal(denied.status, 409);
  assert.equal(denied.value.code, "INVITATION_INVALIDATED");
  counters.revokedMutation++;
  for (const state of [a, b]) {
    await actionResponse(
      state.page,
      BASE,
      () => button(state.page, "서버 상태 다시 조회"),
      409,
    );
    await absentPrivate(state, [
      own.revealText,
      ...Object.values(fixture.report),
    ]);
    await logout(state);
  }
}

try {
  const launchOptions = {
    executablePath:
      process.env.CHROME_BIN ||
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    headless: true,
    acceptInsecureCerts: true,
    protocolTimeout: 15000,
    args: ["--no-sandbox"],
  };
  browser = await puppeteer.launch(launchOptions);
  memberBrowsers.push(browser);
  const states = viewStates;
  for (const account of fixture.accounts) {
    // 두 회원의 실제 별도 기기를 모델링하며 incognito 창 간 focus 경합을 공유하지 않는다.
    const memberBrowser =
      states.length === 0 ? browser : await puppeteer.launch(launchOptions);
    if (memberBrowser !== browser) memberBrowsers.push(memberBrowser);
    const context = await memberBrowser.createBrowserContext();
    await context.setCookie({
      name: "player_test_nonsecret",
      value: "probe",
      domain: "localhost",
      path: "/",
      secure: true,
      sameSite: "Strict",
    });
    const page = await context.newPage();
    const state = await instrument(page, account);
    state.context = context;
    await page.goto(fixture.origin + "/player/", { waitUntil: "networkidle0" });
    await semantics(page);
    await login(state);
    states.push(state);
  }
  const [a, b] = states;
  phase = "explicit-consent-unknown-response";
  await invitation(a);
  await agree(a);
  const originalNotice = structuredClone(a.notice);
  await a.client.send("Fetch.enable", {
    patterns: [
      {
        urlPattern: fixture.origin + BASE + "/accept",
        requestStage: "Response",
      },
    ],
  });
  const dropped = new Promise((resolve, reject) => {
    a.client.once("Fetch.requestPaused", (event) => {
      void (async () => {
        assert.equal(event.responseStatusCode, 200);
        await a.client.send("Fetch.failRequest", {
          requestId: event.requestId,
          errorReason: "Failed",
        });
        await a.client.send("Fetch.disable");
        counters.droppedAccept++;
        resolve();
      })().catch(() => reject(new Error("Accept response drop failed")));
    });
  });
  await Promise.all([dropped, button(a.page, "동의하고 초대 수락")]);
  await a.page.waitForSelector(
    'aria/원본 동의 요청 다시 보내기[role="button"]',
  );
  assert.equal(counters.accept, 1);
  const original = a.requests.find(
    (request) => request.path === BASE + "/accept",
  );
  assert.deepEqual(original.value, {
    expectedRev: originalNotice.revision,
    inviteGen: originalNotice.generation,
    blindDeclared: false,
    policyCode: originalNotice.policyCode,
    noticeHash: originalNotice.noticeHash,
    requestKey: original.value.requestKey,
  });
  assert.match(original.value.requestKey, UUID4);
  await actionResponse(a.page, BASE, () => button(a.page, "서버 상태 확인"));
  await signalState(
    () => a.detail?.self.accepted === true && a.detail.rev === "1",
  );
  assert.equal(counters.accept, 1);
  assert.equal(await a.page.$('aria/조사 화면으로[role="button"]'), null);
  await a.page.waitForSelector(
    'aria/원본 동의 요청 다시 보내기[role="button"]',
  );
  await a.page.waitForFunction(
    (notice) =>
      document.body.textContent.includes(notice.body) &&
      document.body.textContent.includes(notice.contact) &&
      document.body.textContent.includes(
        "개인정보 처리 고지 " + notice.version,
      ),
    {},
    originalNotice,
  );

  await invitation(b);
  await agree(b);
  await actionResponse(b.page, BASE + "/accept", () =>
    button(b.page, "동의하고 초대 수락"),
  );
  await b.page.waitForSelector('aria/조사 화면으로[role="button"]');
  await signalState(() =>
    b.receipts.some((receipt) => receipt.path === BASE + "/accept"),
  );
  const secondConsent = b.receipts.find(
    (receipt) => receipt.path === BASE + "/accept",
  ).value;
  assert.equal(secondConsent.changed, true);
  assert.equal(secondConsent.replayed, false);
  assert.equal(secondConsent.current.rev, "2");
  await a.page.bringToFront();
  await actionResponse(a.page, BASE + "/accept", () =>
    button(a.page, "원본 동의 요청 다시 보내기"),
  );
  await a.page.waitForSelector('aria/조사 화면으로[role="button"]');
  const accepts = a.requests.filter(
    (request) => request.path === BASE + "/accept",
  );
  assert.equal(accepts.length, 2);
  assert.equal(accepts[1].url, original.url);
  assert.equal(accepts[1].body, original.body);
  await signalState(() =>
    a.receipts.some((receipt) => receipt.path === BASE + "/accept"),
  );
  const replay = a.receipts.find(
    (receipt) => receipt.path === BASE + "/accept",
  ).value;
  assert.equal(replay.action, "INVITATION_ACCEPT");
  assert.equal(replay.replayed, true);
  assert.equal(replay.changed, false);
  assert.equal(replay.original.rev, "1");
  assert.equal(replay.current.rev, "2");
  assert.equal(counters.accept, 3);
  counters.originalReplay = 1;

  phase = "paired-readiness-start";
  await investigation(a);
  await actionResponse(a.page, BASE + "/ready", () =>
    button(a.page, "준비하기"),
  );
  await a.page.waitForSelector('aria/준비 해제[role="button"]');
  await investigation(b);
  await actionResponse(b.page, BASE + "/ready", () =>
    button(b.page, "준비하기"),
  );
  await b.page.waitForSelector('aria/조사 시작[role="button"]');
  assert.ok(a.requests.some((request) => request.path === BASE + "/heartbeat"));
  assert.ok(b.requests.some((request) => request.path === BASE + "/heartbeat"));
  await actionResponse(b.page, BASE + "/start", () =>
    button(b.page, "조사 시작"),
  );
  await materials(b);
  await a.page.bringToFront();
  await materials(a);
  assert.notEqual(a.material.role.code, b.material.role.code);
  assert.notDeepEqual(
    a.material.clues.map((clue) => clue.code),
    b.material.clues.map((clue) => clue.code),
  );

  phase = "private-notes-visibility-recovery";
  const noteA = "합성 메모 A " + randomUUID();
  const noteB = "합성 메모 B " + randomUUID();
  await input(a.page, "개인 조사 기록 (임시 메모)", noteA);
  const retiredEditor = await a.page.waitForSelector(
    'textarea[aria-label*="개인 조사 기록"]',
  );
  const originalRole = a.material.role.code;
  const materialBefore = a.materialCount;
  // 별도 incognito 창은 둘 다 visible일 수 있으므로 같은 창의 실제 다른 탭으로 전환한다.
  const cover = await a.context.newPage();
  await cover.goto("about:blank");
  await cover.bringToFront();
  await a.page.waitForFunction(
    (note) =>
      document.hidden &&
      !Array.from(document.querySelectorAll("input,textarea")).some((element) =>
        element.value.includes(note),
      ),
    { polling: 100 },
    noteA,
  );
  assert.equal(
    await retiredEditor.evaluate(
      (element) => element.value === "" && element.defaultValue === "",
    ),
    true,
  );
  await retiredEditor.dispose();
  counters.backgroundHidden = 1;
  await materials(b);
  await input(b.page, "개인 조사 기록 (임시 메모)", noteB);
  await a.page.bringToFront();
  await cover.close();
  await signalState(() => a.materialCount > materialBefore);
  await materials(a);
  assert.equal(a.material.role.code, originalRole);
  await reveal(a.page, 'textarea[aria-label*="개인 조사 기록"]');
  await a.page.locator('textarea[aria-label*="개인 조사 기록"]').click();
  await a.page.waitForFunction(
    (note) =>
      Array.from(
        document.querySelectorAll('textarea[aria-label*="개인 조사 기록"]'),
      ).some(
        (element) =>
          element.value === note &&
          !element.readOnly &&
          /^sicha-private-note-[0-9a-f-]{36}$/.test(
            element
              .closest("[flt-semantics-identifier]")
              ?.getAttribute("flt-semantics-identifier") ?? "",
          ),
      ),
    {},
    noteA,
  );
  await input(a.page, "개인 조사 기록 (임시 메모)", noteA + " 추가 입력");
  counters.sameRoleRecovered = 1;
  await storage(a, [noteA, noteB]);
  await storage(b, [noteA, noteB]);

  phase = "allowed-hint-isolated-scope";
  await actionResponse(a.page, BASE + "/hints/1/open", () =>
    button(a.page, "1단계 힌트 열기"),
  );
  await signalState(() =>
    a.material?.openedHints.some((hint) => hint.level === 1),
  );
  await reveal(a.page, "text/1단계 힌트:");
  await a.page.waitForFunction(
    (body) =>
      (
        document.body.textContent +
        Array.from(document.querySelectorAll("[aria-label]"), (element) =>
          element.getAttribute("aria-label"),
        ).join("\n")
      ).includes("1단계 힌트: " + body),
    {},
    a.material.openedHints.find((hint) => hint.level === 1).body,
  );
  await materials(b);
  assert.equal(b.material.openedHints.length, 0);

  phase = "separate-https-negative-boundaries";
  const headers = bearerHeaders(a);
  for (const [path, method, additions, expected] of [
    [BASE + "/report", "POST", {}, 403],
    [BASE + "/result", "PATCH", {}, 403],
    [BASE + "/forfeit", "GET", {}, 403],
    [BASE + "/feedback", "GET", {}, 403],
    [BASE + "/report", "DELETE", {}, 403],
    [BASE + "/report/proposals", "GET", {}, 403],
    [BASE + "/hints/4/open", "POST", {}, 403],
    [BASE + "/materials", "POST", {}, 403],
    ["/api/playtests/" + fixture.testKey.toUpperCase(), "GET", {}, 403],
    [BASE + "/materials?size=20", "GET", {}, 400],
    ["/api/playtests/invitations?size=101", "GET", {}, 400],
    ["/api/playtests/invitations?size=0", "GET", {}, 400],
    ["/api/playtests/invitations?size=20&size=20", "GET", {}, 400],
    ["/api/playtests/invitations?cursor=%31", "GET", {}, 400],
    ["/api/playtests/invitations?cursor=9223372036854775808", "GET", {}, 400],
    [BASE, "GET", { Cookie: "nonsecret=probe" }, 403],
    [BASE, "GET", { Origin: "https://foreign.example.invalid" }, 403],
  ]) {
    const result = await probe(
      path,
      method,
      { ...headers, ...additions },
      method === "GET" ? undefined : "{}",
    );
    assert.equal(result.status, expected);
    assert.deepEqual(Object.keys(result.value).sort(), [
      "code",
      "message",
      "requestId",
    ]);
    assert.match(result.value.requestId, UUID4);
  }

  for (const query of [
    "size=1",
    "size=100",
    "cursor=1&size=100",
    "size=20&cursor=9223372036854775807",
  ]) {
    const result = await probe(
      "/api/playtests/invitations?" + query,
      "GET",
      headers,
    );
    assert.equal(result.status, 200);
    assert.deepEqual(result.value.items, []);
  }

  if (reportMode) {
    await reportLifecycle(a, b);
  } else {
    phase = "logout-account-change-purge";
    const oldClueTitles = a.material.clues.map((clue) => clue.title);
    await logout(a);
    await a.page.waitForFunction(
      (titles) =>
        titles.every((title) => !document.body.textContent.includes(title)),
      {},
      oldClueTitles,
    );
    await a.page.waitForFunction(
      (notes) =>
        !Array.from(document.querySelectorAll("input,textarea")).some(
          (element) => notes.some((note) => element.value.includes(note)),
        ),
      {},
      [noteA, noteB],
    );
    // 다른 실제 계정으로 같은 페이지를 재인증한 뒤 기존 역할 메모가 되살아나지 않는지 확인한다.
    a.access = null;
    a.detail = null;
    a.notice = null;
    a.material = null;
    // 조사 화면 로그아웃은 초대 목록으로 복귀하며 이미 동의한 회원의 실제 이동만 수행한다.
    await login(a, fixture.accounts[1], "invitations");
    // RUNNING 목록 제외를 우회해 동의를 만들지 않는다. 실제 내부 deep-link를 같은 문서에서 연다.
    const documentOrigin = await a.page.evaluate(() => performance.timeOrigin);
    await a.page.goto(
      origin.origin + "/player/#/investigation/" + fixture.testKey,
    );
    assert.equal(
      await a.page.evaluate(() => performance.timeOrigin),
      documentOrigin,
    );
    await route(a.page, "investigation/" + fixture.testKey);
    await materials(a);
    assert.equal(a.material.role.code, b.material.role.code);
    await a.page.waitForFunction(
      (titles) =>
        titles.every((title) => !document.body.textContent.includes(title)),
      {},
      oldClueTitles,
    );
    await reveal(a.page, 'textarea[aria-label*="개인 조사 기록"]');
    const freshNotes = await a.page.waitForSelector(
      'textarea[aria-label*="개인 조사 기록"]',
    );
    await a.page.waitForFunction(
      (element) => element.value === "",
      {},
      freshNotes,
    );
    counters.accountPurged = 1;
    await logout(a);
    await logout(b);
  }
  for (const state of states) {
    const cookies = await state.context.cookies();
    assert.equal(
      cookies.some((cookie) => cookie.name === "__Secure-sicha_player_refresh"),
      false,
    );
  }
  await signalState(() => pending.size === 0);
  assert.equal(failures.length, 0);
  assert.equal(counters.runtimeErrors, 0);
  assert.equal(counters.excludedCalls, 0);
  assert.equal(counters.materials, counters.validatedMaterials);
  assert.ok(counters.materials >= 3);
  assert.equal(counters.accept, 3);
  assert.equal(counters.ready, 2);
  assert.equal(counters.start, 1);
  assert.equal(counters.hint, 1);
  for (const value of Object.values(counters))
    assert.ok(Number.isSafeInteger(value) && value >= 0);
  phase = "complete";
  process.stdout.write(JSON.stringify({ ok: true, counters }));
} catch (error) {
  // assert 비교값·본문·쿠키·토큰·역할 원문은 진단에 반사하지 않는다.
  const views = await Promise.all(
    viewStates.map(async (state) => {
      if (!state.material)
        return {
          materialObserved: false,
          routeFlags: await state.page.evaluate(() => ({
            login: location.hash === "#/login",
            account: location.hash === "#/account",
            invitations: location.hash === "#/invitations",
            investigation: location.hash.startsWith("#/investigation/"),
            session: location.hash === "#/session",
          })),
        };
      return state.page
        .evaluate(
          ({ role, clues, person }) => {
            const text = document.body.textContent;
            const names = Array.from(document.querySelectorAll("[aria-label]"))
              .map((element) => element.getAttribute("aria-label"))
              .join("\n");
            const normalize = (value) => value.replace(/\s+/g, " ").trim();
            return {
              materialObserved: true,
              roleInText: text.includes(role.name),
              roleInAccessibleNames: names.includes(role.name),
              clueTitlesInText: clues.map((clue) => text.includes(clue.title)),
              clueTitlesInAccessibleNames: clues.map((clue) =>
                names.includes(clue.title),
              ),
              clueBodiesInText: clues.map(
                (clue) => clue.body === null || text.includes(clue.body),
              ),
              clueBodiesInAccessibleNames: clues.map(
                (clue) => clue.body === null || names.includes(clue.body),
              ),
              normalizedClueBodies: clues.map(
                (clue) =>
                  clue.body === null ||
                  normalize(text + names).includes(normalize(clue.body)),
              ),
              runningRendered: text.includes("진행 상태: RUNNING"),
              notesLabelRendered: text.includes("개인 조사 기록 (임시 메모)"),
              investigationRoute: location.hash.startsWith("#/investigation/"),
              invitationsRoute: location.hash === "#/invitations",
              loginRoute: location.hash === "#/login",
              reportFormatError:
                text.includes("보고서 형식이 올바르지 않습니다"),
              reportVisible: text.includes("공동 보고서"),
              ownerIdentifierCount: document.querySelectorAll(
                '[flt-semantics-identifier^="sicha-private-report-"]',
              ).length,
              methodControlCount:
                document.querySelectorAll('[aria-label*="방법"]').length,
              menuCount: document.querySelectorAll('[role="menu"]').length,
              documentFocused: document.hasFocus(),
              recoveryControls: Array.from(
                document.querySelectorAll('[role="button"][aria-label]'),
              )
                .filter((element) =>
                  [
                    "서버 상태 다시 조회",
                    "원본 요청 다시 보내기",
                    "현재 초안 제안",
                    "제안 수락 및 판정 접수",
                  ].includes(element.getAttribute("aria-label").trim()),
                )
                .map((element) => {
                  const bounds = element.getBoundingClientRect();
                  const hit = document.elementFromPoint(
                    bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2,
                  );
                  return {
                    original:
                      element.getAttribute("aria-label").trim() ===
                      "원본 요청 다시 보내기",
                    disabled: element.getAttribute("aria-disabled"),
                    width: bounds.width,
                    height: bounds.height,
                    y: bounds.y,
                    ownHit: hit !== null && element.contains(hit),
                    hitTag: hit?.tagName ?? null,
                    hitRole: hit?.getAttribute("role") ?? null,
                  };
                }),
              menuGeometry: Array.from(
                document.querySelectorAll('[role="menu"]'),
              ).map((element) => {
                const bounds = element.getBoundingClientRect();
                return {
                  tag: element.tagName,
                  width: bounds.width,
                  height: bounds.height,
                  x: bounds.x,
                  y: bounds.y,
                  children: element.children.length,
                  owned: (element.getAttribute("aria-owns") ?? "")
                    .split(" ")
                    .filter(Boolean).length,
                };
              }),
              menuStructure: Array.from(
                document.querySelectorAll(
                  '[role="menuitem"], [role="menu"] [aria-label]',
                ),
              )
                .slice(0, 8)
                .map((element) => ({
                  tag: element.tagName,
                  role: element.getAttribute("role"),
                  codeInLabel: (
                    element.getAttribute("aria-label") ?? ""
                  ).includes(person.code),
                  nameInLabel: (
                    element.getAttribute("aria-label") ?? ""
                  ).includes(person.name),
                  codeInText: (element.textContent ?? "").includes(person.code),
                  nameInText: (element.textContent ?? "").includes(person.name),
                })),
              privateIdentifierCount: document.querySelectorAll(
                '[flt-semantics-identifier^="sicha-private-note-"]',
              ).length,
              semanticTextareaCount: document.querySelectorAll(
                'textarea[data-semantics-role="text-field"]',
              ).length,
              editableCount: document.querySelectorAll(
                'input,textarea,[role="textbox"]',
              ).length,
              editableStructure: Array.from(
                document.querySelectorAll('input,textarea,[role="textbox"]'),
              ).map((element) => ({
                tag: element.tagName,
                role: element.getAttribute("role"),
                multiline: element.getAttribute("aria-multiline"),
                labelPresent: element.hasAttribute("aria-label"),
                labelIncludesExpected: (
                  element.getAttribute("aria-label") ?? ""
                ).includes("개인 조사 기록"),
                labelledByPresent: element.hasAttribute("aria-labelledby"),
                privateOwnerAncestor:
                  element.closest(
                    '[flt-semantics-identifier^="sicha-private-note-"]',
                  ) !== null,
                visible: element.getBoundingClientRect().height > 0,
              })),
              hidden: document.hidden,
              materialFormatError: text.includes(
                "역할 자료 형식이 올바르지 않습니다.",
              ),
              textLength: text.length,
              accessibleNameCount:
                document.querySelectorAll("[aria-label]").length,
            };
          },
          {
            role: state.material.role,
            clues: state.material.clues,
            person: state.material.persons[0],
          },
        )
        .catch(() => ({ unavailable: true }));
    }),
  );
  process.stderr.write(
    JSON.stringify({
      ok: false,
      phase,
      failure: "browser-investigation-contract",
      errorClass: [
        "AssertionError",
        "TimeoutError",
        "ProtocolError",
        "TypeError",
      ].includes(error.name)
        ? error.name
        : "Error",
      sourceLines: [
        ...String(error.stack).matchAll(
          /player-web-investigation\.mjs:(\d+):(\d+)/g,
        ),
      ]
        .slice(0, 6)
        .map((match) => [Number(match[1]), Number(match[2])]),
      contractFailures: [...new Set(failures)].slice(0, 12),
      views,
      counters,
    }),
  );
  process.exitCode = 1;
} finally {
  clearTimeout(deadline);
  await Promise.all(
    memberBrowsers.map((memberBrowser) => memberBrowser.close()),
  );
}
