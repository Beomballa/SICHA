/**
 * 새 확인 창의 실제 버튼을 Puppeteer로 누르는 시험 전용 운전자를 설치한다.
 * @param {object} page 최초 이동 전의 Puppeteer 페이지. null은 허용하지 않는다.
 * @returns {Promise<object>} automatic을 false로 바꾸면 수동 시험에 맡기며 errors에는 예상 밖 오류가 남는다.
 * @throws {Error} 시험 알림 함수나 최초 문서 관찰자 설치가 실패하면 전파한다.
 */
export async function installConfirmationDriver(page) {
  const driver = { automatic: true, errors: [] };
  let navigation = 0;
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigation++;
  });
  page.on("dialog", async (dialog) => {
    try {
      if (dialog.type() === "beforeunload") await dialog.accept();
      else {
        driver.errors.push(`예상 밖 네이티브 대화상자: ${dialog.type()}`);
        await dialog.dismiss();
      }
    } catch (error) {
      driver.errors.push(error.message);
    }
  });
  await page.exposeFunction(
    "notifyTestConfirmation",
    async (documentId, serial) => {
      const originalNavigation = navigation;
      const automatic = driver.automatic;
      let node;
      try {
        node = await page.evaluateHandle(
          ({ documentId, serial }) => {
            const state = window.testConfirmationNodes;
            return state?.documentId === documentId
              ? state.nodes.get(serial)
              : null;
          },
          { documentId, serial },
        );
        const element = node.asElement();
        if (!element || !automatic) return;
        if (
          !(await element.evaluate(
            (dialog) => dialog.isConnected && dialog.open,
          ))
        )
          return;
        const button = await element.$("#ui-confirm-accept");
        if (!button) throw new Error("현재 확인 창의 확인 버튼이 없습니다.");
        try {
          await button.click();
        } finally {
          await button.dispose();
        }
      } catch (error) {
        // 같은 문서에 원래 노드가 남아 있으면 실제 클릭 오류를 숨기지 않는다.
        let gone = false;
        try {
          gone = await page.evaluate(
            ({ documentId, serial }) => {
              const state = window.testConfirmationNodes;
              return (
                state?.documentId !== documentId ||
                !state.nodes.get(serial)?.isConnected
              );
            },
            { documentId, serial },
          );
        } catch {
          gone = page.isClosed() || navigation !== originalNavigation;
        }
        if (!gone) driver.errors.push(error.message);
      } finally {
        await node?.dispose().catch((error) => {
          if (!page.isClosed() && navigation === originalNavigation)
            driver.errors.push(error.message);
        });
        if (!page.isClosed()) {
          await page
            .evaluate(
              ({ documentId, serial }) => {
                const state = window.testConfirmationNodes;
                if (state?.documentId === documentId)
                  state.nodes.delete(serial);
              },
              { documentId, serial },
            )
            .catch((error) => {
              if (!page.isClosed() && navigation === originalNavigation)
                driver.errors.push(error.message);
            });
        }
      }
    },
  );
  await page.evaluateOnNewDocument(() => {
    const state = {
      documentId: crypto.randomUUID(),
      nodes: new Map(),
      seen: new WeakSet(),
      serial: 0,
    };
    window.testConfirmationNodes = state;
    new MutationObserver(() => {
      const dialog = document.querySelector("#ui-confirm-dialog[open]");
      if (!dialog || state.seen.has(dialog)) return;
      state.seen.add(dialog);
      const serial = ++state.serial;
      state.nodes.set(serial, dialog);
      window.notifyTestConfirmation(state.documentId, serial);
    }).observe(document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["open"],
    });
  });
  return driver;
}

/**
 * 확인 창 종료와 동의 후 처리의 마이크로태스크를 기다린다.
 * @param {object} page 실제 화면의 Puppeteer 페이지. null은 허용하지 않는다.
 * @returns {Promise<void>} 열린 확인 창이 없으면 완료한다.
 * @throws {Error} 페이지가 닫히거나 제한 시간 안에 확인 창이 닫히지 않으면 실패한다.
 */
export async function waitForConfirmation(page) {
  await page.waitForFunction(
    () => !document.querySelector("#ui-confirm-dialog[open]"),
  );
  await page.evaluate(() => Promise.resolve());
}

/**
 * 실제 클릭 후 확인 창 종료를 기다리며, 새 자료 작성은 폼 초기화와 첫 키 입력의 실제 초점까지 기다린다.
 * @param {object} page 운전자가 설치된 Puppeteer 페이지. null은 허용하지 않는다.
 * @param {string} selector 실제 클릭할 비어 있지 않은 CSS 선택자. "#child-new"는 새 작성 승인 경로에만 사용하며 null은 허용하지 않는다.
 * @returns {Promise<void>} "#child-new"는 표시된 편집 영역의 첫 키 입력이 비어 있고 쓰기 가능하며 실제 초점을 받으면 완료한다. 다른 선택자는 확인 창 종료까지만 기다린다.
 * @throws {Error} 실제 클릭, 확인 창 종료 또는 새 작성 준비 대기가 실패하면 전파한다.
 */
export async function clickWithConfirmation(page, selector) {
  await page.click(selector);
  await waitForConfirmation(page);

  if (selector === "#child-new") {
    await page.waitForFunction(() => {
      const edit = document.getElementById("child-edit");
      const key = edit?.querySelector(
        '.field[data-fixed="false"] [data-value]',
      );
      return (
        edit &&
        !edit.closest("[hidden]") &&
        edit.getClientRects().length > 0 &&
        key instanceof HTMLInputElement &&
        !key.readOnly &&
        !key.matches(":disabled") &&
        key.value === "" &&
        !key.closest("[hidden]") &&
        key.getClientRects().length > 0 &&
        document.activeElement === key
      );
    });
  }
}

/**
 * 자료 종류 변경을 승인하는 경로에서 미저장 입력 확인 후 요청한 종류의 실제 반영까지 기다린다.
 * @param {object} page 운전자가 설치된 Puppeteer 페이지. null은 허용하지 않는다.
 * @param {"persons"|"roles"|"pairs"|"clues"|"clue-roles"|"hints"|"events"|"facts"|"rubrics"|"rubric-clues"|"grade-samples"} resource 실제 select에 열거된 자료 값만 허용하며 null은 허용하지 않는다.
 * @returns {Promise<void>} 확인 창이 닫히고 실제 자료 종류가 요청한 값으로 반영되며 자식 편집 영역이 숨겨지면 완료한다.
 * @throws {Error} 열거되지 않은 값, 선택·확인 창 종료 오류 또는 취소·거부 등으로 제한 시간 안에 요청한 종류 반영과 자식 편집 영역 숨김이 완료되지 않으면 실패한다.
 */
export async function selectChildResource(page, resource) {
  const allowed = await page.$$eval("#child-resource option", (options) =>
    options.map((option) => option.value),
  );
  if (!allowed.includes(resource))
    throw new Error(`열거되지 않은 자료 종류: ${resource}`);
  await page.select("#child-resource", resource);
  await waitForConfirmation(page);
  await page.waitForFunction(
    (resource) =>
      document.getElementById("child-resource").value === resource &&
      document.getElementById("child-edit").hidden,
    {},
    resource,
  );
}
