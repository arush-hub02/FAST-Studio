# Frontend folder architecture

Status: **adopted 2026-10-31** as part of restructure step 1 (see [PROGRESS.md](../PROGRESS.md)). Mirrors the backend audiences and features in [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md), so a feature has the same name on both sides.

## Target tree

```text
frontend/src/
├── main.tsx                         # entry: mounts <App/>
├── app/                             # application shell — nothing feature-specific
│   ├── App.tsx                      #   routes, session/org context, navigation shell (was app.tsx)
│   ├── theme.tsx                    #   light/dark theme
│   └── styles.css                   #   global styles
├── shared/                          # reused by several features; no feature imports another feature
│   ├── api.ts                       #   HTTP client, CSRF, shared API types (was api.ts)
│   ├── ui.tsx                       #   UI kit: Modal, PageHead, Loading, useApi, useAction … (was ui.tsx)
│   └── components/                  #   DateTimePicker, ProfileSettingsModal
└── features/
    ├── auth/                        # Login, Password (reset / change)
    ├── home/                        # Home, Guide, Learn
    ├── library/                     # Library (media assets)
    ├── rights/                      # Rights (licences, windows)
    ├── channels/                    # Channels, Watch (player preview)
    ├── programming/                 # Schedule, SmartProgramming
    ├── ads/                         # Ads, AdSources
    ├── distribution/                # Distribution, Partners (sharing with partner organizations)
    ├── monitoring/                  # Monitoring, Analytics
    ├── team/                        # Team
    ├── settings/                    # Settings + configuration editor (project / tenant scope)
    ├── partner/                     # PartnerHome, PartnerApi (partner portal)
    └── platform/                    # platform admin: TenantAdmin, PartnerOrganizations, GlobalConfiguration,
                                     #   Connectors, MuxConnector, Email
```

Each feature folder holds its pages (`XxxPage.tsx` naming is not required; existing names are kept) and the components only it uses. A component used by two features moves to `shared/components/`.

## Rules

- `app/` may import `features/` and `shared/`; `features/` import `shared/` only; `shared/` imports nothing above it.
- API paths live next to the feature that calls them; shared types that several features use stay in `shared/api.ts`.
- Routes and URLs do not change in a restructure.
- Verify with `npx tsc --noEmit` and `npm run build` after each move.
