import { useEffect, useMemo, useState } from 'react'
import {
  Dropdown,
  type DropdownOption,
  fmtDayTime,
  fmtDur,
  fmtTime,
  Icon,
  Modal,
} from '../../shared/ui'
import { DateTimePicker } from '../../shared/components/DateTimePicker'
import { type View, BREAK_SECONDS_OPTIONS, readyProgrammes } from './scheduleShared'

export function StartPicker({
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

export function ProgramModal({
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

export function BreakModal({
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
