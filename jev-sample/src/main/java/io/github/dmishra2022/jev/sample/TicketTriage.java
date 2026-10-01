package io.github.dmishra2022.jev.sample;

import io.github.dmishra2022.jev.ChoiceAnswer;
import io.github.dmishra2022.jev.EvaluationResult;
import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevContext;
import io.github.dmishra2022.jev.Question;
import io.github.dmishra2022.jev.ScoreAnswer;
import io.github.dmishra2022.jev.SystemOneRequest;
import io.github.dmishra2022.jev.structured.Outcome;
import io.github.dmishra2022.jev.structured.StructuredJev;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket triage built the System One way: ask four atomic questions in one call, then combine the typed answers
 * with weights and thresholds that live in code (composite scoring + confidence-gated routing).
 */
public final class TicketTriage {

    /** Tunable knobs; change a coefficient here instead of rewriting a prompt. */
    public record Policy(double minDepartmentConfidence, double minMargin, double escalateChurnRisk,
                         double urgencyWeight, double frustrationWeight, double churnWeight) {
        public static final Policy DEFAULT = new Policy(0.6, 0.15, 0.7, 0.5, 0.3, 0.2);
    }

    static final Map<String, Question> QUESTIONS = questions();

    private final JevClient client;
    private final StructuredJev structured;
    private final Policy policy;

    public TicketTriage(JevClient client, Policy policy) {
        this.client = client;
        this.structured = new StructuredJev(client);
        this.policy = policy;
    }

    private static Map<String, Question> questions() {
        var questions = new LinkedHashMap<String, Question>();
        questions.put("department", Question.choice("Which team should handle this ticket?", Department.class));
        questions.put("urgent", Question.noul("Does the customer need help urgently?",
                "Explicitly time-sensitive, blocked, or losing money right now", "No time pressure expressed"));
        questions.put("frustration", Question.score("How frustrated is the customer?",
                "Calm", "Mildly annoyed", "Frustrated", "Very angry"));
        questions.put("churn", Question.noul("Is the customer threatening to cancel, leave or switch providers?"));
        return Collections.unmodifiableMap(questions);
    }

    static SystemOneRequest requestFor(Ticket ticket) {
        return SystemOneRequest.of(Map.of("ticket", ticket), QUESTIONS);
    }

    /** Triage one ticket (one API call, four questions evaluated in parallel by the model). */
    public TriageDecision triage(Ticket ticket) {
        return ScopedValue.where(JevContext.CORRELATION_ID, ticket.id())
                .call(() -> decide(ticket, client.evaluate(requestFor(ticket))));
    }

    /** Triage a batch concurrently on virtual threads; one slow or failing ticket never sinks the batch. */
    public List<Outcome<TriageDecision>> triageAll(List<Ticket> tickets, Duration deadline) {
        List<SystemOneRequest> requests = tickets.stream().map(TicketTriage::requestFor).toList();
        List<Outcome<EvaluationResult>> outcomes = structured.evaluateAllSettled(requests, deadline);
        var decisions = new ArrayList<Outcome<TriageDecision>>(tickets.size());
        for (int i = 0; i < tickets.size(); i++) {
            Ticket ticket = tickets.get(i);
            decisions.add(switch (outcomes.get(i)) {
                case Outcome.Success<EvaluationResult> success -> new Outcome.Success<>(decide(ticket, success.value()));
                case Outcome.Failure<EvaluationResult> failure -> new Outcome.Failure<>(failure.error());
            });
        }
        return decisions;
    }

    /** Latency-critical path: hedge after {@code hedgeAfter} to cut tail latency. */
    public TriageDecision triageHedged(Ticket ticket, Duration hedgeAfter) {
        return decide(ticket, structured.hedged(requestFor(ticket), hedgeAfter));
    }

    TriageDecision decide(Ticket ticket, EvaluationResult result) {
        ChoiceAnswer department = result.choice("department");
        double urgency = result.noul("urgent").noul();
        ScoreAnswer frustration = result.score("frustration");
        double churn = result.noul("churn").noul();

        double priority = policy.urgencyWeight() * urgency
                + policy.frustrationWeight() * frustration.normalized()
                + policy.churnWeight() * churn;

        TriageDecision.Route route;
        String reason;
        if (churn >= policy.escalateChurnRisk()) {
            route = TriageDecision.Route.ESCALATE;
            reason = "churn risk %.2f".formatted(churn);
        } else if (!department.isConfident(policy.minDepartmentConfidence())) {
            route = TriageDecision.Route.HUMAN_REVIEW;
            reason = "low confidence %.2f".formatted(department.confidence());
        } else if (department.margin() < policy.minMargin()) {
            route = TriageDecision.Route.HUMAN_REVIEW;
            reason = "close call, margin %.2f".formatted(department.margin());
        } else {
            route = TriageDecision.Route.AUTO_ASSIGN;
            reason = "confident";
        }
        return new TriageDecision(ticket, department.as(Department.class), department.confidence(), urgency,
                frustration.level(), churn, priority, route, reason, result.model());
    }
}
