package com.reasoning.common.auth.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** Offline lookup against a sorted, LF-delimited corpus of uppercase SHA-1 hex digests. */
@Component
public class BreachedPasswordChecker {
    private static final String CONFIG = "AUTH_BREACHED_HASHES_FILE";
    private static final int RECORD_SIZE = 41;
    private final Path file;
    private final long records;

    public BreachedPasswordChecker(AuthProperties properties) {
        String configured = properties.getBreachedHashesFile();
        if (configured == null || configured.isBlank()) throw invalid();
        try {
            file = Path.of(configured).toAbsolutePath().normalize();
            if (!Path.of(configured).isAbsolute()) throw invalid();
            checkFile();
            if (file.toRealPath().startsWith(Path.of("").toRealPath())) throw invalid();
            try (RandomAccessFile corpus = new RandomAccessFile(file.toFile(), "r")) {
                long length = corpus.length();
                if (length == 0 || length % RECORD_SIZE != 0) throw invalid();
                records = length / RECORD_SIZE;
                byte[] previous = new byte[RECORD_SIZE];
                byte[] line = new byte[RECORD_SIZE];
                for (long index = 0; index < records; index++) {
                    corpus.readFully(line);
                    if (!valid(line) || index != 0 && Arrays.compareUnsigned(previous, line) >= 0)
                        throw invalid();
                    byte[] swap = previous;
                    previous = line;
                    line = swap;
                }
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            throw invalid();
        }
    }

    /** Hashes the exact raw UTF-8 password for lookup only; storage remains Argon2id. */
    public boolean isBreached(String password) {
        byte[] digest;
        try {
            digest =
                    MessageDigest.getInstance("SHA-1")
                            .digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable");
        }
        byte[] target =
                HexFormat.of()
                        .withUpperCase()
                        .formatHex(digest)
                        .getBytes(StandardCharsets.US_ASCII);
        try {
            checkFile();
            try (RandomAccessFile corpus = new RandomAccessFile(file.toFile(), "r")) {
                if (corpus.length() != records * RECORD_SIZE) throw invalid();
                long low = 0;
                long high = records;
                byte[] line = new byte[RECORD_SIZE];
                while (low < high) {
                    long mid = low + (high - low) / 2;
                    corpus.seek(mid * RECORD_SIZE);
                    corpus.readFully(line);
                    if (!valid(line)) throw invalid();
                    int comparison = Arrays.compareUnsigned(line, 0, 40, target, 0, 40);
                    if (comparison == 0) return true;
                    if (comparison < 0) low = mid + 1;
                    else high = mid;
                }
                return false;
            }
        } catch (IOException | SecurityException e) {
            throw invalid();
        }
    }

    private void checkFile() throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw invalid();
        Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.contains(PosixFilePermission.OWNER_READ)) throw invalid();
        for (PosixFilePermission permission : permissions) {
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
                throw invalid();
        }
    }

    private static boolean valid(byte[] line) {
        if (line[40] != '\n') return false;
        for (int i = 0; i < 40; i++) {
            byte c = line[i];
            if (!(c >= '0' && c <= '9' || c >= 'A' && c <= 'F')) return false;
        }
        return true;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(
                CONFIG
                        + " requires an external owner-only regular file of sorted uppercase SHA-1"
                        + " hex lines");
    }
}
