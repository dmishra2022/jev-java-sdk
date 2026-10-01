package io.github.dmishra2022.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The response to a System One request: one {@link Answer} per question, keyed by the names you chose.
 *
 * @param model     the versioned model that answered (e.g. {@code jev-1.13.0}); log it next to results
 * @param answers   answers in the order the questions were asked
 * @param usage     token usage
 * @param requestId the {@code x-typesafe-request-id} response header, useful for support tickets
 */
public record EvaluationResult(String model, Map<String, Answer> answers, Usage usage, Optional<String> requestId) {

    public EvaluationResult {
        Objects.requireNonNull(model, "model");
        answers = Checks.orderedCopy(answers, "answers");
        for (var entry : answers.entrySet()) {
            Objects.requireNonNull(entry.getValue(), () -> "answer for " + entry.getKey());
        }
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(requestId, "requestId");
    }

    /** The answer named {@code name}, if present. */
    public Optional<Answer> answer(String name) {
        return Optional.ofNullable(answers.get(name));
    }

    /** The noul answer named {@code name}. */
    public NoulAnswer noul(String name) {
        return typed(name, NoulAnswer.class);
    }

    /** The choice answer named {@code name}. */
    public ChoiceAnswer choice(String name) {
        return typed(name, ChoiceAnswer.class);
    }

    /** The score answer named {@code name}. */
    public ScoreAnswer score(String name) {
        return typed(name, ScoreAnswer.class);
    }

    /** All noul answers, in question order. */
    public Map<String, NoulAnswer> nouls() {
        return filter(NoulAnswer.class);
    }

    /** All choice answers, in question order. */
    public Map<String, ChoiceAnswer> choices() {
        return filter(ChoiceAnswer.class);
    }

    /** All score answers, in question order. */
    public Map<String, ScoreAnswer> scores() {
        return filter(ScoreAnswer.class);
    }

    private <A extends Answer> A typed(String name, Class<A> type) {
        Answer answer = answers.get(name);
        if (answer == null) {
            throw new NoSuchElementException("no answer named '" + name + "'; available: " + answers.keySet());
        }
        if (!type.isInstance(answer)) {
            throw new IllegalStateException(
                    "answer '" + name + "' is a " + answer.type().wireName() + ", not a " + type.getSimpleName());
        }
        return type.cast(answer);
    }

    private <A extends Answer> Map<String, A> filter(Class<A> type) {
        var result = new LinkedHashMap<String, A>();
        for (var entry : answers.entrySet()) {
            if (type.isInstance(entry.getValue())) {
                result.put(entry.getKey(), type.cast(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
