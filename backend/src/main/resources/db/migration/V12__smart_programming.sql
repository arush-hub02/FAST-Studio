create table media_type (
  key text primary key,
  label text not null,
  kind text not null check (kind in ('MOVIE','EPISODE','SHORT','AD')),
  enabled boolean not null default true
);
insert into media_type(key, label, kind) values
 ('MOVIE','Movie','MOVIE'), ('EPISODE','Episode','EPISODE'), ('SHORT','Short','SHORT'),
 ('AD','Advertisement','AD'), ('PROMO','Promo','SHORT');
alter table organization add column media_entitlements text not null default 'MOVIE,EPISODE,SHORT,AD,PROMO';
alter table asset add column media_type text;
update asset set media_type = kind where kind <> 'SLATE';
alter table asset add foreign key (media_type) references media_type(key);

create table tenant_settings (
 org_id uuid primary key references organization(id),
 enabled_types text not null,
 default_type text not null references media_type(key),
 premiere_time time not null default '20:00',
 weekdays text not null default '1,2,3,4,5,6,7',
 personalized_ads boolean not null default false,
 viewer_secret text,
 revision int not null default 1
);
alter table tenant_settings enable row level security;
create policy tenant_isolation on tenant_settings using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));

create table programming_rule (
 id uuid primary key default gen_random_uuid(),
 org_id uuid not null,
 channel_id uuid not null,
 series text not null,
 start_date date not null,
 end_date date not null check (end_date >= start_date),
 premiere_time time not null,
 repeat_time time,
 weekdays text not null,
 start_season int not null check (start_season > 0),
 start_episode int not null check (start_episode > 0),
 loop boolean not null default false,
 enabled boolean not null default true,
 created_at timestamptz not null default now(),
 unique (org_id, id),
 foreign key (org_id, channel_id) references channel(org_id, id)
);
create table programming_occurrence (
 org_id uuid not null,
 rule_id uuid not null,
 air_date date not null,
 asset_id uuid not null,
 skipped boolean not null default false,
 primary key (org_id, rule_id, air_date),
 foreign key (org_id, rule_id) references programming_rule(org_id, id),
 foreign key (org_id, asset_id) references asset(org_id, id)
);
alter table schedule_entry add column rule_id uuid;
alter table schedule_entry add column rule_date date;
alter table schedule_entry add foreign key (org_id, rule_id) references programming_rule(org_id, id);
create index programming_channel on programming_rule(org_id, channel_id);
do $$ declare t text; begin
 foreach t in array array['programming_rule','programming_occurrence'] loop
  execute format('alter table %I enable row level security', t);
  execute format('create policy tenant_isolation on %I using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id))', t);
 end loop;
end $$;

alter table campaign add column audiences text not null default '';
alter table campaign add column frequency_cap int not null default 0 check (frequency_cap between 0 and 100);
alter table playback_session add column viewer_key text;
alter table playback_session add column audiences text not null default '';
alter table playback_session add column personalized_until timestamptz;
create index playback_viewer on playback_session(org_id, viewer_key) where viewer_key is not null;
create table ad_decision (
 org_id uuid not null,
 session_id uuid not null,
 break_id uuid not null,
 decision text not null,
 created_at timestamptz not null default now(),
 primary key (org_id, session_id, break_id),
 foreign key (org_id, session_id) references playback_session(org_id, id)
);
alter table ad_decision enable row level security;
create policy tenant_isolation on ad_decision using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));
