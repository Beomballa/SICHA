import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sicha_player/auth/auth_controller.dart';
import 'package:sicha_player/auth/auth_session.dart';
import 'package:sicha_player/core/player_api.dart';
import 'package:sicha_player/lobby/invitations.dart';

class _MemoryStorage implements AuthTokenStorage {
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

class _InvitationStub extends InvitationRepository {
  _InvitationStub(super.auth);
  int attempts = 0;
  bool fail = false;

  @override
  Future<TestState> detail(String key) async => TestState(_testState());

  @override
  Future<ConsentNoticeData> notice(String key) async =>
      ConsentNoticeData(_notice());

  @override
  Future<void> accept(ConsentNoticeData notice, String key) async {
    attempts++;
    if (fail) throw StateError('uncertain');
  }
}

Map<String, dynamic> _invitation() => {
  'testKey': 'test-1',
  'inviteGen': 3,
  'title': '테스트 사건',
  'mode': 'FUNCTIONAL',
  'limitSec': 3600,
  'inviteExpiresAt': '2030-01-01T00:00:00Z',
  'inviteStatus': 'SENT',
  'policyCode': 'PLAYTEST',
  'noticeHash': 'hash',
  'noticeSummary': '테스트 원문 90일, 선별 자료 365일',
};

Map<String, dynamic> _testState() => {
  'testKey': 'test-1',
  'title': '테스트 사건',
  'mode': 'FUNCTIONAL',
  'state': 'WAITING',
  'outcome': null,
  'rev': '7',
  'draftRev': '0',
  'snapshotRef': '42',
  'runtimeConfigId': 'RUNTIME',
  'self': {
    'slot': 1,
    'inviteGen': 3,
    'accepted': false,
    'ready': false,
    'roleCode': null,
    'blindStatus': false,
  },
  'partner': {'accepted': false, 'ready': false, 'online': false},
  'inviteExpiresAt': '2030-01-01T00:00:00Z',
  'lobbyExpiresAt': null,
  'startedAt': null,
  'playDeadline': null,
  'serverTime': '2026-10-09T00:00:00Z',
  'submissionState': 'NONE',
  'attemptsRemaining': null,
  'hintsRemaining': null,
  'feedbackUntil': null,
  'feedbackSubmitted': false,
  'resultPhase': 'NONE',
  'requestId': 'request-1',
};

Map<String, dynamic> _notice() => {
  'testKey': 'test-1',
  'generation': 3,
  'revision': '7',
  'policyCode': 'PLAYTEST',
  'noticeHash': 'hash',
  'version': 'v1',
  'body': '테스트 고지',
  'contact': 'support@example.invalid',
};

Map<String, dynamic> _tokens(String access, String refresh) => {
  'tokenType': 'Bearer',
  'accessToken': access,
  'refreshToken': refresh,
  'accessExpiresAt': '2030-01-01T00:00:00Z',
};

void main() {
  test(
    'absent installation marker erases all auth before mark and read',
    () async {
      final events = <String>[];
      final credentials = {
        'player:old-endpoint:refresh': 'old-refresh',
        'player:old-endpoint:refresh:rotating': '1',
        'player:current-endpoint:refresh': 'current-refresh',
      };
      var marker = false;
      final guard = AuthInstallationGuard(
        readMarker: () async {
          events.add('marker-read');
          return marker;
        },
        eraseCredentials: () async {
          events.add('erase');
          credentials.clear();
        },
        writeMarker: () async {
          events.add('mark');
          expect(credentials, isEmpty);
          marker = true;
        },
      );
      await guard.ensureInstalled();
      events.add('credential-read');
      expect(credentials['player:current-endpoint:refresh'], isNull);
      expect(marker, isTrue);
      expect(events, ['marker-read', 'erase', 'mark', 'credential-read']);
      await guard.ensureInstalled();
      expect(events, hasLength(4));
    },
  );

  test(
    'existing installation marker preserves credentials without rewriting',
    () async {
      final events = <String>[];
      final guard = AuthInstallationGuard(
        readMarker: () async {
          events.add('marker-read');
          return true;
        },
        eraseCredentials: () async => events.add('erase'),
        writeMarker: () async => events.add('mark'),
      );
      await guard.ensureInstalled();
      events.add('credential-read');
      expect(events, ['marker-read', 'credential-read']);
    },
  );

  for (final failure in ['read', 'erase', 'mark']) {
    test(
      'installation $failure failure blocks credential use and retries safely',
      () async {
        final events = <String>[];
        var fail = true;
        var marker = false;
        final guard = AuthInstallationGuard(
          readMarker: () async {
            events.add('read');
            if (fail && failure == 'read') throw StateError('read failed');
            return marker;
          },
          eraseCredentials: () async {
            events.add('erase');
            if (fail && failure == 'erase') throw StateError('erase failed');
          },
          writeMarker: () async {
            events.add('mark');
            if (fail && failure == 'mark') throw StateError('mark failed');
            marker = true;
          },
        );
        Future<void> useCredentials(String operation) async {
          await guard.ensureInstalled();
          events.add(operation);
        }

        for (final operation in [
          'credential-read',
          'credential-write',
          'credential-delete',
        ]) {
          await expectLater(useCredentials(operation), throwsStateError);
        }
        expect(marker, isFalse);
        expect(
          events.where((event) => event.startsWith('credential-')),
          isEmpty,
        );
        if (failure != 'mark') expect(events, isNot(contains('mark')));
        fail = false;
        events.clear();
        await useCredentials('credential-read');
        expect(marker, isTrue);
        expect(events, [
          if (failure == 'read') 'read',
          'erase',
          'mark',
          'credential-read',
        ]);
      },
    );
  }

  test(
    'concurrent first installation checks share erase and marking',
    () async {
      final started = Completer<void>();
      final release = Completer<void>();
      final events = <String>[];
      final guard = AuthInstallationGuard(
        readMarker: () async {
          events.add('read');
          return false;
        },
        eraseCredentials: () async {
          events.add('erase');
          started.complete();
          await release.future;
        },
        writeMarker: () async => events.add('mark'),
      );
      final first = guard.ensureInstalled();
      final second = guard.ensureInstalled();
      expect(second, same(first));
      await started.future;
      expect(events, ['read', 'erase']);
      final third = guard.ensureInstalled();
      expect(third, same(first));
      release.complete();
      await Future.wait([first, second, third]);
      expect(events, ['read', 'erase', 'mark']);
    },
  );

  test(
    'shared failed erase rejects every waiter and same owner retries once',
    () async {
      final release = Completer<void>();
      var erases = 0;
      var marks = 0;
      var credentialUses = 0;
      final guard = AuthInstallationGuard(
        readMarker: () async => false,
        eraseCredentials: () async {
          erases++;
          if (erases == 1) {
            await release.future;
            throw StateError('locked Keychain');
          }
        },
        writeMarker: () async {
          marks++;
        },
      );
      Future<void> useCredentials() async {
        await guard.ensureInstalled();
        credentialUses++;
      }

      final first = expectLater(useCredentials(), throwsStateError);
      final second = expectLater(useCredentials(), throwsStateError);
      release.complete();
      await Future.wait([first, second]);
      expect((erases, marks, credentialUses), (1, 0, 0));
      await Future.wait([useCredentials(), useCredentials()]);
      expect((erases, marks, credentialUses), (2, 1, 2));
    },
  );

  test(
    'uncertain marker write is not trusted by the same owner on retry',
    () async {
      final events = <String>[];
      var marker = false;
      var failMark = true;
      final guard = AuthInstallationGuard(
        readMarker: () async {
          events.add('read');
          return marker;
        },
        eraseCredentials: () async => events.add('erase'),
        writeMarker: () async {
          events.add('mark');
          marker = true;
          if (failMark) throw StateError('write acknowledgement lost');
        },
      );
      await expectLater(guard.ensureInstalled(), throwsStateError);
      expect(events, ['read', 'erase', 'mark']);
      failMark = false;
      events.clear();
      await guard.ensureInstalled();
      expect(events, ['erase', 'mark']);
    },
  );

  test(
    'API rejects missing, non-HTTPS, credentialed and path-bearing endpoints',
    () {
      for (final endpoint in [
        '',
        'http://example.invalid',
        'https://user@example.invalid',
        'https://example.invalid/api',
        'https://example.invalid?token=x',
      ]) {
        expect(() => PlayerApi(endpoint), throwsStateError);
      }
      expect(
        PlayerApi('https://example.invalid').endpoint,
        'https://example.invalid/',
      );
    },
  );

  test('request keys are unique canonical UUID v4 values', () {
    final keys = List.generate(64, (_) => requestKey());
    expect(keys.toSet(), hasLength(keys.length));
    for (final key in keys) {
      expect(
        key,
        matches(
          RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
          ),
        ),
      );
    }
  });

  test('invitation and waiting state reject extra participant data', () {
    final invitation = Invitation(_invitation());
    expect((invitation.inviteGen, invitation.inviteStatus), (3, 'SENT'));
    expect(
      () => Invitation({
        ..._invitation(),
        'members': ['other-member'],
      }),
      throwsFormatException,
    );
    expect(
      () => ConsentNoticeData({..._notice(), 'memberKey': 'other-member'}),
      throwsFormatException,
    );
    expect(TestState(_testState()).rev, '7');
    expect(
      () => TestState({..._testState(), 'memberKey': 'other'}),
      throwsFormatException,
    );
    expect(() => TestState({..._testState(), 'rev': 7}), throwsFormatException);
    final page = InvitationPageData({
      'items': [_invitation()],
      'nextCursor': '42',
      'requestId': 'request-1',
    });
    expect(page.items.single.title, '테스트 사건');
    expect(page.nextCursor, '42');
    expect(
      () => InvitationPageData({
        'items': [_invitation()],
        'nextCursor': '042',
        'requestId': 'request-1',
      }),
      throwsFormatException,
    );
    expect(
      () => InvitationPageData({
        'items': [_invitation()],
        'nextCursor': 42,
        'requestId': 'request-1',
      }),
      throwsA(isA<TypeError>()),
    );
  });

  test(
    'POST failure has no implicit retry and retains its original request key',
    () async {
      final api = PlayerApi('https://example.invalid');
      final requests = <RequestOptions>[];
      api.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            requests.add(options);
            handler.reject(
              DioException(
                requestOptions: options,
                type: DioExceptionType.connectionError,
              ),
            );
          },
        ),
      );
      final body = {'requestKey': requestKey(), 'expectedRev': 7};
      await expectLater(
        api.post('/api/playtests/test-1/accept', body, access: 'access'),
        throwsA(isA<DioException>()),
      );
      expect(requests, hasLength(1));
      expect(requests.single.method, 'POST');
      expect(requests.single.data, same(body));
      expect(requests.single.headers['Authorization'], 'Bearer access');
    },
  );

  test('consent sends only the current notice revision, generation and approved fields', () async {
    final storage = _MemoryStorage();
    final api = PlayerApi('https://example.invalid');
    Map<String, dynamic>? sent;
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          if (options.uri.path.endsWith('/login/local')) {
            handler.resolve(
              Response(
                requestOptions: options,
                data: _tokens('access', 'refresh-1'),
              ),
            );
          } else if (options.uri.path.endsWith('/me')) {
            handler.resolve(
              Response(requestOptions: options, data: {'nickname': 'tester'}),
            );
          } else {
            sent = Map<String, dynamic>.from(options.data as Map);
            handler.resolve(
              Response(
                requestOptions: options,
                data: {
                  'action': 'INVITATION_ACCEPT',
                  'changed': true,
                  'replayed': false,
                  'current': {'testKey': 'test-1'},
                },
              ),
            );
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
    await auth.login('tester@example.invalid', 'password');
    await InvitationRepository(auth)
        .accept(ConsentNoticeData(_notice()), requestKey());
    expect(sent!['expectedRev'], '7');
    expect(sent!['inviteGen'], 3);
    expect(sent!['blindDeclared'], false);
    expect(
      sent!.keys,
      unorderedEquals([
        'expectedRev',
        'inviteGen',
        'blindDeclared',
        'policyCode',
        'noticeHash',
        'requestKey',
      ]),
    );
  });

  test('explicit 403 auth failure refreshes concurrent GETs once, never retries POST', () async {
    final storage = _MemoryStorage();
    final api = PlayerApi('https://example.invalid');
    final refreshReply = Completer<void>();
    final refreshStarted = Completer<void>();
    var refreshes = 0;
    var oldGets = 0;
    var newGets = 0;
    var posts = 0;
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          final path = options.uri.path;
          if (path == '/api/member/auth/login/local') {
            handler.resolve(
              Response(
                requestOptions: options,
                data: _tokens('old', 'refresh-1'),
              ),
            );
          } else if (path == '/api/member/auth/me') {
            handler.resolve(
              Response(requestOptions: options, data: {'nickname': 'tester'}),
            );
          } else if (path == '/api/member/auth/refresh') {
            refreshes++;
            refreshStarted.complete();
            await refreshReply.future;
            handler.resolve(
              Response(
                requestOptions: options,
                data: _tokens('new', 'refresh-2'),
              ),
            );
          } else if (options.method == 'GET') {
            if (options.headers['Authorization'] == 'Bearer old') {
              oldGets++;
              handler.reject(
                DioException(
                  requestOptions: options,
                  response: Response(
                    requestOptions: options,
                    statusCode: 403,
                    data: {'code': 'MEMBER_AUTH_REQUIRED'},
                  ),
                  type: DioExceptionType.badResponse,
                ),
              );
            } else {
              newGets++;
              handler.resolve(
                Response(requestOptions: options, data: {'ok': true}),
              );
            }
          } else {
            posts++;
            handler.reject(
              DioException(
                requestOptions: options,
                response: Response(
                  requestOptions: options,
                  statusCode: 403,
                  data: {'code': 'MEMBER_AUTH_REQUIRED'},
                ),
                type: DioExceptionType.badResponse,
              ),
            );
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
    await auth.login('tester@example.invalid', 'password');
    expect(container.read(authProvider).phase, AuthPhase.signedIn);
    final first = auth.authorizedGet('/one');
    final second = auth.authorizedGet('/two');
    await refreshStarted.future;
    expect((oldGets, refreshes), (2, 1));
    refreshReply.complete();
    expect(await Future.wait([first, second]), [
      {'ok': true},
      {'ok': true},
    ]);
    expect((refreshes, newGets), (1, 2));
    await expectLater(
      auth.authorizedPost('/accept', {'requestKey': requestKey()}),
      throwsA(isA<DioException>()),
    );
    expect((posts, refreshes), (1, 1));
    expect(storage.values.values, contains('refresh-2'));
  });

  test(
    'uncertain refresh preserves rotation marker and does not reuse token',
    () async {
      final storage = _MemoryStorage();
      final api = PlayerApi('https://example.invalid');
      var refreshes = 0;
      api.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            if (options.uri.path.endsWith('/refresh')) {
              refreshes++;
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
                  data: _tokens('access', 'refresh-1'),
                ),
              );
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
      storage.values['player:${Uri.encodeComponent(api.endpoint)}:refresh'] =
          'refresh-1';
      final auth = container.read(authProvider.notifier);
      await auth.restore();
      expect(container.read(authProvider).phase, AuthPhase.loginRequired);
      expect(storage.values.values, isNot(contains('refresh-1')));
      expect(storage.values.values, contains('1'));
      await expectLater(
        auth.authorizedGet('/api/playtests/identity'),
        throwsStateError,
      );
      expect(refreshes, 1);
    },
  );

  test(
    'logout owns state and storage when an in-flight login completes late',
    () async {
      final storage = _MemoryStorage();
      final api = PlayerApi('https://example.invalid');
      final loginReply = Completer<void>();
      final loginStarted = Completer<void>();
      api.dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) async {
            if (options.uri.path.endsWith('/login/local')) {
              loginStarted.complete();
              await loginReply.future;
              handler.resolve(
                Response(
                  requestOptions: options,
                  data: _tokens('access', 'refresh-1'),
                ),
              );
            } else {
              handler.resolve(
                Response(requestOptions: options, data: {'nickname': 'tester'}),
              );
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
      final login = auth.login('tester@example.invalid', 'password');
      await loginStarted.future;
      final logout = auth.logout();
      loginReply.complete();
      await Future.wait([login, logout]);
      expect(container.read(authProvider).phase, AuthPhase.signedOut);
      expect(storage.values, isEmpty);
    },
  );

  test('missing endpoint fails before touching the native store', () async {
    final container = ProviderContainer(
      overrides: [
        authProvider.overrideWith(
          () => AuthController(storage: _MemoryStorage()),
        ),
      ],
    );
    addTearDown(container.dispose);
    await container.read(authProvider.notifier).restore();
    expect(container.read(authProvider).phase, AuthPhase.recovery);
    expect(container.read(authProvider).error, contains('PLAYER_API_BASE_URL'));
  });

  testWidgets(
    'uncertain consent preserves checkbox and forbids resending until a read',
    (tester) async {
      final storage = _MemoryStorage();
      final api = PlayerApi('https://example.invalid');
      final container = ProviderContainer(
        overrides: [
          apiProvider.overrideWith((ref) => api),
          authProvider.overrideWith(() => AuthController(storage: storage)),
        ],
      );
      addTearDown(container.dispose);
      final repository = _InvitationStub(container.read(authProvider.notifier))
        ..fail = true;
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: ProviderScope(
            overrides: [
              invitationRepositoryProvider.overrideWith((ref) => repository),
            ],
            child: const MaterialApp(
              home: InvitationDetailPage(testKey: 'test-1'),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byType(CheckboxListTile));
      await tester.pump();
      await tester.tap(find.text('동의하고 초대 수락'));
      await tester.pumpAndSettle();
      expect(repository.attempts, 1);
      expect(
        (tester.widget<CheckboxListTile>(find.byType(CheckboxListTile))).value,
        isTrue,
      );
      expect(find.text('동의하고 초대 수락'), findsNothing);
      expect(find.text('서버 상태 확인'), findsOneWidget);
    },
  );
}
