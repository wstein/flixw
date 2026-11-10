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
# One scratch directory per run: a fixed name was shared by a parallel run of this script (or
# of tests/lint.sh, which calls it), and each one's cleanup removed the other's fixtures.
mkdir -p "$root/tests/.work"
t=$(mktemp -d "$root/tests/.work/upstream-cli-test.XXXXXX") || { echo "cannot create a scratch directory" >&2; exit 2; }
trap 'rm -rf "$t"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$t/bin"
real_grep=$(command -v grep)

# What upstream serves, keyed by URL without the scheme. A missing file is a 404 (curl -f: 22).
cat > "$t/bin/curl" <<'CURL'
#!/bin/sh
out=; url=
while [ $# -gt 0 ]; do
  case $1 in -o|-H) [ "$1" = -o ] && out=$2; shift ;; -*) ;; *) url=$1 ;; esac
  shift
done
echo "$url" >> "$FAKE_ROOT/requests.log"
[ -z "${FAKE_OFFLINE:-}" ] || exit 6
f=$FAKE_ROOT/${url#https://}
[ -d "$f" ] && f=$f/.self
[ -f "$f" ] || exit 22
# The branch moves while the sources are being fetched: after the first raw download, the
# name resolves to, and serves, the second revision.
case $url in
  */raw.githubusercontent.com/*) n=$(cat "$FAKE_ROOT/raw.count" 2>/dev/null || echo 0)
    echo $((n + 1)) > "$FAKE_ROOT/raw.count"
    if [ "$n" = 0 ] && [ -d "$FAKE_ROOT/moved" ]; then cp -R "$FAKE_ROOT/moved/." "$FAKE_ROOT/"; fi ;;
esac
if [ -n "$out" ]; then cp "$f" "$out"; else /bin/cat "$f"; fi
CURL
# A tool that fails the way a broken machine does: grep (extractor), sed (extractor), diff.
cat > "$t/bin/grep" <<GREP
#!/bin/sh
case "\${FAKE_BREAK:-}:\$*" in grep:*-oE*) exit 2 ;; esac
exec $real_grep "\$@"
GREP
# Every other tool the check relies on can be broken by name (FAKE_BREAK=<tool>); diff breaks
# with a status other than 1 on purpose, since 1 is its way of saying "different".
for tool in sed diff cat wc sort mv rm mktemp awk; do
  case $tool in diff) bad=3 ;; *) bad=1 ;; esac
  cat > "$t/bin/$tool" <<STUB
#!/bin/sh
[ "\${FAKE_BREAK:-}" = $tool ] && exit $bad
# FAKE_BREAK=<tool>:<text> breaks only the calls whose arguments contain <text>.
case "\${FAKE_BREAK:-}" in $tool:*) case "\$*" in *"\${FAKE_BREAK#*:}"*) exit $bad ;; esac ;; esac
[ $tool != mktemp ] || echo "\$*" >> "\$FAKE_ROOT/mktemp.log"
exec env PATH="\$ORIG_PATH" $tool "\$@"
STUB
  chmod +x "$t/bin/$tool"
done
chmod +x "$t/bin/curl" "$t/bin/grep"
ORIG_PATH=$PATH
export ORIG_PATH

p_main=main/src/ca/uwaterloo/flix/Main.scala
p_opts=main/src/ca/uwaterloo/flix/util/Options.scala
sha1=1111111111111111111111111111111111111111   # master when the check starts
sha2=2222222222222222222222222222222222222222   # master after it moves
sha3=3333333333333333333333333333333333333333   # the release tag v0.79.0
raw=$t/up/raw.githubusercontent.com/flix/flix
api=$t/up/api.github.com/repos/flix/flix
up=$raw/$sha1

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

put() { mkdir -p "$(dirname "$2")"; printf '%s\n' "$1" > "$2"; }
tree() { # <dir> <extra Main.scala line> <extra Options.scala line>
  mkdir -p "$1/$(dirname "$p_main")" "$1/$(dirname "$p_opts")"
  { good_main; [ -z "$2" ] || echo "$2"; } > "$1/$p_main"
  { good_opts; [ -z "$3" ] || echo "$3"; } > "$1/$p_opts"
}
setup() { # fresh upstream: master at sha1, a release at sha3, a fingerprint made from sha1
  rm -rf "$t/up" "$t/w" "$t/fp"
  put '{"default_branch": "master"}' "$api/.self"
  put $sha1 "$api/commits/master"
  put $sha3 "$api/commits/v0.79.0"
  tree "$raw/$sha1" "" ""
  tree "$raw/$sha2" 'cmd("build")' 'case object Mid extends Mode'
  tree "$raw/$sha3" "" 'case object Old extends Mode'
  tree "$raw/master" "" ""   # what a download through the branch name would see
  saved=${FAKE_BREAK:-}; unset FAKE_BREAK
  run --update >/dev/null 2>&1
  rm -rf "$t/w"   # the cache of that run is not part of the scenario
  if [ -z "${recorded:-}" ] && [ -f "$t/fp" ]; then
    # Like the committed fingerprint, which predates the record: no revision, no digests. The
    # scenarios that edit sources at sha1 would otherwise contradict the record, as they
    # should; the ones about the record use setup_recorded.
    "$real_grep" -v -e '^# Revision: ' -e '^#   [0-9a-f]\{64\}  ' "$t/fp" > "$t/fp.legacy"
    mv "$t/fp.legacy" "$t/fp"
  fi
  [ -z "$saved" ] || { FAKE_BREAK=$saved; export FAKE_BREAK; }
  rm -f "$t/up/requests.log" "$t/up/raw.count"
}
setup_recorded() { recorded=1; setup; recorded=; }
# Upstream's branch moves to sha2 as soon as the first source has been downloaded.
move_master() {
  mkdir -p "$t/up/moved/api.github.com/repos/flix/flix/commits"
  tree "$t/up/moved/raw.githubusercontent.com/flix/flix/master" 'cmd("build")' 'case object Mid extends Mode'
  printf '%s\n' "$sha2" > "$t/up/moved/api.github.com/repos/flix/flix/commits/master"
}

# run <args>: the exit status and combined output of one check against the fixture.
run() {
  rc=0
  out=$(PATH=$t/bin:$PATH FAKE_OFFLINE=${offline:-} FAKE_ROOT=$t/up FLIX_UPSTREAM_WORK=$t/w \
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

check() { # <label> <command...>: a plain assertion
  label=$1; shift
  if "$@"; then pass=$((pass + 1)); echo "ok    $label"; else failed=$((failed + 1)); echo "FAIL  $label"; fi
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

setup; printf '`cmd`("x")\n' >> "$up/$p_main";      run; expect 2 "a backticked builder name is UNKNOWN" "unsupported syntax"
# Comments are not source: a commented-out builder is not in the surface, and a comment that
# mentions one cannot make up for, or hide, a real call.
setup; printf '// cmd("gone")\n' >> "$up/$p_main";   run; expect 0 "a line-commented builder is not counted" "unchanged"
setup; printf '/* cmd("gone")\n   opt[Unit]("gone") */\n' >> "$up/$p_main"; run
  expect 0 "a block-commented builder is not counted" "unchanged"
setup; printf 'cmd("kept") // note: cmd(name)\n' >> "$up/$p_main"; run
  expect 1 "a trailing comment does not hide the call before it" "cmd - kept"
setup; printf '/* http://x */ cmd("kept")\n' >> "$up/$p_main"; run
  expect 1 "// inside a block comment does not swallow what follows" "cmd - kept"
setup; printf 'opt[Unit]("a//b")\n' >> "$up/$p_main"; run
  expect 1 "// inside a string is not a comment" "opt Unit a//b"
setup; printf "val q = '\"'; cmd(\"kept\")\n" >> "$up/$p_main"; run
  expect 1 "a quote character literal does not open a string" "cmd - kept"
setup; cat >> "$up/$p_main" <<'X'
val q = '"'; cmd("kept") // cmd(name)
X
run; expect 1 "a quote literal does not leave the trailing comment unstripped" "cmd - kept"
setup; cat >> "$up/$p_main" <<'X'
val s = "x\" // y"; cmd("kept") // cmd(name)
X
run; expect 1 "an escaped quote does not end a string" "cmd - kept"
# Strings can span lines, and a `/*` inside one is not a comment: the usage text of the real
# Main.scala has `flix check src/*.flix` on the line after an opening """.
setup; cat >> "$up/$p_main" <<'X'
val usage =
  """usage
    |  flix check src/*.flix
    |""".stripMargin
cmd("kept")
/** doc */
X
run; expect 1 "a /* inside a multi-line string does not open a comment" "cmd - kept"
setup; cat >> "$up/$p_main" <<'X'
val q = """a "quoted" // not a comment"""; cmd("kept") // cmd(name)
X
run; expect 1 "quotes and // inside a triple-quoted string are text" "cmd - kept"
setup; cat >> "$up/$p_main" <<'X'
val q = """a""""; cmd("kept") // cmd(name)
X
run; expect 1 "a run of quotes closes a triple-quoted string once" "cmd - kept"
setup; printf 'cmd("kept")\n/* never closed\ncmd("hidden")\n' >> "$up/$p_main"; run
  expect 2 "a block comment open at the end of the file is UNKNOWN" "inside a comment or string"
setup; printf 'cmd("kept")\nval s = """never closed\ncmd("hidden")\n' >> "$up/$p_main"; run
  expect 2 "a triple-quoted string open at the end of the file is UNKNOWN" "inside a comment or string"
# Scala nests block comments.
setup; cat >> "$up/$p_main" <<'X'
/* a /* b */ cmd("zz") */ cmd("kept")
X
run; expect 1 "after a nested block comment the call that follows is read" "cmd - kept"
no_zz() { ! printf '%s' "$out" | "$real_grep" -q 'cmd - zz'; }
check "a builder inside a nested block comment is not counted" no_zz
setup; export FAKE_BREAK="awk"; run; expect 2 "a failing comment scan is UNKNOWN" "cannot scan"
unset FAKE_BREAK
# A string literal inside `${...}` of an interpolation is not modelled; the scan stops rather
# than let a `//` or `/*` in it discard the rest of the line.
setup; cat >> "$up/$p_main" <<'X'
val a = s"${"//"}"; cmd("zzB")
X
run; expect 2 "a string inside \${...} that holds // is UNKNOWN, not a discarded line" "interpolation"
setup; cat >> "$up/$p_main" <<'X'
val a = s"${"/*"}"; cmd("zzB")
X
run; expect 2 "a string inside \${...} that holds /* is UNKNOWN" "interpolation"
setup; cat >> "$up/$p_main" <<'X'
val a = s"""${"x"}"""; cmd("zzB")
X
run; expect 2 "a string inside \${...} of a triple-quoted string is UNKNOWN" "interpolation"
setup; cat >> "$up/$p_main" <<'X'
val a = s"${ {1} + g("x") }"; cmd("zzB")
X
run; expect 2 "a string after a nested brace inside \${...} is UNKNOWN" "interpolation"
setup; cat >> "$up/$p_main" <<'X'
val a = s"x ${name} y ${f(b)} z"; cmd("kept") // cmd(name)
X
run; expect 1 "an interpolation without a string inside is read" "cmd - kept"
# Messages name the file as upstream's repository does, not the scratch copy.
no_workpath() { ! printf '%s' "$out" | "$real_grep" -qF "$t/w"; }
setup; printf '/* never closed\n' >> "$up/$p_main"; run; expect 2 "the scan's message names the upstream path" "$p_main ends inside"
check "...and not the scratch copy" no_workpath
setup; printf '// enum Mode { case A }\n' >> "$up/$p_opts"; run; expect 0 "a commented enum is not counted" "unchanged"
setup; printf 'enum Level { case Hi, Lo }\n' >> "$up/$p_opts"; run
  expect 2 "a Scala 3 enum is UNKNOWN, not ignored" "unsupported syntax"
setup; export FAKE_BREAK=grep; run; expect 2 "an extractor that fails is UNKNOWN" "grep failed"
setup; export FAKE_BREAK="sed";          run; expect 2 "an unexpected failure is UNKNOWN, not drift (exit 1)" "UNKNOWN"
setup; export FAKE_BREAK="diff"; run; expect 2 "diff failing with a code other than 1 is UNKNOWN" "diff failed"
unset FAKE_BREAK

setup; export FAKE_BREAK="cat"; run; expect 0 "a failing cat does not make a clean check drift" "unchanged"
setup; export FAKE_BREAK="wc";  run; expect 2 "a failing wc is UNKNOWN, not an empty count" "UNKNOWN"
setup; export FAKE_BREAK="sort"; run; expect 2 "a failing sort is UNKNOWN" "UNKNOWN"
unset FAKE_BREAK

# The verdict file belongs to one run: made by mktemp, gone afterwards, never ignored on removal.
# A find that fails has not shown that nothing is left.
leftovers() { l=$(find "$1" -name 'verdict*' 2>/dev/null) || return 1; [ -z "$l" ]; }
setup; run; check "no verdict file is left behind" leftovers "$t/w"
find_fails() { ! leftovers "$t/no-such-dir"; }
check "a find that fails is not 'nothing left'" find_fails
setup; run; check "the verdict file is made with mktemp" test -s "$t/up/mktemp.log"
setup; export FAKE_BREAK="mktemp"; run; expect 2 "a verdict file that cannot be made is UNKNOWN" "verdict file"
setup; export FAKE_BREAK="rm"; run; expect 2 "a verdict file that cannot be removed is UNKNOWN" "cannot remove"
unset FAKE_BREAK
# The exit code counts only with the verdict the check recorded for it. Each variant of the
# script changes one verdict, so that code and verdict disagree or the verdict is missing.
vrun() { # <sed-expr> <args...>
  vexpr=$1; shift
  sed "$vexpr" "$script" > "$t/variant.sh"
  vsave=$script; script=$t/variant.sh; run "$@"; script=$vsave
}
setup; vrun 's/record clean/true/'
  expect 2 "exit 0 without a verdict is UNKNOWN" "that is not a result"
setup; vrun 's/record clean/record drift/'
  expect 2 "exit 0 with the verdict drift is UNKNOWN" "that is not a result"
setup; vrun 's/record updated/true/' --update
  expect 2 "exit 0 of an update without a verdict is UNKNOWN" "that is not a result"
setup; echo 'cmd("build")' >> "$up/$p_main"; vrun 's/record drift/true/'
  expect 2 "exit 1 without a verdict is UNKNOWN" "that is not a result"
setup; echo 'cmd("build")' >> "$up/$p_main"; vrun 's/record drift/record clean/'
  expect 2 "exit 1 with the verdict clean is UNKNOWN" "that is not a result"
setup; rm "$up/$p_main"; vrun 's/record unknown/true/'
  expect 2 "exit 2 without a verdict is reported as not a result" "that is not a result"
setup; rm "$up/$p_main"; vrun 's/record unknown/record clean/'
  expect 2 "exit 2 with the verdict clean is reported as not a result" "that is not a result"
setup; rm "$up/$p_main"; run
  expect 2 "exit 2 with its own verdict is a plain UNKNOWN" "cannot fetch"
check "...and says nothing about a missing verdict" test -z "$(printf '%s' "$out" | "$real_grep" 'not a result' || true)"
# A variable another process left in the environment is not a way past the verdict check.
setup; export FAKE_BREAK="sed" FLIX_UPSTREAM_INNER=1; run
  expect 2 "FLIX_UPSTREAM_INNER in the environment does not skip the verdict check" "UNKNOWN"
unset FAKE_BREAK FLIX_UPSTREAM_INNER

# An update that fails part-way leaves the committed baseline as it was; without `set -e`
# the failed rename would still be reported as "updated".
fp_unchanged() { cmp -s "$t/fp" "$t/fp.before"; }
# (the first run fills the cache, so that mv fails at the rename of the baseline and nowhere earlier)
setup; echo 'cmd("build")' >> "$up/$p_main"; cp "$t/fp" "$t/fp.before"; run
export FAKE_BREAK="mv"; run --update
  expect 2 "--update whose rename fails is UNKNOWN, not updated" "UNKNOWN"
  check "--update whose rename fails leaves the baseline alone" fp_unchanged
setup; echo 'cmd("build")' >> "$up/$p_main"; cp "$t/fp" "$t/fp.before"; run
export FAKE_BREAK="cat"; run --update
  expect 2 "--update that fails while writing is UNKNOWN" "UNKNOWN"
  check "--update that fails while writing leaves the baseline alone" fp_unchanged
unset FAKE_BREAK

setup; rm "$t/fp";                                  run; expect 2 "no committed fingerprint is UNKNOWN" "no committed fingerprint"
setup; printf '# nothing\n' > "$t/fp";              run; expect 2 "an empty committed fingerprint is UNKNOWN" "no entries"

# An incomplete source must not become the new baseline either.
setup; cp "$t/fp" "$t/fp.before"; : > "$up/$p_main"; run --update; expect 2 "--update on an empty source is UNKNOWN"
if cmp -s "$t/fp" "$t/fp.before"; then pass=$((pass + 1)); echo "ok    --update left the fingerprint untouched"
else failed=$((failed + 1)); echo "FAIL  --update overwrote the fingerprint from an incomplete source"; fi

# --- pinned revision ---------------------------------------------------------
requests_not_pinned() { # raw requests whose path is not the revision $1
  "$real_grep" 'raw.githubusercontent.com' "$t/up/requests.log" | "$real_grep" -v "/flix/flix/$1/" || true
}
no_unpinned() { [ -s "$t/up/requests.log" ] && [ -z "$(requests_not_pinned "$sha1")" ]; }
only_sha1_cached() { [ "$(ls "$t/w/cache")" = "$sha1" ]; }

setup_recorded; move_master; run
  expect 0 "branch movement between downloads: every source stays at the recorded revision" "$sha1"
  check "no source request names a branch" no_unpinned
  check "only the recorded revision is cached" only_sha1_cached
  check "the moved revision was never requested" test -z "$("$real_grep" "$sha2" "$t/up/requests.log" || true)"

digests_match() { (cd "$t/w/cache/$sha1" && $sum -c SHA256SUMS >/dev/null 2>&1) \
  && [ "$("$real_grep" -c . "$t/w/cache/$sha1/SHA256SUMS")" = 2 ] \
  && "$real_grep" -q "$($sum "$t/w/cache/$sha1/Main.scala" | cut -d' ' -f1)" "$t/fp"; }
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
setup_recorded; run
  check "source digests are recorded beside the sources and in the fingerprint" digests_match
  check "the fingerprint names the revision" "$real_grep" -q "Revision: $sha1" "$t/fp"

setup_recorded; run; rm -f "$t/up/requests.log"; offline=1 run --to $sha1
  expect 0 "a pinned revision is reused offline from the cache" "unchanged"
  check "offline reuse makes no request" test ! -s "$t/up/requests.log"
setup_recorded; run; printf 'tampered\n' > "$t/w/cache/$sha1/Main.scala"; offline=1 run --to $sha1
  expect 2 "a cached source that fails its digest is not used offline" "cannot fetch"
# A cache entry whose SHA256SUMS names only one file leaves the other unchecked.
setup_recorded; run; printf 'tampered\n' > "$t/w/cache/$sha1/Options.scala"
  (cd "$t/w/cache/$sha1" && $sum Main.scala > SHA256SUMS); offline=1 run --to $sha1
  expect 2 "a SHA256SUMS that names one file only is not trusted" "cannot fetch"
setup_recorded; run; printf 'tampered\n' > "$t/w/cache/$sha1/Options.scala"
  (cd "$t/w/cache/$sha1" && $sum Main.scala Main.scala > SHA256SUMS); offline=1 run --to $sha1
  expect 2 "a SHA256SUMS that names Main.scala twice is not trusted" "cannot fetch"
setup_recorded; run; printf 'tampered\n' > "$t/w/cache/$sha1/Main.scala"
  (cd "$t/w/cache/$sha1" && $sum Options.scala Options.scala > SHA256SUMS); offline=1 run --to $sha1
  expect 2 "a SHA256SUMS that names Options.scala twice is not trusted" "cannot fetch"
# The fingerprint's Revision line is read with a tool that can fail; a failed read must not
# switch the digest comparison off.
setup_recorded; run; printf 'case object Evil extends Mode\n' >> "$t/w/cache/$sha1/Options.scala"
  (cd "$t/w/cache/$sha1" && $sum Main.scala Options.scala > SHA256SUMS)
  export FAKE_BREAK="sed:Revision"; offline=1 run --to $sha1; unset FAKE_BREAK
  expect 2 "a failed read of the fingerprint's revision is UNKNOWN, not drift" "UNKNOWN"
# A revision without digests is a record with nothing to compare against, not a pass.
setup_recorded; run; "$real_grep" -v '^#   [0-9a-f]\{64\}  ' "$t/fp" > "$t/fp.nodigests"; mv "$t/fp.nodigests" "$t/fp"
  offline=1 run --to $sha1
  expect 2 "a fingerprint with a revision and no digests is UNKNOWN" "recorded"
# Sources and digests altered together agree with each other; the fingerprint's record does not.
setup_recorded; run; printf 'case object Evil extends Mode\n' >> "$t/w/cache/$sha1/Options.scala"
  (cd "$t/w/cache/$sha1" && $sum Main.scala Options.scala > SHA256SUMS); offline=1 run --to $sha1
  expect 2 "cached sources that differ from the digests the fingerprint recorded are UNKNOWN" "recorded"
setup_recorded; run; printf 'tampered\n' > "$t/w/cache/$sha1/Main.scala"; run --to $sha1
  expect 0 "a cached source that fails its digest is fetched again online" "unchanged"
setup_recorded; offline=1 run --to $sha1
  expect 2 "a pinned revision that was never cached is UNKNOWN offline" "cannot fetch"
unset offline

setup_recorded;           run --from v0.79.0
  expect 1 "release baseline against master head is drift" "v0.79.0"
  check "the compared revisions are both named" sh -c 'printf "%s" "$1" | grep -q "$2" && printf "%s" "$1" | grep -q "$3"' _ "$out" "$sha1" "$sha3"
setup_recorded; rm "$t/fp"; run --from v0.79.0 --to master
  expect 1 "explicit from/to refs do not read the committed fingerprint" "Old"
setup_recorded;           run --from $sha1 --to master
  expect 0 "explicit from/to naming the same revision is clean" "unchanged"
setup_recorded;           run --to v0.79.0
  expect 2 "a release is not compared with the master baseline" "--from"
setup_recorded;           run --to $sha1
  expect 0 "a master revision by SHA is compared with the master baseline" "unchanged"
# A short hex string is a name until the API says otherwise; it is not a revision to fetch by.
setup_recorded; put $sha3 "$api/commits/abc1234"; run --from abc1234
  expect 1 "a short hex ref is resolved through the API" "$sha3"
# The ref goes into a URL path: '#', '%', '?' or a space would make the API answer for another ref.
for badref in 'a#b' 'a%2Fb' 'a?b' 'a b' 'a..b' '-x' 'a/' 'a//b'; do
  setup; run --from "$badref"
  expect 2 "a ref spelled '$badref' is refused" "not a valid ref"
  check "no request was made for '$badref'" test ! -s "$t/up/requests.log"
done
setup; run --to 'a#b'; expect 2 "--to takes the same validation" "not a valid ref"
# No silent fallback to `master`: if the repository record cannot be read, the branch being
# watched is not known, and a stale `master` could be watched in place of the renamed default.
setup; rm "$api/.self"; run; expect 2 "an unreadable repository record is UNKNOWN, not master" "default branch"
setup; put '{"name": "flix"}' "$api/.self"; run; expect 2 "a repository record without a default branch is UNKNOWN" "default branch"
# The default branch's name comes from the API and goes into the same URL.
setup; put '{"default_branch": "a#b"}' "$api/.self"; run
  expect 2 "a default branch name that is not a valid ref is refused" "not a valid ref"
setup; put $sha3 "$api/commits/release/0.79"; run --from release/0.79
  expect 1 "a ref with a slash is valid" "$sha3"
setup_recorded;           run --from nosuch
  expect 2 "an unresolvable ref is UNKNOWN" "cannot resolve"
setup_recorded; put '<html>rate limited</html>' "$api/commits/master"; run
  expect 2 "a ref that does not resolve to a full SHA is UNKNOWN" "full SHA"
setup_recorded;           run --update --from v0.79.0
  expect 2 "--update takes no --from" "--from"
setup_recorded;           run --bogus
  expect 2 "an unknown argument is UNKNOWN" "unknown argument"

echo
echo "upstream-cli-test: $pass passed, $failed failed"
[ "$failed" -eq 0 ]
