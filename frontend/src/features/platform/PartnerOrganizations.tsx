import { useState } from 'react'
import { api } from '../../shared/api'
import { Confirm, fmtDate, Icon, Loading, Modal, PageHead, Status, useAction, useApi } from '../../shared/ui'
import { ResetLinkModal } from '../../shared/components/ResetLinkModal'

type Org = { id: string; slug: string; name: string; status: string; country: string; timezone: string; plan: string; maxChannels: number; members: number; channels: number; liveChannels: number; videos: number; sessions: number; createdAt: string; kind: 'CUSTOMER' | 'PARTNER'; partnerLinks: number }

export default function PartnerOrganizations() {
  const list = useApi<Org[]>('/api/platform/orgs')
  const [creating, setCreating] = useState(false)
  const [toggling, setToggling] = useState<Org | null>(null)
  const { run } = useAction()
  if (list.error?.status === 403) return <div className="page"><div className="callout bad"><Icon name="shield" />Platform administrators only.</div></div>
  return (
    <div className="page">
      <PageHead title="Partner organizations" subtitle="External TV platforms, apps and business partners. Partner organizations receive explicitly shared project access; they are not customer tenants or subscription accounts."
        actions={<button className="btn primary" onClick={() => setCreating(true)}><Icon name="plus" />New partner organization</button>} />
      <section className="card">
        {!list.data ? <div className="card-pad"><Loading /></div> : (
          <table className="table"><thead><tr><th>Organization</th><th>Status</th><th>Plan</th><th>Members</th><th>Channels (live)</th><th>Videos</th><th>Viewer sessions</th><th>Since</th><th /></tr></thead>
            <tbody>{list.data.filter(o => o.kind === 'PARTNER').map(o => (
              <tr key={o.id}><td><b>{o.name}</b>{o.kind === 'PARTNER' && <span className="badge sim" style={{ marginLeft: 6 }}>partner</span>}<div className="faint">/{o.slug} · {o.country} · {o.timezone}{o.partnerLinks > 0 && ` · ${o.partnerLinks} active sharing${o.partnerLinks > 1 ? 's' : ''}`}</div></td><td><Status value={o.status} /></td>
                <td>{o.plan}<div className="faint">max {o.maxChannels} channels</div></td><td>{o.members}</td><td>{o.channels} ({o.liveChannels})</td><td>{o.videos}</td><td>{o.sessions}</td>
                <td className="small">{fmtDate(o.createdAt)}</td>
                <td><button className={'btn sm ' + (o.status === 'ACTIVE' ? 'danger' : '')} onClick={() => setToggling(o)}>{o.status === 'ACTIVE' ? 'Suspend' : 'Re-activate'}</button></td></tr>
            ))}</tbody></table>
        )}
      </section>
      {toggling && <Confirm danger={toggling.status === 'ACTIVE'} title={toggling.status === 'ACTIVE' ? `Suspend ${toggling.name}?` : `Re-activate ${toggling.name}?`}
        confirmLabel={toggling.status === 'ACTIVE' ? 'Suspend' : 'Re-activate'}
        text={toggling.status === 'ACTIVE' ? 'Their team can no longer sign in to manage anything. Nothing is deleted, and channels already on air keep playing so viewers are not cut off.' : 'Their team regains access straight away.'}
        onClose={() => setToggling(null)} onConfirm={() => run(() => api(`/api/platform/orgs/${toggling.id}/status`, { body: { status: toggling.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' } }), 'Updated').then(list.reload)} />}
      <ResetCard />
      {creating && <CreateOrg kind="PARTNER" onClose={() => setCreating(false)} onDone={list.reload} />}
    </div>
  )
}

export function CreateOrg({ onClose, onDone, kind }: { onClose: () => void; onDone: () => void; kind:'CUSTOMER'|'PARTNER' }) {
  const [f, setF] = useState({ name: '', slug: '', country: 'IN', timezone: 'Asia/Kolkata', ownerName: '', ownerEmail: '', kind })
  const [result, setResult] = useState<{ ownerTemporaryPassword?: string; note: string } | null>(null)
  const { busy, run } = useAction()
  const set = (k: string, v: string) => setF(x => ({ ...x, [k]: v, ...(k === 'name' && !x.slug ? {} : {}) }))
  return (
    <Modal title={kind==='PARTNER'?'New partner organization':'New customer tenant'} subtitle={kind==='PARTNER'?'Creates a partner identity and its first administrator.':'Creates a customer tenant with its first isolated project and owner. More projects can be added under tenant settings.'} onClose={onClose}
      footer={result ? <button className="btn primary" onClick={onClose}>Done</button> : <><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy} onClick={() => run(() => api<{ ownerTemporaryPassword?: string; note: string }>('/api/platform/orgs', { body: f }), 'Organization created').then(r => { if (r) { setResult(r); onDone() } })}>Create</button></>}>
      {result ? <div className="callout sim"><Icon name="sparkle" /><div>Created. {result.note}{result.ownerTemporaryPassword && <div style={{ marginTop: 8 }}>Owner's one-time password: <code style={{ fontSize: 15 }}>{result.ownerTemporaryPassword}</code></div>}</div></div> : <>
        <div className="grid g2">
          <label className="field">Company name<input value={f.name} onChange={e => { set('name', e.target.value); setF(x => ({ ...x, slug: e.target.value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') })) }} /></label>
          <label className="field">{kind==='PARTNER'?'Partner ID':'First project slug'}<span className="hint">Used in links, e.g. /{f.slug || 'acme'}</span><input value={f.slug} onChange={e => set('slug', e.target.value)} /></label>
          <label className="field">Country (2 letters)<input value={f.country} maxLength={2} onChange={e => set('country', e.target.value.toUpperCase())} /></label>
          <label className="field">Time zone<input value={f.timezone} onChange={e => set('timezone', e.target.value)} /></label>
          <label className="field">{f.kind === 'PARTNER' ? 'Partner admin name' : 'Owner name'}<input value={f.ownerName} onChange={e => set('ownerName', e.target.value)} /></label>
          <label className="field">{f.kind === 'PARTNER' ? 'Partner admin email' : 'Owner email'}<input type="email" value={f.ownerEmail} onChange={e => set('ownerEmail', e.target.value)} /></label>
        </div>
      </>}
    </Modal>
  )
}

function ResetCard() {
  const [email, setEmail] = useState('')
  const [link, setLink] = useState<{ path: string; note: string } | null>(null)
  const { busy, run } = useAction()
  return (
    <section className="card card-pad stack">
      <div><h2>Reset someone's password</h2><p className="faint">For a person who can't sign in (for example an organization owner). Creates a one-time link valid for 24 hours and emails it to them; it is shown here too, in case the email doesn't arrive.</p></div>
      <div className="row"><input type="email" placeholder="person@company.com" value={email} onChange={e => setEmail(e.target.value)} aria-label="Email of the account" />
        <button className="btn primary" disabled={busy || !email.includes('@')} onClick={() => run(() => api<{ path: string; note: string }>('/api/platform/password-reset', { body: { email } })).then(r => r && setLink(r))}>Create reset link</button></div>
      {link && <ResetLinkModal who={email} {...link} onClose={() => { setLink(null); setEmail('') }} />}
    </section>
  )
}
