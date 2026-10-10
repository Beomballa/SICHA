import 'package:flutter/material.dart';

import '../core/player_api.dart';
import '../core/player_theme.dart';
import 'private_note_renderer_native.dart'
    if (dart.library.js_interop) 'private_note_renderer_web.dart';

class ResultSheet extends StatefulWidget {
  const ResultSheet({
    super.key,
    required this.result,
    required this.enabled,
    required this.onFeedback,
  });

  final Map<String, dynamic> result;
  final bool enabled;
  final void Function(Map<String, String> feedback) onFeedback;

  @override
  State<ResultSheet> createState() => _ResultSheetState();
}

class _ResultSheetState extends State<ResultSheet> with WidgetsBindingObserver {
  final _blocked = TextEditingController();
  final _contribution = TextEditingController();
  final _fairness = TextEditingController();
  final _concern = TextEditingController();
  late final _identifiers = {
    'blockedAt': 'sicha-private-feedback-${requestKey()}',
    'roleContribution': 'sicha-private-feedback-${requestKey()}',
    'fairness': 'sicha-private-feedback-${requestKey()}',
    'gradingConcern': 'sicha-private-feedback-${requestKey()}',
  };
  bool _revealed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didUpdateWidget(covariant ResultSheet oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.result['testKey'] != widget.result['testKey'] ||
        widget.result['resultPhase'] != 'AVAILABLE') {
      _revealed = false;
    }
    if ((oldWidget.enabled && !widget.enabled) ||
        (oldWidget.result['resultPhase'] == 'AWAITING_FEEDBACK' &&
            oldWidget.result['feedbackSubmitted'] == false &&
            (widget.result['resultPhase'] != 'AWAITING_FEEDBACK' ||
                widget.result['feedbackSubmitted'] != false))) {
      _eraseEditors();
    }
  }

  /// 자식 편집기의 연결 해제 전에 이 시트가 소유한 DOM만 지운다.
  void _eraseRenderers() {
    for (final identifier in _identifiers.values) {
      privateEditorEraser?.call(identifier);
    }
  }

  /// 피드백 텍스트와 이 시트의 semantic textarea만 함께 지운다.
  void _eraseEditors() {
    _eraseRenderers();
    for (final entry in {
      'blockedAt': _blocked,
      'roleContribution': _contribution,
      'fairness': _fairness,
      'gradingConcern': _concern,
    }.entries) {
      entry.value.clear();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) _eraseEditors();
  }

  /// 자식 연결 해제 전 DOM만 지운다. 컨트롤러 알림은 자식 제거 뒤 dispose에서 처리한다.
  @override
  void deactivate() {
    _eraseRenderers();
    super.deactivate();
  }

  @override
  void dispose() {
    _eraseEditors();
    WidgetsBinding.instance.removeObserver(this);
    _blocked.dispose();
    _contribution.dispose();
    _fairness.dispose();
    _concern.dispose();
    super.dispose();
  }

  /// 피드백 필드는 비활성 요청 동안 편집할 수 없으며 시트별 식별자로만 지운다.
  Widget _field(String name, String label, TextEditingController controller) =>
      Semantics(
        identifier: _identifiers[name],
        container: true,
        explicitChildNodes: true,
        child: TextField(
          controller: controller,
          enabled: widget.enabled,
          maxLines: 3,
          maxLength: 2000,
          decoration: InputDecoration(
            labelText: label,
            alignLabelWithHint: true,
          ),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final result = widget.result;
    final available = result['resultPhase'] == 'AVAILABLE';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '조사 종료: ${result['outcome']}',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        if (result['resultPhase'] == 'AWAITING_FEEDBACK' &&
            result['feedbackSubmitted'] == false) ...[
          const PlayerNotice(
            '본인 피드백을 제출하면 서버가 허용한 결과만 다시 조회합니다. 제출 전에는 점수와 해설을 볼 수 없습니다.',
          ),
          _field('blockedAt', '막힌 지점', _blocked),
          _field('roleContribution', '역할 기여', _contribution),
          _field('fairness', '공정성', _fairness),
          _field('gradingConcern', '채점 우려 (선택)', _concern),
          PlayerButton(
            '본인 피드백 제출',
            onPressed: widget.enabled
                ? () => widget.onFeedback({
                    'blockedAt': _blocked.text,
                    'roleContribution': _contribution.text,
                    'fairness': _fairness.text,
                    'gradingConcern': _concern.text,
                  })
                : null,
          ),
        ],
        if (available) ...[
          Text('최종 점수: ${result['finalScore']}'),
          if (result['scoreSummary']
              case final Map<String, dynamic> summary) ...[
            Text(
              '기본 점수 ${summary['baseScore']} · 오답 ${summary['wrongCount']} · 감점 ${summary['penalty']}',
            ),
            for (final category
                in summary['categories'] as List<Map<String, dynamic>>)
              Text(
                '${category['category']}: ${category['score']}/${category['maxScore']}',
              ),
          ],
          const PlayerNotice('아래 제출본과 해설에는 사건의 정답이 포함될 수 있습니다.'),
          PlayerButton(
            _revealed ? '해설 닫기' : '제출본과 해설 열기',
            onPressed: () => setState(() => _revealed = !_revealed),
            secondary: true,
          ),
          if (_revealed) ...[
            for (final report
                in result['reports'] as List<Map<String, dynamic>>)
              Card(
                color: PlayerTheme.surface,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '제출 ${report['submitNo']} · 기본 점수 ${report['baseScore']}',
                    ),
                    for (final entry
                        in (report['report'] as Map<String, dynamic>).entries)
                      Text('${entry.key}: ${entry.value ?? ''}'),
                  ],
                ),
              ),
            if (result['revealText'] is String)
              Text(result['revealText'] as String),
          ],
        ] else if (result['resultPhase'] == 'UNAVAILABLE')
          PlayerNotice(
            result['outcome'] == 'FORFEIT'
                ? '포기로 조사가 종료되었습니다. 점수와 정답 해설은 제공되지 않습니다.'
                : '시스템 오류로 조사가 종료되었습니다. 점수와 정답 해설은 제공되지 않습니다.',
          )
        else if (result['resultPhase'] == 'AWAITING_FEEDBACK' &&
            result['feedbackSubmitted'] == true)
          const PlayerNotice('피드백을 확인하는 중입니다. 서버 결과를 다시 조회해 주세요.'),
      ],
    );
  }
}
