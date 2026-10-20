import { useEffect, useState } from 'react'
import { api, type ChannelView, type Programme } from '../api'
import { useOrg } from '../app'
import { ChannelLogo, Empty, fmtDayTime, fmtDur, fmtTime, Icon, Loading, Modal, PageHead, useApi } from '../ui'

const HOURS = 3, PX = 760   // 3-hour window; wide scale because demo programmes are only minutes long

export default function Guide() {
  const org = useOrg()
  const channels = useApi<ChannelView[]>(`/api/orgs/${org.slug}/channels`)
  const published = channels.data?.filter(c => c.channel.publishedVersionId) ?? []
  const [offset, setOffset] = useState(0)
  const [guides, setGuides] = useState<Record<string, Programme[]>>({})
  const [open, setOpen] = useState<{ p: Programme; ch: string } | null>(null)
  const from = new Date(Math.floor(Date.now() / 1_800_000) * 1_800_000 + offset * 3_600_000)

  useEffect(() => {
    published.forEach(c => api<Programme[]>(`/api/orgs/${org.slug}/channels/${c.channel.id}/epg?from=${from.toISOString()}&hours=${HOURS}`)
      .then(g => setGuides(x => ({ ...x, [c.channel.id]: g }))).catch(() => {}))
  }, [channels.data, offset]) // eslint-disable-line react-hooks/exhaustive-deps

  const t0 = from.getTime(), t1 = t0 + HOURS * 3_600_000
  const px = (t: number) => ((Math.min(Math.max(t, t0), t1) - t0) / 3_600_000) * PX
  return (
    <div className="page">
      <PageHead title="TV guide" tip="The EPG (electronic programme guide) is generated from the published schedule, so it can never disagree with what actually plays."
        subtitle="What viewers see in their channel guide. Partners receive the same data as an XMLTV feed."
        actions={<>
          <button className="btn" onClick={() => setOffset(o => o - 2)}>← Earlier</button>
          <button className="btn" onClick={() => setOffset(0)}>Now</button>
          <button className="btn" onClick={() => setOffset(o => o + 2)}>Later →</button>
        </>} />
      {!channels.data ? <Loading /> : published.length === 0 ? <div className="card"><Empty icon="grid" title="Nothing published yet" text="Publish a channel's schedule and its guide appears here." /></div> : (
        <div style={{ overflowX: 'auto' }}>
          <div className="epg" style={{ minWidth: 200 + HOURS * PX }}>
            <div className="epg-ch" style={{ height: 34, borderTop: 0 }}><b className="small">{fmtDayTime(from.toISOString()).split(',')[0]}</b></div>
            <div className="epg-row" style={{ height: 34, borderTop: 0 }}>
              {Array.from({ length: HOURS * 2 }, (_, i) => <div key={i} className="tl-tick" style={{ left: i * PX / 2 }}>{fmtTime(new Date(t0 + i * 1_800_000).toISOString())}</div>)}
              {Date.now() > t0 && Date.now() < t1 && <div className="tl-now" style={{ left: px(Date.now()), top: 0 }} />}
            </div>
            {published.map(c => [
              <div key={c.channel.id + 'h'} className="epg-ch"><ChannelLogo name={c.channel.name} color={c.channel.color} size={34} />
                <div className="grow"><b className="ellipsis small" style={{ display: 'block' }}>{c.channel.name}</b>
                  <a className="faint" href={`/api/orgs/${org.slug}/channels/${c.channel.id}/epg.xml`} download={`${c.channel.slug}.xml`}>XMLTV ↓</a></div></div>,
              <div key={c.channel.id + 'r'} className="epg-row">
                {(guides[c.channel.id] ?? []).map(p => {
                  const s = new Date(p.start).getTime(), e = new Date(p.end).getTime()
                  if (e <= t0 || s >= t1) return null
                  const left = px(s), w = px(e) - left
                  return (
                    <button key={p.start} className={'epg-prog' + (p.now ? ' now' : '')} style={{ left: left + 2, width: Math.max(w - 4, 4), font: 'inherit', textAlign: 'left' }}
                      onClick={() => setOpen({ p, ch: c.channel.name })}>
                      {w > 50 && <><b className="ellipsis small" style={{ display: 'block' }}>{p.title}</b>
                        <span className="faint ellipsis" style={{ display: 'block' }}>{fmtTime(p.start)} – {fmtTime(p.end)}</span></>}
                    </button>
                  )
                })}
              </div>,
            ])}
          </div>
        </div>
      )}
      {open && <Modal title={open.p.title} subtitle={`${open.ch} · ${fmtDayTime(open.p.start)} – ${fmtTime(open.p.end)}`} onClose={() => setOpen(null)}>
        {open.p.thumbUrl && <div className="thumb"><img src={open.p.thumbUrl} alt="" /></div>}
        <p>{open.p.description}</p>
        <div className="kv"><span>Genre</span><span>{open.p.genre}</span><span>Running time</span><span>{fmtDur(new Date(open.p.end).getTime() - new Date(open.p.start).getTime())} including {open.p.adBreaks} ad break(s)</span></div>
        {open.p.now && <div className="callout ok"><Icon name="play" />On air right now</div>}
      </Modal>}
    </div>
  )
}
