// Project details kept in the browser after sign-in, so every screen and every new tab starts at once from the last
// known copy; it is refreshed in the background (on open, on returning to the tab, after changes). The response holds
// names, switches and permissions, never credentials (the API only says whether one is stored). Keyed by person and
// project, and cleared at sign-out, so nobody else on the browser sees it. The API still enforces every permission.
import type { ProjectDetails } from './api'

const PREFIX = 'fs.project.'
const key = (user: string, org: string) => `${PREFIX}${user}.${org}`

export type Cached = { details: ProjectDetails; at: number }

export function readProject(user: string, org: string): Cached | null {
  try {
    const raw = localStorage.getItem(key(user, org))
    const c = raw ? (JSON.parse(raw) as Cached) : null
    return c && c.details?.project?.id === org ? c : null
  } catch { return null }
}

export function writeProject(user: string, org: string, details: ProjectDetails) {
  try { localStorage.setItem(key(user, org), JSON.stringify({ details, at: Date.now() })) } catch { /* storage full or blocked: keep working without it */ }
}

export function forgetProject(user: string, org: string) {
  try { localStorage.removeItem(key(user, org)) } catch { /* blocked */ }
}

/** Forgets every cached project (sign-out, expired sign-in). */
export function clearProjects() {
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i)
      if (k?.startsWith(PREFIX)) localStorage.removeItem(k)
    }
  } catch { /* blocked */ }
}
