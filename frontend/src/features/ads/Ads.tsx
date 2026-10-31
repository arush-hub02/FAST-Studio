import { useMemo, useState } from 'react'
import { api } from '../../shared/api'
import { useOrg } from '../../app/App'
import { AdSources } from '../../shared/components/AdSources'
import {
  Countries,
  csv,
  Empty,
  ErrorBox,
  fmtDate,
  fmtDur,
  Dropdown,
  type DropdownOption,
  Icon,
  Loading,
  Modal,
  PageHead,
  Status,
  useAction,
  useApi,
  useToast,
} from '../../shared/ui'
import { Simulator, AdArchitectureGuide } from './AdSimulator'
import { CampaignModal } from './CampaignModal'
import type { Campaign } from './adTypes'

const SORT_OPTIONS: DropdownOption[] = [
  { value: 'priority', label: 'Priority (High to Low)', icon: 'shield', description: 'Tier 1 sponsor flights first' },
  { value: 'cpm', label: 'Highest CPM ($)', icon: 'chart', description: 'Rank by revenue value' },
  { value: 'impressions', label: 'Most Impressions', icon: 'users', description: 'Rank by delivery volume' },
  { value: 'endsAt', label: 'Ending Soonest', icon: 'calendar', description: 'Expiring campaigns first' },
]

export default function Ads() {
  const org = useOrg()
  const toast = useToast()
  const list = useApi<Campaign[]>(`/api/orgs/${org.slug}/campaigns`, [], 15000)

  const [showSimulator, setShowSimulator] = useState(false)
  const [showGuide, setShowGuide] = useState(false)
  const [editing, setEditing] = useState<Campaign | 'new' | null>(null)
  const { busy, run } = useAction()

  // Filter & Search Controls
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'PAUSED' | 'ENDED'>('ALL')
  const [sortBy, setSortBy] = useState<'priority' | 'cpm' | 'impressions' | 'endsAt'>('priority')
  const [viewMode, setViewMode] = useState<'cards' | 'table'>('cards')

  // Simulator preset target (when jumping from a campaign card)
  const [simPresetCountry, setSimPresetCountry] = useState<string | null>(null)

  // Status toggle handler
  const setStatus = (c: Campaign, status: string) =>
    run(
      () => api(`/api/orgs/${org.slug}/campaigns/${c.id}/status`, { body: { status } }),
      `${c.name}: ${status.toLowerCase()}`
    ).then(() => {
      list.reload()
      toast(`Campaign "${c.name}" marked as ${status.toLowerCase()}`, 'ok')
    })

  // KPI calculations
  const totalCampaigns = list.data?.length ?? 0
  const activeCount = list.data?.filter(c => c.status === 'ACTIVE').length ?? 0
  const pausedCount = list.data?.filter(c => c.status === 'PAUSED').length ?? 0
  const totalImpressions = list.data?.reduce((s, c) => s + c.impressions, 0) ?? 0
  const totalCompletes = list.data?.reduce((s, c) => s + c.completes, 0) ?? 0
  const completionRate = totalImpressions > 0 ? Math.round((totalCompletes / totalImpressions) * 100) : 0
  const estTotalRevenue =
    list.data?.reduce((s, c) => s + (c.impressions * Number(c.cpm)) / 1000, 0) ?? 0

  // Filtered & Sorted campaigns
  const filteredCampaigns = useMemo(() => {
    if (!list.data) return []
    const q = searchQuery.toLowerCase().trim()
    let res = list.data.filter(c => {
      const matchSearch =
        !q ||
        c.name.toLowerCase().includes(q) ||
        c.advertiser.toLowerCase().includes(q) ||
        c.creativeTitle?.toLowerCase().includes(q)
      const matchStatus = statusFilter === 'ALL' || c.status === statusFilter
      return matchSearch && matchStatus
    })

    res = res.slice().sort((a, b) => {
      if (sortBy === 'priority') return b.priority - a.priority
      if (sortBy === 'cpm') return Number(b.cpm) - Number(a.cpm)
      if (sortBy === 'impressions') return b.impressions - a.impressions
      if (sortBy === 'endsAt') return new Date(a.endsAt).getTime() - new Date(b.endsAt).getTime()
      return 0
    })

    return res
  }, [list.data, searchQuery, statusFilter, sortBy])

  return (
    <div className="page" style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Page Header */}
      <PageHead
        title="Advertising & Monetization"
        subtitle="Manage dynamic ad campaigns, sponsor flights, CPM pricing, and live linear TV inventory delivery."
        tip="During linear ad breaks, the Ad Decision Server (ADS) matches viewer territory, priority tiers, and flight dates to inject targeted sponsor creatives."
        actions={
          <div className="row" style={{ gap: 8, alignItems: 'center' }}>
            <button
              type="button"
              className="btn ghost icon-btn"
              title="Refresh campaign delivery stats"
              onClick={() => {
                list.reload()
                toast('Refreshed campaign delivery data', 'ok')
              }}
            >
              <Icon name="refresh" size={15} />
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => {
                setSimPresetCountry(null)
                setShowSimulator(true)
              }}
              title="Open Broadcast SSAI Decision Simulator (Demo tool)"
            >
              <Icon name="wand" size={15} />
              <span>Simulate SSAI</span>
              <span className="badge sm live" style={{ marginLeft: 4, padding: '1px 5px', fontSize: 9.5 }}>
                Demo
              </span>
            </button>
            <button
              type="button"
              className="btn ghost"
              onClick={() => setShowGuide(true)}
              title="View SSAI Architecture & SCTE-35 Specification"
            >
              <Icon name="info" size={15} />
              <span>SCTE-35 Specs</span>
            </button>
            {org.can('ADS_EDIT') && (
              <button
                type="button"
                className="btn primary"
                onClick={() => setEditing('new')}
              >
                <Icon name="plus" size={15} />
                <span>New Campaign</span>
              </button>
            )}
          </div>
        }
      />

      {/* Top 4-KPI Metric Cards (Harmonized with Channels, Library, Schedule, Guide, Watch) */}
      <div className="grid g4 library-kpi-grid">
        <div className="card library-kpi-card color-primary">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-primary">
              <Icon name="megaphone" size={18} />
            </span>
            <span className="badge primary sm">SSAI Targeted</span>
          </div>
          <div>
            <div className="library-kpi-val">
              {activeCount}{' '}
              <span className="faint" style={{ fontSize: 15, fontWeight: 500 }}>
                / {totalCampaigns}
              </span>
            </div>
            <div className="library-kpi-label">Active Ad Campaigns</div>
            <div className="library-kpi-sub faint small">
              {pausedCount > 0 ? `${pausedCount} paused · ` : ''}Monetizing live linear channels
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-emerald">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-emerald">
              <Icon name="activity" size={18} />
            </span>
            <span className="badge ok sm">Live Delivered</span>
          </div>
          <div>
            <div className="library-kpi-val">{totalImpressions.toLocaleString()}</div>
            <div className="library-kpi-label">Total Ad Impressions</div>
            <div className="library-kpi-sub faint small">First segment delivered to player sessions</div>
          </div>
        </div>

        <div className="card library-kpi-card color-cyan">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-cyan">
              <Icon name="checkCircle" size={18} />
            </span>
            <span className="badge info sm">{completionRate}% VCR</span>
          </div>
          <div>
            <div className="library-kpi-val">{totalCompletes.toLocaleString()}</div>
            <div className="library-kpi-label">Completed Views</div>
            <div className="library-kpi-sub faint small">Full creative completion rate (VCR)</div>
          </div>
        </div>

        <div className="card library-kpi-card color-purple">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-purple">
              <Icon name="dollarSign" size={18} />
            </span>
            <span className="badge sim sm">eCPM Revenue</span>
          </div>
          <div>
            <div className="library-kpi-val">${estTotalRevenue.toFixed(2)}</div>
            <div className="library-kpi-label">Estimated Ad Value</div>
            <div className="library-kpi-sub faint small">Shown impressions × campaign CPM ÷ 1,000</div>
          </div>
        </div>
      </div>

      {/* Campaign Controls & Filter Bar */}
      <div
        className="card card-pad"
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 12,
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        {/* Quick Search */}
        <div style={{ position: 'relative', minWidth: 260, flex: 1 }}>
          <span style={{ position: 'absolute', left: 10, top: 10, color: 'var(--text-3)' }}>
            <Icon name="search" size={15} />
          </span>
          <input
            type="text"
            className="input sm"
            style={{ paddingLeft: 32, width: '100%' }}
            placeholder="Search campaigns, advertisers, or creatives..."
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

        {/* Status Filter Chips */}
        <div className="row" style={{ gap: 4 }}>
          {(['ALL', 'ACTIVE', 'PAUSED'] as const).map(st => (
            <button
              key={st}
              type="button"
              className={`chip ${statusFilter === st ? 'active' : ''}`}
              style={{ cursor: 'pointer', height: 30, fontSize: 11.5 }}
              onClick={() => setStatusFilter(st)}
            >
              {st === 'ALL'
                ? `All (${totalCampaigns})`
                : st === 'ACTIVE'
                ? `Active (${activeCount})`
                : `Paused (${pausedCount})`}
            </button>
          ))}
        </div>

        {/* Sorting Dropdown & View Mode Switcher */}
        <div className="row" style={{ gap: 10, alignItems: 'center' }}>
          <Dropdown
            options={SORT_OPTIONS}
            value={sortBy}
            onChange={val => setSortBy(val as any)}
            triggerPrefix={<span className="faint small" style={{ fontWeight: 600 }}>Sort:</span>}
            style={{ minWidth: 210 }}
          />

          <div className="row" style={{ gap: 4 }}>
            <button
              type="button"
              className={`btn sm ${viewMode === 'cards' ? 'primary' : 'ghost'}`}
              style={{ height: 32, padding: '0 10px', fontSize: 12 }}
              onClick={() => setViewMode('cards')}
              title="Cards View"
            >
              <Icon name="grid" size={14} />
              <span>Cards</span>
            </button>
            <button
              type="button"
              className={`btn sm ${viewMode === 'table' ? 'primary' : 'ghost'}`}
              style={{ height: 32, padding: '0 10px', fontSize: 12 }}
              onClick={() => setViewMode('table')}
              title="Table View"
            >
              <Icon name="list" size={14} />
              <span>Table</span>
            </button>
          </div>
        </div>
      </div>

          {/* List Display */}
          {list.error ? (
            <ErrorBox error={list.error} />
          ) : !list.data ? (
            <Loading />
          ) : filteredCampaigns.length === 0 ? (
            <div className="card" style={{ padding: 48 }}>
              <Empty
                icon="megaphone"
                title={searchQuery || statusFilter !== 'ALL' ? 'No Matching Campaigns' : 'No Campaigns Yet'}
                text={
                  searchQuery || statusFilter !== 'ALL'
                    ? 'No campaigns match your active search and filter criteria.'
                    : 'Create your first advertising campaign to begin monetizing linear TV ad breaks.'
                }
                action={
                  searchQuery || statusFilter !== 'ALL' ? (
                    <button
                      type="button"
                      className="btn"
                      onClick={() => {
                        setSearchQuery('')
                        setStatusFilter('ALL')
                      }}
                    >
                      Clear Filters
                    </button>
                  ) : org.can('ADS_EDIT') ? (
                    <button type="button" className="btn primary" onClick={() => setEditing('new')}>
                      <Icon name="plus" size={14} />
                      <span>New Campaign</span>
                    </button>
                  ) : undefined
                }
              />
            </div>
          ) : viewMode === 'cards' ? (
            /* Cards View */
            <div className="stack" style={{ gap: 12 }}>
              {filteredCampaigns.map(c => {
                const nowMs = Date.now()
                const startMs = new Date(c.startsAt).getTime()
                const endMs = new Date(c.endsAt).getTime()
                const totalFlight = Math.max(1, endMs - startMs)
                const elapsedFlight = Math.min(totalFlight, Math.max(0, nowMs - startMs))
                const flightPercent = Math.round((elapsedFlight / totalFlight) * 100)
                const daysRemaining = Math.max(0, Math.ceil((endMs - nowMs) / (1000 * 60 * 60 * 24)))

                const vcr = c.impressions > 0 ? Math.round((c.completes / c.impressions) * 100) : 0
                const estValue = (c.impressions * Number(c.cpm)) / 1000

                // Priority tier determination
                const isHighTier = c.priority >= 8
                const isMidTier = c.priority >= 5 && c.priority < 8

                return (
                  <section
                    key={c.id}
                    className={`campaign-card status-${c.status.toLowerCase()}`}
                  >
                    {/* Left Col: Creative Poster Thumbnail */}
                    <div
                      className="campaign-thumb-box"
                      style={{ background: c.creativeColor || '#1e1b4b' }}
                    >
                      {c.thumbUrl && c.creativeStatus === 'READY' ? (
                        <img src={c.thumbUrl} alt={c.creativeTitle || c.name} />
                      ) : (
                        <div style={{ textAlign: 'center', padding: 8 }}>
                          <Icon name="megaphone" size={32} />
                          <div style={{ fontSize: 10, marginTop: 4, opacity: 0.8 }}>
                            {c.creativeTitle || 'Ad Creative'}
                          </div>
                        </div>
                      )}
                      <span className="campaign-thumb-dur">
                        {fmtDur(c.creativeDurationMs)}
                      </span>
                    </div>

                    {/* Middle Col: Campaign Specs & Delivery */}
                    <div className="stack" style={{ gap: 8, overflow: 'hidden' }}>
                      <div className="between" style={{ alignItems: 'flex-start', gap: 8 }}>
                        <div>
                          <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                            <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: 'var(--text)' }}>
                              {c.name}
                            </h3>
                            <span
                              className={`campaign-priority-badge ${
                                isHighTier ? 'tier-high' : isMidTier ? 'tier-mid' : 'tier-low'
                              }`}
                              title={`Priority ${c.priority} of 10. Placed ahead of lower priorities in each ad break.`}
                            >
                              <span>⚡</span>
                              <span>P{c.priority}</span>
                            </span>
                          </div>
                          <div className="faint small" style={{ marginTop: 2, display: 'flex', alignItems: 'center', gap: 6 }}>
                            <span>{c.advertiser}</span>
                            <span>·</span>
                            <span>Creative: <b>{c.creativeTitle || 'Standard Video'}</b></span>
                          </div>
                        </div>

                        <Status value={c.status} />
                      </div>

                      {/* Targeting & Pricing Chips */}
                      <div className="row-wrap small" style={{ gap: 6, alignItems: 'center' }}>
                        <span className="badge sm ok" style={{ fontWeight: 700, fontSize: 11 }}>
                          CPM ${Number(c.cpm).toFixed(2)}
                        </span>
                        <Countries codes={csv(c.territories)} />
                        <span className="faint" style={{ fontSize: 11 }}>
                          {fmtDate(c.startsAt)} → {fmtDate(c.endsAt)} ({daysRemaining}d left)
                        </span>
                      </div>

                      {/* Flight Timeline Progress Bar */}
                      <div>
                        <div className="between faint small" style={{ fontSize: 10, marginBottom: 2 }}>
                          <span>Flight Timeline ({flightPercent}% completed)</span>
                          <span>{daysRemaining > 0 ? `${daysRemaining} days remaining` : 'Flight concluded'}</span>
                        </div>
                        <div className="flight-progress-wrap">
                          <div
                            className="flight-progress-fill"
                            style={{
                              width: `${flightPercent}%`,
                              background:
                                c.status === 'ACTIVE'
                                  ? 'linear-gradient(90deg, var(--primary), var(--ok))'
                                  : 'var(--text-3)',
                            }}
                          />
                        </div>
                      </div>

                      {/* Delivery Stats & Impressions */}
                      <div
                        className="between small"
                        style={{
                          background: 'var(--surface-2)',
                          padding: '6px 10px',
                          borderRadius: 6,
                          fontSize: 11.5,
                        }}
                      >
                        <div className="row" style={{ gap: 12 }}>
                          <span>
                            Delivered: <b>{c.impressions.toLocaleString()}</b> views
                          </span>
                          <span>
                            Completed: <b>{c.completes.toLocaleString()}</b> ({vcr}% VCR)
                          </span>
                        </div>
                        <div>
                          Est. Value: <b style={{ color: 'var(--ok)' }}>${estValue.toFixed(2)}</b>
                        </div>
                      </div>
                    </div>

                    {/* Right Col: Quick Actions */}
                    <div
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 6,
                        justifyContent: 'center',
                        borderLeft: '1px solid var(--border)',
                        paddingLeft: 16,
                      }}
                    >
                      {org.can('ADS_EDIT') && (
                        <>
                          {c.status === 'ACTIVE' && (
                            <button
                              type="button"
                              className="btn sm"
                              disabled={busy}
                              style={{ width: 100 }}
                              onClick={() => setStatus(c, 'PAUSED')}
                              title="Pause this campaign from airing in live breaks"
                            >
                              <Icon name="pause" size={13} />
                              <span>Pause</span>
                            </button>
                          )}
                          {c.status === 'PAUSED' && (
                            <button
                              type="button"
                              className="btn sm primary"
                              disabled={busy}
                              style={{ width: 100 }}
                              onClick={() => setStatus(c, 'ACTIVE')}
                              title="Resume campaign playback in live breaks"
                            >
                              <Icon name="play" size={13} />
                              <span>Resume</span>
                            </button>
                          )}
                          <button
                            type="button"
                            className="btn sm ghost"
                            style={{ width: 100 }}
                            onClick={() => setEditing(c)}
                          >
                            <span>Edit Flight</span>
                          </button>
                        </>
                      )}

                      <button
                        type="button"
                        className="btn sm ghost"
                        style={{ width: 100, fontSize: 11 }}
                        title="Simulate ad decision with this campaign's target country"
                        onClick={() => {
                          const targetCountry = csv(c.territories)[0] || 'GB'
                          setSimPresetCountry(targetCountry === 'WW' ? 'GB' : targetCountry)
                          setShowSimulator(true)
                          toast(`Loaded ${c.name} targeting into SSAI simulator`, 'ok')
                        }}
                      >
                        <Icon name="wand" size={12} />
                        <span>Simulate</span>
                      </button>
                    </div>
                  </section>
                )
              })}
            </div>
          ) : (
            /* Dense Table View */
            <div className="card" style={{ overflowX: 'auto' }}>
              <table className="table" style={{ width: '100%', fontSize: 12.5 }}>
                <thead>
                  <tr>
                    <th>Campaign & Creative</th>
                    <th>Advertiser</th>
                    <th>Status</th>
                    <th>Priority</th>
                    <th>CPM Rate</th>
                    <th>Territories</th>
                    <th>Flight Window</th>
                    <th>Impressions</th>
                    <th>Completes</th>
                    <th>Est. Revenue</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredCampaigns.map(c => {
                    const estValue = (c.impressions * Number(c.cpm)) / 1000
                    return (
                      <tr key={c.id}>
                        <td>
                          <b>{c.name}</b>
                          <div className="faint small">
                            {c.creativeTitle} ({fmtDur(c.creativeDurationMs)})
                          </div>
                        </td>
                        <td>{c.advertiser}</td>
                        <td>
                          <Status value={c.status} />
                        </td>
                        <td>
                          <span
                            className={`campaign-priority-badge ${
                              c.priority >= 8 ? 'tier-high' : c.priority >= 5 ? 'tier-mid' : 'tier-low'
                            }`}
                          >
                            P{c.priority}
                          </span>
                        </td>
                        <td>
                          <b>${Number(c.cpm).toFixed(2)}</b>
                        </td>
                        <td>
                          <Countries codes={csv(c.territories)} />
                        </td>
                        <td className="faint small">
                          {fmtDate(c.startsAt)} → {fmtDate(c.endsAt)}
                        </td>
                        <td>{c.impressions.toLocaleString()}</td>
                        <td>{c.completes.toLocaleString()}</td>
                        <td>
                          <b style={{ color: 'var(--ok)' }}>${estValue.toFixed(2)}</b>
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <div className="row" style={{ gap: 4, justifyContent: 'flex-end' }}>
                            {c.status === 'ACTIVE' && (
                              <button
                                type="button"
                                className="btn sm icon-btn"
                                onClick={() => setStatus(c, 'PAUSED')}
                                title="Pause"
                              >
                                <Icon name="pause" size={13} />
                              </button>
                            )}
                            {c.status === 'PAUSED' && (
                              <button
                                type="button"
                                className="btn sm icon-btn primary"
                                onClick={() => setStatus(c, 'ACTIVE')}
                                title="Resume"
                              >
                                <Icon name="play" size={13} />
                              </button>
                            )}
                            <button
                              type="button"
                              className="btn sm icon-btn ghost"
                              onClick={() => {
                                const targetCountry = csv(c.territories)[0] || 'GB'
                                setSimPresetCountry(targetCountry === 'WW' ? 'GB' : targetCountry)
                                setShowSimulator(true)
                                toast(`Loaded ${c.name} targeting into SSAI simulator`, 'ok')
                              }}
                              title="Simulate SSAI Decision"
                            >
                              <Icon name="wand" size={13} />
                            </button>
                            <button
                              type="button"
                              className="btn sm icon-btn ghost"
                              onClick={() => setEditing(c)}
                              title="Edit"
                            >
                              <Icon name="sliders" size={13} />
                            </button>
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}

      {/* Campaign Create / Edit Modal */}
      {editing && (
        <CampaignModal
          c={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onDone={() => {
            setEditing(null)
            list.reload()
          }}
        />
      )}

      {(org.features.includes('AD_WATERFALL_VIEW') || org.features.includes('AD_OWN_TAGS')) && (
        <AdSources base={`/api/orgs/${org.slug}/ad-sources`} mode="company" canAddOwn={org.features.includes('AD_OWN_TAGS') && org.can('ADS_EDIT')} />
      )}

      {/* Broadcast SSAI Decision Simulator Modal */}
      {showSimulator && (
        <Modal
          title="Broadcast SSAI Decision Simulator"
          subtitle="Simulate real-time linear ad break stitching, viewer geo-targeting, competitive brand separation, and SCTE-35 manifest cue generation."
          wide
          size="lg"
          onClose={() => {
            setShowSimulator(false)
            setSimPresetCountry(null)
          }}
        >
          <Simulator
            presetCountry={simPresetCountry}
            onClearPreset={() => setSimPresetCountry(null)}
          />
        </Modal>
      )}

      {/* SSAI Architecture & Specification Modal */}
      {showGuide && (
        <Modal
          title="SSAI Architecture & SCTE-35 Specification"
          subtitle="Playout SCTE-35 cue injection, dynamic manifest rewriting, and ad pod rules."
          wide
          size="lg"
          onClose={() => setShowGuide(false)}
        >
          <AdArchitectureGuide />
        </Modal>
      )}
    </div>
  )
}
