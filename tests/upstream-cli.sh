#!/bin/sh
# Watches Flix's CLI definition for changes that would move flixw's ground.
#
#   sh tests/upstream-cli.sh                      compare master's head with the committed fingerprint
#   sh tests/upstream-cli.sh --update             adopt what master's head currently has
#   sh tests/upstream-cli.sh --to <branch|sha>    the same for another master revision: the default branch
#                                                 by name, or a full lowercase 40-hex SHA (taken at its word,
#                                                 no request); a short or upper-case SHA, a tag or another
#                                                 branch is refused without --from
#   sh tests/upstream-cli.sh --from <ref|sha> [--to <ref|sha>]
#                                                 compare two refs (a release tag against master, say)
#                                                 without reading the committed fingerprint
#
# Every ref is resolved once to a full SHA and every source is fetched through that SHA, so a
# branch that moves between two downloads cannot give one comparison files from two commits.
# The release guard covers names only. Without --from, a branch or tag name other than the
# default branch is refused, but a SHA is taken at its word: a release's SHA given as --to is
# compared with the master baseline (reported as drift), and `--update --to <sha>` replaces
# that baseline with whatever the SHA holds. Nothing checks that a SHA is on the default
# branch; do not adopt a release's SHA into the committed fingerprint.
# The sources and their digests are kept under tests/.work/upstream/cache/<sha>/ and reused
# without the network when the ref given is a SHA that is already cached.
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
# A backtick is how Scala spells an identifier that is not one (`cmd`("x")); it is as unreadable
# to the extractor as a space.
bt='`'
leftover_builder="$before(cmd|opt|arg)[[:space:]]*[[($bt]"

# The complementary half: a sealed trait's case objects are the value set behind a custom
# scopt type -- LibLevel's Nix/Min/All, Subeffecting's ModDefs/InsDefs/Lambdas. Main.scala
# maps user-facing strings onto these ("nix" => LibLevel.Nix) as bare match arms, not a
# builder call, so that mapping is not what gets watched; the enum it draws from is.
# The file is read as one line, so `extends` on the next line is still found.
strict_enum='case[[:space:]]+object[[:space:]]+[A-Za-z0-9_]+[[:space:]]+extends[[:space:]]+[A-Za-z0-9_]+'
# Scala 3's `enum X { case A, B }` is another way to write the same value set, and one the
# strict pattern does not read: seeing the keyword is enough to stop.
leftover_enum="${before}(case[[:space:]]+object([^A-Za-z0-9_]|\$)|enum[[:space:]]+[A-Za-z_])"

# Extraction coverage. Every builder and every case object must be read by the strict
# pattern, and each source must yield what it is known to hold: commands and options from
# Main.scala, enum members from Options.scala. A parser that stopped matching would otherwise
# produce an empty set, and an empty set compared with an empty set is "unchanged".
# Comments are not source: a commented-out builder must not count as present, and a call after
# a comment on the same line must not be lost with it. A scan, not a regex, because `//` is
# also inside strings ("a//b") and `/*` can hide `//`; `"` inside a character literal ('"')
# is skipped so it does not open a string. The scan carries what Scala lets span lines across
# them: block comments, which nest, and """ strings, whose text (`src/*.flix` in a usage
# message) is not a comment. Single-quoted strings and literals end at the line.
# A scan that discards text silently is the failure this guards against, so one that cannot
# say where every discarded character came from fails: a comment or string still open at the
# end of the file (the rest of the file would be gone) is status 3, and any non-zero status
# of awk is UNKNOWN.
strip_comments() { # <in> <out> <label>
  rc=0
  awk '
    # A quote inside the ${...} of an interpolation starts a string the scan does not model; a
    # `//` or `/*` in it would be taken for a comment. Fail closed rather than guess.
    function bad_interp(line, k,   depth, m, ch) {
      depth = 0
      for (m = k + 1; m <= length(line); m++) {
        ch = substr(line, m, 1)
        if (ch == "{") depth++
        else if (ch == "}") { depth--; if (depth == 0) return 0 }
        else if (ch == "\"") return 1
      }
      return 0
    }
    BEGIN { blk = 0; tri = 0; bad = 0 }
    {
      out = ""; n = length($0); i = 1; str = 0
      while (i <= n) {
        c = substr($0, i, 1); d = substr($0, i, 2); t = substr($0, i, 3)
        if (blk > 0) {
          if (d == "/*") { blk++; i += 2 } else if (d == "*/") { blk--; i += 2 } else i++
          continue
        }
        if ((tri || str) && d == "${" && bad_interp($0, i)) { bad = 1; exit 4 }
        if (tri) {
          if (t == "\"\"\"") {
            j = i; while (substr($0, j, 1) == "\"") j++
            out = out substr($0, i, j - i); tri = 0; i = j
          } else { out = out c; i++ }
          continue
        }
        if (str) {
          out = out c
          if (c == "\\") { out = out substr($0, i + 1, 1); i += 2; continue }
          if (c == "\"") str = 0
          i++; continue
        }
        if (d == "//") break
        if (d == "/*") { blk = 1; out = out " "; i += 2; continue }
        if (t == "\"\"\"") { tri = 1; out = out t; i += 3; continue }
        if (c == "\047") {
          w = (substr($0, i + 1, 1) == "\\") ? 4 : 3
          if (substr($0, i + w - 1, 1) == "\047") { out = out substr($0, i, w); i += w; continue }
        }
        if (c == "\"") str = 1
        out = out c; i++
      }
      print out
    }
    END { if (!bad && (blk > 0 || tri)) exit 3 }' "$1" > "$2" || rc=$?
  case $rc in
    0) ;;
    3) unknown "$3 ends inside a comment or string; the rest of the file cannot be read" ;;
    4) unknown "$3 has a string inside \${...} of an interpolation, which the comment scan cannot read" ;;
    *) unknown "cannot scan $3 for comments (awk status $rc)" ;;
  esac
}
extract() { # <file> <flat> <strict> <leftover> <out.raw> <label>
  strip_comments "$1" "$2.nc" "$6"
  tr '\n' ' ' < "$2.nc" > "$2"
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

# Resolved, not hardcoded. flix/flix is on `master`; the obvious guess is `main`, which 404s,
# and a project that renames its default branch would otherwise silently stop being watched.
# No fallback to `master` when the record cannot be read: the branch being watched would then
# be a guess, and a stale `master` left behind by a rename would be watched in place of the
# real one. The next request (the commit lookup) needs the same API, so this costs nothing
# the run would not have lost anyway. Written to files so that no failing stage is hidden.
default_branch() { # -> $dflt
  curl -fsSL -o "$work/repo.json" "$api/repos/$repo" \
    || unknown "cannot read the repository record of $repo to find its default branch"
  sed -n 's/.*"default_branch": *"\([^"]*\)".*/\1/p' "$work/repo.json" > "$work/default_branch"
  read -r dflt < "$work/default_branch" || dflt=
  [ -n "$dflt" ] || unknown "the repository record of $repo names no default branch"
}

# A ref is resolved once, here, to the 40-hex commit it names right now; nothing later looks
# at the name again. The `sha` media type returns the bare SHA. A SHA given as the ref is
# already immutable and needs no request, which is what makes a cached revision usable
# offline. Anything that is not a full SHA afterwards -- an error page, a rate-limit body --
# is UNKNOWN: it would otherwise be used as a path component.
is_sha() { [ "${#1}" -eq 40 ] && case $1 in *[!0-9a-f]*) return 1 ;; *) return 0 ;; esac; }
# The ref is also a path component of the API URL, where '#', '?' and '%' would make the
# request name another ref than the one asked for. Git ref names cannot contain most of what
# is excluded here anyway; anything outside [A-Za-z0-9._/-] is refused, not encoded. So are
# `.` segments, which curl removes before sending (`./v0.79.0` would ask for `v0.79.0`).
valid_ref() { # <ref>
  case $1 in
    ''|-*|*[!A-Za-z0-9._/-]*|*..*|*//*|*/|.|./*|*/.|*/./*) return 1 ;;
  esac
}
resolve() { # <ref> -> $resolved
  valid_ref "$1" || unknown "'$1' is not a valid ref (allowed: letters, digits, . _ / -)"
  if is_sha "$1"; then resolved=$1; return; fi
  resolved=$(curl -fsSL -H 'Accept: application/vnd.github.sha' "$api/repos/$repo/commits/$1" 2>/dev/null) \
    || unknown "cannot resolve $repo@$1 to a commit"
  is_sha "$resolved" || unknown "$repo@$1 did not resolve to a full SHA"
}

# The required source inventory: every file the extractors read. A fetch that succeeds is
# still not a source -- an error page, an empty body or a truncated file arrives with status
# 200 -- so each file must also be non-empty. Missing one is UNKNOWN, never "no entries".
# The pair lands in the cache as one unit (written aside, then renamed), with the digests of
# both beside them: a half-written or later-modified cache entry fails its digests and is not
# used, and the digests are what a comparison can later be checked against.
# SHA256SUMS must name both sources: `sum -c` checks only what a file lists, so a list that
# dropped a name (or named another one twice) would leave that file unchecked.
cache_ok() { # <dir>
  [ -s "$1/Main.scala" ] && [ -s "$1/Options.scala" ] && [ -s "$1/SHA256SUMS" ] \
    && grep -Eq '^[0-9a-f]{64}  Main\.scala$' "$1/SHA256SUMS" \
    && grep -Eq '^[0-9a-f]{64}  Options\.scala$' "$1/SHA256SUMS" \
    && (cd "$1" && $sum -c SHA256SUMS >/dev/null 2>&1)
}
acquire() { # <sha> -> $srcdir
  srcdir=$work/cache/$1
  if cache_ok "$srcdir"; then return; fi
  rm -rf "$srcdir.tmp"
  mkdir -p "$srcdir.tmp"
  for pair in "$path_main:Main.scala" "$path_options:Options.scala"; do
    curl -fsSL -o "$srcdir.tmp/${pair#*:}" "$raw/$repo/$1/${pair%%:*}" \
      || unknown "cannot fetch $repo@$1/${pair%%:*}"
    [ -s "$srcdir.tmp/${pair#*:}" ] || unknown "$repo@$1/${pair%%:*} is empty"
  done
  (cd "$srcdir.tmp" && $sum Main.scala Options.scala > SHA256SUMS) || unknown "cannot digest the sources of $1"
  rm -rf "$srcdir"
  mv "$srcdir.tmp" "$srcdir"
  cache_ok "$srcdir" || unknown "the cached sources of $1 do not match their digests"
}

# The surface of one revision's sources, sorted, in <out>.
surface_of() { # <srcdir> <out>
  [ -s "$1/Main.scala" ] || unknown "$1/Main.scala is missing or empty"
  [ -s "$1/Options.scala" ] || unknown "$1/Options.scala is missing or empty"
  extract_surface "$1/Main.scala" > "$work/surface"
  grep -q '^cmd ' "$work/surface" || unknown "no command extracted from $path_main"
  grep -q '^opt ' "$work/surface" || unknown "no option extracted from $path_main"
  extract_enums "$1/Options.scala" > "$work/enums"
  [ -s "$work/enums" ] || unknown "no enum member extracted from $path_options"
  LC_ALL=C sort -u "$work/surface" "$work/enums" > "$2"
}

check() {
  mkdir -p "$work" "$(dirname "$fingerprint")"

  update=0; from=; to=
  while [ $# -gt 0 ]; do
    case $1 in
      --update) update=1 ;;
      --from|--to)
        [ $# -ge 2 ] || unknown "$1 needs a ref"
        if [ "$1" = --from ]; then from=$2; else to=$2; fi
        shift ;;
      *) unknown "unknown argument $1" ;;
    esac
    shift
  done
  [ -z "$from" ] || [ "$update" -eq 0 ] || unknown "--update adopts the --to side as the baseline; it takes no --from"

  # Before any request is made.
  for ref in "$from" "$to"; do
    [ -z "$ref" ] || valid_ref "$ref" || unknown "'$ref' is not a valid ref (allowed: letters, digits, . _ / -)"
  done

  if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum
  elif command -v shasum >/dev/null 2>&1; then sum="shasum -a 256"
  else unknown "neither sha256sum nor shasum is installed; source digests cannot be recorded"; fi

  # The head being checked. Without --from it is compared with the committed fingerprint, which
  # is the baseline of the repository's default branch; a release tag has its own surface and
  # comparing it with that baseline would report every difference between a release and master
  # as drift. So without --from the target must be the default branch (or one of its commits,
  # named by SHA); a release is compared with --from.
  if [ -z "$to" ] || { [ -z "$from" ] && ! is_sha "$to"; }; then
    default_branch
    if [ -z "$to" ]; then to=$dflt
    elif [ "$to" != "$dflt" ]; then
      unknown "--to $to is not $repo's default branch ($dflt); compare it with a baseline of its own: --from <ref> --to $to"
    fi
  fi
  resolve "$to"; to_sha=$resolved
  acquire "$to_sha"
  to_dir=$srcdir
  surface_of "$to_dir" "$work/current"

  if [ "$update" -eq 1 ]; then
    # Written aside and renamed: a failure part-way leaves the committed baseline as it was.
    {
      echo "# The CLI surface of $repo@$to, as flixw last saw it:"
      echo "# Revision: $to_sha"
      echo "# Sources and their sha256:"
      sed 's/^/#   /' "$to_dir/SHA256SUMS"
      echo "#   $path_main"
      echo "#   $path_options"
      echo "# Regenerate with: sh tests/upstream-cli.sh --update"
      echo "# Prose is excluded on purpose -- see the header of that script."
      cat "$work/current"
    } > "$fingerprint.new"
    mv "$fingerprint.new" "$fingerprint"
    echo "upstream-cli: fingerprint updated from $repo@$to $to_sha ($(grep -cv '^#' "$fingerprint") entries)"
    record updated
    exit 0
  fi

  if [ -n "$from" ]; then
    resolve "$from"; from_sha=$resolved
    acquire "$from_sha"
    surface_of "$srcdir" "$work/known"
    base="$repo@$from $from_sha"
  else
    if [ ! -f "$fingerprint" ]; then
      echo "upstream-cli: no committed fingerprint at $fingerprint" >&2
      echo "              run: sh tests/upstream-cli.sh --update" >&2
      record unknown
      exit 2
    fi
    grep -v '^#' "$fingerprint" > "$work/known" || true
    [ -s "$work/known" ] || unknown "the committed fingerprint $fingerprint has no entries"
    # Into a file and read back: in `$(a | b)` a failing `a` is invisible, an empty revision
    # would skip the digest comparison below, and a tampered cache would pass as drift.
    sed -n 's/^# Revision: //p' "$fingerprint" > "$work/revision"
    read -r rev < "$work/revision" || rev=
    base="the committed fingerprint (${rev:-revision not recorded})"
    # The cache vouches for itself with its own digests, which whoever altered a source can
    # rewrite. When the revision is the one the fingerprint was made from, the fingerprint's
    # record of the digests is the independent witness.
    if [ "$rev" = "$to_sha" ]; then
      sed -n 's/^#   \([0-9a-f]\{64\}  [A-Za-z.]*\)$/\1/p' "$fingerprint" > "$work/recorded.unsorted"
      LC_ALL=C sort "$work/recorded.unsorted" > "$work/recorded"
      LC_ALL=C sort "$to_dir/SHA256SUMS" > "$work/cached"
      # An empty record is a mismatch too: a revision without digests has nothing to vouch for the cache.
      if ! cmp -s "$work/recorded" "$work/cached"; then
        unknown "the sources of $to_sha differ from the digests recorded in the fingerprint; remove $to_dir"
      fi
    fi
  fi
  here="$repo@$to $to_sha"

  # diff's own failure (status 2) is not a difference (status 1).
  drc=0
  diff -u "$work/known" "$work/current" > "$work/diff" 2>&1 || drc=$?
  if [ "$drc" -eq 0 ]; then
    known_n=$(count "$work/known")
    echo "upstream-cli: unchanged -- $known_n entries, $base -> $here"
    record clean
    exit 0
  fi
  [ "$drc" -eq 1 ] || unknown "diff failed (status $drc)"

  echo "upstream-cli: Flix's CLI surface has changed: $base -> $here"
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
