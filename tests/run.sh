#!/bin/sh
# flixw regression suite.
#
#   sh tests/run.sh
#
# Builds every fixture it needs under tests/.work/ and exercises the installed wrapper
# exactly as a user would: through ./flixw, against an unmodified stock flix.jar.
#
# The suite downloads one real compiler on first run (~34 MB) and reuses the cache
# afterwards. Set FLIXW_TEST_VERSION to pin a different release.
#
# shellcheck disable=SC2016
# Several cases run `sh -c '...'` so that a failure inside the case cannot abort the
# suite. Their single quotes are deliberate: $FLIX_CACHE_HOME and friends must expand in
# the inner shell, under the environment the case sets up, not when the case is written.
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/stage0/flixw
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
# Java, not the shell, has to be able to resolve this. Git Bash reports /d/a/x, which a JVM
# reads as \d\a\x on the *current drive* -- so the cache silently landed in D:\d\a\...
# and no assertion about its location could hold. cygpath -m gives D:/a/x, which both the
# JVM and Git Bash resolve to the same directory, so $cache stays usable in shell tests.
if command -v cygpath >/dev/null 2>&1; then cache_native=$(cygpath -m "$cache")
else cache_native=$cache; fi
export FLIX_CACHE_HOME="$cache_native"

# The suite asserts what flixw does with a *clean* environment, and every variable below
# changes that. A developer working on a Flix fork legitimately has FLIX_JAR exported, and
# with it set this suite reports five failures -- two of them "truncated cache entry is
# refused: rc=0 want=85" and "wrong digest in the lock is refused: rc=0 want=85", because
# an override is by design not digest-verified and the case sails straight through it.
# That is the most alarming thing this suite can say and it would be saying it about the
# developer's shell, not about the code. Individual cases still set these deliberately;
# what is refused here is inheriting one by accident.
#
# JAVA_HOME and FLIX_JAVA_HOME are deliberately *not* cleared: which JDK a developer runs
# is legitimate input, and Java selection is one of the things under test.
unset FLIX_JAR FLIX_DIST_URL FLIX_BACKEND FLIX_JVM_OPTS
unset FLIXW_STRICT_JAVA FLIXW_TRACE FLIXW_UNSAFE_JVM_OPTS FLIXW_RELAUNCHED FLIXW_ASSET_SOURCE
unset FLIXW_RELEASE_SOURCE FLIXW_PLUGIN_CACHE

# A release, stood up in a directory. Everything flixw fetches at run time now comes from
# a companion asset -- the installer among them -- so this has to exist before the first
# `install` in the suite rather than beside the completion cases that used to own it.
# Same code path as production; only the base differs, and nothing here touches the network.
# Git Bash reports paths as /d/a/flixw, which is meaningful to it and to nothing else: a
# JVM resolves file:///d/a/flixw to \d\a\flixw on the current drive, and nothing is there.
# Windows had never run a file:// case -- the plugin and asset ones postdate the last green
# Windows build -- so both platforms were green while every such url was unusable on one.
fileurl() {
  if command -v cygpath >/dev/null 2>&1; then printf 'file:///%s' "$(cygpath -m "$1")"
  else printf 'file://%s' "$1"; fi
}

relfixture=$work/release
mkdir -p "$relfixture"
cp "$root/src/stage0/flixw.java" "$root/src/assets/flixw-jdk.java" \
   "$root/src/assets/flixw-setup.java" "$root/src/assets/flixw-inspect.java" "$root/src/assets/flixw-help.java" \
   "$relfixture/"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$relfixture" && sha256sum flixw.java flixw-jdk.java \
     flixw-setup.java flixw-inspect.java flixw-help.java > SHA256SUMS)
else
  (cd "$relfixture" && shasum -a 256 flixw.java flixw-jdk.java \
     flixw-setup.java flixw-inspect.java flixw-help.java > SHA256SUMS)
fi
# The renderer's picocli rides the fixture exactly as it rides a real release, so the
# suite exercises the same ensureAsset path a user takes rather than a special case.
picocli_v=$(sed -n 's/.*PICOCLI_VERSION = "\([^"]*\)".*/\1/p' "$root/src/stage0/flixw.java")
picocli_jar=$root/tests/.work/picocli-$picocli_v.jar
if [ ! -f "$picocli_jar" ]; then
  curl -fsSL -o "$picocli_jar" \
    "https://repo1.maven.org/maven2/info/picocli/picocli/$picocli_v/picocli-$picocli_v.jar" \
    2>/dev/null || true
fi
if [ -f "$picocli_jar" ]; then
  cp "$picocli_jar" "$relfixture/picocli-$picocli_v.jar"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$relfixture" && sha256sum "picocli-$picocli_v.jar" >> SHA256SUMS)
  else
    (cd "$relfixture" && shasum -a 256 "picocli-$picocli_v.jar" >> SHA256SUMS)
  fi
fi
relfixture_url=$(fileurl "$relfixture")
export FLIXW_ASSET_SOURCE="$relfixture_url/"


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

# The same shape, but the JVM behind it is below the floor -- the case that actually
# hurts: no release file to read, and a JVM that cannot compile stage 0. It answers
# -version as Java 17 and otherwise defers to the real one, so the swap it should
# trigger can be observed without a second JDK on the machine.
mkdir -p "$work/jdkbare17/bin"
cat > "$work/jdkbare17/bin/java" <<EOF
#!/bin/sh
if [ "\$1" = "-version" ]; then
  echo 'openjdk version "17.0.9" 2023-10-17' >&2
  exit 0
fi
exec $realjava "\$@"
EOF
chmod +x "$work/jdkbare17/bin/java"

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

# A JAR whose --help is picocli's layout rather than scopt's: the usage bracket wraps
# across lines, and the verbs are an indented `Commands:` block instead of one `Command:`
# line each. The picocli-based fork renders this. A scopt-only parser finds nothing in it
# at all, so the wrapper reported FLIXW010 and silently fell back to the built-in 0.75.x
# table -- losing every verb the fork had added.
mkdir -p "$work/picocli"
cat > "$work/picocli/Picocli.java" <<'EOF'
public final class Picocli {
    public static void main(String[] a) {
        if (a.length == 0 || !a[0].equals("--help")) return;
        System.out.println("The Flix Programming Language");
        System.out.println("Usage: flix [init|check|capabilities|stubs|build|");
        System.out.println("             clean|run|test|repl] [options] <file>...");
        System.out.println("      <file>...             input Flix source code files.");
        System.out.println("  -h, --help                prints this usage information.");
        System.out.println("Commands:");
        System.out.println("  init          creates a new project.");
        System.out.println("  check         checks the current project for errors.");
        System.out.println("  capabilities  reports the tooling contract this compiler speaks.");
        System.out.println("  stubs         writes compile-only Java stubs for @Export-ed defs.");
        System.out.println("  clean         recursively removes class files from the build");
        System.out.println("                  directory.");
        System.out.println("  run           runs main for the current project.");
        System.out.println("Experimental options and commands are omitted.");
    }
}
EOF
javac -d "$work/picocli" "$work/picocli/Picocli.java"
printf 'Main-Class: Picocli\n' > "$work/picocli/mf"
(cd "$work/picocli" && jar cfm picocli.jar mf Picocli.class)

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

# Three plugin formats, one echo: each prints every FLIXW_* variable the ABI promises
# and the raw FLIXW_CONTEXT file body (newlines escaped, so a grep sees one line), so a
# test can assert the ABI actually delivers correct context without parsing JSON in a
# POSIX shell. `plugin install` names the destination "plugin.<format>" regardless of
# the source file's own name, so only the source URL needs the matching extension.
mkdir -p "$work/pluginjar"
cat > "$work/pluginjar/EchoPlugin.java" <<'EOF'
import java.nio.file.Files;
import java.nio.file.Paths;

public final class EchoPlugin {
    public static void main(String[] a) throws Exception {
        for (String k : new String[] {
                "FLIXW_ABI_VERSION", "FLIXW_PROJECT_ROOT", "FLIXW_CACHE_HOME",
                "FLIXW_COMPILER_VERSION", "FLIXW_COMPILER_JAR", "FLIXW_JAVA_HOME",
                "FLIXW_PLUGIN_NAME", "FLIXW_PLUGIN_VERSION", "FLIXW_PLUGIN_CACHE",
                "FLIXW_CONTEXT" }) {
            String v = System.getenv(k);
            System.out.println(k + "=" + (v == null ? "" : v));
        }
        String ctx = System.getenv("FLIXW_CONTEXT");
        if (ctx != null)
            System.out.println("CONTEXT_BODY=" + Files.readString(Paths.get(ctx)).replace("\n", "\\n"));
        System.out.println("ARGS=" + String.join(",", a));
    }
}
EOF
javac -d "$work/pluginjar" "$work/pluginjar/EchoPlugin.java"
printf 'Main-Class: EchoPlugin\n' > "$work/pluginjar/mf"
(cd "$work/pluginjar" && jar cfm plugin.jar mf EchoPlugin.class)

# Same echo, source-launched via JEP 330 instead of packaged -- the `.java` plugin format.
mkdir -p "$work/pluginjava"
cat > "$work/pluginjava/plugin.java" <<'EOF'
public class plugin {
    public static void main(String[] a) {
        for (String k : new String[] {
                "FLIXW_ABI_VERSION", "FLIXW_PROJECT_ROOT", "FLIXW_PLUGIN_NAME", "FLIXW_CONTEXT" }) {
            String v = System.getenv(k);
            System.out.println(k + "=" + (v == null ? "" : v));
        }
        System.out.println("ARGS=" + String.join(",", a));
    }
}
EOF

# The `.flix` format: no CLI arguments reach it (stock Flix has no `run <file>` mode), so
# this is the one format that can only prove itself through Sys.Env.Env -- confirmed
# against a real compiler, not assumed. It runs against the invoking project's own pinned
# compiler, so the fixture is deliberately this small: proving the channel works, not
# exercising Flix itself.
mkdir -p "$work/pluginflix"
cat > "$work/pluginflix/plugin.flix" <<'EOF'
def main(): Unit \ {IO, Sys.Env.Env} =
    match Sys.Env.getVar("FLIXW_PROJECT_ROOT") {
        case Some(v) => println("FLIXW_PROJECT_ROOT=${v}")
        case None    => println("FLIXW_PROJECT_ROOT=")
    }
EOF

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

java "$root/src/assets/flixw-setup.java" setup "$proj" > /dev/null
cp "$root/src/stage0/flixw.java" "$proj/.flixw/flixw.java"

# The suite's scratch tree lives inside this repository, which gitignores it. `validate`
# reports gitignored wrapper files as a failure -- correctly -- so the fixture has to be
# its own work tree, exactly as a real consuming project is.
git init -q "$proj"

cd "$proj"
echo "flixw regression suite  (Flix $version, cache $cache)"
echo

# The global launcher has no compiler policy of its own: from a project it must simply
# enter the checked-in shim, while outside one it must refuse rather than guess.
g 0  'flixw' "global launcher delegates to the project wrapper" "$cache_native/bin/flixw" wrapper --version
g 87 'no checked-in flixw wrapper' "global launcher refuses outside a project" \
  sh -c 'cd "$1" && "$2" wrapper --version' sh "$work" "$cache_native/bin/flixw"

# --- lock lifecycle --------------------------------------------------------
echo "lock lifecycle"
g 81 'no .*lock.toml'      "no lock blocks the compiler"        ./flixw check
t 0  "pin creates the lock"                                     ./flixw pin "$version"
t 0  "lock is reused"                                           ./flixw wrapper --version

# pin now takes an optional owner/repository. These need no network: the repository is
# rejected, or the arguments are, before any request is made.
t 81 "pin rejects a malformed repository"                       ./flixw pin not/a/repo "$version"
t 88 "pin rejects two repositories"                             ./flixw pin a/b c/d "$version"
t 88 "pin rejects two versions"                                 ./flixw pin 0.75.1 "$version"
# The source is recorded so a bare re-pin cannot silently move the project elsewhere.
t 0  "pin records the repository it fetched from"               sh -c '
  grep -q "^repo    = \"flix/flix\"" .flixw/lock.toml'
t 0  "a bare re-pin keeps the recorded repository"              sh -c '
  ./flixw pin '"$version"' >/dev/null 2>&1
  grep -q "^repo    = \"flix/flix\"" .flixw/lock.toml'

# --- dispatch --------------------------------------------------------------
echo "dispatch"
t 0  "rule 1  -- pass-through"                                  ./flixw -- --version
t 0  "rule 2  wrapper --version"                                ./flixw wrapper --version
t 0  "rule 2  wrapper --help"                                   ./flixw wrapper --help
# FLIX_JAR worked for years and was findable only by reading one table row in
# docs/CONTRACT.md. The routing table is where someone looks instead; keep it there.
g 0 'FLIX_JAR' "the routing table names the local-build route"   ./flixw wrapper --help
t 87 "an operation with trailing arguments"                     ./flixw wrapper --help check
t 87 "an unknown wrapper operation"                                  ./flixw wrapper --frobnicate
# The install itself is a ~200MB download from Adoptium and is verified by hand. Argument
# handling is asserted here; the fetch/verify/launch path around it has its own section
# further down, exercised offline against a stand-in provisioner.
t 87 "wrapper --install-jdk takes no arguments"                 ./flixw wrapper --install-jdk temurin
# Stage 0 has no install verb at all now -- the bootstrap is `java flixw-setup.java`,
# which is what somebody downloads and verifies. An unknown operation, not a missing one.
g 87 'unknown operation' "wrapper has no --install"             ./flixw wrapper --install .
# An older instruction says `install`. It must fail loudly rather than quietly setting up a
# directory of that name, which is what a fall-through-to-default verb did.
t 87 "the setup asset rejects a stale verb rather than pathing it" \
  java "$root/src/assets/flixw-setup.java" install .
# The bootstrap moved into flixw's namespace because `install` is a name Flix could claim
# for a project's dependencies, and holding it meant `./flixw install` reached flixw rather
# than the compiler in any project that had not pinned yet.
# 81 is FLIXW002, "no lock" -- the ordinary answer for any verb that needs a compiler in a
# project that has not pinned. The point is that it is *that* answer and not an install:
# flixw used to swallow the word here, so a project asking Flix to install its dependencies
# got the wrapper reinstalling itself instead.
t 81 "install reaches ordinary dispatch, even with no lock"     sh -c '
  d=$1/bare-install; rm -rf "$d"; mkdir -p "$d"
  java "$2/src/assets/flixw-setup.java" setup "$d" >/dev/null 2>&1
  cd "$d" && rm -f .flixw/lock.toml
  ./flixw install' sh "$work" "$root"
t 1  "...and did not quietly reinstall the wrapper"             sh -c '
  d=$1/bare-install
  cd "$d" && ./flixw install 2>&1 | grep -q "installed ./flixw"' sh "$work"
t 0  "rule 3  compiler verb"                                    ./flixw check
t 0  "rule 4  wrapper verb"                                     ./flixw doctor
# Routing used to be announced on every wrapper-handled command, which told the caller
# what they had just typed. It belongs to whoever is debugging dispatch, and nobody else.
t 0  "routing is silent by default"                             sh -c '
  ! ./flixw doctor 2>&1 >/dev/null | grep -q "wrapper 0"'
g 0 'does not implement it' "routing is visible under FLIXW_TRACE"  env FLIXW_TRACE=1 ./flixw doctor
t 1  "rule 5  unknown verb reaches the compiler"                ./flixw frobnicate
# No verb starts the REPL, which reads stdin until EOF. The case must supply that EOF
# itself: inheriting a terminal -- or any stdin that stays open -- hangs the suite forever
# rather than failing it, which is exactly what it did once.
t 0  "no arguments reaches the compiler"                        sh -c './flixw < /dev/null'
t 0  "FLIX_BACKEND=wrapper forces the wrapper"                  env FLIX_BACKEND=wrapper ./flixw validate
t 1  "FLIX_BACKEND=compiler forces the compiler"                env FLIX_BACKEND=compiler ./flixw doctor
# An unrecognised value used to read as unset, which silently restores ordinary dispatch --
# the one outcome someone forcing a side is trying to rule out.
t 87 "an unknown FLIX_BACKEND is rejected, not ignored"         env FLIX_BACKEND=Compiler ./flixw doctor

# `help` and `--help` answer with both halves. The escape hatches must still reach the
# compiler alone, because that is what anyone parsing its output is asking for.
g 0 'repository-local Flix bootstrap' "help merges both halves"  ./flixw help
# `help` used to print the wrapper table and then launch the compiler for its own screen,
# which put two differently-shaped help pages on one screen. It is now one rendered tree; the
# compiler's words verbatim moved to `help flix`, and `-- --help` still reaches the compiler.
g 0 'Usage: flix' "help flix shows the compiler's own words verbatim"  ./flixw help flix
g 0 'repository-local Flix bootstrap' "--help merges them too"    ./flixw --help
g 0 'The Flix Programming Language'   "-- --help is the compiler alone"  ./flixw -- --help
t 0 "-- --help does not merge"                                  sh -c '
  ! ./flixw -- --help 2>&1 | grep -q "repository-local Flix bootstrap"'
t 0 "FLIX_BACKEND=compiler does not merge"                      sh -c '
  ! FLIX_BACKEND=compiler ./flixw --help 2>&1 | grep -q "repository-local"'
t 0 "a flag with arguments is passed through untouched"         sh -c '
  ! ./flixw --help check 2>&1 | grep -q "repository-local"'

# info reports, validate judges, doctor does both. The split matters because doctor used
# to print twelve lines of state, notice nothing, and exit 0 with an edited shim.
t 0  "info reports without judging"                             ./flixw info
g 88 'problem' "doctor catches what info does not"              sh -c '
  cp flixw "$1/flixw.keep"; echo "# tampered" >> flixw
  ./flixw doctor; rc=$?
  cp "$1/flixw.keep" flixw; chmod +x flixw; exit $rc' sh "$work"
t 0  "info is blind to the same tampering"                      sh -c '
  cp flixw "$1/flixw.keep"; echo "# tampered" >> flixw
  ./flixw info >/dev/null 2>&1; rc=$?
  cp "$1/flixw.keep" flixw; chmod +x flixw; exit $rc' sh "$work"
t 0  "doctor --fix repairs what it reports"                     sh -c '
  cp flixw "$1/flixw.keep"; echo "# tampered" >> flixw
  ./flixw doctor --fix >/dev/null 2>&1
  ./flixw doctor >/dev/null 2>&1; rc=$?
  cp "$1/flixw.keep" flixw; chmod +x flixw; exit $rc' sh "$work"
t 87 "doctor rejects an unknown option"                         ./flixw doctor --frobnicate
t 87 "info rejects an unknown option"                           ./flixw info --frobnicate
# An entry with no marker at all is not "unused" -- it is unseen. It is kept, and said so,
# because a first purge on a cache older than the markers otherwise reports freeing nothing
# against gigabytes and reads as broken.
g 0 'never recorded a use of' "purge says what it kept for lack of a record"  sh -c '
  sha=$(printf "e%.0s" $(seq 1 64))
  cp "$(ls "$FLIX_CACHE_HOME"/compilers/flix-*.jar | head -1)" \
     "$FLIX_CACHE_HOME/compilers/flix-8.8.8-$sha.jar"
  rm -f "$FLIX_CACHE_HOME/usage/compiler/$sha.used"
  ./flixw wrapper --purge --yes 2>&1'

t 0  "purge removes an old unpinned compiler and its metadata" sh -c '
  sha=$(printf "d%.0s" $(seq 1 64))
  jar=$(ls "$FLIX_CACHE_HOME"/compilers/flix-*.jar | head -1)
  stale="$FLIX_CACHE_HOME/compilers/flix-9.9.9-$sha.jar"
  cp "$jar" "$stale"
  printf stale > "$FLIX_CACHE_HOME/verbs/$sha.verbs"
  mkdir -p "$FLIX_CACHE_HOME/usage/compiler"
  printf "2020-01-01\n" > "$FLIX_CACHE_HOME/usage/compiler/$sha.used"
  ./flixw wrapper --purge --yes
  test ! -e "$stale" && test ! -e "$FLIX_CACHE_HOME/verbs/$sha.verbs"'

# --- the compiler's own help, kept ------------------------------------------
# `verbs()` runs `flix --help` and parses a verb list out of it. The verb list is lossy, so
# the raw text is kept beside it: `help flix` then costs a file read rather than a second
# compiler launch, and when flixw misreads a layout the unedited text is still there to show.
t 0  "an ordinary run keeps the compiler's help beside the verb record" sh -c '
  sha=$(sed -n "s/^sha256  *= *\"\(.*\)\"/\1/p" .flixw/lock.toml)
  ./flixw check >/dev/null 2>&1
  test -s "$FLIX_CACHE_HOME/verbs/$sha.help" \
    && grep -q "^Usage: flix" "$FLIX_CACHE_HOME/verbs/$sha.help"'

t 0  "the help record carries provenance, not a second copy of the lock" sh -c '
  sha=$(sed -n "s/^sha256  *= *\"\(.*\)\"/\1/p" .flixw/lock.toml)
  m="$FLIX_CACHE_HOME/verbs/$sha.helpmeta"
  grep -q "^reported_version=" "$m" && grep -q "^content_sha256=[0-9a-f]\{64\}$" "$m" \
    && grep -q "^captured_at=[0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}$" "$m" \
    && ! grep -q "^lock_version=" "$m"'

# A cache populated by a flixw that did not keep the help text has a .verbs and no .help.
# Nothing repairs that specially -- the next ordinary command re-captures both.
# Provenance is not optional decoration: a .help with no .helpmeta cannot be checked against
# anything, so the cache hit requires all three records and a missing one re-captures rather
# than being trusted for the rest of the compiler's life in this cache.
t 0  "a help record without provenance is re-captured, not trusted" sh -c '
  sha=$(sed -n "s/^sha256  *= *\"\(.*\)\"/\1/p" .flixw/lock.toml)
  rm -f "$FLIX_CACHE_HOME/verbs/$sha.helpmeta"
  ./flixw check >/dev/null 2>&1
  test -s "$FLIX_CACHE_HOME/verbs/$sha.helpmeta"'

t 0  "a verb record without a help record re-captures on the next run" sh -c '
  sha=$(sed -n "s/^sha256  *= *\"\(.*\)\"/\1/p" .flixw/lock.toml)
  rm -f "$FLIX_CACHE_HOME/verbs/$sha.help" "$FLIX_CACHE_HOME/verbs/$sha.helpmeta"
  ./flixw check >/dev/null 2>&1
  test -s "$FLIX_CACHE_HOME/verbs/$sha.help" && test -s "$FLIX_CACHE_HOME/verbs/$sha.helpmeta"'

# Purge deletes a compiler's sidecars with it; a suffix missing from that list leaves an
# orphan keyed to bytes that are gone, which nothing else would ever collect.
t 0  "purge takes the help sidecars with the compiler"          sh -c '
  sha=$(printf "c%.0s" $(seq 1 64))
  cp "$(ls "$FLIX_CACHE_HOME"/compilers/flix-*.jar | head -1)" \
     "$FLIX_CACHE_HOME/compilers/flix-7.7.7-$sha.jar"
  printf stale > "$FLIX_CACHE_HOME/verbs/$sha.help"
  printf stale > "$FLIX_CACHE_HOME/verbs/$sha.helpmeta"
  mkdir -p "$FLIX_CACHE_HOME/usage/compiler"
  printf "2020-01-01\n" > "$FLIX_CACHE_HOME/usage/compiler/$sha.used"
  ./flixw wrapper --purge --yes >/dev/null 2>&1
  test ! -e "$FLIX_CACHE_HOME/verbs/$sha.help" \
    && test ! -e "$FLIX_CACHE_HOME/verbs/$sha.helpmeta"'
g 0 'cached compilers' "info --verbose lists the cache"         ./flixw info --verbose
g 0 'cached JDKs' "info --verbose lists JDKs too"                ./flixw info --verbose
g 0 '<= pinned' "info --verbose marks the pinned compiler"       ./flixw info --verbose
g 0 'cached compilers' "-v is the short form of --verbose"      ./flixw info -v
t 0  "-v and --verbose print the same thing"                    sh -c '
  [ "$(./flixw info -v)" = "$(./flixw info --verbose)" ]'
g 0 "  $version  " "the cached-compiler line shows the reported version" ./flixw info -v
# The record `verbs()` writes is read-only from a listing that must stay a file read, so an
# entry no run has ever captured a version for still gets a usable line instead of a blank --
# and, since the canonical name alone does not tell two such entries apart, its digest.
t 0  "a cached compiler with no captured version falls back to its canonical name plus a digest" sh -c '
  fake=$(printf "b%.0s" $(seq 1 64))
  short=$(printf "b%.0s" $(seq 1 12))
  jar=$(ls "$FLIX_CACHE_HOME"/compilers/flix-*.jar | head -1)
  cp "$jar" "$FLIX_CACHE_HOME/compilers/flix-9.9.9-$fake.jar"
  out=$(./flixw info -v)
  rm -f "$FLIX_CACHE_HOME/compilers/flix-9.9.9-$fake.jar"
  printf "%s\n" "$out" | grep -Fq "  9.9.9  " \
    && printf "%s\n" "$out" | grep -Fq "(sha $short...)"'
# A fork the compiler itself does not tag with the build a project pinned is exactly the
# case that motivated showing a version at all: the lock still has it exactly, so the
# pinned line must show the full pin even when "reported" above it says less.
g 0 "  $version+test.metadata  " "the pinned line shows the lock's exact version even when the compiler omits its build metadata" sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^version = .*/version = \"'"$version"'+test.metadata\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw info -v
  rc=$?
  cp "$1/lock.keep" .flixw/lock.toml
  exit $rc' sh "$work"
g 0 'system JDKs' "info --verbose also lists JDKs it did not install"  ./flixw info --verbose
# The pin record survives the project that wrote it: a build another project on this
# machine pinned, and this one never ran, still shows its exact tag and fork repo rather
# than falling back to the canonical name a bare directory listing would only ever give.
t 0  "a compiler another project pinned still shows its tag and fork repo" sh -c '
  fake=$(printf "e%.0s" $(seq 1 64))
  jar=$(ls "$FLIX_CACHE_HOME"/compilers/flix-*.jar | head -1)
  cp "$jar" "$FLIX_CACHE_HOME/compilers/flix-9.9.9-$fake.jar"
  printf "wstein/flix-fork\n9.9.9+stable.names.7\n" > "$FLIX_CACHE_HOME/verbs/$fake.pin"
  out=$(./flixw info -v)
  rm -f "$FLIX_CACHE_HOME/compilers/flix-9.9.9-$fake.jar" "$FLIX_CACHE_HOME/verbs/$fake.pin"
  printf "%s\n" "$out" | grep -Fq "  9.9.9+stable.names.7  " \
    && printf "%s\n" "$out" | grep -Fq "(wstein/flix-fork)"'
t 0  "an upstream cached compiler carries no fork annotation" sh -c '
  ! ./flixw info -v | grep -Eq "^  '"$version"' .*\(flix/flix\)"'
# The bug this guards: `pin A; pin B` with nothing else run in between must not lose A's
# record. It used to -- only `acquire()` wrote it, and `pin` alone never reaches `acquire`,
# so pinning straight through a run of fork builds left every one but the last as a bare
# digest forever. The fix writes the record from `pin` itself; verify without ever calling
# a command that would paper over the bug by re-running `acquire` for us.
t 0  "pin alone -- with no other command run against it -- records its own digest" sh -c '
  d=$1/pin-record-only; rm -rf "$d"; mkdir -p "$d"
  cd "$d" || exit 1
  java "$2/src/assets/flixw-setup.java" setup . >/dev/null 2>&1
  cp "$3/flix.toml" flix.toml
  cache="$d/.cache"
  FLIX_CACHE_HOME="$cache" ./flixw pin '"$version"' >/dev/null 2>&1
  sha=$(sed -n "s/^sha256  *= *\"\\(.*\\)\"/\\1/p" .flixw/lock.toml)
  [ -n "$sha" ] && [ -f "$cache/verbs/$sha.pin" ] \
    && grep -Fq "flix/flix" "$cache/verbs/$sha.pin" \
    && grep -Fq "'"$version"'" "$cache/verbs/$sha.pin"' sh "$work" "$root" "$proj"

# --- version grammar -------------------------------------------------------
echo "version grammar"
t 81 "reject 0.75"                                              ./flixw pin 0.75
# GitHub shows the tag, not the version: the releases page, the tag list and every asset
# URL read v0.75.2, so copying from where the versions actually are yields the tag. flixw
# builds "v" + version to construct that URL itself, so it already holds that the two name
# one release -- and refusing the spelling it prints made the user normalize by hand.
t 0  "accept the release tag spelling"                          sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  # The version line is moved away first: asserting the lock alone would pass on a
  # *refused* pin, since the version under test is the one the lock already holds. Done
  # with sed rather than a second pin, which would download and leave the compiler cache
  # holding two entries -- enough to break the truncation case further down.
  sed "s/^version = .*/version = \"0.0.0\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw pin "v'"$version"'" >/dev/null 2>&1
  rc=$?
  # The lock records the version, not the tag, so one release cannot yield two locks.
  [ "$rc" = 0 ] && grep -q "^version = \"'"$version"'\"$" .flixw/lock.toml
  rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
t 0  "the tag spelling is silent"                               sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  out=$(./flixw pin "v'"$version"'" 2>&1)
  cp "$1/lock.keep" .flixw/lock.toml
  # "pinned Flix ..." is the ordinary confirmation; anything about a leading v is not.
  printf "%s" "$out" | grep -qi "leading\|strip\|tag" && exit 1
  exit 0' sh "$work"
# owner/repo@version as one token -- the shape npm and Go modules train people to reach
# for -- reaches the same lock as the existing two-token form.
t 0  "owner/repo@version pins the same as two tokens"           sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  ./flixw pin "flix/flix@'"$version"'" >/dev/null 2>&1
  rc=$?
  [ "$rc" = 0 ] && grep -q "^version = \"'"$version"'\"$" .flixw/lock.toml \
                && grep -q "^repo    = \"flix/flix\"$" .flixw/lock.toml
  rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
t 81 "owner/repo@ with nothing after '@' is rejected"           ./flixw pin 'flix/flix@'
g 88 "two versions" "owner/repo@version plus a second version is two versions" sh -c '
  ./flixw pin "flix/flix@'"$version"'" "'"$version"'"'

# The prefix is taken only ahead of a digit, so these stay bad versions rather than
# becoming the versions `Next` and `v0.75.2`.
t 81 "reject a bare v"                                          ./flixw pin v
t 81 "reject a v that leaves no version"                        ./flixw pin vNext
t 81 "reject a doubled v"                                       ./flixw pin "vv$version"
t 81 "reject wildcard"                                          ./flixw pin '0.75.*'
t 81 "reject range"                                             ./flixw pin '>=0.75.0'
t 81 "reject traversal"                                         ./flixw pin '../../etc'
t 81 "reject empty prerelease suffix"                           ./flixw pin '0.75.2-'

# Build metadata is accepted in the manifest and stripped from the release tag and the
# cache coordinate. If canonical() were applied inconsistently this would either fail to
# resolve or produce a drift error that pin cannot repair.
# pin never touches flix.toml: that key is Flix's, with Flix's rules, and Flix rejects
# anything but x.x.x there. The exact version lives in the lock, which is flixw's file.
t 0  "pin leaves flix.toml alone"                               sh -c '
  cp flix.toml "$1/toml.before"
  ./flixw pin '"$version"'+build.4 >/dev/null 2>&1
  cmp -s flix.toml "$1/toml.before"' sh "$work"
t 0  "the exact version lands in the lock"                      sh -c '
  grep -q "^version = \"'"$version"'+build.4\"" .flixw/lock.toml'
# pin below the manifest floor is allowed -- lowering the floor may be the plan, and pin
# has to stay usable in a broken state -- but it must say so when it happens rather than
# leaving it to be discovered by the next command that needs a compiler.
g 0 'will not run until' "pin below the floor warns at pin time"  sh -c '
  cp flix.toml "$1/toml.keep"
  sed "s/^flix .*/flix        = \"0.99.0\"/" "$1/toml.keep" > flix.toml
  ./flixw pin '"$version"' 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"

# A manifest floor below the pinned compiler is the normal case, not drift.
t 0  "a lower floor in flix.toml is satisfied, not drift"       sh -c '
  cp flix.toml "$1/toml.keep"
  sed "s/^flix .*/flix        = \"0.70.0\"/" "$1/toml.keep" > flix.toml
  ./flixw check >/dev/null 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
t 0  "accept and strip build metadata"                          ./flixw pin "$version+build.4"
g 0  "$version"  "stripped pin still resolves"                  ./flixw wrapper --help
./flixw pin "$version" > /dev/null 2>&1

# --- manifest reading ------------------------------------------------------
# A regex over the whole file reads `flix` out of any table, or out of the body of a
# multi-line string. These cases pin the table-aware behaviour.
echo "manifest reading"
cp flix.toml "$work/flix.toml.good"
t 0  "a decoy flix key in another table is ignored"              sh -c '
  { cat "$1/flix.toml.good"; printf "\n[other]\nflix = \"9.9.9\"\n"; } > flix.toml
  ./flixw -- --version' sh "$work"
t 0  "a decoy inside a multi-line string is ignored"             sh -c '
  { printf "[package]\nname = \"x\"\nversion = \"0.1.0\"\n"; \
    printf "description = \"\"\"\nflix = \"9.9.9\"\n\"\"\"\n"; \
    grep "^flix" "$1/flix.toml.good"; printf "authors = [\"n\"]\n"; } > flix.toml
  ./flixw -- --version' sh "$work"
t 0  "a trailing comment on the version is ignored"              sh -c '
  sed "s/^flix .*/flix        = \"'"$version"'\"  # pinned/" "$1/flix.toml.good" > flix.toml
  ./flixw -- --version' sh "$work"
# The one command documented as the repair has to work in the state it repairs. A lock
# that does not parse used to throw before routing ever reached pin.
t 0  "pin repairs a lock that does not parse"                    sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^sha256.*/sha256  = \"not-a-digest\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw pin '"$version"' >/dev/null 2>&1
  # The digest, not merely the key: this case once passed against the corrupted line it
  # was supposed to have replaced, and every test after it inherited a broken lock.
  grep -qE "^sha256  = \"[0-9a-f]{64}\"" .flixw/lock.toml' sh "$work"
t 81 "a lock that does not parse still blocks the compiler"      sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^sha256.*/sha256  = \"not-a-digest\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw check; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"

# A manifest that does not parse must not take the repair verbs down with it -- the same
# trap as an unparseable lock, one file over.
# Reaching the verb at all is the point: 88 means doctor ran and judged, where 81
# would mean it never got there. It reports the broken manifest rather than
# exiting 0 over it, which is the whole reason doctor now judges.
g 88 'flix.toml' "doctor runs on a manifest that does not parse"  sh -c '
  cp flix.toml "$1/toml.keep"
  printf "\n[package]\nflix = \"9.9.9\"\n" >> flix.toml
  ./flixw doctor; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
t 88 "validate reports a manifest that does not parse"           sh -c '
  cp flix.toml "$1/toml.keep"
  printf "\n[package]\nflix = \"9.9.9\"\n" >> flix.toml
  ./flixw validate >/dev/null 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
t 81 "a manifest that does not parse still blocks the compiler"  sh -c '
  cp flix.toml "$1/toml.keep"
  printf "\n[package]\nflix = \"9.9.9\"\n" >> flix.toml
  ./flixw check; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"

t 81 "a duplicate [package] table is ambiguous"                  sh -c '
  { cat "$1/flix.toml.good"; printf "\n[package]\nflix = \"9.9.9\"\n"; } > flix.toml
  ./flixw -- --version' sh "$work"
t 81 "an unquoted version is refused"                            sh -c '
  sed "s/^flix .*/flix        = '"$version"'/" "$1/flix.toml.good" > flix.toml
  ./flixw -- --version' sh "$work"
t 81 "an unreadable manifest is not treated as absent"           sh -c '
  chmod 000 flix.toml
  ./flixw -- --version; rc=$?
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
  ./flixw doctor >/dev/null 2>&1; rc=$?
  cp "$1/toml.keep" flix.toml; exit $rc' sh "$work"
cp "$work/flix.toml.good" flix.toml

# --- the java pin ----------------------------------------------------------
echo "java pin"
# The lock pins which Java runs the compiler, in the same file and for the same reason as
# the compiler itself. It is a version, not a path: a path is true on one machine.
# The pin is the running JVM's own feature version, not a constant: a case that pins 21
# while the runner is on 26 is testing the conflict diagnostic, not the pin.
jfeature=$(java -version 2>&1 | sed -n 's/^[A-Za-z ]*version "\([0-9][0-9]*\).*/\1/p' | head -1)
t 0  "pin --java writes the java table"                         sh -c '
  ./flixw pin --java '"$jfeature"' >/dev/null 2>&1 || exit 1
  grep -q "^\[java\]" .flixw/lock.toml && grep -q "^version = \"'"$jfeature"'\"" .flixw/lock.toml'
t 0  "a java pin does not disturb the compiler pin"             sh -c '
  grep -q "^version = \"'"$version"'\"" .flixw/lock.toml'
t 0  "the compiler still runs under a satisfied pin"            ./flixw -- --version
g 0  'java 2[0-9]' "doctor reports the satisfied pin"           ./flixw doctor
# Re-pinning the compiler must not quietly unpin the Java.
t 0  "repinning the compiler keeps the java pin"                sh -c '
  ./flixw pin '"$version"' >/dev/null 2>&1 || exit 1
  grep -q "^version = \"'"$jfeature"'\"" .flixw/lock.toml'
t 0  "pin --java none removes it"                               sh -c '
  ./flixw pin --java none >/dev/null 2>&1 || exit 1
  ! grep -q "^\[java\]" .flixw/lock.toml'
# A pin below the floor is a contradiction: the compiler cannot run there at all, so it is
# refused where it is written rather than at every run afterwards.
t 81 "a java pin below the floor is refused"                    ./flixw pin --java 17
t 81 "a java pin that is not a number is refused"               ./flixw pin --java latest
t 87 "an unknown pin option is refused"                         ./flixw pin --jaba 21
# A repository with no version parsed and was then dropped: a --java-only pin rewrites one
# line and never re-resolves the compiler, so there was nowhere for it to go.
t 81 "a repository without a version is refused"                ./flixw pin wstein/flix-fork --java 21
# Writing a pin for a JDK this machine does not have is allowed -- CI may have it, and
# --install-jdk can fetch it -- but it must be said now, not discovered by the next
# command failing on a missing JDK.
g 0 'no Java 99 on this machine' "pin warns when the pinned Java is absent" sh -c '
  ./flixw pin --java 99 2>&1; rc=$?
  ./flixw pin --java none >/dev/null 2>&1; exit $rc'
# The instructions and the offer must name the Java the pin asked for. They named the
# wrapper floor instead, so a project pinning 22 was told to install 21 and offered a
# download of 21 -- which, if accepted, fetched 22 anyway.
g 82 'Temurin 99' "the offer names the pinned Java, not the floor" sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "\n[java]\nversion = \"99\"\n" >> .flixw/lock.toml
  env -u JAVA_HOME -u FLIX_JAVA_HOME ./flixw -- --version < /dev/null 2>&1; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# A lock asking for a Java this machine does not have stops before any compiler work.
# JAVA_HOME is unset deliberately: with it set this takes the explicit-JDK branch below
# and reports the conflict there instead, which is a different diagnostic and a different
# exit. Leaving it to the environment made the expected code depend on the runner.
g 82 'no Java 99' "an unsatisfiable java pin fails, saying so"   env -u JAVA_HOME -u FLIX_JAVA_HOME sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "\n[java]\nversion = \"99\"\n" >> .flixw/lock.toml
  ./flixw -- --version; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# An explicitly named JDK is still obeyed rather than replaced -- but not silently against
# a pin the project committed.
g 83 'lock.toml pins java' "an explicit JDK against the pin is refused" sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "\n[java]\nversion = \"99\"\n" >> .flixw/lock.toml
  JAVA_HOME=$2 ./flixw -- --version; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work" "$(dirname "$(dirname "$realjava")")"

# --- the resolved-JDK note ------------------------------------------------
echo "resolved-JDK note"
# Stage 0 leaves the shim a note naming the JDK this project resolved to, so the next run
# starts on it rather than starting on PATH and relaunching. It is machine-specific, so it
# is git-ignored rather than committed, and it is only ever an optimisation: every failure
# to read, write or use it must land back on the old behaviour.
t 0  "a run records the JDK it selected"                        sh -c '
  ./flixw -- --version >/dev/null 2>&1
  test -x "$(cat .flixw/local/java)"'
t 0  ".flixw/.gitignore keeps the note out of git"              sh -c '
  grep -q "^local/$" .flixw/.gitignore
  git check-ignore -q .flixw/local/java'
if [ -n "$realjava" ]; then
  # The shim must actually start on the note. A stand-in that records being run proves it
  # without needing a second real JDK on the machine.
  mkdir -p "$work/notedjdk/bin"
  printf '#!/bin/sh\nprintf x >> "%s/noted.log"\nexec %s "$@"\n' "$work" "$realjava" \
    > "$work/notedjdk/bin/java"
  chmod +x "$work/notedjdk/bin/java"
  t 0  "the shim starts on the noted JDK"                       sh -c '
    cp .flixw/local/java "$1/note.keep"
    printf "%s\n" "$1/notedjdk/bin/java" > .flixw/local/java
    : > "$1/noted.log"
    env -u JAVA_HOME -u FLIX_JAVA_HOME ./flixw wrapper --version >/dev/null 2>&1
    rc=1; [ -s "$1/noted.log" ] && rc=0
    cp "$1/note.keep" .flixw/local/java; exit $rc' sh "$work"
  # An explicitly named JDK still outranks it: the note records what flixw worked out,
  # not what the caller asked for.
  t 0  "an explicit JAVA_HOME outranks the note"                sh -c '
    cp .flixw/local/java "$1/note.keep"
    printf "%s\n" "$1/notedjdk/bin/java" > .flixw/local/java
    : > "$1/noted.log"
    JAVA_HOME=$2 ./flixw wrapper --version >/dev/null 2>&1
    rc=0; [ -s "$1/noted.log" ] && rc=1
    cp "$1/note.keep" .flixw/local/java; exit $rc' sh "$work" "$(dirname "$(dirname "$realjava")")"
else
  s "the shim starts on the noted JDK"                          "no real java to stand in for"
  s "an explicit JAVA_HOME outranks the note"                   "no real java to stand in for"
fi
# Shape is checked before the note is used: stage 0 writes a normalized absolute path
# ending in bin/java, so anything else was not written by this wrapper. Cheap sanity
# rather than a boundary -- whoever can write the note can edit the shim -- but a note is
# not where anyone should discover they are running something else.
if [ -n "$realjava" ]; then
  t 0 "a note of the wrong shape is ignored"                    sh -c '
    cp .flixw/local/java "$1/note.keep"
    : > "$1/noted.log"
    printf "%s\n" "$(command -v cat)" > .flixw/local/java
    env -u JAVA_HOME -u FLIX_JAVA_HOME ./flixw wrapper --version >/dev/null 2>&1; rc=$?
    cp "$1/note.keep" .flixw/local/java; exit $rc' sh "$work"
  t 0 "a note that walks out with .. is ignored"                sh -c '
    cp .flixw/local/java "$1/note.keep"
    printf "%s/../../bin/java\n" "$1/notedjdk" > .flixw/local/java
    env -u JAVA_HOME -u FLIX_JAVA_HOME ./flixw wrapper --version >/dev/null 2>&1; rc=$?
    cp "$1/note.keep" .flixw/local/java; exit $rc' sh "$work"
else
  s "a note of the wrong shape is ignored"                      "no real java to stand in for"
  s "a note that walks out with .. is ignored"                  "no real java to stand in for"
fi
# A note naming something that is gone is not an error, it is a cache miss -- and the next
# run through the compiler path rewrites it.
t 0  "a stale note falls back, then heals"                      sh -c '
  printf "/nope/nowhere/java\n" > .flixw/local/java
  env -u JAVA_HOME -u FLIX_JAVA_HOME ./flixw -- --version >/dev/null 2>&1 || exit 1
  test -x "$(cat .flixw/local/java)"'

# --- lock validation -------------------------------------------------------
echo "lock validation"
t 81 "a non-https url in the lock is refused"                    sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s|^url .*|url = \"http://example.invalid/flix.jar\"|" "$1/lock.keep" > .flixw/lock.toml
  ./flixw -- --version; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
t 81 "a malformed url in the lock is a diagnostic, not a crash"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s|^url .*|url = \"https://\"|" "$1/lock.keep" > .flixw/lock.toml
  ./flixw -- --version; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# The diagnostic has to name the key and say what it is for, not quote the pattern back:
# the person reading it is repairing a generated file by hand.
g 81 'expected the SHA-256 of that JAR' "a bad digest names the key and what it holds"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^sha256.*/sha256  = \"nope\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw -- --version; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"

# --- the lock schema -------------------------------------------------------
# The schema is rendered from the list stage 0 validates against, so the two cannot
# disagree; what the shell can still show is that the render is reachable offline and that
# the file every generated lock points an editor at is the one the wrapper serves.
echo "lock schema"
g 0 '"\$id": "https://wstein.github.io/flixw/schema/lock-v1.schema.json"' \
     "wrapper --schema renders the published schema"          ./flixw wrapper --schema
t 87 "wrapper --schema takes no arguments"                     ./flixw wrapper --schema v1
# Offline and project-free, like --version: a build validating locks should be able to
# carry its own copy of the schema rather than reaching the network for one.
t 0  "wrapper --schema needs no project"                       sh -c '
  cd "$1" && java "$2" wrapper --schema | grep -q json-schema.org' sh "$work" "$root/src/stage0/flixw.java"
t 0  "every generated lock names the schema"                    sh -c '
  head -1 .flixw/lock.toml | grep -q "^#:schema https://wstein.github.io/flixw/schema/lock-v1.schema.json$"'
t 0  "validate reports the lock as conforming and named"        sh -c '
  ./flixw validate | grep -q "^ok    the lock conforms to, and names, the v1 schema$"'

# An unknown key is advisory in both directions: it is said out loud, and it never stops
# the run. A lock is committed, so the ordinary way to meet one is a collaborator whose
# flixw is newer than yours.
g 0 'FLIXW011.*mirror' "an unknown key is reported and ignored" sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "mirror  = \"https://mirror.example.invalid\"\n" >> .flixw/lock.toml
  ./flixw wrapper --version >/dev/null; ./flixw validate >/dev/null; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# ...and doctor --fix must not "repair" it away: the rewrite is from the values it read,
# so a key it did not read would be deleted by the command that just said it was ignored.
t 0 "doctor --fix leaves a lock with an unknown key alone"      sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "mirror  = \"https://mirror.example.invalid\"\n" >> .flixw/lock.toml
  ./flixw doctor --fix >/dev/null 2>&1
  grep -q "^mirror" .flixw/lock.toml; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# A lock written before this release has no #:schema line and no offline way to get one,
# because pin re-downloads the compiler to write the file. Two commands are that way, and
# they are the same rewrite: doctor --fix as one repair among several, pin --refresh on its
# own. Both are asserted, because a shared implementation is not a shared code path.
t 0 "doctor --fix adds a missing #:schema line"                 sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  grep -v "^#:schema" "$1/lock.keep" > .flixw/lock.toml
  ./flixw validate | grep -q "^warn  the lock conforms to the v1 schema but does not name it"
  ./flixw doctor --fix >/dev/null 2>&1
  head -1 .flixw/lock.toml | grep -q "^#:schema "; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
g 0 'rewrote' "pin --refresh adds a missing #:schema line"      sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  grep -v "^#:schema" "$1/lock.keep" > .flixw/lock.toml
  ./flixw pin --refresh
  head -1 .flixw/lock.toml | grep -q "^#:schema " || exit 9
  # The pin itself must not have moved: same repository, version, URL and digest.
  grep -v "^#:schema\|^wrapperVersion" .flixw/lock.toml > "$1/after"
  grep -v "^#:schema\|^wrapperVersion" "$1/lock.keep" > "$1/before"
  diff "$1/before" "$1/after"; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# Offline is the point: it is what pin <version> cannot be. FLIX_DIST_URL pointed at a
# host that does not resolve would fail any command that reaches the network.
t 0 "pin --refresh reaches no network"                          sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  grep -v "^#:schema" "$1/lock.keep" > .flixw/lock.toml
  FLIX_DIST_URL=https://dist.invalid ./flixw pin --refresh; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# Asked for explicitly, a refusal has to be said out loud: a command that does nothing and
# prints nothing reads as one that worked.
g 0 'already what flixw' "pin --refresh says when there is nothing to do"  ./flixw pin --refresh
g 0 'does not read' "pin --refresh declines a lock with an unknown key"    sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "mirror  = \"https://mirror.example.invalid\"\n" >> .flixw/lock.toml
  ./flixw pin --refresh 2>&1
  grep -q "^mirror" .flixw/lock.toml; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
t 81 "pin --refresh needs a lock that parses"                   sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^sha256.*/sha256  = \"not-a-digest\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw pin --refresh; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# It rewrites the lock from the lock; anything else on the line is a different request.
t 87 "pin --refresh takes no version"                           ./flixw pin --refresh "$version"
t 87 "pin --refresh takes no repository"                        ./flixw pin --refresh flix/flix
t 87 "pin --refresh takes no --java"                            ./flixw pin --refresh --java 21
# A lock written by a newer flixw is not this release's to reshape, by either route.
t 0 "doctor --fix leaves a newer wrapper's lock alone"          sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^wrapperVersion.*/wrapperVersion = \"99.0.0\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw doctor --fix >/dev/null 2>&1
  grep -q "^wrapperVersion = \"99.0.0\"$" .flixw/lock.toml; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
g 0 'newer than this one' "pin --refresh leaves a newer wrapper's lock alone"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^wrapperVersion.*/wrapperVersion = \"99.0.0\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw pin --refresh 2>&1
  grep -q "^wrapperVersion = \"99.0.0\"$" .flixw/lock.toml; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"

# --- completion ------------------------------------------------------------
# UnitCheck asserts what the emitted scripts say; only a real shell can say whether they
# parse and complete. A completion script is the one thing flixw emits that runs somewhere
# else entirely -- in a shell startup, days later, with no channel to report a FLIXWnnn --
# so a quoting slip in a text block has to fail here or it fails in someone's terminal.
echo "completion"
# The generator is fetched on first use rather than embedded, so every case below needs a
# source to fetch it from -- and must not depend on a real GitHub release existing for
# whatever WRAPPER_VERSION this checkout happens to report (this feature's own first
# release is the first one that could ever carry the asset -- the same chicken-and-egg
# `wrapper --upgrade`'s own test, further down, already works around). A minimal local
# fixture stands in: just the generator and a SHA256SUMS naming it, not the full tar/zip
# tests/pack.sh also builds, which nothing here needs.
# The suite-wide release fixture built at the top serves these too: one SHA256SUMS names
# every companion asset, which is what ensureAsset looks a wanted name up in.
complfixture=$relfixture

t 87 "completion needs a shell"                      ./flixw completion
t 87 "completion rejects an unknown shell"           ./flixw completion csh
t 87 "completion takes one shell only"               ./flixw completion bash zsh
# Project-free, like --schema: a dotfiles repository generating completers has no lock and
# no compiler. Not offline, unlike --schema -- see the fetch/cache/verify cases below --
# but this one call is a cache hit already by the time it runs, from the shell already
# exercised at the top of this section.

# A stand-in project carrying only the note, so the completers can be driven against a
# known verb set rather than against whatever this suite's own project has resolved.
mkdir -p "$work/complproj/.flixw/local"
printf 'build\ncheck\ndoctor\nrun\n' > "$work/complproj/.flixw/local/verbs"
: > "$work/complproj/flixw"

./flixw completion bash > "$work/_flixw.bash"
./flixw completion zsh  > "$work/_flixw.zsh"
t 0  "the bash completer parses"                               bash -n "$work/_flixw.bash"
if command -v zsh >/dev/null 2>&1; then
  t 0  "the zsh completer parses"                              zsh -n "$work/_flixw.zsh"
else
  s "the zsh completer parses"                                 "no zsh on this machine"
fi
# fish is the one completer that can be driven without simulating readline: `complete -C`
# asks it for the candidates of a command line, which is exactly what a keypress asks.
./flixw completion fish > "$work/flixw.fish"
if command -v fish >/dev/null 2>&1; then
  # --no-config, because a developer's own fish config is not part of what is under test
  # and a broken line in it would fail this suite for the wrong reason.  Unlike bash -c,
  # fish -c takes no $0 placeholder: the first extra word is already $argv[1].
  t 0  "the fish completer parses"                             fish -n "$work/flixw.fish"
else
  s "the fish completer parses"                                "no fish on this machine"
fi

# The generated scripts bake in the command tree, so there is no note to drive them through
# any more. What replaced these cases is at the end of this section: the scripts are loaded
# by real bash and real fish and asked for candidates, which is the same question a keypress
# asks and a stronger one than reading the text of a template.

# Stage 0 leaves the note itself on any run that resolves a compiler.

# The generator itself: fetched once per machine per release, verified against the
# fixture's SHA256SUMS the same way --upgrade verifies flixw.java, and cached from there.
t 0  "a cold cache fetches and verifies the generator"          sh -c '
  rm -rf "$1/wrapper"
  ./flixw completion bash >/dev/null 2>&1 || exit 1
  find "$1/wrapper/assets" -name "flixw-help.java.sha256" | grep -q .' sh "$cache"
t 0  "a warm cache needs no source at all"                      sh -c '
  FLIXW_ASSET_SOURCE="file:///nonexistent/" ./flixw completion bash \
    | grep -q "_complete_flixw"'
g 85 'digest mismatch' "a tampered generator is refused before it is cached"  sh -c '
  rm -rf "$1/wrapper"
  bad=$2/badfixture; rm -rf "$bad"; mkdir -p "$bad"
  cp "$3/flixw-help.java" "$bad/"
  cp "$3"/picocli-*.jar "$bad/" 2>/dev/null || true
  if command -v sha256sum >/dev/null 2>&1; then (cd "$bad" && sha256sum flixw-help.java picocli-*.jar > SHA256SUMS)
  else (cd "$bad" && shasum -a 256 flixw-help.java picocli-*.jar > SHA256SUMS); fi
  printf "\n// tampered\n" >> "$bad/flixw-help.java"
  if command -v cygpath >/dev/null 2>&1; then u="file:///$(cygpath -m "$bad")"
  else u="file://$bad"; fi
  FLIXW_ASSET_SOURCE="$u/" ./flixw completion bash' \
  sh "$cache" "$work" "$complfixture"
t 0  "...and nothing was cached"                                sh -c '
  ! find "$1/wrapper/assets" -name flixw-help.java 2>/dev/null | grep -q .' sh "$cache"
g 84 'cannot reach' "no network on a cold cache fails with a clear diagnostic"  sh -c '
  rm -rf "$1/wrapper"
  FLIXW_ASSET_SOURCE=https://dist.invalid ./flixw completion bash' sh "$cache"
empty=$work/emptycomplfixture
rm -rf "$empty" && mkdir -p "$empty"
: > "$empty/SHA256SUMS"
g 84 'no published flixw' "SHA256SUMS silent on the asset names the specific problem" sh -c '
  rm -rf "$1/wrapper"
  FLIXW_ASSET_SOURCE="$2/" ./flixw completion bash' sh "$cache" "$(fileurl "$empty")"
# Restore a warm, valid cache: later sections in this suite share $cache, and leaving it
# in whatever failure state the last negative case above left it in would be a trap for
# the next person adding a case here, not a property this suite promises to anyone else.
./flixw completion bash >/dev/null 2>&1

# ---- the JDK provisioner asset ------------------------------------------------------
# Stage 0 no longer provisions: `wrapper --install-jdk` fetches src/assets/flixw-jdk.java on the
# same footing as the completion generator and runs it. Everything below stops at or
# before that fetch, because the step after it is a ~200MB download from Adoptium -- so
# what is asserted here is the trust boundary, not the install, which is verified by hand.
g 85 'digest mismatch' "a tampered provisioner is refused before it is cached"  sh -c '
  rm -rf "$1/wrapper"
  bad=$2/badjdkfixture; rm -rf "$bad"; mkdir -p "$bad"
  cp "$3/flixw-help.java" "$3/flixw-jdk.java" "$bad/"
  if command -v sha256sum >/dev/null 2>&1
  then (cd "$bad" && sha256sum flixw-help.java flixw-jdk.java > SHA256SUMS)
  else (cd "$bad" && shasum -a 256 flixw-help.java flixw-jdk.java > SHA256SUMS); fi
  printf "\n// tampered\n" >> "$bad/flixw-jdk.java"
  if command -v cygpath >/dev/null 2>&1; then u="file:///$(cygpath -m "$bad")"
  else u="file://$bad"; fi
  FLIXW_ASSET_SOURCE="$u/" ./flixw wrapper --install-jdk' \
  sh "$cache" "$work" "$complfixture"
t 0  "...and the provisioner was not cached"                    sh -c '
  ! find "$1/wrapper/assets" -name flixw-jdk.java 2>/dev/null | grep -q .' sh "$cache"
g 84 'cannot reach' "no network on a cold cache names the source, not Adoptium"  sh -c '
  rm -rf "$1/wrapper"
  FLIXW_ASSET_SOURCE=https://dist.invalid ./flixw wrapper --install-jdk' sh "$cache"
# Both assets share one SHA256SUMS, so the "not published" branch has to name the one
# actually asked for rather than whichever is checked first.
g 84 'flixw-jdk.java' "SHA256SUMS silent on the provisioner names it specifically" sh -c '
  rm -rf "$1/wrapper"
  FLIXW_ASSET_SOURCE="$2/" ./flixw wrapper --install-jdk' sh "$cache" "$(fileurl "$empty")"

# The asset stands alone -- it is a program, launched by a JVM that may predate this one --
# so its own argument handling is asserted directly, with no stage 0 and no network.
t 87 "the provisioner rejects a wrong argument count"           java "$root/src/assets/flixw-jdk.java"
t 87 "the provisioner rejects a non-numeric feature release"    java "$root/src/assets/flixw-jdk.java" x "$work" "$work/out"

# The whole of runJdkAsset -- fetch, verify, cache, load, read the path it recorded, probe it
# -- with a stand-in provisioner that names the JVM already running. The real one would
# download ~200MB from Adoptium at this point; the digest is the fixture's own, so the trust
# path is exercised exactly as in production, only the payload differs.
#
# The stub implements `run`, not `main`: stage 0 loads assets in its own JVM and calls that.
# It writes the java it "installed" to the file named by the third argument rather than to
# stdout, because in-process stdout is the user's terminal and shared with everything else
# printed there.
stub=$work/jdkstub
rm -rf "$stub" && mkdir -p "$stub"
cp "$root/src/assets/flixw-help.java" "$stub/"
cat > "$stub/flixw-jdk.java" <<'STUB'
final class flixwjdk {
    public static void main(String[] a) throws Exception { System.exit(run(a)); }

    public static int run(String[] a) throws Exception {
        System.err.println("stub: feature=" + a[0]);
        java.nio.file.Files.writeString(java.nio.file.Paths.get(a[2]),
            System.getProperty("java.home") + "/bin/java");
        return 0;
    }
}
STUB
if command -v sha256sum >/dev/null 2>&1
then (cd "$stub" && sha256sum flixw-help.java flixw-jdk.java > SHA256SUMS)
else (cd "$stub" && shasum -a 256 flixw-help.java flixw-jdk.java > SHA256SUMS); fi
g 0 'is installed' "the provisioner is fetched, verified, launched and believed"  sh -c '
  rm -rf "$1/wrapper"
  FLIXW_ASSET_SOURCE="$2/" ./flixw wrapper --install-jdk' sh "$cache" "$(fileurl "$stub")"
t 0  "...and it was cached under the shared asset tree"         sh -c '
  find "$1/wrapper/assets" -name "flixw-jdk.java.sha256" | grep -q .' sh "$cache"

# Stage 0 itself must no longer carry any of it: a grep is the only check that stays true
# after someone "helpfully" restores one of these for a quick fix.
t 1  "stage 0 names no JDK vendor endpoint"                     grep -q "api.adoptium.net" "$root/src/stage0/flixw.java"
t 1  "stage 0 has no archive unpacker"                          grep -q "ZipInputStream" "$root/src/stage0/flixw.java"
t 1  "stage 0 never prompts for a download"                     grep -q "FLIXW_INSTALL_JDK" "$root/src/stage0/flixw.java"

# --- a FLIX_JAR that names flixw's own cache -------------------------------
# The failure this closes: cache names are content-addressed, so a re-pin changes them.
# An override set once to whatever `info` reported that day goes on naming the superseded
# artifact, and the project builds with the compiler it used to pin. Nothing else catches
# it -- the digest guard is switched off by the override, and the version check passes
# because two builds of one release share a canonical version.
echo "FLIX_JAR override"
# The real compiler, copied out of the cache: identical bytes, so only the location differs
# and the case tests the containment rule rather than the contents.
t 0  "a jar built elsewhere is the supported use, and is quiet"  sh -c '
  jar=$(./flixw info 2>/dev/null | awk "/^jar /{print \$2}")
  cp "$jar" "$1/my-build.jar"
  FLIX_JAR="$1/my-build.jar" ./flixw info 2>&1 | grep -q "FLIX_JAR names" && exit 1
  exit 0' sh "$work"
g 0 'NOT the pinned one' "a stale cache entry is named as such"   sh -c '
  jar=$(./flixw info 2>/dev/null | awk "/^jar /{print \$2}")
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^sha256\( *\)=.*/sha256\1= \"$(printf "c%.0s" $(seq 64))\"/" "$1/lock.keep" > .flixw/lock.toml
  FLIX_JAR="$jar" ./flixw info 2>&1
  cp "$1/lock.keep" .flixw/lock.toml' sh "$work"
g 0 'names flixw.s own cache entry for the pinned compiler' \
     "a cache entry matching the lock is still called out"       sh -c '
  jar=$(./flixw info 2>/dev/null | awk "/^jar /{print \$2}")
  FLIX_JAR="$jar" ./flixw info 2>&1'
# The digest of what is actually running, so the reader never has to diff a file name
# against a `digest` line by eye -- which is the diff that was there to be made, and missed.
g 0 'override digest' "info prints the digest of the overridden jar"  sh -c '
  jar=$(./flixw info 2>/dev/null | awk "/^jar /{print \$2}")
  FLIX_JAR="$jar" ./flixw info 2>&1'

# --- the version the compiler reports -------------------------------------
# The digest settles which bytes run; nothing settled that those bytes are the release the
# lock names. A mislabelled asset -- a fork that tagged over an older build, an upstream
# re-upload -- was pinned, verified and run without a word. The compiler's own answer is
# the second opinion, and `pin` now asks for it once and records it in the lock beside the
# digest that vouches for it on every later run.
echo "reported version"
# Recorded at pin time, so it is in the committed lock and a fresh clone inherits the
# answer rather than having to re-derive it.
g 0 '^reported_version = ' "pin records what the jar reports of itself"  sh -c '
  cat .flixw/lock.toml'
# The comparison stayed per-run even though the capture did not: both strings are in the
# lock by the time it is parsed, so it costs a string compare and catches the one case pin
# cannot see -- a lock edited, or merged, after pin wrote it.
g 0 'reports itself as' "an edited reported_version is caught on an ordinary run"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^reported_version = .*/reported_version = \"0.99.0\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw check 2>&1
  cp "$1/lock.keep" .flixw/lock.toml' sh "$work"
# A lock from a flixw that predated the key has no second opinion and could never acquire
# one, since the check moved to pin time and re-pinning means a fresh download. The refresh
# backfills it from the cache -- offline, no network, and only from bytes that still hash
# to what the lock pins.
t 0  "a lock without the key says so rather than claiming agreement"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  grep -v "^reported_version" "$1/lock.keep" > .flixw/lock.toml
  ./flixw validate 2>&1 | grep -q "records no reported_version"; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
t 0  "pin --refresh backfills it from the cached jar, offline"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  grep -v "^reported_version" "$1/lock.keep" > .flixw/lock.toml
  ./flixw pin --refresh >/dev/null 2>&1
  grep -q "^reported_version = " .flixw/lock.toml; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# The run path must not have kept a back door to the compiler for this.
t 1  "no per-run version record is written to the cache"       sh -c '
  find "$FLIX_CACHE_HOME/verbs" -name "*.version" 2>/dev/null | grep -q .'

t 0  "agreement is not worth saying"                           sh -c '
  ./flixw info 2>&1 | grep -q "^reported " && exit 1
  ./flixw validate | grep -q "^ok    the compiler reports the version the lock pins"'
# A lock that misnames its compiler is loud on every run, not only when someone looks.
g 0 'reports itself as' "a misnamed version is reported on an ordinary run"  sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^version = .*/version = \"0.99.0\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw check 2>&1
  cp "$1/lock.keep" .flixw/lock.toml' sh "$work"
# ...and `validate` is what CI runs, so it refuses rather than mentions.
t 88 "a mismatch fails validate"                               sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^version = .*/version = \"0.99.0\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw validate >/dev/null 2>&1; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"
# Build metadata is not a mismatch: it identifies a build, and a compiler reporting the
# release it was built from is agreeing. Said once where state is printed, never per run --
# warning on every run of every fork build teaches the reader to skip the line that matters.
t 0  "build metadata is a note, not a mismatch"                sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  sed "s/^version = \"\(.*\)\"/version = \"\1+demo.1\"/" "$1/lock.keep" > .flixw/lock.toml
  ./flixw check 2>&1 | grep -q FLIXW010 && { cp "$1/lock.keep" .flixw/lock.toml; exit 1; }
  ./flixw validate >/dev/null 2>&1; rc=$?
  ./flixw info 2>&1 | grep -q "build metadata the lock pins" || rc=1
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"

# --- drift -----------------------------------------------------------------
echo "drift"
cp flix.toml "$work/flix.toml.bak"
sed 's/^flix .*/flix        = "0.99.0"/' flix.toml > "$work/drifted" && cp "$work/drifted" flix.toml
g 81 'or newer' "an unsatisfied floor blocks the compiler"              ./flixw check
t 0  "drift does not block wrapper --version"                   ./flixw wrapper --version
g 88 'or newer' "an unsatisfied floor does not block doctor"           ./flixw doctor
t 88 "drift does not block validate (which reports it)"         ./flixw validate
cp "$work/flix.toml.bak" flix.toml

# --- integrity -------------------------------------------------------------
echo "integrity"
t 85 "truncated cache entry is refused"                         sh -c '
  jar=$(ls "$FLIX_CACHE_HOME"/compilers/*.jar | head -1)
  cp "$jar" "$jar.keep"; : > "$jar"
  ./flixw -- --version; rc=$?
  mv "$jar.keep" "$jar"; exit $rc'
t 85 "wrong digest in the lock is refused"                      sh -c '
  cp .flixw/lock.toml /tmp/flixw-lock.keep
  sed "s/^sha256.*/sha256  = \"0000000000000000000000000000000000000000000000000000000000000000\"/" \
      /tmp/flixw-lock.keep > .flixw/lock.toml
  rm -f "$FLIX_CACHE_HOME"/compilers/*0000*.jar
  ./flixw -- --version; rc=$?
  cp /tmp/flixw-lock.keep .flixw/lock.toml; exit $rc'
t 87 "FLIX_DIST_URL must be https"                              env FLIX_DIST_URL=http://x/y ./flixw -- --version
# URI.create accepts a hostless URL, so the scheme test alone let `https:///mirror`
# through and it resurfaced as an uncaught IllegalArgumentException mid-download.
t 87 "FLIX_DIST_URL without a host is a diagnostic, not a crash" env FLIX_DIST_URL='https:///mirror' ./flixw -- --version
t 87 "FLIX_DIST_URL with traversal is refused"                  env FLIX_DIST_URL='https://m.example/../x' ./flixw -- --version

# --- java selection --------------------------------------------------------
echo "java selection"
t 126 "broken FLIX_JAVA_HOME is caught by the shim"             env FLIX_JAVA_HOME=/nonexistent ./flixw -- --version
# The three cases below drive a fake JDK: a lying release file over a bin/java that
# delegates to the real one. Windows would need that trampoline to be a genuine java.exe,
# which cannot be faked by copying -- the JVM resolves java.home from its own path.
if [ "$posix" = yes ]; then
  t 83  "explicit Java below the floor is fatal"                env FLIX_JAVA_HOME="$work/jdk17" ./flixw -- --version
  g 0   'FLIXW011' "above the ceiling warns and proceeds"       env FLIX_JAVA_HOME="$work/jdk99" ./flixw -- --version
  t 83  "FLIXW_STRICT_JAVA makes the ceiling fatal"             env FLIX_JAVA_HOME="$work/jdk99" FLIXW_STRICT_JAVA=1 ./flixw -- --version
  # The compiled stage 0 is built for the floor and the shim execs it, which leaves no
  # way back: handing it to an older JVM is an UnsupportedClassVersionError with no
  # FLIXW code reached and no fallback. The shim must therefore decline the fast path
  # below the floor. Both fixtures' bin/java is really the host java, so the only
  # observable difference is which route the shim took -- which is exactly the bug.
  g 0   'stage0 source'   "below the floor the shim declines the class"   env FLIX_JAVA_HOME="$work/jdk17" ./flixw wrapper --version
  g 0   'stage0 compiled' "at the floor or above the shim uses it"        env FLIX_JAVA_HOME="$work/jdk99" ./flixw wrapper --version
  # asdf, mise and jenv install `java` as a shim script, not a symlink into a JDK, so
  # there is no release file beside it. Running the cached class blind under one of those
  # pointing at an old JVM died on class file version with no way back, so an
  # unidentifiable Java now earns the source path rather than the fast one.
  g 0   'stage0 source' "an unidentifiable Java declines the class too"   env FLIX_JAVA_HOME="$work/jdkbare" ./flixw wrapper --version

  # With no java on PATH at all the shim never reaches stage 0, so the only help a user
  # gets is the shim's own message -- and the only route back is a JDK flixw installed
  # earlier, whose path it reads from a file rather than guessing a vendor's layout.
  # PATH is stripped to a directory of the utilities the shim itself needs, minus java.
  mkdir -p "$work/tools"
  for u in uname dirname readlink cat sed cut head grep tr shasum sha256sum openssl; do
    p=$(command -v "$u" 2>/dev/null) && ln -sf "$p" "$work/tools/$u"
  done
  # The note is a java source in its own right, so a case meaning "nothing at all" has to
  # take it away as well, or it is testing a machine that still has a JDK.
  mv .flixw/local/java "$work/note.aside" 2>/dev/null || true
  g 127 'Temurin' "no java at all names a JDK to install"       env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flixw check
  g 127 'install-jdk' "and says how flixw can fetch one" env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flixw check
  # A recorded JDK is used when nothing else answers. The fixture stands in for a real
  # install so the suite stays offline; what is under test is the shim reading it.
  # A stand-in for an installed JDK, inside the cache where a real one lands: the marker
  # is only honoured when it names something there, since the shims execute what it names.
  mkdir -p "$cache/jdks/fake/bin"
  printf '#!/bin/sh\nexec %s "$@"\n' "$realjava" > "$cache/jdks/fake/bin/java"
  chmod +x "$cache/jdks/fake/bin/java"
  printf 'JAVA_VERSION="21.0.1"\n' > "$cache/jdks/fake/release"
  printf '%s\n' "$cache/jdks/fake/bin/java" > "$cache/jdks/default"
  g 0 'flixw' "a recorded JDK is used when PATH has none"       env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flixw wrapper --version
  # A java below the floor on PATH is worse than none: under 15 it cannot even compile
  # stage 0, so nothing flixw knows is ever reached. A recorded JDK outranks it.
  g 0 'stage0' "a recorded JDK outranks a below-floor PATH java" env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/jdk17/bin:$work/tools" ./flixw wrapper --version
  # But never an explicitly named one: those fail loudly rather than being replaced by a
  # JVM the caller did not ask for.
  t 83 "an explicit below-floor JDK is not silently replaced"    env FLIX_JAVA_HOME="$work/jdk17" ./flixw -- --version
  # A marker naming something outside the cache is an instruction to run someone else's
  # binary, not a record of an install.
  printf '%s\n' "$realjava" > "$cache/jdks/default"
  g 127 'no java executable' "a marker outside the cache is ignored"  env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flixw check
  # ...and a marker that walks back out of it with `..` is the same instruction wearing
  # the right prefix, which a starts-with test alone accepts.
  printf '%s\n' "$cache/jdks/../../../../../../..$realjava" > "$cache/jdks/default"
  g 127 'no java executable' "a marker escaping the cache with .. is ignored" env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/tools" ./flixw check
  # A java with no release file leaves the shim unable to read a version. That is
  # harmless above the floor, and below 15 it means the JVM cannot compile stage 0 at
  # all -- so when a recorded JDK exists, the shim asks the JVM itself rather than
  # source-launching into a class-version error stage 0 would never get to explain.
  printf '%s\n' "$cache/jdks/fake/bin/java" > "$cache/jdks/default"
  # 'compiled' is the discriminating word: the recorded JDK declares 21 in its release
  # file, so the shim may hand it the compiled class. Had the swap not happened, the
  # unidentifiable java would have taken the source path and said so.
  g 0 'stage0 compiled' "an unidentifiable below-floor java defers to the recorded JDK" \
      env -u JAVA_HOME -u FLIX_JAVA_HOME PATH="$work/jdkbare17/bin:$work/tools" ./flixw wrapper --version
  rm -rf "$cache/jdks/default" "$cache/jdks/fake"
  mv "$work/note.aside" .flixw/local/java 2>/dev/null || true
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
  s "a marker escaping the cache with .. is ignored"            "PATH cannot be stripped the same way"
  s "an unidentifiable below-floor java defers to the recorded JDK" "PATH cannot be stripped the same way"
fi

# --- jvm options -----------------------------------------------------------
echo "jvm options"
t 0  "safe options are passed through"                          env FLIX_JVM_OPTS="-Xmx512m -Dfoo=bar" ./flixw -- --version
t 87 "an agent needs the unsafe opt-in"                         env FLIX_JVM_OPTS="-javaagent:/x" ./flixw -- --version
t 87 "-jar is refused"                                          env FLIX_JVM_OPTS="-jar /x" ./flixw -- --version
t 87 "an unterminated quote is refused"                         env FLIX_JVM_OPTS='-Dx="y' ./flixw -- --version
t 0  "the unsafe opt-in works"                                  env FLIX_JVM_OPTS="-XX:OnError=true" FLIXW_UNSAFE_JVM_OPTS=1 ./flixw -- --version

# --- project root ----------------------------------------------------------
echo "project root"
t 0  "invocation from a subdirectory"                           sh -c 'cd src && ../flixw -- --version'
t 80 "invocation from outside the anchored tree is refused"     sh -c 'cd / && "'"$proj"'/flixw" -- --version'
t 80 "FLIX_PROJECT_ROOT naming no directory"                    env FLIX_PROJECT_ROOT=/nope/nowhere ./flixw -- --version
# flix.toml is Flix's file, not flixw's: the wrapper needs somewhere to keep the lock, and
# the directory it was installed into is that place. Requiring a manifest made the empty
# directory unreachable in both directions -- no pin without a manifest, and no `init` to
# write one without a pinned compiler.
t 0  "pin works in a project with no manifest yet"              sh -c '
  d=$1/bare; rm -rf "$d"; mkdir -p "$d"
  java "$2/src/assets/flixw-setup.java" setup "$d" >/dev/null 2>&1
  cd "$d" && ./flixw pin '"$version"' >/dev/null 2>&1
  test -f .flixw/lock.toml' sh "$work" "$root"
g 81 'nested' "the nearest manifest wins over the anchor's"     sh -c 'cd nested && ../flixw -- --version'
mkdir -p "$proj/nested/.flixw"
cp "$proj/.flixw/lock.toml" "$proj/nested/.flixw/lock.toml"
t 0  "a nested project runs once it has its own lock"           sh -c 'cd nested && ../flixw -- --version'

# --- help ------------------------------------------------------------------
echo "help"
g 0 'Compiler commands:' "help renders the merged command tree" ./flixw help
g 0 'checks the current project for errors' \
                 "compiler verbs carry the compiler's own descriptions"  ./flixw help
# The wrapper's verbs used to be marked by a `(wrapper)` prefix on each description; the
# group heading states it once instead.  The requirement is unchanged -- a reader must be
# able to tell who answers a word -- so this asserts the heading, not the prefix.  The
# prefix does survive in the generated completions, which have no headings to carry it.
g 0 'Wrapper commands:' "wrapper verbs are marked as the wrapper's"  ./flixw help

# Colour is a second channel over the headings, never the only one, so the three ways it
# can be wrong are all worth pinning: leaking escapes into a pipe, styling the compiler's
# verbs (which are most of the screen, so styling them says nothing), and ignoring
# NO_COLOR.  None of these can be checked from a terminal-less CI run without forcing.
t 0 "help emits no ANSI when stdout is not a terminal" sh -c '
  esc=$(printf "\033")
  ! ./flixw help 2>/dev/null | grep -q "$esc"'
t 0 "forced colour styles the wrapper group, not the compiler's" sh -c '
  out=$(CLICOLOR_FORCE=1 ./flixw help 2>/dev/null)
  printf "%s" "$out" | grep -q "\[36mpin" &&
  printf "%s" "$out" | grep -q "  init " &&
  printf "%s" "$out" | grep "  init " | { ! grep -q "\[3"; }'
t 0 "NO_COLOR beats a forced colour setting" sh -c '
  esc=$(printf "\033")
  ! NO_COLOR=1 CLICOLOR_FORCE=1 ./flixw help 2>/dev/null | grep -q "$esc"'
g 0 'checks the current project for errors' \
                 "help flix <command> describes one command"    ./flixw help flix check
# Flix 0.75 answers `check --help` with the *top-level* help and exit 0. Saying so is the
# whole point: without it the top-level screen gets rendered under a "check" heading.
g 0 'only a top-level' "help flix says when there is no per-command help" ./flixw help flix check
t 89 "help flix rejects a command the compiler does not list"   ./flixw help flix nosuchverb
t 89 "an unknown help topic is a usage error"                   ./flixw help nosuchtopic
g 0 'no plugins\|plugin'  "help plugin answers without running anything" ./flixw help plugin
g 0 'tasks'      "help task answers from the project's own file"  ./flixw help task

# gencomp, the widely used generic generator, emits the first word of each *description* as
# a verb for Flix -- `creates`, `checks`, `builds` -- because its section detector consumes
# the `Command: init` line itself. These two assertions are what separate this generator
# from that one, so they are worth stating as behaviour rather than as a comment.
g 0 "__fish_use_subcommand -a 'check'" \
                 "generated fish completion names real verbs"    ./flixw help completion fish
t 0  "generated fish completion invents no verb from a description" sh -c '
  ! ./flixw help completion fish | grep -q -- "-a .creates."'
# Flix ships a description containing an apostrophe ("the 'effects.lock' file"), so quoting
# is a live case: unescaped, the literal ends early and the rest becomes fish source.
t 0  "generated fish completion escapes quotes in descriptions"  sh -c '
  ./flixw help completion fish | grep -q "effects.lock" \
    && ! ./flixw help completion fish | grep -qE "^complete[^#]*[^\\\\]'"'"'effects"'
# One completion, generated from one picocli CommandSpec. bash and zsh come from picocli's
# own AutoComplete; fish and pwsh are flixw walking the same model, because picocli generates
# neither. The point of the shared model is that four shells cannot describe four command
# sets, so every shell is asserted to name the same compiler verb.
for sh in bash zsh fish pwsh; do
  g 0 'build-fatjar' "completion $sh names this compiler's verbs"  ./flixw completion "$sh"
done
g 0 'generated by \[picocli\]' "bash comes from picocli's own generator" ./flixw completion bash
g 0 '__fish_use_subcommand' "fish is flixw walking the same model"  ./flixw completion fish
g 0 'Register-ArgumentCompleter' "pwsh registers against the cmd trampoline" ./flixw completion pwsh
t 87 "an unknown shell is a usage error"                        ./flixw completion tcsh
t 87 "completion takes exactly one shell"                       ./flixw completion bash zsh

# Generating a completion must not need a project -- it is what somebody runs while setting
# up a shell, routinely before any flixw project exists. Outside one it falls back to the
# wrapper verbs plus the built-in table rather than failing.
t 0  "completion needs no project at all"                       sh -c '
  d=$1/nocomp; rm -rf "$d"; mkdir -p "$d"
  cd "$d" && java "$2/src/stage0/flixw.java" completion fish | grep -q "__fish_use_subcommand"' sh "$work" "$root"

# The scripts are loaded by real shells, not merely grepped: a completion that parses in a
# test harness and not in the shell it is for has been tested for the wrong thing.
if command -v bash >/dev/null 2>&1; then
  t 0  "the generated bash completion loads in real bash"       sh -c '
    ./flixw completion bash > "$1/c.bash"
    bash --norc -c "source $1/c.bash"' sh "$work"
else
  s "the generated bash completion loads in real bash" "no bash on this machine"
fi
if command -v fish >/dev/null 2>&1; then
  t 0  "the generated fish completion loads and completes"      sh -c '
    ./flixw completion fish > "$1/c.fish"
    fish --no-config -c "source $1/c.fish; complete -C \"flixw bui\"" | grep -q build' sh "$work"
else
  s "the generated fish completion loads and completes" "no fish on this machine"
fi

# The renderer is a companion asset, so it can be unreachable. What must not happen is that
# `help` then says less than it did before the renderer existed: the compiler's half comes
# from the cache, offline, with no compiler launch at all.
t 0  "help degrades to wrapper table plus cached compiler help" sh -c '
  # The cached asset has to go first. Without this the renderer is already on disk, the
  # unreachable source is never consulted, and the test passes while asserting nothing --
  # which is how it was first written. The next test to need the asset re-fetches it.
  rm -rf "$FLIX_CACHE_HOME/wrapper/assets"
  out=$(FLIXW_ASSET_SOURCE="file:///nonexistent/" ./flixw help 2>&1)
  case "$out" in *"repository-local Flix bootstrap"*) ;; *) exit 1 ;; esac
  case "$out" in *"Usage: flix"*) ;; *) exit 1 ;; esac'

# --- degradation -----------------------------------------------------------
echo "degradation"
g 0 'FLIXW010' "unparseable --help falls back, does not brick"  env FLIX_JAR="$work/impostor/impostor.jar" ./flixw check
# The raw text is kept even though the parse failed, which is the case it exists for: a
# layout flixw cannot read is exactly when a reader needs the compiler's own words. Writing
# the record only on a successful parse would discard them precisely when they matter.
t 0  "help kept even when the verb parse fails"                 sh -c '
  rm -f "$FLIX_CACHE_HOME"/verbs/override-*.help
  FLIX_JAR="$1/impostor/impostor.jar" ./flixw check >/dev/null 2>&1
  for f in "$FLIX_CACHE_HOME"/verbs/override-*.help; do test -s "$f" && exit 0; done
  exit 1' sh "$work"
t 87 "FLIX_JAR pointing at nothing is a usage error"            env FLIX_JAR=/nonexistent/x.jar ./flixw check
# The lock is read and drift checked before the override is, so an override cannot stand in
# for a pin -- not even one naming a jar that exists. It is the first thing someone testing
# a local build runs into, so it is documented in docs/CONTRACT.md and asserted here.
g 81 'lock.toml' "FLIX_JAR does not substitute for a pin"       sh -c '
  d=$1/nolock; rm -rf "$d"; mkdir -p "$d"
  java "$2/src/assets/flixw-setup.java" setup "$d" >/dev/null 2>&1
  cd "$d" && FLIX_JAR="$1/impostor/impostor.jar" ./flixw check' sh "$work" "$root"
t 0  "a read-only verb cache stays silent"                      sh -c '
  chmod -R a-w "$FLIX_CACHE_HOME/verbs"
  ./flixw check; rc=$?
  chmod -R u+w "$FLIX_CACHE_HOME/verbs"; exit $rc'
# --help/-h/bare help must work on a project's very first command, before anything has
# ever been pinned -- there is no compiler yet to ask for its half, so this is the routing
# table alone. It used to fail with FLIXW002, the same "no lock.toml" error every other
# verb correctly gives, which made the one command meant to explain what to do next
# unreachable in exactly the state that needed it.
for spelling in --help -h help; do
  g 0 'repository-local Flix bootstrap' "$spelling works before any project is pinned" sh -c '
    d=$1/nolock-help; rm -rf "$d"; mkdir -p "$d"
    java "$2/src/assets/flixw-setup.java" setup "$d" >/dev/null 2>&1
    cd "$d" && ./flixw '"$spelling"'' sh "$work" "$root"
done

# --- verb capture across help renderers ------------------------------------
echo "verb capture"
# Neither renderer is a contract, so both are read. The unit checks assert the parse
# itself against both layouts; these two prove the whole capture path -- subprocess,
# parse, cache -- reaches a picocli screen, which is what FLIXW010 used to answer.
g 0 'capabilities' "picocli Commands: block is captured"        env FLIX_JAR="$work/picocli/picocli.jar" ./flixw info
t 0 "picocli help does not degrade to the built-in table"       sh -c '
  ! FLIX_JAR="$1/picocli/picocli.jar" ./flixw info 2>&1 | grep -q FLIXW010' sh "$work"
# The stock renderer must keep working unchanged; the sleeper answers scopt's one-line
# form, and its three verbs are the whole set it advertises.
g 0 'check run test' "scopt usage line is still captured"       env FLIX_JAR="$work/sleeper/sleeper.jar" ./flixw info

# --- process behaviour -----------------------------------------------------
echo "process behaviour"
t 42 "the child exit status is propagated"                      sh -c 'cd nested && ../flixw run'
if [ "$posix" != yes ]; then
  s "SIGTERM to stage 0 does not orphan the compiler"           "MSYS cannot signal a native JVM"
else
t 0  "SIGTERM to stage 0 does not orphan the compiler"          sh -c '
  FLIX_JAR="$1/sleeper/sleeper.jar" ./flixw check >/dev/null 2>&1 &
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
  ./flixw run > "$1/out.txt" 2>/dev/null
  grep -qx ok "$1/out.txt"' sh "$work"

# --- unit checks -----------------------------------------------------------
# Compiled against stage 0 itself, so it can reach the manifest scanner and the bounded
# capture directly. Its output is shown rather than swallowed: the corpus size and the
# per-group counts are the interesting part, and one shell case cannot express them.
echo "unit checks"
# flixw-help.java links against picocli, so the unit checks compile against the same jar the
# release publishes -- staged into tests/.work above, beside the release fixture.
javac -cp "$picocli_jar" -d "$work/unit" "$root/src/stage0/flixw.java" "$root/src/assets/flixw-help.java" \
  "$root/src/assets/flixw-jdk.java" \
  "$root/tests/UnitCheck.java"
set +e
java -cp "$work/unit:$picocli_jar" UnitCheck "$root/tests/corpus" "$root/tests/schema"
unit_rc=$?
set -e
t 0  "manifest corpus, pin rewrite and capture bounds"          test "$unit_rc" = 0

# --- diagnostics -----------------------------------------------------------
echo "diagnostics"
# doctor output is meant to be pasted into bug reports, so it must not carry the password
# out of a proxy URL. Java's HttpClient ignores these variables; only doctor reads them.
t 0  "doctor redacts credentials in proxy urls"                 sh -c '
  out=$(HTTPS_PROXY="http://user:hunter2@proxy.example:3128" ./flixw doctor 2>&1)
  printf "%s" "$out" | grep -q "[*][*][*]@proxy.example" || exit 1
  ! printf "%s" "$out" | grep -q hunter2'
# stdout only: the JVM announces "Picked up JAVA_TOOL_OPTIONS: ..." on stderr itself, which
# no wrapper can suppress. What flixw controls is its own report, and that must be clean.
t 0  "doctor redacts secrets in JVM option variables"           sh -c '
  out=$(JAVA_TOOL_OPTIONS="-Dhttps.proxyPassword=hunter2 -Xmx1g" ./flixw doctor 2>/dev/null)
  printf "%s" "$out" | grep -q "proxyPassword=[*][*][*]" || exit 1
  ! printf "%s" "$out" | grep -q hunter2'
# The stage-0 cache is keyed by source hash alone, so a class compiled by a newer JDK
# would be handed to a Java 21 shim. 65 is the classfile version of the declared floor.
t 0  "the cached stage 0 targets the Java floor"                sh -c '
  d=$(ls -d "$FLIX_CACHE_HOME"/stage0/*/ 2>/dev/null | head -1)
  [ -n "$d" ] || exit 1
  major=$(od -An -tu1 -j7 -N1 "$d/flixw.class" | tr -d " ")
  [ "$major" = "65" ] || { echo "classfile major $major, want 65"; exit 1; }'

# --- maintenance verbs -----------------------------------------------------
echo "maintenance verbs"
t 0  "validate passes on a healthy project"                     ./flixw validate
g 88 'differs from flixw' "validate detects an edited shim"     sh -c '
  cp flixw "$1/flixw.keep"; echo "# tampered" >> flixw
  ./flixw validate; rc=$?
  cp "$1/flixw.keep" flixw; chmod +x flixw; exit $rc' sh "$work"
g 88 'changes flixw' "validate detects a gitattributes override" sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "* text=auto eol=crlf\n" >> .gitattributes
  ./flixw validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
# The most direct override of all names the file outright, and a wildcard-only check
# read it as healthy -- while CRLF on the POSIX shim makes it unrunnable.
g 88 'changes flixw' "an exact later rule is an override too"     sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "/flixw text eol=crlf\n" >> .gitattributes
  ./flixw validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
# Two blocks means the last one wins, and rewriting each in place left two.
g 88 'markers' "validate detects a second flixw block"      sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "# >>> flixw >>>\n/flix text eol=crlf\n# <<< flixw <<<\n" >> .gitattributes
  ./flixw validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
t 0  "doctor --fix collapses duplicate blocks to one"          sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "# >>> flixw >>>\n/flix text eol=crlf\n# <<< flixw <<<\n" >> .gitattributes
  ./flixw doctor --fix >/dev/null 2>&1
  n=$(grep -c ">>> flixw >>>" .gitattributes)
  cp "$1/ga.keep" .gitattributes
  [ "$n" = 1 ]' sh "$work"
# Repeating what the block already says changes nothing, and calling it an override
# would send someone hunting for a problem they do not have.
t 0  "a later rule identical to the block is harmless"           sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "/flixw text eol=lf\n" >> .gitattributes
  ./flixw validate >/dev/null 2>&1; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
g 88 'markers' "an unbalanced flixw marker is a failure"         sh -c '
  cp .gitattributes "$1/ga.keep"
  printf "# <<< flixw <<<\n" >> .gitattributes
  ./flixw validate; rc=$?
  cp "$1/ga.keep" .gitattributes; exit $rc' sh "$work"
t 0  "doctor --fix is a no-op when files match"                ./flixw doctor --fix
g 0  'rewrote' "doctor --fix repairs a clobbered shim"        sh -c 'echo broken > flixw.cmd; ./flixw doctor --fix'
t 0  "the repaired shim matches the source of truth"            cmp flixw.cmd "$root/src/stage0/flixw.cmd"
t 0  "doctor --fix restores the executable bit"                sh -c 'chmod -x flixw; java .flixw/flixw.java doctor --fix; test -x flixw'

# flixw's own namespace is answered before dispatch, so nothing can take it away: not a
# compiler that claimed the name, not FLIX_BACKEND, and not a lock too broken to read --
# which is the state it exists to repair.
t 0  "bare wrapper prints the routing table"                   ./flixw wrapper
t 87 "wrapper rejects an unknown operation"                    ./flixw wrapper --frobnicate
t 87 "wrapper --upgrade takes no arguments"                    ./flixw doctor --fix now
# FLIX_BACKEND=compiler forces every bare verb to the compiler, doctor among them.
# The exemption belongs to flixw's own namespace, so that is what is tested.
t 0  "wrapper --version survives FLIX_BACKEND=compiler"       env FLIX_BACKEND=compiler ./flixw wrapper --version
# 88 because doctor judges and the lock is broken; the point is that it ran at
# all and repaired the shim, rather than being blocked before routing.
g 88 'rewrote' "doctor --fix survives an unreadable lock"        sh -c '
  cp .flixw/lock.toml "$1/lock.keep"
  printf "garbage\n" > .flixw/lock.toml
    echo broken > flixw.cmd; ./flixw doctor --fix; rc=$?
  cp "$1/lock.keep" .flixw/lock.toml; exit $rc' sh "$work"

# The upgrade hand-off runs the *downloaded* stage 0 as `install <root>`, and it inherits
# this project's environment. FLIXW_SOURCE names the running wrapper's source file, so a
# child that believes it anchors in this project, finds a lock, decides `install` is not
# first contact and hands the word to the compiler: `Unrecognized file extension:
# 'install'.` That broke every upgrade from 0.18.0, and it is invisible to any test that
# does not set the variable, so the case sets it deliberately.
t 0 "the installer ignores a stale FLIXW_SOURCE"                sh -c '
  cp "$1/src/assets/flixw-setup.java" "$2/elsewhere-setup.java"
  rm -rf "$2/upgraded" && mkdir -p "$2/upgraded"
  FLIXW_SOURCE="$PWD/.flixw/flixw.java" \
    java "$2/elsewhere-setup.java" setup "$2/upgraded" "$1/src/stage0/flixw.java" \
      >/dev/null 2>&1 || exit 1
  test -x "$2/upgraded/flixw" && test -f "$2/upgraded/.flixw/flixw.java"' sh "$root" "$work"

# The half that actually moves a project: fetch the manifest, verify the new stage 0,
# refuse a downgrade, hand the verified bytes to the setup asset, warm the new release's
# assets. None of it was reachable by any test until FLIXW_RELEASE_SOURCE existed -- the
# suite could assert the refusal and nothing else, so the path whose failure leaves a user
# with no way forward ran first in somebody's project.
newrel=$work/newrelease
rm -rf "$newrel" && mkdir -p "$newrel"
# A release that is genuinely newer, so the downgrade guard has to let it through. Same
# tree with the version rewritten: what is under test is the upgrade machinery, not a diff.
sed 's/WRAPPER_VERSION = "[^"]*"/WRAPPER_VERSION = "9.9.9"/' \
  "$root/src/stage0/flixw.java" > "$newrel/flixw.java"
sed 's/WRAPPER_VERSION = "[^"]*"/WRAPPER_VERSION = "9.9.9"/' \
  "$root/src/assets/flixw-setup.java" > "$newrel/flixw-setup.java"
cp "$root/src/assets/flixw-help.java" "$root/src/assets/flixw-jdk.java" "$newrel/"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$newrel" && sha256sum flixw.java flixw-setup.java flixw-help.java \
     flixw-jdk.java > SHA256SUMS)
else
  (cd "$newrel" && shasum -a 256 flixw.java flixw-setup.java flixw-help.java \
     flixw-jdk.java > SHA256SUMS)
fi
upgproj=$work/upgraded-real
rm -rf "$upgproj" && mkdir -p "$upgproj"
java "$root/src/assets/flixw-setup.java" setup "$upgproj" "$root/src/stage0/flixw.java" >/dev/null 2>&1
g 0 '0.25.3 -> 9.9.9' "upgrade moves the project to a newer release"  sh -c '
  cd "$1" && FLIXW_RELEASE_SOURCE="$2/" FLIXW_ASSET_SOURCE="$2/" \
    ./flixw wrapper --upgrade 2>&1' sh "$upgproj" "$(fileurl "$newrel")"
t 0 "...and the project now carries that stage 0"                sh -c '
  grep -q "WRAPPER_VERSION = \"9.9.9\"" "$1/.flixw/flixw.java"' sh "$upgproj"
# The override is normalised rather than concatenated blindly: asset names are appended to it,
# so a source given with a trailing slash -- or three, which is what a copied URL and a shell
# variable produce between them -- must not become `...//flixw.java`. Asserted through a real
# upgrade because that is the only way the joined URL is ever exercised.
upgslash=$work/upgraded-slashes
rm -rf "$upgslash" && mkdir -p "$upgslash"
java "$root/src/assets/flixw-setup.java" setup "$upgslash" "$root/src/stage0/flixw.java" >/dev/null 2>&1
t 0 "a release source with trailing slashes still resolves"      sh -c '
  cd "$1" && FLIXW_RELEASE_SOURCE="$2///" FLIXW_ASSET_SOURCE="$2/" \
    ./flixw wrapper --upgrade >/dev/null 2>&1
  grep -q "WRAPPER_VERSION = \"9.9.9\"" "$1/.flixw/flixw.java"' sh "$upgslash" "$(fileurl "$newrel")"

t 0 "...and its shims were rewritten by the new release"         sh -c '
  test -x "$1/flixw" && test -f "$1/flixw.cmd"' sh "$upgproj"
# The assets are version-keyed, so an upgrade that did not warm them would leave every
# later --completion and --install-jdk reaching for the network.
t 0 "...and the new release's assets were warmed"                sh -c '
  find "$FLIX_CACHE_HOME/wrapper/assets/9.9.9" -name "flixw-setup.java" | grep -q .'
# A tampered release must not be installed, and must leave the project on what it had.
#
# From a project that has *not* already been upgraded. The first version of this case
# reused the one above, which by then carried the very stage 0 the manifest names -- so
# upgrade answered "already the newest release" and returned 0 without downloading
# anything. It asserted nothing while looking like it asserted the digest guard.
tamperproj=$work/upgraded-tampered
rm -rf "$tamperproj" && mkdir -p "$tamperproj"
java "$root/src/assets/flixw-setup.java" setup "$tamperproj" "$root/src/stage0/flixw.java" >/dev/null 2>&1
g 85 'digest mismatch' "upgrade refuses a release whose stage 0 was tampered with"  sh -c '
  bad=$2-tampered; rm -rf "$bad"; cp -R "$2" "$bad"
  printf "\n// tampered\n" >> "$bad/flixw.java"
  if command -v cygpath >/dev/null 2>&1; then u="file:///$(cygpath -m "$bad")"
  else u="file://$bad"; fi
  cd "$1" && FLIXW_RELEASE_SOURCE="$u/" FLIXW_ASSET_SOURCE="$u/" \
    ./flixw wrapper --upgrade 2>&1' sh "$tamperproj" "$newrel"
t 0 "...and left the project on the stage 0 it had"              sh -c '
  grep -q "WRAPPER_VERSION = \"0.25.3\"" "$1/.flixw/flixw.java"' sh "$tamperproj"

# --upgrade moves to the newest published flixw. What the suite can assert is the guard
# that keeps it from walking backwards -- and it must hold whether this version is newer
# than the newest release (working on flixw) or exactly it (the commit a release was cut
# from), which is why both no-op paths end in the same sentence.
set +e
upg=$(./flixw wrapper --upgrade 2>&1); rc=$?
set -e
case $rc:$upg in
  0:*"Nothing to do"*)
    pass=$((pass + 1)); printf '  ok   %-52s rc=%s\n' "upgrade declines to downgrade" "$rc" ;;
  *)
    fail=$((fail + 1)); printf '  FAIL %-52s rc=%s\n' "upgrade declines to downgrade" "$rc"
    printf '       %s\n' "$(printf '%s' "$upg" | head -3 | tr '\n' '|')" ;;
esac

# --- what install says afterwards -----------------------------------------
echo "install advice"
# `install` is reached two ways. First contact has nothing pinned and the next step is
# pinning. `wrapper --upgrade` arrives here with a lock already in place, and telling that
# reader to pin reads as though the upgrade had lost their compiler.
g 0 'commit all five files' "first contact says to pin"          sh -c '
  d=$1/advice-new; rm -rf "$d"; mkdir -p "$d"
  java "$2/src/assets/flixw-setup.java" setup "$d" 2>&1' sh "$work" "$root"
g 0 'pin is untouched' "installing over a pinned project does not"  sh -c '
  d=$1/advice-pinned; rm -rf "$d"; mkdir -p "$d"
  java "$2/src/assets/flixw-setup.java" setup "$d" >/dev/null 2>&1
  cp "$3/.flixw/lock.toml" "$d/.flixw/lock.toml"
  java "$2/src/assets/flixw-setup.java" setup "$d" 2>&1' sh "$work" "$root" "$proj"

# --- git integration -------------------------------------------------------
echo "git integration"
t 0  "validate warns when generated files are untracked"        sh -c 'git init -q . 2>/dev/null; ./flixw validate'
g 88 'gitignore' "validate fails when the lock is ignored"      sh -c '
  echo ".flixw/" > .gitignore
  ./flixw validate; rc=$?
  rm -f .gitignore; exit $rc'

# --- release archives ------------------------------------------------------
# The archives exist so an existing project can adopt flixw by extracting one. That is only
# true if what comes out of them is exactly what `install` writes -- an archive that is one
# CRLF or one permission bit off produces a project that fails in a way nobody will connect
# back to the tarball. So both are unpacked and diffed against a fresh install.
echo "release archives"
if command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  pk="$work/pack"
  rm -rf "$pk" && mkdir -p "$pk/out" "$pk/ref" "$pk/tar" "$pk/zip"
  if sh "$root/tests/pack.sh" "$pk/out" >"$pk/log" 2>&1; then
    t 0 "pack builds both archives and SHA256SUMS"               sh -c '
      set -e
      ls "$1"/flixw-*.tar.gz "$1"/flixw-*.zip "$1"/flixw.java "$1"/SHA256SUMS' sh "$pk/out"
    # install into ref, minus the file the archives deliberately leave out.
    #
    # From the *published* stage 0, not from src/. What a release ships is the documented
    # source with its comments stripped, and pack.sh puts that in the archives -- so
    # installing from src/ here would compare a stripped archive against a documented
    # reference and call the difference a drift. Both routes still have to agree; they just
    # both start from the artifact the release actually publishes, which is the thing this
    # case exists to pin down.
    java "$pk/out/flixw-setup.java" setup "$pk/ref" "$pk/out/flixw.java" \
      >/dev/null 2>&1 || true
    rm -f "$pk/ref/.gitattributes"
    t 0 "the tarball unpacks to exactly what install writes"     sh -c '
      tar -xzf "$1"/flixw-*.tar.gz -C "$2" && diff -r "$3" "$2"' sh "$pk/out" "$pk/tar" "$pk/ref"
    t 0 "the zip unpacks to exactly what install writes"         sh -c '
      unzip -qo "$1"/flixw-*.zip -d "$2" && diff -r "$3" "$2"' sh "$pk/out" "$pk/zip" "$pk/ref"
    # The shim is useless without this, and an archive is the classic way to lose it.
    t 0 "the shim keeps its executable bit through the tarball"  test -x "$pk/tar/flixw"
    t 0 "the shim keeps its executable bit through the zip"      test -x "$pk/zip/flixw"
    # Extraction leaves .gitattributes alone; doctor --fix is what merges the block, and
    # the archive route is documented as needing it.
    t 0 "the archives do not carry .gitattributes"               sh -c '
      ! test -e "$1/.gitattributes" && ! test -e "$2/.gitattributes"' sh "$pk/tar" "$pk/zip"
  else
    s "pack builds both archives" "pack.sh failed: $(tail -1 "$pk/log")"
  fi
else
  s "release archives" "zip/unzip not installed"
fi

# --- plugins -----------------------------------------------------------
# A machine-wide, digest-verified, opt-in extension mechanism, entirely separate from the
# main fixture project above: its own scratch project, so a latent bug here (a stray
# `rm -rf`, a corrupted lock) cannot leave any earlier section's assumptions one step off.
echo "plugins"
pp=$work/pluginproj
rm -rf "$pp" && mkdir -p "$pp"
java "$root/src/assets/flixw-setup.java" setup "$pp" >/dev/null 2>&1
git init -q "$pp"
(cd "$pp" && ./flixw pin "$version" >/dev/null 2>&1)
ppcv=$(cd "$pp" && ./flixw info 2>/dev/null | awk '/^compiler /{print $2}')

t 0  "plugin with no subcommand prints usage"                    sh -c '
  cd "$1" || exit 1
  out=$(./flixw plugin 2>&1); rc=$?
  [ "$rc" = 88 ] && printf "%s" "$out" | grep -q "usage: ./flixw plugin install"' sh "$pp"

t 0  "plugin install accepts a .jar via file://"                 sh -c '
  cd "$1" && ./flixw plugin install echoer 1.0.0 "$2/pluginjar/plugin.jar" >/dev/null 2>&1' \
  sh "$pp" "$(fileurl "$work")"
g 0  '\[plugins.echoer\]' "the install is recorded in lock.toml"  sh -c 'cd "$1" && cat .flixw/lock.toml' sh "$pp"
g 0  'echoer  1.0.0-'     "plugin list shows the installed build" sh -c 'cd "$1" && ./flixw plugin list' sh "$pp"
g 0  'not audited by flixw' "invoking warns it is unaudited 3rd-party code" sh -c '
  cd "$1" && ./flixw plugin echoer' sh "$pp"

t 0  "plugin invoke delivers the full ABI env tier"               sh -c '
  cd "$1" || exit 1
  out=$(./flixw plugin echoer 2>/dev/null) || exit 1
  need "^FLIXW_ABI_VERSION=1$"
  # flixw is a Java program and reports native paths, so on Windows these are D:\... and
  # not the /d/... the shell would give. That is correct -- a plugin receives what it can
  # open -- so the expectation is converted rather than the value. -F because a Windows
  # path is full of regex metacharacters.
  # Twelve assertions behind one exit code told us only that something broke, which on a
  # platform this cannot be reproduced on is a nine-minute round trip per guess. Each now
  # names itself.
  need() { printf "%s\n" "$out" | grep -qE "$1" || { echo "MISSING /$1/" >&2; exit 1; }; }
  needf() { printf "%s\n" "$out" | grep -Fq "$1" || { echo "MISSING [$1]" >&2; exit 1; }; }
  # flixw is a Java program and reports native paths; the shell reports its own. What the
  # plugin receives has to be openable by the plugin, so the expectation is converted.
  eroot=$1
  if command -v cygpath >/dev/null 2>&1; then eroot=$(cygpath -w "$1"); fi
  needf "FLIXW_PROJECT_ROOT=$eroot"
  # Not converted: FLIX_CACHE_HOME is exported to flixw already in a form a JVM resolves,
  # so what comes back is that same string normalised, not a translation of a shell path.
  need "^FLIXW_CACHE_HOME=."
  need "^FLIXW_COMPILER_VERSION=$3$"
  need "^FLIXW_COMPILER_JAR=.*[.]jar$"
  need "^FLIXW_JAVA_HOME=(/|[A-Za-z]:)"
  need "^FLIXW_PLUGIN_NAME=echoer$"
  need "^FLIXW_PLUGIN_VERSION=1[.]0[.]0$"
  need "^FLIXW_PLUGIN_CACHE=.*plugin-cache.echoer$"
  need "^FLIXW_CONTEXT=.*[.]json$"
  need "CONTEXT_BODY=.*\"abiVersion\": 1"
  # By its last segment: the value is a native path, and JSON escapes every backslash in
  # it, so matching in full would assert the platform'"'"'s spelling rather than the contract.
  need "CONTEXT_BODY=.*\"projectRoot\": \".*pluginproj"
  need "CONTEXT_BODY=.*\"name\": \"echoer\""
  need "CONTEXT_BODY=.*\"cache\": \".*plugin-cache"' \
  sh "$pp" "$cache" "$ppcv"

# Derived data: flixw promises a plugin a directory and promises to collect it. Both halves
# are asserted, because a promise to collect that nothing collects is how a cache turns into
# an unbounded directory nobody remembers writing.
t 0  "the plugin data directory is not created until a plugin writes" sh -c '
  cd "$1" || exit 1
  d=$(./flixw plugin echoer 2>/dev/null | sed -n "s/^FLIXW_PLUGIN_CACHE=//p")
  test -n "$d" && test ! -e "$d"' sh "$pp"

# Not under plugins/<name>/: `plugin list` reports the directories there as installed
# versions, so derived data living there would be listed as a version that cannot be run.
t 0  "plugin data is not mistaken for an installed version"      sh -c '
  cd "$1" || exit 1
  d=$(./flixw plugin echoer 2>/dev/null | sed -n "s/^FLIXW_PLUGIN_CACHE=//p")
  mkdir -p "$d" && printf x > "$d/derived"
  ./flixw plugin list | grep -q "plugin-cache" && exit 1
  ./flixw plugin list | grep -q "^echoer  1[.]0[.]0-"' sh "$pp"

# Against a synthetic plugin, not echoer: the cases after this one still need echoer
# installed, and a test that quietly removes a fixture the rest of the file depends on is a
# failure waiting for whoever adds the next case.
t 0  "removing a plugin removes what it derived"                 sh -c '
  mkdir -p "$2/plugins/scratchling/1.0.0-aaa" "$2/plugin-cache/scratchling"
  printf x > "$2/plugin-cache/scratchling/derived"
  cd "$1" && ./flixw plugin remove scratchling >/dev/null 2>&1
  test ! -e "$2/plugins/scratchling" && test ! -e "$2/plugin-cache/scratchling"' sh "$pp" "$cache"

# An orphan has no usage marker of its own -- the marker went with the plugin -- so purge
# must not treat it as never-seen-used and keep it for ever.
t 0  "purge collects data whose plugin is gone"                 sh -c '
  orphan="$2/plugin-cache/vanished"
  mkdir -p "$orphan" && printf xxxx > "$orphan/derived"
  cd "$1" && ./flixw wrapper --purge --yes >/dev/null 2>&1
  test ! -e "$orphan"' sh "$pp" "$cache"

# The context file is per-invocation and cleaned up by a shutdown hook -- not a `finally`,
# because runArtifact ends in System.exit, which finally never survives.
t 0  "the context file does not outlive its invocation"          sh -c '
  cd "$1" || exit 1
  ctx=$(./flixw plugin echoer 2>/dev/null | sed -n "s/^FLIXW_CONTEXT=//p")
  [ -n "$ctx" ] && [ ! -e "$ctx" ]' sh "$pp"

t 0  "plugin install accepts a .java via file://"                 sh -c '
  cd "$1" && ./flixw plugin install echoer-java 1.0.0 "$2/pluginjava/plugin.java" \
    >/dev/null 2>&1' sh "$pp" "$(fileurl "$work")"
t 0  "a .java plugin receives positional args and the ABI"        sh -c '
  cd "$1" || exit 1
  out=$(./flixw plugin echoer-java hello world 2>/dev/null) || exit 1
  printf "%s\n" "$out" | grep -q "^ARGS=hello,world$"          || exit 1
  printf "%s\n" "$out" | grep -q "^FLIXW_PLUGIN_NAME=echoer-java$" || exit 1
  printf "%s\n" "$out" | grep -q "^FLIXW_ABI_VERSION=1$"' sh "$pp"

t 0  "plugin install accepts a .flix via file://"                 sh -c '
  cd "$1" && ./flixw plugin install echoer-flix 1.0.0 "$2/pluginflix/plugin.flix" \
    >/dev/null 2>&1' sh "$pp" "$(fileurl "$work")"
# The value is a *native* path -- D:\... on Windows, where the shell would say /d/... --
# because flixw is a Java program and a plugin has to be able to open what it is handed.
# Matched by its last segment rather than in full: `g` greps a regex, and a Windows path
# is mostly regex metacharacters. What is under test is that the root reaches the plugin.
g 0  "FLIXW_PROJECT_ROOT=.*pluginproj" "a .flix plugin reads the ABI via Sys.Env.Env" sh -c '
  cd "$1" && ./flixw plugin echoer-flix' sh "$pp"
g 88 'cannot receive arguments' ".flix plugin invoke rejects arguments up front" sh -c '
  cd "$1" && ./flixw plugin echoer-flix oops' sh "$pp"

# The P0 this round carries forward unchanged: `plugin remove ..` dead-reckons to
# <cache>/plugins/.. -- the cache root -- unless the name is validated before deleteTree
# ever runs. Exercised against an isolated cache so a regression cannot wipe the shared one.
travcache=$work/travcache
rm -rf "$travcache" && mkdir -p "$travcache/plugins/echoer/1.0.0-deadbeef"
touch "$travcache/plugins/echoer/1.0.0-deadbeef/plugin.jar" "$travcache/marker"
g 88 'must be lowercase' "plugin remove rejects a traversal name"  sh -c '
  cd "$1" && env FLIX_CACHE_HOME="$2" ./flixw plugin remove ".."' sh "$pp" "$travcache"
t 0  "...and the cache root survived the attempt"                 test -f "$travcache/marker"
t 0  "...and the untouched plugin survived the attempt"           \
  test -f "$travcache/plugins/echoer/1.0.0-deadbeef/plugin.jar"
g 88 'must be lowercase' "plugin invoke rejects a traversal name"  sh -c '
  cd "$1" && ./flixw plugin ".."' sh "$pp"
g 88 'must be lowercase' "plugin install rejects a traversal name" sh -c '
  cd "$1" && ./flixw plugin install ".." 1.0.0 "$2/pluginjar/plugin.jar"' sh "$pp" "$(fileurl "$work")"

t 0  "digest mismatch at launch is caught before the plugin runs" sh -c '
  cd "$1" || exit 1
  f=$(find "$FLIX_CACHE_HOME/plugins/echoer" -maxdepth 1 -type d -name "1.0.0-*")/plugin.jar
  cp "$f" "$f.bak"
  printf corrupt >> "$f"
  out=$(./flixw plugin echoer 2>&1); rc=$?
  mv "$f.bak" "$f"
  [ "$rc" = 85 ] && printf "%s" "$out" | grep -q "no longer matches the digest"' sh "$pp"

g 88 'must look like x.y.z' "plugin install validates the version"     sh -c '
  cd "$1" && ./flixw plugin install badver latest "$2/pluginjar/plugin.jar"' sh "$pp" "$(fileurl "$work")"
g 88 'must end in .jar, .java or .flix' "plugin install validates the url's extension" sh -c '
  cd "$1" && ./flixw plugin install badext 1.0.0 "$2/pluginjar/plugin.jar.txt"' sh "$pp" "$(fileurl "$work")"
g 88 'must be https:// or file://' "plugin install rejects other url schemes" sh -c '
  cd "$1" && ./flixw plugin install badscheme 1.0.0 "http://example.invalid/plugin.jar"' sh "$pp"
g 85 'digest mismatch' "plugin install validates an explicit --sha256"  sh -c '
  cd "$1" && ./flixw plugin install baddigest 1.0.0 "$2/pluginjar/plugin.jar" \
    --sha256 0000000000000000000000000000000000000000000000000000000000000000' sh "$pp" "$(fileurl "$work")"
g 88 'is not installed' "plugin remove on a name never installed fails"  sh -c '
  cd "$1" && ./flixw plugin remove doesnotexist' sh "$pp"
g 88 'is not installed' "invoking a name never installed fails"         sh -c '
  cd "$1" && ./flixw plugin doesnotexist2' sh "$pp"

t 0  "reinstalling a name updates the lock to the new version"    sh -c '
  cd "$1" && ./flixw plugin install echoer 1.1.0 "$2/pluginjar/plugin.jar" >/dev/null 2>&1' \
  sh "$pp" "$(fileurl "$work")"
g 0  '1\.1\.0' "the lock now points at 1.1.0"                      sh -c '
  cd "$1" && grep -A2 "\[plugins.echoer\]" .flixw/lock.toml' sh "$pp"
g 88 'expected by lock.toml but not installed' \
  "invoking after the pinned build is removed names the repair"   sh -c '
  cd "$1" || exit 1
  d=$(find "$FLIX_CACHE_HOME/plugins/echoer" -maxdepth 1 -type d -name "1.1.0-*")
  rm -rf "$d"
  ./flixw plugin echoer' sh "$pp"
g 0  'expected by lock.toml but not installed' \
  "doctor warns, not fails, on a plugin the lock wants but is missing" sh -c '
  cd "$1" && ./flixw doctor' sh "$pp"

pp3=$work/pluginproj-nolock
rm -rf "$pp3" && mkdir -p "$pp3"
java "$root/src/assets/flixw-setup.java" setup "$pp3" >/dev/null 2>&1
git init -q "$pp3"
t 0  "plugin install works before any project has ever been pinned" sh -c '
  cd "$1" && ./flixw plugin install echoer 1.0.0 "$2/pluginjar/plugin.jar" >/dev/null 2>&1' \
  sh "$pp3" "$(fileurl "$work")"
t 0  "...and a .jar plugin needs no compiler to run"               sh -c '
  cd "$1" && ./flixw plugin echoer >/dev/null 2>&1' sh "$pp3"
t 0  "...but a .flix plugin does"                                  sh -c '
  cd "$1" && ./flixw plugin install echoer-flix 1.0.0 "$2/pluginflix/plugin.flix" \
    >/dev/null 2>&1' sh "$pp3" "$(fileurl "$work")"
g 88 'no compiler pinned' "...and refuses to run without one"      sh -c '
  cd "$1" && ./flixw plugin echoer-flix' sh "$pp3"
t 0  "installing a second version with no lock present"            sh -c '
  cd "$1" && ./flixw plugin install echoer 2.0.0 "$2/pluginjar/plugin.jar" >/dev/null 2>&1' \
  sh "$pp3" "$(fileurl "$work")"
g 88 'has 2 versions installed' \
  "an ambiguous plugin with no lock entry names the fix"           sh -c '
  cd "$1" && ./flixw plugin echoer' sh "$pp3"

# --- tasks ---------------------------------------------------------------
# npm's `scripts`, not a new verb per task: .flixw/tasks.toml is hand-edited, never
# fetched, never installed -- a shell string in a file the project already trusts.
echo "tasks"
t 0  "task with no tasks.toml lists nothing, offline"              sh -c '
  cd "$1" && ./flixw task' sh "$pp3"
printf 'greet = "echo GOT"\n' > "$pp/.flixw/tasks.toml"
g 0  '^greet$' "task with no name lists the tasks by name"         sh -c 'cd "$1" && ./flixw task' sh "$pp"
g 0  '^GOT$'   "a task with no extra args runs the bare command"   sh -c 'cd "$1" && ./flixw task greet' sh "$pp"
g 0  '^GOT extra1 extra2$' "extra args are appended positionally"  sh -c '
  cd "$1" && ./flixw task greet extra1 extra2' sh "$pp"
g 88 "no task 'nope'" "an unknown task names the tasks that exist" sh -c '
  cd "$1" && ./flixw task nope' sh "$pp"
g 88 'known tasks: greet' "...including the list itself"          sh -c '
  cd "$1" && ./flixw task nope' sh "$pp"

echo
echo "passed=$pass failed=$fail skipped=$skipped"
[ "$fail" -eq 0 ]
