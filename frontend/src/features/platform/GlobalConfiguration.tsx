import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api'
import { Empty, ErrorBox, Icon, Loading, Modal, PageHead, useAction, useApi } from '../../shared/ui'
import { PropertyInput } from '../../shared/configuration/ConfigurationEditor'
import type { AccessRole, Connector, Plan, Property } from '../../shared/configuration/types'
import { GlobalCatalogue, PermissionPicker, type Catalogue } from '../../shared/configuration/Catalogue'

const emptyConnector: Connector = { key: '', name: '', category: 'MEDIA', driver: 'CUSTOM', enabled: true, description: '' }
const emptyPlan: Plan = { key: '', name: '', enabled: true, maxProjects: 1, maxChannels: 10, monthlyPrice: 0, currency: 'USD', features: [] }
const emptyProperty: Property = { key: '', label: '', valueType: 'STRING', connectorKey: null, secret: false, projectOverride: true, enabled: true, options: [], defaultValue: null, defaultConfigured: false, revision: 0 }

export default function GlobalConfiguration() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = searchParams.get('tab') || 'connectors'
  const setTab = (t: string) => setSearchParams({ tab: t }, { replace: true })

  const tabs: [string, string, string][] = [
    ['connectors', 'Connector definitions', 'link'],
    ['properties', 'Properties', 'sliders'],
    ['capabilities', 'Features & permissions', 'shield'],
    ['plans', 'Subscription plans', 'dollarSign'],
    ['roles', 'Organization roles', 'users'],
  ]

  return (
    <div className="page stack" style={{ gap: 20 }}>
      <PageHead
        title="Global configuration"
        subtitle="Define reusable connectors, subscription plans, typed custom properties, features and permissions, and organization roles. Tenants and projects inherit these definitions."
      />

      <nav className="row-wrap" aria-label="Configuration sections" style={{ gap: 6, borderBottom: '1px solid var(--border)', paddingBottom: 10 }}>
        {tabs.map(([key, label, iconName]) => (
          <button
            key={key}
            type="button"
            aria-pressed={tab === key}
            className={`config-tab-btn ${tab === key ? 'active' : ''}`}
            onClick={() => setTab(key)}
          >
            <Icon name={iconName} size={15} />
            <span>{label}</span>
          </button>
        ))}
      </nav>

      {tab === 'connectors' && <Connectors />}
      {tab === 'properties' && <Properties />}
      {tab === 'capabilities' && <GlobalCatalogue />}
      {tab === 'plans' && <Plans />}
      {tab === 'roles' && <Roles />}
    </div>
  )
}

// ============================================================================
// Connector Icons & Visual Components
// ============================================================================

export function ConnectorIcon({ connector, size = 52 }: { connector?: Partial<Connector> | null; size?: number }) {
  const driver = connector?.driver?.toUpperCase() || ''
  const key = connector?.key?.toLowerCase() || ''
  const name = connector?.name?.toLowerCase() || ''
  const cat = connector?.category?.toUpperCase() || ''

  // Mux Video Platform
  if (driver === 'MUX' || key === 'mux' || name.includes('mux')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(255, 43, 109, 0.22), rgba(250, 78, 67, 0.08))',
          borderColor: 'rgba(255, 43, 109, 0.35)',
        }}
        title="Mux Video Platform"
      >
        <svg width={size * 0.65} height={size * 0.65} viewBox="0 0 100 100" fill="none" aria-hidden="true">
          <polygon points="12,24 38,50 12,76" fill="#FF2B6D" />
          <polygon points="38,50 64,24 64,76" fill="#FA4E43" />
          <polygon points="64,24 88,50 64,76" fill="#FF755A" />
        </svg>
      </div>
    )
  }

  // Cloudflare R2 Storage
  if (driver === 'R2' || key === 'r2' || name.includes('cloudflare') || name.includes('r2')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(243, 128, 32, 0.22), rgba(250, 173, 63, 0.08))',
          borderColor: 'rgba(243, 128, 32, 0.35)',
        }}
        title="Cloudflare R2 Storage"
      >
        <svg width={size * 0.65} height={size * 0.65} viewBox="0 0 48 48" fill="none" aria-hidden="true">
          <path
            d="M36 27c3.3 0 6-2.7 6-6 0-3.1-2.3-5.6-5.4-5.9C35.5 11 31 7.5 25.5 7.5c-4.8 0-8.8 2.9-10.4 7.1C14.2 14.2 13.1 14 12 14c-4.4 0-8 3.6-8 8 0 4.4 3.6 8 8 8h24z"
            fill="rgba(243, 128, 32, 0.25)"
            stroke="#F38020"
            strokeWidth="2.4"
            strokeLinejoin="round"
          />
          <rect x="17" y="21" width="16" height="13" rx="2.5" fill="#FAAD3F" stroke="#F38020" strokeWidth="1.6" />
          <path d="M21 26h8M21 29h8M21 32h5" stroke="#1c1917" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </div>
    )
  }

  // VAST / Ad Demand Waterfall
  if (driver === 'VAST' || key === 'vast' || cat === 'ADS' || name.includes('ad ') || name.includes('vast') || name.includes('demand')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.2), rgba(6, 182, 212, 0.08))',
          borderColor: 'rgba(16, 185, 129, 0.35)',
        }}
        title="VAST Ad Demand"
      >
        <svg width={size * 0.65} height={size * 0.65} viewBox="0 0 48 48" fill="none" aria-hidden="true">
          <circle cx="24" cy="24" r="18" stroke="#10B981" strokeWidth="2.2" strokeDasharray="4 2.5" />
          <circle cx="24" cy="24" r="11" fill="rgba(16,185,129,0.18)" stroke="#10B981" strokeWidth="2" />
          <circle cx="24" cy="24" r="4.5" fill="#10B981" />
          <path d="M24 6v4M24 38v4M6 24h4M38 24h4" stroke="#06B6D4" strokeWidth="2" strokeLinecap="round" />
        </svg>
      </div>
    )
  }

  // Storage (S3, Cloud, Buckets)
  if (cat === 'STORAGE' || key.includes('s3') || name.includes('storage') || name.includes('s3')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(14, 165, 233, 0.2), rgba(99, 102, 241, 0.08))',
          borderColor: 'rgba(14, 165, 233, 0.35)',
        }}
        title="Object Storage Provider"
      >
        <svg width={size * 0.65} height={size * 0.65} viewBox="0 0 48 48" fill="none" aria-hidden="true">
          <ellipse cx="24" cy="14" rx="14" ry="5.5" fill="rgba(14, 165, 233, 0.25)" stroke="#0EA5E9" strokeWidth="2.4" />
          <path d="M10 14v10c0 3 6.3 5.5 14 5.5s14-2.5 14-5.5V14" stroke="#0EA5E9" strokeWidth="2.4" />
          <path d="M10 24v10c0 3 6.3 5.5 14 5.5s14-2.5 14-5.5V24" stroke="#0EA5E9" strokeWidth="2.4" />
        </svg>
      </div>
    )
  }

  // Video & Media Provider
  if (cat === 'MEDIA') {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(139, 92, 246, 0.2), rgba(236, 72, 153, 0.08))',
          borderColor: 'rgba(139, 92, 246, 0.35)',
        }}
        title="Media Provider"
      >
        <svg width={size * 0.65} height={size * 0.65} viewBox="0 0 48 48" fill="none" aria-hidden="true">
          <rect x="8" y="10" width="32" height="28" rx="4" stroke="#8B5CF6" strokeWidth="2.4" />
          <path d="M8 18h32M8 30h32M16 10v8M16 30v8M32 10v8M32 30v8" stroke="#8B5CF6" strokeWidth="2" />
          <polygon points="21,21 29,24 21,27" fill="#EC4899" />
        </svg>
      </div>
    )
  }

  // Custom / Other Adapter
  return (
    <div
      className="connector-icon-wrap"
      style={{
        width: size,
        height: size,
        background: 'linear-gradient(135deg, rgba(99, 102, 241, 0.2), rgba(59, 130, 246, 0.08))',
        borderColor: 'rgba(99, 102, 241, 0.35)',
      }}
      title="Custom Integration Adapter"
    >
      <svg width={size * 0.65} height={size * 0.65} viewBox="0 0 48 48" fill="none" aria-hidden="true">
        <rect x="14" y="14" width="20" height="20" rx="5" fill="rgba(99, 102, 241, 0.2)" stroke="#6366F1" strokeWidth="2.4" />
        <path d="M20 14V8M28 14V8M24 34v6M14 24H8M34 24h6" stroke="#3B82F6" strokeWidth="2.4" strokeLinecap="round" />
        <circle cx="24" cy="24" r="3.5" fill="#6366F1" />
      </svg>
    </div>
  )
}

function CategoryBadge({ category }: { category: string }) {
  if (category === 'MEDIA') return <span className="badge" style={{ color: '#c084fc', borderColor: 'rgba(192, 132, 252, 0.3)', background: 'rgba(192, 132, 252, 0.1)' }}><Icon name="film" size={12} /> Media</span>
  if (category === 'ADS') return <span className="badge ok"><Icon name="megaphone" size={12} /> Advertising</span>
  if (category === 'STORAGE') return <span className="badge info"><Icon name="globe" size={12} /> Storage</span>
  return <span className="badge"><Icon name="sliders" size={12} /> {category}</span>
}

function DriverChip({ driver }: { driver: string }) {
  if (driver === 'MUX') return <span className="chip" style={{ color: '#ff2b6d' }}><Icon name="play" size={11} /> Mux Driver</span>
  if (driver === 'VAST') return <span className="chip" style={{ color: '#10b981' }}><Icon name="target" size={11} /> VAST Waterfall</span>
  if (driver === 'R2') return <span className="chip" style={{ color: '#f38020' }}><Icon name="globe" size={11} /> Cloudflare R2</span>
  return <span className="chip"><Icon name="code" size={11} /> Custom Adapter</span>
}

// ============================================================================
// 1. Connectors (Cards Grid + Modal Form)
// ============================================================================

function Connectors() {
  const view = useApi<Connector[]>('/api/platform/connector-definitions')
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState<Connector>(emptyConnector)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<'ALL' | 'MEDIA' | 'ADS' | 'STORAGE' | 'OTHER'>('ALL')
  const { busy, run } = useAction()

  const exists = Boolean(view.data?.some(c => c.key === form.key))

  const openCreate = () => {
    setForm(emptyConnector)
    setModalOpen(true)
  }

  const openEdit = (c: Connector) => {
    setForm(c)
    setModalOpen(true)
  }

  const filtered = useMemo(() => {
    if (!view.data) return []
    return view.data.filter(c => {
      const matchFilter = filter === 'ALL' || c.category === filter
      const q = search.trim().toLowerCase()
      const matchSearch = !q || c.name.toLowerCase().includes(q) || c.key.toLowerCase().includes(q) || (c.description || '').toLowerCase().includes(q)
      return matchFilter && matchSearch
    })
  }, [view.data, filter, search])

  const counts = useMemo(() => {
    const list = view.data ?? []
    return {
      ALL: list.length,
      MEDIA: list.filter(c => c.category === 'MEDIA').length,
      ADS: list.filter(c => c.category === 'ADS').length,
      STORAGE: list.filter(c => c.category === 'STORAGE').length,
      OTHER: list.filter(c => c.category === 'OTHER').length,
    }
  }, [view.data])

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    run(
      () => api<Connector[]>('/api/platform/connector-definitions', { method: 'PUT', body: form }),
      exists ? `Connector "${form.name}" updated` : `Connector "${form.name}" created`
    ).then(r => {
      if (r) {
        view.reload()
        setModalOpen(false)
        setForm(emptyConnector)
      }
    })
  }

  return (
    <div className="stack" style={{ gap: 18 }}>
      <div className="card card-pad stack" style={{ gap: 14 }}>
        <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div className="row" style={{ gap: 10 }}>
              <h2 style={{ margin: 0 }}>Reusable connector definitions</h2>
              {view.data && <span className="badge mono small">{view.data.length} defined</span>}
            </div>
            <p className="faint" style={{ margin: '4px 0 0' }}>
              Create definitions globally with typed properties and adapters. Tenants grant them and projects enable or configure them.
            </p>
          </div>
          <button type="button" className="btn primary" onClick={openCreate}>
            <Icon name="plus" size={15} />
            <span>Add connector</span>
          </button>
        </div>

        <div className="between" style={{ flexWrap: 'wrap', gap: 12, paddingTop: 6, borderTop: '1px solid var(--border)' }}>
          <div className="row" style={{ flex: '1 1 240px', maxWidth: 360 }}>
            <input
              type="search"
              className="input"
              placeholder="Search by name, key, or description..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              aria-label="Filter connectors"
            />
          </div>

          <div className="seg" role="radiogroup" aria-label="Connector category filter">
            {(['ALL', 'MEDIA', 'ADS', 'STORAGE', 'OTHER'] as const).map(cat => (
              <button
                key={cat}
                type="button"
                className={filter === cat ? 'active' : ''}
                onClick={() => setFilter(cat)}
              >
                {cat === 'ALL' ? 'All' : cat === 'MEDIA' ? 'Media' : cat === 'ADS' ? 'Advertising' : cat === 'STORAGE' ? 'Storage' : 'Other'} ({counts[cat]})
              </button>
            ))}
          </div>
        </div>
      </div>

      {view.error ? (
        <ErrorBox error={view.error} retry={view.reload} />
      ) : !view.data ? (
        <Loading rows={3} />
      ) : filtered.length === 0 ? (
        <Empty
          icon="link"
          title="No connectors found"
          text={search ? `No connectors match "${search}". Try a different search term or category.` : 'No connector definitions in this category.'}
          action={
            <div className="row" style={{ gap: 8 }}>
              {search && <button type="button" className="btn" onClick={() => setSearch('')}>Clear search</button>}
              <button type="button" className="btn primary" onClick={openCreate}><Icon name="plus" size={15} /> Add connector</button>
            </div>
          }
        />
      ) : (
        <div className="connector-grid">
          {filtered.map(c => (
            <div key={c.key} className="connector-card">
              <div className="between" style={{ alignItems: 'flex-start', gap: 12 }}>
                <div className="row" style={{ gap: 12, minWidth: 0 }}>
                  <ConnectorIcon connector={c} size={50} />
                  <div className="grow" style={{ minWidth: 0 }}>
                    <b style={{ fontSize: 16, display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {c.name}
                    </b>
                    <span className="badge mono small" style={{ marginTop: 3 }}>
                      {c.key}
                    </span>
                  </div>
                </div>
                <span className={`badge ${c.enabled ? 'ok' : ''}`}>
                  <span className="dot" />
                  {c.enabled ? 'Available' : 'Disabled'}
                </span>
              </div>

              <div className="row-wrap" style={{ gap: 6 }}>
                <CategoryBadge category={c.category} />
                <DriverChip driver={c.driver} />
              </div>

              <p className="faint" style={{ minHeight: 40, margin: 0, fontSize: 13, lineHeight: 1.45, flex: 1 }}>
                {c.description || 'No description provided.'}
              </p>

              <div className="between" style={{ borderTop: '1px solid var(--border)', paddingTop: 12, marginTop: 'auto' }}>
                <span className="faint small">
                  {c.enabled ? 'Available to tenants' : 'Disabled globally'}
                </span>
                <button type="button" className="btn sm" onClick={() => openEdit(c)}>
                  <Icon name="sliders" size={13} />
                  <span>Edit definition</span>
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Modal Form for Create & Edit */}
      {modalOpen && (
        <Modal
          title={exists ? `Edit connector: ${form.name || form.key}` : 'Add connector definition'}
          subtitle="Configure global connector properties. Tenants and projects inherit this connector definition."
          onClose={() => setModalOpen(false)}
          size="md"
        >
          <form onSubmit={handleSave} className="stack" style={{ gap: 16 }}>
            {/* Live card preview */}
            <div className="stack" style={{ gap: 6 }}>
              <span className="faint small" style={{ textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600 }}>
                Live card preview
              </span>
              <div className="connector-preview-box">
                <ConnectorIcon connector={form} size={48} />
                <div className="grow" style={{ minWidth: 0 }}>
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <b style={{ fontSize: 15 }}>{form.name || 'Untitled connector'}</b>
                    <span className="badge mono small">{form.key || 'key'}</span>
                    <span className={`badge ${form.enabled ? 'ok' : ''}`} style={{ marginLeft: 'auto' }}>
                      <span className="dot" />
                      {form.enabled ? 'Available' : 'Disabled'}
                    </span>
                  </div>
                  <div className="row-wrap" style={{ gap: 6, marginTop: 4 }}>
                    <CategoryBadge category={form.category} />
                    <DriverChip driver={form.driver} />
                    <span className="faint small" style={{ marginLeft: 6 }}>
                      {form.description ? (form.description.length > 50 ? `${form.description.slice(0, 50)}…` : form.description) : 'No description'}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <div className="grid g2">
              <label className="field">
                Stable key
                <input
                  required
                  pattern="[a-z][a-z0-9_\-]{1,49}"
                  disabled={exists}
                  placeholder="e.g. mux, r2, s3_archive"
                  value={form.key}
                  onChange={e => setForm({ ...form, key: e.target.value.toLowerCase() })}
                />
                <span className="hint">Lower case, digits, - or _. Unchangeable after create.</span>
              </label>

              <label className="field">
                Display name
                <input
                  required
                  maxLength={100}
                  placeholder="e.g. Cloudflare R2"
                  value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })}
                />
                <span className="hint">Public connector name displayed across FAST Studio.</span>
              </label>

              <label className="field">
                Category
                <select value={form.category} onChange={e => setForm({ ...form, category: e.target.value })}>
                  <option value="MEDIA">MEDIA (Video & Content)</option>
                  <option value="ADS">ADS (Advertising & Demand)</option>
                  <option value="STORAGE">STORAGE (Object storage & CDN)</option>
                  <option value="OTHER">OTHER (Utilities & Custom)</option>
                </select>
                <span className="hint">Marketplace category for grouping.</span>
              </label>

              <label className="field">
                Adapter / Driver
                <select disabled={exists} value={form.driver} onChange={e => setForm({ ...form, driver: e.target.value })}>
                  <option value="CUSTOM">CUSTOM (Generic configuration)</option>
                  <option value="MUX">MUX (Mux Video & Library sync)</option>
                  <option value="VAST">VAST (VAST Ad Waterfall)</option>
                  <option value="R2">R2 (Cloudflare R2 Storage)</option>
                </select>
                <span className="hint">{exists ? 'Driver cannot be modified once created.' : 'Underlying protocol operations driver.'}</span>
              </label>
            </div>

            <label className="field">
              Description
              <textarea
                maxLength={500}
                rows={3}
                placeholder="Describe provider credentials, features, or storage details..."
                value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })}
              />
            </label>

            <label className="check" style={{ cursor: 'pointer', padding: '4px 0' }}>
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={e => setForm({ ...form, enabled: e.target.checked })}
              />
              <span>Available globally (can be granted to customer tenants and enabled per project)</span>
            </label>

            <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 8, borderTop: '1px solid var(--border)', paddingTop: 14 }}>
              <button type="button" className="btn" onClick={() => setModalOpen(false)}>
                Cancel
              </button>
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Saving…' : exists ? 'Save connector' : 'Create connector'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

// ============================================================================
// 2. Properties (Table + Modal Form)
// ============================================================================

function Properties() {
  const view = useApi<Property[]>('/api/platform/property-definitions')
  const connectors = useApi<Connector[]>('/api/platform/connector-definitions')
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState(emptyProperty)
  const [options, setOptions] = useState('')
  const [search, setSearch] = useState('')
  const { busy, run } = useAction()

  const exists = Boolean(view.data?.some(p => p.key === form.key))

  const openCreate = () => {
    setForm(emptyProperty)
    setOptions('')
    setModalOpen(true)
  }

  const openEdit = (p: Property) => {
    setForm({ ...p, defaultValue: p.secret ? null : p.defaultValue })
    setOptions((p.options || []).join('\n'))
    setModalOpen(true)
  }

  const filtered = useMemo(() => {
    if (!view.data) return []
    const q = search.trim().toLowerCase()
    if (!q) return view.data
    return view.data.filter(p =>
      p.label.toLowerCase().includes(q) ||
      p.key.toLowerCase().includes(q) ||
      (p.connectorKey || '').toLowerCase().includes(q) ||
      p.valueType.toLowerCase().includes(q)
    )
  }, [view.data, search])

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    run(
      () => api('/api/platform/property-definitions', {
        method: 'PUT',
        body: {
          ...form,
          options: options.split('\n').map(o => o.trim()).filter(Boolean),
          expectedRevision: form.revision,
        },
      }),
      exists ? `Property "${form.label}" saved` : `Property "${form.label}" added`
    ).then(r => {
      if (r) {
        view.reload()
        setModalOpen(false)
        setForm(emptyProperty)
        setOptions('')
      }
    })
  }

  const handleClearDefault = () => {
    run(
      () => api(`/api/platform/property-definitions/${form.key}/default`, { method: 'DELETE' }),
      'Global default cleared'
    ).then(r => {
      if (r) {
        view.reload()
        setForm({ ...form, defaultValue: null, defaultConfigured: false, revision: form.revision + 1 })
      }
    })
  }

  return (
    <div className="stack" style={{ gap: 18 }}>
      <section className="card card-pad stack" style={{ gap: 14 }}>
        <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div className="row" style={{ gap: 10 }}>
              <h2 style={{ margin: 0 }}>Dynamic property catalogue</h2>
              {view.data && <span className="badge mono small">{view.data.length} defined</span>}
            </div>
            <p className="faint" style={{ margin: '4px 0 0' }}>
              New keys and defaults are stored in the database and rendered from their type. Existing media/programming controls retain their domain validation.
            </p>
          </div>
          <button type="button" className="btn primary" onClick={openCreate}>
            <Icon name="plus" size={15} />
            <span>Add property</span>
          </button>
        </div>

        <div className="row" style={{ maxWidth: 360, paddingTop: 4 }}>
          <input
            type="search"
            className="input"
            placeholder="Search properties by key, label, or connector..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            aria-label="Filter properties"
          />
        </div>

        {view.error ? (
          <ErrorBox error={view.error} retry={view.reload} />
        ) : !view.data ? (
          <Loading />
        ) : filtered.length === 0 ? (
          <Empty
            icon="sliders"
            title="No properties found"
            text={search ? `No property definitions match "${search}".` : 'No dynamic properties defined yet.'}
            action={search ? <button type="button" className="btn" onClick={() => setSearch('')}>Clear search</button> : undefined}
          />
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Property</th>
                <th>Type / scope</th>
                <th>Global default</th>
                <th>Status</th>
                <th style={{ textAlign: 'right' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(p => (
                <tr key={p.key}>
                  <td>
                    <b style={{ fontSize: 14 }}>{p.label}</b>
                    <div className="faint mono small">
                      {p.key}
                      {p.connectorKey && <span className="chip" style={{ marginLeft: 6 }}>{p.connectorKey}</span>}
                    </div>
                  </td>
                  <td>
                    <span className="badge">{p.secret ? 'Secret' : p.valueType}</span>
                    <div className="faint small" style={{ marginTop: 2 }}>
                      {p.projectOverride ? 'Project overrides allowed' : 'Tenant-wide'}
                    </div>
                  </td>
                  <td>
                    {p.secret ? (
                      p.defaultConfigured ? <span className="badge ok">Configured (hidden)</span> : <span className="faint">Not configured</span>
                    ) : p.defaultValue == null ? (
                      <span className="faint">Not set</span>
                    ) : (
                      <span className="mono small">{String(p.defaultValue)}</span>
                    )}
                  </td>
                  <td>
                    <span className={`badge ${p.enabled ? 'ok' : ''}`}>
                      <span className="dot" />
                      {p.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button type="button" className="btn sm" onClick={() => openEdit(p)}>
                      <Icon name="sliders" size={13} />
                      <span>Edit {p.label}</span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* Modal Form for Property */}
      {modalOpen && (
        <Modal
          title={exists ? `Edit property: ${form.label || form.key}` : 'Add property definition'}
          subtitle="Configure typed property schema and global default values."
          onClose={() => setModalOpen(false)}
          size="md"
        >
          <form onSubmit={handleSave} className="stack" style={{ gap: 16 }}>
            <div className="grid g2">
              <label className="field">
                Stable property key
                <input
                  required
                  disabled={exists}
                  pattern="[a-z][a-z0-9_.\-]{1,79}"
                  placeholder="e.g. streaming.bitrate_ceiling"
                  value={form.key}
                  onChange={e => setForm({ ...form, key: e.target.value })}
                />
                <span className="hint">Dot-separated lower-case identifier.</span>
              </label>

              <label className="field">
                Display label
                <input
                  required
                  maxLength={100}
                  placeholder="e.g. Bitrate Ceiling"
                  value={form.label}
                  onChange={e => setForm({ ...form, label: e.target.value })}
                />
                <span className="hint">Human-readable label for settings forms.</span>
              </label>

              <label className="field">
                Value type
                <select
                  disabled={exists}
                  value={form.valueType}
                  onChange={e => setForm({ ...form, valueType: e.target.value, defaultValue: null, secret: false })}
                >
                  {['STRING', 'NUMBER', 'BOOLEAN', 'SELECT'].map(t => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
                <span className="hint">Data type rendered in configuration editors.</span>
              </label>

              <label className="field">
                Connector binding
                <select
                  disabled={exists}
                  value={form.connectorKey ?? ''}
                  onChange={e => setForm({ ...form, connectorKey: e.target.value || null })}
                >
                  <option value="">General project property</option>
                  {connectors.data?.map(c => (
                    <option key={c.key} value={c.key}>{c.name} ({c.key})</option>
                  ))}
                </select>
                <span className="hint">Attach to a specific connector or keep general.</span>
              </label>
            </div>

            {form.valueType === 'SELECT' && (
              <label className="field">
                Allowed values (one per line)
                <textarea
                  required
                  rows={3}
                  value={options}
                  onChange={e => setOptions(e.target.value)}
                  placeholder="e.g.&#10;1080p&#10;720p&#10;480p"
                />
              </label>
            )}

            <div className="row-wrap" style={{ gap: 16 }}>
              <label className="check" style={{ cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  disabled={exists || form.valueType !== 'STRING'}
                  checked={form.secret}
                  onChange={e => setForm({ ...form, secret: e.target.checked, defaultValue: null })}
                />
                <span>Secret (encrypted & write-only)</span>
              </label>

              <label className="check" style={{ cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={form.projectOverride}
                  onChange={e => setForm({ ...form, projectOverride: e.target.checked })}
                />
                <span>Allow project overrides</span>
              </label>

              <label className="check" style={{ cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={form.enabled}
                  onChange={e => setForm({ ...form, enabled: e.target.checked })}
                />
                <span>Enabled</span>
              </label>
            </div>

            <PropertyInput
              definition={{ ...form, options: options.split('\n').filter(Boolean) }}
              label={form.secret ? 'New global default (blank keeps stored secret)' : 'Global default (optional)'}
              value={form.defaultValue}
              onChange={value => setForm({ ...form, defaultValue: value })}
            />

            <div className="row" style={{ justifyContent: 'space-between', gap: 10, marginTop: 8, borderTop: '1px solid var(--border)', paddingTop: 14 }}>
              <div>
                {exists && form.defaultConfigured && (
                  <button type="button" className="btn danger" disabled={busy} onClick={handleClearDefault}>
                    Clear global default
                  </button>
                )}
              </div>
              <div className="row" style={{ gap: 10 }}>
                <button type="button" className="btn" onClick={() => setModalOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="btn primary" disabled={busy}>
                  {busy ? 'Saving…' : exists ? 'Save property' : 'Create property'}
                </button>
              </div>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

// ============================================================================
// 3. Subscription Plans (Cards Grid + Modal Form)
// ============================================================================

function Plans() {
  const view = useApi<Plan[]>('/api/platform/plans')
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState(emptyPlan)
  const [features, setFeatures] = useState('')
  const [search, setSearch] = useState('')
  const [viewMode, setViewMode] = useState<'CARDS' | 'TABLE'>('CARDS')
  const { busy, run } = useAction()

  const exists = Boolean(view.data?.some(p => p.key === form.key))

  const openCreate = () => {
    setForm(emptyPlan)
    setFeatures('')
    setModalOpen(true)
  }

  const openEdit = (p: Plan) => {
    setForm(p)
    setFeatures((p.features || []).join('\n'))
    setModalOpen(true)
  }

  const filtered = useMemo(() => {
    if (!view.data) return []
    const q = search.trim().toLowerCase()
    if (!q) return view.data
    return view.data.filter(p =>
      p.name.toLowerCase().includes(q) ||
      p.key.toLowerCase().includes(q) ||
      p.features.some(f => f.toLowerCase().includes(q))
    )
  }, [view.data, search])

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    run(
      () => api('/api/platform/plans', {
        method: 'PUT',
        body: {
          ...form,
          features: features.split('\n').map(f => f.trim()).filter(Boolean),
        },
      }),
      exists ? `Plan "${form.name}" updated` : `Plan "${form.name}" created`
    ).then(r => {
      if (r) {
        view.reload()
        setModalOpen(false)
        setForm(emptyPlan)
        setFeatures('')
      }
    })
  }

  return (
    <div className="stack" style={{ gap: 18 }}>
      <section className="card card-pad stack" style={{ gap: 14 }}>
        <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div className="row" style={{ gap: 10 }}>
              <h2 style={{ margin: 0 }}>Subscription plans</h2>
              {view.data && <span className="badge mono small">{view.data.length} plans</span>}
            </div>
            <p className="faint" style={{ margin: '4px 0 0' }}>
              Project and channel allowances apply across the tenant. Prices are plan metadata; no payment is collected here.
            </p>
          </div>
          <button type="button" className="btn primary" onClick={openCreate}>
            <Icon name="plus" size={15} />
            <span>Add plan</span>
          </button>
        </div>

        <div className="between" style={{ flexWrap: 'wrap', gap: 12, paddingTop: 4, borderTop: '1px solid var(--border)' }}>
          <div className="row" style={{ maxWidth: 360 }}>
            <input
              type="search"
              className="input"
              placeholder="Search plans by name, key, or features..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              aria-label="Filter plans"
            />
          </div>

          <div className="seg" aria-label="View layout switcher">
            <button
              type="button"
              className={viewMode === 'CARDS' ? 'active' : ''}
              onClick={() => setViewMode('CARDS')}
              title="Cards view"
            >
              <Icon name="grid" size={13} />
            </button>
            <button
              type="button"
              className={viewMode === 'TABLE' ? 'active' : ''}
              onClick={() => setViewMode('TABLE')}
              title="Table view"
            >
              <Icon name="list" size={13} />
            </button>
          </div>
        </div>

        {view.error ? (
          <ErrorBox error={view.error} retry={view.reload} />
        ) : !view.data ? (
          <Loading />
        ) : filtered.length === 0 ? (
          <Empty
            icon="dollarSign"
            title="No subscription plans"
            text={search ? `No plans match "${search}".` : 'Create plans to define maximum projects, channels, and features for customer tenants.'}
            action={
              <div className="row" style={{ gap: 8 }}>
                {search && <button type="button" className="btn" onClick={() => setSearch('')}>Clear search</button>}
                <button type="button" className="btn primary" onClick={openCreate}><Icon name="plus" size={15} /> Add plan</button>
              </div>
            }
          />
        ) : viewMode === 'CARDS' ? (
          <div className="connector-grid">
            {filtered.map(p => (
              <div key={p.key} className="connector-card">
                <div className="between" style={{ alignItems: 'flex-start', gap: 12 }}>
                  <div className="row" style={{ gap: 12, minWidth: 0 }}>
                    <div
                      className="connector-icon-wrap"
                      style={{
                        width: 48,
                        height: 48,
                        background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.22), rgba(6, 182, 212, 0.08))',
                        borderColor: 'rgba(16, 185, 129, 0.35)',
                        color: '#10b981',
                      }}
                      title="Subscription Tier"
                    >
                      <Icon name="dollarSign" size={24} />
                    </div>
                    <div className="grow" style={{ minWidth: 0 }}>
                      <b style={{ fontSize: 16, display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {p.name}
                      </b>
                      <span className="badge mono small" style={{ marginTop: 3 }}>
                        {p.key}
                      </span>
                    </div>
                  </div>
                  <span className={`badge ${p.enabled ? 'ok' : ''}`}>
                    <span className="dot" />
                    {p.enabled ? 'Available' : 'Inactive'}
                  </span>
                </div>

                <div className="stack" style={{ gap: 6, margin: '4px 0' }}>
                  <div className="row" style={{ alignItems: 'baseline', gap: 4 }}>
                    <span style={{ fontSize: 22, fontWeight: 750, color: 'var(--text)' }}>
                      {p.currency} {p.monthlyPrice}
                    </span>
                    <span className="faint small">/ month</span>
                  </div>

                  <div className="row-wrap" style={{ gap: 8 }}>
                    <span className="chip"><Icon name="building" size={11} /> {p.maxProjects} {p.maxProjects === 1 ? 'project' : 'projects'} max</span>
                    <span className="chip"><Icon name="tv" size={11} /> {p.maxChannels} channels</span>
                  </div>
                </div>

                {p.features && p.features.length > 0 ? (
                  <div className="row-wrap" style={{ gap: 4 }}>
                    {p.features.map(f => (
                      <span key={f} className="badge mono small">{f}</span>
                    ))}
                  </div>
                ) : (
                  <span className="faint small">No extra features assigned</span>
                )}

                <div className="between" style={{ borderTop: '1px solid var(--border)', paddingTop: 10, marginTop: 'auto' }}>
                  <span className="faint small">
                    {p.enabled ? 'Assignable to tenants' : 'Not accepting assignments'}
                  </span>
                  <button type="button" className="btn sm" onClick={() => openEdit(p)}>
                    <Icon name="sliders" size={13} />
                    <span>Edit plan</span>
                  </button>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Plan</th>
                <th>Allowances</th>
                <th>Monthly price</th>
                <th>Status</th>
                <th style={{ textAlign: 'right' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(p => (
                <tr key={p.key}>
                  <td>
                    <b style={{ fontSize: 14 }}>{p.name}</b>
                    <div className="faint mono small">{p.key}</div>
                  </td>
                  <td>
                    <span>{p.maxProjects} projects / {p.maxChannels} channels</span>
                    {p.features && p.features.length > 0 && (
                      <div className="row-wrap" style={{ gap: 4, marginTop: 4 }}>
                        {p.features.map(f => (
                          <span key={f} className="badge mono small">{f}</span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td>
                    <b style={{ fontSize: 14 }}>{p.currency} {p.monthlyPrice}</b>
                    <span className="faint small"> / month</span>
                  </td>
                  <td>
                    <span className={`badge ${p.enabled ? 'ok' : ''}`}>
                      <span className="dot" />
                      {p.enabled ? 'Available' : 'Unavailable'}
                    </span>
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button type="button" className="btn sm" onClick={() => openEdit(p)}>
                      <Icon name="sliders" size={13} />
                      <span>Edit {p.name}</span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* Modal Form for Plan */}
      {modalOpen && (
        <Modal
          title={exists ? `Edit plan: ${form.name || form.key}` : 'Add subscription plan'}
          subtitle="Configure tenant tier limits, monthly pricing metadata, and granted features."
          onClose={() => setModalOpen(false)}
          size="md"
        >
          <form onSubmit={handleSave} className="stack" style={{ gap: 16 }}>
            <div className="grid g2">
              <label className="field">
                Plan key
                <input
                  required
                  disabled={exists}
                  pattern="[a-z][a-z0-9_\-]{1,49}"
                  placeholder="e.g. enterprise, pro, starter"
                  value={form.key}
                  onChange={e => setForm({ ...form, key: e.target.value.toLowerCase() })}
                />
                <span className="hint">Lower-case identifier used in database and assignments.</span>
              </label>

              <label className="field">
                Display name
                <input
                  required
                  maxLength={100}
                  placeholder="e.g. Enterprise Tier"
                  value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })}
                />
                <span className="hint">Plan name shown in tenant selection.</span>
              </label>

              <label className="field">
                Maximum projects
                <input
                  required
                  type="number"
                  min={1}
                  max={10000}
                  value={form.maxProjects}
                  onChange={e => setForm({ ...form, maxProjects: Number(e.target.value) })}
                />
                <span className="hint">Project creation ceiling per customer tenant.</span>
              </label>

              <label className="field">
                Maximum channels across projects
                <input
                  required
                  type="number"
                  min={0}
                  max={100000}
                  value={form.maxChannels}
                  onChange={e => setForm({ ...form, maxChannels: Number(e.target.value) })}
                />
                <span className="hint">Broadcast channel ceiling.</span>
              </label>

              <label className="field">
                Monthly price
                <input
                  required
                  type="number"
                  step="0.01"
                  min={0}
                  value={form.monthlyPrice}
                  onChange={e => setForm({ ...form, monthlyPrice: Number(e.target.value) })}
                />
              </label>

              <label className="field">
                Currency
                <input
                  required
                  pattern="[A-Z]{3}"
                  placeholder="USD"
                  value={form.currency}
                  onChange={e => setForm({ ...form, currency: e.target.value.toUpperCase() })}
                />
                <span className="hint">3-letter currency code (e.g. USD, EUR).</span>
              </label>
            </div>

            <label className="field">
              Feature keys (one per line)
              <textarea
                rows={3}
                value={features}
                onChange={e => setFeatures(e.target.value)}
                placeholder="e.g.&#10;LIBRARY_SYNC&#10;AD_WATERFALL_VIEW&#10;AD_OWN_TAGS"
              />
              <span className="hint">Standard features include LIBRARY_SYNC, AD_WATERFALL_VIEW, and AD_OWN_TAGS.</span>
            </label>

            <label className="check" style={{ cursor: 'pointer', padding: '4px 0' }}>
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={e => setForm({ ...form, enabled: e.target.checked })}
              />
              <span>Available for assignment to new tenants</span>
            </label>

            <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 8, borderTop: '1px solid var(--border)', paddingTop: 14 }}>
              <button type="button" className="btn" onClick={() => setModalOpen(false)}>
                Cancel
              </button>
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Saving…' : exists ? 'Save plan' : 'Create plan'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}

// ============================================================================
// 4. Configurable Organization Roles (Cards Grid + Modal Form)
// ============================================================================

function Roles() {
  const view = useApi<{ roles: AccessRole[]; permissions: string[]; ceilings: Record<string, string[]> }>('/api/platform/access-roles')
  const catalogue = useApi<Catalogue>('/api/platform/catalogue')
  const initial = { key: '', name: '', kind: 'CUSTOMER', baseRole: 'ADMIN', permissions: ['VIEW'], enabled: true, expectedRevision: 0 }
  const [form, setForm] = useState(initial)
  const [modalOpen, setModalOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [viewMode, setViewMode] = useState<'CARDS' | 'TABLE'>('CARDS')
  const { busy, run } = useAction()

  const exists = Boolean(view.data?.roles.some(r => r.key === form.key))
  const bases = form.kind === 'PARTNER'
    ? ['PARTNER_ADMIN', 'PARTNER_VIEWER']
    : ['ADMIN', 'CONTENT_MANAGER', 'CHANNEL_OPERATOR', 'AD_OPS', 'ANALYST']

  const openCreate = () => {
    setForm(initial)
    setModalOpen(true)
  }

  const openEdit = (r: AccessRole) => {
    setForm({
      key: r.key,
      name: r.name,
      kind: r.kind,
      baseRole: r.baseRole,
      permissions: r.permissions ? r.permissions.split(',').filter(Boolean) : ['VIEW'],
      enabled: r.enabled,
      expectedRevision: r.revision,
    })
    setModalOpen(true)
  }

  const filtered = useMemo(() => {
    if (!view.data?.roles) return []
    const q = search.trim().toLowerCase()
    if (!q) return view.data.roles
    return view.data.roles.filter(r =>
      r.name.toLowerCase().includes(q) ||
      r.key.toLowerCase().includes(q) ||
      r.kind.toLowerCase().includes(q) ||
      r.baseRole.toLowerCase().includes(q)
    )
  }, [view.data?.roles, search])

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    run(
      () => api('/api/platform/access-roles', { method: 'PUT', body: form }),
      exists ? `Role "${form.name}" updated` : `Role "${form.name}" defined`
    ).then(r => {
      if (r) {
        view.reload()
        setModalOpen(false)
        setForm(initial)
      }
    })
  }

  return (
    <div className="stack" style={{ gap: 18 }}>
      <section className="card card-pad stack" style={{ gap: 14 }}>
        <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div className="row" style={{ gap: 10 }}>
              <h2 style={{ margin: 0 }}>Configurable organization roles</h2>
              {view.data && <span className="badge mono small">{view.data.roles.length} roles</span>}
            </div>
            <p className="faint" style={{ margin: '4px 0 0' }}>
              Customer projects and partner organizations can assign different roles to members within a supported role ceiling.
            </p>
          </div>
          <button type="button" className="btn primary" onClick={openCreate}>
            <Icon name="plus" size={15} />
            <span>Add role</span>
          </button>
        </div>

        <div className="between" style={{ flexWrap: 'wrap', gap: 12, paddingTop: 4, borderTop: '1px solid var(--border)' }}>
          <div className="row" style={{ maxWidth: 360 }}>
            <input
              type="search"
              className="input"
              placeholder="Search roles by name, key, or ceiling..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              aria-label="Filter roles"
            />
          </div>

          <div className="seg" aria-label="View layout switcher">
            <button
              type="button"
              className={viewMode === 'CARDS' ? 'active' : ''}
              onClick={() => setViewMode('CARDS')}
              title="Cards view"
            >
              <Icon name="grid" size={13} />
            </button>
            <button
              type="button"
              className={viewMode === 'TABLE' ? 'active' : ''}
              onClick={() => setViewMode('TABLE')}
              title="Table view"
            >
              <Icon name="list" size={13} />
            </button>
          </div>
        </div>

        {view.error ? (
          <ErrorBox error={view.error} retry={view.reload} />
        ) : !view.data ? (
          <Loading />
        ) : filtered.length === 0 ? (
          <Empty
            icon="users"
            title="No organization roles"
            text={search ? `No roles match "${search}".` : 'Define reusable roles with specific permission bounds for customer and partner organizations.'}
            action={
              <div className="row" style={{ gap: 8 }}>
                {search && <button type="button" className="btn" onClick={() => setSearch('')}>Clear search</button>}
                <button type="button" className="btn primary" onClick={openCreate}><Icon name="plus" size={15} /> Add role</button>
              </div>
            }
          />
        ) : viewMode === 'CARDS' ? (
          <div className="connector-grid">
            {filtered.map(r => (
              <div key={r.key} className="connector-card">
                <div className="between" style={{ alignItems: 'flex-start', gap: 12 }}>
                  <div className="row" style={{ gap: 12, minWidth: 0 }}>
                    <div
                      className="connector-icon-wrap"
                      style={{
                        width: 48,
                        height: 48,
                        background: 'linear-gradient(135deg, rgba(99, 102, 241, 0.22), rgba(139, 92, 246, 0.08))',
                        borderColor: 'rgba(99, 102, 241, 0.35)',
                        color: '#818cf8',
                      }}
                      title="Access Role"
                    >
                      <Icon name="shield" size={24} />
                    </div>
                    <div className="grow" style={{ minWidth: 0 }}>
                      <b style={{ fontSize: 16, display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {r.name}
                      </b>
                      <span className="badge mono small" style={{ marginTop: 3 }}>
                        {r.key}
                      </span>
                    </div>
                  </div>
                  <span className={`badge ${r.enabled ? 'ok' : ''}`}>
                    <span className="dot" />
                    {r.enabled ? 'Enabled' : 'Disabled'}
                  </span>
                </div>

                <div className="row-wrap" style={{ gap: 6 }}>
                  <span className="badge">{r.kind}</span>
                  <span className="chip"><Icon name="key" size={11} /> Ceiling: {r.baseRole}</span>
                </div>

                <div className="stack" style={{ gap: 4, flex: 1, minHeight: 38 }}>
                  <span className="faint small" style={{ fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                    Granted Permissions
                  </span>
                  <div className="row-wrap" style={{ gap: 4 }}>
                    {r.permissions ? (
                      r.permissions.split(',').filter(Boolean).map(p => (
                        <span key={p} className="badge mono small">{p}</span>
                      ))
                    ) : (
                      <span className="faint small">None assigned</span>
                    )}
                  </div>
                </div>

                <div className="between" style={{ borderTop: '1px solid var(--border)', paddingTop: 10, marginTop: 'auto' }}>
                  <span className="faint small">
                    {r.enabled ? 'Active assignment' : 'Inactive role'}
                  </span>
                  <button type="button" className="btn sm" onClick={() => openEdit(r)}>
                    <Icon name="sliders" size={13} />
                    <span>Edit role</span>
                  </button>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Role</th>
                <th>Organization type</th>
                <th>Ceiling</th>
                <th>Status</th>
                <th style={{ textAlign: 'right' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(r => (
                <tr key={r.key}>
                  <td>
                    <b style={{ fontSize: 14 }}>{r.name}</b>
                    <div className="faint mono small">{r.key}</div>
                  </td>
                  <td>
                    <span className="badge">{r.kind}</span>
                  </td>
                  <td>
                    <span className="chip">{r.baseRole}</span>
                  </td>
                  <td>
                    <span className={`badge ${r.enabled ? 'ok' : ''}`}>
                      <span className="dot" />
                      {r.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td style={{ textAlign: 'right' }}>
                    <button type="button" className="btn sm" onClick={() => openEdit(r)}>
                      <Icon name="sliders" size={13} />
                      <span>Edit {r.name}</span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* Modal Form for Role */}
      {modalOpen && (
        <Modal
          title={exists ? `Edit role: ${form.name || form.key}` : 'Define organization role'}
          subtitle="Configure membership permissions and maximum privilege ceiling."
          onClose={() => setModalOpen(false)}
          size="md"
        >
          <form onSubmit={handleSave} className="stack" style={{ gap: 16 }}>
            <div className="grid g2">
              <label className="field">
                Stable role key
                <input
                  required
                  pattern="[a-z][a-z0-9_\-]{1,49}"
                  disabled={exists}
                  placeholder="e.g. content_lead, traffic_mgr"
                  value={form.key}
                  onChange={e => setForm({ ...form, key: e.target.value.toLowerCase() })}
                />
                <span className="hint">Lower-case identifier. Unchangeable once created.</span>
              </label>

              <label className="field">
                Display name
                <input
                  required
                  maxLength={100}
                  placeholder="e.g. Content Lead"
                  value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })}
                />
                <span className="hint">Label shown in team member role selectors.</span>
              </label>

              <label className="field">
                Organization type
                <select
                  disabled={exists}
                  value={form.kind}
                  onChange={e => setForm({
                    ...form,
                    kind: e.target.value,
                    baseRole: e.target.value === 'PARTNER' ? 'PARTNER_ADMIN' : 'ADMIN',
                    permissions: ['VIEW'],
                  })}
                >
                  <option value="CUSTOMER">Customer project</option>
                  <option value="PARTNER">Partner organization</option>
                </select>
                <span className="hint">Target organization model.</span>
              </label>

              <label className="field">
                Maximum privilege ceiling
                <select
                  disabled={exists}
                  value={form.baseRole}
                  onChange={e => setForm({ ...form, baseRole: e.target.value, permissions: ['VIEW'] })}
                >
                  {bases.map(b => (
                    <option key={b} value={b}>{b}</option>
                  ))}
                </select>
                <span className="hint">Maximum authorization boundary for this role.</span>
              </label>
            </div>

            <fieldset style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '12px 16px' }}>
              <legend style={{ padding: '0 6px', fontWeight: 600, fontSize: 13, color: 'var(--text-2)' }}>
                Permissions by module, feature group and feature
              </legend>
              {catalogue.data ? (
                <PermissionPicker
                  c={catalogue.data}
                  value={form.permissions}
                  onChange={permissions => setForm({ ...form, permissions })}
                  allowed={view.data?.ceilings[form.baseRole] ?? []}
                />
              ) : catalogue.error ? <ErrorBox error={catalogue.error} retry={catalogue.reload} /> : <Loading />}
            </fieldset>

            <label className="check" style={{ cursor: 'pointer', padding: '4px 0' }}>
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={e => setForm({ ...form, enabled: e.target.checked })}
              />
              <span>Enabled for assignment to team members</span>
            </label>

            <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 8, borderTop: '1px solid var(--border)', paddingTop: 14 }}>
              <button type="button" className="btn" onClick={() => setModalOpen(false)}>
                Cancel
              </button>
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Saving…' : exists ? 'Save role' : 'Create role'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
