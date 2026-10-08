#!/usr/bin/env bash
# Print a Cognito access token for calling this server, e.g. from MCP Inspector.
#
#   ./get-token.sh read     token with scope vinnies/read  (all current tools and resources)
#   ./get-token.sh write    token with scope vinnies/write (read tools refuse it: shows scopes work)
#
# Uses the machine-to-machine client in .env (VINNIES_MCP_CLIENT_ID / _SECRET /
# _TOKEN_URL). The token is valid for 60 minutes; treat it like a password.
# In Inspector: Authentication -> Header "Authorization", value "Bearer <token>".
set -euo pipefail

cd "$(dirname "$0")"
case "${1:-}" in
  read|write) scope="vinnies/$1" ;;
  *) echo "usage: $0 read|write" >&2; exit 2 ;;
esac

if [[ ! -f .env ]]; then
  echo ".env not found -- copy .env.example to .env and fill it in" >&2
  exit 1
fi
set -a; source .env; set +a

response=$(curl -sS -X POST "$VINNIES_MCP_TOKEN_URL" \
  -u "$VINNIES_MCP_CLIENT_ID:$VINNIES_MCP_CLIENT_SECRET" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=$scope")

token=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))')
if [[ -z "$token" ]]; then
  echo "Cognito refused the token request: $response" >&2
  exit 1
fi
printf '%s\n' "$token"
