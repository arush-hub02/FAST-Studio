import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useOrg, useSession } from '../../app/App'
import type { Alert, AuditRow, ChannelView } from '../../shared/api'
import {
  ago,
  describeAction,
  ChannelLogo,
  Dropdown,
  type DropdownOption,
  ErrorBox,
  fmtTime,
  Icon,
  InfoTip,
  Loading,
  ProgressRing,
  Status,
  useApi,
} from '../../shared/ui'

type Dashboard = {
  counts: Record<string, number>
  checklist: { key: string; title: string; description: string; done: boolean; link: string }[]
  activity: AuditRow[]
  alerts: Alert[]
}

const CATEGORY_OPTIONS: DropdownOption[] = [
  { value: 'channel', label: 'Channels', icon: 'tv', description: 'Creation, playout status, schedules', badge: 'Linear' },
  { value: 'asset', label: 'Media Library', icon: 'film', description: 'Transcoding, uploads, metadata', badge: 'Assets' },
  { value: 'schedule', label: 'Schedule & EPG', icon: 'calendar', description: 'Auto-fill, gaps, publishing', badge: 'Playout' },
  { value: 'campaign', label: 'SSAI Advertising', icon: 'megaphone', description: 'Ad rules, campaigns, impressions', badge: 'Monetize' },
  { value: 'alert', label: 'Alerts', icon: 'alert', description: 'Incidents, health warnings', badge: 'Health' },
  { value: 'member', label: 'Team', icon: 'users', description: 'Access control, invitations', badge: 'Access' },
]

export default function Home() {
  const org = useOrg()
  const { me } = useSession()
  const d = useApi<Dashboard>(`/api/orgs/${org.slug}/dashboard`, [], 15000)
  const ch = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`, [], 15000)
  const [activityCategories, setActivityCategories] = useState<string[]>([])
  const [channelFilter, setChannelFilter] = useState<string>('ALL')

  if (d.error) return <div className="page"><ErrorBox error={d.error} retry={d.reload} /></div>

  const done = d.data?.checklist.filter(s => s.done).length ?? 0
  const total = d.data?.checklist.length ?? 7
  const next = d.data?.checklist.find(s => !s.done)
  const c = d.data?.counts ?? {}
  const liveCount = c.liveChannels ?? 0
  const alertCount = c.openAlerts ?? 0
  const percentDone = Math.round((done / Math.max(total, 1)) * 100)

  const channelFilterOptions: DropdownOption[] = [
    { value: 'ALL', label: 'All channels', icon: 'tv', description: 'Show all streams in workspace' },
    { value: 'LIVE', label: 'On air only', icon: 'play', description: 'Broadcasting live with ad insertion', badge: `${liveCount} Live`, badgeType: 'live' },
    { value: 'OFFLINE', label: 'Off air', icon: 'sliders', description: 'Draft, stopped, or incomplete' },
  ]

  const displayedChannels = (ch.data ?? []).filter(v => {
    if (channelFilter === 'LIVE') return v.channel.status === 'LIVE'
    if (channelFilter === 'OFFLINE') return v.channel.status !== 'LIVE'
    return true
  })

  const displayedActivity = (d.data?.activity ?? []).filter(a => {
    if (activityCategories.length === 0) return true
    return activityCategories.some(cat => a.action.startsWith(cat + '.'))
  })

  return (
    <div className="page home-page">
      {/* Hero Welcome Header */}
      <div className="home-hero">
        <div className="home-hero-content">
          <div className="home-greeting-row">
            <h1>Good {greeting()}, {me?.name.split(' ')[0]}</h1>
            {liveCount > 0 ? (
              <span className="home-status-chip live">
                <span className="chip-pulse-dot" /> {liveCount} {liveCount === 1 ? 'channel' : 'channels'} on air
              </span>
            ) : alertCount > 0 ? (
              <span className="home-status-chip warn">
                <Icon name="alert" size={13} /> {alertCount} alert needs attention
              </span>
            ) : (
              <span className="home-status-chip ok">
                <Icon name="shieldCheck" size={13} /> All systems operational
              </span>
            )}
          </div>
          <p className="home-hero-subtitle">
            <b>{org.name}</b> · Fast-track linear channels, SSAI monetization, and live distribution.
          </p>
        </div>

        {/* Quick Launch Buttons */}
        <div className="home-hero-actions">
          <Link className="btn primary home-hero-btn" to={`${org.base}/watch`}>
            <Icon name="play" size={15} /> Watch live
          </Link>
          <Link className="btn home-hero-btn" to={`${org.base}/channels`}>
            <Icon name="tv" size={15} /> Channels
          </Link>
          <Link className="btn home-hero-btn" to={`${org.base}/library`}>
            <Icon name="upload" size={15} /> Library
          </Link>
          <Link className="btn ghost home-hero-btn" to={`${org.base}/learn`} title="Documentation">
            <Icon name="book" size={15} /> Guide
          </Link>
        </div>
      </div>

      {/* KPI Stat Cards Grid */}
      <div className="grid g4 home-stats-grid">
        <ModernStat
          label="Channels on air"
          value={c.liveChannels ?? 0}
          sub={`${c.channels ?? 0} channels total`}
          icon="tv"
          color="primary"
        />
        <ModernStat
          label="Video Library"
          value={c.videos ?? 0}
          sub={c.processing ? `${c.processing} still encoding…` : 'All videos ready'}
          icon="film"
          color="cyan"
        />
        <ModernStat
          label="Ad Impressions"
          value={c.impressions?.toLocaleString() ?? '0'}
          sub={`${c.viewerSessions ?? 0} viewer sessions`}
          icon="megaphone"
          color="purple"
          tip="Counted when the prototype player receives an ad decision. These demonstrate SSAI breakfill revenue."
        />
        <ModernStat
          label="System Health"
          value={c.openAlerts ?? 0}
          sub={alertCount > 0 ? 'Needs your attention' : 'Zero issues reported'}
          icon="activity"
          color={alertCount > 0 ? 'amber' : 'emerald'}
        />
      </div>

      {/* Main Split Layout */}
      <div className="split home-main-split">
        {/* Left Column: Channels & Activity */}
        <div className="stack" style={{ gap: 20 }}>
          {/* On air channels */}
          <section className="card home-section-card">
            <div className="card-head">
              <div>
                <h2>On air channels</h2>
                <p>Live streams, active schedule, and real-time playback</p>
              </div>
              <div className="row" style={{ gap: 8 }}>
                {ch.data && ch.data.length > 0 && (
                  <Dropdown
                    options={channelFilterOptions}
                    value={channelFilter}
                    onChange={setChannelFilter}
                    style={{ minWidth: 150 }}
                  />
                )}
                <Link to={`${org.base}/guide`} className="btn sm ghost">
                  <Icon name="grid" size={14} /> TV guide
                </Link>
                <Link to={`${org.base}/channels`} className="btn sm">
                  All channels
                </Link>
              </div>
            </div>

            {!ch.data ? (
              <div className="card-pad"><Loading rows={2} /></div>
            ) : ch.data.length === 0 ? (
              <div className="card-pad home-empty-state">
                <div className="empty-icon-wrap">
                  <Icon name="tv" size={24} />
                </div>
                <h3>No channels created yet</h3>
                <p className="faint">Create your first 24/7 linear channel to start broadcasting content with server-side ads.</p>
                <Link to={`${org.base}/channels`} className="btn primary sm" style={{ marginTop: 10 }}>
                  <Icon name="plus" size={14} /> Create channel
                </Link>
              </div>
            ) : displayedChannels.length === 0 ? (
              <div className="card-pad faint">No channels match this filter.</div>
            ) : (
              <div className="home-channels-list">
                {displayedChannels.map(v => (
                  <div key={v.channel.id} className="home-channel-row">
                    <ChannelLogo name={v.channel.name} color={v.channel.color} size={42} />
                    <div className="home-channel-details grow">
                      <div className="row" style={{ gap: 8, alignItems: 'baseline' }}>
                        <b className="home-channel-name">{v.channel.name}</b>
                        <Status value={v.channel.status} />
                      </div>
                      <div className="home-channel-prog faint ellipsis">
                        {v.now ? (
                          <>
                            <span className="prog-pill now">Now</span>
                            <span className="prog-title">{v.now.title}</span>
                            <span className="prog-time">until {fmtTime(v.now.end)}</span>
                            {v.next && (
                              <span className="prog-next">
                                · Next: <b>{v.next.title}</b> at {fmtTime(v.next.start)}
                              </span>
                            )}
                          </>
                        ) : v.channel.status === 'LIVE' ? (
                          <span className="prog-slate">Between programmes · Ad break filled</span>
                        ) : v.readiness.length ? (
                          <span>To air: {v.readiness[0]}</span>
                        ) : (
                          <span>Not broadcasting</span>
                        )}
                      </div>
                    </div>
                    <div className="home-channel-actions">
                      {v.channel.status === 'LIVE' ? (
                        <Link className="btn sm primary" to={`${org.base}/watch?channel=${v.channel.slug}`}>
                          <Icon name="play" size={14} /> Watch
                        </Link>
                      ) : (
                        <Link className="btn sm" to={`${org.base}/schedule?channel=${v.channel.id}`}>
                          Schedule
                        </Link>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>

          {/* Recent activity feed */}
          <section className="card home-section-card">
            <div className="card-head">
              <div>
                <h2>Recent activity</h2>
                <p>Auditable event history across your studio workspace</p>
              </div>
              <div className="row" style={{ gap: 8 }}>
                <Dropdown
                  placeholder="Filter events…"
                  options={CATEGORY_OPTIONS}
                  value={activityCategories}
                  onChange={setActivityCategories}
                  multiple
                  searchable
                  clearable
                  align="right"
                  emptyText="No matching categories"
                />
                <Link to={`${org.base}/monitoring?tab=audit`} className="btn sm ghost">
                  Full log
                </Link>
              </div>
            </div>

            {!d.data ? (
              <div className="card-pad"><Loading rows={3} /></div>
            ) : d.data.activity.length === 0 ? (
              <div className="card-pad faint">No recent activity recorded yet.</div>
            ) : displayedActivity.length === 0 ? (
              <div className="card-pad faint">No recent activity matching the selected filters.</div>
            ) : (
              <div className="home-activity-feed">
                {displayedActivity.map(a => {
                  const iconName = getActivityIcon(a.action)
                  return (
                    <div key={a.id} className="home-activity-item">
                      <div className={`activity-actor-icon ${a.actor === 'System' ? 'system' : 'user'}`}>
                        <Icon name={iconName} size={13} />
                      </div>
                      <div className="home-activity-body grow">
                        <div>
                          <b className="home-activity-actor">{a.actor}</b>{' '}
                          <span className="home-activity-verb">{describeAction(a.action)}</span>
                          {a.detail && <span className="home-activity-detail"> — {a.detail}</span>}
                        </div>
                      </div>
                      <span className="home-activity-time faint">{ago(a.createdAt)}</span>
                    </div>
                  )
                })}
              </div>
            )}
          </section>
        </div>

        {/* Right Column: Launch Checklist & Needs Attention */}
        <div className="stack" style={{ gap: 20 }}>
          {/* Launch Checklist */}
          <section className="card home-section-card">
            <div className="card-head">
              <div>
                <h2>Launch checklist</h2>
                <p>{done} of {total} steps completed ({percentDone}%)</p>
              </div>
              <ProgressRing value={total ? done / total : 0} size={54} strokeWidth={4} />
            </div>

            {/* Checklist progress bar */}
            <div className="home-checklist-bar-wrap">
              <div className="home-checklist-bar" style={{ width: `${percentDone}%` }} />
            </div>

            {!d.data ? (
              <div className="card-pad"><Loading rows={4} /></div>
            ) : (
              <div className="home-checklist-list">
                {d.data.checklist.map((s, i) => {
                  const isNext = s === next
                  return (
                    <Link
                      key={s.key}
                      to={org.base + s.link}
                      className={`home-checklist-item ${s.done ? 'is-done' : ''} ${isNext ? 'is-next' : ''}`}
                    >
                      <div className={`checklist-num ${s.done ? 'done' : isNext ? 'next' : ''}`}>
                        {s.done ? <Icon name="check" size={13} /> : i + 1}
                      </div>
                      <div className="checklist-text grow">
                        <div className="between">
                          <b className="checklist-title">{s.title}</b>
                          {isNext && <span className="badge info sm">Action</span>}
                        </div>
                        <div className="faint small">{s.description}</div>
                      </div>
                    </Link>
                  )
                })}
              </div>
            )}
          </section>

          {/* Needs attention alerts */}
          <section className="card home-section-card">
            <div className="card-head">
              <div>
                <h2>Needs attention</h2>
                <p>System warnings and alerts</p>
              </div>
              <Link to={`${org.base}/monitoring`} className="btn sm ghost">
                Monitoring
              </Link>
            </div>
            <div className="card-pad stack" style={{ gap: 10 }}>
              {!d.data ? (
                <Loading rows={2} />
              ) : d.data.alerts.length === 0 ? (
                <div className="home-healthy-banner">
                  <div className="healthy-icon">
                    <Icon name="shieldCheck" size={20} />
                  </div>
                  <div>
                    <b>Zero unresolved issues</b>
                    <p className="faint small">Your channels, media streams, and ad breaks are all running normally.</p>
                  </div>
                </div>
              ) : (
                d.data.alerts.map(a => (
                  <Link
                    key={a.id}
                    to={org.base + a.link}
                    className="callout warn home-alert-callout"
                    style={{ textDecoration: 'none' }}
                  >
                    <Icon name="alert" size={18} />
                    <div className="grow">
                      <b>{a.title}</b>
                      <div className="small faint">{a.suggestion}</div>
                    </div>
                    <span className="btn sm ghost" style={{ alignSelf: 'center' }}>
                      Resolve →
                    </span>
                  </Link>
                ))
              )}
            </div>
          </section>
        </div>
      </div>
    </div>
  )
}

function ModernStat({
  label,
  value,
  sub,
  icon,
  tip,
  color = 'indigo',
  badge,
  badgeType = 'default',
}: {
  label: string
  value: number | string
  sub: string
  icon: string
  tip?: string
  color?: string
  badge?: string
  badgeType?: 'live' | 'ok' | 'warn' | 'accent' | 'default'
}) {
  return (
    <div className={`card home-stat-card color-${color}`}>
      <div className="between" style={{ alignItems: 'flex-start' }}>
        <div className={`home-stat-icon-wrap bg-${color}`}>
          <Icon name={icon} size={18} />
        </div>
        {badge && (
          <span className={`home-stat-badge ${badgeType}`}>
            {badgeType === 'live' && <span className="stat-pulse-dot" />}
            {badge}
          </span>
        )}
      </div>
      <div className="home-stat-body">
        <div className="home-stat-value">{value}</div>
        <div className="home-stat-label">
          {label} {tip && <InfoTip text={tip} />}
        </div>
        <div className="home-stat-sub faint small">{sub}</div>
      </div>
    </div>
  )
}

function greeting() {
  const h = new Date().getHours()
  return h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening'
}

function getActivityIcon(action: string) {
  if (action.startsWith('channel.')) return 'tv'
  if (action.startsWith('asset.')) return 'film'
  if (action.startsWith('schedule.')) return 'calendar'
  if (action.startsWith('campaign.')) return 'megaphone'
  if (action.startsWith('alert.')) return 'alert'
  if (action.startsWith('member.')) return 'users'
  if (action.startsWith('rights.')) return 'shield'
  return 'activity'
}
