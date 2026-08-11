# services — reserved, not built

This module is a placeholder. It contains no sources, runs nothing, and ships nothing.

A *service* here is the implementation of one wrapper verb — the design paper's
`ProjectManifest`, `ReleaseMetadata`, `ToolchainDoctor` and `WrapperInstaller`. That is the
whole membership rule, and it is the point of the name: anything that is not a wrapper
verb's implementation does not belong here and can be argued out. Nothing in this module
would ever be a resident process. An optional JAR may implement wrapper verbs, but never
compiler launch, and never any part of the hot path. Paper Revision 6 calls this artifact a
*helper JAR*; it is the same thing under its earlier name, which suggested a running
companion and so described the one thing it must not be.

Every wrapper verb (`pin`, `doctor`, `setup`, `validate`, `update-wrapper`) is currently
implemented inside `src/flix.java`, where it costs no second release artifact, no second
digest to publish, and no second security-response path.

A separate `flixw-services.jar` becomes justified only when that surface outgrows a single
auditable file — concretely, when stage 0 can no longer be read end to end in one sitting,
or when a verb needs a dependency the JDK does not provide. Introducing it earlier would
mean operating a release pipeline for exactly the class of artifact this project exists to
verify, which is a cost to accept deliberately and late rather than by default.

If that threshold is reached, the JAR would be attached as a standalone asset to a
versioned `wstein/flixw` GitHub release. Its coordinates would be constants compiled into
`src/flix.java` — never project lock data — with `FLIXW_SERVICES_JAR` as a development and
recovery override, and it would never appear on the compiler hot path.

The plural is load-bearing. Services retire one at a time: when the pinned compiler's
captured verb set claims a formerly wrapper-owned command, dispatch rule 3 routes to Flix
and that service is deleted. Removing the last one removes a branch and nothing else — not
`./flix`, not compiler acquisition, not `.flix-wrapper/lock.toml`. A singular name cannot
describe a thing that dies in pieces.

The build is Scala because Flix's compiler is Scala 2.13. Each service is meant to be
portable upstream with its fixtures, if a maintainer ever wants it — which no one has
agreed to, so it stays a handoff design rather than an adoption claim.

Until then this directory holds only a build definition, so the decision stays cheap to
revisit.
