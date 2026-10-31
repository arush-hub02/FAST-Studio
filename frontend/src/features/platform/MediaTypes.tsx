import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type MediaType } from '../../shared/api'
import { ErrorBox, Loading, PageHead, useAction, useApi } from '../../shared/ui'

/** Platform admin: which media types one company may enable. Shown in Connectors. */
export function TenantMediaEntitlements({ orgId }: { orgId: string }) {
  const types = useApi<MediaType[]>('/api/platform/media-types')
  const selected = useApi<string[]>(`/api/platform/orgs/${orgId}/media-types`)
  const { busy, run } = useAction()
  return <section className="card card-pad stack"><h3>Permitted media types</h3>
    <p className="faint">Choose what this company may enable in Tenant settings. Existing assets are retained. <Link to="/platform/media-types">Manage the platform catalogue</Link>.</p>
    {types.error || selected.error ? <ErrorBox error={types.error ?? selected.error!} retry={() => { types.reload(); selected.reload() }} /> : !types.data || !selected.data ? <Loading /> :
      <div className="row-wrap">{types.data.map(t => <label key={t.key} className="row" style={{ gap: 8, padding: 8 }}><input type="checkbox" disabled={busy} checked={selected.data!.includes(t.key)} onChange={e => {
        const next = e.target.checked ? [...selected.data!, t.key] : selected.data!.filter(k => k !== t.key)
        run(() => api<string[]>(`/api/platform/orgs/${orgId}/media-types`, { method: 'PUT', body: { types: next } }), 'Media permissions saved').then(r => { if (r) selected.setData(r) })
      }} />{t.label}{!t.enabled && ' (disabled platform-wide)'}</label>)}</div>}
  </section>
}

export function PlatformMediaTypes() {
  const types = useApi<MediaType[]>('/api/platform/media-types')
  const [editing, setEditing] = useState<MediaType>({ key: '', label: '', kind: 'MOVIE', enabled: true })
  const { busy, run } = useAction()
  const exists = types.data?.some(t => t.key === editing.key)
  return <div className="page stack"><PageHead title="Media types" subtitle="Define business labels and supported scheduling behavior; grant each company's types in Connectors." />
    {types.error ? <ErrorBox error={types.error} retry={types.reload} /> : !types.data ? <Loading /> : <>
      <div className="card card-pad"><table className="table"><thead><tr><th>Type</th><th>Stable key</th><th>Behavior</th><th>Status</th><th>Action</th></tr></thead><tbody>{types.data.map(t => <tr key={t.key}><td>{t.label}</td><td className="mono">{t.key}</td><td>{t.kind}</td><td>{t.enabled ? 'Enabled' : 'Disabled'}</td><td><button className="btn sm" onClick={() => setEditing(t)}>Edit {t.label}</button></td></tr>)}</tbody></table></div>
      <form className="card card-pad stack" onSubmit={e => { e.preventDefault(); run(() => api('/api/platform/media-types', { body: editing }), 'Media type saved').then(r => { if (r) types.reload() }) }}>
        <h2>{exists ? 'Edit media type' : 'Add media type'}</h2>
        <div className="grid g2"><label className="field"><span>Stable key</span><input required pattern="[A-Z][A-Z0-9_]{0,39}" value={editing.key} disabled={!!exists} onChange={e => setEditing({ ...editing, key: e.target.value.toUpperCase() })} /></label>
          <label className="field"><span>Display label</span><input required maxLength={80} value={editing.label} onChange={e => setEditing({ ...editing, label: e.target.value })} /></label></div>
        <label className="field"><span>Scheduling behavior</span><select disabled={!!exists} value={editing.kind} onChange={e => setEditing({ ...editing, kind: e.target.value })}>{['MOVIE','EPISODE','SHORT','AD'].map(k => <option key={k}>{k}</option>)}</select></label>
        <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={editing.enabled} onChange={e => setEditing({ ...editing, enabled: e.target.checked })} />Available for new use</label>
        <p className="faint">Episodes require series, season and episode numbers. New labels reuse existing processing behavior.</p>
        <div className="row"><button className="btn primary" disabled={busy}>Save type</button><button type="button" className="btn" onClick={() => setEditing({ key: '', label: '', kind: 'MOVIE', enabled: true })}>New type</button></div>
      </form>
    </>}
  </div>
}
