// FAST Studio edge: everything a viewer touches. Live playlists are computed per request from the channel's
// schedule snapshot (KV) and the clock; video comes straight from R2. The control plane (Java, CONTROL_URL) is
// asked only for ad decisions, once per viewer and break, and told about delivered ads. If it is down, channels
// keep playing with slate in the breaks.
//
//   GET  /live/{token}/master.m3u8                  quality ladder (token = signed viewer session)
//   GET  /live/{token}/v{n}.m3u8                    live media playlist of rendition n
//   GET  /live/{token}/asset-list/{breakId}.json    SGAI: the ads for one break
//   GET  /live/{token}/slate/{seconds}.m3u8         SGAI: slate padding
//   GET  /media/{key}                               packaged media from R2 (production serves R2's own domain)
//   PUT  /_ingest/media/{key}                       control plane -> R2      (Bearer EDGE_SECRET)
//   PUT  /_ingest/channels/{org}/{channel}          control plane -> KV      (Bearer EDGE_SECRET)

import {
  type Beacon, type Decided, type Decision, type Session, type Snapshot,
  assetList, breaksToDecide, masterPlaylist, mediaPlaylist, slatePlaylist,
} from './playout.ts'
export { BreakDecision } from './decision.ts'

interface Env {
  MEDIA: any              // R2 bucket
  SCHEDULES: any          // KV namespace
  EDGE_SECRET: string     // shared with the control plane (faststudio.edge.secret)
  CONTROL_URL: string     // the Java control plane, e.g. http://127.0.0.1:8080
  MEDIA_BASE?: string     // public R2 domain in production; blank = this Worker's /media route
  DECISIONS: any          // Durable Objects: stable ads/slate for each viewer and break
}
type Ctx = { waitUntil(p: Promise<unknown>): void }
type Viewer = { ip: string; ua: string }

const HLS = 'application/vnd.apple.mpegurl'
const CORS = { 'Access-Control-Allow-Origin': '*' }
const SNAPSHOT_TTL_MS = 5_000

// per-isolate memory (see the ponytail note in playout.ts)
const snapshots = new Map<string, { at: number; snap: Snapshot | null }>()
const decisions = new Map<string, Decided>()
const dsnMemo = new Map<string, [number, number]>()
const reported = new Set<string>()
let hmacKey: CryptoKey | null = null

export default {
  async fetch(req: Request, env: Env, ctx: Ctx): Promise<Response> {
    const url = new URL(req.url)
    const p = url.pathname
    try {
      if (p.startsWith('/_ingest/')) return await ingest(req, env, p)
      if (p.startsWith('/media/') && (req.method === 'GET' || req.method === 'HEAD')) return await media(req, env, p.slice(7))
      const m = p.match(/^\/live\/([^/]+)\/(.+)$/)
      if (m && req.method === 'GET') return await live(req, env, ctx, url, m[1], m[2])
      return text('Not found', 404)
    } catch (e) {
      console.error(p, e)
      return text('Edge error', 500)
    }
  },
}

// ------------------------------------------------------------------ viewer: live playlists

async function live(req: Request, env: Env, ctx: Ctx, url: URL, token: string, rest: string): Promise<Response> {
  const s = await verify(env, token)
  if (!s) return text('Invalid or tampered stream link', 403)
  // ad servers target and fraud-check on the real viewer, not on us
  const viewer: Viewer = { ip: req.headers.get('CF-Connecting-IP') ?? '', ua: req.headers.get('User-Agent') ?? '' }
  const snap = await snapshot(env, s.org, s.channel)
  if (!snap) return new Response('Channel is not on the edge yet', { status: 503, headers: { ...CORS, 'Retry-After': '5' } })
  const base = env.MEDIA_BASE || `${url.origin}/media`
  const now = Date.now()

  if (rest === 'master.m3u8') return playlist(masterPlaylist(snap))
  const v = rest.match(/^v(\d+)\.m3u8$/)
  if (v) {
    const variant = Number(v[1])
    if (variant >= snap.ladder.length) return text('No such rendition', 404)
    const { now: needed, soon } = breaksToDecide(snap, s, now)
    await Promise.all(needed.map(b => decide(env, s, b.id, true, viewer)))
    soon.forEach(b => ctx.waitUntil(decide(env, s, b.id, false, viewer)))
    const out = mediaPlaylist(snap, s, variant, now, base, id => decisions.get(`${s.sid}:${id}`), dsnMemo)
    ctx.waitUntil(report(env, s, out.beacons))
    return playlist(out.text)
  }
  const a = rest.match(/^asset-list\/([0-9a-f-]+)\.json$/)
  if (a) {
    const brk = snap.items.find(e => e.id === a[1] && e.kind === 'AD_BREAK')
    if (!brk) return text('No such ad break', 404)
    await decide(env, s, brk.id, true, viewer)
    const body = assetList(snap, brk, decisions.get(`${s.sid}:${brk.id}`), base, secs => `${url.origin}/live/${token}/slate/${secs}.m3u8`)
    return new Response(JSON.stringify(body), { headers: { ...CORS, 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } })
  }
  const sl = rest.match(/^slate\/(\d+)\.m3u8$/)
  if (sl) return playlist(slatePlaylist(snap, Number(sl[1]), base), 'max-age=3600')
  return text('Not found', 404)
}

/**
 * Asks the control plane once per viewer and break. A break that has started without a decision is locked to slate
 * (lock = true), so a late answer never swaps segments the viewer already has in their playlist.
 */
async function decide(env: Env, s: Session, breakId: string, lock: boolean, viewer: Viewer) {
  const k = `${s.sid}:${breakId}`
  if (decisions.get(k) !== undefined) return
  if (decisions.size > 50_000) decisions.clear()
  try {
    const object = env.DECISIONS.get(env.DECISIONS.idFromName(`${s.org}:${s.sid}:${breakId}`))
    const res = await object.fetch('https://decision.internal/', {
      method: 'POST', body: JSON.stringify({ sid: s.sid, breakId, lock, ip: viewer.ip, ua: viewer.ua }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    if (decisions.get(k) === undefined) decisions.set(k, (await res.json()) as Decision)
  } catch (e) {
    console.warn('ad decision failed, slate plays', k, String(e))
    // A binding failure must not issue media that could change after recovery. The player retries its old window.
    if (lock) throw e
  }
}

/** Delivery events to the control plane, once per isolate; the database ignores repeats. */
async function report(env: Env, s: Session, beacons: Beacon[]) {
  if (reported.size > 200_000) reported.clear()
  for (const b of beacons) {
    const k = `${s.sid}:${b.breakId}:${b.campaignId}:${b.event}`
    if (reported.has(k)) continue
    reported.add(k)
    // fire-and-forget: a count is never worth a stall
    await fetch(`${env.CONTROL_URL}/play/${s.sid}/beacons`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b), signal: AbortSignal.timeout(2000),
    }).then(res => { if (!res.ok) reported.delete(k) }).catch(() => reported.delete(k))
  }
}

async function snapshot(env: Env, org: string, channel: string): Promise<Snapshot | null> {
  const k = `ch/${org}/${channel}`
  const hit = snapshots.get(k)
  if (hit && Date.now() - hit.at < SNAPSHOT_TTL_MS) return hit.snap
  // KV's edge cache: schedule edits reach every location within ~30 s
  const snap = (await env.SCHEDULES.get(k, { type: 'json', cacheTtl: 30 })) as Snapshot | null
  snapshots.set(k, { at: Date.now(), snap })
  return snap
}

/** token = base64url(session json) + "." + base64url(HMAC-SHA256(EDGE_SECRET, first part)). */
async function verify(env: Env, token: string): Promise<Session | null> {
  const dot = token.lastIndexOf('.')
  if (dot < 1) return null
  const payload = token.slice(0, dot)
  hmacKey ??= await crypto.subtle.importKey('raw', new TextEncoder().encode(env.EDGE_SECRET), { name: 'HMAC', hash: 'SHA-256' }, false, ['verify'])
  let sig: Uint8Array
  try { sig = b64url(token.slice(dot + 1)) } catch { return null }
  if (!(await crypto.subtle.verify('HMAC', hmacKey, sig, new TextEncoder().encode(payload)))) return null
  return JSON.parse(new TextDecoder().decode(b64url(payload))) as Session
}

function b64url(s: string): Uint8Array {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - s.length % 4) % 4))
  return Uint8Array.from(bin, c => c.charCodeAt(0))
}

// ------------------------------------------------------------------ media (R2)

async function media(req: Request, env: Env, key: string): Promise<Response> {
  const obj = req.method === 'HEAD' ? await env.MEDIA.head(key) : await env.MEDIA.get(key)
  if (!obj) return text('Not found', 404)
  // keys carry a packaging revision and are never overwritten, so any cache may keep them forever
  const headers = { ...CORS, 'Content-Type': obj.httpMetadata?.contentType ?? 'application/octet-stream',
    'Cache-Control': 'public, max-age=31536000, immutable', ETag: obj.httpEtag }
  return new Response(req.method === 'HEAD' ? null : obj.body, { headers })
}

// ------------------------------------------------------------------ control plane: ingest

async function ingest(req: Request, env: Env, p: string): Promise<Response> {
  if (req.method !== 'PUT') return text('Method not allowed', 405)
  if (!env.EDGE_SECRET || env.EDGE_SECRET.length < 32 || !(await sameSecret(req.headers.get('Authorization') ?? '', `Bearer ${env.EDGE_SECRET}`)))
    return text('Unauthorized', 401)
  if (p.startsWith('/_ingest/media/')) {
    const key = p.slice('/_ingest/media/'.length)
    if (!/^[A-Za-z0-9_.\/-]+$/.test(key) || key.includes('..')) return text('Bad key', 400)
    await env.MEDIA.put(key, req.body, { httpMetadata: { contentType: req.headers.get('Content-Type') ?? 'application/octet-stream' } })
    return new Response(null, { status: 204 })
  }
  const c = p.match(/^\/_ingest\/channels\/([a-z0-9-]+)\/([a-z0-9-]+)$/)
  if (c) {
    const body = await req.text()
    JSON.parse(body)   // reject garbage before it reaches viewers
    await env.SCHEDULES.put(`ch/${c[1]}/${c[2]}`, body)
    snapshots.delete(`ch/${c[1]}/${c[2]}`)
    return new Response(null, { status: 204 })
  }
  return text('Not found', 404)
}

async function sameSecret(a: string, b: string) {
  const [x, y] = await Promise.all([a, b].map(v => crypto.subtle.digest('SHA-256', new TextEncoder().encode(v))))
  const u = new Uint8Array(x), w = new Uint8Array(y)
  let diff = 0
  for (let i = 0; i < u.length; i++) diff |= u[i] ^ w[i]
  return diff === 0
}

const playlist = (body: string, cache = 'no-store') =>
  new Response(body, { headers: { ...CORS, 'Content-Type': HLS, 'Cache-Control': cache } })
const text = (body: string, status: number) => new Response(body, { status, headers: { ...CORS, 'Content-Type': 'text/plain' } })
