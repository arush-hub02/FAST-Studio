import { useState } from 'react'
import { api } from '../../shared/api'
import { ErrorBox, InfoTip, Loading, fmtDayTime, useAction, useApi } from '../../shared/ui'

// Platform administrator's settings for one company's Mux account (Connectors page). Mux is a content source only:
// each video is imported once and re-cut into our 5-second segments, viewers never stream from Mux. The company
// never sees this; with the LIBRARY_SYNC feature it gets a provider-neutral "sync library" action.
// Sync runs in the background and resumes after interruptions; it adds videos as Drafts (catalogue only), and the
// company prepares the ones it wants to air.
type Account = {
  connected: boolean; tokenId: string | null; environment: string | null; passthroughTag: string | null; webhookSecretSet: boolean; webhookUrl: string
  checkedAt: string | null; checkError: string | null; healthy: boolean | null
}
type Sync = {
  state: string; videos: number; added: number; pages: number; complete: boolean
  startedAt: string | null; finishedAt: string | null; lastError: string | null; nextRunAt: string | null
}

const BLANK = { tokenId: '', tokenSecret: '', webhookSecret: '', environment: '', passthroughTag: '' }
const ACTIVE = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']
const STATE: Record<string, string> = {
  NEVER: 'Never synced', QUEUED: 'Waiting to start', RUNNING: 'Syncing…', CANCEL_REQUESTED: 'Stopping…',
  SUCCEEDED: 'Finished', FAILED: 'Failed', CANCELLED: 'Cancelled',
}

export function MuxConnector({ orgId }: { orgId: string }) {
  const base = `/api/platform/orgs/${orgId}/connectors/mux`
  const account = useApi<Account>(base)
  const [syncing, setSyncing] = useState(false)
  // polls only while a sync is queued or running
  const status = useApi<Sync>(`${base}/sync`, [], syncing ? 3000 : undefined)
  const { busy, run } = useAction()
  const [form, setForm] = useState(BLANK)
  const [editing, setEditing] = useState(false)
  const active = !!status.data && ACTIVE.includes(status.data.state)
  if (active !== syncing) setSyncing(active)
  if (account.error) return <ErrorBox error={account.error} retry={account.reload} />
  if (!account.data) return <Loading rows={1} />
  const a = account.data
  const s = status.data
  const set = (k: keyof typeof BLANK) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value })

  const save = () =>
    run(() => api<Account>(base, { method: 'PUT', body: form }), a.connected ? 'Saved' : 'Mux connected')
      .then(r => { if (r) { account.setData(r); setForm(BLANK); setEditing(false) } })
  const check = () =>
    run(() => api<Account>(`${base}/check`, { method: 'POST' }), r => r.healthy ? 'Connection works' : `Connection failed: ${r.checkError}`)
      .then(r => { if (r) account.setData(r) })
  const sync = () =>
    run(() => api<Sync>(`${base}/sync`, { method: 'POST' }), 'Sync started: new videos are added as drafts')
      .then(r => { if (r) status.setData(r) })

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
            <span>Connection check</span>
            <span>
              {a.healthy == null ? 'Not checked yet' : a.healthy ? `Works · ${fmtDayTime(a.checkedAt)}` : `Failed · ${fmtDayTime(a.checkedAt)}: ${a.checkError}`}
            </span>
            <span>Library sync</span>
            <span>
              {!s ? '…' : <>
                {STATE[s.state] ?? s.state}
                {s.state !== 'NEVER' && ` · ${s.videos} videos seen, ${s.added} added as drafts, ${s.pages} pages`}
                {s.complete && s.finishedAt && ` · completed ${fmtDayTime(s.finishedAt)}`}
                {s.lastError && <span className="faint"> · {s.lastError}{s.nextRunAt ? ` (retries ${fmtDayTime(s.nextRunAt)})` : ''}</span>}
              </>}
            </span>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn" disabled={busy}
                    onClick={() => { setForm({ ...BLANK, environment: a.environment ?? '', passthroughTag: a.passthroughTag ?? '' }); setEditing(true) }}>Edit</button>
            <button className="btn" disabled={busy} onClick={check}>Check connection</button>
            <button className="btn primary" disabled={busy || active} onClick={sync}>{active ? 'Sync running…' : 'Sync library now'}</button>
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
        In the Mux environment add this webhook URL (events video.asset.created, ready, errored, deleted) and paste its signing secret above:{' '}
        <span className="mono" style={{ wordBreak: 'break-all' }}>{a.webhookUrl}</span>
      </span>
    </div>
  )
}
