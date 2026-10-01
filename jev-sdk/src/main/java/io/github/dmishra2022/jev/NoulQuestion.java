package io.github.dmishra2022.jev;

/**
 * A yes/no question; answered by {@link NoulAnswer}.
 *
 * @param instructions the yes/no question
 * @param whenTrue     optional description of what a yes (value near 1) means
 * @param whenFalse    optional description of what a no (value near 0) means
 */
public record NoulQuestion(Object instructions, Object whenTrue, Object whenFalse) implements Question {

    public NoulQuestion {
        Checks.requireInstructions(instructions);
    }

    @Override
    public QuestionType type() {
        return QuestionType.NOUL;
    }

    /** Whether either criteria description is present. */
    public boolean hasCriteria() {
        return whenTrue != null || whenFalse != null;
    }
}
