# flixw

[![CI](https://github.com/wstein/flixw/actions/workflows/ci.yaml/badge.svg)](https://github.com/wstein/flixw/actions/workflows/ci.yaml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![dependencies: none](https://img.shields.io/badge/dependencies-none-brightgreen.svg)](src/flixw.java)
[![platforms: linux | macOS | windows](https://img.shields.io/badge/platforms-linux%20%7C%20macos%20%7C%20windows-lightgrey.svg)](.github/workflows/ci.yaml)
[![docs](https://img.shields.io/badge/docs-wstein.github.io%2Fflixw-blue.svg)](https://wstein.github.io/flixw/)

An **experimental, third-party, opt-in** repository bootstrapper for
[Flix](https://flix.dev). It pins a Flix compiler version in your project, verifies the
official release JAR against a committed SHA-256, and runs it — with no Flix
installation, no compiler fork, and no patched build.

That digest is recorded from whatever the first `pin` downloaded, so what it buys you is
that everyone afterwards runs the same bytes — not that those bytes are the ones the Flix
project built. Flix publishes no signatures, so there is nothing to check them against.
[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) states the difference plainly, and is worth
reading before trusting this with a download.

> This is not an official Flix tool. It is not affiliated with or endorsed by the Flix
> project. It works against unmodified release JARs published by
> [`flix/flix`](https://github.com/flix/flix) — and against a fork's, so long as the fork
> publishes its build as a GitHub release asset named `flix-<version>.jar` or `flix.jar`:
> `./flixw pin <owner>/<repo> <version>`. A fork is downloaded, digest-verified and pinned
> exactly as the stock compiler is; what it is *not* is evidence that a project works with
> stock Flix, and flixw says so on every pin that names one.

## Quickstart

A new project, from an empty directory. Every command and every line of output below is
from an actual run.

You need a JDK first: flixw is itself a Java program, so it cannot be the thing that gets
you your first Java. Java 21+ is what the compiler needs — see
[what if my Java is older](#what-if-my-java-is-older) if yours is not.

```console
git init hello && cd hello
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.25.0-pre.1/flixw-setup.java
java flixw-setup.java
rm flixw-setup.java
```

<details>
<summary>The same, in PowerShell</summary>

```powershell
git init hello; cd hello
Invoke-WebRequest -OutFile flixw-setup.java `
  https://github.com/wstein/flixw/releases/download/v0.25.0-pre.1/flixw-setup.java
java flixw-setup.java
Remove-Item flixw-setup.java
```

Then read `.\flixw.cmd` wherever this page writes `./flixw`: `.\flixw.cmd pin 0.75.2`,
`.\flixw.cmd init`, `.\flixw.cmd run`. Git Bash and WSL run the POSIX shim, so there the
commands work as written.
</details>

Pin a compiler. This is the step that makes the project reproducible: it fetches Flix
0.75.2, hashes it, and records the digest in `.flixw/lock.toml`. There is no `flix.toml`
yet and none is needed — that file is Flix's, and the compiler is about to write it.

```console
$ ./flixw pin 0.75.2
flixw: pinned Flix 0.75.2 from flix/flix (a2697d875725a0dd...)
```

`init` is a compiler verb, not one of ours: it goes to the pinned Flix, which scaffolds
the project around the wrapper.

```console
$ ./flixw init
$ ./flixw run
Hello World!
```

That is the whole bootstrap.

### What if my Java is older?

Two versions of "you need a JDK" are true at once. The pinned *compiler* needs Java 21+;
stage 0 itself compiles on Java 16. So with anything from 16 up, flixw runs, tells you the
compiler will not, and `./flixw wrapper --install-jdk` fetches a verified Temurin 21 into
its own cache rather than touching your system.

Below 16, and with no Java at all, that command cannot help: it is a Java program and there
is nothing to run it. You get the shim's own message naming the install command for your
platform instead. First contact needs a JDK you installed yourself.

From here every verb is the stock compiler, run by the wrapper:

```console
$ ./flixw test
Passed: 1, Failed: 0. Skipped: 0. Elapsed: 3.4ms.

$ ./flixw validate
ok    ./flixw matches flixw 0.25.0-pre.1
ok    ./flixw.cmd matches flixw 0.25.0-pre.1
ok    .flixw/flixw.java  sha256=c41d7b3eec8f91ce...
ok    the lock satisfies flix.toml
ok    the compiler reports the version the lock pins
```

The JDK can be pinned too, in the same file and for the same reason — a version, not a
path, since a path is true on one machine only:

```console
$ ./flixw pin --java 21
flixw: pinned java 21
```

Any vendor's JDK satisfies it, `21.0.12` pins harder than `21`, and a machine without a
match is told so before anything is downloaded. Leave it out and flixw picks the newest
tested JDK it can find, which is what it has always done.

Commit, and a collaborator with nothing but a JDK gets the same compiler you have:

```console
git add flixw flixw.cmd .flixw .gitattributes flix.toml src test
```

Verify what you downloaded before you run it: every release publishes the SHA-256 of
each file it ships, and `./flixw validate` prints the one in your project so you can
compare it against the release you meant to install.

### What the project looks like

Nine committed files and one that stays on your machine, plus a template you may keep or
delete. flixw writes five of the nine and shares a sixth; all of them are committed on
purpose, so a clone needs no bootstrap step of its own and no `flix` on `PATH`. (`init`
also scaffolds a `README.md`, a `LICENSE.md`, a `.gitignore` and a CI workflow, which are
yours to keep or delete.)

```text
hello/
├── flixw                  the wrapper you actually run; a POSIX sh shim
├── flixw.cmd              the same, for cmd.exe and PowerShell
├── .flixw/
│   ├── flixw.java         stage 0: the bootstrap, one dependency-free Java file
│   ├── .gitignore         keeps local/ out of git
│   ├── lock.toml          the pin — repository, version, URL, SHA-256
│   └── local/java         the JDK this machine resolved to — not committed
├── .gitattributes         line endings for the five above, as a block in your file
├── flix.toml              your project: name, dependencies, the minimum Flix
├── src/Main.flix          your code
└── test/TestMain.flix     your tests
```

`flixw`, `flixw.cmd`, `flixw.java` and `.gitignore` are byte-identical in every project on
the same flixw release, so one published digest validates all four. `lock.toml` is yours,
`.gitattributes` is yours with a block of ours in it, and `local/java` belongs to this
machine alone. Not committed, and safe to delete at any time:

```text
build/  lib/  artifact/  .flix-cache/    Flix's own output and dependency cache
<cache>/                                 verified compiler JARs and JDKs, shared
                                         across every project on the machine
```

`<cache>` is `FLIX_CACHE_HOME` if you set it, and otherwise the place the platform keeps
caches:

| | |
|---|---|
| Linux, BSD | `${XDG_CACHE_HOME:-~/.cache}/flixw` |
| macOS | `~/Library/Caches/flixw` |
| Windows | `%LOCALAPPDATA%\flixw` |

`./flixw info` prints the one in use; `./flixw info --verbose` (or `-v`) lists what is
actually cached there -- every compiler JAR and JDK, not just the ones this project has
pinned -- plus every JDK it can find on the machine without flixw having installed it
(Homebrew, scoop, sdkman, asdf, mise, jenv, and the usual OS install directories).

A real project on this: [`flix-invaders`](https://github.com/wstein/flix-invaders), by
the same author, which type-checks, tests, formats and packages through `./flixw` on
Linux, macOS and Windows, and keeps a
[pin-lag log](https://github.com/wstein/flix-invaders/blob/main/docs/pin-lag.md) — one row
per Flix release — so the cost of pinning is a number rather than an argument. That is one
project; [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) says exactly what is and is not
established.

## Adding it to an existing project

A project that already has sources and a `flix.toml` takes the quickstart route unchanged
— `curl` the single file, `java flixw-setup.java`, then `pin`. It adds the
wrapper without touching anything else: it merges its block into an existing
`.gitattributes` rather than replacing it, and it never writes `flix.toml`.

The `.flixw/flixw.java` you commit is the documented source with its comments removed —
3288 lines rather than 4678 — with a header pointing at
[the docs](https://wstein.github.io/flixw/) and [the source](https://github.com/wstein/flixw)
for the reasoning behind every check. The strip is reproducible from the tagged source, so
the file you can read and the file you run can be compared rather than taken on trust.

The bootstrap sits in flixw's own namespace rather than being a bare `install` verb,
because `install` is a name Flix could claim for a project's dependencies — so
`./flixw install` reaches the compiler, like every other word flixw does not own.

The archive is the alternative, for when you would rather not run a downloaded program to
install a program:

```console
base=https://github.com/wstein/flixw/releases/download/v0.25.0-pre.1
curl -fsSLO $base/flixw-0.25.0-pre.1.tar.gz
curl -fsSL  $base/SHA256SUMS | grep flixw-0.25.0-pre.1.tar.gz | shasum -a 256 -c -
tar -xzf flixw-0.25.0-pre.1.tar.gz        # flixw, flixw.cmd, .flixw/flixw.java
rm flixw-0.25.0-pre.1.tar.gz
./flixw pin <version>               # writes the lock, fetches and verifies the compiler
                                    # 0.75.2 or v0.75.2 -- the release tag works too
./flixw doctor --fix                # merges the .gitattributes block
git add flixw flixw.cmd .flixw .gitattributes
```

The digest line is a check you run, not a comparison you eyeball: it prints `OK` or fails.
On Windows, `Get-FileHash flixw-0.25.0-pre.1.tar.gz` and `Expand-Archive` are the equivalents.

Pick `<version>` to satisfy the `flix` key your `flix.toml` already has. That key is Flix's
own field and flixw reads it as a **minimum**, so the same version or anything newer is
fine. Pinning something older is allowed but warned about, and every later command that
needs the compiler refuses until it is resolved, naming both numbers and the repair — `pin`
is deliberately still usable in that state, because lowering the floor in `flix.toml` may
be exactly what you meant. flixw never edits the manifest itself.

A `.zip` with the same contents is attached for machines without `tar`. The archives leave
`.gitattributes` alone rather than overwriting the one your project already has, which is
why `doctor --fix` — which *merges* the block — is a step of its own; `install` does that
merge itself. It comes after `pin` because it reports on the whole installation, and until
there is a lock the honest report is that one is missing.

Install from a release rather than from `main`: a tool that asks you to pin an exact
compiler should not ask you to fetch itself from a moving branch.

Once installed, `./flixw wrapper --upgrade` moves the project to the newest release.
`./flixw wrapper --help` prints the routing table: which verbs go to the compiler, which to
the wrapper, and how to force either.

## The lock, in your editor

`.flixw/lock.toml` is generated, and its first line says what it is:

```toml
#:schema https://wstein.github.io/flixw/schema/lock-v1.schema.json
```

That is the directive [taplo](https://taplo.tamasfe.dev) and the Even Better TOML
extension follow, so an editor validates the file — completing keys, flagging a mistyped
one, explaining what each holds — with nothing configured per project. `./flixw wrapper --schema`
prints the same schema on stdout if you would rather validate offline; it is rendered from
the list stage 0 itself checks the lock against, so the two cannot disagree.

Nothing about the build depends on the line. A lock written by an older flixw has none, and
`./flixw validate` says so:

```console
$ ./flixw pin --refresh
flixw: rewrote .flixw/lock.toml in the shape flixw 0.25.0-pre.1 writes; the pin is unchanged
```

That is offline and moves nothing — same repository, version, URL, digest and java pin —
which is why it is not `pin <version>`, which would fetch the compiler again to write one
comment. `./flixw doctor --fix` performs the same rewrite as one repair among several.

The schema, the API docs for stage 0, and a short index of both live at
<https://wstein.github.io/flixw/>, published from the same tag as the release.

## TAB completion

```sh
./flixw wrapper --completion bash > ~/.local/share/bash-completion/completions/flixw
./flixw wrapper --completion zsh  > "${fpath[1]}/_flixw"
./flixw wrapper --completion fish > ~/.config/fish/completions/flixw.fish
./flixw wrapper --completion pwsh >> $PROFILE
```

Write it once. The script holds no verbs — it reads them when you press TAB from a note the
wrapper keeps in `.flixw/local/`, so completion follows the pin and you do not regenerate
anything after `./flixw pin`. Different projects on the same machine complete their own
verbs. Nothing starts a JVM at TAB time, so TAB stays instant.

The generator behind `wrapper --completion` is fetched from the flixw release you're
running and cached machine-wide, the same way `wrapper --upgrade` fetches `flixw.java`
itself — so the very first `--completion` call on a machine, for a given flixw release,
needs network once; every call after that, from any project, is offline.

In bash, if your compiler ships its own completer — a picocli-based fork does — flixw finds
it and options complete too. Everywhere else, and with stock Flix anywhere, completion
covers the verb and then hands over to ordinary filename completion.

`cmd.exe` is not supported and cannot be: it has no per-command completion mechanism.
PowerShell works against the `flixw.cmd` that already ships; nothing needs to move to a
`.ps1`.

## Plugins and tasks

Two ways to extend what `./flixw` runs, both opt-in. `pin`, `info`, `doctor`, `validate`
and `help` stay built in permanently — they are what a fresh clone needs before anything
else can be trusted — but everything past them can be a task or a plugin.

**Tasks** are the lightweight one: `.flixw/tasks.toml` is a flat `name = "shell command"`
table you write and commit yourself, the same idea as npm's `scripts`. Nothing is fetched.

```toml
build = "./flixw build && ./flixw build-jar"
```

```console
./flixw task            # lists the names you have defined
./flixw task build      # runs it
./flixw task build --release   # extra words are appended to the command
```

**Plugins** are the heavier one: third-party `.jar`, `.java` or `.flix` code, installed
once into a machine-wide, digest-verified cache and invoked by name.

```console
./flixw plugin install metrics 1.2.0 https://example.com/metrics/plugin.jar
./flixw plugin metrics --since 30d
./flixw plugin list
./flixw plugin remove metrics
```

Every invocation re-verifies the cached bytes against the digest install recorded, and says
on stderr that it is running unaudited third-party code — a digest proves the bytes have not
changed, not that they are safe. `--sha256 <digest>` at install time lets you pin the digest
you expect instead of trusting whatever the URL returns.

A plugin gets a small, versioned context — project root, cache location, the pinned
compiler's version and jar path, the JDK in use — as both environment variables
(`FLIXW_PROJECT_ROOT`, `FLIXW_CACHE_HOME`, `FLIXW_COMPILER_VERSION`, …) and a JSON file
named by `FLIXW_CONTEXT`, so it can extend what Flix does in this project without flixw
handing it a second, unverified compiler to run. A `.flix` plugin reads that same context
through `Sys.Env.getVar` even though it cannot receive command-line arguments — stock Flix
has no way to run a standalone file with `args`.

See [`docs/CONTRACT.md`](docs/CONTRACT.md#plugins-and-tasks) for exactly what is guaranteed.

## Testing a locally built compiler

If you build Flix yourself — a fork, or a patch you have not tagged yet — run it with:

```sh
FLIX_JAR=/path/to/flix.jar ./flixw run
```

Two caveats. The jar is **not** digest-verified, every such run says so on stderr, and
those runs are not evidence about the stock compiler. And a valid `.flixw/lock.toml` is
still required: the lock is read and drift is checked before the override is, so pin a
release first even if you intend to override it every time.

To set this and the other variables — `FLIX_JAVA_HOME`, `FLIX_CACHE_HOME`,
`FLIX_DIST_URL`, `FLIX_JVM_OPTS` — per project rather than per shell, write them into an
`.envrc` for [direnv](https://direnv.net) and run `direnv allow`. The full table is in
[docs/CONTRACT.md](docs/CONTRACT.md). direnv needs its hook in your shell's startup file
first:

```sh
eval "$(direnv hook bash)"   # ~/.bashrc
eval "$(direnv hook zsh)"    # ~/.zshrc
direnv hook fish | source    # ~/.config/fish/config.fish
```

The `.envrc` is bash whatever your own shell is — direnv evaluates it with bash and exports
the difference — so fish users still write `export FOO=bar` in it, not `set -x FOO bar`.

flixw itself never reads it; direnv sets the variables in your shell before flixw
starts, which is why it works in a terminal but not for an editor-spawned `flixw lsp`, and
why there is no `cmd.exe` equivalent. The template is safe to delete — nothing checks for
it. See [`docs/CONTRACT.md`](docs/CONTRACT.md#running-a-locally-built-compiler) for the full
rules.

## Documentation

- [`docs/CONTRACT.md`](docs/CONTRACT.md) — what is guaranteed, and the diagnostics table
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — measured overhead, with the method
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — what it cannot do, stated plainly
- [`docs/Flix_Bootstrap_Wrapper_Paper.md`](docs/Flix_Bootstrap_Wrapper_Paper.md) — the
  design paper this grew from, kept as historical evidence

## Repository layout

```text
flixw/
├── src/
│   ├── flixw.java             stage 0 — the whole bootstrap, one dependency-free Java file
│   ├── flixw-completion.java  TAB-completion generator — a wrapper-owned companion asset,
│   │                          fetched and cached on demand, never committed into a project
│   ├── flixw                  POSIX shim — finds a Java, prefers the compiled stage 0
│   └── flixw.cmd              cmd.exe shim — the same, without a POSIX shell
├── tests/             regression suite, unit checks, and a corpus of 95 real
│                      flix.toml files, checked against a TOML oracle
└── docs/              contract, benchmarks, limitations, design paper
```

## Development

```console
sh tests/lint.sh    # javac -Xlint:all -Werror, shellcheck, shim byte-parity
sh tests/run.sh     # regression suite (needs network on first run)
```

Both are required before a commit. In CI, `lint.sh` runs once on Linux — `javac` and
`shellcheck` answer the same on every platform — while `run.sh` runs on Linux, macOS and
Windows, on Java 21 and again on the tested ceiling, and Windows additionally gets a
`cmd.exe` job that installs into a scratch project and drives `flixw.cmd` end to end.

`sh tests/pack.sh <dir>` builds the release archives locally, by the same script the release
workflow runs — so a published digest can be reproduced rather than trusted. It needs
`java`, `zip`, `tar`, and `sha256sum` or `shasum`; the byte-for-byte reproduction of a
published archive needs GNU `tar`, which is why the release job runs on Linux.
`sh tests/fetch-corpus.sh` refreshes the manifest corpus; it is a maintenance tool rather
than a test, and needs `gh`, `curl` and python3. See
[`tests/corpus/README.md`](tests/corpus/README.md).

## License

MIT. These files are meant to be committed into other people's repositories; the license
permits that without conditions beyond attribution.
