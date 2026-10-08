import assert from "node:assert/strict";

/** 실제 등록 관리자 HTTPS 세션으로 화면만 탐색한다. 미연동 API·회원 수집·게임 실행은 하지 않는다. */
export async function exerciseAdminWorkspace(page, fixture, helpers) {
  const { notice, text, layout, inspectLayout } = helpers;
  const apiRequests = [];
  const observe = (request) => {
    const path = new URL(request.url()).pathname;
    if (path.includes("/api/")) apiRequests.push([request.method(), path]);
  };
  page.on("request", observe);
  try {
    await notice(page, "현재 세션을 확인했습니다");
    assert.equal(await text(page, "#page-title"), "대시보드");
    assert.equal(
      await page.$eval(
        ".workspace-actions .primary-link",
        (link) => getComputedStyle(link).backgroundColor,
      ),
      "rgb(201, 26, 26)",
    );
    assert.deepEqual(
      await page.$$eval(".admin-nav-list a", (links) =>
        links.map((a) => a.textContent.trim()),
      ),
      [
        "대시보드",
        "게임 관리",
        "회원 관리",
        "통계 관리",
        "보안 관리",
        "유료 결제 관리",
      ],
    );
    assert.equal(
      await page.$eval(".admin-nav-list [aria-current]", (a) =>
        a.getAttribute("href"),
      ),
      "/admin",
    );
    await layout(page, "workspace-dashboard");
    for (const width of [900, 1200]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      await inspectLayout(page, "workspace-dashboard", `${width}px`);
    }
    await page.setViewport({ width: 360, height: 900, deviceScaleFactor: 1 });
    await page.waitForFunction(
      () => document.getElementById("admin-menu").hidden,
    );
    await page.click("#admin-nav-toggle");
    await page.waitForFunction(
      () =>
        document
          .getElementById("admin-nav-toggle")
          .getAttribute("aria-expanded") === "true",
    );
    await inspectLayout(page, "workspace-mobile-menu", "360px");
    await page.focus('.admin-nav-list a[href="/admin?tab=members"]');
    await page.keyboard.press("Escape");
    assert.equal(
      await page.$eval("#admin-nav-toggle", (button) =>
        button.getAttribute("aria-expanded"),
      ),
      "false",
    );
    assert.equal(
      await page.evaluate(() => document.activeElement.id),
      "admin-nav-toggle",
    );

    for (const [tab, heading] of [
      ["members", "회원 관리"],
      ["statistics", "통계 관리"],
      ["payments", "유료 결제 관리"],
      ["security", "보안 관리"],
    ]) {
      await page.goto(`${fixture.url}/admin?tab=${tab}`);
      await notice(page, "현재 세션을 확인했습니다");
      assert.equal(await text(page, "#page-title"), heading);
      assert.equal(
        await page.$eval(".admin-nav-list [aria-current]", (a) =>
          a.getAttribute("href"),
        ),
        `/admin?tab=${tab}`,
      );
      if (tab !== "security") {
        assert.match(await text(page, "#content"), /미연동|연결되지|연결 전/);
        assert.equal(
          await page.$$("#content form").then((nodes) => nodes.length),
          0,
        );
      } else {
        assert.deepEqual(
          await page.$$eval("#content form", (forms) =>
            forms.map((form) => form.dataset.action),
          ),
          ["logout", "logout-all", "codes-replace"],
        );
      }
      await layout(page, `workspace-${tab}`);
    }
    const invalid = await page.goto(
      `${fixture.url}/admin?tab=SYNTHETIC_QUERY_SECRET`,
    );
    assert.equal(invalid.status(), 400);
    assert.doesNotMatch(await page.content(), /SYNTHETIC_QUERY_SECRET/);

    await page.goto(`${fixture.url}/admin/preview/player-home`);
    assert.equal(await page.title(), "시차 · 플레이어 메인 디자인 시안");
    assert.match(await text(page, ".preview-banner"), /디자인 시안/);
    assert.equal(
      await page.$eval(".case-empty button", (button) => button.disabled),
      true,
    );
    await layout(page, "player-home-preview");
    assert.equal(
      await page.$eval(
        ".player-actions .primary-link",
        (link) => getComputedStyle(link).backgroundColor,
      ),
      "rgb(255, 199, 0)",
    );
    assert.equal(
      await page.$eval(
        ".case-cover",
        (cover) => getComputedStyle(cover).backgroundColor,
      ),
      "rgb(230, 223, 208)",
    );
    assert.equal(
      await page.$eval(
        "#player-heading",
        (heading) => heading.querySelectorAll("br").length,
      ),
      0,
    );
    assert.equal(
      await page.$eval(
        "#cover-heading",
        (heading) => getComputedStyle(heading).fontWeight,
      ),
      "600",
    );
    assert.equal(
      await page
        .$$(".role-identity svg[aria-hidden='true'][focusable='false']")
        .then((nodes) => nodes.length),
      2,
    );
    for (const width of [360, 720, 768, 1000, 1200, 1440]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      const theme = await page.evaluate(() => ({
        columns: getComputedStyle(
          document.querySelector(".player-hero"),
        ).gridTemplateColumns.split(" ").length,
        wrapper: document.querySelector(".player-shell").getBoundingClientRect()
          .width,
        title: parseFloat(
          getComputedStyle(document.getElementById("cover-heading")).fontSize,
        ),
        heading: parseFloat(
          getComputedStyle(document.getElementById("player-heading")).fontSize,
        ),
        radius: getComputedStyle(
          document.querySelector(".player-actions .primary-link"),
        ).borderRadius,
      }));
      assert.equal(theme.columns, width <= 1000 ? 1 : 2);
      assert.ok(theme.wrapper <= 1280);
      assert.equal(theme.title, width <= 720 ? 32 : 36);
      assert.equal(theme.heading, width < 768 ? 24 : 28);
      assert.equal(theme.radius, "6px");
    }
    for (const width of [900, 1200]) {
      await page.setViewport({ width, height: 900, deviceScaleFactor: 1 });
      await inspectLayout(page, "player-home-preview", `${width}px`);
    }
    await page.click('a[href="#guide"]');
    assert.equal(new URL(page.url()).hash, "#guide");
    await page.waitForFunction(
      () =>
        document.querySelector(".player-header nav [aria-current]")?.hash ===
        "#guide",
      { timeout: 5000 },
    );
    assert.equal(
      await page.$eval(
        ".player-header nav [aria-current]",
        (link) => link.hash,
      ),
      "#guide",
    );
    assert.equal(
      await page.$$(".guide-grid article").then((nodes) => nodes.length),
      3,
    );
    assert.ok(
      apiRequests.every(
        ([method, path]) =>
          (method === "GET" &&
            ["/admin/api/auth/csrf", "/admin/api/auth/me"].includes(path)) ||
          (method === "POST" && path === "/admin/api/history/navigation"),
      ),
      "관리 개요와 플레이어 시안에서 미연동 API·사업 변경을 호출하지 않음",
    );
    console.log(
      "PASS HTTPS categorized workspace, honest disconnected states, responsive sidebar, five widths/200%, protected player HTML preview; no member collection/game API",
    );
  } finally {
    page.off("request", observe);
  }
}
