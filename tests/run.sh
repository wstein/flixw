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

# A JDK stand-in with no release file at all: the shape asdf, mise and jenv install,
# where `java` is a shim script rather than a symlink into a JDK layout. The wrapper
# cannot tell what version this is, which is the whole point of the fixture.
mkdir -p "$work/jdkbare/bin"
printf '#!/bin/sh\nexec %s "$@"\n' "$realjava" > "$work/jdkbare/bin/java"
chmod +x "$work/jdkbare/bin/java"

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
t 0  "lock is reused"                                           ./flix wrapper --version

# pin now takes an optional owner/repository. These need no network: the repository is
# rejected, or the arguments are, before any request is made.
t 81 "pin rejects a malformed repository"                       ./flix pin not/a/repo "$version"
t 88 "pin rejects two repositories"                             ./flix pin a/b c/d "$version"
t 88 "pin rejects two versions"                                 ./flix pin 0.75.1 "$version"
# The source is recorded so a bare re-pin cannot silently move the project elsewhere.
t 0  "pin records the repository it fetched from"               sh -c '
  grep -q "^repo    = \"flix/flix\"" .flix-wrapper/lock.toml'
t 0  "a bare re-pin keeps the recorded repository"              sh -c '
  ./flix pin '"$version"' >/dev/null 2>&1
  grep -q "^repo    = \"flix/flix\"" .flix-wrapper/lock.toml'

# --- dispatch --------------------------------------------------------------
echo "dispatch"
t 0  "rule 1  -- pass-through"                                  ./flix -- --version
t 0  "rule 2  wrapper --version"                                ./flix wrapper --version
t 0  "rule 2  wrapper --help"                                   ./flix wrapper --help
t 87 "an operation with trailing arguments"                     ./flix wrapper --help check
t 87 "an unknown wrapper operation"                                  ./flix wrapper --frobnicate
# The install itself is a 200MB download and is verified by hand; what the suite can
# assert offline is that the flag exists and refuses arguments.
t 87 "wrapper --install-jdk takes no arguments"                 ./flix wrapper --install-jdk temurin
t 0  "rule 3  compiler verb"                                    ./flix check
t 0  "rule 4  wrapper verb"                                     ./flix doctor
# Routing used to be announced on every wrapper-handled command, which told the caller
# what they had just typed. It belongs to whoever is debugging dispatch, and nobody else.
t 0  "routing is silent by default"                             sh -c '
  ! ./flix doctor 2>&1 >/dev/null | grep -q "wrapper 0"'
g 0 'does not implement it' "routing is visible under FLIXW_TRACE"  env FLIXW_TRACE=1 ./flix doctor
t 1  "rule 5  unknown verb reaches the compiler"                ./flix frobnicate
# No verb starts the REPL, which reads stdin until EOF. The case must supply that EOF
# itself: inheriting a terminal -- or any stdin that stays open -- hangs the suite forever
# rather than failing it, which is exactly what it did once.
t 0  "no arguments reaches the compiler"                        sh -c './flix < /dev/null'
t 0  "FLIX_BACKEND=wrapper forces the wrapper"                  env FLIX_BACKEND=wrapper ./flix validate
t 1  "FLIX_BACKEND=compiler forces the compiler"                env FLIX_BACKEND=compiler ./flix doctor
# An unrecognised value used to read as unset, which silently restores ordinary dispatch --
# the one outcome someone forcing a side is trying to rule out.
t 87 "an unknown FLIX_BACKEND is rejected, not ignored"         env FLIX_BACKEND=Compiler ./flix doctor

# `help` and `--help` answer with both halves. The escape hatches must still reach the
# compiler alone, because that is what anyone parsing its output is asking for.
g 0 'repository-local Flix bootstrap' "help merges both halves"  ./flix help
g 0 'The Flix Programming Language'   "help includes the compiler's own"  ./flix help
g 0 'repository-local Flix bootstrap' "--help merges them too"    ./flix --help
g 0 'The Flix Programming Language'   "-- --help is the compiler alone"  ./flix -- --help
t 0 "-- --help does not merge"                                  sh -c '
  ! ./flix -- --help 2>&1 | grep -q "repository-local Flix bootstrap"'
t 0 "FLIX_BACKEND=compiler does not merge"                      sh -c '
  ! FLIX_BACKEND=compiler ./flix --help 2>&1 | grep -q "repository-local"'
t 0 "a flag with arguments is passed through untouched"         sh -c '
  ! ./flix --help check 2>&1 | grep -q "repository-local"'

# info reports, validate judges, doctor does both. The split matters because doctor used
# to print twelve lines of state, notice nothing, and exit 0 with an edited shim.
t 0  "info reports without judging"                             ./flix info
g 88 'problem' "doctor catches what info does not"              sh -c '
  cp flix "$1/flix.keep"; echo "# tampered" >> flix
  ./flix doctor; rc=$?
  cp "$1/flix.keep" flix; chmod +x flix; exit $rc' sh "$work"
t 0  "info is blind to the same tampering"                      sh -c '
  cp flix "$1/flix.keep"; echo "# tampered" >> flix
  ./flix info >/dev/null 2>&1; rc=$?
  cp "$1/flix.keep" flix; chmod +x flix; exit $rc' sh "$work"
t 0  "doctor --fix repairs what it reports"                     sh -c '
  cp flix "$1/flix.keep"; echo "# tampered" >> flix
  ./flix doctor --fix >/dev/null 2>&1
  ./flix doctor >/dev/null 2>&1; rc=$?
  cp "$1/flix.keep" flix; chmod +x flix; exit $rc' sh "$work"
t 87 "doctor rejects an unknown option"                         ./flix doctor --frobnicate

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
# pin never touches flix.toml: that key is Flix's, with Flix's rules, and Flix rejects
# anything but x.x.x there. The exact version lives in the lock, which is flixw's file.
t 0  "pin leaves flix.toml alone"                               sh -c '
  cp flix.toml "$1/toml.before"
  ./flix pin '"$version"'+build.4 >/dev/null 2>&1
  cmp -s flix.toml "$1/toml.before"' sh "$work"
t 0  "the exact version lands in the lock"                      sh -c '
  grep -q "^version = \"'"$version"'+build.4\"" .flix-wrapper/lock.toml'
# A manifest floor below the pinned compiler is the normal case, not drift.
t 0  "a lower floor in flix.toml is satisfied, not drift"       sh -c '
  cp flix.toml "$1/toml.keep"
  sed "s/^flix .*/flix        = \"0.70.0\"/" "$1/toml.keep" > flix.toml
  ./flix check >/dev/null 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
t 0  "accept and strip build metadata"                          ./flix pin "$version+build.4"
g 0  "$version"  "stripped pin still resolves"                  ./flix wrapper --help
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
# The one command documented as the repair has to work in the state it repairs. A lock
# that does not parse used to throw before routing ever reached pin.
t 0  "pin repairs a lock that does not parse"                    sh -c '
  cp .flix-wrapper/lock.toml "$1/lock.keep"
  sed "s/^sha256.*/sha256  = \"not-a-digest\"/" "$1/lock.keep" > .flix-wrapper/lock.toml
  ./flix pin '"$version"' >/dev/null 2>&1
  grep -q "^sha256" .flix-wrapper/lock.toml' sh "$work"
t 81 "a lock that does not parse still blocks the compiler"      sh -c '
  cp .flix-wrapper/lock.toml "$1/lock.keep"
  sed "s/^sha256.*/sha256  = \"not-a-digest\"/" "$1/lock.keep" > .flix-wrapper/lock.toml
  ./flix check; rc=$?
  cp "$1/lock.keep" .flix-wrapper/lock.toml; exit $rc' sh "$work"

# A manifest that does not parse must not take the repair verbs down with it -- the same
# trap as an unparseable lock, one file over.
# Reaching the verb at all is the point: 88 means doctor ran and judged, where 81
# would mean it never got there. It reports the broken manifest rather than
# exiting 0 over it, which is the whole reason doctor now judges.
g 88 'flix.toml' "doctor runs on a manifest that does not parse"  sh -c '
  cp flix.toml "$1/toml.keep"
  printf "\n[package]\nflix = \"9.9.9\"\n" >> flix.toml
  ./flix doctor; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
t 88 "validate reports a manifest that does not parse"           sh -c '
  cp flix.toml "$1/toml.keep"
  printf "\n[package]\nflix = \"9.9.9\"\n" >> flix.toml
  ./flix validate >/dev/null 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
t 81 "a manifest that does not parse still blocks the compiler"  sh -c '
  cp flix.toml "$1/toml.keep"
  printf "\n[package]\nflix = \"9.9.9\"\n" >> flix.toml
  ./flix check; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"

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
# pin used to run its own line scanner, which had never learned about multi-line strings,
# so a decoy inside a description read as a second [package].flix key. pin no longer writes
# the manifest at all, but the floor check still reads it, and must still ignore the decoy.
t 0  "the floor reader ignores a flix key inside a description"  sh -c '
  cp flix.toml "$1/toml.keep"
  { printf "[package]\nname = \"x\"\nversion = \"0.1.0\"\n"; \
    printf "description = \"\"\"\nflix = \"9.9.9\"\n\"\"\"\n"; \
    printf "flix = \"0.70.0\"\nauthors = [\"n\"]\n"; } > flix.toml
  ./flix doctor >/dev/null 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
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
sed 's/^flix .*/flix        = "0.99.0"/' flix.toml > "$work/drifted" && cp "$work/drifted" flix.toml
g 81 'or newer' "an unsatisfied floor blocks the compiler"              ./flix check
t 0  "drift does not block wrapper --version"                   ./flix wrapper --version
g 88 'or newer' "an unsatisfied floor does not block doctor"           ./flix doctor
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
# URI.create accepts a hostless URL, so the scheme test alone let `https:///mirror`
# through and it resurfaced as an uncaught IllegalArgumentException mid-download.
t 87 "FLIX_DIST_URL without a host is a diagnostic, not a crash" env FLIX_DIST_URL='https:///mirror' ./flix -- --version
t 87 "FLIX_DIST_URL with traversal is refused"                  env FLIX_DIST_URL='https://m.example/../x' ./flix -- --version

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
  # The compiled stage 0 is built for the floor and the shim execs it, which leaves no
  # way back: handing it to an older JVM is an UnsupportedClassVersionError with no
  # FLIXW code reached and no fallback. The shim must therefore decline the fast path
  # below the floor. Both fixtures' bin/java is really the host java, so the only
  # observable difference is which route the shim took -- which is exactly the bug.
  g 0   'stage0 source'   "below the floor the shim declines the class"   env FLIX_JAVA_HOME="$work/jdk17" ./flix wrapper --version
  g 0   'stage0 compiled' "at the floor or above the shim uses it"        env FLIX_JAVA_HOME="$work/jdk99" ./flix wrapper --version
  # asdf, mise and jenv install `java` as a shim script, not a symlink into a JDK, so
  # there is no release file beside it. Running the cached class blind under one of those
  # pointing at an old JVM died on class file version with no way back, so an
  # unidentifiable Java now earns the source path rather than the fast one.
  g 0   'stage0 source' "an unidentifiable Java declines the class too"   env FLIX_JAVA_HOME="$work/jdkbare" ./flix wrapper --version

  # With no java on PATH at all the shim never reaches stage 0, so the only help a user
  # gets is the shim's own message -- and the only route back is a JDK flixw installed
  # earlier, whose path it reads from a file rather than guessing a vendor's layout.
  # PATH is stripped to a directory of the utilities the shim itself needs, minus java.
  mkdir -p "$work/tools"
  for u in uname dirname readlink cat sed cut head grep tr shasum sha256sum openssl; do
    p=$(command -v "$u" 2>/dev/null) && ln -sf "$p" "$work/tools/$u"
  done
  g 127 'Temurin' "no java at all names a JDK to install"       env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flix check
  g 127 'install-jdk' "and says how flixw can fetch one" env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flix check
  # A recorded JDK is used when nothing else answers. The fixture stands in for a real
  # install so the suite stays offline; what is under test is the shim reading it.
  # A stand-in for an installed JDK, inside the cache where a real one lands: the marker
  # is only honoured when it names something there, since the shims execute what it names.
  mkdir -p "$cache/jdks/fake/bin"
  printf '#!/bin/sh\nexec %s "$@"\n' "$realjava" > "$cache/jdks/fake/bin/java"
  chmod +x "$cache/jdks/fake/bin/java"
  printf 'JAVA_VERSION="21.0.1"\n' > "$cache/jdks/fake/release"
  printf '%s\n' "$cache/jdks/fake/bin/java" > "$cache/jdks/default"
  g 0 'flixw' "a recorded JDK is used when PATH has none"       env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flix wrapper --version
  # A java below the floor on PATH is worse than none: under 15 it cannot even compile
  # stage 0, so nothing flixw knows is ever reached. A recorded JDK outranks it.
  g 0 'stage0' "a recorded JDK outranks a below-floor PATH java" env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/jdk17/bin:$work/tools" ./flix wrapper --version
  # But never an explicitly named one: those fail loudly rather than being replaced by a
  # JVM the caller did not ask for.
  t 83 "an explicit below-floor JDK is not silently replaced"    env FLIX_JAVA_HOME="$work/jdk17" ./flix -- --version
  # A marker naming something outside the cache is an instruction to run someone else's
  # binary, not a record of an install.
  printf '%s\n' "$realjava" > "$cache/jdks/default"
  g 127 'no java executable' "a marker outside the cache is ignored"  env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flix check
  rm -rf "$cache/jdks/default" "$cache/jdks/fake"
else
  s "explicit Java below the floor is fatal"                    "needs a runnable fake bin/java.exe"
  s "above the ceiling warns and proceeds"                      "needs a runnable fake bin/java.exe"
  s "FLIXW_STRICT_JAVA makes the ceiling fatal"                 "needs a runnable fake bin/java.exe"
  s "below the floor the shim declines the class"               "needs a runnable fake bin/java.exe"
  s "at the floor or above the shim uses it"                    "needs a runnable fake bin/java.exe"
  s "an unidentifiable Java declines the class too"              "needs a runnable fake bin/java.exe"
  s "no java at all names a JDK to install"                     "PATH cannot be stripped the same way"
  s "and says how flixw can fetch one"                          "PATH cannot be stripped the same way"
  s "a recorded JDK is used when PATH has none"                 "PATH cannot be stripped the same way"
  s "a recorded JDK outranks a below-floor PATH java"           "PATH cannot be stripped the same way"
  s "an explicit below-floor JDK is not silently replaced"      "needs a runnable fake bin/java.exe"
  s "a marker outside the cache is ignored"                     "PATH cannot be stripped the same way"
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
g 88 'changes flix' "validate detects a gitattributes override" sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "* text=auto eol=crlf\n" >> .gitattributes
  ./flix validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
# The most direct override of all names the file outright, and a wildcard-only check
# read it as healthy -- while CRLF on the POSIX shim makes it unrunnable.
g 88 'changes flix' "an exact later rule is an override too"     sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "/flix text eol=crlf\n" >> .gitattributes
  ./flix validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
# Two blocks means the last one wins, and rewriting each in place left two.
g 88 'markers' "validate detects a second flixw block"      sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "# >>> flixw >>>\n/flix text eol=crlf\n# <<< flixw <<<\n" >> .gitattributes
  ./flix validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
t 0  "doctor --fix collapses duplicate blocks to one"          sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "# >>> flixw >>>\n/flix text eol=crlf\n# <<< flixw <<<\n" >> .gitattributes
  ./flix doctor --fix >/dev/null 2>&1
  n=$(grep -c ">>> flixw >>>" .gitattributes)
  cp "$1/ga.keep" .gitattributes
  [ "$n" = 1 ]' sh "$work"
# Repeating what the block already says changes nothing, and calling it an override
# would send someone hunting for a problem they do not have.
t 0  "a later rule identical to the block is harmless"           sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "/flix text eol=lf\n" >> .gitattributes
  ./flix validate >/dev/null 2>&1; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
g 88 'markers' "an unbalanced flixw marker is a failure"         sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "# <<< flixw <<<\n" >> .gitattributes
  ./flix validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
t 0  "doctor --fix is a no-op when files match"                ./flix doctor --fix
g 0  'rewrote' "doctor --fix repairs a clobbered shim"        sh -c 'echo broken > flix.cmd; ./flix doctor --fix'
t 0  "the repaired shim matches the source of truth"            cmp flix.cmd "$root/src/flix.cmd"
t 0  "doctor --fix restores the executable bit"                sh -c 'chmod -x flix; java .flix-wrapper/flix.java doctor --fix; test -x flix'

# flixw's own namespace is answered before dispatch, so nothing can take it away: not a
# compiler that claimed the name, not FLIX_BACKEND, and not a lock too broken to read --
# which is the state it exists to repair.
t 0  "bare wrapper prints the routing table"                   ./flix wrapper
t 87 "wrapper rejects an unknown operation"                    ./flix wrapper --frobnicate
t 87 "wrapper --upgrade takes no arguments"                    ./flix doctor --fix now
# FLIX_BACKEND=compiler forces every bare verb to the compiler, doctor among them.
# The exemption belongs to flixw's own namespace, so that is what is tested.
t 0  "wrapper --version survives FLIX_BACKEND=compiler"       env FLIX_BACKEND=compiler ./flix wrapper --version
# 88 because doctor judges and the lock is broken; the point is that it ran at
# all and repaired the shim, rather than being blocked before routing.
g 88 'rewrote' "doctor --fix survives an unreadable lock"        sh -c '
  cp .flix-wrapper/lock.toml "$1/lock.keep"
  printf "garbage\n" > .flix-wrapper/lock.toml
    echo broken > flix.cmd; ./flix doctor --fix; rc=$?
  cp "$1/lock.keep" .flix-wrapper/lock.toml; exit $rc' sh "$work"

# --upgrade moves to the newest published flixw. The suite runs a version no release has
# yet, so what it can assert offline is the guard that keeps that from walking backwards.
g 0 'Nothing to do' "upgrade declines to downgrade"             ./flix wrapper --upgrade

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
