#!/usr/bin/env python3
"""Run all standalone smoke scenarios against the Docker-backed gateway."""
import subprocess
import sys
from pathlib import Path

scripts = ['authorized', 'currencies', 'invalid_currency', 'invalid_cvv', 'declined', 'replay', 'conflict', 'bank_unavailable', 'missing_payment', 'health', 'metrics', 'correlation', 'management_exposure']
for name in scripts:
    result = subprocess.run([sys.executable, str(Path(__file__).parent / 'smoke' / (name + '.py')), *sys.argv[1:]])
    if result.returncode:
        sys.exit(result.returncode)
print('ALL SMOKE CHECKS PASSED')
