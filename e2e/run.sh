#!/usr/bin/env bash
#
# E2E tests (Playwright): brings up the whole stack on ports that do NOT collide with dev:
#   Riot mock  → :9099   (deterministic responses, no key)
#   backend    → :8082   (RIOT_BASE_URL points to the mock)
#   frontend   → :4201   (proxy /api → backend)
# then runs Playwright. Requires a running Postgres (default
# localhost:5433/lp_leaderboard like dev.sh; CI overrides DB_URL).
#
# Usage:
#   bash e2e/run.sh                     # all tests
#   bash e2e/run.sh tests/full-flow.spec.ts   # selected file
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MOCK_PORT="${MOCK_PORT:-9099}"
BACKEND_PORT="${BACKEND_PORT:-8082}"
FRONTEND_PORT="${FRONTEND_PORT:-4201}"
DB_URL="${DB_URL:-jdbc:postgresql://localhost:5433/lp_leaderboard}"
RIOT_API_KEY="${RIOT_API_KEY:-e2e-mock-key}"
E2E_BASE_URL="${E2E_BASE_URL:-http://localhost:${FRONTEND_PORT}}"
# low dashboard-creation limit for e2e — the rate-limit test sends many
# POST /api/dashboards from one IP and verifies 429 + Retry-After.
# Default 5: the full flow makes 2 POSTs, with CI retries up to 4 — 5 leaves headroom;
# the rate-limit test reads the same variable, so it always knows the current limit.
export RATE_LIMIT_DASHBOARD_CREATE_MAX="${RATE_LIMIT_DASHBOARD_CREATE_MAX:-5}"

LOG_MOCK="/tmp/climbcheck-e2e-mock.log"
LOG_BACKEND="/tmp/climbcheck-e2e-backend.log"
LOG_FRONTEND="/tmp/climbcheck-e2e-frontend.log"

log() { echo "[e2e] $*"; }
die() { log "ERROR: $*"; exit 1; }

PIDS=()
cleanup() {
  log "Cleaning up processes..."
  for pgid in "${PIDS[@]:-}"; do
    kill -TERM -- "-$pgid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT

for port in "$MOCK_PORT" "$BACKEND_PORT" "$FRONTEND_PORT"; do
  if ss -tln 2>/dev/null | grep -qE "[:.]${port} "; then
    die "port $port is busy — close the process (it may be left from a previous e2e) or set another port (e.g. BACKEND_PORT=8083)"
  fi
done

wait_for() {
  local url="$1" name="$2" logfile="$3" max="${4:-180}"
  local i=0
  while [ "$i" -lt "$max" ]; do
    if curl -sf -o /dev/null "$url" 2>/dev/null; then
      log "$name gotowy ($url)"
      return 0
    fi
    sleep 2
    i=$((i + 2))
  done
  log "ERROR: $name did not start within ${max}s"
  [ -f "$logfile" ] && tail -30 "$logfile"
  return 1
}

start_mock() {
  log "Starting the Riot mock on :${MOCK_PORT}"
  setsid node "$ROOT/e2e/mock-riot-server.mjs" >"$LOG_MOCK" 2>&1 &
  PIDS+=("$!")
  wait_for "http://localhost:${MOCK_PORT}/ping" "mock Riot" "$LOG_MOCK" 15
}

start_backend() {
  log "Starting the backend on :${BACKEND_PORT} (Riot → mock :${MOCK_PORT})"
  setsid bash -c '
    cd "$1"
    exec env \
      RIOT_API_KEY="$2" \
      RIOT_BASE_URL="http://localhost:$3/{routing}" \
      SPRING_DATASOURCE_URL="$4" \
      SERVER_PORT="$5" \
      APP_RATE_LIMIT_DASHBOARD_CREATE_MAX="$6" \
      ./mvnw spring-boot:run
  ' _ "$ROOT/backend" "$RIOT_API_KEY" "$MOCK_PORT" "$DB_URL" "$BACKEND_PORT" "$RATE_LIMIT_DASHBOARD_CREATE_MAX" >"$LOG_BACKEND" 2>&1 &
  PIDS+=("$!")
  wait_for "http://localhost:${BACKEND_PORT}/actuator/health" "backend" "$LOG_BACKEND" 300
}

start_frontend() {
  log "Starting the frontend on :${FRONTEND_PORT} (proxy → :${BACKEND_PORT})"
  setsid bash -c '
    cd "$1"
    exec env BACKEND_URL="http://localhost:$2" npx ng serve --port "$3" --proxy-config proxy.conf.js
  ' _ "$ROOT/frontend" "$BACKEND_PORT" "$FRONTEND_PORT" >"$LOG_FRONTEND" 2>&1 &
  PIDS+=("$!")
  wait_for "http://localhost:${FRONTEND_PORT}/" "frontend" "$LOG_FRONTEND" 180
}

start_mock
start_backend
start_frontend

log "Playwright (${E2E_BASE_URL})..."
cd "$ROOT/e2e"
npx playwright install chromium
E2E_BASE_URL="$E2E_BASE_URL" npx playwright test "$@"
log "E2E tests finished successfully."
