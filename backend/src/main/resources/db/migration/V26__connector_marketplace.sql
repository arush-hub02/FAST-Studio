-- Platform-administration P3a (docs/PLATFORM_ADMIN_PLAN.md "P3 connector marketplace: design"), modelled on the v2 CMS
-- ops connectors: Connector category ─ Connector ─ Property definitions; Instances (per project, or one for the platform)
-- hold the property values. Every field is a row here, not code: adapters read values by property key.

create table connector_category (
  key         text primary key check (key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  name        text not null,
  description text not null default '',
  position    int  not null default 0
);
insert into connector_category(key, name, description, position) values
  ('MEDIA', 'Media library', 'Where videos come from', 1),
  ('ADS', 'Advertising', 'Ad servers and demand', 2),
  ('STORAGE', 'Storage and delivery', 'Media origin and CDN', 3),
  ('OTHER', 'Other', 'Everything else', 4);

alter table connector_definition drop constraint if exists connector_definition_category_check;
alter table connector_definition add constraint connector_definition_category_fk foreign key (category) references connector_category(key);
alter table connector_definition
  add column long_description    text not null default '',
  add column logo_url            text,
  add column dark_logo_url       text,
  add column reference_link      text,
  add column tier                text not null default 'FREE' check (tier in ('FREE','PRO')),
  add column status              text not null default 'AVAILABLE' check (status in ('AVAILABLE','UPCOMING','UNPUBLISHED')),
  add column tenant_update       text not null default 'NOT_ALLOWED' check (tenant_update in ('ALLOWED','NOT_ALLOWED','CONTACT_ACCOUNT_MANAGER')),
  add column required_permission text,          -- tenants see the connector only with this permission (null = everyone)
  add column level               text not null default 'PROJECT' check (level in ('PLATFORM','PROJECT')),
  add column multi_instance      boolean not null default false;

update connector_definition set level = 'PLATFORM' where key = 'r2';
update connector_definition set multi_instance = true, tenant_update = 'ALLOWED' where key = 'vast';

-- The fields of a connector. external = shown to tenants; internal = platform administrators only.
create table connector_property (
  connector_key text not null references connector_definition(key) on delete cascade,
  key           text not null check (key ~ '^[a-z][a-z0-9_]{1,63}$'),
  label         text not null,
  description   text not null default '',
  value_type    text not null default 'STRING' check (value_type in ('STRING','NUMBER','BOOLEAN','SELECT','URL')),
  options       text not null default '[]',      -- JSON array, for SELECT
  default_value text,
  position      int  not null default 0,
  required      boolean not null default false,
  encrypted     boolean not null default false,
  scope         text not null default 'EXTERNAL' check (scope in ('EXTERNAL','INTERNAL')),
  primary key (connector_key, key),
  check (not encrypted or default_value is null)
);

insert into connector_property(connector_key, key, label, description, value_type, position, required, encrypted, scope, default_value) values
  ('mux', 'token_id', 'Access token ID', 'From the Mux environment''s access tokens', 'STRING', 1, true, true, 'INTERNAL', null),
  ('mux', 'token_secret', 'Access token secret', 'Shown once when the token is created in Mux', 'STRING', 2, true, true, 'INTERNAL', null),
  ('mux', 'webhook_secret', 'Webhook signing secret', 'Lets uploads turn ready on their own', 'STRING', 3, false, true, 'INTERNAL', null),
  ('mux', 'environment', 'Environment name', 'For people; e.g. Production', 'STRING', 4, false, false, 'EXTERNAL', null),
  ('mux', 'passthrough_tag', 'Passthrough tag', 'Only videos with this passthrough are imported (blank = all)', 'STRING', 5, false, false, 'INTERNAL', null),
  ('r2', 'worker_url', 'Edge Worker URL', 'Where the control plane publishes (Cloudflare Worker)', 'URL', 1, true, false, 'INTERNAL', null),
  ('r2', 'edge_secret', 'Edge secret', 'Shared secret between the control plane and the Worker', 'STRING', 2, true, true, 'INTERNAL', null),
  ('r2', 'public_url', 'Viewer address', 'Public address viewers play from', 'URL', 3, false, false, 'INTERNAL', null),
  ('vast', 'tag_url', 'VAST tag URL', 'The ad server''s VAST tag; macros are filled per viewer and break', 'URL', 1, true, true, 'EXTERNAL', null),
  ('vast', 'timeout_ms', 'Timeout (ms)', 'How long to wait for this ad server (100–5000)', 'NUMBER', 2, true, false, 'EXTERNAL', '1000');

-- One configured use of a connector: for one project (org_id) or, for PLATFORM-level connectors, the whole platform.
create table connector_instance (
  id            uuid primary key default gen_random_uuid(),
  connector_key text not null references connector_definition(key) on delete cascade,
  org_id        uuid references organization(id) on delete cascade,   -- null = platform level
  name          text not null default '',
  position      int  not null default 0,
  enabled       boolean not null default true,
  checked_at    timestamptz,
  check_error   text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create unique index connector_instance_single on connector_instance(connector_key, coalesce(org_id, '00000000-0000-0000-0000-000000000000'::uuid))
  where position = 0;   -- single-instance connectors use position 0; multi-instance ones use 1..n
create index connector_instance_project on connector_instance(org_id, connector_key, position);
alter table connector_instance enable row level security;
create policy tenant_isolation on connector_instance using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- The values; encrypted properties hold AES-GCM ciphertext (core.Secrets) and are never returned.
create table connector_value (
  instance_id  uuid not null references connector_instance(id) on delete cascade,
  org_id       uuid references organization(id) on delete cascade,  -- copy of the instance's, for row-level security
  property_key text not null,
  value        text not null,
  updated_at   timestamptz not null default now(),
  primary key (instance_id, property_key)
);
alter table connector_value enable row level security;
create policy tenant_isolation on connector_value using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));
