import { useEffect, useRef, useState } from 'react'
import { api, type AssetView } from '../api'
import { useOrg } from '../../app/App'
import {
  Dropdown,
  type DropdownOption,
  ErrorBox,
  Icon,
  Modal,
  useAction,
  useApi,
} from '../ui'
import { DateTimePicker } from '../components/DateTimePicker'
import { GenreSelector } from '../components/GenreSelector'
import { useProjectMedia } from '../projectMedia'

const COLOR_PRESETS = ['#6366f1', '#06b6d4', '#10b981', '#f59e0b', '#ec4899', '#8b5cf6', '#3b82f6', '#14b8a6']

/** Redesigned Add Video Modal for Non-Tech Users */
/** Redesigned Add Video Modal with Simplified Layout & DateTime Scheduling */
/** PUTs the file straight to the video library's one-time URL (it never passes through FAST Studio), reporting progress. */
function putFile(url: string, file: File, onProgress: (pct: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const x = new XMLHttpRequest()
    x.open('PUT', url)
    x.upload.onprogress = e => { if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100)) }
    x.onload = () => (x.status >= 200 && x.status < 300 ? resolve() : reject(new Error(`The video library refused the file (${x.status}). Try again.`)))
    x.onerror = () => reject(new Error('The upload to the video library failed. Check your connection and try again.'))
    x.send(file)
  })
}

/**
 * scope 'library': programmes. With a connected video library the file goes straight to it (choose one when there
 * are several); without one, to FAST Studio. scope 'ads': ad creatives, always kept by FAST Studio.
 */
export function AddVideoModal({ onClose, onDone, scope = 'library' }: { onClose: () => void; onDone: (id: string) => void; scope?: 'library' | 'ads' }) {
  const org = useOrg()
  const { busy, run } = useAction()
  const [mode, setMode] = useState<'upload' | 'sample'>('upload')
  const mediaSettings = useProjectMedia()
  const forAds = scope === 'ads'
  const lib = useApi<{ uploadTo: { id: string; name: string }[] }>(forAds ? null : `/api/orgs/${org.slug}/library`)
  const targets = lib.data?.uploadTo ?? []
  const [target, setTarget] = useState('')
  const [progress, setProgress] = useState<number | null>(null)
  const [f, setF] = useState({
    title: '',
    kind: 'MOVIE',
    mediaType: '',
    series: '',
    season: 1,
    episode: 1,
    genre: 'Action',
    description: '',
    minutes: 3,
    tagline: '',
    color: '#6366f1',
  })
  const [releaseDateTime, setReleaseDateTime] = useState<string>('')
  // only the types this place adds: ads in the Ads section, everything else in the Library
  const types = (mediaSettings.data?.types ?? []).filter(t => mediaSettings.data!.enabledTypes.includes(t.key) && (t.kind === 'AD') === forAds)
  useEffect(() => {
    const s = mediaSettings.data
    const enabled = (s?.types ?? []).filter(t => s!.enabledTypes.includes(t.key) && (t.kind === 'AD') === forAds)
    const type = enabled.find(t => t.key === s?.defaultType) ?? enabled[0]
    if (type) setF(f => ({ ...f, mediaType: type.key, kind: type.kind, minutes: type.kind === 'AD' ? 15 : 3 }))
  }, [mediaSettings.data, forAds])
  useEffect(() => { if (targets.length === 1) setTarget(targets[0].id) }, [targets.length])   // eslint-disable-line react-hooks/exhaustive-deps
  const [file, setFile] = useState<File | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const isAd = f.kind === 'AD'

  // Auto-fill title from filename if title is empty
  const handleFileChange = (selected: File | null) => {
    setFile(selected)
    if (selected && !f.title.trim()) {
      const cleanName = selected.name
        .replace(/\.[^/.]+$/, '')
        .replace(/[-_]+/g, ' ')
        .replace(/\b\w/g, l => l.toUpperCase())
      set('title', cleanName)
    }
  }

  async function submit() {
    if (!mediaSettings.data || !f.mediaType) throw new Error('Wait for your company media settings to load and choose an enabled type.')
    if (!f.title.trim()) throw new Error('Please enter a title for the video.')
    if (mode === 'upload' && !file) throw new Error('Please choose a video file to upload.')
    const toLibrary = mode === 'upload' && !forAds && targets.length > 0
    if (toLibrary && !target) throw new Error('Choose the video library to upload to.')

    const body = {
      title: f.title.trim(),
      kind: f.kind,
      mediaType: f.mediaType || f.kind,
      ...(f.kind === 'EPISODE' ? { series: f.series.trim(), season: Number(f.season), episode: Number(f.episode) } : {}),
      genre: isAd ? 'Advertising' : f.genre.trim(),
      description: f.description.trim(),
      color: f.color,
      ...(mode === 'sample'
        ? {
            sampleSeconds: isAd ? Number(f.minutes) : Math.round(Number(f.minutes) * 60),
            sampleTagline: f.tagline.trim(),
          }
        : {}),
    }

    const v = await api<AssetView>(`/api/orgs/${org.slug}/assets`, { body })

    if (toLibrary && file) {
      const u = await api<{ url: string }>(`/api/orgs/${org.slug}/assets/${v.asset.id}/upload-url?connection=${target}`, { method: 'POST' })
      setProgress(0)
      try {
        await putFile(u.url, file, setProgress)
      } finally {
        setProgress(null)
      }
    } else if (mode === 'upload' && file) {
      const form = new FormData()
      form.append('file', file)
      await api(`/api/orgs/${org.slug}/assets/${v.asset.id}/upload`, { form })
    }

    // Attach release / broadcast schedule date to rights policy if set
    if (releaseDateTime) {
      try {
        await api(`/api/orgs/${org.slug}/assets/${v.asset.id}/rights`, {
          method: 'PUT',
          body: {
            allowedCountries: ['WW'],
            blockedCountries: [],
            validFrom: new Date(releaseDateTime).toISOString(),
            validUntil: null,
            adsAllowed: true,
            notes: `Scheduled release: ${new Date(releaseDateTime).toLocaleString()}`,
          },
        })
      } catch (err) {
        console.warn('Could not set scheduled broadcast rights:', err)
      }
    }

    return v.asset.id
  }

  const toLibrary = mode === 'upload' && !forAds && targets.length > 0
  const kindOptions: DropdownOption[] = types.map(t => ({ value: t.key, label: t.label, description: t.kind === 'EPISODE' ? 'Series, season and episode metadata required' : `Scheduling behavior: ${t.kind.toLowerCase()}`, icon: t.kind === 'AD' ? 'megaphone' : t.kind === 'EPISODE' ? 'tv' : 'film' }))

  return (
    <Modal
      title={forAds ? 'Add ad creative' : 'Add Media to Catalog'}
      subtitle={forAds ? 'Ad creatives are converted once and kept by FAST Studio for your ad breaks.'
        : 'Upload full media files or generate instant test broadcasts ready for playout.'}
      onClose={onClose}
      wide
      footer={
        <>
          <button type="button" className="btn" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy}
            onClick={() =>
              run(submit, toLibrary ? 'Uploaded to the video library. It becomes ready once the library has converted it.'
                : 'Media added — background transcoding started').then(id => id && onDone(id))
            }
          >
            {progress !== null ? (
              <><span className="spinner" /> Uploading {progress}%</>
            ) : busy ? (
              <span className="spinner" />
            ) : mode === 'upload' ? (
              <>
                <Icon name="upload" size={15} /> Upload & Ingest Media
              </>
            ) : (
              <>
                <Icon name="wand" size={15} /> Generate Demo Media
              </>
            )}
          </button>
        </>
      }
    >
      {/* Sleek Segmented Mode Switcher */}
      <div className="library-mode-switcher-wrap">
        <div className="library-mode-segmented">
          <button
            type="button"
            className={`library-mode-btn ${mode === 'upload' ? 'is-active' : ''}`}
            onClick={() => setMode('upload')}
          >
            <Icon name="upload" size={14} />
            <span>Upload Media File</span>
            <span className="badge info sm">Real Media</span>
          </button>
          <button
            type="button"
            className={`library-mode-btn ${mode === 'sample' ? 'is-active' : ''}`}
            onClick={() => setMode('sample')}
          >
            <Icon name="wand" size={14} />
            <span>Generate Demo Media</span>
            <span className="badge sim sm">Instant Setup</span>
          </button>
        </div>
      </div>

      <div className="stack" style={{ gap: 16 }}>
        {/* Upload Dropzone (Primary) */}
        {mode === 'upload' ? (
          <div
            className={`library-upload-dropzone ${file ? 'has-file' : ''}`}
            onClick={() => fileInputRef.current?.click()}
          >
            <input
              ref={fileInputRef}
              type="file"
              accept="video/*,.mkv,.m4v"
              style={{ display: 'none' }}
              onChange={e => handleFileChange(e.target.files?.[0] ?? null)}
            />
            <div className="library-dropzone-icon">
              <Icon name={file ? 'check' : 'upload'} size={28} />
            </div>
            {file ? (
              <div className="stack" style={{ alignItems: 'center', gap: 4 }}>
                <b className="library-dropzone-filename">{file.name}</b>
                <span className="badge ok sm">
                  {(file.size / (1024 * 1024)).toFixed(1)} MB · Ready to upload
                </span>
                <span className="faint small" style={{ marginTop: 4 }}>
                  Click to select a different media file
                </span>
              </div>
            ) : (
              <div className="stack" style={{ alignItems: 'center', gap: 4 }}>
                <b style={{ fontSize: 14 }}>Click to choose media or drag & drop</b>
                <span className="faint small">
                  {toLibrary ? 'Supports MP4, MOV, MKV, WebM, and M4V. Goes straight to your video library, which converts it.'
                    : 'Supports MP4, MOV, MKV, WebM, and M4V. Auto-converted to broadcast HLS.'}
                </span>
                <div className="row" style={{ gap: 6, marginTop: 6 }}>
                  <span className="chip sm">MP4</span>
                  <span className="chip sm">MOV</span>
                  <span className="chip sm">MKV</span>
                  <span className="chip sm">WebM</span>
                </div>
              </div>
            )}
          </div>
        ) : (
          /* Instant Generator Banner */
          <div className="card card-pad stack" style={{ gap: 12, background: 'var(--surface-2)' }}>
            <div className="row" style={{ gap: 8 }}>
              <Icon name="sparkle" size={15} />
              <b>Instant Simulated Media</b>
            </div>
            <div className="grid g2">
              {isAd ? (
                <label className="field">
                  <span>Ad Duration</span>
                  <div className="row" style={{ gap: 8 }}>
                    <button
                      type="button"
                      className={`btn sm ${f.minutes === 15 ? 'primary' : ''}`}
                      onClick={() => set('minutes', 15)}
                    >
                      15s Spot
                    </button>
                    <button
                      type="button"
                      className={`btn sm ${f.minutes === 30 ? 'primary' : ''}`}
                      onClick={() => set('minutes', 30)}
                    >
                      30s Spot
                    </button>
                  </div>
                </label>
              ) : (
                <label className="field">
                  <span>Duration</span>
                  <div className="row-wrap" style={{ gap: 6 }}>
                    {[1, 3, 5, 10, 15, 30].map(m => (
                      <button
                        key={m}
                        type="button"
                        className={`chip sm ${f.minutes === m ? 'active' : ''}`}
                        onClick={() => set('minutes', m)}
                      >
                        {m} min
                      </button>
                    ))}
                  </div>
                </label>
              )}

              <label className="field">
                <span>Slate & Card Color</span>
                <div className="row" style={{ gap: 8, alignItems: 'center' }}>
                  {COLOR_PRESETS.map(c => (
                    <button
                      key={c}
                      type="button"
                      className="library-color-swatch"
                      style={{
                        background: c,
                        boxShadow: f.color === c ? `0 0 0 2px var(--surface), 0 0 0 4px ${c}` : 'none',
                      }}
                      onClick={() => set('color', c)}
                      aria-label={`Select color ${c}`}
                    />
                  ))}
                  <input
                    type="color"
                    value={f.color}
                    onChange={e => set('color', e.target.value)}
                    className="library-custom-color"
                    title="Choose custom color"
                  />
                </div>
              </label>
            </div>
          </div>
        )}

        {/* Where a programme upload goes: the project's video library (choose when there are several) */}
        {toLibrary && (targets.length === 1 ? (
          <div className="callout" data-testid="upload-target"><Icon name="info" /><div className="small">Uploads to <b>{targets[0].name}</b>.</div></div>
        ) : (
          <label className="field" data-testid="upload-target">
            <span>Upload to <b style={{ color: 'var(--bad)' }}>*</b></span>
            <select aria-label="Upload to" value={target} onChange={e => setTarget(e.target.value)}>
              <option value="">Choose a video library…</option>
              {targets.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </label>
        ))}

        {/* Title & Format Fields */}
        <div className="grid g2">
          <label className="field">
            <span>
              Media Title <b style={{ color: 'var(--bad)' }}>*</b>
            </span>
            <input
              type="text"
              value={f.title}
              onChange={e => set('title', e.target.value)}
              placeholder={isAd ? 'e.g. Refresh Energy Drink 30s' : 'e.g. Journey to the Stars'}
              autoFocus={mode !== 'upload'}
            />
          </label>

          <label className="field">
            <span>Content Format</span>
            <Dropdown
              options={kindOptions}
              value={f.mediaType}
              onChange={val => {
                const type = mediaSettings.data?.types.find(t => t.key === val)
                if (type) setF(f => ({ ...f, mediaType: val, kind: type.kind, minutes: type.kind === 'AD' ? 15 : 3 }))
              }}
              align="full"
            />
          </label>
        </div>

        {mediaSettings.error && <ErrorBox error={mediaSettings.error} retry={mediaSettings.reload} />}
        {f.kind === 'EPISODE' && <div className="grid g3">
          <label className="field"><span>Series</span><input maxLength={120} required value={f.series} onChange={e => set('series', e.target.value)} placeholder="e.g. City Stories" /></label>
          <label className="field"><span>Season number</span><input type="number" min={1} required value={f.season} onChange={e => set('season', Number(e.target.value))} /></label>
          <label className="field"><span>Episode number</span><input type="number" min={1} required value={f.episode} onChange={e => set('episode', Number(e.target.value))} /></label>
        </div>}
        {/* Genre Multi-Select */}
        {!isAd && (
          <GenreSelector
            value={f.genre}
            onChange={val => set('genre', val)}
          />
        )}

        {/* Scheduled Broadcast / Release Date & Time Component */}
        <div className="field library-datetime-field">
          <DateTimePicker
            label="Scheduled Broadcast / Release Window"
            hint="Optional linear scheduling time"
            value={releaseDateTime}
            onChange={val => setReleaseDateTime(val)}
            placeholder="Select broadcast date & time..."
            showTime={true}
            allowTimeToggle={true}
            showTimezone={true}
            fixed={true}
          />
        </div>

        {/* Description / Synopsis */}
        <label className="field">
          <span>Description / Synopsis</span>
          <textarea
            rows={2}
            value={f.description}
            onChange={e => set('description', e.target.value)}
            placeholder="Shown to viewers in the TV Guide and programme preview…"
          />
        </label>
      </div>
    </Modal>
  )
}
