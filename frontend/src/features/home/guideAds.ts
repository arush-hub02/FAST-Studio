/** Ad-break fill shown in the programme guide: which campaigns would air in each break. */
export type Campaign = {
  id: string
  name: string
  advertiser: string
  status: string
  startsAt: string
  endsAt: string
  territories: string
  priority: number
  cpm: number
  creativeAssetId: string
  creativeTitle: string
  creativeStatus: string
  creativeDurationMs: number
  creativeColor: string
  mediaKey?: string
  impressions: number
  completes: number
  thumbUrl?: string
}

export type AdSpot = {
  campaignId?: string
  title: string
  advertiser: string
  durationSecs: number
  cpm?: number
  color: string
  startMs: number
  endMs: number
  isCurrent: boolean
  elapsedSecs: number
  remainingSecs: number
}

export function resolveBreakAds(
  breakStart: string,
  breakEnd: string,
  totalSecs: number,
  campaigns: Campaign[],
  nowMs: number
) {
  const startMs = new Date(breakStart).getTime()
  const activeCampaigns = campaigns
    .filter(c => c.status === 'ACTIVE' && c.creativeDurationMs > 0)
    .sort((a, b) => b.priority - a.priority)

  let cursorMs = startMs
  let remSecs = totalSecs
  const usedAdvertisers = new Set<string>()
  const spots: AdSpot[] = []

  for (const c of activeCampaigns) {
    const adSecs = Math.round(c.creativeDurationMs / 1000)
    if (adSecs <= remSecs && !usedAdvertisers.has(c.advertiser)) {
      usedAdvertisers.add(c.advertiser)
      const spotEnd = cursorMs + adSecs * 1000
      const isCurrent = nowMs >= cursorMs && nowMs < spotEnd
      const elapsedSecs = isCurrent ? Math.floor((nowMs - cursorMs) / 1000) : 0
      const remainingSecs = isCurrent ? Math.max(0, adSecs - elapsedSecs) : adSecs
      spots.push({
        campaignId: c.id,
        title: c.name,
        advertiser: c.advertiser,
        durationSecs: adSecs,
        cpm: c.cpm,
        color: c.creativeColor || '#f59e0b',
        startMs: cursorMs,
        endMs: spotEnd,
        isCurrent,
        elapsedSecs,
        remainingSecs,
      })
      cursorMs = spotEnd
      remSecs -= adSecs
    }
  }

  // Fallback defaults if campaigns table has no active match
  if (spots.length === 0) {
    const defaultSpots = [
      { title: 'Glow Cola — Summer Sparkle', advertiser: 'Glow Beverages', dur: Math.min(30, remSecs), cpm: 12.0, color: '#f59e0b' },
      { title: 'Zenith X Launch', advertiser: 'Zenith Electronics', dur: Math.min(30, Math.max(0, remSecs - 30)), cpm: 18.0, color: '#06b6d4' },
      { title: 'House: Stay Tuned Promo', advertiser: 'Aurora Media Network', dur: Math.min(30, Math.max(0, remSecs - 60)), cpm: 0, color: '#8b5cf6' },
    ].filter(s => s.dur > 0)

    for (const d of defaultSpots) {
      const spotEnd = cursorMs + d.dur * 1000
      const isCurrent = nowMs >= cursorMs && nowMs < spotEnd
      const elapsedSecs = isCurrent ? Math.floor((nowMs - cursorMs) / 1000) : 0
      const remainingSecs = isCurrent ? Math.max(0, d.dur - elapsedSecs) : d.dur
      spots.push({
        title: d.title,
        advertiser: d.advertiser,
        durationSecs: d.dur,
        cpm: d.cpm,
        color: d.color,
        startMs: cursorMs,
        endMs: spotEnd,
        isCurrent,
        elapsedSecs,
        remainingSecs,
      })
      cursorMs = spotEnd
      remSecs -= d.dur
    }
  }

  if (remSecs > 0) {
    const spotEnd = cursorMs + remSecs * 1000
    const isCurrent = nowMs >= cursorMs && nowMs < spotEnd
    const elapsedSecs = isCurrent ? Math.floor((nowMs - cursorMs) / 1000) : 0
    const remainingSecs = isCurrent ? Math.max(0, remSecs - elapsedSecs) : remSecs
    spots.push({
      title: 'Station Promo & Standby Slate',
      advertiser: 'Network Broadcast Operations',
      durationSecs: remSecs,
      cpm: 0,
      color: '#6366f1',
      startMs: cursorMs,
      endMs: spotEnd,
      isCurrent,
      elapsedSecs,
      remainingSecs,
    })
  }

  const currentSpot = spots.find(s => s.isCurrent) ?? null
  const currentSpotIndex = spots.findIndex(s => s.isCurrent)
  const filledSecs = totalSecs - (remSecs > 0 ? remSecs : 0)
  const fillRatePercent = Math.round((filledSecs / totalSecs) * 100)

  return {
    spots,
    currentSpot,
    currentSpotIndex,
    fillRatePercent,
    filledSecs,
  }
}
