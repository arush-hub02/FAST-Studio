-- Ad delivery per ad server connection: Store in R2 and serve (default) or Direct from the ad server (its own HLS played
-- from its CDN). Direct is a feature the platform switches on per project; the pilot plan may grant it.
insert into capability(key, kind, name, description, category, built_in, group_key) values
  ('AD_DIRECT_DELIVERY', 'FEATURE', 'Direct ad delivery', 'Ad server connections may play the ad server''s own HLS directly (Direct delivery)', 'Advertising', true, 'AD_DEMAND');
insert into feature_permission values ('AD_DIRECT_DELIVERY', 'ADS_EDIT');
update subscription_plan set features = trim(both ',' from features || ',AD_DIRECT_DELIVERY') where key = 'pilot';

insert into connector_property(connector_key, key, label, description, value_type, options, position, required, encrypted, scope, default_value) values
  ('vast', 'delivery', 'Delivery', 'Store in R2 and serve, or play the ad server''s own HLS from its CDN (needs the Direct ad delivery feature)',
   'SELECT', '["STORE_R2","DIRECT"]', 3, false, false, 'EXTERNAL', 'STORE_R2');

-- What a creative's HLS offers (checked once per creative, again after a day): container, separate audio, heights,
-- piece lengths. Keyed like creative assets (ad system + creative id); HLS-only creatives have no asset row.
create table ad_direct_check (
  org_id     uuid not null references organization(id) on delete cascade,
  source_ref text not null,
  profile    jsonb not null,
  checked_at timestamptz not null default now(),
  primary key (org_id, source_ref)
);
alter table ad_direct_check enable row level security;
create policy tenant_isolation on ad_direct_check using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));
