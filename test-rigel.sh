#!/usr/bin/env zsh
set -e

# Backward-compatible alias for the explicitly named AI diagnostics launcher.
DIR="${0:A:h}"
exec "$DIR/aitest.sh" "$@"
