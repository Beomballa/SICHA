import 'package:dio/dio.dart';
import 'package:dio/io.dart';

/// 네이티브는 기존 Dio IO 전송을 사용하며 브라우저 경로 제한을 적용하지 않는다.
void configurePlayerTransport(Dio dio, String endpoint) {
  dio.httpClientAdapter = IOHttpClientAdapter();
}
