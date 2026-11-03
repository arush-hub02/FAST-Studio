import { useEffect, useState } from 'react'
import { api } from '../../shared/api'
import { Confirm, Icon, Loading, Modal, PageHead, useAction, useApi } from '../../shared/ui'
import { ResetLinkModal } from '../../shared/components/ResetLinkModal'
import { useSession } from '../../app/App'

type Membership = { orgId: string; slug: string; name: string; kind: 'CUSTOMER' | 'PARTNER'; role: string; customRoleKey: string | null }
type Person = { id: string; email: string; name: string; platformAdmin: boolean; disabled: boolean; createdAt: string; memberships: Membership[] }
type Page = { people: Person[]; total: number }
type Org = { id: string; slug: string; name: string; kind: 'CUSTOMER' | 'PARTNER'; status: string }

const ROLES: Record<'CUSTOMER' | 'PARTNER', [string, string][]> = {
  CUSTOMER: [['OWNER', 'Owner'], ['ADMIN', 'Administrator'], ['CONTENT_MANAGER', 'Content manager'], ['CHANNEL_OPERATOR', 'Channel operator'], ['AD_OPS', 'Ad operations'], ['ANALYST', 'Analyst']],
  PARTNER: [['PARTNER_ADMIN', 'Partner administrator'], ['PARTNER_VIEWER', 'Partner viewer']],
}
const label = (kind: 'CUSTOMER' | 'PARTNER', role: string) => ROLES[kind].find(r => r[0] === role)?.[1] ?? role
const PAGE = 50
type CustomRole = { key: string; name: string; baseRole: string }

/** The roles a person can get in one organization: built-in ones, then its custom roles ("custom:key" values). */
function RoleSelect({ orgId, kind, value, onChange }: { orgId: string; kind: 'CUSTOMER' | 'PARTNER'; value: string; onChange: (v: { role: string; customRoleKey: string | null }) => void }) {
  const custom = useApi<CustomRole[]>(orgId ? `/api/platform/people/roles?orgId=${orgId}` : null)
  return <label className="field">Role<select value={value} onChange={e => {
    const v = e.target.value
    const c = v.startsWith('custom:') ? custom.data?.find(x => x.key === v.slice(7)) : null
    onChange(c ? { role: c.baseRole, customRoleKey: c.key } : { role: v, customRoleKey: null })
  }}>
    {ROLES[kind].map(([k, l]) => <option key={k} value={k}>{l}</option>)}
    {(custom.data ?? []).length > 0 && <optgroup label="Custom roles">
      {custom.data!.map(c => <option key={c.key} value={'custom:' + c.key}>{c.name} (based on {label(kind, c.baseRole)})</option>)}
    </optgroup>}
  </select></label>
}

/** Everyone across every organization: find a person, add them to an organization, change or remove a role, switch their account off. */
export default function People() {
  const [q, setQ] = useState('')
  const [query, setQuery] = useState('')
  const [limit, setLimit] = useState(PAGE)
  useEffect(() => { const t = setTimeout(() => { setQuery(q.trim()); setLimit(PAGE) }, 250); return () => clearTimeout(t) }, [q])
  const list = useApi<Page>(`/api/platform/people?q=${encodeURIComponent(query)}&limit=${limit}`)
  const orgs = useApi<Org[]>('/api/platform/orgs')
  const [adding, setAdding] = useState<Person | 'new' | null>(null)
  const [editing, setEditing] = useState<{ person: Person; m: Membership } | null>(null)
  const [removing, setRemoving] = useState<{ person: Person; m: Membership } | null>(null)
  const [toggling, setToggling] = useState<Person | null>(null)
  const [reset, setReset] = useState<{ email: string; path: string; note: string } | null>(null)
  const { run } = useAction()
  const { me } = useSession()
  if (list.error?.status === 403) return <div className="page"><div className="callout bad"><Icon name="shield" />Platform administrators only.</div></div>
  const people = list.data?.people ?? []
  return (
    <div className="page">
      <PageHead title="People" subtitle="Everyone with an account, and the organizations they belong to. Changes apply to their sign-in at once."
        actions={<button className="btn primary" onClick={() => setAdding('new')}><Icon name="plus" />Add person</button>} />
      <section className="card">
        <div className="card-pad row" style={{ gap: 8 }}>
          <input type="search" placeholder="Search by name or email" value={q} onChange={e => setQ(e.target.value)} aria-label="Search people" style={{ maxWidth: 360 }} />
          {list.data && <span className="faint small">{list.data.total} {list.data.total === 1 ? 'person' : 'people'}</span>}
        </div>
        {!list.data ? <div className="card-pad"><Loading /></div> : people.length === 0 ? <div className="card-pad faint">Nobody matches.</div> : (
          <table className="table"><thead><tr><th>Person</th><th>Organizations and roles</th><th>Account</th><th /></tr></thead>
            <tbody>{people.map(p => (
              <tr key={p.id}>
                <td><div><b>{p.name}</b>{p.platformAdmin && <span className="badge info" style={{ marginLeft: 6 }}>platform admin</span>}<div className="faint small">{p.email}</div></div></td>
                <td><div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                  {p.memberships.length === 0 && <span className="faint small">No organization</span>}
                  {p.memberships.map(m => (
                    <span key={m.orgId} className="chip" style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                      <button type="button" style={{ background: "none", border: 0, padding: 0, color: "inherit", cursor: "pointer", font: "inherit" }} aria-label={`Change ${p.name}'s role in ${m.name}`} onClick={() => setEditing({ person: p, m })}>
                        {m.name} · {label(m.kind, m.role)}{m.customRoleKey ? ` (${m.customRoleKey})` : ''}
                      </button>
                      <button type="button" className="icon-btn" aria-label={`Remove ${p.name} from ${m.name}`} onClick={() => setRemoving({ person: p, m })}><Icon name="x" size={12} /></button>
                    </span>
                  ))}
                  <button type="button" className="btn sm ghost" onClick={() => setAdding(p)}><Icon name="plus" size={12} />Add to organization</button>
                </div></td>
                <td>{p.disabled ? <span className="badge bad">Switched off</span> : <span className="badge ok">Active</span>}</td>
                <td><div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                  {!p.disabled && <button type="button" className="btn sm" onClick={() => run(() => api<{ path: string; note: string }>('/api/platform/password-reset', { body: { email: p.email } })).then(r => r && setReset({ email: p.email, ...r }))}>Reset password</button>}
                  {p.id !== me?.id && <button type="button" className={'btn sm ' + (p.disabled ? '' : 'danger')} aria-label={`${p.disabled ? 'Switch on' : 'Switch off'} ${p.name}`} onClick={() => setToggling(p)}>{p.disabled ? 'Switch on' : 'Switch off'}</button>}
                </div></td>
              </tr>
            ))}</tbody></table>
        )}
        {list.data && people.length < list.data.total && <div className="card-pad"><button type="button" className="btn" onClick={() => setLimit(l => l + PAGE)}>Show more</button></div>}
      </section>

      {adding && <AddPerson person={adding === 'new' ? null : adding} orgs={orgs.data ?? []} onClose={() => setAdding(null)} onDone={list.reload} />}
      {editing && <ChangeRole {...editing} onClose={() => setEditing(null)} onDone={list.reload} />}
      {removing && <Confirm danger title={`Remove ${removing.person.name} from ${removing.m.name}?`} confirmLabel="Remove"
        text="They lose access to this organization at once. Their account and other organizations are kept."
        onClose={() => setRemoving(null)} onConfirm={() => run(() => api(`/api/platform/people/${removing.person.id}/memberships/${removing.m.orgId}`, { method: 'DELETE' }), 'Removed').then(list.reload)} />}
      {toggling && <Confirm danger={!toggling.disabled} title={toggling.disabled ? `Switch on ${toggling.name}'s account?` : `Switch off ${toggling.name}'s account?`}
        confirmLabel={toggling.disabled ? 'Switch on' : 'Switch off'}
        text={toggling.disabled ? 'They can sign in again with their organizations and roles as before.' : 'They are signed out everywhere and can\'t sign in. Their organizations, roles and history are kept, so switching the account back on restores them.'}
        onClose={() => setToggling(null)} onConfirm={() => run(() => api(`/api/platform/people/${toggling.id}/status`, { method: 'PUT', body: { disabled: !toggling.disabled } }), 'Updated').then(list.reload)} />}
      {reset && <ResetLinkModal who={reset.email} path={reset.path} note={reset.note} onClose={() => setReset(null)} />}
    </div>
  )
}

function AddPerson({ person, orgs, onClose, onDone }: { person: Person | null; orgs: Org[]; onClose: () => void; onDone: () => void }) {
  const joinable = orgs.filter(o => o.kind === 'CUSTOMER' || o.kind === 'PARTNER').filter(o => !person?.memberships.some(m => m.orgId === o.id))
  const [f, setF] = useState({ email: person?.email ?? '', name: person?.name ?? '', orgId: joinable[0]?.id ?? '', role: '', customRoleKey: null as string | null })
  const org = joinable.find(o => o.id === f.orgId)
  const roles = org ? ROLES[org.kind] : []
  const role = roles.some(r => r[0] === f.role) ? f.role : roles[roles.length - 1]?.[0] ?? ''
  const [result, setResult] = useState<{ temporaryPassword: string | null; note: string } | null>(null)
  const { busy, run } = useAction()
  return (
    <Modal title={person ? `Add ${person.name} to an organization` : 'Add person'} subtitle="New email addresses get an account with a one-time password." onClose={onClose}
      footer={result ? <button className="btn primary" onClick={onClose}>Done</button> : <><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy || !f.email.includes('@') || !f.name.trim() || !org}
          onClick={() => run(() => api<{ temporaryPassword: string | null; note: string }>('/api/platform/people', { body: { ...f, role, customRoleKey: f.customRoleKey } }), 'Added').then(r => { if (r) { setResult(r); onDone() } })}>Add</button></>}>
      {result ? <div className="callout sim"><Icon name="sparkle" /><div>Added. {result.note}{result.temporaryPassword && <div style={{ marginTop: 8 }}>One-time password: <code style={{ fontSize: 15 }}>{result.temporaryPassword}</code></div>}</div></div> : (
        <div className="grid g2">
          <label className="field">Email<input type="email" value={f.email} disabled={!!person} onChange={e => setF({ ...f, email: e.target.value })} /></label>
          <label className="field">Name<input value={f.name} disabled={!!person} onChange={e => setF({ ...f, name: e.target.value })} /></label>
          <label className="field">Organization<select value={f.orgId} onChange={e => setF({ ...f, orgId: e.target.value, role: '', customRoleKey: null })}>
            {joinable.map(o => <option key={o.id} value={o.id}>{o.name}{o.kind === 'PARTNER' ? ' (partner)' : ''}</option>)}
          </select></label>
          {org && <RoleSelect orgId={org.id} kind={org.kind} value={f.customRoleKey ? 'custom:' + f.customRoleKey : role} onChange={v => setF({ ...f, ...v })} />}
        </div>
      )}
    </Modal>
  )
}

function ChangeRole({ person, m, onClose, onDone }: { person: Person; m: Membership; onClose: () => void; onDone: () => void }) {
  const [role, setRole] = useState({ role: m.role, customRoleKey: m.customRoleKey })
  const { busy, run } = useAction()
  const unchanged = role.role === m.role && role.customRoleKey === m.customRoleKey
  return (
    <Modal title={`${person.name} in ${m.name}`} subtitle="The new role applies to their sign-in at once." onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy || unchanged}
          onClick={() => run(() => api(`/api/platform/people/${person.id}/memberships/${m.orgId}`, { method: 'PUT', body: role }), 'Role changed').then(r => { if (r) { onDone(); onClose() } })}>Save</button></>}>
      <RoleSelect orgId={m.orgId} kind={m.kind} value={role.customRoleKey ? 'custom:' + role.customRoleKey : role.role} onChange={setRole} />
    </Modal>
  )
}
