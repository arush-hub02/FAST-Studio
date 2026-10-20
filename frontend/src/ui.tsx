import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import Hls from 'hls.js'
import { api, ApiError } from './api'

// ---------------------------------------------------------------- icons (inline, stroke-based)

const P: Record<string, ReactNode> = {
  home: <path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />,
  film: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4" /></>,
  tv: <><rect x="3" y="6" width="18" height="13" rx="2" /><path d="m8 2 4 4 4-4" /></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 10h18M8 3v4M16 3v4" /></>,
  grid: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 10h18M9 4v16" /></>,
  play: <><circle cx="12" cy="12" r="9" /><path d="m10 8.5 5.5 3.5-5.5 3.5z" /></>,
  megaphone: <><path d="M3 11v2a1 1 0 0 0 1 1h3l6 4V6L7 10H4a1 1 0 0 0-1 1z" /><path d="M17 8a5 5 0 0 1 0 8" /></>,
  shield: <path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6z" />,
  send: <path d="m22 2-7 20-4-9-9-4zM22 2 11 13" />,
  activity: <path d="M22 12h-4l-3 9L9 3l-3 9H2" />,
  chart: <><path d="M3 3v18h18" /><path d="M7 16v-4M12 16V8M17 16v-7" /></>,
  users: <><circle cx="9" cy="8" r="4" /><path d="M2 21a7 7 0 0 1 14 0M16 3.5a4 4 0 0 1 0 8M22 21a7 7 0 0 0-4-6.3" /></>,
  book: <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5zM4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5" />,
  building: <><rect x="4" y="3" width="16" height="18" rx="1" /><path d="M9 8h1M14 8h1M9 12h1M14 12h1M10 21v-4h4v4" /></>,
  plus: <path d="M12 5v14M5 12h14" />,
  mail: <><rect x="3" y="5" width="18" height="14" rx="2" /><path d="m3 7 9 6 9-6" /></>,
  key: <><circle cx="8" cy="15" r="4" /><path d="m10.8 12.2 9.2-9.2M17 6l3 3M14 9l2 2" /></>,
  link: <path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" />,
  x: <path d="M18 6 6 18M6 6l12 12" />,
  check: <path d="M20 6 9 17l-5-5" />,
  alert: <><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" /><path d="M12 9v4M12 17h.01" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 16v-4M12 8h.01" /></>,
  upload: <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" />,
  wand: <path d="m15 4 5 5M3 21l12-12M14 3v2M20 9h2M19 3l1 1M10 4l1 1" />,
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></>,
  logout: <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />,
  refresh: <path d="M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5" />,
  trash: <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6" />,
  sparkle: <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6" />,
  globe: <><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" /></>,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  code: <path d="m16 18 6-6-6-6M8 6l-6 6 6 6" />,
  arrowRight: <path d="M5 12h14M13 5l7 7-7 7" />,
  stop: <rect x="6" y="6" width="12" height="12" rx="2" />,
  rocket: <path d="M5 15c-1.5 1.5-2 5-2 5s3.5-.5 5-2M14 6a8 8 0 0 1 5-3s0 3-3 5l-6 6-2-2zM9 11 6 10l-3 3 4 1M13 15l1 3-3 3-1-4" />,
  chevronsLeft: <path d="m11 17-5-5 5-5M18 17l-5-5 5-5" />,
  chevronsRight: <path d="m6 17 5-5-5-5M13 17l5-5-5-5" />,
  chevronLeft: <path d="m15 18-6-6 6-6" />,
  chevronRight: <path d="m9 18 6-6-6-6" />,
  panelLeft: <><rect width="18" height="18" x="3" y="3" rx="2" /><path d="M9 3v18" /></>,
  gripVertical: <><circle cx="9" cy="12" r="1.5" /><circle cx="9" cy="6" r="1.5" /><circle cx="9" cy="18" r="1.5" /><circle cx="15" cy="12" r="1.5" /><circle cx="15" cy="6" r="1.5" /><circle cx="15" cy="18" r="1.5" /></>,
}

export function Icon({ name, size = 18 }: { name: keyof typeof P | string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {P[name]}
    </svg>
  )
}

// ---------------------------------------------------------------- formatting

export function fmtTime(iso?: string | null, tz?: string) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', timeZone: tz }).format(new Date(iso))
}
export function fmtDayTime(iso?: string | null, tz?: string) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: tz }).format(new Date(iso))
}
export function fmtDate(iso?: string | null) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' }).format(new Date(iso))
}
export function fmtDur(ms: number) {
  const s = Math.round(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60
  return h ? `${h}h ${m}m` : m ? `${m}:${String(r).padStart(2, '0')}` : `${r}s`
}
export function ago(iso: string) {
  const d = (Date.now() - new Date(iso).getTime()) / 1000
  if (d < 60) return 'just now'
  if (d < 3600) return Math.floor(d / 60) + ' min ago'
  if (d < 86400) return Math.floor(d / 3600) + ' h ago'
  return Math.floor(d / 86400) + ' d ago'
}
export function initials(s: string) {
  return s.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]).join('').toUpperCase()
}

export const COUNTRIES: Record<string, string> = {
  WW: 'Worldwide', IN: 'India', GB: 'United Kingdom', US: 'United States', CA: 'Canada', IE: 'Ireland', AU: 'Australia', DE: 'Germany',
  FR: 'France', ES: 'Spain', BR: 'Brazil', MX: 'Mexico', JP: 'Japan', SG: 'Singapore', AE: 'United Arab Emirates', ZA: 'South Africa',
}
export function flag(code: string) {
  if (code === 'WW') return '🌐'
  return String.fromCodePoint(...[...code.toUpperCase()].map(c => 0x1f1e6 - 65 + c.charCodeAt(0)))
}
export const csv = (s?: string) => (s ?? '').split(',').map(x => x.trim()).filter(Boolean)

// ---------------------------------------------------------------- data hook

export function useApi<T>(path: string | null, deps: unknown[] = [], pollMs?: number) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(true)
  const reload = useCallback(async () => {
    if (!path) return
    try {
      setData(await api<T>(path))
      setError(null)
    } catch (e) {
      setError(e as ApiError)
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, ...deps])
  useEffect(() => { setLoading(true); reload() }, [reload])
  useEffect(() => {
    if (!pollMs) return
    const t = setInterval(reload, pollMs)
    return () => clearInterval(t)
  }, [reload, pollMs])
  return { data, error, loading, reload, setData }
}

// ---------------------------------------------------------------- toasts & confirm

type Toast = { id: number; text: string; kind?: 'ok' | 'bad' }
const ToastCtx = createContext<(text: string, kind?: 'ok' | 'bad') => void>(() => {})
export const useToast = () => useContext(ToastCtx)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([])
  const push = useCallback((text: string, kind?: 'ok' | 'bad') => {
    const id = Date.now() + Math.random()
    setItems(x => [...x, { id, text, kind }])
    setTimeout(() => setItems(x => x.filter(t => t.id !== id)), kind === 'bad' ? 7000 : 4000)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {items.map(t => (
          <div key={t.id} className={'toast ' + (t.kind ?? '')}>
            <Icon name={t.kind === 'bad' ? 'alert' : 'check'} size={16} />
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}

/** Runs an async action with a busy flag; shows success / error toasts. */
export function useAction() {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const run = useCallback(async <T,>(fn: () => Promise<T>, ok?: string | ((r: T) => string)): Promise<T | undefined> => {
    setBusy(true)
    try {
      const r = await fn()
      if (ok) toast(typeof ok === 'function' ? ok(r) : ok, 'ok')
      return r
    } catch (e) {
      toast(e instanceof Error ? e.message : String(e), 'bad')
      return undefined
    } finally {
      setBusy(false)
    }
  }, [toast])
  return { busy, run }
}

// ---------------------------------------------------------------- layout pieces

export function PageHead({ title, subtitle, actions, tip }: { title: string; subtitle?: ReactNode; actions?: ReactNode; tip?: string }) {
  return (
    <div className="page-head">
      <div>
        <div className="row"><h1>{title}</h1>{tip && <InfoTip text={tip} />}</div>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {actions && <div className="row-wrap">{actions}</div>}
    </div>
  )
}

export function InfoTip({ text }: { text: ReactNode }) {
  const [open, setOpen] = useState(false)
  return (
    <span className="tip" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
      <button type="button" className="tip-btn" aria-label="What is this?" onClick={() => setOpen(o => !o)} onBlur={() => setOpen(false)}>?</button>
      {open && <span className="tip-pop" role="tooltip">{text}</span>}
    </span>
  )
}

export function Modal({ title, subtitle, onClose, children, footer, wide }: {
  title: string; subtitle?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean
}) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <div className="overlay" onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div className={'modal' + (wide ? ' wide' : '')} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>
          <button className="btn ghost icon-btn" onClick={onClose} aria-label="Close"><Icon name="x" /></button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  )
}

export function Drawer({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <>
      <div className="overlay" style={{ background: 'rgba(15,23,42,.25)' }} onMouseDown={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true">{children}</aside>
    </>
  )
}

export function Confirm({ title, text, confirmLabel = 'Confirm', danger, onConfirm, onClose }: {
  title: string; text: ReactNode; confirmLabel?: string; danger?: boolean; onConfirm: () => void; onClose: () => void
}) {
  return (
    <Modal title={title} onClose={onClose} footer={<>
      <button className="btn" onClick={onClose}>Cancel</button>
      <button className={'btn ' + (danger ? 'danger solid' : 'primary')} onClick={() => { onConfirm(); onClose() }}>{confirmLabel}</button>
    </>}>
      <div className="muted">{text}</div>
    </Modal>
  )
}

export function Empty({ icon = 'sparkle', title, text, action }: { icon?: string; title: string; text?: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-icon"><Icon name={icon} size={24} /></div>
      <h3>{title}</h3>
      {text && <p style={{ maxWidth: 420 }}>{text}</p>}
      {action}
    </div>
  )
}

export function Loading({ rows = 3 }: { rows?: number }) {
  return <div className="stack">{Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" style={{ height: 56 }} />)}</div>
}

export function ErrorBox({ error, retry }: { error: ApiError; retry?: () => void }) {
  return (
    <div className="callout bad"><Icon name="alert" /><div className="grow">{error.message}</div>
      {retry && <button className="btn sm" onClick={retry}>Try again</button>}</div>
  )
}

/** Marks anything that is not real-world evidence. */
export function Sim({ text = 'Simulated', tip }: { text?: string; tip?: string }) {
  return (
    <span className="row" style={{ gap: 4 }}>
      <span className="badge sim"><Icon name="sparkle" size={12} />{text}</span>
      {tip && <InfoTip text={tip} />}
    </span>
  )
}

const STATUS: Record<string, [string, string]> = {
  LIVE: ['live', 'On air'], DRAFT: ['', 'Draft'], STOPPED: ['warn', 'Stopped'], ARCHIVED: ['', 'Archived'],
  READY: ['ok', 'Ready'], PROCESSING: ['info', 'Processing'], FAILED: ['bad', 'Failed'],
  OK: ['ok', 'Licensed'], EXPIRING: ['warn', 'Expiring soon'], EXPIRED: ['bad', 'Expired'], MISSING: ['bad', 'No licence'], INCOMPLETE: ['bad', 'Incomplete licence'],
  ACTIVE: ['ok', 'Active'], PAUSED: ['warn', 'Paused'], ENDED: ['', 'Ended'],
  OPEN: ['bad', 'Open'], ACKNOWLEDGED: ['warn', 'Acknowledged'], RESOLVED: ['ok', 'Resolved'],
  SUCCEEDED: ['ok', 'Succeeded'], CRITICAL: ['bad', 'Critical'], WARNING: ['warn', 'Warning'], INFO: ['info', 'Info'], ERROR: ['bad', 'Must fix'],
  SUSPENDED: ['bad', 'Suspended'],
  QUEUED: ['info', 'Queued'], SENT: ['ok', 'Sent'], SAVED: ['warn', 'Saved to outbox'],
  INVITED: ['warn', 'Waiting for partner'], REVOKED: ['', 'Stopped'],
}
export function Status({ value }: { value: string }) {
  const [cls, label] = STATUS[value] ?? ['', value]
  return <span className={'badge ' + cls}>{value === 'PROCESSING' ? <span className="spinner" style={{ width: 10, height: 10, borderWidth: 1.5 }} /> : <span className="dot" />}{label}</span>
}

export function ChannelLogo({ name, color, size = 40 }: { name: string; color: string; size?: number }) {
  return <div className="ch-logo" style={{ background: `linear-gradient(135deg, ${color}, ${color}cc)`, width: size, height: size, fontSize: size * .38 }}>{initials(name)}</div>
}

export function CountryPicker({ value, onChange, allowWorldwide }: { value: string[]; onChange: (v: string[]) => void; allowWorldwide?: boolean }) {
  const codes = Object.keys(COUNTRIES).filter(c => allowWorldwide || c !== 'WW')
  return (
    <div className="row-wrap">
      {codes.map(c => {
        const on = value.includes(c)
        return (
          <button type="button" key={c} className={'btn sm' + (on ? ' primary' : '')} aria-pressed={on}
            onClick={() => onChange(on ? value.filter(x => x !== c) : [...value, c])}>
            {flag(c)} {COUNTRIES[c]}
          </button>
        )
      })}
    </div>
  )
}

export function Countries({ codes, bad }: { codes: string[]; bad?: boolean }) {
  if (!codes.length) return <span className="faint">none</span>
  return <span className="row-wrap" style={{ gap: 4 }}>{codes.map(c => <span key={c} className={'chip' + (bad ? ' bad' : '')} title={COUNTRIES[c] ?? c}>{flag(c)} {c}</span>)}</span>
}

// ---------------------------------------------------------------- video

export type PlayerEvent = { type: 'interstitial-start' | 'interstitial-end' | 'error' | 'playing'; uri?: string; message?: string }

export function VideoPlayer({ src, live, onEvent, poster }: { src: string | null; live?: boolean; onEvent?: (e: PlayerEvent) => void; poster?: string }) {
  const ref = useRef<HTMLVideoElement>(null)
  const cb = useRef(onEvent)
  cb.current = onEvent
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    const video = ref.current
    if (!video || !src) return
    setErr(null)
    if (!Hls.isSupported()) {
      video.src = src     // Safari plays HLS natively (incl. interstitials)
      return
    }
    const hls = new Hls({ liveSyncDurationCount: 3, enableInterstitialPlayback: true })
    hls.loadSource(src)
    hls.attachMedia(video)
    hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => { /* autoplay blocked: user presses play */ }) })
    hls.on(Hls.Events.INTERSTITIAL_ASSET_STARTED, (_e, d) => cb.current?.({ type: 'interstitial-start', uri: d.asset?.uri }))
    hls.on(Hls.Events.INTERSTITIAL_ASSET_ENDED, (_e, d) => cb.current?.({ type: 'interstitial-end', uri: d.asset?.uri }))
    hls.on(Hls.Events.ERROR, (_e, d) => {
      if (d.fatal) {
        setErr(d.details)
        cb.current?.({ type: 'error', message: d.details })
        if (d.type === Hls.ErrorTypes.NETWORK_ERROR) setTimeout(() => hls.startLoad(), 3000)
        else if (d.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError()
      }
    })
    return () => hls.destroy()
  }, [src])
  return (
    <div className="player">
      <video ref={ref} controls muted={live} playsInline poster={poster} />
      {err && <div className="player-top"><span className="badge bad">Playback problem: {err}. Retrying…</span></div>}
    </div>
  )
}

export function ProgressRing({ value, size = 64 }: { value: number; size?: number }) {
  const r = size / 2 - 6, c = 2 * Math.PI * r
  return (
    <svg width={size} height={size} role="img" aria-label={`${Math.round(value * 100)}% complete`}>
      <circle cx={size / 2} cy={size / 2} r={r} stroke="#e5e8ef" strokeWidth={6} fill="none" />
      <circle cx={size / 2} cy={size / 2} r={r} stroke="var(--primary)" strokeWidth={6} fill="none" strokeLinecap="round"
        strokeDasharray={c} strokeDashoffset={c * (1 - value)} transform={`rotate(-90 ${size / 2} ${size / 2})`} />
      <text x="50%" y="54%" textAnchor="middle" fontSize={14} fontWeight={700} fill="var(--text)">{Math.round(value * 100)}%</text>
    </svg>
  )
}
