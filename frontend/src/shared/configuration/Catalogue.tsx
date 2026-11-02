import { useMemo, useState } from 'react'
import { api } from '../api'
import { Empty, ErrorBox, Icon, Loading, Modal, useAction, useApi } from '../ui'

// The global catalogue (docs/PLATFORM_ADMIN_PLAN.md): Module ─ Feature group ─ Feature ─ uses → Permission(s).
// Platform administrators keep it (Global configuration); a project links global features (Settings) and keeps its own
// roles (Team). Screens check features with org.has('KEY') and permissions with org.can('KEY'), or both together.
export type ModuleEntry = { key: string; name: string; description: string; position: number; enabled: boolean }
export type Group = { key: string; moduleKey: string; name: string; description: string; position: number; published: boolean }
export type Feature = {
  key: string; name: string; description: string; groupKey: string; permissions: string[]
  isDefault: boolean; hidden: boolean; beta: boolean; enabled: boolean; builtIn: boolean
}
export type Permission = { key: string; name: string; description: string; category: string; enabled: boolean; builtIn: boolean }
export type Catalogue = { modules: ModuleEntry[]; groups: Group[]; features: Feature[]; permissions: Permission[] }

const KEY = '[A-Z][A-Z0-9_]{1,63}'
const keyField = (value: string, onChange: (v: string) => void, locked: boolean, example: string) =>
  <label className="field">Identifier<input required disabled={locked} pattern={KEY} placeholder={example} value={value}
    onChange={e => onChange(e.target.value.toUpperCase())} /><span className="hint">Capitals, digits and underscores; it can't change later.</span></label>
const status = (on: boolean, onText = 'On', offText = 'Off') => <span className={`badge ${on ? 'ok' : ''}`}><span className="dot" />{on ? onText : offText}</span>
const matches = (q: string, ...text: string[]) => !q || text.join(' ').toLowerCase().includes(q.toLowerCase())

// ---------------------------------------------------------------- platform: Global configuration → Features & permissions

type Section = 'FEATURES' | 'PERMISSIONS' | 'GROUPS' | 'MODULES'

export function GlobalCatalogue() {
  const view = useApi<Catalogue>('/api/platform/catalogue')
  const [section, setSection] = useState<Section>('FEATURES')
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const c = view.data
  const tabs: [Section, string, number][] = [['FEATURES', 'Features', c.features.length], ['PERMISSIONS', 'Permissions', c.permissions.length],
    ['GROUPS', 'Feature groups', c.groups.length], ['MODULES', 'Modules', c.modules.length]]
  return <div className="stack" style={{ gap: 14 }}>
    <div className="seg" role="tablist" aria-label="Catalogue sections">
      {tabs.map(([k, label, n]) => <button key={k} type="button" role="tab" aria-selected={section === k} className={section === k ? 'active' : ''} onClick={() => setSection(k)}>{label} ({n})</button>)}
    </div>
    {section === 'FEATURES' && <FeaturesSection c={c} reload={view.reload} />}
    {section === 'PERMISSIONS' && <PermissionsSection c={c} reload={view.reload} />}
    {section === 'GROUPS' && <GroupsSection c={c} reload={view.reload} />}
    {section === 'MODULES' && <ModulesSection c={c} reload={view.reload} />}
  </div>
}

function SectionHead({ title, text, count, onAdd, addLabel, search, setSearch }: {
  title: string; text: string; count: number; onAdd: () => void; addLabel: string; search: string; setSearch: (s: string) => void
}) {
  return <>
    <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
      <div><div className="row" style={{ gap: 10 }}><h2 style={{ margin: 0 }}>{title}</h2><span className="badge mono small">{count}</span></div>
        <p className="faint" style={{ margin: '4px 0 0' }}>{text}</p></div>
      <button type="button" className="btn primary" onClick={onAdd}><Icon name="plus" size={15} /><span>{addLabel}</span></button>
    </div>
    <input type="search" className="input" style={{ maxWidth: 360 }} placeholder="Search by name or identifier…" aria-label={`Search ${title.toLowerCase()}`}
      value={search} onChange={e => setSearch(e.target.value)} />
  </>
}

function RowActions({ label, builtIn, onEdit, onDelete, busy }: { label: string; builtIn?: boolean; onEdit: () => void; onDelete: () => void; busy: boolean }) {
  return <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
    <button type="button" className="btn sm" onClick={onEdit} aria-label={`Edit ${label}`}>Edit</button>
    {!builtIn && <button type="button" className="btn sm" disabled={busy} onClick={onDelete} aria-label={`Remove ${label}`}><Icon name="trash" size={14} /></button>}
  </div>
}

function useRemove(reload: () => void) {
  const { busy, run } = useAction()
  return { busy, run, remove: (path: string, label: string) => run(() => api(path, { method: 'DELETE' }), `${label} removed`).then(() => reload()) }
}

function FeaturesSection({ c, reload }: { c: Catalogue; reload: () => void }) {
  const [search, setSearch] = useState('')
  const [moduleKey, setModuleKey] = useState('')
  const [editing, setEditing] = useState<Feature | 'new' | null>(null)
  const { busy, remove } = useRemove(reload)
  const groupOf = (k: string) => c.groups.find(g => g.key === k)
  const rows = c.features.filter(f => (!moduleKey || groupOf(f.groupKey)?.moduleKey === moduleKey) && matches(search, f.name, f.key, groupOf(f.groupKey)?.name ?? ''))
  return <section className="card card-pad stack" style={{ gap: 14 }}>
    <SectionHead title="Features" count={c.features.length} search={search} setSearch={setSearch} addLabel="Add feature" onAdd={() => setEditing('new')}
      text="A feature is switched on per project; when it's off, its parts of the screens are hidden for everyone. It lists the permissions it uses, which is how the role editor groups them." />
    <div className="seg" aria-label="Filter by module">
      <button type="button" className={!moduleKey ? 'active' : ''} onClick={() => setModuleKey('')}>All modules</button>
      {c.modules.map(m => <button key={m.key} type="button" className={moduleKey === m.key ? 'active' : ''} onClick={() => setModuleKey(m.key)}>{m.name}</button>)}
    </div>
    {rows.length === 0 ? <Empty icon="shield" title="No features here" text={search ? `Nothing matches "${search}".` : 'Add the first feature of this module.'} /> :
      <table className="table"><thead><tr><th>Feature</th><th>Group</th><th>Permissions it uses</th><th>Status</th><th /></tr></thead>
        <tbody>{rows.map(f => <tr key={f.key}>
          <td><b>{f.name}</b> {f.beta && <span className="badge small">Beta</span>}<div className="faint mono small">{f.key}</div></td>
          <td>{groupOf(f.groupKey)?.name ?? f.groupKey}<div className="faint small">{c.modules.find(m => m.key === groupOf(f.groupKey)?.moduleKey)?.name}</div></td>
          <td><div className="row-wrap" style={{ gap: 4 }}>{f.permissions.length ? f.permissions.map(p => <span key={p} className="chip mono small">{p}</span>) : <span className="faint">none</span>}</div></td>
          <td><div className="row-wrap" style={{ gap: 4 }}>{f.builtIn ? <span className="badge"><Icon name="shieldCheck" size={12} /> Built in</span> : status(f.enabled)}
            {f.isDefault && <span className="badge small">Default</span>}{f.hidden && <span className="badge small">Hidden</span>}</div></td>
          <td><RowActions label={f.key} builtIn={f.builtIn} busy={busy} onEdit={() => setEditing(f)} onDelete={() => remove(`/api/platform/features/${f.key}`, f.key)} /></td>
        </tr>)}</tbody></table>}
    {editing && <FeatureModal c={c} feature={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload() }} />}
  </section>
}

function FeatureModal({ c, feature, onClose, onSaved }: { c: Catalogue; feature: Feature | null; onClose: () => void; onSaved: () => void }) {
  // default group: the first group of the first module, as the group list shows them
  const firstGroup = c.modules.map(m => c.groups.find(g => g.moduleKey === m.key)).find(Boolean)?.key ?? ''
  const [form, setForm] = useState<Feature>(feature ?? { key: '', name: '', description: '', groupKey: firstGroup, permissions: [], isDefault: false, hidden: false, beta: false, enabled: true, builtIn: false })
  const { busy, run } = useAction()
  const save = () => run(() => api(feature ? `/api/platform/features/${form.key}` : '/api/platform/features', { method: feature ? 'PUT' : 'POST', body: form }),
    feature ? 'Feature saved' : 'Feature added').then(r => { if (r !== undefined) onSaved() })
  const flag = (k: 'isDefault' | 'hidden' | 'beta' | 'enabled', label: string, disabled = false) =>
    <label className="check"><input type="checkbox" disabled={disabled} checked={form[k]} onChange={e => setForm({ ...form, [k]: e.target.checked })} />{label}</label>
  return <Modal title={feature ? `Edit feature ${feature.key}` : 'Add a feature'} onClose={onClose} wide
    footer={<><button type="button" className="btn" onClick={onClose}>Cancel</button><button type="submit" form="feature-form" className="btn primary" disabled={busy}>{feature ? 'Save feature' : 'Add feature'}</button></>}>
    <form id="feature-form" className="stack" onSubmit={e => { e.preventDefault(); save() }}>
      <div className="grid g2">
        {keyField(form.key, v => setForm({ ...form, key: v }), !!feature, 'e.g. CLIPS')}
        <label className="field">Name<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Feature group<select required value={form.groupKey} onChange={e => setForm({ ...form, groupKey: e.target.value })}>
          {c.modules.map(m => <optgroup key={m.key} label={m.name}>{c.groups.filter(g => g.moduleKey === m.key).map(g => <option key={g.key} value={g.key}>{g.name}</option>)}</optgroup>)}
        </select></label>
        <label className="field">Description<input maxLength={300} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
      </div>
      <fieldset className="stack" style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '10px 14px', gap: 8 }}>
        <legend style={{ padding: '0 6px', fontWeight: 600, fontSize: 13 }}>Permissions this feature uses ({form.permissions.length})</legend>
        <div className="row-wrap" style={{ gap: 10 }}>{c.permissions.map(p => <label key={p.key} className="check" title={p.description}>
          <input type="checkbox" checked={form.permissions.includes(p.key)} onChange={e => setForm({ ...form, permissions: e.target.checked ? [...form.permissions, p.key] : form.permissions.filter(x => x !== p.key) })} />
          <span className="mono small">{p.key}</span></label>)}</div>
      </fieldset>
      <div className="row-wrap" style={{ gap: 16 }}>
        {flag('enabled', 'Enabled', form.builtIn)}{flag('isDefault', 'Default (linked by "Add default features")')}{flag('hidden', 'Hidden from projects')}{flag('beta', 'Beta')}
      </div>
    </form>
  </Modal>
}

function PermissionsSection({ c, reload }: { c: Catalogue; reload: () => void }) {
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState<Permission | 'new' | null>(null)
  const [form, setForm] = useState<Permission>({ key: '', name: '', description: '', category: '', enabled: true, builtIn: false })
  const { busy, run, remove } = useRemove(reload)
  const usedBy = (k: string) => c.features.filter(f => f.permissions.includes(k)).map(f => f.name)
  const rows = c.permissions.filter(p => matches(search, p.name, p.key, p.category))
  const open = (p: Permission | 'new') => { setEditing(p); setForm(p === 'new' ? { key: '', name: '', description: '', category: '', enabled: true, builtIn: false } : p) }
  const save = () => run(() => api(editing === 'new' ? '/api/platform/permissions' : `/api/platform/permissions/${form.key}`, { method: editing === 'new' ? 'POST' : 'PUT', body: form }),
    'Permission saved').then(r => { if (r !== undefined) { setEditing(null); reload() } })
  return <section className="card card-pad stack" style={{ gap: 14 }}>
    <SectionHead title="Permissions" count={c.permissions.length} search={search} setSearch={setSearch} addLabel="Add permission" onAdd={() => open('new')}
      text="Permissions are given to people through roles. System permissions are enforced by the server; custom ones show or hide things in screens, often together with a feature." />
    {rows.length === 0 ? <Empty icon="key" title="No permissions match" /> :
      <table className="table"><thead><tr><th>Permission</th><th>Category</th><th>Used by features</th><th>Type</th><th /></tr></thead>
        <tbody>{rows.map(p => <tr key={p.key}>
          <td><b>{p.name}</b><div className="faint mono small">{p.key}</div>{p.description && <div className="faint small">{p.description}</div>}</td>
          <td><span className="chip">{p.category}</span></td>
          <td className="small">{usedBy(p.key).join(', ') || <span className="faint">none</span>}</td>
          <td>{p.builtIn ? <span className="badge"><Icon name="shieldCheck" size={12} /> System</span> : <div className="row" style={{ gap: 4 }}><span className="badge">Custom</span>{status(p.enabled)}</div>}</td>
          <td><RowActions label={p.key} builtIn={p.builtIn} busy={busy} onEdit={() => open(p)} onDelete={() => remove(`/api/platform/permissions/${p.key}`, p.key)} /></td>
        </tr>)}</tbody></table>}
    {editing && <Modal title={editing === 'new' ? 'Add a permission' : `Edit permission ${form.key}`} onClose={() => setEditing(null)}
      footer={<><button type="button" className="btn" onClick={() => setEditing(null)}>Cancel</button><button type="submit" form="permission-form" className="btn primary" disabled={busy}>Save permission</button></>}>
      <form id="permission-form" className="stack" onSubmit={e => { e.preventDefault(); save() }}>
        {keyField(form.key, v => setForm({ ...form, key: v }), editing !== 'new', 'e.g. CLIPS_EXPORT')}
        <label className="field">Name<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Category<input maxLength={40} placeholder="General" value={form.category} onChange={e => setForm({ ...form, category: e.target.value })} /></label>
        <label className="field">Description<input maxLength={300} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
        <label className="check"><input type="checkbox" disabled={form.builtIn} checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} />Enabled</label>
      </form>
    </Modal>}
  </section>
}

function GroupsSection({ c, reload }: { c: Catalogue; reload: () => void }) {
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState<'new' | string | null>(null)
  const blank: Group = { key: '', moduleKey: c.modules[0]?.key ?? '', name: '', description: '', position: 0, published: true }
  const [form, setForm] = useState<Group>(blank)
  const { busy, run, remove } = useRemove(reload)
  const rows = c.groups.filter(g => matches(search, g.name, g.key))
  const save = () => run(() => api(editing === 'new' ? '/api/platform/feature-groups' : `/api/platform/feature-groups/${form.key}`, { method: editing === 'new' ? 'POST' : 'PUT', body: form }),
    'Feature group saved').then(r => { if (r !== undefined) { setEditing(null); reload() } })
  return <section className="card card-pad stack" style={{ gap: 14 }}>
    <SectionHead title="Feature groups" count={c.groups.length} search={search} setSearch={setSearch} addLabel="Add group" onAdd={() => { setForm(blank); setEditing('new') }}
      text="Groups sort features inside a module. Projects search features by group, and the role editor shows one tab per group. Unpublished groups are hidden from projects." />
    <table className="table"><thead><tr><th>Group</th><th>Module</th><th>Features</th><th>Status</th><th /></tr></thead>
      <tbody>{rows.map(g => <tr key={g.key}>
        <td><b>{g.name}</b><div className="faint mono small">{g.key}</div></td>
        <td>{c.modules.find(m => m.key === g.moduleKey)?.name ?? g.moduleKey}</td>
        <td>{c.features.filter(f => f.groupKey === g.key).length}</td>
        <td>{status(g.published, 'Published', 'Unpublished')}</td>
        <td><RowActions label={g.key} busy={busy} onEdit={() => { setForm(g); setEditing(g.key) }} onDelete={() => remove(`/api/platform/feature-groups/${g.key}`, g.key)} /></td>
      </tr>)}</tbody></table>
    {editing && <Modal title={editing === 'new' ? 'Add a feature group' : `Edit group ${form.key}`} onClose={() => setEditing(null)}
      footer={<><button type="button" className="btn" onClick={() => setEditing(null)}>Cancel</button><button type="submit" form="group-form" className="btn primary" disabled={busy}>Save group</button></>}>
      <form id="group-form" className="stack" onSubmit={e => { e.preventDefault(); save() }}>
        {keyField(form.key, v => setForm({ ...form, key: v }), editing !== 'new', 'e.g. CLIPS_TOOLS')}
        <label className="field">Name<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Module<select required value={form.moduleKey} onChange={e => setForm({ ...form, moduleKey: e.target.value })}>{c.modules.map(m => <option key={m.key} value={m.key}>{m.name}</option>)}</select></label>
        <label className="field">Description<input maxLength={300} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
        <label className="field">Order<input type="number" min={0} value={form.position} onChange={e => setForm({ ...form, position: Number(e.target.value) })} /></label>
        <label className="check"><input type="checkbox" checked={form.published} onChange={e => setForm({ ...form, published: e.target.checked })} />Published (offered to projects)</label>
      </form>
    </Modal>}
  </section>
}

function ModulesSection({ c, reload }: { c: Catalogue; reload: () => void }) {
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState<'new' | string | null>(null)
  const blank: ModuleEntry = { key: '', name: '', description: '', position: 0, enabled: true }
  const [form, setForm] = useState<ModuleEntry>(blank)
  const { busy, run, remove } = useRemove(reload)
  const rows = c.modules.filter(m => matches(search, m.name, m.key))
  const save = () => run(() => api(editing === 'new' ? '/api/platform/modules' : `/api/platform/modules/${form.key}`, { method: editing === 'new' ? 'POST' : 'PUT', body: form }),
    'Module saved').then(r => { if (r !== undefined) { setEditing(null); reload() } })
  return <section className="card card-pad stack" style={{ gap: 14 }}>
    <SectionHead title="Modules" count={c.modules.length} search={search} setSearch={setSearch} addLabel="Add module" onAdd={() => { setForm(blank); setEditing('new') }}
      text="The top level of the catalogue, such as Media or Advertising. The role editor shows one tab per module." />
    <table className="table"><thead><tr><th>Module</th><th>Groups</th><th>Order</th><th>Status</th><th /></tr></thead>
      <tbody>{rows.map(m => <tr key={m.key}>
        <td><b>{m.name}</b><div className="faint mono small">{m.key}</div>{m.description && <div className="faint small">{m.description}</div>}</td>
        <td>{c.groups.filter(g => g.moduleKey === m.key).map(g => g.name).join(', ') || <span className="faint">none</span>}</td>
        <td>{m.position}</td><td>{status(m.enabled)}</td>
        <td><RowActions label={m.key} busy={busy} onEdit={() => { setForm(m); setEditing(m.key) }} onDelete={() => remove(`/api/platform/modules/${m.key}`, m.key)} /></td>
      </tr>)}</tbody></table>
    {editing && <Modal title={editing === 'new' ? 'Add a module' : `Edit module ${form.key}`} onClose={() => setEditing(null)}
      footer={<><button type="button" className="btn" onClick={() => setEditing(null)}>Cancel</button><button type="submit" form="module-form" className="btn primary" disabled={busy}>Save module</button></>}>
      <form id="module-form" className="stack" onSubmit={e => { e.preventDefault(); save() }}>
        {keyField(form.key, v => setForm({ ...form, key: v }), editing !== 'new', 'e.g. STUDIO')}
        <label className="field">Name<input required maxLength={80} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
        <label className="field">Description<input maxLength={300} value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></label>
        <label className="field">Order<input type="number" min={0} value={form.position} onChange={e => setForm({ ...form, position: Number(e.target.value) })} /></label>
        <label className="check"><input type="checkbox" checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} />Enabled</label>
      </form>
    </Modal>}
  </section>
}

// ---------------------------------------------------------------- role editor: permissions by module → group → feature

/**
 * Picks permissions the way the role editor of the v2 CMS does: a tab per module, a tab per feature group, and under
 * each feature the permissions it uses. Permissions no feature uses are listed under "Other". allowed = the role
 * ceiling's keys (others are greyed out); VIEW is always on.
 */
export function PermissionPicker({ c, value, onChange, allowed }: { c: Catalogue; value: string[]; onChange: (v: string[]) => void; allowed: string[] }) {
  const modules = c.modules.filter(m => c.groups.some(g => g.moduleKey === m.key && c.features.some(f => f.groupKey === g.key && f.permissions.length)))
  const [moduleKey, setModuleKey] = useState(modules[0]?.key ?? 'OTHER')
  const groups = c.groups.filter(g => g.moduleKey === moduleKey && c.features.some(f => f.groupKey === g.key && f.permissions.length))
  const [groupKey, setGroupKey] = useState('')
  const group = groups.find(g => g.key === groupKey) ?? groups[0]
  const used = useMemo(() => new Set(c.features.flatMap(f => f.permissions)), [c])
  const other = c.permissions.filter(p => !used.has(p.key))
  const name = (k: string) => c.permissions.find(p => p.key === k)
  const toggle = (k: string, on: boolean) => onChange(on ? [...new Set([...value, k])] : value.filter(x => x !== k))
  const box = (k: string) => <label key={k} className="check" title={name(k)?.description} style={{ opacity: allowed.includes(k) ? 1 : 0.45 }}>
    <input type="checkbox" disabled={k === 'VIEW' || !allowed.includes(k)} checked={value.includes(k)} onChange={e => toggle(k, e.target.checked)} />
    <span>{name(k)?.name ?? k} <span className="faint mono small">{k}</span></span></label>
  return <div className="stack" style={{ gap: 10 }}>
    <div className="seg" role="tablist" aria-label="Modules">
      {modules.map(m => <button key={m.key} type="button" className={moduleKey === m.key ? 'active' : ''} onClick={() => { setModuleKey(m.key); setGroupKey('') }}>{m.name}</button>)}
      {other.length > 0 && <button type="button" className={moduleKey === 'OTHER' ? 'active' : ''} onClick={() => setModuleKey('OTHER')}>Other</button>}
    </div>
    {moduleKey === 'OTHER' ? <div className="stack" style={{ gap: 8 }}>
      <span className="faint small">Permissions no feature uses yet, by category.</span>
      {[...new Set(other.map(p => p.category))].map(cat => <div key={cat} style={{ padding: '8px 12px', border: '1px solid var(--border)', borderRadius: 'var(--radius)' }}>
        <b className="small">{cat}</b><div className="row-wrap" style={{ gap: 12, marginTop: 6 }}>{other.filter(p => p.category === cat).map(p => box(p.key))}</div>
      </div>)}
    </div> : <>
      <div className="seg" role="tablist" aria-label="Feature groups">
        {groups.map(g => <button key={g.key} type="button" className={group?.key === g.key ? 'active' : ''} onClick={() => setGroupKey(g.key)}>{g.name}</button>)}
      </div>
      <div className="stack" style={{ gap: 8 }}>{c.features.filter(f => f.groupKey === group?.key && f.permissions.length).map(f =>
        <div key={f.key} className="box" style={{ padding: '8px 12px', border: '1px solid var(--border)', borderRadius: 'var(--radius)' }}>
          <b className="small">{f.name}</b> <span className="faint mono small">{f.key}</span>
          <div className="row-wrap" style={{ gap: 12, marginTop: 6 }}>{f.permissions.map(box)}</div>
        </div>)}</div>
    </>}
    <span className="faint small">{value.length} permissions selected. Greyed-out ones are above this role's ceiling.</span>
  </div>
}

// ---------------------------------------------------------------- a project: link global features

type ProjectView = { modules: ModuleEntry[]; groups: Group[]; available: Feature[]; linked: string[]; features: string[]; permissions: string[] }

export function ProjectFeatures({ slug, editable }: { slug: string; editable: boolean }) {
  const base = `/api/orgs/${slug}/features`
  const view = useApi<ProjectView>(base)
  const [search, setSearch] = useState('')
  const [moduleKey, setModuleKey] = useState('')
  const [groupKey, setGroupKey] = useState('')
  const { busy, run } = useAction()
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const v = view.data
  const groupOf = (k: string) => v.groups.find(g => g.key === k)
  const rows = v.available.filter(f => (!moduleKey || groupOf(f.groupKey)?.moduleKey === moduleKey) && (!groupKey || f.groupKey === groupKey)
    && matches(search, f.name, f.key, f.description))
  const set = (f: Feature, on: boolean) => run(() => api<ProjectView>(`${base}/${f.key}`, { method: on ? 'PUT' : 'DELETE' }), `${f.name} ${on ? 'switched on' : 'switched off'}`)
    .then(r => { if (r) view.setData(r) })
  return <>
    <section className="card card-pad stack" aria-label="Features and permissions that apply to you">
      <h2>Features and permissions that apply to you</h2>
      <p className="faint">Screens show or hide things by these identifiers.</p>
      <div><b>Features</b> <span className="row-wrap" style={{ gap: 6, display: 'inline-flex' }}>{v.features.length ? v.features.map(f => <span key={f} className="badge ok mono small">{f}</span>) : <span className="faint">none</span>}</span></div>
      <div><b>Permissions</b> <span className="row-wrap" style={{ gap: 6, display: 'inline-flex' }}>{v.permissions.map(p => <span key={p} className="badge mono small">{p}</span>)}</span></div>
    </section>
    <section className="card card-pad stack" style={{ gap: 12 }} aria-label="Project features">
      <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
        <div><h2 style={{ margin: 0 }}>Project features</h2><p className="faint" style={{ margin: '4px 0 0' }}>Switch on features from the platform catalogue for this project. Subscription features are switched on by the platform administrator.</p></div>
        {editable && <button type="button" className="btn" disabled={busy} onClick={() => run(() => api<ProjectView>(`${base}/defaults`, { method: 'POST' }), 'Default features switched on').then(r => { if (r) view.setData(r) })}>Add default features</button>}
      </div>
      <div className="row-wrap" style={{ gap: 10 }}>
        <input type="search" className="input" style={{ flex: '1 1 220px', maxWidth: 320 }} placeholder="Search features…" aria-label="Search features" value={search} onChange={e => setSearch(e.target.value)} />
        <select className="input" style={{ width: 'auto' }} aria-label="Module" value={moduleKey} onChange={e => { setModuleKey(e.target.value); setGroupKey('') }}>
          <option value="">All modules</option>{v.modules.map(m => <option key={m.key} value={m.key}>{m.name}</option>)}</select>
        <select className="input" style={{ width: 'auto' }} aria-label="Feature group" value={groupKey} onChange={e => setGroupKey(e.target.value)}>
          <option value="">All groups</option>{v.groups.filter(g => !moduleKey || g.moduleKey === moduleKey).map(g => <option key={g.key} value={g.key}>{g.name}</option>)}</select>
      </div>
      {rows.length === 0 ? <Empty icon="search" title="No features match" /> :
        <table className="table"><thead><tr><th>Feature</th><th>Group</th><th>Status</th><th /></tr></thead>
          <tbody>{rows.map(f => {
            const on = v.features.includes(f.key), linked = v.linked.includes(f.key)
            return <tr key={f.key}>
              <td><b>{f.name}</b> {f.beta && <span className="badge small">Beta</span>}<div className="faint mono small">{f.key}</div>{f.description && <div className="faint small">{f.description}</div>}</td>
              <td>{groupOf(f.groupKey)?.name}<div className="faint small">{v.modules.find(m => m.key === groupOf(f.groupKey)?.moduleKey)?.name}</div></td>
              <td>{f.builtIn ? <span className="badge">{on ? 'On (subscription)' : 'Subscription'}</span> : status(on)}</td>
              <td>{editable && !f.builtIn && <button type="button" className={`btn sm ${linked ? '' : 'primary'}`} disabled={busy} onClick={() => set(f, !linked)}>{linked ? 'Switch off' : 'Switch on'}</button>}</td>
            </tr>
          })}</tbody></table>}
    </section>
  </>
}

// ---------------------------------------------------------------- a project: its own roles

type RoleRow = { key: string; name: string; kind: string; baseRole: string; permissions: string; enabled: boolean; revision: number; orgId: string | null }
type ProjectRolesView = { roles: RoleRow[]; permissions: string[]; ceilings: Record<string, string[]>; catalogue: Catalogue }
const CUSTOMER_CEILINGS = ['ADMIN', 'CONTENT_MANAGER', 'CHANNEL_OPERATOR', 'AD_OPS', 'ANALYST']

export function ProjectRoles({ slug, editable, kind }: { slug: string; editable: boolean; kind: string }) {
  const base = `/api/orgs/${slug}/roles`
  const view = useApi<ProjectRolesView>(base)
  const blank = { key: '', name: '', kind, baseRole: kind === 'PARTNER' ? 'PARTNER_VIEWER' : 'ANALYST', permissions: ['VIEW'], enabled: true, expectedRevision: 0 }
  const [form, setForm] = useState<typeof blank | null>(null)
  const [isNew, setIsNew] = useState(true)
  const { busy, run } = useAction()
  if (view.error) return <ErrorBox error={view.error} retry={view.reload} />
  if (!view.data) return <Loading />
  const v = view.data
  const ceilings = kind === 'PARTNER' ? ['PARTNER_ADMIN', 'PARTNER_VIEWER'] : CUSTOMER_CEILINGS
  const save = () => form && run(() => api<ProjectRolesView>(base, { method: 'PUT', body: form }), 'Role saved').then(r => { if (r) { view.setData(r); setForm(null) } })
  const remove = (key: string) => run(() => api<ProjectRolesView>(`${base}/${key}`, { method: 'DELETE' }), 'Role removed').then(r => { if (r) view.setData(r) })
  return <section className="card card-pad stack" style={{ gap: 12 }} aria-label="Roles">
    <div className="between" style={{ flexWrap: 'wrap', gap: 12 }}>
      <div><h2 style={{ margin: 0 }}>Roles</h2><p className="faint" style={{ margin: '4px 0 0' }}>Platform roles are available to every project; add roles of your own for this project. A role stays within its ceiling and can add custom permissions.</p></div>
      {editable && <button type="button" className="btn primary" onClick={() => { setIsNew(true); setForm(blank) }}><Icon name="plus" size={15} /><span>Add role</span></button>}
    </div>
    <table className="table"><thead><tr><th>Role</th><th>Ceiling</th><th>Permissions</th><th>Scope</th><th /></tr></thead>
      <tbody>{v.roles.map(r => <tr key={r.key}>
        <td><b>{r.name}</b><div className="faint mono small">{r.key}</div></td><td>{r.baseRole}</td>
        <td className="small">{r.permissions.split(',').length}</td>
        <td>{r.orgId ? <span className="badge ok">This project</span> : <span className="badge">Platform</span>}{!r.enabled && <span className="badge small">Off</span>}</td>
        <td>{editable && r.orgId && <div className="row" style={{ gap: 6, justifyContent: 'flex-end' }}>
          <button type="button" className="btn sm" onClick={() => { setIsNew(false); setForm({ ...r, permissions: r.permissions.split(','), expectedRevision: r.revision }) }}>Edit</button>
          <button type="button" className="btn sm" disabled={busy} aria-label={`Remove ${r.key}`} onClick={() => remove(r.key)}><Icon name="trash" size={14} /></button></div>}</td>
      </tr>)}</tbody></table>
    {form && <Modal title={isNew ? 'Add a project role' : `Edit role ${form.key}`} wide onClose={() => setForm(null)}
      footer={<><button type="button" className="btn" onClick={() => setForm(null)}>Cancel</button><button type="submit" form="project-role-form" className="btn primary" disabled={busy}>Save role</button></>}>
      <form id="project-role-form" className="stack" onSubmit={e => { e.preventDefault(); save() }}>
        <div className="grid g2">
          <label className="field">Role key<input required disabled={!isNew} pattern="[a-z][a-z0-9_\-]{1,49}" placeholder="e.g. clip-reviewer" value={form.key} onChange={e => setForm({ ...form, key: e.target.value })} /></label>
          <label className="field">Name<input required maxLength={100} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label className="field">Ceiling (most it may do)<select disabled={!isNew} value={form.baseRole} onChange={e => setForm({ ...form, baseRole: e.target.value, permissions: ['VIEW'] })}>{ceilings.map(c => <option key={c}>{c}</option>)}</select></label>
          <label className="check" style={{ alignSelf: 'end' }}><input type="checkbox" checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} />Enabled</label>
        </div>
        <PermissionPicker c={v.catalogue} value={form.permissions} onChange={p => setForm({ ...form, permissions: p })} allowed={v.ceilings[form.baseRole] ?? []} />
      </form>
    </Modal>}
  </section>
}
