# FAST Studio — planning and design docs

**Revision 3 — 20 October 2026.** A working prototype now exists (see the [root README](../README.md) for how to run and demo it). This revision validated the revision-2 document set, fixed what was wrong or missing, and recorded the as-built decisions.

**New here? Read [How FAST works](HOW_FAST_WORKS.md) first:** FAST, the media pipeline, SSAI vs SGAI vs CSAI, and SCTE-35, in plain language with real playlist examples.

| Document | Purpose |
| --- | --- |
| [**Product guide**](PRODUCT_GUIDE.md) | **New.** Step-by-step guide to every screen with annotated screenshots, setup, end-to-end flow and behind-the-scenes tech (also as HTML/PDF) |
| [How FAST works](HOW_FAST_WORKS.md) | **New.** Primer: FAST what/why, virtual-linear playout, SSAI/SGAI, SCTE-35 |
| [Partner access and tokens](PARTNER_ACCESS_AND_TOKENS.md) | **New.** Audit of organization IDs on every API, partner organizations with minimal access (use case + example), JWT claims (user, tenant, org, role, project); P1–P4 built |
| [Multitenancy](MULTITENANCY.md) | **New.** Isolation layers, tenant lifecycle, roles, per-tenant config, what's built |
| [Product requirements](PRODUCT_REQUIREMENTS.md) | Customer, priorities, feature backlog F01–F44, success measures |
| [Functional specification](FUNCTIONAL_SPECIFICATION.md) | Workflows, edge cases, acceptance criteria |
| [Technical architecture](TECHNICAL_ARCHITECTURE.md) | Stack and module decisions (now as built) |
| [Data model](DATA_MODEL.md) | Entities, integrity rules |
| [API specification](API_SPECIFICATION.md) | HTTP contracts and permissions |
| [UX and navigation](UX_AND_NAVIGATION.md) | Journeys and accessible interactions |
| [Integration strategy](INTEGRATION_STRATEGY.md) | Build/buy boundaries and provider readiness |
| [Security and rights](SECURITY_AND_RIGHTS.md) | Tenant isolation, rights, ad safeguards |
| [Test plan](TEST_PLAN.md) | Release evidence |
| [Implementation roadmap](IMPLEMENTATION_ROADMAP.md) | Phases, gates, and prototype status |
| [Assumptions and open questions](ASSUMPTIONS_AND_OPEN_QUESTIONS.md) | Decisions made and still open |
| [Monetization strategy](MONETIZATION_STRATEGY.md) | Platform pricing, ad revenue, reconciliation |
| [Research and sources](RESEARCH_AND_SOURCES.md) | Competitor claims and standards references |

## Validation of revision 2 — findings and fixes

| # | Finding | Severity | Fix in revision 3 |
| --- | --- | --- | --- |
| V1 | Frontend undecided (Angular vs React), which blocked any scaffolding | Blocker | **React + TypeScript chosen** for the prototype (the docs allowed either). Business rules live in Java, so switching later only means rewriting the UI. |
| V2 | No explanation of *how* a FAST channel is produced; "playout" stayed an abstract adapter, so a "working" prototype could only fake it | High | Added [HOW_FAST_WORKS](HOW_FAST_WORKS.md). The prototype **implements virtual-linear playout** (deterministic HLS from schedule + clock), which is real at prototype grade. |
| V3 | SGAI absent; SSAI mentioned only as a future provider; no guidance on which to use where | High | SSAI/SGAI/CSAI comparison and a decision: **ad mode is per destination**. SSAI is the partner default, SGAI the option for capable owned players, pass-through for platforms that sell their own breaks. Both are built. |
| V4 | SCTE-35 mentioned once ("may be relevant"); nothing on segmentation types, formats or the provider/distributor split | High | Full SCTE-35 section. Pod **ownership → segmentation type 0x34/0x36** ties markers to the inventory split. Encoder/decoder built and tested against the SCTE sample. |
| V5 | Ad-break model conflated "replacement" breaks (a live-TV concept) with VOD-based FAST | Medium | Corrected: breaks are **explicit timeline items** (planned like programmes); SSAI/SGAI **fill** them, and leftover time becomes slate. Mid-rolls sit on **asset cue points** snapped to segment boundaries. |
| V6 | No asset **cue points**, so mid-roll ads had nowhere to go | Medium | Added cue points (F39), an editor in the Library, and use in auto-fill. |
| V7 | Multitenancy described only as principles; channel-slug scope, suspension behaviour, playback-side tenancy, platform-admin visibility and per-tenant config undefined | Medium | [MULTITENANCY.md](MULTITENANCY.md). Slugs: org globally unique, channel unique per org. Suspension blocks admin but not running channels. Platform admin sees counts only. Composite FKs built. |
| V8 | API naming drift between the original brief (`/api/v1/assets`, `/rights/evaluate`) and API_SPECIFICATION (`/organizations/{id}/…`, `/rights/evaluations`) | Low | Prototype uses org-scoped paths `/api/orgs/{slug}/…`. API_SPECIFICATION lists the as-built routes; the original brief is now a historical input. |
| V9 | The brief text duplicated whole docs (roadmap copied verbatim), so they drift | Low | `docs/` is the source of truth; `FAST produxt.txt` is kept as the original brief only. |
| V10 | R1 scope (F01–F10 incl. insertion orders, creative approval workflows) too large for a first working prototype; no "destination conformance" concept | Medium | R1 prototype re-scoped around the real end-to-end path. Added F40–F44: playout, markers, ad modes, destination profiles, launch checklist. Order/approval workflows move to R3. |
| V11 | Test plan had no media/marker tests | Medium | Unit tests for SCTE-35/CRC, rights and the scheduler; an end-to-end smoke test for isolation, playout and markers. |

What was correct and is kept unchanged: honesty rules (simulated vs real labels), default-deny rights with `[start,end)` windows, immutable published versions with optimistic concurrency, EPG derived from the published version, the modular monolith, and the build-vs-integrate split for production media/ads/payments.

## Revision 3.1 — fixes found while producing the [Product Guide](PRODUCT_GUIDE.md)

Driving every screen with a scripted browser to take the guide's screenshots surfaced four real bugs. All are fixed:

| Bug | Root cause | Fix |
| --- | --- | --- |
| SGAI ads didn't play when the player reached a break during normal playback (they only worked when joining mid-break) | The interstitial tag first appeared at the break's own start, so hls.js had already buffered up to the boundary and stalled | `PlayoutService` announces SGAI breaks up to 30 s ahead of the live edge |
| "Generate a sample" videos sometimes failed with "Incorrect result size" | The ffmpeg job was queued inside the create transaction and raced the commit | `MediaService.enqueue` defers to after-commit when a transaction is active (one fix covers every caller) |
| Preview player in the video drawer collapsed to a thin line | Flex children of the scrolling dialog body could shrink to zero height | `.modal-body > * { flex-shrink: 0 }` |
| Playlist viewer coloured `#EXT-X-DISCONTINUITY-SEQUENCE` as a discontinuity | Prefix match | Exact-tag match |

Also: clicking a schedule problem now scrolls the timeline to the flagged item.
