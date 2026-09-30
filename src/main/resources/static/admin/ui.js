(() => {
  "use strict";
  let pending;

  /**
   * 단일 확인 창을 표시하며 중복 요청은 기존 창을 바꾸지 않고 거절한다.
   * @param {object} options title/message/confirmLabel은 공백뿐이 아닌 문자열이며 null은 허용하지 않는다.
   * @returns {Promise<boolean>} 명시적 확인 버튼만 true, 취소·닫힘·페이지 이탈·중복 요청은 false.
   * @throws {TypeError} 필수 문구가 문자열이 아니거나 비어 있으면 발생한다.
   * @throws {Error} 브라우저가 모달 대화상자를 표시하지 못하면 발생한다. 네이티브 확인으로 대체하지 않는다.
   */
  function confirm(options) {
    for (const key of ["title", "message", "confirmLabel"]) {
      if (typeof options?.[key] !== "string" || !options[key].trim())
        throw new TypeError(
          `확인 창 ${key}는 비어 있지 않은 문자열이어야 합니다.`,
        );
    }
    if (pending) return Promise.resolve(false);
    let trigger = document.activeElement;
    let dialog = document.createElement("dialog");
    dialog.id = "ui-confirm-dialog";
    dialog.className = "ui-confirm";
    dialog.setAttribute("role", "alertdialog");
    dialog.setAttribute("aria-labelledby", "ui-confirm-title");
    dialog.setAttribute("aria-describedby", "ui-confirm-message");
    let title = document.createElement("h2");
    title.id = "ui-confirm-title";
    title.textContent = options.title;
    let message = document.createElement("p");
    message.id = "ui-confirm-message";
    message.textContent = options.message;
    let actions = document.createElement("div");
    actions.className = "ui-confirm-actions";
    let cancel = document.createElement("button");
    cancel.id = "ui-confirm-cancel";
    cancel.type = "button";
    cancel.className = "secondary";
    cancel.textContent = "취소";
    cancel.autofocus = true;
    let accept = document.createElement("button");
    accept.id = "ui-confirm-accept";
    accept.type = "button";
    accept.className = "primary";
    accept.textContent = options.confirmLabel;
    options = undefined;
    actions.append(cancel, accept);
    dialog.append(title, message, actions);
    let resolve;
    const result = new Promise((done) => {
      resolve = done;
    });
    let settled = false;

    /**
     * 현재 창만 한 번 종료하고 문구·리스너·DOM 참조를 폐기한다.
     * @param {boolean} value 명시적 확인이면 true, 그 외 모든 종료는 false.
     * @returns {void} 이미 종료한 창은 다시 처리하지 않는다.
     */
    function settle(value) {
      if (settled) return;
      settled = true;
      pending = undefined;
      cancel.removeEventListener("click", reject);
      accept.removeEventListener("click", approve);
      dialog.removeEventListener("cancel", escape);
      dialog.removeEventListener("close", reject);
      dialog.removeEventListener("keydown", confineFocus);
      title.textContent = message.textContent = accept.textContent = "";
      if (dialog.open) dialog.close();
      dialog.replaceChildren();
      dialog.remove();
      if (
        trigger?.isConnected &&
        !trigger.matches(":disabled") &&
        !trigger.closest("[hidden],[inert]") &&
        trigger.getClientRects().length &&
        getComputedStyle(trigger).visibility === "visible"
      )
        trigger.focus({ preventScroll: true });
      const done = resolve;
      trigger =
        dialog =
        title =
        message =
        actions =
        cancel =
        accept =
        resolve =
          undefined;
      done(value);
    }

    /** 명시적 확인 이외의 종료는 변경을 허용하지 않는다. */
    function reject() {
      settle(false);
    }

    /** 명시적으로 누른 확인 버튼만 변경을 허용한다. */
    function approve() {
      settle(true);
    }

    /**
     * Escape의 기본 닫힘 대신 동일한 정리 경로를 실행한다.
     * @param {Event} event 현재 창의 cancel 이벤트. 기본 동작을 막는다.
     * @returns {void} 동의 없이 창을 종료한다.
     */
    function escape(event) {
      event.preventDefault();
      settle(false);
    }

    /**
     * 두 행동 사이에서 Tab·Shift+Tab 초점을 순환시킨다.
     * @param {KeyboardEvent} event 현재 창의 키 입력. Tab 외 입력은 건드리지 않는다.
     * @returns {void} 두 버튼 사이의 초점만 이동한다.
     */
    function confineFocus(event) {
      if (event.key !== "Tab") return;
      event.preventDefault();
      (document.activeElement === cancel ? accept : cancel).focus();
    }
    cancel.addEventListener("click", reject);
    accept.addEventListener("click", approve);
    dialog.addEventListener("cancel", escape);
    dialog.addEventListener("close", reject);
    dialog.addEventListener("keydown", confineFocus);
    pending = reject;
    document.body.append(dialog);
    try {
      dialog.showModal();
      cancel.focus();
    } catch (error) {
      settle(false);
      throw error;
    }
    return result;
  }

  /** 열려 있는 확인만 거절한다. 반환값은 없으며 닫힌 상태에서는 아무것도 바꾸지 않는다. */
  function cancelConfirmation() {
    pending?.();
  }
  window.AdminUI = Object.freeze({ confirm, cancelConfirmation });
  window.addEventListener("pagehide", cancelConfirmation);
})();
