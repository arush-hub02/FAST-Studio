-- Ingestion Stage 3 (docs/MEDIA_INGESTION_PLAN.md "Tenant Mux catalogue sync"): a project's video-platform connection
-- becomes provider-neutral, every imported video records where it came from and what the platform last said about it,
-- and a library sync is a durable, resumable job.

-- The connection: mux_account keeps its rows, ciphertexts and row policy under the new name.
alter table mux_account rename to media_connection;
alter table media_connection enable row level security;   -- already on; restated so the tenant-filter check sees the new name
alter table media_connection
  add column provider text not null default 'mux',
  add column checked_at timestamptz,          -- last health check; null = never checked
  add column check_error text,                -- null after a successful check; tenant-safe words otherwise
  add column sync_cursor text,                -- checkpoint: where the running scan continues (null = from the start)
  add column sync_started_at timestamptz,     -- start of the current/last full scan (sources not seen since then are missing)
  add column sync_finished_at timestamptz,    -- set when every page of that scan was processed
  add column sync_pages int not null default 0,
  add column sync_videos int not null default 0,
  add column sync_added int not null default 0;

-- Where each imported video came from. The asset row keeps source_ref ('{provider}:{id}') as its binding and dedupe key;
-- this row holds what the platform last reported, separate from our own readiness (asset.status).
create table asset_source (
  org_id          uuid not null,
  asset_id        uuid not null,
  provider        text not null,
  external_id     text not null,
  provider_status text not null check (provider_status in ('READY','PREPARING','ERRORED','DELETED','MISSING')),
  title           text,
  duration_ms     bigint,
  first_seen_at   timestamptz not null default now(),
  last_seen_at    timestamptz not null default now(),
  primary key (org_id, asset_id),
  unique (org_id, provider, external_id),
  foreign key (org_id, asset_id) references asset(org_id, id) on delete cascade
);
alter table asset_source enable row level security;
create policy tenant_isolation on asset_source using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

insert into asset_source(org_id, asset_id, provider, external_id, provider_status, title, duration_ms)
select org_id, id, 'mux', substr(source_ref, 5), 'READY', title, duration_ms from asset where source_ref like 'mux:%';

-- A sync is a media job of the project, not of one asset.
alter table media_job alter column asset_id drop not null;
alter table media_job drop constraint media_job_kind_check;
alter table media_job add constraint media_job_kind_check check (kind in ('PACKAGE','SYNC'));
alter table media_job add constraint media_job_asset_needed check ((kind = 'SYNC') = (asset_id is null));
create unique index media_job_one_sync on media_job(org_id) where kind = 'SYNC' and state in ('QUEUED','RUNNING','CANCEL_REQUESTED');
