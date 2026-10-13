import { randomUUID } from "node:crypto";
import { soloFixture } from "./solo-fixture.mjs";

/** 실제 공개/회원/모델 승인이 아닌 두 독립 브라우저의 합성 계약 서버다. */
export function coopFixture() {
  const base = soloFixture();
  const key = base.gameKey;
  const now = () => new Date().toISOString();
  let rev = 0n,
    draftRev = 0n,
    joined = false,
    status = "WAITING",
    submission = "NONE",
    proposal = null,
    pending = null;
  let inviteKey = null,
    secret = null;
  const ready = { host: false, guest: false };
  const calls = [],
    receipts = new Map();
  const report = { ...base.report };
  const clone = (value) => JSON.parse(JSON.stringify(value));
  function state(actor) {
    const self = actor === "host",
      peer = self ? "guest" : "host";
    return {
      ...clone(base.state),
      mode: "COOP",
      state: status,
      rev: String(rev),
      draftRev: String(draftRev),
      host: self,
      self: {
        slot: self ? 1 : 2,
        ready: ready[actor],
        effectiveReady: ready[actor] && joined,
        roleCode: status === "RUNNING" ? (self ? "R1" : "R2") : null,
      },
      partner: {
        joined: self ? joined : true,
        ready: ready[peer],
        effectiveReady: ready[peer] && joined,
        online: self ? joined : true,
      },
      canReady: status === "WAITING",
      canStart:
        self && status === "WAITING" && joined && ready.host && ready.guest,
      startedAt: status === "RUNNING" ? now() : null,
      playDeadline: status === "RUNNING" ? base.materials.playDeadline : null,
      attemptsRemaining: status === "RUNNING" ? 5 : null,
      hintsRemaining: status === "RUNNING" ? 3 : null,
      submissionState: submission,
      serverTime: now(),
      requestId: randomUUID(),
    };
  }
  function action(actor, name, body, originalExtra = {}, extra = {}) {
    const old = receipts.get(body.requestKey);
    if (old)
      return {
        ...clone(old),
        current: state(actor),
        replayed: true,
        changed: false,
        requestId: randomUUID(),
        ...(name === "PUBLIC_INVITE"
          ? {
              inviteKey: undefined,
              inviteSecret: undefined,
              expiresAt: undefined,
              requiresReissue: true,
            }
          : {}),
      };
    const value = {
      action: name,
      replayed: false,
      changed: true,
      original: { gameKey: key, rev: String(rev), ...originalExtra },
      current: state(actor),
      requestId: randomUUID(),
      ...extra,
    };
    receipts.set(body.requestKey, clone(value));
    return value;
  }
  function respond(actor, method, raw, body = {}) {
    calls.push({ actor, method, path: raw, body: clone(body) });
    const path = raw.split("?")[0];
    const reply = (value, status = 200) => ({ status, value: clone(value) });
    if (method === "GET") {
      if (path === "/api/stories")
        return reply({
          items: [
            {
              ...base.story,
              intro: undefined,
              setting: undefined,
              policyCode: undefined,
              noticeHash: undefined,
              noticeSummary: undefined,
            },
          ],
          nextCursor: null,
          requestId: randomUUID(),
        });
      if (path.startsWith("/api/stories/")) return reply(base.story);
      if (path === "/api/member/privacy/policies/friendly")
        return reply(base.notice);
      if (path === "/api/games/current")
        return reply({ game: state(actor), requestId: randomUUID() });
      if (path === "/api/games/" + key) return reply(state(actor));
      if (path.endsWith("/materials")) {
        const role = actor === "host" ? 0 : 1;
        return reply({
          ...clone(base.materials),
          rev: String(rev),
          roles: [base.materials.roles[role]],
          clues: [
            {
              code: role ? "C2" : "C1",
              title: role ? "손님 전용" : "방장 전용",
              body: role ? "PRIVATE_GUEST_ONLY" : "PRIVATE_HOST_ONLY",
              personCode: "P1",
              read: true,
              readAt: now(),
            },
          ],
        });
      }
      if (path.endsWith("/report"))
        return reply({
          gameKey: key,
          draftRev: String(draftRev),
          report,
          proposal: proposal
            ? {
                ...proposal,
                canWithdraw: actor === "host",
                canAccept: actor !== "host",
                canReject: actor !== "host",
              }
            : null,
          pending,
          submissionState: submission,
          requestId: randomUUID(),
        });
    }
    if (receipts.has(body.requestKey))
      return reply(
        action(actor, receipts.get(body.requestKey).action, body),
        path.endsWith("/invitations") || path.endsWith("/report/proposals")
          ? 201
          : 200,
      );
    if (path === "/api/games")
      return reply(
        action(actor, "PUBLIC_CREATE", body, {
          state: "WAITING",
          lobbyExpiresAt: base.materials.playDeadline,
        }),
        201,
      );
    if (path.endsWith("/invitations")) {
      rev++;
      inviteKey = randomUUID();
      secret = "A".repeat(43);
      return reply(
        action(
          actor,
          "PUBLIC_INVITE",
          body,
          { inviteKey },
          {
            inviteKey,
            inviteSecret: secret,
            expiresAt: base.materials.playDeadline,
            requiresReissue: false,
            blockedReason: null,
          },
        ),
        201,
      );
    }
    if (path.endsWith("/invitations/inspect"))
      return reply({
        inviteKey,
        title: base.story.title,
        difficulty: 2,
        limitSec: 3600,
        playerCount: 2,
        expiresAt: base.materials.playDeadline,
        lobbyExpiresAt: base.materials.playDeadline,
        policyCode: base.notice.policyCode,
        noticeHash: base.notice.noticeHash,
        noticeSummary: base.notice.body,
        requestId: randomUUID(),
      });
    if (path.endsWith("/invitations/join")) {
      joined = true;
      rev++;
      ready.host = ready.guest = false;
      return reply(action(actor, "PUBLIC_JOIN", body));
    }
    if (path.endsWith("/ready")) {
      ready[actor] = body.ready;
      rev++;
      return reply(action(actor, "PUBLIC_READY", body));
    }
    if (path.endsWith("/start")) {
      status = "RUNNING";
      rev++;
      return reply(
        action(actor, "PUBLIC_START", body, {
          state: status,
          lobbyExpiresAt: base.materials.playDeadline,
        }),
      );
    }
    if (method === "PATCH" && path.endsWith("/report")) {
      Object.assign(report, body.report);
      draftRev++;
      rev++;
      proposal = null;
      submission = "NONE";
      return reply(
        action(actor, "PUBLIC_REPORT_EDIT", body, {
          draftRev: String(draftRev),
        }),
      );
    }
    if (path.endsWith("/report/proposals")) {
      rev++;
      submission = "PROPOSED";
      proposal = {
        reportKey: randomUUID(),
        sourceRev: String(draftRev),
        report: clone(report),
        proposerSlot: 1,
        state: "PROPOSED",
      };
      return reply(
        action(actor, "PUBLIC_REPORT_PROPOSE", body, {
          reportKey: proposal.reportKey,
        }),
        201,
      );
    }
    if (path.endsWith("/respond")) {
      rev++;
      const reportKey = proposal.reportKey;
      if (body.decision === "ACCEPT") {
        submission = "PENDING";
        pending = {
          reportKey,
          submitNo: 1,
          report: clone(proposal.report),
          acceptedAt: now(),
          gradeDeadline: base.materials.playDeadline,
        };
      } else submission = "NONE";
      proposal = null;
      return reply(
        action(
          actor,
          "PUBLIC_REPORT_" + body.decision,
          body,
          body.decision === "ACCEPT"
            ? {
                reportKey,
                jobKey: randomUUID(),
                ordinal: 1,
                acceptedAt: pending.acceptedAt,
                gradeDeadline: pending.gradeDeadline,
              }
            : {},
        ),
      );
    }
    if (path.endsWith("/heartbeat")) return { status: 204, value: null };
    return reply({ code: "SYNTHETIC_UNSUPPORTED" }, 403);
  }
  return { respond, calls, state, gameKey: key };
}
