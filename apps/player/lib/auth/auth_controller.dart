import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show ProviderException;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../core/player_api.dart';

const _baseUrl = String.fromEnvironment('PLAYER_API_BASE_URL');
final apiProvider = Provider<PlayerApi>((ref) => PlayerApi(_baseUrl));
final authProvider = NotifierProvider<AuthController, AuthSnapshot>(
  AuthController.new,
);

const _ios = IOSOptions(
  accountName: 'sicha.player.auth',
  accessibility: KeychainAccessibility.unlocked_this_device,
  synchronizable: false,
);

/// 네이티브 Keychain 없이 인증 상태를 검증할 수 있는 저장소 경계다.
abstract class AuthTokenStorage {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// 설치 표지 확인·앱 인증 공간 삭제·표지 기록을 하나의 실행으로 직렬화한다.
/// 콜백 오류는 호출자에게 전달하며 같은 소유자의 다음 호출에서 재시도한다.
/// readMarker는 유효한 표지만 true로, eraseCredentials는 앱 인증 전체를
/// 정리하고, writeMarker는 비밀이 없는 표지의 기록 확인까지 수행한다.
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
    // 표지 쓰기가 실패했지만 일부 반영되어도 같은 소유자는 재삭제한다.
    _eraseRequired = true;
    await _eraseCredentials();
    await _writeMarker();
    _eraseRequired = false;
    _ready = true;
  }
}

class _KeychainTokenStorage implements AuthTokenStorage {
  static const _storage = FlutterSecureStorage(iOptions: _ios);
  static const _installationChannel = MethodChannel(
    'sicha.player/auth_installation',
  );
  static final _installation = AuthInstallationGuard(
    readMarker: () async {
      if (kIsWeb || defaultTargetPlatform != TargetPlatform.iOS) {
        throw UnsupportedError('네이티브 인증은 iOS에서만 지원합니다.');
      }
      final marker = await _installationChannel.invokeMethod<bool>(
        'readMarker',
      );
      if (marker == null) throw StateError('설치 표지를 확인하지 못했습니다.');
      return marker;
    },
    eraseCredentials: () async {
      await _storage.deleteAll(iOptions: _ios);
      if ((await _storage.readAll(iOptions: _ios)).isNotEmpty) {
        throw StateError('앱 인증 저장소를 정리하지 못했습니다.');
      }
    },
    writeMarker: () async {
      if (await _installationChannel.invokeMethod<bool>('writeMarker') !=
          true) {
        throw StateError('설치 표지를 확인하지 못했습니다.');
      }
    },
  );

  @override
  Future<String?> read(String key) async {
    await _installation.ensureInstalled();
    return _storage.read(key: key, iOptions: _ios);
  }

  @override
  Future<void> write(String key, String value) async {
    await _installation.ensureInstalled();
    await _storage.write(key: key, value: value, iOptions: _ios);
  }

  @override
  Future<void> delete(String key) async {
    await _installation.ensureInstalled();
    await _storage.delete(key: key, iOptions: _ios);
  }
}

class AuthSnapshot {
  const AuthSnapshot(this.phase, {this.nickname, this.error});
  final AuthPhase phase;
  final String? nickname;
  final String? error;
}

enum AuthPhase { checking, signedOut, signedIn, recovery, loginRequired }

class AuthController extends Notifier<AuthSnapshot> {
  AuthController({AuthTokenStorage? storage})
    : _storage = storage ?? _KeychainTokenStorage();

  final AuthTokenStorage _storage;
  String? _access;
  DateTime? _accessUntil;
  Future<bool>? _refreshFlight;
  Future<void>? _loginFlight;
  Future<void>? _logoutFlight;
  int _generation = 0;
  bool _started = false;

  int get generation => _generation;
  bool get signedIn => state.phase == AuthPhase.signedIn;
  String get _key =>
      'player:${Uri.encodeComponent(ref.read(apiProvider).endpoint)}:refresh';
  String get _marker => '$_key:rotating';

  @override
  AuthSnapshot build() => const AuthSnapshot(AuthPhase.checking);

  Future<void> restore() async {
    if (_started) return;
    _started = true;
    final generation = _generation;
    try {
      ref.read(
        apiProvider,
      ); // Reject missing or non-HTTPS configuration before any auth attempt.
      if (await _storage.read(_marker) != null) {
        await _storage.delete(_key);
        if (generation != _generation) return;
        state = const AuthSnapshot(AuthPhase.loginRequired);
        return;
      }
      if (await _storage.read(_key) == null) {
        if (generation != _generation) return;
        state = const AuthSnapshot(AuthPhase.signedOut);
        return;
      }
      if (generation != _generation) return;
      if (await _refresh()) await _loadMe();
    } catch (error) {
      if (generation != _generation) return;
      _access = null;
      final cause = error is ProviderException ? error.exception : error;
      state = AuthSnapshot(
        AuthPhase.recovery,
        error: cause is PlayerConfigurationError
            ? cause.message.toString()
            : '저장된 인증을 확인할 수 없습니다. 잠금 해제와 연결 상태를 확인한 뒤 다시 시도해 주세요.',
      );
    }
  }

  Future<void> login(String email, String password) async {
    while (_logoutFlight != null) {
      await _logoutFlight;
    }
    await (_loginFlight ??= _login(
      email,
      password,
    ).whenComplete(() => _loginFlight = null));
  }

  Future<void> _login(String email, String password) async {
    final generation = ++_generation;
    _access = null;
    state = const AuthSnapshot(AuthPhase.signedOut);
    try {
      await _refreshFlight;
      await _storage.delete(_key);
      await _storage.delete(_marker);
      if (generation != _generation) return;
      final tokens = await ref.read(apiProvider).post(
        '/api/member/auth/login/local',
        {'email': email, 'password': password},
      );
      if (generation != _generation) return;
      await _storeTokens(tokens);
      if (generation != _generation) return;
      await _loadMe();
    } catch (error) {
      if (generation == _generation) {
        _access = null;
        try {
          await _storage.delete(_key);
        } catch (_) {
          state = const AuthSnapshot(
            AuthPhase.loginRequired,
            error: '인증 저장소를 정리하지 못했습니다.',
          );
          return;
        }
        state = AuthSnapshot(AuthPhase.signedOut, error: playerError(error));
      }
    }
  }

  Future<void> _storeTokens(Map<String, dynamic> tokens) async {
    if (tokens['tokenType'] != 'Bearer') {
      throw const FormatException('인증 응답 오류');
    }
    final access = tokens['accessToken'] as String;
    final refresh = tokens['refreshToken'] as String;
    final until = DateTime.parse(tokens['accessExpiresAt'] as String);
    await _storage.write(_key, refresh);
    _access = access;
    _accessUntil = until;
  }

  Future<bool> _refresh() =>
      _refreshFlight ??= _rotate().whenComplete(() => _refreshFlight = null);

  Future<bool> _rotate() async {
    final generation = _generation;
    var requestSent = false;
    try {
      if (await _storage.read(_marker) != null) {
        await _forget(AuthPhase.loginRequired);
        return false;
      }
      final token = await _storage.read(_key);
      if (token == null) {
        await _forget(AuthPhase.loginRequired);
        return false;
      }
      await _storage.write(_marker, '1');
      // A lost response cannot safely reuse the consumed refresh token.
      requestSent = true;
      final result = await ref.read(apiProvider).post(
        '/api/member/auth/refresh',
        {'refreshToken': token},
      );
      if (generation != _generation) return false;
      await _storeTokens(result);
      await _storage.delete(_marker);
      return generation == _generation;
    } on DioException catch (error) {
      if (!requestSent) rethrow;
      if (generation == _generation) {
        await _rotationFailed(playerError(error));
      }
      return false;
    } catch (_) {
      if (!requestSent) rethrow;
      if (generation == _generation) {
        await _rotationFailed('갱신 결과를 확인할 수 없습니다. 다시 로그인해 주세요.');
      }
      return false;
    }
  }

  /// 회전 응답이 불확실할 때 기존 토큰 재사용을 막고 회전 마커를 유지한다.
  Future<void> _rotationFailed(String message) async {
    _access = null;
    _accessUntil = null;
    await _storage.delete(_key);
    state = AuthSnapshot(AuthPhase.loginRequired, error: message);
  }

  Future<void> _forget(AuthPhase phase) async {
    _access = null;
    _accessUntil = null;
    await _storage.delete(_key);
    await _storage.delete(_marker);
    state = AuthSnapshot(phase);
  }

  Future<Map<String, dynamic>> authorizedGet(
    String path, {
    Map<String, dynamic>? query,
  }) async {
    final generation = _generation;
    if (_access == null ||
        _accessUntil == null ||
        DateTime.now().isAfter(
          _accessUntil!.subtract(const Duration(seconds: 30)),
        )) {
      if (!await _refresh()) throw StateError('로그인이 필요합니다.');
    }
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
    final access = _access!;
    try {
      final value = await ref.read(apiProvider).get(path, access, query: query);
      if (generation != _generation) throw StateError('계정이 변경되었습니다.');
      return value;
    } on DioException catch (error) {
      if (!_authorizationFailure(error) || generation != _generation) rethrow;
      if (_access == access && !await _refresh()) rethrow;
      if (_access == null || generation != _generation) rethrow;
      final value = await ref
          .read(apiProvider)
          .get(path, _access!, query: query);
      if (generation != _generation) throw StateError('계정이 변경되었습니다.');
      return value;
    }
  }

  /// 서버가 명시한 인증 실패만 읽기 재시도 대상으로 판정한다.
  bool _authorizationFailure(DioException error) {
    final status = error.response?.statusCode;
    final data = error.response?.data;
    return (status == 401 || status == 403) &&
        data is Map &&
        data['code'] == 'MEMBER_AUTH_REQUIRED';
  }

  Future<Map<String, dynamic>> authorizedPost(
    String path,
    Map<String, dynamic> body,
  ) async {
    final generation = _generation;
    if (_access == null ||
        _accessUntil == null ||
        DateTime.now().isAfter(
          _accessUntil!.subtract(const Duration(seconds: 30)),
        )) {
      if (!await _refresh()) throw StateError('로그인이 필요합니다.');
    }
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
    final result = await ref
        .read(apiProvider)
        .post(path, body, access: _access!);
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
    return result;
  }

  /// 현재 세대의 Bearer로 PATCH를 한 번만 전송한다. 인증 오류나 불확실한 응답은 자동 재전송하지 않는다.
  Future<Map<String, dynamic>> authorizedPatch(
    String path,
    Map<String, dynamic> body,
  ) async {
    final generation = _generation;
    if (_access == null ||
        _accessUntil == null ||
        DateTime.now().isAfter(
          _accessUntil!.subtract(const Duration(seconds: 30)),
        )) {
      if (!await _refresh()) throw StateError('로그인이 필요합니다.');
    }
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
    final response = await ref.read(apiProvider).patch(path, body, _access!);
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
    return response;
  }

  /// 세대가 바뀐 뒤 도착한 빈 하트비트 응답도 이전 계정의 성공으로 취급하지 않는다.
  Future<void> authorizedPostNoContent(String path) async {
    final generation = _generation;
    if (_access == null ||
        _accessUntil == null ||
        DateTime.now().isAfter(
          _accessUntil!.subtract(const Duration(seconds: 30)),
        )) {
      if (!await _refresh()) throw StateError('로그인이 필요합니다.');
    }
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
    await ref.read(apiProvider).postNoContent(path, _access!);
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
  }

  Future<void> _loadMe() async {
    final generation = _generation;
    try {
      final me = await authorizedGet('/api/member/auth/me');
      if (generation == _generation) {
        state = AuthSnapshot(
          AuthPhase.signedIn,
          nickname: me['nickname'] as String,
        );
      }
    } on DioException catch (error) {
      if (generation != _generation) return;
      if (_authorizationFailure(error)) {
        await _forget(AuthPhase.loginRequired);
      } else {
        state = AuthSnapshot(AuthPhase.recovery, error: playerError(error));
      }
    } catch (_) {
      if (generation == _generation && state.phase != AuthPhase.loginRequired) {
        state = const AuthSnapshot(
          AuthPhase.recovery,
          error: '계정 확인에 실패했습니다. 다시 시도해 주세요.',
        );
      }
    }
  }

  Future<void> retryRestore() async {
    if (state.phase != AuthPhase.recovery) return;
    state = const AuthSnapshot(AuthPhase.checking);
    try {
      if (_access == null) {
        if (!await _refresh()) return;
      }
      await _loadMe();
    } catch (_) {
      state = const AuthSnapshot(
        AuthPhase.recovery,
        error: '계정 확인에 실패했습니다. 다시 시도해 주세요.',
      );
    }
  }

  Future<void> logout() =>
      _logoutFlight ??= _logout().whenComplete(() => _logoutFlight = null);

  Future<void> _logout() async {
    ++_generation;
    final access = _access;
    final loginFlight = _loginFlight;
    _access = null;
    state = const AuthSnapshot(AuthPhase.signedOut);
    // The in-flight rotation must settle before deleting Keychain values.
    try {
      await _refreshFlight;
    } catch (_) {}
    try {
      await loginFlight;
    } catch (_) {}
    try {
      await _storage.delete(_key);
      await _storage.delete(_marker);
    } catch (_) {
      state = const AuthSnapshot(
        AuthPhase.loginRequired,
        error: '인증 저장소를 정리하지 못했습니다.',
      );
      return;
    }
    if (access != null) {
      try {
        await ref.read(apiProvider).post('/api/member/auth/logout', {
          'requestKey': requestKey(),
        }, access: access);
      } catch (_) {
        state = const AuthSnapshot(
          AuthPhase.signedOut,
          error: '기기의 인증은 삭제했지만 서버 로그아웃 결과는 확인되지 않았습니다. 자동 재전송하지 않습니다.',
        );
      }
    }
  }
}
