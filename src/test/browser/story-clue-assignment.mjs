import assert from "node:assert/strict";
import { clickWithConfirmation, selectChildResource } from "./confirmation.mjs";

/** 폐기형 HTTPS 사건에서 단서·배정의 원고, 관계, 충돌과 화면 상태를 검증한다. */
export async function exerciseClueAssignments({
  page,
  openSection,
  apiPath,
  baseUrl,
  api,
  edit,
  save,
  notice,
  layout,
  holdResponse,
  loseResponse,
}) {
  /** 자료 종류의 목록 조회 후 키를 선택해 단건 조회가 끝날 때까지 기다린다. */
  async function open(resource, key, active = true) {
    await openSection(page, "child");
    await selectChildResource(page, resource);
    await page.select("#child-filter", String(active));
    await page.waitForSelector(`[data-child-key="${key}"]`);
    await clickWithConfirmation(page, `[data-child-key="${key}"]`);
    await page.waitForFunction(
      () => !document.getElementById("child-resource").disabled,
    );
  }
  async function active() {
    await openSection(page, "child");
    await clickWithConfirmation(page, "#child-active");
    await notice(page, "최신 원고를 조회했습니다");
  }
  async function revision() {
    return (await api(page, apiPath)).body.editRev;
  }

  await edit(
    page,
    "answer",
    "timeAnswer",
    "단서 변경 중 보존할 다른 영역 입력",
  );
  await openSection(page, "child");
  await selectChildResource(page, "clues");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "A");
  await edit(page, "child", "title", "합성 단서 A");
  await edit(
    page,
    "child",
    "body",
    "<script>실행 금지</script>\n플레이어 본문",
  );
  await page.$eval("#child-body", (input) => {
    input.value = "가".repeat(12001);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "12000자를 넘을 수 없습니다");
  await edit(
    page,
    "child",
    "body",
    "<script>실행 금지</script>\n플레이어 본문",
  );
  await edit(page, "child", "sourceText", "제작자 출처만 표시");
  await page.$eval("#child-sourceText", (input) => {
    input.value = "가".repeat(401);
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "400자를 넘을 수 없습니다");
  await edit(page, "child", "sourceText", "제작자 출처만 표시");
  await save(page, "child");
  assert.equal((await api(page, `${apiPath}/clues/A`)).body.item.scope, "ROLE");
  assert.equal(
    await page.$eval("#child-scope-record", (e) => e.textContent),
    "ROLE",
  );
  assert.equal(
    await page.$$eval("#child-body-record script", (nodes) => nodes.length),
    0,
  );
  assert.match(
    await page.$eval("#child-body-record", (e) => e.textContent),
    /<script>실행 금지<\/script>/,
  );
  assert.match(
    await page.$eval("#warnings", (e) => e.textContent),
    /역할 배정 없음/,
  );
  assert.equal(
    await page.$eval('#warnings a[href="#child-resource"]', (e) =>
      e.textContent.includes("초안 저장 가능"),
    ),
    true,
  );
  await layout(page, "child-clue-record");

  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "code", "b");
  await edit(page, "child", "title", "합성 단서 B");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "코드는 영문 대문자");
  await edit(page, "child", "code", "B");
  await page.select("#child-scope-mode", "value");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "입력을 확인하세요");
  assert.equal((await api(page, `${apiPath}/clues/B`)).status, 404);
  await page.select("#child-scope", "ROLE");
  await save(page, "child");
  assert.equal((await api(page, `${apiPath}/clues/B`)).body.item.body, null);
  assert.equal(
    (
      await api(page, `${apiPath}/clues/B`, "PATCH", {
        expectedRev: await revision(),
        changes: { scope: null },
      })
    ).status,
    422,
  );
  await selectChildResource(page, "clue-roles");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "clueCode", "B");
  await edit(page, "child", "roleCode", "B");
  assert.match(
    await page.$eval("#pair-key-preview", (e) => e.textContent),
    /B~B/,
  );
  let posts = 0;
  const count = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname === `${apiPath}/clue-roles`
    )
      posts++;
  };
  page.on("request", count);
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/clue-roles`);
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(posts, 1);
  assert.match(await page.$eval("#latest-values", (e) => e.textContent), /B~B/);
  assert.equal(await page.$eval("#child-save", (e) => e.disabled), true);
  assert.equal(
    await page.$eval("#answer-timeAnswer", (e) => e.value),
    "단서 변경 중 보존할 다른 영역 입력",
  );
  await lost.detach();
  page.off("request", count);
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  assert.equal(await page.$eval("#child-save", (e) => e.hidden), true);
  assert.equal(
    (await api(page, `${apiPath}/clue-roles/B~B`)).body.item.roleCode,
    "B",
  );
  assert.equal(
    (
      await api(page, `${apiPath}/clue-roles/B~B`, "PATCH", {
        expectedRev: await revision(),
        changes: { roleCode: "A" },
      })
    ).status,
    400,
  );
  await layout(page, "child-clue-assignment");

  // 서로 다른 종류의 같은 코드 A와 A는 유효하며 ROLE의 미배정 경고를 해소한다.
  const sameCode = await api(page, `${apiPath}/clue-roles`, "POST", {
    expectedRev: await revision(),
    item: { clueCode: "A", roleCode: "A" },
  });
  assert.equal(sameCode.status, 201);
  assert.equal(sameCode.body.itemKey, "A~A");
  const linkedPerson = await api(page, `${apiPath}/clues/A`, "PATCH", {
    expectedRev: await revision(),
    changes: { personCode: "UI_PERSON" },
  });
  assert.equal(linkedPerson.status, 200);
  const personGuard = await api(
    page,
    `${apiPath}/persons/UI_PERSON/deactivate`,
    "POST",
    { expectedRev: await revision() },
  );
  assert.equal(personGuard.status, 409);
  assert.equal(
    (await api(page, `${apiPath}/persons/UI_PERSON`)).body.item.activeYn,
    true,
  );
  const clueGuard = await api(page, `${apiPath}/clues/A/deactivate`, "POST", {
    expectedRev: await revision(),
  });
  assert.equal(clueGuard.status, 409);
  const scopeGuard = await api(page, `${apiPath}/clues/A`, "PATCH", {
    expectedRev: await revision(),
    changes: { scope: "COMMON" },
  });
  assert.equal(scopeGuard.status, 409);
  assert.equal(
    (
      await api(page, `${apiPath}/pairs/A~B/deactivate`, "POST", {
        expectedRev: await revision(),
      })
    ).status,
    200,
  );
  const roleGuard = await api(page, `${apiPath}/roles/A/deactivate`, "POST", {
    expectedRev: await revision(),
  });
  assert.equal(roleGuard.status, 409);
  assert.equal(
    (await api(page, `${apiPath}/roles/A`)).body.item.activeYn,
    true,
  );
  assert.equal(
    (
      await api(page, `${apiPath}/pairs/A~B/reactivate`, "POST", {
        expectedRev: await revision(),
      })
    ).status,
    200,
  );
  assert.equal((await api(page, `${apiPath}/clues/A`)).body.item.scope, "ROLE");

  // 직접 조회·비교를 수락하기 전에는 외부 수정번호를 로컬 폼에 섞지 않는다.
  await clickWithConfirmation(page, "#child-list");
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await open("clues", "A");
  await page.select("#child-scope-mode", "value");
  await page.select("#child-scope", "COMMON");
  assert.equal(await page.$eval("#child-scope", (e) => e.value), "COMMON");
  await clickWithConfirmation(page, "#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(await page.$eval("#child-scope", (e) => e.value), "COMMON");
  assert.equal((await api(page, `${apiPath}/clues/A`)).body.item.scope, "ROLE");
  await layout(page, "child-clue-conflict");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  assert.equal(await page.$eval("#child-scope", (e) => e.value), "COMMON");
  await open("clue-roles", "A~A");
  await active();
  await open("clues", "A");
  await page.select("#child-scope-mode", "value");
  await page.select("#child-scope", "COMMON");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/clues/A`)).body.item.scope,
    "COMMON",
  );
  await open("clue-roles", "A~A", false);
  await clickWithConfirmation(page, "#child-active");
  await page.waitForFunction(() =>
    document.getElementById("notice").classList.contains("error"),
  );
  await page.waitForFunction(
    () => !document.getElementById("child-resource").disabled,
  );
  assert.equal(
    (await api(page, `${apiPath}/clue-roles/A~A`)).body.item.activeYn,
    false,
  );
  await open("clues", "A");
  await page.select("#child-scope-mode", "value");
  await page.select("#child-scope", "ROLE");
  await save(page, "child");
  await open("clue-roles", "A~A", false);
  await active();
  assert.equal(
    (await api(page, `${apiPath}/clue-roles/A~A`)).body.item.activeYn,
    true,
  );
  await open("clues", "A");
  await page.select("#child-personCode-mode", "clear");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/clues/A`)).body.item.personCode,
    null,
  );

  // 20개 경계를 넘는 키 목록은 메타데이터만 반환하고 afterKey로 다음 페이지를 조회한다.
  for (let index = 0; index < 19; index++) {
    const code = `QA_${String(index).padStart(2, "0")}`;
    assert.equal(
      (
        await api(page, `${apiPath}/clues`, "POST", {
          expectedRev: await revision(),
          item: { code, title: `합성 ${index}`, scope: "COMMON" },
        })
      ).status,
      201,
    );
  }
  const first = await api(page, `${apiPath}/clues?size=20&activeYn=true`);
  assert.equal(first.body.items.length, 20);
  assert.equal(first.body.hasNext, true);
  assert.deepEqual(Object.keys(first.body.items[0]).sort(), [
    "activeYn",
    "code",
    "updatedAt",
  ]);
  assert.doesNotMatch(
    JSON.stringify(first.body),
    /플레이어 본문|제작자 출처만 표시/,
  );
  const second = await api(
    page,
    `${apiPath}/clues?size=20&activeYn=true&afterKey=${first.body.nextAfterKey}`,
  );
  assert.equal(second.body.items.length, 1);
  assert.equal(second.body.hasNext, false);
  assert.equal(second.body.nextAfterKey, null);

  // 다른 자원의 늦은 목록이 새 자료의 입력이나 커서를 덮지 못한다.
  await clickWithConfirmation(page, "#child-list");
  await notice(page, "검토 전 저장은 차단");
  await clickWithConfirmation(page, "#accept-latest");
  await notice(page, "서버 최신값과 수정번호를 화면에 반영했습니다.");
  await selectChildResource(page, "clues");
  await page.waitForSelector('[data-child-key="QA_17"]');
  assert.equal(await page.$eval("#child-next", (e) => e.hidden), false);
  await clickWithConfirmation(page, "#child-next");
  await page.waitForSelector('[data-child-key="QA_18"]');
  const heldUrl = `${baseUrl}${apiPath}/clues?size=20&activeYn=true`;
  await page.evaluate((url) => {
    const original = window.fetch;
    window.__clueListFetch = original;
    window.__clueListConsumed = false;
    window.fetch = async (...args) => {
      const response = await original(...args);
      if (response.url === url) {
        const json = response.json.bind(response);
        response.json = async () => {
          const value = await json();
          setTimeout(() => {
            window.__clueListConsumed = true;
          }, 0);
          return value;
        };
      }
      return response;
    };
  }, heldUrl);
  const held = await holdResponse(page, heldUrl);
  await clickWithConfirmation(page, "#child-list");
  await held.ready;
  await selectChildResource(page, "clue-roles");
  await clickWithConfirmation(page, "#child-new");
  await edit(page, "child", "clueCode", "A");
  await edit(page, "child", "roleCode", "B");
  const before = await page.evaluate(() => ({
    resource: document.getElementById("child-resource").value,
    key: document.getElementById("pair-key-preview").textContent,
    rows: [...document.querySelectorAll("#child-rows [data-child-key]")].map(
      (e) => e.dataset.childKey,
    ),
  }));
  await held.release();
  await page.waitForFunction(() => window.__clueListConsumed === true);
  assert.deepEqual(
    await page.evaluate(() => ({
      resource: document.getElementById("child-resource").value,
      key: document.getElementById("pair-key-preview").textContent,
      rows: [...document.querySelectorAll("#child-rows [data-child-key]")].map(
        (e) => e.dataset.childKey,
      ),
    })),
    before,
  );
  await page.evaluate(() => {
    window.fetch = window.__clueListFetch;
    delete window.__clueListFetch;
    delete window.__clueListConsumed;
  });
  await page.focus("#child-resource");
  await page.keyboard.press("Tab");
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "child-filter",
  );
  await layout(page, "child-clue-new");
  assert.equal(
    await page.$eval("#answer-timeAnswer", (e) => e.value),
    "단서 변경 중 보존할 다른 영역 입력",
  );
  console.log(
    "PASS HTTPS clues/assignments, metadata paging, XSS, nullable defaults, guards, scope transitions, response loss, conflicts, races, drafts and layouts",
  );
}
