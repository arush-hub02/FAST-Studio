// Rebuilds every generated HTML page in docs/html/ in the right order:
//   1. docs/db/data-flow.json   (node tools/data-flow.mjs: API → service → repository → table, traced from the code)
//   2. docs/html/API.html       (node tools/build-api-docs.mjs; fails on undocumented routes, fields or parameters)
//   3. docs/html/FEATURES.html  (node tools/build-feature-docs.mjs)
//   4. docs/html/visualizeDB.html (embedded PostgreSQL via the backend test; needs Java 21)
// Usage: node tools/build-docs.mjs [--no-db]    (--no-db skips step 4, e.g. when no migration or SQL changed)
// Not included: response examples (node tools/check-api-docs.mjs --save, needs the demo running), screenshots
// (cd frontend && npm run docs:shots, needs the demo) and the product guide (node tools/build-guide.mjs).
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const run = (cmd, args, cwd = root) => {
  console.log(`\n> ${cmd} ${args.join(' ')}`)
  const r = spawnSync(cmd, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' && /mvnw/.test(cmd) })   // .cmd files need a shell on Windows
  if (r.status !== 0) { console.error(`Failed: ${cmd} ${args.join(' ')}`); process.exit(r.status || 1) }
}
run(process.execPath, ['tools/data-flow.mjs'])
run(process.execPath, ['tools/build-api-docs.mjs'])
run(process.execPath, ['tools/build-feature-docs.mjs'])
if (!process.argv.includes('--no-db'))
  run(process.platform === 'win32' ? `"${path.join(root, 'backend', 'mvnw.cmd')}"` : './mvnw', ['-q', '-B', 'test', '-Dtest=SchemaCatalogExportTest', '-Ddbdocs=true', '-Dsurefire.failIfNoSpecifiedTests=false'], path.join(root, 'backend'))
console.log('\nAll pages in docs/html/ are up to date. Open docs/html/index.html.')
