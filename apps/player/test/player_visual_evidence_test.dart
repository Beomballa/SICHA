import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sicha_player/auth/auth_controller.dart';
import 'package:sicha_player/core/player_api.dart';
import 'package:sicha_player/core/player_theme.dart';
import 'package:sicha_player/investigation/investigation.dart';
import 'package:sicha_player/investigation/report_sheet.dart';
import 'package:sicha_player/investigation/result_sheet.dart';

// DTO shapes copied from the read-only investigation_test.dart sample helpers.
// Do not import that file: its main registers unrelated tests.
// This is a synthetic widget transport, NOT backend, TLS, native, member,
// publication, or two-human consent evidence. No production source is replaced.
final _root = Directory.current.absolute.parent.parent.path;
const _output = 'outputs/player-reference-assessment-20261008';
const _boundaryKey = ValueKey('synthetic-player-capture');
const _cases = <String>[
  'investigation-role-materials',
  'shared-report-draft',
  'fixed-partner-proposal',
  'unknown-report-buffer-hidden',
  'unknown-report-exact-replay',
  'missing-report-fail-closed',
  'ended-awaiting-feedback',
  'own-available-truth-closed',
  'own-available-truth-open',
];

class _MemoryStorage implements AuthTokenStorage {
  final _values = <String, String>{};
  @override
  Future<String?> read(String key) async => _values[key];
  @override
  Future<void> write(String key, String value) async {
    _values[key] = value;
  }

  @override
  Future<void> delete(String key) async {
    _values.remove(key);
  }
}

Map<String, dynamic> _state(String scenario) {
  final ended = scenario.startsWith('ended-') || scenario.startsWith('own-');
  final partner = scenario == 'fixed-partner-proposal';
  return {
    'testKey': 'test-1',
    'title': 'SYNTHETIC · 공개 위젯 조사',
    'mode': 'FUNCTIONAL',
    'state': ended ? 'ENDED' : 'RUNNING',
    'outcome': ended ? 'SUCCESS' : null,
    'rev': ended ? '10' : '7',
    'draftRev': '4',
    'snapshotRef': '42',
    'runtimeConfigId': 'RUNTIME',
    'self': {
      'slot': partner ? 2 : 1,
      'inviteGen': 3,
      'accepted': true,
      'ready': false,
      'roleCode': ended
          ? null
          : partner
          ? 'R2'
          : 'R1',
      'blindStatus': false,
    },
    'partner': {'accepted': true, 'ready': false, 'online': false},
    'inviteExpiresAt': '2030-01-01T00:00:00Z',
    'lobbyExpiresAt': null,
    'startedAt': '2026-10-09T00:00:00Z',
    'playDeadline': '2030-01-01T00:00:00Z',
    'serverTime': '2029-12-31T23:30:00Z',
    'submissionState': partner ? 'PROPOSED' : 'NONE',
    'attemptsRemaining': null,
    'hintsRemaining': 2,
    'feedbackUntil': ended ? '2030-01-02T00:00:00Z' : null,
    'feedbackSubmitted': scenario.startsWith('own-'),
    'resultPhase': ended
        ? scenario.startsWith('own-')
              ? 'AVAILABLE'
              : 'AWAITING_FEEDBACK'
        : 'NONE',
    'requestId': 'synthetic-request',
  };
}

Map<String, dynamic> _materials(String scenario) => {
  'basic': {
    'title': 'SYNTHETIC · 공개 사건',
    'intro': 'SYNTHETIC 예시 자료 · 실제 사건이나 참가자가 아닙니다.',
    'setting': 'SYNTHETIC 공개 장소',
    'difficulty': 2,
    'estMin': 20,
    'estMax': 30,
    'limitSec': 1800,
  },
  'role': {
    'code': scenario == 'fixed-partner-proposal' ? 'R2' : 'R1',
    'name': 'SYNTHETIC 탐정',
    'brief': 'SYNTHETIC 배정 역할의 공개 임무',
  },
  'persons': [
    {'code': 'P1', 'name': 'SYNTHETIC 증인', 'publicText': '공개 증언 예시'},
  ],
  'clues': [
    {
      'code': 'C1',
      'title': 'SYNTHETIC 메모',
      'body': '공개 본문 예시',
      'personCode': null,
    },
  ],
  'openedHints': [
    {'level': 1, 'body': 'SYNTHETIC 열린 힌트'},
  ],
  'requiredNotices': [
    {'rubricCode': 'EVIDENCE', 'text': 'SYNTHETIC 필수 고지'},
  ],
  'requestId': 'synthetic-request',
};

Map<String, dynamic> _report(String scenario) => {
  'testKey': 'test-1',
  'draftRev': '4',
  'requestId': 'synthetic-request',
  'report': {
    'culpritCode': 'P1',
    'method': 'SYNTHETIC 공동 방법',
    'time': 'SYNTHETIC 정오',
    'motive': 'SYNTHETIC 동기',
    'evidence': 'SYNTHETIC 공개 근거',
  },
  'submissionState': scenario == 'fixed-partner-proposal' ? 'PROPOSED' : 'NONE',
  'proposal': scenario == 'fixed-partner-proposal'
      ? {
          'reportKey': 'report-1',
          'sourceRev': '4',
          'proposerSlot': 1,
          'state': 'PROPOSED',
          'canAccept': true,
          'canReject': true,
          'canWithdraw': false,
        }
      : null,
};

Map<String, dynamic> _result(String scenario) {
  final available = scenario.startsWith('own-');
  return {
    'testKey': 'test-1',
    'state': 'ENDED',
    'outcome': 'SUCCESS',
    'endedAt': '2026-10-09T00:30:00Z',
    'feedbackUntil': '2030-01-02T00:00:00Z',
    'resultPhase': available ? 'AVAILABLE' : 'AWAITING_FEEDBACK',
    'feedbackSubmitted': available,
    'finalScore': available ? 82 : null,
    'scoreSummary': available
        ? {
            'baseScore': 82,
            'wrongCount': 0,
            'penalty': 0,
            'finalScore': 82,
            'categories': <Map<String, dynamic>>[],
          }
        : null,
    'reports': available
        ? [
            {
              'reportKey': 'report-1',
              'submitNo': 1,
              'report': _report(scenario)['report'],
              'baseScore': 82,
              'success': true,
            },
          ]
        : null,
    'revealText': available ? 'SYNTHETIC 해설 · 실제 정답이 아닌 공개 예시입니다.' : null,
    'requestId': 'synthetic-request',
  };
}

class _SyntheticAdapter implements HttpClientAdapter {
  _SyntheticAdapter(this.scenario);
  final String scenario;
  final mutations = <({String path, String body})>[];
  bool hideReads = false;
  int loginCount = 0;
  int meCount = 0;
  int stateReadCount = 0;

  ResponseBody _json(Map<String, dynamic> body, [int status = 200]) =>
      ResponseBody.fromString(
        jsonEncode(body),
        status,
        headers: {
          Headers.contentTypeHeader: ['application/json'],
        },
      );

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.uri.path;
    if (path == '/api/member/auth/login/local') {
      loginCount++;
      return _json({
        'tokenType': 'Bearer',
        'accessToken': 'synthetic-memory-access',
        'refreshToken': 'synthetic-memory-refresh',
        'accessExpiresAt': '2099-01-01T00:00:00Z',
      });
    }
    if (path == '/api/member/auth/me') {
      meCount++;
      return _json({'nickname': 'SYNTHETIC widget participant'});
    }
    // Only record command path/body. Never persist auth headers or login inputs.
    if (options.method == 'POST' && path.endsWith('/report/proposals')) {
      mutations.add((path: path, body: jsonEncode(options.data)));
      if (mutations.length == 1) {
        hideReads = true;
        throw DioException(
          requestOptions: options,
          type: DioExceptionType.connectionError,
        );
      }
      return _json({'code': 'REV_CONFLICT'}, 409);
    }
    if (path.endsWith('/heartbeat')) return ResponseBody.fromString('', 204);
    if (options.method != 'GET') {
      throw StateError('Unexpected synthetic mutation route');
    }
    if (hideReads) return _json({'code': 'SYNTHETIC_UNAVAILABLE'}, 503);
    if (path == '/api/playtests/test-1') {
      stateReadCount++;
      return _json(_state(scenario));
    }
    if (path.endsWith('/materials')) return _json(_materials(scenario));
    if (path.endsWith('/report')) {
      return _json(
        scenario == 'missing-report-fail-closed' ? {} : _report(scenario),
      );
    }
    if (path.endsWith('/result')) return _json(_result(scenario));
    throw StateError('Unexpected synthetic GET route');
  }

  @override
  void close({bool force = false}) {}
}

Future<void> _pumpUntil(WidgetTester tester, bool Function() ready) async {
  for (var i = 0; i < 60 && !ready(); i++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 2)),
    );
    await tester.pump(const Duration(milliseconds: 10));
    expect(tester.takeException(), isNull);
  }
  expect(ready(), isTrue);
}

Future<void> _show(WidgetTester tester, Finder target) async {
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
  expect(target, findsOneWidget);
  await tester
      .state<ScrollableState>(find.byType(Scrollable).first)
      .position
      .ensureVisible(tester.renderObject(target), alignment: 0.1);
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
  expect(tester.takeException(), isNull);
}

Future<void> _tap(WidgetTester tester, String label) async {
  final target = find.text(label);
  await _show(tester, target);
  expect(target.hitTestable(), findsOneWidget);
  await tester.tap(target);
  await tester.pump();
  expect(tester.takeException(), isNull);
}

Future<Map<String, dynamic>> _capture(
  WidgetTester tester,
  String scenario,
  int width,
) async {
  await tester.pump();
  expect(tester.takeException(), isNull);
  final buttons = <Map<String, dynamic>>[];
  for (final type in [ElevatedButton, OutlinedButton]) {
    for (final element in find.byType(type).hitTestable().evaluate()) {
      final finder = find.byElementPredicate(
        (candidate) => identical(candidate, element),
      );
      final size = tester.getSize(finder);
      expect(size.width, greaterThanOrEqualTo(48));
      expect(size.height, greaterThanOrEqualTo(48));
      final button = element.widget as ButtonStyleButton;
      buttons.add({
        'label': button.child is Text ? (button.child as Text).data : null,
        'width': size.width,
        'height': size.height,
        'enabled': button.onPressed != null,
      });
    }
  }
  final path = '$_output/player-widget-rendering/$scenario-$width.png';
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(_boundaryKey),
  );
  expect(boundary.debugNeedsPaint, isFalse);
  await tester.runAsync(() async {
    final image = await boundary.toImage(pixelRatio: 1);
    try {
      expect(image.width, width);
      expect(image.height, 900);
      final data = await image.toByteData(format: ui.ImageByteFormat.png);
      expect(data, isNotNull);
      final file = File('$_root/$path');
      await file.parent.create(recursive: true);
      await file.writeAsBytes(
        data!.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
        flush: true,
      );
    } finally {
      image.dispose();
    }
  });
  expect(tester.takeException(), isNull);
  return {
    'state': scenario,
    'width': width,
    'height': 900,
    'pixelRatio': 1,
    'path': path,
    'renderMethod': 'RepaintBoundary.toImage/png',
    'layoutException': null,
    'measuredViewportButtons': buttons,
    'syntheticWidgetTransport': true,
  };
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('actual player widgets: synthetic responsive and error captures', (
    tester,
  ) async {
    // Invalidate any prior executed receipt before this attempt, so a failed
    // rerun cannot leave an old success index attributed to new PNGs.
    await tester.runAsync(() async {
      await Directory('$_root/$_output').create(recursive: true);
      await File('$_root/$_output/player-widget-capture-index.json')
          .writeAsString(
            '${const JsonEncoder.withIndent('  ').convert({
              'schemaVersion': 1,
              'status': 'execution-in-progress-not-passed',
              'source': 'apps/player/test/player_visual_evidence_test.dart',
              'declaredStates': _cases,
              'logicalWidths': [360, 768, 1440],
              'logicalHeight': 900,
              'captures': <Map<String, dynamic>>[],
            })}\n',
            flush: true,
          );
    });
    final font = FontLoader('SUIT')
      ..addFont(rootBundle.load('assets/fonts/SUIT-Variable.ttf'));
    await tester.runAsync(font.load);
    // Load the actual SDK font bundled by uses-material-design, not a
    // substituted family or drawn stand-in for the production icon glyphs.
    final materialIcons = FontLoader('MaterialIcons')
      ..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
    await tester.runAsync(materialIcons.load);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final captures = <Map<String, dynamic>>[];
    // A single test writes an executed index only after every state/width passes.
    for (final width in [360, 768, 1440]) {
      for (final scenario in _cases) {
        tester.view.physicalSize = Size(width.toDouble(), 900);
        final adapter = _SyntheticAdapter(scenario);
        final api = PlayerApi('https://synthetic.example.invalid');
        api.dio.httpClientAdapter = adapter;
        final container = ProviderContainer(
          overrides: [
            apiProvider.overrideWith((ref) => api),
            authProvider.overrideWith(
              () => AuthController(storage: _MemoryStorage()),
            ),
          ],
        );
        try {
          final auth = container.read(authProvider.notifier);
          await tester.runAsync(
            () =>
                auth.login('synthetic@example.invalid', 'synthetic-only-input'),
          );
          expect(container.read(authProvider).phase, AuthPhase.signedIn);
          expect(auth.generation, greaterThan(0));
          expect(adapter.loginCount, 1);
          expect(adapter.meCount, 1);
          await tester.pumpWidget(
            UncontrolledProviderScope(
              container: container,
              child: RepaintBoundary(
                key: _boundaryKey,
                child: MaterialApp(
                  theme: PlayerTheme.data,
                  home: const InvestigationPage(testKey: 'test-1'),
                  builder: (context, child) => Column(
                    children: [
                      Material(
                        color: PlayerTheme.accent,
                        child: const SizedBox(
                          height: 48,
                          width: double.infinity,
                          child: Center(
                            child: Text(
                              'SYNTHETIC · WIDGET TRANSPORT ONLY',
                              textAlign: TextAlign.center,
                              style: TextStyle(
                                color: PlayerTheme.background,
                                fontSize: 12,
                              ),
                            ),
                          ),
                        ),
                      ),
                      Expanded(child: child!),
                    ],
                  ),
                ),
              ),
            ),
          );
          if (scenario == 'missing-report-fail-closed') {
            await _pumpUntil(
              tester,
              () => find.textContaining('다시 조회해 주세요').evaluate().isNotEmpty,
            );
            expect(find.byType(ReportSheet), findsNothing);
            expect(find.text('SYNTHETIC 공동 방법'), findsNothing);
          } else if (scenario.startsWith('ended-') ||
              scenario.startsWith('own-')) {
            await _pumpUntil(
              tester,
              () => find.byType(ResultSheet).evaluate().isNotEmpty,
            );
            expect(find.byType(ReportSheet), findsNothing);
            if (scenario == 'ended-awaiting-feedback') {
              expect(find.textContaining('최종 점수:'), findsNothing);
              expect(find.text('제출본과 해설 열기'), findsNothing);
              await _show(tester, find.text('조사 종료: SUCCESS'));
            } else {
              expect(find.text('최종 점수: 82'), findsOneWidget);
              expect(find.text('본인 피드백 제출'), findsNothing);
              expect(find.textContaining('SYNTHETIC 해설'), findsNothing);
              if (scenario.endsWith('-open')) {
                await _tap(tester, '제출본과 해설 열기');
                expect(find.textContaining('SYNTHETIC 해설'), findsOneWidget);
              }
              await _show(tester, find.text('조사 종료: SUCCESS'));
            }
          } else {
            await _pumpUntil(
              tester,
              () => find.byType(ReportSheet).evaluate().isNotEmpty,
            );
            if (scenario == 'investigation-role-materials') {
              await _show(tester, find.text('SYNTHETIC 탐정'));
            } else if (scenario == 'shared-report-draft') {
              await _show(tester, find.text('공동 보고서'));
            } else if (scenario == 'fixed-partner-proposal') {
              expect(find.text('제안 수락 및 판정 접수'), findsOneWidget);
              expect(adapter.mutations, isEmpty);
              await _show(tester, find.textContaining('고정 제안:'));
            } else {
              final notes = find.widgetWithText(TextField, '개인 조사 기록 (임시 메모)');
              await _show(tester, notes);
              await tester.enterText(notes, 'SYNTHETIC preserved buffer');
              await _tap(tester, '현재 초안 제안');
              await _pumpUntil(
                tester,
                () =>
                    adapter.mutations.length == 1 &&
                    find.byType(ReportSheet).evaluate().isEmpty,
              );
              await _pumpUntil(
                tester,
                () => find.textContaining('다시 조회해 주세요').evaluate().isNotEmpty,
              );
              expect(find.byType(ReportSheet), findsNothing);
              expect(find.text('SYNTHETIC preserved buffer'), findsNothing);
              expect(
                find.widgetWithText(TextField, '개인 조사 기록 (임시 메모)'),
                findsNothing,
              );
              expect(find.text('원본 요청 다시 보내기'), findsOneWidget);
              expect(adapter.mutations, hasLength(1));
              if (scenario == 'unknown-report-exact-replay') {
                adapter.hideReads = false;
                await _tap(tester, '서버 상태 다시 조회');
                await _pumpUntil(
                  tester,
                  () => find.byType(ReportSheet).evaluate().isNotEmpty,
                );
                expect(
                  tester.widget<TextField>(notes).controller!.text,
                  'SYNTHETIC preserved buffer',
                );
                await _tap(tester, '원본 요청 다시 보내기');
                await _pumpUntil(
                  tester,
                  () =>
                      adapter.mutations.length == 2 &&
                      adapter.stateReadCount >= 3 &&
                      find.byType(ReportSheet).evaluate().isNotEmpty,
                );
                expect(adapter.mutations[1], adapter.mutations[0]);
                final body = jsonDecode(adapter.mutations.first.body) as Map;
                expect(body['expectedRev'], '7');
                expect(body['expectedDraftRev'], '4');
                expect(body['requestKey'], isA<String>());
                await _show(tester, find.text('원본 요청 다시 보내기'));
              }
            }
          }
          captures.add(await _capture(tester, scenario, width));
          expect(
            adapter.mutations.length,
            scenario == 'unknown-report-exact-replay'
                ? 2
                : scenario == 'unknown-report-buffer-hidden'
                ? 1
                : 0,
          );
        } finally {
          await tester.pumpWidget(const SizedBox.shrink());
          expect(tester.takeException(), isNull);
          container.dispose();
          api.dio.close(force: true);
        }
      }
    }
    expect(captures, hasLength(_cases.length * 3));
    await tester.runAsync(() async {
      await File(
        '$_root/$_output/player-widget-capture-index.json',
      ).writeAsString(
        '${const JsonEncoder.withIndent('  ').convert({
          'schemaVersion': 1,
          'status': 'executed-all-source-cases',
          'evidenceScope': 'Actual existing Flutter widgets; synthetic in-memory auth and Dio HttpClientAdapter only.',
          'notEvidenceOf': ['backend', 'member TLS', 'native device', 'two human participants', 'publication', 'golden comparison'],
          'referenceSha256': '9c58af488a4d9d3cefa71af78f7ca1e12706afd9096204c9ea9af78913e4e4fe',
          'source': 'apps/player/test/player_visual_evidence_test.dart',
          'captures': captures,
        })}\n',
        flush: true,
      );
    });
  }, timeout: const Timeout(Duration(minutes: 5)));
}
