#!/bin/sh
# Pins that a release carries the LICENSE of its tag, byte for byte (Apache-2.0 section 4(a)
# asks for a copy when the work is passed on):
#
#   sh tests/license-check.sh                      the scripts: tests/pack.sh and release.yaml
#   sh tests/license-check.sh --archives <dir>     the output of `sh tests/pack.sh <dir>`
#   sh tests/license-check.sh --root <dir> ...     a tree other than this checkout (mutations)
#
# The script mode needs neither zip nor the network, so tests/lint.sh can run it on every
# commit. It reads script text only: a precheck, not the guarantee. The guarantee is the
# archive mode on a real `tests/pack.sh` output and the cases in tests/run.sh (heavy lane). pack.sh itself needs both (it fetches picocli), so the archive mode is run by
# hand or after a pack; it compares .flixw/LICENSE in the tarball and in the zip, and the
# LICENSE asset, with the repository's LICENSE, and wants the asset in SHA256SUMS.
# shellcheck disable=SC2016  # the patterns are literal script text, not expansions
set -eu

# shellcheck disable=SC1007  # CDPATH is cleared for this command only
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
archives=
usage() { echo "usage: sh tests/license-check.sh [--root <dir>] [--archives <dir>]" >&2; exit 2; }
while [ $# -gt 0 ]; do
  case $1 in
    --root) [ $# -ge 2 ] && [ -n "$2" ] || usage; root=$2; shift 2 ;;
    --archives) [ $# -ge 2 ] && [ -n "$2" ] || usage; archives=$2; shift 2 ;;
    *) usage ;;
  esac
done
# shellcheck disable=SC1007
root=$(CDPATH= cd -- "$root" && pwd -P)

fail=0
say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }

pack=$root/tests/pack.sh
rel=$root/.github/workflows/release.yaml
[ -f "$root/LICENSE" ] || bad "LICENSE is missing from the repository"

# --- the scripts -------------------------------------------------------------
# Inside .flixw, not at the top: an archive is extracted over a project root, and a top-level
# LICENSE would replace the adopting project's own.
# Comment lines do not count, and the stage copy must come before the tar line.
code=$(sed '/^[[:space:]]*#/d' "$pack")
stage_line=$(printf '%s\n' "$code" | grep -nF 'cp "$root/LICENSE" "$stage/.flixw/LICENSE"' | head -1 | cut -d: -f1)
tar_line=$(printf '%s\n' "$code" | grep -nF 'tar $tar_flags -cf -' | head -1 | cut -d: -f1)
if [ -n "$stage_line" ] && [ -n "$tar_line" ] && [ "$stage_line" -lt "$tar_line" ]; then
  say "ok    pack.sh stages LICENSE as .flixw/LICENSE before the archives are made"
else
  bad "tests/pack.sh does not copy LICENSE into the staging tree's .flixw/ before the tar step (neither archive carries it)"
fi
if printf '%s\n' "$code" | grep -Fq 'cp "$root/LICENSE" "$out/LICENSE"'; then
  say "ok    pack.sh publishes LICENSE as a release asset"
else
  bad "tests/pack.sh does not copy LICENSE into the output directory (no LICENSE asset)"
fi
if printf '%s\n' "$code" | sed -n '/(cd "\$out" && sum/,/> SHA256SUMS)/p' | grep -Eq '(^|[[:space:]])LICENSE([[:space:]]|$)'; then
  say "ok    pack.sh lists LICENSE in SHA256SUMS"
else
  bad "tests/pack.sh does not list LICENSE in SHA256SUMS"
fi
# release.yaml uploads the whole output directory, so the asset list is pack.sh's output.
if grep -Fq 'gh release upload "$TAG" dist/*' "$rel" && grep -Fq 'gh release create "$TAG" dist/*' "$rel"; then
  say "ok    release.yaml uploads all of dist/, LICENSE with it"
else
  bad ".github/workflows/release.yaml no longer uploads dist/* (LICENSE would not be an asset)"
fi

# --- the packed output -------------------------------------------------------
if [ -n "$archives" ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT INT TERM
  for f in "$archives"/flixw-*.tar.gz; do
    if tar -xzf "$f" -O .flixw/LICENSE 2>/dev/null | cmp -s - "$root/LICENSE"; then
      say "ok    $(basename "$f") carries .flixw/LICENSE, identical to LICENSE"
    else
      bad "$(basename "$f") lacks .flixw/LICENSE or it differs from LICENSE"
    fi
    # The mode is compared with its neighbour's rather than pinned: a chmod after the cp
    # would survive every byte comparison.
    lm=$(tar -tvzf "$f" .flixw/LICENSE 2>/dev/null | awk '{print substr($1,1,10)}')
    jm=$(tar -tvzf "$f" .flixw/flixw.java 2>/dev/null | awk '{print substr($1,1,10)}')
    if [ -n "$lm" ] && [ "$lm" = "$jm" ]; then
      say "ok    $(basename "$f"): .flixw/LICENSE has the mode of .flixw/flixw.java ($lm)"
    else
      bad "$(basename "$f"): .flixw/LICENSE mode differs from .flixw/flixw.java ('$lm' against '$jm')"
    fi
  done
  for f in "$archives"/flixw-*.zip; do
    if command -v unzip >/dev/null 2>&1; then
      if unzip -p "$f" .flixw/LICENSE 2>/dev/null | cmp -s - "$root/LICENSE"; then
        say "ok    $(basename "$f") carries .flixw/LICENSE, identical to LICENSE"
      else
        bad "$(basename "$f") lacks .flixw/LICENSE or it differs from LICENSE"
      fi
      lm=$(unzip -Z "$f" .flixw/LICENSE 2>/dev/null | awk '{print substr($1,1,10)}')
      jm=$(unzip -Z "$f" .flixw/flixw.java 2>/dev/null | awk '{print substr($1,1,10)}')
      if [ -n "$lm" ] && [ "$lm" = "$jm" ]; then
        say "ok    $(basename "$f"): .flixw/LICENSE has the mode of .flixw/flixw.java ($lm)"
      else
        bad "$(basename "$f"): .flixw/LICENSE mode differs from .flixw/flixw.java ('$lm' against '$jm')"
      fi
    else
      say "skip  $(basename "$f"): unzip is not installed"
    fi
  done
  if cmp -s "$archives/LICENSE" "$root/LICENSE"; then
    say "ok    the LICENSE asset is identical to LICENSE"
  else
    bad "the LICENSE asset is missing or differs from LICENSE"
  fi
  if grep -Eq '^[0-9a-f]{64}  LICENSE$' "$archives/SHA256SUMS" 2>/dev/null; then
    want=$(grep '  LICENSE$' "$archives/SHA256SUMS" | cut -d' ' -f1)
    if command -v sha256sum >/dev/null 2>&1; then got=$(sha256sum "$archives/LICENSE" | cut -d' ' -f1)
    else got=$(shasum -a 256 "$archives/LICENSE" | cut -d' ' -f1); fi
    if [ "$want" = "$got" ]; then say "ok    SHA256SUMS covers LICENSE with its digest"
    else bad "SHA256SUMS names a different digest for LICENSE"; fi
  else
    bad "SHA256SUMS does not list LICENSE"
  fi
fi

if [ "$fail" -eq 0 ]; then say "license-check: ok"; else say "license-check: $fail failure(s)"; exit 1; fi
