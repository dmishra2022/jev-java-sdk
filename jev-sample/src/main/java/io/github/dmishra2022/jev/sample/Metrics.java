package io.github.dmishra2022.jev.sample;

import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.JevListener;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/** A thread-safe listener that aggregates attempt metrics; swap for Micrometer/OpenTelemetry in production. */
public final class Metrics implements JevListener {

    private final LongAdder attempts = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final Queue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();

    @Override
    public void onAttemptEnd(Attempt attempt, int statusCode, Duration latency, JevException failure) {
        attempts.increment();
        latenciesMicros.add(latency.toNanos() / 1_000);
        if (failure != null) {
            failures.increment();
        }
    }

    @Override
    public void onRetryScheduled(Attempt attempt, Duration delay, JevException cause) {
        retries.increment();
    }

    public long attempts() {
        return attempts.sum();
    }

    public long retries() {
        return retries.sum();
    }

    public String summary() {
        List<Long> sorted = latenciesMicros.stream().sorted().toList();
        if (sorted.isEmpty()) {
            return "no attempts";
        }
        return "attempts=%d failed=%d retries=%d latency p50=%.1fms p95=%.1fms max=%.1fms".formatted(
                attempts.sum(), failures.sum(), retries.sum(),
                percentile(sorted, 0.50), percentile(sorted, 0.95), sorted.getLast() / 1000.0);
    }

    private static double percentile(List<Long> sorted, double p) {
        return sorted.get((int) Math.ceil(p * sorted.size()) - 1) / 1000.0;
    }
}
