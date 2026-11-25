# Notes

One entry per delivery phase.

## Phase 0 — Skeleton (2026-11-24)

Done when the example tile validates and events replay into the projections: **passing**
(`clojure -M:test`, 16 tests, 98 assertions).

- Loregate is a `:local/root` dependency (`../loregate`). The tile's query is validated with
  Loregate's own `loregate.schema.query`, and the fake gate checks its outputs and results
  against Loregate's plan and result schemas — a fixture that drifts from Loregate fails.
- `loreview.schema.{tile,checks,log}` (cljc). A tile's view and its UI events live in
  `tile.cljc`; there is no separate `view.cljc` / `events.cljc` (spec layout adjusted).
- `tile/problems` checks, without Loregate, that every column the view, the emitted events
  and the expectations name is one the query outputs (Loregate's naming rule: a keyword
  names itself, a path its last element, a local its name), and that listened events aim
  at declared params. Types against the plan's output contract wait for phase 2.
- Log events are closed maps: envelope (`:event/type :event/id :event/at :event/actor`) +
  payload. The author is always `:event/actor`; payloads have no `:by`. A mosaic is one
  `:mosaic/tiles` vector (tile, version, x, y, w, h), not layout + tiles.
- `loreview.store.projections`: one pure fold plus a guard (`problem`) run before every
  append: a tile drafted twice, an event on an unknown tile or version, a non-owner
  approving/publishing/retiring, versions out of order, a spec for another tile, events on
  a retired version, a mosaic saved by a non-owner or showing a version that is not live.
  The catalogue (declaration → live tile versions) is derived from pins on demand.
- `loreview.store.log`: the port and a file adapter (one EDN event per line). An append is
  checked, written, then folded; a refused or failed append changes nothing. A log with a
  refused line does not open — never silently repaired.
- `loreview.gate.port` (compile, run, query, plan-status) and the fake. describe, publish
  and unpublish join the port in the phases that need them. The HTTP client is phase 1.
- The test runner is Loregate's, copied.

## Phase 1 — A hand-written tile on screen (2026-11-24)

Done when the example tile renders for two fixture users with different results, and a
hidden column says why it is empty: **passing** — `clojure -M:test` (18 tests, 110
assertions) and `node test/browser/tile-check.js` against `clojure -M:dev`.

- Loregate has no HTTP yet, so phase 1 runs against the fake. The dev server answers
  Loregate's routes under `/loregate/api/v1` on Loreview's own origin — the place a reverse
  proxy puts the real Loregate. Swapping in the real one is a proxy change, not a code
  change; the browser already calls `run` directly, with the query beside the plan id.
- Fixture users and data are Loregate's (`ana`: everything; `bob`: US clients only, amounts
  hidden). `dev/fixtures/gate.edn` holds the rows Loregate's rules make of its fixture data
  for each of them, worked out by hand; replace them with real runs once Loregate executes.
- `loreview.api.server`: JDK HttpServer, EDN bodies; `handler` is a plain function, tested
  without a socket. Routes: `GET /api/tiles`, `GET /api/tiles/{id}/versions/{v}` (the id
  URL-encoded as one segment), static files. Any bearer token is accepted until the real
  identity check exists (marked `ponytail:`).
- `clojure -M:dev` seeds a fresh log by compiling the fixture tiles through the gate and
  approving them as ana — the shape the interview will produce later. Lineage is empty
  until `describe` (phase 4).
- Web: `loreview.web.{core,tile,render}` (fewer files than the spec layout listed). The tile
  engine keeps every tile under `[:tiles <instance-id>]` with generic handlers only. The
  viewer is picked from Loregate's fixture users in the header until real sign-in.
- Rendering decisions:
  - A bar chart whose measure is empty on every row (hidden from this viewer) shows the
    **table** instead — Vega drops rows with an empty value, which also dropped the names
    the viewer may see. With only some bars empty, every category stays on the axis.
  - An empty value shows as "—", never 0.
  - `:rows-filtered` reads "Rows may be limited by your access": Loregate sets it whenever a
    row rule exists, not only when rows were actually cut.
  - Labels come from the column keyword ("Unpaid total") until `describe` gives titles.
- Observed in the example tile: `n` (a local `[:count]`) counts paid invoices too, because
  the unpaid filter belongs to the measure, not the query. Correct by Loregate's rules, and
  exactly the kind of thing the "defined in this tile" lineage note is for.

## Phase 3 — Mosaics (2026-11-24)

Done when selecting a client in one tile filters another: **passing** —
`clojure -M:test` (20 tests, 123 assertions), `node test/browser/mosaic-check.js` and
`tile-check.js` against `clojure -M:dev`.

- A mosaic is tiles on a 12-column grid (`x y w h`); on phones every tile takes the full
  width. Instance ids are `[mosaic-id tile-id version]`.
- The bus: a click on a bar or a table row emits each `:emits` event with that row's value
  of its column (never an empty value) under `[:mosaic :bus]`; every tile of the mosaic
  listening for it sets the param and re-runs. Vega charts redraw only when their spec
  changes, so a click handler can change without a redraw.
- Saving (`POST /api/mosaics/{id}`) takes only title and tiles from the body; the owner is
  the caller. The guard refuses a non-owner, a version that is not live, a tile placed
  twice, and an event whose emitters and listeners disagree on type.
- **Changed from the spec**: a listener with no emitter in the mosaic is allowed (its param
  keeps its value) — the old rule refused any mosaic holding a tile whose `:listens` had no
  partner, e.g. the example tile alone. Spec 11.3 updated.
- Not built: a layout editor (drag, resize, add a tile). Mosaics are saved through the API;
  `clojure -M:dev` seeds "Receivables".
- A tile with no rows says "Nothing to show." instead of drawing an empty table.

## Phase 2 — Trust (2026-11-24)

Done when a failing check blocks approval, and changing a fixture measure makes the tile
stale, then valid again or invalid: **passing** against Loregate a754554 (phase 4) —
`clojure -M:test` (28 tests, 203 assertions), `tile-check.js`, `mosaic-check.js`.

- `loreview.check.view` — the view against the plan's output contract (bar y numeric, line
  x ordered, kpi numeric and one row, a tile's own emit/listen types agree).
- `loreview.check.expectations` — the six checks; a check on a column hidden from the
  viewer fails, never passes vacuously.
- `loreview.check.deterministic` — rows generated per `:plan/inputs` entry (keyed by link
  path) from the attributes' declared types, seeded: keys unique; attributes a link joins
  on draw from the keys, so links connect; other text draws, weighted, from the query's
  text parameter values, the test user's values and the keys, so a parameter filter and a
  rule comparing a row with the user both let rows through. Cases: base (kept as the golden
  case), empty, empty values, each parameter at its bounds/options, 2 000 rows. Rules apply
  for the test user (bob's runs keep amounts hidden).
- `loreview.check.approval` — `approve!`: a non-owner is refused before anything runs →
  schema → compile through the gate AND the embedded library (same plan id, or nothing is
  approved: the tested plan is the plan that runs) → view fits → deterministic cases →
  expectations live as the author → `:tile/approved` with the golden case. `refresh!`: a
  stale or no-longer-held plan is recompiled; same output, cases pass, golden case
  reproduces → `:tile/plan-replaced`; a rule-only change retakes the golden case; anything
  else → health `:invalid` with the reason. `POST /api/tiles/{id}/approvals` (the caller is
  the author; the caller's attributes are the test user). Dev rechecks every minute.
- The log stores dates (`#time/date`), so golden cases survive a reopen.

## The real Loregate replaces the fake (2026-11-24)

- `clojure -M:dev` runs on the real Loregate: embedded over its fixtures
  (`../loregate/dev/fixtures`), the fixture tiles approved through `approve!` at start,
  and Loregate's own HTTP handler mounted under `/loregate` (the browser talks to the real
  API and wire encoding). Development identity: the bearer token is a fixture user's id
  (`dev/dev-identity`, through Loregate's Identity port — Loregate unchanged). Users: ana,
  bob, cleo.
- `loreview.gate.client` — the gate over Loregate's HTTP API, decoding wire strings back to
  the library's values by column type; tested against Loregate's server on a socket,
  including an approval over HTTP. `loreview.gate.library` — the gate in-process.
- The fake gate is gone. Its answers live on as `dev/fixtures/expected.edn`, checked by
  `expected_test` against the real Loregate (rows and notes, ana and bob, both tiles).
- Fixed on the way: the dev pass-through gave Loregate the raw path, so a URL-encoded plan
  id missed the cache and was reported stale; it now passes the decoded path, as
  Loregate's own server does.

**Loregate is pinned by commit** (`deps.edn`: its GitHub URL and sha — 30d07be, phase 7,
on 2026-11-25). Move the sha deliberately, after running the tests against it. To work
against a local checkout instead (e.g. Loregate work in progress):
`clojure -Sdeps '{:deps {io.github.smogstate/loregate {:local/root "../loregate"}}}' -M:test`
The tests and `-M:dev` also read Loregate's fixtures from a checkout beside this one
(`../loregate/dev/fixtures`), which should be at the same commit.

## Phase 5, first part — line and KPI views (2026-11-24)

- `:kpi`: one big number, its label, and the difference from `:compare` when both are
  numbers; a result that is not exactly one row is shown as a table. `:line`: Vega-Lite,
  x temporal for date columns, optional `:series` as colour; with no number to draw it
  falls back to the table, like the bar.
- The fixture "Total contract value" tile now renders as a KPI, different per viewer
  (ana €50 000, bob €12 000, cleo €161 000 — per-row grants on contract values); checked in
  `tile-check.js` for all three users.
- `:partial` reads "X may be hidden on some rows": Loregate lists every attribute with a
  per-row rule, not only those it actually hid (cleo sees every value and is still listed).

## Phase 5, second part — publishing (2026-11-24)

Done when a published tile can be run by name by another caller with that caller's own
rules applied: **passing** against Loregate a7999f4 (phase 6) — 31 tests, 224 assertions;
on the dev server ana publishes and bob runs `tile_unpaid_invoices_v1` through Loregate's
HTTP with US clients only and amounts hidden.

- The gate gained `publish!` / `unpublish!` (library and HTTP). `loreview.check.approval`:
  `publish!` (owner, live version → Loregate names the plan `tile_<id>_v<n>`, the tile's
  title as its description until lineage exists → `:tile/published`), `unpublish!`,
  `retire!` (a published version loses its name in Loregate first). Nothing is recorded
  when Loregate refuses.
- Routes: `PUT` / `DELETE /api/tiles/{id}/versions/{v}/publication`,
  `DELETE /api/tiles/{id}/versions/{v}` (retire). The caller acts as the author.
- Loregate decides who may publish (`:publish/role`); in dev that is `role:finance`, so ana
  can and bob cannot (403 `:auth/forbidden`, nothing recorded). Spec 8 said "the author
  alone publishes": it is the author AND Loregate's role.
- **Not built: the catalogue in the graph (SPEC 10.2).** Loregate's graph store is
  Loregate's own (its plans and their dependencies); the ontology graph Loreview would
  write tile and mosaic nodes into needs a port to Loretile that does not exist yet. "Which
  tiles reach this measure" is answered by the projection's catalogue (from each plan's
  pins), which is enough today.
- Still to do in phase 5: `repair` — needs the model, as the interview does.
