#!/bin/sh
# flixw lint -- the repository's configured static checks. Run before every commit.
#
#   sh tests/lint.sh
#
# 1. javac -Xlint:all -Werror   stage 0 must compile clean on the Java it targets
# 2. shellcheck                 the POSIX shim is executed on machines we cannot test
# 3. shim byte-parity           src/flix and src/flix.cmd are the checked-in copies of
#                               the SHIM and CMD text blocks inside src/flix.java, and
#                               `install` writes the latter. Drift means a project gets
#                               a shim whose published hash does not match this tree.
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/flix
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$root/tests/.work/lint
rm -rf "$work"
mkdir -p "$work"
fail=0

say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }

# --- 1. Java ---------------------------------------------------------------
if javac -Xlint:all -Werror -d "$work/classes" "$root/src/flix.java" 2>"$work/javac.log"; then
  say "ok    javac -Xlint:all -Werror"
else
  bad "javac"
  cat "$work/javac.log"
fi

# --- 2. shell --------------------------------------------------------------
if command -v shellcheck >/dev/null 2>&1; then
  # The shim reads FLIX_* from the environment by design; SC2154 would flag every one.
  scripts="$root/src/flix"
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
if java "$root/src/flix.java" install "$work/parity" >"$work/install.log" 2>&1; then
  for f in flix flix.cmd; do
    if cmp -s "$work/parity/$f" "$root/src/$f"; then
      say "ok    $f matches the text block in src/flix.java"
    else
      bad "$f differs from what install writes; edit both sides"
      diff "$root/src/$f" "$work/parity/$f" || true
    fi
  done
  if [ -x "$work/parity/flix" ]; then
    say "ok    installed shim is executable"
  else
    bad "installed shim is not executable"
  fi
else
  bad "install did not run"
  cat "$work/install.log"
fi

# CRLF is load-bearing for cmd.exe: a LF-only .cmd breaks multi-line if/for blocks.
if od -c "$root/src/flix.cmd" | grep -q '\\r'; then
  say "ok    src/flix.cmd has CRLF line endings"
else
  bad "src/flix.cmd must have CRLF line endings"
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "lint: clean"
else
  say "lint: $fail failure(s)"
  exit 1
fi
