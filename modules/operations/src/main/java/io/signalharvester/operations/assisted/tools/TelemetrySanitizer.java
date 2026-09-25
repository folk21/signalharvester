package io.signalharvester.operations.assisted.tools;

import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.regex.Pattern;

/** Redacts common credential shapes and bounds untrusted telemetry before it can reach a model. */
@Singleton
public final class TelemetrySanitizer {
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;\\\"]+");
    private static final Pattern SENSITIVE_JSON = Pattern.compile(
            "(?i)(\\\"?(?:password|passwd|secret|token|api[_-]?key|credential|cookie)\\\"?\\s*[:=]\\s*\\\"?)[^\\\"\\s,;}]+");
    private static final Pattern URI_USER_INFO = Pattern.compile("(https?://)[^/@\\s]+:[^/@\\s]+@");

    public String sanitize(String value, int maxChars) {
        Objects.requireNonNull(value, "value");
        if (maxChars < 128) {
            throw new IllegalArgumentException("maxChars must be at least 128");
        }
        String sanitized = AUTHORIZATION.matcher(value).replaceAll("$1[REDACTED]");
        sanitized = SENSITIVE_JSON.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = URI_USER_INFO.matcher(sanitized).replaceAll("$1[REDACTED]@");
        if (sanitized.length() <= maxChars) {
            return sanitized;
        }
        return sanitized.substring(0, maxChars) + "\n[tool result truncated by configured bound]";
    }
}
