import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../auth/auth_controller.dart';
import '../core/player_api.dart';
import '../core/player_theme.dart';
import '../lobby/invitations.dart';
import 'report_sheet.dart';
import 'result_sheet.dart';

/// 허용된 자료 필드만 검증하며 알 수 없는 필드는 화면에 전달하지 않는다.
Map<String, dynamic> _fields(Object? value, Set<String> names) {
  if (value is! Map<String, dynamic> ||
      value.keys.any((key) => !names.contains(key))) {
    throw const FormatException('역할 자료 형식이 올바르지 않습니다.');
  }
  return value;
}

/// 필수 문자열의 존재와 타입을 확인한다.
String _text(Map<String, dynamic> item, String key) {
  final value = item[key];
  if (value is! String) throw const FormatException('역할 자료 형식이 올바르지 않습니다.');
  return value;
}

/// 비어 있으면 안 되는 공개 식별자와 고지 본문을 검증한다.
String _nonblank(Map<String, dynamic> item, String key) {
  final value = _text(item, key);
  if (value.trim().isEmpty) throw const FormatException('역할 자료 형식이 올바르지 않습니다.');
  return value;
}

/// 필드 생략은 거부하고 명시적 null 또는 문자열만 허용한다.
void _nullableText(Map<String, dynamic> item, String key) {
  if (!item.containsKey(key) || (item[key] != null && item[key] is! String)) {
    throw const FormatException('역할 자료 형식이 올바르지 않습니다.');
  }
}

/// 필드 생략은 거부하고 명시적 null 또는 정수만 허용한다.
void _nullableInt(Map<String, dynamic> item, String key) {
  if (!item.containsKey(key) || (item[key] != null && item[key] is! int)) {
    throw const FormatException('역할 자료 형식이 올바르지 않습니다.');
  }
}

/// 역할별 원고의 허용 필드를 구문 검사한 뒤 구조화된 자료로 돌려준다.
class InvestigationMaterials {
  InvestigationMaterials(Map<String, dynamic> json) {
    _fields(json, const {
      'basic',
      'role',
      'persons',
      'clues',
      'openedHints',
      'requiredNotices',
      'requestId',
    });
    basic = _fields(json['basic'], const {
      'title',
      'intro',
      'setting',
      'difficulty',
      'estMin',
      'estMax',
      'limitSec',
    });
    role = _fields(json['role'], const {'code', 'name', 'brief'});
    _nonblank(basic, 'title');
    _nullableText(basic, 'intro');
    _nullableText(basic, 'setting');
    _nonblank(role, 'code');
    _nonblank(role, 'name');
    _nullableText(role, 'brief');
    _text(json, 'requestId');
    for (final key in ['difficulty', 'limitSec']) {
      if (basic[key] is! int) {
        throw const FormatException('역할 자료 형식이 올바르지 않습니다.');
      }
    }
    _nullableInt(basic, 'estMin');
    _nullableInt(basic, 'estMax');
    persons = _items(
      json['persons'],
      const {'code', 'name', 'publicText'},
      const {'code', 'name'},
    );
    for (final person in persons) {
      _nullableText(person, 'publicText');
    }
    clues = _items(
      json['clues'],
      const {'code', 'title', 'body', 'personCode'},
      const {'code', 'title'},
    );
    for (final clue in clues) {
      _nullableText(clue, 'body');
      _nullableText(clue, 'personCode');
    }
    openedHints = _items(
      json['openedHints'],
      const {'level', 'body'},
      const {'body'},
    );
    for (final hint in openedHints) {
      if (hint['level'] is! int ||
          (hint['level'] as int) < 1 ||
          (hint['level'] as int) > 3) {
        throw const FormatException('힌트 단계가 올바르지 않습니다.');
      }
    }
    requiredNotices = _items(
      json['requiredNotices'],
      const {'rubricCode', 'text'},
      const {'rubricCode', 'text'},
    );
    for (final notice in requiredNotices) {
      _nonblank(notice, 'text');
    }
  }

  late final Map<String, dynamic> basic;
  late final Map<String, dynamic> role;
  late final List<Map<String, dynamic>> persons;
  late final List<Map<String, dynamic>> clues;
  late final List<Map<String, dynamic>> openedHints;
  late final List<Map<String, dynamic>> requiredNotices;

  /// 목록의 각 행을 타입 검사하고 허용된 필드만 보유한다.
  static List<Map<String, dynamic>> _items(
    Object? value,
    Set<String> names,
    Set<String> required,
  ) {
    if (value is! List) throw const FormatException('역할 자료 목록이 올바르지 않습니다.');
    return value.map((raw) {
      final item = _fields(raw, names);
      for (final key in required) {
        _text(item, key);
      }
      return item;
    }).toList();
  }
}

/// 현재 인증을 거쳐 상태·역할 자료·명시적 변경만 서버에 요청한다.
class InvestigationIntent {
  InvestigationIntent(
    this.testKey,
    this.generation,
    this.endpoint,
    this.action,
    Map<String, dynamic> body,
  ) : body = Map.unmodifiable(body);

  final String testKey;
  final int generation;
  final String endpoint;
  final String action;
  final Map<String, dynamic> body;
}

Map<String, dynamic> _reportData(Object? value, {bool draft = true}) {
  final report = _fields(value, const {
    'culpritCode',
    'method',
    'time',
    'motive',
    'evidence',
  });
  if (report.length != 5) throw const FormatException('보고서 형식이 올바르지 않습니다.');
  final culprit = report['culpritCode'];
  if (culprit != null && (culprit is! String || culprit.trim().isEmpty)) {
    throw const FormatException('보고서 인물 형식이 올바르지 않습니다.');
  }
  if (!draft && culprit == null) {
    throw const FormatException('보고서가 완성되지 않았습니다.');
  }
  var length = 0;
  for (final field in ['method', 'time', 'motive', 'evidence']) {
    final text = _text(report, field);
    if (text.runes.length > 5000) {
      throw const FormatException('보고서 길이가 너무 깁니다.');
    }
    length += text.runes.length;
  }
  if (length > 20000) throw const FormatException('보고서 길이가 너무 깁니다.');
  return report;
}

class ReportView {
  ReportView(Map<String, dynamic> json) {
    _fields(json, const {
      'testKey',
      'draftRev',
      'report',
      'proposal',
      'submissionState',
      'requestId',
    });
    testKey = _nonblank(json, 'testKey');
    draftRev = _nonblank(json, 'draftRev');
    if (!RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(draftRev)) {
      throw const FormatException('보고서 수정번호가 올바르지 않습니다.');
    }
    report = _reportData(json['report']);
    _nonblank(json, 'requestId');
    submissionState = _nonblank(json, 'submissionState');
    if (!const {'NONE', 'PROPOSED', 'PENDING'}.contains(submissionState)) {
      throw const FormatException('보고서 제출 상태가 올바르지 않습니다.');
    }
    if (json['proposal'] != null) {
      final item = _fields(json['proposal'], const {
        'reportKey',
        'sourceRev',
        'proposerSlot',
        'state',
        'canAccept',
        'canReject',
        'canWithdraw',
      });
      _nonblank(item, 'reportKey');
      if (!RegExp(r'^(0|[1-9][0-9]*)$')
              .hasMatch(_nonblank(item, 'sourceRev')) ||
          !const {1, 2}.contains(item['proposerSlot']) ||
          !const {
            'PROPOSED',
            'REJECTED',
            'WITHDRAWN',
            'INVALIDATED',
            'ACCEPTED',
            'GRADED',
            'UNGRADABLE',
            'CANCELLED',
          }.contains(item['state']) ||
          item['canAccept'] is! bool ||
          item['canReject'] is! bool ||
          item['canWithdraw'] is! bool) {
        throw const FormatException('보고서 제안 형식이 올바르지 않습니다.');
      }
      proposal = item;
    } else {
      proposal = null;
    }
  }
  late final String testKey;
  late final String draftRev;
  late final String submissionState;
  late final Map<String, dynamic> report;
  late final Map<String, dynamic>? proposal;
}

class ResultView {
  ResultView(Map<String, dynamic> json) {
    _fields(json, const {
      'testKey',
      'state',
      'outcome',
      'endedAt',
      'feedbackUntil',
      'resultPhase',
      'feedbackSubmitted',
      'finalScore',
      'scoreSummary',
      'reports',
      'revealText',
      'requestId',
    });
    testKey = _nonblank(json, 'testKey');
    if (json['state'] != 'ENDED' ||
        json['feedbackSubmitted'] is! bool ||
        !const {
          'AWAITING_FEEDBACK',
          'AVAILABLE',
          'UNAVAILABLE',
        }.contains(json['resultPhase'])) {
      throw const FormatException('결과 상태가 올바르지 않습니다.');
    }
    final outcome = _nonblank(json, 'outcome');
    final phase = json['resultPhase'];
    final technical = const {'SYSTEM_ERROR', 'FORFEIT'}.contains(outcome);
    if (phase == 'UNAVAILABLE' &&
        (!technical || json['feedbackSubmitted'] != true)) {
      throw const FormatException('결과 상태가 올바르지 않습니다.');
    }
    DateTime.parse(_nonblank(json, 'endedAt'));
    DateTime.parse(_nonblank(json, 'feedbackUntil'));
    _nonblank(json, 'requestId');
    if (phase == 'AWAITING_FEEDBACK' || phase == 'UNAVAILABLE') {
      if (json['finalScore'] != null ||
          json['scoreSummary'] != null ||
          json['reports'] != null ||
          json['revealText'] != null) {
        throw const FormatException('보호된 결과가 너무 일찍 전달되었습니다.');
      }
    } else {
      if (json['feedbackSubmitted'] != true ||
          !const {
            'SUCCESS',
            'ATTEMPTS_EXHAUSTED',
            'TIME_LIMIT',
          }.contains(outcome) ||
          json['finalScore'] is! int ||
          json['finalScore'] < 0 ||
          json['finalScore'] > 100 ||
          json['revealText'] is! String) {
        throw const FormatException('결과 형식이 올바르지 않습니다.');
      }
      final summary = _fields(json['scoreSummary'], const {
        'baseScore',
        'wrongCount',
        'penalty',
        'finalScore',
        'categories',
      });
      for (final field in [
        'baseScore',
        'wrongCount',
        'penalty',
        'finalScore',
      ]) {
        if (summary[field] is! int || summary[field] < 0) {
          throw const FormatException('결과 점수 형식이 올바르지 않습니다.');
        }
      }
      if (summary['finalScore'] != json['finalScore'] ||
          summary['categories'] is! List) {
        throw const FormatException('결과 점수가 일치하지 않습니다.');
      }
      summary['categories'] = (summary['categories'] as List).map((raw) {
        final category = _fields(raw, const {'category', 'score', 'maxScore'});
        _nonblank(category, 'category');
        if (category['score'] is! int ||
            category['maxScore'] is! int ||
            category['score'] < 0 ||
            category['maxScore'] < 0) {
          throw const FormatException('결과 항목 형식이 올바르지 않습니다.');
        }
        return category;
      }).toList();
      if (json['reports'] is! List) {
        throw const FormatException('제출본 형식이 올바르지 않습니다.');
      }
      json['reports'] = (json['reports'] as List).map((raw) {
        final item = _fields(raw, const {
          'reportKey',
          'submitNo',
          'report',
          'baseScore',
          'success',
        });
        _nonblank(item, 'reportKey');
        if (item['submitNo'] is! int ||
            item['submitNo'] < 1 ||
            item['baseScore'] is! int ||
            item['baseScore'] < 0 ||
            item['baseScore'] > 100 ||
            item['success'] is! bool) {
          throw const FormatException('제출본 형식이 올바르지 않습니다.');
        }
        item['report'] = _reportData(item['report'], draft: false);
        return item;
      }).toList();
    }
    data = json;
  }
  late final String testKey;
  late final Map<String, dynamic> data;
}

void _actionResult(Map<String, dynamic> json, InvestigationIntent intent) {
  _fields(json, const {
    'action',
    'replayed',
    'changed',
    'original',
    'current',
    'requestId',
  });
  if (json['action'] != intent.action ||
      json['replayed'] is! bool ||
      json['changed'] is! bool) {
    throw const FormatException('변경 영수증이 올바르지 않습니다.');
  }
  _nonblank(json, 'requestId');
  for (final key in ['original', 'current']) {
    final safe = _fields(json[key], const {
      'testKey',
      'rev',
      'state',
      'outcome',
      'startedAt',
      'playDeadline',
      'draftRev',
      'reportKey',
      'acceptedAt',
      'gradeDeadline',
      'jobKey',
      'feedbackSubmitted',
    });
    if (safe['testKey'] != intent.testKey ||
        safe['rev'] is! String ||
        !RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(safe['rev'] as String) ||
        !const {
          'WAITING',
          'RUNNING',
          'ENDED',
          'CANCELLED',
          'EXPIRED',
        }.contains(safe['state'])) {
      throw const FormatException('변경 영수증의 상태가 올바르지 않습니다.');
    }
    for (final field in ['outcome', 'startedAt', 'playDeadline']) {
      _nullableText(safe, field);
    }
    for (final field in ['startedAt', 'playDeadline']) {
      if (safe[field] != null) DateTime.parse(safe[field] as String);
    }
    for (final field in ['draftRev']) {
      if (safe.containsKey(field) &&
          (safe[field] is! String ||
              !RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(safe[field] as String))) {
        throw const FormatException('변경 영수증의 수정번호가 올바르지 않습니다.');
      }
    }
    for (final field in ['reportKey', 'jobKey']) {
      if (safe.containsKey(field)) _nonblank(safe, field);
    }
    for (final field in ['acceptedAt', 'gradeDeadline']) {
      if (safe.containsKey(field)) DateTime.parse(_nonblank(safe, field));
    }
    if (safe.containsKey('feedbackSubmitted') &&
        safe['feedbackSubmitted'] is! bool) {
      throw const FormatException('변경 영수증 형식이 올바르지 않습니다.');
    }
  }
}

class InvestigationRepository {
  InvestigationRepository(this.auth);
  final AuthController auth;

  Future<TestState> state(String key) async =>
      TestState(await auth.authorizedGet('/api/playtests/$key'));

  Future<InvestigationMaterials> materials(String key) async =>
      InvestigationMaterials(
        await auth.authorizedGet('/api/playtests/$key/materials'),
      );

  Future<ReportView> report(String key) async =>
      ReportView(await auth.authorizedGet('/api/playtests/$key/report'));

  Future<ResultView> result(String key) async =>
      ResultView(await auth.authorizedGet('/api/playtests/$key/result'));

  /// 현재 방·초안 수정번호와 전체 보고서 사본을 한 요청 키에 고정한다.
  InvestigationIntent editIntent(
    String key,
    String rev,
    String draftRev,
    Map<String, dynamic> report,
  ) => InvestigationIntent(
    key,
    auth.generation,
    '/api/playtests/$key/report',
    'REPORT_EDIT',
    {
      'expectedRev': rev,
      'expectedDraftRev': draftRev,
      'report': Map<String, dynamic>.from(_reportData(report)),
      'requestKey': requestKey(),
    },
  );

  InvestigationIntent proposeIntent(String key, String rev, String draftRev) =>
      InvestigationIntent(
        key,
        auth.generation,
        '/api/playtests/$key/report/proposals',
        'REPORT_PROPOSE',
        {
          'expectedRev': rev,
          'expectedDraftRev': draftRev,
          'requestKey': requestKey(),
        },
      );

  InvestigationIntent respondIntent(
    String key,
    String rev,
    String reportKey,
    String decision,
  ) {
    if (!const {'ACCEPT', 'REJECT', 'WITHDRAW'}.contains(decision)) {
      throw ArgumentError.value(decision, 'decision');
    }
    return InvestigationIntent(
      key,
      auth.generation,
      '/api/playtests/$key/report/proposals/$reportKey/respond',
      'REPORT_RESPOND',
      {'expectedRev': rev, 'decision': decision, 'requestKey': requestKey()},
    );
  }

  InvestigationIntent forfeitIntent(String key, String rev) =>
      InvestigationIntent(
        key,
        auth.generation,
        '/api/playtests/$key/forfeit',
        'TEST_FORFEIT',
        {'expectedRev': rev, 'requestKey': requestKey()},
      );

  InvestigationIntent feedbackIntent(String key, Map<String, String> feedback) {
    if (feedback.keys.toSet().difference(const {
          'blockedAt',
          'roleContribution',
          'fairness',
          'gradingConcern',
        }).isNotEmpty ||
        feedback.length != 4 ||
        feedback.values.any((value) => value.runes.length > 2000)) {
      throw const FormatException('피드백 길이 또는 형식이 올바르지 않습니다.');
    }
    return InvestigationIntent(
      key,
      auth.generation,
      '/api/playtests/$key/feedback',
      'TEST_FEEDBACK',
      {
        'requestKey': requestKey(),
        'feedback': Map<String, String>.from(feedback),
      },
    );
  }

  /// 새 명령만 키를 생성하고 재전송은 원본을 그대로 사용한다.
  InvestigationIntent readyIntent(String key, String rev, bool ready) =>
      InvestigationIntent(
        key,
        auth.generation,
        '/api/playtests/$key/ready',
        'TEST_READY',
        {'expectedRev': rev, 'ready': ready, 'requestKey': requestKey()},
      );

  Future<void> ready(String key, String rev, bool ready) =>
      send(readyIntent(key, rev, ready));

  InvestigationIntent startIntent(String key, String rev) =>
      InvestigationIntent(
        key,
        auth.generation,
        '/api/playtests/$key/start',
        'TEST_START',
        {'expectedRev': rev, 'requestKey': requestKey()},
      );

  Future<void> start(String key, String rev) => send(startIntent(key, rev));

  InvestigationIntent hintIntent(String key, String rev, int level) {
    if (level < 1 || level > 3) throw RangeError.range(level, 1, 3);
    return InvestigationIntent(
      key,
      auth.generation,
      '/api/playtests/$key/hints/$level/open',
      'HINT_OPEN',
      {'expectedRev': rev, 'requestKey': requestKey()},
    );
  }

  Future<void> openHint(String key, String rev, int level) async =>
      send(hintIntent(key, rev, level));

  Future<void> send(InvestigationIntent intent) async {
    if (intent.generation != auth.generation) throw StateError('계정이 변경되었습니다.');
    final result = intent.action == 'REPORT_EDIT'
        ? await auth.authorizedPatch(
            intent.endpoint,
            Map<String, dynamic>.from(intent.body),
          )
        : await auth.authorizedPost(
            intent.endpoint,
            Map<String, dynamic>.from(intent.body),
          );
    if (intent.generation != auth.generation) throw StateError('계정이 변경되었습니다.');
    _actionResult(result, intent);
  }

  /// 빈 204 응답을 현재 인증 세대에서만 유효한 접속 갱신으로 처리한다.
  Future<void> heartbeat(String key) async {
    await auth.authorizedPostNoContent('/api/playtests/$key/heartbeat');
  }
}

/// 열린 화면에서만 상태 조회와 접속 갱신을 유지하며 자격 변화 때 즉시 폐기한다.
class InvestigationPage extends ConsumerStatefulWidget {
  const InvestigationPage({super.key, required this.testKey});
  final String testKey;

  @override
  ConsumerState<InvestigationPage> createState() => _InvestigationPageState();
}

class _InvestigationPageState extends ConsumerState<InvestigationPage>
    with WidgetsBindingObserver {
  TestState? _state;
  InvestigationMaterials? _materials;
  ReportView? _report;
  Map<String, dynamic>? _editedReport;
  String? _editedDraftRev;
  int _editVersion = 0;
  ResultView? _result;
  final _notes = TextEditingController();
  final Map<String, String> _roleNotes = {};
  String? _roleCode;
  Timer? _pollTimer;
  Timer? _heartTimer;
  Timer? _clockTimer;
  int _epoch = 0;
  int? _heartbeatEpoch;
  int? _generation;
  bool _active = true;
  bool _busy = false;
  int? _loadingEpoch;
  Future<void>? _heartbeatFlight;
  bool _uncertain = false;
  InvestigationIntent? _intent;
  Object? _postOwner;
  bool _postSent = false;
  String? _message;
  DateTime? _serverAt;
  DateTime? _sampledAt;

  bool get _current =>
      mounted &&
      _active &&
      _generation == ref.read(authProvider.notifier).generation &&
      ref.read(authProvider).phase == AuthPhase.signedIn;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _generation = ref.read(authProvider.notifier).generation;
    Future.microtask(_resume);
  }

  /// 다른 테스트로 위젯이 재사용되면 이전 역할 원문과 응답 세대를 폐기한다.
  @override
  void didUpdateWidget(covariant InvestigationPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.testKey == widget.testKey) return;
    _stop();
    _purge();
    Future.microtask(_resume);
  }

  /// 접근 회수·종료·계정 변경 시 역할 자료와 임시 기록을 함께 제거한다.
  void _purge() {
    _intent = null;
    _uncertain = false;
    _postOwner = null;
    _postSent = false;
    _state = null;
    _materials = null;
    _report = null;
    _editedReport = null;
    _editedDraftRev = null;
    ++_editVersion;
    _result = null;
    _busy = false;
    _roleNotes.clear();
    _roleCode = null;
    _notes.clear();
    _serverAt = null;
    _sampledAt = null;
    _heartbeatEpoch = null;
  }

  /// 숨긴 기록은 새 역할 자료의 접근 증명 전에는 컨트롤러에도 노출하지 않는다.
  void _hide() {
    _state = null;
    _materials = null;
    _report = null;
    _result = null;
    _notes.clear();
    _serverAt = null;
    _sampledAt = null;
    _heartbeatEpoch = null;
  }

  bool _revoked(Object error) =>
      error is DioException &&
      const {401, 403, 404, 410}.contains(error.response?.statusCode);

  /// 비활성화 시 예약 요청을 중단하고 이전 응답의 반영을 막는다.
  void _stop() {
    ++_epoch;
    _pollTimer?.cancel();
    _heartTimer?.cancel();
    _clockTimer?.cancel();
    _pollTimer = null;
    _heartTimer = null;
    _clockTimer = null;
  }

  /// 복귀 시 서버 상태를 새로 조회하고 활동 중에만 반복 조회를 시작한다.
  void _resume() {
    if (!_current) return;
    _stop();
    _hide();
    if (mounted) setState(() {});
    _refresh();
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) => _refresh());
    _heartTimer = Timer.periodic(
      const Duration(seconds: 15),
      (_) => _heartbeat(),
    );
    _clockTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (_current) setState(() {});
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _active = state == AppLifecycleState.resumed;
    if (_active) {
      _resume();
    } else {
      _stop();
      if (_postOwner != null) {
        if (_postSent) {
          _uncertain = true;
          _message = '처리 결과가 불확실합니다. 원본 요청을 명시적으로 다시 보내 확인해 주세요.';
        } else if (!_uncertain) {
          _intent = null;
        }
        _postOwner = null;
        _postSent = false;
        _busy = false;
      }
      _hide();
      if (mounted) setState(() {});
    }
  }

  /// 현재 상태의 수정번호와 서버 기준 시각을 갱신하며 다른 방의 응답은 폐기한다.
  Future<void> _refresh() async {
    if (!_current || _loadingEpoch == _epoch || _busy) return;
    final epoch = _epoch;
    _loadingEpoch = epoch;
    try {
      final repo = InvestigationRepository(ref.read(authProvider.notifier));
      final state = await repo.state(widget.testKey);
      if (!_current || epoch != _epoch) return;
      if (state.testKey != widget.testKey) {
        throw const FormatException('조사 상태의 테스트 키가 일치하지 않습니다.');
      }
      if (state.state != 'WAITING' &&
          state.state != 'RUNNING' &&
          state.state != 'ENDED') {
        _stop();
        _purge();
        if (_current) setState(() => _state = state);
        return;
      }
      if (_roleCode != null &&
          (state.data['self'] as Map)['roleCode'] != _roleCode) {
        _purge();
      }
      InvestigationMaterials? materials;
      ReportView? report;
      ResultView? result;
      if (state.state == 'RUNNING') {
        materials = await repo.materials(widget.testKey);
        if (materials.role['code'] != (state.data['self'] as Map)['roleCode']) {
          throw const FormatException('배정된 역할과 자료가 일치하지 않습니다.');
        }
        report = await repo.report(widget.testKey);
        if (report.testKey != widget.testKey ||
            report.draftRev != state.data['draftRev'] ||
            report.submissionState != state.data['submissionState']) {
          // 서로 다른 조회 사이에 공동 보고서가 바뀐 경우 다음 polling에서 다시 확인한다.
          return;
        }
      } else if (state.state == 'ENDED') {
        result = await repo.result(widget.testKey);
        if (result.testKey != widget.testKey) {
          throw const FormatException('결과 테스트 키가 일치하지 않습니다.');
        }
      }
      if (!_current || epoch != _epoch) return;
      if (_state != null &&
          BigInt.parse(state.rev) < BigInt.parse(_state!.rev)) {
        return;
      }
      final roleCode = materials?.role['code'] as String?;
      if (_roleCode != null && roleCode != _roleCode) {
        _purge();
      }
      _roleCode = roleCode;
      _notes.text = roleCode == null ? '' : _roleNotes[roleCode] ?? '';
      setState(() {
        _state = state;
        _materials = state.state == 'RUNNING' ? materials : null;
        _report = report;
        _result = result;
        _serverAt = DateTime.parse(state.data['serverTime'] as String);
        _sampledAt = DateTime.now();
        if (!_uncertain) _message = null;
      });
      if (_heartbeatEpoch != epoch &&
          (state.state == 'WAITING' || state.state == 'RUNNING')) {
        _heartbeatEpoch = epoch;
        _heartbeat();
      }
    } catch (error) {
      if (_current && epoch == _epoch) {
        _stop();
        if (_revoked(error)) {
          _purge();
        } else {
          _hide();
        }
        setState(() => _message = '${playerError(error)} 연결이 복구되면 다시 조회해 주세요.');
      }
    } finally {
      if (_loadingEpoch == epoch) _loadingEpoch = null;
    }
  }

  /// 서버 접속 시각만 15초마다 갱신하고 전송 실패 시 자동 트래픽을 중단한다.
  Future<void> _heartbeat() async {
    if (!_current ||
        _busy ||
        _heartbeatFlight != null ||
        (_state?.state != 'WAITING' && _state?.state != 'RUNNING')) {
      return;
    }
    final epoch = _epoch;
    final flight = InvestigationRepository(ref.read(authProvider.notifier))
        .heartbeat(widget.testKey);
    _heartbeatFlight = flight;
    try {
      await flight;
    } catch (error) {
      if (_current && epoch == _epoch) {
        _stop();
        if (_revoked(error)) {
          _purge();
        } else {
          _hide();
        }
        setState(() => _message = '${playerError(error)} 연결이 복구되면 다시 조회해 주세요.');
      }
    } finally {
      if (identical(_heartbeatFlight, flight)) _heartbeatFlight = null;
    }
  }

  /// 응답 수명과 전송 소유권을 분리하고 원본 재전송은 명시적으로만 수행한다.
  Future<void> _act(
    InvestigationIntent Function(InvestigationRepository, TestState) action, {
    bool replay = false,
  }) async {
    final state = _state;
    if (!_current || _busy || state == null || (!replay && _intent != null)) {
      return;
    }
    final repo = InvestigationRepository(ref.read(authProvider.notifier));
    final intent = replay ? _intent : action(repo, state);
    if (intent == null ||
        intent.testKey != widget.testKey ||
        intent.generation != _generation) {
      return;
    }
    final wasUnknown = _uncertain;
    _stop();
    final epoch = _epoch;
    final owner = Object();
    _postOwner = owner;
    _postSent = false;
    _intent = intent;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      await _heartbeatFlight;
      if (!_current || epoch != _epoch || !identical(owner, _postOwner)) return;
      _postSent = true;
      await repo.send(intent);
      if (_current && epoch == _epoch && identical(owner, _postOwner)) {
        if (intent.action == 'REPORT_EDIT') {
          _editedReport = null;
          _editedDraftRev = null;
          ++_editVersion;
        }
        _intent = null;
        _uncertain = false;
        setState(() => _busy = false);
        _resume();
      }
    } catch (error) {
      if (_current && epoch == _epoch && identical(owner, _postOwner)) {
        final sent = _postSent;
        final conflict =
            error is DioException && error.response?.statusCode == 409;
        if (_revoked(error)) {
          _purge();
        } else {
          _hide();
        }
        if (!_revoked(error)) {
          _uncertain = wasUnknown || (sent && !conflict);
          _intent = _uncertain ? intent : null;
        }
        _busy = false;
        setState(() {
          _message = _uncertain
              ? '처리 결과가 불확실합니다. 자동 재전송하지 않습니다. 원본 요청을 다시 보내 확인해 주세요. ${playerError(error)}'
              : conflict
              ? '상태가 변경되었습니다. 새 조회 후 변경 내용을 확인하고 다시 명령해 주세요. ${playerError(error)}'
              : '접속 확인에 실패하여 변경 요청을 보내지 않았습니다. ${playerError(error)}';
        });
        _resume();
      }
    } finally {
      if (identical(owner, _postOwner)) {
        _postOwner = null;
        _postSent = false;
        _busy = false;
      }
    }
  }

  Future<void> _confirmForfeit() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('조사를 포기하시겠습니까?'),
        content: const Text('포기는 최종 종료 요청입니다. 점수와 해설은 제공되지 않습니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('포기 요청'),
          ),
        ],
      ),
    );
    if (confirmed == true && _current && _state?.state == 'RUNNING') {
      _act((repo, current) => repo.forfeitIntent(widget.testKey, current.rev));
    }
  }

  @override
  void dispose() {
    _stop();
    _purge();
    _notes.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<AuthSnapshot>(authProvider, (previous, next) {
      if (next.phase != AuthPhase.signedIn ||
          _generation != ref.read(authProvider.notifier).generation) {
        _stop();
        _purge();
      }
    });
    final auth = ref.watch(authProvider);
    if (auth.phase != AuthPhase.signedIn ||
        _generation != ref.read(authProvider.notifier).generation) {
      _stop();
      return const SizedBox.shrink();
    }
    final state = _state;
    final materials = _materials;
    final self = state?.data['self'] as Map?;
    final partner = state?.data['partner'] as Map?;
    final deadline = state?.data['playDeadline'] as String?;
    final now = _serverAt == null || _sampledAt == null
        ? null
        : _serverAt!.add(DateTime.now().difference(_sampledAt!));
    final remaining = deadline == null || now == null
        ? null
        : DateTime.parse(deadline).difference(now).inSeconds;
    final opened =
        materials?.openedHints.map((hint) => hint['level']).toSet() ??
        <Object?>{};
    final content = <Widget>[
      if (_message != null) PlayerNotice(_message!),
      if (state == null) const Center(child: CircularProgressIndicator()),
      if (state != null) ...[
        Text(state.title, style: Theme.of(context).textTheme.titleLarge),
        Text('진행 상태: ${state.state}'),
        if (state.state == 'WAITING') ...[
          Text(
            '내 수락: ${state.accepted ? '완료' : '대기'} · 상대 수락: ${partner?['accepted'] == true ? '완료' : '대기'}',
          ),
          Text(
            '내 준비: ${self?['ready'] == true ? '완료' : '대기'} · 상대 준비: ${partner?['ready'] == true ? '완료' : '대기'}',
          ),
          Text('상대 접속: ${partner?['online'] == true ? '최근 활동' : '확인되지 않음'}'),
          if (state.accepted)
            PlayerButton(
              self?['ready'] == true ? '준비 해제' : '준비하기',
              onPressed: _busy || _intent != null
                  ? null
                  : () => _act(
                      (repo, current) => repo.readyIntent(
                        widget.testKey,
                        current.rev,
                        self?['ready'] != true,
                      ),
                    ),
            ),
          if (self?['ready'] == true && partner?['ready'] == true)
            PlayerButton(
              '조사 시작',
              onPressed: _busy || _intent != null
                  ? null
                  : () => _act(
                      (repo, current) =>
                          repo.startIntent(widget.testKey, current.rev),
                    ),
            ),
        ],
        if (state.state == 'RUNNING') ...[
          if (remaining != null)
            Text(
              '서버 기준 남은 시간: ${remaining < 0 ? 0 : remaining}초 (표시용, 서버 판정 우선)',
            ),
          if (materials == null) const PlayerNotice('배정된 자료를 확인하는 중입니다.'),
          if (materials != null) ...[
            Text(
              materials.role['name'] as String,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            if (materials.role['brief'] is String)
              Text(materials.role['brief'] as String),
            Text(
              materials.basic['title'] as String,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (materials.basic['intro'] is String)
              Text(materials.basic['intro'] as String),
            if (materials.basic['setting'] is String)
              Text(materials.basic['setting'] as String),
            Text('난이도: ${materials.basic['difficulty']}'),
            if (materials.basic['estMin'] != null &&
                materials.basic['estMax'] != null)
              Text(
                '예상 시간: ${materials.basic['estMin']}~${materials.basic['estMax']}분',
              ),
            for (final person in materials.persons)
              Card(
                color: PlayerTheme.surface,
                child: ListTile(
                  title: Text(person['name'] as String),
                  subtitle: person['publicText'] == null
                      ? null
                      : Text(person['publicText'] as String),
                ),
              ),
            for (final clue in materials.clues)
              Card(
                color: PlayerTheme.surface,
                child: ListTile(
                  title: Text(clue['title'] as String),
                  subtitle: clue['body'] == null
                      ? null
                      : Text(clue['body'] as String),
                ),
              ),
            for (final notice in materials.requiredNotices)
              PlayerNotice(notice['text'] as String),
            for (final hint in materials.openedHints)
              PlayerNotice('${hint['level']}단계 힌트: ${hint['body']}'),
            if (state.data['submissionState'] != 'PENDING' &&
                remaining != null &&
                remaining > 0)
              for (final level in [1, 2, 3])
                if (!opened.contains(level))
                  PlayerButton(
                    '$level단계 힌트 열기',
                    onPressed:
                        _busy ||
                            _intent != null ||
                            (state.data['hintsRemaining'] is int &&
                                (state.data['hintsRemaining'] as int) <= 0)
                        ? null
                        : () => _act(
                            (repo, current) => repo.hintIntent(
                              widget.testKey,
                              current.rev,
                              level,
                            ),
                          ),
                  ),
            const PlayerNotice(
              '개인 기록은 현재 화면의 메모리에서만 보관되며 서버에 저장되지 않습니다. 화면을 닫거나 로그아웃하면 사라집니다.',
            ),
            TextField(
              controller: _notes,
              maxLines: 8,
              onChanged: (value) {
                if (_roleCode != null) _roleNotes[_roleCode!] = value;
              },
              decoration: const InputDecoration(
                labelText: '개인 조사 기록 (임시 메모)',
                alignLabelWithHint: true,
              ),
            ),
            if (_report != null)
              ReportSheet(
                key: ValueKey(
                  '${widget.testKey}-${_generation ?? 0}-${_editedReport == null ? _report!.draftRev : 'edited'}-$_editVersion',
                ),
                report: _editedReport ?? _report!.report,
                edited: _editedReport != null,
                persons: _materials!.persons,
                proposal: _report!.proposal,
                submissionState: _report!.submissionState,
                enabled:
                    !_busy &&
                    _intent == null &&
                    remaining != null &&
                    remaining > 0,
                staleDraft:
                    _editedDraftRev != null &&
                    _editedDraftRev != _report!.draftRev,
                onChanged: (report) => setState(() {
                  _editedDraftRev ??= _report!.draftRev;
                  _editedReport = report;
                }),
                onDiscard: () => setState(() {
                  _editedReport = null;
                  _editedDraftRev = null;
                  ++_editVersion;
                }),
                onSave: (report) => _act(
                  (repo, current) => repo.editIntent(
                    widget.testKey,
                    current.rev,
                    _report!.draftRev,
                    report,
                  ),
                ),
                onPropose: () => _act(
                  (repo, current) => repo.proposeIntent(
                    widget.testKey,
                    current.rev,
                    _report!.draftRev,
                  ),
                ),
                onRespond: (decision) => _act(
                  (repo, current) => repo.respondIntent(
                    widget.testKey,
                    current.rev,
                    _report!.proposal!['reportKey'] as String,
                    decision,
                  ),
                ),
              ),
            if (state.data['submissionState'] != 'PENDING' &&
                remaining != null &&
                remaining > 0)
              PlayerButton(
                '조사 포기',
                secondary: true,
                onPressed: _busy || _intent != null ? null : _confirmForfeit,
              ),
          ],
        ],
        if (state.state == 'ENDED' && _result != null)
          ResultSheet(
            key: ValueKey('${widget.testKey}-${_generation ?? 0}'),
            result: _result!.data,
            enabled: !_busy && _intent == null,
            onFeedback: (feedback) => _act(
              (repo, current) => repo.feedbackIntent(widget.testKey, feedback),
            ),
          ),
        if (state.state != 'WAITING' &&
            state.state != 'RUNNING' &&
            state.state != 'ENDED')
          const PlayerNotice('이 조사의 접근은 종료되었습니다.'),
      ],
      if (_uncertain && _intent != null)
        PlayerButton(
          '원본 요청 다시 보내기',
          onPressed: _busy || state == null
              ? null
              : () => _act((repo, current) => _intent!, replay: true),
          secondary: true,
        ),
      PlayerButton(
        '서버 상태 다시 조회',
        onPressed: _busy
            ? null
            : () {
                _resume();
              },
        secondary: true,
      ),
      PlayerButton(
        '초대로 돌아가기',
        onPressed: () => context.go('/invitations/${widget.testKey}'),
        secondary: true,
      ),
    ];
    return PlayerPage(
      title: '사건 조사',
      actions: [
        IconButton(
          tooltip: '로그아웃',
          onPressed: () => ref.read(authProvider.notifier).logout(),
          icon: const Icon(Icons.logout),
        ),
      ],
      children: [
        // Keep one stable sliver child. Conditional notices, refresh hiding,
        // hints and retained editable fields must not shift lazy-list indices
        // and trigger contradictory scroll-offset corrections on recovery.
        Column(
          key: const ValueKey('investigation-content'),
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: content
              .expand((child) => [child, const SizedBox(height: 16)])
              .toList(),
        ),
      ],
    );
  }
}
