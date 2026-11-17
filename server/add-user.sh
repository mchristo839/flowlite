#!/bin/sh
# Adds a FlowLite login, or resets its password if it already exists.
# Usage: ./add-user.sh EMAIL PASSWORD
set -eu
cd "$(dirname "$0")"
[ $# -eq 2 ] || { echo "Usage: ./add-user.sh EMAIL PASSWORD"; exit 1; }
. ./.env
URL="http://127.0.0.1:${FLOWLITE_PORT:-8090}"
json() { python3 -c 'import json,sys; print(json.dumps(dict(zip(sys.argv[1::2], sys.argv[2::2]))))' "$@"; }

TOKEN=$(curl -sf -X POST "$URL/api/collections/_superusers/auth-with-password" -H 'Content-Type: application/json' \
  -d "$(json identity "$PB_SUPERUSER_EMAIL" password "$PB_SUPERUSER_PASSWORD")" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

FILTER=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote("email = \"%s\"" % sys.argv[1]))' "$1")
ID=$(curl -sf "$URL/api/collections/users/records?filter=$FILTER" -H "Authorization: $TOKEN" \
  | python3 -c 'import json,sys; i=json.load(sys.stdin)["items"]; print(i[0]["id"] if i else "")')

BODY=$(json email "$1" password "$2" passwordConfirm "$2")
if [ -n "$ID" ]; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "$URL/api/collections/users/records/$ID" \
    -H "Authorization: $TOKEN" -H 'Content-Type: application/json' -d "$BODY")
  ACTION="updated"
else
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/api/collections/users/records" \
    -H "Authorization: $TOKEN" -H 'Content-Type: application/json' \
    -d "$(json email "$1" password "$2" passwordConfirm "$2" | python3 -c 'import json,sys; d=json.load(sys.stdin); d["verified"]=True; print(json.dumps(d))')")
  ACTION="created"
fi
[ "$CODE" = "200" ] && echo "App login $ACTION: $1" || { echo "Failed (HTTP $CODE). Passwords need at least 8 characters."; exit 1; }
