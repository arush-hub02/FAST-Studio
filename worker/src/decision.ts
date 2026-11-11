import type { Decision, Fmp4Layout } from './playout.ts'

type Store = { get(key: string): Promise<unknown>; put(key: string, value: unknown): Promise<void>; setAlarm(at: number): Promise<void>; deleteAll(): Promise<void> }
type State = { storage: Store }
type Env = { CONTROL_URL: string; MEDIA: { head(key: string): Promise<unknown> } }

/** One durable final decision, including slate, for a viewer/break across all edge locations. */
export class BreakDecision {
  private state: State
  private env: Env
  private retryAt = 0
  private pending = Promise.resolve()
  constructor(state: State, env: Env) { this.state = state; this.env = env }

  async fetch(req: Request): Promise<Response> {
    const { sid, breakId, lock, ip, ua, layout } = await req.json() as { sid: string; breakId: string; lock: boolean; ip: string; ua: string; layout?: Fmp4Layout }
    // Serialize only this viewer/break while the provider and R2 checks are in flight.
    const result = this.pending.then(async () => {
      const key = layout ? 'decision:' + layout : 'decision'
      const prior = await this.state.storage.get(key)
      if (prior !== undefined) return Response.json(prior)
      if (!lock && Date.now() < this.retryAt) return new Response(null, { status: 202 })
      let decision: Decision | 'slate'
      try {
        // Reuse existing decisions when upgrading a running edge or changing layouts.
        const old = layout ? await this.state.storage.get('decision') as Decision | 'slate' | undefined : undefined
        if (old !== undefined) decision = old
        else {
          const res = await fetch(`${this.env.CONTROL_URL}/play/${sid}/decisions/${breakId}`, {
            headers: { 'X-Viewer-IP': ip, 'X-Viewer-UA': ua, 'X-Ad-Prefetch': String(!lock) }, signal: AbortSignal.timeout(3000),
          })
          if (res.status === 202 && !lock) return this.retry()
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          decision = await res.json() as Decision
        }
        if (layout && decision !== 'slate') {
          const slots: Decision['slots'] = []
          const marker = layout === 'split' ? 's-master.m3u8' : 'f-master.m3u8'
          for (const slot of decision.slots) {
            if (slot.delivery === 'DIRECT') { slots.push(slot); continue }   // the ad server's own HLS: nothing of ours in R2
            if (!await this.env.MEDIA.head(`${slot.mediaKey}/${marker}`)) break
            let levels = slot.levels
            if (!levels?.length) {
              const prefix = layout === 'split' ? 'v' : 'f'
              const available = await Promise.all([0, 1, 2, 3, 4, 5].map(i =>
                this.env.MEDIA.head(`${slot.mediaKey}/${prefix}${i}/init.mp4`).then(found => found ? i : -1)))
              levels = available.filter(i => i >= 0)
              if (!levels.length) break
            }
            slots.push({ ...slot, levels })
          }
          if (!lock && slots.length === 0 && decision.slots.length > 0) return this.retry()
          decision = { ...decision, slots, filledMs: slots.reduce((ms, slot) => ms + slot.durationMs, 0) }
        }
      } catch {
        if (!lock) return new Response('Decision not ready', { status: 503 })
        decision = 'slate'
      }
      await this.state.storage.put(key, decision)
      await this.state.storage.setAlarm(Date.now() + 24 * 3600_000)
      return Response.json(decision)
    })
    this.pending = result.then(() => {}, () => {})
    return result
  }
  private retry() {
    this.retryAt = Date.now() + 8000
    return new Response(null, { status: 202 })
  }

  async alarm() { await this.state.storage.deleteAll() }
}
