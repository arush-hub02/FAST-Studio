import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type AssetView, type MediaSettingsView } from '../api'
import { useOrg } from '../app'
import { ErrorBox, Loading, useAction, useApi } from '../ui'
import { DAYS } from '../pages/Settings'

type Rule = { id: string; series: string; startDate: string; endDate: string; premiereTime: string; repeatTime?: string; weekdays: string; startSeason: number; startEpisode: number; loop: boolean; enabled: boolean }
type Preview = { revision: number; placements: { date: string; title: string; season: number; episode: number; encore: boolean; start: string; end: string }[]; notices: string[] }
type Props = { channelId: string; timezone: string; revision: number; onChange: () => void }

export function SmartProgramming(props: Props) {
  const org = useOrg()
  const settings = useApi<MediaSettingsView>(`/api/orgs/${org.slug}/settings`)
  const assets = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets?kind=EPISODE`)
  const rules = useApi<Rule[]>(`/api/orgs/${org.slug}/channels/${props.channelId}/programming`)
  const error = settings.error ?? assets.error ?? rules.error
  return <details className="card card-pad" style={{ marginBlock: 16 }}>
    <summary style={{ cursor: 'pointer', padding: 8 }}><b>Smart programming — daily episodes and encores</b></summary>
    {error ? <ErrorBox error={error} retry={() => { settings.reload(); assets.reload(); rules.reload() }} /> : !settings.data || !assets.data || !rules.data ? <Loading /> :
      <ProgrammingForm key={props.channelId} {...props} settings={settings.data} assets={assets.data} rules={rules.data} reload={rules.reload} />}
  </details>
}
function ProgrammingForm({ settings, assets, rules, reload, ...props }: Props & { settings: MediaSettingsView; assets: AssetView[]; rules: Rule[]; reload: () => void }) {
  const org = useOrg()
  const base = `/api/orgs/${org.slug}/channels/${props.channelId}/programming`
  const names = [...new Set(assets.map(v => v.asset.series).filter((s): s is string => !!s))].sort()
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: props.timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  const addDays = (date: string, days: number) => new Date(Date.parse(date + 'T12:00:00Z') + days * 86400000).toISOString().slice(0, 10)
  const [form, setForm] = useState({ series: names[0] ?? '', startDate: addDays(today, 1), endDate: addDays(today, 14), premiereTime: settings.premiereTime, repeatTime: '', weekdays: settings.weekdays, startSeason: 1, startEpisode: 1, loop: false })
  const [preview, setPreview] = useState<Preview | null>(null)
  const [generated, setGenerated] = useState<Preview | null>(null)
  const [days, setDays] = useState(14)
  const { busy, run } = useAction()
  const editable = org.can('SCHEDULE_EDIT')
  const set = (change: Partial<typeof form>) => { setForm({ ...form, ...change }); setPreview(null) }
  const result = generated ?? preview
  return <div className="stack" style={{ paddingTop: 16 }}>
    <p>Rules select the next episode in season/episode order. Encores reuse the day's premiere. Generate a draft, review issues, then publish through the usual Schedule workflow. Manual placements are retained.</p>
    {rules.length > 0 && <div style={{ overflowX: 'auto' }}><table className="table"><thead><tr><th>Series</th><th>Dates</th><th>Local time</th><th>Progression</th><th>Action</th></tr></thead><tbody>{rules.map(r => <tr key={r.id}>
      <td>{r.series}</td><td>{r.startDate} – {r.endDate}</td><td>{r.premiereTime}{r.repeatTime && ` / encore ${r.repeatTime}`}</td><td>S{r.startSeason} E{r.startEpisode}{r.loop ? ' · loops' : ' · holds at end'}</td>
      <td><button className="btn sm" disabled={!editable || busy} onClick={() => run(() => api(`${base}/${r.id}`, { method: 'PUT', body: { enabled: !r.enabled } }), r.enabled ? 'Rule paused; existing draft placements retained' : 'Rule enabled').then(reload)}>{r.enabled ? 'Pause rule' : 'Enable rule'}</button></td>
    </tr>)}</tbody></table></div>}
    {editable && <div className="row-wrap" style={{ gap: 12 }}>
      <label className="field"><span>Generate through the next</span><select value={days} onChange={e => setDays(Number(e.target.value))}>{[7,14,31].map(n => <option key={n} value={n}>{n} days</option>)}</select></label>
      <button className="btn" disabled={busy || !rules.some(r => r.enabled)} onClick={() => run(() => api<Preview>(`${base}/generate`, { body: { expectedRevision: props.revision, days } }), 'Draft generation complete; review before publishing').then(r => { if (r) { setGenerated(r); props.onChange() } })}>Generate draft from saved rules</button>
    </div>}
    {!names.length ? <p className="callout">Add episode media with series, season and episode numbers in <Link to={`/${org.slug}/library`}>Library</Link> first.</p> : editable && <form className="stack" onSubmit={e => { e.preventDefault(); setGenerated(null); run(() => api<Preview>(`${base}/preview`, { body: form })).then(r => { if (r) setPreview(r) }) }}>
      <h3>New programming rule</h3>
      <div className="grid g3">
        <label className="field"><span>Series</span><select required value={form.series} onChange={e => set({ series: e.target.value })}>{names.map(s => <option key={s}>{s}</option>)}</select></label>
        <label className="field"><span>Starting season</span><input type="number" min={1} required value={form.startSeason} onChange={e => set({ startSeason: Number(e.target.value) })} /></label>
        <label className="field"><span>Starting episode</span><input type="number" min={1} required value={form.startEpisode} onChange={e => set({ startEpisode: Number(e.target.value) })} /></label>
        <label className="field"><span>Start date</span><input type="date" min={today} required value={form.startDate} onChange={e => set({ startDate: e.target.value })} /></label>
        <label className="field"><span>End date</span><input type="date" min={form.startDate} required value={form.endDate} onChange={e => set({ endDate: e.target.value })} /></label>
        <label className="field"><span>Premiere time ({props.timezone})</span><input type="time" required value={form.premiereTime} onChange={e => set({ premiereTime: e.target.value })} /></label>
        <label className="field"><span>Same-day encore (optional)</span><input type="time" value={form.repeatTime} onChange={e => set({ repeatTime: e.target.value })} /></label>
      </div>
      <fieldset><legend>One new episode on each selected day</legend><div className="row-wrap">{DAYS.map((day,i) => <label key={day} className="row" style={{ gap: 8, padding: 8 }}><input type="checkbox" checked={form.weekdays.includes(i+1)} onChange={e => set({ weekdays: e.target.checked ? [...form.weekdays,i+1] : form.weekdays.filter(d => d !== i+1) })} />{day}</label>)}</div></fieldset>
      <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={form.loop} onChange={e => set({ loop: e.target.checked })} />Restart from the chosen episode after the series ends</label>
      <p className="faint">Missing/unready episodes, rights failures, ambiguous clock changes and conflicts hold later occurrences. Unscheduled time uses the channel fallback. Preview shows at most 31 days.</p>
      <div className="row"><button className="btn" disabled={busy || !form.weekdays.length}>Preview rule</button><button type="button" className="btn primary" disabled={busy || !preview?.placements.length} onClick={() => run(() => api(base, { body: form }), 'Rule saved. Generate its draft to add programmes.').then(r => { if (r) { setPreview(null); reload() } })}>Save previewed rule</button></div>
    </form>}
    {result && <section aria-label="Programming result" className="stack">
      <h3>{generated ? 'Draft generation' : 'Rule preview'}: {result.placements.length} placements</h3>
      {result.notices.length > 0 && <div className="callout warn" role="status"><ul>{result.notices.map((n,i) => <li key={i}>{n}</li>)}</ul></div>}
      <div style={{ maxHeight: 360, overflow: 'auto' }}><table className="table"><thead><tr><th>Broadcast day</th><th>Programme</th><th>Episode</th><th>Showing</th><th>Start ({props.timezone})</th></tr></thead><tbody>{result.placements.map((p,i) => <tr key={i}><td>{p.date}</td><td>{p.title}</td><td>S{p.season} E{p.episode}</td><td>{p.encore ? 'Encore' : 'Premiere'}</td><td>{new Intl.DateTimeFormat(undefined, { timeZone: props.timezone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(p.start))}</td></tr>)}</tbody></table></div>
    </section>}
  </div>
}
