# flixw

[![CI](https://github.com/wstein/flixw/actions/workflows/ci.yaml/badge.svg)](https://github.com/wstein/flixw/actions/workflows/ci.yaml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![bootstraps on Java 16+](https://img.shields.io/badge/bootstrap-java%2016%2B-orange.svg)](https://openjdk.org/projects/jdk/16/)
[![Flix needs Java 21+](https://img.shields.io/badge/flix-java%2021%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![dependencies: none](https://img.shields.io/badge/dependencies-none-brightgreen.svg)](src/flixw.java)
[![platforms: linux | macOS | windows](https://img.shields.io/badge/platforms-linux%20%7C%20macos%20%7C%20windows-lightgrey.svg)](.github/workflows/ci.yaml)
[![docs](https://img.shields.io/badge/docs-wstein.github.io%2Fflixw-blue.svg)](https://wstein.github.io/flixw/)

`./flixw` pins one Flix compiler version in your repository and runs it — no Flix
installation, no fork, no patched build. A collaborator with nothing but a JDK clones and
compiles with the same bytes you did.

> **What the digest does and does not prove.** It is recorded from whatever the first `pin`
> downloaded, so it guarantees everyone runs the *same* bytes — not that those bytes are
> the ones the Flix project built. Flix publishes no signatures.
> [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) states the difference plainly and is worth
> reading before trusting this with a download. This is an **experimental, third-party,
> opt-in** tool, not affiliated with or endorsed by the Flix project.

## Start here

| you have | go to |
|---|---|
| an existing Flix project | [the five steps below](#quickstart) — the default |
| an empty directory | [the five steps below](#quickstart), then `./flixw init` |
| Windows | the same steps — PowerShell and cmd.exe are collapsed inside [step 1](#1-download-the-setup-program-and-check-it) |
| a preference for not running downloaded programs | [manual installation](#manual-installation-without-running-a-downloaded-program) |

**Java.** flixw is itself a Java program, so it cannot be the thing that gets you your
first JDK.

| `java -version` says | what works | do this |
|---|---|---|
| nothing — no `java` | neither | install any JDK; flixw cannot bootstrap itself |
| Java 16–20 | flixw yes, Flix no | start below; then `./flixw wrapper --install-jdk` |
| Java 21+ | both | start below |

See [what if my Java is older](#what-if-my-java-is-older) for why the two floors differ.

## Quickstart

Install the committed wrapper, pin one Flix compiler version, commit the lock, then use
`./flixw` where you would have used `flix`. The output below is from real runs; version
strings, digests and paths will differ on your machine.

> **wrapper** what flixw writes into your repo — two shims, stage 0, and a `.gitignore` ·
> **pin** the act of choosing one exact compiler release · **lock** `.flixw/lock.toml`,
> which records that choice and its SHA-256 · **cache** a machine-wide directory outside
> your project where the compiler JAR and JDKs are kept

### 1. Download the setup program, and check it

The expected SHA-256 for v0.25.1 is

```
1f73d7fd875945dbab6e817cf1eb2a650ca2fa8bbd73197dfaff54682645f851  flixw-setup.java
```

```console
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.25.1/flixw-setup.java

# paste the digest above; this prints "OK" and exits non-zero if it does not match
echo "1f73d7fd875945dbab6e817cf1eb2a650ca2fa8bbd73197dfaff54682645f851  flixw-setup.java" \
  | sha256sum -c -            # macOS: shasum -a 256 -c -
```

That is a check, not a comparison you make by eye — but the digest still comes from this
page, not from the download. The digest is printed here rather than piped
from the release's own `SHA256SUMS`, because taking it from the same place as the file
proves only that the download arrived intact. This page is a different artifact, on a
different path, with its own history — a second opinion rather than an echo, and
`tests/lint.sh` fails if it stops matching what a release publishes.

`sha256sum` is coreutils and busybox; `shasum -a 256` is what stock macOS has. Neither is
on every machine, which is why both are given.

<details>
<summary>The same, in cmd.exe</summary>

`certutil` is the built-in — cmd has no `Get-FileHash`, and `curl.exe` ships with
Windows 10 and later:

```bat
curl -fsSLO https://github.com/wstein/flixw/releases/download/v0.25.1/flixw-setup.java
certutil -hashfile flixw-setup.java SHA256
java .\flixw-setup.java
del flixw-setup.java
```

`certutil` prints the digest on a line of its own between two lines of chatter. Older
Windows builds space the bytes in pairs; compare it without the spaces.

Then read `.\flixw.cmd` wherever the five steps write `./flixw`: `.\flixw.cmd pin 0.75.2`,
`.\flixw.cmd check`, `.\flixw.cmd validate`. `setup` writes both shims on every platform,
so the POSIX `flixw` is there too and is what Git Bash and WSL use.
</details>

<details>
<summary>The same, in PowerShell</summary>

```powershell
Invoke-WebRequest -OutFile flixw-setup.java `
  https://github.com/wstein/flixw/releases/download/v0.25.1/flixw-setup.java
(Get-FileHash -Algorithm SHA256 flixw-setup.java).Hash.ToLower()
# compare with the digest printed above before running the next line
java .\flixw-setup.java
Remove-Item flixw-setup.java
```

Then read `.\flixw.cmd` wherever the five steps write `./flixw`: `.\flixw.cmd pin 0.75.2`,
`.\flixw.cmd check`, `.\flixw.cmd validate`. Git Bash and WSL run the POSIX shim, so
there the commands work as written.
</details>

**Network happens in three places, and nowhere else.** `setup` downloads the wrapper code
and verifies it; `pin` downloads the compiler and verifies it; the JDK and completion
helpers download only when you ask for them by name. An ordinary `./flixw check` on a
pinned project touches the network not at all.

### 2. Run it, in the project root

```console
java ./flixw-setup.java
rm flixw-setup.java
```

In your project it writes the wrapper files and nothing else — it merges its block into an
existing `.gitattributes` rather than replacing it, and never touches `flix.toml` or
`.flixw/lock.toml`. It does reach the network once, to fetch and digest-verify this
release's wrapper code into a cache outside your project.

**Its last line tells you which of two situations you are in.**

<table>
<tr><th>already pinned</th><th>first adoption</th></tr>
<tr><td>

```
the compiler pin is untouched;
commit the wrapper files that changed:
  git add flixw flixw.cmd .flixw
```

Nothing further to run. `.flixw/lock.toml` is not rewritten, so the compiler version, its
digest, the repository it came from and any declared plugins all survive. Skip to step 5.

</td><td>

```
next: ./flixw pin <version>
      then commit all five files
```

The project has no lock yet. Continue with step 3.

</td></tr>
</table>

### 3. Pin a compiler

```console
$ ./flixw pin 0.75.2
flixw: pinned Flix 0.75.2 from flix/flix (a2697d875725a0dd...)
```

This is the trust root: it fetches that exact release, hashes it, and records the digest in
`.flixw/lock.toml`. Every later run re-checks it. `flix.toml` is untouched — that file is
Flix's, and `pin` has no business editing it.

An empty directory needs `./flixw init` after this, which is a compiler verb: Flix
scaffolds the project around the wrapper.

### 4. Run the compiler

```console
$ ./flixw check
$ ./flixw test
Passed: 1, Failed: 0. Skipped: 0. Elapsed: 3.4ms.
```

An ordinary verb goes straight to the pinned compiler. `pin`, `info`, `doctor`, `validate`
and `help` are flixw's own, because a project needs them before it can reach a compiler at
all.

### 5. Commit

```console
git add flixw flixw.cmd .flixw .gitattributes
```

**What gets committed:** `flixw`, `flixw.cmd`, `.flixw/flixw.java`, `.flixw/.gitignore`,
`.flixw/lock.toml`, and the flixw block merged into your `.gitattributes`.
**Never** `.flixw/local/` — that is this machine's resolved JDK and belongs to no one else.

A collaborator with nothing but a JDK now gets the compiler you have.

## After your first successful run

### Coming from flixw 0.20.0–0.24.1

Those releases upgrade by running `flixw.java install`, which no longer exists, so
`./flixw wrapper --upgrade` fails with `FLIXW001` and **changes nothing at all**. The five
steps above are the way across, and they are the same steps: your `lock.toml` and compiler
pin survive, so step 2 will tell you the pin is untouched and there is nothing else to do.
[`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) has the full failure text.

`flixw-setup.java` is a program you run directly, not a flixw verb — stage 0 has no
`install` at all. That is deliberate: `install` is a name Flix could claim for a project's
dependencies, so `./flixw install` reaches the compiler, like every other word flixw does
not own.

### What you are committing

The `.flixw/flixw.java` in your project is the documented source with its comments removed
— 3256 lines rather than 4651 — with a header pointing at
[the docs](https://wstein.github.io/flixw/) and [the source](https://github.com/wstein/flixw)
for the reasoning behind every check. The strip is reproducible from the tagged source, so
the file you can read and the file you run can be compared rather than taken on trust.

### When something goes wrong

| symptom | what it means | do this |
|---|---|---|
| the shim prints install instructions and stops | no `java` on `PATH` at all | install any JDK 16+; flixw cannot bootstrap itself |
| `FLIXW003: no Java in [21, …]` | flixw runs, the compiler cannot | `./flixw wrapper --install-jdk`, or install a JDK 21+ |
| `FLIXW002: no .flixw/lock.toml` | the project has never pinned | `./flixw pin <version>` |
| `FLIXW005: cannot reach …` on a first pin | no network, and nothing cached yet | pin once online; afterwards the cache serves it |
| `FLIXW006: digest mismatch` | the bytes are not what the lock pins | do not override it — re-pin, or find out why they differ |

### What if my Java is older?

Two versions of "you need a JDK" are true at once. The pinned *compiler* needs Java 21+;
stage 0 itself compiles on Java 16. So with anything from 16 up, flixw runs, tells you the
compiler will not, and `./flixw wrapper --install-jdk` fetches a verified Temurin 21 into
its own cache rather than touching your system.

Below 16, and with no Java at all, that command cannot help: it is a Java program and there
is nothing to run it. You get the shim's own message naming the install command for your
platform instead. First contact needs a JDK you installed yourself.

From here an ordinary verb is the stock compiler, run by the wrapper — `pin`, `info`,
`doctor`, `validate` and `help` are flixw's, because a project needs them before it can
reach a compiler at all:

```console
$ ./flixw test
Passed: 1, Failed: 0. Skipped: 0. Elapsed: 3.4ms.

$ ./flixw validate
ok    ./flixw matches flixw 0.25.1
ok    ./flixw.cmd matches flixw 0.25.1
ok    .flixw/flixw.java  sha256=c41d7b3eec8f91ce...
ok    the lock satisfies flix.toml
ok    the compiler reports the version the lock pins
```

The JDK can be pinned too, in the same file and for the same reason — a version, not a
path, since a path is true on one machine only:

```console
$ ./flixw pin --java 21
flixw: pinned java 21
```

Any vendor's JDK satisfies it, `21.0.12` pins harder than `21`, and a machine without a
match is told so before anything is downloaded. Leave it out and flixw picks the newest
tested JDK it can find, which is what it has always done.

`./flixw validate` prints the digest of the stage 0 in your project, so it can be compared
against the release you meant to install.

### What the project looks like

Nine committed files and one that stays on your machine. flixw writes five of the nine and
shares a sixth; all of them are committed on purpose, so a clone needs no bootstrap step of its own and no `flix` on `PATH`. (`init`
also scaffolds a `README.md`, a `LICENSE.md`, a `.gitignore` and a CI workflow, which are
yours to keep or delete.)

```text
hello/
├── flixw                  the wrapper you actually run; a POSIX sh shim
├── flixw.cmd              the same, for cmd.exe and PowerShell
├── .flixw/
│   ├── flixw.java         stage 0: the bootstrap, one dependency-free Java file
│   ├── .gitignore         keeps local/ out of git
│   ├── lock.toml          the pin — repository, version, URL, SHA-256
│   └── local/java         the JDK this machine resolved to — not committed
├── .gitattributes         line endings for the five above, as a block in your file
├── flix.toml              your project: name, dependencies, the minimum Flix
├── src/Main.flix          your code
└── test/TestMain.flix     your tests
```

`flixw`, `flixw.cmd`, `flixw.java` and `.gitignore` are byte-identical in every project on
the same flixw release, so one published digest validates all four. `lock.toml` is yours,
`.gitattributes` is yours with a block of ours in it, and `local/java` belongs to this
machine alone. Not committed, and safe to delete at any time:

```text
build/  lib/  artifact/  .flix-cache/    Flix's own output and dependency cache
<cache>/                                 verified compiler JARs and JDKs, shared
                                         across every project on the machine
```

`<cache>` is `FLIX_CACHE_HOME` if you set it, and otherwise the place the platform keeps
caches:

| | |
|---|---|
| Linux, BSD | `${XDG_CACHE_HOME:-~/.cache}/flixw` |
| macOS | `~/Library/Caches/flixw` |
| Windows | `%LOCALAPPDATA%\flixw` |

`./flixw info` prints the one in use; `./flixw info --verbose` (or `-v`) lists what is
actually cached there -- every compiler JAR and JDK, not just the ones this project has
pinned -- plus every JDK it can find on the machine without flixw having installed it
(Homebrew, scoop, sdkman, asdf, mise, jenv, and the usual OS install directories).
When space matters, `./flixw wrapper --purge [days]` offers each flixw cache entry unused
for 14 days by default for deletion: compiler JARs, provisioned JDKs, plugins and old
companion assets. flixw records its own last-use date rather than trusting filesystem access
time. Answer each prompt, or add `--yes` for an intentional non-interactive purge. It retains
the default JDK, this release's assets and stage-0 cache; entries without a flixw use record
are retained conservatively.

A real project on this: [`flix-invaders`](https://github.com/wstein/flix-invaders), by
the same author, which type-checks, tests, formats and packages through `./flixw` on
Linux, macOS and Windows, and keeps a
[pin-lag log](https://github.com/wstein/flix-invaders/blob/main/docs/pin-lag.md) — one row
per Flix release — so the cost of pinning is a number rather than an argument. That is one
project; [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) says exactly what is and is not
established.

## Manual installation, without running a downloaded program

For when you would rather not run a downloaded program to install a program. Its digests come from the release's own `SHA256SUMS`, which proves the
download arrived intact and nothing more — unlike the setup asset's digest above, which is
published here, on a different path, and can be compared against something the release did
not serve:

```console
base=https://github.com/wstein/flixw/releases/download/v0.25.1
curl -fsSLO $base/flixw-0.25.1.tar.gz
curl -fsSL  $base/SHA256SUMS | grep flixw-0.25.1.tar.gz | sha256sum -c -
tar -xzf flixw-0.25.1.tar.gz        # flixw, flixw.cmd, .flixw/flixw.java
rm flixw-0.25.1.tar.gz
./flixw pin <version>               # writes the lock, fetches and verifies the compiler
                                    # 0.75.2 or v0.75.2 -- the release tag works too
./flixw doctor --fix                # merges the .gitattributes block
git add flixw flixw.cmd .flixw .gitattributes
```

The digest line is a check you run, not a comparison you eyeball: it prints `OK` or fails.
On Windows the equivalents are `Get-FileHash flixw-0.25.1.tar.gz` and `Expand-Archive` in
PowerShell, or `certutil -hashfile flixw-0.25.1.tar.gz SHA256` and `tar -xf` in cmd.exe —
`tar` ships with Windows 10 and later.

Pick `<version>` to satisfy the `flix` key your `flix.toml` already has. That key is Flix's
own field and flixw reads it as a **minimum**, so the same version or anything newer is
fine. Pinning something older is allowed but warned about, and every later command that
needs the compiler refuses until it is resolved, naming both numbers and the repair — `pin`
is deliberately still usable in that state, because lowering the floor in `flix.toml` may
be exactly what you meant. flixw never edits the manifest itself.

A `.zip` with the same contents is attached for machines without `tar`. The archives leave
`.gitattributes` alone rather than overwriting the one your project already has, which is
why `doctor --fix` — which *merges* the block — is a step of its own; `install` does that
merge itself. It comes after `pin` because it reports on the whole installation, and until
there is a lock the honest report is that one is missing.

Install from a release rather than from `main`: a tool that asks you to pin an exact
compiler should not ask you to fetch itself from a moving branch.

Once installed, `./flixw wrapper --upgrade` moves the project to the newest release.
`./flixw wrapper --help` prints the routing table: which verbs go to the compiler, which to
the wrapper, and how to force either.

## The lock, in your editor

`.flixw/lock.toml` is generated, and its first line says what it is:

```toml
#:schema https://wstein.github.io/flixw/schema/lock-v1.schema.json
```

That is the directive [taplo](https://taplo.tamasfe.dev) and the Even Better TOML
extension follow, so an editor validates the file — completing keys, flagging a mistyped
one, explaining what each holds — with nothing configured per project. `./flixw wrapper --schema`
prints the same schema on stdout if you would rather validate offline; it is rendered from
the list stage 0 itself checks the lock against, so the two cannot disagree.

Nothing about the build depends on the line. A lock written by an older flixw has none, and
`./flixw validate` says so:

```console
$ ./flixw pin --refresh
flixw: rewrote .flixw/lock.toml in the shape flixw 0.25.1 writes; the pin is unchanged
```

That is offline and moves nothing — same repository, version, URL, digest and java pin —
which is why it is not `pin <version>`, which would fetch the compiler again to write one
comment. `./flixw doctor --fix` performs the same rewrite as one repair among several.

The schema, the API docs for stage 0, and a short index of both live at
<https://wstein.github.io/flixw/>, published from the same tag as the release.

## TAB completion

```sh
./flixw wrapper --completion bash > ~/.local/share/bash-completion/completions/flixw
./flixw wrapper --completion zsh  > "${fpath[1]}/_flixw"
./flixw wrapper --completion fish > ~/.config/fish/completions/flixw.fish
./flixw wrapper --completion pwsh >> $PROFILE
```

Write it once. The script holds no verbs — it reads them when you press TAB from a note the
wrapper keeps in `.flixw/local/`, so completion follows the pin and you do not regenerate
anything after `./flixw pin`. Different projects on the same machine complete their own
verbs. Nothing starts a JVM at TAB time, so TAB stays instant.

The generator behind `wrapper --completion` is fetched from the flixw release you're
running and cached machine-wide, the same way `wrapper --upgrade` fetches `flixw.java`
itself — so the very first `--completion` call on a machine, for a given flixw release,
needs network once; every call after that, from any project, is offline.

In bash, if your compiler ships its own completer — a picocli-based fork does — flixw finds
it and options complete too. Everywhere else, and with stock Flix anywhere, completion
covers the verb and then hands over to ordinary filename completion.

`cmd.exe` is not supported and cannot be: it has no per-command completion mechanism.
PowerShell works against the `flixw.cmd` that already ships; nothing needs to move to a
`.ps1`.

## Plugins and tasks

Two ways to extend what `./flixw` runs, both opt-in. `pin`, `info`, `doctor`, `validate`
and `help` stay built in permanently — they are what a fresh clone needs before anything
else can be trusted — but everything past them can be a task or a plugin.

**Tasks** are the lightweight one: `.flixw/tasks.toml` is a flat `name = "shell command"`
table you write and commit yourself, the same idea as npm's `scripts`. Nothing is fetched.

```toml
build = "./flixw build && ./flixw build-jar"
```

```console
./flixw task            # lists the names you have defined
./flixw task build      # runs it
./flixw task build --release   # extra words are appended to the command
```

**Plugins** are the heavier one: third-party `.jar`, `.java` or `.flix` code, installed
once into a machine-wide, digest-verified cache and invoked by name.

```console
./flixw plugin install metrics 1.2.0 https://example.com/metrics/plugin.jar
./flixw plugin metrics --since 30d
./flixw plugin list
./flixw plugin remove metrics
```

Every invocation re-verifies the cached bytes against the digest install recorded, and says
on stderr that it is running unaudited third-party code — a digest proves the bytes have not
changed, not that they are safe. `--sha256 <digest>` at install time lets you pin the digest
you expect instead of trusting whatever the URL returns.

A plugin gets a small, versioned context — project root, cache location, the pinned
compiler's version and jar path, the JDK in use — as both environment variables
(`FLIXW_PROJECT_ROOT`, `FLIXW_CACHE_HOME`, `FLIXW_COMPILER_VERSION`, …) and a JSON file
named by `FLIXW_CONTEXT`, so it can extend what Flix does in this project without flixw
handing it a second, unverified compiler to run. A `.flix` plugin reads that same context
through `Sys.Env.getVar` even though it cannot receive command-line arguments — stock Flix
has no way to run a standalone file with `args`.

See [`docs/CONTRACT.md`](docs/CONTRACT.md#plugins-and-tasks) for exactly what is guaranteed.

## Testing a locally built compiler

If you build Flix yourself — a fork, or a patch you have not tagged yet — run it with:

```sh
FLIX_JAR=/path/to/flix.jar ./flixw run
```

Two caveats. The jar is **not** digest-verified, every such run says so on stderr, and
those runs are not evidence about the stock compiler. And a valid `.flixw/lock.toml` is
still required: the lock is read and drift is checked before the override is, so pin a
release first even if you intend to override it every time.

To set this and the other variables — `FLIX_JAVA_HOME`, `FLIX_CACHE_HOME`,
`FLIX_DIST_URL`, `FLIX_JVM_OPTS` — per project rather than per shell, write them into an
`.envrc` for [direnv](https://direnv.net) and run `direnv allow`. The full table is in
[docs/CONTRACT.md](docs/CONTRACT.md). direnv needs its hook in your shell's startup file
first:

```sh
eval "$(direnv hook bash)"   # ~/.bashrc
eval "$(direnv hook zsh)"    # ~/.zshrc
direnv hook fish | source    # ~/.config/fish/config.fish
```

The `.envrc` is bash whatever your own shell is — direnv evaluates it with bash and exports
the difference — so fish users still write `export FOO=bar` in it, not `set -x FOO bar`.

flixw itself never reads it; direnv sets the variables in your shell before flixw
starts, which is why it works in a terminal but not for an editor-spawned `flixw lsp`, and
why there is no `cmd.exe` equivalent. flixw ships no `.envrc` template — the variables are
listed above and in `docs/CONTRACT.md`, which is where they stay current. See [`docs/CONTRACT.md`](docs/CONTRACT.md#running-a-locally-built-compiler) for the full
rules.

## Documentation

- [`docs/CONTRACT.md`](docs/CONTRACT.md) — what is guaranteed, and the diagnostics table
- [`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) — measured overhead, with the method
- [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) — what it cannot do, stated plainly
- [`docs/Flix_Bootstrap_Wrapper_Paper.md`](docs/Flix_Bootstrap_Wrapper_Paper.md) — the
  design paper this grew from, kept as historical evidence

## Repository layout

```text
flixw/
├── src/
│   ├── flixw.java             stage 0 — the whole bootstrap, one dependency-free Java file
│   ├── flixw-setup.java       the bootstrap: fetches and verifies stage 0, writes a project
│   ├── flixw-jdk.java         optional JDK provisioning, for `wrapper --install-jdk`
│   ├── flixw-inspect.java     the cache inventory behind `info --verbose`
│   ├── flixw-completion.java  TAB-completion generator
│   │                          — the four companion assets: published per release, fetched
│   │                            on first use, digest-verified, never committed to a project
│   ├── flixw                  POSIX shim — finds a Java, prefers the compiled stage 0
│   └── flixw.cmd              cmd.exe shim — the same, without a POSIX shell
├── tests/             regression suite, unit checks, and a corpus of 95 real
│                      flix.toml files, checked against a TOML oracle
└── docs/              contract, benchmarks, limitations, design paper
```

## Development

```console
sh tests/lint.sh    # javac -Xlint:all -Werror, shellcheck, shim byte-parity
sh tests/run.sh     # regression suite (needs network on first run)
```

Both are required before a commit. In CI, `lint.sh` runs once on Linux — `javac` and
`shellcheck` answer the same on every platform — while `run.sh` runs on Linux, macOS and
Windows, on Java 21 and again on the tested ceiling, and Windows additionally gets a
`cmd.exe` job that installs into a scratch project and drives `flixw.cmd` end to end.

`sh tests/pack.sh <dir>` builds the release archives locally, by the same script the release
workflow runs — so a published digest can be reproduced rather than trusted. It needs
`java`, `zip`, `tar`, and `sha256sum` or `shasum`; the byte-for-byte reproduction of a
published archive needs GNU `tar`, which is why the release job runs on Linux.
`sh tests/fetch-corpus.sh` refreshes the manifest corpus; it is a maintenance tool rather
than a test, and needs `gh`, `curl` and python3. See
[`tests/corpus/README.md`](tests/corpus/README.md).

## License

MIT. These files are meant to be committed into other people's repositories; the license
permits that without conditions beyond attribution.
