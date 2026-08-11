# helper — reserved, not built

This module is a placeholder. It contains no sources and ships nothing.

Every wrapper verb (`pin`, `doctor`, `setup`, `validate`, `update-wrapper`) is currently
implemented inside `src/flix.java`, where it costs no second release artifact, no second
digest to publish, and no second security-response path.

A separate `flixw-helper.jar` becomes justified only when that surface outgrows a single
auditable file — concretely, when stage 0 can no longer be read end to end in one sitting,
or when a verb needs a dependency the JDK does not provide. Introducing it earlier would
mean operating a release pipeline for exactly the class of artifact this project exists to
verify, which is a cost to accept deliberately and late rather than by default.

If that threshold is reached, the helper would be attached as a standalone asset to a
versioned `wstein/flixw` GitHub release. Its coordinates would be constants compiled into
`src/flix.java` — never project lock data — with `FLIXW_HELPER_JAR` as a development and
recovery override, and it would never appear on the compiler hot path.

Until then this directory holds only a build definition, so the decision stays cheap to
revisit.
