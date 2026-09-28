# Compiler CLI specs

`flix-<version>.picocli` files in this directory are [picocli-spec](https://github.com/wstein/picocli/tree/develop/picocli-spec)
DSL descriptions of the real `flix` CLI's commands, options and positionals for each
compiler version range. `flixw-cli.java` loads the one matching the project's pinned
compiler version (from `/specs/flix-<version>.picocli` in the release's `flixw-specs.jar`,
built from this directory) to render rich per-command help and generate full-fidelity shell completions;
when no spec matches the pinned version, it falls back to the existing regex extraction over
the compiler's own `--help` text, so an uncurated or custom build never breaks.

## Curated Compiler Versions

| Version Range | Spec File | Companion JSON | Key Capabilities & Changes |
|---|---|---|---|
| `v0.60.0` – `v0.66.2` | [`flix-0.60.0.picocli`](flix-0.60.0.picocli) | [`flix-0.60.0.json`](flix-0.60.0.json) | 14 subcommands, 14 experimental `-X` flags, `--args`, `--explain`, global `--listen` |
| `v0.67.0` | [`flix-0.67.0.picocli`](flix-0.67.0.picocli) | — | Adds `clean` subcommand |
| `v0.67.1` – `v0.67.2` | [`flix-0.67.1.picocli`](flix-0.67.1.picocli) | — | Adds `format` subcommand; restricts positional files strictly to `check`/`doc`/`format`/`test`; drops `--args` in favor of `--`; removes 4 `-X` flags |
| `v0.68.0` – `v0.72.0` | [`flix-0.68.0.picocli`](flix-0.68.0.picocli) | — | Adds `eff-check` and `eff-lock` subcommands; removes `--explain` |
| `v0.73.0` – `v0.75.1` | [`flix-0.73.0.picocli`](flix-0.73.0.picocli) | — | Adds `--top` compiler profiling option to compile options |
| `v0.75.2` | [`flix-0.75.2.picocli`](flix-0.75.2.picocli) | — | Adds experimental `--Xnewmono` flag (11 experimental flags) |
| `v0.75.3` | [`flix-0.75.3.picocli`](flix-0.75.3.picocli) | — | Adds `build-classes` subcommand; updates `clean` and `--Xprint-phases` descriptions |
| `v0.76.0` – `v0.76.1` | [`flix-0.76.0.picocli`](flix-0.76.0.picocli) | — | Adds `stat` subcommand; adds experimental `--Xverify`; removes `--Xsummary` |
| `v0.76.2` | [`flix-0.76.2.picocli`](flix-0.76.2.picocli) | [`flix-0.76.2.json`](flix-0.76.2.json) | Adds package management commands (`install`, `remove`, `upgrade`); adds `--library` to `doc` |
| `v0.77.0` | [`flix-0.77.0.picocli`](flix-0.77.0.picocli) | — | `install`/`remove` take one or more packages, `upgrade` any number; optional package on `eff-check`/`eff-lock`; `clean` no longer resolves dependencies; adds global `--pause-on-exit` |

## On-Demand Loading Architecture

`flixw-cli.java` does not hardcode any spec text blocks in Java source code. Instead, `loadSpec(version)` resolves specs dynamically:
1. **Release asset**: `tests/pack.sh` builds every file here into `flixw-specs.jar` (via `tests/specs.sh`), published and digest-verified like every other asset. Stage 0 puts it on the renderer's class path, where `loadSpec` reads `/specs/flix-<version>.picocli`. Not bundled into `picocli.jar`: Flix's command line is flixw's data, and a new Flix release should need a flixw release, not a library one.
2. **Unit checks**: `-Dflixw.specs.dir` points `tests/UnitCheck.java` at this directory.
3. **Graceful regex fallback**: No spec for the version, or no `flixw-specs.jar` in the release, falls back to regex-parsed `--help` output.

Adding a file here means naming it in `SPECS` in `flixw-cli.java` too; `tests/UnitCheck.java` fails if the two disagree.
