(() => {
  "use strict";
  let captured = null;
  let failed = false;
  /** 초대 비밀은 한 번만 문서 메모리에 전달하고 URL/저장소에는 다시 넣지 않는다. */
  Object.defineProperty(window, "takeCoopInvitation", {
    value() {
      if (failed) throw new Error("초대 주소를 안전하게 정리할 수 없습니다.");
      const value = captured;
      captured = null;
      return value;
    },
  });
  const location = window.location;
  const fragment = location.hash;
  const query = location.search;
  if (
    location.pathname === "/coop-invite" ||
    fragment.startsWith("#inviteKey=")
  ) {
    try {
      window.history.replaceState(null, "", location.pathname);
    } catch (_) {
      failed = true;
      return;
    }
    const key =
      "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    const match = new RegExp(
      "^#inviteKey=(" +
        key +
        ")&inviteSecret=([A-Za-z0-9_-]{42}[AEIMQUYcgkosw048])$",
    ).exec(fragment);
    captured =
      location.pathname === "/coop-invite" && query === "" && match
        ? Object.freeze({ inviteKey: match[1], inviteSecret: match[2] })
        : Object.freeze({ invalid: true });
  }
  window.addEventListener("pagehide", () => {
    captured = null;
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") captured = null;
  });
})();
