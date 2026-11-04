import { useState } from 'react'
import { api } from '../api'
import { Confirm, Empty, ErrorBox, Icon, Loading, Modal, useAction, useApi } from '../ui'

// Connector marketplace connections (docs/PLATFORM_ADMIN_PLAN.md, P3b). One panel for three places, all with the same
// answer shape: a project's Settings (/api/orgs/{org}/connectors), a project on the tenant page
// (/api/platform/orgs/{id}/connector-instances) and platform-level connectors (/api/platform/connector-instances).
// The fields come from the server (connector_property rows); this file knows no connector by name.

export type ConnectorCategory = { key: string; name: string; description: string; position: number }
export type ConnectorField = {
  connectorKey: string; key: string; label: string; description: string; valueType: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'SELECT' | 'URL'
  options: string[]; defaultValue: string | null; position: number; required: boolean; encrypted: boolean; scope: 'EXTERNAL' | 'INTERNAL'
}
export type MarketConnector = {
  key: string; name: string; category: string; driver: string; enabled: boolean; description: string; longDescription: string
  logoUrl: string | null; darkLogoUrl: string | null; referenceLink: string | null; tier: 'FREE' | 'PRO'
  status: 'AVAILABLE' | 'UPCOMING' | 'UNPUBLISHED'; tenantUpdate: 'ALLOWED' | 'NOT_ALLOWED' | 'CONTACT_ACCOUNT_MANAGER'
  requiredPermission: string | null; level: 'PLATFORM' | 'PROJECT'; multiInstance: boolean; properties: ConnectorField[]
}
export type ConnectorInstance = {
  id: string; name: string; position: number; enabled: boolean; connected: boolean; missing: string[]
  values: Record<string, { value: string | null; configured: boolean }>; checkedAt: string | null; checkError: string | null
  editable: boolean
}
export type ConnectorState = { connector: MarketConnector; instances: ConnectorInstance[]; connected: boolean; canConnect: boolean; note: string | null }
export type Marketplace = { categories: ConnectorCategory[]; connectors: ConnectorState[] }

type Editing = { state: ConnectorState; instance: ConnectorInstance | null }

export function ConnectorPanel({ base, title = 'Connectors', intro }: { base: string; title?: string; intro?: string }) {
  const view = useApi<Marketplace>(base, [base])
  const [category, setCategory] = useState('ALL')
  const [editing, setEditing] = useState<Editing | null>(null)
  const [removing, setRemoving] = useState<{ state: ConnectorState; instance: ConnectorInstance } | null>(null)
  const { run } = useAction()

  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const used = view.data.categories.filter(c => view.data!.connectors.some(s => s.connector.category === c.key))
  const shown = view.data.connectors.filter(s => category === 'ALL' || s.connector.category === category)

  return (
    <section className="card card-pad stack" style={{ gap: 14 }} aria-label={title}>
      <div className="between" style={{ flexWrap: 'wrap', gap: 10 }}>
        <div>
          <h2 style={{ margin: 0 }}>{title}</h2>
          {intro && <p className="faint" style={{ margin: '4px 0 0' }}>{intro}</p>}
        </div>
        {used.length > 1 && (
          <div className="seg" role="radiogroup" aria-label="Connector category">
            {[{ key: 'ALL', name: 'All' }, ...used].map(c => (
              <button key={c.key} type="button" className={category === c.key ? 'active' : ''} onClick={() => setCategory(c.key)}>{c.name}</button>
            ))}
          </div>
        )}
      </div>

      {!shown.length ? <Empty icon="link" title="No connectors here" text="Nothing in this category is offered to this project yet." /> : (
        <div className="connector-grid">
          {shown.map(s => {
            const c = s.connector
            const add = s.canConnect && (c.multiInstance || !s.instances.length)
            return (
              <div key={c.key} className="connector-card" aria-label={c.name}>
                <div className="between" style={{ alignItems: 'flex-start', gap: 10 }}>
                  <div className="row" style={{ gap: 10, minWidth: 0 }}>
                    {c.logoUrl ? <img src={c.logoUrl} alt="" width={36} height={36} style={{ borderRadius: 8, objectFit: 'contain' }} /> : <Icon name="link" size={22} />}
                    <b style={{ fontSize: 15 }}>{c.name}</b>
                  </div>
                  <span className={'badge ' + (s.connected ? 'ok' : '')}><span className="dot" />{c.status === 'UPCOMING' ? 'Coming soon' : s.connected ? 'Connected' : 'Not connected'}</span>
                </div>
                <p className="faint" style={{ margin: 0, fontSize: 13 }}>{c.longDescription || c.description}</p>
                {c.referenceLink && <a className="small" href={c.referenceLink} target="_blank" rel="noreferrer">Provider documentation</a>}

                {s.instances.map(i => (
                  <div key={i.id} className="between" style={{ gap: 8, borderTop: '1px solid var(--border)', paddingTop: 8 }}>
                    <div style={{ minWidth: 0 }}>
                      <b className="small">{c.multiInstance ? `${i.position}. ` : ''}{i.name}</b>
                      <div className="faint small">
                        {!i.enabled ? 'Switched off' : i.connected ? 'Ready' : `Missing: ${i.missing.map(k => c.properties.find(p => p.key === k)?.label ?? k).join(', ')}`}
                      </div>
                    </div>
                    {!i.editable && <span className="faint small">Managed by the platform</span>}
                    {s.canConnect && i.editable && (
                      <div className="row" style={{ gap: 6 }}>
                        <button type="button" className="btn sm" onClick={() => setEditing({ state: s, instance: i })}>Edit</button>
                        <button type="button" className="btn sm" aria-label={`Remove ${i.name}`} onClick={() => setRemoving({ state: s, instance: i })}><Icon name="trash" size={13} /></button>
                      </div>
                    )}
                  </div>
                ))}

                <div className="between" style={{ marginTop: 'auto', borderTop: '1px solid var(--border)', paddingTop: 10, gap: 8 }}>
                  <span className="faint small">{s.note ?? (s.canConnect ? '' : 'Read only')}</span>
                  {add && <button type="button" className="btn sm primary" onClick={() => setEditing({ state: s, instance: null })}>
                    <Icon name="plus" size={13} /> {s.instances.length ? 'Add another' : 'Connect'}
                  </button>}
                </div>
              </div>
            )
          })}
        </div>
      )}

      {editing && <ConnectForm base={base} editing={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); view.reload() }} />}
      {removing && <Confirm title={`Remove ${removing.instance.name}?`} danger confirmLabel="Remove"
        text={`Its stored values, secrets included, are deleted. ${removing.state.connector.name} stops working for this ${base.includes('/orgs/') ? 'project' : 'platform'} until it is connected again.`}
        onClose={() => setRemoving(null)}
        onConfirm={() => run(() => api(`${base}/${removing.state.connector.key}/${removing.instance.id}`, { method: 'DELETE' }), `${removing.instance.name} removed`).then(view.reload)} />}
    </section>
  )
}

function ConnectForm({ base, editing, onClose, onSaved }: { base: string; editing: Editing; onClose: () => void; onSaved: () => void }) {
  const { state: { connector: c }, instance } = editing
  const { busy, run } = useAction()
  const [name, setName] = useState(instance?.name ?? (c.multiInstance ? '' : c.name))
  const [enabled, setEnabled] = useState(instance?.enabled ?? true)
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(c.properties.map(p => [p.key, !p.encrypted && instance?.values[p.key]?.configured ? instance.values[p.key].value ?? '' : ''])))
  const fields = [...c.properties].sort((a, b) => a.position - b.position)

  const save = (e: React.FormEvent) => {
    e.preventDefault()
    const path = instance ? `${base}/${c.key}/${instance.id}` : `${base}/${c.key}`
    run(() => api(path, { method: instance ? 'PUT' : 'POST', body: { name, enabled, values } }), `${c.name} saved`).then(r => { if (r) onSaved() })
  }

  return (
    <Modal title={`${instance ? 'Edit' : 'Connect'} ${c.name}`} subtitle="Encrypted values are stored sealed and never shown again." onClose={onClose} size="md">
      <form className="stack" style={{ gap: 12 }} onSubmit={save} aria-label={`${c.name} connection`}>
        <label className="field">Name<input value={name} maxLength={100} placeholder={c.name} onChange={e => setName(e.target.value)} /></label>
        {fields.map(p => {
          const stored = instance?.values[p.key]?.configured
          const set = (v: string) => setValues({ ...values, [p.key]: v })
          const v = values[p.key] ?? ''
          const required = p.required && !stored && p.defaultValue == null
          return (
            <label key={p.key} className="field">
              <span>{p.label}{p.required && ' *'}{p.scope === 'INTERNAL' && <span className="chip" style={{ marginLeft: 6 }}>Platform only</span>}</span>
              {p.valueType === 'BOOLEAN' || p.valueType === 'SELECT' ? (
                <select aria-label={p.label} value={v} required={required} onChange={e => set(e.target.value)}>
                  <option value="">{p.defaultValue != null ? `Default (${p.defaultValue})` : 'Choose…'}</option>
                  {(p.valueType === 'BOOLEAN' ? ['true', 'false'] : p.options).map(o => <option key={o} value={o}>{p.valueType === 'BOOLEAN' ? (o === 'true' ? 'Yes' : 'No') : o}</option>)}
                </select>
              ) : (
                <input aria-label={p.label} value={v} required={required} autoComplete="off"
                  type={p.encrypted ? 'password' : p.valueType === 'NUMBER' ? 'number' : p.valueType === 'URL' ? 'url' : 'text'}
                  placeholder={p.encrypted && stored ? 'Stored. Leave blank to keep it.' : p.defaultValue ?? ''}
                  onChange={e => set(e.target.value)} />
              )}
              {p.description && <span className="hint">{p.description}</span>}
            </label>
          )
        })}
        <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> Switched on</label>
        <div className="row" style={{ gap: 8 }}>
          <button className="btn primary" disabled={busy}>{busy ? 'Saving…' : instance ? 'Save' : 'Connect'}</button>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
        </div>
      </form>
    </Modal>
  )
}
