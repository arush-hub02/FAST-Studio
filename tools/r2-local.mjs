// Lists what the local R2 stand-in holds (Wrangler keeps it in a SQLite index plus blobs, not as plain files).
//   node tools/r2-local.mjs                 folders (one per video, ad or slate): files, size, link to open
//   node tools/r2-local.mjs <prefix>        every file under a prefix, e.g. arush-media/ or _platform/standby-5s-q6/
//   node tools/r2-local.mjs --demo ...      the demo's R2 (data/edge) instead of production's (data-pilot/edge)
//   node tools/r2-local.mjs --usage         also says which folders a video, ad or slate uses (needs that environment's
//                                           FAST Studio running, for its database, and Java, as the backend build does)
// Read-only: safe while FAST Studio runs. Files open through the local edge: http://127.0.0.1:8787/media/<key>
import { DatabaseSync } from 'node:sqlite'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { execFileSync } from 'node:child_process'

const args = process.argv.slice(2)
const demo = args.includes('--demo')
const prefix = args.find(a => !a.startsWith('--')) ?? ''
const root = path.resolve(import.meta.dirname, '..', demo ? 'data/edge' : 'data-pilot/edge', 'v3/r2/miniflare-R2BucketObject')
const edge = 'http://127.0.0.1:8787/media/'
if (!fs.existsSync(root)) { console.error(`No local R2 at ${root}. Has FAST Studio run with its edge${demo ? '' : ' (pilot)'}?`); process.exit(1) }
const file = fs.readdirSync(root).find(f => f.endsWith('.sqlite'))
const db = new DatabaseSync(path.join(root, file), { readOnly: true })
const rows = db.prepare('select key, size, uploaded from _mf_objects where key like ? order by key').all(prefix.replace(/[%_]/g, '\\$&') + '%')
const mb = b => (b / 1048576).toFixed(1) + ' MB'

/** Media folders in use, from the environment's database: videos, ads and slates not archived, plus the current platform slates. */
function inUse() {
  const repo = path.join(os.homedir(), '.m2/repository/org/postgresql/postgresql')
  const jar = fs.existsSync(repo) && fs.readdirSync(repo).sort().map(v => path.join(repo, v, `postgresql-${v}.jar`)).filter(f => fs.existsSync(f)).pop()
  if (!jar) return null
  const src = path.join(os.tmpdir(), 'R2Usage.java')
  fs.writeFileSync(src, [
    'import java.sql.*;',
    'public class R2Usage { public static void main(String[] a) throws Exception {',
    `  try (Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${demo ? 54329 : 54339}/postgres", "postgres", "")) {`,
    '    c.setReadOnly(true);',
    '    ResultSet r = c.createStatement().executeQuery("select media_key, kind, title from asset where status <> \'ARCHIVED\' and media_key is not null");',
    '    while (r.next()) System.out.println(r.getString(1) + "|" + r.getString(2) + "|" + r.getString(3)); } } }'].join('\n'))
  try {
    const out = execFileSync('java', ['-cp', jar, src], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 60000 })
    const m = new Map()
    for (const l of out.split('\n').filter(Boolean)) { const [k, kind, ...t] = l.trim().split('|'); m.set(k, `${kind.toLowerCase()} "${t.join('|')}"`) }
    for (const s of ['standby', 'geo']) m.set(`_platform/${s}-5s-q6`, 'platform slate')   // MediaServiceV1.STANDBY and GEO
    return m
  } catch { return null }
}
const usage = args.includes('--usage') ? inUse() : undefined
if (usage === null) console.log('Usage unknown: start FAST Studio in this mode (its database runs with it) and have Java installed.')

if (prefix) {
  for (const r of rows) console.log(`${mb(r.size).padStart(9)}  ${new Date(r.uploaded).toISOString().slice(0, 16)}  ${edge}${r.key}`)
  console.log(`${rows.length} files, ${mb(rows.reduce((s, r) => s + r.size, 0))}`)
} else {
  // a folder = project/video.revision (or _platform/slate); its master playlist is the link to give a player
  const folders = new Map()
  for (const r of rows) {
    const k = r.key.split('/').slice(0, 2).join('/')
    const f = folders.get(k) ?? { files: 0, size: 0, uploaded: 0, master: false }
    f.files++; f.size += r.size; f.uploaded = Math.max(f.uploaded, r.uploaded); f.master ||= r.key === k + '/master.m3u8'
    folders.set(k, f)
  }
  for (const [k, f] of folders) {
    const use = usage ? (usage.get(k) ? `  IN USE: ${usage.get(k)}` : '  unused, safe to delete') : ''
    console.log(`${String(f.files).padStart(5)} files ${mb(f.size).padStart(9)}  ${new Date(f.uploaded).toISOString().slice(0, 10)}  ${k}${use}${f.master ? `\n${' '.repeat(29)}${edge}${k}/master.m3u8` : ''}`)
  }
  console.log(`${folders.size} folders, ${rows.length} files, ${mb(rows.reduce((s, r) => s + r.size, 0))}`)
}
