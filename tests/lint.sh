#!/bin/sh
# flixw lint -- the repository's configured static checks. Run before every commit.
#
#   sh tests/lint.sh
#
# 1. javac -Xlint:all -Werror   stage 0 must compile clean on the Java it targets
# 2. shellcheck                 the POSIX shim is executed on machines we cannot test
# 3. shim byte-parity           src/flixw and src/flixw.cmd are the checked-in copies of
#                               the SHIM and CMD text blocks inside src/flixw.java, and
#                               `install` writes the latter. Drift means a project gets
#                               a shim whose published hash does not match this tree.
# 4. the Java floor             MIN_JAVA is written out again in both shims
# 5. the source floor           stage 0 still compiles at the release its diagnostics promise
# 6. the wrapper namespace      every `./flixw wrapper --x` spelling is one the usage offers
# 7. schema parity              docs/schema/ is what `wrapper --schema` emits, nothing else
# 8. javadoc                    the published API docs build with no malformed doc comment
# 9. CRLF                       src/flixw.cmd must keep its cmd.exe line endings
# 10. the size ratchet         stage 0 is shrinking to a verified launcher; the code-line
#                              ceiling and the comment-density floor hold that, pulling
#                              against each other so neither is met at the other's cost
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/flixw
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$root/tests/.work/lint
rm -rf "$work"
mkdir -p "$work"
fail=0

say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }

# --- 1. Java ---------------------------------------------------------------
# auxiliaryclass is off for this one compile, not project-wide: src/flixw-completion.java
# is deliberately a same-package companion file rather than a class merged into flixw.java
# (its file name is the release asset name ensureCompletionAsset fetches, which cannot be
# a valid Java identifier), and tests/UnitCheck.java deliberately calls its package-private
# render() directly rather than through a subprocess -- exactly the pattern this warning
# exists to flag by default, and exactly what this repository's own multi-file layout is.
if javac -Xlint:all,-auxiliaryclass -Werror -d "$work/classes" \
        "$root/src/flixw.java" "$root/src/flixw-completion.java" "$root/src/flixw-jdk.java" \
        "$root/tests/UnitCheck.java" 2>"$work/javac.log"; then
  say "ok    javac -Xlint:all -Werror (stage 0, completion generator and unit checks)"
else
  bad "javac"
  cat "$work/javac.log"
fi

# --- 2. shell --------------------------------------------------------------
if command -v shellcheck >/dev/null 2>&1; then
  # The shim reads FLIX_* from the environment by design; SC2154 would flag every one.
  scripts="$root/src/flixw"
  for s in "$root"/tests/*.sh; do [ -f "$s" ] && scripts="$scripts $s"; done
  # shellcheck disable=SC2086
  if shellcheck -s sh -e SC2154 $scripts >"$work/shellcheck.log" 2>&1; then
    say "ok    shellcheck"
  else
    bad "shellcheck"
    cat "$work/shellcheck.log"
  fi
else
  say "skip  shellcheck (not installed)"
fi

# --- 3. shim byte-parity ---------------------------------------------------
# `install` refuses to run inside an installed project, so give it a clean target.
if java "$root/src/flixw.java" install "$work/parity" >"$work/install.log" 2>&1; then
  for f in flixw flixw.cmd; do
    if cmp -s "$work/parity/$f" "$root/src/$f"; then
      say "ok    $f matches the text block in src/flixw.java"
    else
      bad "$f differs from what install writes; edit both sides"
      diff "$root/src/$f" "$work/parity/$f" || true
    fi
  done
  if [ -x "$work/parity/flixw" ]; then
    say "ok    installed shim is executable"
  else
    bad "installed shim is not executable"
  fi
else
  bad "install did not run"
  cat "$work/install.log"
fi

# --- 4. the Java floor is stated in three files ----------------------------
# MIN_JAVA is the authority, but a shim cannot import a Java constant, so the floor is
# written out in both of them -- in a message and in a numeric comparison. That is
# exactly the "written twice" hazard the shims are supposed to avoid, so it is checked
# rather than trusted: a MIN_JAVA bump that misses a shim would silently hand the
# compiled stage 0 to a JVM that cannot load it.
min=$(sed -n 's/.*static final int MIN_JAVA = \([0-9][0-9]*\).*/\1/p' "$root/src/flixw.java")
if [ -z "$min" ]; then
  bad "cannot read MIN_JAVA from src/flixw.java"
else
  floors=$( { grep -o 'Java [0-9][0-9]*+' "$root/src/flixw" "$root/src/flixw.cmd"
              grep -o -- '-ge [0-9][0-9]*'  "$root/src/flixw"
              grep -o 'LSS [0-9][0-9]*'     "$root/src/flixw.cmd"; } | grep -o '[0-9][0-9]*' | sort -u)
  if [ "$floors" = "$min" ]; then
    say "ok    the Java floor is $min in MIN_JAVA and in both shims"
  else
    bad "Java floor disagrees: MIN_JAVA=$min, shims say $(echo "$floors" | tr '\n' ' ')"
  fi
fi

# --- 5. stage 0 still compiles at its own floor ----------------------------
# SOURCE_FLOOR is the oldest javac that can compile stage 0, and it is a *claim made to
# users*: below Java 21 flixw still runs and offers to fetch a JDK, and the no-java
# diagnostic says how far down that offer reaches. One post-floor language feature would
# make the promise false with nothing failing, so it is compiled at that release.
floor=$(sed -n 's/.*static final int SOURCE_FLOOR = \([0-9][0-9]*\).*/\1/p' "$root/src/flixw.java")
if [ -z "$floor" ]; then
  bad "cannot read SOURCE_FLOOR from src/flixw.java"
elif javac --release "$floor" -d "$work/floor" "$root/src/flixw.java" >"$work/floor.log" 2>&1; then
  say "ok    stage 0 compiles at its stated floor (Java $floor)"
elif grep -q "release version $floor not supported" "$work/floor.log"; then
  # A javac new enough to have dropped that release cannot answer the question.
  say "skip  floor check (this javac no longer targets $floor)"
else
  bad "stage 0 no longer compiles at Java $floor, which its diagnostics promise"
  head -5 "$work/floor.log"
fi

# The JDK provisioner has the *same* floor, and for a sharper reason than stage 0's.
# Stage 0 source-launches a companion asset with the JVM it is itself running on, and this
# asset exists precisely for the machine whose only JVM is below MIN_JAVA -- so a Java 21
# construct in it would make the provisioner unrunnable in the one case it is for, with
# nothing failing until a user hit it. The completion asset carries no such constraint: it
# is only ever reached from a JVM that already cleared the floor.
if [ -n "$floor" ]; then
  if javac --release "$floor" -d "$work/floor-jdk" "$root/src/flixw-jdk.java" \
        >"$work/floor-jdk.log" 2>&1; then
    say "ok    the JDK provisioner compiles at Java $floor, the JVM it may be launched by"
  elif grep -q "release version $floor not supported" "$work/floor-jdk.log"; then
    say "skip  provisioner floor check (this javac no longer targets $floor)"
  else
    bad "src/flixw-jdk.java no longer compiles at Java $floor; it is launched by the"
    say "      too-old JVM it exists to replace, so it cannot use a newer language level"
    head -5 "$work/floor-jdk.log"
  fi
fi

# --- 6. the wrapper namespace is spelled the same way everywhere -----------
# Diagnostics are required to name the command that repairs the problem, which makes a
# renamed command a wrong answer printed at the worst possible moment: four messages went
# on recommending `./flixw wrapper upgrade` for a release after flixw's own operations moved
# behind flags, and nothing failed. `./flixw wrapper` takes flags only, so a bare word after
# it is always a stale spelling -- and every flag it is told to run must be one the usage
# text offers.
# Both extractions below read a bracketed list, and such a list is a Java string
# concatenation that may wrap across source lines once it grows past the margin -- as the
# usage line did when --completion was added. Line-at-a-time greps saw nothing there and
# reported the list as empty, which is the one answer a spelling check must never give
# quietly. Flatten first: join `" + "` continuations and drop the \n escapes, so what is
# under test is the set the list offers rather than the column it happens to end in.
# RS to a character the file cannot contain makes the whole source one record, which is
# what lets the join see across a newline at all.
flat=$work/wrapper-lists.txt
awk 'BEGIN{RS="\034"} { gsub(/\\n/," "); gsub(/"[ \t]*\n[ \t]*\+[ \t]*"/,""); print }' \
  "$root/src/flixw.java" > "$flat"
usage=$(sed -n 's/.*usage: .\/flixw wrapper \[\([^]]*\)\].*/\1/p' "$flat" | tr -d ' |')
stale=$(grep -o './flixw wrapper [a-z][a-z-]*' "$root/src/flixw.java" | sort -u || true)
flags=$(grep -o -- './flixw wrapper --[a-z-][a-z-]*' "$root/src/flixw.java" \
        | sed 's|.*wrapper ||' | sort -u)
# shellcheck disable=SC2086  # deliberate: the flags are ours and contain no spaces
set -- $flags
for flag do
  case $usage in *"$flag"*) ;; *) stale="$stale $flag" ;; esac
done
if [ -z "$stale" ]; then
  say "ok    every ./flixw wrapper spelling matches its usage line"
else
  bad "stale ./flixw wrapper spellings: $(echo "$stale" | tr '\n' ' ')"
fi

# The check above greps for one flag at a time, so a *list* of them was invisible to it --
# and the routing table's `./flixw wrapper [--help | ...]` line was a release behind for
# exactly that reason, offering four operations where the usage offered five. Every
# bracketed list must therefore hold the same set as the usage line, not merely a subset.
lists=$(grep -o -- './flixw wrapper \[[^]]*\]' "$flat" \
        | sed 's|.*wrapper \[||; s|\]||' | tr -d ' |' | sort -u)
if [ "$lists" = "$usage" ]; then
  say "ok    every bracketed ./flixw wrapper list offers what the usage does"
else
  bad "a ./flixw wrapper list disagrees with the usage line"
  say "      usage offers: $usage"
  say "      lists offer:  $(echo "$lists" | tr '\n' ' ')"
fi

# --- 7. the published schema matches the lock this stage 0 writes ----------
# The file name carries the lock format's major version, so a bump that renames the schema
# must move the committed file with it rather than leaving the old name to be served.
schema_version=$(sed -n 's/.*LOCK_SCHEMA_VERSION = "\([a-z0-9]*\)".*/\1/p' "$root/src/flixw.java")
if [ -z "$schema_version" ]; then
  bad "cannot read LOCK_SCHEMA_VERSION from src/flixw.java"
  schema_version=none
elif [ ! -f "$root/docs/schema/lock-$schema_version.schema.json" ]; then
  bad "LOCK_SCHEMA_VERSION is $schema_version but docs/schema/lock-$schema_version.schema.json does not exist"
else
  say "ok    docs/schema/ carries the lock format version stage 0 declares ($schema_version)"
fi


# docs/schema/ is what GitHub Pages serves, and what every generated lock points an editor
# at with its `#:schema` line. A schema describing a lock flixw no longer writes is worse
# than none, because an editor presents it as authority -- so it is generated, never
# edited, and the committed copy is diffed against what stage 0 emits.
if java "$root/src/flixw.java" wrapper --schema >"$work/schema.json" 2>"$work/schema.log"; then
  if cmp -s "$work/schema.json" "$root/docs/schema/lock-$schema_version.schema.json"; then
    say "ok    docs/schema/lock-$schema_version.schema.json matches wrapper --schema"
  else
    bad "docs/schema/lock-$schema_version.schema.json is stale; regenerate it:"
    say "      java src/flixw.java wrapper --schema > docs/schema/lock-$schema_version.schema.json"
    diff "$root/docs/schema/lock-$schema_version.schema.json" "$work/schema.json" || true
  fi
else
  bad "wrapper --schema did not run"
  cat "$work/schema.log"
fi

# --- 8. the API docs build ------------------------------------------------
# These are published to GitHub Pages from a tag, where a malformed doc comment is not a
# warning anybody sees -- it silently swallows the text around it. `<version>` and
# `<owner>/<repo>` read as HTML tags and disappeared from four comments that way, so the
# check runs the doclint groups that catch it and treats a warning as a failure.
#
# The `missing` group is deliberately off. It wants @param and @return on every one of
# ~100 package-private helpers, which is the "explain what the line does" documentation
# this repository's conventions reject; the groups left on are about comments being
# *wrong*, not about there being fewer of them than a tool would like.
if javadoc -private -quiet -Xdoclint:all,-missing -Xwerror \
        -d "$work/javadoc" "$root/src/flixw.java" "$root/src/flixw-completion.java" "$root/src/flixw-jdk.java" \
        >"$work/javadoc.log" 2>&1; then
  say "ok    javadoc -private builds with no malformed doc comment"
else
  bad "javadoc"
  head -20 "$work/javadoc.log"
fi

# --- 9. cmd.exe line endings -----------------------------------------------
# CRLF is load-bearing for cmd.exe: a LF-only .cmd breaks multi-line if/for blocks.
if od -c "$root/src/flixw.cmd" | grep -q '\\r'; then
  say "ok    src/flixw.cmd has CRLF line endings"
else
  bad "src/flixw.cmd must have CRLF line endings"
fi

# --- 10. the size ratchet --------------------------------------------------
# Stage 0 is heading for a verified-launcher contract, and the three numbers below are
# how that shrink is held rather than intended. They are *ceilings at today's value*,
# not the target: a gate set at the target fails every commit until the last one, which
# means it gets commented out on the first day. Lower them as work lands; the advisory
# at the bottom says when they have gone slack.
#
# The line gate counts *code* lines, excluding blanks and comment-only lines. A gate on
# physical lines is a gate on comments, and comments are this repository's security
# story -- "audited by strangers who must trust it with a download" is not a style
# preference, it is the reason the density floor exists alongside the ceiling. The two
# pull against each other on purpose: neither can be met by sacrificing the other.
#
# Text blocks count as code. The shims embed shell `case` arms beginning with `*` and
# `/*`, which any leading-token classifier reads as javadoc -- so the density floor
# could otherwise be met by shipping more embedded shell, which is the opposite of what
# it is asking for.
MAX_CODE_LINES=3368          # target: 3050 -- see "What detaches, and what does not" in AGENTS.md
MIN_COMMENT_PCT=25           # floor, not a ceiling; today 27
MAX_BYTES=277921             # target: 237000, derived from the two numbers above
# The byte ceiling may move *up* when code lines move down and density moves up -- that is
# the two gates pulling against each other as intended, not drift. Refusing that would let
# them deadlock: any change trading code for the explanation this repository asks for would
# be unable to pass both. What must never rise is the code-line ceiling.

# shellcheck disable=SC2046  # deliberate: awk emits four bare integers to split on
set -- $(awk '
BEGIN { intb = 0 }
{
  line = $0; sub(/^[ \t]+/, "", line); n = length($0) + 1
  if (intb)                     { kl++; kb += n; if (line ~ /^"""/) intb = 0; next }
  if ($0 ~ /"""[ \t]*$/)        { kl++; kb += n; intb = 1; next }
  if (line == "")               { bl++; next }
  if (line ~ /^(\/\/|\*|\/\*)/) { cl++; next }
  kl++; kb += n
}
END { printf "%d %d %d %d\n", kl, cl, bl, kb+cb+bb }
' "$root/src/flixw.java")
code=$1; comments=$2; blanks=$3
bytes=$(wc -c < "$root/src/flixw.java" | tr -d ' ')
physical=$((code + comments + blanks))
density=$((comments * 100 / physical))

if [ "$code" -le "$MAX_CODE_LINES" ]; then
  say "ok    stage 0 is $code code lines (ceiling $MAX_CODE_LINES, target 3050)"
else
  bad "stage 0 grew to $code code lines; the ceiling is $MAX_CODE_LINES"
  say "      the target is 3050; raising the ceiling needs a reason"
fi

if [ "$density" -ge "$MIN_COMMENT_PCT" ]; then
  say "ok    comment density is $density% of $physical lines (floor $MIN_COMMENT_PCT%)"
else
  bad "comment density fell to $density%; the floor is $MIN_COMMENT_PCT%"
  say "      code shrinks by deleting subsystems, not by deleting the reasons for them"
fi

if [ "$bytes" -le "$MAX_BYTES" ]; then
  say "ok    src/flixw.java is $bytes bytes (ceiling $MAX_BYTES, target 237000)"
else
  bad "src/flixw.java grew to $bytes bytes; the ceiling is $MAX_BYTES"
fi

# A ratchet that is never tightened is a ceiling, and a ceiling well above the work is
# not a gate at all. Nudge, do not fail: the tightening is a deliberate edit, and it
# belongs in the commit that earned it rather than in whatever commit trips a threshold.
slack_lines=$((MAX_CODE_LINES - code))
slack_bytes=$((MAX_BYTES - bytes))
if [ "$slack_lines" -ge 100 ] || [ "$slack_bytes" -ge 5120 ]; then
  say "note  the ratchet has gone slack by $slack_lines lines / $slack_bytes bytes"
  say "      lower MAX_CODE_LINES to $code and MAX_BYTES to $bytes in tests/lint.sh"
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "lint: clean"
else
  say "lint: $fail failure(s)"
  exit 1
fi
