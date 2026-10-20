# FAST Studio implementation roadmap

## Status update — 20 October 2026

**R1 working prototype: built.** It runs locally with one command and goes from content → rights → schedule → publish → live HLS playout with SCTE-35 → SSAI/SGAI ads → distribution checks → monitoring/analytics → audit. It has two tenants and seven demo roles. Evidence: 11 backend unit tests plus `tools/smoke-test.mjs` (18 end-to-end checks).

Deviations from the plan below, with reasons:

| Planned | Built instead | Why / when to revisit |
| --- | --- | --- |
| Angular (provisional) | React 19 + TypeScript + Vite | The plan allowed either; React was faster to build accessible custom timeline/player UI. API and rules are frontend-agnostic. |
| Spring Data JPA | Spring `JdbcClient` + Flyway SQL | Explicit tenant-scoped SQL is easier to review for isolation. Revisit if the domain model grows. |
| OIDC dev provider | Local accounts (bcrypt, session cookie, CSRF, login throttling), clearly marked demo | No identity provider was available. Swap in OIDC before any pilot (R2). |
| Playout simulator only | Real virtual-linear HLS playout + local SSAI/SGAI | Needed to make the product understandable and honestly "working". Still not broadcast-grade; R3 keeps the provider path. |
| Durable job/outbox tables | In-process 2-thread job pool; jobs resume on restart | Enough for the demo. Durable queue at R2 with real uploads. |

**Pilot hardening done (20 Oct 2026):** PostgreSQL row-level security on all tenant tables (restricted app role + per-connection tenant stamp); HMAC-signed, expiring media URLs; a cross-tenant test matrix generated from the route table (45 endpoints); a 360p/540p/720p ABR ladder with master playlists for VOD and live. Evidence: 20 backend tests and 27 end-to-end smoke checks.

**Real-data pilot mode done (20 Oct 2026):** `start.bat -Pilot` / `./start.sh --pilot` runs a `pilot` profile that has no demo content or accounts and keeps its data in `data-pilot/`. It refuses to start without a stable media secret of 32+ characters, which the launcher generates once. The first platform admin is created from `FASTSTUDIO_ADMIN_EMAIL`/`FASTSTUDIO_ADMIN_PASSWORD`. Account lifecycle: every temporary password (bootstrap, invite, new owner) must be changed at first sign-in, and the API enforces this. Users can change their own password. Admins create one-time, 24-hour, hashed reset links; an org admin cannot reset someone who also has access elsewhere. Password rules: 10+ characters, not containing the email name, not trivial. `tools/backup.ps1` makes cold backups and restores, keeping a safety copy; a restore was tested on a live pilot. The smoke test detects pilot mode. Evidence: 25 backend tests (incl. `PilotModeTest`), 27 demo + 9 pilot smoke checks.

**Email done (20 Oct 2026):** an outbox table plus **nodemailer** (`mailer/send.mjs`) sends invitations, new-owner welcomes, reset links, password-changed notices and new-alert emails. It uses SMTP when `FASTSTUDIO_SMTP_URL` is set, or writes .eml files otherwise. Platform admins edit all 8 templates with a live preview and a check for required or unknown values, send a test email, and see a sent-email log with secrets hidden. **Forgot your password?** works for every account in every mode (neutral answer, 3 per hour, demo accounts excluded). Evidence: `MailTest` (5 tests, real nodemailer), 30 backend tests in total, and a 12-check SMTP run against a local test mail server.

**Organization IDs and sign-in tokens done (20 Oct 2026, phases P1–P2):** org UUID or slug in every tenant path, `X-Org-Id`, org-tagged logs, organization kinds and tenants, a platform organization for platform staff, and JWT sign-in. The token carries user, tenant, active org (kind and role), all orgs, permissions, projects (empty for now) and a version for instant revocation. 10-minute ES256 access tokens and rotating refresh tokens with replay detection, as HttpOnly cookies for the browser or Bearer for scripts, with JWKS. Evidence: 42 backend tests, including `TokenTest` and the matrix on UUID paths with real tokens.

**Partner organizations done (20 Oct 2026, phases P3–P4):** customers share chosen channels and permissions with partner organizations (TV platforms, apps, ad partners): channel details, guide/XMLTV, stream set-up with the partner's own delivery settings, ad-break times with SCTE-35, viewing numbers for the partner's destination only. Partners accept in a small portal; their systems use API clients with OAuth 2.0 client credentials. Access is read-only, revocable at once, audited in the owner's log, and rate limited. An OpenAPI document is included, and the demo has a partner (*Partner TV*). Evidence: 51 backend tests, including `PartnerTest` and a partner isolation matrix over every partner endpoint.

**Planned next:** projects and company single sign-on (P5), per-company email wording. See [PARTNER_ACCESS_AND_TOKENS.md](PARTNER_ACCESS_AND_TOKENS.md).

**Added after the SoFast review (20 Oct 2026):** F45–F54 and extensions to F02/F15, F05, F11, F18/F21, F22, F28, F33 and F35. Details and sources are in [Product requirements](PRODUCT_REQUIREMENTS.md#competitive-review-sofast). They are placed in R2–R4 below. Recommended order, because these decide whether a real platform accepts a channel:
1. Platform-readiness check (F46), loudness (F47) and captions (F02/F15)
2. Library readiness report (F50) and guide enrichment (F49)
3. Platform delivery packs (F45) for the first real destination
4. On-screen graphics (F48)
5. Live input (F33), then restream (F51)

**Still needed for a pilot:** OIDC sign-in; CDN token auth; the creative-approval workflow (F08 remainder); bulk import (F14); a durable job queue; structured logs/metrics tagged with org; an accessibility audit (WCAG 2.2 AA) of the timeline and player.

Planning baseline: 19 October 2026. Status: all releases unstarted; discovery inspection and this plan are complete, but R0 customer/provider validation remains open.

## Delivery assumptions

Use one React or Angular TypeScript application and a Java/Spring Boot modular monolith. Angular is the reversible default from the original brief; the plan supports React with the same API and domain rules. Start with PostgreSQL, object storage and durable background jobs. Integrate media delivery and ad insertion through providers.

Indicative elapsed ranges assume two frontend engineers, two Java engineers, one QA engineer, a shared product/UX lead, and a media/ad-tech specialist available during R2–R3. Work can overlap within a release. Ranges include integration and hardening work, but exclude unpredictable partner procurement/certification delays. A smaller team must re-estimate. These are planning estimates, not delivery commitments.

| Release | Indicative duration / cumulative time | Main outcome | Feature scope | Accountable lead |
| --- | --- | --- | --- | --- |
| R0: validate and design | 1–2 weeks / 1–2 | Confirm pilot buyer, framework, provider path and commercial hypothesis | Discovery for F01–F25 | Product lead + technical lead |
| R1: leadership prototype | 6–8 weeks / 7–10 | Demonstrate content → rights → schedule → EPG → preview → ad/distribution simulation | F01–F10 | Engineering lead |
| R2: paid SaaS pilot | 6–8 weeks / 13–18 | Onboard real catalog and collect/reconcile first platform subscription | F11–F16; F46, F47, F49, F50, captions (F02/F15); harden F01–F10 | Engineering + billing lead |
| R3: monetized channel pilot | 8–12 weeks / 21–30 | One working channel/provider/destination/ad path with financial evidence | F17–F25; F45, F48, F54; F26 only if contracted | Media lead + ad-ops + finance |
| R4: expansion tranche | 8–12 weeks per selected tranche | Expand proven revenue paths and customer segments | Select F27–F38 and F51–F53 by demand; not all in one tranche | Product + commercial lead |

## R0 — confirm the smallest sellable outcome

1. Interview five content owners/operators and identify two prospective pilot customers. Record catalog size, territories, operating effort, current costs, ad inventory ownership and purchase authority.
2. Choose React or Angular from actual team capability; use Angular provisionally. Confirm Java LTS/runtime, Spring Boot support and provider SDK compatibility before pinning versions.
3. Inventory reusable Enveu identity, storage, media processing, player, analytics and billing services. No such code is present in this workspace.
4. Obtain technical access and commercial requirements for one playout/SSAI path and one destination. Prepare a configurable simulator if access is delayed.
5. Define pilot coverage, broadcaster responsibilities, billing currency, billable meters, inventory split, measurement authority and settlement ownership.
6. Produce the API contract, rights rules, seed scenarios and first-slice acceptance cases from this document pack.

**Exit evidence:** named pilot owners, selected frontend, approved Java approach, provider feasibility checklist, agreed pilot scope and a reviewed backlog. Customer/provider access is a gate to paid rollout, not a reason to stop local prototype work.

## R1 — working product narrative

| Slice | Work and dependencies | Demonstrable exit |
| --- | --- | --- |
| R1.1 foundation | F01; schema migrations, sessions, memberships, roles, app shell, tenant-aware repositories, audit and seed data | Two tenants; cross-tenant reads/writes fail; restart preserves state |
| R1.2 content and rights | F02–F04; asset metadata/upload, rights evaluator, channel wizard, eligible fallback | Asset can be prepared and assigned; unavailable rights block use with reasons |
| R1.3 schedule and guide | F05–F07; draft editing, templates, conflict checks, publication, EPG, preview | Publish a valid 24-hour lineup; reject overlap/expiry; EPG matches version; stale edits fail |
| R1.4 commercial demo | F08–F10; approved creatives, ad pods, campaign eligibility, destination simulator, alerts, report provenance | Show a filled pod, unfilled fallback, failed publication, resolution and labeled simulated revenue |

Rights validation belongs before schedule publication. A prototype must never publish an invalid schedule first and postpone rights checking to a later milestone. Derive simulated metrics from deterministic fixtures/events rather than random dashboard numbers.

**Exit evidence:** repeatable clean-start demo, persistent data, complete keyboard workflow, permission checks, critical business tests, setup instructions and implemented/simulated/unavailable feature matrix. No claims of a live continuous broadcast or collected ad revenue.

## R2 — first paying platform customer

- F11: versioned plans and contracts, trial/active/past-due/canceled subscriptions, backend feature and quota enforcement.
- F12: authoritative tenant usage events, replay-safe aggregation, allowances and overage estimates. Alert before limits; prevent new paid resources without unexpectedly stopping existing broadcasts.
- F13: one hosted billing provider or invoice adapter, real settlement confirmation, signed webhook deduplication, credits, cancellations and payment-failure recovery. Test mode and live mode remain separate.
- F14–F15: resumable upload, bulk import preview, media QC evidence and real processing results for pilot assets.
- F16: approval separation, optimistic concurrency, activity history and production-safe identities/secrets.
- F46–F47 and captions (F02/F15): platform-readiness report per target platform, loudness normalization at upload, caption upload with HLS subtitle renditions. Gate: a pilot channel passes the report for its chosen platform.
- F49–F50: guide enrichment with a human confirmation step for uncertain matches, and the library readiness report. Gate: a pilot catalog shows how many channels it can fill and what is missing.
- Operational baseline: deployment rollback, restore test, monitoring, support owner, tenant exports and documented retention policy.

**Dependencies:** billing-provider availability, legal entity/currency choices, commercial agreement, real media processing access and R1 workflow correctness.

**Exit evidence:** pilot user loads a catalog; signed-in billing owner sees the same usage as the invoice; payment/invoice settlement is reconciled; replay does not double-bill; downgrade and delinquency policies pass; no cross-tenant access. A sandbox checkout alone does not satisfy the commercial gate. If payment rails are unavailable, use an agreed invoice workflow with independently verified payment and clearly record the adapter limitation.

## R3 — earn and explain actual advertising revenue

| Workstream | Requirements | Launch gate |
| --- | --- | --- |
| Stream and partner delivery | F17; playout adapter, eligible rendition, published EPG, signed authorization, one destination | Agreed 72-hour initial soak, stream continuity checks, rollback and rights revocation drill; partner approval recorded |
| Platform delivery and look | F45, F48, F54; delivery pack (stream, XMLTV/JSON guide, MRSS) for the chosen destination, logo and "Up next" graphics, CDN with token auth, DRM if the destination requires it | Destination accepts the pack in its test or certification; graphics pass its safe-area rules |
| Ads and direct sales | F18–F20; orders/line items, duration reservations, inventory ownership, pacing, pods, creative approval, frequency limits | Provider test matrix passes including malformed tags, timeout, missing markers, underfill and slate; actual approved test insertion |
| Measurement and privacy | F21/F24; authoritative report ingestion, provenance, consent restrictions, invalid-traffic adjustments, authorized sellers | No billing from ad decisions; no duplicate accepted impressions; restricted context produces permitted fallback |
| Finance and margin | F22–F23; contract versions, adjustments, statements, channel costs and approvals | One report period reconciled to partner statement; disputed deltas visible; shares balance; finance approves export |
| Operations | F25; stream probes, provider lag, ad errors, incident response, restore and fallback drills | Runbooks exercised, on-call owner assigned, initial service objectives measured |

Start the 72-hour soak only after representative programme transitions and ad breaks are configured. Extend it when defects or insufficient coverage invalidate the evidence. This soak is an initial pilot gate, not proof of an annual availability SLA.

**Commercial gate:** two pilots review actual subscription and channel economics and renew or commit to continue. Prove demand and manageable operating cost before broad destination or ad-provider expansion. Real ad cash settlement may lag the technical pilot by the partner's payment cycle; record outstanding receivables rather than calling them cash received.

## R4 — select expansion by evidence

1. **Yield and regional growth:** F27–F28 after sufficient comparable impressions, a second demand contract or a signed new-territory customer. Compare net revenue per watch hour alongside retention and errors.
2. **Enterprise and partner sales:** F29–F32 when a qualified contract requires SSO/SCIM, APIs, reseller access or managed services. Price incremental support and infrastructure.
3. **Format expansion:** F33–F34 after live/catch-up rights, media capability and a paying use case are verified.
4. **AI and commerce experiments:** F35–F37 after approved source content/catalog, human review, cost limits and a measured experiment goal. Build manual sponsorships before automated product detection.
5. **Premium resilience:** F38 after measured failover/restore behavior and a costed service commitment.
6. **Live, formats and languages:** F51 (restream) after a paying broadcaster with an existing linear channel; F52 when a destination refuses HLS/H.264; F53 when a customer needs the interface in their language.

Each selected tranche gets its own capacity estimate, acceptance criteria and commercial success test. Do not bundle all expansion ideas into a promised release.

## Dependency order and critical path

Identity/tenant boundaries → ready media + rights → schedule validation → immutable publication/EPG → playout/destination approval → ad insertion + inventory rights → measured impressions → reconciliation → statements → paid settlement.

Billing plans → durable usage → versioned price calculation → invoice/payment reconciliation is a parallel SaaS revenue path from R2. Finance and ad reporting share references and audit conventions, but keep their ledgers and obligations separate.

Critical external risks: media/SSAI access, destination acceptance, demand agreements, rights clearance and payment availability. Product must show provider readiness and blocked dependencies explicitly. Prototype simulators keep discovery moving; they cannot satisfy production gates.

## First engineering sprint after planning

Create a single frontend and Java service; add PostgreSQL migrations, two fictional organizations, OIDC development integration and server-enforced memberships. Deliver asset metadata + rights + channel draft persisted through the API. Cover cross-tenant rejection and rights expiry with integration tests. Publish setup instructions and capture a working demo. Do not begin all modules or add media-scale infrastructure in the first sprint.

## Explicit deferrals

Defer a proprietary SSAI engine, RTB exchange, TV/mobile app suite, recommendation engine, microservices, Kafka, Kubernetes, marketplace payouts and autonomous AI publishing. Revisit each only when a paying requirement or measured scaling constraint justifies it.

Not adopted from the SoFast review: a TV launcher, cloud gaming, hotel TV, set-top box platforms, satellite distribution, a licensed content catalog, an in-house ad-sales team and 24/7 human master control. These are separate businesses, not channel-operations software; ad sales and master control return only as F32 managed services if a customer pays for them.
