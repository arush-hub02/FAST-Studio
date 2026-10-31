import assert from 'node:assert/strict'
import { test } from 'node:test'
import { BreakDecision } from './decision.ts'

const request = (lock = true) => new Request('https://decision.internal/', { method: 'POST', body: JSON.stringify({ sid: 's', breakId: 'b', lock, ip: '', ua: '' }) })
function storage() {
  const values = new Map<string, unknown>()
  let tail = Promise.resolve()
  return {
    storage: { get: async (k: string) => values.get(k), put: async (k: string, v: unknown) => { values.set(k, v) }, setAlarm: async (_: number) => {}, deleteAll: async () => { values.clear() } },
    blockConcurrencyWhile<T>(fn: () => Promise<T>): Promise<T> { const next = tail.then(fn); tail = next.then(() => {}, () => {}); return next },
  }
}
test('a slate lock survives object recreation after control-plane recovery', async () => {
  const state = storage(), env = { CONTROL_URL: 'https://control.invalid' }, original = globalThis.fetch
  try {
    globalThis.fetch = async () => new Response('down', { status: 503 })
    assert.equal(await (await new BreakDecision(state, env).fetch(request())).json(), 'slate')
    globalThis.fetch = async () => Response.json({ slots: [{ campaignId: 'ad' }] })
    assert.equal(await (await new BreakDecision(state, env).fetch(request())).json(), 'slate')
  } finally { globalThis.fetch = original }
})
test('failed prefetch can retry; concurrent final requests share one decision', async () => {
  const state = storage(), object = new BreakDecision(state, { CONTROL_URL: 'https://control.invalid' }), original = globalThis.fetch
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
