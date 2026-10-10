import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../auth/auth_controller.dart';
import '../core/player_api.dart';
import '../core/player_theme.dart';

class Invitation {
  Invitation(Map<String, dynamic> json)
    : testKey =
          _allowed(json, const {
                'testKey',
                'inviteGen',
                'title',
                'mode',
                'limitSec',
                'inviteExpiresAt',
                'inviteStatus',
                'policyCode',
                'noticeHash',
                'noticeSummary',
              })['testKey']
              as String,
      inviteGen = json['inviteGen'] as int,
      title = json['title'] as String,
      mode = json['mode'] as String,
      limitSec = json['limitSec'] as int,
      inviteExpiresAt = DateTime.parse(json['inviteExpiresAt'] as String),
      inviteStatus = json['inviteStatus'] as String,
      policyCode = json['policyCode'] as String,
      noticeHash = json['noticeHash'] as String,
      noticeSummary = json['noticeSummary'] as String;

  final String testKey;
  final int inviteGen;
  final String title;
  final String mode;
  final int limitSec;
  final DateTime inviteExpiresAt;
  final String inviteStatus;
  final String policyCode;
  final String noticeHash;
  final String noticeSummary;
}

class TestState {
  TestState(Map<String, dynamic> json)
    : data = _allowed(json, const {
        'testKey',
        'title',
        'mode',
        'state',
        'outcome',
        'rev',
        'draftRev',
        'snapshotRef',
        'runtimeConfigId',
        'self',
        'partner',
        'inviteExpiresAt',
        'lobbyExpiresAt',
        'startedAt',
        'playDeadline',
        'serverTime',
        'submissionState',
        'attemptsRemaining',
        'hintsRemaining',
        'feedbackUntil',
        'feedbackSubmitted',
        'resultPhase',
        'requestId',
      }) {
    _revision(data['rev']);
    _revision(data['draftRev']);
    _allowed(Map<String, dynamic>.from(data['self'] as Map), const {
      'slot',
      'inviteGen',
      'accepted',
      'ready',
      'roleCode',
      'blindStatus',
    });
    _allowed(Map<String, dynamic>.from(data['partner'] as Map), const {
      'accepted',
      'ready',
      'online',
    });
  }

  final Map<String, dynamic> data;
  String get testKey => data['testKey'] as String;
  String get title => data['title'] as String;
  String get rev => data['rev'] as String;
  String get state => data['state'] as String;
  DateTime get inviteExpiresAt =>
      DateTime.parse(data['inviteExpiresAt'] as String);
  bool get accepted => (data['self'] as Map)['accepted'] as bool;
}

class InvitationPageData {
  InvitationPageData(Map<String, dynamic> json)
    : items = (json['items'] as List<dynamic>)
          .map((item) => Invitation(Map<String, dynamic>.from(item as Map)))
          .toList(),
      nextCursor = json['nextCursor'] as String? {
    _allowed(json, const {'items', 'nextCursor', 'requestId'});
    if (nextCursor != null && !RegExp(r'^[1-9][0-9]*$').hasMatch(nextCursor!)) {
      throw const FormatException('목록 커서가 올바르지 않습니다.');
    }
  }
  final List<Invitation> items;
  final String? nextCursor;
}

class ConsentNoticeData {
  ConsentNoticeData(Map<String, dynamic> json)
    : testKey =
          _allowed(json, const {
                'testKey',
                'generation',
                'revision',
                'policyCode',
                'noticeHash',
                'version',
                'body',
                'contact',
                'requestId',
              })['testKey']
              as String,
      generation = json['generation'] as int,
      revision = _revision(json['revision']),
      policyCode = json['policyCode'] as String,
      noticeHash = json['noticeHash'] as String,
      version = json['version'] as String,
      body = json['body'] as String,
      contact = json['contact'] as String;

  final String testKey;
  final int generation;
  final String revision;
  final String policyCode;
  final String noticeHash;
  final String version;
  final String body;
  final String contact;
}

/// 최초 명시 동의의 고지 전체와 요청 키를 계정 세대에 고정한다.
class InvitationAcceptIntent {
  InvitationAcceptIntent(this.notice, this.accountGeneration)
    : key = requestKey() {
    body = Map<String, dynamic>.unmodifiable({
      'expectedRev': notice.revision,
      'inviteGen': notice.generation,
      'blindDeclared': false,
      'policyCode': notice.policyCode,
      'noticeHash': notice.noticeHash,
      'requestKey': key,
    });
  }

  final ConsentNoticeData notice;
  final int accountGeneration;
  final String key;
  late final Map<String, dynamic> body;
}

/// 다른 참가자 정보가 섞인 응답은 화면에 전달하지 않는다.
Map<String, dynamic> _allowed(Map<String, dynamic> json, Set<String> allowed) {
  if (json.keys.any((key) => !allowed.contains(key))) {
    throw const FormatException('초대 응답에 허용되지 않은 항목이 있습니다.');
  }
  return json;
}

/// 서버 수정번호는 정밀도를 잃지 않는 표준 십진 문자열이어야 한다.
String _revision(Object? value) {
  if (value is! String ||
      !RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(value) ||
      BigInt.parse(value) > BigInt.parse('9223372036854775807')) {
    throw const FormatException('초대 수정번호가 올바르지 않습니다.');
  }
  return value;
}

final invitationRepositoryProvider = Provider<InvitationRepository>(
  (ref) => InvitationRepository(ref.read(authProvider.notifier)),
);

class InvitationRepository {
  InvitationRepository(this.auth);
  final AuthController auth;

  Future<String> identity() async =>
      (await auth.authorizedGet('/api/playtests/identity'))['memberKey']
          as String;

  Future<InvitationPageData> list([String? cursor]) async => InvitationPageData(
    await auth.authorizedGet(
      '/api/playtests/invitations${cursor == null ? '' : '?cursor=$cursor'}',
    ),
  );

  Future<TestState> detail(String key) async =>
      TestState(await auth.authorizedGet('/api/playtests/$key'));

  Future<ConsentNoticeData> notice(String key) async => ConsentNoticeData(
    await auth.authorizedGet('/api/playtests/$key/policy-notice'),
  );

  /// 동일 계정 세대의 원본 요청만 전송하고 영수증 뒤 현재 상태는 별도 조회한다.
  Future<void> accept(InvitationAcceptIntent intent) async {
    if (intent.accountGeneration != auth.generation) {
      throw StateError('계정이 변경되었습니다.');
    }
    final notice = intent.notice;
    final result = await auth.authorizedPost(
      '/api/playtests/${notice.testKey}/accept',
      Map<String, dynamic>.from(intent.body),
    );
    if (intent.accountGeneration != auth.generation) {
      throw StateError('계정이 변경되었습니다.');
    }
    _allowed(result, const {
      'action',
      'changed',
      'replayed',
      'original',
      'current',
      'requestId',
    });
    if (result['action'] != 'INVITATION_ACCEPT' ||
        result['changed'] is! bool ||
        result['replayed'] is! bool ||
        result['changed'] == result['replayed'] ||
        result['requestId'] is! String ||
        (result['requestId'] as String).trim().isEmpty) {
      throw const FormatException('동의 결과가 올바르지 않습니다.');
    }
    for (final field in ['original', 'current']) {
      final value = result[field];
      if (value is! Map) throw const FormatException('동의 영수증이 올바르지 않습니다.');
      final state = _allowed(Map<String, dynamic>.from(value), const {
        'testKey',
        'rev',
        'state',
        'inviteExpiresAt',
      });
      if (state.length != 4 ||
          state['testKey'] != notice.testKey ||
          !const {
            'WAITING',
            'RUNNING',
            'ENDED',
            'CANCELLED',
            'EXPIRED',
          }.contains(state['state']) ||
          state['inviteExpiresAt'] is! String) {
        throw const FormatException('동의 영수증의 상태가 올바르지 않습니다.');
      }
      _revision(state['rev']);
      DateTime.parse(state['inviteExpiresAt'] as String);
    }
  }
}

class InvitationListPage extends ConsumerStatefulWidget {
  const InvitationListPage({super.key});
  @override
  ConsumerState<InvitationListPage> createState() => _InvitationListPageState();
}

class _InvitationListPageState extends ConsumerState<InvitationListPage> {
  List<Invitation>? _items;
  String? _nextCursor;
  String? _identity;
  String? _message;
  bool _busy = false;
  int _sequence = 0;

  @override
  void initState() {
    super.initState();
    Future.microtask(_load);
  }

  Future<void> _load([bool more = false]) async {
    if (_busy) return;
    if (more && _nextCursor == null) return;
    final sequence = ++_sequence;
    final auth = ref.read(authProvider.notifier);
    final generation = auth.generation;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final repo = ref.read(invitationRepositoryProvider);
      final identity = await repo.identity();
      final page = await repo.list(more ? _nextCursor : null);
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      setState(() {
        _identity = identity;
        _items = more ? [...?_items, ...page.items] : page.items;
        _nextCursor = page.nextCursor;
      });
    } catch (error) {
      if (mounted && sequence == _sequence && generation == auth.generation) {
        setState(() => _message = playerError(error));
      }
    } finally {
      if (mounted && sequence == _sequence) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => PlayerPage(
    title: '초대',
    actions: [
      IconButton(
        tooltip: '로그아웃',
        onPressed: () => ref.read(authProvider.notifier).logout(),
        icon: const Icon(Icons.logout),
        constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
      ),
    ],
    children: [
      if (_identity != null) SelectableText('내 참가 키: $_identity'),
      if (_message != null) PlayerNotice(_message!),
      if (_busy) const Center(child: CircularProgressIndicator()),
      if (_items != null && _items!.isEmpty)
        const PlayerNotice('현재 확인할 수 있는 초대가 없습니다.'),
      for (final invitation in _items ?? <Invitation>[])
        Card(
          color: PlayerTheme.surface,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  invitation.title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 8),
                Text(
                  '상태: ${invitation.inviteStatus} · ${invitation.mode} · ${invitation.limitSec}초',
                ),
                Text('초대 기한: ${invitation.inviteExpiresAt.toLocal()}'),
                SelectableText(
                  '정책: ${invitation.policyCode}\n고지 해시: ${invitation.noticeHash}',
                ),
                PlayerNotice(invitation.noticeSummary),
                const SizedBox(height: 12),
                PlayerButton(
                  '초대 확인',
                  onPressed: () =>
                      context.push('/invitations/${invitation.testKey}'),
                ),
              ],
            ),
          ),
        ),
      PlayerButton(
        '목록 다시 조회',
        onPressed: _busy ? null : () => _load(),
        secondary: true,
      ),
      if (_nextCursor != null)
        PlayerButton('더 보기', onPressed: _busy ? null : () => _load(true)),
    ],
  );
}

class InvitationDetailPage extends ConsumerStatefulWidget {
  const InvitationDetailPage({super.key, required this.testKey});
  final String testKey;
  @override
  ConsumerState<InvitationDetailPage> createState() =>
      _InvitationDetailPageState();
}

class _InvitationDetailPageState extends ConsumerState<InvitationDetailPage> {
  TestState? _invitation;
  ConsentNoticeData? _notice;
  String? _message;
  bool _agreed = false;
  bool _busy = false;
  bool _uncertain = false;
  InvitationAcceptIntent? _intent;
  bool _receiptConfirmed = false;
  late final ProviderSubscription<AuthSnapshot> _authSubscription;
  late int _generation;
  int _sequence = 0;

  @override
  void initState() {
    super.initState();
    _generation = ref.read(authProvider.notifier).generation;
    _authSubscription = ref.listenManual(authProvider, (previous, next) {
      if (_generation != ref.read(authProvider.notifier).generation ||
          (previous?.phase == AuthPhase.signedIn &&
              next.phase != AuthPhase.signedIn)) {
        ++_sequence;
        _generation = ref.read(authProvider.notifier).generation;
        setState(_purge);
      }
    });
    Future.microtask(_load);
  }

  /// 접근 회수·계정 변경·화면 폐기 시 원문과 동의 의도를 함께 제거한다.
  void _purge() {
    _invitation = null;
    _notice = null;
    _intent = null;
    _receiptConfirmed = false;
    _agreed = false;
    _uncertain = false;
    _busy = false;
    _message = null;
  }

  bool _revoked(Object error) =>
      error is DioException &&
      const {401, 403, 404, 410}.contains(error.response?.statusCode);

  /// 해시뿐 아니라 고지의 전체 신원이 바뀌면 새 명시 동의가 필요하다.
  bool _sameNotice(ConsentNoticeData? left, ConsentNoticeData? right) =>
      left?.testKey == right?.testKey &&
      left?.generation == right?.generation &&
      left?.revision == right?.revision &&
      left?.policyCode == right?.policyCode &&
      left?.noticeHash == right?.noticeHash &&
      left?.version == right?.version &&
      left?.body == right?.body &&
      left?.contact == right?.contact;

  @override
  void dispose() {
    ++_sequence;
    _authSubscription.close();
    _purge();
    super.dispose();
  }

  /// 같은 위젯이 다른 초대를 표시하면 이전 동의 원문과 응답을 폐기한다.
  @override
  void didUpdateWidget(covariant InvitationDetailPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.testKey == widget.testKey) return;
    ++_sequence;
    _purge();
    Future.microtask(_load);
  }

  /// 안전한 조회는 불확실한 원본 의도를 바꾸거나 자동 재전송하지 않는다.
  Future<void> _load() async {
    if (!mounted || _busy) return;
    final sequence = ++_sequence;
    final auth = ref.read(authProvider.notifier);
    final generation = auth.generation;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      final repo = ref.read(invitationRepositoryProvider);
      final invitation = await repo.detail(widget.testKey);
      if (invitation.testKey != widget.testKey) {
        throw const FormatException('초대의 테스트 키가 일치하지 않습니다.');
      }
      ConsentNoticeData? notice;
      if (!invitation.accepted && invitation.state == 'WAITING') {
        notice = await repo.notice(widget.testKey);
        if (notice.testKey != widget.testKey ||
            notice.generation !=
                (invitation.data['self'] as Map)['inviteGen'] ||
            notice.revision != invitation.rev) {
          throw const FormatException('초대와 고지의 신원이 일치하지 않습니다.');
        }
      }
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      // 느린 이전 조회로 최신 수정번호를 덮어쓰지 않는다.
      if (_invitation != null &&
          BigInt.parse(invitation.rev) < BigInt.parse(_invitation!.rev)) {
        return;
      }
      setState(() {
        if (_receiptConfirmed && invitation.accepted) {
          _intent = null;
          _receiptConfirmed = false;
          _uncertain = false;
          _agreed = false;
        }
        if (_intent == null) {
          if (!_sameNotice(notice, _notice)) _agreed = false;
          _notice = notice;
        }
        _invitation = invitation;
      });
    } catch (error) {
      if (mounted && sequence == _sequence && generation == auth.generation) {
        setState(() {
          if (_revoked(error)) _purge();
          _message = playerError(error);
        });
      }
    } finally {
      if (mounted && sequence == _sequence) setState(() => _busy = false);
    }
  }

  /// 첫 명시 동의만 키를 만들며 재전송은 원본 고지와 키를 그대로 사용한다.
  Future<void> _accept({bool replay = false}) async {
    final notice = _notice;
    if (_busy || (!replay && (_intent != null || !_agreed || notice == null))) {
      return;
    }
    final auth = ref.read(authProvider.notifier);
    final generation = auth.generation;
    final intent = replay
        ? _intent
        : InvitationAcceptIntent(notice!, generation);
    if (intent == null ||
        intent.accountGeneration != generation ||
        intent.notice.testKey != widget.testKey) {
      return;
    }
    final sequence = ++_sequence;
    setState(() {
      _intent = intent;
      _busy = true;
      _message = null;
    });
    try {
      final repo = ref.read(invitationRepositoryProvider);
      await repo.accept(intent);
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      _receiptConfirmed = true;
      final result = await repo.detail(widget.testKey);
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      if (result.testKey != widget.testKey || !result.accepted) {
        throw const FormatException('동의 상태를 확인하지 못했습니다.');
      }
      setState(() {
        _invitation = result;
        _notice = null;
        _intent = null;
        _receiptConfirmed = false;
        _agreed = false;
        _uncertain = false;
        _message = '동의가 접수되었습니다.';
      });
    } catch (error) {
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      setState(() {
        if (_revoked(error)) {
          _purge();
          _message = playerError(error);
          return;
        }
        _uncertain = true;
        _message =
            '동의 결과를 확인할 수 없습니다. 자동 재전송하지 않습니다. 서버 상태 조회 또는 원본 동의 요청 재전송으로 확인해 주세요. ${playerError(error)}';
      });
    } finally {
      if (mounted && sequence == _sequence) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final invitation = _invitation;
    final notice = _notice;
    return PlayerPage(
      title: '초대 확인',
      children: [
        if (_message != null) PlayerNotice(_message!),
        if (_busy) const Center(child: CircularProgressIndicator()),
        if (invitation != null) ...[
          Text(
            invitation.title,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          Text('상태: ${invitation.accepted ? '동의 완료' : '동의 대기'}'),
          Text('초대 기한: ${invitation.inviteExpiresAt.toLocal()}'),
          const PlayerNotice(
            '테스트 원문은 종료 후 90일, 선별 보존 자료는 최대 365일 보관됩니다. 원문 파기 뒤에는 보고서·피드백 원문 조회와 재판정이 불가능합니다.',
          ),
        ],
        if (notice != null &&
            invitation != null &&
            (!invitation.accepted || _intent != null)) ...[
          Text(
            '개인정보 처리 고지 ${notice.version}',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          PlayerNotice(notice.body),
          Text('문의: ${notice.contact}'),
          CheckboxListTile(
            value: _agreed,
            onChanged: _busy || _intent != null
                ? null
                : (value) => setState(() => _agreed = value ?? false),
            title: const Text('위 고지를 읽고 기능 테스트 참여에 동의합니다.'),
            controlAffinity: ListTileControlAffinity.leading,
          ),
          if (_intent == null)
            PlayerButton(
              '동의하고 초대 수락',
              onPressed: _busy || !_agreed ? null : _accept,
            ),
        ],
        if (_uncertain && _intent != null)
          PlayerButton(
            '원본 동의 요청 다시 보내기',
            onPressed: _busy ? null : () => _accept(replay: true),
            secondary: true,
          ),
        if (invitation != null &&
            _intent == null &&
            invitation.accepted &&
            (invitation.state == 'WAITING' || invitation.state == 'RUNNING'))
          PlayerButton(
            '조사 화면으로',
            onPressed: _busy
                ? null
                : () => context.push('/investigation/${widget.testKey}'),
          ),
        PlayerButton(
          _uncertain ? '서버 상태 확인' : '초대 다시 조회',
          onPressed: _busy ? null : () => _load(),
          secondary: true,
        ),
        PlayerButton(
          '목록으로',
          onPressed: () => context.go('/invitations'),
          secondary: true,
        ),
      ],
    );
  }
}
