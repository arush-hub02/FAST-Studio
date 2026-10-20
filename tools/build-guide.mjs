import path from 'node:path'
// Builds docs/FAST_Studio_Product_Guide.html (self-contained, images embedded) and .pdf from docs/PRODUCT_GUIDE.md
import fs from 'node:fs'
import { marked } from 'marked'
import puppeteer from 'puppeteer-core'

const DOCS = path.resolve(import.meta.dirname, '../docs')
let md = fs.readFileSync(`${DOCS}/PRODUCT_GUIDE.md`, 'utf8')
let html = marked.parse(md, { gfm: true })
// heading anchors (GitHub-style), so the contents links work in the HTML and PDF
html = html.replace(/<h([1-4])>(.*?)<\/h\1>/g, (_, l, t) => {
  const id = t.replace(/<[^>]+>/g, '').replace(/&[a-z#0-9]+;/gi, '').toLowerCase().replace(/[^a-z0-9 -]/g, '').trim().replace(/ /g, '-')
  return `<h${l} id="${id}">${t}</h${l}>`
})
html = html.replace(/src="screenshots\/([^"]+)"/g, (_, f) =>
  `src="data:image/png;base64,${fs.readFileSync(`${DOCS}/screenshots/${f}`).toString('base64')}"`)
// relative doc links -> plain text references (the PDF travels alone)
html = html.replace(/<a href="([A-Z_]+\.md)[^"]*">([^<]+)<\/a>/g, '<b>$2</b> <span class="ref">(docs/$1)</span>')

const page = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>FAST Studio — Product Guide</title>
<style>
  :root { --ink:#0f172a; --muted:#475569; --brand:#4f46e5; --red:#e11d48; }
  body { font: 15px/1.6 'Segoe UI', Inter, system-ui, sans-serif; color: var(--ink); max-width: 1000px; margin: 0 auto; padding: 0 32px 60px; }
  .cover { min-height: 92vh; display: flex; flex-direction: column; justify-content: center; gap: 18px; page-break-after: always; }
  .cover .logo { width: 64px; height: 64px; border-radius: 16px; background: linear-gradient(135deg,#6366f1,#a855f7); }
  .cover h1 { font-size: 46px; margin: 0; border: 0; }
  .cover p { font-size: 18px; color: var(--muted); max-width: 640px; margin: 0; }
  h1 { font-size: 30px; border-bottom: 3px solid var(--brand); padding-bottom: 6px; }
  h2 { font-size: 24px; margin-top: 44px; padding-top: 10px; border-top: 1px solid #e5e8ef; page-break-before: always; color: #1e1b4b; }
  h2:first-of-type { page-break-before: auto; }
  h3 { font-size: 18px; margin-top: 26px; color: #312e81; }
  img { width: 100%; border: 1px solid #d4d9e3; border-radius: 10px; box-shadow: 0 4px 14px rgba(15,23,42,.12); margin: 10px 0 6px; page-break-inside: avoid; }
  p:has(> img) { page-break-inside: avoid; }
  table { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: 14px; page-break-inside: avoid; }
  th, td { border: 1px solid #e5e8ef; padding: 7px 10px; text-align: left; vertical-align: top; }
  th { background: #f1f5ff; }
  blockquote { margin: 16px 0; padding: 12px 18px; background: #f5f3ff; border-left: 5px solid #7c3aed; border-radius: 0 10px 10px 0; color: #2e1065; page-break-inside: avoid; }
  blockquote p { margin: 6px 0; }
  code { font-family: Consolas, 'Cascadia Code', monospace; font-size: 13px; background: #eef2ff; padding: 1px 5px; border-radius: 5px; }
  pre { background: #0b1224; color: #cbd5e1; padding: 14px 16px; border-radius: 10px; overflow-x: auto; font-size: 12.5px; page-break-inside: avoid; }
  pre code { background: none; color: inherit; padding: 0; }
  ol li::marker { color: var(--red); font-weight: 700; }
  .ref { color: var(--muted); font-size: 12px; }
  a { color: var(--brand); }
  @media print { body { padding: 0 } }
</style></head><body>
<section class="cover">
  <div class="logo"></div>
  <h1>FAST Studio — Product Guide</h1>
  <p>Step-by-step walkthrough of every screen, from setup and video upload to live channels with SSAI/SGAI ads and SCTE-35 markers — with what happens behind the scenes, in plain language.</p>
  <p style="font-size:14px">Prototype 0.1 · 20 October 2026 · Screenshots taken from the running product · All companies, people and money are fictional</p>
</section>
${html}
</body></html>`
fs.writeFileSync(`${DOCS}/FAST_Studio_Product_Guide.html`, page)
console.log('html', (page.length / 1e6).toFixed(1), 'MB')

const browser = await puppeteer.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
const p = await browser.newPage()
await p.setContent(page, { waitUntil: 'load', timeout: 120000 })
await p.pdf({
  path: `${DOCS}/FAST_Studio_Product_Guide.pdf`, format: 'A4', printBackground: true,
  margin: { top: '16mm', bottom: '16mm', left: '12mm', right: '12mm' }, displayHeaderFooter: true,
  headerTemplate: '<div style="font-size:8px;color:#94a3b8;width:100%;text-align:right;padding-right:12mm">FAST Studio — Product Guide</div>',
  footerTemplate: '<div style="font-size:8px;color:#94a3b8;width:100%;text-align:center"><span class="pageNumber"></span> / <span class="totalPages"></span></div>',
})
await browser.close()
console.log('pdf', (fs.statSync(`${DOCS}/FAST_Studio_Product_Guide.pdf`).size / 1e6).toFixed(1), 'MB')
