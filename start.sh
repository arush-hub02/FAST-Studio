#!/usr/bin/env sh
# FAST Studio prototype - one-command start (macOS / Linux). Needs Java 21+ and Node.js 22+.
# ./start.sh          demo mode (fictional companies, one-click demo accounts)
# ./start.sh --pilot  real-data pilot: no demo content, data in data-pilot/, first admin from data-pilot/pilot.env
set -e
cd "$(dirname "$0")"
command -v java >/dev/null || { echo "Java 21 or newer is required: https://adoptium.net"; exit 1; }
command -v npm >/dev/null && [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 22 ] || { echo "Node.js 22 or newer is required: https://nodejs.org"; exit 1; }
rnd() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
# your own settings (copy .env.example to .env); in pilot mode data-pilot/pilot.env is loaded after it and wins
[ -f .env ] && { set -a; . ./.env; set +a; }
EDGE_DATA="$(pwd)/data/edge"
if [ "$1" = "--pilot" ]; then
  mkdir -p data-pilot
  if [ ! -f data-pilot/pilot.env ]; then
    email="${FASTSTUDIO_ADMIN_EMAIL:-admin@pilot.local}"
    pw="${FASTSTUDIO_ADMIN_PASSWORD:-Fs-$(rnd 8)}"
    umask 077
    printf 'FASTSTUDIO_MEDIA_SECRET=%s\nFASTSTUDIO_ADMIN_EMAIL=%s\nFASTSTUDIO_ADMIN_PASSWORD=%s\n' "$(rnd 32)" "$email" "$pw" > data-pilot/pilot.env
    cat >> data-pilot/pilot.env <<'ENV'
# Generated on the first pilot start: keep this file private. Values here win over .env.
# Email, your address (FASTSTUDIO_APP_URL), HTTPS and the rest: put them in .env (see .env.example).
ENV
    printf 'FAST Studio pilot - first sign-in\n\nOpen http://localhost:8080 and sign in as:\n  Email:    %s\n  Password: %s\n\nYou must choose your own password straight away.\n' "$email" "$pw" > data-pilot/FIRST-SIGN-IN.txt
    echo "First pilot start - sign in as $email with temporary password $pw (also in data-pilot/FIRST-SIGN-IN.txt)"
  fi
  set -a; . ./data-pilot/pilot.env; set +a
  export SPRING_PROFILES_ACTIVE=pilot FASTSTUDIO_DATA_DIR="$(pwd)/data-pilot"
  EDGE_DATA="$(pwd)/data-pilot/edge"
fi
[ -d frontend/node_modules ] || (cd frontend && npm install --no-audit --no-fund)
[ -d mailer/node_modules ] || (cd mailer && npm ci --no-audit --no-fund)
[ -d worker/node_modules ] || (cd worker && npm ci --no-audit --no-fund)
(cd frontend && npm run build)
# Viewers are served by the Cloudflare Worker (worker/), run locally by wrangler. It shares one secret with the
# server, generated once into data/runtime/edge.env unless .env sets FASTSTUDIO_EDGE_SECRET.
mkdir -p data/runtime
if [ -z "$FASTSTUDIO_EDGE_SECRET" ]; then
  [ -f data/runtime/edge.env ] || (umask 077; printf 'FASTSTUDIO_EDGE_SECRET=%s\n' "$(rnd 32)" > data/runtime/edge.env)
  set -a; . ./data/runtime/edge.env; set +a
fi
(umask 077; printf 'EDGE_SECRET=%s\n' "$FASTSTUDIO_EDGE_SECRET" > worker/.dev.vars)
(cd worker && WRANGLER_SEND_METRICS=false npx wrangler dev --ip 127.0.0.1 --port 8787 --persist-to "$EDGE_DATA" > ../data/runtime/edge.log 2>&1) &
trap 'kill 0 2>/dev/null' EXIT
echo "Local Cloudflare Worker on http://127.0.0.1:8787 (log: data/runtime/edge.log)"
echo "Starting FAST Studio on http://localhost:8080 ..."
cd backend && ./mvnw -q spring-boot:run
