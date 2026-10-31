# FAST Studio — working prototype

Turn a video library into 24/7 TV channels with ads: upload, licence, schedule, play live with **real SSAI and SGAI ad breaks** and **real SCTE-35 markers**, then send to (simulated) partner platforms. Built so non-technical people can follow it end to end.

> **Full illustrated walkthrough:** [docs/PRODUCT_GUIDE.md](docs/PRODUCT_GUIDE.md) (also `docs/FAST_Studio_Product_Guide.pdf`).

> Demo environment: companies, people, ads and money are fictional. Simulated parts are labelled in the UI.

> **Agents (Codex, Claude and others):** read [AGENTS.md](AGENTS.md) and [PROGRESS.md](PROGRESS.md) before working. Claim your task and update progress, verification and the next action before every handoff. The detailed continuation plan is [docs/MEDIA_INGESTION_PLAN.md](docs/MEDIA_INGESTION_PLAN.md).

## Start it

Needs **Java 21+** and **Node.js 22+** (the local Cloudflare Worker needs it; with nvm: `nvm use 24`). By default PostgreSQL starts inside the app, ffmpeg comes from npm, and the viewer-facing Cloudflare Worker runs locally under `wrangler dev` (no Cloudflare account needed).

- **Windows:** double-click `start.bat` to build/start; double-click `stop.bat` to stop. `start.cmd` forwards to the same launcher.
- **macOS/Linux:** `./start.sh`

Then open **http://localhost:8080**. The first start downloads libraries and generates about 1 hour of demo video with ffmpeg (a few minutes). Local data is kept in `data/` between runs. Stopping the app preserves it; Docker database storage is described separately below.

On Windows the Java server and the local Worker (port 8787) run in the background. Startup waits for both before opening the browser. Logs are in `data/runtime/app.log`, `app-error.log` and `edge.log`. The Worker's local R2 and KV live in `data/edge`. Repeated starts recognize this workspace's running server. Stop targets only a verified workspace Java process, shuts down its embedded database cleanly, and retains data. The original relative-jar/Maven launch can also be recognized when the JDK's `jcmd` is available. An unrecognized process on port 8080 is left alone. For troubleshooting, run `start.bat -NoBrowser` from a terminal.

The Windows launcher now checks that the HTML application and API both respond before opening a browser. Java runs from a hash-named copy in `data/runtime/releases/`, so rebuilding Maven's output cannot overwrite the running package. If the existing process is unhealthy, stop it with `stop.bat` and start again to rebuild. When the default Node version is too old, the launcher can use an already-installed compatible NVM runtime for this launch without changing the system-wide selection.

### Where is the database? Is it in Docker?

The default database is **embedded PostgreSQL 14**, with database name/user `postgres`, on localhost port `54329`. Its files are in `data/pg`. No Docker image or container is needed in this mode. Tables are created by Flyway from `backend/src/main/resources/db/migration`; fictional records are inserted by `DemoSeeder`.

Optional Docker mode is configured in [compose.yaml](compose.yaml). Start Docker Desktop, stop the currently running app, then run:

```bat
stop.bat
start.bat -Docker
```

This pulls the official `postgres:17-alpine` image and creates container **`faststudio-db`**, database/user **`faststudio`**, and persistent volume **`faststudio_pgdata`**. Connect using `127.0.0.1:55432`. The launcher generates a local password in git-ignored `data/runtime/docker.env`; it passes the matching `DB_URL`, `DB_USER` and `DB_PASSWORD` to Java. Do not remove this credential file while keeping the Docker volume: the database retains its original password. This is a local development setup.

In Docker Desktop, see **Images** for `postgres:17-alpine`, **Containers** for `faststudio-db` (under project `faststudio`), and **Volumes** for `faststudio_pgdata`. Docker manages the volume inside its Linux storage; it is separate from Windows `data/pg`. The two modes have independent database contents; switching does not migrate existing records. They share the existing media directory.

After a Docker launch, `stop.bat` stops both Java and the managed database while preserving the volume. If the launcher state was lost, `stop.bat -Docker` also stops this Compose database. To run database-only commands after the first credential generation:

```bat
docker compose --env-file data/runtime/docker.env up -d --wait db
docker compose --env-file data/runtime/docker.env ps
docker compose --env-file data/runtime/docker.env stop db
```

`start.bat` without `-Docker` still honors an existing `DB_URL` for a separately managed server; `stop.bat` does not stop such an external server. Docker mode explicitly selects the Compose database. Database tables and seed data appear when Java first connects, not merely when the image is pulled.

Developer mode (hot reload): run the backend with `cd backend && ./mvnw spring-boot:run`, and the UI with `cd frontend && npm run dev`, then open http://localhost:5173.

### Settings: application.yaml and .env

All settings, each with a description, are in two files:

| File | What it is |
| --- | --- |
| `backend/src/main/resources/application.yaml` | Every setting with its default, used for development and the demo |
| `backend/src/main/resources/application-pilot.yaml` | The few values that differ for real data (`start.bat -Pilot`) |
| `.env.example` | Every environment variable, documented with examples (mail servers too). Copy it to `.env` and uncomment what you need. |
| `.env` (you create it, git-ignored) | Your own values and secrets. `start.bat` / `start.sh` load it in demo and pilot mode. |
| `data-pilot/pilot.env` (generated, git-ignored) | Pilot secrets created on the first pilot start: media signing key and first administrator. Loaded after `.env` and wins. |

How a value travels: `.env` → environment → `application.yaml` (`${NAME:default}`) → the server. For email, the server passes `FASTSTUDIO_SMTP_URL` on to nodemailer (`mailer/send.mjs`) as `SMTP_URL`. Any other setting can also be given as an environment variable: upper-case, with dots and dashes as underscores, e.g. `FASTSTUDIO_PARTNERS_RATE_LIMIT_PER_MINUTE=300`.

### Demo accounts (development only, password `demo1234`)

The sign-in page lists them as one-click buttons.

| Account | Role | Use it to show |
| --- | --- | --- |
| owner@aurora.test | Owner, Aurora Media (also Analyst at Northwind) | Everything; switching organizations |
| operator@aurora.test | Channel operator | Scheduling, publishing, going live |
| content@aurora.test | Content manager | Videos and licences |
| ads@aurora.test | Ad operations | Campaigns and ad decisions |
| analyst@aurora.test | Analyst | Read-only: buttons disappear, API refuses edits |
| owner@northwind.test | Owner of another company | Tenant isolation: can't see Aurora at all |
| partner@partnertv.test | Partner admin at *Partner TV* (a fictional smart-TV app) | Partner access: sees only the 2 channels Aurora shares, read-only |
| admin@platform.test | Platform admin | Create and suspend customer organizations |

## Use it with real data (pilot mode)

Demo mode is for showing the product. For your own companies, videos and people, start **pilot mode**. It has no demo content and no demo accounts, and keeps its data in a separate folder, `data-pilot/`, so the demo is never touched.

```bat
stop.bat
start.bat -Pilot
```

macOS/Linux: `./start.sh --pilot`.

On the first pilot start the launcher creates `data-pilot/pilot.env` containing:

- a stable media-signing secret (so video links survive restarts), and
- the first platform administrator. It uses `FASTSTUDIO_ADMIN_EMAIL` / `FASTSTUDIO_ADMIN_PASSWORD` if you set them, otherwise `admin@pilot.local` with a generated temporary password.

The sign-in details are printed once and saved in `data-pilot/FIRST-SIGN-IN.txt`. Then:

1. **Sign in as the administrator.** You must choose your own password at once (at least 10 characters, not containing your email name). The temporary one stops working.
2. **Organizations → New organization** creates a customer company and its owner. The owner is **emailed** their sign-in details, and the one-time password is also shown on screen in case the email doesn't arrive.
3. **The owner signs in and sets their own password.** In **Team** they invite colleagues; each colleague gets an invitation email with a one-time password.
4. **Forgotten passwords:** anyone (any role, administrators included) can use **Forgot your password?** on the sign-in page to get a reset link by email. Admins can also help: on the Team page, **Reset password** emails a link that works once for 24 hours (and shows it too). A person who also works for another company, or a platform administrator, must be reset by the platform admin (Organizations → *Reset someone's password*). Anyone can change their own password by clicking their avatar.

`stop.bat` stops whichever mode is running. Logs for both modes are in `data/runtime/`. Keep `data-pilot/pilot.env` private. Without a 32+ character media secret, the pilot profile refuses to start. Behind HTTPS, set `FASTSTUDIO_SECURE_COOKIES=true`.

### Email (nodemailer)

FAST Studio sends invitations, new-owner welcomes, reset links, password-changed notices and monitoring alerts. The Java server queues each email in the database (outbox) and hands it to `mailer/send.mjs`, which uses **nodemailer**. Settings go in `.env` (copy `.env.example`: it explains, with links, where to get the values for Mailtrap, SendGrid, Amazon SES, Google and Microsoft 365):

| Setting | Example | Default |
| --- | --- | --- |
| `FASTSTUDIO_SMTP_URL` | `smtps://user:password@smtp.example.com:465` | blank: emails are saved as `.eml` files in `<data folder>/mail-outbox` instead of sent |
| `FASTSTUDIO_MAIL_FROM` | `"FAST Studio <no-reply@yourcompany.com>"` | `FAST Studio <no-reply@faststudio.local>` |
| `FASTSTUDIO_APP_URL` | `https://studio.yourcompany.com` | `http://localhost:8080` (used for links in emails) |

Platform admins edit every template on **Platform → Email** (live preview, required values are checked before saving). The same page shows delivery status, a **Send a test email** button and the **Sent emails** log; one-time passwords and links are hidden in the log. The launchers install the mailer automatically (`npm ci` in `mailer/`).

### Partners (TV platforms and apps)

Customers share chosen channels with **partner organizations** on the **Partners** page: channel details, programme guide (XMLTV), stream set-up with the partner's own ad mode, ad-break times (SCTE-35), and viewing numbers. Partners accept and read in a small portal (*Shared with us*). Their systems use API clients: `POST /oauth/token` with client credentials, then `/api/partners/…` with the Bearer token. Everything is read-only, can be stopped at once, and appears in the customer's Activity log. API reference: `/partner-api.openapi.json`. Use case and example: guide chapter 24 and `docs/PARTNER_ACCESS_AND_TOKENS.md`.

### Sign-in tokens

Sign-in uses signed tokens (JWT): a 10-minute access token and a refresh token, kept in HttpOnly cookies, so the web app works as before. Scripts can sign in with `POST /api/auth/token` and send `Authorization: Bearer <access_token>`. Tenant API paths accept the organization's ID (UUID) or its slug. The token says who you are, your tenant, your organizations and roles, and your permissions. Signing keys live in `<data folder>/keys/` (included in backups). `FASTSTUDIO_AUTH_MODE=session` switches back to server sessions for one release. Details: `docs/PARTNER_ACCESS_AND_TOKENS.md`.

### Backups

```powershell
powershell -File tools/backup.ps1 -Pilot
```

This makes a full copy of the database and media in `backups/pilot-<date-time>/`. If the app is running, it is stopped for the copy (about 30 seconds) and started again.

```powershell
powershell -File tools/backup.ps1 -Action Restore -Pilot -From backups/pilot-20261020-160454
```

This puts that copy back. The data being replaced is first saved in `backups/pilot-before-restore-<date-time>/`, so a restore can itself be undone. Leave out `-Pilot` to back up or restore the demo. Copy `backups/` to another disk or cloud storage: a backup on the same disk does not protect against disk failure. Docker mode is not covered by this script; use `pg_dump` (the script prints the command).

## 10-minute leadership demo

1. **Sign in** as Asha (owner). Home shows the launch checklist, what's on air, and alerts.
2. **How FAST works** (Help): what FAST is and why it exists, then the SSAI/SGAI/CSAI toggle.
3. **Library**: real thumbnails, processing status, licence badges. Open *Neon Pursuit*, play the preview and point at the ad break point.
4. **Rights → Can it play?** *Steel Horizon* for a US viewer is blocked, and each rule shows why.
5. **Schedule → Travel & Discovery**: a red *Must fix* (Mountain Markets has an incomplete licence). Click it, remove the programme, **Publish** (version 1). Then **Channels → Go live**.
6. **Watch live → Aurora Action**, viewer in India, *web player (SSAI)*. Wait for an ad break (a few per hour; the side panel shows when the next one starts):
   - stitched ads with an "Ad 1 of 4" overlay;
   - the *Ad delivery log* fills with Started/25%/…/Completed;
   - Bluebird Air is left out because it doesn't target India.
7. **Under the hood** tab: the live HLS playlist updating every 3 s, with DISCONTINUITY, the `DATERANGE … SCTE35-OUT=0xFC…` marker and `/ad/` segments. Click *Decode it* to open the SCTE-35 inspector.
8. Switch **Watching on → SGAI beta**: the playlist now carries a `com.apple.hls.interstitial`, and the player fetches the ads itself.
9. Switch **Viewer is in → United States** while *Steel Horizon* is on: the "Not available in your region" screen plays. That is geo-blocking enforced in the stream.
10. **Distribution**: send Aurora Action to *Smart TV Hub*. It fails with a reason (ad load 450 s/h, limit 360) and an alert appears in **Monitoring**. *Partner TV* passes (labelled simulated).
11. **Analytics**: every number comes from the playback you just watched.
12. Sign in as **analyst** (read-only), then as **owner@northwind.test** (a different company, sees nothing of Aurora).

## What's real and what's simulated

| Capability | Status in this prototype |
| --- | --- |
| Sign-in, sessions, CSRF, roles, tenant isolation, audit log | **Real** (local accounts; production swaps in OIDC) |
| Video upload → ffmpeg → 5 s HLS segments (360p/540p/720p ladder, loudness −23 LUFS), thumbnails | **Real**. Where video comes from is a content connector (upload, generated demo, URL, Mux library: catalogue sync, direct upload, webhooks; packaged once onto R2) |
| Licences: territories, blocks, dates, ads-allowed; default deny | **Real**, enforced by schedule validation, playout and ad decisions |
| Scheduling: auto-fill, cue-point mid-rolls, ad-load limit, overlap/gap/rights checks, optimistic locking, immutable versions, rollback | **Real** |
| EPG from the published version, XMLTV export | **Real** |
| Live channel playout (virtual linear HLS) | **Real**, computed per request by the Cloudflare Worker (`worker/`) from a schedule snapshot in KV and the clock; video served from R2. Locally emulated by wrangler |
| SCTE-35 markers (time_signal + segmentation descriptor, MPEG-2 CRC) | **Real**, verified against the SCTE sample in unit tests |
| SSAI (server-stitched ads, server-side beacons) | **Real**: count-preserving slate-for-ad swap in the Worker; a spot counts when its segment enters the viewer's window |
| SGAI (HLS Interstitials, client beacons) | **Real** with hls.js ≥1.6 / Apple players |
| Ad decisions | **Real waterfall of ad connectors** per tenant: direct-sold campaigns plus any number of external VAST ad servers (macros, wrappers, timeouts, tag tester, tracking URLs fired on delivery). New creatives are re-cut to 5 s and air from the next break |
| Viewer location | **Simulated**: you pick the country. Production uses trusted edge geo-IP plus signed URLs. |
| Partner TV platforms | **Simulated**: conformance checks are real; no partner is contacted. |
| Revenue | **Estimate** = impressions × typed-in CPM. Not market data, not money received. |
| CDN | R2 + Cloudflare CDN when the Worker is deployed (`worker/wrangler.toml` lists the steps); locally the Worker serves R2 itself |
| Billing, settlement, DRM, SSO | Not built (see `docs/IMPLEMENTATION_ROADMAP.md`) |

## Smart programming and viewer ads

- Platform administrators define media types at `/platform/media-types` (also linked from Connectors), then grant types to each company in Connectors.
- Tenant owners/admins choose enabled media types, a default, new-episode weekdays/time and personalization in **Settings**.
- In **Library**, choose Episode and enter its series, season and episode number. Existing imported programme videos can be classified as episodes in their Edit tab without re-uploading.
- In **Schedule → Smart programming**, preview and save a series rule, generate its draft, review issues, then publish normally. Each selected day advances one episode; an optional encore repeats that day's episode. A background job extends drafts 14 days ahead; it never publishes automatically.
- In **Ads**, direct campaigns may target signed audience keys and set hourly viewer selection caps. Integrate your viewer application's server using [Viewer context](docs/VIEWER_CONTEXT.md). Without permitted context, viewers receive contextual ads.

The Worker requires the `DECISIONS` Durable Object binding/migration now included in `worker/wrangler.toml`. Configure and deploy it before using the updated control plane with a remote Worker. This feature set does not resolve all production-readiness gaps. Detailed scope: [Smart programming and viewer advertising](docs/SMART_PROGRAMMING_AND_VIEWER_ADS.md).

## Project layout

```
backend/   Java 21, Spring Boot 4 — API, playout, SCTE-35, ad decisions, media jobs
  src/main/java/com/faststudio/{core,catalog,rights,channels,schedule,playout,ads,distribution,ops,team,demo,media}
  src/main/resources/db/migration/V1__schema.sql   (multi-tenant schema)
frontend/  React 19 + TypeScript + Vite, hls.js — the web app
worker/    Cloudflare Worker: live playlists, ad stitching, R2 media (everything a viewer touches)
mailer/    send.mjs — sends one email with nodemailer (called by the server)
tools/     smoke-test.mjs (end-to-end checks against a running server), backup.ps1 (backup/restore), launcher scripts, guide screenshots/build
docs/      product, architecture, multitenancy, and HOW_FAST_WORKS (SSAI/SGAI/SCTE-35 primer)
data/      created at runtime: embedded database + generated media (git-ignore it)
data-pilot/ the same for pilot mode, plus pilot.env (secret) and FIRST-SIGN-IN.txt
backups/   copies made by tools/backup.ps1
```

## Tests

Windows launcher checks (process ownership, syntax and Compose configuration; no server is stopped):

```powershell
powershell -NoProfile -File tools/test-launchers.ps1
```

```bash
cd backend && ./mvnw test
```

```bash
cd worker && npm test
```

```bash
node tools/smoke-test.mjs
```

`node tools/smoke-test.mjs` detects pilot mode (demo accounts switched off) and then runs a reduced set: demo sign-in refused, CSRF required, anonymous and unsigned-media access refused. With `FASTSTUDIO_ADMIN_EMAIL`/`FASTSTUDIO_ADMIN_PASSWORD` set, it also checks the administrator sign-in. The full set needs demo mode.

The first command runs unit tests: SCTE-35 encode/decode and CRC, rights rules, scheduling rules. The second runs against a running server and checks tenant isolation, roles, the live playlist, markers, EPG, ad decisions and geo rules.

### Continuous integration

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on every push and pull request, in four parallel jobs: backend `./mvnw clean test` (Java 21, embedded PostgreSQL), frontend `npm run build` (type-check + build), the Worker tests, and a browser smoke test that starts the app in demo mode and opens every main screen as the demo owner ([frontend/e2e/smoke.spec.ts](frontend/e2e/smoke.spec.ts)). It also fails when a controller or service runs SQL, or when a frontend feature imports another feature. Test reports, screenshots and traces are kept for 7 days when a job fails.

Run the browser smoke test locally against a running demo: `cd frontend && npx playwright install chromium && npm run e2e` (`E2E_BASE_URL` changes the address). The local smoke test and launcher checks need a running Windows demo and stay manual.

To make a red check block merging, turn on branch protection for `main` in GitHub (Settings → Branches → require status checks: `Backend tests (Java 21)`, `Frontend type-check and build`, `Cloudflare Worker tests`).

## Container image

The [Dockerfile](Dockerfile) builds one image with the backend, the built frontend, the mailer, ffmpeg and Node.js, running as a non-root user:

```bash
docker build -t fast-studio .
```

```bash
docker run -p 8080:8080 -v fs-data:/data --env-file .env fast-studio
```

It starts in the real-data pilot profile (no demo data); add `-e SPRING_PROFILES_ACTIVE=default` for demo mode. Data lives in the `/data` volume. For production set `DB_URL`, `DB_USER` and `DB_PASSWORD` to a managed PostgreSQL; without them the built-in database runs inside the container. The Cloudflare Worker is deployed separately (`cd worker && npx wrangler deploy`); set `FASTSTUDIO_EDGE_URL`, `FASTSTUDIO_EDGE_PUBLIC_URL` and `FASTSTUDIO_EDGE_SECRET` to reach it. No secrets are baked into the image.

## Known limitations

The Worker keeps ad decisions and discontinuity counts per isolate (move them to the Cache API if viewers hop isolates mid-break); ad decisions are cached in memory on the control plane (single server); the first time an external ad server returns a creative it can't air yet (it is re-cut to 5 s first; the tag tester can prepare it in advance); only VAST linear MP4 creatives are used; in demo mode the media-signing key is random per run (pilot mode keeps a stable one); email templates are platform-wide (not per company yet); local accounts (with JWT sign-in tokens) rather than company single sign-on; partner organizations not built yet (planned: `docs/PARTNER_ACCESS_AND_TOKENS.md`); backups are cold copies that need a short stop. Media processed before the quality ladder existed plays as a single quality — delete `data/` to regenerate the demo with all three qualities. Details and next steps: `docs/IMPLEMENTATION_ROADMAP.md`.
