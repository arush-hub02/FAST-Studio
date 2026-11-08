// Live playlists from a schedule snapshot and the clock: a pure function, so every Worker instance in every
// location computes the same playlist without coordinating. No I/O in this file (index.ts fetches snapshots, ad
// decisions and the platform playlists of streaming programmes).
//
// Numbering: one segment per 5 s slot of the grid, counted from the snapshot's anchor (t0, seq0), except inside a
// programme that streams from its platform (Mux, ...): there the platform's own segments play at their real lengths,
// followed by slate up to the programme's end, and the programme counts its own segment count n. The control plane
// computes the anchor and every streaming programme's seq/n (util/cloudflare/Timeline.java), so the numbers stay the
// same across snapshots. Snapshots without an anchor number by the clock alone: i = (t - EPOCH) / 5 s.

export const EPOCH = Date.parse('2026-01-01T00:00:00Z')
export const WINDOW = 12                 // segments in a live playlist
export const LOOKAHEAD_MS = 30_000       // ad decisions and SGAI interstitials are prepared this far ahead

export type Rendition = { name: string; width: number; height: number; bandwidth: number; codecs: string }
export type Media = { key: string | null; segments: number }
export type Fallback = { key: string; segments: number; allowed: string[]; validFrom: number | null; validUntil: number | null }
export type Item = {
  id: string; kind: 'PROGRAM' | 'AD_BREAK'; key: string | null; segments: number; offsetMs: number
  start: number; end: number; allowed: string[]; ownership: string | null; scteOut: string | null; scteIn: string | null
  // a programme streaming from its platform: asset id (key of Snapshot.ovp), numbering and layout
  ovp?: string | null; seq?: number | null; n?: number | null; i0?: number | null; nc?: number | null; last?: number | null; fill?: number[] | null
}
/** A video that streams from its platform: master playlist URL (null = can't be reached), real segment lengths in ms. */
export type Ovp = { url: string | null; d: number[]; fmt: string }
/** Published by the control plane (edge.EdgePublisher) whenever a channel's schedule changes. */
export type Snapshot = {
  v: number; org: string; channel: string; status: string; territories: string[]; segMs: number
  from: number; until: number; ladder: Rendition[]; standby: Media; geo: Media; fallback: Fallback | null; items: Item[]
  t0?: number; seq0?: number; target?: number; ovp?: Record<string, Ovp>
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
/** URL of segment i of a streaming video in our rendition `variant`; null while its playlist can't be read. */
export type OvpSegment = (asset: string, variant: number, i: number) => string | null

/** One segment: start and duration in ms, the content key (a change means DISCONTINUITY), and where it comes from. */
type Seg = { key: string; media: string; n: number; start: number; dur: number; url?: string; ad?: { breakId: string; slot: Slot; segs: number } }
/** A streaming programme in the timeline: its first sequence number, segment count and segment start offsets. */
export type Span = { item: Item; seq: number; n: number; offs: number[] }

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

const timelines = new WeakMap<Snapshot, { t0: number; seq0: number; spans: Span[] }>()

/** The snapshot's anchor and streaming programmes in time order, with segment offsets (computed once per snapshot). */
export function timeline(snap: Snapshot) {
  let tl = timelines.get(snap)
  if (tl) return tl
  const SEG = snap.segMs
  const t0 = snap.t0 ?? snap.from
  const seq0 = snap.seq0 ?? Math.floor((t0 - EPOCH) / SEG)
  const spans: Span[] = []
  for (const it of snap.items) {
    if (it.seq == null || it.n == null || it.n < 1) continue
    const d = it.ovp ? snap.ovp?.[it.ovp]?.d : undefined
    const nc = it.nc ?? 0, fill = it.fill ?? [], i0 = it.i0 ?? 0
    const offs = [0]
    for (let q = 0; q < it.n; q++) {
      const len = q < nc ? (q === nc - 1 && it.last ? it.last : d?.[i0 + q]) : fill[q - nc]
      offs.push(offs[q] + (len ?? (it.end - it.start) / it.n))
    }
    offs[it.n] = it.end - it.start   // the layout always fills the slot exactly
    spans.push({ item: it, seq: it.seq, n: it.n, offs })
  }
  spans.sort((a, b) => a.seq - b.seq)
  tl = { t0, seq0, spans }
  timelines.set(snap, tl)
  return tl
}

export function resolver(snap: Snapshot, s: Session, decided: (breakId: string) => Decided, ovpSeg: OvpSegment = () => null, variant = 0) {
  const SEG = snap.segMs
  const live = snap.status === 'LIVE'
  const offered = snap.territories.includes(s.country)
  const items = snap.items
  const { t0, seq0, spans } = timeline(snap)

  function at(t: number): Item | null {
    let lo = 0, hi = items.length - 1, best = -1
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1
      if (items[mid].start <= t) { best = mid; lo = mid + 1 } else hi = mid - 1
    }
    return best >= 0 && items[best].end > t ? items[best] : null
  }
  /** Last span whose key(span) <= x. */
  function spanBefore(x: number, key: (sp: Span) => number): Span | null {
    let lo = 0, hi = spans.length - 1, best: Span | null = null
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1
      if (key(spans[mid]) <= x) { best = spans[mid]; lo = mid + 1 } else hi = mid - 1
    }
    return best
  }
  /** Sequence number of the segment that contains instant t. */
  function seqAt(t: number): number {
    const sp = spanBefore(t, x => x.item.start)
    if (sp && t < sp.item.end) {
      const rel = t - sp.item.start
      let k = 0
      while (k + 1 < sp.n && sp.offs[k + 1] <= rel) k++
      return sp.seq + k
    }
    const [bt, bs] = sp ? [sp.item.end, sp.seq + sp.n] : [t0, seq0]
    return bs + Math.floor((t - bt) / SEG)
  }

  const loop = (prefix: string, m: Media, i: number) => {
    const n = Math.max(1, m.segments)
    return { key: prefix + Math.floor(i / n), media: m.key ?? '', n: ((i % n) + n) % n }
  }
  const standby = (i: number) => loop('sb:', snap.standby, i)
  const geo = (i: number) => loop('geo:', snap.geo, i)

  function fallback(i: number, t: number) {
    const fb = snap.fallback
    if (!fb || fb.segments < 1 || !fb.allowed.includes(s.country)
      || (fb.validFrom != null && t < fb.validFrom) || (fb.validUntil != null && t + SEG > fb.validUntil)) return standby(i)
    const pos = (t - EPOCH), dur = fb.segments * SEG
    return { key: 'fb:' + Math.floor(pos / dur), media: fb.key, n: Math.floor((((pos % dur) + dur) % dur) / SEG) }
  }

  /** The segment with sequence number i. */
  function seg(i: number): Seg {
    const sp = spanBefore(i, x => x.seq)
    if (sp && i < sp.seq + sp.n) return streamed(sp, i)
    const [bt, bs] = sp ? [sp.item.end, sp.seq + sp.n] : [t0, seq0]
    const t = bt + (i - bs) * SEG
    return { ...grid(i, t), start: t, dur: SEG }
  }

  /** A grid slot: R2 programmes, ad breaks, fallback and standby, exactly as before streaming programmes existed. */
  function grid(i: number, t: number): Omit<Seg, 'start' | 'dur'> {
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

  /** Segment k of a streaming programme: the platform's segment, or slate (filler, off air, geo, platform unreachable). */
  function streamed(sp: Span, i: number): Seg {
    const e = sp.item, k = i - sp.seq
    const start = e.start + sp.offs[k], dur = sp.offs[k + 1] - sp.offs[k]
    const slate = (g: { key: string; media: string; n: number }) => ({ ...g, start, dur })
    if (!live) return slate(standby(i))
    if (!offered || !e.allowed.includes(s.country)) return slate(geo(i))
    if (k >= (e.nc ?? 0) || !e.ovp) return slate(standby(i))
    const url = ovpSeg(e.ovp, variant, (e.i0 ?? 0) + k)
    if (!url) return { ...standby(i), key: 'sbx:' + e.id, start, dur }   // platform unreachable: slate for the whole programme
    return { key: 'e:' + e.id, media: '', n: k, start, dur, url }
  }

  return { seg, seqAt, at, live, offered, SEG }
}

/** Segment index on the plain 5 s grid (no anchor): kept for callers that only need the clock. */
export const index = (t: number, seg: number) => Math.floor((t - EPOCH) / seg)

/** Longest segment a playlist may list, in ms (streaming programmes may run longer than 5 s). */
const longest = (snap: Snapshot) => Math.max(snap.segMs, (snap.target ?? 0) * 1000)

/** SSAI breaks this response will show (need a decision now) and those starting soon (prefetch). */
export function breaksToDecide(snap: Snapshot, s: Session, now: number) {
  const edgeT = now
  const firstT = edgeT - (WINDOW + 1) * longest(snap)   // generous: a break barely outside the window is decided too
  const now_: Item[] = [], soon: Item[] = []
  if (s.adMode !== 'SSAI' || snap.status !== 'LIVE' || !snap.territories.includes(s.country)) return { now: now_, soon }
  for (const e of snap.items) {
    if (e.kind !== 'AD_BREAK' || !ours(s, e)) continue
    if (e.start < edgeT && e.end > firstT) now_.push(e)
    else if (e.start >= edgeT && e.start < edgeT + LOOKAHEAD_MS) soon.push(e)
  }
  return { now: now_, soon }
}

/** Streaming videos (asset ids) a playlist at this instant lists (now) and those starting soon (to read ahead). */
export function streamsInWindow(snap: Snapshot, now: number) {
  const cur = new Set<string>(), soon = new Set<string>()
  const from = now - (WINDOW + 1) * longest(snap)
  if (snap.status !== 'LIVE') return { now: [], soon: [] }
  for (const e of snap.items) {
    if (!e.ovp || !e.n) continue
    if (e.start < now && e.end > from) cur.add(e.ovp)
    else if (e.start >= now && e.start < now + LOOKAHEAD_MS) soon.add(e.ovp)
  }
  return { now: [...cur], soon: [...soon].filter(a => !cur.has(a)) }
}

/**
 * One rendition's live media playlist. dsnMemo (per Worker isolate) keeps DISCONTINUITY-SEQUENCE cheap: it counts
 * content changes since this viewer's last playlist instead of since the session started.
 * ponytail: per-isolate memos (DSN, decisions); move them to the Cache API if viewers hop isolates mid-break.
 */
export function mediaPlaylist(snap: Snapshot, s: Session, variant: number, now: number, base: string,
                              decided: (breakId: string) => Decided, dsnMemo: Map<string, [number, number]>, ovpSeg: OvpSegment = () => null) {
  const r = resolver(snap, s, decided, ovpSeg, variant)
  const SEG = r.SEG
  const edge = r.seqAt(now) - 1                     // newest segment that has fully "aired"
  const first = edge - WINDOW + 1
  const origin = r.seqAt(s.created) - WINDOW
  const memo = dsnMemo.get(s.sid)
  const startIdx = memo && memo[0] <= first && memo[0] >= origin ? memo[0] : Math.max(Math.min(origin, first), first - 20_000)
  let dsn = memo && startIdx === memo[0] ? memo[1] : 0
  let prevKey = r.seg(startIdx).key
  for (let i = startIdx + 1; i <= first; i++) {
    const k = r.seg(i).key
    if (k !== prevKey) dsn++
    prevKey = k
  }
  dsnMemo.set(s.sid, [first, dsn])

  let m = `#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:${Math.max(SEG / 1000, snap.target ?? 0)}\n#EXT-X-INDEPENDENT-SEGMENTS\n`
  m += `#EXT-X-MEDIA-SEQUENCE:${first}\n#EXT-X-DISCONTINUITY-SEQUENCE:${dsn}\n`
  m += `## FAST Studio edge playout | channel=${snap.channel} | ad mode=${s.adMode} | markers=${s.markers} | simulated viewer country=${s.country}\n`
  const announced = new Set<string>()
  const beacons: Beacon[] = []
  const showMarkers = r.live && r.offered
  // SGAI: announce upcoming breaks ahead of the live edge so the player can fetch the ad list
  // before it reaches the break (players stall if an interstitial first appears at its own start).
  if (showMarkers && s.adMode === 'SGAI') {
    const from = r.seg(edge + 1).start, until = from + LOOKAHEAD_MS
    for (const e of snap.items)
      if (e.kind === 'AD_BREAK' && ours(s, e) && e.start >= from && e.start < until && !announced.has(e.id)) {
        announced.add(e.id)
        m += interstitial(e)
      }
  }
  let prev: Seg = r.seg(first - 1)
  for (let i = first; i <= edge; i++) {
    const g = r.seg(i)
    const t = g.start
    const disc = i > first && g.key !== prev.key
    if (disc) m += '#EXT-X-DISCONTINUITY\n'
    if (i === first || disc) m += `#EXT-X-PROGRAM-DATE-TIME:${iso(t)}\n`
    if (showMarkers) m += markers(s, r.at, t, prev.start, i === first, announced)
    m += `#EXTINF:${secs(g.dur)},\n${g.url ?? `${base}/${g.media}/${variant}/seg_${pad(g.n)}.ts`}\n`
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

  function markers(s: Session, at: (t: number) => Item | null, t: number, prevT: number, firstInWindow: boolean, announced: Set<string>) {
    let out = ''
    let cur = at(t)
    if (cur && cur.kind !== 'AD_BREAK') cur = null
    let before = at(prevT)
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
