#!/bin/bash
set -euo pipefail

# Kept only so cached desktop overrides from older app builds do not break
# during an in-place upgrade. New overrides use launch-app-runtime.sh.
exec /usr/local/lib/proroot/launch-app-runtime.sh "$@"
