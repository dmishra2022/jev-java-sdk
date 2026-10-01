package io.github.dmishra2022.jev.supplychain.web;

import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.PurchaseOrder;
import io.github.dmishra2022.jev.supplychain.domain.SupplierNotice;
import io.github.dmishra2022.jev.supplychain.erp.PlanningRepository;
import io.github.dmishra2022.jev.supplychain.service.DisruptionResponseService;
import io.github.dmishra2022.jev.supplychain.service.DisruptionResponseService.BatchItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DisruptionController {

    public record BatchRequest(@NotEmpty @Size(max = 100) List<@Valid SupplierNotice> notices) {
    }

    private final DisruptionResponseService service;
    private final PlanningRepository planning;

    public DisruptionController(DisruptionResponseService service, PlanningRepository planning) {
        this.service = service;
        this.planning = planning;
    }

    /** Assess one supplier notice and recommend a response. */
    @PostMapping("/disruptions/assessments")
    public DisruptionDecision assess(@Valid @RequestBody SupplierNotice notice) {
        return service.assess(notice);
    }

    /** Assess up to 100 notices concurrently (e.g. the overnight supplier inbox). */
    @PostMapping("/disruptions/assessments/batch")
    public List<BatchItem> assessBatch(@Valid @RequestBody BatchRequest request) {
        return service.assessAll(request.notices());
    }

    @GetMapping("/purchase-orders")
    public List<PurchaseOrder> openPurchaseOrders() {
        return planning.openOrders();
    }
}
