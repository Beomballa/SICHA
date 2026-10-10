import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';

import '../core/player_api.dart';

class AccessCredentials {
  const AccessCredentials(this.token, this.expiresAt);
  final String token;
  final DateTime expiresAt;
}

abstract class AuthSession {
  Future<AccessCredentials?> restore(PlayerApi api);
  Future<AccessCredentials> login(PlayerApi api, String email, String password);
  Future<AccessCredentials?> refresh(PlayerApi api);
  Future<void> logout(PlayerApi api);
  Future<void> clearLocal();
  bool get current;
  Stream<void> get invalidations;
  void dispose();
}

class AuthSessionBlocked extends StateError {
  AuthSessionBlocked() : super('인증 결과를 확인할 수 없습니다. 다시 로그인해 주세요.');
}

/// 네이티브 Keychain 없이 인증 상태를 검증할 수 있는 저장소 경계다.
abstract class AuthTokenStorage {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// 설치 표지 확인·앱 인증 공간 삭제·표지 기록을 하나의 실행으로 직렬화한다.
class AuthInstallationGuard {
  AuthInstallationGuard({
    required this._readMarker,
    required this._eraseCredentials,
    required this._writeMarker,
  });

  final Future<bool> Function() _readMarker;
  final Future<void> Function() _eraseCredentials;
  final Future<void> Function() _writeMarker;
  Future<void>? _flight;
  bool _ready = false;
  bool _eraseRequired = false;

  /// 성공한 설치 확인 뒤에만 자격 저장소 접근을 허용한다.
  Future<void> ensureInstalled() {
    if (_ready) return Future<void>.value();
    return _flight ??= _check().whenComplete(() => _flight = null);
  }

  Future<void> _check() async {
    if (!_eraseRequired && await _readMarker()) {
      _ready = true;
      return;
    }
    _eraseRequired = true;
    await _eraseCredentials();
    await _writeMarker();
    _eraseRequired = false;
    _ready = true;
  }
}

final _uuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
);

/// 브라우저 잠금과 비밀 없는 영속 표지의 경계다. 테스트 주입은 제품 대체 경로가 아니다.
abstract class WebAuthCoordinator {
  void validate(PlayerApi api);
  Future<T> exclusive<T>(Future<T> Function() action);
  String? read();
  void write(String marker);
  Stream<void> get changes;
  void dispose();
}

class WebAuthMarker {
  const WebAuthMarker(this.epoch, this.operation, [this.attempt]);
  final String epoch;
  final String operation;
  final String? attempt;

  static WebAuthMarker? parse(String? text) {
    if (text == null) return null;
    final value = jsonDecode(text);
    if (value is! Map<String, dynamic> ||
        value['format'] is! int ||
        value['format'] != 1 ||
        value['epoch'] is! String ||
        !_uuid.hasMatch(value['epoch'] as String) ||
        !const [
          'login',
          'refresh',
          'logout',
          'ready',
          'blocked',
        ].contains(value['operation'])) {
      throw AuthSessionBlocked();
    }
    final operation = value['operation'] as String;
    final attempt = value['attempt'];
    final inFlight = operation != 'ready';
    if (value.length != (inFlight ? 4 : 3) ||
        !value.containsKey('format') ||
        !value.containsKey('epoch') ||
        !value.containsKey('operation') ||
        (inFlight && (attempt is! String || !_uuid.hasMatch(attempt)))) {
      throw AuthSessionBlocked();
    }
    return WebAuthMarker(
      value['epoch'] as String,
      operation,
      attempt as String?,
    );
  }

  String encode() => jsonEncode({
    'format': 1,
    'epoch': epoch,
    'operation': operation,
    if (attempt != null) 'attempt': attempt,
  });
}

/// 서버가 공개한 일곱 필드만 수락하며 쿠키 세대와 응답 세대를 결합한다.
AccessCredentials parseWebAccess(Map<String, dynamic> value, String epoch) {
  const fields = {
    'tokenType',
    'accessToken',
    'accessExpiresAt',
    'sessionKey',
    'sessionAbsoluteExpiresAt',
    'webEpoch',
    'requestId',
  };
  if (value.length != fields.length ||
      !fields.every(value.containsKey) ||
      value['tokenType'] != 'Bearer' ||
      value['webEpoch'] != epoch ||
      value['accessToken'] is! String ||
      !RegExp(r'^[A-Za-z0-9_-]{43}$')
          .hasMatch(value['accessToken'] as String) ||
      !['sessionKey', 'requestId', 'webEpoch'].every(
        (key) => value[key] is String && _uuid.hasMatch(value[key] as String),
      ) ||
      value['accessExpiresAt'] is! String ||
      value['sessionAbsoluteExpiresAt'] is! String) {
    throw const FormatException('인증 응답 오류');
  }
  final until = DateTime.parse(value['accessExpiresAt'] as String);
  final absolute = DateTime.parse(value['sessionAbsoluteExpiresAt'] as String);
  if (!until.isUtc ||
      !absolute.isUtc ||
      !until.isAfter(DateTime.now()) ||
      until.isAfter(absolute)) {
    throw const FormatException('인증 응답 오류');
  }
  return AccessCredentials(value['accessToken'] as String, until);
}

/// 쿠키는 읽지 않는다. 모든 인증 명령은 원점 잠금과 영속 불확실성 표지로 보호한다.
class CookieAuthSession implements AuthSession {
  CookieAuthSession(this.coordinator) {
    _subscription = coordinator.changes.listen((_) {
      if (!current) _invalidations.add(null);
    });
  }

  final WebAuthCoordinator coordinator;
  final _invalidations = StreamController<void>.broadcast(sync: true);
  late final StreamSubscription<void> _subscription;
  String? _epoch;
  bool _active = false;

  @override
  Stream<void> get invalidations => _invalidations.stream;

  @override
  bool get current {
    if (!_active || _epoch == null) return false;
    try {
      final marker = WebAuthMarker.parse(coordinator.read());
      // 정상 refresh는 같은 family의 기존 access를 폐기하지 않는다.
      // 다른 탭의 직렬 회전 중에도 현재 계정을 유지하되 blocked/logout/epoch 변경은 차단한다.
      return marker?.epoch == _epoch &&
          (marker?.operation == 'ready' || marker?.operation == 'refresh');
    } catch (_) {
      return false;
    }
  }

  void _write(WebAuthMarker marker) {
    final text = marker.encode();
    coordinator.write(text);
    if (coordinator.read() != text) throw AuthSessionBlocked();
  }

  void _invalidate() {
    _active = false;
    _invalidations.add(null);
  }

  @override
  Future<AccessCredentials?> restore(PlayerApi api) => refresh(api);

  @override
  Future<AccessCredentials?> refresh(PlayerApi api) {
    coordinator.validate(api);
    return coordinator.exclusive(() async {
      WebAuthMarker? marker;
      try {
        marker = WebAuthMarker.parse(coordinator.read());
      } catch (_) {
        _invalidate();
        throw AuthSessionBlocked();
      }
      if (marker != null && marker.operation != 'ready') {
        _invalidate();
        throw AuthSessionBlocked();
      }
      final epoch = marker?.epoch ?? requestKey();
      return _issue(
        api,
        epoch,
        'refresh',
        '/api/member/browser-auth/refresh',
        {},
      );
    });
  }

  Future<AccessCredentials?> _issue(
    PlayerApi api,
    String epoch,
    String operation,
    String path,
    Map<String, dynamic> body,
  ) async {
    final attempt = requestKey();
    if (operation != 'refresh') _active = false;
    try {
      _write(WebAuthMarker(epoch, operation, attempt));
      final value = await api.browserPost(path, body, epoch: epoch);
      final pending = WebAuthMarker.parse(coordinator.read());
      if (pending?.epoch != epoch ||
          pending?.attempt != attempt ||
          pending?.operation != operation) {
        throw AuthSessionBlocked();
      }
      if (value == null && operation != 'refresh') {
        throw const FormatException('인증 응답 오류');
      }
      final credentials = value == null ? null : parseWebAccess(value, epoch);
      _write(WebAuthMarker(epoch, 'ready'));
      _epoch = epoch;
      _active = credentials != null;
      if (credentials == null) _invalidations.add(null);
      return credentials;
    } catch (_) {
      // 요청 여부나 서버 결과를 추측하지 않는다. 실패한 표지는 재시도할 수 없다.
      _invalidate();
      try {
        final marker = WebAuthMarker.parse(coordinator.read());
        if (marker?.epoch == epoch && marker?.attempt == attempt) {
          _write(WebAuthMarker(epoch, 'blocked', attempt));
        }
      } catch (_) {}
      throw AuthSessionBlocked();
    }
  }

  Future<void> _revoke(
    PlayerApi api,
    String epoch, {
    bool recovery = false,
  }) async {
    final attempt = requestKey();
    _write(WebAuthMarker(epoch, 'logout', attempt));
    try {
      final value = await api.browserPost('/api/member/browser-auth/logout', {
        'requestKey': requestKey(),
      }, epoch: epoch);
      if (value == null ||
          value.length != 2 ||
          !value.containsKey('state') ||
          !value.containsKey('requestId') ||
          !const ['LOGGED_OUT', 'NO_SESSION'].contains(value['state']) ||
          value['requestId'] is! String ||
          !_uuid.hasMatch(value['requestId'] as String)) {
        throw const FormatException('로그아웃 응답 오류');
      }
    } on DioException catch (error) {
      // 확정적인 무효 증명만 새 로그인 회복에 사용할 수 있다. 5xx는 불확실하다.
      if (!recovery ||
          error.response?.statusCode != 401 ||
          error.response?.data is! Map ||
          (error.response!.data as Map)['code'] != 'MEMBER_AUTH_REQUIRED') {
        rethrow;
      }
    }
    final marker = WebAuthMarker.parse(coordinator.read());
    if (marker?.epoch != epoch ||
        marker?.attempt != attempt ||
        marker?.operation != 'logout') {
      throw AuthSessionBlocked();
    }
  }

  @override
  Future<AccessCredentials> login(
    PlayerApi api,
    String email,
    String password,
  ) {
    coordinator.validate(api);
    _invalidate();
    return coordinator.exclusive(() async {
      String epoch;
      try {
        epoch = WebAuthMarker.parse(coordinator.read())?.epoch ?? requestKey();
      } catch (_) {
        // 명시적인 로그인만 손상된 표지를 복구할 수 있고 먼저 쿠키를 회수한다.
        epoch = requestKey();
      }
      try {
        await _revoke(api, epoch, recovery: true);
        return (await _issue(
          api,
          requestKey(),
          'login',
          '/api/member/browser-auth/login/local',
          {'email': email, 'password': password},
        ))!;
      } catch (_) {
        _active = false;
        throw AuthSessionBlocked();
      }
    });
  }

  @override
  Future<void> logout(PlayerApi api) {
    coordinator.validate(api);
    _invalidate();
    return coordinator.exclusive(() async {
      final epoch = requestKey();
      try {
        await _revoke(api, epoch);
        _write(WebAuthMarker(epoch, 'ready'));
        _epoch = epoch;
      } catch (_) {
        throw AuthSessionBlocked();
      }
    });
  }

  @override
  Future<void> clearLocal() async => _invalidate();

  @override
  void dispose() {
    _subscription.cancel();
    coordinator.dispose();
    _invalidations.close();
  }
}
