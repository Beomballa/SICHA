import { WebApiError } from "./web-api.mjs";
import { WebSessionBlocked } from "./web-session.mjs";
import { parseReceipt } from "./game-model.mjs";

/**
 * 한 문서·인증 세대의 단일 원본 명령을 소유한다. 조회나 수정번호 변화로 성공을 추정하지 않는다.
 * @param {object} session 기존 회원 인증 조정자.
 * @param {function} purge 권한/문서 경계에서 원문 UI를 동기 폐기하는 함수.
 * @param {function} uuid 실제 브라우저 UUID 생성 함수. 테스트에서만 합성 경계를 주입한다.
 * @param {function} mode 서버 확인된 방 또는 명시 생성 선택의 SOLO/COOP 모드를 반환한다. 명령 생성 때 고정한다.
 */
export function createGameClient(
  session,
  purge,
  uuid = () => crypto.randomUUID(),
  mode = () => "SOLO",
) {
  let generation = 0;
  let intent = null;
  let disposed = false;

  /** 화면/계정 교체는 메모리 명령과 늦은 응답의 소유권을 함께 폐기한다. */
  function clear(reason = "SCREEN_CHANGED") {
    generation += 1;
    intent = null;
    purge(reason);
  }
  const unsubscribe = session.onClear(clear);

  /** 거절된 소유 방은 이전 자료로 표시하지 않으며 조회 오류는 정상 빈 결과가 아니다. */
  async function query(method, path, body, parser) {
    const fence = generation;
    try {
      const value = await session.authorizedRequest(method, path, body);
      if (disposed || fence !== generation || !session.current())
        throw new WebSessionBlocked();
      return parser(value);
    } catch (failure) {
      if (
        failure instanceof WebApiError &&
        [401, 403, 404, 410].includes(failure.status)
      )
        clear();
      else if (failure instanceof WebApiError && failure.status === 503)
        purge();
      throw failure;
    }
  }

  /** 유실 시 키·본문·인증 세대를 유지하고 명시 replay만 허용한다. */
  async function execute(command) {
    const fence = generation;
    if (
      disposed ||
      intent !== command ||
      command.generation !== generation ||
      !session.current()
    )
      throw new WebSessionBlocked();
    try {
      const value = await session.authorizedRequest(
        command.method,
        command.path,
        JSON.parse(command.bodyText),
      );
      if (
        disposed ||
        fence !== generation ||
        intent !== command ||
        !session.current()
      )
        throw new WebSessionBlocked();
      const receipt = parseReceipt(
        value,
        command.action,
        command.gameKey,
        command.mode,
      );
      intent = null;
      return receipt;
    } catch (failure) {
      if (fence === generation && intent === command) {
        if (
          failure instanceof WebApiError &&
          [401, 403, 404, 410].includes(failure.status)
        )
          clear();
        else if (
          !command.uncertain &&
          failure instanceof WebApiError &&
          [400, 409, 422].includes(failure.status)
        )
          intent = null;
        else command.uncertain = true;
      }
      throw failure;
    }
  }

  return Object.freeze({
    read(path, parser) {
      return query("GET", path, undefined, parser);
    },
    inspect(path, body, parser) {
      return query("POST", path, body, parser);
    },
    clear,
    /** 성공 여부가 불확실한 원본의 비밀 없는 상태만 반환한다. */
    pending() {
      return intent
        ? { action: intent.action, uncertain: intent.uncertain }
        : null;
    },
    /** 신규 명령은 이전 유실 명령이 없을 때만 새로운 키를 생성한다. */
    send(method, path, action, body, gameKey) {
      if (intent || disposed || !session.current())
        throw new WebSessionBlocked();
      const command = {
        method,
        path,
        action,
        gameKey,
        mode: mode(),
        generation,
        uncertain: false,
        bodyText: JSON.stringify({ ...body, requestKey: uuid() }),
      };
      intent = command;
      return execute(command);
    },
    /** 원본 명령의 본문·키만 명시적으로 재전송한다. */
    replay() {
      if (!intent?.uncertain) throw new WebSessionBlocked();
      return execute(intent);
    },
    /** 접속 증명은 한 번만 전송하고 서버 상태나 revision을 바꾸는 동의로 사용하지 않는다. */
    async heartbeat(gameKey) {
      const fence = generation;
      await session.authorizedRequest(
        "POST",
        "/api/games/" + gameKey + "/heartbeat",
        {},
      );
      if (disposed || fence !== generation || !session.current())
        throw new WebSessionBlocked();
    },
    dispose() {
      disposed = true;
      unsubscribe();
      clear();
    },
  });
}
