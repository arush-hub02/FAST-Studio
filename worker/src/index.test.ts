import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createHmac } from 'node:crypto'
import worker from './index.ts'

test('HTTP 503 delivery errors are retried on the next live poll', async () => {
  const now = Math.floor(Date.now() / 5000) * 5000, secret = 'worker-report-test-at-least-32-characters'
  const session = { sid: 'report-test', org: 'test', channel: 'test', country: 'IN', adMode: 'SSAI', markers: 'CUE_OUT', destKind: 'OWNED_WEB', created: now - 60000 }
  const payload = Buffer.from(JSON.stringify(session)).toString('base64url')
  const token = payload + '.' + createHmac('sha256', secret).update(payload).digest('base64url')
  const brk = '11111111-1111-1111-1111-111111111111'
  const snapshot = { v: 1, org: 'test', channel: 'test', status: 'LIVE', territories: ['IN'], segMs: 5000, from: now - 3600000, until: now + 3600000,
    ladder: [{ name: '360p', width: 640, height: 360, bandwidth: 800000, codecs: 'avc1.4d401e,mp4a.40.2' }],
    standby: { key: 'standby', segments: 12 }, geo: { key: 'geo', segments: 6 }, fallback: null,
    items: [{ id: brk, kind: 'AD_BREAK', key: null, segments: 0, offsetMs: 0, start: now - 30000, end: now + 30000, allowed: ['IN'], ownership: 'PROVIDER', scteOut: 'OUT', scteIn: 'IN' }] }
  const decision = { breakMs: 60000, filledMs: 30000, slots: [{ campaignId: 'campaign', campaign: 'Ad', mediaKey: 'test/ad', durationMs: 30000 }] }
  const env = { EDGE_SECRET: secret, CONTROL_URL: 'https://control.invalid', MEDIA: {}, SCHEDULES: { get: async () => snapshot },
    DECISIONS: { idFromName: (n: string) => n, get: () => ({ fetch: async () => Response.json(decision) }) } }
  let posts = 0
  const original = globalThis.fetch
  globalThis.fetch = async () => { posts++; return new Response('retry later', { status: 503 }) }
  try {
    async function poll() {
      const pending: Promise<unknown>[] = []
      const r = await worker.fetch(new Request(`https://edge.invalid/live/${token}/v0.m3u8`), env, { waitUntil: p => { pending.push(p) } })
      assert.equal(r.status, 200)
      await Promise.all(pending)
    }
    await poll()
    const first = posts
    assert.ok(first > 0)
    await poll()
    assert.ok(posts > first, 'failed HTTP responses must not be marked delivered')
  } finally { globalThis.fetch = original }
})

test('a legacy fMP4 snapshot references existing slate objects and keeps the old window on a slate upload', async (t) => {
  const now = Math.floor(Date.now() / 5000) * 5000
  t.mock.timers.enable({ apis: ['Date'], now })
  const secret = 'worker-report-test-at-least-32-characters'
  const session = { sid: 'slate-test', org: 'test', channel: 'slate', country: 'IN', adMode: 'SSAI', markers: 'CUE_OUT', destKind: 'OWNED_WEB', created: now - 60000 }
  const payload = Buffer.from(JSON.stringify(session)).toString('base64url')
  const token = payload + '.' + createHmac('sha256', secret).update(payload).digest('base64url')
  let saved = { v: 2, org: 'test', channel: 'slate', status: 'LIVE', fmt: 'FMP4', territories: ['IN'],
    segMs: 5000, from: now - 3600000, until: now + 3600000,
    ladder: [{ name: '360p', width: 640, height: 360, bandwidth: 800000, codecs: 'avc1.4d401e,mp4a.40.2' }],
    standby: { key: 'old', segments: 2, levels: [0] }, geo: { key: 'geo', segments: 2, levels: [0] }, fallback: null, items: [] }
  const exists = (key: string) => /^(old|new)\/f0\/(init\.mp4|seg_0000[01]\.m4s)$/.test(key)
  const env = { EDGE_SECRET: secret, CONTROL_URL: 'https://control.invalid', MEDIA: {
    head: async (key: string) => exists(key) ? { httpEtag: 'test' } : null },
    SCHEDULES: { get: async () => structuredClone(saved), put: async (_: string, json: string) => { saved = JSON.parse(json) } }, DECISIONS: {} }
  const ctx = { waitUntil: () => {} }
  const live = (name: string) => worker.fetch(new Request('https://edge.invalid/live/' + token + '/' + name), env, ctx)
  const variant = async () => (await live('v0.m3u8')).text()

  assert.ok(!(await (await live('master.m3u8')).text()).includes('a.m3u8'))
  const before = await variant()
  const update = await worker.fetch(new Request('https://edge.invalid/_ingest/channels/test/slate', {
    method: 'PUT', headers: { Authorization: 'Bearer ' + secret },
    body: JSON.stringify({ ...saved, standby: { ...saved.standby, key: 'new' } }),
  }), env, ctx)
  assert.equal(update.status, 204)
  assert.equal(await variant(), before)

  t.mock.timers.tick(65000)
  const later = await variant()
  assert.match(later, /#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:[^\n]+\n#EXT-X-MAP:URI="[^"]+\/new\/f0\/init\.mp4"/)
  const urls = [...later.matchAll(/https:\/\/edge\.invalid\/media\/[^"\n]+/g)].map(m => m[0])
  assert.ok(urls.some(url => url.includes('/old/')))
  for (const url of urls) {
    const segment = await worker.fetch(new Request(url, { method: 'HEAD' }), env, ctx)
    assert.equal(segment.status, 200)
  }
})
