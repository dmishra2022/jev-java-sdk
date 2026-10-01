package io.github.dmishra2022.jev.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dmishra2022.jev.JevClient;
import io.github.dmishra2022.jev.JevServerException;
import io.github.dmishra2022.jev.structured.Outcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TriageAppTest {

    private static JevClient client(OfflineTypeSafeServer server, Metrics metrics) {
        return JevClient.builder().apiKey("test-key").baseUrl(server.baseUri().toString())
                .defaultModel(OfflineTypeSafeServer.MODEL).listener(metrics).build();
    }

    private static Ticket ticket(String id) {
        return SampleTickets.ALL.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void routesTicketsByAnswersAndConfidence() throws Exception {
        try (var server = new OfflineTypeSafeServer(0); var client = client(server, new Metrics())) {
            var triage = new TicketTriage(client, TicketTriage.Policy.DEFAULT);

            TriageDecision billing = triage.triage(ticket("T-1001"));
            assertThat(billing.department()).isEqualTo(Department.BILLING);
            assertThat(billing.route()).isEqualTo(TriageDecision.Route.AUTO_ASSIGN);
            assertThat(billing.model()).isEqualTo(OfflineTypeSafeServer.MODEL);

            TriageDecision angry = triage.triage(ticket("T-1005"));
            assertThat(angry.route()).isEqualTo(TriageDecision.Route.ESCALATE);
            assertThat(angry.frustrationLevel()).isEqualTo(3);
            assertThat(angry.priority()).isGreaterThan(billing.priority());

            TriageDecision vague = triage.triage(ticket("T-1008"));
            assertThat(vague.route()).isEqualTo(TriageDecision.Route.HUMAN_REVIEW);
            assertThat(vague.reason()).startsWith("low confidence");
        }
    }

    @Test
    void batchPreservesOrderAndSettlesEveryTicket() throws Exception {
        try (var server = new OfflineTypeSafeServer(0); var client = client(server, new Metrics())) {
            List<Outcome<TriageDecision>> outcomes = new TicketTriage(client, TicketTriage.Policy.DEFAULT)
                    .triageAll(SampleTickets.ALL, Duration.ofSeconds(10));

            assertThat(outcomes).hasSize(SampleTickets.ALL.size()).allMatch(Outcome::isSuccess);
            assertThat(outcomes.stream().map(o -> o.orElseThrow().ticket()).toList()).isEqualTo(SampleTickets.ALL);
        }
    }

    @Test
    void transientOverloadIsRetriedTransparently() throws Exception {
        var metrics = new Metrics();
        try (var server = new OfflineTypeSafeServer(2); var client = client(server, metrics)) {
            var triage = new TicketTriage(client, TicketTriage.Policy.DEFAULT);
            triage.triage(ticket("T-1001"));
            triage.triage(ticket("T-1002"));

            assertThat(metrics.retries()).isEqualTo(1);
            assertThat(metrics.attempts()).isEqualTo(3);
            assertThat(server.calls()).isEqualTo(3);
        }
    }

    @Test
    void persistentOverloadSurfacesAsTypedException() throws Exception {
        var metrics = new Metrics();
        try (var server = new OfflineTypeSafeServer(1); var client = client(server, metrics)) {
            var triage = new TicketTriage(client, TicketTriage.Policy.DEFAULT);

            assertThatThrownBy(() -> triage.triage(ticket("T-1001")))
                    .isInstanceOfSatisfying(JevServerException.class, e -> assertThat(e.isOverloaded()).isTrue());
            assertThat(metrics.attempts()).isEqualTo(3);
            assertThat(metrics.summary()).contains("failed=3", "retries=2");
        }
    }

    @Test
    void appRunsEndToEndOffline() throws Exception {
        var bytes = new ByteArrayOutputStream();
        int exit = TriageApp.run(new PrintStream(bytes, true, StandardCharsets.UTF_8), new Properties(), false, 0);
        String output = bytes.toString(StandardCharsets.UTF_8);

        assertThat(exit).isZero();
        assertThat(output).contains("offline heuristic server", "apiKey=offl****", OfflineTypeSafeServer.MODEL,
                "T-1008", "ESCALATE", "failed=0", "urgency {T-1003=", "== Metrics");
        assertThat(output).doesNotContain("offline-demo-key");
    }

    @Test
    void apiKeyDetectionAndPropertiesLoading(@TempDir Path dir) throws Exception {
        assertThat(TriageApp.hasApiKey(new Properties(), "  ")).isFalse();
        assertThat(TriageApp.hasApiKey(new Properties(), "ts-key")).isTrue();

        assertThat(TriageApp.loadProperties(dir.resolve("missing.properties"))).isEmpty();
        Path file = Files.writeString(dir.resolve("jev.properties"), "typesafe.api-key=ts-file\n");
        Properties loaded = TriageApp.loadProperties(file);
        assertThat(TriageApp.hasApiKey(loaded, null)).isTrue();
    }

    @Test
    void metricsWithoutAttempts() {
        assertThat(new Metrics().summary()).isEqualTo("no attempts");
    }
}
