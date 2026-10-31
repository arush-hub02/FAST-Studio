import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, type AssetView, type ChannelView, type RightsDecision } from '../api'
import { useOrg } from '../app'
import { DateTimePicker } from '../components/DateTimePicker'
import { COUNTRIES, Countries, CountryPicker, csv, ErrorBox, flag, fmtDate, Icon, InfoTip, Loading, Modal, PageHead, Sim, Status, useAction, useApi } from '../ui'

export default function RightsPage() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const list = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`)
  const programmes = list.data?.filter(a => a.asset.kind !== 'AD') ?? []
  const editing = programmes.find(a => a.asset.id === params.get('asset'))
  const problems = programmes.filter(a => a.rightsStatus !== 'OK').length
  return (
    <div className="page">
      <PageHead title="Rights" tip="A licence (rights policy) says where and when you may show a video, and whether ads may run during it. Missing information always means 'not allowed'."
        subtitle="Where and when each programme may play. The schedule, the player and the ad server all check these rules — not just this page." />
      <div className="callout"><Icon name="shield" /><div><b>How decisions are made:</b> no licence → blocked · a blocked country always wins · otherwise the country must be licensed (or worldwide) <i>and</i> offered by the channel · the whole programme must fit inside the licence dates.</div></div>
      <div className="split">
        <section className="card">
          <div className="card-head"><div><h2>Licences</h2><p>{problems ? `${problems} programme(s) need attention` : 'All programmes licensed'}</p></div></div>
          {list.error ? <div className="card-pad"><ErrorBox error={list.error} /></div> : !list.data ? <div className="card-pad"><Loading /></div> : (
            <table className="table">
              <thead><tr><th>Programme</th><th>May play in</th><th>Blocked</th><th>Until</th><th>Ads</th><th>Status</th></tr></thead>
              <tbody>{programmes.map(a => (
                <tr key={a.asset.id} className="clickable" onClick={() => setParams({ asset: a.asset.id })}>
                  <td><b>{a.asset.title}</b><div className="faint">{a.rights?.notes}</div></td>
                  <td><Countries codes={csv(a.rights?.allowedCountries)} /></td>
                  <td><Countries codes={csv(a.rights?.blockedCountries)} bad /></td>
                  <td className="small">{a.rights?.validUntil ? fmtDate(a.rights.validUntil) : a.rights ? 'no end' : '—'}</td>
                  <td>{a.rights ? (a.rights.adsAllowed ? <span className="badge ok">Yes</span> : <span className="badge warn">No ads</span>) : '—'}</td>
                  <td><Status value={a.rightsStatus} /></td>
                </tr>
              ))}</tbody>
            </table>
          )}
        </section>
        <Checker programmes={programmes} />
      </div>
      {editing && <Editor view={editing} onClose={() => setParams({})} onDone={() => { setParams({}); list.reload() }} />}
    </div>
  )
}

function Checker({ programmes }: { programmes: AssetView[] }) {
  const org = useOrg()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const [assetId, setAssetId] = useState('')
  const [country, setCountry] = useState('US')
  const [channelId, setChannelId] = useState('')
  const { busy, run } = useAction()
  const [result, setResult] = useState<{ decision: RightsDecision; asset: string; channel?: string } | null>(null)
  const id = assetId || programmes[0]?.asset.id
  return (
    <section className="card">
      <div className="card-head"><div><h2>Can it play?</h2><p>Ask the rights engine about any viewer</p></div><Sim tip="You type the country here. In production the viewer's location comes from a trusted geo-IP service at the network edge, never from the browser." /></div>
      <div className="card-pad stack">
        <label className="field">Programme<select value={id} onChange={e => setAssetId(e.target.value)}>{programmes.map(a => <option key={a.asset.id} value={a.asset.id}>{a.asset.title}</option>)}</select></label>
        <label className="field">Viewer's country<select value={country} onChange={e => setCountry(e.target.value)}>{Object.keys(COUNTRIES).filter(c => c !== 'WW').map(c => <option key={c} value={c}>{flag(c)} {COUNTRIES[c]}</option>)}</select></label>
        <label className="field">On channel <span className="hint">Optional — also checks the channel's countries</span>
          <select value={channelId} onChange={e => setChannelId(e.target.value)}><option value="">Any channel</option>{channels.data?.map(c => <option key={c.channel.id} value={c.channel.id}>{c.channel.name}</option>)}</select></label>
        <button className="btn primary" disabled={busy || !id} onClick={() => run(() => api<{ decision: RightsDecision; asset: string; channel?: string }>(`/api/orgs/${org.slug}/rights/check`, { body: { assetId: id, country, channelId: channelId || undefined } })).then(r => r && setResult(r))}>Check now</button>
        {result && (
          <div className={'callout ' + (result.decision.allowed ? 'ok' : 'bad')} style={{ flexDirection: 'column', gap: 8 }}>
            <b style={{ fontSize: 15 }}>{result.decision.allowed ? '✅ Allowed' : '⛔ Blocked'} — {result.asset} in {COUNTRIES[country]}</b>
            {result.decision.checks.map((c, i) => <div key={i} className="row small" style={{ alignItems: 'flex-start' }}><span>{c.passed ? '✓' : '✗'}</span><div><b>{c.label}</b><div>{c.detail}</div></div></div>)}
          </div>
        )}
      </div>
    </section>
  )
}

function Editor({ view, onClose, onDone }: { view: AssetView; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const p = view.rights
  const { busy, run } = useAction()
  const [allowed, setAllowed] = useState(csv(p?.allowedCountries))
  const [blocked, setBlocked] = useState(csv(p?.blockedCountries))
  const [from, setFrom] = useState(p?.validFrom?.slice(0, 10) ?? '')
  const [until, setUntil] = useState(p?.validUntil?.slice(0, 10) ?? '')
  const [ads, setAds] = useState(p?.adsAllowed ?? true)
  const [notes, setNotes] = useState(p?.notes ?? '')
  const editable = org.can('RIGHTS_EDIT')
  return (
    <Modal wide title={`Licence: ${view.asset.title}`} subtitle="Changes apply at once: the live player checks licences every 3 seconds, and the schedule page flags any programme that no longer fits." onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button>{editable && <button className="btn primary" disabled={busy} onClick={() => run(() => api(`/api/orgs/${org.slug}/assets/${view.asset.id}/rights`, { method: 'PUT', body: {
        allowedCountries: allowed, blockedCountries: blocked, validFrom: from ? from + 'T00:00:00Z' : null, validUntil: until ? until + 'T00:00:00Z' : null, adsAllowed: ads, notes } }), 'Licence saved').then(r => r !== undefined && onDone())}>Save licence</button>}</>}>
      {!editable && <div className="callout warn"><Icon name="info" />Your role can view but not change licences.</div>}
      <div className="field"><span className="row">May play in <InfoTip text="Pick 'Worldwide' to allow everywhere, then block specific countries below." /></span><CountryPicker value={allowed} onChange={setAllowed} allowWorldwide /></div>
      <div className="field">Never in (blocks always win)<CountryPicker value={blocked} onChange={setBlocked} /></div>
      <div className="grid g2" style={{ gap: 12 }}>
        <DateTimePicker
          label="Licence Starts"
          value={from}
          onChange={val => setFrom(val ? val.slice(0, 10) : '')}
          showTime={false}
          allowTimeToggle={false}
          showTimezone={false}
          fixed={false}
          placeholder="Select start date..."
        />
        <DateTimePicker
          label="Licence Ends"
          hint="Programme must conclude by midnight UTC"
          value={until}
          onChange={val => setUntil(val ? val.slice(0, 10) : '')}
          showTime={false}
          allowTimeToggle={false}
          showTimezone={false}
          fixed={false}
          placeholder="Select end date..."
        />
      </div>
      <label className="check"><input type="checkbox" checked={ads} onChange={e => setAds(e.target.checked)} />Ads may run during this programme</label>
      <label className="field">Notes<input value={notes} onChange={e => setNotes(e.target.value)} placeholder="Contract reference, contact…" /></label>
    </Modal>
  )
}
