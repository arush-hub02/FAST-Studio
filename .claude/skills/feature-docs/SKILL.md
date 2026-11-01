---
name: feature-docs
description: Add or update a feature in docs/html/FEATURES.html (and its endpoints in docs/html/API.html, the API reference with copy-paste curl), the illustrated feature documentation (what it does, who can use it, step-by-step how-to, real screenshots, rules, API, tests). Use whenever a feature is completed or changed, when its screens change (re-take screenshots), or when the user asks for feature documentation, user docs or updated screenshots.
---

# Feature documentation (docs/html/FEATURES.html)

Every completed feature gets an entry, and its screenshots are re-taken whenever its screens change. The page is
**generated**; never edit `docs/html/FEATURES.html` by hand.

| File | Role | Edited |
| --- | --- | --- |
| `docs/features/features.json` | Content per feature (text, roles, steps, rules, API, tests) and a **screenshot recipe** per picture | by hand, for each feature |
| `frontend/docs-shots/feature-docs.spec.ts` | Plays every recipe against the running demo and saves `docs/features/img/<feature>--<shot>.png` | rarely (new step types) |
| `docs/features/features.template.html` | Page layout, search, zoom | rarely |
| `tools/build-feature-docs.mjs` | Checks the JSON and builds `docs/html/FEATURES.html` | rarely |

## When a feature is finished (part of the definition of done)

1. Add (or update) its entry in `docs/features/features.json`:
   - `id` (kebab-case, stable), `title`, `area` (group in the menu), `status` (`Built`), `completed` (YYYY-MM-DD).
   - `summary`: two or three plain sentences for a non-technical reader.
   - `who`: which roles can use it. `steps`: numbered how-to in the words on the screen.
   - `howItWorks`: the mechanism in plain words, step by step (what is stored, what happens on each request, what is checked).
   - `optimizations`: what was done to keep it fast and cheap (indexes, caches and their lifetimes, avoided writes, lazy loading, limits).
   - `scale` ("Under heavy load"): how it behaves with many users, many servers and attacks, with numbers where known; always
     name the **known ceiling** and its upgrade path. Never claim load behaviour that wasn't designed or measured; say "not measured".
   - `rules`: limits, permissions, what happens on errors. Say plainly what is **not** built yet; never present planned work as built.
   - `api`, `tests`, `docs`: endpoints, the tests that cover it, longer design docs.
   - `screenshots`: one recipe per picture (see below). Features without screens use `[]`.
2. Make sure the demo runs the new build (`start.bat`, demo mode on http://localhost:8080).
3. Take the screenshots: `cd frontend && npm run docs:shots` (`DOCS_BASE_URL` for another address).
4. Build the page: `node tools/build-feature-docs.mjs`. It fails on missing fields and lists screenshots not taken yet.
5. Look at every new picture (open the PNGs) and at the page in a browser. Check that nothing secret is visible.
6. **API reference**: if the feature added or changed endpoints, add or update them in `docs/api/api.json` under the right
   category: `title`, a plain `description` (what it does, who may call it, what errors mean), an example `body` for every
   request body, `query` and `params` values. Use `{{variable}}` placeholders from `variables` (org, channelId, assetId…)
   so the page and the Postman collection fill them in. Request body fields (type, required, rules) are read from the Java
   records automatically; give every new field name a plain explanation in `docs/api/glossary.json` (`fields`; path and
   query parameters under `params`), or per endpoint with `"fields": {name: text}`. Then:
   - `node tools/build-api-docs.mjs` → `docs/html/API.html`. It fails if any route has no entry, or an entry has no route
     (CI runs it too).
   - `node tools/check-api-docs.mjs --save` against the running demo (`--save` records the real answers as the page's
     response examples in `docs/api/responses.json`, shortened, with tokens, keys and signed links replaced; check the diff): every GET and safe POST runs for real, project writes are sent
     as the read-only analyst (403 = body valid, nothing changed), platform writes against a missing ID. 0 failures required;
     a documented exception gets `"checkAccepts": [status]` and a description that says why.
   - Open `docs/html/API.html`, copy one curl, and run it.
7. Commit `features.json`, the PNGs, `api.json` and the regenerated pages in `docs/html/` with the feature.

All generated HTML lives in `docs/html/` (index.html links them). Never edit those files by hand.

When only the UI changed: steps 3–5 and 7 (and fix recipes whose buttons or labels changed).

## Screenshot recipes

```json
{
  "id": "pat-tab",
  "account": "owner",
  "path": "/aurora",
  "steps": [
    { "click": { "role": "button", "name": "Profile and Settings" } },
    { "fill": { "placeholder": "Token name", "value": "Claude desktop (read-only)" } },
    { "select": { "nth": 2, "value": "never" } },
    { "waitFor": { "text": "Copy" } }
  ],
  "target": "[role=dialog]",
  "mask": ["[aria-label='New token']"],
  "caption": "What the reader should notice in this picture."
}
```

- `account`: a key of `accounts` in the JSON (demo people: owner, operator, analyst, partner, platform) or a demo name.
- `path`: where to start. `target`: CSS selector to crop to (a dialog, a card); omit for the whole window (1440×900, dark theme).
- Steps: `click`, `fill`, `select`, `waitFor` with a locator (`role`+`name`, `text`, `placeholder`, `label`, `selector`, `nth`), `wait` (ms), `eval` (JavaScript in the page).
- Prefer roles and visible labels over CSS classes, so recipes survive restyling.
- **Mask** anything secret or one-time (tokens, passwords, keys): it is painted over in the PNG.
- Data a recipe creates must be cleaned up: name it with a `Docs:` prefix and give the feature a `cleanup` script (runs in the page after each shot).

## Rules for the content

- Write for an operator, not a developer: what it is for, which screen, which button.
- Use only the demo's fictional data. No real customer data, secrets or tokens in text or pictures.
- Keep `docs/PRODUCT_GUIDE.md` and design docs as the long-form reference; this page is the illustrated overview.
