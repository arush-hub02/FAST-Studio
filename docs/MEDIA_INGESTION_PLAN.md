# Media ingestion implementation plan
Prepared 31 October 2026, Asia/Calcutta. Status: staged implementation started; the first security/bootstrap slice is recorded below.

Agent workflow: read [AGENTS.md](../AGENTS.md) and the current [PROGRESS.md](../PROGRESS.md), claim a stage before implementation, and update the shared checkpoint before handoff. This document is the detailed design; PROGRESS.md records which parts actually exist and their verification.

Implementation checkpoint: the existing work was committed as 88e533b, pushed and fast-forwarded into main. New work is isolated on codex/media-ingestion. The first slice adds a tenant-scoped project-details bootstrap, the requested controller/service/repository/DTO boundaries, and write-only credential response protections. See [Project bootstrap security](PROJECT_BOOTSTRAP_SECURITY.md). Existing asset/Mux extraction, durable batches and the ingestion UI remain the subsequent stages below.

## Intended result

A tenant imports media through one consistent workflow: CSV with downloadable examples, bulk files in batches, selection from an existing library, or sync of its existing Mux account. Imports create product assets with usable metadata and explain progress/errors. Additional OVPs can be registered later without changing the ingestion controllers, job orchestration or basic UI.

Media ownership remains: Mux generates programme renditions; packaging/export workers publish compatible outputs to R2; Cloudflare CDN delivers segments; the Worker assembles live manifests. The existing master-download/full-encode implementation must not silently become the new default.

## What exists and what changes

| Existing code | Observed issue | Planned change |
| --- | --- | --- |
| catalog/AssetController.java | HTTP, SQL, metadata/episode validation, rights updates and processing submission share a controller | Thin media controllers; extract tenant-aware asset/rights services and repositories |
| media/MuxController.java | Account administration, direct upload, catalogue import, asset upsert and webhook translation mixed together | Separate connection, catalogue sync, upload and webhook use cases |
| media/MuxSource.java | Encrypted account storage, HTTP requests, paging, master polling and full FFmpeg encode delegation mixed together | Mux client/adapter separate from credential persistence and packaging |
| media/ContentSource.java | Connector contract requires the provider to package via our encoder | Provider-neutral catalogue/upload/export contracts; packaging is a separate pipeline stage |
| media/MediaService.java | In-memory executor with two programme jobs; recovery scans PROCESSING assets | Durable item/jobs, transactionally recorded dispatch and leased workers |
| frontend/pages/Library.tsx | Large component contains upload, metadata editing and catalogue presentation | Small import wizard and batch-monitor components outside the catalogue page |
| frontend/components/MuxConnector.tsx | Connected currently reflects saved configuration, and sync returns counts | Add tested connection health, scope, sync history and item-level results |

Keep unrelated scheduling, ads, billing and distribution code unchanged in this ingestion work. Move shared asset rules with their callers so imports, ordinary edits and sync use the same validation.

## User entry points

Library has an Add media entry with:
1. Upload files.
2. Import CSV.
3. Browse connected library.
4. View import batches and failures.

Channel programming's Add from library selects already-existing eligible tenant assets. It creates placements/references, not duplicate assets or another encode. Browse connected library instead selects remote provider assets to import into the product catalogue. This plan covers both interpretations of “Add from library.”

### CSV import

Flow: Download template/example -> choose CSV -> map/validate -> preview -> attach matching files if required -> confirm import -> monitor results.

Provide:
- A header-only template and a filled example, plus a column guide.
- A versioned product template, and header mapping for customer spreadsheets.
- A tenant-specific template/guide listing enabled media-type keys and usable connector keys, without credentials.
- Row-level errors with row number, column and corrective message.
- A downloadable failed-row CSV with spreadsheet-formula-safe output and redacted credential-bearing URLs.
- Dry-run preview showing create, existing, conflict, needs file, missing metadata and blocked rows.
- Explicit Create only default. A later/optional Update metadata mode must preview exact changes and never replace an airing media revision silently.
- Valid rows may proceed only after the operator explicitly chooses that partial-import option; invalid rows remain visible and retryable.

Template artifacts supplied with this plan:
- [Blank template](samples/media-import-template.csv)
- [Filled example](samples/media-import-example.csv)

The example is illustrative. The FILE names must match selected local files. The URL, connector key, Mux ID and rights values must be replaced with actual tenant data before importing.

| Column | Requirement / interpretation |
| --- | --- |
| external_id | Required stable customer reference; deduplication within the tenant's declared ingestion namespace |
| title | Required; use existing title length/validation rules |
| media_type | Required enabled registry key, e.g. MOVIE/EPISODE or tenant-enabled custom key; server derives base kind |
| source_type | FILE, URL or CONNECTOR; provider names do not become new source types |
| source | Exact unique filename for FILE; reachable HTTP(S) media URL for URL; opaque external asset ID for CONNECTOR |
| connector_key | Required for CONNECTOR; optional processing connection for other types; resolves only within this tenant |
| series | Required for EPISODE |
| season / episode | Required positive integers for EPISODE; do not infer an entire series order from filenames |
| genre / language / description | Optional metadata; defaults are visible in preview |
| allowed_countries / blocked_countries | Pipe-separated country codes; do not infer worldwide rights from an empty cell |
| rights_start / rights_end | ISO 8601 timestamps including timezone; validate ordering; empty means unknown according to explicit rights policy |
| ads_allowed | true/false; unknown/blank must not silently grant advertising rights |

CSV parsing handles UTF-8 BOM, quoting, embedded commas/newlines, empty values and duplicate headers. Apply existing field bounds. Never parse with a simple comma split. Reuse an existing maintained parser if present; otherwise select and pin one small parser dependency during implementation.

Duplicate episode checks run across CSV rows, provider selections and existing tenant assets. Rights need to be resolved before broadcast, even when an asset is already imported and processed. CSV import alone does not create a channel or publish anything.

### Bulk files and batches

- Multiple file selection and drag/drop; display one item per file with size, media type, metadata and state.
- Attach a CSV for mixed movies/episodes. Without CSV, provide a bulk metadata editor and per-item validation; episode order remains explicit.
- Allow title cleanup and an optional reviewed S01E01 filename suggestion, not an unreviewed metadata write.
- Detect filename collisions, missing CSV files, unsupported formats, empty files and size/quota violations before transfer.
- One durable batch tracks independent file transfers and provider assets. Do not send all movies in one giant multipart API request.
- Programme uploads use resumable direct provider URLs. Java handles authorization and upload-session metadata, not the video payload.
- Proposed starting defaults: 100 selected files/batch, 500 CSV rows, two concurrent transfers per browser and two active preparation jobs per tenant. Make limits configurable; a queue does not imply unlimited storage or processing.
- Upload-session expiration, retry, pause and re-selection behavior are visible. Server-side preparation continues after the browser closes. Unfinished local-file transfers may require reopening and reselecting matching files; do not promise upload continuation from a closed browser.
- Retry only failed/missing items. Persist item asset/upload IDs so a retry or double-click cannot create another asset or duplicate paid provider job.
- Cancel stops new work and requests supported in-flight cancellation. Existing successfully imported assets remain. Deleting local or remote assets is a separate deliberate operation.
- Show counts and individual stages. Do not describe “upload complete” or “Mux ready” as “broadcast ready.”

Mux direct upload supports resumable transfers. [Provider documentation](https://www.mux.com/docs/guides/upload-files-directly)

### Tenant Mux catalogue sync

Platform owner configures the tenant's existing Mux environment/token/webhook connection; credentials stay encrypted on the server. Tenant users with asset-edit permission and the appropriate feature may browse/import/sync that connection. Keep current platform-owned connection-management policy; tenant self-service credential administration is a separately permissioned future option.

1. Test credentials/capabilities and report last successful check. Saved credentials alone do not mean a healthy connection.
2. Discover existing assets page by page with resumable checkpoints. Permit full-catalogue scope or an explicit passthrough filter, and show that scope clearly.
3. Preview import counts and metadata mappings. Permit selecting assets or all matching assets.
4. Create/update local product asset records referencing those existing Mux IDs. **Do not upload the same video to Mux again.**
5. Expose two distinct outcomes: Catalogue synced and Prepared for broadcast. Default sync imports metadata; selected items can then be queued for export/QC/R2 preparation.
6. Support “prepare selected assets” and an explicit optional auto-prepare setting, with quotas and estimated impact before a large paid export.
7. Persist the source binding and source revision. Repeated sync/webhook delivery is idempotent.
8. Import provider metadata where available; leave unmapped media type or missing episode data in Needs review rather than classifying every video as a movie.
9. Preserve operator-authored descriptions, series/episode corrections and rights. Default refresh updates source status and fills mapped empty fields; preview any explicit overwrite policy.
10. Provider status ready, errored, deleted and missing-export are separate from our media-readiness state. A missing remote asset flags a source issue; it does not automatically delete the local catalogue or an R2-backed published revision.
11. Add manual sync first. Webhooks plus a low-frequency scheduled reconciliation handle later changes; do not assume a universal updated-since API. A full scan is finished only when all pages have been processed.
12. Sync writes checkpoint/item results incrementally. A large sync does not hold an HTTP request or database transaction while making provider calls.

Keep credential access least-privilege: catalogue discovery may work with read permissions while upload/export creation requires additional provider permission. UI actions reflect tested capabilities.

## Connector design for more OVPs

All three source types enter one IngestionService and produce normalized product metadata. Provider differences are isolated in adapters.

Two responsibilities must remain distinct:
- Source discovery: list/get remote assets, normalized metadata, provider status and source identity.
- Preparation capability: direct uploads, URL ingest and encoded exports, available only when a provider supports them.

Define a small MediaProviderConnector SPI, a registry and explicit capabilities such as CATALOGUE, DIRECT_UPLOAD, URL_INGEST, ENCODED_EXPORTS and WEBHOOKS. Use optional focused capability contracts where needed; do not force every provider to fake Mux-only methods.

Normalized results include external ID, provider revision, source status, duration, metadata, tracks/artwork references, pagination cursor and usable export descriptors. Credentials and provider response objects do not escape into controllers or the generic UI.

Mux adapter uses a MuxClient for provider HTTP calls. A future Brightcove/JW/custom OVP adapter registers its provider key and capabilities. Controllers, job states, batch screens and CSV columns remain unchanged. A provider that supplies only a master may feed the selected encoding provider; an already compatible encoded source can avoid a redundant encode.

No generic plugin marketplace or hot-loaded provider framework is required. Implement and test Mux as the first real adapter and a fake limited-capability adapter to prove the boundary.

## Folder architecture

Follow the project-wide layout in [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md) (adopted 2026-10-31, replacing the earlier `controller/`, `integration/`, `jobs/` proposal). Media placement:

```text
backend/src/main/java/com/faststudio/
  v1_0/controllers/studio/media/
    AssetControllerV1_0.java
    MediaImportControllerV1_0.java
    MediaSyncControllerV1_0.java
  v1_0/controllers/platform/connectors/
    MediaConnectionControllerV1_0.java
  v1_0/controllers/webhook/
    MuxWebhookControllerV1_0.java
  v1_0/services/media/
    AssetServiceV1_0.java
    MediaImportServiceV1_0.java
    MediaSyncService.java
    UploadSessionService.java
    MediaPreparationService.java
  v1_0/services/webhook/
    MediaWebhookServiceV1_0.java
    parsers/IWebhookParser.java, MuxWebhookParser.java, WebhookParserFactory.java
  v1_0/repositories/media/
    AssetRepository.java
    MediaConnectionRepository.java
    MediaImportRepository.java
    MediaJobRepository.java
  v1_0/dto/media/
    AssetRequests.java, ImportRequests.java, ImportViews.java, ConnectionViews.java
    ImportBatch.java, ImportItem.java, MediaJob.java, AssetSource.java   (records)
  v1_0/util/mediaProvider/
    IMediaProvider.java
    MediaProviderResolverService.java
    ProviderCapabilities.java
  v1_0/util/mux/
    MuxClientService.java
    MuxWebhookVerifier.java
  messaging/v1_0/media/
    MediaJobPublisherV1_0.java
    MediaJobObserverV1_0.java
    MediaReconciliationJob.java
```

Controllers: request binding, endpoint authorization and response mapping.
Services: use-case rules, reusable validation, idempotency, transaction boundaries and audit actions.
Repositories: tenant-scoped SQL using current JdbcClient; keep the RLS protection.
`util/` adapters: provider HTTP, authentication, pagination and provider response/webhook translation.
`messaging/v1_0`: lease/execute/retry background stages using the same services.

Use records where useful for DTOs, but do not keep all API contracts, SQL and business rules nested inside a large controller. Do not create one-file-per-trivial-field or unused interfaces just to populate folders.

Keep existing public API paths working through delegating compatibility routes while moving implementation. Migrate owned ingestion/catalogue callers and tests in the same step. Other domains move to this structure when changed, rather than a repository-wide rename now.

Frontend:
```text
frontend/src/features/media/
  api/
  components/
    AddMediaDialog.tsx
    CsvImportWizard.tsx
    BatchUpload.tsx
    ConnectedLibraryPicker.tsx
    ImportProgress.tsx
  hooks/
  types.ts
```
Library remains the catalogue page; the existing shared Modal/forms/table styles stay in use. Provider capabilities drive available actions. Show Mux as the tenant-connected provider only where the platform's visibility policy permits; processing stages use user-readable labels.

## Persistence and durable jobs

Proposed records (final columns defined in the schema change):
- media_connection: tenant, provider key, stable connection key, encrypted credentials, capabilities/health, sync scope and checkpoint. Migrate existing mux_account data while preserving ciphertext/key compatibility and legacy lookups.
- media_import_batch: origin, template version, mode, actor, configuration snapshot, state and counts.
- media_import_item: row/file identity, normalized metadata, validation result, asset binding, progress, current stage and error.
- media_upload_session: item, attempt, provider upload ID, file identity and resumability/expiration state.
- asset_source: tenant asset -> connection/provider external ID and revision. Unique by tenant + connection + external ID; enforce external_id dedupe in the chosen ingestion namespace.
- media_job: stage, item/asset/revision, attempts, next run, lease owner/expiry, fencing token and dispatch state.
- media_webhook_receipt: connection, provider event identity/body hash, verified receipt status and replay-safe processing.

Use the durable media_job rows as the transactional dispatch outbox instead of introducing a second redundant job table. An outbox dispatcher sends job IDs to Cloudflare Queues. Duplicate dispatch/delivery is safe. Job outcomes and batch progress remain authoritative in PostgreSQL, not in a browser tab or queue payload.

Short transactions claim a stage and lease; provider calls and file transfer happen outside the transaction. Heartbeats renew database leases. Completed or superseded jobs are no-ops on redelivery. Fencing prevents an expired worker from publishing a stale revision. Reconciliation republishes stranded due jobs; failed items become retryable with a visible reason.

For jobs too long for a queue lease, acknowledge only after a durable execution handoff, and use database leases/reconciliation for recovery, or split the job into bounded resumable stages. Finalize the chosen semantics before implementing consumers. Do not hold database locks or Cloudflare request handlers for a full movie transcode.

Production uses Cloudflare Queues with pull consumers from our Java workers. Local dispatch still uses durable database state; no production fallback to an in-memory executor. [Pull-consumer documentation](https://developers.cloudflare.com/queues/configuration/pull-consumers/)

Item preparation stages:
```text
VALIDATING -> NEEDS_INPUT or READY_TO_IMPORT
READY_TO_IMPORT -> UPLOADING / IMPORTING_SOURCE
-> WAITING_PROVIDER -> EXPORTING -> PACKAGING -> QC -> BROADCAST_READY
```
Any active stage can produce a retryable/terminal failure. Aggregate batch results distinguish complete, partial success, cancellation and remaining input. Catalogue-only sync ends successfully with imported records; it does not have to pay for preparation to be complete.

Keep existing asset status/API values compatible during the first refactor. Readiness details belong to import/preparation state until an explicit asset-state migration is validated.

## Safety and product defaults

- Every record/query/queue lookup is tenant-scoped; IDs from CSV or provider webhooks are not tenant authorization.
- Asset edits, CSV imports and provider sync share the same media-type/episode/rights rules.
- Do not grant worldwide rights or ads permission because a field is blank.
- Validate outbound URLs and every redirect; protect downloaders/secondary media fetches from internal network access. Redact signed URLs/tokens from logs and error exports.
- Verify webhook signatures on raw bytes and deduplicate replayed events; retain existing webhook route compatibility.
- Preview and quotas precede large paid preparation; cancelled/duplicate jobs do not launch another provider operation.
- Disconnecting/reconfiguring a provider does not delete remote assets, ready R2 media or published schedules.
- New revisions prepare beside the old one; an incomplete sync/encode/export cannot replace currently airing ready media.
- Add tenant limits for stored bytes, input duration/resolution, active uploads, preparation concurrency and retries.
- Provider output has individual readiness; Mux asset ready does not mean every static export is ready. [Mux export lifecycle](https://www.mux.com/docs/guides/enable-static-mp4-renditions)

## Implementation sequence and checkpoints

| Stage | Work | Evidence before moving on |
| --- | --- | --- |
| 1. Safe structural refactor | Extract services/repositories from asset and Mux controllers, centralize validation, retain route compatibility | Existing endpoint behavior and cross-tenant tests pass; no changed production workflow |
| 2. Durable ingestion foundation | Batch/item/source/upload/job records, registry, outbox dispatcher, leases and status APIs | Duplicate dispatch, restart, cancellation and tenant-isolation tests pass |
| 3. Existing Mux library | Tested tenant connection, paginated browse/sync, normalized asset creation, checkpoints, safe refresh and catalogue-only mode | Re-sync creates no duplicates; partial scan resumes; manual edits and paid preparation choice are retained |
| 4. CSV and library picker | Template downloads, row parser/preview, metadata validation/mapping, source matching and results | Good/bad/quoted CSV cases, duplicate episodes, tenant types/rights and deterministic retry pass |
| 5. Batch direct upload | Multi-file selection, metadata review, resumable provider sessions, bounded concurrency and progress | Large-file interrupt/resume and failed-item retry pass without duplicate assets/uploads |
| 6. Mux outputs to R2 | Prove representative export/keyframe compatibility, implement packaging/QC/output profile, atomic ready revision | Programme -> ad -> programme playback, ABR changes and movie-length output pass with actual services |
| 7. Acceptance and operational finish | End-to-end UI, load/backpressure, webhook reconciliation, metrics, cleanup/retention and upgrade rehearsal | Operator can complete all three source workflows with accountable errors/costs |

Run a small export feasibility spike early alongside Stage 1, before committing the full ingest UI to an unproven segment profile. Catalogue sync/CSV metadata work can proceed independently. If output conditioning needs a full encode, record that architecture/cost decision explicitly.

Each stage leaves a reviewable result; no big-bang switch or application-wide folder rewrite. No cloud deployment or spending is part of this planning task.

## Acceptance scenarios

1. Download the blank/filled CSV; preview shows valid rows and exact errors without importing.
2. Mixed file/URL/Mux rows create one asset each with correct tenant media type, metadata and source identity.
3. Batch FILE rows match selected files; an ambiguous duplicate filename or missing file is actionable.
4. Long transfers resume after interruption; retry retains the same asset/provider operation when safe.
5. EPISODE rows contain valid series/season/episode data and immediately work with the existing smart-scheduling selector once broadcast-ready.
6. Tenant A cannot list, import, edit or retry Tenant B's assets, connections or batches.
7. Existing Mux library sync is paginated, resumable and duplicate-safe; no re-upload or automatic paid export is hidden in sync.
8. Updating local rights/episode metadata survives re-sync; provider deletion does not purge local ready media.
9. Provider ready/export ready/R2 ready remain distinct. Failed preparation retains the previous ready published revision.
10. Duplicate webhook/queue messages, worker death, expired lease, cancellation and credential rotation recover without stale ready publication or duplicate processing.
11. Catalogue-only import can finish before broadcast preparation; preparation has an explicit selected scope.
12. Live playback and fresh schedule/rights publication remain responsive while a bulk batch runs.
13. Another provider with limited capabilities can be registered without changing the generic import controllers or UI branching on provider name.

## Suggestions beyond the requested three entry points

Include now: import history, item retry/error CSV, duplicate detection, metadata/rights validation, cost-aware prepare selection, source provenance, connection health and configurable tenant limits.

Add later when required: scheduled storage-bucket imports, SFTP/MRSS feeds, more OVP adapters, caption/artwork sidecars, richer bulk metadata patching, automatic reviewed metadata enrichment and a public ingestion API.

Deliberately deferred: additional real provider implementations, marketplace/plugin infrastructure, a full catalogue-editor rewrite and automatic remote deletion. This plan does not claim the Mux-to-R2 media proof, queue or batch feature is implemented.

