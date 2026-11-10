// Builds docs/html/FAQ.html from docs/faq/faq.json: the user's questions with plain answers, newest first.
// To add a question: add an entry at the top of "questions" (id, asked, question, short, sections[{title, html}],
// optional flow.boxes [[name, what it does], ...] drawn left to right, optional related paths), then run this.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const src = JSON.parse(fs.readFileSync(path.join(root, 'docs/faq/faq.json'), 'utf8'))
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
for (const q of src.questions) for (const k of ['id', 'asked', 'question', 'short', 'sections'])
  if (!q[k]) throw new Error(`FAQ entry ${q.id ?? '?'} is missing "${k}"`)

const link = p => {
  const href = p.startsWith('docs/html/') ? p.slice('docs/html/'.length) : '../../' + p
  return `<a href="${esc(href)}">${esc(p)}</a>`
}
const flow = f => !f ? '' : `<div class="flow" role="img" aria-label="${esc(f.boxes.map(b => b[0]).join(', '))}">${f.boxes.map((b, i) =>
  `${i ? '<span class="arrow" aria-hidden="true">→</span>' : ''}<div class="box"><b>${esc(b[0])}</b><span>${esc(b[1])}</span></div>`).join('')}</div>`

const entry = q => `
<article id="${esc(q.id)}">
  <p class="asked">Asked ${esc(q.asked)}</p>
  <h2><a href="#${esc(q.id)}">${esc(q.question)}</a></h2>
  <p class="short"><b>Short answer.</b> ${esc(q.short)}</p>
  ${flow(q.flow)}
  ${q.sections.map(s => `<h3>${esc(s.title)}</h3>${s.html}`).join('\n')}
  ${q.related?.length ? `<p class="related">Related: ${q.related.map(link).join(' · ')}</p>` : ''}
</article>`

const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FAST Studio FAQ</title>
<style>
:root{--bg:#fff;--fg:#0f172a;--muted:#475569;--line:#e2e8f0;--card:#f8fafc;--brand:#4f46e5;--note:#eef2ff}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){--bg:#0b1020;--fg:#e2e8f0;--muted:#94a3b8;--line:#1e293b;--card:#111827;--brand:#818cf8;--note:#1e1b4b}}
:root[data-theme=dark]{--bg:#0b1020;--fg:#e2e8f0;--muted:#94a3b8;--line:#1e293b;--card:#111827;--brand:#818cf8;--note:#1e1b4b}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.65 "Segoe UI",system-ui,sans-serif}
main{max-width:980px;margin:0 auto;padding:32px 16px 80px}h1{font-size:32px;margin:0 0 6px}.intro{color:var(--muted);margin:0 0 20px}
nav.toc{border:1px solid var(--line);background:var(--card);border-radius:10px;padding:10px 16px;margin-bottom:10px}nav.toc ol{margin:6px 0;padding-left:20px}
article{border-top:3px solid var(--brand);margin-top:34px;padding-top:6px}h2{font-size:22px;margin:4px 0 10px}h2 a{color:inherit;text-decoration:none}
h3{font-size:17px;margin:22px 0 6px}.asked{color:var(--muted);font-size:13px;margin:0}a{color:var(--brand)}
.short{background:var(--note);border:1px solid var(--line);border-radius:10px;padding:12px 16px}
code{font-family:Consolas,monospace;font-size:13px;background:var(--card);border:1px solid var(--line);padding:1px 5px;border-radius:5px}
table{border-collapse:collapse;width:100%;font-size:14px}th,td{border:1px solid var(--line);padding:8px 10px;text-align:left;vertical-align:top}th{background:var(--card)}
.flow{display:flex;align-items:stretch;gap:6px;flex-wrap:wrap;margin:16px 0}.flow .box{flex:1 1 150px;border:1px solid var(--line);background:var(--card);border-radius:10px;padding:10px 12px;display:flex;flex-direction:column;gap:4px;font-size:13px}
.flow .box b{font-size:14px}.flow .arrow{align-self:center;color:var(--brand);font-size:20px}
.related{color:var(--muted);font-size:13px}
@media (max-width:640px){.flow{flex-direction:column}.flow .arrow{transform:rotate(90deg)}table{display:block;overflow-x:auto}}
</style></head><body><main>
<h1>${esc(src.title)}</h1><p class="intro">${esc(src.intro)}</p>
<nav class="toc"><b>Questions</b><ol>${src.questions.map(q => `<li><a href="#${esc(q.id)}">${esc(q.question)}</a></li>`).join('')}</ol></nav>
${src.questions.map(entry).join('\n')}
</main></body></html>`
fs.writeFileSync(path.join(root, 'docs/html/FAQ.html'), html)
console.log(`docs/html/FAQ.html: ${src.questions.length} question(s)`)
