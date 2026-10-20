# FAST Studio API specification

Proposed contracts, not implemented endpoints. Convert these to a reviewed OpenAPI document before R1 implementation. Retain the original resource families while adding commercial endpoints below.

## Common contract

Base path `/api/v1`. Tenant routes below are relative to `/organizations/{organizationId}` unless explicitly marked global. A path ID selects context; the authenticated session's membership and permission must authorize it. Never accept a client tenant ID as authority. OIDC login/logout endpoints use the identity integration rather than custom password storage by default.

Use secure same-origin session cookies and CSRF tokens for browser mutations. HTTPS outside local development. UUID IDs, RFC 3339 UTC instants, IANA channel time zones, integer duration milliseconds, ISO currency codes and decimal strings for money/rates. Reject unknown critical enum values. Limit request size, filters, export range and page size.

Collections return `{ "items": [], "nextCursor": null }`, with a default limit of 50 and maximum 200. Stable sort includes ID as tie-breaker. Clients cannot request unbounded records. Create returns 201 plus Location; jobs return 202 with operation URL; delete/archive returns documented state or 204.

Draft updates require `If-Match` with the last ETag. Missing required precondition => 428; stale revision => 412. Domain conflicts => 409. Validation/rights failure => 422 with machine-readable issue codes. Unauthorized => 401; unauthorized operation => 403; out-of-scope resource lookup => 404; throttled => 429 with retry hint. Errors use `application/problem+json` with `type`, `title`, `status`, `code`, `requestId`, and optional field issues; no secrets or stack traces.

Side-effecting publication, checkout, import, settlement approval and provider requests accept `Idempotency-Key`. Scope it to organization + actor/client + operation. Same key/body returns the recorded result; different body => 409. Keep keys for at least the documented retry horizon (provisional seven days); payment/invoice business uniqueness remains durable beyond this cache. Jobs are polled through `/operations/{id}` with ownership checks.

## Core endpoints

| Method and route | Contract / permission |
| --- | --- |
| Global `GET /auth/me` | Authenticated identity, authorized memberships and selected permissions; no secrets |
| Global `GET /organizations` | Only organizations the actor may access; platform administration is separate |
| `GET/POST /memberships` and `POST /invitations` | Read/invite with role ceiling, expiry and revocation; `users.read` / `users.invite` |
| `GET/POST /assets`, `GET/PATCH /assets/{id}` | Paginated metadata, typed update/ETag; `assets.read/create/update` |
| `POST /assets/{id}/upload-sessions` | Validated filename/size/checksum → short-lived signed upload parts; `assets.create` |
| `POST /assets/{id}/upload-sessions/{sessionId}/complete` | Verify stored object/checksum and queue processing; idempotent |
| `GET /assets/{id}/processing-jobs`, `POST /assets/{id}/archive` | Source/QC evidence; reject active dependencies |
| `POST /asset-imports/preview`, `POST /asset-imports` | Dry-run token/digest, mappings and row errors; accepted job on commit |
| `GET/POST /rights-policies`, `PATCH /rights-policies/{id}` | Versioned constraints, evidence and change reason; `rights.manage` |
| `POST /rights/evaluations` | Asset/channel/destination/interval → allow/deny/issues; resource scoped; arbitrary country allowed only in labeled simulation |
| `GET/POST /channels`, `GET/PATCH /channels/{id}` | Metadata, lifecycle and fallback; `channels.read/create/update` |
| `POST /channels/{id}/schedules`, `GET/PATCH /schedules/{id}` | Create/edit draft and entries with ETag; `schedules.manage` |
| `POST /schedules/{id}/validations` | Revision/digest → blocking issues, warnings, evaluation versions |
| `POST /schedules/{id}/approvals` | Exact revision, approve/reject/reason; independent reviewer from R2 |
| `POST /schedules/{id}/publications` | Validates approved revision atomically; returns publication/operation reference |
| `POST /channels/{id}/rollbacks` | Prior version + expected current revision → new validated publication |
| `GET /channels/{id}/epg?from=&to=&format=json` | Published schedule only, source version and generated time; XMLTV after schema/partner validation |
| `POST /channels/{id}/activations`, `POST /channels/{id}/stops` | Readiness/permission checks; provider operation with separate observed status |
| `GET/POST /destinations`, `POST /channels/{id}/distribution-publications` | Capability-checked provider config/publication; secret references only |
| `GET /alerts`, `POST /alerts/{id}/acknowledgments`, `POST /alerts/{id}/resolutions` | Owner/reason/evidence, distinct underlying fault state |
| `GET /audit-events`, `GET /reports/operations` | Tenant/date filters and appropriate read permission |

Playback bootstrap uses a separate `/playback/v1/authorizations` contract. It identifies the channel/destination/session context, derives trusted location from approved edge/backend context, evaluates current rights, and returns a short-lived playback credential or denial. It never trusts a submitted country as production location. Public viewer access does not require an admin session; use signed partner/session context, rate limits and isolated playback credentials. Do not expose admin APIs to viewers.

## Advertising and commercial endpoints

| Method and route | Important rules / permission |
| --- | --- |
| `GET/POST /advertisers`, `GET/POST /insertion-orders` | Commercial scope and currency; `ads.manage` |
| `POST /insertion-orders/{id}/line-items`, `PATCH /line-items/{id}` | Flight/goal/price/inventory rules; approval required before active delivery |
| `GET/POST /creatives`, `POST /creatives/{id}/approvals` | Asset/QC reference and independent approval; `ads.approve` |
| `GET/POST /channels/{id}/ad-pods` | Duration, offset, ownership and break mode; same schedule validation |
| `POST /inventory/reservations` | Atomic scoped reservation; 409 on conflicting capacity |
| `POST /ad-decisions/simulations` | Explainable demo result and `SIMULATED` provenance; never billed |
| Global `GET /plans` | Public/authorized offer definitions; immutable price version IDs |
| `GET /subscription`, `POST /subscription/checkout-sessions` | Server calculates allowed offer; hosted provider URL; `billing.manage` |
| `POST /subscription/changes`, `POST /subscription/cancellations` | Quote/version/effective date, impacts and idempotency; no browser payment confirmation |
| `GET /usage`, `GET /billing/estimates`, `GET /billing/invoices` | Units, cutoff, estimated/final status and tenant scope; `billing.read` |
| `POST /ad-report-imports`, `GET /ad-report-imports/{id}` | Provider/source/digest/version, period, provenance and job status; `finance.read` plus import authority |
| `GET /reconciliation-cases`, `POST /reconciliation-cases/{id}/decisions` | Source comparison, authority and reason; `finance.approve` |
| `GET/POST /revenue-contracts`, `POST /revenue-contracts/{id}/versions` | Effective-dated scope, basis, shares/deductions; commercial approval required |
| `POST /settlements/previews`, `POST /settlements` | Report/contract versions → draft; immutable source references |
| `POST /settlements/{id}/approvals`, `POST /settlements/{id}/adjustments` | Independent finance approval, decimal money, idempotency and reversal links |
| `GET /reports/channel-profitability`, `GET /reports/advertising` | Same-scope revenue/cost, freshness, currency and completeness flags |
| `POST /report-exports` | Permission/filter snapshot → expiring signed download; CSV formula-injection handling |

Webhook ingress is global `/webhooks/{provider}` and uses provider signature/authentication with raw request bytes where required. Map provider account/resource to the tenant on the server. Persist verified events before acknowledgement, then process idempotently. Do not trust organization IDs in callback bodies. Signed outbound customer webhooks, key rotation and replay logs are R4 work.

## Representative payloads

Schedule publication request (headers include `If-Match` and `Idempotency-Key`):

```json
{"expectedRevision":7,"effectiveAt":"2026-11-01T00:00:00Z","approvalId":"<uuid>"}
```

Publication response distinguishes internal publication from actual provider application:

```json
{"scheduleVersionId":"<uuid>","state":"PUBLISHED","deliveryState":"PENDING","operationId":"<uuid>","provenance":"SIMULATED"}
```

Financial report line:

```json
{"currency":"USD","billableImpressions":100000,"grossAmount":"1000.00","netAmount":"900.00","provenance":"RECONCILED","paymentState":"UNPAID","definitionVersion":"ad-metrics-v1","sourceReportId":"<uuid>","asOf":"2026-11-02T12:00:00Z"}
```

Release implementation must finish endpoint-specific schemas, required fields, enum/state transitions and negative-case examples. This document is a contract baseline, not a claim that complete machine-readable OpenAPI or provider certification exists.

## As built (prototype)

Admin API (sign-in token in the `fs_at` HttpOnly cookie + `X-XSRF-TOKEN`, or `Authorization: Bearer`; see [PARTNER_ACCESS_AND_TOKENS.md](PARTNER_ACCESS_AND_TOKENS.md)). Tenant routes are under `/api/orgs/{org}`, where `{org}` is the organization UUID or slug, and answers carry `X-Org-Id`:

| Area | Routes |
| --- | --- |
| Auth | `GET /api/auth/csrf`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`, `POST /api/auth/password` (change own), `POST /api/auth/reset` (public, from a link), `POST /api/auth/forgot` (public, emails a link; same answer for every address), `GET /api/auth/options` (public), `GET /api/auth/demo-accounts` (demo mode only). Tokens: `POST /api/auth/token` (JSON tokens for scripts), `POST /api/auth/token/refresh`, `POST /api/auth/token/switch`, `POST /api/auth/logout-all`, `GET /.well-known/jwks.json` |
| Home | `GET …/dashboard` |
| Library | `GET/POST …/assets`, `GET/PATCH …/assets/{id}`, `POST …/assets/{id}/upload` (multipart), `…/retry`, `…/archive` |
| Rights | `PUT …/assets/{id}/rights`, `POST …/rights/check` |
| Channels | `GET/POST …/channels`, `GET/PATCH …/channels/{id}`, `POST …/channels/{id}/go-live`, `…/stop`, `…/archive` |
| Schedule | `GET …/channels/{id}/schedule`; `POST …/schedule/autofill`, `…/schedule/entries`, `DELETE …/schedule/entries/{entryId}?expectedRevision=`, `POST …/schedule/close-gaps`, `…/clear`, `…/restore`, `…/publish` (all carry `expectedRevision`; stale → 412) |
| EPG | `GET …/channels/{id}/epg?from=&hours=`, `GET …/channels/{id}/epg.xml` (XMLTV) |
| Ads | `GET/POST …/campaigns`, `PUT …/campaigns/{id}`, `POST …/campaigns/{id}/status`, `POST …/ads/simulate` |
| Distribution | `GET/POST …/destinations`, `POST …/channels/{id}/distribute/{destinationId}` |
| Ops | `GET …/alerts?filter=`, `POST …/alerts/{id}/acknowledge`, `…/resolve`, `POST …/alerts/scan`, `GET …/audit`, `GET …/analytics` |
| Team | `GET/POST …/members` (invite emails the person), `PATCH/DELETE …/members/{userId}`, `POST …/members/{userId}/password-reset` (emails a one-time link) |
| Platform | `GET/POST /api/platform/orgs`, `POST /api/platform/orgs/{id}/status`, `POST /api/platform/password-reset` |
| Partners (customer) | `GET …/partners/directory`, `GET/POST …/partners`, `PUT …/partners/{linkId}`, `POST …/partners/{linkId}/revoke` |
| Partner API | `GET /api/partners/{partner}/links`, `POST …/links/{linkId}/accept`, `GET …/sources/{owner}/channels`, `…/channels/{channelId}`, `…/epg`, `…/epg.xml`, `…/stream`, `…/breaks`, `…/delivery`; API clients `GET/POST …/clients`, `POST …/clients/{id}/revoke`; `POST /oauth/token` (client credentials). OpenAPI: `/partner-api.openapi.json` |
| Email (platform admins) | `GET /api/platform/email/status`, `GET /api/platform/email/templates`, `PUT/DELETE …/templates/{key}` (save / back to default), `POST …/templates/{key}/preview`, `POST /api/platform/email/test`, `GET /api/platform/email/messages`, `GET …/messages/{id}` (secrets hidden), `POST …/messages/{id}/retry` |
| Tools | `POST /api/tools/scte35/decode`, `GET /api/tools/scte35/example` |

Public viewer API (no admin session): `POST /play/sessions {org, channel, destinationId?, country}` (country is a **labelled simulation**), `GET /play/{session}/live.m3u8`, `GET /play/{session}/ad/{breakId}/{slot}/{n}.ts` (SSAI segment + server beacon), `GET /play/{session}/asset-list/{breakId}.json` (SGAI), `GET /play/{session}/slate/{seconds}.m3u8`, `POST /play/{session}/beacons`, `GET /play/{session}/info`. Media: `GET /media/{orgSlug}/{assetId}/…`.
