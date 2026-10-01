package io.github.dmishra2022.jev.supplychain.decision;

import java.util.List;
import java.util.OptionalInt;

/**
 * Day-by-day projection of on-hand stock against constant demand until the last planned receipt. Day 0 is today;
 * goods received on a day can serve that day's demand. Unserved demand is lost (or back-ordered at a cost).
 */
final class InventorySimulator {

    record Receipt(int day, long quantity) {
    }

    /**
     * @param shortUnits          demand that could not be served
     * @param stockoutDays        days with unserved demand
     * @param firstStockoutDay    first such day
     * @param safetyStockBreached closing stock fell below the safety stock at least once
     */
    record Projection(long shortUnits, int stockoutDays, OptionalInt firstStockoutDay, boolean safetyStockBreached) {
    }

    private InventorySimulator() {
    }

    static Projection simulate(long onHand, long dailyDemand, long safetyStock, List<Receipt> receipts) {
        int horizon = receipts.stream().mapToInt(Receipt::day).max().orElse(0);
        long stock = onHand;
        long shortUnits = 0;
        int stockoutDays = 0;
        int firstStockout = -1;
        boolean breached = false;
        for (int day = 0; day < horizon; day++) {
            for (Receipt receipt : receipts) {
                if (receipt.day() == day) {
                    stock += receipt.quantity();
                }
            }
            if (stock < dailyDemand) {
                shortUnits += dailyDemand - stock;
                stockoutDays++;
                firstStockout = firstStockout < 0 ? day : firstStockout;
                stock = 0;
            } else {
                stock -= dailyDemand;
            }
            breached |= stock < safetyStock;
        }
        return new Projection(shortUnits, stockoutDays,
                firstStockout < 0 ? OptionalInt.empty() : OptionalInt.of(firstStockout), breached);
    }
}
