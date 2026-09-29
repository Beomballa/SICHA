package com.reasoning.common.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class TestKeys {
    private TestKeys() {}

    public static String create(byte value) {
        try {
            Path file = Files.createTempFile("h0-test-key-", ".secret");
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, value);
            Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            file.toFile().deleteOnExit();
            return file.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Test key file unavailable", e);
        }
    }

    public static String createCorpus() {
        try {
            Path file = Files.createTempFile("h0-test-breaches-", ".txt");
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest("Password-Already-Leaked-1234".getBytes(StandardCharsets.UTF_8));
            Files.writeString(file, HexFormat.of().withUpperCase().formatHex(digest) + "\n");
            file.toFile().deleteOnExit();
            return file.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Synthetic test corpus unavailable", e);
        }
    }
}
