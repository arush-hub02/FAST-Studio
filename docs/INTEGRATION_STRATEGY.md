# FAST Studio integration strategy

Use capability-aware Java adapters and one tested provider per critical path before adding alternatives. Existing Enveu services take precedence when their actual capabilities, costs and isolation meet the requirements. No provider account/access was verified in this planning update.

| Capability | Build / integrate | R1 behavior | Paid release dependency |
| --- | --- | --- | --- |
| Identity | Integrate OIDC | Local development identity provider or explicitly restricted demo adapter | Real provider, invitation/session policies and secure production configuration |
| Catalog/rights/schedules | Build | Real API/database and deterministic policies | Hardened concurrent multi-user operation |
| Object storage | Integrate | Local S3-compatible storage or approved development bucket | Private objects, signed multipart uploads, lifecycle and tenant scope |
| QC/transcode | Integrate existing media pipeline | Real file facts or labeled simulation | R2 actual processing evidence, approved profiles and caption checks |
| Playout/channel assembly | Integrate (prototype: built in-house) | **Built:** virtual-linear HLS playout with SCTE-35, SSAI stitcher, SGAI interstitials — prototype grade | R3 continuous stream, supported transitions/cues/fallback and monitoring |
| CDN/playback controls | Integrate | Playable demo media plus access simulator | R3 origin and segment authorization, revocation policy and trusted geo context |
| Ad server / SSAI / demand | Integrate | Explainable simulated decisions | R3 provider-supported tags/cues, measurement authority, inventory and demand contract |
| Destination | Integrate one partner | Publication simulator with error cases | Stream/EPG/artwork profile validation and partner acceptance |
| Subscription collection | Integrate one billing/invoice provider | Optional price/usage preview only | R2 live payment/invoice reconciliation; country/currency/provider feasibility |
| Ad finance | Build orchestration + import partner reports | Fictional source reports | R3 real statements, contract definitions and independently approved exports |
| Viewer analytics | Integrate source player/partner events | Seeded fictional observations | R3 permitted identifiers, same-scope dimensions, lag and deduplication |

Adapters expose supported capabilities instead of claiming universal support. Suggested operations: create/update channel, submit schedule, get applied version, start/stop, get observed health, validate destination, authorize playback, ingest ad report, create checkout, fetch authoritative payment state. Separate desired state from provider state. Backend credentials stay in a secret store; the browser sees health/capability summaries only.

Candidate evaluation: [AWS MediaTailor](https://docs.aws.amazon.com/mediatailor/latest/ug/what-is.html) documents channel assembly and SSAI. Its assembly workflow need not require SCTE-35 conditioning, so marker requirements must follow the selected workflow/provider rather than being assumed universal. It remains one candidate; broadcast graphics, live switching, rights enforcement and destination support need separate validation.

For ad delivery, follow provider-supported [IAB VAST](https://iabtechlab.com/standards/vast/) contracts, cue requirements and measurement definitions. VAST describes the ad response, while SSAI stitches delivery; neither creates demand or certifies revenue. The minimum supported versions/features belong in each tested adapter contract.

For programmatic inventory, assess [ads.txt/app-ads.txt](https://iabtechlab.com/ads-txt/) ownership and seller authorization at the actual publisher/app distribution surface. Record responsible party and partner validation. Do not claim that the FAST Studio admin domain authorizes every partner app's inventory.

## Provider onboarding checklist

For each provider, record environment, external account, contract owner, credentials reference, allowed outbound endpoints, capabilities, formats, rate limits, retries, webhook signature scheme, idempotency support, measured lag, fees, data residency and escalation owner. Validate using controlled test media and signed test callbacks. Destination certification and provider acceptance must be recorded as external evidence.

Keep sandbox and production credentials, accounts, webhooks and metric namespaces separate. Redact signed URLs and sensitive targeting parameters from logs. User-supplied source/VAST/callback URLs need SSRF-safe networking, public destination validation and redirect restrictions.

## Failure and recovery

1. Persist intent plus outbox job transactionally; return a job ID.
2. Worker acquires durable lease, calls provider with stable request key and stores correlation/result.
3. For timeout, query authoritative job state before retrying a potentially completed side effect.
4. Apply authenticated callbacks through deduplicated inbox records. Late/out-of-order events cannot overwrite newer state.
5. Bound retries and expose dead-letter failures with safe operator replay. Test duplicate, partial-success and provider-outage scenarios.

Follow [Stripe's webhook guidance](https://docs.stripe.com/webhooks) if Stripe is selected: verify signatures against raw bytes, account for retries/duplicates, persist accepted events and handle them asynchronously. Stripe is an example, not an approved provider; regional availability and invoicing requirements remain open.

## Readiness labels

Every provider panel and export identifies `SIMULATED`, `SANDBOX`, `CONNECTED`, `VALIDATED` or `PARTNER_APPROVED` status as appropriate, plus last success and evidence. These labels are integration status, separate from a metric's simulated/observed/estimated/reconciled provenance. "Connected" means credentials work, not that a stream is certified or monetized.
