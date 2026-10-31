-- Connectors (content platforms, ad servers, storage) are managed by the platform administrator.
-- What a customer company may see or do with them is switched on per company (core.Feature).
alter table organization add column features text not null default '';

-- Who manages an ad source: the platform (default) or the company itself (only with the AD_OWN_TAGS feature).
alter table ad_source add column managed_by text not null default 'PLATFORM' check (managed_by in ('PLATFORM','TENANT'));
