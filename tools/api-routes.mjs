// Lists every HTTP route of the backend from the controller sources: method, path, request body type, query
// parameters. Used by tools/build-api-docs.mjs so the API page can never silently miss an endpoint.
// Usage: node tools/api-routes.mjs   (prints the table)
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const controllers = path.join(root, 'backend/src/main/java/com/faststudio/v1/controllers')

export function routes() {
  const files = []
  const walk = d => { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); fs.statSync(p).isDirectory() ? walk(p) : p.endsWith('.java') && files.push(p) } }
  walk(controllers)
  const out = []
  for (const f of files) {
    const s = fs.readFileSync(f, 'utf8')
    const base = (s.match(/@RequestMapping\("([^"]*)"\)\s*public class/) || s.match(/@RequestMapping\("([^"]*)"\)/) || [])[1] || ''
    const re = /@(Get|Post|Put|Patch|Delete)Mapping(?:\((?:value\s*=\s*)?(?:"([^"]*)")?[^)]*\))?\s*\n\s*([\s\S]*?)\{/g
    let m
    while ((m = re.exec(s))) {
      const sig = m[3].replace(/\s+/g, ' ')
      const body = (sig.match(/@RequestBody (?:byte\[\]|[\w.<>, ]+?) (\w+)[,)]/) || [])[0]
      const bodyType = (sig.match(/@RequestBody ([\w.<>\[\], ]+?) \w+[,)]/) || [])[1] || null
      const query = [...sig.matchAll(/@RequestParam\(([^)]*)\) [\w.<>]+ (\w+)/g)].map(q => {
        const name = (q[1].match(/(?:value = |name = |^)"(\w+)"/) || [])[1] || q[2]
        return { name, required: !/required = false|defaultValue/.test(q[1]) }
      })
      const multipart = /MultipartFile/.test(sig)
      out.push({ method: m[1].toUpperCase(), path: (base + (m[2] || '')).replace(/\/$/, '') || '/', controller: path.basename(f, '.java'),
        bodyType, query, multipart, handler: (sig.match(/ (\w+)\(/) || [])[1] })
    }
  }
  return out.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method))
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]))
  for (const r of routes()) console.log(`${r.method.padEnd(6)} ${r.path}  ${r.bodyType ? 'body=' + r.bodyType : ''} ${r.query.map(q => q.name + (q.required ? '' : '?')).join(',')} ${r.multipart ? 'multipart' : ''}  [${r.controller}]`)
