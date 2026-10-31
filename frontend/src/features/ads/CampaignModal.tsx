import { useMemo, useState } from 'react'
import { api, type AssetView } from '../../shared/api'
import { useOrg } from '../../app/App'
import { DateTimePicker } from '../../shared/components/DateTimePicker'
import {
  CountryPicker,
  csv,
  fmtDur,
  Dropdown,
  type DropdownOption,
  Icon,
  InfoTip,
  Modal,
  useAction,
  useApi,
  useToast,
} from '../../shared/ui'
import type { Campaign } from './adTypes'

export function CampaignModal({
  c,
  onClose,
  onDone,
}: {
  c: Campaign | null
  onClose: () => void
  onDone: () => void
}) {
  const org = useOrg()
  const toast = useToast()
  const creatives = useApi<AssetView[]>(`/api/orgs/${org.slug}/assets?kind=AD`)
  const { busy, run } = useAction()

  const day = (d: Date) => d.toISOString().slice(0, 10)
  const [f, setF] = useState({
    name: c?.name ?? '',
    advertiser: c?.advertiser ?? '',
    creativeAssetId: c?.creativeAssetId ?? '',
    territories: c ? csv(c.territories) : ['WW'],
    startsAt: c ? c.startsAt.slice(0, 10) : day(new Date()),
    endsAt: c ? c.endsAt.slice(0, 10) : day(new Date(Date.now() + 30 * 86400000)),
    priority: c?.priority ?? 5,
    cpm: c?.cpm ?? 12,
    audiences: c?.audiences ?? '',
    frequencyCap: c?.frequencyCap ?? 0,
  })

  const set = (k: string, v: unknown) => setF(x => ({ ...x, [k]: v }))
  const creativeId = f.creativeAssetId || creatives.data?.[0]?.asset.id || ''

  // Selected creative metadata
  const selectedCreative = useMemo(() => {
    return creatives.data?.find(a => a.asset.id === creativeId)
  }, [creatives.data, creativeId])

  const creativeOptions = useMemo<DropdownOption[]>(() => {
    return (creatives.data ?? []).map(a => ({
      value: a.asset.id,
      label: a.asset.title,
      description: `${fmtDur(a.asset.durationMs)} · ${a.asset.status.toLowerCase()} encoding`,
      icon: (
        <span
          style={{
            width: 14,
            height: 14,
            borderRadius: 3,
            background: a.asset.color || 'var(--primary)',
            display: 'inline-block',
            flexShrink: 0,
          }}
        />
      ),
      badge: a.asset.status,
      badgeType: a.asset.status === 'READY' ? 'ok' : 'info',
    }))
  }, [creatives.data])

  // Flight duration preset helper
  const setFlightDays = (days: number) => {
    let start: Date
    if (f.startsAt && /^\d{4}-\d{2}-\d{2}$/.test(f.startsAt)) {
      const [y, m, d] = f.startsAt.split('-').map(Number)
      start = new Date(y, m - 1, d)
    } else {
      start = new Date()
    }
    const end = new Date(start.getTime() + days * 86400000)
    set('endsAt', day(end))
  }

  // Computed flight duration in days
  const flightDurationDays = useMemo(() => {
    if (!f.startsAt || !f.endsAt) return 0
    try {
      const [sy, sm, sd] = f.startsAt.split('-').map(Number)
      const [ey, em, ed] = f.endsAt.split('-').map(Number)
      const start = new Date(sy, sm - 1, sd).getTime()
      const end = new Date(ey, em - 1, ed).getTime()
      return Math.max(0, Math.round((end - start) / 86400000))
    } catch {
      return 0
    }
  }, [f.startsAt, f.endsAt])

  // Estimated CPM revenue projection calculator
  const est10k = (10_000 * Number(f.cpm || 0)) / 1000
  const est100k = (100_000 * Number(f.cpm || 0)) / 1000

  const save = () =>
    api(`/api/orgs/${org.slug}/campaigns${c ? '/' + c.id : ''}`, {
      method: c ? 'PUT' : 'POST',
      body: {
        ...f,
        creativeAssetId: creativeId,
        startsAt: f.startsAt + 'T00:00:00Z',
        endsAt: f.endsAt + 'T23:59:59Z',
        priority: +f.priority,
        cpm: +f.cpm,
        audiences: f.audiences.split(',').map(s => s.trim()).filter(Boolean),
        frequencyCap: Number(f.frequencyCap),
      },
    })

  return (
    <Modal
      wide
      title={c ? `Edit Campaign: ${c.name}` : 'New Advertising Campaign Flight'}
      subtitle="Book targeted sponsor campaigns with frame-accurate creative placement, geo-fencing, and priority ranking."
      onClose={onClose}
      footer={
        <div className="between" style={{ width: '100%' }}>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="btn primary"
            disabled={busy || !creativeId || !f.name.trim() || !f.advertiser.trim()}
            onClick={() =>
              run(save, 'Campaign saved').then(r => {
                if (r !== undefined) {
                  toast(`Saved campaign "${f.name}"`, 'ok')
                  onDone()
                }
              })
            }
          >
            {busy ? 'Saving Campaign...' : c ? 'Update Flight' : 'Create Campaign'}
          </button>
        </div>
      }
    >
      <div className="stack" style={{ gap: 16 }}>
        {/* Basic Identity */}
        <div className="grid g2">
          <label className="field">
            <span>Campaign Title</span>
            <input
              value={f.name}
              onChange={e => set('name', e.target.value)}
              placeholder="e.g. Summer Sparkle Launch 2026"
              className="input sm"
            />
          </label>
          <label className="field">
            <span>Advertiser / Sponsor</span>
            <input
              value={f.advertiser}
              onChange={e => set('advertiser', e.target.value)}
              placeholder="e.g. Acme Beverages Inc."
              className="input sm"
            />
          </label>
        </div>

        <div className="grid g2">
          <label className="field"><span>Viewer audiences (optional)</span><input value={f.audiences} onChange={e => set('audiences', e.target.value)} placeholder="SPORTS_FANS, RETURNING_VIEWERS" /><span className="faint small">Match any listed key from signed viewer context. Leave blank for contextual delivery.</span></label>
          <label className="field"><span>Maximum selections per viewer per hour</span><input type="number" min={0} max={100} value={f.frequencyCap} onChange={e => set('frequencyCap', Number(e.target.value))} /><span className="faint small">0 means unlimited. Reservations prevent concurrent over-serving; only confirmed impressions count as delivered. Without identity the cap applies to the session.</span></label>
        </div>
        {/* Video Creative Selector with Visual Preview */}
        <div>
          <div className="field">
            <div className="between" style={{ width: '100%', marginBottom: 4 }}>
              <span className="field-label" style={{ margin: 0, fontWeight: 600, fontSize: 13 }}>
                Video Creative Asset
              </span>
              <span className="hint" style={{ fontSize: 11 }}>
                New video files can be ingested via the Media Library
              </span>
            </div>
            <Dropdown
              options={creativeOptions}
              value={creativeId}
              onChange={val => set('creativeAssetId', val)}
              searchable
              placeholder="Select ingested creative video asset…"
              align="full"
            />
          </div>

          {selectedCreative && (
            <div
              style={{
                marginTop: 8,
                padding: '10px 14px',
                background: 'var(--surface-2)',
                borderRadius: 8,
                border: '1px solid var(--border)',
                display: 'flex',
                alignItems: 'center',
                gap: 12,
              }}
            >
              <div
                style={{
                  width: 50,
                  height: 32,
                  borderRadius: 4,
                  background: selectedCreative.asset.color || '#312e81',
                  display: 'grid',
                  placeItems: 'center',
                  color: '#fff',
                  flexShrink: 0,
                }}
              >
                <Icon name="film" size={16} />
              </div>
              <div className="grow">
                <b style={{ fontSize: 13 }}>{selectedCreative.asset.title}</b>
                <div className="faint small" style={{ fontSize: 11 }}>
                  Duration: {fmtDur(selectedCreative.asset.durationMs)} · Encoding:{' '}
                  <span className="badge ok sm" style={{ padding: '0 4px', fontSize: 9.5 }}>
                    {selectedCreative.asset.status}
                  </span>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Target Territories */}
        <div className="field">
          <span className="row" style={{ gap: 6, marginBottom: 4 }}>
            <span>Target Territories</span>
            <InfoTip text="The ad decision engine only places this campaign for viewers connecting from these territories. Select 'Worldwide' for universal delivery." />
          </span>
          <CountryPicker
            value={f.territories}
            onChange={v => set('territories', v)}
            allowWorldwide
          />
        </div>

        {/* Flight Schedule Window */}
        <div>
          <div className="between" style={{ alignItems: 'center', marginBottom: 8, flexWrap: 'wrap', gap: 8 }}>
            <div className="row" style={{ gap: 6, alignItems: 'center' }}>
              <span className="field-label" style={{ margin: 0, fontSize: 12, fontWeight: 600 }}>
                Flight Schedule Window
              </span>
              {flightDurationDays > 0 && (
                <span className="badge sm ok" style={{ fontSize: 10.5, padding: '1px 6px' }}>
                  {flightDurationDays} {flightDurationDays === 1 ? 'day' : 'days'} duration
                </span>
              )}
            </div>
            <div className="row" style={{ gap: 4, alignItems: 'center' }}>
              <span className="faint small" style={{ fontSize: 10.5 }}>Presets:</span>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(7)}
              >
                7d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(14)}
              >
                14d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(30)}
              >
                30d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(90)}
              >
                90d
              </button>
              <button
                type="button"
                className="btn sm ghost"
                style={{ height: 24, padding: '0 8px', fontSize: 11 }}
                onClick={() => setFlightDays(365)}
              >
                1y
              </button>
            </div>
          </div>

          <div className="grid g2" style={{ gap: 14 }}>
            <DateTimePicker
              label="Flight Start Date"
              value={f.startsAt}
              onChange={val => {
                const s = val ? val.slice(0, 10) : ''
                set('startsAt', s)
                if (f.endsAt && s && f.endsAt < s) {
                  set('endsAt', s)
                }
              }}
              showTime={false}
              allowTimeToggle={false}
              showTimezone={false}
              fixed={false}
              placeholder="Select flight start date..."
            />
            <DateTimePicker
              label="Flight End Date"
              value={f.endsAt}
              onChange={val => {
                const e = val ? val.slice(0, 10) : ''
                set('endsAt', e)
              }}
              showTime={false}
              allowTimeToggle={false}
              showTimezone={false}
              fixed={false}
              placeholder="Select flight end date..."
            />
          </div>
        </div>

        {/* Priority & Monetization */}
        <div className="grid g2" style={{ alignItems: 'flex-start' }}>
          <label className="field">
            <span className="row" style={{ gap: 6 }}>
              <span>Playout Priority (1 - 10)</span>
              <InfoTip text="Higher priority campaigns are placed first in each break. Direct brand sponsor flights usually sit at 8-10. House promos sit at 1-3 to fill remaining time." />
            </span>
            <input
              type="number"
              min={1}
              max={10}
              value={f.priority}
              onChange={e => set('priority', e.target.value)}
              className="input sm"
            />
            <span className="faint small" style={{ fontSize: 10.5 }}>
              {Number(f.priority) >= 8
                ? '⭐ Tier 1: Premium Direct Sponsor'
                : Number(f.priority) >= 5
                ? '⚡ Tier 2: Programmatic Network Fill'
                : '🛡️ Tier 3: House Promo & Fallback'}
            </span>
          </label>

          <label className="field">
            <span className="row" style={{ gap: 6 }}>
              <span>CPM Price ($)</span>
              <InfoTip text="Cost Per Mille: gross price paid per 1,000 views. Used to estimate revenue and prioritize inventory." />
            </span>
            <input
              type="number"
              min={0}
              step={0.5}
              value={f.cpm}
              onChange={e => set('cpm', e.target.value)}
              className="input sm"
            />
            <span className="faint small" style={{ fontSize: 10.5 }}>
              Est. Value: 10k views = <b>${est10k.toFixed(2)}</b> · 100k views ={' '}
              <b>${est100k.toFixed(2)}</b>
            </span>
          </label>
        </div>
      </div>
    </Modal>
  )
}
