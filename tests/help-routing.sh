#!/bin/sh
# Exercise the installed CLI router, not a hand-built renderer request.
# Usage: sh tests/help-routing.sh <installed-project>
# Reuse the caller's stock compiler/cache and verified companion fixture.
set -eu
if [ "$#" -ne 1 ]; then
  echo 'usage: sh tests/help-routing.sh <installed-project>' >&2
  exit 2
fi
cd "$1"
pass=0
fail=0
check() {
  label=$1
  shift
  if "$@"; then
    pass=$((pass + 1))
    printf '  ok   router: %s\n' "$label"
  else
    fail=$((fail + 1))
    printf '  FAIL router: %s\n' "$label"
  fi
}
alias_help() {
  discovery=$(./flixw help "$1") && direct=$(./flixw "$1" --help) && [ "$discovery" = "$direct" ]
}
focused_help() {
  discovery=$(./flixw help flix doc) && direct=$(./flixw doc "$1") &&
    printf '%s\n' "$direct" | grep -q 'Usage: ./flixw doc' && [ "$discovery" = "$direct" ]
}
extended_help() {
  discovery=$(./flixw help flix "$1" --Xhelp) && direct=$(./flixw "$1" --Xhelp) &&
    printf '%s\n' "$direct" | grep -q -- "$2" && [ "$discovery" = "$direct" ]
}
jvm_help() {
  plain=$(./flixw help "$@") && with_jvm=$(FLIX_JVM_OPTS=-Xmx512m ./flixw help "$@") &&
    [ "$plain" = "$with_jvm" ]
}
for verb in check build init; do check "$verb usage alias" alias_help "$verb"; done
for flag in --help -h; do check "doc $flag is ordinary help" focused_help "$flag"; done
check 'check --Xhelp retains experimental options' extended_help check --Xno-deprecated
check 'doc --Xhelp retains empty experimental section' extended_help doc 'No experimental options for this command.'
check 'absent topic with JVM options' jvm_help
check 'absent task name with JVM options' jvm_help task
printf 'help routing: %s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
