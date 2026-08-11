# Manifest corpus

95 real `flix.toml` files, copied verbatim from 76 public repositories, plus the answers a
conforming TOML parser gives for each of them.

## Why it exists

`tomlLookup()` in `src/flix.java` is a hand-written scanner, not a TOML parser — stage 0
has no dependencies by design. [`docs/LIMITATIONS.md`](../../docs/LIMITATIONS.md) is candid
that this is the weakest component in the wrapper, and the honest question about a
hand-written scanner is not whether it handles the specification, but whether it disagrees
with a real parser on the manifests people actually publish.

So this is a differential test. `expected.tsv` records what python3's `tomllib` reads from
each file; [`tests/UnitCheck.java`](../UnitCheck.java) requires flixw to agree, file by
file. The oracle runs once, at fetch time, so the test itself stays offline, deterministic
and fast — python3 is not a dependency of the suite.

The same corpus doubles as a property test for `pin`: repinning every manifest must change
exactly one line and read back as the new version. That is the invariant that broke when
`pin` carried its own scanner.

## What it does and does not cover

These are ordinary manifests. Not one of them contains a multi-line string, a dotted key,
a quoted table header or a duplicate table — which is precisely why `UnitCheck.java` also
carries 17 hand-written adversarial cases. The corpus establishes that flixw reads real
input correctly; the hand-written cases establish that it fails closed on input designed to
fool it. Neither is sufficient alone.

## Provenance and licensing

`PROVENANCE.tsv` names the repository, path and commit for every file, so any of them can
be re-fetched and compared. Each file is someone else's work, copied unmodified and used
here only as test input; none is a work of this project. Files are deduplicated by content,
so where several projects publish an identical manifest, only the first is kept.

## Refreshing

```console
sh tests/fetch-corpus.sh      # needs gh, curl and python3 3.11+
```

This is a maintenance tool, not a test: it is not run by `tests/lint.sh`, by
`tests/run.sh`, or by CI. Review the diff before committing — an upstream file changing
under a new commit is normal, but a *verdict* changing in `expected.tsv` means either the
oracle or flixw now reads a manifest differently, and that is worth understanding before
it is committed.
