package io.github.dmishra2022.jev;

/** An answer that carries a model-reported confidence (Choice and Score). */
public sealed interface ConfidentAnswer extends Answer permits ChoiceAnswer, ScoreAnswer {

    /** How certain the model is, from 0 to 1, derived from the probability distribution. */
    double confidence();

    /** Whether {@link #confidence()} is at least {@code threshold}; use it to gate automatic actions. */
    default boolean isConfident(double threshold) {
        return confidence() >= Checks.requireUnitInterval(threshold, "threshold");
    }
}
