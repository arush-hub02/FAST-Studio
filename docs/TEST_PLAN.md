# FAST Studio release verification plan

This is the release verification plan for the implemented prototype. Production acceptance cases below are not all complete. See [PROGRESS.md](../PROGRESS.md) for dated implementation/test evidence and known limitations; historical results here are not a new test run.

## Critical acceptance coverage

| Coverage | Feature IDs / release | Required evidence |
| --- | --- | --- |
| Tenant/auth boundary | F01 / R1 onward | Tenant B cannot enumerate/read/mutate tenant A's IDs, media links, reports, jobs, invitations or finance data; expired sessions and revoked membership fail |
| Asset lifecycle | F02/F14/F15 / R1–R2 | Upload versus QC-ready distinction; interrupted upload/resume, duplicate import, malformed metadata and retry without duplicate charges |
| Rights matrix | F03 / R1 onward | Missing/deny/allow/conflict by asset/channel/destination, territory/language/ad use, exact expiry boundary, full-interval eligibility and revoked fallback |
| Schedule correctness | F05/F06 / R1 | Overlap/gap/repeat exhaustion, episode order, DST ambiguity/nonexistent input, additive/replacement pod duration and stale ETag |
| Publish consistency | F06/F16 / R1–R2 | Publish races, changed-after-approval rejection, immutable snapshot, rollback as new version and matching EPG/current-next |
| Simulation honesty | F07–F10 / R1 | Deterministic demo fixtures, clearly labeled previews/metrics/exports and no claim of provider delivery |
| Entitlements/meters | F11/F12 / R2 | Atomic quota race, allowance/overage math, price version, timezone/period cutoffs, replay, corrections and uncharged platform retry |
| Payment lifecycle | F13 / R2 | Raw-body signature checks, duplicate/out-of-order callbacks, provider outage, failed payment, grace, cancellation/downgrade, credit and verified settlement |
| Real stream | F17/F25 / R3 | Authorized origin/segment access, denied territory, rights revocation, cue/transition continuity, observed health and fallback drill |
| Ad operation | F18–F20/F24 / R3 | Paused/expired creative, full pod fit, concurrent reservation, invalid VAST/wrappers/timeouts, capped identifiers and restricted targeting fallback |
| Measurement | F21 / R3 | Decision versus impression separation, report deduplication/correction, provider lag, invalid traffic and source disagreement |
| Finance | F22/F23 / R3 | Known decimal examples, contract effective dates, rounding residual, currency separation, missing-cost incompleteness, independent approval and immutable adjustments |
| Operations | F10/F25 / R2–R3 | Alert acknowledgment distinct from resolution, stale probes, retry/dead-letter replay, deploy recovery and restoration evidence |
| Usability | All shipped IDs | Keyboard/list alternative to dragging, labeled errors, focus order, permission-aware controls, zoom and laptop/tablet rendering |

## Test layers

Use small Java unit tests for rights interval/policy composition, schedule validation, pod fit, contract allocation and meter rounding. Use PostgreSQL-backed integration tests for authorization/query scope, concurrent publish/reservation/quota constraints, migrations and financial uniqueness. A lightweight in-memory database is insufficient evidence for PostgreSQL locking/constraints.

Use adapter contract tests with controlled provider fixtures for success, partial success, timeout, replay and wrong signature. Run payment sandbox tests before a controlled real pilot invoice/payment. Separate simulated ad events from authoritative partner fixtures and production sources.

Use one frontend E2E framework chosen with the app to exercise the whole role-aware journey against Java APIs, not mocked browser state alone. Validate React or Angular actually selected; do not maintain duplicate frontend suites.

## Required end-to-end scenarios

1. **R1:** create tenant/catalog/channel; assign rights; deliberately introduce overlap and expiry; repair; publish; inspect matching EPG/preview; simulate ad underfill and destination failure; acknowledge/resolve alert; repeat as read-only user; verify tenant isolation.
2. **R2:** billing owner reviews contract, onboards media and creates allowed resources; threshold alert fires; replay usage/callback; invoice remains unchanged; payment settles; downgrade at renewal exposes excess-resource plan without an unplanned broadcast stop.
3. **R3:** provider applies schedule; authorized stream plays; allowed ad test is measured by authoritative source; restricted context falls back; import report twice and corrected version once; resolve discrepancy; split accepted net revenue; second user approves statement; reconcile receivable independently.

## Load, resilience and security gates

Exercise the [architecture envelope](TECHNICAL_ARCHITECTURE.md): 100,000 metadata assets, 10 channels, 14 days and 20 operator sessions per tenant. Record p95 metadata/schedule validation latency, tenant fairness, database query behavior and worker retry backlog. Provider/stream traffic uses its own capacity test, not a Java metadata benchmark.

Before R3: an agreed 72-hour representative soak including programme changes and ad breaks; stream fallback, rights revocation, provider outage and database/object recovery drills. Record actual RPO/RTO and incident ownership. Extend/repeat only when changed code, failures or inadequate coverage warrant it.

Security verification includes direct-object access, role escalation, CSRF/session handling, SSRF via media/VAST/redirect URLs, unsafe XML, upload abuse, forged/replayed callbacks, origin bypass, secret redaction and CSV formula injection. Block paid release for unresolved critical/high-impact findings.

## Release evidence

For each shipped feature record implemented/simulated/unavailable status, test result, environment, build/version, provider evidence and known limitations. R1 needs repeatable demo and documented setup; R2 additionally needs verified payment/usage, restore and support evidence; R3 needs stream/partner/measurement/finance approvals. A passing UI screenshot or simulator does not satisfy a real integration gate.

## Historical prototype checks (20 October 2026)

| Suite | Command | Covers |
| --- | --- | --- |
| Backend tests (20) | `cd backend && ./mvnw test` | **Cross-tenant matrix** over all 45 tenant endpoints (generated from the route table, app running under RLS); **row-level security** at the database layer; **signed media URLs** (folder binding, expiry, foreign keys); plus: SCTE-35 encode/decode, MPEG-2 CRC check value, official SCTE sample decode, 33-bit PTS wrap; rights default-deny, block precedence, channel territory, half-open licence window; auto-fill ad-load limit and cue points, no-ads licence, overlap/rights/ad-load validation |
| End-to-end smoke (27 checks) | `node tools/smoke-test.mjs` (server running) | Login/bad password, cross-tenant 404, role 403, anonymous 401, public playback session, live playlist structure, SCTE-35 marker + CRC, XMLTV, explainable ad decision, break fit, geo block (incl. no ads/markers for blocked viewers), 3-rendition master playlist with aligned media sequences, signed-URL refusal cases |

Manual browser verification done on 20 Oct 2026: SSAI stitched pod with server beacons, SGAI interstitial playback with client beacons, publish/go-live flow, channel wizard, analyst read-only UI, distribution conformance pass and fail.
