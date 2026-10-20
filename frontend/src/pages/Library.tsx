import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import Hls from 'hls.js'
import { api, type AssetView } from '../api'
import { useOrg } from '../app'
import { Confirm, Drawer, Empty, ErrorBox, fmtDur, Icon, InfoTip, Loading, Modal, PageHead, Status, useAction, useApi } from '../ui'

const KINDS = [['', 'All'], ['MOVIE', 'Movies'], ['EPISODE', 'Episodes'], ['SHORT', 'Shorts'], ['AD', 'Ad creatives']] as const
const KIND_LABEL: Record<string, string> = { MOVIE: 'Movie', EPISODE: 'Episode', SHORT: 'Short', AD: 'Ad', SLATE: 'Slate' }

export default function Library() {
  const org = useOrg()
  const [params, setParams] = useSearchParams()
  const [kind, setKind] = useState('')
  const [q, setQ] = useState('')
  const [adding, setAdding] = useState(false)
  const list = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets?kind=${kind}&q=${encodeURIComponent(q)}`, [], 0)
  const processing = list.data?.some(a => a.asset.status === 'PROCESSING')
  useEffect(() => {
    if (!processing) return
    const t = setInterval(list.reload, 4000)
    return () => clearInterval(t)
  }, [processing, list.reload])
  const openId = params.get('asset')
  const open = list.data?.find(a => a.asset.id === openId)

  return (
    <div className="page">
      <PageHead title="Library" subtitle="Every programme and ad creative your channels can use. Videos are converted into 3-second pieces so they can be stitched into a live channel."
        actions={org.can('ASSETS_EDIT') && <button className="btn primary" onClick={() => setAdding(true)}><Icon name="plus" />Add video</button>} />
      <div className="between">
        <div className="seg" role="tablist">
          {KINDS.map(([k, l]) => <button key={k} className={kind === k ? 'active' : ''} onClick={() => setKind(k)}>{l}</button>)}
        </div>
        <label className="row" style={{ width: 300 }}><Icon name="search" />
          <input placeholder="Search titles, genres, series…" value={q} onChange={e => setQ(e.target.value)} aria-label="Search videos" /></label>
      </div>
      {processing && <div className="callout"><span className="spinner" /><div>Some videos are being processed right now (real ffmpeg conversion). This page refreshes by itself.</div></div>}
      {list.error ? <ErrorBox error={list.error} retry={list.reload} /> : !list.data ? <Loading rows={4} /> : list.data.length === 0 ? (
        <div className="card"><Empty icon="film" title="No videos here yet" text="Add a video file, or generate a sample programme to try things out — no file needed."
          action={org.can('ASSETS_EDIT') && <button className="btn primary" onClick={() => setAdding(true)}><Icon name="plus" />Add video</button>} /></div>
      ) : (
        <div className="grid g4">
          {list.data.map(a => (
            <button key={a.asset.id} className="card" style={{ padding: 10, textAlign: 'left', cursor: 'pointer', font: 'inherit' }}
              onClick={() => setParams({ asset: a.asset.id })}>
              <div className="thumb" style={{ background: a.asset.color }}>
                {a.thumbUrl && a.asset.status === 'READY' ? <img src={a.thumbUrl} alt="" loading="lazy" /> : <Icon name="film" size={30} />}
                <span className="overlay-badge"><Status value={a.asset.status} /></span>
                {a.asset.durationMs > 0 && <span className="dur">{fmtDur(a.asset.durationMs)}</span>}
              </div>
              <div style={{ padding: '10px 4px 4px' }} className="stack">
                <b className="ellipsis">{a.asset.title}</b>
                <div className="between"><span className="faint">{KIND_LABEL[a.asset.kind]} · {a.asset.genre || '—'}</span>
                  {a.asset.kind !== 'AD' && <Status value={a.rightsStatus} />}</div>
              </div>
            </button>
          ))}
        </div>
      )}
      {adding && <AddVideo onClose={() => setAdding(false)} onDone={id => { setAdding(false); list.reload(); setParams({ asset: id }) }} />}
      {open && <AssetDrawer view={open} onClose={() => setParams({})} onChange={list.reload} />}
    </div>
  )
}

function AddVideo({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const org = useOrg()
  const { busy, run } = useAction()
  const [mode, setMode] = useState<'sample' | 'upload'>('sample')
  const [f, setF] = useState({ title: '', kind: 'MOVIE', genre: '', description: '', minutes: 3, tagline: '', color: '#6366f1' })
  const [file, setFile] = useState<File | null>(null)
  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const isAd = f.kind === 'AD'

  async function submit() {
    if (!f.title.trim()) throw new Error('Give the video a title.')
    if (mode === 'upload' && !file) throw new Error('Choose a video file to upload.')
    const body = {
      title: f.title, kind: f.kind, genre: isAd ? 'Advertising' : f.genre, description: f.description, color: f.color,
      ...(mode === 'sample' ? { sampleSeconds: isAd ? Number(f.minutes) : Math.round(Number(f.minutes) * 60), sampleTagline: f.tagline } : {}),
    }
    const v = await api<AssetView>(`/api/orgs/${org.slug}/assets`, { body })
    if (mode === 'upload' && file) {
      const form = new FormData()
      form.append('file', file)
      await api(`/api/orgs/${org.slug}/assets/${v.asset.id}/upload`, { form })
    }
    return v.asset.id
  }

  return (
    <Modal title="Add a video" subtitle="Programmes fill your schedule. Ad creatives are what advertisers pay to show in ad breaks." onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn primary" disabled={busy} onClick={() => run(submit, 'Video added — processing has started').then(id => id && onDone(id))}>
        {busy ? <span className="spinner" /> : mode === 'sample' ? 'Generate video' : 'Upload'}</button>
    </>}>
      <div className="seg">
        <button className={mode === 'sample' ? 'active' : ''} onClick={() => setMode('sample')}><Icon name="wand" size={14} /> Generate a sample</button>
        <button className={mode === 'upload' ? 'active' : ''} onClick={() => setMode('upload')}><Icon name="upload" size={14} /> Upload a file</button>
      </div>
      {mode === 'sample'
        ? <div className="callout sim"><Icon name="sparkle" /><div>The server draws a fictional test video with your title on it using ffmpeg. Great for trying the product without real content.</div></div>
        : <div className="callout"><Icon name="info" /><div>MP4, MOV, MKV, WEBM or M4V. The server converts it to 640×360 and cuts it into 3-second pieces. Only upload content you have rights to.</div></div>}
      <div className="grid g2">
        <label className="field">Title<input value={f.title} onChange={e => set('title', e.target.value)} placeholder={isAd ? 'e.g. Sunny Juice' : 'e.g. The Midnight Express'} autoFocus /></label>
        <label className="field">Type
          <select value={f.kind} onChange={e => { set('kind', e.target.value); set('minutes', e.target.value === 'AD' ? 15 : 3) }}>
            <option value="MOVIE">Movie</option><option value="EPISODE">Episode</option><option value="SHORT">Short</option><option value="AD">Ad creative</option>
          </select></label>
      </div>
      {!isAd && <label className="field">Genre<input value={f.genre} onChange={e => set('genre', e.target.value)} placeholder="Action, Travel, Family…" /></label>}
      <label className="field">Description<textarea value={f.description} onChange={e => set('description', e.target.value)} placeholder="Shown in the TV guide" /></label>
      {mode === 'sample' ? (
        <div className="grid g2">
          {isAd ? (
            <label className="field">Length<select value={f.minutes} onChange={e => set('minutes', e.target.value)}><option value={15}>15 seconds</option><option value={30}>30 seconds</option></select></label>
          ) : (
            <label className="field">Length (minutes)<input type="number" min={1} max={30} value={f.minutes} onChange={e => set('minutes', e.target.value)} /></label>
          )}
          <label className="field">Brand colour<input type="color" value={f.color} onChange={e => set('color', e.target.value)} /></label>
          {isAd && <label className="field" style={{ gridColumn: 'span 2' }}>Tagline<input value={f.tagline} onChange={e => set('tagline', e.target.value)} placeholder="e.g. Squeezed this morning" /></label>}
        </div>
      ) : (
        <label className="field">Video file<input type="file" accept="video/*,.mkv" onChange={e => setFile(e.target.files?.[0] ?? null)} /></label>
      )}
    </Modal>
  )
}

function AssetDrawer({ view, onClose, onChange }: { view: AssetView; onClose: () => void; onChange: () => void }) {
  const org = useOrg()
  const a = view.asset
  const { busy, run } = useAction()
  const videoRef = useRef<HTMLVideoElement>(null)
  const [title, setTitle] = useState(a.title)
  const [description, setDescription] = useState(a.description)
  const [genre, setGenre] = useState(a.genre)
  const [confirmArchive, setConfirmArchive] = useState(false)
  const cues = a.cuePoints ? a.cuePoints.split(',').map(Number) : []
  const editable = org.can('ASSETS_EDIT')

  useEffect(() => {
    const v = videoRef.current
    if (!v || !view.previewUrl || a.status !== 'READY') return
    if (!Hls.isSupported()) { v.src = view.previewUrl; return }
    const hls = new Hls()
    hls.loadSource(view.previewUrl)
    hls.attachMedia(v)
    return () => hls.destroy()
  }, [view.previewUrl, a.status])

  const save = (body: object, msg: string) => run(() => api(`/api/orgs/${org.slug}/assets/${a.id}`, { method: 'PATCH', body }), msg).then(onChange)

  return (
    <Drawer onClose={onClose}>
      <div className="modal-head" style={{ padding: '18px 22px' }}>
        <div><div className="row"><h2>{a.title}</h2><Status value={a.status} /></div>
          <p className="faint">{KIND_LABEL[a.kind]} · {fmtDur(a.durationMs)} · {a.genre}</p></div>
        <button className="btn ghost icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button>
      </div>
      <div className="modal-body" style={{ paddingTop: 0 }}>
        {a.status === 'READY' ? (
          <div className="player"><video ref={videoRef} controls playsInline /></div>
        ) : (
          <div className="thumb" style={{ background: a.color }}>
            <div className="stack" style={{ alignItems: 'center', textAlign: 'center', padding: 20 }}>
              {a.status === 'PROCESSING' ? <span className="spinner" /> : <Icon name={a.status === 'FAILED' ? 'alert' : 'upload'} size={28} />}
              <span>{a.statusDetail || (a.status === 'DRAFT' ? 'Waiting for a video file' : a.status)}</span>
            </div>
          </div>
        )}
        {a.status === 'FAILED' && editable && <button className="btn" disabled={busy} onClick={() => run(() => api(`/api/orgs/${org.slug}/assets/${a.id}/retry`, { method: 'POST' }), 'Retrying').then(onChange)}><Icon name="refresh" />Retry processing</button>}
        {a.status === 'READY' && <p className="faint">{a.statusDetail}</p>}

        {a.kind !== 'AD' && (
          <section className="card card-pad stack">
            <div className="between"><div className="row"><h3>Ad break points</h3>
              <InfoTip text="Cue points are the moments inside a programme where an ad break may start (like a chapter break). They snap to the nearest 3-second piece so the stream can cut cleanly." /></div>
              {editable && a.status === 'READY' && <button className="btn sm" onClick={() => {
                const t = Math.round((videoRef.current?.currentTime ?? 0) * 1000)
                if (t < 3000) return
                save({ cuePoints: [...cues, t] }, 'Break point added')
              }}><Icon name="plus" size={14} />Add at current time</button>}
            </div>
            {cues.length === 0 ? <p className="faint">No break points — this programme plays without mid-roll ads.</p> : (
              <div className="row-wrap">{cues.map(c => (
                <span key={c} className="chip"><Icon name="megaphone" size={12} />{fmtDur(c)}
                  {editable && <button className="btn ghost sm icon-btn" style={{ height: 20, width: 20 }} aria-label="Remove break point" onClick={() => save({ cuePoints: cues.filter(x => x !== c) }, 'Break point removed')}><Icon name="x" size={12} /></button>}
                </span>))}</div>
            )}
          </section>
        )}

        {a.kind !== 'AD' && (
          <section className="card card-pad stack">
            <div className="between"><h3>Licence</h3><Status value={view.rightsStatus} /></div>
            {view.rights ? (
              <div className="kv">
                <span>May play in</span><span>{view.rights.allowedCountries || <span className="chip bad">none listed</span>}</span>
                <span>Blocked in</span><span>{view.rights.blockedCountries || '—'}</span>
                <span>Valid until</span><span>{view.rights.validUntil ? new Date(view.rights.validUntil).toLocaleDateString() : 'no end date'}</span>
                <span>Ads allowed</span><span>{view.rights.adsAllowed ? 'Yes' : 'No — no ad breaks inside this programme'}</span>
              </div>
            ) : <p className="muted">No licence entered, so this video is blocked everywhere (missing rights never mean permission).</p>}
            <Link className="btn sm" to={`${org.base}/rights?asset=${a.id}`} style={{ alignSelf: 'start' }}>Open in Rights</Link>
          </section>
        )}

        <section className="card card-pad stack">
          <h3>Where it's used</h3>
          {view.channels.length ? <p>Scheduled on {view.channels.join(', ')}</p> : <p className="faint">Not in any published upcoming schedule.</p>}
        </section>

        <section className="card card-pad stack">
          <h3>Details</h3>
          <label className="field">Title<input value={title} disabled={!editable} onChange={e => setTitle(e.target.value)} /></label>
          <label className="field">Genre<input value={genre} disabled={!editable} onChange={e => setGenre(e.target.value)} /></label>
          <label className="field">Description<textarea value={description} disabled={!editable} onChange={e => setDescription(e.target.value)} /></label>
          {editable && <div className="between">
            <button className="btn danger" onClick={() => setConfirmArchive(true)}><Icon name="trash" />Archive</button>
            <button className="btn primary" disabled={busy} onClick={() => save({ title, description, genre }, 'Saved')}>Save changes</button>
          </div>}
        </section>
      </div>
      {confirmArchive && <Confirm title="Archive this video?" danger confirmLabel="Archive"
        text="Archived videos disappear from the library and can't be scheduled. If it's still on a schedule, campaign or used as a fallback, we'll tell you what to change first."
        onClose={() => setConfirmArchive(false)}
        onConfirm={() => run(() => api(`/api/orgs/${org.slug}/assets/${a.id}/archive`, { method: 'POST' }), 'Archived').then(r => { if (r) { onChange(); onClose() } })} />}
    </Drawer>
  )
}
