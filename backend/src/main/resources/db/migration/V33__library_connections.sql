-- A project can have more than one video-library connection (e.g. two Mux accounts). Each connection is a
-- connector_instance; every library video remembers the connection it lives in, and each connection syncs on its own.

-- which connection a library video (or a direct upload in flight) belongs to; null = not from a library
alter table asset add column connection_id uuid;
update asset a set connection_id = (
    select i.id from connector_instance i
    where i.org_id = a.org_id and i.connector_key = split_part(split_part(a.source_ref, ':', 1), '-', 1)
    order by i.position, i.id limit 1)
where a.source_ref like 'mux:%' or a.source_ref like 'mux-upload:%';

-- sync state per connection instead of per project
alter table media_connection add column instance_id uuid;
update media_connection m set instance_id = (
    select i.id from connector_instance i
    where i.org_id = m.org_id and i.connector_key = m.provider order by i.position, i.id limit 1);
delete from media_connection where instance_id is null;
alter table media_connection drop constraint mux_account_pkey;
alter table media_connection alter column instance_id set not null;
alter table media_connection add primary key (org_id, instance_id);

-- one running sync per connection
alter table media_job add column connection_id uuid;
update media_job j set connection_id = m.instance_id from media_connection m where j.kind = 'SYNC' and m.org_id = j.org_id;
drop index media_job_one_sync;
create unique index media_job_one_sync on media_job(org_id, connection_id) where kind = 'SYNC' and state in ('QUEUED','RUNNING','CANCEL_REQUESTED');

-- a project may connect more than one Mux account
update connector_definition set multi_instance = true where key = 'mux';
