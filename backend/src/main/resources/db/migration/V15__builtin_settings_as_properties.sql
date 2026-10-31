-- Built-in programming/advertising settings become dynamic properties, so they follow the same
-- global default -> tenant value -> project override resolution as custom properties.
-- Product code reads them through TenantConfigurationServiceV1_0.effective(); keys are fixed there.
insert into property_definition(key, label, value_type, secret, project_override, enabled, options, default_value) values
  ('programming.premiere_time', 'Default premiere time (HH:MM, five-second boundary)', 'STRING', false, true, true, '[]', '"20:00"'),
  ('programming.weekdays', 'Default programming weekdays (1 = Monday … 7 = Sunday, comma separated)', 'STRING', false, true, true, '[]', '"1,2,3,4,5,6,7"'),
  ('ads.personalized', 'Personalized viewer advertising', 'BOOLEAN', false, true, true, '[]', 'false');

-- Keep every project's current values as project overrides. Values are stored as JSON text.
-- tenant_settings keeps these columns for rollback compatibility only; they are no longer read.
insert into project_property_value(org_id, property_key, value)
select org_id, 'programming.premiere_time',
       '"' || case when extract(second from premiere_time) = 0 then to_char(premiere_time, 'HH24:MI')
                   else to_char(premiere_time, 'HH24:MI:SS') end || '"'
from tenant_settings;

insert into project_property_value(org_id, property_key, value)
select org_id, 'programming.weekdays', '"' || weekdays || '"' from tenant_settings;

insert into project_property_value(org_id, property_key, value)
select org_id, 'ads.personalized', case when personalized_ads then 'true' else 'false' end from tenant_settings;
