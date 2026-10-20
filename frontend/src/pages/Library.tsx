import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import Hls from 'hls.js'
import { api, type AssetView } from '../api'
import { useOrg } from '../app'
import {
  Confirm,
  DateTimePicker,
  Drawer,
  Dropdown,
  type DropdownOption,
  Empty,
  ErrorBox,
  flag,
  fmtDur,
  Icon,
  InfoTip,
  Loading,
  Modal,
  PageHead,
  Status,
  useAction,
  useApi,
} from '../ui'

const KINDS = [
  { key: '', label: 'All Catalog', icon: 'film' },
  { key: 'MOVIE', label: 'Movies', icon: 'film' },
  { key: 'EPISODE', label: 'Episodes', icon: 'tv' },
  { key: 'SHORT', label: 'Shorts', icon: 'sliders' },
  { key: 'AD', label: 'Ad Creatives', icon: 'megaphone' },
] as const

const KIND_LABEL: Record<string, string> = {
  MOVIE: 'Movie',
  EPISODE: 'Episode',
  SHORT: 'Short',
  AD: 'Commercial Ad',
  SLATE: 'Between Programmes',
}

export function formatMediaKind(k: string): string {
  if (!k) return 'Media'
  if (KIND_LABEL[k]) return KIND_LABEL[k]
  // Format dynamic enum keys from admin portal like "DOCUMENTARY_FILM" -> "Documentary Film"
  return k
    .split('_')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
    .join(' ')
}

const GENRE_SUGGESTIONS = [
  'Action', 'Adventure', 'Animation', 'Comedy', 'Documentary', 
  'Drama', 'Fantasy', 'Horror', 'Lifestyle', 'Music', 
  'News', 'Romance', 'Sci-Fi', 'Sports', 'Thriller', 'True Crime'
]
const COLOR_PRESETS = ['#6366f1', '#06b6d4', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6', '#3b82f6', '#14b8a6']

export function GenreSelector({
  value,
  onChange,
  disabled = false,
}: {
  value: string
  onChange: (val: string) => void
  disabled?: boolean
}) {
  const [customGenre, setCustomGenre] = useState('')
  const selectedGenres = useMemo(() => {
    return value ? value.split(',').map(s => s.trim()).filter(Boolean) : []
  }, [value])

  const toggleGenre = (gName: string) => {
    if (disabled) return
    const trimmed = gName.trim()
    if (!trimmed) return
    let next: string[]
    if (selectedGenres.includes(trimmed)) {
      next = selectedGenres.filter(g => g !== trimmed)
    } else {
      next = [...selectedGenres, trimmed]
    }
    onChange(next.join(', '))
  }

  const handleAddCustom = (e: React.FormEvent) => {
    e.preventDefault()
    if (disabled) return
    const trimmed = customGenre.trim()
    if (!trimmed) return
    if (!selectedGenres.includes(trimmed)) {
      toggleGenre(trimmed)
    }
    setCustomGenre('')
  }

  return (
    <div className="field library-genre-section">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <span className="row" style={{ gap: 6, alignItems: 'center' }}>
          <Icon name="sliders" size={13} />
          <b>Genre(s)</b>
          <span className="faint small" style={{ fontWeight: 400 }}>— select all that apply</span>
        </span>
        <div className="row" style={{ gap: 8, alignItems: 'center' }}>
          {selectedGenres.length > 0 && (
            <>
              <span className="badge primary sm">{selectedGenres.length} selected</span>
              {!disabled && (
                <button
                  type="button"
                  className="btn ghost sm"
                  style={{ height: 22, padding: '0 6px', fontSize: 11 }}
                  onClick={() => onChange('')}
                >
                  Clear all
                </button>
              )}
            </>
          )}
        </div>
      </div>

      {/* Checkbox grid of genres */}
      <div className="library-genre-checkbox-grid">
        {GENRE_SUGGESTIONS.map(g => {
          const isChecked = selectedGenres.includes(g)
          return (
            <button
              key={g}
              type="button"
              role="checkbox"
              aria-checked={isChecked}
              disabled={disabled}
              className={`library-genre-check-pill ${isChecked ? 'is-checked' : ''}`}
              onClick={() => toggleGenre(g)}
            >
              <span className={`genre-check-box ${isChecked ? 'is-checked' : ''}`}>
                {isChecked && <Icon name="check" size={11} />}
              </span>
              <span className="genre-check-label">{g}</span>
            </button>
          )
        })}

        {/* Custom genres added by user */}
        {selectedGenres
          .filter(g => !GENRE_SUGGESTIONS.includes(g))
          .map(g => (
            <button
              key={g}
              type="button"
              role="checkbox"
              aria-checked={true}
              disabled={disabled}
              className="library-genre-check-pill is-checked"
              onClick={() => toggleGenre(g)}
            >
              <span className="genre-check-box is-checked">
                <Icon name="check" size={11} />
              </span>
              <span className="genre-check-label">{g}</span>
            </button>
          ))}
      </div>

      {/* Custom genre add row */}
      {!disabled && (
        <div className="library-custom-genre-row">
          <input
            type="text"
            placeholder="+ Add custom genre tag (e.g. Cyberpunk, Anime)…"
            value={customGenre}
            onChange={e => setCustomGenre(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault()
                handleAddCustom(e)
              }
            }}
            className="library-custom-genre-input"
          />
          <button
            type="button"
            className="btn sm"
            disabled={!customGenre.trim()}
            onClick={handleAddCustom}
          >
            <Icon name="plus" size={13} /> Add
          </button>
        </div>
      )}
    </div>
  )
}

export default function Library() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const [kind, setKind] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [rightsFilter, setRightsFilter] = useState('ALL')
  const [sortBy, setSortBy] = useState('RECENT')
  const [q, setQ] = useState('')
  const [viewMode, setViewMode] = useState<'grid' | 'table'>('grid')
  const [adding, setAdding] = useState(false)

  // Fetch full asset list for the org (stable catalog-wide dataset)
  const list = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`, [], 0)
  const processing = list.data?.some(a => a.asset.status === 'PROCESSING')

  // Auto-refresh when any transcode is in progress
  useEffect(() => {
    if (!processing) return
    const t = setInterval(list.reload, 4000)
    return () => clearInterval(t)
  }, [processing, list.reload])

  const openId = params.get('asset')
  const open = list.data?.find(a => a.asset.id === openId)

  // Full catalog base (stable)
  const allAssets = list.data ?? []

  // Stable catalog-wide total counts (these NEVER drop to 0 when filtering!)
  const totalCatalogCount = allAssets.length
  const totalReadyCount = allAssets.filter(a => a.asset.status === 'READY').length
  const totalProcessingCount = allAssets.filter(a => a.asset.status === 'PROCESSING' || a.asset.status === 'DRAFT').length
  const totalRightsIssues = allAssets.filter(a => a.asset.kind !== 'AD' && a.rightsStatus !== 'OK').length
  const totalClearedCount = allAssets.filter(a => a.rightsStatus === 'OK' || a.asset.kind === 'AD').length

  // Stable category counts across the entire catalog (never drop to 0!)
  const countsByKind = useMemo(() => {
    const c: Record<string, number> = { '': totalCatalogCount, MOVIE: 0, EPISODE: 0, SHORT: 0, AD: 0 }
    allAssets.forEach(a => {
      if (c[a.asset.kind] !== undefined) c[a.asset.kind]++
    })
    return c
  }, [allAssets, totalCatalogCount])

  // Check if any filter is actively applied
  const isFiltered = Boolean(kind || q.trim() || statusFilter !== 'ALL' || rightsFilter !== 'ALL')

  // Filtered & Sorted list
  const filteredAssets = useMemo(() => {
    let result = [...allAssets]

    // Format filter
    if (kind) {
      result = result.filter(a => a.asset.kind === kind)
    }

    // Search query filter
    if (q.trim()) {
      const query = q.trim().toLowerCase()
      result = result.filter(
        a =>
          a.asset.title.toLowerCase().includes(query) ||
          (a.asset.genre && a.asset.genre.toLowerCase().includes(query)) ||
          (a.asset.description && a.asset.description.toLowerCase().includes(query))
      )
    }

    // Status filter
    if (statusFilter !== 'ALL') {
      result = result.filter(a => a.asset.status === statusFilter)
    }

    // Rights filter
    if (rightsFilter === 'OK') {
      result = result.filter(a => a.rightsStatus === 'OK' || a.asset.kind === 'AD')
    } else if (rightsFilter === 'ISSUE') {
      result = result.filter(a => a.asset.kind !== 'AD' && a.rightsStatus !== 'OK')
    }

    // Sort
    if (sortBy === 'TITLE_ASC') {
      result.sort((a, b) => a.asset.title.localeCompare(b.asset.title))
    } else if (sortBy === 'TITLE_DESC') {
      result.sort((a, b) => b.asset.title.localeCompare(a.asset.title))
    } else if (sortBy === 'DUR_DESC') {
      result.sort((a, b) => (b.asset.durationMs || 0) - (a.asset.durationMs || 0))
    } else if (sortBy === 'DUR_ASC') {
      result.sort((a, b) => (a.asset.durationMs || 0) - (b.asset.durationMs || 0))
    }

    return result
  }, [allAssets, kind, q, statusFilter, rightsFilter, sortBy])

  // Metrics for the filtered subset
  const filteredReadyCount = filteredAssets.filter(a => a.asset.status === 'READY').length
  const filteredProcessingCount = filteredAssets.filter(a => a.asset.status === 'PROCESSING' || a.asset.status === 'DRAFT').length
  const filteredRightsIssues = filteredAssets.filter(a => a.asset.kind !== 'AD' && a.rightsStatus !== 'OK').length
  const filteredClearedCount = filteredAssets.filter(a => a.rightsStatus === 'OK' || a.asset.kind === 'AD').length

  const statusOptions: DropdownOption[] = [
    { value: 'ALL', label: 'All statuses', icon: 'sliders', description: 'Show all playout readiness states' },
    { value: 'READY', label: 'Ready to air', icon: 'checkCircle', description: 'Transcoded & ready for live playout', badge: 'Ready', badgeType: 'ok' },
    { value: 'PROCESSING', label: 'Processing video', icon: 'refresh', description: 'Currently transcoding with FFmpeg', badge: 'Active', badgeType: 'warn' },
    { value: 'DRAFT', label: 'Draft (missing file)', icon: 'upload', description: 'Awaiting video source upload' },
    { value: 'FAILED', label: 'Transcode failed', icon: 'alert', description: 'Error during video encoding', badge: 'Error', badgeType: 'live' },
  ]

  const rightsOptions: DropdownOption[] = [
    { value: 'ALL', label: 'All licences', icon: 'shield', description: 'Show all distribution licences' },
    { value: 'OK', label: 'Rights cleared', icon: 'shieldCheck', description: 'Valid licence with active territory permission', badge: 'Cleared', badgeType: 'ok' },
    { value: 'ISSUE', label: 'Needs attention', icon: 'alert', description: 'Missing rights, expired, or blocked', badge: 'Action', badgeType: 'warn' },
  ]

  const sortOptions: DropdownOption[] = [
    { value: 'RECENT', label: 'Recently added', icon: 'clock', description: 'Default catalog order' },
    { value: 'TITLE_ASC', label: 'Title (A → Z)', icon: 'film', description: 'Alphabetical ascending' },
    { value: 'TITLE_DESC', label: 'Title (Z → A)', icon: 'film', description: 'Alphabetical descending' },
    { value: 'DUR_DESC', label: 'Duration (Longest first)', icon: 'clock', description: 'Feature movies and long episodes' },
    { value: 'DUR_ASC', label: 'Duration (Shortest first)', icon: 'clock', description: 'Quick shorts and ad spots' },
  ]

  return (
    <div className="page library-page">
      {/* Header */}
      <PageHead
        title="Media Library"
        subtitle="Manage programmes, movies, episodes, and sponsor ads ready to broadcast across your linear FAST channels."
        actions={
          <div className="row" style={{ gap: 10 }}>
            <div className="seg" role="group" aria-label="View mode">
              <button
                type="button"
                className={viewMode === 'grid' ? 'active' : ''}
                onClick={() => setViewMode('grid')}
                title="Grid poster view"
                aria-label="Grid poster view"
              >
                <Icon name="grid" size={15} />
              </button>
              <button
                type="button"
                className={viewMode === 'table' ? 'active' : ''}
                onClick={() => setViewMode('table')}
                title="Compact list view"
                aria-label="Compact list view"
              >
                <Icon name="list" size={15} />
              </button>
            </div>
            {org.can('ASSETS_EDIT') && (
              <button type="button" className="btn primary" onClick={() => setAdding(true)}>
                <Icon name="plus" size={16} /> Add media
              </button>
            )}
          </div>
        }
      />

      {/* Top High-Level Metric Cards with Explicit Scope Labels */}
      <div className="grid g4 library-kpi-grid">
        <div className="card library-kpi-card color-primary">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-primary">
              <Icon name="film" size={18} />
            </span>
            {isFiltered ? (
              <span className="badge primary sm">Filtered Selection</span>
            ) : (
              <span className="badge default sm">{countsByKind['AD'] || 0} ads</span>
            )}
          </div>
          <div>
            <div className="library-kpi-val">
              {isFiltered ? (
                <>
                  {filteredAssets.length}{' '}
                  <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                    / {totalCatalogCount}
                  </span>
                </>
              ) : (
                totalCatalogCount
              )}
            </div>
            <div className="library-kpi-label">
              {isFiltered ? (kind ? `${formatMediaKind(kind)}s Matching` : 'Filtered Titles') : 'Total Catalog'}
            </div>
            <div className="library-kpi-sub faint small">
              {isFiltered
                ? `Showing ${filteredAssets.length} of ${totalCatalogCount} in library`
                : 'Across all formats & genres'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-emerald">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-emerald">
              <Icon name="checkCircle" size={18} />
            </span>
            <span className="badge ok sm">{isFiltered ? 'In Selection' : 'Broadcasting'}</span>
          </div>
          <div>
            <div className="library-kpi-val">
              {isFiltered ? (
                <>
                  {filteredReadyCount}{' '}
                  <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                    / {totalReadyCount}
                  </span>
                </>
              ) : (
                totalReadyCount
              )}
            </div>
            <div className="library-kpi-label">
              {isFiltered ? 'Ready in Selection' : 'Ready to Air'}
            </div>
            <div className="library-kpi-sub faint small">
              {isFiltered
                ? `${filteredReadyCount} of ${totalReadyCount} ready titles matching`
                : 'Encoded into 3-sec HLS segments'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-cyan">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-cyan">
              <Icon name="refresh" size={18} />
            </span>
            {(isFiltered ? filteredProcessingCount : totalProcessingCount) > 0 ? (
              <span className="badge warn sm">
                <span className="spinner" style={{ width: 10, height: 10, borderWidth: 1.5 }} /> Processing
              </span>
            ) : (
              <span className="badge ok sm">Completed</span>
            )}
          </div>
          <div>
            <div className="library-kpi-val">
              {isFiltered ? (
                <>
                  {filteredProcessingCount}{' '}
                  <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                    / {totalProcessingCount}
                  </span>
                </>
              ) : (
                totalProcessingCount
              )}
            </div>
            <div className="library-kpi-label">
              {isFiltered ? 'In Transcode (Filtered)' : 'In Ingest / Transcode'}
            </div>
            <div className="library-kpi-sub faint small">
              {totalProcessingCount > 0 ? 'Background FFmpeg encoding' : 'All video files ready'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-purple">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-purple">
              <Icon name="shieldCheck" size={18} />
            </span>
            {(isFiltered ? filteredRightsIssues : totalRightsIssues) > 0 ? (
              <span className="badge warn sm">
                {isFiltered ? filteredRightsIssues : totalRightsIssues} needs rights
              </span>
            ) : (
              <span className="badge ok sm">Compliant</span>
            )}
          </div>
          <div>
            <div className="library-kpi-val">
              {isFiltered ? (
                <>
                  {filteredClearedCount}{' '}
                  <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                    / {totalClearedCount}
                  </span>
                </>
              ) : (
                totalClearedCount
              )}
            </div>
            <div className="library-kpi-label">
              {isFiltered ? 'Cleared in Selection' : 'Licence Clearance'}
            </div>
            <div className="library-kpi-sub faint small">
              {totalRightsIssues > 0
                ? `${totalRightsIssues} titles require licence update`
                : 'All programmes cleared'}
            </div>
          </div>
        </div>
      </div>

      {/* Real-time processing notice */}
      {processing && (
        <div className="callout warn library-processing-banner">
          <span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }} />
          <div className="grow">
            <b>Background Video Processing Active</b>
            <div className="small faint">
              FFmpeg is converting video files into standardized 640×360 HLS segments for seamless broadcast stitching. This page updates automatically.
            </div>
          </div>
        </div>
      )}

      {/* Search & Filter Toolbar */}
      <div className="library-filter-bar">
        {/* Format segmented switcher + Catalog live counter */}
        <div className="library-filter-nav">
          <div className="library-format-tabs" role="tablist" aria-label="Catalog format">
            {KINDS.map(k => {
              const count = countsByKind[k.key] ?? 0
              const active = kind === k.key
              return (
                <button
                  key={k.key}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  className={`library-tab-btn ${active ? 'is-active' : ''}`}
                  onClick={() => setKind(active && k.key !== '' ? '' : k.key)}
                  title={active ? `Filtering by ${k.label}. Click to show all formats.` : `Filter by ${k.label}`}
                >
                  <Icon name={k.icon} size={13} />
                  <span>{k.label}</span>
                  <span className="library-tab-count">{count}</span>
                </button>
              )
            })}
          </div>

          <div className="library-catalog-meta">
            <span className="library-catalog-meta-count">
              <strong>{filteredAssets.length}</strong>
              <span className="faint"> / {totalCatalogCount} titles</span>
            </span>
            {isFiltered && (
              <span className="library-catalog-meta-badge">
                <span className="meta-badge-dot" /> Filtered
              </span>
            )}
          </div>
        </div>

        {/* Search, Status, Rights & Sort Controls */}
        <div className="library-controls-row">
          {/* Live Search Input */}
          <div className={`library-search-wrap ${q ? 'has-value' : ''}`}>
            <Icon name="search" size={14} />
            <input
              type="text"
              className="library-search-input"
              placeholder="Search catalog by title, genre, series…"
              value={q}
              onChange={e => setQ(e.target.value)}
              aria-label="Search video catalog"
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
            {/* Status Filter */}
            <Dropdown
              className={statusFilter !== 'ALL' ? 'is-filtered' : ''}
              options={statusOptions}
              value={statusFilter}
              onChange={setStatusFilter}
              style={{ minWidth: 155 }}
            />

            {/* Rights Filter */}
            <Dropdown
              className={rightsFilter !== 'ALL' ? 'is-filtered' : ''}
              options={rightsOptions}
              value={rightsFilter}
              onChange={setRightsFilter}
              style={{ minWidth: 155 }}
            />

            {/* Sort */}
            <Dropdown
              className={sortBy !== 'RECENT' ? 'is-filtered' : ''}
              options={sortOptions}
              value={sortBy}
              onChange={setSortBy}
              align="right"
              style={{ minWidth: 165 }}
            />

            {/* Clear filters quick action button if any filter is active */}
            {isFiltered && (
              <button
                type="button"
                className="library-quick-clear-btn"
                onClick={() => {
                  setKind('')
                  setQ('')
                  setStatusFilter('ALL')
                  setRightsFilter('ALL')
                }}
                title="Reset all applied filters"
              >
                <Icon name="refresh" size={12} />
                <span>Reset</span>
              </button>
            )}
          </div>
        </div>

        {/* Minimal Active Filter Chips Row */}
        {isFiltered && (
          <div className="library-active-filters-strip">
            <div className="library-active-filters-tags">
              <span className="library-active-tag-label">
                <Icon name="sliders" size={11} />
                <span>Active:</span>
              </span>

              {kind && (
                <span className="active-filter-chip">
                  <span className="chip-key">Format:</span>
                  <strong>{formatMediaKind(kind)}</strong>
                  <button type="button" onClick={() => setKind('')} aria-label="Remove format filter" title="Remove format filter">
                    <Icon name="x" size={11} />
                  </button>
                </span>
              )}
              {q.trim() && (
                <span className="active-filter-chip">
                  <span className="chip-key">Search:</span>
                  <strong>"{q.trim()}"</strong>
                  <button type="button" onClick={() => setQ('')} aria-label="Remove search filter" title="Remove search filter">
                    <Icon name="x" size={11} />
                  </button>
                </span>
              )}
              {statusFilter !== 'ALL' && (
                <span className="active-filter-chip">
                  <span className="chip-key">Status:</span>
                  <strong>{statusOptions.find(o => o.value === statusFilter)?.label}</strong>
                  <button type="button" onClick={() => setStatusFilter('ALL')} aria-label="Remove status filter" title="Remove status filter">
                    <Icon name="x" size={11} />
                  </button>
                </span>
              )}
              {rightsFilter !== 'ALL' && (
                <span className="active-filter-chip">
                  <span className="chip-key">Licence:</span>
                  <strong>{rightsOptions.find(o => o.value === rightsFilter)?.label}</strong>
                  <button type="button" onClick={() => setRightsFilter('ALL')} aria-label="Remove licence filter" title="Remove licence filter">
                    <Icon name="x" size={11} />
                  </button>
                </span>
              )}
              <button
                type="button"
                className="library-reset-pill-btn"
                onClick={() => {
                  setKind('')
                  setQ('')
                  setStatusFilter('ALL')
                  setRightsFilter('ALL')
                }}
                title="Reset all filters back to full catalog"
              >
                <span>Clear All</span>
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Main Content Area */}
      {list.error ? (
        <ErrorBox error={list.error} retry={list.reload} />
      ) : !list.data ? (
        <Loading rows={4} />
      ) : filteredAssets.length === 0 ? (
        <div className="card library-empty-card">
          <Empty
            icon="film"
            title={isFiltered ? 'No matching media found' : 'No media in your library yet'}
            text={
              isFiltered
                ? 'No media titles match your active filter and search combination. Reset filters to view all catalog titles.'
                : 'Upload a media file from your computer, or generate an instant test broadcast with no file required.'
            }
            action={
              isFiltered ? (
                <button
                  type="button"
                  className="btn primary sm"
                  onClick={() => {
                    setKind('')
                    setQ('')
                    setStatusFilter('ALL')
                    setRightsFilter('ALL')
                  }}
                >
                  <Icon name="refresh" size={14} /> Clear all filters ({totalCatalogCount} available)
                </button>
              ) : (
                org.can('ASSETS_EDIT') && (
                  <button type="button" className="btn primary" onClick={() => setAdding(true)}>
                    <Icon name="plus" size={16} /> Add your first media
                  </button>
                )
              )
            }
          />
        </div>
      ) : viewMode === 'grid' ? (
        /* Visual Grid View */
        <div className="grid g4 library-assets-grid">
          {filteredAssets.map(a => {
            const isReady = a.asset.status === 'READY'
            const isScheduled = a.channels.length > 0
            const cueCount = a.asset.cuePoints ? a.asset.cuePoints.split(',').filter(Boolean).length : 0

            return (
              <button
                key={a.asset.id}
                type="button"
                className="card library-video-card"
                onClick={() => setParams({ asset: a.asset.id })}
                aria-label={`Inspect media ${a.asset.title}`}
              >
                {/* 16:9 Thumbnail Poster */}
                <div className="library-card-thumb" style={{ background: a.asset.color || 'var(--surface-3)' }}>
                  {a.thumbUrl && isReady ? (
                    <img src={a.thumbUrl} alt="" loading="lazy" className="library-card-img" />
                  ) : (
                    <div className="library-card-placeholder">
                      <Icon name="film" size={32} />
                    </div>
                  )}

                  {/* Hover Play Button Overlay */}
                  <div className="library-card-play-hover">
                    <span className="library-card-play-icon">
                      <Icon name="play" size={24} />
                    </span>
                  </div>

                  {/* Top Status Badge */}
                  <div className="library-card-badge-top-left">
                    <Status value={a.asset.status} />
                  </div>

                  {/* Top Rights Badge */}
                  {a.asset.kind !== 'AD' && (
                    <div className="library-card-badge-top-right">
                      <span className={`badge ${a.rightsStatus === 'OK' ? 'ok' : 'warn'} sm`}>
                        <Icon name={a.rightsStatus === 'OK' ? 'shieldCheck' : 'alert'} size={11} />
                        {a.rightsStatus === 'OK' ? 'Licensed' : 'No licence'}
                      </span>
                    </div>
                  )}

                  {/* Duration Tag */}
                  {a.asset.durationMs > 0 && (
                    <span className="library-card-dur">
                      <Icon name="clock" size={11} />
                      {fmtDur(a.asset.durationMs)}
                    </span>
                  )}
                </div>

                {/* Card Info Details */}
                <div className="library-card-body">
                  <div className="library-card-title-row">
                    <b className="library-card-title" title={a.asset.title}>
                      {a.asset.title}
                    </b>
                  </div>

                  <div className="library-card-meta-row">
                    <span className="library-kind-tag">{formatMediaKind(a.asset.kind)}</span>
                    <span className="library-meta-dot">·</span>
                    <span className="library-genre-tag faint ellipsis">{a.asset.genre || 'Unspecified'}</span>
                  </div>

                  {/* Channel scheduled indicators / Cue points */}
                  <div className="library-card-footer">
                    {isScheduled ? (
                      <span className="library-channel-pill" title={`On air on ${a.channels.join(', ')}`}>
                        <Icon name="tv" size={12} />
                        <span>{a.channels.length} {a.channels.length === 1 ? 'channel' : 'channels'}</span>
                      </span>
                    ) : (
                      <span className="faint small">Not scheduled</span>
                    )}

                    {cueCount > 0 && (
                      <span className="library-cue-pill" title={`${cueCount} commercial ad breaks inserted`}>
                        <Icon name="megaphone" size={11} />
                        <span>{cueCount} breaks</span>
                      </span>
                    )}
                  </div>
                </div>
              </button>
            )
          })}
        </div>
      ) : (
        /* Compact Table List View */
        <div className="card library-table-card">
          <div className="table-wrap">
            <table className="library-table">
              <thead>
                <tr>
                  <th style={{ width: 44 }}>Media</th>
                  <th>Title & Genre</th>
                  <th>Format</th>
                  <th>Duration</th>
                  <th>Status</th>
                  <th>Licence</th>
                  <th>Linear Channels</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredAssets.map(a => {
                  const isReady = a.asset.status === 'READY'
                  return (
                    <tr
                      key={a.asset.id}
                      className="library-table-row"
                      onClick={() => setParams({ asset: a.asset.id })}
                    >
                      <td>
                        <div
                          className="library-table-thumb"
                          style={{ background: a.asset.color || 'var(--surface-3)' }}
                        >
                          {a.thumbUrl && isReady ? (
                            <img src={a.thumbUrl} alt="" loading="lazy" />
                          ) : (
                            <Icon name="film" size={16} />
                          )}
                        </div>
                      </td>
                      <td>
                        <div className="stack" style={{ gap: 2 }}>
                          <b className="library-table-title">{a.asset.title}</b>
                          <span className="faint small">{a.asset.genre || 'No genre'}</span>
                        </div>
                      </td>
                      <td>
                        <span className="library-kind-tag">{KIND_LABEL[a.asset.kind] || a.asset.kind}</span>
                      </td>
                      <td>
                        <span className="row" style={{ gap: 5, fontVariantNumeric: 'tabular-nums' }}>
                          <Icon name="clock" size={13} />
                          {a.asset.durationMs > 0 ? fmtDur(a.asset.durationMs) : '—'}
                        </span>
                      </td>
                      <td>
                        <Status value={a.asset.status} />
                      </td>
                      <td>
                        {a.asset.kind !== 'AD' ? (
                          <span className={`badge ${a.rightsStatus === 'OK' ? 'ok' : 'warn'} sm`}>
                            {a.rightsStatus === 'OK' ? 'Cleared' : 'Needs Rights'}
                          </span>
                        ) : (
                          <span className="faint small">—</span>
                        )}
                      </td>
                      <td>
                        {a.channels.length > 0 ? (
                          <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                            {a.channels.map(ch => (
                              <span key={ch} className="chip sm">
                                <Icon name="tv" size={11} /> {ch}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <span className="faint small">Unscheduled</span>
                        )}
                      </td>
                      <td style={{ textAlign: 'right' }}>
                        <button
                          type="button"
                          className="btn sm ghost"
                          onClick={e => {
                            e.stopPropagation()
                            setParams({ asset: a.asset.id })
                          }}
                        >
                          Details →
                        </button>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Add Video Modal */}
      {adding && (
        <AddVideoModal
          onClose={() => setAdding(false)}
          onDone={id => {
            setAdding(false)
            list.reload()
            setParams({ asset: id })
          }}
        />
      )}

      {/* Detailed Asset Drawer */}
      {open && (
        <AssetDrawer
          view={open}
          onClose={() => setParams({})}
          onChange={list.reload}
        />
      )}
    </div>
  )
}

/** Redesigned Add Video Modal for Non-Tech Users */
/** Redesigned Add Video Modal with Simplified Layout & DateTime Scheduling */
function AddVideoModal({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const org = useOrg()
  const { busy, run } = useAction()
  const [mode, setMode] = useState<'upload' | 'sample'>('upload')
  const [f, setF] = useState({
    title: '',
    kind: 'MOVIE',
    genre: 'Action',
    description: '',
    minutes: 3,
    tagline: '',
    color: '#6366f1',
  })
  const [releaseDateTime, setReleaseDateTime] = useState<string>('')
  const [file, setFile] = useState<File | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const isAd = f.kind === 'AD'

  // Auto-fill title from filename if title is empty
  const handleFileChange = (selected: File | null) => {
    setFile(selected)
    if (selected && !f.title.trim()) {
      const cleanName = selected.name
        .replace(/\.[^/.]+$/, '')
        .replace(/[-_]+/g, ' ')
        .replace(/\b\w/g, l => l.toUpperCase())
      set('title', cleanName)
    }
  }


  async function submit() {
    if (!f.title.trim()) throw new Error('Please enter a title for the video.')
    if (mode === 'upload' && !file) throw new Error('Please choose a video file to upload.')

    const body = {
      title: f.title.trim(),
      kind: f.kind,
      genre: isAd ? 'Advertising' : f.genre.trim(),
      description: f.description.trim(),
      color: f.color,
      ...(mode === 'sample'
        ? {
            sampleSeconds: isAd ? Number(f.minutes) : Math.round(Number(f.minutes) * 60),
            sampleTagline: f.tagline.trim(),
          }
        : {}),
    }

    const v = await api<AssetView>(`/api/orgs/${org.slug}/assets`, { body })

    if (mode === 'upload' && file) {
      const form = new FormData()
      form.append('file', file)
      await api(`/api/orgs/${org.slug}/assets/${v.asset.id}/upload`, { form })
    }

    // Attach release / broadcast schedule date to rights policy if set
    if (releaseDateTime) {
      try {
        await api(`/api/orgs/${org.slug}/assets/${v.asset.id}/rights`, {
          method: 'PUT',
          body: {
            allowedCountries: 'WW',
            blockedCountries: '',
            validFrom: new Date(releaseDateTime).toISOString(),
            validUntil: null,
            adsAllowed: true,
            notes: `Scheduled release: ${new Date(releaseDateTime).toLocaleString()}`,
          },
        })
      } catch (err) {
        console.warn('Could not set scheduled broadcast rights:', err)
      }
    }

    return v.asset.id
  }

  const kindOptions: DropdownOption[] = [
    { value: 'MOVIE', label: 'Feature Movie', icon: 'film', description: 'Full-length movie or documentary' },
    { value: 'EPISODE', label: 'TV Episode', icon: 'tv', description: 'Episodic series content with season/episode' },
    { value: 'SHORT', label: 'Short / Interstitial', icon: 'sliders', description: 'Bite-sized clip, filler or vignette' },
    { value: 'AD', label: 'Commercial Ad', icon: 'megaphone', description: 'Sponsor ad creative for SSAI breaks' },
  ]

  return (
    <Modal
      title="Add Media to Catalog"
      subtitle="Upload full media files or generate instant test broadcasts ready for playout."
      onClose={onClose}
      wide
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy}
            onClick={() =>
              run(submit, 'Media added — background transcoding started').then(id => id && onDone(id))
            }
          >
            {busy ? (
              <span className="spinner" />
            ) : mode === 'upload' ? (
              <>
                <Icon name="upload" size={15} /> Upload & Ingest Media
              </>
            ) : (
              <>
                <Icon name="wand" size={15} /> Generate Demo Media
              </>
            )}
          </button>
        </>
      }
    >
      {/* Sleek Segmented Mode Switcher */}
      <div className="library-mode-switcher-wrap">
        <div className="library-mode-segmented">
          <button
            type="button"
            className={`library-mode-btn ${mode === 'upload' ? 'is-active' : ''}`}
            onClick={() => setMode('upload')}
          >
            <Icon name="upload" size={14} />
            <span>Upload Media File</span>
            <span className="badge info sm">Real Media</span>
          </button>
          <button
            type="button"
            className={`library-mode-btn ${mode === 'sample' ? 'is-active' : ''}`}
            onClick={() => setMode('sample')}
          >
            <Icon name="wand" size={14} />
            <span>Generate Demo Media</span>
            <span className="badge sim sm">Instant Setup</span>
          </button>
        </div>
      </div>

      <div className="stack" style={{ gap: 16 }}>
        {/* Upload Dropzone (Primary) */}
        {mode === 'upload' ? (
          <div
            className={`library-upload-dropzone ${file ? 'has-file' : ''}`}
            onClick={() => fileInputRef.current?.click()}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept="video/*,.mkv,.m4v"
              style={{ display: 'none' }}
              onChange={e => handleFileChange(e.target.files?.[0] ?? null)}
            />
            <div className="library-dropzone-icon">
              <Icon name={file ? 'check' : 'upload'} size={28} />
            </div>
            {file ? (
              <div className="stack" style={{ alignItems: 'center', gap: 4 }}>
                <b className="library-dropzone-filename">{file.name}</b>
                <span className="badge ok sm">
                  {(file.size / (1024 * 1024)).toFixed(1)} MB · Ready to upload
                </span>
                <span className="faint small" style={{ marginTop: 4 }}>
                  Click to select a different media file
                </span>
              </div>
            ) : (
              <div className="stack" style={{ alignItems: 'center', gap: 4 }}>
                <b style={{ fontSize: 14 }}>Click to choose media or drag & drop</b>
                <span className="faint small">
                  Supports MP4, MOV, MKV, WebM, and M4V. Auto-converted to broadcast HLS.
                </span>
                <div className="row" style={{ gap: 6, marginTop: 6 }}>
                  <span className="chip sm">MP4</span>
                  <span className="chip sm">MOV</span>
                  <span className="chip sm">MKV</span>
                  <span className="chip sm">WebM</span>
                </div>
              </div>
            )}
          </div>
        ) : (
          /* Instant Generator Banner */
          <div className="card card-pad stack" style={{ gap: 12, background: 'var(--surface-2)' }}>
            <div className="row" style={{ gap: 8 }}>
              <Icon name="sparkle" size={15} />
              <b>Instant Simulated Media</b>
            </div>
            <div className="grid g2">
              {isAd ? (
                <label className="field">
                  <span>Ad Duration</span>
                  <div className="row" style={{ gap: 8 }}>
                    <button
                      type="button"
                      className={`btn sm ${f.minutes === 15 ? 'primary' : ''}`}
                      onClick={() => set('minutes', 15)}
                    >
                      15s Spot
                    </button>
                    <button
                      type="button"
                      className={`btn sm ${f.minutes === 30 ? 'primary' : ''}`}
                      onClick={() => set('minutes', 30)}
                    >
                      30s Spot
                    </button>
                  </div>
                </label>
              ) : (
                <label className="field">
                  <span>Duration</span>
                  <div className="row-wrap" style={{ gap: 6 }}>
                    {[1, 3, 5, 10, 15, 30].map(m => (
                      <button
                        key={m}
                        type="button"
                        className={`chip sm ${f.minutes === m ? 'active' : ''}`}
                        onClick={() => set('minutes', m)}
                      >
                        {m} min
                      </button>
                    ))}
                  </div>
                </label>
              )}

              <label className="field">
                <span>Slate & Card Color</span>
                <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                  {COLOR_PRESETS.map(c => (
                    <button
                      key={c}
                      type="button"
                      className="library-color-swatch"
                      style={{
                        background: c,
                        boxShadow: f.color === c ? `0 0 0 2px var(--surface), 0 0 0 4px ${c}` : 'none',
                      }}
                      onClick={() => set('color', c)}
                      aria-label={`Select color ${c}`}
                    />
                  ))}
                  <input
                    type="color"
                    value={f.color}
                    onChange={e => set('color', e.target.value)}
                    className="library-custom-color"
                    title="Choose custom color"
                  />
                </div>
              </label>
            </div>
          </div>
        )}

        {/* Title & Format Fields */}
        <div className="grid g2">
          <label className="field">
            <span>
              Media Title <b style={{ color: 'var(--bad)' }}>*</b>
            </span>
            <input
              type="text"
              value={f.title}
              onChange={e => set('title', e.target.value)}
              placeholder={isAd ? 'e.g. Refresh Energy Drink 30s' : 'e.g. Journey to the Stars'}
              autoFocus={mode !== 'upload'}
            />
          </label>

          <label className="field">
            <span>Content Format</span>
            <Dropdown
              options={kindOptions}
              value={f.kind}
              onChange={val => {
                set('kind', val)
                set('minutes', val === 'AD' ? 15 : 3)
              }}
              align="full"
            />
          </label>
        </div>

        {/* Genre Multi-Select */}
        {!isAd && (
          <GenreSelector
            value={f.genre}
            onChange={val => set('genre', val)}
          />
        )}

        {/* Scheduled Broadcast / Release Date & Time Component */}
        <div className="field library-datetime-field">
          <DateTimePicker
            label="Scheduled Broadcast / Release Window"
            hint="Optional linear scheduling time"
            value={releaseDateTime}
            onChange={val => setReleaseDateTime(val)}
            placeholder="Select broadcast date & time..."
            showTime={true}
            allowTimeToggle={true}
            showTimezone={true}
            fixed={true}
          />
        </div>

        {/* Description / Synopsis */}
        <label className="field">
          <span>Description / Synopsis</span>
          <textarea
            rows={2}
            value={f.description}
            onChange={e => set('description', e.target.value)}
            placeholder="Shown to viewers in the TV Guide and programme preview…"
          />
        </label>
      </div>
    </Modal>
  )
}

/** Redesigned Asset Drawer with Preview Player, Interactive Cue Timeline, and Easy Ad Insertion */
function AssetDrawer({ view, onClose, onChange }: { view: AssetView; onClose: () => void; onChange: () => void }) {
  const org = useOrg()
  const a = view.asset
  const { busy, run } = useAction()
  const videoRef = useRef<HTMLVideoElement>(null)
  const [activeTab, setActiveTab] = useState<'overview' | 'cues' | 'rights' | 'edit'>('overview')
  const [title, setTitle] = useState(a.title)
  const [description, setDescription] = useState(a.description)
  const [genre, setGenre] = useState(a.genre)
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

  const canHaveBreaks = !a.durationMs || a.durationMs >= 6000
  const maxValidCue = a.durationMs > 0 ? Math.floor((a.durationMs - 1) / 3000) * 3000 : 3600000

  // Smart Add Cue Point handler (snaps cleanly, avoids duplicates, handles segment boundaries)
  const handleAddBreak = (targetMs: number) => {
    if (a.durationMs > 0 && a.durationMs < 6000) {
      alert(`Media duration (${fmtDur(a.durationMs)}) is too short for SSAI ad breaks. A minimum of 6 seconds (2 broadcast segments) is required.`)
      return
    }
    // Snap to 3000ms boundary
    let snapped = Math.round(targetMs / 3000) * 3000
    if (snapped < 3000) snapped = 3000
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
                {/* 3-Second Segment Ticks (Visual representation of broadcast segment boundaries) */}
                <div className="library-timeline-ticks">
                  {Array.from({ length: Math.min(40, Math.floor((a.durationMs || 30000) / 3000)) }).map((_, i) => (
                    <div
                      key={i}
                      className="library-timeline-tick"
                      style={{ left: `${((i + 1) * 3000 / (a.durationMs || 1)) * 100}%` }}
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
                    Converting video to 640×360 and cutting into 3-second segments for live broadcast.
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
                    <InfoTip text="Cue points indicate where server-side ads can play during broadcast. Playout snaps cleanly to 3-second boundaries." />
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
                          Add Break at Playhead ({fmtDur(Math.max(3000, Math.min(maxValidCue, Math.round(((currentTimeSec * 1000) || 3000) / 3000) * 3000)))})
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
                          const midMs = a.durationMs ? Math.round((a.durationMs / 2) / 3000) * 3000 : 60000
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
                          const openMs = 3000
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
                          const q1Ms = Math.round((a.durationMs * 0.25) / 3000) * 3000
                          const q3Ms = Math.round((a.durationMs * 0.75) / 3000) * 3000
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
                        <span className="faint small">Snaps cleanly to 3s segment</span>
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
                            const snappedMs = Math.max(3000, Math.min(maxValidCue, Math.round(parsedMs / 3000) * 3000))
                            return (
                              <span className="library-time-snap-pill">
                                ⚡ Snaps to <b>{fmtDur(snappedMs)}</b> (Seg #{Math.round(snappedMs / 3000)})
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
                        FAST Studio linear playout streams in standardized 3-second broadcast segments. SSAI mid-roll ad insertion requires media of at least 6 seconds (minimum 2 broadcast segments) so ad breaks can splice cleanly between segment boundaries.
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
                      onClick={() => handleAddBreak(a.durationMs ? Math.round((a.durationMs / 2) / 3000) * 3000 : 60000)}
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
                    onClick={() => save({ title: title.trim(), genre: genre.trim(), description: description.trim() }, 'Video details updated')}
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
