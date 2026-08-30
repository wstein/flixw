# Copilot instructions

The canonical instructions for this repository are in **`AGENTS.md` at the repository root**.
Read that file before proposing changes; it covers commands, architecture, and the invariants
below in full. This file is a pointer plus the constraints that are most expensive to violate.

`flixw` is a repository-local bootstrap that downloads, digest-verifies, caches and runs an
**unmodified stock Flix compiler JAR**. `src/stage0/flixw.java` (stage 0) holds all logic; `src/stage0/flixw`
and `src/stage0/flixw.cmd` are shims that only locate a `java` and prefer the cached compiled stage 0.

- `src/stage0/flixw.java` must stay **one file, dependency-free, Java 21** — no preview features, no
  build tool, no JBang. It is meant to be audited by strangers.
- The shims exist twice: `src/stage0/flixw` / `src/stage0/flixw.cmd` on disk, and the `SHIM` / `CMD` text blocks
  inside `src/stage0/flixw.java` (what `install` writes). Edit both or they drift.
- `docs/schema/lock-v1.schema.json` is **generated** from `LOCK_SCHEMA` in `src/stage0/flixw.java` by
  `java src/stage0/flixw.java wrapper --schema`, and `tests/lint.sh` diffs the two. Change the Java
  list and regenerate; never hand-edit the JSON.
- Never patch, wrap, or link against `flix.jar`; it is launched as an opaque process.
- **Never vendor picocli, or any other library, into `src/stage0/flixw.java`.** It imports
  nothing and stays dependency-free; picocli is used only by the `flixw-help.java`
  companion asset, fetched as a verified release asset, never committed to a project.
- `help`, `completion <shell>`, `wrapper --install-jdk` and `examples` are answered by
  companion assets (`src/assets/flixw-*.java`), not stage 0 itself — fetched, digest-verified
  and cached the way `wrapper --upgrade` fetches `flixw.java`. `ensureAsset(name, version)`
  is the one way stage 0 reaches any of them; add a new command there, not by growing
  stage 0's own code for something a companion asset could do instead. They have **no
  on-disk copies** in a project — never installed, so `install`, `validate` and
  `doctor --fix` do not know about them.
- The cached compiler is SHA-256 verified on **every** run. One download attempt, at most one
  Java relaunch, no retry loops.
- `flix.toml` is the human authority; drift against `.flixw/lock.toml` fails before any
  network access, except for the `pin`, `doctor` and `validate` repair verbs.
- stdout belongs to the compiler. Wrapper messages go to stderr; cwd, argv and all three
  streams are inherited.
- Errors are `FLIXWnnn` identifiers on stderr (`001`–`009` fatal, advisory exits 80–88;
  `010`/`011` advisory only). Diagnostics must name the repair command.
- Degrade rather than brick: unparseable `flix --help`, an unwritable cache, or a missing
  `javac` each fall back silently instead of failing the command.
- `docs/Flix_Bootstrap_Wrapper_Paper.md` is the original design rationale, not kept in sync
  with the implementation. `docs/CONTRACT.md` is the current, accurate description of what
  ships; check there, not the paper.
- Conventional Commits (`feat:`, `docs:`, `refactor:`, `chore:`).
