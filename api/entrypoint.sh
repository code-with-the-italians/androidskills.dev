#!/bin/sh
set -e

DB_PATH="${DATA_DIR:-/data}/androidskills.db"
LITESTREAM_CONFIG="${LITESTREAM_CONFIG:-/etc/litestream.yml}"

if [ ! -f "$DB_PATH" ]; then
    echo "Database not found at $DB_PATH; restoring from Litestream replica if one exists..."
    # -if-replica-exists lets a brand-new deployment start fresh when the R2
    # bucket has no replica yet, instead of aborting the container on a missing
    # backup (which would create a boot-loop).
    litestream restore -if-replica-exists -config "$LITESTREAM_CONFIG" "$DB_PATH"
fi

exec litestream replicate -config "$LITESTREAM_CONFIG" \
    -exec "java $JAVA_OPTS -jar /app/androidskills-api.jar"
