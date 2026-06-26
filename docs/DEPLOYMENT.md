# Production deployment (climbcheck.com)

This document describes what has to be done manually to get the site live at
`https://climbcheck.com` — everything else is already in the repo (the `deploy` job
in GitHub Actions, `docker-compose.prod.yml`, Caddy with auto-HTTPS).

## 1. Production Riot Games API key

The dev key (from `developer.riotgames.com`) **expires after 24h** and **must not** be used
in a public product. The production key:

1. Go to https://developer.riotgames.com → **Applications** → **Create New Application**.
2. Fill in the product description: name (ClimbCheck), purpose (a public web app for
   tracking solo queue rankings in a group of friends), domain (`climbcheck.com`),
   feature description (dashboard + LP leaderboard, charts, data from account-v1, league-v4, match-v5).
3. Riot reviews the application (usually a few days). After approval the **production key**
   appears in the dashboard — different from the dev key, with a longer validity and higher limits.
4. The key goes into the GitHub secret **`RIOT_API_KEY`** (Settings → Secrets) and into `.env`
   on the server. Never into the repo.

> ⚠️ Production key limits depend on the approved product; the 2h snapshot scheduler
> and the match cache are designed so they do not eat through the limits.

## 2. DNS and HTTPS

Caddy (in `frontend/Caddyfile`) **issues and renews the Let's Encrypt certificate itself** —
no certbot or crons needed. Requirements:

1. **A record**: `climbcheck.com` → server IP (e.g. `A climbcheck.com 203.0.113.10`).
2. Ports **80 and 443** open on the server (firewall).
3. The domain in the `DOMAIN` variable (in `.env` on the server, or the default `climbcheck.com`
   from `docker-compose.prod.yml`).

Caddy redirects HTTP → HTTPS and adds security headers (HSTS, nosniff,
`Referrer-Policy: no-referrer` — important, because the management key sometimes lives in the URL `?admin=`).

Local test without a domain: `DOMAIN=localhost docker compose -f docker-compose.prod.yml up -d --build`
(Caddy issues an internal cert for localhost).

## 3. Server bootstrap

The server needs: **docker + compose v2** (plugin), **rsync**, and a deploy directory:

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 rsync
sudo usermod -aG docker $USER

sudo mkdir -p /opt/climbcheck && sudo chown $USER:$USER /opt/climbcheck
cd /opt/climbcheck && cat > .env <<'EOF'
DATABASE_USERNAME=lp_user
DATABASE_PASSWORD=<strong-password>
RIOT_API_KEY=<production-key>
DOMAIN=climbcheck.com
EOF
```

Deployer SSH key: `ssh-keygen -t ed25519` locally → the public key goes to
`~/.ssh/authorized_keys` on the server, the private one becomes the GitHub secret **`SSH_KEY`**
(+ `SSH_HOST`, `SSH_USER`).

## 4. Deploy

A push to **main** (or a manual `workflow_dispatch` in GitHub Actions) runs the
`deploy` job: rsync of the code → `docker compose -f docker-compose.prod.yml up -d --build`
→ `/actuator/health` healthcheck → verification of the public `https://DOMAIN/`.

The `.env` on the server is protected from overwriting (excluded in rsync).

> Troubleshooting: if the server DB already has the migrations applied (e.g. from an
> earlier deployment), a comment-only edit to an applied migration can cause a Flyway
> `checksum mismatch` on the next boot. Fix it once with `flyway repair` (or delete the
> container volume if the data is disposable). Fresh databases are never affected.

## 5. Disclaimers (Riot license requirement)

The footer on every page contains the disclaimer required by Riot
("ClimbCheck isn't endorsed by Riot Games…") plus links to **Privacy** and **Terms**
(`/privacy`, `/terms`), which describe: stored data (tokens, Riot IDs, LP/rank/winrate snapshots),
no cookies/tracking, the data source (Riot Games API) and removal.
