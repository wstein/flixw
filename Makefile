.DEFAULT_GOAL := help

.PHONY: help lint check test pages pack

help:
	@printf '%s\n' \
	  'make lint             fast local gate (the normal pre-commit check)' \
	  'make check            alias for lint' \
	  'make test             full regression suite; normally CI-gated' \
	  'make pages OUT=<dir>  build the documentation site' \
	  'make pack OUT=<dir>   build release archives'

lint:
	sh tests/lint.sh

check: lint

test:
	sh tests/run.sh

pages:
	sh tests/pages.sh "$(OUT)"

pack:
	sh tests/pack.sh "$(OUT)"
