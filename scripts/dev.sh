#!/usr/bin/env bash
#
# dev.sh — starts the whole local ClimbCheck stack with one command:
#
#   Postgres (Docker, port 5433) → Backend (Spring Boot, port 8081) → Frontend (Angular, port 4200)
#
# Default ports are shifted vs docker-compose.yml (5432) and Spring Boot (8080),
# because these are often taken by other projects. They can be overridden:
#
#   DB_PORT=5433 BACKEND_PORT=8081 FRONTEND_PORT=4200 ./scripts/dev.sh
#
# Stopping: Ctrl+C — stops the backend and frontend. The Postgres container stays
# running, so the data and Flyway migrations survive between sessions.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DB_PORT="${DB_PORT:-5433}"
BACKEND_PORT="${BACKEND_PORT:-8081}"
FRONTEND_PORT="${FRONTEND_PORT:-4200}"

DB_CONTAINER="climbcheck-db"
DB_IMAGE="postgres:17-alpine"
DB_NAME="lp_leaderboard"
DB_USER="lp_user"
DB_PASSWORD="lp_password"

BACKEND_LOG="/tmp/climbcheck-backend.log"
FRONTEND_LOG="/tmp/climbcheck-frontend.log"

log() { printf '\033[1;34m[dev]\033[0m %s\n' "$*"; }
die() {
  printf '\033[1;31m[dev]\033[0m %s\n' "$*" >&2
  exit 1
}

# --- Prerequisites ------------------------------------------------------------

command -v docker >/dev/null || die "Docker is not installed."
docker info >/dev/null 2>&1 || die "Docker daemon is not running (on Linux: systemctl start docker)."
command -v curl >/dev/null || die "curl is required."

# The Riot key must not be empty — with an empty key the stack starts, but every
# player addition fails at runtime with a 401 from Riot.
has_api_key=0
if [ -n "${RIOT_API_KEY:-}" ]; then
  has_api_key=1
elif [ -f "$ROOT/.env" ] && grep -qE '^[[:space:]]*RIOT_API_KEY=[^[:space:]]' "$ROOT/.env"; then
  has_api_key=1
fi
if [ "$has_api_key" -ne 1 ]; then
  die "RIOT_API_KEY is missing. Fill in .env (cp env.example .env) or export the variable."
fi

# --- 1. Postgres ---------------------------------------------------------------

# Equivalent of the db service from docker-compose.yml, but with a host port override:
# the default 5432 is often taken by other projects.
db_up=0
if docker ps -q -f name="$DB_CONTAINER" >/dev/null; then
  # docker port prints "5432/tcp -> 0.0.0.0:5433" — the host port is at the end of the line.
  if docker port "$DB_CONTAINER" 2>/dev/null | grep -qE -- "-> .*:${DB_PORT}$"; then
    db_up=1
    log "Postgres already running (container $DB_CONTAINER, port $DB_PORT)"
  else
    log "Container $DB_CONTAINER is on another port — rebuilding on $DB_PORT"
  fi
fi

if [ "$db_up" -ne 1 ]; then
  log "Starting Postgres (container $DB_CONTAINER) on port $DB_PORT..."
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$DB_CONTAINER" \
    -p "$DB_PORT:5432" \
    -e POSTGRES_DB="$DB_NAME" \
    -e POSTGRES_USER="$DB_USER" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    -v "${DB_CONTAINER}-data:/var/lib/postgresql/data" \
    "$DB_IMAGE" >/dev/null \
    || die "Failed to start Postgres (port $DB_PORT busy or image $DB_IMAGE missing)."
  log "Postgres started (port $DB_PORT)"
fi

log "Waiting for Postgres..."
db_ready=0
for _ in $(seq 1 30); do
  if docker exec "$DB_CONTAINER" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
    db_ready=1
    break
  fi
  sleep 1
done
[ "$db_ready" -eq 1 ] || die "Postgres did not respond within 30 s. See: docker logs $DB_CONTAINER"
log "Postgres ready."

# --- 2. Backend ----------------------------------------------------------------

log "Starting the backend on port $BACKEND_PORT (logs: $BACKEND_LOG)..."
# setsid: the backend gets its own session/process group, so cleanup can kill
# the whole tree (mvnw + forked app) with one kill on the group.
(
  cd "$ROOT/backend"
  if command -v setsid >/dev/null 2>&1; then
    exec setsid env SERVER_PORT="$BACKEND_PORT" \
      SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" \
      ./mvnw spring-boot:run
  else
    # Fallback (e.g. macOS) — cleanup catches the app via pkill by class name.
    exec env SERVER_PORT="$BACKEND_PORT" \
      SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME" \
      ./mvnw spring-boot:run
  fi
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

cleaned=0
cleanup() {
  [ "$cleaned" -eq 1 ] && return
  cleaned=1
  log "Stopping backend and frontend..."
  kill -TERM -- "-$BACKEND_PID" 2>/dev/null || kill -TERM "$BACKEND_PID" 2>/dev/null || true
  # Fuses in case SIGINT does not reach the frontend (e.g. kill -TERM
  # of the script itself) or setsid is unavailable and the app was orphaned.
  pkill -TERM -f "ng serve --port $FRONTEND_PORT" 2>/dev/null || true
  pkill -TERM -f 'ClimbcheckApplication' 2>/dev/null || true
}
trap cleanup INT TERM EXIT

log "Waiting for the backend (first start may take a while — downloading dependencies)..."
backend_ready=0
for _ in $(seq 1 180); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    break # the process died — the logs will tell why
  fi
  # Probe: any 4xx code (404) means the listener is up; 000 = no connection,
  # 5xx = the app responds, but something is wrong (e.g. the DB).
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    "http://localhost:$BACKEND_PORT/api/dashboards/startup-probe" 2>/dev/null || true)"
  if [ "${code:-000}" != "000" ] && [ "${code:0:1}" != "5" ]; then
    backend_ready=1
    break
  fi
  sleep 1
done
if [ "$backend_ready" -ne 1 ]; then
  die "Backend did not start within 180 s. See logs: tail -50 $BACKEND_LOG"
fi
log "Backend ready (port $BACKEND_PORT)."

# --- 3. Frontend ---------------------------------------------------------------

if [ ! -d "$ROOT/frontend/node_modules" ]; then
  log "node_modules missing — running npm install (first start)..."
  (cd "$ROOT/frontend" && npm install)
fi

echo
printf '\033[1;32m%s\033[0m\n' "  ClimbCheck — local stack is running"
echo "  Frontend:  http://localhost:$FRONTEND_PORT  (the first build may take a moment)"
echo "  Backend:   http://localhost:$BACKEND_PORT"
echo "  Postgres:  localhost:$DB_PORT"
echo "  Logs:      $BACKEND_LOG (backend) | $FRONTEND_LOG (frontend)"
echo "  Stop:      Ctrl+C"
echo

log "Starting the frontend on port $FRONTEND_PORT (logs: $FRONTEND_LOG)..."
(
  cd "$ROOT/frontend"
  exec env BACKEND_URL="http://localhost:$BACKEND_PORT" \
    npm start -- --port "$FRONTEND_PORT"
) 2>&1 | tee "$FRONTEND_LOG"

log "Frontend finished."
