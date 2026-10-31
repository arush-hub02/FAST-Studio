# Project bootstrap and credential response policy
Implemented on codex/media-ingestion, 31 October 2026.

## Browser bootstrap

After login or page reload the application requests /api/auth/me for identity and available memberships. On selecting an active customer tenant it then requests:

GET /api/orgs/{org}/project-details

The tenant controller uses the existing membership, token-org, active-tenant, forced-password-change and service-token guards. The response is no-store, varies by Cookie/Authorization and is kept in React context only. Tenant switching remounts that context; it is not written to localStorage/sessionStorage. The stored last-tenant slug is still only a navigation preference.

The allowlisted payload contains schemaVersion, project identity/status, current role/permissions/features, enabled media types and scheduling defaults, connector configuration/capabilities and catalogue/channel summary counts. It does not dump the full catalogue. Detailed data continues through paginated/domain APIs.

The bootstrap does not authorize later requests: each API must still enforce its own permissions and tenant boundary. The platform admin can inspect a selected customer using GET /api/platform/orgs/{orgId}/project-details; this does not give the admin tenant membership.

Partner organizations retain their existing restricted shared-channel flow and do not request a customer bootstrap.

## Credential handling

Stored provider API keys, signing secrets and credential-bearing tag URLs must not be returned, including to platform administrators. Browser read DTOs return presence/configuration status only. Explicit DTOs form the serialization boundary; hiding a field in a component is not redaction.

Changes in this slice:
- Mux account reads no longer return even a token-ID prefix. Credentials are configured through masked, write-only inputs.
- Ad-source tag URLs are write-only in JSON. Metadata-only pause/resume updates preserve the stored URL instead of erasing it.
- Tag-test results omit source/creative URLs and return safe errors.
- Viewer integration key creation no longer generates and returns a server key. An authorized owner/admin provisions a key generated on the viewer application server through the write-only Settings form. The endpoint returns safe settings only.
- Browser/player ad-decision views exclude tracking URLs. Internal persisted decisions retain them for server-side tracking callbacks.
- Invalid source URLs and provider HTTP failures no longer echo submitted credential-bearing values or response bodies in the touched paths.

Credential entry by an authorized administrator is a write operation: newly entered values necessarily exist transiently in that administrator's form/request and are cleared afterward. They are never loaded back from storage. Viewer application clients must not receive the shared signing key. Scoped, expiring playback/upload URLs and HttpOnly authentication cookies are distinct runtime capabilities, not stored provider credentials.

## Folder boundary

The new project bootstrap demonstrates controller/project, service/project, repository/project and dto/project separation. Public ad-decision DTOs are under dto/media. Existing asset/Mux ingestion extraction continues in the next stage; this change does not claim all legacy controllers have been reorganized. Since 2026-10-31 these classes live in the [BACKEND_ARCHITECTURE.md](BACKEND_ARCHITECTURE.md) layout: `v1/controllers/studio/project/ProjectDetailsControllerV1`, `v1/controllers/platform/project/PlatformProjectDetailsControllerV1`, `v1/services/project/ProjectDetailsServiceV1`, `v1/repositories/project`, `v1/dto/project` and `v1/dto/media`. API paths are unchanged.

## Validation and remaining production gates

Regression coverage includes anonymous/foreign/suspended access, platform-only inspection, feature/role capability filtering, credential absence, encrypted write-only viewer keys, safe metadata-only connector updates and retention of internal tracking while excluding it from player JSON.

This is an initial security improvement, not a full production audit. Remaining gates include production identity/session policy and shared throttling, outbound redirect/DNS/download controls, encrypting legacy credential-bearing ad-tag storage, access-controlled media/CDN delivery, retention, durable measurement, migration rehearsals, real provider/playback acceptance and load/recovery testing. No cloud deployment is performed.
