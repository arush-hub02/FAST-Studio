-- House promos: the channel's own spots (idents, "coming up next"). Never sold; they only fill break time that
-- every ad source left open, instead of the standby slate.
alter table campaign add column house boolean not null default false;
