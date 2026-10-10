import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show ProviderException;

import '../core/player_api.dart';
import 'auth_session.dart';
import 'auth_session_native.dart'
    if (dart.library.js_interop) 'auth_session_web.dart'
    as platform;

const _baseUrl = String.fromEnvironment('PLAYER_API_BASE_URL');
final apiProvider = Provider<PlayerApi>((ref) => PlayerApi(_baseUrl));
final authProvider = NotifierProvider<AuthController, AuthSnapshot>(
  AuthController.new,
);

class AuthSnapshot {
  const AuthSnapshot(this.phase, {this.nickname, this.error});
  final AuthPhase phase;
  final String? nickname;
  final String? error;
}

enum AuthPhase { checking, signedOut, signedIn, recovery, loginRequired }

class AuthController extends Notifier<AuthSnapshot> {
  AuthController({AuthTokenStorage? storage, AuthSession? session})
    : _session = _selectSession(storage, session);

  static AuthSession _selectSession(
    AuthTokenStorage? storage,
    AuthSession? session,
  ) {
    if (storage != null && session != null) {
      throw ArgumentError('인증 경계는 하나만 지정하세요.');
    }
    return session ?? platform.createAuthSession(storage: storage);
  }

  final AuthSession _session;
  String? _access;
  DateTime? _accessUntil;
  Future<bool>? _refreshFlight;
  Future<void>? _loginFlight;
  Future<void>? _logoutFlight;
  int _generation = 0;
  bool _started = false;

  int get generation => _generation;
  bool get signedIn => state.phase == AuthPhase.signedIn;

  @override
  AuthSnapshot build() {
    final subscription = _session.invalidations.listen((_) => _checkCurrent());
    ref.onDispose(() {
      subscription.cancel();
      _session.dispose();
    });
    return const AuthSnapshot(AuthPhase.checking);
  }

  void _checkCurrent() {
    if (_access != null && !_session.current) {
      ++_generation;
      _access = null;
      _accessUntil = null;
      state = const AuthSnapshot(AuthPhase.loginRequired);
    }
  }

  void _accept(AccessCredentials credentials) {
    if (!_session.current) throw AuthSessionBlocked();
    _access = credentials.token;
    _accessUntil = credentials.expiresAt;
  }

  /// 새 프로세스의 세션 복원을 한 번 수행한다. 불확실한 회전은 자동 재시도하지 않는다.
  Future<void> restore() async {
    if (_started) return;
    _started = true;
    final generation = _generation;
    try {
      final api = ref.read(apiProvider);
      final flight = () async {
        final credentials = await _session.restore(api);
        if (generation != _generation) return false;
        if (credentials == null) {
          state = const AuthSnapshot(AuthPhase.signedOut);
          return false;
        }
        _accept(credentials);
        return true;
      }();
      _refreshFlight = flight;
      bool restored;
      try {
        restored = await flight;
      } finally {
        if (identical(_refreshFlight, flight)) _refreshFlight = null;
      }
      if (restored && generation == _generation) await _loadMe();
    } catch (error) {
      if (generation != _generation) return;
      _access = null;
      _accessUntil = null;
      final cause = error is ProviderException ? error.exception : error;
      state = AuthSnapshot(
        cause is AuthSessionBlocked
            ? AuthPhase.loginRequired
            : AuthPhase.recovery,
        error: cause is PlayerConfigurationError
            ? cause.message.toString()
            : '저장된 인증을 확인할 수 없습니다. 잠금 해제와 연결 상태를 확인한 뒤 다시 시도해 주세요.',
      );
    }
  }

  /// 사용자의 명시적 로그인만 실행하며 중복 클릭은 단일 명령으로 합친다.
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
    _accessUntil = null;
    state = const AuthSnapshot(AuthPhase.signedOut);
    try {
      try {
        await _refreshFlight;
      } catch (_) {
        // 명시적 로그인은 세션 경계의 회수 절차로 불확실성을 처리한다.
      }
      if (generation != _generation) return;
      final credentials = await _session.login(
        ref.read(apiProvider),
        email,
        password,
      );
      if (generation != _generation) return;
      _accept(credentials);
      await _loadMe();
    } catch (error) {
      if (generation != _generation) return;
      _access = null;
      _accessUntil = null;
      state = AuthSnapshot(
        error is AuthSessionBlocked
            ? AuthPhase.loginRequired
            : AuthPhase.signedOut,
        error: playerError(error),
      );
    }
  }

  Future<bool> _refresh() =>
      _refreshFlight ??= _rotate().whenComplete(() => _refreshFlight = null);

  Future<bool> _rotate() async {
    final generation = _generation;
    try {
      final credentials = await _session.refresh(ref.read(apiProvider));
      if (generation != _generation) return false;
      if (credentials == null) {
        _access = null;
        _accessUntil = null;
        state = const AuthSnapshot(AuthPhase.loginRequired);
        return false;
      }
      _accept(credentials);
      return true;
    } on AuthSessionBlocked catch (_) {
      if (generation == _generation) {
        _access = null;
        _accessUntil = null;
        state = const AuthSnapshot(
          AuthPhase.loginRequired,
          error: '갱신 결과를 확인할 수 없습니다. 다시 로그인해 주세요.',
        );
      }
      return false;
    }
  }

  Future<void> _forget(AuthPhase phase) async {
    _access = null;
    _accessUntil = null;
    await _session.clearLocal();
    state = AuthSnapshot(phase);
  }

  void _assertGeneration(int generation) {
    _checkCurrent();
    if (generation != _generation) throw StateError('계정이 변경되었습니다.');
  }

  Future<void> _ensureAccess(int generation) async {
    _assertGeneration(generation);
    if (_access == null ||
        _accessUntil == null ||
        DateTime.now().isAfter(
          _accessUntil!.subtract(const Duration(seconds: 30)),
        )) {
      if (!await _refresh()) throw StateError('로그인이 필요합니다.');
    }
    _assertGeneration(generation);
  }

  /// 명시적인 인증 실패에 한해 GET을 한 번 재시도한다.
  Future<Map<String, dynamic>> authorizedGet(
    String path, {
    Map<String, dynamic>? query,
  }) async {
    final generation = _generation;
    await _ensureAccess(generation);
    final access = _access!;
    try {
      final value = await ref.read(apiProvider).get(path, access, query: query);
      _assertGeneration(generation);
      return value;
    } on DioException catch (error) {
      _checkCurrent();
      if (!_authorizationFailure(error) || generation != _generation) rethrow;
      if (_access == access && !await _refresh()) rethrow;
      if (_access == null || generation != _generation) rethrow;
      final value = await ref
          .read(apiProvider)
          .get(path, _access!, query: query);
      _assertGeneration(generation);
      return value;
    } finally {
      _checkCurrent();
    }
  }

  bool _authorizationFailure(DioException error) {
    final status = error.response?.statusCode;
    final data = error.response?.data;
    return (status == 401 || status == 403) &&
        data is Map &&
        data['code'] == 'MEMBER_AUTH_REQUIRED';
  }

  /// 변경 요청은 한 번만 전송하며 인증 오류에도 재전송하지 않는다.
  Future<Map<String, dynamic>> authorizedPost(
    String path,
    Map<String, dynamic> body,
  ) async {
    final generation = _generation;
    await _ensureAccess(generation);
    try {
      final result = await ref
          .read(apiProvider)
          .post(path, body, access: _access!);
      _assertGeneration(generation);
      return result;
    } finally {
      _checkCurrent();
    }
  }

  /// 현재 세대의 Bearer로 PATCH를 한 번만 전송한다.
  Future<Map<String, dynamic>> authorizedPatch(
    String path,
    Map<String, dynamic> body,
  ) async {
    final generation = _generation;
    await _ensureAccess(generation);
    try {
      final result = await ref.read(apiProvider).patch(path, body, _access!);
      _assertGeneration(generation);
      return result;
    } finally {
      _checkCurrent();
    }
  }

  /// 빈 응답에도 세대 검증을 적용하며 하트비트를 재전송하지 않는다.
  Future<void> authorizedPostNoContent(String path) async {
    final generation = _generation;
    await _ensureAccess(generation);
    try {
      await ref.read(apiProvider).postNoContent(path, _access!);
      _assertGeneration(generation);
    } finally {
      _checkCurrent();
    }
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

  /// 저장소 접근 이전의 실패만 재시도한다. 불확실한 인증 명령은 재실행하지 않는다.
  Future<void> retryRestore() async {
    if (state.phase != AuthPhase.recovery) return;
    state = const AuthSnapshot(AuthPhase.checking);
    try {
      if (_access == null && !await _refresh()) return;
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
    final loginFlight = _loginFlight;
    _access = null;
    _accessUntil = null;
    state = const AuthSnapshot(AuthPhase.signedOut);
    try {
      await _refreshFlight;
    } catch (_) {}
    try {
      await loginFlight;
    } catch (_) {}
    try {
      await _session.logout(ref.read(apiProvider));
    } on AuthSessionBlocked catch (_) {
      state = const AuthSnapshot(
        AuthPhase.loginRequired,
        error: '서버 로그아웃 결과는 확인되지 않았습니다. 자동 재전송하지 않습니다.',
      );
    } catch (_) {
      state = const AuthSnapshot(
        AuthPhase.signedOut,
        error: '기기의 인증은 삭제했지만 서버 로그아웃 결과는 확인되지 않았습니다. 자동 재전송하지 않습니다.',
      );
    }
  }
}
