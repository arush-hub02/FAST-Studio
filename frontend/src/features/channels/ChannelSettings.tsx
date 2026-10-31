import { useMemo, useState } from 'react'
import { api, type AssetView, type ChannelView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  CountryPicker,
  csv,
  Dropdown,
  type DropdownOption,
  fmtDur,
  Icon,
  Modal,
  useAction,
  useApi,
} from '../../shared/ui'
import { GenreSelector } from '../../shared/components/GenreSelector'
import { TIMEZONE_OPTIONS, AD_MINUTE_OPTIONS, BREAK_SECOND_OPTIONS, COLORS, REGION_PRESETS } from './channelOptions'

export function ChannelSettings({ view, onClose, onDone }: { view: ChannelView; onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const c = view.channel
  const assets = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`)
  const { busy, run } = useAction()

  const [f, setF] = useState({
    name: c.name,
    description: c.description,
    genre: c.genre,
    timezone: c.timezone,
    color: c.color || COLORS[6],
    territories: csv(c.territories),
    adMinutes: Math.round(c.adSecondsPerHour / 60),
    breakSeconds: c.breakSeconds,
    fallback: c.fallbackAssetId ?? '',
  })

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))

  // Ensure current channel timezone is included in options
  const timezoneOptions: DropdownOption[] = useMemo(() => {
    const list = [...TIMEZONE_OPTIONS]
    if (c.timezone && !list.some(t => t.value === c.timezone)) {
      list.unshift({
        value: c.timezone,
        label: c.timezone,
        icon: 'clock',
        description: 'Channel designated timezone',
      })
    }
    return list
  }, [c.timezone])

  // Ready video fallback options with rich thumbnails & metadata
  const fallbackOptions: DropdownOption[] = useMemo(() => {
    const readyAssets = assets.data?.filter(a => a.asset.kind !== 'AD' && a.asset.status === 'READY') ?? []
    return [
      {
        value: '',
        label: 'None (Default Standby Slate)',
        icon: 'shield',
        description: 'Display standard broadcast off-air test card slate',
        badge: 'Slate',
        badgeType: 'default',
      },
      ...readyAssets.map(a => ({
        value: a.asset.id,
        label: a.asset.title,
        icon: 'film',
        description: `${a.asset.genre || 'General'} · ${fmtDur(a.asset.durationMs)}`,
        badge: fmtDur(a.asset.durationMs),
        badgeType: 'info' as const,
      })),
    ]
  }, [assets.data])

  return (
    <Modal
      wide
      title={`${c.name} — Settings`}
      subtitle={`Channel ID: ${c.id} · Broadcast slug: /${c.slug}`}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy}
            onClick={() =>
              run(
                () =>
                  api(`/api/orgs/${org.slug}/channels/${c.id}`, {
                    method: 'PATCH',
                    body: {
                      name: f.name,
                      description: f.description,
                      genre: f.genre,
                      timezone: f.timezone,
                      color: f.color,
                      territories: f.territories,
                      adSecondsPerHour: f.adMinutes * 60,
                      breakSeconds: f.breakSeconds,
                      fallbackAssetId: f.fallback || undefined,
                      clearFallback: !f.fallback,
                    },
                  }),
                'Settings updated successfully'
              ).then(r => r !== undefined && onDone())
            }
          >
            {busy ? <span className="spinner" /> : 'Save Changes'}
          </button>
        </>
      }
    >
      {/* Branding & Info */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="palette" size={15} />
          Branding & Identity
        </div>

        <label className="field">
          Channel Name
          <input value={f.name} onChange={e => set('name', e.target.value)} />
        </label>

        {/* Genre Multi-Select Checkbox Component (Matching Media Library UI) */}
        <GenreSelector
          value={f.genre}
          onChange={v => set('genre', v)}
        />

        <label className="field">
          Description
          <textarea
            rows={2}
            value={f.description}
            onChange={e => set('description', e.target.value)}
          />
        </label>

        <div className="grid g2">
          <div className="field">
            <span>Broadcast Timezone</span>
            <span className="hint">Governs daypart planning and program scheduling grids</span>
            <Dropdown
              options={timezoneOptions}
              value={f.timezone}
              onChange={v => set('timezone', v)}
              searchable
              align="full"
            />
          </div>

          <div className="field">
            Brand Accent Color
            <div className="color-swatches-grid">
              {COLORS.map(color => (
                <button
                  key={color}
                  type="button"
                  className={`color-swatch-ring ${f.color === color ? 'selected' : ''}`}
                  onClick={() => set('color', color)}
                  style={{ background: color }}
                />
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Distribution & Geo Reach */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="globe" size={15} />
          Distribution & Geo Reach
        </div>

        <div className="regional-presets-bar">
          {REGION_PRESETS.map(p => (
            <button
              key={p.label}
              type="button"
              className="regional-preset-chip"
              onClick={() => set('territories', Array.from(new Set([...f.territories, ...p.codes])))}
            >
              <span>{p.icon}</span>
              <span>{p.label}</span>
            </button>
          ))}
        </div>

        <CountryPicker value={f.territories} onChange={v => set('territories', v)} />
      </div>

      {/* SSAI Ad Monetization Rules */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="megaphone" size={15} />
          SSAI Monetization & Ad Rules
        </div>

        <div className="grid g2">
          <div className="field">
            <span>Max Ads Per Hour</span>
            <Dropdown
              options={AD_MINUTE_OPTIONS}
              value={String(f.adMinutes)}
              onChange={v => set('adMinutes', Number(v))}
              align="full"
            />
          </div>

          <div className="field">
            <span>Ad Break Length</span>
            <Dropdown
              options={BREAK_SECOND_OPTIONS}
              value={String(f.breakSeconds)}
              onChange={v => set('breakSeconds', Number(v))}
              align="full"
            />
          </div>
        </div>
      </div>

      {/* Playout Failover & Fallback */}
      <div className="settings-section-card">
        <div className="settings-section-title">
          <Icon name="shield" size={15} />
          Failover & Standby Loop
        </div>

        <div className="field">
          <span>Fallback Video Playout</span>
          <span className="hint">
            Plays during timetable gaps or stream buffer failovers instead of a blank standby screen.
          </span>
          <Dropdown
            options={fallbackOptions}
            value={f.fallback}
            onChange={v => set('fallback', v)}
            searchable
            align="full"
            placeholder="Select fallback video loop..."
          />
        </div>
      </div>
    </Modal>
  )
}
