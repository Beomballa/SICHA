import assert from "node:assert/strict";

/**
 * 폐기형 HTTPS 세션에서 역할·조합의 실제 저장과 복구를 시험한다.
 * @param {object} flow 현재 page, 고정 apiPath/baseUrl 및 기존 API·편집·응답 제어·레이아웃 헬퍼다.
 * 실제 계정·DB·공개 상태는 사용하지 않으며 실패는 상위 브라우저 시험으로 전달한다.
 */
export async function exerciseRolePairs({
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
  /** 자원과 활성 필터를 명시하고 키 목록에서 선택한 단건 조회의 완료를 기다린다. */
  async function open(resource, key, active = true) {
    await openSection(page, "child");
    await page.select("#child-resource", resource);
    await page.select("#child-filter", String(active));
    await page.waitForSelector(`[data-child-key="${key}"]`);
    await page.click(`[data-child-key="${key}"]`);
    await page.waitForFunction(
      () => !document.getElementById("child-resource").disabled,
    );
  }

  /** 서버 조회까지 끝난 논리 삭제·복원을 확인하며 자동 재전송을 하지 않는다. */
  async function changeActive() {
    await openSection(page, "child");
    await page.click("#child-active");
    await notice(page, "최신 원고를 조회했습니다");
    await page.waitForFunction(
      () => !document.getElementById("child-resource").disabled,
    );
  }

  await edit(
    page,
    "answer",
    "methodAnswer",
    "역할 조합 작업 동안 보존할 다른 영역 입력",
  );
  await openSection(page, "child");
  await page.select("#child-resource", "roles");
  await page.waitForFunction(() =>
    document
      .getElementById("child-list-status")
      .textContent.includes("이 상태의 역할이 없습니다"),
  );
  await page.click("#child-new");
  await edit(page, "child", "code", "A");
  await edit(page, "child", "name", " ");
  await page.click("#child-save");
  await notice(page, "공백일 수 없습니다");
  assert.equal(
    await page.$eval("#child-name", (e) => document.activeElement === e),
    true,
  );
  await edit(page, "child", "name", "역할 A");
  await edit(
    page,
    "child",
    "brief",
    "<script>실행 금지 역할</script>\n역할 전용 비밀",
  );
  await save(page, "child");
  assert.equal(await page.$eval("#child-code", (e) => e.readOnly), true);
  assert.equal(
    await page.$eval(
      "#child-brief-record",
      (e) => e.querySelectorAll("script").length,
    ),
    0,
  );
  assert.match(
    await page.$eval("#child-brief-record", (e) => e.textContent),
    /<script>실행 금지 역할<\/script>/,
  );
  await layout(page, "child-role-editor");

  await page.click("#child-new");
  await edit(page, "child", "code", "B");
  await edit(page, "child", "name", "역할 B");
  await save(page, "child");
  const roles = await api(page, `${apiPath}/roles`);
  assert.equal(roles.status, 200);
  assert.deepEqual(Object.keys(roles.body.items[0]).sort(), [
    "activeYn",
    "code",
    "updatedAt",
  ]);
  assert.doesNotMatch(JSON.stringify(roles.body), /역할 전용 비밀|역할 A/);

  // 다른 자원의 지연 목록이 새 조합의 선택·입력을 덮지 않아야 한다.
  const heldUrl = `${baseUrl}${apiPath}/roles?size=20&activeYn=true`;
  await page.evaluate((url) => {
    const original = window.fetch;
    window.__roleListFetch = original;
    window.__roleListConsumed = false;
    window.fetch = async (...args) => {
      const response = await original(...args);
      if (response.url === url) {
        const json = response.json.bind(response);
        response.json = async () => {
          const value = await json();
          // 실제 본문 소비 뒤 요청/목록의 연속 마이크로태스크가 끝난 다음 턴에서만 확인한다.
          setTimeout(() => {
            window.__roleListConsumed = true;
          }, 0);
          return value;
        };
      }
      return response;
    };
  }, heldUrl);
  const oldList = await holdResponse(page, heldUrl);
  await page.click("#child-list");
  await oldList.ready;
  await page.select("#child-resource", "pairs");
  await page.waitForFunction(() =>
    document
      .getElementById("child-list-status")
      .textContent.includes("이 상태의 역할 조합이 없습니다"),
  );
  await page.click("#child-new");
  await edit(page, "child", "roleA", "B");
  await edit(page, "child", "roleB", "A");
  assert.match(
    await page.$eval("#pair-key-preview", (e) => e.textContent),
    /A~B/,
  );
  /** 목록·커서 조작 상태와 양쪽 입력을 함께 확인해 폼만 보존된 잘못된 수락도 잡는다. */
  const listState = () =>
    page.evaluate(() => ({
      resource: document.getElementById("child-resource").value,
      keys: [...document.querySelectorAll("#child-rows [data-child-key]")].map(
        (e) => e.dataset.childKey,
      ),
      status: document.getElementById("child-list-status").textContent,
      nextHidden: document.getElementById("child-next").hidden,
      nextDisabled: document.getElementById("child-next").disabled,
      roleA: document.getElementById("child-roleA").value,
      roleB: document.getElementById("child-roleB").value,
    }));
  const beforeList = await listState();
  assert.equal(beforeList.resource, "pairs");
  assert.deepEqual(beforeList.keys, []);
  assert.equal(beforeList.nextHidden, true);
  assert.equal(beforeList.roleA, "B");
  assert.equal(beforeList.roleB, "A");
  try {
    await oldList.release();
    await page.waitForFunction(() => window.__roleListConsumed === true);
    assert.deepEqual(await listState(), beforeList);
    assert.equal((await page.$$("#child-code")).length, 0);
  } finally {
    await page.evaluate(() => {
      window.fetch = window.__roleListFetch;
      delete window.__roleListFetch;
      delete window.__roleListConsumed;
    });
  }
  await layout(page, "child-pair-new");

  let creates = 0;
  const count = (request) => {
    if (
      request.method() === "POST" &&
      new URL(request.url()).pathname === `${apiPath}/pairs`
    )
      creates++;
  };
  page.on("request", count);
  const lost = await loseResponse(page, `${baseUrl}${apiPath}/pairs`);
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(creates, 1);
  assert.equal(await page.$eval("#child-resource", (e) => e.disabled), true);
  assert.match(await page.$eval("#latest-values", (e) => e.textContent), /A~B/);
  assert.equal(
    await page.$eval("#answer-methodAnswer", (e) => e.value),
    "역할 조합 작업 동안 보존할 다른 영역 입력",
  );
  await lost.detach();
  page.off("request", count);
  await page.click("#accept-latest");
  assert.equal(await page.$eval("#child-save", (e) => e.hidden), true);
  assert.equal(
    await page.$eval("#child-roleA-record", (e) => e.textContent),
    "A",
  );
  assert.equal(
    await page.$eval("#child-roleB-record", (e) => e.textContent),
    "B",
  );
  await layout(page, "child-pair-record");

  await open("roles", "A");
  await page.click("#child-active");
  await notice(page, "참조 중인 자료");
  await page.waitForFunction(
    () => !document.getElementById("accept-latest").disabled,
  );
  assert.equal(
    (await api(page, `${apiPath}/roles/A`)).body.item.activeYn,
    true,
  );
  await page.click("#accept-latest");
  await open("pairs", "A~B");
  await changeActive();
  assert.equal(
    (await api(page, `${apiPath}/pairs/A~B`)).body.item.activeYn,
    false,
  );
  await open("roles", "A");
  await changeActive();
  await open("pairs", "A~B", false);
  const before = (await api(page, apiPath)).body.editRev;
  await page.click("#child-active");
  await page.waitForFunction(
    () =>
      document.getElementById("notice").classList.contains("error") &&
      !document.getElementById("child-resource").disabled,
  );
  assert.equal((await api(page, apiPath)).body.editRev, before);
  assert.equal(
    (await api(page, `${apiPath}/pairs/A~B`)).body.item.activeYn,
    false,
  );
  await layout(page, "child-pair-restore-error");
  await open("roles", "A", false);
  await changeActive();
  await open("pairs", "A~B", false);
  await changeActive();
  assert.equal(
    (await api(page, `${apiPath}/pairs/A~B`)).body.item.activeYn,
    true,
  );

  await open("roles", "A");
  await edit(page, "child", "brief", "충돌 후 보존한 역할 원고");
  const revision = (await api(page, apiPath)).body.editRev;
  assert.equal(
    (
      await api(page, `${apiPath}/roles/A`, "PATCH", {
        expectedRev: revision,
        changes: { name: "별도 요청의 최신 역할" },
      })
    ).status,
    200,
  );
  await page.click("#child-save");
  await notice(page, "검토 전 저장은 차단");
  assert.equal(
    await page.$eval("#child-brief", (e) => e.value),
    "충돌 후 보존한 역할 원고",
  );
  assert.match(
    await page.$eval("#latest-values", (e) => e.textContent),
    /별도 요청의 최신 역할/,
  );
  await layout(page, "child-role-conflict");
  await page.click("#accept-latest");
  await save(page, "child");
  assert.equal(
    (await api(page, `${apiPath}/roles/A`)).body.item.brief,
    "충돌 후 보존한 역할 원고",
  );
  assert.equal(
    await page.$eval("#answer-methodAnswer", (e) => e.value),
    "역할 조합 작업 동안 보존할 다른 영역 입력",
  );
  await page.focus("#child-resource");
  await page.keyboard.press("Tab");
  assert.equal(
    await page.evaluate(() => document.activeElement.id),
    "child-filter",
  );
  console.log(
    "PASS HTTPS roles/pairs, canonical keys, reference guards, restore errors, lost response, cross-resource race and draft preservation",
  );
}
