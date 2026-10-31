# FAST Studio progress and continuation plan

Last updated: 2026-10-31 19:00 IST, Asia/Calcutta (UTC+05:30).
Updated by: Claude, tenancy completion checkpoint; application restructure step 1 claimed.

**Read AGENTS.md before working. Update this file before handing off.** This is the shared checkpoint for agents; it is not a production-readiness certificate. Read the relevant detailed plan before implementing its next stage.

## Current checkpoint

- Product: working FAST channel studio prototype with local playback and initial security improvements. **Not production-ready; no production deployment verified.**
- Repository: `https://github.com/arush-hub02/FAST-Studio.git`.
- Main checkpoint: `88e533b` (existing product work committed and merged/pushed).
- Current feature branch: `codex/media-ingestion`; implementation checkpoint `fc68e38`, pushed. Its predecessor `676c203` adds secure project bootstrap. These feature commits have not been merged into main.
- Shared handoff documentation checkpoint: `95c6d34`, previously pushed. The approval-policy changes following it are local and uncommitted; no further push is authorized.
- **Mandatory user policy: test the exact changes and obtain explicit user approval before every push to any branch. Earlier blanket authorization no longer applies.** See AGENTS.md for the required review evidence. Continue authorized local work while awaiting approval; do not push or merge automatically.
- Last observed local service: demo app on `http://localhost:8080`, Worker on `http://127.0.0.1:8787`, embedded PostgreSQL; recheck rather than relying on a recorded PID.
- No ingestion implementation task is currently claimed. The next task is Stage 1 below.
- User steering (2026-10-31): tenant/project/partner redesign is complete locally. Feature building is paused; next is application restructure step 1 (whole backend moved and split into the v1_0 layout, frontend into app/features/shared, repo root cleanup). No push is authorized.

## Active work and ownership

Keep one row per agent/task. Add your own row before implementation. Paths must be specific enough to expose overlapping work. For separate worktrees, also record the worktree path; unmerged progress changes in one branch are not automatically visible in another, so communicate the claim to the coordinating agent.

| Agent / session | Task | Branch / worktree | Affected paths | Status | Next checkpoint |
| --- | --- | --- | --- | --- | --- |
| Codex / 2026-10-31 handoff | Establish shared progress and mandatory agent workflow | codex/media-ingestion / repository checkout | AGENTS.md, CLAUDE.md, PROGRESS.md, README.md, docs/TEST_PLAN.md, docs/MEDIA_INGESTION_PLAN.md | Completed; documentation only | Next agent claims Stage 1 after inspecting Git state |
| Codex / 2026-10-31 approval policy | Require testing and explicit user approval before every push | codex/media-ingestion / repository checkout | AGENTS.md, CLAUDE.md, PROGRESS.md | Completed locally; uncommitted and not pushed | Next agent honors approval gate; Stage 1 remains unclaimed |
| Codex / 2026-10-31 tenancy redesign | Customer tenants with projects; separate partners; dynamic configuration and connector/plan assignment | codex/media-ingestion / repository checkout | backend tenancy migration/controllers/services/repositories/DTOs and bootstrap/auth integration; frontend platform/settings/navigation; tests; docs | Handed off to Claude 2026-10-31 (Codex usage exhausted); local only | See Claude tenancy completion row |
| Claude / 2026-10-31 tenancy completion | Finish Codex's tenancy redesign: move tenancy/project code into v1_0 layout with comments; built-in programming settings as dynamic properties; tenant plan channel limit; review fixes | codex/media-ingestion / repository checkout | backend v1_0/{controllers,services,repositories,dto}/{tenancy,project,configuration,team}, V15 migration, catalog/MediaSettings*, channels, connectors, ads, core auth/token callers, tests; frontend Team/TenantAdmin/types; docs | Completed; committed locally, not pushed | Restructure step 1 |
| Claude / 2026-10-31 restructure step 1 | Whole-application restructure, no feature changes: backend packages moved into v1_0 layout with SQL/business logic split out of controllers (user chose move + split); frontend into app/features/shared; root cleanup; docs | codex/media-ingestion / repository checkout | all of backend/src, frontend/src, repo root, docs | In progress, local only; one local commit per phase; no push authorized | Phase 1: config/core into v1_0 |
| Claude / 2026-10-31 backend layout | Adopt user's reference microservice folder architecture as the backend target layout; documentation only | codex/media-ingestion / repository checkout | docs/BACKEND_ARCHITECTURE.md (new), AGENTS.md, CLAUDE.md, PROGRESS.md, docs/MEDIA_INGESTION_PLAN.md, docs/PROJECT_BOOTSTRAP_SECURITY.md, docs/TECHNICAL_ARCHITECTURE.md | Completed locally; uncommitted, not pushed; no Java files moved | Stage 1 implements media under the new layout |

Do not infer another agent's work is abandoned from an old timestamp. Coordinate before taking over its paths. Mark completed/handed-off claims explicitly.

## Built versus remaining

| Area | Implemented now | Remaining / limitations |
| --- | --- | --- |
| Tenant platform | Organizations, memberships/roles, PostgreSQL RLS, platform administration and audit foundation | Full production identity/session, throttling, security and recovery validation |
| Media library | Asset catalogue, single-file upload, rights/metadata, platform media-type registry and tenant enablement, episode metadata | Resumable bulk upload, import batches, CSV parser/preview/import and connected-library picker |
| Programming | Manual scheduling/publish, versions and EPG; smart daily ordered episodes, weekdays, season rollover, encore/loop behavior; recurring draft generation | Broader production acceptance; generation prepares drafts, it does not automatically authorize publication |
| Playback | Virtual linear HLS, local Cloudflare Worker, R2/KV integration, rendition playlists, SCTE-35 and ad-break handling | Actual deployed origin/CDN access controls, representative movie-length playback, device, load and recovery acceptance |
| Advertising | Direct/VAST sources, signed viewer context, audience targeting, frequency-cap foundation, shared decision persistence, stable edge decisions/slate and beacon retries | Real provider end-to-end acceptance, authoritative measurement, pacing/settlement and privacy-policy completion; demo numbers are not revenue |
| Mux | Per-tenant encrypted connection controlled by platform admin; basic catalogue sync, direct-upload backend and webhooks | Capability-tested connections, resumable background sync, safe metadata refresh, item results and operational retries |
| Project bootstrap | Tenant/platform project-details APIs with allowlisted DTOs, no-store response and React context after login/tenant switch | Only this new slice uses the full requested folder boundary; existing media/Mux extraction is next |
| Credential responses | Stored Mux token identifiers/secrets and ad-tag URLs omitted from touched read APIs; write-only admin entry; public ad decisions omit tracking URLs | This is not a full audit. Legacy ad-tag at-rest encryption and outbound download/redirect protections remain |
| CSV | Blank template and filled example in docs/samples | Samples are documentation artifacts; application download/import workflow is not built |
| Business/distribution | Prototype screens, conformance/partner-sharing foundations and reporting | Billing/usage/payment, authoritative financial reconciliation/royalties and real destination onboarding/acceptance |

## Agreed architecture and product constraints

- Stack: Java 21 / Spring Boot, PostgreSQL, React/TypeScript, Cloudflare Worker/R2/KV; edge decision state also uses Durable Objects.
- Intended programme pipeline: files/URL/tenant OVP catalogue -> Mux encoding -> compatible encoded exports -> packaging/QC -> versioned R2 media -> Cloudflare CDN segments; Worker assembles final live/personalized manifests.
- **Current Mux implementation differs:** it downloads the master and delegates full local FFmpeg encoding. Do not describe the intended export pipeline as built. Prove export/keyframe/segment compatibility before replacing it; record any need for another full encode and its cost impact.
- R2 bandwidth egress being free does not make all viewing infrastructure free; storage, requests, Workers and provider operations still have costs. Cost documents contain dated assumptions, not a current quote.
- Tenant Mux credentials are platform-owner managed. Authorized tenant users may use allowed catalogue/import capabilities, without reading stored secrets. Saved configuration alone is not connection-health evidence.
- Catalogue sync imports references/metadata to existing provider assets; do not re-upload them or silently trigger paid preparation. Catalogue synced, provider ready and broadcast ready are separate states.
- Backend folder layout (adopted 2026-10-31 from the user's reference microservice): [BACKEND_ARCHITECTURE.md](docs/BACKEND_ARCHITECTURE.md). `v1_0/controllers/<audience>/<feature>`, `services`, `repositories`, `dto`, provider clients in `v1_0/util/<provider>`, async in `messaging/v1_0`; `V1_0` suffix on controllers and primary services. Root package stays `com.faststudio`; transport stays the agreed Postgres outbox + Cloudflare Queues (reference used RabbitMQ). Legacy packages move only when touched. Preserve API paths and tenant/RLS enforcement. No whole-repository folder rewrite.
- Proposed queue design: PostgreSQL job rows are authoritative and serve as the transactional dispatch outbox; Cloudflare Queues transports job IDs to Java pull consumers. Queue implementation is pending. Finalize lease/acknowledgment semantics for long jobs first; video payloads never go through the queue.
- New ready revisions must publish atomically; failed imports/export/QC must retain existing airing media. Retry and webhook replay must not duplicate assets or paid work.
- Future OVPs use capability-based adapters behind the same import services. No plugin marketplace or extra real connector is required for the first implementation.

## Continuation plan

Detailed contracts, proposed schema, folder layout, defaults and acceptance cases: [MEDIA_INGESTION_PLAN.md](docs/MEDIA_INGESTION_PLAN.md). Its stage numbers below are the shared task identifiers.

| Stage | Status | Work and completion evidence |
| --- | --- | --- |
| 0. Security/bootstrap prerequisite | Implemented at 676c203 | Project-details bootstrap and safe credential DTO boundary. See PROJECT_BOOTSTRAP_SECURITY.md. Does not complete the media refactor. |
| 1. Safe media/Mux structural refactor | Not started | Extract asset/Mux services and repositories, share metadata/episode/rights validation; retain endpoints, permission checks and compatible responses. Existing behavior and cross-tenant tests pass. Start a small export feasibility spike early if provider access and paid work are authorized. |
| 2. Durable ingestion foundation | Planned | Connection, batch/item/source/upload/job state, provider registry, dispatcher, leases/fencing and status APIs. Finalize queue handoff semantics. Prove duplicate delivery, restart, cancellation and tenant isolation. |
| 3. Existing Mux library | Basic legacy sync exists; new workflow planned | Health/capability check, paginated browse/checkpoints, idempotent sync, preserve manual metadata, catalogue-only import and explicit prepare selection. Prove partial scan recovery without duplicate uploads. |
| 4. CSV and library picker | Samples only | Template downloads, maintained CSV parser, validation/mapping/preview, source matching and actionable row results. Prove quoting, duplicate episodes, tenant types/rights and retry behavior. |
| 5. Batch direct upload | Backend upload primitive only | Multiple files, metadata review, resumable direct-provider sessions, bounded concurrency and batch progress. Prove large-file interruption/resume and item retry without duplicate assets/charges. |
| 6. Mux encoded outputs to R2 | Not proven | Validate representative exports, conditioning/packaging/QC and atomic output revision. Prove programme/ad transitions, ABR switches and movie-length playback against actual services. |
| 7. Acceptance / operations | Planned | End-to-end workflows, fairness/backpressure, reconciliation, metrics, retention and migration/restore rehearsal. Record evidence and remaining limits; do not equate deployment with acceptance. |

Next concrete task: application restructure step 1 (row "Claude / 2026-10-31 restructure step 1"). Ingestion Stage 1 resumes after it, on the restructured layout. Stage 1 notes: inspect `catalog/AssetController.java`, `media/MuxController.java`, `media/MuxSource.java`, `media/ContentSource.java`, `media/MediaService.java` (paths before the restructure) and all callers/tests; do not build the bulk UI over the current in-memory processing executor.

## Production blockers and decisions to carry forward

1. Actual Mux export-to-R2 feasibility and output profile, including keyframe/segment alignment and ad transitions. Provider permissions/representative media are needed; obtain authorization before paid preparation if not already given.
2. Durable ingestion execution and tenant limits; current MediaService uses an in-memory executor. Finalize queue acknowledgment, leases, fencing and recovery before consumers.
3. Security: SSRF/DNS/redirect/download controls across media/VAST, legacy credential-bearing ad-tag storage encryption, deployed origin/CDN access, production sessions and shared throttling. Existing redaction changes do not close all of these.
4. Migration rehearsal: inspect V7 asset readiness reset and published-snapshot behavior during processing/re-sync. Do not run an unreviewed migration against customer data or silently make airing media unavailable.
5. Viewer identity/consent integration: signed context foundation exists; confirm actual signed-in/anonymous viewer contract and personalization policy. Tenant staff login is not viewer identity.
6. Real service/device playback, ad measurement, destination acceptance, sustained load, backup/restore and outage drills remain unverified production gates.
7. Finance/billing/usage and full distribution/partner acceptance are separate later scope; screens or estimated metrics are not proof of payment or delivery.

## Verification record

Evidence below is historical and scoped. Re-run checks appropriate to your change; record a new result instead of silently refreshing these dates. Ignored local artifacts are not available in a fresh clone.

| Date / checkpoint | Actual evidence | Limits |
| --- | --- | --- |
| 2026-10-31 / 676c203 security slice | Backend report aggregate: 90 tests, 0 failures/errors (17 suites); existing 83 plus 7 security/bootstrap tests. Frontend build passed. Local owner login, tenant switch and analyst restrictions checked. | Tests were run in the preceding implementation task, not rerun by this documentation task. No real Mux/ad-provider or production-load acceptance. |
| Previous implementation checkpoint | Worker suite: 9 passing checks; source command is npm test in worker. | Historical result, not rerun for launcher/docs changes. |
| 2026-10-31 / fc68e38 launcher | PowerShell syntax/ownership, immutable runtime package, UI/API readiness, compatible Node selection and Compose configuration checks passed. Frontend/backend packaging passed. Actual UI/index/API HTTP 200, demo login reached dashboard, repeated start reused app. Maven package while server was live left UI/API healthy. | Windows local demo only; Compose config check did not start a Docker database. No macOS/Linux launcher validation in this fix. |
| 2026-10-31 / uncommitted tenancy work (Claude review) | `mvnw test`: 98 tests, 0 failures/errors across 18 suites incl. 8 TenantConfigurationTest; V13/V14 migrate a fresh DB. Frontend `tsc --noEmit` passed. | Read-only review of Codex's in-progress work. No vite build, browser check or migration against existing demo/pilot data. Open findings in handoff log. |
| 2026-10-31 / tenancy completion (Claude, local commit) | `mvnw test`: 100 tests, 0 failures/errors (18 suites; TenantConfigurationTest 10). V13–V15 migrate a fresh DB. Frontend `tsc --noEmit` passed. `git diff --check` clean. | No browser check, no vite build, no migration against existing demo/pilot data. |
| 2026-10-31 / backend layout docs | Local Markdown links in the seven touched files resolve; `git diff --check` clean. | Documentation only; no application tests rerun for this change. |
| 2026-10-31 / documentation handoff | All 23 local Markdown links across the six touched documents resolve; git diff --check passed. Checked existing backend XML reports: 90 tests, 0 failures/errors. | Documentation changes only; application tests were not rerun and existing report totals are historical evidence. |

Local evidence if retained: `backend/target/surefire-reports/`, `data/handover-validation/startup-recovered.jpg`, `data/handover-validation/project-bootstrap-security.jpg`, `data/launcher-live-rebuild.log`. Some earlier isolated packaging/test output is under `data/ingestion-build/`. These directories are intentionally ignored; never commit runtime data or screenshots containing credentials.

### Useful commands and local setup

- Windows launch/stop: `start.bat -NoBrowser` / `stop.bat`. Browser opening by default is intentional. Check the UI and API, not merely whether the Java PID exists.
- Launcher regression: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/test-launchers.ps1`.
- Backend: from backend, `./mvnw test` (`.\mvnw.cmd test` on Windows); use Java 21+.
- Frontend: from frontend, `npm run build`; Worker: from worker, `npm test` using a compatible recent Node runtime.
- Local Windows paths last used: Java at `C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot`; installed Node 24 at `C:\Users\enveu\AppData\Local\nvm\v24.22.1`. Verify on your machine. The default Node was older; Windows launcher selects an installed compatible NVM runtime for its process only.
- Windows app now runs from `data/runtime/releases/<sha256>.jar`, not mutable backend/target output. Use stop/start to apply a newly built version. Do not revert this isolation or kill unrelated Java/Node processes. Preserve data and verify workspace ownership before any restart.
- Demo data: `data/pg`, `data/edge`; pilot data: `data-pilot`. Never delete either to make a test pass. `.env`, runtime credentials and provider secrets remain untracked.

## Handoff log

- 2026-10-31 / Codex / 88e533b: committed existing product changes, pushed and fast-forwarded main; cut codex/media-ingestion. Recorded ingestion design, CSV examples, architecture and cost assumptions.
- 2026-10-31 / Codex / 676c203: implemented secure tenant project bootstrap and response redaction; media extraction/bulk workflow left pending. See docs/PROJECT_BOOTSTRAP_SECURITY.md for exact guarantees and limitations.
- 2026-10-31 / Codex / fc68e38: fixed localhost /index.html HTTP 500 caused by rebuilding a live JAR; isolated runtime packages, added real UI/API readiness and compatible installed Node selection. Rebuilt/restarted local demo and verified dashboard. Fix pushed on feature branch; not merged to main.
- 2026-10-31 / Codex / documentation handoff: established this shared state/plan plus mandatory AGENTS.md and CLAUDE.md entry points. Cross-checked Git state, implementation paths, detailed plan and existing test report totals; corrected stale test-plan introduction. Documentation only; no application tests rerun. Next agent should claim Stage 1.
- 2026-10-31 17:04 IST / Codex / user policy correction: user now requires testing and explicit approval before every push to any branch. Updated AGENTS.md, CLAUDE.md and this checkpoint; superseded earlier blanket push authorization. Validated all 9 local links in these files and git diff --check. Documentation only; no application tests run. These three files remain local and uncommitted; nothing pushed. Next implementation task is still Stage 1, subject to the new push gate.
- 2026-10-31 ~17:40 IST / Claude / tenancy review (read-only): validated Codex's uncommitted tenancy work (results in verification record). Open items for the tenancy owner: PROGRESS/docs (MULTITENANCY, DATA_MODEL, API_SPECIFICATION) not yet updated; project OWNER/ADMIN can edit tenant-wide defaults affecting sibling projects (decide); per-ad-break DB query for the VAST connector gate on the playback path; service tokens no longer carry `tid` (check consumers); `PUT /features` silently drops non-plan features; Team page lost friendly role labels; per-project suspend/owner reset no longer reachable from platform UI; no browser or existing-data migration check.
- 2026-10-31 18:00 IST / Claude / backend layout: user supplied a reference microservice structure as the required architecture. Added docs/BACKEND_ARCHITECTURE.md (target tree, layer rules, naming, deviations, current-to-target mapping, incremental migration rules) and pointed AGENTS.md, CLAUDE.md, MEDIA_INGESTION_PLAN.md, PROJECT_BOOTSTRAP_SECURITY.md and TECHNICAL_ARCHITECTURE.md at it. Documentation only; no Java moved; nothing committed or pushed. Open user decisions: keep `com.faststudio` root vs reference-style root; Cloudflare Queues vs RabbitMQ under `messaging/v1_0`. Next: Stage 1 uses the new layout; Codex's tenancy packages move after that work is finished and approved.
- 2026-10-31 19:00 IST / Claude / tenancy completion: took over Codex's in-progress tenancy redesign (Codex out of usage). Moved tenancy and project-bootstrap code into the v1_0 layout with comments (TenantAdminControllerV1_0, AccessRoleControllerV1_0, ProjectConfigurationControllerV1_0, MemberRoleControllerV1_0, Project/PlatformProjectDetailsControllerV1_0, TenantConfigurationServiceV1_0, AccessRoleServiceV1_0, ProjectDetailsServiceV1_0, repositories, dto). V15 makes premiere time, weekdays and personalized ads dynamic properties (migrated as project overrides; tenant_settings columns kept for rollback only). Tenant plan is the only channel limit. Fixes: tenant-wide defaults need tenant OWNER; features outside the plan return 422; connector gate cached 30 s per node; Team shows built-in role names/descriptions from the server; per-project suspend on the Tenants admin page. Service tokens omitting `tid` is correct (they belong to partner orgs). Verification: `mvnw test` 100 tests, 0 failures/errors (18 suites, incl. 10 TenantConfigurationTest); frontend `tsc --noEmit` passed; `git diff --check` clean. Not done: browser check, vite build, V13–V15 against existing demo/pilot data. Docs: MULTITENANCY §1b, DATA_MODEL, API_SPECIFICATION, BACKEND_ARCHITECTURE, new FRONTEND_ARCHITECTURE. Codex's replaced untracked files are backed up outside the repo in the session scratchpad (codex-tenancy-backup). Committed locally; nothing pushed.
