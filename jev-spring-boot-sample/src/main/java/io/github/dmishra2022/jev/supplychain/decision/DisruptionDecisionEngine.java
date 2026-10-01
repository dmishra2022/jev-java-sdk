package io.github.dmishra2022.jev.supplychain.decision;

import io.github.dmishra2022.jev.NoulAnswer;
import io.github.dmishra2022.jev.supplychain.assessment.NoticeSignals;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Action;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Flag;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Lever;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Plan;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Projection;
import io.github.dmishra2022.jev.supplychain.decision.InventorySimulator.Receipt;
import io.github.dmishra2022.jev.supplychain.domain.DelayWindow;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.AlternateSource;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.Criticality;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.ExpediteOption;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.OrderContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Deterministic, explainable decision logic. Jev supplies calibrated judgments about the notice; this class owns
 * the arithmetic: planning delay, inventory projection, costing every combination of levers, and governance
 * (approval limits, review reasons). Same inputs, same decision.
 */
@Component
public class DisruptionDecisionEngine {

    private final DecisionPolicy policy;
    private final Clock clock;

    public DisruptionDecisionEngine(DecisionPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    public DisruptionDecision decide(String noticeId, OrderContext context, NoticeSignals signals) {
        var rationale = new ArrayList<String>();
        var review = new ArrayList<String>();
        var flags = EnumSet.noneOf(Flag.class);
        LocalDate today = LocalDate.now(clock);
        var order = context.order();
        boolean suspectGoods = signals.qualityRisk() >= policy.qualityRiskAbove();

        rationale.add("Jev (%s): delay confirmed p=%.2f; window %s p=%.2f; cause %s p=%.2f; commitment level %d/3."
                .formatted(signals.model(), signals.delayConfirmed(), signals.likelyDelayWindow(),
                        signals.delayWindowConfidence(), signals.likelyRootCause(), signals.rootCauseConfidence(),
                        signals.commitmentLevel()));
        if (signals.systemicRisk() >= policy.systemicRiskAbove()) {
            flags.add(Flag.SUPPLIER_RISK_REVIEW);
            rationale.add("Cause looks systemic (p=%.2f): review sourcing strategy for %s."
                    .formatted(signals.systemicRisk(), context.supplier().name()));
        }
        if (suspectGoods) {
            flags.add(Flag.QUALITY_HOLD);
            review.add("Possible quality/compliance problem (p=%.2f): quarantine and inspect on receipt."
                    .formatted(signals.qualityRisk()));
        }

        var verdict = new NoulAnswer(signals.delayConfirmed())
                .verdict(policy.delayDeniedBelow(), policy.delayConfirmedAbove());
        if (!suspectGoods && verdict == NoulAnswer.Verdict.NO) {
            rationale.add("Supplier indicates on-time delivery; no action needed.");
            return new DisruptionDecision(noticeId, order.number(), order.sku(), Action.NO_ACTION,
                    Optional.empty(), true, Optional.empty(), List.of(), rationale, review, flags, Optional.of(signals));
        }
        if (!suspectGoods && verdict == NoulAnswer.Verdict.UNCERTAIN) {
            review.add("Notice is ambiguous about a delay (p=%.2f); confirm with the supplier."
                    .formatted(signals.delayConfirmed()));
            return new DisruptionDecision(noticeId, order.number(), order.sku(), Action.ESCALATE_TO_PLANNER,
                    Optional.empty(), false, Optional.empty(), List.of(), rationale, review, flags,
                    Optional.of(signals));
        }

        int delay = planningDelay(signals, suspectGoods, rationale);
        if (signals.delayWindowConfidence() < policy.minDelayWindowConfidence()) {
            review.add("Delay length is uncertain (confidence %.2f); plan assumes %d days."
                    .formatted(signals.delayWindowConfidence(), delay));
        }
        if (signals.commitmentLevel() == 0) {
            review.add("Supplier gave no revised date; chase a firm commitment.");
        }

        int daysToEta = (int) Math.max(0, ChronoUnit.DAYS.between(today, order.originalEta()));
        var inventory = context.inventory();
        double cover = inventory.dailyDemand() == 0 ? Double.POSITIVE_INFINITY
                : (double) inventory.onHand() / inventory.dailyDemand();

        List<Plan> plans = plans(context, signals, suspectGoods, daysToEta, delay);
        Plan doNothing = plans.stream().filter(Plan::isDoNothing).findFirst().orElseThrow();
        Plan best = plans.stream()
                .min(Comparator.comparingDouble(Plan::totalCost).thenComparingInt(p -> p.levers().size()))
                .orElseThrow();
        var unmitigated = simulate(context, receipts(context, daysToEta, delay, false));

        var projection = new Projection(today, order.originalEta(), daysToEta, round(cover, 1), delay,
                order.originalEta().plusDays(delay), doNothing.shortUnits(),
                unmitigated.firstStockoutDay().isPresent()
                        ? Optional.of(today.plusDays(unmitigated.firstStockoutDay().getAsInt()))
                        : Optional.empty());
        rationale.add("Stock covers %.1f days; original ETA in %d days; doing nothing leaves %,d units short (%s)."
                .formatted(cover, daysToEta, doNothing.shortUnits(), money(doNothing.stockoutCost())));

        Action action;
        if (best.isDoNothing()) {
            if (doNothing.shortUnits() == 0) {
                action = Action.ACCEPT_DELAY;
                rationale.add("Current stock absorbs the delay.");
            } else if (doNothing.stockoutCost() > policy.approvalLimit()) {
                action = Action.ESCALATE_TO_PLANNER;
                review.add("No lever reduces the %s stockout cost; needs a planner (customer allocation, substitutes)."
                        .formatted(money(doNothing.stockoutCost())));
            } else {
                action = Action.ACCEPT_DELAY;
                rationale.add("Every mitigation costs more than the %s shortage it avoids."
                        .formatted(money(doNothing.stockoutCost())));
            }
        } else {
            action = Action.MITIGATE;
            rationale.add("Recommended %s: spend %s, %,d units short; total %s vs %s doing nothing."
                    .formatted(best.levers(), money(best.mitigationCost()), best.shortUnits(),
                            money(best.totalCost()), money(doNothing.totalCost())));
            if (best.mitigationCost() > policy.approvalLimit()) {
                review.add("Mitigation spend %s exceeds the approval limit of %s."
                        .formatted(money(best.mitigationCost()), money(policy.approvalLimit())));
            }
        }
        if (action != Action.ESCALATE_TO_PLANNER && best.shortUnits() > 0) {
            flags.add(Flag.RESIDUAL_STOCKOUT);
            if (context.item().criticality() == Criticality.A) {
                review.add("A-item %s still stocks out (%,d units): consider customer allocation."
                        .formatted(order.sku(), best.shortUnits()));
            }
        }
        if (best.safetyStockBreached()) {
            flags.add(Flag.SAFETY_STOCK_BREACH);
        }
        boolean autoExecutable = action != Action.ESCALATE_TO_PLANNER && review.isEmpty();
        return new DisruptionDecision(noticeId, order.number(), order.sku(), action, Optional.of(best),
                autoExecutable, Optional.of(projection), plans, rationale, review, flags, Optional.of(signals));
    }

    /** Probability-weighted delay, inflated when the supplier's commitment is weak. */
    int planningDelay(NoticeSignals signals, boolean suspectGoods, List<String> rationale) {
        double weighted = 0;
        double mass = 0;
        for (Map.Entry<DelayWindow, Double> entry : signals.delayWindows().entrySet()) {
            weighted += entry.getValue() * entry.getKey().representativeDays().orElse(policy.unspecifiedDelayDays());
            mass += entry.getValue();
        }
        double expected = mass > 0 ? weighted / mass : policy.unspecifiedDelayDays();
        double slippage = 1 + policy.slippagePessimism() * (1 - signals.commitmentStrength());
        int delay = (int) Math.ceil(expected * slippage);
        if (suspectGoods && delay < policy.unspecifiedDelayDays()) {
            delay = policy.unspecifiedDelayDays();
            rationale.add("Suspect goods: assuming %d days until conforming stock arrives.".formatted(delay));
        } else {
            rationale.add("Planning delay %d days (expected %.1f × slippage %.2f for commitment strength %.2f)."
                    .formatted(delay, expected, slippage, signals.commitmentStrength()));
        }
        return delay;
    }

    private List<Plan> plans(OrderContext context, NoticeSignals signals, boolean suspectGoods, int daysToEta,
                             int delay) {
        boolean partial = !suspectGoods && signals.partialShipmentOffered() >= policy.partialOfferedAbove();
        Optional<ExpediteOption> expedite = context.expedite()
                .filter(_ -> !suspectGoods && delay > 0 && signals.freightRecoverable() >= policy.freightRecoverableAbove());
        var plans = new ArrayList<Plan>();
        for (boolean usePartial : partial ? List.of(false, true) : List.of(false)) {
            for (boolean useExpedite : expedite.isPresent() ? List.of(false, true) : List.of(false)) {
                plans.add(plan(context, daysToEta, delay, usePartial, useExpedite ? expedite : Optional.empty(), null));
                for (AlternateSource alternate : context.alternates()) {
                    Plan withSpotBuy = plan(context, daysToEta, delay, usePartial,
                            useExpedite ? expedite : Optional.empty(), alternate);
                    if (withSpotBuy != null) {
                        plans.add(withSpotBuy);
                    }
                }
            }
        }
        plans.sort(Comparator.comparingDouble(Plan::totalCost));
        return List.copyOf(plans);
    }

    /** Costs one lever combination; {@code null} when a spot buy would not reduce the shortage at all. */
    private Plan plan(OrderContext context, int daysToEta, int delay, boolean partial,
                      Optional<ExpediteOption> expedite, AlternateSource alternate) {
        var order = context.order();
        var levers = new ArrayList<Lever>();
        double spend = 0;
        if (partial) {
            levers.add(Lever.ACCEPT_PARTIAL_SHIPMENT);
            spend += policy.partialShipmentFee();
        }
        int lateArrival = expedite.map(e -> Math.max(0, delay - e.daysSaved())).orElse(delay);
        List<Receipt> receipts = new ArrayList<>(receipts(context, daysToEta, lateArrival, partial));
        if (expedite.isPresent()) {
            levers.add(Lever.EXPEDITE_FREIGHT);
            spend += lateQuantity(order.quantity(), partial) * expedite.get().costPerUnit();
        }
        long spotQuantity = 0;
        if (alternate != null) {
            long shortWithout = simulate(context, receipts).shortUnits();
            spotQuantity = Math.min(alternate.maxQuantity(), shortWithout);
            if (spotQuantity == 0 || alternate.leadTimeDays() >= daysToEta + lateArrival) {
                return null;
            }
            levers.add(Lever.SPOT_BUY);
            receipts.add(new Receipt(alternate.leadTimeDays(), spotQuantity));
            spend += spotQuantity * Math.max(0, alternate.unitCost() - order.unitCost()) + policy.spotBuyFixedCost();
        }
        var outcome = simulate(context, receipts);
        double stockoutCost = outcome.shortUnits() * context.item().stockoutCostPerUnit();
        return new Plan(List.copyOf(levers),
                Optional.ofNullable(alternate).map(a -> a.supplierName() + " (" + a.supplierId() + ")"),
                spotQuantity, expedite.map(ExpediteOption::mode), round(spend, 2), outcome.shortUnits(),
                outcome.stockoutDays(), round(stockoutCost, 2), round(spend + stockoutCost, 2),
                outcome.safetyStockBreached());
    }

    private List<Receipt> receipts(OrderContext context, int daysToEta, int lateBy, boolean partial) {
        long quantity = context.order().quantity();
        long late = lateQuantity(quantity, partial);
        var receipts = new ArrayList<Receipt>();
        if (late < quantity) {
            receipts.add(new Receipt(daysToEta, quantity - late));
        }
        receipts.add(new Receipt(daysToEta + lateBy, late));
        return receipts;
    }

    private long lateQuantity(long quantity, boolean partial) {
        return partial ? quantity - Math.round(quantity * policy.partialShare()) : quantity;
    }

    private static InventorySimulator.Projection simulate(OrderContext context, List<Receipt> receipts) {
        var inventory = context.inventory();
        return InventorySimulator.simulate(inventory.onHand(), inventory.dailyDemand(),
                (long) inventory.safetyStockDays() * inventory.dailyDemand(), receipts);
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(value * scale) / scale;
    }

    private static String money(double amount) {
        return "$%,.0f".formatted(amount);
    }
}
