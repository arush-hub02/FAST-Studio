// Builds docs/FEATURES.html from docs/features/features.json and the screenshots in docs/features/img/.
// Usage: node tools/build-feature-docs.mjs   (after `npm run docs:shots` in frontend/ when screens changed)
// Fails when a feature is missing required fields; reports screenshots that haven't been taken yet.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const dir = path.join(root, 'docs', 'features')
const data = JSON.parse(fs.readFileSync(path.join(dir, 'features.json'), 'utf8'))

const required = ['id', 'title', 'area', 'status', 'completed', 'summary', 'who', 'steps', 'howItWorks', 'optimizations', 'scale', 'rules', 'screenshots']
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
    s.src = `features/img/${file}`
    s.exists = fs.existsSync(path.join(dir, 'img', file))
    if (!s.exists) missing.push(file)
    delete s.steps; delete s.mask; delete s.target           // recipes are for the screenshot runner, not the page
  }
  delete f.cleanup
}
data.generatedAt = new Date().toISOString().slice(0, 10)

const template = fs.readFileSync(path.join(dir, 'features.template.html'), 'utf8')
if (!template.includes('/*__FEATURES__*/null')) throw new Error('Template placeholder missing')
const html = template.replace('/*__FEATURES__*/null', () => JSON.stringify(data).replaceAll('</', '<\\/'))
fs.writeFileSync(path.join(root, 'docs', 'FEATURES.html'), html)
console.log(`docs/FEATURES.html: ${data.features.length} features`)
if (missing.length) console.log(`Screenshots not taken yet (run npm run docs:shots in frontend/): ${missing.join(', ')}`)
