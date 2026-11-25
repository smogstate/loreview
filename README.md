# Loreview

A UI platform where every screen is composed of tiles. A tile is designed through a short
interview with a language model, grounded in a business ontology, compiled once into a
tested plan, and then rendered deterministically on every view — no model at runtime.

Loreview reads all its data through [Loregate](https://github.com/smogstate/loregate), the
read-only access layer over the ontology, as the viewing user.

Status: early. Tiles are approved (compiled, tested, golden case kept) and rendered per
viewer against the real Loregate; mosaics with a live event bus. No interview yet.

## Development

Needs the Clojure CLI, Java 21+, Node, and a checkout of
[Loregate](https://github.com/smogstate/loregate) next to this one (`../loregate`): its
fixture ontology, users and data are Loreview's development and test data. The Loregate
code itself is pinned by commit in `deps.edn`.

```sh
npm install
npx shadow-cljs compile app        # or: npm run watch
clojure -M:dev                     # http://localhost:8292, on Loregate's fixtures (users ana, bob, cleo)
clojure -M:test                    # unit tests
node test/browser/tile-check.js    # browser checks, against the dev server (needs Chrome)
node test/browser/mosaic-check.js
```

To run against a local Loregate checkout instead of the pinned commit, see `NOTES.md`.

## License

[Apache-2.0](LICENSE)
