import { useMemo, useState } from 'react'
import { api, type ChannelView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  ChannelLogo,
  COUNTRIES,
  flag,
  fmtDayTime,
  fmtTime,
  Dropdown,
  type DropdownOption,
  Icon,
  useAction,
  useApi,
} from '../../shared/ui'
import type { Simulation } from './adTypes'

export function Simulator({
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

export function AdArchitectureGuide() {
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
