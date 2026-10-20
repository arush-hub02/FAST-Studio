-- Real accounts without an email service: forced password change for temporary passwords,
-- and single-use, expiring password-reset links created by an administrator.

alter table app_user add column must_change_password boolean not null default false;
alter table app_user add column password_changed_at timestamptz;

create table password_reset (
  token_hash text primary key,            -- SHA-256 of the token; the token itself is never stored
  user_id uuid not null references app_user(id),
  created_by uuid references app_user(id),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  used_at timestamptz
);
create index password_reset_user on password_reset(user_id);

grant select, insert, update, delete on password_reset to faststudio_app;
