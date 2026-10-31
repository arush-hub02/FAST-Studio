import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type ChannelView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Confirm,
  COUNTRIES,
  csv,
  Dropdown,
  type DropdownOption,
  Empty,
  ErrorBox,
  fmtDayTime,
  fmtTime,
  Icon,
  InfoTip,
  Loading,
  PageHead,
  Status,
  useAction,
  useApi,
} from '../../shared/ui'
import { ChannelWizard } from './ChannelWizard'
import { ChannelSettings } from './ChannelSettings'

function getPlayoutProgress(start?: string, end?: string, nowMs = Date.now()): number {
  if (!start || !end) return 45
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (isNaN(s) || isNaN(e) || e <= s) return 50
  if (nowMs <= s) return 8
  if (nowMs >= e) return 96
  return Math.min(96, Math.max(8, Math.round(((nowMs - s) / (e - s)) * 100)))
}

function ChannelTerritoriesBadge({ codes }: { codes: string[] }) {
  if (!codes.length) return <span className="faint">none</span>

  const [open, setOpen] = useState(false)
  const [placement, setPlacement] = useState<'right' | 'bottom' | 'top' | 'left'>('right')
  const wrapperRef = useRef<HTMLDivElement>(null)

  const visible = codes.slice(0, 2)
  const remaining = codes.slice(2)

  const handleOpen = () => {
    if (wrapperRef.current) {
      const rect = wrapperRef.current.getBoundingClientRect()
      const popoverWidth = 260
      const popoverHeight = 220
      const spaceRight = window.innerWidth - rect.right
      const spaceLeft = rect.left
      const spaceBottom = window.innerHeight - rect.bottom
      const spaceTop = rect.top

      // Space-aware positioning preference:
      // 1. Right side if space permits (user requested opening on right if space available)
      if (spaceRight >= popoverWidth + 14) {
        setPlacement('right')
      } else if (spaceBottom >= popoverHeight + 14) {
        setPlacement('bottom')
      } else if (spaceLeft >= popoverWidth + 14) {
        setPlacement('left')
      } else if (spaceTop >= popoverHeight + 14) {
        setPlacement('top')
      } else {
        setPlacement('right')
      }
    }
    setOpen(true)
  }

  return (
    <div
      ref={wrapperRef}
      className={`channel-territories-wrapper ${open ? 'open' : ''}`}
      onMouseEnter={handleOpen}
      onMouseLeave={() => setOpen(false)}
    >
      <div className="channel-territories-row">
        {visible.map(c => (
          <span key={c} className="channel-country-pill">
            {c}
          </span>
        ))}
        {remaining.length > 0 && (
          <button
            type="button"
            className="channel-territories-more"
            onClick={e => {
              e.stopPropagation()
              if (!open) handleOpen()
              else setOpen(false)
            }}
            onFocus={handleOpen}
            onBlur={() => setOpen(false)}
            aria-label={`View all ${codes.length} broadcast territories`}
          >
            +{remaining.length}
          </button>
        )}
      </div>

      {/* Custom Territories Viewer (Space-aware flyout with solid background) */}
      {open && (
        <div
          className={`channel-territories-viewer placement-${placement}`}
          role="dialog"
          aria-label="Broadcast Territories"
          onClick={e => e.stopPropagation()}
        >
          <div className="channel-territories-viewer-head">
            <div className="channel-territories-viewer-title">
              <Icon name="globe" size={13} />
              <span>Broadcast Territories</span>
            </div>
            <span className="channel-territories-viewer-count">
              {codes.length} Countries
            </span>
          </div>

          <div className="channel-territories-viewer-list">
            {codes.map(c => (
              <div key={c} className="channel-territories-viewer-item">
                <span className="channel-territories-viewer-code">{c}</span>
                <span className="channel-territories-viewer-name">
                  {COUNTRIES[c] ?? c}
                </span>
              </div>
            ))}
          </div>

          <div className="channel-territories-viewer-footer">
            Licensed for 24/7 linear playout & SSAI delivery
          </div>
        </div>
      )}
    </div>
  )
}

export default function Channels() {
  const org = useOrg()
  const list = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`, [], 20000)
  const [wizard, setWizard] = useState(false)
  const [editing, setEditing] = useState<ChannelView | null>(null)
  const [confirm, setConfirm] = useState<{ v: ChannelView; action: 'stop' | 'archive' } | null>(null)
  const [q, setQ] = useState('')
  const [statusFilter, setStatusFilter] = useState<'all' | 'live' | 'draft' | 'stopped'>('all')
  const [sortBy, setSortBy] = useState('NAME_ASC')
  const [nowTick, setNowTick] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNowTick(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const { busy, run } = useAction()
  const act = (v: ChannelView, action: string, msg: string) =>
    run(() => api(`/api/orgs/${org.slug}/channels/${v.channel.id}/${action}`, { method: 'POST' }), msg).then(list.reload)

  const allChannels = list.data ?? []

  // Stable catalog-wide KPI stats (matches Library.tsx behavior)
  const stats = useMemo(() => {
    const liveCount = allChannels.filter(v => v.channel.status === 'LIVE').length
    const draftCount = allChannels.filter(v => v.channel.status === 'DRAFT').length
    const stoppedCount = allChannels.filter(v => v.channel.status === 'STOPPED' || v.channel.status === 'ARCHIVED').length
    const allTerritories = new Set<string>()
    let totalAdSecs = 0

    allChannels.forEach(v => {
      csv(v.channel.territories).forEach(t => allTerritories.add(t))
      totalAdSecs += v.channel.adSecondsPerHour || 0
    })

    const avgAdMin = allChannels.length ? Math.round(totalAdSecs / allChannels.length / 60) : 8

    return {
      total: allChannels.length,
      liveCount,
      draftCount,
      stoppedCount,
      territoriesCount: allTerritories.size,
      avgAdMin,
    }
  }, [allChannels])

  const isFiltered = Boolean(statusFilter !== 'all' || q.trim())

  // Filtered and sorted channels
  const filteredChannels = useMemo(() => {
    let res = [...allChannels]

    if (statusFilter === 'live') {
      res = res.filter(v => v.channel.status === 'LIVE')
    } else if (statusFilter === 'draft') {
      res = res.filter(v => v.channel.status === 'DRAFT')
    } else if (statusFilter === 'stopped') {
      res = res.filter(v => v.channel.status === 'STOPPED' || v.channel.status === 'ARCHIVED')
    }

    if (q.trim()) {
      const query = q.toLowerCase().trim()
      res = res.filter(v =>
        v.channel.name.toLowerCase().includes(query) ||
        v.channel.slug.toLowerCase().includes(query) ||
        (v.channel.genre && v.channel.genre.toLowerCase().includes(query)) ||
        (v.channel.territories && v.channel.territories.toLowerCase().includes(query)) ||
        (v.channel.timezone && v.channel.timezone.toLowerCase().includes(query))
      )
    }

    if (sortBy === 'NAME_ASC') {
      res.sort((a, b) => a.channel.name.localeCompare(b.channel.name))
    } else if (sortBy === 'NAME_DESC') {
      res.sort((a, b) => b.channel.name.localeCompare(a.channel.name))
    } else if (sortBy === 'AD_DESC') {
      res.sort((a, b) => (b.channel.adSecondsPerHour || 0) - (a.channel.adSecondsPerHour || 0))
    } else if (sortBy === 'AD_ASC') {
      res.sort((a, b) => (a.channel.adSecondsPerHour || 0) - (b.channel.adSecondsPerHour || 0))
    }

    return res
  }, [allChannels, statusFilter, q, sortBy])

  const sortOptions: DropdownOption[] = [
    { value: 'NAME_ASC', label: 'Channel Name (A → Z)', icon: 'tv', description: 'Alphabetical order' },
    { value: 'NAME_DESC', label: 'Channel Name (Z → A)', icon: 'tv', description: 'Reverse alphabetical' },
    { value: 'AD_DESC', label: 'Ad Load (Highest first)', icon: 'megaphone', description: 'Highest commercial density' },
    { value: 'AD_ASC', label: 'Ad Load (Lowest first)', icon: 'megaphone', description: 'Lowest commercial density' },
  ]

  const statusDropdownOptions: DropdownOption[] = [
    { value: 'all', label: 'All Playout States', icon: 'tv', description: 'Show all linear channels' },
    { value: 'live', label: 'On Air (Broadcasting)', icon: 'activity', description: 'Currently streaming live 24/7', badge: `${stats.liveCount}`, badgeType: 'ok' },
    { value: 'draft', label: 'Draft & Staging', icon: 'upload', description: 'Awaiting timetable publish', badge: `${stats.draftCount}`, badgeType: 'warn' },
    { value: 'stopped', label: 'Standby / Stopped', icon: 'stop', description: 'Off-air or archived channels', badge: `${stats.stoppedCount}`, badgeType: 'default' },
  ]

  return (
    <div className="page library-page">
      {/* Header matching Library.tsx */}
      <PageHead
        title="Linear Channels"
        tip="A FAST channel is a continuous 24/7 TV service: brand identity, licensed territories, schedule timetable, and programmatic ad insertion."
        subtitle="Manage 24/7 master linear TV playout services, broadcast timetables, and automated SSAI ad insertion."
        actions={
          org.can('CHANNELS_EDIT') && (
            <button type="button" className="btn primary" onClick={() => setWizard(true)}>
              <Icon name="plus" size={16} /> New Channel
            </button>
          )
        }
      />

      {/* Top High-Level Metric Cards (Harmonized with Media Library UI) */}
      <div className="grid g4 library-kpi-grid">
        <div className="card library-kpi-card color-primary">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-primary">
              <Icon name="tv" size={18} />
            </span>
            <span className="badge primary sm">{isFiltered ? 'Filtered Selection' : 'Broadcasting'}</span>
          </div>
          <div>
            <div className="library-kpi-val">
              {isFiltered ? (
                <>
                  {filteredChannels.length}{' '}
                  <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                    / {stats.total}
                  </span>
                </>
              ) : (
                stats.total
              )}
            </div>
            <div className="library-kpi-label">
              {isFiltered ? 'Matching Channels' : 'Total Linear Channels'}
            </div>
            <div className="library-kpi-sub faint small">
              {isFiltered ? `Showing ${filteredChannels.length} of ${stats.total} in network` : '24/7 master playout services'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-emerald">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-emerald">
              <Icon name="activity" size={18} />
            </span>
            <span className="badge ok sm">
              {stats.liveCount > 0 ? 'On Air' : 'Standby'}
            </span>
          </div>
          <div>
            <div className="library-kpi-val">
              {stats.liveCount}
              {stats.liveCount > 0 && (
                <span className="channel-sound-waves" title="Broadcasting on air" style={{ marginLeft: 6 }}>
                  <span /><span /><span /><span />
                </span>
              )}
            </div>
            <div className="library-kpi-label">Live Playout (On Air)</div>
            <div className="library-kpi-sub faint small">
              {stats.liveCount > 0 ? `${stats.liveCount} channels actively streaming` : 'All channels in staging'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-cyan">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-cyan">
              <Icon name="globe" size={18} />
            </span>
            <span className="badge info sm">{stats.territoriesCount} Covered</span>
          </div>
          <div>
            <div className="library-kpi-val">{stats.territoriesCount}</div>
            <div className="library-kpi-label">Broadcast Regions</div>
            <div className="library-kpi-sub faint small">Licensed distribution markets</div>
          </div>
        </div>

        <div className="card library-kpi-card color-purple">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-purple">
              <Icon name="megaphone" size={18} />
            </span>
            <span className="badge sim sm">SSAI Dynamic</span>
          </div>
          <div>
            <div className="library-kpi-val">{stats.avgAdMin} min/h</div>
            <div className="library-kpi-label">Average Ad Load</div>
            <div className="library-kpi-sub faint small">Scheduled break frequency</div>
          </div>
        </div>
      </div>

      {/* Filter and Search Bar (Harmonized with Media Library UI) */}
      <div className="library-filter-bar">
        {/* Nav row: Segmented status switcher + Live count meta */}
        <div className="library-filter-nav">
          <div className="library-format-tabs" role="tablist" aria-label="Channel playout filter">
            <button
              type="button"
              className={`library-tab-btn ${statusFilter === 'all' ? 'is-active' : ''}`}
              onClick={() => setStatusFilter('all')}
            >
              <Icon name="tv" size={13} />
              <span>All Channels</span>
              <span className="library-tab-count">{stats.total}</span>
            </button>
            <button
              type="button"
              className={`library-tab-btn ${statusFilter === 'live' ? 'is-active' : ''}`}
              onClick={() => setStatusFilter('live')}
            >
              <Icon name="activity" size={13} />
              <span>On Air</span>
              <span className="library-tab-count">{stats.liveCount}</span>
            </button>
            <button
              type="button"
              className={`library-tab-btn ${statusFilter === 'draft' ? 'is-active' : ''}`}
              onClick={() => setStatusFilter('draft')}
            >
              <Icon name="upload" size={13} />
              <span>Draft & Staging</span>
              <span className="library-tab-count">{stats.draftCount}</span>
            </button>
            <button
              type="button"
              className={`library-tab-btn ${statusFilter === 'stopped' ? 'is-active' : ''}`}
              onClick={() => setStatusFilter('stopped')}
            >
              <Icon name="stop" size={13} />
              <span>Standby</span>
              <span className="library-tab-count">{stats.stoppedCount}</span>
            </button>
          </div>

          <div className="library-catalog-meta">
            <span className="library-catalog-meta-count">
              <strong>{filteredChannels.length}</strong>
              <span className="faint"> / {stats.total} channels</span>
            </span>
            {isFiltered && (
              <span className="library-catalog-meta-badge">
                <span className="meta-badge-dot" /> Filtered
              </span>
            )}
          </div>
        </div>

        {/* Controls row: Live Search & Custom Dropdowns */}
        <div className="library-controls-row">
          <div className={`library-search-wrap ${q ? 'has-value' : ''}`}>
            <Icon name="search" size={14} />
            <input
              type="text"
              className="library-search-input"
              placeholder="Search channels by name, genre, timezone, or region…"
              value={q}
              onChange={e => setQ(e.target.value)}
              aria-label="Search channels"
            />
            {q && (
              <button
                type="button"
                className="library-search-clear"
                onClick={() => setQ('')}
                aria-label="Clear search"
                title="Clear search text"
              >
                <Icon name="x" size={12} />
              </button>
            )}
          </div>

          <div className="library-dropdowns-wrap">
            {/* Status Filter Dropdown */}
            <Dropdown
              className={statusFilter !== 'all' ? 'is-filtered' : ''}
              options={statusDropdownOptions}
              value={statusFilter}
              onChange={val => setStatusFilter(val as 'all' | 'live' | 'draft' | 'stopped')}
            />

            {/* Sort Dropdown */}
            <Dropdown
              options={sortOptions}
              value={sortBy}
              onChange={setSortBy}
            />
          </div>
        </div>
      </div>

      {list.error ? (
        <ErrorBox error={list.error} retry={list.reload} />
      ) : !list.data ? (
        <Loading rows={4} />
      ) : filteredChannels.length === 0 ? (
        <div className="card">
          <Empty
            icon="tv"
            title={q ? 'No matching channels found' : 'No channels in this view'}
            text={q ? 'Try clearing your search query or switching filters.' : 'Create a channel in four short steps. We\'ll even build its first day of programming for you.'}
            action={
              q ? (
                <button type="button" className="btn" onClick={() => setQ('')}>Clear Search</button>
              ) : (
                org.can('CHANNELS_EDIT') && (
                  <button type="button" className="btn primary" onClick={() => setWizard(true)}>
                    <Icon name="plus" size={16} /> Create a Channel
                  </button>
                )
              )
            }
          />
        </div>
      ) : (
        <div className="grid g2">
          {filteredChannels.map(v => {
            const c = v.channel
            const isLive = c.status === 'LIVE'

            // Linear broadcast playout resolution:
            // 1. Explicit ad break reported by backend (v.now.kind === 'AD' or title matches ad break/commercial)
            const isExplicitAd = Boolean(v.now?.kind === 'AD' || (v.now?.title && /ad break|commercial/i.test(v.now.title)))
            const nextStartMs = v.next ? new Date(v.next.start).getTime() : 0
            const hasNext = Boolean(v.next && nextStartMs > 0)

            // 2. On a LIVE 24/7 linear channel, any gap between scheduled content actively broadcasts
            //    SSAI commercial break pods or station bumpers (which is what plays in Watch Live)
            const isLiveBreak = Boolean(isLive && !v.now && hasNext)
            const isOnAdBreak = isExplicitAd || isLiveBreak

            // Calculate live playout scrubber percentage
            let progressPct = 0
            if (v.now) {
              progressPct = getPlayoutProgress(v.now.start, v.now.end, nowTick)
            } else if (isLiveBreak && nextStartMs > 0) {
              const breakWindowMs = Math.max(30000, (c.breakSeconds || 90) * 1000)
              const msUntilNext = nextStartMs - nowTick
              if (msUntilNext <= 0) {
                progressPct = 95
              } else {
                progressPct = Math.min(96, Math.max(10, Math.round(((breakWindowMs - msUntilNext) / breakWindowMs) * 100)))
              }
            }

            return (
              <section key={c.id} className="channel-console-card">
                {/* Brand color accent stripe with ambient glow */}
                <div
                  className="channel-card-accent-stripe"
                  style={{
                    background: c.color || 'var(--primary)',
                    boxShadow: `0 0 14px ${c.color}60`,
                  }}
                />

                <div className="channel-card-body">
                  {/* Header: Logo, Name, Meta, Status */}
                  <div className="channel-card-head">
                    <div className="channel-card-brand">
                      <div
                        className="channel-badge-box"
                        style={{
                          background: `linear-gradient(135deg, ${c.color || 'var(--primary)'}, ${c.color || 'var(--primary)'}cc)`,
                          boxShadow: `0 4px 16px ${c.color}40`,
                        }}
                      >
                        {c.name.slice(0, 2).toUpperCase()}
                      </div>
                      <div className="channel-card-title-group">
                        <div className="channel-card-title-row">
                          <h2 className="channel-card-name">{c.name}</h2>
                          <span className="channel-slug-pill">/{c.slug}</span>
                        </div>
                        <div className="channel-card-pills">
                          {c.genre && <span className="channel-genre-pill">{c.genre}</span>}
                          <span className="channel-tz-pill">
                            <Icon name="clock" size={12} />
                            {c.timezone}
                          </span>
                        </div>
                      </div>
                    </div>

                    {/* Status Pill */}
                    {isLive ? (
                      <span className="channel-live-status-badge live" title="Broadcasting 24/7 linear stream">
                        <span className="channel-sound-waves">
                          <span /><span /><span /><span />
                        </span>
                        ON AIR
                      </span>
                    ) : (
                      <Status value={c.status} />
                    )}
                  </div>

                  {c.description && <p className="channel-card-desc">{c.description}</p>}

                  {/* Linear Broadcast Playout Monitor Widget */}
                  <div className="channel-playout-monitor">
                    <div className="channel-monitor-row">
                      <span className="channel-monitor-label">
                        {isOnAdBreak ? (
                          <Icon name="megaphone" size={13} />
                        ) : (
                          <Icon name="film" size={13} />
                        )}
                        ON NOW
                      </span>

                      {/* Playout status state machine */}
                      {v.now ? (
                        isExplicitAd ? (
                          <div className="channel-monitor-programme" title="SSAI commercial ad break currently broadcasting">
                            <span className="channel-ad-indicator-pill">
                              <Icon name="megaphone" size={11} />
                              AD BREAK
                            </span>
                            <span>Commercial Break</span>
                            <span className="channel-monitor-time">until {fmtTime(v.now.end)}</span>
                          </div>
                        ) : (
                          <div className="channel-monitor-programme" title={v.now.title}>
                            <span className="badge primary sm" style={{ fontSize: 10, textTransform: 'uppercase' }}>
                              {v.now.kind || 'SHOW'}
                            </span>
                            <span>{v.now.title}</span>
                            <span className="channel-monitor-time">until {fmtTime(v.now.end)}</span>
                          </div>
                        )
                      ) : isLive ? (
                        hasNext ? (
                          <div className="channel-monitor-programme" title="Commercial break currently broadcasting on live stream">
                            <span className="channel-ad-indicator-pill">
                              <Icon name="megaphone" size={11} />
                              AD BREAK
                            </span>
                            <span>Commercial Break</span>
                            <span className="channel-monitor-time">
                              {nextStartMs > nowTick ? `until ${fmtTime(v.next!.start)}` : 'next program starting'}
                            </span>
                          </div>
                        ) : v.fallbackTitle ? (
                          <div className="channel-monitor-programme" title="Fallback loop playing">
                            <span className="badge default sm" style={{ fontSize: 10 }}>FALLBACK</span>
                            <span>{v.fallbackTitle}</span>
                            <span className="faint" style={{ fontSize: 12 }}>Looping</span>
                          </div>
                        ) : (
                          <div className="channel-monitor-programme" title="Standby slate active">
                            <span className="badge default sm" style={{ fontSize: 10 }}>STANDBY</span>
                            <span className="faint">Standby screen active · No scheduled program</span>
                          </div>
                        )
                      ) : (
                        <span className="faint">Off Air · Playout stopped</span>
                      )}
                    </div>

                    {/* Playout progress scrubber */}
                    <div className="channel-playout-progress-track">
                      <div
                        className={`channel-playout-progress-fill ${isLive ? (isOnAdBreak ? 'ad-pulse' : 'live-pulse') : ''}`}
                        style={{ width: `${v.now || isLiveBreak ? progressPct : 0}%` }}
                      />
                    </div>

                    <div className="channel-monitor-divider" />

                    <div className="channel-monitor-row">
                      <span className="channel-monitor-label">
                        <Icon name="clock" size={13} />
                        UP NEXT
                      </span>
                      {v.next ? (
                        <div className="channel-monitor-programme" title={v.next.title}>
                          <span>{v.next.title}</span>
                          <span className="channel-monitor-time">at {fmtTime(v.next.start)}</span>
                        </div>
                      ) : (
                        <span className="faint">End of programmed block</span>
                      )}
                    </div>
                  </div>

                  {/* 4-Column Micro-Spec Badges */}
                  <div className="channel-specs-grid">
                    <div className="channel-spec-badge territories-badge">
                      <span className="channel-spec-title">
                        Territories
                        <Icon name="globe" size={11} />
                      </span>
                      <span className="channel-spec-val territories-val">
                        <ChannelTerritoriesBadge codes={csv(c.territories)} />
                      </span>
                    </div>

                    <div className="channel-spec-badge">
                      <span className="channel-spec-title">
                        Ad Rules
                        <InfoTip text="Max ad time per hour and break duration. SCTE-35 markers will be placed automatically." />
                      </span>
                      <span className="channel-spec-val">
                        {Math.round(c.adSecondsPerHour / 60)}m/h · {c.breakSeconds}s
                      </span>
                    </div>

                    <div className="channel-spec-badge">
                      <span className="channel-spec-title">
                        Schedule
                        <Icon name="calendar" size={11} />
                      </span>
                      <span className="channel-spec-val">
                        {v.publishedVersion ? `v${v.publishedVersion} · ${fmtDayTime(v.scheduledUntil)}` : 'Not published'}
                      </span>
                    </div>

                    <div className="channel-spec-badge">
                      <span className="channel-spec-title">
                        Fallback Loop
                        <Icon name="shield" size={11} />
                      </span>
                      <span className="channel-spec-val">
                        {v.fallbackTitle ?? <span className="faint">Standby Screen</span>}
                      </span>
                    </div>
                  </div>

                  {/* Pre-flight readiness notice */}
                  {v.readiness.length > 0 && c.status !== 'LIVE' && (
                    <div className="channel-readiness-box">
                      <Icon name="alert" size={16} />
                      <div>
                        <b>Ready to go live checklist:</b>
                        <ul>
                          {v.readiness.map((item, idx) => (
                            <li key={idx}>{item}</li>
                          ))}
                        </ul>
                      </div>
                    </div>
                  )}

                  {/* Executive Action Toolbar */}
                  <div className="channel-card-actions">
                    <Link className="btn sm" to={`${org.base}/schedule?channel=${c.id}`}>
                      <Icon name="calendar" size={14} />
                      Schedule
                    </Link>

                    {isLive && (
                      <Link className="btn sm primary" to={`${org.base}/watch?channel=${c.slug}`}>
                        <Icon name="play" size={14} />
                        Watch Live
                      </Link>
                    )}

                    {org.can('CHANNELS_EDIT') && (
                      <button type="button" className="btn sm" onClick={() => setEditing(v)}>
                        <Icon name="settings" size={14} />
                        Settings
                      </button>
                    )}

                    <div className="grow" />

                    {org.can('SCHEDULE_PUBLISH') && c.status !== 'LIVE' && c.status !== 'ARCHIVED' && (
                      <button
                        type="button"
                        className="btn sm primary"
                        disabled={busy || v.readiness.length > 0}
                        title={v.readiness.join('; ')}
                        onClick={() => act(v, 'go-live', c.name + ' is now on air')}
                      >
                        <Icon name="rocket" size={14} />
                        Go Live
                      </button>
                    )}

                    {org.can('SCHEDULE_PUBLISH') && isLive && (
                      <button type="button" className="btn sm danger" onClick={() => setConfirm({ v, action: 'stop' })}>
                        <Icon name="stop" size={14} />
                        Stop Playout
                      </button>
                    )}

                    {org.can('CHANNELS_EDIT') && c.status !== 'LIVE' && (
                      <button type="button" className="btn sm ghost" onClick={() => setConfirm({ v, action: 'archive' })}>
                        <Icon name="trash" size={13} />
                        Archive
                      </button>
                    )}
                  </div>
                </div>
              </section>
            )
          })}
        </div>
      )}

      {wizard && <ChannelWizard onClose={() => setWizard(false)} onDone={() => { setWizard(false); list.reload() }} />}
      {editing && <ChannelSettings view={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); list.reload() }} />}
      {confirm && (
        <Confirm
          danger
          title={confirm.action === 'stop' ? `Stop broadcast for ${confirm.v.channel.name}?` : `Archive ${confirm.v.channel.name}?`}
          confirmLabel={confirm.action === 'stop' ? 'Stop Channel' : 'Archive Channel'}
          text={
            confirm.action === 'stop'
              ? 'Viewers will see the fallback video or standby slate screen until you go live again. The scheduled programming remains intact.'
              : 'The channel will be hidden from the active list. Complete run records remain in the audit log.'
          }
          onClose={() => setConfirm(null)}
          onConfirm={() => act(confirm.v, confirm.action, confirm.action === 'stop' ? 'Channel stopped' : 'Channel archived')}
        />
      )}
    </div>
  )
}
