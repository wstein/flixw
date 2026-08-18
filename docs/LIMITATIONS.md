# Known limitations

Stated plainly, because a tool that asks you to trust it with a download does not get to
be vague about what it cannot do.

## The pin is trust-on-first-generation, not authenticity

`flix pin` records the SHA-256 that GitHub reports for the release asset, and every later
run verifies the cached JAR against it. That defends against truncation, cache
corruption, a proxy substituting the file, and silent tampering after the pin.

It does **not** establish that the bytes came from the Flix team. GitHub serves both the
asset and the digest over the same TLS trust anchor, and a release asset can be deleted
and re-uploaded under the same tag. If the first generation was compromised, the pin
faithfully preserves the compromise.

Fixing this needs something only upstream can publish: a checksum file alongside the
release, or a signed build-provenance attestation. Until then, review the digest in the
lock the way you would review any other dependency pin — it is a committed, diffable,
git-blameable line, which is the most this design can offer.

Pinning a fork is supported and verified the same way, and means something different.
`./flixw pin <owner>/<repo> <version>` records the repository in the lock, so the source is
visible in review and a later bare re-pin cannot quietly move the project back to stock.
What it cannot do is make a fork build evidence about the stock compiler: this project's
compatibility claims are about unmodified releases from `flix/flix`, and a run against
anything else says nothing about those. `doctor` labels it, and `pin` says so once.

The wrapper files have the same shape of problem one level up: published hashes protect a
release you already have, not the first copy you obtained.

## No persistent local-compiler override

`FLIX_JAR` is the only way to run a compiler flixw did not download, and it is an
environment variable on purpose. It dies with the shell, so its blast radius is one
terminal session.

That has real costs, and they are not hypothetical. It does not reach an editor-spawned
`flixw lsp`, because a GUI editor never passes through a shell prompt. `.envrc` covers the
POSIX-terminal case, but needs direnv installed and allowed per clone, and has no
`cmd.exe` or PowerShell equivalent — so on Windows there is no project-scoped answer at
all. The `.envrc.example` that `install` writes narrows the gap; it does not close it.

A persistent per-project override — a `wrapper --dev-jar` verb writing a gitignored marker
under `.flixw/local/` — was designed in full and rejected. The reason is not that it is hard;
it is about eight lines of resolution plus a `doctor` line. It is that flixw sells exactly
one property, *the jar you run is the jar in the lock*, and every affordance for not doing
that turns the sentence into one with a footnote. An override you have to type is a
decision; one that persists in a file is a state you can forget you are in, and the failure
mode — debugging a compiler bug for a week against a jar from `~/Downloads` that predates
two releases — is expensive and quiet.

The counter-argument was good enough to record rather than bury: an override flixw
*represents* is one `doctor` can report, and an override hidden in a VS Code settings blob
is invisible to every diagnostic flixw has. Visibility beats prohibition. It was not
accepted, on the grounds that the population it helps is small and the property it costs is
the whole product — but if the editor-launch case turns out to be common in practice, this
is the argument that should reopen the decision, and reopening it is legitimate.

Also rejected, and for narrower reasons: `pin --local` (a machine-specific absolute path in
a file that is committed and reviewed is a lock that is true on one machine); a top-level
`./flixw dev` verb (compiler-first dispatch would silently reassign it the day Flix ships a
`dev` verb of its own); and any expiry or time-to-live on a dev mode (a tool whose behaviour
changes because you were on holiday is worse than the problem). Out of scope in the same
breath: `--dev-classpath`, `--dev-src`, rebuild watching, and relaxing either the lock
requirement or the manifest floor for override runs.

## SIGKILL to stage 0 orphans the compiler

Java has no `exec(2)`. Stage 0 therefore spawns the compiler and stays resident for its
whole life, which has three consequences:

- **Resident cost.** Stage 0 holds roughly 25–50 MB while the compiler runs, against a
  compiler that peaks near 1 GB. On a memory-capped CI container this is a real line
  item, if a small one.
- **SIGTERM is handled.** A shutdown hook destroys and reaps the child, so terminating
  stage 0 does not leave a compiler running. This is tested.
- **SIGKILL is not, and cannot be.** `kill -9` on stage 0 runs no hook, and the compiler
  is orphaned. No Java code can prevent this. If you script around `flixw`, prefer
  `SIGTERM`.

Ctrl-C is fine: both processes are in the terminal's foreground process group, so the
signal reaches the compiler directly.

## Windows is covered, with two gaps

`flixw.cmd` is written, lint-checked, and covered by a CI job that installs into a scratch
project and runs `pin`, `check`, `run` and `validate` through it under `cmd.exe`, plus the
full suite under Git Bash and the shim once from PowerShell. That job now runs on every
push and passes. `flix-invaders` also runs the POSIX shim under Git Bash on
`windows-latest`, launching the game in a real window.

Two gaps remain behind that green tick. Four suite cases cannot exist on Windows and are
reported as skipped, not passed. And the `cmd.exe` trampoline has no *field* coverage: the
one real project using flixw drives it from Git Bash, so `flixw.cmd` is exercised only by
flixw's own smoke job. Specific risks:

- `cmd.exe` transforms arguments before any script sees them. `%VAR%` is expanded at
  parse time and cannot be recovered, `!` is destroyed under delayed expansion, and `^`
  and quoting do not round-trip through `%*`. Byte-exact argument parity with the POSIX
  shim is **not** achievable and is not claimed.
- Ctrl-C in `cmd.exe` prints `Terminate batch job (Y/N)?` and may leave the child
  running.
- The `certutil` hash used to find the compiled stage 0 is best-effort; if it fails the
  shim falls back to the source launch, which is slower but correct.
- Three decisions in `flixw.cmd` have **no** automated coverage on Windows: choosing the
  JDK named by `<cache>\jdks\default`, refusing a marker that points outside that
  directory, and falling back to it when the `java` on `PATH` is below the floor. The
  POSIX suite tests all three and skips them on Windows, because each needs a fake
  `bin\java.exe` that is a real executable — a batch file will not do, and building a
  native stub in CI is a larger piece of work than the rest of the suite put together.
  The two shims are written and reviewed against each other line by line, which is the
  only assurance those paths currently have on Windows.

`java .flixw\flixw.java <args>` is the lossless fallback on Windows: it needs no
shim, no shell, and no execution policy. Git Bash also works, and is present on every
GitHub Windows runner, which is how `wstein/flix-invaders` exercises the wrapper there.

There is deliberately no PowerShell script. A Group-Policy `MachinePolicy` execution
policy cannot be overridden by `-ExecutionPolicy Bypass`, so a `.ps1` can be
administratively unrunnable in exactly the corporate environments where it would matter.

That reasoning is about the *shim*, and it does not change for completion.
`./flixw wrapper --completion pwsh` prints a `Register-ArgumentCompleter` block for the
user's own `$PROFILE`; it registers against the `flixw.cmd` that already ships, because
PowerShell completes native commands including batch files. Nothing moves to a `.ps1`. If
an execution policy stops `$PROFILE` from loading, the completer does not load either —
but neither does anything else in that profile, which is a property of the machine rather
than of flixw.

`cmd.exe` gets no completion at all, and this is an absence in `cmd.exe` rather than a gap
here: it has no per-command completion hook. `doskey` does not provide one, and the
`CompletionChar` registry setting completes filenames only. Nothing flixw could ship would
change that.

The `pwsh` completer is verified by hand against a real PowerShell — the registration does
fire for `./flixw`, `flixw` and `flixw.cmd`, since PowerShell resolves the command name
from the path — but it is not exercised in CI, so it carries the same field-coverage
caveat as the `cmd.exe` trampoline above.

## Help introspection is not a contract

Compiler-first dispatch needs to know which verbs the pinned compiler implements, and the
only source is `flix --help`. Nothing upstream promises its format. Flix 0.75.1 and 0.75.2
are byte-identical here apart from one experimental option line, which is evidence about
two adjacent patch releases and nothing more.

That the format is not a contract is no longer hypothetical. Two renderers are now in
circulation and they share no layout:

- **scopt**, which stock Flix uses — the verb list is one long `Usage: flix [a|b|c]` line,
  and every verb repeats as its own `Command: a` line.
- **picocli**, which a fork uses — the same bracket wraps across several lines, and the
  per-verb lines are replaced by one indented `Commands:` block.

A parser written for the first finds *nothing* in the second: not a degraded set, zero
candidates. So flixw reads both, by three independent parses, and any one of them reaching
three verbs is enough. Adding a third renderer would need the same treatment; that is the
cost of introspecting output meant for humans.

When every parse fails, stage 0 warns once with `FLIXW010` and uses a built-in table, so a
future reformat costs accuracy on one narrow question rather than bricking every pinned
project. That fallback is genuinely narrow but not harmless: against a fork it means the
wrapper knows only the stock 0.75.x verbs, so anything the fork added stops being
recognised as a compiler verb. It still *runs* — rule 5 sends unknown verbs to the compiler
anyway — but the verb no longer displaces a wrapper verb of the same name, and `info` and
`--help` under-report what the compiler can do.

A machine-readable command list from upstream would remove this entirely. The fork's
`capabilities` verb is a step in that direction.

## JDK provisioning is opt-in, and cannot bootstrap from nothing

`flixw` finds a Java. When it finds none it prints what to type on your OS, and — only if
you say yes — downloads one: Eclipse Temurin at `MIN_JAVA`, verified against the SHA-256
Adoptium publishes for that exact package, unpacked into `<cache>/jdks/`.

Temurin is the only vendor flixw fetches, and the instructions it prints name the same
one. It is vendor-neutral rather than tied to a single cloud's ecosystem, TCK-verified
under GPLv2 with the Classpath Exception so it is usable commercially without further
conditions, and its API publishes a per-package SHA-256 — which is the part that decides
it, because it lets a JDK be verified the way the compiler is. Other TCK-verified builds
are equally legitimate; flixw simply does not choose between them for you, and will happily
*use* any of them that is already installed.

Three limits are worth knowing.

**It cannot bootstrap from no Java at all — the first time.** Stage 0 is a Java program,
so something must be able to run it before flixw can fetch anything. With no `java`
anywhere, the shim exits before stage 0 starts, and what you get is its own message: how
to install Temurin on this OS, and a note that `./flixw wrapper --install-jdk` manages one
for you once any Java 16 or newer is reachable — 16 being what stage 0 itself compiles at,
not what the compiler needs. The offer therefore reaches you when a *too old* Java exists,
and not when none does.

Afterwards it is no longer true. A JDK flixw installed is recorded in
`<cache>/jdks/default`, and the shims read that when `PATH`, `JAVA_HOME` and
`FLIX_JAVA_HOME` all come up empty — so a machine with no system Java at all still
compiles and runs, on flixw's own JDK. That is verified: with `PATH` stripped of every
`java`, `./flixw run` builds and runs the program.

A `java` below the floor no longer hides it either: when `PATH` resolves one under Java
21 and flixw has a JDK of its own recorded, the shim prefers the recorded one, because
below Java 15 stage 0 cannot be compiled at all and nothing it knows would ever be
reached. An *explicitly* set `FLIX_JAVA_HOME` or `JAVA_HOME` is never substituted this
way — those fail loudly, as the contract says.

**A `java` that is not a JVM still defeats it.** macOS ships `/usr/bin/java` as a stub that
exists, is executable, and only prints *"Unable to locate a Java Runtime"*. The shim
cannot distinguish that from a real one without running it, so it is used and the stub's
own message is what you see. Setting `JAVA_HOME`, or removing the stub from `PATH`, is the
way out.

**It never prompts where nobody can answer.** In CI, in a pipe, or in a git hook a prompt
is not a question, it is a hang; so a non-terminal stdin, or `CI` in the environment, gets
the instructions and a failure instead. `FLIXW_INSTALL_JDK=1` opts in ahead of time for
scripted setup, and `./flixw wrapper --install-jdk` does it on demand.

**The Windows install has been reasoned about, not run.** Unpacking there is a zip read
by `java.util.zip` inside stage 0 — not `tar`, `Expand-Archive` or any external tool, so it
needs nothing installed beyond the Java already running. The real Adoptium Windows archive
was extracted and inspected during development: 577 entries, correct layout, `java.exe`
where it belongs. What could not be exercised off Windows is `Files.isExecutable` on a
`.exe`, so that check is no longer relied on there — Adoptium builds the Windows zip on a
Unix machine, entries carry mode 0770, and `java.util.zip` discards it, so every file
lands 0644 and an executable-bit test would have found no JDK at all.

**The digest is Adoptium's, over Adoptium's TLS.** Same shape as the compiler pin: it
defends against a corrupted or truncated download and against a mirror substituting bytes,
not against Adoptium itself. It is trust-on-first-use, and it is one more supply chain than
this tool had yesterday — which is why it happens only when asked.

Delegating instead to Coursier (`cs java`) or JBang remains the alternative, and both do
more than this does.

It looks in the directories JDKs are normally unpacked into, including the version
managers that hold them when the OS does not know about them at all — SDKMAN, asdf, mise,
jenv, Gradle's provisioned JDKs, Homebrew on both architectures, and Scoop on Windows.
It deliberately does not shell out to `java_home`, `update-alternatives`, `dpkg`, `rpm`,
`scoop` or `choco`: on this author's macOS machine `/usr/libexec/java_home -V` reports no
Java at all while five Homebrew JDKs are installed and one of them is running the wrapper,
`update-alternatives --config` is interactive and wants root, and the package managers
answer with package names rather than paths.

Discovery only runs when neither `FLIX_JAVA_HOME` nor `JAVA_HOME` is set and the JVM
already running is unusable; an explicit setting is always obeyed and fails loudly rather
than being quietly improved upon. It also does not shell out to `update-alternatives
--list`, which unlike `--config` is non-interactive and needs no root: what it returns on
Debian already lives under `/usr/lib/jvm`, so it would add a subprocess to a path that is
deliberately free of them for candidates that are already found.

**A `java` that is a shim script costs you the fast path.** `asdf`, `mise` and `jenv`
install `java` as a script rather than a symlink into a JDK, so there is no `release` file
beside it and the shim cannot tell which version it is. Since it cannot tell, it declines
the compiled stage 0 and launches the source instead — correct, and about 400 ms slower
per command. Setting `JAVA_HOME` to the real JDK restores it. If flixw installed a JDK of
its own, one `java -version` decides between them instead; that costs about 100 ms and only
happens when a recorded JDK exists to switch to.

**Below Java 15 the diagnostic degrades badly.** Stage 0 is a single source file compiled
at launch by whatever JVM the shim found, and it uses text blocks and records. On Java 17
this works and you get a clean `FLIXW004`; on Java 11 you get a wall of `javac` errors
starting with `unclosed string literal`, because the file cannot be compiled before it can
report anything. Nothing in a single-file bootstrap can fix that — the diagnostic would
have to be a second, older-syntax file — so it is stated instead.

## The trust-gate verbs live in stage 0 permanently, and there is no second artifact for them

`pin`, `info`, `doctor`, `validate` and `help` are implemented inside `src/flixw.java` and
always will be. This is a decided boundary, not a temporary gap: they are what a fresh
clone or CI needs before anything else — including a plugin — can be trusted, so moving
any of them out from under stage 0 recreates the exact chicken-and-egg problem a plugin
system exists to avoid. `pin` in particular never moves; it creates the trust root
everything else, plugins included, is verified against.

Past those five, `./flixw plugin <name>` (machine-wide, digest-verified, explicitly
installed `.jar`/`.java`/`.flix` code) and `.flixw/tasks.toml` (a project's own shell
aliases, never fetched) are how the wrapper extends without adding more stage-0 commands —
see [CONTRACT.md](CONTRACT.md#plugins-and-tasks).

TAB-completion generation has since moved out of `src/flixw.java`, but not onto the plugin
mechanism above: `wrapper --completion` has to keep working with no project in scope, which
`./flixw plugin <name>` cannot (that dispatch always needs a resolvable project root). It is
instead a wrapper-owned companion asset (`src/flixw-completion.java`), fetched from the
matching flixw release and verified against that release's own `SHA256SUMS` — the same
trust footing `wrapper --upgrade` already gives `flixw.java` itself — then cached. The
honest cost, stated plainly the way this file's other entries are: `wrapper --completion`
used to need no network at all; now its first call on a machine, for a given release, does.
Every call after that is an offline cache hit, same as the compiler and JDK caches. JDK
provisioning remains a migration *candidate* only, and is harder either way it might move —
it is reached from inside Java selection's own automatic fallback rather than only through
explicit dispatch.

The design paper separately describes an optional services JAR that could hold *stage-0's
own* verbs if the surface outgrew a single auditable file — a different question from
plugins, which are third-party and explicitly installed rather than part of the wrapper's
own trust base. A directory stood reserved for the services-JAR idea for a while, holding a
build definition and no sources. It is gone: it built nothing, shipped nothing, and its
version pins would have been stale by the time anything needed them, while the sbt ignore
rules it justified had already hidden a stale build tree at the repository root for the
project's whole life.

The reasoning it existed to preserve is worth keeping, so here it is. A second *stage-0*
artifact becomes justified only when stage 0 can no longer be read end to end in one
sitting, or when the trust-gate verbs need a dependency the JDK does not provide. It is not
free: it means a second release pipeline, a second digest to publish, a second
security-response path and a second trust-on-first-use anchor — for exactly the class of
artifact this project exists to verify. That cost is worth accepting deliberately and late,
and reviving a three-file directory from this paragraph is ten minutes' work if the day
comes.

## A plugin's digest proves its bytes, not its safety

`./flixw plugin install` and every later `./flixw plugin <name>` re-verify the cached
artifact's SHA-256, exactly as the compiler pin does. That defends against truncation,
cache corruption and tampering after install — it says nothing about whether the code was
safe to run in the first place. flixw does not sandbox a plugin, inspect what it does, or
distinguish a plugin that reads `FLIXW_CONTEXT` from one that does something else entirely
with the same process privileges this user has. The unaudited-third-party-code warning on
every invocation is the whole mitigation; treat installing a plugin the way you would treat
adding any other unreviewed dependency to a build.

A `.flix` plugin cannot receive command-line arguments. Stock Flix has no `run <file>`
mode — the only way to execute a standalone `.flix` file is `java -jar flix.jar
plugin.flix`, where every extra word is parsed as one more source file to compile rather
than a program argument. Such a plugin still receives the full ABI through environment
variables and `FLIXW_CONTEXT`, which is why those, not `args`, are the channel every format
can rely on — but a `.flix` plugin wanting positional arguments has no path to them until
Flix's own CLI grows one; `.jar` or `.java` is the workaround today.

`.flixw/tasks.toml`'s Windows argument quoting (`cmdQuote`) is best-effort, not byte-exact,
for the same reason the shim's own Windows argument handling is not claimed to be — see
"Windows is covered, with two gaps" above. A task argument containing an embedded double
quote survives; one relying on `cmd.exe`'s more exotic parsing rules (caret escaping inside
quotes, delayed expansion) may not.

## The manifest is not read by a TOML parser

`flix.toml` and the lock are read by a small hand-written scanner, because stage 0 has no
dependencies by design. It is table-aware, comment-aware and multi-line-string-aware, so
the obvious failures are covered and tested: a `flix` key in another table is ignored, a
decoy assignment inside a `"""` block is ignored, a trailing comment is stripped, and a
duplicate `[package]` table or duplicate key is rejected rather than resolved. `pin`
rewrites only `[package].flix` and leaves every other table alone.

What it still is not is a conforming parser. Exotic-but-legal TOML — unusual escape
sequences, inline tables spanning constructs it does not model — may be misread or
refused. It fails closed where it can: a value it cannot classify inside the table it was
asked about is an error, not a guess.

The remaining mitigations are that both files are small, that the version is
grammar-validated after extraction, and that a wrong version fails at the digest rather
than silently running something else.

The corpus test that used to be listed here as future work now exists. `tests/corpus/`
holds 95 real `flix.toml` files from 76 public repositories, and `tests/UnitCheck.java`
requires the scanner to agree, file by file, with what python3's `tomllib` reads from each
one. It does, and repinning every one of them changes exactly one line. That measures the
scanner against manifests people actually publish — none of which, as it happens, contains
a multi-line string, a dotted key or a quoted table header. The exotic-but-legal input
above is covered only by 36 hand-written adversarial cases, and remains the honest gap.

The lock's published JSON Schema does not narrow that gap, and should not be read as
though it does. It describes the lock's *keys and values*, so an editor following the
`#:schema` line does parse the file with a conforming TOML implementation — which is a
real check on a hand-edited lock, and the reason the directive is written at all. It is
not a check flixw performs: stage 0 still reads the file with the same scanner, and a lock
this scanner misreads is misread whether or not something else validated it first. The
schema and the scanner are held to the same verdicts by `tests/schema/`, where 23 locks are
filed under the answer each is supposed to get and CI runs taplo over the rows that are
claims about the schema. That measures agreement on the inputs chosen; it does not make
the scanner conforming.

`pin` never rewrites a manifest by pattern. The scanner records where each value sits and
that span is replaced whole, because a regex could not do it safely: an escaped quote
inside the value stopped the character class early and produced `flix = "2.0.0"x"`, which
is not TOML at all. Replacing the span also repairs a value that was unquoted or
mis-quoted, which is what `pin` is for. Table headers fail closed too — trailing text after
`[package]` used to be dropped.

## The compiled stage 0 in the cache is executed on trust

The shims run whatever class sits at `<cache>/stage0/<sha256 of flixw.java>/flixw.class`.
The path is keyed by the hash of the *source*, not of the class, so anyone who can write
that directory can run code as you.

That is the same trust boundary as the rest of the user cache — and as `~/.cache`
generally — but this entry is executable, which makes it a more attractive target than a
JAR that gets digest-verified before use. Stage 0 narrows the directory's permissions to
the owner where the platform allows it. If your cache directory is shared, group-writable,
or on a network filesystem other people can write to, set `FLIX_CACHE_HOME` to somewhere
private.

The JDK cache is the same boundary with one extra guard. An unpacked JDK is verified
against Adoptium's digest when it is installed, and reused afterwards only if the note
flixw writes at that point is still present and still records the same archive — merely
containing a `bin/java` is not evidence, since any directory can. It is not re-hashed on
every run; the marker naming it is required to resolve, through symlinks, inside the cache.

Verifying the class in the shim would need a hash of the compiled output in a file the
shim also has to trust, which moves the problem rather than solving it.

## No field evidence

This wrapper has been exercised by a 121-case regression suite — one of those cases being
363 unit assertions over a corpus of real manifests — against one compiler release, on
Linux, macOS and Windows.

Since 2026-08-11 it has also run in one real project.
[`flix-invaders`](https://github.com/wstein/flix-invaders) replaced its hand-written
downloader with `./flixw` outright: its CI type-checks, tests and formats through the
wrapper, and its smoke job launches the game in a real window on Linux, macOS and Windows.
That is the first evidence from something other than a scratch fixture, and it produced
two findings the test suite could not. The project's `main` read an asset path relative to
the working directory, which its previous script had silently supplied by `cd`-ing to the
project root — the wrapper's refusal to move the caller is correct and is documented, but
it is a real migration cost. And its CI cache key hashed `flix.toml`, which does not name
the compiler bytes; keying on the lock is what the pin actually requires.

What is still missing is more important than what is there. It has not been run across the
real Flix release cadence — the adoption above is one compiler release, pinned to the
version the project already ran, so nothing has yet been *migrated*. It has not been run by
anyone other than its author, and one author adopting their own tool in their own project
is the weakest possible form of field evidence. `flix-invaders` keeps
[`docs/pin-lag.md`](https://github.com/wstein/flix-invaders/blob/main/docs/pin-lag.md), one
row per Flix release, precisely so the cost shows up as a number rather than an impression;
its baseline row records that the project adopted 0.75.2 on the day it shipped, *before*
pinning, which is the bar the experiment now has to clear.

Four of those cases cannot run on Windows and are reported as skipped rather than
quietly passing. Three drive a fake JDK — a lying `release` file over a `bin/java` that
delegates to the real one — which Windows would need as a genuine `java.exe`; a copied
`java.exe` resolves `java.home` from its own path and would find no `lib/modules`. The
fourth signals stage 0 with `SIGTERM`, which MSYS cannot deliver to a native JVM. The
behaviours themselves are OS-independent and are covered on the other two platforms.

Every claim in [CONTRACT.md](CONTRACT.md) is tested. None of them is *proven in the
field*, and the policy question the design paper raises — whether pinning helps or hurts
a project tracking a pre-1.0 language — cannot be answered by tests at all.
