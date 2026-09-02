// flixw unit checks -- the parts of stage 0 the shell suite cannot reach from outside.
//
//   javac -d <out> src/stage0/flixw.java src/assets/flixw-help.java src/assets/flixw-jdk.java \
//         src/assets/flixw-examples.java src/assets/flixw-local.java tests/UnitCheck.java
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

    /**
     * One field of an option row, or a marker when the option is missing entirely.
     *
     * <p>Indexing the array directly reads better and fails worse: a parser that drops
     * an option -- the exact regression these cases exist for -- then throws NPE out of
     * main, which names no assertion and abandons every check after it.
     */
    static String field(java.util.Map<String, String[]> rows, String key, int i) {
        String[] r = rows.get(key);
        return r == null ? "(no such option)" : r[i];
    }

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
        // These parse Adoptium's reply, which now lives in src/assets/flixw-jdk.java -- stage 0
        // no longer provisions. findJavaUnder below is still stage 0's: it *discovers* a
        // JDK the asset installed earlier, on every run, without fetching the asset.
        //
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
           "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.pkg", flixwjdk.jsonField(body, "name"));
        String pkg = flixwjdk.jsonObject(body, "package");
        eq("metadata: package name", "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12_8.tar.gz",
           flixwjdk.jsonField(pkg, "name"));
        eq("metadata: package checksum, not the installer's", "a".repeat(64),
           flixwjdk.jsonField(pkg, "checksum"));
        eq("metadata: package link", "https://github.com/adoptium/x.tar.gz",
           flixwjdk.jsonField(pkg, "link"));
        eq("metadata: an absent field is absent", null, flixwjdk.jsonField(pkg, "nope"));
        // The key is quoted into the pattern, so a key containing regex metacharacters
        // must not become one.
        eq("metadata: a key is not a pattern", null, flixwjdk.jsonField(pkg, "na.e"));
        eq("metadata: an absent object is absent", null, flixwjdk.jsonObject(body, "nope"));

        // Nested braces have to balance, or the object ends at the first inner close.
        eq("metadata: nested objects balance", "x",
           flixwjdk.jsonField(flixwjdk.jsonObject("{\"a\":{\"b\":{\"c\":1},\"d\":\"x\"}}", "a"), "d"));

        // Windows is published as a zip and nothing else; the rest as tar.gz.
        eq("coords: archive type follows the platform",
           System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                 .startsWith("windows") ? "zip" : "tar.gz",
           flixwjdk.jdkArchiveType());
        String arch = flixwjdk.jdkArch();
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

    // ---- 13: which release `--upgrade` offers -----------------------------

    /**
     * `wrapper --upgrade` must not offer a pre-release, and the whole of that policy is one
     * URL.
     *
     * <p>Every 0.x flixw is published as a GitHub pre-release, and GitHub's `releases/latest`
     * excludes those -- so an adopter running `--upgrade` is never moved onto one, and asks for
     * a pre-release by tag through `FLIXW_RELEASE_SOURCE` instead. That is a deliberate product
     * decision explained at length in {@code latestBase}'s own comment, and until now nothing
     * checked it: changing this URL to any other spelling of "newest" would have started
     * shipping pre-releases to everyone with a green suite.
     *
     * <p>The override branch is not asserted here, because `env` reads the real environment and
     * a test cannot set one for its own JVM. tests/run.sh covers it end to end instead, which is
     * the better test anyway -- it upgrades an actual project through an actual fixture release.
     */
    static void releaseChannel() {
        String base = flixw.latestBase();
        eq("upgrade: the default release source is GitHub's latest",
           "https://github.com/wstein/flixw/releases/latest/download/", base);
        // Named separately from the equality above: if someone rewrites that URL, this is the
        // assertion whose failure says *why* it mattered.
        if (base.contains("/releases/latest/")) ok();
        else bad("upgrade: the default must be `releases/latest`, which skips pre-releases",
                 "got " + base);
        if (base.endsWith("/")) ok();
        else bad("upgrade: the base must end in a slash, since asset names are appended to it",
                 "got " + base);
    }

    // ---- 13: the asset set a release publishes ----------------------------

    /**
     * Which release `wrapper --upgrade` reaches for.
     *
     * <p>`latest` skips pre-releases and is whatever GitHub says today; a named version
     * is a fixed URL. FLIXW_RELEASE_SOURCE outranks both, because it already names one
     * release -- a mirror, or a staging directory -- so appending a version to it would
     * ask for a release inside a release.
     */
    static void upgradeTarget() {
        eq("upgrade: no version means latest", "true",
           String.valueOf(flixw.releaseBase(null).endsWith("/releases/latest/download/")));
        eq("upgrade: a version names its own release",
           "https://github.com/wstein/flixw/releases/download/v0.25.8/",
           flixw.releaseBase("0.25.8"));
        // Both spellings of one release resolve the same way, since `strip` runs first.
        eq("upgrade: latest and a version differ", "false",
           String.valueOf(flixw.releaseBase(null).equals(flixw.releaseBase("0.25.8"))));
    }

    /**
     * `--upgrade --pre-release` reaches a release {@code /releases/latest} cannot see --
     * one still finishing `verify` in release.yaml, or a real pre-release version -- by
     * asking the releases API for the newest entry instead of the filtered shortcut.
     *
     * <p>The live request itself is not asserted here for the same reason {@link
     * #releaseChannel} does not assert one: a test cannot make the JVM see a fake network
     * response. What is asserted is the pure half -- pulling {@code tag_name} out of a
     * response body -- and the URL that request is made against, which is what a rename of
     * either would actually break.
     */
    static void prereleaseChannel() {
        eq("pre-release: the endpoint is the releases API, not the latest shortcut", "true",
           String.valueOf(flixw.RELEASES_API.startsWith(
               "https://api.github.com/repos/wstein/flixw/releases")));
        // Newest-first by creation date is the API's own contract, not flixw's -- so the
        // one thing worth pinning here is that only one object is asked for.
        eq("pre-release: only the newest entry is requested", "true",
           String.valueOf(flixw.RELEASES_API.contains("per_page=1")));
        // A real response carries dozens of fields before tag_name; the extractor must not
        // require any particular one to come first.
        String body = "[{\"url\":\"https://api.github.com/repos/wstein/flixw/releases/1\","
                    + "\"id\":1,\"prerelease\":true,\"tag_name\":\"v0.31.0-rc.1\","
                    + "\"name\":\"flixw 0.31.0-rc.1\"}]";
        eq("pre-release: tag_name is pulled out of a real response shape",
           "v0.31.0-rc.1", flixw.extractTagName(body));
        eq("pre-release: no tag_name at all yields no target", null,
           flixw.extractTagName("[{\"id\":1}]"));
        eq("pre-release: an empty or broken body yields no target", null,
           flixw.extractTagName(""));
    }

    /**
     * Rewriting a release asset URL for a newer tag, which is how upgrade finds one.
     *
     * <p>Derived from the URL the lock already records rather than from a naming scheme:
     * that URL worked once, and between two releases of one project only the tag moves.
     * Anything not a github release asset has to be declined rather than guessed at.
     */
    static void upgradeUrls() {
        String gh = "https://github.com/o/r/releases/download/v1.2.3/plugin.jar";
        eq("upgrade: a github asset is recognised", "true",
           String.valueOf(flixw.GH_ASSET.matcher(gh).matches()));
        eq("upgrade: a file url is not", "false",
           String.valueOf(flixw.GH_ASSET.matcher("file:///tmp/p.jar").matches()));
        // A tag names one release whichever way it is spelled; the lock records the
        // version, so the two have to compare equal or every run looks like an upgrade.
        eq("upgrade: v-prefix is not a different version", "1.2.3", flixw.strip("v1.2.3"));
        eq("upgrade: a bare version survives", "1.2.3", flixw.strip("1.2.3"));
        eq("upgrade: no source, nothing to derive", null, flixw.newerAsset(null, "1.0.0"));
        eq("upgrade: a non-github source is declined", null,
           flixw.newerAsset("https://example.invalid/p.jar", "1.0.0"));
    }

    /**
     * Declared verbs: the shape rule, and what sanitising a manifest value means.
     *
     * <p>A manifest attribute is third-party text headed for a terminal and for a
     * committed file, so an escape character in one is a terminal control sequence in
     * every help screen that renders it. Stripped rather than escaped: nothing
     * legitimate in a one-line description needs one.
     */
    static void declaredVerbs() {
        String esc = String.valueOf((char) 27);
        eq("verb: control characters do not survive", "a [31m b",
           flixw.sanitize("a " + esc + "[31m\tb", 100));
        eq("verb: length is bounded", "8",
           String.valueOf(flixw.sanitize("abcdefghijklmnop", 8).length()));
        eq("verb: null is not a value", "", flixw.sanitize(null, 10));
        // The owner lookup dispatch uses: a lock is the only place a claim is recorded.
        flixw.Lock l = new flixw.Lock("0.75.3", "https://x/f.jar", "a".repeat(64),
            "flix/flix", null, null, java.util.Map.of("m",
                new flixw.PluginDep("1.0.0", "b".repeat(64), null, "", "metrics")));
        eq("verb: the lock names the owner", "m", flixw.commandOwner(l, "metrics"));
        eq("verb: an unclaimed word has none", null, flixw.commandOwner(l, "nosuch"));

        // isUpstream gates run's auto-- and help's option curation: both must apply only
        // to a verified, unoverridden flix/flix pin, never to a fork or a FLIX_JAR override
        // -- format(help) alone cannot tell a fork reusing scopt's layout from the real
        // thing, so this is the actual provenance check both features rely on.
        flixw.Lock explicit = new flixw.Lock("0.75.3", "https://x/f.jar", "a".repeat(64),
            "flix/flix", null, null, java.util.Map.of());
        flixw.Lock defaultRepo = new flixw.Lock("0.75.3", "https://x/f.jar", "a".repeat(64),
            null, null, null, java.util.Map.of());
        flixw.Lock fork = new flixw.Lock("0.75.3", "https://x/f.jar", "a".repeat(64),
            "wstein/flix-fork", null, null, java.util.Map.of());
        if (flixw.isUpstream(explicit, false) && flixw.isUpstream(defaultRepo, false)
            && !flixw.isUpstream(explicit, true)
            && !flixw.isUpstream(fork, false)
            && !flixw.isUpstream(null, false)) ok();
        else bad("isUpstream", "explicit=" + flixw.isUpstream(explicit, false)
                             + " overridden=" + flixw.isUpstream(explicit, true)
                             + " fork=" + flixw.isUpstream(fork, false));
    }

    /**
     * A plugin's declared description, through the lock reader and writer.
     *
     * <p>It is the one free-text value in the format -- every other is pattern-checked into a
     * shape that cannot contain a quote or a backslash -- so the round trip is what says the
     * escaping is right rather than merely present.
     */
    static void pluginDescription() {
        String lock = flixw.lockText("0.25.5", "flix/flix", "0.75.3", "https://x/flix.jar",
            "a".repeat(64), "0.75.3", null,
            java.util.Map.of("demo", new flixw.PluginDep("1.0.0", "b".repeat(64),
                                                         "https://x/p.jar", "does a thing", "")));
        eq("plugin desc: written into the lock", "true",
           String.valueOf(lock.contains("description = \"does a thing\"")));
        // A plugin that declares nothing gains no empty key to explain.
        String bare = flixw.lockText("0.25.5", "flix/flix", "0.75.3", "https://x/flix.jar",
            "a".repeat(64), "0.75.3", null,
            java.util.Map.of("demo", new flixw.PluginDep("1.0.0", "b".repeat(64), null, "", "")));
        eq("plugin desc: absent when undeclared", "false",
           String.valueOf(bare.contains("description")));
        // The two escapes a TOML basic string needs, on the only value that can carry them.
        eq("plugin desc: a quote is escaped", "a \\\"b\\\"", flixw.tomlEscape("a \"b\""));
        eq("plugin desc: a backslash is escaped", "a\\\\b", flixw.tomlEscape("a\\b"));
    }

    /**
     * Option rows in both help layouts, which is where two defects lived undetected.
     *
     * <p>Split from the subprocess for the same reason verb capture is: a fork's layout can be
     * asserted here without a fork's jar, and the corpus is otherwise entirely stock, which is
     * precisely why nobody noticed. picocli attaches a parameter with {@code =} rather than a
     * space, and wraps prose that scopt leaves on one line -- so against a real fork the parser
     * dropped every value-taking option and truncated the rest mid-sentence.
     *
     * <p>Stock is not innocent either: a name long enough to fill the column pushes the
     * description onto the next line there too, which is how {@code --Xbenchmark-incremental}
     * went missing from a help screen generated by the compiler that documents it.
     */
    static void optionRows() {
        String scopt = "  --entrypoint <value>     specifies the main entry point.\n"
                     + "  --Xbenchmark-incremental\n"
                     + "                           [experimental] benchmarks incremental mode.\n"
                     + "  --yes                    automatically answer yes to all prompts.\n"
                     + "Command: init\n"
                     + "  creates a new project in the current directory.\n";
        java.util.Map<String, String[]> o = flixwhelp.options(scopt);
        eq("options/scopt: a spaced parameter is read", "value", field(o, "--entrypoint", 2));
        // The row whose description starts on the next line is an option, not a non-match.
        eq("options/scopt: a description below the name is joined",
           "[experimental] benchmarks incremental mode.", field(o, "--Xbenchmark-incremental", 3));
        // A `Command:` block sits at a shallower indent, so it ends the option rather than
        // being appended to it -- otherwise the last option inherits the whole command list.
        eq("options/scopt: a command block is not swallowed",
           "automatically answer yes to all prompts.", field(o, "--yes", 3));

        String pico = "      --coverage            enables source-level coverage for\n"
                    + "                              tests.\n"
                    + "      --coverage-output=<path>\n"
                    + "                            path to write the report (JSON).\n"
                    + "      --threads=<n>         number of threads to use.\n"
                    + "  -h, --help                prints this usage information.\n"
                    + "Commands:\n"
                    + "  init          creates a new project.\n";
        o = flixwhelp.options(pico);
        // The six options a real fork lost: every one of them takes a value, which is also
        // what a completion most needs to know about an option.
        eq("options/picocli: an =parameter is read", "n", field(o, "--threads", 2));
        eq("options/picocli: a long name with the value below it survives",
           "path", field(o, "--coverage-output", 2));
        // The truncation that deleted the word saying which command the flag is for.
        eq("options/picocli: wrapped prose is joined, not cut",
           "enables source-level coverage for tests.", field(o, "--coverage", 3));
        eq("options/picocli: a short and long pair still pairs", "-h", field(o, "--help", 0));
        eq("options/picocli: the Commands block is not swallowed",
           "prints this usage information.", field(o, "--help", 3));
        eq("options/picocli: every row is found, none invented", "4",
           String.valueOf(o.size()));
    }

    /**
     * A truth table over {@code appliesToVerb}, one row per rule sourced from flix/flix's
     * {@code Main.scala}/{@code Bootstrap.scala} (verified against 0.75.3) rather than
     * inferred from {@code --help} text. Each row is a fact about a specific verb's actual
     * reachable code, not a generalisation from the others -- {@code init} excludes both
     * tiers, {@code clean}/{@code build-pkg} keep bootstrap options but not compile ones,
     * {@code release} is the one verb that gains {@code --yes} rather than losing something,
     * and the {@code --Xbenchmark-*}/{@code --listen} flags never belong to any named verb
     * at all, regardless of which one is asked.
     */
    static void curationTruthTable() {
        Object[][] rows = {
            // flag, verb, expected
            {"--entrypoint", "run", true},
            {"--entrypoint", "init", false},
            {"--entrypoint", "clean", false},
            {"--entrypoint", "build-pkg", false},
            {"--threads", "check", true},
            {"--top", "test", true},
            {"--Xlib", "build", true},
            {"--Xlib", "clean", false},
            {"--Xno-deprecated", "init", false},
            {"--github-token", "run", true},
            {"--github-token", "clean", true},
            {"--github-token", "build-pkg", true},
            {"--github-token", "init", false},
            {"--no-install", "clean", true},
            {"--no-install", "init", false},
            {"--yes", "release", true},
            {"--yes", "check", false},
            {"--yes", "init", false},
            {"--yes", "clean", false},
            {"--listen", "run", false},
            {"--listen", "init", false},
            {"--listen", "release", false},
            {"--Xbenchmark-code-size", "run", false},
            {"--Xbenchmark-incremental", "init", false},
            {"--Xbenchmark-phases", "clean", false},
            {"--Xbenchmark-frontend", "release", false},
            {"--Xbenchmark-throughput", "check", false},
            {"--json", "init", true},
            {"--help", "clean", true},
            {"--version", "run", true},
        };
        int wrong = 0;
        for (Object[] row : rows) {
            String flag = (String) row[0], verb = (String) row[1];
            boolean want = (Boolean) row[2];
            boolean got = flixwhelp.appliesToVerb(flag, verb);
            if (got != want) {
                wrong++;
                bad("curation: " + flag + " x " + verb,
                    "wanted " + want + ", got " + got);
            }
        }
        if (wrong == 0) ok();
    }

    /**
     * {@code .flixw/local/editor-jar.toml} round-trips, and {@code ownsEditorJar} is the
     * one check standing between a future {@code ./flixw pin --editor-jar=copy} and
     * silently overwriting a file this project's flixw never created.
     */
    static void editorJarPrefs() throws IOException {
        Path root = Files.createTempDirectory("flixw-editorjar-uc-");
        flixw.writeEditorJarPref(root, "copy", "a".repeat(64));
        flixw.EditorJarPref pref = flixw.readEditorJarPref(root);
        if (pref != null && pref.mode().equals("copy") && pref.sha256().equals("a".repeat(64))) ok();
        else bad("editor-jar: pref round-trips", String.valueOf(pref));

        Path bare = Files.createTempDirectory("flixw-editorjar-uc-bare-");
        if (flixw.readEditorJarPref(bare) == null) ok();
        else bad("editor-jar: no file means no preference", "found one");

        Path link = root.resolve("flix.jar");
        Files.writeString(link, "stub jar bytes");
        flixw.writeEditorJarPref(root, "copy", flixw.sha256(link));
        if (flixw.ownsEditorJar(link, flixw.readEditorJarPref(root))) ok();
        else bad("editor-jar: a copy matching the recorded digest is owned", "not owned");

        flixw.writeEditorJarPref(root, "copy", "b".repeat(64));
        if (!flixw.ownsEditorJar(link, flixw.readEditorJarPref(root))) ok();
        else bad("editor-jar: a copy not matching the recorded digest is a stranger's", "owned");

        if (!flixw.ownsEditorJar(link, null)) ok();
        else bad("editor-jar: with no recorded preference, a regular file is a stranger's", "owned");
    }

    /**
     * {@code flixw-local.java}'s manifest reading and {@code packages.toml} round-trip --
     * the pure logic behind {@code local add/list/remove}, exercised with no compiler and
     * no overlay, the same way {@link #editorJarPrefs} exercises the editor-jar file
     * format without a real cache or JAR.
     */
    static void localOverrides() throws IOException {
        String pkg = "[package]\n"
                   + "name        = \"demo\"\n"
                   + "version     = \"1.2.3\"\n"
                   + "repository  = \"github:acme/demo\"\n"
                   + "flix        = \"0.75.3\"\n";
        eq("local: packageField reads repository", "github:acme/demo",
           flixwlocal.packageField(pkg, "repository"));
        eq("local: packageField reads version", "1.2.3", flixwlocal.packageField(pkg, "version"));
        eq("local: packageField is absent when the field is absent", null,
           flixwlocal.packageField(pkg, "modules"));

        String deps = "[dependencies]\n"
                    + "\"github:acme/demo\" = \"1.2.3\"\n"
                    + "\"github:acme/other\" = { version = \"4.5.6\", security = \"unrestricted\" }\n";
        eq("local: dependencyVersion reads a bare-string entry", "1.2.3",
           flixwlocal.dependencyVersion(deps, "github:acme/demo"));
        eq("local: dependencyVersion reads an inline-table entry", "4.5.6",
           flixwlocal.dependencyVersion(deps, "github:acme/other"));
        eq("local: dependencyVersion is absent for an undeclared coordinate", null,
           flixwlocal.dependencyVersion(deps, "github:acme/nope"));

        Path root = Files.createTempDirectory("flixw-local-uc-");
        if (flixwlocal.readOverrides(root).isEmpty()) ok();
        else bad("local: no packages.toml means no overrides", "found some");

        java.util.Map<String, String> overrides = new java.util.LinkedHashMap<>();
        overrides.put("github:acme/demo", "/checkouts/demo");
        flixwlocal.writeOverrides(root, overrides);
        java.util.Map<String, String> got = flixwlocal.readOverrides(root);
        eq("local: packages.toml round-trips one override", "{github:acme/demo=/checkouts/demo}",
           got.toString());

        overrides.put("github:acme/other", "/checkouts/other");
        flixwlocal.writeOverrides(root, overrides);
        eq("local: a second override is written alongside the first", "2",
           String.valueOf(flixwlocal.readOverrides(root).size()));

        overrides.remove("github:acme/demo");
        flixwlocal.writeOverrides(root, overrides);
        java.util.Map<String, String> afterRemove = flixwlocal.readOverrides(root);
        eq("local: removing one override leaves the other", "{github:acme/other=/checkouts/other}",
           afterRemove.toString());

        // A path containing a literal quote or backslash -- plausible on Windows, where
        // every path separator is one -- must round-trip rather than corrupting the file
        // or being read back truncated at the first embedded quote.
        Path oddRoot = Files.createTempDirectory("flixw-local-uc-odd-");
        java.util.Map<String, String> odd = new java.util.LinkedHashMap<>();
        odd.put("github:acme/demo", "C:\\Users\\a\"b\\checkouts\\demo");
        flixwlocal.writeOverrides(oddRoot, odd);
        eq("local: a path with a quote and a backslash round-trips", odd.toString(),
           flixwlocal.readOverrides(oddRoot).toString());

        // Table-scoping: a same-named field in the wrong table must not be read as
        // [package]'s or [dependencies]'s own -- the whole reason packageField/
        // dependencyVersion read tableBlock() rather than the raw manifest text.
        String scoped = "[dependencies]\n"
                       + "version = \"9.9.9\"\n"
                       + "[package]\n"
                       + "name        = \"demo\"\n"
                       + "version     = \"1.2.3\"\n"
                       + "repository  = \"github:acme/demo\"\n";
        eq("local: packageField ignores a same-named field in [dependencies]", "1.2.3",
           flixwlocal.packageField(scoped, "version"));
        String scoped2 = "[package]\n"
                        + "\"github:acme/demo\" = \"9.9.9\"\n"
                        + "[dependencies]\n"
                        + "\"github:acme/demo\" = \"1.2.3\"\n";
        eq("local: dependencyVersion ignores a same-named entry in [package]", "1.2.3",
           flixwlocal.dependencyVersion(scoped2, "github:acme/demo"));

        // Fail-closed reading: a file this writer would never produce must refuse rather
        // than read back as "no overrides", which a subsequent add/remove would then
        // silently overwrite, discarding whatever the corruption had not already lost.
        Path badRoot = Files.createTempDirectory("flixw-local-uc-bad-");
        Files.createDirectories(badRoot.resolve(".flixw").resolve("local"));
        Files.writeString(badRoot.resolve(".flixw").resolve("local").resolve("packages.toml"),
                "this is not a packages.toml at all\n");
        try {
            flixwlocal.readOverrides(badRoot);
            bad("local: a malformed packages.toml fails closed", "read without throwing");
        } catch (flixwlocal.Exit e) { ok(); }

        Path danglingRoot = Files.createTempDirectory("flixw-local-uc-dangling-");
        Files.createDirectories(danglingRoot.resolve(".flixw").resolve("local"));
        Files.writeString(danglingRoot.resolve(".flixw").resolve("local").resolve("packages.toml"),
                "[overrides.\"github:acme/demo\"]\n");
        try {
            flixwlocal.readOverrides(danglingRoot);
            bad("local: a header with no path fails closed", "read without throwing");
        } catch (flixwlocal.Exit e) { ok(); }

        // safeSegment: the last line of defence before a coordinate/version becomes a
        // filesystem path segment in seedOverride -- COORDINATE's character class alone
        // accepts ".." as a segment, so this is what actually stops the climb.
        if (flixwlocal.safeSegment("flix-cubesolve") && flixwlocal.safeSegment("0.4.5")) ok();
        else bad("local: an ordinary segment is safe", "rejected");
        if (!flixwlocal.safeSegment("..") && !flixwlocal.safeSegment(".")
                && !flixwlocal.safeSegment("a/b") && !flixwlocal.safeSegment("a\\b")
                && !flixwlocal.safeSegment("")) ok();
        else bad("local: a traversal or separator segment is unsafe", "accepted");

        // "github:../.." passes COORDINATE's own character class (it allows '.' and '-'
        // in a segment, and does not itself exclude ".."), so seedOverride is the actual
        // last line of defence against a coordinate that would otherwise climb out of
        // overlay/lib/github via Path.resolve("..") -- proven end to end, not just that
        // the character class matches or does not.
        if (flixwlocal.COORDINATE.matcher("github:../..").matches()) ok();
        else bad("local: COORDINATE alone does not exclude '..'", "unexpectedly rejected");
        Path traversalPkg = Files.createTempDirectory("flixw-local-uc-traversal-");
        Files.writeString(traversalPkg.resolve("flix.toml"), "[package]\nversion = \"..\"\n");
        try {
            flixwlocal.seedOverride(Files.createTempDirectory("flixw-local-uc-overlay-"),
                "github:../..", traversalPkg.resolve("nonexistent.fpkg"), traversalPkg);
            bad("local: seedOverride refuses a traversal-shaped coordinate", "did not throw");
        } catch (flixwlocal.Exit e) { ok(); }
    }

    /**
     * `wrapper --upgrade` warms every companion asset of the release it moves to, and
     * reads which ones those are out of that release's own SHA256SUMS rather than a list
     * compiled into this stage 0. An upgrade runs in the *old* wrapper, so a hard-coded
     * list would stop warming the day a new asset shipped, silently.
     */
    static void releaseAssets() {
        String sums = "aa".repeat(32) + "  flixw.java\n"
                    + "bb".repeat(32) + "  flixw-help.java\n"
                    + "cc".repeat(32) + "  flixw-jdk.java\n"
                    + "dd".repeat(32) + "  flixw-0.24.1.tar.gz\n"
                    + "ee".repeat(32) + "  flixw-0.24.1.zip\n"
                    + "ff".repeat(32) + "  flix.java\n";
        java.util.List<String> got = flixw.publishedAssets(sums);
        eq("assets: companions are found", "[flixw-help.java, flixw-jdk.java]", got.toString());
        // flixw.java is the wrapper, not a companion to it, and the upgrade installs it by
        // a different route entirely -- warming it would download it a second time.
        eq("assets: flixw.java is not a companion", "false", String.valueOf(got.contains("flixw.java")));
        // Only the flixw- prefix marks a companion asset. A release may publish other
        // .java files beside stage 0 without them being something to fetch and run.
        eq("assets: an unprefixed .java is not one", "false", String.valueOf(got.contains("flix.java")));
        eq("assets: archives are not assets", "false", String.valueOf(got.toString().contains(".zip")));
        // The renderer's picocli ships in the same manifest and must be warmed with the rest,
        // or the first `./flixw help` after an upgrade needs a network the upgrade was meant
        // to make unnecessary. A jar in a flixw release is by construction a dependency this
        // release owns; nothing else gets in there.
        eq("assets: the pinned picocli is a companion", "true", String.valueOf(
            flixw.publishedAssets("ff".repeat(32) + "  " + flixw.PICOCLI_ASSET + "\n")
                 .contains(flixw.PICOCLI_ASSET)));
        // Not "any jar". A release that later publishes an artifact for a reader rather than
        // a runtime must not be downloaded by every upgrade because it happened to be a jar.
        eq("assets: an unnamed jar is not a companion", "false", String.valueOf(
            flixw.publishedAssets("ff".repeat(32) + "  something-else-1.0.jar\n")
                 .contains("something-else-1.0.jar")));
        eq("assets: an empty manifest yields none", "0", String.valueOf(flixw.publishedAssets("").size()));
        // GNU coreutils marks binary mode with a * before the name, which is the default
        // on Windows. A mirror whose digests were generated there must still be readable.
        eq("assets: a binary-mode marker is not part of the name",
           "[flixw-jdk.java]",
           flixw.publishedAssets("77".repeat(32) + " *flixw-jdk.java\n").toString());
        eq("assets: and its digest is still found", "88".repeat(32),
           flixw.digestFor("88".repeat(32) + " *flixw-setup.java\n", "flixw-setup.java"));
        // A name repeated in the manifest is one asset, not two fetches of it.
        eq("assets: a repeated name appears once", "1",
           String.valueOf(flixw.publishedAssets("11".repeat(32) + "  flixw-jdk.java\n"
                                             + "22".repeat(32) + "  flixw-jdk.java\n").size()));
        // Warming is best-effort by contract: an unreachable source must return a count,
        // not throw, or an upgrade that already succeeded would report as a failure.
        eq("assets: an unreachable source warms none, and does not throw", "0",
           String.valueOf(flixw.warmAssets("99".repeat(32) + "  flixw-nosuch.java\n", "0.0.1")));
    }

    // ---- 7: pin targets ---------------------------------------------------

    /**
     * `pin` takes an owner/repository and a version in either order, told apart by the
     * slash a version can never contain. None of this needs the network.
     */
    static void pinTargets() {
        flixw.Lock forked = new flixw.Lock("0.75.2+f.1", "https://x/y.jar", "a".repeat(64),
                                         "wstein/flix-fork", null, null, java.util.Map.of());
        flixw.Pin t = flixw.parsePin(java.util.List.of("wstein/flix-fork", "0.75.2+f.1"), null);
        eq("pin: repository then version", "wstein/flix-fork", t.repo());
        eq("pin: version survives its build metadata", "0.75.2+f.1", t.version());

        t = flixw.parsePin(java.util.List.of("0.75.2+f.1", "wstein/flix-fork"), null);
        eq("pin: order does not matter", "wstein/flix-fork", t.repo());

        // The trap this exists to close: a bare re-pin used to rebuild the upstream URL
        // and move a fork-tracking project back to stock, silently, because both are
        // honestly the same version.
        t = flixw.parsePin(java.util.List.of("0.75.2+f.1"), forked);
        eq("pin: a bare re-pin stays on the fork", "wstein/flix-fork", t.repo());
        t = flixw.parsePin(java.util.List.of("0.75.2"), null);
        eq("pin: with no lock and no repository it is upstream", "flix/flix", t.repo());
        t = flixw.parsePin(java.util.List.of("flix/flix", "0.75.2"), forked);
        eq("pin: naming upstream leaves the fork", "flix/flix", t.repo());

        // The release tag is the spelling GitHub shows, and flixw builds it itself to reach
        // the asset, so `pin` takes either and records the version. Two spellings of one
        // release must not be able to produce two different locks.
        t = flixw.parsePin(java.util.List.of("v0.75.2"), null);
        eq("pin: the release tag spelling normalizes to the version", "0.75.2", t.version());
        t = flixw.parsePin(java.util.List.of("wstein/flix-fork", "v0.75.2+f.1"), null);
        eq("pin: a tag keeps its build metadata", "0.75.2+f.1", t.version());
        eq("pin: a tag does not disturb the repository", "wstein/flix-fork", t.repo());
        eq("pin: both spellings reach the same version",
           flixw.parsePin(java.util.List.of("0.75.2"), null).version(),
           flixw.parsePin(java.util.List.of("v0.75.2"), null).version());

        // owner/repo@version as one token: the shape npm and Go modules train people to
        // reach for by habit, accepted alongside the two-token form.
        t = flixw.parsePin(java.util.List.of("wstein/flix-fork@0.75.3+f.1"), null);
        eq("pin: owner/repo@version splits into repo", "wstein/flix-fork", t.repo());
        eq("pin: owner/repo@version splits into version", "0.75.3+f.1", t.version());
        t = flixw.parsePin(java.util.List.of("wstein/flix-fork@v0.75.3"), null);
        eq("pin: the tag half of owner/repo@tag still normalizes", "0.75.3", t.version());

        // Taken only ahead of a digit: `vNext` is a bad version, not the version `Next`.
        for (java.util.List<String> bad : java.util.List.of(
                java.util.List.<String>of(),
                java.util.List.of("a/b", "c/d", "1.0.0"),
                java.util.List.of("1.0.0", "2.0.0"),
                java.util.List.of("not/a/repo", "1.0.0"),
                java.util.List.of("v"),
                java.util.List.of("vNext"),
                java.util.List.of("vv0.75.2"),
                java.util.List.of("v0.75"),
                java.util.List.of("a/b", "not-a-version"),
                java.util.List.of("wstein/flix-fork@"),              // nothing after '@'
                java.util.List.of("wstein/flix-fork@0.75.3", "0.75.4"))) { // two versions
            try { flixw.parsePin(bad, null); bad("pin: " + bad, "accepted"); }
            catch (flixw.Fail e) { ok(); }
        }

        // --- the java pin ---------------------------------------------------
        t = flixw.parsePin(java.util.List.of("0.75.2", "--java", "21"), null);
        eq("pin: --java rides along with a compiler version", "21", t.java());
        t = flixw.parsePin(java.util.List.of("--java", "21.0.12"), forked);
        eq("pin: --java alone needs no compiler version", null, t.version());
        eq("pin: ...and keeps the repository", "wstein/flix-fork", t.repo());
        eq("pin: ...and takes an exact version", "21.0.12", t.java());
        t = flixw.parsePin(java.util.List.of("--java", "none"), forked);
        eq("pin: --java none clears it", null, t.java());
        if (t.clearJava()) ok(); else bad("pin: --java none", "did not ask to clear");

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

        // --- --refresh ------------------------------------------------------
        // It rewrites the lock from the lock, so it needs one and takes nothing else.
        // Accepting `pin 0.75.3 --refresh` would have to mean one of two different
        // requests, and picking either silently is how a repair loses a pin.
        t = flixw.parsePin(java.util.List.of("--refresh"), forked);
        if (t.refresh()) ok(); else bad("pin: --refresh", "did not ask for a refresh");
        eq("pin: --refresh moves no version", null, t.version());
        eq("pin: --refresh moves no repository", null, t.repo());
        eq("pin: --refresh moves no java pin", null, t.java());
        if (!t.clearJava()) ok(); else bad("pin: --refresh", "asked to clear the java pin");

        for (java.util.List<String> bad : java.util.List.of(
                java.util.List.of("--refresh", "0.75.2"),        // a version is a different request
                java.util.List.of("0.75.2", "--refresh"),
                java.util.List.of("--refresh", "flix/flix"),
                java.util.List.of("--refresh", "--java", "21"))) {
            try { flixw.parsePin(bad, forked); bad("pin: " + bad, "accepted"); }
            catch (flixw.Fail e) { ok(); }
        }
        // Nothing to rewrite, and nothing to guess at.
        try { flixw.parsePin(java.util.List.of("--refresh"), null); bad("pin: --refresh with no lock", "accepted"); }
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

        // The same two headers carry the version, and neither puts it where the other does:
        // scopt on the product line, picocli on the line after it, with build metadata.
        // Reading it is what lets flixw notice a lock that misnames the compiler it pins.
        eq("version: scopt reports on the product line", "0.75.2",
           flixw.parseReportedVersion(scopt));
        eq("version: picocli reports on the line below, metadata and all",
           "0.75.2+fork.wstein.260807.1.88.ge3027b3e2.dirty",
           flixw.parseReportedVersion(picocli));

        // Absent is not an error: it is the absence of a second opinion, and a fork that
        // prints no version must not become a project that cannot run.
        eq("version: a header without one reports nothing", null,
           flixw.parseReportedVersion(lines("Some Other Compiler", "Usage: x [a|b]")));
        // Only the header. An option default or an example further down is text about
        // something else, and reading one as the compiler's identity would report a
        // mismatch against nothing.
        eq("version: a version below the header is not the compiler's", null,
           flixw.parseReportedVersion(lines("The Flix Programming Language", "Usage: flix",
                                            "Commands:", "  init   scaffolds 1.2.3")));
        // A version-looking run of digits inside a longer token is not a version.
        eq("version: an embedded token is not a version", null,
           flixw.parseReportedVersion(lines("build x1.2.3-rc", "Usage: flix")));

        System.out.println("  ok   verb capture: scopt and picocli help renderers");
        System.out.println("  ok   version: what each renderer reports about itself");
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
    static void lockSchema(Path fixtures) throws IOException {
        lockFixtures(fixtures);
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

    /**
     * Every fixture in `tests/schema/`, through the validator that runs on each invocation.
     *
     * The directory a fixture is filed under is its expected verdict, so a new case is a
     * new file and nothing else -- see tests/schema/README.md for what each one means. CI
     * runs taplo over `valid/` and `invalid/` against the published schema, which is the
     * other half of this: these two rows must hold for both validators, and the remaining
     * two are where they are specified to differ.
     */
    static void lockFixtures(Path dir) throws IOException {
        int n = 0;
        for (String kind : new String[] {"valid", "invalid", "semantic", "advisory"}) {
            for (Path lock : locks(dir.resolve(kind))) {
                String label = "lock fixture " + kind + "/" + lock.getFileName();
                n++;
                boolean accepted;
                String detail = "";
                // The advisory group's whole point is what reaches stderr, so stderr is
                // the assertion rather than noise to scroll past. Restored in a finally,
                // because a swapped System.err outliving a failure would silence the rest
                // of the run.
                java.io.ByteArrayOutputStream noise = new java.io.ByteArrayOutputStream();
                java.io.PrintStream real = System.err;
                System.setErr(new java.io.PrintStream(noise, true, StandardCharsets.UTF_8));
                try { flixw.readLock(lock); accepted = true; }
                catch (flixw.Fail e) { accepted = false; detail = e.getMessage().split("\n")[0]; }
                finally { System.setErr(real); }

                boolean want = kind.equals("valid") || kind.equals("advisory");
                if (accepted == want) ok();
                else if (want) bad(label, "rejected: " + detail);
                else bad(label, "accepted, but this group must be rejected");

                boolean noted = noise.toString(StandardCharsets.UTF_8).contains("FLIXW011");
                if (noted == kind.equals("advisory")) ok();
                else bad(label, noted ? "an unexpected FLIXW011" : "no FLIXW011 for an unknown key");
            }
        }
        // A fixture directory that quietly emptied would turn this whole group into a
        // no-op that still reports ok.
        if (n >= 20) ok();
        else bad("lock fixtures", "only " + n + " fixtures found under " + dir);
        System.out.println("  ok   lock fixtures: " + n + " locks through readLock");
    }

    static List<Path> locks(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().forEach(out::add);
        }
        return out;
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

    // ---- 11: the FLIX_JAR override's containment test ----------------------

    /**
     * `FLIX_JAR` inside flixw's own compiler cache is always a mistake -- those names carry
     * the digest, so a re-pin changes them and the override goes on naming the compiler the
     * project used to pin. Telling the two apart is a prefix test, the shape of test a `..`
     * walks straight through, which is why the shims' JDK marker has a rule of its own.
     */
    static void overrideContainment() {
        Path compilers = flixw.cacheHome().resolve("compilers");
        if (flixw.insideCompilerCache(compilers.resolve("flix-0.75.2-abc.jar"))) ok();
        else bad("override: a cache entry is inside the cache", "reported outside");
        // The whole reason for normalizing first: the right prefix, and not inside.
        if (!flixw.insideCompilerCache(compilers.resolve("..").resolve("..").resolve("evil.jar")))
            ok();
        else bad("override: `..` does not escape the containment test", "reported inside");
        // A sibling sharing the prefix as a *string* is not inside it.
        if (!flixw.insideCompilerCache(Paths.get(compilers + "-elsewhere").resolve("x.jar"))) ok();
        else bad("override: a name-prefix sibling is not inside", "reported inside");
        // The supported use: a jar you built yourself, anywhere else.
        if (!flixw.insideCompilerCache(Paths.get("/tmp/flix-build/flix.jar"))) ok();
        else bad("override: a locally built jar is not a cache entry", "reported inside");
        System.out.println("  ok   override: what counts as flixw's own compiler cache");
    }

    // ---- 10b: examples/ discovery and containment ---------------------------

    /**
     * Offline coverage for flixwexamples's discovery and symlink-containment logic --
     * a shell test would otherwise need a real pinned compiler and a real subprocess
     * launch just to reach it. Uses a plain temp directory tree; needs no project.
     */
    static void examplesDiscovery() throws IOException {
        Path root = Files.createTempDirectory("flixw-examples-uc-");
        try {
            Path ex = root.resolve("examples");
            Files.createDirectories(ex.resolve("cli-tool"));
            Files.createFile(ex.resolve("cli-tool").resolve("flix.toml"));
            Files.createDirectories(ex.resolve("no-manifest"));      // no flix.toml: not listed
            Files.createDirectories(ex.resolve("Bad_Name"));         // fails NAME: not listed
            Files.createFile(ex.resolve("Bad_Name").resolve("flix.toml"));

            List<String> found = flixwexamples.discover(root);
            if (found.equals(List.of("cli-tool"))) ok();
            else bad("examples: discover lists only NAME-conforming dirs with a manifest",
                     found.toString());

            if (flixwexamples.NAME.matcher("cli-tool").matches()
                && !flixwexamples.NAME.matcher("Bad_Name").matches()
                && !flixwexamples.NAME.matcher("-leading-dash").matches()) ok();
            else bad("examples: NAME pattern", "accepted something it should reject, or the reverse");

            Path noEx = Files.createTempDirectory("flixw-examples-uc-none-");
            try {
                if (flixwexamples.discover(noEx).isEmpty()) ok();
                else bad("examples: no examples/ directory discovers nothing", "found something");
            } finally {
                Files.deleteIfExists(noEx);
            }

            // The bug this guards against: canonicalizing examples/ and only ever comparing
            // a *child* against it passes trivially once both have resolved through the same
            // escaping symlink. discover() must refuse before it ever lists anything.
            Path outside = Files.createTempDirectory("flixw-examples-uc-outside-");
            try {
                Files.createFile(outside.resolve("flix.toml"));
                Files.delete(ex.resolve("cli-tool").resolve("flix.toml"));
                Files.delete(ex.resolve("cli-tool"));
                Files.delete(ex.resolve("no-manifest"));
                Files.delete(ex.resolve("Bad_Name").resolve("flix.toml"));
                Files.delete(ex.resolve("Bad_Name"));
                Files.delete(ex);
                try {
                    Files.createSymbolicLink(ex, outside);
                    try {
                        flixwexamples.discover(root);
                        bad("examples: a symlinked examples/ is refused, not enumerated",
                            "discover() returned instead of refusing");
                    } catch (flixwexamples.Exit e) {
                        ok();      // refused, whatever the code -- that is the property under test
                    }
                } catch (java.nio.file.FileSystemException e) {
                    // No symlink privilege on this machine (notably some Windows CI
                    // accounts); the shell suite probes and skips the same case rather
                    // than asserting for the wrong reason, and so does this one.
                }
            } finally {
                try (var walk = Files.walk(outside)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
                }
            }
            System.out.println("  ok   examples: discovery and symlink containment");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    // ---- 10b: the verb-flags-before-<name> grammar in examples ---------------

    /**
     * Offline coverage for the parsing behind {@code examples run [flags] <name>}: which
     * compiler flags take a value, and where the leading run of flags ends. A shell test
     * proves the whole path reaches a real compiler; this proves the split itself is right
     * on inputs a shell test would need a contrived {@code --help} fixture to reach at all.
     */
    static void examplesGrammar() {
        String help = "Usage: flix [run] [options]\n"
                     + "Command: run\n"
                     + "  runs main for the current project.\n"
                     + "\n"
                     + "  --entrypoint <value>     specifies the main entry point.\n"
                     + "  --yes                    automatically answer yes to all prompts.\n"
                     + "  -o, --output <value>     where to write it.\n";
        var valueTaking = flixwexamples.valueTakingOptions(help);
        if (valueTaking.contains("--entrypoint") && valueTaking.contains("--output")
            && valueTaking.contains("-o") && !valueTaking.contains("--yes")) ok();
        else bad("examples: valueTakingOptions arity", valueTaking.toString());

        if (flixwexamples.valueTakingOptions("").isEmpty()
            && flixwexamples.valueTakingOptions(null).isEmpty()) ok();
        else bad("examples: an empty or missing --help yields no known flags", "found some");

        // A value-taking flag consumes the next token; a boolean one does not; an
        // unrecognised flag degrades to zero-arity rather than being refused.
        var split = flixwexamples.splitVerbFlags(
            List.of("--entrypoint", "Foo.main", "cli-tool", "--", "x"), valueTaking);
        if (split.get(0).equals(List.of("--entrypoint", "Foo.main"))
            && split.get(1).equals(List.of("cli-tool", "--", "x"))) ok();
        else bad("examples: a value-taking flag consumes its value", split.toString());

        split = flixwexamples.splitVerbFlags(List.of("--yes", "cli-tool"), valueTaking);
        if (split.get(0).equals(List.of("--yes")) && split.get(1).equals(List.of("cli-tool")))
            ok();
        else bad("examples: a boolean flag does not consume the next token", split.toString());

        split = flixwexamples.splitVerbFlags(List.of("--unknown-flag", "cli-tool"), valueTaking);
        if (split.get(0).equals(List.of("--unknown-flag"))
            && split.get(1).equals(List.of("cli-tool"))) ok();
        else bad("examples: an unrecognised flag degrades to zero-arity", split.toString());

        // A bare "--" can never be a flag, even though it starts with "-": it is always the
        // forwarding boundary, so the scan must stop there rather than consuming it.
        split = flixwexamples.splitVerbFlags(List.of("--", "cli-tool"), valueTaking);
        if (split.get(0).isEmpty() && split.get(1).equals(List.of("--", "cli-tool"))) ok();
        else bad("examples: a bare -- never counts as a flag", split.toString());

        split = flixwexamples.splitVerbFlags(List.of("cli-tool"), valueTaking);
        if (split.get(0).isEmpty() && split.get(1).equals(List.of("cli-tool"))) ok();
        else bad("examples: no leading flags at all leaves <name> untouched", split.toString());

        // Regression: a Windows-captured --help arrives \r\n-terminated, and the trailing \r
        // left after splitting on "\n" fails OPTION_ENTRY's $ anchor on every line -- so raw,
        // unnormalized text silently loses every flag's arity. That is the exact windows-latest
        // CI failure that shipped in 0.26.2 ("no example 'Bogus.main'" instead of reaching the
        // compiler): pin it here so a future change cannot reintroduce it unnoticed.
        String rawCrlfHelp = help.replace("\n", "\r\n");
        if (flixwexamples.valueTakingOptions(rawCrlfHelp).isEmpty()) ok();
        else bad("examples: unnormalized \\r\\n text was expected to lose every flag",
                 flixwexamples.valueTakingOptions(rawCrlfHelp).toString());

        // captureHelp normalizes exactly this way before anything downstream ever parses it;
        // simulate that here rather than spawning a subprocess just to prove the composition.
        String normalizedHelp = rawCrlfHelp.replace("\r\n", "\n").replace('\r', '\n');
        var normalizedValueTaking = flixwexamples.valueTakingOptions(normalizedHelp);
        if (normalizedValueTaking.equals(valueTaking)) ok();
        else bad("examples: valueTakingOptions survives a normalized \\r\\n-terminated --help",
                 normalizedValueTaking.toString());

        System.out.println("  ok   examples: verb-flags-before-<name> grammar");

        // A bare trailing word after "run" can only ever mean a forgotten forwarding
        // boundary -- Flix rejects the shape outright rather than reading it as a file, the
        // one case examples' own dispatch() now shares with the root's autoRunBoundary.
        if (flixw.autoRunBoundary(List.of("run", "foo"))
                 .equals(List.of("run", "--", "foo"))) ok();
        else bad("run: a bare trailing word gets -- inserted",
                 flixw.autoRunBoundary(List.of("run", "foo")).toString());

        // A leading flag is left alone: telling --entrypoint's value from the boundary
        // needs the same value-taking-option knowledge examples' own splitVerbFlags has,
        // which the root does not carry for every compiler verb.
        if (flixw.autoRunBoundary(List.of("run", "--entrypoint", "Foo.main"))
                 .equals(List.of("run", "--entrypoint", "Foo.main"))) ok();
        else bad("run: a leading flag is not touched",
                 flixw.autoRunBoundary(List.of("run", "--entrypoint", "Foo.main")).toString());

        // Already has the boundary: inserting a second one would deliver an empty string as
        // the example's own first argument.
        if (flixw.autoRunBoundary(List.of("run", "--", "foo"))
                 .equals(List.of("run", "--", "foo"))) ok();
        else bad("run: an existing -- is not doubled",
                 flixw.autoRunBoundary(List.of("run", "--", "foo")).toString());

        // No trailing word at all -- nothing to insert a boundary in front of.
        if (flixw.autoRunBoundary(List.of("run")).equals(List.of("run"))) ok();
        else bad("run: no trailing word leaves the list untouched",
                 flixw.autoRunBoundary(List.of("run")).toString());
    }

    // ---- 10: the command tree the completers are generated from -------------

    /**
     * The completion scripts are no longer templates with a verb list substituted in; they
     * are emitted from one picocli {@code CommandSpec}, and picocli's own {@code AutoComplete}
     * produces the bash and zsh ones. So there is nothing here that a unit test can assert
     * about their text without asserting picocli's output, which is picocli's business.
     *
     * <p>What is still flixw's, and is still worth pinning down here, is the *model*: the
     * union that a project with no resolved compiler falls back to. Get that wrong and every
     * shell completes the wrong words, and the end-to-end checks in tests/run.sh -- which do
     * load the generated scripts into real bash and real fish -- would all be verifying a
     * consistent mistake.
     */
    static void completion() throws IOException {
        List<String> want = new ArrayList<>(flixw.WRAPPER_VERBS);
        for (String v : flixw.BUILTIN_VERBS) if (!want.contains(v)) want.add(v);
        want.sort(null);

        // `completion` is deliberately absent from WRAPPER_VERBS: it is answered before the
        // project is resolved, not from the wrapper-verb table, so listing it there would
        // advertise a route that never runs.
        if (!flixw.WRAPPER_VERBS.contains("completion")) ok();
        else bad("completion: the verb is not answered from WRAPPER_VERBS",
                 "it is listed there");

        // The trust-gate verbs stay in the fallback, because a project that cannot reach a
        // compiler is exactly the project whose user needs to type `pin` or `doctor`.
        for (String v : List.of("pin", "info", "doctor", "validate", "help")) {
            if (want.contains(v)) ok();
            else bad("completion: the fallback offers " + v, "missing from the union");
        }
        if (want.contains("check") && want.contains("build")) ok();
        else bad("completion: the fallback offers the compiler's common verbs",
                 "BUILTIN_VERBS did not reach the union");
    }

    /**
     * Every {@code WRAPPER_VERBS} entry must render a real description in {@code
     * flixwhelp.wrapperDesc}, or it shows up blank in {@code ./flixw help} and {@code
     * ./flixw help wrapper} -- exactly what happened to {@code local}: added to
     * WRAPPER_VERBS in one commit, and only given a description here, by hand, days
     * later. This closes the gap so the next new verb fails a test instead of shipping
     * with a blank line.
     */
    static void wrapperVerbDescriptions() {
        for (String v : flixw.WRAPPER_VERBS) {
            String d = flixwhelp.wrapperDesc(v);
            if (d != null && !d.isEmpty()) ok();
            else bad("wrapperDesc: " + q(v) + " has no description",
                     "wrapperDesc returned " + (d == null ? "null" : "an empty string"));
        }
    }


    static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/corpus");
        Path fixtures = Paths.get(args.length > 1 ? args[1] : "tests/schema");
        List<Row> rows = rows(dir);
        corpus(dir, rows);
        adversarial();
        chooser();
        provisioning();
        pinTargets();
        verbs();
        lockSchema(fixtures);
        bounded();
        completion();
        wrapperVerbDescriptions();
        overrideContainment();
        examplesDiscovery();
        examplesGrammar();
        releaseChannel();
        releaseAssets();
        optionRows();
        curationTruthTable();
        editorJarPrefs();
        pluginDescription();
        declaredVerbs();
        upgradeUrls();
        upgradeTarget();
        prereleaseChannel();
        localOverrides();
        System.out.println("  unit checks: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
