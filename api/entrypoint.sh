#!/bin/sh
set -e

DB_PATH="${DATA_DIR:-/data}/androidskills.db"
LITESTREAM_CONFIG="${LITESTREAM_CONFIG:-/etc/litestream.yml}"

if [ ! -f "$DB_PATH" ]; then
    echo "Database not found at $DB_PATH; restoring from Litestream replica..."
    litestream restore -config "$LITESTREAM_CONFIG" "$DB_PATH"
fi

exec litestream replicate -config "$LITESTREAM_CONFIG" \
    -exec "java $JAVA_OPTS -jar /app/androidskills-api.jar"
