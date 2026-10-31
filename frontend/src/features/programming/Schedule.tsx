import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError, type ChannelView, type Entry, type Issue } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Confirm,
  Dropdown,
  type DropdownOption,
  Empty,
  ErrorBox,
  fmtDayTime,
  Icon,
  PageHead,
  useApi,
  useToast,
} from '../../shared/ui'
import { SmartProgramming } from './SmartProgramming'
import { Timeline, Selected, EntryList } from './ScheduleTimeline'
import { PreflightPanel } from './PreflightPanel'
import { ReplaceModal } from './ReplaceModal'
import { ProgramModal, BreakModal } from './ProgramModal'
import { AutofillModal, PublishModal } from './ScheduleModals'
import { type View, ZOOM } from './scheduleShared'

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
