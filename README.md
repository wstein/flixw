# flixw

[![CI](https://github.com/wstein/flixw/actions/workflows/ci.yaml/badge.svg)](https://github.com/wstein/flixw/actions/workflows/ci.yaml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Flix 0.75.2](https://img.shields.io/badge/flix-0.75.2-6a4c93.svg)](https://github.com/flix/flix/releases/tag/v0.75.2)
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

The only prerequisite is a Java 21+ JDK. `flixw` does not install one.

## Is this for you?

Probably not, and that is a deliberate answer. Flix is a pre-1.0 language shipping
roughly a release every two weeks, and its Community Build keeps downstream projects
near compiler head — which is how a small team gets fast, honest migration feedback. A
project that pins an exact compiler stops contributing that signal and accumulates
migration debt until it upgrades.

`flixw` is worth it when a project has already decided it values reproducibility over
automatic head-tracking: a release branch, a teaching repository, a paper artifact, a CI
job that must behave identically in a year. If that is not you, install Flix normally
and track head.

If you do adopt it, keep a head-compiler CI job alongside the pinned one.

## Who uses it

One project, honestly: [`flix-invaders`](https://github.com/wstein/flix-invaders), by the
same author. It replaced a hand-written download script with `./flix` and now type-checks,
tests, formats and packages through the wrapper, with a smoke job that launches the game in
a real window on Linux, macOS and Windows. It keeps a
[pin-lag log](https://github.com/wstein/flix-invaders/blob/main/docs/pin-lag.md) — one row
per Flix release — so the cost of pinning is recorded as a number rather than argued about.

That is one project and one compiler release, which is not yet evidence that pinning pays.
[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) says exactly what is and is not established.

## Getting it into a project

```console
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.19.0/flixw-0.19.0.tar.gz
shasum -a 256 flixw-0.19.0.tar.gz   # compare with the release notes before extracting it
tar -xzf flixw-0.19.0.tar.gz        # writes flix, flix.cmd, .flix-wrapper/flix.java
rm flixw-0.19.0.tar.gz
./flix doctor --fix                 # merges the .gitattributes block
./flix pin 0.75.2                   # writes the lock, fetches and verifies the compiler
git add flix flix.cmd .flix-wrapper .gitattributes
```

A `.zip` with the same contents is attached to every release for machines without `tar`.
The archives leave `.gitattributes` alone rather than overwriting the one your project
already has, which is why `doctor --fix` — which *merges* the block — is a step of its own.

`flix.java` is published on its own as well, for the equivalent route through the installer:

```console
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.19.0/flix.java
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
