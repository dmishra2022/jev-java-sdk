package io.github.dmishra2022.jev.supplychain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dmishra2022.jev.supplychain.decision.InventorySimulator.Receipt;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventorySimulatorTest {

    @Test
    void countsUnservedDemandUntilTheLastReceipt() {
        var projection = InventorySimulator.simulate(10, 5, 0, List.of(new Receipt(4, 100)));
        // day 0: 10 -> 5, day 1: 5 -> 0, days 2 and 3: 5 short each, day 4: receipt arrives
        assertThat(projection.shortUnits()).isEqualTo(10);
        assertThat(projection.stockoutDays()).isEqualTo(2);
        assertThat(projection.firstStockoutDay()).hasValue(2);
    }

    @Test
    void receiptsServeDemandOnTheirArrivalDay() {
        var projection = InventorySimulator.simulate(0, 5, 0, List.of(new Receipt(0, 20), new Receipt(3, 1)));
        assertThat(projection.shortUnits()).isZero();
        assertThat(projection.firstStockoutDay()).isEmpty();
    }

    @Test
    void partialStockOnADayIsConsumedBeforeCountingTheShortfall() {
        var projection = InventorySimulator.simulate(7, 5, 0, List.of(new Receipt(2, 100)));
        assertThat(projection.shortUnits()).isEqualTo(3);
        assertThat(projection.stockoutDays()).isEqualTo(1);
    }

    @Test
    void flagsSafetyStockBreaches() {
        assertThat(InventorySimulator.simulate(100, 10, 50, List.of(new Receipt(3, 10))).safetyStockBreached())
                .isFalse();
        assertThat(InventorySimulator.simulate(100, 10, 85, List.of(new Receipt(3, 10))).safetyStockBreached())
                .isTrue();
    }

    @Test
    void noReceiptsOrNoDemandMeansNothingToProject() {
        assertThat(InventorySimulator.simulate(0, 5, 0, List.of()).shortUnits()).isZero();
        assertThat(InventorySimulator.simulate(0, 0, 0, List.of(new Receipt(10, 1))).shortUnits()).isZero();
    }
}
