#!/usr/bin/env bash
# Start local dependencies for development (PostgreSQL).
set -euo pipefail
cd "$(dirname "$0")/.."

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start it and try again." >&2
  exit 1
fi

NAME=budget-app-postgres
PORT="${DB_PORT:-5432}"

if [ "$(docker ps -aq -f name="^${NAME}$")" ]; then
  docker start "$NAME" >/dev/null
  echo "Started existing container ${NAME} on port ${PORT}."
else
  # Local development only. These credentials are deliberately non-secret and are
  # never used outside a developer machine; production reads real values from the
  # environment with no fallback (docs/architecture/security-model.md).
  docker run -d --name "$NAME" \
    -e POSTGRES_DB=budget \
    -e POSTGRES_USER=budget \
    -e POSTGRES_PASSWORD=localdev \
    -p "${PORT}:5432" \
    postgres:16-alpine >/dev/null
  echo "Created ${NAME} on port ${PORT} (db=budget user=budget)."
fi

printf 'Waiting for Postgres'
for _ in $(seq 1 30); do
  if docker exec "$NAME" pg_isready -U budget >/dev/null 2>&1; then echo " ready."; exit 0; fi
  printf '.'; sleep 1
done
echo " timed out." >&2
exit 1
