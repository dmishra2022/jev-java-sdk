package io.github.dmishra2022.jev.supplychain.domain;

import io.github.dmishra2022.jev.ChoiceOption;

/**
 * Why the order is late. {@code recoverableByFreight} says whether premium freight can win back time: it can when
 * goods are (or will be) produced and only transit is the problem, not when they are stuck in customs, suspect, or
 * the plant is down.
 */
public enum RootCause implements ChoiceOption {
    LOGISTICS("Port congestion, rolled vessel or flight bookings, carrier or trucking capacity", true),
    RAW_MATERIAL_SHORTAGE("The supplier lacks raw materials or sub-components, or is on allocation", true),
    PRODUCTION_CAPACITY("Machine breakdown, line maintenance, labour shortage or strike at the supplier", true),
    CUSTOMS("Held in customs, missing import/export documents or licences", false),
    QUALITY_HOLD("Failed inspection, defects, rework or a compliance hold on the goods", false),
    FORCE_MAJEURE("Natural disaster, fire, war, sanctions or similar events", false),
    OTHER("Any other reason, or no reason given", true);

    private final String description;
    private final boolean recoverableByFreight;

    RootCause(String description, boolean recoverableByFreight) {
        this.description = description;
        this.recoverableByFreight = recoverableByFreight;
    }

    @Override
    public Object description() {
        return description;
    }

    public boolean recoverableByFreight() {
        return recoverableByFreight;
    }
}
