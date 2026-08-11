# An Experimental Repository Bootstrapper for Flix

## Design, Trade-offs, and Evaluation Plan for `flixw`

**Werner Stein**  
**Design paper — Revision 4**  
**11 August 2026**

## Abstract

Flix already integrates compilation, dependency management, testing, packaging, publishing, and language-server support in one JVM application. A project can declare a compiler version in `flix.toml`, although current documentation says that the field is not yet used [2]. Earlier revisions of this paper proposed an official wrapper generator for every Flix project. This revision withdraws that recommendation. Flix is a rapidly changing pre-1.0 language whose Community Build deliberately keeps downstream projects near compiler head. Universal exact pinning could reduce that migration signal, accumulate breaking changes in dormant repositories, and transfer upgrade costs from continuous integration to users.

The revised proposal is an opt-in third-party experiment, `wstein/flixw`. It targets projects that explicitly prefer reproducibility over automatic head tracking and must work with an unmodified stock compiler JAR published by `github.com/flix/flix`. A small single-file Java bootstrap verifies and installs a versioned `flixw-helper.jar`; that helper verifies and installs the separately pinned stock `flix.jar`, implements diagnostics and maintenance commands, and finally launches the compiler as an opaque process. Convenience scripts only locate Java and delegate. Generated non-executable `.flix-wrapper.toml` metadata pins both artifacts by URL and digest. Artifacts are cached by version and digest. Hashing occurs during installation and optionally on every run, avoiding an impossible universal 50 ms budget.

The paper separates evidence from proposal, defines falsifiable experiments over several Flix release cycles, and narrows upstream requests to two independently useful changes: publish checksums or signed release attestations, and warn when `[package].flix` differs from the running compiler. Official wrapper adoption is explicitly deferred until the experiment demonstrates demand, maintainability, and compatibility with Flix's upgrade culture.

**Keywords:** Flix, bootstrap wrapper, reproducibility, pre-1.0 languages, Java helper, supply chain, toolchain pinning

## 1. Revised Position

The useful question is no longer “How should Flix generate wrappers for every repository?” It is:

> For which Flix projects does an exact repository-local compiler pin provide more value than the upgrade pressure it removes?

The existing `wstein/flix-invaders` repository contains a short `bin/flix` convenience script used in continuous integration [16]. It demonstrates demand for version-directed acquisition but is not the general artifact proposed here. Its simple extractor, fixed temporary filename, missing digest check, and opaque Java errors supply adverse test cases. Its Windows CI also runs the POSIX script under Git Bash, demonstrating that a second PowerShell implementation is not automatically justified.

The experiment will therefore live independently as `wstein/flixw`, under a permissive license and its own release cadence. Flix maintainers would not initially own its templates, Windows behavior, security response, or compatibility policy. `flixw` shall not require a Flix fork, compiler plugin, patched manifest parser, new compiler command, or unreleased branch. It treats the official release JAR as an opaque CLI process. If adoption and measurements later justify upstream work, the implementation can inform a compiled Flix launcher rather than obligating the compiler repository to vendor an unproven script system.

## 2. Effect on a Pre-1.0 Language's Upgrade Pressure

Flix released 26 versions during the twelve months ending 11 August 2026, approximately one release every two weeks. Versions 0.74.0 and 0.75.0 were separated by five days. The core team is small and development is concentrated. Breaking changes are normal rather than exceptional; recent work has removed language features. Flix mitigates this through its Community Build, which compiles a set of downstream repositories against a fresh compiler during compiler changes.

Universal per-repository pinning would alter this feedback mechanism. A project pinned to an old compiler can remain green while its compatibility with current Flix silently decays. When it eventually upgrades, failures from many releases arrive together, potentially against a compiler version the core team no longer uses. Pinning therefore changes who pays migration cost and when.

The Flix repository itself provides a cautionary example: its committed Mill launcher has carried a default Mill version different from `.mill-version`. This does not prove wrappers are undesirable, but it demonstrates that generated version declarations drift even in actively maintained repositories.

`flixw` consequently adopts four policy constraints:

- Exact pinning is opt-in, not the default recommendation for all Flix projects.
- Exactness is an integrity property: a digest identifies one immutable byte sequence and cannot safely authenticate a floating range.
- Projects participating in Community Build should continue testing against current Flix even when their release workflow uses an exact pin.
- The experiment must measure upgrade lag and accumulated migration cost across several release cycles.

`flix = "latest"` is not introduced in the first experiment. A floating channel and an exact digest express different trust and update models. If head-tracking support is later required, it should be a separately named policy with an explicit resolution record, not a value that appears reproducible while changing over time.

## 3. Launcher Taxonomy and Related Work

### 3.1 Repository launchers

Gradle, Maven, Mill, and JBang can place launch material in a repository [6]–[9]. Their scripts generally read deliberately simple version data. Gradle keeps substantial logic in `gradle-wrapper.jar` and variable distribution data in properties. Maven Wrapper 3.2.0 made its script-only mode the default, reducing committed binary risk. Mill uses a fixed version directive or `.mill-version`; its current Flix-repository launcher is the live precedent for the intended audience.

Digest verification is not uniformly enabled in these tools. Gradle and Maven support it but do not make every checksum pin automatic; Mill's common bootstrap script performs a normal HTTPS download without an independent artifact digest. Mandatory digest pinning would therefore be a security contribution of `flixw`, not merely imitation of an established default.

### 3.2 Global compiled muxers

Language toolchains commonly use globally installed compiled selectors: Go, rustup proxies, the .NET host, Bazelisk, Corepack, uv, and Coursier. A compiled selector can safely parse a rich project manifest, implement roll-forward policies, provision runtimes, and centralize security updates without regenerating scripts in every repository.

Corepack is particularly relevant because `packageManager` may combine an exact version with a hash. That model works because a compiled global shim parses the manifest. The .NET host demonstrates project-scoped search paths and custom error messages. Bazelisk exposes a sanctioned project hook, illustrating that composition points sometimes matter more than opaque forwarding.

### 3.3 Coursier and JBang

Coursier and JBang are the strongest JVM-native alternatives. Coursier already provides artifact resolution, a shared cache, mirrors, proxies, offline behavior, checksum handling through Maven repositories, and managed JVM acquisition. JBang supports repository wrappers and can provision a JDK. If Flix issue #13002 results in a current Maven Central artifact, a thin Coursier-based launcher could replace most compiler acquisition logic [18].

Neither alternative is zero-install by itself unless its launcher is also bootstrapped, and no current Maven Central coordinate exists for Flix 0.75.2. Nevertheless, Maven publication is now a decisive design fork rather than an incidental transport improvement. `flixw` should not reimplement features that become reliably delegable to Coursier.

### 3.4 Why a Java bootstrap and helper JAR

Java 21 or newer is already a Flix prerequisite [1], [5]. Java's source-file launcher can execute a small `.java` bootstrap without a committed binary or explicit compile step. That bootstrap has one security-critical job: validate and install the pinned `flixw-helper.jar`, then invoke it. The helper JAR provides TOML handling, SemVer validation, HTTPS policy, proxy behavior, SHA-256, atomic moves, path handling, diagnostics, and shared tests. This keeps the committed bootstrap auditable while allowing the richer implementation to be packaged and tested normally. Windows users can invoke the same bootstrap directly:

```console
java flixw.java test
```

Git Bash may offer `./flix test` as a convenience on Windows. A tiny optional `flix.cmd` can cover common arguments but is never described as byte-for-byte equivalent. PowerShell is not a required implementation because enterprise execution policy can make workspace scripts administratively unrunnable.

## 4. Experimental Architecture

### 4.1 Repository files

```text
flix.toml                 # human-maintained Flix project manifest
flix                      # small POSIX Java-discovery shim
flixw                     # small POSIX helper-command shim
flixw.java                # byte-identical stage-0 helper bootstrap
.flix-wrapper.toml        # generated project-specific lock metadata
.gitattributes            # line-ending rules merged idempotently
flix.cmd                  # optional, non-normative convenience shim
```

The sidecar uses TOML, avoiding a second configuration format. It is generated lock metadata rather than a second human-maintained compiler declaration:

```toml
wrapperVersion = "0.1.0"

[helper]
version = "0.1.0"
url = "https://github.com/wstein/flixw/releases/download/v0.1.0/flixw-helper.jar"
sha256 = "<published-helper-digest>"

[compiler]
version = "0.75.2"
url = "https://github.com/flix/flix/releases/download/v0.75.2/flix.jar"
sha256 = "a2697d875725a0dde6e793b8d54cb220e86167a6d49ec5f0ccb0832966c8c15a"
```

`flix.toml` remains the human authority for the compiler version. The sidecar repeats it so the helper can detect drift before downloading anything, while independently pinning the helper implementation. A mismatch produces an actionable diagnostic and never enters a download/retry loop.

`flixw-helper.jar` is distributed as a standalone asset attached to a versioned `wstein/flixw` GitHub Release. It is not published or resolved as a Maven package and introduces no package-manager dependency. Its release model intentionally mirrors stock Flix: one directly executable JAR, one stable release URL convention, and one published SHA-256 or attestation. The bootstrap treats the helper and compiler as independent executable release artifacts.

### 4.2 Wrapper identity and validation

`flix`, `flixw`, `flixw.java`, and an optional `flix.cmd` are byte-identical across repositories for a given wrapper release. Upstream or `wstein/flixw` can therefore publish one hash per wrapper file and version. Project-specific URL and digest data live only in `.flix-wrapper.toml`; updating a compiler pin changes data, not executable code.

Published wrapper hashes apply to canonical release bytes. `.gitattributes` establishes `/flix text eol=lf`, `/flixw text eol=lf`, `/flixw.java text eol=lf`, and optional `/flix.cmd text eol=crlf`. Validation distinguishes Git blob identity from checked-out canonical bytes. The generator owns a marked block in `.gitattributes`, replaces that block idempotently, preserves unrelated rules, and fails if a later rule overrides the wrapper paths.

### 4.3 Version and integrity policy

The initial experiment supports exact versions only. Exactness is not a temporary omission; it is what makes a pinned digest meaningful. The generated sidecar binds the tuple:

```text
(wrapper version, Flix version, distribution URL, SHA-256)
```

GitHub's release API currently reports a SHA-256 for the single `flix.jar` asset. For 0.75.2 the field matches the observed cached JAR. This proves that the metadata is populated, not that it supplies independent authenticity: the hash and bytes share GitHub and TLS as their trust anchor, and an asset can be replaced. The initial model is therefore trust on first generation. A signed release attestation or independently published checksum is the preferred upstream improvement.

### 4.4 Content-addressed cache

Both artifacts use version-plus-digest cache identities:

```text
<cache>/helpers/flixw-helper-<version>-<sha256>.jar
<cache>/compilers/flix-<version>-<sha256>.jar
```

Projects using different mirrors or historical digests coexist. No project removes another project's cache entry. A cold setup installs the helper first and the stock compiler second. Each download uses a uniquely created temporary file in the destination directory, verifies SHA-256, and atomically renames it. Concurrent identical writers converge safely without locks.

Verification occurs at installation. A stamp records digest, size, modification time, and file identity where available. Warm startup validates the stamp; `FLIX_VERIFY_ALWAYS=1` recomputes SHA-256 on every run for CI or a stronger local threat model. The paper no longer asserts a universal 50 ms budget before platform measurements.

### 4.5 Java selection

Selection order is:

1. If `FLIX_JAVA_HOME` is set, validate it and hard-fail if incompatible.
2. Otherwise, if `JAVA_HOME` is set, validate it and hard-fail if incompatible.
3. Otherwise, select compatible `java` on `PATH`.
4. Otherwise, inspect known installations and select a compatible candidate.
5. Otherwise, fail with a concise Java requirement.

The launcher first reads `$JAVA_HOME/release` or the platform equivalent and executes only the selected JVM when a reliable version cannot be obtained. It does not fork every candidate. No LTS ranking exists. The experiment assumes an existing JDK and states its benefit honestly: contributors who already have Java can skip installing Flix. Managed JDK acquisition is delegated to Coursier or JBang in a separate experiment, not silently implemented by `flixw`.

### 4.6 Project selection and working directory

Project search starts at the caller's current working directory, not at the wrapper file. It chooses the nearest ancestor containing `flix.toml`, never walks above the directory containing the wrapper unless `FLIX_PROJECT_ROOT` explicitly selects another root, and hard-stops at the filesystem root or home directory. `.git` may be a directory or file; GitHub archives have no `.git`, so the wrapper anchor remains the primary boundary.

The wrapper does not change the caller's working directory. Relative paths and output locations retain ordinary CLI meaning. Root discovery selects configuration only. Flix issue #11150 remains the proper place for native compiler upward-search behavior [12].

### 4.7 Streams, terminal, and arguments

The launcher inherits stdin, stdout, stderr, and terminal handles. It allocates no console, buffers no child stream, and writes bootstrap diagnostics only to stderr. The no-argument Flix REPL therefore retains interactive input, line editing, and terminal-sensitive colour. Redirection such as `./flix run >out.txt` contains only compiler or program stdout.

`--wrapper-version` and wrapper help execute without a manifest, network, compiler JAR, or compatible project. All compiler arguments otherwise pass through the Java implementation unchanged. `./flix -- <args>` forces pass-through when a future compiler argument collides with a launcher-level flag.

### 4.8 Setup and updates

Project creation remains a global-tool operation because a repository wrapper cannot exist before the repository:

```console
flix init my-project
```

The experimental wrapper is installed or refreshed independently:

```console
flixw install .
flixw update-wrapper .
flixw pin 0.76.0 .
flixw validate .
```

`pin` resolves the target once, writes a new `.flix-wrapper.toml`, and updates `[package].flix` transactionally through a real TOML implementation. It never rewrites executable wrapper code. A failed acquisition is attempted at most once per invocation.

The stock-compiler entry point remains opaque:

```console
./flix check
./flix test
./flix run
```

Capabilities not implemented by stock Flix use a separate helper surface routed through the verified helper JAR:

```console
./flixw setup
./flixw doctor
./flixw pin 0.76.0
./flixw validate
./flixw update-wrapper
```

This avoids claiming that current stock Flix implements `setup` or `wrapper`, and avoids reserving future compiler verbs in `./flix`. If Flix later adopts an equivalent command, the helper can deprecate its spelling and delegate to the compiler.

`./flixw setup` is the explicit two-artifact preparation step. Stage 0 verifies or installs `flixw-helper.jar`; the helper then verifies or installs the exact stock `flix.jar` declared by the manifest and lock. It reports both versions and cache paths. Normal `./flix ...` execution follows the same chain lazily, so setup is recommended for diagnostics and offline preparation but not mandatory after the lock is committed.

### 4.9 Stock-compiler compatibility boundary

Every required `flixw` operation is external to the compiler: project discovery, version extraction, lock validation, Java selection, compiler acquisition, caching, and process launch. After bootstrap, the selected stock `flix.jar` receives the user's compiler arguments. `flixw` neither injects classes into the compiler nor relies on private Flix APIs.

The current stock compiler need not honor `[package].flix`; `flixw` reads that declaration before startup and chooses the corresponding official release JAR. Proposed upstream checksum publication and compiler-version mismatch warnings are independent enhancements. Their absence may weaken provenance or diagnostics, but it must not make `flixw` unusable.

Compatibility is tested against official release artifacts from `github.com/flix/flix`, not against `wstein/flix-fork` or locally patched compilers. `FLIX_JAR` remains available for explicit development testing, but such runs are reported as overrides and do not count as stock-compatibility evidence.

### 4.10 Integration-ready helper boundary

Missing stock capabilities are implemented in `flixw-helper.jar` behind small services rather than embedded in shell or stage-0 control flow:

```text
ProjectManifest       read and transactionally update [package].flix
ReleaseMetadata       resolve official URL, digest, and optional attestation
ToolchainResolver     select Java and compiler cache entry
ToolchainDoctor       report project and machine readiness
WrapperInstaller      install, validate, and update invariant wrapper files
CompilerProcess       launch an opaque stock flix.jar with inherited I/O
```

The helper depends only on public files, release metadata, and process behavior. It does not link against compiler internals. Each service has fixtures and a transport-neutral result type so an accepted capability can later be reimplemented in the Flix repository's Scala code, exposed as an official command, and compared against the same behavioral corpus.

Project metadata is migration-stable. Upstream integration may read `[package].flix` and `.flix-wrapper.toml` directly or provide a one-time converter, but it must not require repositories to change their ordinary `./flix` commands. The external helper remains usable until an official release containing the replacement command is the project's pinned compiler.

## 5. Prototype Contract

The following requirements govern `wstein/flixw`; they are not demands placed on the Flix core team.

**P1 — Opt-in policy.** Installation requires an explicit project decision and documents its effect on upgrade pressure.

**P2 — Invariant executable artifacts.** Wrapper implementation files vary only by wrapper release; project data lives in `.flix-wrapper.toml`.

**P3 — Exact authenticated binding.** Flix version, URL, and digest form one generated lock record; manifest drift fails before network access.

**P4 — Staged Java implementation.** `flixw.java` owns only trusted stage-0 helper acquisition. The released `flixw-helper.jar` owns project parsing, compiler acquisition, cache policy, diagnostics, maintenance commands, I/O, and stock-compiler launch. Shell or Command Prompt files are convenience delegates only.

**P5 — Explicit Java precedence.** An incompatible explicit Java setting fails immediately rather than falling through.

**P6 — Content-addressed installation.** Cache identity includes SHA-256; installation is unique-temp-plus-atomic-move and lock-free.

**P7 — Transparent process behavior.** Working directory and three standard streams are inherited; bootstrap messages use stderr only.

**P8 — Offline inspection.** Wrapper version and help require no project, Java execution, compiler, or network. Cached projects work offline.

**P9 — Bounded failure.** One invocation performs at most one acquisition attempt for one resolved artifact.

**P10 — Observable errors.** Stable `FLIXWnnn` strings are the normative discriminator. Numeric exit codes are advisory because a compiler or user program may return the same integer.

**P11 — Stock Flix compatibility.** All required behavior shall work with unmodified official `github.com/flix/flix` release JARs. Upstream proposals are optional improvements, never runtime prerequisites.

**P12 — Integration-ready helpers.** Missing compiler capabilities shall be exposed through the separate `flixw` helper, decomposed into portable services, and tested without private Flix APIs so they can be adopted upstream incrementally.

## 6. Security Model

The threat model distinguishes three objects:

- **Wrapper source:** validated against a hash published for the `flixw` release.
- **Helper artifact:** `flixw-helper.jar`, independently pinned and verified from a `wstein/flixw` GitHub Release.
- **Generated lock metadata:** reviewed as project data and checked for internal consistency with `flix.toml`.
- **Compiler artifact:** verified against the digest recorded during trusted generation.

GitHub-provided asset hashes are generation-time TOFU, not signed provenance. The preferred upstream change is a release checksum file plus a build-provenance attestation verifiable independently of asset download. Maven Central publication would add repository checksums and publication signatures, while Coursier could delegate resolution and JVM provisioning.

`FLIX_JVM_OPTS` accepts only a documented allow-list in the experiment. Options that select another JAR, load an argument file, inject agents, or execute error handlers are rejected unless an explicit unsafe mode is chosen. `_JAVA_OPTIONS` and `JAVA_TOOL_OPTIONS` are reported because they can modify execution and prepend messages to stderr.

The VS Code extension is deliberately excluded. Executing a workspace-provided wrapper on folder open would cross the Workspace Trust boundary. The useful upstream editor changes are a project compiler-version setting, an explicit JAR path, and digest verification—not automatic execution of repository scripts.

## 7. Evidence and Falsifiable Evaluation

### 7.1 Evidence to date

- `flix-invaders` demonstrates the practical need for version-directed acquisition and supplies concrete failure fixtures [16].
- Its Windows workflow demonstrates that Git Bash may eliminate the need for a separate Windows implementation in CI.
- The 0.75.2 GitHub asset digest matches the locally observed JAR, establishing API availability but not independent authenticity.
- No proposed stage-0 bootstrap or `flixw-helper.jar` artifact has yet been validated in production.

### 7.2 Questions that can fail

1. Does opt-in pinning reduce reproducibility failures without causing unacceptable upgrade lag over at least six Flix releases?
2. Does the parser accept every public `flix.toml` used in the evaluation corpus and reject ambiguous compiler declarations?
3. Does warm overhead, measured separately on Linux, macOS, Git Bash, and direct Windows Java, remain acceptable relative to `java -jar`?
4. Do content-addressed cache entries coexist across mirrors and historical digests without deletion or repeated downloads?
5. Does the REPL preserve TTY behavior, colour, signals, stdin, stdout, and stderr?
6. Can the Community Build continue testing pinned projects against current compiler head?
7. If Maven Central publication lands, does a Coursier implementation make custom acquisition code unnecessary?
8. Does every supported operation work against stock Flix release JARs without patched compiler code or private APIs?
9. Can each helper service be ported or delegated upstream without changing project commands or invalidating existing lock metadata?

Measurements include cold time, warm overhead, bytes transferred, hash time, recovery after truncation, upgrade lag, accumulated migration changes, corpus acceptance, stream fidelity, and argument vectors. Lines of code and committed files are costs.

## 8. Implementation and Evaluation Plan

### Phase 0 — Establish upstream-independent policy

1. Create `wstein/flixw` under a permissive license.
2. State that it is experimental, third-party, opt-in, and not an official Flix tool.
3. Record current release cadence and Community Build participation.
4. Ask Flix only about release checksums/attestations, running-version mismatch diagnostics, and Maven publication.

**Gate:** the prototype can proceed without an unanswered maintainer decision.

### Phase 1 — Define deterministic artifacts

1. Specify canonical bytes and hashes for `flix`, `flixw`, `flixw.java`, and optional `flix.cmd`.
2. Specify `.flix-wrapper.toml` and its consistency rules.
3. Implement idempotent marked-block merging for `.gitattributes` and detect later overrides.
4. Define wrapper release and upgrade policy.

**Gate:** independent generation with identical inputs produces identical wrapper files and lock metadata.

### Phase 2 — Implement stage 0 and the helper JAR

1. Implement the small `flixw.java` bootstrap with only the lock parsing necessary to locate and verify `flixw-helper.jar`.
2. Publish `flixw-helper.jar` as a standalone GitHub Release asset with SHA-256 and, when available, attestation.
3. Implement full TOML handling, exact version validation, build metadata handling, and path-safety checks in the helper.
4. Implement root selection without changing cwd.
5. Implement stable diagnostics and advisory exit codes.

**Gate:** stage 0 cannot execute an unverified helper, and the helper's public-manifest corpus matches a standards-compliant TOML oracle.

### Phase 3 — Implement Java and process behavior

1. Implement explicit Java precedence and hard-fail semantics.
2. Read release metadata before executing the selected JVM where possible.
3. Preserve cwd, arguments, terminal, and all three standard streams.
4. Handle offline `--wrapper-version`, help, and forced pass-through first.

**Gate:** REPL, redirection, signals, Unicode, empty arguments, and relative paths match direct invocation.

### Phase 4 — Implement acquisition and cache

1. Implement HTTPS-only bounded download and proxy behavior.
2. Store artifacts under version-plus-digest identity.
3. Verify on installation and implement optional always-verify mode.
4. Use unique temporary files and atomic move without deletion of divergent valid entries.

**Gate:** concurrent, corrupt, truncated, mirror-divergent, and offline cases terminate safely with at most one acquisition attempt.

### Phase 5 — Implement installer and pin transaction

1. Implement `flixw install`, `setup`, `doctor`, `update-wrapper`, `pin`, and `validate` in `flixw-helper.jar`.
2. Make `pin` update `flix.toml` and `.flix-wrapper.toml` as one recoverable transaction.
3. Ensure no command self-modifies executable wrapper source.
4. Preserve unrelated `.gitattributes` content.
5. Separate manifest, release, resolver, doctor, installer, and process services behind fixture-tested interfaces.

**Gate:** injected failures at every write boundary leave either the old consistent pair or the new consistent pair.

### Phase 6 — Cross-platform experiment

1. Test POSIX launchers on Linux, macOS, and Git Bash.
2. Test direct `java flixw.java` on Windows without PowerShell.
3. Evaluate optional `flix.cmd`; reject it if any supported vector is silently altered.
4. Derive platform-specific warm-overhead budgets from measurement.

**Gate:** normative POSIX and direct-Java paths preserve the contract; optional shims fail explicitly on unsupported vectors.

### Phase 7 — Multi-release field study

1. Use `flixw` in `flix-invaders` and several structurally different Flix projects.
2. Continue Community Build or head-compiler testing alongside exact release pins.
3. Record each Flix release, pin lag, wrapper maintenance, and migration work for at least six releases.
4. Publish the complete Appendix B result matrix, including failures.
5. Run the primary matrix against official stock release artifacts; label every `FLIX_JAR` override separately.

**Gate:** the study supplies enough evidence to accept, revise, or abandon repository pinning for each project class.

### Phase 8 — Reassess architecture

1. Compare custom acquisition with Maven Central plus Coursier if #13002 progresses.
2. Decide whether managed JDK delegation is worth adding.
3. Decide whether Flix documentation should list `flixw` as an optional community tool.
4. Propose official ownership only if maintainers request it after field evidence.
5. For each useful helper capability, prepare a small upstream-ready Scala port or delegation design with the same fixtures and no dependency on `flixw` internals.

**Gate:** every retained component has measured value that is not more cheaply supplied by Flix, Coursier, JBang, or a global launcher.

## 9. Minimal Upstream Proposals

### 9.1 Publish release integrity metadata

Publish `flix.jar.sha256` with each release and preferably generate a signed build-provenance attestation. This benefits Homebrew, nixpkgs, editor downloads, mirrors, and any launcher. It is useful even if `flixw` is abandoned.

### 9.2 Warn on compiler-version mismatch

When a project manifest declares `[package].flix`, compare it with the running compiler and emit an actionable warning or error according to an explicit policy. This gives every project diagnostic value from the existing field without requiring universal bootstrap scripts or automatic freezing.

### 9.3 Keep editor acquisition explicit

The editor should offer an explicit compiler version or JAR-path setting and verify downloaded artifacts. It should not execute a repository wrapper merely because a folder was opened.

## 10. Deliberately Not Implemented

- Official `flix init` wrapper generation: premature before adoption evidence.
- Compiler forks, plugins, or private APIs: `flixw` must remain compatible with stock Flix releases.
- Pretending helper commands already exist in stock Flix: missing behavior stays under `./flixw` until an official release adopts it.
- Universal exact pinning: potentially harmful to pre-1.0 migration feedback.
- Floating `latest`: incompatible with a stable digest without a separate resolution policy.
- Shell or PowerShell TOML parsers: one Java implementation is sufficient.
- Mandatory PowerShell or Batch: direct Java and Git Bash are the normative experiment paths.
- Automatic JDK installation: delegate experimentally to Coursier or JBang.
- Per-run hashing by default: available through `FLIX_VERIFY_ALWAYS=1`.
- Workspace-wrapper LSP execution: conflicts with editor trust boundaries.
- Numeric exit-code uniqueness: impossible for arbitrary user programs.
- Manifest-format constraints imposed for wrapper convenience.

## 11. Risks and Adverse Evidence

The largest risk is policy rather than code: exact pins may weaken the feedback loop that helps a small team evolve Flix quickly. The field study must be allowed to conclude that some or all projects should track head instead.

The second risk is maintenance duplication. If Flix reaches Maven Central, Coursier could make custom compiler download, cache, proxy, mirror, and JDK logic unjustifiable. The helper should isolate compiler acquisition so it can be deleted while preserving the GitHub-release distribution of `flixw-helper.jar` itself.

Single-file Java still requires a compatible Java installation. The proposal no longer calls this pure zero-install bootstrapping. Its immediate promise is narrower: a contributor who already has Java can clone a project and avoid separately installing Flix.

Release digests supplied by the same host as release bytes are not independent authenticity. Until attestations or signatures exist, generated pins are TOFU. Wrapper hashes protect known released source files but cannot protect a malicious first installation.

Finally, the experiment has no running implementation evidence yet. The correct next move is to build `flixw`, exercise it over the actual Flix release cadence, and publish failures rather than continue expanding normative prose.

## 12. Conclusion

Repository-local compiler pinning is not an unconditional improvement for a fast-moving pre-1.0 language. It trades reproducibility for reduced upgrade pressure, and that trade must be measured against Flix's Community Build and release cadence. Revision 4 therefore withdraws the proposal that Flix generate wrappers universally.

The remaining experiment is smaller and more testable: an opt-in third-party `flixw`, a minimal single-file Java bootstrap, a released helper JAR, an unmodified stock Flix JAR, invariant wrapper source, TOML lock metadata, version-plus-digest caches, explicit Java precedence, faithful process I/O, and no editor or JDK-provisioning overreach. The Flix team can independently improve release attestations and compiler-version mismatch diagnostics without adopting the wrapper.

If several release cycles show that `flixw` reduces real onboarding and reproducibility failures without accumulating migration debt, it may earn documentation or deeper integration. If Coursier or a global Flix launcher makes it redundant, deleting the custom layer is the successful outcome.

## References

[1] Flix Project, “Getting Started,” *Programming Flix*. https://doc.flix.dev/getting-started.html. Accessed: Aug. 11, 2026.

[2] Flix Project, “Package Management,” *Programming Flix*. https://doc.flix.dev/packages.html. Accessed: Aug. 11, 2026.

[3] Flix Project, “Build and Package Management,” *Programming Flix*. https://doc.flix.dev/build-and-packages.html. Accessed: Aug. 11, 2026.

[4] Flix Project, “Publishing a Package on GitHub,” *Programming Flix*. https://doc.flix.dev/publish.html. Accessed: Aug. 11, 2026.

[5] Flix Project, “Interoperability with Java,” *Programming Flix*. https://doc.flix.dev/interoperability.html. Accessed: Aug. 11, 2026.

[6] Gradle Inc., “Gradle Wrapper,” *Gradle User Manual*. https://docs.gradle.org/current/userguide/gradle_wrapper.html. Accessed: Aug. 11, 2026.

[7] Gradle Inc., “Validate the Gradle Distribution SHA-256 Checksum,” *Best Practices for Security*. https://docs.gradle.org/current/userguide/best_practices_security.html. Accessed: Aug. 11, 2026.

[8] Apache Maven, “Maven Wrapper.” https://maven.apache.org/wrapper/. Accessed: Aug. 11, 2026.

[9] Mill Project, “Installation & IDE Setup.” https://mill-build.org/mill/cli/installation-ide.html. Accessed: Aug. 11, 2026.

[10] P. Butcher, “Discussion: project and dependency management,” Flix issue #4380, July 27, 2022. https://github.com/flix/flix/issues/4380.

[11] J. Schneider (`jaschdoc`), “Meta Issue: Tracking the Future of the Flix Package Manager,” Flix issue #11436, Aug. 27, 2025. https://github.com/flix/flix/issues/11436.

[12] M. Lutze, “CLI: detect toml in parent directory,” Flix issue #11150, July 29, 2025. https://github.com/flix/flix/issues/11150.

[13] M. Madsen, “Improvements to TOML formatter,” Flix issue #12208, Dec. 30, 2025. https://github.com/flix/flix/issues/12208.

[14] Oracle, “Oracle Java SE Support Roadmap.” https://www.oracle.com/java/technologies/java-se-support-roadmap.html. Accessed: Aug. 11, 2026.

[15] OpenJDK Project, “JEP 330: Launch Single-File Source-Code Programs.” https://openjdk.org/jeps/330. Accessed: Aug. 11, 2026.

[16] W. Stein, “flix-invaders,” GitHub repository. https://github.com/wstein/flix-invaders. Accessed: Aug. 11, 2026.

[17] chengjilai, “Self-contained flix toolchain: manage the JDK and pin/fetch the compiler version like Go's go.mod toolchain,” Flix issue #13003, Aug. 10, 2026. https://github.com/flix/flix/issues/13003.

[18] chengjilai, “Publish the Flix compiler to Maven Central,” Flix issue #13002, Aug. 10, 2026. https://github.com/flix/flix/issues/13002.

[19] O. Weiler (`helpermethod`), “Publish to brew/Docker/SDKMAN! via JReleaser,” Flix issue #4561, Sept. 14, 2022. https://github.com/flix/flix/issues/4561.

[20] Coursier Project, “Java Development Kit Management.” https://get-coursier.io/docs/cli-java. Accessed: Aug. 11, 2026.

[21] JBang Project, “JBang Documentation.” https://www.jbang.dev/documentation/guide/latest/. Accessed: Aug. 11, 2026.

[22] GitHub, “Using artifact attestations to establish provenance for builds.” https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations. Accessed: Aug. 11, 2026.

## Appendix A. Normative Prototype Pseudocode

```text
# Stage 0: invariant flixw.java bootstrap
wrapper_root := directory_containing(flixw.java)
lock := parse_only_helper_record(wrapper_root / ".flix-wrapper.toml")
helper := helper_cache / artifact_name(lock.helper.version, lock.helper.sha256)
if not valid_install_stamp(helper, lock.helper.sha256):
    temp := unique_temp_in(helper_cache)
    download_once(lock.helper.url, temp) or fail(FLIXW005)
    sha256(temp) == lock.helper.sha256 or fail(FLIXW006)
    atomic_move(temp, helper) or accept_identical_concurrent_winner() or fail(FLIXW007)
exec_or_spawn(current_java, "-jar", helper, invocation_mode, original_arguments)

# flixw-helper.jar: project and compiler behavior
if argv is ["--wrapper-version"] or helper help:
    print offline metadata
    exit 0

root := env("FLIX_PROJECT_ROOT")
     or nearest_manifest_ancestor(cwd, upper_anchor = wrapper_root,
                                  stop = [home, filesystem_root])
     or fail(FLIXW001)
manifest_version := parse_package_flix(root / "flix.toml")
lock := parse_generated_lock(root / ".flix-wrapper.toml")
manifest_version == lock.compiler.version
    or fail(FLIXW002, "run: ./flixw pin " + manifest_version)

java := validate_current_or_select_relaunch_target()
     or fail_explicit(FLIXW004)
     or fail_implicit(FLIXW003)

if env("FLIX_JAR") is set:
    compiler := validate_override(env("FLIX_JAR")) or fail(FLIXW006)
else:
    compiler := compiler_cache / artifact_name(lock.compiler.version,
                                                lock.compiler.sha256)
    install_verified_once_if_missing(lock.compiler, compiler)

inherit_cwd_and_stdio()
exec_or_spawn(java, validated_jvm_options, "-jar", compiler,
              passthrough_arguments)
```

Every Appendix C identifier has a reachable site above. Numeric codes are advisory; the identifier printed to stderr is normative.

## Appendix B. Evaluation Matrix

| Area | Required cases |
|---|---|
| Upgrade policy | six releases; head CI retained; pin lag; accumulated migration effort; abandoned project |
| Manifest | comments; whitespace; dotted/quoted keys; four string forms; multiline decoy; duplicate; ambiguity; public corpus |
| Lock | missing; manifest mismatch; URL mismatch; digest mismatch; deterministic generation; interrupted pair update |
| Helper release | GitHub Release URL; helper digest; corrupt helper; offline helper hit/miss; helper upgrade independent of compiler |
| Root and cwd | subdirectory; nested manifest; `.git` directory/file; archive without Git; home/root stop; override; relative input/output |
| Java | each explicit source; incompatible explicit hard-fail; PATH; discovery; release-file parsing; selected-process fallback |
| Cache | separate helper/compiler caches; content-address coexistence; hit; miss; corrupt; truncated; concurrent; offline; mirror; always-verify mode |
| I/O | no-argument REPL; stdin; stdout redirect; stderr separation; TTY; colour; signal/Ctrl-C; no extra console |
| Arguments | empty; spaces; quotes; Unicode; leading hyphen; forced pass-through; optional Command shim failures |
| Wrapper files | canonical hashes; LF; executable bit; archive recovery; idempotent `.gitattributes`; overriding rule detection |
| Security | one attempt per artifact; helper-before-compiler verification; HTTPS downgrade; proxy; timeout; attestation; unsafe JVM option; `_JAVA_OPTIONS` notice |
| Platforms | Linux; macOS ARM/Intel; Git Bash on Windows; direct Java on Windows; optional `flix.cmd` |

## Appendix C. Stable Diagnostics

| Identifier | Advisory exit | Reachable condition |
|---|---:|---|
| `FLIXW001` | 80 | no project manifest within the bounded search |
| `FLIXW002` | 81 | manifest and generated lock are missing, invalid, ambiguous, or inconsistent |
| `FLIXW003` | 82 | no compatible implicit Java installation |
| `FLIXW004` | 83 | explicitly selected Java is invalid or incompatible |
| `FLIXW005` | 84 | the single helper or compiler acquisition attempt failed |
| `FLIXW006` | 85 | helper, local override, or downloaded compiler failed digest validation |
| `FLIXW007` | 86 | cache or atomic installation failed without an identical winner |
| `FLIXW008` | 87 | wrapper environment, JVM option, or launcher flag is invalid |
| `FLIXW009` | 88 | wrapper installation, pin, validation, or lock transaction failed |

A Flix program may return any integer, including 80–88. Automation distinguishes bootstrap failures by the `FLIXWnnn` stderr identifier, not by assuming globally unique numeric status values.
