---
name: visualize-db
description: Regenerate and maintain docs/html/visualizeDB.html, the visual database guide (every table, its feature/pages/APIs/code, relations, CREATE/ALTER/auto-increment and diagnose queries, and a 500-error decoder). Use after ANY Flyway migration, new/renamed/dropped table or column, new screen or API that uses a table, or when the user asks to update/explain the database guide or debug a database-caused 500 error.
---

# Visual database guide (docs/html/visualizeDB.html)

The guide is **generated**, never hand-edited. It combines two sources:

| Source | What it gives | Maintained |
| --- | --- | --- |
| The real schema (all Flyway migrations applied to a fresh embedded PostgreSQL) | tables, columns, types, defaults, auto-increment/identity, primary/foreign/unique/check constraints, indexes, row-level security policies, triggers | automatically |
| Backend source scan (`backend/src/main/java`) | which classes read/insert/update/delete each table, and their layer | automatically |
| `docs/db/table-usage.json` | plain-language purpose, feature group, screens (frontend routes), API calls, "good to know" notes | **by hand** |
| `docs/db/visualizeDB.template.html` | page layout, query builders, 500-error decoder, how-to | by hand (rarely) |

## Regenerate

```bash
cd backend
./mvnw test -Dtest=SchemaCatalogExportTest -Ddbdocs=true
```

On Windows: `.\mvnw.cmd test -Dtest=SchemaCatalogExportTest -Ddbdocs=true` (Java 21). Without `-Ddbdocs=true` the test is skipped, so normal test runs never rewrite the file.

The generator **fails** when a table has no entry in `table-usage.json`, or an entry names a table that no longer exists. Fix the JSON, then rerun.

## When to update `docs/db/table-usage.json`

- **New table** (new migration): add an entry under `tables` with `feature` (one of the keys in `features`), `purpose` (one or two plain sentences, no jargon), `pages` (keys from `pages`), `apis` (`METHOD /path` as in the controllers), `watch` (short notes: what a wrong value means, which error code appears).
- **New screen**: add the route to `pages` (`/:org/...` or `/platform/...`, label = menu name) and list it on the tables it shows.
- **New API that touches a table**: add it to that table's `apis`.
- **New feature area**: add it to `features` (key = group name, value = one sentence).
- **Renamed/dropped table**: rename/remove the entry.
- Column-level facts (types, defaults, keys, checks) are read from the schema — do not copy them into the JSON.

## Verify

1. Open `docs/html/visualizeDB.html` in a browser (it is self-contained; no server needed).
2. Check the map shows the new/changed table in the right feature group, its page lists the right screens/APIs/code, and the Diagnose tab has queries for its foreign keys, unique keys and checks.
3. Paste a constraint name from a recent error into the search box: the right table must appear.
4. Commit `docs/html/visualizeDB.html` together with the migration and `table-usage.json` changes.

## Rules for the content

- Write for a non-technical operator: what the table stores, which screen breaks when it is wrong, what to check.
- Queries that change data or structure are templates for developers (shown with a red "changes the database" badge); structure changes always go into a new migration, never by hand on a live database.
- Never put real customer data, secrets, tokens or connection passwords in the JSON or template. The local demo connection (localhost, user postgres) is the only connection detail shown.
