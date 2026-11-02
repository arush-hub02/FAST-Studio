import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api'
import { Confirm, ErrorBox, Icon, Loading, Modal, PageHead, Status, useAction, useApi } from '../../shared/ui'
import { ConfigurationEditor } from '../../shared/configuration/ConfigurationEditor'
import { ProjectFeatures, ProjectRoles } from '../../shared/configuration/Catalogue'
import type { Configuration, Plan, Project } from '../../shared/configuration/types'
import { OrgConnectors } from './Connectors'
import { TenantMediaEntitlements } from './MediaTypes'

// One customer tenant, opened from the Tenants list: side tabs for everything the platform manages for it. Tenant-wide
// tabs (overview, configuration) and project tabs (features, roles, connectors, media types), which work on the
// project chosen at the top of the side bar. The tab and project are in the address, so a link reopens the same place.
const TABS: { key: string; label: string; icon: string; project: boolean }[] = [
  { key: 'overview', label: 'Overview', icon: 'building', project: false },
  { key: 'configuration', label: 'Configuration', icon: 'sliders', project: false },
  { key: 'features', label: 'Features', icon: 'shield', project: true },
  { key: 'roles', label: 'Roles', icon: 'users', project: true },
  { key: 'connectors', label: 'Connectors', icon: 'link', project: true },
  { key: 'media', label: 'Media types', icon: 'film', project: true },
]

export default function TenantDetail() {
  const { tenantId = '' } = useParams()
  const view = useApi<Configuration>(`/api/platform/tenants/${tenantId}/settings`)
  const [params, setParams] = useSearchParams()
  if (view.error) return <div className="page"><ErrorBox error={view.error} retry={view.reload} /></div>
  if (!view.data) return <div className="page"><Loading /></div>
  const v = view.data
  const tab = TABS.find(t => t.key === params.get('tab')) ?? TABS[0]
  const projectId = v.projects.some(p => p.id === params.get('project')) ? params.get('project')! : v.projects[0]?.id ?? ''
  const go = (next: { tab?: string; project?: string }) => setParams({ tab: next.tab ?? tab.key, project: next.project ?? projectId }, { replace: true })
  const project = v.projects.find(p => p.id === projectId)
  return <div className="page stack">
    <PageHead title={v.tenant.name} subtitle={<span><Link to="/platform">Tenants</Link> / {v.tenant.name} · {v.tenant.planKey} · <Status value={v.tenant.status} /></span>} />
    <div className="side-tabs-layout">
      <nav className="side-tabs card" aria-label="Tenant settings">
        <label className="field side-tabs-project"><span>Project</span>
          <select value={projectId} onChange={e => go({ project: e.target.value })} disabled={!v.projects.length}>
            {v.projects.length ? v.projects.map(p => <option key={p.id} value={p.id}>{p.name} (/{p.slug})</option>) : <option>No projects yet</option>}
          </select></label>
        <div className="side-tabs-heading">Tenant</div>
        {TABS.filter(t => !t.project).map(t => <SideTab key={t.key} t={t} active={tab.key === t.key} onClick={() => go({ tab: t.key })} />)}
        <div className="side-tabs-heading">Project{project ? `: ${project.name}` : ''}</div>
        {TABS.filter(t => t.project).map(t => <SideTab key={t.key} t={t} active={tab.key === t.key} disabled={!projectId} onClick={() => go({ tab: t.key })} />)}
      </nav>
      <div className="stack" style={{ minWidth: 0 }}>
        {tab.key === 'overview' && <Overview id={tenantId} v={v} reload={view.reload} open={p => go({ tab: 'features', project: p })} />}
        {tab.key === 'configuration' && <Configure id={tenantId} v={v} reload={view.reload} />}
        {tab.project && !projectId && <section className="card card-pad faint">Add a project on the Overview tab first.</section>}
        {tab.key === 'features' && projectId && <ProjectFeatures key={projectId} base={`/api/platform/orgs/${projectId}/project-features`} editable platform />}
        {tab.key === 'roles' && projectId && <ProjectRoles key={projectId} base={`/api/platform/orgs/${projectId}/roles`} editable kind="CUSTOMER" />}
        {tab.key === 'connectors' && projectId && <OrgConnectors key={projectId} orgId={projectId} />}
        {tab.key === 'media' && projectId && <TenantMediaEntitlements key={projectId} orgId={projectId} />}
      </div>
    </div>
  </div>
}

function SideTab({ t, active, disabled, onClick }: { t: typeof TABS[number]; active: boolean; disabled?: boolean; onClick: () => void }) {
  return <button type="button" className={active ? 'active' : ''} aria-current={active ? 'page' : undefined} disabled={disabled} onClick={onClick}>
    <Icon name={t.icon} size={15} /><span>{t.label}</span></button>
}

function Overview({ id, v, reload, open }: { id: string; v: Configuration; reload: () => void; open: (project: string) => void }) {
  const plans = useApi<Plan[]>('/api/platform/plans')
  const [creating, setCreating] = useState(false)
  const [confirmStatus, setConfirmStatus] = useState(false)
  const [toggling, setToggling] = useState<Project | null>(null)
  const { busy, run } = useAction()
  const base = `/api/platform/tenants/${id}`
  const subscription = (planKey: string, status: string) => run(() => api(`${base}/subscription`, { method: 'PUT', body: { planKey, status, expectedRevision: v.tenant.revision } }), 'Subscription saved').then(r => { if (r) reload() })
  return <>
    <section className="card card-pad stack">
      <h2>Subscription</h2>
      {!plans.data ? <Loading /> : <div className="grid g2">
        <label className="field"><span>Plan</span><select disabled={busy} value={v.tenant.planKey} onChange={e => subscription(e.target.value, v.tenant.subscriptionStatus)}>
          {plans.data.filter(p => p.enabled || p.key === v.tenant.planKey).map(p => <option key={p.key} value={p.key}>{p.name} · {p.maxProjects} projects / {p.maxChannels} channels</option>)}</select></label>
        <label className="field"><span>Status</span><select disabled={busy} value={v.tenant.subscriptionStatus} onChange={e => subscription(v.tenant.planKey, e.target.value)}>
          {['TRIAL', 'ACTIVE', 'PAUSED', 'CANCELLED'].map(s => <option key={s}>{s}</option>)}</select></label>
      </div>}
      <div className="row-wrap"><button className="btn danger" onClick={() => setConfirmStatus(true)}>{v.tenant.status === 'ACTIVE' ? 'Suspend tenant' : 'Re-activate tenant'}</button></div>
      <p className="faint small">Tenant suspension blocks management across its projects and keeps existing media.</p>
    </section>
    <section className="card card-pad stack">
      <div className="between"><h2 style={{ margin: 0 }}>Projects</h2><button className="btn primary" onClick={() => setCreating(true)}><Icon name="plus" size={15} /><span>Add project</span></button></div>
      <p className="faint small">Projects have separate libraries, channels, memberships and partner shares.</p>
      <table className="table"><thead><tr><th>Project</th><th>Status</th><th>Members</th><th /></tr></thead><tbody>{v.projects.map(p => <tr key={p.id}>
        <td><b>{p.name}</b><div className="faint">/{p.slug}</div></td><td><Status value={p.status} /></td><td>{p.members}</td>
        <td><div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
          <button className="btn sm" onClick={() => open(p.id)}>Open {p.name}</button>
          <button className="btn sm" disabled={busy} onClick={() => setToggling(p)}>{p.status === 'ACTIVE' ? 'Suspend' : 'Re-activate'}</button></div></td>
      </tr>)}</tbody></table>
      {!v.projects.length && <p className="faint">No projects yet.</p>}
    </section>
    {toggling && <Confirm title={toggling.status === 'ACTIVE' ? `Suspend ${toggling.name}?` : `Re-activate ${toggling.name}?`} text="Only this project is affected. Channels on air keep playing; nothing is deleted." confirmLabel="Confirm"
      onClose={() => setToggling(null)} onConfirm={() => run(() => api(`/api/platform/orgs/${toggling.id}/status`, { body: { status: toggling.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' } }), 'Project updated').then(reload)} />}
    {confirmStatus && <Confirm title="Change tenant access?" text="This changes management access for every project under this customer tenant. Existing data is retained." confirmLabel="Confirm"
      onClose={() => setConfirmStatus(false)} onConfirm={() => run(() => api(`${base}/status`, { body: { status: v.tenant.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' } }), 'Tenant updated').then(reload)} />}
    {creating && <CreateProject tenant={id} onClose={() => setCreating(false)} done={reload} />}
  </>
}

function Configure({ id, v, reload }: { id: string; v: Configuration; reload: () => void }) {
  const [project, setProject] = useState('')
  const base = `/api/platform/tenants/${id}`
  return <>
    <section className="card card-pad stack">
      <label className="field" style={{ maxWidth: 420 }}><span>Configuration scope</span><select value={project} onChange={e => setProject(e.target.value)}>
        <option value="">Tenant defaults (all projects)</option>{v.projects.map(p => <option value={p.id} key={p.id}>Project: {p.name} (/{p.slug})</option>)}</select></label>
      <p className="faint small">Tenant defaults apply to every project; a project value overrides it for that project only.</p>
    </section>
    <ConfigurationEditor key={project || 'tenant'} base={project ? `${base}/projects/${project}/settings` : `${base}/settings`} platform onSaved={reload} />
  </>
}

function CreateProject({ tenant, onClose, done }: { tenant: string; onClose: () => void; done: () => void }) {
  const owners = useApi<{ id: string; name: string; email: string }[]>(`/api/platform/tenants/${tenant}/owners`)
  const [form, setForm] = useState({ name: '', slug: '', country: 'IN', timezone: 'Asia/Kolkata', ownerUserId: '' })
  const { busy, run } = useAction()
  return <Modal title="New project" subtitle="A separate content workspace under this customer tenant." onClose={onClose}><form className="stack" onSubmit={e => { e.preventDefault(); run(() => api(`/api/platform/tenants/${tenant}/projects`, { body: form }), 'Project created').then(r => { if (r) { done(); onClose() } }) }}>
    <label className="field">Project name<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
    <label className="field">Unique project slug<input required pattern="[a-z0-9\-]{3,40}" value={form.slug} onChange={e => setForm({ ...form, slug: e.target.value })} /></label>
    <div className="grid g2"><label className="field">Country<input required pattern="[A-Z]{2}" value={form.country} onChange={e => setForm({ ...form, country: e.target.value })} /></label><label className="field">Timezone<input required value={form.timezone} onChange={e => setForm({ ...form, timezone: e.target.value })} /></label></div>
    {owners.error ? <ErrorBox error={owners.error} retry={owners.reload} /> : <label className="field">Initial project owner<select required value={form.ownerUserId} onChange={e => setForm({ ...form, ownerUserId: e.target.value })}><option value="">Choose an existing tenant owner/admin</option>{owners.data?.map(o => <option value={o.id} key={o.id}>{o.name} · {o.email}</option>)}</select></label>}
    <button className="btn primary" disabled={busy}>Create project</button>
  </form></Modal>
}
