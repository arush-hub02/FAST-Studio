import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type MediaSettingsView, type MediaType } from '../../shared/api'
import { useOrg } from '../../app/App'
import { ErrorBox, Loading, PageHead, useAction, useApi } from '../../shared/ui'
import { ConfigurationEditor } from '../../shared/configuration/ConfigurationEditor'

export const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export default function Settings() {
  const org = useOrg()
  const view = useApi<MediaSettingsView>(`/api/orgs/${org.slug}/settings`)
  const [scope,setScope]=useState('project')
  return <div className="page stack">
    <PageHead title="Settings" subtitle={`${org.tenantName??org.name} / ${org.name}. Tenant defaults are inherited by its projects.`} />
    <div className="row-wrap"><button className={'btn '+(scope==='project'?'primary':'')} aria-pressed={scope==='project'} onClick={()=>setScope('project')}>Project settings</button><button className={'btn '+(scope==='tenant'?'primary':'')} aria-pressed={scope==='tenant'} onClick={()=>setScope('tenant')}>Tenant settings</button></div>
    <ConfigurationEditor key={scope} base={`/api/orgs/${org.slug}/${scope==='project'?'configuration':'tenant-settings'}`} onSaved={org.reloadProject}/>
    {scope==='project'&&<>
    {view.error ? <ErrorBox error={view.error} retry={view.reload} /> : !view.data ? <Loading /> :
      <SettingsForm key={`${org.slug}:${view.data.revision}`} initial={view.data} reload={view.reload} />}
    </>}
  </div>
}

function SettingsForm({ initial, reload }: { initial: MediaSettingsView; reload: () => void }) {
  const org = useOrg()
  const [form, setForm] = useState(initial)
  const [key, setKey] = useState('')
  const { busy, run } = useAction()
  const editable = org.can('TEAM_MANAGE')
  const base = `/api/orgs/${org.slug}/settings`
  return <>
    <form className="card card-pad stack" onSubmit={e => { e.preventDefault(); run(() => api(base, { method: 'PUT', body: { ...form, expectedRevision: form.revision } }), 'Settings saved').then(r => { if (r) reload() }) }}>
      <fieldset disabled={!editable || busy} className="stack" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend><h2>Media types</h2></legend>
        <p className="faint">The platform owner controls which types your company may use. Disabling a type prevents new use and keeps existing videos and published schedules.</p>
        <div className="row-wrap">{form.types.map(t => <label key={t.key} className="row" style={{ gap: 8, padding: 8 }}>
          <input type="checkbox" checked={form.enabledTypes.includes(t.key)} onChange={e => {
            const enabledTypes = e.target.checked ? [...form.enabledTypes, t.key] : form.enabledTypes.filter(k => k !== t.key)
            setForm({ ...form, enabledTypes, defaultType: enabledTypes.includes(form.defaultType) ? form.defaultType : enabledTypes[0] ?? '' })
          }} />{t.label}
        </label>)}</div>
        <label className="field"><span>Default media type</span><select value={form.defaultType} required onChange={e => setForm({ ...form, defaultType: e.target.value })}>
          <option value="" disabled>Select a type</option>{form.types.filter(t => form.enabledTypes.includes(t.key)).map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
        </select></label>
        <h2>Programming defaults</h2>
        <p className="faint">These initialize new rules. Existing rules keep their saved times. Configure each series in the channel's Schedule.</p>
        <label className="field"><span>Premiere time in the channel timezone</span><input type="time" required value={form.premiereTime} onChange={e => setForm({ ...form, premiereTime: e.target.value })} /></label>
        <fieldset><legend>New episode days</legend><div className="row-wrap">{DAYS.map((day, i) => <label key={day} className="row" style={{ gap: 8, padding: 8 }}>
          <input type="checkbox" checked={form.weekdays.includes(i + 1)} onChange={e => setForm({ ...form, weekdays: e.target.checked ? [...form.weekdays, i + 1] : form.weekdays.filter(d => d !== i + 1) })} />{day}
        </label>)}</div></fieldset>
        <h2>Viewer advertising</h2>
        <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={form.personalizedAds} onChange={e => setForm({ ...form, personalizedAds: e.target.checked })} />Allow personalization from verified viewer context</label>
        <p className="faint">Viewers without permitted identity receive contextual ads. Campaign audience keys and frequency limits are configured in Ads. Viewer application servers sign short-lived context; tenant staff accounts are separate.</p>
        <button className="btn primary" disabled={busy || !form.enabledTypes.length || !form.weekdays.length}>Save settings</button>
      </fieldset>
      {!editable && <p className="faint">An owner or administrator can change these settings.</p>}
    </form>
    {editable && <form className="card card-pad stack" aria-label="Viewer integration key"
      onSubmit={e => { e.preventDefault(); run(() => api<MediaSettingsView>(`${base}/viewer-key`, { body: { key } }), 'Signing key saved; stored credentials are never returned').then(r => { if (r) { setForm(r); reload(); org.reloadProject() } }).finally(() => setKey('')) }}>
      <h2>Viewer integration</h2>
      <p>{initial.viewerKeySet ? 'A signing key is configured.' : 'No signing key is configured.'} Keep it only on your viewer application server. Rotating it revokes existing context for future decisions; in-progress breaks retain their committed ads.</p>
      <p className="faint">Generate a random signing key on your viewer application server, then enter it here to configure or rotate the integration. Stored keys cannot be retrieved.</p>
      <label className="field"><span>Replacement signing key</span><input type="password" autoComplete="new-password" required minLength={32} maxLength={256} pattern="[A-Za-z0-9_-]+" value={key} disabled={busy || !initial.revision} onChange={e => setKey(e.target.value)} /></label>
      <button type="submit" className="btn" disabled={busy || !initial.revision || key.length < 32}>Save replacement signing key</button>
      {!initial.revision && <p className="faint">Save tenant settings first.</p>}
    </form>}
  </>
}

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
