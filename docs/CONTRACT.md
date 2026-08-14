# The contract

What `flixw` guarantees, as implemented and tested. Anything not listed here is not
promised. Every statement below is covered by a case in `tests/run.sh`.

## Files

Five files are committed into a consuming project, plus a block inside one the project
already owns. Four of the five are byte-identical across every project using a given
`flixw` release, so a single published hash validates them; only the lock differs per
project.

```text
flixw                 POSIX shim
flixw.cmd             cmd.exe shim
.flixw/flixw.java     stage 0
.flixw/lock.toml      the pin: version, URL, SHA-256
.flixw/.gitignore     keeps .flixw/local/ out of git
.flixw/local/java     the JDK this machine resolved to; NOT committed
```

`flixw install` also merges a marked block into `.gitattributes` — the sixth file, and the
only one flixw shares rather than owns — preserving unrelated rules. `flixw validate` compares the two shims byte for byte against the bytes this
release ships, reports stage 0's digest for comparison against the published release, and
fails if a later `.gitattributes` rule overrides the block — any rule matching one of the
five shipped paths, whether by wildcard or by naming it outright — or if more than one
flixw block exists, since git honours the last — gitattributes resolves by
last matching pattern, so an override silently un-pins the line endings the block exists
to fix. All five must be committed; `flixw validate` fails if a gitignore rule swallows one,
because a collaborator would then get a project that cannot bootstrap. `.flixw/local/` is
the exception that proves it: machine-specific, and ignored by the `.gitignore` flixw
ships for exactly that purpose.

**Installing from an archive.** A release also ships `flixw-<version>.tar.gz` and
`flixw-<version>.zip`, whose contents are byte-identical to what `install` writes and are
extracted at a project root. They carry the four invariant files and nothing else: the lock is yours to
generate, and `.gitattributes` is omitted because `install` *merges* its block into
whatever the project already has, while an archive can only overwrite. `./flixw doctor --fix` performs that merge afterwards, and
`./flixw validate` reports the block as missing if it was skipped. The executable bit on
`flixw` is carried by both archive formats.

## The pin

`.flixw/lock.toml` is the pin: it is flixw's own file, it is generated, and it
binds the tuple *(repository, version, distribution URL, SHA-256)* that decides which
compiler actually runs. `flix.toml` is the project's manifest and belongs to Flix; its
`[package].flix` key is Flix's field, with Flix's rules — a plain `x.x.x`, read here as a
*minimum* compiler version rather than an exact one.

```console
./flixw pin 0.75.2                                    # the stock compiler
./flixw pin wstein/flix-fork 0.75.2+fork.wstein.1     # a fork build
```

The two arguments are told apart by the slash, which a version can never contain, so their
order does not matter. An omitted repository means the one already in the lock, so a bare
re-pin stays where the project already is; naming one changes it. Upstream is resolved by
constructing the release URL, which has been stable for every release this wrapper has
seen and keeps `pin` independent of an API for the common case. Any other repository is probed for
its asset name, because nothing says a fork's asset is called `flix.jar`: one `HEAD` each
against `flix-<version>.jar` and `flix.jar`, and then a single download of whichever
exists. No API is involved — GitHub's is limited to sixty unauthenticated requests an
hour across the whole machine, which made `pin` fail with `403` on a tag that existed,
while release downloads carry no such limit.

A fork is verified exactly as the stock compiler is, and is **not** stock-compatibility
evidence. `doctor` names the source for that reason.

Only exact versions are accepted — no ranges, no wildcards, no `latest`. This is not a
temporary limitation. A digest identifies one immutable byte sequence, so a floating
version and a pinned digest cannot both be honoured; exactness is what makes the digest
mean anything.

`[package].flix` is Flix's own field, not flixw's, and Flix accepts only `x.x.x` there —
anything else is *"a Flix version number of the wrong length"*. It also accepts `99.99.99`
against a 0.75.2 compiler, so it reads as a coarse floor rather than a pin. The exact
version — a fork build, a prerelease — therefore lives in the lock, which is flixw's file
and can say so, and the floor is compared at `x.x.x`, because that is all the manifest is
able to express.

A pin may carry SemVer build metadata — `0.75.2+fork.wstein.1` — which the lock records
in full and which is stripped from the release tag and the cache coordinate. That
normalization is defined once, in `canonical()`, and used for every comparison. The
manifest never sees it: Flix accepts only `x.x.x` there, so a fork build is expressible in
the lock alone.

**The manifest sets a floor, and it is checked before the network.** If `flix.toml` asks
for a Flix newer than the lock pins, the compiler path stops immediately — before Java
selection, before any download, before the compiler is executed. A lock *at or above* the
floor is not an error and is the ordinary case: `flix = "0.70.0"` in the manifest with
`0.75.2` in the lock is a project that declares what it needs and runs something newer.
`pin`, `doctor`, `validate` and `wrapper --upgrade` still run when the floor is
unsatisfied, because otherwise the repair the diagnostic recommends is unreachable. The
same holds for a lock that does not *parse*: `pin` replaces it, while everything needing a
compiler still fails on it.

The lock is written through a same-directory temporary and an atomic rename, so a
termination or power loss mid-write leaves the previous file rather than half of a new one.
The previous contents are held first, and restored if anything after the write fails.

`pin` fills the compiler cache before it writes the lock, and treats every failure there as
nothing — the cache is an optimisation and the next run refills it.

**`pin` never writes `flix.toml`.** The exact compiler is flixw's business and lives in
flixw's file; the manifest's floor is the project's statement about what its sources need,
and moving it is a decision only a human should make. The floor check reads the manifest
through the same table- and multi-line-string-aware scanner used everywhere else, so a
`flix = "…"` sitting inside a `"""` description is invisible to it.

### The lock schema

The lock's shape is published as a JSON Schema:

```
https://wstein.github.io/flixw/schema/lock-v1.schema.json
```

`v1` is the *lock format's* major version, not the wrapper's. It moves only when a lock an
older flixw wrote would stop being readable; adding an optional key is not that, and does
not move it.

The schema is not a second description of the lock kept alongside the code — it is
rendered from the same list stage 0 validates against, and `./flixw wrapper --schema`
prints it. That command is offline, needs no project and touches nothing, so a build that
validates locks can carry its own copy of the schema instead of fetching one:

```console
./flixw wrapper --schema > lock.schema.json
```

Every generated lock names it on the first line:

```toml
#:schema https://wstein.github.io/flixw/schema/lock-v1.schema.json
```

That is Taplo's directive, honoured by `taplo` and by the Even Better TOML extension, so
an editor validates the lock with nothing configured per project. Nothing about the build
depends on the line: `validate` reports a lock without one as `warn`, not as a failure,
and `./flixw doctor --fix` adds it. That repair is offline and changes the file's form
rather than its meaning — same repository, version, URL, digest and java pin — which is
why it exists at all, since `pin` would re-download the compiler to write one comment.

**A key the schema does not describe is advisory, both ways.** Stage 0 prints `FLIXW011`,
names the key, and carries on; it never sets exit status. The ordinary way to meet an
unknown key is a lock written by a *newer* flixw — the lock is committed, so that is every
collaborator who has not upgraded — and refusing to run would turn a forward-compatible
file into a broken project. The schema is stricter, because there the reader is a person
editing the file by hand and a warning is what they want. `doctor --fix` declines to
rewrite a lock carrying such a key, and declines to rewrite one written by a newer flixw
at all: the rewrite is from the values it read, so anything it did not read would be
deleted by the command that had just called it harmless.

Every key below is what the schema declares. `[compiler]` is required; within it,
`version`, `url` and `sha256` are required and `repo` is not. `wrapperVersion` records the
release that last wrote the file. `[java]` is optional entirely.

| key | required | value |
|---|---|---|
| `wrapperVersion` | no | the flixw release that last wrote this lock |
| `[compiler] repo` | no | `owner/repository`; absent means the stock one |
| `[compiler] version` | yes | an exact version: `x.y.z`, optionally with prerelease and build metadata |
| `[compiler] url` | yes | the `https` URL the JAR is downloaded from |
| `[compiler] sha256` | yes | that JAR's SHA-256, 64 lowercase hex digits |
| `[java] version` | no | a feature release (`21`) or an exact one (`21.0.12`) |

### The java pin

`[java] version` in the lock says which Java runs the compiler, and is optional. Absent,
the selection picks the newest tested JDK, which is the old behaviour and stays the
default.

```toml
[java]
version = "21"        # or "21.0.12", to be exact
```

It is a *version*, not a path, because a path is true on one machine and the lock is
committed. Any vendor satisfies it: the pin says which Java the project needs, not whose.
Matching is a prefix cut at a dot — `21` accepts every 21.x and nothing else, `21.0.12`
accepts only that build — so how exact a pin is, is the writer's choice rather than a
second syntax.

```console
./flixw pin --java 21          # pin the Java, leave the compiler alone
./flixw pin 0.75.2 --java 21   # both at once
./flixw pin --java none        # unpin
```

A pin below `MIN_JAVA` is refused where it is written: the compiler cannot run there at
all, so accepting it would produce a lock that fails on every later run instead of on the
command that created it. Re-pinning the compiler carries the java pin over unchanged —
`pin 0.75.3` is not a request to unpin the Java.

Resolution, in order: `FLIX_JAVA_HOME` or `JAVA_HOME` if set — obeyed as always, but a
named JDK that contradicts the pin is an error naming both sides rather than a silent
substitution; then the running JVM; then a JDK flixw installed; then any known
installation. A machine with nothing matching gets `FLIXW003`, and everything about that message — the
install instructions, the download prompt, the environment variable — names the *pinned*
feature release rather than the wrapper's floor.

`pin --java` says so at once when the machine has no such JDK. It still writes the pin,
because the machine that runs the build may not be this one, and `--install-jdk` can fetch
it; what it does not do is let you find out from the next command.

`./flixw info` prints the pin, and `doctor`/`validate` check it against the JDK the run
actually selected — "there is a 21 somewhere on this machine" is not the question.

The pin does not reach the shims by being parsed there — they choose a Java to run *stage
0*, which needs only `SOURCE_FLOOR`, while the compiler is launched as a child process
under the JDK stage 0 selected. Where the two differ, stage 0 relaunches itself under the
selected JDK, which costs a whole extra process.

So stage 0 leaves a note. `.flixw/local/java` holds the absolute path of the JDK this
project last resolved to, and a shim that has not been given `FLIX_JAVA_HOME` or
`JAVA_HOME` starts there instead of on whatever `java` comes first on `PATH`. That is one
file read and no parsing: the shims still make one decision, and the reasoning behind the
answer stays in stage 0, which can be tested.

Everything about it degrades to the old behaviour. A missing note, an unreadable one, or
one naming a JDK that has since been removed is a cache miss rather than an error, and the
next run through the compiler path rewrites it. The shims check its *shape* before using
it — stage 0 writes a normalized absolute path ending in `bin/java`, so anything else was
not written by this wrapper and is ignored. That is sanity rather than security, since
whoever can write the note can edit the shim, but a note is not where anyone should
discover they are running something else. An explicitly named JDK still outranks it,
because the note records what flixw worked out and not what the caller asked for.

`.flixw/local/` is machine-specific and must not be committed, so flixw ships
`.flixw/.gitignore` covering it rather than editing the project's own ignore file — the
wrapper directory is flixw's to manage. The note names an executable the shim will run,
which is not a new trust boundary: anyone who can write into `.flixw/local/` can edit
`./flixw` itself, which is easier and does more.

## Integrity

The cached JAR is hashed on **every** invocation and compared with the lock. There is no
install stamp and no skip flag: measured at ~120 ms against a 32 MB JAR, the check is
lost in the noise of any real command, and a skip flag is a footgun that only pays off
on commands too cheap to matter.

Downloads are HTTPS only, including after redirects, with bounded connect and total
timeouts, into a unique temporary file in the destination directory, verified, then
atomically renamed. Exactly one acquisition attempt per artifact per invocation — no
retry loops. The cache is content-addressed
(`compilers/flix-<version>-<sha256>.jar`), so concurrent writers converge without
locking and projects pinning different digests of the same version coexist.

What this is **not**: independent authenticity. GitHub serves both the bytes and the
digest, over the same TLS trust anchor, and a release asset can be replaced. The pin is
trust-on-first-generation. See [LIMITATIONS.md](LIMITATIONS.md).

## Java

Selection order: `FLIX_JAVA_HOME` → `JAVA_HOME` → the running JVM → a JDK flixw
installed earlier → known installations. The shims share only the first three and the
flixw-installed one: with nothing on `PATH` they read `<cache>/jdks/default`, which names
that JDK's `java` outright, because vendors nest differently on every platform and a shim
must not have to know how.
An explicit setting that is invalid or incompatible fails immediately rather than
falling through to a JVM the user did not choose.

Below Java 21 is always fatal — the compiler will not run. **Above the tested ceiling
warns and proceeds**, so a JDK upgrade cannot break a wrapper whose pinned compiler
tolerates it. `FLIXW_STRICT_JAVA=1` restores the hard failure for reproducible builds.

Version is read from the candidate's `release` file when it is parseable, and otherwise
by executing that candidate once. At most one relaunch per invocation, guarded by an
environment marker, so a stale `release` file cannot loop.

Among *known installations* the newest JDK inside `[21, ceiling]` wins, and one above the
ceiling is taken only when nothing inside it exists — the lowest such, being nearest to
tested ground. Directory order does not decide: on a machine holding 11, 17, 21, 25 and
26 the first-in-order rule chose 26, warned about the ceiling on every run, and had an
exactly-tested JDK one entry away.

When nothing usable is found, stage 0 prints OS-specific installation instructions and
exits `FLIXW003`. If stdin is a terminal and `CI` is unset it first offers to download a
JDK — Eclipse Temurin at `MIN_JAVA`, verified against Adoptium's published SHA-256 for
that package and unpacked into `<cache>/jdks/` — and `FLIXW_INSTALL_JDK=1` accepts that
offer in advance. `./flixw wrapper --install-jdk` performs it on demand and prints the
resulting `java` on stdout. Temurin is the only vendor fetched; any other already on the
machine is found and used. It is never taken silently.

The offer reaches further down than the floor it repairs: stage 0 compiles at
`SOURCE_FLOOR` (16), so every Java from there to `MIN_JAVA` runs flixw, fails to run the
*compiler*, and can fetch one that will. Below that, and with no Java at all, nothing here
speaks — stage 0 needs a JVM to say anything — so the shim's own message is the whole
answer, and it does not offer an install it cannot perform. `tests/lint.sh` compiles stage
0 at `SOURCE_FLOOR` so that promise cannot rot.

Discovery covers the directories JDKs are unpacked into, including version managers the
OS has no record of — SDKMAN, asdf, mise, jenv, Gradle, Homebrew on both architectures,
Scoop. It never shells out to an inventory tool; `docs/LIMITATIONS.md` says why. It is
reached only when no explicit setting exists and the running JVM is unusable.

## Dispatch

`./flixw <verb>` is compiler-first. In order:

1. `./flixw -- <args>` forwards everything after `--` to the compiler.
2. `./flixw wrapper [--operation]` is flixw's own namespace, answered by stage 0 before
   anything else. `--version` and `--help` are offline: no project, no lock, no network,
   no compiler. `--upgrade` rewrites this project's wrapper files; `--install-jdk` fetches
   a JDK and needs the network. A bare `wrapper` prints the routing table.
3. If the first word is a verb the pinned compiler implements, the compiler gets it.
   That includes `help`: it is a wrapper verb only until Flix ships one of its own.
4. Otherwise, if it is `pin`, `info`, `doctor`, `validate` or `help`, the
   wrapper implements it.
5. Otherwise the compiler gets it.

`./flixw wrapper --upgrade` moves the project to the newest published flixw: it fetches
that release, checks it against the `SHA256SUMS` published beside it, declines to walk
backwards, and then lets the *new* stage 0 install itself — it is the only thing that knows
its own shim bytes. Repairing the files a project already has is `./flixw doctor --fix`.

`./flixw wrapper [--operation]` is answered before any of this. It is flixw's own namespace,
not a stand-in for anything Flix might ship, so it is not routed to the compiler, does not
retire, and is unaffected by `FLIX_BACKEND` — and it is reachable with a lock too broken
to parse, because `./flixw wrapper --upgrade` is one of the two ways out of a broken
installation, the other being `./flixw doctor --fix`. The bare
verbs above collide with names Flix could claim *on purpose*; rewriting flixw's own files
never will, so it does not compete for one.

`./flixw help` and `./flixw --help` answer with both halves: flixw's routing table, then
the pinned compiler's own help, unedited. `help` is a bare verb and retires under rule 3
like any other; `--help` is a flag, can never be a compiler verb, and is intercepted
outright. `./flixw -- --help`, `FLIX_BACKEND=compiler`, and any `--help` carrying further
arguments reach the compiler alone — which is what anyone parsing its output wants. The
exit status is the compiler's.

`FLIX_BACKEND=wrapper` forces rule 4 during a transition; `FLIX_BACKEND=compiler` forces
the compiler for every verb, including the wrapper's own.

Dispatch is silent. Which side handled a verb is visible under `FLIXW_TRACE`, and
nowhere else: printing it on every wrapper-handled command told the caller what they had
just typed, and read as a warning about something that had not happened. The one notice
that remains is the deprecation warning below, which reports a change rather than a
routine.

The point of rule 3 preceding rule 4 is **automatic retirement**: the day Flix ships its
own `doctor`, users get the real one and the wrapper's stand-in steps aside, one verb at
a time, with a deprecation notice and no edit to any project file.

The verb set is captured once per compiler identity from `flix --help` and cached under
`<cache>/verbs/`. That capture is an optimisation, never a precondition. Its only job is
noticing that a pinned compiler has claimed a wrapper verb. It is bounded in both output
size and wall clock — as is the one-shot probe of a candidate `java` — because a `FLIX_JAR`
may point at any JAR at all, and a child that starts but never answers must cost a
timeout rather than the session.

Two help renderers are read, because two are in circulation and neither is a contract:
scopt's, which stock Flix uses — a single-line `Usage: flix [a|b|c]` plus one `Command: a`
line per verb — and picocli's, used by a fork, which wraps that bracket across lines and
lists the verbs in an indented `Commands:` block instead. Three independent parses run and
their results are merged; any one of them reaching three verbs is enough.

If none of them can parse `--help`, stage 0 warns once with `FLIXW010`, falls back to a
built-in table, and carries on — otherwise one upstream help reformat would brick every
project pinned to a compiler this wrapper has not seen, including for `check`, which never
consults the verb set.

Rule 5 exists because unknown first words may be filenames or future verbs. Note that
Flix does not currently produce a good unknown-command message — `flix doctro` reports
`Unrecognized file extension: 'doctro'.` on stdout — so routing there is correct but not
generous.

## Project selection

The search starts at the caller's working directory and takes the nearest ancestor
containing `flix.toml`, bounded above by the wrapper's own project. Invocation from
outside that tree is refused rather than searched: an unbounded walk finds the first
stray manifest above the caller and silently builds an unrelated project.
`FLIX_PROJECT_ROOT` overrides the search entirely. A search that finds no `flix.toml`
falls back to the directory the wrapper was installed into rather than failing: `flix.toml`
is Flix's file, and what flixw needs is somewhere to keep the lock. Demanding one first made
an empty directory unreachable from both sides — `pin` refused without a manifest, and
`init`, the compiler verb that writes one, refused without a pinned compiler. A project that
really is missing its manifest hears it from the compiler, whose file it is.

**The wrapper never changes the caller's working directory.** Relative paths in and out
keep their ordinary meaning.

## Process behaviour

Stage 0 launches the stock compiler as an opaque process with `stdin`, `stdout`, `stderr`
and the terminal inherited. Consequences, all tested:

- The child's exit status is the wrapper's exit status.
- The REPL keeps raw-mode input, line editing and colour.
- `./flixw run > out.txt` contains only the program's stdout. Every wrapper *diagnostic* —
  routing notices, warnings, `FLIXWnnn` — goes to stderr. Wrapper *command results*
  (`doctor`, `validate`, `wrapper --help`) go to stdout, so they can be redirected and
  piped like any other command output.
- Ctrl-C reaches the compiler through the foreground process group.
- A `SIGTERM` to stage 0 destroys the compiler rather than orphaning it. This holds for a
  stage 0 that has relaunched itself into another JVM too: every waiting stage 0 in the
  chain carries the same reaper, so the whole subtree goes down together.

Java has no `exec(2)`, so stage 0 stays resident for the compiler's whole life. See
[LIMITATIONS.md](LIMITATIONS.md) for what that costs and the one signal it cannot handle.

`./flixw info` reports state and judges nothing. `./flixw validate` judges and reports no
state, which is the form CI wants: a verdict and an exit code. `./flixw doctor` is both,
for a person — and `./flixw doctor --fix` repairs what can be repaired, naming what cannot.

That split replaced a `doctor` that printed state, noticed nothing and exited 0 with an
edited shim in the project, while the command that actually diagnosed was called
`validate`. `setup` is gone: it was `doctor` plus one printed line.

## Diagnostics

Failures print `FLIXWnnn` to stderr. **The identifier is normative; the numeric exit
status is advisory**, because a compiled Flix program may return any integer including
these.

| Code | Exit | Condition |
|---|---:|---|
| `FLIXW001` | 80 | the project root cannot be established |
| `FLIXW002` | 81 | manifest or lock missing, invalid, or inconsistent |
| `FLIXW003` | 82 | no compatible Java found (also emitted by the shims, as 126/127) |
| `FLIXW004` | 83 | explicitly selected Java is invalid or incompatible |
| `FLIXW005` | 84 | the single compiler acquisition attempt failed |
| `FLIXW006` | 85 | a cached, downloaded or overridden compiler failed digest validation |
| `FLIXW007` | 86 | cache or atomic installation failed |
| `FLIXW008` | 87 | an environment variable, JVM option or launcher flag is invalid |
| `FLIXW009` | 88 | install, verb capture, dispatch, pin, validate or lock transaction failed |

`FLIXW010` (unparseable `--help`) and `FLIXW011` (Java above the ceiling; a lock key flixw
does not read) are advisory: printed, never fatal.

## Environment

| Variable | Effect |
|---|---|
| `FLIX_JAVA_HOME` | JDK to use; invalid is fatal |
| `JAVA_HOME` | fallback JDK; invalid is fatal |
| `FLIX_CACHE_HOME` | overrides the platform cache directory |
| `FLIX_DIST_URL` | rewrites the distribution base, preserving the pinned digest; must be https |
| `FLIX_PROJECT_ROOT` | selects the project explicitly |
| `FLIX_JAR` | run this JAR instead — **unverified**, announced, and not compatibility evidence |
| `FLIX_JVM_OPTS` | options for the compiler JVM; a documented tokenizer, with a deny-list |
| `FLIX_BACKEND` | `wrapper` or `compiler`, to force a side during a transition; any other value is fatal |
| `FLIXW_STRICT_JAVA` | makes the tested ceiling fatal |
| `FLIXW_UNSAFE_JVM_OPTS` | permits the denied JVM options |
| `FLIXW_TRACE` | per-phase timings on stderr |
| `FLIXW_INSTALL_JDK` | accept the Temurin download offer without being asked |
| `HTTPS_PROXY`, `https_proxy`, `NO_PROXY` | honoured for downloads |

`JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` are reported by `doctor` because they alter the
JVM and prepend text to stderr, which otherwise looks like wrapper output.

### Running a locally built compiler

`FLIX_JAR` is the only supported way to run a compiler flixw did not download:

```sh
FLIX_JAR=/path/to/flix.jar ./flixw run
```

Three things about it are not obvious from the table row.

**A valid lock is still required.** The lock is read and drift is checked *before* the
override is, so a project with no `.flixw/lock.toml` fails `FLIXW002` whatever `FLIX_JAR`
says. Testing your own build therefore still means pinning a release first. That ordering
is deliberate — the manifest floor is a statement about which compiler the project needs,
and an override is not an answer to it — but it surprises people, so it is stated here.

**It is never verified.** The digest check has nothing to check against, every such run
prints the unverified note on stderr, and those runs are not stock-compatibility evidence.

**Verb capture still works.** With an override, the captured verb set is keyed on the
jar's path, size and mtime rather than on a digest, so a rebuilt jar re-runs `--help` once
and then caches again.

### The `.envrc.example` template

`install` writes an `.envrc.example` into the project root — a commented-out template for
[direnv](https://direnv.net) covering `FLIX_JAVA_HOME`, `FLIX_JAR`, `FLIX_CACHE_HOME`,
`FLIX_DIST_URL`, the proxy variables, `FLIX_JVM_OPTS` and `FLIXW_TRACE`.

It is written only when absent, so an edited copy survives re-running `install` or
`wrapper --upgrade`; extracting a release archive does overwrite it, which is the one place
the install route and the archive route differ on purpose. It is deliberately outside the
wrapper contract: not in the canonical-bytes comparison, not in the tracked-file audit, not
in the `.gitattributes` block. Deleting it is a valid answer and nothing will nag about it.

The name matters. direnv refuses an `.envrc` it has not been shown and reprints
`direnv: error … is blocked` on every `cd` into the directory until someone runs
`direnv allow` or deletes it — and the refusal is keyed on the file's hash, so a fully
commented-out `.envrc` is blocked exactly like a live one. Shipping one would hand
recurring noise to the only people it could help.

**flixw never reads `.envrc` or `.env`.** It reads the environment through one call,
`System.getenv`. direnv works by mutating the *shell's* environment before flixw starts,
which is also the source of its limits: it needs direnv installed and hooked plus a
per-clone `direnv allow`; it does not reach an editor-spawned `flixw lsp`, because a GUI
editor never passes through a shell prompt; and there is no `cmd.exe` or PowerShell
equivalent, so on Windows the variables must be set some other way.

The hook differs per shell, and without it an `.envrc` is an inert text file:

| Shell | Line, in the startup file named |
|---|---|
| bash | `eval "$(direnv hook bash)"` — `~/.bashrc` |
| zsh | `eval "$(direnv hook zsh)"` — `~/.zshrc` |
| fish | `direnv hook fish \| source` — `~/.config/fish/config.fish` |

The `.envrc` itself is bash in every case: direnv evaluates it with bash and exports the
resulting difference, so a fish user still writes `export FOO=bar` rather than
`set -x FOO bar`. The template says so, because getting it wrong produces a direnv error
rather than a flixw one and the two are easy to confuse.

`doctor` output is meant to be pasted into bug reports, so every value it prints that can
carry a credential is redacted: user-info and query string are stripped from proxy and
distribution URLs, and `-D…password=`-shaped JVM options are masked. The JVM's own
`Picked up JAVA_TOOL_OPTIONS: …` line is written by the JVM to stderr before stage 0
runs, and no wrapper can suppress it.

## Cache layout

The shims must locate the compiled stage 0, so these paths are a versioned interface
between shim and stage 0, not an implementation detail:

```text
<cache>/stage0/<sha256 of flixw.java>/flixw.class
<cache>/compilers/flix-<version>-<sha256>.jar
<cache>/verbs/<identity>.verbs
<cache>/jdks/<temurin package name>/  # only if you accepted the JDK offer
<cache>/jdks/default                 # one line: the java the last install produced
```

The stage-0 class is compiled with `--release 21`, the same floor `MIN_JAVA` declares.
The directory is keyed by source hash alone, so without that pin a stage 0 compiled by a
newer JDK would be handed to an older shim, which would fail on classfile version with no
route back to the source path.

For the same reason the shims take the fast path only when the selected Java is *known* to
be at or above the floor, read from that JDK's own `release` file — one file read, not a
subprocess. A Java the shim cannot place is not good enough: `asdf`, `mise` and `jenv`
install `java` as a shim script rather than a symlink into a JDK, so no `release` file sits
beside it, and running the class blind under one of those pointing at an old JVM fails on
class file version with no way back. Such setups always take the source path, which costs
them the fast path and is the deliberate trade. With one exception: if a JDK flixw
installed is recorded and Java was not named explicitly, the shim spends one
`java -version` to find out, because a version manager pointing at Java 11 cannot compile
stage 0 either — the source path fails too, and the recorded JDK is right there. Below the floor
the shim silently declines the cached class and launches the source instead, where stage 0
produces the ordinary `FLIXW003`/`FLIXW004` diagnostic; `exec` is one-way, so a class the
JVM refuses to load would otherwise surface as a bare `UnsupportedClassVersionError` with no
wrapper code reached and no fallback — including for `wrapper --help`.

`<cache>` is `FLIX_CACHE_HOME`, else `%LOCALAPPDATA%\flixw`,
`~/Library/Caches/flixw`, or `${XDG_CACHE_HOME:-~/.cache}/flixw`. Verb records live in
the cache and never beside the JAR: a content-addressed compiler directory may
legitimately be read-only, and a `FLIX_JAR` override points at a JAR flixw does not own.
