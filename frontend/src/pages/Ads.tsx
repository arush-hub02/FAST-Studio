import { useState } from 'react'
import { api, type AssetView, type ChannelView, type Decision } from '../api'
import { useOrg } from '../app'
import { COUNTRIES, Countries, CountryPicker, csv, Empty, ErrorBox, flag, fmtDate, fmtDayTime, fmtDur, Icon, InfoTip, Loading, Modal, PageHead, Sim, Status, useAction, useApi } from '../ui'

type Campaign = {
  id: string; name: string; advertiser: string; status: string; startsAt: string; endsAt: string; territories: string; priority: number; cpm: number
  creativeAssetId: string; creativeTitle: string; creativeStatus: string; creativeDurationMs: number; creativeColor: string; mediaKey?: string; impressions: number; completes: number; thumbUrl?: string
}
type Simulation = { breakStart: string; breakSeconds: number; ownership: string; programme: string; decision: Decision }

export default function Ads() {
  const org = useOrg()
  const list = useApi<Campaign[]>(`/api/orgs/${org.slug}/campaigns`, [], 15000)
  const [editing, setEditing] = useState<Campaign | 'new' | null>(null)
  const { busy, run } = useAction()
  const setStatus = (c: Campaign, status: string) => run(() => api(`/api/orgs/${org.slug}/campaigns/${c.id}/status`, { body: { status } }), `${c.name}: ${status.toLowerCase()}`).then(list.reload)
  const active = list.data?.filter(c => c.status === 'ACTIVE').length ?? 0
  const impressions = list.data?.reduce((s, c) => s + c.impressions, 0) ?? 0
  return (
    <div className="page">
      <PageHead title="Ads" tip="Advertisers book campaigns. During each ad break, the ad server picks eligible campaigns for that viewer and fits them into the break."
        subtitle="Campaigns are what advertisers buy. Each one has a video creative, dates, target countries, a priority and a price per thousand views (CPM)."
        actions={org.can('ADS_EDIT') && <button className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" />New campaign</button>} />
      <div className="grid g4">
        <div className="card stat"><div className="label">Active campaigns</div><div className="value">{active}</div><div className="sub">{list.data?.length ?? 0} in total</div></div>
        <div className="card stat"><div className="label">Ads shown <InfoTip text="An ad counts as shown when its first piece was actually delivered to a player in this demo." /></div><div className="value">{impressions}</div><div className="sub">from demo playback</div></div>
        <div className="card stat"><div className="label">Completed</div><div className="value">{list.data?.reduce((s, c) => s + c.completes, 0) ?? 0}</div><div className="sub">watched to the end</div></div>
        <div className="card stat"><div className="label">Est. value <Sim text="Estimate" /></div>
          <div className="value">${(list.data?.reduce((s, c) => s + c.impressions * c.cpm / 1000, 0) ?? 0).toFixed(2)}</div><div className="sub">shown × CPM ÷ 1000 — not money received</div></div>
      </div>
      <div className="split">
        <div className="stack">
          {list.error ? <ErrorBox error={list.error} /> : !list.data ? <Loading /> : list.data.length === 0 ? <div className="card"><Empty icon="megaphone" title="No campaigns yet" /></div> :
            list.data.map(c => (
              <section key={c.id} className="card card-pad row" style={{ alignItems: 'stretch', gap: 16 }}>
                <div className="thumb" style={{ width: 150, flexShrink: 0, background: c.creativeColor }}>
                  {c.thumbUrl && c.creativeStatus === 'READY' ? <img src={c.thumbUrl} alt="" /> : <Icon name="megaphone" />}
                  <span className="dur">{fmtDur(c.creativeDurationMs)}</span>
                </div>
                <div className="grow stack" style={{ gap: 6 }}>
                  <div className="between"><div><h3>{c.name}</h3><div className="faint">{c.advertiser}</div></div><Status value={c.status} /></div>
                  <div className="row-wrap small">
                    <span className="chip">CPM ${Number(c.cpm).toFixed(2)}</span><span className="chip">Priority {c.priority}</span>
                    <Countries codes={csv(c.territories)} /><span className="faint">{fmtDate(c.startsAt)} → {fmtDate(c.endsAt)}</span>
                  </div>
                  <div className="between">
                    <span className="small"><b>{c.impressions}</b> shown · <b>{c.completes}</b> completed{c.creativeStatus !== 'READY' && <> · <span className="badge info">creative {c.creativeStatus.toLowerCase()}</span></>}</span>
                    {org.can('ADS_EDIT') && <div className="row">
                      {c.status === 'ACTIVE' && <button className="btn sm" disabled={busy} onClick={() => setStatus(c, 'PAUSED')}>Pause</button>}
                      {c.status === 'PAUSED' && <button className="btn sm" disabled={busy} onClick={() => setStatus(c, 'ACTIVE')}>Resume</button>}
                      <button className="btn sm ghost" onClick={() => setEditing(c)}>Edit</button>
                    </div>}
                  </div>
                </div>
              </section>
            ))}
        </div>
        <Simulator />
      </div>
      {editing && <CampaignModal c={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); list.reload() }} />}
    </div>
  )
}

function Simulator() {
  const org = useOrg()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const live = channels.data?.filter(c => c.channel.publishedVersionId) ?? []
  const [channelId, setChannelId] = useState('')
  const [country, setCountry] = useState('GB')
  const [sim, setSim] = useState<Simulation | null>(null)
  const { busy, run } = useAction()
  const id = channelId || live[0]?.channel.id
  const d = sim?.decision
  return (
    <section className="card">
      <div className="card-head"><div><h2>Ad decision simulator</h2><p>Which ads would fill the next break — and why the others didn't</p></div><Sim /></div>
      <div className="card-pad stack">
        <div className="grid g2">
          <label className="field">Channel<select value={id} onChange={e => setChannelId(e.target.value)}>{live.map(c => <option key={c.channel.id} value={c.channel.id}>{c.channel.name}</option>)}</select></label>
          <label className="field">Viewer in<select value={country} onChange={e => setCountry(e.target.value)}>{Object.keys(COUNTRIES).filter(c => c !== 'WW').map(c => <option key={c} value={c}>{flag(c)} {COUNTRIES[c]}</option>)}</select></label>
        </div>
        <button className="btn primary" disabled={busy || !id} onClick={() => run(() => api<Simulation>(`/api/orgs/${org.slug}/ads/simulate`, { body: { channelId: id, country } })).then(r => r && setSim(r))}>Run ad decision</button>
        {sim && d && <>
          <div className="faint">Next break {fmtDayTime(sim.breakStart)} · {sim.breakSeconds}s · {sim.programme} · {sim.ownership === 'DISTRIBUTOR' ? "platform's break (shown for illustration)" : 'our break'}</div>
          <div className="ad-bar">
            {d.slots.map(s => <div key={s.campaignId} style={{ flex: s.durationMs, background: '#4f46e5' }} title={s.campaign}>{s.campaign.split(' ')[0]} {s.durationMs / 1000}s</div>)}
            {d.breakMs > d.filledMs && <div className="slate" style={{ flex: d.breakMs - d.filledMs }}>slate {(d.breakMs - d.filledMs) / 1000}s</div>}
          </div>
          <div className="callout ok"><Icon name="check" />{d.summary}</div>
          {d.excluded.length > 0 && <div className="stack" style={{ gap: 6 }}><b className="small">Not chosen</b>
            {d.excluded.map((x, i) => <div key={i} className="small"><span className="chip bad">✗</span> <b>{x.campaign}</b> — {x.reason}</div>)}</div>}
        </>}
      </div>
    </section>
  )
}

function CampaignModal({ c, onClose, onDone }: { c: Campaign | null; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const creatives = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets?kind=AD`)
  const { busy, run } = useAction()
  const day = (d: Date) => d.toISOString().slice(0, 10)
  const [f, setF] = useState({
    name: c?.name ?? '', advertiser: c?.advertiser ?? '', creativeAssetId: c?.creativeAssetId ?? '', territories: c ? csv(c.territories) : ['WW'],
    startsAt: c ? c.startsAt.slice(0, 10) : day(new Date()), endsAt: c ? c.endsAt.slice(0, 10) : day(new Date(Date.now() + 30 * 86400000)),
    priority: c?.priority ?? 5, cpm: c?.cpm ?? 12,
  })
  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const creativeId = f.creativeAssetId || creatives.data?.[0]?.asset.id || ''
  const save = () => api(`/api/orgs/${org.slug}/campaigns${c ? '/' + c.id : ''}`, { method: c ? 'PUT' : 'POST', body: {
    ...f, creativeAssetId: creativeId, startsAt: f.startsAt + 'T00:00:00Z', endsAt: f.endsAt + 'T23:59:59Z', priority: +f.priority, cpm: +f.cpm } })
  return (
    <Modal wide title={c ? 'Edit campaign' : 'New campaign'} onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn primary" disabled={busy || !creativeId} onClick={() => run(save, 'Campaign saved').then(r => r !== undefined && onDone())}>Save campaign</button></>}>
      <div className="grid g2">
        <label className="field">Campaign name<input value={f.name} onChange={e => set('name', e.target.value)} /></label>
        <label className="field">Advertiser<input value={f.advertiser} onChange={e => set('advertiser', e.target.value)} /></label>
      </div>
      <label className="field">Ad creative<span className="hint">Add new creatives in the Library (type: Ad creative)</span>
        <select value={creativeId} onChange={e => set('creativeAssetId', e.target.value)}>
          {creatives.data?.map(a => <option key={a.asset.id} value={a.asset.id}>{a.asset.title} · {fmtDur(a.asset.durationMs)} · {a.asset.status.toLowerCase()}</option>)}
        </select></label>
      <div className="field">Target countries<CountryPicker value={f.territories} onChange={v => set('territories', v)} allowWorldwide /></div>
      <div className="grid g4">
        <label className="field">Starts<input type="date" value={f.startsAt} onChange={e => set('startsAt', e.target.value)} /></label>
        <label className="field">Ends<input type="date" value={f.endsAt} onChange={e => set('endsAt', e.target.value)} /></label>
        <label className="field"><span className="row">Priority <InfoTip text="Higher priority campaigns are placed first in each break (10 = highest). House ads usually sit at 1 to fill leftover time." /></span>
          <input type="number" min={1} max={10} value={f.priority} onChange={e => set('priority', e.target.value)} /></label>
        <label className="field"><span className="row">CPM ($) <InfoTip text="Cost per mille: what the advertiser pays per 1,000 ads shown. Used only to estimate value in this demo." /></span>
          <input type="number" min={0} step={0.5} value={f.cpm} onChange={e => set('cpm', e.target.value)} /></label>
      </div>
    </Modal>
  )
}
