package io.github.dmishra2022.jev;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Token counts for a request, when reported by the API. Input tokens are billed; output tokens are free.
 *
 * @param inputTokens  input tokens, if reported
 * @param outputTokens output tokens, if reported
 */
public record Usage(OptionalLong inputTokens, OptionalLong outputTokens) {

    /** Usage with no reported counts. */
    public static final Usage EMPTY = new Usage(OptionalLong.empty(), OptionalLong.empty());

    public Usage {
        Objects.requireNonNull(inputTokens, "inputTokens");
        Objects.requireNonNull(outputTokens, "outputTokens");
    }

    /** Usage with both counts reported. */
    public static Usage of(long inputTokens, long outputTokens) {
        return new Usage(OptionalLong.of(inputTokens), OptionalLong.of(outputTokens));
    }

    /** The sum of the reported counts, empty when neither is reported. */
    public OptionalLong totalTokens() {
        return sum(inputTokens, outputTokens);
    }

    /** Field-wise sum; a count is present if it is present on either side. */
    public Usage plus(Usage other) {
        Objects.requireNonNull(other, "other");
        return new Usage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens));
    }

    private static OptionalLong sum(OptionalLong a, OptionalLong b) {
        if (a.isEmpty()) {
            return b;
        }
        return b.isEmpty() ? a : OptionalLong.of(a.getAsLong() + b.getAsLong());
    }
}
