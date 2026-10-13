import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const observations = [];
const captured = new Set();

/** 실제 열린 제품 DOM의 읽기 폭·간격·카드 정렬과 잘림을 측정한다. 식별자/원문 값은 저장하지 않는다. */
async function measure(page) {
  return page.evaluate(() => {
    const visible = (node) =>
      node?.getClientRects().length && !node.closest("[hidden]");
    const rect = (node) => {
      const box = node.getBoundingClientRect();
      return {
        left: box.left,
        top: box.top,
        bottom: box.bottom,
        width: box.width,
        height: box.height,
      };
    };
    const panels = [...document.querySelectorAll("#review-check,#comparison")]
      .filter(visible)
      .map((panel) => ({
        id: panel.id,
        outerWidth: rect(panel).width,
        copy: [...panel.children]
          .filter((node) => node.matches("h2,p") && visible(node))
          .map((node) => ({ kind: node.tagName, ...rect(node) })),
      }));
    const review = document.querySelector("#review-check");
    let gaps = null;
    if (visible(review)) {
      const description = review.querySelector(":scope > p.muted"),
        actions = review.querySelector(":scope > .button-row"),
        status = document.getElementById("review-check-status");
      gaps = {
        descriptionToActions: rect(actions).top - rect(description).bottom,
        actionsToStatus: rect(status).top - rect(actions).bottom,
        horizontal: parseFloat(getComputedStyle(actions).columnGap),
      };
    }
    const cards = [
      ...document.querySelectorAll(".security-grid > section"),
    ].map((card) => ({
      card: rect(card),
      button: rect(card.querySelector("button")),
      form: rect(card.querySelector("form")),
      paddingBottom: parseFloat(getComputedStyle(card).paddingBottom),
    }));
    const dialogs = [...document.querySelectorAll("dialog[open]")].map(
      (dialog) => {
        const buttons = [...dialog.querySelectorAll("button")];
        return {
          id: dialog.id,
          ...rect(dialog),
          buttonHeights: buttons.map((node) => rect(node).height),
          groupGap: dialog.querySelector(".actions,.ui-confirm-actions")
            ? parseFloat(
                getComputedStyle(
                  dialog.querySelector(".actions,.ui-confirm-actions"),
                ).columnGap,
              )
            : null,
        };
      },
    );
    // 1px 접근성 전용 label은 의도된 비시각 표현이다. 실제 표시 텍스트의 잘림과 구분한다.
    const clipped = [
      ...document.querySelectorAll("h1,h2,h3,p,label,button,legend"),
    ].filter(
      (node) =>
        visible(node) &&
        !node.closest(".visually-hidden") &&
        ["hidden", "clip"].includes(getComputedStyle(node).overflowY) &&
        node.scrollHeight > node.clientHeight + 1,
    ).length;
    const scope = document.querySelector("dialog[open]") || document;
    const rgb = (value) => value.match(/[\d.]+/g).map(Number);
    const luminance = (values) =>
      values
        .slice(0, 3)
        .map((value) => {
          const channel = value / 255;
          return channel <= 0.04045
            ? channel / 12.92
            : ((channel + 0.055) / 1.055) ** 2.4;
        })
        .reduce(
          (sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index],
          0,
        );
    const textStyles = [
      ...scope.querySelectorAll(
        "h1,h2,h3,p,label,button,input,select,legend,a",
      ),
    ]
      .filter(
        (node) =>
          visible(node) && !node.disabled && !node.closest(".visually-hidden"),
      )
      .map((node) => {
        let parent = node,
          background;
        while (parent) {
          const color = rgb(getComputedStyle(parent).backgroundColor);
          if (color.length === 3 || color[3] === 1) {
            background = color;
            break;
          }
          parent = parent.parentElement;
        }
        const style = getComputedStyle(node);
        const light = [luminance(rgb(style.color)), luminance(background)].sort(
          (a, b) => b - a,
        );
        return {
          contrast: (light[0] + 0.05) / (light[1] + 0.05),
          font: parseFloat(style.fontSize),
        };
      });
    const focus = document.activeElement;
    return {
      width: innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      panels,
      gaps,
      cards,
      dialogs,
      clipped,
      minContrast: Math.min(...textStyles.map((item) => item.contrast)),
      minFont: Math.min(...textStyles.map((item) => item.font)),
      focus: focus
        ? {
            id: focus.id,
            outline: getComputedStyle(focus).outlineStyle,
            ...rect(focus),
          }
        : null,
    };
  });
}

/** Chrome 실제 확대의 DIP 전체 폭으로 캡처하며 CSS 크기로 잘라 확대를 흉내내지 않는다. */
async function screenshot(page, path) {
  const session = await page.createCDPSession();
  try {
    const { contentSize } = await session.send("Page.getLayoutMetrics");
    const shot = await session.send("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: true,
      clip: { ...contentSize, scale: 1 },
    });
    await writeFile(path, Buffer.from(shot.data, "base64"));
  } finally {
    await session.detach();
  }
}

/** 명시적 로컬 baseline CSS가 있으면 같은 열린 상태에서 전후 스타일만 대조하고 즉시 원복한다. */
async function baseline(page, directory, capture) {
  const client = await page.createCDPSession();
  const sheets = new Map(),
    original = new Map();
  const fileName = (sheet) =>
    new URL(sheet.sourceURL || "https://invalid.local").pathname.match(
      /^\/admin\/(ui|auth|dashboard|stories)\.css$/,
    )?.[1];
  client.on("CSS.styleSheetAdded", ({ header }) =>
    sheets.set(header.styleSheetId, header),
  );
  client.on("CSS.styleSheetRemoved", ({ styleSheetId }) =>
    sheets.delete(styleSheetId),
  );
  const settle = () =>
    page.evaluate(
      () =>
        new Promise((done) =>
          requestAnimationFrame(() => requestAnimationFrame(done)),
        ),
    );
  async function replace(file, text) {
    for (const sheet of [...sheets.values()].filter(
      (sheet) => fileName(sheet) === file,
    ))
      await client.send("CSS.setStyleSheetText", {
        styleSheetId: sheet.styleSheetId,
        text,
      });
    await settle();
  }
  try {
    await client.send("DOM.enable");
    await client.send("CSS.enable");
    for (const sheet of sheets.values()) {
      const file = fileName(sheet);
      if (!file) continue;
      original.set(
        file,
        (
          await client.send("CSS.getStyleSheetText", {
            styleSheetId: sheet.styleSheetId,
          })
        ).text,
      );
    }
    assert.ok(original.size >= 2, "실제 공통/화면 stylesheet의 전후 대조");
    // 상위 import 교체는 공통 stylesheet ID를 다시 만든다. 부모 먼저, 실제 새 UI ID를 마지막에 처리한다.
    for (const file of ["auth", "dashboard", "stories", "ui"].filter((file) =>
      original.has(file),
    ))
      await replace(
        file,
        await readFile(resolve(directory, file + ".css"), "utf8"),
      );
    await capture();
  } finally {
    // import 갱신의 캐시 공유가 다른 새 시트에 영향을 줄 수 있으므로 동일 후보 자산의 정식 byte로 복구한다.
    for (const file of ["auth", "dashboard", "stories", "ui"].filter((file) =>
      original.has(file),
    ))
      await replace(
        file,
        await readFile(
          resolve("src/main/resources/static/admin", file + ".css"),
          "utf8",
        ),
      );
    await client.detach();
    await settle();
  }
}

/** 지정 화면 표본만 검사하고 원본 before는 비교 근거로 저장하되 PASS로 판단하지 않는다. */
export async function inspectAdminDesign(page, name, viewport) {
  if (
    ![
      "workspace-security",
      "design-security-long",
      "saved-draft-precheck",
      "conflict",
      "accounts-list",
      "accounts-active-detail",
      "accounts-inactive-preview",
      "design-account-reauth",
      "design-story-reauth",
      "design-confirm",
    ].includes(name)
  )
    return;
  const directory = resolve(
    process.env.ADMIN_DESIGN_EVIDENCE || "build/browser-evidence/admin-design",
  );
  await mkdir(directory, { recursive: true });
  const key = name + "-" + viewport.replace(/[^a-zA-Z0-9-]/g, "-");
  if (captured.has(key)) return;
  captured.add(key);
  if (process.env.ADMIN_DESIGN_BASELINE_DIR)
    await baseline(page, process.env.ADMIN_DESIGN_BASELINE_DIR, async () => {
      observations.push({
        name,
        viewport,
        phase: "before-css",
        ...(await measure(page)),
      });
      await screenshot(page, resolve(directory, key + "-before.png"));
    });
  const state = await measure(page);
  assert.ok(state.scrollWidth <= state.width, name + " 가로 넘침");
  assert.equal(state.clipped, 0, name + " 텍스트 잘림");
  assert.ok(state.minContrast >= 4.5, name + " 측정 문자 대비");
  assert.ok(state.minFont >= 14, name + " 표시 문자 크기");
  for (const panel of state.panels)
    for (const copy of panel.copy) {
      assert.ok(copy.width <= 720 + 1, panel.id + " 읽기 폭");
      assert.ok(
        Math.abs(copy.left - panel.copy[0].left) <= 1,
        panel.id + " 내부 정렬",
      );
    }
  if (name === "saved-draft-precheck") {
    assert.equal(state.gaps.descriptionToActions, 16);
    assert.equal(state.gaps.actionsToStatus, 8);
    assert.equal(state.gaps.horizontal, 16);
  }
  for (const card of state.cards) {
    assert.ok(card.button.height >= 48);
    for (const peer of state.cards)
      if (Math.abs(peer.card.top - card.card.top) <= 1)
        assert.ok(
          Math.abs(peer.button.bottom - card.button.bottom) <= 1,
          "같은 행의 행동 하단 정렬",
        );
  }
  for (const dialog of state.dialogs) {
    assert.ok(
      dialog.left >= -1 && dialog.left + dialog.width <= state.width + 1,
      "실제 열린 모달 가로 범위",
    );
    const minimum = dialog.id === "ui-confirm-dialog" ? 40 : 48;
    assert.ok(dialog.buttonHeights.every((height) => height >= minimum));
    assert.equal(dialog.groupGap, dialog.id === "ui-confirm-dialog" ? 12 : 16);
  }
  observations.push({ name, viewport, phase: "after", ...state });
  await screenshot(page, resolve(directory, key + "-after.png"));
  await writeFile(
    resolve(directory, "measurements.json"),
    JSON.stringify(
      {
        limitations: [
          "합성 폐기 PG 표본",
          "모든 권한/상태·reader/실물 모바일 접근성 인증 아님",
          "before-css는 같은 DOM의 승인 전 CSS이며 열린 모달의 과거 markup 간격까지 복원하지 않음",
        ],
        observations,
      },
      null,
      2,
    ),
  );
}

/** 기존 실제 Chrome 탭 확대 API로 열린 모달을 검증하며 입력/계정 변경은 하지 않는다. */
export async function inspectOpenDialog(page, name, zoomWorker) {
  for (const width of [360, 768, 1440]) {
    await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
    await inspectAdminDesign(page, name, width + "px");
  }
  const zoom = await zoomWorker.evaluate(async (url) => {
    const tab = (await chrome.tabs.query({})).find(
      (tab) => tab.url === url && tab.active,
    );
    await chrome.tabs.setZoom(tab.id, 2);
    return chrome.tabs.getZoom(tab.id);
  }, page.url());
  assert.equal(zoom, 2);
  await page.waitForFunction(() => innerWidth === 720);
  await inspectAdminDesign(page, name, "200%");
  await zoomWorker.evaluate(async (url) => {
    const tab = (await chrome.tabs.query({})).find(
      (tab) => tab.url === url && tab.active,
    );
    await chrome.tabs.setZoom(tab.id, 1);
  }, page.url());
  await page.waitForFunction(() => innerWidth === 1440);
}
