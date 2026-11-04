-- Platform-administration redesign (docs/PLATFORM_ADMIN_PLAN.md, 2026-11-02), modelled on the v2 CMS ops portal:
-- Module ─ Feature group ─ Feature ─ uses → Permission(s); roles (global or one project's) grant permissions; a project
-- links global features. Features and permissions stay rows of `capability` (kind FEATURE / PERMISSION), now global only.

create table module (
  key         text primary key check (key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  name        text not null,
  description text not null default '',
  position    int  not null default 0,
  enabled     boolean not null default true
);

create table feature_group (
  key         text primary key check (key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  module_key  text not null references module(key),
  name        text not null,
  description text not null default '',
  position    int  not null default 0,
  published   boolean not null default true
);

alter table capability
  add column group_key  text references feature_group(key),   -- features only
  add column is_default boolean not null default false,       -- linked by "add default features"
  add column hidden     boolean not null default false,       -- internal: not offered to projects
  add column beta       boolean not null default false;

-- The permissions a feature uses (what the role editor shows under that feature).
create table feature_permission (
  feature_key    text not null references capability(key) on delete cascade,
  permission_key text not null references capability(key) on delete cascade,
  primary key (feature_key, permission_key)
);

insert into module(key, name, description, position) values
  ('MEDIA', 'Media', 'Videos, library and rights', 1),
  ('CHANNELS', 'Channels', 'Channels, schedules and programming', 2),
  ('ADVERTISING', 'Advertising', 'Campaigns and ad demand', 3),
  ('DISTRIBUTION', 'Distribution', 'Destinations and partners', 4),
  ('ADMINISTRATION', 'Administration', 'Team, settings and monitoring', 5);
insert into feature_group(key, module_key, name, position) values
  ('LIBRARY', 'MEDIA', 'Library', 1), ('RIGHTS', 'MEDIA', 'Rights', 2),
  ('CHANNEL_SETUP', 'CHANNELS', 'Channel setup', 1), ('SCHEDULING', 'CHANNELS', 'Scheduling', 2),
  ('CAMPAIGNS', 'ADVERTISING', 'Campaigns', 1), ('AD_DEMAND', 'ADVERTISING', 'Ad demand', 2),
  ('DESTINATIONS', 'DISTRIBUTION', 'Destinations and partners', 1),
  ('TEAM', 'ADMINISTRATION', 'Team and settings', 1), ('MONITORING', 'ADMINISTRATION', 'Monitoring', 2);

-- Built-in features: one per screen area, each with the permissions it uses. The three switchable ones keep their keys.
update capability set group_key = 'LIBRARY' where key = 'LIBRARY_SYNC';
update capability set group_key = 'AD_DEMAND' where key in ('AD_WATERFALL_VIEW', 'AD_OWN_TAGS');
insert into feature_permission values
  ('LIBRARY_SYNC', 'ASSETS_EDIT'), ('AD_WATERFALL_VIEW', 'VIEW'), ('AD_OWN_TAGS', 'ADS_EDIT');

-- Project-made entries (P1) become global: a project's enabled feature stays on for it by being linked.
update organization o set features = trim(both ',' from coalesce(o.features, '') || ',' || c.key)
from capability c where c.org_id = o.id and c.kind = 'FEATURE' and c.enabled;
update capability set org_id = null where org_id is not null;

-- Roles: global (org_id null) or one project's own. Global roles are readable by everyone; only system work writes them.
alter table access_role add column org_id uuid references organization(id) on delete cascade;
alter table access_role enable row level security;
create policy tenant_isolation on access_role using (org_id is null or fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));
