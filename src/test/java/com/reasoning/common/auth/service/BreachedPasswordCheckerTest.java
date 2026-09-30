package com.reasoning.common.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

class BreachedPasswordCheckerTest {
    @Test
    void checksExactSortedDigestWithoutExposingPasswords() throws Exception {
        Path file = Files.createTempFile("h0-breach-test-", ".txt");
        try {
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            List<String> lines =
                    List.of(
                                    digest("already-leaked-A"),
                                    digest("already-leaked-B"),
                                    digest("already-leaked-C"))
                            .stream()
                            .sorted()
                            .toList();
            Files.writeString(file, String.join("\n", lines) + "\n");
            AuthProperties properties = new AuthProperties();
            properties.setBreachedHashesFile(file.toString());
            var checker = new BreachedPasswordChecker(properties);
            assertThat(checker.isBreached("already-leaked-A")).isTrue();
            assertThat(checker.isBreached("already-leaked-B")).isTrue();
            assertThat(checker.isBreached("already-leaked-C")).isTrue();
            assertThat(checker.isBreached("unrelated-distinct-passphrase")).isFalse();
            Files.writeString(file, lines.get(0) + "\n" + lines.get(0) + "\n");
            assertThatThrownBy(() -> new BreachedPasswordChecker(properties))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> checker.isBreached("already-leaked-B"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private String digest(String password) throws Exception {
        return HexFormat.of()
                .withUpperCase()
                .formatHex(
                        MessageDigest.getInstance("SHA-1")
                                .digest(password.getBytes(StandardCharsets.UTF_8)));
    }
}
