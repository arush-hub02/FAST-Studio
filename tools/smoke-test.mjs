// End-to-end smoke test against a running FAST Studio (default http://localhost:8080).
// Usage: node tools/smoke-test.mjs [baseUrl]
// Checks: login, tenant isolation, role enforcement, live playlist with SCTE-35, SSAI ad stitching, EPG/XMLTV.
const BASE = process.argv[2] ?? 'http://localhost:8080'
let failures = 0
const ok = (cond, label) => { console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`); if (!cond) failures++ }

function client() {
  const jar = new Map()
  const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ')
  async function req(path, { method = 'GET', body, accept = 'application/json' } = {}) {
    const headers = { Cookie: cookie(), Accept: accept }
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = decodeURIComponent(jar.get('XSRF-TOKEN') ?? '')
    if (body) headers['Content-Type'] = 'application/json'
    const res = await fetch(path.startsWith('http') ? path : BASE + path, { method, headers, body: body ? JSON.stringify(body) : undefined, redirect: 'manual' })
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(';'); const i = kv.indexOf('='); jar.set(kv.slice(0, i), kv.slice(i + 1)) }
    const text = await res.text()
    let json; try { json = JSON.parse(text) } catch { json = undefined }
    return { status: res.status, json, text }
  }
  return {
    req,
    async login(email) { await req('/api/auth/csrf'); return req('/api/auth/login', { method: 'POST', body: { email, password: 'demo1234' } }) },
  }
}

// The full run needs the demo data (demo mode). Against a real-data pilot it runs the checks that need no demo content.
if (!(await client().req('/api/auth/demo-accounts')).json?.enabled) {
  console.log('Pilot (non-demo) server: running the reduced checks. Set FASTSTUDIO_ADMIN_EMAIL / FASTSTUDIO_ADMIN_PASSWORD to also check the admin sign-in.\n')
  const c = client()
  ok((await c.login('owner@aurora.test')).status === 401, 'demo accounts cannot sign in')
  ok((await c.req('/api/auth/login', { method: 'POST', body: { email: 'x@y.z', password: 'x' } })).status === 401, 'unknown account rejected with 401')
  ok((await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status === 403, 'POST without CSRF token rejected')
  ok((await c.req('/api/platform/orgs')).status === 401, 'anonymous API access rejected')
  ok((await c.req('/media/aurora/x/master.m3u8', { accept: '*/*' })).status >= 400, 'unsigned media path is not served')
  ok((await c.req('/play/sessions', { method: 'POST', body: { org: 'aurora', channel: 'aurora-action', country: 'IN' } })).status === 404, 'no demo channel exists')
  const { FASTSTUDIO_ADMIN_EMAIL: email, FASTSTUDIO_ADMIN_PASSWORD: password } = process.env
  if (email && password) {
    const a = client()
    await a.req('/api/auth/csrf')
    ok((await a.req('/api/auth/login', { method: 'POST', body: { email, password } })).status === 200, 'administrator can sign in')
    const me = (await a.req('/api/auth/me')).json
    ok(me?.platformAdmin === true, 'administrator is a platform admin')
    if (me?.mustChangePassword) ok((await a.req('/api/platform/orgs')).json?.code === 'PASSWORD_CHANGE_REQUIRED', 'temporary password must be changed before anything else')
    else ok(Array.isArray((await a.req('/api/platform/orgs')).json), 'administrator lists organizations')
  }
  console.log(failures ? `\n${failures} check(s) FAILED` : '\nAll pilot checks passed')
  process.exit(failures ? 1 : 0)
}

const owner = client(), other = client(), analyst = client(), anon = client()
ok((await owner.login('owner@aurora.test')).status === 200, 'owner can sign in')
const bad = client()
await bad.req('/api/auth/csrf')
ok((await bad.req('/api/auth/login', { method: 'POST', body: { email: 'owner@aurora.test', password: 'wrong' } })).status === 401, 'wrong password rejected with 401')
ok((await other.login('owner@northwind.test')).status === 200, 'second tenant owner can sign in')
ok((await analyst.login('analyst@aurora.test')).status === 200, 'analyst can sign in')

const chans = (await owner.req('/api/orgs/aurora/channels')).json
ok(Array.isArray(chans) && chans.length >= 3, 'owner lists Aurora channels')
const action = chans.find(c => c.channel.slug === 'aurora-action').channel
ok((await other.req('/api/orgs/aurora/channels')).status === 404, 'tenant B cannot list tenant A channels (404, existence hidden)')
ok((await other.req(`/api/orgs/northwind/channels/${action.id}`)).status === 404, 'tenant B cannot read tenant A channel by ID through its own org')
ok((await analyst.req(`/api/orgs/aurora/channels/${action.id}/stop`, { method: 'POST' })).status === 403, 'analyst cannot stop a channel (403)')
ok((await anon.req('/api/orgs/aurora/channels')).status === 401, 'anonymous API access rejected')

const start = await anon.req('/play/sessions', { method: 'POST', body: { org: 'aurora', channel: 'aurora-action', country: 'IN' } })
ok(start.status === 200 && start.json.playlistUrl, 'public playback session starts for a live channel')
const master = (await anon.req(start.json.playlistUrl)).text
ok((master.match(/#EXT-X-STREAM-INF/g) ?? []).length === 3 && master.includes('RESOLUTION=1280x720'), 'master playlist offers the 360p/540p/720p ladder')
// live playlists come from the Cloudflare Worker (the edge), at the URL the session start hands out
const variant = (s, n = 0) => s.playlistUrl.replace(/master\.m3u8$/, `v${n}.m3u8`)
const pl = (await anon.req(variant(start.json))).text
const pl720 = (await anon.req(variant(start.json, 2))).text
ok(pl.startsWith('#EXTM3U') && /#EXT-X-MEDIA-SEQUENCE:\d+/.test(pl), 'live HLS media playlist served')
ok(/#EXTINF:5\.000,/.test(pl) && /#EXT-X-PROGRAM-DATE-TIME/.test(pl), 'playlist has 5 s segments and program-date-time')
ok(pl.match(/#EXT-X-MEDIA-SEQUENCE:\d+/)[0] === pl720.match(/#EXT-X-MEDIA-SEQUENCE:\d+/)[0], 'all renditions share the same media sequence (seamless quality switching)')

const edgeSeg = pl.split(/\r?\n/).find(l => l.endsWith('.ts'))
ok(!!edgeSeg && (await anon.req(edgeSeg, { accept: '*/*' })).status === 200, 'segments are served by the edge (R2)')
const token = start.json.playlistUrl.split('/live/')[1].split('/')[0]
const forged = start.json.playlistUrl.replace(token, token.split('.')[0] + '.AAAA')
ok((await anon.req(forged)).status === 403, 'a tampered stream token is rejected by the edge')

// signed operator media URLs (library previews): valid works, unsigned / tampered / other-folder do not
const segUrl = (await owner.req('/api/orgs/aurora/campaigns')).json.find(c => c.thumbUrl)?.thumbUrl
ok(!!segUrl, 'library media is served through signed /media/s/… URLs')
ok((await anon.req(segUrl, { accept: '*/*' })).status === 200, 'signed media URL works')
const [, , , exp, sig, scope, folder, ...file] = segUrl.split('/')
ok((await anon.req(`/media/${scope}/${folder}/${file.join('/')}`, { accept: '*/*' })).status >= 400, 'unsigned media path is not served')
ok((await anon.req(`/media/s/${exp}/${sig.slice(0, -2)}xx/${scope}/${folder}/${file.join('/')}`, { accept: '*/*' })).status === 403, 'tampered signature is rejected')
ok((await anon.req(`/media/s/${Number(exp) + 60}/${sig}/${scope}/${folder}/${file.join('/')}`, { accept: '*/*' })).status === 403, 'extended expiry is rejected')
ok((await anon.req(`/media/s/${exp}/${sig}/northwind/${folder}/${file.join('/')}`, { accept: '*/*' })).status === 403, 'signature cannot be reused for another tenant folder')

// look ahead through the published schedule for a break and check its decision + marker
const info = (await anon.req(`/play/${start.json.sessionId}/info`)).json
const brk = info.currentBreak ?? info.upcomingBreaks[0]
ok(brk && brk.scteOut.startsWith('0xFC'), 'ad breaks carry an SCTE-35 marker (table_id 0xFC)')
const dec = (await owner.req('/api/tools/scte35/decode', { method: 'POST', body: { value: brk.scteOut } })).json
ok(dec.crc_valid === true && dec.splice_command_name === 'time_signal', 'marker decodes with a valid CRC as time_signal')

const xml = await owner.req(`/api/orgs/aurora/channels/${action.id}/epg.xml`, { accept: 'application/xml' })
ok(xml.status === 200 && xml.text.includes('<programme'), 'XMLTV EPG export contains programmes')

const sim = (await owner.req('/api/orgs/aurora/ads/simulate', { method: 'POST', body: { channelId: action.id, country: 'IN' } })).json
ok(sim.decision && sim.decision.excluded.some(x => /Not targeting IN|paused/.test(x.reason)), 'ad decision explains excluded campaigns (targeting / paused)')
ok(sim.decision.filledMs <= sim.decision.breakMs, 'ads never overrun the break')

const de = await anon.req('/play/sessions', { method: 'POST', body: { org: 'aurora', channel: 'aurora-action', country: 'DE' } })
const dePl = (await anon.req(variant(de.json))).text
const deSegs = dePl.split(/\r?\n/).filter(l => l.endsWith('.ts'))
ok(deSegs.length > 0 && deSegs.every(l => l.includes('_platform/geo')) && !dePl.includes('#EXT-X-DATERANGE'), 'viewer outside channel countries gets only the region-block screen (no ads, no markers)')

const blocked = (await owner.req('/api/orgs/aurora/rights/check', { method: 'POST', body: { assetId: (await owner.req('/api/orgs/aurora/assets')).json.find(a => a.asset.title === 'Steel Horizon').asset.id, country: 'US' } })).json
ok(blocked.decision.allowed === false, 'India-only title is blocked for a US viewer')

console.log(failures ? `\n${failures} check(s) FAILED` : '\nAll checks passed')
process.exit(failures ? 1 : 0)
