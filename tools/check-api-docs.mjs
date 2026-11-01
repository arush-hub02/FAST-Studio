// Runs the examples of docs/api/api.json against a running FAST Studio (demo mode) to prove the copy-paste curls work.
// - Every GET example runs for real (customer owner, partner or platform admin, as its category needs).
// - Write examples on /api/orgs are sent as the read-only analyst: a 403 means the request and its body passed
//   validation and only the permission stopped it, so nothing changes. A 400 means the example body is wrong.
// - A few side-effect-free POSTs (checks, previews, decoders, sign-in) run for real.
// - Platform and partner writes are not run (they would change data); their bodies are checked by the build only.
// Usage: node tools/check-api-docs.mjs [baseUrl] [--save]  (--save: keep the answers as response examples)
//   (default http://localhost:8080, demo password demo1234)
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const base = process.argv.slice(2).find(a => !a.startsWith('--')) || 'http://localhost:8080'
const data = JSON.parse(fs.readFileSync(path.join(root, 'docs/api/api.json'), 'utf8'))
const SAFE_POSTS = new Set(['/api/auth/token', '/api/orgs/{org}/rights/check', '/api/orgs/{org}/ads/simulate', '/api/orgs/{org}/channels/{id}/programming/preview',
  '/api/tools/scte35/decode', '/api/platform/email/templates/{key}/preview', '/play/sessions'])

async function call(method, url, token, body, form) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (form) delete headers['Content-Type']
  const res = await fetch(url, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) })
  const text = await res.text()
  let json = null
  try { json = JSON.parse(text) } catch {}
  return { status: res.status, json, text }
}
async function login(email) {
  const r = await call('POST', base + '/api/auth/token', null, { email, password: 'demo1234' })
  if (r.status !== 200) throw new Error(`sign-in ${email}: ${r.status} ${r.text}`)
  return r.json
}
const first = (list, pick) => (Array.isArray(list) ? list : []).map(pick).find(Boolean)

const owner = await login('owner@aurora.test'), analyst = await login('analyst@aurora.test')
const partner = await login('partner@partnertv.test'), platform = await login('admin@platform.test')
const v = Object.fromEntries(data.variables.map(x => [x.key, x.value]))
v.baseUrl = base
v.org = 'aurora'
v.orgId = owner.org.id
v.project = owner.org.id
const get = async (p, t = owner.access_token) => (await call('GET', base + p, t)).json
const channels = await get('/api/orgs/aurora/channels')
v.channelId = first(channels, c => c.channel?.id)
const channelSlug = v.channelSlug = first(channels, c => c.channel?.slug)
const assets = await get('/api/orgs/aurora/assets')
v.assetId = first(assets, a => a.asset?.status === 'READY' && a.asset.kind !== 'AD' && a.asset.id)
v.campaignId = first(await get('/api/orgs/aurora/campaigns'), c => c.id || c.campaign?.id)
v.destinationId = first((await get('/api/orgs/aurora/destinations'))?.destinations, d => d.id)
v.partnerOrgId = first((await get('/api/orgs/aurora/partners/directory'))?.partners, p => p.id)
v.userId = first(await get('/api/orgs/aurora/members'), m => m.userId || m.id || m.user?.id)
v.linkId = first(await get('/api/orgs/aurora/partners'), l => l.id || l.link?.id)
v.tenant = first(await get('/api/platform/tenants', platform.access_token), t => t.slug === 'aurora' && t.id)
const session = await call('POST', base + '/play/sessions', null, { org: 'aurora', channel: channelSlug, country: 'IN' })
v.sessionId = session.json?.sessionId
for (const [k, val] of Object.entries(v)) if (val == null) console.log(`(could not find a demo value for ${k})`)

const fill = s => String(s).replace(/\{\{(\w+)\}\}/g, (m, k) => v[k] ?? m)
const PATH = { org: '{{org}}', orgId: '{{orgId}}', tenant: '{{tenant}}', project: '{{project}}', partner: '{{partner}}', owner: '{{owner}}',
  channelId: '{{channelId}}', destinationId: '{{destinationId}}', linkId: '{{linkId}}', userId: '{{userId}}', session: '{{sessionId}}' }
const tokenFor = c => c.startsWith('platform') ? platform.access_token : c === 'partner-api' ? partner.access_token : owner.access_token

// --save keeps each real answer as the page's response example: lists cut to 2 items, long text cut, and anything
// secret or signed replaced (tokens, keys, signatures, signed media and playback links), never committed as is.
const responses = {}
const SECRET_KEYS = /token|secret|password|signature|^sig$|^key$|^x$|^y$|^d$|hash/i
function clean(s) {
  return String(s).replace(/eyJ[\w-]+\.[\w-]+\.[\w-]+/g, '<token>').replace(/fsp_[\w-]+/g, 'fsp_<token>')
    .replace(/(https?:\/\/[^\s"']*)?\/media\/s\/[^\s"']*/g, '<signed media link>').replace(/\/live\/[^\s"'/…]+/g, '/live/<signed session token>').replace(/([?&](token|sig|signature|t)=)[^&\s"']+/g, '$1<signed>')
}
function shorten(v, depth = 0) {
  if (Array.isArray(v)) return v.length > 2 ? [...v.slice(0, 2).map(x => shorten(x, depth + 1)), `… ${v.length - 2} more`] : v.map(x => shorten(x, depth + 1))
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, SECRET_KEYS.test(k) && typeof x === 'string' ? '<hidden>' : shorten(x, depth + 1)]))
  if (typeof v === 'string') { const c = clean(v); return c.length > 160 ? c.slice(0, 160) + '…' : c }
  return v
}
const rows = []
let failed = 0
for (const c of data.categories)
  for (const e of c.endpoints) {
    if (e.noExample) { rows.push(['skip', e, 'no example (signed links / webhooks)']); continue }
    let p = e.path.replace(/\{(\w+)\}/g, (m, k) => (e.params && e.params[k]) || PATH[k] || m)
    const q = Object.entries(e.query || {}).filter(([, x]) => x !== '').map(([k, x]) => k + '=' + encodeURIComponent(x)).join('&')
    const url = fill(base + p + (q ? '?' + q : ''))
    const body = e.body === undefined ? undefined : JSON.parse(fill(JSON.stringify(e.body)))
    const auth = e.auth === 'none' ? null : tokenFor(c.id)
    if (/PASTE|\{\w+\}/.test(url) || (body && /PASTE/.test(JSON.stringify(body)))) { rows.push(['skip', e, 'needs a value the demo can\'t provide']); continue }
    let mode, r
    if (e.method === 'GET' || SAFE_POSTS.has(e.path)) { mode = 'run'; r = await call(e.method, url, auth, body) }
    else if (url.startsWith(base + '/api/orgs/')) {
      mode = 'validate'
      let form
      if (e.multipart) { form = new FormData(); form.append('file', new Blob(['not a video']), 'clip.mp4') }
      r = await call(e.method, url, analyst.access_token, form ? undefined : body ?? {}, form)
    }
    else if (/{(tenant|orgId|id|project)}/.test(e.path) && url.startsWith(base + '/api/platform/')) {
      // an ID that does not exist: a 400 means the example body is wrong, a 404 means it passed and nothing changed
      mode = 'validate'
      const missing = e.path.replace(/{(tenant|orgId|id|project)}/g, '00000000-0000-0000-0000-000000000000').replace(/\{(\w+)\}/g, (m, k) => (e.params && e.params[k]) || PATH[k] || m)
      r = await call(e.method, fill(base + missing), platform.access_token, body)
      if (r.status === 404) r.status = 403   // counted like the analyst check: validated, refused, unchanged
    }
    else { rows.push(['skip', e, 'changes platform or partner data; not run']); continue }
    const ok = (mode === 'run' ? r.status < 300 : r.status === 403 || r.status < 300) || (e.checkAccepts || []).includes(r.status)
    if (!ok) failed++
    rows.push([ok ? mode + ' ✓' : mode + ' ✗', e, r.status + (ok ? '' : ' ' + r.text.slice(0, 160))])
    if (mode === 'run' && r.status < 300 && e.path !== '/api/auth/csrf')
      responses[e.method + ' ' + e.path] = { status: r.status, body: r.json != null ? shorten(r.json) : clean(r.text.slice(0, 700) + (r.text.length > 700 ? '\n…' : '')) }
  }
if (process.argv.includes('--save')) {
  fs.writeFileSync(path.join(root, 'docs/api/responses.json'), JSON.stringify(responses, null, 2) + '\n')
  console.log(`docs/api/responses.json: ${Object.keys(responses).length} example answers (trimmed; tokens, keys and signed links replaced)`)
}
for (const [m, e, note] of rows) console.log(`${m.padEnd(11)} ${e.method.padEnd(6)} ${e.path}  ${note}`)
const count = k => rows.filter(r => r[0].startsWith(k)).length
console.log(`\n${count('run')} run, ${count('validate')} validated (nothing changed), ${count('skip')} skipped, ${failed} failed`)
process.exit(failed ? 1 : 0)
