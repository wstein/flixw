#!/bin/sh
# flixw shim -- invariant file, byte-identical across projects for a wrapper release.
# Finds an initial java, prefers the compiled stage 0, else launches the source.
set -e
self=$0
while [ -L "$self" ]; do
  link=$(readlink "$self")
  case $link in /*) self=$link ;; *) self=$(dirname "$self")/$link ;; esac
done
# CDPATH is cleared for this command only: a set CDPATH makes `cd` resolve
# elsewhere and echo the result. shellcheck reads that as a typo (SC1007).
# shellcheck disable=SC1007
root=$(CDPATH= cd -- "$(dirname -- "$self")" && pwd -P)
src=$root/.flix-wrapper/flix.java

if [ -n "${FLIX_JAVA_HOME:-}" ]; then java0=$FLIX_JAVA_HOME/bin/java
elif [ -n "${JAVA_HOME:-}" ]; then java0=$JAVA_HOME/bin/java
else java0=$(command -v java 2>/dev/null || true); fi

if [ -z "$java0" ]; then
  echo "FLIXW003: no java executable found." >&2
  echo "          Flix needs Java 21+. Set JAVA_HOME or put java on PATH." >&2
  exit 127
fi
if [ ! -x "$java0" ]; then
  echo "FLIXW003: $java0 is not executable." >&2
  exit 126
fi
if [ ! -f "$src" ]; then
  echo "FLIXW009: missing $src" >&2
  exit 88
fi

# Content-keyed compiled stage 0.  Versioned interface with stage 0; see README.
if [ -n "${FLIX_CACHE_HOME:-}" ]; then cache=$FLIX_CACHE_HOME
else
  case $(uname -s) in
    Darwin) cache=$HOME/Library/Caches/flixw ;;
    *)      cache=${XDG_CACHE_HOME:-$HOME/.cache}/flixw ;;
  esac
fi
h=
if command -v shasum >/dev/null 2>&1; then h=$(shasum -a 256 "$src" 2>/dev/null | cut -d' ' -f1)
elif command -v sha256sum >/dev/null 2>&1; then h=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
elif command -v openssl >/dev/null 2>&1; then h=$(openssl dgst -sha256 -r "$src" 2>/dev/null | cut -d' ' -f1)
fi
if [ -n "$h" ] && [ -f "$cache/stage0/$h/flix.class" ]; then
  FLIXW_SOURCE=$src; export FLIXW_SOURCE
  exec "$java0" -cp "$cache/stage0/$h" flix "$@"
fi
exec "$java0" "$src" "$@"
