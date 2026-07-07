#!/bin/sh
set -e

DB_PATH="${DATA_DIR:-/data}/androidskills.db"
LITESTREAM_CONFIG="${LITESTREAM_CONFIG:-/etc/litestream.yml}"

# If R2 is not configured, run Ktor directly. This is the normal pre-production
# state (local dev, first deploy before backups) and avoids Litestream aborting
# because it cannot connect to a non-existent replica.
R2_VARS_PRESENT=0
R2_VARS_MISSING=""
for var in R2_BUCKET R2_ENDPOINT R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY; do
  if [ -n "$(eval echo \"\$$var\")" ]; then
    R2_VARS_PRESENT=$((R2_VARS_PRESENT + 1))
  else
    R2_VARS_MISSING="$R2_VARS_MISSING $var"
  fi
done

if [ "$R2_VARS_PRESENT" -eq 0 ]; then
    echo "R2 replica not configured; running Ktor without Litestream."
    exec java $JAVA_OPTS -jar /app/androidskills-api.jar
fi

if [ "$R2_VARS_PRESENT" -ne 4 ]; then
    echo "WARNING: R2 partially configured (${R2_VARS_PRESENT}/4); missing:${R2_VARS_MISSING}. Backups disabled."
    exec java $JAVA_OPTS -jar /app/androidskills-api.jar
fi

if [ ! -f "$DB_PATH" ]; then
    echo "Database not found at $DB_PATH; restoring from Litestream replica if one exists..."
    litestream restore -if-replica-exists -config "$LITESTREAM_CONFIG" "$DB_PATH"
fi

exec litestream replicate -config "$LITESTREAM_CONFIG" \
    -exec "java $JAVA_OPTS -jar /app/androidskills-api.jar"
