-- Programmes stream from the video platform they live on (docs/OVP_DELIVERY_PLAN.md); ads and slate stay on R2.

-- How one library video plays from its platform: neutral facts read from the platform's HLS playlists when the video
-- is prepared. segments_ms are the real segment lengths (the same in every quality level); duration_ms is their sum.
-- Signed playback lists no URL here: tokens are made per use from the connection's signing key.
create table asset_playback (
  asset_id    uuid primary key references asset(id) on delete cascade,
  org_id      uuid not null references organization(id) on delete cascade,
  provider    text not null,
  playback_id text not null check (playback_id ~ '^[A-Za-z0-9]{1,128}$'),
  signed      boolean not null default false,
  format      text not null default 'TS' check (format in ('TS', 'FMP4')),
  renditions  jsonb not null default '[]',
  segments_ms int[] not null,
  duration_ms bigint not null,
  checked_at  timestamptz not null default now()
);
create index asset_playback_org on asset_playback(org_id);
alter table asset_playback enable row level security;
create policy tenant_isolation on asset_playback using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- Where each channel's segment numbering stands: the media sequence number of the segment that starts at at_ms.
-- Programmes from a platform have their own segment lengths, so numbering can no longer be read off the clock; the
-- publisher moves this anchor forward hour by hour (util/cloudflare/Timeline). target_s never goes down (HLS rule).
create table channel_timeline (
  channel_id uuid primary key references channel(id) on delete cascade,
  org_id     uuid not null references organization(id) on delete cascade,
  at_ms      bigint not null,
  seq        bigint not null,
  target_s   int not null default 5,
  updated_at timestamptz not null default now()
);
create index channel_timeline_org on channel_timeline(org_id);
alter table channel_timeline enable row level security;
create policy tenant_isolation on channel_timeline using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

-- Signed playback on the Mux connector (optional): the signing key Mux issues for signed playback IDs.
insert into connector_property(connector_key, key, label, description, value_type, position, required, encrypted, scope, default_value) values
  ('mux', 'signing_key_id', 'Signing key ID', 'Only for signed playback: the ID of a Mux signing key', 'STRING', 6, false, false, 'INTERNAL', null),
  ('mux', 'signing_private_key', 'Signing private key', 'Only for signed playback: the base64 private key Mux shows once', 'STRING', 7, false, true, 'INTERNAL', null)
on conflict do nothing;
