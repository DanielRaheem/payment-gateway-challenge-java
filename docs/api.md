# API contracts

### Payment lookup flow

1. A caller sends `GET /payment/{id}` with a UUID.
2. The controller delegates to the service, which reads the repository.
3. If found, the stored payment is returned as JSON with HTTP 200.
4. If absent, the service throws an exception and the shared handler returns HTTP 404.

The repository starts empty. `POST /payment` validates the request, submits it to
the bank, and stores authorized or declined results. Both outcomes return HTTP 201
with `PaymentResponse`: ID, status, last four digits, expiry month/year,
currency, and amount. Invalid input or unreadable JSON returns HTTP 400 with
`{"status":"Rejected","message":"Invalid payment request"}`, without calling the
bank or saving a payment. The gateway does not retain full card numbers or CVVs in its repository or
idempotency cache. They are sent to the supplied simulator for processing.

Example submission (with the simulator and gateway running):

```sh
curl -i http://localhost:8090/payment \
  -H 'Content-Type: application/json' \
  -d '{"card_number":"2222405343240001","expiry_month":12,"expiry_year":2099,"currency":"GBP","amount":1050,"cvv":"012"}'
```

Use the returned ID with `GET /payment/{id}` to retrieve the result.

### Bank simulator and validation

The simulator accepts `POST /payments` with `card_number`, `expiry_date`,
`currency`, `amount`, and `cvv`. Missing required properties produce HTTP 400.
Card numbers ending in an odd digit are authorized; endings 2, 4, 6, or 8 are
declined; ending 0 produces HTTP 503. These rules come from the supplied simulator
configuration, which should remain unchanged.

The service uses `BankPaymentRequest` and `BankPaymentResponse` for simulator
communication. Missing authorization outcomes are rejected rather than interpreted
as declines. `BankClient` translates HTTP/transport and response-format failures into
`BankProcessingException`; the shared exception handler returns the documented
502/503/504 response before any payment is stored. There are no automatic retries.

DTO constraints run through `@Valid @RequestBody` at the controller boundary.
The service assumes validated input and does not repeat validation. Record
construction itself does not execute validation. Response constraints can be
checked explicitly with a Validator.

Requests now accept the full `card_number` (14-19 ASCII digits), a 3-4 digit CVV,
and GBP, USD, or EUR. Expiry must be strictly later than the current month, using
the validation provider's clock. Years are limited to 1-9999 for MM/YYYY formatting.
Missing numeric request fields remain null; fractional numbers are rejected during
JSON binding. Amounts use Java Integer minor units; no additional positive-only
rule is imposed. Historical response expiry dates do not need to be in the future.

Payment request/response and bank DTOs have explicit `toString()` methods. Request card numbers and CVVs are
redacted. Response suffixes remain strings to preserve leading zeroes. The merchant request
contains separate expiry month/year fields; only the payment-owned bank request mapper
formats `expiry_date` as MM/YYYY for the bank contract.
Payment completion logs include the gateway ID and status, without card data.

### Bank-error response contracts

The following gateway responses are implemented and covered by automated tests. They are design
choices for this API; the bank simulator itself is unchanged.

| Bank failure | Gateway HTTP status | Code | Safe message |
| --- | --- | --- | --- |
| HTTP 503 or connection refusal | 503 | `BANK_UNAVAILABLE` | Bank service is unavailable |
| Connection/read timeout | 504 | `BANK_TIMEOUT` | Bank request timed out; payment outcome is unknown |
| Other unexpected HTTP status, including bank 400/404/500 | 502 | `BANK_INVALID_RESPONSE` | Bank returned an invalid response |
| Empty/malformed body, missing/null/non-boolean `authorized`, wrong JSON shape | 502 | `BANK_INVALID_RESPONSE` | Bank returned an invalid response |

The response contains exactly `code` and `message`, represented by
`BankErrorResponse`. It contains no payment ID or payment status: these errors
are neither bank declines nor invalid-input rejections. Upstream response bodies,
exception messages, card data, and stack traces must not be exposed.

`BankProcessingException` and `BankErrorCode` define the internal failure contract;
the bank client translates failures into specific exception subclasses.
`GlobalExceptionHandler` maps each subtype to HTTP using `@ResponseStatus`.
Each attempt makes one bank call, performs no repository operations on failure,
and does not automatically retry. Not saving a completed result does not establish
that the bank did not process it; a timeout can leave the outcome unknown.

`BankFailureServiceTest` and `BankFailureControllerTest` share upstream fixtures
and run without Docker. They specify error translation, HTTP mapping, safe bodies,
no storage, and no retries. String/numeric authorization values must be rejected
rather than coerced to a Boolean. Authorization-code requirements are unchanged;
a declined response can contain an empty authorization code.

`PaymentRoundTripTest` submits authorized and declined payments, then retrieves each
returned ID twice through the real application layers. It verifies matching masked
details and that GET does not call the bank. Only bank HTTP transport is substituted.
