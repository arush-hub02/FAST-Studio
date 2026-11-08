-- Tenant-wide roles: one role for a person across every project of a customer tenant, including projects added later.
-- The role is kept here; the app writes the matching membership row in each project (via_tenant = true), so sign-in,
-- tokens and every permission check keep reading memberships as before. A project role the person already had
-- directly (via_tenant = false) is never overwritten.
create table tenant_membership (
  tenant_id  uuid not null references customer_tenant(id) on delete cascade,
  user_id    uuid not null references app_user(id),
  role       text not null check (role in ('OWNER','ADMIN','CONTENT_MANAGER','CHANNEL_OPERATOR','AD_OPS','ANALYST')),
  created_at timestamptz not null default now(),
  primary key (tenant_id, user_id)
);
create index tenant_membership_user on tenant_membership(user_id);

alter table membership add column via_tenant boolean not null default false;
