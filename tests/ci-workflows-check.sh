#!/bin/sh
# Structure checks for the light/heavy CI split, and an opt-in mutation check of them.
#
#   sh tests/ci-workflows-check.sh               check .github/workflows
#   sh tests/ci-workflows-check.sh --mutations   also run the mutants (or MUTATIONS=1)
#
# There is no YAML parser here. Each file is read line by line after the same normalisation
# tests/lint.sh uses for ci.yaml (comment cut, quotes and all whitespace removed), and a job
# is the run of lines from its `  <id>:` key to the next one. What is pinned is whole lines,
# the whole block of every job that writes or decides (unlabel, gate, heavy, result), and the
# list of control keys of every job in the three files, so that a changed condition, a dropped argument or a renamed job fails
# here rather than in a heavy run nobody was meant to start:
#   ci.yaml           lint, schema, unlabel, heavy, windows; `heavy` is skipped on a pull request
#   ci-heavy-jobs.yaml  the suite jobs, on workflow_call only, each checkout at `inputs.ref`
#   ci-heavy.yaml     on `labeled` only, the gate job, its call of tests/ci-gate.sh, `heavy` after
#                     the gate, the verdict job; every action pinned to a commit
#   release.yaml      reads the Windows job names, and cuts the `heavy / ` prefix of called jobs
# shellcheck disable=SC2016,SC1003  # the pinned lines are YAML text, not shell to expand
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/stage0/flixw
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)

# Only quotes and whitespace go. A trailing ` #` is NOT cut: inside a `run:` block it is shell
# text (`#$PR` in an echo hides a command behind it), and comment-only lines are dropped
# whole by the callers.
norm() { sed 's/["'"'"']//g; s/[[:space:]]//g'; }
# blk <file> <job>: the job's normalised lines, comments and blank lines dropped
blk() {
  awk -v j="  $2:" '$0 ~ /^  [a-z][a-z-]*:[ \t]*$/ { on = ($0 == j) } on' "$1" \
    | grep -v '^[[:space:]]*#' | norm | grep -v '^$'
}
# top <file> <key>: a top-level block, same treatment
top() {
  sed -n "/^$2:/,/^[a-z]/p" "$1" | grep -v '^[[:space:]]*#' | norm | grep -v '^$' | sed '$d'
}
jobs_of() { sed -n '/^jobs:/,$p' "$1" | sed -n 's/^  \([a-z][a-z-]*\):[[:space:]]*$/\1/p' | tr '\n' ' '; }

# check <dir>: print one FAIL line per violated pin; the count is the exit status
check() {
  d=$1 n=0
  bad() { printf 'FAIL  %s\n' "$*"; n=$((n + 1)); }
  # need <file> <job> <literal line>: the job has this line, up to normalisation
  need() {
    want=$(printf '%s\n' "$3" | norm)
    blk "$d/$1" "$2" | grep -qxF -- "$want" || bad "$1 job $2 lacks the line: $3"
  }
  # exactb <file> <job>: the job is exactly the block on stdin (comments and spacing aside)
  exactb() {
    want=$(grep -v '^[[:space:]]*#' | norm | grep -v '^$')
    [ "$(blk "$d/$1" "$2")" = "$want" ] || bad "$1 job $2 is not the pinned block"
  }
  # exact <file> <job> <literal line>...: the job is exactly these lines
  exact() {
    f=$1 j=$2
    shift 2
    want=$(printf '%s\n' "$@" | norm)
    [ "$(blk "$d/$f" "$j")" = "$want" ] || bad "$f job $j is not the pinned block"
  }

  [ "$(jobs_of "$d/ci.yaml")" = "lint schema unlabel heavy windows " ] \
    || bad "ci.yaml jobs are '$(jobs_of "$d/ci.yaml")' (a heavy job added here runs on every pull request push)"
  [ "$(jobs_of "$d/ci-heavy-jobs.yaml")" = "test shipped windows-suite suite-timing " ] \
    || bad "ci-heavy-jobs.yaml jobs are '$(jobs_of "$d/ci-heavy-jobs.yaml")'"
  [ "$(jobs_of "$d/ci-heavy.yaml")" = "gate heavy result " ] \
    || bad "ci-heavy.yaml jobs are '$(jobs_of "$d/ci-heavy.yaml")'"

  # ci.yaml: the heavy call, and what a push does to the authorization
  exact ci.yaml heavy 'heavy:' 'name: heavy' "if: github.event_name != 'pull_request'" \
    'uses: ./.github/workflows/ci-heavy-jobs.yaml'
  need ci.yaml unlabel "if: github.event.action == 'synchronize' && github.event.pull_request.head.repo.full_name == github.repository"
  need ci.yaml unlabel 'pull-requests: write'
  need ci.yaml unlabel 'actions: write'
  need ci.yaml unlabel "if gh pr view \"\$PR\" --json labels --jq '.labels[].name' | grep -qx ci-full; then"
  if grep -Eq '^[[:space:]]*types:' "$d/ci.yaml"; then bad "ci.yaml must keep the default pull_request types"; fi

  # Whole blocks of the jobs that hold a write permission or decide the gate
  exactb ci.yaml unlabel <<'BLOCK'
  unlabel:
    name: revoke ci-full after a push
    if: github.event.action == 'synchronize' && github.event.pull_request.head.repo.full_name == github.repository
    runs-on: ubuntu-latest
    permissions:
      pull-requests: write
      actions: write
    steps:
      - name: remove the label, cancel older heavy runs
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          PR: ${{ github.event.pull_request.number }}
          HEAD: ${{ github.event.pull_request.head.sha }}
        run: |
          set -eu
          if gh pr view "$PR" --json labels --jq '.labels[].name' | grep -qx ci-full; then
            if gh api -X DELETE "repos/$GH_REPO/issues/$PR/labels/ci-full" --silent; then
              echo "removed ci-full from #$PR"
            elif now=$(gh pr view "$PR" --json labels --jq '.labels[].name'); then
              if printf '%s\n' "$now" | grep -qx ci-full; then
                echo "::warning::could not remove ci-full from #$PR; the gate still refuses a head without its own CLEAR"
              else
                echo "ci-full on #$PR was removed meanwhile"
              fi
            else
              echo "::warning::could not remove ci-full from #$PR and could not re-read its labels; the gate still refuses a head without its own CLEAR"
            fi
          fi
          if ids=$(gh api "repos/$GH_REPO/actions/workflows/ci-heavy.yaml/runs?event=pull_request&per_page=100" \
            --jq ".workflow_runs[] | select(.status != \"completed\" and .head_sha != \"$HEAD\") | select(any(.pull_requests[]; .number == $PR)) | .id"); then
            for id in $ids; do
              if gh run cancel "$id"; then echo "cancelled run $id"; else echo "could not cancel run $id (finished meanwhile?)"; fi
            done
          else
            echo "::warning::could not list the heavy runs of #$PR; older runs are not cancelled"
          fi
BLOCK
  exactb ci-heavy.yaml gate <<'BLOCK'
  gate:
    name: gate (CLEAR for this head)
    if: github.event.label.name == 'ci-full'
    runs-on: ubuntu-latest
    permissions:
      contents: read
      issues: read
      pull-requests: read
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          ref: ${{ github.event.repository.default_branch }}
          sparse-checkout: tests/ci-gate.sh
          sparse-checkout-cone-mode: false
          path: trusted
          persist-credentials: false
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          ref: ${{ github.event.pull_request.head.sha }}
          fetch-depth: 0
          path: pr
      - name: the gate
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          PR: ${{ github.event.pull_request.number }}
          HEAD: ${{ github.event.pull_request.head.sha }}
          DEFAULT: ${{ github.event.repository.default_branch }}
        run: |
          set -eu
          current=$(gh api "repos/$GH_REPO/pulls/$PR" --jq .head.sha)
          git -C pr fetch --no-tags origin "+refs/heads/$DEFAULT:refs/remotes/origin/$DEFAULT"
          pid=$(git -C pr diff "origin/$DEFAULT...$HEAD" | sh trusted/tests/ci-gate.sh patch-id)
          gh api --paginate "repos/$GH_REPO/issues/$PR/comments" \
            --jq '.[] | "\(.author_association) \(.body | split("\n")[0])"' > records
          echo "tested head $HEAD, current head $current, patch-id $pid"
          sh trusted/tests/ci-gate.sh gate --head "$HEAD" --pr "$PR" --patch-id "$pid" \
            --current-head "$current" records
BLOCK
  exactb ci-heavy.yaml result <<'BLOCK'
  result:
    name: heavy-result
    if: ${{ !cancelled() && github.event.label.name == 'ci-full' }}
    needs: [gate, heavy]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          ref: ${{ github.event.repository.default_branch }}
          sparse-checkout: tests/ci-gate.sh
          sparse-checkout-cone-mode: false
          persist-credentials: false
      - name: verdict
        env:
          GATE: ${{ needs.gate.result }}
          HEAVY: ${{ needs.heavy.result }}
        run: |
          verdict=$(sh tests/ci-gate.sh result "$GATE" "$HEAVY") && rc=0 || rc=$?
          echo "$verdict"
          echo "$verdict (head ${{ github.event.pull_request.head.sha }})" >> "$GITHUB_STEP_SUMMARY"
          exit "$rc"
BLOCK

  # Every control key of every job: conditions, needs, permissions, actions, runners and
  # continue-on-error, as `job|line`. A new or changed one fails here, wherever it sits.
  ctl_inv() { awk '/^  [a-z][a-z-]*:[ \t]*$/ {job=$1} /^[ \t]*(- )?(if|continue-on-error|needs|uses|runs-on|timeout-minutes|permissions|contents|actions|issues|pull-requests|id-token|packages|fail-fast):/ {print job "|" $0}' "$1" | norm; }
  [ "$(ctl_inv "$d/ci.yaml")" = "$(cat <<'INV'
push:|permissions:
push:|contents:read
lint:|runs-on:ubuntu-latest
lint:|-uses:actions/checkout@v7
lint:|-uses:actions/setup-java@v6
schema:|runs-on:ubuntu-latest
schema:|-uses:actions/checkout@v7
unlabel:|if:github.event.action==synchronize&&github.event.pull_request.head.repo.full_name==github.repository
unlabel:|runs-on:ubuntu-latest
unlabel:|permissions:
unlabel:|pull-requests:write
unlabel:|actions:write
heavy:|if:github.event_name!=pull_request
heavy:|uses:./.github/workflows/ci-heavy-jobs.yaml
windows:|runs-on:windows-latest
windows:|-uses:actions/checkout@v7
windows:|-uses:actions/setup-java@v6
INV
)" ] || bad "ci.yaml: a control key (if, needs, permissions, uses, runs-on, continue-on-error) differs from the pinned list"
  [ "$(ctl_inv "$d/ci-heavy-jobs.yaml")" = "$(cat <<'INV'
|permissions:
|contents:read
test:|runs-on:${{matrix.os}}
test:|fail-fast:false
test:|-uses:actions/checkout@v7
test:|-uses:actions/setup-java@v6
test:|-uses:actions/cache@v6
test:|-uses:actions/upload-artifact@v7
test:|if:always()
shipped:|runs-on:ubuntu-latest
shipped:|-uses:actions/checkout@v7
shipped:|-uses:actions/setup-java@v6
shipped:|-uses:actions/cache@v6
shipped:|-uses:actions/upload-artifact@v7
shipped:|if:always()
windows-suite:|runs-on:windows-latest
windows-suite:|-uses:actions/checkout@v7
windows-suite:|-uses:actions/setup-java@v6
windows-suite:|-uses:actions/upload-artifact@v7
windows-suite:|if:always()
suite-timing:|if:always()
suite-timing:|needs:[test,shipped,windows-suite]
suite-timing:|runs-on:ubuntu-latest
suite-timing:|-uses:actions/checkout@v7
suite-timing:|-uses:actions/download-artifact@v8
suite-timing:|-uses:actions/upload-artifact@v7
suite-timing:|if:always()
INV
)" ] || bad "ci-heavy-jobs.yaml: a control key (if, needs, permissions, uses, runs-on, continue-on-error) differs from the pinned list"
  [ "$(ctl_inv "$d/ci-heavy.yaml")" = "$(cat <<'INV'
|permissions:
|contents:read
gate:|if:github.event.label.name==ci-full
gate:|runs-on:ubuntu-latest
gate:|permissions:
gate:|contents:read
gate:|issues:read
gate:|pull-requests:read
gate:|-uses:actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1#v7.0.1
gate:|-uses:actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1#v7.0.1
heavy:|needs:gate
heavy:|uses:./.github/workflows/ci-heavy-jobs.yaml
result:|if:${{!cancelled()&&github.event.label.name==ci-full}}
result:|needs:[gate,heavy]
result:|runs-on:ubuntu-latest
result:|-uses:actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1#v7.0.1
INV
)" ] || bad "ci-heavy.yaml: a control key (if, needs, permissions, uses, runs-on, continue-on-error) differs from the pinned list"

  # ci-heavy-jobs.yaml: callable only, at the commit it is given, one test version
  if ! top "$d/ci-heavy-jobs.yaml" on | grep -qx 'workflow_call:' \
     || top "$d/ci-heavy-jobs.yaml" on | grep -Eq '^(push|pull_request|pull_request_target|workflow_dispatch|schedule):'; then
    bad "ci-heavy-jobs.yaml must start on workflow_call only"
  fi
  if [ "$(grep -c 'uses: actions/checkout@' "$d/ci-heavy-jobs.yaml")" != 4 ] \
     || [ "$(grep -cF 'ref: ${{ inputs.ref }}' "$d/ci-heavy-jobs.yaml")" != 4 ]; then
    bad "ci-heavy-jobs.yaml: every checkout (4) must take ref: inputs.ref (and the tested-commit step reads it)"
  fi
  v1=$(sed -n 's/^  FLIXW_TEST_VERSION: *"\(.*\)"$/\1/p' "$d/ci.yaml")
  v2=$(sed -n 's/^  FLIXW_TEST_VERSION: *"\(.*\)"$/\1/p' "$d/ci-heavy-jobs.yaml")
  { [ -n "$v1" ] && [ "$v1" = "$v2" ]; } || bad "FLIXW_TEST_VERSION is '$v1' in ci.yaml and '$v2' in ci-heavy-jobs.yaml"

  # ci-heavy.yaml: trigger, concurrency, gate, heavy, verdict
  [ "$(top "$d/ci-heavy.yaml" on | tr '\n' ' ')" = "on: pull_request: types:[labeled] " ] \
    || bad "ci-heavy.yaml must start on pull_request [labeled] and nothing else"
  if grep -Eq '^[[:space:]]*(pull_request_target|issue_comment|workflow_run|workflow_dispatch|push|schedule):' "$d/ci-heavy.yaml"; then
    bad "ci-heavy.yaml must not use pull_request_target, issue_comment, workflow_run, push, dispatch or schedule"
  fi
  [ "$(top "$d/ci-heavy.yaml" concurrency)" = "$(printf '%s\n' \
    "concurrency:" \
    "group: ci-heavy-\${{ github.event.pull_request.number }}\${{ github.event.label.name != 'ci-full' && format('-other-{0}', github.run_id) || '' }}" \
    "cancel-in-progress: \${{ github.event.label.name == 'ci-full' }}" | norm)" ] \
    || bad "ci-heavy.yaml concurrency is not the pinned group (another label must not cancel the matrix)"
  for l in "if: github.event.label.name == 'ci-full'" \
           'permissions:' 'contents: read' 'issues: read' 'pull-requests: read' \
           'ref: ${{ github.event.repository.default_branch }}' 'path: trusted' \
           'ref: ${{ github.event.pull_request.head.sha }}' 'path: pr' \
           'DEFAULT: ${{ github.event.repository.default_branch }}' \
           'current=$(gh api "repos/$GH_REPO/pulls/$PR" --jq .head.sha)' \
           'git -C pr fetch --no-tags origin "+refs/heads/$DEFAULT:refs/remotes/origin/$DEFAULT"' \
           'pid=$(git -C pr diff "origin/$DEFAULT...$HEAD" | sh trusted/tests/ci-gate.sh patch-id)' \
           'gh api --paginate "repos/$GH_REPO/issues/$PR/comments" \' \
           "--jq '.[] | \"\\(.author_association) \\(.body | split(\"\\n\")[0])\"' > records" \
           'sh trusted/tests/ci-gate.sh gate --head "$HEAD" --pr "$PR" --patch-id "$pid" \' \
           '--current-head "$current" records'; do
    need ci-heavy.yaml gate "$l"
  done
  if blk "$d/ci-heavy.yaml" gate | grep -q 'write'; then bad "ci-heavy.yaml's gate job must not hold a write permission"; fi
  exact ci-heavy.yaml heavy 'heavy:' 'name: heavy' 'needs: gate' \
    'uses: ./.github/workflows/ci-heavy-jobs.yaml' 'with:' 'ref: ${{ github.event.pull_request.head.sha }}'
  need ci-heavy.yaml result "if: \${{ !cancelled() && github.event.label.name == 'ci-full' }}"
  need ci-heavy.yaml result 'needs: [gate, heavy]'
  need ci-heavy.yaml result 'GATE: ${{ needs.gate.result }}'
  need ci-heavy.yaml result 'HEAVY: ${{ needs.heavy.result }}'
  need ci-heavy.yaml result 'verdict=$(sh tests/ci-gate.sh result "$GATE" "$HEAVY") && rc=0 || rc=$?'
  need ci-heavy.yaml result 'exit "$rc"'
  need ci-heavy.yaml result 'name: heavy-result'
  unpinned=$(sed -n 's/^[[:space:]-]*uses:[[:space:]]*\([^[:space:]]*\).*/\1/p' "$d/ci-heavy.yaml" \
             | grep -Ev '^\./|@[0-9a-f]{40}$' || true)
  [ -z "$unpinned" ] || bad "ci-heavy.yaml has actions that are not pinned to a commit: $unpinned"

  # release.yaml: `promote` reads these job names from the CI run on the tag's commit
  rel_jobs=$(sed -n 's/.*for job in \(.*\); do$/\1/p' "$d/release.yaml" | tr -d '"' | sed 's/ windows/\nwindows/g')
  for want in "windows suite (Git Bash)" "windows smoke"; do
    if ! printf '%s\n' "$rel_jobs" | grep -qxF "$want" \
       || ! cat "$d/ci.yaml" "$d/ci-heavy-jobs.yaml" | grep -qxF "    name: $want"; then
      bad "release.yaml promote and ci.yaml/ci-heavy-jobs.yaml do not agree on the CI job '$want'"
    fi
  done
  grep -q "sed 's|^heavy / ||'" "$d/release.yaml" || bad "release.yaml must cut the 'heavy / ' prefix Actions puts on called jobs"
  return "$n"
}

dir=$root/.github/workflows
out=$(check "$dir") && rc=0 || rc=$?
if [ "$rc" != 0 ]; then
  printf '%s\n' "$out"
  printf 'FAIL  ci workflows: %s pins violated\n' "$rc"
  exit 1
fi
printf 'ok    ci workflows: the light/heavy pins hold\n'

case ${1-} in
  --mutations) ;;
  '') [ "${MUTATIONS:-0}" = 1 ] || { printf 'mutations: not run (opt-in: --mutations or MUTATIONS=1)\n'; exit 0; } ;;
  *) echo "usage: ci-workflows-check.sh [--mutations]" >&2; exit 2 ;;
esac

work=$(mktemp -d)
trap 'rm -rf "$work"' 0
mut=0 survived=0
# label~file~sed expression~text the failure must contain
while IFS='~' read -r label file expr expect; do
  [ -n "$label" ] || continue
  mut=$((mut + 1))
  rm -rf "$work/wf"; cp -R "$dir" "$work/wf"
  sed "$expr" "$dir/$file" > "$work/wf/$file"
  if cmp -s "$dir/$file" "$work/wf/$file"; then
    printf 'FAIL  mutant "%s" does not change %s\n' "$label" "$file"; survived=$((survived + 1)); continue
  fi
  res=$(check "$work/wf" 2>&1) || true
  case $res in
    *"$expect"*) ;;
    *) printf 'FAIL  mutant "%s" not caught for its reason (%s); got: %s\n' "$label" "$expect" "$res"; survived=$((survived + 1)) ;;
  esac
done <<'MUTANTS'
W1 heavy runs without the gate~ci-heavy.yaml~s/^    needs: gate$/    if: always()/~job heavy is not the pinned block
W2 gate also on push~ci-heavy.yaml~s/^    if: github.event.label.name == 'ci-full'$/    if: github.event.label.name == 'ci-full' || true/~lacks the line: if: github.event.label.name
W3 current head not passed~ci-heavy.yaml~s/--current-head "\$current" records/records/~lacks the line: --current-head
W4 two-dot diff~ci-heavy.yaml~s/\.\.\.\$HEAD"/..$HEAD"/~lacks the line: pid=
W5 association constant~ci-heavy.yaml~s/\\(\.author_association)/OWNER/~lacks the line: --jq
W6 gate script from the PR checkout~ci-heavy.yaml~s#sh trusted/tests/ci-gate.sh gate#sh pr/tests/ci-gate.sh gate#~lacks the line: sh trusted
W7 caller job renamed~ci-heavy.yaml~s/^    name: heavy$/    name: matrix/~job heavy is not the pinned block
M8 patch-id against the base sha~ci-heavy.yaml~s#origin/\$DEFAULT\.\.\.#origin/\${{ github.base_ref }}...#~lacks the line: pid=
M9 trigger gains synchronize~ci-heavy.yaml~s/types: \[labeled\]/types: [labeled, synchronize]/~must start on pull_request [labeled]
M10 pull_request_target~ci-heavy.yaml~s/^  pull_request:$/  pull_request_target:/~must not use pull_request_target
M11 any label cancels the matrix~ci-heavy.yaml~s/ != 'ci-full' \&\& format/ == 'ci-full' \&\& format/~concurrency
M12 action unpinned~ci-heavy.yaml~s/actions\/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1/actions\/checkout@v7/~not pinned to a commit
M13 heavy skipped on push to main~ci.yaml~s/github.event_name != 'pull_request'/github.event_name == 'push'/~job heavy is not the pinned block
M14 unlabel drops the label removal~ci.yaml~s/labels\/ci-full" --silent/labels\/other" --silent/~job unlabel is not the pinned block
M15 ref not passed to a checkout~ci-heavy-jobs.yaml~0,/ref: \${{ inputs.ref }}/s//ref: x/~every checkout
M16 test version differs~ci-heavy-jobs.yaml~s/FLIXW_TEST_VERSION: "0/FLIXW_TEST_VERSION: "1/~FLIXW_TEST_VERSION
M17 release prefix strip dropped~release.yaml~s/ | sed 's|^heavy \/ ||'//~cut the 'heavy / ' prefix
M18 Windows suite renamed~ci-heavy-jobs.yaml~s/name: windows suite (Git Bash)/name: windows suite/~do not agree on the CI job
M19 heavy jobs gain a push trigger~ci-heavy-jobs.yaml~s/^  workflow_call:/  push:\n  workflow_call:/~workflow_call only
M20 continue-on-error on the gate step~ci-heavy.yaml~s/^      - name: the gate$/&\n        continue-on-error: true/~is not the pinned block
M21 extra write permission in unlabel~ci.yaml~s/^      actions: write$/&\n      contents: write/~is not the pinned block
M22 extra cancel of every run in unlabel~ci.yaml~s/^          set -eu$/&\n          gh run cancel 1/~is not the pinned block
M23 continue-on-error on a suite job~ci-heavy-jobs.yaml~s/^    name: the stage 0 that ships$/&\n    continue-on-error: true/~a control key
M24 id-token write on the verdict job~ci-heavy.yaml~s/^    name: heavy-result$/&\n    permissions:\n      id-token: write/~a control key
U6 listing warning becomes a failure~ci.yaml~s@older runs are not cancelled"@&; exit 1@~is not the pinned block
U8 a second label call behind the echo~ci.yaml~s@echo "removed ci-full from #\$PR"@& ; gh api -X DELETE "repos/$GH_REPO/issues/$PR/labels/other"@~is not the pinned block
U9 failure behind removed meanwhile~ci.yaml~s@was removed meanwhile"@&; exit 1@~is not the pinned block
MUTANTS
if [ "$survived" != 0 ]; then
  printf 'FAIL  ci workflows: %s of %s mutants not caught\n' "$survived" "$mut"
  exit 1
fi
printf 'ok    ci workflows: %s of %s mutants caught for their reason\n' "$mut" "$mut"
