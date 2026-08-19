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
[Flix](https://flix.dev) compiler: `./flixw <verb>` downloads, digest-verifies, caches and
executes an **unmodified stock `flix.jar`** pinned by the project. It is not a Flix fork,
plugin, or official tool.

`docs/Flix_Bootstrap_Wrapper_Paper.md` (design paper, Revision 6) is the normative spec.
Appendix A is pseudocode for `realMain`, Appendix B the evaluation matrix, Appendix C the
diagnostic table. Change behaviour there and in code together, or say explicitly which one
you are deliberately letting lead.

## Commands

The wrapper has no build system — it is one Java 21 source file, run via JEP 330.

```sh
java src/flixw.java wrapper --version         # offline; no project, lock, or network needed
java src/flixw.java wrapper --help            # routing table (enriched if run inside a project)
java src/flixw.java wrapper --schema          # the JSON Schema for lock.toml, on stdout
java src/flixw.java wrapper --completion bash # a TAB-completion script, on stdout
javac -d /tmp/flixw-out src/flixw.java        # compile check
FLIXW_TRACE=1 ./flixw check                   # per-phase timings on stderr
```

Exercising it end to end means installing into a scratch project:

```sh
java src/flixw-setup.java setup /tmp/proj src/flixw.java   # the four project files
cd /tmp/proj && ./flixw pin 0.75.2         # writes .flixw/lock.toml, downloads the JAR
./flixw pin wstein/flix-fork 0.75.2+fork.1 # a fork build; the repository is recorded in the lock
./flixw pin --refresh                      # rewrite the lock in this release's shape; offline
./flixw info                               # java, compiler, cache, mirror, proxy, routing state
./flixw doctor [--fix]                     # the same, plus every check, with a verdict
./flixw validate                           # wrapper files, lock/manifest agreement, git tracked status
./flixw plugin install <name> <ver> <url>  # fetch, digest-verify, cache; .jar, .java or .flix
./flixw plugin <name> [args...]            # run an installed plugin, re-verified every time
./flixw task [<name> [args...]]            # .flixw/tasks.toml's aliases, never fetched
```

A release ships the same three files as two archives plus `flixw.java`; `sh tests/pack.sh
<dir>` builds them by running `install` into a staging directory and packing the result, so
the archive route cannot drift from the install route. `.github/workflows/release.yaml`
runs it on a `v*` tag and refuses to publish if the tag and `WRAPPER_VERSION` disagree.

What a release publishes is `flixw.java` (stripped — see "What ships is not what you read")
plus the three companion assets, and **no `flix.java`**. That name was stage 0's until
0.19.1, and the bridge that kept those installations upgradeable is gone: the rename
shipped a week after the first release and nothing had adopted the wrapper before it, so
the bridge and the installer's matching rename migration served nobody.

The same tag publishes <https://wstein.github.io/flixw/> — the landing page, the lock
schema and the stage 0 API docs. `sh tests/pages.sh <dir>` builds the whole site, so what a
tag publishes can be inspected before it is; CI builds it on every commit, because the tag
is a bad moment to discover the docs do not build. `.github/workflows/pages.yaml` runs it,
checking the tag against `WRAPPER_VERSION` exactly as the release does: locks in other
people's repositories already point at the schema URL this serves.

The repository's configured checks, both required before a commit:

```sh
sh tests/lint.sh    # javac -Werror, shellcheck, shim parity, schema parity/permanence, javadoc, CRLF, size
sh tests/run.sh     # 293-case regression suite; one ~32MB download on a cold cache
```

`tests/UnitCheck.java` is compiled against stage 0 and run from `tests/run.sh` as one of
those cases. It reaches what the shell cannot: the manifest scanner over
`tests/corpus/`, the `pin` rewrite as a property over the same corpus, 36 adversarial
manifests, JDK selection and discovery in stage 0 plus the provisioner asset's own
metadata parsing and platform coordinates, pin targets, verb capture against both help
renderers, 23 lock fixtures and the lock schema against the hand-written validators, and
the bounds on `runCapture`, and the four completion scripts with the note they read —
406 assertions in total. Refresh the corpus with
`sh tests/fetch-corpus.sh`; see `tests/corpus/README.md` before changing it.

`tests/schema/` holds locks filed under the verdict they are supposed to get: `valid/`,
`invalid/`, `semantic/` (well-formed and still wrong — the checks a regex cannot make) and
`advisory/` (the schema rejects, stage 0 warns and runs). `UnitCheck` walks all four
through `readLock`; the `schema` job in CI runs taplo over the first two and over
`advisory/`, which is the only outside evidence that the published file is a working JSON
Schema. Adding a case means adding a file; nothing enumerates them by name. See
`tests/schema/README.md`.

`tests/run.sh` builds every fixture it needs under `tests/.work/`, its gitignored scratch
space: two JDK stand-ins, a JAR whose `--help` cannot be parsed, a JAR that sleeps, and a
git-initialised scratch project. Nothing binary is committed. Four cases cannot exist on
Windows — three need a runnable fake `bin/java.exe`, one needs a POSIX signal — and are
reported as `skip` rather than asserted for the wrong reason.

## Architecture

Four artifacts ship into a consuming project, byte-identical across projects for a given
wrapper release; only `.flixw/lock.toml` differs per project.

| File | Role |
|---|---|
| `src/flixw` | POSIX `sh` shim: find a `java`, prefer the cached compiled stage 0, else source-launch |
| `src/flixw.cmd` | same for `cmd.exe`/PowerShell |
| `src/flixw.java` | **stage 0** — everything else, in one dependency-free file |

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

There is exactly one subprocess in a shim, and it is guarded three ways: when the version
is *unknown* (no `release` file — a version-manager shim), and Java was not named
explicitly, and a JDK flixw installed is recorded, the shim runs `java -version` once. Below
Java 15 the JVM cannot compile stage 0 at all, so without this the user gets `javac` noise
instead of `FLIXW003` and the JDK flixw installed for that exact case is never reached. The
guards are what keep it off every other run.

Whatever the marker at `<cache>/jdks/default` names, both shims execute it, so both require
it to be *inside* `<cache>/jdks/` — prefix **and** no `..`, since `…/jdks/../../evil` has
the right prefix. That is a guardrail, not the security boundary: the boundary is who can
write the cache, which `doctor` checks.

### The shims exist twice

`src/flixw` and `src/flixw.cmd` are the checked-in copies of the `SHIM` and `CMD` text blocks
in `src/flixw.java`, which is what `install` actually writes out (`CMD` with CRLF). **Edit both
sides or they drift.** In the Java text block, backslashes are escaped (`\\`); on disk they
are literal.

### Cache layout is a versioned interface

The shim must know where the compiled stage 0 lives, so these paths are contract, not detail:

```
<cache>/stage0/<sha256 of flixw.java>/flixw.class   # self-compiled stage 0 (~131ms vs ~532ms)
<cache>/compilers/flix-<version>-<sha256>.jar     # content-addressed compiler
<cache>/verbs/<digest|override-…>.verbs           # captured `flix --help` verb set
<cache>/verbs/<digest|override-…>.compl           # the compiler's own completer, if it has one
<cache>/verbs/<digest>.pin                        # the repo and exact tag last pinned as
```

`<cache>` = `FLIX_CACHE_HOME`, else `$LOCALAPPDATA\flixw` / `~/Library/Caches/flixw` /
`${XDG_CACHE_HOME:-~/.cache}/flixw`. Verb records live under `<cache>/verbs/`, never beside
the JAR — a content-addressed compiler directory may legitimately be read-only, and a
`FLIX_JAR` override points at a JAR flixw does not own.

### The lock's shape is stated once

`LOCK_SCHEMA` in `src/flixw.java` is a list of `LockField` — table, key, required, pattern,
description — and it is the only place the lock format is written down. `lockText` writes
from it, `readLock` validates against it, and `wrapper --schema` renders it as the JSON
Schema published at `https://wstein.github.io/flixw/schema/lock-v1.schema.json`.
Every generated lock names that URL on its first line as a Taplo `#:schema` directive, so
an editor validates it with no per-project configuration. `pin --refresh` and `doctor --fix`
share one implementation — `refreshLock` — which rewrites the lock in this release's shape
from the values already in it: offline, and the pin does not move. It declines on a lock
that does not parse, one written by a newer flixw, or one carrying a key this release does
not read, since the rewrite is from the values read. `pin --refresh` prints which; `doctor
--fix` stays quiet, because there it is one item among several. A key the schema does not
describe is `FLIXW011` and never fatal — a lock is committed, so an unknown key is usually
a collaborator's newer flixw.

**A published schema URL is permanent.** Every generated lock names one on its first line
as a Taplo `#:schema` directive, and that lock is committed in somebody else's repository
for as long as they keep it. A schema that stops being served therefore does not break
flixw — it breaks the editor of a project that has already shipped, and nothing in a
version bump would notice: `pin --refresh` rewrites the *local* lock, and no CI anywhere
runs on a repository that has not upgraded. So `docs/schema/lock-v*.schema.json` is
append-only, `tests/pages.sh` publishes the whole glob rather than the current version, and
`tests/lint.sh` fails if `lock-v1.schema.json` disappears or if the publisher narrows to
one file. A lock-v2 that made an existing lock unreadable would be a migration to design,
not a rename to perform, and v1 would go on being served throughout it.

`docs/schema/lock-v1.schema.json` is the committed copy of that render; `tests/lint.sh`
diffs the two, so **regenerate it rather than editing it**:

```sh
java src/flixw.java wrapper --schema > docs/schema/lock-v1.schema.json
```

Patterns live in the intersection of Java's regex dialect and ECMA-262's — `String.matches`
compiles them on every run, an editor's JSON Schema validator compiles the published ones —
and carry no anchors, because Java implies them and JSON Schema does not. `LOCK_SCHEMA_VERSION`
is the *lock format's* major version, not the wrapper's: adding an optional key does not move
it, and a change that would make an existing lock unreadable does.

### Dispatch is compiler-first

Order in `realMain` (paper §4.8): `--wrapper-*` flags → `install` (first contact only) →
drift check → `./flixw -- args` forced pass-through → verb in the captured compiler verb set →
verb in `WRAPPER_VERBS` (`pin info doctor validate help plugin task`) → otherwise the
compiler, so Flix owns unknown-command diagnostics. Wrapper verbs therefore retire
*automatically*, one at a time, as Flix implements them; a displaced verb prints a
deprecation notice. `FLIX_BACKEND=wrapper|compiler` forces a side during a transition.
`plugin` and `task` are namespaces, not bare verbs — see below — so nothing under them is
subject to this retirement; only the two words `plugin` and `task` themselves are.

**Stage 0 has no install verb at all.** The bootstrap is `java flixw-setup.java`: the
installer is what somebody downloads, verifies and runs, and it fetches the stage 0 of its
own release. What has to be read before anything executes is therefore ~640 lines instead
of 3288 — both are named in the same `SHA256SUMS`, so verifying either establishes the
other, and the difference is only in what a person can actually finish reading.

`install` was a bare verb, then briefly `wrapper --install`, and is now neither. It is a
name Flix could claim for a project's dependencies, and stage 0 held it for an operation
that runs once, before the project exists — so `./flixw install` now reaches the compiler
like every other word flixw does not own. The bridge that kept published 0.20–0.24
wrappers upgradeable went with it, established the same way the `flix.java` drop was:
nothing had adopted them.

**Bare wrapper verbs are staying.** Moving them under `wrapper --*` would delete verb
capture, both help parsers, the `<cache>/verbs/*` records, `routingNotice` and the
deprecation path — about 220 code lines —
and it was weighed and declined: `./flixw doctor` reading like `gradlew doctor` is the
whole reason this CLI is nicer than the wrapper it is modelled on. The retirement
machinery is the price of the spelling, and it is being paid deliberately.

`pin` is the exception that already exists. It is dispatched *before* `selectJava`,
`acquire` and `verbs()` — it never consults the compiler's verb set, so it is not subject
to retirement at all. That is not an optimisation: `pin` is the documented repair for a
project that cannot reach a compiler, so it cannot be routed by asking a compiler.

### Plugins and tasks are a stable ABI, not more stage-0 commands

Two separate mechanisms, chosen deliberately over adding more builtin verbs: `./flixw
plugin <name>` runs machine-wide, digest-verified, explicitly-installed third-party code
(`.jar`, `.java` or `.flix`); `.flixw/tasks.toml` aliases a shell string the project already
trusts, the way `npm run` does, and is never fetched. Both are namespaced —
`plugin <name>` / `task <name>`, never a bare top-level verb — so a plugin can never
collide with a compiler verb, another plugin, or a future wrapper verb; only the words
`plugin` and `task` occupy the namespace stage 0 owns.

**`pin`, `info`, `doctor`, `validate` and `help` stay in stage 0 permanently — this is a
decided product boundary, not a temporary gap.** They are the trust-gate verbs a fresh
clone or CI needs before any plugin can be trusted at all: a `doctor` that was itself a
plugin could not diagnose a broken or missing plugin system, and a first `validate` on a
clean checkout cannot depend on a plugin being pre-installed to run at all. `pin` in
particular never moves — it creates the trust root (repository, version, digest) everything
else, plugins included, is verified against.

Two builtins were recorded as migration *candidates* onto this ABI. **Both have now
moved, and neither onto the plugin mechanism** — they are wrapper-owned companion assets,
fetched and cached the way `wrapper --upgrade` fetches `flixw.java` itself. `./flixw
plugin <name>` was the wrong destination for both for one reason: that dispatch always
requires a resolvable project root, and both of these have to answer without one.

| Asset | reached by | why not a plugin |
|---|---|---|
| `src/flixw-completion.java` | `wrapper --completion <shell>` | answered before `findRoot`, same as `--schema`/`--version` |
| `src/flixw-jdk.java` | `wrapper --install-jdk` | runs on a machine that may have no usable Java at all |
| `src/flixw-setup.java` | run directly as the bootstrap; `doctor --fix` | it *is* the entry point — the project has no stage 0 yet |

`ensureAsset(name, version)` fetches, verifies and caches any of them; see "Completion is
data, not a generated script" below for the shape, which is now shared. The version is a
parameter for one reason: `wrapper --upgrade` warms the assets of the release it is
upgrading *to*, from the stage 0 it is upgrading *from*.

**`wrapper --upgrade` warms them all**, so nothing needs the network on first use
afterwards. The set comes from the release's own `SHA256SUMS` — every `flixw-<name>.java`
in it — rather than from `COMPLETION_ASSET`/`JDK_ASSET` here, because the upgrade runs in
the *old* stage 0 and a compiled-in list would stop warming the day a fourth asset shipped,
silently. Best-effort and never fatal: the upgrade has already done its real work by then.

The JDK move also **stopped stage 0 provisioning automatically**. `noJavaFound` used to
prompt and then download inline; it is now a diagnostic and nothing else, because an
automatic network fetch is the wrong default answer to a missing dependency in a wrapper
whose whole argument is that it fetches only what a lock named and a digest confirmed.
Provisioning still exists, explicitly, as `./flixw wrapper --install-jdk`.

`src/flixw-jdk.java` carries a constraint the completion asset does not, and it is the
reason the two are separate files rather than one. Stage 0 source-launches a companion
asset **with the JVM it is itself running on**, and the provisioner exists precisely for
the machine whose only JVM is below `MIN_JAVA` — so it must compile and run at
`SOURCE_FLOOR`, not `MIN_JAVA`. A Java 21 construct in it would make the provisioner
unrunnable in the one case it is for, silently. `tests/lint.sh` compiles it at
`--release SOURCE_FLOOR` for exactly that reason.

Discovery is *not* provisioning and stayed in stage 0: `installedJdk`, `findJavaUnder` and
`knownInstalls` run on every invocation to *find* a JDK the asset installed earlier, and
must not require fetching the asset to do it.

The ABI a plugin receives is deliberately small and additive-only (`FLIXW_ABI_VERSION=1`):
a flat environment-variable tier for the common case (`FLIXW_PROJECT_ROOT`,
`FLIXW_CACHE_HOME`, `FLIXW_COMPILER_*`, `FLIXW_JAVA_HOME`, `FLIXW_PLUGIN_*`), plus
`FLIXW_CONTEXT` naming a per-invocation JSON file for anything structured — built once in
`pluginEnv`/`writeContextFile` for every format alike. `.flix` plugins get the same context
despite being unable to receive `args`: stock Flix has no `run <file>` mode, so the only way
to execute one standalone is `java -jar flix.jar plugin.flix`, where every extra word is
parsed as another source file rather than a program argument (verified against a real
compiler, not assumed) — but `Sys.Env.Env`/`Sys.Env.getVar` reads the same environment
variables every other format does, which is why the ABI's env tier, not `args`, is the one
channel every format can rely on. The context file is written fresh per invocation and
deleted by a JVM shutdown hook, not a `finally` — `runArtifact` ends in `System.exit`, which
skips `finally` blocks but not shutdown hooks.

A plugin's bytes are re-hashed on every invocation exactly like the compiler's — `pin`'s
own guarantee, not a lesser one, applied to `resolvePlugin` — and every invocation prints
that it is unaudited third-party code, not only the install that first fetched it. A
plugin name is a single path segment that reaches `<cache>/plugins/<name>/` before anything
else about it is read, so `validPluginName` (`[a-z][a-z0-9-]*`) is checked at install,
remove, invoke *and* lock-parse time — a lock is exactly as attacker-controlled as anything
else committed, and `plugin remove ..` without that check dead-reckons to
`<cache>/plugins/..`, the cache root.

### Completion is data, not a generated script

`wrapper --completion bash|zsh|fish|pwsh` prints a completer. The script is **static and
byte-identical across projects** — everything per-project is read at TAB time from
`.flixw/local/verbs`, the note stage 0 leaves next to the `local/java` note the shim
already reads. That is forced by dispatch: the candidate set is the compiler's verbs ∪ the
wrapper verbs it has not displaced, so it moves with the lock, and a script that baked the
verbs in would go stale at the next `pin` and say nothing. No note yet means a list baked
in at emission, the same bargain `BUILTIN_VERBS` makes.

**The generator itself is not in `flixw.java`.** The four `COMPL_*` templates and the
`render` that fills them in live in `src/flixw-completion.java`, source-launched exactly
like a `.java` plugin — but it is not a plugin: it is a wrapper-owned companion asset,
fetched from the flixw release matching this stage 0's own `WRAPPER_VERSION` (never
`releases/latest`, since a completion script generated by one release must be interpreted
by that same release's dispatch) and verified against that release's own `SHA256SUMS`,
exactly the trust footing `upgradeWrapper` already gives `flixw.java` itself (`digestFor`
is shared between the two). Verified once at fetch time, not on every call — there is no
local record of the expected digest the way a project's lock gives the compiler cache one
for free — so a sidecar `.sha256` file records what was checked, and every later call
re-verifies the cached bytes against *that*, offline, under
`<cache>/wrapper/assets/<WRAPPER_VERSION>/`. It is a pure function of `(shell, verbs)`,
declares no dependency on `WRAPPER_VERBS`/`BUILTIN_VERBS`, and needs none — stage 0 still
computes that union and passes it as an argument — which is what lets the cache entry live
forever until the next release moves it to a new, version-keyed path.

This keeps the JVM off the **TAB-press** path unchanged: a keypress still costs one file
read against `.flixw/local/verbs`, never a stage 0 launch. What changed is the *setup*
call: `wrapper --completion <shell>` was already one-time, explicit and already cost a JVM
launch; now its first call on a machine, for a given release, also needs network once, to
fetch and verify the generator. Every call after that — from any project, on that machine,
for that release — is an offline cache hit, the same shape `--install-jdk` already has.

The completers are **not installed into projects**. They are emitted on demand, so
`install`, `validate`, `doctor --fix` and the shim byte-parity lint are untouched, and the
"the shims exist twice" duplication does not grow. Do not add on-disk copies of them, and
do not add `src/flixw-completion.java`'s content back into `flixw.java` — the whole point
of the split is that it is committed into every adopting project's own repository, and the
templates no longer need to be.

Past the verb, the compiler owns the arguments. A picocli-based Flix answers
`generate-completion`; stage 0 caches that output and leaves the path in
`.flixw/local/completion`, and the bash completer delegates to it. **Detection is free and
needs no version sniffing**: picocli registers `generate-completion` as an ordinary
subcommand, so it arrives in the verb set `parseVerbs` already captures. Stock Flix is
scopt, never advertises it, and takes that path zero times.

flixw does not read, rewrite or splice the generated script — its internals are picocli's
business and move with picocli. The one line the completer reads, in shell and at TAB time,
is the `complete -F` registration every bash completion script must end with, so an
upstream rename costs filename completion for a release rather than a broken completer.
Splicing was the alternative and is worse than it looks: `parseVerbs` guessing wrong falls
back to a verb table, while a bad splice puts broken bash in someone's shell startup.
**Never vendor picocli to reach `AutoComplete`** — it is 20,350 lines against stage 0's
5,221, its generator describes `flix` rather than `./flixw`, and its output is static where
flixw's verb set is not.

Only bash delegates. zsh and fish cannot load a bash completion script — zsh not without
`bashcompinit` and a compatibility shim, fish not at all — and picocli generates neither a
fish nor a PowerShell completer, so those three complete verbs and hand the rest to the
shell's own file completion.

fish is the cheapest of the four to test: `complete -C` asks it for a command line's
candidates directly, which is what a keypress asks, so it needs no readline simulation. It
also matches on the command's **base name**, so one `complete -c flixw` covers `flixw`,
`./flixw` and an absolute path; bash matches the word as typed and needs both spellings
registered.

### What ships is not what you read

`src/flixw.java` is the documented source. What a release publishes, and what every
adopting project commits as `.flixw/flixw.java`, is that file with its commentary removed
— generated by `tests/strip.java` in `tests/pack.sh`, never committed:

```sh
java tests/strip.java src/flixw.java 0.25.0 > flixw.java   # 4678 -> 3288 lines, 255 -> 152 KB
```

A third of stage 0 is prose written for whoever audits flixw itself, and that reader is on
the website or in this repository — both named in the header the stripper writes, which is
the only comment that survives. The vendored copy exists to be executed and digest-checked,
in somebody else's repository, where it is 100 KB of someone else's diff.

**This makes the readable artifact and the running one different files, which is only
honest while anyone can regenerate the second from the first.** So the strip is a pure
function — no clock, no environment, nothing but the one file — and `tests/lint.sh` checks
that two runs agree byte for byte, that the result compiles at both `MIN_JAVA` and
`SOURCE_FLOOR`, that it renders the same lock schema, and that the header still names both
URLs. A `shipped` CI job runs the **whole suite** against the stripped tree, because the
claim is behavioural equivalence and compiling is not that: every other job tests the
documented file, so without it the artifact people actually run would be the one thing
here nothing exercises.

The scanner is a state machine, not a regex, and three things in this repository break the
naive version: `"https://…"` is not a line comment, an escaped-quote char literal is not
an unterminated one, and a comment that *mentions* a `"""` delimiter is not a text block —
read that as an opener and the rest of the file goes with it.

One consequence worth stating, because it removes a tension that was real: the
comment-density floor below now costs adopting projects **nothing**. Comments are free at
the point of delivery, so the pressure to write fewer of them to keep the vendored file
small is gone, and the floor and the line ceiling stop competing for the same budget.

### Size is a ratchet, not an aspiration

Stage 0 is shrinking toward a **verified launcher with a narrow plugin broker**: install,
pin, strict lock and manifest floor, Java selection and relaunch, atomic acquisition,
unconditional digest verification, launch, a minimal status, and the plugin broker. Rich
maintenance moves out to verified companion assets, not to `./flixw plugin` — that
dispatch requires a resolvable project root, so it cannot answer for a project whose lock
is the broken thing. `src/flixw-completion.java` is the shape to copy.

`tests/lint.sh` holds that with three numbers, all ceilings **at today's value** rather
than at the target, so the gate is green on the way down instead of red until the last
commit:

| Gate | today | target |
|---|---:|---:|
| code lines in `src/flixw.java` | 2893 | 2900 |
| comment density | 29% | ≥25% floor |
| bytes | 252357 | 225000 |

The first cut against these was JDK provisioning, out to `src/flixw-jdk.java`: 132 code
lines and 9.3 KB. It is also the honest shape of what "moving it out" costs — the asset is
~400 lines, because the 202 that moved brought ~200 of infrastructure with them (HTTP,
hashing, cache paths, `FLIXWnnn`) that stage 0 keeps for its own use. *Total* lines went
up; only stage 0 shrank, which is what the gate measures and what a file loaded on every
invocation should be judged by.

#### What detaches, and what does not

Provisioning detached because it is **self-contained work**: given a cache path it needs
nothing else stage 0 keeps. That is the test, and most of what is left fails it. Measured,
after the JDK move:

| candidate | leaves stage 0 | primitives it would have to duplicate | why |
|---|---:|---:|---|
| `listCache` (`info -v`) | 90 | 146 | `knownInstalls`, `probeVersion`, `installedJdk`, `probe` are all kept by `selectJava` |
| `check`/`report` | 165 | the same 146, plus lock and digest state | a *view over* what the verified chain already computed |
| gitattributes audit | 84 | — | `mergeGitattributes` is called by `install`; moving only the check splits one concern across two files |
| `upgradeWrapper` | 60 | ~110 | leans on `digestFor`, `download`, `sha256`, `httpGet` |
| lock-schema JSON renderer | 96 | none | the one clean seam left — but it makes `wrapper --schema` network-dependent and couples the lint gate to an asset fixture, for 96 lines |

The install cluster *was* extractable, and went: `SHIM`, `CMD`, `install`,
`updateWrapper`, the templates and `mergeGitattributes` are
`src/flixw-setup.java`, 428 code lines out of stage 0. It passes the self-contained
test — given a target directory it writes files and needs nothing stage 0 computes — and
the one thing that looked like a counter-example was solved rather than accepted: stage 0
keeps `SHIM_SHA256`/`CMD_SHA256`, so `validate` and `doctor` still detect a drifted shim
**offline, on a cold cache, with no fetch**. Only repair reaches for the asset. That is
the shape to copy when something looks stuck: keep the *judgement* resident and move the
*bytes*.

#### The test that keeps being got wrong

Twice now a candidate was scored low by asking *"does it share primitives with stage 0?"*
and stopping at yes. That is the wrong question, and it was wrong both times.

The right one is: **can the judgement stay resident while the bulk leaves?** The installer
looked unmovable because `validate` compares shims against `SHIM`/`CMD` — until stage 0
kept two SHA-256 constants and 428 lines left. `info --verbose` looked unmovable because
listing JDKs needs `knownInstalls`/`probeVersion`/`probe` — until you notice `selectJava`
already enumerates every candidate before choosing one, so stage 0 holds the list and can
simply hand it over.

So a read-only inspection asset (`flixw-inspect.java`) is viable on one condition: it
**receives gathered state and never re-gathers**. Passing the resolved JDK candidates,
lock and compiler status, cache root and plugin/asset summaries costs ~20 lines and moves
~90. Letting it rescan would cost 128 lines of duplicated primitives *and* create a second
source of JDK policy — the one shown in `info` would be the one that never runs during
selection, free to disagree with the one that does.

The same condition applies to any deep-audit asset: whichever of them owns cache walking,
the other calls it, or the duplication returns through a side door.

**Rich maintenance is not extractable the way provisioning was** — as a whole. Its
*gathering* is not extractable; its *rendering* is. `info`, `doctor` and
`validate` are views over state the verified chain computes anyway, so moving them
relocates the presentation and duplicates the gathering. A companion asset earns its
keep when it removes work, not when it removes a rendering of work that still happens.

#### The target is not reachable by extraction alone

The 2400/2650 figures came from a keep-set that assumed rich maintenance would move *and*
that the lock reader would narrow. Neither holds. What each lever is actually worth,
measured against today's 3368:

| lever | lands at | status |
|---|---:|---|
| the install cluster → `flixw-setup.java` | **2915** | **done** |
| + rich maintenance, duplicating 146 lines of discovery | ~2600 | available, and a poor trade |
| + retiring bare wrapper verbs | ~2380 | **declined** (see "Dispatch is compiler-first") |
| + a narrow lock-v2 reader | ~2170 | **declined** (lock-v1 is served indefinitely) |

The 2650 figure silently assumed both declined decisions and a keep-set that has since
been measured rather than estimated. Extraction is now essentially exhausted: what remains
is a view over state the verified chain computes, and moving it would duplicate the
gathering while relocating only the presentation.

The gates measure `src/flixw.java`, the documented source — not the stripped file that
ships, which is a function of it. The code-line count is identical in both by construction;
only the byte and density numbers differ, and those describe what a *maintainer* reads.

The line gate counts **code** lines — blanks and comment-only lines excluded — and the
density floor pulls the other way on purpose. A gate on *physical* lines is a gate on
comments, and the comments are the security story: a *physical*-line gate at any of the
targets above sits below the zero-comment floor of the code it would have to keep, so such
a gate can only be met by deleting the reasons. Text blocks count as code; the shims embed shell `case`
arms starting with `*` and `/*`, so a leading-token classifier would let the density floor
be met by shipping more embedded shell.

The three numbers must stay arithmetically compatible, and the byte target is *derived*
from the other two rather than chosen. At the measured 52 bytes per code line and 69 per
comment line, 2900 code lines at a 25% density is 4142 physical lines and ~225 KB — so
**a 100 KB target is unreachable**, being below even the zero-comment cost of 2900 code
lines (152 KB). When one number moves, re-derive the others rather than picking a round
one: this target was briefly set to 208 KB by hand, which no combination of the other two
could have produced.

Lowering a ceiling is a deliberate edit and belongs in the commit that earned it; lint
prints a `note` when the slack passes 100 lines or 5 KB rather than failing, because the
commit that happens to trip a threshold is rarely the commit that should own the change.

The **byte** ceiling may move up when the code-line count moves down and density moves up:
that is the two gates pulling against each other as intended. Refusing it would let them
deadlock, since any change trading code for the explanation this file asks for would then
pass neither.

The **code-line** ceiling may rise only for a *new capability*, and the commit that raises
it must name the capability. Never for a refactor, a rewrite, or a helper something needed
— those are the shapes drift arrives in. A gate that cannot tell a feature from drift is
one that gets deleted the first time somebody has to ship a feature, so it distinguishes
them and makes the author say which this is.

### Invariants that are load-bearing

These come from the paper's prototype contract (§5) and are easy to break accidentally:

- **Stock compiler only.** Never patch, wrap, or link against `flix.jar`; it is an opaque
  process. `FLIX_JAR` overrides are announced as unverified and are not compatibility evidence.
  An override *disagreeing* with the lock is the ordinary case — that is what it is for — so
  it is reported in `info`/`doctor`, not per run. An override pointing **inside
  `<cache>/compilers/`** is different and always wrong: those names carry the digest, so a
  re-pin changes them and the override silently goes on naming the superseded artifact. That
  is `FLIXW010` on every run, because nothing else can catch it — the digest guard is off by
  definition, and the version check passes since two builds of one release share a canonical
  version.
- **Digest every run.** The cached JAR is re-hashed on every invocation (~105ms on 33MB);
  no install stamps, no skip flag.
- **The digest says which bytes, not which release.** Nothing tied those bytes to the
  version the lock names, so a mislabelled asset was pinned and run in silence. The
  compiler's own header is the second opinion, and `pin` is where it is asked for:
  `captureReportedVersion` runs the JAR once and records the answer as
  `[compiler].reported_version`, beside the digest that vouches for it afterwards. Every
  run re-hashes those bytes anyway, so a digest that still matches is a version that still
  matches — a per-run capture would re-derive what the digest already proves, at the cost
  of a subprocess and a cache file. The *comparison* is free once both strings are in the
  lock and so stays per-run: `FLIXW010` when `canonical()` disagrees, `FAIL` in `validate`,
  a note in `info`/`doctor` when only build metadata differs. That is what catches a lock
  edited or merged after `pin` wrote it, which pin-time checking cannot see. Metadata is
  *not* a mismatch: a compiler built from `0.75.3+stable.names.3` reporting `0.75.3` is
  agreeing, and warning per run on every fork would train the reader to skip the line that
  matters. A lock predating the key has no second opinion and says so rather than claiming
  agreement; `pin --refresh` and `doctor --fix` backfill it from the cached JAR — offline,
  and only from bytes that still hash to what the lock pins, so an unverified JAR's
  self-description can never be laundered into the lock. `FLIX_JAR` is outside the
  guarantee by construction: those bytes are not the locked ones and the lock never
  described them, which is `reportOverrideGap`'s job.
- **One acquisition attempt, one relaunch.** No retry loops; relaunch is guarded by
  `FLIXW_RELAUNCHED` so a stale `release` file cannot loop.
- **The manifest is a floor, checked before the network.** `flix.toml`'s `flix` key is
  Flix's field with Flix's rules (`x.x.x` only), read as a *minimum*; `lock.toml` is flixw's
  and pins the exact compiler. `pin` therefore takes `v0.75.2` as readily as `0.75.2` — the
  tag is what GitHub shows and what flixw builds to reach the asset — while the manifest
  does not, because a tag there is a manifest Flix itself rejects. A lock below the floor is fatal — except that `pin`, `doctor`
  and `validate` still run so the project can be repaired. A lock *above* it is normal, and
  `pin` never edits the manifest.
- **stdout belongs to the compiler.** All wrapper chatter goes to stderr; cwd, argv and all
  three streams are inherited so the REPL keeps its TTY.
- **Failures are `FLIXWnnn` on stderr.** `FLIXW001`–`009` are fatal (advisory exits 80–88);
  `FLIXW010`/`011` are printed and never set exit status. Numeric codes are advisory because a
  user program may return the same integer.
- **Degrade, don't brick.** Verb capture is an optimisation: an unparseable `--help` falls back
  to `BUILTIN_VERBS`, an unwritable cache stays silent, a missing `javac` stays on the source path.
- **Two help renderers, three parses.** `parseVerbs` reads scopt's layout (stock Flix: one
  `Usage: flix [a|b|c]` line, one `Command: a` line per verb) *and* picocli's (a fork: that
  bracket wrapped across lines, verbs in an indented `Commands:` block). A parser for one
  finds zero candidates in the other. Parsing is deliberately split from the subprocess so
  `tests/UnitCheck.java` can assert both layouts without a JAR.
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
- `flixw.cmd`'s `windows smoke` job runs green on every push, and that is a smoke test, not
  parity: argument parity with the POSIX shim is not achievable and is not claimed. See
  `docs/LIMITATIONS.md`.
- `cmd.exe` has no per-command completion mechanism at all — not a limited one, none — so
  `wrapper --completion` has no `cmd` target and never will. PowerShell users are served by
  the `pwsh` script, which registers against the existing `flixw.cmd`; **the trampoline does
  not move to a `.ps1`**, because a `.ps1` is not invokable as a bare command from `cmd.exe`
  or a build tool and a Group-Policy execution policy can make one administratively
  unrunnable. The pwsh registration is verified against real `pwsh` by hand, not in CI.
- GitHub Pages needs two pieces of repository configuration that no workflow can supply and
  no failure message names: the site must exist with Actions as its source, and the
  `github-pages` environment must allow `v*` **tags**, since GitHub creates it allowing the
  default branch alone. Without the second, a tag builds the site, uploads it, and is then
  refused at the deploy step. Both commands are in the header of `pages.yaml`.
- The design paper is Revision 6 and now trails the implementation in places. `docs/CONTRACT.md`
  is the accurate description of what ships; the paper is kept as historical evidence.

## Conventions

- Comments explain *why a cheaper option was rejected*, not what the line does. Match that
  density — this file is meant to be audited by strangers who must trust it with a download.
- Doc comments are published, so they are also checked. `tests/lint.sh` runs
  `javadoc -private -Xdoclint:all,-missing -Xwerror`: a malformed comment is a lint
  failure, because on a rendered page it silently swallows the text around it rather than
  showing a warning. Angle brackets are the usual cause — `<version>`, `<owner>/<repo>` and
  `<home>` all read as HTML tags — so write those inside `{@code …}`. The `missing` group
  is deliberately off: `@param` and `@return` on every package-private helper is the
  what-the-line-does documentation the rule above rejects.
- Diagnostics are actionable: state what was found, what was expected, and the command that
  repairs it (`run: ./flixw pin <version>`).
- Commits are Conventional Commits (`feat:`, `docs:`, `refactor:`, `chore:`).
