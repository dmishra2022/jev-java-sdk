package io.github.dmishra2022.jev.supplychain.decision;

import io.github.dmishra2022.jev.supplychain.assessment.NoticeSignals;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The recommended response to a supplier notice, with everything needed to audit it: the signals Jev extracted
 * (including model and request id), the projection, every plan considered and why the winner won.
 *
 * @param autoExecutable true when the plan may be executed without a planner (no approval, no review reasons)
 */
public record DisruptionDecision(
        String noticeId,
        String purchaseOrder,
        String sku,
        Action action,
        Optional<Plan> recommendedPlan,
        boolean autoExecutable,
        Optional<Projection> projection,
        List<Plan> plansConsidered,
        List<String> rationale,
        List<String> reviewReasons,
        Set<Flag> flags,
        Optional<NoticeSignals> signals) {

    public enum Action {
        /** The supplier confirms on-time delivery. */
        NO_ACTION,
        /** Let the delay happen: stock covers it, or every mitigation costs more than the shortage. */
        ACCEPT_DELAY,
        /** Execute the recommended levers. */
        MITIGATE,
        /** A planner must decide: the notice is ambiguous or no affordable plan avoids a costly stockout. */
        ESCALATE_TO_PLANNER
    }

    public enum Lever { ACCEPT_PARTIAL_SHIPMENT, EXPEDITE_FREIGHT, SPOT_BUY }

    public enum Flag {
        /** The cause likely affects future orders too: review dual sourcing / safety stock for this supplier. */
        SUPPLIER_RISK_REVIEW,
        /** Goods may be non-conforming: quarantine and inspect on receipt. */
        QUALITY_HOLD,
        SAFETY_STOCK_BREACH,
        /** Even the recommended plan leaves unserved demand. */
        RESIDUAL_STOCKOUT
    }

    /**
     * @param expectedDelayDays  planning delay after slippage adjustment
     * @param firstStockoutDate  when stock runs out if nothing is done
     */
    public record Projection(LocalDate today, LocalDate originalEta, int daysToOriginalEta, double daysOfCover,
                             int expectedDelayDays, LocalDate expectedArrival, long unmitigatedShortUnits,
                             Optional<LocalDate> firstStockoutDate) {
    }

    /**
     * One combination of levers, costed. {@code totalCost = mitigationCost + stockoutCost}.
     */
    public record Plan(List<Lever> levers, Optional<String> spotBuySupplier, long spotBuyQuantity,
                       Optional<String> expediteMode, double mitigationCost, long shortUnits, int stockoutDays,
                       double stockoutCost, double totalCost, boolean safetyStockBreached) {

        public boolean isDoNothing() {
            return levers.isEmpty();
        }
    }
}
