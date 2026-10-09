import Flutter
import UIKit
import Darwin

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    let channel = FlutterMethodChannel(
      name: "sicha.player/auth_installation",
      binaryMessenger: engineBridge.applicationRegistrar.messenger()
    )
    channel.setMethodCallHandler { call, result in
      do {
        switch call.method {
        case "readMarker":
          result(try AuthInstallationMarker.read())
        case "writeMarker":
          try AuthInstallationMarker.write()
          result(true)
        default:
          result(FlutterMethodNotImplemented)
        }
      } catch {
        result(FlutterError(
          code: "AUTH_INSTALLATION_IO",
          message: "설치 표지를 확인하거나 기록하지 못했습니다.",
          details: nil
        ))
      }
    }
  }
}

/// 앱 컨테이너 안의 비백업·비밀 없는 설치 표지만 관리한다.
private enum AuthInstallationMarker {
  private static let contents = Data("sicha.player.auth.installation.v1\n".utf8)

  private static func directory() throws -> URL {
    let support = try FileManager.default.url(
      for: .applicationSupportDirectory,
      in: .userDomainMask,
      appropriateFor: nil,
      create: true
    )
    return support.appendingPathComponent("sicha.player.auth.installation", isDirectory: true)
  }

  /// 부재만 미설치로 취급하며 읽기·속성 확인 오류는 인증을 차단한다.
  static func read() throws -> Bool {
    let directory = try directory()
    let marker = directory.appendingPathComponent("installed-v1")
    do {
      let directoryValues = try directory.resourceValues(forKeys: [
        .isDirectoryKey, .isSymbolicLinkKey, .isExcludedFromBackupKey,
      ])
      guard directoryValues.isDirectory == true,
        directoryValues.isSymbolicLink != true,
        directoryValues.isExcludedFromBackup == true else {
        throw failure()
      }
      let values = try marker.resourceValues(forKeys: [
        .isRegularFileKey, .isSymbolicLinkKey, .isExcludedFromBackupKey,
      ])
      guard values.isRegularFile == true, values.isSymbolicLink != true,
        values.isExcludedFromBackup == true else {
        throw failure()
      }
      return try Data(contentsOf: marker) == contents
    } catch let error as NSError {
      if error.domain == NSCocoaErrorDomain &&
        (error.code == NSFileReadNoSuchFileError || error.code == NSFileNoSuchFileError) {
        return false
      }
      throw error
    }
  }

  /// 제외 속성 확인·원자 교체·파일 및 디렉터리 동기화·재읽기 뒤에만 성공한다.
  static func write() throws {
    let directory = try directory()
    try FileManager.default.createDirectory(
      at: directory, withIntermediateDirectories: true,
      attributes: [.protectionKey: FileProtectionType.complete]
    )
    try excludeFromBackup(directory)
    let temporary = directory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: temporary) }
    try contents.write(to: temporary, options: [.withoutOverwriting, .completeFileProtection])
    try excludeFromBackup(temporary)
    try synchronize(temporary)
    let marker = directory.appendingPathComponent("installed-v1")
    let status = temporary.withUnsafeFileSystemRepresentation { source in
      marker.withUnsafeFileSystemRepresentation { target in
        Darwin.rename(source!, target!)
      }
    }
    guard status == 0 else { throw posixFailure() }
    try excludeFromBackup(marker)
    try synchronize(marker)
    try synchronize(directory)
    try synchronize(directory.deletingLastPathComponent())
    guard try read() else { throw failure() }
  }

  private static func excludeFromBackup(_ url: URL) throws {
    let existing = try url.resourceValues(forKeys: [.isSymbolicLinkKey])
    guard existing.isSymbolicLink != true else { throw failure() }
    var mutableURL = url
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    try mutableURL.setResourceValues(values)
    // URL의 속성 캐시가 아니라 새 URL에서 실제 설정을 다시 확인한다.
    let freshURL = URL(fileURLWithPath: url.path)
    guard try freshURL.resourceValues(forKeys: [.isExcludedFromBackupKey])
      .isExcludedFromBackup == true else { throw failure() }
  }

  private static func synchronize(_ url: URL) throws {
    let descriptor = url.withUnsafeFileSystemRepresentation { path in
      Darwin.open(path!, O_RDONLY | O_NOFOLLOW)
    }
    guard descriptor >= 0 else { throw posixFailure() }
    defer { Darwin.close(descriptor) }
    guard Darwin.fsync(descriptor) == 0 else { throw posixFailure() }
  }

  private static func failure() -> NSError {
    NSError(domain: "sicha.player.auth.installation", code: 1)
  }

  private static func posixFailure() -> NSError {
    NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
  }
}
