package io.github.dmishra2022.jev;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation for user-supplied HTTP headers. */
final class HeaderRules {

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /** Set by the SDK; users may not override them. */
    private static final Set<String> PROTECTED = Set.of("authorization", "accept", "content-type", "user-agent");

    /** Managed by {@link java.net.http.HttpClient}, which rejects them. */
    private static final Set<String> RESTRICTED = Set.of(
            "connection", "content-length", "date", "expect", "from", "host", "upgrade", "via", "warning");

    private HeaderRules() {
    }

    static void validateUserHeaders(Map<String, String> headers) {
        headers.forEach(HeaderRules::validateUserHeader);
    }

    static void validateUserHeader(String name, String value) {
        validateName(name);
        String lower = name.toLowerCase(Locale.ROOT);
        if (PROTECTED.contains(lower)) {
            throw new IllegalArgumentException("header '" + name + "' is managed by the SDK and cannot be overridden");
        }
        if (RESTRICTED.contains(lower)) {
            throw new IllegalArgumentException("header '" + name + "' is managed by java.net.http.HttpClient");
        }
        validateValue(name, value);
    }

    static void validateName(String name) {
        Objects.requireNonNull(name, "header name");
        if (!TOKEN.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid header name: '" + name + "'");
        }
    }

    private static void validateValue(String name, String value) {
        Objects.requireNonNull(value, () -> "value of header " + name);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException("header '" + name + "' contains a CR, LF or NUL character");
            }
        }
    }
}
