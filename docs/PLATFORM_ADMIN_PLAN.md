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

## P3 connector marketplace: design (user decisions 2026-11-02)

Reference: v2 CMS ops portal `ops-core/component/connectors` (add, update, dashboard, category list), `modals/connector.modal.ts` (GlobalConnector, ConnectorProperty, ConnectorStatus, AllowUserToUpdateConnector, ConnectorPropertyType), project `connector-settings`, and the studio `connectors-list` / `connector-detail` (tenant side).

**Decisions:**
- General settings (premiere time, weekdays, API, media and conversion limits) stay as Properties under Configuration.
- Connector properties live inside each connector.
- All connectors move onto connector properties now: Mux, R2 and ad servers.
- Who connects is a per-connector rule.
- Tenants connect from Settings with side tabs by category.

**Model**

```
Connector category ─ Connector ─ Property definitions (required, encrypted, external/internal, type, default)
                        │
                        └─ Instances (one per scope, or many for multi-instance connectors such as ad servers)
                              └─ Property values (encrypted when the property is)
```

- **Connector category** (global): key, name, position. Replaces the fixed MEDIA/ADS/STORAGE/OTHER list; those four are seeded.
- **Connector** (global): key, name, short and long description, light and dark logo URL, category, adapter (driver: MUX, R2, VAST, DIRECT, CUSTOM), reference link, plan tier (FREE or PRO). It also has:
  - **status**: AVAILABLE, UPCOMING (shown as "coming soon", can't be connected) or UNPUBLISHED (hidden from tenants);
  - **tenant update rule**: ALLOWED, NOT_ALLOWED or CONTACT_ACCOUNT_MANAGER;
  - **required permission**: tenants see the connector only if they hold it;
  - **level**: PLATFORM (one set of values for the whole platform, e.g. R2) or PROJECT;
  - **multi-instance**: true for ad servers (an ordered list per project).
- **Connector property** (belongs to the connector): key, label, description, type (STRING, NUMBER, BOOLEAN, SELECT, URL), options, default value, position, plus three flags:
  - **required**;
  - **encrypted**: stored sealed and never returned, only "configured";
  - **external or internal**: external properties are shown to tenants; internal ones only to platform administrators.
- **Connector instance and values:**
  - Each instance belongs to one connector, at platform level or for one project. It has a name, a position, on/off, and a connected status (all required values present, last check result).
  - Values are stored per instance and property, sealed when the property is encrypted.
  - Project instances follow row-level security.
- **Migration:**
  - The existing connector, tenant-connector and project-connector switches stay as availability.
  - `media_connection` rows become Mux instances; their ciphertexts move unchanged into encrypted values.
  - `ad_source` rows become instances of the VAST and DIRECT connectors (VAST tag URLs are already AES-GCM sealed).
  - R2 / edge settings become platform-level values, with environment variables as the fallback.

**Fields are data, not code (user, 2026-11-02).** Every connector's fields are rows in the property table, created by a migration as ordinary data:
- Mux: token ID, token secret, webhook secret, environment, passthrough tag.
- R2 / edge: Worker URL, edge secret, public URL.
- VAST: tag URL, timeout.

Platform administrators can rename, re-describe, reorder or extend them, and change their required, encrypted and external/internal flags, in Global configuration. Forms are built from those rows; no screen has a hard-coded field list. An adapter (the Mux, R2 or VAST code) reads a value only by its property key. If a property it needs is missing, the connection shows as "not connected" with the missing field named, rather than failing.

**Screens**

- **Global configuration → Connector definitions:** each connector has its own page with Details and Properties (a property table with required, encrypted and external/internal) and a category list.
- **Project Settings → Connectors:** side tabs by category. Picking a connector shows its description, status (Connected, Available or Coming soon) and a form of its **external** properties (required marked, encrypted fields write-only), with Connect and Disconnect when the rule allows. "Contact your account manager" is shown when it doesn't. Multi-instance connectors show an ordered list of instances.
- **Platform tenant page → Connectors tab:** the same view per project, including internal properties and platform-only actions.

**Sub-stages (each tested and pushed on its own)**

| Sub-stage | Scope |
| --- | --- |
| P3a | Categories, connector metadata, connector properties, instances and values (tables, APIs, encryption, RLS, tests); Global configuration connector pages |
| P3b | Project Settings → Connectors side tabs and the tenant page Connectors tab; connect, disconnect and rules |
| P3c | **Built 2026-11-03.** Mux moves onto instances and values (V27 copies ciphertexts; MediaConnectionService reads from there; webhooks, sync and uploads unchanged; check stored on the connection); R2 / edge platform values with environment fallback (EdgeClient, 30 s cache; CSP still from start-up settings); tenant export leaves out encrypted connector values |
| P3d | **Built 2026-11-03.** Ad servers move onto multi-instance vast and direct connectors (V28 copies ad_source with ids, sealed tags and order, then drops it; waterfall order = instance position; ad decisions and creative conditioning unchanged). Connections carry managed_by: the platform's are read-only for the project; a project adds its own ad servers only with Edit ads and AD_OWN_TAGS. The Ads page and platform Connectors ad-source screens edit the same connections |

## Stages

| Stage | Scope | Status |
| --- | --- | --- |
| P1. Features and permissions catalogue | `capability` table (V24): global and per-project entries, built-ins seeded and protected. Effective keys in project details. Platform tab "Features & permissions", project Settings section. Custom roles may list global custom permissions | Built 2026-11-01 (pushed 7d81c09); redesigned 2026-11-02 (V25, pushed e7a6299) |
| P2. Roles and people | Roles at global level (exists) and project level. User management (invite, assign to organizations, change roles, deactivate). Organization creation and assignment from one place | Built: project-level roles (2026-11-02, pushed e7a6299); people across organizations on the platform People page (list, add to an organization, change or remove a role, switch an account off or on; V29 app_user.disabled_at) built 2026-11-03, local |
| P3. Connector marketplace | Global connector catalogue with category, status (available, upcoming), visibility by permission, and per-connector property definitions: key, label, type, encrypted, required, external (shown to tenants) or internal. Tenant page with side tabs to connect a connector by entering its keys. Mux credentials (media_connection), R2 and ad servers move onto connector properties, keeping their encrypted values | Built 2026-11-02/03 in four sub-stages, all pushed with CI green: P3a 678dbe8, P3b f16f520, P3c 3a18621, P3d 9ae1652 (ad servers on connections, Google Ad Manager connector) |
| P4. Media types | Global and project-level types: create, edit, "add from global" to a project, enable per project | Built 2026-11-03, local: the global catalogue and per-project entitlement already existed; projects now create their own types (V29 project_media_type) in Settings → Media types; the tenant page shows them |
| P5. Project email templates | Per-project overrides of platform templates, with preview and fallback to the platform version | Built 2026-11-03, local: Settings → Emails (V29 project_email_template); sent emails use the project's wording |

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
