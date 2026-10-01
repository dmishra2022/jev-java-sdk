package io.github.dmishra2022.jev;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Argument validation shared by the public value types. */
final class Checks {

    private Checks() {
    }

    static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static Object requireInstructions(Object instructions) {
        Objects.requireNonNull(instructions, "instructions");
        if (instructions instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        return instructions;
    }

    static double requireUnitInterval(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be within [0, 1] but was " + value);
        }
        return value;
    }

    static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
        return value;
    }

    static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative but was " + value);
        }
        return value;
    }

    /** Copies a map preserving iteration order; values may be {@code null}, keys may not. */
    static <V> Map<String, V> orderedCopy(Map<String, ? extends V> source, String name) {
        Objects.requireNonNull(source, name);
        var copy = new LinkedHashMap<String, V>(source.size() * 2);
        for (var entry : source.entrySet()) {
            copy.put(requireNonBlank(entry.getKey(), name + " key"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
