#!/bin/sh
# Starts the FlowLite sync server and creates your admin and app logins from .env.
# Safe to run again: it updates the passwords instead of failing.
set -eu
cd "$(dirname "$0")"
[ -f .env ] || { echo "No .env found. Run: cp .env.example .env  then edit it."; exit 1; }
. ./.env
for v in PB_SUPERUSER_EMAIL PB_SUPERUSER_PASSWORD FLOWLITE_EMAIL FLOWLITE_PASSWORD; do
  eval "val=\${$v:-}"; [ -n "$val" ] || { echo "Set $v in .env"; exit 1; }
done
case "$PB_SUPERUSER_PASSWORD$FLOWLITE_PASSWORD" in *change-me*|*choose-*) echo "Change the example passwords in .env first."; exit 1;; esac

if [ "${HTTPS:-caddy}" = "caddy" ]; then
  docker compose --profile https up -d --build
else
  docker compose up -d --build pocketbase
fi

URL="http://127.0.0.1:${FLOWLITE_PORT:-8090}"
printf "Waiting for the server"
i=0; until curl -sf "$URL/api/health" >/dev/null; do
  i=$((i+1)); [ $i -gt 60 ] && { echo " timed out. Check: docker compose logs pocketbase"; exit 1; }
  printf "."; sleep 1
done; echo " up."

docker compose exec -T pocketbase /pb/pocketbase superuser upsert "$PB_SUPERUSER_EMAIL" "$PB_SUPERUSER_PASSWORD" --dir=/pb/pb_data >/dev/null
echo "Admin account ready."

./add-user.sh "$FLOWLITE_EMAIL" "$FLOWLITE_PASSWORD"

if [ "${HTTPS:-caddy}" = "caddy" ]; then
  echo "Done. In the FlowLite app enter: https://$DOMAIN"
else
  echo "Done. Point your reverse proxy at $URL, then enter your https address in the app."
fi
