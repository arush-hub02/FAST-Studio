# FAST Studio product requirements

Revision 3 — 20 October 2026: F45–F54 and feature extensions added after the [SoFast review](#competitive-review-sofast). What is built is listed under "Prototype status" below.

## Product and customer

FAST Studio helps a content owner turn an existing licensed catalog into a scheduled channel, distribute it through approved partners, and understand the resulting revenue and cost. The initial customer hypothesis is a small or midsize publisher operating 1–10 channels with limited broadcast engineering capacity. Validate this through five customer interviews and two prospective pilot partners before treating it as confirmed.

The product needs to support two buyers: the channel operator buying SaaS, and the ad-sales or finance team using the monetization workflow. Viewer access remains free for FAST channels. Platform subscriptions are B2B customer charges, separate from advertising revenue.

Positioning hypothesis: a rights-aware channel launch workflow with transparent advertising settlement and per-channel contribution margin. This is a proposed differentiator, not a verified competitor gap. Public competitor findings are in [Research](RESEARCH_AND_SOURCES.md).

## Priority and delivery definitions

- P0: required for the phase's promised customer outcome or safe operation.
- P1: commercially useful after the core outcome works; phase-gated.
- P2: expansion bet requiring evidence of demand.
- R0: discovery; R1: leadership prototype; R2: paid SaaS pilot; R3: real monetized channel pilot; R4: expansion.

P0 is relative to the assigned release. R3 monetization requirements do not all belong in R1. The roadmap defines release dependencies and exit gates.

## Expanded feature backlog

| ID | Priority / release | Capability and concrete requirement | Commercial value |
| --- | --- | --- | --- |
| F01 | P0 / R1 | Organizations, OIDC sign-in, roles, invitations, tenant isolation, audit history | Enterprise trust and account retention |
| F02 | P0 / R1 | Asset library, upload sessions, metadata, series/episodes, captions and processing lifecycle | Shorter content onboarding |
| F03 | P0 / R1 | Rights by time, territory, platform, language and advertising permission; expiry alerts | Prevent unauthorized distribution |
| F04 | P0 / R1 | Channel wizard, branding, eligible fallback content, lifecycle and readiness checklist | First-channel activation |
| F05 | P0 / R1 | Schedule templates, repeat rules, gap/overlap checks, episode ordering and manual edits | Lower operator effort |
| F06 | P0 / R1 | Immutable published versions, stale-edit protection, rollback by new publication, generated EPG | Reliable operations |
| F07 | P0 / R1 | Preview, current/next programme, rights decision simulator, visible simulation status | Credible sales demonstration |
| F08 | P0 / R1 | Campaigns, creative approval, ad-pod rules and explainable decision simulator | Demonstrate ad-sales workflow |
| F09 | P0 / R1 | Destination configuration, validation, publication simulation and attempt history | Demonstrate partner workflow |
| F10 | P0 / R1 | Alerts, acknowledgment, resolution evidence, metric provenance and exports | Faster diagnosis |
| F11 | P0 / R2 | Plan catalog, versioned prices, subscription lifecycle and server-enforced entitlements | Recurring SaaS revenue |
| F12 | P0 / R2 | Durable usage meters, allowances, estimates, overage policy and threshold alerts | Recover variable costs |
| F13 | P0 / R2 | Hosted payment/invoice integration, credits, dunning, cancellation and reconciliation | Collect subscription revenue |
| F14 | P0 / R2 | Resumable uploads, duplicate detection, bulk metadata import with row errors | Migrate real catalogs |
| F15 | P0 / R2 | Actual media validation/QC, caption/track checks and approved rendition readiness | Prevent failed broadcasts |
| F16 | P0 / R2 | Four-eyes approval for publish/rights changes, usage limits and channel change history | Sell to controlled operations teams |
| F17 | P0 / R3 | One playout/assembly integration, signed playback, real EPG delivery and one approved destination | Operate a paid channel |
| F18 | P0 / R3 | One ad-server and SSAI integration; marker validation, creative compatibility and slate fallback | Enable actual monetized playback |
| F19 | P0 / R3 | Direct insertion orders, line items, duration-based reservations, flight dates and paced delivery | Sell inventory directly |
| F20 | P0 / R3 | Ad-load limits, pod fitting, competitive separation, permitted targeting and identity-aware frequency caps | Protect viewing experience |
| F21 | P0 / R3 | Provider report ingestion, duplicate suppression, discrepancy resolution and billable-impression definitions | Defensible reporting |
| F22 | P0 / R3 | Effective-dated revenue-share contracts, royalty statements, approval and adjustment ledger | Revenue share and settlement |
| F23 | P0 / R3 | Channel P&L, cost allocation, revenue per watch hour and incomplete-data warnings | Retain profitable customers |
| F24 | P0 / R3 | Permission/consent context, child-directed content flags, advertiser exclusions and seller authorization checklist | Qualify monetizable inventory |
| F25 | P0 / R3 | Real stream probes, failed-ad alerts, fallback drills, incident runbooks and restore tests | Paid-service reliability |
| F26 | P1 / R3 | Sponsorship packages, branded dayparts, manually approved QR campaigns and delivery proof | Premium direct-sales products |
| F27 | P1 / R4 | Multiple demand partners, floors, unsold-inventory waterfall and yield experiments | Improve yield after baseline measurement |
| F28 | P1 / R4 | Regional channel variants, local rights/ads, translated EPG and caption/dubbing workflows | New territories and localization fees |
| F29 | P1 / R4 | SAML SSO, SCIM, support-access controls and configurable audit retention | Enterprise plan upsell |
| F30 | P1 / R4 | API access, signed outbound webhooks, delivery logs and customer sandbox | Integration add-on revenue |
| F31 | P1 / R4 | Reseller accounts, delegated organization access and white-label configuration | Partner-led sales |
| F32 | P1 / R4 | Managed launch, scheduling and ad-operations services with service scope and task tracking | Setup and managed-service fees |
| F33 | P1 / R4 | Live input switch, overrun handling, ad cue passthrough/replacement and regional blackout fallback | Live events and sports buyers |
| F34 | P1 / R4 | Rights-approved catch-up/AVOD exports with independent windows and ad policies | Reuse catalog across products |
| F35 | P2 / R4 | AI metadata, suggested cue points, schedule drafts and anomaly summaries with human approval | Optional credits and operator productivity |
| F36 | P2 / R4 | Content licensing exchange, offer negotiation and rights availability discovery | Potential transaction fees |
| F37 | P2 / R4 | Shoppable overlays with approved product catalog and click/conversion reconciliation | Sponsorship and commerce experiments |
| F38 | P1 / R4 | Measured multi-region failover and premium support commitments | Premium reliability offering |
| F39 | P0 / R1 ✅ | Asset cue points (mid-roll opportunities) snapped to 3 s segment boundaries; editor in the Library; used by auto-fill | Mid-roll inventory without manual editing |
| F40 | P0 / R1 ✅ | Virtual-linear playout: deterministic live HLS from published schedule + clock, discontinuities, program-date-time, fallback/standby, geo slate per viewer | Real playable channel without a playout vendor |
| F41 | P0 / R1 ✅ | SCTE-35 time_signal markers per break (provider/distributor placement opportunity by pod ownership) in DATERANGE or CUE-OUT/IN; decoder/inspector | Marker correctness = partner acceptance and correct inventory split |
| F42 | P0 / R1 ✅ | Destination profiles: ad mode (SSAI / SGAI / pass-through), marker format, ad-load limit; conformance check on publish | Prevent partner rejections before they happen |
| F43 | P0 / R1 ✅ | Prototype SSAI stitcher (server beacons) and SGAI via HLS Interstitials (client beacons); explainable ad decisions | Demonstrable monetisation path; evidence-based analytics |
| F44 | P1 / R1 ✅ | Guided first-channel checklist, plain-language validation messages, in-app "How FAST works" primer | Non-specialist operators succeed without training |
| F45 | P0 / R3 | Platform delivery packs: per-destination export of the HLS stream, XMLTV and JSON guide, and an MRSS feed (titles, artwork, ratings, durations, ad-break times); submission checklist and status per platform (e.g. Samsung TV Plus, LG Channels, Roku, Pluto TV, Tubi, Plex, VIDAA) | Channels accepted by real platforms; replaces the simulated hand-off |
| F46 | P0 / R2 | Platform-readiness check: compare a channel with each target platform's specification (codecs, bitrate ladder, segment length, loudness, captions, ad-break spacing and length, guide fields, artwork sizes) and show a pass/fail report before submission | Fewer rejected submissions; automates checks that managed-service competitors do by hand |
| F47 | P0 / R2 | Loudness measurement and normalization at upload (EBU R128 / ATSC A/85 target per destination, e.g. ffmpeg `loudnorm`), shown in media QC | Platforms reject channels with uneven volume |
| F48 | P1 / R3 | On-screen graphics: channel logo bug (burned in at conversion), "Up next" / "Coming up" cards and promos as generated bumper clips placed by the scheduler, per-channel templates with a safe-area check | Branded channel look that platforms and viewers expect |
| F49 | P1 / R2 | Guide enrichment: match titles against a metadata source (e.g. TMDB, or licensed Gracenote; check commercial-use terms) for synopsis, cast, genres, season/episode, artwork and canonical IDs; uncertain matches need human confirmation; daily guide-quality report (missing synopsis or artwork, unidentified titles, gaps) | Richer guide = better placement and discovery on platforms |
| F50 | P1 / R2 | Library readiness report: hours by genre, rating and territory with valid rights; how many 24/7 channels the library can fill within repeat limits; missing metadata, rights, captions and artwork | Self-service catalog evaluation; sales tool |
| F51 | P1 / R4 | Restream: ingest an existing linear feed (SRT or HLS), add or condition SCTE-35 markers and deliver it as a FAST channel without changing the original broadcast | Broadcasters with existing channels |
| F52 | P2 / R4 | Extra output formats per destination: DASH, SRT, HEVC renditions | Destinations that do not accept HLS/H.264 |
| F53 | P2 / R4 | Interface and emails in several languages (start with Spanish, Portuguese, French) | Customers outside English-speaking markets |
| F54 | P1 / R3 | CDN delivery with token authentication, and DRM where a destination requires it | Scale and content protection |

**Prototype status (20 Oct 2026):** F39–F44 are built at prototype grade, as are the core flows of F01 and F03–F07 (see the root README's real/simulated matrix). Partial items:

| Feature | Built | Not built |
| --- | --- | --- |
| F01 | Local accounts, JWT sign-in, emailed invitations, forgot-password | OIDC / company single sign-on |
| F02 | Upload, processing, metadata, series fields | Captions; resumable upload |
| F05 | Auto-fill, manual add, gap close | Recurring templates |
| F08 | Campaigns, ad decisions | Creative approval workflow; insertion orders |
| F09 | Destination checks; partner hand-off is simulated | — |
| F10 | Alerts, acknowledge/resolve, provenance labels | CSV export |

Everything else from F11 onward is unstarted, apart from the partner API (part of F30) and partner organizations (part of F31). F45–F54 are unstarted.

## Competitive review: SoFast

Reviewed 20 October 2026 from SoFast's public website only (no demo, no pricing; see also [Research](RESEARCH_AND_SOURCES.md)). SoFast is a managed service and distribution network. It audits a catalog, programs, brands and plays out the channel, places it on 360+ platforms and operators, and sells the ads: its own SSAI and ad server, programmatic plus direct sales, and revenue-share, inventory-split or hybrid deals. Customers get dashboards and a contact form, not a self-service tool. FAST Studio is self-service software, so SoFast is as much a possible customer or partner as a competitor.

**Differentiators to keep** (not mentioned on SoFast's public site, which does not prove they lack them): self-service operation; SGAI; rights enforced in the schedule, stream and ads; the partner portal and partner API; isolated multi-company operation with audit history; transparency (SCTE-35 inspector, built-in course, real-vs-simulated labels); running on the customer's own infrastructure.

**Added to the backlog:** F45–F54 in the table above, plus these extensions to existing features:

| Feature | Extension | Why |
| --- | --- | --- |
| F02 / F15 | Captions moved up to R2: upload SRT/WebVTT per asset and language, deliver them as HLS subtitle renditions, caption coverage in the F46 report | US platforms expect captions; accessibility |
| F05 | Programming blocks in auto-fill: marathons, premieres, themed dayparts, repeat rules (minimum gap before a title airs again) | SoFast advertises AI-assisted blocks; operators want them without AI too |
| F11 | Published prices and a self-serve free trial | SoFast and most managed services quote on request only |
| F18 / F21 | Reports by channel, platform and country: impressions, fill rate, CPM, revenue, matched to payment statements | Category expectation for monetization |
| F22 | Revenue per title and per rights holder in statements | Rights holders are paid per title; SoFast reports by channel, platform and country |
| F28 | Dubbing and subtitle provider integration (e.g. Deepdub, which SoFast uses, or ElevenLabs) with human QC; extra audio tracks in HLS; a guide per country | New territories |
| F33 | Live SRT input switch (to live and back to the schedule, ad markers kept consistent) can move to R3 if a pilot needs live | Live events and news |
| F35 | AI suggestions for programming blocks and ad-break points, always with human approval | Operator productivity |

**Not adopted** (SoFast business lines that are not part of a channel-operations product): TV launcher (WAYO TV), cloud gaming, hotel TV, set-top box / OEM platforms, satellite distribution, a licensed content catalog (F36 covers a marketplace if demand appears), an in-house ad-sales team and 24/7 human master control (only as F32 managed services if a customer pays for them).

Sources: [SoFast](https://sofast.tv/), [FAST On The Fly](https://sofast.tv/fast-on-the-fly/index.html), [Monetization](https://sofast.tv/monetization/index.html), [Cloud playout](https://sofast.tv/playout/index.html), [Technology](https://sofast.tv/technology/index.html), [Why SoFast](https://sofast.tv/why-sofast/index.html), [AI dubbing](https://sofast.tv/ai-localization/index.html), [EPG Enricher demo](https://epg-enricher.pages.dev/).

## Personas and new permissions

Retain the original Super Administrator, Organization Owner/Admin, Content Manager, Channel Operator, Ad Operations and Analyst roles. Add Finance Manager for statements/invoices and a commercial approval permission for signed revenue contracts. A rights reviewer may be an existing role with `rights.approve` rather than a new fixed persona.

Add `billing.read`, `billing.manage`, `usage.read`, `finance.read`, `finance.approve`, `ads.approve`, `schedules.approve`, `rights.approve`, and `support.access`. Organization owners can delegate billing; ordinary organization admins do not automatically receive finance approval rights. Explicitly separate platform billing receipts from publisher ad receivables and royalty liabilities.

## Scope by outcome

R1 must demonstrate persistent multi-user control-plane behavior, with simulated media/distribution/ad outcomes labeled throughout. R2 must collect a real B2B pilot subscription or a settled invoice, reconcile usage, and support customer data safely. A subscription pilot does not imply a monetized broadcast is operating. R3 adds real streaming, actual ad measurement and approved settlement through contracted providers.

## Product measures and proposed targets

| Measure | Definition | Initial decision rule |
| --- | --- | --- |
| Time to first schedule | Tenant creation to first valid published 24-hour lineup | R1 target <=30 minutes with prepared ready assets; measure onboarding/upload separately |
| First-channel activation | Pilot tenants achieving their agreed first-channel checklist / onboarded pilot tenants | Establish baseline with first two pilots; investigate every failed activation |
| Operator effort | Hands-on minutes to build and approve one channel-day | Target <=20 minutes after templates; confirm with pilot operators |
| SaaS MRR | Recurring platform subscription charges normalized monthly; exclude tax, setup, usage and ad proceeds | Report from billing records with credits/churn visible |
| Channel contribution | Publisher ad proceeds minus rights, delivery, operations and platform charges | Show incomplete when required costs/revenue are absent |
| Revenue / watch hour | Defined net ad revenue / measured watch hours for same coverage and period | No cross-partner total without comparable definitions |
| Ad discrepancy | Absolute source-count difference / agreed authoritative source count | Initial review trigger >5%; customer-specific tolerance; zero denominator => N/A |
| Pilot commercial gate | Two customers renew or commit after reviewing actual cost and service evidence | Evidence before expanding integrations |

These are proposed product targets, not measured performance or market forecasts. No CPM, fill, distribution deal, ad demand or revenue is guaranteed.
