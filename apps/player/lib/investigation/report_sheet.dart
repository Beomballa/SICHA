import 'package:flutter/material.dart';

import '../core/player_theme.dart';

/// 공동 초안의 다섯 필드를 편집하되 저장·동의는 현재 서버 조회본으로만 허용한다.
class ReportSheet extends StatefulWidget {
  const ReportSheet({
    super.key,
    required this.report,
    required this.persons,
    required this.proposal,
    required this.submissionState,
    required this.enabled,
    required this.edited,
    required this.staleDraft,
    required this.onChanged,
    required this.onDiscard,
    required this.onSave,
    required this.onPropose,
    required this.onRespond,
  });

  final Map<String, dynamic> report;
  final List<Map<String, dynamic>> persons;
  final Map<String, dynamic>? proposal;
  final String submissionState;
  final bool enabled;
  final bool edited;
  final bool staleDraft;
  final void Function(Map<String, dynamic> report) onChanged;
  final VoidCallback onDiscard;
  final void Function(Map<String, dynamic> report) onSave;
  final VoidCallback onPropose;
  final void Function(String decision) onRespond;

  @override
  State<ReportSheet> createState() => _ReportSheetState();
}

class _ReportSheetState extends State<ReportSheet> {
  late Map<String, dynamic> _report;

  @override
  void initState() {
    super.initState();
    _report = Map<String, dynamic>.from(widget.report);
  }

  /// 서버 갱신은 편집 중인 입력을 덮지 않고, 편집 전 조회본만 따라간다.
  @override
  void didUpdateWidget(covariant ReportSheet oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.edited && !identical(oldWidget.report, widget.report)) {
      _report = Map<String, dynamic>.from(widget.report);
    }
  }

  /// 입력 변경은 화면 소유자에게 복사해 조회 실패와 재조회 사이에도 보존한다.
  void _change(String field, String? value) {
    _report = {..._report, field: value};
    widget.onChanged(Map<String, dynamic>.from(_report));
  }

  bool get _valid =>
      _report['culpritCode'] == null ||
      widget.persons.any((person) => person['code'] == _report['culpritCode']);

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('공동 보고서', style: Theme.of(context).textTheme.titleLarge),
      const PlayerNotice(
        '공동 초안은 두 참가자가 함께 수정합니다. 저장하면 기존 고정 제안은 무효화되며 다시 제안해야 합니다.',
      ),
      if (widget.staleDraft)
        const PlayerNotice(
          '다른 수정으로 서버 초안이 변경되었습니다. 현재 입력은 보존되지만 저장할 수 없습니다. 서버 초안을 확인해 주세요.',
        ),
      if (!_valid)
        const PlayerNotice('현재 초안의 지목 인물이 배정된 인물 목록에 없습니다. 인물을 다시 선택해 주세요.'),
      DropdownButtonFormField<String>(
        initialValue: _valid ? _report['culpritCode'] as String? : null,
        decoration: const InputDecoration(labelText: '지목 인물'),
        items: [
          const DropdownMenuItem<String>(value: null, child: Text('미선택')),
          for (final person in widget.persons)
            DropdownMenuItem<String>(
              value: person['code'] as String,
              child: Text('${person['name']} (${person['code']})'),
            ),
        ],
        onChanged: widget.enabled && widget.submissionState != 'PENDING'
            ? (value) => _change('culpritCode', value)
            : null,
      ),
      for (final field in const {
        'method': '방법',
        'time': '시간',
        'motive': '동기',
        'evidence': '근거',
      }.entries)
        Card(
          color: PlayerTheme.surface,
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: TextFormField(
              key: ValueKey('${field.key}:${widget.report[field.key]}'),
              initialValue: _report[field.key] as String,
              maxLines: 3,
              enabled: widget.enabled && widget.submissionState != 'PENDING',
              decoration: InputDecoration(labelText: field.value),
              onChanged: (value) => _change(field.key, value),
            ),
          ),
        ),
      if (widget.edited) ...[
        PlayerButton(
          '서버 초안으로 되돌리기',
          onPressed: widget.onDiscard,
          secondary: true,
        ),
        PlayerButton(
          '공동 초안 저장',
          onPressed:
              widget.enabled &&
                  !widget.staleDraft &&
                  widget.submissionState != 'PENDING' &&
                  _valid &&
                  const ['method', 'time', 'motive', 'evidence'].every(
                    (field) => (_report[field] as String).runes.length <= 5000,
                  )
              ? () => widget.onSave(Map<String, dynamic>.from(_report))
              : null,
        ),
      ],
      Text('제출 상태: ${widget.submissionState}'),
      if (widget.proposal != null) ...[
        Text(
          '고정 제안: ${widget.proposal!['state']} · 초안 수정번호 ${widget.proposal!['sourceRev']} · 제안 역할 ${widget.proposal!['proposerSlot']}',
        ),
        const PlayerNotice(
          '수락은 현재 서버에 고정된 제안에 대한 동의입니다. 아래 초안은 제출 사본의 보증이 아닙니다.',
        ),
        if (widget.proposal!['canAccept'] == true)
          PlayerButton(
            '제안 수락 및 판정 접수',
            onPressed: widget.enabled && !widget.edited
                ? () => widget.onRespond('ACCEPT')
                : null,
          ),
        if (widget.proposal!['canReject'] == true)
          PlayerButton(
            '제안 거절',
            onPressed: widget.enabled ? () => widget.onRespond('REJECT') : null,
            secondary: true,
          ),
        if (widget.proposal!['canWithdraw'] == true)
          PlayerButton(
            '제안 철회',
            onPressed: widget.enabled
                ? () => widget.onRespond('WITHDRAW')
                : null,
            secondary: true,
          ),
      ] else if (widget.submissionState == 'NONE')
        PlayerButton(
          '현재 초안 제안',
          onPressed:
              widget.enabled &&
                  !widget.edited &&
                  _valid &&
                  _report['culpritCode'] != null &&
                  const ['method', 'time', 'motive', 'evidence'].every(
                    (field) => (_report[field] as String).trim().isNotEmpty,
                  )
              ? widget.onPropose
              : null,
        ),
    ],
  );
}
