-- Configurable roles reuse supported server permissions and a fixed privilege ceiling.
-- Built-in owner/admin roles remain available for recovery; arbitrary permission names
-- never create new authorization behavior.
create table access_role (
  key text primary key,
  name text not null,
  kind text not null check(kind in ('CUSTOMER','PARTNER')),
  base_role text not null check(base_role in ('ADMIN','CONTENT_MANAGER','CHANNEL_OPERATOR','AD_OPS','ANALYST','PARTNER_ADMIN','PARTNER_VIEWER')),
  permissions text not null,
  enabled boolean not null default true,
  revision int not null default 0
);
alter table membership add column custom_role_key text references access_role(key);
