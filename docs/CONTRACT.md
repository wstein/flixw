# The contract

What `flixw` guarantees, as implemented and tested. Anything not listed here is not
promised. Every statement below is covered by a case in `tests/run.sh`.

## Files

Four files are committed into a consuming project. Three are byte-identical across every
project using a given `flixw` release, so a single published hash validates them; only
the lock differs per project.

```text
flix                        POSIX shim
flix.cmd                    cmd.exe shim
.flix-wrapper/flix.java     stage 0
.flix-wrapper/lock.toml     the pin: version, URL, SHA-256
```

`flix install` also merges a marked block into `.gitattributes`, preserving unrelated
rules. `flix validate` compares the two shims byte for byte against the bytes this
release ships, reports stage 0's digest for comparison against the published release, and
fails if a later `.gitattributes` rule overrides the block — gitattributes resolves by
last matching pattern, so an override silently un-pins the line endings the block exists
to fix. All four files must be committed; `flix validate` fails if a gitignore rule
swallows one, because a collaborator would then get a project that cannot bootstrap.

## The pin

`flix.toml` is the human authority for the compiler version. `.flix-wrapper/lock.toml`
is generated and binds the tuple *(version, distribution URL, SHA-256)*.

Only exact versions are accepted — no ranges, no wildcards, no `latest`. This is not a
temporary limitation. A digest identifies one immutable byte sequence, so a floating
version and a pinned digest cannot both be honoured; exactness is what makes the digest
mean anything.

SemVer build metadata is accepted in the manifest and stripped from the release tag and
the cache coordinate. That normalization is defined once, in `canonical()`, and used for
every comparison — otherwise `flix = "0.75.2+build.4"` produces a drift error that
`./flix pin` regenerates and cannot repair.

**Drift is fatal, and detected before the network.** If `flix.toml` and the lock
disagree, the compiler path stops immediately — before Java selection, before any
download, before the compiler is executed. `pin`, `doctor`, `validate` and
`update-wrapper` still run, because otherwise the repair the diagnostic recommends is
unreachable. `setup` does not, because it acquires the compiler.

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

Selection order: `FLIX_JAVA_HOME` → `JAVA_HOME` → the running JVM → known installations.
An explicit setting that is invalid or incompatible fails immediately rather than
falling through to a JVM the user did not choose.

Below Java 21 is always fatal — the compiler will not run. **Above the tested ceiling
warns and proceeds**, so a JDK upgrade cannot break a wrapper whose pinned compiler
tolerates it. `FLIXW_STRICT_JAVA=1` restores the hard failure for reproducible builds.

Version is read from the candidate's `release` file when it is parseable, and otherwise
by executing that candidate once. At most one relaunch per invocation, guarded by an
environment marker, so a stale `release` file cannot loop.

## Dispatch

`./flix <verb>` is compiler-first. In order:

1. `./flix -- <args>` forwards everything after `--` to the compiler.
2. `--wrapper-version` and `--wrapper-help` are answered by stage 0, offline: no project,
   no lock, no network, no compiler. They take no arguments.
3. If the first word is a verb the pinned compiler implements, the compiler gets it.
4. Otherwise, if it is `pin`, `doctor`, `setup`, `validate` or `update-wrapper`, the
   wrapper implements it, and says so on stderr.
5. Otherwise the compiler gets it.

`FLIX_BACKEND=wrapper` forces rule 4 during a transition; `FLIX_BACKEND=compiler` forces
the compiler for every verb, including the wrapper's own.

The point of rule 3 preceding rule 4 is **automatic retirement**: the day Flix ships its
own `doctor`, users get the real one and the wrapper's stand-in steps aside, one verb at
a time, with a deprecation notice and no edit to any project file.

The verb set is captured once per compiler identity from `flix --help` and cached under
`<cache>/verbs/`. That capture is an optimisation, never a precondition. Its only job is
noticing that a pinned compiler has claimed a wrapper verb. If `--help` cannot be parsed,
stage 0 warns once with `FLIXW010`, falls back to a built-in table, and carries on —
otherwise one upstream help reformat would brick every project pinned to a compiler this
wrapper has not seen, including for `check`, which never consults the verb set.

Rule 5 exists because unknown first words may be filenames or future verbs. Note that
Flix does not currently produce a good unknown-command message — `flix doctro` reports
`Unrecognized file extension: 'doctro'.` on stdout — so routing there is correct but not
generous.

## Project selection

The search starts at the caller's working directory and takes the nearest ancestor
containing `flix.toml`, bounded above by the wrapper's own project. Invocation from
outside that tree is refused rather than searched: an unbounded walk finds the first
stray manifest above the caller and silently builds an unrelated project.
`FLIX_PROJECT_ROOT` overrides the search entirely.

**The wrapper never changes the caller's working directory.** Relative paths in and out
keep their ordinary meaning.

## Process behaviour

Stage 0 launches the stock compiler as an opaque process with `stdin`, `stdout`, `stderr`
and the terminal inherited. Consequences, all tested:

- The child's exit status is the wrapper's exit status.
- The REPL keeps raw-mode input, line editing and colour.
- `./flix run > out.txt` contains only the program's stdout. Every wrapper *diagnostic* —
  routing notices, warnings, `FLIXWnnn` — goes to stderr. Wrapper *command results*
  (`doctor`, `validate`, `--wrapper-help`) go to stdout, so they can be redirected and
  piped like any other command output.
- Ctrl-C reaches the compiler through the foreground process group.
- A `SIGTERM` to stage 0 destroys the compiler rather than orphaning it.

Java has no `exec(2)`, so stage 0 stays resident for the compiler's whole life. See
[LIMITATIONS.md](LIMITATIONS.md) for what that costs and the one signal it cannot handle.

## Diagnostics

Failures print `FLIXWnnn` to stderr. **The identifier is normative; the numeric exit
status is advisory**, because a compiled Flix program may return any integer including
these.

| Code | Exit | Condition |
|---|---:|---|
| `FLIXW001` | 80 | no project manifest within the bounded search |
| `FLIXW002` | 81 | manifest or lock missing, invalid, or inconsistent |
| `FLIXW003` | 82 | no compatible Java found (also emitted by the shims, as 126/127) |
| `FLIXW004` | 83 | explicitly selected Java is invalid or incompatible |
| `FLIXW005` | 84 | the single compiler acquisition attempt failed |
| `FLIXW006` | 85 | a cached, downloaded or overridden compiler failed digest validation |
| `FLIXW007` | 86 | cache or atomic installation failed |
| `FLIXW008` | 87 | an environment variable, JVM option or launcher flag is invalid |
| `FLIXW009` | 88 | install, verb capture, dispatch, pin, validate or lock transaction failed |

`FLIXW010` (unparseable `--help`) and `FLIXW011` (Java above the ceiling) are advisory:
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
| `FLIX_BACKEND` | `wrapper` or `compiler`, to force a side during a transition |
| `FLIXW_STRICT_JAVA` | makes the tested ceiling fatal |
| `FLIXW_UNSAFE_JVM_OPTS` | permits the denied JVM options |
| `FLIXW_TRACE` | per-phase timings on stderr |
| `HTTPS_PROXY`, `https_proxy`, `NO_PROXY` | honoured for downloads |

`JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` are reported by `doctor` because they alter the
JVM and prepend text to stderr, which otherwise looks like wrapper output.

## Cache layout

The shims must locate the compiled stage 0, so these paths are a versioned interface
between shim and stage 0, not an implementation detail:

```text
<cache>/stage0/<sha256 of flix.java>/flix.class
<cache>/compilers/flix-<version>-<sha256>.jar
<cache>/verbs/<identity>.verbs
```

`<cache>` is `FLIX_CACHE_HOME`, else `%LOCALAPPDATA%\flixw`,
`~/Library/Caches/flixw`, or `${XDG_CACHE_HOME:-~/.cache}/flixw`. Verb records live in
the cache and never beside the JAR: a content-addressed compiler directory may
legitimately be read-only, and a `FLIX_JAR` override points at a JAR flixw does not own.
