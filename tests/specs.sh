#!/bin/sh
# Builds flixw-specs.jar: every curated src/assets/picocli/flix-*.picocli under specs/, which
# is where flixw-cli.java's loadSpec looks on its class path. Used by tests/pack.sh for the
# release and by tests/run.sh for its release fixture, so the suite renders curated help from
# the same artifact a user fetches rather than from this source tree.
#
# Why a jar and not one asset per spec: stage 0 would then have to know which file a compiler
# version maps to, and that range table belongs to the renderer. Why not inside picocli.jar:
# describing Flix's command line is not picocli's job, and a new Flix release would otherwise
# wait on a release of somebody else's library.
#
# Reproducible on purpose -- no manifest (it would name the JDK that built it) and a fixed
# timestamp -- so the digest in SHA256SUMS is a function of the specs alone.
#
#   sh tests/specs.sh <out.jar>
set -eu
out=${1:?usage: sh tests/specs.sh <out.jar>}
root=$(cd "$(dirname "$0")/.." && pwd)
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
mkdir "$stage/specs"
cp "$root"/src/assets/picocli/flix-*.picocli "$stage/specs/"
rm -f "$out"
# Named one by one in a sorted order rather than as the directory: jar walks a directory in
# whatever order the filesystem returns, and entry order is part of the bytes. `-C` applies
# to the single path after it, hence one per file. --date needs JDK 17+, below stage 0's 21.
set --
for f in $(cd "$stage" && LC_ALL=C ls specs/*.picocli); do set -- "$@" -C "$stage" "$f"; done
jar --create --no-manifest --date=2000-01-01T00:00:02Z --file "$out" "$@"
