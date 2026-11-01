-- Per-project limits, so one busy project can't slow everyone else down. They are ordinary dynamic properties:
-- global default -> tenant value -> project override, set by platform administrators (TenantLimitsService reads them).
insert into property_definition(key, label, value_type, secret, project_override, enabled, options, default_value) values
  ('limits.api_requests_per_minute', 'API requests per minute (per project, per server)', 'NUMBER', false, true, true, '[]', '600'),
  ('limits.media_hours', 'Stored media hours (per project; archived videos excluded)', 'NUMBER', false, true, true, '[]', '500'),
  ('limits.processing_videos', 'Videos converting at the same time (per project)', 'NUMBER', false, true, true, '[]', '20');

-- the quota checks sum and count a project's assets by status
create index if not exists asset_org_status on asset(org_id, status);
