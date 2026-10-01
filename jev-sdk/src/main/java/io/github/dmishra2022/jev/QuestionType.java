package io.github.dmishra2022.jev;

import java.util.Optional;

/** The three System One primitives. */
public enum QuestionType {
    /** A yes/no question answered with the probability of "yes". */
    NOUL("noul"),
    /** Selects one option from a set; answered with a distribution and confidence. */
    CHOICE("choice"),
    /** Rates the state on ordered levels; answered with an expected level and confidence. */
    SCORE("score");

    private final String wireName;

    QuestionType(String wireName) {
        this.wireName = wireName;
    }

    /** The value of the {@code type} field on the wire. */
    public String wireName() {
        return wireName;
    }

    static Optional<QuestionType> fromWireName(String wireName) {
        for (QuestionType type : values()) {
            if (type.wireName.equals(wireName)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
