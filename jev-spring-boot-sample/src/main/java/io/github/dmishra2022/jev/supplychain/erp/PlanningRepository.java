package io.github.dmishra2022.jev.supplychain.erp;

import io.github.dmishra2022.jev.supplychain.domain.PlanningData.OrderContext;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.PurchaseOrder;
import java.util.List;
import java.util.Optional;

/** Read access to ERP planning data; replace the in-memory implementation with your ERP/WMS integration. */
public interface PlanningRepository {

    Optional<OrderContext> context(String purchaseOrder);

    List<PurchaseOrder> openOrders();
}
