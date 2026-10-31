-- Personal access tokens: long-lived credentials a person creates for scripts and AI assistants (MCP clients).
-- Each acts as its owner in ONE organization (customer or partner), never above the role ceiling chosen at creation
-- (role_key) and never above the owner's current role there. org_id null = platform scope (role_key PLATFORM_ADMIN),
-- only for platform administrators and only while they still are one. Only a SHA-256 hash of the token is stored; the token itself
-- (fsp_…) is shown once. expires_at null = never expires (the owner chose "no expiry").
create table personal_access_token (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references app_user(id) on delete cascade,
  org_id uuid references organization(id) on delete cascade,
  name text not null check (length(name) between 1 and 80),
  token_hash text not null unique,
  token_hint text not null,                -- first characters, so people can tell their tokens apart
  role_key text not null,                  -- Tenant.Role ceiling, e.g. ANALYST for read-only
  created_at timestamptz not null default now(),
  expires_at timestamptz,
  last_used_at timestamptz,
  revoked_at timestamptz,
  check ((org_id is null) = (role_key = 'PLATFORM_ADMIN'))
);
create index personal_access_token_user on personal_access_token(user_id);

grant select, insert, update, delete on personal_access_token to faststudio_app;
