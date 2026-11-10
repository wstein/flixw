# Regression suite timing

The normal `sh tests/run.sh` output and assertions are unchanged. To record a run:

```sh
FLIXW_TEST_TIMINGS="$PWD/suite-timing.tsv" sh tests/run.sh
python3 tests/timing-report.py suite-timing.tsv
```

Use an absolute report path outside `tests/.work/run`: the suite clears that directory
and later changes directory. The reporter uses Python 3's standard library; the suite
itself and its timing collection require only the existing POSIX shell tools. Python
keeps report validation outside the measured JVM workload and adds no dependency to the
wrapper or installed projects.

Collection is opt-in. It runs `java -version` once for actual runtime metadata, then
starts the clock before the suite's scratch cleanup. There is one `date +%s` query at
each group boundary and two per sampled phase, never one per case. The disabled path
starts no clock processes. Timestamp resolution is one second; a short operation may
therefore report zero. Contiguous group boundaries telescope: their durations and
passed/failed/skipped counts must sum exactly to the measured suite totals. Timing
includes reporting overhead, shell assertions, fixture preparation and gaps between
commands. Metadata collection before the clock and whole-job setup are excluded.

Each passed or failed count is one invocation of the suite's `t` or `g` command harness;
a shell case can launch several processes and the UnitCheck case contains many Java
assertions. These counts are not JVM-launch counts. Skips retain their existing meaning.
The original suite exit status is recorded even on early failure; a missing final record
means the report is incomplete. A nonzero suite status remains visible in the report;
report reconciliation never turns a failed suite green.

Groups cover all measured time. Nested phase samples identify the first real compiler
acquisition (`./flixw pin`, including digest and compiler-version probing), a warm-cache
compiler invocation (`./flixw -- --version`, including wrapper startup and verification),
the deliberate SIGTERM/reaper wait probe where supported, UnitCheck fixture compilation,
and the UnitCheck JVM including its bounded timeout probes. These are elapsed command
measurements, not a claim to separate pure JVM startup, network transfer, compilation,
or deliberate sleep inside a command. The `fixtures` group includes generated fixture
compilation and project setup; `setup` includes release fixtures and auxiliary acquisition.
Do not add nested phase times to group times.

CI collects five reports: Linux/JDK 21 source, macOS/JDK 21 source, Linux/JDK 26 source,
Linux/JDK 21 shipped, and Windows/JDK 21 source. Artifact names preserve that identity;
the report records the actual OS and JDK version. Each suite uploads its TSV even after
failure. The `suite timing report` job requires all five files, validates format and
accounting, writes a Markdown job summary, and retains that summary as an artifact.
Missing or malformed timing data fails that report job without suppressing suite failures.
No workflow dispatch is needed: the existing PR/push events collect the measurements.

The report stratifies summed measured suite wall time (suite runner seconds) by OS, JDK,
source/shipped and cache metadata. The measurement window is the interval from earliest
suite start to latest suite end across runner clocks; it includes scheduling gaps and
has one-second endpoint precision. It is separate from the sum, which counts concurrent
suites once per runner. Neither number is CPU time, billed Actions time, or whole workflow
wall time. Compare like strata before drawing performance conclusions.

Cache-action restoration and effective suite cache state are separate fields. Currently
`run.sh` deletes its entire `tests/.work/run` directory, including restored compiler
entries, so the suite begins with an empty compiler cache even after an Actions cache
hit. Later commands reuse what the suite acquired. Picocli's separate `tests/.work` cache
is outside that reset and is not covered by the compiler-cache field. This instrumentation
does not change cache policy. Acquisition samples must be compared with fixture, help,
examples and local groups before proposing caching or sharding; the compiler download's
size alone establishes no dominant cost.

Accounting checks run without Java:

```sh
sh tests/timing-check.sh
python3 tests/timing-report-check.py
```

The shell test controls the clock and tests disabled collection and failure status.
Synthetic report records test nested-phase accounting, group/case reconciliation,
timestamps, invalid durations, incomplete reports, metadata and concurrent runner sums.
