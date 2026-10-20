-- Partner organizations (docs/PARTNER_ACCESS_AND_TOKENS.md phases P3-P4): a customer shares chosen channels and
-- scopes with a partner org, read-only; partner machines use API clients. These tables establish access, so like
-- membership they are outside row-level security; every query names the owner or partner org explicitly.

alter table membership drop constraint membership_role_check;
alter table membership add constraint membership_role_check check (role in
  ('OWNER','ADMIN','CONTENT_MANAGER','CHANNEL_OPERATOR','AD_OPS','ANALYST','PARTNER_ADMIN','PARTNER_VIEWER'));

create table partner_link (
  id uuid primary key default gen_random_uuid(),
  owner_org_id uuid not null references organization(id),      -- the customer that shares
  partner_org_id uuid not null references organization(id),    -- the partner that receives
  all_channels boolean not null default false,                 -- true: every channel the owner has, now and later
  scopes text not null,                                         -- space-separated, e.g. 'channels:read epg:read'
  destination_id uuid,                                          -- the owner's destination record for this partner
  status text not null default 'INVITED' check (status in ('INVITED','ACTIVE','REVOKED')),
  note text not null default '',
  created_by uuid references app_user(id),
  created_at timestamptz not null default now(),
  accepted_by uuid references app_user(id),
  accepted_at timestamptz,
  revoked_by uuid references app_user(id),
  revoked_at timestamptz,
  expires_at timestamptz,
  check (owner_org_id <> partner_org_id),
  foreign key (owner_org_id, destination_id) references destination(org_id, id)
);
-- one open sharing per customer and partner (a revoked one can be replaced)
create unique index partner_link_open on partner_link(owner_org_id, partner_org_id) where status <> 'REVOKED';
create index partner_link_partner on partner_link(partner_org_id) where status <> 'REVOKED';

create table partner_link_channel (
  link_id uuid not null references partner_link(id) on delete cascade,
  org_id uuid not null,
  channel_id uuid not null,
  primary key (link_id, channel_id),
  foreign key (org_id, channel_id) references channel(org_id, id)   -- only the owner's own channels can be shared
);

-- machine access for partners: the secret is shown once and stored as SHA-256 (it is 256 random bits)
create table api_client (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references organization(id),
  name text not null,
  secret_hash text not null,
  scopes text not null,
  created_by uuid references app_user(id),
  created_at timestamptz not null default now(),
  last_used_at timestamptz,
  revoked_at timestamptz
);
create index api_client_org on api_client(org_id);

-- partner reads are recorded in the owner's audit log together with the partner that read
alter table audit_event add column via_org_id uuid references organization(id);

grant select, insert, update, delete on partner_link, partner_link_channel, api_client to faststudio_app;
