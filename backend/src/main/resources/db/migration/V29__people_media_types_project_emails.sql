-- Platform-administration P2, P4 and P5 (docs/PLATFORM_ADMIN_PLAN.md).

-- P2: a platform administrator can switch a person's account off. Sign-in, token refresh and existing tokens stop at
-- once; memberships and history are kept, so switching it back on restores everything.
alter table app_user add column disabled_at timestamptz;

-- P4: a project's own media types, next to the platform's catalogue (media_type) it is entitled to. kind = the
-- scheduling behaviour, as in media_type. Keys never collide with the platform's.
create table project_media_type (
  org_id     uuid not null references organization(id) on delete cascade,
  key        text not null check (key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  label      text not null,
  kind       text not null check (kind in ('MOVIE','EPISODE','SHORT','AD')),
  enabled    boolean not null default true,
  created_at timestamptz not null default now(),
  primary key (org_id, key)
);
alter table project_media_type enable row level security;
-- A video may now use a project type too, so asset.media_type no longer points only at the platform catalogue; the
-- type is checked against what the project may use when the video is saved (MediaSettingsServiceV1.require).
alter table asset drop constraint if exists asset_media_type_fkey;
create policy tenant_isolation on project_media_type using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- P5: a project's own version of a platform email; without a row the platform's (or built-in) version is used.
create table project_email_template (
  org_id     uuid not null references organization(id) on delete cascade,
  key        text not null,
  subject    text,
  body       text not null,
  updated_by uuid references app_user(id),
  updated_at timestamptz not null default now(),
  primary key (org_id, key)
);
alter table project_email_template enable row level security;
create policy tenant_isolation on project_email_template using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- Faster sign-in and people lists: memberships by person (the primary key starts with org_id).
create index if not exists membership_user on membership(user_id);

