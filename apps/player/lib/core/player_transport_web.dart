import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:web/web.dart' as web;

import 'player_api.dart' show PlayerConfigurationError;

/// 같은 HTTPS origin에서만 동작하는 Fetch 전송을 설치한다.
/// endpoint는 경로·query·fragment 없는 정규 HTTPS origin이어야 한다.
void configurePlayerTransport(Dio dio, String endpoint) {
  final origin = _canonicalOrigin(endpoint);
  _checkBrowser(origin);
  dio.httpClientAdapter = PlayerFetchAdapter(dio, origin);
}

String _canonicalOrigin(String value) {
  final uri = Uri.tryParse(value);
  if (uri == null ||
      uri.scheme != 'https' ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      (uri.path.isNotEmpty && uri.path != '/') ||
      (value != uri.origin && value != '${uri.origin}/')) {
    throw PlayerConfigurationError();
  }
  return uri.origin;
}

void _checkBrowser(String origin) {
  if (!web.window.isSecureContext ||
      web.window.location.protocol != 'https:' ||
      web.window.location.origin != origin ||
      web.window.navigator.serviceWorker.controller != null) {
    throw PlayerConfigurationError();
  }
}

/// 쿠키 인증 3개 POST와 Bearer 회원 확인·초대·조사만 허용하며 redirect를 따라가지 않는다.
/// Dio의 직렬화된 본문은 UTF-8 JSON으로 검증하고 제한 초과는 잘라내지 않고 거부한다.
class PlayerFetchAdapter implements HttpClientAdapter {
  PlayerFetchAdapter(this._dio, this._origin);

  final Dio _dio;
  final String _origin;
  final Set<web.AbortController> _active = {};
  bool _closed = false;
  static const _authPaths = {
    '/api/member/browser-auth/login/local',
    '/api/member/browser-auth/refresh',
    '/api/member/browser-auth/logout',
  };
  static final _epoch = RegExp(
    r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
  );
  static const _requestLimit = 16 * 1024;
  static const _responseLimit = 1024 * 1024;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    // 본문 수집 중 호출자가 원래 options를 바꿔도 URL·헤더·credentials를 섞지 않는다.
    options = options.copyWith(
      headers: Map<String, dynamic>.unmodifiable(options.headers),
      queryParameters: Map<String, dynamic>.unmodifiable(
        options.queryParameters,
      ),
    );
    final cookieAuth = _validate(options);
    final controller = web.AbortController();
    _active.add(controller);
    final stopped = Completer<void>();
    StreamIterator<Uint8List>? requestIterator;
    Timer? timer;
    var finished = false;
    var responseConsumed = false;

    void stop(Object error) {
      if (finished || stopped.isCompleted) return;
      controller.abort();
      stopped.completeError(error, StackTrace.current);
    }

    Future<T> wait<T>(Future<T> work, Duration? requested, Duration maximum) {
      timer?.cancel();
      final duration =
          requested != null && requested > Duration.zero && requested < maximum
          ? requested
          : maximum;
      timer = Timer(
        duration,
        () => stop(TimeoutException('브라우저 요청 시간이 초과되었습니다.', duration)),
      );
      return Future.any<T>([
        work,
        stopped.future.then<T>((_) => throw StateError('요청이 취소되었습니다.')),
      ]);
    }

    cancelFuture?.then((_) => stop(StateError('요청이 취소되었습니다.')));
    try {
      final headers = web.Headers();
      for (final entry in options.headers.entries) {
        // Dio가 계산한 길이는 본문 검증에만 쓰고 브라우저 금지 헤더로 전송하지 않는다.
        if (entry.key.toLowerCase() == 'content-length') continue;
        headers.set(entry.key, entry.value.toString());
      }
      headers.set('X-Sicha-Player-Web', '1');
      JSString? body;
      if (requestStream != null) {
        requestIterator = StreamIterator(requestStream);
        final bytes = await wait(
          _requestBytes(requestIterator),
          options.sendTimeout,
          const Duration(seconds: 10),
        );
        final length = options.headers.entries
            .where((entry) => entry.key.toLowerCase() == 'content-length')
            .map((entry) => entry.value as String)
            .firstOrNull;
        if (length != null && int.parse(length) != bytes.length) {
          throw const FormatException('요청 본문 길이가 일치하지 않습니다.');
        }
        final text = utf8.decode(bytes);
        if (jsonDecode(text) is! Map<String, dynamic>) {
          throw const FormatException('요청 형식이 올바르지 않습니다.');
        }
        body = text.toJS;
      }
      // 본문 수집 중 설정이나 SW 제어가 바뀌어도 비밀을 보내지 않는다.
      _validate(options);
      // Fetch는 업로드 완료와 연결 완료를 분리하지 않으므로 두 제한 중 짧은 값을 쓴다.
      var dispatchTimeout = options.connectTimeout;
      final sendTimeout = options.sendTimeout;
      if (sendTimeout != null &&
          sendTimeout > Duration.zero &&
          (dispatchTimeout == null ||
              dispatchTimeout <= Duration.zero ||
              sendTimeout < dispatchTimeout)) {
        dispatchTimeout = sendTimeout;
      }
      final response = await wait(
        web.window
            .fetch(
              options.path.toJS,
              web.RequestInit(
                method: options.method,
                headers: headers,
                body: body,
                mode: 'same-origin',
                redirect: 'error',
                cache: 'no-store',
                credentials: cookieAuth ? 'same-origin' : 'omit',
                signal: controller.signal,
              ),
            )
            .toDart,
        dispatchTimeout,
        const Duration(seconds: 10),
      );
      if (response.redirected ||
          response.status < 200 ||
          response.status > 599) {
        throw StateError('브라우저 응답을 확인할 수 없습니다.');
      }
      final responseHeaders = <String, List<String>>{};
      // Web Headers의 표준 forEach는 생성 타입에 없어 최소 interop 선언을 사용한다.
      _eachHeader(
        response.headers,
        ((JSString value, JSString key) {
          responseHeaders[key.toDart] = [value.toDart];
        }).toJS,
      );
      final bytes = await wait(
        _responseBytes(response),
        options.receiveTimeout,
        const Duration(seconds: 15),
      );
      if (bytes.isNotEmpty) {
        // Dio 변환기가 잘못된 UTF-8을 대체 문자로 받아들이지 않도록 먼저 검증한다.
        if (response.status == 204 ||
            (response.headers.get('content-type') ?? '')
                    .toLowerCase()
                    .split(';')
                    .first
                    .trim() !=
                'application/json' ||
            jsonDecode(utf8.decode(bytes)) is! Map<String, dynamic>) {
          throw const FormatException('응답 형식이 올바르지 않습니다.');
        }
      } else if (response.status != 204) {
        throw const FormatException('응답 형식이 올바르지 않습니다.');
      }
      responseConsumed = true;
      return ResponseBody.fromBytes(
        bytes,
        response.status,
        headers: responseHeaders,
      );
    } on PlayerConfigurationError {
      rethrow;
    } on TimeoutException {
      rethrow;
    } catch (_) {
      // JS TypeError의 주소나 서버 본문을 호출자 오류에 반영하지 않는다.
      throw StateError('브라우저 요청을 완료하지 못했습니다.');
    } finally {
      finished = true;
      timer?.cancel();
      if (!responseConsumed) controller.abort();
      await requestIterator?.cancel();
      _active.remove(controller);
    }
  }

  /// 목록의 선택적 long 커서와 1~100 크기만 허용하고 중복·인코딩 우회는 거절한다.
  bool _invitationQuery(String query) {
    final fields = query.split('&');
    if (fields.length > 2) return false;
    var cursor = false;
    var size = false;
    for (final field in fields) {
      if (RegExp(r'^cursor=[1-9][0-9]{0,18}$').hasMatch(field) && !cursor) {
        cursor = true;
        if (BigInt.parse(field.substring(7)) >
            BigInt.parse('9223372036854775807')) {
          return false;
        }
      } else if (RegExp(r'^size=[1-9][0-9]{0,2}$').hasMatch(field) && !size) {
        size = true;
        if (int.parse(field.substring(5)) > 100) return false;
      } else {
        return false;
      }
    }
    return true;
  }

  bool _validate(RequestOptions options) {
    _checkBrowser(_origin);
    final cookieAuth =
        options.method == 'POST' && _authPaths.contains(options.path);
    final resource = Uri.tryParse(options.path);
    final path = options.path.split('?').first;
    final bearerRoute = _bearerRoute(options.method, path);
    if (_closed ||
        _origin != _canonicalOrigin(_dio.options.baseUrl) ||
        _origin != _canonicalOrigin(options.baseUrl) ||
        (!cookieAuth && !bearerRoute) ||
        resource == null ||
        resource.hasScheme ||
        resource.hasAuthority ||
        resource.hasFragment ||
        resource.path != path ||
        (resource.hasQuery &&
            !(options.method == 'GET' &&
                path == '/api/playtests/invitations' &&
                _invitationQuery(resource.query))) ||
        options.queryParameters.isNotEmpty ||
        options.uri.toString() != '$_origin${options.path}') {
      throw PlayerConfigurationError();
    }
    final headers = <String, dynamic>{};
    for (final entry in options.headers.entries) {
      final name = entry.key.toLowerCase();
      if (headers.containsKey(name) ||
          !{
            'accept',
            'content-type',
            'content-length',
            'authorization',
            'x-sicha-player-web',
            'x-sicha-player-web-epoch',
          }.contains(name) ||
          entry.value is! String) {
        throw PlayerConfigurationError();
      }
      headers[name] = entry.value;
    }
    final length = headers['content-length'] as String?;
    if (length != null &&
        (options.method != 'POST' ||
            !RegExp(r'^(0|[1-9][0-9]*)$').hasMatch(length) ||
            int.tryParse(length) == null ||
            int.parse(length) > _requestLimit)) {
      throw PlayerConfigurationError();
    }
    if (headers.containsKey('x-sicha-player-web') &&
        headers['x-sicha-player-web'] != '1') {
      throw PlayerConfigurationError();
    }
    if (cookieAuth &&
        (headers.containsKey('authorization') ||
            !_epoch.hasMatch(
              headers['x-sicha-player-web-epoch'] as String? ?? '',
            ) ||
            headers['content-type'] != 'application/json')) {
      throw PlayerConfigurationError();
    }
    if (bearerRoute &&
        (headers.containsKey('x-sicha-player-web-epoch') ||
            !RegExp(r'^Bearer [A-Za-z0-9_-]{43}$')
                .hasMatch(headers['authorization'] as String? ?? '') ||
            (options.method == 'POST' &&
                headers['content-type'] != 'application/json'))) {
      throw PlayerConfigurationError();
    }
    return cookieAuth;
  }

  /// 정규 UUID v4와 고정 메서드만 허용하며 보고서·채점·결과는 웹 3단계까지 닫는다.
  bool _bearerRoute(String method, String path) {
    const key =
        r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}';
    if (method == 'GET') {
      return const {
            '/api/member/auth/me',
            '/api/playtests/identity',
            '/api/playtests/invitations',
          }.contains(path) ||
          RegExp('^/api/playtests/$key(?:/policy-notice|/materials)?\$')
              .hasMatch(path);
    }
    return method == 'POST' &&
        RegExp(
          '^/api/playtests/$key/(?:accept|ready|start|heartbeat|hints/[1-3]/open)\$',
        ).hasMatch(path);
  }

  Future<Uint8List> _requestBytes(StreamIterator<Uint8List> iterator) async {
    final bytes = BytesBuilder(copy: false);
    while (await iterator.moveNext()) {
      final chunk = iterator.current;
      if (bytes.length + chunk.length > _requestLimit) {
        throw StateError('요청 크기가 제한을 초과했습니다.');
      }
      bytes.add(chunk);
    }
    return bytes.takeBytes();
  }

  Future<Uint8List> _responseBytes(web.Response response) async {
    final stream = response.body;
    if (stream == null) return Uint8List(0);
    final reader = web.ReadableStreamDefaultReader(stream);
    final bytes = BytesBuilder(copy: false);
    try {
      while (true) {
        final result = await reader.read().toDart;
        if (result.done) break;
        final chunk = (result.value as JSUint8Array).toDart;
        if (bytes.length + chunk.length > _responseLimit) {
          throw StateError('응답 크기가 제한을 초과했습니다.');
        }
        bytes.add(chunk);
      }
      return bytes.takeBytes();
    } finally {
      reader.releaseLock();
    }
  }

  @override
  void close({bool force = false}) {
    _closed = true;
    if (force) {
      for (final controller in _active) {
        controller.abort();
      }
    }
  }
}

extension type _HeaderIteration._(JSObject _) implements JSObject {
  external void forEach(JSFunction callback);
}

void _eachHeader(web.Headers headers, JSFunction callback) =>
    _HeaderIteration._(headers).forEach(callback);
