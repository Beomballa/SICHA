import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sicha_player/auth/auth_controller.dart';
import 'package:sicha_player/auth/auth_session.dart';
import 'package:sicha_player/auth/auth_session_native.dart';
import 'package:sicha_player/core/player_api.dart';

const epoch = '11111111-1111-4111-8111-111111111111';
const otherEpoch = '22222222-2222-4222-8222-222222222222';

Map<String, dynamic> accessDto(String webEpoch) => {
  'tokenType': 'Bearer',
  'accessToken': List.filled(43, 'a').join(),
  'accessExpiresAt': '2030-01-01T00:00:00Z',
  'sessionKey': epoch,
  'sessionAbsoluteExpiresAt': '2031-01-01T00:00:00Z',
  'webEpoch': webEpoch,
  'requestId': epoch,
};

class TestCoordinator implements WebAuthCoordinator {
  String? marker;
  final events = StreamController<void>.broadcast(sync: true);
  Future<void> tail = Future.value();
  bool writable = true;

  @override
  void validate(PlayerApi api) {}

  @override
  Future<T> exclusive<T>(Future<T> Function() action) {
    final result = tail.then((_) => action());
    tail = result.then<void>((_) {}, onError: (Object _) {});
    return result;
  }

  @override
  String? read() => marker;

  @override
  void write(String value) {
    if (!writable) throw StateError('storage unavailable');
    marker = value;
  }

  @override
  Stream<void> get changes => events.stream;

  @override
  void dispose() {
    events.close();
  }
}

class TestBrowserApi extends PlayerApi {
  TestBrowserApi(this.reply) : super('https://example.invalid');
  final Future<Map<String, dynamic>?> Function(
    String,
    Map<String, dynamic>,
    String,
  )
  reply;
  int sends = 0;

  @override
  Future<Map<String, dynamic>?> browserPost(
    String path,
    Map<String, dynamic> body, {
    required String epoch,
  }) {
    sends++;
    return reply(path, body, epoch);
  }
}

class MemoryStorage implements AuthTokenStorage {
  final values = <String, String>{};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String value) async {
    values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    values.remove(key);
  }
}

class InjectedSession implements AuthSession {
  AccessCredentials? credentials = AccessCredentials(
    'access',
    DateTime.utc(2030),
  );
  Completer<AccessCredentials?>? refreshing;
  final events = StreamController<void>.broadcast(sync: true);
  bool active = true;
  int logouts = 0;

  @override
  bool get current => active;
  @override
  Stream<void> get invalidations => events.stream;
  @override
  Future<AccessCredentials?> restore(PlayerApi api) async => credentials;
  @override
  Future<AccessCredentials> login(
    PlayerApi api,
    String email,
    String password,
  ) async => credentials!;
  @override
  Future<AccessCredentials?> refresh(PlayerApi api) async =>
      refreshing == null ? credentials : await refreshing!.future;
  @override
  Future<void> logout(PlayerApi api) async {
    logouts++;
    active = false;
  }

  @override
  Future<void> clearLocal() async {
    active = false;
  }

  @override
  void dispose() {
    events.close();
  }
}

void main() {
  test('cookie restore accepts only explicit no-cookie null and stores no credentials', () async {
    final coordinator = TestCoordinator();
    final session = CookieAuthSession(coordinator);
    addTearDown(session.dispose);
    final api = TestBrowserApi((path, body, epoch) async {
      expect(path, '/api/member/browser-auth/refresh');
      expect(body, isEmpty);
      expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'refresh');
      return null;
    });
    expect(await session.restore(api), isNull);
    expect(api.sends, 1);
    expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'ready');
    expect(session.current, isFalse);
    expect(coordinator.marker, isNot(contains('Token')));
  });

  test(
    'seven-field access parser rejects secrets, extra fields and wrong epoch',
    () {
      expect(parseWebAccess(accessDto(epoch), epoch).token.length, 43);
      expect(
        () => parseWebAccess({
          ...accessDto(epoch),
          'refreshToken': 'secret',
        }, epoch),
        throwsFormatException,
      );
      expect(
        () => parseWebAccess(accessDto(otherEpoch), epoch),
        throwsFormatException,
      );
      expect(
        () => parseWebAccess({
          ...accessDto(epoch),
          'accessExpiresAt': '2020-01-01T00:00:00Z',
        }, epoch),
        throwsFormatException,
      );
      expect(
        () =>
            parseWebAccess({...accessDto(epoch), 'tokenType': 'bearer'}, epoch),
        throwsFormatException,
      );
    },
  );

  test(
    'unknown marker blocks restore without a send or age heuristic',
    () async {
      for (final marker in [
        'not-json',
        WebAuthMarker(epoch, 'refresh', otherEpoch).encode(),
        WebAuthMarker(epoch, 'blocked', otherEpoch).encode(),
      ]) {
        final coordinator = TestCoordinator()..marker = marker;
        final session = CookieAuthSession(coordinator);
        final api = TestBrowserApi((_, _, _) async => accessDto(epoch));
        await expectLater(
          session.restore(api),
          throwsA(isA<AuthSessionBlocked>()),
        );
        expect(api.sends, 0);
        expect(coordinator.marker, marker);
        session.dispose();
      }
    },
  );

  test(
    'ambiguous response is single-send and remains blocked on reload',
    () async {
      final coordinator = TestCoordinator()
        ..marker = WebAuthMarker(epoch, 'ready').encode();
      final session = CookieAuthSession(coordinator);
      addTearDown(session.dispose);
      final api = TestBrowserApi((_, _, _) async => {'tokenType': 'Bearer'});
      await expectLater(
        session.refresh(api),
        throwsA(isA<AuthSessionBlocked>()),
      );
      await expectLater(
        session.restore(api),
        throwsA(isA<AuthSessionBlocked>()),
      );
      expect(api.sends, 1);
      expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'blocked');
    },
  );

  test('failed durable write prevents all cookie traffic', () async {
    final coordinator = TestCoordinator()..writable = false;
    final session = CookieAuthSession(coordinator);
    addTearDown(session.dispose);
    final api = TestBrowserApi((_, _, epoch) async => accessDto(epoch));
    await expectLater(session.restore(api), throwsStateError);
    expect(api.sends, 0);
  });

  test(
    'epoch changes invalidate access even without storage event delivery',
    () async {
      final coordinator = TestCoordinator()
        ..marker = WebAuthMarker(epoch, 'ready').encode();
      final session = CookieAuthSession(coordinator);
      addTearDown(session.dispose);
      final api = TestBrowserApi((_, _, webEpoch) async => accessDto(webEpoch));
      await session.restore(api);
      expect(session.current, isTrue);
      coordinator.marker = WebAuthMarker(otherEpoch, 'ready').encode();
      expect(session.current, isFalse);
    },
  );

  test('own singleflight rotation retains current generation until validated response', () async {
    final coordinator = TestCoordinator()
      ..marker = WebAuthMarker(epoch, 'ready').encode();
    final session = CookieAuthSession(coordinator);
    addTearDown(session.dispose);
    final initial = TestBrowserApi(
      (_, _, webEpoch) async => accessDto(webEpoch),
    );
    await session.restore(initial);
    final started = Completer<void>();
    final reply = Completer<Map<String, dynamic>?>();
    final rotating = TestBrowserApi((_, _, _) async {
      started.complete();
      return reply.future;
    });
    final refresh = session.refresh(rotating);
    await started.future;
    expect(session.current, isTrue);
    reply.complete(accessDto(epoch));
    await refresh;
    expect(session.current, isTrue);
    expect(rotating.sends, 1);
  });

  test(
    'foreign same-epoch rotation retains access until uncertainty',
    () async {
      final coordinator = TestCoordinator()
        ..marker = WebAuthMarker(epoch, 'ready').encode();
      final first = CookieAuthSession(coordinator);
      final second = CookieAuthSession(coordinator);
      addTearDown(first.dispose);
      addTearDown(second.dispose);
      await first.restore(
        TestBrowserApi((_, _, value) async => accessDto(value)),
      );
      final invalidations = <void>[];
      final subscription = first.invalidations.listen(invalidations.add);
      addTearDown(subscription.cancel);
      final started = Completer<void>();
      final response = Completer<Map<String, dynamic>?>();
      final rotating = TestBrowserApi((_, _, _) {
        started.complete();
        return response.future;
      });
      final restoration = second.restore(rotating);
      await started.future;
      coordinator.events.add(null);
      expect(first.current, isTrue);
      expect(invalidations, isEmpty);
      response.complete(accessDto(epoch));
      await restoration;
      expect(first.current, isTrue);
      expect(second.current, isTrue);
      expect(rotating.sends, 1);
      coordinator.write(WebAuthMarker(epoch, 'blocked', otherEpoch).encode());
      coordinator.events.add(null);
      expect(first.current, isFalse);
      expect(second.current, isFalse);
      expect(invalidations, hasLength(1));
    },
  );

  test('late access response cannot overwrite a newer durable epoch', () async {
    final coordinator = TestCoordinator()
      ..marker = WebAuthMarker(epoch, 'ready').encode();
    final session = CookieAuthSession(coordinator);
    addTearDown(session.dispose);
    final started = Completer<void>();
    final reply = Completer<Map<String, dynamic>?>();
    final api = TestBrowserApi((_, _, _) async {
      started.complete();
      return reply.future;
    });
    final restore = session.restore(api);
    final rejected = expectLater(restore, throwsA(isA<AuthSessionBlocked>()));
    await started.future;
    final replacement = WebAuthMarker(otherEpoch, 'ready').encode();
    coordinator.marker = replacement;
    reply.complete(accessDto(epoch));
    await rejected;
    expect(coordinator.marker, replacement);
    expect(session.current, isFalse);
    expect(api.sends, 1);
  });

  test(
    'explicit login revokes first, uses a fresh epoch and holds one lock',
    () async {
      final coordinator = TestCoordinator()
        ..marker = WebAuthMarker(epoch, 'blocked', otherEpoch).encode();
      final session = CookieAuthSession(coordinator);
      addTearDown(session.dispose);
      final paths = <String>[];
      final api = TestBrowserApi((path, body, webEpoch) async {
        paths.add(path);
        if (path.endsWith('/logout')) {
          expect(webEpoch, epoch);
          return {'state': 'LOGGED_OUT', 'requestId': epoch};
        }
        expect(webEpoch, isNot(epoch));
        expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'login');
        return accessDto(webEpoch);
      });
      await session.login(api, 'synthetic@example.invalid', 'synthetic');
      expect(paths, [
        '/api/member/browser-auth/logout',
        '/api/member/browser-auth/login/local',
      ]);
      expect(session.current, isTrue);
      expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'ready');
      expect(coordinator.marker, isNot(contains('synthetic')));
      expect(coordinator.marker, isNot(contains('accessToken')));
    },
  );

  test('definitive rejected proof does not claim successful logout', () async {
    final coordinator = TestCoordinator()
      ..marker = WebAuthMarker(epoch, 'ready').encode();
    final session = CookieAuthSession(coordinator);
    addTearDown(session.dispose);
    final api = TestBrowserApi((path, _, _) async {
      final options = RequestOptions(path: path);
      throw DioException(
        requestOptions: options,
        response: Response(
          requestOptions: options,
          statusCode: 401,
          data: {'code': 'MEMBER_AUTH_REQUIRED'},
        ),
      );
    });
    await expectLater(session.logout(api), throwsA(isA<AuthSessionBlocked>()));
    expect(api.sends, 1);
    expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'logout');
  });

  test(
    'revocation 503 does not hide uncertainty or send fresh login',
    () async {
      final coordinator = TestCoordinator()
        ..marker = WebAuthMarker(epoch, 'refresh', otherEpoch).encode();
      final session = CookieAuthSession(coordinator);
      addTearDown(session.dispose);
      final api = TestBrowserApi((path, _, _) async {
        expect(path, '/api/member/browser-auth/logout');
        final options = RequestOptions(path: path);
        throw DioException(
          requestOptions: options,
          response: Response(
            requestOptions: options,
            statusCode: 503,
            data: {'code': 'SERVICE_UNAVAILABLE'},
          ),
        );
      });
      await expectLater(
        session.login(api, 'synthetic@example.invalid', 'synthetic'),
        throwsA(isA<AuthSessionBlocked>()),
      );
      expect(api.sends, 1);
      expect(WebAuthMarker.parse(coordinator.marker)!.operation, 'logout');
      await expectLater(
        session.restore(api),
        throwsA(isA<AuthSessionBlocked>()),
      );
      expect(api.sends, 1);
    },
  );

  test('remote epoch invalidates before use and late business response is rejected', () async {
    final session = InjectedSession();
    final api = PlayerApi('https://example.invalid');
    final started = Completer<void>();
    final reply = Completer<void>();
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          if (options.path != '/api/member/auth/me') {
            started.complete();
            await reply.future;
          }
          handler.resolve(
            Response(requestOptions: options, data: {'nickname': 'synthetic'}),
          );
        },
      ),
    );
    final container = ProviderContainer(
      overrides: [
        apiProvider.overrideWith((ref) => api),
        authProvider.overrideWith(() => AuthController(session: session)),
      ],
    );
    addTearDown(container.dispose);
    final auth = container.read(authProvider.notifier);
    await auth.restore();
    final generation = auth.generation;
    final request = auth.authorizedGet('/read');
    final rejected = expectLater(request, throwsStateError);
    await started.future;
    session.active = false;
    session.events.add(null);
    expect(auth.generation, generation + 1);
    expect(container.read(authProvider).phase, AuthPhase.loginRequired);
    reply.complete();
    await rejected;
    await expectLater(auth.authorizedPost('/write', {}), throwsStateError);
  });

  test('logout during restore cannot accept late access', () async {
    final coordinator = TestCoordinator()
      ..marker = WebAuthMarker(epoch, 'ready').encode();
    final session = CookieAuthSession(coordinator);
    final started = Completer<void>();
    final reply = Completer<Map<String, dynamic>?>();
    final api = TestBrowserApi((path, _, webEpoch) async {
      if (path.endsWith('/refresh')) {
        started.complete();
        return reply.future;
      }
      return {'state': 'LOGGED_OUT', 'requestId': epoch};
    });
    final container = ProviderContainer(
      overrides: [
        apiProvider.overrideWith((ref) => api),
        authProvider.overrideWith(() => AuthController(session: session)),
      ],
    );
    addTearDown(container.dispose);
    final auth = container.read(authProvider.notifier);
    final restore = auth.restore();
    await started.future;
    final logout = auth.logout();
    reply.complete(accessDto(epoch));
    await Future.wait([restore, logout]);
    expect(container.read(authProvider).phase, AuthPhase.signedOut);
    expect(session.current, isFalse);
    expect(api.sends, 2);
  });

  test(
    'native unknown marker erases proof and does not reuse rotation',
    () async {
      final storage = MemoryStorage();
      final api = PlayerApi('https://example.invalid');
      final key = 'player:${Uri.encodeComponent(api.endpoint)}:refresh';
      storage.values[key] = 'native-proof';
      storage.values['$key:rotating'] = 'unknown';
      final session = NativeAuthSession(storage);
      await expectLater(
        session.restore(api),
        throwsA(isA<AuthSessionBlocked>()),
      );
      expect(storage.values[key], isNull);
      expect(storage.values['$key:rotating'], 'unknown');
    },
  );

  test(
    'native logout waits for restore before erasing rotated credentials',
    () async {
      final storage = MemoryStorage();
      final api = PlayerApi('https://example.invalid');
      final key = 'player:${Uri.encodeComponent(api.endpoint)}:refresh';
      storage.values[key] = 'native-proof';
      final started = Completer<void>();
      final reply = Completer<void>();
      api.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) async {
            if (options.path.endsWith('/refresh')) {
              started.complete();
              await reply.future;
              handler.resolve(
                Response(
                  requestOptions: options,
                  data: {
                    'tokenType': 'Bearer',
                    'accessToken': 'native-access',
                    'refreshToken': 'rotated-proof',
                    'accessExpiresAt': '2030-01-01T00:00:00Z',
                  },
                ),
              );
            } else {
              handler.resolve(Response(requestOptions: options, data: {}));
            }
          },
        ),
      );
      final container = ProviderContainer(
        overrides: [
          apiProvider.overrideWith((ref) => api),
          authProvider.overrideWith(() => AuthController(storage: storage)),
        ],
      );
      addTearDown(container.dispose);
      final auth = container.read(authProvider.notifier);
      final restore = auth.restore();
      await started.future;
      final logout = auth.logout();
      reply.complete();
      await Future.wait([restore, logout]);
      expect(storage.values, isEmpty);
      expect(container.read(authProvider).phase, AuthPhase.signedOut);
    },
  );

  for (final failLogout in [false, true]) {
    test(
      'native failed refresh preserves one logout proof; failure=$failLogout',
      () async {
        final storage = MemoryStorage();
        final api = PlayerApi('https://example.invalid');
        final started = Completer<void>();
        final release = Completer<void>();
        var refreshes = 0;
        var logouts = 0;
        api.dio.interceptors.add(
          InterceptorsWrapper(
            onRequest: (options, handler) async {
              if (options.path.endsWith('/login/local')) {
                handler.resolve(
                  Response(
                    requestOptions: options,
                    data: {
                      'tokenType': 'Bearer',
                      'accessToken': 'pre-refresh-proof',
                      'refreshToken': 'native-refresh',
                      'accessExpiresAt': '2030-01-01T00:00:00Z',
                    },
                  ),
                );
              } else if (options.path.endsWith('/me')) {
                handler.resolve(
                  Response(
                    requestOptions: options,
                    data: {'nickname': 'synthetic'},
                  ),
                );
              } else if (options.path == '/read') {
                handler.reject(
                  DioException(
                    requestOptions: options,
                    response: Response(
                      requestOptions: options,
                      statusCode: 401,
                      data: {'code': 'MEMBER_AUTH_REQUIRED'},
                    ),
                  ),
                );
              } else if (options.path.endsWith('/refresh')) {
                refreshes++;
                started.complete();
                await release.future;
                handler.reject(
                  DioException(
                    requestOptions: options,
                    type: DioExceptionType.connectionError,
                  ),
                );
              } else if (options.path.endsWith('/logout')) {
                logouts++;
                expect(
                  options.headers['Authorization'],
                  'Bearer pre-refresh-proof',
                );
                if (failLogout) {
                  handler.reject(
                    DioException(
                      requestOptions: options,
                      type: DioExceptionType.connectionError,
                    ),
                  );
                } else {
                  handler.resolve(
                    Response(
                      requestOptions: options,
                      data: <String, dynamic>{
                        'state': 'LOGGED_OUT',
                        'requestId': epoch,
                      },
                    ),
                  );
                }
              } else {
                throw StateError('unexpected test endpoint');
              }
            },
          ),
        );
        final container = ProviderContainer(
          overrides: [
            apiProvider.overrideWith((ref) => api),
            authProvider.overrideWith(() => AuthController(storage: storage)),
          ],
        );
        addTearDown(container.dispose);
        final auth = container.read(authProvider.notifier);
        await auth.login('synthetic@example.invalid', 'synthetic');
        final failure = expectLater(
          auth.authorizedGet('/read'),
          throwsA(isA<DioException>()),
        );
        await started.future;
        final logout = auth.logout();
        release.complete();
        await Future.wait([failure, logout]);
        expect(refreshes, 1);
        expect(logouts, 1);
        expect(storage.values, isEmpty);
        final state = container.read(authProvider);
        expect(state.phase, AuthPhase.signedOut);
        if (failLogout) {
          expect(state.error, contains('서버 로그아웃 결과는 확인되지 않았습니다'));
        } else {
          expect(state.error, isNull);
        }
      },
    );
  }

  test('storage and session injection are mutually exclusive', () {
    final session = InjectedSession();
    addTearDown(session.dispose);
    expect(
      () => AuthController(storage: MemoryStorage(), session: session),
      throwsArgumentError,
    );
  });
}
