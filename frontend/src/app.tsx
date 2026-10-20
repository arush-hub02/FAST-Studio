import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { NavLink, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, ensureCsrf, type Me, type Membership } from './api'
import { Icon, initials, useApi } from './ui'
import Login from './pages/Login'
import Home from './pages/Home'
import Library from './pages/Library'
import Channels from './pages/Channels'
import Schedule from './pages/Schedule'
import Guide from './pages/Guide'
import Watch from './pages/Watch'
import Ads from './pages/Ads'
import RightsPage from './pages/Rights'
import Distribution from './pages/Distribution'
import Monitoring from './pages/Monitoring'
import Analytics from './pages/Analytics'
import Team from './pages/Team'
import Learn from './pages/Learn'
import Platform from './pages/Platform'
import Email from './pages/Email'
import Partners from './pages/Partners'
import PartnerHome from './pages/PartnerHome'
import PartnerApi from './pages/PartnerApi'
import { ChangePasswordModal, ForcedPasswordChange, ResetPassword } from './pages/Password'

// ---------------------------------------------------------------- session

type Session = { me: Me | null; refresh: () => Promise<void>; logout: () => Promise<void> }
const SessionCtx = createContext<Session>({ me: null, refresh: async () => {}, logout: async () => {} })
export const useSession = () => useContext(SessionCtx)

/** Current organization from the URL; permissions come from the server-issued membership (UI hint only — the API enforces). */
export function useOrg(): Membership & { can: (p: string) => boolean; base: string } {
  const { me } = useSession()
  const { org } = useParams()
  const m = me?.memberships.find(x => x.slug === org)
  if (!m) throw new Error('No membership')
  return { ...m, can: (p: string) => m.permissions.includes(p), base: '/' + m.slug }
}

export default function App() {
  const [me, setMe] = useState<Me | null | undefined>(undefined)
  const refresh = useCallback(async () => {
    try { setMe(await api<Me>('/api/auth/me')) } catch { setMe(null) }
  }, [])
  const logout = useCallback(async () => {
    try { await api('/api/auth/logout', { method: 'POST' }) } catch { /* session already gone */ }
    setMe(null)
  }, [])
  useEffect(() => { ensureCsrf().then(refresh) }, [refresh])
  useEffect(() => {
    const h = () => setMe(null)
    window.addEventListener('fs:unauthorized', h)
    return () => window.removeEventListener('fs:unauthorized', h)
  }, [])
  if (me === undefined) return <div className="empty" style={{ height: '100%', justifyContent: 'center' }}><span className="spinner" /></div>
  return (
    <SessionCtx.Provider value={{ me, refresh, logout }}>
      <Routes>
        <Route path="/reset-password" element={<ResetPassword />} />
        {/* a temporary password must be replaced before anything else (the API enforces this too) */}
        {me?.mustChangePassword ? <Route path="*" element={<ForcedPasswordChange />} /> : <>
        <Route path="/login" element={me ? <Navigate to={landing(me)} replace /> : <Login />} />
        <Route element={me ? <Shell /> : <RedirectToLogin />}>
          <Route path="/platform" element={<Platform />} />
          <Route path="/platform/email" element={<Email />} />
          <Route path="/welcome" element={<NoAccess />} />
          <Route path="/:org" element={<OrgGuard />}>
            <Route index element={<OrgHome />} />
            <Route path="partners" element={<Partners />} />
            <Route path="api" element={<PartnerApi />} />
            <Route path="library" element={<Library />} />
            <Route path="channels" element={<Channels />} />
            <Route path="schedule" element={<Schedule />} />
            <Route path="guide" element={<Guide />} />
            <Route path="watch" element={<Watch />} />
            <Route path="ads" element={<Ads />} />
            <Route path="rights" element={<RightsPage />} />
            <Route path="distribution" element={<Distribution />} />
            <Route path="monitoring" element={<Monitoring />} />
            <Route path="analytics" element={<Analytics />} />
            <Route path="team" element={<Team />} />
            <Route path="learn" element={<Learn />} />
            <Route path="*" element={<NotFound />} />
          </Route>
          <Route path="/" element={me ? <Navigate to={landing(me)} replace /> : null} />
        </Route>
        </>}
      </Routes>
    </SessionCtx.Provider>
  )
}

function landing(me: Me) {
  const saved = localStorage.getItem('fs.org')
  const m = me.memberships.find(x => x.slug === saved && x.status === 'ACTIVE') ?? me.memberships.find(x => x.status === 'ACTIVE')
  return m ? '/' + m.slug : me.platformAdmin ? '/platform' : '/welcome'
}

function RedirectToLogin() {
  const loc = useLocation()
  return <Navigate to="/login" replace state={{ from: loc.pathname }} />
}

let activeOrg: string | null = null

function OrgGuard() {
  const { me } = useSession()
  const { org } = useParams()
  const m = me?.memberships.find(x => x.slug === org)
  useEffect(() => {
    if (!m) return
    localStorage.setItem('fs.org', m.slug)
    // the sign-in token's active organization follows the one on screen (ignored when the server uses sessions)
    if (activeOrg !== m.orgId) { activeOrg = m.orgId; api('/api/auth/token/switch', { body: { org: m.orgId } }).catch(() => {}) }
  }, [m])
  if (!m) return <NoAccess />
  if (m.status !== 'ACTIVE') return (
    <div className="page"><div className="callout bad"><Icon name="alert" /><div><b>{m.name} is suspended.</b> The platform administrator has paused access. Your data is kept safe.</div></div></div>
  )
  return <Outlet />
}

/** Customers start at Home; partner organizations at what is shared with them. */
function OrgHome() {
  return useOrg().kind === 'PARTNER' ? <PartnerHome /> : <Home />
}

function NoAccess() {
  return <div className="page"><div className="empty"><div className="empty-icon"><Icon name="shield" /></div><h3>Nothing here for you</h3>
    <p>This organization doesn't exist or you're not a member. Each company only ever sees its own data.</p></div></div>
}

function NotFound() {
  return <div className="page"><div className="empty"><h3>Page not found</h3><p>Use the menu on the left.</p></div></div>
}

// ---------------------------------------------------------------- shell

const NAV: { group: string; items: { to: string; icon: string; label: string; hint: string }[] }[] = [
  { group: '', items: [{ to: '', icon: 'home', label: 'Home', hint: 'Launch checklist & status' }] },
  { group: 'Create', items: [
    { to: 'library', icon: 'film', label: 'Library', hint: 'Videos & ad creatives' },
    { to: 'channels', icon: 'tv', label: 'Channels', hint: 'Your TV channels' },
    { to: 'schedule', icon: 'calendar', label: 'Schedule', hint: 'What plays when' },
  ] },
  { group: 'Watch', items: [
    { to: 'watch', icon: 'play', label: 'Watch live', hint: 'Player with real ad breaks' },
    { to: 'guide', icon: 'grid', label: 'TV guide', hint: 'Programme guide (EPG)' },
  ] },
  { group: 'Earn & deliver', items: [
    { to: 'ads', icon: 'megaphone', label: 'Ads', hint: 'Campaigns & ad decisions' },
    { to: 'rights', icon: 'shield', label: 'Rights', hint: 'Where content may play' },
    { to: 'distribution', icon: 'send', label: 'Distribution', hint: 'Send to platforms' },
    { to: 'partners', icon: 'link', label: 'Partners', hint: 'Share channels with platforms' },
  ] },
  { group: 'Operate', items: [
    { to: 'monitoring', icon: 'activity', label: 'Monitoring', hint: 'Alerts & activity log' },
    { to: 'analytics', icon: 'chart', label: 'Analytics', hint: 'Viewing & ad results' },
    { to: 'team', icon: 'users', label: 'Team', hint: 'People & permissions' },
  ] },
  { group: 'Help', items: [{ to: 'learn', icon: 'book', label: 'How FAST works', hint: 'SSAI, SGAI, SCTE-35 explained' }] },
]

// partner organizations: they only read what customers share with them
const PARTNER_NAV: typeof NAV = [
  { group: '', items: [{ to: '', icon: 'link', label: 'Shared with us', hint: 'Channels customers share' }] },
  { group: 'Connect', items: [{ to: 'api', icon: 'key', label: 'API access', hint: 'Let your systems read' }] },
  { group: 'Operate', items: [{ to: 'team', icon: 'users', label: 'Team', hint: 'People & permissions' }] },
  { group: 'Help', items: [{ to: 'learn', icon: 'book', label: 'How FAST works', hint: 'SSAI, SGAI, SCTE-35 explained' }] },
]

function Shell() {
  const [changingPassword, setChangingPassword] = useState(false)
  const { me, logout } = useSession()
  const nav = useNavigate()
  const { pathname } = useLocation()
  const slug = pathname.split('/')[1]
  const current = me?.memberships.find(m => m.slug === slug)
  const alerts = useApi<{ id: string }[]>(current && current.status === 'ACTIVE' && current.kind !== 'PARTNER' ? `/api/orgs/${current.slug}/alerts` : null, [current?.slug], 30000)
  const menu = current?.kind === 'PARTNER' ? PARTNER_NAV : NAV
  if (!me) return null
  return (
    <div className="shell">
      <nav className="nav" aria-label="Main">
        <div className="brand"><div className="brand-logo"><Icon name="play" size={18} /></div>FAST Studio</div>
        {current ? menu.map(g => (
          <div key={g.group}>
            {g.group && <div className="nav-section">{g.group}</div>}
            {g.items.map(i => (
              <NavLink key={i.to} to={`/${current.slug}${i.to ? '/' + i.to : ''}`} end={!i.to}>
                <Icon name={i.icon} /><span><span>{i.label}</span><small>{i.hint}</small></span>
              </NavLink>
            ))}
          </div>
        )) : null}
        {me.platformAdmin && <>
          <div className="nav-section">Platform</div>
          <NavLink to="/platform" end><Icon name="building" /><span><span>Organizations</span><small>Customer tenants</small></span></NavLink>
          <NavLink to="/platform/email"><Icon name="mail" /><span><span>Email</span><small>Templates & sent emails</small></span></NavLink>
        </>}
        <div className="nav-foot">{me.demoMode ? 'Prototype build · data is fictional' : 'Pilot · your real data'}</div>
      </nav>
      <div className="main">
        {me.demoMode && <div className="demo-ribbon"><Icon name="sparkle" size={14} /> Demo environment — fictional companies and development-only accounts. Ad money shown is an estimate from demo playback, never real revenue.</div>}
        <header className="topbar">
          {me.memberships.length > 0 && (
            <label className="row" style={{ gap: 8 }}>
              <Icon name="building" />
              <select aria-label="Organization" value={current?.slug ?? ''} style={{ width: 240, fontWeight: 600 }}
                onChange={e => nav('/' + e.target.value)}>
                {!current && <option value="">Choose organization…</option>}
                {me.memberships.map(m => <option key={m.slug} value={m.slug}>{m.name}</option>)}
              </select>
            </label>
          )}
          {current && <span className="badge info" title="Your role in this organization">{current.role.replace('_', ' ').toLowerCase()}</span>}
          {current?.kind === 'PARTNER' && <span className="badge sim" title="A partner organization reads what content owners share with it">partner</span>}
          <div className="grow" />
          {current && current.kind !== 'PARTNER' && <NavLink to={`/${current.slug}/monitoring`} className="btn ghost" aria-label="Alerts">
            <Icon name="alert" />{alerts.data && alerts.data.length > 0 && <span className="badge bad">{alerts.data.length}</span>}
          </NavLink>}
          {current && <NavLink to={`/${current.slug}/learn`} className="btn ghost"><Icon name="book" />Help</NavLink>}
          <div className="row">
            <button className="avatar" style={{ background: '#6366f1', border: 0, cursor: 'pointer' }} onClick={() => setChangingPassword(true)} title="Change password" aria-label="Change password">{initials(me.name)}</button>
            <div className="small" style={{ lineHeight: 1.2 }}><b>{me.name}</b><div className="faint">{me.email}</div></div>
          </div>
          <button className="btn ghost icon-btn" onClick={logout} aria-label="Sign out" title="Sign out"><Icon name="logout" /></button>
          {changingPassword && <ChangePasswordModal onClose={() => setChangingPassword(false)} />}
        </header>
        <main className="content" id="main"><Outlet /></main>
      </div>
    </div>
  )
}

export function Can({ perm, children, fallback }: { perm: string; children: ReactNode; fallback?: ReactNode }) {
  const org = useOrg()
  return <>{org.can(perm) ? children : fallback ?? null}</>
}
