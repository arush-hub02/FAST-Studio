import { useState } from 'react'
import { api } from '../../shared/api'
import { ErrorBox, InfoTip, Loading, useAction, useApi } from '../../shared/ui'

// Platform administrator's settings for one company's Mux account (Connectors page). Mux is a content source only:
// each video is imported once and re-cut into our 5-second segments, viewers never stream from Mux. The company
// never sees this; with the LIBRARY_SYNC feature it gets a provider-neutral "sync library" action.
type Account = { connected: boolean; tokenId: string | null; environment: string | null; passthroughTag: string | null; webhookSecretSet: boolean; webhookUrl: string }
type Sync = { videos: number; added: number }

const BLANK = { tokenId: '', tokenSecret: '', webhookSecret: '', environment: '', passthroughTag: '' }

export function MuxConnector({ orgId }: { orgId: string }) {
  const base = `/api/platform/orgs/${orgId}/connectors/mux`
  const account = useApi<Account>(base)
  const { busy, run } = useAction()
  const [form, setForm] = useState(BLANK)
  const [editing, setEditing] = useState(false)
  if (account.error) return <ErrorBox error={account.error} retry={account.reload} />
  if (!account.data) return <Loading rows={1} />
  const a = account.data
  const set = (k: keyof typeof BLANK) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value })

  const save = () =>
    run(() => api<Account>(base, { method: 'PUT', body: form }), a.connected ? 'Saved' : 'Mux connected')
      .then(r => { if (r) { account.setData(r); setForm(BLANK); setEditing(false) } })
  const sync = () =>
    run(() => api<Sync>(`${base}/sync`, { method: 'POST' }), r => `${r.videos} videos in Mux, ${r.added} newly added to the library`)

  return (
    <div className="stack" style={{ gap: 8, paddingTop: 8, borderTop: '1px solid var(--border)' }}>
      <div className="row" style={{ gap: 8 }}>
        <span className={`badge ${a.connected ? 'ok' : ''}`}>{a.connected ? 'Connected' : 'Not connected'}</span>
        <b>Mux video library</b>
        <InfoTip text="Imports this company's Mux assets that carry the passthrough tag, and sends its uploads straight to Mux. Each video is converted once into 5-second segments on our own storage, so viewers never stream from Mux. The company only ever sees 'Library', never Mux." />
      </div>

      {a.connected && !editing && (
        <>
          <div className="kv" style={{ gridTemplateColumns: '150px 1fr' }}>
            <span>Access token</span><span>Configured · write-only</span>
            <span>Environment</span><span>{a.environment || '-'}</span>
            <span>Passthrough tag</span><span className="mono small">{a.passthroughTag || 'none (imports every asset)'}</span>
            <span>Webhook</span><span>{a.webhookSecretSet ? 'Signing secret saved' : "No signing secret: uploads won't turn Ready on their own"}</span>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn" disabled={busy}
                    onClick={() => { setForm({ ...BLANK, environment: a.environment ?? '', passthroughTag: a.passthroughTag ?? '' }); setEditing(true) }}>Edit</button>
            <button className="btn primary" disabled={busy} onClick={sync}>Sync library now</button>
          </div>
        </>
      )}

      {(editing || !a.connected) && (
        <div className="stack" style={{ gap: 8 }}>
          <div className="row-wrap" style={{ gap: 8 }}>
            <input type="password" className="input mono" style={{ flex: '1 1 180px' }} autoComplete="new-password" aria-label="Mux access token ID"
                   placeholder={a.connected ? 'Access token ID (blank = keep)' : 'Access token ID'} value={form.tokenId} onChange={set('tokenId')} />
            <input className="input mono" style={{ flex: '1 1 180px' }} type="password" autoComplete="new-password" aria-label="Mux access token secret"
                   placeholder={a.connected ? 'Token secret (blank = keep)' : 'Token secret'} value={form.tokenSecret} onChange={set('tokenSecret')} />
          </div>
          <div className="row-wrap" style={{ gap: 8 }}>
            <input className="input" style={{ flex: '1 1 180px' }} aria-label="Mux environment name"
                   placeholder="Environment, e.g. Production" value={form.environment} onChange={set('environment')} />
            <input className="input mono" style={{ flex: '1 1 180px' }} aria-label="Passthrough tag"
                   placeholder="Passthrough tag, e.g. fast (blank = all assets)" value={form.passthroughTag} onChange={set('passthroughTag')} />
          </div>
          <input className="input mono" type="password" autoComplete="new-password" aria-label="Webhook signing secret"
                 placeholder={a.webhookSecretSet ? 'Webhook signing secret (blank = keep)' : 'Webhook signing secret'} value={form.webhookSecret} onChange={set('webhookSecret')} />
          <div className="row" style={{ gap: 8 }}>
            {a.connected && <button className="btn" disabled={busy} onClick={() => { setForm(BLANK); setEditing(false) }}>Cancel</button>}
            <button className="btn primary" disabled={busy || (!a.connected && (!form.tokenId || !form.tokenSecret))} onClick={save}>
              {a.connected ? 'Save' : 'Connect Mux'}
            </button>
          </div>
        </div>
      )}

      <span className="faint small">
        In the Mux environment add this webhook URL (events video.asset.created, ready, errored) and paste its signing secret above:{' '}
        <span className="mono" style={{ wordBreak: 'break-all' }}>{a.webhookUrl}</span>
      </span>
    </div>
  )
}
