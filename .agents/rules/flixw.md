# flixw workspace rules

The canonical instructions for this workspace are in **`AGENTS.md` at the repository root**.
Read that file before proposing changes — it covers commands, architecture, and the invariants
below in full. This rule file is a pointer plus the constraints that are most expensive to
violate.

`flixw` is a repository-local bootstrap that downloads, digest-verifies, caches and runs an
**unmodified stock Flix compiler JAR**. `src/flixw.java` (stage 0) holds all logic; `src/flixw`
and `src/flixw.cmd` are shims that only locate a `java` and prefer the cached compiled stage 0.

- `src/flixw.java` must stay **one file, dependency-free, Java 21** — no preview features, no
  build tool, no JBang. It is meant to be audited by strangers.
- The shims exist twice: `src/flixw` / `src/flixw.cmd` on disk, and the `SHIM` / `CMD` text blocks
  inside `src/flixw.java` (what `install` writes). Edit both or they drift.
- `docs/schema/lock-v1.schema.json` is **generated** from `LOCK_SCHEMA` in `src/flixw.java` by
  `java src/flixw.java wrapper --schema`, and `tests/lint.sh` diffs the two. Change the Java
  list and regenerate; never hand-edit the JSON.
- Never patch, wrap, or link against `flix.jar`; it is launched as an opaque process.
- **Never vendor picocli**, or any other library, to reach a completion generator. The
  compiler may be picocli-based; flixw observes that from the outside and delegates to the
  script the compiler emits. `src/flixw.java` imports nothing.
- The completion scripts are text blocks in `src/flixw.java` with **no on-disk copies** —
  unlike the shims. They are emitted by `wrapper --completion`, never installed, so
  `install`, `validate` and `doctor --fix` do not know about them. Do not add copies.
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
- `docs/Flix_Bootstrap_Wrapper_Paper.md` is the normative design spec; keep code and paper in
  step, or state which one you are deliberately letting lead.
- Conventional Commits (`feat:`, `docs:`, `refactor:`, `chore:`).
