package io.github.dmishra2022.jev.supplychain.service;

import io.github.dmishra2022.jev.JevApiException;
import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.supplychain.assessment.NoticeAssessor;
import io.github.dmishra2022.jev.supplychain.assessment.NoticeSignals;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecision;
import io.github.dmishra2022.jev.supplychain.decision.DisruptionDecisionEngine;
import io.github.dmishra2022.jev.supplychain.domain.PlanningData.OrderContext;
import io.github.dmishra2022.jev.supplychain.domain.SupplierNotice;
import io.github.dmishra2022.jev.supplychain.erp.PlanningRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Assess a notice (Jev), decide (engine), log the decision for audit. */
@Service
public class DisruptionResponseService {

    private static final Logger LOG = LoggerFactory.getLogger(DisruptionResponseService.class);

    /** One element of a batch response: a decision or the reason there is none. */
    public record BatchItem(String noticeId, Optional<DisruptionDecision> decision, Optional<String> error) {
    }

    private final PlanningRepository planning;
    private final NoticeAssessor assessor;
    private final DisruptionDecisionEngine engine;

    public DisruptionResponseService(PlanningRepository planning, NoticeAssessor assessor,
                                     DisruptionDecisionEngine engine) {
        this.planning = planning;
        this.assessor = assessor;
        this.engine = engine;
    }

    public DisruptionDecision assess(SupplierNotice notice) {
        OrderContext context = planning.context(notice.purchaseOrder())
                .orElseThrow(() -> new UnknownPurchaseOrderException(notice.purchaseOrder()));
        NoticeSignals signals = assessor.assess(notice, context);
        DisruptionDecision decision = engine.decide(notice.noticeId(), context, signals);
        LOG.info("notice={} po={} action={} levers={} autoExecutable={} model={} requestId={}",
                notice.noticeId(), notice.purchaseOrder(), decision.action(),
                decision.recommendedPlan().map(DisruptionDecision.Plan::levers).orElse(List.of()),
                decision.autoExecutable(), signals.model(), signals.requestId().orElse("n/a"));
        return decision;
    }

    /**
     * Assesses notices concurrently, one virtual thread each; the SDK's bulkhead caps in-flight API calls. A failing
     * notice does not fail the batch. Results keep the input order.
     */
    public List<BatchItem> assessAll(List<SupplierNotice> notices) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<DisruptionDecision>>(notices.size());
            for (SupplierNotice notice : notices) {
                futures.add(executor.submit(() -> assess(notice)));
            }
            var results = new ArrayList<BatchItem>(notices.size());
            for (int i = 0; i < notices.size(); i++) {
                results.add(collect(notices.get(i).noticeId(), futures.get(i)));
            }
            return results;
        }
    }

    private static BatchItem collect(String noticeId, Future<DisruptionDecision> future) {
        try {
            return new BatchItem(noticeId, Optional.of(future.get()), Optional.empty());
        } catch (ExecutionException e) {
            LOG.warn("notice={} failed: {}", noticeId, e.getCause().toString());
            String error = switch (e.getCause()) {
                case UnknownPurchaseOrderException unknown -> unknown.getMessage();
                case JevApiException api -> "decision model error " + api.status()
                        + api.requestId().map(id -> " (upstream request " + id + ")").orElse("");
                case JevException jev -> "decision model error: " + jev.getClass().getSimpleName();
                default -> "internal error";
            };
            return new BatchItem(noticeId, Optional.empty(), Optional.of(error));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BatchItem(noticeId, Optional.empty(), Optional.of("interrupted"));
        }
    }
}
