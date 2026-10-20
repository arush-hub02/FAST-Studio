# FAST Studio assumptions and open questions

Baseline: 19 October 2026. This register makes planning reversible; it does not block independent specification/prototype work.

## Confirmed from the request and workspace

- Product: FAST Studio, from the existing 71 KB text brief.
- Requested work: enrich roadmap, add important features and monetization, using React/Angular with Java backend.
- Workspace before this update: one text specification; no application repository/framework/build configuration was found.
- Original brief prefers Angular and suggests Node.js as one possible backend. The revised backend is Java; frontend choice remains React or Angular, provisionally Angular.
- No provider access, customer contracts, real audience data, billing records or deployment evidence were supplied.

## Decided in revision 3 (20 Oct 2026)

| Decision | Choice | Reversible? |
| --- | --- | --- |
| Frontend | React + TypeScript (prototype built) | Yes. UI-only rewrite; API unchanged |
| Playout for the prototype | In-house virtual-linear HLS; provider integration still planned for R3 | Yes. `PlayoutService` sits behind the `/play` contract |
| Ad insertion mode | Per destination: SSAI (default), SGAI (capable owned players), pass-through (platform sells) | Yes |
| Break model | Breaks are explicit schedule items; mid-rolls on cue points; leftover time = slate | Core rule; changing it means migrating data |
| Marker semantics | SCTE-35 time_signal + segmentation descriptor; type 0x34 provider / 0x36 distributor from pod ownership | Per-destination format (DATERANGE / CUE-OUT) |
| Slug scope | Organization slug global; channel slug unique per organization | Hard to change after launch |
| Suspension | Blocks admin access; running channels continue | Policy can change |

Still open and blocking a paid pilot: identity provider; which partner platforms; whose ad server/SSAI for production; launch territories; billing provider.

## Provisional choices (revision 2)

| Decision | Working assumption | Owner / latest useful decision point |
| --- | --- | --- |
| Initial customer | Publisher operating 1–10 channels | Product / R0 interviews |
| Frontend | Angular because original intended developer uses it; React is valid if team prefers | Engineering / before app scaffold |
| Backend | Java/Spring Boot modular monolith, PostgreSQL, object storage, durable jobs | Technical lead / R0 |
| Java/Boot version | Java 21 provisional; assess Java 25 and current supported Boot line against services/SDKs | Technical lead / before dependency pinning |
| Commercial model | B2B base fee + channel allowance + disclosed usage; optional managed ad service | Commercial + finance / before R2 proposal |
| Delivery responsibility | Integrate media processing, playout, CDN and SSAI rather than build custom engines | Media lead / R0 provider assessment |
| First distribution | One approved destination; no platform presumed available | Partnerships / before R3 scope commitment |
| Ad measurement | Contractually authoritative partner report, not browser dashboard counters | Ad-ops + finance / before real ad billing |
| Settlement | Statements/export and payment evidence; automated publisher payouts deferred | Finance / R3 contract |
| First-launch markets | Not selected; demo includes fictional territories only | Commercial + rights owner / before collecting real audience data |
| Timeline | Team/capacity assumptions and estimates in roadmap; provider delays excluded | Delivery lead / after R0 |

## Questions with material impact

| Question | Why it matters | Decision deadline |
| --- | --- | --- |
| Which existing Enveu Java, identity, media, analytics and billing services can be reused? | Avoid duplicate systems and incorrect integration estimates | R0 |
| React or Angular for the actual team's skills and component ecosystem? | Changes UI scaffold, not domain/API | Before R1 coding |
| Who are the first two pilots, and what makes them willing to pay? | Defines MVP, service scope and price validation | Before R2 commitment |
| Does the customer own ad inventory and retain direct-sales rights per destination? | Determines reservations, ad demand and share contract | Before R3 inventory sale |
| Who supplies demand, authoritative measurement, invalid-traffic adjustments and cash settlement? | Defines real monetization path and financial responsibility | Before R3 integration acceptance |
| Which output profiles, SCTE/cue modes, captions, DRM and EPG formats are mandatory? | Determines provider fit and media effort | Before selecting live adapter |
| Which launch territories, rights evidence and advertising restrictions apply? | Determines enforceable rules and data/consent handling | Before real stream/audience rollout |
| Which entity/currency/payment rails/invoicing and tax requirements apply? | Determines subscription integration and commercial review | Before R2 live billing |
| Who pays CDN/transcode/SSAI and how are shared costs allocated? | Prevents double charging and false profitability | Before publishing prices |
| What incident coverage and recovery objectives can the team fund? | Determines support cost and promises | Before paid-service commitment |
| Are live events, graphics, catch-up or white-label apps contracted needs? | These can change the media path substantially | Before selecting R4 tranche |

## Risks and mitigations

Ad demand and partner acceptance may be unavailable: finish the labeled prototype, pursue access early and keep live gates unmet until evidence exists. Gross advertising may fail to cover costs: report contribution and run sensitivity scenarios. Real rights revocation may be slower than the control plane: document cache/provider/token exposure and test it. Report definitions may differ: retain source coverage, authoritative counts and discrepancy workflow. Excess feature scope may delay first revenue: ship one customer/provider/destination path before expansion.

This update does not authorize app implementation, vendor purchases, production changes, external messages or customer pricing commitments. It provides the planning artifacts for the requested enrichment.
