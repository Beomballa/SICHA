import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:sicha_player/auth/auth_session.dart';
import 'package:sicha_player/auth/auth_session_native.dart' as native;
import 'package:sicha_player/core/player_api.dart';

const _phase = String.fromEnvironment('NATIVE_AUTH_TEST_PHASE');
const _device = String.fromEnvironment('NATIVE_AUTH_TEST_DEVICE');
const _channel = MethodChannel('sicha.player/auth_installation');
const _ios = IOSOptions(
  accountName: 'sicha.player.auth',
  accessibility: KeychainAccessibility.unlocked_this_device,
  synchronizable: false,
);
const _otherIos = IOSOptions(
  accountName: 'sicha.native.integration.other',
  accessibility: KeychainAccessibility.unlocked_this_device,
  synchronizable: false,
);
const _keychain = FlutterSecureStorage(iOptions: _ios);
const _otherKeychain = FlutterSecureStorage(iOptions: _otherIos);
const _probeKey = 'native-integration-probe';
// 서버 자격이 아닌 합성 표지다. 시험은 별도 생성한 시뮬레이터에서만 실행한다.
const _probe = 'synthetic-native-installation-probe';
const _otherProbe = 'synthetic-unrelated-keychain-probe';

/// 실제 iOS 채널·Keychain으로 설치/재시작/재설치·확정 차단을 확인한다.
/// `NATIVE_AUTH_TEST_PHASE`는 install/resume/reinstall/tampered/read-error뿐이다.
/// `NATIVE_AUTH_TEST_DEVICE`는 새로 생성한 시험 전용 Simulator UDID와 일치해야 한다.
/// 프로세스 재시작/속성 손상 단계는 `--no-uninstall`로 컨테이너를 보존한다.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  if (!Platform.isIOS ||
      _device.isEmpty ||
      !Directory.systemTemp.path.contains('/CoreSimulator/Devices/$_device/') ||
      !const {
        'install',
        'resume',
        'reinstall',
        'tampered',
        'read-error',
      }.contains(_phase)) {
    throw StateError('전용 iOS 시뮬레이터와 명시적인 native 시험 단계가 필요합니다.');
  }

  testWidgets('real native installation boundary: $_phase', (tester) async {
    final api = PlayerApi('https://localhost:64357');
    var networkRequests = 0;
    api.dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (request, handler) {
          ++networkRequests;
          handler.next(request);
        },
      ),
    );
    final session = native.createAuthSession() as native.NativeAuthSession;
    final refreshKey = 'player:${Uri.encodeComponent(api.endpoint)}:refresh';
    final rotatingKey = '$refreshKey:rotating';

    try {
      if (_phase == 'read-error') {
        expect(await _keychain.read(key: _probeKey), _probe);
        await expectLater(
          session.restore(api),
          throwsA(isA<PlatformException>()),
        );
        expect(session.current, isFalse);
        expect(await _keychain.read(key: _probeKey), _probe);
        expect(networkRequests, 0);
        return;
      }

      if (_phase == 'install') {
        expect(await _channel.invokeMethod<bool>('readMarker'), isFalse);
        await _keychain.write(key: _probeKey, value: _probe);
        await _otherKeychain.write(key: _probeKey, value: _otherProbe);
        expect(await session.restore(api), isNull);
        expect(await _keychain.readAll(), isEmpty);
      } else if (_phase == 'resume') {
        expect(await _channel.invokeMethod<bool>('readMarker'), isTrue);
        expect(await _keychain.read(key: _probeKey), _probe);
        expect(await session.restore(api), isNull);
        expect(await session.storage.read(_probeKey), _probe);
      } else {
        // 재설치는 실제 컨테이너 삭제 뒤, 손상 단계는 host의 합성 표지 변경 뒤 실행한다.
        expect(await _channel.invokeMethod<bool>('readMarker'), isFalse);
        expect(await _keychain.read(key: _probeKey), _probe);
        expect(await session.restore(api), isNull);
        expect(await _keychain.readAll(), isEmpty);
      }
      expect(await _channel.invokeMethod<bool>('readMarker'), isTrue);
      expect(await _otherKeychain.read(key: _probeKey), _otherProbe);

      // 회전 표지가 있으면 실제 전송 전에 소비 증명을 지우고 원본을 재사용하지 않는다.
      await session.storage.write(refreshKey, _probe);
      await session.storage.write(rotatingKey, '1');
      await expectLater(
        session.restore(api),
        throwsA(isA<AuthSessionBlocked>()),
      );
      expect(session.current, isFalse);
      expect(await _keychain.read(key: refreshKey), isNull);
      expect(await _keychain.read(key: rotatingKey), '1');
      await session.logout(api);
      expect(await _keychain.read(key: rotatingKey), isNull);
      expect(await _otherKeychain.read(key: _probeKey), _otherProbe);
      expect(networkRequests, 0);

      // 다음 실제 프로세스/재설치 단계의 Keychain 잔존 및 설치 표지 검증용이다.
      await session.storage.write(_probeKey, _probe);
      expect(await _keychain.read(key: _probeKey), _probe);
    } finally {
      session.dispose();
      api.dio.close(force: true);
    }
  });
}
