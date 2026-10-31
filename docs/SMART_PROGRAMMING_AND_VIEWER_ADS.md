# Smart programming and viewer advertising requirements

Scope updated 30 October 2026. This document records the requested product capabilities and the first implementation below. The existing production-readiness assessment still applies; implementation is not production sign-off.

The product must support series/season/episode programming, automatic daily episode progression, ads selected for individual viewers, and media-type configuration at platform and tenant levels. In this specification, product owner means the platform administrator; viewer means someone watching a channel, not a tenant CMS staff member.

The initial viewer identity choice is pending: signed-in viewer profiles, anonymous/device audiences, or both. Contextual advertising must remain available when an eligible viewer identity or personalization permission is unavailable. Defaults below are proposed implementation behavior, subject to product review.

## Media types and settings

The platform administrator defines the catalog of supported media types, their stable keys, labels, required metadata and scheduling behavior. Initial examples are Movie, Episode, Short, Advertisement and Promo. Business labels such as Documentary or Music Video may be added with an explicitly supported scheduling behavior; a new label does not automatically introduce a new encoding pipeline.

A series contains seasons; a season contains playable episodes. Series and season records organize content and are selectable scheduling sources. They are not uploaded video files themselves. An episode references a series and season and has an episode number, optional broadcast order, availability/release time and processing/rights status. Prevent duplicate numbering within a season unless an explicit alternate-version model is introduced.

| Setting owner | Controls |
| --- | --- |
| Platform administrator | Media-type definitions, required fields, supported behavior, tenant entitlements and platform limits |
| Tenant administrator | Enabled subset of entitled media types, default type, metadata defaults, proposed programming defaults and permitted ad-personalization settings |
| Channel operator | Channel timezone, recurring programming rules, source series/season, starting episode, premiere and repeat times, fallback and season-end behavior |

The effective configuration is the intersection of platform support, tenant entitlement and tenant enablement. The API enforces it on uploads, imports and edits; hiding a dropdown option is insufficient. Tenant settings cannot override platform limits. Channel overrides may only use tenant-permitted settings.

Disabling a type prevents new use without deleting existing content or silently removing published programmes. Show affected assets, future rules and schedules before an administrator changes availability. Keep identifiers stable when labels change. Preserve existing assets when migrating from the current four hard-coded kinds.

Initial metadata behavior:

- Movie/Short: playable standalone content with duration, language, classification, availability and applicable rights.
- Episode: standalone fields plus series, season and episode/broadcast order.
- Advertisement: creative duration and trafficking metadata; only placed inside an ad break through ad decisioning.
- Promo: playable promotional content with explicit eligibility for filler or scheduled placements; it is not automatically a paid ad.

## Smart episode scheduling

A channel operator can create a rule such as:

> Every weekday at 20:00 in the channel timezone, play the next episode of Example Show, starting at Season 1 Episode 1. Air one new episode per broadcast day. Repeat that day's episode at 23:00. Continue to the next season when available; otherwise alert the operator and use the configured fallback.

| Broadcast day | Premiere at 20:00 | Repeat at 23:00 |
| --- | --- | --- |
| Monday | S01E01 | S01E01 |
| Tuesday | S01E02 | S01E02 |
| Wednesday | S01E03 | S01E03 |
| Thursday, after the last episode of season 1 | S02E01 | S02E01 |

The Thursday example assumes season 1 has three episodes and season 2 episode 1 is ready and eligible. This advances the channel's broadcast lineup; it does not give each viewer a private episode timeline. A viewer joining Tuesday watches Tuesday's broadcast already in progress.

Rules store channel, timezone, local start time, recurrence/day selection, effective dates, source series/season or playlist, initial episode, order, episodes per premiere occurrence, explicit repeat times, no-repeat interval, placement priority, fallback and season/series-end policy. Tenant settings supply defaults; the channel rule records its effective values so later default changes cannot silently alter an existing rule.

Required behavior:

1. Offer preview before committing. Materialize recurring rules into normal schedule entries ahead of airtime so playout reads an explicit schedule rather than running the programming logic on every viewer request.
2. Choose the next episode in configured broadcast order, using numeric season/episode order as the default. An encore reuses that day's selected episode and does not advance the sequence.
3. Construct each occurrence from local calendar date/time in the channel's timezone. Do not implement the next day by adding 24 hours to a UTC instant. Resolve ambiguous/nonexistent daylight-saving times explicitly.
4. Wait for content readiness, release time, rights and tenant media-type eligibility. Never skip a missing or unavailable next episode silently. Show the reason and apply the rule's explicit fallback/skip policy.
5. Make end-of-season/end-of-series behavior explicit: continue to the next season, stop for operator action, switch source, or restart if repeats are permitted. Do not restart season 1 unexpectedly.
6. Preserve manual edits and locked slots when regenerating future occurrences. Flag conflicts with other rules or placements for resolution before publication.
7. Make generation idempotent per rule/occurrence: preview, retries, restart or concurrent jobs cannot advance the sequence twice or create duplicate placements. Sequence selection must be stable across overlapping generation windows and published history; deleting/rebuilding a draft must not accidentally consume another episode.
8. Account for programme duration plus planned ad breaks. A fixed programming band has an explicit underfill/overrun policy; never truncate an episode or shift later published programmes silently.
9. Generate EPG series, season and episode information from the same published placements. Keep premiere and repeat labels consistent with the chosen episode.

Acceptance includes consecutive days, weekdays/weekends, season rollover, missing episodes, release dates, expired rights, repeats, manual locks, concurrent generation, regeneration, and both daylight-saving transitions. No AI model is required for this deterministic scheduling behavior.

## Viewer-based advertising

Two eligible viewers watching the same channel at the same instant may receive different ads in the same break. The programme lineup, break duration and segment timeline remain shared.

The platform defines supported targeting dimensions and limits. The tenant configures allowed audience segments, campaigns, exclusions and frequency caps within its entitlements and personalization policy. The first implementation should support explicit audience segments and contextual inputs such as channel/programme, country, language and device class. Do not infer sensitive characteristics or require demographic profiling for the first release.

Signed-in targeting, if selected, uses a viewer identity supplied by the authorized viewer application or identity integration. Anonymous targeting, if selected, uses a permitted tenant-scoped identifier with a defined lifetime. Neither model may trust an arbitrary browser-submitted claim that it belongs to a privileged audience. Tenant staff login accounts are not a substitute for a viewer identity integration.

Required behavior:

1. Validate identity/audience claims and personalization permission server-side. Keep identities, segments and frequency counts scoped to the tenant; do not correlate viewers across customer companies by default.
2. When identity or personalization permission is absent, choose eligible contextual ads, house ads or slate. A viewer must still be able to watch without personalized advertising.
3. Apply targeting, campaign flight/goal/pacing, per-viewer caps, competitive separation and exact pod fitting before finalizing the decision. Frequency caps span applicable channels/sessions within the tenant when a stable permitted identity exists; session-only limits must be identified as such.
4. Store one stable final decision per viewer session and break, including a slate decision, in state shared across edge instances. Refreshing or changing rendition cannot reshuffle ads for an in-progress break.
5. Keep personalized live manifests uncached. Serve shared immutable programme/ad segments through the delivery cache.
6. Forward only permitted targeting/permission signals to external ad servers. Avoid raw email addresses or unnecessary profile data in playback URLs, ad tags, logs and tracking requests.
7. Separate selected ads from confirmed delivery. Deduplicate events and retry transient reporting failures; reports must explain the reason for no fill without exposing viewer profile details.
8. Define withdrawal, identifier expiration and retention behavior before enabling persistent personalization. Apply updated permission to future decisions and prevent reuse of stale audience data.

Acceptance includes two viewers receiving different eligible ads without changing programme timing; stable repeat manifest requests across edge instances; caps across sessions; rejected forged audience claims; contextual fallback when personalization is unavailable; and no cross-tenant audience leakage.

## First implementation on 30 October 2026

Platform administrators manage media-type definitions at `/platform/media-types` and tenant entitlements in Connectors. Tenant owners/admins configure enabled types, defaults, premiere weekdays/time and viewer personalization in Settings. Library creation and editing support series/season/episode metadata; existing imported programme videos can be classified as episodes without another upload. Advertisement/programme conversion is refused.

Schedule now contains a Smart programming panel with persistent weekday rules, start/end dates, initial season/episode, next-season progression, one optional same-day encore, optional series looping, preview and draft generation. A background job extends drafts through the next 14 days every ten minutes. Publication remains an explicit operator action. Pausing a rule stops generation and retains existing placements. Rules add placements without rewriting manual edits; stored occurrence selections prevent retries or draft deletion from consuming another episode. A replacement rule requires resolving existing future placements deliberately. Full RRULE syntax, arbitrary multiple encores, independent empty series/season records and a rule-editing workflow are not in this first implementation.

Direct-sold campaigns support permitted audience keys and per-viewer hourly selection caps. The viewer application signs short-lived context using a tenant-specific key; identities may represent signed-in viewers or permitted anonymous viewers. Unsigned/no-consent contexts receive contextual ads. Reservations and final decisions are persisted in PostgreSQL; Worker break decisions, including slate on control-plane failure, are shared through a Durable Object. Failed beacon HTTP responses can retry on a subsequent live poll. Campaign controls preserve compatibility with older clients that omit the new fields. Audience selection does not imply goal pacing, provider-side audience activation or IAB measurement certification.

See [Viewer context integration](VIEWER_CONTEXT.md) for token format, privacy withdrawal, key rotation and deployment notes. Real viewer-app integration, retention policy, external demand acceptance, browser playback/load testing and the remaining production-readiness findings are launch gates.

## Remaining scope

| Capability | First implementation | Remaining work |
| --- | --- | --- |
| Media types | Platform registry, tenant entitlement/default settings, migration and server enforcement | Bulk classification and additional processing behaviors when required |
| Episodic content | Metadata UI/validation, duplicate checks, numeric season/episode ordering and guide metadata | Independent series/season records and custom broadcast order |
| Smart programming | Recurring rules, daily progression, season rollover, encore, preview, draft job and retained manual placements | Full RRULE syntax, multiple encores and editing saved rules |
| Viewer advertising | Signed audience context, contextual fallback, tenant settings, direct campaign targeting, shared caps/decisions and HTTP beacon retry | Production viewer-app integration, retention/consent integration, external audience activation and durable reporting beyond the live retry window |

Recommended order: media types and episodic catalog; daily episode scheduling with preview and publication; tenant-scoped viewer/audience integration; personalized ad decisions and measurement. Production security, migration and cross-instance consistency fixes remain prerequisites to launch.

Compute programming ahead of time and encode each asset once. Personalized ads require different manifests and shared decision state, not a separate encoder per viewer. These requirements are part of the intended launch scope, not optional later expansion.
