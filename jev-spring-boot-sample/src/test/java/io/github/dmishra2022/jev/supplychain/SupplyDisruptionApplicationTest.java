package io.github.dmishra2022.jev.supplychain;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Full application over real HTTP (Tomcat on virtual threads) against the offline Jev stand-in. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jev.mode=offline", "supplychain.business-date=2026-10-01"})
@AutoConfigureRestTestClient
@Import(SupplyDisruptionApplicationTest.ThreadProbe.class)
class SupplyDisruptionApplicationTest {

    static final String ROLLED_VESSEL = "Due to severe congestion at Yantian port our vessel booking was rolled to "
            + "the next sailing. Goods are loaded; revised ETA is 10 days later than planned. Booking ref MAEU-778812.";

    /** Records whether each request was handled on a virtual thread. */
    @TestConfiguration
    static class ThreadProbe implements WebMvcConfigurer {
        final List<Boolean> virtual = new CopyOnWriteArrayList<>();

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override
                public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    virtual.add(Thread.currentThread().isVirtual());
                    return true;
                }
            });
        }
    }

    @Autowired
    RestTestClient http;

    @Autowired
    ThreadProbe probe;

    @Autowired
    MeterRegistry meters;

    static Map<String, Object> notice(String id, String po, String message) {
        return Map.of("noticeId", id, "purchaseOrder", po, "channel", "email", "message", message);
    }

    @Test
    void assessesANoticeEndToEnd() {
        http.post().uri("/api/v1/disruptions/assessments").contentType(MediaType.APPLICATION_JSON)
                .body(notice("N-1", "PO-4500012", ROLLED_VESSEL))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.action").isEqualTo("MITIGATE")
                .jsonPath("$.recommendedPlan.levers[0]").isEqualTo("EXPEDITE_FREIGHT")
                .jsonPath("$.recommendedPlan.shortUnits").isEqualTo(0)
                .jsonPath("$.autoExecutable").isEqualTo(true)
                .jsonPath("$.projection.originalEta").isEqualTo("2026-10-13")
                .jsonPath("$.signals.likelyRootCause").isEqualTo("LOGISTICS")
                .jsonPath("$.signals.model").isEqualTo("jev-offline-supplychain-heuristic")
                .jsonPath("$.signals.requestId").value(id -> assertThat((String) id).startsWith("offline-"));

        assertThat(meters.find("jev.client.attempts").tag("path", "/v1/systemone").timers()).isNotEmpty();
    }

    @Test
    void batchRunsConcurrentlyKeepsOrderAndIsolatesFailures() {
        var body = Map.of("notices", List.of(
                notice("A", "PO-4500015", "Trucks are short this week, delivery will be 2 days later."),
                notice("B", "PO-999", "We are late."),
                notice("C", "PO-4500012", "Please note the order is on schedule and will ship as planned.")));
        http.post().uri("/api/v1/disruptions/assessments/batch").contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[0].noticeId").isEqualTo("A")
                .jsonPath("$[0].decision.action").isEqualTo("ACCEPT_DELAY")
                .jsonPath("$[1].error").isEqualTo("No open purchase order PO-999")
                .jsonPath("$[1].decision").doesNotExist()
                .jsonPath("$[2].decision.action").isEqualTo("NO_ACTION");
    }

    @Test
    void requestsAreHandledOnVirtualThreads() {
        http.get().uri("/api/v1/purchase-orders").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(4);
        assertThat(probe.virtual).isNotEmpty().containsOnly(true);
    }

    @Test
    void invalidAndUnknownInputsAreProblemDetails() {
        http.post().uri("/api/v1/disruptions/assessments").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("noticeId", "", "purchaseOrder", "PO-4500012", "message", ""))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        http.post().uri("/api/v1/disruptions/assessments/batch").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("notices", List.of()))
                .exchange()
                .expectStatus().isBadRequest();
        http.post().uri("/api/v1/disruptions/assessments").contentType(MediaType.APPLICATION_JSON)
                .body(notice("X", "PO-999", "late"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.detail").isEqualTo("No open purchase order PO-999");
    }

    @Test
    void healthReportsTheJevDependency() {
        http.get().uri("/actuator/health/jev").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.details.mode").isEqualTo("offline")
                .jsonPath("$.details.models[0]").isEqualTo("jev-offline-supplychain-heuristic");
    }
}
