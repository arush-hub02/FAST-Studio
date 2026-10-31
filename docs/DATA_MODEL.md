# FAST Studio data model

Proposed relational model. Retain the original organization's memberships, media, channel, schedule, rights, campaign, destination, events and audit concepts; extend them as follows. No migrations exist yet.

## Shared conventions

Use UUID identifiers, `organization_id`, UTC creation/update timestamps, actor, revision and lifecycle status on tenant records where applicable. Use composite tenant/resource foreign keys to prevent cross-tenant references even when IDs are globally unique. Platform-global plan definitions and provider capabilities are explicitly separate from tenant-owned data. Financial records are retained/corrected rather than cascade-deleted.

| Aggregate | Important fields and relationships |
| --- | --- |
| Organization / Membership | Organization defaults/status; unique organization/user membership, identity-provider subject, assigned role/permissions |
| MediaAsset / Rendition | Type, external ID/checksum, duration in ms, language, series/episode, object key, processing/QC state; rendition codec/tracks/profile |
| MediaJob / ImportBatch | Source, job key, stage, retries, lease/status, findings; dry-run/commit rows and errors |
| RightsPolicy / Assignment | Effective interval, asset/channel/destination scope, territory/platform/language, ad permission, DRM, version, evidence reference |
| Channel | Time zone, output profile, permitted destinations, fallback reference, lifecycle, current published schedule ID |
| ScheduleVersion / Entry | Draft revision, immutable published snapshot, approval/publication timestamps; asset, start/end, type, break mode, source template |
| ScheduleApproval | Snapshot digest/revision, submitter, reviewer, decision/reason, invalidation record |
| AdPod / PodSlot | Channel/schedule entry, offset, duration, ownership, content/ad mode, creative assignments and provider cue reference |
| Advertiser / InsertionOrder | Advertiser account/contact, external reference, dates, currency, commercial status |
| AdLineItem | Order, flight, goal/budget, pricing model, unit rate, channel/daypart/territory, priority/pacing and exclusions |
| Creative / Approval | Asset/provider reference, duration/profile, advertiser/category, QC, approval actor/time, expiry |
| InventoryReservation | Owner, channel/destination/daypart/date, reserved duration, contract/line item, status/revision |
| Destination / ProviderBinding | Capabilities, environment, external account/channel IDs and secret references only |
| DistributionPublication | Schedule/version, destination, idempotency key, attempts, provider job ID, desired/applied/observed status |
| Plan / PlanPriceVersion | Global offer, currency, validity, base/allowance/meter rates and immutable version |
| Subscription / Entitlement | Tenant, plan version, provider customer/subscription refs, lifecycle/effective dates; feature limits and override audit |
| UsageEvent / UsageAggregate | Source event/key, meter/unit/quantity, occurrence and ingestion times, period, adjustment reference; derived period totals |
| PlatformInvoice / InvoiceLine | Provider invoice reference, period, currency, amounts, meter/rate evidence, credits and payment status |
| AdReportImport / AdReportRow | Provider/account/environment, source report ID/checksum/version, period, dimension keys, measured/billable counts, amount/currency and provenance |
| RevenueContract / ContractVersion | Parties, destination/territory scope, effective interval, inventory allocation, split basis, allowed deductions, shares, currency/rounding/holdback |
| ReconciliationCase | Compared source rows, authoritative source, discrepancy, review status/actor and accepted amounts |
| SettlementStatement / Entry | Contract version, report references, gross/deductions/net/shares, approval, append-only adjustment/reversal references |
| Receivable / PaymentEvidence | Debtor, statement/invoice reference, due/received amount, currency, external evidence and applied allocation |
| ChannelCost | Channel, period, category, allocation rule, source, amount/currency and estimated/actual flag |
| OperationalEvent / Alert | Resource, severity, observed/simulated provenance, occurrence/receipt, owner, acknowledgment and resolution evidence |
| AuditEvent | Tenant, actor, action, resource, outcome, sanitized change, request correlation, time |
| InboxEvent / OutboxJob | Provider event/idempotency scope, payload reference/checksum, status, retry/lease/dead-letter and processing timestamps |

## Invariants

1. Schedule entries have positive duration and do not overlap within the selected published linear timeline. Adjacent half-open intervals are valid. Entries and associated ad pods fit the declared replacement/additive break model.
2. Channel published pointer references a published schedule belonging to that channel and organization. Approval must reference the exact revision. EPG is derived; exported snapshots retain source version/digest.
3. Missing, expired or conflicting rights cannot be treated as allowed. Published snapshots retain the rights evaluation version for audit, while current enforcement uses current revocations.
4. Every money amount has currency. Unit price precision and rounding are defined; invoice/statement totals are rounded once at agreed boundaries. Never combine currencies without an explicit dated FX source and conversion record.
5. Revenue shares equal 100% of the contract's stated distributable basis or explicitly identify an unallocated holdback. Each settlement line traces to source rows and contract version. Issued entries cannot be edited in place.
6. Unique `(provider, account, environment, event_id)` prevents callback replay effects. Usage uniqueness includes organization, meter and source key. Report import also handles corrected report versions through reversal/replacement, not blind append.
7. A billing invoice contains platform charges. Publisher ad invoices and royalty statements have distinct record types and identifiers. Customer funds/pass-through amounts never silently inflate SaaS MRR.
8. Inventory reservations use transactional conflict checks within the owning scope; audience forecasts remain estimates, not reserved impressions.
9. Tenant archival respects retention and live-resource dependencies. Media removal is blocked while referenced by published/active schedules; archive and schedule replacement precede physical purge.

## Indexes and reporting

Start with tenant-prefixed indexes for asset status/title/external ID, channel status, schedule channel/version/start, rights assignments/effective interval, alert status/time and financial period/currency. Unique constraints enforce membership, channel slug, source usage/event IDs and provider invoice references. Use normalized contract/report records rather than arbitrary JSON for commercial calculations; JSON is acceptable for versioned provider payload references or nonauthoritative metadata.

Use cursor pagination and date-bounded reports. Partition usage/ad observations only after measured volume justifies it. Reporting aggregates carry source watermark and definition version. Raw provider payloads can be encrypted in object storage with governed retention; do not retain unnecessary viewer identifiers.

## As built (prototype, `V1__schema.sql`)

Tables: `organization`, `app_user`, `membership`, `asset` (incl. `cue_points`, `media_key`), `rights_policy` (one per asset), `channel` (territories, `ad_seconds_per_hour`, `break_seconds`, fallback, published pointer), `schedule_version` (number 0 = draft, revision for optimistic locking), `schedule_entry` (`PROGRAM` | `AD_BREAK`, `asset_offset_ms`, `ownership` PROVIDER/DISTRIBUTOR), `campaign`, `destination` (ad mode, marker format, ad-load limit), `publication`, `playback_session`, `ad_event` (unique per session/break/campaign/event, so replays never double count), `alert` (partial unique index = one open alert per condition), `audit_event`.

Later migrations: `V2` row-level security; `V3` `app_user.must_change_password`/`password_changed_at` and `password_reset` (SHA-256 of single-use, 24-hour tokens); `V4` `email_template` (platform-wide overrides of built-in templates) and `email_message` (outbox and sent log; the real content, which can hold one-time secrets, is cleared once delivered). `V5`: `organization.kind`/`tenant_id`, platform organization, `app_user.token_version`, `refresh_token`. `V6`: partner roles, `partner_link` + `partner_link_channel`, `api_client`, `audit_event.via_org_id` ([PARTNER_ACCESS_AND_TOKENS.md](PARTNER_ACCESS_AND_TOKENS.md)).

All tenant tables use composite `(org_id, id)` keys and composite foreign keys (see [MULTITENANCY.md](MULTITENANCY.md)). Not yet built from the model above: renditions/jobs as tables, approvals, insertion orders/line items, reservations, billing and finance records, inbox/outbox.

## Tenant hierarchy tables (V13–V15, local, 2026-10-31)

Full model and rules: [MULTITENANCY.md §1b](MULTITENANCY.md).

| Table | Scope | Purpose |
| --- | --- | --- |
| `customer_tenant` | platform | Customer account: `slug`, `name`, `status` (ACTIVE/SUSPENDED), `plan_key`, `subscription_status` (TRIAL/ACTIVE/PAUSED/CANCELLED), `revision` for optimistic locking |
| `organization.tenant_id` | — | Owning tenant of a CUSTOMER project; null for PARTNER/PLATFORM (check constraint) |
| `subscription_plan` | global | `max_projects`, `max_channels` (tenant-wide), `monthly_price`, `currency`, comma-separated `features` |
| `connector_definition` | global | `key`, `category`, `driver` (MUX/VAST/R2/CUSTOM, immutable), `enabled` |
| `tenant_connector` | tenant (RLS via `fs_customer_tenant_ok`) | Connector granted to a tenant |
| `project_connector` | project (RLS) | Project opt-in/out; no row = inherit the tenant grant |
| `property_definition` | global | Typed custom property, `options` (JSON), `default_value` (JSON, sealed if secret), `project_override`, `revision` |
| `tenant_property_value` | tenant (RLS) | Tenant value (JSON text, sealed if secret) |
| `project_property_value` | project (RLS) | Project override (JSON text, sealed if secret) |
| `access_role` | global | Custom role per organization `kind`, with a built-in `base_role` ceiling and allowed `permissions` |
| `membership.custom_role_key` | — | Optional custom role; `membership.role` holds its ceiling |

`tenant_settings.premiere_time`, `weekdays` and `personalized_ads` are kept for rollback only since V15; the values live in the property tables.
