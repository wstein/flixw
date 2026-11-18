#!/bin/sh
# The gate of the heavy CI workflow (.github/workflows/ci-heavy.yaml).
#
#   ci-gate.sh gate --head <sha> --pr <n> --patch-id <hex> [--current-head <sha>] [records]
#   ci-gate.sh result <gate-result> <heavy-result>
#
# gate: reads one record per PR comment, `<author_association> <first line of the body>`,
# oldest first, from the file or stdin. It exits 0 only when the newest relevant review
# comment of a repository member is `Review: CLEAR` (or a `Carried-over:` that lands on this
# head after a CLEAR it names) for exactly this head and PR, and its patch-id is the one the
# caller computed from the PR diff. The line has the fixed shape of the review protocol and
# is matched whole: markdown, CRLF, other hex, other keys or text after the line do not match
# and therefore authorize nothing. A later FINDINGS for the head withdraws a CLEAR. With
# --current-head, a head that differs from --head (a push after the event) is refused.
#
# result: folds the two job results of the heavy workflow into one verdict line. Only
# `passed` exits 0; deferred, skipped, failed and cancelled each have a name and exit 1.
#
# Exit status: 0 accepted, 1 refused, 2 usage.
set -eu

usage() {
  echo "usage: ci-gate.sh gate --head <sha> --pr <n> --patch-id <hex> [--current-head <sha>] [records]" >&2
  echo "       ci-gate.sh result <gate-result> <heavy-result>" >&2
  exit 2
}

hex40() { printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{40}$'; }

cmd_gate() {
  head='' pr='' pid='' cur='' file=''
  while [ $# -gt 0 ]; do
    case $1 in
      --head)         [ $# -ge 2 ] || usage; head=$2; shift 2 ;;
      --pr)           [ $# -ge 2 ] || usage; pr=$2; shift 2 ;;
      --patch-id)     [ $# -ge 2 ] || usage; pid=$2; shift 2 ;;
      --current-head) [ $# -ge 2 ] || usage; cur=$2; shift 2 ;;
      -*)             usage ;;
      *)              file=$1; shift ;;
    esac
  done
  hex40 "$head" || usage
  hex40 "$pid" || usage
  printf '%s\n' "$pr" | grep -Eq '^[1-9][0-9]*$' || usage
  if [ -n "$cur" ]; then
    hex40 "$cur" || usage
    if ! [ "$cur" = "$head" ]; then
      echo "ci-gate: refused: the head moved from $head to $cur since the event" >&2
      return 1
    fi
  fi

  parsed=$(mktemp)
  trap 'rm -f "$parsed"' 0
  if [ -n "$file" ]; then exec < "$file"; fi
  sed -nE \
    -e 's/^(OWNER|MEMBER|COLLABORATOR) Review: (CLEAR|FINDINGS) head=([0-9a-f]{40}) patch-id=([0-9a-f]{40}) pr=([0-9]+)$/R \2 \3 \4 \5/p' \
    -e 's/^(OWNER|MEMBER|COLLABORATOR) Carried-over: from=([0-9a-f]{40}) to=([0-9a-f]{40}) patch-id=([0-9a-f]{40}) pr=([0-9]+)$/C \2 \3 \4 \5/p' \
    > "$parsed"

  state=none spid='' clears=''
  while read -r kind a b c d; do
    case $kind in
      R) verdict=$a h=$b p=$c n=$d
         [ "$n" = "$pr" ] || continue
         if [ "$verdict" = CLEAR ]; then
           clears="$clears $h:$p"
           if [ "$h" = "$head" ]; then state=clear; spid=$p; fi
         else
           if [ "$h" = "$head" ]; then state=findings; fi
         fi ;;
      C) from=$a to=$b p=$c n=$d
         [ "$n" = "$pr" ] || continue
         [ "$to" = "$head" ] || continue
         case " $clears " in
           *" $from:$p "*) state=clear; spid=$p ;;
         esac ;;
    esac
  done < "$parsed"

  case $state in
    none)
      echo "ci-gate: refused: no CLEAR review comment for head $head on PR $pr" >&2
      return 1 ;;
    findings)
      echo "ci-gate: refused: the newest review for head $head on PR $pr is FINDINGS" >&2
      return 1 ;;
  esac
  if ! [ "$spid" = "$pid" ]; then
    echo "ci-gate: refused: the CLEAR names patch-id $spid, the PR diff has $pid" >&2
    return 1
  fi
  echo "ci-gate: accepted head=$head pr=$pr patch-id=$pid"
}

cmd_result() {
  [ $# -eq 2 ] || usage
  gate=$1 heavy=$2
  for r in "$gate" "$heavy"; do
    case $r in success|failure|skipped|cancelled) ;; *) usage ;; esac
  done
  case $gate in
    success) ;;
    failure)   echo "heavy-result: skipped (the gate refused this head)"; return 1 ;;
    cancelled) echo "heavy-result: cancelled"; return 1 ;;
    *)         echo "heavy-result: deferred (the gate did not run)"; return 1 ;;
  esac
  case $heavy in
    success)   echo "heavy-result: passed"; return 0 ;;
    failure)   verdict=failed ;;
    cancelled) verdict=cancelled ;;
    *)         verdict=skipped ;;
  esac
  echo "heavy-result: $verdict"
  return 1
}

[ $# -ge 1 ] || usage
sub=$1
shift
case $sub in
  gate)   cmd_gate "$@" ;;
  result) cmd_result "$@" ;;
  *)      usage ;;
esac
