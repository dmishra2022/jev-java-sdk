package io.github.dmishra2022.jev.sample;

import io.github.dmishra2022.jev.EvaluationResult;
import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevContext;
import io.github.dmishra2022.jev.JevException;
import io.github.dmishra2022.jev.ModelInfo;
import io.github.dmishra2022.jev.structured.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Support-ticket triage on top of the Jev SDK.
 *
 * <p>Configuration (highest precedence first): system properties ({@code -Dtypesafe.api-key=...}),
 * environment variables ({@code TYPESAFE_API_KEY}, ...), then {@code ./jev.properties}. Without an API key the
 * app starts {@link OfflineTypeSafeServer} and talks to it over real HTTP, so every SDK code path still runs.
 *
 * <p>Run: {@code mvn -pl jev-sample -am install -DskipTests && mvn -pl jev-sample exec:exec}
 */
public final class TriageApp {

    static final String API_KEY_PROPERTY = "typesafe.api-key";

    private TriageApp() {
    }

    public static void main(String[] args) throws Exception {
        Properties file = loadProperties(Path.of("jev.properties"));
        boolean online = hasApiKey(file, System.getenv("TYPESAFE_API_KEY"));
        int failEvery = args.length > 0 && args[0].equals("--no-chaos") ? 0 : 5;
        System.exit(run(System.out, file, online, failEvery));
    }

    static boolean hasApiKey(Properties file, String environmentValue) {
        return !isBlank(System.getProperty(API_KEY_PROPERTY)) || !isBlank(environmentValue)
                || !isBlank(file.getProperty(API_KEY_PROPERTY));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static Properties loadProperties(Path path) throws IOException {
        var properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
            }
        }
        return properties;
    }

    /** @return process exit code */
    static int run(PrintStream out, Properties file, boolean online, int failEvery) throws Exception {
        var metrics = new Metrics();
        JevClient.Builder builder = JevClient.builder().properties(file).listener(metrics);
        OfflineTypeSafeServer offline = null;
        if (!online) {
            offline = new OfflineTypeSafeServer(failEvery);
            builder.apiKey("offline-demo-key").baseUrl(offline.baseUri().toString())
                    .defaultModel(OfflineTypeSafeServer.MODEL);
            out.println("No TYPESAFE_API_KEY found: using the offline heuristic server at " + offline.baseUri()
                    + (failEvery > 0 ? " (injecting 529 on every " + failEvery + "th call)" : ""));
        }
        try (JevClient client = builder.build()) {
            out.println("Config: " + client.config());
            var triage = new TicketTriage(client, TicketTriage.Policy.DEFAULT);

            section(out, "Models");
            for (ModelInfo model : client.listModels()) {
                out.printf("  %-24s %s%n", model.name(), model.description().orElse(""));
            }

            section(out, "Single ticket (4 questions, 1 call)");
            print(out, triage.triage(SampleTickets.ALL.getFirst()));

            section(out, "Batch: %d tickets concurrently on virtual threads, settle-all"
                    .formatted(SampleTickets.ALL.size()));
            long start = System.nanoTime();
            List<Outcome<TriageDecision>> outcomes = ScopedValue.where(JevContext.CORRELATION_ID, "batch-42")
                    .call(() -> triage.triageAll(SampleTickets.ALL, Duration.ofSeconds(20)));
            var routes = new EnumMap<TriageDecision.Route, Integer>(TriageDecision.Route.class);
            int failed = 0;
            for (Outcome<TriageDecision> outcome : outcomes) {
                switch (outcome) {
                    case Outcome.Success<TriageDecision>(TriageDecision decision) -> {
                        print(out, decision);
                        routes.merge(decision.route(), 1, Integer::sum);
                    }
                    case Outcome.Failure<TriageDecision>(Throwable error) -> {
                        out.println("  FAILED: " + error);
                        failed++;
                    }
                }
            }
            out.printf("  routes=%s failed=%d wall=%dms%n", routes, failed, (System.nanoTime() - start) / 1_000_000);

            section(out, "Hedged call (second request after 150ms)");
            print(out, triage.triageHedged(SampleTickets.ALL.get(1), Duration.ofMillis(150)));

            section(out, "Async composition with CompletableFuture");
            CompletableFuture<EvaluationResult> a = client.evaluateAsync(TicketTriage.requestFor(SampleTickets.ALL.get(2)));
            CompletableFuture<EvaluationResult> b = client.evaluateAsync(TicketTriage.requestFor(SampleTickets.ALL.get(3)));
            Map<String, Double> urgency = a.thenCombine(b, (x, y) -> Map.of(
                    "T-1003", x.noul("urgent").noul(), "T-1004", y.noul("urgent").noul())).join();
            out.println("  urgency " + new TreeMap<>(urgency));

            section(out, "Metrics");
            out.println("  " + metrics.summary());
            return failed == 0 ? 0 : 2;
        } catch (JevException e) {
            out.println("Jev call failed: " + e);
            return 1;
        } finally {
            if (offline != null) {
                offline.close();
            }
        }
    }

    private static void section(PrintStream out, String title) {
        out.println();
        out.println("== " + title);
    }

    private static void print(PrintStream out, TriageDecision d) {
        out.printf("  %-7s %-10s conf=%.2f urgency=%.2f frustration=%d churn=%.2f priority=%.2f -> %-12s (%s)%n",
                d.ticket().id(), d.department(), d.departmentConfidence(), d.urgency(), d.frustrationLevel(),
                d.churnRisk(), d.priority(), d.route(), d.reason());
    }
}
