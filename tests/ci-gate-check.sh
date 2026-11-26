#!/bin/sh
# Fixture tests for tests/ci-gate.sh, the gate of the heavy CI workflow, and a mutation
# check of those tests: each mutant of the gate must make at least one fixture fail, so
# a rule that the fixtures do not pin cannot be dropped unnoticed. No network, no GitHub.
# The mutants are opt-in; the default run is the fixtures only.
#
#   sh tests/ci-gate-check.sh                  fixtures
#   sh tests/ci-gate-check.sh --mutations      fixtures, then the mutants (or MUTATIONS=1)
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/stage0/flixw
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d)
trap 'rm -rf "$work"' 0

H=$(printf 'a%.0s' $(seq 1 40))   # the PR head under test
O=$(printf 'b%.0s' $(seq 1 40))   # some other head
P=$(printf '1%.0s' $(seq 1 40))   # the patch-id of the head under test
Q=$(printf '2%.0s' $(seq 1 40))   # a different patch-id
N=7

# run_case <script> <name> <want-rc> <want-text> <records> [extra gate args...]
# <records> is printed with printf %b, so \r and \n are written out. <want-text> must occur
# on the combined output, so a case fails for the reason it names and not for another.
run_case() {
  script=$1 name=$2 want_rc=$3 want_text=$4 records=$5
  shift 5
  printf '%b' "$records" > "$work/records"
  rc=0
  sh "$script" gate --head "$H" --pr "$N" --patch-id "$P" "$@" "$work/records" \
    > "$work/out" 2>&1 || rc=$?
  if [ "$rc" = "$want_rc" ] && grep -qF -- "$want_text" "$work/out"; then
    return 0
  fi
  printf 'FAIL  %s: rc=%s (want %s), output: %s\n' "$name" "$rc" "$want_rc" "$(cat "$work/out")"
  return 1
}

# run_result <script> <name> <want-rc> <want-text> <gate> <heavy>
run_result() {
  script=$1 name=$2 want_rc=$3 want_text=$4
  shift 4
  rc=0
  sh "$script" result "$@" > "$work/out" 2>&1 || rc=$?
  if [ "$rc" = "$want_rc" ] && grep -qF -- "$want_text" "$work/out"; then
    return 0
  fi
  printf 'FAIL  %s: rc=%s (want %s), output: %s\n' "$name" "$rc" "$want_rc" "$(cat "$work/out")"
  return 1
}

# run_all <script>: print one FAIL line per failing fixture; the count is the exit status.
run_all() {
  s=$1 bad=0
  c() { run_case "$s" "$@" || bad=$((bad + 1)); }
  r() { run_result "$s" "$@" || bad=$((bad + 1)); }
  clear_h="OWNER Review: CLEAR head=$H patch-id=$P pr=$N\n"

  c 'CLEAR for the head is accepted'          0 accepted "$clear_h"
  c 'MEMBER counts'                           0 accepted "MEMBER Review: CLEAR head=$H patch-id=$P pr=$N\n"
  c 'COLLABORATOR counts'                     0 accepted "COLLABORATOR Review: CLEAR head=$H patch-id=$P pr=$N\n"
  c 'no comment at all'                       1 'no CLEAR review comment' ''
  c 'CLEAR for another head'                  1 'no CLEAR review comment' "OWNER Review: CLEAR head=$O patch-id=$P pr=$N\n"
  c 'CLEAR for another PR'                    1 'no CLEAR review comment' "OWNER Review: CLEAR head=$H patch-id=$P pr=99\n"
  c 'CLEAR with pr=none'                      1 'no CLEAR review comment' "OWNER Review: CLEAR head=$H patch-id=$P pr=none\n"
  c 'CLEAR with a different patch-id'         1 'patch-id' "OWNER Review: CLEAR head=$H patch-id=$Q pr=$N\n"
  c 'CLEAR then FINDINGS of another head'     1 'FINDINGS' "$clear_h""OWNER Review: FINDINGS head=$O patch-id=$P pr=$N\n"
  c 'FINDINGS of another PR changes nothing'  0 accepted "$clear_h""OWNER Review: FINDINGS head=$O patch-id=$P pr=99\n"
  c 'CLEAR of another head comes last'        1 'another head' "$clear_h""OWNER Review: CLEAR head=$O patch-id=$P pr=$N\n"
  c 'Carried-over of a withdrawn CLEAR'       1 'FINDINGS' "OWNER Review: CLEAR head=$O patch-id=$P pr=$N\nOWNER Review: FINDINGS head=$O patch-id=$P pr=$N\nOWNER Carried-over: from=$O to=$H patch-id=$P pr=$N\n"
  c 'FINDINGS after Carried-over'             1 'FINDINGS' "OWNER Review: CLEAR head=$O patch-id=$P pr=$N\nOWNER Carried-over: from=$O to=$H patch-id=$P pr=$N\nOWNER Review: FINDINGS head=$O patch-id=$P pr=$N\n"
  c 'FINDINGS after CLEAR on the head'        1 'FINDINGS' "$clear_h""OWNER Review: FINDINGS head=$H patch-id=$P pr=$N\n"
  c 'CLEAR after FINDINGS on the head'        0 accepted "OWNER Review: FINDINGS head=$H patch-id=$P pr=$N\n$clear_h"
  c 'only FINDINGS'                           1 'FINDINGS' "OWNER Review: FINDINGS head=$H patch-id=$P pr=$N\n"
  c 'author NONE'                             1 'no CLEAR review comment' "NONE Review: CLEAR head=$H patch-id=$P pr=$N\n"
  c 'author CONTRIBUTOR'                      1 'no CLEAR review comment' "CONTRIBUTOR Review: CLEAR head=$H patch-id=$P pr=$N\n"
  c 'CRLF line ending'                        1 'no CLEAR review comment' "OWNER Review: CLEAR head=$H patch-id=$P pr=$N\r\n"
  c 'markdown bold'                           1 'no CLEAR review comment' "OWNER **Review: CLEAR head=$H patch-id=$P pr=$N**\n"
  c 'markdown code span'                      1 'no CLEAR review comment' "OWNER \`Review: CLEAR head=$H patch-id=$P pr=$N\`\n"
  c 'text after the line'                     1 'no CLEAR review comment' "OWNER Review: CLEAR head=$H patch-id=$P pr=$N ok\n"
  c 'uppercase hex'                           1 'no CLEAR review comment' "OWNER Review: CLEAR head=$(printf '%s' "$H" | tr a A) patch-id=$P pr=$N\n"
  c 'short head'                              1 'no CLEAR review comment' "OWNER Review: CLEAR head=aaaaaaa patch-id=$P pr=$N\n"
  c 'wrong key order'                         1 'no CLEAR review comment' "OWNER Review: CLEAR patch-id=$P head=$H pr=$N\n"
  c 'Carried-over to the head after a CLEAR'  0 accepted "OWNER Review: CLEAR head=$O patch-id=$P pr=$N\nOWNER Carried-over: from=$O to=$H patch-id=$P pr=$N\n"
  c 'Carried-over without a CLEAR to carry'   1 'no CLEAR review comment' "OWNER Carried-over: from=$O to=$H patch-id=$P pr=$N\n"
  c 'Carried-over from another patch-id'      1 'no CLEAR review comment' "OWNER Review: CLEAR head=$O patch-id=$Q pr=$N\nOWNER Carried-over: from=$O to=$H patch-id=$P pr=$N\n"
  c 'Carried-over to another head'            1 'no CLEAR review comment' "OWNER Review: CLEAR head=$O patch-id=$P pr=$N\nOWNER Carried-over: from=$O to=$O patch-id=$P pr=$N\n"
  c 'Carried-over then FINDINGS'              1 'FINDINGS' "OWNER Review: CLEAR head=$O patch-id=$P pr=$N\nOWNER Carried-over: from=$O to=$H patch-id=$P pr=$N\nOWNER Review: FINDINGS head=$H patch-id=$P pr=$N\n"
  c 'the head moved since the event'          1 'moved' "$clear_h" --current-head "$O"
  c 'the head did not move'                   0 accepted "$clear_h" --current-head "$H"

  # Stack tip: the CLEAR carries the patch-id of main...tip, not of parent...tip.
  # The diffs are built with --no-index so that no commit, and no identity, is needed.
  sp=$(sh "$s" patch-id < "$work/diff-main-tip" 2>/dev/null) || sp=''
  pp=$(sh "$s" patch-id < "$work/diff-parent-tip" 2>/dev/null) || pp=''
  if [ -z "$sp" ] || [ -z "$pp" ] || [ "$sp" = "$pp" ]; then
    printf 'FAIL  patch-id: cumulative %s and parent %s must exist and differ\n' "$sp" "$pp"; bad=$((bad + 1))
  else
    printf '%b' "OWNER Review: CLEAR head=$H patch-id=$sp pr=$N\n" > "$work/records"
    rc=0; sh "$s" gate --head "$H" --pr "$N" --patch-id "$sp" "$work/records" > "$work/out" 2>&1 || rc=$?
    [ "$rc" = 0 ] || { printf 'FAIL  stack tip with the cumulative patch-id: rc=%s\n' "$rc"; bad=$((bad + 1)); }
    rc=0; sh "$s" gate --head "$H" --pr "$N" --patch-id "$pp" "$work/records" > "$work/out" 2>&1 || rc=$?
    if [ "$rc" != 1 ] || ! grep -qF 'patch-id' "$work/out"; then
      printf 'FAIL  stack tip with the parent patch-id: rc=%s\n' "$rc"; bad=$((bad + 1))
    fi
  fi
  rc=0; sh "$s" patch-id < /dev/null > "$work/out" 2>&1 || rc=$?
  [ "$rc" = 1 ] || { printf 'FAIL  patch-id of an empty diff: rc=%s (want 1)\n' "$rc"; bad=$((bad + 1)); }

  rc=0; sh "$s" gate --pr "$N" --patch-id "$P" /dev/null > "$work/out" 2>&1 || rc=$?
  [ "$rc" = 2 ] || { printf 'FAIL  missing --head: rc=%s (want 2)\n' "$rc"; bad=$((bad + 1)); }
  rc=0; sh "$s" gate --head abc --pr "$N" --patch-id "$P" /dev/null > "$work/out" 2>&1 || rc=$?
  [ "$rc" = 2 ] || { printf 'FAIL  malformed --head: rc=%s (want 2)\n' "$rc"; bad=$((bad + 1)); }

  r 'result passed'                 0 'heavy-result: passed'    success success
  r 'result failed'                 1 'heavy-result: failed'    success failure
  r 'result heavy skipped'          1 'heavy-result: skipped'   success skipped
  r 'result heavy cancelled'        1 'heavy-result: cancelled' success cancelled
  r 'result gate refused'           1 'heavy-result: skipped'   failure skipped
  r 'result gate not run'           1 'heavy-result: deferred'  skipped skipped
  r 'result gate cancelled'         1 'heavy-result: cancelled' cancelled skipped
  return "$bad"
}

# main -> parent -> tip, as files: the tip's cumulative diff differs from the parent's.
mkdir "$work/a" "$work/b" "$work/c"
printf 'one\n' > "$work/a/f"
printf 'one\ntwo\n' > "$work/b/f"
printf 'one\ntwo\nthree\n' > "$work/c/f"
git diff --no-index "$work/a" "$work/b" > "$work/diff-main-parent" || true
git diff --no-index "$work/a" "$work/c" > "$work/diff-main-tip" || true
git diff --no-index "$work/b" "$work/c" > "$work/diff-parent-tip" || true

gate=$root/tests/ci-gate.sh
fails=$(run_all "$gate" 2>&1) && nfail=0 || nfail=$?
total=$(( $(grep -cE "^  [cr] '" "$0") + 6 ))
if [ "$nfail" != 0 ]; then
  printf '%s\n' "$fails"
  printf 'FAIL  ci-gate.sh: %s of %s fixtures fail\n' "$nfail" "$total"
  exit 1
fi
printf 'ok    ci-gate.sh: %s fixtures pass\n' "$total"

case ${1-} in
  --mutations) ;;
  '') [ "${MUTATIONS:-0}" = 1 ] || { printf 'mutations: not run (opt-in: --mutations or MUTATIONS=1)\n'; exit 0; } ;;
  *) echo "usage: ci-gate-check.sh [--mutations]" >&2; exit 2 ;;
esac

# --- mutants ------------------------------------------------------------------------
# Each line: <label>|<sed expression>. The expression must change the script (a mutant
# that matches nothing proves nothing) and the fixtures must then fail.
mut=0
survived=0
while IFS='|' read -r label expr; do
  [ -n "$label" ] || continue
  mut=$((mut + 1))
  sed "$expr" "$gate" > "$work/mutant.sh"
  if cmp -s "$gate" "$work/mutant.sh"; then
    printf 'FAIL  mutant "%s" does not change ci-gate.sh\n' "$label"
    survived=$((survived + 1))
    continue
  fi
  if run_all "$work/mutant.sh" > /dev/null 2>&1; then
    printf 'FAIL  mutant "%s" survives: no fixture notices it\n' "$label"
    survived=$((survived + 1))
  fi
done <<'MUTANTS'
head comparison dropped|s/\[ "\$h" = "\$head" \]/true/
FINDINGS no longer withdraws CLEARs|s/^           clears=\x27\x27$/           :/
pr comparison dropped|s/\[ "\$n" = "\$pr" \] || continue/true/
patch-id comparison dropped|s/\[ "\$spid" = "\$pid" \]/true/
association OWNER dropped|s/OWNER|MEMBER|COLLABORATOR/MEMBER|COLLABORATOR/
association NONE accepted|s/(OWNER|MEMBER|COLLABORATOR)/(OWNER|MEMBER|COLLABORATOR|NONE)/
refusal exits 0|s/return 1 ;;/return 0 ;;/
FINDINGS withdraws nothing|s/^           state=findings$/           state=none/
CLEAR of another head does not supersede|s/else state=other; fi$/else state=clear; fi/
empty patch-id accepted|s/\[ -n "\$out" \] ||/true ||/
Carried-over target dropped|s/if \[ "\$to" = "\$head" \]/if true/
Carried-over source unchecked|s/\*" \$from:\$p "\*)/*)/
head-moved check dropped|s/\[ "\$cur" = "\$head" \]/true/
failed heavy job reported as passed|s/failure)   verdict=failed/failure)   verdict=passed/
MUTANTS
if [ "$survived" != 0 ]; then
  printf 'FAIL  ci-gate.sh: %s of %s mutants not caught\n' "$survived" "$mut"
  exit 1
fi
printf 'ok    ci-gate.sh: %s of %s mutants caught\n' "$mut" "$mut"
