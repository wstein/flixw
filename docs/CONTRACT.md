# The contract

What `flixw` guarantees, as implemented and tested. Anything not listed here is not
promised. Every statement below is covered by a case in `tests/run.sh`.

## Files

Six files are committed into a consuming project, plus a block inside one the project
already owns. Five of the six are byte-identical across every project using a given
`flixw` release, so a single published hash validates them; only the lock differs per
project.

```text
flixw                 POSIX shim
flixw.cmd             cmd.exe shim
.flixw/flixw.java     stage 0
.flixw/lock.toml      the pin: version, URL, SHA-256
.flixw/.gitignore     keeps .flixw/local/ out of git
.flixw/.sccignore     keeps the vendored wrapper out of scc's line counts
.flixw/local/java     the JDK this machine resolved to; NOT committed
```

The installer also merges a marked block into `.gitattributes` — the seventh file, and the
only one flixw shares rather than owns — preserving unrelated rules. The block pins the
line endings of all six shipped files and marks the three that carry code — `flixw`,
`flixw.cmd`, `.flixw/flixw.java` — `linguist-vendored`, so GitHub's language graph
describes the project rather than the wrapper vendored into it. Not `linguist-generated`,
which excludes a file from the graph and collapses its pull-request diff as well: the
vendored stage 0 is meant to be read before it is trusted, and an upgrade rewriting it is
the diff that most needs to be open. A project that disagrees says `-linguist-vendored`
after the block.

`flixw validate` compares the two shims byte for byte against the bytes this release ships,
reports stage 0's digest for comparison against the published release, and fails if a later
`.gitattributes` rule overrides the block, or if more than one flixw block exists, since git
honours the last.

An override is judged by the attribute that results, not by the presence of a later rule.
git resolves attributes one at a time, so a rule counts only when it matches a shipped path
— by wildcard or by naming it outright — *and* leaves `text` or `eol` saying something
other than the block does. Repeating what the block already says is not an override, nor is
setting some unrelated attribute on the same path; a bare `text=auto` is not one either,
because the block's own `eol` goes on resolving beside it. `binary` is, despite naming
neither attribute: it is git's macro for `-diff -merge -text`, and so is any macro the file
defines that unsets `text` the same way. Macros defined in git config are out of scope,
being uncommitted and therefore true of one clone rather than of the project.

All six shipped files must be committed; `flixw validate` fails if a gitignore rule
swallows one, because a collaborator would then get a project that cannot bootstrap. `.flixw/local/` is
the exception that proves it: machine-specific, and ignored by the `.gitignore` flixw
ships for exactly that purpose. Stage 0 rewrites both notes in it on any run that resolves
a compiler, and discards every failure doing so — a note whose absence costs only a
startup optimisation is not worth a diagnostic, still less a failed build.

**Installing from an archive.** A release also ships `flixw-<version>.tar.gz` and
`flixw-<version>.zip`, whose contents are byte-identical to what `install` writes and are
extracted at a project root. They carry the five invariant files and nothing else: the lock is yours to
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
./flixw pin v0.75.2                                   # the same, spelled as the tag
./flixw pin wstein/flix-fork 0.75.2+fork.wstein.1     # a fork build
```

A leading `v` is accepted and says nothing about it. GitHub shows the *tag* — the releases
page, the tag list and every asset URL read `v0.75.2` — so copying from where the versions
actually are yields the tag, and flixw builds `"v" + version` to construct that URL itself.
The lock records the version either way, so one release cannot produce two locks. The
prefix is taken only ahead of a digit: `vNext` stays a bad version rather than becoming the
version `Next`.

`[package].flix` does **not** get this. That field is Flix's, and Flix accepts `x.x.x`
alone; tolerating a tag there would let flixw read a manifest Flix itself rejects.

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

### The stage 0 a project receives

`.flixw/flixw.java` is the documented source with its comments removed. It is generated at
release time, from that release's tag, and the header is the only comment left:

```java
// flixw 0.25.3 -- stage 0. GENERATED: this is the documented source with its
// comments removed, which is why it reads as bare mechanism.
//   https://wstein.github.io/flixw/          docs, and the lock schema
//   https://github.com/wstein/flixw          the source this was made from
```

The commentary is written for whoever audits flixw, and that reader is at one of those two
URLs. The vendored copy is there to be executed and digest-checked — in your repository,
in your diffs — so it ships as mechanism: 3288 lines instead of 4678, 152 KB instead of 255.

**The transformation is reproducible, and that is the point.** Running
`java tests/strip.java src/stage0/flixw.java <version>` on the tagged source regenerates the
published bytes exactly, so "read it before you trust it" survives the two artifacts being
different files: read the documented source, regenerate, compare digests with what you
have. A release whose published `flixw.java` did not match that would be detectable by
anyone, not just by whoever built it.

CI checks the strip is deterministic, that its output compiles at both the floor and the
minimum, that it renders an identical lock schema, and — in a job of its own — that the
stripped stage 0 passes the entire regression suite. Compiling is not equivalence.

The lock's shape is published as a JSON Schema:

```
https://wstein.github.io/flixw/schema/lock-v1.schema.json
```

`v1` is the *lock format's* major version, not the wrapper's. It moves only when a lock an
older flixw wrote would stop being readable; adding an optional key is not that, and does
not move it. `[compiler].reported_version` was added exactly that way.

**Every version ever published stays published.** The URL above is committed, verbatim, in
the lock of every project that has ever run a flixw that wrote it — so if `v2` ever
arrives, `v1` keeps resolving. Retiring it would not break flixw; it would break the editor
of a project that shipped a year ago and has upgraded nothing. `tests/pages.sh` publishes
`docs/schema/lock-v*.schema.json` as a glob for that reason, and `tests/lint.sh` fails if
`lock-v1.schema.json` disappears or if the publisher is narrowed to a single version.

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
depends on the line: `validate` reports a lock without one as `warn`, not as a failure.

Two commands add it, and they are the same rewrite:

```console
./flixw pin --refresh     # the lock, in this release's shape, and nothing else
./flixw doctor --fix      # that, as one repair among several
```

The rewrite is offline — the compiler is not re-resolved, not re-downloaded and not
re-hashed — and it changes the file's form rather than its meaning: same repository,
version, URL, digest and java pin. That is why it exists at all, since `pin <version>`
would fetch 33MB to write one comment. `--refresh` takes no other argument: a version, a
repository or a `--java` on the same line is a different request, and choosing one of the
two silently is how a repair loses the pin it was asked to preserve.

Three things stop the rewrite, and `pin --refresh` prints which one — a command that does
nothing and says nothing reads as one that worked. A lock that does not parse is
`pin <version>`'s job and fails `FLIXW002`. A lock written by a *newer* flixw is not this
release's to reshape. A lock carrying a key this release does not read would have that key
deleted, since the rewrite is from the values read. `doctor --fix` declines in the same
three cases and stays quiet about it, because there this is one item among several.

**A key the schema does not describe is advisory, both ways.** Stage 0 prints `FLIXW011`,
names the key, and carries on; it never sets exit status. The ordinary way to meet an
unknown key is a lock written by a *newer* flixw — the lock is committed, so that is every
collaborator who has not upgraded — and refusing to run would turn a forward-compatible
file into a broken project. The schema is stricter, because there the reader is a person
editing the file by hand and a warning is what they want. It is also why neither rewrite
above will touch such a lock: the rewrite is from the values read, so anything unread
would be deleted by the command that had just called it harmless.

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
install instructions and the `--install-jdk` suggestion alike — names the *pinned* feature
release rather than the wrapper's floor.

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

### An override that names flixw's own cache

`FLIX_JAR` exists to run a compiler you built yourself, so a jar that is *not* the pinned
one is the normal case; `info` and `doctor` report the difference and nothing else is said.

Pointing it inside `<cache>/compilers/` is always a mistake, and gets `FLIXW010` on every
run. Those names are content-addressed — `flix-<version>-<sha256>.jar` — so **the path
changes at every re-pin**. An override set once to whatever `info` printed that day goes on
naming the superseded artifact, and the project builds with the compiler it used to pin.
Nothing else in flixw can notice: the digest guard is switched off by the override, and the
version check passes because two builds of one release share a canonical version.

Naming the cache entry that *does* match the lock is also reported, more gently: it is the
jar flixw would have chosen anyway, and it will stop being that at the next pin.

`info` prints `override digest` — the SHA-256 of what is actually running — so the
comparison against the pinned digest is on screen rather than hidden in a file name.

### The version the compiler reports

The digest settles *which bytes* run. It does not settle that those bytes are the release
the lock names — a fork that tagged over an older build, or an upstream asset re-uploaded
under the same tag, was pinned, verified and run without a word.

`pin` therefore reads the version out of the compiler's own help header, once, and records
it in the lock as `[compiler].reported_version` beside the digest that vouches for it:

```toml
[compiler]
version = "0.75.2"
sha256  = "a2697d…"
reported_version = "0.75.2"
```

Asked once rather than per run, because the answer is a property of the bytes. Every run
re-hashes those bytes anyway, so a digest that still matches is a version that still
matches; a per-run capture would re-derive what the digest already proves, at the cost of a
subprocess and a cache file. The *comparison* is free once both strings are in the lock, so
it stays on every run — and it is what catches a lock edited or merged after `pin` wrote it,
the one case pin-time checking cannot see.

| | |
|---|---|
| they agree | nothing is said; `validate` reports `ok` |
| build metadata differs only | a note in `info` and `doctor`, `warn` in `validate` |
| the versions differ | `FLIXW010` on **every** run, and `validate` **fails** |
| the JAR just pinned disagrees | `FLIXW010` from `pin` itself, naming the version to pin instead |
| the lock has no `reported_version` | `warn` in `validate`; `pin --refresh` backfills it |

The key is optional. A lock written before it existed has no second opinion and says so
rather than claiming agreement — `pin --refresh` and `doctor --fix` fill it in from the
already-cached JAR, which needs no network, and only from bytes that still hash to what the
lock pins. That last condition is the point: without it a refresh would launder an
unverified JAR's self-description into the lock, where every later run would treat it as
something `pin` had checked.

`FLIX_JAR` is outside this guarantee by construction. An override's bytes are not the
locked ones and the lock never described them, so `reported_version` says nothing about
them. `info` and `doctor` report the override separately, and an override pointing inside
`<cache>/compilers/` is `FLIXW010` on every run for a different reason entirely.

Build metadata is not a mismatch. It identifies a build rather than a release, so a
compiler built from `0.75.3+stable.names.3` and reporting `0.75.3` is agreeing — and a
warning on every run of every fork build would teach the reader to skip the line that
matters. The comparison is `canonical()`, the same normalization used for release tags and
cache coordinates.

A compiler whose header carries no version is not an error; it is the absence of a second
opinion, and `validate` says so rather than failing — `pin` records no key at all in that
case, and on a machine with no usable Java it records none either, because writing a lock
must never depend on being able to run what it pins. Nothing here can stop a run: the
compiler is the authority on what it will execute, this is flixw's account of what was
asked for, and `FLIXW010` is printed and never sets exit status.

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
exits `FLIXW003`. **It does not download anything.** Provisioning is explicit:

```console
./flixw wrapper --install-jdk      # prints the resulting java on stdout
```

Stage 0 used to prompt at this point and install inline when the answer was yes. That is
gone. An automatic network fetch is the wrong default answer to a missing dependency in a
wrapper whose entire argument is that it fetches only what a lock named and a digest
confirmed — and it put ~200 lines of vendor-metadata parsing, archive handling and
per-platform policy in the file that loads on every single invocation, to serve the rarest
path there is. `FLIXW_INSTALL_JDK` is therefore also gone; there is no longer an offer for
it to pre-accept.

What `--install-jdk` does is unchanged: Eclipse Temurin at the *pinned* feature release
(or `MIN_JAVA` outside a project), verified against Adoptium's published SHA-256 for that
package and unpacked into `<cache>/jdks/`. Temurin is the only vendor fetched; any other
already on the machine is found and used.

The code that does it is a **companion asset**, `flixw-jdk.java`, fetched once per machine
per flixw release and verified against that release's own `SHA256SUMS` — the same trust
footing `wrapper --upgrade` gives `flixw.java` itself — then cached under
`<cache>/wrapper/assets/<version>/`. So `--install-jdk` needs network on its first use per
release, and none after.

Provisioning reaches further down than the floor it repairs: stage 0 compiles at
`SOURCE_FLOOR` (16), so every Java from there to `MIN_JAVA` runs flixw, fails to run the
*compiler*, and can fetch one that will. **`flixw-jdk.java` therefore compiles at
`SOURCE_FLOOR` too** — stage 0 launches a companion asset with the JVM it is itself
running on, which here is by definition the too-old one. Below that, and with no Java at
all, nothing here speaks — stage 0 needs a JVM to say anything — so the shim's own message
is the whole answer. `tests/lint.sh` compiles both files at `SOURCE_FLOOR` so neither
promise can rot.

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
   a JDK and needs the network. `--schema` prints to stdout and is offline too.
   A bare `wrapper` prints the routing table. `completion <shell>` is a verb of its own,
   not an operation here — see "Completion" below.
3. If the first word is a verb the pinned compiler implements, the compiler gets it.
   That includes `help`: it is a wrapper verb only until Flix ships one of its own.
4. Otherwise, if it is `pin`, `info`, `doctor`, `validate`, `help`, `plugin`, `task` or
   `examples`, the wrapper implements it. `plugin` and `task` are namespaces rather than
   bare verbs — `plugin <name>`/`task <name>` — so only those two words are subject to
   this rule; a third-party plugin's own name can never collide with a future compiler
   verb.
5. Otherwise the compiler gets it.

`./flixw wrapper --upgrade` moves the project to the newest published flixw: it fetches
that release, checks it against the `SHA256SUMS` published beside it, declines to walk
backwards, and then lets the *new* stage 0 install itself — it is the only thing that knows
its own shim bytes. Repairing the files a project already has is `./flixw doctor --fix`.

**It also warms every companion asset that release publishes**, so the commands needing
one work offline afterwards. Which assets those are is read out of the release's own
`SHA256SUMS` rather than from a list inside the wrapper: an upgrade runs in the *old* stage
0, which cannot know what the new release added, so a compiled-in list would quietly stop
warming the day a new asset shipped. Anything matching `flixw-<name>.java` is a companion,
as is the one third-party jar a release names — currently `picocli-<version>.jar`, which
`./flixw help` renders through. `flixw.java` is not one, being the wrapper itself, and
neither is anything else a release happens to publish: the jar is accepted by name rather
than by extension, so a future artifact published for a reader is not downloaded by every
upgrade because it happened to end in `.jar`.

Warming is best-effort and never fatal. An upgrade that installed a new stage 0 and then
could not pre-fetch a generator has still upgraded, and the asset is fetched on demand the
first time it is wanted — failing the upgrade over it would turn a slow network into a
broken wrapper. It also runs on the two no-op paths, where "nothing to do" is a statement
about stage 0 and not about the assets beside it: an upgrade is the natural moment to
notice one is missing from the cache. Ahead of the newest release — working on flixw
itself — nothing is warmed, because assets for an unpublished version cannot exist.

Stage 0 has **no install verb**. Adoption is `java flixw-setup.java [dir]`, run on the
installer downloaded from a release — it fetches and digest-verifies the stage 0 of its own
release, then writes the project files. `wrapper --upgrade` uses the same program, handing
it the stage 0 it has already verified rather than letting it fetch a second copy.

Setup also writes `<cache>/bin/flixw` as a machine-wide convenience launcher, where
`<cache>` is the cache `./flixw info` reports. It walks upward from the caller's working
directory for the nearest executable `flixw` with `.flixw/flixw.java`, then `exec`s it. It
owns no Java, compiler, cache or lock policy; those remain solely with the checked-in
wrapper it found. Outside such a project it fails rather than searching elsewhere or
downloading anything.

`install` is therefore a name flixw does not own, and `./flixw install` reaches the
compiler like any other word it does not own — which is where a project asking to install
its dependencies was always trying to go.

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

`--help`/`-h` after a wrapper verb answers that verb's own usage and exits 0 — `./flixw pin
--help`, `./flixw info --help`, `./flixw doctor --help`, `./flixw validate --help` and
`./flixw plugin --help` alike. `./flixw task --help` lists this project's tasks, the same as
a bare `./flixw task`; unlike a plugin name, `tasks.toml` has no naming grammar, so a task
literally named `--help` or `-h` is reserved by this rather than reachable through it —
`./flixw task <other-name>` is unaffected. Each verb's own parser checks for `--help`/`-h`
before its own grammar runs, so it can never be mistaken for an unrecognised option the way
it once was.

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

## Help

`./flixw help flix <command>` curates which of the compiler's options it shows, for stock
Flix's own scopt-based CLI specifically. Every option is grammatically global in that
parser — there is no `.children(...)` scoping any option to `run` rather than `check`, and
the compiler's own `--help` draws no distinction between them either — so this is not
extracted from anything Flix documents. It is sourced directly from flix/flix's `Main.scala`
and `Bootstrap.scala` (verified against 0.75.3): which options feed the compile-options bag
every command except `init`, `clean` and `build-pkg` constructs, which resolve dependencies
via `Bootstrap.bootstrap` (every command except `init`), which answer a confirmation prompt
only `release` asks (`--yes`), and which are read only with no command at all (`--listen`,
every `--Xbenchmark-*` flag) and so never belong on any named verb's screen.

This never adds an option that is not already in the real capture, and never hides one
from the *general* screen — `./flixw help flix` (no command), `./flixw completion`, and
`FLIXW_CONTEXT` all still see every option the compiler actually offers; only the
per-command curation narrows anything, and only when **both** of two independent facts
hold: `format(help)` confirms this is scopt's rendered layout, and stage 0's own
`isUpstream` — the lock's recorded repository, checked once where that fact is already
known, not inferred from anything about the captured text — confirms the pinned compiler
is flix/flix itself, unoverridden by `FLIX_JAR`. Layout alone was tried and rejected: a
fork can reproduce scopt's exact rendering while giving `--entrypoint` or `--threads` a
completely different meaning, so `format(help) == "scopt-v1"` proves the shape of the text,
never whose compiler produced it. A fork with real, differing per-command help
(`format`-independent, decided by byte-comparing `<verb> --help` against the top level)
skips this path entirely regardless and shows its own answer unedited.

Being sourced rather than inferred is also why this can go stale in one specific way: if
Flix's own CLI structure changes — an option moves into a real `.children(...)` block, a
command starts reading one it did not before — this curation will not notice on its own.
`./flixw -- <verb> --help` always reaches the compiler directly for the unedited, current
answer regardless, named in every curated screen for exactly this reason.

## Completion

`./flixw completion bash|zsh|fish|pwsh` prints a TAB-completion script on stdout.
Nothing is installed into the project; you place the output where your shell looks:

```console
./flixw completion bash > ~/.local/share/bash-completion/completions/flixw
./flixw completion zsh  > "${fpath[1]}/_flixw"
./flixw completion fish > ~/.config/fish/completions/flixw.fish
./flixw completion pwsh >> $PROFILE
```

The script describes the compiler this project has pinned, so **regenerate it after a
re-pin**. That is a deliberate reversal of what earlier releases did, and the trade is worth
stating plainly: completion used to be byte-identical across projects and read its verbs at
TAB time from a note, so it never went stale — but it could carry only bare verb names, no
descriptions and no options, because a note cannot hold them. It now carries all three, at
the cost of being a snapshot.

Everything is generated from one picocli `CommandSpec`: the pinned compiler's commands with
their own descriptions, the wrapper verbs they have not displaced, and the compiler's
options with the value-taking ones marked as such. `help` renders that same tree, so a
completion cannot disagree with the help screen on the same terminal.

bash and zsh come from picocli's own `AutoComplete`, which emits one script serving both.
fish and PowerShell are flixw walking the same model, because picocli generates neither.
`cmd.exe` gets nothing — it has no per-command completion mechanism to hook.

Generating a script needs **no project**. It is what somebody runs while setting up a shell,
routinely before any flixw project exists on the machine; outside a project the tree is the
wrapper's own verbs plus the built-in table. Inside one, no compiler is downloaded or
launched either — the verb set is read from the cache record, so a project whose compiler is
not cached yet still gets a working script.

It does need the renderer, which is a companion asset. Only the *first* `completion` call on
a machine, for a given release, needs the network; every call after that is an offline cache
hit. A cold cache with nothing reachable fails with `FLIXW005` naming what it could not
reach, rather than emitting a partial script — one installed into a shell startup and
quietly wrong would be found out days later, by someone wondering why nothing completes.

bash needs both `flixw` and `./flixw` registered, because it matches the command word as
typed. zsh, fish and PowerShell each resolve the name from the path, so one registration
covers every spelling including an absolute one.

## Examples

`examples/<name>/` is a real, separate Flix package inside a project — its own
`flix.toml`, typically depending on a *released* build of the root project rather than its
local source, proof that a published consumer actually works. `./flixw examples` runs one
of them against the root project's already-selected Java and already-verified compiler,
without touching the root's own lock:

```console
./flixw examples list
./flixw examples run cli-tool
./flixw examples run cli-tool -- some-token
./flixw examples run --entrypoint Foo.main cli-tool -- some-token
./flixw examples check cli-tool
./flixw examples build cli-tool
./flixw examples test cli-tool
```

A bare `./flixw examples` is the same as `./flixw examples list`. `list` prints nothing
but a directory name for every `examples/<name>/` that contains a `flix.toml`; a name that
does not match `[a-z][a-z0-9-]*` is silently excluded rather than listed and then refused,
degrading the same way an unparsed compiler `--help` does elsewhere in this wrapper. Every
other subcommand exits 89, naming the known examples, if `<name>` is not one of them — the
companion-asset convention `help` already uses for "no help topic" and "no plugin", not a
`FLIXWnnn` code: an asset's own diagnostics are not stage 0's numbered registry.

**Dispatch is verb-agnostic.** `run`, `check`, `build`, `build-classes`, `build-jar`,
`build-fatjar`, `build-pkg`, `clean`, `doc`, `format`, `outdated`, `eff-check`, `eff-lock`
and `test` all reach the same code path — change the compiler's working directory, forward
what follows `<name>` — so `build` needs no opinion from this wrapper about where its
artifact goes (`examples/<name>/build/`, Flix's own convention, exactly as it would be for
the root project) and `test` means what it says for a package with its own `@Test`
definitions, not Cargo's sense of running an
example as a test of the root package: there is no such sense here, since `examples` is its
own command rather than a flag riding `run`.

Three kinds of compiler verb are deliberately not in that list. `init` creates a *new*
project, and every verb here is reached through `discover()`/`known.contains(name)`, which
already requires the example to exist — there is nothing for it to do. `release` pushes to
GitHub using the example's own manifest, an external, stateful action no other verb here
takes; a generic relay should not trigger that by name alone. `repl`, `lsp` and
`lsp-vscode` are long-running or interactive rather than a batch command with an exit
code, the shape every verb above shares. None of the three is a `FLIXWnnn` refusal — they
simply are not accepted words, the same "unknown command" every other unrecognised verb
gets.

**Flags before `<name>` reach the compiler verb itself**, mirroring
`./flixw run --entrypoint Foo.main` at the root: `examples run --entrypoint Foo.main
cli-tool` runs `cli-tool` with that entry point, the same as it would for the root
project. Telling a value-taking flag (`--entrypoint <class>`) from `<name>` needs to know
the verb's own flags — without that, `--entrypoint Foo.main` and `<name>` look identical,
two bare words in a row. The scan stops at the first token that is not a known or plausible
flag, which is `<name>`, or at a bare `--`, which can never be one. A flag not recognised as
value-taking is treated as taking no value rather than refused: guessing wrong there is no
worse than the flag being mistaken for `<name>` outright, which is what happened before this
existed, and the compiler's own "Unknown option" is a clearer failure than flixw inventing
one.

That knowledge comes from the pinned compiler itself, per verb, not from a schema flixw
maintains. The probe itself is not conditional on stock versus fork — only on there being a
leading flag to disambiguate at all, the same short-circuit that skips it entirely for
`examples run cli-tool` with nothing before `<name>`. When there is a flag, even stock
Flix pays for one extra `<verb> --help` subprocess (with the same `FLIX_JVM_OPTS` the real
launch gets, so a fork needing one just to start does not probe with a bare `java -jar` and
fail for a reason that has nothing to do with per-command help existing); it is the
*comparison* that then degrades for stock Flix, not the probing — its `<verb> --help`
always echoes the identical top-level screen (the same byte-equality `help flix <command>`
already relies on to tell "no real per-command help" from an answer worth using), so the
result is exactly the flat, top-level `--help` capture it always was. A fork with real
per-command help answers differently per verb, and what that answer adds is *added to* the
flat set, never used to replace it — a subcommand screen that documents its own flags and
leaves an inherited global one to the top level, the same thing flix/flix's own layout does
with `--entrypoint`, must not make `examples run --global VALUE <name>` mistake `VALUE` for
`<name>`. A hand-maintained table of every Flix release's flags would need to know about
versions nobody using this project has ever pinned and would still be a static guess about
a fork; asking the exact jar already in hand is never wrong about it and costs nothing when
there is no flag to disambiguate.

**Everything after `<name>` is forwarded to the compiler verb verbatim, including a
leading `--`** — with one exception, and it exists at the root too, for the same reason.
Flix's own `run` rejects a bare trailing word outright as an unsupported "file argument"
rather than trying to load it — `run foo` refuses to run at all; `run -- foo` delivers `foo`
to `Sys.Env.Env.getArgs()`. `check`/`test` do not share this: the same shape there is a
legitimate extra file to compile, rejected only if it is not one. So a missing `--` before
a bare word can only ever be an omission for `run`, never a real choice being overridden,
and both `./flixw run foo` and `./flixw examples run cli-tool foo` insert it rather than
making the caller retype the one thing that position can mean — `check`/`build`/`test` are
left exactly as typed. A leading flag is left alone either way, `--help`/`-h` included:
telling `--entrypoint`'s value from the forwarding boundary needs the same value-taking-
option knowledge `examples`' own flag scan has, which the root command does not carry for
every compiler verb, so `./flixw run --entrypoint Foo.main foo` still needs `--` typed by
hand.

This was verified against flix/flix's own `run`, not against every fork or `FLIX_JAR`
override, so it only applies when stage 0's own `isUpstream` says the pinned compiler is
flix/flix, unoverridden — a fork's `run` may legitimately define its own positional
operand, and silently turning that into a forwarded program argument would be exactly the
kind of fork-invisible behaviour change this project exists to avoid. An override is
never eligible by construction: it is announced as unverified and is explicitly not
stock-compatibility evidence.

`--help`/`-h` gets its own rule regardless of the boundary above: `examples --help`
(no verb named yet)
answers with this wrapper's usage, but once a real verb is named, `--help`/`-h` is never
intercepted, in any position — `examples run cli-tool --help`, `examples run --help
cli-tool` and `examples run cli-tool -- --help` all reach the compiler or the example, never
this wrapper's usage. `examples`, alone among wrapper verbs, has a subordinate that answers
`--help` better than a generic usage line once a verb is in play — Flix's own `--help`
answers at exit 0 regardless of subcommand, the same known quirk `help flix <command>`
already documents — so flixw defers rather than repeating a worse answer.

`examples run --help`, with no `<name>` at all, is the one shape of this that needs its own
rule rather than falling out of the flag scan: `--help`/`-h` still gets peeled off as a
flag, and normally what is left over must be `<name>` — but here nothing is left, and
`--help` does not need an example's directory to answer, so refusing with "needs a name"
would be flixw inventing an error for a question it could have just answered. It runs from
the project root instead of `examples/<name>/` in this one case, which the compiler cannot
tell apart since `--help` reads nothing project-specific either way. Any other flag with no
`<name>` still refuses — there is no directory to run *that* against.

The compiler's *working directory* changes to `examples/<name>/`; nothing else does. Java
selection, the compiler jar (including a `FLIX_JAR` override) and its digest verification
all remain the root project's — `examples` reads no manifest of its own and performs no
floor check, on purpose: a second, weaker version comparison would only ever disagree with
the compiler's own, better error. `FLIX_JVM_OPTS` applies here exactly as it does to
`./flixw run`: it names "options for the compiler JVM", and `examples` launches that same
compiler jar, just from a different working directory.

Unlike `info`/`doctor`/`validate`, which must answer even when the project's compiler
cannot be reached — that is how a broken project is diagnosed — `examples` exists only to
launch one. A project with no working lock gets `FLIXW009` from stage 0 itself, naming the
repair (`./flixw pin <version>`), before the asset is ever fetched.

**A companion asset, not a plugin — see AGENTS.md for the full reasoning.** In short: this
is flixw's own code, shipped and warmed the way `flixw-help.java` is, so there is no
"3rd-party, unaudited" warning and no separate install step gating a project's own
advertised demo command on a fresh clone or in CI.

Symlinks are defended against twice, the same way plugin names are: `examples/` itself
resolving outside the project root is refused before anything is listed, and a selected
`<name>` resolving outside the real `examples/` directory is refused before anything runs.

## Plugins and tasks

Two separate, deliberately small mechanisms for extending what `./flixw` runs beyond the
pinned compiler — neither is a way to move `pin`, `info`, `doctor`, `validate` or `help`
out of stage 0, which stay there permanently. See [AGENTS.md](../AGENTS.md#plugins-and-tasks-are-a-stable-abi-not-more-stage-0-commands)
for the reasoning; this section is what each guarantees.

### `./flixw task`

`.flixw/tasks.toml` is a flat `name = "shell command"` table, hand-edited and committed
like the rest of the wrapper's files, but never generated, rewritten or read by `pin` or
`doctor --fix`. There is no trust question: it is a shell string in a file the project
already trusts, exactly as any other checked-in script is.

```console
./flixw task              # lists the names, or "(no tasks in .flixw/tasks.toml)"
./flixw task build        # runs it through the platform shell
./flixw task build --arg  # extra words are appended positionally, after the command
```

The command runs through `sh -c '<command> "$@"'` on POSIX and `cmd /c <command> <args>` on
Windows, with cwd and all three streams inherited exactly like a plugin or the compiler.
Windows argument quoting is best-effort (`cmdQuote` doubles an embedded `"` and wraps a
word containing whitespace or a shell metacharacter) and is not byte-exact, for the same
reason the shim's own argument parity is not claimed — see
[LIMITATIONS.md](LIMITATIONS.md).

### `./flixw plugin`

A machine-wide, explicit, digest-verified cache of third-party `.jar`, `.java` or `.flix`
code, installed once and invoked by name under its own namespace so it can never collide
with a compiler verb, another plugin, or a future wrapper verb:

```console
./flixw plugin install <name> <version> <url> [--sha256 <digest>]
./flixw plugin list
./flixw plugin remove <name>
./flixw plugin <name> [args...]
```

`<name>` is `[a-z][a-z0-9-]*` — a single path segment, checked at install, remove, invoke
*and* lock-parse time, because it reaches `<cache>/plugins/<name>/` before anything else
about the entry is read. `<url>` must be `https://` (the ordinary path) or `file://` (local
development, and this project's own tests) and must end in `.jar`, `.java` or `.flix`,
which decides how the artifact is later launched. `--sha256` is verified against the
download the same way a compiler pin is; installing without it trusts the download once, and
every later invocation re-hashes the cached bytes against what installation recorded — a
plugin gets no lesser guarantee than the compiler does. Every invocation, not only install,
prints that the code is third-party and unaudited by flixw: a digest verified once does not
become a safety review by being run again.

Installing when the project has a lock records `[plugins.<name>]` (`version`, `sha256`,
`source`) in it, the same way `pin` records the compiler; installing before any `pin` (or in
a project with no `flix.toml` yet) works and simply is not recorded anywhere. A lock's
`[plugins.<name>]` entry is authoritative about which installed build runs when present;
its absence falls back to "whatever is installed," which only resolves while there is
exactly one version of that name on the machine.

**The ABI.** Every plugin, every format, receives the same context, both as flat
environment variables and as a versioned JSON file:

```
FLIXW_ABI_VERSION=1
FLIXW_PROJECT_ROOT, FLIXW_CACHE_HOME
FLIXW_COMPILER_VERSION, FLIXW_COMPILER_REPO, FLIXW_COMPILER_SHA256, FLIXW_COMPILER_JAR
FLIXW_JAVA_HOME
FLIXW_PLUGIN_NAME, FLIXW_PLUGIN_VERSION, FLIXW_PLUGIN_SHA256
FLIXW_PLUGIN_CACHE   # <cache>/plugin-cache/<name>/ -- yours to write, collected by --purge
FLIXW_CONTEXT   # path to a fresh JSON file carrying all of the above, plus "args"
```

**`FLIXW_PLUGIN_CACHE` is the one place a plugin may keep derived data between runs.** It is
not created eagerly, so a plugin that never writes leaves no trace, and it is not under
`plugins/<name>/`, because `plugin list` reports the directories there as installed
*versions* — derived data placed there is listed as a version that cannot be run.

flixw guarantees the path and that `wrapper --purge` collects it. It guarantees nothing about
what is inside: keying and invalidation belong to the plugin, which is the only thing that
knows what its output depends on. The directory is not keyed by plugin version, so an upgrade
does not silently orphan what the previous build derived; a plugin whose output shape changes
should say so in its own keys.

`plugin remove` deletes it along with the plugin. `--purge` offers *orphaned* data — whose
plugin is no longer installed — without consulting a usage marker, since that marker belonged
to the plugin and was removed with it. Data belonging to an installed plugin is never touched:
that would be a cache invalidation the plugin could not know had happened.

The compiler and Java fields are simply absent when the project has no lock yet — a `.jar`
or `.java` plugin that does not need a compiler is not handed a context it has to guess is
incomplete. `FLIXW_CONTEXT` names a temporary file written fresh before each launch and
removed by a JVM shutdown hook once the plugin exits, because the launch ends in
`System.exit`, which a `finally` block does not survive. `FLIXW_ABI_VERSION` is additive-only
for now; a real breaking change is what would move it, and none has yet.

**`.flix` plugins cannot receive `args`.** Stock Flix has no `run <file>` mode — `run` runs
the current project's own `main`, and refuses a file argument outright — so the only way to
execute a standalone `.flix` file is `java -jar flix.jar plugin.flix`, where every extra
word is parsed as one more source file to compile, not a program argument. A `.flix`
plugin invoked with arguments fails before it ever runs, naming the problem, rather than
compiling the arguments as source. It still receives the full ABI: `Sys.Env.Env` /
`Sys.Env.getVar` reads the same environment variables every other format does, which is why
the env tier — not `args` — is the one channel every format can rely on regardless of
whether it can take CLI arguments. It also always runs against *this project's own pinned
compiler*, never a version the plugin names, so a plugin can extend what Flix does here, not
choose which Flix does it.

`./flixw doctor` and `./flixw info --verbose` report plugin state the same way they report
the compiler's: `doctor` warns — never fails — when a lock names a plugin build that is not
installed, naming the install command that repairs it; `info -v` lists every plugin ever
installed on the machine, not only what the current project declares, marking the build a
present lock expects with `<= expected by lock.toml`.

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
`./flixw info --verbose` (or `-v`) adds a listing of the machine-wide cache — every
compiler JAR and JDK actually sitting under `<cache>`, not only the pin this project
reads — plus every JDK `knownInstalls()` already finds without flixw having put it there
(Homebrew, scoop, sdkman, asdf, mise, jenv, the OS-native install directories). It is a
directory listing, not a catalogue: it names nothing that could be pinned, provisioned or
downloaded but is not already on the machine, because that would be a network call on a
verb the paper promises stays offline.

`./flixw wrapper --purge [days]` is an explicit cache-recovery operation, not automatic
maintenance. It offers each flixw-owned compiler, provisioned-JDK, plugin and old
companion-asset entry unused for 14 days by default (or the supplied non-negative number of
days); answer each deletion prompt, or add `--yes` for an intentional non-interactive purge.
Stage 0 records its own one-date usage marker and reads it before writing, so a normal run
does not rewrite the marker more than once a day; filesystem access timestamps are not used.
It retains the default JDK, this wrapper release's assets, the small stage-0 compilation
cache, and entries with no usable flixw usage marker. Purge is best-effort space recovery,
not a correctness or security mechanism; use it only when cached bytes may be re-acquired if
a project needs them. The cache inventory is a verified companion asset, so the first purge
on a machine for a wrapper release needs network if that asset is not already cached; offline
purge then reports the missing asset rather than deleting anything blindly.

Each cached compiler is labelled with the exact repo and tag it was pinned as, not the
canonical `x.x.x` its cache filename carries: `acquire` writes that pair beside the digest
on every run, so it survives long after the project that wrote it moves on to another pin,
and a fork that never echoes its own build metadata in `--help` still gets an answer. An
entry no project on this machine has ever acquired falls back to what the compiler itself
reports, then to the canonical name plus a short digest — the only two things a bare
directory listing could otherwise offer.

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
| `FLIXW005` | 84 | the single compiler or wrapper-companion-asset acquisition attempt failed |
| `FLIXW006` | 85 | a cached, downloaded or overridden compiler failed digest validation |
| `FLIXW007` | 86 | cache or atomic installation failed |
| `FLIXW008` | 87 | an environment variable, JVM option or launcher flag is invalid |
| `FLIXW009` | 88 | install, verb capture, dispatch, pin, validate or lock transaction failed |

`FLIXW010` (unparseable `--help`; the compiler reporting a version the lock does not pin)
and `FLIXW011` (Java above the ceiling; a lock key flixw does not read) are advisory:
printed, never fatal.

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
| `FLIXW_RELEASE_SOURCE` | where `wrapper --upgrade` looks for the newest release |
| `FLIXW_ASSET_SOURCE` | where companion assets are fetched from |
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

## Cache layout

The shims must locate the compiled stage 0, so these paths are a versioned interface
between shim and stage 0, not an implementation detail:

```text
<cache>/stage0/<sha256 of flixw.java>/flixw.class
<cache>/compilers/flix-<version>-<sha256>.jar
<cache>/verbs/<identity>.verbs
<cache>/verbs/<identity>.compl        # only if the compiler ships its own completer
<cache>/jdks/<temurin package name>/  # only if you accepted the JDK offer
<cache>/jdks/default                 # one line: the java the last install produced
<cache>/plugins/<name>/<version>-<sha256>/plugin.{jar,java,flix}
<cache>/plugins/.context-*.json      # one per invocation, deleted by a shutdown hook
<cache>/wrapper/assets/<version>/flixw-help.java        # the TAB-completion generator
<cache>/wrapper/assets/<version>/flixw-help.java.sha256 # verified once, checked locally after
<cache>/wrapper/assets/<version>/flixw-jdk.java               # the optional JDK provisioner
<cache>/wrapper/assets/<version>/flixw-jdk.java.sha256        # same, one sidecar per asset
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
