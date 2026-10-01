package io.github.dmishra2022.jev.sample;

/**
 * What the triage service decided for one ticket.
 *
 * @param priority composite 0..1 score computed in code from atomic answers
 * @param route    where the ticket goes, chosen from answers <em>and</em> their confidence
 * @param model    the versioned model that answered, worth logging next to every decision
 */
public record TriageDecision(Ticket ticket, Department department, double departmentConfidence, double urgency,
                             int frustrationLevel, double churnRisk, double priority, Route route, String reason,
                             String model) {

    /** Destinations, from fully automatic to human. */
    public enum Route { AUTO_ASSIGN, HUMAN_REVIEW, ESCALATE }
}
