#!/bin/sh
# Shell-only contract tests; the deterministic clock makes rounding and accounting exact.
set -eu
# shellcheck disable=SC1007
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' 0
export FLIXW_TEST_TIMINGS="$work/report.tsv"
(
  . "$root/tests/timing.sh"
  timing_now() { echo tick >> "$work/clock-calls"; cat "$work/clock"; }
  echo 10 > "$work/clock"
  pass=0 fail=0 skipped=0
  timing_init
  echo 12 > "$work/clock"
  pass=2 skipped=1
  timing_group compiler
  timing_phase_start acquisition pin
  echo 15 > "$work/clock"
  timing_phase_end
  fail=1
  echo 18 > "$work/clock"
  timing_finish 7
)
awk -F '\t' '
 $1=="group" && $2=="setup" && $3==2 && $4==2 && $6==1 {a++}
 $1=="group" && $2=="compiler" && $3==6 && $5==1 {b++}
 $1=="phase" && $2=="acquisition" && $3==3 && $4=="pin" {c++}
 $1=="total" && $2==8 && $3==2 && $4==1 && $5==1 && $6==7 {d++}
 END {exit !(a==1 && b==1 && c==1 && d==1)}' "$FLIXW_TEST_TIMINGS"
[ "$(wc -l < "$work/clock-calls" | tr -d " ")" -eq 5 ]
# Installing the EXIT reporter must preserve an early failure, including before any case.
set +e
FLIXW_TEST_TIMINGS="$work/failed.tsv" sh -c '
 set -eu
 . "$1/tests/timing.sh"
 pass=0 fail=0 skipped=0
 timing_init
 trap '\''timing_status=$?; timing_finish "$timing_status" || :; exit "$timing_status"'\'' 0
 exit 19' sh "$root"
rc=$?
set -e
[ "$rc" -eq 19 ]
awk -F '\t' '$1=="total" && $6==19 {ok=1} END {exit !ok}' "$work/failed.tsv"
# With reporting disabled there is no clock/process work and no output file.
disabled=$(FLIXW_TEST_TIMINGS='' sh -c '
 set -eu
 . "$1/tests/timing.sh"
 timing_now() { exit 99; }
 timing_init
 timing_group disabled
 timing_phase_start ignored ignored
 timing_phase_end
 timing_finish 0' sh "$root")
[ -z "$disabled" ]
# A failed reporter must not replace the original suite failure.
set +e
FLIXW_TEST_TIMINGS="$work/io-failed.tsv" sh -c '
 set -eu
 . "$1/tests/timing.sh"
 timing_init
 FLIXW_TEST_TIMINGS=/dev/null/missing-report
 trap '\''timing_status=$?; timing_finish "$timing_status" || :; exit "$timing_status"'\'' 0
 exit 19' sh "$root" 2>/dev/null
rc=$?
set -e
[ "$rc" -eq 19 ]
printf 'timing-check: 10 assertions passed\n'
