import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useOrg } from '../app'
import { Icon, PageHead, useAction } from '../ui'

export default function Learn() {
  const org = useOrg()
  return (
    <div className="page" style={{ maxWidth: 1100 }}>
      <PageHead title="How FAST works" subtitle="A 10-minute guide to free ad-supported TV, written for everyone. Every concept links to the part of FAST Studio where you can try it." />
      <WhatIsFast />
      <Pipeline base={org.base} />
      <AdModels />
      <ScteSection />
      <Glossary />
    </div>
  )
}

function WhatIsFast() {
  return (
    <section className="card card-pad stack" style={{ gap: 16 }}>
      <h2>1 · What is FAST, and why does it exist?</h2>
      <p className="muted"><b>FAST = Free Ad-Supported Streaming Television.</b> It's normal TV channels delivered over the internet: one continuous schedule, the same for everyone at the same moment.
        Viewers don't pay and don't choose each show — they tune in and something is already on. Ads pay for it.</p>
      <div className="grid g3">
        {[
          ['film', 'Content owners', 'Earn money from old films and series that sit unused. A 1990s detective show can become a 24/7 channel.'],
          ['tv', 'TV makers & platforms', 'Fill the channel guide built into smart TVs and apps with free channels, and share the ad money.'],
          ['users', 'Viewers', 'Get free, lean-back TV with no subscription and no "what should I watch?" fatigue.'],
        ].map(([i, t, d]) => <div key={t} className="card card-pad" style={{ background: 'var(--surface-2)' }}><Icon name={i} /><h3 style={{ margin: '8px 0 4px' }}>{t}</h3><p className="faint">{d}</p></div>)}
      </div>
      <table className="table card">
        <thead><tr><th>Model</th><th>Viewer pays?</th><th>Viewer picks each show?</th><th>Example</th></tr></thead>
        <tbody>
          <tr><td><b>SVOD</b></td><td>Subscription</td><td>Yes</td><td>A paid movie library</td></tr>
          <tr><td><b>AVOD</b></td><td>Free, with ads</td><td>Yes</td><td>Free on-demand videos with ad breaks</td></tr>
          <tr style={{ background: '#eef2ff' }}><td><b>FAST</b></td><td>Free, with ads</td><td><b>No — scheduled channel</b></td><td>"Classic Cinema" in your TV's channel guide</td></tr>
        </tbody>
      </table>
      <div className="callout"><Icon name="info" /><div><b>Why it's cheap to run:</b> most FAST channels are <i>virtual linear</i>. Nobody encodes a live signal 24/7 — the server stitches already-prepared video files together according to the schedule. That's exactly what this prototype does.</div></div>
    </section>
  )
}

function Pipeline({ base }: { base: string }) {
  const steps = [
    ['Upload & prepare', 'Videos are converted into identical 5-second pieces.', 'library'],
    ['Licence', 'Rules say where and when each video may play.', 'rights'],
    ['Schedule', 'A timeline decides what plays every second, with ad breaks.', 'schedule'],
    ['Playout', 'The server turns schedule + clock into a live playlist.', 'watch'],
    ['Ads', 'At each break, ads are chosen and inserted (SSAI/SGAI).', 'ads'],
    ['Deliver', 'The stream + TV guide go to apps and TV platforms.', 'distribution'],
  ]
  return (
    <section className="card card-pad stack" style={{ gap: 16 }}>
      <h2>2 · How a channel is made</h2>
      <div className="pipeline">{steps.map(([t, d, to], i) => (
        <Link key={t} to={`${base}/${to}`} className="pipe-step" style={{ color: 'inherit', textDecoration: 'none' }}>
          <div className="n">{i + 1}</div><b>{t}</b><p className="faint" style={{ marginTop: 4 }}>{d}</p></Link>
      ))}</div>
      <p className="muted">Under the hood the live stream is an <b>HLS playlist</b>: a text file listing the next few 5-second video pieces. The player re-downloads it every few seconds.
        Open <Link to={`${base}/watch`}>Watch live → Under the hood</Link> to see it update in real time.</p>
    </section>
  )
}

function AdModels() {
  const [mode, setMode] = useState<'CSAI' | 'SSAI' | 'SGAI'>('SSAI')
  const info = {
    CSAI: { who: 'The viewer\'s player', how: 'The player pauses the show, asks an ad server for ads, plays them from a separate source, then resumes.', good: 'Rich interactivity and tracking', bad: 'Ad blockers stop it; buffering and black frames between show and ad; needs ad code on every device.' },
    SSAI: { who: 'Our server', how: 'For each viewer, the server replaces the break in their personal playlist with ad video pieces. The player just sees one continuous stream.', good: 'Works on every TV and device; seamless; hard to block — the standard for FAST platforms', bad: 'One playlist per viewer (more server work, less caching); ads must be converted to match the show first.' },
    SGAI: { who: 'Server decides, player plays', how: 'Everyone gets the same playlist with a pointer (an HLS "interstitial") at the break. The player fetches the ad list and plays the ads, then rejoins the live channel at the right spot.', good: 'One shared, cacheable playlist; smooth; client-side tracking', bad: 'Needs a player that supports HLS interstitials — newer Apple devices and hls.js do; many TV platforms don\'t yet.' },
  }[mode]
  return (
    <section className="card card-pad stack" style={{ gap: 16 }}>
      <h2>3 · How ads get into the stream: CSAI vs SSAI vs SGAI</h2>
      <p className="muted">All three start from the same thing: an <b>ad break</b> in the schedule (say 90 seconds at 14:30), announced by an <b>SCTE-35 marker</b>. They differ in <i>who</i> puts the ad in front of the viewer.</p>
      <div className="seg" style={{ alignSelf: 'start' }}>{(['CSAI', 'SSAI', 'SGAI'] as const).map(m => <button key={m} className={mode === m ? 'active' : ''} onClick={() => setMode(m)}>{m}</button>)}</div>
      <AdDiagram mode={mode} />
      <div className="grid g2">
        <div className="stack"><div><b>Who inserts the ad:</b> {info.who}</div><div className="muted">{info.how}</div></div>
        <div className="stack small"><div className="callout ok"><Icon name="check" />{info.good}</div><div className="callout warn"><Icon name="alert" />{info.bad}</div></div>
      </div>
      <table className="table card small">
        <thead><tr><th /><th>CSAI</th><th>SSAI</th><th>SGAI</th></tr></thead>
        <tbody>
          <tr><td>Playlist per viewer?</td><td>Shared</td><td>Unique per viewer</td><td>Shared, cacheable</td></tr>
          <tr><td>Ad blockers</td><td>Easily blocked</td><td>Very hard</td><td>Harder</td></tr>
          <tr><td>Device support</td><td>Needs ad SDK</td><td><b>Any HLS player</b></td><td>Interstitial-capable players</td></tr>
          <tr><td>Who counts views</td><td>Player</td><td>Server (segment delivered)</td><td>Player beacons</td></tr>
          <tr><td>In FAST Studio</td><td>—</td><td>Default for our web player &amp; partners</td><td>"SGAI beta" web destination</td></tr>
        </tbody>
      </table>
      <div className="callout"><Icon name="info" /><div>Many TV platforms take a third route: we send the stream with markers only, and <b>they</b> insert their own ads in the breaks marked as theirs. That's the "Partner TV" destination in Distribution.</div></div>
    </section>
  )
}

function AdDiagram({ mode }: { mode: string }) {
  const seg = (label: string, color: string, flex = 1, key?: string) => <div key={key ?? label} style={{ flex, background: color, color: '#fff', display: 'grid', placeItems: 'center', fontSize: 11.5, fontWeight: 650, borderRight: '2px solid #fff', minHeight: 40 }}>{label}</div>
  return (
    <div className="card card-pad stack" style={{ background: 'var(--surface-2)' }}>
      <div className="small faint">What the viewer's player receives</div>
      <div className="row" style={{ alignItems: 'stretch', borderRadius: 9, overflow: 'hidden' }}>
        {seg('Show', '#6366f1', 3)}
        {mode === 'SSAI' && <>{seg('Ad 30s', '#f59e0b', 1, 'a1')}{seg('Ad 30s', '#f97316', 1, 'a2')}{seg('Ad 15s + slate', '#fb923c', 1, 'a3')}</>}
        {mode === 'SGAI' && <div style={{ flex: 3, background: 'repeating-linear-gradient(135deg,#d1fae5,#d1fae5 6px,#a7f3d0 6px,#a7f3d0 12px)', display: 'grid', placeItems: 'center', fontSize: 11.5, fontWeight: 650, color: '#065f46', borderRight: '2px solid #fff' }}>📌 interstitial → player fetches ads</div>}
        {mode === 'CSAI' && <div style={{ flex: 3, background: '#e2e8f0', display: 'grid', placeItems: 'center', fontSize: 11.5, fontWeight: 650, color: '#334155', borderRight: '2px solid #fff' }}>⏸ player pauses, loads ads separately</div>}
        {seg('Show continues', '#6366f1', 3)}
      </div>
      <div className="small muted">{mode === 'SSAI' ? 'One continuous stream — the ads are just more video pieces in this viewer\'s own playlist.' : mode === 'SGAI' ? 'Same playlist for everyone; the ads come from a separate list the player requests at the break.' : 'Two separate video sources the player switches between.'}</div>
    </div>
  )
}

const TYPES: [string, string][] = [['0x10 / 0x11', 'Programme start / end'], ['0x22 / 0x23', 'Break start / end'], ['0x30 / 0x31', 'Provider advertisement start / end'],
  ['0x32 / 0x33', 'Distributor advertisement start / end'], ['0x34 / 0x35', 'Provider placement opportunity — a break WE fill'], ['0x36 / 0x37', 'Distributor placement opportunity — a break the PLATFORM fills']]

function ScteSection() {
  const [params] = useSearchParams()
  const [input, setInput] = useState(params.get('scte') ?? '')
  const [decoded, setDecoded] = useState<Record<string, unknown> | null>(null)
  const { busy, run } = useAction()
  const decode = (v = input) => run(() => api<Record<string, unknown>>('/api/tools/scte35/decode', { body: { value: v } })).then(r => r && setDecoded(r))
  useEffect(() => { if (params.get('scte')) decode(params.get('scte')!) }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const descs = (decoded?.descriptors as Record<string, unknown>[] | undefined) ?? []
  return (
    <section className="card card-pad stack" style={{ gap: 16 }}>
      <h2>4 · SCTE-35: the ad-break marker</h2>
      <p className="muted"><b>SCTE-35</b> is a tiny binary message from the cable-TV world that says <i>"a break starts here, it lasts 90 seconds, and it belongs to X."</i>
        Ad servers, stitchers and TV platforms all read it — so if the marker is wrong, ads don't play or the wrong party sells the break.</p>
      <div className="grid g2">
        <div className="stack">
          <h3>What's inside a marker</h3>
          <div className="kv small">
            <span className="mono">table_id</span><span>Always 0xFC — "this is SCTE-35"</span>
            <span className="mono">splice_command</span><span><b>time_signal</b> (modern) or splice_insert (older)</span>
            <span className="mono">pts_time</span><span>When, in 90,000 ticks per second</span>
            <span className="mono">segmentation_type</span><span>What kind of moment (see table)</span>
            <span className="mono">duration</span><span>How long the break lasts</span>
            <span className="mono">event_id</span><span>Pairs the start marker with its end marker</span>
            <span className="mono">CRC_32</span><span>Checksum so corrupted markers are rejected</span>
          </div>
          <h3 style={{ marginTop: 8 }}>Segmentation types that matter for FAST</h3>
          <table className="table small card"><tbody>{TYPES.map(([c, d]) => <tr key={c}><td className="mono">{c}</td><td>{d}</td></tr>)}</tbody></table>
        </div>
        <div className="stack">
          <h3>Where it appears in the stream</h3>
          <div className="m3u8" style={{ fontSize: 11.5 }}>
            <div className="t-com"># Modern HLS (carries the full binary)</div>
            <div className="t-scte">#EXT-X-DATERANGE:ID="brk-1",START-DATE="…",</div>
            <div className="t-scte">  PLANNED-DURATION=90.0,SCTE35-OUT=0xFC30…</div>
            <div className="t-com"># Older style some platforms still want</div>
            <div className="t-scte">#EXT-X-CUE-OUT:DURATION=90</div>
            <div className="t-scte">#EXT-X-CUE-OUT-CONT:ElapsedTime=30,Duration=90</div>
            <div className="t-scte">#EXT-X-CUE-IN</div>
          </div>
          <div className="callout"><Icon name="info" /><div className="small">FAST Studio generates these automatically for every ad break. Each destination chooses the format it needs.</div></div>
        </div>
      </div>
      <div className="card card-pad stack" style={{ background: 'var(--surface-2)' }}>
        <div className="between"><h3>SCTE-35 inspector</h3>
          <button className="btn sm" disabled={busy} onClick={() => run(() => api<{ hex: string }>('/api/tools/scte35/example')).then(r => { if (r) { setInput(r.hex); decode(r.hex) } })}>Use a freshly generated example</button></div>
        <p className="faint">Paste a marker (hex like <code>0xFC30…</code> or base64 like <code>/DA0…</code>) — or open <b>Watch live → Under the hood</b> and click "decode".</p>
        <div className="row"><input className="mono" value={input} onChange={e => setInput(e.target.value)} placeholder="0xFC302E…" aria-label="SCTE-35 marker" />
          <button className="btn primary" disabled={busy || !input} onClick={() => decode()}>Decode</button></div>
        {decoded && (
          <div className="grid g2">
            <div className="kv small">{Object.entries(decoded).filter(([k]) => k !== 'descriptors').map(([k, v]) => [<span key={k} className="mono">{k}</span>, <span key={k + 'v'}>{String(v)}</span>])}</div>
            <div className="stack">{descs.map((d, i) => (
              <div key={i} className="card card-pad small">
                <b>{String(d.segmentation_type ?? 'Descriptor')}</b>
                <div className="kv" style={{ marginTop: 6 }}>{Object.entries(d).map(([k, v]) => [<span key={k} className="mono">{k}</span>, <span key={k + 'v'}>{String(v)}</span>])}</div>
              </div>))}
              <div className={'callout ' + (decoded.crc_valid ? 'ok' : 'bad')}><Icon name={decoded.crc_valid ? 'check' : 'alert'} />{decoded.crc_valid ? 'Checksum valid — a player or platform would accept this marker.' : 'Checksum invalid — this marker would be rejected.'}</div>
            </div>
          </div>
        )}
      </div>
    </section>
  )
}

function Glossary() {
  const words: [string, string][] = [
    ['Ad break / pod', 'Time reserved for ads. A pod holds several ads back to back.'],
    ['Ad decision', 'Choosing which ads fill a break for a particular viewer.'],
    ['As-run', 'A record of what actually played, used to settle money with partners.'],
    ['Cue point', 'A moment inside a video where an ad break may start.'],
    ['CPM', 'Price an advertiser pays per 1,000 ads shown.'],
    ['EPG / XMLTV', 'The programme guide; XMLTV is a common file format for sending it to partners.'],
    ['Fill rate', 'How much of the available ad time was actually filled with ads.'],
    ['Geo-blocking', 'Stopping playback in countries where you don\'t hold the rights.'],
    ['HLS', 'The streaming format used here: a playlist of short video pieces.'],
    ['Impression', 'One ad started playing for one viewer.'],
    ['Inventory split', 'An agreement that some ad breaks are sold by you and others by the platform.'],
    ['Slate', 'A neutral "we\'ll be right back" screen used when nothing else is available.'],
    ['Tenant', 'One customer company. Tenants never see each other\'s data.'],
    ['VAST', 'The standard XML format an ad server uses to answer "which ad should play?".'],
  ]
  return (
    <section className="card card-pad stack">
      <h2>5 · Glossary</h2>
      <dl className="glossary grid g2" style={{ margin: 0 }}>{words.map(([w, d]) => <div key={w}><dt>{w}</dt><dd>{d}</dd></div>)}</dl>
    </section>
  )
}
