import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sicha_player/auth/auth_controller.dart';
import 'package:sicha_player/core/player_api.dart';
import 'package:sicha_player/core/player_theme.dart';
import 'package:sicha_player/investigation/investigation.dart';

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

Map<String, dynamic> _materials() => {
  'basic': {
    'title': '공개 사건',
    'intro': '공개 소개',
    'setting': '공개 장소',
    'difficulty': 2,
    'estMin': 20,
    'estMax': 30,
    'limitSec': 1800,
  },
  'role': {'code': 'R1', 'name': '탐정', 'brief': '공개 임무'},
  'persons': [
    {'code': 'P1', 'name': '증인', 'publicText': '공개 증언'},
  ],
  'clues': [
    {'code': 'C1', 'title': '메모', 'body': '공개 본문', 'personCode': null},
  ],
  'openedHints': [
    {'level': 1, 'body': '열린 힌트'},
  ],
  'requiredNotices': [
    {'rubricCode': 'EVIDENCE', 'text': '필수 고지'},
  ],
  'requestId': 'request-1',
};

Map<String, dynamic> _waitingState() => {
  'testKey': 'test-1',
  'title': '늦게 도착한 사건',
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
    'accepted': true,
    'ready': false,
    'roleCode': null,
    'blindStatus': false,
  },
  'partner': {'accepted': true, 'ready': false, 'online': false},
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

Map<String, dynamic> _tokens() => {
  'tokenType': 'Bearer',
  'accessToken': 'access-1',
  'refreshToken': 'refresh-1',
  'accessExpiresAt': '2030-01-01T00:00:00Z',
};

Map<String, dynamic> _result(RequestOptions request, {bool replayed = false}) {
  final safe = <String, dynamic>{
    'testKey': request.uri.path.split('/')[3],
    'rev': '8',
    'state': 'WAITING',
    'outcome': null,
    'startedAt': null,
    'playDeadline': null,
  };
  return {
    'action': request.uri.path.endsWith('/ready')
        ? 'TEST_READY'
        : request.uri.path.endsWith('/start')
        ? 'TEST_START'
        : 'HINT_OPEN',
    'replayed': replayed,
    'changed': !replayed,
    'original': safe,
    'current': safe,
    'requestId': 'request-1',
  };
}

Map<String, dynamic> _runningState({
  String key = 'test-1',
  String role = 'R1',
}) => {
  ..._waitingState(),
  'testKey': key,
  'state': 'RUNNING',
  'startedAt': '2026-10-09T00:00:00Z',
  'playDeadline': '2030-01-01T00:00:00Z',
  'hintsRemaining': 2,
  'self': {..._waitingState()['self'] as Map, 'roleCode': role},
};

Future<void> _until(WidgetTester tester, bool Function() condition) async {
  for (var frame = 0; frame < 20 && !condition(); frame++) {
    await tester.pump(const Duration(milliseconds: 10));
  }
  expect(condition(), isTrue);
}

Future<void> _mount(
  WidgetTester tester,
  ProviderContainer container, {
  String key = 'test-1',
}) async {
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(home: InvestigationPage(testKey: key)),
    ),
  );
}

/// Scroll the real lazy list; finding a DTO-backed widget is not viewport proof.
Future<void> _show(WidgetTester tester, Finder target) async {
  // Finish the edit before scrolling: EditableText's deferred caret reveal
  // otherwise scrolls the list back after ensureVisible has positioned a button.
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
  final scrollable = find.byType(Scrollable).first;
  if (target.evaluate().isEmpty) {
    // Walk back with real gestures instead of jumping an estimated lazy-list
    // offset while retained editable children are changing during a refresh.
    for (var step = 0; step < 30; step++) {
      final position = tester.state<ScrollableState>(scrollable).position;
      if (position.pixels <= position.minScrollExtent) break;
      await tester.drag(scrollable, const Offset(0, 300));
      await tester.pump(const Duration(milliseconds: 10));
    }
    await tester.scrollUntilVisible(
      target,
      250,
      scrollable: scrollable,
      duration: const Duration(milliseconds: 10),
    );
  }
  // Align in the page viewport only, not any nested editable viewport.
  await tester
      .state<ScrollableState>(scrollable)
      .position
      .ensureVisible(tester.renderObject(target), alignment: 0.5);
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

Future<void> _tap(WidgetTester tester, String label) async {
  final target = find.text(label);
  await _show(tester, target);
  expect(target.hitTestable(), findsOneWidget);
  await tester.tap(target);
  await tester.pump();
}

Finder get _notesField => find.widgetWithText(TextField, '개인 조사 기록 (임시 메모)');

/// 실제 인증 컨트롤러와 Dio 전송 계층을 사용해 로그인된 저장소를 만든다.
Future<(ProviderContainer, AuthController)> _signedIn(PlayerApi api) async {
  final container = ProviderContainer(
    overrides: [
      apiProvider.overrideWith((ref) => api),
      authProvider.overrideWith(
        () => AuthController(storage: _MemoryStorage()),
      ),
    ],
  );
  addTearDown(container.dispose);
  final auth = container.read(authProvider.notifier);
  await auth.login('tester@example.invalid', 'password');
  expect(container.read(authProvider).phase, AuthPhase.signedIn);
  return (container, auth);
}

/// 인증 요청에는 정상 JSON만 반환하고 나머지 요청은 검증용 핸들러에 전달한다.
/// Test-only Dio responses are not real network, backend, or native evidence.
void _intercept(
  PlayerApi api,
  FutureOr<void> Function(RequestOptions, RequestInterceptorHandler) respond, {
  Map<String, dynamic>? loginTokens,
}) {
  api.dio.interceptors.add(
    InterceptorsWrapper(
      onRequest: (options, handler) async {
        switch (options.uri.path) {
          case '/api/member/auth/login/local':
            handler.resolve(
              Response(requestOptions: options, data: loginTokens ?? _tokens()),
            );
            return;
          case '/api/member/auth/me':
            handler.resolve(
              Response(requestOptions: options, data: {'nickname': 'tester'}),
            );
            return;
          case '/api/member/auth/logout':
            handler.resolve(
              Response(requestOptions: options, data: {'ok': true}),
            );
            return;
          default:
            await respond(options, handler);
        }
      },
    ),
  );
}

void main() {
  test(
    'materials allow only role-visible fields and reject secret sentinels',
    () {
      final visible = InvestigationMaterials(_materials());
      expect(visible.role['code'], 'R1');
      expect(visible.clues.single['personCode'], isNull);
      expect(visible.openedHints.single['body'], '열린 힌트');
      expect(visible.requiredNotices.single['text'], '필수 고지');

      for (final secret in [
        {..._materials(), 'answer': 'SECRET_SENTINEL'},
        {
          ..._materials(),
          'role': {
            ..._materials()['role'] as Map,
            'secretText': 'SECRET_SENTINEL',
          },
        },
        {
          ..._materials(),
          'persons': [
            {
              ...(_materials()['persons'] as List).single as Map,
              'secretText': 'SECRET_SENTINEL',
            },
          ],
        },
        {
          ..._materials(),
          'clues': [
            {
              ...(_materials()['clues'] as List).single as Map,
              'sourceText': 'SECRET_SENTINEL',
            },
          ],
        },
        {
          ..._materials(),
          'openedHints': [
            {
              ...(_materials()['openedHints'] as List).single as Map,
              'answer': 'SECRET_SENTINEL',
            },
          ],
        },
      ]) {
        expect(() => InvestigationMaterials(secret), throwsFormatException);
      }
      expect(
        () => InvestigationMaterials({
          ..._materials(),
          'persons': [
            {'code': 'P1', 'name': '증인', 'publicText': 42},
          ],
        }),
        throwsFormatException,
      );
      expect(
        () => InvestigationMaterials({
          ..._materials(),
          'clues': [
            {'code': 'C1', 'title': '메모', 'body': '공개 본문'},
          ],
        }),
        throwsFormatException,
      );
      expect(
        () => InvestigationMaterials({
          ..._materials(),
          'clues': [
            {'code': 'C1', 'title': '메모', 'body': '공개 본문', 'personCode': 42},
          ],
        }),
        throwsFormatException,
      );
      expect(
        () => InvestigationMaterials({
          ..._materials(),
          'openedHints': [
            {'level': 4, 'body': '잘못된 단계'},
          ],
        }),
        throwsFormatException,
      );
    },
  );

  test(
    'ready, start and hint send only explicit requests through current auth',
    () async {
      final api = PlayerApi('https://example.invalid');
      final sent = <RequestOptions>[];
      _intercept(api, (options, handler) {
        sent.add(options);
        handler.resolve(
          Response(requestOptions: options, data: _result(options)),
        );
      });
      final (_, auth) = await _signedIn(api);
      final repo = InvestigationRepository(auth);

      await repo.ready('test-1', '7', true);
      await repo.start('test-1', '8');
      await repo.openHint('test-1', '9', 2);
      await expectLater(repo.openHint('test-1', '9', 4), throwsRangeError);

      expect(sent.map((request) => request.uri.path), [
        '/api/playtests/test-1/ready',
        '/api/playtests/test-1/start',
        '/api/playtests/test-1/hints/2/open',
      ]);
      expect(sent.map((request) => request.method), everyElement('POST'));
      expect(
        sent.map((request) => request.headers['Authorization']),
        everyElement('Bearer access-1'),
      );
      final bodies = sent
          .map((request) => Map<String, dynamic>.from(request.data as Map))
          .toList();
      expect(
        bodies[0].keys,
        unorderedEquals(['expectedRev', 'ready', 'requestKey']),
      );
      expect(bodies[0]['expectedRev'], '7');
      expect(bodies[0]['ready'], isTrue);
      expect(bodies[1].keys, unorderedEquals(['expectedRev', 'requestKey']));
      expect(bodies[1]['expectedRev'], '8');
      expect(bodies[2].keys, unorderedEquals(['expectedRev', 'requestKey']));
      expect(bodies[2]['expectedRev'], '9');
      final keys = bodies.map((body) => body['requestKey']).toList();
      expect(keys.toSet(), hasLength(3));
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
    },
  );

  test('canonical action receipt rejects wrong action, test, types and unsafe fields', () async {
    final api = PlayerApi('https://example.invalid');
    Map<String, dynamic> Function(RequestOptions) response = _result;
    _intercept(api, (options, handler) {
      handler.resolve(
        Response(requestOptions: options, data: response(options)),
      );
    });
    final (_, auth) = await _signedIn(api);
    final repo = InvestigationRepository(auth);
    for (final mutate in <void Function(Map<String, dynamic>)>[
      (json) => json['action'] = 'TEST_START',
      (json) => json['changed'] = 'true',
      (json) => json['replayed'] = 1,
      (json) => json.remove('requestId'),
      (json) => (json['original'] as Map)['testKey'] = 'test-other',
      (json) => (json['current'] as Map)['rev'] = 8,
      (json) => (json['current'] as Map).remove('outcome'),
      (json) => (json['current'] as Map)['startedAt'] = false,
      (json) => (json['current'] as Map)['answer'] = 'SECRET_SENTINEL',
    ]) {
      response = (request) {
        final json = _result(request);
        mutate(json);
        return json;
      };
      await expectLater(repo.ready('test-1', '7', true), throwsFormatException);
    }
  });

  test('authenticated PATCH refreshes once for concurrent edits and never retries a rejected mutation', () async {
    final api = PlayerApi('https://example.invalid');
    final sent = <RequestOptions>[];
    var refreshes = 0;
    _intercept(
      api,
      (options, handler) {
        if (options.uri.path == '/api/member/auth/refresh') {
          refreshes++;
          handler.resolve(
            Response(
              requestOptions: options,
              data: {
                ..._tokens(),
                'accessToken': 'rotated-access',
                'refreshToken': 'rotated-refresh',
              },
            ),
          );
        } else {
          sent.add(options);
          handler.reject(
            DioException(
              requestOptions: options,
              type: DioExceptionType.badResponse,
              response: Response(
                requestOptions: options,
                statusCode: 401,
                data: {'code': 'MEMBER_AUTH_REQUIRED'},
              ),
            ),
          );
        }
      },
      loginTokens: {
        ..._tokens(),
        'accessExpiresAt': DateTime.now()
            .add(const Duration(seconds: 31))
            .toUtc()
            .toIso8601String(),
      },
    );
    final (_, auth) = await _signedIn(api);
    await Future<void>.delayed(const Duration(milliseconds: 1100));
    final repo = InvestigationRepository(auth);
    final first = repo.send(
      repo.editIntent(
        'test-1',
        '7',
        '4',
        _sharedReport()['report'] as Map<String, dynamic>,
      ),
    );
    final second = repo.send(
      repo.editIntent(
        'test-1',
        '7',
        '4',
        _sharedReport()['report'] as Map<String, dynamic>,
      ),
    );
    await expectLater(first, throwsA(isA<DioException>()));
    await expectLater(second, throwsA(isA<DioException>()));
    expect(refreshes, 1);
    expect(sent, hasLength(2));
    for (final request in sent) {
      expect(request.method, 'PATCH');
      expect(request.headers['Authorization'], 'Bearer rotated-access');
      expect(
        (request.data as Map).keys,
        unorderedEquals([
          'expectedRev',
          'expectedDraftRev',
          'report',
          'requestKey',
        ]),
      );
    }
    expect(
      (sent[0].data as Map)['requestKey'],
      isNot((sent[1].data as Map)['requestKey']),
    );
  });

  test('late PATCH receipt cannot become success after logout', () async {
    final api = PlayerApi('https://example.invalid');
    final pending = Completer<void>();
    final started = Completer<void>();
    _intercept(api, (options, handler) async {
      if (options.method == 'PATCH') {
        started.complete();
        await pending.future;
        handler.resolve(
          Response(
            requestOptions: options,
            data: _reportReceipt('REPORT_EDIT'),
          ),
        );
      } else {
        handler.reject(DioException(requestOptions: options));
      }
    });
    final (container, auth) = await _signedIn(api);
    final repo = InvestigationRepository(auth);
    final flight = repo.send(
      repo.editIntent(
        'test-1',
        '7',
        '4',
        _sharedReport()['report'] as Map<String, dynamic>,
      ),
    );
    await started.future;
    await auth.logout();
    pending.complete();
    await expectLater(flight, throwsStateError);
    expect(container.read(authProvider).phase, AuthPhase.signedOut);
  });

  test('heartbeat accepts only empty 204, rejecting malformed successful responses', () async {
    final api = PlayerApi('https://example.invalid');
    final sent = <RequestOptions>[];
    var status = 204;
    Object? body;
    _intercept(api, (options, handler) {
      sent.add(options);
      handler.resolve(
        Response(requestOptions: options, statusCode: status, data: body),
      );
    });
    final (_, auth) = await _signedIn(api);
    final repo = InvestigationRepository(auth);

    await repo.heartbeat('test-1');
    status = 200;
    body = {'ok': true};
    await expectLater(repo.heartbeat('test-1'), throwsFormatException);
    status = 204;
    body = {'unexpected': true};
    await expectLater(repo.heartbeat('test-1'), throwsFormatException);

    expect(sent, hasLength(3));
    for (final request in sent) {
      expect(request.uri.path, '/api/playtests/test-1/heartbeat');
      expect(request.method, 'POST');
      expect(request.data, isEmpty);
      expect(request.headers['Authorization'], 'Bearer access-1');
    }
  });

  for (final malformed in [false, true]) {
    testWidgets(
      malformed
          ? 'malformed success remains unknown'
          : 'response loss requires exact original explicit replay',
      (tester) async {
        final api = PlayerApi('https://example.invalid');
        final posts = <RequestOptions>[];
        var reads = 0;
        _intercept(api, (options, handler) {
          if (options.uri.path.endsWith('/heartbeat')) {
            handler.resolve(Response(requestOptions: options, statusCode: 204));
          } else if (options.method == 'GET') {
            reads++;
            final state = _waitingState();
            if (posts.isNotEmpty) {
              state['rev'] = '9';
              (state['self'] as Map)['ready'] = true;
            }
            handler.resolve(Response(requestOptions: options, data: state));
          } else {
            posts.add(options);
            if (posts.length == 1 && !malformed) {
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
                  data: posts.length == 1
                      ? {'changed': true}
                      : _result(options, replayed: true),
                ),
              );
            }
          }
        });
        final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
        await _mount(tester, container);
        await _until(tester, () => find.text('준비하기').evaluate().isNotEmpty);
        await _tap(tester, '준비하기');
        await _until(tester, () => find.text('준비 해제').evaluate().isNotEmpty);
        expect(posts, hasLength(1));
        expect(find.text('원본 요청 다시 보내기'), findsOneWidget);
        final before = reads;
        await _tap(tester, '서버 상태 다시 조회');
        await _until(
          tester,
          () => reads > before && find.text('준비 해제').evaluate().isNotEmpty,
        );
        await _tap(tester, '준비 해제');
        expect(posts, hasLength(1));
        await _tap(tester, '원본 요청 다시 보내기');
        await _until(
          tester,
          () =>
              posts.length == 2 && find.text('원본 요청 다시 보내기').evaluate().isEmpty,
        );
        expect(posts[1].uri.path, posts[0].uri.path);
        expect(posts[1].data, posts[0].data);
        expect((posts[1].data as Map)['expectedRev'], '7');
        expect((posts[1].data as Map)['ready'], true);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  for (final previouslyUnknown in [false, true]) {
    testWidgets(
      '409 requires fresh explicit command; prior unknown=$previouslyUnknown',
      (tester) async {
        final api = PlayerApi('https://example.invalid');
        final posts = <RequestOptions>[];
        var reads = 0;
        final freshReply = Completer<void>();
        _intercept(api, (options, handler) async {
          if (options.uri.path.endsWith('/heartbeat')) {
            handler.resolve(Response(requestOptions: options, statusCode: 204));
          } else if (options.method == 'GET') {
            reads++;
            if (reads > (previouslyUnknown ? 2 : 1)) await freshReply.future;
            final state = _waitingState();
            if (reads > 1) state['rev'] = '9';
            handler.resolve(Response(requestOptions: options, data: state));
          } else {
            posts.add(options);
            if (previouslyUnknown && posts.length == 1) {
              handler.reject(
                DioException(
                  requestOptions: options,
                  type: DioExceptionType.connectionError,
                ),
              );
            } else if (posts.length <= (previouslyUnknown ? 2 : 1)) {
              handler.reject(
                DioException(
                  requestOptions: options,
                  type: DioExceptionType.badResponse,
                  response: Response(requestOptions: options, statusCode: 409),
                ),
              );
            } else {
              handler.resolve(
                Response(requestOptions: options, data: _result(options)),
              );
            }
          }
        });
        final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
        await _mount(tester, container);
        await _until(tester, () => find.text('준비하기').evaluate().isNotEmpty);
        await _tap(tester, '준비하기');
        if (previouslyUnknown) {
          await _until(
            tester,
            () => find.text('준비하기').evaluate().isNotEmpty && reads == 2,
          );
          await _tap(tester, '원본 요청 다시 보내기');
        }
        await _until(tester, () => reads > (previouslyUnknown ? 2 : 1));
        expect(find.text('준비하기'), findsNothing);
        expect(posts, hasLength(previouslyUnknown ? 2 : 1));
        freshReply.complete();
        await _until(tester, () => find.text('준비하기').evaluate().isNotEmpty);
        if (previouslyUnknown) {
          expect(find.text('원본 요청 다시 보내기'), findsOneWidget);
          expect(posts[1].data, posts[0].data);
          await _tap(tester, '준비하기');
          expect(posts, hasLength(2));
        } else {
          expect(find.text('원본 요청 다시 보내기'), findsNothing);
          await _tap(tester, '준비하기');
          await _until(tester, () => posts.length == 2);
          expect((posts[1].data as Map)['expectedRev'], '9');
          expect(
            (posts[1].data as Map)['requestKey'],
            isNot((posts[0].data as Map)['requestKey']),
          );
        }
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets(
    'background POST releases busy; foreground reads ignore late completion',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final reply = Completer<void>();
      var posts = 0;
      var reads = 0;
      _intercept(api, (options, handler) async {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'GET') {
          reads++;
          final state = _waitingState();
          state['title'] = 'fresh-$reads';
          handler.resolve(Response(requestOptions: options, data: state));
        } else {
          posts++;
          await reply.future;
          handler.resolve(
            Response(requestOptions: options, data: _result(options)),
          );
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _until(tester, () => find.text('준비하기').evaluate().isNotEmpty);
      await _tap(tester, '준비하기');
      await _until(tester, () => posts == 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      final before = reads;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await _until(
        tester,
        () => reads > before && find.text('준비하기').evaluate().isNotEmpty,
      );
      expect(find.text('원본 요청 다시 보내기'), findsOneWidget);
      expect(posts, 1);
      final resumedReads = reads;
      reply.complete();
      await tester.pump(const Duration(milliseconds: 100));
      expect(reads, resumedReads);
      expect(posts, 1);
      expect(find.text('원본 요청 다시 보내기'), findsOneWidget);
      await _tap(tester, '서버 상태 다시 조회');
      await _until(tester, () => reads > resumedReads);
      expect(posts, 1);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'notes stay hidden until matching materials proof; revocation and test switch purge',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      var offline = false;
      var revoked = false;
      var mismatch = false;
      var materialReads = 0;
      Completer<void>? materialReply;
      _intercept(api, (options, handler) async {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (offline || revoked) {
          handler.reject(
            DioException(
              requestOptions: options,
              type: offline
                  ? DioExceptionType.connectionError
                  : DioExceptionType.badResponse,
              response: revoked
                  ? Response(requestOptions: options, statusCode: 403)
                  : null,
            ),
          );
        } else if (options.uri.path.endsWith('/materials')) {
          materialReads++;
          if (materialReply != null) await materialReply.future;
          final materials = _materials();
          if (mismatch) (materials['role'] as Map)['code'] = 'R2';
          handler.resolve(Response(requestOptions: options, data: materials));
        } else if (options.uri.path.endsWith('/report')) {
          handler.resolve(
            Response(
              requestOptions: options,
              data: {
                ..._sharedReport(),
                'testKey': options.uri.path.split('/')[3],
                'draftRev': '0',
                'report': {
                  'culpritCode': null,
                  'method': '',
                  'time': '',
                  'motive': '',
                  'evidence': '',
                },
              },
            ),
          );
        } else {
          handler.resolve(
            Response(
              requestOptions: options,
              data: _runningState(key: options.uri.path.split('/')[3]),
            ),
          );
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, _notesField);
      await tester.enterText(_notesField, 'private-note');
      offline = true;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(
        tester,
        () => find.textContaining('연결이 복구되면').evaluate().isNotEmpty,
      );
      expect(find.text('private-note'), findsNothing);
      offline = false;
      materialReply = Completer<void>();
      final before = materialReads;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(tester, () => materialReads > before);
      expect(find.text('private-note'), findsNothing);
      materialReply.complete();
      materialReply = null;
      await _show(tester, _notesField);
      expect(
        tester.widget<TextField>(_notesField).controller!.text,
        'private-note',
      );
      mismatch = true;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(
        tester,
        () => find.textContaining('연결이 복구되면').evaluate().isNotEmpty,
      );
      mismatch = false;
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, _notesField);
      expect(
        tester.widget<TextField>(_notesField).controller!.text,
        'private-note',
      );
      revoked = true;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(
        tester,
        () => find.textContaining('연결이 복구되면').evaluate().isNotEmpty,
      );
      revoked = false;
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, _notesField);
      expect(tester.widget<TextField>(_notesField).controller!.text, isEmpty);
      await tester.enterText(_notesField, 'test-one-note');
      await _mount(tester, container, key: 'test-2');
      await _show(tester, _notesField);
      expect(tester.widget<TextField>(_notesField).controller!.text, isEmpty);
      await _mount(tester, container);
      await _show(tester, _notesField);
      expect(tester.widget<TextField>(_notesField).controller!.text, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('hint response loss preserves notes and exact original replay', (
    tester,
  ) async {
    final api = PlayerApi('https://example.invalid');
    final posts = <RequestOptions>[];
    var role = 'R1';
    _intercept(api, (options, handler) {
      if (options.uri.path.endsWith('/heartbeat')) {
        handler.resolve(Response(requestOptions: options, statusCode: 204));
      } else if (options.uri.path.endsWith('/materials')) {
        final materials = _materials();
        (materials['role'] as Map)['code'] = role;
        handler.resolve(Response(requestOptions: options, data: materials));
      } else if (options.method == 'GET' &&
          options.uri.path.endsWith('/report')) {
        handler.resolve(
          Response(
            requestOptions: options,
            data: {
              ..._sharedReport(),
              'draftRev': '0',
              'report': {
                'culpritCode': null,
                'method': '',
                'time': '',
                'motive': '',
                'evidence': '',
              },
            },
          ),
        );
      } else if (options.method == 'GET') {
        handler.resolve(
          Response(
            requestOptions: options,
            data: _runningState(role: role),
          ),
        );
      } else {
        posts.add(options);
        if (posts.length == 1) {
          handler.reject(
            DioException(
              requestOptions: options,
              type: DioExceptionType.connectionError,
            ),
          );
        } else {
          final result = _result(options, replayed: true);
          for (final field in ['original', 'current']) {
            final safe = result[field] as Map;
            safe['state'] = 'RUNNING';
            safe['startedAt'] = '2026-10-09T00:00:00Z';
            safe['playDeadline'] = '2030-01-01T00:00:00Z';
          }
          handler.resolve(Response(requestOptions: options, data: result));
        }
      }
    });
    final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
    await _mount(tester, container);
    await _show(tester, _notesField);
    await tester.enterText(_notesField, 'hint-note');
    await _tap(tester, '2단계 힌트 열기');
    await _until(tester, () => posts.length == 1);
    await _show(tester, find.text('원본 요청 다시 보내기'));
    await _show(tester, _notesField);
    expect(tester.widget<TextField>(_notesField).controller!.text, 'hint-note');
    await _tap(tester, '원본 요청 다시 보내기');
    await _until(
      tester,
      () => posts.length == 2 && find.text('원본 요청 다시 보내기').evaluate().isEmpty,
    );
    expect(posts[1].uri.path, '/api/playtests/test-1/hints/2/open');
    expect(posts[1].data, posts[0].data);
    role = 'R2';
    await _tap(tester, '서버 상태 다시 조회');
    await _show(tester, _notesField);
    expect(tester.widget<TextField>(_notesField).controller!.text, isEmpty);
    role = 'R1';
    await _tap(tester, '서버 상태 다시 조회');
    await _show(tester, _notesField);
    expect(tester.widget<TextField>(_notesField).controller!.text, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'test switch prevents old POST completion restoring an original intent',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final reply = Completer<void>();
      var posts = 0;
      _intercept(api, (options, handler) async {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'GET') {
          final state = _waitingState();
          state['testKey'] = options.uri.path.split('/')[3];
          state['title'] = state['testKey'];
          handler.resolve(Response(requestOptions: options, data: state));
        } else {
          posts++;
          await reply.future;
          handler.reject(
            DioException(
              requestOptions: options,
              type: DioExceptionType.connectionError,
            ),
          );
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _until(tester, () => find.text('준비하기').evaluate().isNotEmpty);
      await _tap(tester, '준비하기');
      await _until(tester, () => posts == 1);
      await _mount(tester, container, key: 'test-2');
      await _until(tester, () => find.text('test-2').evaluate().isNotEmpty);
      reply.complete();
      await tester.pump(const Duration(milliseconds: 100));
      expect(find.text('test-1'), findsNothing);
      expect(find.text('원본 요청 다시 보내기'), findsNothing);
      expect(posts, 1);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'logout fences a delayed state response from the investigation widget',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final stateStarted = Completer<void>();
      final stateReply = Completer<void>();
      var stateReads = 0;
      var heartbeats = 0;
      _intercept(api, (options, handler) async {
        if (options.uri.path == '/api/playtests/test-1') {
          stateReads++;
          if (!stateStarted.isCompleted) stateStarted.complete();
          await stateReply.future;
          handler.resolve(
            Response(requestOptions: options, data: _waitingState()),
          );
        } else if (options.uri.path.endsWith('/heartbeat')) {
          heartbeats++;
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else {
          handler.reject(DioException(requestOptions: options));
        }
      });
      final (container, auth) = (await tester.runAsync(() => _signedIn(api)))!;
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: InvestigationPage(testKey: 'test-1')),
        ),
      );
      for (var frame = 0; frame < 20 && !stateStarted.isCompleted; frame++) {
        await tester.pump(const Duration(milliseconds: 10));
      }
      expect(stateStarted.isCompleted, isTrue);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      await tester.runAsync(auth.logout);
      stateReply.complete();
      await tester.pump();
      await tester.pump(const Duration(seconds: 16));
      expect(container.read(authProvider).phase, AuthPhase.signedOut);
      expect(find.text('늦게 도착한 사건'), findsNothing);
      expect(find.byType(InvestigationPage), findsOneWidget);
      expect(stateReads, 1);
      expect(heartbeats, 0);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  // Dio interceptors here model responses; they do not prove backend, network or native behavior.
  test(
    'report/result envelopes reject unsolicited truth and malformed gates',
    () {
      final report = _sharedReport();
      expect(ReportView(report).report['method'], '공동 방법');
      expect(
        () => ReportView({...report, 'answer': 'SECRET_SENTINEL'}),
        throwsFormatException,
      );
      expect(() => ReportView(<String, dynamic>{}), throwsFormatException);
      expect(
        () => ReportView({
          ...report,
          'report': {...report['report'] as Map}..remove('evidence'),
        }),
        throwsFormatException,
      );
      expect(
        () => ReportView({
          ...report,
          'proposal': {..._proposal(), 'snapshot': 'SECRET_SENTINEL'},
        }),
        throwsFormatException,
      );
      final waiting = _endedResult();
      expect(ResultView(waiting).data['finalScore'], isNull);
      for (final field in [
        'finalScore',
        'scoreSummary',
        'reports',
        'revealText',
      ]) {
        expect(
          () => ResultView({..._endedResult(), field: 'SECRET_SENTINEL'}),
          throwsFormatException,
        );
      }
      expect(
        () => ResultView({..._endedResult(), 'resultPhase': 'AVAILABLE'}),
        throwsFormatException,
      );
    },
  );

  testWidgets(
    'two authors read server draft; only eligible partner accepts fixed proposal',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final posts = <RequestOptions>[];
      var role = 'R1';
      var proposal = false;
      var pending = false;
      final acceptReply = Completer<void>();
      _intercept(api, (options, handler) async {
        final path = options.uri.path;
        if (path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'GET' && path.endsWith('/materials')) {
          final materials = _materials();
          (materials['role'] as Map)['code'] = role;
          handler.resolve(Response(requestOptions: options, data: materials));
        } else if (options.method == 'GET' && path.endsWith('/report')) {
          handler.resolve(
            Response(
              requestOptions: options,
              data: _sharedReport(
                proposal: proposal,
                pending: pending,
                role: role,
              ),
            ),
          );
        } else if (options.method == 'GET') {
          final state = _runningState(role: role);
          state['draftRev'] = '4';
          state['submissionState'] = pending
              ? 'PENDING'
              : proposal
              ? 'PROPOSED'
              : 'NONE';
          handler.resolve(Response(requestOptions: options, data: state));
        } else {
          posts.add(options);
          if (posts.length == 1) {
            proposal = true;
          } else {
            await acceptReply.future;
            pending = true;
          }
          handler.resolve(
            Response(
              requestOptions: options,
              data: _reportReceipt(
                posts.length == 1 ? 'REPORT_PROPOSE' : 'REPORT_RESPOND',
              ),
            ),
          );
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('현재 초안 제안'));
      expect(find.text('공동 방법'), findsOneWidget);
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      expect(find.textContaining('저장하면 기존 고정 제안은 무효화'), findsOneWidget);
      await _tap(tester, '현재 초안 제안');
      await _show(tester, find.text('제안 철회'));
      expect(posts.single.uri.path, '/api/playtests/test-1/report/proposals');
      expect((posts.single.data as Map)['expectedDraftRev'], '4');
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      role = 'R2';
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, find.text('제안 수락 및 판정 접수'));
      await _tap(tester, '제안 수락 및 판정 접수');
      await _until(tester, () => posts.length == 2);
      expect(
        posts[1].uri.path,
        '/api/playtests/test-1/report/proposals/report-1/respond',
      );
      expect((posts[1].data as Map)['decision'], 'ACCEPT');
      expect((posts[1].data as Map).containsKey('report'), isFalse);
      expect(find.text('현재 초안 제안'), findsNothing);
      acceptReply.complete();
      await _show(tester, find.text('제출 상태: PENDING'));
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      expect(find.textContaining('최종 점수'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'five-field PATCH retains draft on unknown outcome and replays the exact CAS body',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final patches = <RequestOptions>[];
      var draftRev = '4';
      var roomRev = '7';
      var proposal = true;
      var serverReport = Map<String, dynamic>.from(
        _sharedReport()['report'] as Map,
      );
      _intercept(api, (options, handler) {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'GET' &&
            options.uri.path.endsWith('/materials')) {
          handler.resolve(
            Response(requestOptions: options, data: _materials()),
          );
        } else if (options.method == 'GET' &&
            options.uri.path.endsWith('/report')) {
          handler.resolve(
            Response(
              requestOptions: options,
              data: {
                ..._sharedReport(proposal: proposal),
                'draftRev': draftRev,
                'report': serverReport,
              },
            ),
          );
        } else if (options.method == 'GET') {
          handler.resolve(
            Response(
              requestOptions: options,
              data: {
                ..._runningState(),
                'rev': roomRev,
                'draftRev': draftRev,
                'submissionState': proposal ? 'PROPOSED' : 'NONE',
              },
            ),
          );
        } else if (options.method == 'PATCH') {
          patches.add(options);
          if (patches.length == 1) {
            handler.reject(
              DioException(
                requestOptions: options,
                type: DioExceptionType.connectionError,
              ),
            );
          } else {
            serverReport = Map<String, dynamic>.from(
              (options.data as Map)['report'] as Map,
            );
            roomRev = '8';
            draftRev = '5';
            proposal = false;
            handler.resolve(
              Response(
                requestOptions: options,
                data: _reportReceipt('REPORT_EDIT'),
              ),
            );
          }
        } else {
          handler.reject(DioException(requestOptions: options));
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('제안 철회'));
      expect(find.text('현재 초안 제안'), findsNothing);
      final culprit = find.byType(DropdownButtonFormField<String>);
      await _show(tester, culprit);
      expect(culprit.hitTestable(), findsOneWidget);
      await tester.tap(culprit);
      await tester.pump(const Duration(milliseconds: 300));
      // The dropdown menu owns a separate overlay viewport, not the page list.
      final unselected = find.text('미선택');
      expect(unselected, findsOneWidget);
      expect(unselected.hitTestable(), findsOneWidget);
      await tester.tap(unselected);
      await tester.pump(const Duration(milliseconds: 300));
      await tester.enterText(find.widgetWithText(TextFormField, '방법'), '새 방법');
      await tester.enterText(find.widgetWithText(TextFormField, '시간'), '새 시간');
      await tester.enterText(find.widgetWithText(TextFormField, '동기'), '새 동기');
      await tester.enterText(find.widgetWithText(TextFormField, '근거'), '새 근거');
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      await _tap(tester, '공동 초안 저장');
      await _show(tester, find.text('원본 요청 다시 보내기'));
      await _show(tester, find.text('새 방법'));
      expect(patches, hasLength(1));
      final body = patches.single.data as Map;
      expect(
        body.keys,
        unorderedEquals([
          'expectedRev',
          'expectedDraftRev',
          'report',
          'requestKey',
        ]),
      );
      expect(body['expectedRev'], '7');
      expect(body['expectedDraftRev'], '4');
      expect(body['report'], {
        'culpritCode': null,
        'method': '새 방법',
        'time': '새 시간',
        'motive': '새 동기',
        'evidence': '새 근거',
      });
      expect(find.text('새 방법'), findsOneWidget);
      await _tap(tester, '원본 요청 다시 보내기');
      await _until(tester, () => patches.length == 2);
      await _show(tester, find.text('현재 초안 제안'));
      expect(patches.last.method, 'PATCH');
      expect(patches.last.data, body);
      expect(find.text('공동 초안 저장'), findsNothing);
      expect(find.text('제안 철회'), findsNothing);
      expect(find.text('새 방법'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'concurrent draft revision prevents resubmission until explicit discard',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      var draftRev = '4';
      var writes = 0;
      _intercept(api, (options, handler) {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.uri.path.endsWith('/materials')) {
          handler.resolve(
            Response(requestOptions: options, data: _materials()),
          );
        } else if (options.uri.path.endsWith('/report')) {
          handler.resolve(
            Response(
              requestOptions: options,
              data: {..._sharedReport(), 'draftRev': draftRev},
            ),
          );
        } else if (options.method == 'GET') {
          handler.resolve(
            Response(
              requestOptions: options,
              data: {..._runningState(), 'draftRev': draftRev},
            ),
          );
        } else {
          writes++;
          handler.reject(DioException(requestOptions: options));
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('현재 초안 제안'));
      await tester.enterText(
        find.widgetWithText(TextFormField, '방법'),
        '보존할 입력',
      );
      draftRev = '5';
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, find.textContaining('다른 수정으로 서버 초안이 변경'));
      expect(find.text('보존할 입력'), findsOneWidget);
      expect(
        tester
            .widget<PlayerButton>(find.widgetWithText(PlayerButton, '공동 초안 저장'))
            .onPressed,
        isNull,
      );
      expect(writes, 0);
      await _tap(tester, '서버 초안으로 되돌리기');
      expect(find.text('보존할 입력'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'unknown proposal retains exact request after refresh, notes and 409',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final posts = <RequestOptions>[];
      var rev = '7';
      _intercept(api, (options, handler) {
        final path = options.uri.path;
        if (path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'GET' && path.endsWith('/materials')) {
          handler.resolve(
            Response(requestOptions: options, data: _materials()),
          );
        } else if (options.method == 'GET' && path.endsWith('/report')) {
          handler.resolve(
            Response(requestOptions: options, data: _sharedReport()),
          );
        } else if (options.method == 'GET') {
          final state = _runningState();
          state['rev'] = rev;
          state['draftRev'] = '4';
          handler.resolve(Response(requestOptions: options, data: state));
        } else {
          posts.add(options);
          if (posts.length == 1) {
            handler.reject(
              DioException(
                requestOptions: options,
                type: DioExceptionType.connectionError,
              ),
            );
          } else {
            handler.reject(
              DioException(
                requestOptions: options,
                type: DioExceptionType.badResponse,
                response: Response(requestOptions: options, statusCode: 409),
              ),
            );
          }
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('현재 초안 제안'));
      await _show(tester, _notesField);
      await tester.enterText(_notesField, 'role-private-note');
      await _tap(tester, '현재 초안 제안');
      await _show(tester, find.text('원본 요청 다시 보내기'));
      final content = find.byKey(const ValueKey('investigation-content'));
      final contentElement = tester.element(content);
      rev = '9';
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, find.text('공동 방법'));
      await _show(tester, _notesField);
      expect(tester.element(content), same(contentElement));
      expect(tester.takeException(), isNull);
      expect(
        tester.widget<TextField>(_notesField).controller!.text,
        'role-private-note',
      );
      expect(find.text('현재 초안 제안'), findsOneWidget);
      await _tap(tester, '현재 초안 제안');
      expect(posts, hasLength(1));
      await _tap(tester, '원본 요청 다시 보내기');
      await _until(tester, () => posts.length == 2);
      expect(posts[1].uri.path, posts[0].uri.path);
      expect(posts[1].data, posts[0].data);
      expect((posts[1].data as Map)['expectedRev'], '7');
      expect((posts[1].data as Map)['expectedDraftRev'], '4');
      await _show(tester, find.text('원본 요청 다시 보내기'));
      await _show(tester, _notesField);
      expect(tester.element(content), same(contentElement));
      expect(tester.takeException(), isNull);
      expect(
        tester.widget<TextField>(_notesField).controller!.text,
        'role-private-note',
      );
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'feedback is personal and truth appears only after a new result GET',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final posts = <RequestOptions>[];
      final resultReply = Completer<void>();
      var resultReads = 0;
      _intercept(api, (options, handler) async {
        if (options.method == 'GET' && options.uri.path.endsWith('/result')) {
          resultReads++;
          if (resultReads > 1) await resultReply.future;
          handler.resolve(
            Response(
              requestOptions: options,
              data: resultReads == 1 ? _endedResult() : _availableResult(),
            ),
          );
        } else if (options.method == 'GET') {
          handler.resolve(
            Response(requestOptions: options, data: _endedState()),
          );
        } else {
          posts.add(options);
          handler.resolve(
            Response(
              requestOptions: options,
              data: _reportReceipt('TEST_FEEDBACK', state: 'ENDED'),
            ),
          );
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('본인 피드백 제출'));
      expect(find.textContaining('최종 점수'), findsNothing);
      expect(find.text('SECRET_SENTINEL'), findsNothing);
      await tester.enterText(find.widgetWithText(TextField, '막힌 지점'), '내 막힘');
      await _tap(tester, '본인 피드백 제출');
      await _until(tester, () => posts.length == 1 && resultReads > 1);
      expect(posts.single.uri.path, '/api/playtests/test-1/feedback');
      expect((posts.single.data as Map)['feedback']['blockedAt'], '내 막힘');
      expect(find.textContaining('최종 점수'), findsNothing);
      expect(find.text('SECRET_SENTINEL'), findsNothing);
      resultReply.complete();
      await _show(tester, find.text('최종 점수: 82'));
      expect(find.text('SECRET_SENTINEL'), findsNothing);
      await _tap(tester, '제출본과 해설 열기');
      expect(find.text('SECRET_SENTINEL'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'transient report loss hides consent and notes; revocation purges role notes',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      var offline = false;
      var revoked = false;
      var malformed = false;
      var posts = 0;
      _intercept(api, (options, handler) {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'POST') {
          posts++;
          handler.reject(DioException(requestOptions: options));
        } else if (options.uri.path.endsWith('/report') &&
            (offline || revoked)) {
          handler.reject(
            DioException(
              requestOptions: options,
              type: revoked
                  ? DioExceptionType.badResponse
                  : DioExceptionType.connectionError,
              response: revoked
                  ? Response(requestOptions: options, statusCode: 403)
                  : null,
            ),
          );
        } else if (options.uri.path.endsWith('/materials')) {
          final materials = _materials();
          (materials['role'] as Map)['code'] = 'R2';
          handler.resolve(Response(requestOptions: options, data: materials));
        } else if (options.uri.path.endsWith('/report')) {
          handler.resolve(
            Response(
              requestOptions: options,
              data: malformed
                  ? <String, dynamic>{}
                  : _sharedReport(proposal: true, role: 'R2'),
            ),
          );
        } else {
          final state = _runningState(role: 'R2');
          state['draftRev'] = '4';
          state['submissionState'] = 'PROPOSED';
          handler.resolve(Response(requestOptions: options, data: state));
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('제안 수락 및 판정 접수'));
      await _show(tester, _notesField);
      await tester.enterText(_notesField, 'private-note');
      offline = true;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(
        tester,
        () => find.textContaining('연결이 복구되면').evaluate().isNotEmpty,
      );
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      expect(find.byType(TextField), findsNothing);
      expect(posts, 0);
      offline = false;
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, find.text('제안 수락 및 판정 접수'));
      await _show(tester, _notesField);
      expect(
        tester.widget<TextField>(_notesField).controller!.text,
        'private-note',
      );
      malformed = true;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(
        tester,
        () => find.textContaining('연결이 복구되면').evaluate().isNotEmpty,
      );
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      expect(find.byType(TextField), findsNothing);
      expect(posts, 0);
      malformed = false;
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, find.text('제안 수락 및 판정 접수'));
      await _show(tester, _notesField);
      expect(
        tester.widget<TextField>(_notesField).controller!.text,
        'private-note',
      );
      revoked = true;
      await _tap(tester, '서버 상태 다시 조회');
      await _until(
        tester,
        () => find.textContaining('연결이 복구되면').evaluate().isNotEmpty,
      );
      revoked = false;
      await _tap(tester, '서버 상태 다시 조회');
      await _show(tester, find.text('제안 수락 및 판정 접수'));
      await _show(tester, _notesField);
      expect(tester.widget<TextField>(_notesField).controller!.text, isEmpty);
      expect(posts, 0);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'background report flight and timer cannot restore stale consent',
    (tester) async {
      final api = PlayerApi('https://example.invalid');
      final lateReport = Completer<void>();
      var reportReads = 0;
      var stateReads = 0;
      var posts = 0;
      _intercept(api, (options, handler) async {
        if (options.uri.path.endsWith('/heartbeat')) {
          handler.resolve(Response(requestOptions: options, statusCode: 204));
        } else if (options.method == 'POST') {
          posts++;
          handler.reject(DioException(requestOptions: options));
        } else if (options.uri.path.endsWith('/materials')) {
          handler.resolve(
            Response(requestOptions: options, data: _materials()),
          );
        } else if (options.uri.path.endsWith('/report')) {
          reportReads++;
          if (reportReads == 2) await lateReport.future;
          handler.resolve(
            Response(
              requestOptions: options,
              data: _sharedReport(proposal: reportReads == 2, role: 'R2'),
            ),
          );
        } else {
          stateReads++;
          final state = _runningState();
          state['draftRev'] = '4';
          state['submissionState'] = stateReads == 2 ? 'PROPOSED' : 'NONE';
          handler.resolve(Response(requestOptions: options, data: state));
        }
      });
      final (container, _) = (await tester.runAsync(() => _signedIn(api)))!;
      await _mount(tester, container);
      await _show(tester, find.text('현재 초안 제안'));
      await _tap(tester, '서버 상태 다시 조회');
      await _until(tester, () => reportReads == 2);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump(const Duration(seconds: 16));
      expect(stateReads, 2);
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      lateReport.complete();
      await tester.pump();
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await _until(tester, () => stateReads > 2);
      await _show(tester, find.text('현재 초안 제안'));
      expect(find.text('제안 수락 및 판정 접수'), findsNothing);
      expect(posts, 0);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}

Map<String, dynamic> _sharedReport({
  bool proposal = false,
  bool pending = false,
  String role = 'R1',
}) => {
  'testKey': 'test-1',
  'draftRev': '4',
  'requestId': 'request-1',
  'report': {
    'culpritCode': 'P1',
    'method': '공동 방법',
    'time': '정오',
    'motive': '동기',
    'evidence': '근거',
  },
  'submissionState': pending
      ? 'PENDING'
      : proposal
      ? 'PROPOSED'
      : 'NONE',
  'proposal': proposal || pending
      ? _proposal(
          canAccept: !pending && role == 'R2',
          canWithdraw: !pending && role == 'R1',
          state: pending ? 'ACCEPTED' : 'PROPOSED',
        )
      : null,
};

Map<String, dynamic> _proposal({
  bool canAccept = false,
  bool canWithdraw = false,
  String state = 'PROPOSED',
}) => {
  'reportKey': 'report-1',
  'sourceRev': '4',
  'proposerSlot': 1,
  'state': state,
  'canAccept': canAccept,
  'canReject': canAccept,
  'canWithdraw': canWithdraw,
};

Map<String, dynamic> _reportReceipt(String action, {String state = 'RUNNING'}) {
  final safe = {
    'testKey': 'test-1',
    'rev': '8',
    'state': state,
    'outcome': null,
    'startedAt': state == 'RUNNING' ? '2026-10-09T00:00:00Z' : null,
    'playDeadline': state == 'RUNNING' ? '2030-01-01T00:00:00Z' : null,
  };
  return {
    'action': action,
    'changed': true,
    'replayed': false,
    'original': safe,
    'current': safe,
    'requestId': 'request-1',
  };
}

Map<String, dynamic> _endedState() => {
  ..._waitingState(),
  'state': 'ENDED',
  'rev': '10',
  'outcome': 'SUCCESS',
  'startedAt': '2026-10-09T00:00:00Z',
  'playDeadline': '2030-01-01T00:00:00Z',
  'feedbackUntil': '2030-01-02T00:00:00Z',
  'resultPhase': 'AWAITING_FEEDBACK',
};

Map<String, dynamic> _endedResult() => {
  'testKey': 'test-1',
  'state': 'ENDED',
  'outcome': 'SUCCESS',
  'endedAt': '2026-10-09T00:30:00Z',
  'feedbackUntil': '2030-01-02T00:00:00Z',
  'resultPhase': 'AWAITING_FEEDBACK',
  'feedbackSubmitted': false,
  'finalScore': null,
  'scoreSummary': null,
  'reports': null,
  'revealText': null,
  'requestId': 'request-1',
};

Map<String, dynamic> _availableResult() => {
  ..._endedResult(),
  'resultPhase': 'AVAILABLE',
  'feedbackSubmitted': true,
  'finalScore': 82,
  'scoreSummary': {
    'baseScore': 82,
    'wrongCount': 0,
    'penalty': 0,
    'finalScore': 82,
    'categories': <Map<String, dynamic>>[],
  },
  'reports': [
    {
      'reportKey': 'report-1',
      'submitNo': 1,
      'report': _sharedReport()['report'],
      'baseScore': 82,
      'success': true,
    },
  ],
  'revealText': 'SECRET_SENTINEL',
};
