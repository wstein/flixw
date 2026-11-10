#!/bin/sh
# Regression for tests/upstream-cli.sh: every way the check can fail to *know* something must
# end as exit 2 (UNKNOWN), never 0 (clean) and never 1 (drift). The workflow closes its drift
# issue on 0 and opens or updates it on 1, so a wrong 0 or 1 is a wrong issue action.
#
#   sh tests/upstream-cli-test.sh
#
# Offline: a fake `curl` serves a fixture tree, so nothing here reaches GitHub.
# shellcheck disable=SC2016  # the fixtures are Scala source; nothing in them is meant to expand
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only, as in the other scripts
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
script=${UPSTREAM_CLI_SCRIPT:-$root/tests/upstream-cli.sh}
t=$root/tests/.work/upstream-cli-test
rm -rf "$t"
mkdir -p "$t/bin"
real_grep=$(command -v grep)

# What upstream serves, keyed by URL without the scheme. A missing file is a 404 (curl -f: 22).
cat > "$t/bin/curl" <<'CURL'
#!/bin/sh
out=; url=
while [ $# -gt 0 ]; do
  case $1 in -o) out=$2; shift ;; -*) ;; *) url=$1 ;; esac
  shift
done
f=$FAKE_ROOT/${url#https://}
[ -f "$f" ] || exit 22
if [ -n "$out" ]; then cp "$f" "$out"; else cat "$f"; fi
CURL
# A tool that fails the way a broken machine does: grep (extractor), sed (extractor), diff.
cat > "$t/bin/grep" <<GREP
#!/bin/sh
case "\${FAKE_BREAK:-}:\$*" in grep:*-oE*) exit 2 ;; esac
exec $real_grep "\$@"
GREP
cat > "$t/bin/sed" <<'SED'
#!/bin/sh
[ "${FAKE_BREAK:-}" = sed ] && exit 1
exec /usr/bin/env -i PATH="$ORIG_PATH" sed "$@"
SED
cat > "$t/bin/diff" <<'DIFF'
#!/bin/sh
[ "${FAKE_BREAK:-}" = diff ] && exit 3
exec /usr/bin/env -i PATH="$ORIG_PATH" diff "$@"
DIFF
chmod +x "$t/bin/curl" "$t/bin/grep" "$t/bin/sed" "$t/bin/diff"
ORIG_PATH=$PATH
export ORIG_PATH

p_main=main/src/ca/uwaterloo/flix/Main.scala
p_opts=main/src/ca/uwaterloo/flix/util/Options.scala
up=$t/up/raw.githubusercontent.com/flix/flix/master

good_main() {
  cat <<'M'
cmd("init")
cmd("check")
opt[Unit]("json")
opt[Seq[Mode]]("Xmodes")
arg[Int]("port")
M
}
good_opts() { printf 'case object Fast extends Mode\ncase object Slow extends Mode\n'; }

setup() { # fresh upstream with the good sources and a fingerprint made from them
  rm -rf "$t/up" "$t/w"
  mkdir -p "$up/$(dirname "$p_main")" "$up/$(dirname "$p_opts")" "$t/up/api.github.com/repos/flix"
  printf '{"default_branch": "master"}\n' > "$t/up/api.github.com/repos/flix/flix"
  good_main > "$up/$p_main"
  good_opts > "$up/$p_opts"
  rm -f "$t/fp"
  saved=${FAKE_BREAK:-}; unset FAKE_BREAK
  run --update >/dev/null 2>&1
  [ -z "$saved" ] || { FAKE_BREAK=$saved; export FAKE_BREAK; }
}

# run <args>: the exit status and combined output of one check against the fixture.
run() {
  rc=0
  out=$(PATH=$t/bin:$PATH FAKE_ROOT=$t/up FLIX_UPSTREAM_WORK=$t/w \
        FLIX_UPSTREAM_FINGERPRINT=$t/fp sh "$script" "$@" 2>&1) || rc=$?
}

pass=0; failed=0
expect() { # <rc> <label> [substring]
  want=$1; label=$2; sub=${3:-}
  if [ "$rc" = "$want" ] && { [ -z "$sub" ] || printf '%s' "$out" | "$real_grep" -Fq -- "$sub"; }; then
    pass=$((pass + 1)); echo "ok    $label"
  else
    failed=$((failed + 1)); echo "FAIL  $label: rc=$rc want=$want${sub:+ containing '$sub'}"
    printf '%s\n' "$out" | sed 's/^/      /'
  fi
}

setup;                                              run; expect 0 "unchanged upstream is clean" "unchanged"
setup; echo 'cmd("build")' >> "$up/$p_main";        run; expect 1 "a new command is drift" "has changed"
setup; echo 'case object Mid extends Mode' >> "$up/$p_opts"; run; expect 1 "a new enum member is drift"

setup; rm "$up/$p_main";                            run; expect 2 "Main.scala missing is UNKNOWN" "cannot fetch"
setup; rm "$up/$p_opts";                            run; expect 2 "Options.scala missing is UNKNOWN" "cannot fetch"
setup; mkdir -p "$up/main/src/ca/uwaterloo/flix/cli"
       mv "$up/$p_main" "$up/main/src/ca/uwaterloo/flix/cli/Main.scala"
                                                    run; expect 2 "moved Main.scala is UNKNOWN" "cannot fetch"
setup; : > "$up/$p_main";                           run; expect 2 "empty Main.scala is UNKNOWN" "is empty"
setup; : > "$up/$p_opts";                           run; expect 2 "empty Options.scala is UNKNOWN" "is empty"
setup; echo '<html>Service Unavailable</html>' > "$up/$p_main"
                                                    run; expect 2 "a 200 error page as Main.scala is UNKNOWN" "no command extracted"
setup; printf 'opt[Unit]("json")\n' > "$up/$p_main"; run; expect 2 "Main.scala without commands is UNKNOWN" "no command extracted"
setup; printf 'cmd("init")\n' > "$up/$p_main";       run; expect 2 "Main.scala without options is UNKNOWN" "no option extracted"
setup; echo 'object X' > "$up/$p_opts";             run; expect 2 "Options.scala without enum members is UNKNOWN" "no enum member"
setup; echo 'opt[Seq[Map[String]]]("Xdeep")' >> "$up/$p_main"
                                                    run; expect 2 "a type nested three deep is UNKNOWN" "unsupported syntax"
setup; echo 'cmd(name)' >> "$up/$p_main";           run; expect 2 "a non-literal builder name is UNKNOWN" "unsupported syntax"
setup; printf 'opt[Int](\n  "multiline")\n' >> "$up/$p_main"
                                                    run; expect 2 "a builder name on the next line is UNKNOWN" "unsupported syntax"

setup; echo 'cmd ("spaced")' >> "$up/$p_main";     run; expect 2 "whitespace before the parenthesis is UNKNOWN" "unsupported syntax"
setup; echo 'opt[Unit] ("spaced")' >> "$up/$p_main"; run; expect 2 "whitespace after the type is UNKNOWN" "unsupported syntax"
setup; printf 'cmd(\n  "next")\n' >> "$up/$p_main"; run; expect 2 "a line break before the name is UNKNOWN" "unsupported syntax"
# A look-alike must not pay for an unreadable call: subcmd("x") is not cmd("x").
setup; printf 'subcmd("x")\ncmd(name)\n' >> "$up/$p_main"; run; expect 2 "subcmd(\"x\") does not make up for cmd(name)" "unsupported syntax"
setup; printf 'subcmd("x")\n' >> "$up/$p_main";       run; expect 0 "a look-alike helper is not a builder" "unchanged"
setup; printf 'case object Mid\n  extends Mode\n' >> "$up/$p_opts"; run
  expect 1 "a case object with extends on the next line is extracted" "enum Mode Mid"
setup; printf 'case object Mid\n' >> "$up/$p_opts"; run; expect 2 "a case object without extends is UNKNOWN" "unsupported syntax"
setup; printf 'case  object   Mid   extends   Mode\n' >> "$up/$p_opts"; run
  expect 1 "case object spelled with extra spaces is extracted" "enum Mode Mid"

setup; export FAKE_BREAK=grep; run; expect 2 "an extractor that fails is UNKNOWN" "grep failed"
setup; export FAKE_BREAK="sed";          run; expect 2 "an unexpected failure is UNKNOWN, not drift (exit 1)" "UNKNOWN"
setup; export FAKE_BREAK="diff"; run; expect 2 "diff failing with a code other than 1 is UNKNOWN" "diff failed"
unset FAKE_BREAK

setup; rm "$t/fp";                                  run; expect 2 "no committed fingerprint is UNKNOWN" "no committed fingerprint"
setup; printf '# nothing\n' > "$t/fp";              run; expect 2 "an empty committed fingerprint is UNKNOWN" "no entries"

# An incomplete source must not become the new baseline either.
setup; cp "$t/fp" "$t/fp.before"; : > "$up/$p_main"; run --update; expect 2 "--update on an empty source is UNKNOWN"
if cmp -s "$t/fp" "$t/fp.before"; then pass=$((pass + 1)); echo "ok    --update left the fingerprint untouched"
else failed=$((failed + 1)); echo "FAIL  --update overwrote the fingerprint from an incomplete source"; fi

echo
echo "upstream-cli-test: $pass passed, $failed failed"
[ "$failed" -eq 0 ]
