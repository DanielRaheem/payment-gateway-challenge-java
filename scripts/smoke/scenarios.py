"""Independent scenarios; each creates its own required payment state."""
import uuid
from common import check, new_key, payload


def authorized(gateway):
    """Authorized payment and masked POST-to-GET round trip."""
    request = payload()
    payment = gateway.expect('/payment', 201, request)
    expected = dict(id=payment['id'], status='Authorized', cardNumberLastFour='0001',
                    expiryMonth=request['expiry_month'], expiryYear=request['expiry_year'],
                    currency='GBP', amount=1050)
    check(payment == expected, 'Incorrect or unmasked payment response')
    check(gateway.expect('/payment/' + payment['id'], 200) == payment, 'GET differed from POST')


def currencies(gateway):
    """All three supported currencies are accepted and preserved on retrieval."""
    for currency in ('GBP', 'USD', 'EUR'):
        payment = gateway.expect('/payment', 201, payload(currency=currency))
        check(payment['status'] == 'Authorized' and payment['currency'] == currency,
              'Incorrect result for ' + currency)
        check(gateway.expect('/payment/' + payment['id'], 200) == payment, 'GET differed')


def invalid_currency(gateway):
    """Unsupported, malformed, null and missing currency return 400 Rejected."""
    requests = [payload(currency=value) for value in ('JPY', 'gbp', '', 'GB', 'GBPP', ' GBP ', None)]
    requests.append({k: v for k, v in payload().items() if k != 'currency'})
    for request in requests:
        check(gateway.expect('/payment', 400, request)['status'] == 'Rejected', 'Expected rejection')


def invalid_cvv(gateway):
    """Invalid CVV returns 400 Rejected."""
    check(gateway.expect('/payment', 400, payload(cvv='1'))['status'] == 'Rejected', 'Expected rejection')


def declined(gateway):
    """Declined payment returns 201 and can be retrieved."""
    payment = gateway.expect('/payment', 201, payload(card_number='2222405343240002'))
    check(payment['status'] == 'Declined', 'Expected decline')
    check(gateway.expect('/payment/' + payment['id'], 200) == payment, 'GET differed')


def replay(gateway):
    """Identical idempotency key and payload replay the original payment."""
    key = new_key()
    payment = gateway.expect('/payment', 201, payload(), key)
    check(gateway.expect('/payment', 201, payload(), key) == payment, 'Replay differed')


def conflict(gateway):
    """Reusing a key for changed payment details returns 409."""
    key = new_key()
    gateway.expect('/payment', 201, payload(), key)
    check(gateway.expect('/payment', 409, payload(amount=200), key)['code'] ==
          'IDEMPOTENCY_CONFLICT', 'Incorrect conflict code')


def bank_unavailable(gateway):
    """Simulator card ending in zero maps to 503 BANK_UNAVAILABLE."""
    check(gateway.expect('/payment', 503, payload(card_number='2222405343240000'))['code'] ==
          'BANK_UNAVAILABLE', 'Incorrect bank failure code')


def missing_payment(gateway):
    """An unknown payment UUID returns 404."""
    gateway.expect('/payment/' + str(uuid.uuid4()), 404)


def health(gateway):
    """Health, liveness and readiness report UP; info is accessible."""
    for path in ('/actuator/health', '/actuator/health/liveness', '/actuator/health/readiness'):
        check(gateway.expect(path, 200)['status'] == 'UP', path + ' is not UP')
    gateway.expect('/actuator/info', 200)


def metrics(gateway):
    """Custom and HTTP metrics and Prometheus export are accessible."""
    # Seed lazy meters so this script also works on a freshly started gateway.
    replay(gateway)
    invalid_cvv(gateway)
    for name in ('gateway.bank.requests', 'gateway.payment.attempts',
                 'gateway.idempotency.requests', 'gateway.payment.errors',
                 'gateway.idempotency.entries', 'http.server.requests'):
        metric = gateway.expect('/actuator/metrics/' + name, 200)
        check(metric['name'] == name and metric['measurements'], 'Missing metric: ' + name)
    check('gateway_bank_requests_seconds_count' in gateway.expect('/actuator/prometheus', 200),
          'Bank timer missing from export')


def correlation(gateway):
    """Payment responses preserve caller-supplied correlation UUIDs."""
    # Gateway.expect checks the header on every payment response.
    authorized(gateway)
    invalid_cvv(gateway)


def management_exposure(gateway):
    """The Actuator environment endpoint is not exposed (404)."""
    gateway.expect('/actuator/env', 404)
