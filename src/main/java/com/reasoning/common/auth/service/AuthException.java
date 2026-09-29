package com.reasoning.common.auth.service;

public class AuthException extends RuntimeException {
    private final int status;
    private final String code;

    public AuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static AuthException badRequest(String code) { return new AuthException(400, code, code); }
    public static AuthException unauthorized(String code) { return new AuthException(401, code, code); }
    public static AuthException forbidden(String code) { return new AuthException(403, code, code); }
    public static AuthException conflict(String code) { return new AuthException(409, code, code); }
    public static AuthException unprocessable(String code) { return new AuthException(422, code, code); }
    public static AuthException unavailable(String code) { return new AuthException(503, code, code); }
}
