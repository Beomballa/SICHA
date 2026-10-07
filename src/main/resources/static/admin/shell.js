(() => {
  "use strict";
  const toggle = document.getElementById("admin-nav-toggle");
  const menu = document.getElementById("admin-menu");
  if (!toggle || !menu) return;
  const desktop = matchMedia("(min-width: 1200px)");

  /** 같은 탐색 DOM을 사용하며 모바일 메뉴를 접어도 원고·요청·선택 상태는 바꾸지 않는다. */
  function expanded(value) {
    if (!value && menu.contains(document.activeElement)) toggle.focus();
    menu.hidden = !value;
    toggle.setAttribute("aria-expanded", String(value));
    toggle.textContent = value ? "메뉴 닫기" : "메뉴 열기";
  }

  function layout() {
    toggle.hidden = desktop.matches;
    expanded(desktop.matches);
  }
  toggle.addEventListener("click", () => expanded(menu.hidden));
  menu.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !desktop.matches) {
      event.preventDefault();
      expanded(false);
      toggle.focus();
    }
  });
  desktop.addEventListener("change", layout);
  layout();
})();
