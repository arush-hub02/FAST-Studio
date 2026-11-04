// Builds docs/html/FEATURES.html from docs/features/features.json and the screenshots in docs/features/img/.
// Usage: node tools/build-feature-docs.mjs   (after `npm run docs:shots` in frontend/ when screens changed)
// Fails when a feature is missing required fields; reports screenshots that haven't been taken yet.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const dir = path.join(root, 'docs', 'features')
const data = JSON.parse(fs.readFileSync(path.join(dir, 'features.json'), 'utf8'))

// API lines that name an endpoint ("GET /api/…") link to its card in docs/html/API.html (same id rule as that page)
const api = JSON.parse(fs.readFileSync(path.join(root, 'docs', 'api', 'api.json'), 'utf8'))
const endpoints = api.categories.flatMap(c => c.endpoints).map(e => ({ method: e.method,
  // the path as a pattern: {param} matches any one segment (so ".../properties/limits.*" finds ".../properties/{key}")
  re: new RegExp('^' + e.path.split(/\{\w+\}/).map(p => p.replace(/[.*+?^$()|[\]\\]/g, '\\$&')).join('[^/\\s]+') + '$'),
  id: 'ep-' + (e.method + e.path).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-$/, '') }))
function apiLink(text) {
  const m = text.match(/^(GET|POST|PUT|PATCH|DELETE) (\/\S+)/)
  const e = m && endpoints.find(x => x.method === m[1] && x.re.test(m[2]))
  return { text, link: e ? 'API.html#' + e.id : null }
}
/** Width and height of a PNG (from its header), so the page reserves the space before the picture loads. */
const pngSize = file => { const b = fs.readFileSync(file); return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) } }

const required = ['id', 'title', 'area', 'status', 'completed', 'summary', 'who', 'steps', 'flow', 'howItWorks', 'optimizations', 'scale', 'rules', 'screenshots']
const ids = new Set()
const missing = []
for (const f of data.features) {
  for (const k of required)
    if (f[k] === undefined || (Array.isArray(f[k]) && !f[k].length && k !== 'screenshots'))   // empty lists don't count
      throw new Error(`Feature ${f.id ?? '?'} is missing "${k}"`)
  if (ids.has(f.id)) throw new Error(`Duplicate feature id ${f.id}`)
  ids.add(f.id)
  for (const s of f.screenshots) {
    const file = `${f.id}--${s.id}.png`
    s.src = `../features/img/${file}`
    s.exists = fs.existsSync(path.join(dir, 'img', file))
    if (!s.exists) missing.push(file)
    else Object.assign(s, pngSize(path.join(dir, 'img', file)))
    delete s.steps; delete s.mask; delete s.target           // recipes are for the screenshot runner, not the page
  }
  {   // the animated flow: every step must stand on a known box
    const nodes = new Set(f.flow.nodes.map(n => n.id))
    for (const s of f.flow.scenarios.flatMap(x => x.steps))
      for (const id of [s.at, ...(s.down || [])]) if (!nodes.has(id)) throw new Error(`Feature ${f.id}: flow step names unknown box "${id}"`)
  }
  delete f.cleanup
  f.api = (f.api || []).map(apiLink)
  for (const k of ['tests', 'docs']) f[k] = f[k] || []
}
data.generatedAt = new Date().toISOString().slice(0, 10)

const template = fs.readFileSync(path.join(dir, 'features.template.html'), 'utf8')
if (!template.includes('/*__FEATURES__*/null')) throw new Error('Template placeholder missing')
const html = template.replace('/*__FEATURES__*/null', () => JSON.stringify(data).replaceAll('</', '<\\/'))
fs.mkdirSync(path.join(root, 'docs', 'html'), { recursive: true })
fs.writeFileSync(path.join(root, 'docs', 'html', 'FEATURES.html'), html)
console.log(`docs/html/FEATURES.html: ${data.features.length} features`)
if (missing.length) console.log(`Screenshots not taken yet (run npm run docs:shots in frontend/): ${missing.join(', ')}`)
