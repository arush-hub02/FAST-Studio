// Traces the data flow of every API route through the backend source: controller method → services → repositories →
// the tables their SQL reads or writes. Static analysis of the Java source (method bodies, fields, SQL text), so it
// follows the code as written; calls through interfaces, lists of strategies and interceptors are not followed.
// Writes docs/db/data-flow.json, used by docs/html/API.html (tables per endpoint) and docs/html/visualizeDB.html
// (endpoints per table). Usage: node tools/data-flow.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { routes } from './api-routes.mjs'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const javaRoot = path.join(root, 'backend/src/main/java/com/faststudio')

const files = []
const walk = d => { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); fs.statSync(p).isDirectory() ? walk(p) : p.endsWith('.java') && files.push(p) } }
walk(javaRoot)

/** Blanks out comments (keeping line breaks) but leaves string literals and text blocks alone, so "https://…" survives. */
function strip(s) {
  let out = '', i = 0
  while (i < s.length) {
    if (s.startsWith('"""', i)) { const e = s.indexOf('"""', i + 3); const end = e < 0 ? s.length : e + 3; out += s.slice(i, end); i = end; continue }
    if (s[i] === '"' || s[i] === "'") { const q = s[i]; let j = i + 1; while (j < s.length && s[j] !== q && s[j] !== '\n') j += s[j] === '\\' ? 2 : 1; out += s.slice(i, j + 1); i = j + 1; continue }
    if (s.startsWith('//', i)) { while (i < s.length && s[i] !== '\n') i++; continue }
    if (s.startsWith('/*', i)) { const e = s.indexOf('*/', i + 2); const end = e < 0 ? s.length : e + 2; out += s.slice(i, end).replace(/[^\n]/g, ' '); i = end; continue }
    out += s[i++]
  }
  return out
}
const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized', 'super', 'this', 'throw', 'try', 'else'])

/** class name → { layer, file, fields: name → type, methods: name → body text } */
const classes = Object.create(null)
for (const f of files) {
  const raw = fs.readFileSync(f, 'utf8'), src = strip(raw)
  const name = path.basename(f, '.java')
  const rel = path.relative(path.join(root, 'backend/src/main/java'), f).replaceAll('\\', '/')
  const layer = /\/controllers\//.test(rel) ? 'controller' : /\/repositories\//.test(rel) ? 'repository' : /\/services\//.test(rel) ? 'service'
    : /\/util\//.test(rel) ? 'util' : /\/core\//.test(rel) ? 'core' : 'other'
  const fields = Object.create(null)
  for (const m of src.matchAll(/^\s{4}(?:(?:private|protected|public|final|static)\s+)+([\w.]+(?:<[^;=]*>)?)\s+(\w+)\s*[;=]/gm)) fields[m[2]] = m[1].replace(/<.*/, '').split('.').pop()
  const methods = Object.create(null)
  const header = /^\s{4}(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|protected|private|static|final|synchronized|default)\s+)*(?:<[^>]+>\s+)?(?:[\w.<>\[\], ?]+\s+)?(\w+)\s*\(/gm
  let m
  while ((m = header.exec(src))) {
    const mname = m[1]
    if (KEYWORDS.has(mname)) continue
    // find the opening brace of the body (skip abstract/interface methods ending with ;)
    let i = header.lastIndex, depth = 1
    for (; i < src.length && depth; i++) { if (src[i] === '(') depth++; if (src[i] === ')') depth-- }
    const rest = src.slice(i, i + 200)
    const brace = rest.search(/[{;]/)
    if (brace < 0 || rest[brace] === ';') continue
    let j = i + brace + 1
    depth = 1
    for (; j < src.length && depth; j++) { if (src[j] === '{') depth++; if (src[j] === '}') depth-- }
    methods[mname] = (methods[mname] || '') + '\n' + src.slice(i + brace, j)
    header.lastIndex = j
  }
  // string constants (SQL fragments kept in a constant, e.g. a shared select list)
  const constants = Object.create(null)
  for (const c of src.matchAll(/static final String (\w+)\s*=\s*("""[\s\S]*?"""|"(?:[^"\\]|\\.)*")/g)) constants[c[1]] = c[2]
  classes[name] = { name, layer, file: rel, fields, methods, constants }
}

// Tables named in SQL: from/join = read; insert into/update/delete from = write.
const tableNames = new Set()
for (const f of fs.readdirSync(path.join(root, 'backend/src/main/resources/db/migration')))
  for (const m of fs.readFileSync(path.join(root, 'backend/src/main/resources/db/migration', f), 'utf8').matchAll(/create table (?:if not exists )?(\w+)/gi)) tableNames.add(m[1])
function sqlAccess(body, cls) {
  const out = {}
  const add = (t, mode) => { if (tableNames.has(t)) out[t] = out[t] && out[t] !== mode ? 'read+write' : mode }
  for (let i = body.indexOf('.sql('); i >= 0; i = body.indexOf('.sql(', i + 1)) {
    let depth = 0, j = i + 4, str = false, tri = false
    for (; j < body.length; j++) {                       // the argument of .sql( up to its closing parenthesis
      if (body.startsWith('"""', j)) { tri = !tri; j += 2; continue }
      if (tri) continue
      if (body[j] === '"' && body[j - 1] !== '\\') str = !str
      if (str) continue
      if (body[j] === '(') depth++
      if (body[j] === ')' && --depth === 0) break
    }
    const text = body.slice(i + 5, j).replace(/\b[A-Z][A-Z0-9_]+\b/g, k => classes[cls]?.constants[k] ?? k)
    for (const t of text.matchAll(/\b(?:from|join)\s+(\w+)/gi)) add(t[1], 'read')
    for (const t of text.matchAll(/\b(?:insert\s+into|update|delete\s+from)\s+(\w+)/gi)) add(t[1], 'write')
  }
  return out
}

/** Calls a method body makes: [class, method] pairs, through fields, static calls, method references and own methods. */
function calls(cls, body) {
  const c = classes[cls], out = []
  for (const m of body.matchAll(/\b(\w+)\s*(?:\.|::)\s*(\w+)\s*\(?/g)) {
    const [, target, method] = m
    const type = c.fields[target] || (classes[target] ? target : null)
    if (type && classes[type] && classes[type].methods[method]) out.push([type, method])
  }
  for (const m of body.matchAll(/(?<![.\w])(\w+)\s*\(/g)) if (c.methods[m[1]] && !KEYWORDS.has(m[1])) out.push([cls, m[1]])
  return out
}

const memo = Object.create(null)
/** Tables reached from cls.method, with the chain of calls that reaches each. */
function trace(cls, method, depth = 0, seen = new Set()) {
  const key = cls + '.' + method
  if (memo[key]) return memo[key]
  if (seen.has(key) || depth > 10) return {}
  seen.add(key)
  const body = classes[cls]?.methods[method]
  if (!body) return {}
  const result = {}
  const merge = (t, mode, chain) => {
    const prev = result[t]
    result[t] = { mode: prev && prev.mode !== mode ? 'read+write' : mode, chain: prev?.chain && prev.chain.length <= chain.length ? prev.chain : chain }
  }
  for (const [t, mode] of Object.entries(sqlAccess(body, cls))) for (const m of mode.split('+')) merge(t, m, [key])
  for (const [tc, tm] of calls(cls, body)) {
    if (tc === cls && tm === method) continue
    for (const [t, v] of Object.entries(trace(tc, tm, depth + 1, seen))) for (const m of v.mode.split('+')) merge(t, m, [key, ...v.chain])
  }
  if (depth === 0) memo[key] = result
  return result
}

// titles and categories from the API reference, so both pages can name and link each endpoint
const api = JSON.parse(fs.readFileSync(path.join(root, 'docs/api/api.json'), 'utf8'))
const info = Object.create(null)
for (const c of api.categories) for (const e of c.endpoints)
  info[e.method + ' ' + e.path] = { title: e.title, category: c.title, id: 'ep-' + (e.method + e.path).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/-$/, '') }

const out = { generatedAt: new Date().toISOString().slice(0, 10), note: 'Static trace of the Java source: controller → services → repositories → SQL. Interceptors, interface strategies and SQL built at run time are not followed.', routes: {}, tables: {} }
for (const r of routes()) {
  const key = r.method + ' ' + r.path
  const tables = trace(r.controller, r.handler)
  out.routes[key] = { ...(info[key] || {}), handler: r.controller + '.' + r.handler, tables: Object.fromEntries(Object.entries(tables).sort().map(([t, v]) => [t, { mode: v.mode,
    chain: v.chain.map(k => { const [c, m] = k.split('.'); return { class: c, method: m, layer: classes[c]?.layer } }) }])) }
  for (const [t, v] of Object.entries(tables)) {
    out.tables[t] ||= { read: [], write: [] }
    for (const m of v.mode.split('+')) out.tables[t][m].push(key)
  }
}
fs.writeFileSync(path.join(root, 'docs/db/data-flow.json'), JSON.stringify(out, null, 2) + '\n')
const traced = Object.values(out.routes).filter(r => Object.keys(r.tables).length).length
console.log(`docs/db/data-flow.json: ${traced} of ${Object.keys(out.routes).length} routes reach the database; ${Object.keys(out.tables).length} of ${tableNames.size} tables used by the API`)
