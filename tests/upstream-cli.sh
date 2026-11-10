#!/bin/sh
# Watches Flix's CLI definition for changes that would move flixw's ground.
#
#   sh tests/upstream-cli.sh            compare upstream against the committed fingerprint
#   sh tests/upstream-cli.sh --update   adopt what upstream currently has
#
# Exit 0 unchanged, 1 drifted, 2 could not tell (UNKNOWN). Nothing else is ever returned: an
# unexpected failure inside the script, an empty or moved source, or an extractor that no
# longer understands the builder or enum syntax is reported as UNKNOWN (2) and never as clean
# or drift. The check runs in a subshell and its exit code counts only together with the
# verdict it recorded for this run; any other pair, a signal included, becomes 2.
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
# **A `.text(...)` string isn't the only place a value set hides.** `opt[LibLevel]("Xlib")`
# and `opt[Seq[Subeffecting]]("Xsubeffecting")` name their *kind*, but not which strings a
# user may pass -- flixw's own curated `.picocli` specs spell those out by hand (`"(nix, min,
# all)"`), because scopt's `Read` instances that parse them live in Main.scala as bare
# `case "nix" => LibLevel.Nix` matches, too unstructured to extract reliably. The enum's
# *case objects* are the structured source of the same set, and they live one file over, in
# Options.scala. Watching both closes the gap: a renamed or added tier changes the object
# name in Options.scala before anyone has to notice the matching string in Main.scala.
#
# **It never gates ordinary CI.** flixw's build must not go red because somebody else merged
# something. The workflow that runs this opens an issue instead.
set -u

# shellcheck disable=SC1007  # CDPATH is cleared for this command only, as in the other scripts
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=${FLIX_UPSTREAM_WORK:-$root/tests/.work/upstream}
vfile=

record() { printf '%s\n' "$1" > "$vfile"; }
unknown() {
  echo "upstream-cli: UNKNOWN -- $1" >&2
  record unknown
  exit 2
}

fingerprint=${FLIX_UPSTREAM_FINGERPRINT:-$root/tests/upstream/flix-cli.fingerprint}
repo=${FLIX_UPSTREAM_REPO:-flix/flix}
api=${FLIX_UPSTREAM_API:-https://api.github.com}
raw=${FLIX_UPSTREAM_RAW:-https://raw.githubusercontent.com}
path_main=main/src/ca/uwaterloo/flix/Main.scala
path_options=main/src/ca/uwaterloo/flix/util/Options.scala

# Each extractor writes to a file and its exit status is read. In a pipeline the status is
# the last stage's, so a grep that fails (2: unreadable) would look like "found nothing";
# the same holds for any `a | b` whose result is used, which is why none is. grep 1 means no
# match, which the coverage checks judge; 2 or more is a failure.
run_grep() { # <extended-regex> <file> <out>
  rc=0
  grep -oE "$1" "$2" > "$3" || rc=$?
  [ "$rc" -le 1 ] || unknown "grep failed (status $rc) on $2"
}
has_match() { # <extended-regex> <file>
  rc=0
  grep -qE "$1" "$2" || rc=$?
  [ "$rc" -le 1 ] || unknown "grep failed (status $rc) on $2"
  [ "$rc" -eq 0 ]
}
# wc pads on BSD; the arithmetic strips it, and a failing wc stops the run.
count() { c=$(wc -l < "$1"); printf '%s' "$((c + 0))"; }

# What may stand in front of a builder or `case`: anything that is not part of an identifier,
# so `subcmd("x")` is not `cmd("x")`. Spelled as a bracket expression because `\b` is a GNU
# extension that BSD grep and sed do not share.
before='(^|[^A-Za-z0-9_])'

# scopt's builders name the whole surface: cmd("check"), opt[Unit]("json"), arg[Int]("port").
# The type inside the brackets is kept because it is the difference between a flag and an
# option that takes a value, which is exactly what a completion has to get right. A builder
# may also wrap its type once more, as `opt[Seq[Subeffecting]]("Xsubeffecting")` does; the
# inner name is what is kept, since that is the type a completion or a curated spec cares
# about -- "a Subeffecting" is the fact, "a Seq of them" is scopt's own plumbing for `--x a,b`.
strict_builder='(cmd|opt\[[A-Za-z._]+(\[[A-Za-z._]+\])?\]|arg\[[A-Za-z._]+\])\("[^"]+"\)'
# What is left of the source once every entry the extractor reads has been cut out. A call
# that still starts like a builder there -- a third level of brackets, a name that is not a
# string literal, whitespace or a line break before the parenthesis -- is syntax the
# extractor cannot read, and the fingerprint would silently lose it. Judging each call, not
# totals, means one unreadable call cannot be hidden by a look-alike that happens to match.
leftover_builder="$before(cmd|opt|arg)[[:space:]]*[[(]"

# The complementary half: a sealed trait's case objects are the value set behind a custom
# scopt type -- LibLevel's Nix/Min/All, Subeffecting's ModDefs/InsDefs/Lambdas. Main.scala
# maps user-facing strings onto these ("nix" => LibLevel.Nix) as bare match arms, not a
# builder call, so that mapping is not what gets watched; the enum it draws from is.
# The file is read as one line, so `extends` on the next line is still found.
strict_enum='case[[:space:]]+object[[:space:]]+[A-Za-z0-9_]+[[:space:]]+extends[[:space:]]+[A-Za-z0-9_]+'
leftover_enum="${before}case[[:space:]]+object([^A-Za-z0-9_]|\$)"

# Extraction coverage. Every builder and every case object must be read by the strict
# pattern, and each source must yield what it is known to hold: commands and options from
# Main.scala, enum members from Options.scala. A parser that stopped matching would otherwise
# produce an empty set, and an empty set compared with an empty set is "unchanged".
extract() { # <file> <flat> <strict> <leftover> <out.raw> <label>
  tr '\n' ' ' < "$1" > "$2"
  run_grep "$before$3" "$2" "$5"
  sed -E "s/$before$3/\\1 /g" "$2" > "$2.rest"
  if has_match "$4" "$2.rest"; then
    unknown "$6 has a call the extractor cannot read (unsupported syntax)"
  fi
  sed -E 's/^[^A-Za-z0-9_]//' "$5" > "$5.trim"
}
extract_surface() {
  extract "$1" "$work/main.flat" "$strict_builder" "$leftover_builder" "$work/surface.raw" "$path_main"
  sed -E '
        s/^cmd\("(.*)"\)$/cmd - \1/
        s/^(opt|arg)\[[A-Za-z._]+\[([A-Za-z._]+)\]\]\("(.*)"\)$/\1 \2 \3/
        s/^(opt|arg)\[([A-Za-z._]+)\]\("(.*)"\)$/\1 \2 \3/
      ' "$work/surface.raw.trim"
}
extract_enums() {
  extract "$1" "$work/options.flat" "$strict_enum" "$leftover_enum" "$work/enums.raw" "$path_options"
  sed -E 's/^case[[:space:]]+object[[:space:]]+([A-Za-z0-9_]+)[[:space:]]+extends[[:space:]]+([A-Za-z0-9_]+)$/enum \2 \1/' \
    "$work/enums.raw.trim"
}

check() {
  mkdir -p "$work" "$(dirname "$fingerprint")"

  # Resolved, not hardcoded. flix/flix is on `master`; the obvious guess is `main`, which 404s,
  # and a project that renames its default branch would otherwise silently stop being watched.
  branch=$(curl -fsSL "$api/repos/$repo" 2>/dev/null \
           | sed -n 's/.*"default_branch": *"\([^"]*\)".*/\1/p' | head -1) || branch=
  [ -n "$branch" ] || branch=master

  # The required source inventory: every file the extractors read. A fetch that succeeds is
  # still not a source -- an error page, an empty body or a truncated file arrives with status
  # 200 -- so each file must also be non-empty. Missing one is UNKNOWN, never "no entries".
  main_src=$work/Main.scala
  options_src=$work/Options.scala
  rm -f "$main_src" "$options_src"
  fetch() {
    curl -fsSL -o "$2" "$raw/$repo/$branch/$1" || unknown "cannot fetch $repo@$branch/$1"
    [ -s "$2" ] || unknown "$repo@$branch/$1 is empty"
  }
  fetch "$path_main" "$main_src"
  fetch "$path_options" "$options_src"

  extract_surface "$main_src" > "$work/surface"
  grep -q '^cmd ' "$work/surface" || unknown "no command extracted from $path_main"
  grep -q '^opt ' "$work/surface" || unknown "no option extracted from $path_main"
  extract_enums "$options_src" > "$work/enums"
  [ -s "$work/enums" ] || unknown "no enum member extracted from $path_options"

  LC_ALL=C sort -u "$work/surface" "$work/enums" > "$work/current"

  if [ "${1:-}" = "--update" ]; then
    # Written aside and renamed: a failure part-way leaves the committed baseline as it was.
    {
      echo "# The CLI surface of $repo@$branch, as flixw last saw it:"
      echo "#   $path_main"
      echo "#   $path_options"
      echo "# Regenerate with: sh tests/upstream-cli.sh --update"
      echo "# Prose is excluded on purpose -- see the header of that script."
      cat "$work/current"
    } > "$fingerprint.new"
    mv "$fingerprint.new" "$fingerprint"
    echo "upstream-cli: fingerprint updated from $repo@$branch ($(grep -cv '^#' "$fingerprint") entries)"
    record updated
    exit 0
  fi

  if [ ! -f "$fingerprint" ]; then
    echo "upstream-cli: no committed fingerprint at $fingerprint" >&2
    echo "              run: sh tests/upstream-cli.sh --update" >&2
    record unknown
    exit 2
  fi

  grep -v '^#' "$fingerprint" > "$work/known" || true
  [ -s "$work/known" ] || unknown "the committed fingerprint $fingerprint has no entries"

  # diff's own failure (status 2) is not a difference (status 1).
  drc=0
  diff -u "$work/known" "$work/current" > "$work/diff" 2>&1 || drc=$?
  if [ "$drc" -eq 0 ]; then
    known_n=$(count "$work/known")
    echo "upstream-cli: unchanged -- $known_n entries, $repo@$branch"
    record clean
    exit 0
  fi
  [ "$drc" -eq 1 ] || unknown "diff failed (status $drc)"

  echo "upstream-cli: Flix's CLI surface has changed at $repo@$branch"
  echo
  sed -n '3,$p' "$work/diff"
  echo
  echo "What to check, in this order:"
  echo "  1. tests/UnitCheck.java -- does parseVerbs still find every command in the new set?"
  echo "  2. BUILTIN_VERBS in src/stage0/flixw.java -- it is the fallback when a parse fails,"
  echo "     and a verb missing from it is a verb flixw silently will not route."
  echo "  3. src/assets/flixw-cli.java -- a new option *kind* changes what a completion must mark -r."
  echo "  4. src/assets/picocli/*.picocli -- an \`enum\` line changes the value set an option's"
  echo "     curated help text names by hand (e.g. Xlib's \"(nix, min, all)\")."
  echo "  5. Adopt with: sh tests/upstream-cli.sh --update"
  record drift
  exit 1
}

# Why the check runs in a subshell and is judged afterwards. Under `set -e` any unexpected
# failure (a full disk, a missing `sed`) exits 1, which is this script's code for *drift*;
# the workflow would then open a drift issue for a machine problem. The check records a
# verdict only where it genuinely decided, in a file made for this run alone (a fixed name
# would be shared with a parallel run, or with a stale one), and its exit code is accepted
# only together with that verdict. Everything else, a signal included, is UNKNOWN.
mkdir -p "$work" 2>/dev/null || { echo "upstream-cli: UNKNOWN -- cannot create $work" >&2; exit 2; }
vfile=$(mktemp "$work/verdict.XXXXXX") || { echo "upstream-cli: UNKNOWN -- cannot create a verdict file in $work" >&2; exit 2; }
( set -e; check "$@" )
rc=$?
verdict=
read -r verdict < "$vfile" || verdict=
rm "$vfile" || { echo "upstream-cli: UNKNOWN -- cannot remove $vfile" >&2; exit 2; }
case "$rc:$verdict" in
  0:clean|0:updated|1:drift|2:unknown) exit "$rc" ;;
esac
echo "upstream-cli: UNKNOWN -- the check ended with status $rc and verdict '${verdict:-none}'; that is not a result" >&2
exit 2
