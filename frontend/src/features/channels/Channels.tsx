import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type AssetView, type ChannelView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Confirm,
  COUNTRIES,
  Countries,
  CountryPicker,
  csv,
  Dropdown,
  type DropdownOption,
  Empty,
  ErrorBox,
  fmtDayTime,
  fmtDur,
  fmtTime,
  Icon,
  InfoTip,
  Loading,
  Modal,
  PageHead,
  Status,
  useAction,
  useApi,
} from '../../shared/ui'
import { GenreSelector } from '../../shared/components/GenreSelector'

const TIMEZONE_OPTIONS: DropdownOption[] = [
  { value: 'Asia/Kolkata', label: 'Asia/Kolkata (IST +05:30)', icon: 'clock', description: 'Indian Standard Time' },
  { value: 'Europe/London', label: 'Europe/London (GMT/BST)', icon: 'clock', description: 'Greenwich / British Summer Time' },
  { value: 'America/New_York', label: 'America/New_York (EST/EDT -05:00)', icon: 'clock', description: 'US Eastern Time' },
  { value: 'America/Los_Angeles', label: 'America/Los_Angeles (PST/PDT -08:00)', icon: 'clock', description: 'US Pacific Time' },
  { value: 'Europe/Berlin', label: 'Europe/Berlin (CET/CEST +01:00)', icon: 'clock', description: 'Central European Time' },
  { value: 'Asia/Singapore', label: 'Asia/Singapore (SGT +08:00)', icon: 'clock', description: 'Singapore Standard Time' },
  { value: 'Australia/Sydney', label: 'Australia/Sydney (AEST/AEDT +10:00)', icon: 'clock', description: 'Australian Eastern Time' },
  { value: 'UTC', label: 'UTC (Coordinated Universal Time)', icon: 'clock', description: 'Coordinated Universal Time' },
]

const AD_MINUTE_OPTIONS: DropdownOption[] = [
  { value: '4', label: '4 min / hour', icon: 'megaphone', description: 'Light ad load (high viewer retention)', badge: 'Light', badgeType: 'ok' },
  { value: '6', label: '6 min / hour', icon: 'megaphone', description: 'Moderate commercial break density', badge: 'Balanced', badgeType: 'info' },
  { value: '8', label: '8 min / hour', icon: 'megaphone', description: 'Standard linear FAST ad density', badge: 'Standard', badgeType: 'info' },
  { value: '10', label: '10 min / hour', icon: 'megaphone', description: 'High commercial inventory monetization', badge: 'High', badgeType: 'warn' },
  { value: '12', label: '12 min / hour', icon: 'megaphone', description: 'Maximum broadcast ad saturation', badge: 'Max', badgeType: 'live' },
]

const BREAK_SECOND_OPTIONS: DropdownOption[] = [
  { value: '60', label: '60 seconds (1.0 min break)', icon: 'clock', description: '2x 30s sponsor spot pods' },
  { value: '90', label: '90 seconds (1.5 min break)', icon: 'clock', description: 'Standard 3x 30s ad break pods', badge: 'Recommended', badgeType: 'ok' },
  { value: '120', label: '120 seconds (2.0 min break)', icon: 'clock', description: '4x 30s commercial slot pod' },
  { value: '180', label: '180 seconds (3.0 min break)', icon: 'clock', description: 'Extended broadcast commercial block' },
]

const HOURS_OPTIONS: DropdownOption[] = [
  { value: '6', label: '6 Hours Playout', icon: 'calendar', description: 'Quarter-day loop pattern' },
  { value: '12', label: '12 Hours Playout', icon: 'calendar', description: 'Half-day daypart programming' },
  { value: '24', label: '24 Hours (Full Day)', icon: 'calendar', description: 'Full 24/7 continuous linear timetable', badge: 'Full Day', badgeType: 'ok' },
  { value: '48', label: '48 Hours (Weekend)', icon: 'calendar', description: 'Multi-day continuous broadcast queue' },
]

const COLORS = ['#ef4444', '#f97316', '#eab308', '#22c55e', '#059669', '#0ea5e9', '#6366f1', '#a855f7', '#ec4899', '#64748b']

const REGION_PRESETS: { label: string; icon: string; codes: string[] }[] = [
  { label: 'Worldwide', icon: '🌐', codes: ['IN', 'GB', 'US', 'CA', 'IE', 'AU', 'DE', 'FR', 'ES', 'BR', 'MX', 'JP', 'SG', 'AE', 'ZA'] },
  { label: 'North America', icon: '🇺🇸', codes: ['US', 'CA', 'MX'] },
  { label: 'Europe Tier-1', icon: '🇪🇺', codes: ['GB', 'IE', 'DE', 'FR', 'ES'] },
  { label: 'Asia-Pacific', icon: '🌏', codes: ['IN', 'SG', 'JP', 'AU'] },
]

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

      {wizard && <Wizard onClose={() => setWizard(false)} onDone={() => { setWizard(false); list.reload() }} />}
      {editing && <Settings view={editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); list.reload() }} />}
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

function Wizard({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const nav = useNavigate()
  const assets = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`)
  const { busy, run } = useAction()
  const [step, setStep] = useState(0)
  const [assetSearch, setAssetSearch] = useState('')

  const [f, setF] = useState({
    name: '',
    description: '',
    genre: 'Drama, Action',
    timezone: 'Asia/Kolkata',
    color: COLORS[6],
    territories: ['IN', 'GB', 'US'] as string[],
    assetIds: [] as string[],
    hours: 24,
    adMinutes: 8,
    breakSeconds: 90,
  })

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))

  const programmes = useMemo(() => {
    return assets.data?.filter(a => a.asset.kind !== 'AD' && a.asset.status === 'READY') ?? []
  }, [assets.data])

  const filteredProgrammes = useMemo(() => {
    if (!assetSearch.trim()) return programmes
    const query = assetSearch.toLowerCase().trim()
    return programmes.filter(a =>
      a.asset.title.toLowerCase().includes(query) ||
      (a.asset.genre && a.asset.genre.toLowerCase().includes(query))
    )
  }, [programmes, assetSearch])

  const selectedCount = f.assetIds.length
  const selectedDurationMs = programmes
    .filter(a => f.assetIds.includes(a.asset.id))
    .reduce((acc, a) => acc + (a.asset.durationMs || 0), 0)

  const steps = [
    { label: 'Identity & Look', desc: 'Brand name, genre & badge' },
    { label: 'Regional Reach', desc: 'Territory licensing' },
    { label: 'Programming Lineup', desc: 'Day-1 assets & ad rules' },
    { label: 'Review & Deploy', desc: 'Verify and launch' },
  ]

  const canNext = [
    f.name.trim().length > 1,
    f.territories.length > 0,
    true,
    true,
  ][step]

  async function create() {
    const v = await api<ChannelView>(`/api/orgs/${org.slug}/channels`, {
      body: {
        name: f.name,
        description: f.description,
        genre: f.genre,
        timezone: f.timezone,
        color: f.color,
        territories: f.territories,
        adSecondsPerHour: f.adMinutes * 60,
        breakSeconds: f.breakSeconds,
        assetIds: f.assetIds,
        autofillHours: f.hours,
      },
    })
    return v
  }

  return (
    <Modal
      wide
      title="Create Linear Channel"
      subtitle={`Step ${step + 1} of 4 — ${steps[step].label}`}
      onClose={onClose}
      footer={
        <>
          {step > 0 && (
            <button type="button" className="btn" onClick={() => setStep(s => s - 1)}>
              <Icon name="chevronLeft" size={14} />
              Back
            </button>
          )}
          <div className="grow" />
          {step < 3 ? (
            <button type="button" className="btn primary" disabled={!canNext} onClick={() => setStep(s => s + 1)}>
              Continue
              <Icon name="arrowRight" size={15} />
            </button>
          ) : (
            <button
              type="button"
              className="btn primary"
              disabled={busy}
              onClick={() =>
                run(create, 'Channel created with a draft schedule').then(v => {
                  if (v) {
                    onDone()
                    nav(`${org.base}/schedule?channel=${v.channel.id}`)
                  }
                })
              }
            >
              {busy ? <span className="spinner" /> : (
                <>
                  <Icon name="rocket" size={15} />
                  Deploy Channel & Open Timetable
                </>
              )}
            </button>
          )}
        </>
      }
    >
      {/* Visual Stepper */}
      <div className="wizard-stepper-container">
        <div className="wizard-stepper-track">
          <div className="wizard-stepper-fill" style={{ width: `${(step / 3) * 100}%` }} />
        </div>
        {steps.map((st, idx) => {
          const isDone = idx < step
          const isActive = idx === step
          return (
            <button
              key={st.label}
              type="button"
              className={`wizard-step-node ${isActive ? 'active' : ''} ${isDone ? 'done' : ''}`}
              onClick={() => {
                if (idx < step) setStep(idx)
              }}
            >
              <div className="wizard-step-circle">
                {isDone ? <Icon name="check" size={16} /> : idx + 1}
              </div>
              <span className="wizard-step-label">{st.label}</span>
            </button>
          )
        })}
      </div>

      {/* Step 0: Identity & Look */}
      {step === 0 && (
        <div className="stack" style={{ gap: 16 }}>
          <label className="field">
            Channel Name
            <input
              autoFocus
              value={f.name}
              onChange={e => set('name', e.target.value)}
              placeholder="e.g. Midnight Cinema, Apex Sports, CineVault"
            />
            <span className="hint">
              Channel slug will be: <code>/{f.name ? f.name.toLowerCase().replace(/[^a-z0-9]/g, '-').replace(/-+/g, '-') : 'my-channel'}</code>
            </span>
          </label>

          {/* Genre Multi-Select Checkbox Component (Matching Media Library UI) */}
          <GenreSelector
            value={f.genre}
            onChange={v => set('genre', v)}
          />

          <label className="field">
            Short Description
            <span className="hint">Displayed to audience in electronic programme guides (EPG)</span>
            <textarea
              rows={2}
              value={f.description}
              onChange={e => set('description', e.target.value)}
              placeholder="e.g. Non-stop blockbuster crime thrillers and mystery series around the clock."
            />
          </label>

          <div className="grid g2">
            <div className="field">
              <span>Broadcast Timezone</span>
              <span className="hint">Governs daypart planning and program scheduling grids</span>
              <Dropdown
                options={TIMEZONE_OPTIONS}
                value={f.timezone}
                onChange={v => set('timezone', v)}
                searchable
                align="full"
              />
            </div>

            <div className="field">
              Brand Accent Color
              <span className="hint">Used for broadcast on-air bug, guide banners, and live telemetry</span>
              <div className="color-swatches-grid">
                {COLORS.map(c => (
                  <button
                    key={c}
                    type="button"
                    className={`color-swatch-ring ${f.color === c ? 'selected' : ''}`}
                    aria-label={'Color ' + c}
                    onClick={() => set('color', c)}
                    style={{ background: c }}
                  />
                ))}
              </div>
            </div>
          </div>

          {/* Real-time Broadcast Badge & EPG Preview */}
          <div className="channel-preview-banner">
            <div className="channel-preview-left">
              <div
                className="channel-badge-box"
                style={{
                  background: `linear-gradient(135deg, ${f.color}, ${f.color}cc)`,
                  boxShadow: `0 4px 18px ${f.color}50`,
                }}
              >
                {(f.name || 'FAST').slice(0, 2).toUpperCase()}
              </div>
              <div>
                <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)' }}>
                  {f.name || 'Untitled Channel'}
                </div>
                <div className="faint" style={{ fontSize: 12 }}>
                  {f.genre || 'General'} · {f.timezone}
                </div>
              </div>
            </div>
            <div className="channel-preview-epg-hint">
              <div style={{ marginBottom: 4 }}><Icon name="tv" size={14} /></div>
              <div>Live EPG Preview</div>
            </div>
          </div>
        </div>
      )}

      {/* Step 1: Regional Reach */}
      {step === 1 && (
        <div className="stack" style={{ gap: 14 }}>
          <div className="callout info">
            <Icon name="globe" size={16} />
            <div>
              <b>Geographical Distribution & Content Licensing:</b> Choose the countries where viewers can stream this channel. If an asset lacks a valid broadcast license in a territory, viewers there will automatically receive a regional standby slate.
            </div>
          </div>

          <div className="field">
            <div className="between" style={{ marginBottom: 6 }}>
              <span>Quick Regional Presets</span>
              <span className="faint" style={{ fontSize: 11 }}>{f.territories.length} countries selected</span>
            </div>
            <div className="regional-presets-bar">
              {REGION_PRESETS.map(preset => (
                <button
                  key={preset.label}
                  type="button"
                  className="regional-preset-chip"
                  onClick={() => set('territories', Array.from(new Set([...f.territories, ...preset.codes])))}
                >
                  <span>{preset.icon}</span>
                  <span>{preset.label}</span>
                </button>
              ))}
              <button
                type="button"
                className="regional-preset-chip"
                onClick={() => set('territories', [])}
                style={{ color: 'var(--bad)' }}
              >
                Clear All
              </button>
            </div>
          </div>

          <div className="field">
            <span>Select Territories</span>
            <CountryPicker value={f.territories} onChange={v => set('territories', v)} />
          </div>
        </div>
      )}

      {/* Step 2: Programming Lineup */}
      {step === 2 && (
        <div className="stack" style={{ gap: 14 }}>
          <p className="muted" style={{ margin: 0 }}>
            Choose ready media assets for your initial broadcast line-up. We will automatically arrange them into a contiguous schedule with timed ad break intervals.
          </p>

          <div className="grid g3">
            <div className="field">
              <span>Fill Playout Duration</span>
              <Dropdown
                options={HOURS_OPTIONS}
                value={String(f.hours)}
                onChange={v => set('hours', Number(v))}
                align="full"
              />
            </div>

            <div className="field">
              <span>Ad Density Per Hour</span>
              <Dropdown
                options={AD_MINUTE_OPTIONS}
                value={String(f.adMinutes)}
                onChange={v => set('adMinutes', Number(v))}
                align="full"
              />
            </div>

            <div className="field">
              <span>Ad Break Length</span>
              <Dropdown
                options={BREAK_SECOND_OPTIONS}
                value={String(f.breakSeconds)}
                onChange={v => set('breakSeconds', Number(v))}
                align="full"
              />
            </div>
          </div>

          {/* Programme Media Selector */}
          <div className="field">
            <div className="between" style={{ marginBottom: 6 }}>
              <span>
                Select Videos ({selectedCount} chosen · {fmtDur(selectedDurationMs)} run time)
              </span>
              <div className="row" style={{ gap: 6 }}>
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => set('assetIds', programmes.map(p => p.asset.id))}
                >
                  Select All Ready
                </button>
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => set('assetIds', [])}
                >
                  Deselect All
                </button>
              </div>
            </div>

            <div style={{ marginBottom: 8 }}>
              <input
                type="text"
                placeholder="Search ready programmes by title or genre..."
                value={assetSearch}
                onChange={e => setAssetSearch(e.target.value)}
              />
            </div>

            {!assets.data ? (
              <Loading rows={2} />
            ) : programmes.length === 0 ? (
              <div className="callout warn">
                <Icon name="alert" />
                <div>No ready videos found in the library. You can proceed now and populate programming later.</div>
              </div>
            ) : (
              <div className="wizard-asset-grid">
                {filteredProgrammes.map(a => {
                  const on = f.assetIds.includes(a.asset.id)
                  return (
                    <button
                      key={a.asset.id}
                      type="button"
                      className={`wizard-asset-card ${on ? 'selected' : ''}`}
                      aria-pressed={on}
                      onClick={() =>
                        set('assetIds', on ? f.assetIds.filter(x => x !== a.asset.id) : [...f.assetIds, a.asset.id])
                      }
                    >
                      <div className="thumb" style={{ background: a.asset.color, height: 75 }}>
                        {a.thumbUrl && <img src={a.thumbUrl} alt="" />}
                        {on && (
                          <span className="overlay-badge badge ok">
                            <Icon name="check" size={12} />
                            Selected
                          </span>
                        )}
                        <span className="dur">{fmtDur(a.asset.durationMs)}</span>
                      </div>
                      <div className="between" style={{ padding: '2px 0 0' }}>
                        <b className="ellipsis small" title={a.asset.title}>{a.asset.title}</b>
                        <Status value={a.rightsStatus} />
                      </div>
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Step 3: Review & Deploy */}
      {step === 3 && (
        <div className="stack" style={{ gap: 16 }}>
          <div className="channel-preview-banner">
            <div className="channel-preview-left">
              <div
                className="channel-badge-box"
                style={{
                  background: `linear-gradient(135deg, ${f.color}, ${f.color}cc)`,
                  boxShadow: `0 4px 18px ${f.color}50`,
                }}
              >
                {(f.name || 'FAST').slice(0, 2).toUpperCase()}
              </div>
              <div>
                <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text)' }}>
                  {f.name}
                </div>
                <div className="faint" style={{ fontSize: 12 }}>
                  {f.genre} · {f.timezone} · /{f.name.toLowerCase().replace(/[^a-z0-9]/g, '-')}
                </div>
              </div>
            </div>
            <span className="badge live">
              <span className="dot" />
              Ready for Day-1 Staging
            </span>
          </div>

          <div className="kv">
            <span>Target Territories</span>
            <Countries codes={f.territories} />

            <span>Programmes Selected</span>
            <span>
              {f.assetIds.length
                ? `${f.assetIds.length} programmes (${fmtDur(selectedDurationMs)} content) automatically populated into ${f.hours}-hour loop`
                : 'No assets selected (blank timetable template)'}
            </span>

            <span>SSAI Ad Policy</span>
            <span>
              {f.adMinutes} minutes/hour maximum · {f.breakSeconds}s breaks with automated SCTE-35 markers
            </span>

            <span>Playout Engine</span>
            <span>HLS Dynamic Playout with seamless gap filler standby</span>
          </div>

          <div className="callout info">
            <Icon name="info" />
            <div>
              Clicking <b>Deploy Channel</b> will generate your <b>Day 1 draft timetable</b>. You will be redirected to the interactive schedule planner to review cues, adjust breakpoints, and press <b>Go Live</b>.
            </div>
          </div>
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

  const [f, setF] = useState({
    name: c.name,
    description: c.description,
    genre: c.genre,
    timezone: c.timezone,
    color: c.color || COLORS[6],
    territories: csv(c.territories),
    adMinutes: Math.round(c.adSecondsPerHour / 60),
    breakSeconds: c.breakSeconds,
    fallback: c.fallbackAssetId ?? '',
  })

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))

  // Ensure current channel timezone is included in options
  const timezoneOptions: DropdownOption[] = useMemo(() => {
    const list = [...TIMEZONE_OPTIONS]
    if (c.timezone && !list.some(t => t.value === c.timezone)) {
      list.unshift({
        value: c.timezone,
        label: c.timezone,
        icon: 'clock',
        description: 'Channel designated timezone',
      })
    }
    return list
  }, [c.timezone])

  // Ready video fallback options with rich thumbnails & metadata
  const fallbackOptions: DropdownOption[] = useMemo(() => {
    const readyAssets = assets.data?.filter(a => a.asset.kind !== 'AD' && a.asset.status === 'READY') ?? []
    return [
      {
        value: '',
        label: 'None (Default Standby Slate)',
        icon: 'shield',
        description: 'Display standard broadcast off-air test card slate',
        badge: 'Slate',
        badgeType: 'default',
      },
      ...readyAssets.map(a => ({
        value: a.asset.id,
        label: a.asset.title,
        icon: 'film',
        description: `${a.asset.genre || 'General'} · ${fmtDur(a.asset.durationMs)}`,
        badge: fmtDur(a.asset.durationMs),
        badgeType: 'info' as const,
      })),
    ]
  }, [assets.data])

  return (
    <Modal
      wide
      title={`${c.name} — Settings`}
      subtitle={`Channel ID: ${c.id} · Broadcast slug: /${c.slug}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy}
            onClick={() =>
              run(
                () =>
                  api(`/api/orgs/${org.slug}/channels/${c.id}`, {
                    method: 'PATCH',
                    body: {
                      name: f.name,
                      description: f.description,
                      genre: f.genre,
                      timezone: f.timezone,
                      color: f.color,
                      territories: f.territories,
                      adSecondsPerHour: f.adMinutes * 60,
                      breakSeconds: f.breakSeconds,
                      fallbackAssetId: f.fallback || undefined,
                      clearFallback: !f.fallback,
                    },
                  }),
                'Settings updated successfully'
              ).then(r => r !== undefined && onDone())
            }
          >
            {busy ? <span className="spinner" /> : 'Save Changes'}
          </button>
        </>
      }
    >
      {/* Branding & Info */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="palette" size={15} />
          Branding & Identity
        </div>

        <label className="field">
          Channel Name
          <input value={f.name} onChange={e => set('name', e.target.value)} />
        </label>

        {/* Genre Multi-Select Checkbox Component (Matching Media Library UI) */}
        <GenreSelector
          value={f.genre}
          onChange={v => set('genre', v)}
        />

        <label className="field">
          Description
          <textarea
            rows={2}
            value={f.description}
            onChange={e => set('description', e.target.value)}
          />
        </label>

        <div className="grid g2">
          <div className="field">
            <span>Broadcast Timezone</span>
            <span className="hint">Governs daypart planning and program scheduling grids</span>
            <Dropdown
              options={timezoneOptions}
              value={f.timezone}
              onChange={v => set('timezone', v)}
              searchable
              align="full"
            />
          </div>

          <div className="field">
            Brand Accent Color
            <div className="color-swatches-grid">
              {COLORS.map(color => (
                <button
                  key={color}
                  type="button"
                  className={`color-swatch-ring ${f.color === color ? 'selected' : ''}`}
                  onClick={() => set('color', color)}
                  style={{ background: color }}
                />
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Distribution & Geo Reach */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="globe" size={15} />
          Distribution & Geo Reach
        </div>

        <div className="regional-presets-bar">
          {REGION_PRESETS.map(p => (
            <button
              key={p.label}
              type="button"
              className="regional-preset-chip"
              onClick={() => set('territories', Array.from(new Set([...f.territories, ...p.codes])))}
            >
              <span>{p.icon}</span>
              <span>{p.label}</span>
            </button>
          ))}
        </div>

        <CountryPicker value={f.territories} onChange={v => set('territories', v)} />
      </div>

      {/* SSAI Ad Monetization Rules */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="megaphone" size={15} />
          SSAI Monetization & Ad Rules
        </div>

        <div className="grid g2">
          <div className="field">
            <span>Max Ads Per Hour</span>
            <Dropdown
              options={AD_MINUTE_OPTIONS}
              value={String(f.adMinutes)}
              onChange={v => set('adMinutes', Number(v))}
              align="full"
            />
          </div>

          <div className="field">
            <span>Ad Break Length</span>
            <Dropdown
              options={BREAK_SECOND_OPTIONS}
              value={String(f.breakSeconds)}
              onChange={v => set('breakSeconds', Number(v))}
              align="full"
            />
          </div>
        </div>
      </div>

      {/* Playout Failover & Fallback */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="shield" size={15} />
          Failover & Standby Loop
        </div>

        <div className="field">
          <span>Fallback Video Playout</span>
          <span className="hint">
            Plays during timetable gaps or stream buffer failovers instead of a blank standby screen.
          </span>
          <Dropdown
            options={fallbackOptions}
            value={f.fallback}
            onChange={v => set('fallback', v)}
            searchable
            align="full"
            placeholder="Select fallback video loop..."
          />
        </div>
      </div>
    </Modal>
  )
}
