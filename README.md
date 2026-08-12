# flixw

[![CI](https://github.com/wstein/flixw/actions/workflows/ci.yaml/badge.svg)](https://github.com/wstein/flixw/actions/workflows/ci.yaml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![dependencies: none](https://img.shields.io/badge/dependencies-none-brightgreen.svg)](src/flix.java)
[![platforms: linux | macOS | windows](https://img.shields.io/badge/platforms-linux%20%7C%20macos%20%7C%20windows-lightgrey.svg)](.github/workflows/ci.yaml)

An **experimental, third-party, opt-in** repository bootstrapper for
[Flix](https://flix.dev). It pins a Flix compiler version in your project, verifies the
official release JAR against a committed SHA-256, and runs it — with no Flix
installation, no compiler fork, and no patched build.

> This is not an official Flix tool. It is not affiliated with or endorsed by the Flix
> project. It works against unmodified release JARs published by
> [`flix/flix`](https://github.com/flix/flix).

```console
git clone <your project>
cd <your project>
./flix check          # downloads and verifies the pinned compiler, then runs it
```

The only prerequisite is a Java 21+ JDK. If there is none, flixw says how to install
one for your platform, and `./flix wrapper --install-jdk` fetches a verified Temurin 21
into its own cache rather than touching your system.

## A worked example

From an empty directory to a running program. Every command and every line of output
below is from an actual run against the published v0.19.1 archive.

```console
mkdir hello && cd hello && git init
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.19.1/flixw-0.19.1.tar.gz
tar -xzf flixw-0.19.1.tar.gz && rm flixw-0.19.1.tar.gz
```

Write the manifest and a little code. `[package].flix` is Flix's own field: the **oldest**
compiler these sources are known to work with, as plain `x.x.x`.

```toml
# flix.toml
[package]
name        = "hello"
description = "a first Flix project"
version     = "0.1.0"
flix        = "0.75.2"
authors     = ["you"]
```

```flix
// src/Main.flix
def greet(name: String): String = "hello, ${name}"

def main(): Unit \ IO = println(greet("flix"))
```

```flix
// test/TestMain.flix
@Test
def testGreet(): Unit \ Assert = Assert.assertEq(expected = "hello, flix", greet("flix"))
```

Now pin a compiler. This is the step that makes the project reproducible: it fetches
Flix 0.75.2, hashes it, and records the digest in `.flix-wrapper/lock.toml`.

```console
$ ./flix pin 0.75.2
flixw: pinned Flix 0.75.2 from flix/flix (a2697d875725a0dd...)

$ ./flix doctor --fix
merged   ./.gitattributes
1 file rewritten from flixw 0.19.1
```

From here every verb is the stock compiler, run by the wrapper:

```console
$ ./flix test
Passed: 1, Failed: 0. Skipped: 0. Elapsed: 3.5ms.

$ ./flix run
hello, flix
```

Commit, and a collaborator with nothing but a JDK gets the same compiler you have:

```console
git add flix flix.cmd .flix-wrapper .gitattributes flix.toml src test
```

### What the project looks like

Nine files, of which flixw owns four. Everything below is committed on purpose — a
clone needs no bootstrap step of its own, and no `flix` on `PATH`.

```text
flix                        the wrapper you actually run; a POSIX sh shim
flix.cmd                    the same, for cmd.exe and PowerShell
.flix-wrapper/flix.java     stage 0: the whole bootstrap, one dependency-free Java file
.flix-wrapper/lock.toml     the pin — repository, version, URL and SHA-256 of the compiler
.gitattributes              line endings for the four files above, as a marked block
flix.toml                   your project: name, dependencies, and the minimum Flix
src/Main.flix               your code
test/TestMain.flix          your tests
```

The first four are byte-identical in every project on the same flixw release; only
`lock.toml` is yours. Not committed, and safe to delete at any time:

```text
build/  lib/  artifact/  .flix-cache/    Flix's own output and dependency cache
~/Library/Caches/flixw/                  verified compiler JARs, shared across projects
```

A real project on this: [`flix-invaders`](https://github.com/wstein/flix-invaders), by
the same author, which type-checks, tests, formats and packages through `./flix` on
Linux, macOS and Windows, and keeps a
[pin-lag log](https://github.com/wstein/flix-invaders/blob/main/docs/pin-lag.md) — one row
per Flix release — so the cost of pinning is a number rather than an argument. That is one
project; [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) says exactly what is and is not
established.

## Getting it into a project

```console
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.19.1/flixw-0.19.1.tar.gz
shasum -a 256 flixw-0.19.1.tar.gz   # compare with the release notes before extracting it
tar -xzf flixw-0.19.1.tar.gz        # writes flix, flix.cmd, .flix-wrapper/flix.java
rm flixw-0.19.1.tar.gz
./flix pin 0.75.2                   # writes the lock, fetches and verifies the compiler
./flix doctor --fix                 # merges the .gitattributes block
git add flix flix.cmd .flix-wrapper .gitattributes
```

A `.zip` with the same contents is attached to every release for machines without `tar`.
The archives leave `.gitattributes` alone rather than overwriting the one your project
already has, which is why `doctor --fix` — which *merges* the block — is a step of its own.
It comes after `pin` because it reports on the whole installation, and until there is a
lock the honest report is that one is missing.

`flix.java` is published on its own as well, for the equivalent route through the installer:

```console
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.19.1/flix.java
java flix.java install .          # writes all four files, merging .gitattributes
rm flix.java
```

Install from a release rather than from `main`: a tool that asks you to pin an exact
compiler should not ask you to fetch itself from a moving branch. Every release publishes
the SHA-256 of all three files it installs, and `./flix validate` prints the one it finds
in your project so you can compare it against the release you meant to install.

Once installed, `./flix wrapper --upgrade` moves the project to the newest release.

Then `./flix check`, `./flix test`, `./flix run` — the pinned stock compiler, unmodified.
`./flix wrapper --help` prints the routing table: which verbs go to the compiler, which
to the wrapper, and how to force either.

## Documentation

- [`docs/CONTRACT.md`](docs/CONTRACT.md) — what is guaranteed, and the diagnostics table
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — measured overhead, with the method
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — what it cannot do, stated plainly
- [`docs/Flix_Bootstrap_Wrapper_Paper.md`](docs/Flix_Bootstrap_Wrapper_Paper.md) — the
  design paper this grew from, kept as historical evidence

## Repository layout

```text
src/flix.java   stage 0 — the whole bootstrap, one dependency-free Java file
src/flix        POSIX shim  — finds a Java, prefers the compiled stage 0
src/flix.cmd    cmd.exe shim — same, for Windows without a POSIX shell
tests/          regression suite, unit checks, and a corpus of 95 real flix.toml
                files used to test the manifest scanner against a TOML oracle
docs/           contract, benchmarks, limitations, design paper
```

## Development

```console
sh tests/lint.sh    # javac -Xlint:all -Werror, shellcheck, shim byte-parity
sh tests/run.sh     # regression suite (needs network on first run)
```

Both are required before a commit, and both run in CI on Linux, macOS and Windows.
`sh tests/pack.sh <dir>` builds the release archives locally, by the same script the
release workflow runs — so a published digest can be reproduced rather than trusted.
`sh tests/fetch-corpus.sh` refreshes the manifest corpus; it is a maintenance tool rather
than a test, and needs `gh`, `curl` and python3. See
[`tests/corpus/README.md`](tests/corpus/README.md).

## License

MIT. These files are meant to be committed into other people's repositories; the license
permits that without conditions beyond attribution.
