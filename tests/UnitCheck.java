// flixw unit checks -- the parts of stage 0 the shell suite cannot reach from outside.
//
//   javac -d <out> src/flix.java tests/UnitCheck.java
//   java -cp <out> UnitCheck tests/corpus
//
// Compiled and run by tests/run.sh; not a separate CI entry point. Four groups:
//
//   1. the manifest scanner over a corpus of real published flix.toml files, compared
//      against what python3's tomllib -- a conforming parser -- read from each of them
//   2. the pin rewrite as a property over that same corpus: it changes exactly one line
//   3. hand-written adversarial manifests, which the real corpus does not contain
//   4. the bounds on runCapture, which end to end would cost a 30-second test case
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
                String got = flix.tomlLookup(text, "package", "flix", r.slug());
                switch (r.kind()) {
                    case "OK" -> eq(label, r.value(), got);
                    case "NONE" -> eq(label, null, got);
                    default -> bad(label, "expected a rejection for a non-string value, got " + q(got));
                }
            } catch (flix.Fail e) {
                if (r.kind().equals("NONSTRING")) ok();
                else bad(label, "rejected a manifest tomllib accepts: " + e.getMessage());
            }
        }
        System.out.println("  ok   corpus: " + rows.size() + " real manifests agree with tomllib");
    }

    /**
     * pin's contract over the corpus: the document it returns differs from the input on
     * exactly one line, that line is the one holding [package].flix, and reading the
     * result back yields the new version. Anything else is a manifest-eating rewrite.
     */
    static void rewriteProperty(Path dir, List<Row> rows) throws IOException {
        int n = 0;
        for (Row r : rows) {
            if (!r.kind().equals("OK")) continue;
            String label = "rewrite " + r.slug();
            String text = Files.readString(dir.resolve(r.slug() + ".toml"), StandardCharsets.UTF_8);
            String updated = flix.rewritePackageFlix(text, "9.9.9", r.slug());
            if (updated == null) {
                bad(label, "declined to rewrite a manifest that has a [package].flix");
                continue;
            }
            eq(label + " reads back", "9.9.9", flix.tomlLookup(updated, "package", "flix", r.slug()));
            String[] before = text.split("\n", -1);
            String[] after = updated.split("\n", -1);
            if (before.length != after.length) {
                bad(label, "line count changed: " + before.length + " -> " + after.length);
                continue;
            }
            int changed = 0;
            for (int i = 0; i < before.length; i++) if (!before[i].equals(after[i])) changed++;
            if (changed != 1) bad(label, changed + " lines changed, want exactly 1");
            else ok();
            n++;
        }
        System.out.println("  ok   rewrite: " + n + " manifests repinned, one line each");
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
                got = flix.tomlLookup(c.toml(), "package", "flix", c.name());
            } catch (flix.Fail e) {
                if (mustFail) ok();
                else bad(label, "unexpected rejection: " + e.getMessage());
                continue;
            }
            if (mustFail) { bad(label, "expected a rejection, got " + q(got)); continue; }
            eq(label, c.want(), got);

            // The writer must agree with the reader on every one of these, which is the
            // invariant that broke: pin had a second scanner that saw different keys.
            String updated;
            try {
                updated = flix.rewritePackageFlix(c.toml(), "2.0.0", c.name());
            } catch (flix.Fail e) {
                bad(label + " (rewrite)", "reader accepted, writer rejected: " + e.getMessage());
                continue;
            }
            if (c.want() == null) {
                if (updated != null) bad(label + " (rewrite)", "rewrote a key the reader does not see");
                else ok();
                continue;
            }
            if (updated == null) {
                bad(label + " (rewrite)", "writer found no key the reader read as " + q(c.want()));
                continue;
            }
            eq(label + " (rewrite)", "2.0.0", flix.tomlLookup(updated, "package", "flix", c.name()));
            if (updated.contains("9.9.9") != c.toml().contains("9.9.9"))
                bad(label + " (rewrite)", "a decoy value was disturbed");
            else ok();
        }
        System.out.println("  ok   adversarial: " + cases().size() + " hand-written manifests");
    }

    /** CRLF is checked separately: the fixture must not go through lines(). */
    static void crlf() {
        String doc = String.join("\r\n", "[package]", "name = \"x\"", "flix = \"1.0.0\"") + "\r\n";
        eq("crlf read", "1.0.0", flix.tomlLookup(doc, "package", "flix", "crlf"));
        String updated = flix.rewritePackageFlix(doc, "2.0.0", "crlf");
        if (updated == null) { bad("crlf rewrite", "no key found"); return; }
        eq("crlf rewrite", "2.0.0", flix.tomlLookup(updated, "package", "flix", "crlf"));
        long crs = updated.chars().filter(ch -> ch == '\r').count();
        long lfs = updated.chars().filter(ch -> ch == '\n').count();
        if (crs != lfs) bad("crlf rewrite", "line endings changed: " + crs + " CR, " + lfs + " LF");
        else ok();
        System.out.println("  ok   crlf: endings survive a repin");
    }

    // ---- 4: the capture bounds --------------------------------------------

    static void bounded() throws IOException {
        Path javaExe = flix.exeIn(System.getProperty("java.home"));
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
            String out = flix.runCapture(List.of(javaExe.toString(), silent.toString()),
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
            String capped = flix.runCapture(List.of(javaExe.toString(), chatty.toString()),
                                            Duration.ofSeconds(120), 4096);
            if (capped == null) bad("bounded capture: chatty child", "timed out instead of truncating");
            else if (capped.length() > 4096) bad("bounded capture: chatty child",
                                                 capped.length() + " chars for a 4096-byte cap");
            else ok();

            // The ordinary case still has to work.
            String version = flix.runCapture(List.of(javaExe.toString(), "--version"),
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
    static java.util.List<flix.Jvm> jdks(int... features) {
        java.util.List<flix.Jvm> out = new ArrayList<>();
        for (int f : features) out.add(new flix.Jvm(Paths.get("/jdk" + f), f, "known installation"));
        return out;
    }

    static void chooser() {
        // The case measured on a real machine: 11, 17, 21, 25 and 26 installed, and the
        // old first-in-directory-order rule answered 26 -- above the tested ceiling, and
        // warned about -- because a symlink named `java` sorts before `openjdk@21`.
        flix.Jvm pick = flix.chooseInstall(jdks(26, 11, 21, 17, 25), false);
        eq("chooser: newest inside the tested interval", "25", pick == null ? null : "" + pick.feature());

        // Above the ceiling is a fallback, not a preference, and the closest one wins.
        pick = flix.chooseInstall(jdks(27, 26, 30), false);
        eq("chooser: lowest above the ceiling when nothing fits", "26",
           pick == null ? null : "" + pick.feature());

        // FLIXW_STRICT_JAVA removes that fallback entirely.
        pick = flix.chooseInstall(jdks(27, 26, 30), true);
        eq("chooser: strict refuses everything above the ceiling", null,
           pick == null ? null : "" + pick.feature());

        // Below the floor is never a candidate, strict or not.
        pick = flix.chooseInstall(jdks(8, 11, 17, 20), false);
        eq("chooser: below the floor is never chosen", null,
           pick == null ? null : "" + pick.feature());

        pick = flix.chooseInstall(jdks(), false);
        eq("chooser: nothing found is not a choice", null,
           pick == null ? null : "" + pick.feature());

        // Exactly at the boundaries, both of which are inclusive.
        pick = flix.chooseInstall(jdks(21), false);
        eq("chooser: the floor itself is usable", "21", pick == null ? null : "" + pick.feature());
        pick = flix.chooseInstall(jdks(25, 21), true);
        eq("chooser: the ceiling itself is usable under strict", "25",
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
           "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.pkg", flix.jsonField(body, "name"));
        String pkg = flix.jsonObject(body, "package");
        eq("metadata: package name", "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.tar.gz",
           flix.jsonField(pkg, "name"));
        eq("metadata: package checksum, not the installer's", "a".repeat(64),
           flix.jsonField(pkg, "checksum"));
        eq("metadata: package link", "https://github.com/adoptium/x.tar.gz",
           flix.jsonField(pkg, "link"));
        eq("metadata: an absent field is absent", null, flix.jsonField(pkg, "nope"));
        // The key is quoted into the pattern, so a key containing regex metacharacters
        // must not become one.
        eq("metadata: a key is not a pattern", null, flix.jsonField(pkg, "na.e"));
        eq("metadata: an absent object is absent", null, flix.jsonObject(body, "nope"));

        // Nested braces have to balance, or the object ends at the first inner close.
        eq("metadata: nested objects balance", "x",
           flix.jsonField(flix.jsonObject("{\"a\":{\"b\":{\"c\":1},\"d\":\"x\"}}", "a"), "d"));

        // Windows is published as a zip and nothing else; the rest as tar.gz.
        eq("coords: archive type follows the platform",
           System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                 .startsWith("windows") ? "zip" : "tar.gz",
           flix.jdkArchiveType());
        String arch = flix.jdkArch();
        if (arch != null && !arch.equals("aarch64") && !arch.equals("x64"))
            bad("coords: architecture", "unexpected " + arch);
        else ok();

        System.out.println("  ok   provisioning: metadata parsing and platform coordinates");
    }

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/corpus");
        List<Row> rows = rows(dir);
        corpus(dir, rows);
        rewriteProperty(dir, rows);
        adversarial();
        crlf();
        chooser();
        provisioning();
        bounded();
        System.out.println("  unit checks: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
