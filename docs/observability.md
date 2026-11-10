# Observability runbook

The gateway uses Spring Boot 3.1.5 Actuator, Micrometer, and the Prometheus registry.
Instrumentation preserves existing API response bodies and payment behavior.
Start the bank with `docker compose up -d bank_simulator`, then use JDK 17 to run
`./gradlew bootRun`. Monitoring endpoints themselves do not require the simulator.

## Endpoints and configuration

For a containerized gateway, use `docker compose up -d --build --wait` and
`docker compose logs -f gateway`. Docker checks `/actuator/health/readiness`
inside the container every 10 seconds. This does not call the bank. The gateway
host port defaults to 8090; set `GATEWAY_PORT=8091` if a local application already
uses it. Compose binds the gateway to loopback for local assessment use.

| Endpoint | Purpose |
| --- | --- |
| `/actuator/health` | Aggregate gateway health, including standard local indicators; details hidden |
| `/actuator/health/liveness` | Whether the application considers itself live |
| `/actuator/health/readiness` | Whether the application considers itself ready to accept traffic |
| `/actuator/info` | Static application name |
| `/actuator/metrics` | Available Micrometer metric names |
| `/actuator/metrics/{name}` | Metric values and available tags |
| `/actuator/prometheus` | Prometheus scrape output |

Only health, info, metrics, and prometheus are exposed. Environment, configuration,
heap dumps, and logger-management endpoints are not exposed. Health components and
details are hidden. The liveness/readiness probes reflect application availability,
not bank availability, payment persistence durability, or adequate capacity.
No payment is submitted as a health check.

```sh
curl http://localhost:8090/actuator/health
curl http://localhost:8090/actuator/health/readiness
curl http://localhost:8090/actuator/metrics/gateway.bank.requests
curl 'http://localhost:8090/actuator/metrics/gateway.bank.requests?tag=outcome:BANK_TIMEOUT'
curl http://localhost:8090/actuator/prometheus
```

Metric series are created when their outcome first occurs; an unused timer can
therefore be absent from the metrics endpoint. Metric values reset on restart.
The `application=payment-gateway` common tag identifies this application.

The assessment has no authentication layer. Monitoring endpoints share port 8090
with the API; this configuration is for local use. Restrict management access with
network rules or authentication before hosting. Spring Boot management properties
can be overridden through environment variables, for example
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus`.

## Metric meanings

Custom Java meter names are centralized in `observability.MetricNames`. Tests keep
literal-name assertions to detect accidental changes to the published contract.
Histogram configuration and Prometheus queries must stay aligned with these names.

| Micrometer name | Type / fixed labels | Meaning |
| --- | --- | --- |
| `gateway.bank.requests` | Timer, `outcome` | Actual bank calls, including response validation and transport failure translation |
| `gateway.payment.attempts` | Timer, `outcome` | Actual processing attempts through bank call and repository write |
| `gateway.idempotency.requests` | Counter, `decision` | NO_KEY, NEW, REPLAY, or CONFLICT decisions for validated service calls |
| `gateway.idempotency.entries` | Gauge, no variable labels | Reserved keys, including in-flight, successful, and failed attempts |
| `gateway.payment.errors` | Counter, `code` | Handled API errors: INVALID_REQUEST, PAYMENT_NOT_FOUND, IDEMPOTENCY_CONFLICT, BANK_UNAVAILABLE, BANK_TIMEOUT, BANK_INVALID_RESPONSE |
| `http.server.requests` | Timer, standard Spring labels | HTTP traffic and latency, including replay and rejected requests |
| `http.client.requests` | Timer, standard Spring labels | Outbound RestTemplate HTTP observations |
| `jvm.*`, `process.*`, `system.*`, `logback.events` | Standard meters | Runtime resource usage and log activity |

Custom attempt outcomes are AUTHORIZED, DECLINED, BANK_UNAVAILABLE, BANK_TIMEOUT,
BANK_INVALID_RESPONSE, and INTERNAL_ERROR. Timers publish count and total duration;
histogram buckets are enabled for bank and payment attempt timers. They are not
additional counters: use their counts for attempt volume.

A declined payment is a processed outcome, not a technical error. A replay creates
an HTTP observation and REPLAY decision, but no new bank/payment attempt. A replayed
bank failure does increment handled API errors again because the caller received
another error response. A REPLAY decision is recorded when joining the original
result, including while it is still running. Invalid input never reaches the
idempotency coordinator. The retained-entry gauge grows until restart: there is
currently no eviction, so watch it alongside heap usage.

Tags never contain payment IDs, card data, amounts, correlation IDs, fingerprints,
idempotency keys, arbitrary exception messages, or request bodies. Custom labels
use fixed outcome/code sets. IDs are for log correlation, not metrics aggregation.

## Prometheus scrape and example queries

Prometheus is not bundled or started automatically. For a Prometheus process on the
same host, add this job to its configuration:

```yaml
scrape_configs:
  - job_name: payment-gateway
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8090']
```

For Prometheus inside Docker Desktop and the gateway running on the host, use
`host.docker.internal:8090` instead. Adapt the target to the actual deployment.

Actual bank call rate by outcome:

```promql
sum by (outcome) (rate(gateway_bank_requests_seconds_count[5m]))
```

Bank call latency, 95th percentile, including all outcomes:

```promql
histogram_quantile(0.95,
  sum by (le) (rate(gateway_bank_requests_seconds_bucket[5m])))
```

Technical bank failures per second:

```promql
sum(rate(gateway_bank_requests_seconds_count{outcome=~"BANK_.*|INTERNAL_ERROR"}[5m]))
```

Idempotency replay rate and cache size:

```promql
sum(rate(gateway_idempotency_requests_total{decision="REPLAY"}[5m]))
sum(gateway_idempotency_entries)
```

These are diagnostic examples, not configured alerts or SLOs. Choose alert windows
and thresholds from expected traffic and latency. No traffic is not proof of health.

## Request correlation and logging

For `/payment` and `/payment/...` requests, the gateway accepts `X-Correlation-ID`
only in canonical UUID syntax. Invalid values are replaced rather than logged.
The chosen ID is returned on successes and handled errors, set in SLF4J MDC, and
forwarded to the bank through a RestTemplate interceptor. MDC is restored/removed
in a finally block so reused threads do not leak one caller's context into another.
Calls outside an HTTP request have no request correlation ID.

```sh
curl -i http://localhost:8090/payment \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-ID: 194b9a50-103d-40ab-a02f-f7fe31d967b6' \
  -H 'Idempotency-Key: demo-payment-1' \
  -d '{"card_number":"2222405343240001","expiry_month":12,"expiry_year":2099,"currency":"GBP","amount":1050,"cvv":"012"}'
```

Console lines include `correlationId=...`. Completion logs use stable fields:
`http_request_completed method=POST route=/payment status=201 duration_ms=...`.
Payment completion also logs the gateway payment ID and outcome. Expected validation
and lookup errors log at INFO; bank failures log their safe code at WARN. Bank
exception causes are deliberately not logged because upstream content can contain
card data. Missing-payment responses no longer generate stack traces.

Application logging does not include raw URLs/query strings, bodies, idempotency
keys, fingerprints, card numbers, CVVs, or upstream exception bodies. Do not enable
HTTP wire/body logging in a hosted environment. Correlation IDs are caller-controlled
identifiers, not authentication, idempotency, or proof of a trusted caller.

This is synchronous request correlation, not distributed tracing. There is no span
export, collector, or asynchronous context propagation. A replay has its own request
correlation ID; its stored payment ID links it to the original completed payment.

## Verification and diagnosis

Run `./gradlew test` with JDK 17. `ObservabilityTest` verifies attempt and replay
counts, declining outcomes, errors, retained entries, correlation forwarding and
cleanup (including exception paths), sensitive-data redaction, health probes,
Prometheus export, and disabled environment exposure. Existing concurrency and
payment contract tests also run with the instrumentation enabled.

When diagnosing problems:

1. Check readiness and the HTTP response/code; obtain its X-Correlation-ID.
2. Find the completion/error logs using that ID. Do not request card/CVV logs.
3. Compare HTTP errors with bank-attempt outcomes: cached errors and validation
   failures are distinct from new bank attempts.
4. Inspect bank latency and heap/cache metrics for saturation or growth.
5. Treat a bank timeout as an unknown outcome. Do not use a new idempotency key to
   force a retry merely because the gateway returned 504.


## Logging coverage and levels

| Event | Level | Owner and fields |
| --- | --- | --- |
| Payment HTTP completion, including expected 4xx and handled bank failures | INFO | CorrelationIdFilter: method, route template, status, duration_ms, completed |
| Unexpected 5xx or exception escaping the request chain | ERROR | CorrelationIdFilter: same safe completion fields; no exception message or cause |
| Authorized or declined payment saved | INFO | PaymentGatewayService: gateway payment ID and outcome |
| Invalid input, missing payment, key conflict | INFO | GlobalExceptionHandler: fixed description, no rejected values |
| Bank unavailable, timeout or invalid response | WARN | GlobalExceptionHandler: stable bank error code, no upstream body/cause |
| Payment lookup | DEBUG | PaymentGatewayService: gateway UUID |
| Actual bank attempt completes | DEBUG | BankClient: fixed outcome and duration_ms |
| Idempotency decision | DEBUG | PaymentIdempotencyService: NO_KEY, NEW, REPLAY or CONFLICT; no key/fingerprint |

A decline is normal business behavior, not WARN/ERROR. Expected caller errors are
INFO so malformed input does not create a warning storm. Bank technical failures
are WARN at the API boundary, including replayed failures. Completion records
remain INFO for handled 502/503/504 to avoid a second severity signal. Unexpected
failures become ERROR even when the chain exits before setting the response status;
`completed=false` means the logged 500 is inferred, not a confirmed wire response.

Repositories, DTOs, validation and mapping helpers intentionally do not log every
operation. Their outcomes are covered at service/HTTP boundaries. Startup and
shutdown logs are supplied by Spring Boot. Health, metrics and Swagger requests
are excluded from the payment completion filter; HTTP metrics cover these requests.
Direct non-HTTP service callers do not get the controller's WARN/error mapping or
request correlation and would need an equivalent boundary if introduced.

For temporary diagnostics, enable only application DEBUG:

```sh
LOGGING_LEVEL_COM_CHECKOUT_PAYMENT_GATEWAY=DEBUG ./gradlew bootRun
```

Alternatively set `logging.level.com.checkout.payment.gateway=DEBUG` through a
Spring configuration override. Keep root, Spring web and HTTP wire/body logging
at normal levels: application redaction cannot sanitize third-party diagnostic
logs. Unexpected exceptions are still propagated to the framework; its own logs
may include exception details. This review does not guarantee redaction of all
container/framework logs, nor does it change the unexpected-error API contract.

`RequestLoggingTest` checks completion levels, escaping failures and omission of
sensitive exception text. `ObservabilityTest` enables application DEBUG and checks
bank/idempotency diagnostics and non-disclosure of card, CVV, key and upstream text.
