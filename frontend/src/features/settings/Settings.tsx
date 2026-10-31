import { useState } from 'react'
import { api, type MediaSettingsView} from '../../shared/api'
import { useOrg } from '../../app/App'
import { DAYS, ErrorBox, Loading, PageHead, useAction, useApi } from '../../shared/ui'
import { ConfigurationEditor } from '../../shared/configuration/ConfigurationEditor'

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
