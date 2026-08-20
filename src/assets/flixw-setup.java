import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * flixw's installer: the bytes a project actually receives.
 *
 * <p>Fetched once per machine per flixw release by {@code wrapper --install} and by
 * {@code doctor --fix}, verified against that release's own {@code SHA256SUMS}, cached,
 * and run as {@code java flixw-setup.java <verb> <target> <version> [<stage0-source>]}.
 * {@code wrapper --upgrade} warms it along with every other companion asset, so the fetch
 * happens at a moment the user asked for network rather than the next time they did not.
 *
 * <p>It is here because it is the largest thing stage 0 carried that stage 0 never uses.
 * Two shims, a .gitignore and a .gitattributes block are ~440 lines of text and
 * file-writing that run on first contact and on repair, and never once on the path every
 * other invocation takes.
 *
 * <p><b>Stage 0 still owns whether they are correct.</b> It keeps the SHA-256 of both
 * shims and compares them on {@code validate} and {@code doctor}, so drift is detected
 * offline, with no fetch, exactly as before -- only *repair* reaches for this file. That
 * split is deliberate: a wrapper that could not tell you your shim was wrong without a
 * network is worse than one that cannot fix it.
 *
 * <p>Nothing here is on any hot path, so it favours clarity over economy.
 */
final class flixwsetup {
    private flixwsetup() {}

    /**
     * The wrapper's directory name, which is also written into the shims below.
     *
     * <p>Stage 0 has its own {@code WRAPPER_DIR} and the two must agree; {@code
     * tests/lint.sh} fails if they do not, the same way it checks the Java floor that is
     * spelled out in three files. Passing it as an argument was the alternative and is
     * worse: it appears inside the shim text as a literal, so a caller could only ever
     * pass the value already baked in here.
     */
    static final String WRAPPER_DIR = ".flixw";
    /** Where GitHub redirects to the newest Flix; the tag is in the target URL. */
    static final String FLIX_LATEST = "https://github.com/flix/flix/releases/latest";

    /**
     * The release this installer belongs to, and therefore the stage 0 it installs.
     *
     * <p>A constant rather than an argument because this file is the *entry point* now:
     * somebody downloads it, checks its digest and runs it, with nothing else in hand.
     * It has to know which release it is in order to fetch the matching stage 0 -- asking
     * the caller would mean asking somebody who has no way to know the right answer.
     *
     * <p>Stage 0 has its own {@code WRAPPER_VERSION} and the two must agree;
     * {@code tests/lint.sh} fails if they do not, the same way it checks the Java floor
     * and {@code WRAPPER_DIR}.
     */
    static final String WRAPPER_VERSION = "0.25.7";

    /** A SHA256SUMS is a few hundred bytes; this is room to spare, not a target. */
    static final int METADATA_CAP = 1 << 21;

    /** Where a release's files live. Overridable for this project's own tests. */
    static String sourceBase() {
        String o = System.getenv("FLIXW_ASSET_SOURCE");
        if (o != null && !o.isBlank()) return o.replaceAll("/+$", "") + "/";
        return "https://github.com/wstein/flixw/releases/download/v" + WRAPPER_VERSION + "/";
    }

    /**
     * Fetches this release's stage 0 and checks it against the digest published beside it.
     *
     * <p>This is the bootstrap's whole trust step, and it is why the entry point moved
     * here: what a person has to read before running anything is now this file, not the
     * 3288-line program it installs. Both are named in the same {@code SHA256SUMS}, so
     * verifying either establishes the other -- the difference is only in what a human
     * can actually finish reading.
     */
    static Path fetchStage0(Path into) {
        String base = sourceBase();
        String sums;
        try {
            sums = base.startsWith("file://")
                ? Files.readString(Paths.get(URI.create(base + "SHA256SUMS")), StandardCharsets.UTF_8)
                : httpGet(base + "SHA256SUMS");
        } catch (IOException e) {
            throw w005("cannot read " + base + "SHA256SUMS: " + why(e));
        }
        String want = null;
        for (String line : sums.split("\r?\n")) {
            String[] f = line.trim().split("\\s+");
            // A `*` before the name marks binary mode in GNU coreutils, and is the
            // default on Windows -- so a mirror whose digests were generated there lists
            // *flixw.java, and an exact comparison finds nothing while the digest is
            // plainly in the file.
            if (f.length == 2 && (f[1].equals("flixw.java") || f[1].equals("*flixw.java")))
                want = f[0];
        }
        if (want == null || !want.matches("[0-9a-f]{64}"))
            throw w005("the published SHA256SUMS for " + WRAPPER_VERSION
                     + " names no digest for flixw.java");
        Path tmp = into.resolve("flixw.java.part");
        try {
            if (base.startsWith("file://"))
                Files.copy(Paths.get(URI.create(base + "flixw.java")), tmp,
                           StandardCopyOption.REPLACE_EXISTING);
            else download(base + "flixw.java", tmp);
            String got = sha256(tmp);
            if (!got.equals(want))
                throw w006("digest mismatch for flixw.java"
                         + "\n       published " + want + "\n       downloaded " + got);
            return tmp;
        } catch (IOException e) {
            throw w007("cannot fetch stage 0: " + why(e));
        }
    }

    /**
     * Redirects are followed, but only ever onto https -- and the scheme of the *final*
     * URI is what is checked, because that is the one the bytes actually came from.
     */
    /**
     * Pins a compiler straight after setting up, so adopting flixw is one command.
     *
     * <p>Only when this project has no lock yet, unless a version was named outright. That
     * rule is what keeps `wrapper --upgrade` -- which reaches this same code with an already
     * verified stage 0 -- from re-pinning a project that has a perfectly good lock, and it is
     * why upgrade does not have to say so.
     *
     * <p>Run through the stage 0 just installed rather than reimplemented here. Pinning is
     * where the trust root is created, and there should be exactly one implementation of it
     * for the same reason there is exactly one lock writer.
     */
    static boolean willPin(boolean pinning, Path target, String wanted) {
        return pinning && (wanted != null
                           || !Files.isRegularFile(target.resolve(WRAPPER_DIR).resolve("lock.toml")));
    }

    static void pinAfterSetup(boolean pinning, Path target, String wanted) {
        if (!willPin(pinning, target, wanted)) return;
        String version = wanted != null ? wanted : latestFlix();
        if (version == null) {
            System.err.println("flixw: could not reach github.com to find the newest Flix");
            System.err.println("       set up; pin when you can:  ./flixw pin <version>");
            return;
        }
        Path java = Paths.get(ProcessHandle.current().info().command().orElse(
                        Paths.get(System.getProperty("java.home"), "bin", "java").toString()));
        try {
            int rc = new ProcessBuilder(java.toString(),
                        target.resolve(WRAPPER_DIR).resolve("flixw.java").toString(),
                        "pin", version)
                    .directory(target.toFile()).inheritIO().start().waitFor();
            // Not fatal: the files are written and the project is usable. A failed pin is a
            // `./flixw pin` away, and undoing a good install because of it would be worse.
            if (rc != 0) System.err.println("       set up; the pin did not complete"
                                          + " -- run: ./flixw pin " + version);
        } catch (IOException e) {
            System.err.println("       set up; could not run pin: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The newest Flix release, read from where GitHub redirects rather than from its API.
     *
     * <p>The API answers this in one field and rate-limits unauthenticated callers to sixty
     * an hour per address, which a CI runner shares with everything else on that address.
     * The redirect target of {@code /releases/latest} carries the tag and is not rate-limited.
     */
    static String latestFlix() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(FLIX_LATEST))
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "flixw-setup/" + WRAPPER_VERSION).build();
        try {
            HttpResponse<Void> res = httpClient().send(req, HttpResponse.BodyHandlers.discarding());
            String u = res.uri().toString();
            if (res.statusCode() != 200 || !u.contains("/releases/tag/")) return null;
            String tag = u.substring(u.lastIndexOf('/') + 1);
            return tag.startsWith("v") ? tag.substring(1) : tag;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** One bounded HTTPS GET returning text.  Metadata only; bytes go through download(). */
    static String httpGet(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "flixw-setup/" + WRAPPER_VERSION).build();
        try {
            // Bounded, because this response supplies both the JDK's URL and the digest it
            // will be verified against: a server that answers forever would otherwise be
            // answering into the heap. ofString has no cap, so the body is read by hand.
            HttpResponse<InputStream> res =
                httpClient().send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (!"https".equals(res.uri().getScheme()))
                throw w005("refusing a redirect off https: " + redact(res.uri().toString()));
            if (res.statusCode() != 200)
                throw w005("HTTP " + res.statusCode() + " from " + redact(url));
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (InputStream in = res.body()) {
                byte[] buf = new byte[1 << 16];
                int total = 0, n;
                while (total < METADATA_CAP && (n = in.read(buf)) > 0) {
                    sink.write(buf, 0, Math.min(n, METADATA_CAP - total));
                    total += n;
                }
                if (total >= METADATA_CAP)
                    throw w005("metadata from " + redact(url) + " exceeded "
                             + (METADATA_CAP >> 10) + "KiB");
            }
            return sink.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw w005("cannot reach " + redact(url) + "\n       " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw w005("metadata request interrupted");
        }
    }

    static void download(String url, Path dest) {
        if (!url.startsWith("https://")) throw w005("refusing non-https url " + redact(url));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "flixw-setup/" + WRAPPER_VERSION).build();
        try {
            HttpResponse<Path> res = httpClient().send(req, HttpResponse.BodyHandlers.ofFile(dest));
            if (!"https".equals(res.uri().getScheme()))
                throw w005("refusing a redirect off https: " + redact(res.uri().toString()));
            if (res.statusCode() != 200)
                throw w005("HTTP " + res.statusCode() + " for " + redact(url));
        } catch (IOException e) {
            throw w005("download failed: " + redact(url) + "\n       " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw w005("download interrupted");
        }
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw w006("cannot hash " + file + ": " + e);
        }
    }


    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static Path lockPath(Path root) { return root.resolve(WRAPPER_DIR).resolve("lock.toml"); }

    /** Mirrors stage 0's codes and advisory exits, so the split is invisible to a caller. */
    static final class Fail extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int exit;
        Fail(String code, int exit, String msg) { super(code + ": " + msg); this.exit = exit; }
    }

    static Fail w005(String m) { return new Fail("FLIXW005", 84, m); }
    static Fail w006(String m) { return new Fail("FLIXW006", 85, m); }
    static Fail w007(String m) { return new Fail("FLIXW007", 86, m); }
    static Fail w008(String m) { return new Fail("FLIXW008", 87, m); }
    static Fail w009(String m) { return new Fail("FLIXW009", 88, m); }

    /** Query strings and userinfo can carry a token; a diagnostic is not a place for one. */
    static String redact(String v) {
        if (v == null) return null;
        return v.replaceAll("://[^/@]*@", "://***@").replaceAll("\\?.*$", "?***");
    }

    static String why(Exception e) {
        String s = e.getMessage();
        return s == null || s.isBlank() ? e.getClass().getSimpleName() : s;
    }

    /** Same as stage 0's: a half-written shim is a project that cannot run at all. */
    static void writeAtomic(Path file, String text) throws IOException {
        Path dir = file.getParent();
        Path tmp = Files.createTempFile(dir, "." + file.getFileName() + "-", ".part");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { } }
        }
    }

    /**
     * The bootstrap. {@code java flixw-setup.java [dir]} adopts flixw into a project.
     *
     * <p>Stage 0 has no install verb at all: this file is what somebody downloads,
     * verifies and runs, and it fetches the stage 0 matching its own release. What has to
     * be read before anything executes is therefore ~640 lines rather than 3288 -- the
     * whole reason the entry point is here and not there.
     *
     * <p>{@code update <dir>} is the other half, and is reached only from
     * {@code ./flixw doctor --fix}: it rewrites the files this one wrote, without
     * fetching a stage 0, because the project already has one.
     */
    /**
     * Standalone entry: the code {@link #run} returns becomes the process's.
     *
     * <p>Kept so the asset still works when launched as a program -- which for the installer is
     * the documented bootstrap, and for the others is how a source launch runs them.
     */
    public static void main(String[] args)  {
        System.exit(run(args));
    }

    /**
     * The real entry point, returning what it would have exited with.
     *
     * <p>An asset used to end by calling {@code System.exit}, which is correct for a program and
     * fatal for a library: the wrapper now loads these in its own JVM, where an exit would take
     * the wrapper down mid-command and skip whatever it still had to clean up. So the exits
     * became a control-flow signal that stops at this boundary, and the code travels back as a
     * value the way any other result does.
     */
    public static int run(String[] args)  {
        try {
            body(args);
            return 0;
        } catch (Exit e) {
            return e.code;
        }
    }

    /** What {@code System.exit} used to do, scoped to this asset. */
    static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code) {
            // No message, no stack: it is a jump, not a failure, and filling one in for every
            // usage error would cost more than the check that raised it.
            super(null, null, false, false);
            this.code = code;
        }
    }

    private static void body(String[] args)  {
        // No arguments is the documented bootstrap and means "set this directory up".
        // With arguments the verb is required, which is stricter than it looks and is the
        // point: treating an unrecognised first word as a directory turned
        // `flixw-setup.java install .` -- the spelling a reader of any older instruction
        // would try -- into an attempt to set up a directory named "install".
        List<String> rest = new java.util.ArrayList<>(List.of(args));
        // `--pin` says which compiler, not whether to pin: naming a version is the only part
        // a caller can get wrong, and a flag says so where a bare `0.75.3` among the
        // positionals would have to be recognised by its shape and could never be told from
        // a directory that happened to look like one.
        String wanted = null;
        for (int i = 0; i < rest.size(); i++) {
            if (!"--pin".equals(rest.get(i))) continue;
            if (i + 1 >= rest.size()) {
                System.err.println("usage: --pin <version>");
                throw new Exit(87);
            }
            rest.remove(i);
            wanted = rest.remove(i);
            break;
        }
        // Pinning follows the spelling a person types. `setup <dir>` is the scripted form --
        // `wrapper --upgrade` uses it, and so does every case in the suite -- and a script
        // that asked for files must not also get a compiler download and a lock it never
        // mentioned. Asking for `--pin` outright is always honoured.
        // A directory positional means an unknown first word is a path, so the one spelling
        // an older instruction would use has to be refused by name. `install` was the verb
        // before the bootstrap moved out of stage 0, and silently setting up a directory
        // called "install" is the failure this catches.
        if (!rest.isEmpty() && rest.get(0).equals("install")) {
            System.err.println("flixw-setup: `install` was renamed; the bootstrap is"
                             + "\n       java flixw-setup.java [dir] [--pin <version>]");
            throw new Exit(87);
        }
        boolean scripted = !rest.isEmpty()
                           && (rest.get(0).equals("setup") || rest.get(0).equals("update"));
        String verb = scripted ? rest.remove(0) : "setup";
        boolean pinning = wanted != null || !scripted;
        if (rest.size() > 2 || (wanted != null && verb.equals("update"))) {
            System.err.println("usage: java flixw-setup.java [dir] [--pin <version>]"
                             + "\n       java flixw-setup.java setup [dir] [--pin <version>]"
                             + "\n       java flixw-setup.java update <dir>"
                             + "\n\n       with no --pin, the newest Flix release is pinned;"
                             + "\n       `setup` is the scripted form and pins only when asked");
            throw new Exit(87);
        }
        Path target = Paths.get(rest.isEmpty() ? "." : rest.get(0)).toAbsolutePath().normalize();
        try {
            switch (verb) {
                case "setup" -> {
                    // An explicit stage 0 is how `wrapper --upgrade` uses this: it has
                    // already downloaded and verified the release it is moving to, and
                    // fetching a second copy would be both wasteful and a chance to
                    // disagree with itself. Absent, this fetches its own.
                    Path fetched = null;
                    Path source = rest.size() == 2 ? Paths.get(rest.get(1))
                                                   : (fetched = fetchStage0(tempDir()));
                    try {
                        install(target, source, willPin(pinning, target, wanted));
                        pinAfterSetup(pinning, target, wanted);
                    } finally {
                        if (fetched != null) {
                            try { Files.deleteIfExists(fetched); } catch (IOException ignored) { }
                            try { Files.deleteIfExists(fetched.getParent()); }
                            catch (IOException ignored) { }
                        }
                    }
                }
                case "update" -> {
                    if (rest.size() != 1) throw w008("update needs exactly one directory");
                    updateWrapper(target);
                }
                default -> throw w008("unknown verb " + verb);
            }
        } catch (Fail f) {
            System.err.println(f.getMessage());
            throw new Exit(f.exit);
        }
    }

    /** Somewhere to land the download; the project directory is not ours to litter. */
    static Path tempDir() {
        try {
            return Files.createTempDirectory("flixw-setup-");
        } catch (IOException e) {
            throw w007("cannot create a temporary directory: " + why(e));
        }
    }

    static final String SHIM = """
        #!/bin/sh
        # flixw shim -- GENERATED; DO NOT EDIT.  `flixw install` writes this file,
        # `flixw doctor --fix` restores it, and `flixw validate` compares it byte for byte,
        # so an edit here is first reported and then overwritten.  It is byte-identical
        # across every project on a given flixw release.  To change it, edit the SHIM text
        # block in the installer; the copy checked into this project's own repository is
        # only a mirror, and tests/lint.sh fails if the two disagree.
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
        src=$root/.flixw/flixw.java

        # The cache is resolved before the java search, because a JDK flixw installed
        # earlier lives in it and is the last thing worth trying.
        if [ -n "${FLIX_CACHE_HOME:-}" ]; then cache=$FLIX_CACHE_HOME
        else
          case $(uname -s) in
            Darwin) cache=$HOME/Library/Caches/flixw ;;
            *)      cache=${XDG_CACHE_HOME:-$HOME/.cache}/flixw ;;
          esac
        fi

        # `chosen` marks an explicitly named JDK. Those are obeyed exactly as given, right
        # down to failing: stage 0's contract is that an explicit setting fails loudly
        # rather than being quietly replaced by a JVM the caller did not ask for.
        chosen=yes
        if [ -n "${FLIX_JAVA_HOME:-}" ]; then java0=$FLIX_JAVA_HOME/bin/java
        elif [ -n "${JAVA_HOME:-}" ]; then java0=$JAVA_HOME/bin/java
        else java0=$(command -v java 2>/dev/null || true); chosen=no; fi

        # The JDK flixw installed, if there is one. Its path is read from a file rather than
        # guessed, because every vendor nests differently -- and the marker names something
        # this script will execute, so it may only name something inside the directory flixw
        # unpacks into. A prefix test alone does not say that: `$cache/jdks/../../bin/java`
        # passes one and is not inside anything. Containment is a guardrail rather than the
        # security boundary, which is who can write the cache at all -- `doctor` checks that
        # -- but a guardrail that a plain `..` walks through is not one.
        cached_jdk() {
          [ -r "$cache/jdks/default" ] || return 0
          cj=$(cat "$cache/jdks/default" 2>/dev/null || true)
          case $cj in
            *"/../"* | */.. ) return 0 ;;
            "$cache/jdks/"* ) ;;
            * ) return 0 ;;
          esac
          [ -x "$cj" ] || return 0
          printf '%s\\n' "$cj"
        }

        # The JDK stage 0 resolved for *this project* last time, which is the one that
        # satisfies its java pin. Starting on it is the whole point: otherwise the shim
        # starts whatever java is first on PATH and stage 0 has to spend a second process
        # correcting it, on every command. Machine-specific, so it is not committed --
        # .flixw/.gitignore keeps it out. It names something this script executes, and
        # that is not a new trust boundary: anyone able to write .flixw/local/ can edit
        # this file instead, which is easier and does more.
        if [ "$chosen" = no ] && [ -r "$root/.flixw/local/java" ]; then
          noted=$(cat "$root/.flixw/local/java" 2>/dev/null || true)
          # Shape first: stage 0 writes a normalized absolute path ending in bin/java, so
          # anything else is not a note this wrapper left. It is a cheap sanity check
          # rather than a security boundary -- whoever can write here can edit this file
          # -- but a note is not the place to discover you are running something else.
          case $noted in
            *"/../"* | */.. ) noted= ;;
            /*/bin/java ) ;;
            * ) noted= ;;
          esac
          if [ -n "$noted" ] && [ -x "$noted" ]; then java0=$noted; fi
        fi

        # Nothing on PATH: fall back to that JDK.
        [ -n "$java0" ] || java0=$(cached_jdk)

        if [ -z "$java0" ]; then
          echo "FLIXW003: no java executable found. Flix needs Java 21+." >&2
          echo "          Install a JDK -- Eclipse Temurin is the usual choice:" >&2
          case $(uname -s) in
            Darwin) echo "            brew install temurin@21" >&2 ;;
            *)      echo "            apt install temurin-21-jdk    (or your package manager)" >&2 ;;
          esac
          echo "            https://adoptium.net/temurin/releases/?version=21" >&2
          echo "          Then set JAVA_HOME, or put its bin directory on PATH." >&2
          echo "          flixw cannot fetch this first one: it is a Java program itself," >&2
          echo "          and there is no Java here to run it. Once any Java 16 or newer is" >&2
          echo "          reachable, ./flixw wrapper --install-jdk fetches a verified" >&2
          echo "          Temurin 21 into the flixw cache and leaves the system alone." >&2
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

        # Feature version of the selected java, read from the release file of the JDK it
        # lives in -- the same source stage 0 prefers, and it costs one file read.  The
        # shim does not decide anything with this beyond whether the compiled class is
        # loadable; below-floor Java stays stage 0's diagnostic to give.  A java that does
        # not resolve into a JDK layout leaves this unknown, and unknown changes nothing.
        jhome=$java0
        while [ -L "$jhome" ]; do
          link=$(readlink "$jhome")
          case $link in /*) jhome=$link ;; *) jhome=$(dirname "$jhome")/$link ;; esac
        done
        jhome=${jhome%/bin/java}
        jfeature=
        if [ -r "$jhome/release" ]; then
          jfeature=$(sed -n 's/^JAVA_VERSION="\\([0-9][0-9]*\\).*/\\1/p' "$jhome/release" 2>/dev/null)
        fi

        # A java below the floor is worse than none: below 15 it cannot even compile stage
        # 0, so nothing flixw knows -- its own installed JDK included -- is ever reached.
        # When one is recorded, prefer it and let stage 0 speak.
        # A version manager's `java` is a shim script with no JDK layout around it, so there
        # is no release file and the feature version stays unknown. Ordinarily that is fine --
        # stage 0 asks the JVM itself -- but below 15 the JVM cannot compile stage 0, so the
        # question is never reached and the user gets a javac error instead of FLIXW003, and
        # instead of the JDK flixw installed for precisely this case. Ask the JVM once, and
        # only when there is something better to switch to, so the cost falls on the machines
        # that need it rather than on every run.
        if [ "$chosen" = no ] && [ -z "$jfeature" ] && [ -n "$(cached_jdk)" ]; then
          jfeature=$("$java0" -version 2>&1 \\
                     | sed -n 's/^[A-Za-z ]*version "\\([0-9][0-9]*\\).*/\\1/p' | head -1)
        fi

        if [ "$chosen" = no ] && [ -n "$jfeature" ] && [ "$jfeature" -lt 21 ]; then
          mine=$(cached_jdk)
          if [ -n "$mine" ]; then
            java0=$mine
            jhome=${mine%/bin/java}
            jfeature=
            if [ -r "$jhome/release" ]; then
              jfeature=$(sed -n 's/^JAVA_VERSION="\\([0-9][0-9]*\\).*/\\1/p' "$jhome/release" 2>/dev/null)
            fi
          fi
        fi

        # Content-keyed compiled stage 0.  Versioned interface with stage 0; see README.
        h=
        if command -v shasum >/dev/null 2>&1; then h=$(shasum -a 256 "$src" 2>/dev/null | cut -d' ' -f1)
        elif command -v sha256sum >/dev/null 2>&1; then h=$(sha256sum "$src" 2>/dev/null | cut -d' ' -f1)
        elif command -v openssl >/dev/null 2>&1; then h=$(openssl dgst -sha256 -r "$src" 2>/dev/null | cut -d' ' -f1)
        fi
        # The class is built for the floor, and the version has to be *known* to be at or
        # above it.  Unknown used to be treated as fine, which is wrong in the one case it
        # matters: asdf, mise and jenv install `java` as a shim script rather than a
        # symlink into a JDK, so there is no release file to read, and a shim pointing at
        # Java 17 loaded the class and died on class file version.  The cost of being
        # careful is that such setups always take the source path.
        if [ -n "$h" ] && [ -f "$cache/stage0/$h/flixw.class" ] \\
           && [ -n "$jfeature" ] && [ "$jfeature" -ge 21 ]; then
          FLIXW_SOURCE=$src; export FLIXW_SOURCE
          exec "$java0" -cp "$cache/stage0/$h" flixw "$@"
        fi
        exec "$java0" "$src" "$@"
        """;

    static final String CMD = """
        @echo off
        rem flixw cmd.exe trampoline -- GENERATED; DO NOT EDIT.  `flixw install` writes it,
        rem `flixw doctor --fix` restores it, and `flixw validate` compares it byte for
        rem byte.  To change it, edit the CMD text block in the installer; the copy checked
        rem into this project's own repository is only a mirror, and tests/lint.sh fails if
        rem the two disagree.  Finds an initial java, prefers the compiled stage 0 in the user
        rem cache, else launches the source.
        setlocal enabledelayedexpansion
        set "ROOT=%~dp0"
        set "SRC=%ROOT%.flixw\\flixw.java"

        rem The cache is resolved first: a JDK flixw installed earlier lives in it, and is
        rem the last thing worth trying when nothing else answers.
        if defined FLIX_CACHE_HOME ( set "CACHE=%FLIX_CACHE_HOME%" ) else (
          set "CACHE=%LOCALAPPDATA%\\flixw" )

        rem CHOSEN marks an explicitly named JDK: those are obeyed as given, failing
        rem included, rather than replaced by one the caller did not ask for.
        set "CHOSEN=1"
        if defined FLIX_JAVA_HOME ( set "JAVA0=%FLIX_JAVA_HOME%\\bin\\java.exe" ) else (
        if defined JAVA_HOME ( set "JAVA0=%JAVA_HOME%\\bin\\java.exe" ) else (
        set "CHOSEN="
        for %%I in (java.exe) do set "JAVA0=%%~$PATH:I" ) )
        rem Its path is read from a file rather than guessed: vendors nest differently.
        rem It names something this script will execute, so it may only name something
        rem inside the directory flixw unpacks into.
        rem The marker is cache-controlled text naming something this script will execute,
        rem so it is never echoed, called, or otherwise handed back to the parser: cmd
        rem metacharacters in it would run before anything could validate the path. The
        rem containment test uses delayed expansion alone -- strip the expected prefix,
        rem then require the original to be exactly prefix plus remainder, which is a
        rem starts-with test that never re-parses the value.
        rem The JDK stage 0 resolved for this project last time -- the one that satisfies
        rem its java pin. Starting on it avoids the relaunch stage 0 would otherwise need.
        rem Machine-specific and git-ignored; writable only by someone who could edit this
        rem file anyway, so it adds no trust boundary.
        set "NOTED="
        if not defined CHOSEN if exist "%ROOT%.flixw\\local\\java" (
          for /f "usebackq delims=" %%J in ("%ROOT%.flixw\\local\\java") do (
            if not defined NOTED set "NOTED=%%J" ) )
        rem Shape first, and by substring arithmetic rather than by echoing the value:
        rem stage 0 writes a normalized path ending in bin\\java.exe, so anything else is
        rem not a note this wrapper left.
        if defined NOTED (
          set "TAIL=!NOTED:bin\\java.exe=!"
          if "!TAIL!"=="!NOTED!" set "NOTED="
        )
        if defined NOTED if not "!NOTED!"=="!TAIL!bin\\java.exe" set "NOTED="
        if defined NOTED if not "!NOTED!"=="!NOTED:..=!" set "NOTED="
        if defined NOTED if not exist "!NOTED!" set "NOTED="
        if defined NOTED set "JAVA0=!NOTED!"

        set "MINE="
        if exist "%CACHE%\\jdks\\default" (
          for /f "usebackq delims=" %%J in ("%CACHE%\\jdks\\default") do (
            if not defined MINE set "MINE=%%J" ) )
        if defined MINE (
          set "TAIL=!MINE:%CACHE%\\jdks\\=!"
          if not "!MINE!"=="%CACHE%\\jdks\\!TAIL!" set "MINE="
        )
        rem A starts-with test does not say "inside": %CACHE%\\jdks\\..\\..\\evil.exe passes one.
        rem Any .. at all is refused rather than resolved, since resolving it here would mean
        rem handing cache-controlled text back to the parser.
        if defined MINE if not "!MINE!"=="!MINE:..=!" set "MINE="
        if defined MINE if not exist "!MINE!" set "MINE="
        if not defined JAVA0 if defined MINE set "JAVA0=!MINE!"
        if not defined JAVA0 (
          echo FLIXW003: no java executable found. Flix needs Java 21+. 1>&2
          echo           Install a JDK -- Eclipse Temurin is the usual choice: 1>&2
          echo             winget install EclipseAdoptium.Temurin.21.JDK 1>&2
          echo             https://adoptium.net/temurin/releases/?version=21 1>&2
          echo           Then set JAVA_HOME, or put its bin directory on PATH. 1>&2
          echo           flixw cannot fetch this first one: it is a Java program 1>&2
          echo           itself, and there is no Java here to run it. Once any Java 16 1>&2
          echo           or newer is reachable, flixw.cmd wrapper --install-jdk fetches 1>&2
          echo           a verified Temurin 21 into the flixw cache. 1>&2
          exit /b 127 )
        if not exist "%JAVA0%" (
          echo FLIXW003: %JAVA0% not found. 1>&2
          exit /b 127 )
        if not exist "%SRC%" (
          echo FLIXW009: missing %SRC% 1>&2
          exit /b 88 )

        rem Feature version of the selected java, from the release file of its own JDK.
        rem Only used to decide whether the compiled class is loadable: a JVM below the
        rem floor cannot load it and exec leaves no way back.  Unknown changes nothing.
        set "JHOME=%JAVA0:\\bin\\java.exe=%"
        set "JFEATURE="
        if exist "%JHOME%\\release" (
          for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "%JHOME%\\release" 2^>nul') do (
            for /f "tokens=1 delims=.-" %%w in ("%%~v") do set "JFEATURE=%%~w" ) )
        rem Unknown is not good enough: a java that is a shim script rather than a JDK
        rem layout has no release file, and running the class blind fails on class file
        rem version with no way back.  Default to the source path; earn the fast one.
        rem A version manager's java.exe is a shim with no JDK layout around it, so there is
        rem no release file and the version stays unknown. Below 15 that java cannot compile
        rem stage 0 either, so the user would see a javac error rather than FLIXW003 or the
        rem JDK flixw installed for this case. Ask the JVM once, and only when there is a
        rem recorded JDK to switch to, so ordinary runs pay nothing.
        if not defined CHOSEN if not defined JFEATURE if defined MINE (
          for /f "tokens=3" %%v in ('cmd /c ""%JAVA0%" -version" 2^>^&1') do (
            if not defined JFEATURE (
              for /f "tokens=1 delims=.-_" %%w in ("%%~v") do set "JFEATURE=%%~w" ) ) )
        rem A java below the floor is worse than none: it cannot load the compiled class
        rem and, far enough below, cannot compile stage 0 either. Prefer a recorded JDK --
        rem but never over an explicitly named one, which must fail loudly instead.
        if not defined CHOSEN if defined JFEATURE if !JFEATURE! LSS 21 if defined MINE (
          set "JAVA0=!MINE!"
          set "JFEATURE="
          for %%H in ("!MINE!") do set "JHOME=%%~dpH"
          if exist "!JHOME!..\\release" (
            for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "!JHOME!..\\release" 2^>nul') do (
              for /f "tokens=1 delims=.-" %%w in ("%%~v") do set "JFEATURE=%%~w" ) ) )
        set "SLOWPATH=1"
        if defined JFEATURE if !JFEATURE! GEQ 21 set "SLOWPATH="

        set "H="
        for /f "skip=1 delims=" %%L in ('certutil -hashfile "%SRC%" SHA256 2^>nul') do (
          if not defined H set "H=%%L" )
        if defined H set "H=!H: =!"
        rem Everything that needed delayed expansion is now in ordinary variables, so it
        rem is switched off before the launch. With it on, `%*` is rescanned for !...!
        rem *after* substitution, and an argument containing an exclamation mark loses
        rem part of itself before java is even started: `flixw run "a!b"` arrives as `ab`.
        rem The two commands are also kept out of parentheses, because a `)` inside a
        rem quoted argument can close a block that a `%*` sits in.
        set "CP=!CACHE!\\stage0\\!H!"
        set "FAST="
        if not defined SLOWPATH if defined H if exist "!CP!\\flixw.class" set "FAST=1"
        if defined FAST set "FLIXW_SOURCE=%SRC%"
        setlocal disabledelayedexpansion
        if defined FAST goto :flixw_fast
        "%JAVA0%" "%SRC%" %*
        exit /b %ERRORLEVEL%
        :flixw_fast
        "%JAVA0%" -cp "%CP%" flixw %*
        exit /b %ERRORLEVEL%
        """;

    /**
     * A user-wide convenience entry point. It deliberately contains no bootstrap policy:
     * it merely finds a checked-in wrapper below the caller and lets that wrapper retain
     * ownership of project discovery, Java selection and the compiler lock.
     */
    static final String GLOBAL_SHIM = """
        #!/bin/sh
        # flixw project launcher -- GENERATED; DO NOT EDIT.
        # Written by flixw-setup.java into the global flixw cache's bin directory. It finds the
        # nearest checked-in flixw wrapper and delegates all policy to it.
        set -eu
        here=$(pwd -P)
        while :; do
          if [ -x "$here/flixw" ] && [ -f "$here/.flixw/flixw.java" ]; then
            exec "$here/flixw" "$@"
          fi
          parent=$(dirname -- "$here")
          [ "$parent" = "$here" ] && break
          here=$parent
        done
        # `setup` is the one word that cannot be delegated: it exists to create the project
        # there is none of. Answered from the setup program this launcher was installed
        # alongside, so `flixw setup newproj --pin 0.75.3` works in an empty directory --
        # which is the only place anyone would type it.
        if [ "${1-}" = setup ]; then
          shift
          cache=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
          asset=$(ls -1d "$cache"/wrapper/assets/*/flixw-setup.java 2>/dev/null | sort | tail -1)
          if [ -n "$asset" ]; then exec java "$asset" "$@"; fi
          echo "flixw: no setup program cached in $cache/wrapper/assets" >&2
          echo "       download one: https://github.com/wstein/flixw/releases/latest" >&2
          exit 87
        fi
        echo "flixw: no checked-in flixw wrapper found above $(pwd -P)" >&2
        echo "       to create one here:  flixw setup . [--pin <version>]" >&2
        exit 87
        """;

    /** Mirrors stage 0's cache location, so setup's global launcher lives with flixw. */
    static Path globalBin() {
        String override = System.getenv("FLIX_CACHE_HOME");
        if (override != null && !override.isBlank())
            return Paths.get(override).toAbsolutePath().normalize().resolve("bin");
        String home = System.getProperty("user.home");
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            return Paths.get(local != null && !local.isBlank() ? local : home, "flixw", "bin");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"))
            return Paths.get(home, "Library", "Caches", "flixw", "bin");
        String xdg = System.getenv("XDG_CACHE_HOME");
        return (xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(home, ".cache"))
            .resolve("flixw").resolve("bin");
    }

    /** Installs the policy-free global launcher in the machine-wide flixw cache. */
    static Path installGlobalShim() throws IOException {
        Path bin = globalBin();
        Files.createDirectories(bin);
        Path launcher = bin.resolve("flixw");
        Files.writeString(launcher, GLOBAL_SHIM, StandardCharsets.UTF_8);
        launcher.toFile().setExecutable(true, false);
        cacheSelf();
        return launcher;
    }

    /**
     * Leaves a copy of this program where the launcher can find it.
     *
     * <p>`flixw setup` has to run something, and the only thing that can set a project up is
     * this file -- which the user downloaded to wherever they happened to be standing and is
     * told to delete on the next line of the instructions. So it is copied into the same
     * version-keyed directory stage 0 caches companion assets in, beside the launcher that
     * will look for it.
     *
     * <p>The source path comes from the launcher protocol rather than from a guess. Absent
     * when this class was loaded from bytecode instead of source -- which is how stage 0
     * runs it for `doctor --fix`, and in that case the cache already has the asset.
     *
     * <p>Best-effort throughout: failing to leave a convenience copy is not a reason to fail
     * an install that has otherwise written every file it promised.
     */
    static void cacheSelf() {
        String src = System.getProperty("jdk.launcher.sourcefile");
        if (src == null || src.isBlank()) return;
        try {
            Path from = Paths.get(src);
            Path dir = globalBin().getParent().resolve("wrapper").resolve("assets")
                                  .resolve(WRAPPER_VERSION);
            Path to = dir.resolve("flixw-setup.java");
            if (Files.isRegularFile(to)) return;
            Files.createDirectories(dir);
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) { }
    }

    static void install(Path target, Path source) { install(target, source, false); }

    /** {@code pinning} suppresses the advice a pin is about to make wrong. */
    static void install(Path target, Path source, boolean pinning) {
        try {
            Path fw = target.resolve(WRAPPER_DIR);
            Files.createDirectories(fw);
            if (source == null || !Files.isRegularFile(source))
                throw w009("install needs the wrapper source; run it as: java flixw.java install <dir>");
            Files.copy(source, fw.resolve("flixw.java"), StandardCopyOption.REPLACE_EXISTING);
            Path shim = target.resolve("flixw");
            Files.writeString(shim, SHIM, StandardCharsets.UTF_8);
            shim.toFile().setExecutable(true, false);
            Files.writeString(target.resolve("flixw.cmd"), CMD.replace("\n", "\r\n"),
                              StandardCharsets.UTF_8);
            writeLocalIgnore(target);
            mergeGitattributes(target.resolve(".gitattributes"));
            Path global = installGlobalShim();
            System.out.println("installed ./flixw, ./flixw.cmd and " + WRAPPER_DIR
                             + "/flixw.java into " + target);
            System.out.println("installed global launcher " + global);
            // `install` is reached two ways, and they need different sentences. First
            // contact has nothing pinned and the next step is pinning; an upgrade arrives
            // here through `wrapper --upgrade` with a lock already in place, and telling
            // that reader to pin reads as though the upgrade lost their compiler.
            if (Files.isRegularFile(lockPath(target))) {
                System.out.println("the compiler pin is untouched; commit the wrapper files"
                                 + " that changed:");
                System.out.println("  git add flixw flixw.cmd " + WRAPPER_DIR);
            } else if (!pinning) {
                System.out.println("next: ./flixw pin <version>   then commit all five files");
            }
        } catch (IOException e) { throw w009("install failed: " + e.getMessage()); }
    }

    /**
     * Rewrites the invariant wrapper files from the running stage 0, leaving the project's
     * compiler lock untouched. This repairs the failures that actually happen: a shim that
     * lost its executable bit to an archive download, a hand-edited shim, a .gitattributes
     * block clobbered by a merge.
     *
     * It deliberately does not fetch a newer flixw. Self-update needs a published release
     * feed with its own digests, which does not exist; until it does, upgrading means
     * running `install` from the newer release, and saying so is better than pretending.
     */
    static void updateWrapper(Path root) {
        int changed = 0;
        try {
            Path shim = root.resolve("flixw");
            if (!Files.isRegularFile(shim)
                || !Files.readString(shim, StandardCharsets.UTF_8).equals(SHIM)) {
                Files.writeString(shim, SHIM, StandardCharsets.UTF_8);
                System.out.println("rewrote  ./flixw"); changed++;
            }
            if (!isWindows() && !Files.isExecutable(shim)) {
                shim.toFile().setExecutable(true, false);
                System.out.println("restored ./flixw executable bit"); changed++;
            }
            Path cmd = root.resolve("flixw.cmd");
            String cmdBytes = CMD.replace("\n", "\r\n");
            if (!Files.isRegularFile(cmd)
                || !Files.readString(cmd, StandardCharsets.UTF_8).equals(cmdBytes)) {
                Files.writeString(cmd, cmdBytes, StandardCharsets.UTF_8);
                System.out.println("rewrote  ./flixw.cmd"); changed++;
            }
            Path ign = root.resolve(WRAPPER_DIR).resolve(".gitignore");
            boolean hadIgnore = Files.isRegularFile(ign)
                && Files.readString(ign, StandardCharsets.UTF_8).equals(LOCAL_IGNORE);
            writeLocalIgnore(root);
            if (!hadIgnore) { System.out.println("wrote    " + WRAPPER_DIR + "/.gitignore"); changed++; }
            Path ga = root.resolve(".gitattributes");
            String before = Files.isRegularFile(ga) ? Files.readString(ga, StandardCharsets.UTF_8) : "";
            mergeGitattributes(ga);
            if (!before.equals(Files.readString(ga, StandardCharsets.UTF_8))) {
                System.out.println("merged   ./.gitattributes"); changed++;
            }
        } catch (IOException e) { throw w009("rewriting the wrapper files failed: " + why(e)); }
        // One line, and only the one that is true. The two-line note that used to follow
        // every run explained that this refreshes rather than upgrades -- a fact about
        // what the command is, not about what just happened.
        System.out.println(changed == 0
            ? "wrapper files already match flixw " + WRAPPER_VERSION
            : changed + (changed == 1 ? " file" : " files")
              + " rewritten from flixw " + WRAPPER_VERSION);
    }

    static void writeLocalIgnore(Path target) throws IOException {
        Path f = target.resolve(WRAPPER_DIR).resolve(".gitignore");
        if (Files.isRegularFile(f)
            && Files.readString(f, StandardCharsets.UTF_8).equals(LOCAL_IGNORE)) return;
        Files.createDirectories(f.getParent());
        writeAtomic(f, LOCAL_IGNORE);
    }

    /**
     * `.flixw/local/` holds what only this machine knows -- currently the resolved JDK --
     * and must not be committed. The ignore rule lives inside the directory flixw owns, so
     * adopting the wrapper does not edit a file the project maintains.
     */
    static final String LOCAL_IGNORE =
        "# Generated by flixw. Do not edit by hand; `flixw doctor --fix` rewrites it.\n"
      + "# It keeps .flixw/local/ -- machine-specific notes -- out of git.\n"
      + "local/\n";

    static void mergeGitattributes(Path ga) throws IOException {
        String begin = "# >>> flixw >>>", end = "# <<< flixw <<<";
        String block = begin + "\n/flixw text eol=lf\n"
                     + "/" + WRAPPER_DIR + "/flixw.java text eol=lf\n"
                     + "/" + WRAPPER_DIR + "/lock.toml text eol=lf\n"
                     // Compared byte for byte by `doctor --fix`, so a checkout that
                     // translated its endings would make every run report a file to
                     // repair and repair it back. Same reason as the four above.
                     + "/" + WRAPPER_DIR + "/.gitignore text eol=lf\n"
                     + "/flixw.cmd text eol=crlf\n" + end + "\n";
        String cur = Files.isRegularFile(ga) ? Files.readString(ga, StandardCharsets.UTF_8) : "";
        // Every existing block is removed and one is appended, rather than each being
        // rewritten where it sits: two blocks rewritten in place stay two blocks, and the
        // last one is the one git honours.
        String stripped = cur.replaceAll("(?s)" + Pattern.quote(begin) + ".*?"
                                       + Pattern.quote(end) + "\\R?", "");
        String next = (stripped.isEmpty() || stripped.endsWith("\n") ? stripped : stripped + "\n")
                    + block;
        Files.writeString(ga, next, StandardCharsets.UTF_8);
    }
}
