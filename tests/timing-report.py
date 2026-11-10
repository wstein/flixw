#!/usr/bin/env python3
"""Validate and summarize optional suite TSVs using only the Python standard library."""
import argparse
from collections import defaultdict
import csv
from pathlib import Path
import sys

METADATA = {"os", "jdk", "tree", "compiler_cache", "restored_cache", "precision_seconds"}


def numbers(fields):
    if any(not field.isascii() or not field.isdecimal() for field in fields):
        raise ValueError("expected nonnegative integer seconds/counts")
    return [int(field) for field in fields]


def read_report(path):
    metadata, groups, phases, totals = {}, [], [], []
    with path.open(encoding="utf-8") as stream:
        rows = list(csv.reader(stream, delimiter="\t"))
    if not rows or rows.pop(0) != ["format", "flixw-suite-timing-v1"]:
        raise ValueError("unknown timing format")
    for row in rows:
        if len(row) == 3 and row[0] == "meta" and row[1] not in metadata:
            metadata[row[1]] = row[2]
        elif len(row) == 6 and row[0] == "group":
            groups.append((row[1], *numbers(row[2:])))
        elif len(row) == 4 and row[0] == "phase":
            phases.append((row[1], *numbers(row[2:3]), row[3]))
        elif len(row) == 8 and row[0] == "total":
            totals.append(numbers(row[1:]))
        else:
            raise ValueError(f"invalid or duplicate timing record: {row}")
    if not METADATA.issubset(metadata) or any(not metadata[key] for key in METADATA):
        raise ValueError("missing timing metadata")
    if metadata["precision_seconds"] != "1":
        raise ValueError("unsupported clock precision")
    if len(totals) != 1:
        raise ValueError("expected exactly one total record (suite may have been interrupted)")
    seconds, passed, failed, skipped, status, started, ended = totals[0]
    if not groups or sum(group[1] for group in groups) != seconds:
        raise ValueError("group seconds do not reconcile with total elapsed seconds")
    if [sum(group[index] for group in groups) for index in (2, 3, 4)] != [passed, failed, skipped]:
        raise ValueError("group case counts do not reconcile with suite counts")
    if ended - started != seconds:
        raise ValueError("timestamps do not reconcile with total elapsed seconds")
    if any(phase[1] > seconds for phase in phases):
        raise ValueError("nested phase exceeds suite elapsed seconds")
    return dict(path=path, metadata=metadata, groups=groups, phases=phases, seconds=seconds,
                cases=[passed, failed, skipped], status=status, started=started, ended=ended)


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def table(headers, rows):
    return [" | ".join(headers), " | ".join("---" for _ in headers),
            *(" | ".join(cell(value) for value in row) for row in rows)]


def render(reports):
    seconds = sum(report["seconds"] for report in reports)
    span = max(report["ended"] for report in reports) - min(report["started"] for report in reports)
    lines = ["## Regression suite timing", "",
             f"Summed suite runner time: **{seconds} s** across {len(reports)} suite runs.",
             f"Suite measurement window: **{span} s** (earliest start to latest end).", "",
             "The window includes scheduling gaps; runner time sums measured suite wall times, not CPU time or whole job duration. "
             "Separate runner clocks have 1 s resolution. Phases are nested in groups and must not be added to them.", ""]
    strata = defaultdict(lambda: [0, 0])
    for report in reports:
        key = tuple(report["metadata"][name] for name in ("os", "jdk", "tree", "compiler_cache", "restored_cache"))
        strata[key][0] += 1
        strata[key][1] += report["seconds"]
    lines += table(["OS", "JDK", "Tree", "Suite compiler cache", "Cache action restored", "Runs", "Runner seconds"],
                   [(*key, *value) for key, value in sorted(strata.items())])
    for report in reports:
        lines += ["", f"### {cell(report['path'].name)}", "",
                  f"Elapsed: {report['seconds']} s; passed/failed/skipped: {'/'.join(map(str, report['cases']))}; "
                  f"suite exit: {report['status']}. Group elapsed and counts reconcile exactly.", ""]
        lines += table(["Group", "Seconds", "Passed commands", "Failed commands", "Skipped cases"], report["groups"])
        lines += ["", "Nested phase samples (one measured command/probe per row):", ""]
        lines += table(["Phase", "Seconds", "Command/probe"], report["phases"])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", type=Path, nargs="+")
    parser.add_argument("--expected", type=int, help="require this many suite reports")
    args = parser.parse_args()
    try:
        if args.expected is not None and len(args.reports) != args.expected:
            raise ValueError(f"expected {args.expected} reports, received {len(args.reports)}")
        reports = [read_report(path) for path in args.reports]
    except (OSError, ValueError) as error:
        parser.exit(1, f"timing-report: {error}\n")
    sys.stdout.write(render(reports))


if __name__ == "__main__":
    main()
