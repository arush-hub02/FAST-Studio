// Builds docs/html/API.html: every backend route, grouped by feature, explained in plain words: parameters, request
// body fields (read from the Java records: type, required, rules), a copy-paste curl, a real response example and a
// downloadable Postman collection.
// Sources: docs/api/api.json (explanations, examples), docs/api/glossary.json (field and parameter meanings),
// docs/api/responses.json (answers recorded by `node tools/check-api-docs.mjs --save`), routes from the controllers.
// Fails when a route has no entry, an entry has no route, or a field/parameter has no explanation.
// Usage: node tools/build-api-docs.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { routes } from './api-routes.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const read = f => JSON.parse(fs.readFileSync(path.join(root, f), 'utf8'))
const data = read('docs/api/api.json'), glossary = read('docs/api/glossary.json')
const responses = fs.existsSync(path.join(root, 'docs/api/responses.json')) ? read('docs/api/responses.json') : {}
const all = routes()
const byKey = new Map(all.map(r => [r.method + ' ' + r.path, r]))
const documented = new Set()
const problems = []

function describe(fields, overrides, key, prefix = '') {
  return (fields || []).map(f => {
    const help = overrides?.[f.name] ?? glossary.fields[f.name]
    if (!help) problems.push(`${key}: body field "${prefix}${f.name}" needs an explanation (docs/api/glossary.json "fields", or "fields" on the endpoint)`)
    return { ...f, help: help || '', fields: f.fields ? describe(f.fields, overrides, key, prefix + f.name + '.') : null }
  })
}

for (const c of data.categories)
  for (const e of c.endpoints) {
    const key = e.method + ' ' + e.path
    const r = byKey.get(key)
    if (!r) { problems.push(`api.json documents ${key}, which no controller serves`); continue }
    if (documented.has(key)) problems.push(`${key} is documented twice`)
    documented.add(key)
    for (const k of ['title', 'description']) if (!e[k]) problems.push(`${key} needs a ${k}`)
    if (r.bodyType && !e.body && !e.noExample && r.bodyType !== 'byte[]') problems.push(`${key} takes a ${r.bodyType} body: add an example "body"`)
    const pathParams = [...e.path.matchAll(/\{(\w+)\}/g)].map(m => m[1]).filter(n => !e.noExample || n)
    e.pathParams = pathParams.map(name => {
      const help = e.paramHelp?.[name] ?? glossary.params[name]
      if (!help) problems.push(`${key}: path parameter {${name}} needs an explanation`)
      return { name, help, example: e.params?.[name] ?? null }
    })
    e.queryParams = r.query.map(q => {
      const help = e.paramHelp?.[q.name] ?? glossary.params[q.name]
      if (!help) problems.push(`${key}: query parameter ${q.name} needs an explanation`)
      return { ...q, help, example: e.query?.[q.name] ?? null }
    })
    if (e.form) e.formFields = Object.keys(e.form).map(name => ({ name, type: 'string', required: name === 'grant_type', rules: [], help: glossary.params[name] || '' }))
    e.bodyFields = describe(e.bodyFields || r.bodyFields, e.fields, key)
    e.response = responses[key] || null
    delete e.checkAccepts
  }
for (const r of all) if (!documented.has(r.method + ' ' + r.path)) problems.push(`${r.method} ${r.path} (${r.controller}) has no entry in docs/api/api.json`)
if (problems.length) { console.error(problems.join('\n')); process.exit(1) }

data.generatedAt = new Date().toISOString().slice(0, 10)
data.routeCount = all.length
data.responseCount = Object.keys(responses).length
const template = fs.readFileSync(path.join(root, 'docs/api/api.template.html'), 'utf8')
if (!template.includes('/*__API__*/null')) throw new Error('Template placeholder missing')
fs.mkdirSync(path.join(root, 'docs/html'), { recursive: true })
fs.writeFileSync(path.join(root, 'docs/html/API.html'), template.replace('/*__API__*/null', () => JSON.stringify(data).replaceAll('</', '<\\/')))
console.log(`docs/html/API.html: ${all.length} routes in ${data.categories.length} categories, ${data.responseCount} response examples`)
