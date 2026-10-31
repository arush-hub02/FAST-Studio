import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../../shared/api'
import { useSession } from '../../app/App'
import { ago, Confirm, Icon, Loading, Modal, PageHead, Status, useAction, useApi } from '../../shared/ui'

type MailStatus = { mode: 'SMTP' | 'OUTBOX'; from: string; server?: string; mailerInstalled: boolean; outbox: string; appUrl: string }
type Template = {
  key: string; name: string; description: string; variables: string[]; required: string[]; subject: string | null; body: string
  customized: boolean; updatedAt?: string; defaultSubject: string | null; defaultBody: string
}
type Preview = { subject: string; text: string; html: string; problems: string[] }
type Message = { id: string; orgName?: string; template: string; toEmail: string; subject: string; status: string; detail?: string; attempts: number; createdAt: string; sentAt?: string; canRetry: boolean }

export default function Email() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'templates'
  const status = useApi<MailStatus>('/api/platform/email/status')
  if (status.error?.status === 403) return <div className="page"><div className="callout bad"><Icon name="shield" />Platform administrators only.</div></div>
  return (
    <div className="page">
      <PageHead title="Email" subtitle="Every email FAST Studio sends — invitations, password links, alerts — and the words in them. Change any template; the preview shows exactly what people will receive." />
      {status.data ? <Delivery s={status.data} onSent={() => setParams({ tab: 'sent' })} /> : <Loading rows={1} />}
      <div className="tabs">
        <button className={'tab' + (tab === 'templates' ? ' active' : '')} onClick={() => setParams({ tab: 'templates' })}>Templates</button>
        <button className={'tab' + (tab === 'sent' ? ' active' : '')} onClick={() => setParams({ tab: 'sent' })}>Sent emails</button>
      </div>
      {tab === 'templates' ? <Templates /> : <Sent />}
    </div>
  )
}

function Delivery({ s, onSent }: { s: MailStatus; onSent: () => void }) {
  const { me } = useSession()
  const [to, setTo] = useState(me?.email ?? '')
  const { busy, run } = useAction()
  return (
    <section className="card card-pad stack">
      {!s.mailerInstalled && <div className="callout bad"><Icon name="alert" /><div>The mailer is not installed, so emails can't be sent. Run <code>npm install</code> in the <code>mailer</code> folder (start.bat does this for you).</div></div>}
      {s.mode === 'SMTP'
        ? <div className="callout ok"><Icon name="check" /><div><b>Emails are delivered</b> through {s.server}, from <code>{s.from}</code>. Links in emails start with <code>{s.appUrl}</code>.</div></div>
        : <div className="callout warn"><Icon name="info" /><div><b>No mail server is set up, so nothing leaves this computer.</b> Each email is written as a file you can open in any mail app, in <code>{s.outbox}</code>, and listed under <i>Sent emails</i>. To send for real, set <code>FASTSTUDIO_SMTP_URL</code> (for a pilot: in <code>data-pilot/pilot.env</code>) and restart.</div></div>}
      <div className="row-wrap">
        <input type="email" value={to} onChange={e => setTo(e.target.value)} aria-label="Send a test email to" style={{ maxWidth: 320 }} />
        <button className="btn" disabled={busy || !to.includes('@')} onClick={() => run(() => api('/api/platform/email/test', { body: { to } }), 'Test email queued').then(r => r && onSent())}>
          <Icon name="mail" size={16} />Send a test email</button>
      </div>
    </section>
  )
}

function Templates() {
  const list = useApi<Template[]>('/api/platform/email/templates')
  const [selected, setSelected] = useState<string | null>(null)
  if (!list.data) return <Loading />
  const t = list.data.find(x => x.key === selected) ?? list.data[0]
  return (
    <div className="email-grid">
      <nav className="card tpl-list" aria-label="Email templates">
        {list.data.map(x => (
          <button key={x.key} className={'tpl-item' + (x.key === t.key ? ' active' : '')} onClick={() => setSelected(x.key)}>
            <span className="between"><b>{x.name}</b>{x.customized && <span className="badge info">Customized</span>}</span>
            <span className="faint small">{x.description}</span>
          </button>
        ))}
      </nav>
      <Editor key={t.key + (t.updatedAt ?? '')} t={t} onSaved={list.reload} />
    </div>
  )
}

function Editor({ t, onSaved }: { t: Template; onSaved: () => void }) {
  const [subject, setSubject] = useState(t.subject ?? '')
  const [body, setBody] = useState(t.body)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [resetting, setResetting] = useState(false)
  const last = useRef<'subject' | 'body'>('body')
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  const subjectRef = useRef<HTMLInputElement>(null)
  const { busy, run } = useAction()
  const hasSubject = t.subject !== null
  const dirty = subject !== (t.subject ?? '') || body !== t.body

  useEffect(() => {
    const h = setTimeout(() => api<Preview>(`/api/platform/email/templates/${t.key}/preview`, { body: { subject: hasSubject ? subject : null, body } })
      .then(setPreview).catch(() => {}), 300)
    return () => clearTimeout(h)
  }, [t.key, subject, body, hasSubject])

  function insert(v: string) {
    const token = `{{${v}}}`
    const el = last.current === 'subject' && hasSubject ? subjectRef.current : bodyRef.current
    if (!el) return
    const [a, b] = [el.selectionStart ?? el.value.length, el.selectionEnd ?? el.value.length]
    const next = el.value.slice(0, a) + token + el.value.slice(b)
    if (el === subjectRef.current) setSubject(next); else setBody(next)
    requestAnimationFrame(() => { el.focus(); el.setSelectionRange(a + token.length, a + token.length) })
  }

  return (
    <section className="card card-pad stack">
      <div className="between"><div><h2>{t.name}</h2><p className="faint">{t.description}</p></div>
        {t.customized ? <span className="badge info">Customized</span> : <span className="badge">Default wording</span>}</div>
      {hasSubject && <label className="field">Subject
        <input ref={subjectRef} value={subject} onChange={e => setSubject(e.target.value)} onFocus={() => (last.current = 'subject')} /></label>}
      <label className="field">Message
        <span className="hint">Plain text. A blank line starts a new paragraph. A link on a line of its own becomes a button.</span>
        <textarea ref={bodyRef} className="mono" rows={13} value={body} onChange={e => setBody(e.target.value)} onFocus={() => (last.current = 'body')} /></label>
      <div className="stack" style={{ gap: 6 }}>
        <span className="small"><b>Fill-in values</b> — click to insert. Each one is replaced with the real value for every person.</span>
        <div className="row-wrap">{t.variables.map(v => (
          <button key={v} type="button" className={'chip var-chip' + (t.required.includes(v) ? ' required' : '')} onClick={() => insert(v)}
            title={t.required.includes(v) ? 'Required: the email is useless without it' : 'Insert'}>{`{{${v}}}`}{t.required.includes(v) && ' *'}</button>))}</div>
      </div>
      {preview && preview.problems.length > 0 && <div className="callout bad" role="alert"><Icon name="alert" /><div>{preview.problems.map(p => <div key={p}>{p}</div>)}</div></div>}
      <div className="row-wrap">
        <button className="btn primary" disabled={busy || !dirty || !!preview?.problems.length}
          onClick={() => run(() => api(`/api/platform/email/templates/${t.key}`, { method: 'PUT', body: { subject: hasSubject ? subject : null, body } }), 'Template saved').then(r => r && onSaved())}>Save template</button>
        {dirty && <button className="btn" onClick={() => { setSubject(t.subject ?? ''); setBody(t.body) }}>Undo changes</button>}
        {t.customized && <button className="btn ghost" onClick={() => setResetting(true)}>Back to default wording</button>}
      </div>
      <div className="email-preview">
        <div className="small faint">Preview with example values{hasSubject && preview && <> · Subject: <b>{preview.subject}</b></>}</div>
        {preview ? <iframe title="Email preview" sandbox="" srcDoc={preview.html} /> : <Loading />}
      </div>
      {resetting && <Confirm title="Go back to the default wording?" confirmLabel="Use default" text="Your changes to this template are removed. Emails sent from now on use the built-in wording."
        onClose={() => setResetting(false)} onConfirm={() => run(() => api(`/api/platform/email/templates/${t.key}`, { method: 'DELETE' }), 'Default wording restored').then(onSaved)} />}
    </section>
  )
}

function Sent() {
  const list = useApi<Message[]>('/api/platform/email/messages', [], 5000)
  const [open, setOpen] = useState<Message | null>(null)
  if (!list.data) return <Loading />
  if (!list.data.length) return <div className="card card-pad faint">No emails yet. Invite someone, create an organization or send a test email.</div>
  return (
    <section className="card">
      <table className="table"><thead><tr><th>When</th><th>To</th><th>Email</th><th>Organization</th><th>Result</th></tr></thead>
        <tbody>{list.data.map(m => (
          <tr key={m.id} className="clickable" onClick={() => setOpen(m)}>
            <td className="small">{ago(m.createdAt)}</td><td>{m.toEmail}</td>
            <td><b>{m.subject}</b><div className="faint small">{m.template.replace(/_/g, ' ')}</div></td>
            <td className="small">{m.orgName ?? <span className="faint">Platform</span>}</td>
            <td><Status value={m.status} />{m.status === 'FAILED' && <div className="faint small" style={{ maxWidth: 260 }}>{m.detail}</div>}</td>
          </tr>))}</tbody></table>
      {open && <MessageView m={open} onClose={() => setOpen(null)} onRetried={list.reload} />}
    </section>
  )
}

function MessageView({ m, onClose, onRetried }: { m: Message; onClose: () => void; onRetried: () => void }) {
  const body = useApi<{ subject: string; html: string }>(`/api/platform/email/messages/${m.id}`)
  const { busy, run } = useAction()
  return (
    <Modal wide title={m.subject} subtitle={`To ${m.toEmail} · ${ago(m.createdAt)}`} onClose={onClose}
      footer={<>{m.canRetry && <button className="btn primary" disabled={busy} onClick={() => run(() => api(`/api/platform/email/messages/${m.id}/retry`, { method: 'POST' }), 'Sending again').then(() => { onRetried(); onClose() })}>Send again</button>}
        <button className="btn" onClick={onClose}>Close</button></>}>
      <div className="row-wrap"><Status value={m.status} /><span className="small faint">{m.status === 'SENT' ? 'Accepted by the mail server: ' : m.status === 'SAVED' ? 'Saved as: ' : ''}{m.detail}</span></div>
      <div className="callout"><Icon name="shield" /><div className="small">This is the logged copy. One-time passwords and reset links are hidden here; the person received the real ones.</div></div>
      <div className="email-preview">{body.data ? <iframe title="Sent email" sandbox="" srcDoc={body.data.html} /> : <Loading />}</div>
    </Modal>
  )
}
