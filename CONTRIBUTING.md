# Contributing to flixw

`flixw` is an experimental, repository-local bootstrap for the unmodified Flix
compiler. Contributions should preserve that boundary: stage 0 verifies and launches a
pinned stock `flix.jar`; it does not patch, link against, or otherwise extend the
compiler.

## Before opening a change

- Search existing issues before reporting a bug or proposing a feature.
- Report a suspected security vulnerability privately under the process in
  [SECURITY.md](SECURITY.md), not in a public issue.
- Keep a proposal narrow. Changes to acquisition, digest verification, lock parsing,
  Java selection, or dispatch need their security and compatibility consequences stated
  up front.

## Development checks

The wrapper has no build system. It is Java 21 source launched with JEP 330.

```sh
sh tests/lint.sh
sh tests/run.sh
```

Run both before requesting review. `lint.sh` checks compilation, documentation, shim
parity, lock-schema parity, and size; `run.sh` is the end-to-end regression suite.

## Change expectations

- Keep `src/flixw`, `src/flixw.cmd`, and the `SHIM`/`CMD` text blocks in
  `src/flixw.java` byte-identical where applicable.
- Preserve the stock-compiler, atomic-acquisition, unconditional-digest, and inherited
  stdio/cwd invariants.
- Add a regression for a behavioural change. Add a lock fixture rather than hard-coding
  its name when changing lock validation.
- Update the normative design paper and user-facing documentation when behaviour changes,
  or explicitly state which source intentionally leads.
- Use Conventional Commit subjects such as `fix:`, `feat:`, `docs:`, `refactor:`, or
  `chore:`.

Small, reviewable commits are preferred. Do not include generated caches, downloaded JARs,
or files from `tests/.work/`.
