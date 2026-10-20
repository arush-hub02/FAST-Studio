-- Organization kinds, tenants and sign-in tokens (docs/PARTNER_ACCESS_AND_TOKENS.md, phases P1-P2).

-- CUSTOMER = content owner (every org so far), PARTNER = receives shared channels (phase P3), PLATFORM = us.
alter table organization add column kind text not null default 'CUSTOMER' check (kind in ('CUSTOMER', 'PARTNER', 'PLATFORM'));
-- the tenant (contract) an organization belongs to; one org = one tenant until a tenant holds several orgs
alter table organization add column tenant_id uuid;
update organization set tenant_id = id;
alter table organization alter column tenant_id set not null;

create function fs_org_tenant_default() returns trigger language plpgsql as $$
begin
  new.tenant_id := coalesce(new.tenant_id, new.id);
  return new;
end $$;
create trigger organization_tenant_default before insert on organization for each row execute function fs_org_tenant_default();

-- the platform organization: platform administrators act as it (audit rows, token "org" claim). The slug starts
-- with "_", which customer slugs can't, so it never clashes with a company's web address.
insert into organization(slug, name, country, timezone, plan, max_channels, kind)
values ('_platform', 'FAST Studio platform', 'IN', 'UTC', 'Platform', 0, 'PLATFORM');

-- bumped whenever what a person's token says becomes wrong (role, membership, password, suspension):
-- access tokens carrying an older number are refused at once
alter table app_user add column token_version int not null default 0;

-- refresh tokens: a 256-bit random value, stored only as SHA-256; one family per sign-in (the token's "sid").
-- Each use replaces the token; using a replaced one again revokes the whole family (it was stolen and replayed).
create table refresh_token (
  token_hash text primary key,
  family_id uuid not null,
  user_id uuid not null references app_user(id),
  active_org_id uuid references organization(id),
  family_started_at timestamptz not null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  replaced_at timestamptz,
  revoked_at timestamptz
);
create index refresh_token_family on refresh_token(family_id);
create index refresh_token_user on refresh_token(user_id) where revoked_at is null;

grant select, insert, update, delete on refresh_token to faststudio_app;
