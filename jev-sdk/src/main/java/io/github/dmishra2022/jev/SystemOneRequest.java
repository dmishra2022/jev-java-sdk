package io.github.dmishra2022.jev;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A state plus named questions, evaluated in parallel by one API call.
 *
 * @param state     text ({@link String}) or any Jackson-serializable structure (maps, lists, records,
 *                  {@code JsonNode})
 * @param questions questions by the names answers will come back under; iteration order is preserved
 * @param model     model override for this request; otherwise the client default is used
 */
public record SystemOneRequest(Object state, Map<String, Question> questions, Optional<String> model) {

    public SystemOneRequest {
        Objects.requireNonNull(state, "state");
        questions = Checks.orderedCopy(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("at least one question is required");
        }
        for (var entry : questions.entrySet()) {
            Objects.requireNonNull(entry.getValue(), () -> "question " + entry.getKey());
        }
        Objects.requireNonNull(model, "model");
        model.ifPresent(name -> Checks.requireNonBlank(name, "model"));
    }

    /** A request using the client's default model. */
    public static SystemOneRequest of(Object state, Map<String, ? extends Question> questions) {
        return new SystemOneRequest(state, Checks.orderedCopy(questions, "questions"), Optional.empty());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Assembles a request question by question. */
    public static final class Builder {
        private Object state;
        private final Map<String, Question> questions = new LinkedHashMap<>();
        private String model;

        private Builder() {
        }

        public Builder state(Object state) {
            this.state = state;
            return this;
        }

        /** Adds a question; names must be unique within a request. */
        public Builder question(String name, Question question) {
            Checks.requireNonBlank(name, "question name");
            Objects.requireNonNull(question, "question");
            if (questions.putIfAbsent(name, question) != null) {
                throw new IllegalArgumentException("duplicate question name: " + name);
            }
            return this;
        }

        public Builder questions(Map<String, ? extends Question> questions) {
            Objects.requireNonNull(questions, "questions");
            questions.forEach(this::question);
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public SystemOneRequest build() {
            return new SystemOneRequest(state, questions, Optional.ofNullable(model));
        }
    }
}
