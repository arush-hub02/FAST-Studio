// Ads played straight from the ad server's HLS (Direct delivery). Run: npm test
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type Decided, type Session, type Snapshot, EPOCH, assetList, mediaPlaylist } from './playout.ts'
import * as ovp from './ovp.ts'

const SEG = 5000
const T0 = EPOCH + 1000 * SEG
const HLS = 'https://ads.cdn/a/master.m3u8'
const base = (over: Partial<Snapshot> = {}): Snapshot => ({
  v: 1, org: 'aurora', channel: 'news', status: 'LIVE', territories: ['IN'], segMs: SEG, from: T0 - 3600_000, until: T0 + 3600_000,
  ladder: [{ name: '360p', width: 640, height: 360, bandwidth: 800000, codecs: 'avc1.4d401e,mp4a.40.2' }],
  standby: { key: '_platform/standby-5s', segments: 12 }, geo: { key: '_platform/geo-5s', segments: 6 }, fallback: null,
  items: [
    { id: 'p1', kind: 'PROGRAM', key: 'aurora/film.1', segments: 100, offsetMs: 0, start: T0, end: T0 + 60_000, allowed: ['IN'], ownership: null, scteOut: null, scteIn: null },
    { id: 'b1', kind: 'AD_BREAK', key: null, segments: 0, offsetMs: 0, start: T0 + 60_000, end: T0 + 90_000, allowed: ['IN'], ownership: 'PROVIDER', scteOut: 'OUT', scteIn: 'IN' },
    { id: 'p2', kind: 'PROGRAM', key: 'aurora/film.1', segments: 100, offsetMs: 60_000, start: T0 + 90_000, end: T0 + 300_000, allowed: ['IN'], ownership: null, scteOut: null, scteIn: null },
  ],
  ...over,
})
const viewer: Session = { sid: 's1', org: 'aurora', channel: 'news', country: 'IN', adMode: 'SSAI', markers: 'CUE_OUT', destKind: 'OWNED_WEB', created: T0 }
const direct: Decided = { breakMs: 30_000, filledMs: 15_000, slots: [{ campaignId: 'd1', campaign: 'GAM: Spot', mediaKey: null, durationMs: 15_000, delivery: 'DIRECT', hlsUrl: HLS }] }
const tsAd = (_url: string, v: number, n: number) => ({ url: `https://ads.cdn/a/v${v}/seg${n}.ts` })
const fmp4Ad = (_url: string, v: number, n: number) => ({ url: `https://ads.cdn/a/${v < 0 ? 'audio' : 'v' + v}/seg${n}.m4s`, map: `https://ads.cdn/a/${v < 0 ? 'audio' : 'v' + v}/init.mp4` })
const now = T0 + 100_000   // the window covers the whole break

test('a DIRECT slot lists the ad server pieces in its 5 s steps, with DISCONTINUITY around them', () => {
  const out = mediaPlaylist(base(), viewer, 0, now, 'https://m', () => direct, new Map(), undefined, tsAd)
  const lines = out.text.split('\n')
  const ad = lines.filter(l => l.startsWith('https://ads.cdn/'))
  assert.deepEqual(ad, ['https://ads.cdn/a/v0/seg0.ts', 'https://ads.cdn/a/v0/seg1.ts', 'https://ads.cdn/a/v0/seg2.ts'])
  const first = lines.indexOf(ad[0])
  assert.ok(lines.slice(first - 4, first).includes('#EXT-X-DISCONTINUITY'))
  assert.equal(lines.filter(l => l.includes('standby')).length, 3, 'the rest of the break is slate')
  assert.ok(out.beacons.some(b => b.campaignId === 'd1' && b.event === 'IMPRESSION'))
})

test('unread direct ad plays slate', () => {
  const text = mediaPlaylist(base(), viewer, 0, now, 'https://m', () => direct, new Map(), undefined, () => null).text
  assert.equal(text.split('\n').filter(l => l.startsWith('https://ads.cdn/')).length, 0)
  assert.equal(text.split('\n').filter(l => l.includes('standby')).length, 6)
})

test('direct slot of the other container plays slate', () => {
  const fmp4 = base({ fmt: 'FMP4' })
  const text = mediaPlaylist(fmp4, viewer, 0, now, 'https://m', () => direct, new Map(), undefined, tsAd).text
  assert.equal(text.split('\n').filter(l => l.startsWith('https://ads.cdn/')).length, 0, 'TS ad pieces never go into an fMP4 playlist')
  const ts = mediaPlaylist(base(), viewer, 0, now, 'https://m', () => direct, new Map(), undefined, fmp4Ad).text
  assert.equal(ts.split('\n').filter(l => l.startsWith('https://ads.cdn/')).length, 0, 'nor fMP4 pieces into a TS one')
})

test('fmp4 split channel lists the ad audio piece in the audio playlist, with its init section', () => {
  const text = mediaPlaylist(base({ fmt: 'FMP4' }), viewer, ovp.AUDIO, now, 'https://m', () => direct, new Map(), undefined, fmp4Ad).text
  const lines = text.split('\n')
  assert.ok(lines.includes('https://ads.cdn/a/audio/seg0.m4s'), text)
  assert.ok(lines.includes('#EXT-X-MAP:URI="https://ads.cdn/a/audio/init.mp4"'))
})

test('SGAI asset list uses the ad HLS URL for DIRECT slots', () => {
  const brk = base().items[1]
  const list = assetList(base(), brk, direct, 'https://m', s => `https://x/slate/${s}.m3u8`)
  assert.equal(list.ASSETS[0].URI, HLS)
})

test('adSegment returns piece n of the variant nearest our level after loadAd; a plain media playlist is one variant', async () => {
  ovp.reset()
  const pages: Record<string, string> = {
    [HLS]: '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nv720/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nv360/index.m3u8\n',
    'https://ads.cdn/a/v360/index.m3u8': '#EXTM3U\n#EXTINF:5.0,\ns0.ts\n#EXTINF:5.0,\ns1.ts\n#EXT-X-ENDLIST\n',
    'https://ads.cdn/b/index.m3u8': '#EXTM3U\n#EXTINF:5.0,\nb0.ts\n#EXT-X-ENDLIST\n',
  }
  const real = globalThis.fetch
  globalThis.fetch = (async (u: string) => pages[u] ? new Response(pages[u]) : new Response('', { status: 404 })) as typeof fetch
  try {
    await ovp.loadAd(base(), HLS, 0)
    assert.deepEqual(ovp.adSegment(HLS, 0, 1), { url: 'https://ads.cdn/a/v360/s1.ts', map: undefined })
    assert.equal(ovp.adSegment(HLS, 0, 5), null)
    await ovp.loadAd(base(), 'https://ads.cdn/b/index.m3u8', 0)
    assert.deepEqual(ovp.adSegment('https://ads.cdn/b/index.m3u8', 0, 0), { url: 'https://ads.cdn/b/b0.ts', map: undefined })
  } finally { globalThis.fetch = real; ovp.reset() }
})

test('an ad whose HLS could not be read stays slate for its break (no flip to the ad mid-slot)', async () => {
  ovp.reset()
  const url = 'https://ads.cdn/flaky/master.m3u8'
  let calls = 0, up = false
  const real = globalThis.fetch
  globalThis.fetch = (async () => { calls++; return up ? new Response('#EXTM3U\n#EXTINF:5.0,\ns0.ts\n#EXT-X-ENDLIST\n') : new Response('', { status: 503 }) }) as typeof fetch
  try {
    const t = Date.now()
    await ovp.loadAd(base(), url, 0, t)
    assert.equal(ovp.adSegment(url, 0, 0), null)
    up = true
    await ovp.loadAd(base(), url, 0, t + 20_000)   // past the programme retry delay, within the ad's
    assert.equal(ovp.adSegment(url, 0, 0), null, 'still slate: the break must not change to the ad part-way')
    assert.equal(calls, 1)
  } finally { globalThis.fetch = real; ovp.reset() }
})
