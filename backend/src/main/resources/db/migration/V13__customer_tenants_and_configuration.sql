-- Customer accounts own projects. Legacy CUSTOMER organization IDs remain project IDs,
-- preserving existing content FKs, RLS, memberships, public links and partner sharing.
create table subscription_plan (
  key text primary key,
  name text not null,
  enabled boolean not null default true,
  max_projects int not null check (max_projects between 1 and 10000),
  max_channels int not null check (max_channels between 0 and 100000),
  monthly_price numeric(14,2) not null default 0 check (monthly_price >= 0),
  currency text not null default 'USD',
  features text not null default ''
);
insert into subscription_plan(key,name,max_projects,max_channels,features)
values ('pilot','Pilot',10,100,'LIBRARY_SYNC,AD_WATERFALL_VIEW,AD_OWN_TAGS');

create table customer_tenant (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  name text not null,
  status text not null default 'ACTIVE' check (status in ('ACTIVE','SUSPENDED')),
  plan_key text not null default 'pilot' references subscription_plan(key),
  subscription_status text not null default 'TRIAL' check (subscription_status in ('TRIAL','ACTIVE','PAUSED','CANCELLED')),
  revision int not null default 0,
  created_at timestamptz not null default now()
);
insert into customer_tenant(id,slug,name,status,created_at)
select id,slug,name,status,created_at from organization where kind='CUSTOMER';

drop trigger organization_tenant_default on organization;
drop function fs_org_tenant_default();
alter table organization alter column tenant_id drop not null;
update organization set tenant_id=null where kind<>'CUSTOMER';
alter table organization add constraint organization_customer_tenant_fk foreign key (tenant_id) references customer_tenant(id);
alter table organization add constraint organization_customer_tenant_kind check ((kind='CUSTOMER') = (tenant_id is not null));
create index organization_customer_tenant on organization(tenant_id) where tenant_id is not null;

-- Compatibility for legacy creation/seeding callers: a new standalone customer project
-- gets a real customer account; partner/platform identities can never own one.
create function fs_project_tenant_default() returns trigger language plpgsql as $$
begin
  if new.kind='CUSTOMER' then
    if new.tenant_id is null then
      insert into customer_tenant(id,slug,name,status) values(new.id,new.slug,new.name,new.status);
      new.tenant_id := new.id;
    end if;
  else
    new.tenant_id := null;
  end if;
  return new;
end $$;
create trigger organization_customer_default before insert on organization for each row execute function fs_project_tenant_default();

create table connector_definition (
  key text primary key,
  name text not null,
  category text not null check (category in ('MEDIA','ADS','STORAGE','OTHER')),
  driver text not null default 'CUSTOM' check (driver in ('MUX','VAST','R2','CUSTOM')),
  enabled boolean not null default true,
  description text not null default ''
);
insert into connector_definition(key,name,category,driver,description) values
('mux','Mux','MEDIA','MUX','Video library, uploads and encoding'),
('vast','VAST ad servers','ADS','VAST','Project ad demand waterfall'),
('r2','Cloudflare R2','STORAGE','R2','Media origin and Cloudflare delivery');

create table tenant_connector (
  tenant_id uuid not null references customer_tenant(id),
  connector_key text not null references connector_definition(key),
  enabled boolean not null default true,
  primary key(tenant_id,connector_key)
);
insert into tenant_connector(tenant_id,connector_key) select t.id,c.key from customer_tenant t cross join connector_definition c;

create table project_connector (
  org_id uuid not null references organization(id),
  connector_key text not null references connector_definition(key),
  enabled boolean not null,
  primary key(org_id,connector_key)
);
alter table project_connector enable row level security;
create policy project_isolation on project_connector using(fs_tenant_ok(org_id)) with check(fs_tenant_ok(org_id));

create table property_definition (
  key text primary key,
  label text not null,
  value_type text not null check (value_type in ('STRING','NUMBER','BOOLEAN','SELECT')),
  connector_key text references connector_definition(key),
  secret boolean not null default false,
  project_override boolean not null default true,
  enabled boolean not null default true,
  options text not null default '[]',
  default_value text,
  revision int not null default 0
);
create table tenant_property_value (
  tenant_id uuid not null references customer_tenant(id),
  property_key text not null references property_definition(key),
  value text not null,
  primary key(tenant_id,property_key)
);
create table project_property_value (
  org_id uuid not null references organization(id),
  property_key text not null references property_definition(key),
  value text not null,
  primary key(org_id,property_key)
);
alter table project_property_value enable row level security;
create policy project_isolation on project_property_value using(fs_tenant_ok(org_id)) with check(fs_tenant_ok(org_id));

-- New tenants inherit only these initial supported connectors, not future definitions.
create function fs_tenant_connector_defaults() returns trigger language plpgsql as $$
begin
  insert into tenant_connector(tenant_id,connector_key)
  select new.id,key from connector_definition where key in ('mux','vast','r2');
  return new;
end $$;
create trigger customer_tenant_connectors after insert on customer_tenant for each row execute function fs_tenant_connector_defaults();

-- Tenant-level settings are visible only while operating a project of that tenant.
create function fs_customer_tenant_ok(row_tenant uuid) returns boolean language sql stable as $$
  select coalesce(current_setting('app.bypass',true),'')='on' or exists (
    select 1 from organization where tenant_id=row_tenant and id=nullif(current_setting('app.org_id',true),'')::uuid
  )
$$;
alter table tenant_property_value enable row level security;
create policy customer_isolation on tenant_property_value using(fs_customer_tenant_ok(tenant_id)) with check(fs_customer_tenant_ok(tenant_id));
alter table tenant_connector enable row level security;
create policy customer_isolation on tenant_connector using(fs_customer_tenant_ok(tenant_id)) with check(fs_customer_tenant_ok(tenant_id));

-- RLS keeps content project-scoped. This narrowly scoped aggregate permits a tenant
-- allowance check without making sibling-project channel rows readable.
create function fs_customer_channel_count(row_tenant uuid) returns bigint
language sql stable security definer set search_path=public,pg_temp as $$
  select case when fs_customer_tenant_ok(row_tenant) then (
    select count(*) from channel c join organization o on o.id=c.org_id
    where o.tenant_id=row_tenant and c.status<>'ARCHIVED'
  ) else 0 end
$$;
