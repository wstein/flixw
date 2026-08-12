#!/bin/sh
# Builds the release payload into <output-dir>:
#
#   flixw-<version>.tar.gz   the wrapper files, extracted over a project root
#   flixw-<version>.zip      the same, for a machine without tar
#   flix.java                stage 0 on its own, for the `java flix.java install .` route
#   SHA256SUMS               digests of all three
#
# The archives are not assembled by hand. `install` is run into a staging directory and
# whatever it wrote is what gets packed, so the archive route and the install route cannot
# diverge -- an archive built from a hand-written file list would have to repeat install's
# rules about CRLF and the executable bit, and would silently stop matching the day one of
# them changed. tests/run.sh proves the two are identical by extracting and diffing.
#
# This is a maintenance and release tool rather than a test. CI calls it on a tag; run it
# by hand to reproduce a published digest.
set -eu

out=${1:-}
if [ -z "$out" ]; then
  echo "usage: sh tests/pack.sh <output-dir>" >&2
  exit 2
fi
# shellcheck disable=SC1007  # CDPATH is cleared for these commands only; see src/flix
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$out"
# shellcheck disable=SC1007
out=$(CDPATH= cd -- "$out" && pwd)

for tool in java zip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "pack: $tool is required" >&2; exit 2; }
done
if command -v sha256sum >/dev/null 2>&1; then sum() { sha256sum "$@"; }
elif command -v shasum >/dev/null 2>&1;  then sum() { shasum -a 256 "$@"; }
else echo "pack: no sha256sum or shasum" >&2; exit 2
fi

version=$(java "$root/src/flix.java" wrapper --version | head -1 | cut -d' ' -f2)
case $version in
  [0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "pack: could not read the wrapper version (got '$version')" >&2; exit 1 ;;
esac

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT INT TERM
java "$root/src/flix.java" install "$stage" >/dev/null

# .gitattributes is deliberately not packed. install *merges* its block into whatever the
# project already has; an archive can only overwrite, and clobbering a project's own
# attributes to pin our four line endings is a bad trade. `./flix doctor --fix` merges it
# after extraction, and `./flix validate` says so if it was skipped.
rm -f "$stage/.gitattributes"

# Timestamps are normalised so that repacking the same commit yields the same bytes, and a
# published digest can be reproduced rather than merely trusted. zip stores DOS times,
# whose epoch is 1980; tar and gzip are given a real zero.
TZ=UTC find "$stage" -exec touch -t 198001010000 {} +

tar_flags=''
if tar --version 2>/dev/null | head -1 | grep -q GNU; then
  # Only GNU tar can pin member order and ownership. Elsewhere the archive is correct but
  # not bit-reproducible, which is why the release job runs on Linux.
  tar_flags='--sort=name --owner=0 --group=0 --numeric-owner --mtime=@0'
fi
# shellcheck disable=SC2086  # word splitting is the point; the flags are ours
(cd "$stage" && tar $tar_flags -cf - flix flix.cmd .flix-wrapper) \
  | gzip -9 -n > "$out/flixw-$version.tar.gz"

rm -f "$out/flixw-$version.zip"
# -X drops uid/gid and the extra timestamp fields, which are per-machine noise; the unix
# permission bits that carry the executable flag are not extra fields and survive it.
# TZ=UTC again: zip stores wall-clock local time, so the same tree packed in Berlin and in
# UTC would otherwise differ in four bytes per member.
(cd "$stage" && TZ=UTC zip -qrX "$out/flixw-$version.zip" flix flix.cmd .flix-wrapper)

cp "$root/src/flix.java" "$out/flix.java"

(cd "$out" && sum "flixw-$version.tar.gz" "flixw-$version.zip" flix.java > SHA256SUMS)
echo "packed flixw $version into $out"
cat "$out/SHA256SUMS"
