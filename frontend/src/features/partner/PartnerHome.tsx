import { useState } from 'react'
import { api, SCOPES } from '../../shared/api'
import { useOrg } from '../../app/App'
import { ago, COUNTRIES, Drawer, Empty, fmtTime, Icon, Loading, PageHead, Status, useAction, useApi, VideoPlayer } from '../../shared/ui'

type Named = { id: string; name: string }
type Shared = {
  id: string; ownerId: string; ownerSlug: string; ownerName: string; status: string; scopes: string[]; allChannels: boolean; channels: Named[]
  destination?: { name: string; adMode: string; markerFormat: string }; note: string; createdAt: string; acceptedAt?: string
}

/** Partner portal: what content owners share with this partner organization. */
export default function PartnerHome() {
  const org = useOrg()
  const links = useApi<Shared[]>(`/api/partners/${org.slug}/links`)
  const [open, setOpen] = useState<{ link: Shared; channel: Named } | null>(null)
  const { busy, run } = useAction()
  return (
    <div className="page">
      <PageHead title="Shared with us" tip="Content owners on FAST Studio share channels with your platform. You can read what they share, nothing else, and they can stop at any time."
        subtitle={`Channels that content owners share with ${org.name}. Everything here is read-only.`} />
      {!links.data ? <Loading /> : links.data.length === 0 ? (
        <Empty icon="link" title="Nothing shared with you yet" text="When a content owner shares channels with you, they appear here and your admins get an email." />
      ) : links.data.map(l => (
        <section key={l.id} className="card card-pad stack">
          <div className="between">
            <div><h2>{l.ownerName}</h2><div className="faint small">{l.status === 'ACTIVE' ? `Active since ${ago(l.acceptedAt!)}` : `Shared ${ago(l.createdAt)}`}{l.note && ` · ${l.note}`}</div></div>
            <Status value={l.status} />
          </div>
          <div className="row-wrap">{l.scopes.map(k => <span key={k} className="chip" title={SCOPES[k]?.meaning}><Icon name="check" size={12} />{SCOPES[k]?.label ?? k}</span>)}
            {Object.keys(SCOPES).filter(k => !l.scopes.includes(k)).map(k => <span key={k} className="chip faint" title="Not shared with you">{SCOPES[k].label}: not shared</span>)}</div>
          {l.status === 'INVITED' ? (
            <div className="callout warn"><Icon name="info" /><div className="stack" style={{ gap: 8 }}>
              <span><b>{l.ownerName}</b> wants to share {l.allChannels ? 'all its channels' : l.channels.map(c => c.name).join(', ')} with you. Accept to start using them.</span>
              {org.can('PARTNER_MANAGE') ? <button className="btn primary sm" style={{ alignSelf: 'start' }} disabled={busy}
                onClick={() => run(() => api(`/api/partners/${org.slug}/links/${l.id}/accept`, { method: 'POST' }), 'Accepted').then(links.reload)}>Accept</button>
                : <span className="faint small">A partner admin of {org.name} can accept.</span>}
            </div></div>
          ) : (
            <div className="grid g3">{l.channels.map(c => (
              <button key={c.id} className="tpl-item card" style={{ padding: 14 }} onClick={() => setOpen({ link: l, channel: c })}>
                <span className="row"><Icon name="tv" /><b>{c.name}</b></span>
                <span className="faint small">Open guide, stream set-up and more</span>
              </button>))}</div>
          )}
          {l.destination && l.status === 'ACTIVE' && <div className="faint small">Your delivery settings: {l.destination.name} · ads {l.destination.adMode === 'PASSTHROUGH' ? 'sold by you (markers only)' : l.destination.adMode} · markers {l.destination.markerFormat === 'CUE_OUT' ? 'CUE-OUT/CUE-IN' : 'DATERANGE + SCTE-35'}</div>}
        </section>
      ))}
      {open && <ChannelDrawer link={open.link} channel={open.channel} onClose={() => setOpen(null)} />}
    </div>
  )
}

type Programme = { title: string; genre: string; start: string; end: string; onNow: boolean; adBreaks: number }
type Stream = { howTo: string; method: string; url: string; body: Record<string, string>; destination: { name: string; adMode: string; markerFormat: string } }
type Break = { id: string; start: string; end: string; durationMs: number; ownership: string; scte35Out: string }
type Delivery = { destination?: string; days: number; viewerSessions: number; adImpressions: number; adCompletes: number; note: string }

const TABS: [string, string][] = [['epg:read', 'Guide'], ['stream:read', 'Stream'], ['markers:read', 'Ad breaks'], ['delivery:read', 'Numbers']]

function ChannelDrawer({ link, channel, onClose }: { link: Shared; channel: Named; onClose: () => void }) {
  const org = useOrg()
  const allowed = TABS.filter(([k]) => link.scopes.includes(k))
  const [tab, setTab] = useState(allowed[0]?.[0] ?? '')
  const base = `/api/partners/${org.slug}/sources/${link.ownerId}/channels/${channel.id}`
  return (
    <Drawer onClose={onClose}>
      <div className="modal-head" style={{ padding: '18px 22px' }}>
        <div><h2>{channel.name}</h2><p className="faint">Shared by {link.ownerName} · read-only</p></div>
        <button className="btn ghost icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button>
      </div>
      <div className="tabs" style={{ padding: '0 22px' }}>{TABS.map(([k, label]) => (
        <button key={k} className={'tab' + (tab === k ? ' active' : '')} disabled={!link.scopes.includes(k)} onClick={() => setTab(k)}
          title={link.scopes.includes(k) ? '' : 'Not shared with you'}>{label}{!link.scopes.includes(k) && ' 🔒'}</button>))}</div>
      <div className="modal-body">
        {tab === 'epg:read' && <GuideTab base={base} />}
        {tab === 'stream:read' && <StreamTab base={base} />}
        {tab === 'markers:read' && <BreaksTab base={base} />}
        {tab === 'delivery:read' && <DeliveryTab base={base} />}
        {!tab && <Empty icon="shield" title="Only the channel name is shared" text="Ask the content owner to share the guide or stream set-up." />}
      </div>
    </Drawer>
  )
}

function GuideTab({ base }: { base: string }) {
  const g = useApi<Programme[]>(`${base}/epg?hours=12`)
  return !g.data ? <Loading /> : <>
    <div className="between"><span className="faint small">Next 12 hours · also as XMLTV for your guide system</span>
      <a className="btn sm" href={`${base}/epg.xml`} target="_blank" rel="noreferrer"><Icon name="code" size={14} />XMLTV</a></div>
    <table className="table"><thead><tr><th>Time</th><th>Programme</th><th>Ad breaks</th></tr></thead>
      <tbody>{g.data.map(p => (
        <tr key={p.start}><td className="small">{fmtTime(p.start)}–{fmtTime(p.end)}{p.onNow && <span className="badge live" style={{ marginLeft: 6 }}>Now</span>}</td>
          <td><b>{p.title}</b><div className="faint small">{p.genre}</div></td><td className="small">{p.adBreaks}</td></tr>))}</tbody></table>
  </>
}

function StreamTab({ base }: { base: string }) {
  const s = useApi<Stream>(`${base}/stream`)
  const [country, setCountry] = useState('IN')
  const [playlist, setPlaylist] = useState<string | null>(null)
  const { busy, run } = useAction()
  if (s.error) return <div className="callout warn"><Icon name="info" />{s.error.message}</div>
  if (!s.data) return <Loading />
  return <div className="stack">
    <div className="callout"><Icon name="play" /><div className="small">{s.data.howTo} Your settings: <b>{s.data.destination.name}</b>, ads {s.data.destination.adMode === 'PASSTHROUGH' ? 'sold by you: the stream carries markers only' : s.data.destination.adMode}.</div></div>
    <pre className="mono" style={{ background: '#0f172a', color: '#e2e8f0', padding: 12, borderRadius: 10, whiteSpace: 'pre-wrap', margin: 0 }}>{`${s.data.method} ${s.data.url}\n${JSON.stringify(s.data.body, null, 2)}`}</pre>
    <div className="row-wrap">
      <select value={country} onChange={e => setCountry(e.target.value)} style={{ width: 200 }} aria-label="Viewer country">{Object.entries(COUNTRIES).map(([c, n]) => <option key={c} value={c}>{n}</option>)}</select>
      <button className="btn primary" disabled={busy} onClick={() => run(() => api<{ playlistUrl: string }>('/play/sessions', { body: { ...s.data!.body, country } })).then(r => r && setPlaylist(r.playlistUrl))}>
        <Icon name="play" size={16} />Try it as a viewer</button>
    </div>
    {playlist && <VideoPlayer key={playlist} src={playlist} live />}
  </div>
}

function BreaksTab({ base }: { base: string }) {
  const b = useApi<Break[]>(`${base}/breaks?hours=6`)
  return !b.data ? <Loading /> : b.data.length === 0 ? <Empty icon="clock" title="No ad breaks in the next 6 hours" /> : <>
    <p className="faint small">Upcoming ad breaks with their SCTE-35 cue-out marker (hex), so your ad system can prepare.</p>
    <table className="table"><thead><tr><th>Starts</th><th>Length</th><th>SCTE-35 (cue out)</th></tr></thead>
      <tbody>{b.data.map(x => (
        <tr key={x.id}><td className="small">{fmtTime(x.start)}</td><td className="small">{Math.round(x.durationMs / 1000)} s</td>
          <td className="mono small" style={{ wordBreak: 'break-all' }} title={x.scte35Out}>{x.scte35Out.slice(0, 34)}…</td></tr>))}</tbody></table>
  </>
}

function DeliveryTab({ base }: { base: string }) {
  const d = useApi<Delivery>(`${base}/delivery?days=7`)
  return !d.data ? <Loading /> : <div className="stack">
    <div className="grid g3">
      <div className="card card-pad"><div className="faint small">Viewers (sessions)</div><div style={{ fontSize: 26, fontWeight: 700 }}>{d.data.viewerSessions}</div></div>
      <div className="card card-pad"><div className="faint small">Ads shown</div><div style={{ fontSize: 26, fontWeight: 700 }}>{d.data.adImpressions}</div></div>
      <div className="card card-pad"><div className="faint small">Ads completed</div><div style={{ fontSize: 26, fontWeight: 700 }}>{d.data.adCompletes}</div></div>
    </div>
    <p className="faint small">Last {d.data.days} days. {d.data.note}</p>
  </div>
}
