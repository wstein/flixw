# Spec-driven compiler help and completion

`flixw-help.java` renders `./flixw help` and generates `./flixw completion <shell>` from one
picocli `CommandSpec` (see `tree()`).

The command model is **spec-derived**: a [picocli-spec](https://github.com/wstein/picocli/tree/develop/picocli-spec)
DSL file under [`src/assets/picocli/`](../../src/assets/picocli/) describes the exact
command/option/positional set for each compiler version range, authored directly from flix's
own source as a `CommandSpec`. This replaces the historical regex scraping of captured `--help`
text (`commands()` / `options()`) and the hand-maintained `appliesToVerb` truth table.

`loadSpec(version)` (in `flixw-help.java`) resolves a curated spec for the pinned compiler
version. `specVerb(c, name)` additionally gates it on provenance -- upstream, not a fork,
`FLIX_JAR`, or a selected local compiler -- since a spec authored against an exact upstream
release is not a claim about anything else. An uncurated version, fork, or `FLIX_JAR` override
gracefully falls back to the compiler's own captured help text verbatim.

## Why a spec instead of scraping and regex tables

The historical regex table only ever *removed* options from what `--help` already printed;
it could not add a positional's arity, a required flag, or a nested option group that
`--help` never laid out clearly. A spec is authored once against the compiler's real source
and carries all of that as data, so `./flixw help flix check` and `./flixw completion <shell>`
both gain accurate positionals and per-command flags without heuristic text scraping.

## Adding a curated version

1. Author a `flix-<version>.picocli` DSL file (and optional `.json` companion).
2. Place it in `src/assets/picocli/` and record its entry in `src/assets/picocli/README.md`.
3. In `picocli`'s build, the spec is packaged under `/specs/` in `picocli.jar`.
4. Add a `tests/UnitCheck.java` assertion in `curatedSpecs()` to verify that `loadSpec("<version>")`
   resolves and parses properly.

## On-demand spec loading

Specs are loaded dynamically on demand: `loadSpec(version)` first checks for the classpath resource
`/specs/flix-<version>.picocli` inside `picocli.jar` (via `flixwhelp.class.getResourceAsStream(...)`),
falling back to `src/assets/picocli/flix-<version>.picocli` on disk when running in development checkouts.
Zero spec text is hardcoded into `flixw-help.java`.

## Dependency: picocli-spec

`flixw-help.java` compiles against `picocli.spec.CommandSpecDsl` from the pinned `PICOCLI_VERSION`
jar. Because a companion asset is always recompiled against whatever `PICOCLI_VERSION` currently
resolves to, this is a hard, unconditional dependency, not a reflective one -- `PICOCLI_VERSION`
must therefore only ever point at a picocli release that bundles `picocli-spec`'s classes into the
same jar (the `io.github.wstein:picocli` fork does, starting with the release that ships this
feature), and the version bump and this feature must land in the same commit so neither ships
without the other.

## Help authority: stage 0 as minimal fallback, flixw-help as full authority

Stage 0 owns routing and process invocation, maintaining only single-line `*_USAGE` strings
for offline degradation when picocli or the asset cannot be fetched. All human-facing, formatted
help is delegated to `flixw-help.java`.

`renderWrapperHelp()` in stage 0 attempts to invoke `flixw-help.java` first, falling back silently
to the minimal usage string on failure.

## Wrapper verb and asset command specs

All wrapper-owned commands (`pin`, `info`, `doctor`, `validate`, `wrapper`, `completion`,
`examples`, `local`) define their `CommandSpec` in `flixw-help.java`. Sub-assets such as
`flixw-examples.java` and `flixw-local.java` carry zero picocli dependencies, keeping execution
logic cleanly separated from CLI presentation.

## Unified command model: tree() as single source of truth

`tree(c, name)` in `flixw-help.java` builds the unified command hierarchy for both `./flixw help`
and `./flixw completion <shell>`. It incorporates:
- Curated spec compiler commands and root options
- Wrapper commands with full subcommand models (`examplesSpec`, `localSpec`, `wrapperSpec`)
- Installed plugins from `lock.toml` under both `plugin <name>` and declared bare verbs
- Configured tasks from `tasks.toml`
This guarantees that shell completion candidates and help screens never drift.
