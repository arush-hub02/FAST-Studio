// Live playlists from a schedule snapshot and the clock: a pure function, so every Worker instance in every
// location computes the same playlist without coordinating. Segment i always covers [EPOCH + SEG*i, +SEG), so
// EXT-X-MEDIA-SEQUENCE is simply i. No I/O in this file (index.ts fetches snapshots and ad decisions).

export const EPOCH = Date.parse('2026-01-01T00:00:00Z')
export const WINDOW = 12                 // segments in a live playlist (60 s)
export const LOOKAHEAD_MS = 30_000       // ad decisions and SGAI interstitials are prepared this far ahead

export type Rendition = { name: string; width: number; height: number; bandwidth: number; codecs: string }
export type Media = { key: string | null; segments: number }
export type Fallback = { key: string; segments: number; allowed: string[]; validFrom: number | null; validUntil: number | null }
export type Item = {
  id: string; kind: 'PROGRAM' | 'AD_BREAK'; key: string | null; segments: number; offsetMs: number
  start: number; end: number; allowed: string[]; ownership: string | null; scteOut: string | null; scteIn: string | null
}
/** Published by the control plane (edge.EdgePublisher) whenever a channel's schedule changes. */
export type Snapshot = {
  v: number; org: string; channel: string; status: string; territories: string[]; segMs: number
  from: number; until: number; ladder: Rendition[]; standby: Media; geo: Media; fallback: Fallback | null; items: Item[]
}
/** Signed by the control plane when a viewer starts watching (PlaybackController.start). */
export type Session = {
  sid: string; org: string; channel: string; country: string; adMode: 'SSAI' | 'SGAI' | 'PASSTHROUGH'
  markers: 'CUE_OUT' | 'DATERANGE'; destKind: string; created: number
}
export type Slot = { campaignId: string; campaign: string; mediaKey: string; durationMs: number }
export type Decision = { breakMs: number; filledMs: number; slots: Slot[] }
/** A decision, or 'slate' once a break has started without one (it must never flip to ads mid-break). */
export type Decided = Decision | 'slate' | undefined
export type Beacon = { breakId: string; campaignId: string; event: string }

type Seg = { key: string; media: string; n: number; ad?: { breakId: string; slot: Slot; segs: number } }

const pad = (n: number) => String(n).padStart(5, '0')
const iso = (t: number) => new Date(t).toISOString()
const secs = (ms: number) => (ms / 1000).toFixed(3)

export function masterPlaylist(snap: Snapshot): string {
  let m = '#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-INDEPENDENT-SEGMENTS\n'
  snap.ladder.forEach((r, i) => {
    m += `#EXT-X-STREAM-INF:BANDWIDTH=${r.bandwidth},AVERAGE-BANDWIDTH=${Math.floor(r.bandwidth * 85 / 100)},RESOLUTION=${r.width}x${r.height},CODECS="${r.codecs}",FRAME-RATE=25.000,NAME="${r.name}"\nv${i}.m3u8\n`
  })
  return m
}

/** True when this viewer's destination gets our ads in this break (vs. the platform filling it). */
export const ours = (s: Session, brk: Item) => s.destKind === 'OWNED_WEB' || brk.ownership === 'PROVIDER'

export function resolver(snap: Snapshot, s: Session, decided: (breakId: string) => Decided) {
  const SEG = snap.segMs
  const live = snap.status === 'LIVE'
  const offered = snap.territories.includes(s.country)
  const items = snap.items
  const time = (i: number) => EPOCH + i * SEG

  function at(t: number): Item | null {
    let lo = 0, hi = items.length - 1, best = -1
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1
      if (items[mid].start <= t) { best = mid; lo = mid + 1 } else hi = mid - 1
    }
    return best >= 0 && items[best].end > t ? items[best] : null
  }
  const loop = (prefix: string, m: Media, i: number): Seg => {
    const n = Math.max(1, m.segments)
    return { key: prefix + Math.floor(i / n), media: m.key ?? '', n: ((i % n) + n) % n }
  }
  const standby = (i: number) => loop('sb:', snap.standby, i)
  const geo = (i: number) => loop('geo:', snap.geo, i)

  function fallback(i: number, t: number): Seg {
    const fb = snap.fallback
    if (!fb || fb.segments < 1 || !fb.allowed.includes(s.country)
      || (fb.validFrom != null && t < fb.validFrom) || (fb.validUntil != null && t + SEG > fb.validUntil)) return standby(i)
    const pos = i * SEG, dur = fb.segments * SEG
    return { key: 'fb:' + Math.floor(pos / dur), media: fb.key, n: Math.floor((((pos % dur) + dur) % dur) / SEG) }
  }

  function seg(i: number): Seg {
    const t = time(i)
    if (!live) return standby(i)
    if (!offered) return geo(i)   // channel not offered here: no programmes, no ads, no markers
    const e = at(t)
    if (!e) return fallback(i, t)
    if (e.kind === 'PROGRAM') {
      if (!e.key) return standby(i)
      if (!e.allowed.includes(s.country)) return geo(i)
      const n = Math.floor((e.offsetMs + t - e.start) / SEG)
      return n < e.segments ? { key: 'e:' + e.id, media: e.key, n } : standby(i)
    }
    const d = s.adMode === 'SSAI' && ours(s, e) ? decided(e.id) : undefined
    if (d && d !== 'slate') {
      // count-preserving swap: the break's slate segments are replaced one for one; underfill leaves slate
      let off = t - e.start
      for (let k = 0; k < d.slots.length; k++) {
        const slot = d.slots[k]
        if (off < slot.durationMs)
          return { key: `ad:${e.id}:${k}`, media: slot.mediaKey, n: Math.floor(off / SEG), ad: { breakId: e.id, slot, segs: Math.floor(slot.durationMs / SEG) } }
        off -= slot.durationMs
      }
    }
    return standby(i)
  }
  return { seg, at, time, live, offered, SEG }
}

export const index = (t: number, seg: number) => Math.floor((t - EPOCH) / seg)

/** SSAI breaks this response will show (need a decision now) and those starting soon (prefetch). */
export function breaksToDecide(snap: Snapshot, s: Session, now: number) {
  const edgeT = EPOCH + index(now, snap.segMs) * snap.segMs   // start of the segment after the live edge
  const firstT = edgeT - WINDOW * snap.segMs
  const now_: Item[] = [], soon: Item[] = []
  if (s.adMode !== 'SSAI' || snap.status !== 'LIVE' || !snap.territories.includes(s.country)) return { now: now_, soon }
  for (const e of snap.items) {
    if (e.kind !== 'AD_BREAK' || !ours(s, e)) continue
    if (e.start < edgeT && e.end > firstT) now_.push(e)
    else if (e.start >= edgeT && e.start < edgeT + LOOKAHEAD_MS) soon.push(e)
  }
  return { now: now_, soon }
}

/**
 * One rendition's live media playlist. dsnMemo (per Worker isolate) keeps DISCONTINUITY-SEQUENCE cheap: it counts
 * content changes since this viewer's last playlist instead of since the session started.
 * ponytail: per-isolate memos (DSN, decisions); move them to the Cache API if viewers hop isolates mid-break.
 */
export function mediaPlaylist(snap: Snapshot, s: Session, variant: number, now: number, base: string,
                              decided: (breakId: string) => Decided, dsnMemo: Map<string, [number, number]>) {
  const r = resolver(snap, s, decided)
  const SEG = r.SEG
  const edge = index(now, SEG) - 1                  // newest segment that has fully "aired"
  const first = edge - WINDOW + 1
  const origin = index(s.created, SEG) - WINDOW
  const memo = dsnMemo.get(s.sid)
  const startIdx = memo && memo[0] <= first && memo[0] >= origin ? memo[0] : Math.min(origin, first)
  let dsn = memo && startIdx === memo[0] ? memo[1] : 0
  let prevKey = r.seg(startIdx).key
  for (let i = startIdx + 1; i <= first; i++) {
    const k = r.seg(i).key
    if (k !== prevKey) dsn++
    prevKey = k
  }
  dsnMemo.set(s.sid, [first, dsn])

  let m = `#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:${SEG / 1000}\n#EXT-X-INDEPENDENT-SEGMENTS\n`
  m += `#EXT-X-MEDIA-SEQUENCE:${first}\n#EXT-X-DISCONTINUITY-SEQUENCE:${dsn}\n`
  m += `## FAST Studio edge playout | channel=${snap.channel} | ad mode=${s.adMode} | markers=${s.markers} | simulated viewer country=${s.country}\n`
  const announced = new Set<string>()
  const beacons: Beacon[] = []
  const showMarkers = r.live && r.offered
  // SGAI: announce upcoming breaks ahead of the live edge so the player can fetch the ad list
  // before it reaches the break (players stall if an interstitial first appears at its own start).
  if (showMarkers && s.adMode === 'SGAI') {
    const from = r.time(edge + 1), until = from + LOOKAHEAD_MS
    for (const e of snap.items)
      if (e.kind === 'AD_BREAK' && ours(s, e) && e.start >= from && e.start < until && !announced.has(e.id)) {
        announced.add(e.id)
        m += interstitial(e)
      }
  }
  let prev: Seg | null = null
  for (let i = first; i <= edge; i++) {
    const t = r.time(i)
    const g = r.seg(i)
    const disc = prev != null && g.key !== prev.key
    if (disc) m += '#EXT-X-DISCONTINUITY\n'
    if (prev == null || disc) m += `#EXT-X-PROGRAM-DATE-TIME:${iso(t)}\n`
    if (showMarkers) m += markers(s, r.at, t, i === first, SEG, announced)
    m += `#EXTINF:${secs(SEG)},\n${base}/${g.media}/${variant}/seg_${pad(g.n)}.ts\n`
    if (g.ad) {
      const { n } = g, segs = g.ad.segs
      const ev = n === 0 ? 'IMPRESSION' : n === segs - 1 ? 'COMPLETE' : n === Math.floor(segs / 4) ? 'FIRST_QUARTILE'
        : n === Math.floor(segs / 2) ? 'MIDPOINT' : n === Math.floor(3 * segs / 4) ? 'THIRD_QUARTILE' : null
      // server-side confirmation: the spot counts when its segment enters the viewer's window
      if (ev) beacons.push({ breakId: g.ad.breakId, campaignId: g.ad.slot.campaignId, event: ev })
    }
    prev = g
  }
  return { text: m, beacons }

  function interstitial(e: Item) {
    const d = secs(e.end - e.start)
    return `#EXT-X-DATERANGE:ID="ad-${e.id}",CLASS="com.apple.hls.interstitial",START-DATE="${iso(e.start)}",DURATION=${d},X-ASSET-LIST="asset-list/${e.id}.json",X-RESUME-OFFSET=${d},X-RESTRICT="SKIP,JUMP"\n`
  }

  function markers(s: Session, at: (t: number) => Item | null, t: number, firstInWindow: boolean, SEG: number, announced: Set<string>) {
    let out = ''
    let cur = at(t)
    if (cur && cur.kind !== 'AD_BREAK') cur = null
    let before = at(t - SEG)
    if (before && (before.kind !== 'AD_BREAK' || before.end !== t)) before = null
    const ended = before != null && (cur == null || cur.id !== before.id)
    if (s.adMode === 'SGAI') {
      if (cur && ours(s, cur) && (t === cur.start || firstInWindow) && !announced.has(cur.id)) {
        announced.add(cur.id)
        out += interstitial(cur)
      }
      return out
    }
    if (s.markers === 'CUE_OUT') {
      if (ended) out += '#EXT-X-CUE-IN\n'
      if (cur) {
        const d = secs(cur.end - cur.start)
        out += t === cur.start ? `#EXT-X-CUE-OUT:DURATION=${d}\n` : `#EXT-X-CUE-OUT-CONT:ElapsedTime=${secs(t - cur.start)},Duration=${d}\n`
      }
      return out
    }
    if (ended && before) out += `#EXT-X-DATERANGE:ID="${before.id}",START-DATE="${iso(before.start)}",DURATION=${secs(before.end - before.start)},SCTE35-IN=${before.scteIn}\n`
    if (cur && (t === cur.start || firstInWindow) && !announced.has(cur.id)) {
      announced.add(cur.id)
      out += `#EXT-X-DATERANGE:ID="${cur.id}",START-DATE="${iso(cur.start)}",PLANNED-DURATION=${secs(cur.end - cur.start)},SCTE35-OUT=${cur.scteOut}\n`
    }
    return out
  }
}

/** SGAI: the ads a player plays over one break, padded with slate so it rejoins the live timeline at break end. */
export function assetList(snap: Snapshot, brk: Item, d: Decided, base: string, slateUrl: (seconds: number) => string) {
  const slots = d && d !== 'slate' ? d.slots : []
  const assets: Record<string, unknown>[] = slots.map(s => ({
    URI: `${base}/${s.mediaKey}/master.m3u8`, DURATION: s.durationMs / 1000, 'X-CAMPAIGN-ID': s.campaignId, 'X-CAMPAIGN': s.campaign,
  }))
  const rest = brk.end - brk.start - slots.reduce((a, s) => a + s.durationMs, 0)
  if (rest >= snap.segMs) assets.push({ URI: slateUrl(Math.floor(rest / 1000)), DURATION: rest / 1000 })
  return { ASSETS: assets }
}

/** VOD slate of the given length (SGAI padding), looping the standby clip. */
export function slatePlaylist(snap: Snapshot, seconds: number, base: string) {
  const SEG = snap.segMs, total = Math.max(1, snap.standby.segments)
  const n = Math.max(1, Math.floor(Math.min(seconds, 600) * 1000 / SEG))
  let m = `#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${SEG / 1000}\n#EXT-X-PLAYLIST-TYPE:VOD\n`
  for (let i = 0; i < n; i++) {
    if (i > 0 && i % total === 0) m += '#EXT-X-DISCONTINUITY\n'
    m += `#EXTINF:${secs(SEG)},\n${base}/${snap.standby.key}/0/seg_${pad(i % total)}.ts\n`
  }
  return m + '#EXT-X-ENDLIST\n'
}
