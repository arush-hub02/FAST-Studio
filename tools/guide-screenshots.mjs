// Regenerates docs/screenshots/*.png (annotated) by driving the running app in headless Chrome.
// Setup (once, outside the repo is fine): npm i puppeteer-core@24 marked@15 ; then from this folder:
//   node guide-screenshots.mjs            -> all sections (needs a FRESH demo: delete data/ and restart first)
//   node guide-screenshots.mjs 7,8         -> only sections whose names start with 7 or 8
//   node guide-screenshots.mjs 12-pilot    -> pilot-mode screens: needs a running PILOT (start.bat -Pilot) and the
//                                             admin's current FASTSTUDIO_ADMIN_EMAIL / FASTSTUDIO_ADMIN_PASSWORD in env
//                                             (creates a test company, so use a throw-away data-pilot folder)
// Then: node build-guide.mjs  -> docs/FAST_Studio_Product_Guide.html + .pdf

// Drives FAST Studio in a real browser and saves annotated product-guide screenshots.
import puppeteer from 'puppeteer-core'
import path from 'node:path'
import fs from 'node:fs'

const BASE = 'http://localhost:8080'
const OUT = path.resolve(import.meta.dirname, '../docs/screenshots')
const UPLOAD = path.resolve(import.meta.dirname, 'my-travel-vlog.mp4')   // any short video; make one: ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=25 -f lavfi -i sine -t 60 -pix_fmt yuv420p my-travel-vlog.mp4
const ONLY = process.argv[2]?.split(',')   // optional: comma-separated section prefixes to run
fs.mkdirSync(OUT, { recursive: true })
const sleep = ms => new Promise(r => setTimeout(r, ms))
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a)

const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless: true, defaultViewport: { width: 1440, height: 900 },
  args: ['--autoplay-policy=no-user-gesture-required', '--mute-audio'],
})
const page = await browser.newPage()
page.on('pageerror', e => log('PAGE ERROR', e.message))

// ------------------------------------------------------------------ annotation layer

/** Draws numbered highlight boxes, arrows and labels over the live page. notes: [target, label]; target is visible text or "css:selector". */
async function annotate(notes) {
  const missing = await page.evaluate(notes => {
    // visible = actually on top at its own centre (so lines hidden inside scroll boxes are skipped)
    const shown = e => {
      const r = e.getBoundingClientRect()
      if (r.width <= 0 || r.height <= 0) return false
      if (window.__full) return true
      const x = Math.min(Math.max(r.left + Math.min(r.width / 2, 40), 1), innerWidth - 1), y = r.top + r.height / 2
      if (y < 0 || y > innerHeight) return false
      const top = document.elementFromPoint(x, y)
      return !!top && (top === e || e.contains(top))
    }
    const pick = t => {
      if (t.startsWith('css:')) return [...document.querySelectorAll(t.slice(4))].find(shown)
      const cands = [...document.querySelectorAll('button, a, h1, h2, h3, b, .badge, .chip, label, .callout, .tab, select, input, th, td, span, p, div, dt')]
        .filter(e => shown(e) && e.textContent.replace(/\s+/g, ' ').includes(t))
      cands.sort((a, b) => { const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect(); return ra.width * ra.height - rb.width * rb.height })
      return cands[0]
    }
    const W = document.documentElement.scrollWidth, H = document.documentElement.scrollHeight, sx = scrollX, sy = scrollY
    const vh = document.documentElement.scrollHeight > innerHeight + 5 && window.__full ? H : innerHeight
    const root = document.createElement('div'); root.id = 'fs-notes'
    root.style.cssText = `position:absolute;left:0;top:0;width:${W}px;height:${H}px;pointer-events:none;z-index:99999;font-family:Inter,'Segoe UI',sans-serif`
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('width', W); svg.setAttribute('height', H); svg.style.cssText = 'position:absolute;left:0;top:0;overflow:visible'
    svg.innerHTML = '<defs><marker id="fsah" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#e11d48"/></marker></defs>'
    root.appendChild(svg); document.body.appendChild(root)
    const missing = [], targets = []
    notes.forEach(([t, label], i) => { const el = pick(t); if (!el) missing.push(t); else targets.push({ el, label, n: targets.length + 1 }) })
    const rects = targets.map(t => t.el.getBoundingClientRect())
    const boxes = rects.map(r => ({ l: r.left + sx - 5, t: r.top + sy - 5, w: r.width + 10, h: r.height + 10 }))
    const hit = (a, b) => a.l < b.l + b.w && b.l < a.l + a.w && a.t < b.t + b.h && b.t < a.t + a.h
    const labels = []
    targets.forEach((tg, i) => {
      const box = boxes[i], r = rects[i]
      const hl = document.createElement("div")
      hl.style.cssText = `position:absolute;border:3px solid #e11d48;border-radius:10px;box-shadow:0 0 0 4px rgba(225,29,72,.18);left:${box.l}px;top:${box.t}px;width:${box.w}px;height:${box.h}px`
      root.appendChild(hl)
      const lab = document.createElement("div")
      lab.style.cssText = "position:absolute;left:0;top:0;max-width:240px;width:max-content;background:#e11d48;color:#fff;border-radius:10px;padding:7px 12px 7px 40px;font-size:13px;font-weight:600;line-height:1.3;box-shadow:0 6px 18px rgba(0,0,0,.28);box-sizing:border-box"
      lab.innerHTML = `<span style="position:absolute;left:8px;top:50%;transform:translateY(-50%);width:24px;height:24px;border-radius:50%;background:#fff;color:#e11d48;display:grid;place-items:center;font-weight:800">${tg.n}</span>${tg.label}`
      root.appendChild(lab)
      const LW = lab.offsetWidth, LH = lab.offsetHeight, G = 46
      const vx0 = sx + 6, vx1 = sx + innerWidth - 6, vy0 = sy + 6, vy1 = sy + vh - 6
      const cx = box.l + box.w / 2, cy = box.t + box.h / 2
      const cands = []
      for (const dy of [0, -LH - 14, LH + 14, -2 * LH - 28, 2 * LH + 28]) {
        cands.push({ l: box.l + box.w + G, t: cy - LH / 2 + dy }, { l: box.l - G - LW, t: cy - LH / 2 + dy })
      }
      for (const dx of [0, -LW / 2 - 20, LW / 2 + 20]) cands.push({ l: cx - LW / 2 + dx, t: box.t + box.h + G - 10 }, { l: cx - LW / 2 + dx, t: box.t - G - LH + 10 })
      const ok = c => { const b = { ...c, w: LW, h: LH }; return c.l >= vx0 && c.l + LW <= vx1 && c.t >= vy0 && c.t + LH <= vy1 && !labels.some(o => hit(b, o)) && !boxes.some(o => hit(b, o)) }
      const okSoft = c => { const b = { ...c, w: LW, h: LH }; return c.l >= vx0 && c.l + LW <= vx1 && c.t >= vy0 && c.t + LH <= vy1 && !labels.some(o => hit(b, o)) && !hit(b, box) }
      const p = cands.find(ok) ?? cands.find(okSoft) ?? { l: Math.min(Math.max(cands[0].l, vx0), vx1 - LW), t: Math.min(Math.max(cands[0].t, vy0), vy1 - LH) }
      lab.style.left = p.l + "px"; lab.style.top = p.t + "px"
      labels.push({ l: p.l, t: p.t, w: LW, h: LH })
      const lcx = p.l + LW / 2, lcy = p.t + LH / 2
      const fx = Math.min(Math.max(cx, p.l), p.l + LW), fy = Math.min(Math.max(cy, p.t), p.t + LH)
      const tx = Math.min(Math.max(lcx, box.l), box.l + box.w), ty = Math.min(Math.max(lcy, box.t), box.t + box.h)
      const line = document.createElementNS("http://www.w3.org/2000/svg", "line")
      Object.entries({ x1: fx, y1: fy, x2: tx, y2: ty, stroke: "#e11d48", "stroke-width": 3, "marker-end": "url(#fsah)" }).forEach(([k, v]) => line.setAttribute(k, v))
      svg.appendChild(line)
    })
    return missing
  }, notes)
  if (missing.length) log('   ! not found:', missing.join(' | '))
}
async function shot(name, notes = [], opts = {}) {
  await sleep(opts.wait ?? 900)
  await page.evaluate(f => { window.__full = f }, !!opts.full)
  const unroll = opts.full ? await page.addStyleTag({ content: 'html,body,#root,.shell{height:auto!important} .main,.content{overflow:visible!important} .nav{min-height:100vh}' }) : null
  if (unroll) await sleep(400)
  if (notes.length) await annotate(notes)
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: !!opts.full })
  await page.evaluate(() => document.getElementById('fs-notes')?.remove())
  if (unroll) await unroll.evaluate(e => e.remove())
  log('shot', name)
}

// ------------------------------------------------------------------ interaction helpers

async function go(p) { await page.goto(BASE + p, { waitUntil: 'networkidle2' }); await sleep(800) }
async function top() { await page.evaluate(() => { scrollTo(0, 0); document.querySelector('.content')?.scrollTo(0, 0) }); await sleep(300) }
async function scrollTo(text) {
  await page.evaluate(t => {
    const el = [...document.querySelectorAll('section, h2, h3')].find(e => e.textContent.includes(t))
    el?.scrollIntoView({ block: 'start' }); document.querySelector('.content')?.scrollBy(0, -16)
  }, text); await sleep(600)
}
async function click(text, sel = 'button, a') {
  await page.evaluate((t, s) => {
    const el = [...document.querySelectorAll(s)].find(e => e.textContent.replace(/\s+/g, ' ').trim().includes(t) && !e.disabled)
    if (!el) throw new Error('not found: ' + t)
    el.scrollIntoView({ block: 'center' }); el.click()
  }, text, sel)
  await sleep(600)
}
async function field(labelText, tag) {
  const id = 'q' + Math.random().toString(36).slice(2)
  await page.evaluate((t, tg, id) => {
    const scope = document.querySelector('.modal, .drawer') ?? document
    const el = [...scope.querySelectorAll('label, .field')].filter(l => l.textContent.includes(t)).map(l => l.querySelector(tg)).find(Boolean)
    if (!el) throw new Error('no field ' + t)
    el.setAttribute('data-q', id)
  }, labelText, tag, id)
  return `[data-q="${id}"]`
}
async function fill(labelText, value) {
  const s = await field(labelText, 'input:not([type=file]), textarea')
  await page.click(s, { clickCount: 3 }); await page.type(s, value)
}
async function choose(labelText, optionText) {
  const s = await field(labelText, 'select')
  const v = await page.$eval(s, (el, t) => [...el.options].find(o => o.textContent.includes(t))?.value, optionText)
  if (v === undefined) throw new Error('no option ' + optionText)
  await page.select(s, v); await sleep(500)
}
async function login(email, password = 'demo1234') {
  await go('/login')
  await page.evaluate(async (e, pw) => {
    const tok = () => decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) ?? '=').split('=')[1])
    await fetch('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': tok() } }).catch(() => {})
    await fetch('/api/auth/csrf')
    const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'X-XSRF-TOKEN': tok(), 'Content-Type': 'application/json' }, body: JSON.stringify({ email: e, password: pw }) })
    if (!r.ok) throw new Error('login failed')
  }, email, password)
}
const api = p => page.evaluate(async p => (await fetch(p)).json(), p)
async function logout() {
  await page.evaluate(async () => {
    const tok = decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) ?? '=').split('=')[1])
    await fetch('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': tok } }).catch(() => {})
  })
}
const post = (p, body) => page.evaluate(async (p, body) => {
  const tok = decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) ?? '=').split('=')[1])
  const r = await fetch(p, { method: 'POST', headers: { 'X-XSRF-TOKEN': tok, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) })
  return { status: r.status, json: await r.json().catch(() => null) }
}, p, body)

// ------------------------------------------------------------------ the tour
const sections = {}
const section = (name, fn) => { sections[name] = fn }

section('1-start', async () => {
  await go('/login'); await logout(); await go('/login')
  await shot('01-login', [['Demo accounts', 'One-click demo sign-in (development only)'], ['css:.login-form form button.primary', 'Normal email + password sign-in'], ['Forgot your password?', 'Anyone can get a reset link by email']])
  await login('owner@aurora.test')
  await go('/aurora')
  await shot('02-home', [
    ['The path from a video file', 'Your step-by-step path to a live channel'],
    ['css:.grid.g4', 'Key numbers at a glance'],
    ['On air now', "Live channels and what's playing"],
    ['css:select[aria-label=Organization]', 'Switch company (tenant)'],
    ['New to FAST? Start here', 'Plain-language guide'],
  ], { full: true })
})

section('2-learn', async () => {
  await go('/aurora/learn')
  await shot('03-learn-overview', [['How FAST works', 'Built-in course for non-experts'], ['No — scheduled channel', 'What makes FAST different']])
  await scrollTo('3 · How ads get into'); await click('SSAI', '.seg button')
  await shot('04-learn-ssai', [['css:.seg', 'Switch CSAI / SSAI / SGAI'], ['What the viewer', 'What the player actually receives'], ['Who inserts the ad', 'Who does the work, and how']])
  await click('SGAI', '.seg button')
  await shot('05-learn-sgai', [['css:.seg', 'Now showing SGAI'], ['interstitial → player fetches ads', 'A pointer, not stitched video']])
  await scrollTo('SCTE-35 inspector'); await click('Use a freshly generated example'); await sleep(1200)
  await shot('06-learn-scte35', [['Use a freshly generated example', 'Generate or paste a marker'], ['segmentation_type_id', 'Break type: provider placement opportunity'], ['Checksum valid', 'CRC check passed']])
})

let vlog
section('3-library', async () => {
  await go('/aurora/library')
  await shot('07-library', [['Add video', 'Add a programme or ad'], ['css:.seg', 'Filter by type'], ['css:input[aria-label="Search videos"]', 'Search'], ['css:.thumb .overlay-badge', 'Processing status'], ['Incomplete licence', 'Licence status at a glance']])
  await click('Add video'); await fill('Title', 'Monsoon Diaries'); await fill('Genre', 'Travel')
  await fill('Description', 'Rain, rivers and roadside chai across the Western Ghats.')
  await shot('08-add-video-generate', [['Generate a sample', 'No file? Generate a test video'], ['Length (minutes)', 'How long'], ['Generate video', 'Server renders it with ffmpeg']])
  await click('Generate video', '.modal-foot button'); await sleep(2000)
  await page.keyboard.press('Escape'); await sleep(500)
  await click('Add video')
  await click('Upload a file', '.seg button')
  await fill('Title', 'My Travel Vlog'); await choose('Type', 'Episode'); await fill('Genre', 'Travel')
  await fill('Description', 'A 60-second clip uploaded from a laptop to show the real upload pipeline.')
  const fi = await page.$('input[type=file]'); await fi.uploadFile(UPLOAD); await sleep(400)
  await shot('09-upload-form', [['Upload a file', 'Upload tab'], ['css:input[type=file]', 'Your video file (MP4, MOV…)'], ['css:.modal-foot .btn.primary', 'Upload and convert']])
  await click('Upload', '.modal-foot button'); await sleep(1800)
  await shot('10-upload-processing', [['css:.drawer .thumb', 'Being converted right now'], ['css:.drawer .badge', 'Status: Processing']])
  for (let i = 0; i < 90; i++) {
    const a = (await api('/api/orgs/aurora/assets?q=My%20Travel%20Vlog'))[0]
    if (a?.asset.status === 'READY') break
    await sleep(2000)
  }
  vlog = (await api('/api/orgs/aurora/assets?q=My%20Travel%20Vlog'))[0].asset
  await go('/aurora/library?asset=' + vlog.id); await sleep(2500)
  await page.evaluate(async () => { const v = document.querySelector('.drawer video'); v.muted = true; await v.play().catch(() => {}); })
  await sleep(2500)
  await page.evaluate(() => { const v = document.querySelector('.drawer video'); v.pause(); v.currentTime = 30 }); await sleep(1500)
  await click('Add at current time'); await sleep(1500)
  await shot('11-asset-ready', [['css:.drawer .player', 'Preview of the converted stream'], ['Processed:', 'What the server produced'], ['css:.drawer .chip', 'Ad-break point at 0:30'], ['Licence', 'Licence summary']])
  await go('/aurora/library')
  for (let i = 0; i < 60; i++) { const a = (await api('/api/orgs/aurora/assets?q=Monsoon'))[0]; if (a?.asset.status === 'READY') break; await sleep(2000) }
  await go('/aurora/library')
  await shot('12-library-after-upload', [['My Travel Vlog', 'Your upload — ready to schedule'], ['Monsoon Diaries', 'Generated sample — also ready']])
})

section('4-rights', async () => {
  vlog ??= (await api('/api/orgs/aurora/assets?q=My%20Travel%20Vlog'))[0].asset
  await go('/aurora/rights')
  await shot('13-rights', [['How decisions are made', 'The rules in one line'], ['Incomplete licence', 'Problems are highlighted'], ['Expired', 'Expired licence'], ['Can it play?', 'Test any viewer']])
  await choose('Programme', 'Steel Horizon'); await choose("Viewer's country", 'United States'); await click('Check now'); await sleep(1200)
  await shot('14-rights-check', [['Check now', 'Pick video, country, channel'], ['Blocked — Steel Horizon', 'Decision for this viewer'], ['US is not in the licensed list', 'The exact rule that failed']])
  await go('/aurora/rights?asset=' + vlog.id); await sleep(800)
  for (const c of ['India', 'United Kingdom', 'United States'])
    await page.evaluate(t => [...document.querySelector('.modal .field').querySelectorAll('button')].find(b => b.textContent.includes(t))?.click(), c)
  await fill('Notes', 'Own content — we hold all rights.')
  await shot('15-rights-editor', [['May play in', 'Countries where it may play'], ['Never in', 'Countries always blocked'], ['Ads may run during this programme', 'Ads allowed?'], ['Save licence', 'Save']])
  await click('Save licence'); await sleep(1000)
})

section('5-channels', async () => {
  await go('/aurora/channels')
  await shot('16-channels', [['New channel', 'Create a channel'], ['On air', 'Live status'], ['Before going live', "What's missing"], ['Ad rules', 'Ad limits for this channel']], { full: true })
  await click('New channel'); await fill('Channel name', 'Weekend Escapes'); await fill('Genre', 'Travel')
  await fill('Short description', 'Slow travel for lazy weekends.')
  await shot('17-wizard-1-name', [['css:.modal .progress', 'Step 1 of 4'], ['Channel name', 'Name it'], ['preview of your channel badge', 'Live preview of the badge']])
  await click('Continue')
  await page.evaluate(() => [...document.querySelectorAll('.modal button')].find(b => b.textContent.includes('United States'))?.click())
  await shot('18-wizard-2-countries', [['Pick the countries', 'Why countries matter'], ['css:.modal button[aria-pressed=true]', 'Selected countries']])
  await click('Continue')
  for (const t of ['My Travel Vlog', 'Coastlines of Kerala', 'Desert Light'])
    await page.evaluate(t => [...document.querySelectorAll('.modal button.card')].find(b => b.textContent.includes(t))?.click(), t)
  await choose('Fill how many hours?', '6 hours')
  await shot('19-wizard-3-programmes', [['css:.modal button.card[aria-pressed=true]', 'Tick the videos to use'], ['Fill how many hours?', 'How much to schedule'], ['Max ads per hour', 'Ad limit']])
  await click('Continue')
  await shot('20-wizard-4-review', [['Offered in', 'Summary'], ["isn't on air until you", "Nothing goes live yet"]])
  await click('Create channel & open schedule'); await sleep(2500)
  await shot('21-schedule', [['Auto-fill', 'Fill hours automatically'], ['css:.timeline', 'Timeline: programmes, ad breaks, NOW line'], ['Check before publishing', 'Problems in plain words'], ['css:.seg button.active', 'Draft vs On air'], ['Version history', 'Every published version']])
  await scrollTo('Running order')
  await shot('22-running-order', [['Running order', 'Same schedule as a list'], ['Ad break', 'Ad break (ours / platform\'s)']])
  await top()
  await click('Add programme'); await choose('Video', 'Mountain Markets')
  await shot('23-add-programme', [['Video', 'Pick a video'], ['At the end of the schedule', 'Where to put it'], ['Insert ad breaks', 'Add its mid-roll breaks']])
  await click('Add', '.modal-foot button'); await sleep(1800); await top()
  await shot('24-schedule-must-fix', [['Must fix:', 'Licence problem detected'], ['css:.tl-block.err', 'Flagged on the timeline'], ['to fix)', 'Publishing is blocked until fixed']])
  await page.evaluate(() => document.querySelector('button.callout.bad')?.click()); await sleep(800)
  await shot('25-schedule-selected', [['Remove programme', 'Remove it in one click'], ['css:.tl-block.sel', 'Selected item']])
  await click('Remove programme'); await sleep(1500); await top()
  await click('Auto-fill')
  await shot('26-autofill', [['Videos to use', 'Which videos to rotate'], ['How many hours?', 'How far ahead'], ['skipped', 'Licences are respected']])
  await page.keyboard.press('Escape'); await sleep(400)
  await page.evaluate(() => [...document.querySelectorAll('button.btn.primary')].find(b => b.textContent.trim().startsWith('Publish'))?.click()); await sleep(700)
  await shot('27-publish', [['All checks passed', 'Checks passed'], ['What changed?', 'Optional note'], ['Publish to viewers', 'Make it official']])
  await click('Publish to viewers'); await sleep(1800); await top()
  await shot('28-published', [['On air now', 'Version 1 is live'], ["Draft matches what's on air", 'Nothing unpublished']])
  await go('/aurora/channels')
  await page.evaluate(() => { const c = [...document.querySelectorAll('section.card')].find(c => c.textContent.includes('Weekend Escapes')); [...c.querySelectorAll('button')].find(b => b.textContent.includes('Go live')).click() })
  await sleep(1500)
  await page.evaluate(() => [...document.querySelectorAll('section.card')].find(c => c.textContent.includes('Weekend Escapes')).scrollIntoView({ block: 'center' })); await sleep(500)
  await shot('29-channel-live', [['css:.badge.live', 'Now on air'], ['Watch', 'Watch it live']])
})

section('6-guide', async () => {
  await go('/aurora/guide')
  await shot('30-tv-guide', [['css:.epg-prog.now', 'On now (highlighted)'], ['XMLTV', 'Download partner feed'], ['Later →', 'Move through time'], ['css:.tl-now', 'Current time']])
  await page.evaluate(() => (document.querySelector('.epg-prog.now') ?? document.querySelector('.epg-prog'))?.click())
  await shot('31-guide-programme', [['Running time', 'Programme details from the schedule']])
})

async function watch(slug, country, dest) {
  await go('/aurora/watch?channel=' + slug); await sleep(1500)
  await choose('Viewer is in', country); await choose('Watching on', dest); await sleep(7000)
}
const breakAt = async (slug, lead) => {
  const ch = (await api('/api/orgs/aurora/channels')).find(c => c.channel.slug === slug).channel
  const v = await api(`/api/orgs/aurora/channels/${ch.id}/schedule?hours=3`)
  return v.publishedEntries.filter(e => e.kind === 'AD_BREAK').map(e => new Date(e.startAt).getTime()).find(t => t > Date.now() + lead)
}
/** waits for the next ad break on the channel, opens the player ~30 s before, returns break start */
async function duringBreak(slug, country, dest, afterMs) {
  const t = await breakAt(slug, 40000)
  log(dest, 'next break in', Math.round((t - Date.now()) / 1000), 's')
  await sleep(Math.max(0, t - Date.now() - 30000))
  await watch(slug, country, dest)
  await sleep(Math.max(0, t + afterMs - Date.now()))
}
const PLAYLIST = async () => {
  await click('Under the hood', '.tab'); await sleep(3500)
  // bring the marker / interstitial line near the top of the playlist box
  await page.evaluate(() => {
    const box = document.querySelector('.m3u8'); box?.scrollIntoView({ block: 'center' })
    box?.querySelectorAll('div').forEach(d => { if (d.textContent.includes('standby/seg')) d.classList.add('fs-slate') }); const l = box?.querySelector('.t-scte, .t-inter'); if (l) box.scrollTop = Math.max(0, l.offsetTop - box.offsetTop - 70)
  })
  await sleep(500)
}
const WE = 'weekend-escapes'

section('7a-geo', async () => {
  await watch('aurora-action', 'Germany', '(SSAI)')
  await shot('32-watch-geo-blocked', [['css:.player', 'Region-block screen — programmes and ads'], ['Viewer is in', 'Simulated viewer in Germany']])
})

section('7b-partner', async () => {
  await duringBreak(WE, 'India', 'Partner TV', 15000)
  await PLAYLIST()
  await shot('33-watch-partner-markers', [['css:.m3u8 .t-scte', 'CUE marker: the platform puts its own ads here'], ['css:.m3u8 .t-disc', 'Our feed switches to slate for the break'], ['css:.m3u8 .fs-slate', 'Slate pieces (the platform replaces them)']])
})

section('7-watch', async () => {
  await duringBreak(WE, 'India', '(SSAI)', 17000)
  for (let i = 0; i < 25 && !(await page.$('.player-ad')); i++) await sleep(1000)
  await shot('34-watch-ssai-ad', [['css:.player-ad', 'Which ad is playing now'], ['css:.ad-bar', 'Ads chosen for this break'], ['Playing now', 'The break being played']])
  await PLAYLIST()
  await shot('35-watch-ssai-playlist', [['css:.m3u8 .t-scte', 'SCTE-35 marker: break starts'], ['css:.m3u8 .t-ad', 'Ad pieces stitched by the server'], ['css:.m3u8 .t-disc', 'Switch from programme to ad']])
  await sleep(60000)
  await scrollTo('Ad delivery log')
  await shot('38-watch-delivery-log', [['Ad delivery log', 'Proof each ad was delivered'], ['Completed', 'Watched to the end'], ['50%', 'Progress beacons']])
  await duringBreak(WE, 'India', 'SGAI', 17000)
  for (let i = 0; i < 25 && !(await page.$('.player-ad')); i++) await sleep(1000)
  await sleep(2500)
  await shot('36-watch-sgai-ad', [['css:.player-ad', 'Player fetched and plays the ad itself'], ['Watching on', 'SGAI destination']])
  await PLAYLIST()
  await shot('37-watch-sgai-playlist', [['css:.m3u8 .t-inter', 'Interstitial: pointer to the ad list'], ['css:.m3u8 .t-seg', 'Programme pieces, same for everyone']])
})

section('8-ads', async () => {
  await go('/aurora/ads')
  await shot('39-ads', [['New campaign', 'Add a campaign'], ['Est. value', 'Estimate — clearly labelled'], ['Pause', 'Pause / resume'], ['Paused', 'A paused campaign'], ['Ad decision simulator', 'Ask "who would fill the next break?"']])
  await choose('Viewer in', 'India'); await click('Run ad decision'); await sleep(1300)
  await shot('40-ad-simulator', [['css:.split .ad-bar', 'How the break gets filled'], ['Not chosen', 'Why the others were left out'], ['Not targeting IN', 'Example: wrong country']])
  await click('New campaign'); await fill('Campaign name', 'Monsoon Sale'); await fill('Advertiser', 'Sunny Juice (fictional)')
  await shot('41-new-campaign', [['Ad creative', 'Which video ad'], ['Target countries', 'Where it may show'], ['Priority', 'Who goes first'], ['CPM ($)', 'Price per 1,000 views']])
  await page.keyboard.press('Escape')
})

section('9-distribution', async () => {
  await go('/aurora/distribution')
  await shot('42-distribution', [['Honest labelling', 'What is real vs simulated'], ['Marker format', "Each platform's technical rules"], ['Send a channel', 'Send a channel']], { full: true })
  for (const [dest, name, notes] of [
    ['Smart TV Hub', '43-distribution-fail', [['Ad load within destination limit', 'Rule that failed'], ['Not delivered', 'Clear outcome']]],
    ['Partner TV', '44-distribution-pass', [['SIMULATED:', 'Checks passed; hand-off simulated']]]]) {
    await go('/aurora/distribution')
    await page.evaluate(d => { const c = [...document.querySelectorAll('section.card')].find(c => c.querySelector('h3')?.textContent.includes(d)); [...c.querySelectorAll('button')].find(b => b.textContent.includes('Send a channel')).click() }, dest)
    await sleep(600); await choose('Channel', 'Aurora Action'); await click('Run checks & send'); await sleep(1600)
    await shot(name, notes)
  }
})

section('10-operate', async () => {
  await go('/aurora/monitoring')
  await shot('45-monitoring', [['Re-check now', 'Run every check now'], ['Acknowledge', '"I\'m on it"'], ['Resolve…', 'Fixed — with a note'], ['Go fix', 'Jump to the problem']])
  await go('/aurora/monitoring?tab=audit')
  await shot('46-audit-log', [['css:.table tbody tr', 'Who did what, and when'], ['Activity log (audit)', 'Permanent record']])
  await go('/aurora/analytics')
  await shot('47-analytics', [['Where these numbers come from', 'Every number has a source'], ['Fill rate', 'How much ad time was filled'], ['By channel', 'Per channel'], ['Ads shown, last 7 days', 'Trend']], { full: true })
  await go('/aurora/team')
  await shot('48-team', [['css:table select', 'Change someone\'s role'], ['What each role can do', 'Roles explained'], ['Invite someone', 'Add a person']])
  await click('Invite someone'); await fill('Name', 'Priya Shah'); await fill('Work email', 'priya@aurora.test'); await choose('Role', 'Content manager')
  await shot('49-invite', [['Role', 'Pick their role'], ['css:.modal-foot .btn.primary', 'Invite']])
})

section('11-tenants', async () => {
  await login('analyst@aurora.test'); await go('/aurora/channels')
  await shot('50-analyst-read-only', [['css:.topbar .badge.info', 'Role: analyst'], ['Schedule', 'Can look…'], ['css:.page-head', '…but no Create / Go live / Stop buttons']])
  await login('owner@northwind.test'); await go('/aurora')
  await shot('51-other-tenant-blocked', [['Nothing here for you', 'Another company\'s data is invisible']])
  await go('/northwind')
  await shot('52-northwind-home', [['css:select[aria-label=Organization]', 'Only their own company'], ['On air now', "Only Northwind's channel"]])
  await login('admin@platform.test'); await go('/platform')
  await shot('53-platform-admin', [['New organization', 'Add a customer company'], ['Suspend', 'Suspend access (data kept)'], ['Viewer sessions', 'Usage counts only — no content']])
})

section('12-pilot', async () => {
  const { FASTSTUDIO_ADMIN_EMAIL: adminEmail, FASTSTUDIO_ADMIN_PASSWORD: adminPw } = process.env
  if (!adminEmail || !adminPw) throw new Error('set FASTSTUDIO_ADMIN_EMAIL / FASTSTUDIO_ADMIN_PASSWORD')
  await logout(); await go('/login')
  await shot('54-pilot-login', [['css:.login-form form', 'Real accounts only — no demo buttons'], ['Forgot your password?', 'Anyone can ask for a reset link by email']])
  await login(adminEmail, adminPw)
  const org = await post('/api/platform/orgs', { name: 'Harbour Films', slug: 'harbour-films', country: 'IN', timezone: 'Asia/Kolkata', ownerName: 'Meera Rao', ownerEmail: 'meera@harbour-films.example' })
  if (org.status !== 200) throw new Error('create org failed ' + org.status)
  await go('/platform'); await click('New organization')
  await fill('Company name', 'Lotus Studios'); await fill('Owner name', 'Arjun Mehta'); await fill('Owner email', 'arjun@lotus-studios.example')
  await shot('55-pilot-new-org', [['Organization ID', 'Used in web addresses'], ['Owner email', 'First owner of the company'], ['css:.modal-foot .btn.primary', 'Create']])
  await click('Cancel')
  await page.evaluate(() => { const i = document.querySelector('input[aria-label="Email of the account"]'); i.setAttribute('data-q', 'reset') })
  await page.type('[data-q=reset]', 'meera@harbour-films.example')
  await scrollTo("Reset someone's password")
  await shot('56-pilot-platform-reset', [["Reset someone's password", 'For owners and admins who are locked out'], ['Create reset link', 'One-time link, 24 hours']])
  // the new owner signs in with the one-time password
  await login('meera@harbour-films.example', org.json.ownerTemporaryPassword); await go('/')
  await fill('Current password', org.json.ownerTemporaryPassword); await fill('New password', 'Harbour-Strong-Pass-5'); await fill('Repeat new password', 'Harbour-Strong-Pass-5')
  await shot('57-pilot-forced-change', [['Choose your password', 'Temporary password must be replaced first'], ['New password', 'At least 10 characters'], ['Save and continue', 'Then the app opens']])
  await click('Save and continue'); await sleep(1200)
  const inv = await post('/api/orgs/harbour-films/members', { name: 'Kabir Singh', email: 'kabir@harbour-films.example', role: 'CHANNEL_OPERATOR' })
  if (inv.status !== 200) throw new Error('invite failed ' + inv.status)
  await go('/harbour-films/team')
  await click('Reset password')
  await shot('58-pilot-reset-link', [['css:.modal input, .modal code', 'Send this link privately'], ['Copy', 'Copy it'], ['expires in 24 hours', 'Works once, for 24 hours']])
  const link = await page.evaluate(() => { const e = document.querySelector('.modal input, .modal code'); return e.value ?? e.textContent })
  await click('Done')
  await page.evaluate(() => document.querySelector('button[aria-label="Change password"]').click()); await sleep(600)
  await shot('59-pilot-change-password', [['css:button[aria-label="Change password"]', 'Click your initials'], ['Current password', 'Prove it is you'], ['Repeat new password', 'Type the new one twice']])
  await click('Cancel').catch(() => page.keyboard.press('Escape'))
  await page.evaluate(() => fetch('/api/auth/logout', { method: 'POST', headers: { 'X-XSRF-TOKEN': decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) ?? '=').split('=')[1]) } }))
  await page.goto(link, { waitUntil: 'networkidle2' }); await sleep(800)
  await fill('New password', 'Kabir-Strong-Pass-6'); await fill('Repeat new password', 'Kabir-Strong-Pass-6')
  await shot('60-pilot-reset-page', [['Set a new password', 'Opened from the link'], ['css:form button.primary, button.btn.primary', 'Set password, then sign in']])
})

// demo mode (no mail server): emails are written to the outbox by nodemailer and listed on the Email page
section('13-email', async () => {
  await login('owner@aurora.test')
  const nehas = async () => (await api('/api/orgs/aurora/members')).filter(m => m.email.startsWith('neha.'))
  for (const m of await nehas()) await page.evaluate(id => { const tok = decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) ?? '=').split('=')[1]); return fetch('/api/orgs/aurora/members/' + id, { method: 'DELETE', headers: { 'X-XSRF-TOKEN': tok } }) }, m.userId)
  await go('/aurora/team')
  await click('Invite someone'); await fill('Name', 'Neha Kapoor'); await fill('Work email', `neha.${Date.now() % 100000}@aurora.test`); await choose('Role', 'Analyst')
  await click('Invite', '.modal-foot button'); await sleep(1200)
  await shot('61-invite-emailed', [['saved to the outbox', 'The invitation is emailed (here: saved, no mail server yet)'], ['One-time password', 'Still shown, in case the email does not arrive']])
  for (const m of await nehas()) await page.evaluate(id => { const tok = decodeURIComponent((document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')) ?? '=').split('=')[1]); return fetch('/api/orgs/aurora/members/' + id, { method: 'DELETE', headers: { 'X-XSRF-TOKEN': tok } }) }, m.userId)
  await login('admin@platform.test'); await go('/platform/email'); await sleep(1200)
  await shot('62-email-page', [['No mail server is set up', 'Where emails go right now'], ['Send a test email', 'Check delivery'],
    ['css:.tpl-item.active', 'Every email the product sends'], ['css:.var-chip', 'Click to insert a fill-in value']])
  const into = async sel => { await page.evaluate(q => document.querySelector(q)?.scrollIntoView({ block: 'center' }), sel); await sleep(700) }
  await into('.email-preview iframe')
  await shot('62b-email-preview', [['css:.email-preview iframe', 'Live preview, exactly as received'], ['Preview with example values', 'Example values fill the gaps']])
  await click('Password reset link', '.tpl-item')
  await page.click('textarea'); await page.keyboard.down('Control'); await page.keyboard.press('KeyA'); await page.keyboard.up('Control')
  await page.keyboard.type('Hello {{name}}, please call the help desk to get a new password.'); await sleep(1000)
  await into('.callout.bad')
  await shot('63-email-check', [['Keep {{resetLink}}', 'Mistakes are caught before saving'], ['Save template', 'Save stays off until fixed'], ['Undo changes', 'Back to the saved wording']])
  await click('Undo changes')
  await fill('Subject', 'Choose a new FAST Studio password, {{name}}'); await sleep(1000)
  await click('Save template'); await sleep(1500)
  await into('.email-preview')
  await shot('64-email-customized', [['Back to default wording', 'One click back to the original'], ['Preview with example values', 'The new subject, used from now on']])
  await click('Back to default wording'); await click('Use default', '.modal button'); await sleep(800)
  await top(); await click('Send a test email'); await sleep(3000)
  await shot('65-email-sent', [['Sent emails', 'The log of every email'], ['css:tbody tr:first-child td:last-child .badge', 'Result: queued, sent, saved or failed'], ['css:tbody tr:nth-child(2) td:nth-child(2)', 'Click any email to open it']])
  await click('invited you to', 'tbody tr'); await sleep(1500)
  await shot('66-email-message', [['This is the logged copy', 'One-time passwords never stay in the log'], ['css:.modal iframe', 'What the person received'], ['Saved as', 'The .eml file nodemailer wrote']])
  await click('Close', '.modal button')
})

// needs a PILOT started with a mail server (FASTSTUDIO_SMTP_URL), so "Forgot your password?" is offered
section('14-forgot', async () => {
  await logout(); await go('/login')
  await click('Forgot your password?')
  await fill('Email', process.env.FORGOT_EMAIL ?? 'admin@pilot.local')
  await shot('67-forgot-form', [['Forgot your password?', 'Shown only when a mail server is set up'], ['css:.login-form form.card', 'Type your email above, then ask for a link'], ['Email me a reset link', 'Sends the reset email']])
  await click('Email me a reset link'); await sleep(1200)
  await shot('68-forgot-sent', [['If an account exists', 'Same answer for every email (no account fishing)']])
})

// partners (demo: Aurora shares Aurora Action + Classic Cinema with the fictional "Partner TV" app)
section('16-partners', async () => {
  await login('owner@aurora.test'); await go('/aurora/partners'); await sleep(800)
  await shot('71-partners', [['css:.card h3', 'The partner and its status'], ['css:.card .kv', 'Channels, permissions, delivery settings, activity'],
    ['Share with a partner', 'Share with another partner'], ['Stop sharing', 'Stop at any time']])
  await click('Change what is shared'); await sleep(900)
  await shot('72-partners-share', [['Only the channels I pick', 'Pick channels, or all of them'], ['What they may use', 'Five read-only permissions'],
    ['Delivery settings for this partner', 'Their ad mode and markers'], ['css:.modal-foot .btn.primary', 'Save']])
  await click('Cancel', '.modal-foot button')
  await login('partner@partnertv.test'); await go('/partner-tv'); await sleep(900)
  await shot('73-partner-shared', [['css:.nav', 'A partner sees a small menu'], ['Aurora Media Network', 'Who shares with us'],
    ['Viewing numbers: not shared', 'What is not shared is shown as such'], ['css:.tpl-item', 'Open a shared channel']])
  await click('Aurora Action', '.tpl-item'); await sleep(1500)
  await shot('74-partner-guide', [['css:.drawer .tabs', 'Only shared tabs open'], ['XMLTV', 'Guide file for their TV-guide system'], ['css:.drawer tbody tr', 'What plays when']])
  await click('Stream', '.drawer .tab'); await sleep(900)
  await click('Try it as a viewer'); await sleep(7000)
  await shot('75-partner-stream', [['css:.drawer pre', 'How their app starts a stream'], ['css:.drawer select', 'Viewer country'], ['css:.drawer .player, .drawer video', 'The real stream, with their ad set-up']])
  await click('Ad breaks', '.drawer .tab'); await sleep(1200)
  await shot('76-partner-breaks', [['css:.drawer tbody tr', 'Upcoming ad breaks'], ['SCTE-35 (cue out)', 'Markers for their ad system']])
  await page.keyboard.press('Escape')
  await login('owner@aurora.test'); await go('/aurora/monitoring?tab=audit'); await sleep(900)
  await shot('79-partner-activity', [['(Partner TV)', 'Partner reads, in your own log'], ['partner.read', 'What they read']])
  await login('admin@platform.test'); await go('/platform'); await click('New organization')
  await choose('Kind of organization', 'Partner'); await sleep(400)
  await shot('80-platform-partner', [['Kind of organization', 'Customer or partner'], ['Partner admin email', 'Their first partner admin']])
  await click('Cancel', '.modal-foot button')
})

// partner API clients (each run creates one more client in the demo data)
section('16b-partners-api', async () => {
  await login('partner@partnertv.test'); await go('/partner-tv/api'); await sleep(700)
  await click('New API client'); await fill('Name', 'Guide importer')
  await click('Create', '.modal-foot button'); await sleep(1200)
  await shot('77-partner-api-secret', [['Client secret', 'Shown only once'], ['css:.modal pre', 'Ready-to-run example'], ['Copy the secret now', 'Store it in their system']])
  await click("I've stored it"); await sleep(600)
  await shot('78-partner-api', [['How it works', 'Three steps for their developers'], ['css:tbody tr:first-child', 'Each system has its own client'], ['OpenAPI document', 'Full reference']])
})

// plan diagrams (no server needed): tools/plan-diagrams.html -> 69-*.png, 70-*.png
section('15-plan', async () => {
  await page.goto(new URL('plan-diagrams.html', import.meta.url).href, { waitUntil: 'load' })
  for (const [id, file] of [['orgs', '69-plan-partner-orgs'], ['token', '70-plan-token'], ['usecase', '81-partner-usecase']]) await (await page.$('#' + id)).screenshot({ path: `${OUT}/${file}.png` })
})

if (ONLY && !ONLY.every(o => /^1[2-5]/.test(o))) await login('owner@aurora.test')
for (const [name, fn] of Object.entries(sections)) {
  if (ONLY && !ONLY.some(o => name.startsWith(o))) continue
  log('== section', name)
  try { await fn() } catch (e) { log('SECTION FAILED', name, e.message) }
}
await browser.close()
log('done')
