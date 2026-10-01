package io.github.dmishra2022.jev.supplychain.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** ERP master and transactional data the planner needs for one purchase order line. */
public final class PlanningData {

    private PlanningData() {
    }

    /** ABC classification: how badly a stockout hurts. */
    public enum Criticality { A, B, C }

    /**
     * @param stockoutCostPerUnit lost margin plus penalties per unit of demand that cannot be served
     */
    public record Item(String sku, String description, Criticality criticality, double stockoutCostPerUnit) {
    }

    public record Supplier(String id, String name, String country) {
    }

    public record PurchaseOrder(String number, String sku, String supplierId, long quantity, double unitCost,
                                LocalDate originalEta, String transportMode) {
    }

    public record InventoryPosition(String sku, long onHand, long dailyDemand, int safetyStockDays) {
    }

    /** A qualified second source that can deliver a spot buy. */
    public record AlternateSource(String supplierId, String supplierName, String sku, double unitCost,
                                  int leadTimeDays, long maxQuantity) {
    }

    /** A faster transport mode for this lane, e.g. ocean to air. */
    public record ExpediteOption(String mode, double costPerUnit, int daysSaved) {
    }

    /** Everything known about one purchase order line, joined. */
    public record OrderContext(PurchaseOrder order, Item item, Supplier supplier, InventoryPosition inventory,
                               List<AlternateSource> alternates, Optional<ExpediteOption> expedite) {
    }
}
