import { useState } from 'react'
import { api, SCOPES } from '../api'
import { useOrg } from '../app'
import { ago, Confirm, Empty, Icon, Loading, Modal, PageHead, Status, useAction, useApi, useToast } from '../ui'

type Client = { id: string; name: string; scopes: string[]; createdAt: string; lastUsedAt?: string; revokedAt?: string }
type Created = { clientId: string; clientSecret: string; scopes: string[]; tokenUrl: string; note: string }

/** Partner portal: API clients so the partner's systems can read shared channels by themselves. */
export default function PartnerApi() {
  const org = useOrg()
  const list = useApi<Client[]>(`/api/partners/${org.slug}/clients`)
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<Created | null>(null)
  const [revoking, setRevoking] = useState<Client | null>(null)
  const { run } = useAction()
  const admin = org.can('PARTNER_MANAGE')
  return (
    <div className="page">
      <PageHead title="API access" tip="Your systems sign in with a client ID and secret (OAuth 2.0 client credentials) and receive a 10-minute token."
        subtitle="Let your systems read shared channels automatically: guide updates, stream set-up, ad-break times."
        actions={admin && <button className="btn primary" onClick={() => setCreating(true)}><Icon name="plus" />New API client</button>} />
      <section className="card card-pad stack">
        <h2>How it works</h2>
        <ol className="stack" style={{ margin: 0, paddingLeft: 20, gap: 6 }}>
          <li>Create an API client and choose what it may read. Keep the secret in your system's secret store.</li>
          <li>Your system asks for a token: <code>POST /oauth/token</code> with <code>grant_type=client_credentials</code>, the client ID and secret. The token lasts 10 minutes.</li>
          <li>It calls the partner API with <code>Authorization: Bearer &lt;token&gt;</code>, for example <code>GET /api/partners/{org.orgId}/links</code> to list what is shared, then <code>…/sources/&#123;owner&#125;/channels/&#123;id&#125;/epg.xml</code> for the guide.</li>
        </ol>
        <p className="faint small">A client only ever sees what content owners share with {org.name} <b>and</b> its own scopes. Revoking it stops its tokens at once. Full reference: <a href="/partner-api.openapi.json" target="_blank" rel="noreferrer">OpenAPI document</a>.</p>
      </section>
      <section className="card">
        {!list.data ? <div className="card-pad"><Loading /></div> : list.data.length === 0 ? <Empty icon="key" title="No API clients yet" text="Create one when your systems should read the guide or stream set-up by themselves." /> : (
          <table className="table"><thead><tr><th>Client</th><th>May read</th><th>Last used</th><th>Status</th><th /></tr></thead>
            <tbody>{list.data.map(c => (
              <tr key={c.id}><td><b>{c.name}</b><div className="faint mono small">{c.id}</div></td>
                <td><div className="row-wrap">{c.scopes.map(k => <span key={k} className="chip">{SCOPES[k]?.label ?? k}</span>)}</div></td>
                <td className="small">{c.lastUsedAt ? ago(c.lastUsedAt) : 'Never'}</td>
                <td><Status value={c.revokedAt ? 'REVOKED' : 'ACTIVE'} /></td>
                <td>{admin && !c.revokedAt && <button className="btn sm ghost danger" onClick={() => setRevoking(c)}>Revoke</button>}</td></tr>))}</tbody></table>
        )}
      </section>
      {creating && <NewClient onClose={() => setCreating(false)} onDone={r => { setCreating(false); setCreated(r); list.reload() }} />}
      {created && <SecretModal created={created} onClose={() => setCreated(null)} />}
      {revoking && <Confirm danger title={`Revoke ${revoking.name}?`} confirmLabel="Revoke" text="Its tokens stop working at once and it can't get new ones."
        onClose={() => setRevoking(null)} onConfirm={() => run(() => api(`/api/partners/${org.slug}/clients/${revoking.id}/revoke`, { method: 'POST' }), 'Revoked').then(list.reload)} />}
    </div>
  )
}

function NewClient({ onClose, onDone }: { onClose: () => void; onDone: (c: Created) => void }) {
  const org = useOrg()
  const [name, setName] = useState('')
  const [scopes, setScopes] = useState<string[]>(['channels:read', 'epg:read'])
  const { busy, run } = useAction()
  return (
    <Modal title="New API client" subtitle="For one system, e.g. your TV-guide importer" onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy || !name.trim() || !scopes.length}
          onClick={() => run(() => api<Created>(`/api/partners/${org.slug}/clients`, { body: { name, scopes } })).then(r => r && onDone(r))}>Create</button></>}>
      <label className="field">Name<input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Guide importer" autoFocus /></label>
      <div className="field">It may read
        <div className="stack" style={{ gap: 6 }}>{Object.entries(SCOPES).map(([k, s]) => (
          <label key={k} className="row" style={{ alignItems: 'flex-start' }}>
            <input type="checkbox" style={{ width: 'auto', minHeight: 0, marginTop: 3 }} checked={scopes.includes(k)} onChange={() => setScopes(scopes.includes(k) ? scopes.filter(x => x !== k) : [...scopes, k])} />
            <span><b>{s.label}</b><span className="faint"> · {s.meaning}</span></span></label>))}</div></div>
    </Modal>
  )
}

function SecretModal({ created, onClose }: { created: Created; onClose: () => void }) {
  const org = useOrg()
  const toast = useToast()
  const origin = window.location.origin
  const curl = `curl -s -X POST ${origin}/oauth/token \\\n  -d grant_type=client_credentials \\\n  -d client_id=${created.clientId} \\\n  -d client_secret=${created.clientSecret}\n\ncurl -s ${origin}/api/partners/${org.orgId}/links \\\n  -H "Authorization: Bearer <access_token>"`
  return (
    <Modal wide title="Copy the secret now" subtitle={created.note} onClose={onClose} footer={<button className="btn primary" onClick={onClose}>I've stored it</button>}>
      <div className="kv"><span>Client ID</span><span className="mono">{created.clientId}</span><span>Client secret</span><span className="mono" style={{ wordBreak: 'break-all' }}>{created.clientSecret}</span></div>
      <div className="between"><b>Try it</b><button className="btn sm" onClick={() => navigator.clipboard.writeText(curl).then(() => toast('Copied', 'ok'))}>Copy</button></div>
      <pre className="mono" style={{ background: '#0f172a', color: '#e2e8f0', padding: 12, borderRadius: 10, whiteSpace: 'pre-wrap', margin: 0 }}>{curl}</pre>
    </Modal>
  )
}
