-- Defence in depth for multitenancy: PostgreSQL row-level security on every tenant-owned table.
-- The application connects, then switches to the restricted role faststudio_app (no BYPASSRLS),
-- and TenantAwareDataSource stamps app.org_id (or app.bypass for trusted system work) on each connection.
-- organization, membership and app_user stay outside RLS: they are read to *establish* the tenant.

do $$
begin
  if not exists (select from pg_roles where rolname = 'faststudio_app') then
    create role faststudio_app nologin nosuperuser nobypassrls;
  end if;
end $$;

grant faststudio_app to current_user;
grant usage on schema public to faststudio_app;
grant select, insert, update, delete on all tables in schema public to faststudio_app;
grant usage, select on all sequences in schema public to faststudio_app;
alter default privileges in schema public grant select, insert, update, delete on tables to faststudio_app;
alter default privileges in schema public grant usage, select on sequences to faststudio_app;

create or replace function fs_tenant_ok(row_org uuid) returns boolean
language sql stable as $$
  select coalesce(current_setting('app.bypass', true), '') = 'on'
      or row_org = nullif(current_setting('app.org_id', true), '')::uuid
$$;

do $$
declare t text;
begin
  foreach t in array array['asset','rights_policy','channel','schedule_version','schedule_entry','campaign',
                           'destination','publication','playback_session','ad_event','alert']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('create policy tenant_isolation on %I using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id))', t);
  end loop;
end $$;

-- sign-in events have no tenant; they may be written but are only readable with bypass
alter table audit_event enable row level security;
create policy tenant_isolation on audit_event using (fs_tenant_ok(org_id)) with check (org_id is null or fs_tenant_ok(org_id));
