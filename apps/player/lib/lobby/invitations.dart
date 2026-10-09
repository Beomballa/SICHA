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

  /// 동의 영수증만 검증하며 확정된 현재 상태는 별도 조회한다.
  Future<void> accept(ConsentNoticeData notice, String key) async {
    final result = await auth.authorizedPost(
      '/api/playtests/${notice.testKey}/accept',
      {
        'expectedRev': notice.revision,
        'inviteGen': notice.generation,
        'blindDeclared': false,
        'policyCode': notice.policyCode,
        'noticeHash': notice.noticeHash,
        'requestKey': key,
      },
    );
    if (result['action'] != 'INVITATION_ACCEPT' ||
        result['changed'] != true && result['replayed'] != true ||
        result['current'] is! Map ||
        result['current']['testKey'] != notice.testKey) {
      throw const FormatException('동의 결과가 올바르지 않습니다.');
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
  int _sequence = 0;

  @override
  void initState() {
    super.initState();
    Future.microtask(_load);
  }

  Future<void> _load() async {
    if (_busy) return;
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
      ConsentNoticeData? notice;
      if (!invitation.accepted && invitation.state == 'WAITING') {
        notice = await repo.notice(widget.testKey);
      }
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      // A slow earlier read must not replace the mutation's newer revision.
      if (_invitation != null &&
          BigInt.parse(invitation.rev) < BigInt.parse(_invitation!.rev)) {
        return;
      }
      setState(() {
        if (notice != null && notice.noticeHash != _notice?.noticeHash) {
          _agreed = false;
        }
        _invitation = invitation;
        _notice = notice;
        _uncertain = false;
      });
    } catch (error) {
      if (mounted && sequence == _sequence && generation == auth.generation) {
        setState(() => _message = playerError(error));
      }
    } finally {
      if (mounted && sequence == _sequence) setState(() => _busy = false);
    }
  }

  Future<void> _accept() async {
    final notice = _notice;
    if (_busy || !_agreed || notice == null) return;
    final auth = ref.read(authProvider.notifier);
    final generation = auth.generation;
    final sequence = ++_sequence;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      // Never generate a new key for an implicit retry; every press is one explicit attempt.
      final repo = ref.read(invitationRepositoryProvider);
      await repo.accept(notice, requestKey());
      final result = await repo.detail(widget.testKey);
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      if (!result.accepted) throw const FormatException('동의 상태를 확인하지 못했습니다.');
      setState(() {
        _invitation = result;
        _notice = null;
        _uncertain = false;
        _message = '동의가 접수되었습니다.';
      });
    } catch (error) {
      if (!mounted || sequence != _sequence || generation != auth.generation) {
        return;
      }
      setState(() {
        _uncertain = true;
        _message =
            '동의 결과를 확인할 수 없습니다. 자동 재전송하지 않습니다. 현재 초대 상태를 조회해 주세요. ${playerError(error)}';
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
        if (notice != null && invitation != null && !invitation.accepted) ...[
          Text(
            '개인정보 처리 고지 ${notice.version}',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          PlayerNotice(notice.body),
          Text('문의: ${notice.contact}'),
          CheckboxListTile(
            value: _agreed,
            onChanged: _busy
                ? null
                : (value) => setState(() => _agreed = value ?? false),
            title: const Text('위 고지를 읽고 기능 테스트 참여에 동의합니다.'),
            controlAffinity: ListTileControlAffinity.leading,
          ),
          if (!_uncertain)
            PlayerButton(
              '동의하고 초대 수락',
              onPressed: _busy || !_agreed ? null : _accept,
            ),
        ],
        if (invitation != null &&
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
