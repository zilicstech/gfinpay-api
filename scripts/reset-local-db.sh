#!/usr/bin/env bash
# Drops and recreates the local `fintech` database, then applies Flyway migrations.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB_NAME="${FINTECH_DB_NAME:-fintech}"
JDBC_URL="${FINTECH_JDBC_URL:-jdbc:postgresql://localhost:5432/${DB_NAME}}"

echo "Stopping anything on port 8090 (optional)..."
lsof -ti :8090 | xargs kill 2>/dev/null || true
sleep 1

echo "Terminating connections to ${DB_NAME}..."
psql -d postgres -v ON_ERROR_STOP=1 -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB_NAME}' AND pid <> pg_backend_pid();"

echo "Recreating database ${DB_NAME}..."
dropdb --if-exists "${DB_NAME}"
createdb "${DB_NAME}"

echo "Refreshing classpath (drops stale migration copies in target/)..."
cd "${ROOT}"
mvn -q -DskipTests clean compile

echo "Running Flyway..."
mvn -q -DskipTests flyway:migrate \
  -Dflyway.url="${JDBC_URL}" \
  -Dflyway.user="${PGUSER:-$(whoami)}" \
  -Dflyway.password="${PGPASSWORD:-}"

echo "Done. Super admin is created on API startup from app.bootstrap.admin (default code GFINADMIN0)."
