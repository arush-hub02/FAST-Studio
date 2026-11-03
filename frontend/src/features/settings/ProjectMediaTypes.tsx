import { useState } from 'react'
import { api, type MediaSettingsView } from '../../shared/api'
import { useOrg } from '../../app/App'
import { Confirm, ErrorBox, Icon, Loading, Modal, useAction, useApi } from '../../shared/ui'

type Own = MediaSettingsView['ownTypes'][number]
const KINDS: [string, string][] = [['MOVIE', 'Movie (long-form)'], ['EPISODE', 'Episode (series order)'], ['SHORT', 'Short (fills gaps)'], ['AD', 'Advertisement']]

/** The project's media types: the platform's ones it may use, plus its own, which owners and administrators create here. */
export function ProjectMediaTypes() {
  const org = useOrg()
  const view = useApi<MediaSettingsView>(`/api/orgs/${org.slug}/settings`)
  const [editing, setEditing] = useState<Own | 'new' | null>(null)
  const [removing, setRemoving] = useState<Own | null>(null)
  const { run } = useAction()
  const editable = org.can('TEAM_MANAGE')
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const own = new Set(view.data.ownTypes.map(t => t.key))
  const platform = view.data.types.filter(t => !own.has(t.key))
  return <>
    <section className="card card-pad stack">
      <div className="between"><div><h2>Media types</h2><p className="faint">Turn types on or off and pick the default under General. The scheduling behaviour of a type never changes once it exists.</p></div>
        {editable && <button className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" />New type</button>}</div>
      <h3 className="small">From the platform</h3>
      <div className="row-wrap">{platform.map(t => <span key={t.key} className="chip">{t.label} <span className="faint small">{t.key}</span></span>)}</div>
      <h3 className="small">This project's own</h3>
      {view.data.ownTypes.length === 0 ? <p className="faint small">None yet.</p> : (
        <table className="table"><thead><tr><th>Type</th><th>Behaves like</th><th>Videos</th><th>Status</th><th /></tr></thead>
          <tbody>{view.data.ownTypes.map(t => (
            <tr key={t.key}><td><b>{t.label}</b><div className="faint small">{t.key}</div></td>
              <td>{KINDS.find(k => k[0] === t.kind)?.[1] ?? t.kind}</td><td>{t.assets}</td>
              <td>{t.enabled ? <span className="badge ok">On</span> : <span className="badge">Off</span>}</td>
              <td>{editable && <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                <button className="btn sm" onClick={() => setEditing(t)}>Edit</button>
                <button className="btn sm" aria-label={`Delete ${t.label}`} disabled={t.assets > 0} title={t.assets > 0 ? 'Videos use it: switch it off instead' : undefined}
                  onClick={() => setRemoving(t)}><Icon name="trash" size={13} /></button></div>}</td></tr>
          ))}</tbody></table>
      )}
    </section>
    {editing && <TypeForm type={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); view.reload(); org.reloadProject() }} />}
    {removing && <Confirm danger title={`Delete ${removing.label}?`} confirmLabel="Delete" text="No videos use it. It disappears from this project's type lists."
      onClose={() => setRemoving(null)} onConfirm={() => run(() => api(`/api/orgs/${org.slug}/settings/media-types/${removing.key}`, { method: 'DELETE' }), 'Deleted').then(() => { view.reload(); org.reloadProject() })} />}
  </>
}

function TypeForm({ type, onClose, onSaved }: { type: Own | null; onClose: () => void; onSaved: () => void }) {
  const org = useOrg()
  const [f, setF] = useState({ key: type?.key ?? '', label: type?.label ?? '', kind: type?.kind ?? 'SHORT', enabled: type?.enabled ?? true })
  const { busy, run } = useAction()
  return (
    <Modal title={type ? `Edit ${type.label}` : 'New media type'} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy || !/^[A-Z][A-Z0-9_]{1,39}$/.test(f.key) || !f.label.trim()}
          onClick={() => run(() => api(`/api/orgs/${org.slug}/settings/media-types`, { body: f }), 'Saved').then(r => r && onSaved())}>Save</button></>}>
      <div className="grid g2">
        <label className="field">Name<input value={f.label} onChange={e => setF({ ...f, label: e.target.value, key: type ? f.key : e.target.value.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 40) })} /></label>
        <label className="field">Identifier<span className="hint">Capitals, digits and _</span><input value={f.key} disabled={!!type} onChange={e => setF({ ...f, key: e.target.value.toUpperCase() })} /></label>
        <label className="field">Behaves like<select value={f.kind} disabled={!!type} onChange={e => setF({ ...f, kind: e.target.value })}>
          {KINDS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
        <label className="row" style={{ gap: 8, alignSelf: 'end' }}><input type="checkbox" checked={f.enabled} onChange={e => setF({ ...f, enabled: e.target.checked })} />Available in this project</label>
      </div>
    </Modal>
  )
}
