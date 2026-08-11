#!/bin/sh
# flixw regression suite.
#
#   sh tests/run.sh
#
# Builds every fixture it needs under tests/.work/ and exercises the installed wrapper
# exactly as a user would: through ./flix, against an unmodified stock flix.jar.
#
# The suite downloads one real compiler on first run (~34 MB) and reuses the cache
# afterwards. Set FLIXW_TEST_VERSION to pin a different release.
#
# shellcheck disable=SC2016
# Several cases run `sh -c '...'` so that a failure inside the case cannot abort the
# suite. Their single quotes are deliberate: $FLIX_CACHE_HOME and friends must expand in
# the inner shell, under the environment the case sets up, not when the case is written.
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/flix
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$root/tests/.work/run
version=${FLIXW_TEST_VERSION:-0.75.2}

# A previous run may have left a read-only directory behind; make it removable.
# Spelled as an if rather than `A && B || true`: older shellcheck reads that idiom as a
# mistyped if-then-else (SC2015) and the runners do not all ship the same version.
if [ -d "$work" ]; then chmod -R u+w "$work" 2>/dev/null || true; fi
rm -rf "$work"
mkdir -p "$work"

cache=$work/cache
proj=$work/proj
export FLIX_CACHE_HOME="$cache"

pass=0
fail=0
skipped=0

# Two fixtures cannot exist on Windows, and asserting around that would be worse than
# saying so. A fake JDK needs a runnable bin/java.exe, and a copied java.exe resolves
# java.home from its own path, so it would find no lib/modules; and a JVM started from
# MSYS does not receive a POSIX SIGTERM, so the reaper cannot be provoked. Both
# behaviours are covered on Linux and macOS. See docs/LIMITATIONS.md.
case $(uname -s) in
  MINGW* | MSYS* | CYGWIN*) posix=no ;;
  *)                        posix=yes ;;
esac

# t <expected-rc> <label> <command...>
t() {
  want=$1
  label=$2
  shift 2
  set +e
  out=$("$@" 2>&1)
  rc=$?
  set -e
  if [ "$rc" = "$want" ]; then
    pass=$((pass + 1))
    printf '  ok   %-52s rc=%s\n' "$label" "$rc"
  else
    fail=$((fail + 1))
    printf '  FAIL %-52s rc=%s want=%s\n' "$label" "$rc" "$want"
    printf '       %s\n' "$(printf '%s' "$out" | head -6 | tr '\n' '|')"
  fi
}

# s <label> <reason>  -- record a case this platform cannot host
s() {
  skipped=$((skipped + 1))
  printf '  skip %-52s %s\n' "$1" "$2"
}

# g <expected-rc> <pattern> <label> <command...>  -- also assert output matches
g() {
  want=$1
  pat=$2
  label=$3
  shift 3
  set +e
  out=$("$@" 2>&1)
  rc=$?
  set -e
  if [ "$rc" = "$want" ] && printf '%s' "$out" | grep -q "$pat"; then
    pass=$((pass + 1))
    printf '  ok   %-52s rc=%s\n' "$label" "$rc"
  else
    fail=$((fail + 1))
    printf '  FAIL %-52s rc=%s want=%s /%s/\n' "$label" "$rc" "$want" "$pat"
    printf '       %s\n' "$(printf '%s' "$out" | head -6 | tr '\n' '|')"
  fi
}

# --- fixtures --------------------------------------------------------------

realjava=$(command -v java)

# A JDK whose release file reports <version>, delegating to the real java so that a
# candidate above the ceiling can actually be relaunched into.
fakejdk() {
  d=$work/jdk$1
  mkdir -p "$d/bin"
  printf 'JAVA_VERSION="%s.0.1"\n' "$1" > "$d/release"
  printf '#!/bin/sh\nexec %s "$@"\n' "$realjava" > "$d/bin/java"
  chmod +x "$d/bin/java"
}
fakejdk 17
fakejdk 99

# A JAR that answers --help in a format flixw cannot parse, and exits 0 for anything
# else. Used to prove that unparseable help degrades instead of bricking the wrapper.
mkdir -p "$work/impostor"
cat > "$work/impostor/Impostor.java" <<'EOF'
public final class Impostor {
    public static void main(String[] a) { System.out.println("impostor: no usage here"); }
}
EOF
javac -d "$work/impostor" "$work/impostor/Impostor.java"
printf 'Main-Class: Impostor\n' > "$work/impostor/mf"
(cd "$work/impostor" && jar cfm impostor.jar mf Impostor.class)

# A JAR that sleeps, so the reaper can be tested: Java has no exec(2), so stage 0 stays
# resident and must destroy its child when it is itself terminated. Without the shutdown
# hook a SIGTERM to stage 0 leaves the compiler running forever.
mkdir -p "$work/sleeper"
cat > "$work/sleeper/Sleeper.java" <<'EOF'
public final class Sleeper {
    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("--help")) {
            System.out.println("Usage: flix [check|run|test]");
            return;
        }
        Thread.sleep(120_000);
    }
}
EOF
javac -d "$work/sleeper" "$work/sleeper/Sleeper.java"
printf 'Main-Class: Sleeper\n' > "$work/sleeper/mf"
(cd "$work/sleeper" && jar cfm sleeper.jar mf Sleeper.class)

# The project under test.
mkdir -p "$proj/src"
cat > "$proj/flix.toml" <<EOF
[package]
name        = "flixw-regression"
description = "scratch project for the flixw regression suite"
version     = "0.1.0"
flix        = "$version"
authors     = ["nobody"]
EOF
cat > "$proj/src/Main.flix" <<'EOF'
def main(): Unit \ IO = println("ok")
EOF
# A project nested inside the first one. It exercises two rules at once: the search
# starts at cwd and takes the nearest manifest, and the wrapper anchor bounds it from
# above. Its program exits 42 so the child's status can be checked end to end.
mkdir -p "$proj/nested/src"
cat > "$proj/nested/flix.toml" <<EOF
[package]
name        = "flixw-nested"
description = "a project nested inside the first, exiting 42"
version     = "0.1.0"
flix        = "$version"
authors     = ["nobody"]
EOF
cat > "$proj/nested/src/Main.flix" <<'EOF'
def main(): Unit \ {IO, Sys.Exit} = {
    println("exiting 42");
    Sys.Exit.exit(42)
}
EOF

java "$root/src/flix.java" install "$proj" > /dev/null
cp "$root/src/flix.java" "$proj/.flix-wrapper/flix.java"

# The suite's scratch tree lives inside this repository, which gitignores it. `validate`
# reports gitignored wrapper files as a failure -- correctly -- so the fixture has to be
# its own work tree, exactly as a real consuming project is.
git init -q "$proj"

cd "$proj"
echo "flixw regression suite  (Flix $version, cache $cache)"
echo

# --- lock lifecycle --------------------------------------------------------
echo "lock lifecycle"
g 81 'no .*lock.toml'      "no lock blocks the compiler"        ./flix check
t 0  "pin creates the lock"                                     ./flix pin "$version"
t 0  "lock is reused"                                           ./flix --wrapper-version

# --- dispatch --------------------------------------------------------------
echo "dispatch"
t 0  "rule 1  -- pass-through"                                  ./flix -- --version
t 0  "rule 2  --wrapper-version"                                ./flix --wrapper-version
t 0  "rule 2  --wrapper-help"                                   ./flix --wrapper-help
t 87 "rule 2  flag with trailing arguments"                     ./flix --wrapper-help check
t 87 "unknown --wrapper- flag"                                  ./flix --wrapper-frobnicate
t 0  "rule 3  compiler verb"                                    ./flix check
g 0  'wrapper'   "rule 4  wrapper verb routes and says so"      ./flix doctor
t 1  "rule 5  unknown verb reaches the compiler"                ./flix frobnicate
t 0  "no arguments reaches the compiler"                        ./flix
t 0  "FLIX_BACKEND=wrapper forces the wrapper"                  env FLIX_BACKEND=wrapper ./flix validate
t 1  "FLIX_BACKEND=compiler forces the compiler"                env FLIX_BACKEND=compiler ./flix doctor
# An unrecognised value used to read as unset, which silently restores ordinary dispatch --
# the one outcome someone forcing a side is trying to rule out.
t 87 "an unknown FLIX_BACKEND is rejected, not ignored"         env FLIX_BACKEND=Compiler ./flix doctor

# --- version grammar -------------------------------------------------------
echo "version grammar"
t 81 "reject 0.75"                                              ./flix pin 0.75
t 81 "reject leading v"                                         ./flix pin "v$version"
t 81 "reject wildcard"                                          ./flix pin '0.75.*'
t 81 "reject range"                                             ./flix pin '>=0.75.0'
t 81 "reject traversal"                                         ./flix pin '../../etc'
t 81 "reject empty prerelease suffix"                           ./flix pin '0.75.2-'

# Build metadata is accepted in the manifest and stripped from the release tag and the
# cache coordinate. If canonical() were applied inconsistently this would either fail to
# resolve or produce a drift error that pin cannot repair.
t 0  "accept and strip build metadata"                          ./flix pin "$version+build.4"
g 0  "$version"  "stripped pin still resolves"                  ./flix --wrapper-help
./flix pin "$version" > /dev/null 2>&1

# --- manifest reading ------------------------------------------------------
# A regex over the whole file reads `flix` out of any table, or out of the body of a
# multi-line string. These cases pin the table-aware behaviour.
echo "manifest reading"
cp flix.toml "$work/flix.toml.good"
t 0  "a decoy flix key in another table is ignored"              sh -c '
  { cat "$1/flix.toml.good"; printf "\n[other]\nflix = \"9.9.9\"\n"; } > flix.toml
  ./flix -- --version' sh "$work"
t 0  "a decoy inside a multi-line string is ignored"             sh -c '
  { printf "[package]\nname = \"x\"\nversion = \"0.1.0\"\n"; \
    printf "description = \"\"\"\nflix = \"9.9.9\"\n\"\"\"\n"; \
    grep "^flix" "$1/flix.toml.good"; printf "authors = [\"n\"]\n"; } > flix.toml
  ./flix -- --version' sh "$work"
t 0  "a trailing comment on the version is ignored"              sh -c '
  sed "s/^flix .*/flix        = \"'"$version"'\"  # pinned/" "$1/flix.toml.good" > flix.toml
  ./flix -- --version' sh "$work"
t 81 "a duplicate [package] table is ambiguous"                  sh -c '
  { cat "$1/flix.toml.good"; printf "\n[package]\nflix = \"9.9.9\"\n"; } > flix.toml
  ./flix -- --version' sh "$work"
t 81 "an unquoted version is refused"                            sh -c '
  sed "s/^flix .*/flix        = '"$version"'/" "$1/flix.toml.good" > flix.toml
  ./flix -- --version' sh "$work"
t 81 "an unreadable manifest is not treated as absent"           sh -c '
  chmod 000 flix.toml
  ./flix -- --version; rc=$?
  chmod 644 flix.toml; exit $rc'
cp "$work/flix.toml.good" flix.toml
t 0  "pin only rewrites [package].flix"                          sh -c '
  { cat "$1/flix.toml.good"; printf "\n[other]\nflix = \"9.9.9\"\n"; } > flix.toml
  ./flix pin '"$version"' >/dev/null 2>&1
  grep -q "flix = \"9.9.9\"" flix.toml' sh "$work"
cp "$work/flix.toml.good" flix.toml
# pin used to run its own line scanner, which had never learned about multi-line strings.
# The decoy below was correctly invisible to the reader and yet visible to the rewriter,
# so pin saw two [package].flix keys and refused. Reader and writer now share tomlScan.
t 0  "pin ignores a flix key inside a multi-line description"    sh -c '
  { printf "[package]\nname = \"x\"\nversion = \"0.1.0\"\n"; \
    printf "description = \"\"\"\nflix = \"9.9.9\"\n\"\"\"\n"; \
    printf "flix = \"0.75.1\"\nauthors = [\"n\"]\n"; } > flix.toml
  ./flix pin '"$version"' >/dev/null 2>&1 || exit 1
  grep -q "^flix = \"'"$version"'\"" flix.toml || exit 1
  grep -q "^flix = \"9.9.9\"" flix.toml' sh "$work"
cp "$work/flix.toml.good" flix.toml
# The rewrite splits on \n and rejoins with \n so that a CRLF manifest keeps its endings;
# splitting on \r?\n silently rewrote the whole file to LF on the first pin.
t 0  "pin preserves CRLF line endings in the manifest"           sh -c '
  printf "[package]\r\nname = \"x\"\r\nversion = \"0.1.0\"\r\nflix = \"0.75.1\"\r\n" > flix.toml
  ./flix pin '"$version"' >/dev/null 2>&1 || exit 1
  grep -q "flix = \"'"$version"'\"" flix.toml || exit 1
  crs=$(tr -cd "\r" < flix.toml | wc -c | tr -d " ")
  lns=$(wc -l < flix.toml | tr -d " ")
  [ "$crs" = "$lns" ]' sh "$work"
cp "$work/flix.toml.good" flix.toml

# --- lock validation -------------------------------------------------------
echo "lock validation"
t 81 "a non-https url in the lock is refused"                    sh -c '
  cp .flix-wrapper/lock.toml "$1/lock.keep"
  sed "s|^url .*|url = \"http://example.invalid/flix.jar\"|" "$1/lock.keep" > .flix-wrapper/lock.toml
  ./flix -- --version; rc=$?
  cp "$1/lock.keep" .flix-wrapper/lock.toml; exit $rc' sh "$work"
t 81 "a malformed url in the lock is a diagnostic, not a crash"  sh -c '
  cp .flix-wrapper/lock.toml "$1/lock.keep"
  sed "s|^url .*|url = \"https://\"|" "$1/lock.keep" > .flix-wrapper/lock.toml
  ./flix -- --version; rc=$?
  cp "$1/lock.keep" .flix-wrapper/lock.toml; exit $rc' sh "$work"

# --- drift -----------------------------------------------------------------
echo "drift"
cp flix.toml "$work/flix.toml.bak"
sed 's/^flix .*/flix        = "0.75.1"/' flix.toml > "$work/drifted" && cp "$work/drifted" flix.toml
g 81 'declares 0.75.1' "drift blocks the compiler"              ./flix check
t 0  "drift does not block --wrapper-version"                   ./flix --wrapper-version
t 0  "drift does not block doctor"                              ./flix doctor
t 88 "drift does not block validate (which reports it)"         ./flix validate
cp "$work/flix.toml.bak" flix.toml

# --- integrity -------------------------------------------------------------
echo "integrity"
t 85 "truncated cache entry is refused"                         sh -c '
  jar=$(ls "$FLIX_CACHE_HOME"/compilers/*.jar | head -1)
  cp "$jar" "$jar.keep"; : > "$jar"
  ./flix -- --version; rc=$?
  mv "$jar.keep" "$jar"; exit $rc'
t 85 "wrong digest in the lock is refused"                      sh -c '
  cp .flix-wrapper/lock.toml /tmp/flixw-lock.keep
  sed "s/^sha256.*/sha256  = \"0000000000000000000000000000000000000000000000000000000000000000\"/" \
      /tmp/flixw-lock.keep > .flix-wrapper/lock.toml
  rm -f "$FLIX_CACHE_HOME"/compilers/*0000*.jar
  ./flix -- --version; rc=$?
  cp /tmp/flixw-lock.keep .flix-wrapper/lock.toml; exit $rc'
t 87 "FLIX_DIST_URL must be https"                              env FLIX_DIST_URL=http://x/y ./flix -- --version

# --- java selection --------------------------------------------------------
echo "java selection"
t 126 "broken FLIX_JAVA_HOME is caught by the shim"             env FLIX_JAVA_HOME=/nonexistent ./flix -- --version
# The three cases below drive a fake JDK: a lying release file over a bin/java that
# delegates to the real one. Windows would need that trampoline to be a genuine java.exe,
# which cannot be faked by copying -- the JVM resolves java.home from its own path.
if [ "$posix" = yes ]; then
  t 83  "explicit Java below the floor is fatal"                env FLIX_JAVA_HOME="$work/jdk17" ./flix -- --version
  g 0   'FLIXW011' "above the ceiling warns and proceeds"       env FLIX_JAVA_HOME="$work/jdk99" ./flix -- --version
  t 83  "FLIXW_STRICT_JAVA makes the ceiling fatal"             env FLIX_JAVA_HOME="$work/jdk99" FLIXW_STRICT_JAVA=1 ./flix -- --version
else
  s "explicit Java below the floor is fatal"                    "needs a runnable fake bin/java.exe"
  s "above the ceiling warns and proceeds"                      "needs a runnable fake bin/java.exe"
  s "FLIXW_STRICT_JAVA makes the ceiling fatal"                 "needs a runnable fake bin/java.exe"
fi

# --- jvm options -----------------------------------------------------------
echo "jvm options"
t 0  "safe options are passed through"                          env FLIX_JVM_OPTS="-Xmx512m -Dfoo=bar" ./flix -- --version
t 87 "an agent needs the unsafe opt-in"                         env FLIX_JVM_OPTS="-javaagent:/x" ./flix -- --version
t 87 "-jar is refused"                                          env FLIX_JVM_OPTS="-jar /x" ./flix -- --version
t 87 "an unterminated quote is refused"                         env FLIX_JVM_OPTS='-Dx="y' ./flix -- --version
t 0  "the unsafe opt-in works"                                  env FLIX_JVM_OPTS="-XX:OnError=true" FLIXW_UNSAFE_JVM_OPTS=1 ./flix -- --version

# --- project root ----------------------------------------------------------
echo "project root"
t 0  "invocation from a subdirectory"                           sh -c 'cd src && ../flix -- --version'
t 80 "invocation from outside the anchored tree is refused"     sh -c 'cd / && "'"$proj"'/flix" -- --version'
t 80 "FLIX_PROJECT_ROOT without a manifest"                     env FLIX_PROJECT_ROOT=/ ./flix -- --version
g 81 'nested' "the nearest manifest wins over the anchor's"     sh -c 'cd nested && ../flix -- --version'
mkdir -p "$proj/nested/.flix-wrapper"
cp "$proj/.flix-wrapper/lock.toml" "$proj/nested/.flix-wrapper/lock.toml"
t 0  "a nested project runs once it has its own lock"           sh -c 'cd nested && ../flix -- --version'

# --- degradation -----------------------------------------------------------
echo "degradation"
g 0 'FLIXW010' "unparseable --help falls back, does not brick"  env FLIX_JAR="$work/impostor/impostor.jar" ./flix check
t 87 "FLIX_JAR pointing at nothing is a usage error"            env FLIX_JAR=/nonexistent/x.jar ./flix check
t 0  "a read-only verb cache stays silent"                      sh -c '
  chmod -R a-w "$FLIX_CACHE_HOME/verbs"
  ./flix check; rc=$?
  chmod -R u+w "$FLIX_CACHE_HOME/verbs"; exit $rc'

# --- process behaviour -----------------------------------------------------
echo "process behaviour"
t 42 "the child exit status is propagated"                      sh -c 'cd nested && ../flix run'
if [ "$posix" != yes ]; then
  s "SIGTERM to stage 0 does not orphan the compiler"           "MSYS cannot signal a native JVM"
else
t 0  "SIGTERM to stage 0 does not orphan the compiler"          sh -c '
  FLIX_JAR="$1/sleeper/sleeper.jar" ./flix check >/dev/null 2>&1 &
  outer=$!
  # Wait for the compiler child to exist, then kill only the stage-0 JVM above it.
  # Match on the verb too: the short-lived --help probe shares the jar name.
  #
  # A command-line match alone is not enough, and the way it fails is instructive: this
  # script contains the pattern it searches for, so pgrep -f matches the sh running the
  # case -- and the subshell of the command substitution -- as well as the JVM. Those have
  # lower pids, so `head -1` selected the poller itself on Linux and the case then waited
  # for its own shell to exit. Requiring the process to actually be a JVM settles it.
  n=0
  inner=
  while [ $n -lt 150 ]; do
    for pid in $(pgrep -f "sleeper.jar check" 2>/dev/null); do
      case $(ps -o comm= -p "$pid" 2>/dev/null) in
        *java*) inner=$pid; break ;;
      esac
    done
    [ -n "$inner" ] && break
    n=$((n + 1)); sleep 0.1
  done
  [ -n "$inner" ] || { echo "the compiler child never started"; exit 2; }
  # Captured on every run, shown only when the case fails: an inner_ppid that is not
  # outer means stage 0 relaunched itself, and the signal has one more JVM to cross.
  echo "outer=$outer inner=$inner ppid=$(ps -o ppid= -p "$inner" 2>/dev/null | tr -d " ")"
  kill -TERM "$outer" 2>/dev/null || true
  wait "$outer" 2>/dev/null || true
  # The reaper must take the compiler with it. Poll rather than sleeping a fixed
  # interval: what is asserted is that the child dies, not that a loaded runner
  # delivers the signal, runs a shutdown hook and reaps the process inside one second.
  n=0
  while [ $n -lt 100 ]; do
    kill -0 "$inner" 2>/dev/null || exit 0
    n=$((n + 1)); sleep 0.1
  done
  echo "the compiler child $inner outlived stage 0"
  ps -o pid,ppid,stat,args -p "$inner" 2>/dev/null || true
  exit 1' sh "$work"
fi
t 0  "stdout carries only compiler output"                      sh -c '
  ./flix run > "$1/out.txt" 2>/dev/null
  grep -qx ok "$1/out.txt"' sh "$work"

# --- unit checks -----------------------------------------------------------
# Compiled against stage 0 itself, so it can reach the manifest scanner and the bounded
# capture directly. Its output is shown rather than swallowed: the corpus size and the
# per-group counts are the interesting part, and one shell case cannot express them.
echo "unit checks"
javac -d "$work/unit" "$root/src/flix.java" "$root/tests/UnitCheck.java"
set +e
java -cp "$work/unit" UnitCheck "$root/tests/corpus"
unit_rc=$?
set -e
t 0  "manifest corpus, pin rewrite and capture bounds"          test "$unit_rc" = 0

# --- diagnostics -----------------------------------------------------------
echo "diagnostics"
# doctor output is meant to be pasted into bug reports, so it must not carry the password
# out of a proxy URL. Java's HttpClient ignores these variables; only doctor reads them.
t 0  "doctor redacts credentials in proxy urls"                 sh -c '
  out=$(HTTPS_PROXY="http://user:hunter2@proxy.example:3128" ./flix doctor 2>&1)
  printf "%s" "$out" | grep -q "[*][*][*]@proxy.example" || exit 1
  ! printf "%s" "$out" | grep -q hunter2'
# stdout only: the JVM announces "Picked up JAVA_TOOL_OPTIONS: ..." on stderr itself, which
# no wrapper can suppress. What flixw controls is its own report, and that must be clean.
t 0  "doctor redacts secrets in JVM option variables"           sh -c '
  out=$(JAVA_TOOL_OPTIONS="-Dhttps.proxyPassword=hunter2 -Xmx1g" ./flix doctor 2>/dev/null)
  printf "%s" "$out" | grep -q "proxyPassword=[*][*][*]" || exit 1
  ! printf "%s" "$out" | grep -q hunter2'
# The stage-0 cache is keyed by source hash alone, so a class compiled by a newer JDK
# would be handed to a Java 21 shim. 65 is the classfile version of the declared floor.
t 0  "the cached stage 0 targets the Java floor"                sh -c '
  d=$(ls -d "$FLIX_CACHE_HOME"/stage0/*/ 2>/dev/null | head -1)
  [ -n "$d" ] || exit 1
  major=$(od -An -tu1 -j7 -N1 "$d/flix.class" | tr -d " ")
  [ "$major" = "65" ] || { echo "classfile major $major, want 65"; exit 1; }'

# --- maintenance verbs -----------------------------------------------------
echo "maintenance verbs"
t 0  "validate passes on a healthy project"                     ./flix validate
g 88 'differs from flixw' "validate detects an edited shim"     sh -c '
  cp flix "$1/flix.keep"; echo "# tampered" >> flix
  ./flix validate; rc=$?
  cp "$1/flix.keep" flix; chmod +x flix; exit $rc' sh "$work"
g 88 'overrides it' "validate detects a gitattributes override" sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "* text=auto eol=crlf\n" >> .gitattributes
  ./flix validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
t 0  "update-wrapper is a no-op when files match"               ./flix update-wrapper
g 0  'rewrote' "update-wrapper repairs a clobbered shim"        sh -c 'echo broken > flix.cmd; ./flix update-wrapper'
t 0  "the repaired shim matches the source of truth"            cmp flix.cmd "$root/src/flix.cmd"
t 0  "update-wrapper restores the executable bit"               sh -c 'chmod -x flix; java .flix-wrapper/flix.java update-wrapper; test -x flix'

# --- git integration -------------------------------------------------------
echo "git integration"
t 0  "validate warns when generated files are untracked"        sh -c 'git init -q . 2>/dev/null; ./flix validate'
g 88 'gitignore' "validate fails when the lock is ignored"      sh -c '
  echo ".flix-wrapper/" > .gitignore
  ./flix validate; rc=$?
  rm -f .gitignore; exit $rc'

echo
echo "passed=$pass failed=$fail skipped=$skipped"
[ "$fail" -eq 0 ]
