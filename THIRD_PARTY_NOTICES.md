# Third-party notices

flixw's stage 0 — `flixw.java`, the file every adopting project commits and every run
executes — has **no third-party dependencies at all**, and that is a property the project
is built to keep rather than a coincidence to be traded away. What is listed below is one
component, used by one companion asset, on one command.

If this file ever lists something reached by stage 0, something the shims execute, or
something a project commits, that is a regression rather than an addition.

## picocli

|  |  |
|---|---|
| **Version** | 4.7.8 |
| **License** | Apache License 2.0 |
| **Copyright** | Copyright 2017 Remko Popma |
| **Homepage** | <https://picocli.info/> |
| **Source** | <https://github.com/wstein/picocli/tree/feat/fish-completion> |
| **Coordinate** | `io.github.wstein:picocli:4.7.8` |
| **Artifact** | `https://wstein.github.io/picocli/maven/io/github/wstein/picocli/4.7.8/picocli-4.7.8.jar` |
| **SHA-256** | `337747a2e97bcb91e678207675575252857b3adc676aa575fdb11068c90aee6d` |
| **Used by** | `flixw-help.java` only |
| **Reached on** | `./flixw help [...]` only |

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
