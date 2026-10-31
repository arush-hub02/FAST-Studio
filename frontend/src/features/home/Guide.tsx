import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type ChannelView, type Programme } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  ChannelLogo,
  Dropdown,
  type DropdownOption,
  Empty,
  fmtDayTime,
  fmtDur,
  fmtTime,
  fmtTimeSec,
  Icon,
  Loading,
  Modal,
  PageHead,
  useApi,
  useToast,
} from '../../shared/ui'
import { type Campaign, resolveBreakAds } from './guideAds'

type ZoomOption = { hours: number; px: number; label: string }
const ZOOM_OPTIONS: ZoomOption[] = [
  { hours: 1, px: 1100, label: '1 Hour (Short-Form Detail)' },
  { hours: 2, px: 850, label: '2 Hours (Standard Linear)' },
  { hours: 3, px: 650, label: '3 Hours (Wide View)' },
  { hours: 6, px: 420, label: '6 Hours (Daypart Horizon)' },
]

type HoveredItem =
  | {
      kind: 'prog'
      p: Programme
      channel: ChannelView['channel']
      x: number
      y: number
      arrowPlacement: 'top' | 'bottom'
      arrowX: number
    }
  | {
      kind: 'break'
      start: string
      end: string
      durationSecs: number
      channel: ChannelView['channel']
      x: number
      y: number
      arrowPlacement: 'top' | 'bottom'
      arrowX: number
      isNow: boolean
    }

const GENRE_COLORS: Record<string, string> = {
  movie: '#a855f7',
  action: '#f43f5e',
  comedy: '#f59e0b',
  drama: '#6366f1',
  news: '#06b6d4',
  classic: '#eab308',
  thriller: '#ec4899',
  travel: '#10b981',
  documentary: '#14b8a6',
  promo: '#8b5cf6',
}

export default function Guide() {
  const org = useOrg()
  const nav = useNavigate()
  const toast = useToast()

  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const published = useMemo(
    () => channels.data?.filter(c => c.channel.publishedVersionId) ?? [],
    [channels.data]
  )

  const campaignsApi = useApi<Campaign[]>(`/api/orgs/${org.slug}/campaigns`)
  const campaigns = useMemo(() => campaignsApi.data ?? [], [campaignsApi.data])

  const [offset, setOffset] = useState(0)
  const [guides, setGuides] = useState<Record<string, Programme[]>>({})
  const [selectedProg, setSelectedProg] = useState<{ p: Programme; channel: ChannelView['channel'] } | null>(null)
  const [selectedBreak, setSelectedBreak] = useState<{
    start: string
    end: string
    durationSecs: number
    channel: ChannelView['channel']
  } | null>(null)
  const [hoveredItem, setHoveredItem] = useState<HoveredItem | null>(null)
  const [xmlModalChannel, setXmlModalChannel] = useState<ChannelView['channel'] | null>(null)

  // View mode: 'pods' (shows amber commercial blocks in gaps) vs 'continuous' (seamless TV guide without gaps)
  const [viewMode, setViewMode] = useState<'pods' | 'continuous'>('pods')

  // Zoom / View settings - Default to 2h standard linear view
  const [zoomIdx, setZoomIdx] = useState(1)
  const { hours: HOURS, px: PX } = ZOOM_OPTIONS[zoomIdx]

  // Filter controls
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedGenre, setSelectedGenre] = useState<string>('ALL')

  // Live real-time clock ticker for timeline playhead
  const [nowMs, setNowMs] = useState(Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const scrollRef = useRef<HTMLDivElement>(null)

  // Calculate EPG time span window rounded to 30 minutes
  const from = useMemo(
    () => new Date(Math.floor(Date.now() / 1_800_000) * 1_800_000 + offset * 3_600_000),
    [offset]
  )
  const t0 = from.getTime()
  const t1 = t0 + HOURS * 3_600_000

  // Pixel calculation function for time
  const px = useCallback(
    (t: number) => ((Math.min(Math.max(t, t0), t1) - t0) / 3_600_000) * PX,
    [t0, t1, PX]
  )

  // Fetch EPG data for published channels (always request integer hours >= 6 so Spring Boot accepts it and pre-buffers adjacent slots)
  useEffect(() => {
    const requestHours = Math.max(6, Math.ceil(HOURS) * 2)
    published.forEach(c =>
      api<Programme[]>(
        `/api/orgs/${org.slug}/channels/${c.channel.id}/epg?from=${from.toISOString()}&hours=${requestHours}`
      )
        .then(g => setGuides(x => ({ ...x, [c.channel.id]: g })))
        .catch(err => console.error(`Failed to fetch EPG for channel ${c.channel.name}:`, err))
    )
  }, [channels.data, offset, HOURS, from, org.slug, published])

  // Center on current live playhead
  const jumpToNow = useCallback(() => {
    setOffset(0)
    setTimeout(() => {
      if (scrollRef.current) {
        const liveX = px(Date.now())
        scrollRef.current.scrollTo({
          left: Math.max(0, liveX - 280),
          behavior: 'smooth',
        })
      }
    }, 50)
  }, [px])

  // Jump to specific daypart time of today
  const jumpToHour = (targetHour: number) => {
    const today = new Date()
    today.setHours(targetHour, 0, 0, 0)
    const baseRounded = new Date(Math.floor(Date.now() / 1_800_000) * 1_800_000)
    const diffHours = (today.getTime() - baseRounded.getTime()) / 3_600_000
    setOffset(Math.round(diffHours))
  }

  // Extract all unique genres across current guide window
  const allGenres = useMemo(() => {
    const set = new Set<string>()
    Object.values(guides).forEach(list => list.forEach(p => p.genre && set.add(p.genre)))
    return Array.from(set).sort()
  }, [guides])

  // Filtered published channels
  const filteredPublished = useMemo(() => {
    if (!searchQuery && selectedGenre === 'ALL') return published
    const q = searchQuery.toLowerCase().trim()
    return published.filter(c => {
      const channelMatch = c.channel.name.toLowerCase().includes(q)
      const list = guides[c.channel.id] ?? []
      const hasMatchingProg = list.some(p => {
        const titleMatch = !q || p.title.toLowerCase().includes(q) || p.description?.toLowerCase().includes(q)
        const genreMatch = selectedGenre === 'ALL' || p.genre.toUpperCase() === selectedGenre.toUpperCase()
        return titleMatch && genreMatch
      })
      return channelMatch || hasMatchingProg
    })
  }, [published, guides, searchQuery, selectedGenre])

  // Calculate visible slots and commercial ad breaks in window
  const { visibleSlotsCount, totalBreaksCount, totalBreakMinutes } = useMemo(() => {
    let progs = 0
    let breaks = 0
    let totalSecs = 0
    Object.values(guides).forEach(list => {
      list.forEach(p => {
        const s = new Date(p.start).getTime()
        const e = new Date(p.end).getTime()
        if (e > t0 && s < t1) progs++
      })
      for (let i = 0; i < list.length - 1; i++) {
        const gap = new Date(list[i + 1].start).getTime() - new Date(list[i].end).getTime()
        if (gap >= 5000) {
          const s = new Date(list[i].end).getTime()
          const e = new Date(list[i + 1].start).getTime()
          if (e > t0 && s < t1) {
            breaks++
            totalSecs += (e - s) / 1000
          }
        }
      }
    })
    return {
      visibleSlotsCount: progs,
      totalBreaksCount: breaks,
      totalBreakMinutes: Math.round(totalSecs / 60),
    }
  }, [guides, t0, t1])

  const liveBroadcastingCount = useMemo(() => {
    return published.filter(c => c.channel.status === 'LIVE').length
  }, [published])

  // Zoom dropdown options
  const zoomOptions: DropdownOption[] = useMemo(
    () =>
      ZOOM_OPTIONS.map((opt, idx) => ({
        value: String(idx),
        label: opt.label,
        icon: 'calendar',
      })),
    []
  )

  const copyEpgXmlUrl = (ch: ChannelView['channel']) => {
    const url = `${window.location.origin}/api/orgs/${org.slug}/channels/${ch.id}/epg.xml`
    navigator.clipboard.writeText(url).then(() => {
      toast(`Copied ${ch.name} XMLTV feed URL to clipboard`, 'ok')
    })
  }

  // Calculate precise, snug tooltip anchoring (8px offset, never far in outer space)
  const calcHoverPos = (el: HTMLElement) => {
    const rect = el.getBoundingClientRect()
    const cardWidth = 320
    const targetCenterX = rect.left + rect.width / 2
    const x = Math.max(16, Math.min(window.innerWidth - cardWidth - 16, targetCenterX - cardWidth / 2))
    const arrowX = Math.max(16, Math.min(cardWidth - 26, targetCenterX - x - 5))

    // If rect.top < 280, element is near sticky time ruler / top KPI bar: place below to avoid collisions
    const placeAbove = rect.top >= 280
    const y = placeAbove ? rect.top - 8 : rect.bottom + 8

    return {
      x,
      y,
      arrowPlacement: (placeAbove ? 'bottom' : 'top') as 'top' | 'bottom',
      arrowX,
    }
  }

  const timelineWidth = HOURS * PX

  return (
    <div className="page" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Page Header */}
      <PageHead
        title="Electronic Programme Guide (EPG)"
        subtitle="Master linear 24/7 broadcast timetable with frame-accurate schedule parity. Click any channel header to launch Watch Live playback."
        tip="The EPG is generated dynamically from the published master schedule, ensuring 100% parity with live playout."
        actions={
          <div className="row" style={{ gap: 8 }}>
            <button
              type="button"
              className="btn"
              onClick={() => setOffset(o => o - 2)}
              title="Step 2 hours earlier"
            >
              <Icon name="chevronLeft" size={14} />
              <span>Earlier</span>
            </button>
            <button
              type="button"
              className={`btn ${offset === 0 ? 'primary' : ''}`}
              onClick={jumpToNow}
              title="Jump to current live air time"
            >
              <span className="dot" style={{ background: '#ef4444' }} />
              <span>Live Now</span>
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => setOffset(o => o + 2)}
              title="Step 2 hours later"
            >
              <span>Later</span>
              <Icon name="chevronRight" size={14} />
            </button>
            <Link className="btn" to={`${org.base}/schedule`}>
              <Icon name="calendar" size={14} />
              <span>Schedule Editor</span>
            </Link>
          </div>
        }
      />

      {/* Top 4-KPI Metric Cards (Harmonized with Channels, Schedule, Watch) */}
      <div className="grid g4 library-kpi-grid">
        <div className="card library-kpi-card color-primary">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-primary">
              <Icon name="grid" size={18} />
            </span>
            <span className="badge primary sm">Syndicated EPG</span>
          </div>
          <div>
            <div className="library-kpi-val">
              {published.length}{' '}
              <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                / {channels.data?.length ?? 0}
              </span>
            </div>
            <div className="library-kpi-label">Active Guide Channels</div>
            <div className="library-kpi-sub faint small">Channels with published timetable</div>
          </div>
        </div>

        <div className="card library-kpi-card color-emerald">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-emerald">
              <Icon name="activity" size={18} />
            </span>
            <span className="badge ok sm">Broadcasting</span>
          </div>
          <div>
            <div className="library-kpi-val" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span>{liveBroadcastingCount}</span>
              {liveBroadcastingCount > 0 && (
                <span className="channel-sound-waves" title="On Air Signals">
                  <span /><span /><span /><span />
                </span>
              )}
            </div>
            <div className="library-kpi-label">Live Channels On Air</div>
            <div className="library-kpi-sub faint small">Click channel header to open Watch Live</div>
          </div>
        </div>

        <div className="card library-kpi-card color-cyan">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-cyan">
              <Icon name="film" size={18} />
            </span>
            <span className="badge info sm">{HOURS}h Viewport</span>
          </div>
          <div>
            <div className="library-kpi-val">{visibleSlotsCount}</div>
            <div className="library-kpi-label">Programmes in Window</div>
            <div className="library-kpi-sub faint small">
              {totalBreaksCount} commercial pods ({totalBreakMinutes}m ad time)
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-purple">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-purple">
              <Icon name="megaphone" size={18} />
            </span>
            <span className="badge sim sm">SSAI Monetized</span>
          </div>
          <div>
            <div className="library-kpi-val">{totalBreaksCount}</div>
            <div className="library-kpi-label">Scheduled Ad Pods</div>
            <div className="library-kpi-sub faint small">Dynamic SSAI & SCTE-35 insertion</div>
          </div>
        </div>
      </div>

      {/* Interactive Guide Controls & Filter Bar */}
      <div
        className="card card-pad"
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 12,
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          {/* Quick Search */}
          <div style={{ position: 'relative', minWidth: 240, flex: 1 }}>
            <span style={{ position: 'absolute', left: 10, top: 10, color: 'var(--text-3)' }}>
              <Icon name="search" size={15} />
            </span>
            <input
              type="text"
              className="input sm"
              style={{ paddingLeft: 32, width: '100%' }}
              placeholder="Search programmes or channels..."
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
            />
            {searchQuery && (
              <button
                type="button"
                className="btn ghost icon-btn"
                style={{ position: 'absolute', right: 4, top: 4, width: 24, height: 24, padding: 0 }}
                onClick={() => setSearchQuery('')}
              >
                <Icon name="x" size={12} />
              </button>
            )}
          </div>

          {/* Ad Break View Mode Switcher (Pods vs Seamless) */}
          <div
            className="row"
            style={{
              gap: 4,
              background: 'var(--surface-2)',
              padding: '3px 4px',
              borderRadius: 8,
              border: '1px solid var(--border)',
            }}
          >
            <button
              type="button"
              className={`btn sm ${viewMode === 'pods' ? 'primary' : 'ghost'}`}
              style={{ height: 28, padding: '0 10px', fontSize: 11.5 }}
              onClick={() => setViewMode('pods')}
              title="Display commercial ad break pods in amber"
            >
              <span>⚡ Show Ad Pods</span>
            </button>
            <button
              type="button"
              className={`btn sm ${viewMode === 'continuous' ? 'primary' : 'ghost'}`}
              style={{ height: 28, padding: '0 10px', fontSize: 11.5 }}
              onClick={() => setViewMode('continuous')}
              title="Continuous programming without gaps (Consumer TV Guide mode)"
            >
              <span>Seamless EPG</span>
            </button>
          </div>

          {/* Daypart Jump Shortcuts */}
          <div className="row" style={{ gap: 6 }}>
            <span className="faint small" style={{ marginRight: 2, fontWeight: 600 }}>Daypart:</span>
            <button
              type="button"
              className="btn sm"
              style={{ height: 30, padding: '0 9px', fontSize: 11 }}
              onClick={() => jumpToHour(6)}
            >
              6 AM
            </button>
            <button
              type="button"
              className="btn sm"
              style={{ height: 30, padding: '0 9px', fontSize: 11 }}
              onClick={() => jumpToHour(12)}
            >
              12 PM
            </button>
            <button
              type="button"
              className="btn sm"
              style={{ height: 30, padding: '0 9px', fontSize: 11 }}
              onClick={() => jumpToHour(18)}
            >
              6 PM
            </button>
            <button
              type="button"
              className="btn sm"
              style={{ height: 30, padding: '0 9px', fontSize: 11 }}
              onClick={() => jumpToHour(22)}
            >
              10 PM
            </button>
          </div>

          {/* Viewport Zoom Dropdown */}
          <div style={{ width: 210 }}>
            <Dropdown
              options={zoomOptions}
              value={String(zoomIdx)}
              onChange={v => setZoomIdx(parseInt(v, 10))}
              align="full"
            />
          </div>
        </div>

        {/* Genre Filter Chips */}
        <div className="row-wrap" style={{ gap: 6, paddingTop: 4, borderTop: '1px solid var(--border)' }}>
          <button
            type="button"
            className={`chip ${selectedGenre === 'ALL' ? 'active' : ''}`}
            style={{ cursor: 'pointer', height: 28, fontSize: 11 }}
            onClick={() => setSelectedGenre('ALL')}
          >
            All Genres ({visibleSlotsCount})
          </button>
          {allGenres.map(g => (
            <button
              key={g}
              type="button"
              className={`chip ${selectedGenre === g ? 'active' : ''}`}
              style={{ cursor: 'pointer', height: 28, fontSize: 11 }}
              onClick={() => setSelectedGenre(g === selectedGenre ? 'ALL' : g)}
            >
              <span
                style={{
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  background: GENRE_COLORS[g.toLowerCase()] ?? 'var(--primary)',
                  display: 'inline-block',
                  marginRight: 4,
                }}
              />
              {g}
            </button>
          ))}
        </div>
      </div>

      {/* Main EPG Timetable Grid */}
      {!channels.data ? (
        <Loading />
      ) : published.length === 0 ? (
        <div className="card" style={{ padding: 48 }}>
          <Empty
            icon="grid"
            title="No Published Linear Channels"
            text="Publish an air schedule for at least one channel to generate the electronic programme guide."
            action={
              <Link className="btn primary" to={`${org.base}/channels`}>
                Go to Channels
              </Link>
            }
          />
        </div>
      ) : (
        <div className="epg-container">
          <div ref={scrollRef} className="epg-scroll-area">
            {/* The Outer 2-Column Grid: Left col 240px, Right col timelineWidth */}
            <div
              className="epg-grid"
              style={{
                gridTemplateColumns: `240px ${timelineWidth}px`,
                width: 240 + timelineWidth,
              }}
            >
              {/* Row 1, Col 1: Sticky Top-Left Corner */}
              <div className="epg-time-corner">
                <div className="stack" style={{ gap: 2 }}>
                  <b style={{ fontSize: 12.5, color: 'var(--text)' }}>
                    {fmtDayTime(from.toISOString()).split(',')[0]}
                  </b>
                  <span className="faint" style={{ fontSize: 10.5 }}>
                    {offset === 0 ? 'Live Air Timetable' : offset > 0 ? `+${offset}h Forward` : `${offset}h Past`}
                  </span>
                </div>
                <span className="badge sm" style={{ fontSize: 10, padding: '1px 6px' }}>
                  {HOURS}h span
                </span>
              </div>

              {/* Row 1, Col 2: Sticky Top Time Ruler */}
              <div
                className="epg-time-ruler"
                style={{ width: timelineWidth, position: 'relative' }}
              >
                {Array.from({ length: Math.ceil(HOURS * 2) }, (_, i) => {
                  const tickTime = new Date(t0 + i * 1_800_000)
                  return (
                    <div
                      key={i}
                      className="tl-tick"
                      style={{
                        left: (i * PX) / 2,
                        position: 'absolute',
                        height: '100%',
                        display: 'flex',
                        alignItems: 'center',
                        borderLeft: '1px solid var(--border)',
                        paddingLeft: 8,
                        color: 'var(--text-2)',
                        fontSize: 11.5,
                        fontWeight: 600,
                        fontFamily: 'var(--mono)',
                      }}
                    >
                      {fmtTime(tickTime.toISOString())}
                    </div>
                  )
                })}

                {/* Real-time moving playhead on the time ruler (Matches Local Browser Time!) */}
                {nowMs >= t0 && nowMs <= t1 && (
                  <div
                    className="tl-now"
                    style={{
                      left: px(nowMs),
                      top: 0,
                      bottom: 0,
                      position: 'absolute',
                      zIndex: 35,
                      transition: 'left 1s linear',
                    }}
                  >
                    <div
                      style={{
                        position: 'absolute',
                        top: 7,
                        left: 0,
                        transform: 'translateX(-50%)',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 5,
                        fontSize: 10.5,
                        fontWeight: 700,
                        color: '#fff',
                        background: '#ef4444',
                        padding: '3px 8px',
                        borderRadius: 5,
                        boxShadow: '0 2px 10px rgba(239,68,68,0.6)',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <span className="dot" style={{ background: '#fff' }} />
                      <span>LIVE {fmtTimeSec(new Date(nowMs).toISOString())}</span>
                    </div>
                  </div>
                )}
              </div>

              {/* Channel Rows */}
              {filteredPublished.map(c => {
                const list = (guides[c.channel.id] ?? []).slice().sort((a, b) => new Date(a.start).getTime() - new Date(b.start).getTime())
                const isLive = c.channel.status === 'LIVE'

                return [
                  /* Col 1: Left Sticky Channel Column - CLICK OPENS WATCH LIVE */
                  <div
                    key={c.channel.id + '-col'}
                    className="epg-ch"
                    onClick={() => nav(`${org.base}/watch?channel=${c.channel.slug}`)}
                    title={`Click to watch ${c.channel.name} live broadcast`}
                    role="button"
                    tabIndex={0}
                  >
                    <ChannelLogo name={c.channel.name} color={c.channel.color} size={38} />
                    <div className="grow" style={{ overflow: 'hidden' }}>
                      <div className="row" style={{ gap: 6, marginBottom: 2 }}>
                        <b
                          className="ellipsis"
                          style={{ fontSize: 13, color: 'var(--text)', display: 'block' }}
                        >
                          {c.channel.name}
                        </b>
                        {isLive && (
                          <span className="badge live sm" style={{ padding: '0 4px', fontSize: 9 }}>
                            ON AIR
                          </span>
                        )}
                      </div>

                      <div className="between" style={{ alignItems: 'center' }}>
                        <span className="faint small">v{c.publishedVersion ?? 1}</span>
                        <span className="epg-ch-watch-tag">
                          <span>Watch</span>
                          <Icon name="arrowRight" size={10} />
                        </span>
                      </div>
                    </div>

                    {/* Quick XMLTV button that doesn't trigger channel navigation */}
                    <button
                      type="button"
                      className="btn ghost icon-btn sm"
                      title="View & copy XMLTV syndication feed"
                      onClick={e => {
                        e.stopPropagation()
                        setXmlModalChannel(c.channel)
                      }}
                      style={{ width: 26, height: 26, padding: 0, opacity: 0.7 }}
                    >
                      <Icon name="code" size={13} />
                    </button>
                  </div>,

                  /* Col 2: Programme Timeline Row */
                  <div
                    key={c.channel.id + '-row'}
                    className="epg-row"
                    style={{ width: timelineWidth, position: 'relative' }}
                  >
                    {/* Synchronized Live Playhead Needle */}
                    {nowMs >= t0 && nowMs <= t1 && (
                      <div
                        style={{
                          left: px(nowMs),
                          top: 0,
                          bottom: 0,
                          position: 'absolute',
                          zIndex: 12,
                          pointerEvents: 'none',
                          borderLeft: '2px solid rgba(239, 68, 68, 0.85)',
                          boxShadow: '0 0 8px rgba(239, 68, 68, 0.4)',
                          transition: 'left 1s linear',
                        }}
                      />
                    )}

                    {/* Render Segments (Programmes + Ad Pods) */}
                    {list.map((p, idx) => {
                      const s = new Date(p.start).getTime()
                      const origEnd = new Date(p.end).getTime()

                      // If viewMode is continuous, extend this programme to the start of the next programme to eliminate gaps seamlessly
                      let effectiveEnd = origEnd
                      const nextP = list[idx + 1]
                      if (viewMode === 'continuous' && nextP) {
                        const nextStart = new Date(nextP.start).getTime()
                        if (nextStart > origEnd) {
                          effectiveEnd = nextStart
                        }
                      }

                      if (effectiveEnd <= t0 || s >= t1) return null

                      const left = px(s)
                      const w = px(effectiveEnd) - left
                      const isNow = nowMs >= s && nowMs < origEnd

                      const genreKey = p.genre?.toLowerCase() ?? 'drama'
                      const genreBorder = GENRE_COLORS[genreKey] ?? 'var(--primary)'

                      // Check if there is an ad break after this programme (in 'pods' mode)
                      let breakElement: React.ReactNode = null
                      if (viewMode === 'pods' && nextP) {
                        const gapStart = origEnd
                        const gapEnd = new Date(nextP.start).getTime()
                        const gapDuration = Math.round((gapEnd - gapStart) / 1000)

                        if (gapDuration >= 5 && gapEnd > t0 && gapStart < t1) {
                          const breakLeft = px(gapStart)
                          const breakWidth = px(gapEnd) - breakLeft
                          const breakIsNow = nowMs >= gapStart && nowMs < gapEnd
                          const breakAds = resolveBreakAds(p.end, nextP.start, gapDuration, campaigns, nowMs)
                          const currentAd = breakAds.currentSpot

                          breakElement = (
                            <button
                              key={`break-${p.start}-${gapStart}`}
                              type="button"
                              className={`epg-break-block ${breakIsNow ? 'now' : ''}`}
                              style={{
                                left: breakLeft + 1,
                                width: Math.max(breakWidth - 2, 8),
                              }}
                              onClick={() =>
                                setSelectedBreak({
                                  start: p.end,
                                  end: nextP.start,
                                  durationSecs: gapDuration,
                                  channel: c.channel,
                                })
                              }
                              onMouseEnter={e => {
                                const pos = calcHoverPos(e.currentTarget)
                                setHoveredItem({
                                  kind: 'break',
                                  start: p.end,
                                  end: nextP.start,
                                  durationSecs: gapDuration,
                                  channel: c.channel,
                                  isNow: breakIsNow,
                                  ...pos,
                                })
                              }}
                              onMouseLeave={() => setHoveredItem(null)}
                            >
                              {breakWidth >= 70 ? (
                                <div
                                  className="row ellipsis"
                                  style={{
                                    gap: 5,
                                    alignItems: 'center',
                                    fontSize: 10.5,
                                    fontWeight: 700,
                                    color: breakIsNow ? '#ef4444' : '#fbbf24',
                                    padding: '0 6px',
                                  }}
                                >
                                  {breakIsNow && <span className="epg-now-dot" />}
                                  <span className="ellipsis">
                                    {breakIsNow && currentAd
                                      ? `⚡ ${currentAd.title.split('—')[0].trim()} (${currentAd.elapsedSecs}s)`
                                      : `⚡ ${gapDuration}s Pod`}
                                  </span>
                                </div>
                              ) : (
                                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                                  <span style={{ fontSize: 11, fontWeight: 700, color: breakIsNow ? '#ef4444' : '#fbbf24' }}>
                                    ⚡
                                  </span>
                                  {breakIsNow && <span className="epg-now-dot" style={{ width: 4, height: 4 }} />}
                                </div>
                              )}
                            </button>
                          )
                        }
                      }

                      return (
                        <div key={`group-${p.start}`}>
                          {/* Programme Block */}
                          <button
                            type="button"
                            className={`epg-prog ${isNow ? 'now' : ''}`}
                            style={{
                              left: left + 1,
                              width: Math.max(w - 2, 10),
                              borderLeft: `3.5px solid ${isNow ? 'var(--primary)' : genreBorder}`,
                            }}
                            onClick={() => setSelectedProg({ p, channel: c.channel })}
                            onMouseEnter={e => {
                              const pos = calcHoverPos(e.currentTarget)
                              setHoveredItem({
                                kind: 'prog',
                                p,
                                channel: c.channel,
                                ...pos,
                              })
                            }}
                            onMouseLeave={() => setHoveredItem(null)}
                          >
                            {/* Case 1: Narrow Block (< 65px) - Clean title + pulsing dot, no bulky badge */}
                            {w < 65 ? (
                              <div className="row ellipsis" style={{ gap: 4, alignItems: 'center' }}>
                                {isNow && <span className="epg-now-dot" />}
                                <span
                                  className="ellipsis"
                                  style={{
                                    fontSize: 11.5,
                                    fontWeight: 600,
                                    color: isNow ? '#fff' : 'var(--text)',
                                  }}
                                >
                                  {p.title}
                                </span>
                              </div>
                            ) : /* Case 2: Medium Block (65px - 120px) - Title + Air Start */
                            w < 120 ? (
                              <div className="stack" style={{ gap: 2, overflow: 'hidden' }}>
                                <div className="row ellipsis" style={{ gap: 5, alignItems: 'center' }}>
                                  {isNow && <span className="epg-now-dot" />}
                                  <span
                                    className="ellipsis"
                                    style={{
                                      fontSize: 12,
                                      fontWeight: 600,
                                      color: isNow ? '#fff' : 'var(--text)',
                                    }}
                                  >
                                    {p.title}
                                  </span>
                                </div>
                                <span className="faint ellipsis" style={{ fontSize: 10.5 }}>
                                  {fmtTime(p.start)}
                                </span>
                              </div>
                            ) : (
                              /* Case 3: Wide Block (>= 120px) - Full title, airtime range, genre tag */
                              <div className="stack" style={{ gap: 2, overflow: 'hidden' }}>
                                <div className="row ellipsis" style={{ gap: 6, alignItems: 'center' }}>
                                  {isNow && (
                                    <span className="epg-prog-now-badge">
                                      <span className="dot" style={{ background: '#fff' }} /> LIVE
                                    </span>
                                  )}
                                  <span
                                    className="ellipsis"
                                    style={{
                                      fontSize: 12.5,
                                      fontWeight: 600,
                                      color: isNow ? '#fff' : 'var(--text)',
                                    }}
                                  >
                                    {p.title}
                                  </span>
                                </div>

                                <div className="between small faint" style={{ fontSize: 11, marginTop: 2 }}>
                                  <span className="ellipsis">
                                    {fmtTime(p.start)} – {fmtTime(new Date(effectiveEnd).toISOString())}
                                  </span>
                                  {w > 180 && p.genre && (
                                    <span
                                      className="badge sm"
                                      style={{
                                        fontSize: 9.5,
                                        padding: '1px 5px',
                                        background: 'rgba(255,255,255,0.08)',
                                      }}
                                    >
                                      {p.genre}
                                    </span>
                                  )}
                                </div>

                                {w > 200 && p.adBreaks > 0 && (
                                  <div className="faint small" style={{ fontSize: 10, marginTop: 1 }}>
                                    {p.adBreaks} ad break{p.adBreaks > 1 ? 's' : ''} (SSAI)
                                  </div>
                                )}
                              </div>
                            )}
                          </button>

                          {/* Commercial Ad Break Pod (rendered in 'pods' mode) */}
                          {breakElement}
                        </div>
                      )
                    })}
                  </div>,
                ]
              })}
            </div>
          </div>
        </div>
      )}

      {/* Floating Instant Hover Card Preview - Snug 8px anchoring, with detailed ad pod playout */}
      {hoveredItem && (
        <div
          className={`epg-hover-card place-${hoveredItem.arrowPlacement === 'bottom' ? 'above' : 'below'}`}
          style={{
            left: hoveredItem.x,
            top: hoveredItem.y,
          }}
        >
          {/* Arrow indicator pointing directly at the hovered programme or ad break */}
          <div
            className={`epg-hover-arrow ${hoveredItem.arrowPlacement}`}
            style={{ left: hoveredItem.arrowX }}
          />

          {hoveredItem.kind === 'prog' ? (
            <div>
              {hoveredItem.p.thumbUrl && (
                <div className="epg-hover-thumb">
                  <img src={hoveredItem.p.thumbUrl} alt={hoveredItem.p.title} />
                  <div className="epg-hover-thumb-overlay" />
                </div>
              )}

              <div className="between" style={{ marginBottom: 6, alignItems: 'center' }}>
                <span
                  className="badge sm"
                  style={{
                    background: GENRE_COLORS[hoveredItem.p.genre?.toLowerCase() ?? 'drama'] ?? 'var(--primary)',
                    color: '#fff',
                    fontSize: 10,
                    padding: '2px 7px',
                  }}
                >
                  {hoveredItem.p.genre || 'General'}
                </span>
                {nowMs >= new Date(hoveredItem.p.start).getTime() &&
                nowMs < new Date(hoveredItem.p.end).getTime() ? (
                  <span className="badge live sm" style={{ padding: '2px 6px', fontSize: 10 }}>
                    <span className="dot" /> ON AIR NOW
                  </span>
                ) : (
                  <span className="badge sm faint" style={{ fontSize: 10 }}>Upcoming</span>
                )}
              </div>

              <b style={{ fontSize: 13.5, display: 'block', lineHeight: 1.3, color: '#fff' }}>
                {hoveredItem.p.title}
              </b>

              <div className="small faint" style={{ marginTop: 4, display: 'flex', alignItems: 'center', gap: 6 }}>
                <span>
                  {fmtTime(hoveredItem.p.start)} – {fmtTime(hoveredItem.p.end)}
                </span>
                <span>·</span>
                <span>
                  {fmtDur(new Date(hoveredItem.p.end).getTime() - new Date(hoveredItem.p.start).getTime())}
                </span>
              </div>

              <div className="row" style={{ gap: 6, marginTop: 8, fontSize: 11.5, color: 'var(--text-2)', alignItems: 'center' }}>
                <ChannelLogo name={hoveredItem.channel.name} color={hoveredItem.channel.color} size={18} />
                <span style={{ fontWeight: 600, color: '#fff' }}>{hoveredItem.channel.name}</span>
                {hoveredItem.p.adBreaks > 0 && (
                  <span className="badge sim sm" style={{ fontSize: 9.5, padding: '1px 5px', marginLeft: 'auto' }}>
                    {hoveredItem.p.adBreaks} Ad Break{hoveredItem.p.adBreaks > 1 ? 's' : ''}
                  </span>
                )}
              </div>

              <div
                style={{
                  marginTop: 10,
                  paddingTop: 6,
                  borderTop: '1px solid rgba(255,255,255,0.1)',
                  fontSize: 10.5,
                  color: 'var(--primary)',
                  fontWeight: 600,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <span>Click to open details & watch options</span>
                <Icon name="arrowRight" size={12} />
              </div>
            </div>
          ) : (
            /* Commercial Ad Break Hover Card - Displays active ad & pod sequence */
            (() => {
              const breakAds = resolveBreakAds(
                hoveredItem.start,
                hoveredItem.end,
                hoveredItem.durationSecs,
                campaigns,
                nowMs
              )
              const { spots, currentSpot, currentSpotIndex, fillRatePercent } = breakAds

              return (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  <div className="between" style={{ alignItems: 'center' }}>
                    <span
                      className="badge sm"
                      style={{
                        background: '#f59e0b',
                        color: '#000',
                        fontWeight: 700,
                        fontSize: 10,
                        padding: '2px 7px',
                      }}
                    >
                      ⚡ SSAI Ad Pod
                    </span>
                    {hoveredItem.isNow ? (
                      <span className="badge live sm" style={{ padding: '2px 6px', fontSize: 10 }}>
                        <span className="dot" /> ON AIR NOW
                      </span>
                    ) : (
                      <span className="badge sm faint" style={{ fontSize: 10 }}>
                        Upcoming ({fmtTime(hoveredItem.start)})
                      </span>
                    )}
                  </div>

                  <div>
                    <b style={{ fontSize: 13.5, display: 'block', lineHeight: 1.3, color: '#fff' }}>
                      {hoveredItem.durationSecs}s Commercial Ad Break
                    </b>
                    <div className="small faint" style={{ marginTop: 2, display: 'flex', alignItems: 'center', gap: 6 }}>
                      <span>
                        {fmtTime(hoveredItem.start)} – {fmtTime(hoveredItem.end)}
                      </span>
                      <span>·</span>
                      <span style={{ color: '#fbbf24', fontWeight: 600 }}>{spots.length} Ad Spots</span>
                    </div>
                  </div>

                  {/* ACTIVE AIR HIGHLIGHT: Displays which ad is currently playing right now */}
                  {hoveredItem.isNow && currentSpot && (
                    <div
                      style={{
                        padding: '8px 10px',
                        background: 'rgba(239, 68, 68, 0.16)',
                        border: '1px solid rgba(239, 68, 68, 0.45)',
                        borderRadius: 8,
                      }}
                    >
                      <div className="between" style={{ alignItems: 'center', marginBottom: 4 }}>
                        <span
                          style={{
                            fontSize: 10,
                            fontWeight: 700,
                            color: '#f87171',
                            textTransform: 'uppercase',
                            letterSpacing: '0.04em',
                            display: 'flex',
                            alignItems: 'center',
                            gap: 5,
                          }}
                        >
                          <span className="dot" style={{ background: '#ef4444', width: 6, height: 6 }} />
                          PLAYING NOW (SPOT {currentSpotIndex + 1}/{spots.length})
                        </span>
                        <span style={{ fontSize: 10, fontFamily: 'var(--mono)', color: '#fca5a5' }}>
                          {currentSpot.elapsedSecs}s / {currentSpot.durationSecs}s
                        </span>
                      </div>
                      <div style={{ fontSize: 12.5, fontWeight: 700, color: '#fff', lineHeight: 1.3 }}>
                        {currentSpot.title}
                      </div>
                      <div className="small faint" style={{ fontSize: 11, color: '#fecaca', marginTop: 1 }}>
                        {currentSpot.advertiser}
                      </div>

                      {/* Live progress indicator */}
                      <div
                        style={{
                          marginTop: 6,
                          height: 4,
                          background: 'rgba(255,255,255,0.15)',
                          borderRadius: 2,
                          overflow: 'hidden',
                        }}
                      >
                        <div
                          style={{
                            height: '100%',
                            background: '#ef4444',
                            width: `${Math.min(
                              100,
                              Math.max(0, (currentSpot.elapsedSecs / currentSpot.durationSecs) * 100)
                            )}%`,
                            transition: 'width 1s linear',
                          }}
                        />
                      </div>
                    </div>
                  )}

                  {/* Scheduled Ad Spot Lineup */}
                  <div>
                    <div className="between" style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-3)', marginBottom: 4 }}>
                      <span>SCHEDULED POD SPOTS</span>
                      <span style={{ color: '#fbbf24' }}>{fillRatePercent}% FILLED</span>
                    </div>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                      {spots.map((spot, sIdx) => {
                        const isCurrentSpot = hoveredItem.isNow && spot === currentSpot
                        const isPastSpot = hoveredItem.isNow && nowMs >= spot.endMs

                        return (
                          <div
                            key={sIdx}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: 7,
                              padding: '3px 6px',
                              borderRadius: 5,
                              background: isCurrentSpot
                                ? 'rgba(239, 68, 68, 0.22)'
                                : 'rgba(255,255,255,0.03)',
                              border: isCurrentSpot
                                ? '1px solid rgba(239, 68, 68, 0.4)'
                                : '1px solid rgba(255,255,255,0.05)',
                              fontSize: 11,
                            }}
                          >
                            <span
                              style={{
                                width: 15,
                                height: 15,
                                borderRadius: '50%',
                                background: isCurrentSpot
                                  ? '#ef4444'
                                  : isPastSpot
                                  ? 'var(--ok)'
                                  : 'rgba(255,255,255,0.12)',
                                color: '#fff',
                                fontSize: 9,
                                fontWeight: 700,
                                display: 'inline-flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                flexShrink: 0,
                              }}
                            >
                              {isPastSpot ? '✓' : sIdx + 1}
                            </span>
                            <div className="grow ellipsis" style={{ lineHeight: 1.2 }}>
                              <span
                                className="ellipsis"
                                style={{
                                  fontWeight: 600,
                                  color: isCurrentSpot ? '#fff' : 'var(--text)',
                                  display: 'block',
                                }}
                              >
                                {spot.title}
                              </span>
                            </div>
                            <span
                              style={{
                                fontFamily: 'var(--mono)',
                                fontSize: 10,
                                color: isCurrentSpot ? '#fca5a5' : 'var(--text-3)',
                                flexShrink: 0,
                              }}
                            >
                              {spot.durationSecs}s
                            </span>
                          </div>
                        )
                      })}
                    </div>
                  </div>

                  <div className="row" style={{ gap: 6, marginTop: 4, fontSize: 11.5, color: 'var(--text-2)', alignItems: 'center' }}>
                    <ChannelLogo name={hoveredItem.channel.name} color={hoveredItem.channel.color} size={18} />
                    <span style={{ fontWeight: 600, color: '#fff' }}>{hoveredItem.channel.name}</span>
                  </div>

                  <div
                    style={{
                      paddingTop: 6,
                      borderTop: '1px solid rgba(255,255,255,0.1)',
                      fontSize: 10.5,
                      color: '#fbbf24',
                      fontWeight: 600,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                    }}
                  >
                    <span>Click to inspect SCTE-35 & SSAI details</span>
                    <Icon name="arrowRight" size={12} />
                  </div>
                </div>
              )
            })()
          )}
        </div>
      )}

      {/* Programme Details Drawer / Modal ("Every click has meaning") */}
      {selectedProg && (
        <Modal
          title={selectedProg.p.title}
          subtitle={`${selectedProg.channel.name} · ${fmtDayTime(selectedProg.p.start)} – ${fmtTime(
            selectedProg.p.end
          )}`}
          onClose={() => setSelectedProg(null)}
          size="md"
          footer={
            <div className="between" style={{ width: '100%' }}>
              <button
                type="button"
                className="btn ghost"
                onClick={() => {
                  const meta = `${selectedProg.p.title} | ${selectedProg.channel.name} | ${fmtDayTime(
                    selectedProg.p.start
                  )} | ${selectedProg.p.genre}`
                  navigator.clipboard.writeText(meta)
                  toast('Copied programme metadata', 'ok')
                }}
              >
                <Icon name="link" size={14} />
                <span>Copy Info</span>
              </button>

              <div className="row" style={{ gap: 8 }}>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    nav(`${org.base}/schedule?channel=${selectedProg.channel.slug}`)
                    setSelectedProg(null)
                  }}
                >
                  <Icon name="calendar" size={14} />
                  <span>Open in Schedule</span>
                </button>

                <button
                  type="button"
                  className="btn primary"
                  onClick={() => {
                    nav(`${org.base}/watch?channel=${selectedProg.channel.slug}`)
                    setSelectedProg(null)
                  }}
                >
                  <Icon name="play" size={14} />
                  <span>
                    {nowMs >= new Date(selectedProg.p.start).getTime() &&
                    nowMs < new Date(selectedProg.p.end).getTime()
                      ? 'Watch Live on Air Now'
                      : `Watch ${selectedProg.channel.name}`}
                  </span>
                </button>
              </div>
            </div>
          }
        >
          <div className="stack" style={{ gap: 16 }}>
            {/* Live Indicator Callout if on air */}
            {nowMs >= new Date(selectedProg.p.start).getTime() &&
            nowMs < new Date(selectedProg.p.end).getTime() ? (
              <div className="callout ok" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span className="dot" style={{ background: 'var(--ok)' }} />
                <div>
                  <b style={{ fontSize: 13.5 }}>Broadcasting Live on Air Right Now</b>
                  <div className="small faint">
                    Playing on {selectedProg.channel.name} (
                    {fmtTime(selectedProg.p.start)} – {fmtTime(selectedProg.p.end)})
                  </div>
                </div>
              </div>
            ) : null}

            {/* Thumbnail Poster Preview */}
            {selectedProg.p.thumbUrl ? (
              <div
                style={{
                  borderRadius: 10,
                  overflow: 'hidden',
                  maxHeight: 200,
                  background: '#000',
                  border: '1px solid var(--border)',
                }}
              >
                <img
                  src={selectedProg.p.thumbUrl}
                  alt={selectedProg.p.title}
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                />
              </div>
            ) : null}

            {/* Synopsis */}
            <div>
              <b className="small" style={{ color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                Synopsis
              </b>
              <p style={{ marginTop: 4, lineHeight: 1.6, fontSize: 13.5 }}>
                {selectedProg.p.description || 'No detailed programme synopsis provided in media catalog metadata.'}
              </p>
            </div>

            {/* Technical & Commercial Airtime Metadata */}
            <div
              className="card card-pad"
              style={{
                background: 'var(--surface-2)',
                display: 'grid',
                gridTemplateColumns: 'repeat(2, 1fr)',
                gap: 12,
              }}
            >
              <div>
                <span className="faint small" style={{ display: 'block' }}>Genre Classification</span>
                <span className="badge info sm" style={{ marginTop: 4 }}>
                  {selectedProg.p.genre || 'General'}
                </span>
              </div>

              <div>
                <span className="faint small" style={{ display: 'block' }}>Broadcast Runtime</span>
                <b style={{ fontSize: 13, marginTop: 4, display: 'block' }}>
                  {fmtDur(new Date(selectedProg.p.end).getTime() - new Date(selectedProg.p.start).getTime())}
                </b>
              </div>

              <div>
                <span className="faint small" style={{ display: 'block' }}>Commercial Monetization</span>
                <span className="badge sim sm" style={{ marginTop: 4 }}>
                  {selectedProg.p.adBreaks} Ad Break{selectedProg.p.adBreaks === 1 ? '' : 's'} (SSAI / SCTE-35)
                </span>
              </div>

              <div>
                <span className="faint small" style={{ display: 'block' }}>Air Channel</span>
                <div className="row" style={{ gap: 6, marginTop: 4 }}>
                  <ChannelLogo
                    name={selectedProg.channel.name}
                    color={selectedProg.channel.color}
                    size={18}
                  />
                  <b style={{ fontSize: 13 }}>{selectedProg.channel.name}</b>
                </div>
              </div>
            </div>
          </div>
        </Modal>
      )}

      {/* Commercial Ad Break Details Modal */}
      {selectedBreak && (
        <Modal
          title={`Commercial Ad Break · ${selectedBreak.durationSecs}s Pod`}
          subtitle={`${selectedBreak.channel.name} · ${fmtDayTime(selectedBreak.start)} – ${fmtTime(selectedBreak.end)}`}
          onClose={() => setSelectedBreak(null)}
          size="md"
          footer={
            <div className="between" style={{ width: '100%' }}>
              <button
                type="button"
                className="btn ghost"
                onClick={() => {
                  navigator.clipboard.writeText(
                    `Commercial Ad Break: ${selectedBreak.durationSecs}s on ${selectedBreak.channel.name} (${fmtTime(selectedBreak.start)} - ${fmtTime(selectedBreak.end)})`
                  )
                  toast('Copied ad break timing', 'ok')
                }}
              >
                <Icon name="link" size={14} />
                <span>Copy Details</span>
              </button>
              <div className="row" style={{ gap: 8 }}>
                <button
                  type="button"
                  className="btn"
                  onClick={() => {
                    nav(`${org.base}/schedule?channel=${selectedBreak.channel.slug}`)
                    setSelectedBreak(null)
                  }}
                >
                  <Icon name="calendar" size={14} />
                  <span>Open in Schedule Editor</span>
                </button>
                <button
                  type="button"
                  className="btn primary"
                  onClick={() => {
                    nav(`${org.base}/watch?channel=${selectedBreak.channel.slug}`)
                    setSelectedBreak(null)
                  }}
                >
                  <Icon name="play" size={14} />
                  <span>Watch Live Playout</span>
                </button>
              </div>
            </div>
          }
        >
          {(() => {
            const breakAds = resolveBreakAds(
              selectedBreak.start,
              selectedBreak.end,
              selectedBreak.durationSecs,
              campaigns,
              nowMs
            )
            const isNow =
              nowMs >= new Date(selectedBreak.start).getTime() &&
              nowMs < new Date(selectedBreak.end).getTime()

            return (
              <div className="stack" style={{ gap: 14 }}>
                {/* On Air Now Banner */}
                {isNow && breakAds.currentSpot ? (
                  <div
                    className="callout alert"
                    style={{
                      background: 'rgba(239, 68, 68, 0.15)',
                      border: '1px solid rgba(239, 68, 68, 0.45)',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 8,
                    }}
                  >
                    <div className="between" style={{ alignItems: 'center' }}>
                      <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                        <span className="dot" style={{ background: '#ef4444', width: 8, height: 8 }} />
                        <b style={{ fontSize: 13.5, color: '#f87171' }}>
                          Commercial Pod On Air Right Now
                        </b>
                      </div>
                      <span className="badge live sm">
                        SPOT {breakAds.currentSpotIndex + 1} OF {breakAds.spots.length}
                      </span>
                    </div>

                    <div style={{ background: 'rgba(0, 0, 0, 0.3)', padding: '10px 12px', borderRadius: 8 }}>
                      <div className="between" style={{ alignItems: 'center' }}>
                        <b style={{ fontSize: 14, color: '#fff' }}>{breakAds.currentSpot.title}</b>
                        <span style={{ fontFamily: 'var(--mono)', fontSize: 12, color: '#fca5a5' }}>
                          {breakAds.currentSpot.elapsedSecs}s / {breakAds.currentSpot.durationSecs}s
                        </span>
                      </div>
                      <div className="small faint" style={{ color: '#fecaca', marginTop: 2 }}>
                        {breakAds.currentSpot.advertiser}
                      </div>

                      <div
                        style={{
                          marginTop: 8,
                          height: 5,
                          background: 'rgba(255,255,255,0.15)',
                          borderRadius: 3,
                          overflow: 'hidden',
                        }}
                      >
                        <div
                          style={{
                            height: '100%',
                            background: '#ef4444',
                            width: `${Math.min(
                              100,
                              Math.max(
                                0,
                                (breakAds.currentSpot.elapsedSecs / breakAds.currentSpot.durationSecs) * 100
                              )
                            )}%`,
                            transition: 'width 1s linear',
                          }}
                        />
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className="callout info" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <span style={{ fontSize: 18 }}>⚡</span>
                    <div>
                      <b style={{ fontSize: 13.5 }}>Dynamic Commercial Insertion Window</b>
                      <div className="small faint">
                        {selectedBreak.durationSecs}s allocated for targeted sponsor campaigns or distributor ad fill.
                      </div>
                    </div>
                  </div>
                )}

                {/* Technical Metric Cards */}
                <div
                  className="card card-pad"
                  style={{
                    background: 'var(--surface-2)',
                    display: 'grid',
                    gridTemplateColumns: 'repeat(2, 1fr)',
                    gap: 12,
                  }}
                >
                  <div>
                    <span className="faint small" style={{ display: 'block' }}>Break Window</span>
                    <b style={{ fontSize: 13, marginTop: 4, display: 'block' }}>
                      {fmtTime(selectedBreak.start)} – {fmtTime(selectedBreak.end)}
                    </b>
                  </div>
                  <div>
                    <span className="faint small" style={{ display: 'block' }}>Pod Duration</span>
                    <b style={{ fontSize: 13, marginTop: 4, display: 'block' }}>
                      {selectedBreak.durationSecs}s ({breakAds.spots.length} Ad Spots)
                    </b>
                  </div>
                  <div>
                    <span className="faint small" style={{ display: 'block' }}>SSAI Fill Rate</span>
                    <span className="badge ok sm" style={{ marginTop: 4 }}>
                      {breakAds.fillRatePercent}% Filled ({breakAds.filledSecs}s / {selectedBreak.durationSecs}s)
                    </span>
                  </div>
                  <div>
                    <span className="faint small" style={{ display: 'block' }}>Broadcast Channel</span>
                    <div className="row" style={{ gap: 6, marginTop: 4 }}>
                      <ChannelLogo name={selectedBreak.channel.name} color={selectedBreak.channel.color} size={18} />
                      <b style={{ fontSize: 13 }}>{selectedBreak.channel.name}</b>
                    </div>
                  </div>
                </div>

                {/* Ad Spot Lineup Details */}
                <div>
                  <b className="small" style={{ color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                    Scheduled Ad Pod Lineup
                  </b>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
                    {breakAds.spots.map((spot, sIdx) => {
                      const isCurrentSpot = isNow && spot === breakAds.currentSpot
                      const isPastSpot = isNow && nowMs >= spot.endMs

                      return (
                        <div
                          key={sIdx}
                          style={{
                            padding: '10px 12px',
                            borderRadius: 8,
                            background: isCurrentSpot ? 'rgba(239, 68, 68, 0.14)' : 'var(--surface-2)',
                            border: isCurrentSpot ? '1px solid rgba(239, 68, 68, 0.4)' : '1px solid var(--border)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            gap: 12,
                          }}
                        >
                          <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                            <span
                              style={{
                                width: 24,
                                height: 24,
                                borderRadius: '50%',
                                background: isCurrentSpot
                                  ? '#ef4444'
                                  : isPastSpot
                                  ? 'var(--ok)'
                                  : 'rgba(255,255,255,0.12)',
                                color: '#fff',
                                fontSize: 11,
                                fontWeight: 700,
                                display: 'inline-flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                flexShrink: 0,
                              }}
                            >
                              {isPastSpot ? '✓' : sIdx + 1}
                            </span>
                            <div>
                              <div className="row" style={{ gap: 6, alignItems: 'center' }}>
                                <b style={{ fontSize: 13, color: isCurrentSpot ? '#fff' : 'var(--text)' }}>
                                  {spot.title}
                                </b>
                                {isCurrentSpot && (
                                  <span className="badge live sm" style={{ fontSize: 9, padding: '1px 5px' }}>
                                    ON AIR
                                  </span>
                                )}
                              </div>
                              <div className="small faint" style={{ fontSize: 11, marginTop: 1 }}>
                                {spot.advertiser} · {spot.durationSecs}s Spot {spot.cpm ? `· CPM $${Number(spot.cpm).toFixed(2)}` : ''}
                              </div>
                            </div>
                          </div>

                          <div style={{ textAlign: 'right', flexShrink: 0 }}>
                            <span className="badge sm" style={{ background: 'rgba(255,255,255,0.08)' }}>
                              {fmtTime(new Date(spot.startMs).toISOString())}
                            </span>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                </div>

                {/* SCTE-35 Marker Payload */}
                <div>
                  <b className="small" style={{ color: 'var(--text-2)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                    SCTE-35 Playout Marker
                  </b>
                  <div
                    style={{
                      background: '#090e1a',
                      border: '1px solid rgba(255,255,255,0.08)',
                      borderRadius: 8,
                      padding: '10px 12px',
                      fontFamily: 'var(--mono)',
                      fontSize: 11.5,
                      color: '#fbbf24',
                      wordBreak: 'break-all',
                      marginTop: 6,
                    }}
                  >
                    #EXT-X-DATERANGE:ID="break-{selectedBreak.channel.slug}-{selectedBreak.start.replace(/[^a-zA-Z0-9]/g, '')}",START-DATE="{selectedBreak.start}",PLANNED-DURATION={selectedBreak.durationSecs}.0,SCTE35-OUT=0xFC302500000000...
                  </div>
                </div>
              </div>
            )
          })()}
        </Modal>
      )}

      {/* XMLTV Syndication Modal */}
      {xmlModalChannel && (
        <Modal
          title={`XMLTV Syndication Feed · ${xmlModalChannel.name}`}
          subtitle="Standard electronic programme guide XML format accepted by Roku, Plex, Samsung TV Plus, and Pluto TV."
          onClose={() => setXmlModalChannel(null)}
          size="md"
          footer={
            <div className="between" style={{ width: '100%' }}>
              <button
                type="button"
                className="btn"
                onClick={() => copyEpgXmlUrl(xmlModalChannel)}
              >
                <Icon name="link" size={14} />
                <span>Copy Feed URL</span>
              </button>

              <div className="row" style={{ gap: 8 }}>
                <a
                  className="btn primary"
                  href={`/api/orgs/${org.slug}/channels/${xmlModalChannel.id}/epg.xml`}
                  download={`${xmlModalChannel.slug}.xml`}
                >
                  <Icon name="upload" size={14} />
                  <span>Download XML File</span>
                </a>
              </div>
            </div>
          }
        >
          <div className="stack" style={{ gap: 14 }}>
            <p className="small muted" style={{ lineHeight: 1.5, margin: 0 }}>
              This XMLTV endpoint produces an industry-standard XML feed updated dynamically as programming changes.
              Partners can poll this endpoint periodically to populate consumer-facing EPG grids.
            </p>

            <div
              style={{
                background: '#090e1a',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 8,
                padding: '12px 14px',
                fontFamily: 'var(--mono)',
                fontSize: 12,
                color: '#93c5fd',
                wordBreak: 'break-all',
              }}
            >
              {window.location.origin}/api/orgs/{org.slug}/channels/{xmlModalChannel.id}/epg.xml
            </div>

            <div className="callout info small">
              <Icon name="info" size={16} />
              <div>
                <b>Syndication Parity Guarantee:</b> XMLTV timestamps use Coordinated Universal Time (UTC) with exact SCTE-35 break markers and episode metadata.
              </div>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}
