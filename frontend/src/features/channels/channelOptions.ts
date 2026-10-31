import type { DropdownOption } from '../../shared/ui'

/** Option lists shared by the channel wizard and channel settings. */
export const TIMEZONE_OPTIONS: DropdownOption[] = [
  { value: 'Asia/Kolkata', label: 'Asia/Kolkata (IST +05:30)', icon: 'clock', description: 'Indian Standard Time' },
  { value: 'Europe/London', label: 'Europe/London (GMT/BST)', icon: 'clock', description: 'Greenwich / British Summer Time' },
  { value: 'America/New_York', label: 'America/New_York (EST/EDT -05:00)', icon: 'clock', description: 'US Eastern Time' },
  { value: 'America/Los_Angeles', label: 'America/Los_Angeles (PST/PDT -08:00)', icon: 'clock', description: 'US Pacific Time' },
  { value: 'Europe/Berlin', label: 'Europe/Berlin (CET/CEST +01:00)', icon: 'clock', description: 'Central European Time' },
  { value: 'Asia/Singapore', label: 'Asia/Singapore (SGT +08:00)', icon: 'clock', description: 'Singapore Standard Time' },
  { value: 'Australia/Sydney', label: 'Australia/Sydney (AEST/AEDT +10:00)', icon: 'clock', description: 'Australian Eastern Time' },
  { value: 'UTC', label: 'UTC (Coordinated Universal Time)', icon: 'clock', description: 'Coordinated Universal Time' },
]

export const AD_MINUTE_OPTIONS: DropdownOption[] = [
  { value: '4', label: '4 min / hour', icon: 'megaphone', description: 'Light ad load (high viewer retention)', badge: 'Light', badgeType: 'ok' },
  { value: '6', label: '6 min / hour', icon: 'megaphone', description: 'Moderate commercial break density', badge: 'Balanced', badgeType: 'info' },
  { value: '8', label: '8 min / hour', icon: 'megaphone', description: 'Standard linear FAST ad density', badge: 'Standard', badgeType: 'info' },
  { value: '10', label: '10 min / hour', icon: 'megaphone', description: 'High commercial inventory monetization', badge: 'High', badgeType: 'warn' },
  { value: '12', label: '12 min / hour', icon: 'megaphone', description: 'Maximum broadcast ad saturation', badge: 'Max', badgeType: 'live' },
]

export const BREAK_SECOND_OPTIONS: DropdownOption[] = [
  { value: '60', label: '60 seconds (1.0 min break)', icon: 'clock', description: '2x 30s sponsor spot pods' },
  { value: '90', label: '90 seconds (1.5 min break)', icon: 'clock', description: 'Standard 3x 30s ad break pods', badge: 'Recommended', badgeType: 'ok' },
  { value: '120', label: '120 seconds (2.0 min break)', icon: 'clock', description: '4x 30s commercial slot pod' },
  { value: '180', label: '180 seconds (3.0 min break)', icon: 'clock', description: 'Extended broadcast commercial block' },
]

export const HOURS_OPTIONS: DropdownOption[] = [
  { value: '6', label: '6 Hours Playout', icon: 'calendar', description: 'Quarter-day loop pattern' },
  { value: '12', label: '12 Hours Playout', icon: 'calendar', description: 'Half-day daypart programming' },
  { value: '24', label: '24 Hours (Full Day)', icon: 'calendar', description: 'Full 24/7 continuous linear timetable', badge: 'Full Day', badgeType: 'ok' },
  { value: '48', label: '48 Hours (Weekend)', icon: 'calendar', description: 'Multi-day continuous broadcast queue' },
]

export const COLORS = ['#ef4444', '#f97316', '#eab308', '#22c55e', '#059669', '#0ea5e9', '#6366f1', '#a855f7', '#ec4899', '#64748b']

export const REGION_PRESETS: { label: string; icon: string; codes: string[] }[] = [
  { label: 'Worldwide', icon: '🌐', codes: ['IN', 'GB', 'US', 'CA', 'IE', 'AU', 'DE', 'FR', 'ES', 'BR', 'MX', 'JP', 'SG', 'AE', 'ZA'] },
  { label: 'North America', icon: '🇺🇸', codes: ['US', 'CA', 'MX'] },
  { label: 'Europe Tier-1', icon: '🇪🇺', codes: ['GB', 'IE', 'DE', 'FR', 'ES'] },
  { label: 'Asia-Pacific', icon: '🌏', codes: ['IN', 'SG', 'JP', 'AU'] },
]
