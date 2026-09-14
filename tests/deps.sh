#!/bin/sh
# Shared locations for dependencies fetched by contributor and release scripts.
#
# Version selection belongs to the consumer (stage 0 for picocli); pack.sh remains the
# authoritative digest pin. This file owns only the repository path so those two facts cannot
# drift across lint, the regression fixture and release packaging.

picocli_url() {
  if [ "$#" -ne 1 ]; then
    echo "usage: picocli_url <version>" >&2
    return 2
  fi
  printf '%s\n' "https://wstein.github.io/picocli/maven/io/github/wstein/picocli/$1/picocli-$1.jar"
}
