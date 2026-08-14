#!/usr/bin/env zsh
set -e

# AI diagnostics only: this one process is visible to screenshots and screen
# recordings. Normal run-rigel.sh deliberately keeps capture protection on.
DIR="${0:A:h}"
export RIGEL_LAUNCH_MODE=diagnostic-capture
exec "$DIR/run-rigel.sh" "$@"
