# FAST Studio monetization strategy

Proposed commercial design — 19 October 2026. Prices, splits, targets and examples below are hypotheses, not market benchmarks, financial forecasts or signed terms.

## Two revenue tracks

**Platform revenue:** the publisher pays FAST Studio for channel software, metered resources and optional services. **Publisher revenue:** advertisers or distribution partners pay for validated ad delivery, sponsorship or contracted distribution. Publisher ad receipts are not automatically FAST Studio revenue. Separate platform invoices, ad receivables and royalty liabilities.

## Recommended commercial packaging

Start R2 with a base SaaS fee plus a clearly defined active-channel allowance and cost-related usage. Offer bring-your-own ad server/demand. An optional managed-monetization agreement can add a contracted service fee or revenue share in R3. Do not mandate revenue sharing for customers bringing their own demand without a stated service.

| Offer | Buyer and included value | Charge basis | Release |
| --- | --- | --- | --- |
| Launch | Small publisher; one channel allowance, core library/scheduling/rights/EPG, standard support | Monthly base + disclosed usage/overage | R2 |
| Growth | Multi-channel operator; larger allowance, approvals and detailed commercial reporting | Larger base + active channels and usage | R3 |
| Enterprise | Broadcaster/reseller; SSO/SCIM, audit terms, dedicated integration/support options | Negotiated annual commitment + stated resource/support rates | R4 |
| Managed launch | Migration, metadata preparation, channel branding and destination submission | One-time scoped setup fee; acceptance checklist | R2 manual service / F32 tooling R4 |
| Managed operations | Scheduling, monitoring or ad-operations work under agreed coverage | Monthly retainer or documented service units | R3 manual / R4 tooling |
| Managed monetization | Contracted demand/ad-sales support, reconciliation and approved reporting | Explicit service fee or effective-dated revenue share | R3 |
| Localization / API / AI | Extra languages, integration capacity, approved AI enrichment jobs | Add-on subscription or prepaid units with cost controls | R4 |

Validate actual prices through buyer interviews, cost estimates and pilot proposals. Include a time-limited trial with capped usage, expiry messaging and assisted conversion; trial viewers never create a paid customer account automatically. Do not offer unlimited CDN or transcoding before measuring costs.

Each offer must disclose included channel capacity, media storage, processing minutes, delivery responsibility, support scope, overage rates, contract currency, tax treatment and cancellation timing. Avoid charging both customer CDN usage and the same provider passthrough cost unless the contract transparently defines the markup.

## Billable meters and subscription behavior

| Meter | Authoritative record | Rule |
| --- | --- | --- |
| Active channel-hours | Accepted provider/control-plane state transitions | Bill actual active intervals; monthly channel-equivalent uses a disclosed denominator |
| Stored GB-month | Object inventory snapshots | Time-weighted bytes; define decimal GB and billing period |
| Transcoded minutes | Completed processing job + rendition profile | Charge successful outputs under the contract; platform-caused retries do not create duplicate usage |
| Delivered GB | CDN/provider usage report | Include only contract-covered delivery; no charge from browser estimates |
| SSAI volume, if charged | Provider-accepted sessions/requests under named meter | Label the exact unit; never infer from a scheduled break |
| Managed tasks / AI credits | Approved completed task/job ledger | Show units and budget before execution; retries/failed jobs follow disclosed policy |

Persist each event with tenant, meter, unit, source ID, occurrence/receipt timestamps, billing period and price-version linkage. Keep raw evidence and correction entries. Usage estimates are labeled until the reporting cutoff. Late records follow a documented next-invoice adjustment policy rather than changing an issued invoice silently.

Quota checks must be atomic under concurrent creation. Upgrades can be immediate; downgrades apply at renewal after showing resources exceeding the new allowance. Cancel-at-period-end preserves contracted service until expiry. A payment failure creates reminders and a defined grace period; do not abruptly stop a channel on a delayed webhook. Approved suspension is separate from billing event processing. Refunds/credits are append-only adjustments; the provider is payment-status authority.

## Publisher advertising products

1. **Direct campaigns (R3):** advertiser → insertion order → line item → approved creative → duration-based reservation → delivered report → invoice/statement. Include price model, budget/impression goal, flight, channel/daypart/territory, allowed targeting, competitive exclusions and delivery remedy. Unsold pods use house ads or slate under policy.
2. **Sponsorships (F26):** branded channel/daypart, approved intro/outro, overlay or QR placement with rights and format approval. Sell specific deliverables and provide proof; a QR scan is not a purchase or billable video impression.
3. **Programmatic demand (R3 one partner; R4 multiple):** integrate approved ad server/SSP and SSAI. Record inventory ownership, permitted identifiers, provider fees, seller authorization and settlement terms. Integrate auctions rather than building an exchange.
4. **Distribution/licensing (R4 opportunity):** negotiated channel carriage, license fee or inventory split where contracted. Platform submission alone is neither acceptance nor guaranteed income.

Inventory split allocates ad opportunities between parties. Revenue split allocates money from defined receipts. They are separate rules; never apply both accidentally. Record channel/destination/daypart/date ownership and prevent duplicate sale. Forecast scheduled seconds and estimated audience separately; scheduled availability cannot guarantee impressions.

## Advertising metrics and states

Attach source, coverage, period, currency, reporting lag, schema/definition version and `SIMULATED`, `OBSERVED`, `ESTIMATED` or `RECONCILED` provenance to commercial reports. Cash receipt is a separate payment state; reconciled revenue may still be unpaid. Never sum simulation with actual observations. Zero denominators display N/A.

- Scheduled pod capacity = planned ad seconds. Not impressions.
- Duration fill = accepted filled ad seconds / eligible requested ad seconds for the same scope. Report provider slot-fill separately if its denominator differs.
- Ad completion rate = valid completions / valid starts from a compatible measurement source.
- Effective CPM = defined ad revenue × 1,000 / authoritative billable impressions.
- Revenue per watch hour = same-scope net ad revenue / measured watch hours.
- Gross CPM revenue estimate = billable impressions × contracted CPM / 1,000. Fixed-fee sponsorships and mixed prices use their own line items.
- Discrepancy = absolute difference between sources / agreed authoritative count. Investigate above the agreed tolerance; retain both counts.

A successful ad request, decision or stitched manifest is not evidence of a billable impression. Use the contractually authoritative provider report and invalid-traffic adjustments. Frequency caps depend on permitted stable identifiers; when unavailable, show the scope/limitation and use contextual or session-level rules only where allowed. Do not claim a universal cross-device cap.

## Settlement workflow

Import provider reports with checksums and deduplicated row/source keys → compare delivery with order/partner reports → flag discrepancies → approve accepted amounts → apply effective-dated contract deductions and shares → generate draft statements → finance approval → export to accounting → reconcile receivable/payment evidence.

Contracts define whether the split basis is gross earned, net earned or collected cash, permitted deductions, recipient shares, effective period, currency, rounding and minimum settlement/holdback policy. Missing contracts or unresolved material disputes block final approval. Changing a contract never silently recalculates approved historical statements. Reversal/correction entries reference originals.

R3 delivers statement/export and payment-status reconciliation. Automated publisher payouts are a separate provider/commercial project; a statement export is not proof of payment. Role separation prevents an ad operator from approving their own financial adjustment.

## Illustrative economics — internally consistent example

Assume one month, one currency (USD), 100,000 authoritative billable impressions, $10 CPM, a 10% demand fee, and shares of the remaining balance: publisher 70%, distributor 20%, FAST Studio 10%. These rates are invented for demonstration.

| Calculation | Amount |
| --- | ---: |
| Gross ad revenue: 100,000 / 1,000 × $10 | $1,000 |
| Demand fee: $1,000 × 10% | $100 |
| Distributable ad revenue | $900 |
| Publisher proceeds: $900 × 70% | $630 |
| Distributor share: $900 × 20% | $180 |
| FAST Studio contracted ad-service share: $900 × 10% | $90 |
| FAST Studio SaaS charge to publisher | $100 |
| Publisher delivery cost, separately borne | $250 |
| Publisher rights/operations cost, separately borne | $100 |
| Publisher contribution: $630 − $100 − $250 − $100 | $180 |
| FAST Studio attributable service cost, excluding publisher-borne costs above | $80 |
| FAST Studio contribution: $100 + $90 − $80 | $110 |

Tax, credits, bad debt, FX and shared corporate overhead are excluded from this illustrative contribution calculation. Commercial accounting treatment depends on the actual contract. The $900 distributable balance must equal $630 + $180 + $90. SaaS charges are a separate transaction.

Holding all other assumptions constant, publisher contribution breaks even at approximately 71,429 billable impressions: `1,000 × ($100 + $250 + $100) / ($10 × 0.90 × 0.70)`. In reality delivery costs and revenue mix can change with audience, so recalculate from measured costs. The product should offer low/base/high scenarios for impressions, net CPM and delivery cost, visibly distinguished from actual results.

## Commercial validation gates

R2: at least one verified platform payment/settled invoice and reproducible usage reconciliation. R3: first accepted partner statement, approved revenue-share calculation and transparent unpaid balances. Expansion: two pilots renew/commit after actual service and economics review. Favor positive contribution and customer retention over higher gross impressions.
