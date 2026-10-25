package com.velopcontrol.app;

/** Mirrors the versioned local firmware policy, before any password write is sent. */
public final class AdminPasswordPolicy {
    public static void validate(String password, boolean modern) {
        if (password.length() < (modern ? 10 : 1) || password.length() > 64 || !password.matches("[\\x20-\\x7E]+"))
            throw new IllegalArgumentException(modern ? "Use 10–64 printable ASCII characters." : "Use 1–64 printable ASCII characters.");
        if (modern && (!password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*") ||
            !password.matches(".*[0-9].*") || !password.matches(".*[\\x20-\\x2F\\x3A-\\x40\\x5B-\\x60\\x7D-\\x7E].*") || password.matches(".*(.)\\1.*")))
            throw new IllegalArgumentException("Include uppercase, lowercase, a number and a symbol; avoid identical consecutive characters.");
    }
    private AdminPasswordPolicy() {}
}
