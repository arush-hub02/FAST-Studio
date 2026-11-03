import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { Confirm, Icon, Loading, useAction, useApi } from '../ui'

type Template = {
  key: string; name: string; description: string; variables: string[]; required: string[]; subject: string | null; body: string
  customized: boolean; updatedAt?: string; defaultSubject: string | null; defaultBody: string
}
type Preview = { subject: string; text: string; html: string; problems: string[] }

/**
 * Email templates with a live preview. base: the templates API (platform or a project). For a project, "customized" means
 * its own wording, and going back restores the platform's.
 */
export function EmailTemplates({ base, editable = true, inherited = 'Default wording', backLabel = 'Back to default wording' }:
  { base: string; editable?: boolean; inherited?: string; backLabel?: string }) {
  const list = useApi<Template[]>(base)
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
      <Editor key={t.key + (t.updatedAt ?? '')} t={t} onSaved={list.reload} base={base} editable={editable} inherited={inherited} backLabel={backLabel} />
    </div>
  )
}

function Editor({ t, onSaved, base, editable, inherited, backLabel }: { t: Template; onSaved: () => void; base: string; editable: boolean; inherited: string; backLabel: string }) {
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
    const h = setTimeout(() => api<Preview>(`${base}/${t.key}/preview`, { body: { subject: hasSubject ? subject : null, body } })
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
        {t.customized ? <span className="badge info">Customized</span> : <span className="badge">{inherited}</span>}</div>
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
      {editable && <div className="row-wrap">
        <button className="btn primary" disabled={busy || !dirty || !!preview?.problems.length}
          onClick={() => run(() => api(`${base}/${t.key}`, { method: 'PUT', body: { subject: hasSubject ? subject : null, body } }), 'Template saved').then(r => r && onSaved())}>Save template</button>
        {dirty && <button className="btn" onClick={() => { setSubject(t.subject ?? ''); setBody(t.body) }}>Undo changes</button>}
        {t.customized && <button className="btn ghost" onClick={() => setResetting(true)}>{backLabel}</button>}
      </div>}
      <div className="email-preview">
        <div className="small faint">Preview with example values{hasSubject && preview && <> · Subject: <b>{preview.subject}</b></>}</div>
        {preview ? <iframe title="Email preview" sandbox="" srcDoc={preview.html} /> : <Loading />}
      </div>
      {resetting && <Confirm title={`${backLabel}?`} confirmLabel="Go back" text={`Your changes to this template are removed. Emails sent from now on use the ${inherited.toLowerCase()}.`}
        onClose={() => setResetting(false)} onConfirm={() => run(() => api(`${base}/${t.key}`, { method: 'DELETE' }), 'Wording restored').then(onSaved)} />}
    </section>
  )
}

