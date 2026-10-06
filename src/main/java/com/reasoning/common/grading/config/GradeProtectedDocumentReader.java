package com.reasoning.common.grading.config;

import com.reasoning.ReasoningApplication;
import com.sun.security.auth.module.UnixSystem;

import org.springframework.boot.system.ApplicationHome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** 보호된 배포 문서의 바이트만 읽으며 파싱·설치 조립·실행 인가를 맡지 않는다. */
final class GradeProtectedDocumentReader {
    /** 보호 문서의 스트림 읽기와 개별 문서 파서가 공유하는 16MiB 상한이다. */
    static final int MAX_BYTES = 16 * 1024 * 1024;

    /** 인스턴스나 빈 없이 고정 파일 보호 규칙만 제공한다. */
    private GradeProtectedDocumentReader() {}

    /**
     * 실제 설치 경계와 파일 보호 규칙을 검사한 뒤 상한 이내의 원문 바이트를 읽는다. 배포 호스트의 파일 불변성을 전제로 하며 검사와 열기 사이의 원자성을 보장하지
     * 않는다.
     *
     * @param configuredPath 공백 제거·정규화하지 않는 보호 파일의 절대 경로
     * @return 파싱하거나 변환하지 않은 전체 문서 바이트
     * @throws IOException 경계 확인·파일 보호 검사·읽기 실패 또는 16MiB 초과인 경우
     * @throws RuntimeException 경로 또는 파일시스템 기능이 지원되지 않는 경우
     * @throws LinkageError 실제 설치 경계 또는 UID 확인 기능을 사용할 수 없는 경우
     */
    static byte[] read(String configuredPath) throws IOException {
        Path path = Path.of(configuredPath);
        secure(path, installationBoundary());

        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
                var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > MAX_BYTES - output.size()) throw failure();
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * 실제 ApplicationHome 또는 디렉터리 CodeSource에서 Git 루트를 찾는다.
     *
     * @return Git 루트가 있으면 해당 경계, 없으면 실제 설치 디렉터리
     * @throws IOException 실제 소스 위치를 확인할 수 없는 경우
     */
    private static Path installationBoundary() throws IOException {
        ApplicationHome home = new ApplicationHome(ReasoningApplication.class);
        Path source;
        if (home.getSource() != null) {
            source = home.getSource().toPath().toRealPath();
        } else {
            var codeSource = ReasoningApplication.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || !"file".equals(codeSource.getLocation().getProtocol()))
                throw failure();
            try {
                source = Path.of(codeSource.getLocation().toURI()).toRealPath();
            } catch (java.net.URISyntaxException exception) {
                throw failure();
            }
        }

        Path directory = Files.isDirectory(source) ? source : source.getParent();
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.exists(ancestor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return ancestor;
        }
        return directory;
    }

    /**
     * 원래 경로의 각 구성요소·실제 UID·0400/0600·일반 파일·설치 외부 위치를 검사한다. 경계 인자는 패키지 내부 시험용이며 운영 읽기는 자체 계산한 실제 경계만
     * 사용한다.
     *
     * @param path 정규화 전 보호 파일의 절대 경로
     * @param boundary 실재하는 설치 경계이며 운영 호출자가 선택하는 우회 인자가 아니다
     * @throws IOException 보호 규칙 위반 또는 파일 속성·실제 경로 확인 실패인 경우
     * @throws RuntimeException POSIX 또는 UID 속성 확인 기능이 지원되지 않는 경우
     * @throws LinkageError 실제 프로세스 UID 확인 기능을 사용할 수 없는 경우
     */
    static void secure(Path path, Path boundary) throws IOException {
        if (!path.isAbsolute()) throw failure();
        Path component = path.getRoot();
        for (Path name : path) {
            component = component.resolve(name);
            if (Files.isSymbolicLink(component)) throw failure();
        }

        var attributes =
                Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (!attributes.isRegularFile()
                || !(permissions.equals(Set.of(PosixFilePermission.OWNER_READ))
                        || permissions.equals(
                                Set.of(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE)))
                || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                                .longValue()
                        != new UnixSystem().getUid()
                || path.toRealPath().startsWith(boundary.toRealPath())) throw failure();
    }

    /**
     * 보호 규칙 위반의 고정 진단을 만들며 개별 문서의 공개 오류 계약은 정하지 않는다.
     *
     * @return 경로·문서·원인 예외를 포함하지 않는 입출력 오류
     */
    private static IOException failure() {
        return new IOException("INVALID_GRADE_PROTECTED_DOCUMENT");
    }
}
