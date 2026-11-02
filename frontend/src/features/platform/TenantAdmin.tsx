import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ErrorBox, Loading, PageHead, Status, useApi } from '../../shared/ui'
import { CreateOrg } from './PartnerOrganizations'
import type { CustomerTenant } from '../../shared/configuration/types'

// The customer tenants list. Clicking a tenant opens its detail page (TenantDetail) with side tabs for everything the
// platform manages for it: subscription and projects, configuration, and each project's features, roles, connectors
// and media types.
export default function TenantAdmin() {
  const list = useApi<CustomerTenant[]>('/api/platform/tenants')
  const [creating, setCreating] = useState(false)
  const nav = useNavigate()
  return <div className="page stack"><PageHead title="Tenants" subtitle="Customer accounts own projects, subscriptions and configuration. Open a tenant to manage it. Partner organizations have a separate directory and explicit project sharing." actions={<><Link className="btn" to="/platform/partners">Partner organizations</Link><button className="btn primary" onClick={() => setCreating(true)}>New tenant</button></>} />
    {list.error ? <ErrorBox error={list.error} retry={list.reload} /> : !list.data ? <Loading /> : <section className="card" style={{ overflowX: 'auto' }}>
      <table className="table"><thead><tr><th>Customer tenant</th><th>Status</th><th>Subscription</th><th>Projects</th><th>Channels</th><th /></tr></thead>
        <tbody>{list.data.map(t => <tr key={t.id} className="clickable-row" onClick={() => nav(`/platform/tenants/${t.id}`)}>
          <td><Link to={`/platform/tenants/${t.id}`} onClick={e => e.stopPropagation()}><b>{t.name}</b></Link><div className="faint">{t.slug} · Customer organization</div></td>
          <td><Status value={t.status} /></td><td>{t.planKey}<div className="faint">{t.subscriptionStatus}</div></td><td>{t.projects}</td><td>{t.channels}</td>
          <td><Link className="btn sm" to={`/platform/tenants/${t.id}`} onClick={e => e.stopPropagation()}>Manage {t.name}</Link></td>
        </tr>)}</tbody></table>
      {!list.data.length && <p className="card-pad faint">Create a customer tenant to begin.</p>}</section>}
    {creating && <CreateOrg kind="CUSTOMER" onClose={() => setCreating(false)} onDone={list.reload} />}
  </div>
}
