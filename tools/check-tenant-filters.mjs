// CI guard: every SQL statement on a tenant-owned table names the organization (org_id, tenant_id, owner_org_id or
// partner_org_id) in addition to row-level security, or says why it doesn't with a /* all tenants: reason */ comment.
// Tenant-owned tables = those the migrations put under row-level security, so new tables are covered automatically.
// Usage: node tools/check-tenant-filters.mjs   (exit 1 lists offending statements)
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const migrations = path.join(root, 'backend/src/main/resources/db/migration')
const javaRoot = path.join(root, 'backend/src/main/java/com/faststudio')
// Whole files that are cross-tenant by design.
const allowFiles = new Set([
  'v1/config/demo/DemoSeeder.java',                    // seeds demo tenants as the system
  'v1/repositories/mail/EmailMessageRepository.java',  // platform email outbox; org_id is optional context
])

const sql = fs.readdirSync(migrations).filter(f => f.endsWith('.sql')).map(f => fs.readFileSync(path.join(migrations, f), 'utf8')).join('\n')
const tenant = new Set()
for (const m of sql.matchAll(/alter table ([a-z_]+) enable row level security/g)) tenant.add(m[1])
for (const m of sql.matchAll(/foreach t in array array\[([^\]]+)\]/g)) for (const t of m[1].matchAll(/'([a-z_]+)'/g)) tenant.add(t[1])

const files = []
const walk = d => { for (const f of fs.readdirSync(d)) { const p = path.join(d, f); fs.statSync(p).isDirectory() ? walk(p) : p.endsWith('.java') && files.push(p) } }
walk(javaRoot)

const bad = []
for (const f of files) {
  const rel = path.relative(javaRoot, f).replaceAll('\\', '/')
  if (allowFiles.has(rel)) continue
  const s = fs.readFileSync(f, 'utf8')
  for (let i = s.indexOf('.sql('); i >= 0; i = s.indexOf('.sql(', i + 1)) {
    let depth = 0, j = i + 4, inStr = false, tri = false
    for (; j < s.length; j++) {                 // the argument of .sql( up to its closing parenthesis
      if (s.startsWith('"""', j)) { tri = !tri; j += 2; continue }
      if (tri) continue
      if (s[j] === '"' && s[j - 1] !== '\\') inStr = !inStr
      if (inStr) continue
      if (s[j] === '(') depth++
      if (s[j] === ')' && --depth === 0) break
    }
    const text = s.slice(i + 5, j).replace(/\s+/g, ' ')
    const used = [...tenant].filter(t => new RegExp(`\\b(from|join|update|into) ${t}\\b`, 'i').test(text))
    if (!used.length || /\b(org_id|tenant_id|owner_org_id|partner_org_id)\b/.test(text) || /\/\* all tenants: [^*]+\*\//.test(text)) continue
    bad.push(`${rel}:${s.slice(0, i).split('\n').length} [${used.join(', ')}] ${text.slice(0, 160)}`)
  }
}
if (bad.length) {
  console.error('SQL on tenant tables without an organization filter (add org_id, or /* all tenants: reason */):\n' + bad.join('\n'))
  process.exit(1)
}
console.log(`Tenant filter check: ${tenant.size} tenant tables, ${files.length} Java files, all statements filter by organization.`)
