# Stage 2: durable media jobs (design for agreement)

Status: **proposal, not built.** Written 2026-11-01 for user review before any table or worker code. Stage numbers refer to [MEDIA_INGESTION_PLAN.md](MEDIA_INGESTION_PLAN.md).

## The problem today

`MediaServiceV1` keeps jobs in two in-memory thread pools (2 programme threads, 1 ad-creative thread).

- **Restart:** a restart loses the queue. On start-up the app re-queues every asset still marked `PROCESSING`, which is the only recovery there is.
- **Two servers:** both would scan, so both would package the same asset.
- **Crash mid-job:** nothing notices a dead worker until the next restart.
- **Retries:** a failure is final. A person has to press Retry.
- **Fairness:** one tenant syncing 500 library videos blocks every other tenant.
- **Cancellation and history:** you can't cancel a job, and there's no record of attempts, only the asset's last status line.

## What Stage 2 builds

One table, `media_job`, becomes the authority for every piece of background media work. Workers claim jobs from it with short transactions. Everything below follows from that.

### 1. A job is written in the same transaction as the change that needs it

`enqueue(assetId)` becomes an `insert into media_job` inside the caller's transaction, replacing today's after-commit thread hand-off. If the transaction rolls back, there's no job. If it commits, the job exists even if the server dies a millisecond later. This is the "transactional outbox" the plan asks for, without a second table.

Duplicate requests are harmless. A partial unique index allows only one *active* job per asset and kind, and a second enqueue is `on conflict do nothing`. Webhook redelivery, double-clicks and re-sync therefore never start a second encode.

### 2. Claiming: one worker per job, guaranteed by the database

Each worker repeats the following:

```sql
update media_job set state = 'RUNNING', lease_owner = :worker, lease_until = now() + interval '2 minutes',
       attempts = attempts + 1, fence = fence + 1, started_at = now()
where id = (select id from media_job
            where state = 'QUEUED' and lane = :lane and next_run_at <= now()
              and (org_id is null or org_id not in (/* tenants at their running limit */))
            order by next_run_at
            for update skip locked limit 1)
returning id, org_id, asset_id, kind, fence;
```

`for update skip locked` lets any number of workers on any number of servers poll the same table. Each job goes to exactly one of them. There's no lock held during the work; the transaction lasts milliseconds.

### 3. Leases and heartbeats, so a dead worker is noticed in minutes

- **Heartbeat:** while a job runs, its worker extends `lease_until` every 30 seconds with `where id = :id and fence = :fence and state = 'RUNNING'`.
- **Crash:** if the server dies, the lease runs out after at most 2 minutes. A once-a-minute **reaper** returns expired jobs to `QUEUED`, with backoff. After the last attempt it marks them `FAILED`.
- **Lost lease:** if a heartbeat updates 0 rows, the worker has lost the job. That happens when the job was cancelled, re-claimed after a long pause, or the database restored. The worker kills its ffmpeg process and stops.

### 4. Fencing: a stale worker can never publish

Every claim increments `fence`. A finished job publishes its result in **one transaction**: it updates the asset's `media_key` and marks the job `SUCCEEDED`. That transaction carries the guard `fence = :myFence and state = 'RUNNING'`.

Take a worker that froze, lost its lease and woke up after another worker re-ran the job. Its publish matches 0 rows, so it deletes its own output folder and exits. The asset keeps the newer revision.

As today, every run writes to a fresh folder (`{slug}/{asset}.{time}`). The currently airing media is never touched until the new revision is published. That keeps the plan's rule: an incomplete or failed encode can't replace airing media.

### 5. Retries and failure reasons

| Failure | Examples | Behaviour |
|---|---|---|
| **Retryable** | Network or provider timeouts, library unavailable, the worker died | Back to `QUEUED` with backoff: 1 min, 5 min, 25 min, 2 h. After **5 attempts**, `FAILED`. |
| **Permanent** | Unsupported or corrupt file, shorter than one segment, no connector, library account removed | `FAILED` immediately. Repeating it won't help. |

The asset keeps today's status values (`PROCESSING`, `READY`, `FAILED`) and its status line, so the screens and API don't change. The Retry button inserts a new job.

### 6. Cancellation

- **Queued job:** goes straight to `CANCELLED`.
- **Running job:** set to `CANCEL_REQUESTED`. The worker sees it on its next heartbeat (within 30 s), kills ffmpeg, deletes its output and marks the job `CANCELLED`.
- **The asset:** it goes back to its previous state. It stays `READY` with its old media, or becomes `DRAFT` if it never had media.

### 7. Lanes and fairness

| Lane | Default threads per server | Purpose |
|---|---|---|
| `AD` | 1 | Creatives are needed at the next break, so they never wait behind a film. This is today's behaviour. |
| `PROGRAMME` | 2 | Packaging programmes, library imports and uploads. |
| `SYSTEM` | shares `PROGRAMME` | Platform slates and future clean-up work. |

**Per-tenant limit:** at most **2 running programme jobs per tenant** across all servers. The claim query skips tenants at their limit, so one tenant's 500-video sync can't starve the others. The limit is a configuration property, not a per-plan setting yet.

### 8. Tenant isolation

`media_job` has `org_id` and the same row-level security policy as `asset`. Platform jobs (slates) have `org_id` null and are visible to the platform only.

- **Claiming:** the claim is system work across tenants and runs under `TenantScope.bypass`, as today's recovery scan does.
- **Running a job:** it runs entirely inside `TenantScope.as(job.org_id)`. A job ID alone never grants access.
- **Status API:** every status read goes through the tenant routes.

### 9. Status API (minimal)

`GET /api/orgs/{org}/assets/{id}/jobs` returns the last 10 jobs for that asset: state, attempt, current lane, last error in plain words, and times. The Library drawer can show it later. No new screen is part of Stage 2.

### 10. Start-up and migration

The migration creates one `QUEUED` job for every asset currently in `PROCESSING`. It then removes the "re-queue everything at start-up" scan. Slates stay start-up work: they're idempotent, check whether their files exist, and need no job rows.

## Proposed table

```sql
create table media_job (
  id            uuid primary key default gen_random_uuid(),
  org_id        uuid references organization(id),          -- null = platform work
  asset_id      uuid references asset(id),
  kind          text not null,                              -- PACKAGE (Stage 2); IMPORT, EXPORT, QC later
  lane          text not null check (lane in ('AD','PROGRAMME','SYSTEM')),
  state         text not null check (state in ('QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED')),
  attempts      int  not null default 0,
  max_attempts  int  not null default 5,
  next_run_at   timestamptz not null default now(),
  lease_owner   text,
  lease_until   timestamptz,
  fence         bigint not null default 0,
  last_error    text,                                       -- plain words, tenant-safe, no URLs or secrets
  created_at    timestamptz not null default now(),
  started_at    timestamptz,
  finished_at   timestamptz
);
create unique index media_job_one_active on media_job(asset_id, kind) where state in ('QUEUED','RUNNING','CANCEL_REQUESTED');
create index media_job_due on media_job(lane, next_run_at) where state = 'QUEUED';
create index media_job_leases on media_job(lease_until) where state in ('RUNNING','CANCEL_REQUESTED');
-- + RLS policy like asset; finished rows older than 30 days purged by the hourly job
```

## Tests that must pass before Stage 2 is called done

1. **Duplicate enqueue:** the same asset enqueued twice, concurrently, gives one active job.
2. **Two workers:** two workers polling at once mean each job runs exactly once (two Spring contexts on one database, or two claim threads).
3. **Crash:** a job whose worker stops heartbeating is re-queued after its lease and succeeds on a second worker.
4. **Stale worker:** a worker whose lease was taken over can't publish, and the asset keeps the newer media.
5. **Cancel:** cancelling queued and running jobs works, ffmpeg is killed, and the old media still plays.
6. **Restart:** jobs survive an application restart, including a migrated `PROCESSING` asset.
7. **Retries:** a retryable failure backs off and then fails after 5 attempts, while a permanent failure fails at once.
8. **Fairness:** with 6 queued jobs from tenant A and 1 from tenant B, B's job starts before A's third.
9. **Tenant isolation:** tenant B can't see or cancel tenant A's jobs, and the job API respects RLS.
10. **Existing tests:** the existing suite (including MuxTest webhook replay) passes unchanged.

## Decisions needed from you

1. **Cloudflare Queues now, or Postgres only?**
   - The agreed plan says Cloudflare Queues carries job IDs to the Java workers.
   - **Recommendation: Postgres polling only in Stage 2.** The Java workers sit next to the database, jobs take minutes, and there are few of them. `skip locked` polling every 2 seconds costs nothing at that rate. Cloudflare Queues would add a second system that has to be kept consistent with the table, and its acknowledgement timeouts don't fit 30–60-minute encodes anyway.
   - The table design leaves room to add Queues later as a "wake up" signal, without changing the semantics.
2. **Scope of Stage 2.**
   - The plan lists batch, item, upload-session and source tables for Stage 2.
   - **Recommendation: build `media_job` (and its API) now.** Add `media_import_batch`/`media_import_item` with CSV import (Stage 4), and `media_upload_session` with batch upload (Stage 5), when the screens that use them exist. Stage 3 adds `asset_source` and the `mux_account` → `media_connection` move.
   - Building all the tables now means guessing at their columns.
3. **Defaults.**
   - 5 attempts with 1 min / 5 min / 25 min / 2 h backoff.
   - A 2-minute lease with a 30-second heartbeat.
   - 2 running programme jobs per tenant.
   - 2 programme threads and 1 ad thread per server.
   - All configurable; tell me if any should differ.

## Known limits after Stage 2

- **Interrupted encodes restart from scratch.** ffmpeg can't resume. Splitting long films into resumable chunks is only worth doing if real films make restarts costly.
- **Orphaned ffmpeg.** If the JVM is killed hard, an ffmpeg child process may keep running until it finishes. Its output can't be published (fencing), but it uses CPU. A container restart ends it; on Windows dev machines it can linger.
- **Polling latency.** An idle worker picks up a new job within about 2 seconds. A local in-process nudge makes jobs created on the same server start at once.
- **Provider work.** Mux master preparation is still polled inside the job (MuxClientService.masterUrl). Driving it from the `master.ready` webhook is Stage 3/6 work.
