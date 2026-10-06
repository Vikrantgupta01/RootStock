#!/usr/bin/env bash
# Load the fictional demo data into the app's schema on RDS.
#
#   ./demo-data.sh seed    load the demo data into empty tables; no-op if it is already there
#   ./demo-data.sh reset   wipe the app's tables and load the demo data again
#
# Both are repeatable: running either twice leaves the same data (the log prints
# a fingerprint of every row, so you can compare runs). Reads settings from .env.
set -euo pipefail

cd "$(dirname "$0")"
case "${1:-}" in
  seed|reset) ;;
  *) echo "usage: $0 seed|reset" >&2; exit 2 ;;
esac

if [[ ! -f .env ]]; then
  echo ".env not found -- copy .env.example to .env and fill it in" >&2
  exit 1
fi
set -a; source .env; set +a

exec mvn -q spring-boot:run -Dspring-boot.run.arguments="$1"
