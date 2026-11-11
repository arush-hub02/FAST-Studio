import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BreakDecision } from './decision.ts'

const request = (lock = true, layout?: 'split' | 'muxed') => new Request('https://decision.internal/', { method: 'POST', body: JSON.stringify({ sid: 's', breakId: 'b', lock, layout, ip: '', ua: '' }) })
function storage() {
  const values = new Map<string, unknown>()
  let tail = Promise.resolve()
  return {
    storage: { get: async (k: string) => values.get(k), put: async (k: string, v: unknown) => { values.set(k, v) }, setAlarm: async (_: number) => {}, deleteAll: async () => { values.clear() } },
    blockConcurrencyWhile<T>(fn: () => Promise<T>): Promise<T> { const next = tail.then(fn); tail = next.then(() => {}, () => {}); return next },
  }
}
test('a slate lock survives object recreation after control-plane recovery', async () => {
  const state = storage(), env = { CONTROL_URL: 'https://control.invalid', MEDIA: { head: async () => null } }, original = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response('down', { status: 503 })
    assert.equal(await (await new BreakDecision(state, env).fetch(request())).json(), 'slate')
    globalThis.fetch = async () => Response.json({ slots: [{ campaignId: 'ad' }] })
    assert.equal(await (await new BreakDecision(state, env).fetch(request())).json(), 'slate')
  } finally { globalThis.fetch = original }
})
test('failed prefetch can retry; concurrent final requests share one decision', async () => {
  const state = storage(), object = new BreakDecision(state, { CONTROL_URL: 'https://control.invalid', MEDIA: { head: async () => null } }), original = globalThis.fetch
  let calls = 0
  try {
    globalThis.fetch = async () => new Response('down', { status: 503 })
    assert.equal((await object.fetch(request(false))).status, 503)
    globalThis.fetch = async () => { calls++; return Response.json({ slots: [] }) }
    const results = await Promise.all([object.fetch(request()), object.fetch(request())])
    assert.deepEqual(await results[0].json(), await results[1].json())
    assert.equal(calls, 1)
  } finally { globalThis.fetch = original }
})

test('an early incomplete creative is retried and only matching fMP4 objects enter the break', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: Date.now() })
  const state = storage(), original = globalThis.fetch
  let available = false, calls = 0
  const object = new BreakDecision(state, {
    CONTROL_URL: 'https://control.invalid',
    MEDIA: { head: async key => available && key === 'ready/s-master.m3u8' ? {} : null },
  })
  try {
    globalThis.fetch = async (_url, request) => {
      calls++
      assert.equal(new Headers(request?.headers).get('X-Ad-Prefetch'), 'true')
      if (calls === 1) return new Response(null, { status: 202 })
      return Response.json({ breakMs: 30000, filledMs: 30000, slots: [
        { mediaKey: 'ready', levels: [3], durationMs: 15000 },
        { mediaKey: 'legacy-only', levels: [3], durationMs: 15000 },
      ] })
    }
    assert.equal((await object.fetch(request(false, 'split'))).status, 202)
    assert.equal((await object.fetch(request(false, 'split'))).status, 202)
    assert.equal(calls, 1)
    t.mock.timers.tick(8000)
    available = true
    const ready = await (await object.fetch(request(false, 'split'))).json()
    assert.equal(ready.filledMs, 15000)
    assert.deepEqual(ready.slots.map((slot: { mediaKey: string }) => slot.mediaKey), ['ready'])
    available = false
    assert.deepEqual(await (await object.fetch(request(true, 'split'))).json(), ready)
    assert.equal(calls, 2)
  } finally { globalThis.fetch = original }
})

test('a Direct slot needs no R2 object on an fMP4 channel and does not cut the break', async () => {
  const state = storage(), original = globalThis.fetch
  const object = new BreakDecision(state, {
    CONTROL_URL: 'https://control.invalid',
    MEDIA: { head: async key => key === 'ours/s-master.m3u8' ? {} : null },
  })
  try {
    globalThis.fetch = async () => Response.json({ breakMs: 45000, filledMs: 45000, slots: [
      { campaignId: 'd1', mediaKey: null, durationMs: 30000, delivery: 'DIRECT', hlsUrl: 'https://ads.cdn/a/master.m3u8' },
      { campaignId: 'c1', mediaKey: 'ours', levels: [0], durationMs: 15000 },
    ] })
    const d = await (await object.fetch(request(true, 'split'))).json()
    assert.deepEqual(d.slots.map((s: { campaignId: string }) => s.campaignId), ['d1', 'c1'])
    assert.equal(d.filledMs, 45000)
  } finally { globalThis.fetch = original }
})
