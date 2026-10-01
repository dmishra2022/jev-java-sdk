package io.github.dmishra2022.jev;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A typed question evaluated against a request's state.
 *
 * <p>{@code instructions} and every criteria value are "entry types": a {@link String}, or any value
 * Jackson can serialize to a JSON object or array ({@link Map}, {@link List}, records, {@code JsonNode}).
 * Criteria values may be {@code null} when an option or level needs no description.
 */
public sealed interface Question permits NoulQuestion, ChoiceQuestion, ScoreQuestion {

    /** The primitive this question uses. */
    QuestionType type();

    /** What the model should decide; never {@code null}. */
    Object instructions();

    /** A yes/no question. */
    static NoulQuestion noul(Object instructions) {
        return new NoulQuestion(instructions, null, null);
    }

    /** A yes/no question with descriptions of what a yes and a no mean. */
    static NoulQuestion noul(Object instructions, Object whenTrue, Object whenFalse) {
        return new NoulQuestion(instructions, whenTrue, whenFalse);
    }

    /** A choice between options that need no description. */
    static ChoiceQuestion choice(Object instructions, String... options) {
        Objects.requireNonNull(options, "options");
        var map = new LinkedHashMap<String, Object>();
        for (String option : options) {
            if (map.containsKey(Checks.requireNonBlank(option, "option"))) {
                throw new IllegalArgumentException("duplicate option: " + option);
            }
            map.put(option, null);
        }
        return new ChoiceQuestion(instructions, map);
    }

    /** A choice between options, each mapped to a rubric description (or {@code null}). */
    static ChoiceQuestion choice(Object instructions, Map<String, ?> options) {
        return new ChoiceQuestion(instructions, Checks.orderedCopy(options, "options"));
    }

    /**
     * A choice whose options are the constants of {@code enumType}, named by their lower-case
     * {@link Enum#name()}. Constants implementing {@link ChoiceOption} contribute a description.
     * Read the answer back with {@link ChoiceAnswer#as(Class)}.
     */
    static <E extends Enum<E>> ChoiceQuestion choice(Object instructions, Class<E> enumType) {
        Objects.requireNonNull(enumType, "enumType");
        var map = new LinkedHashMap<String, Object>();
        for (E constant : enumType.getEnumConstants()) {
            Object description = constant instanceof ChoiceOption option ? option.description() : null;
            map.put(ChoiceOption.wireName(constant), description);
        }
        return new ChoiceQuestion(instructions, map);
    }

    /** A score over ordered levels, lowest first. */
    static ScoreQuestion score(Object instructions, Object... levels) {
        Objects.requireNonNull(levels, "levels");
        return new ScoreQuestion(instructions, Arrays.asList(levels));
    }

    /** A score over ordered levels, lowest first. */
    static ScoreQuestion score(Object instructions, List<?> levels) {
        return new ScoreQuestion(instructions, new ArrayList<Object>(Objects.requireNonNull(levels, "levels")));
    }
}
