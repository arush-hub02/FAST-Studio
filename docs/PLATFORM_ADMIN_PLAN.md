# Platform administration program

Requested by the user on 2026-11-01, while media ingestion is paused after Stage 3. Stage numbers here are this program's own (P1–P5), separate from the ingestion stages.

## Decisions (user, 2026-11-01)

- **Screens:** Claude builds the backend, APIs and screens for this program.
- **Features and permissions:** one database catalogue with stable identifiers. Built-in entries stay enforced by the server. Added entries are identifiers that screens check (`org.has`, `org.can`), and that any endpoint can check by key. API routes are **not** remapped to permissions in the database.
- **Connectors:** a marketplace at global level with categories (media library such as Mux, storage such as R2, ad servers). Every connector keeps its keys as typed properties, including Mux, R2 and ad servers.
- **Delivery:** stage by stage. Each stage is tested and documented, and pushed only with approval.

## Redesign of features, permissions and roles (user, 2026-11-02)

The user refined P1 after it was pushed, taking the v2 CMS ops portal (`D:\v2\Enveu CMS 2\…\ops-portal\ops-core`: module-features, roles, role-permissions side panel, project feature-settings) as the reference. This replaces P1's single shared catalogue.

**Model**

```
Module ─ Feature group ─ Feature ─ uses → Permission(s)
                                          ↑
Role (global or project) ── grants ───────┘   (picked group by group, feature by feature)
Project ── links → Features (switched on for the project)
```

- **Permission** (global only): identifier, name, description, system (built-in, enforced by the server) or custom. Permissions are what roles grant.
- **Module** (global): top level, e.g. Media, Channels, Advertising.
- **Feature group** (global): belongs to a module, published or unpublished.
- **Feature** (global only): identifier, name, description, group, the permissions it uses, and flags for default (linked to new projects), hidden and beta. When a feature is off, its screens are hidden for everyone.
- **Project features:** a project links global features. It can search by module or group, filter by status and name, and "add default features". Projects no longer create their own features or permissions; P1 project-made entries are migrated into the global catalogue.
- **Roles:** global roles and project roles. The role editor goes module (tab), then feature group (tab), then feature, then its permissions switched on or off.
- **Frontend checks:** `org.has(feature)` hides things when the feature is off; `org.has(feature) && org.can(permission)` shows something only when both apply.

**Screens**

- Global configuration: separate list, create and edit for Modules, Feature groups, Features and Permissions.
- Global configuration and project: role management with the group-by-group permission editor.
- Project Settings: link and unlink features with search by module and group.

**Status (2026-11-02):** Antigravity's Global configuration redesign was checked and committed (cdefc69). This redesign is built on top of it, locally: V25 (module, feature_group, feature_permission, feature flags on capability, access_role.org_id with row-level security; P1 project-made entries moved to global and kept switched on), CatalogueServiceV1 and CatalogueControllerV1 (platform), ProjectFeatureControllerV1 and ProjectRoleControllerV1 (project), and the screens in frontend/src/shared/configuration/Catalogue.tsx (Global configuration tab, Settings → Project features, Team → Roles, grouped permission picker also in the platform role editor). P1's /capabilities endpoints are removed. Projects no longer create features or permissions. Not yet: built-in features for every screen area (permissions no feature uses are listed under "Other" by category); screens using the new identifiers.

## Tenant detail page (2026-11-02)

Clicking a tenant on the Tenants page opens /platform/tenants/{tenant} with side tabs: Overview (subscription, status, projects) and Configuration for the tenant, and Features, Roles, Connectors and Media types for the project picked at the top of the side bar. The tab and project are in the address. Platform routes for one customer project: /api/platform/orgs/{orgId}/project-features and /api/platform/orgs/{orgId}/roles. Planned additions: Team (members) with P2, Email templates with P5.

## Stages

| Stage | Scope | Status |
| --- | --- | --- |
| P1. Features and permissions catalogue | `capability` table (V24): global and per-project entries, built-ins seeded and protected. Effective keys in project details. Platform tab "Features & permissions", project Settings section. Custom roles may list global custom permissions | Built 2026-11-01 (pushed 7d81c09); redesigned 2026-11-02 (V25, local) |
| P2. Roles and people | Roles at global level (exists) and project level. User management (invite, assign to organizations, change roles, deactivate). Organization creation and assignment from one place | Project-level roles built with the redesign (2026-11-02, local); user and organization management planned |
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
