# Test and review instructions

Run commands from the repository root. Use JDK 17 for local Gradle and Python 3
for the standalone smoke scripts. Docker is needed only for the live simulator route.

## Automated Java tests

```sh
./gradlew test
```

HTML report: `build/reports/tests/test/index.html`; XML: `build/test-results/test/`.
To deliberately run again rather than accepting Gradle's cached results:

```sh
./gradlew test --rerun-tasks
```

| Coverage | Tests |
| --- | --- |
| Validation, fixed-clock expiry, JSON binding and redaction | `PaymentValidationTest`, `PostPaymentRequestTest`, `BankPaymentDtoTest` |
| Processing, masking, retrieval and rejection | `PaymentProcessingTest`, `PaymentGatewayServiceTest`, `PaymentGatewayControllerTest`, `PaymentRoundTripTest` |
| Bank timeouts, invalid JSON/types and HTTP errors | `BankFailureServiceTest`, `BankFailureControllerTest`, `BankErrorResponseTest` |
| Concurrent repository access and key coordination | `PaymentsRepositoryConcurrencyTest`, `PaymentIdempotencyTest` |
| Metrics, correlation cleanup, forwarding and safe logging | `ObservabilityTest`, `RequestLoggingTest` |

Tests replace the bank transport or bank client. They do not call Docker or a real
bank. HTTP rejection tests assert no bank operation and no repository write;
round-trip tests assert retrieval does not call the bank. Idempotency tests check
call/write counts and overlapping same-key/distinct-key requests. A passing smoke
replay alone would not establish those call-count or concurrency guarantees.

## Container build and end-to-end smoke tests

```sh
docker compose up -d --build --wait
python3 scripts/smoke-test.py
```

The multi-stage Docker build runs `test bootJar`; a failing Java test fails the
image build. Docker may reuse an unchanged successful build layer. Image-build
reports stay in the build stage; use local Gradle for convenient report browsing.

If another application occupies 8090:

```sh
GATEWAY_PORT=8091 docker compose up -d --build --wait
python3 scripts/smoke-test.py --base-url http://localhost:8091
```

All 13 scripts are listed in [the individual script guide](../scripts/smoke/README.md).
Each seeds its own required state, can run independently, and returns nonzero on
failure. The runner stops at the first failed script. Runs create synthetic data
and increment metrics; they do not start, stop or reset services.

Live checks cover authorization, declines, POST-to-GET, currencies, invalid input,
idempotency replay/conflict, bank 503, lookup 404, correlation, probes and metrics.
Timeouts, malformed bank responses and concurrency remain Java-test scenarios;
the supplied simulator is not changed to manufacture these failures.

## Review checklist

- Java tests pass; inspect the report rather than only compilation status.
- Compose gateway reports healthy and runs as UID 10001 (`docker compose exec -T gateway id`).
- All smoke scripts pass against the intended port, not an older local gateway.
- Review logs for correlation and safe messages; do not enable request-body logging.
- Read the documented limitations before describing the solution as deployable.
- Run `git diff --check` and review changes before committing; pre-existing wrapper
  line-ending changes should be reviewed separately from application changes.

Validation evidence from 10 November 2026: the container build completed its Java
tests, and all 13 standalone smoke scenarios passed against the gateway on port
8091. The runtime UID was verified as 10001. This is a point-in-time result, not a
continuous integration guarantee or a performance/security assessment.

Latest source verification after response consolidation and expiry mapping cleanup:
120 Java tests passed with zero failures/errors/skips. Rerun the commands above
for the exact source revision being submitted.
