-- Platform-administration P3d: a project's ad waterfall (ad_source) becomes instances of the ad connectors.
-- DIRECT = our own direct-sold campaigns (no fields); VAST = an external ad server (tag_url sealed, timeout_ms).
-- Waterfall order = instance position (1..n, shared by all multi-instance connectors of a project). Ids, sealed tag
-- URLs (ciphertext unchanged), order, on/off and who manages each source are kept.

alter table connector_definition drop constraint if exists connector_definition_driver_check;
alter table connector_definition add constraint connector_definition_driver_check check (driver in ('MUX','VAST','R2','DIRECT','CUSTOM'));

insert into connector_definition(key, name, category, driver, description, multi_instance, tenant_update)
values ('direct', 'Direct-sold campaigns', 'ADS', 'DIRECT', 'Our own direct-sold campaigns in the ad waterfall', true, 'NOT_ALLOWED')
on conflict (key) do nothing;
-- Google Ad Manager: an external ad server reached through its VAST tag, so it uses the VAST adapter and the same field keys.
insert into connector_definition(key, name, category, driver, description, long_description, reference_link, multi_instance, tenant_update)
values ('gam', 'Google Ad Manager', 'ADS', 'VAST', 'Google''s ad server, asked through a VAST tag',
        'Paste the VAST tag of a Google Ad Manager video ad unit (Inventory → Ad units → Generate tags → Google Publisher Tag for video). Macros such as [CACHEBUSTER] are filled per viewer and break.',
        'https://support.google.com/admanager/answer/1068325', true, 'ALLOWED')
on conflict (key) do nothing;
insert into connector_property(connector_key, key, label, description, value_type, position, required, encrypted, scope, default_value) values
  ('gam', 'tag_url', 'VAST tag URL', 'The ad unit''s VAST tag from Google Ad Manager (https://pubads.g.doubleclick.net/gampad/ads?...)', 'URL', 1, true, true, 'EXTERNAL', null),
  ('gam', 'timeout_ms', 'Timeout (ms)', 'How long to wait for Google Ad Manager (100–5000)', 'NUMBER', 2, true, false, 'EXTERNAL', '1000')
on conflict do nothing;
insert into tenant_connector(tenant_id, connector_key) select id, k from customer_tenant cross join (values ('direct'), ('gam')) v(k) on conflict do nothing;

-- PLATFORM = set up by the platform administrator (tenants never see or change it); TENANT = the project's own.
alter table connector_instance add column managed_by text not null default 'PLATFORM' check (managed_by in ('PLATFORM','TENANT'));
create unique index connector_instance_one_direct on connector_instance(org_id) where connector_key = 'direct';

insert into connector_instance(id, connector_key, org_id, name, position, enabled, managed_by, created_at, updated_at)
select s.id, case s.kind when 'DIRECT' then 'direct' else 'vast' end, s.org_id, s.name,
       row_number() over (partition by s.org_id order by s.position, s.created_at), s.enabled, s.managed_by, s.created_at, s.created_at
from ad_source s;

insert into connector_value(instance_id, org_id, property_key, value)
select id, org_id, 'tag_url', tag_url from ad_source where kind = 'VAST' and tag_url is not null
union all
select id, org_id, 'timeout_ms', timeout_ms::text from ad_source where kind = 'VAST';

drop table ad_source;
