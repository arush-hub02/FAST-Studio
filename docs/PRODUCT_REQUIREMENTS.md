# FAST Studio product requirements

Revision 2 — 19 October 2026. All listed capabilities are proposed; implementation status: unstarted.

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

**Prototype status (20 Oct 2026):** F39–F44 are built at prototype grade, as are the core flows of F01 and F03–F07 (see the root README's real/simulated matrix). Partial items:

| Feature | Built | Not built |
| --- | --- | --- |
| F01 | Local accounts | OIDC; invitations show a one-time password instead of email |
| F02 | Upload, processing, metadata, series fields | Captions; resumable upload |
| F05 | Auto-fill, manual add, gap close | Recurring templates |
| F08 | Campaigns, ad decisions | Creative approval workflow; insertion orders |
| F09 | Destination checks; partner hand-off is simulated | — |
| F10 | Alerts, acknowledge/resolve, provenance labels | CSV export |

Everything from F11 onward is unstarted.

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
