# Organization IDs on every API, partner organizations, and sign-in tokens (JWT)

Revision 3 — 20 October 2026. **Status:** phases **P1 to P4 are built** (✅): stable organization IDs, organization kinds, the platform organization, JWT sign-in tokens, partner organizations with minimal read-only access, and partner API clients with OAuth 2.0 client credentials. Evidence: 51 backend tests, including `TokenTest` (11), `PartnerTest` (8), `SessionModeTest`, and a cross-tenant matrix on UUID paths with real Bearer tokens that also checks every partner endpoint. Projects and company single sign-on (P5) are still to come. Section 1 is the audit of every route; §3.1 is a worked example.

## 1. Does every API carry an organization ID? (audit of all 78 routes)

| Route family | Routes | Organization in the request? | How the organization is decided today | Verdict |
| --- | --- | --- | --- | --- |
| Tenant admin API `/api/orgs/{org}/…` (library, rights, channels, schedule, EPG, ads, distribution, monitoring, analytics, team) | 46 | **Yes**, in the path (the org's **slug**, e.g. `aurora`) | `TenantInterceptor` looks up the slug **and** the signed-in user's membership. Non-members get 404 and suspended orgs 403. The database's row-level security then only shows that org's rows. | ✅ Org-scoped. Change: also accept the org's **UUID**, which never changes (§1.1). |
| Platform admin API `/api/platform/…` (organizations, email, password resets) | 13 | Only `POST /api/platform/orgs/{id}/status` | Platform administrators only. Cross-tenant **by design**: it shows counts, not customer content. | ⚠️ No *acting* organization. Change: platform staff act as the **platform organization** (§2), so their tokens and audit entries carry an org ID too. |
| Account API `/api/auth/…` (sign-in, me, password, reset, forgot) | 8 | No | Belongs to a *person*, not a company. `/api/auth/me` returns all their organizations. | ✅ Correct as is. The token (§4) carries the person's organizations. |
| Tools `/api/tools/scte35/…` | 2 | No | Stateless marker encoder/decoder; reads no customer data | ✅ Correct as is. |
| Viewer playback `/play/…` (public) | 8 | `POST /play/sessions` names the org; later calls use the session ID | Each viewer session row is bound to one org, one channel and one destination | ✅ Org-scoped through the session. |
| Signed media `/media/s/…` | 1 | Yes, the org folder is part of the **signed** address | An HMAC signature covers the org folder, so an address can't be reused for another org | ✅ Org-scoped. |

**Answer:** every API that touches customer data already requires an organization, and the server checks it on every request. Three routes plus database security enforce this; the client is never trusted. The routes without an org ID are either about the person (sign-in) or deliberately cross-tenant (platform admin). Two things are missing for partners: a *stable* org ID in paths, and an *acting* organization for platform and partner callers.

### 1.1 Changes so every call carries a stable organization ID — ✅ built (P1)

1. ✅ `/api/orgs/{org}` accepts the org **UUID** as well as the slug, so integrations can use the ID that never changes. A versioned `/api/v1/orgs/{orgId}/…` with UUID only comes with the OpenAPI document (P4). Slugs stay for human-readable web addresses.
2. ✅ Every tenant response carries `X-Org-Id: <uuid>`. Every log line of a request carries `[org=<uuid>]` (`logging.pattern.correlation`). Every audit row of company work or platform administration names the acting organization. Personal account events (sign-in, password) belong to the person and have none. Metrics come with structured logging later.
3. ✅ Platform staff act as the **platform organization**: `kind = PLATFORM`, slug `_platform` (customer slugs can't start with `_`). It appears in their token and on their audit rows, never in customer lists.
4. ✅ Partner calls name **both** organizations: the partner's own (acting org) and the content owner whose channel it reads: `/api/partners/{partner}/sources/{owner}/…` (each an ID or a short name).

## 2. Organization kinds — ✅ built (P1, partner roles P3)

| Kind | Who | Example | Default access |
| --- | --- | --- | --- |
| `CUSTOMER` | A content owner running FAST channels (today every org) | Aurora Media Network | Full product for its own data, by role |
| `PARTNER` | A company that **receives** channels: a TV platform, an app, an ad partner, an agency | "a smart-TV channel app" | **Nothing**, until a customer shares something (§3) |
| `PLATFORM` | Us, the operator of FAST Studio | FAST Studio operations | Platform admin pages: counts, email, resets. No customer content. |

Partner people get **partner roles only** and never a membership in a customer org:

| Partner role | Can |
| --- | --- |
| `PARTNER_ADMIN` | Manage the partner's own people and API keys; read everything shared with the partner |
| `PARTNER_VIEWER` | Read what is shared: channel details, EPG, stream addresses, delivery reports |

## 3. Partner organizations with minimal access — ✅ built (P3)

### 3.1 Use case, with an example

Most FAST viewers watch inside **other companies' apps**: smart-TV channel guides, streaming services, car and airline screens. Those platforms need four things from a content owner, automatically: the **programme guide**, a way to **start the stream** with their own ad set-up, the **ad-break times** (SCTE-35) if they sell ads, and **viewing numbers** for revenue share. Without partner organizations this means spreadsheets, shared passwords or a custom feed per partner. With them, each platform gets its own small account, reads exactly what the contract allows, and the owner sees every read and can stop at any time.

**Example (in the demo data):** *Aurora Media* signs a 3-month pilot with *Partner TV*, a fictional smart-TV channel app.
1. The platform admin creates *Partner TV* as a `PARTNER` organization; its admin is Sam Lee (`partner@partnertv.test`).
2. Aurora shares *Aurora Action* and *Classic Cinema* with `channels:read epg:read stream:read markers:read`, using its destination *Partner TV (simulated)*: markers only (CUE-OUT/CUE-IN), and Partner TV sells the ads. Sam is emailed.
3. Sam accepts. Partner TV's developer creates an API client; its systems pull `…/epg.xml` every hour into the TV guide, start one `POST /play/sessions` per viewer, and read `…/breaks` so its ad server prepares ads for each SCTE-35 break.
4. Every read appears in Aurora's Activity log (*Sam Lee (Partner TV)*, *Partner TV (API)*), and the Partners page counts reads per week.
5. For the full contract Aurora adds `delivery:read`: viewers and ads **through Partner TV's destination only**.
6. The pilot ends: Aurora presses *Stop sharing*, and Partner TV's next request answers 404.

Partner TV never sees Aurora's library, licences, campaigns, money, team, audit log, other channels, or any other company. Illustrated walkthrough: PRODUCT_GUIDE.md chapter 24.

### 3.2 Design (as built)

**Endpoints.** Customer side: `GET /api/orgs/{org}/partners/directory`, `GET|POST /api/orgs/{org}/partners`, `PUT /api/orgs/{org}/partners/{linkId}`, `POST …/{linkId}/revoke`. Sharing needs `DISTRIBUTION_EDIT`, and partner admins are emailed (template *partner_shared*). Partner side: `GET /api/partners/{partner}/links`, `POST …/links/{linkId}/accept` (partner admin), and read-only `…/sources/{owner}/channels[/{channelId}[/epg|/epg.xml|/stream|/breaks|/delivery]]`. API clients: `GET|POST /api/partners/{partner}/clients`, `POST …/clients/{id}/revoke`. Plus `POST /oauth/token`. Reference: `frontend/public/partner-api.openapi.json`, served at `/partner-api.openapi.json`.

**Rate limit:** 120 requests a minute per partner organization (`faststudio.partners.rate-limit-per-minute`); above that, 429.

**Principle:** a partner sees only what a customer explicitly shares, read-only, and the customer can stop sharing at any time.

**Sharing record** (`partner_link`): content owner org → partner org, a list of channels (or "all live channels"), a set of **scopes**, a status (`INVITED → ACTIVE → REVOKED`), who created it, who accepted it, and an optional end date. The customer creates it on a new **Partners** page under *Earn & deliver*. The partner accepts it. A revocation takes effect on the next request.

| Scope | What the partner gets | What it never gets |
| --- | --- | --- |
| `channels:read` | Name, logo, description, genre, territories of the shared channels | Unshared channels |
| `epg:read` | Programme guide and XMLTV for those channels | Rights contracts, licence terms |
| `stream:read` | The playback address for **their** destination (ad mode and marker format as agreed) | Other destinations, internal media files |
| `markers:read` | The ad-break schedule (SCTE-35 times and durations) | Campaigns, advertisers, prices |
| `delivery:read` | Aggregated viewing and ad-delivery numbers for their destination | Viewer-level data, revenue of other partners |

The partner **never** sees the video library, uploads, rights, campaigns, money, team members or the audit log. There are no write scopes.

**How it is enforced (same layered approach as today):**

1. The token says *who* is calling (partner org and role) (§4).
2. A `PartnerInterceptor` loads the `partner_link` for (partner org from the token, owner org from the path). It checks status, scope and channel. Anything else gives 404, so the owner's other channels are not revealed.
3. The request runs as the **owner org** in a **read-only database transaction**, so it cannot change anything even if a bug tries.
4. Responses are fixed shapes (`PartnerChannel`, `PartnerEpg` …), never the internal objects, so new internal fields can't leak.
5. Every partner read is audited with **both** org IDs. The customer sees these reads in their Activity log.
6. Machine access: a partner admin creates an **API client**. The secret is shown once and stored as a hash. The client signs in with OAuth2 *client credentials* and gets a short-lived token of type `service` with only the shared scopes.
7. Distribution ties in: a destination can be linked to a partner org, so *Send to platform* creates or updates the sharing record.

## 4. Sign-in tokens (JWT) carrying who the user is — ✅ built (P2)

**Before:** a server-side session cookie plus a CSRF token. The session held only the user ID, and other services and partners couldn't read it.

**Now:** a signed **access token** (JWT) describes the person and their organizations, and a **refresh token** keeps them signed in. `faststudio.auth.mode=session` (env `FASTSTUDIO_AUTH_MODE`) switches back to server sessions for one release.

| Item | Decision |
| --- | --- |
| Signature | ES256 with rotating keys (`kid`), kept in `<data folder>/keys/jwt-signing-keys.json`. That file holds the private key, so it travels with backups and is never in git. The newest key signs. A new key is made after 90 days (`faststudio.auth.key-rotation-days`), and the previous one stays published so its tokens still verify. Public keys at `/.well-known/jwks.json`. |
| Access token lifetime | 10 minutes |
| Refresh token | 256-bit random value, stored only as a SHA-256 hash (like reset links). A new one on every use. 12 h idle, 7 days maximum. If an old one is used again more than 30 s after it was replaced, the whole sign-in is revoked (it was copied). Within 30 s, two tabs refreshing at once is allowed. |
| Browser | `fs_at` (access, `Path=/`, 10 min) and `fs_rt` (refresh, `Path=/api/auth` only), both `HttpOnly; SameSite=Lax`, plus `Secure` behind HTTPS (`FASTSTUDIO_SECURE_COOKIES=true`). Never in `localStorage`, which protects against script theft. The CSRF token stays for browser calls. The web app renews the access token on a 401 (one shared request) and retries. |
| API clients and partners | `Authorization: Bearer <token>`. Calls that carry no cookies skip CSRF, because they can't be forged by another website. |
| Revocation | `ver` claim = the person's `token_version`. It is bumped when the password changes, a role changes, the person is removed or an org is suspended, and on "sign out everywhere". Checked on each request (cached 30 s), so these take effect immediately rather than after 10 minutes. |
| Size | Kept under 4 KB. With more than 20 organizations, `orgs` is left out (`orgs_truncated: true`) and the client reads `/api/auth/me`. |

### 4.1 Claims

```json
{
  "iss": "https://studio.example.com",
  "aud": "faststudio-api",
  "sub": "6f1c…-user-uuid",
  "typ": "user",
  "sid": "b42e…-sign-in-session",
  "jti": "0d9a…-token-id",
  "iat": 1792500000, "nbf": 1792500000, "exp": 1792500600,
  "name": "Asha Rao",
  "email": "owner@aurora.test",
  "amr": ["pwd"],
  "pwd_change_required": false,
  "platform_roles": [],
  "tid": "2a7f…-tenant-uuid",
  "org": { "id": "2a7f…-org-uuid", "slug": "aurora", "kind": "CUSTOMER", "role": "OWNER" },
  "perms": ["VIEW", "ASSETS_EDIT", "RIGHTS_EDIT", "CHANNELS_EDIT", "SCHEDULE_EDIT", "SCHEDULE_PUBLISH", "ADS_EDIT", "DISTRIBUTION_EDIT", "ALERTS_EDIT", "TEAM_MANAGE"],
  "orgs": [
    { "id": "2a7f…", "slug": "aurora", "kind": "CUSTOMER", "role": "OWNER" },
    { "id": "91c0…", "slug": "northwind", "kind": "CUSTOMER", "role": "ANALYST" }
  ],
  "projects": [],
  "ver": 7
}
```

| Claim | Plain meaning |
| --- | --- |
| `iss`, `aud` | Who issued the token and which API it is for. Tokens for anything else are refused. |
| `sub` | The person's permanent ID (or the API client's ID when `typ` = `service`). |
| `typ` | `user` (a person) or `service` (a partner's machine, with `scope` instead of `perms`) |
| `sid`, `jti` | This sign-in and this exact token, for sign-out and tracing |
| `iat`, `nbf`, `exp` | Issued at, not valid before, expires at |
| `name`, `email` | Shown in the UI; never used for authorization |
| `amr` | How the person proved who they are: password now; single sign-on and 2-step later |
| `pwd_change_required` | `true` after a temporary password. Such a token is accepted only by `/api/auth/*`. |
| `platform_roles` | e.g. `["PLATFORM_ADMIN"]` for our staff; empty for customers and partners |
| `tid` | **Tenant**: the contract or billing account. Today one organization is one tenant, so `tid` equals the org ID. Later a media group can hold several organizations under one tenant. |
| `org` | **Active organization**: ID, slug, kind (`CUSTOMER` / `PARTNER` / `PLATFORM`) and the person's role in it |
| `perms` | What that role allows in the active org (the same list the UI uses today) |
| `orgs` | Every organization the person belongs to, with their role in each, for the organization menu |
| `projects` | **Projects** the person can use in the active org: groups of channels with their own roles, e.g. "India bundle". It is an empty list until projects exist (P5), and is present now so clients don't have to change. |
| `ver` | Token version, for immediate revocation (see above) |

**Rules:** the path decides the organization, and the token must agree. A call to `/api/orgs/{orgId}/…` gives 404 unless the org is in `orgs` (and the person is still a member in the database). Database row-level security stays as the final guard, stamped from the same org ID. The token never adds a permission the database row doesn't have, because it is minted from the same data.

### 4.2 Endpoints

| Endpoint | Purpose |
| --- | --- |
| `POST /api/auth/login` | Browser sign-in: sets the `fs_at` and `fs_rt` cookies and returns the person |
| `POST /api/auth/token` | Scripts: email + password (or a single-sign-on code later) → JSON `access_token`, `refresh_token`, `expires_in`, `org` (no cookies) |
| `POST /api/auth/token/refresh` | New access token; rotates the refresh token. Uses the cookie, or `{"refresh_token": …}` in the body. |
| `POST /api/auth/token/switch {"org": id or slug}` | Makes another of the person's orgs active (new token, remembered on refresh). The web app calls it when you change organization. |
| `POST /api/auth/logout` / `…/logout-all` | Revokes this sign-in / every sign-in (bumps `ver`) |
| `POST /oauth/token` ✅ (P4) | Partner API clients: `client_credentials` (form fields or HTTP Basic) → 10-minute `service` token (`typ` service, `org` = the partner, `scope`, `client_name`). It works only under `/api/partners/**` and stops at once when the client is revoked. |
| `GET /.well-known/jwks.json` | Public keys, so partners and our own services can verify tokens |

Built with Spring Security's resource server (`JwtDecoder` with issuer, audience, expiry, type and version checks) and a `NimbusJwtEncoder` (`core/Tokens.java`, `core/TokenKeys.java`). `TenantInterceptor` reads the org from the path, checks it against the token and the database, and stamps `TenantScope` as before. Session sign-in stays available for one release (`faststudio.auth.mode=session`).

## 5. Data model changes (migrations V5 and V6 ✅)

| Change | Why |
| --- | --- |
| `organization.kind` (`CUSTOMER` default, `PARTNER`, `PLATFORM`) | Organization kinds |
| `organization.tenant_id` (defaults to its own ID) | `tid` claim; several orgs per tenant later |
| `app_user.token_version int` | Immediate revocation (`ver`) |
| `refresh_token(token_hash, family_id, user_id, active_org_id, family_started_at, created_at, expires_at, replaced_at, revoked_at)` ✅ | Refresh rotation and reuse detection |
| platform organization row (`_platform`) ✅ | Acting org of platform staff |
| `partner_link(id, owner_org_id, partner_org_id, all_channels, scopes, destination_id, status, note, created_by, accepted_by, revoked_by, expires_at, …)` + `partner_link_channel(link_id, org_id, channel_id)` ✅ (V6) | What is shared with whom. At most one open sharing per pair. Channels use composite foreign keys, so only the owner's own channels can be shared. |
| `api_client(id, org_id, name, secret_hash, scopes, created_by, last_used_at, revoked_at)` ✅ (V6) | Partner machine access (secret `fss_…`, 256 random bits, stored as SHA-256) |
| `audit_event.via_org_id` ✅ (V6) | Partner reads are recorded in the owner's log together with the partner organization |
| `membership.role` adds `PARTNER_ADMIN`, `PARTNER_VIEWER` ✅ (V6) | Partner roles, only in partner organizations |
| `project`, `project_member` (later) | `projects` claim |

## 6. Delivery plan

| Phase | Work | Size | Done when |
| --- | --- | --- | --- |
| P1 ✅ | UUID in paths, `X-Org-Id`, org ID in every log/audit line, `organization.kind`/`tenant_id`, platform org, `token_version` | 2–3 days | The cross-tenant matrix passes with UUID paths; audit rows always have an org |
| P2 ✅ | JWT access + refresh tokens (browser cookies and Bearer), JWKS, switch org, sign out everywhere | 4–5 days | Tests for claims, expiry, refresh reuse, `ver` bump, wrong-org path, suspended org, `pwd_change_required` |
| P3 ✅ | Partner orgs: sharing records, scopes, read-only partner API, Partners page (customer) and a minimal partner portal | 5–7 days | Matrix extended: a partner reads only shared channels and scopes, cannot write, revocation is immediate |
| P4 ✅ | Partner API clients (`client_credentials`), per-partner rate limits, OpenAPI document | 2–3 days | A partner script fetches EPG with only its API client |
| P5 | Projects, customer single sign-on (OIDC token exchange), per-organization email templates | later | — |
