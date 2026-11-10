# Payment Gateway

Java 17 / Spring Boot payment gateway for the [Checkout.com assessment](https://github.com/cko-recruitment/).
Processes payments through the supplied bank simulator and retrieves masked results.

## Quick start with Docker

Start Docker, then run from the repository root:

```sh
docker compose up -d --build --wait
python3 scripts/smoke-test.py
docker compose logs -f gateway
```

The image build runs the Java tests and packages the application. The runtime uses
Java 17 and non-root UID 10001. No host JDK is required for this route; smoke scripts
need Python 3 with no third-party packages. The first build needs network access.

Open [Swagger UI](http://localhost:8090/swagger-ui/index.html).
The gateway is published on loopback port 8090 and calls `http://bank_simulator:8080`
inside Compose. The simulator's host ports are 8080 (payments) and 2525 (administration).
Readiness checks the gateway, not the bank; smoke tests verify the integration.

If a local gateway already occupies 8090:

```sh
GATEWAY_PORT=8091 docker compose up -d --build --wait
python3 scripts/smoke-test.py --base-url http://localhost:8091
```

Keep that port override for subsequent Compose `up` commands. Scripts default to
8090, so explicitly target 8091 when checking Docker container logs. Rebuild after
source changes. `docker compose stop` stops the stack; `docker compose down` also
removes its containers and network. Restarting the gateway loses in-memory state.

## Run locally or in IntelliJ

Use JDK 17 for `JAVA_HOME` and IntelliJ's Gradle JVM:

```sh
docker compose up -d bank_simulator
./gradlew test
./gradlew bootRun
```

Alternatively select the shared **Payment Gateway Local** run configuration in
IntelliJ (reload the Gradle project if needed). It runs `bootRun` on port 8090 with
`BANK_BASE_URL=http://localhost:8080`; logs appear in the Run console. Stop any prior
local gateway on 8090 first. Override `BANK_BASE_URL` for another simulator address.
On Windows use `gradlew.bat` and the available Python launcher.

## API at a glance

| Endpoint | Behavior |
| --- | --- |
| `POST /payment` | Validates input, calls the bank and returns 201 with an Authorized or Declined `PaymentResponse`. Supports optional `Idempotency-Key`. |
| `GET /payment/{id}` | Returns the stored `PaymentResponse` with 200, or 404 when absent. No bank call. |

Responses contain a gateway UUID, status, last four card digits, expiry, currency
and amount. The gateway does not store full card numbers or CVVs in its repository
or key cache. Invalid input returns 400 Rejected before calling the bank. Supported
currencies are GBP, USD and EUR; amounts are integer minor units.

## Documentation

- [API fields, examples and error contracts](docs/api.md)
- [Architecture, decisions and known limitations](docs/architecture.md)
- [Test coverage and review instructions](docs/testing.md)
- [Individual smoke scripts](scripts/smoke/README.md)
- [Logging, metrics and operational diagnosis](docs/observability.md)
- [Five-minute demonstration](docs/demo.md)
- [Setup and demonstration PDF](output/pdf/payment-gateway-setup-and-demo.pdf)
- [Complete request-flow PDF](output/pdf/payment-gateway-flow-walkthrough.pdf)

The Markdown guides are the maintainable reference; PDFs are companion walkthroughs.

## Scope and project layout

`src/main/java/.../payment` owns merchant processing, validation, idempotency and
storage; `bank` owns simulator integration; `observability` owns correlation and
metric names. Tests mirror those responsibilities. Dockerfile and Compose package
the application; `.run/` holds the shared IntelliJ configuration.

Storage and idempotency are process-local with no eviction or durability. The
application has no merchant authentication, distributed coordination or public
hosting setup. See the architecture guide for the complete limitations.
The supplied `imposters/` configuration and `.editorconfig` are unchanged.
