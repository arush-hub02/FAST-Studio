# FAST Studio security and rights

Proposed engineering requirements. The original prohibitions on fake broadcasts, frontend-only geo controls, bundled secrets and unlabeled synthetic revenue remain mandatory product constraints.

## Identity, tenancy and authority

- Use a mature OIDC identity provider and Spring Security. Same-origin secure HttpOnly session cookies, deliberate SameSite settings, CSRF protection for browser mutations, session expiration and server-side logout. Production cannot silently enable demo identity shortcuts.
- Derive authorized tenant access from verified identity/membership. Every repository query, job, report, upload and signed download carries tenant scope. Composite foreign keys protect relationships. PostgreSQL row policies may add defense if correctly configured/tested; do not rely on them without worker/connection context tests.
- Enforce least privilege in Java. Feature visibility and frontend route guards support UX, while the API owns authorization. Billing permissions, contract approval and statement approval are explicit. Prevent self-approval and privilege escalation.
- Keep platform support access time-limited, justified, approved and audited. Platform administration must not grant silent broad customer content/finance access.
- External identities and viewer/playback credentials have separate audiences/scopes from administration sessions.

## Effective rights policy

Evaluate asset, channel, destination and contractual restrictions together. Default to deny when required rights data is missing. Explicit prohibitions take precedence; otherwise the requested territory/platform/language and full playback interval must be allowed at every applicable restrictive level. Distinguish "no applicable policy" from an explicit unrestricted policy.

Use `[start,end)` time intervals. Permit an item ending exactly at the rights end; reject one extending past it. Ad-supported use and advertiser/category restrictions are evaluated separately from content playback eligibility. Exceptions need an explicit permission, documented evidence and scope; no operator can override a legal prohibition merely by hiding a validation issue.

R1's country chooser is a labeled simulator. R3 derives location from a trusted provider/edge path and binds it to backend authorization. Short-lived tokens, protected origins/segments and cache policies must prevent a permitted manifest from exposing unauthorized playback. DRM is an independent provider capability, required only where the licensed workflow demands it.

Rights revocation or a new blackout invalidates affected future schedules and initiates replacement/takedown of current output. Token TTL, cache eviction/authorization and provider response define the maximum exposure window; document and test it before promising enforcement. Signed links alone cannot revoke already delivered media. Fallback content must independently satisfy current rights for the destination.

## Ad and data controls

Campaign configuration records inventory ownership, content rating, child-directed flags, advertiser exclusions and allowed targeting. Preserve applicable consent/permission context and purpose restrictions through provider requests. Restrict targeting when permission or identity is unavailable; do not fabricate consent or fingerprint viewers to restore caps.

Specific jurisdictional obligations, consent strings, retention and seller disclosures require provider/customer review for launch markets. No legal compliance certification is asserted by this document. Keep unnecessary viewer identifiers out of reports; restrict export access and define retention/deletion rules before real audience collection.

## External data and secrets

- Store credentials in a backend secret mechanism; return references and redacted status. Never write API secrets into React/Angular bundles, logs, seed data or CSV exports.
- Validate media URLs, VAST tags, redirects and webhook destinations against SSRF rules; block private/link-local/metadata destinations and revalidate resolution/redirects. Disable unsafe XML external entities and bound payload size/wrapper depth/timeouts.
- Use expiring signed upload/download URLs bound to allowed object/resource actions; verify size/type/checksum server-side. Validate uploaded media outside the request thread and quarantine failed inputs.
- Verify provider webhook signatures before persistence/processing, protect against replay and map provider identity to tenant internally. Use scoped outbound signing keys with rotation for future customer webhooks.
- Escape untrusted display metadata and protect CSV exports from spreadsheet formula execution. Parameterize queries, validate filter fields and rate-limit expensive/reporting operations.

## Financial integrity and lifecycle

Source report IDs, definition versions, contract versions and approval actors must be traceable through every amount. Use exact decimal calculations and deterministic rounding. No simulated event enters live billing; no browser flag changes paid state. Verify correction/reversal history and keep ad proceeds separate from platform invoices.

Document account delinquency/grace and service suspension policies; do not interrupt a running channel due only to a late/out-of-order billing callback. Archival and deletion respect active schedules, contract records and approved retention. Define operational recovery and deletion authority for each data class.

Before paid launch, test tenant boundaries, authorization, callback forgery/replay, origin bypass, rights changes, retention rules and restored-environment isolation. Record findings and close release-blocking defects; a reviewed specification is not evidence these controls exist.
