package io.github.dmishra2022.jev.supplychain.assessment;

import io.github.dmishra2022.jev.supplychain.domain.DelayWindow;
import io.github.dmishra2022.jev.supplychain.domain.RootCause;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * What Jev read out of a supplier notice: calibrated probabilities, not free text. The decision engine combines
 * these with ERP data; nothing here is a decision yet.
 *
 * @param delayConfirmed      probability the notice says the order arrives after its original ETA
 * @param delayWindows        probability of each delay window (sums to ~1)
 * @param commitmentLevel     0 = no revised date ... 3 = date backed by evidence
 * @param commitmentStrength  {@code commitmentLevel} scaled to [0, 1] from the expected score
 */
public record NoticeSignals(
        double delayConfirmed,
        Map<DelayWindow, Double> delayWindows,
        DelayWindow likelyDelayWindow,
        double delayWindowConfidence,
        Map<RootCause, Double> rootCauses,
        RootCause likelyRootCause,
        double rootCauseConfidence,
        int commitmentLevel,
        double commitmentStrength,
        double partialShipmentOffered,
        double systemicRisk,
        double qualityRisk,
        String model,
        Optional<String> requestId) {

    public NoticeSignals {
        delayWindows = Collections.unmodifiableMap(delayWindows);
        rootCauses = Collections.unmodifiableMap(rootCauses);
    }

    /** Probability mass on causes where premium freight can recover time. */
    public double freightRecoverable() {
        return rootCauses.entrySet().stream()
                .filter(e -> e.getKey().recoverableByFreight())
                .mapToDouble(Map.Entry::getValue)
                .sum();
    }
}
