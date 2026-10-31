import { useState } from 'react'
import { api } from '../api'
import { ErrorBox, InfoTip, Loading, useAction, useApi } from '../ui'

// A company's waterfall of ad connectors: each break is filled by asking these sources in order.
// platform mode (Connectors page): the platform administrator manages everything.
// company mode (Ads page, behind feature switches): platform sources are read-only and their tag URLs hidden;
// with canAddOwn the company may add, pause and remove its own VAST tags, which run after the platform's.
type Source = { id: string | null; name: string; kind: 'DIRECT' | 'VAST'; tagUrl: string | null; timeoutMs: number; position: number; enabled: boolean; managedBy: 'PLATFORM' | 'TENANT' }
type TestAd = { title: string; advertiser: string; durationMs: number; mediaUrl: string; trackingUrls: number; creative: string }
type TagTest = { url: string; ms: number; ads: TestAd[]; error: string | null }

const MACROS = '[POD_DURATION] [CHANNEL_ID] [DEVICE_ID] [CLIENT_IP] [USER_AGENT] [COUNTRY] [CONTENT_GENRE] [CACHEBUSTER]'

export function AdSources({ base, mode, canAddOwn = false }: { base: string; mode: 'platform' | 'company'; canAddOwn?: boolean }) {
  const list = useApi<Source[]>(base)
  const { busy, run } = useAction()
  const [form, setForm] = useState({ name: '', tagUrl: '', timeoutMs: 1000 })
  const [test, setTest] = useState<TagTest | null>(null)
  const sources = list.data ?? []
  const platform = mode === 'platform'
  const canAdd = platform || canAddOwn
  const editable = (s: Source) => !!s.id && (platform || s.managedBy === 'TENANT')

  const save = (s: Source, patch: Partial<Source>) =>
    run(() => api(`${base}/${s.id}`, { method: 'PUT', body: { ...s, ...patch } }), 'Saved').then(list.reload)
  const move = (i: number, d: number) => {
    const ids = sources.map(s => s.id)
    if (ids.some(id => !id)) return
    ;[ids[i], ids[i + d]] = [ids[i + d], ids[i]]
    run(() => api(`${base}/order`, { body: { ids } }), 'Waterfall reordered').then(list.reload)
  }
  const add = () =>
    run(() => api(base, { body: { name: form.name, kind: 'VAST', tagUrl: form.tagUrl, timeoutMs: form.timeoutMs, enabled: true } }), 'Ad server added')
      .then(r => { if (r) { setForm({ name: '', tagUrl: '', timeoutMs: 1000 }); setTest(null); list.reload() } })
  const runTest = (prepare: boolean) =>
    run(() => api<TagTest>(`${base}/test`, { body: { tagUrl: form.tagUrl, timeoutMs: form.timeoutMs, prepare } })).then(r => r && setTest(r))

  return (
    <div className="card card-pad stack" style={{ gap: 14 }}>
      <div className="between">
        <div>
          <h3 style={{ margin: 0, fontSize: 16 }}>Ad demand waterfall</h3>
          <p className="faint small" style={{ margin: '4px 0 0' }}>
            Each break is filled by asking these sources top to bottom, each within its timeout. Whatever stays open plays slate.
            {!platform && ' Sources marked Platform are managed by your platform administrator.'}
          </p>
        </div>
        <InfoTip text="External ad servers (Google Ad Manager, SpringServe, FreeWheel, your own) are added by VAST tag. A creative seen for the first time is converted to 5-second segments and airs from the next break; use 'Test + prepare' to warm it up in advance." />
      </div>

      {list.error ? <ErrorBox error={list.error} retry={list.reload} /> : list.loading ? <Loading rows={2} /> : (
        <div className="stack" style={{ gap: 8 }}>
          {sources.map((s, i) => (
            <div key={s.id ?? 'default'} className="row" style={{ gap: 10, padding: '10px 12px', border: '1px solid var(--border)', borderRadius: 10, opacity: s.enabled ? 1 : 0.55 }}>
              <span className="badge">{i + 1}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <b>{s.name}</b> <span className={`badge ${s.kind === 'DIRECT' ? 'ok' : 'info'}`}>{s.kind === 'DIRECT' ? 'Direct-sold' : 'VAST ad server'}</span>
                {' '}<span className="badge">{s.managedBy === 'TENANT' ? (platform ? 'Company tag' : 'Your tag') : 'Platform'}</span>
                {!s.enabled && <span className="faint small"> · paused</span>}
                {s.tagUrl && <div className="faint small mono" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.tagUrl}</div>}
              </div>
              {s.kind === 'VAST' && <span className="faint small">{s.timeoutMs} ms</span>}
              {platform && s.id && (
                <>
                  <button className="btn ghost sm" disabled={busy || i === 0} onClick={() => move(i, -1)} aria-label="Ask earlier">↑</button>
                  <button className="btn ghost sm" disabled={busy || i === sources.length - 1} onClick={() => move(i, 1)} aria-label="Ask later">↓</button>
                </>
              )}
              {editable(s) && (
                <>
                  <button className="btn sm" disabled={busy} onClick={() => save(s, { enabled: !s.enabled })}>{s.enabled ? 'Pause' : 'Resume'}</button>
                  {s.kind === 'VAST' && (
                    <button className="btn danger sm" disabled={busy} onClick={() => run(() => api(`${base}/${s.id}`, { method: 'DELETE' }), 'Removed').then(list.reload)}>Remove</button>
                  )}
                </>
              )}
            </div>
          ))}
        </div>
      )}

      {canAdd && (
        <div className="stack" style={{ gap: 8, paddingTop: 6, borderTop: '1px solid var(--border)' }}>
          <b className="small">{platform ? 'Add an external ad server' : 'Add your own VAST ad tag'}</b>
          <div className="row-wrap" style={{ gap: 8 }}>
            <input className="input" style={{ flex: '1 1 160px' }} placeholder="Name, e.g. Google Ad Manager" value={form.name}
                   onChange={e => setForm({ ...form, name: e.target.value })} aria-label="Ad server name" />
            <input className="input" style={{ flex: '1 1 110px', maxWidth: 140 }} type="number" min={100} max={5000} step={100} value={form.timeoutMs}
                   onChange={e => setForm({ ...form, timeoutMs: +e.target.value })} aria-label="Timeout in milliseconds" />
          </div>
          <input type="password" autoComplete="new-password" className="input mono" placeholder="Write-only VAST tag URL" value={form.tagUrl}
                 onChange={e => setForm({ ...form, tagUrl: e.target.value })} aria-label="VAST tag URL" />
          <span className="faint small">Macros filled in per viewer and break: <span className="mono">{MACROS}</span></span>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn" disabled={busy || !form.tagUrl} onClick={() => runTest(false)}>Test tag</button>
            <button className="btn" disabled={busy || !form.tagUrl} onClick={() => runTest(true)}>Test + prepare creatives</button>
            <button className="btn primary" disabled={busy || !form.tagUrl || !form.name} onClick={add}>Add to waterfall</button>
          </div>
          {test && (
            <div className="callout small" style={{ display: 'block' }}>
              {test.error
                ? <div><b style={{ color: 'var(--bad)' }}>Failed after {test.ms} ms:</b> {test.error}</div>
                : <div><b>{test.ads.length} ad(s) in {test.ms} ms</b>{test.ads.length === 0 && ' (no fill: the tag works but returned nothing)'}</div>}
              {test.ads.map((a, i) => (
                <div key={i} style={{ marginTop: 6 }}>
                  {a.title} · {Math.round(a.durationMs / 1000)} s{a.advertiser && ` · ${a.advertiser}`} · {a.trackingUrls} tracking URLs · <i>{a.creative}</i>
                </div>
              ))}
              <div className="faint mono" style={{ marginTop: 6, wordBreak: 'break-all' }}>{test.url}</div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
