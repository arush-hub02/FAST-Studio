import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
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
  moon: <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41" /></>,
  palette: <path d="M13.5 2H12C6.5 2 2 6.5 2 12s4.5 10 10 10c.8 0 1.5-.7 1.5-1.5 0-.4-.1-.7-.4-1-.3-.3-.4-.6-.4-1 0-.8.7-1.5 1.5-1.5H16c3.3 0 6-2.7 6-6 0-5.5-3.8-9-8.5-9z" />,
  settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" /></>,
  sliders: <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6" />,
  checkCircle: <><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" /><path d="m9 11 3 3L22 4" /></>,
  bell: <><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" /><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" /></>,
  chevronDown: <path d="m6 9 6 6 6-6" />,
  shieldCheck: <><path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6z" /><path d="m9 12 2 2 4-4" /></>,
  list: <><line x1="8" y1="6" x2="21" y2="6" /><line x1="8" y1="12" x2="21" y2="12" /><line x1="8" y1="18" x2="21" y2="18" /><line x1="3" y1="6" x2="3.01" y2="6" /><line x1="3" y1="12" x2="3.01" y2="12" /><line x1="3" y1="18" x2="3.01" y2="18" /></>,
  pause: <><rect x="6" y="4" width="4" height="16" rx="1" /><rect x="14" y="4" width="4" height="16" rx="1" /></>,
  dollarSign: <><line x1="12" y1="2" x2="12" y2="22" /><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" /></>,
  target: <><circle cx="12" cy="12" r="10" /><circle cx="12" cy="12" r="6" /><circle cx="12" cy="12" r="2" /></>,
  copy: <><rect width="14" height="14" x="8" y="8" rx="2" ry="2" /><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2" /></>,
  filter: <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3" />,
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
export function fmtTimeSec(iso?: string | null, tz?: string) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: tz }).format(new Date(iso))
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

export function Modal({
  title,
  subtitle,
  onClose,
  children,
  footer,
  wide,
  size,
  className = '',
}: {
  title: string
  subtitle?: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
  size?: 'sm' | 'md' | 'lg' | 'wide'
  className?: string
}) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  const resolvedSizeClass = size ? ` modal-${size}` : (wide ? ' wide' : '')
  return (
    <div className="overlay" onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div className={`modal${resolvedSizeClass}${className ? ' ' + className : ''}`} role="dialog" aria-modal="true" aria-label={title}>
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
    <Modal title={title} onClose={onClose} size="sm" footer={<>
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

export function ProgressRing({
  value,
  size = 52,
  strokeWidth = 4,
  color,
}: {
  value: number
  size?: number
  strokeWidth?: number
  color?: string
}) {
  const clamped = Math.min(Math.max(value, 0), 1)
  const percent = Math.round(clamped * 100)
  const r = (size - strokeWidth) / 2
  const c = 2 * Math.PI * r
  const strokeColor = color ?? (percent === 100 ? 'var(--ok)' : 'var(--primary)')
  const fontSize = Math.round(size * 0.23)

  return (
    <div className="progress-ring-wrap" style={{ width: size, height: size, position: 'relative', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        role="img"
        aria-label={`${percent}% complete`}
        style={{ overflow: 'visible', display: 'block' }}
      >
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          stroke="var(--surface-3)"
          strokeWidth={strokeWidth}
          fill="none"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          stroke={strokeColor}
          strokeWidth={strokeWidth}
          fill="none"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - clamped)}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
          style={{ transition: 'stroke-dashoffset 0.4s ease' }}
        />
        <text
          x={size / 2}
          y={size / 2}
          textAnchor="middle"
          dominantBaseline="central"
          fontSize={fontSize}
          fontWeight={700}
          fill="var(--text)"
          fontFamily="var(--font)"
          style={{ fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.02em' }}
        >
          {percent}%
        </text>
      </svg>
    </div>
  )
}

// ---------------------------------------------------------------- dropdown component

export type DropdownOption = {
  value: string
  label: string
  description?: string
  icon?: string | ReactNode
  badge?: string
  badgeType?: 'live' | 'ok' | 'warn' | 'accent' | 'info' | 'default' | 'sim'
  disabled?: boolean
}

export function Dropdown({
  options,
  value,
  onChange,
  placeholder = 'Select option…',
  searchable = false,
  multiple = false,
  disabled = false,
  clearable = false,
  className = '',
  style,
  triggerPrefix,
  align = 'left',
  emptyText = 'No options found',
}: {
  options: DropdownOption[]
  value?: string | string[]
  onChange: (val: any) => void
  placeholder?: string
  searchable?: boolean
  multiple?: boolean
  disabled?: boolean
  clearable?: boolean
  className?: string
  style?: React.CSSProperties
  triggerPrefix?: ReactNode
  align?: 'left' | 'right' | 'full'
  emptyText?: string
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const rootRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)

  const selectedValues = useMemo<string[]>(() => {
    if (value === undefined || value === null) return []
    return Array.isArray(value) ? value : [value]
  }, [value])

  const selectedOptions = useMemo(
    () => options.filter(o => selectedValues.includes(o.value)),
    [options, selectedValues]
  )

  const hasAnyIcon = useMemo(() => options.some(o => !!o.icon), [options])

  const filtered = useMemo(() => {
    if (!query.trim()) return options
    const q = query.toLowerCase()
    return options.filter(
      o =>
        o.label.toLowerCase().includes(q) ||
        (o.description && o.description.toLowerCase().includes(q))
    )
  }, [options, query])

  const handleSelect = (opt: DropdownOption) => {
    if (opt.disabled) return
    if (multiple) {
      const exists = selectedValues.includes(opt.value)
      const next = exists
        ? selectedValues.filter(v => v !== opt.value)
        : [...selectedValues, opt.value]
      onChange(next)
    } else {
      onChange(opt.value)
      setOpen(false)
    }
  }

  const handleSelectAll = () => {
    onChange(options.filter(o => !o.disabled).map(o => o.value))
  }

  const handleClearAll = () => {
    onChange([])
  }

  const menuRef = useRef<HTMLDivElement>(null)
  const [dropUp, setDropUp] = useState(false)
  const [menuCoords, setMenuCoords] = useState<{
    top: number
    bottom: number
    left: number
    width: number
    maxHeight: number
  } | null>(null)

  const updateMenuPosition = useCallback(() => {
    if (!rootRef.current) return
    const rect = rootRef.current.getBoundingClientRect()
    const viewportHeight = window.innerHeight || document.documentElement.clientHeight
    const viewportWidth = window.innerWidth || document.documentElement.clientWidth
    const spaceBelow = viewportHeight - rect.bottom
    const spaceAbove = rect.top

    const shouldDropUp = spaceBelow < 260 && spaceAbove > spaceBelow
    setDropUp(shouldDropUp)

    const menuWidth = Math.max(rect.width, 220)
    let left = rect.left
    if (align === 'right') {
      left = rect.right - menuWidth
    }
    left = Math.max(8, Math.min(left, viewportWidth - menuWidth - 8))

    const maxHeight = shouldDropUp
      ? Math.max(160, rect.top - 12)
      : Math.max(160, viewportHeight - rect.bottom - 12)

    setMenuCoords({
      top: rect.bottom + 5,
      bottom: viewportHeight - rect.top + 5,
      left,
      width: align === 'full' ? rect.width : Math.min(menuWidth, viewportWidth - 16),
      maxHeight,
    })
  }, [align])

  const handleToggle = () => {
    if (disabled) return
    if (!open) {
      updateMenuPosition()
    }
    setOpen(o => !o)
  }

  useEffect(() => {
    if (open) {
      updateMenuPosition()
      window.addEventListener('resize', updateMenuPosition)
      window.addEventListener('scroll', updateMenuPosition, true)
      return () => {
        window.removeEventListener('resize', updateMenuPosition)
        window.removeEventListener('scroll', updateMenuPosition, true)
      }
    }
  }, [open, updateMenuPosition])

  // Close on outside click and Escape
  useEffect(() => {
    if (!open) return
    const handleClick = (e: MouseEvent) => {
      const target = e.target as Node
      if (
        rootRef.current &&
        !rootRef.current.contains(target) &&
        menuRef.current &&
        !menuRef.current.contains(target)
      ) {
        setOpen(false)
      }
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('mousedown', handleClick)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  return (
    <div
      ref={rootRef}
      className={`custom-dropdown align-${align} ${dropUp ? 'drop-up' : ''} ${open ? 'is-open' : ''} ${disabled ? 'is-disabled' : ''} ${className}`}
      style={style}
    >
      {/* Trigger Button */}
      <button
        type="button"
        className="dropdown-trigger"
        onClick={handleToggle}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span className="dropdown-trigger-content">
          {triggerPrefix && (selectedOptions.length === 0 || multiple || !selectedOptions[0]?.icon) && (
            <span className="dropdown-trigger-prefix">{triggerPrefix}</span>
          )}
          {selectedOptions.length === 0 ? (
            <span className="dropdown-placeholder">{placeholder}</span>
          ) : multiple ? (
            <span className="dropdown-summary">
              {selectedOptions.length === 1 ? (
                <span className="dropdown-selected-label">
                  {selectedOptions[0].icon && (
                    <span className="dropdown-trigger-icon">
                      {typeof selectedOptions[0].icon === 'string' ? (
                        <Icon name={selectedOptions[0].icon} size={14} />
                      ) : (
                        selectedOptions[0].icon
                      )}
                    </span>
                  )}
                  <span>{selectedOptions[0].label}</span>
                </span>
              ) : (
                <span className="dropdown-summary-badge">
                  {selectedOptions.length} selected
                </span>
              )}
            </span>
          ) : (
            <span className="dropdown-selected-label">
              {selectedOptions[0].icon && (
                <span className="dropdown-trigger-icon">
                  {typeof selectedOptions[0].icon === 'string' ? (
                    <Icon name={selectedOptions[0].icon} size={14} />
                  ) : (
                    selectedOptions[0].icon
                  )}
                </span>
              )}
              <span>{selectedOptions[0].label}</span>
            </span>
          )}
        </span>

        <span className="dropdown-trigger-actions">
          {clearable && selectedValues.length > 0 && !disabled && (
            <span
              role="button"
              tabIndex={0}
              className="dropdown-trigger-clear"
              onClick={e => {
                e.stopPropagation()
                onChange(multiple ? [] : '')
              }}
              aria-label="Clear selection"
            >
              <Icon name="x" size={12} />
            </span>
          )}
          <span className={`dropdown-chevron ${open ? 'is-open' : ''}`}>
            <Icon name="chevronDown" size={13} />
          </span>
        </span>
      </button>

      {/* Popover Menu - Portaled to document.body so modals/tables with overflow never clip it */}
      {open &&
        menuCoords &&
        createPortal(
          <div
            ref={menuRef}
            className={`dropdown-menu align-${align} ${dropUp ? 'drop-up' : ''}`}
            role="listbox"
            style={{
              position: 'fixed',
              top: dropUp ? 'auto' : `${menuCoords.top}px`,
              bottom: dropUp ? `${menuCoords.bottom}px` : 'auto',
              left: `${menuCoords.left}px`,
              right: 'auto',
              width: `${menuCoords.width}px`,
              minWidth: `${menuCoords.width}px`,
              maxWidth: `min(420px, calc(100vw - 16px))`,
              maxHeight: `${menuCoords.maxHeight}px`,
              zIndex: 99999,
            }}
          >
            {searchable && (
              <div className="dropdown-search-wrap">
                <Icon name="search" size={14} />
                <input
                  ref={searchInputRef}
                  type="text"
                  className="dropdown-search-input"
                  placeholder="Search…"
                  value={query}
                  onChange={e => setQuery(e.target.value)}
                  onKeyDown={e => e.stopPropagation()}
                />
                {query && (
                  <button
                    type="button"
                    className="dropdown-search-clear"
                    onClick={() => setQuery('')}
                    aria-label="Clear search"
                  >
                    <Icon name="x" size={12} />
                  </button>
                )}
              </div>
            )}

            {multiple && (
              <div className="dropdown-multi-toolbar">
                <span className="dropdown-multi-count">
                  {selectedValues.length} of {options.length} selected
                </span>
                <div className="dropdown-multi-actions">
                  <button
                    type="button"
                    className="dropdown-toolbar-btn"
                    onClick={handleSelectAll}
                  >
                    Select all
                  </button>
                  {selectedValues.length > 0 && (
                    <button
                      type="button"
                      className="dropdown-toolbar-btn"
                      onClick={handleClearAll}
                    >
                      Clear
                    </button>
                  )}
                </div>
              </div>
            )}

            <div className="dropdown-options-list">
              {filtered.length === 0 ? (
                <div className="dropdown-empty">{emptyText}</div>
              ) : (
                filtered.map(opt => {
                  const isSelected = selectedValues.includes(opt.value)
                  const hasDesc = !!opt.description
                  return (
                    <button
                      key={opt.value}
                      type="button"
                      role="option"
                      aria-selected={isSelected}
                      disabled={opt.disabled}
                      className={`dropdown-item ${isSelected ? 'is-selected' : ''} ${
                        opt.disabled ? 'is-disabled' : ''
                      } ${hasDesc ? 'has-desc' : ''}`}
                      onClick={() => handleSelect(opt)}
                    >
                      {/* Checkbox for multiple selection */}
                      {multiple && (
                        <span className={`dropdown-checkbox ${isSelected ? 'checked' : ''}`}>
                          {isSelected && <Icon name="check" size={11} />}
                        </span>
                      )}

                      {/* Left Icon with alignment spacer */}
                      {hasAnyIcon && (
                        <span className="dropdown-item-icon">
                          {opt.icon ? (
                            typeof opt.icon === 'string' ? (
                              <Icon name={opt.icon} size={15} />
                            ) : (
                              opt.icon
                            )
                          ) : (
                            <span className="dropdown-icon-spacer" />
                          )}
                        </span>
                      )}

                      {/* Label & Description Column */}
                      <div className="dropdown-item-text">
                        <span className="dropdown-item-label">{opt.label}</span>
                        {opt.description && (
                          <span className="dropdown-item-desc">{opt.description}</span>
                        )}
                      </div>

                      {/* Right Badge or Single Checkmark */}
                      <div className="dropdown-item-aside">
                        {opt.badge && (
                          <span className={`badge ${opt.badgeType ?? 'info'} sm`}>
                            {opt.badge}
                          </span>
                        )}
                        {!multiple && isSelected && (
                          <span className="dropdown-item-check">
                            <Icon name="check" size={14} />
                          </span>
                        )}
                      </div>
                    </button>
                  )
                })
              )}
            </div>
          </div>,
          document.body
        )}
    </div>
  )
}

// Re-export DateTimePicker & Timezone utilities for universal usage across the project
export {
  DateTimePicker,
  formatDateTimeTz,
  getTimezoneBadge,
  COMMON_TIMEZONES,
  type DateTimePickerProps,
  type TimezoneOption,
} from './components/DateTimePicker'

/** Human wording for an audit action key (falls back to the key). */
const VERBS: Record<string, string> = {
  'auth.login': 'signed in', 'asset.create': 'added a video', 'asset.upload': 'uploaded a file', 'asset.update': 'edited a video', 'asset.archive': 'archived a video',
  'rights.update': 'changed a licence', 'channel.create': 'created a channel', 'channel.update': 'edited a channel', 'channel.live': 'took a channel live',
  'channel.stopped': 'stopped a channel', 'channel.archived': 'archived a channel', 'schedule.publish': 'published a schedule', 'schedule.autofill': 'auto-filled a schedule',
  'schedule.add': 'added to a schedule', 'schedule.remove': 'removed from a schedule', 'schedule.close_gaps': 'closed schedule gaps', 'schedule.clear': 'cleared a schedule',
  'schedule.restore': 'restored an old schedule', 'campaign.create': 'created a campaign', 'campaign.update': 'edited a campaign', 'campaign.paused': 'paused a campaign',
  'campaign.active': 'resumed a campaign', 'campaign.ended': 'ended a campaign', 'distribution.publish': 'sent a channel to a destination',
  'destination.create': 'added a destination', 'alert.acknowledge': 'acknowledged an alert', 'alert.resolve': 'resolved an alert', 'member.invite': 'invited a member',
  'member.role': 'changed a role', 'member.remove': 'removed a member',
}
export function describeAction(action: string) { return VERBS[action] ?? action }

/** Weekday names in ISO order (index 0 = Monday = 1). */
export const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
