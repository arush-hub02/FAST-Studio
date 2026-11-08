import { useState } from 'react'
import { api } from '../../shared/api'
import { Confirm, Icon, Loading, Modal, useAction, useApi } from '../../shared/ui'
import { PeopleList } from './People'

type Row = { userId: string; name: string; email: string; role: string; createdAt: string; projects: number }

const ROLES: [string, string][] = [['OWNER', 'Owner'], ['ADMIN', 'Administrator'], ['CONTENT_MANAGER', 'Content manager'],
  ['CHANNEL_OPERATOR', 'Channel operator'], ['AD_OPS', 'Ad operations'], ['ANALYST', 'Analyst']]
const label = (role: string) => ROLES.find(r => r[0] === role)?.[1] ?? role

/**
 * Tenant-wide roles: one role in every project of the tenant, including projects added later. A role someone has
 * directly in a project wins there. Each project keeps at least one owner.
 */
export function TenantRoles({ tenantId, projectCount, onChanged }: { tenantId: string; projectCount: number; onChanged: () => void }) {
  const base = `/api/platform/tenants/${tenantId}/members`
  const list = useApi<Row[]>(base)
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<Row | null>(null)
  const { busy, run } = useAction()
  const changed = () => { list.reload(); onChanged() }
  return (
    <section className="card card-pad stack">
      <div className="between">
        <div><h2>Tenant-wide roles</h2><p className="faint">One role in every project of this tenant, including projects added later. A role someone has directly in a project stays as it is there.</p></div>
        <button className="btn primary" onClick={() => setAdding(true)}><Icon name="plus" />Add tenant-wide role</button>
      </div>
      {!list.data ? <Loading /> : list.data.length === 0 ? <p className="faint small">Nobody has a tenant-wide role yet.</p> : (
        <table className="table"><thead><tr><th>Person</th><th>Role in every project</th><th>Applies in</th><th /></tr></thead>
          <tbody>{list.data.map(r => (
            <tr key={r.userId}>
              <td><b>{r.name}</b><div className="faint small">{r.email}</div></td>
              <td><select aria-label={`Tenant-wide role of ${r.name}`} value={r.role} disabled={busy}
                onChange={e => run(() => api(`${base}/${r.userId}`, { method: 'PUT', body: { role: e.target.value } }), 'Role changed').then(x => x && changed())}>
                {ROLES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></td>
              <td className="small">{r.projects} of {projectCount} {projectCount === 1 ? 'project' : 'projects'}
                {r.projects < projectCount && <div className="faint">Their own project role is used in the rest</div>}</td>
              <td><button className="btn sm ghost danger" aria-label={`Remove ${r.name}'s tenant-wide role`} onClick={() => setRemoving(r)}>Remove</button></td>
            </tr>
          ))}</tbody></table>
      )}
      {adding && <AddRole base={base} onClose={() => setAdding(false)} onDone={changed} />}
      {removing && <Confirm danger title={`Remove ${removing.name}'s tenant-wide role?`} confirmLabel="Remove"
        text={`They lose the ${label(removing.role).toLowerCase()} role in this tenant's projects. Roles they have directly in a project stay.`}
        onClose={() => setRemoving(null)} onConfirm={() => run(() => api(`${base}/${removing.userId}`, { method: 'DELETE' }), 'Removed').then(x => x && changed())} />}
    </section>
  )
}

function AddRole({ base, onClose, onDone }: { base: string; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ email: '', name: '', role: 'ANALYST' })
  const [result, setResult] = useState<{ temporaryPassword: string | null; note: string } | null>(null)
  const { busy, run } = useAction()
  return (
    <Modal title="Add tenant-wide role" subtitle="New email addresses get an account with a one-time password." onClose={onClose}
      footer={result ? <button className="btn primary" onClick={onClose}>Done</button> : <><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy || !f.email.includes('@') || !f.name.trim()}
          onClick={() => run(() => api<{ temporaryPassword: string | null; note: string }>(base, { body: f }), 'Added').then(r => { if (r) { setResult(r); onDone() } })}>Add</button></>}>
      {result ? <div className="callout sim"><Icon name="sparkle" /><div>Added. {result.note}{result.temporaryPassword && <div style={{ marginTop: 8 }}>One-time password: <code style={{ fontSize: 15 }}>{result.temporaryPassword}</code></div>}</div></div> : (
        <div className="grid g2">
          <label className="field">Email<input type="email" value={f.email} onChange={e => setF({ ...f, email: e.target.value })} /></label>
          <label className="field">Name<input value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></label>
          <label className="field">Role in every project<select value={f.role} onChange={e => setF({ ...f, role: e.target.value })}>
            {ROLES.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
        </div>
      )}
    </Modal>
  )
}

/** Tenant page → People: tenant-wide roles, then everyone in the tenant's projects (refreshed when a tenant-wide role changes). */
export function TenantPeople({ tenantId, projectIds }: { tenantId: string; projectIds: string[] }) {
  const [version, setVersion] = useState(0)
  return <>
    <TenantRoles tenantId={tenantId} projectCount={projectIds.length} onChanged={() => setVersion(v => v + 1)} />
    <section className="card card-pad stack"><PeopleList key={version} tenantId={tenantId} projectIds={projectIds} /></section>
  </>
}
