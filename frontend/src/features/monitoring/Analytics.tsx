import { useOrg } from '../../app/App'
import { Empty, Icon, InfoTip, Loading, PageHead, Sim, useApi } from '../../shared/ui'

type A = {
  provenance: string; formula: string
  channels: { channelId: string; channel: string; sessions: number; impressions: number; completes: number; adSeconds: number; slateSeconds: number; fillRate: number | null; completionRate: number | null; estimatedRevenue: number }[]
  campaigns: { campaign: string; advertiser: string; impressions: number; completes: number; cpm: number; estimatedRevenue: number }[]
  days: { day: string; impressions: number; sessions: number }[]
}
const pct = (v: number | null) => v === null ? 'N/A' : Math.round(v * 100) + '%'

export default function Analytics() {
  const org = useOrg()
  const a = useApi<A>(`/api/orgs/${org.slug}/analytics`, [], 20000)
  const d = a.data
  const total = d?.channels.reduce((s, c) => ({ sessions: s.sessions + c.sessions, imp: s.imp + c.impressions, ad: s.ad + c.adSeconds, slate: s.slate + c.slateSeconds, rev: s.rev + Number(c.estimatedRevenue) }),
    { sessions: 0, imp: 0, ad: 0, slate: 0, rev: 0 })
  const max = Math.max(1, ...(d?.days.map(x => x.impressions) ?? [1]))
  return (
    <div className="page">
      <PageHead title="Analytics" subtitle="Results from playback of this prototype. Every number here comes from a recorded event — nothing is invented." />
      <div className="callout sim"><Icon name="sparkle" /><div><b>Where these numbers come from:</b> {d?.formula ?? '…'}</div></div>
      {!d || !total ? <Loading rows={4} /> : <>
        <div className="grid g4">
          <div className="card stat"><div className="label">Viewer sessions</div><div className="value">{total.sessions}</div><div className="sub">players started</div></div>
          <div className="card stat"><div className="label">Ads shown</div><div className="value">{total.imp}</div><div className="sub">ad starts delivered</div></div>
          <div className="card stat"><div className="label">Fill rate <InfoTip text="Ad seconds actually filled with paid or house ads ÷ ad-break seconds we were responsible for. N/A until a break has played." /></div>
            <div className="value">{pct(total.ad + total.slate ? total.ad / (total.ad + total.slate) : null)}</div><div className="sub">{total.slate}s of breaks went unfilled</div></div>
          <div className="card stat"><div className="label">Est. ad value <Sim text="Estimate" /></div><div className="value">${total.rev.toFixed(2)}</div><div className="sub">not money received</div></div>
        </div>
        <div className="split">
          <section className="card">
            <div className="card-head"><div><h2>By channel</h2><p>How well each channel's breaks were filled</p></div></div>
            <table className="table"><thead><tr><th>Channel</th><th>Sessions</th><th>Ads shown</th><th>Completion</th><th>Fill rate</th><th>Est. value</th></tr></thead>
              <tbody>{d.channels.map(c => (
                <tr key={c.channelId}><td><b>{c.channel}</b></td><td>{c.sessions}</td><td>{c.impressions}</td><td>{pct(c.completionRate)}</td>
                  <td><div className="row"><div className="progress" style={{ width: 80 }}><div style={{ width: pct(c.fillRate) === 'N/A' ? 0 : pct(c.fillRate) }} /></div>{pct(c.fillRate)}</div></td>
                  <td>${Number(c.estimatedRevenue).toFixed(2)}</td></tr>
              ))}</tbody></table>
          </section>
          <section className="card">
            <div className="card-head"><div><h2>Ads shown, last 7 days</h2></div></div>
            <div className="card-pad">
              {d.days.every(x => x.impressions === 0) ? <Empty icon="chart" title="No ad views yet" text="Open Watch live and let an ad break play — the numbers appear here." /> : (
                <div className="bars" role="img" aria-label={'Ads shown per day: ' + d.days.map(x => `${x.day} ${x.impressions}`).join(', ')}>
                  {d.days.map(x => <div key={x.day} className="bar"><span className="faint">{x.impressions}</span><div style={{ height: `${(x.impressions / max) * 100}%` }} /><span className="faint">{new Date(x.day).toLocaleDateString(undefined, { weekday: 'short' })}</span></div>)}
                </div>
              )}
            </div>
          </section>
        </div>
        <section className="card">
          <div className="card-head"><div><h2>By campaign</h2><p>What each advertiser received</p></div></div>
          <table className="table"><thead><tr><th>Campaign</th><th>Advertiser</th><th>Shown</th><th>Completed</th><th>CPM</th><th>Est. value</th></tr></thead>
            <tbody>{d.campaigns.map(c => <tr key={c.campaign}><td><b>{c.campaign}</b></td><td>{c.advertiser}</td><td>{c.impressions}</td><td>{c.completes}</td><td>${Number(c.cpm).toFixed(2)}</td><td>${Number(c.estimatedRevenue).toFixed(2)}</td></tr>)}</tbody></table>
        </section>
      </>}
    </div>
  )
}
