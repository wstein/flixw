# Copilot instructions

The canonical instructions for this repository are in **`AGENTS.md` at the repository root**.
Read that file before proposing changes; it covers commands, architecture, and the invariants
below in full. This file is a pointer plus the constraints that are most expensive to violate.

`flixw` is a repository-local bootstrap that downloads, digest-verifies, caches and runs an
**unmodified stock Flix compiler JAR**. `src/flix.java` (stage 0) holds all logic; `src/flix`
and `src/flix.cmd` are shims that only locate a `java` and prefer the cached compiled stage 0.

- `src/flix.java` must stay **one file, dependency-free, Java 21** — no preview features, no
  build tool, no JBang. It is meant to be audited by strangers.
- The shims exist twice: `src/flix` / `src/flix.cmd` on disk, and the `SHIM` / `CMD` text blocks
  inside `src/flix.java` (what `install` writes). Edit both or they drift.
- Never patch, wrap, or link against `flix.jar`; it is launched as an opaque process.
- The cached compiler is SHA-256 verified on **every** run. One download attempt, at most one
  Java relaunch, no retry loops.
- `flix.toml` is the human authority; drift against `.flix-wrapper/lock.toml` fails before any
  network access, except for the `pin`, `doctor` and `validate` repair verbs.
- stdout belongs to the compiler. Wrapper messages go to stderr; cwd, argv and all three
  streams are inherited.
- Errors are `FLIXWnnn` identifiers on stderr (`001`–`009` fatal, advisory exits 80–88;
  `010`/`011` advisory only). Diagnostics must name the repair command.
- Degrade rather than brick: unparseable `flix --help`, an unwritable cache, or a missing
  `javac` each fall back silently instead of failing the command.
- `docs/Flix_Bootstrap_Wrapper_Paper.md` is the normative design spec; keep code and paper in
  step, or state which one you are deliberately letting lead.
- Conventional Commits (`feat:`, `docs:`, `refactor:`, `chore:`).
