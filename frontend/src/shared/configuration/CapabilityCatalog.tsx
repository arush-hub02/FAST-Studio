import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Loading, useAction, useApi } from '../ui'

// The features and permissions catalogue (capability). The platform keeps the global entries; a project keeps its own.
// Keys are the identifiers screens check with org.has(feature) and org.can(permission). Built-in entries are enforced
// by the server; added ones only show or hide things in the frontend (and in any endpoint that checks the key).
export type Capability = {
  key: string; kind: 'FEATURE' | 'PERMISSION'; name: string; description: string; category: string
  orgId: string | null; builtIn: boolean; enabled: boolean
}
type ProjectView = { catalogue: Capability[]; features: string[]; permissions: string[] }

const BLANK = { key: '', kind: 'FEATURE' as Capability['kind'], name: '', description: '', category: '', enabled: true }

/** Platform administration: the global catalogue. */
export function GlobalCapabilities() {
  const view = useApi<Capability[]>('/api/platform/capabilities')
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  return <CatalogEditor base="/api/platform/capabilities" own={view.data} reload={view.reload}
    intro="Global features and permissions every project can use. Built-in entries are enforced by the server and can be renamed, not removed. Added entries are identifiers for screens: switch a feature on per project on the Connectors page; give a permission to people through organization roles (owners and administrators have all of them)." />
}

/** A project: the keys that apply to you, the global catalogue (read-only) and the project's own entries. */
export function ProjectCapabilities({ base, editable }: { base: string; editable: boolean }) {
  const view = useApi<ProjectView>(base)
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const v = view.data
  return <>
    <section className="card card-pad stack" aria-label="Features and permissions that apply to you">
      <h2>Features and permissions that apply to you</h2>
      <p className="faint">What this project has switched on and what your role allows. Screens show or hide things by these identifiers.</p>
      <div className="stack" style={{ gap: 6 }}>
        <div><b>Features</b> <span className="row-wrap" style={{ gap: 6, display: 'inline-flex' }}>{v.features.length ? v.features.map(f => <span key={f} className="badge ok mono small">{f}</span>) : <span className="faint">none</span>}</span></div>
        <div><b>Permissions</b> <span className="row-wrap" style={{ gap: 6, display: 'inline-flex' }}>{v.permissions.map(p => <span key={p} className="badge mono small">{p}</span>)}</span></div>
      </div>
    </section>
    <CatalogEditor base={base} own={v.catalogue.filter(c => c.orgId)} global={v.catalogue.filter(c => !c.orgId)} reload={view.reload} readOnly={!editable}
      intro="Add features and permissions for this project only. A project feature is on while it's enabled; a project permission goes to owners and administrators, and to custom roles that list it. Global entries come from the platform." />
  </>
}

function CatalogEditor({ base, own, global, reload, intro, readOnly }: {
  base: string; own: Capability[]; global?: Capability[]; reload: () => void; intro: string; readOnly?: boolean
}) {
  const [form, setForm] = useState(BLANK)
  const [editing, setEditing] = useState<string | null>(null)
  const { busy, run } = useAction()
  const save = () => editing
    ? run(() => api(`${base}/${editing}`, { method: 'PUT', body: { name: form.name, description: form.description, category: form.category, enabled: form.enabled } }), 'Saved')
    : run(() => api(base, { body: form }), `${form.kind === 'FEATURE' ? 'Feature' : 'Permission'} added`)
  const remove = (c: Capability) => run(() => api(`${base}/${c.key}`, { method: 'DELETE' }), `${c.key} removed`).then(() => reload())
  const reset = () => { setForm(BLANK); setEditing(null) }
  const rows = (list: Capability[], editable: boolean) => <table className="table"><thead><tr><th>Identifier</th><th>Kind</th><th>Category</th><th>Status</th><th /></tr></thead>
    <tbody>{list.map(c => <tr key={c.key}>
      <td>{c.name}<div className="faint mono small">{c.key}</div>{c.description && <div className="faint small">{c.description}</div>}</td>
      <td>{c.kind === 'FEATURE' ? 'Feature' : 'Permission'}</td><td>{c.category}</td>
      <td>{c.builtIn ? <span className="badge">Built in</span> : c.enabled ? <span className="badge ok">On</span> : <span className="badge">Off</span>}</td>
      <td>{editable && <div className="row" style={{ gap: 6 }}>
        <button className="btn sm" onClick={() => { setEditing(c.key); setForm({ key: c.key, kind: c.kind, name: c.name, description: c.description, category: c.category, enabled: c.enabled }) }}>Edit {c.key}</button>
        {!c.builtIn && <button className="btn sm" disabled={busy} onClick={() => remove(c)}>Remove {c.key}</button>}
      </div>}</td>
    </tr>)}</tbody></table>
  const builtIn = editing ? own.find(c => c.key === editing)?.builtIn : false
  return <>
    <section className="card card-pad stack">
      <h2>{global ? 'This project\'s own features and permissions' : 'Features and permissions catalogue'}</h2>
      <p className="faint">{intro}</p>
      {own.length ? rows(own, !readOnly) : <p className="faint">None yet.</p>}
      {global && <details><summary>Global catalogue ({global.length})</summary>{rows(global, false)}</details>}
    </section>
    {!readOnly && <form className="card card-pad stack" onSubmit={e => { e.preventDefault(); save().then(r => { if (r !== undefined) { reload(); reset() } }) }}>
      <h2>{editing ? `Edit ${editing}` : 'Add a feature or permission'}</h2>
      <div className="grid g2">
        <label className="field">Identifier<input required disabled={!!editing} pattern="[A-Z][A-Z0-9_]{1,63}" placeholder="e.g. CLIPS_EXPORT" value={form.key}
          onChange={e => setForm({ ...form, key: e.target.value.toUpperCase() })} /><span className="hint">Capitals, digits and underscores. Screens check this; it can't change later.</span></label>
        <label className="field">Kind<select disabled={!!editing} value={form.kind} onChange={e => setForm({ ...form, kind: e.target.value as Capability['kind'] })}>
          <option value="FEATURE">Feature (switched on per project)</option><option value="PERMISSION">Permission (given through roles)</option></select></label>
        <label className="field">Name<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Category<input maxLength={40} placeholder="General" value={form.category} onChange={e => setForm({ ...form, category: e.target.value })} /></label>
      </div>
      <label className="field">Description<input maxLength={300} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
      <label className="row"><input type="checkbox" disabled={builtIn} checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} />Enabled</label>
      <div className="row-wrap"><button className="btn primary" disabled={busy}>{editing ? 'Save' : 'Add'}</button>{editing && <button type="button" className="btn" onClick={reset}>Cancel</button>}</div>
    </form>}
  </>
}
