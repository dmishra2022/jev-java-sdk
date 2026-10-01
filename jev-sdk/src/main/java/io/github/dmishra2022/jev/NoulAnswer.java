package io.github.dmishra2022.jev;

/**
 * The answer to a {@link NoulQuestion}.
 *
 * @param noul probability that the answer is yes, from 0 to 1
 */
public record NoulAnswer(double noul) implements Answer {

    /** A three-way reading of a noul against two thresholds. */
    public enum Verdict {
        /** At or above the "yes" threshold. */
        YES,
        /** At or below the "no" threshold. */
        NO,
        /** Between the thresholds: route to review. */
        UNCERTAIN
    }

    @Override
    public QuestionType type() {
        return QuestionType.NOUL;
    }

    /** Whether the probability of yes is at least 0.5. */
    public boolean isTrue() {
        return isTrue(0.5);
    }

    /** Whether the probability of yes is at least {@code threshold}. */
    public boolean isTrue(double threshold) {
        return noul >= Checks.requireUnitInterval(threshold, "threshold");
    }

    /**
     * Classifies the answer as {@link Verdict#YES} ({@code noul >= yesAtOrAbove}), {@link Verdict#NO}
     * ({@code noul <= noAtOrBelow}) or {@link Verdict#UNCERTAIN}.
     */
    public Verdict verdict(double noAtOrBelow, double yesAtOrAbove) {
        Checks.requireUnitInterval(noAtOrBelow, "noAtOrBelow");
        Checks.requireUnitInterval(yesAtOrAbove, "yesAtOrAbove");
        if (noAtOrBelow >= yesAtOrAbove) {
            throw new IllegalArgumentException("noAtOrBelow must be below yesAtOrAbove");
        }
        if (noul >= yesAtOrAbove) {
            return Verdict.YES;
        }
        return noul <= noAtOrBelow ? Verdict.NO : Verdict.UNCERTAIN;
    }
}
