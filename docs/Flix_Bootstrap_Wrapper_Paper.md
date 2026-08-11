# An Experimental Repository Bootstrapper for Flix

## Design, Trade-offs, and Evaluation Plan for `flixw`

**Werner Stein**  
**Design paper — Revision 6**  
**11 August 2026**

## Abstract

Flix already integrates compilation, dependency management, testing, packaging, publishing, and language-server support in one JVM application. A project can declare a compiler version in `flix.toml`, although current documentation says that the field is not yet used [2]. Earlier revisions of this paper proposed an official wrapper generator for every Flix project. This revision withdraws that recommendation. Flix is a rapidly changing pre-1.0 language whose Community Build deliberately keeps downstream projects near compiler head. Universal exact pinning could reduce that migration signal, accumulate breaking changes in dormant repositories, and transfer upgrade costs from continuous integration to users.

The revised proposal is an opt-in third-party experiment, `wstein/flixw`. Its load-bearing claim is that a minimal, auditable, dependency-free stage-0 bootstrap can verify, install, and execute an exact unmodified stock compiler JAR published by `github.com/flix/flix`. Every operation uses one entry point, `./flix`. The dispatcher gives compiler verbs precedence, forwards unknown verbs to the compiler, and handles a wrapper verb only when the pinned compiler does not implement it. Initial wrapper verbs may live directly in `.flixw/flix.java`; a helper JAR is introduced only if later functionality cannot remain small. Thus `./flix check` is always shim → stage 0 → stock `flix.jar`, and scaffolding retires automatically one verb at a time as Flix adopts equivalent commands.

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

### 3.4 Why stage 0 is Java

Java 21 or newer is already a Flix prerequisite [1], [5]. Since Java 11, JEP 330 has allowed a source file to be executed directly with `java File.java`; Java 21 makes this a practical scripting substrate with the standard HTTP client, filesystem, process, hashing, records, and pattern-matching APIs available without a build tool. The working prototype demonstrates that one Java source file can parse the compiler lock, detect manifest drift, download over HTTPS, verify SHA-256, populate a content-addressed cache atomically, and execute the stock compiler. It uses ordinary Java 21 syntax: preview unnamed classes, `--enable-preview`, shebang-specific behavior, and JBang are deliberately unnecessary. Source launch costs approximately 375 ms per invocation in the measured prototype, whereas the same code precompiled and cached starts in approximately 50 ms. Stage 0 therefore compiles itself into the user cache once and executes the cached form thereafter. No second distributed bootstrap artifact is required. Windows users can invoke the source entry directly for first contact:

```console
java .flixw/flix.java test
```

Git Bash offers `./flix test` on Windows. The small `flix.cmd` shim works from both Command Prompt and PowerShell; arguments transformed by `cmd.exe` remain documented deviations. `java .flixw/flix.java test` is the universal Windows fallback and does not depend on PowerShell execution policy.

## 4. Experimental Architecture

### 4.1 Repository files

```text
flix.toml                 # human-maintained Flix project manifest
flix                      # small POSIX Java-discovery shim
flix.cmd                  # minimal cmd.exe trampoline
.gitattributes            # line-ending rules merged idempotently
.flixw/
  flix.java               # byte-identical stage-0 dispatcher/bootstrap
  lock.toml               # committed compiler-only lock metadata
```

`.flixw/` is committed wrapper metadata, not a cache or machine-local directory. Compiler JARs and compiled stage-0 classes remain in the user cache. Dedicated root pollution is therefore limited to `flix` and `flix.cmd`; `.gitattributes` is shared repository infrastructure.

The sidecar uses TOML, avoiding a second configuration format. It is generated lock metadata rather than a second human-maintained compiler declaration:

```toml
[compiler]
version = "0.75.2"
url = "https://github.com/flix/flix/releases/download/v0.75.2/flix.jar"
sha256 = "a2697d875725a0dde6e793b8d54cb220e86167a6d49ec5f0ccb0832966c8c15a"
```

`flix.toml` remains the human authority for the compiler version. The committed `.flixw/lock.toml` repeats it so stage 0 can detect drift before downloading anything. A mismatch produces an actionable diagnostic and never enters a download/retry loop.

Wrapper verbs initially fit inside stage 0. A standalone `flixw-helper.jar` may later be attached to a versioned `wstein/flixw` GitHub Release if the integration surface outgrows the auditable bootstrap. Its coordinates would be wrapper-release constants in `.flixw/flix.java`, with `FLIXW_HELPER_JAR` as a development and recovery override. It is scaffolding, never project lock data and never part of the compiler hot path.

### 4.2 Wrapper identity and validation

`flix`, `flix.cmd`, and `.flixw/flix.java` are byte-identical across repositories for a given wrapper release. `wstein/flixw` can therefore publish one hash per file and version. Project-specific URL and digest data live only in `.flixw/lock.toml`; updating a compiler pin changes data, not executable code.

Published wrapper hashes apply to canonical release bytes. `.gitattributes` establishes `/flix text eol=lf`, `/.flixw/flix.java text eol=lf`, `/.flixw/lock.toml text eol=lf`, and `/flix.cmd text eol=crlf`. Validation distinguishes Git blob identity from checked-out canonical bytes. The installer owns a marked block, replaces it idempotently, preserves unrelated rules, and fails if a later rule overrides these paths. It also sets and verifies the POSIX executable bit and documents archive-download recovery.

### 4.3 Version and integrity policy

The initial experiment supports exact versions only. Exactness is not a temporary omission; it is what makes a pinned digest meaningful. The generated sidecar binds the tuple:

```text
(Flix version, distribution URL, SHA-256)
```

The accepted identifier grammar is:

```text
[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z](?:[0-9A-Za-z.-]*[0-9A-Za-z])?)?(?:\+[0-9A-Za-z](?:[0-9A-Za-z.-]*[0-9A-Za-z])?)?
```

Stage 0 independently rejects `..`, slash, backslash, whitespace, ranges, wildcards, empty suffixes, and leading `v`. Accepted examples include `0.75.2`, `0.76.0-rc.1`, `0.75.2+build.4`, and `1.2.3-beta.2+build.17`. Build metadata is accepted in the manifest but stripped from the canonical Flix release tag and compiler cache coordinate; the pinned digest still determines artifact identity. External utility operands use `--` wherever supported.

GitHub's release API currently reports a SHA-256 for the single `flix.jar` asset. For 0.75.2 the field matches the observed cached JAR. This proves that the metadata is populated, not that it supplies independent authenticity: the hash and bytes share GitHub and TLS as their trust anchor, and an asset can be replaced. The initial model is therefore trust on first generation. A signed release attestation or independently published checksum is the preferred upstream improvement.

### 4.4 Content-addressed cache and transport

`FLIX_CACHE_HOME` overrides the default cache. Defaults are outside tracked repository paths:

```text
Linux:   ${XDG_CACHE_HOME:-$HOME/.cache}/flixw
macOS:   $HOME/Library/Caches/flixw
Windows: %LOCALAPPDATA%\flixw
```

The compiler path is `<cache>/compilers/flix-<version>-<sha256>.jar`; a cached self-compiled stage 0 is stored separately by wrapper-source hash. Projects using different mirrors or historical digests coexist. Downloads use destination-local unique temporary files, unconditional SHA-256 verification, and atomic rename without locks. The measured 105 ms hash cost for the 33 MB JAR is approximately 2.5% of a representative warm `check`, so no install-stamp format or `FLIX_VERIFY_ALWAYS` mode is introduced.

`FLIX_DIST_URL` rewrites the approved distribution base while preserving the committed digest. `HTTPS_PROXY`, `https_proxy`, and `NO_PROXY` are honored. HTTPS-only initial and redirected protocols, bounded connect and total timeouts, and one acquisition attempt per artifact are mandatory.

### 4.5 Java selection

Selection order is:

1. If `FLIX_JAVA_HOME` is set, validate it and hard-fail if incompatible.
2. Otherwise, if `JAVA_HOME` is set, validate it and hard-fail if incompatible.
3. Otherwise, select compatible `java` on `PATH`.
4. Otherwise, inspect known installations and select a compatible candidate.
5. Otherwise, fail with a concise Java requirement.

Compatibility is the tested interval `[21, tested_ceiling]`. The shims locate a candidate sufficient to start `.flixw/flix.java`; Java owns precedence, validation, diagnostics, and at most one relaunch. It reads a candidate's `release` file when reliable and otherwise executes that candidate once. A stale or inconsistent release file cannot trigger another relaunch. If no Java executable exists, the shim emits the sole shim-owned diagnostic, `FLIXW003`. No LTS ranking exists. Managed JDK acquisition remains a separate Coursier or JBang experiment.

`FLIX_JVM_OPTS` uses one documented tokenizer implemented in Java. Options that replace the JAR, inject agents, load argument files, or install execution hooks require an explicit unsafe mode. `JAVA_TOOL_OPTIONS` and `_JAVA_OPTIONS` are reported because they can modify behavior and stderr.

### 4.6 Project selection and working directory

Stage 0 resolves the `.flixw/flix.java` file's symlink chain without physicalizing unrelated directory symlinks. Project search starts at the caller's current working directory, not at the wrapper file. It chooses the nearest ancestor containing `flix.toml`, never walks above the directory containing the resolved wrapper unless `FLIX_PROJECT_ROOT` explicitly selects another root, and hard-stops at the filesystem root or home directory. `.git` may be a directory or file; GitHub archives have no `.git`, so the wrapper anchor remains the primary boundary.

The wrapper does not change the caller's working directory. Relative paths and output locations retain ordinary CLI meaning. Root discovery selects configuration only. Flix issue #11150 remains the proper place for native compiler upward-search behavior [12].

### 4.7 Streams, terminal, and arguments

The launcher inherits stdin, stdout, stderr, and terminal handles. It allocates no console, buffers no child stream, and writes bootstrap diagnostics only to stderr. The no-argument Flix REPL therefore retains interactive input, line editing, and terminal-sensitive colour. Redirection such as `./flix run >out.txt` contains only compiler or program stdout.

`--wrapper-version` and `--wrapper-help` execute in stage 0 before project, lock, network, or compiler work. They require Java because stage 0 is Java; only “no Java executable” is handled by the shims. `./flix -- <args>` forces compiler pass-through.

### 4.8 Compiler-first dispatch

After installing and hashing the stock compiler, stage 0 captures `flix --help` once and stores one compiler verb per line beside the content-addressed JAR. The verb file is keyed by the compiler digest and cannot become stale. A future machine-readable command-list endpoint is an upstream convenience request; the prototype validates help parsing against every tested release.

Dispatch precedence is normative:

1. `./flix -- <args>` forwards everything after `--` to the compiler.
2. `--wrapper-version` and `--wrapper-help` are answered by stage 0.
3. If the first word is in the cached compiler verb set, invoke the compiler.
4. Otherwise, if it is a wrapper verb—`pin`, `doctor`, `setup`, `validate`, or `update-wrapper`—invoke the wrapper implementation.
5. Otherwise, invoke the compiler so Flix owns unknown-command diagnostics and future verbs.

`FLIX_BACKEND=wrapper` forces a wrapper implementation during a transition. When a newly pinned compiler claims a wrapper verb, compiler precedence takes effect automatically and stage 0 prints a one-release deprecation notice for the displaced wrapper implementation. The hot path is silent; a wrapper-handled command prints one routing line to stderr.

```text
flix: 'doctor' → wrapper 0.1.0 (pinned compiler 0.75.2 does not implement it)
```

`./flix --wrapper-help` reports the wrapper version, compiler version and digest, selected Java, cache path, compiler-owned verbs, wrapper fallbacks, and the forced pass-through spelling. This generated routing table is the primary transparency mechanism.

### 4.9 Installation, setup, and updates

Project creation remains a global-tool operation because a repository wrapper cannot exist before the repository:

```console
flix init my-project
```

First contact uses a downloaded, release-verified `flix.java` directly; its `install` mode creates `.flixw/`, writes the invariant files and compiler-only lock, and leaves only `flix` and `flix.cmd` as dedicated root entry files. Thereafter one entry point is used:

```console
java flix.java install .
./flix setup
./flix pin 0.76.0
./flix validate
./flix update-wrapper
```

`setup` exercises the same stage-0 compiler acquisition used by `check`, then reports Java, compiler, cache, mirror, proxy, and routing state. `pin` resolves the target once and updates `[package].flix` plus `.flixw/lock.toml` as a recoverable transaction. `update-wrapper` changes invariant wrapper files and any embedded helper constants, not the project compiler lock. `validate` checks canonical wrapper hashes, lock consistency, `.gitattributes`, executable mode, cache digest, and verb metadata.

### 4.10 Stock-compiler compatibility and helper retirement

Stage 0 performs project discovery, version extraction, drift detection, Java selection, compiler acquisition, digest verification, verb dispatch, and process launch. It neither injects classes into stock Flix nor relies on private APIs. The load-bearing path is therefore tested even if no helper JAR exists.

The current stock compiler need not honor `[package].flix`; stage 0 reads that declaration before startup and chooses the corresponding official release JAR. Proposed upstream checksum publication and compiler-version mismatch warnings are independent enhancements. Their absence may weaken provenance or diagnostics, but it must not make `flixw` unusable.

Compatibility is tested against official release artifacts from `github.com/flix/flix`, not against `wstein/flix-fork` or locally patched compilers. `FLIX_JAR` remains available for explicit development testing, but such runs are reported as overrides and do not count as stock-compatibility evidence.

If a helper JAR becomes necessary, it contains only wrapper-verb services:

```text
ProjectManifest       read and transactionally update [package].flix
ReleaseMetadata       resolve official URL, digest, and optional attestation
ToolchainDoctor       report project and machine readiness
WrapperInstaller      install, validate, and update invariant wrapper files
```

These services depend only on public files and release metadata. They have transport-neutral results and shared fixtures so each can be ported into Flix's Scala code. No maintainer has agreed to receive that port; this is a handoff design, not an adoption claim.

The retirement condition is per verb: when the pinned compiler's captured verb set contains a formerly wrapper-owned command and conformance fixtures pass, rule 3 routes to Flix. Deleting the last helper removes a branch only; it does not alter `./flix`, stage-0 compiler acquisition, or `.flixw/lock.toml`.

## 5. Prototype Contract

The following requirements govern `wstein/flixw`; they are not demands placed on the Flix core team.

**P1 — Opt-in policy.** Installation requires an explicit project decision and documents its effect on upgrade pressure.

**P2 — Invariant executable artifacts.** Wrapper implementation files vary only by wrapper release; project data lives in `.flixw/lock.toml`.

**P3 — Exact authenticated binding.** Flix version, URL, and digest form one generated lock record; manifest drift fails before network access.

**P4 — One hot-path implementation.** `.flixw/flix.java` owns lock parsing, drift detection, compiler acquisition, verification, dispatch, I/O, and stock-compiler launch. Shims only find an initial Java. An optional helper may implement wrapper verbs but never compiler launch.

**P5 — Explicit Java precedence.** An incompatible explicit Java setting fails immediately rather than falling through.

**P6 — Content-addressed installation.** Cache identity includes SHA-256; installation is unique-temp-plus-atomic-move and lock-free.

**P7 — Transparent process behavior.** Working directory and three standard streams are inherited; bootstrap messages use stderr only.

**P8 — Offline inspection.** Wrapper version and help require Java but no project, lock, compiler, or network. Cached projects work offline.

**P9 — Bounded failure.** One invocation performs at most one acquisition attempt for each resolved artifact and at most one Java relaunch.

**P10 — Observable errors.** Stable `FLIXWnnn` strings are the normative discriminator. Numeric exit codes are advisory because a compiler or user program may return the same integer.

**P11 — Stock Flix compatibility.** All required behavior shall work with unmodified official `github.com/flix/flix` release JARs. Upstream proposals are optional improvements, never runtime prerequisites.

**P12 — Compiler-first retirement.** Missing compiler capabilities use portable wrapper services reached through compiler-first dispatch. Each service retires automatically when the pinned compiler claims its verb and passes shared fixtures.

## 6. Security Model

The threat model distinguishes three required objects and one optional scaffold:

- **Wrapper source:** validated against a hash published for the `flixw` release.
- **Generated lock metadata:** reviewed as project data and checked for internal consistency with `flix.toml`.
- **Compiler artifact:** verified against the digest recorded during trusted generation.
- **Optional helper artifact:** verified against wrapper-release constants before any wrapper verb uses it.

GitHub-provided asset hashes are generation-time TOFU, not signed provenance. The preferred upstream change is a release checksum file plus a build-provenance attestation verifiable independently of asset download. Maven Central publication would add repository checksums and publication signatures, while Coursier could delegate resolution and JVM provisioning.

`FLIX_JVM_OPTS` accepts only a documented allow-list in the experiment. Options that select another JAR, load an argument file, inject agents, or execute error handlers are rejected unless an explicit unsafe mode is chosen. `_JAVA_OPTIONS` and `JAVA_TOOL_OPTIONS` are reported because they can modify execution and prepend messages to stderr.

The VS Code extension is deliberately excluded. Executing a workspace-provided wrapper on folder open would cross the Workspace Trust boundary. The useful upstream editor changes are a project compiler-version setting, an explicit JAR path, and digest verification—not automatic execution of repository scripts.

## 7. Evidence and Falsifiable Evaluation

### 7.1 Evidence to date

- `flix-invaders` demonstrates the practical need for version-directed acquisition and supplies concrete failure fixtures [16].
- Its Windows workflow demonstrates that Git Bash may eliminate the need for a separate Windows implementation in CI.
- The 0.75.2 GitHub asset digest matches the locally observed JAR, establishing API availability but not independent authenticity.
- A working 297-line prototype, installed as `.flixw/flix.java` in this design, has parsed the lock, detected drift, downloaded the real 0.75.2 stock JAR, matched digest `a2697d87…`, populated the cache atomically, implemented `pin`, and launched the compiler. It is prototype evidence, not yet a multi-release field result.
- Measured source launch is approximately 375 ms, cached precompiled stage 0 approximately 50 ms, unconditional SHA-256 approximately 105 ms, stock `flix.jar --version` approximately 200 ms, and a warm project `check` approximately 3.6–4.7 s.

### 7.2 Questions that can fail

1. Can stage 0 verify, install, and execute an exact unmodified stock Flix release without a helper JAR or compiler changes?
2. Does opt-in pinning reduce reproducibility failures without causing unacceptable upgrade lag over at least six Flix releases?
3. Does the parser accept every public `flix.toml` used in the evaluation corpus and reject ambiguous compiler declarations?
4. Is warm overhead of the end-state stage 0 → compiler path acceptable on Linux, macOS, Git Bash, PowerShell, `cmd.exe`, and direct Windows Java?
5. Do content-addressed cache entries coexist across mirrors and historical digests without deletion or repeated downloads?
6. Does the REPL preserve TTY behavior, colour, signals, stdin, stdout, and stderr?
7. Can compiler-first dispatch retire wrapper verbs without changing project files or misrouting unknown commands?
8. Can the Community Build continue testing pinned projects against current compiler head?
9. If Maven Central publication lands, does Coursier make custom compiler acquisition unnecessary?

Measurements include cold time, warm overhead, bytes transferred, hash time, recovery after truncation, upgrade lag, accumulated migration changes, corpus acceptance, stream fidelity, and argument vectors. Lines of code and committed files are costs.

## 8. Implementation and Evaluation Plan

### Phase 0 — Establish upstream-independent policy

1. Create `wstein/flixw` under a permissive license.
2. State that it is experimental, third-party, opt-in, and not an official Flix tool.
3. Record current release cadence and Community Build participation.
4. Ask Flix only about release checksums/attestations, running-version mismatch diagnostics, and Maven publication.

**Gate:** the prototype can proceed without an unanswered maintainer decision.

### Phase 1 — Define deterministic artifacts

1. Specify canonical bytes and hashes for `flix`, `flix.cmd`, and `.flixw/flix.java`.
2. Specify `.flixw/lock.toml` and its consistency rules.
3. Implement idempotent marked-block merging for `.gitattributes` and detect later overrides.
4. Define wrapper release and upgrade policy.

**Gate:** independent generation with identical inputs produces identical wrapper files and lock metadata.

### Phase 2 — Complete the stage-0 compiler path

1. Preserve the working prototype's lock parsing, drift detection, exact version validation, download, digest, cache, atomic install, and opaque stock-compiler launch.
2. Add safe build-metadata normalization and independent traversal rejection.
3. Compile stage 0 into the cache by wrapper-source hash after first source launch.
4. Implement bounded root selection without changing cwd.
5. Implement stable diagnostics and advisory exit codes with reachable sites.

**Gate:** `./flix check` can verify, install, and execute an unmodified stock Flix JAR without any helper JAR, and the public-manifest corpus matches a TOML oracle.

### Phase 3 — Implement Java and process behavior

1. Implement explicit Java precedence and hard-fail semantics.
2. Enforce `[21, tested_ceiling]`, reliable release-file parsing, one candidate execution, and at most one relaunch.
3. Preserve cwd, arguments, terminal, and all three standard streams.
4. Handle offline `--wrapper-version`, help, and forced pass-through first.

**Gate:** REPL, redirection, signals, Unicode, empty arguments, and relative paths match direct invocation.

### Phase 4 — Implement acquisition and cache

1. Implement HTTPS-only bounded download and proxy behavior.
2. Store artifacts under version-plus-digest identity.
3. Verify SHA-256 unconditionally on every execution.
4. Use unique temporary files and atomic move without deletion of divergent valid entries.

**Gate:** concurrent, corrupt, truncated, mirror-divergent, and offline cases terminate safely with at most one acquisition attempt.

### Phase 5 — Implement compiler-first dispatch and wrapper verbs

1. Capture and cache compiler verbs by compiler digest.
2. Implement the five dispatch rules, wrapper routing notice, forced backend, and compiler-first deprecation.
3. Implement `install`, `setup`, `doctor`, `update-wrapper`, `pin`, and `validate` directly in stage 0 while they remain small.
4. Make `pin` update `flix.toml` and `.flixw/lock.toml` as one recoverable transaction.
5. Preserve unrelated `.gitattributes` content and detect later overrides.
6. Extract an optional helper JAR only if measured complexity requires it.

**Gate:** injected write failures leave the old or new consistent pair; known compiler verbs always win; unknown verbs always reach Flix; each wrapper verb retires when a fixture compiler claims it.

### Phase 6 — Cross-platform experiment

1. Test POSIX launchers on Linux, macOS, and Git Bash.
2. Test `flix.cmd` from Command Prompt and PowerShell plus direct `java .flixw/flix.java` on Windows.
3. Document arguments transformed by `cmd.exe`; direct Java remains the lossless fallback.
4. Derive platform-specific warm-overhead budgets from measurement.

**Gate:** POSIX, PowerShell, and direct-Java paths preserve the contract; Command Prompt deviations are bounded and documented.

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

### 9.4 Expose a machine-readable command list

A stable `flix --commands` or equivalent would remove the need to parse human-oriented help. The prototype remains compatible with stock releases by caching and validating `--help` output until such a surface exists.

## 10. Deliberately Not Implemented

- Official `flix init` wrapper generation: premature before adoption evidence.
- Compiler forks, plugins, or private APIs: `flixw` must remain compatible with stock Flix releases.
- Permanent wrapper ownership of compiler names: compiler-first dispatch retires scaffolding automatically.
- Universal exact pinning: potentially harmful to pre-1.0 migration feedback.
- Floating `latest`: incompatible with a stable digest without a separate resolution policy.
- Shell or PowerShell TOML parsers: one Java implementation is sufficient.
- PowerShell scripts as a required Windows route: `flix.cmd` and direct Java work from PowerShell without execution-policy dependence.
- Automatic JDK installation: delegate experimentally to Coursier or JBang.
- Install-stamp integrity shortcuts: the compiler JAR is hashed unconditionally.
- Workspace-wrapper LSP execution: conflicts with editor trust boundaries.
- Numeric exit-code uniqueness: impossible for arbitrary user programs.
- Manifest-format constraints imposed for wrapper convenience.

## 11. Risks and Adverse Evidence

The largest risk is policy rather than code: exact pins may weaken the feedback loop that helps a small team evolve Flix quickly. The field study must be allowed to conclude that some or all projects should track head instead.

The second risk is maintenance duplication. If Flix reaches Maven Central, Coursier could make custom compiler acquisition unjustifiable. Stage 0 isolates that service so it can be replaced. If a helper JAR is ever introduced, it adds a second release pipeline, digest publication, security-response path, and TOFU anchor. That cost is accepted only after stage-0 wrapper verbs exceed a measured complexity threshold.

The helper handoff may never be accepted upstream. Compiler-first dispatch limits that risk: wrapper implementations remain usable without maintainer commitment, while each accepted command retires independently. The explicit retirement condition is a stock compiler verb plus passing conformance fixtures.

Single-file Java still requires a compatible Java installation. The proposal no longer calls this pure zero-install bootstrapping. Its immediate promise is narrower: a contributor who already has Java can clone a project and avoid separately installing Flix.

Release digests supplied by the same host as release bytes are not independent authenticity. Until attestations or signatures exist, generated pins are TOFU. Wrapper hashes protect known released source files but cannot protect a malicious first installation.

Finally, the 297-line prototype proves the central download-and-launch path but not dispatch, multi-platform behavior, or multi-release policy. The correct next move is to preserve that prototype, implement this routing contract, exercise it over the actual release cadence, and publish failures rather than continue expanding normative prose.

## 12. Conclusion

Repository-local compiler pinning is not an unconditional improvement for a fast-moving pre-1.0 language. It trades reproducibility for reduced upgrade pressure, and that trade must be measured against Flix's Community Build and release cadence. Revision 6 therefore keeps wrapper adoption opt-in.

The remaining experiment is smaller and more testable: one `./flix` entry point, an auditable Java stage 0, an unmodified stock Flix JAR, a committed compiler-only lock, unconditional digest verification, content-addressed caching, compiler-first verb dispatch, faithful process I/O, and wrapper services that retire one at a time. A helper JAR is optional scaffolding, not the architecture and not the hot path.

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
# Shims: only find an initial java executable.
java0 := FLIX_JAVA_HOME/bin/java
      or JAVA_HOME/bin/java
      or java_on_path()
      or fail(FLIXW003)
exec(java0, wrapper_root / ".flixw/flix.java", original_arguments)

# Stage 0: one Java implementation.
if argv is ["--wrapper-version"] or ["--wrapper-help"]:
    print offline metadata
    exit 0
if argv starts with an unknown launcher-level "--wrapper-" flag:
    fail(FLIXW008)
if argv is ["install", target] under first-contact mode:
    install_invariant_files(target) or fail(FLIXW009)
    exit 0

root := env("FLIX_PROJECT_ROOT")
     or nearest_manifest_ancestor(cwd, upper_anchor = wrapper_root,
                                  stop = [home, filesystem_root])
     or fail(FLIXW001)
manifest_version := parse_package_flix(root / "flix.toml")
lock := parse_generated_lock(root / ".flixw/lock.toml")
validate_version_and_paths(lock.compiler) or fail(FLIXW002)

if env("FLIX_JAVA_HOME") is set:
    java := validate_explicit(FLIX_JAVA_HOME, interval = [21, ceiling])
         or fail(FLIXW004)
else if env("JAVA_HOME") is set:
    java := validate_explicit(JAVA_HOME, interval = [21, ceiling])
         or fail(FLIXW004)
else:
    java := validate_current_or_select_once(interval = [21, ceiling])
         or fail(FLIXW003)
relaunch_at_most_once_if_needed(java)

validated_jvm_options := parse_safe(env("FLIX_JVM_OPTS"))
                      or fail(FLIXW008)

if env("FLIX_JAR") is set:
    compiler := validate_override(env("FLIX_JAR")) or fail(FLIXW006)
else:
    compiler := compiler_cache / artifact_name(lock.compiler.version,
                                                lock.compiler.sha256)
    if not exists(compiler):
        temp := unique_temp_in(compiler_cache) or fail(FLIXW007)
        download_once(rewrite_base_if_set(lock.compiler.url,
                                          env("FLIX_DIST_URL")), temp)
            or fail(FLIXW005)
        sha256(temp) == lock.compiler.sha256 or fail(FLIXW006)
        atomic_move(temp, compiler)
            or accept_identical_concurrent_winner()
            or fail(FLIXW007)
    sha256(compiler) == lock.compiler.sha256 or fail(FLIXW006)

verbs_file := sibling(compiler, ".verbs")
if not exists(verbs_file):
    capture_and_validate_help_verbs(java, compiler, verbs_file)
        or fail(FLIXW009)

wrapper_verbs := [pin, doctor, setup, validate, update-wrapper]
if env("FLIX_BACKEND") == "wrapper" and first_word(argv) in wrapper_verbs:
    backend := WRAPPER
else if argv starts with ["--"]:
    backend := COMPILER
    passthrough := argv after first "--"
else if first_word(argv) in read_lines(verbs_file):
    backend := COMPILER
else if first_word(argv) in wrapper_verbs:
    backend := WRAPPER
else:
    backend := COMPILER

if backend == COMPILER:
    if manifest_version != lock.compiler.version:
        fail(FLIXW002, "run: ./flix pin " + manifest_version)
    inherit_cwd_and_stdio()
    exec_or_spawn(java, validated_jvm_options, "-jar", compiler,
                  passthrough_or_original_arguments)
else:
    print_routing_notice_to_stderr()
    run_wrapper_service(first_word(argv), remaining_arguments)
        or fail(FLIXW009)
```

Every Appendix C identifier has a reachable site above. Numeric codes are advisory; the identifier printed to stderr is normative. Shims retain conventional 126 for a found but non-executable Java and 127 when no Java command exists.

## Appendix B. Evaluation Matrix

| Area | Required cases |
|---|---|
| Upgrade policy | six releases; head CI retained; pin lag; accumulated migration effort; abandoned project |
| Manifest | comments; whitespace; dotted/quoted keys; four string forms; multiline decoy; duplicate; ambiguity; public corpus |
| Version | release; prerelease; build metadata; leading/trailing punctuation; `..`; slash; backslash; range; wildcard; leading `v` |
| Lock | missing; manifest mismatch; URL mismatch; digest mismatch; deterministic generation; interrupted pair update |
| Root and cwd | subdirectory; nested manifest; `.git` directory/file; archive without Git; home/root stop; override; relative input/output |
| Java | `FLIX_JAVA_HOME`; `JAVA_HOME`; PATH; below 21; above ceiling; stale/unparseable release file; one relaunch bound; no-Java shim error |
| Cache | platform defaults; `FLIX_CACHE_HOME`; self-compiled stage 0; content-address coexistence; hit; miss; corrupt; truncated; concurrent; offline; mirror |
| Dispatch | forced pass-through; two wrapper flags; compiler wins; wrapper fallback; unknown to compiler; forced wrapper; per-verb retirement |
| I/O | no-argument REPL; stdin; stdout redirect; stderr separation; TTY; colour; signal/Ctrl-C; no extra console |
| Arguments | empty; spaces; quotes; Unicode; leading hyphen; forced pass-through; Command Prompt transformations |
| Wrapper files | canonical hashes; LF/CRLF; committed lock; executable bit; symlink chain; archive recovery; idempotent `.gitattributes`; override detection |
| Security | unconditional hash; one acquisition; HTTPS downgrade; `FLIX_DIST_URL`; proxy variables; timeout; attestation; unsafe JVM option; `_JAVA_OPTIONS` notice |
| Platforms | Linux; macOS ARM/Intel; Git Bash; PowerShell; cmd.exe; direct Java on Windows |

## Appendix C. Stable Diagnostics

| Identifier | Advisory exit | Reachable condition |
|---|---:|---|
| `FLIXW001` | 80 | no project manifest within the bounded search |
| `FLIXW002` | 81 | manifest and generated lock are missing, invalid, ambiguous, or inconsistent |
| `FLIXW003` | 82 | no compatible implicit Java installation |
| `FLIXW004` | 83 | explicitly selected Java is invalid or incompatible |
| `FLIXW005` | 84 | the single compiler acquisition attempt failed |
| `FLIXW006` | 85 | local override, cached, or downloaded compiler failed digest validation |
| `FLIXW007` | 86 | cache or atomic installation failed without an identical winner |
| `FLIXW008` | 87 | wrapper environment, JVM option, or launcher flag is invalid |
| `FLIXW009` | 88 | wrapper installation, verb capture, dispatch service, pin, validation, or lock transaction failed |

A Flix program may return any integer, including 80–88. Automation distinguishes bootstrap failures by the `FLIXWnnn` stderr identifier, not by assuming globally unique numeric status values.
