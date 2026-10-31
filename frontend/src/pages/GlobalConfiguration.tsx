import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, Loading, PageHead, useAction, useApi } from '../ui'
import { PropertyInput } from '../features/configuration/ConfigurationEditor'
import type { AccessRole, Connector, Plan, Property } from '../features/configuration/types'

const emptyConnector:Connector={key:'',name:'',category:'MEDIA',driver:'CUSTOM',enabled:true,description:''}
const emptyPlan:Plan={key:'',name:'',enabled:true,maxProjects:1,maxChannels:10,monthlyPrice:0,currency:'USD',features:[]}
const emptyProperty:Property={key:'',label:'',valueType:'STRING',connectorKey:null,secret:false,projectOverride:true,enabled:true,options:[],defaultValue:null,defaultConfigured:false,revision:0}

export default function GlobalConfiguration() {
  const [tab,setTab]=useState('properties')
  return <div className="page stack"><PageHead title="Global configuration" subtitle="Define reusable connectors, subscription plans, typed custom properties and organization roles. Tenants and projects inherit these definitions."/>
    <nav className="row-wrap" aria-label="Configuration sections">{[['properties','Properties'],['connectors','Connector definitions'],['plans','Subscription plans'],['roles','Organization roles']].map(([key,label])=><button key={key} aria-pressed={tab===key} className={'btn '+(tab===key?'primary':'')} onClick={()=>setTab(key)}>{label}</button>)}</nav>
    {tab==='properties'?<Properties/>:tab==='connectors'?<Connectors/>:tab==='plans'?<Plans/>:<Roles/>}
  </div>
}

function Connectors() {
  const view=useApi<Connector[]>('/api/platform/connector-definitions')
  const [form,setForm]=useState(emptyConnector)
  const {busy,run}=useAction()
  const exists=view.data?.some(c=>c.key===form.key)
  return <><section className="card card-pad stack"><h2>Reusable connector definitions</h2><p className="faint">Create a definition globally, grant it under a tenant, then enable it per project. Custom definitions store configuration; provider operations require an implemented adapter.</p>
    {view.error?<ErrorBox error={view.error} retry={view.reload}/>:!view.data?<Loading/>:<table className="table"><thead><tr><th>Connector</th><th>Category</th><th>Driver</th><th>Status</th><th/></tr></thead><tbody>{view.data.map(c=><tr key={c.key}><td>{c.name}<div className="faint">{c.key}</div></td><td>{c.category}</td><td>{c.driver}</td><td>{c.enabled?'Available':'Disabled'}</td><td><button className="btn sm" onClick={()=>setForm(c)}>Edit {c.name}</button></td></tr>)}</tbody></table>}</section>
    <form className="card card-pad stack" onSubmit={e=>{e.preventDefault();run(()=>api('/api/platform/connector-definitions',{method:'PUT',body:form}),'Connector definition saved').then(r=>{if(r){view.reload();setForm(emptyConnector)}})}}><h2>{exists?'Edit connector':'Add connector'}</h2>
      <div className="grid g2"><label className="field">Stable key<input required pattern="[a-z][a-z0-9_-]{1,49}" disabled={exists} value={form.key} onChange={e=>setForm({...form,key:e.target.value})}/></label><label className="field">Name<input required maxLength={100} value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
      <label className="field">Category<select value={form.category} onChange={e=>setForm({...form,category:e.target.value})}>{['MEDIA','ADS','STORAGE','OTHER'].map(c=><option key={c}>{c}</option>)}</select></label><label className="field">Adapter<select disabled={exists} value={form.driver} onChange={e=>setForm({...form,driver:e.target.value})}>{['CUSTOM','MUX','VAST','R2'].map(c=><option key={c}>{c}</option>)}</select></label></div>
      <label className="field">Description<input maxLength={500} value={form.description} onChange={e=>setForm({...form,description:e.target.value})}/></label><label className="row"><input type="checkbox" checked={form.enabled} onChange={e=>setForm({...form,enabled:e.target.checked})}/>Available globally</label>
      <div className="row-wrap"><button className="btn primary" disabled={busy}>Save connector definition</button><button type="button" className="btn" onClick={()=>setForm(emptyConnector)}>New definition</button></div>
    </form></>
}

function Properties() {
  const view=useApi<Property[]>('/api/platform/property-definitions')
  const connectors=useApi<Connector[]>('/api/platform/connector-definitions')
  const [form,setForm]=useState(emptyProperty)
  const [options,setOptions]=useState('')
  const {busy,run}=useAction()
  const exists=view.data?.some(p=>p.key===form.key)
  return <><section className="card card-pad stack"><h2>Dynamic property catalogue</h2><p className="faint">New keys and defaults are stored in the database and rendered from their type. Existing media/programming controls retain their domain validation. Properties add configuration; they do not introduce new runtime behavior without a consumer.</p>
    {view.error?<ErrorBox error={view.error} retry={view.reload}/>:!view.data?<Loading/>:<table className="table"><thead><tr><th>Property</th><th>Type / scope</th><th>Global default</th><th/></tr></thead><tbody>{view.data.map(p=><tr key={p.key}><td>{p.label}<div className="faint">{p.key}{p.connectorKey?` · ${p.connectorKey}`:''}{!p.enabled?' · Disabled':''}</div></td><td>{p.secret?'Secret':p.valueType}<div className="faint">{p.projectOverride?'Project overrides allowed':'Tenant-wide'}</div></td><td>{p.secret?p.defaultConfigured?'Configured (hidden)':'Not configured':p.defaultValue==null?'Not set':String(p.defaultValue)}</td><td><button className="btn sm" onClick={()=>{setForm({...p,defaultValue:p.secret?null:p.defaultValue});setOptions(p.options.join('\n'))}}>Edit {p.label}</button></td></tr>)}</tbody></table>}</section>
    <form className="card card-pad stack" onSubmit={e=>{e.preventDefault();run(()=>api('/api/platform/property-definitions',{method:'PUT',body:{...form,options:options.split('\n').map(o=>o.trim()).filter(Boolean),expectedRevision:form.revision}}),'Property definition saved').then(r=>{setForm(emptyProperty);setOptions('');if(r)view.reload()})}}>
      <h2>{exists?'Edit property':'Add property'}</h2><div className="grid g2"><label className="field">Stable property key<input required disabled={exists} pattern="[a-z][a-z0-9_.-]{1,79}" value={form.key} onChange={e=>setForm({...form,key:e.target.value})}/></label><label className="field">Display label<input required maxLength={100} value={form.label} onChange={e=>setForm({...form,label:e.target.value})}/></label>
      <label className="field">Value type<select disabled={exists} value={form.valueType} onChange={e=>setForm({...form,valueType:e.target.value,defaultValue:null,secret:false})}>{['STRING','NUMBER','BOOLEAN','SELECT'].map(t=><option key={t}>{t}</option>)}</select></label><label className="field">Connector binding<select disabled={exists} value={form.connectorKey??''} onChange={e=>setForm({...form,connectorKey:e.target.value||null})}><option value="">General project property</option>{connectors.data?.map(c=><option key={c.key} value={c.key}>{c.name}</option>)}</select></label></div>
      {form.valueType==='SELECT'&&<label className="field">Allowed values (one per line)<textarea required value={options} onChange={e=>setOptions(e.target.value)}/></label>}
      <div className="row-wrap"><label className="row"><input type="checkbox" disabled={exists||form.valueType!=='STRING'} checked={form.secret} onChange={e=>setForm({...form,secret:e.target.checked,defaultValue:null})}/>Secret (encrypted and write-only)</label><label className="row"><input type="checkbox" checked={form.projectOverride} onChange={e=>setForm({...form,projectOverride:e.target.checked})}/>Allow project overrides</label><label className="row"><input type="checkbox" checked={form.enabled} onChange={e=>setForm({...form,enabled:e.target.checked})}/>Enabled</label></div>
      <PropertyInput definition={{...form,options:options.split('\n').filter(Boolean)}} label={form.secret?'New global default (blank keeps stored secret)':'Global default (optional)'} value={form.defaultValue} onChange={value=>setForm({...form,defaultValue:value})}/>
      <div className="row-wrap"><button className="btn primary" disabled={busy}>Save property definition</button><button className="btn" type="button" onClick={()=>{setForm(emptyProperty);setOptions('')}}>New property</button>{exists&&form.defaultConfigured&&<button className="btn" type="button" disabled={busy} onClick={()=>run(()=>api(`/api/platform/property-definitions/${form.key}/default`,{method:'DELETE'}),'Global default cleared').then(r=>{if(r){view.reload();setForm({...form,defaultValue:null,defaultConfigured:false,revision:form.revision+1})}})}>Clear global default</button>}</div>
    </form></>
}

function Plans() {
  const view=useApi<Plan[]>('/api/platform/plans')
  const [form,setForm]=useState(emptyPlan)
  const [features,setFeatures]=useState('')
  const {busy,run}=useAction()
  return <><section className="card card-pad stack"><h2>Subscription plans</h2><p className="faint">Project and channel allowances apply across the tenant. Prices are plan metadata; no payment is collected here.</p>{view.error?<ErrorBox error={view.error} retry={view.reload}/>:!view.data?<Loading/>:<table className="table"><thead><tr><th>Plan</th><th>Allowances</th><th>Monthly price</th><th/></tr></thead><tbody>{view.data.map(p=><tr key={p.key}><td>{p.name}<div className="faint">{p.key}{!p.enabled?' · Unavailable for new assignments':''}</div></td><td>{p.maxProjects} projects / {p.maxChannels} channels</td><td>{p.currency} {p.monthlyPrice}</td><td><button className="btn sm" onClick={()=>{setForm(p);setFeatures(p.features.join('\n'))}}>Edit {p.name}</button></td></tr>)}</tbody></table>}</section>
    <form className="card card-pad stack" onSubmit={e=>{e.preventDefault();run(()=>api('/api/platform/plans',{method:'PUT',body:{...form,features:features.split('\n').map(f=>f.trim()).filter(Boolean)}}),'Plan saved').then(r=>{if(r){view.reload();setForm(emptyPlan);setFeatures('')}})}}><h2>Plan details</h2><div className="grid g2">
      <label className="field">Plan key<input required disabled={view.data?.some(p=>p.key===form.key)} pattern="[a-z][a-z0-9_-]{1,49}" value={form.key} onChange={e=>setForm({...form,key:e.target.value})}/></label><label className="field">Name<input required maxLength={100} value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
      <label className="field">Maximum projects<input required type="number" min={1} max={10000} value={form.maxProjects} onChange={e=>setForm({...form,maxProjects:Number(e.target.value)})}/></label><label className="field">Maximum channels across projects<input required type="number" min={0} max={100000} value={form.maxChannels} onChange={e=>setForm({...form,maxChannels:Number(e.target.value)})}/></label>
      <label className="field">Monthly price<input required type="number" step="0.01" min={0} value={form.monthlyPrice} onChange={e=>setForm({...form,monthlyPrice:Number(e.target.value)})}/></label><label className="field">Currency<input required pattern="[A-Z]{3}" value={form.currency} onChange={e=>setForm({...form,currency:e.target.value})}/></label></div>
      <label className="field">Feature keys (one per line)<textarea value={features} onChange={e=>setFeatures(e.target.value)}/><span className="hint">Known integrations use LIBRARY_SYNC, AD_WATERFALL_VIEW and AD_OWN_TAGS. Custom feature keys require a product consumer.</span></label><label className="row"><input type="checkbox" checked={form.enabled} onChange={e=>setForm({...form,enabled:e.target.checked})}/>Available for assignment</label><div className="row-wrap"><button className="btn primary" disabled={busy}>Save plan</button><button className="btn" type="button" onClick={()=>{setForm(emptyPlan);setFeatures('')}}>New plan</button></div>
    </form></>
}

function Roles() {
  const view=useApi<{roles:AccessRole[];permissions:string[]}>('/api/platform/access-roles')
  const initial={key:'',name:'',kind:'CUSTOMER',baseRole:'ADMIN',permissions:['VIEW'],enabled:true,expectedRevision:0}
  const [form,setForm]=useState(initial)
  const {busy,run}=useAction()
  const bases=form.kind==='PARTNER'?['PARTNER_ADMIN','PARTNER_VIEWER']:['ADMIN','CONTENT_MANAGER','CHANNEL_OPERATOR','AD_OPS','ANALYST']
  return <><section className="card card-pad stack"><h2>Configurable organization roles</h2><p className="faint">Customer projects and partner organizations can assign different roles to members. Permissions stay within a supported role ceiling. Built-in owners and partner administrators remain available for recovery.</p>{view.error?<ErrorBox error={view.error} retry={view.reload}/>:!view.data?<Loading/>:<table className="table"><thead><tr><th>Role</th><th>Organization type</th><th>Ceiling</th><th/></tr></thead><tbody>{view.data.roles.map(r=><tr key={r.key}><td>{r.name}<div className="faint">{r.key}{!r.enabled?' · Disabled':''}</div></td><td>{r.kind}</td><td>{r.baseRole}</td><td><button className="btn sm" onClick={()=>setForm({...r,permissions:r.permissions.split(','),expectedRevision:r.revision})}>Edit {r.name}</button></td></tr>)}</tbody></table>}</section>
    <form className="card card-pad stack" onSubmit={e=>{e.preventDefault();run(()=>api('/api/platform/access-roles',{method:'PUT',body:form}),'Role saved').then(r=>{if(r){view.reload();setForm(initial)}})}}><h2>Role definition</h2><div className="grid g2"><label className="field">Stable role key<input required pattern="[a-z][a-z0-9_-]{1,49}" disabled={view.data?.roles.some(r=>r.key===form.key)} value={form.key} onChange={e=>setForm({...form,key:e.target.value})}/></label><label className="field">Name<input required maxLength={100} value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
      <label className="field">Organization type<select disabled={view.data?.roles.some(r=>r.key===form.key)} value={form.kind} onChange={e=>setForm({...form,kind:e.target.value,baseRole:e.target.value==='PARTNER'?'PARTNER_ADMIN':'ADMIN',permissions:['VIEW']})}><option value="CUSTOMER">Customer</option><option value="PARTNER">Partner</option></select></label>
      <label className="field">Maximum privilege<select disabled={view.data?.roles.some(r=>r.key===form.key)} value={form.baseRole} onChange={e=>setForm({...form,baseRole:e.target.value,permissions:['VIEW']})}>{bases.map(b=><option key={b}>{b}</option>)}</select></label></div>
      <fieldset><legend>Permissions</legend><div className="row-wrap">{view.data?.permissions.map(p=><label className="row" key={p}><input type="checkbox" disabled={p==='VIEW'} checked={form.permissions.includes(p)} onChange={e=>setForm({...form,permissions:e.target.checked?[...form.permissions,p]:form.permissions.filter(x=>x!==p)})}/>{p}</label>)}</div></fieldset><label className="row"><input type="checkbox" checked={form.enabled} onChange={e=>setForm({...form,enabled:e.target.checked})}/>Enabled</label><div className="row-wrap"><button className="btn primary" disabled={busy}>Save role</button><button className="btn" type="button" onClick={()=>setForm(initial)}>New role</button></div>
    </form></>
}
