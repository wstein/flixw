#!/bin/sh
# Runs the `unlabel` step of .github/workflows/ci.yaml against a stub `gh`. The step is
# taken out of the workflow file as written, so what is tested is what Actions executes.
#
#   sh tests/ci-unlabel-check.sh                 fixtures
#   sh tests/ci-unlabel-check.sh --mutations     fixtures, then mutants of the step (or MUTATIONS=1)
#
# The step must remove the label, cancel only not-yet-completed heavy runs of this PR for the
# head before the push, and never turn the light CI red because a run finished between the
# listing and the cancel. Needs jq (the stub applies the step's --jq expression).
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/stage0/flixw
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
command -v jq > /dev/null 2>&1 || { echo "skip  ci-unlabel-check: jq not found"; exit 0; }
work=$(mktemp -d)
trap 'rm -rf "$work"' 0
mkdir "$work/bin"

# the run block of the step, dedented
extract() {
  awk '/^  [a-z][a-z-]*:[ \t]*$/ { job = ($0 == "  unlabel:") }
       job && /^        run: \|$/ { on = 1; next }
       on && /^          / { sub(/^          /, ""); print; next }
       on && !/^[ \t]*$/ { on = 0 }' "$1"
}

cat > "$work/bin/gh" <<'STUB'
#!/bin/sh
echo "gh $*" >> "$STUB_LOG"
case "$1 $2" in
  "pr view")
    n=$(grep -c '^gh pr view' "$STUB_LOG")
    if [ "$n" -ge 2 ]; then printf '%s' "$STUB_LABELS_AFTER"; else printf '%s' "$STUB_LABELS"; fi ;;
  "api -X") [ "${STUB_DELETE_FAIL:-0}" = 1 ] && exit 1; exit 0 ;;
  "run cancel") [ "$3" = "${STUB_CANCEL_FAIL:-none}" ] && exit 1; exit 0 ;;
  api*)
    while [ $# -gt 0 ] && [ "$1" != --jq ]; do shift; done
    jq -r "$2" "$STUB_JSON" ;;
esac
STUB
chmod +x "$work/bin/gh"

# runs: 1 in progress for PR 7, 2 completed for PR 7, 3 in progress with no PR (a fork),
# 4 queued for PR 99, 5 queued for PR 7
cat > "$work/runs.json" <<'JSON'
{"workflow_runs":[
 {"id":1,"status":"in_progress","pull_requests":[{"number":7}]},
 {"id":2,"status":"completed","pull_requests":[{"number":7}]},
 {"id":3,"status":"in_progress","pull_requests":[]},
 {"id":4,"status":"queued","pull_requests":[{"number":99}]},
 {"id":5,"status":"queued","pull_requests":[{"number":7}]}]}
JSON
echo '{"workflow_runs":[]}' > "$work/none.json"

# run_step <script> <name> <want-rc> <log must contain...> -- <log must not contain...>
# environment of the stub is set by the caller
run_step() {
  script=$1 name=$2 want=$3
  shift 3
  : > "$work/log"
  rc=0
  PATH="$work/bin:$PATH" STUB_LOG="$work/log" GH_REPO=o/r PR=7 BEFORE=abc \
    sh "$script" > "$work/out" 2>&1 || rc=$?
  ok=1
  [ "$rc" = "$want" ] || ok=0
  mode=has
  for t in "$@"; do
    if [ "$t" = -- ]; then mode=not; continue; fi
    if [ "$mode" = has ]; then
      grep -qF -- "$t" "$work/log" "$work/out" || ok=0
    elif grep -qF -- "$t" "$work/log"; then
      ok=0
    fi
  done
  [ "$ok" = 1 ] && return 0
  printf 'FAIL  %s: rc=%s (want %s)\n      log: %s\n      out: %s\n' "$name" "$rc" "$want" \
    "$(tr '\n' ';' < "$work/log")" "$(tr '\n' ';' < "$work/out")"
  return 1
}

run_all() {
  s=$1 bad=0
  t() { "$@" || bad=$((bad + 1)); }
  export STUB_JSON="$work/runs.json" STUB_LABELS_AFTER=''
  STUB_LABELS='ci-full
other
' t run_step "$s" 'label removed, only live runs of this PR cancelled' 0 \
    'api -X DELETE repos/o/r/issues/7/labels/ci-full' 'gh run cancel 1' 'gh run cancel 5' \
    -- 'gh run cancel 2' 'gh run cancel 3' 'gh run cancel 4'
  STUB_LABELS='other
' t run_step "$s" 'no label, no removal, runs still cancelled' 0 'gh run cancel 1' -- 'api -X DELETE'
  STUB_LABELS='' STUB_JSON="$work/none.json" t run_step "$s" 'nothing to cancel' 0 -- 'gh run cancel'
  STUB_LABELS='ci-full
' STUB_CANCEL_FAIL=1 t run_step "$s" 'a failed cancel does not fail the step' 0 \
    'could not cancel run 1' 'gh run cancel 5' 'cancelled run 5'
  STUB_LABELS='ci-full
' STUB_DELETE_FAIL=1 STUB_LABELS_AFTER='' t run_step "$s" 'label gone meanwhile is fine' 0 'removed ci-full'
  STUB_LABELS='ci-full
' STUB_DELETE_FAIL=1 STUB_LABELS_AFTER='ci-full
' t run_step "$s" 'a label that cannot be removed fails the step' 1 'could not remove ci-full'
  return "$bad"
}

extract "$root/.github/workflows/ci.yaml" > "$work/step.sh"
[ -s "$work/step.sh" ] || { echo "FAIL  ci-unlabel-check: no run block found for job unlabel"; exit 1; }
total=6
fails=$(run_all "$work/step.sh") && nfail=0 || nfail=$?
if [ "$nfail" != 0 ]; then
  printf '%s\n' "$fails"
  printf 'FAIL  unlabel step: %s of %s fixtures fail\n' "$nfail" "$total"
  exit 1
fi
printf 'ok    unlabel step: %s fixtures pass\n' "$total"

case ${1-} in
  --mutations) ;;
  '') [ "${MUTATIONS:-0}" = 1 ] || { printf 'mutations: not run (opt-in: --mutations or MUTATIONS=1)\n'; exit 0; } ;;
  *) echo "usage: ci-unlabel-check.sh [--mutations]" >&2; exit 2 ;;
esac

mut=0 survived=0
while IFS='~' read -r label expr; do
  [ -n "$label" ] || continue
  mut=$((mut + 1))
  sed "$expr" "$work/step.sh" > "$work/mutant.sh"
  if cmp -s "$work/step.sh" "$work/mutant.sh"; then
    printf 'FAIL  mutant "%s" does not change the step\n' "$label"; survived=$((survived + 1)); continue
  fi
  if run_all "$work/mutant.sh" > /dev/null 2>&1; then
    printf 'FAIL  mutant "%s" survives\n' "$label"; survived=$((survived + 1))
  fi
done <<'MUTANTS'
cancel failure ends the loop with its status~s/; else echo "could not cancel run \$id (finished meanwhile?)"; fi/; fi; false/
cancel of every listed run, whatever its PR~s/select(any(.pull_requests\[\]; .number == \$PR))/select(true)/
completed runs cancelled too~s/select(.status != \\"completed\\")/select(true)/
no label removal~s/gh api -X DELETE/true/
label that stays is not reported~s/exit 1/true/
MUTANTS
if [ "$survived" != 0 ]; then
  printf 'FAIL  unlabel step: %s of %s mutants not caught\n' "$survived" "$mut"
  exit 1
fi
printf 'ok    unlabel step: %s of %s mutants caught\n' "$mut" "$mut"
