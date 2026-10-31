import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, type ChannelView, type Decision, type Programme, type RightsDecision } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  ChannelLogo,
  COUNTRIES,
  Dropdown,
  type DropdownOption,
  Empty,
  flag,
  fmtDayTime,
  fmtDur,
  fmtTime,
  Icon,
  InfoTip,
  Loading,
  PageHead,
  Sim,
  useApi,
  useToast,
  VideoPlayer,
  type PlayerEvent,
} from '../../shared/ui'

type Destination = { id: string; name: string; kind: string; adMode: string; markerFormat: string; maxAdSecondsPerHour: number; notes: string }
type Started = { sessionId: string; playlistUrl: string; adMode: string; markerFormat: string; destination: string; country: string; rightsNow?: RightsDecision }
type BreakInfo = { id: string; start: string; end: string; ownership: string; filledByUs: boolean; scteOut: string; decision?: Decision }
type Info = {
  channel: string; adMode: string; markerFormat: string; country: string; now?: Programme; next?: Programme; currentBreak?: BreakInfo
  upcomingBreaks: BreakInfo[]; rightsNow?: RightsDecision; events: { event: string; campaign: string; seconds: number; occurredAt: string }[]; serverTime: string
}

const MODE_TEXT: Record<string, { title: string; subtitle: string; body: string; badge: string }> = {
  SSAI: {
    title: 'Server-Side Ad Insertion (SSAI)',
    subtitle: 'Dynamic Manifest Stitching',
    body: 'Our server stitches targeted commercial spots directly into this viewer’s personalized HLS media playlist. The video player treats ads as continuous linear content without client interruptions. Every segment request is validated and credited as an ad impression.',
    badge: 'Stitched Stream',
  },
  SGAI: {
    title: 'Server-Guided Ad Insertion (SGAI)',
    subtitle: 'HLS Interstitial Architecture',
    body: 'All viewers receive the identical pristine master playlist. At ad breaks, the playlist introduces an HLS interstitial directive. The client player independently fetches the targeted sponsor asset list, executes client-side playback, and seamlessly resumes linear broadcast playout.',
    badge: 'Client Interstitial',
  },
  PASSTHROUGH: {
    title: 'Marker Passthrough (Distributor Fill)',
    subtitle: 'SCTE-35 / CUE Splice Signals',
    body: 'Raw linear broadcast feed delivered to third-party MVPDs and smart TV platforms (Roku, Samsung TV Plus, Pluto TV). Ad breaks are signaled using standard SCTE-35 markers. The distributor injects commercial inventory; during local windows, our feed transmits standby slate.',
    badge: 'SCTE-35 CUE',
  },
}

const EVENT: Record<string, { label: string; badge: string }> = {
  IMPRESSION: { label: 'Impression Started', badge: 'ok' },
  FIRST_QUARTILE: { label: '25% Viewed', badge: 'info' },
  MIDPOINT: { label: '50% Midpoint', badge: 'info' },
  THIRD_QUARTILE: { label: '75% Viewed', badge: 'info' },
  COMPLETE: { label: 'Completed (100%)', badge: 'ok' },
  SLATE: { label: 'Slate Standby', badge: 'warn' },
}

export default function Watch() {
  const org = useOrg()
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const dests = useApi<{ destinations: Destination[] }>(`/api/orgs/${org.slug}/destinations`)
  const live = useMemo(() => channels.data?.filter(c => c.channel.status === 'LIVE') ?? [], [channels.data])
  const slug = params.get('channel') ?? live[0]?.channel.slug ?? ''
  const channel = useMemo(() => live.find(c => c.channel.slug === slug), [live, slug])
  
  const [destId, setDestId] = useState('')
  const [country, setCountry] = useState('IN')
  const [viewerToken, setViewerToken] = useState('')
  const [viewerInput, setViewerInput] = useState('')
  const [session, setSession] = useState<Started | null>(null)
  const [info, setInfo] = useState<Info | null>(null)
  const [adNow, setAdNow] = useState<string | null>(null)
  const [tab, setTab] = useState<'what' | 'playlist' | 'health'>('what')
  const [copiedUrl, setCopiedUrl] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  const sgaiDecisions = useRef<Map<string, { breakId: string; campaignId: string; campaign: string }>>(new Map())

  // Channel dropdown options
  const channelOptions: DropdownOption[] = useMemo(() => live.map(c => ({
    value: c.channel.slug,
    label: c.channel.name,
    icon: 'tv',
    badge: 'LIVE',
    badgeType: 'live' as const,
    description: `${c.channel.timezone} · v${c.publishedVersion ?? 1}`,
  })), [live])

  // Country dropdown options
  const countryOptions: DropdownOption[] = useMemo(() => Object.keys(COUNTRIES).filter(c => c !== 'WW').map(c => ({
    value: c,
    label: `${flag(c)} ${COUNTRIES[c]} (${c})`,
  })), [])

  // Destination dropdown options
  const destOptions: DropdownOption[] = useMemo(() => (dests.data?.destinations ?? []).map(d => ({
    value: d.id,
    label: d.name,
    icon: 'cast',
    badge: d.adMode,
    badgeType: d.adMode === 'SSAI' ? 'ok' : d.adMode === 'SGAI' ? 'info' : 'warn',
    description: `${d.kind} · SCTE: ${d.markerFormat}`,
  })), [dests.data])

  useEffect(() => {
    if (!destId && dests.data?.destinations.length) {
      setDestId(dests.data.destinations.find(d => d.kind === 'OWNED_WEB' && d.adMode === 'SSAI')?.id ?? dests.data.destinations[0].id)
    }
  }, [dests.data, destId])

  // Initialize playback session
  const start = useCallback(async () => {
    if (!channel || !destId) return
    setSession(null)
    setInfo(null)
    try {
      const s = await api<Started>('/play/sessions', {
        body: { org: org.slug, channel: channel.channel.slug, destinationId: destId, country, viewerToken: viewerToken || undefined },
      })
      setSession(s)
    } catch (e) {
      toast((e as Error).message, 'bad')
    }
  }, [channel, destId, country, org.slug, toast, refreshKey, viewerToken])

  useEffect(() => {
    start()
  }, [start])

  // Poll "what's on" + ad events for this viewer session every 3 seconds
  useEffect(() => {
    if (!session) return
    let stop = false
    const tick = async () => {
      try {
        const i = await api<Info>(`/play/${session.sessionId}/info`)
        if (stop) return
        setInfo(i)

        if (session.adMode === 'SGAI') {
          for (const b of [i.currentBreak, ...i.upcomingBreaks]) {
            b?.decision?.slots.forEach(s =>
              sgaiDecisions.current.set(s.mediaKey, { breakId: b.id, campaignId: s.campaignId, campaign: s.campaign })
            )
          }
        }

        if (session.adMode === 'SSAI') {
          const b = i.currentBreak
          if (b?.decision) {
            const off = Date.now() - new Date(b.start).getTime() - 15000 // Player buffer latency compensation (~3 chunks of 5 s)
            let acc = 0
            let label: string | null = null
            b.decision.slots.forEach((s, k) => {
              if (off >= acc && off < acc + s.durationMs) {
                label = `Commercial ${k + 1} of ${b.decision!.slots.length} · ${s.campaign} (${s.advertiser})`
              }
              acc += s.durationMs
            })
            setAdNow(label ?? (off >= 0 && off < b.decision.breakMs ? 'Commercial Break · Standby Slate (Unallocated Time)' : null))
          } else {
            setAdNow(i.currentBreak ? (i.currentBreak.filledByUs ? 'Commercial Break in Progress' : 'Distributor Ad Window · Slate in Master Feed') : null)
          }
        }
      } catch {
        /* transient polling error */
      }
    }
    tick()
    const t = setInterval(tick, 3000)
    return () => {
      stop = true
      clearInterval(t)
    }
  }, [session])

  // SGAI client interstitial ad beacons
  const onPlayer = useCallback((e: PlayerEvent) => {
    if (!session || session.adMode !== 'SGAI' || !e.uri) return
    const key = [...sgaiDecisions.current.keys()].find(k => e.uri!.includes(k))
    const d = key ? sgaiDecisions.current.get(key) : undefined
    if (e.type === 'interstitial-start') setAdNow(d ? `Interstitial Sponsor Spot · ${d.campaign}` : 'Interstitial Commercial · Standby Slate')
    if (e.type === 'interstitial-end') setAdNow(null)
    if (d) {
      api(`/play/${session.sessionId}/beacons`, {
        body: { breakId: d.breakId, campaignId: d.campaignId, event: e.type === 'interstitial-start' ? 'IMPRESSION' : 'COMPLETE' },
      }).catch(() => {})
    }
  }, [session])

  const copyStreamUrl = useCallback(() => {
    if (!session?.playlistUrl) return
    const fullUrl = session.playlistUrl.startsWith('http') ? session.playlistUrl : `${window.location.origin}${session.playlistUrl}`
    navigator.clipboard.writeText(fullUrl).then(() => {
      setCopiedUrl(true)
      toast('Copied live HLS stream URL to clipboard', 'ok')
      setTimeout(() => setCopiedUrl(false), 2400)
    })
  }, [session?.playlistUrl, toast])

  const dest = useMemo(() => dests.data?.destinations.find(d => d.id === destId), [dests.data, destId])

  if (channels.data && live.length === 0) {
    return (
      <div className="page">
        <PageHead title="Watch Live Broadcast" />
        <div className="card" style={{ padding: 40 }}>
          <Empty
            icon="tv"
            title="No linear channel is broadcasting on air"
            text="Stage a programming schedule, publish your daypart lineup, and activate Go Live on a channel."
            action={<Link className="btn primary" to={`${org.base}/channels`}>Manage Linear Channels</Link>}
          />
        </div>
      </div>
    )
  }

  // Calculate now progress
  const nowPercent = useMemo(() => {
    if (!info?.now) return 0
    const s = new Date(info.now.start).getTime()
    const e = new Date(info.now.end).getTime()
    const nowTime = info.serverTime ? new Date(info.serverTime).getTime() : Date.now()
    if (isNaN(s) || isNaN(e) || e <= s) return 50
    return Math.max(0, Math.min(100, Math.round(((nowTime - s) / (e - s)) * 100)))
  }, [info?.now, info?.serverTime])

  const remainingSecs = useMemo(() => {
    if (!info?.now) return 0
    const e = new Date(info.now.end).getTime()
    const nowTime = info.serverTime ? new Date(info.serverTime).getTime() : Date.now()
    return Math.max(0, Math.round((e - nowTime) / 1000))
  }, [info?.now, info?.serverTime])

  return (
    <div className="page watch-page-container">
      {/* Studio Header */}
      <PageHead
        title="Live Broadcast Monitor & Ad Telemetry"
        subtitle="Cinema-grade live HLS feed running master playout with SSAI/SGAI monetization, SCTE-35 markers, and viewer territory compliance."
        actions={
          <div className="row" style={{ gap: 8 }}>
            <button
              type="button"
              className="btn"
              onClick={() => setRefreshKey(k => k + 1)}
              title="Restart viewer stream session"
            >
              <Icon name="refresh" size={14} />
              <span>Reset Feed</span>
            </button>
            <button
              type="button"
              className="btn"
              onClick={copyStreamUrl}
              disabled={!session?.playlistUrl}
              title="Copy Master HLS M3U8 Manifest URL"
            >
              <Icon name={copiedUrl ? 'check' : 'link'} size={14} />
              <span>{copiedUrl ? 'Copied URL' : 'Copy HLS URL'}</span>
            </button>
            <Link className="btn" to={`${org.base}/schedule?channel=${slug}`}>
              <Icon name="calendar" size={14} />
              <span>Schedule Editor</span>
            </Link>
          </div>
        }
      />

      {/* Top High-Level Metric Cards (Harmonized with Channels & Library UI) */}
      <div className="grid g4 library-kpi-grid">
        <div className="card library-kpi-card color-primary">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-primary">
              <Icon name="tv" size={18} />
            </span>
            <span className="badge live sm">
              <span className="dot" /> ON AIR
            </span>
          </div>
          <div>
            <div className="library-kpi-val" style={{ fontSize: 19, letterSpacing: '-0.02em' }}>
              {channel ? channel.channel.name : 'Master Playout'}
            </div>
            <div className="library-kpi-label">Active Linear Channel</div>
            <div className="library-kpi-sub faint small">
              {channel ? `${channel.channel.timezone} · v${channel.publishedVersion ?? 1}` : 'Synchronizing...'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-emerald">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-emerald">
              <Icon name="activity" size={18} />
            </span>
            <span className="badge ok sm">5s Chunks</span>
          </div>
          <div>
            <div className="library-kpi-val" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span>~9.0s</span>
              <span className="channel-sound-waves" title="Signal Active">
                <span /><span /><span /><span />
              </span>
            </div>
            <div className="library-kpi-label">Broadcast Jitter Latency</div>
            <div className="library-kpi-sub faint small">3-segment rolling live window</div>
          </div>
        </div>

        <div className="card library-kpi-card color-cyan">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-cyan">
              <Icon name="globe" size={18} />
            </span>
            <span className={`badge sm ${info?.rightsNow?.allowed === false ? 'bad' : 'info'}`}>
              {info?.rightsNow?.allowed === false ? 'Geo-Blocked' : 'Territory Clear'}
            </span>
          </div>
          <div>
            <div className="library-kpi-val" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span>{flag(country)}</span>
              <span>{country}</span>
            </div>
            <div className="library-kpi-label">Simulated Viewer Location</div>
            <div className="library-kpi-sub faint small" title={COUNTRIES[country]}>
              {COUNTRIES[country] ?? 'Simulated edge node'}
            </div>
          </div>
        </div>

        <div className="card library-kpi-card color-purple">
          <div className="between">
            <span className="library-kpi-icon-wrap bg-purple">
              <Icon name="megaphone" size={18} />
            </span>
            <span className="badge sim sm">{dest?.adMode ?? 'SSAI'}</span>
          </div>
          <div>
            <div className="library-kpi-val">
              {info?.upcomingBreaks.length ?? 0}{' '}
              <span className="faint" style={{ fontSize: 14, fontWeight: 500 }}>
                Breaks
              </span>
            </div>
            <div className="library-kpi-label">Ad Insertion Pipeline</div>
            <div className="library-kpi-sub faint small">
              {info?.events.length ?? 0} impressions verified
            </div>
          </div>
        </div>
      </div>

      {/* Broadcast Simulation Console */}
      <div className="watch-controls-bar">
        <div className="watch-control-item">
          <label className="watch-control-label">
            <span>Broadcast Channel</span>
            <span className="badge ok sm" style={{ padding: '1px 6px', fontSize: 10 }}>Live</span>
          </label>
          <Dropdown
            options={channelOptions}
            value={slug}
            onChange={s => setParams({ channel: s })}
            align="full"
            searchable
            placeholder="Select broadcasting channel..."
          />
        </div>

        <div className="watch-control-item">
          <label className="watch-control-label">
            <span className="row" style={{ gap: 4 }}>
              Viewer Territory
              <Sim text="Edge Sim" tip="Simulates viewer IP geo-location to test licensing rules and localized commercial breaks." />
            </span>
            <span className="faint" style={{ fontSize: 10.5 }}>{flag(country)}</span>
          </label>
          <Dropdown
            options={countryOptions}
            value={country}
            onChange={setCountry}
            align="full"
            searchable
            placeholder="Select simulated territory..."
          />
        </div>

        <div className="watch-control-item">
          <label className="watch-control-label">
            <span className="row" style={{ gap: 4 }}>
              Ad Insertion Destination
              <InfoTip text="Controls how commercial inventory is delivered: SSAI stitched, SGAI interstitials, or SCTE-35 marker passthrough." />
            </span>
            <span className="badge primary sm" style={{ padding: '1px 6px', fontSize: 10 }}>{dest?.adMode ?? 'SSAI'}</span>
          </label>
          <Dropdown
            options={destOptions}
            value={destId}
            onChange={setDestId}
            align="full"
            placeholder="Select ad pipeline destination..."
          />
        </div>

        <details className="watch-control-item"><summary>Viewer audience context</summary>
          <label className="field"><span>Signed context from your viewer application</span><input type="password" autoComplete="off" value={viewerInput} onChange={e => setViewerInput(e.target.value)} placeholder="Optional signed viewer token" /></label>
          <p className="faint small">Leave blank for contextual ads. The tenant must enable personalization and your application server must sign the context.</p>
          <button type="button" className="btn sm" onClick={() => setViewerToken(viewerInput.trim())}>Apply and restart viewing</button>
          <button type="button" className="btn sm" onClick={async () => { if (session) await api(`/play/${session.sessionId}/privacy/contextual`, { method: 'POST' }); setViewerInput(''); setViewerToken('') }}>Use contextual ads</button>
        </details>
        <div className="watch-control-item" style={{ justifyContent: 'flex-end', paddingTop: 18 }}>
          <button
            type="button"
            className="btn"
            onClick={copyStreamUrl}
            title="Copy Master M3U8 URL"
            style={{ height: 38 }}
          >
            <Icon name="link" size={14} />
            <span>Copy Feed</span>
          </button>
        </div>
      </div>

      {/* Main Studio Work Area: Split Grid */}
      {!channels.data || !dests.data ? (
        <Loading />
      ) : (
        <div className="split">
          {/* Left Column: Broadcast Monitor & Manifest Inspector */}
          <div className="stack" style={{ gap: 18 }}>
            {/* Studio Monitor Frame */}
            <div className="watch-monitor-frame">
              {/* Monitor Top Status Bezel */}
              <div className="watch-monitor-top">
                <div className="row" style={{ gap: 10 }}>
                  <span className="watch-tally-badge">
                    <span className="watch-tally-dot" /> LIVE AIR
                  </span>
                  {channel && (
                    <div className="row" style={{ gap: 6 }}>
                      <ChannelLogo name={channel.channel.name} color={channel.channel.color} size={22} />
                      <span style={{ color: '#fff', fontWeight: 600, fontSize: 13 }}>
                        {channel.channel.name}
                      </span>
                    </div>
                  )}
                </div>

                <div className="row" style={{ gap: 14 }}>
                  <div className="row" style={{ gap: 6 }} title="Broadcast Audio Level Active">
                    <span style={{ fontSize: 11, color: '#94a3b8', fontWeight: 600 }}>AUDIO</span>
                    <div className="watch-vu-meter">
                      <div className="watch-vu-bar" />
                      <div className="watch-vu-bar" />
                      <div className="watch-vu-bar" />
                      <div className="watch-vu-bar" />
                      <div className="watch-vu-bar" />
                    </div>
                  </div>

                  <span className="badge sm" style={{ background: 'rgba(255,255,255,0.1)', color: '#e2e8f0', border: '1px solid rgba(255,255,255,0.15)' }}>
                    1080p60 · AAC
                  </span>
                  <span className="badge sim sm">
                    {flag(country)} {country}
                  </span>
                </div>
              </div>

              {/* Video Player Display */}
              <div className="watch-player-wrap">
                {session ? (
                  <VideoPlayer key={session.sessionId} src={session.playlistUrl} live onEvent={onPlayer} />
                ) : (
                  <div className="player-msg">
                    <div className="stack" style={{ alignItems: 'center', gap: 12 }}>
                      <span className="spinner" />
                      <div>
                        <b>Synchronizing Live Playout Feed...</b>
                        <p className="faint small" style={{ marginTop: 4 }}>Connecting to edge distribution node</p>
                      </div>
                    </div>
                  </div>
                )}

                {/* Live Commercial Alert Overlay */}
                {adNow && (
                  <div className={`watch-player-ad-banner ${adNow.includes('Slate') ? 'slate-banner' : ''}`}>
                    <div className="row" style={{ gap: 10 }}>
                      <span style={{ fontSize: 16 }}>📣</span>
                      <div>
                        <div style={{ fontWeight: 700, fontSize: 13 }}>
                          {adNow.includes('Slate') ? 'Standby Slate Window' : 'Commercial Insertion Active'}
                        </div>
                        <div style={{ fontSize: 12, opacity: 0.9 }}>{adNow}</div>
                      </div>
                    </div>
                    <span className="badge sm" style={{ background: 'rgba(0,0,0,0.3)', color: '#fff' }}>
                      {dest?.adMode ?? 'SSAI'}
                    </span>
                  </div>
                )}
              </div>

              {/* Monitor Footer Bar */}
              <div className="watch-monitor-footer">
                <div className="row" style={{ gap: 6 }}>
                  <Icon name="info" size={13} />
                  <span>Stream starts muted by browser policy — toggle speaker icon for audio.</span>
                </div>
                <div className="row" style={{ gap: 8 }}>
                  <span className="faint">Delay: ~9s live jitter buffer</span>
                  <span className="badge sm" style={{ background: 'rgba(255,255,255,0.06)', color: '#94a3b8' }}>HLS.js v4</span>
                </div>
              </div>
            </div>

            {/* Technical Inspection Deck (Tabs) */}
            <section className="card">
              <div className="tabs" style={{ padding: '4px 16px', borderBottom: '1px solid var(--border)' }}>
                <button
                  type="button"
                  className={`watch-tab-btn ${tab === 'what' ? 'is-active' : ''}`}
                  onClick={() => setTab('what')}
                >
                  <Icon name="sliders" size={14} />
                  <span>Pipeline Architecture</span>
                </button>
                <button
                  type="button"
                  className={`watch-tab-btn ${tab === 'playlist' ? 'is-active' : ''}`}
                  onClick={() => setTab('playlist')}
                >
                  <Icon name="code" size={14} />
                  <span>Live HLS Manifest Inspector</span>
                </button>
                <button
                  type="button"
                  className={`watch-tab-btn ${tab === 'health' ? 'is-active' : ''}`}
                  onClick={() => setTab('health')}
                >
                  <Icon name="activity" size={14} />
                  <span>Stream Diagnostics</span>
                </button>
              </div>

              <div className="card-pad">
                {tab === 'what' ? (
                  <Explain mode={dest?.adMode ?? 'SSAI'} dest={dest} info={info} />
                ) : tab === 'playlist' ? (
                  session ? <PlaylistViewer url={session.playlistUrl} adKeys={info?.currentBreak?.decision?.slots.map(s => s.mediaKey) ?? []} /> : <p className="faint">Awaiting playback session...</p>
                ) : (
                  <Diagnostics session={session} info={info} channel={channel} dest={dest} />
                )}
              </div>
            </section>
          </div>

          {/* Right Column: Live Broadcast Telemetry */}
          <div className="stack" style={{ gap: 18 }}>
            {/* On Air Now Hero Card */}
            <section className="card card-pad stack watch-now-playing-card">
              <div className="between" style={{ alignItems: 'flex-start' }}>
                <div className="row" style={{ gap: 10 }}>
                  {channel && <ChannelLogo name={channel.channel.name} color={channel.channel.color} size={36} />}
                  <div>
                    <span className="badge live sm" style={{ marginBottom: 4 }}>
                      <span className="dot" /> ON AIR NOW
                    </span>
                    <h2 style={{ fontSize: 18, margin: 0 }}>Current Broadcast</h2>
                  </div>
                </div>
                {info?.now?.genre && (
                  <span className="badge primary sm">{info.now.genre}</span>
                )}
              </div>

              {info?.now ? (
                <div className="stack" style={{ gap: 10, marginTop: 4 }}>
                  <div>
                    <div className="watch-now-playing-title">{info.now.title}</div>
                    <div className="between small faint" style={{ marginTop: 4 }}>
                      <span>
                        {fmtTime(info.now.start)} – {fmtTime(info.now.end)}
                      </span>
                      <span>
                        {remainingSecs > 0 ? `${Math.ceil(remainingSecs / 60)} min remaining` : 'Ending soon'}
                      </span>
                    </div>
                  </div>

                  <div className="watch-progress-track" title={`${nowPercent}% elapsed`}>
                    <div className="watch-progress-fill" style={{ width: `${nowPercent}%` }} />
                  </div>

                  {info.now.description && (
                    <p className="small muted" style={{ lineHeight: 1.5, margin: 0 }}>
                      {info.now.description}
                    </p>
                  )}
                </div>
              ) : (
                <div className="stack" style={{ gap: 8, padding: '16px 0' }}>
                  <p className="muted" style={{ margin: 0 }}>
                    {info?.currentBreak ? 'Commercial intermission between scheduled programmes' : 'Loading on-air schedule data...'}
                  </p>
                </div>
              )}

              {info?.next && (
                <div
                  style={{
                    padding: '10px 12px',
                    borderRadius: 8,
                    background: 'var(--surface-2)',
                    border: '1px solid var(--border)',
                    marginTop: 6,
                  }}
                >
                  <div className="between small faint">
                    <span style={{ fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.04em' }}>Up Next</span>
                    <span>Starts {fmtTime(info.next.start)}</span>
                  </div>
                  <div style={{ fontWeight: 600, color: 'var(--text)', marginTop: 2, fontSize: 13.5 }}>
                    {info.next.title}
                  </div>
                  {info.next.genre && <span className="faint small">{info.next.genre}</span>}
                </div>
              )}

              {/* Rights & Licensing Callout */}
              {info?.rightsNow && (
                <div className={`callout ${info.rightsNow.allowed ? 'ok' : 'bad'}`} style={{ marginTop: 8 }}>
                  <Icon name={info.rightsNow.allowed ? 'checkCircle' : 'shield'} size={18} />
                  <div className="small">
                    <b>
                      {info.rightsNow.allowed
                        ? `Territory Cleared in ${COUNTRIES[country]}`
                        : `Territory Restricted in ${COUNTRIES[country]}`}
                    </b>
                    <div style={{ marginTop: 2 }}>{info.rightsNow.summary}</div>
                  </div>
                </div>
              )}
            </section>

            {/* Ad Breaks & Inventory Allocation Card */}
            <section className="card">
              <div className="card-head between">
                <div>
                  <h2 style={{ fontSize: 16 }}>Ad Break Queue</h2>
                  <p className="small faint">Programmed commercial pods in 3-hour window</p>
                </div>
                <span className="badge sim sm">{dest?.adMode ?? 'SSAI'}</span>
              </div>
              <div className="card-pad stack" style={{ gap: 10 }}>
                {info?.currentBreak && <BreakCard b={info.currentBreak} mode={info.adMode} live />}
                {info?.upcomingBreaks.map(b => (
                  <BreakCard key={b.id} b={b} mode={info.adMode} />
                ))}
                {info && !info.currentBreak && info.upcomingBreaks.length === 0 && (
                  <div className="empty-box" style={{ padding: '24px 12px', textAlign: 'center' }}>
                    <span style={{ opacity: 0.3, display: 'inline-block', margin: '0 auto 8px' }}>
                      <Icon name="megaphone" size={24} />
                    </span>
                    <p className="faint small" style={{ margin: 0 }}>No commercial breaks scheduled in the next 3 hours.</p>
                  </div>
                )}
              </div>
            </section>

            {/* Ad Delivery & Telemetry Log */}
            <section className="card">
              <div className="card-head between">
                <div>
                  <h2 style={{ fontSize: 16 }}>Live Ad Delivery Telemetry</h2>
                  <p className="small faint">Audited playout beacons for this viewer session</p>
                </div>
                <span className="badge ok sm">
                  {info?.events.length ?? 0} Beacons
                </span>
              </div>
              <div className="card-pad stack" style={{ gap: 8, maxHeight: 300, overflowY: 'auto' }}>
                {!info?.events.length && (
                  <div style={{ padding: '16px 8px', textAlign: 'center' }}>
                    <p className="faint small" style={{ margin: 0 }}>
                      {info?.adMode === 'PASSTHROUGH'
                        ? 'Pass-through mode: Platform partners track their own impressions.'
                        : 'Awaiting commercial break: Verified impression beacons will stream here during ad playback.'}
                    </p>
                  </div>
                )}
                {info?.events.map((e, i) => {
                  const ev = EVENT[e.event] ?? { label: e.event, badge: 'info' }
                  return (
                    <div key={i} className="watch-event-row">
                      <div className="watch-event-left">
                        <span className={`badge sm ${ev.badge}`}>{ev.label}</span>
                        <span style={{ fontWeight: 600, color: 'var(--text)' }} title={e.campaign}>
                          {e.campaign}
                        </span>
                      </div>
                      <span className="faint small" style={{ whiteSpace: 'nowrap' }}>
                        {fmtTime(e.occurredAt)}
                      </span>
                    </div>
                  )
                })}
              </div>
            </section>
          </div>
        </div>
      )}
    </div>
  )
}

function BreakCard({ b, mode, live }: { b: BreakInfo; mode: string; live?: boolean }) {
  const secs = (new Date(b.end).getTime() - new Date(b.start).getTime()) / 1000
  const ours = b.filledByUs

  return (
    <div className={`watch-break-card ${live ? 'is-live' : ''}`}>
      <div className="between">
        <div className="row small" style={{ gap: 8 }}>
          <b style={{ color: 'var(--text)' }}>{fmtTime(b.start)}</b>
          <span className="badge primary sm">{secs}s pod</span>
          {live && (
            <span className="badge warn sm">
              <span className="dot" /> Playing Now
            </span>
          )}
        </div>
        <span className={`badge sm ${b.ownership === 'DISTRIBUTOR' ? '' : 'warn'}`}>
          {b.ownership === 'DISTRIBUTOR' ? "Distributor Pod" : 'Our SSAI Pod'}
        </span>
      </div>

      {b.decision ? (
        <div className="stack" style={{ gap: 6, marginTop: 8 }}>
          <div className="watch-break-timeline-bar">
            {b.decision.slots.map(s => (
              <div
                key={s.campaignId}
                className="watch-slot-segment"
                style={{ flex: s.durationMs, background: '#4f46e5' }}
                title={`${s.campaign} (${s.advertiser}) · ${s.durationMs / 1000}s`}
              >
                {s.advertiser.split(' ')[0]} ({s.durationMs / 1000}s)
              </div>
            ))}
            {b.decision.breakMs > b.decision.filledMs && (
              <div
                className="watch-slot-slate"
                style={{ flex: b.decision.breakMs - b.decision.filledMs }}
                title={`Standby slate: ${(b.decision.breakMs - b.decision.filledMs) / 1000}s`}
              >
                Slate ({(b.decision.breakMs - b.decision.filledMs) / 1000}s)
              </div>
            )}
          </div>
          <div className="between small faint" style={{ marginTop: 2 }}>
            <span>{b.decision.summary}</span>
            <span>{Math.round((b.decision.filledMs / b.decision.breakMs) * 100)}% Fill Yield</span>
          </div>
        </div>
      ) : (
        <div className="faint small" style={{ marginTop: 6 }}>
          {!ours || mode === 'PASSTHROUGH'
            ? 'Signaled via SCTE-35 marker — filled by downstream distribution partner.'
            : 'Ad Decision Server evaluates real-time campaign bids at cue trigger.'}
        </div>
      )}
    </div>
  )
}

function Explain({ mode, dest, info }: { mode: string; dest?: Destination; info: Info | null }) {
  const t = MODE_TEXT[mode] ?? MODE_TEXT.SSAI

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="between" style={{ alignItems: 'flex-start' }}>
        <div>
          <h3 style={{ fontSize: 17, margin: '0 0 4px' }}>{t.title}</h3>
          <span className="badge info sm">{t.subtitle}</span>
        </div>
        <span className="badge primary sm">{t.badge}</span>
      </div>

      <p className="muted" style={{ lineHeight: 1.6, margin: 0 }}>{t.body}</p>

      {dest?.notes && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'var(--surface-2)', border: '1px solid var(--border)' }}>
          <b className="small" style={{ color: 'var(--text)' }}>Destination Profile: {dest.name}</b>
          <p className="faint small" style={{ margin: '4px 0 0' }}>{dest.notes}</p>
        </div>
      )}

      <div className="grid g3" style={{ gap: 12, marginTop: 4 }}>
        <Mini
          n={1}
          title="Playout & Manifest"
          text="Every 3 seconds, the linear scheduler computes active segments from the master air schedule and broadcast clock."
        />
        <Mini
          n={2}
          title="Break Marker Cues"
          text={
            mode === 'SGAI'
              ? 'At an ad break, the manifest injects an HLS interstitial directive pointing to dynamic ad inventory.'
              : `At an ad break, SCTE-35 markers (${info?.markerFormat === 'CUE_OUT' ? 'CUE-OUT/IN' : 'EXT-X-DATERANGE'}) tag the window duration and pod ownership.`
          }
        />
        <Mini
          n={3}
          title={mode === 'PASSTHROUGH' ? 'Partner Insertion' : 'Targeted Decision'}
          text={
            mode === 'PASSTHROUGH'
              ? 'The distribution platform decodes the SCTE-35 marker and fills local inventory with its own ad server.'
              : 'The Ad Decision Engine selects active campaigns matching viewer territory, frequency caps, and pod length.'
          }
        />
      </div>

      <div style={{ paddingTop: 6 }}>
        <Link to="../learn" relative="path" className="btn sm">
          <Icon name="book" size={14} />
          <span>Read Full Architecture Guide (SSAI, SGAI, and SCTE-35)</span>
        </Link>
      </div>
    </div>
  )
}

function Mini({ n, title, text }: { n: number; title: string; text: string }) {
  return (
    <div className="pipe-step" style={{ padding: 14 }}>
      <div className="n">{n}</div>
      <b style={{ fontSize: 13, color: 'var(--text)' }}>{title}</b>
      <p className="faint small" style={{ marginTop: 6, lineHeight: 1.45 }}>
        {text}
      </p>
    </div>
  )
}

/** Color-coded live HLS playlist viewer */
function PlaylistViewer({ url, adKeys }: { url: string; adKeys: string[] }) {
  const [text, setText] = useState('')
  const [master, setMaster] = useState('')

  useEffect(() => {
    let stop = false
    fetch(url)
      .then(r => r.text())
      .then(t => {
        if (!stop) setMaster(t)
      })
      .catch(() => {})

    const variant = url.replace(/master\.m3u8$/, 'v0.m3u8')
    const load = () => {
      fetch(variant)
        .then(r => r.text())
        .then(t => {
          if (!stop) setText(t)
        })
        .catch(() => {})
    }
    load()
    const t = setInterval(load, 5000)
    return () => {
      stop = true
      clearInterval(t)
    }
  }, [url])

  const lines = useMemo(() => text.split('\n').filter(Boolean), [text])
  const masterLines = useMemo(() => master.split('\n').filter(Boolean), [master])
  const scte = lines.find(l => l.includes('SCTE35-OUT='))?.match(/SCTE35-OUT=(0x[0-9A-F]+)/)?.[1]

  return (
    <div className="stack" style={{ gap: 14 }}>
      <div className="row-wrap small" style={{ gap: 8 }}>
        <span className="badge" style={{ background: '#fdf2f8', color: '#be185d' }}>
          DISCONTINUITY: Segment boundary
        </span>
        <span className="badge warn">DATERANGE / CUE: Ad break marker</span>
        <span className="badge ok">interstitial: SGAI ad pointer</span>
        <span className="badge" style={{ background: '#fff7ed', color: '#c2410c' }}>
          Ad creative: SSAI Stitched Ad
        </span>
      </div>

      <div>
        <div className="between small" style={{ marginBottom: 6 }}>
          <b style={{ color: 'var(--text)' }}>1 · Master Playlist (master.m3u8)</b>
          <span className="faint">Adaptive bitrate selector</span>
        </div>
        <div className="watch-manifest-box" style={{ maxHeight: 120 }}>
          {masterLines.map((l, i) => (
            <div key={i} className={cls(l, adKeys)}>
              {l}
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="between small" style={{ marginBottom: 6 }}>
          <b style={{ color: 'var(--text)' }}>2 · Rendition 360p Media Playlist (v0.m3u8)</b>
          <span className="badge ok sm">Auto-refreshing (5s)</span>
        </div>
        <div className="watch-manifest-box">
          {lines.map((l, i) => (
            <div key={i} className={cls(l, adKeys)}>
              {l}
            </div>
          ))}
        </div>
      </div>

      {scte && (
        <div className="callout info small">
          <Icon name="code" size={16} />
          <div>
            <b>Active SCTE-35 Marker Detected in Manifest:</b> <code>{scte}</code>
            <div style={{ marginTop: 4 }}>
              <Link to={`../learn?scte=${scte}`} relative="path">
                Decode this marker in the SCTE-35 Inspector →
              </Link>
            </div>
          </div>
        </div>
      )}

      <p className="faint small" style={{ margin: 0 }}>
        Manifest window updates every 5 seconds. Total active sliding window: {fmtDur(lines.filter(l => l.startsWith('#EXTINF')).length * 5000)}.
      </p>
    </div>
  )
}

function Diagnostics({
  session,
  info,
  channel,
  dest,
}: {
  session: Started | null
  info: Info | null
  channel?: ChannelView
  dest?: Destination
}) {
  return (
    <div className="stack" style={{ gap: 14 }}>
      <h3 style={{ fontSize: 16, margin: 0 }}>Playout Stream Health Diagnostics</h3>

      <div className="grid g2" style={{ gap: 12 }}>
        <div className="card card-pad stack" style={{ gap: 8, background: 'var(--surface-2)' }}>
          <b className="small" style={{ color: 'var(--text)' }}>Edge Playout State</b>
          <div className="kv" style={{ gridTemplateColumns: '140px 1fr' }}>
            <span>Target Duration:</span>
            <b>5.0 seconds</b>
            <span>Playout Engine:</span>
            <b>HLS v4 Live Sliding Window</b>
            <span>Active Channel:</span>
            <b>{channel?.channel.name ?? 'None'}</b>
            <span>Server Clock:</span>
            <span className="mono">{info?.serverTime ? fmtDayTime(info.serverTime) : 'Syncing'}</span>
          </div>
        </div>

        <div className="card card-pad stack" style={{ gap: 8, background: 'var(--surface-2)' }}>
          <b className="small" style={{ color: 'var(--text)' }}>Session Metadata</b>
          <div className="kv" style={{ gridTemplateColumns: '140px 1fr' }}>
            <span>Session ID:</span>
            <span className="mono" style={{ fontSize: 11 }}>{session?.sessionId ?? 'None'}</span>
            <span>Ad Mode:</span>
            <b>{dest?.adMode ?? 'SSAI'}</b>
            <span>Marker Format:</span>
            <b>{dest?.markerFormat ?? 'DATERANGE'}</b>
            <span>Active Location:</span>
            <b>{info?.country ? `${flag(info.country)} ${COUNTRIES[info.country]}` : 'None'}</b>
          </div>
        </div>
      </div>

      <div className="card card-pad stack" style={{ gap: 8 }}>
        <b className="small" style={{ color: 'var(--text)' }}>Territory Rights Verification</b>
        {info?.rightsNow ? (
          <div className="row" style={{ gap: 8 }}>
            <span className={`badge ${info.rightsNow.allowed ? 'ok' : 'bad'}`}>
              {info.rightsNow.allowed ? 'CLEAR TO BROADCAST' : 'GEO-BLOCKED'}
            </span>
            <span className="small faint">{info.rightsNow.summary}</span>
          </div>
        ) : (
          <span className="faint small">Awaiting clearance decision from rights registry...</span>
        )}
      </div>
    </div>
  )
}

function cls(l: string, adKeys: string[]) {
  if (l.startsWith('##')) return 't-com'
  if (l === '#EXT-X-DISCONTINUITY') return 't-disc'
  if (l.includes('interstitial')) return 't-inter'
  if (l.includes('DATERANGE') || l.includes('CUE-')) return 't-scte'
  if (l.startsWith('#')) return 't-head'
  // segment URIs: absolute edge/R2 URLs; a stitched ad is one whose media key belongs to this break's decision
  return adKeys.some(k => l.includes('/' + k + '/')) ? 't-ad' : 't-seg'
}
