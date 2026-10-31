import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { AssetView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Dropdown,
  type DropdownOption,
  Empty,
  ErrorBox,
  fmtDur,
  Icon,
  Loading,
  PageHead,
  Status,
  useApi,
} from '../../shared/ui'
import { AddVideoModal } from './AddVideoModal'
import { AssetDrawer } from './AssetDrawer'
import { formatMediaKind, KIND_LABEL } from './mediaKind'

const KINDS = [
  { key: '', label: 'All Catalog', icon: 'film' },
  { key: 'MOVIE', label: 'Movies', icon: 'film' },
  { key: 'EPISODE', label: 'Episodes', icon: 'tv' },
  { key: 'SHORT', label: 'Shorts', icon: 'sliders' },
  { key: 'AD', label: 'Ad Creatives', icon: 'megaphone' },
] as const

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
          (a.asset.series && a.asset.series.toLowerCase().includes(query)) ||
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
