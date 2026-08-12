# flixw

[![CI](https://github.com/wstein/flixw/actions/workflows/ci.yaml/badge.svg)](https://github.com/wstein/flixw/actions/workflows/ci.yaml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![dependencies: none](https://img.shields.io/badge/dependencies-none-brightgreen.svg)](src/flixw.java)
[![platforms: linux | macOS | windows](https://img.shields.io/badge/platforms-linux%20%7C%20macos%20%7C%20windows-lightgrey.svg)](.github/workflows/ci.yaml)

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
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.20.2/flixw.java
java flixw.java install .
rm flixw.java
```

<details>
<summary>The same, in PowerShell</summary>

```powershell
git init hello; cd hello
Invoke-WebRequest -OutFile flixw.java `
  https://github.com/wstein/flixw/releases/download/v0.20.2/flixw.java
java flixw.java install .
Remove-Item flixw.java
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
ok    ./flixw matches flixw 0.20.2
ok    ./flixw.cmd matches flixw 0.20.2
ok    .flixw/flixw.java  sha256=11854c8776a6885d...
ok    the lock satisfies flix.toml
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

Nine committed files and one that stays on your machine. flixw writes five of the nine and
shares a sixth; all of them are committed on purpose, so a clone needs no bootstrap step of
its own and no `flix` on `PATH`. (`init` also scaffolds a `README.md`, a `LICENSE.md`, a
`.gitignore` and a CI workflow, which are yours to keep or delete.)

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

`./flixw info` prints the one in use.

A real project on this: [`flix-invaders`](https://github.com/wstein/flix-invaders), by
the same author, which type-checks, tests, formats and packages through `./flixw` on
Linux, macOS and Windows, and keeps a
[pin-lag log](https://github.com/wstein/flix-invaders/blob/main/docs/pin-lag.md) — one row
per Flix release — so the cost of pinning is a number rather than an argument. That is one
project; [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) says exactly what is and is not
established.

## Adding it to an existing project

A project that already has sources and a `flix.toml` takes the quickstart route unchanged
— `curl` the single file, `java flixw.java install .`, then `pin`. `install` adds the
wrapper without touching anything else: it merges its block into an existing
`.gitattributes` rather than replacing it, and it never writes `flix.toml`.

The archive is the alternative, for when you would rather not run a downloaded program to
install a program:

```console
base=https://github.com/wstein/flixw/releases/download/v0.20.2
curl -fsSLO $base/flixw-0.20.2.tar.gz
curl -fsSL  $base/SHA256SUMS | grep flixw-0.20.2.tar.gz | shasum -a 256 -c -
tar -xzf flixw-0.20.2.tar.gz        # writes flixw, flixw.cmd, .flixw/flixw.java
rm flixw-0.20.2.tar.gz
./flixw pin <version>               # writes the lock, fetches and verifies the compiler
./flixw doctor --fix                # merges the .gitattributes block
git add flixw flixw.cmd .flixw .gitattributes
```

The digest line is a check you run, not a comparison you eyeball: it prints `OK` or fails.
On Windows, `Get-FileHash flixw-0.20.2.tar.gz` and `Expand-Archive` are the equivalents.

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
│   ├── flixw.java     stage 0 — the whole bootstrap, one dependency-free Java file
│   ├── flixw          POSIX shim — finds a Java, prefers the compiled stage 0
│   └── flixw.cmd      cmd.exe shim — the same, without a POSIX shell
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
