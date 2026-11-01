# Multitenancy design

Revision 3.2 — 20 October 2026. **Status:** items marked ✅ are implemented and covered by automated tests (`CrossTenantMatrixTest`, `RowLevelSecurityTest`, `MediaSignerTest`, `tools/smoke-test.mjs`). Items marked ⏳ are designed but not built.

## 1. Model in one paragraph

FAST Studio uses **one shared database schema**. Every customer company is an `organization` (tenant). Every row a tenant owns carries `org_id`. A person (`app_user`) can belong to several organizations through `membership`, with a different role in each. Platform staff are flagged `platform_admin` and do **not** get membership in customer organizations by default.

This is the cheapest model to run and migrate, and it scales to thousands of small tenants. The trade-off is that isolation depends on getting every query right. So the design puts that check in one place and has the database enforce it as well.

## 1b. Tenant → project hierarchy (implemented locally 2026-10-31, migrations V13–V15)

The earlier model treated every `organization` as a tenant, which made partner organizations tenants too. It is now:

```text
Platform (global definitions: subscription plans, connector definitions, custom property definitions + global defaults, custom roles)
└── Customer tenant  (customer_tenant: subscription plan/status, connector grants, tenant property values)
    └── Project       (organization kind=CUSTOMER, tenant_id set: content, channels, members, project overrides, connector opt-in/out)
Partner organization  (organization kind=PARTNER, tenant_id null: no content; sees only what projects share with it)
```

- **Organization types.** `organization.kind` is CUSTOMER (a project of a tenant) or PARTNER. A database check enforces `kind = CUSTOMER ⇔ tenant_id is not null`. Existing customer organization IDs became both the tenant ID and the first project ID, so content foreign keys, RLS, public links and partner sharing are unchanged.
- **RLS stays project-scoped.** `app.org_id` is still the project. Tenant tables (`tenant_property_value`, `tenant_connector`) are readable only while operating a project of that tenant (`fs_customer_tenant_ok`). The tenant-wide channel count uses a narrow `security definer` aggregate so sibling projects' channel rows stay invisible.
- **Subscription plans** (`subscription_plan`) set max projects and max channels **across the tenant**, plus the feature keys the tenant's projects may switch on. The plan is the only channel limit; `organization.max_channels` is legacy and no longer enforced. Payment collection is not connected.
- **Connectors.** Platform defines connectors (`connector_definition`: MUX, VAST, R2 or CUSTOM driver). Platform grants each tenant a connector (`tenant_connector`); each project may opt out (`project_connector`). Mux sync, the media library bootstrap and VAST ad decisions check `driverEnabled(project, driver)` (cached 30 s per node). CUSTOM connectors store configuration only until an adapter exists.
- **Dynamic properties.** Platform defines typed properties (`property_definition`: STRING/NUMBER/BOOLEAN/SELECT, optional connector binding, secret flag, project-override flag). Values resolve **project override → tenant value → global default**. Secrets are sealed at rest and never returned. Since V15 the built-in settings `programming.premiere_time`, `programming.weekdays` and `ads.personalized` are properties too (with their domain validation); existing values were migrated as project overrides.
- **Who edits what.** Platform admin: everything, including secrets and tenant connector grants. Tenant OWNER (owner of any project in the tenant): tenant-wide values. Project OWNER/ADMIN (with TEAM_MANAGE): project overrides and connector opt-in/out. Members: read resolved values.
- **Roles.** Both organization types have built-in roles plus platform-defined custom roles (`access_role`, per type). A custom role picks a built-in ceiling and may only remove permissions; a disabled custom role falls back to VIEW only.
- **Tokens.** User tokens carry `tid` only when the active organization is a customer project; partner and platform identities have no tenant.
- **Code.** `v1/controllers/platform/tenancy`, `v1/controllers/studio/{configuration,team,project}`, `v1/services/tenancy`, `v1/repositories/tenancy`, `v1/dto/tenancy` (see [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md)). Tests: `TenantConfigurationTest`.

Not done: billing/invoicing, tenant-level SSO, existing-data migration rehearsal on a production-like copy.

## 2. Five layers of isolation

| # | Layer | How | Status |
| --- | --- | --- | --- |
| 1 | **Tenant resolution** | Admin API paths are `/api/orgs/{orgSlug}/...`. `TenantInterceptor` (one class) loads the org and checks the **session user's membership**. A non-member gets **404**, so the tenant's existence isn't revealed. A suspended org gets **403** `ORG_SUSPENDED`. The resolved `Tenant(orgId, role, userId)` is injected into controllers; client-sent org IDs are never trusted. | ✅ |
| 2 | **Authorization** | `Tenant.Role` → `Perm` set, checked with `t.require(Perm.X)` in every mutating endpoint. The UI hides controls too, but that's only a convenience. | ✅ |
| 3 | **Query scoping** | `Data` (shared reads) takes `orgId` first on every method; there is no unscoped "find by id". Every statement on a tenant table names the organization (`org_id`, `owner_org_id`, `partner_org_id` or `tenant_id`), or carries a `/* all tenants: reason */` comment for deliberate system work. The CI guard `tools/check-tenant-filters.mjs` enforces this for every table under RLS (2026-11-01). | ✅ |
| 4 | **Database constraints** | Composite unique `(org_id, id)` on tenant tables. Child tables reference parents with **composite foreign keys** `(org_id, x_id) → parent(org_id, id)`. Even a buggy query can't link tenant A's schedule to tenant B's video. | ✅ |
| 5 | **Row-level security** | PostgreSQL RLS on every table that carries an organization (policy `fs_tenant_ok`, migrations V2–V18). **Three logins** (V18, `DatabaseLogins`): requests use `faststudio_app_login` (member of `faststudio_app` only, no `BYPASSRLS`, not the owner, cannot `SET ROLE` to anything else); `TenantAwareDataSource` stamps `app.org_id` on every checkout, so an unfiltered `select * from asset` returns only the current tenant and cross-tenant writes fail in PostgreSQL. Cross-tenant **system work** (`TenantScope.bypass`: verified platform admins, media jobs, alert scans, edge sync, client authentication) gets a connection from a separate pool that logs in as `faststudio_system_login`; the policies trust that *login* (`session_user`), which a request connection can't become. The old `app.bypass` session switch is gone. Migrations use the owner pool, which also maintains the two logins (passwords derived from the secrets key). Outside RLS by design: `organization`, `membership` (they establish the tenant), `personal_access_token` (belongs to a person), `email_message` (platform outbox). `RowLevelSecurityTest` fails if any other table with an organization column lacks a policy. | ✅ |

## 3. Tenant-scoped resources beyond tables

| Resource | Scoping | Status |
| --- | --- | --- |
| Media files | `data/media/{orgSlug}/{assetId}/…`; uploads in `data/uploads/{orgId}/{assetId}/` with server-chosen file names. Served **only** via HMAC-signed, expiring URLs `/media/s/{exp}/{sig}/{orgSlug}/{assetId}/…`; the signature covers the folder, so it can't be reused for another tenant, extended, or forged. Set `faststudio.media-secret` in production (otherwise a random key per run). | ✅ |
| Playback sessions | Public `/play/**`. A session row carries `org_id` and is bound to one org's channel and destination. Channel lookup is `org slug + channel slug`. | ✅ |
| Jobs | The media job re-reads the asset with its org; no tenant context is held in memory between jobs | ✅ |
| Ad decisions cache | Keyed by session (which is tenant-bound) | ✅ |
| Alerts, audit | `org_id` on every row; the scanner loops per org | ✅ |
| Platform view | `/api/platform/orgs` returns **counts only**: no content, schedules or money | ✅ |
| Logs / metrics | Include `orgId` in structured logs and metric tags | ⏳ |

## 4. Tenant lifecycle

```
PROVISIONED ──► ACTIVE ◄──► SUSPENDED ──► (OFFBOARDING ──► PURGED)   ⏳ last two
```

- **Create** (platform admin): creates the org, its first OWNER and a default "Web player" destination. ✅
- **Suspend**: blocks the customer's **admin access** (403 on every `/api/orgs/{org}`). **Data is kept, and channels already on air keep streaming**, so viewers and partners aren't cut off by an account issue. A separate, deliberate "stop all channels" action belongs to the service-suspension runbook, not to a billing callback. ✅
- **Export** (platform admin, 2026-11-01): `GET /api/platform/tenants/{tenant}/export` returns every row the tenant owns as JSON, table by table (tables found from the database catalog, so new tables are included), plus its projects and members. Credentials, hashes and unredacted mail are left out; video files are not included. ✅
- **Delete** (platform admin, 2026-11-01): `POST /api/platform/tenants/{tenant}/delete` with `{"confirm": "<exact tenant name>"}`, only while the tenant is suspended. In one transaction: channels taken off the edge (final ARCHIVED snapshot: open players show the standby slate), nullable references between its tables cleared, rows deleted children-first, people who belonged only to this tenant anonymized (their rows stay because other audit records may name them), projects and tenant removed, local media/upload folders deleted. Audited on the platform. Not yet: copies already on R2 (edge storage) and a retention waiting period before purge. ✅ (R2 cleanup ⏳)

## 5. Roles and the role ceiling

| Role | Can |
| --- | --- |
| OWNER | Everything, including managing owners |
| ADMIN | Everything except managing owners |
| CONTENT_MANAGER | Videos, cue points, licences |
| CHANNEL_OPERATOR | Channels, schedules, publish, go live, distribution, alerts |
| AD_OPS | Campaigns, ad decisions, alerts |
| ANALYST | Read only |

Rules (✅):

- Nobody can grant or change a role ranked higher than their own.
- Only owners manage owners.
- Every org keeps at least one owner.
- Users can't remove themselves.

## 6. Per-tenant configuration (what may differ between customers)

| Setting | Where | Status |
| --- | --- | --- |
| Country, time zone, plan label, channel limit (`max_channels`) | `organization` | ✅ (limit enforced on channel create) |
| Channel territories, ad limit per hour, break length | `channel` | ✅ |
| Destinations: ad mode SSAI/SGAI/pass-through, marker format, ad-load limit | `destination` | ✅ |
| Ad server / VAST endpoint, CDN, partner credentials | `provider_binding` with **secret references only** | ⏳ |
| Branding / white-label, SSO (SAML/OIDC per tenant), SCIM | — | ⏳ (F29/F31) |

## 7. Noisy-neighbour and fairness (partly built, 2026-11-01)

- ✅ **Per-project limits** (V19), ordinary dynamic properties (global default → tenant → project override, set by platform administrators; 0 = unlimited): `limits.api_requests_per_minute` (default 600; 429 `RATE_LIMITED`; counted per server in memory, so N servers allow N × the limit), `limits.media_hours` (default 500; checked before uploads, generated samples, direct uploads and library sync; 409 `MEDIA_LIMIT`), `limits.processing_videos` (default 20 converting at once; 409 `PROCESSING_LIMIT`). Values are cached 60 s per project and server. Test: `TenantLimitsTest`.
- ✅ **Tenant-first indexes** on the growing event tables (V20): `ad_event`, `playback_session`, `ad_decision` lead with `org_id`.
- ⏳ Media job queue picks work round-robin per org (Stage 2, [MEDIA_JOBS_DESIGN.md](MEDIA_JOBS_DESIGN.md)).
- ⏳ Rate limits on playback-session creation; exact global API limits (Cloudflare rate limiting or a shared counter); usage shown to customers (F12).
- ⏳ Monthly partitions of the event tables: not done, deliberately. `ad_event` and `ad_decision` reference `playback_session` by `(org_id, id)`; partitioning would need the partition key in those keys. Worth doing once volumes are measured.
- ⏳ A dedicated database for one tenant: only when a customer contract requires it (requests are already routed by the tenant in the token).

## 7b. Organization IDs, sign-in tokens and partner organizations ✅

**Built (P1–P2):** `/api/orgs/{org}` takes the org UUID or slug and answers with `X-Org-Id`; log lines carry `[org=…]`; organizations have a kind (`CUSTOMER` / `PARTNER` / `PLATFORM`) and a `tenant_id`; platform staff act as the `_platform` organization. Sign-in uses 10-minute ES256 JWTs plus rotating refresh tokens, and the token must list the path's organization (6th check, before membership and RLS). Tests: `TokenTest`, `SessionModeTest`, and the matrix on UUID paths with Bearer tokens.

**Partners (P3–P4):** a `PARTNER` organization owns no content; of the tenant routes it can only use `/members`. A customer shares chosen channels and scopes (`partner_link`). `PartnerInterceptor` checks the caller (partner member or API client), an active sharing, the scope and the channel; anything else is 404. The read then runs as the owner under RLS in a read-only transaction, is audited in the owner's log with `via_org_id`, and is rate limited per partner. API clients' `service` tokens are refused outside `/api/partners/**`. Tests: `PartnerTest` and `CrossTenantMatrixTest.partnersSeeOnlyWhatIsShared` (every partner read endpoint × other customer / unshared channel / unshared scope).

Audit result: every route that touches customer data is scoped to one organization (46 tenant routes in the path, viewer sessions and signed media by binding). The plan adds stable org UUIDs in paths, an acting organization for platform and partner callers, **partner organizations** that see only what a customer shares (read-only, revocable, audited with both org IDs), and **JWT** access tokens carrying `sub`, `tid` (tenant), `org` (id, kind, role), `orgs`, `perms`, `pid` (project, reserved) and `ver` (instant revocation). Row-level security stays the final guard. Full design: [PARTNER_ACCESS_AND_TOKENS.md](PARTNER_ACCESS_AND_TOKENS.md).

## 8. Tests

**Automated (backend `./mvnw test`):**
- `CrossTenantMatrixTest` reads the live route table and probes **every** `/api/orgs/{org}` endpoint (45 today) twice as another tenant: on the victim's org path (must be 404) and on its own path with the victim's resource IDs (must never return victim data). It also checks the victim's data hash is unchanged. A new endpoint with an unmapped path variable fails the test, so coverage can't silently lapse. The whole app runs under RLS during this test.
- `RowLevelSecurityTest` proves the database layer on its own with the production logins: unscoped queries see one tenant, no tenant sees nothing, cross-tenant writes are rejected, pooled connections don't leak scope, a request connection can't reach system or owner rights (`set role`, `reset role`, the old `app.bypass` switch), and every table with an organization column has RLS and a policy.
- `TenantLimitsTest` (per-project API, media-hour and conversion limits) and `TenantDataTest` (export, then delete one tenant leaving another untouched).
- CI guard `tools/check-tenant-filters.mjs`: SQL on tenant tables must name the organization.

**End to end (`node tools/smoke-test.mjs`):**

27 checks against the running server, including tenant isolation (404/403/401), signed media URLs (unsigned, tampered, extended-expiry and other-tenant URLs are refused), and region blocking.
