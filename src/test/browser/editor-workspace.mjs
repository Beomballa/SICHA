import assert from "node:assert/strict";

const tasks = ["write", "review", "read", "evidence", "version"];

/**
 * 목차·상태 링크 이동에서 확인된 동일 원점 HTTPS 브라우저 favicon 요청만 구분한다.
 * @param {object} request method/url/resourceType을 제공하는 Puppeteer 요청. null은 허용하지 않는다.
 * @param {string} pageOrigin 관측 시작 시 문서 URL의 원점.
 * @returns {boolean} GET·other·/favicon.ico이며 쿼리·프래그먼트가 전혀 없을 때만 true.
 * 잘못된 URL과 fetch/xhr/script/image 등 나머지 요청은 모두 false다.
 */
export function isBrowserFaviconRequest(request, pageOrigin) {
  if (request.method() !== "GET" || request.resourceType() !== "other")
    return false;
  try {
    const url = new URL(request.url());
    return (
      url.protocol === "https:" &&
      url.origin === pageOrigin &&
      url.pathname === "/favicon.ico" &&
      url.search === "" &&
      url.hash === "" &&
      !url.href.includes("?") &&
      !url.href.includes("#")
    );
  } catch {
    return false;
  }
}

/**
 * 실제 작업 버튼과 대상의 조상 summary만 클릭해 상호작용을 준비한다.
 * @param {object} page 제품 편집기가 로드된 Puppeteer Page. null은 허용하지 않는다.
 * @param {string} selector 정확히 하나의 기존 요소를 가리키는 CSS 선택자.
 * @returns {Promise<{task: string|null, openedDetails: number}>} 선택한 작업과 실제로 연 disclosure 수.
 * @throws {Error} 대상이 없거나 중복되거나 원래 숨겨진 원고 영역·필드·결과이면 실패한다.
 * disabled는 바꾸지 않는다. 원고 영역은 호출자가 기존 목차로 먼저 선택해야 한다.
 */
export async function selectEditorTaskFor(page, selector) {
  const matches = await page.$$(selector);
  try {
    assert.equal(
      matches.length,
      1,
      `대상은 정확히 하나여야 합니다: ${selector}`,
    );
    const target = matches[0];
    const task = await target.evaluate(
      (node) =>
        node.closest("[data-workspace-panel]")?.dataset.workspacePanel ?? null,
    );
    if (task !== null) {
      assert.ok(tasks.includes(task), "알 수 없는 작업입니다.");
      await page.click(`[data-workspace-task="${task}"]`);
      await page.waitForFunction(
        (name) =>
          document
            .querySelector(`[data-workspace-task="${name}"]`)
            ?.getAttribute("aria-pressed") === "true",
        {},
        task,
      );
    }
    assert.equal(
      await target.evaluate((node) => Boolean(node.closest("[hidden]"))),
      false,
      `원래 숨겨진 대상은 작업 준비로 열지 않습니다: ${selector}`,
    );
    let openedDetails = 0;
    // 바깥 disclosure부터 열고 실제 summary를 클릭한다. 페이지 클릭은 교체하지 않는다.
    while (true) {
      const handle = await target.evaluateHandle((node) => {
        let outer = null;
        for (
          let parent = node.parentElement;
          parent;
          parent = parent.parentElement
        ) {
          if (
            parent.matches("details:not([open])") &&
            !parent.querySelector(":scope > summary")?.contains(node)
          )
            outer = parent;
        }
        return outer?.querySelector(":scope > summary") ?? null;
      });
      try {
        const summary = handle.asElement();
        if (!summary) break;
        await summary.click();
        assert.equal(
          await summary.evaluate((node) => node.parentElement.open),
          true,
        );
        openedDetails++;
      } finally {
        await handle.dispose();
      }
    }
    assert.equal(
      await target.evaluate((node) => node.checkVisibility()),
      true,
      `대상이 실제로 보이지 않습니다: ${selector}`,
    );
    return { task, openedDetails };
  } finally {
    await Promise.all(matches.map((node) => node.dispose()));
  }
}

/**
 * 선택한 작업·초점과 작업 밖의 사건명·수정번호·미저장·복구 맥락이 보이는지 확인한다.
 * @param {object} page 준비된 Puppeteer Page.
 * @param {string} task 다섯 작업 중 하나.
 * @returns {Promise<void>} DOM 표시·선택 상태를 단언한다.
 * @throws {Error} 숨은 초점·중복 선택·잘못된 제어 영역이면 실패한다.
 */
async function assertTask(page, task) {
  const state = await page.evaluate(() => {
    const buttons = [...document.querySelectorAll("[data-workspace-task]")];
    const panels = [...document.querySelectorAll("[data-workspace-panel]")];
    return {
      selected: buttons
        .filter((node) => node.getAttribute("aria-pressed") === "true")
        .map((node) => node.dataset.workspaceTask),
      visible: panels
        .filter((node) => !node.hidden)
        .map((node) => node.dataset.workspacePanel),
      controls: buttons.every((button) => {
        const panel = document.getElementById(
          button.getAttribute("aria-controls"),
        );
        return (
          panel?.dataset.workspacePanel === button.dataset.workspaceTask &&
          !button.hasAttribute("role")
        );
      }),
      focused:
        document.activeElement !== document.body &&
        document.activeElement.checkVisibility(),
      context: [
        "state-heading",
        "version-summary",
        "draft-summary",
        "workspace-status",
        "notice",
      ].every((id) => {
        const node = document.getElementById(id);
        return (
          node?.checkVisibility() &&
          Boolean(node.textContent.trim()) &&
          !node.closest("[data-workspace-panel], details")
        );
      }),
    };
  });
  assert.deepEqual(state.selected, [task]);
  assert.deepEqual(state.visible, [task]);
  assert.equal(state.controls, true);
  assert.equal(state.focused, true);
  assert.equal(
    state.context,
    true,
    "작업 이동으로 필수 사건 맥락을 숨기지 않습니다.",
  );
}

/**
 * 실제 폼과 원고 버퍼를 보존한 작업 이동·키보드·상태 링크·조건 폼을 검증한다.
 * @param {object} page 호출자가 시나리오를 준비한 기존 제품 Puppeteer Page.
 * 초기 선택자로 편집 작업 UI 준비를 기다리며 모든 네트워크 요청의 완료를 요구하지 않는다.
 * 관측은 요청 시작 사건을 수집하므로 이미 진행 중인 응답의 완료는 새 요청으로 세지 않는다.
 * 알려진 동일 원점 HTTPS GET·other·/favicon.ico(쿼리·프래그먼트 없음)만
 * 브라우저 리소스로 별도 집계하고 나머지 업무·기타 요청은 모두 0을 단언한다.
 * @param {object} options 호출자가 준비한 시나리오. 서버 응답·권한을 이 helper에서 만들지 않는다.
 * @param {object|null} options.keepBuffer 선택 사항. {section, field, value}의 실제 원고 텍스트
 * 필드에 value를 입력한 뒤 keep으로 전환한다. section은 basic/answer/reveal/child이며
 * child는 호출자가 실제 자료를 선택해 두어야 한다. 미지정이면 기존 입력만 보존 검사한다.
 * @param {string|null} options.unknownTarget 실제 요청 유실로 data-state=unknown이 된 상태 노드의
 * CSS 선택자. 미지정이면 unknown 검증은 not-requested이며 가짜 상태를 주입하지 않는다.
 * @param {string[]} options.disabledForms 실제 비활성 submit을 가진 기존 폼 선택자 배열.
 * 비어 있으면 조건 폼 검증은 수행하지 않는다. 서버 조건을 만들거나 제출하지 않는다.
 * @returns {Promise<{tasks: string[], requests: number, browserResourceRequests: number,
 * sameDom: boolean, keyboard: boolean,
 * focus: boolean, keepBuffer: string, unknownTarget: string, disabledForms: string[]}>}
 * requests는 업무·기타 요청 수, browserResourceRequests는 엄격한 조건을 충족한 브라우저
 * 정적 favicon 요청 수다. 업무 0을 전체 HTTP 0으로 해석하지 않는다.
 * 실제 수행한 범위만 반환한다. 버퍼·unknown은 verified 또는 not-requested다.
 * @throws {Error} 업무·기타 요청 발생·DOM/입력 교체·숨은 초점·잘못된 사전 조건·보존 위반이면 실패한다.
 * 원고 입력은 의도적으로 남으며 저장·폐기하지 않는다. 별도 실행기나 서버를 시작하지 않는다.
 */
export async function exerciseEditorWorkspace(
  page,
  { keepBuffer = null, unknownTarget = null, disabledForms = [] } = {},
) {
  await page.waitForSelector("#editor:not([hidden]) [data-workspace-task]");
  assert.deepEqual(
    await page.$$eval("[data-workspace-task]", (nodes) =>
      nodes.map((node) => node.dataset.workspaceTask),
    ),
    tasks,
  );
  const requests = [];
  const browserResourceRequests = [];
  const requestMetadata = [];
  const pageOrigin = new URL(page.url()).origin;

  /**
   * 증명된 브라우저 정적 favicon만 별도 집계하고 업무·기타 요청은 모두 0 단언 대상으로 남긴다.
   * 실패 진단용 메타데이터만 최대 20건 보관하며 집계 수에는 제한을 두지 않는다.
   * @param {object} request Puppeteer 요청. 원문 URL·쿼리·프래그먼트·자격·헤더·본문은 보관하지 않는다.
   * @returns {void} 프로토콜·유형은 32자, 같은 원점 HTTP(S) 경로는 200자로 제한한다.
   */
  const observe = (request) => {
    if (isBrowserFaviconRequest(request, pageOrigin)) {
      browserResourceRequests.push(request.method());
      return;
    }
    requests.push(request.method());
    // favicon 이외 모든 요청은 메서드·자원 종류와 관계없이 기존 빈 배열 단언에 포함한다.
    if (requestMetadata.length >= 20) return;
    let protocol = "invalid";
    let pathname = null;
    try {
      const url = new URL(request.url());
      protocol =
        url.protocol === "data:" ? "data-literal" : url.protocol.slice(0, 32);
      // data·blob 등 비 HTTP(S) 원문과 다른 원점의 경로는 진단에 포함하지 않는다.
      if (
        (url.protocol === "http:" || url.protocol === "https:") &&
        url.origin === pageOrigin
      ) {
        pathname = url.pathname.slice(0, 200);
      }
    } catch {
      // 파싱 오류 객체에는 원문이 포함될 수 있으므로 보관하거나 출력하지 않는다.
    }
    requestMetadata.push({
      protocol,
      resourceType: request.resourceType().slice(0, 32),
      pathname,
    });
  };
  page.on("request", observe);
  let snapshot;
  let unknown;
  try {
    if (keepBuffer) {
      const { section, field, value } = keepBuffer;
      assert.ok(["basic", "answer", "reveal", "child"].includes(section));
      assert.match(field, /^[A-Za-z][A-Za-z0-9]*$/);
      assert.equal(typeof value, "string");
      const mode = `#${section}-${field}-mode`;
      const input = `#${section}-${field}`;
      const nav = `#editor-nav a[href="#${section}-heading"]`;
      await selectEditorTaskFor(page, nav);
      await page.click(nav);
      await selectEditorTaskFor(page, mode);
      assert.equal(await page.$eval(mode, (node) => node.disabled), false);
      await page.select(mode, "value");
      await page.waitForSelector(input, { visible: true });
      assert.equal(
        await page.$eval(input, (node) => node.disabled || node.readOnly),
        false,
      );
      assert.equal(
        await page.$eval(input, (node) =>
          node.matches('textarea, input[type="text"]'),
        ),
        true,
      );
      assert.equal(
        await page.$eval(
          input,
          (node, expected) => node.value !== expected,
          value,
        ),
        true,
        "keep 버퍼 검증에는 기존 값과 다른 입력이 필요합니다.",
      );
      await page.click(input);
      const originalLength = await page.$eval(
        input,
        (node) => node.value.length,
      );
      // 플랫폼 단축키 대신 방향키와 Shift로 기존 문자열 전체를 선택한다.
      for (let index = 0; index < originalLength; index++) {
        await page.keyboard.press("ArrowLeft");
      }

      await page.keyboard.down("Shift");
      try {
        for (let index = 0; index < originalLength; index++) {
          await page.keyboard.press("ArrowRight");
        }
      } finally {
        await page.keyboard.up("Shift");
      }
      await page.keyboard.press("Backspace");
      assert.equal(
        await page.$eval(input, (node) => node.value),
        "",
        "keep 버퍼 입력 전에 기존 문자열 전체를 비워야 합니다.",
      );
      if (value) {
        await page.type(input, value);
      }
      assert.equal(
        await page.$eval(
          input,
          (node, expected) => node.value === expected,
          value,
        ),
        true,
      );
      await page.select(mode, "keep");
      assert.equal(await page.$eval(input, (node) => node.hidden), true);
      await page.waitForSelector("#workspace-input-status", { visible: true });
    }
    if (unknownTarget) {
      unknown = await page.$(unknownTarget);
      assert.ok(unknown, "호출자가 실제 unknown 상태를 먼저 준비해야 합니다.");
      assert.equal(
        await unknown.evaluate((node) => node.dataset.state),
        "unknown",
      );
    }
    // 값은 브라우저 문맥에만 남기고 실패 메시지·반환 영수증에 원문을 복사하지 않는다.
    snapshot = await page.evaluateHandle(() => {
      const selector =
        "#editor [id], #editor input, #editor select, #editor textarea, #editor form";
      const nodes = [...document.querySelectorAll(selector)];
      return {
        selector,
        nodes,
        inputs: nodes
          .filter((node) => node.matches("input, select, textarea"))
          .map((node) => ({
            node,
            value: node.value,
            checked: node.checked,
            disabled: node.disabled,
            required: node.required,
            maxLength: node.maxLength,
            describedBy: node.getAttribute("aria-describedby"),
          })),
        chapter: document.querySelector(
          '#editor-nav a[aria-current="location"]',
        ),
        unknown: [...document.querySelectorAll('[data-state="unknown"]')].map(
          (node) => ({ node, text: node.textContent }),
        ),
      };
    });
    for (const task of tasks) {
      await page.click(`[data-workspace-task="${task}"]`);
      await assertTask(page, task);
    }
    // 일반 버튼의 원래 Tab·Enter·Space 동작을 사용하며 가짜 tab 역할을 기대하지 않는다.
    await page.focus('[data-workspace-task="write"]');
    await page.keyboard.press("Enter");
    await assertTask(page, "write");
    for (const task of tasks.slice(1)) {
      await page.keyboard.press("Tab");
      assert.equal(
        await page.evaluate(() => document.activeElement.dataset.workspaceTask),
        task,
      );
      await page.keyboard.press("Space");
      await assertTask(page, task);
    }
    await page.keyboard.down("Shift");
    await page.keyboard.press("Tab");
    await page.keyboard.up("Shift");
    assert.equal(
      await page.evaluate(() => document.activeElement.dataset.workspaceTask),
      "evidence",
    );
    await page.keyboard.press("Enter");
    await assertTask(page, "evidence");

    for (const selector of disabledForms) {
      const form = await page.$(selector);
      assert.ok(form, `없는 조건 폼: ${selector}`);
      try {
        assert.equal(
          await form.evaluate(
            (node) =>
              node.matches("form") &&
              node.querySelector('button[type="submit"]')?.disabled,
          ),
          true,
        );
        await selectEditorTaskFor(page, selector);
        const disclosure = await form.evaluateHandle((node) =>
          node.closest("details"),
        );
        try {
          assert.ok(
            disclosure.asElement(),
            "조건 폼은 명시적 disclosure 안에 있어야 합니다.",
          );
          const summary = await disclosure.asElement().$(":scope > summary");
          try {
            await summary.click();
            assert.equal(await disclosure.evaluate((node) => node.open), false);
            await summary.click();
            assert.equal(await disclosure.evaluate((node) => node.open), true);
          } finally {
            await summary.dispose();
          }
          assert.equal(
            await form.evaluate(
              (node) => node.querySelector('button[type="submit"]').disabled,
            ),
            true,
          );
        } finally {
          await disclosure.dispose();
        }
      } finally {
        await form.dispose();
      }
    }
    if (unknown) {
      const { id, task } = await unknown.evaluate((node) => ({
        id: node.id,
        task: node.closest("[data-workspace-panel]")?.dataset.workspacePanel,
      }));
      assert.ok(id && tasks.includes(task));
      await page.click(
        `[data-workspace-task="${task === "write" ? "review" : "write"}"]`,
      );
      const link = `#workspace-status-links a[href="#${id}"]`;
      await page.waitForSelector(link, { visible: true });
      await page.focus(link);
      await page.keyboard.press("Enter");
      await assertTask(page, task);
      assert.equal(
        await unknown.evaluate((node) => document.activeElement === node),
        true,
      );
      assert.equal(
        await unknown.evaluate((node) => node.dataset.state),
        "unknown",
      );
    }
    const preserved = await snapshot.evaluate((before) => {
      const after = [...document.querySelectorAll(before.selector)];
      return {
        sameDom:
          before.nodes.length === after.length &&
          before.nodes.every((node, i) => node === after[i]),
        inputs: before.inputs.every(
          ({
            node,
            value,
            checked,
            disabled,
            required,
            maxLength,
            describedBy,
          }) =>
            node.value === value &&
            node.checked === checked &&
            node.disabled === disabled &&
            node.required === required &&
            node.maxLength === maxLength &&
            node.getAttribute("aria-describedby") === describedBy,
        ),
        chapter:
          document.querySelector('#editor-nav a[aria-current="location"]') ===
          before.chapter,
        unknown: before.unknown.every(
          ({ node, text }) =>
            node.textContent === text && node.dataset.state === "unknown",
        ),
      };
    });
    assert.deepEqual(preserved, {
      sameDom: true,
      inputs: true,
      chapter: true,
      unknown: true,
    });
    if (keepBuffer) {
      const { section, field, value } = keepBuffer;
      await page.click('[data-workspace-task="write"]');
      const mode = `#${section}-${field}-mode`;
      assert.equal(await page.$eval(mode, (node) => node.value), "keep");
      await page.select(mode, "value");
      assert.equal(
        await page.$eval(
          `#${section}-${field}`,
          (node, expected) => node.value === expected && node.checkVisibility(),
          value,
        ),
        true,
      );
      await page.select(mode, "keep");
    }
    // 브라우저의 표시·관찰 콜백까지 지난 뒤 업무·기타 0과 브라우저 정적 favicon 수를 구분한다.
    await page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => resolve())),
    );
    console.log(
      `편집 작업 요청 관측: 업무·기타 requests=${requests.length}, ` +
        `브라우저 정적 favicon browserResourceRequests=${browserResourceRequests.length} (전체 HTTP 0 주장이 아님)`,
    );
    assert.deepEqual(
      requests,
      [],
      "작업 이동·접기·버퍼 전환의 업무·기타 요청은 0이어야 합니다. 발생 시 미완료입니다." +
        ` 요청 순서별 메타데이터=${JSON.stringify(requestMetadata)}` +
        ` 브라우저 정적 favicon=${browserResourceRequests.length}` +
        ` (진단 생략=${requests.length - requestMetadata.length})`,
    );
    return {
      tasks: [...tasks],
      requests: requests.length,
      browserResourceRequests: browserResourceRequests.length,
      sameDom: preserved.sameDom,
      keyboard: true,
      focus: true,
      keepBuffer: keepBuffer ? "verified" : "not-requested",
      unknownTarget: unknownTarget ? "verified" : "not-requested",
      disabledForms: [...disabledForms],
    };
  } finally {
    page.off("request", observe);
    await snapshot?.dispose();
    await unknown?.dispose();
  }
}
