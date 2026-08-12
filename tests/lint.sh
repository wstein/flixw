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
if javac -Xlint:all -Werror -d "$work/classes" \
        "$root/src/flixw.java" "$root/tests/UnitCheck.java" 2>"$work/javac.log"; then
  say "ok    javac -Xlint:all -Werror (stage 0 and unit checks)"
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

# --- 5. the wrapper namespace is spelled the same way everywhere -----------
# Diagnostics are required to name the command that repairs the problem, which makes a
# renamed command a wrong answer printed at the worst possible moment: four messages went
# on recommending `./flixw wrapper upgrade` for a release after flixw's own operations moved
# behind flags, and nothing failed. `./flixw wrapper` takes flags only, so a bare word after
# it is always a stale spelling -- and every flag it is told to run must be one the usage
# text offers.
usage=$(sed -n 's/.*usage: .\/flixw wrapper \[\(.*\)\].*/\1/p' "$root/src/flixw.java" | tr -d ' |')
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

# CRLF is load-bearing for cmd.exe: a LF-only .cmd breaks multi-line if/for blocks.
if od -c "$root/src/flixw.cmd" | grep -q '\\r'; then
  say "ok    src/flixw.cmd has CRLF line endings"
else
  bad "src/flixw.cmd must have CRLF line endings"
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "lint: clean"
else
  say "lint: $fail failure(s)"
  exit 1
fi
