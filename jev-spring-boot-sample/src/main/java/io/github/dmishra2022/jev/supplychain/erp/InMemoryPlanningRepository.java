package io.github.dmishra2022.jev.supplychain.erp;

import io.github.dmishra2022.jev.supplychain.domain.PlanningData.AlternateSource;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.Criticality;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.ExpediteOption;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.InventoryPosition;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.Item;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.OrderContext;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.PurchaseOrder;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.Supplier;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/**
 * Demo data for an electronics manufacturer building battery-powered devices. ETAs are relative to "today" so the
 * scenarios stay meaningful whenever the app runs.
 */
@Repository
public class InMemoryPlanningRepository implements PlanningRepository {

    private final Map<String, OrderContext> contexts;

    public InMemoryPlanningRepository(Clock clock) {
        LocalDate today = LocalDate.now(clock);

        var mcu = new Item("MCU-STM32F4", "32-bit microcontroller, LQFP-64", Criticality.A, 45.0);
        var cells = new Item("LIION-18650", "Li-ion cell 18650 3.6V 3000mAh", Criticality.A, 18.0);
        var enclosure = new Item("ENCL-PC-07", "Injection-moulded PC enclosure, model 7", Criticality.B, 9.0);
        var carton = new Item("CARTON-M", "Printed shipping carton, medium", Criticality.C, 2.0);

        var shenzhen = new Supplier("SUP-1001", "Shenzhen MicroSemi Co.", "CN");
        var ningde = new Supplier("SUP-1002", "Ningde Cell Technology", "CN");
        var moulding = new Supplier("SUP-2001", "Tijuana Precision Moulding", "MX");
        var packaging = new Supplier("SUP-3001", "Midwest Corrugated", "US");

        contexts = List.of(
                new OrderContext(
                        new PurchaseOrder("PO-4500012", mcu.sku(), shenzhen.id(), 20_000, 3.80, today.plusDays(12), "OCEAN"),
                        mcu, shenzhen, new InventoryPosition(mcu.sku(), 14_000, 1_000, 5),
                        List.of(new AlternateSource("SUP-9001", "Avnet (distributor)", mcu.sku(), 5.10, 4, 8_000)),
                        Optional.of(new ExpediteOption("AIR", 0.35, 16))),
                new OrderContext(
                        new PurchaseOrder("PO-4500013", cells.sku(), ningde.id(), 50_000, 1.95, today.plusDays(20), "OCEAN"),
                        cells, ningde, new InventoryPosition(cells.sku(), 60_000, 2_500, 7),
                        List.of(new AlternateSource("SUP-1003", "Kunshan Power Cells", cells.sku(), 2.30, 25, 30_000)),
                        // Lithium cells are dangerous goods: no air freight on this lane.
                        Optional.empty()),
                new OrderContext(
                        new PurchaseOrder("PO-4500014", enclosure.sku(), moulding.id(), 15_000, 0.85, today.plusDays(6), "TRUCK"),
                        enclosure, moulding, new InventoryPosition(enclosure.sku(), 4_000, 800, 3),
                        List.of(new AlternateSource("SUP-2002", "Local Moulding Partners", enclosure.sku(), 1.40, 7, 10_000)),
                        Optional.of(new ExpediteOption("TEAM_DRIVERS", 0.12, 3))),
                new OrderContext(
                        new PurchaseOrder("PO-4500015", carton.sku(), packaging.id(), 30_000, 0.22, today.plusDays(4), "TRUCK"),
                        carton, packaging, new InventoryPosition(carton.sku(), 25_000, 1_200, 5),
                        List.of(new AlternateSource("SUP-3002", "Box Depot", carton.sku(), 0.30, 2, 50_000)),
                        Optional.empty()))
                .stream().collect(Collectors.toUnmodifiableMap(c -> c.order().number(), Function.identity()));
    }

    @Override
    public Optional<OrderContext> context(String purchaseOrder) {
        return Optional.ofNullable(contexts.get(purchaseOrder));
    }

    @Override
    public List<PurchaseOrder> openOrders() {
        return contexts.values().stream().map(OrderContext::order)
                .sorted(Comparator.comparing(PurchaseOrder::number)).toList();
    }
}
