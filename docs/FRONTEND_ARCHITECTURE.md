# Frontend folder architecture

Status: **applied 2026-10-31** (restructure step 1). Mirrors the backend audiences and features in [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md), so a feature has the same name on both sides. Routes and URLs did not change.

## Tree

```text
frontend/src/
├── main.tsx                          # entry: mounts <App/> inside the router and toast provider
├── app/                              # application shell
│   ├── App.tsx                       #   routes, session/org context (useSession, useOrg), navigation shell
│   ├── theme.tsx                     #   light/dark theme
│   └── styles.css                    #   global styles
├── shared/                           # used by more than one feature
│   ├── api.ts                        #   HTTP client, CSRF, shared API types
│   ├── ui.tsx                        #   UI kit: Modal, PageHead, Loading, ErrorBox, useApi, useAction, DAYS, describeAction …
│   ├── components/                   #   AdSources, DateTimePicker, GenreSelector, ProfileSettingsModal, ResetLinkModal
│   └── configuration/                #   ConfigurationEditor + types (tenant/project settings and platform admin)
└── features/
    ├── auth/                         # Login, Password (reset / change)
    ├── home/                         # Home, Guide (+ guideAds: ad-break fill), Learn
    ├── library/                      # Library, AssetDrawer, AddVideoModal, mediaKind
    ├── rights/                       # Rights
    ├── channels/                     # Channels, ChannelWizard, ChannelSettings, channelOptions, Watch
    ├── programming/                  # Schedule, ScheduleTimeline, PreflightPanel, ProgramModal, ReplaceModal,
    │                                 #   ScheduleModals (auto-fill, publish), scheduleShared, SmartProgramming
    ├── ads/                          # Ads, CampaignModal, AdSimulator, adTypes
    ├── distribution/                 # Distribution, Partners (sharing with partner organizations)
    ├── monitoring/                   # Monitoring, Analytics
    ├── team/                         # Team
    ├── settings/                     # Settings (media types, programming defaults, configuration tabs)
    ├── partner/                      # PartnerHome, PartnerApi (partner portal)
    └── platform/                     # platform admin: TenantAdmin, PartnerOrganizations, GlobalConfiguration,
                                      #   Connectors, MediaTypes, MuxConnector, Email
```

| Old | New |
| --- | --- |
| `app.tsx`, `theme.tsx`, `styles.css` | `app/` (`app.tsx` → `App.tsx`) |
| `api.ts`, `ui.tsx` | `shared/` |
| `components/*` | `shared/components/` (AdSources, DateTimePicker, GenreSelector, ProfileSettingsModal, ResetLinkModal) or the owning feature (MuxConnector → platform, SmartProgramming → programming) |
| `features/configuration/*` | `shared/configuration/` |
| `pages/X.tsx` | `features/<feature>/X.tsx` (`Platform.tsx` → `platform/PartnerOrganizations.tsx`) |

## Rules

- `app/` may import `features/` and `shared/`. `features/` import `shared/` and the session/org hooks from `app/App` (`useSession`, `useOrg`). `shared/` imports only `shared/` (and those hooks).
- A component used by two features moves to `shared/components/`.
- A screen file keeps the page component. Its dialogs, drawers, wizards and large panels go into sibling files in the same feature folder (`PascalCase.tsx`); types and option lists they share go into a small lowercase module (`scheduleShared.ts`, `channelOptions.ts`). Sibling files never import the screen file.
- Verify with `npx tsc --noEmit` and `npm run build` after each change; check pages in the browser.

## Cross-feature imports

None: no file under `features/` imports from another feature (cleaned up in step 2, 2026-10-31). Check with:

```bash
grep -rnE "from '\.\./[a-z]+/" frontend/src/features
```
