-- The per-project API allowance is meant to stop runaway scripts, not busy teams: open Library and Watch screens poll
-- every 3-5 seconds, so 20 people on a project already make several hundred requests a minute. Raise the default.
update property_definition set default_value = '6000', label = 'API requests per minute (per project, per server; stops runaway scripts)'
where key = 'limits.api_requests_per_minute' and default_value = '600';
