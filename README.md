# Jev Java SDK

A Java 26 client for the [TypeSafe](https://docs.typesafe.ai/introduction) System One API (Jev models): ask
atomic questions about any state and get typed, calibrated answers (`noul`, `choice`, `score`).

> Community SDK, not affiliated with TypeSafe. Wire format follows the public API reference.

| Module | What it is | Needs `--enable-preview`? |
|---|---|---|
| `jev-sdk` | The client: typed questions/answers, retries, timeouts, bulkhead, virtual threads, `ScopedValue` correlation | No |
| `jev-sdk-structured` | Fan-out, settle-all, hedging and chunking built on `StructuredTaskScope` | Yes (preview in JDK 26) |
| `jev-sample` | Support-ticket triage app; runs offline without an API key | Yes |
| `jev-spring-boot-sample` | Spring Boot 4 supply-disruption decision service on virtual threads ([README](jev-spring-boot-sample/README.md)) | No |

Only dependency: Jackson 3 (`tools.jackson.core:jackson-databind`).

## Install

```xml
<dependency>
    <groupId>io.github.dmishra2022</groupId>
    <artifactId>jev-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
<!-- optional, requires compiling and running with enable-preview on JDK 26 -->
<dependency>
    <groupId>io.github.dmishra2022</groupId>
    <artifactId>jev-sdk-structured</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle: `implementation("io.github.dmishra2022:jev-sdk:0.1.0")`.

## Quick start

```java
try (JevClient client = JevClient.create()) {            // reads TYPESAFE_API_KEY
    EvaluationResult result = client.evaluate(
            "Customer: I was charged twice, please refund the duplicate.",
            Map.of(
                    "is_billing", Question.noul("Is this a billing issue?"),
                    "sentiment", Question.choice("What is the sentiment?", "positive", "neutral", "negative"),
                    "urgency", Question.score("How urgent is it?", "Low", "Medium", "High")));

    result.noul("is_billing").isTrue();                    // 0.94 >= 0.5
    result.choice("sentiment").choice();                   // "negative"
    result.choice("sentiment").isConfident(0.8);           // gate automation on confidence
    result.score("urgency").level();                       // 0..2, index into the levels
    result.usage().totalTokens();
    result.requestId();                                    // quote this in support tickets
}
```

Use `SystemOneRequest.builder()` to keep question order, set a model per request, or pass structured state
(any Jackson-serializable object, e.g. a record). Enums map to choices directly:

```java
enum Department implements ChoiceOption {
    BILLING("Payments, refunds"), TECHNICAL("Bugs and outages");
    private final String description;
    Department(String d) { description = d; }
    public Object description() { return description; }
}

Question q = Question.choice("Which team should handle this?", Department.class);
Department team = result.choice("team").as(Department.class);
```

`noul` criteria, `choice` option descriptions and `score` levels accept any JSON value, not only strings.

## Configuration

Precedence, highest first: explicit `JevClient.builder()` calls, `builder().properties(...)`, system
properties, environment variables, defaults.

| Property / env var | Default | Notes |
|---|---|---|
| `typesafe.api-key` / `TYPESAFE_API_KEY` | required | Never logged; `toString()` redacts it |
| `typesafe.base-url` / `TYPESAFE_BASE_URL` | `https://api.typesafe.ai` | http(s), no query/userinfo |
| `typesafe.default-model` / `TYPESAFE_DEFAULT_MODEL` | `jev-latest` | Pin a version (e.g. `jev-1`) in production |
| `typesafe.connect-timeout` / `TYPESAFE_CONNECT_TIMEOUT` | `10s` | `500ms`, `10s`, `2m`, `1h`, `10`, or `PT10S` |
| `typesafe.request-timeout` / `TYPESAFE_REQUEST_TIMEOUT` | `10s` | Per attempt |
| `typesafe.total-timeout` / `TYPESAFE_TOTAL_TIMEOUT` | `30s` | Whole call incl. retries; `none` disables |
| `typesafe.max-retries` / `TYPESAFE_MAX_RETRIES` | `2` | |
| `typesafe.max-concurrent-requests` / `TYPESAFE_MAX_CONCURRENT_REQUESTS` | `64` | Client-side bulkhead |
| `typesafe.http-version` / `TYPESAFE_HTTP_VERSION` | `2` | `1.1`, `2`, `3` (HTTP/3 via Alt-Svc, falls back) |

Builder-only: `retryPolicy`, default `header`s, `correlationIdHeader`, `listener`, `proxy`, `sslContext`,
your own `httpClient` (the SDK then does not close it), and a custom `jsonMapper`.

Per call, `RequestOptions` overrides model, timeout and retry policy, and adds headers or extra body fields:

```java
client.evaluate(request, RequestOptions.builder().model("jev-1").timeout(Duration.ofSeconds(3)).build());
```

## Reliability

- **Retries**: 408, 429 and 5xx (including 529 overloaded), connection errors and timeouts; exponential
  back-off 0.5 s doubling to 5 s with 25 % jitter; honors `retry-after-ms` / `Retry-After` up to 60 s; never
  retries 4xx validation errors, malformed responses or interrupts. Add your own rule with
  `RetryPolicy.builder().retryPredicate(...)`.
- **Deadlines**: every attempt's timeout is capped by the remaining total budget; a retry that cannot finish in
  the budget is not attempted.
- **Bulkhead**: at most `maxConcurrentRequests` calls in flight; waiting is bounded by the call's budget.
- **Strict decoding**: a missing or wrongly typed answer raises `JevResponseValidationException` with the JSON
  path, instead of surfacing `null`s later.
- **Interrupts**: `JevInterruptedException`, with the thread's interrupt flag restored. Cancelling an
  `evaluateAsync` future interrupts the in-flight request.

Errors are unchecked and form a small hierarchy:

```
JevException
├── JevConfigurationException
├── JevConnectionException ── JevTimeoutException
├── JevInterruptedException
└── JevApiException (status, body, headers, requestId, retryAfter)
    ├── JevBadRequestException 400     ├── JevUnprocessableEntityException 422
    ├── JevAuthenticationException 401 ├── JevRateLimitException 429
    ├── JevPermissionDeniedException 403
    ├── JevNotFoundException 404       └── JevServerException 5xx (isOverloaded() for 529)
    └── JevResponseValidationException (fieldPath)
```

## Concurrency

The client is thread-safe and meant to be shared. Blocking calls are cheap on virtual threads; the owned
`HttpClient` runs on a virtual-thread executor.

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = tickets.stream().map(t -> executor.submit(() -> client.evaluate(t, questions))).toList();
}
CompletableFuture<EvaluationResult> f = client.evaluateAsync(request);   // one virtual thread per call
```

**Correlation ids** flow through `ScopedValue` (into child threads, async calls and structured subtasks) and are
sent as `X-Correlation-Id`:

```java
ScopedValue.where(JevContext.CORRELATION_ID, "order-42").run(() -> client.evaluate(request));
```

**Observability**: implement `JevListener` (`onAttemptStart`, `onAttemptEnd`, `onRetryScheduled`) to feed
Micrometer or OpenTelemetry. Listener exceptions are logged and never break a call.

### Structured concurrency (`jev-sdk-structured`)

```java
var jev = new StructuredJev(client);
List<EvaluationResult> all = jev.evaluateAll(requests, Duration.ofSeconds(5));          // fail fast, ordered
List<Outcome<EvaluationResult>> settled = jev.evaluateAllSettled(requests, Duration.ofSeconds(5));
EvaluationResult fast = jev.hedged(request, Duration.ofMillis(300));                     // cut tail latency
EvaluationResult big = jev.evaluateChunked(state, manyQuestions, 20);                     // split, merge
```

When one subtask fails, the others are cancelled and their HTTP requests interrupted. `StructuredTaskScope` is a
preview API in JDK 26, so compile and run with `--enable-preview` (see `jev-sample/pom.xml`). The core module has
no such requirement.

## Sample app

```bash
./mvnw -pl jev-sample -am install -DskipTests
./mvnw -pl jev-sample exec:exec                 # offline: built-in heuristic server, injects 529s
TYPESAFE_API_KEY=ts-... ./mvnw -pl jev-sample exec:exec
```

Copy `jev-sample/jev.properties.example` to `jev-sample/jev.properties` to experiment with configuration.

## Building and quality gates

Requires JDK 26 (the enforcer rejects anything else).

```bash
./mvnw verify                          # all tests + 100% JaCoCo gate (instruction, branch, line, complexity, method, class)
./mvnw -Pmutation -pl jev-sdk verify   # PIT mutation testing
```

Reports: `*/target/site/jacoco/index.html`. Releasing: see [RELEASING.md](RELEASING.md).

## License

Apache License 2.0.
