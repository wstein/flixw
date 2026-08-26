#!/bin/sh
# flixw lint -- the repository's configured static checks. Run before every commit.
#
#   sh tests/lint.sh
#
# 1. javac -Xlint:all -Werror   stage 0 must compile clean on the Java it targets
# 2. shellcheck                 the POSIX shim is executed on machines we cannot test
# 3. shim byte-parity           src/stage0/flixw and src/stage0/flixw.cmd are the checked-in copies of
#                               the SHIM and CMD text blocks inside src/stage0/flixw.java, and
#                               `install` writes the latter. Drift means a project gets
#                               a shim whose published hash does not match this tree.
# 4. the Java floor             MIN_JAVA is written out again in both shims
# 5. the source floor           stage 0 still compiles at the release its diagnostics promise
# 6. the wrapper namespace      every `./flixw wrapper --x` spelling is one the usage offers
# 7. schema parity              docs/schema/ is what `wrapper --schema` emits, nothing else
# 8. javadoc                    the published API docs build with no malformed doc comment
# 9. CRLF                       src/stage0/flixw.cmd must keep its cmd.exe line endings
# 10. the size ratchet         stage 0 is shrinking to a verified launcher; the code-line
#                              ceiling and the comment-density floor hold that, pulling
#                              against each other so neither is met at the other's cost
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only; see src/stage0/flixw
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$root/tests/.work/lint
rm -rf "$work"
mkdir -p "$work"
fail=0

say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }

# --- 0b. picocli -------------------------------------------------------------
# src/assets/flixw-help.java is the one file here that compiles against something outside this
# repository, so the jar has to be present to check it at all. Fetched into the gitignored
# work dir rather than committed -- nothing binary is committed here, and tests/run.sh
# already downloads a 32MB compiler on a cold cache, so one 400KB jar is the existing
# bargain rather than a new one.
#
# Verified against the digest stage 0 pins, which makes this a test of that pin as well:
# if PICOCLI_SHA256 is ever edited to something Maven Central does not serve, this fails
# here rather than on a user's first `./flixw help`.
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
pv=$(sed -n 's/.*PICOCLI_VERSION = "\([^"]*\)".*/\1/p' "$root/src/stage0/flixw.java")
pd=$(sed -n 's/^PICOCLI_SHA256=\([0-9a-f]\{64\}\)$/\1/p' "$root/tests/pack.sh")
# Cached across runs in the gitignored work dir, so only the first lint on a machine needs
# the network. A *failed* fetch is fatal here rather than skipped: continuing would compile
# src/assets/flixw-help.java against a classpath entry that does not exist, which javac reports as
# a warning about a missing path and then a pile of unrelated symbol errors -- a diagnostic
# that sends the reader looking at the wrong file entirely.
picocli="$work/picocli-$pv.jar"
if [ ! -f "$picocli" ]; then
  curl -fsSL -o "$picocli" \
    "https://repo1.maven.org/maven2/info/picocli/picocli/$pv/picocli-$pv.jar" || {
    rm -f "$picocli"
    bad "cannot fetch picocli $pv; src/assets/flixw-help.java cannot be checked without it"
    say "      it caches in $work, so this is a one-time download per machine"
    exit 1
  }
fi
got=$($sum "$picocli" | cut -d' ' -f1)
if [ "$got" = "$pd" ]; then
  say "ok    picocli $pv matches the digest tests/pack.sh pins"
else
  rm -f "$picocli"
  bad "picocli $pv digest mismatch: tests/pack.sh pins $pd, Maven Central served $got"
  exit 1
fi

# One dependency, declared in four places that must agree: the version stage 0 names, the
# digest pack.sh will publish, and both of those as written down for a reader in
# THIRD_PARTY_NOTICES.md. A notices file that drifts is worse than none -- it is a licence
# and provenance claim, and the release publishes it. Checked here because a release is a
# bad moment to discover the SBOM describes a version that is no longer shipped.
notices=$root/THIRD_PARTY_NOTICES.md
if [ ! -f "$notices" ]; then
  bad "THIRD_PARTY_NOTICES.md is missing; picocli ships in every release"
elif ! grep -Fq "$pv" "$notices"; then
  bad "THIRD_PARTY_NOTICES.md does not name picocli $pv (stage 0 pins that version)"
elif ! grep -Fq "$pd" "$notices"; then
  bad "THIRD_PARTY_NOTICES.md does not carry the digest tests/pack.sh pins"
elif ! grep -Fq "Apache License 2.0" "$notices"; then
  bad "THIRD_PARTY_NOTICES.md does not state picocli's licence"
else
  say "ok    THIRD_PARTY_NOTICES.md agrees with the pinned picocli version and digest"
fi

# --- 1. Java ---------------------------------------------------------------
# auxiliaryclass is off for this one compile, not project-wide: src/assets/flixw-help.java
# is deliberately a same-package companion file rather than a class merged into flixw.java
# (its file name is the release asset name ensureCompletionAsset fetches, which cannot be
# a valid Java identifier), and tests/UnitCheck.java deliberately calls its package-private
# render() directly rather than through a subprocess -- exactly the pattern this warning
# exists to flag by default, and exactly what this repository's own multi-file layout is.
if javac -Xlint:all,-auxiliaryclass -Werror -cp "$picocli" -d "$work/classes" \
        "$root/src/stage0/flixw.java" "$root/src/assets/flixw-jdk.java" \
        "$root/src/assets/flixw-setup.java" "$root/src/assets/flixw-inspect.java" \
        "$root/src/assets/flixw-help.java" \
        "$root/tests/UnitCheck.java" 2>"$work/javac.log"; then
  say "ok    javac -Xlint:all -Werror (stage 0, completion generator and unit checks)"
else
  bad "javac"
  cat "$work/javac.log"
fi

# --- 2. shell --------------------------------------------------------------
if command -v shellcheck >/dev/null 2>&1; then
  # The shim reads FLIX_* from the environment by design; SC2154 would flag every one.
  scripts="$root/src/stage0/flixw"
  for s in "$root"/tests/*.sh; do [ -f "$s" ] && scripts="$scripts $s"; done
  # shellcheck disable=SC2086
  if shellcheck -s sh -e SC2154 $scripts >"$work/shellcheck.log" 2>&1; then
    say "ok    shellcheck"
  else
    bad "shellcheck"
    cat "$work/shellcheck.log"
  fi
else
  say "skip  shellcheck (not installed)"
fi

# --- 3. shim byte-parity ---------------------------------------------------
# The shim text lives in src/assets/flixw-setup.java now, fetched and digest-verified at run
# time -- so this stands a release up in a directory and points the wrapper at it. Same
# code path as production, only the base URL differs; nothing here touches the network.
fixture=$work/release
mkdir -p "$fixture"
cp "$root/src/stage0/flixw.java" "$root/src/assets/flixw-jdk.java" \
   "$root/src/assets/flixw-setup.java" "$root/src/assets/flixw-inspect.java" "$root/src/assets/flixw-help.java" \
   "$fixture/"
[ -f "$picocli" ] && cp "$picocli" "$fixture/picocli-$pv.jar"
if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum; else sum="shasum -a 256"; fi
# shellcheck disable=SC2086  # $sum is a command name plus flags, deliberately split
(cd "$fixture" && $sum flixw.java flixw-jdk.java flixw-setup.java \
   flixw-inspect.java flixw-help.java "picocli-$pv.jar" > SHA256SUMS)
export FLIXW_ASSET_SOURCE="file://$fixture/"
export FLIX_CACHE_HOME="$work/cache"

# `install` refuses to run inside an installed project, so give it a clean target.
if java "$root/src/assets/flixw-setup.java" setup "$work/parity" "$root/src/stage0/flixw.java" \
      >"$work/install.log" 2>&1; then
  for f in flixw flixw.cmd; do
    if cmp -s "$work/parity/$f" "$root/src/stage0/$f"; then
      say "ok    $f matches the text block in src/stage0/flixw.java"
    else
      bad "$f differs from what install writes; edit both sides"
      diff "$root/src/stage0/$f" "$work/parity/$f" || true
    fi
  done
  if [ -x "$work/parity/flixw" ]; then
    say "ok    installed shim is executable"
  else
    bad "installed shim is not executable"
  fi
else
  bad "install did not run"
  cat "$work/install.log"
fi

# --- 3b. what stage 0 kept about a file it no longer holds -----------------
# The shim text moved to the installer asset; the shims' digests stayed, so validate and
# doctor detect drift offline with no fetch. That only works while the digests are right,
# and nothing else would notice them rotting behind a shim edit -- byte-parity above
# compares the *installed* file with src/, not either with the constants.
for pair in "flixw:SHIM_SHA256" "flixw.cmd:CMD_SHA256"; do
  f=${pair%%:*}; k=${pair##*:}
  declared=$(sed -n "/static final String $k =/,/;/p" "$root/src/stage0/flixw.java"              | grep -o '[0-9a-f]\{64\}')
  if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$root/src/stage0/$f" | cut -d' ' -f1)
  else actual=$(shasum -a 256 "$root/src/stage0/$f" | cut -d' ' -f1); fi
  if [ "$declared" = "$actual" ]; then
    say "ok    $k matches src/stage0/$f"
  else
    bad "$k is stale: src/stage0/$f hashes to $actual"
    say "      update the constant in src/stage0/flixw.java, or the shim changed by accident"
  fi
done

# The installer is the bootstrap now: it fetches the stage 0 of its own release, so it
# carries WRAPPER_VERSION too. A disagreement would have somebody download 0.25.0's
# installer and receive some other release's stage 0, with both digests checking out.
v0=$(sed -n 's/.*WRAPPER_VERSION = "\([^"]*\)".*/\1/p' "$root/src/stage0/flixw.java" | head -1)
v1=$(sed -n 's/.*WRAPPER_VERSION = "\([^"]*\)".*/\1/p' "$root/src/assets/flixw-setup.java" | head -1)
if [ -n "$v0" ] && [ "$v0" = "$v1" ]; then
  say "ok    WRAPPER_VERSION is $v0 in stage 0 and in the installer"
else
  bad "WRAPPER_VERSION disagrees: stage 0 says '$v0', the installer says '$v1'"
fi

# WRAPPER_DIR is written into the shim text, so the installer needs its own copy -- and a
# disagreement would have stage 0 reading a directory the installer never wrote.
d0=$(sed -n 's/.*static final String WRAPPER_DIR = "\([^"]*\)".*/\1/p' "$root/src/stage0/flixw.java")
d1=$(sed -n 's/.*static final String WRAPPER_DIR = "\([^"]*\)".*/\1/p' "$root/src/assets/flixw-setup.java")
if [ -n "$d0" ] && [ "$d0" = "$d1" ]; then
  say "ok    WRAPPER_DIR is '$d0' in stage 0 and in the installer"
else
  bad "WRAPPER_DIR disagrees: stage 0 says '$d0', the installer says '$d1'"
fi

# The .gitattributes block markers are written by the installer and validated by stage 0.
# One writes the block, the other decides whether a project has one -- so a rename on
# either side means doctor --fix writes a block validate cannot find, forever.
m0=$(grep -c '# >>> flixw >>>' "$root/src/stage0/flixw.java" || true)
m1=$(grep -c '# >>> flixw >>>' "$root/src/assets/flixw-setup.java" || true)
if [ "$m0" -ge 1 ] && [ "$m1" -ge 1 ]; then
  say "ok    both sides know the .gitattributes block markers"
else
  bad "the .gitattributes markers are in stage 0 ($m0) and the installer ($m1); need both"
fi

# --- 3c. the workflows call commands that still exist ----------------------
# The bootstrap moved twice -- bare `install`, then `wrapper --install`, then the setup
# asset -- and each time the tests and docs were swept while .github/workflows was not.
# The Windows job went on running `java src\flixw.java install scratch` for three
# releases' worth of commits and only failed once the suite ahead of it stopped failing
# first. A workflow is the one caller no local run exercises.
stale=$(grep -rn 'flixw\.java" *install\|flixw\.java install\|wrapper --install\b' \
        "$root/.github/workflows" 2>/dev/null || true)
if [ -z "$stale" ]; then
  say "ok    no workflow calls a bootstrap spelling that was removed"
else
  bad "a workflow calls a removed bootstrap spelling:"
  printf '%s\n' "$stale" | sed 's/^/      /'
fi

# --- 3c2. the workflows name files that still exist -------------------------
# 3c catches a *verb* that moved.  The src/ split moved the *files* instead, and the
# Windows job went on naming `src\flixw-setup.java` -- caught by CI rather than here,
# which is the same failure 3c was written for arriving through the other door.  So
# check the paths too: every src/ or tests/ file a workflow names must exist.  Windows
# steps spell them with backslashes, so both separators normalise to one.  That is
# \134 -- tr's octal for a lone backslash -- rather than the literal two-character
# form, which means the same to tr but reads to shellcheck as a mis-escaped quote.
# A trailing separator is then dropped: a path written inside an escaped markdown
# backtick (\`tests/upstream-cli.sh\`) otherwise carries that backslash into the name.
missing=
for tok in $(grep -rhoE '(src|tests)[\\/][A-Za-z0-9._\\/-]+' "$root/.github/workflows" 2>/dev/null \
             | tr '\134' '/' | sed 's|/*$||' | sort -u); do
  # `tests/.work/` is deliberately gitignored scratch space. The test jobs name a child
  # directory there only as an actions/cache target; it is created by tests/run.sh, so its
  # absence in a fresh checkout is correct. Every other src/ or tests/ path is a repository
  # path and must exist before the workflow reaches it.
  case "$tok" in tests/.work/*) continue ;; esac
  # Only files: a bare directory (tests/fixtures/smoke) is equally real and equally named.
  [ -e "$root/$tok" ] || missing="$missing $tok"
done
if [ -z "$missing" ]; then
  say "ok    every src/ and tests/ path a workflow names exists"
else
  bad "a workflow names a path that does not exist:"
  for m in $missing; do say "      $m"; done
fi

# --- 3c3. the README downloads the release its digest describes --------------
# The digest check below compares README against local source, which says nothing about
# which release the reader is told to fetch. Those drifted: the page carried a digest for
# 0.25.8 beside five `releases/download/v0.25.3/` URLs, so anyone following it downloaded
# one file and compared it against another's digest -- and, doing exactly as instructed,
# stopped. A quickstart that fails safe is still a quickstart that fails.
readme_ver=$(sed -n 's|.*releases/download/v\([0-9][0-9.]*\)/.*|\1|p' "$root/README.md" \
             | sort -u)
want_ver=$(sed -n 's/.*WRAPPER_VERSION = "\([^"]*\)".*/\1/p' "$root/src/stage0/flixw.java" \
           | head -1)
if [ "$readme_ver" = "$want_ver" ]; then
  say "ok    README downloads v$want_ver, the release it prints a digest for"
else
  bad "README's download URLs name $(echo "$readme_ver" | tr '\n' ' ')but WRAPPER_VERSION is $want_ver"
fi

# --- 3d. the digest README tells people to compare against ------------------
# README prints the setup asset's SHA-256 so an adopter can check it against something
# other than the file's own download. That is only a second opinion while it is right, and
# a wrong one is worse than none: it would train someone to accept a mismatch. The asset
# ships verbatim, so what a release publishes is exactly this file's digest.
readme_sha=$(grep -oE '^[0-9a-f]{64}  flixw-setup\.java$' "$root/README.md" \
             | cut -d' ' -f1 | head -1)
# Against the *stripped* file, because that is what a release publishes -- comparing with
# src/ would have gone quietly wrong the moment the assets started shipping stripped.
setup_ver=$(sed -n 's/.*WRAPPER_VERSION = "\([^"]*\)".*/\1/p' "$root/src/assets/flixw-setup.java" | head -1)
java "$root/tests/strip.java" "$root/src/assets/flixw-setup.java" "$setup_ver" flixw-setup.java \
  > "$work/setup-shipped.java"
if command -v sha256sum >/dev/null 2>&1; then real_sha=$(sha256sum "$work/setup-shipped.java" | cut -d' ' -f1)
else real_sha=$(shasum -a 256 "$work/setup-shipped.java" | cut -d' ' -f1); fi
if [ -z "$readme_sha" ]; then
  bad "README names no SHA-256 for flixw-setup.java"
elif [ "$readme_sha" = "$real_sha" ]; then
  say "ok    README's flixw-setup.java digest matches what a release ships"
else
  bad "README's flixw-setup.java digest is stale"
  say "      README says $readme_sha"
  say "      src/ hashes  $real_sha"
fi

# --- 4. the Java floor is stated in three files ----------------------------
# MIN_JAVA is the authority, but a shim cannot import a Java constant, so the floor is
# written out in both of them -- in a message and in a numeric comparison. That is
# exactly the "written twice" hazard the shims are supposed to avoid, so it is checked
# rather than trusted: a MIN_JAVA bump that misses a shim would silently hand the
# compiled stage 0 to a JVM that cannot load it.
min=$(sed -n 's/.*static final int MIN_JAVA = \([0-9][0-9]*\).*/\1/p' "$root/src/stage0/flixw.java")
if [ -z "$min" ]; then
  bad "cannot read MIN_JAVA from src/stage0/flixw.java"
else
  # -h, or grep prefixes the filename and `stage0` contributes a stray 0 to the set.
  floors=$( { grep -oh 'Java [0-9][0-9]*+' "$root/src/stage0/flixw" "$root/src/stage0/flixw.cmd"
              grep -o -- '-ge [0-9][0-9]*'  "$root/src/stage0/flixw"
              grep -o 'LSS [0-9][0-9]*'     "$root/src/stage0/flixw.cmd"; } | grep -o '[0-9][0-9]*' | sort -u)
  if [ "$floors" = "$min" ]; then
    say "ok    the Java floor is $min in MIN_JAVA and in both shims"
  else
    bad "Java floor disagrees: MIN_JAVA=$min, shims say $(echo "$floors" | tr '\n' ' ')"
  fi
fi

# --- 5. stage 0 still compiles at its own floor ----------------------------
# SOURCE_FLOOR is the oldest javac that can compile stage 0, and it is a *claim made to
# users*: below Java 21 flixw still runs and offers to fetch a JDK, and the no-java
# diagnostic says how far down that offer reaches. One post-floor language feature would
# make the promise false with nothing failing, so it is compiled at that release.
floor=$(sed -n 's/.*static final int SOURCE_FLOOR = \([0-9][0-9]*\).*/\1/p' "$root/src/stage0/flixw.java")
if [ -z "$floor" ]; then
  bad "cannot read SOURCE_FLOOR from src/stage0/flixw.java"
elif javac --release "$floor" -d "$work/floor" "$root/src/stage0/flixw.java" >"$work/floor.log" 2>&1; then
  say "ok    stage 0 compiles at its stated floor (Java $floor)"
elif grep -q "release version $floor not supported" "$work/floor.log"; then
  # A javac new enough to have dropped that release cannot answer the question.
  say "skip  floor check (this javac no longer targets $floor)"
else
  bad "stage 0 no longer compiles at Java $floor, which its diagnostics promise"
  head -5 "$work/floor.log"
fi

# The installer has the same floor for a plainer reason than the provisioner: `install`
# itself must work on the oldest JVM stage 0 runs on, and stage 0 launches the asset with
# the JVM it is running on.
if [ -n "$floor" ]; then
  if javac --release "$floor" -d "$work/floor-install" "$root/src/assets/flixw-setup.java" \
        >"$work/floor-install.log" 2>&1; then
    say "ok    the installer compiles at Java $floor"
  elif grep -q "release version $floor not supported" "$work/floor-install.log"; then
    say "skip  installer floor check (this javac no longer targets $floor)"
  else
    bad "src/assets/flixw-setup.java no longer compiles at Java $floor"
    head -5 "$work/floor-install.log"
  fi
fi

# The JDK provisioner has the *same* floor, and for a sharper reason than stage 0's.
# Stage 0 source-launches a companion asset with the JVM it is itself running on, and this
# asset exists precisely for the machine whose only JVM is below MIN_JAVA -- so a Java 21
# construct in it would make the provisioner unrunnable in the one case it is for, with
# nothing failing until a user hit it. The completion asset carries no such constraint: it
# is only ever reached from a JVM that already cleared the floor.
if [ -n "$floor" ]; then
  if javac --release "$floor" -d "$work/floor-jdk" "$root/src/assets/flixw-jdk.java" \
        >"$work/floor-jdk.log" 2>&1; then
    say "ok    the JDK provisioner compiles at Java $floor, the JVM it may be launched by"
  elif grep -q "release version $floor not supported" "$work/floor-jdk.log"; then
    say "skip  provisioner floor check (this javac no longer targets $floor)"
  else
    bad "src/assets/flixw-jdk.java no longer compiles at Java $floor; it is launched by the"
    say "      too-old JVM it exists to replace, so it cannot use a newer language level"
    head -5 "$work/floor-jdk.log"
  fi
fi

# --- 6. the wrapper namespace is spelled the same way everywhere -----------
# Diagnostics are required to name the command that repairs the problem, which makes a
# renamed command a wrong answer printed at the worst possible moment: four messages went
# on recommending `./flixw wrapper upgrade` for a release after flixw's own operations moved
# behind flags, and nothing failed. `./flixw wrapper` takes flags only, so a bare word after
# it is always a stale spelling -- and every flag it is told to run must be one the usage
# text offers.
# Both extractions below read a bracketed list, and such a list is a Java string
# concatenation that may wrap across source lines once it grows past the margin -- as the
# usage line did when --completion was added. Line-at-a-time greps saw nothing there and
# reported the list as empty, which is the one answer a spelling check must never give
# quietly. Flatten first: join `" + "` continuations and drop the \n escapes, so what is
# under test is the set the list offers rather than the column it happens to end in.
# RS to a character the file cannot contain makes the whole source one record, which is
# what lets the join see across a newline at all.
flat=$work/wrapper-lists.txt
awk 'BEGIN{RS="\034"} { gsub(/\\n/," "); gsub(/"[ \t]*\n[ \t]*\+[ \t]*"/,""); print }' \
  "$root/src/stage0/flixw.java" > "$flat"
usage=$(sed -n 's/.*usage: .\/flixw wrapper \[\([^]]*\)\].*/\1/p' "$flat" | tr -d ' |')
stale=$(grep -o './flixw wrapper [a-z][a-z-]*' "$root/src/stage0/flixw.java" | sort -u || true)
flags=$(grep -o -- './flixw wrapper --[a-z-][a-z-]*' "$root/src/stage0/flixw.java" \
        | sed 's|.*wrapper ||' | sort -u)
# shellcheck disable=SC2086  # deliberate: the flags are ours and contain no spaces
set -- $flags
for flag do
  case $usage in *"$flag"*) ;; *) stale="$stale $flag" ;; esac
done
if [ -z "$stale" ]; then
  say "ok    every ./flixw wrapper spelling matches its usage line"
else
  bad "stale ./flixw wrapper spellings: $(echo "$stale" | tr '\n' ' ')"
fi

# The check above greps for one flag at a time, so a *list* of them was invisible to it --
# and the routing table's `./flixw wrapper [--help | ...]` line was a release behind for
# exactly that reason, offering four operations where the usage offered five. Every
# bracketed list must therefore hold the same set as the usage line, not merely a subset.
lists=$(grep -o -- './flixw wrapper \[[^]]*\]' "$flat" \
        | sed 's|.*wrapper \[||; s|\]||' | tr -d ' |' | sort -u)
if [ "$lists" = "$usage" ]; then
  say "ok    every bracketed ./flixw wrapper list offers what the usage does"
else
  bad "a ./flixw wrapper list disagrees with the usage line"
  say "      usage offers: $usage"
  say "      lists offer:  $(echo "$lists" | tr '\n' ' ')"
fi

# --- 7. the published schema matches the lock this stage 0 writes ----------
# The file name carries the lock format's major version, so a bump that renames the schema
# must move the committed file with it rather than leaving the old name to be served.
schema_version=$(sed -n 's/.*LOCK_SCHEMA_VERSION = "\([a-z0-9]*\)".*/\1/p' "$root/src/stage0/flixw.java")
if [ -z "$schema_version" ]; then
  bad "cannot read LOCK_SCHEMA_VERSION from src/stage0/flixw.java"
  schema_version=none
elif [ ! -f "$root/docs/schema/lock-$schema_version.schema.json" ]; then
  bad "LOCK_SCHEMA_VERSION is $schema_version but docs/schema/lock-$schema_version.schema.json does not exist"
else
  say "ok    docs/schema/ carries the lock format version stage 0 declares ($schema_version)"
fi

# Superseded schemas are never removed, and v1 is the one with locks in the wild naming it.
# Every generated lock carries `#:schema <url>` on its first line, and that lock is
# committed in somebody else's repository for as long as they keep it -- so a schema that
# stops being served does not break flixw, it breaks the editor of a project that has
# already been shipped. Nothing in a version bump would otherwise notice: `pin --refresh`
# rewrites the *local* lock, and no CI anywhere runs on a repository that has not upgraded.
if [ -f "$root/docs/schema/lock-v1.schema.json" ]; then
  say "ok    lock-v1.schema.json is still served (locks in the wild name that URL)"
else
  bad "docs/schema/lock-v1.schema.json is gone; published schema URLs are permanent"
  say "      restore it -- it is named by every lock any released flixw has written"
fi

# ...and the publisher must take them by glob, or a bump silently stops serving the old one.
# Anchored on the loop itself, not on the string appearing anywhere: the first version of
# this check also matched the glob inside pages.sh's own error message, so it went on
# passing after the loop had been changed to publish exactly one file.
if grep -qE '^for f in .*docs/schema/lock-v\*\.schema\.json' "$root/tests/pages.sh"; then
  say "ok    tests/pages.sh publishes every schema version, not just the current"
else
  bad "tests/pages.sh must publish docs/schema/lock-v*.schema.json, not one version"
fi


# docs/schema/ is what GitHub Pages serves, and what every generated lock points an editor
# at with its `#:schema` line. A schema describing a lock flixw no longer writes is worse
# than none, because an editor presents it as authority -- so it is generated, never
# edited, and the committed copy is diffed against what stage 0 emits.
if java "$root/src/stage0/flixw.java" wrapper --schema >"$work/schema.json" 2>"$work/schema.log"; then
  if cmp -s "$work/schema.json" "$root/docs/schema/lock-$schema_version.schema.json"; then
    say "ok    docs/schema/lock-$schema_version.schema.json matches wrapper --schema"
  else
    bad "docs/schema/lock-$schema_version.schema.json is stale; regenerate it:"
    say "      java src/stage0/flixw.java wrapper --schema > docs/schema/lock-$schema_version.schema.json"
    diff "$root/docs/schema/lock-$schema_version.schema.json" "$work/schema.json" || true
  fi
else
  bad "wrapper --schema did not run"
  cat "$work/schema.log"
fi

# --- 8. the API docs build ------------------------------------------------
# These are published to GitHub Pages from a tag, where a malformed doc comment is not a
# warning anybody sees -- it silently swallows the text around it. `<version>` and
# `<owner>/<repo>` read as HTML tags and disappeared from four comments that way, so the
# check runs the doclint groups that catch it and treats a warning as a failure.
#
# The `missing` group is deliberately off. It wants @param and @return on every one of
# ~100 package-private helpers, which is the "explain what the line does" documentation
# this repository's conventions reject; the groups left on are about comments being
# *wrong*, not about there being fewer of them than a tool would like.
if javadoc -private -quiet -Xdoclint:all,-missing -Xwerror \
        -d "$work/javadoc" -cp "$picocli" "$root/src/stage0/flixw.java" \
        "$root/src/assets/flixw-jdk.java" "$root/src/assets/flixw-setup.java" \
        "$root/src/assets/flixw-inspect.java" "$root/src/assets/flixw-help.java" \
        >"$work/javadoc.log" 2>&1; then
  say "ok    javadoc -private builds with no malformed doc comment"
else
  bad "javadoc"
  head -20 "$work/javadoc.log"
fi

# --- 8b. the stage 0 that actually ships -----------------------------------
# Projects commit .flixw/flixw.java into their own repositories, so what they receive is
# the documented source with its commentary removed -- generated at release time by
# tests/strip.java. That makes the readable artifact and the running one different files,
# which is only honest while anyone can regenerate the second from the first. These checks
# are what "reproducible" means here in practice.
version=$(sed -n 's/.*WRAPPER_VERSION = "\([^"]*\)".*/\1/p' "$root/src/stage0/flixw.java" | head -1)
# The file has to be named for the class it declares, or javac rejects it before reading a
# line -- which would report a stripper bug that is not there.
mkdir -p "$work/shipdir"
if java "$root/tests/strip.java" "$root/src/stage0/flixw.java" "$version" >"$work/shipdir/flixw.java" \
      2>"$work/strip.log"; then
  # Determinism first: everything below is worthless if two runs can differ.
  java "$root/tests/strip.java" "$root/src/stage0/flixw.java" "$version" >"$work/shipped2.java" 2>&1
  if cmp -s "$work/shipdir/flixw.java" "$work/shipped2.java"; then
    say "ok    the stripper is deterministic"
  else
    bad "tests/strip.java is not deterministic; the published bytes cannot be reproduced"
  fi

  # It has to be the same program. A stripper that ate a string literal, or read the
  # `"""` inside a comment as a text block, produces something javac rejects -- and the
  # release would ship it to every project that installed.
  if javac -d "$work/shipped-classes" "$work/shipdir/flixw.java" >"$work/shipped.log" 2>&1; then
    say "ok    the shipped stage 0 compiles"
  else
    bad "the stripped stage 0 does not compile"
    head -5 "$work/shipped.log"
  fi
  if [ -n "$floor" ]; then
    if javac --release "$floor" -d "$work/shipped-floor" "$work/shipdir/flixw.java" \
          >"$work/shipped-floor.log" 2>&1; then
      say "ok    the shipped stage 0 compiles at Java $floor"
    elif grep -q "release version $floor not supported" "$work/shipped-floor.log"; then
      say "skip  shipped floor check (this javac no longer targets $floor)"
    else
      bad "the stripped stage 0 no longer compiles at Java $floor"
    fi
  fi

  # Behaviour, not just syntax: --schema renders the lock format from LOCK_SCHEMA through
  # a string builder full of quotes and escapes, so it is the output most likely to notice
  # a stripper that mishandled a literal.
  if java "$work/shipdir/flixw.java" wrapper --schema >"$work/shipped-schema.json" 2>&1 \
     && cmp -s "$work/shipped-schema.json" "$root/docs/schema/lock-$schema_version.schema.json"; then
    say "ok    the shipped stage 0 emits the same lock schema"
  else
    bad "the shipped stage 0 emits a different lock schema than the documented one"
  fi

  # The header is the whole of what a vendored copy tells its reader, so it has to carry
  # the two places the commentary actually lives.
  missing=
  for url in "https://wstein.github.io/flixw/" "https://github.com/wstein/flixw"; do
    grep -qF "$url" "$work/shipdir/flixw.java" || missing="$missing $url"
  done
  if [ -z "$missing" ]; then
    say "ok    the shipped header names the docs and the source"
  else
    bad "the shipped stage 0 does not name:$missing"
  fi
else
  bad "tests/strip.java did not run"
  head -5 "$work/strip.log"
fi

# --- 9. cmd.exe line endings -----------------------------------------------
# CRLF is load-bearing for cmd.exe: a LF-only .cmd breaks multi-line if/for blocks.
if od -c "$root/src/stage0/flixw.cmd" | grep -q '\\r'; then
  say "ok    src/stage0/flixw.cmd has CRLF line endings"
else
  bad "src/stage0/flixw.cmd must have CRLF line endings"
fi

# --- 10. the size ratchet --------------------------------------------------
# Stage 0 is heading for a verified-launcher contract, and the three numbers below are
# how that shrink is held rather than intended. They are *ceilings at today's value*,
# not the target: a gate set at the target fails every commit until the last one, which
# means it gets commented out on the first day. Lower them as work lands; the advisory
# at the bottom says when they have gone slack.
#
# The line gate counts *code* lines, excluding blanks and comment-only lines. A gate on
# physical lines is a gate on comments, and comments are this repository's security
# story -- "audited by strangers who must trust it with a download" is not a style
# preference, it is the reason the density floor exists alongside the ceiling. The two
# pull against each other on purpose: neither can be met by sacrificing the other.
#
# Text blocks count as code. The shims embed shell `case` arms beginning with `*` and
# `/*`, which any leading-token classifier reads as javadoc -- so the density floor
# could otherwise be met by shipping more embedded shell, which is the opposite of what
# it is asking for.
MAX_CODE_LINES=3381          # a later gitattributes rule is read one attribute at a time,
                             # through git's macros; target: 2900
MIN_COMMENT_PCT=25           # floor, not a ceiling; today 33
MAX_BYTES=307654             # same capability; target: 225000
# The byte ceiling may move *up* when code lines move down and density moves up -- that is
# the two gates pulling against each other as intended, not drift. Refusing that would let
# them deadlock: any change trading code for the explanation this repository asks for would
# be unable to pass both.
#
# The code-line ceiling may rise only for a **new capability**, and the commit that raises
# it has to name the capability. Never for a refactor, a rewrite, or "it needed a helper" --
# those are the shapes drift arrives in, and a ratchet that cannot tell them apart is one
# that gets deleted the first time somebody has to ship a feature.

# shellcheck disable=SC2046  # deliberate: awk emits four bare integers to split on
set -- $(awk '
BEGIN { intb = 0 }
{
  line = $0; sub(/^[ \t]+/, "", line); n = length($0) + 1
  if (intb)                     { kl++; kb += n; if (line ~ /^"""/) intb = 0; next }
  if ($0 ~ /"""[ \t]*$/)        { kl++; kb += n; intb = 1; next }
  if (line == "")               { bl++; next }
  if (line ~ /^(\/\/|\*|\/\*)/) { cl++; next }
  kl++; kb += n
}
END { printf "%d %d %d %d\n", kl, cl, bl, kb+cb+bb }
' "$root/src/stage0/flixw.java")
code=$1; comments=$2; blanks=$3
bytes=$(wc -c < "$root/src/stage0/flixw.java" | tr -d ' ')
physical=$((code + comments + blanks))
density=$((comments * 100 / physical))

if [ "$code" -le "$MAX_CODE_LINES" ]; then
  say "ok    stage 0 is $code code lines (ceiling $MAX_CODE_LINES, target 2900)"
else
  bad "stage 0 grew to $code code lines; the ceiling is $MAX_CODE_LINES"
  say "      the target is 2900; raising the ceiling needs a reason"
fi

if [ "$density" -ge "$MIN_COMMENT_PCT" ]; then
  say "ok    comment density is $density% of $physical lines (floor $MIN_COMMENT_PCT%)"
else
  bad "comment density fell to $density%; the floor is $MIN_COMMENT_PCT%"
  say "      code shrinks by deleting subsystems, not by deleting the reasons for them"
fi

if [ "$bytes" -le "$MAX_BYTES" ]; then
  say "ok    src/stage0/flixw.java is $bytes bytes (ceiling $MAX_BYTES, target 225000)"
else
  bad "src/stage0/flixw.java grew to $bytes bytes; the ceiling is $MAX_BYTES"
fi

# A ratchet that is never tightened is a ceiling, and a ceiling well above the work is
# not a gate at all. Nudge, do not fail: the tightening is a deliberate edit, and it
# belongs in the commit that earned it rather than in whatever commit trips a threshold.
slack_lines=$((MAX_CODE_LINES - code))
slack_bytes=$((MAX_BYTES - bytes))
if [ "$slack_lines" -ge 100 ] || [ "$slack_bytes" -ge 5120 ]; then
  say "note  the ratchet has gone slack by $slack_lines lines / $slack_bytes bytes"
  say "      lower MAX_CODE_LINES to $code and MAX_BYTES to $bytes in tests/lint.sh"
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "lint: clean"
else
  say "lint: $fail failure(s)"
  exit 1
fi
