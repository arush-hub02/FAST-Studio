import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, type AssetView, type ChannelView } from '../../shared/api'
import { useOrg } from '../../app/App'
import {
  Countries,
  CountryPicker,
  Dropdown,
  fmtDur,
  Icon,
  Loading,
  Modal,
  Status,
  useAction,
  useApi,
} from '../../shared/ui'
import { GenreSelector } from '../../shared/components/GenreSelector'
import { TIMEZONE_OPTIONS, AD_MINUTE_OPTIONS, BREAK_SECOND_OPTIONS, HOURS_OPTIONS, COLORS, REGION_PRESETS } from './channelOptions'

export function ChannelWizard({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const org = useOrg()
  const nav = useNavigate()
  const assets = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets`)
  const { busy, run } = useAction()
  const [step, setStep] = useState(0)
  const [assetSearch, setAssetSearch] = useState('')

  const [f, setF] = useState({
    name: '',
    description: '',
    genre: 'Drama, Action',
    timezone: 'Asia/Kolkata',
    color: COLORS[6],
    territories: ['IN', 'GB', 'US'] as string[],
    assetIds: [] as string[],
    hours: 24,
    adMinutes: 8,
    breakSeconds: 90,
  })

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))

  const programmes = useMemo(() => {
    return assets.data?.filter(a => a.asset.kind !== 'AD' && a.asset.status === 'READY') ?? []
  }, [assets.data])

  const filteredProgrammes = useMemo(() => {
    if (!assetSearch.trim()) return programmes
    const query = assetSearch.toLowerCase().trim()
    return programmes.filter(a =>
      a.asset.title.toLowerCase().includes(query) ||
      (a.asset.genre && a.asset.genre.toLowerCase().includes(query))
    )
  }, [programmes, assetSearch])

  const selectedCount = f.assetIds.length
  const selectedDurationMs = programmes
    .filter(a => f.assetIds.includes(a.asset.id))
    .reduce((acc, a) => acc + (a.asset.durationMs || 0), 0)

  const steps = [
    { label: 'Identity & Look', desc: 'Brand name, genre & badge' },
    { label: 'Regional Reach', desc: 'Territory licensing' },
    { label: 'Programming Lineup', desc: 'Day-1 assets & ad rules' },
    { label: 'Review & Deploy', desc: 'Verify and launch' },
  ]

  const canNext = [
    f.name.trim().length > 1,
    f.territories.length > 0,
    true,
    true,
  ][step]

  async function create() {
    const v = await api<ChannelView>(`/api/orgs/${org.slug}/channels`, {
      body: {
        name: f.name,
        description: f.description,
        genre: f.genre,
        timezone: f.timezone,
        color: f.color,
        territories: f.territories,
        adSecondsPerHour: f.adMinutes * 60,
        breakSeconds: f.breakSeconds,
        assetIds: f.assetIds,
        autofillHours: f.hours,
      },
    })
    return v
  }

  return (
    <Modal
      wide
      title="Create Linear Channel"
      subtitle={`Step ${step + 1} of 4 — ${steps[step].label}`}
      onClose={onClose}
      footer={
        <>
          {step > 0 && (
            <button type="button" className="btn" onClick={() => setStep(s => s - 1)}>
              <Icon name="chevronLeft" size={14} />
              Back
            </button>
          )}
          <div className="grow" />
          {step < 3 ? (
            <button type="button" className="btn primary" disabled={!canNext} onClick={() => setStep(s => s + 1)}>
              Continue
              <Icon name="arrowRight" size={15} />
            </button>
          ) : (
            <button
              type="button"
              className="btn primary"
              disabled={busy}
              onClick={() =>
                run(create, 'Channel created with a draft schedule').then(v => {
                  if (v) {
                    onDone()
                    nav(`${org.base}/schedule?channel=${v.channel.id}`)
                  }
                })
              }
            >
              {busy ? <span className="spinner" /> : (
                <>
                  <Icon name="rocket" size={15} />
                  Deploy Channel & Open Timetable
                </>
              )}
            </button>
          )}
        </>
      }
    >
      {/* Visual Stepper */}
      <div className="wizard-stepper-container">
        <div className="wizard-stepper-track">
          <div className="wizard-stepper-fill" style={{ width: `${(step / 3) * 100}%` }} />
        </div>
        {steps.map((st, idx) => {
          const isDone = idx < step
          const isActive = idx === step
          return (
            <button
              key={st.label}
              type="button"
              className={`wizard-step-node ${isActive ? 'active' : ''} ${isDone ? 'done' : ''}`}
              onClick={() => {
                if (idx < step) setStep(idx)
              }}
            >
              <div className="wizard-step-circle">
                {isDone ? <Icon name="check" size={16} /> : idx + 1}
              </div>
              <span className="wizard-step-label">{st.label}</span>
            </button>
          )
        })}
      </div>

      {/* Step 0: Identity & Look */}
      {step === 0 && (
        <div className="stack" style={{ gap: 16 }}>
          <label className="field">
            Channel Name
            <input
              autoFocus
              value={f.name}
              onChange={e => set('name', e.target.value)}
              placeholder="e.g. Midnight Cinema, Apex Sports, CineVault"
            />
            <span className="hint">
              Channel slug will be: <code>/{f.name ? f.name.toLowerCase().replace(/[^a-z0-9]/g, '-').replace(/-+/g, '-') : 'my-channel'}</code>
            </span>
          </label>

          {/* Genre Multi-Select Checkbox Component (Matching Media Library UI) */}
          <GenreSelector
            value={f.genre}
            onChange={v => set('genre', v)}
          />

          <label className="field">
            Short Description
            <span className="hint">Displayed to audience in electronic programme guides (EPG)</span>
            <textarea
              rows={2}
              value={f.description}
              onChange={e => set('description', e.target.value)}
              placeholder="e.g. Non-stop blockbuster crime thrillers and mystery series around the clock."
            />
          </label>

          <div className="grid g2">
            <div className="field">
              <span>Broadcast Timezone</span>
              <span className="hint">Governs daypart planning and program scheduling grids</span>
              <Dropdown
                options={TIMEZONE_OPTIONS}
                value={f.timezone}
                onChange={v => set('timezone', v)}
                searchable
                align="full"
              />
            </div>

            <div className="field">
              Brand Accent Color
              <span className="hint">Used for broadcast on-air bug, guide banners, and live telemetry</span>
              <div className="color-swatches-grid">
                {COLORS.map(c => (
                  <button
                    key={c}
                    type="button"
                    className={`color-swatch-ring ${f.color === c ? 'selected' : ''}`}
                    aria-label={'Color ' + c}
                    onClick={() => set('color', c)}
                    style={{ background: c }}
                  />
                ))}
              </div>
            </div>
          </div>

          {/* Real-time Broadcast Badge & EPG Preview */}
          <div className="channel-preview-banner">
            <div className="channel-preview-left">
              <div
                className="channel-badge-box"
                style={{
                  background: `linear-gradient(135deg, ${f.color}, ${f.color}cc)`,
                  boxShadow: `0 4px 18px ${f.color}50`,
                }}
              >
                {(f.name || 'FAST').slice(0, 2).toUpperCase()}
              </div>
              <div>
                <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text)' }}>
                  {f.name || 'Untitled Channel'}
                </div>
                <div className="faint" style={{ fontSize: 12 }}>
                  {f.genre || 'General'} · {f.timezone}
                </div>
              </div>
            </div>
            <div className="channel-preview-epg-hint">
              <div style={{ marginBottom: 4 }}><Icon name="tv" size={14} /></div>
              <div>Live EPG Preview</div>
            </div>
          </div>
        </div>
      )}

      {/* Step 1: Regional Reach */}
      {step === 1 && (
        <div className="stack" style={{ gap: 14 }}>
          <div className="callout info">
            <Icon name="globe" size={16} />
            <div>
              <b>Geographical Distribution & Content Licensing:</b> Choose the countries where viewers can stream this channel. If an asset lacks a valid broadcast license in a territory, viewers there will automatically receive a regional standby slate.
            </div>
          </div>

          <div className="field">
            <div className="between" style={{ marginBottom: 6 }}>
              <span>Quick Regional Presets</span>
              <span className="faint" style={{ fontSize: 11 }}>{f.territories.length} countries selected</span>
            </div>
            <div className="regional-presets-bar">
              {REGION_PRESETS.map(preset => (
                <button
                  key={preset.label}
                  type="button"
                  className="regional-preset-chip"
                  onClick={() => set('territories', Array.from(new Set([...f.territories, ...preset.codes])))}
                >
                  <span>{preset.icon}</span>
                  <span>{preset.label}</span>
                </button>
              ))}
              <button
                type="button"
                className="regional-preset-chip"
                onClick={() => set('territories', [])}
                style={{ color: 'var(--bad)' }}
              >
                Clear All
              </button>
            </div>
          </div>

          <div className="field">
            <span>Select Territories</span>
            <CountryPicker value={f.territories} onChange={v => set('territories', v)} />
          </div>
        </div>
      )}

      {/* Step 2: Programming Lineup */}
      {step === 2 && (
        <div className="stack" style={{ gap: 14 }}>
          <p className="muted" style={{ margin: 0 }}>
            Choose ready media assets for your initial broadcast line-up. We will automatically arrange them into a contiguous schedule with timed ad break intervals.
          </p>

          <div className="grid g3">
            <div className="field">
              <span>Fill Playout Duration</span>
              <Dropdown
                options={HOURS_OPTIONS}
                value={String(f.hours)}
                onChange={v => set('hours', Number(v))}
                align="full"
              />
            </div>

            <div className="field">
              <span>Ad Density Per Hour</span>
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

          {/* Programme Media Selector */}
          <div className="field">
            <div className="between" style={{ marginBottom: 6 }}>
              <span>
                Select Videos ({selectedCount} chosen · {fmtDur(selectedDurationMs)} run time)
              </span>
              <div className="row" style={{ gap: 6 }}>
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => set('assetIds', programmes.map(p => p.asset.id))}
                >
                  Select All Ready
                </button>
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => set('assetIds', [])}
                >
                  Deselect All
                </button>
              </div>
            </div>

            <div style={{ marginBottom: 8 }}>
              <input
                type="text"
                placeholder="Search ready programmes by title or genre..."
                value={assetSearch}
                onChange={e => setAssetSearch(e.target.value)}
              />
            </div>

            {!assets.data ? (
              <Loading rows={2} />
            ) : programmes.length === 0 ? (
              <div className="callout warn">
                <Icon name="alert" />
                <div>No ready videos found in the library. You can proceed now and populate programming later.</div>
              </div>
            ) : (
              <div className="wizard-asset-grid">
                {filteredProgrammes.map(a => {
                  const on = f.assetIds.includes(a.asset.id)
                  return (
                    <button
                      key={a.asset.id}
                      type="button"
                      className={`wizard-asset-card ${on ? 'selected' : ''}`}
                      aria-pressed={on}
                      onClick={() =>
                        set('assetIds', on ? f.assetIds.filter(x => x !== a.asset.id) : [...f.assetIds, a.asset.id])
                      }
                    >
                      <div className="thumb" style={{ background: a.asset.color, height: 75 }}>
                        {a.thumbUrl && <img src={a.thumbUrl} alt="" />}
                        {on && (
                          <span className="overlay-badge badge ok">
                            <Icon name="check" size={12} />
                            Selected
                          </span>
                        )}
                        <span className="dur">{fmtDur(a.asset.durationMs)}</span>
                      </div>
                      <div className="between" style={{ padding: '2px 0 0' }}>
                        <b className="ellipsis small" title={a.asset.title}>{a.asset.title}</b>
                        <Status value={a.rightsStatus} />
                      </div>
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Step 3: Review & Deploy */}
      {step === 3 && (
        <div className="stack" style={{ gap: 16 }}>
          <div className="channel-preview-banner">
            <div className="channel-preview-left">
              <div
                className="channel-badge-box"
                style={{
                  background: `linear-gradient(135deg, ${f.color}, ${f.color}cc)`,
                  boxShadow: `0 4px 18px ${f.color}50`,
                }}
              >
                {(f.name || 'FAST').slice(0, 2).toUpperCase()}
              </div>
              <div>
                <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text)' }}>
                  {f.name}
                </div>
                <div className="faint" style={{ fontSize: 12 }}>
                  {f.genre} · {f.timezone} · /{f.name.toLowerCase().replace(/[^a-z0-9]/g, '-')}
                </div>
              </div>
            </div>
            <span className="badge live">
              <span className="dot" />
              Ready for Day-1 Staging
            </span>
          </div>

          <div className="kv">
            <span>Target Territories</span>
            <Countries codes={f.territories} />

            <span>Programmes Selected</span>
            <span>
              {f.assetIds.length
                ? `${f.assetIds.length} programmes (${fmtDur(selectedDurationMs)} content) automatically populated into ${f.hours}-hour loop`
                : 'No assets selected (blank timetable template)'}
            </span>

            <span>SSAI Ad Policy</span>
            <span>
              {f.adMinutes} minutes/hour maximum · {f.breakSeconds}s breaks with automated SCTE-35 markers
            </span>

            <span>Playout Engine</span>
            <span>HLS Dynamic Playout with seamless gap filler standby</span>
          </div>

          <div className="callout info">
            <Icon name="info" />
            <div>
              Clicking <b>Deploy Channel</b> will generate your <b>Day 1 draft timetable</b>. You will be redirected to the interactive schedule planner to review cues, adjust breakpoints, and press <b>Go Live</b>.
            </div>
          </div>
        </div>
      )}
    </Modal>
  )
}
