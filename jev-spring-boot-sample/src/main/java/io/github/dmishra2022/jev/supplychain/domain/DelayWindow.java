package io.github.dmishra2022.jev.supplychain.domain;

import io.github.dmishra2022.jev.ChoiceOption;
import java.util.OptionalInt;

/** How late the supplier says the order will be; each description is the rubric the model sees. */
public enum DelayWindow implements ChoiceOption {
    NONE("Arrives on or before the original ETA", 0),
    UNDER_ONE_WEEK("Arrives 1 to 6 days after the original ETA", 4),
    ONE_TO_TWO_WEEKS("Arrives 7 to 14 days after the original ETA", 10),
    TWO_TO_FOUR_WEEKS("Arrives 15 to 28 days after the original ETA", 21),
    OVER_FOUR_WEEKS("Arrives more than 28 days after the original ETA", 42),
    UNSPECIFIED("Late, but the notice gives no way to tell by how much", -1);

    private final String description;
    private final int representativeDays;

    DelayWindow(String description, int representativeDays) {
        this.description = description;
        this.representativeDays = representativeDays;
    }

    @Override
    public Object description() {
        return description;
    }

    /** A planning value for the window; empty for {@link #UNSPECIFIED}, which policy fills in. */
    public OptionalInt representativeDays() {
        return representativeDays < 0 ? OptionalInt.empty() : OptionalInt.of(representativeDays);
    }
}
