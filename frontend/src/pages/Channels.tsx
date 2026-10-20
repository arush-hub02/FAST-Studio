import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type AssetView, type ChannelView } from '../api'
import { useOrg } from '../app'
import { ChannelLogo, Confirm, Countries, CountryPicker, csv, Empty, ErrorBox, fmtDayTime, fmtDur, fmtTime, Icon, InfoTip, Loading, Modal, PageHead, Status, useAction, useApi } from '../ui'

const TIMEZONES = ['Asia/Kolkata', 'Europe/London', 'America/New_York', 'America/Los_Angeles', 'Europe/Berlin', 'Asia/Singapore', 'Australia/Sydney', 'UTC']
const COLORS = ['#ef4444', '#f97316', '#eab308', '#22c55e', '#059669', '#0ea5e9', '#6366f1', '#a855f7', '#ec4899', '#64748b']

export default function Channels() {
  const org = useOrg()
  const list = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`, [], 20000)
  const [wizard, setWizard] = useState(false)
  const [editing, setEditing] = useState<ChannelView | null>(null)
  const [confirm, setConfirm] = useState<{ v: ChannelView; action: 'stop' | 'archive' } | null>(null)
  const { busy, run } = useAction()
  const act = (v: ChannelView, action: string, msg: string) =>
    run(() => api(`/api/orgs/${org.slug}/channels/${v.channel.id}/${action}`, { method: 'POST' }), msg).then(list.reload)

  return (
    <div className="page">
      <PageHead title="Channels" tip="A channel is a 24/7 TV service: a name and look, the countries it's offered in, a schedule, and rules for ads."
        subtitle="Each channel plays one continuous schedule. Viewers tune in and something is already on."
        actions={org.can('CHANNELS_EDIT') && <button className="btn primary" onClick={() => setWizard(true)}><Icon name="plus" />New channel</button>} />
      {list.error ? <ErrorBox error={list.error} retry={list.reload} /> : !list.data ? <Loading rows={4} /> : list.data.length === 0 ? (
        <div className="card"><Empty icon="tv" title="No channels yet" text="Create a channel in four short steps. We'll even build its first day of programming for you."
          action={org.can('CHANNELS_EDIT') && <button className="btn primary" onClick={() => setWizard(true)}>Create a channel</button>} /></div>
      ) : (
        <div className="grid g2">
          {list.data.map(v => {
            const c = v.channel
            return (
              <section key={c.id} className="card" style={{ overflow: 'hidden' }}>
                <div style={{ height: 6, background: c.color }} />
                <div className="card-pad stack">
                  <div className="between">
                    <div className="row"><ChannelLogo name={c.name} color={c.color} size={46} />
                      <div><h2>{c.name}</h2><div className="faint">{c.genre} · {c.timezone} · /{c.slug}</div></div></div>
                    <Status value={c.status} />
                  </div>
                  <p className="muted">{c.description}</p>
                  <div className="kv">
                    <span>Offered in</span><span><Countries codes={csv(c.territories)} /></span>
                    <span>On now</span><span>{v.now ? <><b>{v.now.title}</b> <span className="faint">until {fmtTime(v.now.end)}</span></> : '—'}</span>
                    <span>Up next</span><span>{v.next ? <>{v.next.title} <span className="faint">at {fmtTime(v.next.start)}</span></> : '—'}</span>
                    <span>Schedule</span><span>{v.publishedVersion ? <>Version {v.publishedVersion}, runs until {fmtDayTime(v.scheduledUntil)}</> : 'Not published yet'}</span>
                    <span>Ad rules <InfoTip text="Maximum ad time per clock hour and the length of each ad break. Platforms often set their own limits too." /></span>
                    <span>{Math.round(c.adSecondsPerHour / 60)} min/hour max · {c.breakSeconds}s breaks</span>
                    <span>Fallback video</span><span>{v.fallbackTitle ?? <span className="faint">none — gaps show the standby screen</span>}</span>
                  </div>
                  {v.readiness.length > 0 && c.status !== 'LIVE' && (
                    <div className="callout warn"><Icon name="alert" /><div><b>Before going live:</b> {v.readiness.join('; ')}.</div></div>
                  )}
                  <div className="row-wrap" style={{ marginTop: 4 }}>
                    <Link className="btn sm" to={`${org.base}/schedule?channel=${c.id}`}><Icon name="calendar" size={15} />Schedule</Link>
                    {c.status === 'LIVE' && <Link className="btn sm" to={`${org.base}/watch?channel=${c.slug}`}><Icon name="play" size={15} />Watch</Link>}
                    {org.can('CHANNELS_EDIT') && <button className="btn sm" onClick={() => setEditing(v)}>Settings</button>}
                    <div className="grow" />
                    {org.can('SCHEDULE_PUBLISH') && c.status !== 'LIVE' && c.status !== 'ARCHIVED' &&
                      <button className="btn sm primary" disabled={busy || v.readiness.length > 0} title={v.readiness.join('; ')}
                        onClick={() => act(v, 'go-live', c.name + ' is on air')}><Icon name="rocket" size={15} />Go live</button>}
                    {org.can('SCHEDULE_PUBLISH') && c.status === 'LIVE' && <button className="btn sm danger" onClick={() => setConfirm({ v, action: 'stop' })}><Icon name="stop" size={14} />Stop</button>}
                    {org.can('CHANNELS_EDIT') && c.status !== 'LIVE' && <button className="btn sm ghost" onClick={() => setConfirm({ v, action: 'archive' })}>Archive</button>}
                  </div>
                </div>
              </section>
            )
          })}
        </div>
      )}
      {wizard && <Wizard onClose={() => setWizard(false)} onDone={() => { setWizard(false); list.reload() }} />}
      {editing && <Settings view={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); list.reload() }} />}
      {confirm && <Confirm danger title={confirm.action === 'stop' ? `Stop ${confirm.v.channel.name}?` : `Archive ${confirm.v.channel.name}?`}
        confirmLabel={confirm.action === 'stop' ? 'Stop channel' : 'Archive'}
        text={confirm.action === 'stop' ? 'Viewers will see the standby screen until you go live again. The schedule is kept.' : 'The channel is hidden from lists. Its history stays in the audit log.'}
        onClose={() => setConfirm(null)} onConfirm={() => act(confirm.v, confirm.action, confirm.action === 'stop' ? 'Channel stopped' : 'Channel archived')} />}
    </div>
  )
}

function Wizard({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const nav = useNavigate()
  const assets = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`)
  const { busy, run } = useAction()
  const [step, setStep] = useState(0)
  const [f, setF] = useState({ name: '', description: '', genre: '', timezone: 'Asia/Kolkata', color: COLORS[6], territories: ['IN', 'GB'] as string[], assetIds: [] as string[], hours: 24, adMinutes: 8, breakSeconds: 90 })
  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const programmes = assets.data?.filter(a => a.asset.kind !== 'AD' && a.asset.status === 'READY') ?? []
  const steps = ['Name & look', 'Where it plays', 'Programmes', 'Review']
  const canNext = [f.name.trim().length > 1, f.territories.length > 0, true, true][step]

  async function create() {
    const v = await api<ChannelView>(`/api/orgs/${org.slug}/channels`, { body: {
      name: f.name, description: f.description, genre: f.genre, timezone: f.timezone, color: f.color, territories: f.territories,
      adSecondsPerHour: f.adMinutes * 60, breakSeconds: f.breakSeconds, assetIds: f.assetIds, autofillHours: f.hours,
    } })
    return v
  }

  return (
    <Modal wide title="Create a channel" subtitle={`Step ${step + 1} of 4 — ${steps[step]}`} onClose={onClose} footer={<>
      {step > 0 && <button className="btn" onClick={() => setStep(s => s - 1)}>Back</button>}
      <div className="grow" />
      {step < 3 ? <button className="btn primary" disabled={!canNext} onClick={() => setStep(s => s + 1)}>Continue<Icon name="arrowRight" size={15} /></button>
        : <button className="btn primary" disabled={busy} onClick={() => run(create, 'Channel created with a draft schedule').then(v => { if (v) { onDone(); nav(`${org.base}/schedule?channel=${v.channel.id}`) } })}>
          {busy ? <span className="spinner" /> : 'Create channel & open schedule'}</button>}
    </>}>
      <div className="progress"><div style={{ width: `${(step + 1) * 25}%` }} /></div>
      {step === 0 && <>
        <div className="grid g2">
          <label className="field">Channel name<input autoFocus value={f.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Midnight Mysteries" /></label>
          <label className="field">Genre<input value={f.genre} onChange={e => set('genre', e.target.value)} placeholder="Crime, Comedy, Travel…" /></label>
        </div>
        <label className="field">Short description<span className="hint">Shown to viewers in channel guides</span><textarea value={f.description} onChange={e => set('description', e.target.value)} /></label>
        <div className="grid g2">
          <label className="field">Time zone<span className="hint">Used when you read and plan the schedule</span>
            <select value={f.timezone} onChange={e => set('timezone', e.target.value)}>{TIMEZONES.map(t => <option key={t}>{t}</option>)}</select></label>
          <div className="field">Colour
            <div className="row-wrap">{COLORS.map(c => <button key={c} type="button" aria-label={'Colour ' + c} onClick={() => set('color', c)}
              style={{ width: 28, height: 28, borderRadius: 8, background: c, border: f.color === c ? '3px solid #0f172a' : '2px solid #fff', boxShadow: '0 0 0 1px #d4d9e3', cursor: 'pointer' }} />)}</div>
          </div>
        </div>
        {f.name && <div className="row"><ChannelLogo name={f.name} color={f.color} /><b>{f.name}</b><span className="faint">— preview of your channel badge</span></div>}
      </>}
      {step === 1 && <>
        <div className="callout"><Icon name="globe" /><div>Pick the countries where viewers can watch. A programme also needs a licence for that country — if it doesn't have one, viewers there see a "not available in your region" screen instead.</div></div>
        <CountryPicker value={f.territories} onChange={v => set('territories', v)} />
      </>}
      {step === 2 && <>
        <p className="muted">Choose videos for this channel. We'll arrange them into a schedule with ad breaks at their break points. You can change everything afterwards.</p>
        {!assets.data ? <Loading /> : programmes.length === 0 ? <div className="callout warn"><Icon name="alert" />No ready videos yet. You can still create the channel and fill it later.</div> : (
          <div className="grid g3">
            {programmes.map(a => {
              const on = f.assetIds.includes(a.asset.id)
              return (
                <button key={a.asset.id} type="button" className="card" aria-pressed={on} onClick={() => set('assetIds', on ? f.assetIds.filter(x => x !== a.asset.id) : [...f.assetIds, a.asset.id])}
                  style={{ padding: 8, textAlign: 'left', cursor: 'pointer', font: 'inherit', borderColor: on ? 'var(--primary)' : undefined, boxShadow: on ? '0 0 0 2px var(--primary)' : undefined }}>
                  <div className="thumb" style={{ background: a.asset.color }}>{a.thumbUrl && <img src={a.thumbUrl} alt="" />}
                    {on && <span className="overlay-badge badge ok"><Icon name="check" size={12} />Selected</span>}<span className="dur">{fmtDur(a.asset.durationMs)}</span></div>
                  <div className="between" style={{ padding: '6px 2px 0' }}><b className="ellipsis small">{a.asset.title}</b><Status value={a.rightsStatus} /></div>
                </button>
              )
            })}
          </div>
        )}
        <div className="grid g3">
          <label className="field">Fill how many hours?<select value={f.hours} onChange={e => set('hours', +e.target.value)}>{[6, 12, 24, 48].map(h => <option key={h} value={h}>{h} hours</option>)}</select></label>
          <label className="field">Max ads per hour<select value={f.adMinutes} onChange={e => set('adMinutes', +e.target.value)}>{[4, 6, 8, 10, 12].map(m => <option key={m} value={m}>{m} minutes</option>)}</select></label>
          <label className="field">Ad break length<select value={f.breakSeconds} onChange={e => set('breakSeconds', +e.target.value)}>{[60, 90, 120].map(s => <option key={s} value={s}>{s} seconds</option>)}</select></label>
        </div>
      </>}
      {step === 3 && (
        <div className="stack">
          <div className="row"><ChannelLogo name={f.name} color={f.color} size={52} /><div><h2>{f.name}</h2><div className="faint">{f.genre} · {f.timezone}</div></div></div>
          <div className="kv">
            <span>Offered in</span><Countries codes={f.territories} />
            <span>Programmes</span><span>{f.assetIds.length ? `${f.assetIds.length} videos, arranged over ${f.hours} hours` : 'None yet — empty schedule'}</span>
            <span>Ads</span><span>up to {f.adMinutes} min per hour, {f.breakSeconds}s breaks</span>
          </div>
          <div className="callout"><Icon name="info" /><div>Next you'll see the <b>draft schedule</b>. It isn't on air until you <b>publish</b> it and press <b>Go live</b>.</div></div>
        </div>
      )}
    </Modal>
  )
}

function Settings({ view, onClose, onDone }: { view: ChannelView; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const c = view.channel
  const assets = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`)
  const { busy, run } = useAction()
  const [f, setF] = useState({ name: c.name, description: c.description, genre: c.genre, timezone: c.timezone, territories: csv(c.territories),
    adMinutes: Math.round(c.adSecondsPerHour / 60), breakSeconds: c.breakSeconds, fallback: c.fallbackAssetId ?? '' })
  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  return (
    <Modal wide title={`${c.name} settings`} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" disabled={busy} onClick={() => run(() => api(`/api/orgs/${org.slug}/channels/${c.id}`, { method: 'PATCH', body: {
        name: f.name, description: f.description, genre: f.genre, timezone: f.timezone, territories: f.territories, adSecondsPerHour: f.adMinutes * 60,
        breakSeconds: f.breakSeconds, fallbackAssetId: f.fallback || undefined, clearFallback: !f.fallback } }), 'Settings saved').then(r => r !== undefined && onDone())}>Save</button>
    </>}>
      <div className="grid g2">
        <label className="field">Name<input value={f.name} onChange={e => set('name', e.target.value)} /></label>
        <label className="field">Genre<input value={f.genre} onChange={e => set('genre', e.target.value)} /></label>
      </div>
      <label className="field">Description<textarea value={f.description} onChange={e => set('description', e.target.value)} /></label>
      <div className="field">Offered in<CountryPicker value={f.territories} onChange={v => set('territories', v)} /></div>
      <div className="grid g3">
        <label className="field">Time zone<select value={f.timezone} onChange={e => set('timezone', e.target.value)}>{[...new Set([c.timezone, ...TIMEZONES])].map(t => <option key={t}>{t}</option>)}</select></label>
        <label className="field">Max ads per hour (min)<input type="number" min={0} max={20} value={f.adMinutes} onChange={e => set('adMinutes', +e.target.value)} /></label>
        <label className="field">Break length (s)<input type="number" min={15} max={300} step={3} value={f.breakSeconds} onChange={e => set('breakSeconds', +e.target.value)} /></label>
      </div>
      <label className="field">Fallback video<span className="hint">Loops during schedule gaps instead of the standby screen</span>
        <select value={f.fallback} onChange={e => set('fallback', e.target.value)}>
          <option value="">None (standby screen)</option>
          {assets.data?.filter(a => a.asset.kind !== 'AD' && a.asset.status === 'READY').map(a => <option key={a.asset.id} value={a.asset.id}>{a.asset.title}</option>)}
        </select></label>
    </Modal>
  )
}
