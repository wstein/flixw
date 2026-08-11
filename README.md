# flixw

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

## Repository layout

```text
src/flix.java   stage 0 — the whole bootstrap, one dependency-free Java file
src/flix        POSIX shim  — finds a Java, prefers the compiled stage 0
src/flix.cmd    cmd.exe shim — same, for Windows without a POSIX shell
tests/          regression suite and fixtures
helper/         placeholder sbt module; built only if the wrapper-verb surface
                outgrows stage 0, which it has not
docs/           contract, benchmarks, limitations, design paper
```

## Development

```console
sh tests/lint.sh    # javac -Xlint:all -Werror, shellcheck, shim byte-parity
sh tests/run.sh     # regression suite (needs network on first run)
```

## License

MIT. These files are meant to be committed into other people's repositories; the license
permits that without conditions beyond attribution.
