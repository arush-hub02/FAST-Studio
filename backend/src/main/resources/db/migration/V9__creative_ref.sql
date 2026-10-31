-- Ad servers often sign creative URLs per request (Google does), so the URL can't identify a creative.
-- source_ref is the stable identity (ad system + UniversalAdId / creative id) a conditioned copy is reused by;
-- source_url stays the address to fetch it from.
alter table asset add column source_ref text;
drop index asset_source_url;
create unique index asset_source_ref on asset(org_id, source_ref) where source_ref is not null;
