#!/usr/bin/env bash
# Test helper for droidOS macOS / cross-platform desktop receiver
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}/host-client"

echo "[+] Running droidOS Host Receiver in Mock Mode..."
python3 main.py --mock "$@"
