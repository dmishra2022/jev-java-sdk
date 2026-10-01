package io.github.dmishra2022.jev.supplychain.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import io.github.dmishra2022.jev.JevAuthenticationException;
import io.github.dmishra2022.jev.JevConnectionException;
import io.github.dmishra2022.jev.JevRateLimitException;
import io.github.dmishra2022.jev.JevServerException;
import io.github.dmishra2022.jev.JevTimeoutException;
import io.github.dmishra2022.jev.supplychain.erp.PlanningRepository;
import io.github.dmishra2022.jev.supplychain.service.DisruptionResponseService;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Upstream failures map to 502/503/504 problem details without leaking the upstream body or credentials. */
@WebMvcTest(DisruptionController.class)
class ApiExceptionHandlerTest {

    static final String NOTICE = """
            {"noticeId":"N","purchaseOrder":"PO-4500012","message":"late"}""";
    static final HttpHeaders UPSTREAM = HttpHeaders.of(
            Map.of("x-typesafe-request-id", List.of("req-9")), (name, value) -> true);
    static final String ENDPOINT = "POST https://api.typesafe.ai/v1/systemone";

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    DisruptionResponseService service;

    @MockitoBean
    PlanningRepository planning;

    MvcTestResult assessWhenJevThrows(RuntimeException failure) {
        doThrow(failure).when(service).assess(any());
        return mvc.post().uri("/api/v1/disruptions/assessments").contentType(MediaType.APPLICATION_JSON)
                .content(NOTICE).exchange();
    }

    @Test
    void rateLimitIsServiceUnavailableWithRetryAfter() {
        MvcTestResult result = assessWhenJevThrows(new JevRateLimitException("HTTP 429 secret-body", 429,
                "secret-body", UPSTREAM, ENDPOINT, Duration.ofSeconds(7)));
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).headers().hasValue("Retry-After", "7");
        assertThat(result).bodyJson().extractingPath("$.upstreamRequestId").isEqualTo("req-9");
        assertThat(result).bodyText().doesNotContain("secret-body");
    }

    @Test
    void overloadedIsServiceUnavailableWithADefaultRetryAfter() {
        MvcTestResult result = assessWhenJevThrows(new JevServerException("HTTP 529", 529, "", UPSTREAM,
                ENDPOINT, null));
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result).headers().hasValue("Retry-After", "5");
    }

    @Test
    void otherServerErrorsAreBadGateway() {
        MvcTestResult result = assessWhenJevThrows(new JevServerException("HTTP 500", 500, "", UPSTREAM,
                ENDPOINT, null));
        assertThat(result).hasStatus(HttpStatus.BAD_GATEWAY);
        assertThat(result).headers().doesNotContainHeader("Retry-After");
    }

    @Test
    void rejectedCredentialsAreBadGatewayWithoutDetails() {
        MvcTestResult result = assessWhenJevThrows(new JevAuthenticationException("HTTP 401 invalid key ts-abc",
                401, "{}", UPSTREAM, ENDPOINT, null));
        assertThat(result).hasStatus(HttpStatus.BAD_GATEWAY);
        assertThat(result).bodyJson().extractingPath("$.detail")
                .isEqualTo("The decision model rejected this service's credentials.");
        assertThat(result).bodyText().doesNotContain("ts-abc");
    }

    @Test
    void timeoutsAreGatewayTimeoutAndConnectionFailuresBadGateway() {
        assertThat(assessWhenJevThrows(new JevTimeoutException("timed out", ENDPOINT, Duration.ofSeconds(10), null)))
                .hasStatus(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(assessWhenJevThrows(new JevConnectionException("refused", ENDPOINT, null)))
                .hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().extractingPath("$.detail").isEqualTo("The decision model is unreachable.");
    }
}
