import { useState } from 'react'
import { api, type ChannelView, type Check } from '../api'
import { useOrg } from '../app'
import { ago, Empty, Icon, InfoTip, Loading, Modal, PageHead, Sim, Status, useAction, useApi } from '../ui'

type Destination = { id: string; name: string; kind: string; adMode: string; markerFormat: string; maxAdSecondsPerHour: number; notes: string }
type Publication = { id: string; channel: string; destination: string; versionNumber?: number; status: string; message: string; simulated: boolean; createdAt: string }
const MODE: Record<string, string> = { SSAI: 'Server stitches ads (SSAI)', SGAI: 'Player fetches ads (SGAI)', PASSTHROUGH: 'Markers only — platform sells ads' }

export default function Distribution() {
  const org = useOrg()
  const data = useApi<{ destinations: Destination[]; publications: Publication[] }>(`/api/orgs/${org.slug}/destinations`)
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const [sending, setSending] = useState<Destination | null>(null)
  const [adding, setAdding] = useState(false)
  return (
    <div className="page">
      <PageHead title="Distribution" tip="FAST channels reach viewers through your own apps and through partner platforms such as smart-TV channel guides. Each partner has its own technical rules."
        subtitle="Where your channels go. Before sending, we check the channel against the destination's rules — ad limits, markers, guide data."
        actions={org.can('DISTRIBUTION_EDIT') && <button className="btn" onClick={() => setAdding(true)}><Icon name="plus" />Add destination</button>} />
      <div className="callout sim"><Icon name="sparkle" /><div><b>Honest labelling:</b> the web player destinations are real (this prototype streams them). Partner TV platforms are <b>simulated</b> — the checks are real, but no partner is contacted and nothing is certified.</div></div>
      {!data.data ? <Loading /> : (
        <div className="grid g2">
          {data.data.destinations.map(d => (
            <section key={d.id} className="card card-pad stack">
              <div className="between">
                <div className="row"><div className="empty-icon" style={{ width: 40, height: 40 }}><Icon name={d.kind === 'OWNED_WEB' ? 'play' : 'tv'} /></div>
                  <div><h3>{d.name}</h3><div className="faint">{d.kind === 'OWNED_WEB' ? 'Your own app' : 'Partner platform'}</div></div></div>
                {d.kind === 'PARTNER_TV' ? <Sim /> : <span className="badge ok">Real (prototype)</span>}
              </div>
              <p className="muted small">{d.notes}</p>
              <div className="kv">
                <span>Ads</span><span>{MODE[d.adMode]}</span>
                <span className="row">Marker format <InfoTip text="How ad breaks are signalled in the stream. DATERANGE carries the full SCTE-35 binary; CUE-OUT/CUE-IN is an older, simpler style some platforms still require." /></span>
                <span className="mono">{d.adMode === 'SGAI' ? 'HLS interstitial' : d.markerFormat === 'CUE_OUT' ? '#EXT-X-CUE-OUT / CUE-IN' : '#EXT-X-DATERANGE + SCTE-35'}</span>
                <span>Ad limit</span><span>{Math.round(d.maxAdSecondsPerHour / 60)} min per hour</span>
              </div>
              {org.can('DISTRIBUTION_EDIT') && <button className="btn primary sm" style={{ alignSelf: 'start' }} onClick={() => setSending(d)}><Icon name="send" size={15} />Send a channel</button>}
            </section>
          ))}
        </div>
      )}
      <section className="card">
        <div className="card-head"><div><h2>Delivery history</h2><p>Every attempt is recorded, including failures</p></div></div>
        {!data.data ? <div className="card-pad"><Loading /></div> : data.data.publications.length === 0 ? <Empty icon="send" title="Nothing sent yet" text="Send a live channel to a destination to see the checks run." /> : (
          <table className="table"><thead><tr><th>When</th><th>Channel</th><th>Destination</th><th>Result</th><th>Details</th></tr></thead>
            <tbody>{data.data.publications.map(p => (
              <tr key={p.id}><td className="small">{ago(p.createdAt)}</td><td><b>{p.channel}</b>{p.versionNumber && <div className="faint">schedule v{p.versionNumber}</div>}</td>
                <td>{p.destination}</td><td><Status value={p.status} /> {p.simulated && <span className="badge sim">sim</span>}</td><td className="small muted">{p.message}</td></tr>
            ))}</tbody></table>
        )}
      </section>
      {sending && <SendModal dest={sending} channels={channels.data ?? []} onClose={() => setSending(null)} onDone={data.reload} />}
      {adding && <AddDestination onClose={() => setAdding(false)} onDone={() => { setAdding(false); data.reload() }} />}
    </div>
  )
}

function SendModal({ dest, channels, onClose, onDone }: { dest: Destination; channels: ChannelView[]; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const [channelId, setChannelId] = useState(channels.find(c => c.channel.status === 'LIVE')?.channel.id ?? channels[0]?.channel.id ?? '')
  const [result, setResult] = useState<{ publication: Publication; checks: Check[] } | null>(null)
  const { busy, run } = useAction()
  return (
    <Modal title={`Send to ${dest.name}`} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Close</button>
      <button className="btn primary" disabled={busy || !channelId} onClick={() => run(() => api<{ publication: Publication; checks: Check[] }>(`/api/orgs/${org.slug}/channels/${channelId}/distribute/${dest.id}`, { method: 'POST' }))
        .then(r => { if (r) { setResult(r); onDone() } })}>{busy ? <span className="spinner" /> : 'Run checks & send'}</button></>}>
      <label className="field">Channel<select value={channelId} onChange={e => { setChannelId(e.target.value); setResult(null) }}>
        {channels.map(c => <option key={c.channel.id} value={c.channel.id}>{c.channel.name} ({c.channel.status.toLowerCase()})</option>)}</select></label>
      {result && <>
        <div className="stack" style={{ gap: 6 }}>{result.checks.map((c, i) => (
          <div key={i} className={'callout ' + (c.passed ? 'ok' : 'bad')} style={{ padding: '8px 12px' }}><Icon name={c.passed ? 'check' : 'x'} /><div className="small"><b>{c.label}</b><div>{c.detail}</div></div></div>
        ))}</div>
        <div className={'callout ' + (result.publication.status === 'SUCCEEDED' ? (result.publication.simulated ? 'sim' : 'ok') : 'warn')}><Icon name="info" /><div>{result.publication.message}</div></div>
      </>}
    </Modal>
  )
}

function AddDestination({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const { busy, run } = useAction()
  const [f, setF] = useState({ name: '', kind: 'PARTNER_TV', adMode: 'PASSTHROUGH', markerFormat: 'DATERANGE', maxAdSecondsPerHour: 600, notes: '' })
  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  return (
    <Modal title="Add a destination" onClose={onClose} footer={<><button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" disabled={busy || !f.name} onClick={() => run(() => api(`/api/orgs/${org.slug}/destinations`, { body: { ...f, maxAdSecondsPerHour: +f.maxAdSecondsPerHour } }), 'Destination added').then(r => r !== undefined && onDone())}>Add</button></>}>
      <label className="field">Name<input value={f.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Acme TV+ (simulated)" /></label>
      <div className="grid g2">
        <label className="field">Type<select value={f.kind} onChange={e => set('kind', e.target.value)}><option value="PARTNER_TV">Partner platform (simulated)</option><option value="OWNED_WEB">Our own web player</option></select></label>
        <label className="field">How ads work<select value={f.adMode} onChange={e => set('adMode', e.target.value)}><option value="PASSTHROUGH">Markers only</option><option value="SSAI">SSAI</option><option value="SGAI">SGAI</option></select></label>
        <label className="field">Marker format<select value={f.markerFormat} onChange={e => set('markerFormat', e.target.value)}><option value="DATERANGE">DATERANGE + SCTE-35</option><option value="CUE_OUT">CUE-OUT / CUE-IN</option></select></label>
        <label className="field">Max ad seconds per hour<input type="number" value={f.maxAdSecondsPerHour} onChange={e => set('maxAdSecondsPerHour', e.target.value)} /></label>
      </div>
      <label className="field">Notes<input value={f.notes} onChange={e => set('notes', e.target.value)} /></label>
    </Modal>
  )
}
