#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$ROOT/tests/run_service_tests.py"
python3 "$ROOT/tools/check_widget_layouts.py"
