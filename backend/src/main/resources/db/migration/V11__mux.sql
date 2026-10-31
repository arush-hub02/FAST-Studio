-- Content library connector (HANDOVER §4.5, §7.1): each tenant's video-platform account, set only by a platform
-- administrator. token_id, token_secret and webhook_secret hold AES-GCM ciphertext (core.Secrets), never plaintext.
-- Imported videos are asset rows with source_ref = 'mux:{asset id}'; a direct upload still in flight is
-- 'mux-upload:{upload id}' until the provider reports the asset it became.
create table mux_account (
  org_id uuid primary key references organization(id),
  token_id text not null,
  token_secret text not null,
  webhook_secret text,
  environment text not null default '',
  passthrough_tag text not null default '',
  updated_at timestamptz not null default now()
);

alter table mux_account enable row level security;
create policy tenant_isolation on mux_account using (fs_tenant_ok(org_id)) with check (fs_tenant_ok(org_id));
