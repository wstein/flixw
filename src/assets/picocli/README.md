# Compiler CLI specs

`flix-<version>.picocli` files in this directory are [picocli-spec](https://github.com/wstein/picocli/tree/develop/picocli-spec)
DSL descriptions of the real `flix` CLI's commands, options and positionals for one
compiler version range. `flixw-help.java` loads the one matching the project's pinned
compiler version (see `HelpTopic`/`tree()`) to render rich per-command help and generate
full-fidelity shell completions; when no spec matches the pinned version, it falls back to
the existing regex extraction over the compiler's own `--help` text, so an uncurated or
custom build never breaks.

| Compiler version | Spec file |
|---|---|
| `v0.76.2` | [`flix-0.76.2.picocli`](flix-0.76.2.picocli) |

Only one version is curated today. Adding another means copying the matching file from
[`picocli-spec/examples/`](https://github.com/wstein/picocli/tree/develop/picocli-spec/examples)
in the `picocli` fork (where it is authored and round-trip tested against the real compiler
jar) and adding a row above.
