# Spec-driven compiler help and completion

`flixw-help.java` renders `./flixw help` and generates `./flixw completion <shell>` from one
picocli `CommandSpec` (see `tree()`). Two ways that model gets built for a given compiler verb:

1. **Regex-derived** (the default, every version): `commands()`/`options()` scrape the pinned
   compiler's own `--help` text, and `appliesToVerb` narrows which options apply to which verb
   from a hand-maintained table re-traced against flix/flix's `Main.scala`/`Bootstrap.scala`.
2. **Spec-derived** (curated versions only): a [picocli-spec](https://github.com/wstein/picocli/tree/develop/picocli-spec)
   DSL file under [`src/assets/picocli/`](../../src/assets/picocli/) describes the exact
   command/option/positional set for one compiler version, built the same way the table in (1)
   is -- by reading flix's own source, not just its `--help` text -- but as a `CommandSpec`
   directly, so nothing is lost translating it back out of prose.

`loadSpec(version)` (in `flixw-help.java`) looks up a curated spec for the pinned compiler
version and returns it, or nothing. `specVerb(c, name)` additionally gates it on the same
provenance check `appliesToVerb`'s table already relies on -- upstream, not a fork, `FLIX_JAR`,
or a selected local compiler -- since a spec authored against one exact upstream release is not
a claim about anything else. `tree()` and `flix()` both prefer the spec-derived `CommandSpec`
for a verb when one exists, and fall back to (1) otherwise: an uncurated version, a fork, or a
parse failure in the spec text all degrade to the regex path exactly as before curation existed.

## Why a spec instead of growing the regex table further

The regex table only ever *removes* options from what `--help` already printed; it cannot add a
positional's arity, a required flag, or a nested option group `--help` never lays out clearly.
A spec is authored once against the compiler's real source and carries all of that as data, so
`./flixw help flix check` and `./flixw completion <shell>` both gain accurate positionals and
per-command flags without new code in this repository -- only a new spec file for a new curated
version.

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
- Curated or scraped compiler commands and root options
- Wrapper commands with full subcommand models (`examplesSpec`, `localSpec`, `wrapperSpec`)
- Installed plugins from `lock.toml` under both `plugin <name>` and declared bare verbs
- Configured tasks from `tasks.toml`
This guarantees that shell completion candidates and help screens never drift.
