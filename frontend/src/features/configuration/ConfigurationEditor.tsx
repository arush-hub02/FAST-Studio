import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../api'
import { ErrorBox, Loading, useAction, useApi } from '../../ui'
import type { Configuration, EffectiveProperty, Property } from './types'

export function PropertyInput({ definition: d, value, onChange, disabled, label }: { definition: Pick<Property,'valueType'|'options'|'secret'|'label'>; value: unknown; onChange: (value: unknown) => void; disabled?: boolean; label?: string }) {
  return <label className="field"><span>{label ?? d.label}</span>{d.valueType === 'BOOLEAN' ? <select disabled={disabled} value={String(value ?? '')} onChange={e => onChange(e.target.value === '' ? null : e.target.value === 'true')}><option value="">Select a value</option><option value="true">Yes</option><option value="false">No</option></select>
    : d.valueType === 'SELECT' ? <select disabled={disabled} value={String(value ?? '')} onChange={e => onChange(e.target.value)}><option value="">Select a value</option>{d.options.map(o => <option key={o}>{o}</option>)}</select>
    : <input disabled={disabled} type={d.secret ? 'password' : d.valueType === 'NUMBER' ? 'number' : 'text'} step={d.valueType === 'NUMBER' ? 'any' : undefined} maxLength={4000} autoComplete={d.secret ? 'new-password' : 'off'} value={String(value ?? '')} onChange={e => onChange(d.valueType === 'NUMBER' ? e.target.value === '' ? null : Number(e.target.value) : e.target.value)} />}</label>
}

function ValueEditor({ property: p, base, editable, platform, reload }: { property: EffectiveProperty; base: string; editable: boolean; platform: boolean; reload: () => void }) {
  const [value, setValue] = useState<unknown>(p.definition.secret ? '' : p.value)
  const { busy, run } = useAction()
  const permitted = editable && p.definition.enabled && (!p.definition.secret || platform) && (p.definition.projectOverride || !base.includes('/projects/') && !base.endsWith('/configuration'))
  return <form className="card card-pad stack" onSubmit={e => { e.preventDefault(); run(() => api(`${base}/properties/${p.definition.key}`, { method:'PUT',body:{value} }), 'Property saved').then(r => { if(r) { setValue(p.definition.secret ? '' : value);reload() } }) }}>
    <div className="row-wrap"><b>{p.definition.label}</b><span className="badge info">{p.source.toLowerCase()}</span>{p.definition.secret && <span className="badge">Secret · {p.configured ? 'configured' : 'not configured'}</span>}</div>
    <p className="faint small">{p.definition.key}{p.definition.connectorKey ? ` · ${p.definition.connectorKey}` : ''}{!p.definition.projectOverride && ' · Tenant-wide property'}</p>
    <PropertyInput definition={p.definition} label={p.definition.secret ? 'Replacement value (never returned)' : 'Value'} value={value} onChange={setValue} disabled={!permitted || busy} />
    {permitted && <div className="row-wrap"><button className="btn primary sm" disabled={busy || value == null || p.definition.secret && value === ''}>Save override</button>{p.overridden && <button className="btn sm" type="button" disabled={busy} onClick={() => run(() => api(`${base}/properties/${p.definition.key}`,{method:'PUT',body:{reset:true}}),'Using inherited default').then(r=>{if(r)reload()})}>Use inherited default</button>}</div>}
    {p.definition.secret && !platform && <p className="faint small">The platform administrator provisions secrets. Stored values are never displayed.</p>}
  </form>
}

export function ConfigurationEditor({ base, platform = false, onSaved }: { base: string; platform?: boolean; onSaved?: () => void }) {
  const view = useApi<Configuration>(base)
  const { busy, run } = useAction()
  const reload = () => { view.reload();onSaved?.() }
  if(view.error) return <ErrorBox error={view.error} retry={view.reload}/>
  if(!view.data) return <Loading/>
  const v=view.data
  const mutationBase=platform?base.replace(/\/settings$/,''):base
  return <div className="stack">
    <section className="card card-pad stack"><h2>{v.project ? 'Project configuration' : 'Tenant defaults'}</h2>
      <p>{v.tenant.name}{v.project ? ` / ${v.project.name}` : ''}</p>
      <p className="faint">Global defaults → tenant defaults → project overrides. Reset an override to inherit future default changes.</p>
      <div className="row-wrap"><span className="badge info">{v.plan.name}</span><span>Subscription: {v.tenant.subscriptionStatus.toLowerCase()}</span><span>{v.tenant.projects}/{v.plan.maxProjects} projects · {v.tenant.channels}/{v.plan.maxChannels} channels across the tenant</span></div>
      <p className="faint small">Plan assignment records access and allowances. Payment collection and invoicing are not connected.</p>
    </section>
    <section className="card card-pad stack"><h2>Connectors</h2><p className="faint">The platform makes a connector available to a tenant. Projects can enable it or opt out. Disabling retains existing media and credentials.</p>
      {v.connectors.map(c=><div className="row-wrap" key={c.definition.key}><label className="row" style={{gap:8}}><input type="checkbox" checked={c.enabled} disabled={busy || !v.editable || !platform && !v.project || !!v.project && !c.allowed} onChange={e=>run(()=>api(`${mutationBase}/connectors/${c.definition.key}`,{method:'PUT',body:{enabled:e.target.checked}}),'Connector updated').then(r=>{if(r)reload()})}/><b>{c.definition.name}</b></label><span className="faint small">{!c.definition.enabled ? 'Disabled globally' : v.project && !c.allowed ? 'Unavailable to this tenant' : c.overridden ? 'Project override' : 'Inherited'}{c.definition.driver==='CUSTOM' ? ' · Configuration only; an adapter is required for provider operations' : ''}</span>
        {v.project && c.overridden && v.editable && <button className="btn sm" disabled={busy} onClick={()=>run(()=>api(`${mutationBase}/connectors/${c.definition.key}`,{method:'PUT',body:{reset:true}}),'Using tenant setting').then(r=>{if(r)reload()})}>Use tenant setting</button>}</div>)}
      {platform && v.project && <Link to={`/platform/connectors?project=${v.project.id}`}>Configure project credentials, media permissions and ad sources</Link>}
    </section>
    <h2>Custom properties</h2>{!v.properties.length && <p className="faint">No properties defined yet. The platform owner can add definitions and global defaults in Global configuration.</p>}
    <div className="grid g2">{v.properties.map(p=><ValueEditor key={`${base}:${p.definition.key}:${p.definition.revision}:${p.source}:${JSON.stringify(p.value)}:${p.configured}`} property={p} base={mutationBase} editable={v.editable} platform={platform} reload={reload}/>)}</div>
  </div>
}
