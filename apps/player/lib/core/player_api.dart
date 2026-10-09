import 'dart:math';

import 'package:dio/dio.dart';

/// 연결 설정 오류는 원본 주소나 비밀 없이 고정된 안내만 제공한다.
class PlayerConfigurationError extends StateError {
  PlayerConfigurationError()
    : super(
        'HTTPS API 주소가 설정되지 않았습니다. --dart-define=PLAYER_API_BASE_URL=https://호스트 를 지정하세요.',
      );
}

/// Native bearer API. No cookies, browser Origin, redirects, logging, or mutation retries.
class PlayerApi {
  PlayerApi(String endpoint)
    : dio = Dio(
        BaseOptions(
          baseUrl: _validated(endpoint),
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 15),
          sendTimeout: const Duration(seconds: 10),
          followRedirects: false,
          headers: {'Accept': 'application/json'},
        ),
      );

  final Dio dio;

  static String _validated(String value) {
    final uri = Uri.tryParse(value);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.path.isNotEmpty && uri.path != '/')) {
      throw PlayerConfigurationError();
    }
    return uri.replace(path: '/').toString();
  }

  String get endpoint => dio.options.baseUrl;

  Future<Map<String, dynamic>> get(
    String path,
    String access, {
    Map<String, dynamic>? query,
  }) async => _object(
    (await dio.get<dynamic>(
      path,
      queryParameters: query,
      options: Options(headers: {'Authorization': 'Bearer $access'}),
    )).data,
  );

  Future<Map<String, dynamic>> post(
    String path,
    Map<String, dynamic> body, {
    String? access,
  }) async => _object(
    (await dio.post<dynamic>(
      path,
      data: body,
      options: Options(
        headers: {
          'Content-Type': 'application/json',
          if (access != null) 'Authorization': 'Bearer $access',
        },
      ),
    )).data,
  );

  /// 현재 Bearer로 변경 본문을 한 번만 보내며 불확실한 응답은 재전송하지 않는다.
  Future<Map<String, dynamic>> patch(
    String path,
    Map<String, dynamic> body,
    String access,
  ) async => _object(
    (await dio.patch<dynamic>(
      path,
      data: body,
      options: Options(
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $access',
        },
      ),
    )).data,
  );

  /// 본문 없는 204만 하트비트 성공으로 인정하고 다른 성공 코드나 본문은 거부한다.
  Future<void> postNoContent(String path, String access) async {
    final response = await dio.post<dynamic>(
      path,
      data: <String, dynamic>{},
      options: Options(
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $access',
        },
      ),
    );
    if (response.statusCode != 204 ||
        response.data != null && response.data != '') {
      throw const FormatException('서버 응답 형식이 올바르지 않습니다.');
    }
  }

  static Map<String, dynamic> _object(dynamic value) {
    if (value is Map<String, dynamic>) return value;
    throw const FormatException('서버 응답 형식이 올바르지 않습니다.');
  }
}

String requestKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String playerError(Object error) {
  if (error is DioException) {
    final data = error.response?.data;
    final code = data is Map ? data['code'] : null;
    if (code == 'MEMBER_AUTH_REQUIRED') return '인증이 만료되었습니다. 다시 로그인해 주세요.';
    if (code == 'POLICY_CHANGED') return '고지가 변경되었습니다. 새 고지를 확인해 주세요.';
    if (code == 'INVITATION_INVALIDATED' || code == 'NOT_FOUND') {
      return '초대가 만료되었거나 변경되었습니다. 목록을 다시 확인해 주세요.';
    }
    if (error.response?.statusCode == null ||
        error.response!.statusCode! >= 500) {
      return '서버 응답을 확인할 수 없습니다. 연결 상태를 확인해 주세요.';
    }
    if (error.response!.statusCode == 429) {
      return '요청이 제한되었습니다. 잠시 후 다시 시도해 주세요.';
    }
    return '요청을 처리하지 못했습니다 (HTTP ${error.response!.statusCode}).';
  }
  return '응답을 확인할 수 없습니다. 잠시 후 다시 조회해 주세요.';
}
