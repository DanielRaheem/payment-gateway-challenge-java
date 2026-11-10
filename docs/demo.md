# Five-minute demonstration

## Preparation (before the interview)

From the project root, start Docker. This example uses 8091 so a local IntelliJ
application can remain on 8090. Use the same override for later Compose up commands.

```sh
GATEWAY_PORT=8091 docker compose up -d --build --wait
python3 scripts/smoke-test.py --base-url http://localhost:8091
```

Open `http://localhost:8091/swagger-ui/index.html` and the Java test report, if
built locally. In a second terminal run `docker compose logs -f gateway`.
The first image build may download dependencies; do not leave that for demo time.

## 0:00-1:00 - Architecture and API

Explain: “The payment domain owns validation, processing, storage and idempotency.
The bank domain owns the external contract and failure translation. POST validates
at the controller; GET reads the saved masked response without calling the bank.”

Show the diagram in [architecture.md](architecture.md). Point out that both
Authorized and Declined are processed outcomes returning 201, while invalid input
is Rejected with 400. The three supported currencies are GBP, USD and EUR.

## 1:00-2:00 - Process and retrieve

```sh
python3 scripts/smoke/authorized.py --base-url http://localhost:8091
python3 scripts/smoke/declined.py --base-url http://localhost:8091
```

Explain: “Each script submits a payment then retrieves the returned UUID. The
response contains only the last four card digits and excludes CVV.” To show the
raw response, use this synthetic payload in Swagger's POST `/payment`, then copy
the returned ID into GET `/payment/{id}`:

```json
{"card_number":"2222405343240001","expiry_month":12,"expiry_year":2099,"currency":"GBP","amount":1050,"cvv":"012"}
```

## 2:00-3:00 - Rejection, replay and bank failures

```sh
python3 scripts/smoke/invalid_currency.py --base-url http://localhost:8091
python3 scripts/smoke/replay.py --base-url http://localhost:8091
python3 scripts/smoke/conflict.py --base-url http://localhost:8091
python3 scripts/smoke/bank_unavailable.py --base-url http://localhost:8091
```

Explain: “JPY is a real currency but outside the three-code allowlist. Repeating
identical details with the same key returns the same payment; changed details
return 409. A bank 503 is translated to a safe BANK_UNAVAILABLE response. A timeout
has an unknown outcome, so we do not automatically retry.”

## 3:00-4:00 - Observability and testing

```sh
curl -s http://localhost:8091/actuator/health/readiness
curl -s http://localhost:8091/actuator/metrics/gateway.bank.requests
curl -s http://localhost:8091/actuator/metrics/gateway.idempotency.requests
```

Show correlated completion logs. Explain: “Bank attempts and replays are measured
separately. IDs are log context, not metric labels. Java tests check bank call counts,
validation boundaries, concurrent keys and repository access; the smoke scripts
check the actual Docker integration.” Metrics are cumulative, not per-demo counts.

## 4:00-5:00 - Packaging and tradeoffs

Show the multi-stage Dockerfile: tests/build with JDK 17, non-root JRE runtime,
readiness health check. Explain: “The assessment allows in-memory storage. Restart
loses payments and idempotency state, and the cache has no eviction. Multiple
instances would require durable coordination and reconciliation. There is no
merchant authentication, production hosting or distributed tracing yet.”

Finish with the testing matrix and known limitations, then invite questions about
tradeoffs. Stop the stack afterwards with `docker compose stop`. Ctrl+C in the
log-following terminal only stops log streaming.
