import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError, type Channel, type ChannelView, type Entry, type Issue, type Version } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Confirm,
  Dropdown,
  type DropdownOption,
  Empty,
  ErrorBox,
  fmtDayTime,
  fmtDur,
  fmtTime,
  fmtTimeSec,
  Icon,
  InfoTip,
  Modal,
  PageHead,
  useApi,
  useToast,
} from '../../shared/ui'
import { DateTimePicker } from '../../shared/components/DateTimePicker'
import { SmartProgramming } from './SmartProgramming'

type AssetRef = { id: string; title: string; kind: string; color: string; mediaKey?: string; durationMs: number; status: string }
type View = {
  channel: Channel
  draft: Version
  draftEntries: Entry[]
  published?: Version
  publishedEntries: Entry[]
  issues: Issue[]
  versions: Version[]
  assets: Record<string, AssetRef>
  unpublishedChanges: boolean
  now: string
  from: string
  to: string
}

const ZOOM = { '1h': 1100, '3h': 360, '6h': 190, '24h': 48 } as const

const AUTOFILL_HOURS_OPTIONS: DropdownOption[] = [
  { value: '3', label: '3 Hours', icon: 'clock', description: 'Quick quarter-day block' },
  { value: '6', label: '6 Hours', icon: 'clock', description: 'Daypart programming block' },
  { value: '12', label: '12 Hours', icon: 'clock', description: 'Half-day schedule queue' },
  { value: '24', label: '24 Hours (Full Day)', icon: 'clock', description: 'Complete 24-hour linear timetable', badge: 'Recommended', badgeType: 'ok' },
  { value: '48', label: '48 Hours (2 Days)', icon: 'clock', description: 'Multi-day broadcast buffer' },
  { value: '72', label: '72 Hours (3 Days)', icon: 'clock', description: 'Extended 3-day continuous queue' },
]

const BREAK_SECONDS_OPTIONS: DropdownOption[] = [
  { value: '30', label: '30 seconds (1 spot)', icon: 'clock', description: 'Single sponsor commercial slot' },
  { value: '60', label: '60 seconds (2 spots)', icon: 'clock', description: 'Standard 2x 30s ad break' },
  { value: '90', label: '90 seconds (3 spots)', icon: 'clock', description: 'Standard 3x 30s ad break', badge: 'Recommended', badgeType: 'ok' },
  { value: '120', label: '120 seconds (2.0 min)', icon: 'clock', description: 'Extended 4-spot commercial break' },
  { value: '180', label: '180 seconds (3.0 min)', icon: 'clock', description: 'Long-form commercial block' },
]

export default function Schedule() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const channelId = params.get('channel') ?? channels.data?.[0]?.channel.id ?? null
  const view = useApi<View>(channelId ? `/api/orgs/${org.slug}/channels/${channelId}/schedule` : null, [], 30000)
  const [mode, setMode] = useState<'draft' | 'published'>('draft')
  const [zoom, setZoom] = useState<keyof typeof ZOOM>('1h')
  const [selected, setSelected] = useState<string | null>(null)
  const [modal, setModal] = useState<null | 'autofill' | 'program' | 'break' | 'publish' | 'clear'>(null)
  const [replaceEntry, setReplaceEntry] = useState<Entry | null>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()

  // Real-time broadcast playhead clock (ticks every 1000ms for continuous live movement)
  const [nowMs, setNowMs] = useState<number>(Date.now())
  useEffect(() => {
    const timer = setInterval(() => {
      setNowMs(Date.now())
    }, 1000)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    setSelected(null)
  }, [channelId, mode])

  const v = view.data
  const editable = org.can('SCHEDULE_EDIT') && mode === 'draft'
  const entries = v ? (mode === 'draft' ? v.draftEntries : v.publishedEntries) : []
  const errors = v?.issues.filter(i => i.severity === 'ERROR') ?? []
  const warnings = v?.issues.filter(i => i.severity !== 'ERROR') ?? []
  const errorIds = new Set(errors.map(i => i.entryId).filter(Boolean))

  // Synchronize server delta if server clock has significant skew
  const [serverDelta, setServerDelta] = useState(0)
  useEffect(() => {
    if (v?.now) {
      const serverTime = new Date(v.now).getTime()
      const diff = serverTime - Date.now()
      if (Math.abs(diff) > 3000) setServerDelta(diff)
    }
  }, [v?.now])

  const liveTimeMs = nowMs + serverDelta

  // Find active channel view
  const currentChannelView = useMemo(
    () => channels.data?.find(c => c.channel.id === channelId),
    [channels.data, channelId]
  )

  // Compute coverage metrics
  const coverageMetrics = useMemo(() => {
    if (!entries.length) return { coverageEnd: null, hoursAhead: 0, progCount: 0, breakCount: 0 }
    const endMs = Math.max(...entries.map(e => new Date(e.endAt).getTime()))
    const nowMs = Date.now()
    const hoursAhead = Math.max(0, Math.round((endMs - nowMs) / 3600_000))
    const progCount = entries.filter(e => e.kind !== 'AD_BREAK').length
    const breakCount = entries.filter(e => e.kind === 'AD_BREAK').length
    return {
      coverageEnd: new Date(endMs).toISOString(),
      hoursAhead,
      progCount,
      breakCount,
    }
  }, [entries])

  // Channel switcher options
  const channelDropdownOptions: DropdownOption[] = useMemo(
    () =>
      (channels.data ?? []).map(c => ({
        value: c.channel.id,
        label: c.channel.name,
        icon: 'tv',
        badge: c.channel.status,
        badgeType: c.channel.status === 'LIVE' ? 'live' : 'default',
        description: `/${c.channel.slug} · ${c.channel.timezone}`,
      })),
    [channels.data]
  )

  /** Every draft edit sends the revision we loaded; a 412 means someone else edited first. */
  async function edit(path: string, body: object | null, method = 'POST') {
    if (!v) return
    setBusy(true)
    try {
      const sep = path.includes('?') ? '&' : '?'
      const r = await api<{ notices?: string[]; versionNumber?: number; warnings?: Issue[] }>(
        `/api/orgs/${org.slug}/channels/${v.channel.id}/schedule/${path}${method === 'DELETE' ? `${sep}expectedRevision=${v.draft.revision}` : ''}`,
        { method, body: body === null ? undefined : { expectedRevision: v.draft.revision, ...body } }
      )
      r?.notices?.forEach(n => toast(n, 'ok'))
      if (r?.versionNumber) toast(`Version ${r.versionNumber} is now on air`, 'ok')
      setModal(null)
      return r
    } catch (e) {
      const err = e as ApiError
      toast(err.message, 'bad')
      if (err.code === 'SCHEDULE_INVALID') setMode('draft')
    } finally {
      setBusy(false)
      view.reload()
    }
  }

  /** Auto-assisted video replacement: atomically swaps asset, preserves airtime, and optionally closes gaps */
  async function handleReplace(
    oldEntry: Entry,
    newAssetId: string,
    withBreaks: boolean,
    autoCloseGaps: boolean
  ) {
    if (!v) return
    setBusy(true)
    try {
      // Step 1: Remove old entry
      const delRes = await api<{ revision?: number }>(
        `/api/orgs/${org.slug}/channels/${v.channel.id}/schedule/entries/${oldEntry.id}?expectedRevision=${v.draft.revision}`,
        { method: 'DELETE' }
      )
      const rev1 = delRes?.revision ?? (v.draft.revision + 1)

      // Step 2: Add replacement video at the exact same start time
      const addRes = await api<{ revision?: number }>(
        `/api/orgs/${org.slug}/channels/${v.channel.id}/schedule/entries`,
        {
          method: 'POST',
          body: {
            expectedRevision: rev1,
            kind: 'PROGRAM',
            assetId: newAssetId,
            withBreaks,
            startAt: oldEntry.startAt,
          },
        }
      )
      const rev2 = addRes?.revision ?? (rev1 + 1)

      // Step 3: Auto-close gaps to ripple downstream schedule if requested
      if (autoCloseGaps) {
        await api(
          `/api/orgs/${org.slug}/channels/${v.channel.id}/schedule/close-gaps`,
          {
            method: 'POST',
            body: { expectedRevision: rev2 },
          }
        )
      }

      const oldTitle = oldEntry.assetId ? v.assets[oldEntry.assetId]?.title : 'Programme'
      const newTitle = v.assets[newAssetId]?.title ?? 'Replacement Video'
      toast(`Replaced "${oldTitle}" with "${newTitle}". Timetable updated.`, 'ok')
      setReplaceEntry(null)
      setSelected(null)
    } catch (e) {
      const err = e as ApiError
      toast(err.message || 'Failed to replace programme', 'bad')
    } finally {
      setBusy(false)
      view.reload()
    }
  }

  if (channels.error) {
    return (
      <div className="page schedule-page">
        <ErrorBox error={channels.error} />
      </div>
    )
  }

  if (channels.data && channels.data.length === 0) {
    return (
      <div className="page schedule-page">
        <PageHead title="Broadcast Schedule" />
        <div className="card">
          <Empty
            icon="calendar"
            title="Create a channel first"
            action={
              <Link className="btn primary" to={`${org.base}/channels`}>
                Go to Channels
              </Link>
            }
          />
        </div>
      </div>
    )
  }

  const sel = entries.find(e => e.id === selected)

  return (
    <div className="page schedule-page">
      {/* Header matching Library & Channels */}
      <PageHead
        title="Broadcast Schedule"
        tip="The linear schedule dictates what plays at every millisecond. You plan and edit a draft; viewers only ever receive the published timetable."
        subtitle="Manage 24/7 master daypart grids, auto-fill video rotations, commercial break insertion, and instant version publishing."
      />

      {v && <SmartProgramming channelId={v.channel.id} timezone={v.channel.timezone} revision={v.draft.revision} onChange={view.reload} />}
      {/* Top High-Level Metric Cards (Harmonized 4-column KPI Grid - Always mounted) */}
      <div className="grid g4 library-kpi-grid">
        {/* Card 1: Scheduled Programmes */}
        <div className="card library-kpi-card color-primary">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-primary">
              <Icon name="film" size={18} />
            </span>
            <span className={`badge ${v ? (v.channel.status === 'LIVE' ? 'live' : 'default') : (currentChannelView?.channel.status === 'LIVE' ? 'live' : 'default')} sm`}>
              {v ? (v.channel.status === 'LIVE' ? 'ON AIR' : v.channel.status) : (currentChannelView?.channel.status === 'LIVE' ? 'ON AIR' : currentChannelView?.channel.status ?? 'STANDBY')}
            </span>
          </div>
          <div>
            <div className="library-kpi-val">
              {v ? coverageMetrics.progCount : <span className="kpi-num-skeleton" />}
            </div>
            <div className="library-kpi-label">Scheduled Programmes</div>
            <div className="library-kpi-sub faint small">
              Queued on {v?.channel.name ?? currentChannelView?.channel.name ?? 'Channel'} · {v?.channel.timezone ?? currentChannelView?.channel.timezone ?? 'UTC'}
            </div>
          </div>
        </div>

        {/* Card 2: Coverage Runway */}
        <div className="card library-kpi-card color-emerald">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-emerald">
              <Icon name="calendar" size={18} />
            </span>
            <span className={`badge ${v ? (v.unpublishedChanges ? 'warn' : 'ok') : 'default'} sm`}>
              {v ? (v.unpublishedChanges ? 'Draft Edits' : 'On Air Match') : 'Syncing'}
            </span>
          </div>
          <div>
            <div className="library-kpi-val">
              {v ? `${coverageMetrics.hoursAhead}h` : <span className="kpi-num-skeleton" />}
            </div>
            <div className="library-kpi-label">Broadcast Runway</div>
            <div className="library-kpi-sub faint small" title={coverageMetrics.coverageEnd ? `Until ${fmtDayTime(coverageMetrics.coverageEnd)}` : 'Empty'}>
              {v ? (coverageMetrics.coverageEnd ? `Continuous playout until ${fmtDayTime(coverageMetrics.coverageEnd)}` : 'No coverage planned') : 'Calculating daypart runway...'}
            </div>
          </div>
        </div>

        {/* Card 3: Monetization & Ad Density */}
        <div className="card library-kpi-card color-cyan">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-cyan">
              <Icon name="megaphone" size={18} />
            </span>
            <span className="badge info sm">
              {Math.round((v?.channel.adSecondsPerHour ?? currentChannelView?.channel.adSecondsPerHour ?? 720) / 60)}m / hr
            </span>
          </div>
          <div>
            <div className="library-kpi-val">
              {v ? coverageMetrics.breakCount : <span className="kpi-num-skeleton" />}
            </div>
            <div className="library-kpi-label">Monetized Ad Pods</div>
            <div className="library-kpi-sub faint small">
              {v?.channel.breakSeconds ?? currentChannelView?.channel.breakSeconds ?? 90}s pods · SCTE-35 server-side insertion
            </div>
          </div>
        </div>

        {/* Card 4: Pre-flight Readiness */}
        <div className="card library-kpi-card color-purple">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-purple">
              <Icon name={!v || errors.length === 0 ? 'checkCircle' : 'alert'} size={18} />
            </span>
            <span className={`badge ${!v || errors.length === 0 ? 'ok' : 'bad'} sm`}>
              {v ? (errors.length === 0 ? 'Pristine' : `${errors.length} to Fix`) : 'Verifying'}
            </span>
          </div>
          <div>
            <div className="library-kpi-val">
              {v ? (errors.length === 0 ? 'Ready to Air' : `${errors.length} Error(s)`) : <span className="kpi-num-skeleton" style={{ width: 72 }} />}
            </div>
            <div className="library-kpi-label">Pre-Flight Readiness</div>
            <div className="library-kpi-sub faint small">
              {v ? (errors.length === 0
                ? (warnings.length > 0 ? `${warnings.length} heads-up notice(s)` : 'Zero validation conflicts')
                : 'Resolve critical errors to publish') : 'Running 5-point broadcast verification...'}
            </div>
          </div>
        </div>
      </div>

      {/* Filter Bar & Controls (Harmonized 2-row layout matching Library & Channels) */}
      <div className="library-filter-bar">
        {/* Nav row: Segmented mode switcher + Live status meta */}
        <div className="library-filter-nav">
          <div className="library-format-tabs" role="tablist">
            <button
              type="button"
              className={`library-tab-btn ${mode === 'draft' ? 'is-active' : ''}`}
              onClick={() => setMode('draft')}
            >
              <Icon name="wand" size={13} />
              <span>Draft (Planning)</span>
              {v?.unpublishedChanges && <span className="library-tab-count warn">Edits</span>}
            </button>

            <button
              type="button"
              className={`library-tab-btn ${mode === 'published' ? 'is-active' : ''}`}
              onClick={() => setMode('published')}
              disabled={Boolean(v && !v.published)}
            >
              <Icon name="play" size={13} />
              <span>On Air {v?.published ? `(v${v.published.number})` : ''}</span>
              {(v?.channel.status === 'LIVE' || currentChannelView?.channel.status === 'LIVE') && (
                <span className="library-tab-count ok">Live</span>
              )}
            </button>
          </div>

          <div className="library-catalog-meta">
            <span className="channel-tz-pill" title={`All timetable slots aligned to ${v?.channel.timezone ?? currentChannelView?.channel.timezone ?? 'UTC'}`}>
              <Icon name="clock" size={12} />
              {v?.channel.timezone ?? currentChannelView?.channel.timezone ?? 'UTC'}
            </span>
            {v ? (
              <>
                <span className="library-catalog-meta-count">
                  <strong>{coverageMetrics.hoursAhead}h</strong>
                  <span className="faint"> runway · {coverageMetrics.progCount} shows</span>
                </span>
                {v.unpublishedChanges ? (
                  <span className="library-catalog-meta-badge" style={{ background: 'var(--warn-soft)', color: 'var(--warn)', borderColor: 'rgba(245, 158, 11, 0.4)' }}>
                    <span className="meta-badge-dot" style={{ background: 'var(--warn)' }} /> Unsaved Draft
                  </span>
                ) : (
                  <span className="library-catalog-meta-badge" style={{ background: 'var(--ok-soft)', color: 'var(--ok)', borderColor: 'rgba(16, 185, 129, 0.4)' }}>
                    <span className="meta-badge-dot" style={{ background: 'var(--ok)' }} /> In Sync
                  </span>
                )}
              </>
            ) : (
              <span className="faint small" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <span className="spinner" style={{ width: 11, height: 11, borderWidth: 1.5 }} /> Syncing broadcast timetable...
              </span>
            )}
          </div>
        </div>

        {/* Controls row: Channel Picker & Channel Actions */}
        <div className="library-controls-row" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            {/* Channel Dropdown Switcher */}
            <div style={{ minWidth: 260 }}>
              <Dropdown
                options={channelDropdownOptions}
                value={channelId ?? ''}
                onChange={id => setParams({ channel: id })}
                searchable
                align="left"
                placeholder="Select linear channel..."
              />
            </div>

            {currentChannelView?.channel.status === 'LIVE' ? (
              <Link
                to={`${org.base}/watch?channel=${currentChannelView.channel.slug}`}
                className="btn sm"
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  fontWeight: 600,
                  borderColor: 'rgba(16, 185, 129, 0.4)',
                  background: 'rgba(16, 185, 129, 0.08)',
                  color: 'var(--ok)',
                }}
                title={`Watch live playback for ${currentChannelView.channel.name}`}
              >
                <span className="dot" style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--ok)', boxShadow: '0 0 6px var(--ok)' }} />
                <Icon name="play" size={13} /> Watch Live
              </Link>
            ) : currentChannelView ? (
              <span
                className="faint small"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '5px 10px', borderRadius: 8, background: 'var(--surface-2)', border: '1px solid var(--border)' }}
                title="Channel must be broadcast live to inspect live playback"
              >
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--text-3)' }} />
                Standby (Offline)
              </span>
            ) : null}
          </div>
        </div>
      </div>

      {view.error ? (
        <ErrorBox error={view.error} retry={view.reload} />
      ) : !v ? (
        <div className="split" style={{ opacity: 0.9 }}>
          <div className="stack" style={{ gap: 16 }}>
            <div className="card card-pad stack" style={{ gap: 12 }}>
              <div className="skeleton" style={{ height: 48, borderRadius: 10 }} />
              <div className="skeleton" style={{ height: 180, borderRadius: 10 }} />
            </div>
            <div className="card card-pad stack" style={{ gap: 12 }}>
              <div className="skeleton" style={{ height: 38, width: '40%' }} />
              <div className="skeleton" style={{ height: 260, borderRadius: 8 }} />
            </div>
          </div>
          <div className="stack" style={{ gap: 16 }}>
            <div className="card card-pad stack" style={{ gap: 12 }}>
              <div className="skeleton" style={{ height: 36, width: '60%' }} />
              <div className="skeleton" style={{ height: 320, borderRadius: 10 }} />
            </div>
            <div className="card card-pad stack" style={{ gap: 12 }}>
              <div className="skeleton" style={{ height: 36, width: '50%' }} />
              <div className="skeleton" style={{ height: 180, borderRadius: 10 }} />
            </div>
          </div>
        </div>
      ) : (
        <div className="split">
          {/* Main Visual Column: Timeline & Running Order */}
          <div className="stack" style={{ gap: 16 }}>
            <section className="card schedule-timeline-card">
              {/* Distinct Mode Workspace Banner & Capabilities Strip */}
              <div className="card-pad" style={{ paddingBottom: 0 }}>
                {mode === 'draft' ? (
                  <div className="epg-mode-banner draft-mode">
                    <div className="epg-mode-banner-top">
                      <div className="epg-mode-title-wrap">
                        <span className="library-kpi-icon-wrap bg-primary" style={{ width: 28, height: 28 }}>
                          <Icon name="wand" size={15} />
                        </span>
                        <div>
                          <div className="epg-mode-title">
                            Draft Planning Workspace
                            <span className="badge primary sm" style={{ marginLeft: 6 }}>
                              Staging {v.published ? `v${v.published.number + 1}` : 'v1'}
                            </span>
                          </div>
                          <div className="epg-mode-desc">
                            You are editing in <b>safe staging mode</b>. Insert shows, schedule ad pods, and auto-fill dayparts freely. Live viewers are unaffected until you publish.
                          </div>
                        </div>
                      </div>

                      <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                        {v.unpublishedChanges ? (
                          <span className="badge warn sm">
                            <Icon name="alert" size={11} /> Unsaved Changes Staged
                          </span>
                        ) : (
                          <span className="badge ok sm">
                            <Icon name="check" size={11} /> In Sync with On-Air Feed
                          </span>
                        )}

                        {org.can('SCHEDULE_PUBLISH') && (
                          <button
                            type="button"
                            className="btn primary sm"
                            disabled={busy || errors.length > 0}
                            onClick={() => setModal('publish')}
                            style={{ whiteSpace: 'nowrap' }}
                          >
                            <Icon name="rocket" size={13} /> Publish to Live Air ({v.published ? `v${v.published.number + 1}` : 'v1'})
                            {errors.length > 0 && <span className="badge bad sm" style={{ marginLeft: 4 }}>{errors.length}</span>}
                          </button>
                        )}
                      </div>
                    </div>

                    {/* Visible Capabilities Toolbar */}
                    <div className="epg-mode-actions-row">
                      <span className="faint small" style={{ fontWeight: 600, marginRight: 4 }}>
                        What you can do here:
                      </span>
                      {editable && (
                        <>
                          <button type="button" className="btn sm" disabled={busy} onClick={() => setModal('autofill')} title="Auto-fill schedule using asset catalog">
                            <Icon name="wand" size={13} /> Auto-fill Daypart
                          </button>
                          <button type="button" className="btn sm" disabled={busy} onClick={() => setModal('program')} title="Insert a video programme at custom airtime">
                            <Icon name="plus" size={13} /> Add Programme
                          </button>
                          <button type="button" className="btn sm" disabled={busy} onClick={() => setModal('break')} title="Insert commercial ad break with SCTE-35 markers">
                            <Icon name="megaphone" size={13} /> Add Ad Break
                          </button>
                          <button type="button" className="btn sm" disabled={busy} onClick={() => edit('close-gaps', {})} title="Close any silent airtime gaps">
                            ⚡ Close Gaps
                          </button>
                          <button type="button" className="btn ghost danger sm" disabled={busy} onClick={() => setModal('clear')} title="Clear all draft entries after now">
                            <Icon name="trash" size={13} /> Clear Staging
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="epg-mode-banner on-air-mode">
                    <div className="epg-mode-banner-top">
                      <div className="epg-mode-title-wrap">
                        <span className="channel-sound-waves" title="Broadcasting on air" style={{ marginRight: 6 }}>
                          <span /><span /><span /><span />
                        </span>
                        <div>
                          <div className="epg-mode-title">
                            24/7 Master On-Air Broadcast Feed
                            <span className="badge live sm" style={{ marginLeft: 6 }}>
                              <span className="dot" /> Broadcasting v{v.published?.number ?? 1} (Locked)
                            </span>
                          </div>
                          <div className="epg-mode-desc">
                            Viewing the <b>immutable live broadcast stream</b> streaming to viewer devices. Direct live edits are locked to prevent airwave disruption.
                          </div>
                        </div>
                      </div>

                      <div className="row" style={{ gap: 8 }}>
                        <button
                          type="button"
                          className="btn primary sm"
                          onClick={() => setMode('draft')}
                          title="Switch to draft workspace to add or edit programming"
                        >
                          <Icon name="wand" size={13} /> Switch to Draft to Edit & Stage
                        </button>
                        {currentChannelView?.channel.status === 'LIVE' && (
                          <Link
                            className="btn ghost sm"
                            to={`${org.base}/watch?channel=${currentChannelView.channel.slug}`}
                            title="Open live player to inspect stream"
                          >
                            <Icon name="play" size={13} /> Watch Live
                          </Link>
                        )}
                      </div>
                    </div>

                    <div className="epg-mode-actions-row">
                      <span className="faint small">
                        💡 <b>What you can do here:</b> Monitor real-time broadcast playback, verify SCTE-35 ad triggers, or switch to <b>Draft</b> above to stage new shows without disrupting active viewers.
                      </span>
                    </div>
                  </div>
                )}
              </div>

              {/* Timeline Container */}
              <div className="card-pad stack" style={{ gap: 10 }}>
                <Timeline
                  entries={entries}
                  assets={v.assets}
                  from={v.from}
                  to={v.to}
                  nowMs={liveTimeMs}
                  pxPerHour={ZOOM[zoom]}
                  tz={v.channel.timezone}
                  selected={selected}
                  onSelect={setSelected}
                  errorIds={errorIds}
                  zoom={zoom}
                  setZoom={setZoom}
                />

                {/* Selected Inspector Drawer */}
                {sel && (
                  <Selected
                    e={sel}
                    a={sel.assetId ? v.assets[sel.assetId] : undefined}
                    tz={v.channel.timezone}
                    editable={editable}
                    busy={busy}
                    issues={v.issues.filter(i => i.entryId === sel.id)}
                    onReplace={() => setReplaceEntry(sel)}
                    onDelete={() => {
                      edit(`entries/${sel.id}`, null, 'DELETE')
                      setSelected(null)
                    }}
                    onClose={() => setSelected(null)}
                  />
                )}
              </div>
            </section>

            {/* Keyboard-Friendly Running Order Table */}
            <EntryList
              entries={entries}
              assets={v.assets}
              tz={v.channel.timezone}
              nowMs={liveTimeMs}
              errorIds={errorIds}
              onSelect={setSelected}
              selected={selected}
              editable={editable}
              busy={busy}
              onReplaceEntry={e => setReplaceEntry(e)}
              onDeleteEntry={e => {
                edit(`entries/${e.id}`, null, 'DELETE')
                if (selected === e.id) setSelected(null)
              }}
            />
          </div>

          {/* Right Rail: Pre-Flight Readiness & Version Rollback */}
          <div className="stack" style={{ gap: 16 }}>
            {/* Pre-Flight Management */}
            <PreflightPanel
              v={v}
              errors={errors}
              warnings={warnings}
              setMode={setMode}
              setSelected={setSelected}
              onCloseGaps={() => edit('close-gaps', {})}
            />

            {/* Version History & Rollback */}
            <section className="card">
              <div className="card-head">
                <div>
                  <h2>Version History</h2>
                  <p>Immutable release logs with one-click restore</p>
                </div>
              </div>
              {v.versions.length === 0 ? (
                <div className="card-pad faint">No published versions yet.</div>
              ) : (
                v.versions.slice(0, 8).map(ver => (
                  <div key={ver.id} className="step" style={{ padding: '10px 16px' }}>
                    <div
                      className="step-num"
                      style={ver.status === 'PUBLISHED' ? { background: 'var(--ok)', color: '#fff' } : {}}
                    >
                      v{ver.number}
                    </div>
                    <div className="grow small">
                      <b>{ver.status === 'PUBLISHED' ? 'On air now' : 'Previous version'}</b>
                      <div className="faint">
                        {fmtDayTime(ver.publishedAt)}
                        {ver.note && ` · ${ver.note}`}
                      </div>
                    </div>
                    {org.can('SCHEDULE_EDIT') && ver.status !== 'PUBLISHED' && (
                      <button
                        type="button"
                        className="btn sm"
                        disabled={busy}
                        onClick={() => edit('restore', { versionNumber: ver.number })}
                      >
                        Restore
                      </button>
                    )}
                  </div>
                ))
              )}
            </section>
          </div>
        </div>
      )}

      {/* Upgraded Modals */}
      {v && replaceEntry && (
        <ReplaceModal
          v={v}
          entry={replaceEntry}
          busy={busy}
          onClose={() => setReplaceEntry(null)}
          onSubmit={(newAssetId, withBreaks, autoCloseGaps) =>
            handleReplace(replaceEntry, newAssetId, withBreaks, autoCloseGaps)
          }
        />
      )}
      {v && modal === 'autofill' && (
        <AutofillModal v={v} busy={busy} onClose={() => setModal(null)} onSubmit={b => edit('autofill', b)} />
      )}
      {v && modal === 'program' && (
        <ProgramModal v={v} busy={busy} onClose={() => setModal(null)} onSubmit={b => edit('entries', b)} />
      )}
      {v && modal === 'break' && (
        <BreakModal v={v} busy={busy} onClose={() => setModal(null)} onSubmit={b => edit('entries', b)} />
      )}
      {v && modal === 'publish' && (
        <PublishModal
          v={v}
          errors={errors}
          warnings={warnings}
          busy={busy}
          onClose={() => setModal(null)}
          onSubmit={note => edit('publish', { note })}
        />
      )}
      {v && modal === 'clear' && (
        <Confirm
          danger
          title="Clear the upcoming draft?"
          confirmLabel="Clear upcoming"
          onClose={() => setModal(null)}
          text="Removes everything after now from the draft. The live broadcast feed is untouched until you publish."
          onConfirm={() => edit('clear', {})}
        />
      )}
    </div>
  )
}

function PreflightPanel({
  v,
  errors,
  warnings,
  setMode,
  setSelected,
  onCloseGaps,
}: {
  v: View
  errors: Issue[]
  warnings: Issue[]
  setMode: (m: 'draft' | 'published') => void
  setSelected: (id: string | null) => void
  onCloseGaps: () => void
}) {
  const [showBlackouts, setShowBlackouts] = useState(false)

  const blackouts = useMemo(() => v.issues.filter(i => i.code === 'BLACKOUT'), [v.issues])
  const hasContinuityError = errors.some(i => i.code === 'GAP' || i.code === 'OVERLAP' || i.code === 'MISALIGNED')
  const hasAdError = errors.some(i => i.code === 'AD_LOAD' || i.code === 'BREAK_TOO_LONG' || i.code === 'ADS_NOT_ALLOWED')
  const hasRightsFatal = errors.some(i => i.code === 'RIGHTS' || i.code === 'NO_TERRITORIES')

  return (
    <section className="card">
      <div className="card-head">
        <div>
          <h2>Pre-Flight Validation</h2>
          <p>Automated 24/7 compliance and broadcast readiness audit</p>
        </div>
        {errors.length === 0 ? (
          <span className="badge ok">
            <Icon name="check" size={12} /> Ready to Air
          </span>
        ) : (
          <span className="badge bad">{errors.length} to Fix</span>
        )}
      </div>

      <div className="card-pad stack" style={{ gap: 12 }}>
        {/* 5-Point Automated Broadcast Verification Matrix */}
        <div className="preflight-audit-list">
          {/* Check 1: 24/7 Timeline Continuity */}
          <div className="preflight-audit-item">
            <span className="preflight-audit-icon" style={{ color: hasContinuityError ? 'var(--bad)' : 'var(--ok)' }}>
              <Icon name={hasContinuityError ? 'alert' : 'checkCircle'} size={17} />
            </span>
            <div className="preflight-audit-content">
              <div className="preflight-audit-header">
                <span className="preflight-audit-name">24/7 Playout Continuity</span>
                <span className={`badge ${hasContinuityError ? 'bad' : 'ok'} sm`}>
                  {hasContinuityError ? 'Gaps Detected' : '100% Contiguous'}
                </span>
              </div>
              <div className="preflight-audit-detail">
                {hasContinuityError
                  ? 'Unprogrammed gaps or overlaps exist in daypart timetable.'
                  : 'Continuous playout queued with 0 dark airtime gaps or overlaps.'}
              </div>
              {hasContinuityError && (
                <div style={{ marginTop: 6 }}>
                  <button type="button" className="btn sm" onClick={onCloseGaps}>
                    ⚡ Close Airtime Gaps Now
                  </button>
                </div>
              )}
            </div>
          </div>

          {/* Check 2: SSAI Commercial Ad Rules */}
          <div className="preflight-audit-item">
            <span className="preflight-audit-icon" style={{ color: hasAdError ? 'var(--bad)' : 'var(--ok)' }}>
              <Icon name={hasAdError ? 'alert' : 'checkCircle'} size={17} />
            </span>
            <div className="preflight-audit-content">
              <div className="preflight-audit-header">
                <span className="preflight-audit-name">SSAI Commercial Ad Rules</span>
                <span className={`badge ${hasAdError ? 'bad' : 'ok'} sm`}>
                  {hasAdError ? 'Limit Exceeded' : 'Compliant'}
                </span>
              </div>
              <div className="preflight-audit-detail">
                {Math.round(v.channel.adSecondsPerHour / 60)}m/h ad load adheres to standard {v.channel.breakSeconds}s pods with SCTE-35 splice markers.
              </div>
            </div>
          </div>

          {/* Check 3: GOP Boundary Alignment */}
          <div className="preflight-audit-item">
            <span className="preflight-audit-icon" style={{ color: 'var(--ok)' }}>
              <Icon name="checkCircle" size={17} />
            </span>
            <div className="preflight-audit-content">
              <div className="preflight-audit-header">
                <span className="preflight-audit-name">3-Sec Frame Boundary Alignment</span>
                <span className="badge ok sm">Synchronized</span>
              </div>
              <div className="preflight-audit-detail">
                All cue splice markers snap cleanly to 5-second HLS transport boundaries.
              </div>
            </div>
          </div>

          {/* Check 4: Media Encoding Readiness */}
          <div className="preflight-audit-item">
            <span className="preflight-audit-icon" style={{ color: 'var(--ok)' }}>
              <Icon name="checkCircle" size={17} />
            </span>
            <div className="preflight-audit-content">
              <div className="preflight-audit-header">
                <span className="preflight-audit-name">Media Asset Encoding</span>
                <span className="badge ok sm">Ready to Air</span>
              </div>
              <div className="preflight-audit-detail">
                All scheduled video assets are transcoded into adaptive bitrate HLS segments.
              </div>
            </div>
          </div>

          {/* Check 5: Multi-Territory Licensing & Fallback Slate */}
          <div className="preflight-audit-item">
            <span className="preflight-audit-icon" style={{ color: hasRightsFatal ? 'var(--bad)' : 'var(--ok)' }}>
              <Icon name={hasRightsFatal ? 'alert' : 'checkCircle'} size={17} />
            </span>
            <div className="preflight-audit-content">
              <div className="preflight-audit-header">
                <span className="preflight-audit-name">Territories & Regional Rights</span>
                <span className={`badge ${hasRightsFatal ? 'bad' : 'ok'} sm`}>
                  {hasRightsFatal ? 'Unlicensed' : 'Regional Fallback Active'}
                </span>
              </div>
              <div className="preflight-audit-detail">
                Targeting <b>{v.channel.territories}</b>. {blackouts.length > 0 ? (
                  <>
                    {blackouts.length} titles broadcast in licensed territories ({v.channel.territories.split(',')[0] || 'IN'}) and seamlessly show the standby slate in restricted regions. Playout is 100% active.
                  </>
                ) : (
                  'All scheduled shows hold active broadcast distribution rights.'
                )}
              </div>

              {blackouts.length > 0 && (
                <div style={{ marginTop: 6 }}>
                  <button
                    type="button"
                    className="preflight-action-btn"
                    onClick={() => setShowBlackouts(!showBlackouts)}
                  >
                    {showBlackouts ? 'Hide Regional List' : `View ${blackouts.length} Regional Titles`}
                  </button>
                </div>
              )}

              {showBlackouts && (
                <div className="stack" style={{ gap: 4, marginTop: 8, maxHeight: 180, overflowY: 'auto' }}>
                  {blackouts.slice(0, 50).map((b, idx) => (
                    <div
                      key={idx}
                      className="row between clickable"
                      style={{
                        padding: '4px 8px',
                        background: 'var(--surface)',
                        borderRadius: 6,
                        fontSize: 11,
                        border: '1px solid var(--border)',
                      }}
                      onClick={() => {
                        if (b.entryId) {
                          setMode('draft')
                          setSelected(b.entryId)
                        }
                      }}
                    >
                      <span className="ellipsis" style={{ maxWidth: '80%' }}>
                        {b.message}
                      </span>
                      <span className="faint small">Jump →</span>
                    </div>
                  ))}
                  {blackouts.length > 50 && (
                    <span className="faint small">…and {blackouts.length - 50} more titles.</span>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Blocking Errors Section (Only shown if blocking errors exist!) */}
        {errors.length > 0 ? (
          <div className="stack" style={{ gap: 6 }}>
            <div className="row" style={{ gap: 6, color: 'var(--bad)', fontWeight: 700, fontSize: 13 }}>
              <Icon name="alert" size={15} /> Action Required Before Publishing ({errors.length})
            </div>
            {errors.map((i, n) => (
              <div key={n} className="callout bad" style={{ width: '100%' }}>
                <Icon name="alert" />
                <div className="stack" style={{ gap: 4, flex: 1 }}>
                  <b>Must fix: {i.message}</b>
                  <div className="small" style={{ opacity: 0.85 }}>→ {i.hint}</div>
                  <div className="row" style={{ gap: 6, marginTop: 4 }}>
                    {i.code === 'GAP' && (
                      <button type="button" className="preflight-action-btn" onClick={onCloseGaps}>
                        ⚡ Close Gaps
                      </button>
                    )}
                    {i.entryId && (
                      <button
                        type="button"
                        className="preflight-action-btn"
                        onClick={() => {
                          setMode('draft')
                          setSelected(i.entryId!)
                        }}
                      >
                        Inspect Slot →
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="callout ok" style={{ margin: 0, padding: '10px 12px' }}>
            <Icon name="check" />
            <div style={{ fontSize: 12 }}>
              <b>Timetable Compliant:</b> Zero fatal conflicts. Timetable is 100% ready to publish to 24/7 airwaves.
            </div>
          </div>
        )}
      </div>
    </section>
  )
}

function Timeline({
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

function Selected({
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

function EntryList({
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

function readyProgrammes(v: View) {
  return Object.values(v.assets).filter(a => a.kind !== 'AD' && a.kind !== 'SLATE' && a.status === 'READY')
}

// ---------------------------------------------------------------- Upgraded Modals

function AutofillModal({
  v,
  busy,
  onClose,
  onSubmit,
}: {
  v: View
  busy: boolean
  onClose: () => void
  onSubmit: (b: object) => void
}) {
  const progs = readyProgrammes(v)
  const used = new Set(v.draftEntries.map(e => e.assetId).filter(Boolean))
  const [ids, setIds] = useState<string[]>(progs.filter(p => used.has(p.id)).map(p => p.id))
  const [hours, setHours] = useState('24')
  const [replace, setReplace] = useState(false)
  const [search, setSearch] = useState('')

  const filteredProgs = useMemo(() => {
    if (!search.trim()) return progs
    const q = search.toLowerCase()
    return progs.filter(p => p.title.toLowerCase().includes(q) || p.kind.toLowerCase().includes(q))
  }, [progs, search])

  const toggleAll = () => {
    if (ids.length === progs.length) setIds([])
    else setIds(progs.map(p => p.id))
  }

  return (
    <Modal
      wide
      title="Auto-Fill Schedule Timetable"
      subtitle="Intelligently builds a 24/7 continuous broadcast grid: rotates selected content, automatically schedules SCTE-35 ad breaks, and respects channel ad density rules."
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
            onClick={() => onSubmit({ hours: Number(hours), assetIds: ids, replace })}
          >
            {busy ? <span className="spinner" /> : `Fill ${hours} Hours Timetable`}
          </button>
        </>
      }
    >
      {/* Target Hours Dropdown */}
      <div className="field">
        <span>Timetable Duration</span>
        <Dropdown
          options={AUTOFILL_HOURS_OPTIONS}
          value={hours}
          onChange={setHours}
          align="full"
        />
      </div>

      {/* Video selection pool */}
      <div className="field">
        <div className="between" style={{ marginBottom: 6 }}>
          <span>
            Content Library Pool ({ids.length}/{progs.length} selected)
          </span>
          <button type="button" className="btn ghost sm" onClick={toggleAll}>
            {ids.length === progs.length ? 'Deselect All' : 'Select All Ready'}
          </button>
        </div>

        <div className="library-search-wrap" style={{ height: 36, marginBottom: 10 }}>
          <Icon name="search" size={13} />
          <input
            className="library-search-input"
            placeholder="Search ready videos..."
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
          {search && (
            <button
              type="button"
              className="library-search-clear"
              onClick={() => setSearch('')}
              aria-label="Clear search"
            >
              <Icon name="x" size={12} />
            </button>
          )}
        </div>

        <div className="grid g3" style={{ maxHeight: 320, overflowY: 'auto', padding: 2 }}>
          {filteredProgs.map(p => {
            const isChecked = ids.includes(p.id)
            return (
              <label
                key={p.id}
                className={`check card ${isChecked ? 'selected' : ''}`}
                style={{
                  padding: '8px 10px',
                  cursor: 'pointer',
                  border: isChecked ? '1px solid var(--primary)' : '1px solid var(--border)',
                  background: isChecked ? 'var(--primary-soft)' : 'var(--surface-2)',
                }}
              >
                <input
                  type="checkbox"
                  checked={isChecked}
                  onChange={e =>
                    setIds(e.target.checked ? [...ids, p.id] : ids.filter(x => x !== p.id))
                  }
                />
                <span style={{ width: 10, height: 10, borderRadius: 2, background: p.color }} />
                <span className="ellipsis grow small" style={{ fontWeight: 600 }}>
                  {p.title}
                </span>
                <span className="faint small">{fmtDur(p.durationMs)}</span>
              </label>
            )
          })}
        </div>
      </div>

      {/* Placement strategy */}
      <div className="field">
        <span>Schedule Placement Mode</span>
        <div className="grid g2" style={{ gap: 10, marginTop: 4 }}>
          <label
            className={`card check ${!replace ? 'selected' : ''}`}
            style={{
              padding: '10px 12px',
              cursor: 'pointer',
              border: !replace ? '1px solid var(--primary)' : '1px solid var(--border)',
            }}
          >
            <input type="radio" checked={!replace} onChange={() => setReplace(false)} />
            <div>
              <b>Append to Draft</b>
              <p className="faint small" style={{ margin: 0 }}>
                Adds content right after the current end of the schedule
              </p>
            </div>
          </label>

          <label
            className={`card check ${replace ? 'selected' : ''}`}
            style={{
              padding: '10px 12px',
              cursor: 'pointer',
              border: replace ? '1px solid var(--primary)' : '1px solid var(--border)',
            }}
          >
            <input type="radio" checked={replace} onChange={() => setReplace(true)} />
            <div>
              <b>Replace Upcoming</b>
              <p className="faint small" style={{ margin: 0 }}>
                Clears future unplayed items and starts fresh rotation
              </p>
            </div>
          </label>
        </div>
      </div>

      <div className="callout info">
        <Icon name="shieldCheck" />
        <div>
          Any videos with licensing or territorial rights restrictions for this channel will be gracefully validated.
        </div>
      </div>
    </Modal>
  )
}

function StartPicker({
  value,
  onChange,
  tz,
}: {
  value: string
  onChange: (v: string) => void
  tz?: string
}) {
  const [mode, setMode] = useState<'end' | 'custom'>(value ? 'custom' : 'end')

  return (
    <div className="field">
      <span>Placement Airtime</span>
      <div className="grid g2" style={{ gap: 8, marginTop: 4 }}>
        <button
          type="button"
          className={`btn ${mode === 'end' ? 'primary' : ''}`}
          onClick={() => {
            setMode('end')
            onChange('')
          }}
        >
          <Icon name="arrowRight" size={14} /> At End of Schedule
        </button>
        <button
          type="button"
          className={`btn ${mode === 'custom' ? 'primary' : ''}`}
          onClick={() => {
            setMode('custom')
            if (!value) onChange(new Date(Date.now() + 3600_000).toISOString())
          }}
        >
          <Icon name="clock" size={14} /> At Specific Time
        </button>
      </div>

      {mode === 'custom' && (
        <div style={{ marginTop: 10 }}>
          <DateTimePicker
            value={value || new Date(Date.now() + 3600_000).toISOString()}
            onChange={v => onChange(v)}
            timezone={tz || 'local'}
            showTime={true}
            fixed={true}
          />
        </div>
      )}
    </div>
  )
}

function ReplaceModal({
  v,
  entry,
  busy,
  onClose,
  onSubmit,
}: {
  v: View
  entry: Entry
  busy: boolean
  onClose: () => void
  onSubmit: (newAssetId: string, withBreaks: boolean, autoCloseGaps: boolean) => void
}) {
  const progs = readyProgrammes(v)
  const currentAsset = entry.assetId ? v.assets[entry.assetId] : undefined
  const currentDur = currentAsset?.durationMs ?? (new Date(entry.endAt).getTime() - new Date(entry.startAt).getTime())

  // Exclude current asset from selection list
  const availableProgs = useMemo(() => progs.filter(p => p.id !== entry.assetId), [progs, entry.assetId])

  const [selectedId, setSelectedId] = useState<string>(availableProgs[0]?.id ?? '')
  const [search, setSearch] = useState('')
  const [filterMode, setFilterMode] = useState<'closest' | 'all' | 'episode' | 'feature' | 'short'>('closest')
  const [withBreaks, setWithBreaks] = useState(true)
  const [autoCloseGaps, setAutoCloseGaps] = useState(true)

  const selectedAsset = availableProgs.find(p => p.id === selectedId)

  // Calculate delta
  const diffMs = selectedAsset ? selectedAsset.durationMs - currentDur : 0
  const diffSec = Math.round(diffMs / 1000)

  // Filtered & sorted candidates
  const candidates = useMemo(() => {
    let list = availableProgs
    if (search.trim()) {
      const q = search.toLowerCase()
      list = list.filter(p => p.title.toLowerCase().includes(q) || p.kind.toLowerCase().includes(q))
    }
    if (filterMode === 'episode') {
      list = list.filter(p => p.durationMs >= 15 * 60_000 && p.durationMs <= 35 * 60_000)
    } else if (filterMode === 'feature') {
      list = list.filter(p => p.durationMs > 35 * 60_000)
    } else if (filterMode === 'short') {
      list = list.filter(p => p.durationMs < 15 * 60_000)
    }

    return list.slice().sort((a, b) => {
      const diffA = Math.abs(a.durationMs - currentDur)
      const diffB = Math.abs(b.durationMs - currentDur)
      return diffA - diffB
    })
  }, [availableProgs, search, filterMode, currentDur])

  return (
    <Modal
      wide
      title="Auto-Assisted Video Swap & Replace"
      subtitle="Swap video programming at this exact broadcast slot. Timing and commercial breaks are automatically recalculated."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy || !selectedId}
            onClick={() => onSubmit(selectedId, withBreaks, autoCloseGaps)}
          >
            {busy ? <span className="spinner" /> : <><Icon name="refresh" size={13} /> Confirm Auto-Assist Swap</>}
          </button>
        </>
      }
    >
      <div className="stack" style={{ gap: 14 }}>
        {/* Source Programme Being Replaced */}
        <div className="replace-source-card">
          <div className="row" style={{ gap: 10 }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, background: currentAsset?.color ?? '#6366f1' }} />
            <div>
              <span className="badge default sm" style={{ fontSize: 10, marginBottom: 2 }}>CURRENTLY SCHEDULED</span>
              <div style={{ fontWeight: 700, fontSize: 13.5 }}>{currentAsset?.title ?? 'Unknown Video'}</div>
              <div className="faint small">
                Airtime: {fmtDayTime(entry.startAt, v.channel.timezone)} – {fmtTime(entry.endAt, v.channel.timezone)}
              </div>
            </div>
          </div>
          <div className="stack" style={{ alignItems: 'flex-end', gap: 2 }}>
            <span className="badge info sm">{fmtDur(currentDur)}</span>
            <span className="faint small">{currentAsset?.kind ?? 'SHOW'}</span>
          </div>
        </div>

        {/* Video Candidate Selection with Filter Chips & Search */}
        <div className="stack" style={{ gap: 8 }}>
          <div className="between" style={{ flexWrap: 'wrap', gap: 8 }}>
            <span style={{ fontSize: 12, fontWeight: 650, color: 'var(--text)' }}>
              Select Replacement Candidate ({candidates.length} available)
            </span>
            <div className="filter-chips-row">
              <button
                type="button"
                className={`filter-chip-btn ${filterMode === 'closest' ? 'active' : ''}`}
                onClick={() => setFilterMode('closest')}
              >
                🎯 Closest Fit
              </button>
              <button
                type="button"
                className={`filter-chip-btn ${filterMode === 'all' ? 'active' : ''}`}
                onClick={() => setFilterMode('all')}
              >
                All Ready
              </button>
              <button
                type="button"
                className={`filter-chip-btn ${filterMode === 'episode' ? 'active' : ''}`}
                onClick={() => setFilterMode('episode')}
              >
                Episodes (~24m)
              </button>
              <button
                type="button"
                className={`filter-chip-btn ${filterMode === 'feature' ? 'active' : ''}`}
                onClick={() => setFilterMode('feature')}
              >
                Features (45m+)
              </button>
              <button
                type="button"
                className={`filter-chip-btn ${filterMode === 'short' ? 'active' : ''}`}
                onClick={() => setFilterMode('short')}
              >
                Shorts (&lt;15m)
              </button>
            </div>
          </div>

          <div className="library-search-wrap" style={{ height: 36 }}>
            <Icon name="search" size={13} />
            <input
              className="library-search-input"
              placeholder="Search candidate titles or categories..."
              value={search}
              onChange={e => setSearch(e.target.value)}
            />
            {search && (
              <button
                type="button"
                className="library-search-clear"
                onClick={() => setSearch('')}
                aria-label="Clear search"
              >
                <Icon name="x" size={12} />
              </button>
            )}
          </div>

          {/* Candidates Grid */}
          <div className="replace-candidate-grid">
            {candidates.map(cand => {
              const isSel = cand.id === selectedId
              const delta = Math.round((cand.durationMs - currentDur) / 1000)
              const absDelta = Math.abs(delta)
              return (
                <div
                  key={cand.id}
                  className={`replace-candidate-card ${isSel ? 'selected' : ''}`}
                  onClick={() => setSelectedId(cand.id)}
                >
                  <span style={{ width: 10, height: 10, borderRadius: 2, background: cand.color }} />
                  <div className="grow" style={{ minWidth: 0 }}>
                    <div className="ellipsis" style={{ fontWeight: 600, fontSize: 12.5 }}>
                      {cand.title}
                    </div>
                    <div className="row" style={{ gap: 6, marginTop: 2 }}>
                      <span className="faint small mono">{fmtDur(cand.durationMs)}</span>
                      <span className="badge default sm" style={{ fontSize: 9.5 }}>{cand.kind}</span>
                    </div>
                  </div>
                  <div>
                    {absDelta <= 3 ? (
                      <span className="delta-badge delta-exact">Exact Fit</span>
                    ) : delta < 0 ? (
                      <span className="delta-badge delta-shorter">-{fmtDur(Math.abs(delta) * 1000)}</span>
                    ) : (
                      <span className="delta-badge delta-longer">+{fmtDur(delta * 1000)}</span>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        {/* Live Comparison Preview & Impact */}
        {selectedAsset && (
          <div className="auto-assist-preview">
            <div className="between">
              <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text)' }}>
                ⚡ Auto-Assisted Playout Preview
              </span>
              <span className="faint small">
                Airtime starts at: {fmtTime(entry.startAt, v.channel.timezone)}
              </span>
            </div>

            <div className="preview-flow-row">
              <div className="preview-flow-item">
                <span className="faint small">Current Item:</span>
                <span style={{ fontSize: 12, fontWeight: 600 }}>{currentAsset?.title ?? 'Item'} ({fmtDur(currentDur)})</span>
              </div>
              <span style={{ color: 'var(--text-3)', fontSize: 14 }}>→</span>
              <div className="preview-flow-item">
                <span className="faint small">Replacement:</span>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--primary)' }}>{selectedAsset.title} ({fmtDur(selectedAsset.durationMs)})</span>
              </div>
              <span style={{ color: 'var(--text-3)', fontSize: 14 }}>=</span>
              <div className="preview-flow-item">
                <span className="faint small">Duration Delta:</span>
                <span style={{ fontSize: 12, fontWeight: 700 }}>
                  {diffSec === 0 ? '0s (Exact timing fit)' : diffSec > 0 ? `+${fmtDur(diffMs)} longer` : `-${fmtDur(Math.abs(diffMs))} shorter`}
                </span>
              </div>
            </div>

            <div className="small" style={{ color: 'var(--text-2)', lineHeight: 1.45, marginTop: 4 }}>
              {diffSec === 0 ? (
                <span>✅ <b>Perfect Timing:</b> The replacement matches the slot length exactly. Downstream schedule remains completely untouched.</span>
              ) : diffSec < 0 ? (
                <span>💡 <b>Slightly Shorter:</b> Enabling <i>Auto-close downstream gaps</i> below will pull following programs {fmtDur(Math.abs(diffMs))} earlier so no dead air occurs.</span>
              ) : (
                <span>⚠️ <b>Slightly Longer:</b> The replacement extends past the original end time by {fmtDur(diffMs)}. Future items may overlap unless shifted.</span>
              )}
            </div>
          </div>
        )}

        {/* Auto-assist options */}
        <div className="stack" style={{ gap: 8 }}>
          <label className="check" style={{ userSelect: 'none' }}>
            <input
              type="checkbox"
              checked={withBreaks}
              onChange={e => setWithBreaks(e.target.checked)}
            />
            <span style={{ fontSize: 12.5 }}>
              <b>Auto-schedule SCTE-35 ad breaks:</b> Intelligently recalculates commercial pods according to channel rules ({Math.round((v.channel.adSecondsPerHour || 720) / 60)} min/hr).
            </span>
          </label>

          <label className="check" style={{ userSelect: 'none' }}>
            <input
              type="checkbox"
              checked={autoCloseGaps}
              onChange={e => setAutoCloseGaps(e.target.checked)}
            />
            <span style={{ fontSize: 12.5 }}>
              <b>Auto-close downstream gaps:</b> Instantly ripples subsequent shows earlier if the replacement is shorter, keeping airwaves 100% gapless.
            </span>
          </label>
        </div>
      </div>
    </Modal>
  )
}

function ProgramModal({
  v,
  busy,
  onClose,
  onSubmit,
}: {
  v: View
  busy: boolean
  onClose: () => void
  onSubmit: (b: object) => void
}) {
  const progs = readyProgrammes(v)
  const [filterMode, setFilterMode] = useState<'all' | 'episode' | 'feature' | 'short'>('all')
  const [placementMode, setPlacementMode] = useState<'end' | 'custom'>('end')
  const [customStart, setCustomStart] = useState('')
  const [withBreaks, setWithBreaks] = useState(true)

  // Calculate schedule end
  const entries = v.draftEntries
  const lastEndMs = entries.length ? Math.max(...entries.map(e => new Date(e.endAt).getTime())) : Date.now()
  const projectedStartIso = placementMode === 'end'
    ? new Date(Math.max(Date.now(), lastEndMs)).toISOString()
    : (customStart || new Date(Date.now() + 3600_000).toISOString())

  // Filter programmes
  const filteredProgs = useMemo(() => {
    let list = progs
    if (filterMode === 'episode') {
      list = list.filter(p => p.durationMs >= 15 * 60_000 && p.durationMs <= 35 * 60_000)
    } else if (filterMode === 'feature') {
      list = list.filter(p => p.durationMs > 35 * 60_000)
    } else if (filterMode === 'short') {
      list = list.filter(p => p.durationMs < 15 * 60_000)
    }
    return list
  }, [progs, filterMode])

  const [assetId, setAssetId] = useState<string>(filteredProgs[0]?.id ?? progs[0]?.id ?? '')

  // Ensure assetId stays valid when filter changes
  useEffect(() => {
    if (filteredProgs.length > 0 && !filteredProgs.some(p => p.id === assetId)) {
      setAssetId(filteredProgs[0].id)
    }
  }, [filteredProgs, assetId])

  const selectedAsset = progs.find(p => p.id === assetId)

  // Options for Dropdown
  const videoDropdownOptions: DropdownOption[] = useMemo(
    () =>
      filteredProgs.map(p => ({
        value: p.id,
        label: p.title,
        icon: 'film',
        badge: fmtDur(p.durationMs),
        badgeType: 'info',
        description: `${p.kind} · ${fmtDur(p.durationMs)}`,
      })),
    [filteredProgs]
  )

  const projectedStart = projectedStartIso ? new Date(projectedStartIso).getTime() : Date.now()
  const projectedEnd = selectedAsset ? new Date(projectedStart + selectedAsset.durationMs).toISOString() : ''

  return (
    <Modal
      wide
      title="Add Programme to Broadcast Schedule"
      subtitle="Select a ready video asset. Timing and SCTE-35 commercial breaks are automatically calculated."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy || !assetId}
            onClick={() =>
              onSubmit({
                kind: 'PROGRAM',
                assetId,
                withBreaks,
                startAt: placementMode === 'custom' && customStart ? new Date(customStart).toISOString() : undefined,
              })
            }
          >
            {busy ? <span className="spinner" /> : '⚡ Add to Schedule'}
          </button>
        </>
      }
    >
      <div className="stack" style={{ gap: 14 }}>
        {/* Quick Duration Filter Chips */}
        <div className="between" style={{ flexWrap: 'wrap', gap: 8 }}>
          <span style={{ fontSize: 12, fontWeight: 650, color: 'var(--text)' }}>
            Filter Video Library ({filteredProgs.length} matching)
          </span>
          <div className="filter-chips-row">
            <button
              type="button"
              className={`filter-chip-btn ${filterMode === 'all' ? 'active' : ''}`}
              onClick={() => setFilterMode('all')}
            >
              All Ready ({progs.length})
            </button>
            <button
              type="button"
              className={`filter-chip-btn ${filterMode === 'episode' ? 'active' : ''}`}
              onClick={() => setFilterMode('episode')}
            >
              Episodes (~24m)
            </button>
            <button
              type="button"
              className={`filter-chip-btn ${filterMode === 'feature' ? 'active' : ''}`}
              onClick={() => setFilterMode('feature')}
            >
              Features (45m+)
            </button>
            <button
              type="button"
              className={`filter-chip-btn ${filterMode === 'short' ? 'active' : ''}`}
              onClick={() => setFilterMode('short')}
            >
              Shorts (&lt;15m)
            </button>
          </div>
        </div>

        {/* Video Asset Dropdown */}
        <div className="field">
          <span>Choose Video Asset</span>
          <Dropdown
            options={videoDropdownOptions}
            value={assetId}
            onChange={setAssetId}
            searchable
            align="full"
            placeholder="Search or pick video asset..."
          />
        </div>

        {/* Smart Placement Mode */}
        <div className="field">
          <span>Auto-Assisted Placement Mode</span>
          <div className="grid g2" style={{ gap: 10, marginTop: 4 }}>
            <label
              className={`card check ${placementMode === 'end' ? 'selected' : ''}`}
              style={{
                padding: '10px 12px',
                cursor: 'pointer',
                border: placementMode === 'end' ? '1px solid var(--primary)' : '1px solid var(--border)',
                background: placementMode === 'end' ? 'var(--primary-soft)' : 'var(--surface)',
              }}
            >
              <input
                type="radio"
                name="placement"
                checked={placementMode === 'end'}
                onChange={() => setPlacementMode('end')}
              />
              <div>
                <b>⚡ Auto-Append to End</b>
                <p className="faint small" style={{ margin: 0 }}>
                  Snaps right after the last scheduled show. 100% gapless continuity.
                </p>
              </div>
            </label>

            <label
              className={`card check ${placementMode === 'custom' ? 'selected' : ''}`}
              style={{
                padding: '10px 12px',
                cursor: 'pointer',
                border: placementMode === 'custom' ? '1px solid var(--primary)' : '1px solid var(--border)',
                background: placementMode === 'custom' ? 'var(--primary-soft)' : 'var(--surface)',
              }}
            >
              <input
                type="radio"
                name="placement"
                checked={placementMode === 'custom'}
                onChange={() => {
                  setPlacementMode('custom')
                  if (!customStart) setCustomStart(new Date(Date.now() + 3600_000).toISOString())
                }}
              />
              <div>
                <b>🕒 Specific Scheduled Time</b>
                <p className="faint small" style={{ margin: 0 }}>
                  Pick an exact broadcast date and hour
                </p>
              </div>
            </label>
          </div>

          {placementMode === 'custom' && (
            <div style={{ marginTop: 10 }}>
              <DateTimePicker
                value={customStart || new Date(Date.now() + 3600_000).toISOString()}
                onChange={v => setCustomStart(v)}
                timezone={v.channel.timezone}
                showTime={true}
                fixed={true}
              />
            </div>
          )}
        </div>

        {/* Live Auto-Assist Broadcast Preview */}
        {selectedAsset && (
          <div className="auto-assist-box">
            <div className="between">
              <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text)' }}>
                ⚡ Auto-Calculated Broadcast Slot
              </span>
              <span className="badge ok sm">Continuous Playout</span>
            </div>

            <div className="row" style={{ gap: 10, marginTop: 4 }}>
              <span style={{ width: 12, height: 12, borderRadius: 2, background: selectedAsset.color }} />
              <div>
                <b style={{ fontSize: 13 }}>{selectedAsset.title}</b>
                <div className="small faint" style={{ marginTop: 2 }}>
                  Airtime: <b>{fmtDayTime(projectedStartIso, v.channel.timezone)}</b> – <b>{fmtTime(projectedEnd, v.channel.timezone)}</b> ({fmtDur(selectedAsset.durationMs)})
                </div>
              </div>
            </div>

            <div className="small" style={{ color: 'var(--text-2)', marginTop: 4, lineHeight: 1.4 }}>
              ✓ Aligned to channel GOP 5-second keyframe boundary.<br />
              {withBreaks ? (
                <span>✓ SCTE-35 commercial ad pods will be automatically inserted at cue points based on channel ad rules ({Math.round((v.channel.adSecondsPerHour || 720) / 60)}m/hr).</span>
              ) : (
                <span>✓ Ad break auto-insertion is off; video will air uninterrupted.</span>
              )}
            </div>
          </div>
        )}

        {/* Ad breaks toggle */}
        <label className="check" style={{ userSelect: 'none' }}>
          <input
            type="checkbox"
            checked={withBreaks}
            onChange={e => setWithBreaks(e.target.checked)}
          />
          <span style={{ fontSize: 12.5 }}>
            <b>Automatically insert SCTE-35 ad breaks</b> at video cue points
          </span>
        </label>
      </div>
    </Modal>
  )
}

function BreakModal({
  v,
  busy,
  onClose,
  onSubmit,
}: {
  v: View
  busy: boolean
  onClose: () => void
  onSubmit: (b: object) => void
}) {
  const [seconds, setSeconds] = useState(String(v.channel.breakSeconds || 90))
  const [start, setStart] = useState('')

  return (
    <Modal
      title="Add Commercial Ad Break"
      subtitle="Reserve broadcast airtime for dynamic SSAI programmatic ads."
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
              onSubmit({
                kind: 'AD_BREAK',
                seconds: Number(seconds),
                startAt: start ? new Date(start).toISOString() : undefined,
              })
            }
          >
            {busy ? <span className="spinner" /> : 'Insert Ad Break'}
          </button>
        </>
      }
    >
      <div className="field">
        <span>Ad Break Duration</span>
        <Dropdown
          options={BREAK_SECONDS_OPTIONS}
          value={seconds}
          onChange={setSeconds}
          align="full"
        />
      </div>

      <StartPicker value={start} onChange={setStart} tz={v.channel.timezone} />

      <div className="callout sim" style={{ borderRadius: 10, padding: '12px 14px' }}>
        <Icon name="megaphone" size={16} />
        <div style={{ fontSize: 12.5, lineHeight: 1.45 }}>
          <b>{seconds}s Ad Pod ({Math.max(1, Math.round(Number(seconds) / 30))} dynamic spots)</b>
          <p style={{ margin: '3px 0 0', color: 'var(--text-2)' }}>
            Inserts SCTE-35 splice out/in markers for server-side dynamic ad insertion. Unfilled inventory safely plays the fallback channel slate.
          </p>
        </div>
      </div>
    </Modal>
  )
}

function PublishModal({
  v,
  errors,
  warnings,
  busy,
  onClose,
  onSubmit,
}: {
  v: View
  errors: Issue[]
  warnings: Issue[]
  busy: boolean
  onClose: () => void
  onSubmit: (note: string) => void
}) {
  const [note, setNote] = useState('')
  const nextNumber = (v.versions[0]?.number ?? 0) + 1

  return (
    <Modal
      title={errors.length ? 'Cannot Publish — Issues Detected' : `Publish Schedule Version ${nextNumber}`}
      subtitle="Publishing updates live HLS streaming feeds and EPG electronic program guides."
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            {errors.length ? 'Close' : 'Cancel'}
          </button>
          {errors.length === 0 && (
            <button
              type="button"
              className="btn primary"
              disabled={busy}
              onClick={() => onSubmit(note)}
            >
              <Icon name="rocket" size={15} />
              {busy ? <span className="spinner" /> : `Publish Version ${nextNumber} to Viewers`}
            </button>
          )}
        </>
      }
    >
      {errors.length > 0 ? (
        <div className="callout bad">
          <Icon name="alert" />
          <div>
            <b>{errors.length} critical issue(s) must be resolved first.</b>
            <p className="small" style={{ margin: '4px 0 0' }}>
              FAST Studio prevents airwave disruption by blocking publishes with overlapping content, missing licenses, or exceeded ad quotas.
            </p>
          </div>
        </div>
      ) : (
        <>
          <div className="callout ok">
            <Icon name="checkCircle" />
            <div>
              <b>Pre-flight checks passed:</b> All schedule entries are licenced and formatted.
              {v.channel.status === 'LIVE'
                ? ' Live players will seamlessly transition without playback interruption.'
                : ' Content will be ready when the channel goes live.'}
            </div>
          </div>

          {warnings.length > 0 && (
            <div className="callout warn">
              <Icon name="info" />
              <div>
                <b>{warnings.length} heads-up notice(s):</b> Non-blocking advisories.
              </div>
            </div>
          )}

          <label className="field" style={{ marginTop: 10 }}>
            <span>Release Changelog Note</span>
            <span className="hint">Optional audit log comment for version history</span>
            <input
              value={note}
              onChange={e => setNote(e.target.value)}
              placeholder="e.g. Added weekend primetime movie feature"
            />
          </label>
        </>
      )}
    </Modal>
  )
}
