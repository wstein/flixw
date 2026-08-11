# AGENTS.md

Guidance for AI coding agents working in this repository. **This file is the single source.**
The others are pointers, and carry only the constraints that are most expensive to violate:

| File | Read by |
|---|---|
| `AGENTS.md` | Codex, Antigravity, Jules, Cursor, and anything else honouring the convention |
| `CLAUDE.md` | Claude Code — imports this file with `@AGENTS.md` |
| `.github/copilot-instructions.md` | GitHub Copilot |
| `.agents/rules/flixw.md` | Google Antigravity workspace rules (max 12,000 chars per rule file) |

Change guidance here first; update the pointers only when a constraint in them is affected.

## What this is

`flixw` is an experimental, third-party, opt-in repository-local bootstrap for the
[Flix](https://flix.dev) compiler: `./flix <verb>` downloads, digest-verifies, caches and
executes an **unmodified stock `flix.jar`** pinned by the project. It is not a Flix fork,
plugin, or official tool.

`docs/Flix_Bootstrap_Wrapper_Paper.md` (design paper, Revision 6) is the normative spec.
Appendix A is pseudocode for `realMain`, Appendix B the evaluation matrix, Appendix C the
diagnostic table. Change behaviour there and in code together, or say explicitly which one
you are deliberately letting lead.

## Commands

The wrapper has no build system — it is one Java 21 source file, run via JEP 330.

```sh
java src/flix.java --wrapper-version      # offline; no project, lock, or network needed
java src/flix.java --wrapper-help         # routing table (enriched if run inside a project)
javac -d /tmp/flixw-out src/flix.java     # compile check
FLIXW_TRACE=1 ./flix check                # per-phase timings on stderr
```

Exercising it end to end means installing into a scratch project:

```sh
java src/flix.java install /tmp/proj      # writes flix, flix.cmd, .flix-wrapper/flix.java, .gitattributes
cd /tmp/proj && ./flix pin 0.75.2         # writes flix.toml + .flix-wrapper/lock.toml, downloads the JAR
./flix doctor                             # java, compiler, cache, mirror, proxy, routing state
./flix validate                           # wrapper files, lock/manifest agreement, git tracked status
```

The repository's configured checks, both required before a commit:

```sh
sh tests/lint.sh    # javac -Xlint:all -Werror, shellcheck, shim byte-parity, CRLF check
sh tests/run.sh     # 96-case regression suite; one ~32MB download on a cold cache
```

`tests/UnitCheck.java` is compiled against stage 0 and run from `tests/run.sh` as one of
those cases. It reaches what the shell cannot: the manifest scanner over
`tests/corpus/`, the `pin` rewrite as a property over the same corpus, 17 adversarial
manifests, and the bounds on `runCapture` — 347 assertions in total. Refresh the corpus
with `sh tests/fetch-corpus.sh`; see `tests/corpus/README.md` before changing it.

`tests/run.sh` builds every fixture it needs under `tests/.work/`, its gitignored scratch
space: two JDK stand-ins, a JAR whose `--help` cannot be parsed, a JAR that sleeps, and a
git-initialised scratch project. Nothing binary is committed. Four cases cannot exist on
Windows — three need a runnable fake `bin/java.exe`, one needs a POSIX signal — and are
reported as `skip` rather than asserted for the wrong reason.

## Architecture

Three artifacts ship into a consuming project, byte-identical across projects for a given
wrapper release; only `.flix-wrapper/lock.toml` differs per project.

| File | Role |
|---|---|
| `src/flix` | POSIX `sh` shim: find a `java`, prefer the cached compiled stage 0, else source-launch |
| `src/flix.cmd` | same for `cmd.exe`/PowerShell |
| `src/flix.java` | **stage 0** — everything else, in one dependency-free file |

Stage 0 owns project discovery, lock parsing, drift detection, version validation, Java
selection, compiler acquisition, unconditional SHA-256 verification, verb dispatch, wrapper
verbs, and process launch. The shims own exactly one decision each (which `java`) plus one
cache lookup; keep it that way — logic added to a shim has to be written twice and cannot be
unit-tested.

The one thing a shim also reads is the selected JDK's `release` file, and it uses that for
nothing except declining the compiled stage 0 when the JVM is below `MIN_JAVA`. That is not
a Java *policy* decision — stage 0 still owns every diagnostic — it is the shim refusing to
`exec` a class the JVM cannot load, because `exec` leaves no way back. The floor therefore
appears in `MIN_JAVA` and in both shims, and `tests/lint.sh` fails if the three disagree.

### The shims exist twice

`src/flix` and `src/flix.cmd` are the checked-in copies of the `SHIM` and `CMD` text blocks
in `src/flix.java`, which is what `install` actually writes out (`CMD` with CRLF). **Edit both
sides or they drift.** In the Java text block, backslashes are escaped (`\\`); on disk they
are literal.

### Cache layout is a versioned interface

The shim must know where the compiled stage 0 lives, so these paths are contract, not detail:

```
<cache>/stage0/<sha256 of flix.java>/flix.class   # self-compiled stage 0 (~131ms vs ~532ms)
<cache>/compilers/flix-<version>-<sha256>.jar     # content-addressed compiler
<cache>/verbs/<digest|override-…>.verbs           # captured `flix --help` verb set
```

`<cache>` = `FLIX_CACHE_HOME`, else `$LOCALAPPDATA\flixw` / `~/Library/Caches/flixw` /
`${XDG_CACHE_HOME:-~/.cache}/flixw`. Verb records live under `<cache>/verbs/`, never beside
the JAR — a content-addressed compiler directory may legitimately be read-only, and a
`FLIX_JAR` override points at a JAR flixw does not own.

### Dispatch is compiler-first

Order in `realMain` (paper §4.8): `--wrapper-*` flags → `install` (first contact only) →
drift check → `./flix -- args` forced pass-through → verb in the captured compiler verb set →
verb in `WRAPPER_VERBS` (`pin doctor setup validate update-wrapper`) → otherwise the compiler,
so Flix owns unknown-command diagnostics. Wrapper verbs therefore retire *automatically*, one
at a time, as Flix implements them; a displaced verb prints a deprecation notice.
`FLIX_BACKEND=wrapper|compiler` forces a side during a transition.

### Invariants that are load-bearing

These come from the paper's prototype contract (§5) and are easy to break accidentally:

- **Stock compiler only.** Never patch, wrap, or link against `flix.jar`; it is an opaque
  process. `FLIX_JAR` overrides are announced as unverified and are not compatibility evidence.
- **Digest every run.** The cached JAR is re-hashed on every invocation (~105ms on 33MB);
  no install stamps, no skip flag.
- **One acquisition attempt, one relaunch.** No retry loops; relaunch is guarded by
  `FLIXW_RELAUNCHED` so a stale `release` file cannot loop.
- **Drift fails before the network.** `flix.toml` is the human authority; a mismatch with
  `lock.toml` is fatal — except that `pin`, `doctor` and `validate` still run so the project
  can be repaired.
- **stdout belongs to the compiler.** All wrapper chatter goes to stderr; cwd, argv and all
  three streams are inherited so the REPL keeps its TTY.
- **Failures are `FLIXWnnn` on stderr.** `FLIXW001`–`009` are fatal (advisory exits 80–88);
  `FLIXW010`/`011` are printed and never set exit status. Numeric codes are advisory because a
  user program may return the same integer.
- **Degrade, don't brick.** Verb capture is an optimisation: an unparseable `--help` falls back
  to `BUILTIN_VERBS`, an unwritable cache stays silent, a missing `javac` stays on the source path.
- **Single file, no dependencies, Java 21.** No preview features, no JBang, no shebang tricks.
  `MIN_JAVA` is fatal; above `TESTED_CEILING` warns unless `FLIXW_STRICT_JAVA=1`.

### Known rough edges

- `lock.toml` and `flix.toml` are read by `tomlScan()`, a hand-written table-aware
  scanner, not a conforming TOML parser. It handles tables, comments, multi-line strings and
  duplicate rejection, and fails closed on anything else. `pin`'s rewrite goes through the
  same scanner — it used to have its own, which read different keys — so reader and writer
  cannot drift apart. It agrees with `tomllib` on all 95 manifests in `tests/corpus/`, which
  answers paper §7.2 question 3 for real input but not for exotic-but-legal TOML.
- Rule 5 routes unknown verbs to the compiler so future verbs and filenames keep working,
  but Flix has no unknown-command diagnostic: `flix doctro` answers
  `Unrecognized file extension: 'doctro'.` on **stdout**, exit 1. The routing is right; the
  resulting message is not good, and improving it is an open UX question.
- `flix.cmd` has a CI job but no results: the repository has no remote, so no workflow has
  ever run. Argument parity with the POSIX shim is not achievable and is not claimed; see
  `docs/LIMITATIONS.md`.
- The design paper is Revision 6 and now trails the implementation in places. `docs/CONTRACT.md`
  is the accurate description of what ships; the paper is kept as historical evidence.

## Conventions

- Comments explain *why a cheaper option was rejected*, not what the line does. Match that
  density — this file is meant to be audited by strangers who must trust it with a download.
- Diagnostics are actionable: state what was found, what was expected, and the command that
  repairs it (`run: ./flix pin <version>`).
- Commits are Conventional Commits (`feat:`, `docs:`, `refactor:`, `chore:`).
