import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:web/web.dart' as web;

import '../core/player_api.dart';
import 'auth_session.dart';

AuthSession createAuthSession({AuthTokenStorage? storage}) {
  if (storage != null) throw ArgumentError('브라우저는 자격 저장소를 사용하지 않습니다.');
  return CookieAuthSession(_BrowserCoordinator());
}

class _BrowserCoordinator implements WebAuthCoordinator {
  static const _key = 'sicha.player.auth.v1';
  static const _lock = 'sicha-player-auth-v1';
  final _changes = StreamController<void>.broadcast(sync: true);
  late final JSFunction _listener;

  _BrowserCoordinator() {
    _listener = ((web.Event event) {
      final storageEvent = event as web.StorageEvent;
      if (storageEvent.key == _key || storageEvent.key == null) {
        _changes.add(null);
      }
    }).toJS;
    web.window.addEventListener('storage', _listener);
  }

  @override
  Stream<void> get changes => _changes.stream;

  /// 고정된 HTTPS 원점, Web Locks, 저장소와 비제어 서비스 워커 조건을 확인한다.
  @override
  void validate(PlayerApi api) {
    final location = web.window.location;
    final endpoint = Uri.parse(api.endpoint);
    if (!web.window.isSecureContext ||
        location.protocol != 'https:' ||
        endpoint.origin != location.origin ||
        web.window.navigator
            .getProperty<JSAny?>('locks'.toJS)
            .isUndefinedOrNull ||
        (!web.window.navigator
                .getProperty<JSAny?>('serviceWorker'.toJS)
                .isUndefinedOrNull &&
            web.window.navigator.serviceWorker.controller != null)) {
      throw PlayerConfigurationError();
    }
    // 기존 표지를 덮어쓰거나 삭제하지 않고 실제 저장소 접근 가능성을 확인한다.
    final probe = '$_key.probe.${requestKey()}';
    try {
      web.window.localStorage.setItem(probe, '1');
      if (web.window.localStorage.getItem(probe) != '1') {
        throw AuthSessionBlocked();
      }
      web.window.localStorage.removeItem(probe);
      read();
    } catch (_) {
      throw AuthSessionBlocked();
    }
  }

  @override
  Future<T> exclusive<T>(Future<T> Function() action) async {
    late T result;
    Object? failure;
    StackTrace? failureStack;
    await web.window.navigator.locks
        .request(
          _lock,
          ((web.Lock? lock) {
            return (() async {
              // Dart 예외를 JS Promise 거절 값으로 바꾸면 호출자의 타입 판별이 사라진다.
              // 잠금 내 명령은 끝까지 기다리고 원래 오류를 Dart 경계에서 다시 던진다.
              try {
                if (lock == null) throw AuthSessionBlocked();
                result = await action();
              } catch (error, stack) {
                failure = error;
                failureStack = stack;
              }
              return null as JSAny?;
            })().toJS;
          }).toJS,
        )
        .toDart;
    if (failure != null) {
      Error.throwWithStackTrace(failure!, failureStack!);
    }
    return result;
  }

  @override
  String? read() => web.window.localStorage.getItem(_key);

  @override
  void write(String marker) => web.window.localStorage.setItem(_key, marker);

  @override
  void dispose() {
    web.window.removeEventListener('storage', _listener);
    _changes.close();
  }
}
