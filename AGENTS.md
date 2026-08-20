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
java src/stage0/flixw.java wrapper --version         # offline; no project, lock, or network needed
java src/stage0/flixw.java wrapper --help            # routing table (enriched if run inside a project)
java src/stage0/flixw.java wrapper --schema          # the JSON Schema for lock.toml, on stdout
java src/stage0/flixw.java completion bash # a TAB-completion script, on stdout
javac -d /tmp/flixw-out src/stage0/flixw.java        # compile check
FLIXW_TRACE=1 ./flixw check                   # per-phase timings on stderr
```

Exercising it end to end means installing into a scratch project:

```sh
java src/assets/flixw-setup.java setup /tmp/proj src/stage0/flixw.java   # the four project files
cd /tmp/proj && ./flixw pin 0.75.3         # writes .flixw/lock.toml, downloads the JAR
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
| `src/stage0/flixw` | POSIX `sh` shim: find a `java`, prefer the cached compiled stage 0, else source-launch |
| `src/stage0/flixw.cmd` | same for `cmd.exe`/PowerShell |
| `src/stage0/flixw.java` | **stage 0** — everything else, in one dependency-free file |

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

`src/stage0/flixw` and `src/stage0/flixw.cmd` are the checked-in copies of the `SHIM` and `CMD` text blocks
in `src/stage0/flixw.java`, which is what `install` actually writes out (`CMD` with CRLF). **Edit both
sides or they drift.** In the Java text block, backslashes are escaped (`\\`); on disk they
are literal.

### Two JVM forks, only one of which was a smell

Stage 0 used to start a second JVM to run its own companion assets, and now loads them
in-process through an isolated class loader. Stage 0 still starts a second JVM to run the
compiler, and always will. **These are not the same decision, and the second is not the
first left half-done.**

An asset is flixw's own code, from flixw's own release, under flixw's own digest — and
stage 0 is already a JVM, so a second one bought nothing a class loader did not give for
free (see the figures above).

`flix.jar` is somebody else's program, and the invariant below says it stays opaque: never
patched, wrapped, or linked against. In-processing it would merge flixw with the compiler —
one heap and one set of GC flags, shared shutdown hooks, a `System.exit` on either side
killing both, and a compiler crash surfacing as a flixw stack trace. "Stock compiler only"
would stop being a claim a reader could check.

So the fork stays for now, and `awaitWithReaper` with it: stage 0 cannot replace itself
with the compiler, and must stay resident for the child's whole life.

#### One process, postponed until Java 22 is ordinary for Flix

The goal is **behavioural, not about memory**: `./flixw <verb>` should *be* the compiler or
the plugin, so that signals, job control, exit status and what `ps` and `pkill` see all
describe the thing actually running. The resident stage 0 costs 22.6 MB against a compiler's
825 — a plugin run stacks three JVMs at 23.9 + 24.1 + 776.8 — and that ratio is not the
case for changing anything. `SIGKILL` to stage 0 orphaning the compiler is, since it is the
one failure `awaitWithReaper` documents and cannot fix.

"Java has no `exec(2)`" stopped being true in Java 22. `java.lang.foreign` reaches `execv`,
and it genuinely replaces the image — same pid, arguments delivered verbatim with no shell
requoting anywhere:

```
[java]   pid=88596 about to execv
[execed] pid=88596 argv=sh-arg0 extra!arg
```

**Decided: postponed until Java 22+ is standard for Flix.** Not rejected, and not blocked on
anything unknown — the trigger is that Flix's own floor moves, at which point this is worth
doing. What follows is the working out, so the next person does not repeat it.

The obstacle is *not* which JVM runs stage 0. flixw already steers that: `.flixw/local/java`
takes precedence over `PATH`, and `wrapper --install-jdk <feature>` can provision any
release. The obstacle is that `src/stage0/flixw.java` must go on **compiling** at
`--release MIN_JAVA`, because it must still run on a user's own Java 21, and `Linker` is a
preview API there. So the FFM code cannot live in stage 0 at all. It belongs in a companion
asset compiled at 22+, loaded in-process by the class loader assets already use — verified
to work, replacing the whole process including stage 0.

Provisioning a JDK to obtain this was considered and declined as a default. It reverses a
decision recorded above (an automatic fetch is the wrong answer to a missing dependency),
and it inverts the shims' precedence, where a flixw-installed JDK is deliberately the last
resort rather than the first choice. Worse, it would make *semantics* depend on whether a
JDK happened to be provisioned — one machine where Ctrl-C behaves and one where it does
not — which is a poor trade in a change whose entire purpose is behaviour. Gate it on the
running JVM's feature version instead; `--install-jdk 25` is then one way to get there
rather than the mechanism.

Three things are prerequisites, none of them optional:

- **Both shims must pass `--enable-native-access=ALL-UNNAMED`.** Without it every run prints
  four `WARNING:` lines to stderr -- flixw's diagnostic channel -- ending with "will be
  blocked in a future release". Java 21 accepts the flag silently, so it can be passed
  unconditionally rather than gated on a version the shim would have to work out.
- **The plugin context file must be deleted before the exec, not by a shutdown hook.** The
  hook is correct *today*, because `System.exit` runs hooks; `execv` runs nothing.
- **stdout and stderr must be flushed first**, since buffered output dies with the image.

Windows never gets this: there is no `execv`, and `cmd.exe` has no `exec` either. So the
fork path and `awaitWithReaper` survive regardless, and this is **additive** -- a second
path, not a replacement. That is the strongest argument against ever doing it, and it is
the one the Java version moving does not weaken.

The earlier proposal here -- have the *shim* exec the compiler on a plan stage 0 hands back
-- is **superseded**. It required handing argv to a shell, as either a string the shim
`eval`s (stage 0 emitting shell code, where a filename containing a quote becomes
execution) or a plan file POSIX `sh` cannot read, having no arrays and no NUL-delimited
`read`; and it put a new protocol in the two files that own one decision each. Doing it
inside the JVM needs none of that.

### Cache layout is a versioned interface

The shim must know where the compiled stage 0 lives, so these paths are contract, not detail:

```
<cache>/stage0/<sha256 of flixw.java>/flixw.class   # self-compiled stage 0 (~96ms vs ~739ms)
<cache>/assets/<sha256 of asset source>/*.class    # compiled companion assets (~63ms vs ~402ms)
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

`LOCK_SCHEMA` in `src/stage0/flixw.java` is a list of `LockField` — table, key, required, pattern,
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
java src/stage0/flixw.java wrapper --schema > docs/schema/lock-v1.schema.json
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
| `src/assets/flixw-help.java` | `help [<topic>]`, `completion <shell>` | the static completer answers before `findRoot`, same as `--schema`/`--version` |
| `src/assets/flixw-jdk.java` | `wrapper --install-jdk` | runs on a machine that may have no usable Java at all |
| `src/assets/flixw-setup.java` | run directly as the bootstrap; `doctor --fix` | it *is* the entry point — the project has no stage 0 yet |

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

`src/assets/flixw-jdk.java` carries a constraint the completion asset does not, and it is the
reason the two are separate files rather than one. Stage 0 runs a companion asset
**in the JVM it is itself running on**, through an isolated class loader, and the
provisioner exists precisely for the machine whose only JVM is below `MIN_JAVA` — so it must compile and run at
`SOURCE_FLOOR`, not `MIN_JAVA`. A Java 21 construct in it would make the provisioner
unrunnable in the one case it is for, silently. `tests/lint.sh` compiles it at
`--release SOURCE_FLOOR` for exactly that reason.

Discovery is *not* provisioning and stayed in stage 0: `installedJdk`, `findJavaUnder` and
`knownInstalls` run on every invocation to *find* a JDK the asset installed earlier, and
must not require fetching the asset to do it.

**`FLIXW_PLUGIN_CACHE` is where a plugin may keep derived data**, at
`<cache>/plugin-cache/<name>/`. It exists because a plugin that computes something expensive
had nowhere to put it, and the obvious place is wrong: `plugins/<name>/` is enumerated by
`plugin list` as the set of installed *versions*, so a cache directory there is reported as a
version that cannot be run. Without a named place, every plugin that needs one invents its own
corner of the cache and none of them are ever collected.

flixw promises the path and promises `--purge` collects it. It promises nothing about the
contents — keying and invalidation are the plugin's, which is the only place they can be
correct. The directory is not created eagerly, so a plugin that never writes leaves no trace,
and it is not keyed by plugin version: an upgrade that changes what a plugin derives should say
so in its own keys, where version-keying the directory would instead orphan the old data
silently on every upgrade.

`plugin remove` deletes it with the plugin, so removal cannot be the thing that manufactures
orphans. `--purge` offers orphans — data whose plugin is no longer installed — *without*
consulting a usage marker, because the marker belonged to the plugin and went with it; routing
them through the ordinary age rule would retain them for ever as "never seen used". Data
belonging to an installed plugin is never touched: deleting it would be a silent cache
invalidation the plugin has no way to learn about.

Adding this did not move `FLIXW_ABI_VERSION`, which is the point of an additive-only ABI: a
plugin that has never heard of the variable is unaffected, and one that wants it reads an
environment variable.

The ABI a plugin receives is deliberately small and additive-only (`FLIXW_ABI_VERSION=1`):
a flat environment-variable tier for the common case (`FLIXW_PROJECT_ROOT`,
`FLIXW_CACHE_HOME`, `FLIXW_COMPILER_*`, `FLIXW_JAVA_HOME`, `FLIXW_PLUGIN_*` — including
`FLIXW_PLUGIN_CACHE`, below), plus
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

### Completion is generated from one picocli model

`./flixw completion bash|zsh|fish|pwsh` prints a completer built from a picocli
`CommandSpec`: the pinned compiler's commands with their own descriptions, the wrapper verbs
they have not displaced, and the compiler's options with value-taking ones marked. `help`
renders that same tree — `tree()` in `src/assets/flixw-help.java` builds it once — so a completion
cannot disagree with the help screen on the same terminal.

bash and zsh come from picocli's own `AutoComplete`, one script serving both. fish and
PowerShell are flixw walking the same model, because picocli generates neither. `cmd.exe`
has no per-command completion mechanism at all and never will.

**The script is a snapshot, and that is a reversal.** Earlier releases emitted a static
script that was byte-identical across projects and read its candidates at TAB time from
`.flixw/local/verbs`, so it never went stale at the next `pin`. It also could not carry a
description or an option, because a note cannot hold them. The trade was made deliberately
in the other direction: regenerate after a re-pin, and get what a completion is actually
for. The note machinery, `recordVerbs`, `recordCompletion` and the `<cache>/verbs/*.compl`
capture went with it — 63 lines of stage 0 whose only consumer had gone.

**Generating needs no project.** It is dispatched before `findRoot`, like `wrapper
--schema`: the script is what somebody runs while setting up a shell, routinely before any
flixw project exists, so requiring one would make the setup step depend on having finished
the setup. Inside a project nothing is acquired or launched either — the verb set is read
from the cache record, so a project whose compiler is not cached still gets a working
script. The word is deliberately *not* in `WRAPPER_VERBS`: it is answered before that table
is consulted, so listing it would advertise a route that never runs, and if a pinned
compiler ever claims `completion` the early path stands aside for compiler-first dispatch.

**Never vendor picocli's source.** It arrives as a verified release asset; nothing in this
repository contains a copy of it.

### What ships is not what you read

`src/stage0/flixw.java` is the documented source. What a release publishes, and what every
adopting project commits as `.flixw/flixw.java`, is that file with its commentary removed
— generated by `tests/strip.java` in `tests/pack.sh`, never committed:

```sh
java tests/strip.java src/stage0/flixw.java 0.25.3 "stage 0" > flixw.java   # 4733 -> 3310 lines
```

Every companion asset ships the same way, with its own name as the third argument.

A third of stage 0 is prose written for whoever audits flixw itself, and that reader is on
the website or in this repository — both named in the header the stripper writes, which is
the only comment that survives. The vendored copy exists to be executed and digest-checked,
in somebody else's repository, where it is someone else's diff.

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

#### Why it stops at comments and a two-space indent

Because the rest is worth almost nothing, and the measurement is the answer rather than the
taste. The vendored file lives in git, which stores objects zlib-compressed, and GitHub
serves it `Content-Encoding: gzip` — so what a project actually pays is the compressed size:

| | raw | gzipped |
|---|---:|---:|
| as shipped | 136480 | 33,059 |
| strip all remaining indentation | 120,029 | 31,394 |
| …and every blank line | 119,673 | 31,225 |

**All further minification is 1.8 KB compressed, about 5%.** Indentation is the most
compressible thing in the file, so removing it is doing badly what the compressor already
does well — and it costs the readability of a file that *is* read, as a diff in somebody's
repository after an upgrade. The same measurement deflates the two-space change itself: an
11% raw saving was ~1.5 KB compressed. What actually paid was removing the comments, prose
compressing far worse than repeated leading spaces.

Two options below this line are excluded on grounds that are not about size at all.
**Obfuscation** contradicts the product: the claim is "read it before you trust it with a
download", and the README publishes a digest so a reader can. **A third-party minifier**
would be the first build dependency in a project whose pitch is zero dependencies, and
would make the reproducibility claim depend on pinning someone else's tool and version —
`tests/strip.java` is 130 lines, pure, in-repo, and validated by running the entire suite
against its output.

If the vendored file needs to be smaller, the lever is **less code**, which the ratchet
below already tracks. It is not fewer characters.

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
is the broken thing. `src/assets/flixw-help.java` is the shape to copy.

`tests/lint.sh` holds that with three numbers, all ceilings **at today's value** rather
than at the target, so the gate is green on the way down instead of red until the last
commit:

| Gate | today | target |
|---|---:|---:|
| code lines in `src/stage0/flixw.java` | 3023 | 2900 |
| comment density | 32% | ≥25% floor |
| bytes | 275687 | 225000 |

These are what `tests/lint.sh` enforces, and the two must be changed in the same commit:
a ratchet the repository publishes and CI does not is worse than no ratchet, because the
number a reader checks against is then the one nothing is holding. The code-line ceiling
last moved for `help`, which needed to keep the compiler's own help text rather than throw
it away after parsing verbs out of it.

The first cut against these was JDK provisioning, out to `src/assets/flixw-jdk.java`: 132 code
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
`src/assets/flixw-setup.java`, 428 code lines out of stage 0. It passes the self-contained
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

The gates measure `src/stage0/flixw.java`, the documented source — not the stripped file that
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
  `completion` has no `cmd` target and never will. PowerShell users are served by
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
