-- Platform-administration program, Stage 1: one catalogue of features and permissions with stable identifiers.
-- Built-in rows mirror what the server enforces in code (core.common.Feature, Tenant.Perm) and can't be removed.
-- Platform administrators add global rows (org_id null); a project's administrators add custom rows for that project
-- only. The frontend reads the effective keys from the project details answer and shows or hides things by key.
create table capability (
  key         text primary key check (key ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  kind        text not null check (kind in ('FEATURE','PERMISSION')),
  name        text not null,
  description text not null default '',
  category    text not null default 'General',
  org_id      uuid references organization(id) on delete cascade,   -- null = global catalogue; set = one project's own
  built_in    boolean not null default false,
  enabled     boolean not null default true,
  created_at  timestamptz not null default now(),
  check (not built_in or org_id is null)
);
create index capability_project on capability(org_id) where org_id is not null;

-- Global rows are readable by everyone; only system (platform) work writes them. Project rows follow the project.
alter table capability enable row level security;
create policy tenant_isolation on capability using (org_id is null or fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

insert into capability(key, kind, name, description, category, built_in) values
  ('AD_WATERFALL_VIEW', 'FEATURE', 'See the ad demand waterfall', 'Company sees its ad demand waterfall (names and order only, no tag URLs)', 'Advertising', true),
  ('AD_OWN_TAGS', 'FEATURE', 'Own ad tags', 'Company may add its own VAST ad tags (appended after the platform''s sources)', 'Advertising', true),
  ('LIBRARY_SYNC', 'FEATURE', 'Library self-sync', 'Company may sync its connected video library itself (otherwise only the platform syncs it)', 'Media', true),
  ('VIEW', 'PERMISSION', 'View', 'See the project''s screens and data', 'General', true),
  ('ASSETS_EDIT', 'PERMISSION', 'Edit videos', 'Add, upload, edit, prepare and archive videos', 'Media', true),
  ('RIGHTS_EDIT', 'PERMISSION', 'Edit rights', 'Change where and when videos may play', 'Media', true),
  ('CHANNELS_EDIT', 'PERMISSION', 'Edit channels', 'Create and configure channels', 'Channels', true),
  ('SCHEDULE_EDIT', 'PERMISSION', 'Edit schedules', 'Change draft schedules and programming rules', 'Channels', true),
  ('SCHEDULE_PUBLISH', 'PERMISSION', 'Publish schedules', 'Put a schedule on air', 'Channels', true),
  ('ADS_EDIT', 'PERMISSION', 'Edit advertising', 'Campaigns, ad breaks and the project''s own ad tags', 'Advertising', true),
  ('DISTRIBUTION_EDIT', 'PERMISSION', 'Edit distribution', 'Destinations and partner sharing', 'Distribution', true),
  ('ALERTS_EDIT', 'PERMISSION', 'Handle alerts', 'Acknowledge and resolve monitoring alerts', 'Monitoring', true),
  ('TEAM_MANAGE', 'PERMISSION', 'Manage team and settings', 'Invite people, change roles, project settings, custom features and permissions', 'Team', true),
  ('PARTNER_MANAGE', 'PERMISSION', 'Manage partner access', 'Partner organizations: their users and API clients', 'Team', true);
