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
[ -d "$work" ] && chmod -R u+w "$work" 2>/dev/null || true
rm -rf "$work"
mkdir -p "$work"

cache=$work/cache
proj=$work/proj
export FLIX_CACHE_HOME="$cache"

pass=0
fail=0

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
    printf '       %s\n' "$(printf '%s' "$out" | head -3 | tr '\n' '|')"
  fi
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
    printf '       %s\n' "$(printf '%s' "$out" | head -3 | tr '\n' '|')"
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
t 83  "explicit Java below the floor is fatal"                  env FLIX_JAVA_HOME="$work/jdk17" ./flix -- --version
g 0   'FLIXW011' "above the ceiling warns and proceeds"         env FLIX_JAVA_HOME="$work/jdk99" ./flix -- --version
t 83  "FLIXW_STRICT_JAVA makes the ceiling fatal"               env FLIX_JAVA_HOME="$work/jdk99" FLIXW_STRICT_JAVA=1 ./flix -- --version

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
t 0  "SIGTERM to stage 0 does not orphan the compiler"          sh -c '
  FLIX_JAR="$1/sleeper/sleeper.jar" ./flix check >/dev/null 2>&1 &
  outer=$!
  # Wait for the compiler child to exist, then kill only the stage-0 JVM above it.
  # Match on the verb too: the short-lived --help probe shares the jar name.
  n=0
  while [ $n -lt 150 ]; do
    inner=$(pgrep -f "sleeper.jar check" 2>/dev/null | head -1)
    [ -n "$inner" ] && break
    n=$((n + 1)); sleep 0.1
  done
  [ -n "$inner" ] || exit 1
  kill -TERM "$outer" 2>/dev/null || true
  wait "$outer" 2>/dev/null || true
  sleep 1
  # The reaper must have taken the compiler with it.
  ! kill -0 "$inner" 2>/dev/null' sh "$work"
t 0  "stdout carries only compiler output"                      sh -c '
  ./flix run > "$1/out.txt" 2>/dev/null
  grep -qx ok "$1/out.txt"' sh "$work"

# --- maintenance verbs -----------------------------------------------------
echo "maintenance verbs"
t 0  "validate passes on a healthy project"                     ./flix validate
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
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
