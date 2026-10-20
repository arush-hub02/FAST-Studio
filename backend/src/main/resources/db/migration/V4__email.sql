-- Email: customisable templates (platform-wide overrides of the built-in defaults) and an outbox that is
-- both the delivery queue and the "Sent emails" log. Platform tables, outside row-level security like app_user;
-- only platform administrators read them (WebConfig guards /api/platform/**).

create table email_template (
  key text primary key,                   -- e.g. 'password_reset'; defaults live in code (mail.EmailTemplates)
  subject text,
  body text not null,
  updated_by uuid references app_user(id),
  updated_at timestamptz not null default now()
);

create table email_message (
  id uuid primary key default gen_random_uuid(),
  org_id uuid references organization(id),
  template text not null,
  to_email text not null,
  subject text not null,                  -- what the log shows: one-time passwords and links replaced by ••••••
  body_text text not null,
  body_html text not null,
  pending_subject text,                   -- the real message, kept only until it is delivered
  pending_text text,
  pending_html text,
  status text not null default 'QUEUED' check (status in ('QUEUED', 'SENT', 'SAVED', 'FAILED')),
  detail text,
  attempts int not null default 0,
  created_at timestamptz not null default now(),
  sent_at timestamptz
);
create index email_message_recent on email_message(created_at desc);
create index email_message_queued on email_message(status) where status = 'QUEUED';

grant select, insert, update, delete on email_template, email_message to faststudio_app;
