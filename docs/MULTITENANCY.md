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
- **Code.** `v1_0/controllers/platform/tenancy`, `v1_0/controllers/studio/{configuration,team,project}`, `v1_0/services/tenancy`, `v1_0/repositories/tenancy`, `v1_0/dto/tenancy` (see [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md)). Tests: `TenantConfigurationTest`.

Not done: billing/invoicing, tenant-level SSO, existing-data migration rehearsal on a production-like copy.

## 2. Five layers of isolation

| # | Layer | How | Status |
| --- | --- | --- | --- |
| 1 | **Tenant resolution** | Admin API paths are `/api/orgs/{orgSlug}/...`. `TenantInterceptor` (one class) loads the org and checks the **session user's membership**. A non-member gets **404**, so the tenant's existence isn't revealed. A suspended org gets **403** `ORG_SUSPENDED`. The resolved `Tenant(orgId, role, userId)` is injected into controllers; client-sent org IDs are never trusted. | ✅ |
| 2 | **Authorization** | `Tenant.Role` → `Perm` set, checked with `t.require(Perm.X)` in every mutating endpoint. The UI hides controls too, but that's only a convenience. | ✅ |
| 3 | **Query scoping** | `Data` (shared reads) takes `orgId` first on every method; there is no unscoped "find by id". Every `update`/`delete` includes `org_id = :o`. | ✅ |
| 4 | **Database constraints** | Composite unique `(org_id, id)` on tenant tables. Child tables reference parents with **composite foreign keys** `(org_id, x_id) → parent(org_id, id)`. Even a buggy query can't link tenant A's schedule to tenant B's video. | ✅ |
| 5 | **Row-level security** | Migration `V2` enables PostgreSQL RLS on every tenant table (policy `fs_tenant_ok(org_id)`). The app pool connects and immediately runs `SET ROLE faststudio_app` (no `BYPASSRLS`); `TenantAwareDataSource` stamps `app.org_id` from `TenantScope` on **every connection checkout**, so even an unfiltered `select * from asset` only returns the current tenant and cross-tenant inserts fail in PostgreSQL. Migrations use a separate owner pool. Explicit **bypass** exists only for trusted system paths: verified platform admins, demo seeding, media jobs (look up by id, then work as the asset's tenant), viewer-session lookup by unguessable id. `organization`, `membership`, `app_user` are outside RLS because they establish the tenant. | ✅ |

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
- **Offboard/purge**: export tenant data, stop channels, wait out the retention period, then delete rows and the `media/{orgSlug}` prefix. ⏳

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

## 7. Noisy-neighbour and fairness ⏳

- Per-tenant rate limits on the admin API and on playback-session creation.
- Media job queue picks work round-robin per org, so one tenant's 500-file import can't starve another tenant's single upload.
- Per-tenant storage and processing quotas, shown on the Billing & Usage page (F12).

## 7b. Organization IDs, sign-in tokens and partner organizations ✅

**Built (P1–P2):** `/api/orgs/{org}` takes the org UUID or slug and answers with `X-Org-Id`; log lines carry `[org=…]`; organizations have a kind (`CUSTOMER` / `PARTNER` / `PLATFORM`) and a `tenant_id`; platform staff act as the `_platform` organization. Sign-in uses 10-minute ES256 JWTs plus rotating refresh tokens, and the token must list the path's organization (6th check, before membership and RLS). Tests: `TokenTest`, `SessionModeTest`, and the matrix on UUID paths with Bearer tokens.

**Partners (P3–P4):** a `PARTNER` organization owns no content; of the tenant routes it can only use `/members`. A customer shares chosen channels and scopes (`partner_link`). `PartnerInterceptor` checks the caller (partner member or API client), an active sharing, the scope and the channel; anything else is 404. The read then runs as the owner under RLS in a read-only transaction, is audited in the owner's log with `via_org_id`, and is rate limited per partner. API clients' `service` tokens are refused outside `/api/partners/**`. Tests: `PartnerTest` and `CrossTenantMatrixTest.partnersSeeOnlyWhatIsShared` (every partner read endpoint × other customer / unshared channel / unshared scope).

Audit result: every route that touches customer data is scoped to one organization (46 tenant routes in the path, viewer sessions and signed media by binding). The plan adds stable org UUIDs in paths, an acting organization for platform and partner callers, **partner organizations** that see only what a customer shares (read-only, revocable, audited with both org IDs), and **JWT** access tokens carrying `sub`, `tid` (tenant), `org` (id, kind, role), `orgs`, `perms`, `pid` (project, reserved) and `ver` (instant revocation). Row-level security stays the final guard. Full design: [PARTNER_ACCESS_AND_TOKENS.md](PARTNER_ACCESS_AND_TOKENS.md).

## 8. Tests

**Automated (backend `./mvnw test`):**
- `CrossTenantMatrixTest` reads the live route table and probes **every** `/api/orgs/{org}` endpoint (45 today) twice as another tenant: on the victim's org path (must be 404) and on its own path with the victim's resource IDs (must never return victim data). It also checks the victim's data hash is unchanged. A new endpoint with an unmapped path variable fails the test, so coverage can't silently lapse. The whole app runs under RLS during this test.
- `RowLevelSecurityTest` proves the database layer on its own: unscoped queries see one tenant, no tenant sees nothing, cross-tenant writes are rejected, and pooled connections don't leak scope.

**End to end (`node tools/smoke-test.mjs`):**

27 checks against the running server, including tenant isolation (404/403/401), signed media URLs (unsigned, tampered, extended-expiry and other-tenant URLs are refused), and region blocking.
