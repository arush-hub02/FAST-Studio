// Programmes streaming from their platform, spliced with our ads and slate. Run: npm test
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { type Decided, type Session, type Snapshot, EPOCH, mediaPlaylist } from './playout.ts'
import { parseMaster, parseMedia, pick } from './ovp.ts'

const SEG = 5000
const T = EPOCH + 2000 * SEG               // the streaming programme starts here (on the grid)
const FROM = T - 60_000                    // snapshot anchor: 12 grid slots before it
const SEQ0 = (FROM - EPOCH) / SEG
// Mux-like: five 5 s segments and a short last one (28.857 s) in a 30 s slot: 1.143 s of slate follows
const D = [5000, 5000, 5000, 5000, 5000, 3857]
const snap: Snapshot = {
  v: 2, org: 'aurora', channel: 'news', status: 'LIVE', territories: ['IN'], segMs: SEG, from: FROM, until: T + 3600_000,
  ladder: [{ name: '360p', width: 640, height: 360, bandwidth: 800000, codecs: 'avc1.4d401e,mp4a.40.2' },
    { name: '720p', width: 1280, height: 720, bandwidth: 2900000, codecs: 'avc1.4d401f,mp4a.40.2' }],
  standby: { key: '_platform/standby-5s', segments: 12 }, geo: { key: '_platform/geo-5s', segments: 6 }, fallback: null,
  t0: FROM, seq0: SEQ0, target: 5, ovp: { a1: { url: 'https://stream.example/p1.m3u8', d: D, fmt: 'TS' } },
  items: [
    { id: 'p1', kind: 'PROGRAM', key: null, segments: 0, offsetMs: 0, start: T, end: T + 30_000, allowed: ['IN'], ownership: null, scteOut: null, scteIn: null,
      ovp: 'a1', seq: SEQ0 + 12, n: 7, i0: 0, nc: 6, last: 0, fill: [1143] },
    { id: 'b1', kind: 'AD_BREAK', key: null, segments: 0, offsetMs: 0, start: T + 30_000, end: T + 60_000, allowed: ['IN'], ownership: 'PROVIDER', scteOut: 'OUT', scteIn: 'IN' },
    { id: 'p2', kind: 'PROGRAM', key: 'aurora/film.1', segments: 100, offsetMs: 0, start: T + 60_000, end: T + 120_000, allowed: ['IN'], ownership: null, scteOut: null, scteIn: null },
  ],
}
const viewer: Session = { sid: 's1', org: 'aurora', channel: 'news', country: 'IN', adMode: 'SSAI', markers: 'CUE_OUT', destKind: 'OWNED_WEB', created: FROM }
const ad15: Decided = { breakMs: 30_000, filledMs: 15_000, slots: [{ campaignId: 'c1', campaign: 'Glow Cola', mediaKey: 'aurora/ad.1', durationMs: 15_000 }] }
const muxUrl = (asset: string, v: number, i: number) => `https://cdn.example/${asset}/r${v}/s${i}.ts`
const mux = (asset: string, v: number, i: number) => ({ url: muxUrl(asset, v, i) })
const play = (now: number, seg = mux, s = viewer, variant = 0) => mediaPlaylist(snap, s, variant, now, 'https://m', () => ad15, new Map(), seg).text

/** [media sequence, EXTINF ms, uri] of every segment in a playlist. */
function parse(t: string) {
  const lines = t.split('\n'), out: [number, number, string][] = []
  let seq = Number(lines.find(l => l.startsWith('#EXT-X-MEDIA-SEQUENCE:'))!.split(':')[1])
  for (let i = 0; i < lines.length; i++)
    if (lines[i].startsWith('#EXTINF:')) out.push([seq++, Math.round(Number(lines[i].slice(8, -1)) * 1000), lines[i + 1]])
  return out
}

test('platform segments play at their real lengths and the slot ends with slate', () => {
  const segs = parse(play(T + 45_000))
  const prog = segs.filter(s => s[2].startsWith('https://cdn.example/'))
  assert.deepEqual(prog.map(s => s[1]), D)
  assert.deepEqual(prog.map(s => s[2]), D.map((_, i) => muxUrl('a1', 0, i)))
  const after = segs[segs.findIndex(s => s[2] === muxUrl('a1', 0, 5)) + 1]
  assert.equal(after[1], 1143)
  assert.ok(after[2].includes('standby'))
  assert.ok(segs.some(s => s[2].includes('aurora/ad.1/')), 'our ad follows in the break')
})

test('a gap shorter than a segment plays the black piece of that length when the edge has one', () => {
  const withBlack = { ...snap, fillers: { '1143': { key: '_platform/black-29f-q6', segments: 1, levels: [0, 2] } } }
  const segs = parse(mediaPlaylist(withBlack, viewer, 1, T + 45_000, 'https://m', () => ad15, new Map(), mux).text)
  const after = segs[segs.findIndex(s => s[2] === muxUrl('a1', 1, 5)) + 1]
  assert.equal(after[1], 1143)
  assert.equal(after[2], 'https://m/_platform/black-29f-q6/0/seg_00000.ts', 'nearest lower level of the filler, its only piece')
  const t = mediaPlaylist(withBlack, viewer, 1, T + 45_000, 'https://m', () => ad15, new Map(), mux).text
  assert.match(t, /DISCONTINUITY\n(#EXT-X-PROGRAM-DATE-TIME:[^\n]+\n)?#EXTINF:1\.143,\nhttps:\/\/m\/_platform\/black/, 'a discontinuity before it')
})

test('listed lengths add up to the clock across the programme, the break and the next programme', () => {
  const segs = parse(play(T + 75_000))
  const total = segs.reduce((a, s) => a + s[1], 0)
  const t = play(T + 75_000)
  const firstDate = Date.parse(t.split('\n').find(l => l.startsWith('#EXT-X-PROGRAM-DATE-TIME:'))!.slice(25))
  assert.equal(firstDate + total, T + 75_000 - (T + 75_000 - T - 60_000) % SEG, 'the window ends at the newest finished segment')
})

test('numbering is identical across reloads and the snapshot anchor holds before the programme', () => {
  const byNumber = new Map<number, string>()
  for (let now = T - 20_000; now < T + 130_000; now += 1_700)
    for (const [seq, , uri] of parse(play(now))) {
      if (byNumber.has(seq)) assert.equal(byNumber.get(seq), uri, `segment ${seq} changed between reloads`)
      byNumber.set(seq, uri)
    }
  // grid slots before the programme keep the clock's numbers
  assert.equal(parse(play(T))[11][0], SEQ0 + 11)
  // after it: 12 grid slots + 7 programme segments, then the break on the grid again
  assert.equal([...byNumber].find(([, u]) => u.includes('aurora/ad.1/'))?.[0], SEQ0 + 12 + 7)
})

test('each quality level gets the matching platform rendition', () => {
  assert.ok(play(T + 45_000, mux, viewer, 1).includes(muxUrl('a1', 1, 0)))
})

test('platform unreachable: the programme plays slate for the same lengths, numbering unchanged', () => {
  const down = parse(play(T + 45_000, () => null)), up = parse(play(T + 45_000))
  assert.deepEqual(down.map(s => [s[0], s[1]]), up.map(s => [s[0], s[1]]))
  assert.ok(down.every(s => !s[2].startsWith('https://cdn.example/')))
  assert.equal(down.filter(s => s[2].includes('standby')).length, up.filter(s => s[2].includes('standby')).length + 6)
})

test('a viewer where the programme is not licensed gets geo slate of the same lengths', () => {
  const t = play(T + 45_000, mux, { ...viewer, country: 'GB' })
  assert.ok(!t.includes('cdn.example'))
})

test('TARGETDURATION follows the snapshot', () => {
  const t = mediaPlaylist({ ...snap, target: 7 }, viewer, 0, T + 45_000, 'https://m', () => ad15, new Map(), mux).text
  assert.ok(t.includes('#EXT-X-TARGETDURATION:7\n'))
})

test('platform playlists: renditions, nearest quality and absolute segment URLs', () => {
  const master = '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=480x270,CODECS="avc1.64001e"\nrendition.m3u8?rid=a\n' +
    '#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nhttps://x.example/r/720.m3u8?token=t\n'
  const v = parseMaster(master, 'https://stream.example/p1.m3u8')
  assert.deepEqual(v.map(x => x.height), [270, 720])
  assert.equal(v[0].uri, 'https://stream.example/rendition.m3u8?rid=a')
  assert.equal(pick(v, snap.ladder[0]), 0)
  assert.equal(pick(v, snap.ladder[1]), 1)
  assert.deepEqual(parseMedia('#EXTM3U\n#EXTINF:5,\nseg0.ts?sig=1\n#EXTINF:5,\nhttps://cdn/1.ts\n#EXT-X-ENDLIST', 'https://a.example/r/720.m3u8'),
    { urls: ['https://a.example/r/seg0.ts?sig=1', 'https://cdn/1.ts'], map: undefined })
  assert.deepEqual(parseMedia('#EXTM3U\n#EXT-X-MAP:URI="init.mp4?sig=2"\n#EXTINF:5,\nseg0.m4s\n#EXT-X-ENDLIST', 'https://a.example/r/720.m3u8'),
    { urls: ['https://a.example/r/seg0.m4s'], map: 'https://a.example/r/init.mp4?sig=2' })
})

test('fMP4 channel: our media from its fMP4 copies, each source announced with its own init section', () => {
  const fsnap: Snapshot = { ...snap, fmt: 'FMP4', ovp: { a1: { ...snap.ovp!.a1, fmt: 'FMP4' } } }
  const fmux = (a: string, v: number, i: number) => ({ url: `https://cdn.example/${a}/r${v}/s${i}.m4s`, map: `https://cdn.example/${a}/r${v}/init.mp4` })
  const t = mediaPlaylist(fsnap, viewer, 0, T + 45_000, 'https://m', () => ad15, new Map(), fmux).text
  const lines = t.split('\n')
  assert.ok(t.startsWith('#EXTM3U\n#EXT-X-VERSION:7\n'))
  assert.ok(!t.includes('.ts\n'), 'no MPEG-TS segment in an fMP4 channel')
  // every segment comes after a map for its own source
  let map = ''
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('#EXT-X-MAP:')) map = /URI="([^"]+)"/.exec(lines[i])![1]
    if (lines[i].startsWith('#EXTINF:')) {
      const uri = lines[i + 1]
      assert.ok(map, 'a map before the first segment')
      assert.equal(map.slice(0, map.lastIndexOf('/')), uri.slice(0, uri.lastIndexOf('/')), `segment ${uri} uses map ${map}`)
    }
  }
  assert.ok(t.includes('#EXT-X-MAP:URI="https://cdn.example/a1/r0/init.mp4"'))
  assert.ok(t.includes('#EXT-X-MAP:URI="https://m/aurora/ad.1/v0/init.mp4"'), 'our media: its video-only copy')
  assert.ok(t.includes('https://m/aurora/ad.1/v0/seg_00000.m4s'))
})

test('fMP4 channel: a separate audio track with the platform audio and our audio, on the same timeline', async () => {
  const { masterPlaylist } = await import('./playout.ts')
  const { AUDIO, parseAudio } = await import('./ovp.ts')
  const fsnap: Snapshot = { ...snap, fmt: 'FMP4', ovp: { a1: { ...snap.ovp!.a1, fmt: 'FMP4' } } }
  const master = masterPlaylist(fsnap)
  assert.ok(master.includes('#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud"') && master.includes('URI="a.m3u8"'), master)
  assert.ok(master.split('\n').filter(l => l.startsWith('#EXT-X-STREAM-INF')).every(l => l.includes('AUDIO="aud"')))
  assert.ok(!masterPlaylist(snap).includes('TYPE=AUDIO'), 'MPEG-TS channels keep audio inside the video segments')

  const fmux = (a: string, v: number, i: number) => v === AUDIO
    ? { url: `https://cdn.example/${a}/audio/s${i}.m4s`, map: `https://cdn.example/${a}/audio/init.mp4` }
    : { url: `https://cdn.example/${a}/r${v}/s${i}.m4s`, map: `https://cdn.example/${a}/r${v}/init.mp4` }
  const at = T + 45_000
  const video = mediaPlaylist(fsnap, viewer, 0, at, 'https://m', () => ad15, new Map(), fmux).text
  const audio = mediaPlaylist(fsnap, viewer, AUDIO, at, 'https://m', () => ad15, new Map(), fmux).text
  assert.ok(audio.includes('https://cdn.example/a1/audio/s0.m4s'), 'the platform audio during the programme')
  assert.ok(audio.includes('https://m/aurora/ad.1/a/seg_00000.m4s') && audio.includes('https://m/aurora/ad.1/a/init.mp4'), 'our audio during the ad')
  const lens = (t: string) => t.split('\n').filter(l => l.startsWith('#EXTINF') || l.startsWith('#EXT-X-MEDIA-SEQUENCE') || l === '#EXT-X-DISCONTINUITY')
  assert.deepEqual(lens(audio), lens(video), 'audio and video list the same numbers, lengths and switches')
  assert.ok(!audio.includes('CUE-OUT') && !audio.includes('DATERANGE'), 'markers only on the video playlists')
  assert.equal(parseAudio('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="x",DEFAULT=YES,URI="aud/lo.m3u8"\n', 'https://p.example/m/master.m3u8'), 'https://p.example/m/aud/lo.m3u8')
  assert.equal(parseAudio('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n', 'https://p.example/m.m3u8'), null)
})
