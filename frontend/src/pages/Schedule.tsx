import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError, type Channel, type ChannelView, type Entry, type Issue, type Version } from '../api'
import { useOrg } from '../app'
import { ChannelLogo, Confirm, Empty, ErrorBox, fmtDayTime, fmtDur, fmtTime, Icon, InfoTip, Loading, Modal, PageHead, Status, useApi, useToast } from '../ui'

type AssetRef = { id: string; title: string; kind: string; color: string; mediaKey?: string; durationMs: number; status: string }
type View = {
  channel: Channel; draft: Version; draftEntries: Entry[]; published?: Version; publishedEntries: Entry[]; issues: Issue[]; versions: Version[]
  assets: Record<string, AssetRef>; unpublishedChanges: boolean; now: string; from: string; to: string
}
const ZOOM = { '1h': 1100, '3h': 360, '6h': 190, '24h': 48 } as const

export default function Schedule() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const channelId = params.get('channel') ?? channels.data?.[0]?.channel.id ?? null
  const view = useApi<View>(channelId ? `/api/orgs/${org.slug}/channels/${channelId}/schedule` : null, [], 30000)
  const [mode, setMode] = useState<'draft' | 'published'>('draft')
  const [zoom, setZoom] = useState<keyof typeof ZOOM>('1h')
  const [selected, setSelected] = useState<string | null>(null)
  const [modal, setModal] = useState<null | 'autofill' | 'program' | 'break' | 'publish' | 'clear'>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  useEffect(() => { setSelected(null) }, [channelId, mode])

  const v = view.data
  const editable = org.can('SCHEDULE_EDIT') && mode === 'draft'
  const entries = v ? (mode === 'draft' ? v.draftEntries : v.publishedEntries) : []
  const errors = v?.issues.filter(i => i.severity === 'ERROR') ?? []
  const warnings = v?.issues.filter(i => i.severity !== 'ERROR') ?? []
  const errorIds = new Set(errors.map(i => i.entryId).filter(Boolean))

  /** Every draft edit sends the revision we loaded; a 412 means someone else edited first. */
  async function edit(path: string, body: object | null, method = 'POST') {
    if (!v) return
    setBusy(true)
    try {
      const sep = path.includes('?') ? '&' : '?'
      const r = await api<{ notices?: string[]; versionNumber?: number; warnings?: Issue[] }>(
        `/api/orgs/${org.slug}/channels/${v.channel.id}/schedule/${path}${method === 'DELETE' ? `${sep}expectedRevision=${v.draft.revision}` : ''}`,
        { method, body: body === null ? undefined : { expectedRevision: v.draft.revision, ...body } })
      r?.notices?.forEach(n => toast(n, 'ok'))
      if (r?.versionNumber) toast(`Version ${r.versionNumber} is now on air`, 'ok')
      setModal(null)
      return r
    } catch (e) {
      const err = e as ApiError
      toast(err.message, 'bad')
      if (err.code === 'SCHEDULE_INVALID') setMode('draft')
    } finally {
      setBusy(false)
      view.reload()
    }
  }

  if (channels.error) return <div className="page"><ErrorBox error={channels.error} /></div>
  if (channels.data && channels.data.length === 0) return (
    <div className="page"><PageHead title="Schedule" /><div className="card"><Empty icon="calendar" title="Create a channel first" action={<Link className="btn primary" to={`${org.base}/channels`}>Go to Channels</Link>} /></div></div>
  )
  const sel = entries.find(e => e.id === selected)
  return (
    <div className="page">
      <PageHead title="Schedule" tip="The schedule decides what plays at every second. You edit a draft; viewers only ever see the published version."
        subtitle="Build the day in the draft, fix anything flagged in red, then publish. Publishing creates a new numbered version — older versions are kept for rollback." />

      <div className="row-wrap">
        {channels.data?.map(c => (
          <button key={c.channel.id} className={'btn' + (c.channel.id === channelId ? ' primary' : '')} onClick={() => setParams({ channel: c.channel.id })}>
            <span style={{ width: 10, height: 10, borderRadius: 3, background: c.channel.color }} />{c.channel.name}
          </button>
        ))}
      </div>

      {view.error ? <ErrorBox error={view.error} retry={view.reload} /> : !v ? <Loading rows={5} /> : (
        <div className="split">
          <div className="stack" style={{ gap: 16 }}>
            <section className="card">
              <div className="card-head">
                <div className="row">
                  <ChannelLogo name={v.channel.name} color={v.channel.color} size={36} />
                  <div><div className="row"><h2>{v.channel.name}</h2><Status value={v.channel.status} /></div>
                    <p>{v.published ? `On air: version ${v.published.number}` : 'Nothing published yet'} · times in {v.channel.timezone}</p></div>
                </div>
                <div className="seg">
                  <button className={mode === 'draft' ? 'active' : ''} onClick={() => setMode('draft')}>Draft (editing)</button>
                  <button className={mode === 'published' ? 'active' : ''} onClick={() => setMode('published')} disabled={!v.published}>On air{v.published ? ` (v${v.published.number})` : ''}</button>
                </div>
              </div>
              {mode === 'draft' && (
                <div className="card-pad row-wrap" style={{ borderBottom: '1px solid var(--border)' }}>
                  {editable && <>
                    <button className="btn" disabled={busy} onClick={() => setModal('autofill')}><Icon name="wand" size={16} />Auto-fill</button>
                    <button className="btn" disabled={busy} onClick={() => setModal('program')}><Icon name="plus" size={16} />Add programme</button>
                    <button className="btn" disabled={busy} onClick={() => setModal('break')}><Icon name="megaphone" size={16} />Add ad break</button>
                    <button className="btn" disabled={busy} onClick={() => edit('close-gaps', {})}>Close gaps</button>
                    <button className="btn ghost danger" disabled={busy} onClick={() => setModal('clear')}>Clear upcoming</button>
                  </>}
                  <div className="grow" />
                  {v.unpublishedChanges ? <span className="badge warn">Unpublished changes</span> : v.published && <span className="badge ok">Draft matches what's on air</span>}
                  {org.can('SCHEDULE_PUBLISH') && <button className="btn primary" disabled={busy} onClick={() => setModal('publish')}>
                    <Icon name="rocket" size={16} />Publish{errors.length ? ` (${errors.length} to fix)` : ''}</button>}
                </div>
              )}
              {mode === 'published' && <div className="card-pad"><div className="callout ok"><Icon name="check" />This is exactly what viewers get. It can't be edited — change the draft and publish a new version.</div></div>}
              <div className="card-pad stack">
                <div className="between">
                  <div className="row small">
                    <span className="row" style={{ gap: 4 }}><span style={{ width: 12, height: 12, borderRadius: 3, background: '#6366f1' }} />Programme</span>
                    <span className="row" style={{ gap: 4 }}><span style={{ width: 12, height: 12, borderRadius: 3, background: '#f59e0b' }} />Our ad break</span>
                    <span className="row" style={{ gap: 4 }}><span style={{ width: 12, height: 12, borderRadius: 3, background: '#94a3b8' }} />Platform's ad break</span>
                    <InfoTip text="Ad breaks are split between you and the distribution platform (an 'inventory split'). Yours are filled by your ads; the platform fills its own. The SCTE-35 marker on each break says whose it is." />
                  </div>
                  <div className="seg">{(Object.keys(ZOOM) as (keyof typeof ZOOM)[]).map(z => <button key={z} className={zoom === z ? 'active' : ''} onClick={() => setZoom(z)}>{z}</button>)}</div>
                </div>
                <Timeline entries={entries} assets={v.assets} from={v.from} to={v.to} now={v.now} pxPerHour={ZOOM[zoom]} tz={v.channel.timezone}
                  selected={selected} onSelect={setSelected} errorIds={errorIds} />
                {sel && <Selected e={sel} a={sel.assetId ? v.assets[sel.assetId] : undefined} tz={v.channel.timezone} editable={editable} busy={busy}
                  issues={v.issues.filter(i => i.entryId === sel.id)} onDelete={() => { edit(`entries/${sel.id}`, null, 'DELETE'); setSelected(null) }} onClose={() => setSelected(null)} />}
              </div>
            </section>
            <EntryList entries={entries} assets={v.assets} tz={v.channel.timezone} now={v.now} errorIds={errorIds} onSelect={setSelected} selected={selected} />
          </div>

          <div className="stack" style={{ gap: 16 }}>
            <section className="card">
              <div className="card-head"><div><h2>Check before publishing</h2><p>Plain-language problems in the draft</p></div>
                {errors.length === 0 ? <span className="badge ok"><Icon name="check" size={12} />Ready</span> : <span className="badge bad">{errors.length} to fix</span>}</div>
              <div className="card-pad stack">
                {v.issues.length === 0 && <p className="muted">No problems found. 🎉</p>}
                {[...errors, ...warnings].slice(0, 30).map((i, n) => (
                  <button key={n} className={'callout ' + (i.severity === 'ERROR' ? 'bad' : 'warn')} style={{ textAlign: 'left', font: 'inherit', cursor: i.entryId ? 'pointer' : 'default' }}
                    onClick={() => { if (i.entryId) { setMode('draft'); setSelected(i.entryId) } }}>
                    <Icon name={i.severity === 'ERROR' ? 'alert' : 'info'} />
                    <div><div><b>{i.severity === 'ERROR' ? 'Must fix: ' : 'Heads up: '}</b>{i.message}</div><div className="small" style={{ opacity: .85 }}>→ {i.hint}</div></div>
                  </button>
                ))}
                {v.issues.length > 30 && <p className="faint">…and {v.issues.length - 30} more.</p>}
              </div>
            </section>
            <section className="card">
              <div className="card-head"><div><h2>Version history</h2><p>Every publish is kept. Roll back by restoring and publishing again.</p></div></div>
              {v.versions.length === 0 ? <div className="card-pad faint">No versions yet.</div> : v.versions.slice(0, 8).map(ver => (
                <div key={ver.id} className="step" style={{ padding: '10px 20px' }}>
                  <div className="step-num" style={ver.status === 'PUBLISHED' ? { background: 'var(--ok)', color: '#fff' } : {}}>v{ver.number}</div>
                  <div className="grow small"><b>{ver.status === 'PUBLISHED' ? 'On air now' : 'Earlier version'}</b><div className="faint">{fmtDayTime(ver.publishedAt)}{ver.note && ` · ${ver.note}`}</div></div>
                  {org.can('SCHEDULE_EDIT') && ver.status !== 'PUBLISHED' && <button className="btn sm" disabled={busy} onClick={() => edit('restore', { versionNumber: ver.number })}>Restore</button>}
                </div>
              ))}
            </section>
          </div>
        </div>
      )}

      {v && modal === 'autofill' && <AutofillModal v={v} busy={busy} onClose={() => setModal(null)} onSubmit={b => edit('autofill', b)} />}
      {v && modal === 'program' && <ProgramModal v={v} busy={busy} onClose={() => setModal(null)} onSubmit={b => edit('entries', b)} />}
      {v && modal === 'break' && <BreakModal v={v} busy={busy} onClose={() => setModal(null)} onSubmit={b => edit('entries', b)} />}
      {v && modal === 'publish' && <PublishModal v={v} errors={errors} warnings={warnings} busy={busy} onClose={() => setModal(null)} onSubmit={note => edit('publish', { note })} />}
      {v && modal === 'clear' && <Confirm danger title="Clear the upcoming draft?" confirmLabel="Clear upcoming" onClose={() => setModal(null)}
        text="Removes everything after now from the draft. What's on air is untouched until you publish." onConfirm={() => edit('clear', {})} />}
    </div>
  )
}

function Timeline({ entries, assets, from, to, now, pxPerHour, tz, selected, onSelect, errorIds }: {
  entries: Entry[]; assets: Record<string, AssetRef>; from: string; to: string; now: string; pxPerHour: number; tz: string
  selected: string | null; onSelect: (id: string) => void; errorIds: Set<string | undefined>
}) {
  const ref = useRef<HTMLDivElement>(null)
  const t0 = new Date(from).getTime(), t1 = new Date(to).getTime()
  const px = (t: number) => ((t - t0) / 3_600_000) * pxPerHour
  const width = px(t1)
  const ticks = useMemo(() => {
    const out: number[] = []
    const step = pxPerHour >= 1000 ? 600_000 : pxPerHour >= 190 ? 1_800_000 : 3_600_000
    for (let t = Math.ceil(t0 / step) * step; t < t1; t += step) out.push(t)
    return out
  }, [t0, t1, pxPerHour])
  useEffect(() => { if (ref.current) ref.current.scrollLeft = Math.max(0, px(new Date(now).getTime()) - 120) }, [pxPerHour, from]) // eslint-disable-line react-hooks/exhaustive-deps
  // bring the selected item (e.g. clicked from the problem list) into view
  useEffect(() => {
    const el = selected ? ref.current?.querySelector<HTMLElement>(`[data-id="${selected}"]`) : null
    if (el && ref.current) ref.current.scrollLeft = Math.max(0, el.offsetLeft - 120)
  }, [selected])
  if (entries.length === 0) return <div className="callout warn"><Icon name="calendar" /><div>Nothing scheduled in this period. Use <b>Auto-fill</b> to build a schedule from your videos.</div></div>
  return (
    <div className="timeline" ref={ref}>
      <div className="tl-inner" style={{ width }}>
        <div className="tl-axis">{ticks.map(t => <div key={t} className="tl-tick" style={{ left: px(t) }}>{fmtTime(new Date(t).toISOString(), tz)}</div>)}</div>
        <div className="tl-lane">
          {entries.map(e => {
            const s = new Date(e.startAt).getTime(), en = new Date(e.endAt).getTime()
            if (en <= t0 || s >= t1) return null
            const a = e.assetId ? assets[e.assetId] : undefined
            const isBreak = e.kind === 'AD_BREAK'
            const left = px(Math.max(s, t0)), w = Math.max(3, px(Math.min(en, t1)) - left)
            return (
              <div key={e.id} data-id={e.id} role="button" tabIndex={0} title={(isBreak ? 'Ad break' : a?.title) + ' · ' + fmtTime(e.startAt, tz) + '–' + fmtTime(e.endAt, tz)}
                className={'tl-block' + (isBreak ? ' break' + (e.ownership === 'DISTRIBUTOR' ? ' dist' : '') : '') + (selected === e.id ? ' sel' : '') + (errorIds.has(e.id) ? ' err' : '')}
                style={{ left, width: w, background: isBreak ? undefined : a?.color ?? '#64748b' }}
                onClick={() => onSelect(e.id)} onKeyDown={k => k.key === 'Enter' && onSelect(e.id)}>
                {w > 40 && <div className="ellipsis">{isBreak ? (w > 70 ? 'Ad break' : 'Ad') : a?.title}</div>}
                {w > 70 && <div className="ellipsis" style={{ fontWeight: 400, opacity: .85 }}>{fmtTime(e.startAt, tz)}</div>}
              </div>
            )
          })}
        </div>
        <div className="tl-now" style={{ left: px(new Date(now).getTime()) }} />
      </div>
    </div>
  )
}

function Selected({ e, a, tz, editable, busy, issues, onDelete, onClose }: {
  e: Entry; a?: AssetRef; tz: string; editable: boolean; busy: boolean; issues: Issue[]; onDelete: () => void; onClose: () => void
}) {
  const isBreak = e.kind === 'AD_BREAK'
  return (
    <div className="card card-pad stack" style={{ background: 'var(--surface-2)' }}>
      <div className="between">
        <div className="row">
          {isBreak ? <span className="badge warn"><Icon name="megaphone" size={12} />Ad break</span> : <span className="badge info">Programme</span>}
          <b>{isBreak ? `${fmtDur(new Date(e.endAt).getTime() - new Date(e.startAt).getTime())} ad break` : a?.title}</b>
        </div>
        <button className="btn ghost sm icon-btn" onClick={onClose} aria-label="Close details"><Icon name="x" size={15} /></button>
      </div>
      <div className="kv">
        <span>When</span><span>{fmtDayTime(e.startAt, tz)} – {fmtTime(e.endAt, tz)}</span>
        {isBreak ? <>
          <span>Belongs to</span><span>{e.ownership === 'DISTRIBUTOR' ? 'The platform (they sell this break)' : 'You (your campaigns fill it)'}</span>
          <span>Marker</span><span className="mono">SCTE-35 time_signal, type {e.ownership === 'DISTRIBUTOR' ? '0x36 Distributor' : '0x34 Provider'} Placement Opportunity</span>
        </> : <>
          <span>Part of video</span><span>{fmtDur(e.assetOffsetMs)} → {fmtDur(e.assetOffsetMs + new Date(e.endAt).getTime() - new Date(e.startAt).getTime())} of {a ? fmtDur(a.durationMs) : '?'}</span>
        </>}
      </div>
      {issues.map((i, n) => <div key={n} className={'callout ' + (i.severity === 'ERROR' ? 'bad' : 'warn')}><Icon name="alert" /><div>{i.message}<div className="small">→ {i.hint}</div></div></div>)}
      {editable && <div className="row"><button className="btn danger sm" disabled={busy} onClick={onDelete}><Icon name="trash" size={14} />Remove {isBreak ? 'break' : 'programme'}</button>
        {!isBreak && <span className="faint">Removes all parts of this showing, including its mid-roll breaks.</span>}</div>}
    </div>
  )
}

function EntryList({ entries, assets, tz, now, errorIds, onSelect, selected }: {
  entries: Entry[]; assets: Record<string, AssetRef>; tz: string; now: string; errorIds: Set<string | undefined>; onSelect: (id: string) => void; selected: string | null
}) {
  const [showPast, setShowPast] = useState(false)
  const t = new Date(now).getTime()
  const rows = entries.filter(e => showPast || new Date(e.endAt).getTime() > t)
  return (
    <section className="card">
      <div className="card-head"><div><h2>Running order</h2><p>The same schedule as a list — keyboard friendly</p></div>
        <label className="check small"><input type="checkbox" checked={showPast} onChange={e => setShowPast(e.target.checked)} />Show earlier items</label></div>
      <div style={{ maxHeight: 420, overflowY: 'auto' }}>
        <table className="table">
          <thead><tr><th>Time</th><th>What plays</th><th>Length</th><th /></tr></thead>
          <tbody>
            {rows.slice(0, 200).map(e => {
              const a = e.assetId ? assets[e.assetId] : undefined
              const live = new Date(e.startAt).getTime() <= t && new Date(e.endAt).getTime() > t
              return (
                <tr key={e.id} className="clickable" onClick={() => onSelect(e.id)} style={selected === e.id ? { background: '#eef2ff' } : undefined}>
                  <td className="mono">{fmtTime(e.startAt, tz)}</td>
                  <td>{e.kind === 'AD_BREAK'
                    ? <span className="row"><span className={'badge ' + (e.ownership === 'DISTRIBUTOR' ? '' : 'warn')}>Ad break</span><span className="faint">{e.ownership === 'DISTRIBUTOR' ? "platform's" : 'ours'}</span></span>
                    : <span className="row"><span style={{ width: 10, height: 10, borderRadius: 3, background: a?.color }} /><b>{a?.title}</b>{e.assetOffsetMs > 0 && <span className="faint">(continued)</span>}</span>}</td>
                  <td>{fmtDur(new Date(e.endAt).getTime() - new Date(e.startAt).getTime())}</td>
                  <td style={{ textAlign: 'right' }}>{live && <span className="badge live"><span className="dot" />Now</span>}{errorIds.has(e.id) && <span className="badge bad">Fix</span>}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {rows.length > 200 && <p className="faint card-pad">Showing the first 200 items.</p>}
      </div>
    </section>
  )
}

// ---------------------------------------------------------------- modals

function readyProgrammes(v: View) {
  return Object.values(v.assets).filter(a => a.kind !== 'AD' && a.kind !== 'SLATE' && a.status === 'READY')
}

function AutofillModal({ v, busy, onClose, onSubmit }: { v: View; busy: boolean; onClose: () => void; onSubmit: (b: object) => void }) {
  const progs = readyProgrammes(v)
  const used = new Set(v.draftEntries.map(e => e.assetId).filter(Boolean))
  const [ids, setIds] = useState<string[]>(progs.filter(p => used.has(p.id)).map(p => p.id))
  const [hours, setHours] = useState(24)
  const [replace, setReplace] = useState(false)
  return (
    <Modal wide title="Auto-fill the schedule" subtitle="We rotate through the videos you pick, add ad breaks at their break points, and respect licences and your ad limit." onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={busy} onClick={() => onSubmit({ hours, assetIds: ids, replace })}>{busy ? <span className="spinner" /> : 'Fill schedule'}</button></>}>
      <div className="field">Videos to use <span className="hint">Leave all unticked to use every ready video</span></div>
      <div className="grid g3">
        {progs.map(p => (
          <label key={p.id} className="check card" style={{ padding: '8px 10px' }}>
            <input type="checkbox" checked={ids.includes(p.id)} onChange={e => setIds(e.target.checked ? [...ids, p.id] : ids.filter(x => x !== p.id))} />
            <span style={{ width: 10, height: 10, borderRadius: 3, background: p.color }} /><span className="ellipsis grow">{p.title}</span><span className="faint">{fmtDur(p.durationMs)}</span>
          </label>
        ))}
      </div>
      <div className="grid g2">
        <label className="field">How many hours?<select value={hours} onChange={e => setHours(+e.target.value)}>{[3, 6, 12, 24, 48, 72].map(h => <option key={h} value={h}>{h} hours</option>)}</select></label>
        <div className="field">Where<label className="check"><input type="radio" checked={!replace} onChange={() => setReplace(false)} />Add after the current end of the draft</label>
          <label className="check"><input type="radio" checked={replace} onChange={() => setReplace(true)} />Replace everything upcoming</label></div>
      </div>
      <div className="callout"><Icon name="shield" /><div>Videos without a licence for this channel's countries and times are skipped — we'll tell you which.</div></div>
    </Modal>
  )
}

function StartPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="field">Start
      <label className="check"><input type="radio" checked={value === ''} onChange={() => onChange('')} />At the end of the schedule</label>
      <label className="check"><input type="radio" checked={value !== ''} onChange={() => onChange(toLocalInput(new Date(Date.now() + 3600_000)))} />At a specific time</label>
      {value !== '' && <input type="datetime-local" value={value} onChange={e => onChange(e.target.value)} />}
    </div>
  )
}
const toLocalInput = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16)

function ProgramModal({ v, busy, onClose, onSubmit }: { v: View; busy: boolean; onClose: () => void; onSubmit: (b: object) => void }) {
  const progs = readyProgrammes(v)
  const [assetId, setAssetId] = useState(progs[0]?.id ?? '')
  const [start, setStart] = useState('')
  const [withBreaks, setWithBreaks] = useState(true)
  return (
    <Modal title="Add a programme" onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={busy || !assetId}
        onClick={() => onSubmit({ kind: 'PROGRAM', assetId, withBreaks, startAt: start ? new Date(start).toISOString() : undefined })}>Add</button></>}>
      <label className="field">Video<select value={assetId} onChange={e => setAssetId(e.target.value)}>{progs.map(p => <option key={p.id} value={p.id}>{p.title} ({fmtDur(p.durationMs)})</option>)}</select></label>
      <StartPicker value={start} onChange={setStart} />
      <label className="check"><input type="checkbox" checked={withBreaks} onChange={e => setWithBreaks(e.target.checked)} />Insert ad breaks at the video's break points</label>
      <p className="faint">If the licence doesn't cover this time or country, the problem shows in the check list — nothing is hidden.</p>
    </Modal>
  )
}

function BreakModal({ v, busy, onClose, onSubmit }: { v: View; busy: boolean; onClose: () => void; onSubmit: (b: object) => void }) {
  const [seconds, setSeconds] = useState(v.channel.breakSeconds)
  const [start, setStart] = useState('')
  return (
    <Modal title="Add an ad break" subtitle="An ad break is time reserved for ads. The ad server decides which ads fill it for each viewer." onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={busy}
        onClick={() => onSubmit({ kind: 'AD_BREAK', seconds, startAt: start ? new Date(start).toISOString() : undefined })}>Add break</button></>}>
      <label className="field">Length<select value={seconds} onChange={e => setSeconds(+e.target.value)}>{[30, 60, 90, 120, 180].map(s => <option key={s} value={s}>{s} seconds</option>)}</select></label>
      <StartPicker value={start} onChange={setStart} />
    </Modal>
  )
}

function PublishModal({ v, errors, warnings, busy, onClose, onSubmit }: { v: View; errors: Issue[]; warnings: Issue[]; busy: boolean; onClose: () => void; onSubmit: (note: string) => void }) {
  const [note, setNote] = useState('')
  const next = (v.versions[0]?.number ?? 0) + 1
  return (
    <Modal title={errors.length ? 'Not ready to publish yet' : `Publish version ${next}?`} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>{errors.length ? 'Close' : 'Cancel'}</button>
        {errors.length === 0 && <button className="btn primary" disabled={busy} onClick={() => onSubmit(note)}><Icon name="rocket" size={16} />Publish to viewers</button>}</>}>
      {errors.length > 0 ? (
        <div className="callout bad"><Icon name="alert" /><div>Fix the {errors.length} item(s) marked <b>Must fix</b> first. Publishing never lets a schedule with licence, overlap or ad-limit problems reach viewers.</div></div>
      ) : <>
        <div className="callout ok"><Icon name="check" /><div>All checks passed. Viewers{v.channel.status === 'LIVE' ? ' will switch to this version straight away' : ' will see it once the channel goes live'}. The TV guide (EPG) updates from the same version.</div></div>
        {warnings.length > 0 && <div className="callout warn"><Icon name="info" /><div>{warnings.length} heads-up item(s) — fine to publish, but worth a look.</div></div>}
        <label className="field">What changed? <span className="hint">Optional note for the version history</span><input value={note} onChange={e => setNote(e.target.value)} placeholder="e.g. Added Friday movie night" /></label>
      </>}
    </Modal>
  )
}
