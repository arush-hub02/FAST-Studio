import { useState } from 'react'
import { api } from '../../shared/api'
import { ErrorBox, Icon, Loading, Modal, useAction, useApi } from '../../shared/ui'
import type { Catalogue } from '../../shared/configuration/Catalogue'

// Connector marketplace administration (docs/PLATFORM_ADMIN_PLAN.md, P3): categories, a connector's marketplace
// details, and its fields. Fields are rows in connector_property, never hard-coded.

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
export type ConnectorCatalogue = { categories: ConnectorCategory[]; connectors: MarketConnector[] }

const STATUS_TEXT = { AVAILABLE: 'Available', UPCOMING: 'Coming soon', UNPUBLISHED: 'Unpublished' }
const RULE_TEXT = { ALLOWED: 'Tenants connect it', NOT_ALLOWED: 'Platform connects it', CONTACT_ACCOUNT_MANAGER: 'Contact account manager' }

export function useConnectorCatalogue() { return useApi<ConnectorCatalogue>('/api/platform/connector-catalogue') }

/** Status and rule chips shown on a connector card. */
export function MarketplaceChips({ c }: { c?: MarketConnector }) {
  if (!c) return null
  return (
    <>
      <span className={`badge ${c.status === 'AVAILABLE' ? 'ok' : ''}`}>{STATUS_TEXT[c.status]}</span>
      <span className="chip">{RULE_TEXT[c.tenantUpdate]}</span>
      <span className="chip">{c.properties.length} field{c.properties.length === 1 ? '' : 's'}</span>
    </>
  )
}

// ------------------------------------------------------------------ categories

export function CategoriesModal({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const view = useConnectorCatalogue()
  const { busy, run } = useAction()
  const [form, setForm] = useState<ConnectorCategory | null>(null)
  const [isNew, setIsNew] = useState(false)
  const done = (r: unknown) => { if (r) { view.reload(); onChanged(); setForm(null) } }

  return (
    <Modal title="Connector categories" subtitle="Group connectors in the marketplace. A category in use can't be deleted." onClose={onClose} size="md">
      {view.loading ? <Loading /> : view.error ? <ErrorBox error={view.error} /> : (
        <div className="stack" style={{ gap: 12 }}>
          <table className="table">
            <thead><tr><th>Identifier</th><th>Name</th><th>Order</th><th /></tr></thead>
            <tbody>
              {view.data!.categories.map(c => (
                <tr key={c.key}>
                  <td className="mono">{c.key}</td><td>{c.name}<div className="faint small">{c.description}</div></td><td>{c.position}</td>
                  <td className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                    <button type="button" className="btn sm" onClick={() => { setIsNew(false); setForm(c) }}>Edit</button>
                    <button type="button" className="btn sm" aria-label={`Delete ${c.name}`} disabled={busy}
                      onClick={() => run(() => api(`/api/platform/connector-categories/${c.key}`, { method: 'DELETE' }), `Category ${c.name} deleted`).then(done)}>
                      <Icon name="trash" size={13} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {form ? (
            <form className="stack" style={{ gap: 10 }} onSubmit={e => {
              e.preventDefault()
              run(() => api(isNew ? '/api/platform/connector-categories' : `/api/platform/connector-categories/${form.key}`,
                { method: isNew ? 'POST' : 'PUT', body: form }), `Category ${form.name} saved`).then(done)
            }}>
              <div className="row-wrap" style={{ gap: 10 }}>
                <label className="field">Identifier
                  <input value={form.key} disabled={!isNew} required pattern="[A-Z][A-Z0-9_]{1,39}" onChange={e => setForm({ ...form, key: e.target.value.toUpperCase() })} />
                  <span className="hint">Capitals and _, e.g. ANALYTICS.</span>
                </label>
                <label className="field">Name<input value={form.name} required maxLength={80} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
                <label className="field">Order<input type="number" value={form.position} onChange={e => setForm({ ...form, position: Number(e.target.value) })} /></label>
              </div>
              <label className="field">Description<input value={form.description} maxLength={300} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
              <div className="row" style={{ gap: 8 }}>
                <button className="btn primary" disabled={busy}>{isNew ? 'Add category' : 'Save category'}</button>
                <button type="button" className="btn" onClick={() => setForm(null)}>Cancel</button>
              </div>
            </form>
          ) : (
            <button type="button" className="btn" style={{ alignSelf: 'flex-start' }}
              onClick={() => { setIsNew(true); setForm({ key: '', name: '', description: '', position: view.data!.categories.length + 1 }) }}>
              <Icon name="plus" size={14} /> Add category
            </button>
          )}
        </div>
      )}
    </Modal>
  )
}

// ------------------------------------------------------------------ one connector: marketplace details and fields

const blankField: ConnectorField = { connectorKey: '', key: '', label: '', description: '', valueType: 'STRING', options: [], defaultValue: null, position: 0, required: false, encrypted: false, scope: 'EXTERNAL' }

export function ConnectorFieldsModal({ connectorKey, onClose, onChanged }: { connectorKey: string; onClose: () => void; onChanged: () => void }) {
  const view = useConnectorCatalogue()
  const perms = useApi<Catalogue>('/api/platform/catalogue')
  const { busy, run } = useAction()
  const [details, setDetails] = useState<MarketConnector | null>(null)
  const [field, setField] = useState<ConnectorField | null>(null)
  const [isNew, setIsNew] = useState(false)
  const c = view.data?.connectors.find(x => x.key === connectorKey)
  const d = details ?? c
  const refresh = (r: unknown) => { if (r) { view.reload(); onChanged() } return r }

  const saveDetails = (e: React.FormEvent) => {
    e.preventDefault()
    if (!d) return
    run(() => api(`/api/platform/connector-definitions/${connectorKey}/details`, { method: 'PUT', body: d }), 'Marketplace details saved')
      .then(r => { if (refresh(r)) setDetails(null) })
  }

  const saveField = (e: React.FormEvent) => {
    e.preventDefault()
    if (!field) return
    run(() => api(`/api/platform/connector-definitions/${connectorKey}/properties/${field.key}`, { method: 'PUT', body: field }), `Field ${field.label} saved`)
      .then(r => { if (refresh(r)) setField(null) })
  }

  return (
    <Modal title={`Marketplace and fields: ${c?.name ?? connectorKey}`} subtitle="What tenants see, who may connect it, and the fields a connection holds." onClose={onClose} size="lg">
      {view.loading ? <Loading /> : view.error ? <ErrorBox error={view.error} /> : !d ? <p>Connector not found.</p> : (
        <div className="stack" style={{ gap: 20 }}>
          <form className="stack" style={{ gap: 10 }} onSubmit={saveDetails}>
            <h3 style={{ margin: 0 }}>Marketplace</h3>
            <div className="row-wrap" style={{ gap: 10 }}>
              <label className="field">Status
                <select value={d.status} onChange={e => setDetails({ ...d, status: e.target.value as MarketConnector['status'] })}>
                  <option value="AVAILABLE">Available</option><option value="UPCOMING">Coming soon</option><option value="UNPUBLISHED">Unpublished (hidden)</option>
                </select>
              </label>
              <label className="field">Who connects it
                <select value={d.tenantUpdate} onChange={e => setDetails({ ...d, tenantUpdate: e.target.value as MarketConnector['tenantUpdate'] })}>
                  <option value="ALLOWED">Tenants connect it themselves</option><option value="NOT_ALLOWED">Platform connects it</option>
                  <option value="CONTACT_ACCOUNT_MANAGER">Contact account manager</option>
                </select>
              </label>
              <label className="field">Shown to people with
                <select value={d.requiredPermission ?? ''} onChange={e => setDetails({ ...d, requiredPermission: e.target.value || null })}>
                  <option value="">Everyone in the project</option>
                  {perms.data?.permissions.map(p => <option key={p.key} value={p.key}>{p.name} ({p.key})</option>)}
                </select>
              </label>
              <label className="field">Level
                <select value={d.level} onChange={e => setDetails({ ...d, level: e.target.value as MarketConnector['level'] })}>
                  <option value="PROJECT">Each project</option><option value="PLATFORM">Whole platform (one set of values)</option>
                </select>
              </label>
              <label className="field">Tier
                <select value={d.tier} onChange={e => setDetails({ ...d, tier: e.target.value as MarketConnector['tier'] })}><option>FREE</option><option>PRO</option></select>
              </label>
            </div>
            <label className="row" style={{ gap: 8 }}>
              <input type="checkbox" checked={d.multiInstance} onChange={e => setDetails({ ...d, multiInstance: e.target.checked })} />
              A project can add several (e.g. ad servers in waterfall order)
            </label>
            <div className="row-wrap" style={{ gap: 10 }}>
              <label className="field">Logo address<input type="url" value={d.logoUrl ?? ''} onChange={e => setDetails({ ...d, logoUrl: e.target.value })} /></label>
              <label className="field">Dark-mode logo<input type="url" value={d.darkLogoUrl ?? ''} onChange={e => setDetails({ ...d, darkLogoUrl: e.target.value })} /></label>
              <label className="field">Reference link<input type="url" value={d.referenceLink ?? ''} onChange={e => setDetails({ ...d, referenceLink: e.target.value })} /></label>
            </div>
            <label className="field">Long description
              <textarea rows={3} maxLength={4000} value={d.longDescription} onChange={e => setDetails({ ...d, longDescription: e.target.value })} />
            </label>
            <button className="btn primary" style={{ alignSelf: 'flex-start' }} disabled={busy || !details}>Save marketplace details</button>
          </form>

          <div className="stack" style={{ gap: 10 }}>
            <div className="between">
              <h3 style={{ margin: 0 }}>Fields</h3>
              <button type="button" className="btn sm" onClick={() => { setIsNew(true); setField({ ...blankField, connectorKey, position: d.properties.length + 1 }) }}>
                <Icon name="plus" size={13} /> Add field
              </button>
            </div>
            {d.properties.length === 0 ? <p className="faint">No fields yet. Add the keys a connection needs.</p> : (
              <table className="table">
                <thead><tr><th>Key</th><th>Label</th><th>Type</th><th>Rules</th><th /></tr></thead>
                <tbody>
                  {d.properties.map(p => (
                    <tr key={p.key}>
                      <td className="mono">{p.key}</td>
                      <td>{p.label}{p.description && <div className="faint small">{p.description}</div>}</td>
                      <td>{p.valueType}{p.valueType === 'SELECT' && <div className="faint small">{p.options.join(', ')}</div>}</td>
                      <td className="row-wrap" style={{ gap: 4 }}>
                        {p.required && <span className="chip">Required</span>}
                        {p.encrypted && <span className="chip"><Icon name="key" size={11} /> Encrypted</span>}
                        <span className="chip">{p.scope === 'EXTERNAL' ? 'Tenant sees' : 'Platform only'}</span>
                        {p.defaultValue && <span className="chip">Default {p.defaultValue}</span>}
                      </td>
                      <td className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
                        <button type="button" className="btn sm" onClick={() => { setIsNew(false); setField(p) }}>Edit</button>
                        <button type="button" className="btn sm" aria-label={`Delete field ${p.key}`} disabled={busy}
                          onClick={() => window.confirm(`Delete field ${p.key}? Values stored for it in every project are removed.`)
                            && run(() => api(`/api/platform/connector-definitions/${connectorKey}/properties/${p.key}`, { method: 'DELETE' }), `Field ${p.key} deleted`).then(refresh)}>
                          <Icon name="trash" size={13} />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            {field && (
              <form className="card card-pad stack" style={{ gap: 10 }} onSubmit={saveField} aria-label="Connector field">
                <div className="row-wrap" style={{ gap: 10 }}>
                  <label className="field">Key
                    <input value={field.key} disabled={!isNew} required pattern="[a-z][a-z0-9_]{1,63}" onChange={e => setField({ ...field, key: e.target.value.toLowerCase() })} />
                    <span className="hint">Lower case and _, e.g. token_id. Adapters read values by this key.</span>
                  </label>
                  <label className="field">Label<input value={field.label} required maxLength={100} onChange={e => setField({ ...field, label: e.target.value })} /></label>
                  <label className="field">Type
                    <select value={field.valueType} onChange={e => setField({ ...field, valueType: e.target.value as ConnectorField['valueType'] })}>
                      <option value="STRING">Text</option><option value="NUMBER">Number</option><option value="BOOLEAN">Yes / no</option>
                      <option value="SELECT">List of choices</option><option value="URL">Web address</option>
                    </select>
                  </label>
                  <label className="field">Order<input type="number" value={field.position} onChange={e => setField({ ...field, position: Number(e.target.value) })} /></label>
                </div>
                {field.valueType === 'SELECT' && (
                  <label className="field">Choices (comma separated)
                    <input required value={field.options.join(', ')} onChange={e => setField({ ...field, options: e.target.value.split(',').map(s => s.trim()).filter(Boolean) })} />
                  </label>
                )}
                <div className="row-wrap" style={{ gap: 10 }}>
                  <label className="field">Description<input value={field.description} maxLength={300} onChange={e => setField({ ...field, description: e.target.value })} /></label>
                  {!field.encrypted && (
                    <label className="field">Default value<input value={field.defaultValue ?? ''} maxLength={500} onChange={e => setField({ ...field, defaultValue: e.target.value || null })} /></label>
                  )}
                  <label className="field">Who sees it
                    <select value={field.scope} onChange={e => setField({ ...field, scope: e.target.value as ConnectorField['scope'] })}>
                      <option value="EXTERNAL">Tenants and platform (external)</option><option value="INTERNAL">Platform only (internal)</option>
                    </select>
                  </label>
                </div>
                <div className="row-wrap" style={{ gap: 16 }}>
                  <label className="row" style={{ gap: 6 }}><input type="checkbox" checked={field.required} onChange={e => setField({ ...field, required: e.target.checked })} /> Required</label>
                  <label className="row" style={{ gap: 6 }}>
                    <input type="checkbox" checked={field.encrypted} onChange={e => setField({ ...field, encrypted: e.target.checked, defaultValue: e.target.checked ? null : field.defaultValue })} />
                    Encrypted (stored sealed, never shown again)
                  </label>
                </div>
                {!isNew && c?.properties.find(p => p.key === field.key)?.encrypted !== field.encrypted && (
                  <p className="faint small" role="alert">Changing encryption removes the values already stored for this field.</p>
                )}
                <div className="row" style={{ gap: 8 }}>
                  <button className="btn primary" disabled={busy}>{isNew ? 'Add field' : 'Save field'}</button>
                  <button type="button" className="btn" onClick={() => setField(null)}>Cancel</button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </Modal>
  )
}
