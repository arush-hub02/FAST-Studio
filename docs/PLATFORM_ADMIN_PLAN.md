# Platform administration program

Requested by the user on 2026-11-01, while media ingestion is paused after Stage 3. Stage numbers here are this program's own (P1–P5), separate from the ingestion stages.

## Decisions (user, 2026-11-01)

- **Screens:** Claude builds the backend, APIs and screens for this program.
- **Features and permissions:** one database catalogue with stable identifiers. Built-in entries stay enforced by the server. Added entries are identifiers that screens check (`org.has`, `org.can`), and that any endpoint can check by key. API routes are **not** remapped to permissions in the database.
- **Connectors:** a marketplace at global level with categories (media library such as Mux, storage such as R2, ad servers). Every connector keeps its keys as typed properties, including Mux, R2 and ad servers.
- **Delivery:** stage by stage. Each stage is tested and documented, and pushed only with approval.

## Stages

| Stage | Scope | Status |
| --- | --- | --- |
| P1. Features and permissions catalogue | `capability` table (V24): global and per-project entries, built-ins seeded and protected. Effective keys in project details. Platform tab "Features & permissions", project Settings section. Custom roles may list global custom permissions | Built locally 2026-11-01 |
| P2. Roles and people | Roles at global level (exists) and project level. User management (invite, assign to organizations, change roles, deactivate). Organization creation and assignment from one place | Planned |
| P3. Connector marketplace | Global connector catalogue with category, status (available, upcoming), visibility by permission, and per-connector property definitions: key, label, type, encrypted, required, external (shown to tenants) or internal. Tenant page with side tabs to connect a connector by entering its keys. Mux credentials (media_connection), R2 and ad servers move onto connector properties, keeping their encrypted values | Planned |
| P4. Media types | Global and project-level types: create, edit, "add from global" to a project, enable per project | Planned |
| P5. Project email templates | Per-project overrides of platform templates, with preview and fallback to the platform version | Planned |

## P1 as built

- Rules for features in a project:
  - Global features are on where the platform switches them on. Built-in ones also need the tenant's plan.
  - A project's own features are on while they're enabled.
- Rules for permissions in a project:
  - People get their role's built-in permissions, plus custom ones.
  - Owners and administrators get every enabled custom permission. A custom role gets the custom permissions it lists. Other built-in roles get none.
  - Custom permissions grant no server access by themselves.
- APIs:
  - Platform: `GET/POST /api/platform/capabilities`, `PUT/DELETE /api/platform/capabilities/{key}`.
  - Project: `GET/POST /api/orgs/{org}/capabilities`, `PUT/DELETE /api/orgs/{org}/capabilities/{key}`.
  - Effective keys are also in `GET /api/orgs/{org}/project-details`, under `access.features` and `access.permissions`.
- Not yet:
  - Project-level roles that use project permissions (P2).
  - Hiding existing screens by the new identifiers. No screen uses an added identifier yet; built-in checks are unchanged.
