-- FAST Studio prototype schema.
-- Multitenancy: shared schema; every tenant-owned row carries org_id, and composite
-- (org_id, id) foreign keys make cross-tenant references impossible at the database level.

create table organization (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  name text not null,
  status text not null default 'ACTIVE' check (status in ('ACTIVE','SUSPENDED')),
  country text not null,
  timezone text not null,
  plan text not null default 'Pilot',
  max_channels int not null default 10,
  created_at timestamptz not null default now()
);

create table app_user (
  id uuid primary key default gen_random_uuid(),
  email text not null unique,
  name text not null,
  password_hash text not null,
  platform_admin boolean not null default false,
  created_at timestamptz not null default now()
);

create table membership (
  org_id uuid not null references organization(id),
  user_id uuid not null references app_user(id),
  role text not null check (role in ('OWNER','ADMIN','CONTENT_MANAGER','CHANNEL_OPERATOR','AD_OPS','ANALYST')),
  created_at timestamptz not null default now(),
  primary key (org_id, user_id)
);

create table asset (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  title text not null,
  description text not null default '',
  kind text not null check (kind in ('MOVIE','EPISODE','SHORT','AD','SLATE')),
  genre text not null default '',
  language text not null default 'en',
  series text,
  season int,
  episode int,
  duration_ms bigint not null default 0,
  cue_points text not null default '',
  status text not null default 'DRAFT' check (status in ('DRAFT','PROCESSING','READY','FAILED','ARCHIVED')),
  status_detail text not null default '',
  media_key text,
  color text not null default '#6366f1',
  generator text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (org_id, id)
);

create table rights_policy (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null,
  asset_id uuid not null,
  allowed_countries text not null default '',
  blocked_countries text not null default '',
  valid_from timestamptz,
  valid_until timestamptz,
  ads_allowed boolean not null default true,
  notes text not null default '',
  updated_at timestamptz not null default now(),
  unique (org_id, asset_id),
  foreign key (org_id, asset_id) references asset(org_id, id)
);

create table channel (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  slug text not null,
  name text not null,
  description text not null default '',
  genre text not null default '',
  language text not null default 'en',
  timezone text not null,
  color text not null default '#6366f1',
  territories text not null default '',
  status text not null default 'DRAFT' check (status in ('DRAFT','LIVE','STOPPED','ARCHIVED')),
  ad_seconds_per_hour int not null default 480,
  break_seconds int not null default 90,
  fallback_asset_id uuid,
  published_version_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (org_id, slug),
  unique (org_id, id),
  foreign key (org_id, fallback_asset_id) references asset(org_id, id)
);

create table schedule_version (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null,
  channel_id uuid not null,
  number int not null,
  status text not null check (status in ('DRAFT','PUBLISHED','SUPERSEDED')),
  revision int not null default 0,
  note text not null default '',
  created_by uuid references app_user(id),
  created_at timestamptz not null default now(),
  published_at timestamptz,
  unique (org_id, id),
  unique (channel_id, number),
  foreign key (org_id, channel_id) references channel(org_id, id)
);

create table schedule_entry (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null,
  version_id uuid not null,
  kind text not null check (kind in ('PROGRAM','AD_BREAK')),
  asset_id uuid,
  asset_offset_ms bigint not null default 0,
  start_at timestamptz not null,
  end_at timestamptz not null,
  ownership text check (ownership in ('PROVIDER','DISTRIBUTOR')),
  check (end_at > start_at),
  foreign key (org_id, version_id) references schedule_version(org_id, id) on delete cascade,
  foreign key (org_id, asset_id) references asset(org_id, id)
);
create index schedule_entry_version_start on schedule_entry(version_id, start_at);

create table campaign (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  name text not null,
  advertiser text not null,
  status text not null default 'ACTIVE' check (status in ('ACTIVE','PAUSED','ENDED')),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  territories text not null default 'WW',
  priority int not null default 5,
  cpm numeric(10,2) not null default 10.00,
  creative_asset_id uuid not null,
  created_at timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, creative_asset_id) references asset(org_id, id)
);

create table destination (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  name text not null,
  kind text not null check (kind in ('OWNED_WEB','PARTNER_TV')),
  ad_mode text not null check (ad_mode in ('SSAI','SGAI','PASSTHROUGH')),
  marker_format text not null default 'DATERANGE' check (marker_format in ('DATERANGE','CUE_OUT')),
  max_ad_seconds_per_hour int not null default 720,
  notes text not null default '',
  created_at timestamptz not null default now(),
  unique (org_id, id)
);

create table publication (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null,
  channel_id uuid not null,
  destination_id uuid not null,
  version_number int,
  status text not null check (status in ('SUCCEEDED','FAILED')),
  message text not null,
  simulated boolean not null,
  created_at timestamptz not null default now(),
  foreign key (org_id, channel_id) references channel(org_id, id),
  foreign key (org_id, destination_id) references destination(org_id, id)
);

create table playback_session (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null,
  channel_id uuid not null,
  destination_id uuid not null,
  country text not null,
  ad_mode text not null,
  created_at timestamptz not null default now(),
  unique (org_id, id),
  foreign key (org_id, channel_id) references channel(org_id, id),
  foreign key (org_id, destination_id) references destination(org_id, id)
);

create table ad_event (
  id bigserial primary key,
  org_id uuid not null,
  session_id uuid not null,
  channel_id uuid not null,
  break_id uuid not null,
  campaign_id uuid,
  event text not null check (event in ('IMPRESSION','FIRST_QUARTILE','MIDPOINT','THIRD_QUARTILE','COMPLETE','SLATE')),
  seconds int not null default 0,
  occurred_at timestamptz not null default now(),
  foreign key (org_id, session_id) references playback_session(org_id, id)
);
-- one event of each type per ad per break per session (replayed segment fetches don't double count)
create unique index ad_event_once on ad_event(session_id, break_id, coalesce(campaign_id, '00000000-0000-0000-0000-000000000000'::uuid), event);

create table alert (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  dedup_key text not null,
  severity text not null check (severity in ('INFO','WARNING','CRITICAL')),
  title text not null,
  detail text not null,
  suggestion text not null default '',
  link text not null default '',
  status text not null default 'OPEN' check (status in ('OPEN','ACKNOWLEDGED','RESOLVED')),
  resolution text not null default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index alert_open_dedup on alert(org_id, dedup_key) where status <> 'RESOLVED';

create table audit_event (
  id bigserial primary key,
  org_id uuid references organization(id),
  actor_id uuid references app_user(id),
  action text not null,
  resource_type text not null,
  resource_id text,
  detail text not null default '',
  created_at timestamptz not null default now()
);
create index audit_org_time on audit_event(org_id, created_at desc);
