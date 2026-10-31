import { useEffect, useMemo, useRef, useState } from 'react'
import { type Entry, type Issue } from '../../shared/api'
import {
  fmtDayTime,
  fmtDur,
  fmtTime,
  fmtTimeSec,
  Icon,
  InfoTip,
} from '../../shared/ui'
import { type AssetRef, ZOOM } from './scheduleShared'

export function Timeline({
  entries,
  assets,
  from,
  to,
  nowMs,
  pxPerHour,
  tz,
  selected,
  onSelect,
  errorIds,
  zoom,
  setZoom,
}: {
  entries: Entry[]
  assets: Record<string, AssetRef>
  from: string
  to: string
  nowMs: number
  pxPerHour: number
  tz: string
  selected: string | null
  onSelect: (id: string) => void
  errorIds: Set<string | undefined>
  zoom: keyof typeof ZOOM
  setZoom: (z: keyof typeof ZOOM) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const t0 = new Date(from).getTime()
  const t1 = new Date(to).getTime()
  const px = (t: number) => ((t - t0) / 3_600_000) * pxPerHour
  const width = px(t1)

  const ticks = useMemo(() => {
    const out: number[] = []
    const step = pxPerHour >= 1000 ? 600_000 : pxPerHour >= 190 ? 1_800_000 : 3_600_000
    for (let t = Math.ceil(t0 / step) * step; t < t1; t += step) out.push(t)
    return out
  }, [t0, t1, pxPerHour])

  const jumpToLive = () => {
    if (ref.current) {
      const livePx = px(nowMs) - 150
      ref.current.scrollTo({ left: Math.max(0, livePx), behavior: 'smooth' })
    }
  }

  useEffect(() => {
    if (ref.current) ref.current.scrollLeft = Math.max(0, px(nowMs) - 120)
  }, [pxPerHour, from])

  useEffect(() => {
    const el = selected ? ref.current?.querySelector<HTMLElement>(`[data-id="${selected}"]`) : null
    if (el && ref.current) ref.current.scrollLeft = Math.max(0, el.offsetLeft - 120)
  }, [selected])

  if (entries.length === 0) {
    return (
      <div className="callout warn" style={{ marginTop: 8 }}>
        <Icon name="calendar" />
        <div>
          Nothing scheduled in this period. Use <b>Auto-fill</b> to generate a continuous schedule from your video assets.
        </div>
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: 10 }}>
      {/* Timeline Controls Header */}
      <div className="timeline-header-bar">
        <div className="row" style={{ gap: 10 }}>
          <div className="seg sm">
            {(Object.keys(ZOOM) as (keyof typeof ZOOM)[]).map(z => (
              <button key={z} className={zoom === z ? 'active' : ''} onClick={() => setZoom(z)}>
                {z}
              </button>
            ))}
          </div>

          <button
            type="button"
            className="tl-jump-live-btn"
            onClick={jumpToLive}
            title="Center timeline on active broadcasting playhead"
          >
            <span className="tl-live-dot" /> Jump to LIVE
          </button>
        </div>

        <div className="row small" style={{ gap: 12 }}>
          <span className="row" style={{ gap: 5 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: '#6366f1' }} />
            Programme
          </span>
          <span className="row" style={{ gap: 5 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: '#f59e0b' }} />
            Our SSAI Ad Break
          </span>
          <span className="row" style={{ gap: 5 }}>
            <span style={{ width: 10, height: 10, borderRadius: 2, background: '#64748b' }} />
            Platform Break
          </span>
          <InfoTip text="Ad breaks are split between you and distribution platforms. SCTE-35 markers signal whose spot it is." />
        </div>
      </div>

      <div className="timeline" ref={ref}>
        <div className="tl-inner" style={{ width }}>
          <div className="tl-axis">
            {ticks.map(t => (
              <div key={t} className="tl-tick" style={{ left: px(t) }}>
                {fmtTime(new Date(t).toISOString(), tz)}
              </div>
            ))}
          </div>
          <div className="tl-lane">
            {ticks.map(t => (
              <div key={'grid-' + t} className="tl-grid-line" style={{ left: px(t) }} />
            ))}
            {entries.map(e => {
              const s = new Date(e.startAt).getTime()
              const en = new Date(e.endAt).getTime()
              if (en <= t0 || s >= t1) return null
              const a = e.assetId ? assets[e.assetId] : undefined
              const isBreak = e.kind === 'AD_BREAK'
              const left = px(Math.max(s, t0))
              const w = Math.max(4, px(Math.min(en, t1)) - left)
              const durText = fmtDur(en - s)
              const titleText = isBreak ? (w > 70 ? 'Commercial Pod' : 'Ad') : (a?.title ?? 'Programme')
              const fullTooltip = `${isBreak ? 'Ad Break' : a?.title} (${fmtTime(e.startAt, tz)} - ${fmtTime(e.endAt, tz)}, ${durText})`

              return (
                <div
                  key={e.id}
                  data-id={e.id}
                  role="button"
                  tabIndex={0}
                  title={fullTooltip}
                  className={`tl-block ${isBreak ? `break ${e.ownership === 'DISTRIBUTOR' ? 'dist' : ''}` : ''} ${selected === e.id ? 'sel' : ''} ${errorIds.has(e.id) ? 'err' : ''}`}
                  style={{
                    left,
                    width: w,
                    background: isBreak ? undefined : a?.color ?? '#6366f1',
                  }}
                  onClick={() => onSelect(e.id)}
                  onKeyDown={k => k.key === 'Enter' && onSelect(e.id)}
                >
                  <div className="tl-block-header">
                    {w > 40 && (
                      <span className="tl-block-title">
                        {titleText}
                      </span>
                    )}
                  </div>
                  {w > 48 && (
                    <div className="tl-block-meta">
                      <span>{fmtTime(e.startAt, tz)}</span>
                      {w > 72 && (
                        <span className="tl-block-pill">
                          {durText}
                        </span>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
          {nowMs >= t0 && nowMs <= t1 && (
            <div className="tl-now" style={{ left: px(nowMs) }}>
              <div
                className="tl-now-badge"
                title={`Live Broadcast Playhead: ${fmtTimeSec(new Date(nowMs).toISOString(), tz)} (${tz})`}
              >
                <span className="tl-now-dot" />
                <span>LIVE</span>
                <span className="tl-now-time">{fmtTimeSec(new Date(nowMs).toISOString(), tz)}</span>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

export function Selected({
  e,
  a,
  tz,
  editable,
  busy,
  issues,
  onDelete,
  onReplace,
  onClose,
}: {
  e: Entry
  a?: AssetRef
  tz: string
  editable: boolean
  busy: boolean
  issues: Issue[]
  onDelete: () => void
  onReplace?: () => void
  onClose: () => void
}) {
  const isBreak = e.kind === 'AD_BREAK'
  return (
    <div className="card card-pad stack" style={{ background: 'var(--surface-2)', border: '1px solid var(--border-strong)', borderRadius: 8, marginTop: 10 }}>
      <div className="between">
        <div className="row">
          {isBreak ? (
            <span className="badge warn">
              <Icon name="megaphone" size={12} /> Ad Break
            </span>
          ) : (
            <span className="badge info">Programme</span>
          )}
          <b>
            {isBreak ? `${fmtDur(new Date(e.endAt).getTime() - new Date(e.startAt).getTime())} Commercial Pod` : a?.title}
          </b>
        </div>
        <button type="button" className="btn ghost sm icon-btn" onClick={onClose} aria-label="Close details">
          <Icon name="x" size={15} />
        </button>
      </div>

      <div className="kv" style={{ fontSize: 12 }}>
        <span>Airtime Window</span>
        <span>
          {fmtDayTime(e.startAt, tz)} – {fmtTime(e.endAt, tz)} ({fmtDur(new Date(e.endAt).getTime() - new Date(e.startAt).getTime())})
        </span>

        {isBreak ? (
          <>
            <span>Inventory Owner</span>
            <span>
              {e.ownership === 'DISTRIBUTOR' ? 'Distribution Platform (Partner Monetization)' : 'Owned & Operated (Channel SSAI)'}
            </span>
            <span>SCTE-35 Marker</span>
            <span className="mono" style={{ fontSize: 11 }}>
              {e.ownership === 'DISTRIBUTOR' ? '0x36 Distributor Opportunity' : '0x34 Provider Opportunity'}
            </span>
          </>
        ) : (
          <>
            <span>Segment Offset</span>
            <span>
              {fmtDur(e.assetOffsetMs)} → {fmtDur(e.assetOffsetMs + new Date(e.endAt).getTime() - new Date(e.startAt).getTime())} (of {a ? fmtDur(a.durationMs) : '?'})
            </span>
          </>
        )}
      </div>

      {issues.map((i, n) => (
        <div key={n} className={`callout ${i.severity === 'ERROR' ? 'bad' : 'warn'}`}>
          <Icon name="alert" />
          <div>
            <b>{i.message}</b>
            <div className="small">→ {i.hint}</div>
          </div>
        </div>
      ))}

      {editable && (
        <div className="row" style={{ marginTop: 8, gap: 8, flexWrap: 'wrap' }}>
          {!isBreak && onReplace && (
            <button
              type="button"
              className="btn primary sm"
              disabled={busy}
              onClick={onReplace}
              title="Auto-assisted swap with duration matching and gap closure"
            >
              <Icon name="refresh" size={13} />
              Replace / Swap Video...
            </button>
          )}
          <button type="button" className="btn danger sm" disabled={busy} onClick={onDelete}>
            <Icon name="trash" size={14} />
            Remove {isBreak ? 'Ad Break' : 'Programme'}
          </button>
          {!isBreak && (
            <span className="faint" style={{ fontSize: 11 }}>
              💡 Swapping preserves airtime window and adjusts SCTE-35 commercial breaks.
            </span>
          )}
        </div>
      )}
    </div>
  )
}

export function EntryList({
  entries,
  assets,
  tz,
  nowMs,
  errorIds,
  onSelect,
  selected,
  editable,
  busy,
  onReplaceEntry,
  onDeleteEntry,
}: {
  entries: Entry[]
  assets: Record<string, AssetRef>
  tz: string
  nowMs: number
  errorIds: Set<string | undefined>
  onSelect: (id: string) => void
  selected: string | null
  editable?: boolean
  busy?: boolean
  onReplaceEntry?: (e: Entry) => void
  onDeleteEntry?: (e: Entry) => void
}) {
  const [showPast, setShowPast] = useState(false)
  const [search, setSearch] = useState('')
  const [filterType, setFilterType] = useState<'all' | 'program' | 'break' | 'conflict'>('all')

  const t = nowMs

  const conflictCount = useMemo(() => entries.filter(e => errorIds.has(e.id)).length, [entries, errorIds])
  const progCount = useMemo(() => entries.filter(e => e.kind !== 'AD_BREAK').length, [entries])
  const breakCount = useMemo(() => entries.filter(e => e.kind === 'AD_BREAK').length, [entries])

  const rows = useMemo(() => {
    let res = entries.filter(e => showPast || new Date(e.endAt).getTime() > t)
    if (filterType === 'program') {
      res = res.filter(e => e.kind !== 'AD_BREAK')
    } else if (filterType === 'break') {
      res = res.filter(e => e.kind === 'AD_BREAK')
    } else if (filterType === 'conflict') {
      res = res.filter(e => errorIds.has(e.id))
    }
    if (search.trim()) {
      const q = search.toLowerCase()
      res = res.filter(e => {
        const title = e.assetId ? assets[e.assetId]?.title?.toLowerCase() : ''
        return title?.includes(q) || (e.kind === 'AD_BREAK' && 'ad break commercial pod'.includes(q))
      })
    }
    return res
  }, [entries, showPast, t, filterType, search, assets, errorIds])

  return (
    <section className="card library-table-card" style={{ marginTop: 0 }}>
      <div className="card-head" style={{ padding: '14px 18px', borderBottom: '1px solid var(--border)' }}>
        <div>
          <h2 style={{ fontSize: 16 }}>Running Order Timetable</h2>
          <p className="faint small" style={{ margin: 0 }}>
            Chronological linear broadcast log with real-time playout state and keyboard navigation
          </p>
        </div>

        <div className="row" style={{ gap: 12 }}>
          <label className="check small" style={{ userSelect: 'none', cursor: 'pointer' }}>
            <input type="checkbox" checked={showPast} onChange={e => setShowPast(e.target.checked)} />
            <span>Show Past Broadcasts</span>
          </label>
        </div>
      </div>

      {/* Filter and Search Toolbar (Matching Media Library & Channels design language) */}
      <div
        className="row"
        style={{
          padding: '10px 16px',
          gap: 12,
          borderBottom: '1px solid var(--border)',
          background: 'var(--surface)',
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        {/* Search Input using .library-search-wrap */}
        <div className={`library-search-wrap ${search ? 'has-value' : ''}`} style={{ maxWidth: 280, height: 34 }}>
          <Icon name="search" size={13} />
          <input
            type="text"
            className="library-search-input"
            placeholder="Filter running order..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            aria-label="Filter running order"
          />
          {search && (
            <button
              type="button"
              className="library-search-clear"
              onClick={() => setSearch('')}
              aria-label="Clear filter"
              title="Clear search"
            >
              <Icon name="x" size={11} />
            </button>
          )}
        </div>

        {/* Filter Format Tabs using .library-format-tabs */}
        <div className="library-format-tabs" role="tablist">
          <button
            type="button"
            className={`library-tab-btn ${filterType === 'all' ? 'is-active' : ''}`}
            onClick={() => setFilterType('all')}
          >
            <span>All Items</span>
            <span className="library-tab-count">{entries.length}</span>
          </button>
          <button
            type="button"
            className={`library-tab-btn ${filterType === 'program' ? 'is-active' : ''}`}
            onClick={() => setFilterType('program')}
          >
            <Icon name="film" size={12} />
            <span>Programmes</span>
            <span className="library-tab-count">{progCount}</span>
          </button>
          <button
            type="button"
            className={`library-tab-btn ${filterType === 'break' ? 'is-active' : ''}`}
            onClick={() => setFilterType('break')}
          >
            <Icon name="megaphone" size={12} />
            <span>Ad Breaks</span>
            <span className="library-tab-count">{breakCount}</span>
          </button>
          {conflictCount > 0 && (
            <button
              type="button"
              className={`library-tab-btn ${filterType === 'conflict' ? 'is-active' : ''}`}
              onClick={() => setFilterType('conflict')}
              style={{ color: 'var(--bad)' }}
            >
              <Icon name="alert" size={12} />
              <span>Conflicts</span>
              <span className="library-tab-count warn">{conflictCount}</span>
            </button>
          )}
        </div>

        <div className="grow" />
        <span className="faint small" style={{ fontSize: 11.5 }}>
          Showing <b>{rows.length}</b> of {entries.length} item(s)
        </span>
      </div>

      <div style={{ maxHeight: 440, overflowY: 'auto' }}>
        <table className="library-table">
          <thead>
            <tr>
              <th style={{ width: 105 }}>Air Time</th>
              <th>Programme / Content</th>
              <th style={{ width: 140 }}>Type & Monetization</th>
              <th style={{ width: 95 }}>Duration</th>
              <th style={{ width: 100, textAlign: 'center' }}>Playout State</th>
              {editable && <th style={{ width: 135, textAlign: 'right' }}>Actions</th>}
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, 250).map(e => {
              const a = e.assetId ? assets[e.assetId] : undefined
              const isLiveNow = new Date(e.startAt).getTime() <= t && new Date(e.endAt).getTime() > t
              const isBreak = e.kind === 'AD_BREAK'
              const dur = new Date(e.endAt).getTime() - new Date(e.startAt).getTime()
              const isSelected = selected === e.id

              return (
                <tr
                  key={e.id}
                  className={`library-table-row ${isSelected ? 'selected-row' : ''}`}
                  onClick={() => onSelect(e.id)}
                  style={isSelected ? { background: 'var(--primary-soft)' } : undefined}
                >
                  <td className="mono" style={{ fontSize: 12, fontWeight: 550, color: isLiveNow ? 'var(--primary)' : 'var(--text)' }}>
                    {fmtTime(e.startAt, tz)}
                  </td>
                  <td>
                    <div className="row" style={{ gap: 10 }}>
                      <div
                        className="library-table-thumb"
                        style={{
                          width: 36,
                          height: 24,
                          borderRadius: 5,
                          background: isBreak ? 'linear-gradient(135deg, #f59e0b, #d97706)' : a?.color ?? '#6366f1',
                          display: 'grid',
                          placeItems: 'center',
                          color: '#fff',
                          flexShrink: 0,
                        }}
                      >
                        <Icon name={isBreak ? 'megaphone' : 'film'} size={13} />
                      </div>
                      <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                        <b className="library-table-title ellipsis" style={{ fontSize: 13 }}>
                          {isBreak ? 'Commercial Ad Pod' : a?.title ?? 'Unknown Video'}
                          {e.assetOffsetMs > 0 && <span className="faint small" style={{ fontWeight: 400, marginLeft: 4 }}>(pt. 2)</span>}
                        </b>
                        <span className="faint small ellipsis">
                          {isBreak
                            ? (e.ownership === 'DISTRIBUTOR' ? 'Partner platform inventory (0x36)' : 'Channel SSAI dynamic inventory (0x34)')
                            : (e.assetOffsetMs > 0 ? `Offset: ${fmtDur(e.assetOffsetMs)}` : 'Continuous broadcast showing')}
                        </span>
                      </div>
                    </div>
                  </td>
                  <td>
                    {isBreak ? (
                      <span className="badge warn sm" style={{ fontSize: 10.5 }}>
                        <Icon name="megaphone" size={10} /> {e.ownership === 'DISTRIBUTOR' ? "Partner's" : 'SSAI Break'}
                      </span>
                    ) : (
                      <span className="badge default sm" style={{ fontSize: 10.5 }}>
                        {a?.kind || 'SHOW'}
                      </span>
                    )}
                  </td>
                  <td className="mono" style={{ fontSize: 12 }}>
                    {fmtDur(dur)}
                  </td>
                  <td style={{ textAlign: 'center' }}>
                    {isLiveNow ? (
                      <span className="badge live sm">
                        <span className="dot" /> ON AIR
                      </span>
                    ) : errorIds.has(e.id) ? (
                      <span className="badge bad sm">Conflict</span>
                    ) : (
                      <span className="badge default sm" style={{ opacity: 0.75, fontSize: 10.5 }}>
                        Scheduled
                      </span>
                    )}
                  </td>
                  {editable && (
                    <td style={{ textAlign: 'right' }} onClick={ev => ev.stopPropagation()}>
                      <div className="row" style={{ justifyContent: 'flex-end', gap: 6 }}>
                        {!isBreak && onReplaceEntry && (
                          <button
                            type="button"
                            className="table-row-action-btn"
                            title="Auto-assisted swap / replace video"
                            disabled={busy}
                            onClick={() => onReplaceEntry(e)}
                          >
                            <Icon name="refresh" size={12} /> Replace
                          </button>
                        )}
                        {onDeleteEntry && (
                          <button
                            type="button"
                            className="table-row-action-btn danger"
                            title={isBreak ? 'Remove commercial break' : 'Remove programme'}
                            disabled={busy}
                            onClick={() => onDeleteEntry(e)}
                          >
                            <Icon name="trash" size={12} />
                          </button>
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
        {rows.length > 250 && <p className="faint card-pad" style={{ margin: 0, padding: 12, textAlign: 'center', fontSize: 12 }}>Showing first 250 items.</p>}
      </div>
    </section>
  )
}
