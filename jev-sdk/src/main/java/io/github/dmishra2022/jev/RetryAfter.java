package io.github.dmishra2022.jev;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Parses {@code retry-after-ms} (milliseconds) and {@code Retry-After} (seconds or an HTTP date). */
final class RetryAfter {

    private RetryAfter() {
    }

    static Optional<Duration> parse(HttpHeaders headers, Instant now) {
        Optional<Duration> millis = headers.firstValue("retry-after-ms").flatMap(value -> amount(value, 1e6));
        if (millis.isPresent()) {
            return millis;
        }
        return headers.firstValue("retry-after").flatMap(value -> secondsOrDate(value, now));
    }

    private static Optional<Duration> secondsOrDate(String value, Instant now) {
        Optional<Duration> seconds = amount(value, 1e9);
        if (seconds.isPresent()) {
            return seconds;
        }
        try {
            Instant at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Optional.of(at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> amount(String value, double nanosPerUnit) {
        double parsed;
        try {
            parsed = Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (!Double.isFinite(parsed) || parsed < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos((long) (parsed * nanosPerUnit)));
    }
}
