package io.github.dmishra2022.jev;

import java.util.Locale;

/**
 * Optional mix-in for enums used with {@link Question#choice(Object, Class)} to attach a rubric
 * description to each option.
 *
 * <pre>{@code
 * enum Department implements ChoiceOption {
 *     BILLING("Payments, invoicing, refunds"),
 *     TECHNICAL("Bugs, outages, integrations");
 *
 *     private final String description;
 *     Department(String description) { this.description = description; }
 *     public Object description() { return description; }
 * }
 * }</pre>
 */
public interface ChoiceOption {

    /** The option's description: a string, a structured value, or {@code null}. */
    Object description();

    /** The option name sent on the wire for an enum constant. */
    static String wireName(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
