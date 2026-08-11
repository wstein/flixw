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

The wrapper files have the same shape of problem one level up: published hashes protect a
release you already have, not the first copy you obtained.

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

`flix.cmd` is written, lint-checked, and covered by a CI job that installs into a scratch
project and runs `pin`, `check`, `run` and `validate` through it under `cmd.exe`, plus the
full suite under Git Bash and the shim once from PowerShell. That job now runs on every
push and passes. `flix-invaders` also runs the POSIX shim under Git Bash on
`windows-latest`, launching the game in a real window.

Two gaps remain behind that green tick. Four suite cases cannot exist on Windows and are
reported as skipped, not passed. And the `cmd.exe` trampoline has no *field* coverage: the
one real project using flixw drives it from Git Bash, so `flix.cmd` is exercised only by
flixw's own smoke job. Specific risks:

- `cmd.exe` transforms arguments before any script sees them. `%VAR%` is expanded at
  parse time and cannot be recovered, `!` is destroyed under delayed expansion, and `^`
  and quoting do not round-trip through `%*`. Byte-exact argument parity with the POSIX
  shim is **not** achievable and is not claimed.
- Ctrl-C in `cmd.exe` prints `Terminate batch job (Y/N)?` and may leave the child
  running.
- The `certutil` hash used to find the compiled stage 0 is best-effort; if it fails the
  shim falls back to the source launch, which is slower but correct.

`java .flix-wrapper\flix.java <args>` is the lossless fallback on Windows: it needs no
shim, no shell, and no execution policy. Git Bash also works, and is present on every
GitHub Windows runner, which is how `wstein/flix-invaders` exercises the wrapper there.

There is deliberately no PowerShell script. A Group-Policy `MachinePolicy` execution
policy cannot be overridden by `-ExecutionPolicy Bypass`, so a `.ps1` can be
administratively unrunnable in exactly the corporate environments where it would matter.

## Help introspection is not a contract

Compiler-first dispatch needs to know which verbs the pinned compiler implements, and the
only source is `flix --help`, whose format is `scopt`'s renderer output. Nothing upstream
promises it. Flix 0.75.1 and 0.75.2 are byte-identical here apart from one experimental
option line, which is evidence about two adjacent patch releases and nothing more.

When parsing fails, stage 0 warns once with `FLIXW010` and uses a built-in table, so a
future reformat costs accuracy on one narrow question rather than bricking every pinned
project. Worst case the wrapper is one release stale about whether Flix has claimed a
wrapper verb — a fact that changes rarely and is announced in release notes.

A machine-readable command list from upstream would remove this entirely.

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
to install Temurin on this OS, and a note that `./flix --wrapper-install-jdk` will manage
one for you once any Java 21+ exists. The offer itself therefore only reaches you when a
*too old* Java exists, not when none does.

Afterwards it is no longer true. A JDK flixw installed is recorded in
`<cache>/jdks/default`, and the shims read that when `PATH`, `JAVA_HOME` and
`FLIX_JAVA_HOME` all come up empty — so a machine with no system Java at all still
compiles and runs, on flixw's own JDK. That is verified: with `PATH` stripped of every
`java`, `./flix run` builds and runs the program.

**A `java` that is not a JVM still defeats it.** macOS ships `/usr/bin/java` as a stub that
exists, is executable, and only prints *"Unable to locate a Java Runtime"*. The shim
cannot distinguish that from a real one without running it, so it is used and the stub's
own message is what you see. Setting `JAVA_HOME`, or removing the stub from `PATH`, is the
way out.

**It never prompts where nobody can answer.** In CI, in a pipe, or in a git hook a prompt
is not a question, it is a hang; so a non-terminal stdin, or `CI` in the environment, gets
the instructions and a failure instead. `FLIXW_INSTALL_JDK=1` opts in ahead of time for
scripted setup, and `./flix --wrapper-install-jdk` does it on demand.

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
per command. Setting `JAVA_HOME` to the real JDK restores it.

**Below Java 15 the diagnostic degrades badly.** Stage 0 is a single source file compiled
at launch by whatever JVM the shim found, and it uses text blocks and records. On Java 17
this works and you get a clean `FLIXW004`; on Java 11 you get a wall of `javac` errors
starting with `unclosed string literal`, because the file cannot be compiled before it can
report anything. Nothing in a single-file bootstrap can fix that — the diagnostic would
have to be a second, older-syntax file — so it is stated instead.

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
above is covered only by 17 hand-written adversarial cases, and remains the honest gap.

## The compiled stage 0 in the cache is executed on trust

The shims run whatever class sits at `<cache>/stage0/<sha256 of flix.java>/flix.class`.
The path is keyed by the hash of the *source*, not of the class, so anyone who can write
that directory can run code as you.

That is the same trust boundary as the rest of the user cache — and as `~/.cache`
generally — but this entry is executable, which makes it a more attractive target than a
JAR that gets digest-verified before use. Stage 0 narrows the directory's permissions to
the owner where the platform allows it. If your cache directory is shared, group-writable,
or on a network filesystem other people can write to, set `FLIX_CACHE_HOME` to somewhere
private.

Verifying the class in the shim would need a hash of the compiled output in a file the
shim also has to trust, which moves the problem rather than solving it.

## No field evidence

This wrapper has been exercised by a 83-case regression suite — one of those cases being
347 unit assertions over a corpus of real manifests — against one compiler release, on
Linux, macOS and Windows.

Since 2026-08-11 it has also run in one real project.
[`flix-invaders`](https://github.com/wstein/flix-invaders) replaced its hand-written
downloader with `./flix` outright: its CI type-checks, tests and formats through the
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
