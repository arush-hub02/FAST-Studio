type Store = { get(key: string): Promise<unknown>; put(key: string, value: unknown): Promise<void>; setAlarm(at: number): Promise<void>; deleteAll(): Promise<void> }
type State = { storage: Store; blockConcurrencyWhile<T>(fn: () => Promise<T>): Promise<T> }
type Env = { CONTROL_URL: string }

/** One durable final decision, including slate, for a viewer/break across all edge locations. */
export class BreakDecision {
  private state: State
  private env: Env
  constructor(state: State, env: Env) { this.state = state; this.env = env }

  async fetch(req: Request): Promise<Response> {
    const { sid, breakId, lock, ip, ua } = await req.json() as { sid: string; breakId: string; lock: boolean; ip: string; ua: string }
    return this.state.blockConcurrencyWhile(async () => {
      const prior = await this.state.storage.get('decision')
      if (prior !== undefined) return Response.json(prior)
      let decision: unknown
      try {
        const res = await fetch(`${this.env.CONTROL_URL}/play/${sid}/decisions/${breakId}`, {
          headers: { 'X-Viewer-IP': ip, 'X-Viewer-UA': ua }, signal: AbortSignal.timeout(3000),
        })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        decision = await res.json()
      } catch {
        if (!lock) return new Response('Decision not ready', { status: 503 })
        decision = 'slate'
      }
      await this.state.storage.put('decision', decision)
      await this.state.storage.setAlarm(Date.now() + 24 * 3600_000)
      return Response.json(decision)
    })
  }
  async alarm() { await this.state.storage.deleteAll() }
}
