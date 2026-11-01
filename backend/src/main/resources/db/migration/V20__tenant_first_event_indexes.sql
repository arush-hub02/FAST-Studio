-- The append-only event tables grow with viewing. Their reports always filter by organization first, so their indexes
-- lead with org_id: one project's reports read only that project's slice, however large other projects get.
create index if not exists ad_event_org_campaign on ad_event(org_id, campaign_id, event);
create index if not exists ad_event_org_session on ad_event(org_id, session_id, break_id);
create index if not exists ad_event_org_time on ad_event(org_id, occurred_at);
create index if not exists playback_session_org_channel on playback_session(org_id, channel_id, created_at);
create index if not exists ad_decision_org_time on ad_decision(org_id, created_at);
