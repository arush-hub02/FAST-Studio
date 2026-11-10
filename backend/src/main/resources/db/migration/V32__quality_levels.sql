-- Which quality levels a tenant's videos, ads and channels use (services/media/QualityLevels): 360p … 2160p, comma
-- separated, lowest first. Tenant-wide (no project override); set by platform administrators only.
insert into property_definition(key, label, value_type, secret, project_override, enabled, options, default_value) values
  ('media.quality_levels', 'Quality levels (any of 360p, 540p, 720p, 1080p, 1440p, 2160p)', 'STRING', false, false, true, '[]', '"360p,540p,720p"')
on conflict (key) do nothing;
