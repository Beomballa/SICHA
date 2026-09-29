package com.reasoning.common.auth.service;

import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private String cryptoKeyFile = "";
    private String searchKeyFile = "";
    private String limitKeyFile = "";
    private String breachedHashesFile = "";
    private boolean cookieSecure = true;

    public String getCryptoKeyFile() { return cryptoKeyFile; }
    public void setCryptoKeyFile(String cryptoKeyFile) { this.cryptoKeyFile = cryptoKeyFile; }
    public String getSearchKeyFile() { return searchKeyFile; }
    public void setSearchKeyFile(String searchKeyFile) { this.searchKeyFile = searchKeyFile; }
    public String getLimitKeyFile() { return limitKeyFile; }
    public void setLimitKeyFile(String limitKeyFile) { this.limitKeyFile = limitKeyFile; }
    public String getBreachedHashesFile() { return breachedHashesFile; }
    public void setBreachedHashesFile(String breachedHashesFile) { this.breachedHashesFile = breachedHashesFile; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean cookieSecure) { this.cookieSecure = cookieSecure; }

    /**
     * Reads a required 256-bit key from a non-symlink, owner-only secret file.
     * @param value configured file path; blank values are never accepted
     * @param name safe configuration name for the exception message
     * @return the decoded 32-byte key material
     * @throws IllegalStateException when the key is absent or not 32 bytes
     */
    public byte[] decodeRequiredKey(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        try {
            Path file = Path.of(value);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(name + " requires a regular secret file");
            }
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ) || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE) || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE) || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IllegalStateException(name + " must be owner-only");
            }
            String content = Files.readString(file).trim();
            if (content.length() > 128) throw new IllegalStateException(name + " is invalid");
            byte[] decoded = Base64.getUrlDecoder().decode(content);
            if (decoded.length != 32) throw new IllegalStateException(name + " must decode to 32 bytes");
            return decoded;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(name + " is unreadable or invalid", e);
        }
    }
}
