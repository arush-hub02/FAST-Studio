-- Connector marketplace P3c (docs/PLATFORM_ADMIN_PLAN.md): a project's video-library credentials move from
-- media_connection into its connector connection. Ciphertexts are copied unchanged (same server key), so nothing is
-- decrypted here. media_connection keeps only the provider and the library-sync checkpoint; the health check moves to
-- the connection (connector_instance.checked_at / check_error).

insert into connector_instance(connector_key, org_id, name, position, enabled)
select m.provider, m.org_id, d.name, 0, true
from media_connection m join connector_definition d on d.key = m.provider
on conflict do nothing;

insert into connector_value(instance_id, org_id, property_key, value)
select i.id, m.org_id, v.k, v.val
from media_connection m
join connector_instance i on i.connector_key = m.provider and i.org_id = m.org_id and i.position = 0
cross join lateral (values ('token_id', m.token_id), ('token_secret', m.token_secret), ('webhook_secret', m.webhook_secret),
                           ('environment', nullif(m.environment, '')), ('passthrough_tag', nullif(m.passthrough_tag, ''))) v(k, val)
join connector_property p on p.connector_key = m.provider and p.key = v.k
where v.val is not null
on conflict do nothing;

update connector_instance i set checked_at = m.checked_at, check_error = m.check_error
from media_connection m
where i.connector_key = m.provider and i.org_id = m.org_id and i.position = 0;

alter table media_connection
  drop column token_id, drop column token_secret, drop column webhook_secret,
  drop column environment, drop column passthrough_tag, drop column checked_at, drop column check_error;
