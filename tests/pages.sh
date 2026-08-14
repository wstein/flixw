#!/bin/sh
# Builds the GitHub Pages site into <dir>.
#
#   sh tests/pages.sh site
#
# Run locally to see exactly what a tag publishes; .github/workflows/pages.yaml runs this
# and uploads the result. Nothing here is generated twice: the schema comes from stage 0,
# the API docs from javadoc, and the landing page from docs/pages/index.html with one
# placeholder substituted. A site built by hand in the workflow could describe a lock
# flixw does not write, which is the whole failure this arrangement exists to prevent.
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/flixw
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
out=${1:?usage: sh tests/pages.sh <dir>}
mkdir -p "$out"
# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/flixw
out=$(CDPATH= cd -- "$out" && pwd -P)

version=$(java "$root/src/flixw.java" wrapper --version | head -1 | cut -d' ' -f2)
schema_version=$(sed -n 's/.*LOCK_SCHEMA_VERSION = "\([a-z0-9]*\)".*/\1/p' "$root/src/flixw.java")
committed=$root/docs/schema/lock-$schema_version.schema.json

# The committed schema is what is published, and what tests/lint.sh diffs against stage 0.
# Checking it again here is not redundant: this is the last point before the file becomes
# the URL that every generated lock already points at, and a stale one published under a
# name that means "current" is worse than a broken link.
java "$root/src/flixw.java" wrapper --schema > "$out/.schema.check"
if ! cmp -s "$out/.schema.check" "$committed"; then
  echo "docs/schema/lock-$schema_version.schema.json is stale; run sh tests/lint.sh" >&2
  rm -f "$out/.schema.check"
  exit 1
fi
rm -f "$out/.schema.check"

mkdir -p "$out/schema"
cp "$committed" "$out/schema/lock-$schema_version.schema.json"
# An unversioned alias for anyone who wants "whatever flixw writes now" rather than a pin.
# Byte-identical, so its $id still names the versioned URL -- which is correct: $id is the
# schema's identity, not the path it happened to be fetched from.
cp "$committed" "$out/schema/lock.schema.json"

# -private, because the internals are what a reader has to trust before letting this file
# download and run a compiler; the public surface is one main().
javadoc -private -quiet -Xdoclint:all,-missing -Xwerror \
        -windowtitle "flixw $version" \
        -doctitle "flixw $version &mdash; stage 0" \
        -d "$out/javadoc" "$root/src/flixw.java"

sed "s/@VERSION@/$version/g" "$root/docs/pages/index.html" > "$out/index.html"

# Jekyll is on by default for Pages and would drop anything under a directory starting with
# an underscore -- which is most of what javadoc emits for a search index.
: > "$out/.nojekyll"

echo "built the site for flixw $version (lock schema $schema_version) into $out"
