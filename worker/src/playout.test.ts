// Run: npm test (Node 22+ strips the types itself)
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type Decided, type Session, type Snapshot, EPOCH, assetList, mediaPlaylist } from './playout.ts'

const SEG = 5000
const T0 = EPOCH + 1000 * SEG
const snap: Snapshot = {
  v: 1, org: 'aurora', channel: 'news', status: 'LIVE', territories: ['IN'], segMs: SEG, from: T0 - 3600_000, until: T0 + 3600_000,
  ladder: [{ name: '360p', width: 640, height: 360, bandwidth: 800000, codecs: 'avc1.4d401e,mp4a.40.2' }],
  standby: { key: '_platform/standby-5s', segments: 12 }, geo: { key: '_platform/geo-5s', segments: 6 }, fallback: null,
  items: [
    { id: 'p1', kind: 'PROGRAM', key: 'aurora/film.1', segments: 100, offsetMs: 0, start: T0, end: T0 + 60_000, allowed: ['IN'], ownership: null, scteOut: null, scteIn: null },
    { id: 'b1', kind: 'AD_BREAK', key: null, segments: 0, offsetMs: 0, start: T0 + 60_000, end: T0 + 90_000, allowed: ['IN'], ownership: 'PROVIDER', scteOut: 'OUT', scteIn: 'IN' },
    { id: 'p2', kind: 'PROGRAM', key: 'aurora/film.1', segments: 100, offsetMs: 60_000, start: T0 + 90_000, end: T0 + 300_000, allowed: ['IN'], ownership: null, scteOut: null, scteIn: null },
  ],
}
const viewer = (over: Partial<Session> = {}): Session => ({
  sid: 's1', org: 'aurora', channel: 'news', country: 'IN', adMode: 'SSAI', markers: 'CUE_OUT', destKind: 'OWNED_WEB', created: T0, ...over,
})
const ad15: Decided = { breakMs: 30_000, filledMs: 15_000, slots: [{ campaignId: 'c1', campaign: 'Glow Cola', mediaKey: 'aurora/ad.1', durationMs: 15_000 }] }
const play = (now: number, d: Decided, s = viewer()) => mediaPlaylist(snap, s, 0, now, 'https://m', () => d, new Map())
const lines = (t: string) => t.split('\n')
const segs = (t: string) => lines(t).filter(l => l.endsWith('.ts'))

test('ad swap preserves segment count and media sequence', () => {
  const now = T0 + 100_000   // window covers the whole break
  const slate = play(now, 'slate').text, ads = play(now, ad15).text
  assert.equal(segs(ads).length, segs(slate).length)
  assert.equal(lines(ads).find(l => l.startsWith('#EXT-X-MEDIA-SEQUENCE')), lines(slate).find(l => l.startsWith('#EXT-X-MEDIA-SEQUENCE')))
  assert.equal(segs(ads).filter(l => l.includes('aurora/ad.1/')).length, 3)          // 15 s spot = 3 slate segments replaced
  assert.equal(segs(ads).filter(l => l.includes('standby')).length, 3)               // underfill: slate covers the rest
  assert.equal(segs(slate).filter(l => l.includes('standby')).length, 6)
})

test('avails are balanced, carry durations, and are never zero-length', () => {
  const t = play(T0 + 100_000, 'slate').text
  assert.equal(lines(t).filter(l => l === '#EXT-X-CUE-OUT:DURATION=30.000').length, 1)
  assert.equal(lines(t).filter(l => l === '#EXT-X-CUE-IN').length, 1)
  assert.equal(lines(t).filter(l => l.startsWith('#EXT-X-CUE-OUT-CONT:')).length, 5)
  assert.ok(!t.includes('#EXT-X-CUE-OUT:DURATION=30.000\n#EXT-X-CUE-IN'))
})

test('a viewer joining mid-break gets the continuation tag and only the rest of the break', () => {
  const now = T0 + 60_000 + 2 * SEG + 12 * SEG + 1   // window starts at the break's third segment
  const s = viewer({ created: now })
  const t = play(now, ad15, s).text
  assert.ok(!t.includes('#EXT-X-CUE-OUT:DURATION'))
  assert.ok(t.includes('#EXT-X-CUE-OUT-CONT:ElapsedTime=10.000,Duration=30.000'))
  assert.equal(segs(t).filter(l => l.includes('aurora/ad.1/')).length, 1)            // only the spot's last segment remains
})

test('the same instant gives the same playlist (no state needed across Worker instances)', () => {
  assert.equal(play(T0 + 77_777, ad15).text, play(T0 + 77_777, ad15).text)
})

test('delivered spots are reported when their segments enter the window', () => {
  const evs = play(T0 + 100_000, ad15).beacons.map(b => b.event)
  assert.deepEqual(evs, ['IMPRESSION', 'MIDPOINT', 'COMPLETE'])
})

test('SGAI asset list pads with slate so the viewer rejoins live at break end', () => {
  const list = assetList(snap, snap.items[1], ad15, 'https://m', s => `slate/${s}.m3u8`)
  assert.deepEqual(list.ASSETS.map(a => a.DURATION), [15, 15])
})
