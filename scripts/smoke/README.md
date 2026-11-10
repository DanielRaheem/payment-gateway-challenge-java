# Individual smoke tests

Run from the project root with the gateway and Docker simulator running.
Python 3 is required; no third-party packages are needed. Each script is independent.

- `python3 scripts/smoke/authorized.py`
- `python3 scripts/smoke/currencies.py`
- `python3 scripts/smoke/invalid_currency.py`
- `python3 scripts/smoke/invalid_cvv.py`
- `python3 scripts/smoke/declined.py`
- `python3 scripts/smoke/replay.py`
- `python3 scripts/smoke/conflict.py`
- `python3 scripts/smoke/bank_unavailable.py`
- `python3 scripts/smoke/missing_payment.py`
- `python3 scripts/smoke/health.py`
- `python3 scripts/smoke/metrics.py`
- `python3 scripts/smoke/correlation.py`
- `python3 scripts/smoke/management_exposure.py`

Run all checks with `python3 scripts/smoke-test.py`. Every command supports
`--base-url http://localhost:8090`. PASS exits 0; FAIL exits nonzero.

Tests create synthetic payments and fresh keys, and increment metrics. Use only
against the supplied simulator. The metrics test seeds its own activity. Shared
HTTP helpers are in common.py and scenarios are in scenarios.py.

These tests do not exercise concurrency, timeouts or malformed bank responses;
those remain covered by the Java tests. No services are started or stopped by
the scripts.
