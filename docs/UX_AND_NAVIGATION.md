# FAST Studio UX and navigation

Workflow specification only; no screens are implemented by this update. Applies to either React or Angular. Retain the original professional application shell, contextual actions and responsive laptop/tablet layout.

## Navigation

| Area | Main views and user purpose |
| --- | --- |
| Overview | Onboarding progress, channel readiness, operational alerts, revenue/cost completeness |
| Media Library | Assets, series/episodes, imports, processing/QC, asset metadata and tracks |
| Channels | Wizard, configuration, preview, schedule, rights, advertising, destinations and activity |
| Scheduler / EPG | Day/list/timeline, templates, validation issues, approvals, version history and published guide |
| Rights | Policies, assignments, expiry/blackouts and affected schedules |
| Advertising | Advertisers, orders, line items, creatives/approvals, pods and inventory ownership |
| Distribution | Destinations, readiness, attempts, desired/applied versions and partner acceptance |
| Monitoring | Observed health, telemetry freshness, alert ownership and incident history |
| Analytics | Audience/advertising performance, source definitions, period and coverage |
| Finance | Reconciliation, contract versions, statements, receivables and channel profitability |
| Billing & Usage | Platform plan, allowances, estimates, invoices, payments and change/cancel actions |
| Settings | Members, integrations, notification preferences and audit; platform administration is separately scoped |

Finance and Billing & Usage use different labels and permissions so customers understand publisher revenue versus software charges. Group advanced areas progressively; avoid a flat sidebar containing every future module. Hide unentitled expansion modules without creating dead controls.

## First-channel journey

Use an actionable checklist: organization → ready media → valid rights → draft channel → valid schedule → EPG/preview → destination readiness. Each item links to the exact resource or validation issue. Preserve drafts and show which decision blocks progress. Display processing time separately from hands-on setup time.

Schedule validation includes issue severity, asset, affected interval, policy/reason and a repair action. Provide form-based start/end editing, move controls and list mode alongside any drag timeline. UI/UX Pro Max's `dragging movements` guidance specifically calls for single-pointer alternatives and retained keyboard operation; do not make dragging the only way to reorder or resize.

## Commercial journeys

Ad-sales journey: create order → assign eligible owned inventory → upload/QC/approve creative → review forecast and obligations → activate → inspect measured delivery → resolve shortfall. Show seconds reserved separately from estimated impressions.

Finance journey: select report period → review source freshness and counts → resolve discrepancy → preview split and deductions → obtain approval → export statement → reconcile payment evidence. Link every figure to its source/definition/contract version. A reconciliation review never silently marks the receivable paid.

Billing journey: review included allowances and price → see overage policy → confirm via hosted provider → display pending/verified payment → manage next renewal. Show upgrade/downgrade dates and resource impact before submission. Do not treat checkout redirect success as verified payment.

## Data trust and interaction requirements

- A persistent environment/provenance indicator accompanies simulated channels, sandbox payments and synthetic metrics. Exports carry the same labels. Currency, period, time zone, source and last refresh are visible where relevant.
- Distinguish internal publication, provider application and observed stream status. Include pending/error/retry states with correlation reference and meaningful next action.
- Financial dashboards show unpaid balances, estimated values, missing cost inputs and report lag. Use N/A when a denominator/source is unavailable; do not display misleading zero profit or zero errors for unknown data.
- Target WCAG 2.2 AA during implementation: keyboard access, visible focus, labeled forms, errors linked to fields, semantic tables, non-color-only statuses and accessible chart summaries. Respect reduced motion and avoid announcing every telemetry update to assistive technology.
- Preserve unsaved edits deliberately; use confirmations for stop/archive and effective billing changes. Prevent duplicate submission and report asynchronous progress without blocking unrelated work.
- Test dense schedule/finance views at laptop and tablet widths. Prefer scoped timeline scrolling with a usable list alternative; keep primary actions and validation accessible at increased zoom.

The release verification plan includes keyboard publication, rights error recovery, billing status comprehension and report provenance checks with real operators. A design-system/color implementation can be chosen when application development begins.
