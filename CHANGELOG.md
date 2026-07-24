# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- Test-count badges in the README are now generated from CI test results on every push
  (shields.io endpoint badges) — no more hard-coded numbers that go stale

## [1.0.0] - 2026-07-24

First complete release of ClimbCheck — a full-stack web app that turns a squad's
solo-queue grind into a live, shareable LP leaderboard.

### Added

- **Backend (Spring Boot, Java 21)**
  - `POST /api/dashboards` — create a dashboard with a unique shareable link and an admin token
  - `GET /api/dashboards/{token}` — live ranking sorted by rank (tier → division → LP)
  - `POST/DELETE /api/dashboards/{token}/players` — add/remove players via Riot ID (EUW/EUNE)
  - Riot Games API integration: account-v1 (PUUID lookup), league-v4 (rank/LP/winrate), match-v5 (match history)
  - LP snapshots with a 2h scheduled job + opportunistic capture on dashboard view
  - LP Progression Trends data (30-day window) with rank stored per snapshot
  - Match history with per-match LP change derived from snapshots (Riot match-v5 has no LP data)
  - Streak (win/loss) computation from recent matches
  - Top champions aggregation with per-champion winrate
  - Sliding-window rate limiter for the public API with `X-RateLimit-Limit/Remaining/Reset` headers
  - Riot API rate-limit handling: exponential backoff, `X-RateLimit-*` header-aware retries
  - In-memory Caffeine cache for match-v5 responses (protects the dev-key rate limit)
  - Snapshot retention cleanup (90 days) + composite/standalone indexes for fast chart queries
  - Admin-token auth (`X-Admin-Token`) for mutations — viewers only read
  - `/actuator/health` endpoint for monitoring
  - Validation of Riot ID format (gameName/tagLine) with readable error messages
  - Feature-first package layout (`dashboard/`, `player/`, `riot/`, `ratelimit/`, `exception/`)
    with controller → service → repository → dto → mapper layering

- **Frontend (Angular, standalone components + signals)**
  - Landing page with hero, how-it-works, feature cards and tech stack
  - Dashboard creation flow with copyable share link and management link
  - Podium (top 3) + leaderboard sorted by rank with winrate, streaks and last LP change
  - Player detail modal: recent matches (champion, KDA, CS, duration, date, W/L), top champions
  - LP Progression Trends chart — smooth rounded curves, hover dots, rank ladder axis, last-30-days window
  - Compare mode — pick 2–3 players and see LP/winrate/KDA/streaks/matches side by side
  - CSV/JSON export of the whole ranking
  - Admin UI: Add/Remove players with two-step remove confirmation (creator only)
  - Auto-refresh every 5 minutes + manual refresh
  - Skeleton loading states (podium, rows, matches) and full mobile responsiveness
  - Live demo dashboard (`/demo`) with realistic sample data — fully client-side, no API key needed

- **Infrastructure & tooling**
  - GitHub Actions CI: backend `mvn test` (112 tests), frontend build + `ng test` (70 tests), Playwright e2e (6 tests)
  - Production deploy job: SSH → rsync → `docker compose -f docker-compose.prod.yml up -d --build`
  - Docker Compose for local dev (`scripts/dev.sh` — one-command stack) and production
  - Caddy with automatic Let's Encrypt HTTPS, SPA routing and security headers
  - Flyway migrations V1–V6 (schema, LP snapshots, indexes, admin token)
  - Playwright e2e covering the full flow, rate limiting and the demo

### Changed

- Full English pass across the whole codebase: UI strings, validation messages, comments,
  test names, logs, CI job names and docs (previously mixed Polish/English)
- Demo dashboard now features popular pro player names (Faker, Caps, Rekkles, Jankos…)
- Rate limiter upgraded from fixed window to sliding window with an injectable clock
- Leaderboard sorts by rank (not raw LP) — Emerald IV 20 LP outranks Silver I 76 LP

### Fixed

- LP Progression Trends showing only today's point — now collects daily snapshots (2h cron + on view)
- Per-match LP change showing whole-interval deltas on one game — match rows hide zero/no-data changes
- Empty "Last Change" column shifting layout — fixed column width with an explanatory tooltip
- Chart lines now render as rounded curves (Catmull-Rom → cubic Bézier) with hover-only dots
- Flaky chart hover test (fake timers + live-point stabilization to the minute)
- Player avatar, CS and game-duration display edge cases
- Flyway checksum alignment for comment-only edits to applied migrations
- CI hardening: Java 21 on runners, action version bumps, private-submodule checkout

---

## How to release

```bash
# 1. Merge develop into main (release branch)
git checkout main && git merge develop && git push origin main

# 2. Tag the release (annotated) and push it
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```
