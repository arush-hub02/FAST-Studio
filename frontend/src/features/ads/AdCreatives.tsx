import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, type AssetView } from '../../shared/api'
import { useOrg } from '../../app/App'
import { Empty, fmtDur, Icon, InfoTip, Modal, Status, useAction, useApi, VideoPlayer } from '../../shared/ui'
import { AddVideoModal } from '../library/AddVideoModal'
import { AssetDrawer } from '../library/AssetDrawer'

type SlateView = AssetView & { nextSchedule?: boolean }

/** The project's own slate for unfilled ad time and other gaps. */
export function AdSlate() {
  const org = useOrg()
  const list = useApi<SlateView[]>(`/api/orgs/${org.slug}/slate`, [], 0)
  const { busy, run } = useAction()
  const [adding, setAdding] = useState(false)
  const slates = list.data ?? []
  const ready = slates.find(s => s.asset.status === 'READY')
  const newest = slates[0]
  const processing = newest && newest.asset.status !== 'READY' ? newest : null

  useEffect(() => {
    if (!processing || processing.asset.status === 'FAILED') return
    const t = setInterval(list.reload, 4000)
    return () => clearInterval(t)
  }, [processing, list.reload])

  const upload = async (file: File, nextSchedule: boolean) => {
    const form = new FormData()
    form.append('file', file)
    form.append('nextSchedule', String(nextSchedule))
    const result = await run(() => api<SlateView>(`/api/orgs/${org.slug}/slate`, { form }), nextSchedule
      ? 'Slate queued for the next schedule publish.' : 'Slate uploaded. It will switch live once ready.')
    if (result) {
      setAdding(false)
      list.reload()
    }
  }
  const remove = () => run(() => api(`/api/orgs/${org.slug}/slate`, { method: 'DELETE' }), 'Back to the platform standby screen').then(() => list.reload())
  const canEdit = org.can('ASSETS_EDIT')

  return (
    <div className="card card-pad stack" data-testid="ad-slate">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>Slate <InfoTip text="Shown on your channels when nothing else can air: unfilled ad time, gaps in the schedule, or when a programme can't be reached. Converted once into your quality levels and kept in R2 only." /></h3>
        {canEdit && <div className="row" style={{ gap: 8 }}>
          <button type="button" className="btn primary sm" disabled={busy} onClick={() => setAdding(true)}><Icon name="upload" size={14} />{ready ? 'Replace slate' : 'Upload slate'}</button>
          {slates.length > 0 && <button type="button" className="btn sm" disabled={busy} onClick={remove}>Use platform standby</button>}
        </div>}
      </div>
      {!list.data ? <span className="faint small">Loading…</span> : (
        <div className="grid g2" style={{ alignItems: 'start' }}>
          <div>
            {ready?.previewUrl ? <VideoPlayer src={ready.previewUrl} title="Your slate" />
              : <div className="player-msg" style={{ position: 'relative', aspectRatio: '16 / 9', borderRadius: 6 }}><div>Platform standby screen<div className="faint small">"We'll be right back"</div></div></div>}
          </div>
          <div className="stack small" style={{ gap: 6 }}>
            <div><b>Latest ready slate:</b> {ready ? fmtDur(ready.asset.durationMs) : 'Platform standby'}</div>
            {newest && <div><b>Apply:</b> {newest.nextSchedule ? 'Next schedule publish' : 'Live when ready'}</div>}
            {processing && <div className="row" style={{ gap: 6 }}><Status value={processing.asset.status} /><span className="faint">{processing.asset.statusDetail}</span></div>}
            <div className="faint">Use a short loop (10 to 60 seconds) without speech. It is stretched or looped to fill any gap.</div>
          </div>
        </div>
      )}
      {adding && <SlateUploadModal busy={busy} onClose={() => setAdding(false)} onSubmit={upload} />}
    </div>
  )
}

function SlateUploadModal({ busy, onClose, onSubmit }: {
  busy: boolean
  onClose: () => void
  onSubmit: (file: File, nextSchedule: boolean) => void
}) {
  const [file, setFile] = useState<File>()
  const [nextSchedule, setNextSchedule] = useState(false)
  return (
    <Modal title="Upload a slate" subtitle="Choose when channels start using this slate." onClose={onClose} footer={<>
      <button type="button" className="btn" onClick={onClose} disabled={busy}>Cancel</button>
      <button type="button" className="btn primary" disabled={busy || !file}
        onClick={() => file && onSubmit(file, nextSchedule)}>Upload slate</button>
    </>}>
      <label className="field">
        <span>Slate video</span>
        <input type="file" accept="video/*,.mkv,.m4v" onChange={e => setFile(e.target.files?.[0])} disabled={busy} />
      </label>
      <label className="field">
        <span>Apply slate</span>
        <select value={nextSchedule ? 'next' : 'live'} onChange={e => setNextSchedule(e.target.value === 'next')} disabled={busy}>
          <option value="live">Live when ready</option>
          <option value="next">Next schedule publish</option>
        </select>
        <small className="faint">The current slate continues until the new video is ready. Next schedule publish also waits for each channel to publish after this upload.</small>
      </label>
    </Modal>
  )
}

/** Ad creatives of the project: added here (never in the Library), converted once and kept by FAST Studio. */
export function AdCreatives() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const list = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets?kind=AD`, [], 0)
  const [adding, setAdding] = useState(false)
  const ads = (list.data ?? []).filter(a => a.asset.kind === 'AD')
  const open = ads.find(a => a.asset.id === params.get('creative'))
  const show = (id: string | null) => setParams(p => { const n = new URLSearchParams(p); if (id) n.set('creative', id); else n.delete('creative'); return n })

  return (
    <div className="card card-pad stack" data-testid="ad-creatives">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 style={{ margin: 0 }}>Ad creatives <InfoTip text="Your own ad videos for direct campaigns. They are converted once into your quality levels and kept by FAST Studio, not in your video library." /></h3>
        {org.can('ASSETS_EDIT') && <button type="button" className="btn primary sm" onClick={() => setAdding(true)}><Icon name="plus" size={14} />Add creative</button>}
      </div>
      {!list.data ? <span className="faint small">Loading…</span>
        : ads.length === 0 ? <Empty icon="megaphone" title="No ad creatives yet" text="Add a creative to book it in a direct campaign." />
          : <div className="grid g4">{ads.map(a => (
            <button key={a.asset.id} type="button" className="tpl-item card" style={{ padding: 10, textAlign: 'left' }} onClick={() => show(a.asset.id)}>
              <div style={{ aspectRatio: '16 / 9', borderRadius: 6, overflow: 'hidden', background: a.asset.color || '#1e1b4b', marginBottom: 8 }}>
                {a.thumbUrl && a.asset.status === 'READY' && <img src={a.thumbUrl} alt="" loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
              </div>
              <b className="small">{a.asset.title}</b>
              <div className="row" style={{ gap: 6, marginTop: 4 }}><Status value={a.asset.status} /><span className="faint small">{fmtDur(a.asset.durationMs)}</span></div>
            </button>))}</div>}
      {adding && <AddVideoModal scope="ads" onClose={() => setAdding(false)} onDone={id => { setAdding(false); list.reload(); show(id) }} />}
      {open && <AssetDrawer view={open} onClose={() => show(null)} onChange={list.reload} />}
    </div>
  )
}
