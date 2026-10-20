import { useState, type FormEvent } from 'react'
import { api, type Me } from '../api'
import { useSession } from '../app'
import { Icon, initials, useApi } from '../ui'

type DemoAccounts = { enabled: boolean; password?: string; accounts: { email: string; name: string; role: string; description: string }[] }
const COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#64748b', '#7c3aed']

export default function Login() {
  const { refresh } = useSession()
  const demo = useApi<DemoAccounts>('/api/auth/demo-accounts')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [forgot, setForgot] = useState<'closed' | 'open' | 'sent'>('closed')
  const [forgotMsg, setForgotMsg] = useState('')

  async function signIn(e?: FormEvent, as?: { email: string; password: string }) {
    e?.preventDefault()
    const creds = as ?? { email, password }
    if (!creds.email || !creds.password) { setError('Enter your email and password.'); return }
    setBusy(true); setError(null)
    try {
      await api<Me>('/api/auth/login', { body: creds })
      await refresh()
    } catch (err) {
      setError((err as Error).message)
    } finally { setBusy(false) }
  }

  return (
    <div className="login">
      <section className="login-hero">
        <div className="brand" style={{ padding: 0 }}><div className="brand-logo"><Icon name="play" size={18} /></div>FAST Studio</div>
        <div className="stack" style={{ gap: 18 }}>
          <h1>Turn your video library into live TV channels — with ads that pay for them.</h1>
          <p style={{ maxWidth: 460, color: '#c7d2fe', fontSize: 16 }}>
            Upload videos, set where they may play, build a 24/7 schedule, and watch it live with real ad breaks.
            Everything a FAST channel needs, in plain language.
          </p>
          <div className="row-wrap" style={{ gap: 10 }}>
            {['Schedule', 'Rights & geo-blocking', 'SSAI & SGAI ads', 'SCTE-35 markers', 'EPG'].map(t =>
              <span key={t} className="badge" style={{ background: 'rgba(255,255,255,.1)', color: '#e0e7ff' }}>{t}</span>)}
          </div>
        </div>
        <p style={{ color: '#818cf8', fontSize: 12.5 }}>{demo.data?.enabled ? 'Prototype for demonstration. Companies, people, ads and numbers are fictional.' : ''}</p>
      </section>
      <section className="login-form">
        <div style={{ width: 'min(420px, 100%)' }} className="stack">
          <h2 style={{ fontSize: 22 }}>Sign in</h2>
          <form className="stack" onSubmit={signIn} noValidate>
            <label className="field">Email
              <input type="email" autoComplete="username" value={email} onChange={e => setEmail(e.target.value)} required />
            </label>
            <label className="field">Password
              <div className="row">
                <input type={show ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required />
                <button type="button" className="btn" onClick={() => setShow(s => !s)} aria-label={show ? 'Hide password' : 'Show password'}>{show ? 'Hide' : 'Show'}</button>
              </div>
            </label>
            {error && <div className="callout bad" role="alert"><Icon name="alert" />{error}</div>}
            <button className="btn primary lg" disabled={busy}>{busy ? <span className="spinner" /> : 'Sign in'}</button>
          </form>
          {forgot === 'closed' ? <button className="btn ghost" style={{ alignSelf: 'flex-start' }} onClick={() => setForgot('open')}>Forgot your password?</button>
            : forgot === 'sent' ? <div className="callout ok" role="status"><Icon name="mail" />{forgotMsg}</div>
            : <form className="stack card card-pad" onSubmit={async e => {
                e.preventDefault(); setBusy(true); setError(null)
                try { setForgotMsg((await api<{ message: string }>('/api/auth/forgot', { body: { email } })).message); setForgot('sent') }
                catch (err) { setError((err as Error).message) } finally { setBusy(false) }
              }}>
                <b>Reset your password</b>
                <span className="faint">Enter your email above, then we'll email you a link to choose a new password.</span>
                <button className="btn primary" disabled={busy || !email.includes('@')}>Email me a reset link</button>
              </form>}
          {demo.data?.enabled && (
            <div className="stack" style={{ marginTop: 18 }}>
              <div className="between">
                <h3>Demo accounts</h3>
                <span className="badge sim">Development only</span>
              </div>
              <p className="faint">One click signs you in. Try different roles to see what each person can and can't do.</p>
              {demo.data.accounts.map((a, i) => (
                <button key={a.email} className="acct" disabled={busy} onClick={() => signIn(undefined, { email: a.email, password: demo.data!.password! })}>
                  <div className="avatar" style={{ background: COLORS[i % COLORS.length] }}>{initials(a.name)}</div>
                  <div className="grow">
                    <div className="between"><b>{a.name}</b><span className="faint">{a.role}</span></div>
                    <div className="faint">{a.description}</div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </section>
    </div>
  )
}
