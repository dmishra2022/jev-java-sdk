package io.github.dmishra2022.jev;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to a {@link ChoiceQuestion}.
 *
 * @param choice        the highest-probability option
 * @param probabilities every option mapped to its probability, in the order the server returned them
 * @param confidence    how certain the model is, from 0 to 1
 */
public record ChoiceAnswer(String choice, Map<String, Double> probabilities, double confidence)
        implements ConfidentAnswer {

    public ChoiceAnswer {
        Objects.requireNonNull(choice, "choice");
        probabilities = Checks.orderedCopy(probabilities, "probabilities");
    }

    @Override
    public QuestionType type() {
        return QuestionType.CHOICE;
    }

    /** The probability of {@code option}. */
    public double probability(String option) {
        Double value = probabilities.get(option);
        if (value == null) {
            throw new IllegalArgumentException("unknown option '" + option + "'; known: " + probabilities.keySet());
        }
        return value;
    }

    /** Options sorted by descending probability; ties keep server order. */
    public List<Map.Entry<String, Double>> ranked() {
        var entries = new ArrayList<Map.Entry<String, Double>>(probabilities.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        return List.copyOf(entries);
    }

    /**
     * The gap between the two most likely options (the top probability when there is only one option).
     * A small margin signals a close call worth reviewing.
     */
    public double margin() {
        var ranked = ranked();
        if (ranked.isEmpty()) {
            return 0.0;
        }
        double top = ranked.getFirst().getValue();
        return ranked.size() == 1 ? top : top - ranked.get(1).getValue();
    }

    /** Maps the choice back to the enum used with {@link Question#choice(Object, Class)}. */
    public <E extends Enum<E>> E as(Class<E> enumType) {
        Objects.requireNonNull(enumType, "enumType");
        for (E constant : enumType.getEnumConstants()) {
            if (ChoiceOption.wireName(constant).equalsIgnoreCase(choice)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("choice '" + choice + "' is not a constant of " + enumType.getName());
    }
}
