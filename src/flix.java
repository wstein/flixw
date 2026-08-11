// flixw stage 0 -- repository-local Flix compiler bootstrap.
//
// Invoked by the ./flix shim as:   java .flix-wrapper/flix.java <args>
// or, once self-compiled, as:      java -cp <cache>/stage0/<hash> flix <args>
//
// One file, no dependencies, Java 21.  Owns: project discovery, lock parsing, drift
// detection, version validation, Java selection, compiler acquisition, unconditional
// digest verification, compiler-first verb dispatch, wrapper verbs, and process launch.
//
// The stock Flix compiler is never modified, patched, or linked against.  It is fetched
// by URL, verified against a committed SHA-256, and executed as an opaque process.
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class flix {

    static final String WRAPPER_VERSION = "0.15.0";
    static final String WRAPPER_DIR = ".flix-wrapper";
    static final int MIN_JAVA = 21;

    /** The interval flixw is tested on.  Above the ceiling is a warning, not an error. */
    static final int TESTED_CEILING = 25;

    /**
     * Bounds for the two child processes stage 0 runs for information rather than for
     * work.  Both are generous: exceeding one means the child is wedged, not slow.
     */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    static final Duration HELP_TIMEOUT = Duration.ofSeconds(30);
    static final int HELP_CAP = 1 << 20;

    static final List<String> WRAPPER_VERBS =
        List.of("pin", "doctor", "setup", "validate", "help");

    /**
     * Fallback verb set, observed in Flix 0.75.1 and 0.75.2.  Used when `flix --help`
     * cannot be captured or parsed.  Its only job is to answer "does the pinned compiler
     * already implement one of WRAPPER_VERBS" -- a question whose answer changes at most
     * once a year, and never silently.  Being one release stale here costs nothing;
     * failing here would brick every project pinned to a compiler flixw has not seen.
     */
    static final List<String> BUILTIN_VERBS = List.of(
        "init", "check", "build", "build-jar", "build-fatjar", "build-pkg", "clean",
        "doc", "format", "run", "test", "repl", "lsp", "lsp-vscode", "release",
        "outdated", "eff-check", "eff-lock");

    // ---- diagnostics -----------------------------------------------------

    static final class Fail extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code; final int exit;
        Fail(String code, int exit, String msg) { super(msg); this.code = code; this.exit = exit; }
    }
    static Fail fail(String code, int exit, String msg) { return new Fail(code, exit, msg); }
    static Fail w001(String m) { return fail("FLIXW001", 80, m); }
    static Fail w002(String m) { return fail("FLIXW002", 81, m); }
    static Fail w003(String m) { return fail("FLIXW003", 82, m); }
    static Fail w004(String m) { return fail("FLIXW004", 83, m); }
    static Fail w005(String m) { return fail("FLIXW005", 84, m); }
    static Fail w006(String m) { return fail("FLIXW006", 85, m); }
    static Fail w007(String m) { return fail("FLIXW007", 86, m); }
    static Fail w008(String m) { return fail("FLIXW008", 87, m); }
    static Fail w009(String m) { return fail("FLIXW009", 88, m); }

    /** FLIXW010 and FLIXW011 are advisory: they are printed, they never set exit status. */
    static void w010(String m) { System.err.println("FLIXW010: " + m); }
    static void w011(String m) { System.err.println("FLIXW011: " + m); }

    static String env(String k) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? null : v;
    }
    static boolean trace() { return env("FLIXW_TRACE") != null; }
    static long T0 = System.nanoTime();
    static void tr(String s) {
        if (trace()) System.err.printf("flixw[%6.1fms] %s%n", (System.nanoTime() - T0) / 1e6, s);
    }

    // ---- version grammar --------------------------------------------------

    static final Pattern SEMVERISH = Pattern.compile(
        "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z](?:[0-9A-Za-z.-]*[0-9A-Za-z])?)?"
      + "(?:\\+[0-9A-Za-z](?:[0-9A-Za-z.-]*[0-9A-Za-z])?)?");

    static String validateVersion(String v, String where) {
        if (v == null) throw w002(where + ": no version");
        for (char c : v.toCharArray())
            if (Character.isWhitespace(c) || c == '/' || c == '\\')
                throw w002(where + ": illegal character in version " + q(v));
        if (v.contains("..")) throw w002(where + ": '..' in version " + q(v));
        if (v.startsWith("v")) throw w002(where + ": strip the leading 'v' from " + q(v));
        if (!SEMVERISH.matcher(v).matches())
            throw w002(where + ": " + q(v) + " is not an exact version"
                     + "\n       ranges, wildcards and empty suffixes are not accepted");
        return v;
    }

    /**
     * The single normalization used for release tags, cache coordinates, and every
     * version comparison.  SemVer build metadata identifies a build, not a release, so
     * it is accepted in the manifest and stripped everywhere it would name an artifact.
     * Defining this once is what stops `flix = "0.75.2+build.4"` from producing a drift
     * error that `./flix pin` cannot repair.
     */
    static String canonical(String v) { int i = v.indexOf('+'); return i < 0 ? v : v.substring(0, i); }

    static String q(String s) { return "'" + s + "'"; }
    /** IOException.getMessage() is often bare the path; name the failure too. */
    static String why(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /**
     * Redacts credentials from a URL-shaped value before it is printed.
     *
     * `doctor` output exists to be pasted into bug reports, and a proxy URL is the one
     * environment value that routinely carries a password. Host and port are what a reader
     * needs; user-info and query string never are. Values that are not URLs at all -- a
     * NO_PROXY host list, say -- have no '@' and pass through untouched.
     */
    static String redact(String v) {
        String s = v.replaceAll("(?i)((?:[a-z][a-z0-9+.-]*://)?)[^/@\\s,]*@", "$1***@");
        int i = s.indexOf('?');
        return i < 0 ? s : s.substring(0, i) + "?***";
    }

    /** The same, for JVM option strings, which can carry -Dhttps.proxyPassword=secret. */
    static String redactOpts(String v) {
        return redact(v).replaceAll(
            "(?i)(-D[^=\\s]*(?:pass|secret|token|credential)[^=\\s]*=)\\S+", "$1***");
    }

    // ---- lock and manifest ------------------------------------------------

    record Lock(String version, String url, String sha256, String repo) {}

    /**
     * One `key = value` occurrence, the table it was found in, and the line it sits on.
     * `value` is the raw right-hand side; `multiline` marks a `"""` or `'''` opener, whose
     * body this scanner deliberately does not reassemble -- no key flixw reads is one.
     */
    record TomlEntry(int line, String table, String key, String value, boolean multiline,
                     int valueStart, int valueEnd) {}

    /** Every scalar entry in a document, plus every table header, in file order. */
    record TomlScan(List<TomlEntry> entries, List<String> tables) {}

    /**
     * The single TOML line scanner in stage 0.
     *
     * This is not a TOML parser and does not try to be one -- stage 0 has no dependencies
     * by design. It is deliberately table-aware, comment-aware and multi-line-string-aware,
     * because the alternative that a plain regex gives you is reading `flix = "..."` out of
     * some unrelated table, or out of the body of a description string.
     *
     * There is exactly one of these because there used to be two: `pin`'s rewrite carried a
     * second copy that had never learned about multi-line strings, so a `flix = "9.9.9"`
     * inside a `"""` description was correctly invisible to the lookup and yet rewritable
     * by pin. Any divergence here means the version flixw reads is not the one it writes,
     * so the two readers share a scanner rather than a convention.
     *
     * Lines are split on \n alone, never on \r?\n: `pin` rejoins with \n to rewrite a
     * single line in place, and a split that swallowed the \r would quietly convert a CRLF
     * manifest to LF. The trailing \r survives into the raw line and is removed by trim().
     */
    static TomlScan tomlScan(String text, String where) {
        List<TomlEntry> entries = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        String current = "";
        String mlDelim = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int base = 0;                                // offsets stay relative to lines[i]
            if (mlDelim != null) {                       // inside """ or ''': find the close
                int e = line.indexOf(mlDelim);
                if (e < 0) continue;
                base = e + 3;                            // both delimiters are three chars
                line = line.substring(base);
                mlDelim = null;
            }
            String t = stripComment(line).trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("[[")) {
                // Fail closed: only a well-formed array-of-tables header counts as
                // one, rather than anything that merely opens with two brackets.
                if (!t.endsWith("]]"))
                    throw w002(where + ": malformed array-of-tables header " + q(t));
                current = "\u0000array";
                continue;
            }
            if (t.startsWith("[")) {
                int close = t.indexOf(']');
                if (close < 0) throw w002(where + ": unterminated table header " + q(t));
                // Trailing text used to be dropped, so `[package] junk` read as
                // `[package]`. A header the scanner cannot account for entirely is
                // one it has no business guessing at.
                if (!t.substring(close + 1).isBlank())
                    throw w002(where + ": trailing text after table header " + q(t));
                current = unquote(t.substring(1, close).trim());
                tables.add(current);
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            String k = unquote(t.substring(0, eq).trim());
            String v = t.substring(eq + 1).trim();
            String delim = v.startsWith("\"\"\"") ? "\"\"\"" : v.startsWith("'''") ? "'''" : null;
            if (delim != null && !v.substring(3).contains(delim)) mlDelim = delim;
            // Where the value sits in the untouched line, so a rewrite can replace exactly
            // it. `t` is the line with comments stripped and both ends trimmed, so the
            // offset is the leading whitespace plus the position within `t`, plus whatever
            // a multi-line string closing earlier on this same line already consumed.
            String noComment = stripComment(line);
            int lead = noComment.length() - noComment.stripLeading().length();
            int vs = lead + eq + 1;
            while (vs < noComment.length() && Character.isWhitespace(noComment.charAt(vs))) vs++;
            entries.add(new TomlEntry(i, current, k, v, delim != null,
                                      base + vs, base + vs + v.length()));
        }
        return new TomlScan(entries, tables);
    }

    /** True when an entry is `table.key`, written either inside [table] or as a dotted key. */
    static boolean isKey(TomlEntry e, String table, String key) {
        return (e.table().equals(table) && e.key().equals(key))
            || (e.table().isEmpty() && e.key().equals(table + "." + key));
    }

    /**
     * Reads one key from one TOML table.  Anything it cannot classify inside the table it
     * was asked about is rejected rather than guessed at.  Duplicate tables and duplicate
     * keys are ambiguous, so they fail rather than resolve.
     *
     * Accepts the key inside [table] and as a dotted key at the root (`package.flix`).
     */
    static String tomlLookup(String text, String table, String key, String where) {
        TomlScan scan = tomlScan(text, where);
        String value = null;
        int hits = 0;
        for (TomlEntry e : scan.entries()) {
            if (!isKey(e, table, key)) continue;
            if (e.multiline()) throw w002(where + ": " + q(key) + " must be a single-line string");
            hits++;
            String v = e.value();
            if (v.length() < 2 || v.charAt(0) != v.charAt(v.length() - 1)
                || (v.charAt(0) != '"' && v.charAt(0) != '\''))
                throw w002(where + ": " + q(key) + " must be a quoted string, got " + q(v));
            value = v.substring(1, v.length() - 1);
        }
        int tables = 0;
        for (String t : scan.tables()) if (t.equals(table)) tables++;
        if (tables > 1) throw w002(where + ": duplicate [" + table + "] table");
        if (hits > 1) throw w002(where + ": duplicate " + q(key) + " key in [" + table + "]");
        return value;
    }

    /** Strips a trailing comment, ignoring '#' inside quotes. */
    static String stripComment(String line) {
        boolean s = false, d = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !d) s = !s;
            else if (c == '"' && !s) d = !d;
            else if (c == '#' && !s && !d) return line.substring(0, i);
        }
        return line;
    }

    static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
            && s.charAt(s.length() - 1) == s.charAt(0)) return s.substring(1, s.length() - 1);
        return s;
    }

    static Path lockPath(Path root) { return root.resolve(WRAPPER_DIR).resolve("lock.toml"); }

    static Lock readLock(Path lockFile) {
        String text;
        try { text = Files.readString(lockFile, StandardCharsets.UTF_8); }
        catch (IOException e) {
            throw w002("cannot read " + lockFile + ": " + why(e)
                     + "\n       run: ./flix pin <version>");
        }
        String w = lockFile.toString();
        String v = tomlLookup(text, "compiler", "version", w);
        String u = tomlLookup(text, "compiler", "url", w);
        String s = tomlLookup(text, "compiler", "sha256", w);
        if (v == null || u == null || s == null)
            throw w002(lockFile + " is missing [compiler] version, url or sha256");
        validateVersion(v, w);
        if (!s.matches("[0-9a-f]{64}")) throw w002(w + ": sha256 is not 64 lowercase hex digits");
        validateUrl(u, w);
        // Optional: locks written before forks were supported do not carry it, and a
        // missing repository simply means the stock one.
        String r = tomlLookup(text, "compiler", "repo", w);
        return new Lock(v, u, s, r == null ? null : checkRepo(r, w));
    }

    /**
     * The manifest is the human authority; disagreement stops us before the network. A
     * manifest that exists but cannot be read is an error, not an absent declaration --
     * swallowing it would silently disable drift detection and let the compiler run.
     */
    static String manifestVersion(Path manifest) {
        if (!Files.isRegularFile(manifest)) return null;
        String text;
        try { text = Files.readString(manifest, StandardCharsets.UTF_8); }
        catch (IOException e) { throw w002("cannot read " + manifest + ": " + why(e)); }
        String declared = tomlLookup(text, "package", "flix", manifest.toString());
        return declared == null ? null : validateVersion(declared, manifest.toString());
    }

    /**
     * Rewrites [package].flix in place, leaving every other table and all formatting alone.
     * Shares tomlScan with the readers, so the key it rewrites is by construction the key
     * manifestVersion reads -- including the multi-line-string body it must not rewrite.
     */
    static String rewritePackageFlix(String text, String version, String where) {
        TomlEntry target = null;
        for (TomlEntry e : tomlScan(text, where).entries()) {
            if (!isKey(e, "package", "flix")) continue;
            if (e.multiline()) throw w002(where + ": 'flix' must be a single-line string");
            if (target != null) throw w002(where + ": duplicate 'flix' key in [package]");
            target = e;
        }
        if (target == null) return null;
        // The same split tomlScan indexed, rejoined with the same separator: a manifest
        // with CRLF endings keeps them, and only the pinned line differs afterwards.
        String[] lines = text.split("\n", -1);
        String raw = lines[target.line()];
        // The span the scanner recorded, replaced whole. A regex over the line could not do
        // this safely: an escaped quote inside the value stopped [^"']* early and left
        // flix = "2.0.0"x", which is not TOML at all. Replacing the whole span also repairs
        // an unquoted or mis-quoted value, which is exactly what pin is for.
        lines[target.line()] = raw.substring(0, target.valueStart())
                             + '"' + version + '"'
                             + raw.substring(target.valueEnd());
        return String.join("\n", lines);
    }
    // ---- cache ------------------------------------------------------------

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    static Path cacheHome() {
        String o = env("FLIX_CACHE_HOME");
        if (o != null) return Paths.get(o).toAbsolutePath();
        String home = System.getProperty("user.home");
        if (isWindows()) {
            String local = env("LOCALAPPDATA");
            return Paths.get(local != null ? local : home).resolve("flixw");
        }
        if (isMac()) return Paths.get(home, "Library", "Caches", "flixw");
        String xdg = env("XDG_CACHE_HOME");
        return (xdg != null ? Paths.get(xdg) : Paths.get(home, ".cache")).resolve("flixw");
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return String.format("%064x", new BigInteger(1, md.digest()));
        } catch (Exception e) {
            throw w007("cannot hash " + file + ": " + e.getMessage());
        }
    }
    static String sha256(byte[] b) {
        try {
            return String.format("%064x",
                new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(b)));
        } catch (Exception e) { throw w007("cannot hash: " + e.getMessage()); }
    }

    /** Where the stock compiler comes from when nothing says otherwise. */
    static final String UPSTREAM_REPO = "flix/flix";

    /** One release asset: what to fetch, and what the publisher says it hashes to. */
    record Asset(String name, String url, String sha256) {}

    static String checkRepo(String repo, String where) {
        if (!repo.matches("[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,100}"))
            throw w002(where + ": " + q(repo) + " is not an owner/repository");
        return repo;
    }

    /** A release tag in a URL path. Only '+' needs it; the rest of a version is path-safe. */
    static String encodeTag(String tag) { return tag.replace("+", "%2B"); }

    /**
     * Resolves the compiler artifact for one repository and version.
     *
     * The upstream layout is constructed rather than queried.  It has been stable for
     * every release this wrapper has seen, and `pin` against it must not start depending
     * on an API -- or on a rate limit -- for the case that is almost all of them.
     *
     * A fork is a different matter: nothing says its asset is called flix.jar, and the
     * example this was built for publishes `flix-0.75.2+fork.wstein.260807.1.jar` under a
     * tag carrying the same build metadata.  Guessing that is worse than asking, so forks
     * are resolved from the release itself, which also hands back the digest GitHub holds.
     */
    static Asset resolveRelease(String repo, String version) {
        if (repo.equals(UPSTREAM_REPO))
            return new Asset("flix.jar",
                "https://github.com/" + UPSTREAM_REPO + "/releases/download/v"
                    + canonical(version) + "/flix.jar", null);

        String tag = "v" + version;
        String body;
        try {
            body = httpGet("https://api.github.com/repos/" + repo + "/releases/tags/"
                         + encodeTag(tag));
        } catch (Fail f) {
            // The overwhelmingly likely cause is a tag that is not there, and the bare
            // HTTP status says nothing about which of the two arguments was wrong.
            throw w005(f.getMessage() + "\n       " + repo + " has no release tagged "
                     + q(tag) + "; the version must match the tag exactly,"
                     + " build metadata included");
        }
        List<String> jars = new ArrayList<>();
        String name = null, url = null, sha = null;
        for (String a : jsonObjects(body, "assets")) {
            String n = jsonField(a, "name");
            if (n == null || !n.endsWith(".jar")) continue;
            jars.add(n);
            // A repository that ships several jars has to be told apart somehow, and the
            // stock name is the only convention there is; otherwise a lone jar is it.
            if (name == null || n.equals("flix.jar")) {
                name = n;
                url = jsonField(a, "browser_download_url");
                String d = jsonField(a, "digest");
                sha = d != null && d.startsWith("sha256:") ? d.substring(7) : null;
            }
        }
        if (name == null)
            throw w005("no .jar asset on " + repo + " release " + tag
                     + "\n       check that the tag exists and publishes a compiler jar");
        if (jars.size() > 1 && !name.equals("flix.jar"))
            throw w005(repo + " release " + tag + " publishes several jars and none is"
                     + " flix.jar: " + String.join(", ", jars));
        if (url == null || !url.startsWith("https://"))
            throw w005("release asset " + q(name) + " has no https download url");
        validateUrl(url, repo + " release " + tag);
        if (sha != null && !sha.matches("[0-9a-f]{64}")) sha = null;
        return new Asset(name, url, sha);
    }

    /** Every brace-balanced object inside the array under `"key":`. */
    static List<String> jsonObjects(String json, String key) {
        List<String> out = new ArrayList<>();
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return out;
        int open = json.indexOf('[', i);
        if (open < 0) return out;
        int depth = 0, start = -1;
        for (int j = open; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '{') { if (depth++ == 0) start = j; }
            else if (c == '}') { if (--depth == 0 && start >= 0) out.add(json.substring(start, j + 1)); }
            else if (c == ']' && depth == 0) break;
        }
        return out;
    }

    /**
     * `./flix pin [<owner>/<repo>] <version>`.
     *
     * The two are told apart by the slash, which a version can never contain -- the
     * grammar rejects it -- so the order does not matter and neither does a flag.  An
     * omitted repository means the one already in the lock, so re-pinning a project that
     * tracks a fork stays on that fork: rebuilding the upstream URL every time silently
     * moved such a project back to stock, and because both are honestly version 0.75.2,
     * nothing about it looked wrong.
     */
    static String[] parsePin(List<String> args, Lock existing) {
        String repo = null, version = null;
        for (String a : args) {
            if (a.contains("/")) {
                if (repo != null) throw w009("pin: two repositories given");
                repo = checkRepo(a, "pin");
            } else {
                if (version != null) throw w009("pin: two versions given");
                version = a;
            }
        }
        if (version == null)
            throw w002("pin: no version\n       usage: ./flix pin [<owner>/<repo>] <version>");
        validateVersion(version, "pin");
        if (repo == null) repo = existing != null && existing.repo() != null
                               ? existing.repo() : UPSTREAM_REPO;
        return new String[] { repo, version };
    }

    // ---- acquisition ------------------------------------------------------

    static Path compilerPath(Lock lock) {
        return cacheHome().resolve("compilers")
                 .resolve("flix-" + canonical(lock.version()) + "-" + lock.sha256() + ".jar");
    }

    /**
     * Validated on every run, not only when a download happens: a warm cache would
     * otherwise hide a malformed mirror setting until the day it is actually needed.
     */
    static void validateDistUrl() {
        String base = env("FLIX_DIST_URL");
        if (base == null) return;
        if (!base.startsWith("https://"))
            throw w008("FLIX_DIST_URL must be https, got " + q(redact(base)));
        URI u;
        try { u = URI.create(base); }
        catch (IllegalArgumentException e) {
            throw w008("FLIX_DIST_URL is not a valid URI: " + q(redact(base)));
        }
        // URI.create happily accepts `https:///mirror`, which names no host at all.  The
        // scheme test alone let that through, and it resurfaced as an uncaught
        // IllegalArgumentException out of HttpRequest.newBuilder in the middle of the
        // download -- a stack trace, at the point where the setting could no longer be
        // blamed.  A base URL may legitimately have no path, so only the host is required.
        if (u.getHost() == null || u.getHost().isBlank())
            throw w008("FLIX_DIST_URL has no host: " + q(redact(base)));
        if (u.getPath() != null && u.getPath().contains(".."))
            throw w008("FLIX_DIST_URL path must not contain '..': " + q(redact(base)));
    }

    /**
     * Structural validation, so a malformed lock produces a FLIXW diagnostic rather than
     * an uncaught IllegalArgumentException from URI.create deep in the download path.
     */
    static void validateUrl(String url, String where) {
        if (!url.startsWith("https://")) throw w002(where + ": url must be https, got " + q(url));
        URI u;
        try { u = URI.create(url); }
        catch (IllegalArgumentException e) { throw w002(where + ": url is not a valid URI: " + q(url)); }
        if (u.getHost() == null || u.getHost().isBlank())
            throw w002(where + ": url has no host: " + q(url));
        if (u.getPath() == null || u.getPath().isBlank() || u.getPath().contains(".."))
            throw w002(where + ": url has no usable path: " + q(url));
    }

    static String rewriteBase(String url) {
        String base = env("FLIX_DIST_URL");
        if (base == null) return url;
        validateDistUrl();
        int slash = url.indexOf('/', "https://".length());
        String tail = slash < 0 ? "" : url.substring(slash);
        return base.replaceAll("/+$", "") + tail;
    }

    static Path acquire(Lock lock) {
        Path jar = compilerPath(lock);
        if (!Files.isRegularFile(jar)) {
            Path dir = jar.getParent(), tmp;
            String url = rewriteBase(lock.url());          // validated before we announce
            System.err.println("flixw: downloading Flix " + lock.version() + " from " + redact(url));
            try {
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, ".flix-", ".part");
            } catch (IOException e) {
                throw w007("cannot prepare cache " + dir + ": " + why(e));
            }
            try {
                download(url, tmp);                              // exactly one attempt
                String got = sha256(tmp);
                if (!got.equals(lock.sha256()))
                    throw w006("digest mismatch for " + redact(lock.url())
                             + "\n       expected " + lock.sha256() + "\n       actual   " + got);
                try { Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException e) {
                    if (!Files.isRegularFile(jar))               // no identical concurrent winner
                        throw w007("cannot install " + jar + ": " + e.getMessage());
                }
            } finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
        }
        tr("compiler present");
        String got = sha256(jar);                                // unconditional, every run
        tr("sha256 done");
        if (!got.equals(lock.sha256()))
            throw w006("cached " + jar + " no longer matches its pinned digest");
        return jar;
    }

    static void download(String url, Path dest) {
        if (!url.startsWith("https://")) throw w005("refusing non-https url " + redact(url));
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "flixw/" + WRAPPER_VERSION).build();
        try {
            HttpResponse<Path> res = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
            if (!"https".equals(res.uri().getScheme()))
                throw w005("refusing a redirect off https: " + redact(res.uri().toString()));
            if (res.statusCode() != 200)
                throw w005("HTTP " + res.statusCode() + " for " + redact(url)
                         + "\n       check that flix.toml names a published release.");
        } catch (IOException e) {
            throw w005("download failed: " + redact(url) + "\n       " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw w005("download interrupted");
        }
    }

    // ---- bounded subprocess capture ---------------------------------------

    /**
     * Runs a child and returns its merged output, bounded in both bytes and wall clock.
     * Returns null when the child did not finish in time, or its output could not be read.
     *
     * The obvious shape -- a read loop with a deadline test in its condition -- bounds
     * nothing: the test runs *between* reads, and read() on a pipe blocks until the writer
     * produces a byte or closes it. A child that starts and then answers nothing parks
     * stage 0 inside that one call forever, which is precisely what a process run for
     * information must never do. So the read runs on a daemon thread and the timeout is
     * enforced on the process, which is the only handle that can actually be revoked.
     * The byte cap is a separate bound: a chatty child would otherwise exhaust the heap.
     */
    static String runCapture(List<String> cmd, Duration timeout, int cap) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String[] box = new String[1];
        Thread readerThread = null;
        try {
            p.getOutputStream().close();
            Thread reader = new Thread(() -> {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[1 << 16];
                    int total = 0, n;
                    while (total < cap && (n = in.read(buf)) > 0) {
                        sink.write(buf, 0, Math.min(n, cap - total));
                        total += n;
                    }
                    // Reaching the cap ends the child now.  Closing the pipe alone does
                    // not: a writer that ignores the error keeps going and still costs
                    // the whole timeout, for output already being discarded.
                    if (total >= cap) { p.destroy(); p.destroyForcibly(); }
                } catch (IOException ignored) {
                    // A broken or closed pipe is a truncated capture, not a failure;
                    // whatever arrived before it is still worth parsing.
                } finally {
                    // Written once, read only after join(). String has final fields, so
                    // it is safely published even if that join times out.
                    box[0] = sink.toString(StandardCharsets.UTF_8);
                }
            }, "flixw-capture");
            reader.setDaemon(true);
            readerThread = reader;
            reader.start();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return null;
            reader.join(2000);          // the child is gone; this is EOF, not a wait
            return box[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            // A probe must never outlive the probe. Killing the child alone is not enough:
            // anything it started is reparented and keeps running, and the reader thread
            // would otherwise be left parked on a pipe nobody will close.
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                try { p.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (readerThread != null) {
                try { readerThread.join(2000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    // ---- Java selection ---------------------------------------------------

    record Jvm(Path exe, int feature, String how) {}

    static Path exeIn(String home) {
        return Paths.get(home, "bin", isWindows() ? "java.exe" : "java");
    }

    /** Reads <home>/release when it is present and parseable; otherwise runs the candidate once. */
    static int probe(Path exe) {
        Path home = exe.getParent() == null ? null : exe.getParent().getParent();
        if (home != null) {
            Path rel = home.resolve("release");
            if (Files.isRegularFile(rel)) {
                try {
                    String t = Files.readString(rel, StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("(?m)^JAVA_VERSION=\"([^\"]+)\"").matcher(t);
                    if (m.find()) {
                        Integer f = feature(m.group(1));
                        if (f != null) return f;
                    }
                } catch (IOException ignored) { }
            }
        }
        try {                                                     // one execution, no retry
            // Bounded: a candidate java that hangs on startup -- a broken installation, a
            // stalled network filesystem -- must cost this probe a timeout, not the run.
            String out = runCapture(List.of(exe.toString(), "-XshowSettings:properties", "-version"),
                                    PROBE_TIMEOUT, 1 << 18);
            if (out != null) {
                Matcher m = Pattern.compile("java\\.specification\\.version = ([0-9.]+)").matcher(out);
                if (m.find()) { Integer f = feature(m.group(1)); if (f != null) return f; }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    static Integer feature(String v) {
        Matcher m = Pattern.compile("^([0-9]+)").matcher(v);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static boolean strictJava() { return env("FLIXW_STRICT_JAVA") != null; }

    /**
     * Below MIN_JAVA is always fatal: the compiler will not run.  Above TESTED_CEILING is
     * a warning, because a JDK upgrade must not break a wrapper whose pinned compiler
     * tolerates it.  FLIXW_STRICT_JAVA=1 makes the ceiling fatal for reproducible builds.
     */
    static boolean acceptable(int f, String source) {
        if (f < 0) return false;
        if (f < MIN_JAVA) return false;
        if (f > TESTED_CEILING) {
            if (strictJava()) return false;
            w011("Java " + f + " (" + source + ") is above the tested ceiling "
               + TESTED_CEILING + "; proceeding. Set FLIXW_STRICT_JAVA=1 to make this fatal.");
        }
        return true;
    }

    /**
     * Picks among discovered installations: the newest JDK that is still inside the
     * tested interval, and only if none is, the one just above it.
     *
     * Taking the first acceptable candidate in directory order was the earlier rule, and
     * it answers by filename.  On a machine carrying 11, 17, 21, 25 and 26 it selected 26
     * -- outside the tested interval, and warned about on every run -- because the
     * symlink named `java` sorts before `openjdk@21`.  Nothing was wrong with the search;
     * the choice was made by `sort`.
     *
     * Above the ceiling is a last resort rather than a preference, so the lowest such
     * candidate wins: it is the one closest to ground that has actually been tested.
     * Returns null when nothing is usable, which is the caller's cue to fail.
     */
    static Jvm chooseInstall(List<Jvm> candidates, boolean strict) {
        Jvm tested = null, above = null;
        for (Jvm c : candidates) {
            if (c.feature() < MIN_JAVA) continue;
            if (c.feature() <= TESTED_CEILING) {
                if (tested == null || c.feature() > tested.feature()) tested = c;
            } else if (!strict) {
                if (above == null || c.feature() < above.feature()) above = c;
            }
        }
        return tested != null ? tested : above;
    }

    static Jvm selectJava() {
        for (String var : new String[] { "FLIX_JAVA_HOME", "JAVA_HOME" }) {
            String h = env(var);
            if (h == null) continue;
            Path exe = exeIn(h);
            if (!Files.isRegularFile(exe))
                throw w004(var + "=" + h + " has no " + exe.getFileName() + " at " + exe);
            if (!Files.isExecutable(exe))
                throw w004(var + "=" + h + ": " + exe + " is not executable");
            int f = probe(exe);
            if (!acceptable(f, var))
                throw w004(var + "=" + h + " is Java " + (f < 0 ? "unidentifiable" : f)
                         + "; flixw needs [" + MIN_JAVA + ", " + TESTED_CEILING + "]"
                         + (strictJava() ? " (FLIXW_STRICT_JAVA is set)" : ""));
            return new Jvm(exe, f, var);
        }
        int self = Runtime.version().feature();
        Path selfExe = ProcessHandle.current().info().command()
                .map(Paths::get).orElse(exeIn(System.getProperty("java.home")));
        if (acceptable(self, "running JVM")) return new Jvm(selfExe, self, "running JVM");

        // Every candidate is probed before any is chosen.  probe() reads the JDK's own
        // release file first and only executes a candidate that has none, so this is
        // cheap, and it is reached only when the JVM already running is unusable.
        List<Jvm> found = new ArrayList<>();
        Path mine = installedJdk();
        if (mine != null) {
            int f = probe(mine);
            if (f >= MIN_JAVA) found.add(new Jvm(mine, f, "installed by flixw"));
        }
        for (Path cand : knownInstalls()) {
            int f = probe(cand);
            if (f >= MIN_JAVA) found.add(new Jvm(cand, f, "known installation"));
        }
        Jvm pick = chooseInstall(found, strictJava());
        if (pick != null) return pick;
        return noJavaFound(self);
    }

    /**
     * Directories a JDK is commonly unpacked into.  Deliberately only directories: the
     * OS-native inventories are either unusable or misleading here.  `java_home -V` is
     * blind to Homebrew, which on macOS is where the JDKs usually are;
     * `update-alternatives --config` is interactive and wants root; `dpkg`, `rpm`, `scoop
     * list` and `choco list` answer with package names rather than paths; and `find /` is
     * an unbounded walk on a tool that runs on every command.  A directory that is not
     * there costs one stat.
     */
    static List<Path> knownInstalls() {
        List<Path> out = new ArrayList<>();
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (isMac()) {
            roots.add(Paths.get("/Library/Java/JavaVirtualMachines"));
            roots.add(Paths.get(home, "Library/Java/JavaVirtualMachines"));
            roots.add(Paths.get("/opt/homebrew/opt"));            // Homebrew, Apple silicon
            roots.add(Paths.get("/usr/local/opt"));               // Homebrew, Intel
        } else if (!isWindows()) {
            roots.add(Paths.get("/usr/lib/jvm"));
            roots.add(Paths.get("/usr/lib64/jvm"));
            roots.add(Paths.get("/usr/java"));
            roots.add(Paths.get("/opt/java"));
        } else {
            roots.add(Paths.get("C:\\Program Files\\Java"));
            roots.add(Paths.get("C:\\Program Files\\Eclipse Adoptium"));
            roots.add(Paths.get("C:\\Program Files\\Microsoft"));
            roots.add(Paths.get("C:\\Program Files\\Amazon Corretto"));
            roots.add(Paths.get("C:\\Program Files\\Zulu"));
            roots.add(Paths.get("C:\\Program Files (x86)\\Java"));
            roots.add(Paths.get(home, "scoop", "apps"));          // scoop
            String localApp = env("LOCALAPPDATA");                // per-user installers
            if (localApp != null) roots.add(Paths.get(localApp, "Programs"));
        }
        // Version managers hold the JDKs of anyone who keeps more than one, and none of
        // them registers with the OS -- which is exactly the case this search exists for.
        for (String vm : new String[] { ".sdkman/candidates/java", ".asdf/installs/java",
                                        ".local/share/mise/installs/java", ".jenv/versions",
                                        ".gradle/jdks" })
            roots.add(Paths.get(home, vm.split("/")));

        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (var s = Files.list(r)) {
                s.sorted().forEach(d -> {
                    for (Path h : new Path[] { d, d.resolve("Contents/Home"),
                                               d.resolve("libexec/openjdk.jdk/Contents/Home"),
                                               d.resolve("current") }) {   // scoop's shim
                        Path e = h.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
                        if (Files.isExecutable(e)) { out.add(e); return; }
                    }
                });
            } catch (IOException ignored) { }
        }
        return out;
    }

    // ---- optional JDK provisioning ----------------------------------------
    //
    // This reverses a stated scope limit, deliberately and on request.  Provisioning means
    // picking a vendor, tracking per-platform archives and digests, unpacking safely, and
    // owning a licensing story; what follows accepts that for exactly one vendor, only
    // when asked, and never silently.
    //
    // Eclipse Temurin, from Adoptium.  It is vendor-neutral rather than tied to one
    // cloud's ecosystem, it is TCK-verified under GPLv2 with the Classpath Exception so it
    // is usable commercially without further conditions, and its API publishes a SHA-256
    // per package -- which is the part that matters here, because it lets a JDK be
    // verified the same way the compiler is.  An unverified JDK download inside a tool
    // built around digest verification would be absurd.  One vendor, named in one place,
    // is also one fewer thing for a reader to check.

    /** Adoptium answers in a few tens of KiB; this is room to spare, not a target. */
    static final int METADATA_CAP = 1 << 21;

    static final String ADOPTIUM_API = "https://api.adoptium.net/v3/assets/latest/";
    static final String ADOPTIUM_RELEASES = "https://github.com/adoptium/";

    record JdkPackage(String name, String url, String sha256) {}

    /** aarch64 or x64 as Adoptium spells it, or null where it publishes nothing for us. */
    static String jdkArch() {
        return switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64" -> "x64";
            default -> null;
        };
    }

    /** Windows gets a zip; nobody publishes a tar.gz for it. */
    static String jdkArchiveType() { return isWindows() ? "zip" : "tar.gz"; }

    /** One bounded HTTPS GET returning text.  Metadata only; bytes go through download(). */
    static String httpGet(String url) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "flixw/" + WRAPPER_VERSION).build();
        try {
            // Bounded, because this response supplies both the JDK's URL and the digest it
            // will be verified against: a server that answers forever would otherwise be
            // answering into the heap. ofString has no cap, so the body is read by hand.
            HttpResponse<InputStream> res =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
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

    /** Enough JSON for flat string fields of one small, known response. */
    static String jsonField(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                           .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The first brace-balanced object under `"key":`.  Enough for this one response, whose
     * values are URLs, digests and filenames and contain no braces of their own.
     */
    static String jsonObject(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int open = json.indexOf('{', i);
        if (open < 0) return null;
        int depth = 0;
        for (int j = open; j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(open, j + 1);
        }
        return null;
    }

    /**
     * Resolves the current Temurin release for this platform.
     *
     * The response describes an `installer` -- a .pkg or .msi -- *before* the `package`
     * that is the archive, and both carry a `checksum` and a `link`.  Reading the first
     * match in the document would fetch a macOS installer package and verify it against
     * its own digest: consistently, and uselessly.  The fields are read out of the
     * `package` object for that reason.
     */
    static JdkPackage resolveTemurin() {
        String arch = jdkArch();
        if (arch == null)
            throw w003("no Temurin build is published for " + System.getProperty("os.name")
                     + " " + System.getProperty("os.arch") + "; install a JDK by hand");
        String os = isWindows() ? "windows" : isMac() ? "mac" : "linux";
        String body = httpGet(ADOPTIUM_API + MIN_JAVA + "/hotspot?architecture=" + arch
                            + "&image_type=jdk&os=" + os + "&vendor=eclipse");
        String pkg = jsonObject(body, "package");
        if (pkg == null)
            throw w005("Adoptium published no JDK " + MIN_JAVA + " for " + os + "/" + arch);
        String name = jsonField(pkg, "name");
        String url = jsonField(pkg, "link");
        String sha = jsonField(pkg, "checksum");
        if (name == null || url == null || sha == null)
            throw w005("Adoptium metadata was missing name, link or checksum for "
                     + os + "/" + arch);
        // All three came out of a third party's JSON.  None is used as a URL, a filename
        // or a digest until it has been checked to be one.
        if (!url.startsWith(ADOPTIUM_RELEASES))
            throw w005("refusing a download outside " + ADOPTIUM_RELEASES + ": " + redact(url));
        // A prefix test still admits text that is not a URI, and URI.create would then
        // throw out of HttpRequest.newBuilder with no FLIXW code attached -- the same way
        // `https:///mirror` once did for FLIX_DIST_URL.
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

    /**
     * Downloads, verifies and unpacks one JDK into the wrapper cache, and returns its
     * `java`.  The directory is named for the archive, which carries the exact build, so a
     * second project on the same machine reuses it and a re-run is a no-op.
     */
    static Path installJdk(JdkPackage p) {
        Path dir = cacheHome().resolve("jdks");
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
                try {
                    Files.move(staging, dest, StandardCopyOption.ATOMIC_MOVE);
                    staging = null;
                } catch (IOException e) {
                    // Another process may have finished the same install first. That is a
                    // win, not a collision: content is addressed by the archive name, so
                    // what is there is what we were about to put there.
                    if (findJavaUnder(dest) == null) throw e;
                }
                try { Files.writeString(origin, p.sha256() + System.lineSeparator()); }
                catch (IOException ignored) { }   // a read-only cache is still usable
            } catch (IOException e) {
                throw w007("cannot install a JDK into " + dir + ": " + why(e));
            } finally {
                if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) { } }
                if (staging != null) deleteTree(staging);
            }
        }
        Path exe = findJavaUnder(dest);
        if (exe == null) throw w003("no bin/java inside " + dest);
        // One line naming the java, so a shim can use it without knowing that Temurin
        // nests differently on every platform -- and so that a machine with no system
        // java at all still has a route back to this one. Part of the cache contract.
        try {
            Files.writeString(dir.resolve("default"), exe + System.lineSeparator(),
                              StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A read-only cache is a correct configuration; the JDK still works here.
        }
        return exe;
    }

    /** The java recorded by the last successful install, if it is still there. */
    static Path installedJdk() {
        Path marker = cacheHome().resolve("jdks").resolve("default");
        try {
            if (!Files.isRegularFile(marker)) return null;
            Path exe = Paths.get(Files.readString(marker, StandardCharsets.UTF_8).strip())
                            .toAbsolutePath().normalize();
            // The shims execute what this names, so it may only name something inside the
            // directory flixw unpacks into. A marker pointing anywhere else is not a
            // record of an install; it is an instruction to run someone else's binary.
            Path jdks = cacheHome().resolve("jdks").toAbsolutePath().normalize();
            if (!exe.startsWith(jdks) || !Files.isRegularFile(exe)) return null;
            // A lexical prefix is not containment: a symlink or junction under jdks/ can
            // point anywhere, and this path is about to be executed. Both sides are
            // resolved before they are compared.
            if (!exe.toRealPath().startsWith(jdks.toRealPath())) return null;
            return exe;
        } catch (IOException | RuntimeException e) { return null; }
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
        try (var zin = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
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
     * The executable bit is only required where it means something.  Adoptium builds its
     * Windows zip on a Unix machine, so entries carry a mode of 0770, and java.util.zip
     * discards it: every file lands 0644.  On Windows that is irrelevant, because what
     * makes java.exe runnable there is the extension and the ACL -- but a check for it
     * would rest on platform semantics rather than on anything unpacking guarantees.  On
     * POSIX the bit does mean something and tar preserves it, so it is still required.
     */
    static Path findJavaUnder(Path root) {
        String want = isWindows() ? "java.exe" : "java";
        try (var s = Files.walk(root, 6)) {
            return s.filter(x -> x.getFileName().toString().equals(want)
                              && x.getParent() != null
                              && x.getParent().getFileName().toString().equals("bin")
                              && Files.isRegularFile(x)
                              && (isWindows() || Files.isExecutable(x)))
                    .findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    /** What to type on this OS, pointing at the same vendor flixw would fetch. */
    static void jdkInstructions() {
        System.err.println("       install a JDK " + MIN_JAVA + "+ and re-run, for example:");
        if (isMac()) {
            System.err.println("         brew install temurin@" + MIN_JAVA);
        } else if (isWindows()) {
            System.err.println("         winget install EclipseAdoptium.Temurin." + MIN_JAVA + ".JDK");
            System.err.println("         scoop install temurin" + MIN_JAVA + "-jdk");
        } else {
            System.err.println("         apt install temurin-" + MIN_JAVA + "-jdk         (Debian, Ubuntu)");
            System.err.println("         dnf install temurin-" + MIN_JAVA + "-jdk         (Fedora, RHEL)");
            System.err.println("         pacman -S jdk" + MIN_JAVA + "-openjdk           (Arch)");
        }
        System.err.println("         or https://adoptium.net/temurin/releases/?version=" + MIN_JAVA);
        System.err.println("       then set JAVA_HOME, or put its bin directory on PATH.");
    }

    /**
     * Offers to fetch one only when there is somebody to answer.  A prompt written into a
     * pipe, a CI log or a hook is not a question, it is a hang, so those get the
     * instructions and a failure instead -- and an opt-in they can set once.
     */
    static boolean offerJdk() {
        if (env("FLIXW_INSTALL_JDK") != null) return true;
        if (env("CI") != null || System.console() == null) {
            System.err.println("       or set FLIXW_INSTALL_JDK=1 to let flixw download a"
                             + " verified Temurin " + MIN_JAVA + " into its own cache,");
            System.err.println("       or run: ./flix wrapper --install-jdk");
            return false;
        }
        System.err.print("flixw: download Eclipse Temurin " + MIN_JAVA
                       + " into the flixw cache instead? [y/N] ");
        String line = System.console().readLine();
        return line != null && line.strip().toLowerCase(Locale.ROOT).startsWith("y");
    }

    /** Nothing usable was found: say how to fix it, then offer to do it. */
    static Jvm noJavaFound(int self) {
        System.err.println("FLIXW003: no Java in [" + MIN_JAVA + ", " + TESTED_CEILING
                         + "] found; this JVM is " + self);
        jdkInstructions();
        if (!offerJdk()) throw w003("no usable Java; see the instructions above");
        Path exe = installJdk(resolveTemurin());
        int f = probe(exe);
        if (f < MIN_JAVA)
            throw w003("the JDK just installed reports Java " + f + ", which is below "
                     + MIN_JAVA + "; install one by hand");
        System.err.println("flixw: using " + exe);
        System.err.println("       flixw owns this JDK; set JAVA_HOME to it to use it elsewhere.");
        return new Jvm(exe, f, "flixw-installed Temurin");
    }

    /** `./flix wrapper --install-jdk`, so the choice need not wait for a failure. */
    static void installJdkVerb(List<String> argv) {
        if (argv.size() > 1)
            throw w008(wrapperUsage("'--install-jdk' takes no arguments"));
        Path exe = installJdk(resolveTemurin());
        int f = probe(exe);
        if (f < MIN_JAVA)
            throw w003("the JDK just installed reports Java " + f + ", below " + MIN_JAVA);
        System.out.println(exe);
        System.err.println("flixw: Temurin Java " + f + " is installed.");
        System.err.println("       flixw will find it from now on; export JAVA_HOME="
                         + exe.getParent().getParent() + " to use it elsewhere.");
    }

    // ---- FLIX_JVM_OPTS ----------------------------------------------------

    static final Pattern UNSAFE = Pattern.compile(
        "^(-jar|-cp|-classpath|--class-path|--module-path|-p|-javaagent:.*|-agentlib:.*|"
      + "-agentpath:.*|@.*|-XX:OnError=.*|-XX:OnOutOfMemoryError=.*|-XX:\\+?UnlockDiagnosticVMOptions|"
      + "-XX:Flags=.*|--patch-module.*|-Xbootclasspath.*)$");

    static List<String> jvmOpts() {
        String raw = env("FLIX_JVM_OPTS");
        if (raw == null) return List.of();
        List<String> toks = tokenize(raw);
        boolean unsafeOk = env("FLIXW_UNSAFE_JVM_OPTS") != null;
        for (String t : toks) {
            if (!t.startsWith("-")) throw w008("FLIX_JVM_OPTS: " + q(t) + " is not an option");
            if (UNSAFE.matcher(t).matches() && !unsafeOk)
                throw w008("FLIX_JVM_OPTS: " + q(t) + " needs FLIXW_UNSAFE_JVM_OPTS=1");
        }
        return toks;
    }

    /** One documented tokenizer: whitespace separates; '' and "" quote; \ escapes inside "" and bare. */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean has = false; char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < s.length()) cur.append(s.charAt(++i));
                else cur.append(c);
            } else if (c == '\'' || c == '"') { quote = c; has = true; }
            else if (c == '\\' && i + 1 < s.length()) { cur.append(s.charAt(++i)); has = true; }
            else if (Character.isWhitespace(c)) {
                if (has || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); has = false; }
            }
            else { cur.append(c); has = true; }
        }
        if (quote != 0) throw w008("FLIX_JVM_OPTS: unterminated " + quote + " quote");
        if (has || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    // ---- project root -----------------------------------------------------

    /** Resolves this file's own symlink chain without physicalizing unrelated directories. */
    static Path wrapperAnchor() {
        String src = env("FLIXW_SOURCE");                 // set by the self-compiled fast path
        Path self = null;
        if (src != null) self = Paths.get(src);
        else {
            try {
                self = Paths.get(flix.class.getProtectionDomain().getCodeSource()
                                 .getLocation().toURI());
            } catch (Exception ignored) { }
        }
        if (self == null) return Paths.get("").toAbsolutePath();
        try { self = resolveLinkChain(self.toAbsolutePath()); } catch (IOException ignored) { }
        Path dir = Files.isDirectory(self) ? self : self.getParent();   // .../.flix-wrapper
        // The anchor is the PROJECT ROOT: the parent of the wrapper directory, not the
        // wrapper directory itself, which holds no flix.toml and would bound every search
        // below the manifest.
        if (dir != null && dir.getFileName() != null
            && dir.getFileName().toString().equals(WRAPPER_DIR)) return dir.getParent();
        return dir == null ? Paths.get("").toAbsolutePath() : dir;
    }

    static Path resolveLinkChain(Path p) throws IOException {
        Path cur = p;
        for (int i = 0; i < 40 && Files.isSymbolicLink(cur); i++) {
            Path t = Files.readSymbolicLink(cur);
            cur = t.isAbsolute() ? t : cur.getParent().resolve(t).normalize();
        }
        return cur;
    }

    /**
     * Search upward from cwd for flix.toml, bounded above by the wrapper's own project.
     * Invocation from outside that tree is refused rather than searched: an unbounded walk
     * finds the first stray manifest above cwd and silently builds an unrelated project.
     */
    static Path findRoot(Path anchor) {
        String o = env("FLIX_PROJECT_ROOT");
        if (o != null) {
            Path r = Paths.get(o).toAbsolutePath().normalize();
            if (!Files.isRegularFile(r.resolve("flix.toml")))
                throw w001("FLIX_PROJECT_ROOT=" + o + " has no flix.toml");
            return r;
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (!cwd.startsWith(anchor))
            throw w001("this wrapper belongs to " + anchor + ", but the current directory is "
                     + cwd + "\n       cd into the project, or set FLIX_PROJECT_ROOT explicitly");
        for (Path p = cwd; p != null && p.startsWith(anchor); p = p.getParent())
            if (Files.isRegularFile(p.resolve("flix.toml"))) return p;
        throw w001("no flix.toml between " + cwd + " and " + anchor);
    }

    // ---- verb capture -----------------------------------------------------

    /**
     * Verb records live in the wrapper cache keyed by identity, never beside the JAR:
     * a content-addressed compiler directory is legitimately read-only, and a FLIX_JAR
     * override points at a JAR flixw does not own and must not write next to.
     */
    static Path verbsFile(Path jar, String identity) {
        return cacheHome().resolve("verbs").resolve(identity + ".verbs");
    }

    /** Pinned compilers are identified by their locked digest; overrides by path+size+mtime. */
    static String verbIdentity(Path jar, Lock lock, boolean override) {
        if (!override) return lock.sha256();
        try {
            return "override-" + sha256((jar.toAbsolutePath() + "|" + Files.size(jar) + "|"
                    + Files.getLastModifiedTime(jar).toMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "override-" + sha256(jar.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static List<String> verbs(Path javaExe, Path jar, String identity) {
        Path vf = verbsFile(jar, identity);
        try {
            if (Files.isRegularFile(vf)) {
                List<String> v = new ArrayList<>(Files.readAllLines(vf, StandardCharsets.UTF_8));
                v.removeIf(String::isBlank);
                if (!v.isEmpty()) return v;
            }
        } catch (IOException ignored) { }
        List<String> v;
        try { v = captureVerbs(javaExe, jar); }
        catch (Fail f) {
            // Capture is an optimisation, never a precondition.  Its sole purpose is to
            // notice that a pinned compiler has claimed one of WRAPPER_VERBS.  Failing
            // here would make an unparseable help screen fatal for `check`, which does
            // not consult the verb set at all.
            w010(f.getMessage().split("\n")[0]
               + "\n          using the built-in verb table for Flix 0.75.x;"
               + " compiler-first dispatch still applies");
            return BUILTIN_VERBS;
        }
        try {
            Files.createDirectories(vf.getParent());
            Path tmp = Files.createTempFile(vf.getParent(), ".verbs-", ".part");
            Files.writeString(tmp, String.join("\n", v) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, vf, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // A read-only cache is a correct configuration, not an error.  Stay silent.
            tr("cannot cache verbs at " + vf + ": " + e.getMessage());
        }
        return v;
    }

    static List<String> captureVerbs(Path javaExe, Path jar) {
        String out;
        try {
            // Real help output is a few kilobytes and arrives in well under a second.
            // Both bounds exist for JARs that are not the Flix compiler: FLIX_JAR points
            // wherever the user says, and a JAR that never answers must not wedge the run.
            out = runCapture(List.of(javaExe.toString(), "-jar", jar.toString(), "--help"),
                             HELP_TIMEOUT, HELP_CAP);
        } catch (IOException e) {
            throw w009("cannot run `flix --help`: " + e.getMessage());
        }
        if (out == null)
            throw w009("`flix --help` did not finish within " + HELP_TIMEOUT.toSeconds() + "s");
        // Two independent parses.  `Command: lsp-vscode port` carries an argument, so a
        // whole-line parse yields a phantom verb; take the first token only.
        Set<String> set = new LinkedHashSet<>();
        Matcher usage = Pattern.compile("(?m)^Usage:.*?\\[([a-z0-9|_-]+)\\]").matcher(out);
        if (usage.find()) for (String s : usage.group(1).split("\\|")) if (!s.isBlank()) set.add(s.trim());
        Matcher cmd = Pattern.compile("(?m)^Command:\\s+([A-Za-z][A-Za-z0-9_-]*)").matcher(out);
        while (cmd.find()) set.add(cmd.group(1));
        if (set.size() < 3)
            throw w009("cannot parse verbs from `flix --help` of " + jar
                     + " (got " + set.size() + " candidate(s))");
        return new ArrayList<>(set);
    }

    // ---- self-compiled stage 0 --------------------------------------------

    static Path stage0Dir(String srcHash) {
        return cacheHome().resolve("stage0").resolve(srcHash);
    }

    /**
     * Compiles this source into the cache so the shim can skip the JEP 330 source launch
     * next time.  The shim -- not stage 0 -- consults this cache: stage 0 is already the
     * running process by the time it could decide.  That is the one place the shim is
     * allowed to know a cache layout, and it is why the cache path is a versioned
     * interface between the shim and stage 0.
     */
    static void selfCompile(Path source) {
        if (source == null) return;
        byte[] bytes;
        try { bytes = Files.readAllBytes(source); } catch (IOException e) { return; }
        Path dir = stage0Dir(sha256(bytes));
        if (Files.isRegularFile(dir.resolve("flix.class"))) return;
        javax.tools.JavaCompiler jc = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (jc == null) { tr("no javac in this runtime; staying on the source path"); return; }
        Path tmp = null;
        try {
            Path parent = Files.createDirectories(dir.getParent());
            // The shims execute whatever class sits at this path, so anyone who can write
            // here can run code as this user. That is the same trust boundary as the rest
            // of the user cache, but this entry is executable, so narrow it where the
            // platform lets us. See docs/LIMITATIONS.md.
            try { parent.toFile().setReadable(false, false); parent.toFile().setReadable(true, true);
                  parent.toFile().setWritable(false, false); parent.toFile().setWritable(true, true);
                  parent.toFile().setExecutable(false, false); parent.toFile().setExecutable(true, true);
            } catch (SecurityException ignored) { }
            tmp = Files.createTempDirectory(parent, ".stage0-");
            // --release pins the classfile version to the floor flixw already requires.
            // The cache is keyed by source hash alone, so without it a stage 0 compiled by
            // a JDK 25 javac lands in a directory a later Java 21 shim will happily -cp
            // into, and that run dies on UnsupportedClassVersionError with no fallback.
            int rc = jc.run(null, java.io.OutputStream.nullOutputStream(),
                            java.io.OutputStream.nullOutputStream(),
                            "-d", tmp.toString(), "-nowarn",
                            "--release", String.valueOf(MIN_JAVA), source.toString());
            if (rc != 0) { tr("self-compile failed rc=" + rc); return; }
            Files.writeString(tmp.resolve("source.path"), source.toAbsolutePath() + "\n");
            Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            tr("self-compiled stage 0 into " + dir);
        } catch (IOException e) {
            tr("self-compile skipped: " + e.getMessage());
        } finally {
            // A directory move cannot take REPLACE_EXISTING, so a concurrent loser must
            // remove its own tree by hand or the cache accumulates one per race.
            if (tmp != null) deleteTree(tmp);
        }
    }

    static void deleteTree(Path p) {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    // ---- installed shims --------------------------------------------------
    //
    // Both shims locate a Java, then prefer the content-keyed compiled stage 0 in the
    // user cache over a source launch.  Measured: ~131ms of wrapper overhead instead of
    // ~532ms.
    //
    // The fast path is taken only when the cache lookup succeeds *and* the selected Java
    // is known to be able to load the class.  exec leaves no way back: a class built for
    // the floor and handed to an older JVM is an UnsupportedClassVersionError printed by
    // the JVM, with no FLIXW code reached, no diagnostic, and no fallback -- and it takes
    // `--wrapper-help` down with it, which is the command someone would run to find out
    // why.  Whatever a shim cannot determine, it does not act on; it falls through to the
    // source path, where stage 0 owns every Java decision and every message.

    static final String SHIM = """
        #!/bin/sh
        # flixw shim -- invariant file, byte-identical across projects for a wrapper release.
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
        src=$root/.flix-wrapper/flix.java

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

        # Nothing on PATH: fall back to the JDK flixw installed, if there is one. Its path
        # is read from a file rather than guessed, because every vendor nests differently.
        if [ -z "$java0" ] && [ -r "$cache/jdks/default" ]; then
          java0=$(cat "$cache/jdks/default" 2>/dev/null || true)
          # It names something the shim will execute, so it may only name something
          # inside the directory flixw unpacks into.
          case $java0 in "$cache/jdks/"*) ;; *) java0= ;; esac
          [ -x "$java0" ] || java0=
        fi

        if [ -z "$java0" ]; then
          echo "FLIXW003: no java executable found. Flix needs Java 21+." >&2
          echo "          Install a JDK -- Eclipse Temurin is the usual choice:" >&2
          case $(uname -s) in
            Darwin) echo "            brew install temurin@21" >&2 ;;
            *)      echo "            apt install temurin-21-jdk    (or your package manager)" >&2 ;;
          esac
          echo "            https://adoptium.net/temurin/releases/?version=21" >&2
          echo "          Then set JAVA_HOME, or put its bin directory on PATH." >&2
          echo "          With any Java 21+ present, ./flix wrapper --install-jdk will" >&2
          echo "          fetch and verify one into the flixw cache for this project." >&2
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
        if [ "$chosen" = no ] && [ -n "$jfeature" ] && [ "$jfeature" -lt 21 ] \\
           && [ -r "$cache/jdks/default" ]; then
          mine=$(cat "$cache/jdks/default" 2>/dev/null || true)
          case $mine in "$cache/jdks/"*) ;; *) mine= ;; esac
          if [ -n "$mine" ] && [ -x "$mine" ]; then
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
        if [ -n "$h" ] && [ -f "$cache/stage0/$h/flix.class" ] \\
           && [ -n "$jfeature" ] && [ "$jfeature" -ge 21 ]; then
          FLIXW_SOURCE=$src; export FLIXW_SOURCE
          exec "$java0" -cp "$cache/stage0/$h" flix "$@"
        fi
        exec "$java0" "$src" "$@"
        """;

    static final String CMD = """
        @echo off
        rem flixw cmd.exe trampoline -- invariant file.  Finds an initial java, prefers the
        rem compiled stage 0 in the user cache, else launches the source.
        setlocal enabledelayedexpansion
        set "ROOT=%~dp0"
        set "SRC=%ROOT%.flix-wrapper\\flix.java"

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
        set "MINE="
        if exist "%CACHE%\\jdks\\default" (
          for /f "usebackq delims=" %%J in ("%CACHE%\\jdks\\default") do (
            if not defined MINE set "MINE=%%J" ) )
        if defined MINE (
          set "TAIL=!MINE:%CACHE%\\jdks\\=!"
          if not "!MINE!"=="%CACHE%\\jdks\\!TAIL!" set "MINE="
        )
        if defined MINE if not exist "!MINE!" set "MINE="
        if not defined JAVA0 if defined MINE set "JAVA0=!MINE!"
        if not defined JAVA0 (
          echo FLIXW003: no java executable found. Flix needs Java 21+. 1>&2
          echo           Install a JDK -- Eclipse Temurin is the usual choice: 1>&2
          echo             winget install EclipseAdoptium.Temurin.21.JDK 1>&2
          echo             https://adoptium.net/temurin/releases/?version=21 1>&2
          echo           Then set JAVA_HOME, or put its bin directory on PATH. 1>&2
          echo           With any Java 21+ present, flix.cmd wrapper --install-jdk will 1>&2
          echo           fetch and verify one into the flixw cache for this project. 1>&2
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
        if not defined SLOWPATH if defined H if exist "!CACHE!\\stage0\\!H!\\flix.class" (
          set "FLIXW_SOURCE=%SRC%"
          "%JAVA0%" -cp "!CACHE!\\stage0\\!H!" flix %*
          exit /b !ERRORLEVEL! )
        "%JAVA0%" "%SRC%" %*
        exit /b !ERRORLEVEL!
        """;

    // ---- wrapper verbs ----------------------------------------------------

    static void wrapperVerb(String verb, List<String> rest, Path root, Lock lock, Path jar,
                            Jvm jvm, List<String> compilerVerbs) {
        switch (verb) {
            case "pin" -> {
                if (rest.isEmpty())
                    throw w009("usage: ./flix pin [<owner>/<repo>] <version>");
                String[] t = parsePin(rest, lock);
                pin(root, t[0], t[1]);
            }
            case "doctor", "setup" -> {
                report(root, lock, jar, jvm, compilerVerbs);
                if (verb.equals("setup")) System.out.println("compiler ready.");
            }
            case "validate" -> validate(root, lock, jar);
            default -> throw w009("no wrapper implementation for " + q(verb));
        }
    }

    static void report(Path root, Lock lock, Path jar, Jvm jvm, List<String> cv) {
        System.out.println("flixw            " + WRAPPER_VERSION);
        System.out.println("project root     " + root);
        System.out.println("compiler         " + (lock == null ? "-" : lock.version()));
        System.out.println("source           " + (lock == null ? "-"
            : (lock.repo() == null ? UPSTREAM_REPO : lock.repo())
              + (lock.repo() != null && !lock.repo().equals(UPSTREAM_REPO)
                 ? "  (a fork; not stock-compatibility evidence)" : "")));
        System.out.println("digest           " + (lock == null ? "-" : lock.sha256()));
        System.out.println("jar              " + (jar == null ? "-" : jar));
        System.out.println("java             " + (jvm == null ? "-" : jvm.exe() + "  (" + jvm.feature()
                                                  + ", via " + jvm.how() + ")"));
        System.out.println("cache            " + cacheHome());
        System.out.println("dist url         " + (lock == null ? "-" : redact(rewriteBase(lock.url()))));
        if (env("FLIX_DIST_URL") != null) System.out.println("mirror           FLIX_DIST_URL is set");
        for (String p : new String[] { "HTTPS_PROXY", "https_proxy", "NO_PROXY" })
            if (env(p) != null) System.out.println("proxy            " + p + "=" + redact(env(p)));
        for (String p : new String[] { "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS" })
            if (env(p) != null) System.out.println("note             " + p + "=" + redactOpts(env(p))
                                                 + "  (affects the JVM and stderr)");
        if (env("FLIX_JAR") != null) System.out.println("override         FLIX_JAR=" + env("FLIX_JAR")
                                     + "  (unverified; not stock-compatibility evidence)");
        System.out.println("compiler verbs   " + (cv == null ? "(not captured)" : String.join(" ", cv)));
        List<String> fallback = new ArrayList<>(WRAPPER_VERBS);
        if (cv != null) fallback.removeAll(cv);
        System.out.println("wrapper verbs    " + String.join(" ", fallback));
        System.out.println("pass-through     ./flix -- <args>");
    }

    /** Compares a committed invariant file against the bytes this wrapper release ships. */
    static int checkCanonical(Path file, String canonical, String label) {
        if (!Files.isRegularFile(file)) { System.out.println("FAIL  missing " + label); return 1; }
        try {
            if (Files.readString(file, StandardCharsets.UTF_8).equals(canonical)) {
                System.out.println("ok    " + label + " matches flixw " + WRAPPER_VERSION);
                return 0;
            }
            System.out.println("FAIL  " + label + " differs from flixw " + WRAPPER_VERSION
                             + " (./flix wrapper upgrade)");
        } catch (IOException e) {
            System.out.println("FAIL  unreadable " + label + ": " + why(e));
        }
        return 1;
    }

    /** The line endings the flixw block pins for one shipped path. */
    static String canonicalAttrs(String shipped) {
        return shipped.equals("flix.cmd") ? "text eol=crlf" : "text eol=lf";
    }

    static final List<String> SHIPPED =
        List.of("flix", "flix.cmd", WRAPPER_DIR + "/flix.java", WRAPPER_DIR + "/lock.toml");

    /** Does one .gitattributes pattern match one path flixw ships? */
    static boolean patternMatches(String pattern, String path) {
        String p = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        if (p.equals("*") || p.equals("**") || p.equals(path)) return true;
        if (p.startsWith("*.") && path.endsWith(p.substring(1))) return true;
        // A pattern without a slash matches that name at any depth, per gitattributes.
        return !p.contains("/") && (path.equals(p) || path.endsWith("/" + p));
    }

    /**
     * gitattributes resolves by *last* matching pattern, so a rule after the wrapper block
     * silently overrides it -- and a checked-out shim with the wrong line endings is exactly
     * the failure the block exists to prevent.
     *
     * What counts as an override is the resulting attribute, not the mere presence of a
     * later rule: a repetition of what the block already says changes nothing, and calling
     * it harmful would send someone hunting for a problem they do not have.
     */
    static int checkGitattributes(Path ga) {
        if (!Files.isRegularFile(ga)) {
            System.out.println("warn  no .gitattributes; line endings are unpinned");
            return 0;
        }
        String text;
        try { text = Files.readString(ga, StandardCharsets.UTF_8); }
        catch (IOException e) { System.out.println("FAIL  unreadable .gitattributes"); return 1; }

        String begin = "# >>> flixw >>>", end = "# <<< flixw <<<";
        int opens = count(text, begin), closes = count(text, end);
        if (opens == 0 && closes == 0) {
            System.out.println("warn  .gitattributes has no flixw block (./flix wrapper upgrade)");
            return 0;
        }
        int bad = 0;
        // Markers have to balance and be unique. A stray end marker on its own used to
        // pass for a block, and two blocks meant the last one silently won.
        if (opens != 1 || closes != 1) {
            System.out.println("FAIL  .gitattributes has " + opens + " flixw start and "
                             + closes + " end markers; expected one of each"
                             + " (./flix wrapper upgrade)");
            bad++;
        }
        int after = text.lastIndexOf(end);
        if (after < 0) return bad;                    // nothing after an end that is not there
        for (String line : text.substring(after).split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String pattern = t.split("\\s+")[0];
            String attrs = t.substring(pattern.length()).trim();
            for (String f : SHIPPED) {
                if (patternMatches(pattern, f) && !attrs.equals(canonicalAttrs(f))) {
                    System.out.println("FAIL  .gitattributes rule " + q(t)
                                     + " comes after the flixw block and changes " + f);
                    bad++;
                    break;
                }
            }
        }
        if (bad == 0) System.out.println("ok    .gitattributes block is not overridden");
        return bad;
    }

    static int count(String haystack, String needle) {
        int n = 0, at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }

    /** Runs `git <args>` in root; null when git is absent or the command fails to start. */
    static Integer git(Path root, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(Arrays.asList(args));
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).directory(root.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(30, TimeUnit.SECONDS) ? p.exitValue() : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            // A timed-out git is still running; validate must not leave one behind.
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    static void validate(Path root, Lock lock, Path jar) {
        int bad = 0;
        // The shims are invariant for a wrapper release, and this stage 0 carries their
        // canonical bytes, so drift is detectable here rather than merely reportable.
        bad += checkCanonical(root.resolve("flix"), SHIM, "./flix");
        bad += checkCanonical(root.resolve("flix.cmd"), CMD.replace("\n", "\r\n"), "./flix.cmd");
        if (!isWindows() && Files.isRegularFile(root.resolve("flix"))
            && !Files.isExecutable(root.resolve("flix"))) {
            System.out.println("FAIL  ./flix is not executable (./flix wrapper upgrade)"); bad++;
        }

        // Stage 0 cannot know its own canonical hash -- it would have to contain it -- so
        // its digest is reported for comparison against the published wrapper release.
        Path src = root.resolve(WRAPPER_DIR).resolve("flix.java");
        if (!Files.isRegularFile(src)) {
            System.out.println("FAIL  missing " + WRAPPER_DIR + "/flix.java"); bad++;
        } else {
            try {
                System.out.println("ok    " + WRAPPER_DIR + "/flix.java  sha256="
                                 + sha256(Files.readAllBytes(src)));
            } catch (IOException e) {
                System.out.println("FAIL  unreadable " + WRAPPER_DIR + "/flix.java"); bad++;
            }
        }

        String mv = null;
        try { mv = manifestVersion(root.resolve("flix.toml")); }
        catch (Fail f) {
            // validate exists to say what is wrong; failing on the first wrong thing
            // would stop it reporting the rest.
            System.out.println("FAIL  " + f.getMessage().split("\n")[0]);
            bad++;
        }
        if (lock == null) { System.out.println("FAIL  no lock"); bad++; }
        else if (mv != null && !canonical(mv).equals(canonical(lock.version()))) {
            System.out.println("FAIL  flix.toml says " + mv + ", lock pins " + lock.version()); bad++;
        } else System.out.println("ok    lock agrees with flix.toml");

        if (jar != null && Files.isRegularFile(jar)) System.out.println("ok    cached compiler digest");

        // The shims execute whatever class sits in the stage-0 cache, on path alone, so a
        // directory anyone else can write to is a way to run code as this user.  Stage 0
        // narrows the permissions it creates; this reports one it did not create.
        for (String kind : List.of("stage0", "jdks")) {
            Path dir = cacheHome().resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            try {
                var perms = Files.getPosixFilePermissions(dir);
                if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                 || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                    System.out.println("warn  " + dir + " is writable by others;"
                                     + " the shims execute what is in it (set FLIX_CACHE_HOME)");
                } else {
                    System.out.println("ok    " + kind + " cache is private to you");
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // Not a POSIX filesystem; there is nothing portable to check.
            }
        }
        bad += checkGitattributes(root.resolve(".gitattributes"));

        // Generated wrapper files are only reproducible for a collaborator if git actually
        // carries them.  A .gitignore rule that swallows the lock is silent otherwise.
        List<String> generated = List.of("flix", "flix.cmd",
                                         WRAPPER_DIR + "/flix.java", WRAPPER_DIR + "/lock.toml");
        Integer isRepo = git(root, "rev-parse", "--is-inside-work-tree");
        if (isRepo == null || isRepo != 0) {
            System.out.println("warn  not a git work tree; cannot check tracked status");
        } else {
            for (String rel : generated) {
                if (!Files.exists(root.resolve(rel))) continue;
                Integer ignored = git(root, "check-ignore", "-q", "--", rel);
                if (ignored != null && ignored == 0) {
                    System.out.println("FAIL  " + rel + " is matched by a gitignore rule"); bad++;
                    continue;
                }
                Integer tracked = git(root, "ls-files", "--error-unmatch", "--", rel);
                if (tracked != null && tracked != 0) {
                    System.out.println("warn  " + rel + " is not tracked yet (git add " + rel + ")");
                } else System.out.println("ok    " + rel + " is tracked");
            }
        }
        if (bad > 0) throw w009(bad + " validation failure(s)");
    }

    /**
     * Same-directory temp plus an atomic move.  A direct write is not a single event: a
     * termination or a power loss in the middle of one leaves a truncated manifest or a
     * half-written lock, and the rollback that was supposed to cover that cannot run
     * either.  Renaming a complete file over the old one has no such window.
     */
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

    /** Best-effort rollback; a failed restore must not mask the failure being reported. */
    static void restore(Path file, String previous) {
        try {
            if (previous == null) Files.deleteIfExists(file);
            else writeAtomic(file, previous);
        } catch (IOException ignored) { }
    }

    static void pin(Path root, String repo, String version) {
        Asset asset = resolveRelease(repo, version);
        String url = asset.url();
        Path wrapperDir = root.resolve(WRAPPER_DIR);
        Path tmp;
        try {
            Files.createDirectories(wrapperDir);
            tmp = Files.createTempFile(wrapperDir, ".pin-", ".part");
        }
        catch (IOException e) { throw w009("cannot write in " + wrapperDir + ": " + e.getMessage()); }
        Path manifest = root.resolve("flix.toml"), lockFile = lockPath(root);
        boolean hadLock = Files.isRegularFile(lockFile);
        String oldManifest = null, oldLock = null;
        try {
            download(rewriteBase(url), tmp);
            String digest = sha256(tmp);
            // When the publisher states a digest, agreeing with it is free evidence: the
            // bytes and the claim then came down two different paths, and a mirror or a
            // truncation that altered one of them is caught here rather than at first use.
            if (asset.sha256() != null && !asset.sha256().equals(digest))
                throw w006("digest mismatch for " + asset.name()
                         + "\n       " + repo + " publishes " + asset.sha256()
                         + "\n       the download hashes to " + digest);
            String lock = """
                # Generated by ./flix pin. Do not edit by hand; commit this file.
                wrapperVersion = "%s"

                [compiler]
                repo    = "%s"
                version = "%s"
                url     = "%s"
                sha256  = "%s"
                """.formatted(WRAPPER_VERSION, repo, version, url, digest);

            // The cache is filled first and every failure in it is discarded, which keeps
            // it out of the transaction below.  It is an optimisation -- the next run
            // downloads again -- but it used to run *after* the lock was written, so a
            // cache directory that could not be created rolled the manifest back and left
            // the new lock in place: exactly the drift the rollback exists to prevent.
            Path jar = cacheHome().resolve("compilers")
                         .resolve("flix-" + canonical(version) + "-" + digest + ".jar");
            if (!Files.isRegularFile(jar)) {
                try {
                    Files.createDirectories(jar.getParent());
                    Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ignored) { }
            }

            // Both previous states are captured before either file is touched, so a
            // failure part-way puts the pair back as it was rather than half of it.
            if (Files.isRegularFile(manifest))
                oldManifest = Files.readString(manifest, StandardCharsets.UTF_8);
            if (hadLock) oldLock = Files.readString(lockFile, StandardCharsets.UTF_8);

            if (oldManifest != null) {
                String updated = rewritePackageFlix(oldManifest, version, manifest.toString());
                if (updated != null && !updated.equals(oldManifest))
                    writeAtomic(manifest, updated);
            }
            writeAtomic(lockFile, lock);
            System.err.println("flixw: pinned Flix " + version + " from " + repo
                             + " (" + digest.substring(0, 16) + "...)");
            if (!repo.equals(UPSTREAM_REPO))
                System.err.println("       a fork is not stock-compatibility evidence;"
                                 + " see docs/LIMITATIONS.md");
        } catch (IOException e) {
            if (oldManifest != null) restore(manifest, oldManifest);
            if (hadLock || Files.isRegularFile(lockFile)) restore(lockFile, oldLock);
            throw w009("pin failed: " + why(e));
        } finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
    }
    static void install(Path target, Path source) {
        try {
            Path fw = target.resolve(WRAPPER_DIR);
            Files.createDirectories(fw);
            if (source == null || !Files.isRegularFile(source))
                throw w009("install needs the wrapper source; run it as: java flix.java install <dir>");
            Files.copy(source, fw.resolve("flix.java"), StandardCopyOption.REPLACE_EXISTING);
            Path shim = target.resolve("flix");
            Files.writeString(shim, SHIM, StandardCharsets.UTF_8);
            shim.toFile().setExecutable(true, false);
            Files.writeString(target.resolve("flix.cmd"), CMD.replace("\n", "\r\n"),
                              StandardCharsets.UTF_8);
            mergeGitattributes(target.resolve(".gitattributes"));
            System.out.println("installed ./flix, ./flix.cmd and " + WRAPPER_DIR
                             + "/flix.java into " + target);
            System.out.println("next: ./flix pin <version>   then commit all four files");
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
            Path shim = root.resolve("flix");
            if (!Files.isRegularFile(shim)
                || !Files.readString(shim, StandardCharsets.UTF_8).equals(SHIM)) {
                Files.writeString(shim, SHIM, StandardCharsets.UTF_8);
                System.out.println("rewrote  ./flix"); changed++;
            }
            if (!isWindows() && !Files.isExecutable(shim)) {
                shim.toFile().setExecutable(true, false);
                System.out.println("restored ./flix executable bit"); changed++;
            }
            Path cmd = root.resolve("flix.cmd");
            String cmdBytes = CMD.replace("\n", "\r\n");
            if (!Files.isRegularFile(cmd)
                || !Files.readString(cmd, StandardCharsets.UTF_8).equals(cmdBytes)) {
                Files.writeString(cmd, cmdBytes, StandardCharsets.UTF_8);
                System.out.println("rewrote  ./flix.cmd"); changed++;
            }
            Path ga = root.resolve(".gitattributes");
            String before = Files.isRegularFile(ga) ? Files.readString(ga, StandardCharsets.UTF_8) : "";
            mergeGitattributes(ga);
            if (!before.equals(Files.readString(ga, StandardCharsets.UTF_8))) {
                System.out.println("merged   ./.gitattributes"); changed++;
            }
        } catch (IOException e) { throw w009("wrapper upgrade failed: " + why(e)); }
        System.out.println(changed == 0
            ? "wrapper files already match flixw " + WRAPPER_VERSION
            : changed + " file(s) rewritten from flixw " + WRAPPER_VERSION);
        System.out.println("note: this refreshes the files this wrapper ships; to move to a"
                         + " newer flixw,\n      run `java flix.java install .` from that release.");
    }

    static void mergeGitattributes(Path ga) throws IOException {
        String begin = "# >>> flixw >>>", end = "# <<< flixw <<<";
        String block = begin + "\n/flix text eol=lf\n"
                     + "/" + WRAPPER_DIR + "/flix.java text eol=lf\n"
                     + "/" + WRAPPER_DIR + "/lock.toml text eol=lf\n"
                     + "/flix.cmd text eol=crlf\n" + end + "\n";
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

    /**
     * flixw's own namespace: `./flix wrapper [--operation]`.
     *
     * One verb, and every flixw-only operation under it as a flag.  These are not
     * stand-ins for anything Flix might one day ship, so they neither retire nor compete
     * for a name with something that will: `pin`, `doctor`, `setup` and `validate`
     * deliberately collide with names Flix could claim, and step aside the day it does.
     * Rewriting flixw's own files, or reporting flixw's own version, never will.
     *
     * Answered before the project, the lock, the network and the compiler, for the same
     * reason the flags it replaces were: a bare verb is subject to compiler-first
     * dispatch, and a compiler that happened to claim `wrapper` would make these
     * unreachable at exactly the moment someone needs them to repair the installation.
     * FLIX_BACKEND does not reach them either.
     */
    static void wrapperNamespace(List<String> argv) {
        String op = argv.size() > 1 ? argv.get(1) : "--help";
        List<String> rest = argv.subList(Math.min(2, argv.size()), argv.size());
        switch (op) {
            case "--help" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--help' takes no arguments"));
                wrapperHelp();
            }
            case "--version" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--version' takes no arguments"));
                System.out.println("flixw " + WRAPPER_VERSION);
                System.out.println("stage0 " + (env("FLIXW_SOURCE") != null ? "compiled" : "source")
                                 + "  java " + Runtime.version());
            }
            case "--upgrade" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--upgrade' takes no arguments"));
                // The only operation here that needs a project, and it resolves one itself
                // rather than making the others depend on being inside one.
                updateWrapper(findRoot(wrapperAnchor()));
            }
            case "--install-jdk" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--install-jdk' takes no arguments"));
                installJdkVerb(argv.subList(1, argv.size()));
            }
            default -> throw w008(wrapperUsage("unknown operation " + q(op)));
        }
    }

    static String wrapperUsage(String problem) {
        return "./flix wrapper: " + problem
             + "\n       usage: ./flix wrapper [--help | --version | --upgrade | --install-jdk]"
             + "\n         --help         the routing table for this project"
             + "\n         --version      the wrapper version and how stage 0 was launched"
             + "\n         --upgrade      rewrite this project's wrapper files from this flixw"
             + "\n         --install-jdk  fetch a verified Temurin " + MIN_JAVA + " into the cache";
    }

    // ---- main -------------------------------------------------------------

    public static void main(String[] args) {
        try { realMain(new ArrayList<>(Arrays.asList(args))); }
        catch (Fail f) {
            System.err.println(f.code + ": " + f.getMessage());
            System.exit(f.exit);
        }
    }

    static void realMain(List<String> argv) {
        tr("stage 0 entered");
        String first = argv.isEmpty() ? null : argv.get(0);

        // flixw's own namespace, before project, lock, network or compiler work.
        if ("wrapper".equals(first)) { wrapperNamespace(argv); return; }
        if (first != null && first.startsWith("--wrapper-"))
            throw w008("unknown launcher flag " + q(first)
                     + "\n       flixw's own operations moved under one verb:"
                     + "\n       ./flix wrapper [--help | --version | --upgrade | --install-jdk]");

        Path anchor = wrapperAnchor();
        if ("install".equals(first) && !Files.isRegularFile(lockPath(anchor))) {
            install(Paths.get(argv.size() > 1 ? argv.get(1) : ".").toAbsolutePath().normalize(),
                    selfSource());
            return;
        }

        // After install, which is entirely offline and has no business failing on a mirror
        // setting it will never use.
        validateDistUrl();

        // A misspelled backend used to read as unset, which silently returns the caller to
        // ordinary dispatch -- the one outcome someone forcing a side is testing against.
        String backend = env("FLIX_BACKEND");
        if (backend != null && !backend.equals("wrapper") && !backend.equals("compiler"))
            throw w008("FLIX_BACKEND=" + q(backend) + " is not a known backend;"
                     + " use 'wrapper' or 'compiler'");
        boolean forcedWrapper = "wrapper".equals(backend);
        boolean forcedCompiler = "compiler".equals(backend);

        Path root = findRoot(anchor);
        tr("root " + root);
        Path lockFile = lockPath(root);
        // A lock that does not parse must not stop `pin` from replacing it. Parsing used
        // to happen here and throw, so the one command documented as the repair was
        // unreachable in precisely the state it repairs -- the same trap as a missing
        // lock, one step further in. The error is carried instead of thrown, and raised
        // below for everything that actually needs a compiler.
        Lock lock = null;
        Fail lockError = null;
        if (Files.isRegularFile(lockFile)) {
            try { lock = readLock(lockFile); }
            catch (Fail f) { lockError = f; }
        }
        // Carried, not thrown, for the same reason as the lock above: pin, doctor and
        // validate are the way out of a broken manifest, and they cannot be the way out
        // if reading it is what fails first.
        String mv = null;
        Fail manifestError = null;
        try { mv = manifestVersion(root.resolve("flix.toml")); }
        catch (Fail f) { manifestError = f; }

        // Drift is detected here -- immediately after the manifest and the lock are read,
        // before any Java selection, any network access, and any compiler execution.
        String drift = (lock != null && mv != null && !canonical(mv).equals(canonical(lock.version())))
            ? "flix.toml declares " + mv + " but " + WRAPPER_DIR + "/lock.toml pins "
              + lock.version() + "\n       run: ./flix pin " + mv
            : null;

        // When the project cannot reach a compiler -- no lock yet, or a lock that
        // disagrees with the manifest -- the verbs that create and diagnose that state
        // must still run, or the repair the diagnostic recommends is unreachable. They
        // route on the built-in wrapper list alone, so no compiler is consulted.
        if ((lock == null || drift != null || manifestError != null) && first != null && !forcedCompiler
            && WRAPPER_VERBS.contains(first) && !first.equals("setup")) {
            if (lockError != null)
                System.err.println("flixw: warning: " + lockError.getMessage().split("\n")[0]);
            if (manifestError != null)
                System.err.println("flixw: warning: " + manifestError.getMessage().split("\n")[0]);
            if (drift != null) System.err.println("flixw: warning: " + drift.split("\n")[0]);
            routingNotice(first, lock == null ? "none" : lock.version());
            if (first.equals("pin")) {
                String[] t = parsePin(argv.subList(1, argv.size()), lock);
                pin(root, t[0], t[1]);
            }
            else
                wrapperVerb(first, argv.subList(1, argv.size()), root, lock, null, null, null);
            return;
        }
        if (lockError != null) throw lockError;      // unreadable, and the repair declined it
        if (manifestError != null) throw manifestError;
        if (lock == null)
            throw w002("no " + lockFile + "\n       run: ./flix pin <version>");
        if (drift != null) throw w002(drift);

        // pin is the documented repair and never needs the compiler.
        if ("pin".equals(first) && !forcedCompiler) {
            routingNotice("pin", lock.version());
            String[] t = parsePin(argv.subList(1, argv.size()), lock);
            pin(root, t[0], t[1]);
            return;
        }

        Jvm jvm = selectJava();
        tr("java " + jvm.exe() + " (" + jvm.feature() + ")");
        if (relaunch(jvm, argv)) return;
        List<String> opts = jvmOpts();

        Path jar;
        String fj = env("FLIX_JAR");
        boolean override = fj != null;
        if (override) {
            jar = Paths.get(fj).toAbsolutePath();
            if (!Files.isRegularFile(jar)) throw w008("FLIX_JAR=" + fj + " is not a file");
            System.err.println("flixw: note: FLIX_JAR override in use; the JAR is NOT digest-verified"
                             + " and this run is not stock-compatibility evidence");
        } else jar = acquire(lock);

        List<String> compilerVerbs = verbs(jvm.exe(), jar, verbIdentity(jar, lock, override));
        tr("verbs " + compilerVerbs.size());
        selfCompile(selfSource());

        // ---- dispatch ----------------------------------------------------
        boolean toCompiler; List<String> forward = argv;
        if (forcedCompiler) {
            toCompiler = true;
            if ("--".equals(first)) forward = argv.subList(1, argv.size());
        } else if (forcedWrapper && first != null && WRAPPER_VERBS.contains(first)) {
            toCompiler = false;
        } else if ("--".equals(first)) {
            toCompiler = true; forward = argv.subList(1, argv.size());
        } else if (first != null && compilerVerbs.contains(first)) {
            toCompiler = true;
        } else if (first != null && WRAPPER_VERBS.contains(first)) {
            toCompiler = false;
        } else {
            toCompiler = true;                       // unknown verbs, and no verb at all
        }

        // `help` and `--help` answer with both halves: flixw's routing table, then the
        // pinned compiler's own help, unedited and straight from the compiler.
        //
        // The two arrive here differently on purpose. `help` is a bare verb Flix could
        // plausibly claim, so it sits in WRAPPER_VERBS and rule 3 above takes it away the
        // day Flix implements one -- the same automatic retirement every wrapper verb
        // gets. `--help` is a flag, can never be a compiler verb, and so is intercepted
        // outright. Either way `./flix -- --help` and FLIX_BACKEND=compiler still reach
        // the compiler alone, which is what someone parsing its output would want.
        if (!toCompiler && "help".equals(first)
            || (!forcedCompiler && "--help".equals(first) && argv.size() == 1)) {
            wrapperHelp();
            System.out.println();
            System.out.println("---- Flix " + lock.version() + " ".repeat(3)
                             + "(./flix -- --help for this alone) ----");
            System.out.println();
            launch(jvm.exe(), opts, jar, List.of("--help"));
            return;                                  // launch exits; this is for the reader
        }

        if (!toCompiler) {
            if (forcedWrapper && compilerVerbs.contains(first))
                System.err.println("flix: " + q(first) + " \u2192 wrapper " + WRAPPER_VERSION
                                 + " (forced by FLIX_BACKEND=wrapper; compiler " + lock.version()
                                 + " also implements it)");
            else routingNotice(first, lock.version());
            wrapperVerb(first, forward.subList(1, forward.size()), root, lock, jar, jvm, compilerVerbs);
            return;
        }
        if (first != null && WRAPPER_VERBS.contains(first) && compilerVerbs.contains(first))
            System.err.println("flix: note: compiler " + lock.version() + " now implements "
                             + q(first) + "; the wrapper implementation is deprecated"
                             + " and will be removed in the next wrapper release");

        launch(jvm.exe(), opts, jar, forward);
    }

    static void routingNotice(String verb, String compilerVersion) {
        System.err.println("flix: " + q(verb) + " \u2192 wrapper " + WRAPPER_VERSION
                         + " (pinned compiler " + compilerVersion + " does not implement it)");
    }

    static void wrapperHelp() {
        System.out.println("flixw " + WRAPPER_VERSION + " -- repository-local Flix bootstrap");
        System.out.println();
        System.out.println("  ./flix help | --help            this table, then the compiler's own help");
        System.out.println("  ./flix <compiler verb> [args]   run the pinned stock compiler");
        System.out.println("  ./flix -- <args>                forced compiler pass-through");
        System.out.println("  ./flix pin [<owner>/<repo>] <version>   repin flix.toml and the lock");
        System.out.println("  ./flix doctor | setup | validate");
        System.out.println("  ./flix wrapper [--help | --version | --upgrade | --install-jdk]");

        System.out.println();
        System.out.println("cache            " + cacheHome());
        System.out.println("java             " + System.getProperty("java.home")
                         + "  (" + Runtime.version().feature() + ")");
        // Offline-only enrichment: never downloads, never launches the compiler.  The
        // routing table is shown from what is already on disk, so --wrapper-help keeps
        // working on a cold clone and while the project is drifted.
        Path root = null;
        try { root = findRoot(wrapperAnchor()); } catch (Fail ignored) { }
        if (root == null) {
            System.out.println("project          (none found; run inside a project for the routing table)");
            return;
        }
        System.out.println("project root     " + root);
        Lock lock;
        try { lock = readLock(lockPath(root)); } catch (Fail f) {
            System.out.println("lock             " + f.getMessage().split("\n")[0]); return;
        }
        System.out.println("compiler         " + lock.version() + "  " + lock.sha256());
        Path vf = verbsFile(compilerPath(lock), lock.sha256());
        List<String> cv = null;
        if (Files.isRegularFile(vf)) {
            try {
                cv = new ArrayList<>(Files.readAllLines(vf));
                cv.removeIf(String::isBlank);
            } catch (IOException ignored) {}
        }
        System.out.println("compiler verbs   " + (cv == null
            ? "(not captured yet; run any compiler verb once)" : String.join(" ", cv)));
        List<String> fb = new ArrayList<>(WRAPPER_VERBS);
        if (cv != null) fb.removeAll(cv);
        System.out.println("wrapper verbs    " + String.join(" ", fb));
        System.out.println("pass-through     ./flix -- <args>");
    }

    static Path selfSource() {
        String s = env("FLIXW_SOURCE");
        if (s != null) return Paths.get(s);
        try {
            Path loc = Paths.get(flix.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(loc) && loc.toString().endsWith(".java")) return loc;
            Path p = loc.resolve("source.path");                      // compiled stage 0
            if (Files.isRegularFile(p)) return Paths.get(Files.readString(p).trim());
        } catch (Exception ignored) { }
        return null;
    }

    /** At most one relaunch, guarded by an env marker, so a stale release file cannot loop. */
    static boolean relaunch(Jvm jvm, List<String> argv) {
        Path cur = ProcessHandle.current().info().command().map(Paths::get).orElse(null);
        if (cur != null && cur.equals(jvm.exe())) return false;
        if (env("FLIXW_RELAUNCHED") != null) return false;
        Path src = selfSource();
        if (src == null) return false;
        List<String> cmd = new ArrayList<>(List.of(jvm.exe().toString(), src.toString()));
        cmd.addAll(argv);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
            pb.environment().put("FLIXW_RELAUNCHED", "1");
            System.exit(awaitWithReaper(pb.start()));
        } catch (IOException | InterruptedException e) {
            throw w004("relaunch under " + jvm.exe() + " failed: " + e.getMessage());
        }
        return true;
    }

    /**
     * Waits for a child that owns the terminal, and guarantees it dies with us.
     *
     * Java has no exec(2): stage 0 must stay resident for the child's whole life.  The
     * child keeps the terminal, so SIGINT reaches it through the foreground process group.
     * The hook covers the rest: without it, a SIGTERM to stage 0 orphans a compiler that
     * then runs forever.  SIGKILL still orphans it -- no Java code can prevent that, and
     * the README says so.
     *
     * The relaunch path shares this.  It used to wait bare, so terminating a stage 0 that
     * had relaunched itself into another JVM orphaned the entire subtree beneath it --
     * the same defect the compiler launch had a hook for, one process further down.
     */
    static int awaitWithReaper(Process p) throws InterruptedException {
        Thread hook = new Thread(() -> {
            if (!p.isAlive()) return;
            // Descendants are collected before anything is destroyed, and destroyed too.
            // Stage 0 may have relaunched itself under a different JVM, which puts a
            // process between this one and the compiler; relying on that middle JVM to
            // run its own hook in time is a race, and destroying p first would reparent
            // whatever it started, at which point it is no longer in p.descendants().
            List<ProcessHandle> below = p.descendants().toList();
            p.destroy();
            below.forEach(ProcessHandle::destroy);
            try {
                // Asking is not the same as making it happen. A compiler that traps or
                // ignores TERM would otherwise outlive the wrapper that started it, which
                // is the whole failure this hook exists to prevent.
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            below.forEach(h -> { if (h.isAlive()) h.destroyForcibly(); });
        }, "flixw-reaper");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return p.waitFor();
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(hook); }
            catch (IllegalStateException ignored) { }   // already shutting down
        }
    }

    /** Inherit cwd and the three streams; propagate the child's status. */
    static void launch(Path javaExe, List<String> opts, Path jar, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(opts);
        cmd.add("-jar"); cmd.add(jar.toString());
        cmd.addAll(args);
        tr("exec " + String.join(" ", cmd));
        try {
            System.exit(awaitWithReaper(new ProcessBuilder(cmd).inheritIO().start()));
        } catch (IOException e) {
            throw w005("cannot launch " + jar + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        }
    }
}
