import { useMemo, useState } from 'react'
import { api, type AssetView, type ChannelView, type Decision } from '../../shared/api'
import { useOrg } from '../../app/App'
import { AdSources } from '../../shared/components/AdSources'
import { DateTimePicker } from '../../shared/components/DateTimePicker'
import {
  ChannelLogo,
  COUNTRIES,
  Countries,
  CountryPicker,
  csv,
  Empty,
  ErrorBox,
  flag,
  fmtDate,
  fmtDayTime,
  fmtDur,
  fmtTime,
  Dropdown,
  type DropdownOption,
  Icon,
  InfoTip,
  Loading,
  Modal,
  PageHead,
  Status,
  useAction,
  useApi,
  useToast,
} from '../../shared/ui'

type Campaign = {
  id: string
  name: string
  advertiser: string
  status: string
  startsAt: string
  endsAt: string
  territories: string
  priority: number
  cpm: number
  creativeAssetId: string
  creativeTitle: string
  creativeStatus: string
  creativeDurationMs: number
  creativeColor: string
  mediaKey?: string
  impressions: number
  completes: number
  thumbUrl?: string
  audiences: string
  frequencyCap: number
}

type Simulation = {
  breakStart: string
  breakSeconds: number
  ownership: string
  programme: string
  decision: Decision
}

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

function Simulator({
  presetCountry,
  onClearPreset,
}: {
  presetCountry?: string | null
  onClearPreset?: () => void
}) {
  const org = useOrg()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const liveChannels = useMemo(
    () => channels.data?.filter(c => c.channel.publishedVersionId) ?? [],
    [channels.data]
  )

  const [channelId, setChannelId] = useState('')
  const [country, setCountry] = useState(presetCountry || 'GB')
  const [sim, setSim] = useState<Simulation | null>(null)
  const { busy, run } = useAction()

  const activeChannelId = channelId || liveChannels[0]?.channel.id || ''
  const selectedChannel = liveChannels.find(c => c.channel.id === activeChannelId)
  const d = sim?.decision

  const channelOptions = useMemo<DropdownOption[]>(() => {
    return liveChannels.map(c => ({
      value: c.channel.id,
      label: c.channel.name,
      description: `v${c.publishedVersion ?? 1} · ${c.channel.status.toLowerCase()}`,
      icon: 'tv',
      badge: c.channel.status,
      badgeType: c.channel.status === 'LIVE' ? 'live' : 'default',
    }))
  }, [liveChannels])

  const countryOptions = useMemo<DropdownOption[]>(() => {
    return Object.keys(COUNTRIES)
      .filter(c => c !== 'WW')
      .map(c => ({
        value: c,
        label: `${COUNTRIES[c]} (${c})`,
        icon: <span style={{ fontSize: 15, lineHeight: 1 }}>{flag(c)}</span>,
      }))
  }, [])

  const runDecision = () => {
    if (!activeChannelId) return
    run(() =>
      api<Simulation>(`/api/orgs/${org.slug}/ads/simulate`, {
        body: { channelId: activeChannelId, country },
      })
    ).then(r => r && setSim(r))
  }

  return (
    <div className="stack" style={{ gap: 16 }}>
      {/* Parameter Controls Bar */}
      <div
        className="card card-pad"
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr)) auto',
          gap: 12,
          alignItems: 'flex-end',
          background: 'var(--surface-2)',
        }}
      >
        <div className="field" style={{ margin: 0 }}>
          <span style={{ fontWeight: 600, fontSize: 12 }}>Target Linear Channel</span>
          <Dropdown
            options={channelOptions}
            value={activeChannelId}
            onChange={val => setChannelId(val)}
            placeholder="Select channel…"
            align="full"
          />
        </div>

        <div className="field" style={{ margin: 0 }}>
          <span style={{ fontWeight: 600, fontSize: 12 }}>Simulated Viewer Location</span>
          <Dropdown
            options={countryOptions}
            value={country}
            onChange={val => {
              setCountry(val)
              onClearPreset?.()
            }}
            searchable
            placeholder="Select country…"
            align="full"
          />
        </div>

        <button
          type="button"
          className="btn primary"
          style={{ height: 38, padding: '0 20px', minWidth: 160 }}
          disabled={busy || !activeChannelId}
          onClick={runDecision}
        >
          {busy ? (
            <>
              <span className="spinner" style={{ width: 12, height: 12 }} />
              <span>Simulating...</span>
            </>
          ) : (
            <>
              <Icon name="wand" size={15} />
              <span>Run Ad Decision</span>
            </>
          )}
        </button>
      </div>

      {/* Simulator Initial State */}
      {!sim && (
        <div className="card card-pad stack" style={{ textAlign: 'center', padding: '36px 20px', alignItems: 'center', background: 'var(--surface-2)' }}>
          <span style={{ fontSize: 36, marginBottom: 4 }}>⚡</span>
          <h3 style={{ margin: 0, fontSize: 16 }}>Ready to Run Playout Simulation</h3>
          <p className="faint small" style={{ margin: '6px 0 0', maxWidth: 520, lineHeight: 1.5 }}>
            Select a target channel and viewer country above, then click <b>Run Ad Decision</b> to test server-side ad stitching, competitive brand separation, and SCTE-35 cue payload generation.
          </p>
        </div>
      )}

      {/* Simulator Results Output */}
      {sim && d && (
        <div className="stack" style={{ gap: 16 }}>
          {/* Live Break Context Card */}
          <div className="card card-pad stack" style={{ gap: 14 }}>
            <div className="between" style={{ alignItems: 'flex-start', flexWrap: 'wrap', gap: 10 }}>
              <div>
                <span className="badge ok sm" style={{ marginBottom: 4 }}>
                  SSAI Resolution Ready
                </span>
                <h3 style={{ margin: '2px 0', fontSize: 16 }}>
                  Next Break Window: {fmtDayTime(sim.breakStart)} ({fmtTime(sim.breakStart)})
                </h3>
                <div className="faint small" style={{ marginTop: 2 }}>
                  {sim.programme} · {sim.breakSeconds}s Pod · Ownership:{' '}
                  <b style={{ color: 'var(--text)' }}>{sim.ownership}</b>{' '}
                  {sim.ownership === 'DISTRIBUTOR'
                    ? "(Platform fill break)"
                    : '(Provider-owned break — 100% our SSAI fill)'}
                </div>
              </div>

              <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                <span className="badge sm" style={{ padding: '3px 8px', fontSize: 11 }}>
                  Viewer: {flag(country)} {COUNTRIES[country]}
                </span>
                {selectedChannel && (
                  <div className="row" style={{ gap: 6, alignItems: 'center' }}>
                    <ChannelLogo
                      name={selectedChannel.channel.name}
                      color={selectedChannel.channel.color}
                      size={20}
                    />
                    <b style={{ fontSize: 12 }}>{selectedChannel.channel.name}</b>
                  </div>
                )}
              </div>
            </div>

            {/* Interactive Visual Ad Pod Timeline Bar */}
            <div>
              <div className="between faint small" style={{ fontSize: 11, marginBottom: 6 }}>
                <span>FRAME-ACCURATE AD POD TIMELINE ({sim.breakSeconds} SECONDS)</span>
                <span style={{ color: 'var(--ok)', fontWeight: 600 }}>
                  {Math.round((d.filledMs / d.breakMs) * 100)}% POD FILLED
                </span>
              </div>

              <div className="sim-pod-timeline">
                {d.slots.map((s, idx) => {
                  const slotSecs = Math.round(s.durationMs / 1000)
                  return (
                    <div
                      key={`${s.campaignId}-${idx}`}
                      className="sim-pod-slot"
                      style={{
                        flex: s.durationMs,
                        background:
                          idx === 0
                            ? 'linear-gradient(135deg, #4f46e5, #4338ca)'
                            : idx === 1
                            ? 'linear-gradient(135deg, #0284c7, #0369a1)'
                            : 'linear-gradient(135deg, #d97706, #b45309)',
                      }}
                      title={`${s.campaign} (${slotSecs}s) · Advertiser: ${s.advertiser} · CPM: $${s.cpm}`}
                    >
                      <div style={{ fontSize: 12, fontWeight: 700, lineHeight: 1.2 }}>
                        {s.campaign}
                      </div>
                      <div style={{ fontSize: 10, opacity: 0.85 }}>
                        {slotSecs}s Spot · ${Number(s.cpm).toFixed(2)} CPM
                      </div>
                    </div>
                  )
                })}

                {d.breakMs > d.filledMs && (
                  <div
                    className="sim-pod-slot slate"
                    style={{ flex: d.breakMs - d.filledMs }}
                    title={`Standby slate covers unfilled gap of ${(d.breakMs - d.filledMs) / 1000}s`}
                  >
                    <div style={{ fontSize: 12, fontWeight: 700 }}>Standby Slate</div>
                    <div style={{ fontSize: 10 }}>{(d.breakMs - d.filledMs) / 1000}s Unfilled Gap</div>
                  </div>
                )}
              </div>
            </div>

            {/* Decision Summary Banner */}
            <div className="callout ok" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <Icon name="checkCircle" size={18} />
              <div style={{ fontSize: 13, lineHeight: 1.4 }}>
                <b>Playout Resolution:</b> {d.summary}
              </div>
            </div>

            {/* SCTE-35 Cue Marker Generated */}
            <div style={{ background: '#090e1a', padding: '10px 14px', borderRadius: 8, border: '1px solid var(--border)' }}>
              <div className="between faint small" style={{ fontSize: 10.5, marginBottom: 4 }}>
                <span>DOWNSTREAM SCTE-35 SPLICE CUE PAYLOAD</span>
                <span style={{ color: '#93c5fd' }}>HLS DATERANGE SPECIFICATION</span>
              </div>
              <code style={{ fontSize: 11.5, color: '#93c5fd', wordBreak: 'break-all' }}>
                #EXT-X-DATERANGE:ID="ad-break-{sim.breakStart.replace(/[^a-zA-Z0-9]/g, '')}",START-DATE="{sim.breakStart}",PLANNED-DURATION={sim.breakSeconds}.0,SCTE35-OUT=0xFC302500000000...
              </code>
            </div>
          </div>

          {/* Detailed Decision Tables Split */}
          <div className="split">
            {/* Accepted Ad Slots Table */}
            <div className="card card-pad stack" style={{ gap: 12 }}>
              <div className="between" style={{ alignItems: 'center' }}>
                <h3 style={{ margin: 0, fontSize: 14 }}>
                  Selected Creative Slots ({d.slots.length})
                </h3>
                <span className="badge ok sm">Stitched Into HLS Ladder</span>
              </div>

              {d.slots.length === 0 ? (
                <div className="faint small" style={{ padding: 12, textAlign: 'center' }}>
                  No active sponsor ads fit this break. Standby slate will air for the full duration.
                </div>
              ) : (
                <div className="stack" style={{ gap: 8 }}>
                  {d.slots.map((s, idx) => (
                    <div
                      key={idx}
                      style={{
                        padding: '10px 12px',
                        background: 'var(--surface-2)',
                        border: '1px solid var(--border)',
                        borderRadius: 8,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 12,
                      }}
                    >
                      <div className="row" style={{ gap: 10, alignItems: 'center' }}>
                        <span
                          style={{
                            width: 24,
                            height: 24,
                            borderRadius: '50%',
                            background: 'var(--primary)',
                            color: '#fff',
                            fontSize: 11,
                            fontWeight: 700,
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                          }}
                        >
                          {idx + 1}
                        </span>
                        <div>
                          <b style={{ fontSize: 13 }}>{s.campaign}</b>
                          <div className="faint small" style={{ fontSize: 11, marginTop: 1 }}>
                            {s.advertiser} · {s.durationMs / 1000}s Creative
                          </div>
                        </div>
                      </div>

                      <div style={{ textAlign: 'right' }}>
                        <span className="badge ok sm" style={{ fontWeight: 700 }}>
                          ${Number(s.cpm).toFixed(2)} CPM
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Exclusion Analysis & Competitive Separation */}
            <div className="card card-pad stack" style={{ gap: 12 }}>
              <div className="between" style={{ alignItems: 'center' }}>
                <h3 style={{ margin: 0, fontSize: 14 }}>
                  Exclusion Analysis ({d.excluded.length})
                </h3>
                <span className="badge sm faint">ADS Rejection Reasons</span>
              </div>

              {d.excluded.length === 0 ? (
                <div className="faint small" style={{ padding: 12, textAlign: 'center' }}>
                  All eligible campaigns in this organization fit into the ad pod.
                </div>
              ) : (
                <div className="stack" style={{ gap: 8, maxHeight: 340, overflowY: 'auto' }}>
                  {d.excluded.map((x, i) => (
                    <div
                      key={i}
                      style={{
                        padding: '8px 10px',
                        borderRadius: 6,
                        background: 'rgba(239, 68, 68, 0.08)',
                        border: '1px solid rgba(239, 68, 68, 0.22)',
                        fontSize: 12,
                        display: 'flex',
                        gap: 8,
                        alignItems: 'flex-start',
                      }}
                    >
                      <span className="chip bad" style={{ padding: '0 5px', height: 18, fontSize: 10 }}>
                        ✕
                      </span>
                      <div className="grow">
                        <b>{x.campaign}</b>
                        <div className="faint small" style={{ color: '#fca5a5', marginTop: 1 }}>
                          {x.reason}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function AdArchitectureGuide() {
  return (
    <div className="stack" style={{ gap: 16 }}>
      <p className="faint small" style={{ margin: 0, lineHeight: 1.6 }}>
        FAST Studio implements broadcast-grade Server-Side Ad Insertion (SSAI). Instead of relying on client-side ad blockers or browser popups, commercials are stitched directly into the HLS video manifest before delivery.
      </p>

      <div className="grid g3" style={{ gap: 14 }}>
        <div className="card card-pad" style={{ background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <span style={{ fontSize: 20 }}>📡</span>
          <h4 style={{ margin: '8px 0 4px', fontSize: 14 }}>SCTE-35 Splice Cues</h4>
          <p className="faint small" style={{ margin: 0, lineHeight: 1.5 }}>
            The schedule planner outputs frame-accurate splice markers (Splice-Out and Splice-In). Downstream distribution platforms (Roku, Pluto, Samsung) read these cue tags to monetize platform breaks.
          </p>
        </div>

        <div className="card card-pad" style={{ background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <span style={{ fontSize: 20 }}>🛡️</span>
          <h4 style={{ margin: '8px 0 4px', fontSize: 14 }}>Competitive Separation</h4>
          <p className="faint small" style={{ margin: 0, lineHeight: 1.5 }}>
            The Ad Decision Engine prevents competing sponsors (e.g. two soda brands or two airlines) from airing in the same commercial break, satisfying strict brand safety contracts.
          </p>
        </div>

        <div className="card card-pad" style={{ background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <span style={{ fontSize: 20 }}>🎬</span>
          <h4 style={{ margin: '8px 0 4px', fontSize: 14 }}>Standby Slate Fallback</h4>
          <p className="faint small" style={{ margin: 0, lineHeight: 1.5 }}>
            If available ad inventory does not fill a break 100%, the playout engine seamlessly loops the branded station slate, guaranteeing no black screen or stream interruption.
          </p>
        </div>
      </div>
    </div>
  )
}

function CampaignModal({
  c,
  onClose,
  onDone,
}: {
  c: Campaign | null
  onClose: () => void
  onDone: () => void
}) {
  const org = useOrg()
  const toast = useToast()
  const creatives = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets?kind=AD`)
  const { busy, run } = useAction()

  const day = (d: Date) => d.toISOString().slice(0, 10)
  const [f, setF] = useState({
    name: c?.name ?? '',
    advertiser: c?.advertiser ?? '',
    creativeAssetId: c?.creativeAssetId ?? '',
    territories: c ? csv(c.territories) : ['WW'],
    startsAt: c ? c.startsAt.slice(0, 10) : day(new Date()),
    endsAt: c ? c.endsAt.slice(0, 10) : day(new Date(Date.now() + 30 * 86400000)),
    priority: c?.priority ?? 5,
    cpm: c?.cpm ?? 12,
    audiences: c?.audiences ?? '',
    frequencyCap: c?.frequencyCap ?? 0,
  })

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const creativeId = f.creativeAssetId || creatives.data?.[0]?.asset.id || ''

  // Selected creative metadata
  const selectedCreative = useMemo(() => {
    return creatives.data?.find(a => a.asset.id === creativeId)
  }, [creatives.data, creativeId])

  const creativeOptions = useMemo<DropdownOption[]>(() => {
    return (creatives.data ?? []).map(a => ({
      value: a.asset.id,
      label: a.asset.title,
      description: `${fmtDur(a.asset.durationMs)} · ${a.asset.status.toLowerCase()} encoding`,
      icon: (
        <span
          style={{
            width: 14,
            height: 14,
            borderRadius: 3,
            background: a.asset.color || 'var(--primary)',
            display: 'inline-block',
            flexShrink: 0,
          }}
        />
      ),
      badge: a.asset.status,
      badgeType: a.asset.status === 'READY' ? 'ok' : 'info',
    }))
  }, [creatives.data])

  // Flight duration preset helper
  const setFlightDays = (days: number) => {
    let start: Date
    if (f.startsAt && /^\d{4}-\d{2}-\d{2}$/.test(f.startsAt)) {
      const [y, m, d] = f.startsAt.split('-').map(Number)
      start = new Date(y, m - 1, d)
    } else {
      start = new Date()
    }
    const end = new Date(start.getTime() + days * 86400000)
    set('endsAt', day(end))
  }

  // Computed flight duration in days
  const flightDurationDays = useMemo(() => {
    if (!f.startsAt || !f.endsAt) return 0
    try {
      const [sy, sm, sd] = f.startsAt.split('-').map(Number)
      const [ey, em, ed] = f.endsAt.split('-').map(Number)
      const start = new Date(sy, sm - 1, sd).getTime()
      const end = new Date(ey, em - 1, ed).getTime()
      return Math.max(0, Math.round((end - start) / 86400000))
    } catch {
      return 0
    }
  }, [f.startsAt, f.endsAt])

  // Estimated CPM revenue projection calculator
  const est10k = (10_000 * Number(f.cpm || 0)) / 1000
  const est100k = (100_000 * Number(f.cpm || 0)) / 1000

  const save = () =>
    api(`/api/orgs/${org.slug}/campaigns${c ? '/' + c.id : ''}`, {
      method: c ? 'PUT' : 'POST',
      body: {
        ...f,
        creativeAssetId: creativeId,
        startsAt: f.startsAt + 'T00:00:00Z',
        endsAt: f.endsAt + 'T23:59:59Z',
        priority: +f.priority,
        cpm: +f.cpm,
        audiences: f.audiences.split(',').map(s => s.trim()).filter(Boolean),
        frequencyCap: Number(f.frequencyCap),
      },
    })

  return (
    <Modal
      wide
      title={c ? `Edit Campaign: ${c.name}` : 'New Advertising Campaign Flight'}
      subtitle="Book targeted sponsor campaigns with frame-accurate creative placement, geo-fencing, and priority ranking."
      onClose={onClose}
      footer={
        <div className="between" style={{ width: '100%' }}>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy || !creativeId || !f.name.trim() || !f.advertiser.trim()}
            onClick={() =>
              run(save, 'Campaign saved').then(r => {
                if (r !== undefined) {
                  toast(`Saved campaign "${f.name}"`, 'ok')
                  onDone()
                }
              })
            }
          >
            {busy ? 'Saving Campaign...' : c ? 'Update Flight' : 'Create Campaign'}
          </button>
        </div>
      }
    >
      <div className="stack" style={{ gap: 16 }}>
        {/* Basic Identity */}
        <div className="grid g2">
          <label className="field">
            <span>Campaign Title</span>
            <input
              value={f.name}
              onChange={e => set('name', e.target.value)}
              placeholder="e.g. Summer Sparkle Launch 2026"
              className="input sm"
            />
          </label>
          <label className="field">
            <span>Advertiser / Sponsor</span>
            <input
              value={f.advertiser}
              onChange={e => set('advertiser', e.target.value)}
              placeholder="e.g. Acme Beverages Inc."
              className="input sm"
            />
          </label>
        </div>

        <div className="grid g2">
          <label className="field"><span>Viewer audiences (optional)</span><input value={f.audiences} onChange={e => set('audiences', e.target.value)} placeholder="SPORTS_FANS, RETURNING_VIEWERS" /><span className="faint small">Match any listed key from signed viewer context. Leave blank for contextual delivery.</span></label>
          <label className="field"><span>Maximum selections per viewer per hour</span><input type="number" min={0} max={100} value={f.frequencyCap} onChange={e => set('frequencyCap', Number(e.target.value))} /><span className="faint small">0 means unlimited. Reservations prevent concurrent over-serving; only confirmed impressions count as delivered. Without identity the cap applies to the session.</span></label>
        </div>
        {/* Video Creative Selector with Visual Preview */}
        <div>
          <div className="field">
            <div className="between" style={{ width: '100%', marginBottom: 4 }}>
              <span className="field-label" style={{ margin: 0, fontWeight: 600, fontSize: 13 }}>
                Video Creative Asset
              </span>
              <span className="hint" style={{ fontSize: 11 }}>
                New video files can be ingested via the Media Library
              </span>
            </div>
            <Dropdown
              options={creativeOptions}
              value={creativeId}
              onChange={val => set('creativeAssetId', val)}
              searchable
              placeholder="Select ingested creative video asset…"
              align="full"
            />
          </div>

          {selectedCreative && (
            <div
              style={{
                marginTop: 8,
                padding: '10px 14px',
                background: 'var(--surface-2)',
                borderRadius: 8,
                border: '1px solid var(--border)',
                display: 'flex',
                alignItems: 'center',
                gap: 12,
              }}
            >
              <div
                style={{
                  width: 50,
                  height: 32,
                  borderRadius: 4,
                  background: selectedCreative.asset.color || '#312e81',
                  display: 'grid',
                  placeItems: 'center',
                  color: '#fff',
                  flexShrink: 0,
                }}
              >
                <Icon name="film" size={16} />
              </div>
              <div className="grow">
                <b style={{ fontSize: 13 }}>{selectedCreative.asset.title}</b>
                <div className="faint small" style={{ fontSize: 11 }}>
                  Duration: {fmtDur(selectedCreative.asset.durationMs)} · Encoding:{' '}
                  <span className="badge ok sm" style={{ padding: '0 4px', fontSize: 9.5 }}>
                    {selectedCreative.asset.status}
                  </span>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Target Territories */}
        <div className="field">
          <span className="row" style={{ gap: 6, marginBottom: 4 }}>
            <span>Target Territories</span>
            <InfoTip text="The ad decision engine only places this campaign for viewers connecting from these territories. Select 'Worldwide' for universal delivery." />
          </span>
          <CountryPicker
            value={f.territories}
            onChange={v => set('territories', v)}
            allowWorldwide
          />
        </div>

        {/* Flight Schedule Window */}
        <div>
          <div className="between" style={{ alignItems: 'center', marginBottom: 8, flexWrap: 'wrap', gap: 8 }}>
            <div className="row" style={{ gap: 6, alignItems: 'center' }}>
              <span className="field-label" style={{ margin: 0, fontSize: 12, fontWeight: 600 }}>
                Flight Schedule Window
              </span>
              {flightDurationDays > 0 && (
                <span className="badge sm ok" style={{ fontSize: 10.5, padding: '1px 6px' }}>
                  {flightDurationDays} {flightDurationDays === 1 ? 'day' : 'days'} duration
                </span>
              )}
            </div>
            <div className="row" style={{ gap: 4, alignItems: 'center' }}>
              <span className="faint small" style={{ fontSize: 10.5 }}>Presets:</span>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(7)}
              >
                7d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(14)}
              >
                14d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(30)}
              >
                30d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(90)}
              >
                90d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(365)}
              >
                1y
              </button>
            </div>
          </div>

          <div className="grid g2" style={{ gap: 14 }}>
            <DateTimePicker
              label="Flight Start Date"
              value={f.startsAt}
              onChange={val => {
                const s = val ? val.slice(0, 10) : ''
                set('startsAt', s)
                if (f.endsAt && s && f.endsAt < s) {
                  set('endsAt', s)
                }
              }}
              showTime={false}
              allowTimeToggle={false}
              showTimezone={false}
              fixed={false}
              placeholder="Select flight start date..."
            />
            <DateTimePicker
              label="Flight End Date"
              value={f.endsAt}
              onChange={val => {
                const e = val ? val.slice(0, 10) : ''
                set('endsAt', e)
              }}
              showTime={false}
              allowTimeToggle={false}
              showTimezone={false}
              fixed={false}
              placeholder="Select flight end date..."
            />
          </div>
        </div>

        {/* Priority & Monetization */}
        <div className="grid g2" style={{ alignItems: 'flex-start' }}>
          <label className="field">
            <span className="row" style={{ gap: 6 }}>
              <span>Playout Priority (1 - 10)</span>
              <InfoTip text="Higher priority campaigns are placed first in each break. Direct brand sponsor flights usually sit at 8-10. House promos sit at 1-3 to fill remaining time." />
            </span>
            <input
              type="number"
              min={1}
              max={10}
              value={f.priority}
              onChange={e => set('priority', e.target.value)}
              className="input sm"
            />
            <span className="faint small" style={{ fontSize: 10.5 }}>
              {Number(f.priority) >= 8
                ? '⭐ Tier 1: Premium Direct Sponsor'
                : Number(f.priority) >= 5
                ? '⚡ Tier 2: Programmatic Network Fill'
                : '🛡️ Tier 3: House Promo & Fallback'}
            </span>
          </label>

          <label className="field">
            <span className="row" style={{ gap: 6 }}>
              <span>CPM Price ($)</span>
              <InfoTip text="Cost Per Mille: gross price paid per 1,000 views. Used to estimate revenue and prioritize inventory." />
            </span>
            <input
              type="number"
              min={0}
              step={0.5}
              value={f.cpm}
              onChange={e => set('cpm', e.target.value)}
              className="input sm"
            />
            <span className="faint small" style={{ fontSize: 10.5 }}>
              Est. Value: 10k views = <b>${est10k.toFixed(2)}</b> · 100k views ={' '}
              <b>${est100k.toFixed(2)}</b>
            </span>
          </label>
        </div>
      </div>
    </Modal>
  )
}
