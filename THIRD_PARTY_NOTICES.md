# Third-party notices

flixw's stage 0 — `flixw.java`, the file every adopting project commits and every run
executes — has **no third-party dependencies at all**, and that is a property the project
is built to keep rather than a coincidence to be traded away. What is listed below is one
component, used by one companion asset, on one command.

If this file ever lists something reached by stage 0, something the shims execute, or
something a project commits, that is a regression rather than an addition.

## cliwerk (picocli fork)

|  |  |
|---|---|
| **Version** | 4.7.7.1 (based on picocli 4.7.7) |
| **License** | Apache License 2.0 |
| **Copyright** | Copyright 2017 Remko Popma |
| **Homepage** | <https://picocli.info/> |
| **Fork source** | <https://github.com/wstein/cliwerk/tree/v4.7.7.1> |
| **Coordinate** | `io.github.wstein:cliwerk:4.7.7.1` |
| **Artifact** | `https://wstein.github.io/cliwerk/maven/io/github/wstein/cliwerk/4.7.7.1/cliwerk-4.7.7.1.jar` |
| **SHA-256** | `bd4576ad7b0d7f3f078d264b37b9eb9e2848caefee991b7c6c7c115ab5cdd77b` |
| **Used by** | `flixw-cli.java` only |
| **Reached on** | `./flixw help [...]` only |

This is wstein's fork of remkop/picocli (formerly `wstein/picocli`, renamed to cliwerk; the
Java package stays `picocli`, the spec DSL lives in `cliwerk.spec`). Its native Fish completion generator is a fork addition, not an
upstream Picocli capability. The coordinate and artifact above identify this fork's build;
the remaining licence and project-background information describes upstream Picocli.

### Why it is here

`./flixw help` puts four sources on one page — the pinned compiler's commands, the wrapper's
own verbs, installed plugins and the project's tasks. Laying that out as a single tree with
aligned, wrapped descriptions is the one part of a help system that a hand-rolled renderer
does badly, and getting it wrong shows: four differently-shaped screens leave the reader to
work out which side actually answers a given word.

### How it is distributed

**Republished as a flixw release asset**, listed in that release's own `SHA256SUMS` beside
`flixw.java`, and fetched through the same `ensureAsset` path as every other companion —
so it is verified, mirrored through `FLIXW_ASSET_SOURCE`, warmed by `wrapper --upgrade`,
and collected by `wrapper --purge`, exactly like the rest.

It is **not** fetched from the fork's Maven repository at run time. A second download origin would be a
second trust root, a second offline story and a second mirror to configure, in a tool whose
entire argument is that everything it runs came from one manifest that one digest covers.
That repository is consulted once, by `tests/pack.sh`, at release time, and the digest above
is what `pack.sh` requires those bytes to have before it will publish them.

Apache-2.0 permits this redistribution; the licence and copyright are reproduced above, and
the unmodified fork artifact is what ships — flixw does not repackage, shade or patch it.

### What it is not allowed to become

- Not a stage 0 dependency. Stage 0 knows the string `PICOCLI_ASSET`, never links against it,
  and never puts it on its own class path. The help asset runs in stage 0's JVM through an
  isolated class loader that can see picocli; the wrapper's own code cannot.
- Not a project dependency. Nothing is committed into an adopting repository; it lives in
  the machine cache under `<cache>/wrapper/assets/<flixw-version>/`.
- Not version-selectable by a project. The flixw release owns the version, the source URL,
  the digest and the compatibility contract; a lock has no say in it.
- Not on the critical path. If it cannot be fetched, `./flixw help` prints the wrapper's own
  routing table and the compiler's captured help from cache, offline.

## Flix compiler sources (test fixtures)

|  |  |
|---|---|
| **Origin** | <https://github.com/flix/flix> |
| **Revisions** | the commits listed in `tests/upstream/history/PROVENANCE.tsv` (tags v0.75.0 to v0.79.0 and master 7ac485c29d87e0eed5dcac2919decbb379096731) |
| **Files** | `main/src/ca/uwaterloo/flix/Main.scala` and `main/src/ca/uwaterloo/flix/util/Options.scala` at each revision, under `tests/upstream/history/<commit>/` |
| **License** | Apache License 2.0 (upstream `LICENSE.md`; the source headers name it) |
| **Copyright** | Copyright 2019 Magnus Madsen, as stated in the file headers |
| **License copy** | `tests/upstream/history/LICENSE.md`, byte for byte from flix/flix at 7ac485c29d87e0eed5dcac2919decbb379096731 |
| **Used by** | `tests/upstream-cli-matrix.sh` only |

The files are unmodified copies, used as input to the upstream CLI detector's regression tests.
They are not part of any release asset and nothing at run time reads them. Upstream has no
`NOTICE` file at those commits. This entry is a notice, not legal advice.

## Third-party flix.toml manifests (test corpus)

|  |  |
|---|---|
| **Origin** | public repositories on GitHub, 95 manifests from 76 repositories |
| **Files** | `tests/corpus/*flix.toml`, one per source file |
| **Provenance** | `tests/corpus/PROVENANCE.tsv` names repository, path and commit for each file; `tests/fetch-corpus.sh` re-fetches them |
| **Used by** | the regression tests only, as test input |

Each file is someone else's work, copied unmodified; none is a work of this project. The
files are not part of any release asset. Files are deduplicated by content, so where several
projects publish an identical manifest, only the first is kept.

The `license` fields of the source projects differ. The table counts the first `license`
field in each manifest as written there (three Apache-2.0 manifests carry the comment "see
also LICENSE.md" on that line). It records what each manifest states and is not a license file
of this repository.

| License field | Manifests |
|---|---|
| Apache-2.0 | 27 |
| MIT | 3 |
| AGPL-3.0-only | 1 |
| AGPL-3.0-or-later | 1 |
| BSD-3-Clause | 1 |
| MPL-2.0 | 1 |
| (no license field) | 61 |

The counts are checked by `sh tests/lint.sh`, which recomputes them from the files. To list
them by hand:

```sh
for f in tests/corpus/*flix.toml; do
  sed -n 's/^[[:space:]]*license[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$f" | sed -n 1p
done | sort | uniq -c
```
