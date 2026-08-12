#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"
exec "$DIR/run-rigel.sh" "$@"
