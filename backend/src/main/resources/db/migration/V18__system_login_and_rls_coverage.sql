-- Cross-tenant system work (job recovery, alert scans, edge sync, platform administration) no longer switches row-level
-- security off with a session setting the app could set on any connection. It runs on its own pool, which logs in as a
-- member of faststudio_system; ordinary request connections log in as a member of faststudio_app only, cannot become
-- the system role, and are no longer opened as the database owner. The login roles and their passwords are maintained
-- at start-up by DatabaseLogins (passwords derived from the server's secrets key, never stored here).

do $$
begin
  if not exists (select from pg_roles where rolname = 'faststudio_system') then
    create role faststudio_system nologin nosuperuser nobypassrls;
  end if;
end $$;
grant faststudio_app to faststudio_system;   -- same table rights; only the row policies treat it differently

-- session_user is the login: SET ROLE and SECURITY DEFINER functions cannot change it.
create or replace function fs_system() returns boolean language sql stable as $$
  select pg_has_role(session_user, 'faststudio_system', 'MEMBER')
$$;

create or replace function fs_tenant_ok(row_org uuid) returns boolean language sql stable as $$
  select fs_system() or row_org = nullif(current_setting('app.org_id', true), '')::uuid
$$;

create or replace function fs_customer_tenant_ok(row_tenant uuid) returns boolean language sql stable as $$
  select fs_system() or exists (
    select 1 from organization where tenant_id = row_tenant and id = nullif(current_setting('app.org_id', true), '')::uuid
  )
$$;

-- Tables that carry an organization but had no row policy yet.
alter table api_client enable row level security;
create policy tenant_isolation on api_client using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- A share is visible to both sides: the customer that shares and the partner that receives.
alter table partner_link enable row level security;
create policy tenant_isolation on partner_link using (fs_tenant_ok(owner_org_id) or fs_tenant_ok(partner_org_id))
  with check (fs_tenant_ok(owner_org_id) or fs_tenant_ok(partner_org_id));   -- the partner accepts it
alter table partner_link_channel enable row level security;
create policy tenant_isolation on partner_link_channel
  using (fs_tenant_ok(org_id) or exists (select 1 from partner_link l where l.id = link_id and fs_tenant_ok(l.partner_org_id)))
  with check (fs_tenant_ok(org_id));

-- email_message stays outside: it is the platform outbox (org_id is context only), no tenant API reads it, and mail
-- is queued inside other transactions of any scope.
