import 'dart:js_interop';

import 'package:web/web.dart' as web;

/// 이 화면의 개인 편집기 DOM만 동기 폐기한다. 입력 이벤트는 만들지 않는다.
void Function(String)? get privateEditorEraser => _erase;

/// 현재 서버 증명 뒤의 새 편집기만 활성화한다. 값은 Flutter 연결에서 받고 주입하지 않는다.
bool Function(String)? get privateEditorFocuser => _focus;

/// 정해진 편집기 종류와 정규 UUID만 선택자로 전달한다.
void _validate(String identifier) {
  if (!RegExp(
    r'^sicha-private-(note|report|feedback)-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
  ).hasMatch(identifier)) {
    throw StateError('개인 편집기 렌더러 식별자가 올바르지 않습니다.');
  }
}

void _erase(String identifier) {
  _validate(identifier);
  final elements = web.document.querySelectorAll(
    '[flt-semantics-identifier="$identifier"] textarea[data-semantics-role="text-field"],'
    'textarea[flt-semantics-identifier="$identifier"][data-semantics-role="text-field"]',
  );
  for (var index = 0; index < elements.length; index++) {
    final node = elements.item(index);
    if (node != null && node.isA<web.HTMLTextAreaElement>()) {
      final editor = node as web.HTMLTextAreaElement;
      editor.value = '';
      editor.defaultValue = '';
      editor.setSelectionRange(0, 0);
      editor.blur();
    }
  }
}

bool _focus(String identifier) {
  _validate(identifier);
  final elements = web.document.querySelectorAll(
    '[flt-semantics-identifier="$identifier"] textarea[data-semantics-role="text-field"],'
    'textarea[flt-semantics-identifier="$identifier"][data-semantics-role="text-field"]',
  );
  for (var index = elements.length - 1; index >= 0; index--) {
    final node = elements.item(index);
    if (node != null && node.isA<web.HTMLTextAreaElement>()) {
      final editor = node as web.HTMLTextAreaElement;
      editor.focus();
      return editor.isConnected && web.document.activeElement == editor;
    }
  }
  return false;
}
