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

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/corpus");
        List<Row> rows = rows(dir);
        corpus(dir, rows);
        rewriteProperty(dir, rows);
        adversarial();
        crlf();
        bounded();
        System.out.println("  unit checks: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
