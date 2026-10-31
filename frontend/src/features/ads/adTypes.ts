import type { Decision } from '../../shared/api'

/** Shapes shared by the Ads screen, its campaign editor and the break simulator. */
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
  audiences: string
  frequencyCap: number
}

export type Simulation = {
  breakStart: string
  breakSeconds: number
  ownership: string
  programme: string
  decision: Decision
}
