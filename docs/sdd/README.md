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

1. Author (or copy from `picocli-spec/examples/` in the `picocli` fork, where it is round-trip
   tested against the real compiler jar) a `flix-<version>.picocli` file.
2. Add it to `src/assets/picocli/` and its row to `src/assets/picocli/README.md`.
3. Add its text as a new `FLIX_<version>_SPEC` constant in `flixw-help.java` and register it in
   `CURATED_SPECS` -- the embedded copy is what actually ships, the file under `src/assets/picocli/`
   is the human-reviewable original; keep them in sync the same way the shim files exist twice.
4. Add a `tests/UnitCheck.java` case asserting the spec parses and the subcommands it should
   carry are present (see `curatedSpecs()`).

## Why the spec text is embedded rather than fetched

Every other companion asset is its own file, fetched and verified independently. A spec is not:
it belongs to `flixw-help.java`'s own behaviour (which options render, which subcommands the
completer knows about) rather than being a program in its own right, and it is small enough
(one compiler version, ~6 KB) that a second fetch-and-verify round trip would cost more than it
saves. `src/assets/picocli/` exists purely so the spec is easy to find, diff and author as its
own file -- not as a second distribution path.

## Dependency: picocli-spec

`flixw-help.java` compiles against `picocli.spec.CommandSpecDsl` from the pinned `PICOCLI_VERSION`
jar. Because a companion asset is always recompiled against whatever `PICOCLI_VERSION` currently
resolves to, this is a hard, unconditional dependency, not a reflective one -- `PICOCLI_VERSION`
must therefore only ever point at a picocli release that bundles `picocli-spec`'s classes into the
same jar (the `io.github.wstein:picocli` fork does, starting with the release that ships this
feature), and the version bump and this feature must land in the same commit so neither ships
without the other.
