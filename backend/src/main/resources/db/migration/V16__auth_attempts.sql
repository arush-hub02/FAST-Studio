-- Rate limiting for sign-in, token and password-reset requests, shared by every server instance and kept across restarts.
-- bucket names what is limited: "login-email:<sha256 of email>", "login-ip:<address>", "forgot-ip:<address>",
-- "client-ip:<address>". Emails are stored only as hashes. Rows older than a day are deleted by the app.
create table auth_attempt (
  bucket text not null,
  created_at timestamptz not null default now()
);
create index auth_attempt_bucket_time on auth_attempt (bucket, created_at);

grant select, insert, update, delete on auth_attempt to faststudio_app;
