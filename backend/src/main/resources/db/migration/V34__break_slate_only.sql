alter table schedule_entry add column slate_only boolean not null default false;
alter table schedule_entry add constraint schedule_entry_slate_only_break
    check (kind = 'AD_BREAK' or not slate_only);
