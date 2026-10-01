package io.github.dmishra2022.jev;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The answer to a {@link ScoreQuestion}.
 *
 * @param score         the probability-weighted level; may land between levels
 * @param legend        each level index mapped back to its description (strings, maps or lists)
 * @param probabilities each level index mapped to its probability
 * @param confidence    how certain the model is, from 0 to 1
 */
public record ScoreAnswer(double score, SortedMap<Integer, Object> legend, SortedMap<Integer, Double> probabilities,
                          double confidence) implements ConfidentAnswer {

    public ScoreAnswer {
        legend = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(legend, "legend")));
        probabilities = Collections.unmodifiableSortedMap(
                new TreeMap<>(Objects.requireNonNull(probabilities, "probabilities")));
    }

    /** Convenience constructor accepting any map; keys are sorted by level. */
    public ScoreAnswer(double score, Map<Integer, ?> legend, Map<Integer, Double> probabilities, double confidence) {
        this(score, new TreeMap<Integer, Object>(legend), new TreeMap<>(probabilities), confidence);
    }

    @Override
    public QuestionType type() {
        return QuestionType.SCORE;
    }

    /** The level nearest to {@link #score()}, clamped to the legend's range. */
    public int level() {
        int rounded = (int) Math.round(score);
        if (legend.isEmpty()) {
            return rounded;
        }
        return Math.clamp(rounded, legend.firstKey(), legend.lastKey());
    }

    /** The description of {@link #level()}, or {@code null} if the legend has none. */
    public Object levelDescription() {
        return legend.get(level());
    }

    /** {@link #score()} scaled to [0, 1] across the legend's range; useful for composite scoring. */
    public double normalized() {
        if (legend.size() < 2) {
            return 0.0;
        }
        int low = legend.firstKey();
        int high = legend.lastKey();
        return Math.clamp((score - low) / (high - low), 0.0, 1.0);
    }
}
