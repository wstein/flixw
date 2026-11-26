# Upstream CLI history

`Main.scala` and `Options.scala` of `flix/flix` at the ten releases 0.75.0 to 0.79.0 and at
master `7ac485c29d87e0eed5dcac2919decbb379096731` (2026-11-07), copied unmodified and used only as
input to `tests/upstream-cli.sh` through `tests/upstream-cli-matrix.sh`. Each directory is named
by the commit and has the layout of the detector's own cache (`Main.scala`, `Options.scala`,
`SHA256SUMS`), so the detector reads it without a request.

- `PROVENANCE.tsv`: tag, commit, entry count of the legacy fingerprint, git blob id and sha256 of
  each file. The blob ids are the ones GitHub's contents API returned for each file at that commit
  and equal `git hash-object` of the fixture; the commits are what the tags resolve to in the
  GitHub commits API.
- `transitions.tsv`: the expected exit code and the exact added/removed entries between neighbouring
  revisions, and which pairs the fingerprint cannot tell apart although behaviour changed.
- The master row is a preview, separate from the releases; its changes belong to no release.

The files carry upstream's own header, which names the Apache 2.0 license; `LICENSE.md` here is
upstream's license text (byte for byte, recorded in `PROVENANCE.tsv`) and `THIRD_PARTY_NOTICES.md` lists these files.
`/.gitattributes` marks the directory `-text` so that no checkout converts line endings and the
digests stay valid under `core.autocrlf`. Refresh or extend:
copy the two files at the new commit, run `sh tests/upstream-cli.sh --update --to <commit>` against a
scratch `FLIX_UPSTREAM_FINGERPRINT` for the count, add the rows, and verify the blob ids against
the contents API.

Source-reviewed only: no compiler was run for these fixtures; what a revision's behaviour is
beyond its fingerprint is recorded in wstein/flixw#16 and is not asserted here.
