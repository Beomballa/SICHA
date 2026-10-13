import assert from "node:assert/strict";
import {
  inspectAdminDesign,
  inspectOpenDialog,
} from "./admin-design-check.mjs";

/** 폐기 서버의 만료된 최근 인증 세션에서 실제 재인증을 완료한다. 사업 변경은 실행하지 않는다. */
export async function exerciseDesignReauth(browser, fixture, helpers) {
  const { otp, notice, zoomWorker } = helpers;
  // 기본 시험 문맥만 실제 탭 확대 확장에서 보인다. 합성 세션만 잠시 교체하고 원래 쿠키를 복구한다.
  const context = browser.defaultBrowserContext();
  const savedCookies = await context.cookies();
  let page;
  try {
    await context.setCookie({
      name: "__Host-admin-session",
      value: fixture.designSessionId,
      url: fixture.url,
      secure: true,
      httpOnly: true,
      sameSite: "Strict",
      path: "/",
    });
    page = await context.newPage();
    await page.bringToFront();
    const writes = [],
      errors = [];
    page.on("pageerror", (error) => errors.push(error.name));
    page.on("request", (request) => {
      if (
        request.method() === "POST" &&
        /\/admin\/api\/(accounts|stories)/.test(new URL(request.url()).pathname)
      )
        writes.push(new URL(request.url()).pathname);
    });
    await page.goto(fixture.url + "/admin/accounts");
    await page.waitForSelector("[data-reauth]");
    await page.click("[data-reauth]");
    await page.waitForSelector("#reauth-dialog[open]");
    await inspectOpenDialog(page, "design-account-reauth", zoomWorker);
    await page.keyboard.press("Tab");
    assert.equal(
      await page.evaluate(
        () => getComputedStyle(document.activeElement).outlineStyle,
      ),
      "solid",
    );
    await page.click("#reauth-close");
    assert.equal(
      await page.evaluate(() =>
        document.activeElement.matches("[data-reauth]"),
      ),
      true,
      "취소 뒤 실제 실행 버튼으로 포커스 복귀",
    );
    await page.click("[data-reauth]");
    // 캡처는 비밀 입력 전에 끝냈다. 로그인/MFA 자격은 파일이나 측정값으로 출력하지 않는다.
    await page.type("#reauth-password", fixture.password);
    await page.type("#reauth-totp", otp(fixture.secret));
    const authenticated = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/admin/api/auth/reauth" &&
        response.request().method() === "POST",
    );
    await page.click("#reauth-form [type=submit]");
    await page.waitForFunction(
      () => !document.querySelector("#reauth-dialog[open]"),
    );
    assert.equal((await authenticated).status(), 200, "실제 최근 재인증 성공");
    await notice(page, "현재 페이지를 조회했습니다");
    await page.goto(fixture.url + "/admin/accounts");
    await notice(page, "현재 페이지를 조회했습니다");
    for (const width of [360, 768, 1440]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      await inspectAdminDesign(page, "accounts-list", width + "px");
    }
    await page.goto(fixture.url + "/admin/stories");
    await notice(page, "현재 페이지");
    await page.$eval("#story-reauth", (dialog) => dialog.showModal());
    await inspectOpenDialog(page, "design-story-reauth", zoomWorker);
    await page.focus("#story-reauth-close");
    await page.keyboard.down("Shift");
    await page.keyboard.press("Tab");
    await page.keyboard.up("Shift");
    assert.equal(
      await page.evaluate(() =>
        document.activeElement.matches("#story-reauth-form [type=submit]"),
      ),
      true,
    );
    assert.equal(
      await page.evaluate(
        () => getComputedStyle(document.activeElement).outlineStyle,
      ),
      "solid",
    );
    await page.click("#story-reauth-close");
    assert.equal(
      await page.$eval("#story-reauth-password", (input) => input.value),
      "",
    );
    assert.equal(
      await page.$eval("#story-reauth-totp", (input) => input.value),
      "",
    );
    assert.deepEqual(
      writes,
      [],
      "디자인 표본·최근 인증은 계정/사건 변경 POST를 생성하지 않는다",
    );
    assert.deepEqual(errors, []);
    console.log(
      "PASS actual expired recent-auth fixture->opened account reauth->real password/MFA reauth->accounts; actual story reauth DOM opened visually only; no business writes",
    );
  } finally {
    await page?.close();
    await context.setCookie(...savedCookies);
  }
}
