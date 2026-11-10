import { useState } from 'react'
import { api } from '../../shared/api'
import { InfoTip, Loading, useAction, useApi } from '../../shared/ui'
import type { Configuration } from '../../shared/configuration/types'

export const QUALITY_PROPERTY = 'media.quality_levels'

/** The quality levels FAST Studio can encode and play (services/media/QualityLevels.ALL). */
const LEVELS = [
  { name: '360p', size: '640×360', mbps: 0.8 },
  { name: '540p', size: '960×540', mbps: 1.7 },
  { name: '720p', size: '1280×720', mbps: 2.9 },
  { name: '1080p', size: '1920×1080', mbps: 5.8 },
  { name: '1440p', size: '2560×1440 (2K)', mbps: 10.4 },
  { name: '2160p', size: '3840×2160 (4K)', mbps: 18.5 },
]

/**
 * Tenant page → Configuration: which quality levels this tenant's videos, ads and channels use. Platform administrators
 * only (higher levels cost encoding time, storage and delivery). Applies to new conversions; older media plays its
 * nearest lower level where a new level is missing.
 */
export function QualityLevels({ tenantId }: { tenantId: string }) {
  const base = `/api/platform/tenants/${tenantId}`
  const view = useApi<Configuration>(`${base}/settings`)
  const { busy, run } = useAction()
  const [picked, setPicked] = useState<string[] | null>(null)
  if (!view.data) return <Loading rows={1} />
  const p = view.data.properties.find(x => x.definition.key === QUALITY_PROPERTY)
  if (!p) return null
  const saved = String(p.value ?? '360p,540p,720p').split(',').map(s => s.trim()).filter(Boolean)
  const current = picked ?? saved
  const toggle = (n: string) => setPicked(LEVELS.map(l => l.name).filter(x => x === n ? !current.includes(n) : current.includes(x)))
  const changed = current.join(',') !== saved.join(',')
  const perHourGb = current.reduce((a, n) => a + (LEVELS.find(l => l.name === n)?.mbps ?? 0), 0) * 3600 / 8 / 1000
  return (
    <section className="card card-pad stack" data-testid="quality-levels">
      <div className="row" style={{ gap: 8 }}>
        <h2 style={{ margin: 0 }}>Quality levels</h2>
        <InfoTip text="Every video uploaded to FAST Studio, every ad and every channel of this tenant uses these levels. Library videos (Mux) keep their own levels; each channel level plays the library level nearest in height. Changes apply to new conversions; older videos and ads play their nearest lower level until converted again." />
      </div>
      <div className="grid g3">
        {LEVELS.map(l => (
          <label key={l.name} className="row" style={{ gap: 8, alignItems: 'flex-start' }}>
            <input type="checkbox" aria-label={`Quality ${l.name}`} checked={current.includes(l.name)} disabled={busy} onChange={() => toggle(l.name)} />
            <span><b>{l.name}</b><div className="faint small">{l.size} · {l.mbps} Mbit/s</div></span>
          </label>
        ))}
      </div>
      <p className="faint small">
        Storage per hour of video at these levels: about {perHourGb.toFixed(1)} GB. 1440p and 2160p take several times longer to convert;
        for library videos, Mux makes levels above 1080p only for videos created with a higher resolution tier (FAST Studio asks for it on uploads).
      </p>
      <div className="row" style={{ gap: 8 }}>
        <button className="btn primary" disabled={busy || !changed || !current.length}
          onClick={() => run(() => api(`${base}/properties/${QUALITY_PROPERTY}`, { method: 'PUT', body: { value: current.join(',') } }), 'Quality levels saved')
            .then(r => { if (r) { setPicked(null); view.reload() } })}>Save quality levels</button>
        {changed && <button className="btn" disabled={busy} onClick={() => setPicked(null)}>Undo</button>}
        {!current.length && <span className="faint small">Pick at least one level.</span>}
      </div>
    </section>
  )
}
