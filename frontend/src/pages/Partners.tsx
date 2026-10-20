import { useState } from 'react'
import { api, SCOPES, type ChannelView } from '../api'
import { useOrg } from '../app'
import { ago, Confirm, Empty, Icon, Loading, Modal, PageHead, Status, useAction, useApi } from '../ui'

type Named = { id: string; name: string }
type Sharing = {
  id: string; partnerId: string; partnerName: string; status: string; scopes: string[]; allChannels: boolean; channels: Named[]
  destinationId?: string; destinationName?: string; note: string; createdAt: string; acceptedAt?: string; revokedAt?: string
  expiresAt?: string; readsLast7Days: number; lastReadAt?: string
}
type Directory = { partners: { id: string; name: string; country: string }[] }
type Destination = { id: string; name: string; kind: string; adMode: string }

/** Customer side: share channels with TV platforms and apps (partner organizations), read-only. */
export default function Partners() {
  const org = useOrg()
  const list = useApi<Sharing[]>(`/api/orgs/${org.slug}/partners`)
  const [editing, setEditing] = useState<Sharing | 'new' | null>(null)
  const [stopping, setStopping] = useState<Sharing | null>(null)
  const { run } = useAction()
  const manage = org.can('DISTRIBUTION_EDIT')
  return (
    <div className="page">
      <PageHead title="Partners" tip="A partner is another company on FAST Studio that carries your channels: a smart-TV channel app, a streaming service or an ad partner."
        subtitle="Share channels with TV platforms and apps. They see only what you choose, read-only, and you can stop at any time."
        actions={manage && <button className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" />Share with a partner</button>} />
      <div className="callout"><Icon name="link" /><div><b>How sharing works:</b> pick a partner, the channels and what they may use: guide, stream set-up, ad-break times, viewing numbers. The partner accepts, then their people and systems read it directly. Nothing else of yours is visible to them: not your videos, licences, campaigns, money or team. Every read appears in your <i>Activity log</i>.</div></div>
      {!list.data ? <Loading /> : list.data.length === 0 ? (
        <Empty icon="link" title="You don't share with any partner yet" text="Share a channel with a TV platform so it can show your programme guide and stream your channel with the right ad set-up."
          action={manage && <button className="btn primary" onClick={() => setEditing('new')}><Icon name="plus" />Share with a partner</button>} />
      ) : (
        <div className="stack">{list.data.map(s => (
          <section key={s.id} className="card card-pad stack" style={{ opacity: s.status === 'REVOKED' ? 0.6 : 1 }}>
            <div className="between">
              <div className="row"><div className="empty-icon" style={{ width: 40, height: 40 }}><Icon name="tv" /></div>
                <div><h3>{s.partnerName}</h3><div className="faint small">{s.status === 'ACTIVE' ? `Accepted ${ago(s.acceptedAt!)}` : s.status === 'INVITED' ? `Shared ${ago(s.createdAt)}, waiting for them to accept` : `Stopped ${ago(s.revokedAt!)}`}</div></div></div>
              <Status value={s.status} />
            </div>
            <div className="kv">
              <span>Channels</span><span className="row-wrap">{s.allChannels ? <span className="chip">All channels, also future ones</span> : s.channels.map(c => <span key={c.id} className="chip">{c.name}</span>)}</span>
              <span>They may use</span><span className="row-wrap">{s.scopes.map(k => <span key={k} className="chip" title={SCOPES[k]?.meaning}>{SCOPES[k]?.label ?? k}</span>)}</span>
              <span>Delivery settings</span><span>{s.destinationName ?? <span className="faint">None: no stream set-up or viewing numbers</span>}</span>
              <span>Their activity</span><span>{s.readsLast7Days} reads in the last 7 days{s.lastReadAt && <> · last {ago(s.lastReadAt)}</>}</span>
              {s.note && <><span>Note</span><span>{s.note}</span></>}
            </div>
            {manage && s.status !== 'REVOKED' && <div className="row">
              <button className="btn sm" onClick={() => setEditing(s)}>Change what is shared</button>
              <button className="btn sm ghost danger" onClick={() => setStopping(s)}>Stop sharing</button></div>}
          </section>
        ))}</div>
      )}
      {editing && <ShareModal sharing={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); list.reload() }} />}
      {stopping && <Confirm danger title={`Stop sharing with ${stopping.partnerName}?`} confirmLabel="Stop sharing"
        text="They lose access on their very next request: guide, stream set-up and numbers. Streams already playing to their viewers are not cut, but they can't start new ones through this sharing."
        onClose={() => setStopping(null)} onConfirm={() => run(() => api(`/api/orgs/${org.slug}/partners/${stopping.id}/revoke`, { method: 'POST' }), 'Sharing stopped').then(list.reload)} />}
    </div>
  )
}

function ShareModal({ sharing, onClose, onDone }: { sharing: Sharing | null; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const dir = useApi<Directory>(sharing ? null : `/api/orgs/${org.slug}/partners/directory`)
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const dests = useApi<{ destinations: Destination[] }>(`/api/orgs/${org.slug}/destinations`)
  const [partner, setPartner] = useState(sharing?.partnerId ?? '')
  const [all, setAll] = useState(sharing?.allChannels ?? false)
  const [chosen, setChosen] = useState<string[]>(sharing?.channels.map(c => c.id) ?? [])
  const [scopes, setScopes] = useState<string[]>(sharing?.scopes ?? ['channels:read', 'epg:read'])
  const [dest, setDest] = useState(sharing?.destinationId ?? '')
  const [note, setNote] = useState(sharing?.note ?? '')
  const { busy, run } = useAction()
  const toggle = (list: string[], v: string) => list.includes(v) ? list.filter(x => x !== v) : [...list, v]
  const needsDest = scopes.includes('stream:read') || scopes.includes('delivery:read')
  const body = { partnerOrgId: partner, allChannels: all, channelIds: chosen, scopes, destinationId: dest || null, note }
  const ok = (sharing || partner) && scopes.length > 0 && (all || chosen.length > 0)
  return (
    <Modal wide title={sharing ? `What ${sharing.partnerName} may use` : 'Share with a partner'} subtitle="Read-only. You can change or stop it at any time." onClose={onClose}
      footer={<><button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" disabled={busy || !ok} onClick={() => run(() => sharing
          ? api(`/api/orgs/${org.slug}/partners/${sharing.id}`, { method: 'PUT', body })
          : api(`/api/orgs/${org.slug}/partners`, { body }), sharing ? 'Sharing updated' : 'Shared: the partner has been emailed to accept').then(r => r && onDone())}>
          {sharing ? 'Save changes' : 'Share'}</button></>}>
      {!sharing && <label className="field">Partner<span className="hint">Partner companies on FAST Studio: TV platforms, apps, ad partners</span>
        <select value={partner} onChange={e => setPartner(e.target.value)}>
          <option value="">Choose a partner…</option>
          {dir.data?.partners.map(p => <option key={p.id} value={p.id}>{p.name} ({p.country})</option>)}
        </select></label>}
      <div className="field">Channels
        <label className="row"><input type="radio" style={{ width: 'auto', minHeight: 0 }} checked={!all} onChange={() => setAll(false)} />Only the channels I pick</label>
        {!all && <div className="row-wrap" style={{ paddingLeft: 26 }}>{channels.data?.map(v => (
          <label key={v.channel.id} className="chip" style={{ cursor: 'pointer', padding: '4px 10px' }}>
            <input type="checkbox" style={{ width: 'auto', minHeight: 0, marginRight: 6 }} checked={chosen.includes(v.channel.id)} onChange={() => setChosen(toggle(chosen, v.channel.id))} />{v.channel.name}</label>))}</div>}
        <label className="row"><input type="radio" style={{ width: 'auto', minHeight: 0 }} checked={all} onChange={() => setAll(true)} />All my channels, including ones I create later</label>
      </div>
      <div className="field">What they may use
        <div className="stack" style={{ gap: 6 }}>{Object.entries(SCOPES).map(([k, s]) => (
          <label key={k} className="row" style={{ alignItems: 'flex-start' }}>
            <input type="checkbox" style={{ width: 'auto', minHeight: 0, marginTop: 3 }} checked={scopes.includes(k)} onChange={() => setScopes(toggle(scopes, k))} />
            <span><b>{s.label}</b><span className="faint"> · {s.meaning}</span></span></label>))}</div>
      </div>
      <label className="field">Delivery settings for this partner<span className="hint">Which of your destinations is this platform? Its ad mode and markers are used for their streams, and only its viewers are counted.</span>
        <select value={dest} onChange={e => setDest(e.target.value)}>
          <option value="">None</option>
          {dests.data?.destinations.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select></label>
      {needsDest && !dest && <div className="callout warn"><Icon name="info" /><div className="small">Stream set-up and viewing numbers need delivery settings. Without them the partner sees "not set up yet".</div></div>}
      <label className="field">Note for the record (optional)<input value={note} onChange={e => setNote(e.target.value)} placeholder="e.g. Pilot agreement, 3 months" /></label>
    </Modal>
  )
}
