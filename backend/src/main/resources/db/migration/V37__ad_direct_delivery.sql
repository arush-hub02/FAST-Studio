-- Ad delivery per ad server connection: Store in R2 and serve (default) or Direct from the ad server (its own HLS played
-- from its CDN). Direct is a feature the platform switches on per project; the pilot plan may grant it.
insert into capability(key, kind, name, description, category, built_in, group_key) values
  ('AD_DIRECT_DELIVERY', 'FEATURE', 'Direct ad delivery', 'Ad server connections may play the ad server''s own HLS directly (Direct delivery)', 'Advertising', true, 'AD_DEMAND');
insert into feature_permission values ('AD_DIRECT_DELIVERY', 'ADS_EDIT');
update subscription_plan set features = trim(both ',' from features || ',AD_DIRECT_DELIVERY') where key = 'pilot';

insert into connector_property(connector_key, key, label, description, value_type, options, position, required, encrypted, scope, default_value) values
  ('vast', 'delivery', 'Delivery', 'Store in R2 and serve, or play the ad server''s own HLS from its CDN (needs the Direct ad delivery feature)',
   'SELECT', '["STORE_R2","DIRECT"]', 3, false, false, 'EXTERNAL', 'STORE_R2');

-- What a creative's HLS offers (checked once per creative, again after a day): container, separate audio, heights, piece lengths.
alter table asset add column direct_check jsonb, add column direct_checked_at timestamptz;
