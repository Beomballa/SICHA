import { inspectOpenDialog } from "./admin-design-check.mjs";

/** 기존 행동을 누르지 않고 합성 긴 문구에서 카드/버튼의 자연 성장만 검증하고 원복한다. */
export async function exerciseSecurityLongCopy(page, zoomWorker) {
  const original = await page.$$eval(
    ".security-grid p,.security-grid button",
    (nodes) => nodes.map((node) => node.textContent),
  );
  try {
    await page.$$eval(".security-grid p", (nodes) =>
      nodes.forEach((node, index) => {
        node.textContent +=
          " 서로 다른 안내 길이와 확대 상태에서도 문장을 숨기거나 자르지 않고 읽을 수 있어야 합니다.".repeat(
            index + 1,
          );
      }),
    );
    await page.$eval(".security-grid button", (node) => {
      node.textContent =
        "현재 기기의 관리자 세션만 종료하는 긴 설명을 가진 합성 행동 버튼 · 다른 기기에는 영향 없음";
    });
    await inspectOpenDialog(page, "design-security-long", zoomWorker);
  } finally {
    await page.$$eval(
      ".security-grid p,.security-grid button",
      (nodes, values) =>
        nodes.forEach((node, index) => {
          node.textContent = values[index];
        }),
      original,
    );
  }
}
