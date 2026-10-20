import { Link } from 'react-router-dom'
import { useOrg, useSession } from '../app'
import type { Alert, AuditRow, ChannelView } from '../api'
import { ago, ChannelLogo, ErrorBox, fmtTime, Icon, InfoTip, Loading, ProgressRing, Status, useApi } from '../ui'

type Dashboard = {
  counts: Record<string, number>
  checklist: { key: string; title: string; description: string; done: boolean; link: string }[]
  activity: AuditRow[]
  alerts: Alert[]
}

export default function Home() {
  const org = useOrg()
  const { me } = useSession()
  const d = useApi<Dashboard>(`/api/orgs/${org.slug}/dashboard`, [], 15000)
  const ch = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`, [], 15000)
  if (d.error) return <div className="page"><ErrorBox error={d.error} retry={d.reload} /></div>
  const done = d.data?.checklist.filter(s => s.done).length ?? 0
  const total = d.data?.checklist.length ?? 7
  const next = d.data?.checklist.find(s => !s.done)
  const c = d.data?.counts ?? {}
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Good {greeting()}, {me?.name.split(' ')[0]}</h1>
          <p>{org.name} · Here's what's on air and what to do next.</p>
        </div>
        <div className="row-wrap">
          <Link className="btn" to={`${org.base}/learn`}><Icon name="book" />New to FAST? Start here</Link>
          <Link className="btn primary" to={`${org.base}/watch`}><Icon name="play" />Watch live</Link>
        </div>
      </div>

      <div className="grid g4">
        <Stat label="Channels on air" value={c.liveChannels} sub={`${c.channels ?? 0} channels in total`} icon="tv" />
        <Stat label="Videos" value={c.videos} sub={c.processing ? `${c.processing} still processing…` : 'all processed'} icon="film" />
        <Stat label="Ads shown (demo)" value={c.impressions} sub={`${c.viewerSessions ?? 0} viewer sessions`} icon="megaphone"
          tip="Counted when the prototype's own player actually received an ad. These are demo views, not a real audience." />
        <Stat label="Open alerts" value={c.openAlerts} sub={c.openAlerts ? 'needs attention' : 'all clear'} icon="alert" />
      </div>

      <div className="split">
        <div className="stack" style={{ gap: 20 }}>
          <section className="card">
            <div className="card-head">
              <div><h2>On air now</h2><p>Live channels and what they're playing right now</p></div>
              <Link to={`${org.base}/channels`} className="btn sm">All channels</Link>
            </div>
            {!ch.data ? <div className="card-pad"><Loading /></div> : ch.data.length === 0 ? (
              <div className="card-pad"><p className="muted">No channels yet. <Link to={`${org.base}/channels`}>Create your first channel</Link>.</p></div>
            ) : ch.data.map(v => (
              <div key={v.channel.id} className="step">
                <ChannelLogo name={v.channel.name} color={v.channel.color} />
                <div className="grow">
                  <div className="row"><b>{v.channel.name}</b><Status value={v.channel.status} /></div>
                  <div className="faint ellipsis">
                    {v.now ? <>Now: <b style={{ color: 'var(--text)' }}>{v.now.title}</b> until {fmtTime(v.now.end)}{v.next && <> · Next: {v.next.title} at {fmtTime(v.next.start)}</>}</>
                      : v.channel.status === 'LIVE' ? 'Between programmes / ad break' : v.readiness.length ? 'To go live: ' + v.readiness[0] : 'Not on air'}
                  </div>
                </div>
                {v.channel.status === 'LIVE'
                  ? <Link className="btn sm" to={`${org.base}/watch?channel=${v.channel.slug}`}><Icon name="play" size={15} />Watch</Link>
                  : <Link className="btn sm" to={`${org.base}/schedule?channel=${v.channel.id}`}>Open schedule</Link>}
              </div>
            ))}
          </section>

          <section className="card">
            <div className="card-head"><div><h2>Recent activity</h2><p>Every change is recorded with who did it</p></div>
              <Link to={`${org.base}/monitoring?tab=audit`} className="btn sm">Full log</Link></div>
            {!d.data ? <div className="card-pad"><Loading /></div> : d.data.activity.map(a => (
              <div key={a.id} className="step" style={{ padding: '10px 20px' }}>
                <div className="avatar" style={{ background: '#e0e7ff', color: '#4338ca', width: 28, height: 28, fontSize: 11 }}>{a.actor === 'System' ? 'S' : a.actor.split(' ').map(w => w[0]).join('')}</div>
                <div className="grow small"><b>{a.actor}</b> <span className="muted">{describe(a.action)}</span> {a.detail && <span className="faint">— {a.detail}</span>}</div>
                <span className="faint">{ago(a.createdAt)}</span>
              </div>
            ))}
          </section>
        </div>

        <div className="stack" style={{ gap: 20 }}>
          <section className="card">
            <div className="card-head">
              <div><h2>Launch checklist</h2><p>The path from a video file to a paying channel</p></div>
              <ProgressRing value={total ? done / total : 0} size={56} />
            </div>
            {!d.data ? <div className="card-pad"><Loading /></div> : (
              <div className="steps">
                {d.data.checklist.map((s, i) => (
                  <Link key={s.key} to={org.base + s.link} className={'step' + (s.done ? ' done' : '')} style={{ color: 'inherit', textDecoration: 'none', background: s === next ? '#fafaff' : undefined }}>
                    <div className="step-num">{s.done ? <Icon name="check" size={15} /> : i + 1}</div>
                    <div className="grow"><b>{s.title}</b><div className="faint">{s.description}</div></div>
                    {s === next && <span className="badge info">Next</span>}
                  </Link>
                ))}
              </div>
            )}
          </section>
          <section className="card">
            <div className="card-head"><h2>Needs attention</h2><Link to={`${org.base}/monitoring`} className="btn sm">Monitoring</Link></div>
            <div className="card-pad stack">
              {!d.data ? <Loading rows={2} /> : d.data.alerts.length === 0 ? <p className="muted"><Icon name="check" size={15} /> Nothing needs attention right now.</p>
                : d.data.alerts.map(a => (
                  <Link key={a.id} to={org.base + a.link} className="callout warn" style={{ textDecoration: 'none' }}>
                    <Icon name="alert" /><div><b>{a.title}</b><div className="small">{a.suggestion}</div></div>
                  </Link>
                ))}
            </div>
          </section>
        </div>
      </div>
    </div>
  )
}

function Stat({ label, value, sub, icon, tip }: { label: string; value?: number; sub: string; icon: string; tip?: string }) {
  return (
    <div className="card stat">
      <div className="label"><Icon name={icon} size={15} />{label}{tip && <InfoTip text={tip} />}</div>
      <div className="value">{value ?? '—'}</div>
      <div className="sub">{sub}</div>
    </div>
  )
}

function greeting() {
  const h = new Date().getHours()
  return h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening'
}

const VERBS: Record<string, string> = {
  'auth.login': 'signed in', 'asset.create': 'added a video', 'asset.upload': 'uploaded a file', 'asset.update': 'edited a video', 'asset.archive': 'archived a video',
  'rights.update': 'changed a licence', 'channel.create': 'created a channel', 'channel.update': 'edited a channel', 'channel.live': 'took a channel live',
  'channel.stopped': 'stopped a channel', 'channel.archived': 'archived a channel', 'schedule.publish': 'published a schedule', 'schedule.autofill': 'auto-filled a schedule',
  'schedule.add': 'added to a schedule', 'schedule.remove': 'removed from a schedule', 'schedule.close_gaps': 'closed schedule gaps', 'schedule.clear': 'cleared a schedule',
  'schedule.restore': 'restored an old schedule', 'campaign.create': 'created a campaign', 'campaign.update': 'edited a campaign', 'campaign.paused': 'paused a campaign',
  'campaign.active': 'resumed a campaign', 'campaign.ended': 'ended a campaign', 'distribution.publish': 'sent a channel to a destination',
  'destination.create': 'added a destination', 'alert.acknowledge': 'acknowledged an alert', 'alert.resolve': 'resolved an alert', 'member.invite': 'invited a member',
  'member.role': 'changed a role', 'member.remove': 'removed a member',
}
export function describe(action: string) { return VERBS[action] ?? action }
