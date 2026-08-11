# A Repository-Local Bootstrap Wrapper for Reproducible Flix Projects

## Design, Alignment with the Flix Tooling Roadmap, and an Implementation Plan Validated through *flix-invaders*

**Werner Stein**  
**Design paper and reference implementation plan**  
**11 August 2026**

## Abstract

Flix combines a compiler, build system, package manager, test runner, and language server in one JVM application. A newly cloned Flix project nevertheless requires two tools that are external to the repository: a compatible Java runtime and a suitable `flix.jar`. This creates an avoidable onboarding and reproducibility gap. The project manifest already declares a compiler version through `[package].flix`, yet current documentation states that this field is not enforced. This paper proposes a repository-local `flix`/`flix.bat` bootstrap wrapper, analogous in user experience to `gradlew`, `mvnw`, and Mill's bootstrap launcher. The wrapper reads the compiler version exclusively from `flix.toml`, discovers a compatible Java 21+ runtime, prefers the latest installed long-term-support release, downloads and caches the corresponding compiler, and forwards all remaining arguments unchanged to the official Flix command-line interface.

The proposal is evaluated against the Flix team's project-management discussion, its package-manager roadmap, and current open issues concerning project-root discovery and deterministic TOML handling. The design deliberately separates bootstrap responsibilities from compiler responsibilities: the wrapper acquires and starts the compiler; Flix retains ownership of compilation, testing, packaging, dependency resolution, upgrades, releases, and manifest mutation. The `wstein/flix-invaders` repository serves as the reference implementation and validation case. A staged implementation plan defines interfaces, failure modes, security controls, test matrices, acceptance gates, and an upstream path. The result is a small compatibility layer that realizes an explicit Flix roadmap goal without introducing a competing build system.

**Keywords:** Flix, build reproducibility, bootstrap wrapper, zero-install tooling, JVM discovery, package management, developer experience, software supply chain

## 1. Introduction

The first command shown to a contributor is part of a language's practical interface. For Gradle projects it is commonly `./gradlew`; for Maven, `./mvnw`; and for Mill, `./mill`. These repository-local entry points reduce the distinction between “having the source” and “having the toolchain.” They also allow continuous integration and local development to use the same tool version.

The documented command-line workflow for Flix currently asks a developer to obtain Java 21+, download `flix.jar`, and invoke it with `java -jar flix.jar` [1]. Once started, the compiler already provides project discovery, dependency management, compilation, testing, packaging, and publishing [2]–[4]. Therefore, most build-tool complexity is already inside Flix. The remaining gap is bootstrapping the correct compiler with a compatible JVM.

This paper proposes two small files committed to a Flix application repository:

```text
flix
flix.bat
```

They enable the following workflow:

```console
git clone https://github.com/wstein/flix-invaders
cd flix-invaders
./flix test
./flix run
```

The scripts use the existing declaration in `flix.toml`:

```toml
[package]
name = "flix-invaders"
version = "0.1.0"
flix = "<project-pinned-version>"
```

No global Flix installation and no second committed version declaration are required. Java is not downloaded automatically in the initial design; it is discovered and selected from compatible installations already present on the machine. An optional `setup` wizard records a user-selected path in the gitignored `.flix/local.toml`.

The work addresses three research questions:

1. **RQ1:** Can a repository-local wrapper close Flix's bootstrap gap while keeping the official CLI as the sole build and package-management authority?
2. **RQ2:** Can Java and compiler selection be deterministic, cross-platform, quiet in normal use, and sufficiently diagnosable when failures occur?
3. **RQ3:** Does the proposed boundary conform to the Flix team's expressed goals and remain replaceable by a future official launcher?

The main contribution is not a new build tool. It is a precisely bounded launcher contract and a reference implementation plan.

## 2. Background and Related Work

### 2.1 Flix as an integrated toolchain

Flix compiles to JVM bytecode and requires Java 21 or newer [1], [5]. The command-line application recognizes `flix.toml`, resolves Flix and Maven dependencies, checks and tests projects, builds JAR and package artifacts, and publishes releases [2]–[4]. The manifest's `[package].flix` field syntactically declares a compiler version, although the package documentation presently notes that this field is not yet used by the compiler [2].

This architecture is favorable for a small bootstrapper: once the correct JAR is running in the correct project directory, the bootstrapper has no reason to interpret dependencies, source layouts, security contexts, or build commands.

### 2.2 Gradle Wrapper

The Gradle Wrapper provides a repository-owned launcher that downloads a declared Gradle distribution when necessary. Its security guidance recommends pinning and verifying a SHA-256 checksum because wrapper changes execute before project build logic [6], [7]. The Flix wrapper adopts the reproducibility pattern but cannot simply copy Gradle's implementation: Flix already stores its compiler version in the application manifest and does not ship an equivalent wrapper JAR.

### 2.3 Mill bootstrap scripts

Mill documents `./mill` and `mill.bat` as its standard installation method. The launcher determines a project-specific version, downloads it if absent, and caches it for reuse [8]. Mill further describes a zero-setup workflow that can acquire its own launcher, JVMs, and dependencies [9]. This supplies a closer conceptual analogue than Gradle because the launcher is itself script-centered. The proposed Flix wrapper is intentionally narrower: it discovers Java rather than provisioning a JDK, thereby limiting supply-chain and platform-management scope in the first implementation.

### 2.4 Why not a generic version manager?

Tools such as SDK managers can install Java and language runtimes, but they impose a global prerequisite and user-specific state. They are useful discovery sources, not substitutes for a repository entry point. A project wrapper should work with `JAVA_HOME`, `PATH`, operating-system facilities, Homebrew layouts, SDKMAN layouts, and standard Windows installations without requiring any one manager.

## 3. Evidence from the Flix Roadmap

### 3.1 Issue #4380: the shim is an explicit goal

The 2022 project and dependency-management discussion proposes a command-line “shim” that permits `flix <options>` rather than `java -jar flix.jar <options>`. It says the shim's primary task should be to identify, and possibly download, the appropriate compiler JAR. It also asks how the approach should work on Windows and through package managers [10].

The same discussion requires projects to specify a compiler version and delegates project interpretation and dependency work to the JAR. Subsequent Flix development resolved several questions raised in that issue: the project file became `flix.toml`; TOML became the format; Flix and Maven dependency sections exist; and the CLI contains the build and package manager.

The proposed wrapper therefore implements a previously articulated boundary rather than inventing a parallel toolchain.

### 3.2 Issue #11436: an active package-manager direction

Issue #11436 is the meta issue tracking the future of the Flix Package Manager [11]. Its stated design principles are user experience first, self-aware actions, transparency about sub-actions, safety for downloads and deletion, and clear actionable errors. Its long-term section explicitly includes an easy way to install Flix, add it to the command path, and upgrade an installed compiler. The wrapper should be understood as an outer bootstrap layer for that roadmap. It operationalizes those principles for compiler startup, but it should not absorb compiler-owned package-manager work.

### 3.3 Issue #11150: project-root discovery

Open issue #11150 observes that invoking Flix from `src/` may build that directory rather than locating the parent `flix.toml`. It proposes searching upward, comparable to Cargo [12]. A repository-local wrapper can provide safe behavior immediately by resolving its own physical directory, verifying `flix.toml` there, and launching the compiler with that directory as the working directory. This supports:

```console
cd src
../flix test
```

If Flix later implements upward manifest discovery, wrapper root normalization becomes redundant but harmless.

### 3.4 Issue #12208: deterministic manifest mutation

Open issue #12208 asks Flix's TOML formatter and upgrade operations to produce consistent ordering while preserving alternative dependency representations [13]. This establishes a useful ownership rule: the bootstrapper may read the compiler version, but it must never format or rewrite `flix.toml`. Compiler-owned commands remain responsible for upgrades and manifest mutation.

### 3.5 Issue #13003: a self-contained Flix toolchain

Open issue #13003, filed on 10 August 2026, is the closest current statement of the exact bootstrap gap addressed here [17]. It observes that Flix cannot manage either half of its own toolchain: Java 21+ must already be installed, and the `flix` field written into `flix.toml` is not read back to enforce or fetch a project compiler. It proposes honoring that field, locating or downloading a JDK, and at minimum providing `doctor` or `setup` behavior with clear Java-version errors.

This issue strengthens the rationale for the wrapper and clarifies two maturity levels:

- **Level 1 — repository bootstrap:** discover an existing compatible JVM, pin and fetch the compiler, cache it, and delegate to Flix. This is the reference implementation proposed in this paper.
- **Level 2 — managed toolchain:** securely provision a JDK when none is installed, then relaunch the compiler. This is a valuable subsequent stage, but it requires a vendor policy, platform and architecture metadata, checksums or signatures, licensing analysis, archive extraction, updates, and a larger security surface.

The initial implementation deliberately completes Level 1 before attempting Level 2. That staging follows #11436's safety principle: automatic acquisition should be introduced only with validation and actionable reporting.

### 3.6 Distribution issues #13002 and #4561

Issue #13002 asks the Flix team to publish the compiler to Maven Central or another JVM artifact registry, noting that integrations currently reimplement raw GitHub-release downloads [18]. Issue #4561 proposes release publication through JReleaser to Homebrew, Docker, SDKMAN!, and related channels [19]. These are complementary to the wrapper:

- Maven publication could replace the prototype's GitHub URL convention with registry resolution and standard metadata.
- JReleaser could publish official global launchers and checksums.
- Repository wrappers would still provide project-specific version selection and clone-to-command reproducibility.

Consequently, the compiler-source abstraction should be isolated behind a small resolver interface even if the first implementation uses GitHub releases.

## 4. Requirements

### 4.1 Functional requirements

**FR1 — Project identification.** The wrapper shall locate the repository root from the launcher file, not from an arbitrary current working directory, and require a readable `flix.toml` in that root.

**FR2 — Single compiler-version authority.** It shall obtain the desired compiler version only from `[package].flix`. There shall be no `flix-wrapper.toml`, hard-coded application version, or second committed version property.

**FR3 — Compatible Java.** It shall require Java 21 or newer, consistent with current Flix documentation [1], [5].

**FR4 — LTS preference.** Among automatically discovered compatible Java installations, it shall prefer the latest installed LTS known to the wrapper. As of August 2026, Java 25 is the newest LTS and Java 21 is the previous compatible LTS [14], [15]. If no compatible LTS is installed, any compatible Java 21+ may be selected through a stable discovery order.

**FR5 — Explicit selection precedence.** A valid local selection, `FLIX_JAVA_HOME`, or `JAVA_HOME` shall take precedence over automatic discovery. Explicit compatible choices may be non-LTS.

**FR6 — Compiler acquisition.** It shall derive the canonical release location from the manifest version, cache the JAR outside the repository, and use atomic installation with concurrency protection.

**FR7 — Transparent forwarding.** Except for a small reserved wrapper command surface, it shall forward arguments and the child process exit status unchanged.

**FR8 — Setup and diagnostics.** `setup` shall permit interactive Java selection; `doctor` shall report readiness. Selection rationale such as “compatible fallback” shall appear only in verbose or debug output.

**FR9 — Offline reuse.** A cached compiler shall work without network access. A cache miss while offline shall fail with the exact version and expected cache location.

**FR10 — Provisioning extension point.** The initial wrapper shall not download a JDK, but Java resolution shall expose a clear `NO_COMPATIBLE_JAVA` outcome so a later, policy-controlled managed-JDK provider can be added without changing command forwarding or compiler caching.

### 4.2 Non-functional requirements

- **Determinism:** stable inputs and machine state produce the same selection.
- **Portability:** POSIX `sh` and Windows Batch are first-class; PowerShell may be invoked only as an available Windows system facility, not as a separately installed dependency.
- **Minimality:** no dependency resolver, command alias layer, JDK installer, or manifest writer.
- **Security:** HTTPS-only release acquisition, content validation, atomic cache writes, no evaluation of TOML as shell code, and documented checksum policy.
- **Observability:** normal success is quiet; actionable failures are concise; verbose mode exposes candidates, rejection reasons, paths, and derived URLs.
- **Replaceability:** a future official Flix launcher can replace the scripts without changing project commands or manifest structure.
- **Roadmap fidelity:** bootstrap progress, safe download behavior, and actionable errors should follow the UX, transparency, safety, and error-reporting principles in issue #11436.

## 5. Architecture

### 5.1 Responsibility boundary

```text
Repository wrapper
  ├─ locate project root
  ├─ read [package].flix
  ├─ discover/select Java 21+
  ├─ download/cache matching flix.jar
  └─ exec Java with unchanged arguments

Official Flix CLI
  ├─ parse full flix.toml
  ├─ resolve Flix and Maven dependencies
  ├─ check, test, run, and build
  ├─ create JAR/fat JAR/package artifacts
  ├─ upgrade dependencies and format TOML
  └─ publish releases
```

This boundary is the central design invariant. A wrapper feature is rejected if it requires understanding Flix dependency graphs, build semantics, source semantics, or command-specific flags.

### 5.2 Files and state

Committed project files:

```text
flix.toml
flix
flix.bat
```

Optional machine-local state:

```text
.flix/local.toml
```

Example:

```toml
schema = 1

[java]
home = "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
```

The repository adds `/.flix/local.toml` to `.gitignore`. The local file stores only a user choice. Minimum Java and known LTS policy are launcher implementation constants because they describe wrapper compatibility, not project semantics.

User cache locations follow platform conventions:

```text
Linux:   ${XDG_CACHE_HOME:-$HOME/.cache}/flix/wrapper/<version>/flix.jar
macOS:   $HOME/Library/Caches/flix/wrapper/<version>/flix.jar
Windows: %LOCALAPPDATA%\Flix\wrapper\<version>\flix.jar
```

### 5.3 Version extraction

Full TOML parsing is undesirable before the compiler exists, but naïve text matching is unsafe. The wrapper needs a deliberately narrow, validating reader for one field:

1. Scan line-by-line using UTF-8 text.
2. Remove an optional UTF-8 byte-order mark on the first line.
3. Recognize standard table headers after trimming whitespace and comments.
4. Enter only the exact `[package]` table.
5. Recognize only a basic quoted string assigned to the exact key `flix`.
6. Reject duplicate `[package]` tables, duplicate `flix` keys, empty values, unsupported string forms, and versions outside the accepted release identifier grammar.
7. Never execute, source, or interpolate manifest content.

The accepted version grammar should initially be strict SemVer without ranges:

```text
[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?
```

If the official manifest semantics later permit ranges, the wrapper must not silently invent resolution semantics. It should fail and request an exact version until the Flix team defines launcher behavior.

### 5.4 Java selection algorithm

Candidate sources are evaluated in precedence groups:

1. `.flix/local.toml`
2. `FLIX_JAVA_HOME`
3. `JAVA_HOME`
4. `java` on `PATH`
5. platform-specific discovery

Platform discovery includes:

- **macOS:** `/usr/libexec/java_home -V`; `/Library/Java/JavaVirtualMachines`; Apple Silicon and Intel Homebrew prefixes.
- **Linux:** `/usr/lib/jvm`; `update-alternatives` where present; SDKMAN and asdf directories where present.
- **Windows:** `where java`; JavaSoft, Eclipse Adoptium, and Microsoft JDK registry locations; standard Program Files directories.

Each candidate is normalized and deduplicated before validation. Validation executes that candidate's own Java binary and obtains its feature version. A candidate is compatible if the executable starts and reports feature version 21 or greater.

Selection rules are:

1. Use the first valid explicit candidate in precedence order.
2. Otherwise select the highest installed compatible LTS known to the wrapper (25, then 21 in the 2026 implementation).
3. Otherwise select the first compatible Java 21+ in stable discovery order.
4. Otherwise fail.

The wrapper does not query the network to determine the current LTS. Known LTS values are updated with wrapper releases. This preserves deterministic and offline behavior.

In the Level 1 implementation, failure to find Java ends with an actionable installation or `setup` instruction. A Level 2 implementation may then offer a managed JDK, but it must never silently replace an explicit local selection.

### 5.5 Output policy

Normal execution prints no Java-selection commentary. A compatible non-LTS fallback is valid and produces no warning. `doctor` provides a compact readiness summary:

```text
Java: OpenJDK 23.0.2
Flix: <manifest version>
Status: Ready
```

Verbose or debug mode may add:

```text
Status:        Compatible fallback
Reason:        No compatible LTS installation was found
Java home:     /usr/lib/jvm/java-23-openjdk
Source:        platform discovery
```

Errors include corrective action but must avoid dumping irrelevant candidates unless verbosity is requested.

## 6. Security and Reproducibility

### 6.1 Threat model

The wrapper executes before trusted project tooling and downloads executable code. Threats include a modified wrapper, malicious manifest values, release substitution, partial downloads, cache races, proxy error pages saved as JARs, path injection, and unsafe temporary files.

### 6.2 Required controls

1. Restrict compiler versions to a release identifier grammar before constructing a URL or path.
2. Use only the canonical HTTPS release origin.
3. Reject redirects to disallowed schemes.
4. Download to a newly created temporary file in the final cache filesystem.
5. Require a successful HTTP status and non-empty bounded response.
6. Validate ZIP/JAR magic and required JAR structure before installation.
7. Verify a publisher-provided digest when Flix publishes a stable machine-readable checksum source.
8. Atomically rename the validated file into place.
9. Coordinate concurrent downloads with a lock directory or exclusive file creation.
10. Never place downloaded compiler JARs in the Git working tree.
11. In CI, permit a read-only pre-populated cache and fail clearly when acquisition is impossible.

Gradle demonstrates the stronger model of a checksum committed alongside the wrapper distribution [6], [7]. Because this proposal rejects a duplicate wrapper configuration file, the preferred long-term Flix solution is an official checksum or signed release manifest indexed by compiler version. Until that exists, HTTPS plus structural validation provides transport integrity but not independent artifact authenticity. This limitation must be documented rather than hidden.

## 7. Reference Case: *flix-invaders*

`wstein/flix-invaders` is a suitable validation repository because it is a normal `flix.toml` application rather than the Flix compiler source tree. It targets JVM execution, uses Processing Core through a narrow Java boundary, and contains both interactive behavior and headless deterministic tests. The wrapper must remain indifferent to these application details; the official compiler resolves the declared Maven and Flix dependencies.

### 7.1 Reference workflow

Fresh clone:

```console
git clone https://github.com/wstein/flix-invaders
cd flix-invaders
./flix check
./flix test
./flix run
```

Windows:

```console
git clone https://github.com/wstein/flix-invaders
cd flix-invaders
flix.bat check
flix.bat test
flix.bat run
```

The baseline acceptance sequence records the exact Flix, Java, and Processing versions; executes `check`; executes all headless tests; and performs a bounded graphical smoke test. The wrapper is successful only if the same Flix commands behave equivalently to direct invocation of the selected compiler JAR.

### 7.2 Case-study hypotheses

- **H1:** A new contributor with Java 21+ but no Flix installation can execute tests with one repository command.
- **H2:** Two clones with the same manifest select the same compiler version.
- **H3:** Java 25 LTS is preferred over compatible non-LTS installations during automatic discovery.
- **H4:** Any explicitly selected compatible Java 21+ remains authoritative.
- **H5:** No compatible LTS causes quiet deterministic fallback to another compatible Java 21+.
- **H6:** Offline operation succeeds after the compiler has been cached.
- **H7:** Wrapper use does not change simulation determinism, the 60 Hz model, Processing isolation, or headless test behavior.

## 8. Detailed Step-by-Step Implementation Plan

### Phase 0 — Establish the executable specification

1. Record the current `flix-invaders` manifest and Git status.
2. Record direct baseline commands using the project's currently intended compiler.
3. Capture exit codes and essential output for `check`, `test`, and `run --help` or another bounded non-graphical command.
4. Record Java 21 and Java 25 baseline behavior.
5. Create an architecture decision record defining the responsibility boundary.
6. Define explicit exclusions: no JDK installation, no dependency resolution, no command aliases, no manifest mutation, and no reliance on the private `--Xdatalog-debug` option.

**Gate P0:** Maintainers approve the command surface, precedence rules, cache locations, and exclusions before scripts are written.

### Phase 1 — Define shared behavioral fixtures

1. Create a `wrapper-tests/fixtures` tree containing minimal manifests.
2. Include valid basic TOML, whitespace variations, comments, CRLF, UTF-8 BOM, and pre-release version fixtures.
3. Include invalid missing section, missing key, duplicate key, duplicate section, unquoted version, malformed string, range, traversal text, and command-substitution-like text.
4. Define expected outcomes in a platform-neutral fixture manifest.
5. Define fake Java homes whose executables return controlled version output and exit codes.
6. Define a fake compiler JAR endpoint or local HTTP test server fixture.

**Gate P1:** The fixture corpus expresses every parser, Java-selection, download, and forwarding rule without depending on the production network.

### Phase 2 — Implement the POSIX launcher skeleton

1. Add executable `flix` with `#!/bin/sh` and strict error handling compatible with POSIX shells.
2. Resolve symbolic links with a bounded loop and determine the physical launcher directory.
3. Set the project root to that directory and verify `flix.toml` exists.
4. Implement structured error and verbose logging helpers.
5. Parse wrapper-owned switches without consuming Flix arguments.
6. Reserve `setup`, `doctor`, and `wrapper`; forward all other commands.
7. Preserve argument boundaries by accumulating and executing through `"$@"`; never flatten arguments into a string.
8. Preserve child exit status and use `exec` for the final Java process when practical.

**Gate P2:** A stub Java executable receives byte-for-byte-equivalent argument boundaries, including spaces, quotes, Unicode, and empty arguments.

### Phase 3 — Implement narrow TOML extraction

1. Implement `[package]` state tracking without sourcing the file.
2. Recognize comments only outside quoted strings.
3. Decode the minimal permitted basic-string escapes or reject unsupported escapes explicitly.
4. Validate exact version syntax.
5. Detect duplicates and ambiguity.
6. Emit no manifest content in default errors beyond the offending field name.
7. Run all fixtures under multiple POSIX shells available in CI.

**Gate P3:** Valid fixtures return one exact compiler version; every ambiguous or unsupported fixture fails closed.

### Phase 4 — Implement Java discovery and selection on POSIX systems

1. Add validators for `<home>/bin/java` and `java` resolved through `PATH`.
2. Parse legacy `1.x` and modern feature-version output defensively.
3. Normalize homes using physical paths where possible.
4. Deduplicate by normalized Java executable.
5. Implement explicit precedence: local TOML, `FLIX_JAVA_HOME`, `JAVA_HOME`, then automatic discovery.
6. Implement macOS discovery and Homebrew paths.
7. Implement Linux `/usr/lib/jvm`, alternatives, SDKMAN, and asdf discovery.
8. Classify known compatible LTS versions and select the latest.
9. Fall back to the first deterministic compatible Java 21+ only when no compatible LTS exists.
10. Keep fallback rationale behind verbose/debug mode.

**Gate P4:** Table-driven tests cover multiple LTS versions, mixed LTS/non-LTS installations, invalid explicit paths, and machines with no compatible Java.

### Phase 5 — Implement `.flix/local.toml` and `setup`

1. Add `.flix/local.toml` to `.gitignore`.
2. Reuse the narrow TOML reader for `[java].home`.
3. Make `setup` enumerate only validated Java 21+ candidates.
4. Display vendor, feature version, and normalized home.
5. Default to the same candidate automatic selection would use.
6. Write to a temporary file and atomically replace `.flix/local.toml`.
7. Never modify `flix.toml`, shell profiles, environment variables, or the operating-system registry.
8. Add `setup --java-home <path>` and a noninteractive form for automation.

**Gate P5:** Interrupted setup cannot leave a partially written file; ordinary wrapper invocation never writes local configuration.

### Phase 6 — Implement compiler cache and download

1. Derive the release tag, URL, and cache key from the validated manifest version.
2. Select the platform cache root.
3. Check for a valid existing JAR before acquiring a lock.
4. Acquire a per-version lock with stale-lock diagnostics.
5. Recheck the cache after acquiring the lock.
6. Download to a unique temporary path with timeouts and redirect restrictions.
7. Validate HTTP result, size, JAR signature bytes, and archive readability.
8. Apply official digest verification when a stable checksum source is available.
9. Atomically rename the artifact and release the lock.
10. Leave a valid prior cache entry untouched on refresh failure.

**Gate P6:** Tests cover cache hit, first download, interrupted download, error-page response, corrupt JAR, concurrent processes, read-only cache, proxy failure, offline hit, and offline miss.

### Phase 7 — Implement execution and diagnostics

1. Launch `<selected-java> -jar <cached-jar>` from the project root.
2. Forward all Flix arguments unchanged.
3. Preserve standard input, output, error, signals, and exit status.
4. Add `doctor` checks for manifest, Java, cache, writable cache parent, and optional network reachability.
5. Keep default `doctor` output compact.
6. Add verbose candidate traces with rejection reasons and sources.
7. Redact credentials and query strings from diagnostic URLs.

**Gate P7:** Direct-JAR and wrapper invocations are behaviorally equivalent for representative success and failure commands.

### Phase 8 — Implement the Windows launcher

1. Add `flix.bat` with delayed-expansion hazards explicitly controlled.
2. Resolve `%~dp0` as the project root.
3. Implement argument forwarding without reconstructing user arguments where Batch permits.
4. Use system PowerShell only for operations that Batch cannot implement safely, such as robust HTTPS download and registry enumeration.
5. Query relevant 32-bit and 64-bit JDK registry views.
6. Inspect `where java`, `JAVA_HOME`, `FLIX_JAVA_HOME`, Program Files, Adoptium, and Microsoft JDK installations.
7. Apply the same precedence and LTS policy as POSIX.
8. Use `%LOCALAPPDATA%\Flix\wrapper` for cache state.
9. Implement atomic local TOML and compiler-cache replacement.
10. Match error identifiers and exit codes across platforms.

**Gate P8:** Windows tests pass in `cmd.exe` with paths containing spaces, parentheses, ampersands, non-ASCII characters, and long user-profile names.

### Phase 9 — Cross-platform conformance tests

1. Establish a shared list of externally observable behaviors.
2. Run POSIX tests on Ubuntu and macOS, including Intel/ARM path fixtures.
3. Run Windows tests in `cmd.exe` on a standard GitHub Actions runner.
4. Test LF and CRLF checkouts.
5. Test Java 21 LTS, Java 25 LTS, and one compatible non-LTS fixture.
6. Test absence of LTS with compatible fallback.
7. Test explicit non-LTS selection.
8. Test simultaneous cache population.
9. Test all supported Flix commands as opaque forwarded strings rather than maintaining a command allow-list.

**Gate P9:** Both launchers satisfy the same behavioral contract; documented platform differences are limited to discovery sources and cache paths.

### Phase 10 — Validate in *flix-invaders*

1. Add the launchers and ignore rule on an isolated branch.
2. Remove or hide any globally installed Flix executable from the test environment.
3. Start with an empty wrapper cache.
4. Execute `./flix check` and `./flix test`.
5. Repeat offline using the populated cache.
6. Execute the bounded Processing smoke test on a graphical runner where available.
7. Confirm that headless deterministic tests are unchanged.
8. Compare artifacts, exit codes, and relevant output with direct compiler invocation.
9. Repeat on Windows with `flix.bat`.
10. Record onboarding time, number of manual prerequisites, download count, and failure-recovery behavior.

**Gate P10:** A fresh clone requires only compatible Java and one project command; all existing tests pass without wrapper-specific application changes.

### Phase 11 — Documentation and maintainability

1. Put the wrapper command first in README onboarding.
2. Document Java 21+ as the minimum and latest installed LTS as the automatic preference.
3. Explain the quiet fallback rule.
4. Document `setup`, `doctor`, verbose/debug diagnostics, offline operation, and cache deletion.
5. Document how maintainers update known LTS constants.
6. Document the compiler release URL template and checksum limitation.
7. Add a generated parity report or test that compares policy constants in `flix` and `flix.bat`.
8. Mark the scripts as a prototype compatible with issues #4380 and #11436, not an official Flix distribution.

**Gate P11:** A contributor unfamiliar with Flix can clone, diagnose, test, and run the application using only the README and wrapper.

### Phase 12 — Upstream feedback and transition

1. Publish the reference implementation and evaluation results.
2. Open a focused Flix discussion linking #4380, #11436, #11150, #12208, #13002, #13003, and #4561.
3. Propose the launcher contract before proposing code inclusion.
4. Ask the Flix team to define an official compiler-artifact checksum or signed release manifest.
5. Ask whether `[package].flix` should become an exact launcher version contract.
6. Keep package resolution and manifest mutation explicitly out of the proposal.
7. If an official global shim emerges, retain repository wrappers as thin delegates or generated launchers.
8. Define a deprecation route that leaves `./flix test` working throughout migration.

**Gate P12:** Upstream adoption can replace implementation internals without changing the user-facing project command or adding a second version source.

### Phase 13 — Optional managed-JDK extension

This phase is intentionally outside the initial `flix-invaders` wrapper milestone. It addresses the stronger self-contained-toolchain goal of issue #13003 only after the discovery-based wrapper is stable.

1. Define supported operating systems, CPU architectures, archive formats, and one or more acceptable OpenJDK distributions.
2. Define a vendor-neutral metadata contract containing version, LTS classification, operating system, architecture, URL, archive type, size, and SHA-256 digest.
3. Establish who maintains and signs that metadata; do not scrape download pages during normal execution.
4. Add an explicit `setup --managed-java` path before considering automatic provisioning.
5. Download into a versioned user cache, never the repository.
6. Verify digest before extraction and defend against archive traversal and symlink attacks.
7. Extract atomically into a per-version, per-platform directory.
8. Validate the downloaded Java by executing it and checking the feature version.
9. Record the selected managed home in `.flix/local.toml` using the same mechanism as discovered Java.
10. Define update and garbage-collection policy without deleting installations still referenced by projects.
11. Test license notices, proxy behavior, offline reuse, interrupted extraction, concurrent setup, and read-only caches.
12. Keep normal `./flix test` quiet after the managed runtime has been installed.

**Gate P13:** A machine with neither Java nor Flix can complete an explicitly authorized setup and then run `check` and `test`, with every downloaded executable independently verified.

## 9. Evaluation Method

### 9.1 Quantitative measures

- Fresh-clone commands before the first successful test.
- Wall-clock time for cold bootstrap and warm-cache invocation.
- Network bytes on cold and warm paths.
- Rate of correct Java selection across the test matrix.
- Cross-platform behavioral-conformance pass rate.
- Percentage of Flix CLI arguments forwarded without wrapper knowledge.
- Number of repository files and lines required by the wrapper.
- Failure recovery after interrupted or concurrent download.

### 9.2 Qualitative measures

- Whether error messages identify a corrective action.
- Whether normal successful output remains attributable to Flix rather than the wrapper.
- Whether maintainers can review launcher changes as security-sensitive code.
- Whether the design can be replaced by an official launcher without manifest migration.

### 9.3 Success criteria

The reference implementation is successful when:

1. A clean environment with compatible Java but no Flix installation can run `check` and `test`.
2. The manifest version alone selects the compiler.
3. Warm-cache operation is offline.
4. Latest installed compatible LTS is selected automatically.
5. Any explicit compatible Java 21+ is respected.
6. Compatible non-LTS fallback is quiet outside verbose/debug mode.
7. Corrupt or partial downloads are never executed.
8. Existing `flix-invaders` tests and runtime boundaries remain unchanged.
9. POSIX and Windows launchers conform to one observable contract.

## 10. Risks and Threats to Validity

**Shell complexity.** POSIX shell and Batch are widely available but difficult to make equivalent. The mitigation is a small feature set, shared fixtures, stable error identifiers, and platform conformance tests.

**Partial TOML implementation.** Reading one manifest field without a complete parser creates a compatibility risk. The wrapper must fail on unsupported syntax rather than misparse it. An official launcher should eventually use the Flix TOML implementation or a small native parser.

**Release-layout dependence.** Derived URLs assume stable GitHub release naming. This is acceptable for a prototype but should become an official Flix release API or metadata contract.

**Artifact authenticity.** HTTPS and structural JAR validation do not replace a trusted checksum. This is the principal security limitation until Flix publishes verifiable release metadata.

**Case-study generality.** `flix-invaders` exercises Maven interoperability, graphics, and headless tests, but it is one application. Follow-up validation should include a pure library, a CLI application, a project with transitive Flix packages, and a Windows-heavy project.

**Changing Java policy.** “Latest LTS” is time-dependent. The wrapper deliberately embeds known LTS versions rather than discovering policy online. Consequently, wrapper maintenance must update the preference when a new LTS becomes supported.

## 11. Discussion

The proposal aligns strongly with the Flix team's goals because it realizes the precise shim boundary described in #4380, applies the UX and safety principles of #11436, and directly answers most of #13003. Its two deliberate limitations are distribution and staging. First, the older discussion emphasizes a global `flix` command, while this work uses repository-local launchers. The two are complementary. Second, the reference implementation discovers Java rather than downloading it; managed-JDK acquisition is staged until trustworthy metadata and extraction controls exist.

The repository-local design also provides an experimental advantage. It can be validated in `flix-invaders` without modifying the compiler repository, and its behavior can inform an upstream implementation. If the Flix team later ships an official launcher, project scripts can delegate to it while preserving the stable `./flix` entry point.

The wrapper should resist feature growth. Adding aliases, dependency commands, automatic manifest upgrades, Java downloads, or application-specific launch behavior would weaken alignment. The smallest useful wrapper is the strongest upstream candidate.

## 12. Conclusion

A Flix project already contains nearly all information and functionality necessary for reproducible execution. The missing layer is a launcher that turns `[package].flix` into an executable compiler choice and connects that compiler to a compatible JVM. A repository-local `flix`/`flix.bat` wrapper closes this gap with minimal conceptual overhead.

The design follows the Flix team's stated shim goal, supports the active package-manager direction, provides immediate project-root normalization, and avoids interfering with deterministic manifest management. Its reference implementation in `flix-invaders` offers a concrete test bed for cross-platform bootstrapping, Java selection, caching, offline reuse, and transparent CLI delegation. With strict scope control and an improved release-integrity mechanism, the wrapper can serve both as practical project infrastructure and as evidence for an official Flix bootstrap launcher.

## References

[1] Flix Project, “Getting Started,” *Programming Flix*. [Online]. Available: https://doc.flix.dev/getting-started.html. Accessed: Aug. 11, 2026.

[2] Flix Project, “Package Management,” *Programming Flix*. [Online]. Available: https://doc.flix.dev/packages.html. Accessed: Aug. 11, 2026.

[3] Flix Project, “Build and Package Management,” *Programming Flix*. [Online]. Available: https://doc.flix.dev/build-and-packages.html. Accessed: Aug. 11, 2026.

[4] Flix Project, “Publishing a Package on GitHub,” *Programming Flix*. [Online]. Available: https://doc.flix.dev/publish.html. Accessed: Aug. 11, 2026.

[5] Flix Project, “Interoperability with Java,” *Programming Flix*. [Online]. Available: https://doc.flix.dev/interoperability.html. Accessed: Aug. 11, 2026.

[6] Gradle Inc., “Gradle Wrapper,” *Gradle User Manual*. [Online]. Available: https://docs.gradle.org/current/userguide/gradle_wrapper.html. Accessed: Aug. 11, 2026.

[7] Gradle Inc., “Validate the Gradle Distribution SHA-256 Checksum,” *Best Practices for Security*. [Online]. Available: https://docs.gradle.org/current/userguide/best_practices_security.html. Accessed: Aug. 11, 2026.

[8] Mill Project, “Installation & IDE Setup,” *Mill Documentation*. [Online]. Available: https://mill-build.org/mill/cli/installation-ide.html. Accessed: Aug. 11, 2026.

[9] Mill Project, “Zero-Setup Java Build Tooling via Mill Bootstrap Scripts,” Sep. 24, 2025. [Online]. Available: https://mill-build.org/blog/16-zero-setup.html. Accessed: Aug. 11, 2026.

[10] P. Butcher, “Discussion: project and dependency management,” Flix issue #4380, Jul. 27, 2022. [Online]. Available: https://github.com/flix/flix/issues/4380. Accessed: Aug. 11, 2026.

[11] Flix Project, “Meta Issue: Tracking the Future of the Flix Package Manager,” Flix issue #11436. [Online]. Available: https://github.com/flix/flix/issues/11436. Accessed: Aug. 11, 2026.

[12] M. Lutze, “CLI: detect toml in parent directory,” Flix issue #11150, Jul. 29, 2025. [Online]. Available: https://github.com/flix/flix/issues/11150. Accessed: Aug. 11, 2026.

[13] M. Madsen, “Improvements to TOML formatter,” Flix issue #12208, Dec. 30, 2025. [Online]. Available: https://github.com/flix/flix/issues/12208. Accessed: Aug. 11, 2026.

[14] Oracle, “Oracle Java SE Support Roadmap.” [Online]. Available: https://www.oracle.com/java/technologies/java-se-support-roadmap.html. Accessed: Aug. 11, 2026.

[15] OpenJDK Project, “JDK 25.” [Online]. Available: https://openjdk.org/projects/jdk/25/. Accessed: Aug. 11, 2026.

[16] W. Stein, “flix-invaders,” GitHub repository. [Online]. Available: https://github.com/wstein/flix-invaders. Accessed: Aug. 11, 2026.

[17] chengjilai, “Self-contained flix toolchain: manage the JDK and pin/fetch the compiler version like Go's go.mod toolchain,” Flix issue #13003, Aug. 10, 2026. [Online]. Available: https://github.com/flix/flix/issues/13003. Accessed: Aug. 11, 2026.

[18] chengjilai, “Publish the Flix compiler to Maven Central (or another JVM registry) so build tools can declare it as a dependency,” Flix issue #13002, Aug. 10, 2026. [Online]. Available: https://github.com/flix/flix/issues/13002. Accessed: Aug. 11, 2026.

[19] ilaborie, “Publish to brew/Docker/SDKMAN! via JReleaser,” Flix issue #4561, Sep. 14, 2022. [Online]. Available: https://github.com/flix/flix/issues/4561. Accessed: Aug. 11, 2026.

## Appendix A. Normative Selection Pseudocode

```text
root := physical_directory_of_wrapper()
manifest := root / "flix.toml"
version := read_exact_package_flix(manifest)

candidates := []
append_if_present(candidates, read_local_java(root / ".flix/local.toml"), EXPLICIT_LOCAL)
append_if_present(candidates, env("FLIX_JAVA_HOME"), EXPLICIT_FLIX_ENV)
append_if_present(candidates, env("JAVA_HOME"), EXPLICIT_JAVA_ENV)
append_if_present(candidates, java_from_path(), PATH)
append_all(candidates, platform_discovery())

validated := normalize_deduplicate_validate(candidates, minimum = 21)

java := first_valid(EXPLICIT_LOCAL, EXPLICIT_FLIX_ENV, EXPLICIT_JAVA_ENV)
     or highest_known_lts(validated)
     or first_in_stable_discovery_order(validated)
     or fail(NO_COMPATIBLE_JAVA)

jar := valid_cached_jar(version)
    or download_validate_install_atomically(version)
    or fail(COMPILER_UNAVAILABLE)

chdir(root)
exec(java, "-jar", jar, original_arguments)
```

## Appendix B. Minimum Test Matrix

| Area | Required cases |
|---|---|
| Manifest | valid, comments, whitespace, CRLF, BOM, missing key, duplicate key, malformed value, malicious-looking value |
| Java precedence | local TOML, `FLIX_JAVA_HOME`, `JAVA_HOME`, PATH, platform discovery |
| Java policy | 25+21, 25+non-LTS, 21+non-LTS, non-LTS only, incompatible only, none |
| Cache | hit, miss, offline hit, offline miss, corrupt entry, concurrent population, read-only cache |
| Download | success, redirect, timeout, 404, HTML response, truncated JAR, digest mismatch when available |
| Arguments | spaces, quotes, empty argument, Unicode, leading hyphens, unknown future Flix command |
| Paths | spaces, symlinked wrapper, non-ASCII home, long Windows path, invocation from `src/` |
| Exit behavior | success, compiler error, test failure, signal/interruption, Java launch failure |
| Platforms | Ubuntu, macOS Intel fixture, macOS ARM fixture, Windows `cmd.exe` |

## Appendix C. Proposed Stable Error Identifiers

| Identifier | Meaning |
|---|---|
| `FLIXW001` | `flix.toml` not found at wrapper project root |
| `FLIXW002` | `[package].flix` missing, ambiguous, or unsupported |
| `FLIXW003` | no compatible Java 21+ installation found |
| `FLIXW004` | explicitly configured Java is invalid or incompatible |
| `FLIXW005` | compiler download failed |
| `FLIXW006` | downloaded or cached compiler failed validation |
| `FLIXW007` | compiler cache is unavailable or locked |
| `FLIXW008` | local configuration is malformed |
| `FLIXW009` | wrapper command or option usage error |
