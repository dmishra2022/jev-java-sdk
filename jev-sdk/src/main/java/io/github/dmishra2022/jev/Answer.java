package io.github.dmishra2022.jev;

/**
 * A typed answer to one {@link Question}. The hierarchy is sealed, so answers can be handled with an
 * exhaustive {@code switch}:
 *
 * <pre>{@code
 * switch (answer) {
 *     case NoulAnswer n -> ...
 *     case ChoiceAnswer c -> ...
 *     case ScoreAnswer s -> ...
 * }
 * }</pre>
 */
public sealed interface Answer permits NoulAnswer, ConfidentAnswer {

    /** The primitive that produced this answer. */
    QuestionType type();
}
