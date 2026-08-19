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

    static final String WRAPPER_VERSION = "0.24.1";
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
        List.of("pin", "info", "doctor", "validate", "help", "plugin", "task");

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
        // Only when stripping the tag prefix would actually leave a version. `pin` accepts
        // that form outright, so anything reaching here still spelled with a leading `v` is
        // either the manifest -- Flix's field, which takes x.x.x alone -- or not a version
        // at all, and telling someone to strip a `v` from `vNext` names the wrong problem.
        if (v.startsWith("v") && SEMVERISH.matcher(v.substring(1)).matches())
            throw w002(where + ": strip the leading 'v' from " + q(v));
        if (!SEMVERISH.matcher(v).matches())
            throw w002(where + ": " + q(v) + " is not an exact version"
                     + "\n       ranges, wildcards and empty suffixes are not accepted");
        return v;
    }

    /**
     * Accepts the release tag where a version is expected: {@code v0.75.2} means
     * {@code 0.75.2}.
     *
     * GitHub shows the tag, not the version.  The releases page, the tag list, the archive
     * links and the asset URLs all read {@code v0.75.2}, so copying from where the versions
     * actually are gets you the tag every time -- and flixw itself builds {@code "v" +
     * version} to construct that URL, so it already holds that the two name one release.
     * Refusing the form flixw prints into its own URLs made the user do a normalization the
     * wrapper was doing anyway.
     *
     * Only ahead of a digit, so {@code vNext} is still a bad version rather than the
     * version {@code Next}, and the diagnostic keeps naming the real problem.
     *
     * Deliberately not applied to {@code [package].flix}: that field is Flix's, and Flix
     * accepts {@code x.x.x} alone.  Tolerating a tag there would let flixw read a manifest
     * that Flix itself rejects, which is a worse outcome than the error it replaces.
     */
    static String stripTagPrefix(String v) {
        return v.length() > 1 && v.charAt(0) == 'v' && Character.isDigit(v.charAt(1))
             ? v.substring(1) : v;
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
        new LockField("compiler", "reported_version", false, SEMVERISH.pattern(),
            "the version that JAR reports of itself, captured when it was pinned"),
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
        // [plugins.<name>] is a dynamic table -- one sub-table per plugin, each the same
        // shape -- which LOCK_SCHEMA's fixed table-and-key model has no way to describe,
        // so it is hand-appended here rather than rendered from it.
        props.add(pluginsTableJson("    "));
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

    /**
     * {@code [plugins.<name>]} for every name at once: an object whose keys are arbitrary
     * (plugin names) but whose values all share one shape, which JSON Schema expresses
     * with {@code additionalProperties} as a sub-schema rather than {@code properties}.
     */
    static String pluginsTableJson(String indent) {
        String i2 = indent + "    ", i3 = i2 + "  ", i4 = i3 + "  ", i5 = i4 + "  ";
        return indent + "\"plugins\": {\n"
             + indent + "  \"type\": \"object\",\n"
             + indent + "  \"description\": " + jsonString(
                 "Plugins this project declares -- installed by `flixw plugin install`,"
               + " which writes this table; never a fetch instruction on its own.") + ",\n"
             // A name is a single path segment: it reaches <cache>/plugins/<name>/ before
             // anything else about the entry is even read, so the schema constrains it as
             // strictly as stage 0's own validPluginName() does. additionalProperties
             // alone would bound only the *value* shape, not which keys are allowed, and
             // would silently accept a plugin name a conforming editor should flag.
             + indent + "  \"patternProperties\": {\n"
             + i2 + jsonString("^" + PLUGIN_NAME_PATTERN + "$") + ": {\n"
             + i3 + "\"type\": \"object\",\n"
             + i3 + "\"additionalProperties\": false,\n"
             + i3 + "\"required\": [\"version\", \"sha256\"],\n"
             + i3 + "\"properties\": {\n"
             + i4 + "\"version\": {\n"
             + i5 + "\"type\": \"string\",\n"
             + i5 + "\"description\": \"the plugin version last installed\",\n"
             + i5 + "\"pattern\": " + jsonString("^" + SEMVERISH.pattern() + "$") + "\n"
             + i4 + "},\n"
             + i4 + "\"sha256\": {\n"
             + i5 + "\"type\": \"string\",\n"
             + i5 + "\"description\": \"the SHA-256 of the installed artifact:"
                       + " 64 lowercase hex digits\",\n"
             + i5 + "\"pattern\": \"^[0-9a-f]{64}$\"\n"
             + i4 + "},\n"
             + i4 + "\"source\": {\n"
             + i5 + "\"type\": \"string\",\n"
             + i5 + "\"description\": \"where this plugin came from;"
                       + " informational, never fetched from automatically\"\n"
             + i4 + "}\n"
             + i3 + "}\n"
             + i2 + "}\n"
             + indent + "  },\n"
             + indent + "  \"additionalProperties\": false\n"
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

    record Lock(String version, String url, String sha256, String repo, String java,
                String reportedVersion, Map<String, PluginDep> plugins) {}

    /**
     * A plugin dependency the project declares: not a fetch instruction, only a record of
     * what {@code flixw plugin install} verified when someone last ran it. {@code source}
     * is informational, the way a fork's {@code repo} field already is -- {@code pin}
     * never reads it to download anything.
     */
    record PluginDep(String version, String sha256, String source) {}

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

    /**
     * A quoted TOML value, fully unescaped -- unlike {@link #unquote}, which every
     * existing caller uses for a version, URL or digest, none of which can legally
     * contain a backslash, so stripping the outer quotes has always been the whole job.
     * A task's command string is arbitrary shell syntax, where `\"` and embedded quotes
     * are the ordinary case, so this processes TOML's basic-string escapes for real. A
     * single-quoted (literal) string has none to process by definition -- exactly the
     * TOML feature that lets a task avoid this entirely by not using `"..."`.
     */
    static String unquoteToml(String v, String where) {
        if (v.length() < 2 || v.charAt(0) != v.charAt(v.length() - 1)
            || (v.charAt(0) != '"' && v.charAt(0) != '\''))
            throw w002(where + ": " + q(v) + " must be a quoted string");
        String inner = v.substring(1, v.length() - 1);
        if (v.charAt(0) == '\'') return inner;           // literal string: no escapes
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c != '\\') { b.append(c); continue; }
            if (i + 1 >= inner.length()) throw w002(where + ": trailing backslash in " + q(v));
            char n = inner.charAt(++i);
            switch (n) {
                case '"'  -> b.append('"');
                case '\\' -> b.append('\\');
                case 'b'  -> b.append('\b');
                case 't'  -> b.append('\t');
                case 'n'  -> b.append('\n');
                case 'f'  -> b.append('\f');
                case 'r'  -> b.append('\r');
                // The lowercase and uppercase Unicode escapes differ only in digit count;
                // malformed hex and an out-of-range or surrogate-half code point are both
                // "not a valid escape" here rather than an uncaught NumberFormatException
                // or IllegalArgumentException -- a hand-edited tasks.toml or lock.toml is
                // exactly where that kind of typo shows up, and it must answer with
                // FLIXW002, not a stack trace. (Spelled out rather than written literally,
                // because the sequence backslash-u is itself a Java source escape.)
                case 'u', 'U' -> {
                    int len = n == 'u' ? 4 : 8;
                    if (i + len >= inner.length())
                        throw w002(where + ": incomplete \\" + n + " escape in " + q(v));
                    String hex = inner.substring(i + 1, i + 1 + len);
                    try {
                        int cp = Integer.parseInt(hex, 16);
                        if (!Character.isValidCodePoint(cp) || (cp >= 0xD800 && cp <= 0xDFFF))
                            throw new NumberFormatException();
                        b.appendCodePoint(cp);
                    } catch (NumberFormatException e) {
                        throw w002(where + ": \\" + n + hex
                                 + " is not a valid Unicode code point in " + q(v));
                    }
                    i += len;
                }
                default -> throw w002(where + ": invalid escape " + q("\\" + n) + " in " + q(v));
            }
        }
        return b.toString();
    }

    static Path lockPath(Path root) { return root.resolve(WRAPPER_DIR).resolve("lock.toml"); }

    static Path tasksPath(Path root) { return root.resolve(WRAPPER_DIR).resolve("tasks.toml"); }

    /**
     * `.flixw/tasks.toml`: npm-`scripts`-style name-to-shell-string pairs, hand-edited and
     * committed like `lock.toml` itself, but never generated or rewritten by `pin` or
     * `doctor --fix` -- unlike the lock, this file is the human's to write, so it carries
     * no `#:schema` directive and no "generated" header. Flat by design: a table would
     * invite grouping that a shell string running through `sh -c`/`cmd /c` gets no benefit
     * from, and it is one fewer thing {@link #tomlScan}'s callers here have to check for.
     */
    static Map<String, String> readTasks(Path root) {
        Path f = tasksPath(root);
        if (!Files.isRegularFile(f)) return Map.of();
        String text;
        try { text = Files.readString(f, StandardCharsets.UTF_8); }
        catch (IOException e) { throw w002("cannot read " + f + ": " + why(e)); }
        String w = f.toString();
        Map<String, String> out = new LinkedHashMap<>();
        for (TomlEntry e : tomlScan(text, w).entries()) {
            if (!e.table().isEmpty())
                throw w002(w + ": [" + e.table() + "] -- tasks.toml holds only"
                         + " name = \"command\" pairs, no tables");
            if (e.multiline())
                throw w002(w + ": " + q(e.key()) + " must be a single-line string");
            out.put(e.key(), unquoteToml(e.value(), w));
        }
        return out;
    }

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
                        got.get("compiler.repo"), j, got.get("compiler.reported_version"),
                        readPlugins(text, w));
    }

    /**
     * {@code [plugins.<name>]} tables, keyed by name -- a dynamic set `LOCK_SCHEMA`'s
     * fixed-table-and-key model cannot describe, so it is read directly from
     * {@link #tomlScan} rather than through {@link #readLockFields}. Each declared
     * plugin needs `version` and `sha256`; `source` is optional and never used to fetch
     * anything, only shown to a reader deciding what to install.
     */
    static Map<String, PluginDep> readPlugins(String text, String where) {
        Map<String, String> version = new LinkedHashMap<>(), sha = new LinkedHashMap<>(),
                            source = new LinkedHashMap<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        Set<String> knownKeys = Set.of("version", "sha256", "source");
        for (TomlEntry e : tomlScan(text, where).entries()) {
            if (!e.table().startsWith("plugins.")) continue;
            String name = e.table().substring("plugins.".length());
            // Fails closed, the same way an invalid sha256 or version does below: a lock
            // is exactly as attacker-controlled as anything else committed to a repo, and
            // a plugin name reaches a filesystem path in resolvePlugin/pluginDir.
            if (!validPluginName(name))
                throw w002(where + ": [plugins." + name + "] is not a valid plugin name"
                         + " -- lowercase letters, digits and hyphens, starting with a letter");
            // An unrecognized key inside a known table is advisory everywhere else in this
            // file (unknownLockKeys / FLIXW011) -- a lock a newer flixw wrote is the
            // ordinary way to meet one. Skipped *before* unquoting, so a future key
            // holding a non-string value (an integer, a bare array) is exactly as
            // survivable as a future key holding a string: neither is ever parsed here.
            if (!knownKeys.contains(e.key())) continue;
            if (!seenKeys.add(name + "." + e.key()))
                throw w002(where + ": duplicate " + q(e.key()) + " key in [plugins." + name + "]");
            if (e.multiline())
                throw w002(where + ": [plugins." + name + "] " + q(e.key())
                         + " must be a single-line string");
            String v = unquoteToml(e.value(), where);
            switch (e.key()) {
                case "version" -> version.put(name, v);
                case "sha256" -> sha.put(name, v);
                case "source" -> source.put(name, v);
            }
        }
        Map<String, PluginDep> out = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        names.addAll(version.keySet());
        names.addAll(sha.keySet());
        for (String name : names) {
            String v = version.get(name);
            if (v == null)
                throw w002(where + ": [plugins." + name + "] is missing version");
            if (!SEMVERISH.matcher(v).matches())
                throw w002(where + ": [plugins." + name + "] version is " + q(v)
                         + "\n       expected x.y.z, optionally with a prerelease and"
                         + " build metadata");
            String d = sha.get(name);
            if (d == null)
                throw w002(where + ": [plugins." + name + "] is missing sha256");
            if (!d.matches("[0-9a-f]{64}"))
                throw w002(where + ": [plugins." + name + "] sha256 is " + q(d)
                         + "\n       expected 64 lowercase hex digits");
            out.put(name, new PluginDep(v, d, source.get(name)));
        }
        return out;
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
            // [plugins.<name>] is a dynamic table LOCK_SCHEMA cannot enumerate by name;
            // readPlugins() is the authority on which of its keys it actually reads.
            boolean known = e.table().startsWith("plugins.")
                          && List.of("version", "sha256", "source").contains(e.key());
            if (!known)
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
        + "\n          or: ./flixw pin <owner>/<repo>@<version>   (one token, a fork)"
        + "\n          or: ./flixw pin --refresh   (rewrite the lock in this release's shape)";

    /** A single path segment, nothing else -- in particular no `.`, so a name can never
     *  climb out of {@code <cache>/plugins/} the way {@code ..} would. Checked at every
     *  point a name reaches a path: the three CLI entry points, and a lock's own {@code
     *  [plugins.<name>]} table, which is attacker-controlled the moment a lock is. */
    static final String PLUGIN_NAME_PATTERN = "[a-z][a-z0-9-]*";

    static boolean validPluginName(String name) { return name.matches(PLUGIN_NAME_PATTERN); }

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
                // A fork's own release page shows owner/repo@tag nowhere -- GitHub does
                // not write it that way -- but it is the one-token shape npm and Go
                // modules train people to reach for by habit, and it costs nothing to
                // accept alongside the two-token form.
                int at = a.indexOf('@');
                if (at < 0) {
                    repo = checkRepo(a, "pin");
                } else {
                    if (version != null) throw w009("pin: two versions given");
                    repo = checkRepo(a.substring(0, at), "pin");
                    version = a.substring(at + 1);
                    if (version.isEmpty())
                        throw w002("pin: " + q(a) + " -- a version must follow '@'"
                                 + "\n       for example: " + a.substring(0, at) + "@0.75.2");
                }
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
        // Normalized before validation, so the lock records the version rather than the tag
        // it was typed as, and two spellings of one release cannot produce two locks.
        if (version != null) version = validateVersion(stripTagPrefix(version), "pin");
        if (repo == null) repo = existing != null && existing.repo() != null
                               ? existing.repo() : UPSTREAM_REPO;
        return new Pin(repo, version, java, clearJava != null, false);
    }

    // ---- acquisition ------------------------------------------------------

    /** True when a path names something inside the cache flixw fills with pinned compilers. */
    static boolean insideCompilerCache(Path jar) {
        return jar.normalize().startsWith(cacheHome().resolve("compilers").normalize());
    }

    /**
     * Says so when {@code FLIX_JAR} names a compiler out of flixw's own cache.
     *
     * A mismatch between the override and the lock is the *ordinary* case -- the override
     * exists to run a jar you built yourself, which is not the pinned one and is not meant
     * to be -- so it is reported where state is printed, and not on every run.
     *
     * Pointing it inside {@code <cache>/compilers/} is different, and is always a mistake.
     * Those names are content-addressed, {@code flix-<version>-<sha256>.jar}, so **the path
     * changes every time the project is re-pinned**. An override set once to whatever
     * `info` reported that day goes on naming the superseded artifact afterwards, and the
     * project quietly builds with the compiler it used to pin. Nothing else in flixw could
     * catch it: the digest guard is switched off by the override, and the version check
     * passes because two builds of one release share a canonical version.
     *
     * Matching the lock is a mistake too, only a harmless one: it names the jar flixw would
     * have chosen anyway, and it will stop doing that at the next pin.
     */
    static void reportOverrideGap(Lock lock, Path jar) {
        if (lock == null || !insideCompilerCache(jar)) return;
        String got = sha256(jar);
        if (got.equals(lock.sha256()))
            w010("FLIX_JAR names flixw's own cache entry for the pinned compiler."
               + "\n          That is the jar flixw would have used anyway, and the name"
               + " changes at the next pin."
               + "\n          run: unset FLIX_JAR");
        else
            w010("FLIX_JAR names a compiler from flixw's cache that is NOT the pinned one."
               + "\n          override " + got.substring(0, 16) + "...  lock pins "
               + lock.sha256().substring(0, 16) + "..."
               + "\n          Cache names carry the digest, so this path is an earlier pin"
               + " left behind by a re-pin."
               + "\n          run: unset FLIX_JAR   (or ./flixw pin <that version> to make"
               + " it the pin)");
    }

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
        // `pin` already wrote this once; every ordinary run re-affirms it, so a cache an
        // older flixw already filled -- one that predates this record entirely -- backfills
        // on its very next use, and `info -v` has an answer for every entry, not only today's.
        writePinRecord(lock.sha256(), lock.repo() == null ? UPSTREAM_REPO : lock.repo(), lock.version());
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

    /**
     * Nothing usable was found: say how to fix it, and stop.  Never returns -- the
     * {@code Jvm} result type only exists so the sole caller can {@code return} it.
     *
     * <p>This used to prompt and then download a JDK inline, and that was the wrong shape
     * twice over.  It put ~200 lines of third-party metadata parsing, archive handling and
     * per-platform policy inside the file that is loaded on every single invocation, to
     * serve the rarest path there is; and it made an automatic network fetch the default
     * answer to a missing dependency, in a wrapper whose entire argument is that it
     * fetches only what a lock named and a digest confirmed.  A precise diagnostic naming
     * the repair is the better answer, and it is the one every other missing-dependency
     * case here already gives.  Provisioning is still available -- explicitly, as
     * {@code ./flixw wrapper --install-jdk} -- and lives in a verified companion asset.
     */
    static Jvm noJavaFound(int self, String pin) {
        // A pinned project is told about the pinned feature release, not the wrapper's
        // floor: naming 21 to a project that asked for 22 recommends installing something
        // that still cannot be selected.
        int want = pin == null ? MIN_JAVA : feature(pin);
        System.err.println(pin == null
            ? "FLIXW003: no Java in [" + MIN_JAVA + ", " + TESTED_CEILING
              + "] found; this JVM is " + self
            : "FLIXW003: no Java " + pin + " found, which " + WRAPPER_DIR
              + "/lock.toml pins; this JVM is " + self);
        jdkInstructions(want);
        System.err.println("       or run: ./flixw wrapper --install-jdk   (fetches a verified"
                         + " Temurin " + want + " into flixw's own cache)");
        throw w003("no usable Java; see the instructions above");
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
        Path exe = runJdkAsset(want == null ? MIN_JAVA : want);
        int f = probe(exe);
        if (f < MIN_JAVA)
            throw w003("the JDK just installed reports Java " + f + ", below " + MIN_JAVA);
        System.out.println(exe);
        System.err.println("flixw: Temurin Java " + f + " is installed.");
        System.err.println("       flixw will find it from now on; export JAVA_HOME="
                         + exe.getParent().getParent() + " to use it elsewhere.");
    }

    /**
     * Fetches, verifies and runs the JDK provisioner, and returns the {@code java} it
     * installed -- the one line the asset prints on stdout.
     *
     * <p>Run as a child rather than source-launched in place because it is the only way
     * to keep this JVM's exit status: the asset uses flixw's own {@code FLIXWnnn} codes
     * and advisory exits, and a caller must not be able to tell that the work moved out
     * of stage 0.  Its stderr is inherited so its progress lines land where every other
     * flixw diagnostic does.
     */
    static Path runJdkAsset(int feature) {
        Path asset = ensureAsset(JDK_ASSET);
        Path javaExe = exeIn(System.getProperty("java.home"));
        ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), asset.toString(),
                Integer.toString(feature), cacheHome().toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        try {
            Process proc = pb.start();
            String out;
            try (InputStream in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            int rc = awaitWithReaper(proc);
            // The asset speaks flixw's own diagnostic language and has already printed a
            // FLIXWnnn line to the inherited stderr. Adding a second code here would
            // report one failure twice, and the outer one would be the less specific of
            // the two -- so its status is propagated and nothing further is said.
            if (rc != 0) System.exit(rc);
            if (out.isEmpty()) throw w003("the JDK provisioner named no java");
            return Paths.get(out);
        } catch (IOException e) {
            throw w005("cannot run " + asset + ": " + why(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw w003("JDK install interrupted");
        }
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
        String help;
        try { help = captureHelp(javaExe, jar); v = captureVerbs(help, jar); }
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

    /** What a lock pinned this digest as: the exact tag and repository, not what the compiler
     *  chooses to say about itself. A fork routinely builds without embedding its own build
     *  metadata -- {@code stable.names.2} never appears in {@code --help} -- so this is the
     *  only place that information survives once the project moves on to the next pin. */
    record PinRecord(String repo, String version) {}

    /** Beside the version record and keyed the same way, so a re-pin gets a fresh one. */
    static Path pinRecordFile(String identity) {
        return cacheHome().resolve("verbs").resolve(identity + ".pin");
    }

    /**
     * What {@code acquire} last wrote for this digest, if any -- a file read, never a
     * subprocess or a re-parse of anyone's lock. Every project that ever acquired this
     * exact jar wrote the same repo and version here, since the digest is the same bytes
     * either way; the last writer is as good as any.
     */
    static PinRecord cachedPinRecord(String identity) {
        try {
            List<String> lines = Files.readAllLines(pinRecordFile(identity), StandardCharsets.UTF_8);
            return lines.size() < 2 ? null : new PinRecord(lines.get(0), lines.get(1));
        } catch (IOException e) { return null; }
    }

    /** Written by {@code pin} itself, the moment it settles on a digest -- not deferred to
     *  the next {@link #acquire}, which may never come: a project that pins one build and
     *  then immediately pins another runs no other command against the first digest in
     *  between, and a record only {@code acquire} writes would never see it. {@code
     *  acquire} re-affirms the same record on every later run regardless, so a cache
     *  populated by an older flixw that predates this file entirely still backfills on its
     *  very next use rather than staying silent forever. */
    static void writePinRecord(String identity, String repo, String version) {
        Path f = pinRecordFile(identity);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), ".pin-", ".part");
            Files.writeString(tmp, repo + "\n" + version + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tr("cannot cache the pin record at " + f + ": " + e.getMessage());
        }
    }

    /** The compiler's `--help`, bounded, as text.  The two parses read it separately. */
    static String captureHelp(Path javaExe, Path jar) {
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
        return out;
    }

    static List<String> captureVerbs(String out, Path jar) {
        List<String> verbs = parseVerbs(out);
        if (verbs.size() < 3)
            throw w009("cannot parse verbs from `flix --help` of " + jar
                     + " (got " + verbs.size() + " candidate(s))");
        return verbs;
    }

    /**
     * A version token standing on its own, rather than one buried inside a longer word.
     * Built from {@link #SEMVERISH} so the two cannot drift into disagreeing about what a
     * version is.
     */
    static final Pattern VERSION_TOKEN = Pattern.compile(
        "(?<![0-9A-Za-z.+-])(" + SEMVERISH.pattern() + ")(?![0-9A-Za-z.+-])");

    /**
     * The version the compiler says it is, read from the header of its own help.
     *
     * Free, because {@link #verbs} already runs {@code --help} and the version is sitting
     * in the text it throws away.  Both renderers put it in the first lines and neither
     * puts it in the same place: scopt writes {@code The Flix Programming Language 0.75.2}
     * on one line, picocli writes the product name and the version on the next.  Rather
     * than encode either layout -- a fork may rename the product string, and one did move
     * the version to its own line -- take the first standalone version token in the header.
     *
     * The header, not the whole screen: an option's default or an example further down is
     * text about something else, and reading one as the compiler's identity would produce
     * a mismatch report about nothing.
     *
     * @return the reported version, or null when the header carries none -- which is not an
     *         error, only the absence of a second opinion
     */
    static String parseReportedVersion(String help) {
        int seen = 0;
        for (String line : help.split("\r?\n")) {
            if (line.isBlank()) continue;
            if (++seen > HEADER_LINES) return null;
            Matcher m = VERSION_TOKEN.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** How much of a help screen counts as the header for {@link #parseReportedVersion}. */
    static final int HEADER_LINES = 3;

    /**
     * Asks a JAR what version it says it is, for the lock.  Best-effort in every
     * direction: null when no JVM can be selected, when the JAR will not run, or when its
     * header carries no version token.
     *
     * <p>Nothing here may throw. This runs inside {@code pin}, which is the documented
     * repair for a project that cannot reach a compiler -- a machine with no usable Java
     * must still be able to write a lock, and losing the second opinion is a far smaller
     * loss than losing the ability to pin at all.
     */
    static String captureReportedVersion(Path jar, String javaPin) {
        try {
            Jvm jvm = selectJava(javaPin);
            return parseReportedVersion(captureHelp(jvm.exe(), jar));
        } catch (RuntimeException e) {          // Fail is one; so is anything selectJava trips over
            tr("cannot ask the compiler its version: " + e.getMessage());
            return null;
        }
    }

    /**
     * Says so when the two version strings recorded about one compiler disagree.
     *
     * <p>The digest settles *which bytes* the lock pins, and nothing settles that those
     * bytes are the release it names. A mislabelled release asset -- a fork that tagged
     * {@code v0.75.4} over a 0.75.2 build, an upstream re-upload -- would otherwise be
     * pinned, verified and run without a word.
     *
     * <p>The compiler is *asked* once, by {@code pin}, and its answer is recorded in the
     * lock beside the digest. Every later run re-hashes those bytes anyway, so a digest
     * that still matches is a version that still matches: a per-run second opinion would
     * re-derive what the digest already proves, at the cost of a subprocess and a cache
     * file. The comparison, unlike the capture, is free once both strings are in the lock,
     * so it stays on every run -- it is what catches a lock edited or merged after pin
     * wrote it, which is exactly the case pin-time checking cannot see.
     *
     * <p>Compared through {@link #canonical}, because build metadata identifies a build
     * rather than a release: a compiler built from {@code 0.75.3+stable.names.3} reporting
     * {@code 0.75.3} is agreeing, not disagreeing.
     *
     * <p>{@code FLIXW010}: printed, never fatal. Pinning a mislabelled asset on purpose is
     * legitimate, and the lock records both strings so {@code validate} can decide what a
     * build should do about it.
     *
     * @param lead how the disagreement is introduced, which differs between the moment of
     *     pinning ("the JAR just pinned") and every run afterwards ("the pinned compiler")
     */
    static void reportVersionGap(String lead, String pinned, String reported) {
        if (reported == null || canonical(reported).equals(canonical(pinned))) return;
        w010(lead + " reports itself as " + q(reported) + ", but the lock pins " + q(pinned)
           + "\n          the digest still pins these exact bytes; what is in doubt is the"
           + " version they were published under"
           + "\n          run: ./flixw pin " + reported + "   (to pin what is actually here)");
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

    // ---- wrapper verbs ----------------------------------------------------

    static void wrapperVerb(String verb, List<String> rest, Path root, Lock lock, Path jar,
                            Jvm jvm, List<String> compilerVerbs) {
        switch (verb) {
            case "pin" -> {
                if (rest.isEmpty())
                    throw w009(PIN_USAGE);
                pin(root, parsePin(rest, lock));
            }
            // Reached with no lock yet -- there is no compiler to ask for its half, so
            // this is the routing table alone. Once a project is pinned, the full
            // `help`/`--help` merge in realMain runs instead and this case is not hit.
            case "help" -> {
                if (!rest.isEmpty())
                    throw w008("./flixw help: unknown argument " + q(rest.get(0))
                             + "\n       usage: ./flixw help");
                wrapperHelp();
            }
            // info reports, validate judges, doctor does both -- which is what the word
            // means everywhere else, and what this one did not do: it printed twelve lines
            // of state, noticed nothing, and exited 0 with a shim that had been edited.
            case "info" -> {
                boolean verbose = rest.contains("--verbose") || rest.contains("-v");
                for (String a : rest)
                    if (!a.equals("--verbose") && !a.equals("-v"))
                        throw w008("./flixw info: unknown option " + q(a)
                                 + "\n       usage: ./flixw info [--verbose | -v]");
                report(root, lock, jar, jvm, compilerVerbs, askedVersion(lock));
                if (verbose) { System.out.println(); listCache(lock, jvm); }
            }
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
                if (fix) { updateWrapper(root); System.out.println(); }   // asset + lock
                report(root, lock, jar, jvm, compilerVerbs, askedVersion(lock));
                System.out.println();
                int bad = check(root, lock, jar, jvm);
                if (bad > 0)
                    throw w009(bad + " problem(s); ./flixw doctor --fix repairs the wrapper"
                             + " files, ./flixw pin <version> repairs a drifted lock");
            }
            // A namespace, not one top-level verb per plugin: `plugin metrics` can never
            // collide with a compiler verb or another plugin's name, because neither is
            // ever reachable as a bare top-level word. install/list/remove manage the
            // machine-wide cache; anything else is treated as a plugin name to invoke.
            case "plugin" -> {
                if (rest.isEmpty()) throw w009(PLUGIN_USAGE);
                String sub = rest.get(0);
                List<String> args = rest.subList(1, rest.size());
                switch (sub) {
                    case "install" -> pluginInstall(root, args);
                    case "list" -> pluginList();
                    case "remove" -> pluginRemove(args);
                    default -> {
                        ResolvedPlugin p = resolvePlugin(sub, lock);
                        // Every invocation, not only install: a digest verified once does
                        // not become a safety review by being run again, and the reader
                        // of a build log two months from now needs the same warning the
                        // installer saw.
                        System.err.println("flixw: running plugin " + sub + " " + p.version()
                                         + " (" + p.sha256().substring(0, 16) + "...)");
                        System.err.println("       this is 3rd-party code, not audited by flixw");
                        Jvm resolvedJvm = jvm != null ? jvm : selectJava(null);
                        Map<String, String> env = pluginEnv(root, lock, resolvedJvm, jar, sub, p, args);
                        runArtifact(p.artifact(), resolvedJvm.exe(), jar, args, env);
                    }
                }
            }
            // Npm's `scripts`, not a new verb per task: a project's own tasks.toml, never
            // fetched, never installed, no trust question -- it is a shell string in a
            // file the project already trusts, the same as any other checked-in script.
            case "task" -> {
                Map<String, String> tasks = readTasks(root);
                if (rest.isEmpty()) {
                    if (tasks.isEmpty()) System.out.println("(no tasks in " + tasksPath(root) + ")");
                    else tasks.keySet().forEach(System.out::println);
                    return;
                }
                String name = rest.get(0);
                String cmd = tasks.get(name);
                if (cmd == null)
                    throw w009("no task " + q(name) + " in " + tasksPath(root)
                             + (tasks.isEmpty() ? "" : "\n       known tasks: "
                               + String.join(", ", tasks.keySet())));
                runTask(cmd, rest.subList(1, rest.size()));
            }
            default -> throw w009("no wrapper implementation for " + q(verb));
        }
    }

    // ---- plugins ------------------------------------------------------------

    static final String PLUGIN_USAGE =
          "usage: ./flixw plugin install <name> <version> <url> [--sha256 <digest>]"
        + "\n          ./flixw plugin list"
        + "\n          ./flixw plugin remove <name>"
        + "\n          ./flixw plugin <name> [args...]";

    static Path pluginsDir() { return cacheHome().resolve("plugins"); }

    static Path pluginDir(String name, String version, String sha256) {
        return pluginsDir().resolve(name).resolve(version + "-" + sha256);
    }

    /**
     * The only path a plugin's bytes reach the machine -- explicit, one attempt, same
     * shape as {@link #acquire} for the compiler. `https://` is verified the ordinary
     * way; `file://` copies a local path directly, for local plugin development and for
     * testing this without a public URL. Neither is fetched because a lock named it: a
     * project's {@code [plugins.<name>]} entry is read only by {@link #resolvePlugin}, never by
     * this, so nothing about running `pin` or `doctor` can trigger a download here.
     */
    static void pluginInstall(Path root, List<String> args) {
        if (args.size() < 3) throw w009(PLUGIN_USAGE);
        String name = args.get(0), version = args.get(1), url = args.get(2);
        if (!validPluginName(name))
            throw w009("plugin name " + q(name) + " must be lowercase letters, digits and"
                     + " hyphens, starting with a letter");
        if (!version.matches(SEMVERISH.pattern()))
            throw w009("plugin version " + q(version) + " must look like x.y.z"
                     + " (optionally with a prerelease/build suffix)");
        String wantSha = null;
        for (int i = 3; i < args.size(); i++) {
            if ("--sha256".equals(args.get(i)) && i + 1 < args.size()) wantSha = args.get(++i);
            else throw w009("plugin install: unknown option " + q(args.get(i))
                          + "\n       " + PLUGIN_USAGE);
        }
        String format = url.endsWith(".jar") ? "jar" : url.endsWith(".java") ? "java"
                       : url.endsWith(".flix") ? "flix" : null;
        if (format == null)
            throw w009("plugin install: url must end in .jar, .java or .flix: " + q(url));
        if (!url.startsWith("https://") && !url.startsWith("file://"))
            throw w009("plugin install: refusing " + q(url) + " (must be https:// or file://)");

        Path stagingDir = pluginsDir();
        Path tmp;
        try {
            Files.createDirectories(stagingDir);
            tmp = Files.createTempFile(stagingDir, ".plugin-", ".part");
        } catch (IOException e) { throw w009("cannot prepare " + stagingDir + ": " + why(e)); }
        try {
            if (url.startsWith("file://"))
                Files.copy(Paths.get(URI.create(url)), tmp, StandardCopyOption.REPLACE_EXISTING);
            else download(url, tmp);
            String got = sha256(tmp);
            if (wantSha != null && !got.equals(wantSha))
                throw w006("digest mismatch for " + q(url) + "\n       expected " + wantSha
                         + "\n       actual   " + got);
            Path dest = pluginDir(name, version, got);
            Files.createDirectories(dest);
            Path artifact = dest.resolve("plugin." + format);
            try { Files.move(tmp, artifact, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { if (!Files.isRegularFile(artifact)) throw e; }
            System.err.println("flixw: installed plugin " + name + " " + version
                             + " (" + got.substring(0, 16) + "...)");
            // Never quieter than a fork pin's own warning: a digest says these are the
            // same bytes as last time, not that the bytes are safe. Third-party code a
            // user explicitly asked to install gets no gentler a badge than that.
            System.err.println("       this is 3rd-party code, not audited by flixw");
            recordPluginInLock(root, name, version, got, url);
        } catch (IOException e) {
            throw w009("plugin install failed: " + why(e));
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /**
     * Only when this project already has a lock: install can run before any `pin`, and
     * populating the machine-wide cache does not need one. `pin`'s own transaction shape
     * is not needed here -- a plugin table failing to write leaves the plugin installed
     * and usable, just not yet recorded, which `doctor` already reports.
     */
    static void recordPluginInLock(Path root, String name, String version, String sha256, String url) {
        Path lf = lockPath(root);
        if (!Files.isRegularFile(lf)) return;
        Lock have;
        try { have = readLock(lf); }
        catch (Fail ignored) { return; }   // a lock that does not parse is not this command's to fix
        Map<String, PluginDep> plugins = new LinkedHashMap<>(have.plugins());
        plugins.put(name, new PluginDep(version, sha256, url));
        String rewritten = lockText(WRAPPER_VERSION, have.repo() == null ? UPSTREAM_REPO : have.repo(),
            have.version(), have.url(), have.sha256(), have.reportedVersion(), have.java(), plugins);
        try { writeAtomic(lf, rewritten); System.err.println("       recorded in " + lf); }
        catch (IOException e) { tr("cannot record plugin in " + lf + ": " + e.getMessage()); }
    }

    static void pluginList() {
        Path dir = pluginsDir();
        List<Path> names = List.of();
        try (var s = Files.isDirectory(dir) ? Files.list(dir) : null) {
            if (s != null) names = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException ignored) { }
        if (names.isEmpty()) { System.out.println("(no plugins installed)"); return; }
        for (Path nameDir : names) {
            List<Path> versions = List.of();
            try (var s = Files.list(nameDir)) { versions = s.filter(Files::isDirectory).sorted().toList(); }
            catch (IOException ignored) { }
            for (Path v : versions) System.out.println(nameDir.getFileName() + "  " + v.getFileName());
        }
    }

    static void pluginRemove(List<String> args) {
        if (args.isEmpty()) throw w009(PLUGIN_USAGE);
        String name = args.get(0);
        // Load-bearing, not defensive style: without this, "flixw plugin remove .." dead-
        // reckons its way to <cache>/plugins/.. -- the cache root -- and deleteTree wipes
        // every compiler and JDK this machine has cached, not just a plugin.
        if (!validPluginName(name))
            throw w009("plugin name " + q(name) + " must be lowercase letters, digits and"
                     + " hyphens, starting with a letter");
        Path dir = pluginsDir().resolve(name);
        if (!Files.isDirectory(dir)) throw w009("plugin " + q(name) + " is not installed");
        deleteTree(dir);
        System.err.println("flixw: removed plugin " + name);
    }

    /** One resolved, digest-verified plugin build, ready to launch. */
    record ResolvedPlugin(String version, String sha256, Path artifact) {}

    /**
     * Which installed build of a plugin this invocation runs, and proof it is still the
     * bytes it was installed as. A lock's own {@code [plugins.<name>]} entry is
     * authoritative when present -- exactly what {@code plugin install} last recorded for
     * this project -- and its absence, not its presence, is what triggers a download;
     * running with no lock entry at all falls back to "whatever is installed," which only
     * works while there is exactly one.
     *
     * Re-hashed here, every run, exactly like {@link #acquire} re-hashes the compiler
     * jar: the cache directory name carries the digest install verified, but a directory
     * name is not evidence about what is inside it now. Skipping this would make a
     * plugin the one cached, executed artifact in this codebase whose bytes are trusted
     * on the strength of a install-time check alone.
     */
    static ResolvedPlugin resolvePlugin(String name, Lock lock) {
        if (!validPluginName(name))
            throw w009("plugin name " + q(name) + " must be lowercase letters, digits and"
                     + " hyphens, starting with a letter");
        Path base = pluginsDir().resolve(name);
        PluginDep want = lock == null ? null : lock.plugins().get(name);
        Path dir;
        if (want != null) {
            dir = base.resolve(want.version() + "-" + want.sha256());
            if (!Files.isDirectory(dir))
                throw w009("plugin " + q(name) + " " + want.version() + " ("
                         + want.sha256().substring(0, 12) + "...) is expected by lock.toml but"
                         + " not installed\n       run: ./flixw plugin install " + name + " "
                         + want.version() + " <url> --sha256 " + want.sha256());
        } else {
            if (!Files.isDirectory(base))
                throw w009("plugin " + q(name) + " is not installed"
                         + "\n       run: ./flixw plugin install " + name + " <version> <url>");
            List<Path> versions;
            try (var s = Files.list(base)) { versions = s.filter(Files::isDirectory).sorted().toList(); }
            catch (IOException e) { throw w009("cannot read " + base + ": " + why(e)); }
            if (versions.isEmpty())
                throw w009("plugin " + q(name) + " is not installed"
                         + "\n       run: ./flixw plugin install " + name + " <version> <url>");
            if (versions.size() > 1)
                throw w009("plugin " + q(name) + " has " + versions.size() + " versions installed,"
                         + " and lock.toml does not say which -- add a [plugins." + name + "] entry"
                         + " (./flixw plugin install " + name + " <version> <url> --sha256 <digest>)");
            dir = versions.get(0);
        }
        Path artifact = findPluginArtifact(dir);
        // The directory name is "<version>-<sha256>"; the digest is always the trailing
        // 64 hex characters, which a version cannot be mistaken for even when the version
        // itself contains a hyphen (a prerelease tag legally does).
        String dirName = dir.getFileName().toString();
        String sha256 = dirName.substring(dirName.length() - 64);
        String version = dirName.substring(0, dirName.length() - 65);
        String got = sha256(artifact);
        if (!got.equals(sha256))
            throw w006("plugin " + q(name) + " " + version + " no longer matches the digest"
                     + " it was installed with\n       expected " + sha256
                     + "\n       actual   " + got
                     + "\n       run: ./flixw plugin remove " + name
                     + "   then reinstall");
        return new ResolvedPlugin(version, sha256, artifact);
    }

    static Path findPluginArtifact(Path dir) {
        for (String ext : List.of(".jar", ".java", ".flix")) {
            Path p = dir.resolve("plugin" + ext);
            if (Files.isRegularFile(p)) return p;
        }
        throw w009("plugin cache at " + dir + " has no plugin.jar, plugin.java or plugin.flix");
    }

    // ---- tasks --------------------------------------------------------------

    /** cmd.exe's own quoting convention for one command-line word: wrap in double quotes
     *  if it needs it, doubling any quote already inside. Not a full re-implementation of
     *  cmd.exe's parser -- nothing short of one is -- just enough that a space or an
     *  embedded quote in a task argument survives as one word in the common case. */
    static String cmdQuote(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(
                c -> c == ' ' || c == '\t' || c == '"' || c == '&' || c == '|'
                  || c == '<' || c == '>' || c == '^' || c == '%'))
            return arg;
        return '"' + arg.replace("\"", "\"\"") + '"';
    }

    /**
     * Runs a task's shell string via the platform shell, inheriting cwd and the three
     * streams exactly like a plugin or the compiler does. Extra args are appended
     * positionally -- the same contract `npm run` already has, and the reason for `"$@"`
     * rather than string concatenation: an argument containing a space must not become
     * two.
     */
    static void runTask(String command, List<String> extraArgs) {
        List<String> cmd = new ArrayList<>();
        if (isWindows()) {
            cmd.add("cmd"); cmd.add("/c"); cmd.add(command);
            // Best-effort, not a guarantee: `cmd /c` re-parses everything after it as one
            // command line with its own rules, so a Java-side argv list is not positional
            // the way POSIX's "$@" is -- an unquoted argument reaching cmd.exe unquoted
            // would not even survive as one word. Quoting the common case (a space, a
            // quote) is what a task author can rely on; byte-exact cmd.exe argument
            // parity is not achievable here for the same reason it is not claimed for the
            // shim itself -- see docs/LIMITATIONS.md.
            for (String a : extraArgs) cmd.add(cmdQuote(a));
        } else {
            cmd.add("sh"); cmd.add("-c"); cmd.add(command + " \"$@\""); cmd.add("sh");
            cmd.addAll(extraArgs);
        }
        tr("exec " + String.join(" ", cmd));
        try {
            System.exit(awaitWithReaper(new ProcessBuilder(cmd).inheritIO().start()));
        } catch (IOException e) {
            throw w005("cannot run task: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        }
    }

    /**
     * What the pinned compiler reports of itself, as {@code pin} recorded it; null when a
     * lock predates the key and no refresh has backfilled it.
     *
     * <p>Read rather than asked: the value is a property of the pinned bytes, so the
     * digest every run already checks is what keeps it honest. Says nothing about a
     * {@code FLIX_JAR} override -- see {@link #reportOverrideGap} for those bytes.
     */
    static String askedVersion(Lock lock) {
        return lock == null ? null : lock.reportedVersion();
    }

    static void report(Path root, Lock lock, Path jar, Jvm jvm, List<String> cv, String reported) {
        System.out.println("flixw            " + WRAPPER_VERSION);
        System.out.println("project root     " + root);
        System.out.println("compiler         " + (lock == null ? "-" : lock.version()));
        System.out.println("source           " + (lock == null ? "-"
            : (lock.repo() == null ? UPSTREAM_REPO : lock.repo())
              + (lock.repo() != null && !lock.repo().equals(UPSTREAM_REPO)
                 ? "  (a fork; not stock-compatibility evidence)" : "")));
        // Only when the two strings differ: printing the pin back twice is not information.
        // Build metadata is the ordinary reason they differ and is not a mismatch, so the
        // line says which of the two it is rather than leaving the reader to compare.
        if (reported != null && lock != null && !reported.equals(lock.version()))
            System.out.println("reported         " + reported + "  ("
                + (canonical(reported).equals(canonical(lock.version()))
                   ? "the compiler does not carry the build metadata the lock pins"
                   : "MISMATCH -- the lock pins " + lock.version()) + ")");
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
        if (env("FLIX_JAR") != null) {
            System.out.println("override         FLIX_JAR=" + env("FLIX_JAR")
                             + "  (unverified; not stock-compatibility evidence)");
            // The digest of what is actually being run. Without it the only clue that the
            // override is a different compiler is a sha inside the jar's file name, two
            // lines above a `digest` line that says something else -- which is a diff the
            // reader has to perform by eye, and did not.
            if (jar != null && Files.isRegularFile(jar)) {
                String got = sha256(jar);
                System.out.println("override digest  " + got + (lock == null ? ""
                    : got.equals(lock.sha256()) ? "  (the jar the lock pins)"
                    : "  (NOT the jar the lock pins)"));
            }
        }
        System.out.println("compiler verbs   " + (cv == null ? "(not captured)" : String.join(" ", cv)));
        List<String> fallback = new ArrayList<>(WRAPPER_VERBS);
        if (cv != null) fallback.removeAll(cv);
        System.out.println("wrapper verbs    " + String.join(" ", fallback));
        System.out.println("pass-through     ./flixw -- <args>");
    }

    /**
     * Everything already sitting on this machine -- compilers and JDKs flixw itself
     * cached, plus the JDKs {@link #knownInstalls()} can already see without a network
     * call, each one labelled with the repo and exact tag {@link #writePinRecord} recorded
     * the last time some project pinned that digest. Not what could be pinned or
     * provisioned: that would mean asking a remote API on a verb the paper promises stays
     * offline. `info` reports state; a catalogue of upstream releases is a different
     * feature with a different cost, and does not belong behind the same flag.
     */
    static void listCache(Lock lock, Jvm jvm) {
        Path compilers = cacheHome().resolve("compilers");
        List<Path> jars = List.of();
        try (var s = Files.isDirectory(compilers) ? Files.list(compilers) : null) {
            if (s != null) jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException ignored) { }
        List<String[]> compilerRows = new ArrayList<>();
        for (Path jar : jars) {
            Matcher m = Pattern.compile("^flix-(.+)-([0-9a-f]{64})\\.jar$").matcher(jar.getFileName().toString());
            String canonicalVersion = m.matches() ? m.group(1) : jar.getFileName().toString();
            String sha = m.matches() ? m.group(2) : null;
            long size;
            try { size = Files.size(jar); } catch (IOException e) { size = -1; }
            boolean pinned = sha != null && lock != null && sha.equals(lock.sha256());
            // The cache directory names only the canonical x.x.x -- build metadata, which
            // is what actually tells two builds of one release apart, comes from the pin
            // record `pin`/`acquire` write: it is what a lock actually pinned this exact
            // digest as, repo included, and it survives long after the project that wrote
            // it moved on to another pin. Failing that, the canonical name the directory
            // entry itself carries.
            //
            // The lock's reported_version is deliberately not consulted: it describes one
            // project's pinned compiler and this listing is machine-wide, so most entries
            // belong to no lock this command can see.
            PinRecord pin = sha == null ? null : cachedPinRecord(sha);
            String version = pin != null ? pin.version() : canonicalVersion;
            String repo = pin != null ? pin.repo() : null;
            // A version string disambiguates only when it says more than the canonical
            // name the cache directory already gives every entry; whenever it does not --
            // no record, or a compiler that reports its release but not its build -- the
            // digest is the only thing left that tells two same-named entries apart.
            boolean disambiguated = !version.equals(canonicalVersion);
            compilerRows.add(new String[] { version, humanSize(size),
                             (repo != null && !repo.equals(UPSTREAM_REPO) ? "  (" + repo + ")" : "")
                           + (!disambiguated && sha != null ? "  (sha " + sha.substring(0, 12) + "...)" : "")
                           + (pinned ? "  <= pinned" : "") });
        }
        System.out.println("cached compilers");
        printAligned(compilerRows);

        Path jdks = cacheHome().resolve("jdks");
        Path installed = installedJdk();
        List<Path> dirs = List.of();
        try (var s = Files.isDirectory(jdks) ? Files.list(jdks) : null) {
            if (s != null) dirs = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException ignored) { }
        List<String[]> jdkRows = new ArrayList<>();
        for (Path dir : dirs) {
            Path exe = findJavaUnder(dir);
            if (exe == null) continue;                    // a partial or foreign directory
            String version = probeVersion(exe);
            jdkRows.add(new String[] { version == null ? "(unknown)" : version, dir.getFileName().toString(),
                             exe.equals(installed) ? "  <= default" : "" });
        }
        System.out.println("cached JDKs");
        printAligned(jdkRows);

        // Distinct from the section above: these are not flixw's to manage, only to find --
        // Homebrew, scoop, sdkman, asdf, mise, jenv and the OS-native install directories
        // knownInstalls() already searches to select a Java when nothing else applies.
        List<Path> known = knownInstalls();
        List<String[]> systemRows = new ArrayList<>();
        for (Path exe : known) {
            String version = probeVersion(exe);
            int feature = probe(exe);
            systemRows.add(new String[] { version == null ? "(unknown)" : version, exe.toString(),
                             jvm != null && exe.equals(jvm.exe()) ? "  <= selected"
                           : feature >= 0 && feature < MIN_JAVA ? "  (below Java " + MIN_JAVA + ")" : "" });
        }
        System.out.println("system JDKs");
        printAligned(systemRows);

        // Every plugin ever installed on this machine, not only what this project
        // declares -- the same "machine-wide, not project-scoped" listing the sections
        // above already give compilers and JDKs. A directory read, nothing here reaches
        // the plugin's own source.
        List<Path> pluginNames = List.of();
        try (var s = Files.isDirectory(pluginsDir()) ? Files.list(pluginsDir()) : null) {
            if (s != null) pluginNames = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException ignored) { }
        List<String[]> pluginRows = new ArrayList<>();
        for (Path nameDir : pluginNames) {
            String name = nameDir.getFileName().toString();
            List<Path> versions;
            try (var s = Files.list(nameDir)) { versions = s.filter(Files::isDirectory).sorted().toList(); }
            catch (IOException e) { continue; }
            PluginDep want = lock == null ? null : lock.plugins().get(name);
            for (Path v : versions) {
                boolean wanted = want != null && v.getFileName().toString()
                                 .equals(want.version() + "-" + want.sha256());
                pluginRows.add(new String[] { name, v.getFileName().toString(),
                                              wanted ? "  <= expected by lock.toml" : "" });
            }
        }
        System.out.println("installed plugins");
        printAligned(pluginRows);

        // Wrapper-owned companion assets, not plugins: fetched once per machine per flixw
        // release the same way --upgrade fetches flixw.java itself, cached under their own
        // subtree so this trust tier stays visually distinct from user-installed plugins
        // and project-pinned compilers. Listed by walking the directory rather than from
        // the two constants, so an asset left behind by a release this one has since
        // replaced is still visible to whoever is deciding what to delete.
        Path assetsDir = cacheHome().resolve("wrapper").resolve("assets");
        List<Path> assetVersions = List.of();
        try (var s = Files.isDirectory(assetsDir) ? Files.list(assetsDir) : null) {
            if (s != null) assetVersions = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException ignored) { }
        List<String[]> assetRows = new ArrayList<>();
        for (Path v : assetVersions) {
            String ver = v.getFileName().toString();
            String mark = ver.equals(canonical(WRAPPER_VERSION)) ? "  <= this release" : "";
            try (var s = Files.list(v)) {
                for (Path f : s.filter(x -> x.getFileName().toString().endsWith(".java"))
                               .sorted().toList())
                    assetRows.add(new String[] { ver, f.getFileName().toString(), mark });
            } catch (IOException ignored) { }
        }
        System.out.println("cached companion assets");
        printAligned(assetRows);
    }

    /**
     * Prints a two-column list with its first two columns aligned, so that a listing whose
     * entries vary wildly in length -- {@code 0.75.2} beside {@code 0.75.3+stable.names.4}
     * -- reads as a table instead of a ragged column of annotations nobody can scan.
     */
    static void printAligned(List<String[]> rows) {
        if (rows.isEmpty()) { System.out.println("  (none)"); return; }
        int w0 = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0);
        int w1 = rows.stream().mapToInt(r -> r[1].length()).max().orElse(0);
        for (String[] r : rows)
            System.out.println("  " + pad(r[0], w0) + "  " + pad(r[1], w1) + r[2]);
    }

    static String pad(String s, int width) {
        return width <= s.length() ? s : s + " ".repeat(width - s.length());
    }

    /** A byte count as a person reads it; cached JARs and JDKs are always well above 1 KB. */
    static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    /** Compares a committed invariant file against the bytes this wrapper release ships. */
    /**
     * Compares an installed file with the bytes this release ships, by digest.
     *
     * <p>By digest rather than by text because the text is no longer here -- it is in the
     * installer asset. The check is the same check: the answer to "is this the shim flixw
     * wrote" is identical whether it comes from comparing 6KB of shell or 64 hex digits,
     * and this way it needs no fetch. What is lost is the ability to say *how* it differs,
     * which this never said anyway.
     */
    static int checkCanonical(Path file, String wantDigest, String label) {
        if (!Files.isRegularFile(file)) { System.out.println("FAIL  missing " + label); return 1; }
        try {
            if (sha256(file).equals(wantDigest)) {
                System.out.println("ok    " + label + " matches flixw " + WRAPPER_VERSION);
                return 0;
            }
            System.out.println("FAIL  " + label + " differs from flixw " + WRAPPER_VERSION
                             + " (./flixw doctor --fix)");
        } catch (Fail e) {
            System.out.println("FAIL  unreadable " + label + ": " + e.getMessage());
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
        bad += checkCanonical(root.resolve("flixw"), SHIM_SHA256, "./flixw");
        bad += checkCanonical(root.resolve("flixw.cmd"), CMD_SHA256, "./flixw.cmd");
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

        // The digest settles which bytes; this settles whether they are the release the lock
        // names. A FAIL rather than a warning when the versions genuinely differ: `validate`
        // is what CI runs, and a lock that misnames the compiler it pins is the kind of
        // thing a build should refuse rather than mention. Build metadata is not that --
        // the compiler is entitled to report the release it was built from.
        if (lock != null && jar != null && jvm != null) {
            String rv = askedVersion(lock);
            if (rv == null)
                System.out.println("warn  the lock records no reported_version; nothing to"
                                 + " check it against (./flixw pin --refresh)");
            else if (canonical(rv).equals(canonical(lock.version())))
                System.out.println(rv.equals(lock.version())
                    ? "ok    the compiler reports the version the lock pins"
                    : "warn  the compiler reports " + rv + "; the lock pins " + lock.version()
                    + " (build metadata only)");
            else {
                System.out.println("FAIL  the compiler reports " + rv + ", but the lock pins "
                                 + lock.version() + " (./flixw pin " + rv + ")");
                bad++;
            }
        }
        if (jar != null && Files.isRegularFile(jar)) System.out.println("ok    cached compiler digest");

        // Checked against the machine-wide cache alone -- a directory read, nothing about
        // this reaches the network. A warn, not a FAIL: the project still builds without
        // it, only `flixw plugin <name>` would fail, and doctor says so without stopping
        // `validate` over a dependency CI may not have installed either.
        if (lock != null) {
            for (var entry : lock.plugins().entrySet()) {
                String name = entry.getKey();
                PluginDep want = entry.getValue();
                if (Files.isDirectory(pluginDir(name, want.version(), want.sha256())))
                    System.out.println("ok    plugin " + name + " " + want.version() + " is installed");
                else
                    System.out.println("warn  plugin " + name + " " + want.version()
                                     + " is expected by lock.toml but not installed"
                                     + " (./flixw plugin install " + name + " " + want.version()
                                     + " <url> --sha256 " + want.sha256() + ")");
            }
        }

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
                                   had.version(), had.url(), had.sha256(), had.reportedVersion(),
                                   javaPin, had.plugins());
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

            // Asked once, here, and recorded beside the digest that will vouch for it on
            // every later run. The JAR is read from wherever it actually landed: the move
            // above is allowed to fail, and a cache that could not be written must not
            // cost the project its second opinion.
            String reported = captureReportedVersion(
                Files.isRegularFile(jar) ? jar : tmp, javaPin);

            // Repinning the compiler is unrelated to what plugins the project has declared
            // -- carried forward exactly like the java pin above, never reset by this.
            String lock = lockText(WRAPPER_VERSION, repo, version, url, digest, reported, javaPin,
                                   had == null ? Map.of() : had.plugins());
            // Written here, not left for the next `acquire()`: a project that pins and then
            // immediately pins again -- trying one fork build after another, say -- never
            // runs any other command against the one in between, so `acquire()` would never
            // see it and its tag would be lost the same way an untracked build's always is.
            writePinRecord(digest, repo, version);

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
            reportVersionGap("the JAR just pinned", version, reported);
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
    /**
     * {@code doctor --fix}: the asset rewrites the files it owns, stage 0 refreshes the
     * lock, and the two counts are added up here.
     *
     * <p>Split that way because the lock is stage 0's alone -- it is the one file in the
     * project whose *meaning* the wrapper has to understand, and `refreshLock` rewrites it
     * from values it has already validated. Handing that to the installer would give a
     * fetched asset write access to the trust root.
     */
    static void updateWrapper(Path root) {
        runInstallAsset(List.of("update", root.toString(), WRAPPER_VERSION));
        // A lock only `pin <version>` can repair is not this command's to guess at;
        // everything else doctor --fix reports is still reported.
        try {
            if (refreshLock(root).changed())
                System.out.println("rewrote  " + WRAPPER_DIR + "/lock.toml");
        } catch (Fail unparseable) {
        } catch (IOException e) { throw w009("rewriting the lock failed: " + why(e)); }
    }

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
                           String sha256, String reportedVersion, String java,
                           Map<String, PluginDep> plugins) {
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
        // Absent when it could not be captured -- an older flixw wrote the lock, or no JVM
        // was selectable at pin time. Absent means "no second opinion was recorded", never
        // "the compiler agreed": `pin --refresh` backfills it from the cached JAR.
        if (reportedVersion != null)
            body += "reported_version = \"" + reportedVersion + "\"\n";
        // Absent rather than empty when unpinned: a project that does not care which JDK
        // runs the compiler should not have to read a line telling it so.
        if (java != null) body += """

            [java]
            version = "%s"
            """.formatted(java);
        // Not `pin`'s to invent: this only re-emits what `flixw plugin install` already
        // wrote, so a re-pin of the compiler -- an unrelated event -- does not silently
        // drop what plugins the project declared. Sorted by name: the map's own order
        // depends on which lock this was read from, and a rewrite that only ever moved
        // the same content around would otherwise look like unrelated churn in a diff.
        for (String name : plugins.keySet().stream().sorted().toList()) {
            PluginDep p = plugins.get(name);
            body += "\n[plugins." + name + "]\n"
                  + "version = \"" + p.version() + "\"\n"
                  + "sha256  = \"" + p.sha256() + "\"\n"
                  + (p.source() == null ? "" : "source  = \"" + p.source() + "\"\n");
        }
        return body;
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
        // The one value a refresh may *add* rather than re-emit. Locks written before the
        // compiler's self-reported version was recorded have no second opinion and could
        // never acquire one, since the check now happens at pin time and re-pinning means
        // a fresh download. Backfilling it here keeps the promise reachable without one:
        // the JAR is already in the cache, and reading it is not network.
        //
        // Only from a cache entry whose bytes still hash to what the lock pins. Otherwise
        // this would launder an unverified JAR's self-description into the lock, where
        // every later run would treat it as something pin had checked.
        String reported = lock.reportedVersion();
        if (reported == null) {
            Path cached = compilerPath(lock);
            if (Files.isRegularFile(cached) && sha256(cached).equals(lock.sha256()))
                reported = captureReportedVersion(cached, lock.java());
        }
        String want = lockText(WRAPPER_VERSION, lock.repo() == null ? UPSTREAM_REPO : lock.repo(),
                               lock.version(), lock.url(), lock.sha256(), reported, lock.java(),
                               lock.plugins());
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

    /** The digest a {@code SHA256SUMS} file names for one file, or null if it does not. */
    static String digestFor(String sums, String assetName) {
        String want = null;
        for (String line : sums.split("\r?\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length == 2 && f[1].equals(assetName)) want = f[0];
        }
        return want;
    }

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
    /**
     * Every companion asset a release publishes, read out of that release's own
     * {@code SHA256SUMS} rather than from a list in here.
     *
     * <p>That is the difference between warming the assets this stage 0 knows about and
     * warming the ones the release actually has. An upgrade runs in the *old* stage 0,
     * which cannot know what the new release added -- so a hard-coded list would silently
     * stop warming the day a fourth asset shipped, and nothing would report it. Reading
     * the manifest means an older wrapper warms assets it has never heard of.
     *
     * <p>{@code flixw.java} itself is excluded: it is the wrapper, not a companion to it,
     * and the upgrade installs it by a different route.
     */
    static List<String> publishedAssets(String sums) {
        List<String> out = new ArrayList<>();
        for (String line : sums.split("\r?\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length == 2 && f[1].matches("flixw-[a-z0-9-]+\\.java") && !out.contains(f[1]))
                out.add(f[1]);
        }
        return out;
    }

    /**
     * Fetches and verifies every companion asset of one release into the cache, so that
     * the commands needing them work offline afterwards.
     *
     * <p>Best-effort per asset, and never fatal. Warming is an optimisation on a command
     * that has already done its real work: an upgrade that installed a new stage 0 and
     * then failed to pre-fetch a completion generator has still upgraded, and the asset
     * will be fetched on demand the first time it is wanted. Failing the upgrade over it
     * would turn a slow network into a broken wrapper.
     *
     * @return how many assets are now cached and verified for that version
     */
    static int warmAssets(String sums, String version) {
        int warm = 0;
        for (String name : publishedAssets(sums)) {
            try {
                ensureAsset(name, version);
                warm++;
            } catch (Fail f) {
                System.err.println("flixw: note: could not pre-fetch " + name + "; it will be"
                                 + " fetched when first needed");
                tr("warm " + name + ": " + f.getMessage());
            }
        }
        if (warm > 0)
            System.err.println("flixw: " + warm + " companion asset" + (warm == 1 ? "" : "s")
                             + " cached for " + canonical(version) + "; they need no network again");
        return warm;
    }

    static void upgradeWrapper(Path root) {
        String sums = httpGet(FLIXW_LATEST + "SHA256SUMS");
        String want = digestFor(sums, "flixw.java");
        if (want == null || !want.matches("[0-9a-f]{64}"))
            throw w005("the published SHA256SUMS names no digest for flixw.java");

        Path current = root.resolve(WRAPPER_DIR).resolve("flixw.java");
        if (Files.isRegularFile(current) && sha256(current).equals(want)) {
            // Same sentence as the version guard below, because it is the same outcome:
            // nothing was changed and nothing needed to be. They differ only in how it was
            // established -- a matching digest here, a version comparison there.
            System.out.println("flixw " + WRAPPER_VERSION
                             + " is the newest release. Nothing to do.");
            // "Nothing to do" is about stage 0, not about the assets beside it. An upgrade
            // is also the natural moment to notice one is missing -- deleted from the
            // cache, or never fetched because nothing had needed it yet.
            warmAssets(sums, WRAPPER_VERSION);
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
                // Warmed only when this *is* the published release, which is the ordinary
                // reading of "same or older". Ahead of it -- someone working on flixw --
                // the assets for this version were never published, so asking for them
                // would be a guaranteed 404 dressed up as a note about the network.
                if (canonical(published).equals(canonical(WRAPPER_VERSION)))
                    warmAssets(sums, WRAPPER_VERSION);
                return;
            }
            System.err.println("flixw: " + WRAPPER_VERSION + " -> "
                             + (published == null ? "the latest release" : published));
            // Hand over: the new stage 0 writes its own shims and its own copy of itself.
            Path javaExe = exeIn(System.getProperty("java.home"));
            ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), fresh.toString(),
                                                   "wrapper", "--install", root.toString())
                                    .inheritIO();
            // The child is a different file in a different directory. Both markers describe
            // *this* process and mean nothing to it -- FLIXW_SOURCE would anchor it in this
            // project, and FLIXW_RELAUNCHED would spend its one relaunch before it starts.
            pb.environment().remove("FLIXW_SOURCE");
            pb.environment().remove("FLIXW_RELAUNCHED");
            Process p = pb.start();
            int rc = awaitWithReaper(p);
            if (rc != 0) throw w009("the downloaded flixw failed to install (exit " + rc + ")");
            // The assets of the release just installed, not of the one being replaced --
            // they are cached under a version-keyed path, so the new stage 0 would
            // otherwise fetch every one of them again on first use.
            warmAssets(sums, published == null ? WRAPPER_VERSION : published);
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
            // Bootstrap. In the namespace and not a bare verb, because `install` is a name
            // Flix could plausibly claim -- for a project's dependencies, which is what
            // every other tool means by it -- and flixw held it for an operation run once,
            // ever, before the project exists. `./flixw install` now reaches the compiler
            // from the first command rather than only after a lock appears.
            case "--install" -> {
                if (rest.size() > 1)
                    throw w008(wrapperUsage("'--install' takes at most a directory"));
                installProject(Paths.get(rest.isEmpty() ? "." : rest.get(0))
                                    .toAbsolutePath().normalize(), selfSource());
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
            // Project-free for the same reason as --schema: the script it prints is
            // byte-identical for every project on a given release, because everything
            // project-specific is read at completion time from the note stage 0 leaves in
            // .flixw/local/.  A script that had to be regenerated after every `pin` would be
            // wrong in the one way a completion script must not be -- silently, and only for
            // the person who forgot.  Not offline, unlike --schema: the generator itself is a
            // wrapper-owned companion asset, fetched once per machine per release and cached
            // from there on -- the same shape --install-jdk already has, not --version's.
            case "--completion" -> {
                if (rest.size() != 1)
                    throw w008(wrapperUsage("'--completion' takes exactly one shell name"));
                String shell = rest.get(0);
                if (!COMPLETION_SHELLS.contains(shell))
                    throw w008(wrapperUsage("unknown shell " + q(shell)));
                Path asset = ensureAsset(COMPLETION_ASSET);
                List<String> fallback = new ArrayList<>(WRAPPER_VERBS);
                for (String v : BUILTIN_VERBS) if (!fallback.contains(v)) fallback.add(v);
                fallback.sort(null);
                Path javaExe = exeIn(System.getProperty("java.home"));
                ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), asset.toString(),
                    shell, String.join(",", fallback)).inheritIO();
                try {
                    System.exit(awaitWithReaper(pb.start()));
                } catch (IOException e) {
                    throw w005("cannot run " + asset + ": " + why(e));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.exit(130);
                }
            }
            default -> throw w008(wrapperUsage("unknown operation " + q(op)));
        }
    }

    static String wrapperUsage(String problem) {
        return "./flixw wrapper: " + problem
             + "\n       usage: ./flixw wrapper [--help | --version | --install | --upgrade"
             + "\n                              | --install-jdk | --schema | --completion]"
             + "\n         --help         the routing table for this project"
             + "\n         --version      the wrapper version and how stage 0 was launched"
             + "\n         --install [dir]  write the wrapper files into a project"
             + "\n         --upgrade      move this project to the newest published flixw"
             + "\n                        (to repair the files it has: ./flixw doctor --fix)"
             + "\n         --install-jdk  fetch a verified Temurin " + MIN_JAVA + " into the cache"
             + "\n         --schema       the JSON Schema for " + WRAPPER_DIR + "/lock.toml, on stdout"
             + "\n         --completion <shell>   a TAB-completion script, on stdout,"
             + "\n                        for one of " + String.join(", ", COMPLETION_SHELLS);
    }

    // ---- completion -------------------------------------------------------

    static final List<String> COMPLETION_SHELLS = List.of("bash", "zsh", "fish", "pwsh");

    static final String COMPLETION_ASSET = "flixw-completion.java";

    /** The optional JDK provisioner; see {@link #runJdkAsset}. */
    static final String JDK_ASSET = "flixw-jdk.java";

    /** The installer; see {@link #runInstallAsset}. */
    static final String INSTALL_ASSET = "flixw-install.java";

    /**
     * The SHA-256 of the two shims this release installs, as they are written to disk --
     * the POSIX one with LF, the {@code .cmd} with CRLF.
     *
     * <p>The shim *text* lives in {@code flixw-install.java}, which is fetched. These two
     * lines are what stays behind, and they are the reason the move is affordable:
     * {@code validate} and {@code doctor} still detect a drifted or truncated shim
     * offline, on a cold cache, with no network -- only *repairing* it reaches for the
     * asset. A wrapper that could not tell you your shim was wrong without a network
     * would be worse than one that cannot fix it.
     *
     * <p>{@code tests/lint.sh} hashes {@code src/flixw} and {@code src/flixw.cmd} and
     * fails if either disagrees, so these cannot rot behind a shim edit.
     */
    /**
     * Runs the installer asset, fetching and verifying it first.
     *
     * <p>A child process rather than an in-process call, for the same reason the JDK
     * provisioner is one: it is a separate program with its own diagnostics, and its exit
     * status is the answer. stderr and stdin are inherited so its messages land where
     * every other flixw diagnostic does.
     *
     */
    static void runInstallAsset(List<String> args) {
        Path asset = ensureAsset(INSTALL_ASSET);
        Path javaExe = exeIn(System.getProperty("java.home"));
        List<String> cmd = new ArrayList<>(List.of(javaExe.toString(), asset.toString()));
        cmd.addAll(args);
        // All three streams inherited: what it writes is what the user came to read, and
        // capturing stdout to count something swallowed every line install prints.
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        try {
            int rc = awaitWithReaper(pb.start());
            // The asset speaks flixw's own diagnostic language and has already said what
            // went wrong on the inherited stderr; a second code here would report one
            // failure twice, the outer one less specifically.
            if (rc != 0) System.exit(rc);
        } catch (IOException e) {
            throw w005("cannot run " + asset + ": " + why(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw w009("install interrupted");
        }
    }

    /**
     * First contact: write the project's files, and leave the machine able to work offline.
     *
     * <p>Warming every asset rather than only the installer is close to free here -- the
     * manifest naming them has just been fetched to verify the installer, and each one is
     * a few kilobytes -- and it is the difference between "installed" and "installed, and
     * the next command that wants a completion script will stop to download one". Since
     * install now needs the network at all, it should ask for it once.
     */
    static void installProject(Path target, Path source) {
        runInstallAsset(List.of("install", target.toString(), WRAPPER_VERSION, source.toString()));
        // Never fatal: the project is installed by this point, and an asset that was not
        // pre-fetched is fetched when something wants it.
        try {
            warmAssets(readSums(assetSourceBase(WRAPPER_VERSION)), WRAPPER_VERSION);
        } catch (RuntimeException e) {
            tr("cannot warm the remaining assets: " + e.getMessage());
        }
    }

    static final String SHIM_SHA256 =
        "f0d8bfd875d0d8436a8dcf82c0afb757028de7f223f410c9a89822ab6b7cabbf";
    static final String CMD_SHA256 =
        "ccaaefdc8aaf3303814849f2fe7c08900d2b5ba7c469b62d146b5739a5003e19";

    /**
     * Wrapper-owned, not a plugin -- fetched and verified against the release this stage 0
     * itself is, never installed by a user and never carrying the "3rd-party, unaudited"
     * warning a real plugin does. Overridable the same shape {@code FLIX_DIST_URL} gives
     * the compiler's own distribution base: a self-hosted mirror, or (unset in production)
     * a local fixture for this project's own tests.
     */
    static String assetSourceBase(String version) {
        String o = env("FLIXW_ASSET_SOURCE");
        if (o != null) return o.replaceAll("/+$", "") + "/";
        return "https://github.com/wstein/flixw/releases/download/v" + canonical(version) + "/";
    }

    /**
     * Version-keyed, so a release's assets can be cached forever: the entry cannot go
     * stale except by a new release, which is exactly when this path moves.
     */
    static Path assetDir(String version) {
        return cacheHome().resolve("wrapper").resolve("assets").resolve(canonical(version));
    }

    /**
     * Ensures this exact release's completion generator sits verified in the cache,
     * fetching it on a miss -- the same trust footing {@link #upgradeWrapper} already
     * gives {@code flixw.java} itself, applied to a companion asset instead: the digest is
     * checked against the {@code SHA256SUMS} published beside it, same origin, same TLS.
     *
     * Verified once, at fetch time, not on every call: unlike the compiler cache, there is
     * no local record of the expected digest to check against for free (this asset is not
     * named by any project's lock), so re-fetching {@code SHA256SUMS} on every invocation
     * would mean every {@code wrapper --completion} needs network forever -- exactly what
     * "cached, works offline" is supposed to rule out. A sidecar {@code .sha256} file
     * records what was verified, so every later call still cheaply self-checks the cached
     * bytes against it, offline, rather than trusting bare presence.
     */
    /**
     * The digest manifest for one release base, from wherever that base points.
     *
     * <p>A {@code file://} base is not a mirror, it is this project's own tests standing a
     * release up locally -- and the JDK HTTP client refuses the scheme outright rather
     * than falling back, so the two cases cannot share one code path. Factored out because
     * they went out of step once already: warming read the manifest with httpGet while
     * fetching read it with this, so `install` threw a raw IllegalArgumentException with
     * no FLIXW code the first time it ran against a fixture.
     */
    static String readSums(String base) {
        try {
            return base.startsWith("file://")
                ? Files.readString(Paths.get(URI.create(base + "SHA256SUMS")), StandardCharsets.UTF_8)
                : httpGet(base + "SHA256SUMS");
        } catch (IOException e) {
            throw w005("cannot read " + redact(base) + "SHA256SUMS: " + why(e));
        }
    }

    static Path ensureAsset(String name) { return ensureAsset(name, WRAPPER_VERSION); }

    /**
     * The version is a parameter because {@code wrapper --upgrade} warms the assets of the
     * release it is upgrading *to*, from the stage 0 it is upgrading *from*. Everything
     * else asks for its own.
     */
    static Path ensureAsset(String name, String version) {
        Path dir = assetDir(version);
        Path asset = dir.resolve(name), marker = dir.resolve(name + ".sha256");
        if (Files.isRegularFile(asset) && Files.isRegularFile(marker)) {
            try {
                if (sha256(asset).equals(Files.readString(marker, StandardCharsets.UTF_8).trim()))
                    return asset;
            } catch (IOException ignored) { }
            // Falls through: a corrupt cache entry re-fetches, exactly like a corrupt
            // compiler jar does in acquire().
        }
        String base = assetSourceBase(version);
        String sums = readSums(base);
        String want = digestFor(sums, name);
        if (want == null || !want.matches("[0-9a-f]{64}"))
            throw w005("no published flixw " + version + " release names " + name
                     + "\n       run: ./flixw wrapper --upgrade   (or wait for this version to be released)");
        try {
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".asset-", ".part");
            try {
                if (base.startsWith("file://"))
                    Files.copy(Paths.get(URI.create(base + name)), tmp,
                               StandardCopyOption.REPLACE_EXISTING);
                else download(base + name, tmp);
                String got = sha256(tmp);
                if (!got.equals(want))
                    throw w006("digest mismatch for " + name
                             + "\n       expected " + want + "\n       actual   " + got);
                try { Files.move(tmp, asset, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException e) { if (!Files.isRegularFile(asset)) throw e; }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            writeAtomic(marker, sha256(asset));
        } catch (IOException e) {
            throw w007("cannot cache " + name + ": " + why(e));
        }
        return asset;
    }

    /**
     * The name of the note stage 0 leaves for a completer, holding the verbs this project
     * would actually dispatch.  It lives beside {@code local/java} and is machine-specific
     * for the same reason: it describes a resolved compiler, not the project.
     */
    static final String VERBS_NOTE = "verbs";

    /** The note naming the pinned compiler's own completion script, when it ships one. */
    static final String COMPL_NOTE = "completion";

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

    /** Same, for the path to the compiler's own completion script; absent means none. */
    static void recordCompletion(Path root, Path script) {
        recordNote(root, COMPL_NOTE, script.toAbsolutePath().normalize().toString());
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

    /**
     * The pinned compiler's own completion script, cached, or null if it has none.
     *
     * Detection costs nothing and needs no version sniffing: picocli registers
     * {@code generate-completion} as an ordinary subcommand, so it arrives in the verb set
     * {@link #parseVerbs} already captured.  Stock Flix is scopt, never advertises it, and
     * takes this path zero times -- which is the whole reason the check is a set membership
     * rather than a probe.
     *
     * flixw does not read, rewrite or splice what comes back.  The generated script's
     * internal shape is picocli's business and changes with picocli; the one line flixw
     * looks at, at completion time and in shell, is the {@code complete -F} registration
     * every bash completion script must end with.  Splicing was the alternative and it is
     * worse than it looks: {@link #parseVerbs} guessing wrong falls back to a verb table,
     * while a bad splice puts broken bash in someone's shell startup.
     *
     * Cached beside the verb record and keyed the same way, so a re-pin gets a new one and
     * an override never writes next to a JAR flixw does not own.
     */
    static Path compilerCompletion(Path javaExe, Path jar, String identity, List<String> verbs) {
        if (!verbs.contains("generate-completion")) return null;
        Path cf = cacheHome().resolve("verbs").resolve(identity + ".compl");
        if (Files.isRegularFile(cf)) return cf;
        String out;
        try {
            out = runCapture(List.of(javaExe.toString(), "-jar", jar.toString(),
                                     "generate-completion"), HELP_TIMEOUT, HELP_CAP);
        } catch (IOException e) {
            tr("cannot run `flix generate-completion`: " + e.getMessage());
            return null;
        }
        // A completer that cannot register itself is not one.  Silence rather than a
        // diagnostic: this is an optimisation on an optimisation, and the compiler owes
        // flixw no such subcommand however it answered.
        if (out == null || !out.contains("complete -F ")) {
            tr("`flix generate-completion` produced no usable bash completer");
            return null;
        }
        try {
            Files.createDirectories(cf.getParent());
            Path tmp = Files.createTempFile(cf.getParent(), ".compl-", ".part");
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, cf, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tr("cannot cache completer at " + cf + ": " + e.getMessage());
            return null;
        }
        return cf;
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
        // A bridge, not an interface. `wrapper --install` is the bootstrap; this exists
        // because a released flixw's own upgrade spawns the *downloaded* stage 0 as
        // `install <root>`, and that wrapper is already published and cannot be changed.
        // Removing it before every supported release spawns `wrapper --install` would
        // break upgrading *into* this release, which is the one path with no way back.
        // Drop it once no supported wrapper spawns the bare word -- same rule as the
        // flix.java name bridge in tests/pack.sh.
        //
        // An explicit target is required, which is what makes this a bridge rather than a
        // squat on the name: the handover always passes one, and a person typing
        // `./flixw install` never does. So that reaches the compiler -- where a project
        // asking to install its dependencies was always trying to go -- from the first
        // command, instead of only once a lock exists.
        if ("install".equals(first) && argv.size() > 1
            && !Files.isRegularFile(lockPath(anchor))) {
            installProject(Paths.get(argv.get(1)).toAbsolutePath().normalize(), selfSource());
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

        // --help/-h ask for the routing table alone here, same as bare "help" -- there is
        // no compiler yet to ask for its half, and that must not be why the table itself
        // is unreachable on a project's very first command. Only when they are the whole
        // invocation: `--help check` is a flag with arguments, passed through untouched,
        // same rule the compiler-reachable path below already applies.
        boolean bareHelp = ("--help".equals(first) || "-h".equals(first)) && argv.size() == 1;

        // When the project cannot reach a compiler -- no lock yet, or a lock that
        // disagrees with the manifest -- the verbs that create and diagnose that state
        // must still run, or the repair the diagnostic recommends is unreachable. They
        // route on the built-in wrapper list alone, so no compiler is consulted.
        if ((lock == null || drift != null || manifestError != null) && first != null && !forcedCompiler
            && (WRAPPER_VERBS.contains(first) || bareHelp)) {
            if (lockError != null)
                System.err.println("flixw: warning: " + lockError.getMessage().split("\n")[0]);
            if (manifestError != null)
                System.err.println("flixw: warning: " + manifestError.getMessage().split("\n")[0]);
            if (drift != null) System.err.println("flixw: warning: " + drift.split("\n")[0]);
            routingNotice(first, lock == null ? "none" : lock.version());
            if (first.equals("pin")) {
                pin(root, parsePin(argv.subList(1, argv.size()), lock));
            } else if (bareHelp) {
                wrapperVerb("help", List.of(), root, lock, null, null, null);
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
            reportOverrideGap(lock, jar);
        } else jar = acquire(lock);

        String verbId = verbIdentity(jar, lock, override);
        List<String> compilerVerbs = verbs(jvm.exe(), jar, verbId);
        tr("verbs " + compilerVerbs.size());
        // Notes for a completer, which cannot afford to start stage 0 itself.  Both are
        // writes to already-resolved values, and both swallow every failure: a completion
        // candidate is not worth a diagnostic, still less a failed build.
        recordVerbs(root, compilerVerbs);
        Path compl = compilerCompletion(jvm.exe(), jar, verbId, compilerVerbs);
        if (compl != null) recordCompletion(root, compl);
        if (lock != null)
            reportVersionGap("the pinned compiler", lock.version(), lock.reportedVersion());
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
        System.out.println("  ./flixw info [--verbose | -v]    project, compiler, java, cache"
                         + " (-v: cached compilers and JDKs)");
        System.out.println("  ./flixw doctor [--fix]           info, plus every check, with a verdict");
        System.out.println("  ./flixw validate                 the checks alone, for CI");
        System.out.println("  ./flixw plugin install <name> <version> <url> [--sha256 <digest>]");
        System.out.println("  ./flixw plugin list | remove <name> | <name> [args]  installed plugins");
        System.out.println("  ./flixw task [<name> [args]]     .flixw/tasks.toml's aliases, or list them");
        System.out.println("  ./flixw wrapper [--help | --version | --install | --upgrade"
                         + " | --install-jdk | --schema | --completion]");
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

    /** {@code exe}'s JDK home: two directories up from {@code bin/java}. Null when {@code
     *  exe} has no parent, which cannot happen for a real path but costs nothing to guard. */
    static Path javaHomeOf(Path exe) {
        Path bin = exe.getParent();
        return bin == null ? null : bin.getParent();
    }

    /**
     * The context a plugin ABI version 1 promises: a flat environment-variable tier for
     * the common case, plus {@code FLIXW_CONTEXT} naming a versioned JSON file for
     * anything structured. Both are built here, once, for every format -- {@code .flix}
     * included: stock Flix's {@code Sys.Env.getVar} reads these even though a {@code
     * .flix} plugin cannot receive {@code args} (verified against a real compiler, not
     * assumed), so the ABI is the one thing every format can rely on regardless of
     * whether it can take CLI arguments.
     *
     * Compiler and Java fields are simply absent when this project has no lock yet or no
     * Java was resolved -- a `.jar`/`.java` plugin that does not need a compiler must not
     * be handed a context it has to guess is incomplete.
     */
    static Map<String, String> pluginEnv(Path root, Lock lock, Jvm jvm, Path compilerJar,
                                         String pluginName, ResolvedPlugin p, List<String> args) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FLIXW_ABI_VERSION", "1");
        env.put("FLIXW_PROJECT_ROOT", root.toString());
        env.put("FLIXW_CACHE_HOME", cacheHome().toString());
        if (lock != null) {
            env.put("FLIXW_COMPILER_VERSION", lock.version());
            env.put("FLIXW_COMPILER_REPO", lock.repo() == null ? UPSTREAM_REPO : lock.repo());
            env.put("FLIXW_COMPILER_SHA256", lock.sha256());
        }
        if (compilerJar != null) env.put("FLIXW_COMPILER_JAR", compilerJar.toString());
        if (jvm != null) {
            Path home = javaHomeOf(jvm.exe());
            if (home != null) env.put("FLIXW_JAVA_HOME", home.toString());
        }
        env.put("FLIXW_PLUGIN_NAME", pluginName);
        env.put("FLIXW_PLUGIN_VERSION", p.version());
        env.put("FLIXW_PLUGIN_SHA256", p.sha256());
        env.put("FLIXW_CONTEXT", writeContextFile(root, lock, jvm, compilerJar, pluginName, p, args).toString());
        return env;
    }

    /**
     * The structured half of the ABI: everything {@link #pluginEnv}'s flat variables
     * carry, plus the arguments this invocation was given, as one versioned JSON object.
     * Written to a fresh temp file per invocation and deleted by a shutdown hook -- not a
     * `finally` in the caller, because {@link System#exit} does not run one.
     */
    static Path writeContextFile(Path root, Lock lock, Jvm jvm, Path compilerJar,
                                 String pluginName, ResolvedPlugin p, List<String> args) {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        b.append("  \"abiVersion\": 1,\n");
        b.append("  \"flixwVersion\": ").append(jsonString(WRAPPER_VERSION)).append(",\n");
        b.append("  \"projectRoot\": ").append(jsonString(root.toString())).append(",\n");
        b.append("  \"cacheHome\": ").append(jsonString(cacheHome().toString())).append(",\n");
        if (lock == null) {
            b.append("  \"compiler\": null,\n");
        } else {
            b.append("  \"compiler\": {\n");
            b.append("    \"repo\": ")
             .append(jsonString(lock.repo() == null ? UPSTREAM_REPO : lock.repo())).append(",\n");
            b.append("    \"version\": ").append(jsonString(lock.version())).append(",\n");
            b.append("    \"sha256\": ").append(jsonString(lock.sha256())).append(",\n");
            b.append("    \"jar\": ")
             .append(compilerJar == null ? "null" : jsonString(compilerJar.toString())).append("\n");
            b.append("  },\n");
        }
        if (jvm == null) {
            b.append("  \"java\": null,\n");
        } else {
            Path home = javaHomeOf(jvm.exe());
            b.append("  \"java\": {\n");
            b.append("    \"home\": ").append(home == null ? "null" : jsonString(home.toString())).append(",\n");
            b.append("    \"feature\": ").append(jvm.feature()).append("\n");
            b.append("  },\n");
        }
        b.append("  \"plugin\": {\n");
        b.append("    \"name\": ").append(jsonString(pluginName)).append(",\n");
        b.append("    \"version\": ").append(jsonString(p.version())).append(",\n");
        b.append("    \"sha256\": ").append(jsonString(p.sha256())).append("\n");
        b.append("  },\n");
        b.append("  \"args\": ").append(jsonArray(args)).append("\n");
        b.append("}\n");
        Path dir = pluginsDir();
        try {
            Files.createDirectories(dir);
            Path f = Files.createTempFile(dir, ".context-", ".json");
            Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { }
            }));
            return f;
        } catch (IOException e) {
            throw w009("cannot write plugin context: " + why(e));
        }
    }

    /**
     * Launches one plugin artifact as an opaque subprocess, inheriting cwd and the three
     * streams exactly like {@link #launch} does for the compiler -- three formats, one
     * launcher, because a plugin is not otherwise different from the compiler stage 0
     * already knows how to run. {@code env} is the ABI: everything {@link #pluginEnv}
     * built, merged into the child's environment alongside whatever it already inherits.
     *
     * {@code .flix} always runs against *this project's own pinned compiler*, never a
     * version the plugin names: a plugin can extend what Flix does here, not choose which
     * Flix does it, so it cannot pull in a second, unverified compiler.
     *
     * A {@code .flix} plugin cannot receive {@code args}: stock Flix has no {@code run
     * <file>} mode -- {@code run} "runs main for the current project" and refuses a file
     * argument outright -- so the only way to execute one standalone is the bare-file form
     * ({@code java -jar flix.jar plugin.flix}), and there every extra positional word is
     * parsed as one more source file to compile, not a program argument -- verified
     * against a real compiler, not assumed. A {@code .jar} or {@code .java} plugin
     * wanting arguments is the workaround until Flix's own CLI grows one; every format
     * can still read the ABI's environment variables and {@code FLIXW_CONTEXT}.
     */
    static void runArtifact(Path artifact, Path javaExe, Path compilerJar, List<String> args,
                            Map<String, String> env) {
        String name = artifact.getFileName().toString();
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        if (name.endsWith(".jar")) {
            cmd.add("-jar"); cmd.add(artifact.toString());
            cmd.addAll(args);
        } else if (name.endsWith(".java")) {
            cmd.add(artifact.toString());
            cmd.addAll(args);
        } else if (name.endsWith(".flix")) {
            if (compilerJar == null)
                throw w009("plugin " + q(name) + " is a .flix plugin, but this project has"
                         + " no compiler pinned\n       run: ./flixw pin <version>");
            if (!args.isEmpty())
                throw w009("plugin " + q(name) + " is a .flix plugin: it cannot receive"
                         + " arguments (stock Flix has no way to pass any to a standalone"
                         + " file)\n       args given: " + String.join(" ", args));
            cmd.add("-jar"); cmd.add(compilerJar.toString());
            cmd.add(artifact.toString());
        } else {
            throw w009("plugin artifact " + q(name) + " is not .jar, .java or .flix");
        }
        tr("exec " + String.join(" ", cmd));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
            pb.environment().putAll(env);
            System.exit(awaitWithReaper(pb.start()));
        } catch (IOException e) {
            throw w005("cannot launch " + artifact + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
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
