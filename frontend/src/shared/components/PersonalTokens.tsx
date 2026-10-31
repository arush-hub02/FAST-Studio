import { useState } from 'react'
import { useSession } from '../../app/App'
import { api } from '../api'
import { Confirm, ErrorBox, Icon, Loading, fmtDate, useAction, useApi, useToast } from '../ui'

/** As listed by GET /api/auth/personal-tokens (the token itself is never listed). Null org = platform scope. */
type PersonalToken = {
  id: string; name: string; tokenHint: string; orgId: string | null; orgSlug: string | null; orgName: string | null
  roleKey: string; createdAt: string; expiresAt: string | null; lastUsedAt: string | null
}

/** Built-in roles a token may be limited to, highest first; rank decides which are at or below your own role. */
const CUSTOMER_ROLES = [
  { key: 'OWNER', label: 'Owner (everything you can do)', rank: 4 },
  { key: 'ADMIN', label: 'Admin', rank: 3 },
  { key: 'CONTENT_MANAGER', label: 'Content manager', rank: 1 },
  { key: 'CHANNEL_OPERATOR', label: 'Channel operator', rank: 1 },
  { key: 'AD_OPS', label: 'Ad operations', rank: 1 },
  { key: 'ANALYST', label: 'Read-only (analyst)', rank: 0 },
]
const PARTNER_ROLES = [
  { key: 'PARTNER_ADMIN', label: 'Partner admin', rank: 4 },
  { key: 'PARTNER_VIEWER', label: 'Read-only (partner viewer)', rank: 0 },
]
const ROLE_LABEL: Record<string, string> = Object.fromEntries([...CUSTOMER_ROLES, ...PARTNER_ROLES].map(r => [r.key, r.label]))
ROLE_LABEL.PLATFORM_ADMIN = 'Platform administrator'

const EXPIRY = [
  { value: '7', label: '7 days' },
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
  { value: '365', label: '1 year' },
  { value: 'custom', label: 'Custom number of days' },
  { value: 'never', label: 'No expiry' },
]

/** Roles a token may have in a scope: yours, or a lower one (roles of equal rank give different rights, so not those). */
function rolesFor(kind: string, myRole: string) {
  const list = kind === 'PARTNER' ? PARTNER_ROLES : CUSTOMER_ROLES
  const mine = list.find(r => r.key === myRole)
  if (!mine) return list.filter(r => r.rank === 0)
  return list.filter(r => r.key === myRole || r.rank < mine.rank)
}

/**
 * Personal access tokens, on everyone's profile: long-lived credentials for scripts and AI assistants (MCP clients).
 * Each works in one scope (an organization you belong to, or platform administration), as at most the role you choose
 * and never more than you may do yourself; expiry is chosen, or none.
 */
export function PersonalTokens() {
  const { me } = useSession()
  const tokens = useApi<PersonalToken[]>('/api/auth/personal-tokens')
  const { busy, run } = useAction()
  const toast = useToast()

  const scopes = [
    ...(me?.memberships ?? []).map(m => ({ value: m.slug, label: `${m.name} (${m.kind === 'PARTNER' ? 'partner' : 'organization'})`, kind: m.kind as string, role: m.role })),
    ...(me?.platformAdmin ? [{ value: 'platform', label: 'Platform administration', kind: 'PLATFORM', role: 'PLATFORM_ADMIN' }] : []),
  ]
  const [name, setName] = useState('')
  const [scope, setScope] = useState(scopes[0]?.value ?? '')
  const current = scopes.find(s => s.value === scope)
  const roles = !current ? [] : current.kind === 'PLATFORM' ? [{ key: 'PLATFORM_ADMIN', label: ROLE_LABEL.PLATFORM_ADMIN, rank: 9 }] : rolesFor(current.kind, current.role)
  const [role, setRole] = useState('')
  const chosenRole = roles.find(r => r.key === role) ? role : roles[roles.length - 1]?.key ?? ''   // default: the most limited
  const [expiry, setExpiry] = useState('90')
  const [customDays, setCustomDays] = useState(180)
  const [created, setCreated] = useState<{ token: string; name: string } | null>(null)
  const [revoking, setRevoking] = useState<PersonalToken | null>(null)

  async function create(e: React.FormEvent) {
    e.preventDefault()
    const expiresInDays = expiry === 'never' ? null : expiry === 'custom' ? customDays : Number(expiry)
    const r = await run(() => api<{ token: string; details: PersonalToken }>('/api/auth/personal-tokens',
      { body: { name: name.trim(), org: scope, role: chosenRole, expiresInDays } }), 'Token created')
    if (r) {
      setCreated({ token: r.token, name: r.details.name })
      setName('')
      tokens.reload()
    }
  }

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div>
        <h4>Personal access tokens</h4>
        <p className="faint">
          Let a script or an AI assistant (an MCP client) work as you, without your password. Each token works in one place,
          as at most the role you choose, and never more than you may do yourself. It can't change your password or make
          other tokens. Send it as <code>Authorization: Bearer fsp_…</code>.
        </p>
      </div>

      {created && (
        <div className="callout ok stack" style={{ gap: 8 }}>
          <div><b>Copy “{created.name}” now.</b> It is shown only this once; we keep only a fingerprint of it.</div>
          <div className="row">
            <input className="mono" readOnly value={created.token} onFocus={e => e.target.select()} aria-label="New token" />
            <button type="button" className="btn" onClick={() => navigator.clipboard.writeText(created.token).then(() => toast('Token copied', 'ok'))}>Copy</button>
          </div>
          <button type="button" className="btn ghost sm" style={{ alignSelf: 'flex-start' }} onClick={() => setCreated(null)}>I have stored it</button>
        </div>
      )}

      <form className="card card-pad stack" style={{ gap: 12 }} onSubmit={create}>
        <div className="settings-section-title"><Icon name="key" size={16} /> New token</div>
        {scopes.length === 0 ? <p className="faint">You don't belong to an organization yet, so there is nothing a token could do.</p> : <>
          <label className="field">Name<span className="hint">What uses it, e.g. “Claude desktop (read-only)”</span>
            <input required maxLength={80} value={name} onChange={e => setName(e.target.value)} placeholder="Token name" /></label>
          <div className="grid g2">
            <label className="field">Works in
              <select value={scope} onChange={e => { setScope(e.target.value); setRole('') }}>
                {scopes.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
              </select></label>
            <label className="field">Most it may do
              <select value={chosenRole} onChange={e => setRole(e.target.value)}>
                {roles.map(r => <option key={r.key} value={r.key}>{r.label}</option>)}
              </select></label>
          </div>
          <div className="grid g2">
            <label className="field">Expires
              <select value={expiry} onChange={e => setExpiry(e.target.value)}>
                {EXPIRY.map(x => <option key={x.value} value={x.value}>{x.label}</option>)}
              </select></label>
            {expiry === 'custom' && <label className="field">Days<input type="number" min={1} max={3650} required value={customDays}
              onChange={e => setCustomDays(Math.max(1, Math.min(3650, Number(e.target.value) || 1)))} /></label>}
          </div>
          {expiry === 'never' && <div className="callout warn"><Icon name="shield" /><div className="small">
            A token without expiry works until you revoke it. Prefer an expiry, and keep it as private as a password.</div></div>}
          <button className="btn primary" style={{ alignSelf: 'flex-start' }} disabled={busy || !name.trim() || !chosenRole}>Create token</button>
        </>}
      </form>

      <div className="stack" style={{ gap: 8 }}>
        <div className="settings-section-title"><Icon name="list" size={16} /> Your tokens</div>
        {tokens.error ? <ErrorBox error={tokens.error} retry={tokens.reload} /> : !tokens.data ? <Loading rows={2} /> :
          tokens.data.length === 0 ? <p className="faint">No tokens yet.</p> :
            <table className="table">
              <thead><tr><th>Name</th><th>Works in</th><th>Most it may do</th><th>Expires</th><th>Last used</th><th /></tr></thead>
              <tbody>{tokens.data.map(t => (
                <tr key={t.id}>
                  <td><b>{t.name}</b><div className="faint mono small">{t.tokenHint}…</div></td>
                  <td>{t.orgName ?? 'Platform administration'}</td>
                  <td>{ROLE_LABEL[t.roleKey] ?? t.roleKey}</td>
                  <td>{t.expiresAt ? fmtDate(t.expiresAt) : <span className="badge warn">Never</span>}</td>
                  <td>{t.lastUsedAt ? fmtDate(t.lastUsedAt) : 'Never'}</td>
                  <td><button type="button" className="btn sm danger" onClick={() => setRevoking(t)}>Revoke</button></td>
                </tr>))}
              </tbody>
            </table>}
      </div>

      {revoking && <Confirm title={`Revoke “${revoking.name}”?`} danger confirmLabel="Revoke"
        text="Anything using this token stops working at once. This can't be undone."
        onClose={() => setRevoking(null)}
        onConfirm={() => run(() => api(`/api/auth/personal-tokens/${revoking.id}`, { method: 'DELETE' }), 'Token revoked')
          .then(() => { setRevoking(null); tokens.reload() })} />}
    </div>
  )
}
