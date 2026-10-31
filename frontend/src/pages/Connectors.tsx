import { useEffect, useState } from 'react'
import { api } from '../api'
import { AdSources } from '../components/AdSources'
import { MuxConnector } from '../components/MuxConnector'
import { TenantMediaEntitlements } from './Settings'
import { ErrorBox, Icon, Loading, PageHead, useAction, useApi } from '../ui'

// Platform administrator: everything a company's channels connect to (content platforms, media storage, ad servers)
// is configured here, per company. Companies never see credentials or tag URLs; feature switches decide what they see.
type Org = { id: string; name: string; slug: string; kind: 'CUSTOMER' | 'PARTNER'; status: string }
type Overview = {
  orgId: string; orgName: string; features: string[]; featureCatalog: { key: string; label: string }[]
  storage: { provider: string; configured: boolean; publicUrl: string; videosReady: number; videosOnEdge: number }
}

export default function Connectors() {
  const orgs = useApi<Org[]>('/api/platform/orgs')
  const customers = (orgs.data ?? []).filter(o => o.kind === 'CUSTOMER')
  const [orgId, setOrgId] = useState('')
  useEffect(() => { if (!orgId && customers.length) setOrgId(customers[0].id) }, [customers, orgId])

  if (orgs.error?.status === 403) return <div className="page"><div className="callout bad"><Icon name="shield" />Platform administrators only.</div></div>
  return (
    <div className="page">
      <PageHead title="Connectors" subtitle="What each company's channels connect to: content platforms, media storage and ad servers. Companies never see credentials or tag URLs; the feature switches decide what they see." />
      {orgs.error ? <ErrorBox error={orgs.error} retry={orgs.reload} /> : !orgs.data ? <Loading /> : (
        <label className="row" style={{ gap: 10, marginBottom: 16 }}>
          <b className="small">Company</b>
          <select className="input" style={{ maxWidth: 320 }} value={orgId} onChange={e => setOrgId(e.target.value)}>
            {customers.map(o => <option key={o.id} value={o.id}>{o.name} (/{o.slug}){o.status !== 'ACTIVE' ? ' · suspended' : ''}</option>)}
          </select>
        </label>
      )}
      {orgId && <OrgConnectors key={orgId} orgId={orgId} />}
    </div>
  )
}

function OrgConnectors({ orgId }: { orgId: string }) {
  const view = useApi<Overview>(`/api/platform/orgs/${orgId}/connectors`)
  const { busy, run } = useAction()
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const v = view.data
  const toggle = (key: string) => {
    const features = v.features.includes(key) ? v.features.filter(f => f !== key) : [...v.features, key]
    run(() => api(`/api/platform/orgs/${orgId}/features`, { method: 'PUT', body: { features } }), 'Saved').then(view.reload)
  }

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="grid g2" style={{ gap: 16 }}>
        <div className="card card-pad stack" style={{ gap: 10 }}>
          <h3 style={{ margin: 0, fontSize: 16 }}>Content sources</h3>
          <p className="faint small" style={{ margin: 0 }}>Where {v.orgName}'s videos come from. Whatever the source, FAST Studio re-cuts them into 5-second segments for playout.</p>
          <div className="row" style={{ gap: 8 }}><span className="badge ok">Built in</span> Upload in FAST Studio</div>
          <div className="row" style={{ gap: 8 }}><span className="badge ok">Built in</span> Ad creatives fetched from connected ad servers</div>
          <MuxConnector orgId={orgId} />
        </div>
        <div className="card card-pad stack" style={{ gap: 10 }}>
          <h3 style={{ margin: 0, fontSize: 16 }}>Media storage and delivery</h3>
          <div className="kv" style={{ gridTemplateColumns: '150px 1fr' }}>
            <span>Provider</span><b>{v.storage.provider}</b>
            <span>Status</span><b>{v.storage.configured ? 'Connected' : 'Not configured (set FASTSTUDIO_EDGE_SECRET)'}</b>
            <span>Viewer address</span><span className="mono small">{v.storage.publicUrl}</span>
            <span>Videos on the edge</span><b>{v.storage.videosOnEdge} of {v.storage.videosReady} ready</b>
          </div>
        </div>
      </div>

      <AdSources base={`/api/platform/orgs/${orgId}/ad-sources`} mode="platform" />
      <TenantMediaEntitlements orgId={orgId} />

      <div className="card card-pad stack" style={{ gap: 10 }}>
        <h3 style={{ margin: 0, fontSize: 16 }}>What {v.orgName} can see</h3>
        <p className="faint small" style={{ margin: 0 }}>Feature switches for this company. Off: the company doesn't see the connector at all.</p>
        {v.featureCatalog.map(f => (
          <label key={f.key} className="row" style={{ gap: 10, cursor: 'pointer' }}>
            <input type="checkbox" checked={v.features.includes(f.key)} disabled={busy} onChange={() => toggle(f.key)} />
            <span>{f.label}</span>
          </label>
        ))}
      </div>
    </div>
  )
}
