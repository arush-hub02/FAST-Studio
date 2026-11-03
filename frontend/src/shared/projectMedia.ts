// The project's media settings from project details (already in memory and in the browser copy), instead of a separate
// /settings request on every screen that only needs the type lists. reload() refreshes project details.
import { useOrg } from '../app/App'
import type { MediaSettingsView } from './api'
import { useApi } from './ui'

export function useProjectMedia(): { data: MediaSettingsView | null; error: ReturnType<typeof useApi>['error']; reload: () => void } {
  const org = useOrg()
  const m = org.project?.media
  const fallback = useApi<MediaSettingsView>(m ? null : `/api/orgs/${org.slug}/settings`)   // only when details are missing
  if (!m) return { data: fallback.data, error: fallback.error, reload: fallback.reload }
  return {
    data: {
      types: m.types.map(t => ({ ...t, enabled: true })), enabledTypes: m.enabledTypes, defaultType: m.defaultType,
      premiereTime: m.premiereTime, weekdays: m.weekdays, personalizedAds: m.personalizedAds,
      viewerKeySet: m.viewerIntegrationConfigured, revision: m.revision, ownTypes: [],
    },
    error: null,
    reload: org.reloadProject,
  }
}
