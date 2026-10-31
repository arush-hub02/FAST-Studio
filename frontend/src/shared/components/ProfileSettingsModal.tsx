import { useState } from 'react'
import { useSession } from '../../app/App'
import { useTheme, type ThemeMode, type ThemeAccent, type ThemeDensity } from '../../app/theme'
import { Icon, Modal, initials, useToast } from '../ui'
import { api, type Me } from '../api'

type Tab = 'appearance' | 'account' | 'security' | 'shortcuts'

const THEME_OPTIONS: { id: ThemeMode; label: string; desc: string; icon: string; previewBg: string; previewCard: string }[] = [
  {
    id: 'dark',
    label: 'Blackish Blue (Studio Console)',
    desc: 'Deep velvety blackish-blue with obsidian undertones and ultra-sharp contrast',
    icon: 'moon',
    previewBg: '#07090e',
    previewCard: '#0c101a',
  },
  {
    id: 'midnight',
    label: 'Midnight Obsidian',
    desc: 'Ultra-deep pure black canvas, high contrast OLED broadcast monitor look',
    icon: 'sparkle',
    previewBg: '#020408',
    previewCard: '#080c14',
  },
  {
    id: 'nord',
    label: 'Nordic Dusk',
    desc: 'Cool oceanic teal undertones inspired by arctic broadcast studios',
    icon: 'shield',
    previewBg: '#080d16',
    previewCard: '#0d1624',
  },
  {
    id: 'light',
    label: 'Daylight Clean',
    desc: 'Crisp, high-clarity light canvas with subtle gray borders',
    icon: 'sun',
    previewBg: '#f4f6fa',
    previewCard: '#ffffff',
  },
]

const ACCENT_OPTIONS: { id: ThemeAccent; label: string; color: string; glow: string }[] = [
  { id: 'indigo', label: 'Electric Indigo', color: '#6366f1', glow: 'rgba(99, 102, 241, 0.4)' },
  { id: 'emerald', label: 'Broadcast Emerald', color: '#10b981', glow: 'rgba(16, 185, 129, 0.4)' },
  { id: 'cyan', label: 'Cyan Pulse', color: '#06b6d4', glow: 'rgba(6, 182, 212, 0.4)' },
  { id: 'violet', label: 'Royal Violet', color: '#8b5cf6', glow: 'rgba(139, 92, 246, 0.4)' },
  { id: 'amber', label: 'Amber Gold', color: '#f59e0b', glow: 'rgba(245, 158, 11, 0.4)' },
  { id: 'rose', label: 'Sunset Rose', color: '#f43f5e', glow: 'rgba(244, 63, 94, 0.4)' },
]

const DENSITY_OPTIONS: { id: ThemeDensity; label: string; desc: string }[] = [
  { id: 'compact', label: 'Compact', desc: 'Dense information density' },
  { id: 'standard', label: 'Standard', desc: 'Balanced spacing (recommended)' },
  { id: 'spacious', label: 'Spacious', desc: 'Generous padding and larger text' },
]

export function ProfileSettingsModal({ currentOrgName, onClose }: { currentOrgName?: string; onClose: () => void }) {
  const [tab, setTab] = useState<Tab>('appearance')
  const { me, logout, refresh } = useSession()
  const { mode, accent, density, setMode, setAccent, setDensity } = useTheme()
  const toast = useToast()

  // Security password state
  const [currentPw, setCurrentPw] = useState('')
  const [newPw, setNewPw] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  const [pwBusy, setPwBusy] = useState(false)
  const [pwError, setPwError] = useState<string | null>(null)

  async function handlePasswordSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (newPw !== confirmPw) {
      setPwError("New passwords do not match")
      return
    }
    if (newPw.length < 10) {
      setPwError("Password must be at least 10 characters long")
      return
    }
    setPwBusy(true)
    setPwError(null)
    try {
      await api<Me>('/api/auth/password', { body: { currentPassword: currentPw, newPassword: newPw } })
      toast('Password changed successfully', 'ok')
      setCurrentPw('')
      setNewPw('')
      setConfirmPw('')
      refresh()
    } catch (err) {
      setPwError((err as Error).message || 'Failed to update password')
    } finally {
      setPwBusy(false)
    }
  }

  return (
    <Modal
      title="Profile & Preferences"
      subtitle="Theme customization, account details, and studio settings"
      onClose={onClose}
      wide
      footer={
        <div className="between" style={{ width: '100%' }}>
          <button
            type="button"
            className="btn danger ghost"
            onClick={() => {
              onClose()
              logout()
            }}
          >
            <Icon name="logout" size={15} /> Sign out
          </button>
          <button type="button" className="btn primary" onClick={onClose}>
            Done
          </button>
        </div>
      }
    >
      <div className="settings-modal-layout">
        {/* Profile Card Header */}
        <div className="settings-user-card">
          <div className="settings-avatar-wrap">
            <div className="settings-avatar">
              {me ? initials(me.name) : 'U'}
            </div>
            <span className="settings-avatar-online" />
          </div>
          <div className="settings-user-info">
            <div className="settings-user-name-row">
              <h3>{me?.name ?? 'User'}</h3>
              {me?.platformAdmin && (
                <span className="badge info">Platform Admin</span>
              )}
              {me?.demoMode && (
                <span className="badge sim">Demo Environment</span>
              )}
            </div>
            <p className="faint">{me?.email}</p>
            {currentOrgName && (
              <p className="settings-org-pill">
                <Icon name="building" size={13} /> Active Org: <b>{currentOrgName}</b>
              </p>
            )}
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="settings-tabs">
          <button
            type="button"
            className={`settings-tab-btn ${tab === 'appearance' ? 'active' : ''}`}
            onClick={() => setTab('appearance')}
          >
            <Icon name="palette" size={15} /> Appearance & Themes
          </button>
          <button
            type="button"
            className={`settings-tab-btn ${tab === 'account' ? 'active' : ''}`}
            onClick={() => setTab('account')}
          >
            <Icon name="users" size={15} /> Account & Role
          </button>
          <button
            type="button"
            className={`settings-tab-btn ${tab === 'security' ? 'active' : ''}`}
            onClick={() => setTab('security')}
          >
            <Icon name="shield" size={15} /> Security & Password
          </button>
          <button
            type="button"
            className={`settings-tab-btn ${tab === 'shortcuts' ? 'active' : ''}`}
            onClick={() => setTab('shortcuts')}
          >
            <Icon name="sliders" size={15} /> Shortcuts
          </button>
        </div>

        {/* Tab Content */}
        <div className="settings-tab-content">
          {tab === 'appearance' && (
            <div className="stack" style={{ gap: 22 }}>
              {/* Theme Modes */}
              <div>
                <div className="settings-section-title">
                  <Icon name="moon" size={16} /> Theme Mode
                </div>
                <p className="faint" style={{ marginBottom: 12 }}>
                  Choose your preferred workspace brightness and color temperature.
                </p>
                <div className="settings-theme-grid">
                  {THEME_OPTIONS.map(t => (
                    <button
                      key={t.id}
                      type="button"
                      className={`settings-theme-card ${mode === t.id ? 'active' : ''}`}
                      onClick={() => setMode(t.id)}
                    >
                      <div className="settings-theme-preview" style={{ background: t.previewBg }}>
                        <div className="settings-preview-sidebar" />
                        <div className="settings-preview-content">
                          <div className="settings-preview-card" style={{ background: t.previewCard }} />
                          <div className="settings-preview-card sm" style={{ background: t.previewCard }} />
                        </div>
                      </div>
                      <div className="settings-theme-card-body">
                        <div className="between">
                          <span className="settings-theme-label">
                            <Icon name={t.icon} size={14} /> {t.label}
                          </span>
                          {mode === t.id && (
                            <span className="settings-check-dot">
                              <Icon name="check" size={12} />
                            </span>
                          )}
                        </div>
                        <p className="settings-theme-desc">{t.desc}</p>
                      </div>
                    </button>
                  ))}
                </div>
              </div>

              {/* Accent Palette */}
              <div>
                <div className="settings-section-title">
                  <Icon name="palette" size={16} /> Studio Accent Color
                </div>
                <p className="faint" style={{ marginBottom: 12 }}>
                  Applies globally to buttons, active navigation pills, badges, and focus rings.
                </p>
                <div className="settings-accent-row">
                  {ACCENT_OPTIONS.map(a => (
                    <button
                      key={a.id}
                      type="button"
                      className={`settings-accent-btn ${accent === a.id ? 'active' : ''}`}
                      onClick={() => setAccent(a.id)}
                      title={a.label}
                      style={{ '--accent-c': a.color, '--accent-glow': a.glow } as React.CSSProperties}
                    >
                      <span className="settings-accent-circle" style={{ background: a.color }}>
                        {accent === a.id && <Icon name="check" size={13} />}
                      </span>
                      <span className="settings-accent-name">{a.label}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Interface Sizing */}
              <div>
                <div className="settings-section-title">
                  <Icon name="sliders" size={16} /> Layout Sizing & Density
                </div>
                <div className="grid g3" style={{ marginTop: 10 }}>
                  {DENSITY_OPTIONS.map(d => (
                    <button
                      key={d.id}
                      type="button"
                      className={`settings-density-card ${density === d.id ? 'active' : ''}`}
                      onClick={() => setDensity(d.id)}
                    >
                      <div className="between">
                        <b>{d.label}</b>
                        {density === d.id && <Icon name="check" size={14} />}
                      </div>
                      <p className="faint small" style={{ marginTop: 4 }}>{d.desc}</p>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}

          {tab === 'account' && (
            <div className="stack" style={{ gap: 18 }}>
              <div>
                <h4>Organization Memberships</h4>
                <p className="faint">Organizations and roles assigned to your account:</p>
                <div className="stack" style={{ marginTop: 12, gap: 10 }}>
                  {me?.memberships.map(m => (
                    <div key={m.slug} className="card card-pad between" style={{ padding: '12px 16px' }}>
                      <div className="row" style={{ gap: 12 }}>
                        <div className="avatar" style={{ background: 'var(--primary-soft)', color: 'var(--primary)', width: 34, height: 34 }}>
                          {initials(m.name)}
                        </div>
                        <div>
                          <b>{m.name}</b>
                          <div className="faint small">Slug: /{m.slug} · Type: {m.kind}</div>
                        </div>
                      </div>
                      <span className="badge info">{m.role.replace('_', ' ').toLowerCase()}</span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="card card-pad" style={{ background: 'var(--surface-2)' }}>
                <h4>Platform Status</h4>
                <div className="grid g2" style={{ marginTop: 10 }}>
                  <div>
                    <span className="faint small">Account Email:</span>
                    <div><b>{me?.email}</b></div>
                  </div>
                  <div>
                    <span className="faint small">Admin Access:</span>
                    <div><b>{me?.platformAdmin ? 'Yes (Full Platform)' : 'No (Organization Only)'}</b></div>
                  </div>
                  <div>
                    <span className="faint small">Environment:</span>
                    <div><b>{me?.demoMode ? 'Prototype Demonstration' : 'Live Pilot Production'}</b></div>
                  </div>
                  <div>
                    <span className="faint small">Status:</span>
                    <div style={{ color: 'var(--ok)', fontWeight: 600 }}>Active Session</div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {tab === 'security' && (
            <form className="stack" style={{ gap: 14 }} onSubmit={handlePasswordSubmit}>
              <div>
                <h4>Change Your Password</h4>
                <p className="faint">Ensure your account is using a strong password of 10+ characters.</p>
              </div>

              {pwError && (
                <div className="callout bad">
                  <Icon name="alert" />
                  <div>{pwError}</div>
                </div>
              )}

              <label className="field">
                Current Password
                <input
                  type="password"
                  value={currentPw}
                  onChange={e => setCurrentPw(e.target.value)}
                  placeholder="Enter current password"
                  required
                />
              </label>

              <label className="field">
                New Password
                <span className="hint">At least 10 characters</span>
                <input
                  type="password"
                  value={newPw}
                  onChange={e => setNewPw(e.target.value)}
                  placeholder="Enter new strong password"
                  required
                />
              </label>

              <label className="field">
                Confirm New Password
                <input
                  type="password"
                  value={confirmPw}
                  onChange={e => setConfirmPw(e.target.value)}
                  placeholder="Repeat new password"
                  required
                />
              </label>

              <button
                type="submit"
                className="btn primary"
                style={{ alignSelf: 'flex-start', marginTop: 8 }}
                disabled={pwBusy || !currentPw || !newPw || !confirmPw}
              >
                {pwBusy ? <span className="spinner" /> : 'Update Password'}
              </button>
            </form>
          )}

          {tab === 'shortcuts' && (
            <div className="stack" style={{ gap: 12 }}>
              <h4>Productivity Shortcuts</h4>
              <p className="faint">Master these keyboard shortcuts to navigate FAST Studio rapidly.</p>

              <div className="shortcuts-table">
                <div className="shortcut-row">
                  <span>Toggle Left Navigation (Collapse / Expand)</span>
                  <kbd>Ctrl + B</kbd> or <kbd>⌘ + B</kbd>
                </div>
                <div className="shortcut-row">
                  <span>Resize Navigation Width</span>
                  <span>Drag right edge or Arrow Keys on focus</span>
                </div>
                <div className="shortcut-row">
                  <span>Close Modals / Drawers</span>
                  <kbd>Escape</kbd>
                </div>
                <div className="shortcut-row">
                  <span>Quick Theme Toggle (Light / Dark)</span>
                  <span>Click Sun/Moon in Topbar</span>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </Modal>
  )
}
