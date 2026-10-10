import 'dart:async';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../core/player_api.dart';
import 'auth_session.dart';

const _ios = IOSOptions(
  accountName: 'sicha.player.auth',
  accessibility: KeychainAccessibility.unlocked_this_device,
  synchronizable: false,
);

AuthSession createAuthSession({AuthTokenStorage? storage}) =>
    NativeAuthSession(storage ?? _KeychainTokenStorage());

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

/// 네이티브 저장소 회전 경계다. 빈 이벤트 스트림은 탭 간 조정 기능이 아니다.
class NativeAuthSession implements AuthSession {
  NativeAuthSession(this.storage);
  final AuthTokenStorage storage;
  String? _key;
  AccessCredentials? _credentials;
  // 갱신 실패로 업무 자격을 숨겨도 대기 중인 로그아웃의 한 번짜리 회수 증명은 유지한다.
  String? _logoutProof;

  void _bind(PlayerApi api) {
    _key = 'player:${Uri.encodeComponent(api.endpoint)}:refresh';
  }

  String get _marker => '$_key:rotating';

  @override
  bool get current => _credentials != null;

  @override
  Stream<void> get invalidations => const Stream<void>.empty();

  @override
  Future<AccessCredentials?> restore(PlayerApi api) async {
    _bind(api);
    if (await storage.read(_marker) != null) {
      await storage.delete(_key!);
      throw AuthSessionBlocked();
    }
    if (await storage.read(_key!) == null) return null;
    return refresh(api);
  }

  Future<AccessCredentials> _store(Map<String, dynamic> tokens) async {
    if (tokens['tokenType'] != 'Bearer') {
      throw const FormatException('인증 응답 오류');
    }
    final access = tokens['accessToken'] as String;
    final refresh = tokens['refreshToken'] as String;
    final until = DateTime.parse(tokens['accessExpiresAt'] as String);
    await storage.write(_key!, refresh);
    _logoutProof = access;
    return _credentials = AccessCredentials(access, until);
  }

  @override
  Future<AccessCredentials> login(
    PlayerApi api,
    String email,
    String password,
  ) async {
    _bind(api);
    try {
      await clearLocal();
      return await _store(
        await api.post('/api/member/auth/login/local', {
          'email': email,
          'password': password,
        }),
      );
    } catch (_) {
      try {
        await storage.delete(_key!);
      } catch (_) {
        throw AuthSessionBlocked();
      }
      rethrow;
    }
  }

  @override
  Future<AccessCredentials?> refresh(PlayerApi api) async {
    _bind(api);
    if (await storage.read(_marker) != null) {
      _credentials = null;
      await storage.delete(_key!);
      throw AuthSessionBlocked();
    }
    final token = await storage.read(_key!);
    if (token == null) {
      await clearLocal();
      return null;
    }
    await storage.write(_marker, '1');
    try {
      final credentials = await _store(
        await api.post('/api/member/auth/refresh', {'refreshToken': token}),
      );
      await storage.delete(_marker);
      return credentials;
    } catch (_) {
      _credentials = null;
      await storage.delete(_key!);
      // 소비된 증명은 재사용하지 않고 회전 표지를 남긴다.
      throw AuthSessionBlocked();
    }
  }

  @override
  Future<void> logout(PlayerApi api) async {
    _bind(api);
    final access = _logoutProof;
    try {
      await clearLocal();
    } catch (_) {
      throw AuthSessionBlocked();
    }
    if (access != null) {
      await api.post('/api/member/auth/logout', {
        'requestKey': requestKey(),
      }, access: access);
    }
  }

  @override
  Future<void> clearLocal() async {
    _credentials = null;
    _logoutProof = null;
    if (_key == null) return;
    await storage.delete(_key!);
    await storage.delete(_marker);
  }

  @override
  void dispose() {}
}
