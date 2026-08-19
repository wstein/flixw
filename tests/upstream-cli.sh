#!/bin/sh
# Watches Flix's CLI definition for changes that would move flixw's ground.
#
#   sh tests/upstream-cli.sh            compare upstream against the committed fingerprint
#   sh tests/upstream-cli.sh --update   adopt what upstream currently has
#
# Exit 0 unchanged, 1 drifted, 2 could not tell.
#
# Why this exists. flixw parses `flix --help` to learn which verbs the pinned compiler
# implements, because dispatch is compiler-first and a verb the compiler owns must reach the
# compiler. That parse reads a *layout*, and the layout is a consequence of how Main.scala
# builds its parser. When upstream changes that, flixw does not fail loudly -- it falls back
# to a built-in verb table and keeps working, silently missing whatever was added. This is
# the thing that catches it.
#
# **It watches the parser surface, not the file.** Main.scala churns constantly for reasons
# that never touch the CLI, and a monitor that fires on every commit is one that gets muted
# inside a month -- which is the same as not having it. So the fingerprint is the sorted set
# of command, option and argument *names and kinds*, with prose deliberately excluded: a
# reworded `.text(...)` changes the help screen a user reads but not the contract flixw
# dispatches against.
#
# **It never gates ordinary CI.** flixw's build must not go red because somebody else merged
# something. The workflow that runs this opens an issue instead.
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only, as in the other scripts
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
fingerprint=$root/tests/upstream/flix-cli.fingerprint
repo=${FLIX_UPSTREAM_REPO:-flix/flix}
path=main/src/ca/uwaterloo/flix/Main.scala
work=$root/tests/.work/upstream
mkdir -p "$work" "$(dirname "$fingerprint")"

# Resolved, not hardcoded. flix/flix is on `master`; the obvious guess is `main`, which 404s,
# and a project that renames its default branch would otherwise silently stop being watched.
branch=$(curl -fsSL "https://api.github.com/repos/$repo" 2>/dev/null \
         | sed -n 's/.*"default_branch": *"\([^"]*\)".*/\1/p' | head -1)
[ -n "$branch" ] || branch=master

src=$work/Main.scala
curl -fsSL -o "$src" "https://raw.githubusercontent.com/$repo/$branch/$path" || {
  echo "upstream-cli: cannot fetch $repo@$branch/$path" >&2
  exit 2
}

# scopt's builders name the whole surface: cmd("check"), opt[Unit]("json"), arg[Int]("port").
# The type inside the brackets is kept because it is the difference between a flag and an
# option that takes a value, which is exactly what a completion has to get right.
extract() {
  grep -oE '(cmd|opt\[[A-Za-z._]+\]|arg\[[A-Za-z._]+\])\("[^"]+"\)' "$1" \
    | sed -E 's/^([a-z]+)\[([A-Za-z._]+)\]\("(.*)"\)$/\1 \2 \3/; s/^cmd\("(.*)"\)$/cmd - \1/' \
    | sort -u
}

extract "$src" > "$work/current"

if [ "${1:-}" = "--update" ]; then
  {
    echo "# The CLI surface of $repo@$branch:$path, as flixw last saw it."
    echo "# Regenerate with: sh tests/upstream-cli.sh --update"
    echo "# Prose is excluded on purpose -- see the header of that script."
    cat "$work/current"
  } > "$fingerprint"
  echo "upstream-cli: fingerprint updated from $repo@$branch ($(grep -cv '^#' "$fingerprint") entries)"
  exit 0
fi

if [ ! -f "$fingerprint" ]; then
  echo "upstream-cli: no committed fingerprint at $fingerprint" >&2
  echo "              run: sh tests/upstream-cli.sh --update" >&2
  exit 2
fi

grep -v '^#' "$fingerprint" > "$work/known"

if diff -u "$work/known" "$work/current" > "$work/diff" 2>&1; then
  echo "upstream-cli: unchanged -- $(wc -l < "$work/known" | tr -d ' ') entries, $repo@$branch"
  exit 0
fi

echo "upstream-cli: Flix's CLI surface has changed at $repo@$branch"
echo
sed -n '3,$p' "$work/diff"
echo
echo "What to check, in this order:"
echo "  1. tests/UnitCheck.java -- does parseVerbs still find every command in the new set?"
echo "  2. BUILTIN_VERBS in src/stage0/flixw.java -- it is the fallback when a parse fails,"
echo "     and a verb missing from it is a verb flixw silently will not route."
echo "  3. src/assets/flixw-help.java -- a new option *kind* changes what a completion must mark -r."
echo "  4. Adopt with: sh tests/upstream-cli.sh --update"
exit 1
