#!/usr/bin/env zsh
set -e

DIR="${0:A:h}"
# Launch via zsh instead of exec'ing the script directly so this still works
# even when a copied folder arrives with run-rigel.sh's execute bit stripped.
exec /bin/zsh "$DIR/run-rigel.sh" "$@"
