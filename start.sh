#!/usr/bin/env sh
# FAST Studio prototype - one-command start (macOS / Linux). Needs Java 21+ and Node.js 20+.
# ./start.sh          demo mode (fictional companies, one-click demo accounts)
# ./start.sh --pilot  real-data pilot: no demo content, data in data-pilot/, first admin from data-pilot/pilot.env
set -e
cd "$(dirname "$0")"
command -v java >/dev/null || { echo "Java 21 or newer is required: https://adoptium.net"; exit 1; }
command -v npm >/dev/null || { echo "Node.js 20 or newer is required: https://nodejs.org"; exit 1; }
# your own settings (copy .env.example to .env); in pilot mode data-pilot/pilot.env is loaded after it and wins
[ -f .env ] && { set -a; . ./.env; set +a; }
if [ "$1" = "--pilot" ]; then
  mkdir -p data-pilot
  if [ ! -f data-pilot/pilot.env ]; then
    rnd() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }
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
fi
[ -d frontend/node_modules ] || (cd frontend && npm install --no-audit --no-fund)
[ -d mailer/node_modules ] || (cd mailer && npm ci --no-audit --no-fund)
(cd frontend && npm run build)
echo "Starting FAST Studio on http://localhost:8080 ..."
cd backend && ./mvnw -q spring-boot:run
