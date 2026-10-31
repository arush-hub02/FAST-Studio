-- Ad demand connectors: each tenant's ordered waterfall of ad sources (HANDOVER §8.1).
-- DIRECT = our own direct-sold campaigns; VAST = any external ad server reached through a VAST tag (GAM, SpringServe,
-- FreeWheel, a tenant's own tag...). A tenant without rows gets the default waterfall: DIRECT only.
create table ad_source (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  name text not null,
  kind text not null check (kind in ('DIRECT','VAST')),
  tag_url text,
  timeout_ms int not null default 1000 check (timeout_ms between 100 and 5000),
  position int not null,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  check (kind <> 'VAST' or tag_url is not null),
  unique (org_id, id)
);
create unique index ad_source_one_direct on ad_source(org_id) where kind = 'DIRECT';

alter table ad_source enable row level security;
create policy tenant_isolation on ad_source using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- Creatives returned by an external ad server are re-cut into 5 s segments once, then reused:
-- the media file URL identifies them.
alter table asset add column source_url text;
create unique index asset_source_url on asset(org_id, source_url) where source_url is not null;
