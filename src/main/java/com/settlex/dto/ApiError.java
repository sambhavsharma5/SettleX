package com.settlex.dto;

import java.time.Instant;
import java.util.List;

/**
 * Uniform RFC-7807-flavoured error body returned by the global exception handler.
 *
 * @param timestamp   server-side UTC instant of failure
 * @param status      HTTP status code
 * @param code        stable machine-readable error code
 * @param error       HTTP reason phrase
 * @param message     human-readable summary
 * @param path        request path that failed
 * @param violations  field-level and semantic details; empty when not applicable
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String error,
        String message,
        String path,
        List<Violation> violations
) {

    /**
     * A single field- or semantic-level detail.
     *
     * @param field    dotted field path, or a logical name such as {@code transactions[3].amount}
     * @param message  what is wrong with it
     * @param rejected the offending value rendered as text, may be {@code null}
     */
    public record Violation(String field, String message, String rejected) {
    }

    public static ApiError of(int status, String code, String error, String message,
                              String path, List<Violation> violations) {
        return new ApiError(Instant.now(), status, code, error, message, path,
                violations == null ? List.of() : List.copyOf(violations));
    }
}