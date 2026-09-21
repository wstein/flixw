# Compiler CLI specs

`flix-<version>.picocli` files in this directory are [picocli-spec](https://github.com/wstein/picocli/tree/develop/picocli-spec)
DSL descriptions of the real `flix` CLI's commands, options and positionals for each
compiler version range. `flixw-help.java` loads the one matching the project's pinned
compiler version (on-demand from `/specs/flix-<version>.picocli` in `picocli.jar` or by reading
this directory) to render rich per-command help and generate full-fidelity shell completions;
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

## On-Demand Loading Architecture

`flixw-help.java` does not hardcode any spec text blocks in Java source code. Instead, `loadSpec(version)` resolves specs dynamically:
1. **Classpath resource**: Checks `/specs/flix-<version>.picocli` in `picocli.jar` (loaded via `flixwhelp.class.getResourceAsStream(...)`), providing instant, offline, zero-disk-overhead access.
2. **Local assets directory fallback**: Checks `src/assets/picocli/flix-<version>.picocli` relative to project/workspace root for development and testing.
3. **Graceful regex fallback**: If no spec file exists for the version, returns empty and falls back to regex-parsed `--help` output.
