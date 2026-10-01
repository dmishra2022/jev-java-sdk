package io.github.dmishra2022.jev.sample;

import io.github.dmishra2022.jev.ChoiceOption;

/** Support teams; each description is the rubric the model sees for that option. */
public enum Department implements ChoiceOption {
    BILLING("Payments, invoices, refunds, double charges, failed payouts"),
    TECHNICAL("Bugs, errors, outages, crashes, API or integration problems"),
    ACCOUNT("Login, password reset, access, profile or security settings"),
    SALES("Pricing questions, plan upgrades, quotes, new accounts");

    private final String description;

    Department(String description) {
        this.description = description;
    }

    @Override
    public Object description() {
        return description;
    }
}
