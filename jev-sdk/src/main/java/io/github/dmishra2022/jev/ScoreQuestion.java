package io.github.dmishra2022.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rates the state on ordered levels; answered by {@link ScoreAnswer}.
 *
 * @param instructions what the model should rate
 * @param levels       level descriptions from lowest (index 0) to highest; entries may be {@code null}
 */
public record ScoreQuestion(Object instructions, List<Object> levels) implements Question {

    /** A Score should have at least two levels. */
    public static final int MIN_LEVELS = 2;
    /** The API accepts at most this many levels. */
    public static final int MAX_LEVELS = 10;

    public ScoreQuestion {
        Checks.requireInstructions(instructions);
        Objects.requireNonNull(levels, "levels");
        if (levels.size() < MIN_LEVELS || levels.size() > MAX_LEVELS) {
            throw new IllegalArgumentException("a score needs between " + MIN_LEVELS + " and " + MAX_LEVELS
                    + " levels but got " + levels.size());
        }
        levels = Collections.unmodifiableList(new ArrayList<>(levels));
    }

    @Override
    public QuestionType type() {
        return QuestionType.SCORE;
    }
}
