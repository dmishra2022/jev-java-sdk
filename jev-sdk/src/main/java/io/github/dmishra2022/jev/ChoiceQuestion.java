package io.github.dmishra2022.jev;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Selects one option from a set; answered by {@link ChoiceAnswer}.
 *
 * @param instructions what the model should decide
 * @param options      option name to description ({@code null} allowed), in presentation order
 */
public record ChoiceQuestion(Object instructions, Map<String, Object> options) implements Question {

    /** The API accepts at most this many options per Choice. */
    public static final int MAX_OPTIONS = 255;

    public ChoiceQuestion {
        Checks.requireInstructions(instructions);
        options = Checks.orderedCopy(options, "options");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("a choice needs at least one option");
        }
        if (options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException(
                    "a choice accepts at most " + MAX_OPTIONS + " options but got " + options.size());
        }
    }

    @Override
    public QuestionType type() {
        return QuestionType.CHOICE;
    }

    /** Option names in presentation order. */
    public Set<String> optionNames() {
        return options.keySet();
    }

    /** Starts a builder for a choice with incrementally added options. */
    public static Builder builder(Object instructions) {
        return new Builder(instructions);
    }

    /** Incrementally assembles a {@link ChoiceQuestion}. */
    public static final class Builder {
        private final Object instructions;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder(Object instructions) {
            this.instructions = instructions;
        }

        /** Adds an option without a description. */
        public Builder option(String name) {
            return option(name, null);
        }

        /** Adds an option; a repeated name replaces the earlier description. */
        public Builder option(String name, Object description) {
            options.put(Checks.requireNonBlank(name, "option"), description);
            return this;
        }

        public ChoiceQuestion build() {
            return new ChoiceQuestion(instructions, options);
        }
    }
}
