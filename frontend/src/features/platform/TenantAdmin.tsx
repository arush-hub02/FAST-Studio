import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../shared/api'
import { Confirm, ErrorBox, Loading, Modal, PageHead, Status, useAction, useApi } from '../../shared/ui'
import { CreateOrg } from './PartnerOrganizations'
import { ConfigurationEditor } from '../../shared/configuration/ConfigurationEditor'
import type { CustomerTenant, Configuration, Plan, Project } from '../../shared/configuration/types'

export default function TenantAdmin() {
  const list=useApi<CustomerTenant[]>('/api/platform/tenants')
  const [creating,setCreating]=useState(false)
  const [selected,setSelected]=useState('')
  return <div className="page stack"><PageHead title="Tenants" subtitle="Customer accounts own projects, subscriptions and configuration. Partner organizations have a separate directory and explicit project sharing." actions={<><Link className="btn" to="/platform/partners">Partner organizations</Link><button className="btn primary" onClick={()=>setCreating(true)}>New tenant</button></>}/>
    {list.error ? <ErrorBox error={list.error} retry={list.reload}/> : !list.data ? <Loading/> : <section className="card" style={{overflowX:'auto'}}><table className="table"><thead><tr><th>Customer tenant</th><th>Status</th><th>Subscription</th><th>Projects</th><th>Channels</th><th/></tr></thead><tbody>{list.data.map(t=><tr key={t.id}><td><b>{t.name}</b><div className="faint">{t.slug} · Customer organization</div></td><td><Status value={t.status}/></td><td>{t.planKey}<div className="faint">{t.subscriptionStatus}</div></td><td>{t.projects}</td><td>{t.channels}</td><td><button className="btn sm" onClick={()=>setSelected(selected===t.id?'':t.id)}>Manage {t.name}</button></td></tr>)}</tbody></table>{!list.data.length&&<p className="card-pad faint">Create a customer tenant to begin.</p>}</section>}
    {selected&&<TenantSettings key={selected} id={selected} changed={list.reload}/>}
    {creating&&<CreateOrg kind="CUSTOMER" onClose={()=>setCreating(false)} onDone={list.reload}/>}
  </div>
}

function TenantSettings({id,changed}:{id:string;changed:()=>void}) {
  const view=useApi<Configuration>(`/api/platform/tenants/${id}/settings`)
  const plans=useApi<Plan[]>('/api/platform/plans')
  const [project,setProject]=useState('')
  const [creating,setCreating]=useState(false)
  const [confirmStatus,setConfirmStatus]=useState(false)
  const [toggling,setToggling]=useState<Project|null>(null)
  const {busy,run}=useAction()
  const reload=()=>{view.reload();changed()}
  if(view.error)return <ErrorBox error={view.error} retry={view.reload}/>
  if(!view.data||!plans.data)return <Loading/>
  const v=view.data
  const base=`/api/platform/tenants/${id}`
  return <section className="stack"><div className="card card-pad stack"><h2>Manage {v.tenant.name}</h2>
    <div className="grid g2"><label className="field"><span>Subscription plan</span><select disabled={busy} value={v.tenant.planKey} onChange={e=>run(()=>api(`${base}/subscription`,{method:'PUT',body:{planKey:e.target.value,status:v.tenant.subscriptionStatus,expectedRevision:v.tenant.revision}}),'Plan saved').then(r=>{if(r)reload()})}>{plans.data.filter(p=>p.enabled||p.key===v.tenant.planKey).map(p=><option key={p.key} value={p.key}>{p.name} · {p.maxProjects} projects / {p.maxChannels} channels</option>)}</select></label>
    <label className="field"><span>Subscription status</span><select disabled={busy} value={v.tenant.subscriptionStatus} onChange={e=>run(()=>api(`${base}/subscription`,{method:'PUT',body:{planKey:v.tenant.planKey,status:e.target.value,expectedRevision:v.tenant.revision}}),'Subscription saved').then(r=>{if(r)reload()})}>{['TRIAL','ACTIVE','PAUSED','CANCELLED'].map(s=><option key={s}>{s}</option>)}</select></label></div>
    <div className="row-wrap"><label className="field"><span>Configuration scope</span><select value={project} onChange={e=>setProject(e.target.value)}><option value="">Tenant defaults</option>{v.projects.map(p=><option value={p.id} key={p.id}>Project: {p.name} (/{p.slug})</option>)}</select></label><button className="btn" onClick={()=>setCreating(true)}>Add project</button><button className="btn danger" onClick={()=>setConfirmStatus(true)}>{v.tenant.status==='ACTIVE'?'Suspend tenant':'Re-activate tenant'}</button></div>
    <p className="faint small">Projects have separate libraries, channels, memberships and partner shares. Tenant suspension blocks management across its projects and keeps existing media.</p>
    {/* Per-project suspension uses the existing organization status endpoint (a project is a CUSTOMER organization row). */}
    <table className="table"><thead><tr><th>Project</th><th>Status</th><th>Members</th><th/></tr></thead><tbody>{v.projects.map(p=><tr key={p.id}><td>{p.name}<div className="faint">/{p.slug}</div></td><td><Status value={p.status}/></td><td>{p.members}</td><td><button className="btn sm" disabled={busy} onClick={()=>setToggling(p)}>{p.status==='ACTIVE'?`Suspend ${p.name}`:`Re-activate ${p.name}`}</button></td></tr>)}</tbody></table>
    </div>
    {toggling&&<Confirm title={toggling.status==='ACTIVE'?`Suspend ${toggling.name}?`:`Re-activate ${toggling.name}?`} text="Only this project is affected. Channels on air keep playing; nothing is deleted." confirmLabel="Confirm" onClose={()=>setToggling(null)} onConfirm={()=>run(()=>api(`/api/platform/orgs/${toggling.id}/status`,{body:{status:toggling.status==='ACTIVE'?'SUSPENDED':'ACTIVE'}}),'Project updated').then(reload)}/>}
    <ConfigurationEditor key={project||'tenant'} base={project?`${base}/projects/${project}/settings`:`${base}/settings`} platform onSaved={reload}/>
    {creating&&<CreateProject tenant={id} onClose={()=>setCreating(false)} done={reload}/>}
    {confirmStatus&&<Confirm title="Change tenant access?" text="This changes management access for every project under this customer tenant. Existing data is retained." confirmLabel="Confirm" onClose={()=>setConfirmStatus(false)} onConfirm={()=>run(()=>api(`${base}/status`,{body:{status:v.tenant.status==='ACTIVE'?'SUSPENDED':'ACTIVE'}}),'Tenant updated').then(reload)}/>}
  </section>
}

function CreateProject({tenant,onClose,done}:{tenant:string;onClose:()=>void;done:()=>void}) {
  const owners=useApi<{id:string;name:string;email:string}[]>(`/api/platform/tenants/${tenant}/owners`)
  const [form,setForm]=useState({name:'',slug:'',country:'IN',timezone:'Asia/Kolkata',ownerUserId:''})
  const {busy,run}=useAction()
  return <Modal title="New project" subtitle="A separate content workspace under this customer tenant." onClose={onClose}><form className="stack" onSubmit={e=>{e.preventDefault();run(()=>api(`/api/platform/tenants/${tenant}/projects`,{body:form}),'Project created').then(r=>{if(r){done();onClose()}})}}>
    <label className="field">Project name<input required maxLength={80} value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
    <label className="field">Unique project slug<input required pattern="[a-z0-9-]{3,40}" value={form.slug} onChange={e=>setForm({...form,slug:e.target.value})}/></label>
    <div className="grid g2"><label className="field">Country<input required pattern="[A-Z]{2}" value={form.country} onChange={e=>setForm({...form,country:e.target.value})}/></label><label className="field">Timezone<input required value={form.timezone} onChange={e=>setForm({...form,timezone:e.target.value})}/></label></div>
    {owners.error?<ErrorBox error={owners.error} retry={owners.reload}/>:<label className="field">Initial project owner<select required value={form.ownerUserId} onChange={e=>setForm({...form,ownerUserId:e.target.value})}><option value="">Choose an existing tenant owner/admin</option>{owners.data?.map(o=><option value={o.id} key={o.id}>{o.name} · {o.email}</option>)}</select></label>}
    <button className="btn primary" disabled={busy}>Create project</button>
  </form></Modal>
}
