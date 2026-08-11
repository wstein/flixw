# Known limitations

Stated plainly, because a tool that asks you to trust it with a download does not get to
be vague about what it cannot do.

## The pin is trust-on-first-generation, not authenticity

`flix pin` records the SHA-256 that GitHub reports for the release asset, and every later
run verifies the cached JAR against it. That defends against truncation, cache
corruption, a proxy substituting the file, and silent tampering after the pin.

It does **not** establish that the bytes came from the Flix team. GitHub serves both the
asset and the digest over the same TLS trust anchor, and a release asset can be deleted
and re-uploaded under the same tag. If the first generation was compromised, the pin
faithfully preserves the compromise.

Fixing this needs something only upstream can publish: a checksum file alongside the
release, or a signed build-provenance attestation. Until then, review the digest in the
lock the way you would review any other dependency pin — it is a committed, diffable,
git-blameable line, which is the most this design can offer.

The wrapper files have the same shape of problem one level up: published hashes protect a
release you already have, not the first copy you obtained.

## SIGKILL to stage 0 orphans the compiler

Java has no `exec(2)`. Stage 0 therefore spawns the compiler and stays resident for its
whole life, which has three consequences:

- **Resident cost.** Stage 0 holds roughly 25–50 MB while the compiler runs, against a
  compiler that peaks near 1 GB. On a memory-capped CI container this is a real line
  item, if a small one.
- **SIGTERM is handled.** A shutdown hook destroys and reaps the child, so terminating
  stage 0 does not leave a compiler running. This is tested.
- **SIGKILL is not, and cannot be.** `kill -9` on stage 0 runs no hook, and the compiler
  is orphaned. No Java code can prevent this. If you script around `flixw`, prefer
  `SIGTERM`.

Ctrl-C is fine: both processes are in the terminal's foreground process group, so the
signal reaches the compiler directly.

## Windows is untested

`flix.cmd` is written and lint-checked but has never been executed — there is no Windows
machine in this project's loop yet. Specific risks:

- `cmd.exe` transforms arguments before any script sees them. `%VAR%` is expanded at
  parse time and cannot be recovered, `!` is destroyed under delayed expansion, and `^`
  and quoting do not round-trip through `%*`. Byte-exact argument parity with the POSIX
  shim is **not** achievable and is not claimed.
- Ctrl-C in `cmd.exe` prints `Terminate batch job (Y/N)?` and may leave the child
  running.
- The `certutil` hash used to find the compiled stage 0 is best-effort; if it fails the
  shim falls back to the source launch, which is slower but correct.

`java .flix-wrapper\flix.java <args>` is the lossless fallback on Windows: it needs no
shim, no shell, and no execution policy. Git Bash also works, and is what this project's
own CI has used.

There is deliberately no PowerShell script. A Group-Policy `MachinePolicy` execution
policy cannot be overridden by `-ExecutionPolicy Bypass`, so a `.ps1` can be
administratively unrunnable in exactly the corporate environments where it would matter.

## Help introspection is not a contract

Compiler-first dispatch needs to know which verbs the pinned compiler implements, and the
only source is `flix --help`, whose format is `scopt`'s renderer output. Nothing upstream
promises it. Flix 0.75.1 and 0.75.2 are byte-identical here apart from one experimental
option line, which is evidence about two adjacent patch releases and nothing more.

When parsing fails, stage 0 warns once with `FLIXW010` and uses a built-in table, so a
future reformat costs accuracy on one narrow question rather than bricking every pinned
project. Worst case the wrapper is one release stale about whether Flix has claimed a
wrapper verb — a fact that changes rarely and is announced in release notes.

A machine-readable command list from upstream would remove this entirely.

## No JDK provisioning

`flixw` finds a Java; it does not install one. On a machine with no compatible JDK you
get `FLIXW003` and an instruction, not a download.

This is a deliberate scope limit, not an oversight: provisioning a JDK means picking a
vendor, tracking per-platform archives and digests, handling extraction safely, and
owning a licensing story. Coursier (`cs java`) and JBang already do all of it. Delegating
to one of them is a separate experiment, and until it runs the promise here is narrow —
*a contributor who already has Java can skip installing Flix*, not zero-install
bootstrapping.

## The manifest is read with a regex

`flix.toml` and the lock are read by matching a quoted scalar, not by a TOML parser. That
keeps stage 0 dependency-free, which is the point of the design, but it is the weakest
part of it: an exotic-but-legal manifest — a multi-line string containing a decoy
assignment, say — could be misread.

The mitigations are that both files are small and generated or hand-edited by one line,
that the version is grammar-validated after extraction, and that a wrong version fails at
the digest rather than silently running something else. It is still the first place to
look if a manifest behaves oddly, and the first thing a corpus test over real published
manifests should attack.

## No field evidence

This wrapper has been exercised by a 55-case regression suite on one machine, one OS, and
one compiler release. It has not been run across the real Flix release cadence, by anyone
other than its author, or on any project but a scratch fixture and one game.

Every claim in [CONTRACT.md](CONTRACT.md) is tested. None of them is *proven in the
field*, and the policy question the design paper raises — whether pinning helps or hurts
a project tracking a pre-1.0 language — cannot be answered by tests at all.
