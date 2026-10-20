import { useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, type Me } from '../api'
import { useSession } from '../app'
import { Icon, Modal, useToast } from '../ui'

/** Same rules as the server (Accounts.weakness); the server is the authority. */
function hint(pw: string) {
  if (pw.length < 10) return 'At least 10 characters'
  if (new Set(pw).size < 4) return 'Too easy to guess'
  return null
}

function NewPasswordFields({ value, onChange, confirm, onConfirm }: { value: string; onChange: (v: string) => void; confirm: string; onConfirm: (v: string) => void }) {
  const h = value ? hint(value) : null
  return <>
    <label className="field">New password<span className="hint">At least 10 characters. A short sentence works well.</span>
      <input type="password" autoComplete="new-password" value={value} onChange={e => onChange(e.target.value)} required /></label>
    {h && <span className="faint">{h}</span>}
    <label className="field">Repeat new password
      <input type="password" autoComplete="new-password" value={confirm} onChange={e => onConfirm(e.target.value)} required /></label>
    {confirm && confirm !== value && <span className="faint" style={{ color: 'var(--bad)' }}>The two passwords don't match yet.</span>}
  </>
}

function ChangePasswordForm({ onDone, submitLabel }: { onDone: (me: Me) => void; submitLabel: string }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function submit(e: FormEvent) {
    e.preventDefault()
    if (next !== confirm) { setError("The new passwords don't match."); return }
    setBusy(true); setError(null)
    try { onDone(await api<Me>('/api/auth/password', { body: { currentPassword: current, newPassword: next } })) }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return (
    <form className="stack" onSubmit={submit}>
      <label className="field">Current password<span className="hint">For a new account: the temporary password you were given</span>
        <input type="password" autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)} required autoFocus /></label>
      <NewPasswordFields value={next} onChange={setNext} confirm={confirm} onConfirm={setConfirm} />
      {error && <div className="callout bad" role="alert"><Icon name="alert" />{error}</div>}
      <button className="btn primary" disabled={busy || !current || !next || next !== confirm}>{busy ? <span className="spinner" /> : submitLabel}</button>
    </form>
  )
}

/** Shown instead of the app until a temporary password has been replaced (the API enforces this too). */
export function ForcedPasswordChange() {
  const { me, refresh, logout } = useSession()
  return (
    <div className="login-form" style={{ minHeight: '100%' }}>
      <div className="card card-pad stack" style={{ width: 'min(440px, 100%)' }}>
        <div className="brand" style={{ padding: 0, color: 'var(--text)' }}><div className="brand-logo"><Icon name="shield" size={18} /></div>Choose your password</div>
        <p className="muted">Welcome, {me?.name}. You signed in with a temporary password. Choose your own before continuing — nobody else will know it.</p>
        <ChangePasswordForm submitLabel="Save and continue" onDone={() => refresh()} />
        <button className="btn ghost" onClick={logout}>Sign out</button>
      </div>
    </div>
  )
}

export function ChangePasswordModal({ onClose }: { onClose: () => void }) {
  const toast = useToast()
  return (
    <Modal title="Change password" onClose={onClose}>
      <ChangePasswordForm submitLabel="Change password" onDone={() => { toast('Password changed', 'ok'); onClose() }} />
    </Modal>
  )
}

/** Public page opened from an admin-issued reset link (/reset-password?token=…). */
export function ResetPassword() {
  const [params] = useSearchParams()
  const nav = useNavigate()
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const token = params.get('token') ?? ''
  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null)
    try { await api('/api/auth/reset', { body: { token, newPassword: next } }); setDone(true) }
    catch (err) { setError((err as Error).message) }
    finally { setBusy(false) }
  }
  return (
    <div className="login-form" style={{ minHeight: '100%' }}>
      <div className="card card-pad stack" style={{ width: 'min(440px, 100%)' }}>
        <div className="brand" style={{ padding: 0, color: 'var(--text)' }}><div className="brand-logo"><Icon name="shield" size={18} /></div>Set a new password</div>
        {done ? <>
          <div className="callout ok"><Icon name="check" />Your password is set. Sign in with it now.</div>
          <button className="btn primary" onClick={() => nav('/login')}>Go to sign in</button>
        </> : !token ? (
          <div className="callout bad"><Icon name="alert" />This link is incomplete. Ask your administrator for a new reset link.</div>
        ) : (
          <form className="stack" onSubmit={submit}>
            <p className="muted">This link works once and expires 24 hours after it was created.</p>
            <NewPasswordFields value={next} onChange={setNext} confirm={confirm} onConfirm={setConfirm} />
            {error && <div className="callout bad" role="alert"><Icon name="alert" />{error}</div>}
            <button className="btn primary" disabled={busy || !next || next !== confirm}>{busy ? <span className="spinner" /> : 'Set password'}</button>
          </form>
        )}
      </div>
    </div>
  )
}

/** Shows an admin-issued reset link with a copy button. */
export function ResetLinkModal({ who, path, note, onClose }: { who: string; path: string; note: string; onClose: () => void }) {
  const url = window.location.origin + path
  const toast = useToast()
  return (
    <Modal title={`Reset link for ${who}`} onClose={onClose} footer={<button className="btn primary" onClick={onClose}>Done</button>}>
      <div className="callout warn"><Icon name="shield" /><div className="small">{note}</div></div>
      <div className="row"><input className="mono" readOnly value={url} onFocus={e => e.target.select()} aria-label="Reset link" />
        <button className="btn" onClick={() => navigator.clipboard.writeText(url).then(() => toast('Link copied', 'ok'))}>Copy</button></div>
    </Modal>
  )
}
