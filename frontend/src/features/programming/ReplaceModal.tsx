import { useMemo, useState } from 'react'
import { type Entry } from '../../shared/api'
import {
  fmtDayTime,
  fmtDur,
  fmtTime,
  Icon,
  Modal,
} from '../../shared/ui'
import { type View, readyProgrammes } from './scheduleShared'

export function ReplaceModal({
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
