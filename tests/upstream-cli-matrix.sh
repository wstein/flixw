#!/bin/sh
# Historical regression matrix for tests/upstream-cli.sh: the detector, run unchanged over the
# CLI sources of every Flix release from 0.75.0 to 0.79.0 and over master at 7ac485c, must give
# the entry counts and the differences recorded for them.
#
#   sh tests/upstream-cli-matrix.sh
#
# Offline: the sources are committed under tests/upstream/history/<commit>/ in the layout of the
# detector's own cache, so the detector finds them there and makes no request. A `curl` that
# fails and logs every call stands in front of the real one, and the run ends by requiring that
# log to be empty. PROVENANCE.tsv names each revision and the digests of its two files;
# transitions.tsv records what is expected between neighbouring revisions.
#
# What the matrix holds fixed, per revision:
#   - the fixture bytes (sha256 against PROVENANCE.tsv),
#   - the legacy fingerprint's entry count (52/52/53/54/56/56/61/63/63/63/57, wstein/flixw#16),
#   - between neighbours: the exit code and the exact set of added and removed entries,
#   - what the fingerprint cannot see: 0.76.0 -> 0.76.1, 0.77.0 -> 0.78.0 and 0.78.0 -> 0.79.0
#     change behaviour and not one entry, so the detector is expected to say "unchanged" there,
#     with sources that differ; 0.75.0 -> 0.75.1 is unchanged because the sources are identical.
#
# The last section sabotages the detector and the fixtures one defect at a time and requires the
# matrix to fail, and to name the revision or pair the defect belongs to. A check that no
# mutation can fail is not part of this file.
#
# shellcheck disable=SC2016  # sed expressions below hold shell text that must not expand
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only, as in the other scripts
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
script=${UPSTREAM_CLI_SCRIPT:-$root/tests/upstream-cli.sh}
hist=${UPSTREAM_HISTORY_DIR:-$root/tests/upstream/history}
mkdir -p "$root/tests/.work"
t=$(mktemp -d "$root/tests/.work/upstream-cli-matrix.XXXXXX") || { echo "cannot create a scratch directory" >&2; exit 2; }
trap 'rm -rf "$t"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if command -v sha256sum >/dev/null 2>&1; then sum=sha256sum
elif command -v shasum >/dev/null 2>&1; then sum="shasum -a 256"
else echo "neither sha256sum nor shasum is installed" >&2; exit 2; fi
tab=$(printf '\t')

# Any request is a failure of the test, not of the network.
mkdir -p "$t/bin"
cat > "$t/bin/curl" <<'CURL'
#!/bin/sh
echo "$*" >> "$MATRIX_REQUESTS"
exit 6
CURL
chmod +x "$t/bin/curl"

# The paper trail of one matrix: <log> gets one "ok"/"FAIL" line per check, `mfail` the count.
# matrix <script> <history dir> <log>
matrix() {
  m_script=$1; m_hist=$2; m_log=$3
  : > "$m_log"
  mfail=0
  m_w=$(mktemp -d "$t/w.XXXXXX")
  m_req=$m_w/requests.log; : > "$m_req"
  mkdir -p "$m_w/work/cache"

  mok() { echo "ok    $1" >> "$m_log"; }
  mbad() { echo "FAIL  $1" >> "$m_log"; mfail=$((mfail + 1)); }
  mnote() { [ -z "${2:-}" ] || printf '      %s\n' "$2" >> "$m_log"; }

  # One check of the detector. FLIX_UPSTREAM_API/RAW point nowhere, so a request cannot succeed
  # by accident; the PATH stub records it.
  mrun() { # <fingerprint> <args...>
    m_fp=$1; shift
    mrc=0
    mout=$(PATH=$t/bin:$PATH MATRIX_REQUESTS=$m_req FLIX_UPSTREAM_WORK=$m_w/work \
           FLIX_UPSTREAM_FINGERPRINT=$m_fp FLIX_UPSTREAM_API=http://127.0.0.1:9 \
           FLIX_UPSTREAM_RAW=http://127.0.0.1:9 sh "$m_script" "$@" 2>&1) || mrc=$?
  }
  field() { # <label> <column number> -> the column of the PROVENANCE row
    awk -F "$tab" -v l="$1" -v c="$2" '$0 !~ /^#/ && $1 == l { print $c }' "$m_hist/PROVENANCE.tsv"
  }

  labels=$(awk -F "$tab" '$0 !~ /^#/ { print $1 }' "$m_hist/PROVENANCE.tsv")

  # 1. The inputs are what PROVENANCE says they are, and the issue's counts are the ones recorded.
  seq=
  for l in $labels; do
    seq="$seq $(field "$l" 3)"
    c=$(field "$l" 2)
    mdig=$(cd "$m_hist/$c" && $sum Main.scala | cut -d' ' -f1)
    odig=$(cd "$m_hist/$c" && $sum Options.scala | cut -d' ' -f1)
    echo "$mdig $odig" > "$m_w/dig.$l"
    if [ "$mdig" = "$(field "$l" 5)" ] && [ "$odig" = "$(field "$l" 7)" ]; then
      mok "$l fixture digests match PROVENANCE"
    else
      mbad "$l fixture digests differ from PROVENANCE"
    fi
    mkdir -p "$m_w/work/cache/$c"
    cp "$m_hist/$c/Main.scala" "$m_hist/$c/Options.scala" "$m_hist/$c/SHA256SUMS" "$m_w/work/cache/$c/"
  done
  if [ "$seq" = " 52 52 53 54 56 56 61 63 63 63 57" ]; then
    mok "recorded entry counts are 52/52/53/54/56/56/61/63/63/63/57"
  else
    mbad "recorded entry counts are$seq, not 52 52 53 54 56 56 61 63 63 63 57"
  fi

  # 2. The legacy fingerprint of each revision has the recorded number of entries.
  for l in $labels; do
    c=$(field "$l" 2); want=$(field "$l" 3)
    mrun "$m_w/fp.$l" --update --to "$c"
    got=$("$m_grep" -vc '^#' "$m_w/fp.$l" 2>/dev/null || true)
    rev=$("$m_grep" -c "^# Revision: $c\$" "$m_w/fp.$l" 2>/dev/null || true)
    if [ "$mrc" = 0 ] && [ "$got" = "$want" ] && [ "$rev" = 1 ]; then
      mok "$l fingerprint has $want entries"
    else
      mbad "$l fingerprint: rc=$mrc entries=$got want $want, revision lines $rev"
      mnote x "$mout"
    fi
  done

  # 3. Neighbouring revisions: exit code, exact changes, and the class of the pair.
  while IFS="$tab" read -r from to want_rc class changes; do
    case $from in '#'*|'') continue ;; esac
    fc=$(field "$from" 2); tc=$(field "$to" 2)
    mrun "$m_w/fp.none" --from "$fc" --to "$tc"
    got=$(printf '%s\n' "$mout" | "$m_grep" -E '^[-+]' | LC_ALL=C sort | paste -sd'|' -)
    [ -n "$got" ] || got=-
    want=$(printf '%s\n' "$changes" | tr '|' '\n' | LC_ALL=C sort | paste -sd'|' -)
    why=
    [ "$mrc" = "$want_rc" ] || why="rc=$mrc want $want_rc"
    [ "$got" = "$want" ] || why="$why changes '$got' want '$want'"
    # The bytes actually present, not the digests PROVENANCE claims for them.
    read -r fm fo < "$m_w/dig.$from"; read -r tm to_ < "$m_w/dig.$to"
    same_main=no; [ "$fm" != "$tm" ] || same_main=yes
    same_opts=no; [ "$fo" != "$to_" ] || same_opts=yes
    case $class in
      identical) [ "$same_main:$same_opts" = yes:yes ] || why="$why sources differ" ;;
      blind)     [ "$same_main:$same_opts" != yes:yes ] || why="$why sources are identical" ;;
      detected)  [ "$want_rc" = 1 ] || why="$why class detected without exit 1" ;;
      *)         why="$why unknown class $class" ;;
    esac
    if [ -z "$why" ]; then
      mok "$from -> $to is $class (exit $want_rc)"
    else
      mbad "$from -> $to ($class):$why"
    fi
  done < "$m_hist/transitions.tsv"

  # 4. What the legacy fingerprint does not contain. These are the blind spots of wstein/flixw#16;
  # the checks fail when the detector starts to see them, which is the moment to extend the
  # matrix (a deliberate change) and not a regression.
  mp=$(field master-preview 2)
  for l in v0.77.0 v0.79.0 master-preview; do [ -f "$m_w/fp.$l" ] || : > "$m_w/fp.$l"; done
  if "$m_grep" -q '^opt SveMode Xsve$' "$m_w/fp.master-preview" \
     && ! "$m_grep" -q '^enum SveMode ' "$m_w/fp.master-preview"; then
    mok "master: Xsve is an option, its SveMode enum (outside Options.scala) is not an entry"
  else
    mbad "master: expected opt SveMode Xsve and no SveMode enum entry"
  fi
  for l in v0.77.0 v0.79.0; do
    n=$("$m_grep" -c 'eff-check' "$m_w/fp.$l" || true)
    if [ "$n" = 1 ]; then mok "$l: eff-check is one entry; its target arity is not in the fingerprint"
    else mbad "$l: eff-check has $n entries, want 1"; fi
  done
  if [ "$("$m_grep" -c 'Xperf' "$m_w/fp.master-preview" || true)" = 1 ]; then
    mok "master: Xperf is one entry; its --seq/--all/--par scope is not in the fingerprint"
  else mbad "master: Xperf should be one entry"; fi
  [ -n "$mp" ] || mbad "master-preview is not in PROVENANCE.tsv"

  # 5. Nothing was fetched.
  if [ ! -s "$m_req" ]; then mok "no request was made"; else mbad "requests were made: $(tr '\n' ' ' < "$m_req")"; fi
}
m_grep=$(command -v grep)

pass=0; failed=0
tally() { # <log>
  while IFS= read -r line; do
    case $line in
      ok*) pass=$((pass + 1)); echo "$line" ;;
      FAIL*) failed=$((failed + 1)); echo "$line" ;;
      *) echo "$line" ;;
    esac
  done < "$1"
}

matrix "$script" "$hist" "$t/real.log"
tally "$t/real.log"

# --- sabotage ----------------------------------------------------------------
# Each variant breaks one thing. The matrix must fail, and its log must name <expected>.
variants=0
sabotage() { # <label> <expected log text> <script|fixture> ...set up by the caller in $t/v.*
  variants=$((variants + 1))
  s_label=$1; s_expect=$2; s_script=$3; s_hist=$4
  matrix "$s_script" "$s_hist" "$t/v$variants.log"
  if [ "$mfail" -gt 0 ] && "$m_grep" -Fq -- "FAIL  $s_expect" "$t/v$variants.log"; then
    pass=$((pass + 1)); echo "ok    sabotage fails the matrix: $s_label"
  else
    failed=$((failed + 1)); echo "FAIL  sabotage is not caught as '$s_expect': $s_label"
    sed 's/^/      /' "$t/v$variants.log"
  fi
}
mutant_script() { # <label> <sed expression>: a copy of the script, which must differ from it
  sed "$2" "$script" > "$t/v$((variants + 1)).sh"
  if cmp -s "$script" "$t/v$((variants + 1)).sh"; then
    failed=$((failed + 1)); echo "FAIL  the sabotage '$1' did not change the script"
  fi
}
mutant_hist() { # prints the path of a fresh copy of the fixtures
  d=$t/h$((variants + 1)); cp -R "$hist" "$d"; printf '%s' "$d"
}
# A fixture edited together with its SHA256SUMS: the detector's own cache check passes, so
# only PROVENANCE.tsv and the expected results can notice.
redigest() { (cd "$1" && $sum Main.scala Options.scala > SHA256SUMS); }
c_075_2=$("$m_grep" -E "^v0.75.2$tab" "$hist/PROVENANCE.tsv" | cut -f2)
c_076_0=$("$m_grep" -E "^v0.76.0$tab" "$hist/PROVENANCE.tsv" | cut -f2)
c_076_1=$("$m_grep" -E "^v0.76.1$tab" "$hist/PROVENANCE.tsv" | cut -f2)

# The detector stops reading argument builders.
mutant_script "arg builders dropped" 's|extract_surface "\$1/Main.scala" > "\$work/surface"|extract_surface "$1/Main.scala" \| grep -v "^arg " > "$work/surface"|'
sabotage "arg builders dropped" "v0.75.0 fingerprint" "$t/v$((variants + 1)).sh" "$hist"
# The detector stops reading the enum case objects of Options.scala.
mutant_script "enum members dropped" 's|LC_ALL=C sort -u "\$work/surface" "\$work/enums" > "\$2"|LC_ALL=C sort -u "$work/surface" > "$2"|'
sabotage "enum members dropped" "v0.75.0 fingerprint" "$t/v$((variants + 1)).sh" "$hist"
# Duplicate entries are kept instead of folded.
mutant_script "duplicates kept" 's|LC_ALL=C sort -u "\$work/surface" "\$work/enums" > "\$2"|LC_ALL=C sort "$work/surface" "$work/enums" > "$2"|'
sabotage "duplicates kept" "v0.75.0 fingerprint" "$t/v$((variants + 1)).sh" "$hist"
# The detector reports every comparison as unchanged.
mutant_script "every comparison unchanged" 's|if \[ "\$drc" -eq 0 \]; then|if true; then|'
sabotage "every comparison unchanged" "v0.75.1 -> v0.75.2" "$t/v$((variants + 1)).sh" "$hist"
# The detector ignores the committed sources and goes to the network.
mutant_script "cache ignored" 's|if cache_ok "\$srcdir"; then return; fi|:|'
sabotage "cache ignored" "requests were made" "$t/v$((variants + 1)).sh" "$hist"

# A fixture loses the entry its revision adds.
d=$(mutant_hist)
"$m_grep" -v 'Xnewmono' "$d/$c_075_2/Main.scala" > "$d/x" || true; mv "$d/x" "$d/$c_075_2/Main.scala"; redigest "$d/$c_075_2"
sabotage "0.75.2 fixture without Xnewmono" "v0.75.2 fingerprint" "$script" "$d"
# 0.76.1 is replaced by the bytes of 0.76.0: the "sources differ" half of a blind pair is gone.
d=$(mutant_hist)
cp "$d/$c_076_0/Main.scala" "$d/$c_076_1/Main.scala"; redigest "$d/$c_076_1"
sabotage "0.76.1 fixture equal to 0.76.0" "v0.76.0 -> v0.76.1 (blind): sources are identical" "$script" "$d"
# An expected change is dropped from the transition table.
d=$(mutant_hist)
"$m_grep" -v "^v0.75.1${tab}v0.75.2" "$d/transitions.tsv" > "$d/x"
printf 'v0.75.1\tv0.75.2\t1\tdetected\t-\n' >> "$d/x"; mv "$d/x" "$d/transitions.tsv"
sabotage "expected change missing from transitions.tsv" "v0.75.1 -> v0.75.2" "$script" "$d"
# The master baseline is declared blind.
d=$(mutant_hist)
sed "s/^v0.79.0${tab}master-preview${tab}1${tab}detected/v0.79.0\tmaster-preview\t0\tblind/" "$d/transitions.tsv" > "$d/x"; mv "$d/x" "$d/transitions.tsv"
sabotage "master declared blind" "v0.79.0 -> master-preview" "$script" "$d"
# A recorded count is wrong.
d=$(mutant_hist)
sed "s/^\(v0.76.1${tab}[0-9a-f]*${tab}\)56/\157/" "$d/PROVENANCE.tsv" > "$d/x"; mv "$d/x" "$d/PROVENANCE.tsv"
sabotage "recorded count of 0.76.1 is 57" "recorded entry counts" "$script" "$d"

echo
echo "upstream-cli-matrix: $pass passed, $failed failed"
[ "$failed" -eq 0 ]
