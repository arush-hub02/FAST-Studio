# FAST Studio functional specification

All workflows are planned. IDs map to [Product requirements](PRODUCT_REQUIREMENTS.md); release gates are in [Roadmap](IMPLEMENTATION_ROADMAP.md).

Launch-scope clarification, 30 October 2026: platform/tenant media-type settings, series/season/episode programming with a new episode each broadcast day, and viewer-based ads are required. Detailed behavior, the first implementation, remaining scope and acceptance cases are in [Smart programming and viewer advertising](SMART_PROGRAMMING_AND_VIEWER_ADS.md). Viewer application identity/permission integration remains a customer integration decision; signed context can represent signed-in or permitted anonymous viewers.

## Tenant onboarding and first channel — F01–F04

Owner authenticates through OIDC, creates/selects an authorized organization, invites staff, uploads or imports media, reviews metadata/QC and assigns rights. The channel wizard selects language, time zone, territories, destination and eligible fallback. Show a readiness checklist rather than treating a saved draft as a running channel.

Acceptance: another tenant cannot discover the asset, upload URL, report or channel; refresh/restart preserves edits; missing rights produce a blocking reason. A successful upload means stored bytes, not a completed transcode. Display job stages separately and permit failed-job retry without duplicate assets.

## Scheduling, approval and EPG — F03/F05/F06/F16

Build a draft from a template or eligible assets, keep series episode order, edit individual start/end times and preview changes. Templates define repeat cadence, dayparts, repeat separation and date range. Expansion produces explicit entries that the operator can inspect; detect exhausted eligible catalogs and unsatisfied rules.

Use half-open time windows `[start, end)`. A programme finishing exactly at the rights end is allowed; one finishing later is rejected. Convert local schedule input through the channel's IANA time zone; require a choice for ambiguous DST time and reject nonexistent local time. Ad breaks are **explicit schedule items** that occupy wall-clock time, just like programmes. A mid-roll splits the programme at an asset cue point: part 1 → break → part 2. SSAI/SGAI then fill the planned break with ads, and any unfilled remainder plays slate, so the linear timeline (and EPG) never shifts. All boundaries sit on the 3-second segment grid. *(Revision 3 correction: the earlier "replacement within the programme window" wording describes live-TV ad replacement, not VOD-based FAST.)*

Publishing validates ready renditions, overlaps, gaps/fallback, all applicable rights, ad eligibility and required approval against an exact revision. An invalid recurring occurrence blocks publication. R2 approval requires a different authorized user; any material edit invalidates the approval. R1 can use owner publication with an audit record.

Acceptance: simultaneous edits produce a conflict; only one revision becomes current; published entries cannot be edited; rollback republishes a validated earlier snapshot as a new version; EPG derives from the same published snapshot. A current-programme calculation uses the published version and clock, never an independent copied list. A rights change invalidates affected future entries and triggers operational remediation for current delivery.

## Preview and distribution — F07/F09/F17

Preview shows programme, current/next, captions, pod markers and a clearly labeled territory-access simulator. A playable sample file is not a continuous playout output. Publish-to-destination creates a distinct operation record; accepted job, provider applied version, partner accepted and actually observed stream are separate states.

Acceptance: R1 simulator has repeatable success/failure cases and visible simulation badge; R3 uses signed authorization plus approved origin/CDN enforcement. A failed provider call leaves the prior successful version visible and creates an actionable alert. Destination rejection records its reason and retry eligibility without inventing platform acceptance.

## Advertising — F08/F18–F20/F24/F26

Ad operations creates advertiser/order/line item, chooses eligible owned inventory, price/goal, flight, territories/dayparts and allowed targeting. Creative upload goes through duration/format/QC and approval. Book inventory in seconds, with explicit reservations per scope and owner. Candidate selection evaluates rights, creative approval, campaign status/flight, budget/pacing, consent context, pod fit, competitive separation and supported caps.

R1 simulates this decision with an explanation of excluded candidates. R3 delegates actual decisions/stitching and tracking to the selected provider while validating configuration and receiving results. The provider's allocation/measurement semantics must be documented; Java is not a homemade replacement for the SSP.

Acceptance: a 120-second pod cannot contain 135 seconds of creatives; paused/expired/unapproved creatives are ineligible; concurrent reservations cannot oversell the same capacity; provider timeout/empty response/malformed VAST yields an eligible house ad or slate; failed insertion is visible. House ads are non-billable unless an explicit contract states otherwise. Direct-delivery shortfall creates a reviewed make-good/credit task, never a fabricated impression.

For permitted identifiers, document cap scope and window. Without them use only allowed contextual/session rules and show reduced capability. Restricted advertising contexts block prohibited targeting. Sponsorship deliverables have proof records; scans, video starts and conversions remain separate metrics.

## Subscription, usage and payments — F11–F13

Billing owner reviews a price version, allowances and overages, begins a trial or subscription and uses hosted checkout/invoicing. Authoritative provider events update payment state. Tenant usage appears with source, cutoff, unit and estimated invoice total; alerts at configured thresholds link to actions. Concurrent resource creation checks both permission and allowance.

Acceptance: duplicate callbacks produce one financial effect; forged callback signatures fail; out-of-order events cannot revive a canceled subscription; the browser return URL cannot mark an invoice paid. Cancellation and downgrade show effective dates and resource impact. Failed payment starts an audited grace workflow; live-channel suspension requires the documented service process. Corrections preserve issued invoice history.

## Reporting and settlement — F10/F21–F23

Select period/channel/destination/currency and see report freshness, coverage, data provenance and metric definitions. Import a partner report, compare counts, resolve material discrepancies, apply contract version and deductions, generate a draft statement, obtain independent finance approval and export to accounting.

Acceptance: reimporting the same source cannot double revenue; shares total the distributable balance with a deterministic rounding residual; a missing contract blocks final approval; an adjustment references its original; simulated data cannot enter a live invoice or settlement. Reports with unknown delivery cost display incomplete margin. Payment evidence changes receivable status independently from revenue reconciliation.

## Media migration and operations — F14/F15/F25

Bulk metadata import provides dry-run counts and per-row errors, maps external IDs and detects duplicates before committing. A malformed row does not silently discard others. Define atomic-batch versus accepted-row behavior explicitly in the import result. Abandoned multipart uploads expire and are cleaned up.

QC records source duration, codec/tracks, captions, loudness/profile checks where supported and actual provider findings. Manual approval cannot falsely imply an automated check passed. Alerts distinguish observed faults, stale telemetry and simulation. Acknowledgment assigns responsibility; resolution records evidence that the underlying fault cleared. Rights-blocked assets never become fallback simply because the primary failed.

## Expansion constraints — F27–F38

Regional variants inherit metadata but evaluate local rights, schedules and inventory independently. Catch-up creates a separately authorized offer. AI proposes changes with source/confidence/cost and human approval; it cannot infer rights or publish autonomously. Live overrun behavior, emergency slates and cue handling need a separate media test matrix before sale. Marketplace negotiation and commerce attribution require separate validated contracts and provider workflows before automated settlement.
