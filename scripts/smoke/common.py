"""Shared HTTP helpers for the standalone simulator smoke tests."""
import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


class Gateway:
    def __init__(self, base):
        self.base = base.rstrip('/')

    def expect(self, path, status, body=None, key=None):
        correlation = str(uuid.uuid4())
        headers = {'Content-Type': 'application/json', 'X-Correlation-ID': correlation}
        if key:
            headers['Idempotency-Key'] = key
        request = urllib.request.Request(self.base + path, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            check(response.status == status,
                  f'{path}: expected HTTP {status}, got {response.status}')
            if path.startswith('/payment'):
                check(response.headers.get('X-Correlation-ID') == correlation,
                      'Correlation ID was not preserved')
            raw = response.read().decode()
            return json.loads(raw) if 'json' in response.headers.get('Content-Type', '') else raw


def payload(**changes):
    result = dict(card_number='2222405343240001', expiry_month=12,
                  expiry_year=date.today().year + 2, currency='GBP', amount=1050, cvv='012')
    return dict(result, **changes)


def new_key():
    return 'smoke-' + str(uuid.uuid4())


def run(test):
    parser = argparse.ArgumentParser(description=test.__doc__)
    parser.add_argument('--base-url', default='http://localhost:8090')
    args = parser.parse_args()
    try:
        test(Gateway(args.base_url))
        print('PASS: ' + test.__doc__)
    except (RuntimeError, OSError, ValueError, KeyError, TypeError) as error:
        print(f'FAIL: {error}', file=sys.stderr)
        sys.exit(1)
