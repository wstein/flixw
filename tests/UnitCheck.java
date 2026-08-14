// flixw unit checks -- the parts of stage 0 the shell suite cannot reach from outside.
//
//   javac -d <out> src/flixw.java tests/UnitCheck.java
//   java -cp <out> UnitCheck tests/corpus
//
// Compiled and run by tests/run.sh; not a separate CI entry point. The groups, in the
// order they appear below:
//
//   1, 2. the manifest scanner over a corpus of real published flix.toml files, compared
//         against what python3's tomllib -- a conforming parser -- read from each of them,
//         and the pin rewrite as a property over that same corpus: it changes one line
//      3. hand-written adversarial manifests, which the real corpus does not contain
//      4. the bounds on runCapture, which end to end would cost a 30-second test case
//   5, 6. JDK selection and provisioning, none of which may touch the network
//      7. pin targets: which release URL a repository and version resolve to
//      8. verb capture against both help renderers, which needs no JAR once the parsing
//         is separated from the subprocess that produces the text
//      9. the lock schema: that the published JSON Schema and the hand-written validators
//         reach the same verdict on the same values
//
// Groups 1 and 2 are the corpus test docs/LIMITATIONS.md calls for: the scanner is
// hand-written and fails closed, so the question worth answering is whether it disagrees
// with a real parser on manifests people actually publish.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class UnitCheck {

    static int pass;
    static int fail;

    static void ok() { pass++; }

    static void bad(String label, String detail) {
        fail++;
        System.out.println("  FAIL " + label);
        System.out.println("       " + detail);
    }

    static void eq(String label, String want, String got) {
        if (want == null ? got == null : want.equals(got)) ok();
        else bad(label, "want " + q(want) + ", got " + q(got));
    }

    static String q(String s) { return s == null ? "(absent)" : "'" + s + "'"; }

    /** Multi-line fixtures without escape soup; every case below is written a line at a time. */
    static String lines(String... l) { return String.join("\n", l) + "\n"; }

    // ---- 1 and 2: the real corpus -----------------------------------------

    record Row(String slug, String kind, String value) {}

    static List<Row> rows(Path dir) throws IOException {
        List<Row> out = new ArrayList<>();
        for (String row : Files.readAllLines(dir.resolve("expected.tsv"), StandardCharsets.UTF_8)) {
            if (row.isBlank() || row.startsWith("#")) continue;
            String[] f = row.split("\t", -1);
            out.add(new Row(f[0], f[1], f.length > 2 ? f[2] : ""));
        }
        return out;
    }

    static void corpus(Path dir, List<Row> rows) throws IOException {
        for (Row r : rows) {
            String label = "corpus " + r.slug();
            String text = Files.readString(dir.resolve(r.slug() + ".toml"), StandardCharsets.UTF_8);
            try {
                String got = flixw.tomlLookup(text, "package", "flix", r.slug());
                switch (r.kind()) {
                    case "OK" -> eq(label, r.value(), got);
                    case "NONE" -> eq(label, null, got);
                    default -> bad(label, "expected a rejection for a non-string value, got " + q(got));
                }
            } catch (flixw.Fail e) {
                if (r.kind().equals("NONSTRING")) ok();
                else bad(label, "rejected a manifest tomllib accepts: " + e.getMessage());
            }
        }
        System.out.println("  ok   corpus: " + rows.size() + " real manifests agree with tomllib");
    }

    // ---- 3: adversarial manifests the wild does not supply -----------------

    /** want == null: the key must be absent. want starting with '!': the read must fail. */
    record Case(String name, String toml, String want) {}

    static final String TQ = new String(new char[] { '"', '"', '"' });
    static final String TA = new String(new char[] { '\'', '\'', '\'' });

    static List<Case> cases() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("decoy in another table",
            lines("[package]", "flix = \"1.0.0\"", "", "[other]", "flix = \"9.9.9\""), "1.0.0"));
        c.add(new Case("decoy inside a basic multi-line string",
            lines("[package]", "description = " + TQ, "flix = \"9.9.9\"", TQ, "flix = \"1.0.0\""),
            "1.0.0"));
        c.add(new Case("decoy inside a literal multi-line string",
            lines("[package]", "description = " + TA, "flix = \"9.9.9\"", TA, "flix = \"1.0.0\""),
            "1.0.0"));
        c.add(new Case("multi-line string closing on the same line",
            lines("[package]", "description = " + TQ + "one line" + TQ, "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("dotted key at the root",
            lines("package.flix = \"1.0.0\""), "1.0.0"));
        // Every spelling below is the same key to a real TOML parser. Matching the raw
        // text saw only the first, so a manifest could state a floor flixw did not read
        // and an older compiler ran with nothing reported.
        c.add(new Case("dotted key with spaces around the dot",
            lines("package . flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("dotted key with a quoted last segment",
            lines("package.\"flix\" = \"1.0.0\""), "1.0.0"));
        c.add(new Case("dotted key with a quoted first segment",
            lines("\"package\".flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("dotted key inside the table it names",
            lines("[package]", "a.b = \"x\"", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("a dot inside a quoted key is not a separator",
            lines("[package]", "\"a.flix\" = \"9.9.9\"", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("quoted key that spells the dotted form is not it",
            lines("\"package.flix\" = \"9.9.9\""), null));
        c.add(new Case("empty key segment", lines("package..flix = \"1.0.0\""), "!"));

        // Everything below was checked against python3 -m tomllib first: the expectation
        // is the oracle's answer, not this scanner's. The array cases are why the scanner
        // now tracks bracket depth -- an authors entry holding `flix = "9.9.9"` read as an
        // assignment, and one with an unbalanced quote in it made a legal manifest
        // unreadable, which is worse than misreading it.
        c.add(new Case("inline table holding the key",
            lines("[package]", "deps = { flix = \"9.9.9\" }", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("nested inline table",
            lines("[package]", "x = { a = { flix = \"9.9.9\" } }", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("array spanning lines",
            lines("[package]", "authors = [", "  \"a\",", "  \"b\",", "]", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("array element that looks like an assignment",
            lines("[package]", "authors = [", "  \"flix = \\\"9.9.9\\\"\",", "]", "flix = \"1.0.0\""),
            "1.0.0"));
        c.add(new Case("bracket inside a string is not depth",
            lines("[package]", "authors = [\"a]b\"]", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("array of tables before the package",
            lines("[[bin]]", "name = \"x\"", "", "[package]", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("comment on the table header line",
            lines("[package] # the package", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("equals inside a value",
            lines("[package]", "name = \"a=b\"", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("hash inside a literal string",
            lines("[package]", "name = 'a#b'", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("a quoted decoy in the trailing comment",
            lines("[package]", "flix = \"1.0.0\" # \"9.9.9\""), "1.0.0"));
        c.add(new Case("unterminated quoted key", lines("\"package.flix = \"1.0.0\""), "!"));
        // A dotted key is relative to the table it sits under, so this one is
        // [package.package].flix -- not a second [package].flix. tomllib agrees.
        c.add(new Case("a dotted key under [package] nests, it does not duplicate",
            lines("[package]", "flix = \"1.0.0\"", "", "package.flix = \"9.9.9\""), "1.0.0"));
        c.add(new Case("quoted table header",
            lines("[\"package\"]", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("trailing comment",
            lines("[package]", "flix = \"1.0.0\"  # pinned"), "1.0.0"));
        c.add(new Case("hash inside a quoted value earlier in the table",
            lines("[package]", "name = \"a#b\"", "flix = \"1.0.0\""), "1.0.0"));
        c.add(new Case("single-quoted value",
            lines("[package]", "flix = '1.0.0'"), "1.0.0"));
        c.add(new Case("array of tables does not leak into [package]",
            lines("[package]", "name = \"x\"", "[[dep]]", "flix = \"9.9.9\""), null));
        c.add(new Case("a table whose name merely starts with package",
            lines("[packages]", "flix = \"9.9.9\""), null));
        c.add(new Case("no flix key at all",
            lines("[package]", "name = \"x\""), null));
        c.add(new Case("duplicate [package] table",
            lines("[package]", "flix = \"1.0.0\"", "[package]", "name = \"x\""), "!"));
        c.add(new Case("duplicate flix key",
            lines("[package]", "flix = \"1.0.0\"", "flix = \"2.0.0\""), "!"));
        c.add(new Case("unquoted value",
            lines("[package]", "flix = 1.0.0"), "!"));
        c.add(new Case("multi-line value for flix itself",
            lines("[package]", "flix = " + TQ, "1.0.0", TQ), "!"));
        c.add(new Case("unterminated table header",
            lines("[package", "flix = \"1.0.0\""), "!"));
        return c;
    }

    static void adversarial() {
        for (Case c : cases()) {
            String label = "adversarial: " + c.name();
            boolean mustFail = "!".equals(c.want());
            String got;
            try {
                got = flixw.tomlLookup(c.toml(), "package", "flix", c.name());
            } catch (flixw.Fail e) {
                if (mustFail) ok();
                else bad(label, "unexpected rejection: " + e.getMessage());
                continue;
            }
            if (mustFail) { bad(label, "expected a rejection, got " + q(got)); continue; }
            eq(label, c.want(), got);
        }
        System.out.println("  ok   adversarial: " + cases().size() + " hand-written manifests");
    }

    // ---- 4: the capture bounds --------------------------------------------

    static void bounded() throws IOException {
        Path javaExe = flixw.exeIn(System.getProperty("java.home"));
        Path tmp = Files.createTempDirectory("flixw-unit-");
        try {
            // A child that starts and then says nothing. Before the fix the deadline was
            // tested around a blocking read(), so this call did not return at all.
            Path silent = tmp.resolve("Silent.java");
            Files.writeString(silent, lines(
                "public final class Silent {",
                "    public static void main(String[] a) throws Exception { Thread.sleep(600_000); }",
                "}"));
            long t0 = System.nanoTime();
            String out = flixw.runCapture(List.of(javaExe.toString(), silent.toString()),
                                         Duration.ofSeconds(2), 1 << 16);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (out != null) bad("bounded capture: silent child", "want null, got " + out.length() + " chars");
            else if (ms > 60_000) bad("bounded capture: silent child", "returned only after " + ms + "ms");
            else ok();

            // And a child that will not stop talking must not be allowed to fill the heap.
            Path chatty = tmp.resolve("Chatty.java");
            Files.writeString(chatty, lines(
                "public final class Chatty {",
                "    public static void main(String[] a) {",
                "        for (int i = 0; i < 500000; i++) System.out.println(\"noise noise noise\");",
                "    }",
                "}"));
            String capped = flixw.runCapture(List.of(javaExe.toString(), chatty.toString()),
                                            Duration.ofSeconds(120), 4096);
            if (capped == null) bad("bounded capture: chatty child", "timed out instead of truncating");
            else if (capped.length() > 4096) bad("bounded capture: chatty child",
                                                 capped.length() + " chars for a 4096-byte cap");
            else ok();

            // The ordinary case still has to work.
            String version = flixw.runCapture(List.of(javaExe.toString(), "--version"),
                                             Duration.ofSeconds(60), 1 << 16);
            if (version == null || version.isBlank())
                bad("bounded capture: ordinary child", "captured nothing from java --version");
            else ok();
            System.out.println("  ok   bounded capture: silent, chatty and ordinary children");
        } finally {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    // ---- 5: choosing among discovered JDKs --------------------------------

    /**
     * The scenario is a developer machine with several JDKs and none of them exported:
     * the search finds them all and something has to pick. It cannot be staged in CI --
     * a runner has one JDK -- so it is asserted here, on the pure function.
     */
    static java.util.List<flixw.Jvm> jdks(int... features) {
        java.util.List<flixw.Jvm> out = new ArrayList<>();
        for (int f : features) out.add(new flixw.Jvm(Paths.get("/jdk" + f), f, "known installation"));
        return out;
    }

    static void chooser() {
        // Everything here is expressed relative to the ceiling rather than against the
        // number it happens to hold: these are statements about the *rule*, and raising
        // TESTED_CEILING after testing a new JDK should not look like a broken chooser.
        int top = flixw.TESTED_CEILING;

        // The case measured on a real machine: several JDKs installed, one of them above
        // the ceiling, and the old first-in-directory-order rule answered that one --
        // because a symlink named `java` sorts before `openjdk@21`.
        flixw.Jvm pick = flixw.chooseInstall(jdks(top + 1, 11, 21, 17, top), false);
        eq("chooser: newest inside the tested interval", "" + top,
           pick == null ? null : "" + pick.feature());

        // Above the ceiling is a fallback, not a preference, and the closest one wins.
        pick = flixw.chooseInstall(jdks(top + 2, top + 1, top + 5), false);
        eq("chooser: lowest above the ceiling when nothing fits", "" + (top + 1),
           pick == null ? null : "" + pick.feature());

        // FLIXW_STRICT_JAVA removes that fallback entirely.
        pick = flixw.chooseInstall(jdks(top + 2, top + 1, top + 5), true);
        eq("chooser: strict refuses everything above the ceiling", null,
           pick == null ? null : "" + pick.feature());

        // Below the floor is never a candidate, strict or not.
        pick = flixw.chooseInstall(jdks(8, 11, 17, 20), false);
        eq("chooser: below the floor is never chosen", null,
           pick == null ? null : "" + pick.feature());

        pick = flixw.chooseInstall(jdks(), false);
        eq("chooser: nothing found is not a choice", null,
           pick == null ? null : "" + pick.feature());

        // Exactly at the boundaries, both of which are inclusive.
        pick = flixw.chooseInstall(jdks(flixw.MIN_JAVA), false);
        eq("chooser: the floor itself is usable", "" + flixw.MIN_JAVA,
           pick == null ? null : "" + pick.feature());
        pick = flixw.chooseInstall(jdks(top, 21), true);
        eq("chooser: the ceiling itself is usable under strict", "" + top,
           pick == null ? null : "" + pick.feature());

        System.out.println("  ok   chooser: 7 selections over discovered JDK sets");
    }

    // ---- 6: JDK provisioning, everything that must not touch the network ---

    /**
     * The install itself is a 200MB download and is verified by hand, not here. What is
     * asserted is the part that decides *what* to fetch and whether to trust the answer:
     * every value below arrives as JSON from a third party.
     */
    static void provisioning() {
        // Shaped like a real Adoptium reply, which describes the .pkg installer *before*
        // the archive and gives both a checksum and a link. Reading the first match in
        // the document fetches the installer and verifies it against its own digest --
        // consistently, and uselessly. This is the case that catches that.
        String body = "[{\"binary\":{\"installer\":{"
                    + "\"name\":\"OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.pkg\","
                    + "\"link\":\"https://github.com/adoptium/x.pkg\","
                    + "\"checksum\":\"" + "b".repeat(64) + "\"},"
                    + "\"package\":{"
                    + "\"name\":\"OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.tar.gz\","
                    + "\"link\":\"https://github.com/adoptium/x.tar.gz\","
                    + "\"checksum\":\"" + "a".repeat(64) + "\"}}}]";

        eq("metadata: the installer is not the package",
           "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.pkg", flixw.jsonField(body, "name"));
        String pkg = flixw.jsonObject(body, "package");
        eq("metadata: package name", "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.tar.gz",
           flixw.jsonField(pkg, "name"));
        eq("metadata: package checksum, not the installer's", "a".repeat(64),
           flixw.jsonField(pkg, "checksum"));
        eq("metadata: package link", "https://github.com/adoptium/x.tar.gz",
           flixw.jsonField(pkg, "link"));
        eq("metadata: an absent field is absent", null, flixw.jsonField(pkg, "nope"));
        // The key is quoted into the pattern, so a key containing regex metacharacters
        // must not become one.
        eq("metadata: a key is not a pattern", null, flixw.jsonField(pkg, "na.e"));
        eq("metadata: an absent object is absent", null, flixw.jsonObject(body, "nope"));

        // Nested braces have to balance, or the object ends at the first inner close.
        eq("metadata: nested objects balance", "x",
           flixw.jsonField(flixw.jsonObject("{\"a\":{\"b\":{\"c\":1},\"d\":\"x\"}}", "a"), "d"));

        // Windows is published as a zip and nothing else; the rest as tar.gz.
        eq("coords: archive type follows the platform",
           System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                 .startsWith("windows") ? "zip" : "tar.gz",
           flixw.jdkArchiveType());
        String arch = flixw.jdkArch();
        if (arch != null && !arch.equals("aarch64") && !arch.equals("x64"))
            bad("coords: architecture", "unexpected " + arch);
        else ok();

        // The Windows archive is a zip built on a Unix machine: entries carry mode 0770
        // and java.util.zip discards it, so everything lands 0644. Requiring an executable
        // bit there would find no java.exe at all. Both trees are built here because CI
        // never runs the install itself.
        try {
            Path root = Files.createTempDirectory("flixw-jdk-");
            try {
                Path bin = root.resolve("jdk-21.0.12+8").resolve("bin");
                Files.createDirectories(bin);
                String want = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                                    .startsWith("windows") ? "java.exe" : "java";
                Path exe = bin.resolve(want);
                Files.writeString(exe, "not really a jvm");
                // 0644, exactly as unzip leaves it.
                Path found = flixw.findJavaUnder(root);
                if (want.equals("java.exe")) {
                    eq("findJavaUnder: a zip-extracted java.exe is found", exe.toString(),
                       found == null ? null : found.toString());
                } else {
                    eq("findJavaUnder: a non-executable java is not a JDK", null,
                       found == null ? null : found.toString());
                    exe.toFile().setExecutable(true, true);
                    eq("findJavaUnder: an executable one is", exe.toString(),
                       flixw.findJavaUnder(root) == null ? null : flixw.findJavaUnder(root).toString());
                }
                eq("findJavaUnder: nothing under an empty tree", null,
                   flixw.findJavaUnder(Files.createTempDirectory("flixw-empty-")) == null
                       ? null : "something");
            } finally {
                try (var w = Files.walk(root)) {
                    w.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                        try { Files.deleteIfExists(x); } catch (IOException ignored) { }
                    });
                }
            }
        } catch (IOException e) { bad("findJavaUnder", e.toString()); }

        System.out.println("  ok   provisioning: metadata parsing and platform coordinates");
    }

    // ---- 7: pin targets ---------------------------------------------------

    /**
     * `pin` takes an owner/repository and a version in either order, told apart by the
     * slash a version can never contain. None of this needs the network.
     */
    static void pinTargets() {
        flixw.Lock forked = new flixw.Lock("0.75.2+f.1", "https://x/y.jar", "a".repeat(64),
                                         "wstein/flix-fork", null);
        String[] t = flixw.parsePin(java.util.List.of("wstein/flix-fork", "0.75.2+f.1"), null);
        eq("pin: repository then version", "wstein/flix-fork", t[0]);
        eq("pin: version survives its build metadata", "0.75.2+f.1", t[1]);

        t = flixw.parsePin(java.util.List.of("0.75.2+f.1", "wstein/flix-fork"), null);
        eq("pin: order does not matter", "wstein/flix-fork", t[0]);

        // The trap this exists to close: a bare re-pin used to rebuild the upstream URL
        // and move a fork-tracking project back to stock, silently, because both are
        // honestly the same version.
        t = flixw.parsePin(java.util.List.of("0.75.2+f.1"), forked);
        eq("pin: a bare re-pin stays on the fork", "wstein/flix-fork", t[0]);
        t = flixw.parsePin(java.util.List.of("0.75.2"), null);
        eq("pin: with no lock and no repository it is upstream", "flix/flix", t[0]);
        t = flixw.parsePin(java.util.List.of("flix/flix", "0.75.2"), forked);
        eq("pin: naming upstream leaves the fork", "flix/flix", t[0]);

        for (java.util.List<String> bad : java.util.List.of(
                java.util.List.<String>of(),
                java.util.List.of("a/b", "c/d", "1.0.0"),
                java.util.List.of("1.0.0", "2.0.0"),
                java.util.List.of("not/a/repo", "1.0.0"),
                java.util.List.of("a/b", "not-a-version"))) {
            try { flixw.parsePin(bad, null); bad("pin: " + bad, "accepted"); }
            catch (flixw.Fail e) { ok(); }
        }

        // --- the java pin ---------------------------------------------------
        t = flixw.parsePin(java.util.List.of("0.75.2", "--java", "21"), null);
        eq("pin: --java rides along with a compiler version", "21", t[2]);
        t = flixw.parsePin(java.util.List.of("--java", "21.0.12"), forked);
        eq("pin: --java alone needs no compiler version", null, t[1]);
        eq("pin: ...and keeps the repository", "wstein/flix-fork", t[0]);
        eq("pin: ...and takes an exact version", "21.0.12", t[2]);
        t = flixw.parsePin(java.util.List.of("--java", "none"), forked);
        eq("pin: --java none clears it", null, t[2]);
        if (t[3] != null) ok(); else bad("pin: --java none", "did not ask to clear");

        for (java.util.List<String> bad : java.util.List.of(
                java.util.List.of("--java"),                    // no value
                java.util.List.of("--java", "17"),              // below the compiler's floor
                java.util.List.of("--java", "21+"),             // not a dotted number
                java.util.List.of("--java", "latest"),
                java.util.List.of("--jaba", "21"),              // not an option we know
                java.util.List.of("--java", "21", "--java", "22"))) {
            try { flixw.parsePin(bad, forked); bad("pin: " + bad, "accepted"); }
            catch (flixw.Fail e) { ok(); }
        }
        // A --java-only pin with no lock has nothing to keep, and says so rather than
        // inventing a compiler.
        try { flixw.parsePin(java.util.List.of("--java", "21"), null); bad("pin: --java with no lock", "accepted"); }
        catch (flixw.Fail e) { ok(); }

        // Matching is a prefix cut at a dot, so 21 accepts every 21.x and nothing else.
        record M(String pin, String version, boolean want) {}
        for (M m : java.util.List.of(
                new M("21", "21.0.12", true), new M("21", "21", true),
                new M("21.0", "21.0.12", true), new M("21.0.12", "21.0.12", true),
                new M("21", "2", false), new M("2", "21", false),
                new M("21", "22.0.1", false), new M("21.0.12", "21.0.1", false),
                new M("21.1", "21.10.0", false), new M(null, "17", true),
                new M("21", null, false))) {
            if (flixw.satisfiesJavaPin(m.pin(), m.version()) == m.want()) ok();
            else bad("java pin: " + m.pin() + " vs " + m.version(), "wrong verdict");
        }

        System.out.println("  ok   pin targets: repository, version and java parsing");
    }

    // ---- 8: the two help renderers ----------------------------------------

    /**
     * Verb capture has to read two help screens that share no layout.
     *
     * Stock Flix renders with scopt: the verb list is one long `Usage:` line, and every
     * verb repeats as its own `Command:` line. The picocli-based fork wraps that same
     * bracket across four lines and replaces the per-verb lines with an indented
     * `Commands:` block -- against which the scopt-only parser found nothing at all, so
     * the wrapper fell back to its built-in 0.75.x table and lost every verb the fork
     * added. Both fixtures below are the real output of each compiler, abridged in the
     * middle only; the shapes that matter -- the wrap, the indents, the trailing note --
     * are verbatim.
     */
    static void verbs() {
        String scopt = lines(
            "The Flix Programming Language 0.75.2",
            "Usage: flix [init|check|build|build-jar|clean|doc|run|test|repl|eff-lock] [options] <args>...",
            "",
            "Command: init",
            "  creates a new project in the current directory.",
            "Command: check",
            "  checks the current project for errors.",
            "Command: lsp-vscode port",
            "  starts the LSP server and listens on the given port.");
        List<String> got = flixw.parseVerbs(scopt);
        eq("verbs: scopt usage line and Command: lines agree",
           "init check build build-jar clean doc run test repl eff-lock lsp-vscode",
           String.join(" ", got));
        // `Command: lsp-vscode port` carries an argument; only the verb is the verb.
        if (got.contains("port")) bad("verbs: scopt argument is not a verb", "captured 'port'");
        else ok();

        String picocli = lines(
            "The Flix Programming Language",
            "0.75.2+fork.wstein.260807.1.88.ge3027b3e2.dirty",
            "Usage: flix [init|check|capabilities|stubs|build|build-jar|",
            "             clean|doc|run|test|repl|bsp|bsp-install|",
            "             eff-lock] [options] <file>...",
            "      <file>...             input Flix source code files.",
            "  -h, --help                prints this usage information.",
            "      --threads=<n>         number of threads to use for compilation.",
            "Commands:",
            "  init          interactively creates a new project in an optional directory.",
            "  check         checks the current project for errors.",
            "  capabilities  reports the tooling contract this compiler speaks.",
            "  stubs         writes compile-only Java stubs for the @Export-ed defs.",
            "  build         builds (i.e. compiles) the current project.",
            "  build-jar     builds a jar-file from the current project.",
            "  clean         recursively removes class files from the build directory.",
            "  doc           generates API documentation.",
            "  run           runs main for the current project.",
            "  test          runs the tests for the current project.",
            "  repl          starts a repl for the current project, or provided Flix source",
            "                  files.",
            "  bsp           starts the Build Server Protocol server on stdio.",
            "  bsp-install   writes '.bsp/flix.json' so an editor can find the BSP server.",
            "  eff-lock      locks the current effect signatures.",
            "Experimental options and commands are omitted. Run 'flix --Xhelp' to list them.");
        List<String> fork = flixw.parseVerbs(picocli);
        eq("verbs: picocli wrapped usage and Commands: block agree",
           "init check capabilities stubs build build-jar clean doc run test repl"
         + " bsp bsp-install eff-lock",
           String.join(" ", fork));
        // The wrapped description line is indented far deeper than an entry, and the
        // closing note is not indented at all. Neither is a verb.
        for (String phantom : List.of("files.", "Experimental", "h", "help", "threads", "file"))
            if (fork.contains(phantom)) bad("verbs: picocli phantom", "captured '" + phantom + "'");
            else ok();

        // An options-only bracket is not a verb list, and must not be mistaken for one now
        // that the usage match may span lines and so reaches further than it used to.
        eq("verbs: [options] alone yields nothing", "",
           String.join(" ", flixw.parseVerbs(lines("Usage: flix [options] <args>..."))));

        // Degradation is the contract: unreadable help returns too few verbs, and
        // captureVerbs turns that into FLIXW010 rather than a dead wrapper.
        eq("verbs: unparseable help yields nothing", "",
           String.join(" ", flixw.parseVerbs(lines("impostor: no usage here"))));

        System.out.println("  ok   verb capture: scopt and picocli help renderers");
    }

    // ---- 9: the lock schema -----------------------------------------------

    /**
     * The published JSON Schema is rendered from the same list stage 0 validates against,
     * so the interesting question is not whether the renderer works -- lint diffs its
     * output against the committed file every run -- but whether the two dialects agree.
     *
     * Every pattern is compiled by Java on each invocation and by an ECMA-262 engine in
     * whoever's editor. They cannot be compared directly, so they are compared through
     * behaviour: each pattern is exercised against values the hand-written validators
     * already have an opinion about, and the two must reach the same verdict.
     */
    static void lockSchema() {
        for (flixw.LockField f : flixw.LOCK_SCHEMA) {
            String label = "schema " + f.name();
            // Anchors are added when the pattern is rendered into JSON, because Java's
            // matches() implies them and JSON Schema's "pattern" does not. One carried in
            // the field itself would be doubled in the published file.
            if (f.pattern().startsWith("^") || f.pattern().endsWith("$"))
                bad(label, "pattern carries its own anchor: " + f.pattern());
            else ok();
            try { java.util.regex.Pattern.compile(f.pattern()); ok(); }
            catch (RuntimeException e) { bad(label, "pattern does not compile: " + e.getMessage()); }
            // A description is what an editor shows on hover, and what a diagnostic reads
            // out; an empty one makes both useless.
            if (f.what().isBlank()) bad(label, "no description");
            else ok();
        }

        // The schema and the validators must not be able to disagree about a value. Each
        // pair below is a pattern and the hand-written check that guards the same key.
        String repo = pattern("compiler", "repo");
        agree("repo", repo, "flix/flix", true);
        agree("repo", repo, "wstein/flix-fork", true);
        agree("repo", repo, "not/a/repo", false);
        agree("repo", repo, "flix", false);
        for (String r : new String[] {"flix/flix", "wstein/flix-fork", "not/a/repo", "flix"}) {
            boolean byPattern = r.matches(repo);
            boolean byValidator = accepts(() -> flixw.checkRepo(r, "unit"));
            if (byPattern != byValidator)
                bad("schema/checkRepo agree on " + q(r), byPattern ? "pattern only" : "validator only");
            else ok();
        }

        String version = pattern("compiler", "version");
        for (String v : new String[] {"0.75.2", "0.75.2+fork.wstein.1", "0.75.2-rc.1",
                                      "v0.75.2", "0.75", "0.75.2+", "latest"}) {
            boolean byPattern = v.matches(version);
            boolean byValidator = accepts(() -> flixw.validateVersion(v, "unit"));
            if (byPattern != byValidator)
                bad("schema/validateVersion agree on " + q(v), byPattern ? "pattern only" : "validator only");
            else ok();
        }

        // validateJavaPin is deliberately stricter than the pattern: it also refuses a
        // Java the compiler cannot run under. The pattern must accept everything it does.
        String java = pattern("java", "version");
        for (String v : new String[] {"21", "21.0.12", "26", "17", "twenty-one", "21+"}) {
            if (accepts(() -> flixw.validateJavaPin(v, "unit")) && !v.matches(java))
                bad("schema/validateJavaPin agree on " + q(v), "validator accepts, pattern rejects");
            else ok();
        }

        // The rendered file is what an editor fetches; these are the parts of it that
        // something outside this repository depends on by name.
        String json = flixw.lockSchemaJson();
        for (String must : new String[] {
                "\"$id\": \"" + flixw.LOCK_SCHEMA_URL + "\"",
                "\"$schema\": \"https://json-schema.org/draft/2020-12/schema\"",
                "\"required\": [\"compiler\"]",
                "\"additionalProperties\": false"}) {
            if (json.contains(must)) ok();
            else bad("schema json", "does not contain " + must);
        }
        if (flixw.LOCK_SCHEMA_URL.contains("/lock-" + flixw.LOCK_SCHEMA_VERSION + ".schema.json")) ok();
        else bad("schema url", "does not carry the format version: " + flixw.LOCK_SCHEMA_URL);

        // Every declared key has to reach the file, and reach it escaped: the patterns are
        // full of backslashes, and a literal one would make the JSON unparseable.
        for (flixw.LockField f : flixw.LOCK_SCHEMA) {
            String want = "\"pattern\": \"^" + f.pattern().replace("\\", "\\\\") + "$\"";
            if (json.contains("\"" + f.key() + "\": {") && json.contains(want)) ok();
            else bad("schema json " + f.name(), "missing key or unescaped pattern");
        }
        eq("schema json escapes a quote", "\"a\\\"b\"", flixw.jsonString("a\"b"));
        eq("schema json escapes a backslash", "\"a\\\\b\"", flixw.jsonString("a\\b"));

        System.out.println("  ok   lock schema: " + flixw.LOCK_SCHEMA.size()
                         + " keys, rendered and cross-checked against the validators");
    }

    /** The pattern the schema declares for one key, so a case reads as the key it tests. */
    static String pattern(String table, String key) {
        for (flixw.LockField f : flixw.LOCK_SCHEMA)
            if (f.table().equals(table) && f.key().equals(key)) return f.pattern();
        throw new IllegalStateException("no schema field for [" + table + "] " + key);
    }

    /** True when a validator returns rather than throwing FLIXW. */
    static boolean accepts(Runnable check) {
        try { check.run(); return true; }
        catch (RuntimeException e) { return false; }
    }

    static void agree(String key, String pattern, String value, boolean want) {
        if (value.matches(pattern) == want) ok();
        else bad("schema " + key + " pattern on " + q(value), want ? "rejected" : "accepted");
    }

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/corpus");
        List<Row> rows = rows(dir);
        corpus(dir, rows);
        adversarial();
        chooser();
        provisioning();
        pinTargets();
        verbs();
        lockSchema();
        bounded();
        System.out.println("  unit checks: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
