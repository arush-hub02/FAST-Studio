import { type Channel, type Entry, type Issue, type Version } from '../../shared/api'
import {
  type DropdownOption,
} from '../../shared/ui'

/** Types, zoom levels and option lists shared by the schedule editor and its panels and modals. */
export type AssetRef = { id: string; title: string; kind: string; color: string; mediaKey?: string; durationMs: number; status: string }

export type View = {
  channel: Channel
  draft: Version
  draftEntries: Entry[]
  published?: Version
  publishedEntries: Entry[]
  issues: Issue[]
  versions: Version[]
  assets: Record<string, AssetRef>
  unpublishedChanges: boolean
  now: string
  from: string
  to: string
}

export const ZOOM = { '1h': 1100, '3h': 360, '6h': 190, '24h': 48 } as const

export const AUTOFILL_HOURS_OPTIONS: DropdownOption[] = [
  { value: '3', label: '3 Hours', icon: 'clock', description: 'Quick quarter-day block' },
  { value: '6', label: '6 Hours', icon: 'clock', description: 'Daypart programming block' },
  { value: '12', label: '12 Hours', icon: 'clock', description: 'Half-day schedule queue' },
  { value: '24', label: '24 Hours (Full Day)', icon: 'clock', description: 'Complete 24-hour linear timetable', badge: 'Recommended', badgeType: 'ok' },
  { value: '48', label: '48 Hours (2 Days)', icon: 'clock', description: 'Multi-day broadcast buffer' },
  { value: '72', label: '72 Hours (3 Days)', icon: 'clock', description: 'Extended 3-day continuous queue' },
]

export const BREAK_SECONDS_OPTIONS: DropdownOption[] = [
  { value: '30', label: '30 seconds (1 spot)', icon: 'clock', description: 'Single sponsor commercial slot' },
  { value: '60', label: '60 seconds (2 spots)', icon: 'clock', description: 'Standard 2x 30s ad break' },
  { value: '90', label: '90 seconds (3 spots)', icon: 'clock', description: 'Standard 3x 30s ad break', badge: 'Recommended', badgeType: 'ok' },
  { value: '120', label: '120 seconds (2.0 min)', icon: 'clock', description: 'Extended 4-spot commercial break' },
  { value: '180', label: '180 seconds (3.0 min)', icon: 'clock', description: 'Long-form commercial block' },
]

export function readyProgrammes(v: View) {
  return Object.values(v.assets).filter(a => a.kind !== 'AD' && a.kind !== 'SLATE' && a.status === 'READY')
}

// ---------------------------------------------------------------- Upgraded Modals
