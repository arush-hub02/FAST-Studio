import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { NavLink, Navigate, Outlet, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, ensureCsrf, type Me, type Membership, type ProjectDetails } from '../shared/api'
import { Dropdown, ErrorBox, Icon, initials, Loading, useApi } from '../shared/ui'
import Login from '../features/auth/Login'
import Home from '../features/home/Home'
import Library from '../features/library/Library'
import Channels from '../features/channels/Channels'
import Schedule from '../features/programming/Schedule'
import Guide from '../features/home/Guide'
import Watch from '../features/channels/Watch'
import Ads from '../features/ads/Ads'
import RightsPage from '../features/rights/Rights'
import Distribution from '../features/distribution/Distribution'
import Monitoring from '../features/monitoring/Monitoring'
import Analytics from '../features/monitoring/Analytics'
import Team from '../features/team/Team'
import Learn from '../features/home/Learn'
import PartnerOrganizations from '../features/platform/PartnerOrganizations'
import Platform from '../features/platform/TenantAdmin'
import GlobalConfiguration from '../features/platform/GlobalConfiguration'
import Connectors from '../features/platform/Connectors'
import Settings, { PlatformMediaTypes } from '../features/settings/Settings'
import Email from '../features/platform/Email'
import Partners from '../features/distribution/Partners'
import PartnerHome from '../features/partner/PartnerHome'
import PartnerApi from '../features/partner/PartnerApi'
import { ForcedPasswordChange, ResetPassword } from '../features/auth/Password'
import { ThemeProvider, useTheme } from './theme'
import { ProfileSettingsModal } from '../shared/components/ProfileSettingsModal'

// ---------------------------------------------------------------- session

type Session = { me: Me | null; refresh: () => Promise<void>; logout: () => Promise<void> }
const SessionCtx = createContext<Session>({ me: null, refresh: async () => {}, logout: async () => {} })
export const useSession = () => useContext(SessionCtx)
const ProjectCtx = createContext<{ details: ProjectDetails; reload: () => void } | null>(null)

/** Current organization from the URL; permissions come from the server-issued membership (UI hint only — the API enforces). */
export function useOrg(): Membership & { can: (p: string) => boolean; base: string; project: ProjectDetails | null; reloadProject: () => void } {
  const { me } = useSession()
  const bootstrap = useContext(ProjectCtx)
  const { org } = useParams()
  const m = me?.memberships.find(x => x.slug === org)
  if (!m) throw new Error('No membership')
  const project = bootstrap?.details.project.id === m.orgId ? bootstrap.details : null
  const permissions = project?.access.permissions ?? m.permissions
  return { ...m, role: project?.access.role ?? m.role, permissions, features: project?.access.features ?? m.features,
    can: (p: string) => permissions.includes(p), base: '/' + m.slug, project, reloadProject: bootstrap?.reload ?? (() => {}) }
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
    <ThemeProvider>
      <SessionCtx.Provider value={{ me, refresh, logout }}>
        <Routes>
        <Route path="/reset-password" element={<ResetPassword />} />
        {/* a temporary password must be replaced before anything else (the API enforces this too) */}
        {me?.mustChangePassword ? <Route path="*" element={<ForcedPasswordChange />} /> : <>
        <Route path="/login" element={me ? <Navigate to={landing(me)} replace /> : <Login />} />
        <Route element={me ? <Shell /> : <RedirectToLogin />}>
          <Route path="/platform" element={<Platform />} />
          <Route path="/platform/partners" element={<PartnerOrganizations />} />
          <Route path="/platform/configuration" element={<GlobalConfiguration />} />
          <Route path="/platform/email" element={<Email />} />
          <Route path="/platform/connectors" element={<Connectors />} />
          <Route path="/platform/media-types" element={<PlatformMediaTypes />} />
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
            <Route path="settings" element={<Settings />} />
            <Route path="learn" element={<Learn />} />
            <Route path="*" element={<NotFound />} />
          </Route>
          <Route path="/" element={me ? <Navigate to={landing(me)} replace /> : null} />
        </Route>
        </>}
      </Routes>
    </SessionCtx.Provider>
  </ThemeProvider>
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
  return m.kind === 'PARTNER' ? <Outlet /> : <ProjectBootstrap key={m.orgId} membership={m} />
}

/** Login and tenant changes fetch a fresh, allowlisted project context; never persist it in browser storage. */
function ProjectBootstrap({ membership }: { membership: Membership }) {
  const project = useApi<ProjectDetails>(`/api/orgs/${membership.slug}/project-details`)
  if (project.error) return <div className="page"><ErrorBox error={project.error} retry={project.reload} /></div>
  if (project.loading || !project.data || project.data.project.id !== membership.orgId)
    return <div className="page"><Loading /></div>
  return <ProjectCtx.Provider value={{ details: project.data, reload: project.reload }}><Outlet /></ProjectCtx.Provider>
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

type NavItem = {
  to: string
  icon: string
  label: string
  hint: string
  tag?: string
  tagType?: 'live' | 'accent' | 'alert' | 'sim'
}

type NavGroup = {
  group: string
  items: NavItem[]
}

const NAV: NavGroup[] = [
  { group: '', items: [{ to: '', icon: 'home', label: 'Home', hint: 'Launch checklist & status' }] },
  { group: 'Create', items: [
    { to: 'library', icon: 'film', label: 'Library', hint: 'Videos & ad creatives' },
    { to: 'channels', icon: 'tv', label: 'Channels', hint: 'Your TV channels' },
    { to: 'schedule', icon: 'calendar', label: 'Schedule', hint: 'What plays when' },
  ] },
  { group: 'Watch', items: [
    { to: 'watch', icon: 'play', label: 'Watch live', hint: 'Player with real ad breaks', tag: 'Live', tagType: 'live' },
    { to: 'guide', icon: 'grid', label: 'TV guide', hint: 'Programme guide (EPG)' },
  ] },
  { group: 'Earn & deliver', items: [
    { to: 'ads', icon: 'megaphone', label: 'Ads', hint: 'Campaigns & ad decisions', tag: 'SSAI', tagType: 'accent' },
    { to: 'rights', icon: 'shield', label: 'Rights', hint: 'Where content may play' },
    { to: 'distribution', icon: 'send', label: 'Distribution', hint: 'Send to platforms' },
    { to: 'partners', icon: 'link', label: 'Partners', hint: 'Share channels with platforms' },
  ] },
  { group: 'Operate', items: [
    { to: 'monitoring', icon: 'activity', label: 'Monitoring', hint: 'Alerts & activity log' },
    { to: 'analytics', icon: 'chart', label: 'Analytics', hint: 'Viewing & ad results' },
    { to: 'team', icon: 'users', label: 'Team', hint: 'People & permissions' },
    { to: 'settings', icon: 'sliders', label: 'Settings', hint: 'Media, programming & viewer ads' },
  ] },
  { group: 'Help', items: [{ to: 'learn', icon: 'book', label: 'How FAST works', hint: 'SSAI, SGAI, SCTE-35 explained' }] },
]

// partner organizations: they only read what customers share with them
const PARTNER_NAV: NavGroup[] = [
  { group: '', items: [{ to: '', icon: 'link', label: 'Shared with us', hint: 'Channels customers share' }] },
  { group: 'Connect', items: [{ to: 'api', icon: 'key', label: 'API access', hint: 'Let your systems read', tag: 'REST', tagType: 'accent' }] },
  { group: 'Operate', items: [{ to: 'team', icon: 'users', label: 'Team', hint: 'People & permissions' }] },
  { group: 'Help', items: [{ to: 'learn', icon: 'book', label: 'How FAST works', hint: 'SSAI, SGAI, SCTE-35 explained' }] },
]

function Shell() {
  const [profileSettingsOpen, setProfileSettingsOpen] = useState(false)
  const { me } = useSession()
  const { mode, toggleDarkLight } = useTheme()
  const nav = useNavigate()
  const { pathname } = useLocation()
  const slug = pathname.split('/')[1]
  const current = me?.memberships.find(m => m.slug === slug)
  const alerts = useApi<{ id: string }[]>(
    current && current.status === 'ACTIVE' && current.kind !== 'PARTNER' ? `/api/orgs/${current.slug}/alerts` : null,
    [current?.slug],
    30000
  )
  const menu = current?.kind === 'PARTNER' ? PARTNER_NAV : NAV

  // Sidenav collapsible & manual resize state
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem('fs.nav.collapsed') === 'true'
    } catch {
      return false
    }
  })
  const [navWidth, setNavWidth] = useState<number>(() => {
    try {
      const saved = localStorage.getItem('fs.nav.width')
      if (saved) {
        const parsed = parseInt(saved, 10)
        if (parsed >= 230 && parsed <= 420) return parsed
      }
    } catch {}
    return 260
  })
  const [isResizing, setIsResizing] = useState(false)
  const [popover, setPopover] = useState<{
    label: string
    hint?: string
    tag?: string
    tagType?: string
    top: number
  } | null>(null)

  const toggleCollapse = useCallback(() => {
    setPopover(null)
    setCollapsed(prev => {
      const next = !prev
      try {
        localStorage.setItem('fs.nav.collapsed', String(next))
      } catch {}
      return next
    })
  }, [])

  const showItemPopover = useCallback((
    e: React.MouseEvent,
    item: { label: string; hint?: string; tag?: string; tagType?: string }
  ) => {
    if (!collapsed) return
    const rect = e.currentTarget.getBoundingClientRect()
    setPopover({
      label: item.label,
      hint: item.hint,
      tag: item.tag,
      tagType: item.tagType,
      top: rect.top + rect.height / 2
    })
  }, [collapsed])

  const hidePopover = useCallback(() => setPopover(null), [])

  // Drag expansion / resizing
  const startResize = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    setPopover(null)
    setIsResizing(true)

    const handleMouseMove = (moveEvent: MouseEvent) => {
      const newWidth = Math.max(220, Math.min(420, moveEvent.clientX))
      setNavWidth(newWidth)
      setCollapsed(false)
    }

    const handleMouseUp = (upEvent: MouseEvent) => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
      setIsResizing(false)
      const finalWidth = Math.max(220, Math.min(420, upEvent.clientX))
      try {
        localStorage.setItem('fs.nav.width', String(finalWidth))
        localStorage.setItem('fs.nav.collapsed', 'false')
      } catch {}
    }

    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)
  }, [])

  // Keyboard accessibility for resizer
  const handleResizerKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      toggleCollapse()
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault()
      if (collapsed) return
      setNavWidth(w => {
        const next = Math.max(220, w - 20)
        try { localStorage.setItem('fs.nav.width', String(next)) } catch {}
        return next
      })
    } else if (e.key === 'ArrowRight') {
      e.preventDefault()
      if (collapsed) {
        toggleCollapse()
      } else {
        setNavWidth(w => {
          const next = Math.min(420, w + 20)
          try { localStorage.setItem('fs.nav.width', String(next)) } catch {}
          return next
        })
      }
    }
  }, [collapsed, toggleCollapse])

  // Global Ctrl+B or Cmd+B shortcut
  useEffect(() => {
    const handleGlobalKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'b') {
        const tag = (e.target as HTMLElement)?.tagName
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return
        e.preventDefault()
        toggleCollapse()
      }
    }
    window.addEventListener('keydown', handleGlobalKey)
    return () => window.removeEventListener('keydown', handleGlobalKey)
  }, [toggleCollapse])

  if (!me) return null
  const effectiveWidth = collapsed ? 68 : navWidth

  return (
    <div
      className={`shell ${isResizing ? 'is-resizing' : ''}`}
      style={{ '--nav-w': `${effectiveWidth}px` } as React.CSSProperties}
    >
      <nav className={`nav ${collapsed ? 'collapsed' : ''}`} aria-label="Main navigation">
        <div className="nav-ambient" />

        {/* Resizer strip with drag & double-click collapse */}
        <div
          className="nav-resizer"
          role="separator"
          tabIndex={0}
          aria-orientation="vertical"
          aria-label="Resize sidebar. Double-click or press Enter to collapse/expand."
          title="Drag to resize · Double-click to collapse/expand (Ctrl+B)"
          onMouseDown={startResize}
          onDoubleClick={toggleCollapse}
          onKeyDown={handleResizerKeyDown}
        >
          <div className="nav-resizer-pill" />
        </div>

        {/* Brand header with animated logo */}
        <div className="brand">
          <div
            className="brand-link"
            onClick={() => nav(current ? `/${current.slug}` : '/')}
            role="button"
            tabIndex={0}
            onMouseEnter={e => showItemPopover(e, { label: 'FAST Studio', hint: 'Platform Home · Click logo to expand (Ctrl+B)' })}
            onMouseLeave={hidePopover}
          >
            <div
              className="brand-logo"
              onClick={e => {
                if (collapsed) {
                  e.stopPropagation()
                  toggleCollapse()
                }
              }}
            >
              <Icon name="play" size={17} />
            </div>
            <div className="brand-text">
              <div className="brand-title">
                <span className="brand-gradient">FAST</span> Studio
              </div>
              <div className="brand-sub">Platform</div>
            </div>
          </div>
          <button
            type="button"
            className="nav-toggle-btn"
            onClick={toggleCollapse}
            title={collapsed ? undefined : "Collapse sidebar (Ctrl+B)"}
            aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          >
            <Icon name={collapsed ? "chevronRight" : "chevronLeft"} size={16} />
          </button>
        </div>

        {/* Dedicated expand arrow button in collapsed mode */}
        {collapsed && (
          <button
            type="button"
            className="nav-expand-btn"
            onClick={toggleCollapse}
            aria-label="Expand sidebar"
            onMouseEnter={e => showItemPopover(e, { label: 'Expand Sidebar', hint: 'Press Ctrl+B or click to expand' })}
            onMouseLeave={hidePopover}
          >
            <Icon name="chevronRight" size={15} />
          </button>
        )}

        {/* Scrollable Navigation Body */}
        <div className="nav-body" onScroll={hidePopover}>
          {current ? menu.map(g => (
            <div key={g.group}>
              {g.group && <div className="nav-section">{g.group}</div>}
              {g.items.map(i => {
                // Dynamic monitoring alerts tag
                const isMonitoring = i.to === 'monitoring'
                const alertCount = alerts.data && alerts.data.length > 0 ? alerts.data.length : 0
                const itemTag = isMonitoring && alertCount > 0 ? String(alertCount) : i.tag
                const itemTagType = isMonitoring && alertCount > 0 ? 'alert' : i.tagType

                return (
                  <NavLink
                    key={i.to}
                    to={`/${current.slug}${i.to ? '/' + i.to : ''}`}
                    end={!i.to}
                    className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                    onMouseEnter={e => showItemPopover(e, { label: i.label, hint: i.hint, tag: itemTag, tagType: itemTagType })}
                    onMouseLeave={hidePopover}
                  >
                    <Icon name={i.icon} size={17} />

                    {/* Expanded text & tags */}
                    <div className="nav-text-col">
                      <div className="nav-title-row">
                        <span className="nav-label">{i.label}</span>
                        {itemTag && (
                          <span className={`nav-tag ${itemTagType ?? 'accent'}`}>
                            {itemTagType === 'live' && <span className="tag-pulse-dot" />}
                            {itemTag}
                          </span>
                        )}
                      </div>
                      <small>{i.hint}</small>
                    </div>

                    {/* Collapsed dot indicator */}
                    {collapsed && itemTag && (
                      <span className={`nav-collapsed-dot ${itemTagType ?? 'accent'}`} />
                    )}
                  </NavLink>
                )
              })}
            </div>
          )) : null}

          {/* Platform Admin Links */}
          {me.platformAdmin && (
            <div>
              <div className="nav-section">Platform</div>
              <NavLink
                to="/platform"
                end
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                onMouseEnter={e => showItemPopover(e, { label: 'Tenants', hint: 'Customers, projects & subscriptions', tag: 'Admin', tagType: 'accent' })}
                onMouseLeave={hidePopover}
              >
                <Icon name="building" size={17} />
                <div className="nav-text-col">
                  <div className="nav-title-row">
                    <span className="nav-label">Tenants</span>
                    <span className="nav-tag accent">Admin</span>
                  </div>
                  <small>Customers, projects & subscriptions</small>
                </div>
                {collapsed && <span className="nav-collapsed-dot accent" />}
              </NavLink>
              <NavLink to="/platform/partners" className={({isActive})=>`nav-item ${isActive?'active':''}`}><Icon name="users" size={17}/><div className="nav-text-col"><span className="nav-label">Partner organizations</span><small>Sharing & external access</small></div></NavLink>
              <NavLink to="/platform/configuration" className={({isActive})=>`nav-item ${isActive?'active':''}`}><Icon name="sliders" size={17}/><div className="nav-text-col"><span className="nav-label">Global configuration</span><small>Properties, plans, connectors & roles</small></div></NavLink>
              <NavLink
                to="/platform/connectors"
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                onMouseEnter={e => showItemPopover(e, { label: 'Connectors', hint: 'Content, storage & ad servers', tag: 'Admin', tagType: 'accent' })}
                onMouseLeave={hidePopover}
              >
                <Icon name="link" size={17} />
                <div className="nav-text-col">
                  <span className="nav-label">Connectors</span>
                  <small>Content, storage & ad servers</small>
                </div>
              </NavLink>
              <NavLink
                to="/platform/email"
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
                onMouseEnter={e => showItemPopover(e, { label: 'Email', hint: 'Templates & sent emails' })}
                onMouseLeave={hidePopover}
              >
                <Icon name="mail" size={17} />
                <div className="nav-text-col">
                  <span className="nav-label">Email</span>
                  <small>Templates & sent emails</small>
                </div>
              </NavLink>
            </div>
          )}
        </div>

        {/* Nav Footer (Fixed at bottom) */}
        <div
          className="nav-foot"
          onMouseEnter={e => showItemPopover(e, { label: 'System Status', hint: me.demoMode ? 'Prototype build · fictional data' : 'Pilot · live system' })}
          onMouseLeave={hidePopover}
        >
          <div
            className={`nav-foot-dot ${me.demoMode ? 'demo' : ''}`}
          />
          <div className="nav-foot-label">
            {me.demoMode ? 'Prototype · fictional data' : 'Pilot · live system'}
          </div>
        </div>
      </nav>

      <div className="main">
        {me.demoMode && <div className="demo-ribbon"><Icon name="sparkle" size={14} /> Demo environment — fictional companies and development-only accounts. Ad money shown is an estimate from demo playback, never real revenue.</div>}
        <header className="topbar">
          {me.memberships.length > 0 && (
            <Dropdown
              options={me.memberships.map(m => ({
                value: m.slug,
                label: m.kind==='PARTNER'?m.name:`${m.tenantName??m.name} / ${m.name}`,
                description: `${m.kind === 'PARTNER' ? 'Partner organization' : 'Project'} · ${m.role.replace('_', ' ').toLowerCase()}`,
                icon: 'building',
                badge: m.kind === 'PARTNER' ? 'Partner' : undefined,
                badgeType: 'sim',
              }))}
              value={current?.slug}
              onChange={val => nav('/' + val)}
              placeholder="Choose project or partner…"
              searchable={me.memberships.length > 2}
              style={{ minWidth: 240 }}
            />
          )}
          {current && <span className="badge info" title="Your role in this organization">{current.role.replace('_', ' ').toLowerCase()}</span>}
          {current?.kind === 'PARTNER' && <span className="badge sim" title="A partner organization reads what content owners share with it">partner</span>}
          <div className="grow" />
          {current && current.kind !== 'PARTNER' && <NavLink to={`/${current.slug}/monitoring`} className="btn ghost" aria-label="Alerts">
            <Icon name="alert" />{alerts.data && alerts.data.length > 0 && <span className="badge bad">{alerts.data.length}</span>}
          </NavLink>}
          {/* Quick theme mode toggle */}
          <button
            type="button"
            className="btn ghost icon-btn topbar-theme-btn"
            onClick={toggleDarkLight}
            title={mode === 'light' ? 'Switch to Eye-soothing Dark theme' : 'Switch to Daylight theme'}
            aria-label="Toggle theme mode"
          >
            <Icon name={mode === 'light' ? 'moon' : 'sun'} size={17} />
          </button>

          {/* Improved Profile & Studio Settings Trigger */}
          <button
            type="button"
            className="topbar-profile-btn"
            onClick={() => setProfileSettingsOpen(true)}
            title="Profile & Studio Settings"
            aria-label="Profile and Settings"
          >
            <div className="topbar-avatar">
              {initials(me.name)}
            </div>
            <div className="topbar-profile-text">
              <span className="topbar-profile-name">{me.name}</span>
              <span className="topbar-profile-role">{current ? current.role.replace('_', ' ').toLowerCase() : 'Studio Member'}</span>
            </div>
            <Icon name="chevronDown" size={13} />
          </button>

          {profileSettingsOpen && (
            <ProfileSettingsModal
              currentOrgName={current?.name}
              onClose={() => setProfileSettingsOpen(false)}
            />
          )}
        </header>
        <main className="content" id="main"><Outlet /></main>
      </div>

      {/* Floating unclipped popover for collapsed icons */}
      {collapsed && popover && (
        <div
          className="nav-floating-popover"
          style={{ top: `${popover.top}px` }}
          role="tooltip"
          aria-hidden="false"
        >
          <div className="nav-popover-title">
            <span>{popover.label}</span>
            {popover.tag && (
              <span className={`nav-tag ${popover.tagType ?? 'accent'}`}>
                {popover.tagType === 'live' && <span className="tag-pulse-dot" />}
                {popover.tag}
              </span>
            )}
          </div>
          {popover.hint && <div className="nav-popover-hint">{popover.hint}</div>}
        </div>
      )}
    </div>
  )
}

export function Can({ perm, children, fallback }: { perm: string; children: ReactNode; fallback?: ReactNode }) {
  const org = useOrg()
  return <>{org.can(perm) ? children : fallback ?? null}</>
}
