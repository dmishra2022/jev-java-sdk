package io.github.dmishra2022.jev.supplychain.decision;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Planner-owned thresholds and costs, bound from {@code supplychain.policy.*}. Tuning these changes behaviour
 * without touching prompts or code.
 *
 * @param delayConfirmedAbove       treat the delay as real at or above this probability
 * @param delayDeniedBelow          treat the order as on time at or below this probability; in between: escalate
 * @param minDelayWindowConfidence  below this, the plan is still computed but sent for review
 * @param unspecifiedDelayDays      planning delay when the supplier gives no indication
 * @param slippagePessimism         vague commitments slip: delay × (1 + pessimism × (1 − commitment strength))
 * @param partialOfferedAbove       treat a partial shipment offer as real at or above this probability
 * @param partialShare              share of the quantity assumed to ship on time when a partial is offered
 * @param partialShipmentFee        extra handling and freight for a split shipment
 * @param freightRecoverableAbove   expedite only if root causes recoverable by freight have at least this mass
 * @param spotBuyFixedCost          admin, incoming inspection and set-up for a spot buy
 * @param qualityRiskAbove          treat the goods as suspect at or above this probability
 * @param systemicRiskAbove         flag the supplier for risk review at or above this probability
 * @param approvalLimit             mitigation spend (or unmitigated stockout cost) needing a planner's approval
 */
@ConfigurationProperties("supplychain.policy")
public record DecisionPolicy(
        @DefaultValue("0.7") double delayConfirmedAbove,
        @DefaultValue("0.3") double delayDeniedBelow,
        @DefaultValue("0.5") double minDelayWindowConfidence,
        @DefaultValue("21") int unspecifiedDelayDays,
        @DefaultValue("0.5") double slippagePessimism,
        @DefaultValue("0.7") double partialOfferedAbove,
        @DefaultValue("0.5") double partialShare,
        @DefaultValue("750") double partialShipmentFee,
        @DefaultValue("0.6") double freightRecoverableAbove,
        @DefaultValue("2500") double spotBuyFixedCost,
        @DefaultValue("0.7") double qualityRiskAbove,
        @DefaultValue("0.7") double systemicRiskAbove,
        @DefaultValue("25000") double approvalLimit) {

    public static final DecisionPolicy DEFAULT =
            new DecisionPolicy(0.7, 0.3, 0.5, 21, 0.5, 0.7, 0.5, 750, 0.6, 2500, 0.7, 0.7, 25_000);

    public DecisionPolicy {
        if (!(delayDeniedBelow < delayConfirmedAbove)) {
            throw new IllegalArgumentException("delayDeniedBelow must be below delayConfirmedAbove");
        }
        if (partialShare <= 0 || partialShare >= 1) {
            throw new IllegalArgumentException("partialShare must be in (0, 1)");
        }
        if (unspecifiedDelayDays < 1 || slippagePessimism < 0 || approvalLimit < 0) {
            throw new IllegalArgumentException("invalid supplychain.policy values");
        }
    }
}
