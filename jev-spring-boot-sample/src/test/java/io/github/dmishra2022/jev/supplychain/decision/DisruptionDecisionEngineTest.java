package io.github.dmishra2022.jev.supplychain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.dmishra2022.jev.supplychain.assessment.NoticeSignals;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Action;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Flag;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Lever;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision.Plan;
import io.github.dmishra2022.jev.supplychain.domain.DelayWindow;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.ExpediteOption;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.InventoryPosition;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.OrderContext;
import io.github.dmishra2022.jev.supplychain.domain.RootCause;
import io.github.dmishra2022.jev.supplychain.erp.InMemoryPlanningRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DisruptionDecisionEngineTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC);
    static final InMemoryPlanningRepository ERP = new InMemoryPlanningRepository(CLOCK);
    static final OrderContext MCU = ERP.context("PO-4500012").orElseThrow();       // A-item, air expedite, 14d cover
    static final OrderContext CELLS = ERP.context("PO-4500013").orElseThrow();     // A-item, no expedite (DG)
    static final OrderContext ENCLOSURE = ERP.context("PO-4500014").orElseThrow(); // B-item, 5d cover
    static final OrderContext CARTON = ERP.context("PO-4500015").orElseThrow();    // C-item, 20.8d cover

    final DisruptionDecisionEngine engine = new DisruptionDecisionEngine(DecisionPolicy.DEFAULT, CLOCK);

    /** Signals builder: the named option gets {@code confidence}, the rest share the remainder evenly. */
    static final class Signals {
        double delay = 0.93;
        DelayWindow window = DelayWindow.ONE_TO_TWO_WEEKS;
        double windowConfidence = 0.9;
        RootCause cause = RootCause.LOGISTICS;
        double causeConfidence = 0.9;
        int commitment = 3;
        double partial = 0.05;
        double systemic = 0.1;
        double quality = 0.02;

        NoticeSignals build() {
            return new NoticeSignals(delay, spread(DelayWindow.class, window, windowConfidence), window,
                    windowConfidence, spread(RootCause.class, cause, causeConfidence), cause, causeConfidence,
                    commitment, commitment / 3.0, partial, systemic, quality, "jev-test", Optional.of("req-1"));
        }

        private static <E extends Enum<E>> EnumMap<E, Double> spread(Class<E> type, E chosen, double p) {
            var map = new EnumMap<E, Double>(type);
            E[] all = type.getEnumConstants();
            for (E e : all) {
                map.put(e, e == chosen ? p : (1 - p) / (all.length - 1));
            }
            return map;
        }
    }

    static Signals signals() {
        return new Signals();
    }

    @Nested
    class Gating {
        @Test
        void onTimeConfirmationNeedsNoAction() {
            var s = signals();
            s.delay = 0.04;
            DisruptionDecision decision = engine.decide("N", MCU, s.build());
            assertThat(decision.action()).isEqualTo(Action.NO_ACTION);
            assertThat(decision.autoExecutable()).isTrue();
            assertThat(decision.plansConsidered()).isEmpty();
            assertThat(decision.recommendedPlan()).isEmpty();
        }

        @Test
        void ambiguousNoticeIsEscalated() {
            var s = signals();
            s.delay = 0.5;
            DisruptionDecision decision = engine.decide("N", MCU, s.build());
            assertThat(decision.action()).isEqualTo(Action.ESCALATE_TO_PLANNER);
            assertThat(decision.autoExecutable()).isFalse();
            assertThat(decision.reviewReasons()).singleElement().asString().contains("ambiguous");
        }

        @Test
        void thresholdsAreInclusive() {
            var s = signals();
            s.delay = DecisionPolicy.DEFAULT.delayDeniedBelow();
            assertThat(engine.decide("N", MCU, s.build()).action()).isEqualTo(Action.NO_ACTION);
            s.delay = DecisionPolicy.DEFAULT.delayConfirmedAbove();
            assertThat(engine.decide("N", MCU, s.build()).action()).isEqualTo(Action.MITIGATE);
        }
    }

    @Nested
    class Planning {
        @Test
        void firmLogisticsDelayIsRecoveredByAirFreight() {
            DisruptionDecision decision = engine.decide("N-1", MCU, signals().build());

            // expected 0.9×10 + 0.02×(0+4+21+42+21) = 10.76 days, firm commitment → no slippage → 11 days
            assertThat(decision.projection()).get().satisfies(p -> {
                assertThat(p.expectedDelayDays()).isEqualTo(11);
                assertThat(p.daysToOriginalEta()).isEqualTo(12);
                assertThat(p.daysOfCover()).isEqualTo(14.0);
                assertThat(p.expectedArrival()).isEqualTo(LocalDate.parse("2026-10-24"));
                assertThat(p.unmitigatedShortUnits()).isEqualTo(9_000); // days 14..22
                assertThat(p.firstStockoutDate()).contains(LocalDate.parse("2026-10-15"));
            });
            assertThat(decision.action()).isEqualTo(Action.MITIGATE);
            Plan plan = decision.recommendedPlan().orElseThrow();
            assertThat(plan.levers()).containsExactly(Lever.EXPEDITE_FREIGHT);
            assertThat(plan.expediteMode()).contains("AIR");
            assertThat(plan.mitigationCost()).isEqualTo(7_000.0); // 20,000 × $0.35
            assertThat(plan.shortUnits()).isZero();
            assertThat(decision.autoExecutable()).isTrue();
            assertThat(decision.plansConsidered()).first().isEqualTo(plan);
            assertThat(decision.plansConsidered()).extracting(Plan::totalCost).isSorted();
        }

        @Test
        void allocationWithPartialOfferCombinesLeversAndFlagsTheSupplier() {
            var s = signals();
            s.window = DelayWindow.TWO_TO_FOUR_WEEKS;
            s.windowConfidence = 0.6;
            s.cause = RootCause.RAW_MATERIAL_SHORTAGE;
            s.commitment = 1;
            s.partial = 0.9;
            s.systemic = 0.85;
            DisruptionDecision decision = engine.decide("N-2", CELLS, s.build());

            assertThat(decision.flags()).contains(Flag.SUPPLIER_RISK_REVIEW);
            Plan plan = decision.recommendedPlan().orElseThrow();
            assertThat(plan.levers()).containsExactly(Lever.ACCEPT_PARTIAL_SHIPMENT, Lever.SPOT_BUY);
            assertThat(plan.spotBuySupplier()).contains("Kunshan Power Cells (SUP-1003)");
            assertThat(plan.shortUnits()).isZero();
            assertThat(decision.plansConsidered()).noneMatch(p -> p.levers().contains(Lever.EXPEDITE_FREIGHT));
        }

        @Test
        void freightCannotHelpGoodsStuckInCustoms() {
            var s = signals();
            s.cause = RootCause.CUSTOMS;
            DisruptionDecision decision = engine.decide("N", MCU, s.build());

            assertThat(decision.plansConsidered()).noneMatch(p -> p.levers().contains(Lever.EXPEDITE_FREIGHT));
            Plan plan = decision.recommendedPlan().orElseThrow();
            assertThat(plan.levers()).containsExactly(Lever.SPOT_BUY);
            assertThat(plan.spotBuyQuantity()).isEqualTo(8_000);              // capped by the distributor
            assertThat(plan.mitigationCost()).isEqualTo(8_000 * 1.30 + 2_500); // premium + fixed cost
            assertThat(plan.shortUnits()).isEqualTo(1_000);
            assertThat(decision.flags()).contains(Flag.RESIDUAL_STOCKOUT);
            assertThat(decision.reviewReasons()).anyMatch(r -> r.contains("A-item"));
            assertThat(decision.autoExecutable()).isFalse();
        }

        @Test
        void shortDelayAbsorbedByStockIsAccepted() {
            var s = signals();
            s.window = DelayWindow.UNDER_ONE_WEEK;
            s.commitment = 2;
            DisruptionDecision decision = engine.decide("N-4", CARTON, s.build());
            assertThat(decision.action()).isEqualTo(Action.ACCEPT_DELAY);
            assertThat(decision.recommendedPlan()).get().extracting(Plan::isDoNothing).isEqualTo(true);
            assertThat(decision.rationale()).anyMatch(r -> r.contains("absorbs"));
            assertThat(decision.autoExecutable()).isTrue();
        }

        @Test
        void acceptsACheapShortageWhenEveryLeverCostsMore() {
            var lowStock = with(CARTON, new InventoryPosition("CARTON-M", 3_600, 1_200, 0), List.of(),
                    Optional.of(new ExpediteOption("AIR", 0.50, 10)));
            var s = signals();
            s.window = DelayWindow.UNDER_ONE_WEEK;
            DisruptionDecision decision = engine.decide("N", lowStock, s.build());
            assertThat(decision.action()).isEqualTo(Action.ACCEPT_DELAY);
            // 8,400 units × $2 = $16,800 short vs $15,000 air + $2,400 residual shortage when expediting
            assertThat(decision.recommendedPlan().orElseThrow().stockoutCost()).isEqualTo(16_800.0);
            assertThat(decision.plansConsidered()).extracting(Plan::totalCost).containsExactly(16_800.0, 17_400.0);
            assertThat(decision.rationale()).anyMatch(r -> r.contains("costs more"));
            assertThat(decision.flags()).contains(Flag.RESIDUAL_STOCKOUT);
            assertThat(decision.reviewReasons()).isEmpty(); // C-item: residual stockout does not block execution
        }

        @Test
        void escalatesWhenNothingAvoidsAnExpensiveStockout() {
            var noLevers = with(MCU, MCU.inventory(), List.of(), Optional.empty());
            DisruptionDecision decision = engine.decide("N", noLevers, signals().build());
            assertThat(decision.action()).isEqualTo(Action.ESCALATE_TO_PLANNER);
            assertThat(decision.plansConsidered()).singleElement().extracting(Plan::isDoNothing).isEqualTo(true);
            assertThat(decision.reviewReasons()).anyMatch(r -> r.contains("No lever"));
            assertThat(decision.flags()).doesNotContain(Flag.RESIDUAL_STOCKOUT);
        }

        @Test
        void spendAboveTheApprovalLimitNeedsApproval() {
            var strict = new DisruptionDecisionEngine(new DecisionPolicy(0.7, 0.3, 0.5, 21, 0.5, 0.7, 0.5, 750, 0.6,
                    2500, 0.7, 0.7, 5_000), CLOCK);
            DisruptionDecision decision = strict.decide("N", MCU, signals().build());
            assertThat(decision.action()).isEqualTo(Action.MITIGATE);
            assertThat(decision.autoExecutable()).isFalse();
            assertThat(decision.reviewReasons()).anyMatch(r -> r.contains("approval limit"));
        }

        @Test
        void uncertainLengthAndMissingDateArePlannedPessimisticallyAndReviewed() {
            var s = signals();
            s.window = DelayWindow.UNSPECIFIED;
            s.windowConfidence = 0.4;
            s.commitment = 0;
            DisruptionDecision decision = engine.decide("N", MCU, s.build());
            assertThat(decision.reviewReasons()).anyMatch(r -> r.contains("uncertain"))
                    .anyMatch(r -> r.contains("no revised date"));
            // 0.4×21 + 0.12×(0+4+10+21+42) = 17.64 days × slippage 1.5 = 26.46 → 27
            assertThat(decision.projection().orElseThrow().expectedDelayDays()).isEqualTo(27);
        }
    }

    @Nested
    class QualityRisk {
        @Test
        void suspectGoodsOnlyAllowSpotBuysAndAreAlwaysReviewed() {
            var s = signals();
            s.delay = 0.5;
            s.cause = RootCause.QUALITY_HOLD;
            s.partial = 0.9;
            s.quality = 0.9;
            s.window = DelayWindow.NONE;
            DisruptionDecision decision = engine.decide("N-6", ENCLOSURE, s.build());

            assertThat(decision.flags()).contains(Flag.QUALITY_HOLD);
            assertThat(decision.projection().orElseThrow().expectedDelayDays()).isEqualTo(21);
            assertThat(decision.plansConsidered()).allMatch(p -> p.levers().stream()
                    .allMatch(l -> l == Lever.SPOT_BUY));
            assertThat(decision.recommendedPlan().orElseThrow().levers()).containsExactly(Lever.SPOT_BUY);
            assertThat(decision.autoExecutable()).isFalse();
        }
    }

    @Test
    void policyRejectsInconsistentValues() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new DecisionPolicy(0.3, 0.7, 0.5, 21, 0.5, 0.7, 0.5, 750, 0.6, 2500, 0.7, 0.7, 25_000));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new DecisionPolicy(0.7, 0.3, 0.5, 21, 0.5, 0.7, 1.0, 750, 0.6, 2500, 0.7, 0.7, 25_000));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new DecisionPolicy(0.7, 0.3, 0.5, 0, 0.5, 0.7, 0.5, 750, 0.6, 2500, 0.7, 0.7, 25_000));
    }

    static OrderContext with(OrderContext base, InventoryPosition inventory,
                             List<io.github.dmishra2022.jev.supplychain.domain.PlanningData.AlternateSource> alternates,
                             Optional<ExpediteOption> expedite) {
        return new OrderContext(base.order(), base.item(), base.supplier(), inventory, new ArrayList<>(alternates),
                expedite);
    }
}
