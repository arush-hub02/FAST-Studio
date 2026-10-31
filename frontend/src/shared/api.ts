// Thin fetch wrapper: same-origin sign-in cookies + CSRF header; errors become ApiError with a human message.

export class ApiError extends Error {
  status: number
  code: string
  details: unknown
  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

function cookie(name: string) {
  return document.cookie.split('; ').find(c => c.startsWith(name + '='))?.split('=')[1]
}

export async function ensureCsrf() {
  if (!cookie('XSRF-TOKEN')) await fetch('/api/auth/csrf', { credentials: 'same-origin' })
}

// Sign-in tokens last 10 minutes. A 401 renews them once (one shared request for parallel calls) and retries.
const NO_RENEW = ['/api/auth/login', '/api/auth/token', '/api/auth/token/refresh', '/api/auth/logout']
let renewing: Promise<boolean> | null = null
function renewSignIn() {
  renewing ??= ensureCsrf()
    .then(() => fetch('/api/auth/token/refresh', { method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': decodeURIComponent(cookie('XSRF-TOKEN') ?? '') } }))
    .then(r => r.ok, () => false)
    .finally(() => { renewing = null })
  return renewing
}

export async function api<T = unknown>(path: string, opts: { method?: string; body?: unknown; form?: FormData } = {}): Promise<T> {
  const method = opts.method ?? (opts.body !== undefined || opts.form ? 'POST' : 'GET')
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    await ensureCsrf()
    headers['X-XSRF-TOKEN'] = decodeURIComponent(cookie('XSRF-TOKEN') ?? '')
  }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  const send = () => fetch(path, {
    method, headers, credentials: 'same-origin',
    body: opts.form ?? (opts.body !== undefined ? JSON.stringify(opts.body) : undefined),
  })
  let res = await send()
  if (res.status === 401 && !NO_RENEW.includes(path.split('?')[0]) && await renewSignIn()) res = await send()
  if (res.status === 401 && !path.startsWith('/api/auth/login')) window.dispatchEvent(new Event('fs:unauthorized'))
  if (!res.ok) {
    let p: { detail?: string; code?: string; details?: unknown } = {}
    try { p = await res.json() } catch { /* not json */ }
    throw new ApiError(res.status, p.code ?? 'HTTP_' + res.status,
      p.detail ?? (res.status === 403 ? 'You are not allowed to do this.' : 'Request failed (' + res.status + ')'), p.details)
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') return undefined as T
  const type = res.headers.get('content-type') ?? ''
  return (type.includes('json') ? res.json() : res.text()) as Promise<T>
}

// ---------------------------------------------------------------- shared types

export type Membership = { orgId: string; slug: string; name: string; status: string; role: string; permissions: string[]; kind: 'CUSTOMER' | 'PARTNER'; features: string[]; tenantId: string | null; tenantName: string | null }

/** What a customer can share with a partner (read-only). */
export const SCOPES: Record<string, { label: string; meaning: string }> = {
  'channels:read': { label: 'Channel details', meaning: 'Name, description, genre and countries of each shared channel' },
  'epg:read': { label: 'Programme guide', meaning: 'EPG and XMLTV, so the channel appears in their TV guide' },
  'stream:read': { label: 'Stream set-up', meaning: 'How to start the stream for their viewers, with their ad mode and markers' },
  'markers:read': { label: 'Ad-break times', meaning: 'When ad breaks happen, with SCTE-35 markers, so they can sell their own ads' },
  'delivery:read': { label: 'Viewing numbers', meaning: 'Viewers and ads delivered through their platform only' },
}
export type Me = { id: string; name: string; email: string; platformAdmin: boolean; memberships: Membership[]; demoMode: boolean; mustChangePassword: boolean }

export type Asset = {
  id: string; title: string; description: string; kind: string; mediaType: string; genre: string; language: string; series?: string
  season?: number; episode?: number; durationMs: number; cuePoints: string; status: string; statusDetail: string
  mediaKey?: string; color: string; createdAt: string; updatedAt: string
}
export type MediaType = { key: string; label: string; kind: string; enabled: boolean }
export type MediaSettingsView = { types: MediaType[]; enabledTypes: string[]; defaultType: string; premiereTime: string; weekdays: number[]; personalizedAds: boolean; viewerKeySet: boolean; revision: number }
export type ProjectDetails = {
  configuration: import('./configuration/types').Configuration
  schemaVersion: number
  project: { id: string; slug: string; name: string; kind: string; status: string }
  access: { role: string; permissions: string[]; features: string[] }
  media: {
    types: Pick<MediaType, 'key' | 'label' | 'kind'>[]; enabledTypes: string[]; defaultType: string
    premiereTime: string; weekdays: number[]; personalizedAds: boolean; viewerIntegrationConfigured: boolean; revision: number
  }
  connectors: {
    media: { provider: string; state: string; environment: string; credentialsConfigured: boolean; webhookConfigured: boolean; capabilities: string[] }[]
    advertising: { id: string; name: string; kind: string; enabled: boolean; managedBy: string; configured: boolean }[]
    delivery: { origin: string; cdn: string; configured: boolean }
  }
  summary: { assets: number; readyAssets: number; channels: number }
}
export type Policy = {
  id: string; assetId: string; allowedCountries: string; blockedCountries: string; validFrom?: string; validUntil?: string
  adsAllowed: boolean; notes: string
}
export type AssetView = {
  asset: Asset; thumbUrl?: string; previewUrl?: string; rights?: Policy; rightsStatus: string; upcomingAirings: number; channels: string[]
}
export type Channel = {
  id: string; slug: string; name: string; description: string; genre: string; language: string; timezone: string; color: string
  territories: string; status: string; adSecondsPerHour: number; breakSeconds: number; fallbackAssetId?: string; publishedVersionId?: string
}
export type Programme = {
  assetId: string; title: string; description: string; genre: string; kind: string; color: string; mediaKey?: string
  start: string; end: string; adBreaks: number; now: boolean; thumbUrl?: string; series?: string; season?: number; episode?: number
}
export type ChannelView = {
  channel: Channel; now?: Programme; next?: Programme; scheduledUntil?: string; publishedVersion?: number; readiness: string[]; fallbackTitle?: string
}
export type Entry = {
  id: string; versionId: string; kind: 'PROGRAM' | 'AD_BREAK'; assetId?: string; assetOffsetMs: number; startAt: string; endAt: string; ownership?: string
}
export type Version = { id: string; number: number; status: string; revision: number; note: string; createdAt: string; publishedAt?: string }
export type Issue = { severity: 'ERROR' | 'WARNING' | 'INFO'; code: string; message: string; entryId?: string; hint: string; at?: string }
export type Check = { label: string; passed: boolean; detail: string }
export type RightsDecision = { allowed: boolean; summary: string; checks: Check[] }
export type Slot = { campaignId: string; campaign: string; advertiser: string; mediaKey: string; durationMs: number; cpm: number }
export type Decision = { breakId: string; breakMs: number; slots: Slot[]; filledMs: number; excluded: { campaign: string; reason: string }[]; summary: string }
export type Alert = {
  id: string; severity: string; title: string; detail: string; suggestion: string; link: string; status: string; resolution: string; createdAt: string; updatedAt: string
}
export type AuditRow = { id: number; actor: string; action: string; resourceType: string; resourceId?: string; detail: string; createdAt: string }
