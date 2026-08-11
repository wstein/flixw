# Benchmarks

Measured, not estimated. Reproduce with the commands below; if your numbers differ,
yours are the ones that matter.

## Method

- **Machine** Apple M2 Pro, macOS 26.5.2, OpenJDK 21.0.12 (Homebrew)
- **Compiler** stock `flix.jar` 0.75.2, 32 MiB (33,931,077 bytes)
- **Project** the regression suite's scratch project: one source file, dependencies
  already resolved
- **State** warm page cache, warm compiler cache, warm dependency cache
- **Timer** `/usr/bin/time -p`, real seconds, 5 samples for cheap commands and 3 for
  `check`

Every figure is wall clock for the whole command as a user would run it, so the wrapper
rows include both JVM starts and the unconditional digest check.

## Wrapper overhead

`--version` is the cheapest command the compiler has, so it isolates wrapper cost.

| Path | `--version` | overhead |
|---|---:|---:|
| `java -jar flix.jar` (baseline) | 0.19 – 0.25 s | — |
| `./flix` — compiled stage 0 | 0.34 – 0.44 s | **≈ +0.17 s** |
| `./flix` — source stage 0 (JEP 330) | 1.16 – 1.17 s | ≈ +0.95 s |

```console
for i in 1 2 3 4 5; do /usr/bin/time -p java -jar "$jar" --version; done
for i in 1 2 3 4 5; do /usr/bin/time -p ./flix -- --version; done
```

Stage 0 in isolation, doing everything except launching the compiler
(`./flix wrapper --version`, which resolves nothing and touches no network):

| | |
|---|---:|
| compiled stage 0, end to end | 0.07 – 0.08 s |
| bare `java -version` (JVM floor) | 0.02 s |

So of the ~170 ms the wrapper adds to `--version`, roughly 120 ms is the unconditional
SHA-256 of the 32 MiB JAR and most of the rest is the second JVM start. Stage 0's own
logic — root search, lock parse, Java selection, dispatch — is a few milliseconds.

## Why the shims consult the cache

The source and compiled rows differ by ~0.95 s **per command**, because JEP 330
recompiles `flix.java` on every invocation. That is the entire justification for letting
the shims look up the content-keyed compiled stage 0 in the user cache, which is
otherwise knowledge a shim should not have. The alternative is not "a slightly slower
wrapper"; it is a wrapper that adds a second to every `check` in an edit loop.

Stage 0 compiles itself into the cache after its first source launch, so the fast path
arrives on the second invocation and no build step is ever required of the user. When
`javac` is unavailable — a JRE rather than a JDK — the compile is skipped silently and
the source path keeps working.

## In context

| | direct | `./flix` |
|---|---:|---:|
| `check`, one source file | 3.12 – 5.49 s | 3.19 – 3.70 s |

At this scale the wrapper's ~170 ms is inside run-to-run variance; the baseline's own
spread is larger than the overhead being measured. The honest summary is that the
overhead matters for `--version` and does not matter for anything that compiles.

## Digest cost

```console
$ for i in 1 2 3; do /usr/bin/time -p shasum -a 256 "$jar"; done
0.12  0.13  0.12
```

This is why there is no install stamp and no `FLIX_VERIFY_ALWAYS` flag. A skip mechanism
would save 120 ms on commands where 120 ms is invisible, in exchange for a cache that is
trusted rather than checked, plus the stamp's own staleness and corruption cases.

One measurement that changed a decision: `-XX:TieredStopAtLevel=1`, the reflex flag for
JVM startup, makes the digest **3.4× slower** because the hash loop is the only hot code
in stage 0 and it needs C2. Net result is worse. Do not add it.

## What is not measured here

Cold start (first download, ~32 MiB), Windows (`flix.cmd` under `cmd.exe` and
PowerShell), Linux, and multi-release behaviour over the real Flix cadence. Those belong
to a field study that has not run yet.
