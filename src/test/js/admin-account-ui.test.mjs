import { readFileSync } from "node:fs";
import { test } from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";

const source = readFileSync(
  new URL("../../main/resources/static/admin/auth.js", import.meta.url),
  "utf8",
);
const footer = /\n  restore\(\);\n\}\)\(\);\s*$/;
assert.match(source, footer);

/**
 * 기존 계정 VM 시험에서 확인 창 취소의 표시 경계만 기록한다.
 * @param {Function} fetch 기존 시험의 응답 함수. null은 허용하지 않는다.
 * @returns {object} 계정 함수·가상 요소·취소 기록. 실제 확인 컴포넌트 시험은 대체하지 않는다.
 */
function ui(fetch) {
  const cancellations = [];
  class Element {
    children = [];
    hidden = false;
    checked = false;
    textContent = "";
    innerHTML = "";
    classList = { toggle() {} };
    append(...children) {
      this.children.push(...children);
    }
    replaceChildren(...children) {
      this.children = children;
    }
    addEventListener() {}
    setAttribute() {}
    focus() {}
    querySelector(selector) {
      if (selector === '[name="confirmImpact"]') return checkbox;
      if (selector === "h2") return new Element();
      return null;
    }
  }
  const checkbox = new Element();
  const elements = new Map();
  const document = {
    body: { dataset: { screen: "ADMIN_ACCOUNT_DETAIL" } },
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    },
    createElement: () => new Element(),
    addEventListener() {},
  };
  const script = source.replace(
    footer,
    "\n  globalThis.testUi = { showReactivationPreview, restore };\n})();",
  );
  const context = {
    document,
    window: {
      addEventListener() {},
      AdminUI: {
        cancelConfirmation() {
          cancellations.push("cancel");
        },
      },
    },
    fetch,
    Response,
    Date,
  };
  context.AdminUI = context.window.AdminUI;
  vm.runInNewContext(script, context, { filename: "auth.js", timeout: 1000 });
  return { ...context.testUi, elements, checkbox, cancellations };
}

const view = {
  accountKey: "22222222-2222-4222-8222-222222222222",
  activeYn: false,
  enrolled: true,
  mfaState: "READY",
  permissions: ["CREATE"],
  editRev: "3",
  createdAt: "",
  updatedAt: "",
};

test("a refreshed relation preview requires a new explicit confirmation", () => {
  const page = ui(async () => {
    throw new Error("Unexpected network request");
  });
  const preview = (impactHash) => ({
    account: view,
    ownedCount: 1,
    accessCount: 0,
    relationSample: [
      { storyCode: "H0_CASE", relation: "OWNER", activeYn: true },
    ],
    truncated: false,
    impactHash,
    nextCredentialAction: "LOGIN",
  });
  page.showReactivationPreview(preview("a".repeat(64)));
  assert.equal(page.checkbox.checked, false);
  page.checkbox.checked = true;
  page.showReactivationPreview(preview("b".repeat(64)));
  assert.ok(page.cancellations.length >= 2);
  assert.equal(page.checkbox.checked, false);
  assert.equal(page.elements.get("reactivation-confirm").hidden, false);
});

test("state refresh after lost self-change response directs revoked session to login", async () => {
  const page = ui(async (url) =>
    url.endsWith("/csrf")
      ? new Response(
          JSON.stringify({ token: "synthetic", headerName: "X-CSRF-TOKEN" }),
          { status: 200 },
        )
      : new Response(JSON.stringify({ code: "AUTH_REQUIRED" }), {
          status: 401,
        }),
  );
  await page.restore();
  assert.ok(page.cancellations.length > 0);
  const content = page.elements.get("content");
  assert.match(content.innerHTML, /href="\/admin\/login"/);
  assert.match(content.innerHTML, /변경 요청을 다시 보내지 말고/);
  assert.doesNotMatch(content.innerHTML, /data-refresh/);
});
