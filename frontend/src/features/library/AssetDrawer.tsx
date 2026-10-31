import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Hls from 'hls.js'
import { api, type AssetView, type MediaSettingsView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Confirm,
  Drawer,
  flag,
  fmtDur,
  Icon,
  InfoTip,
  Status,
  useAction,
  useApi,
} from '../../shared/ui'
import { GenreSelector } from '../../shared/components/GenreSelector'
import { formatMediaKind } from './mediaKind'

/** Redesigned Asset Drawer with Preview Player, Interactive Cue Timeline, and Easy Ad Insertion */
export function AssetDrawer({ view, onClose, onChange }: { view: AssetView; onClose: () => void; onChange: () => void }) {
  const org = useOrg()
  const a = view.asset
  const { busy, run } = useAction()
  const videoRef = useRef<HTMLVideoElement>(null)
  const [activeTab, setActiveTab] = useState<'overview' | 'cues' | 'rights' | 'edit'>('overview')
  const [title, setTitle] = useState(a.title)
  const [description, setDescription] = useState(a.description)
  const [genre, setGenre] = useState(a.genre)
  const mediaSettings = useApi<MediaSettingsView>(`/api/orgs/${org.slug}/settings`)
  const [mediaType, setMediaType] = useState(a.mediaType)
  const editKind = mediaSettings.data?.types.find(t => t.key === mediaType)?.kind ?? a.kind
  const [series, setSeries] = useState(a.series ?? '')
  const [season, setSeason] = useState(a.season ?? 1)
  const [episode, setEpisode] = useState(a.episode ?? 1)
  const [confirmArchive, setConfirmArchive] = useState(false)
  const [currentTimeSec, setCurrentTimeSec] = useState(0)
  const [manualMinutes, setManualMinutes] = useState('')
  const [manualSeconds, setManualSeconds] = useState('')

  const cues = a.cuePoints
    ? a.cuePoints.split(',').filter(Boolean).map(Number).sort((x, y) => x - y)
    : []
  const editable = org.can('ASSETS_EDIT')
  const durationSec = Math.max(1, Math.round(a.durationMs / 1000))

  // Setup HLS video preview
  useEffect(() => {
    const v = videoRef.current
    if (!v || !view.previewUrl || a.status !== 'READY') return
    if (!Hls.isSupported()) {
      v.src = view.previewUrl
      return
    }
    const hls = new Hls()
    hls.loadSource(view.previewUrl)
    hls.attachMedia(v)

    const onTime = () => setCurrentTimeSec(Math.round(v.currentTime))
    v.addEventListener('timeupdate', onTime)

    return () => {
      v.removeEventListener('timeupdate', onTime)
      hls.destroy()
    }
  }, [view.previewUrl, a.status])

  const save = (body: object, msg: string) =>
    run(() => api(`/api/orgs/${org.slug}/assets/${a.id}`, { method: 'PATCH', body }), msg).then(onChange)

  const canHaveBreaks = !a.durationMs || a.durationMs >= 10000
  const maxValidCue = a.durationMs > 0 ? Math.floor((a.durationMs - 1) / 5000) * 5000 : 3600000

  // Smart Add Cue Point handler (snaps cleanly, avoids duplicates, handles segment boundaries)
  const handleAddBreak = (targetMs: number) => {
    if (a.durationMs > 0 && a.durationMs < 10000) {
      alert(`Media duration (${fmtDur(a.durationMs)}) is too short for SSAI ad breaks. A minimum of 10 seconds (2 broadcast segments) is required.`)
      return
    }
    // Snap to the 5000 ms segment boundary
    let snapped = Math.round(targetMs / 5000) * 5000
    if (snapped < 5000) snapped = 5000
    if (snapped > maxValidCue) snapped = maxValidCue

    if (cues.includes(snapped)) {
      alert(`An ad break already exists at ${fmtDur(snapped)}.`)
      return
    }

    const updated = [...cues, snapped].sort((x, y) => x - y)
    save({ cuePoints: updated }, `Ad break added at ${fmtDur(snapped)}`)
  }

  // Seek video to exact time
  const seekTo = (sec: number) => {
    if (videoRef.current) {
      videoRef.current.currentTime = sec
      setCurrentTimeSec(Math.round(sec))
      videoRef.current.play().catch(() => {})
    }
  }

  // Handle manual time input submission
  const handleManualAdd = (e: React.FormEvent) => {
    e.preventDefault()
    const m = parseInt(manualMinutes || '0', 10)
    const s = parseInt(manualSeconds || '0', 10)
    const totalMs = (m * 60 + s) * 1000
    if (totalMs > 0) {
      handleAddBreak(totalMs)
      setManualMinutes('')
      setManualSeconds('')
    }
  }

  return (
    <Drawer onClose={onClose}>
      {/* Header */}
      <div className="modal-head library-drawer-head">
        <div>
          <div className="row" style={{ gap: 8, alignItems: 'center' }}>
            <h2>{a.title}</h2>
            <Status value={a.status} />
          </div>
          <div className="row-wrap" style={{ gap: 6, marginTop: 4 }}>
            <span className="chip sm">{formatMediaKind(a.kind)}</span>
            <span className="chip sm">{fmtDur(a.durationMs)}</span>
            <span className="chip sm">{a.genre || 'General'}</span>
          </div>
        </div>
        <button type="button" className="btn ghost icon-btn" onClick={onClose} aria-label="Close">
          <Icon name="x" size={16} />
        </button>
      </div>

      <div className="modal-body library-drawer-body">
        {/* Built-in Video Player */}
        {a.status === 'READY' ? (
          <div className="library-player-container">
            <div className="player library-preview-player">
              <video ref={videoRef} controls playsInline />
            </div>

            {/* Interactive Broadcast Breakpoint Scrubber */}
            <div className="library-timeline-panel">
              {/* Header: Title, Break Counter, and Playhead Timer */}
              <div className="library-timeline-panel-head">
                <div className="library-timeline-panel-title">
                  <span className="library-timeline-icon">
                    <Icon name="megaphone" size={13} />
                  </span>
                  <b>SSAI Breakpoints Timeline</b>
                  <span className={`badge sm ${cues.length > 0 ? 'amber' : 'neutral'}`}>
                    {cues.length} {cues.length === 1 ? 'break' : 'breaks'}
                  </span>
                </div>
                <div className="library-timeline-timer">
                  <span className="library-timer-current">{fmtDur(currentTimeSec * 1000)}</span>
                  <span className="library-timer-sep">/</span>
                  <span className="library-timer-total">{fmtDur(a.durationMs)}</span>
                </div>
              </div>

              {/* Scrubber Track */}
              <div
                className="library-timeline-track"
                onClick={e => {
                  const rect = e.currentTarget.getBoundingClientRect()
                  const pos = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
                  seekTo(pos * durationSec)
                }}
                title="Click anywhere to scrub playhead"
              >
                {/* 5-Second Segment Ticks (Visual representation of broadcast segment boundaries) */}
                <div className="library-timeline-ticks">
                  {Array.from({ length: Math.min(40, Math.floor((a.durationMs || 30000) / 5000)) }).map((_, i) => (
                    <div
                      key={i}
                      className="library-timeline-tick"
                      style={{ left: `${((i + 1) * 5000 / (a.durationMs || 1)) * 100}%` }}
                    />
                  ))}
                </div>

                {/* Progress bar fill */}
                <div
                  className="library-timeline-progress"
                  style={{ width: `${Math.min(100, (currentTimeSec / durationSec) * 100)}%` }}
                >
                  <div className="library-timeline-knob" />
                </div>

                {/* Breakpoint pins (SSAI Cue Markers) */}
                {cues.map((c, idx) => {
                  const pct = Math.min(100, Math.max(0, (c / (a.durationMs || 1)) * 100))
                  return (
                    <button
                      key={c}
                      type="button"
                      className="library-timeline-cue-marker"
                      style={{ left: `${pct}%` }}
                      title={`Break #${idx + 1} at ${fmtDur(c)} (Click to jump)`}
                      onClick={ev => {
                        ev.stopPropagation()
                        seekTo(c / 1000)
                      }}
                    >
                      <span className="cue-marker-pin-head">
                        <span className="cue-marker-label">#{idx + 1}</span>
                      </span>
                      <span className="cue-marker-stem" />
                      <div className="cue-marker-tooltip">
                        Break #{idx + 1} · {fmtDur(c)}
                      </div>
                    </button>
                  )
                })}
              </div>

              {/* Quick Jump Break Pills strip */}
              <div className="library-timeline-footer">
                <span className="library-timeline-boundary-lbl">0s</span>
                <div className="library-timeline-pills-list">
                  {cues.length > 0 ? (
                    cues.map((c, idx) => (
                      <button
                        key={c}
                        type="button"
                        className="library-timeline-jump-pill"
                        onClick={() => seekTo(c / 1000)}
                        title={`Jump playhead to Break #${idx + 1} at ${fmtDur(c)}`}
                      >
                        <span className="jump-pill-dot" />
                        <b>Break #{idx + 1}</b>
                        <span>{fmtDur(c)}</span>
                      </button>
                    ))
                  ) : (
                    <span className="library-timeline-no-cues-note">
                      No ad breaks placed on timeline yet
                    </span>
                  )}
                </div>
                <span className="library-timeline-boundary-lbl">{fmtDur(a.durationMs)}</span>
              </div>
            </div>
          </div>
        ) : (
          <div className="thumb library-preview-thumb" style={{ background: a.color || 'var(--surface-3)' }}>
            <div className="stack" style={{ alignItems: 'center', textAlign: 'center', padding: 24, gap: 10 }}>
              {a.status === 'PROCESSING' ? (
                <>
                  <span className="spinner" style={{ width: 28, height: 28, borderWidth: 3 }} />
                  <b>Transcoding Video</b>
                  <p className="faint small" style={{ maxWidth: 320 }}>
                    Converting video to 640×360 and cutting into 5-second segments for live broadcast.
                  </p>
                </>
              ) : a.status === 'FAILED' ? (
                <>
                  <Icon name="alert" size={32} />
                  <b>Transcoding Failed</b>
                  <p className="faint small">{a.statusDetail || 'Error processing source video.'}</p>
                  {editable && (
                    <button
                      type="button"
                      className="btn sm"
                      disabled={busy}
                      onClick={() =>
                        run(() => api(`/api/orgs/${org.slug}/assets/${a.id}/retry`, { method: 'POST' }), 'Retrying transcode').then(onChange)
                      }
                    >
                      <Icon name="refresh" size={14} /> Retry processing
                    </button>
                  )}
                </>
              ) : (
                <>
                  <Icon name="upload" size={32} />
                  <b>Waiting for Source Video File</b>
                  <p className="faint small">Upload a video file to start encoding.</p>
                </>
              )}
            </div>
          </div>
        )}

        {/* Tab Navigation */}
        <div className="library-drawer-tabs" role="tablist">
          <button
            type="button"
            className={`library-drawer-tab-btn ${activeTab === 'overview' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('overview')}
          >
            <Icon name="film" size={13} />
            <span>Overview</span>
          </button>
          {a.kind !== 'AD' && (
            <button
              type="button"
              className={`library-drawer-tab-btn ${activeTab === 'cues' ? 'is-active' : ''}`}
              onClick={() => setActiveTab('cues')}
            >
              <Icon name="megaphone" size={13} />
              <span>Ad Breaks</span>
              <span className="library-tab-count">{cues.length}</span>
            </button>
          )}
          {a.kind !== 'AD' && (
            <button
              type="button"
              className={`library-drawer-tab-btn ${activeTab === 'rights' ? 'is-active' : ''}`}
              onClick={() => setActiveTab('rights')}
            >
              <Icon name="shieldCheck" size={13} />
              <span>Licence</span>
            </button>
          )}
          <button
            type="button"
            className={`library-drawer-tab-btn ${activeTab === 'edit' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('edit')}
          >
            <Icon name="settings" size={13} />
            <span>Edit</span>
          </button>
        </div>

        {/* TAB 1: Overview */}
        {activeTab === 'overview' && (
          <div className="stack" style={{ gap: 14 }}>
            <div className="card card-pad stack">
              <h3>Media Details</h3>
              <div className="kv">
                <span>Format</span>
                <b>{formatMediaKind(a.kind)}</b>
                <span>Genre</span>
                <span>{a.genre || 'Unspecified'}</span>
                <span>Duration</span>
                <span style={{ fontVariantNumeric: 'tabular-nums' }}>{fmtDur(a.durationMs)}</span>
                <span>Encoding Detail</span>
                <span className="small faint">{a.statusDetail || '640×360 HLS live chunks'}</span>
                {a.kind !== 'AD' && (
                  <>
                    <span>Licence Rights</span>
                    <span><Status value={view.rightsStatus} /></span>
                  </>
                )}
              </div>
            </div>

            {a.description && (
              <div className="card card-pad stack">
                <h3>Synopsis</h3>
                <p className="faint" style={{ whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>
                  {a.description}
                </p>
              </div>
            )}

            <div className="card card-pad stack">
              <h3>Scheduled Linear Channels</h3>
              {view.channels.length > 0 ? (
                <div className="row-wrap" style={{ gap: 8 }}>
                  {view.channels.map(c => (
                    <span key={c} className="chip">
                      <Icon name="tv" size={13} /> <b>{c}</b>
                    </span>
                  ))}
                </div>
              ) : (
                <p className="faint small">This title is not currently assigned to any published channel schedule.</p>
              )}
            </div>
          </div>
        )}

        {/* TAB 2: Ad Breaks (Cue Points) — Easy Mode */}
        {activeTab === 'cues' && a.kind !== 'AD' && (
          <div className="stack" style={{ gap: 14 }}>
            <div className="card card-pad stack" style={{ gap: 14 }}>
              <div className="between" style={{ alignItems: 'flex-start' }}>
                <div>
                  <div className="row" style={{ gap: 6 }}>
                    <h3>Commercial Break Points (SSAI)</h3>
                    <InfoTip text="Cue points indicate where server-side ads can play during broadcast. Playout snaps cleanly to 5-second boundaries." />
                  </div>
                  <p className="faint small" style={{ marginTop: 3 }}>
                    Easily insert ad breaks at scene cuts, or use 1-click smart presets below.
                  </p>
                </div>
                <span className="badge primary sm">
                  {cues.length} {cues.length === 1 ? 'break' : 'breaks'} scheduled
                </span>
              </div>

              {/* Action Buttons Row */}
              {editable && a.status === 'READY' && (
                canHaveBreaks ? (
                  <div className="library-ad-action-box">
                    {/* Hero Playhead Break Button */}
                    <div className="library-ad-action-primary">
                      <button
                        type="button"
                        className="btn primary library-ad-hero-btn"
                        disabled={busy}
                        onClick={() => handleAddBreak((videoRef.current?.currentTime ?? 0) * 1000)}
                        title="Insert break at current player time"
                      >
                        <Icon name="plus" size={15} />
                        <span>
                          Add Break at Playhead ({fmtDur(Math.max(5000, Math.min(maxValidCue, Math.round(((currentTimeSec * 1000) || 5000) / 5000) * 5000)))})
                        </span>
                      </button>
                    </div>

                    {/* Smart Presets Section */}
                    <div className="library-ad-presets-section">
                      <div className="library-ad-presets-head">
                        <span className="row" style={{ gap: 6 }}>
                          <Icon name="wand" size={13} />
                          <b>1-Click Smart Presets</b>
                        </span>
                        <span className="faint small">SSAI segment cuts</span>
                      </div>
                      <div className="library-ad-presets-grid">
                        {/* Mid-roll preset */}
                        {(() => {
                          const midMs = a.durationMs ? Math.round((a.durationMs / 2) / 5000) * 5000 : 60000
                          const exists = cues.includes(midMs)
                          return (
                            <button
                              type="button"
                              className={`library-preset-btn ${exists ? 'is-added' : ''}`}
                              disabled={busy || exists}
                              onClick={() => handleAddBreak(midMs)}
                              title={exists ? `Already added at ${fmtDur(midMs)}` : `Add mid-roll at ${fmtDur(midMs)}`}
                            >
                              <div className="preset-btn-top">
                                <span className="preset-btn-icon"><Icon name="sparkle" size={13} /></span>
                                <span className="preset-btn-title">Mid-roll (50%)</span>
                              </div>
                              <span className="preset-btn-time">{fmtDur(midMs)} {exists ? '✓' : ''}</span>
                            </button>
                          )
                        })()}

                        {/* Intro 00:03 preset */}
                        {(() => {
                          const openMs = 5000
                          const exists = cues.includes(openMs)
                          return (
                            <button
                              type="button"
                              className={`library-preset-btn ${exists ? 'is-added' : ''}`}
                              disabled={busy || exists}
                              onClick={() => handleAddBreak(openMs)}
                              title={exists ? `Already added at 00:03` : `Add opening break after intro at 00:03`}
                            >
                              <div className="preset-btn-top">
                                <span className="preset-btn-icon"><Icon name="film" size={13} /></span>
                                <span className="preset-btn-title">Opening Break</span>
                              </div>
                              <span className="preset-btn-time">00:03 {exists ? '✓' : ''}</span>
                            </button>
                          )
                        })()}

                        {/* Dynamic presets for media >= 60s */}
                        {a.durationMs >= 60000 && (() => {
                          const q1Ms = Math.round((a.durationMs * 0.25) / 5000) * 5000
                          const q3Ms = Math.round((a.durationMs * 0.75) / 5000) * 5000
                          const existsQ1 = cues.includes(q1Ms)
                          const existsQ3 = cues.includes(q3Ms)
                          return (
                            <>
                              <button
                                type="button"
                                className={`library-preset-btn ${existsQ1 ? 'is-added' : ''}`}
                                disabled={busy || existsQ1}
                                onClick={() => handleAddBreak(q1Ms)}
                              >
                                <div className="preset-btn-top">
                                  <span className="preset-btn-icon"><Icon name="clock" size={13} /></span>
                                  <span className="preset-btn-title">25% Mark</span>
                                </div>
                                <span className="preset-btn-time">{fmtDur(q1Ms)} {existsQ1 ? '✓' : ''}</span>
                              </button>
                              <button
                                type="button"
                                className={`library-preset-btn ${existsQ3 ? 'is-added' : ''}`}
                                disabled={busy || existsQ3}
                                onClick={() => handleAddBreak(q3Ms)}
                              >
                                <div className="preset-btn-top">
                                  <span className="preset-btn-icon"><Icon name="clock" size={13} /></span>
                                  <span className="preset-btn-title">75% Mark</span>
                                </div>
                                <span className="preset-btn-time">{fmtDur(q3Ms)} {existsQ3 ? '✓' : ''}</span>
                              </button>
                            </>
                          )
                        })()}
                      </div>
                    </div>

                    {/* Direct Custom Time Entry Section */}
                    <div className="library-ad-manual-section">
                      <div className="library-ad-manual-head">
                        <span className="row" style={{ gap: 6 }}>
                          <Icon name="clock" size={13} />
                          <b>Custom Timestamp Insertion</b>
                        </span>
                        <span className="faint small">Snaps cleanly to 5s segment</span>
                      </div>

                      <form onSubmit={handleManualAdd} className="library-ad-manual-form">
                        <div className="library-time-boxes-group">
                          <div className="library-time-box">
                            <span className="library-time-box-lbl">MIN</span>
                            <input
                              type="number"
                              min={0}
                              max={99}
                              placeholder="00"
                              value={manualMinutes}
                              onChange={e => setManualMinutes(e.target.value)}
                              className="library-time-input"
                              aria-label="Minutes"
                            />
                          </div>
                          <span className="library-time-colon">:</span>
                          <div className="library-time-box">
                            <span className="library-time-box-lbl">SEC</span>
                            <input
                              type="number"
                              min={0}
                              max={59}
                              placeholder="00"
                              value={manualSeconds}
                              onChange={e => setManualSeconds(e.target.value)}
                              className="library-time-input"
                              aria-label="Seconds"
                            />
                          </div>
                        </div>

                        {/* Real-time Snap Preview */}
                        {(() => {
                          const parsedMs = ((parseInt(manualMinutes || '0', 10) * 60) + parseInt(manualSeconds || '0', 10)) * 1000
                          if (parsedMs > 0) {
                            const snappedMs = Math.max(5000, Math.min(maxValidCue, Math.round(parsedMs / 5000) * 5000))
                            return (
                              <span className="library-time-snap-pill">
                                ⚡ Snaps to <b>{fmtDur(snappedMs)}</b> (Seg #{Math.round(snappedMs / 5000)})
                              </span>
                            )
                          }
                          return null
                        })()}

                        <button
                          type="submit"
                          className="btn primary sm"
                          disabled={busy || (!manualMinutes && !manualSeconds)}
                          style={{ marginLeft: 'auto' }}
                        >
                          <Icon name="plus" size={13} />
                          <span>Insert Break</span>
                        </button>
                      </form>
                    </div>
                  </div>
                ) : (
                  <div
                    className="alert warn"
                    style={{
                      fontSize: 13,
                      display: 'flex',
                      gap: 10,
                      alignItems: 'flex-start',
                      padding: '12px 14px',
                      borderRadius: 8,
                    }}
                  >
                    <Icon name="alert" size={18} />
                    <div>
                      <b>Media Duration Too Short for SSAI Ad Insertion ({fmtDur(a.durationMs)})</b>
                      <p style={{ margin: '4px 0 0', opacity: 0.88, fontSize: 12, lineHeight: 1.45 }}>
                        FAST Studio linear playout streams in standardized 5-second broadcast segments. SSAI mid-roll ad insertion requires media of at least 10 seconds (minimum 2 broadcast segments) so ad breaks can splice cleanly between segment boundaries.
                      </p>
                    </div>
                  </div>
                )
              )}

              {/* Break Points List */}
              {cues.length === 0 ? (
                <div className="callout" style={{ marginTop: 4 }}>
                  <Icon name="info" size={16} />
                  <div className="grow">
                    <b>No ad breaks inserted yet</b>
                    <div className="small faint">
                      This programme currently streams without commercial interruption. Click "Add Break at Playhead" above or choose a preset to enable SSAI monetization.
                    </div>
                  </div>
                  {editable && a.status === 'READY' && canHaveBreaks && (
                    <button
                      type="button"
                      className="btn sm primary"
                      onClick={() => handleAddBreak(a.durationMs ? Math.round((a.durationMs / 2) / 5000) * 5000 : 60000)}
                    >
                      + Add Mid-roll
                    </button>
                  )}
                </div>
              ) : (
                <div className="library-breaks-list">
                  {cues.map((c, idx) => (
                    <div key={c} className="library-break-card">
                      <div className="library-break-left">
                        <span className="library-break-badge">Break #{idx + 1}</span>
                        <b className="library-break-timestamp">{fmtDur(c)}</b>
                        <span className="faint small">SSAI Mid-roll cut point</span>
                      </div>

                      <div className="library-break-actions">
                        <button
                          type="button"
                          className="btn ghost sm"
                          onClick={() => seekTo(c / 1000)}
                          title="Seek video player to this timestamp"
                        >
                          <Icon name="play" size={12} />
                          <span>Seek to Scene</span>
                        </button>

                        {editable && (
                          <button
                            type="button"
                            className="btn ghost sm icon-btn"
                            style={{ color: 'var(--bad)' }}
                            aria-label={`Remove break at ${fmtDur(c)}`}
                            title="Remove this ad break"
                            onClick={() => save({ cuePoints: cues.filter(x => x !== c) }, 'Ad break removed')}
                          >
                            <Icon name="trash" size={13} />
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {/* TAB 3: Licence & Rights */}
        {activeTab === 'rights' && a.kind !== 'AD' && (
          <div className="stack" style={{ gap: 14 }}>
            <div className="card card-pad stack">
              <div className="between">
                <h3>Broadcast Clearance</h3>
                <Status value={view.rightsStatus} />
              </div>

              {view.rights ? (
                <div className="kv" style={{ marginTop: 6 }}>
                  <span>Allowed Countries</span>
                  <span>
                    {view.rights.allowedCountries ? (
                      <span className="row-wrap" style={{ gap: 6 }}>
                        {view.rights.allowedCountries.split(',').map(c => (
                          <span key={c.trim()} className="chip sm">
                            {flag(c.trim())} {c.trim()}
                          </span>
                        ))}
                      </span>
                    ) : (
                      <span className="badge bad sm">None listed (blocked)</span>
                    )}
                  </span>

                  <span>Blocked Countries</span>
                  <span>{view.rights.blockedCountries || 'None'}</span>

                  <span>Valid Through</span>
                  <span>
                    {view.rights.validUntil
                      ? new Date(view.rights.validUntil).toLocaleDateString(undefined, { dateStyle: 'medium' })
                      : 'Perpetual (No expiration)'}
                  </span>

                  <span>Ad Monetization</span>
                  <span>
                    {view.rights.adsAllowed ? (
                      <span className="badge ok sm">Allowed</span>
                    ) : (
                      <span className="badge warn sm">No ads permitted</span>
                    )}
                  </span>
                </div>
              ) : (
                <div className="callout warn" style={{ marginTop: 6 }}>
                  <Icon name="alert" size={16} />
                  <div className="grow">
                    <b>No Licence Record Found</b>
                    <div className="small faint">
                      This programme is currently blocked from live playout. Assign a valid territory licence in Rights Management.
                    </div>
                  </div>
                </div>
              )}

              <Link
                className="btn sm"
                to={`${org.base}/rights?asset=${a.id}`}
                style={{ alignSelf: 'flex-start', marginTop: 8 }}
              >
                <Icon name="shield" size={14} /> Open in Rights Portal →
              </Link>
            </div>
          </div>
        )}

        {/* TAB 4: Edit & Archival */}
        {activeTab === 'edit' && (
          <div className="stack" style={{ gap: 14 }}>
            <div className="card card-pad stack">
              <h3>Edit Video Information</h3>
              <label className="field"><span>Media type</span><select disabled={!editable || !mediaSettings.data} value={mediaType} onChange={e => setMediaType(e.target.value)}>
                {!mediaSettings.data?.types.some(t => t.key === a.mediaType && mediaSettings.data?.enabledTypes.includes(t.key)) && <option value={a.mediaType}>{a.mediaType} (existing)</option>}
                {mediaSettings.data?.types.filter(t => mediaSettings.data?.enabledTypes.includes(t.key) && (t.kind === 'AD') === (a.kind === 'AD')).map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
              </select></label>
              {editKind === 'EPISODE' && <div className="grid g3">
                <label className="field"><span>Series</span><input disabled={!editable} maxLength={120} value={series} onChange={e => setSeries(e.target.value)} /></label>
                <label className="field"><span>Season</span><input disabled={!editable} type="number" min={1} value={season} onChange={e => setSeason(Number(e.target.value))} /></label>
                <label className="field"><span>Episode</span><input disabled={!editable} type="number" min={1} value={episode} onChange={e => setEpisode(Number(e.target.value))} /></label>
              </div>}
              <label className="field">
                <span>Title</span>
                <input
                  type="text"
                  value={title}
                  disabled={!editable}
                  onChange={e => setTitle(e.target.value)}
                />
              </label>

              {a.kind !== 'AD' && (
                <GenreSelector
                  value={genre}
                  onChange={setGenre}
                  disabled={!editable}
                />
              )}

              <label className="field">
                <span>Description</span>
                <textarea
                  rows={3}
                  value={description}
                  disabled={!editable}
                  onChange={e => setDescription(e.target.value)}
                />
              </label>

              {editable && (
                <div className="row" style={{ justifyContent: 'flex-end', marginTop: 6 }}>
                  <button
                    type="button"
                    className="btn primary"
                    disabled={busy}
                    onClick={() => save({ title: title.trim(), genre: genre.trim(), description: description.trim(), ...(mediaType !== a.mediaType ? { mediaType } : {}), ...(editKind === 'EPISODE' ? { series: series.trim(), season, episode } : {}) }, 'Video details updated')}
                  >
                    Save Changes
                  </button>
                </div>
              )}
            </div>

            {editable && (
              <div className="card card-pad stack" style={{ border: '1px solid var(--border-strong)' }}>
                <h3>Archive Video</h3>
                <p className="faint small">
                  Archived videos are removed from your active library catalog and cannot be scheduled on any live channel.
                </p>
                <div>
                  <button
                    type="button"
                    className="btn danger"
                    onClick={() => setConfirmArchive(true)}
                  >
                    <Icon name="trash" size={15} /> Archive Video
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Confirmation for archive */}
      {confirmArchive && (
        <Confirm
          title="Archive this video?"
          danger
          confirmLabel="Archive"
          text="Archived videos disappear from your library catalog and cannot be broadcast. If this video is currently active in a published schedule, you will need to adjust the schedule."
          onClose={() => setConfirmArchive(false)}
          onConfirm={() =>
            run(() => api(`/api/orgs/${org.slug}/assets/${a.id}/archive`, { method: 'POST' }), 'Video archived').then(r => {
              if (r) {
                onChange()
                onClose()
              }
            })
          }
        />
      )}
    </Drawer>
  )
}
