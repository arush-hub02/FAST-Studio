# Viewer audience integration

Viewer identity comes from the customer viewer application, separately from the staff who use FAST Studio. The application can represent a signed-in user or an anonymous viewer using an opaque identifier. Do not place email addresses or identifying profile data in the subject or audience keys.

1. In Tenant settings, an owner/admin saves the allowed media/programming defaults and enables viewer personalization.
2. Generate a random base64url signing key of at least 32 characters on the **viewer application server** and configure that same key through the authorized tenant Settings write-only form. FAST Studio encrypts it and never returns it through read APIs or a create-key response. Keep it out of viewer browser/mobile clients. Rotating it clears existing session audience context for future decisions; already finalized breaks keep their committed timeline.
3. The application server checks the viewer's personalization permission and signs the context below. The key is tenant-specific; the org claim must be that customer's UUID, not its slug.
4. Send the token as `viewerToken` in the JSON body of `POST /play/sessions`, along with the existing org/channel/destination/country fields. Watch live also has an optional signed-context field for integration testing. The response's stream token contains no audience keys or viewer subject.

The format is `base64url(JSON claims) + '.' + base64url(HMAC-SHA256(key, first part))`. Claims:

```json
{
  "org": "customer-organization-uuid",
  "sub": "opaque-viewer-id",
  "audiences": ["SPORTS_FANS", "RETURNING_VIEWERS"],
  "consent": true,
  "iat": 1793310000,
  "exp": 1793310600
}
```

Issue fresh timestamps: the sample is structural, not a usable token. Maximum lifetime is one hour; a ten-minute lifetime is a reasonable integration default. `iat` may be at most 30 seconds in the future. There may be at most 32 audience keys, each starting with a letter and containing up to 40 letters, digits, underscores or hyphens. Keys are normalized to uppercase. The server rejects invalid signatures, org mismatches and expired tokens when personalization is enabled. With personalization disabled, playback ignores viewer context and remains contextual.

Example server-side signer using Node's built-in crypto:

```js
import { createHmac } from 'node:crypto'

const key = process.env.VIEWER_CONTEXT_KEY // viewer application server only
if (!key) throw new Error('Configure the tenant viewer signing key')
const now = Math.floor(Date.now() / 1000)
const claims = {
  org: tenantOrganizationUuid,
  sub: opaqueViewerId,
  audiences: permittedAudienceKeys,
  consent: viewerAllowsPersonalization,
  iat: now,
  exp: now + 600,
}
const payload = Buffer.from(JSON.stringify(claims)).toString('base64url')
const signature = createHmac('sha256', key).update(payload).digest('base64url')
const viewerToken = `${payload}.${signature}`
// Send viewerToken to POST /play/sessions; never send key to the client.
```

The subject is converted into a tenant-specific HMAC identifier before persistence. The raw subject is not stored. Audience/identity context expires for new decisions at the token's expiration time. Starting another session with a renewed valid token permits continued targeting and shares caps for the same opaque subject/key.

## Campaign behavior

In Ads, set audience keys on a direct campaign. A viewer must match any configured key; a blank audience list allows contextual eligibility. Existing flight, country, priority, creative-readiness and competitive-separation rules still apply. External VAST providers retain their existing context macros; provider-specific audience/consent activation needs its own integration and is not implied by these direct-campaign controls.

The hourly frequency cap counts **decision reservations**, including unconfirmed selections, to prevent simultaneous sessions from over-serving. Set zero for no cap. A permitted stable viewer shares the cap across channels/sessions in the same tenant; without identity the cap is session-only. Reservation is not a billable event. Confirmed impression events remain separate and deduplicated.

The selected decision is stored per session and break, so restarting a server or refreshing a playlist does not reshuffle committed ads. Edge Durable Objects also persist the final ads/slate choice when a control-plane request fails. A failure to contact the durable coordinator makes the playlist request retry rather than issuing segments that could change underneath the viewer.

## Permission withdrawal and production limits

`POST /play/{sessionId}/privacy/contextual` clears that session's identity/audience context for future uncommitted decisions. The Watch live contextual action calls this endpoint. A previously finalized break, including an already prefetched one, retains its timeline. The session ID remains a private playback capability; do not publish it or trust it as a user login credential.

Tenant personalization can be disabled globally, and key rotation removes active session context. A real viewer application must connect its own consent/withdrawal workflow to this API, refresh expiring context, and define profile and decision-data retention before enabling persistent personalization in production. This feature does not establish legal compliance or certified ad measurement.

The existing country selector is still a labelled simulation; production geo enforcement remains a separate hardening task. HTTP beacon errors retry during subsequent live polls, but there is no durable event outbox at the edge to guarantee reporting across a long outage or a viewer leaving immediately.

## Edge rollout

The Worker now requires a `DECISIONS` binding to `BreakDecision`. `worker/wrangler.toml` includes its SQLite-backed Durable Object migration. Deploy the Worker with that binding before serving the updated application. Configure the real KV namespace, R2 bucket, control-plane/public URLs and shared edge secret as usual. No cloud deployment is performed by the code change.

The implementation uses the [Durable Object concurrency API](https://developers.cloudflare.com/durable-objects/api/state/) and [persistent SQLite-backed storage](https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/) so a finalized break survives isolate eviction. Object decision storage expires after 24 hours; PostgreSQL decision/audience retention still requires an operator policy.
