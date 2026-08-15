// flixw stage 0 -- repository-local Flix compiler bootstrap.
//
// GENERATED IN A PROJECT; DO NOT EDIT THERE.  The copy under a project's .flixw/ is
// written by `flixw install` and replaced by `flixw wrapper --upgrade`, and
// `flixw validate` prints its SHA-256 so an altered one is visible against the published
// release.  This file, in the flixw repository, is where it is actually written.
//
// Invoked by the ./flixw shim as:   java .flixw/flixw.java <args>
// or, once self-compiled, as:      java -cp <cache>/stage0/<hash> flixw <args>
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage 0 of the flixw bootstrap: one file, no dependencies, Java 21.
 *
 * <p>It owns project discovery, lock parsing, drift detection, version validation, Java
 * selection, compiler acquisition, unconditional digest verification, compiler-first verb
 * dispatch, the wrapper's own verbs, and the process launch. The two shims that reach it,
 * {@code flixw} and {@code flixw.cmd}, own exactly one decision each -- which {@code java}
 * -- plus one cache lookup, because logic in a shim has to be written twice and cannot be
 * unit-tested.
 *
 * <p>The stock Flix compiler is never modified, patched, or linked against. It is fetched
 * by URL, verified against a SHA-256 committed in {@code .flixw/lock.toml}, and executed
 * as an opaque process. The digest is recomputed on every invocation: there is no install
 * stamp and no flag that skips it.
 *
 * <p>These docs are published from the flixw repository and cover every member, private
 * ones included, because the internals are what a reader has to trust before letting this
 * file download and run a compiler. {@code docs/CONTRACT.md} is the description of what
 * ships and what is promised; this is how it is done.
 *
 * @see <a href="https://wstein.github.io/flixw/">flixw documentation</a>
 */
public final class flixw {

    static final String WRAPPER_VERSION = "0.21.0";
    static final String WRAPPER_DIR = ".flixw";
    static final int MIN_JAVA = 21;
    /**
     * The oldest javac that can compile this file, which is a different number from the
     * floor above and answers a different question. MIN_JAVA is what the *compiler* needs;
     * this is what *stage 0* needs, and between the two lies the range where flixw runs,
     * says the pinned Flix will not, and can fetch a JDK that will. Below it flixw cannot
     * speak at all -- which is why the no-java diagnostic does not offer to install one.
     * `tests/lint.sh` compiles this file with --release SOURCE_FLOOR so the number cannot
     * quietly drift when a newer language feature is used.
     */
    static final int SOURCE_FLOOR = 16;

    /**
     * The interval flixw is tested on.  Above the ceiling is a warning, not an error.
     *
     * The number means the suite has actually been run there, so it moves when that is
     * done and not when a JDK is released: `.github/workflows/ci.yaml` runs the whole
     * suite on the ceiling as well as on MIN_JAVA, which is what keeps the claim true
     * rather than aspirational.
     */
    static final int TESTED_CEILING = 26;

    /**
     * Bounds for the two child processes stage 0 runs for information rather than for
     * work.  Both are generous: exceeding one means the child is wedged, not slow.
     */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    static final Duration HELP_TIMEOUT = Duration.ofSeconds(30);
    static final int HELP_CAP = 1 << 20;

    static final List<String> WRAPPER_VERBS =
        List.of("pin", "info", "doctor", "validate", "help");

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
     * error that `./flixw pin` cannot repair.
     */
    static String canonical(String v) { int i = v.indexOf('+'); return i < 0 ? v : v.substring(0, i); }

    /**
     * The `x.x.x` that `[package].flix` is allowed to hold.
     *
     * That field is Flix's, not flixw's, and Flix rejects anything else outright --
     * "This toml file has a Flix version number of the wrong length" for a version
     * carrying build metadata. It also accepts 99.99.99 against a 0.75.2 compiler, so it
     * reads as a coarse floor rather than a pin.
     *
     * The exact version therefore lives in the lock, which is flixw's own file and can say
     * `0.75.2+fork.wstein.260807.1` without breaking anything. Drift compares the two at
     * this precision, because that is all the manifest is able to express.
     */
    static String triple(String v) {
        Matcher m = Pattern.compile("^([0-9]+\\.[0-9]+\\.[0-9]+)").matcher(v);
        return m.find() ? m.group(1) : v;
    }

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

    // ---- the lock schema --------------------------------------------------

    /**
     * The lock format's major version, which is not the wrapper's. It changes only when a
     * lock this stage 0 writes would stop being readable under the rules below; adding an
     * optional key is not such a change, and does not move it.
     */
    static final String LOCK_SCHEMA_VERSION = "v1";

    /** Where the generated documentation and the JSON Schema are published. */
    static final String PAGES_BASE = "https://wstein.github.io/flixw/";

    /**
     * The URL written into every generated lock as a `#:schema` directive, and the `$id`
     * of the schema itself. Taplo and Even Better TOML read that directive, so an editor
     * validates the lock with no per-project configuration.
     */
    static final String LOCK_SCHEMA_URL =
        PAGES_BASE + "schema/lock-" + LOCK_SCHEMA_VERSION + ".schema.json";

    /** GitHub's own limits on the two path segments; a fork may live anywhere within them. */
    static final String REPO_PATTERN = "[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,100}";

    /** A feature release or an exact one, and nothing else -- no ranges, no vendor. */
    static final String JAVA_PIN_PATTERN = "[0-9]+(\\.[0-9]+)*";

    /**
     * One key in lock.toml: the table it lives in, whether that table may omit it, the
     * shape its value must have, and the sentence a diagnostic uses to describe it.
     *
     * The lock's shape was previously stated in three places -- {@link #lockText} wrote it,
     * {@link #readLock} read it, and the documentation described it -- with nothing keeping
     * them in step, and a published JSON Schema would have been a fourth. So it is stated
     * once here, and the writer, the reader and the schema are all derived from this list.
     *
     * {@code pattern} is deliberately written in the intersection of Java's regex dialect
     * and ECMA-262's: it is compiled by {@code String.matches} on every run, and by
     * whatever JSON Schema validator reads the published file. It carries no anchors,
     * because Java implies them and JSON Schema does not.
     */
    record LockField(String table, String key, boolean required, String pattern, String what) {
        /** How a diagnostic names this key: `[compiler] sha256`, or a bare key at the root. */
        String name() { return table.isEmpty() ? key : "[" + table + "] " + key; }
    }

    /**
     * Every key a lock may hold, in the order a generated lock writes them.
     *
     * {@code required} means required when the table it sits in is present, which is why
     * `[java] version` is optional: a project that does not care which JDK runs the
     * compiler omits the table entirely, and an empty one means the same thing.
     */
    static final List<LockField> LOCK_SCHEMA = List.of(
        new LockField("", "wrapperVersion", false, SEMVERISH.pattern(),
            "the flixw release that last wrote this lock"),
        new LockField("compiler", "repo", false, REPO_PATTERN,
            "the owner/repository the compiler was fetched from"),
        new LockField("compiler", "version", true, SEMVERISH.pattern(),
            "the exact compiler version: x.y.z, optionally with a prerelease and build metadata"),
        new LockField("compiler", "url", true, "https://[^\\s]+",
            "the https URL the compiler JAR is downloaded from"),
        new LockField("compiler", "sha256", true, "[0-9a-f]{64}",
            "the SHA-256 of that JAR: 64 lowercase hex digits"),
        new LockField("java", "version", false, JAVA_PIN_PATTERN,
            "the Java that runs the compiler: a feature release (21) or an exact one (21.0.12)"));

    /** The tables the schema knows about, deduplicated, in lock order. The root is "". */
    static List<String> lockTables() {
        List<String> out = new ArrayList<>();
        for (LockField f : LOCK_SCHEMA) if (!out.contains(f.table())) out.add(f.table());
        return out;
    }

    /**
     * The published JSON Schema for lock.toml, rendered from {@link #LOCK_SCHEMA}.
     *
     * Generated rather than hand-written for the reason the shims are compared byte for
     * byte: a schema describing a lock this wrapper no longer writes is worse than no
     * schema at all, because an editor presents it as authority. `tests/lint.sh` diffs
     * this against the copy in `docs/schema/`, so the published file cannot drift from the
     * code that writes the file it describes.
     *
     * Hand-rolled rather than serialised by a library, because stage 0 has no
     * dependencies. The only values interpolated are ours, and {@link #jsonString} escapes
     * them anyway -- the patterns are full of backslashes.
     */
    static String lockSchemaJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\",\n");
        b.append("  \"$id\": ").append(jsonString(LOCK_SCHEMA_URL)).append(",\n");
        b.append("  \"title\": \"flixw lock.toml\",\n");
        b.append("  \"description\": ").append(jsonString(
            "The pin written by `./flixw pin`: the repository, exact version, distribution"
          + " URL and SHA-256 of the Flix compiler a project runs. Generated and verified by"
          + " flixw; committed, and not edited by hand.")).append(",\n");
        b.append("  \"type\": \"object\",\n");
        b.append("  \"additionalProperties\": false,\n");

        List<String> tables = lockTables();
        List<String> rootRequired = new ArrayList<>();
        for (String t : tables)
            if (!t.isEmpty() && lockFields(t).stream().anyMatch(LockField::required))
                rootRequired.add(t);
        b.append("  \"required\": ").append(jsonArray(rootRequired)).append(",\n");

        b.append("  \"properties\": {\n");
        List<String> props = new ArrayList<>();
        for (String t : tables) {
            if (t.isEmpty()) { for (LockField f : lockFields(t)) props.add(fieldJson(f, "    ")); }
            else props.add(tableJson(t, "    "));
        }
        b.append(String.join(",\n", props)).append("\n");
        b.append("  }\n");
        b.append("}\n");
        return b.toString();
    }

    /** The fields declared for one table, in lock order. */
    static List<LockField> lockFields(String table) {
        List<LockField> out = new ArrayList<>();
        for (LockField f : LOCK_SCHEMA) if (f.table().equals(table)) out.add(f);
        return out;
    }

    static String fieldJson(LockField f, String indent) {
        return indent + jsonString(f.key()) + ": {\n"
             + indent + "  \"type\": \"string\",\n"
             + indent + "  \"description\": " + jsonString(f.what()) + ",\n"
             + indent + "  \"pattern\": " + jsonString("^" + f.pattern() + "$") + "\n"
             + indent + "}";
    }

    static String tableJson(String table, String indent) {
        List<LockField> fields = lockFields(table);
        List<String> required = new ArrayList<>();
        for (LockField f : fields) if (f.required()) required.add(f.key());
        List<String> props = new ArrayList<>();
        for (LockField f : fields) props.add(fieldJson(f, indent + "    "));
        // An empty "required" is legal and says nothing; [java] has no mandatory key
        // because an empty table means exactly what an absent one does.
        return indent + jsonString(table) + ": {\n"
             + indent + "  \"type\": \"object\",\n"
             + indent + "  \"additionalProperties\": false,\n"
             + (required.isEmpty() ? ""
                : indent + "  \"required\": " + jsonArray(required) + ",\n")
             + indent + "  \"properties\": {\n"
             + String.join(",\n", props) + "\n"
             + indent + "  }\n"
             + indent + "}";
    }

    static String jsonArray(List<String> items) {
        List<String> quoted = new ArrayList<>();
        for (String s : items) quoted.add(jsonString(s));
        return quoted.isEmpty() ? "[]" : "[" + String.join(", ", quoted) + "]";
    }

    /** JSON string literal. Only the escapes RFC 8259 requires; every value here is ASCII. */
    static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    // ---- lock and manifest ------------------------------------------------

    record Lock(String version, String url, String sha256, String repo, String java) {}

    /**
     * One `key = value` occurrence, the table it was found in, and the line it sits on.
     * `value` is the raw right-hand side; `multiline` marks a `"""` or `'''` opener, whose
     * body this scanner deliberately does not reassemble -- no key flixw reads is one.
     */
    record TomlEntry(int line, String table, String key, String value, boolean multiline) {}

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
        int arrayDepth = 0;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Inside a value that spans lines as an array, nothing is a key. A line-based
            // reader took an authors entry holding `flix = "9.9.9"` for an assignment, and
            // an unbalanced quote in one made the whole manifest unreadable -- a legal file
            // this wrapper simply refused to work with. Depth counts brackets outside
            // quotes, so a bracket inside a string stays text.
            if (arrayDepth > 0) {
                arrayDepth += bracketDelta(line);
                continue;
            }
            if (mlDelim != null) {                       // inside """ or ''': find the close
                int e = line.indexOf(mlDelim);
                if (e < 0) continue;
                line = line.substring(e + 3);            // three chars either way
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
                current = String.join(".", splitKey(t.substring(1, close), where));
                tables.add(current);
                continue;
            }
            int eq = t.indexOf('=');
            if (eq < 0) continue;
            // A dotted key is a table path, and TOML lets it be written with spaces around
            // the dots and with any segment quoted -- `package . flix`, `package."flix"`
            // and `"package".flix` all mean [package].flix. Matching the raw text meant
            // only the tightest spelling was seen, so a manifest could state a floor this
            // scanner did not read: the check passed, and an older compiler ran.
            List<String> path = splitKey(t.substring(0, eq), where);
            String k = path.get(path.size() - 1);
            String tbl = current;
            if (path.size() > 1) {
                String prefix = String.join(".", path.subList(0, path.size() - 1));
                tbl = current.isEmpty() ? prefix : current + "." + prefix;
            }
            String v = t.substring(eq + 1).trim();
            String delim = v.startsWith("\"\"\"") ? "\"\"\"" : v.startsWith("'''") ? "'''" : null;
            if (delim != null && !v.substring(3).contains(delim)) mlDelim = delim;
            else if (delim == null) arrayDepth = Math.max(0, bracketDelta(v));
            entries.add(new TomlEntry(i, tbl, k, v, delim != null));
        }
        return new TomlScan(entries, tables);
    }

    /**
     * True when an entry is `table.key`. Dotted keys are resolved to their table by
     * {@link #tomlScan}, so both spellings arrive here already in the same shape.
     */
    static boolean isKey(TomlEntry e, String table, String key) {
        return e.table().equals(table) && e.key().equals(key);
    }

    /**
     * Splits a key into its segments, respecting quotes, then unquotes and trims each one.
     * `a.b` is two segments; `"a.b"` is one. Fails closed: an unterminated quote or an
     * empty segment is a manifest this scanner will not guess at.
     */
    static List<String> splitKey(String raw, String where) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) { cur.append(c); if (c == quote) quote = 0; }
            else if (c == '"' || c == '\'') { quote = c; cur.append(c); }
            else if (c == '.') { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (quote != 0) throw w002(where + ": unterminated quoted key " + q(raw.trim()));
        parts.add(cur.toString());
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String seg = unquote(part.trim());
            if (seg.isEmpty()) throw w002(where + ": empty key segment in " + q(raw.trim()));
            out.add(seg);
        }
        return out;
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

    /**
     * How much this line opens or closes an inline array, counting only brackets outside
     * quotes. Used to skip a value that spans lines; it never goes below zero, because a
     * stray closing bracket is not this scanner's business to diagnose.
     */
    static int bracketDelta(String line) {
        String t = stripComment(line);
        int depth = 0;
        boolean sq = false, dq = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\'' && !dq) sq = !sq;
            else if (c == '"' && !sq) dq = !dq;
            else if (!sq && !dq) {
                if (c == '[') depth++;
                else if (c == ']') depth--;
            }
        }
        return depth;
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
                     + "\n       run: ./flixw pin <version>");
        }
        String w = lockFile.toString();
        Map<String, String> got = readLockFields(text, w);
        noteUnknownLockKeys(text, w, got.get("wrapperVersion"));
        String u = got.get("compiler.url");
        String j = got.get("java.version");
        // What a pattern cannot say. The schema has already accepted both values as
        // well-formed; these are the checks that need more than their shape -- that the
        // URL names a host and does not climb out of its path, and that the java pin is
        // one the compiler can actually run under.
        validateUrl(u, w);
        if (j != null) validateJavaPin(j, w);
        // repo is absent in locks written before forks were supported, and means the stock
        // repository. java is absent whenever a project does not care which JDK it gets.
        return new Lock(got.get("compiler.version"), u, got.get("compiler.sha256"),
                        got.get("compiler.repo"), j);
    }

    /**
     * Reads every key {@link #LOCK_SCHEMA} declares, keyed as `table.key` with the root
     * table's keys unprefixed. Absent optional keys are simply not in the map.
     *
     * Presence and shape are both checked here, from the same list the published JSON
     * Schema is rendered from, so a lock an editor flags is a lock flixw refuses -- and
     * the diagnostic can say what the key is *for* rather than quoting a regex at someone.
     */
    static Map<String, String> readLockFields(String text, String where) {
        Map<String, String> got = new LinkedHashMap<>();
        for (LockField f : LOCK_SCHEMA) {
            String v = tomlLookup(text, f.table(), f.key(), where);
            if (v == null) {
                if (!f.required()) continue;
                throw w002(where + ": missing " + f.name() + " -- " + f.what()
                         + "\n       run: ./flixw pin <version>");
            }
            if (!v.matches(f.pattern()))
                throw w002(where + ": " + f.name() + " is " + q(v)
                         + "\n       expected " + f.what()
                         + "\n       run: ./flixw pin <version>");
            got.put(f.table().isEmpty() ? f.key() : f.table() + "." + f.key(), v);
        }
        return got;
    }

    /**
     * Keys the schema does not describe, reported once and never fatally.
     *
     * Advisory because the ordinary way to meet one is a lock written by a newer flixw,
     * and refusing to run would make such a project unbuildable by every collaborator who
     * had not upgraded yet -- the lock is committed, so that is most of them. Silence is
     * the wrong answer too: a mistyped key is otherwise invisible, and the value someone
     * believed they had set is simply never read.
     *
     * A lock that says it was written by a newer flixw gets no note at all, because there
     * the unknown key is expected and the message would be wrong as well as noisy.
     */
    static void noteUnknownLockKeys(String text, String where, String wroteIt) {
        // A run reads the lock more than once by design -- `doctor` reads it, then reads
        // it again to decide whether to rewrite it -- and an advisory said twice reads as
        // two problems. Once per file per run is what "reported once" means.
        if (!NOTED_LOCKS.add(where)) return;
        if (wroteIt != null && !olderOrSame(wroteIt, WRAPPER_VERSION)) return;
        List<String> unknown = unknownLockKeys(text, where);
        if (unknown.isEmpty()) return;
        w011(where + ": " + String.join(", ", unknown)
           + (unknown.size() == 1 ? " is not a key flixw reads, and is ignored"
                                  : " are not keys flixw reads, and are ignored")
           + "\n         the keys a lock may hold: " + LOCK_SCHEMA_URL);
    }

    /** Locks already reported on, so a second read in the same run stays quiet. */
    static final Set<String> NOTED_LOCKS = new LinkedHashSet<>();

    /**
     * Every key in the file that {@link #LOCK_SCHEMA} does not describe, named the way a
     * diagnostic names it, in file order and without repeats.
     *
     * Separate from the note because `doctor --fix` asks the same question for the
     * opposite reason: it regenerates the lock from the values it read, which would
     * *delete* any key it did not read.
     */
    static List<String> unknownLockKeys(String text, String where) {
        List<String> unknown = new ArrayList<>();
        for (TomlEntry e : tomlScan(text, where).entries()) {
            boolean known = false;
            for (LockField f : LOCK_SCHEMA)
                if (isKey(e, f.table(), f.key())) { known = true; break; }
            String name = e.table().isEmpty() ? e.key() : "[" + e.table() + "] " + e.key();
            if (!known && !unknown.contains(name)) unknown.add(name);
        }
        return unknown;
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

    /**
     * One usage line for `pin`, because four diagnostics quote it and the fourth was
     * already a release behind the first the last time one was written out by hand.
     */
    static final String PIN_USAGE =
          "usage: ./flixw pin [<owner>/<repo>] [<version>] [--java <version>]"
        + "\n          or: ./flixw pin --refresh   (rewrite the lock in this release's shape)";

    /** One release asset: what to fetch, and what the publisher says it hashes to. */
    record Asset(String name, String url) {}

    static String checkRepo(String repo, String where) {
        if (!repo.matches(REPO_PATTERN))
            throw w002(where + ": " + q(repo) + " is not an owner/repository");
        return repo;
    }

    /** A release tag in a URL path. Only '+' needs it; the rest of a version is path-safe. */
    static String encodeTag(String tag) { return tag.replace("+", "%2B"); }

    /**
     * Resolves the compiler artifact for one repository and version, without asking an
     * API anything.
     *
     * The GitHub API answered this in one call and threw in a digest, and it was the
     * wrong tool: unauthenticated it allows sixty requests an hour across everything on
     * the machine, so `pin` failed with HTTP 403 for a tag that plainly existed, and the
     * error blamed the tag. Release *downloads* carry no such limit, so the asset name --
     * the only thing that was ever unknown -- is found by asking for the file itself.
     *
     * Upstream is a single constructed URL, as before. A fork is probed against the two
     * conventions in the wild, {@code flix-<version>.jar} and `flix.jar`, with a HEAD each; the
     * download that follows is still exactly one acquisition attempt for one artifact.
     */
    static Asset resolveRelease(String repo, String version) {
        if (repo.equals(UPSTREAM_REPO)) {
            String u = "https://github.com/" + UPSTREAM_REPO + "/releases/download/v"
                     + canonical(version) + "/flix.jar";
            return new Asset("flix.jar", u);
        }
        String base = "https://github.com/" + repo + "/releases/download/"
                    + encodeTag("v" + version) + "/";
        List<String> tried = new ArrayList<>();
        for (String name : List.of("flix-" + version + ".jar", "flix.jar")) {
            String u = base + encodeTag(name);
            tried.add(u);
            if (assetExists(u)) {
                validateUrl(u, repo + " release v" + version);
                return new Asset(name, u);
            }
        }
        throw w005("no compiler jar found in " + repo + " release " + q("v" + version)
                 + "\n       tried " + String.join("\n             ", tried)
                 + "\n       the version must match the tag exactly, build metadata included");
    }

    /**
     * The one HTTP client, pinned to HTTP/1.1.
     *
     * Every request flixw makes is a single one-shot HEAD or GET, so HTTP/2 buys nothing
     * here -- there are no concurrent streams to multiplex onto one connection -- and it
     * costs a failure mode that only shows up as a red CI run. When a server sends GOAWAY
     * while a stream is being opened, the JDK client raises `request not processed by
     * peer`; because acquisition is one attempt with no retry loop, that lands on the user
     * as a failed download and, through the missing lock, as fifteen further failures.
     * That is a real observation, not a theoretical one: it took out the whole windows
     * smoke job on ccba32b while ubuntu and macos passed the same commit.
     *
     * Pinning 1.1 deletes the race rather than retrying around it, which is the trade this
     * project already makes everywhere else -- a retry would have to be bounded, logged
     * and explained, and would still leave the request that *was* processed ambiguous.
     */
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** Does this release asset exist? A HEAD, so the download itself stays a single attempt. */
    static boolean assetExists(String url) {
        HttpClient client = httpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "flixw/" + WRAPPER_VERSION).build();
        try {
            HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 200 && "https".equals(res.uri().getScheme());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    /** What one `pin` command line asks for; `parsePin` is the only thing that builds it. */
    record Pin(String repo, String version, String java, boolean clearJava, boolean refresh) {}

    /**
     * {@code ./flixw pin [<owner>/<repo>] [<version>] [--java <version>]}, or
     * {@code ./flixw pin --refresh}.
     *
     * The two are told apart by the slash, which a version can never contain -- the
     * grammar rejects it -- so the order does not matter and neither does a flag.  An
     * omitted repository means the one already in the lock, so re-pinning a project that
     * tracks a fork stays on that fork: rebuilding the upstream URL every time silently
     * moved such a project back to stock, and because both are honestly version 0.75.2,
     * nothing about it looked wrong.
     */
    static Pin parsePin(List<String> args, Lock existing) {
        String repo = null, version = null, java = null, clearJava = null;
        boolean repoGiven = false, refresh = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--java")) {
                if (java != null || clearJava != null) throw w009("pin: two --java values given");
                if (i + 1 >= args.size())
                    throw w002("pin: --java needs a version\n       for example:"
                             + " ./flixw pin --java " + MIN_JAVA + "   (or --java none)");
                String v = args.get(++i);
                if (v.equals("none")) clearJava = "yes"; else { validateJavaPin(v, "pin"); java = v; }
            } else if (a.equals("--refresh")) {
                refresh = true;
            } else if (a.startsWith("--")) {
                throw w008("pin: unknown option " + q(a) + "\n       " + PIN_USAGE);
            } else if (a.contains("/")) {
                if (repo != null) throw w009("pin: two repositories given");
                repo = checkRepo(a, "pin");
                repoGiven = true;
            } else {
                if (version != null) throw w009("pin: two versions given");
                version = a;
            }
        }
        if (refresh) {
            // --refresh rewrites the lock from the lock. Everything else on this line
            // changes what the lock says, and doing one of the two silently is how a
            // repair loses the pin it was asked to preserve.
            if (version != null || repoGiven || java != null || clearJava != null)
                throw w008("pin: --refresh takes no other arguments -- it rewrites the lock"
                         + " in the shape flixw " + WRAPPER_VERSION + " writes,"
                         + "\n       from the values already in it, without moving the pin"
                         + "\n       " + PIN_USAGE);
            if (existing == null)
                throw w002("pin: --refresh needs a lock that parses"
                         + "\n       run: ./flixw pin <version>");
            return new Pin(null, null, null, false, true);
        }
        // A compiler version is required unless this is only a Java pin, in which case
        // the compiler stays exactly as it was -- rewriting the lock is not repinning it.
        if (version == null && java == null && clearJava == null)
            throw w002("pin: no version\n       " + PIN_USAGE);
        // Naming a repository without a version was accepted and then quietly dropped: a
        // --java-only pin rewrites one line and does not re-resolve the compiler, so the
        // repository had nowhere to go. Changing where the compiler comes from means
        // fetching it, which means saying which version to fetch.
        if (version == null && repoGiven)
            throw w002("pin: a repository needs a version -- changing it means fetching"
                     + " that compiler\n       for example: ./flixw pin " + repo
                     + " <version> --java " + (java == null ? MIN_JAVA + "" : java));
        if (version == null && existing == null)
            throw w002("pin: --java needs an existing lock, or a compiler version to write"
                     + " one\n       for example: ./flixw pin 0.75.2 --java " + MIN_JAVA);
        if (version != null) validateVersion(version, "pin");
        if (repo == null) repo = existing != null && existing.repo() != null
                               ? existing.repo() : UPSTREAM_REPO;
        return new Pin(repo, version, java, clearJava != null, false);
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
        HttpClient client = httpClient();
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
            // Unless something the child started inherited the pipe and outlived it, in
            // which case there is no EOF coming and the reader is parked on a descendant
            // nobody is waiting for. Closing this end makes that read fail, which is the
            // only way back: the handle belongs to a process this one no longer owns.
            if (reader.isAlive()) {
                try { p.getInputStream().close(); } catch (IOException ignored) { }
                reader.join(1000);
            }
            return box[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            // A probe must never outlive the probe. Killing the child alone is not enough:
            // anything it started is reparented and keeps running, and the reader thread
            // would otherwise be left parked on a pipe nobody will close.
            // Descendants first, and outside the isAlive guard, because the guard also
            // skipped the kill for a child that was killed a line earlier. What this
            // cannot do is reach a grandchild of a child that exited on its own: it was
            // reparented away and is nobody's descendant now. That case is survivable
            // only because the pipe is closed above -- stage 0 stops waiting on it, and
            // an orphan holding a closed pipe is the operating system's problem, not a
            // probe that never returns.
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            if (p.isAlive()) {
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

    /** Reads {@code <home>/release} when present and parseable; else runs the candidate once. */
    static int probe(Path exe) {
        Integer f = feature(probeVersion(exe));
        return f == null ? -1 : f;
    }

    /**
     * The candidate's own version string -- `21.0.12` rather than `21` -- so a pin can be
     * as exact as the person who wrote it chose to be. Same two sources as probe(), in the
     * same order and for the same reasons: the release file costs one read, and executing
     * the candidate is the fallback for a java that is not laid out like a JDK.
     */
    static String probeVersion(Path exe) {
        Path home = exe.getParent() == null ? null : exe.getParent().getParent();
        if (home != null) {
            Path rel = home.resolve("release");
            if (Files.isRegularFile(rel)) {
                try {
                    String t = Files.readString(rel, StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("(?m)^JAVA_VERSION=\"([^\"]+)\"").matcher(t);
                    if (m.find() && feature(m.group(1)) != null) return m.group(1);
                } catch (IOException ignored) { }
            }
        }
        try {                                                     // one execution, no retry
            // Bounded: a candidate java that hangs on startup -- a broken installation, a
            // stalled network filesystem -- must cost this probe a timeout, not the run.
            String out = runCapture(List.of(exe.toString(), "-XshowSettings:properties", "-version"),
                                    PROBE_TIMEOUT, 1 << 18);
            if (out != null) {
                // java.version, not java.specification.version: the latter is "21" and
                // says nothing a pin like 21.0.12 could be checked against.
                Matcher m = Pattern.compile("(?m)^\\s*java\\.version = ([0-9][0-9.+_-]*)").matcher(out);
                if (m.find() && feature(m.group(1)) != null) return m.group(1);
                m = Pattern.compile("java\\.specification\\.version = ([0-9.]+)").matcher(out);
                if (m.find() && feature(m.group(1)) != null) return m.group(1);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Does this JDK satisfy `[java] version` in the lock?
     *
     * A pin is a prefix of the version, cut at a dot: `21` accepts 21.0.12, `21.0` accepts
     * 21.0.12, and neither accepts 21.1 or 2. Prefix rather than equality because the
     * useful pin is nearly always "this feature release", and the exact one is available
     * to whoever wants it by writing more of the number. Vendor is deliberately not part
     * of it -- the pin says which Java the project needs, not whose.
     */
    static boolean satisfiesJavaPin(String pin, String version) {
        if (pin == null) return true;
        if (version == null) return false;
        if (version.equals(pin)) return true;
        return version.startsWith(pin) && version.charAt(pin.length()) == '.';
    }

    /**
     * A pin is a dotted number and nothing else: no ranges, no `latest`, no vendor. It must
     * also be a Java the pinned compiler can actually run under, so a pin below MIN_JAVA is
     * refused at the point it is written rather than at every run afterwards.
     */
    static void validateJavaPin(String v, String where) {
        if (!v.matches(JAVA_PIN_PATTERN))
            throw w002(where + ": java version " + q(v) + " is not a dotted number"
                     + "\n       write a feature release (21) or an exact one (21.0.12)");
        Integer f = feature(v);
        if (f == null || f < MIN_JAVA)
            throw w002(where + ": java " + q(v) + " is below Java " + MIN_JAVA
                     + ", which the compiler needs");
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

    static Jvm selectJava(String pin) {
        for (String var : new String[] { "FLIX_JAVA_HOME", "JAVA_HOME" }) {
            String h = env(var);
            if (h == null) continue;
            Path exe = exeIn(h);
            if (!Files.isRegularFile(exe))
                throw w004(var + "=" + h + " has no " + exe.getFileName() + " at " + exe);
            if (!Files.isExecutable(exe))
                throw w004(var + "=" + h + ": " + exe + " is not executable");
            String full = probeVersion(exe);
            int f = probe(exe);
            if (!acceptable(f, var))
                throw w004(var + "=" + h + " is Java " + (f < 0 ? "unidentifiable" : f)
                         + "; flixw needs [" + MIN_JAVA + ", " + TESTED_CEILING + "]"
                         + (strictJava() ? " (FLIXW_STRICT_JAVA is set)" : ""));
            // An explicitly named JDK is still obeyed rather than replaced -- but not
            // quietly against a pin the project committed. Saying which two things
            // disagree is the whole job here; picking a winner is not.
            if (!satisfiesJavaPin(pin, full))
                throw w004(var + "=" + h + " is Java " + (full == null ? "unidentifiable" : full)
                         + ", but " + WRAPPER_DIR + "/lock.toml pins java " + pin
                         + "\n       unset " + var + ", or run: ./flixw pin --java "
                         + (full == null ? "<version>" : full));
            return new Jvm(exe, f, var);
        }
        int self = Runtime.version().feature();
        Path selfExe = ProcessHandle.current().info().command()
                .map(Paths::get).orElse(exeIn(System.getProperty("java.home")));
        if (acceptable(self, "running JVM")
            && satisfiesJavaPin(pin, Runtime.version().toString().split("[+-]")[0]))
            return new Jvm(selfExe, self, "running JVM");

        // Every candidate is probed before any is chosen.  probe() reads the JDK's own
        // release file first and only executes a candidate that has none, so this is
        // cheap, and it is reached only when the JVM already running is unusable.
        List<Jvm> found = new ArrayList<>();
        Path mine = installedJdk();
        if (mine != null) {
            int f = probe(mine);
            if (f >= MIN_JAVA && satisfiesJavaPin(pin, probeVersion(mine)))
                found.add(new Jvm(mine, f, "installed by flixw"));
        }
        for (Path cand : knownInstalls()) {
            int f = probe(cand);
            if (f >= MIN_JAVA && satisfiesJavaPin(pin, probeVersion(cand)))
                found.add(new Jvm(cand, f, "known installation"));
        }
        Jvm pick = chooseInstall(found, strictJava());
        if (pick != null) return pick;
        return noJavaFound(self, pin);
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
            // Chocolatey nests one level deeper than everyone else -- lib\<package>\tools
            // -- and puts the JDK either in tools itself or in a single directory under
            // it. Expanding each package's tools directory into a root keeps the search
            // one shape rather than two, since the loop below tries every root as a home
            // as well as listing it.
            String progData = env("ProgramData");
            if (progData != null) {
                Path lib = Paths.get(progData, "chocolatey", "lib");
                if (Files.isDirectory(lib)) {
                    try (var s = Files.list(lib)) {
                        s.sorted().map(pkg -> pkg.resolve("tools"))
                                  .filter(Files::isDirectory).forEach(roots::add);
                    } catch (IOException ignored) { }
                }
            }
        }
        // Version managers hold the JDKs of anyone who keeps more than one, and none of
        // them registers with the OS -- which is exactly the case this search exists for.
        for (String vm : new String[] { ".sdkman/candidates/java", ".asdf/installs/java",
                                        ".local/share/mise/installs/java", ".jenv/versions",
                                        ".gradle/jdks" })
            roots.add(Paths.get(home, vm.split("/")));

        String exe = isWindows() ? "java.exe" : "java";
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            // A root that is itself a JDK: chocolatey's tools directory sometimes is one.
            // Everywhere else this costs a single stat that fails.
            Path self = r.resolve("bin").resolve(exe);
            if (Files.isExecutable(self)) out.add(self);
            try (var s = Files.list(r)) {
                s.sorted().forEach(d -> {
                    for (Path h : new Path[] { d, d.resolve("Contents/Home"),
                                               d.resolve("libexec/openjdk.jdk/Contents/Home"),
                                               d.resolve("current") }) {   // scoop's shim
                        Path e = h.resolve("bin").resolve(exe);
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
        HttpClient client = httpClient();
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
    static void jdkInstructions(int want) {
        System.err.println("       install a JDK " + want
                         + (want == MIN_JAVA ? "+" : "") + " and re-run, for example:");
        if (isMac()) {
            System.err.println("         brew install temurin@" + want);
        } else if (isWindows()) {
            System.err.println("         winget install EclipseAdoptium.Temurin." + want + ".JDK");
            System.err.println("         scoop install temurin" + want + "-jdk");
        } else {
            System.err.println("         apt install temurin-" + want + "-jdk         (Debian, Ubuntu)");
            System.err.println("         dnf install temurin-" + want + "-jdk         (Fedora, RHEL)");
            System.err.println("         pacman -S jdk" + want + "-openjdk           (Arch)");
        }
        System.err.println("         or https://adoptium.net/temurin/releases/?version=" + want);
        System.err.println("       then set JAVA_HOME, or put its bin directory on PATH.");
    }

    /**
     * Offers to fetch one only when there is somebody to answer.  A prompt written into a
     * pipe, a CI log or a hook is not a question, it is a hang, so those get the
     * instructions and a failure instead -- and an opt-in they can set once.
     */
    static boolean offerJdk(int want) {
        if (env("FLIXW_INSTALL_JDK") != null) return true;
        if (env("CI") != null || System.console() == null) {
            System.err.println("       or set FLIXW_INSTALL_JDK=1 to let flixw download a"
                             + " verified Temurin " + want + " into its own cache,");
            System.err.println("       or run: ./flixw wrapper --install-jdk");
            return false;
        }
        System.err.print("flixw: download Eclipse Temurin " + want
                       + " into the flixw cache instead? [y/N] ");
        String line = System.console().readLine();
        return line != null && line.strip().toLowerCase(Locale.ROOT).startsWith("y");
    }

    /**
     * Is there a JDK on this machine that satisfies the pin? Asked by `pin` so that writing
     * one is not silently a decision to break the next command. It never offers, downloads
     * or throws: the answer is used for a note, and a pin for a JDK this machine does not
     * have is legitimate -- CI may have it, and `--install-jdk` can fetch it.
     */
    static boolean javaPinAvailable(String pin) {
        if (pin == null) return true;
        if (satisfiesJavaPin(pin, Runtime.version().toString().split("[+-]")[0])) return true;
        Path mine = installedJdk();
        if (mine != null && satisfiesJavaPin(pin, probeVersion(mine))) return true;
        for (Path cand : knownInstalls())
            if (satisfiesJavaPin(pin, probeVersion(cand))) return true;
        return false;
    }

    /** Nothing usable was found: say how to fix it, then offer to do it. */
    static Jvm noJavaFound(int self, String pin) {
        System.err.println(pin == null
            ? "FLIXW003: no Java in [" + MIN_JAVA + ", " + TESTED_CEILING
              + "] found; this JVM is " + self
            : "FLIXW003: no Java " + pin + " found, which " + WRAPPER_DIR
              + "/lock.toml pins; this JVM is " + self);
        // A pinned project gets the pinned feature release, not the wrapper's floor:
        // fetching 21 for a project that asked for 22 installs something that cannot then
        // be selected, and offering it in those words is worse still -- it was the
        // instructions and the prompt that said 21 while the pin said 22.
        int want = pin == null ? MIN_JAVA : feature(pin);
        jdkInstructions(want);
        if (!offerJdk(want)) throw w003("no usable Java; see the instructions above");
        Path exe = installJdk(resolveTemurin(want));
        int f = probe(exe);
        if (f < MIN_JAVA)
            throw w003("the JDK just installed reports Java " + f + ", which is below "
                     + MIN_JAVA + "; install one by hand");
        if (!satisfiesJavaPin(pin, probeVersion(exe)))
            throw w003("the JDK just installed reports Java " + probeVersion(exe)
                     + ", which does not satisfy the pinned java " + pin
                     + "\n       Adoptium publishes feature releases, not every patch;"
                     + " pin the feature release, or install that build by hand");
        System.err.println("flixw: using " + exe);
        System.err.println("       flixw owns this JDK; set JAVA_HOME to it to use it elsewhere.");
        return new Jvm(exe, f, "flixw-installed Temurin");
    }

    /** `./flixw wrapper --install-jdk`, so the choice need not wait for a failure. */
    static void installJdkVerb(List<String> argv) {
        if (argv.size() > 1)
            throw w008(wrapperUsage("'--install-jdk' takes no arguments"));
        // Inside a project that pins a Java, fetch that one: installing the wrapper's
        // floor instead would download a JDK this project is then not allowed to select.
        // Outside one -- this namespace works anywhere -- the floor is the honest answer.
        Integer want = null;
        try {
            Path lf = lockPath(findRoot(wrapperAnchor()));
            if (Files.isRegularFile(lf)) {
                String j = readLock(lf).java();
                if (j != null) want = feature(j);
            }
        } catch (Fail ignored) { }
        Path exe = installJdk(resolveTemurin(want == null ? MIN_JAVA : want));
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
        Path self = sourceLaunchPath();                   // authoritative; see its javadoc
        if (self == null) {
            String src = env("FLIXW_SOURCE");             // set by the self-compiled fast path
            if (src != null) self = Paths.get(src);
            else {
                try {
                    self = Paths.get(flixw.class.getProtectionDomain().getCodeSource()
                                     .getLocation().toURI());
                } catch (Exception ignored) { }
            }
        }
        if (self == null) return Paths.get("").toAbsolutePath();
        try { self = resolveLinkChain(self.toAbsolutePath()); } catch (IOException ignored) { }
        Path dir = Files.isDirectory(self) ? self : self.getParent();   // .../.flixw
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
            if (!Files.isDirectory(r))
                throw w001("FLIX_PROJECT_ROOT=" + o + " is not a directory");
            return r;
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (!cwd.startsWith(anchor))
            throw w001("this wrapper belongs to " + anchor + ", but the current directory is "
                     + cwd + "\n       cd into the project, or set FLIX_PROJECT_ROOT explicitly");
        for (Path p = cwd; p != null && p.startsWith(anchor); p = p.getParent())
            if (Files.isRegularFile(p.resolve("flix.toml"))) return p;
        // No manifest anywhere below the wrapper. That is not flixw's problem to have:
        // flix.toml is Flix's file, and flixw needs only somewhere to keep the lock --
        // which is the directory the wrapper was installed into, by definition. Refusing
        // here made the empty directory unreachable in both directions: `pin` could not
        // run without a manifest, and `init`, the compiler verb that writes one, could
        // not run without a pinned compiler. The compiler still says what it needs.
        return anchor;
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
        List<String> verbs = parseVerbs(out);
        if (verbs.size() < 3)
            throw w009("cannot parse verbs from `flix --help` of " + jar
                     + " (got " + verbs.size() + " candidate(s))");
        return verbs;
    }

    /** Two-space indent, a lowercase name, then the column gap before its description. */
    static final Pattern COMMAND_ENTRY = Pattern.compile("^ {2}([a-z][a-z0-9_-]*)(?:\\s|$)");

    /**
     * The verbs a help screen advertises, by three independent parses.
     *
     * Two help renderers are in play and neither is a contract. Stock Flix is scopt: the
     * verb list is one `Usage: flix [a|b|c]` line, and each verb repeats as `Command: a`.
     * The picocli-based fork wraps that same bracket across several lines and replaces the
     * per-verb lines with one indented `Commands:` block. A parser that handles only the
     * first reports zero candidates on the second, which is what FLIXW010 was saying.
     *
     * Kept separate from the subprocess that produces the text so it can be tested against
     * both renderers' real output without a JAR; `tests/UnitCheck.java` does exactly that.
     */
    static List<String> parseVerbs(String out) {
        Set<String> set = new LinkedHashSet<>();

        // DOTALL, because picocli breaks the bracket after a `|` and indents what follows:
        // a line-bounded match never reaches the closing `]` and so matches nothing at all.
        Matcher usage = Pattern.compile("(?ms)^Usage:.*?\\[([a-z0-9|_\\-\\s]+)\\]").matcher(out);
        if (usage.find()) {
            String list = usage.group(1).replaceAll("\\s", "");
            // A bracket with no alternation is `[options]`, not a verb list. Worth
            // excluding now that the match may span lines and so reaches further.
            if (list.contains("|"))
                for (String s : list.split("\\|")) if (!s.isBlank()) set.add(s);
        }

        // `Command: lsp-vscode port` carries an argument, so a whole-line parse yields a
        // phantom verb; take the first token only.
        Matcher cmd = Pattern.compile("(?m)^Command:\\s+([A-Za-z][A-Za-z0-9_-]*)").matcher(out);
        while (cmd.find()) set.add(cmd.group(1));

        // picocli's block. The indent is what separates an entry from the wrapped rest of
        // a description -- continuations are indented far deeper -- so this needs no
        // knowledge of how wide the name column happens to be. It stops at the first line
        // that is neither, which for Flix is the trailing note about `--Xhelp`; without
        // that stop a later section could contribute phantom verbs.
        boolean inBlock = false;
        for (String line : out.split("\n", -1)) {
            if (!inBlock) { inBlock = line.startsWith("Commands:"); continue; }
            Matcher m = COMMAND_ENTRY.matcher(line);
            if (m.find()) set.add(m.group(1));
            else if (!line.isBlank() && !line.startsWith("   ")) break;
        }

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
        if (Files.isRegularFile(dir.resolve("flixw.class"))) return;
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
    // `wrapper --help` down with it, which is the command someone would run to find out
    // why.  Whatever a shim cannot determine, it does not act on; it falls through to the
    // source path, where stage 0 owns every Java decision and every message.

    static final String SHIM = """
        #!/bin/sh
        # flixw shim -- GENERATED; DO NOT EDIT.  `flixw install` writes this file,
        # `flixw doctor --fix` restores it, and `flixw validate` compares it byte for byte,
        # so an edit here is first reported and then overwritten.  It is byte-identical
        # across every project on a given flixw release.  To change it, edit the SHIM text
        # block in flixw.java -- src/flixw in that repository is only the checked-in copy,
        # and tests/lint.sh fails if the two disagree.
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
        rem byte.  To change it, edit the CMD text block in flixw.java; src/flixw.cmd in
        rem that repository is only the checked-in copy, and tests/lint.sh fails if the two
        rem disagree.  Finds an initial java, prefers the compiled stage 0 in the user
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

    // ---- wrapper verbs ----------------------------------------------------

    static void wrapperVerb(String verb, List<String> rest, Path root, Lock lock, Path jar,
                            Jvm jvm, List<String> compilerVerbs) {
        switch (verb) {
            case "pin" -> {
                if (rest.isEmpty())
                    throw w009(PIN_USAGE);
                pin(root, parsePin(rest, lock));
            }
            // info reports, validate judges, doctor does both -- which is what the word
            // means everywhere else, and what this one did not do: it printed twelve lines
            // of state, noticed nothing, and exited 0 with a shim that had been edited.
            case "info" -> report(root, lock, jar, jvm, compilerVerbs);
            case "validate" -> {
                int bad = check(root, lock, jar, jvm);
                if (bad > 0) throw w009(bad + " validation failure(s)");
            }
            case "doctor" -> {
                boolean fix = rest.contains("--fix");
                for (String a : rest)
                    if (!a.equals("--fix"))
                        throw w008("./flixw doctor: unknown option " + q(a)
                                 + "\n       usage: ./flixw doctor [--fix]");
                if (fix) { updateWrapper(root); System.out.println(); }
                report(root, lock, jar, jvm, compilerVerbs);
                System.out.println();
                int bad = check(root, lock, jar, jvm);
                if (bad > 0)
                    throw w009(bad + " problem(s); ./flixw doctor --fix repairs the wrapper"
                             + " files, ./flixw pin <version> repairs a drifted lock");
            }
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
        System.out.println("java pin         " + (lock == null || lock.java() == null
            ? "-  (any tested JDK)" : lock.java()));
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
        System.out.println("pass-through     ./flixw -- <args>");
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
                             + " (./flixw doctor --fix)");
        } catch (IOException e) {
            System.out.println("FAIL  unreadable " + label + ": " + why(e));
        }
        return 1;
    }

    /** The line endings the flixw block pins for one shipped path. */
    static String canonicalAttrs(String shipped) {
        return shipped.equals("flixw.cmd") ? "text eol=crlf" : "text eol=lf";
    }

    static final List<String> SHIPPED =
        List.of("flixw", "flixw.cmd", WRAPPER_DIR + "/flixw.java", WRAPPER_DIR + "/lock.toml",
                WRAPPER_DIR + "/.gitignore");

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
            System.out.println("warn  .gitattributes has no flixw block (./flixw doctor --fix)");
            return 0;
        }
        int bad = 0;
        // Markers have to balance and be unique. A stray end marker on its own used to
        // pass for a block, and two blocks meant the last one silently won.
        if (opens != 1 || closes != 1) {
            System.out.println("FAIL  .gitattributes has " + opens + " flixw start and "
                             + closes + " end markers; expected one of each"
                             + " (./flixw doctor --fix)");
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

    /** Runs {@code git <args>} in root; null when git is absent or the command fails to start. */
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

    /** Every check, printed; the count is the caller's to act on. */
    static int check(Path root, Lock lock, Path jar, Jvm jvm) {
        int bad = 0;
        // The shims are invariant for a wrapper release, and this stage 0 carries their
        // canonical bytes, so drift is detectable here rather than merely reportable.
        bad += checkCanonical(root.resolve("flixw"), SHIM, "./flixw");
        bad += checkCanonical(root.resolve("flixw.cmd"), CMD.replace("\n", "\r\n"), "./flixw.cmd");
        if (!isWindows() && Files.isRegularFile(root.resolve("flixw"))
            && !Files.isExecutable(root.resolve("flixw"))) {
            System.out.println("FAIL  ./flixw is not executable (./flixw doctor --fix)"); bad++;
        }

        // Stage 0 cannot know its own canonical hash -- it would have to contain it -- so
        // its digest is reported for comparison against the published wrapper release.
        Path src = root.resolve(WRAPPER_DIR).resolve("flixw.java");
        if (!Files.isRegularFile(src)) {
            System.out.println("FAIL  missing " + WRAPPER_DIR + "/flixw.java"); bad++;
        } else {
            try {
                System.out.println("ok    " + WRAPPER_DIR + "/flixw.java  sha256="
                                 + sha256(Files.readAllBytes(src)));
            } catch (IOException e) {
                System.out.println("FAIL  unreadable " + WRAPPER_DIR + "/flixw.java"); bad++;
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
        else if (mv != null && !olderOrSame(triple(mv), triple(lock.version()))) {
            System.out.println("FAIL  flix.toml asks for " + mv + " or newer, lock pins "
                             + lock.version()); bad++;
        } else System.out.println("ok    the lock satisfies flix.toml");

        // readLock has already validated the lock against the schema by the time validate
        // runs -- a lock that failed it never reached here. What is left to report is the
        // directive that points an *editor* at the same schema, which a lock written by an
        // older flixw does not carry. Not a failure: nothing about the build depends on it.
        if (lock != null) {
            try {
                String text = Files.readString(lockPath(root), StandardCharsets.UTF_8);
                if (text.startsWith("#:schema " + LOCK_SCHEMA_URL + "\n"))
                    System.out.println("ok    the lock conforms to, and names, the "
                                     + LOCK_SCHEMA_VERSION + " schema");
                else
                    System.out.println("warn  the lock conforms to the " + LOCK_SCHEMA_VERSION
                                     + " schema but does not name it; editors will not"
                                     + " validate it (./flixw doctor --fix)");
            } catch (IOException e) {
                System.out.println("FAIL  unreadable " + WRAPPER_DIR + "/lock.toml"); bad++;
            }
        }

        // A java pin is checked against the JDK this run actually selected, not against
        // the machine in general: "there is a 21 somewhere" is not the question.
        if (lock != null && lock.java() != null) {
            String got = jvm == null ? null : probeVersion(jvm.exe());
            if (satisfiesJavaPin(lock.java(), got))
                System.out.println("ok    java " + got + " satisfies the pinned java " + lock.java());
            else {
                System.out.println("FAIL  java " + (got == null ? "unidentifiable" : got)
                                 + " does not satisfy the pinned java " + lock.java()
                                 + " (./flixw wrapper --install-jdk)"); bad++;
            }
        }

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
        List<String> generated = List.of("flixw", "flixw.cmd",
                                         WRAPPER_DIR + "/flixw.java", WRAPPER_DIR + "/lock.toml",
                                         WRAPPER_DIR + "/.gitignore");
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
        return bad;
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

    static void pin(Path root, Pin what) {
        if (what.refresh()) { refreshPin(root); return; }
        String repo = what.repo(), version = what.version(), java = what.java();
        boolean clearJava = what.clearJava();
        Path lockFile0 = lockPath(root);
        // Defensively: `pin` is the documented repair for a lock that does not parse, so
        // reading the old one must not be able to stop it. What is lost when it cannot be
        // read is the java pin it carried, and a lock nobody can parse has no java pin
        // worth preserving.
        Lock had = null;
        if (Files.isRegularFile(lockFile0)) {
            try { had = readLock(lockFile0); } catch (Fail ignored) { }
        }
        // Carry the java pin unless this run changes it: `pin 0.75.3` on a project that
        // pinned java 21 must not silently unpin the Java as well.
        String javaPin = clearJava ? null : (java != null ? java : had == null ? null : had.java());
        // A --java-only run rewrites one line and touches nothing else: no download, no
        // digest, no network. Repinning the compiler is a different request.
        if (version == null) {
            if (had == null)
                throw w002("pin: --java needs a lock that parses"
                         + "\n       run: ./flixw pin <version> --java <version>");
            String lock = lockText(WRAPPER_VERSION, had.repo() == null ? UPSTREAM_REPO : had.repo(),
                                   had.version(), had.url(), had.sha256(), javaPin);
            try { writeAtomic(lockFile0, lock); }
            catch (IOException e) { throw w009("pin failed: " + why(e)); }
            System.err.println(javaPin == null
                ? "flixw: unpinned java; the newest tested JDK will be used"
                : "flixw: pinned java " + javaPin);
            warnMissingJava(javaPin);
            return;
        }
        Asset asset = resolveRelease(repo, version);
        String url = asset.url();
        Path wrapperDir = root.resolve(WRAPPER_DIR);
        Path tmp;
        try {
            Files.createDirectories(wrapperDir);
            tmp = Files.createTempFile(wrapperDir, ".pin-", ".part");
        }
        catch (IOException e) { throw w009("cannot write in " + wrapperDir + ": " + e.getMessage()); }
        Path lockFile = lockPath(root);
        boolean hadLock = Files.isRegularFile(lockFile);
        String oldLock = null;
        // Snapshot before anything can fail, and record whether it succeeded. Rolling back
        // means "put it back the way it was", and that is only possible while the way it
        // was is known: reading the old lock lazily inside the transaction meant an
        // unreadable-but-present lock left oldLock null, so the rollback deleted the
        // project's pin outright -- destroying, in the name of repair, the one file the
        // user came to `pin` to fix.
        boolean snapshot = !hadLock;                       // absence, confirmed
        if (hadLock) {
            try { oldLock = Files.readString(lockFile, StandardCharsets.UTF_8); snapshot = true; }
            catch (IOException ignored) { }                // unknown: leave the file alone
        }
        try {
            download(rewriteBase(url), tmp);
            String digest = sha256(tmp);
            String lock = lockText(WRAPPER_VERSION, repo, version, url, digest, javaPin);

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

            // Only the lock is written. flix.toml belongs to the project and to Flix --
            // its `flix` key is Flix's own field, with Flix's rules -- so pin has no
            // business editing it. What pin owns is the lock, and that is now the only
            // file in this transaction.
            writeAtomic(lockFile, lock);
            System.err.println("flixw: pinned Flix " + version + " from " + repo
                             + " (" + digest.substring(0, 16) + "...)");
            if (!repo.equals(UPSTREAM_REPO))
                System.err.println("       a fork is not stock-compatibility evidence;"
                                 + " see docs/LIMITATIONS.md");
            // Said now rather than only on the next command. pin still writes it -- the
            // floor lives in the project's own file and lowering it may be exactly the
            // plan -- but "accepted, and every later run will refuse" is not something to
            // find out later.
            warnMissingJava(javaPin);
            String floor = null;
            try { floor = manifestVersion(root.resolve("flix.toml")); } catch (Fail ignored) { }
            if (floor != null && !olderOrSame(triple(floor), triple(version)))
                System.err.println("       note: flix.toml asks for " + floor + " or newer,"
                                 + " so this lock will not run until one of them moves");
        } catch (IOException e) {
            if (snapshot) restore(lockFile, oldLock);
            throw w009("pin failed: " + why(e));
        } finally { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
    }
    /**
     * A pin naming a Java this machine does not have is written, and said out loud. It is
     * not an error -- the machine that runs the build may not be this one -- but finding
     * out at the next command, from a diagnostic about a missing JDK, is finding out late.
     */
    static void warnMissingJava(String javaPin) {
        if (javaPin == null || javaPinAvailable(javaPin)) return;
        System.err.println("       note: no Java " + javaPin + " on this machine;"
                         + " nothing here will run the compiler until there is");
        System.err.println("       run: ./flixw wrapper --install-jdk   (fetches Temurin "
                         + feature(javaPin) + " into the flixw cache)");
    }

    /**
     * One place that knows what a lock looks like, so the writer cannot drift by table.
     *
     * The first line is a Taplo `#:schema` directive, which Even Better TOML and taplo
     * both honour: an editor validates the lock against the published schema with nothing
     * configured per project, which is the only way a generated file gets checked by the
     * person editing it by hand against the advice at the top of it. It names the
     * versioned schema rather than a floating one, for the reason the compiler pin names
     * an exact version -- a lock is a pin, including of what it means.
     */
    static String lockText(String wrapper, String repo, String version, String url,
                           String sha256, String java) {
        String body = """
            #:schema %s
            # Generated by flixw. Do not edit by hand; commit this file.
            wrapperVersion = "%s"

            [compiler]
            repo    = "%s"
            version = "%s"
            url     = "%s"
            sha256  = "%s"
            """.formatted(LOCK_SCHEMA_URL, wrapper, repo, version, url, sha256);
        // Absent rather than empty when unpinned: a project that does not care which JDK
        // runs the compiler should not have to read a line telling it so.
        return java == null ? body : body + """

            [java]
            version = "%s"
            """.formatted(java);
    }

    static void install(Path target, Path source) {
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
            writeEnvrcExample(target);
            migrateFromFlixNames(target);
            mergeGitattributes(target.resolve(".gitattributes"));
            System.out.println("installed ./flixw, ./flixw.cmd and " + WRAPPER_DIR
                             + "/flixw.java into " + target);
            // `install` is reached two ways, and they need different sentences. First
            // contact has nothing pinned and the next step is pinning; an upgrade arrives
            // here through `wrapper --upgrade` with a lock already in place, and telling
            // that reader to pin reads as though the upgrade lost their compiler.
            if (Files.isRegularFile(lockPath(target))) {
                System.out.println("the compiler pin is untouched; commit the wrapper files"
                                 + " that changed:");
                System.out.println("  git add flixw flixw.cmd " + WRAPPER_DIR);
            } else {
                System.out.println("next: ./flixw pin <version>   then commit all five files");
            }
        } catch (IOException e) { throw w009("install failed: " + e.getMessage()); }
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

    static void writeLocalIgnore(Path target) throws IOException {
        Path f = target.resolve(WRAPPER_DIR).resolve(".gitignore");
        if (Files.isRegularFile(f)
            && Files.readString(f, StandardCharsets.UTF_8).equals(LOCAL_IGNORE)) return;
        Files.createDirectories(f.getParent());
        writeAtomic(f, LOCAL_IGNORE);
    }

    /**
     * A template for the one supported way to run a compiler flixw did not download.
     *
     * `FLIX_JAR` has always worked, and was findable only by reading one table row in
     * docs/CONTRACT.md -- so in practice the people who needed it did not know it existed.
     * A file sitting in the project says so without being read.
     *
     * The name is `.envrc.example`, not `.envrc`, and that is the whole point of the file
     * rather than a detail of it. direnv refuses an `.envrc` it has not been shown, and
     * reprints `direnv: error ... is blocked` on every cd into the directory until someone
     * runs `direnv allow` or deletes it. The refusal is keyed on the file's hash, so a
     * fully commented-out `.envrc` is blocked exactly like a live one: shipping one would
     * hand recurring noise to the only population it could help. `.example` is inert.
     */
    static final String ENVRC_EXAMPLE =
        "# .envrc.example -- copy to .envrc, then run: direnv allow\n"
      + "#\n"
      + "# Requires direnv (https://direnv.net); flixw itself never reads this file.\n"
      + "# direnv exports these into your shell before ./flixw ever starts, which is\n"
      + "# also why it reaches a terminal and not an editor-spawned `flixw lsp`.\n"
      + "#\n"
      + "# One-time setup per machine. direnv does nothing until its hook is in your\n"
      + "# shell's startup file, and until then an .envrc is an inert text file:\n"
      + "#\n"
      + "#   bash   in ~/.bashrc:                  eval \"$(direnv hook bash)\"\n"
      + "#   zsh    in ~/.zshrc:                   eval \"$(direnv hook zsh)\"\n"
      + "#   fish   in ~/.config/fish/config.fish: direnv hook fish | source\n"
      + "#\n"
      + "# This file is bash whatever your own shell is: direnv evaluates it with bash\n"
      + "# and exports the difference. So fish users still write `export FOO=bar` here\n"
      + "# -- `set -x FOO bar` is a syntax error in this file.\n"
      + "#\n"
      + "# Everything here is optional and every line is commented out. flixw works with\n"
      + "# none of it set; the full table is in docs/CONTRACT.md.\n"
      + "\n"
      + "# ---- which JDK runs the compiler ------------------------------------------\n"
      + "# Prefer pinning it for everyone in " + WRAPPER_DIR + "/lock.toml:\n"
      + "#   ./flixw pin <version> --java 21\n"
      + "# That is committed and reproducible. Use this only when *your* machine keeps\n"
      + "# that JDK somewhere the search would not find. An invalid value is fatal, on\n"
      + "# purpose: a silently ignored JDK selection is worse than a stopped build.\n"
      + "#\n"
      + "# export FLIX_JAVA_HOME=\"$HOME/.sdkman/candidates/java/21.0.5-tem\"\n"
      + "\n"
      + "# ---- running a compiler flixw did not download ----------------------------\n"
      + "# The jar is NOT digest-verified, every such run says so on stderr, and those\n"
      + "# runs are not stock-compatibility evidence. A valid lock is still required:\n"
      + "# " + WRAPPER_DIR + "/lock.toml is read, and drift checked, before the override is.\n"
      + "#\n"
      + "# export FLIX_JAR=\"$PWD/../flix/build/libs/flix.jar\"\n"
      + "\n"
      + "# ---- where downloads land -------------------------------------------------\n"
      + "# A cache inside the project, rather than the shared one under your home\n"
      + "# directory. Useful for a throwaway container, or to keep one project's\n"
      + "# compilers off a small home volume; the compiler is then downloaded once\n"
      + "# per project instead of once per machine.\n"
      + "#\n"
      + "# Put it under local/ if you put it here at all -- that is the one path\n"
      + "# " + WRAPPER_DIR + "/.gitignore already keeps out of git, and a cache holds a\n"
      + "# ~33MB jar that must never reach a commit.\n"
      + "#\n"
      + "# export FLIX_CACHE_HOME=\"$PWD/" + WRAPPER_DIR + "/local/cache\"\n"
      + "\n"
      + "# ---- fetching through a mirror --------------------------------------------\n"
      + "# Rewrites the download base only. The pinned digest is unchanged and still\n"
      + "# verified, so a mirror serving different bytes fails rather than substitutes.\n"
      + "# Must be https.\n"
      + "#\n"
      + "# export FLIX_DIST_URL=\"https://artifacts.example.com/flix\"\n"
      + "# export HTTPS_PROXY=\"http://proxy.example.com:3128\"\n"
      + "# export NO_PROXY=\"localhost,127.0.0.1,.example.com\"\n"
      + "\n"
      + "# ---- options for the compiler JVM -----------------------------------------\n"
      + "# For a project big enough to need more heap than the default. These go to the\n"
      + "# compiler's JVM, not to flixw's; a deny-list rejects the ones that would\n"
      + "# change what code the JVM loads or runs (-cp, -javaagent:, @argfiles).\n"
      + "#\n"
      + "# export FLIX_JVM_OPTS=\"-Xmx4g\"\n"
      + "\n"
      + "# ---- while debugging flixw itself -----------------------------------------\n"
      + "# Per-phase timings on stderr. Transient by nature -- if this is still on in a\n"
      + "# month, that is the sign it belongs in your shell for one command instead.\n"
      + "#\n"
      + "# export FLIXW_TRACE=1\n"
      + "\n"
      + "# ---- keeping this file honest ---------------------------------------------\n"
      + "# Anything machine-specific belongs in an ignored file, not in the one you\n"
      + "# might be tempted to commit:\n"
      + "#\n"
      + "# source_env_if_exists .envrc.local\n"
      + "#\n"
      + "# flixw does not edit your .gitignore. Add these yourself if you use them:\n"
      + "#   .envrc\n"
      + "#   .envrc.local\n";

    /**
     * Written once, then never touched again -- unlike every other file install writes.
     *
     * The others are flixw's: they are executed or parsed, drift in them breaks a run, and
     * `doctor --fix` restoring them is a repair. This one sits at the project root among
     * files the project owns, nothing reads it, and its whole purpose is to be copied and
     * edited. Rewriting it on drift would be overwriting someone's notes to restore a file
     * that does nothing. For the same reason it is absent from SHIPPED, from doctor's
     * canonical comparison and tracked-file audit, and from the .gitattributes block:
     * deleting it is a valid answer, and nothing should nag about that.
     */
    static void writeEnvrcExample(Path target) throws IOException {
        Path f = target.resolve(".envrc.example");
        if (Files.exists(f)) return;
        writeAtomic(f, ENVRC_EXAMPLE);
        System.out.println("wrote    ./.envrc.example  (direnv template for FLIX_JAR;"
                         + " safe to delete)");
    }

    /**
     * Moves a project installed under the pre-0.20 names onto the current ones.
     *
     * Until 0.20 the wrapper shipped as `flix`, `flix.cmd` and `.flix-wrapper/flix.java`,
     * which read as the compiler's own name on a tool that is not the compiler. Installing
     * over such a project would otherwise leave both sets side by side, and the pin -- the
     * one file here that is the project's rather than ours -- would still be in the old
     * directory, where nothing reads it. So the lock moves first, and the old files are
     * removed only when they are recognisably the ones flixw wrote: a shim someone edited,
     * or a directory holding anything else, is left alone and reported.
     */
    static void migrateFromFlixNames(Path target) throws IOException {
        Path old = target.resolve(".flix-wrapper");
        Path oldLock = old.resolve("lock.toml");
        Path newLock = lockPath(target);
        if (Files.isRegularFile(oldLock) && !Files.isRegularFile(newLock)) {
            Files.createDirectories(newLock.getParent());
            Files.move(oldLock, newLock, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("moved    .flix-wrapper/lock.toml -> " + WRAPPER_DIR + "/lock.toml");
        }
        for (String name : List.of("flix", "flix.cmd")) {
            Path f = target.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            String body = Files.readString(f, StandardCharsets.UTF_8);
            // Only a shim that still says what flixw writes is ours to delete.
            if (!body.contains("flixw") || !body.contains("stage0")) {
                System.out.println("kept     ./" + name + " (edited; remove it by hand)");
                continue;
            }
            Files.delete(f);
            System.out.println("removed  ./" + name);
        }
        Path oldSrc = old.resolve("flix.java");
        if (Files.isRegularFile(oldSrc)) { Files.delete(oldSrc); }
        if (Files.isDirectory(old)) {
            try (var s = Files.list(old)) {
                if (s.findAny().isEmpty()) {
                    Files.delete(old);
                    System.out.println("removed  .flix-wrapper/");
                } else {
                    System.out.println("kept     .flix-wrapper/ (not empty)");
                }
            }
        }
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
            // A lock only `pin <version>` can repair is not this command's to guess at;
            // everything else doctor --fix reports is still reported.
            try {
                if (refreshLock(root).changed()) {
                    System.out.println("rewrote  " + WRAPPER_DIR + "/lock.toml"); changed++;
                }
            } catch (Fail unparseable) { }
        } catch (IOException e) { throw w009("rewriting the wrapper files failed: " + why(e)); }
        // One line, and only the one that is true. The two-line note that used to follow
        // every run explained that this refreshes rather than upgrades -- which is a fact
        // about what the command is, not about what just happened, so it belongs in
        // `wrapper --help` where somebody is looking for it. Printing it as output made
        // every successful run look like it came with a caveat.
        System.out.println(changed == 0
            ? "wrapper files already match flixw " + WRAPPER_VERSION
            : changed + (changed == 1 ? " file" : " files")
              + " rewritten from flixw " + WRAPPER_VERSION);
    }

    /**
     * Rewrites the lock in the shape this release writes, from the values already in it.
     * True when it changed. Offline, and it changes the file's form rather than its
     * meaning: same repository, version, URL, digest and java pin.
     *
     * It exists because a lock written by an older flixw has no `#:schema` line, and there
     * was no offline way to acquire one -- `pin` re-downloads the compiler to write the
     * file, which is a large price for a comment. `doctor --fix` is where the project's
     * generated files are brought up to this release, and the lock is one of them.
     *
     * Three things stop it. A lock that does not parse is `pin`'s job, and guessing at one
     * is how a repair destroys what it came to fix. A lock written by a *newer* flixw is
     * not this release's to reshape. And a lock carrying a key this release does not read
     * would have that key silently deleted, since the rewrite is from the values read --
     * which is the same hazard as the second, one key at a time.
     */
    /**
     * Whether the lock was rewritten, and the sentence explaining why not when it was not.
     *
     * Both callers need the reason, for opposite purposes: `doctor --fix` discards it,
     * because it is repairing everything it can and this is one item among several, while
     * `pin --refresh` prints it -- there the user asked for this and nothing else, and a
     * command that does nothing and says nothing reads as one that worked.
     */
    record Refresh(boolean changed, String why) {}

    static Refresh refreshLock(Path root) throws IOException {
        Path lockFile = lockPath(root);
        if (!Files.isRegularFile(lockFile))
            return new Refresh(false, "there is no " + WRAPPER_DIR + "/lock.toml");
        String text = Files.readString(lockFile, StandardCharsets.UTF_8);
        // Not caught here: a lock this cannot read is one only `pin <version>` repairs,
        // and its diagnostic already says so. doctor --fix is what catches it.
        Lock lock = readLock(lockFile);
        String w = lockFile.toString();
        String wroteIt = tomlLookup(text, "", "wrapperVersion", w);
        if (wroteIt != null && !olderOrSame(wroteIt, WRAPPER_VERSION))
            return new Refresh(false, "the lock was written by flixw " + wroteIt
                                    + ", which is newer than this one (" + WRAPPER_VERSION + ")");
        List<String> unknown = unknownLockKeys(text, w);
        if (!unknown.isEmpty())
            return new Refresh(false, "the lock carries " + String.join(", ", unknown)
                                    + ", which this flixw does not read and would drop");
        String want = lockText(WRAPPER_VERSION, lock.repo() == null ? UPSTREAM_REPO : lock.repo(),
                               lock.version(), lock.url(), lock.sha256(), lock.java());
        if (want.equals(text))
            return new Refresh(false, "the lock is already what flixw " + WRAPPER_VERSION
                                    + " writes");
        writeAtomic(lockFile, want);
        return new Refresh(true, null);
    }

    /**
     * `./flixw pin --refresh`. Offline: the compiler is not re-resolved, not re-downloaded
     * and not re-hashed, and the pin does not move. What changes is the file's shape --
     * the `#:schema` line a lock written before it existed does not carry, the recorded
     * wrapper version, the layout -- which is why it is a form of `pin` and not of
     * `upgrade`.
     */
    static void refreshPin(Path root) {
        Refresh r;
        try { r = refreshLock(root); }
        catch (IOException e) { throw w009("pin --refresh failed: " + why(e)); }
        System.err.println(r.changed()
            ? "flixw: rewrote " + WRAPPER_DIR + "/lock.toml in the shape flixw "
              + WRAPPER_VERSION + " writes; the pin is unchanged"
            : "flixw: nothing to do -- " + r.why());
    }

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

    /** Is `a` no newer than `b`? Both are the wrapper's own dotted versions. */
    static boolean olderOrSame(String a, String b) {
        String[] x = canonical(a).split("\\."), y = canonical(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0, yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) return xi < yi;
        }
        return true;                                  // identical is not an upgrade
    }

    static int num(String s) {
        Matcher m = Pattern.compile("^([0-9]+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** flixw's own releases. `latest/download` resolves without asking an API anything. */
    static final String FLIXW_LATEST =
        "https://github.com/wstein/flixw/releases/latest/download/";

    /**
     * Moves this project to the newest published flixw.
     *
     * The old `--upgrade` rewrote the files from the stage 0 already in the tree, which is
     * a repair rather than a version change -- so it printed a note on every run
     * explaining that it had not done what its name says. That repair is now
     * `./flixw doctor --fix`, and this does what the word means.
     *
     * The new stage 0 installs itself. It is the only thing that knows its own shim bytes,
     * and having the old one write files for a version it has never seen is how the two
     * drift apart.
     *
     * The digest is checked against the SHA256SUMS published beside it -- same origin,
     * same TLS, so this catches a corrupted or truncated download and not a compromised
     * release. That is the same footing as the compiler pin, and docs/LIMITATIONS.md says
     * so; a self-update is simply where it matters most.
     */
    static void upgradeWrapper(Path root) {
        String sums = httpGet(FLIXW_LATEST + "SHA256SUMS");
        String want = null;
        for (String line : sums.split("\r?\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length == 2 && f[1].equals("flixw.java")) want = f[0];
        }
        if (want == null || !want.matches("[0-9a-f]{64}"))
            throw w005("the published SHA256SUMS names no digest for flixw.java");

        Path current = root.resolve(WRAPPER_DIR).resolve("flixw.java");
        if (Files.isRegularFile(current) && sha256(current).equals(want)) {
            // Same sentence as the version guard below, because it is the same outcome:
            // nothing was changed and nothing needed to be. They differ only in how it was
            // established -- a matching digest here, a version comparison there.
            System.out.println("flixw " + WRAPPER_VERSION
                             + " is the newest release. Nothing to do.");
            return;
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("flixw-upgrade-");
            Path fresh = dir.resolve("flixw.java");
            System.err.println("flixw: downloading the latest flixw");
            download(FLIXW_LATEST + "flixw.java", fresh);
            String got = sha256(fresh);
            if (!got.equals(want))
                throw w006("digest mismatch for the downloaded flixw.java"
                         + "\n       published " + want + "\n       downloaded " + got);

            // Newest published is not the same as newer than this. Anyone working on
            // flixw itself runs a version no release has yet, and "upgrade" must not walk
            // them backwards to it.
            Matcher m = Pattern.compile("WRAPPER_VERSION\\s*=\\s*\"([^\"]+)\"")
                               .matcher(Files.readString(fresh, StandardCharsets.UTF_8));
            String published = m.find() ? m.group(1) : null;
            if (published != null && olderOrSame(published, WRAPPER_VERSION)) {
                System.out.println("flixw " + WRAPPER_VERSION + " is newer than the newest"
                                 + " release (" + published + "). Nothing to do.");
                return;
            }
            System.err.println("flixw: " + WRAPPER_VERSION + " -> "
                             + (published == null ? "the latest release" : published));
            // Hand over: the new stage 0 writes its own shims and its own copy of itself.
            Path javaExe = exeIn(System.getProperty("java.home"));
            ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), fresh.toString(),
                                                   "install", root.toString()).inheritIO();
            // The child is a different file in a different directory. Both markers describe
            // *this* process and mean nothing to it -- FLIXW_SOURCE would anchor it in this
            // project, and FLIXW_RELAUNCHED would spend its one relaunch before it starts.
            pb.environment().remove("FLIXW_SOURCE");
            pb.environment().remove("FLIXW_RELAUNCHED");
            Process p = pb.start();
            int rc = awaitWithReaper(p);
            if (rc != 0) throw w009("the downloaded flixw failed to install (exit " + rc + ")");
        } catch (IOException e) {
            throw w007("cannot upgrade the wrapper: " + why(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw w009("upgrade interrupted");
        } finally {
            if (dir != null) deleteTree(dir);
        }
    }

    /**
     * flixw's own namespace. {@link #wrapperUsage} is the one list of what it offers.
     *
     * One verb, and every flixw-only operation under it as a flag.  These are not
     * stand-ins for anything Flix might one day ship, so they neither retire nor compete
     * for a name with something that will: `pin`, `info`, `doctor` and `validate`
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
                System.out.println("stage0 " + (sourceLaunchPath() == null ? "compiled" : "source")
                                 + "  java " + Runtime.version());
            }
            case "--upgrade" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--upgrade' takes no arguments"));
                // The only operation here that needs a project, and it resolves one itself
                // rather than making the others depend on being inside one.
                upgradeWrapper(findRoot(wrapperAnchor()));
            }
            case "--install-jdk" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--install-jdk' takes no arguments"));
                installJdkVerb(argv.subList(1, argv.size()));
            }
            // Offline, project-free and side-effect-free, like --version: the schema is a
            // property of this stage 0, not of any project, and someone validating a lock
            // in CI should not have to reach the network for the file the lock points at.
            case "--schema" -> {
                if (!rest.isEmpty()) throw w008(wrapperUsage("'--schema' takes no arguments"));
                System.out.print(lockSchemaJson());
            }
            // Offline and project-free for the same reason as --schema, and for one more:
            // the script it prints is byte-identical for every project on a given release,
            // because everything project-specific is read at completion time from the note
            // stage 0 leaves in .flixw/local/.  A script that had to be regenerated after
            // every `pin` would be wrong in the one way a completion script must not be --
            // silently, and only for the person who forgot.
            case "--completion" -> {
                if (rest.size() != 1)
                    throw w008(wrapperUsage("'--completion' takes exactly one shell name"));
                System.out.print(completionScript(rest.get(0)));
            }
            default -> throw w008(wrapperUsage("unknown operation " + q(op)));
        }
    }

    static String wrapperUsage(String problem) {
        return "./flixw wrapper: " + problem
             + "\n       usage: ./flixw wrapper [--help | --version | --upgrade | --install-jdk"
             + "\n                              | --schema | --completion]"
             + "\n         --help         the routing table for this project"
             + "\n         --version      the wrapper version and how stage 0 was launched"
             + "\n         --upgrade      move this project to the newest published flixw"
             + "\n                        (to repair the files it has: ./flixw doctor --fix)"
             + "\n         --install-jdk  fetch a verified Temurin " + MIN_JAVA + " into the cache"
             + "\n         --schema       the JSON Schema for " + WRAPPER_DIR + "/lock.toml, on stdout"
             + "\n         --completion <shell>   a TAB-completion script, on stdout,"
             + "\n                        for one of " + String.join(", ", COMPLETION_SHELLS);
    }

    // ---- completion -------------------------------------------------------

    static final List<String> COMPLETION_SHELLS = List.of("bash", "zsh", "fish", "pwsh");

    static final String COMPL_BASH = """
        # flixw TAB completion for bash -- GENERATED by `flixw wrapper --completion bash`.
        #
        #   ./flixw wrapper --completion bash > ~/.local/share/bash-completion/completions/flixw
        #
        # Candidates are read at TAB time from <project>/.flixw/local/verbs, the note stage 0
        # leaves after it resolves a compiler, so they follow the pin and this file does not
        # have to be regenerated after `pin`.  Nothing here starts a JVM: a stage 0 launch
        # plus the digest re-hash flixw does on every run would cost more than typing the verb.

        _flixw_root() {
          # The project is wherever the wrapper being completed lives, not the working
          # directory: `../other/flixw` is a different project's verb set, and completing it
          # from this one would be confidently wrong.
          local d
          d=$(dirname -- "$1" 2>/dev/null) || d=.
          [ -n "$d" ] || d=.
          printf '%s' "$d"
        }

        _flixw() {
          local cur root note words
          cur=${COMP_WORDS[COMP_CWORD]}
          root=$(_flixw_root "${COMP_WORDS[0]}")
          note=$root/.flixw/local/verbs

          if [ "$COMP_CWORD" -eq 1 ]; then
            # No note yet means this project has never resolved a compiler.  The baked-in
            # list is this wrapper release's own view -- stale in the same harmless way the
            # built-in verb table in stage 0 is, and better than completing nothing.
            if [ -r "$note" ]; then words=$(cat -- "$note" 2>/dev/null)
            else words="@VERBS@"; fi
            # shellcheck disable=SC2207
            COMPREPLY=( $(compgen -W "$words" -- "$cur") )
            return 0
          fi

          # Past the verb the compiler owns the arguments, and this completer has nothing
          # to say about them; `-o default` then hands the word to filename completion,
          # which is what the arguments to `run`, `check` and `build` mostly are.
          return 0
        }

        # Both spellings: nobody puts the wrapper on PATH, so `./flixw` is the form that
        # matters, and bash matches the command word as typed rather than resolving it.
        complete -F _flixw -o default flixw ./flixw
        """;

    static final String COMPL_ZSH = """
        #compdef flixw ./flixw
        # flixw TAB completion for zsh -- GENERATED by `flixw wrapper --completion zsh`.
        #
        #   ./flixw wrapper --completion zsh > "${fpath[1]}/_flixw"
        #
        # Candidates are read at TAB time from <project>/.flixw/local/verbs, the note stage 0
        # leaves after it resolves a compiler, so they follow the pin and this file does not
        # have to be regenerated after `pin`.  Nothing here starts a JVM.

        _flixw() {
          # The project is wherever the wrapper being completed lives, not the working
          # directory; :h on a bare name yields `.`, which is the same answer.
          local root=${words[1]:h}
          [[ -n $root ]] || root=.
          local note=$root/.flixw/local/verbs

          if (( CURRENT == 2 )); then
            local -a verbs
            # No note yet means no compiler has been resolved here; the baked-in list is this
            # release's own view, stale in the same harmless way stage 0's table is.
            if [[ -r $note ]]; then verbs=( ${(f)"$(<$note)"} )
            else verbs=( @VERBS@ ); fi
            _describe -t flixw-verbs 'flixw verb' verbs
            return
          fi

          # Past the verb the compiler owns the arguments; fall through to files, which
          # is what the arguments to `run`, `check` and `build` mostly are.
          _files
        }

        _flixw "$@"
        """;

    static final String COMPL_FISH = """
        # flixw TAB completion for fish -- GENERATED by `flixw wrapper --completion fish`.
        #
        #   ./flixw wrapper --completion fish > ~/.config/fish/completions/flixw.fish
        #
        # Candidates are read at TAB time from <project>/.flixw/local/verbs, the note stage 0
        # leaves after it resolves a compiler, so they follow the pin and this file does not
        # have to be regenerated after `pin`.  Nothing here starts a JVM.
        #
        # Verbs only.  Past the verb, fish's own file completion takes over -- which is what
        # the arguments to `run`, `check` and `build` mostly are.

        function __flixw_verbs --description 'the verbs the project being completed dispatches'
            set -l tokens (commandline -opc)
            test (count $tokens) -gt 0; or return
            # The project is wherever the wrapper being completed lives, not the working
            # directory: `../other/flixw` is a different project's verb set.  Done with
            # builtins rather than dirname, so a keypress costs no process at all.
            set -l root .
            if string match -q '*/*' -- $tokens[1]
                set root (string replace -r '/[^/]*$' '' -- $tokens[1])
                test -n "$root"; or set root /
            end
            set -l note $root/.flixw/local/verbs
            if test -r $note
                cat -- $note
            else
                # No note yet means this project has never resolved a compiler.  The baked-in
                # list is this wrapper release's own view -- stale in the same harmless way
                # the built-in verb table in stage 0 is, and better than completing nothing.
                printf '%s\\n' @VERBS@
            end
        end

        # fish matches on the command's base name, so this one registration covers `flixw`,
        # `./flixw` and an absolute path alike -- unlike bash, which matches the word as typed.
        complete -c flixw -f -n __fish_is_first_arg -a '(__flixw_verbs)'
        complete -c flixw -n 'not __fish_is_first_arg' -F
        """;

    static final String COMPL_PWSH = """
        # flixw TAB completion for PowerShell -- GENERATED by
        # `flixw wrapper --completion pwsh`.
        #
        #   ./flixw wrapper --completion pwsh >> $PROFILE
        #
        # Candidates are read at TAB time from <project>\\.flixw\\local\\verbs, the note stage 0
        # leaves after it resolves a compiler, so they follow the pin and this file does not
        # have to be regenerated after `pin`.  Nothing here starts a JVM.
        #
        # This registers against the existing flixw.cmd trampoline; PowerShell completes
        # native commands including batch files, so nothing has to move to a .ps1 -- and the
        # trampoline could not move anyway, since a .ps1 is not invokable as a bare command
        # from cmd.exe or from a build tool, and the default execution policy blocks a
        # downloaded one.  cmd.exe itself has no per-command completion mechanism at all, so
        # it gets nothing here and that is an absence in cmd, not a gap in this script.
        #
        # Verbs only.  Past the verb, PowerShell's own file completion takes over.
        Register-ArgumentCompleter -Native -CommandName flixw, flixw.cmd -ScriptBlock {
            param($wordToComplete, $commandAst, $cursorPosition)

            $elements = $commandAst.CommandElements
            # The verb position only; past it the compiler owns the arguments.
            if ($elements.Count -gt 2) { return }
            if ($elements.Count -eq 2 -and [string]::IsNullOrEmpty($wordToComplete)) { return }

            # The project is wherever the wrapper being completed lives, not the working
            # directory: another project's wrapper has another project's verb set.
            $root = Split-Path -Parent $elements[0].ToString()
            if ([string]::IsNullOrEmpty($root)) { $root = '.' }
            $note = Join-Path $root '.flixw/local/verbs'

            # No note yet means no compiler has been resolved here; the baked-in list is this
            # release's own view, stale in the same harmless way stage 0's table is.
            $verbs = if (Test-Path -LiteralPath $note) { Get-Content -LiteralPath $note }
                     else { @(@VERBS@) }

            $verbs | Where-Object { $_ -and $_.StartsWith($wordToComplete) } | ForEach-Object {
                [System.Management.Automation.CompletionResult]::new(
                    $_, $_, 'ParameterValue', $_)
            }
        }
        """;

    /**
     * The name of the note stage 0 leaves for a completer, holding the verbs this project
     * would actually dispatch.  It lives beside {@code local/java} and is machine-specific
     * for the same reason: it describes a resolved compiler, not the project.
     */
    static final String VERBS_NOTE = "verbs";

    /**
     * A completion script for one shell, on stdout.
     *
     * The script is static and the data is not.  Completion candidates depend on the pinned
     * compiler -- compiler-first dispatch means a verb set that changes with the lock -- so
     * a script that baked them in would go stale at the next {@code pin} and say nothing
     * about it.  Instead the script reads them at TAB time from {@code .flixw/local/verbs},
     * which stage 0 rewrites on every run that resolves a compiler.  That also keeps the
     * JVM out of the completion path: a TAB press costs a file read, not a stage 0 launch
     * plus the mandatory digest re-hash, which together are slower than typing the verb.
     *
     * The verb list compiled into each script is the fallback for a project that has not
     * resolved a compiler yet -- the same bargain {@link #BUILTIN_VERBS} makes, and stale
     * in the same harmless way.
     *
     * @param shell one of {@link #COMPLETION_SHELLS}
     * @return the script text, ending in a newline
     */
    static String completionScript(String shell) {
        List<String> fallback = new ArrayList<>(WRAPPER_VERBS);
        for (String v : BUILTIN_VERBS) if (!fallback.contains(v)) fallback.add(v);
        fallback.sort(null);
        List<String> quoted = new ArrayList<>();
        for (String v : fallback) quoted.add(q(v));
        return switch (shell) {
            case "bash" -> COMPL_BASH.replace("@VERBS@", String.join(" ", fallback));
            case "zsh" -> COMPL_ZSH.replace("@VERBS@", String.join(" ", fallback));
            case "fish" -> COMPL_FISH.replace("@VERBS@", String.join(" ", fallback));
            case "pwsh" -> COMPL_PWSH.replace("@VERBS@", String.join(",", quoted));
            default -> throw w008(wrapperUsage("unknown shell " + q(shell)));
        };
    }

    /**
     * Records the verbs this project dispatches, for a completer to read.
     *
     * The union, not the compiler's set alone: a wrapper verb the compiler has claimed is
     * still a verb the user can type, and one it has not claimed is still handled here.
     * Which side runs it is dispatch's business and no help to someone pressing TAB.
     *
     * Every failure is discarded, exactly as in {@link #recordJava}: a read-only checkout
     * or a deleted directory is not worth a diagnostic for a note whose absence only costs
     * a completer its per-project accuracy.
     */
    static void recordVerbs(Path root, List<String> compilerVerbs) {
        List<String> all = new ArrayList<>(compilerVerbs);
        for (String v : WRAPPER_VERBS) if (!all.contains(v)) all.add(v);
        all.sort(null);
        recordNote(root, VERBS_NOTE, String.join(System.lineSeparator(), all));
    }

    static void recordNote(Path root, String name, String body) {
        Path note = root.resolve(WRAPPER_DIR).resolve("local").resolve(name);
        try {
            String want = body + System.lineSeparator();
            if (Files.isRegularFile(note)
                && Files.readString(note, StandardCharsets.UTF_8).equals(want)) return;
            Files.createDirectories(note.getParent());
            writeAtomic(note, want);
        } catch (IOException | RuntimeException ignored) { }
    }

    // ---- main -------------------------------------------------------------

    /**
     * The one entry point. Every failure inside is a {@link Fail}, which carries both the
     * {@code FLIXWnnn} code printed on stderr and the advisory exit status; nothing else
     * writes an exit status, so a code the user's own program returns cannot be confused
     * with one of ours by accident of where it was thrown.
     *
     * @param args the wrapper's argv, passed on to the compiler unchanged when dispatch
     *             decides the compiler owns them
     */
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
        // The list of operations is wrapperUsage's alone. Spelled out a second time here,
        // it went stale the first time one was added, and lint cannot see this copy: it
        // greps for one flag named after the verb, which a bracketed list is not.
        if (first != null && first.startsWith("--wrapper-"))
            throw w008(wrapperUsage("unknown launcher flag " + q(first)
                     + "\n       flixw's own operations moved under one verb"));

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
        // `[package].flix` is a floor, not a pin: Flix accepts 99.99.99 against a 0.75.2
        // compiler and rejects anything but x.x.x, so comparing it to the lock for
        // equality was comparing two different kinds of statement. What can be checked is
        // that the pinned compiler satisfies what the project asked for.
        String drift = (lock != null && mv != null
                        && !olderOrSame(triple(mv), triple(lock.version())))
            ? "flix.toml asks for Flix " + mv + " or newer, but " + WRAPPER_DIR
              + "/lock.toml pins " + lock.version()
              + "\n       run: ./flixw pin " + triple(mv) + " (or lower the requirement)"
            : null;

        // When the project cannot reach a compiler -- no lock yet, or a lock that
        // disagrees with the manifest -- the verbs that create and diagnose that state
        // must still run, or the repair the diagnostic recommends is unreachable. They
        // route on the built-in wrapper list alone, so no compiler is consulted.
        if ((lock == null || drift != null || manifestError != null) && first != null && !forcedCompiler
            && WRAPPER_VERBS.contains(first)) {
            if (lockError != null)
                System.err.println("flixw: warning: " + lockError.getMessage().split("\n")[0]);
            if (manifestError != null)
                System.err.println("flixw: warning: " + manifestError.getMessage().split("\n")[0]);
            if (drift != null) System.err.println("flixw: warning: " + drift.split("\n")[0]);
            routingNotice(first, lock == null ? "none" : lock.version());
            if (first.equals("pin")) {
                pin(root, parsePin(argv.subList(1, argv.size()), lock));
            }
            else
                wrapperVerb(first, argv.subList(1, argv.size()), root, lock, null, null, null);
            return;
        }
        if (lockError != null) throw lockError;      // unreadable, and the repair declined it
        if (manifestError != null) throw manifestError;
        if (lock == null)
            throw w002("no " + lockFile + "\n       run: ./flixw pin <version>");
        if (drift != null) throw w002(drift);

        // pin is the documented repair and never needs the compiler.
        if ("pin".equals(first) && !forcedCompiler) {
            routingNotice("pin", lock.version());
            pin(root, parsePin(argv.subList(1, argv.size()), lock));
            return;
        }

        Jvm jvm = selectJava(lock == null ? null : lock.java());
        tr("java " + jvm.exe() + " (" + jvm.feature() + ")");
        recordJava(root, jvm.exe());
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
        // A note for a completer, which cannot afford to start stage 0 itself.  Every
        // failure is swallowed: a completion candidate is not worth a diagnostic, still
        // less a failed build.
        recordVerbs(root, compilerVerbs);
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
        // outright. Either way `./flixw -- --help` and FLIX_BACKEND=compiler still reach
        // the compiler alone, which is what someone parsing its output would want.
        if (!toCompiler && "help".equals(first)
            || (!forcedCompiler && ("--help".equals(first) || "-h".equals(first)) && argv.size() == 1)) {
            wrapperHelp();
            System.out.println();
            System.out.println("---- Flix " + lock.version() + " ".repeat(3)
                             + "(./flixw -- --help for this alone) ----");
            System.out.println();
            launch(jvm.exe(), opts, jar, List.of("--help"));
            return;                                  // launch exits; this is for the reader
        }

        if (!toCompiler) {
            if (forcedWrapper && compilerVerbs.contains(first) && trace())
                System.err.println("flixw: " + q(first) + " \u2192 wrapper " + WRAPPER_VERSION
                                 + " (forced by FLIX_BACKEND=wrapper; compiler " + lock.version()
                                 + " also implements it)");
            else routingNotice(first, lock.version());
            wrapperVerb(first, forward.subList(1, forward.size()), root, lock, jar, jvm, compilerVerbs);
            return;
        }
        if (first != null && WRAPPER_VERBS.contains(first) && compilerVerbs.contains(first))
            System.err.println("flixw: note: compiler " + lock.version() + " now implements "
                             + q(first) + "; the wrapper implementation is deprecated"
                             + " and will be removed in the next wrapper release");

        launch(jvm.exe(), opts, jar, forward);
    }

    /**
     * Which side handled a verb, under FLIXW_TRACE only.
     *
     * It used to print on every wrapper-handled command, and it told the caller what they
     * had already said: typing `./flixw doctor` and being told that doctor went to the
     * wrapper is not news. Worse, it read as a warning -- something had happened worth
     * mentioning -- when nothing had. The hot path was already silent; now the rest is
     * too, and the routing is still visible to anyone debugging it.
     */
    static void routingNotice(String verb, String compilerVersion) {
        if (!trace()) return;
        System.err.println("flixw: " + q(verb) + " \u2192 wrapper " + WRAPPER_VERSION
                         + " (pinned compiler " + compilerVersion + " does not implement it)");
    }

    static void wrapperHelp() {
        System.out.println("flixw " + WRAPPER_VERSION + " -- repository-local Flix bootstrap");
        System.out.println();
        System.out.println("  ./flixw help | --help | -h       this table, then the compiler's own help");
        System.out.println("  ./flixw <compiler verb> [args]   run the pinned stock compiler");
        System.out.println("  ./flixw -- <args>                forced compiler pass-through");
        System.out.println("  ./flixw pin [<owner>/<repo>] [<version>] [--java <v>]  write the lock");
        System.out.println("  ./flixw pin --refresh            rewrite the lock in this release's shape");
        System.out.println("  ./flixw info                     project, compiler, java, cache");
        System.out.println("  ./flixw doctor [--fix]           info, plus every check, with a verdict");
        System.out.println("  ./flixw validate                 the checks alone, for CI");
        System.out.println("  ./flixw wrapper [--help | --version | --upgrade | --install-jdk"
                         + " | --schema | --completion]");
        System.out.println();
        System.out.println("  FLIX_JAR=<path> ./flixw <verb>   run a locally built compiler"
                         + " (unverified; see ./.envrc.example)");

        System.out.println();
        System.out.println("cache            " + cacheHome());
        System.out.println("java             " + System.getProperty("java.home")
                         + "  (" + Runtime.version().feature() + ")");
        // Offline-only enrichment: never downloads, never launches the compiler.  The
        // routing table is shown from what is already on disk, so `wrapper --help` keeps
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
        System.out.println("pass-through     ./flixw -- <args>");
    }

    /**
     * The .java file this stage 0 was launched from, or null when it is running as the
     * compiled class out of the cache.
     *
     * A source launch knows its own path, and that knowledge outranks FLIXW_SOURCE.
     * FLIXW_SOURCE is the shim telling the *compiled* stage 0 which source it was built
     * from; it says nothing about a stage 0 launched by path. `wrapper --upgrade` hands
     * its environment to a freshly downloaded stage 0 in a temporary directory, which is
     * a different file in a different project-less place -- and believing the inherited
     * variable there anchored the new wrapper in the old project, where a lock exists, so
     * `install` was no longer first contact and went to the compiler:
     * `Unrecognized file extension: 'install'.` Every upgrade failed that way.
     */
    static Path sourceLaunchPath() {
        try {
            Path loc = Paths.get(flixw.class.getProtectionDomain().getCodeSource()
                                 .getLocation().toURI());
            if (Files.isRegularFile(loc) && loc.toString().endsWith(".java")) return loc;
        } catch (Exception ignored) { }
        return null;
    }

    static Path selfSource() {
        Path launched = sourceLaunchPath();
        if (launched != null) return launched;
        String s = env("FLIXW_SOURCE");
        if (s != null) return Paths.get(s);
        try {
            Path loc = Paths.get(flixw.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path p = loc.resolve("source.path");                      // compiled stage 0
            if (Files.isRegularFile(p)) return Paths.get(Files.readString(p).trim());
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Leaves the shim a note saying which JDK this project resolved to, so the next run can
     * start on it instead of starting on whatever `java` is first on PATH and then
     * relaunching. Without it a project that pins a Java the machine does not use by
     * default pays a whole extra stage 0 -- about 100ms -- on every command.
     *
     * Machine-specific, therefore not committed: `.flixw/.gitignore` keeps `local/` out of
     * git, and flixw writes that file itself rather than editing the project's own
     * .gitignore. It names an executable the shim will run, which sounds like a new trust
     * boundary and is not one: anyone who can write `.flixw/local/` can edit `./flixw`
     * itself, which is simpler and does more.
     *
     * Every failure here is discarded. A read-only checkout, a directory someone deleted,
     * a race with another run -- none of it is worth a diagnostic for a cache whose only
     * job is to save a process start, and whose absence is already the old behaviour.
     */
    static void recordJava(Path root, Path exe) {
        Path marker = root.resolve(WRAPPER_DIR).resolve("local").resolve("java");
        try {
            // Normalized, so the shim can reject anything with a `..` in it without ever
            // refusing a path flixw itself wrote.
            String want = exe.toAbsolutePath().normalize() + System.lineSeparator();
            if (Files.isRegularFile(marker)
                && Files.readString(marker, StandardCharsets.UTF_8).equals(want)) return;
            Files.createDirectories(marker.getParent());
            writeAtomic(marker, want);
        } catch (IOException | RuntimeException ignored) { }
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
