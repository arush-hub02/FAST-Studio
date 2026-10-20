import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, type ChannelView, type Decision, type Programme, type RightsDecision } from '../api'
import { useOrg } from '../app'
import { ChannelLogo, COUNTRIES, Dropdown, Empty, flag, fmtDur, fmtTime, Icon, InfoTip, Loading, PageHead, Sim, useApi, useToast, VideoPlayer, type PlayerEvent } from '../ui'

type Destination = { id: string; name: string; kind: string; adMode: string; markerFormat: string; maxAdSecondsPerHour: number; notes: string }
type Started = { sessionId: string; playlistUrl: string; adMode: string; markerFormat: string; destination: string; country: string; rightsNow?: RightsDecision }
type BreakInfo = { id: string; start: string; end: string; ownership: string; filledByUs: boolean; scteOut: string; decision?: Decision }
type Info = {
  channel: string; adMode: string; markerFormat: string; country: string; now?: Programme; next?: Programme; currentBreak?: BreakInfo
  upcomingBreaks: BreakInfo[]; rightsNow?: RightsDecision; events: { event: string; campaign: string; seconds: number; occurredAt: string }[]; serverTime: string
}

const MODE_TEXT: Record<string, { title: string; body: string }> = {
  SSAI: { title: 'Server-side ad insertion (SSAI)', body: 'Our server swaps the break for real ad video inside this viewer’s own playlist. The player never knows it’s an ad — it just keeps playing. Every ad piece the player downloads is counted as delivered.' },
  SGAI: { title: 'Server-guided ad insertion (SGAI)', body: 'Everyone gets the same programme playlist. At the break it contains a pointer (an HLS “interstitial”); the player itself fetches the ad list and plays the ads, then rejoins the channel exactly where it should be.' },
  PASSTHROUGH: { title: 'Markers only (partner inserts ads)', body: 'This is what a TV platform receives: the programme plus SCTE-35/CUE markers saying “ad break here, 90 s”. The platform fills the break with its own ads. Here you see our standby slate in the gap.' },
}

export default function Watch() {
  const org = useOrg()
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const dests = useApi<{ destinations: Destination[] }>(`/api/orgs/${org.slug}/destinations`)
  const live = channels.data?.filter(c => c.channel.status === 'LIVE') ?? []
  const slug = params.get('channel') ?? live[0]?.channel.slug ?? ''
  const channel = live.find(c => c.channel.slug === slug)
  const [destId, setDestId] = useState('')
  const [country, setCountry] = useState('IN')
  const [session, setSession] = useState<Started | null>(null)
  const [info, setInfo] = useState<Info | null>(null)
  const [adNow, setAdNow] = useState<string | null>(null)
  const [tab, setTab] = useState<'what' | 'playlist'>('what')
  const sgaiDecisions = useRef<Map<string, { breakId: string; campaignId: string; campaign: string }>>(new Map())

  const channelOptions = useMemo(() => live.map(c => ({
    value: c.channel.slug,
    label: c.channel.name,
    icon: 'tv',
    badge: 'LIVE',
    badgeType: 'live' as const,
    description: `${c.channel.timezone} · v${c.publishedVersion ?? 1}`,
  })), [live])

  const countryOptions = useMemo(() => Object.keys(COUNTRIES).filter(c => c !== 'WW').map(c => ({
    value: c,
    label: `${flag(c)} ${COUNTRIES[c]}`,
  })), [])

  const destOptions = useMemo(() => (dests.data?.destinations ?? []).map(d => ({
    value: d.id,
    label: d.name,
    icon: 'cast',
    description: `${d.kind} · ${d.adMode}`,
  })), [dests.data])

  useEffect(() => {
    if (!destId && dests.data?.destinations.length) setDestId(dests.data.destinations.find(d => d.kind === 'OWNED_WEB' && d.adMode === 'SSAI')?.id ?? dests.data.destinations[0].id)
  }, [dests.data, destId])

  const start = useCallback(async () => {
    if (!channel || !destId) return
    setSession(null); setInfo(null)
    try {
      const s = await api<Started>('/play/sessions', { body: { org: org.slug, channel: channel.channel.slug, destinationId: destId, country } })
      setSession(s)
    } catch (e) { toast((e as Error).message, 'bad') }
  }, [channel, destId, country, org.slug, toast])
  useEffect(() => { start() }, [start])

  // poll "what's on" + ad events for this viewer session
  useEffect(() => {
    if (!session) return
    let stop = false
    const tick = async () => {
      try {
        const i = await api<Info>(`/play/${session.sessionId}/info`)
        if (stop) return
        setInfo(i)
        if (session.adMode === 'SGAI') for (const b of [i.currentBreak, ...i.upcomingBreaks]) b?.decision?.slots.forEach(s => sgaiDecisions.current.set(s.mediaKey, { breakId: b.id, campaignId: s.campaignId, campaign: s.campaign }))
        if (session.adMode === 'SSAI') {
          const b = i.currentBreak
          if (b?.decision) {
            const off = Date.now() - new Date(b.start).getTime() - 9000   // player runs ~3 segments behind live
            let acc = 0, label: string | null = null
            b.decision.slots.forEach((s, k) => { if (off >= acc && off < acc + s.durationMs) label = `Ad ${k + 1} of ${b.decision!.slots.length} · ${s.campaign}`; acc += s.durationMs })
            setAdNow(label ?? (off >= 0 && off < b.decision.breakMs ? 'Ad break · standby slate (unfilled time)' : null))
          } else setAdNow(i.currentBreak ? (i.currentBreak.filledByUs ? 'Ad break' : "Platform's ad break — slate in our feed") : null)
        }
      } catch { /* transient */ }
    }
    tick()
    const t = setInterval(tick, 3000)
    return () => { stop = true; clearInterval(t) }
  }, [session])

  // SGAI: the player reports its own ad playback as client-side beacons
  const onPlayer = useCallback((e: PlayerEvent) => {
    if (!session || session.adMode !== 'SGAI' || !e.uri) return
    const key = [...sgaiDecisions.current.keys()].find(k => e.uri!.includes(k))
    const d = key ? sgaiDecisions.current.get(key) : undefined
    if (e.type === 'interstitial-start') setAdNow(d ? `Interstitial ad · ${d.campaign}` : 'Interstitial · standby slate')
    if (e.type === 'interstitial-end') setAdNow(null)
    if (d) api(`/play/${session.sessionId}/beacons`, { body: { breakId: d.breakId, campaignId: d.campaignId, event: e.type === 'interstitial-start' ? 'IMPRESSION' : 'COMPLETE' } }).catch(() => {})
  }, [session])

  const dest = dests.data?.destinations.find(d => d.id === destId)
  if (channels.data && live.length === 0) return (
    <div className="page"><PageHead title="Watch live" /><div className="card"><Empty icon="play" title="No channel is on air yet"
      text="Publish a schedule and press Go live on a channel, then come back here." action={<Link className="btn primary" to={`${org.base}/channels`}>Go to Channels</Link>} /></div></div>
  )
  return (
    <div className="page">
      <PageHead title="Watch live" tip="This is a real live HLS stream generated by the prototype from your published schedule — the same thing a viewer's app would play."
        subtitle="Pick a channel, where the viewer is, and how they receive ads. Everything below updates live." />
      {!channels.data || !dests.data ? <Loading /> : (
        <div className="split">
          <div className="stack" style={{ gap: 16 }}>
            <div className="card card-pad grid g3">
              <div className="field">
                <span>Channel</span>
                <Dropdown
                  options={channelOptions}
                  value={slug}
                  onChange={s => setParams({ channel: s })}
                  align="full"
                  searchable
                  placeholder="Select live channel..."
                />
              </div>
              <div className="field">
                <span className="row">Viewer Location <Sim text="Simulated" tip="A real service detects the viewer's country from their network address at the edge. Here you choose it to see geo-blocking work." /></span>
                <Dropdown
                  options={countryOptions}
                  value={country}
                  onChange={setCountry}
                  align="full"
                  searchable
                  placeholder="Select country..."
                />
              </div>
              <div className="field">
                <span className="row">Ad Destination <InfoTip text="Each destination has its own way of handling ads: SSAI, SGAI, or passing markers to a partner platform." /></span>
                <Dropdown
                  options={destOptions}
                  value={destId}
                  onChange={setDestId}
                  align="full"
                  placeholder="Select destination..."
                />
              </div>
            </div>

            <div style={{ position: 'relative' }}>
              {session ? <VideoPlayer key={session.sessionId} src={session.playlistUrl} live onEvent={onPlayer} /> :
                <div className="player"><div className="player-msg"><span className="spinner" /></div></div>}
              <div className="player-top">
                <div className="row"><span className="badge live"><span className="dot" />LIVE</span>{channel && <span className="badge" style={{ background: 'rgba(0,0,0,.6)', color: '#fff' }}>{channel.channel.name}</span>}</div>
                <span className="badge" style={{ background: 'rgba(124,58,237,.85)', color: '#fff' }}>{flag(country)} viewer in {COUNTRIES[country]} (simulated)</span>
              </div>
              {adNow && <div className="player-ad">📣 {adNow}</div>}
            </div>
            <p className="faint">Tip: the player starts muted — click the speaker icon for sound. The stream runs a few seconds behind real time, like broadcast TV.</p>

            <section className="card">
              <div className="tabs" style={{ padding: '0 16px' }}>
                <button className={'tab' + (tab === 'what' ? ' active' : '')} onClick={() => setTab('what')}>What's happening</button>
                <button className={'tab' + (tab === 'playlist' ? ' active' : '')} onClick={() => setTab('playlist')}>Under the hood: live playlist</button>
              </div>
              <div className="card-pad">
                {tab === 'what' ? <Explain mode={dest?.adMode ?? 'SSAI'} dest={dest} info={info} /> : session ? <PlaylistViewer url={session.playlistUrl} /> : null}
              </div>
            </section>
          </div>

          <div className="stack" style={{ gap: 16 }}>
            <section className="card card-pad stack">
              <div className="row">{channel && <ChannelLogo name={channel.channel.name} color={channel.channel.color} size={34} />}<h2>On now</h2></div>
              {info?.now ? <>
                <div><b style={{ fontSize: 16 }}>{info.now.title}</b><div className="faint">{fmtTime(info.now.start)} – {fmtTime(info.now.end)} · {info.now.genre}</div></div>
                <div className="progress"><div style={{ width: `${progress(info.now, info.serverTime)}%` }} /></div>
                <p className="small muted">{info.now.description}</p>
              </> : <p className="muted">{info?.currentBreak ? 'Ad break between programmes' : 'Loading…'}</p>}
              {info?.next && <div className="faint">Next at {fmtTime(info.next.start)}: <b style={{ color: 'var(--text)' }}>{info.next.title}</b></div>}
              {info?.rightsNow && (
                <div className={'callout ' + (info.rightsNow.allowed ? 'ok' : 'bad')}>
                  <Icon name={info.rightsNow.allowed ? 'check' : 'shield'} />
                  <div className="small"><b>{info.rightsNow.allowed ? `Licensed in ${COUNTRIES[country]}` : `Blocked in ${COUNTRIES[country]}`}</b><div>{info.rightsNow.summary}</div></div>
                </div>
              )}
            </section>

            <section className="card">
              <div className="card-head"><div><h2>Ad breaks</h2><p>Who fills each break, and with what</p></div></div>
              <div className="card-pad stack">
                {info?.currentBreak && <BreakCard b={info.currentBreak} mode={info.adMode} live />}
                {info?.upcomingBreaks.map(b => <BreakCard key={b.id} b={b} mode={info.adMode} />)}
                {info && !info.currentBreak && info.upcomingBreaks.length === 0 && <p className="faint">No ad breaks in the next 3 hours.</p>}
              </div>
            </section>

            <section className="card">
              <div className="card-head"><div><h2>Ad delivery log</h2><p>Evidence for this viewer session</p></div><Sim text="Demo playback" /></div>
              <div className="card-pad stack" style={{ gap: 6 }}>
                {!info?.events.length && <p className="faint">{info?.adMode === 'PASSTHROUGH' ? 'In pass-through mode the partner platform counts its own ads.' : 'Wait for the next ad break — impressions appear here as the player downloads each ad.'}</p>}
                {info?.events.map((e, i) => (
                  <div key={i} className="between small"><span><span className={'badge ' + (e.event === 'SLATE' ? '' : e.event === 'IMPRESSION' ? 'ok' : 'info')}>{EVENT[e.event] ?? e.event}</span> {e.campaign}</span>
                    <span className="faint">{fmtTime(e.occurredAt)}</span></div>
                ))}
              </div>
            </section>
          </div>
        </div>
      )}
    </div>
  )
}

const EVENT: Record<string, string> = { IMPRESSION: 'Started', FIRST_QUARTILE: '25%', MIDPOINT: '50%', THIRD_QUARTILE: '75%', COMPLETE: 'Completed', SLATE: 'Unfilled' }

function progress(p: Programme, now: string) {
  const s = new Date(p.start).getTime(), e = new Date(p.end).getTime()
  return Math.max(0, Math.min(100, ((new Date(now).getTime() - s) / (e - s)) * 100))
}

function BreakCard({ b, mode, live }: { b: BreakInfo; mode: string; live?: boolean }) {
  const secs = (new Date(b.end).getTime() - new Date(b.start).getTime()) / 1000
  const ours = b.filledByUs
  return (
    <div className="card" style={{ padding: 12, borderColor: live ? '#f59e0b' : undefined }}>
      <div className="between">
        <div className="row small"><b>{fmtTime(b.start)}</b><span className="faint">{secs}s</span>{live && <span className="badge warn">Playing now</span>}</div>
        <span className={'badge ' + (b.ownership === 'DISTRIBUTOR' ? '' : 'warn')}>{b.ownership === 'DISTRIBUTOR' ? "Platform's break" : 'Our break'}</span>
      </div>
      {b.decision ? (
        <div className="stack" style={{ gap: 6, marginTop: 8 }}>
          <div className="ad-bar">
            {b.decision.slots.map(s => <div key={s.campaignId} style={{ flex: s.durationMs, background: '#4f46e5' }} title={s.campaign}>{s.advertiser.split(' ')[0]}</div>)}
            {b.decision.breakMs > b.decision.filledMs && <div className="slate" style={{ flex: b.decision.breakMs - b.decision.filledMs }}>slate</div>}
          </div>
          <div className="faint">{b.decision.summary}</div>
        </div>
      ) : <div className="faint" style={{ marginTop: 6 }}>{!ours || mode === 'PASSTHROUGH' ? 'Passed through with a marker — the platform fills it.' : 'The ad server decides when the break starts.'}</div>}
    </div>
  )
}

function Explain({ mode, dest, info }: { mode: string; dest?: Destination; info: Info | null }) {
  const t = MODE_TEXT[mode]
  return (
    <div className="stack">
      <h3>{t.title}</h3>
      <p className="muted">{t.body}</p>
      {dest?.notes && <p className="faint">{dest.name}: {dest.notes}</p>}
      <div className="grid g3">
        <Mini n={1} title="Schedule → playlist" text="Every 3 seconds the server works out what should be on, from the published schedule and the clock." />
        <Mini n={2} title="Break marked" text={mode === 'SGAI' ? 'At a break it adds an interstitial pointer to the ad list.' : `At a break it adds an SCTE-35 marker (${info?.markerFormat === 'CUE_OUT' ? 'CUE-OUT / CUE-IN tags' : 'EXT-X-DATERANGE'}) with the break length and owner.`} />
        <Mini n={3} title={mode === 'PASSTHROUGH' ? 'Partner fills' : 'Ads chosen'} text={mode === 'PASSTHROUGH' ? 'The partner platform reads the marker and inserts its own ads.' : 'The ad decision picks campaigns that target this country, fit the break and are approved. Leftover time gets a slate.'} />
      </div>
      <Link to="../learn" relative="path" className="btn sm" style={{ alignSelf: 'start' }}><Icon name="book" size={14} />Learn more about SSAI, SGAI and SCTE-35</Link>
    </div>
  )
}

function Mini({ n, title, text }: { n: number; title: string; text: string }) {
  return <div className="pipe-step"><div className="n">{n}</div><b>{title}</b><p className="faint" style={{ marginTop: 4 }}>{text}</p></div>
}

/** Shows the raw live playlist, colour-coded, refreshing like a player does. */
function PlaylistViewer({ url }: { url: string }) {
  const [text, setText] = useState('')
  const [master, setMaster] = useState('')
  useEffect(() => {
    let stop = false
    fetch(url).then(r => r.text()).then(t => { if (!stop) setMaster(t) }).catch(() => {})
    // the live.m3u8 URL is the master playlist; show the 360p rendition's live playlist below it
    const variant = url.replace('live.m3u8', 'v/0.m3u8')
    const load = () => fetch(variant).then(r => r.text()).then(t => { if (!stop) setText(t) }).catch(() => {})
    load()
    const t = setInterval(load, 3000)
    return () => { stop = true; clearInterval(t) }
  }, [url])
  const lines = useMemo(() => text.split('\n').filter(Boolean), [text])
  const masterLines = useMemo(() => master.split('\n').filter(Boolean), [master])
  const scte = lines.find(l => l.includes('SCTE35-OUT='))?.match(/SCTE35-OUT=(0x[0-9A-F]+)/)?.[1]
  return (
    <div className="stack">
      <div className="row-wrap small">
        <span className="badge" style={{ background: '#fdf2f8', color: '#be185d' }}>DISCONTINUITY = switch to another video</span>
        <span className="badge warn">DATERANGE / CUE = ad break marker</span>
        <span className="badge ok">interstitial = SGAI ad pointer</span>
        <span className="badge" style={{ background: '#fff7ed', color: '#c2410c' }}>/ad/… = stitched ad (SSAI)</span>
      </div>
      <b className="small">1 · Master playlist — the player picks a quality that fits its connection, and can switch at any 3-second boundary</b>
      <div className="m3u8" aria-label="Master playlist" style={{ maxHeight: 150 }}>{masterLines.map((l, i) => <div key={i} className={cls(l)}>{l}</div>)}</div>
      <b className="small">2 · Live playlist of the 360p quality (the other qualities list the same pieces at higher resolution)</b>
      <div className="m3u8" aria-label="Live HLS playlist">{lines.map((l, i) => <div key={i} className={cls(l)}>{l}</div>)}</div>
      {scte && <p className="small">This playlist contains a real SCTE-35 marker. <Link to={`../learn?scte=${scte}`} relative="path">Decode it in the SCTE-35 inspector →</Link></p>}
      <p className="faint">Refreshes every 3 s — exactly what the player downloads. Each line is one 3-second piece of video. {fmtDur(lines.filter(l => l.startsWith('#EXTINF')).length * 3000)} window.</p>
    </div>
  )
}

function cls(l: string) {
  if (l.startsWith('##')) return 't-com'
  if (l === '#EXT-X-DISCONTINUITY') return 't-disc'
  if (l.includes('interstitial')) return 't-inter'
  if (l.includes('DATERANGE') || l.includes('CUE-')) return 't-scte'
  if (l.startsWith('/play/') && l.includes('/ad/')) return 't-ad'
  if (l.startsWith('/play/') && l.endsWith('.m3u8')) return 't-seg'
  if (l.startsWith('/')) return 't-seg'
  return 't-head'
}
