#!/bin/sh
set -e

DB_PATH="${DATA_DIR:-/data}/androidskills.db"
LITESTREAM_CONFIG="${LITESTREAM_CONFIG:-/etc/litestream.yml}"

# If R2 is not configured, run Ktor directly. This is the normal pre-production
# state (local dev, first deploy before backups) and avoids Litestream aborting
# because it cannot connect to a non-existent replica.
if [ -z "${R2_BUCKET:-}" ] || [ -z "${R2_ENDPOINT:-}" ] || [ -z "${R2_ACCESS_KEY_ID:-}" ] || [ -z "${R2_SECRET_ACCESS_KEY:-}" ]; then
    echo "R2 replica not configured; running Ktor without Litestream."
    exec java $JAVA_OPTS -jar /app/androidskills-api.jar
fi

if [ ! -f "$DB_PATH" ]; then
    echo "Database not found at $DB_PATH; restoring from Litestream replica if one exists..."
    litestream restore -if-replica-exists -config "$LITESTREAM_CONFIG" "$DB_PATH"
fi

exec litestream replicate -config "$LITESTREAM_CONFIG" \
    -exec "java $JAVA_OPTS -jar /app/androidskills-api.jar"
