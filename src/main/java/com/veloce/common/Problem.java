package com.veloce.common;

import java.util.List;

public class Problem extends RuntimeException {
    public final int status;
    public final String code;
    public final List<?> issues;

    public Problem(int status, String code, String message) { this(status, code, message, List.of()); }
    public Problem(int status, String code, String message, List<?> issues) {
        super(message);
        this.status = status;
        this.code = code;
        this.issues = issues;
    }

    public static Problem invalid(String message) { return new Problem(400, "INVALID_INPUT", message); }
    public static Problem missing() { return new Problem(404, "NOT_FOUND", "This item is unavailable."); }
    public static Problem conflict(String message) { return new Problem(409, "CONFLICT", message); }
    public static Problem stale() { return new Problem(409, "STALE_PREVIEW", "Your settings or account schedule changed. Refresh the preview before generating."); }
    public static Problem unauthorized() { return new Problem(401, "SESSION_REQUIRED", "Sign in to continue."); }
}
