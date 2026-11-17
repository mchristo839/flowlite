#!/bin/sh
# Consistent SQLite copies of the server data, keeps 7 days.
# Usage: ./backup.sh [destination folder]   (default ./backups). Run it daily from cron.
set -eu
cd "$(dirname "$0")"
DEST=${1:-./backups}
mkdir -p "$DEST"
docker compose exec -T pocketbase sh -c '
  rm -rf /pb/pb_data/.backup_tmp && mkdir -p /pb/pb_data/.backup_tmp &&
  for db in /pb/pb_data/*.db; do sqlite3 "$db" ".backup /pb/pb_data/.backup_tmp/$(basename "$db")"; done'
tar czf "$DEST/flowlite-$(date +%F).tar.gz" -C pb_data .backup_tmp
docker compose exec -T pocketbase rm -rf /pb/pb_data/.backup_tmp
find "$DEST" -name 'flowlite-*.tar.gz' -mtime +6 -delete
echo "Backup written to $DEST/flowlite-$(date +%F).tar.gz"
