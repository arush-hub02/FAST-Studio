import { useEffect, useRef, useState } from 'react'
import { api } from '../../shared/api'
import { useOrg } from '../../app/App'
import { Icon, InfoTip, fmtDayTime, useAction, useApi } from '../../shared/ui'

type Sync = { state: string; videos: number; added: number; complete: boolean; finishedAt: string | null; lastError: string | null }
type Library = { connected: boolean; healthy: boolean | null; capabilities: string[]; sync: Sync; waiting: string[] }

const ACTIVE = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED']

/**
 * The project's video library on the Library page: sync its catalogue and get imported videos ready to stream.
 * Library videos stream from the library itself; our ads and slate are spliced in at the breaks. Provider-neutral.
 */
export function LibrarySource({ onChanged }: { onChanged: () => void }) {
  const org = useOrg()
  const base = `/api/orgs/${org.slug}/library`
  const [syncing, setSyncing] = useState(false)
  const lib = useApi<Library>(base, [], syncing ? 3000 : undefined)   // polls only while a sync runs
  const active = !!lib.data && ACTIVE.includes(lib.data.sync.state)
  if (active !== syncing) setSyncing(active)
  const was = useRef(false)
  useEffect(() => { if (was.current && !syncing) onChanged(); was.current = syncing }, [syncing, onChanged])   // a sync finished: show its drafts
  const { busy, run } = useAction()
  if (!lib.data?.connected) return null
  const l = lib.data
  const streams = l.capabilities.includes('PLAYBACK')
  const waiting = l.waiting.slice(0, 100)

  const sync = () => run(() => api(`${base}/sync`, { method: 'POST' }), 'Sync started: new videos arrive as drafts').then(() => { lib.reload(); onChanged() })
  const prepare = () =>
    run(() => api<{ queued: number; skipped: { reason: string }[] }>(`${base}/prepare`, { body: { assetIds: waiting } }),
      r => `${r.queued} ${r.queued === 1 ? 'video' : 'videos'} getting ready${r.skipped.length ? `, ${r.skipped.length} skipped` : ''}`)
      .then(() => { lib.reload(); onChanged() })

  return (
    <section className="card card-pad row-wrap" data-testid="library-source" style={{ gap: 12, alignItems: 'center', marginBottom: 16 }}>
      <span className={`badge ${l.healthy === false ? 'bad' : 'ok'}`}>{l.healthy === false ? 'Connection failing' : 'Video library connected'}</span>
      <span className="small">
        {l.sync.state === 'NEVER' ? 'Never synced' : syncing ? 'Syncing…'
          : `Last sync: ${l.sync.videos} videos seen, ${l.sync.added} added${l.sync.finishedAt ? ` · ${fmtDayTime(l.sync.finishedAt)}` : ''}`}
        {l.sync.lastError && <span className="faint"> · {l.sync.lastError}</span>}
      </span>
      <InfoTip text={streams
        ? 'Library videos stream straight from the library. Getting one ready reads its quality levels and segment lengths (nothing is copied). Ads and slate are spliced in at the breaks.'
        : 'Library videos are copied once and cut into 5-second segments for playout.'} />
      <span style={{ flex: 1 }} />
      {org.can('ASSETS_EDIT') && <>
        <button className="btn" disabled={busy || syncing} onClick={sync}><Icon name="refresh" size={14} />{syncing ? 'Syncing…' : 'Sync library'}</button>
        <button className="btn primary" disabled={busy || !waiting.length} onClick={prepare}>
          {waiting.length ? `Get ${waiting.length} ready to ${streams ? 'stream' : 'air'}` : 'All library videos are ready'}
        </button>
      </>}
    </section>
  )
}
