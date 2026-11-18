#!/bin/sh
# Pins that a release carries the LICENSE of its tag, byte for byte (Apache-2.0 section 4(a)
# asks for a copy when the work is passed on):
#
#   sh tests/license-check.sh                      the scripts: tests/pack.sh and release.yaml
#   sh tests/license-check.sh --archives <dir>     the output of `sh tests/pack.sh <dir>`
#   sh tests/license-check.sh --root <dir> ...     a tree other than this checkout (mutations)
#
# The script mode needs neither zip nor the network, so tests/lint.sh can run it on every
# commit. pack.sh itself needs both (it fetches picocli), so the archive mode is run by
# hand or after a pack; it compares .flixw/LICENSE in the tarball and in the zip, and the
# LICENSE asset, with the repository's LICENSE, and wants the asset in SHA256SUMS.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
archives=
while [ $# -gt 0 ]; do
  case $1 in
    --root) root=$(CDPATH= cd -- "$2" && pwd -P); shift 2 ;;
    --archives) archives=$2; shift 2 ;;
    *) echo "usage: sh tests/license-check.sh [--root <dir>] [--archives <dir>]" >&2; exit 2 ;;
  esac
done

fail=0
say() { printf '%s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }

pack=$root/tests/pack.sh
rel=$root/.github/workflows/release.yaml
[ -f "$root/LICENSE" ] || bad "LICENSE is missing from the repository"

# --- the scripts -------------------------------------------------------------
# Inside .flixw, not at the top: an archive is extracted over a project root, and a top-level
# LICENSE would replace the adopting project's own.
if grep -Fq 'cp "$root/LICENSE" "$stage/.flixw/LICENSE"' "$pack"; then
  say "ok    pack.sh stages LICENSE as .flixw/LICENSE, so both archives carry it"
else
  bad "tests/pack.sh does not copy LICENSE into the staging tree's .flixw/ (neither archive carries it)"
fi
if grep -Fq 'cp "$root/LICENSE" "$out/LICENSE"' "$pack"; then
  say "ok    pack.sh publishes LICENSE as a release asset"
else
  bad "tests/pack.sh does not copy LICENSE into the output directory (no LICENSE asset)"
fi
if sed -n '/(cd "\$out" && sum/,/> SHA256SUMS)/p' "$pack" | grep -Eq '(^|[[:space:]])LICENSE([[:space:]]|$)'; then
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
  done
  for f in "$archives"/flixw-*.zip; do
    if command -v unzip >/dev/null 2>&1; then
      if unzip -p "$f" .flixw/LICENSE 2>/dev/null | cmp -s - "$root/LICENSE"; then
        say "ok    $(basename "$f") carries .flixw/LICENSE, identical to LICENSE"
      else
        bad "$(basename "$f") lacks .flixw/LICENSE or it differs from LICENSE"
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
    [ "$want" = "$got" ] && say "ok    SHA256SUMS covers LICENSE with its digest" \
      || bad "SHA256SUMS names a different digest for LICENSE"
  else
    bad "SHA256SUMS does not list LICENSE"
  fi
fi

if [ "$fail" -eq 0 ]; then say "license-check: ok"; else say "license-check: $fail failure(s)"; exit 1; fi
