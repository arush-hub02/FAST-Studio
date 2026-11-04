-- Durable media jobs (ingestion Stage 2, docs/MEDIA_JOBS_DESIGN.md). A row is written in the same transaction as the
-- change that needs it (the outbox) and is the authority for that work: workers on any server claim rows with
-- FOR UPDATE SKIP LOCKED, keep a lease alive with heartbeats, and publish only while their fence still matches.
-- Platform slates stay start-up work, so every job belongs to a project.
create table media_job (
  id            uuid primary key default gen_random_uuid(),
  org_id        uuid not null references organization(id),
  asset_id      uuid not null,
  kind          text not null default 'PACKAGE' check (kind in ('PACKAGE')),   -- IMPORT, EXPORT, QC in later stages
  lane          text not null check (lane in ('AD','PROGRAMME')),
  state         text not null default 'QUEUED' check (state in ('QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED')),
  attempts      int  not null default 0,
  max_attempts  int  not null default 5,
  next_run_at   timestamptz not null default now(),
  lease_owner   text,
  lease_until   timestamptz,
  fence         bigint not null default 0,
  last_error    text,                                   -- plain words, tenant-safe, no URLs or secrets
  created_at    timestamptz not null default now(),
  started_at    timestamptz,
  finished_at   timestamptz,
  foreign key (org_id, asset_id) references asset(org_id, id) on delete cascade
);
-- one active job per asset and kind: duplicate enqueues (webhook redelivery, double clicks, re-sync) are no-ops
create unique index media_job_one_active on media_job(asset_id, kind) where state in ('QUEUED','RUNNING','CANCEL_REQUESTED');
create index media_job_due on media_job(lane, next_run_at) where state = 'QUEUED';
create index media_job_leases on media_job(lease_until) where state in ('RUNNING','CANCEL_REQUESTED');
create index media_job_asset on media_job(org_id, asset_id, created_at desc);

alter table media_job enable row level security;
create policy tenant_isolation on media_job using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- Assets left converting by the old in-memory pools become queued jobs; the start-up re-queue scan is gone.
insert into media_job(org_id, asset_id, lane)
select org_id, id, case when kind = 'AD' then 'AD' else 'PROGRAMME' end from asset where status = 'PROCESSING';
