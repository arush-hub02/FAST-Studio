import { useState } from 'react'
import { api } from '../api'
import { useOrg, useSession } from '../app'
import { Confirm, fmtDate, Icon, initials, Loading, Modal, PageHead, useAction, useApi } from '../ui'
import { ResetLinkModal } from './Password'

type Member = { userId: string; name: string; email: string; role: string; createdAt: string; customRoleKey?: string }
/** Built-in and custom roles assignable in this organization; the server supplies names and descriptions. */
function useRoles(slug: string) {
  const view = useApi<{ key: string; name: string; description: string; baseRole: string }[]>(`/api/orgs/${slug}/members/roles`)
  return Object.fromEntries((view.data ?? []).map(r => [r.key, { label: r.name, can: r.description, baseRole: r.baseRole }]))
}
export default function Team() {
  const org = useOrg()
  const { me } = useSession()
  const list = useApi<Member[]>(`/api/orgs/${org.slug}/members`)
  const [inviting, setInviting] = useState(false)
  const [removing, setRemoving] = useState<Member | null>(null)
  const [resetLink, setResetLink] = useState<{ who: string; path: string; note: string } | null>(null)
  const { busy, run } = useAction()
  const manage = org.can('TEAM_MANAGE')
  const roles = useRoles(org.slug)
  return (
    <div className="page">
      <PageHead title="Team" subtitle={`People who can work in ${org.name}. Roles decide what each person can change — the server enforces this on every request, not just the screen.`}
        actions={manage && <button className="btn primary" onClick={() => setInviting(true)}><Icon name="plus" />Invite someone</button>} />
      <div className="split">
        <section className="card">
          {!list.data ? <div className="card-pad"><Loading /></div> : (
            <table className="table"><thead><tr><th>Person</th><th>Role</th><th>Member since</th><th /></tr></thead>
              <tbody>{list.data.map(m => (
                <tr key={m.userId}>
                  <td><div className="row"><div className="avatar" style={{ background: '#6366f1' }}>{initials(m.name)}</div><div><b>{m.name}</b>{m.userId === me?.id && <span className="badge info" style={{ marginLeft: 6 }}>you</span>}<div className="faint">{m.email}</div></div></div></td>
                  <td>{manage && m.userId !== me?.id ? (
                    <select value={m.customRoleKey??m.role} disabled={busy} style={{ width: 190 }} onChange={e => run(() => api(`/api/orgs/${org.slug}/members/${m.userId}`, { method: 'PATCH', body: { role: roles[e.target.value].baseRole, customRoleKey: roles[e.target.value].baseRole===e.target.value?null:e.target.value } }), 'Role changed').then(list.reload)}>
                      {Object.entries(roles).map(([k, r]) => <option key={k} value={k}>{r.label}</option>)}
                    </select>) : roles[m.customRoleKey??m.role]?.label??m.customRoleKey??m.role}</td>
                  <td className="small">{fmtDate(m.createdAt)}</td>
                  <td>{manage && m.userId !== me?.id && <div className="row-wrap" style={{ justifyContent: 'flex-end' }}>
                    <button className="btn sm ghost" disabled={busy} title="Create a one-time link to set a new password"
                      onClick={() => run(() => api<{ path: string; note: string }>(`/api/orgs/${org.slug}/members/${m.userId}/password-reset`, { method: 'POST' })).then(r => r && setResetLink({ who: m.name, ...r }))}>Reset password</button>
                    <button className="btn sm ghost danger" onClick={() => setRemoving(m)}>Remove</button></div>}</td>
                </tr>
              ))}</tbody></table>
          )}
        </section>
        <section className="card">
          <div className="card-head"><h2>What each role can do</h2></div>
          <div className="card-pad stack">{Object.entries(roles).map(([k, r]) => <div key={k}><b>{r.label}</b><div className="faint">{r.can}</div></div>)}
            <div className="callout"><Icon name="shield" /><div className="small">Nobody can give a role higher than their own, and every organization keeps at least one owner.</div></div></div>
        </section>
      </div>
      {resetLink && <ResetLinkModal {...resetLink} onClose={() => setResetLink(null)} />}
      {inviting && <Invite onClose={() => setInviting(false)} onDone={list.reload} />}
      {removing && <Confirm danger title={`Remove ${removing.name}?`} confirmLabel="Remove" text="They lose access to this organization immediately. Their past actions stay in the audit log."
        onClose={() => setRemoving(null)} onConfirm={() => run(() => api(`/api/orgs/${org.slug}/members/${removing.userId}`, { method: 'DELETE' }), 'Removed').then(list.reload)} />}
    </div>
  )
}

function Invite({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const roles = useRoles(org.slug)
  const [f, setF] = useState({ name: '', email: '', role: org.kind === 'PARTNER' ? 'PARTNER_VIEWER' : 'ANALYST' })
  const [result, setResult] = useState<{ temporaryPassword?: string; note: string } | null>(null)
  const { busy, run } = useAction()
  return (
    <Modal title="Invite someone" onClose={onClose} footer={result ? <button className="btn primary" onClick={onClose}>Done</button> : <>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" disabled={busy || !f.email || !f.name} onClick={() => run(() => api<{ temporaryPassword?: string; note: string }>(`/api/orgs/${org.slug}/members`, { body: { ...f, role: roles[f.role]?.baseRole??f.role, customRoleKey: roles[f.role]?.baseRole===f.role?null:f.role } }), 'Invited').then(r => { if (r) { setResult(r); onDone() } })}>Invite</button></>}>
      {result ? <>
        <div className="callout ok"><Icon name="check" />{f.name} can now sign in to {org.name}.</div>
        {result.temporaryPassword && <div className="callout sim"><Icon name="sparkle" /><div>{result.note}<div style={{ marginTop: 8 }}>One-time password: <code style={{ fontSize: 15 }}>{result.temporaryPassword}</code></div></div></div>}
      </> : <>
        <label className="field">Name<input value={f.name} onChange={e => setF({ ...f, name: e.target.value })} autoFocus /></label>
        <label className="field">Work email<input type="email" value={f.email} onChange={e => setF({ ...f, email: e.target.value })} /></label>
        <label className="field">Role<select value={f.role} onChange={e => setF({ ...f, role: e.target.value })}>{Object.entries(roles).map(([k, r]) => <option key={k} value={k}>{r.label} — {r.can}</option>)}</select></label>
      </>}
    </Modal>
  )
}
