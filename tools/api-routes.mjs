// Lists every HTTP route of the backend from the controller sources: method, path, query parameters and the request
// body with its fields (name, JSON type, required, rules), read from the Java records the controllers bind.
// Used by tools/build-api-docs.mjs so the API page can never silently miss an endpoint or a field.
// Usage: node tools/api-routes.mjs   (prints the table)
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const javaRoot = path.join(root, 'backend/src/main/java')
const controllers = path.join(javaRoot, 'com/faststudio/v1/controllers')

/** Splits at top-level commas (not inside (), <>, [] or quotes). */
function splitTop(s) {
  const out = []
  let depth = 0, cur = '', str = false
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (c === '"' && s[i - 1] !== '\\') str = !str
    if (!str && '(<['.includes(c)) depth++
    if (!str && ')>]'.includes(c)) depth--
    if (!str && c === ',' && depth === 0) { out.push(cur); cur = '' } else cur += c
  }
  if (cur.trim()) out.push(cur)
  return out.map(x => x.trim())
}

/** The parameter list of `record Name(...)` in a source text, or null. */
function recordParams(src, name) {
  const m = new RegExp(`record ${name}\\s*\\(`).exec(src)
  if (!m) return null
  let depth = 1, i = m.index + m[0].length, start = i
  for (; i < src.length && depth; i++) { if (src[i] === '(') depth++; if (src[i] === ')') depth-- }
  return src.slice(start, i - 1).replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')
}

function jsonType(t) {
  t = t.replace(/@\w+(\([^)]*\))?\s*/g, '').trim()
  const list = t.match(/^(?:List|Set|Collection)<(.+)>$/)
  if (list) return { type: 'array', of: jsonType(list[1]).type, item: list[1] }
  if (/^Map</.test(t)) return { type: 'object' }
  const map = { String: 'string', UUID: 'string (UUID)', int: 'integer', Integer: 'integer', long: 'integer', Long: 'integer', double: 'number', Double: 'number',
    BigDecimal: 'number', boolean: 'boolean', Boolean: 'boolean', Instant: 'string (date-time)', OffsetDateTime: 'string (date-time)', LocalDate: 'string (date)',
    LocalTime: 'string (time)', JsonNode: 'any JSON value', 'byte[]': 'raw bytes' }
  return { type: map[t] || 'object', record: map[t] ? null : t }
}

/** Fields of a record: name, JSON type, required, human-readable rules (from the validation annotations). */
function fields(params, sources, seen = new Set()) {
  return splitTop(params).map(p => {
    const name = p.match(/(\w+)\s*$/)[1]
    const typeText = p.slice(0, p.lastIndexOf(name)).replace(/@\w+(\([^)]*(\([^)]*\)[^)]*)*\))?/g, '').trim()
    const t = jsonType(typeText)
    const ann = s => (p.match(new RegExp(`@${s}(?:\\(([^)]*)\\))?`)) || [])
    const rules = []
    const size = ann('Size')[1], min = ann('Min')[1], max = ann('Max')[1], pattern = ann('Pattern')[1]
    const nested = t.record && !seen.has(t.record) ? resolve(t.record, sources) : null
    if (size) { const lo = (size.match(/min\s*=\s*(\d+)/) || [])[1], hi = (size.match(/max\s*=\s*(\d+)/) || [])[1]
      rules.push(t.type === 'array' ? `${lo ? lo + '–' : 'up to '}${hi} items` : lo && hi ? `${lo}–${hi} characters` : `up to ${hi} characters`) }
    if (min) rules.push('at least ' + min.replace(/value\s*=\s*/, ''))
    if (max) rules.push('at most ' + max.replace(/value\s*=\s*/, ''))
    const dmin = ann('DecimalMin')[1], dmax = ann('DecimalMax')[1]
    if (dmin) rules.push('at least ' + dmin.replace(/"/g, ''))
    if (dmax) rules.push('at most ' + dmax.replace(/"/g, ''))
    if (/@Email\b/.test(p)) rules.push('an email address')
    if (pattern) {
      const re = (pattern.match(/regexp\s*=\s*"((?:[^"\\]|\\.)*)"/) || [])[1]
      if (re && /^[A-Z_|]+$/.test(re)) rules.push('one of: ' + re.split('|').join(', '))
      else if (re) rules.push('format: ' + re.replace(/\\\\/g, '\\'))
    }
    const primitive = /^(int|long|boolean|double)$/.test(typeText)
    const required = /@(NotNull|NotBlank|NotEmpty)\b/.test(p)
    return { name, type: t.type === 'array' ? `array of ${t.of}` : t.type, required, rules,
      default: primitive && !required ? (typeText === 'boolean' ? 'false' : '0') : null,
      fields: nested ? fields(nested, sources, new Set([...seen, t.record])) : (t.type === 'array' && t.item && jsonType(t.item).record ? (() => {
        const inner = resolve(jsonType(t.item).record, sources); return inner ? fields(inner, sources, new Set([...seen, t.item])) : null })() : null) }
  })
}

function resolve(type, sources) {
  const simple = type.split('.').pop()
  for (const s of sources) { const p = recordParams(s, simple); if (p != null) return p }
  return null
}

/** Source texts a controller can see: itself plus the classes it imports from the project. */
function sourcesOf(controllerSrc) {
  const out = [controllerSrc]
  for (const m of controllerSrc.matchAll(/^import (com\.faststudio\.[\w.]+?)(?:\.\*)?;/gm)) {
    const parts = m[1].split('.')
    for (let n = parts.length; n > 2; n--) {
      const file = path.join(javaRoot, ...parts.slice(0, n)) + '.java'
      if (fs.existsSync(file)) { out.push(fs.readFileSync(file, 'utf8')); break }
    }
  }
  return out
}

export function routes() {
  const files = []
  const walk = d => { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); fs.statSync(p).isDirectory() ? walk(p) : p.endsWith('.java') && files.push(p) } }
  walk(controllers)
  const out = []
  for (const f of files) {
    const s = fs.readFileSync(f, 'utf8')
    const sources = sourcesOf(s)
    const base = (s.match(/@RequestMapping\("([^"]*)"\)\s*public class/) || s.match(/@RequestMapping\("([^"]*)"\)/) || [])[1] || ''
    const re = /@(Get|Post|Put|Patch|Delete)Mapping(?:\((?:value\s*=\s*)?(?:"([^"]*)")?[^)]*\))?\s*\n\s*([\s\S]*?)\{/g
    let m
    while ((m = re.exec(s))) {
      const sig = m[3].replace(/\s+/g, ' ')
      const bodyType = (sig.match(/@RequestBody(?:\([^)]*\))? ([\w.<>\[\], ]+?) \w+[,)]/) || [])[1] || null
      const query = [...sig.matchAll(/@RequestParam\(([^)]*)\) [\w.<>]+ (\w+)/g)].map(q => {
        const name = (q[1].match(/(?:value = |name = |^)"(\w+)"/) || [])[1] || q[2]
        const def = (q[1].match(/defaultValue = "([^"]*)"/) || [])[1]
        return { name, required: !/required = false|defaultValue/.test(q[1]), default: def ?? null }
      })
      const params = bodyType && !/^(Map<|byte\[\])/.test(bodyType) ? resolve(bodyType, sources) : null
      out.push({ method: m[1].toUpperCase(), path: (base + (m[2] || '')).replace(/\/$/, '') || '/', controller: path.basename(f, '.java'),
        bodyType, bodyFields: params == null ? null : fields(params, sources), query, multipart: /MultipartFile/.test(sig), handler: (sig.match(/ (\w+)\(/) || [])[1] })
    }
  }
  return out.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method))
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]))
  for (const r of routes()) console.log(`${r.method.padEnd(6)} ${r.path}  ${r.bodyType ? 'body=' + r.bodyType + (r.bodyFields ? '(' + r.bodyFields.map(f => f.name + (f.required ? '*' : '')).join(',') + ')' : ' (no fields)') : ''} ${r.query.map(q => q.name + (q.required ? '' : '?')).join(',')}`)
