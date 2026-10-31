import { useMemo, useState } from 'react'
import { type Issue } from '../../shared/api'
import {
  Dropdown,
  fmtDur,
  Icon,
  Modal,
} from '../../shared/ui'
import { type View, AUTOFILL_HOURS_OPTIONS, readyProgrammes } from './scheduleShared'

export function AutofillModal({
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

export function PublishModal({
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
