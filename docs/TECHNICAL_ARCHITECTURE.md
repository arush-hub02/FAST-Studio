# FAST Studio technical architecture

Proposed architecture — 19 October 2026. There is no application code in the inspected workspace. This replaces the original Node.js backend suggestion with the user's Java requirement.

## Stack decision

| Layer | Proposed choice | Reason and boundary |
| --- | --- | --- |
| Admin frontend | Angular + TypeScript provisionally; React + TypeScript supported alternative | Original brief favors Angular. Select one at R0 from team experience; do not build duplicate applications |
| Java API | Spring Boot, Spring MVC, Spring Security, Bean Validation | Typed APIs, transactional domain services, established authorization support |
| Java runtime/build | Java 21 as conservative provisional baseline; evaluate Java 25 with existing services and SDKs; Maven wrapper | Pin a supported compatible release at kickoff; no production version is selected by this document |
| Persistence | PostgreSQL; Spring Data JPA; Flyway migrations | Transactional schedules, tenant relationships and financial integrity |
| Media files | S3-compatible object storage; signed multipart uploads | Keep video bytes outside the API/database |
| Async work | PostgreSQL job/outbox tables with bounded worker pool and durable leases | Sufficient initial durability without a message broker |
| Auth | Existing organizational OIDC provider; secure server session | Reuse identity and avoid browser-stored bearer credentials |
| API contract | Versioned REST, OpenAPI, generated TypeScript client once contract stabilizes | Same contracts for either frontend |
| Observability | Structured logs, Spring Actuator metrics, traces and provider correlation IDs | Debug real customer/provider failures |
| Test approach | JUnit, Spring integration tests against PostgreSQL, one browser E2E runner | Verify tenant, schedule, rights and billing behavior |

Official [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) confirm Java compatibility for the current release. Verify the actual chosen Boot line, Java runtime, identity client, ORM and media/payment SDKs together before pinning. Avoid unverified "latest" version numbers in the implementation brief.

Angular implementation: feature routes, typed reactive forms and established Angular HTTP/error handling. React alternative: feature routes, accessible typed forms, a server-state library if needed and the project's chosen component system. Both keep business invariants in Java, use generated API types, and provide identical permissions, loading/error states and schedule behavior. No SSR is required for an authenticated administration tool unless a concrete requirement appears.

## As built (prototype, 20 Oct 2026)

| Layer | Built with |
| --- | --- |
| Frontend | React 19, TypeScript, Vite 7, react-router 7, hls.js 1.7 (interstitials). Built into `backend/src/main/resources/static` and served by Spring. |
| Backend | Java 21, Spring Boot 4.0 (MVC, Security, JDBC `JdbcClient`, Validation), Flyway |
| Database | Default PostgreSQL 14 via zonky embedded-postgres (data in `data/pg`); optional `start.bat -Docker` uses `postgres:17-alpine` and named volume `faststudio_pgdata`; `DB_URL` can select an external server |
| Media | ffmpeg (npm `ffmpeg-static`): ABR ladder **360p / 540p / 720p** H.264 Main + AAC in one pass, **3 s segments with forced keyframes** (identical cadence across renditions), `master.m3u8` per asset; generated demo media via lavfi. Served only through HMAC-signed expiring URLs (`MediaSigner`, `MediaController`). |
| Playout | `PlayoutService`: `/play/{s}/live.m3u8` is a master playlist; `/play/{s}/v/{0..2}.m3u8` are per-rendition live playlists with identical media/discontinuity sequences. Segment *i* covers `[EPOCH + 3s·i, +3s)`, so `MEDIA-SEQUENCE = i`. Markers per destination profile; SGAI breaks announced 30 s ahead. |
| Tenancy in the database | Migration `V2`: RLS policies on every tenant table; requests log in as `faststudio_app_login` and cross-tenant system work as `faststudio_system_login` (V18, no session switch); `TenantAwareDataSource` stamps the tenant on each connection checkout; migrations use an owner pool (`@FlywayDataSource`). See MULTITENANCY.md. |
| Ads | `AdDecisionService` (local ADS stand-in) → SSAI segments served through `/play/{s}/ad/...` (server beacons) or an SGAI asset list (client beacons) |

Current module packages: `core` (auth, tenancy, data, audit), `catalog`, `rights`, `channels`, `schedule`, `playout`, `ads`, `distribution`, `ops`, `team`, `media`, `demo`. Target layout and incremental migration rules: [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md). Multitenancy details: [MULTITENANCY.md](MULTITENANCY.md). Media path explained: [HOW_FAST_WORKS.md](HOW_FAST_WORKS.md).

## Logical topology

Admin browser → same-origin reverse proxy → Java API/session → domain modules → PostgreSQL and object storage.

Java workers → media QC/transcode, playout, SSAI/ad server, destination, payment and report-import adapters.

Approved media output → provider origin/CDN → authorized viewer player or partner platform.

Provider callbacks → authenticated webhook ingress → durable inbox → background handler → domain state/audit/outbox.

These are logical boundaries. One API deployment and one worker process from the same Java codebase are sufficient initially. Video streaming, encoding and per-viewer ad stitching run in the media provider path; they are not proxied through Spring MVC.

## Module boundaries

| Module | Owns |
| --- | --- |
| identity / tenancy | Memberships, permissions, authenticated tenant context |
| catalog | Assets, renditions, processing jobs, QC and captions |
| rights | Effective policy, advertising permission, revocation and eligibility explanation |
| channels / schedules | Lifecycle, versioned timeline, validation, approval, EPG |
| advertising | Advertisers, orders, line items, creatives, pods, inventory reservations |
| distribution | Destinations, provider bindings, publication attempts and capabilities |
| billing | Plans, price versions, subscriptions, usage ledger and platform invoices |
| finance | Ad report reconciliation, revenue contracts, statements and adjustments |
| operations / reporting | Alerts, observed events, metric provenance and materialized reports |

Keep module calls explicit through application services; do not let controllers mutate another module's tables directly. Add separate deployables only after measured scaling or isolation needs.

## Key consistency decisions

- Every tenant-owned key and relationship carries organization scope. Authorization occurs before lookup or action; database constraints prevent cross-tenant references.
- Schedule drafts use a numeric revision/ETag. Publication validates the exact immutable snapshot, approved rights and rendition readiness, then atomically updates the channel's published pointer and writes an outbox job. Provider acceptance remains asynchronous and separate.
- An in-flight publisher cannot silently overwrite another publisher. Serialize per-channel publication using a transaction/lock or expected pointer revision. Display control-plane published version and provider-applied version separately.
- New rights revocation triggers affected schedule revalidation, provider takedown/replacement work and playback-token invalidation strategy. Never depend only on the original schedule check.
- Jobs and callbacks are at-least-once. Unique source IDs, inbox/outbox records and idempotent handlers prevent duplicate invoices, shares and publication requests. Retry transient errors with bounded backoff; dead letters are visible and replayable by authorized staff.
- Lock usage counters/reservations atomically. Financial amounts use Java `BigDecimal` and database decimal/minor-unit values with explicit currency; never JavaScript floating point as calculation authority.
- Keep provider timestamps and received timestamps. Events arriving out of order cannot roll payment/subscription state backward; reconcile with provider authority.
- Report aggregation is asynchronous. Define freshness and coverage; do not lock schedule editing while a finance report runs.

## Initial nonfunctional acceptance budgets

Proposed pilot test envelope: 10 channels per tenant, 100,000 catalog metadata records, 14 days of schedule data, and 20 concurrent operator sessions per tenant. This is a benchmark target, not demonstrated capacity.

- p95 ordinary paginated metadata reads <=500 ms server-side at the agreed envelope; excludes uploads, exports and external provider execution.
- p95 validation of one channel's 24-hour schedule <=2 seconds with local rights/readiness data. Measure query counts and avoid per-entry provider calls.
- Return asynchronous job acceptance promptly; use signed uploads and cursor pagination. Index tenant/status/time queries; add a reporting store only after measuring load.
- Provisional R3 control-plane objective: 99.9% monthly availability, restore-point objective <=24 hours and restore-time objective <=4 hours. Verify restore evidence and negotiate actual commitments separately. Stream continuity has independent provider and end-to-end objectives.
- Daily database backups and object-retention policy, isolated environments, migration rollback/forward recovery and tested restoration before paid pilot.
- Store schedule instants in UTC, channel time zones as IANA identifiers, and duration/offset in integral milliseconds. Preserve rational frame-rate/timecode metadata where needed by provider contracts; never equate rounded UI seconds to frame-accurate media boundaries.

## Build versus integrate

Build tenant workflows, scheduling constraints, rights policy, commercial orchestration, reporting and reconciliation. Integrate identity, media QC/transcoding, channel assembly/playout, CDN, SSAI/ad demand and payment collection. [AWS MediaTailor documentation](https://docs.aws.amazon.com/mediatailor/latest/ug/what-is.html) provides one candidate for channel assembly and ad insertion; it is not a selected vendor or a confirmed fit for all destination requirements.

Do not add Kubernetes, Kafka, Elasticsearch, a data lake or a custom real-time bidding service at prototype stage. Add a cache/queue only when the measured need and ownership are clear.
