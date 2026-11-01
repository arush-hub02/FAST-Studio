// Builds docs/html/API.html: every backend route, grouped by feature, explained in plain words with a copy-paste curl
// and a downloadable Postman collection. Content: docs/api/api.json; routes: read from the controllers.
// Fails when a route has no entry in api.json, or an entry no longer matches a route.
// Usage: node tools/build-api-docs.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { routes } from './api-routes.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const data = JSON.parse(fs.readFileSync(path.join(root, 'docs/api/api.json'), 'utf8'))
const all = routes()
const byKey = new Map(all.map(r => [r.method + ' ' + r.path, r]))
const documented = new Set()
const problems = []
for (const c of data.categories)
  for (const e of c.endpoints) {
    const key = e.method + ' ' + e.path
    const r = byKey.get(key)
    if (!r) { problems.push(`api.json documents ${key}, which no controller serves`); continue }
    if (documented.has(key)) problems.push(`${key} is documented twice`)
    documented.add(key)
    for (const k of ['title', 'description']) if (!e[k]) problems.push(`${key} needs a ${k}`)
    if (r.bodyType && !e.body && !e.noExample && r.bodyType !== 'byte[]') problems.push(`${key} takes a ${r.bodyType} body: add an example "body"`)
    e.route = { query: r.query, bodyType: r.bodyType }
  }
for (const r of all) if (!documented.has(r.method + ' ' + r.path)) problems.push(`${r.method} ${r.path} (${r.controller}) has no entry in docs/api/api.json`)
if (problems.length) { console.error(problems.join('\n')); process.exit(1) }

data.generatedAt = new Date().toISOString().slice(0, 10)
data.routeCount = all.length
const template = fs.readFileSync(path.join(root, 'docs/api/api.template.html'), 'utf8')
if (!template.includes('/*__API__*/null')) throw new Error('Template placeholder missing')
fs.mkdirSync(path.join(root, 'docs/html'), { recursive: true })
fs.writeFileSync(path.join(root, 'docs/html/API.html'), template.replace('/*__API__*/null', () => JSON.stringify(data).replaceAll('</', '<\\/')))
console.log(`docs/html/API.html: ${all.length} routes in ${data.categories.length} categories`)
