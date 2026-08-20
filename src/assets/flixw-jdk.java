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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * flixw's optional JDK provisioner.  Fetched once per machine per flixw release by
 * {@code ./flixw wrapper --install-jdk}, verified against the release's own
 * {@code SHA256SUMS}, cached, and source-launched from there on:
 * {@code java flixw-jdk.java <feature> <cache-dir>}.
 *
 * <p>It is here rather than in stage 0 because it is ~240 lines of third-party metadata
 * parsing, archive handling and per-platform policy that runs on the rarest path there
 * is -- a machine with no suitable Java -- while stage 0 is loaded on every single
 * invocation.  Nothing in the verified chain (install, pin, lock, digest, launch) needs
 * it, so it does not get to be resident in the file that chain lives in.
 *
 * <p><b>This file must compile and run at {@code SOURCE_FLOOR}, not {@code MIN_JAVA}</b>,
 * and that is the whole reason it is a separate constraint from the completion asset's.
 * Stage 0 source-launches a companion asset with the JVM it is itself running on, and
 * this asset exists precisely for the case where that JVM is *below* the floor flixw
 * needs.  A Java 21 language feature in here would make the provisioner unrunnable in
 * the one situation it is for.  {@code tests/lint.sh} compiles it at
 * {@code --release SOURCE_FLOOR} for that reason.
 *
 * <p>Diagnostics use flixw's own {@code FLIXWnnn} codes and advisory exit statuses so a
 * caller cannot tell from the outside that the work moved out of stage 0.
 */
final class flixwjdk {
    private flixwjdk() {}

    /** Adoptium's metadata endpoint, and the only host a download may come from. */
    static final String ADOPTIUM_API = "https://api.adoptium.net/v3/assets/latest/";
    static final String ADOPTIUM_RELEASES = "https://github.com/adoptium/";

    /** A JSON response supplies both a URL and the digest it is checked against. */
    static final int METADATA_CAP = 1 << 21;

    static final String VERSION = "1";

    record JdkPackage(String name, String url, String sha256) {}

    /**
     * The cache root is an argument rather than a computation: {@code FLIX_CACHE_HOME} and
     * the three per-platform defaults are stage 0's to resolve, and a second copy of that
     * rule here could disagree with the one the shims and {@code doctor} already read.
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
        if (args.length != 2) {
            System.err.println("usage: java flixw-jdk.java <feature> <cache-dir>");
            throw new Exit(87);
        }
        int feature;
        try {
            feature = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("FLIXW008: not a Java feature release: " + args[0]);
            throw new Exit(87);
        }
        Path cache = Paths.get(args[1]).toAbsolutePath().normalize();
        try {
            Path exe = installJdk(cache, resolveTemurin(feature));
            // stdout is the answer and nothing else, so a caller can read the path
            // directly; every word of narration went to stderr on the way here.
            System.out.println(exe);
        } catch (Fail f) {
            System.err.println(f.getMessage());
            throw new Exit(f.exit);
        }
    }

    // ---- diagnostics ------------------------------------------------------

    /** Mirrors stage 0's codes and advisory exits so the split is invisible to a caller. */
    static final class Fail extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int exit;
        Fail(String code, int exit, String msg) { super(code + ": " + msg); this.exit = exit; }
    }

    static Fail w003(String m) { return new Fail("FLIXW003", 82, m); }
    static Fail w005(String m) { return new Fail("FLIXW005", 84, m); }
    static Fail w006(String m) { return new Fail("FLIXW006", 85, m); }
    static Fail w007(String m) { return new Fail("FLIXW007", 86, m); }

    /** Query strings and userinfo can carry a token; a diagnostic is not a place for one. */
    static String redact(String v) {
        if (v == null) return null;
        return v.replaceAll("://[^/@]*@", "://***@").replaceAll("\\?.*$", "?***");
    }

    static String q(String s) { return "'" + s + "'"; }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    // ---- Adoptium metadata ------------------------------------------------

    /** aarch64 or x64 as Adoptium spells it, or null where it publishes nothing for us. */
    static String jdkArch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (a.equals("aarch64") || a.equals("arm64")) return "aarch64";
        if (a.equals("x86_64") || a.equals("amd64")) return "x64";
        return null;
    }

    /** Windows gets a zip; nobody publishes a tar.gz for it. */
    static String jdkArchiveType() { return isWindows() ? "zip" : "tar.gz"; }

    /** Enough JSON for flat string fields of one small, known response. */
    static String jsonField(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                           .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The text of one nested object, by brace counting rather than by regex.
     *
     * A regex cannot do this: {@code "package": { ... }} may contain further braces, and
     * the lazy match that looks right stops at the first inner one.  Counting is the
     * shortest thing that is actually correct on the response this parses.
     */
    static String jsonObject(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{").matcher(json);
        if (!m.find()) return null;
        int depth = 0;
        for (int i = m.end() - 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(m.end() - 1, i + 1);
        }
        return null;
    }

    /**
     * Asks Adoptium for one build and checks every field of the answer before using it.
     *
     * All three values come out of a third party's JSON.  None is used as a URL, a
     * filename or a digest until it has been checked to be one -- the download that
     * follows is the one place flixw fetches something no lock named in advance.
     */
    static JdkPackage resolveTemurin(int feature) {
        String arch = jdkArch();
        if (arch == null)
            throw w003("no Temurin build is published for " + System.getProperty("os.name")
                     + " " + System.getProperty("os.arch") + "; install a JDK by hand");
        String os = isWindows() ? "windows" : isMac() ? "mac" : "linux";
        String body = httpGet(ADOPTIUM_API + feature + "/hotspot?architecture=" + arch
                            + "&image_type=jdk&os=" + os + "&vendor=eclipse");
        String pkg = jsonObject(body, "package");
        if (pkg == null)
            throw w005("Adoptium published no JDK " + feature + " for " + os + "/" + arch);
        String name = jsonField(pkg, "name");
        String url = jsonField(pkg, "link");
        String sha = jsonField(pkg, "checksum");
        if (name == null || url == null || sha == null)
            throw w005("Adoptium metadata was missing name, link or checksum for "
                     + os + "/" + arch);
        if (!url.startsWith(ADOPTIUM_RELEASES))
            throw w005("refusing a download outside " + ADOPTIUM_RELEASES + ": " + redact(url));
        // A prefix test still admits text that is not a URI, and URI.create would then
        // throw out of HttpRequest.newBuilder with no FLIXW code attached.
        try {
            URI u = URI.create(url);
            if (u.getHost() == null || u.getHost().isBlank() || u.getPath() == null
                || u.getPath().isBlank() || u.getPath().contains(".."))
                throw w005("refusing a malformed download url: " + redact(url));
        } catch (IllegalArgumentException e) {
            throw w005("Adoptium metadata carried an unparseable url: " + redact(url));
        }
        if (!sha.matches("[0-9a-f]{64}"))
            throw w005("Adoptium metadata carried no usable checksum for " + name);
        if (!name.matches("[A-Za-z0-9._+-]{1,120}"))
            throw w005("refusing an unexpected package name: " + q(name));
        return new JdkPackage(name, url, sha);
    }

    // ---- network ----------------------------------------------------------

    /**
     * Redirects are followed, but only ever onto https -- and the scheme of the *final*
     * URI is what is checked, because that is the one the bytes actually came from.
     */
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** One bounded HTTPS GET returning text.  Metadata only; bytes go through download(). */
    static String httpGet(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "flixw-jdk/" + VERSION).build();
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
                .header("User-Agent", "flixw-jdk/" + VERSION).build();
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

    // ---- install ----------------------------------------------------------

    /**
     * Downloads, verifies and unpacks one JDK into the wrapper cache, and returns its
     * {@code java}.  The directory is named for the archive, which carries the exact
     * build, so a second project on the same machine reuses it and a re-run is a no-op.
     */
    static Path installJdk(Path cache, JdkPackage p) {
        Path dir = cache.resolve("jdks");
        Path dest = dir.resolve(p.name().replaceAll("\\.(tar\\.gz|zip)$", ""));
        // Containing a bin/java is not evidence of anything: any directory can. The note
        // flixw writes after a verified unpack is, so a tree without one -- or with one
        // recording a different archive -- is replaced rather than trusted.
        Path origin = dest.resolve(".flixw-origin");
        boolean vouched = false;
        try {
            vouched = Files.isDirectory(dest) && Files.isRegularFile(origin)
                   && Files.readString(origin, StandardCharsets.UTF_8).strip().equals(p.sha256());
        } catch (IOException ignored) { }
        if (Files.isDirectory(dest) && !vouched) deleteTree(dest);
        if (!Files.isDirectory(dest)) {
            Path tmp = null, staging = null;
            try {
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, ".jdk-", ".part");
                System.err.println("flixw: downloading " + p.name());
                System.err.println("       from " + p.url());
                download(p.url(), tmp);
                String got = sha256(tmp);
                if (!got.equals(p.sha256()))
                    throw w006("digest mismatch for " + p.name()
                             + "\n       expected " + p.sha256()
                             + "\n       actual   " + got);
                staging = Files.createTempDirectory(dir, ".unpack-");
                String log = unpack(tmp, staging);
                // Unpacking is judged by its result rather than an exit status: the only
                // thing that matters is whether a runnable java came out of it.
                if (findJavaUnder(staging) == null)
                    throw w007("no bin/java after unpacking " + p.name()
                             + (log.isBlank() ? "" : "\n       " + log.strip()));
                boolean moved = false;
                try {
                    Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
                    staging = null;
                    moved = true;
                } catch (IOException e) {
                    // Another process may have finished the same install first. That is a
                    // win, not a collision: content is addressed by the archive name, so
                    // what is there is what we were about to put there.
                    if (findJavaUnder(dest) == null) throw e;
                }
                // Only the process that unpacked the tree may vouch for it. The loser of
                // that race verified an archive it then threw away, so signing a tree it
                // never wrote would turn the note from "flixw unpacked a verified archive
                // here" into "some flixw once verified an archive of this name" -- and if
                // the winner dies before writing its own note, the next run replacing an
                // unvouched tree is the outcome worth having.
                if (moved) {
                    try { Files.writeString(origin, p.sha256() + System.lineSeparator()); }
                    catch (IOException ignored) { }   // a read-only cache is still usable
                }
            } catch (IOException e) {
                throw w007("cannot install a JDK into " + dir + ": " + e);
            } finally {
                if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { } }
                if (staging != null) deleteTree(staging);
            }
        }
        Path exe = findJavaUnder(dest);
        if (exe == null) throw w003("no bin/java inside " + dest);
        // One line naming the java, so a shim can use it without knowing that Temurin
        // nests differently on every platform -- and so that a machine with no system
        // java at all still has a route back to this one. Part of the cache contract,
        // and the one thing stage 0 reads back out of this program's work.
        try {
            Files.writeString(dir.resolve("default"), exe + System.lineSeparator(),
                              StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A read-only cache is a correct configuration; the JDK still works here.
        }
        return exe;
    }

    /** Returns whatever the unpacker said, for a diagnostic; success is judged separately. */
    static String unpack(Path archive, Path dest) throws IOException {
        if (isWindows()) { unzip(archive, dest); return ""; }
        // System tar on POSIX: it already handles modes, symlinks and hostile member
        // names, all of which a hand-written reader would have to get right to be safe.
        String out = runCapture(List.of("tar", "-xzf", archive.toString(),
                                        "-C", dest.toString()),
                                Duration.ofMinutes(10), 1 << 16);
        return out == null ? "tar did not finish within 10 minutes" : out;
    }

    static void unzip(Path archive, Path dest) throws IOException {
        try (java.util.zip.ZipInputStream zin =
                new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            for (java.util.zip.ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                // A zip entry names its own destination, so it can name one outside the
                // directory being unpacked into.  Refuse rather than write there.
                Path target = dest.resolve(e.getName()).normalize();
                if (!target.startsWith(dest))
                    throw new IOException("refusing zip entry outside the target: " + e.getName());
                if (e.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Layout differs per platform -- macOS nests a .jdk bundle -- so look rather than guess.
     *
     * <p>The executable bit is only required where it means something.  Adoptium builds its
     * Windows zip on a Unix machine, so entries carry a mode of 0770, and java.util.zip
     * discards it: every file lands 0644.  On Windows that is irrelevant, because what
     * makes java.exe runnable there is the extension and the ACL -- but a check for it
     * would rest on platform semantics rather than on anything unpacking guarantees.  On
     * POSIX the bit does mean something and tar preserves it, so it is still required.
     *
     * <p>Stage 0 keeps its own copy of this: it needs it to *discover* a JDK this program
     * installed earlier, on every run, without fetching this asset at all.
     */
    static Path findJavaUnder(Path root) {
        String want = isWindows() ? "java.exe" : "java";
        try (java.util.stream.Stream<Path> s = Files.walk(root, 6)) {
            return s.filter(x -> x.getFileName().toString().equals(want)
                              && x.getParent() != null
                              && x.getParent().getFileName().toString().equals("bin")
                              && Files.isRegularFile(x)
                              && (isWindows() || Files.isExecutable(x)))
                    .findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) return;
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /**
     * Runs a child and returns its merged output, bounded in both bytes and wall clock.
     * Returns null when the child did not finish in time.
     *
     * <p>The obvious shape -- a read loop with a deadline test in its condition -- bounds
     * nothing: the test runs *between* reads, and read() on a pipe blocks until the writer
     * produces a byte or closes it.  So the read runs on a daemon thread and the timeout is
     * enforced on the process, which is the only handle that can actually be revoked.
     */
    static String runCapture(List<String> cmd, Duration timeout, int cap) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int total = 0, n;
                while (total < cap && (n = in.read(buf)) > 0) {
                    sink.write(buf, 0, Math.min(n, cap - total));
                    total += n;
                }
            } catch (IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return null;
        }
        return sink.toString(StandardCharsets.UTF_8);
    }
}
