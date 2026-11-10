alter table asset add column slate_next_schedule boolean not null default false;
alter table asset add constraint asset_slate_activation_kind
    check (kind = 'SLATE' or not slate_next_schedule);
