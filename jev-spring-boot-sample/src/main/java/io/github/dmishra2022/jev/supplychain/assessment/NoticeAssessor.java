package io.github.dmishra2022.jev.supplychain.assessment;

import io.github.dmishra2022.jev.ChoiceAnswer;
import io.github.dmishra2022.jev.EvaluationResult;
import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevContext;
import io.github.dmishra2022.jev.Question;
import io.github.dmishra2022.jev.ScoreAnswer;
import io.github.dmishra2022.jev.SystemOneRequest;
import io.github.dmishra2022.jev.supplychain.domain.DelayWindow;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.OrderContext;
import io.github.dmishra2022.jev.supplychain.domain.RootCause;
import io.github.dmishra2022.jev.supplychain.domain.SupplierNotice;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns an unstructured supplier notice into {@link NoticeSignals} with one Jev call of seven atomic questions.
 * Each question has one job, so each answer can be thresholded, logged and audited on its own.
 */
@Component
public class NoticeAssessor {

    static final Map<String, Question> QUESTIONS = questions();

    private final JevClient jev;

    public NoticeAssessor(JevClient jev) {
        this.jev = jev;
    }

    private static Map<String, Question> questions() {
        var q = new LinkedHashMap<String, Question>();
        q.put("delay_confirmed", Question.noul(
                "Does the notice say this purchase order will arrive later than its original ETA?",
                "The supplier states or clearly implies a later arrival",
                "Arrival is on time or early, or the notice is not about timing"));
        q.put("delay_window", Question.choice(
                "Relative to the original ETA in the purchase order, when will the goods arrive?", DelayWindow.class));
        q.put("root_cause", Question.choice("What is the main reason for the change?", RootCause.class));
        q.put("commitment", Question.score("How firm is the supplier's revised commitment?",
                "No revised date given",
                "Vague estimate such as 'a few weeks' or 'approximately'",
                "Specific revised date",
                "Specific date backed by evidence: booking, tracking number or confirmed production slot"));
        q.put("partial_offered", Question.noul(
                "Does the supplier offer to ship part of the quantity by the original ETA?"));
        q.put("systemic", Question.noul(
                "Is the cause likely to affect this supplier's other upcoming orders, not just this one?",
                "Allocation, industry-wide shortage, ongoing capacity or site problem",
                "A one-off issue with this shipment"));
        q.put("quality_risk", Question.noul(
                "Does the notice reveal a quality, safety or compliance problem with the goods themselves?"));
        return Collections.unmodifiableMap(q);
    }

    public NoticeSignals assess(SupplierNotice notice, OrderContext context) {
        SystemOneRequest request = SystemOneRequest.of(state(notice, context), QUESTIONS);
        EvaluationResult result = ScopedValue.where(JevContext.CORRELATION_ID, notice.noticeId())
                .call(() -> jev.evaluate(request));
        return signals(result);
    }

    static Map<String, Object> state(SupplierNotice notice, OrderContext context) {
        var order = new LinkedHashMap<String, Object>();
        order.put("number", context.order().number());
        order.put("item", context.item().description());
        order.put("quantity", context.order().quantity());
        order.put("original_eta", context.order().originalEta().toString());
        order.put("transport_mode", context.order().transportMode());
        var state = new LinkedHashMap<String, Object>();
        state.put("supplier", context.supplier().name());
        state.put("channel", notice.channel() == null ? "email" : notice.channel());
        state.put("purchase_order", order);
        state.put("notice", notice.message());
        return state;
    }

    static NoticeSignals signals(EvaluationResult result) {
        ChoiceAnswer window = result.choice("delay_window");
        ChoiceAnswer cause = result.choice("root_cause");
        ScoreAnswer commitment = result.score("commitment");
        return new NoticeSignals(
                result.noul("delay_confirmed").noul(),
                distribution(window, DelayWindow.class),
                window.as(DelayWindow.class),
                window.confidence(),
                distribution(cause, RootCause.class),
                cause.as(RootCause.class),
                cause.confidence(),
                commitment.level(),
                commitment.normalized(),
                result.noul("partial_offered").noul(),
                result.noul("systemic").noul(),
                result.noul("quality_risk").noul(),
                result.model(),
                result.requestId());
    }

    private static <E extends Enum<E>> Map<E, Double> distribution(ChoiceAnswer answer, Class<E> type) {
        var distribution = new EnumMap<E, Double>(type);
        answer.probabilities().forEach((option, p) ->
                distribution.put(Enum.valueOf(type, option.toUpperCase(Locale.ROOT)), p));
        return distribution;
    }
}
