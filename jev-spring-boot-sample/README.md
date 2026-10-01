# Supply disruption response (Spring Boot 4 + Jev)

A Spring Boot 4.1 service on Java 26 virtual threads that decides how to respond when a supplier says a purchase
order will be late.

Planners get dozens of these notices a day: e-mails, portal updates, call notes. Each one forces the same
decision: **accept the delay, take a partial shipment, pay for faster freight, spot-buy from a second source, or
escalate**. The right answer depends on what the message actually says (how late? why? how firm is the new
date? is it only this order?) *and* on hard numbers in the ERP (stock cover, demand, alternates, freight costs).

## How it decides

```
supplier notice ──► NoticeAssessor ──Jev, 7 atomic questions──► NoticeSignals (calibrated probabilities)
                                                                      │
ERP data (PO, stock, demand, alternates, expedite lanes) ─────────────┤
                                                                      ▼
                                                     DisruptionDecisionEngine (deterministic)
                                                       1. gate: on time? ambiguous? suspect goods?
                                                       2. planning delay = Σ p(window)·days × slippage(commitment)
                                                       3. simulate stock day by day for every lever combination
                                                       4. pick the cheapest plan (spend + stockout cost)
                                                       5. governance: approval limit, review reasons, flags
                                                                      ▼
                                                     DisruptionDecision (action, plan, every plan costed, rationale)
```

**Jev answers only what language models are good at, one question each:**

| Question | Type | Used for |
|---|---|---|
| `delay_confirmed` | noul | Gate: on time / ambiguous (escalate) / delayed |
| `delay_window` | choice (`DelayWindow` enum) | Probability-weighted planning delay |
| `root_cause` | choice (`RootCause` enum) | Whether premium freight can recover time (not for customs or quality holds) |
| `commitment` | score, 4 levels | Slippage: vague dates get pessimistic padding |
| `partial_offered` | noul | Enables the partial-shipment lever |
| `systemic` | noul | `SUPPLIER_RISK_REVIEW` flag (dual sourcing, safety stock) |
| `quality_risk` | noul | `QUALITY_HOLD`: only spot buys allowed, always reviewed |

**Code owns the arithmetic and the governance.** Every threshold and cost lives in
`supplychain.policy.*` (see `application.yml`), so planners can tune it without touching prompts. The same inputs
always give the same decision, and the response carries the Jev model and request id for audit.

Example outcomes with the seeded data (`supplychain.business-date=2026-10-01`):

| Notice | Decision |
|---|---|
| Vessel rolled at Yantian, +10 days, booking ref | `MITIGATE` with air freight: $7k spend instead of $450k in MCU stockouts, auto-executable |
| Cathode allocation, 50% on time, rest "approximately 3 weeks", into next quarter | `MITIGATE` with partial shipment + spot buy from Kunshan, plus `SUPPLIER_RISK_REVIEW` |
| Moulding machine breakdown, 9 days late | `MITIGATE` with team drivers + local moulder; small residual stockout on a B-item |
| Trucks short, 2 days late (cartons, 20 days of cover) | `ACCEPT_DELAY`: stock absorbs it |
| "On schedule, will ship as planned" | `NO_ACTION` |
| Defects found at final inspection | `QUALITY_HOLD`: spot buy only, sent for review |
| "Just checking in, will let you know" | `ESCALATE_TO_PLANNER`: ambiguous |

## Virtual threads

`spring.threads.virtual.enabled=true` puts Tomcat request handling on virtual threads, so a request blocked on a
Jev call holds no platform thread. The batch endpoint fans out one virtual thread per notice
(`Executors.newVirtualThreadPerTaskExecutor()`). The SDK's own bulkhead
(`jev.max-concurrent-requests`) caps concurrent API calls per instance, and each notice id flows to the API as
`X-Correlation-Id` through a `ScopedValue`.

## Run it

```bash
# from the repository root
./mvnw -pl jev-spring-boot-sample -am install -DskipTests
./mvnw -pl jev-spring-boot-sample spring-boot:run                     # offline stand-in, no key needed
TYPESAFE_API_KEY=ts-... ./mvnw -pl jev-spring-boot-sample spring-boot:run   # real Jev model
```

Then use [`requests.http`](requests.http) (IntelliJ / VS Code REST Client) or curl:

```bash
curl -s localhost:8080/api/v1/disruptions/assessments -H 'Content-Type: application/json' -d '{
  "noticeId":"N-1","purchaseOrder":"PO-4500012",
  "message":"Port congestion: our vessel booking was rolled; revised ETA is 10 days later. Booking ref MAEU-778812."}'
```

Without an API key, `jev.mode=auto` starts `OfflineTypeSafeServer`, a local keyword-heuristic imitation of the
API, and points the SDK at it over real HTTP. A warning is logged. Its answers are crude. Use the real model to
see paraphrase, negation and context handled properly.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/disruptions/assessments` | Assess one notice |
| `POST /api/v1/disruptions/assessments/batch` | Up to 100 notices, concurrent, failures isolated per notice |
| `GET /api/v1/purchase-orders` | Seeded open POs |
| `GET /actuator/health/jev` | Jev reachability and credentials (cached 30 s, 3 s timeout, no retries) |
| `GET /actuator/metrics/jev.client.attempts` | Latency by path, status and outcome; also `jev.client.retries` |

Upstream failures become RFC 9457 problem responses: 503 with `Retry-After` for rate limits and overload, 504 for
timeouts, 502 otherwise. They carry the TypeSafe request id and never the upstream body or the API key.

## Make it yours

- Replace `InMemoryPlanningRepository` with your ERP/WMS integration (`PlanningRepository`).
- Add levers to `DisruptionDecisionEngine.plans` (re-routing, substitute SKUs, customer allocation).
- Pin `jev.model` to a versioned model in production and store `signals.model` / `signals.requestId` with each
  decision.
