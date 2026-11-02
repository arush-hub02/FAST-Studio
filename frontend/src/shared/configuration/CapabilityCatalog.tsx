import { useMemo, useState } from 'react'
import { api } from '../api'
import { Empty, ErrorBox, Icon, Loading, Modal, useAction, useApi } from '../ui'

// The features and permissions catalogue (capability). The platform keeps the global entries; a project keeps its own.
// Keys are the identifiers screens check with org.has(feature) and org.can(permission). Built-in entries are enforced
// by the server; added ones only show or hide things in the frontend (and in any endpoint that checks the key).
export type Capability = {
  key: string
  kind: 'FEATURE' | 'PERMISSION'
  name: string
  description: string
  category: string
  orgId: string | null
  builtIn: boolean
  enabled: boolean
}

type ProjectView = {
  catalogue: Capability[]
  features: string[]
  permissions: string[]
}

const BLANK: {
  key: string
  kind: Capability['kind']
  name: string
  description: string
  category: string
  enabled: boolean
} = {
  key: '',
  kind: 'FEATURE',
  name: '',
  description: '',
  category: '',
  enabled: true,
}

// ============================================================================
// Capability Visual Icons & Badges
// ============================================================================

export function CapabilityIcon({ capability, size = 48 }: { capability?: Partial<Capability> | null; size?: number }) {
  const cat = (capability?.category || '').toLowerCase()
  const kind = capability?.kind || 'FEATURE'
  const key = (capability?.key || '').toLowerCase()

  if (cat.includes('media') || key.includes('asset') || key.includes('video') || key.includes('library')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(168, 85, 247, 0.22), rgba(236, 72, 153, 0.08))',
          borderColor: 'rgba(168, 85, 247, 0.35)',
          color: '#c084fc',
        }}
        title="Media & Content"
      >
        <Icon name="film" size={size * 0.48} />
      </div>
    )
  }

  if (cat.includes('ad') || key.includes('ad') || key.includes('waterfall') || key.includes('monetiz')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.2), rgba(6, 182, 212, 0.08))',
          borderColor: 'rgba(16, 185, 129, 0.35)',
          color: '#10b981',
        }}
        title="Advertising & Monetization"
      >
        <Icon name="target" size={size * 0.48} />
      </div>
    )
  }

  if (cat.includes('channel') || cat.includes('schedule') || key.includes('channel') || key.includes('schedule')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(245, 158, 11, 0.2), rgba(239, 68, 68, 0.08))',
          borderColor: 'rgba(245, 158, 11, 0.35)',
          color: '#f59e0b',
        }}
        title="Channels & Scheduling"
      >
        <Icon name="tv" size={size * 0.48} />
      </div>
    )
  }

  if (cat.includes('distribut') || key.includes('distribut') || key.includes('partner')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(6, 182, 212, 0.2), rgba(59, 130, 246, 0.08))',
          borderColor: 'rgba(6, 182, 212, 0.35)',
          color: '#06b6d4',
        }}
        title="Distribution & Partners"
      >
        <Icon name="send" size={size * 0.48} />
      </div>
    )
  }

  if (cat.includes('monitor') || cat.includes('alert') || key.includes('alert')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(244, 63, 94, 0.2), rgba(245, 158, 11, 0.08))',
          borderColor: 'rgba(244, 63, 94, 0.35)',
          color: '#f43f5e',
        }}
        title="Monitoring & Alerts"
      >
        <Icon name="activity" size={size * 0.48} />
      </div>
    )
  }

  if (cat.includes('team') || key.includes('team') || key.includes('manage') || key.includes('role')) {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(99, 102, 241, 0.2), rgba(139, 92, 246, 0.08))',
          borderColor: 'rgba(99, 102, 241, 0.35)',
          color: '#818cf8',
        }}
        title="Team & Administration"
      >
        <Icon name="users" size={size * 0.48} />
      </div>
    )
  }

  if (kind === 'PERMISSION') {
    return (
      <div
        className="connector-icon-wrap"
        style={{
          width: size,
          height: size,
          background: 'linear-gradient(135deg, rgba(139, 92, 246, 0.2), rgba(99, 102, 241, 0.08))',
          borderColor: 'rgba(139, 92, 246, 0.35)',
          color: '#a78bfa',
        }}
        title="Permission"
      >
        <Icon name="key" size={size * 0.48} />
      </div>
    )
  }

  // Feature default
  return (
    <div
      className="connector-icon-wrap"
      style={{
        width: size,
        height: size,
        background: 'linear-gradient(135deg, rgba(14, 165, 233, 0.2), rgba(16, 185, 129, 0.08))',
        borderColor: 'rgba(14, 165, 233, 0.35)',
        color: '#38bdf8',
      }}
      title="Feature"
    >
      <Icon name="sparkle" size={size * 0.48} />
    </div>
  )
}

function KindBadge({ kind }: { kind: 'FEATURE' | 'PERMISSION' }) {
  if (kind === 'FEATURE') {
    return (
      <span className="badge" style={{ color: '#06b6d4', borderColor: 'rgba(6, 182, 212, 0.3)', background: 'rgba(6, 182, 212, 0.1)' }}>
        <Icon name="sparkle" size={12} /> Feature
      </span>
    )
  }
  return (
    <span className="badge" style={{ color: '#c084fc', borderColor: 'rgba(192, 132, 252, 0.3)', background: 'rgba(192, 132, 252, 0.1)' }}>
      <Icon name="key" size={12} /> Permission
    </span>
  )
}

// ============================================================================
// Public Exports: Global & Project Capabilities
// ============================================================================

/** Platform administration: the global catalogue. */
export function GlobalCapabilities() {
  const view = useApi<Capability[]>('/api/platform/capabilities')
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  return (
    <CatalogEditor
      base="/api/platform/capabilities"
      own={view.data}
      reload={view.reload}
      intro="Global features and permissions every project can use. Built-in entries are enforced by the server and can be renamed, not removed. Added entries are identifiers for screens: switch a feature on per project on the Connectors page; give a permission to people through organization roles (owners and administrators have all of them)."
    />
  )
}

/** A project: the keys that apply to you, the global catalogue (read-only) and the project's own entries. */
export function ProjectCapabilities({ base, editable }: { base: string; editable: boolean }) {
  const view = useApi<ProjectView>(base)
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const v = view.data
  return (
    <div className="stack" style={{ gap: 18 }}>
      <section className="card card-pad stack" aria-label="Features and permissions that apply to you">
        <h2>Features and permissions that apply to you</h2>
        <p className="faint">What this project has switched on and what your role allows. Screens show or hide things by these identifiers.</p>
        <div className="stack" style={{ gap: 8 }}>
          <div className="row-wrap" style={{ gap: 8, alignItems: 'center' }}>
            <b style={{ minWidth: 100 }}>Features:</b>
            {v.features.length ? (
              v.features.map(f => <span key={f} className="badge ok mono small">{f}</span>)
            ) : (
              <span className="faint">None active</span>
            )}
          </div>
          <div className="row-wrap" style={{ gap: 8, alignItems: 'center' }}>
            <b style={{ minWidth: 100 }}>Permissions:</b>
            {v.permissions.map(p => (
              <span key={p} className="badge mono small">{p}</span>
            ))}
          </div>
        </div>
      </section>

      <CatalogEditor
        base={base}
        own={v.catalogue.filter(c => c.orgId)}
        global={v.catalogue.filter(c => !c.orgId)}
        reload={view.reload}
        readOnly={!editable}
        intro="Add features and permissions for this project only. A project feature is on while it's enabled; a project permission goes to owners and administrators, and to custom roles that list it. Global entries come from the platform."
      />
    </div>
  )
}

// ============================================================================
// Core Catalog Editor (Card Grid + Search + Filter + Modal Dialog)
// ============================================================================

function CatalogEditor({
  base,
  own,
  global,
  reload,
  intro,
  readOnly,
}: {
  base: string
  own: Capability[]
  global?: Capability[]
  reload: () => void
  intro: string
  readOnly?: boolean
}) {
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState(BLANK)
  const [editingKey, setEditingKey] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<'ALL' | 'FEATURE' | 'PERMISSION' | 'BUILTIN' | 'CUSTOM'>('ALL')
  const [viewMode, setViewMode] = useState<'CARDS' | 'TABLE'>('CARDS')
  const { busy, run } = useAction()

  const openCreate = () => {
    setForm(BLANK)
    setEditingKey(null)
    setModalOpen(true)
  }

  const openEdit = (c: Capability) => {
    setEditingKey(c.key)
    setForm({
      key: c.key,
      kind: c.kind,
      name: c.name,
      description: c.description || '',
      category: c.category || '',
      enabled: c.enabled,
    })
    setModalOpen(true)
  }

  const save = () =>
    editingKey
      ? run(
          () =>
            api(`${base}/${editingKey}`, {
              method: 'PUT',
              body: { name: form.name, description: form.description, category: form.category, enabled: form.enabled },
            }),
          `Capability "${form.name}" updated`
        )
      : run(
          () => api(base, { method: 'POST', body: form }),
          `${form.kind === 'FEATURE' ? 'Feature' : 'Permission'} "${form.name}" added`
        )

  const remove = (c: Capability) =>
    run(() => api(`${base}/${c.key}`, { method: 'DELETE' }), `${c.key} removed`).then(() => reload())

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    save().then(r => {
      if (r !== undefined) {
        reload()
        setModalOpen(false)
        setForm(BLANK)
        setEditingKey(null)
      }
    })
  }

  const filtered = useMemo(() => {
    return own.filter(c => {
      if (filter === 'FEATURE' && c.kind !== 'FEATURE') return false
      if (filter === 'PERMISSION' && c.kind !== 'PERMISSION') return false
      if (filter === 'BUILTIN' && !c.builtIn) return false
      if (filter === 'CUSTOM' && c.builtIn) return false

      const q = search.trim().toLowerCase()
      if (!q) return true
      return (
        c.name.toLowerCase().includes(q) ||
        c.key.toLowerCase().includes(q) ||
        (c.category || '').toLowerCase().includes(q) ||
        (c.description || '').toLowerCase().includes(q)
      )
    })
  }, [own, filter, search])

  const counts = useMemo(() => {
    return {
      ALL: own.length,
      FEATURE: own.filter(c => c.kind === 'FEATURE').length,
      PERMISSION: own.filter(c => c.kind === 'PERMISSION').length,
      BUILTIN: own.filter(c => c.builtIn).length,
      CUSTOM: own.filter(c => !c.builtIn).length,
    }
  }, [own])

  const builtIn = editingKey ? own.find(c => c.key === editingKey)?.builtIn : false

  return (
    <div className="stack" style={{ gap: 18 }}>
      <section className="card card-pad stack" style={{ gap: 14 }}>
        <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div className="row" style={{ gap: 10 }}>
              <h2 style={{ margin: 0 }}>
                {global ? "This project's features & permissions" : 'Features & permissions catalogue'}
              </h2>
              <span className="badge mono small">{own.length} defined</span>
            </div>
            <p className="faint" style={{ margin: '4px 0 0' }}>{intro}</p>
          </div>
          {!readOnly && (
            <button type="button" className="btn primary" onClick={openCreate}>
              <Icon name="plus" size={15} />
              <span>Add feature or permission</span>
            </button>
          )}
        </div>

        {/* Search, Filter Pills & View Mode */}
        <div className="between" style={{ flexWrap: 'wrap', gap: 12, paddingTop: 6, borderTop: '1px solid var(--border)' }}>
          <div className="row" style={{ flex: '1 1 240px', maxWidth: 360 }}>
            <input
              type="search"
              className="input"
              placeholder="Search by name, identifier, or category..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              aria-label="Filter capabilities"
            />
          </div>

          <div className="row-wrap" style={{ gap: 8 }}>
            <div className="seg" role="radiogroup" aria-label="Capability kind filter">
              {(['ALL', 'FEATURE', 'PERMISSION', 'BUILTIN', 'CUSTOM'] as const).map(k => (
                <button
                  key={k}
                  type="button"
                  className={filter === k ? 'active' : ''}
                  onClick={() => setFilter(k)}
                >
                  {k === 'ALL' ? 'All' : k === 'FEATURE' ? 'Features' : k === 'PERMISSION' ? 'Permissions' : k === 'BUILTIN' ? 'Built-in' : 'Custom'} ({counts[k]})
                </button>
              ))}
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
        </div>

        {/* Content: Cards or Table */}
        {filtered.length === 0 ? (
          <Empty
            icon="shield"
            title="No matching capabilities"
            text={search ? `No capabilities match "${search}". Try adjusting your query or filter.` : 'No capabilities defined in this category.'}
            action={
              <div className="row" style={{ gap: 8 }}>
                {search && <button type="button" className="btn" onClick={() => setSearch('')}>Clear search</button>}
                {!readOnly && <button type="button" className="btn primary" onClick={openCreate}><Icon name="plus" size={15} /> Add capability</button>}
              </div>
            }
          />
        ) : viewMode === 'CARDS' ? (
          <div className="connector-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))' }}>
            {filtered.map(c => (
              <div key={c.key} className="connector-card">
                <div className="between" style={{ alignItems: 'flex-start', gap: 12 }}>
                  <div className="row" style={{ gap: 12, minWidth: 0 }}>
                    <CapabilityIcon capability={c} size={48} />
                    <div className="grow" style={{ minWidth: 0 }}>
                      <b style={{ fontSize: 15, display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {c.name}
                      </b>
                      <span className="badge mono small" style={{ marginTop: 3 }}>
                        {c.key}
                      </span>
                    </div>
                  </div>
                  {c.builtIn ? (
                    <span className="badge" title="Built-in server enforcement">
                      <Icon name="shieldCheck" size={12} /> Built in
                    </span>
                  ) : (
                    <span className={`badge ${c.enabled ? 'ok' : ''}`}>
                      <span className="dot" />
                      {c.enabled ? 'On' : 'Off'}
                    </span>
                  )}
                </div>

                <div className="row-wrap" style={{ gap: 6 }}>
                  <KindBadge kind={c.kind} />
                  <span className="chip">{c.category || 'General'}</span>
                </div>

                <p className="faint" style={{ minHeight: 38, margin: 0, fontSize: 13, lineHeight: 1.45, flex: 1 }}>
                  {c.description || 'No description provided.'}
                </p>

                <div className="between" style={{ borderTop: '1px solid var(--border)', paddingTop: 10, marginTop: 'auto' }}>
                  <span className="faint small">
                    {c.builtIn ? 'Platform enforced' : 'Custom key'}
                  </span>
                  {!readOnly && (
                    <div className="row" style={{ gap: 6 }}>
                      <button type="button" className="btn sm" onClick={() => openEdit(c)}>
                        <Icon name="sliders" size={13} />
                        <span>Edit</span>
                      </button>
                      {!c.builtIn && (
                        <button type="button" className="btn sm danger" disabled={busy} onClick={() => remove(c)}>
                          <Icon name="trash" size={13} />
                          <span>Remove</span>
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Identifier & Name</th>
                <th>Kind</th>
                <th>Category</th>
                <th>Status</th>
                {!readOnly && <th style={{ textAlign: 'right' }}>Action</th>}
              </tr>
            </thead>
            <tbody>
              {filtered.map(c => (
                <tr key={c.key}>
                  <td>
                    <div className="row" style={{ gap: 10 }}>
                      <CapabilityIcon capability={c} size={34} />
                      <div>
                        <b style={{ fontSize: 14 }}>{c.name}</b>
                        <div className="faint mono small">{c.key}</div>
                        {c.description && <div className="faint small" style={{ marginTop: 2 }}>{c.description}</div>}
                      </div>
                    </div>
                  </td>
                  <td><KindBadge kind={c.kind} /></td>
                  <td><span className="chip">{c.category || 'General'}</span></td>
                  <td>
                    {c.builtIn ? (
                      <span className="badge"><Icon name="shieldCheck" size={12} /> Built in</span>
                    ) : (
                      <span className={`badge ${c.enabled ? 'ok' : ''}`}>
                        <span className="dot" />
                        {c.enabled ? 'On' : 'Off'}
                      </span>
                    )}
                  </td>
                  {!readOnly && (
                    <td style={{ textAlign: 'right' }}>
                      <div className="row" style={{ justifyContent: 'flex-end', gap: 6 }}>
                        <button type="button" className="btn sm" onClick={() => openEdit(c)}>
                          <Icon name="sliders" size={13} />
                          <span>Edit</span>
                        </button>
                        {!c.builtIn && (
                          <button type="button" className="btn sm danger" disabled={busy} onClick={() => remove(c)}>
                            <Icon name="trash" size={13} />
                            <span>Remove</span>
                          </button>
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {global && global.length > 0 && (
          <details style={{ marginTop: 12, borderTop: '1px solid var(--border)', paddingTop: 10 }}>
            <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: 13, color: 'var(--text-2)' }}>
              Global platform catalogue ({global.length} inherited entries)
            </summary>
            <div className="connector-grid" style={{ marginTop: 12 }}>
              {global.map(c => (
                <div key={c.key} className="connector-card" style={{ opacity: 0.9 }}>
                  <div className="between" style={{ alignItems: 'flex-start', gap: 12 }}>
                    <div className="row" style={{ gap: 10, minWidth: 0 }}>
                      <CapabilityIcon capability={c} size={40} />
                      <div className="grow" style={{ minWidth: 0 }}>
                        <b style={{ fontSize: 14 }}>{c.name}</b>
                        <div className="badge mono small">{c.key}</div>
                      </div>
                    </div>
                    <span className="badge"><Icon name="shieldCheck" size={12} /> Global</span>
                  </div>
                  <div className="row-wrap" style={{ gap: 6 }}>
                    <KindBadge kind={c.kind} />
                    <span className="chip">{c.category || 'General'}</span>
                  </div>
                  <p className="faint small" style={{ margin: 0 }}>{c.description}</p>
                </div>
              ))}
            </div>
          </details>
        )}
      </section>

      {/* Modal Dialog for Create & Edit */}
      {modalOpen && (
        <Modal
          title={editingKey ? `Edit capability: ${form.name || form.key}` : 'Add a feature or permission'}
          subtitle={editingKey ? 'Modify display name, category, or status. Identifier cannot be changed.' : 'Define a new capability identifier for screen gating and role permissions.'}
          onClose={() => setModalOpen(false)}
          size="md"
        >
          <form onSubmit={handleSave} className="stack" style={{ gap: 16 }}>
            {/* Live Card Preview */}
            <div className="stack" style={{ gap: 6 }}>
              <span className="faint small" style={{ textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 600 }}>
                Live card preview
              </span>
              <div className="connector-preview-box">
                <CapabilityIcon capability={form} size={48} />
                <div className="grow" style={{ minWidth: 0 }}>
                  <div className="between" style={{ gap: 8 }}>
                    <div className="row" style={{ gap: 8, minWidth: 0 }}>
                      <b style={{ fontSize: 15 }}>{form.name || 'Untitled capability'}</b>
                      <span className="badge mono small">{form.key || 'KEY'}</span>
                    </div>
                    {builtIn ? (
                      <span className="badge"><Icon name="shieldCheck" size={12} /> Built in</span>
                    ) : (
                      <span className={`badge ${form.enabled ? 'ok' : ''}`}>
                        <span className="dot" />
                        {form.enabled ? 'On' : 'Off'}
                      </span>
                    )}
                  </div>
                  <div className="row-wrap" style={{ gap: 6, marginTop: 4 }}>
                    <KindBadge kind={form.kind} />
                    <span className="chip">{form.category || 'General'}</span>
                    <span className="faint small" style={{ marginLeft: 6 }}>
                      {form.description ? (form.description.length > 50 ? `${form.description.slice(0, 50)}…` : form.description) : 'No description'}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            <div className="grid g2">
              <label className="field">
                Identifier
                <input
                  required
                  disabled={Boolean(editingKey)}
                  pattern="[A-Z][A-Z0-9_]{1,63}"
                  placeholder="e.g. CLIPS_EXPORT"
                  value={form.key}
                  onChange={e => setForm({ ...form, key: e.target.value.toUpperCase() })}
                />
                <span className="hint">Capitals, digits, and underscores. Cannot change later.</span>
              </label>

              <label className="field">
                Kind
                <select
                  disabled={Boolean(editingKey)}
                  value={form.kind}
                  onChange={e => setForm({ ...form, kind: e.target.value as Capability['kind'] })}
                >
                  <option value="FEATURE">Feature (switched on per project)</option>
                  <option value="PERMISSION">Permission (granted through roles)</option>
                </select>
                <span className="hint">Feature gates UI elements; permission gates actions.</span>
              </label>

              <label className="field">
                Display name
                <input
                  required
                  maxLength={80}
                  placeholder="e.g. Video Clips Export"
                  value={form.name}
                  onChange={e => setForm({ ...form, name: e.target.value })}
                />
                <span className="hint">Human label shown in selectors and project settings.</span>
              </label>

              <label className="field">
                Category
                <input
                  maxLength={40}
                  placeholder="e.g. Media, Advertising, Channels, Team"
                  value={form.category}
                  onChange={e => setForm({ ...form, category: e.target.value })}
                />
                <span className="hint">Grouping category for cards and lists.</span>
              </label>
            </div>

            <label className="field">
              Description
              <textarea
                maxLength={300}
                rows={2}
                placeholder="Describe what this capability enables..."
                value={form.description}
                onChange={e => setForm({ ...form, description: e.target.value })}
              />
            </label>

            <label className="check" style={{ cursor: builtIn ? 'not-allowed' : 'pointer', padding: '4px 0' }}>
              <input
                type="checkbox"
                disabled={builtIn}
                checked={form.enabled}
                onChange={e => setForm({ ...form, enabled: e.target.checked })}
              />
              <span>Enabled {builtIn ? '(built-in capabilities are server-enforced and always enabled)' : ''}</span>
            </label>

            <div className="row" style={{ justifyContent: 'flex-end', gap: 10, marginTop: 8, borderTop: '1px solid var(--border)', paddingTop: 14 }}>
              <button type="button" className="btn" onClick={() => setModalOpen(false)}>
                Cancel
              </button>
              <button type="submit" className="btn primary" disabled={busy}>
                {busy ? 'Saving…' : editingKey ? 'Save capability' : 'Add capability'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  )
}
