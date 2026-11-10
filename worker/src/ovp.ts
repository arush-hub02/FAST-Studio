// Programmes that stream from their video platform (Mux, ...): the Worker reads the platform's HLS playlists and lists
// its segment URLs in our live playlists. Each (video, rendition) is read once per location and reused by every viewer:
// per-isolate memory first, then the location's Cache API, then the platform. A failed read keeps serving the last good
// copy (segment URLs stay valid for days); with none, that programme plays slate until a retry 15 s later succeeds.

import type { Rendition, Snapshot } from './playout.ts'

export type Variant = { height: number; bandwidth: number; uri: string }
/** "Rendition" number of a video's separate audio track (fMP4 platforms such as Mux deliver audio apart from video). */
export const AUDIO = -1

const FRESH_MS = 30 * 60_000      // re-read a playlist after this long (signed URLs last far longer)
const RETRY_MS = 15_000           // after a failed first read
const FETCH_MS = 1_500            // per request: a viewer's playlist must not wait long
const MAX_ENTRIES = 5_000

type Entry = { at: number; urls: string[] | null; map?: string }
const memo = new Map<string, Entry>()
const inflight = new Map<string, Promise<Entry>>()

/** Quality levels of a master playlist, URIs absolute. */
export function parseMaster(text: string, base: string): Variant[] {
  const lines = text.split(/\r?\n/), out: Variant[] = []
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith('#EXT-X-STREAM-INF:')) continue
    const attrs = lines[i].slice(18)
    const height = Number(/RESOLUTION=\d+x(\d+)/.exec(attrs)?.[1] ?? 0)
    const bandwidth = Number(/(?:^|,)BANDWIDTH=(\d+)/.exec(attrs)?.[1] ?? 0)
    let j = i + 1
    while (j < lines.length && (lines[j].trim() === '' || lines[j].startsWith('#'))) j++
    if (j < lines.length) out.push({ height, bandwidth, uri: new URL(lines[j].trim(), base).toString() })
    i = j
  }
  return out
}

/** The audio track's playlist in a master (the default one, else the first); null when audio is inside the video segments. */
export function parseAudio(text: string, base: string): string | null {
  let first: string | null = null
  for (const line of text.split(/\r?\n/)) {
    if (!line.startsWith('#EXT-X-MEDIA:') || !line.includes('TYPE=AUDIO')) continue
    const u = /URI="([^"]+)"/.exec(line)?.[1]
    if (!u) continue
    const abs = new URL(u, base).toString()
    if (line.includes('DEFAULT=YES')) return abs
    first ??= abs
  }
  return first
}

/** Segment URLs of a media playlist and its fMP4 init section (EXT-X-MAP), absolute. */
export function parseMedia(text: string, base: string): { urls: string[]; map?: string } {
  const urls: string[] = []
  let map: string | undefined
  for (const line of text.split(/\r?\n/)) {
    const l = line.trim()
    if (l.startsWith('#EXT-X-MAP:')) {
      const u = /URI="([^"]+)"/.exec(l)?.[1]
      if (u) map = new URL(u, base).toString()
    } else if (l && !l.startsWith('#')) urls.push(new URL(l, base).toString())
  }
  return { urls, map }
}

/** The platform rendition for one of our quality levels: the nearest height, the lower one on a tie. */
export function pick(variants: Variant[], ours: Rendition): number {
  let best = 0
  for (let i = 1; i < variants.length; i++) {
    const d = Math.abs(variants[i].height - ours.height), b = Math.abs(variants[best].height - ours.height)
    if (d < b || (d === b && variants[i].height < variants[best].height)) best = i
  }
  return best
}

async function get(url: string): Promise<string> {
  const res = await fetch(url, { signal: AbortSignal.timeout(FETCH_MS), cf: { cacheTtl: 60 } } as RequestInit)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.text()
}

/** ours null = the audio track. */
async function read(master: string, ours: Rendition | null): Promise<{ urls: string[]; map?: string }> {
  const text = await get(master)
  let uri: string
  if (ours) {
    const variants = parseMaster(text, master)
    if (!variants.length) {
      if (!text.includes('#EXTINF:')) throw new Error('no renditions')
      return parseMedia(text, master)   // a single media playlist (some ad servers): the only rendition
    }
    uri = variants[pick(variants, ours)].uri
  } else {
    const a = parseAudio(text, master)
    if (!a) throw new Error('no separate audio track')
    uri = a
  }
  const r = parseMedia(await get(uri), uri)
  if (!r.urls.length) throw new Error('empty rendition')
  return r
}

const cacheKey = async (master: string, variant: number | string) => {
  const h = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(master)))
  return `https://ovp-playlists.internal/${[...h.slice(0, 16)].map(b => b.toString(16).padStart(2, '0')).join('')}/${variant}`
}

/** Makes sure the segment URLs of this video and rendition are in memory (fresh, or the last good copy). */
export async function load(snap: Snapshot, asset: string, variant: number, now = Date.now()): Promise<void> {
  const master = snap.ovp?.[asset]?.url
  if (master) await loadUrl(snap, master, variant, asset, now)
}

/** Same for a Direct ad's HLS (read once per location like programmes; signed ad URLs differ per answer). */
export async function loadAd(snap: Snapshot, hlsUrl: string, variant: number, now = Date.now()): Promise<void> {
  await loadUrl(snap, hlsUrl, variant, 'ad', now)
}

/** Piece n of a Direct ad in our rendition, with its fMP4 init section; null when not loaded or out of range. */
export function adSegment(hlsUrl: string, variant: number, n: number): { url: string; map?: string } | null {
  const e = memo.get(`${hlsUrl}|${variant}`)
  const url = e?.urls?.[n]
  return url ? { url, map: e?.map } : null
}

/** True when this ad's rendition has piece URLs in memory. */
export function adCached(hlsUrl: string, variant: number): boolean { return !!memo.get(`${hlsUrl}|${variant}`)?.urls }

async function loadUrl(snap: Snapshot, master: string, variant: number, asset: string, now: number): Promise<void> {
  const ours = variant === AUDIO ? null : snap.ladder[variant]
  if (variant !== AUDIO && !ours) return
  const k = `${master}|${variant}`
  const hit = memo.get(k)
  if (hit && now - hit.at < (hit.urls ? FRESH_MS : RETRY_MS)) return
  let p = inflight.get(k)
  if (!p) {
    p = (async (): Promise<Entry> => {
      const cache = typeof caches !== 'undefined' ? (caches as unknown as { default: Cache }).default : null
      const ck = cache ? await cacheKey(master, variant === AUDIO ? 'audio' : variant) : ''
      if (cache) {
        const c = await cache.match(ck)
        if (c) {
          const j = (await c.json()) as { urls: string[]; map?: string }
          return { at: Number(c.headers.get('X-Read-At') ?? now), urls: j.urls, map: j.map }
        }
      }
      try {
        const { urls, map } = await read(master, ours ?? null)
        if (cache) await cache.put(ck, new Response(JSON.stringify({ urls, map }), {
          headers: { 'Content-Type': 'application/json', 'Cache-Control': `max-age=${FRESH_MS / 1000}`, 'X-Read-At': String(now) } }))
        return { at: now, urls, map }
      } catch (e) {
        console.warn('platform playlist unavailable', asset, variant, String(e))
        return hit?.urls ? { at: now - FRESH_MS + RETRY_MS, urls: hit.urls, map: hit.map } : { at: now, urls: null }
      }
    })().finally(() => inflight.delete(k))
    inflight.set(k, p)
  }
  const e = await p
  if (memo.size > MAX_ENTRIES) memo.clear()
  memo.set(k, e)
}

/** Segment i of a streaming video in our rendition, with its fMP4 init section; null when not loaded or out of range. */
export function segmentUrl(snap: Snapshot, asset: string, variant: number, i: number): { url: string; map?: string } | null {
  const master = snap.ovp?.[asset]?.url
  if (!master) return null
  const e = memo.get(`${master}|${variant}`)
  const url = e?.urls?.[i]
  return url ? { url, map: e?.map } : null
}

/** For tests. */
export function reset() { memo.clear(); inflight.clear() }

/** True when this video and rendition have segment URLs in memory (possibly due for a refresh). */
export function cached(snap: Snapshot, asset: string, variant: number): boolean {
  const master = snap.ovp?.[asset]?.url
  return !!master && !!memo.get(`${master}|${variant}`)?.urls
}
