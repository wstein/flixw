import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * flixw's cache inventory: what {@code ./flixw info --verbose} prints below the summary.
 *
 * <p>{@code java flixw-inspect.java <context-file>}, fetched and digest-verified like
 * every other companion asset. Concise {@code info} stays in stage 0 and is unaffected by
 * whether this file can be reached — a wrapper that could not describe its own state
 * without a network would be a worse wrapper than one that describes less of it.
 *
 * <p><b>Policy stays in stage 0; I/O moved here.</b> That line is the whole design, and it
 * is not arbitrary. The JDK tables are handed over already resolved, because deriving them
 * means running {@code java -version} over a search path and deciding what counts — that
 * is selection policy, and a second copy of it would be free to disagree with the one that
 * actually picks the JVM. The table people read would then be the one that never runs.
 *
 * <p>Walking the cache is the opposite: documented paths, no decisions, nothing to
 * disagree about. Handing over pre-walked summaries would have kept that code resident in
 * stage 0 and moved only the formatting, which is not worth an asset.
 */
final class flixwinspect {
    private flixwinspect() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 && args.length != 4) {
            System.err.println("usage: java flixw-inspect.java <context-file> [--purge <days> <--ask|--yes>]");
            System.exit(87);
        }
        Ctx c = read(Paths.get(args[0]));
        if (args.length == 4) {
            if (!args[1].equals("--purge")) {
                System.err.println("usage: java flixw-inspect.java <context-file> [--purge <days> <--ask|--yes>]");
                System.exit(87);
            }
            int days;
            try { days = Integer.parseInt(args[2]); }
            catch (NumberFormatException e) { throw new IOException("purge days must be a whole number"); }
            if (days < 0) throw new IOException("purge days must not be negative");
            if (!args[3].equals("--ask") && !args[3].equals("--yes"))
                throw new IOException("purge mode must be --ask or --yes");
            purge(c, days, args[3].equals("--yes"));
            return;
        }
        compilers(c);
        jdks(c);
        plugins(c);
        assets(c);
    }

    // ---- the context stage 0 hands over -----------------------------------

    /**
     * Everything this program is told, as opposed to everything it looks up.
     *
     * <p>Line-based rather than JSON because both ends ship in one release and neither has
     * a parser: a format that needs 60 lines of reader to carry six fields and two tables
     * would cost more than it documents. Keys are {@code key=value}; tables are
     * {@code name:} followed by tab-separated rows, ended by a blank line.
     */
    static final class Ctx {
        String cacheRoot = "", wrapperVersion = "", upstreamRepo = "", lockSha256 = "";
        /** plugin name to {@code version-sha256}, as the lock declares it. */
        Map<String, String> lockPlugins = new LinkedHashMap<>();
        /** version, directory name, "default" — JDKs flixw itself installed. */
        List<String[]> cachedJdks = new ArrayList<>();
        /** version, path, feature, "selected"/"below" — what selection actually found. */
        List<String[]> systemJdks = new ArrayList<>();
    }

    static Ctx read(Path file) throws IOException {
        Ctx c = new Ctx();
        String table = null;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) { table = null; continue; }
            if (line.endsWith(":") && !line.contains("=")) { table = line.substring(0, line.length() - 1); continue; }
            if (table != null) {
                String[] row = line.split("\t", -1);
                switch (table) {
                    case "cachedJdks" -> c.cachedJdks.add(row);
                    case "systemJdks" -> c.systemJdks.add(row);
                    case "lockPlugins" -> c.lockPlugins.put(row[0], row.length > 1 ? row[1] : "");
                    default -> { }
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            switch (k) {
                case "cacheRoot" -> c.cacheRoot = v;
                case "wrapperVersion" -> c.wrapperVersion = v;
                case "upstreamRepo" -> c.upstreamRepo = v;
                case "lockSha256" -> c.lockSha256 = v;
                default -> { }
            }
        }
        return c;
    }

    static Path cache(Ctx c, String... parts) {
        Path p = Paths.get(c.cacheRoot);
        for (String s : parts) p = p.resolve(s);
        return p;
    }

    static List<Path> dirsIn(Path dir) {
        try (var s = Files.isDirectory(dir) ? Files.list(dir) : null) {
            return s == null ? List.of() : s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) { return List.of(); }
    }

    // ---- the four tables ---------------------------------------------------

    static final Pattern JAR = Pattern.compile("^flix-(.+)-([0-9a-f]{64})\\.jar$");

    static void compilers(Ctx c) {
        Path dir = cache(c, "compilers");
        List<Path> jars = List.of();
        try (var s = Files.isDirectory(dir) ? Files.list(dir) : null) {
            if (s != null) jars = s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException ignored) { }
        List<String[]> rows = new ArrayList<>();
        for (Path jar : jars) {
            Matcher m = JAR.matcher(jar.getFileName().toString());
            String canonical = m.matches() ? m.group(1) : jar.getFileName().toString();
            String sha = m.matches() ? m.group(2) : null;
            long size;
            try { size = Files.size(jar); } catch (IOException e) { size = -1; }
            boolean pinned = sha != null && sha.equals(c.lockSha256);
            // The directory names only the canonical x.x.x. Build metadata -- what actually
            // tells two builds of one release apart -- comes from the pin record `pin` and
            // `acquire` write: what a lock pinned this exact digest as, repo included, and
            // it outlives the project that wrote it. Failing that, the directory's name.
            String[] pin = sha == null ? null : pinRecord(c, sha);
            String version = pin != null ? pin[1] : canonical;
            String repo = pin != null ? pin[0] : null;
            // A version disambiguates only when it says more than the directory already
            // does; when it does not, the digest is all that separates two like-named
            // entries.
            boolean disambiguated = !version.equals(canonical);
            rows.add(new String[] { version, humanSize(size),
                     (repo != null && !repo.equals(c.upstreamRepo) ? "  (" + repo + ")" : "")
                   + (!disambiguated && sha != null ? "  (sha " + sha.substring(0, 12) + "...)" : "")
                   + (pinned ? "  <= pinned" : "") });
        }
        System.out.println("cached compilers");
        printAligned(rows);
    }

    /**
     * {@code <cache>/verbs/<digest>.pin}: the repo on the first line, the exact tag on the
     * second. Two lines rather than one field, because a tag may contain anything a
     * separator could be.
     */
    static String[] pinRecord(Ctx c, String sha) {
        try {
            List<String> lines = Files.readAllLines(cache(c, "verbs", sha + ".pin"),
                                                    StandardCharsets.UTF_8);
            return lines.size() < 2 ? null : new String[] { lines.get(0), lines.get(1) };
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * Both JDK tables, rendered from what stage 0 resolved rather than from a fresh search.
     *
     * <p>The split matters to a reader deciding what to delete: the first are flixw's to
     * manage, the second are the machine's and are only found.
     */
    static void jdks(Ctx c) {
        List<String[]> cached = new ArrayList<>();
        for (String[] r : c.cachedJdks)
            cached.add(new String[] { r[0], r[1], r.length > 2 && r[2].equals("default") ? "  <= default" : "" });
        System.out.println("cached JDKs");
        printAligned(cached);

        List<String[]> system = new ArrayList<>();
        for (String[] r : c.systemJdks)
            system.add(new String[] { r[0], r[1], r.length > 2 ? r[2] : "" });
        System.out.println("system JDKs");
        printAligned(system);
    }

    /**
     * Every plugin on this machine, not only what this project declares -- the same
     * machine-wide listing the sections above give compilers and JDKs. A directory read;
     * nothing here reaches a plugin's own bytes.
     */
    static void plugins(Ctx c) {
        List<String[]> rows = new ArrayList<>();
        for (Path nameDir : dirsIn(cache(c, "plugins"))) {
            String name = nameDir.getFileName().toString();
            String want = c.lockPlugins.get(name);
            for (Path v : dirsIn(nameDir)) {
                String have = v.getFileName().toString();
                rows.add(new String[] { name, have,
                         have.equals(want) ? "  <= expected by lock.toml" : "" });
            }
        }
        System.out.println("installed plugins");
        printAligned(rows);
    }

    /**
     * Listed by walking the directory rather than from a list of known names, so an asset
     * left behind by a release this one has replaced is still visible to whoever is
     * deciding what to delete.
     */
    static void assets(Ctx c) {
        List<String[]> rows = new ArrayList<>();
        for (Path v : dirsIn(cache(c, "wrapper", "assets"))) {
            String ver = v.getFileName().toString();
            String mark = ver.equals(c.wrapperVersion) ? "  <= this release" : "";
            try (var s = Files.list(v)) {
                for (Path f : s.filter(x -> x.getFileName().toString().endsWith(".java")).sorted().toList())
                    rows.add(new String[] { ver, f.getFileName().toString(), mark });
            } catch (IOException ignored) { }
        }
        System.out.println("cached companion assets");
        printAligned(rows);
    }

    // ---- cache lifecycle -------------------------------------------------

    /**
     * Deletes flixw-owned entries that the filesystem says no process has read recently.
     *
     * <p>Stage 0 writes its own one-date usage records when it actually launches an
     * artifact. Filesystem atime is not evidence: mounts may disable or coarsen it, and a
     * directory listing may update it without executing anything. A missing or malformed
     * record is retained, never guessed from modification time. The current wrapper's
     * companion assets and stage-0 classes stay even when old.
     */
    static void purge(Ctx c, int days, boolean yes) {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        Purge p = new Purge(cutoff, yes);
        System.out.println("purging flixw cache entries not used since " + cutoff
                         + " (" + days + " day" + (days == 1 ? "" : "s") + ")");
        purgeCompilers(c, p);
        purgeJdks(c, p);
        purgePlugins(c, p);
        purgeAssets(c, p);
        System.out.println("freed " + humanSize(p.bytes) + " from " + p.count + " entr"
                         + (p.count == 1 ? "y" : "ies"));
        if (p.unmarked > 0)
            System.out.println("kept " + p.unmarked + " entr" + (p.unmarked == 1 ? "y" : "ies")
                             + " (" + humanSize(p.unmarkedBytes) + ") flixw has never recorded"
                             + " a use of; they become purgeable once used");
        System.out.println("stage0 is retained; it is only a few megabytes and speeds every run.");
    }

    static final class Purge {
        final LocalDate cutoff;
        final boolean yes;
        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        long bytes;
        int count;
        /** Entries flixw has never seen used, which are retained and are not a failure. */
        int unmarked;
        long unmarkedBytes;
        Purge(LocalDate cutoff, boolean yes) { this.cutoff = cutoff; this.yes = yes; }
        void remove(Ctx c, Path p, String kind, String key) {
            try {
                if (Files.isSymbolicLink(p)) return;
                LocalDate used = used(c, key);
                // No marker is "never seen used", not "unused". Markers accrue from the
                // release that introduced them, so a cache filled before it -- or by a
                // project not run since -- is full of entries purge must leave alone. That
                // is counted rather than passed over silently: a first purge on an old
                // cache otherwise reports freeing nothing against gigabytes, and reads as
                // a broken feature rather than an honest one.
                if (used == null) {
                    unmarked++;
                    try { unmarkedBytes += treeSize(p); } catch (IOException ignored) { }
                    return;
                }
                if (used.isAfter(cutoff)) return;
                long size = treeSize(p);
                if (!confirm(kind, p, size)) return;
                deleteTree(p);
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
                try { Files.deleteIfExists(cache(c, "usage", key + ".used")); } catch (IOException ignored) { }
                bytes += size; count++;
                System.out.println("  removed " + kind + "  " + p.getFileName() + "  (" + humanSize(size) + ")");
            } catch (IOException ignored) { }
        }
        boolean confirm(String kind, Path p, long size) throws IOException {
            if (yes) return true;
            System.err.print("delete " + kind + " " + p.getFileName() + " (" + humanSize(size)
                             + ")? [y/N] ");
            String answer = in.readLine();
            return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
        }
    }

    static void purgeCompilers(Ctx c, Purge p) {
        for (Path jar : filesIn(cache(c, "compilers"))) {
            Matcher m = JAR.matcher(jar.getFileName().toString());
            // No exemption for the digest a lock names. Purge is handed no project on
            // purpose -- sparing the one you happen to stand in, while deleting another
            // project's equally old entry, is a distinction the cache cannot justify. The
            // usage marker is the protection, and it applies to every project alike.
            if (!m.matches()) continue;
            String sha = m.group(2);
            long before = p.bytes;
            p.remove(c, jar, "compiler", "compiler/" + sha);
            if (p.bytes != before)
                for (String suffix : List.of(".verbs", ".version", ".pin", ".compl", ".help", ".helpmeta"))
                    try { Files.deleteIfExists(cache(c, "verbs", sha + suffix)); } catch (IOException ignored) { }
        }
    }

    static void purgeJdks(Ctx c, Purge p) {
        Path defaultJava = cache(c, "jdks", "default");
        String keep = "";
        try { keep = Files.readString(defaultJava, StandardCharsets.UTF_8).trim(); } catch (IOException ignored) { }
        for (Path dir : dirsIn(cache(c, "jdks")))
            if (!keep.startsWith(dir.toString() + java.io.File.separator))
                p.remove(c, dir, "JDK", "jdk/" + dir.getFileName());
    }

    static void purgePlugins(Ctx c, Purge p) {
        for (Path name : dirsIn(cache(c, "plugins")))
            for (Path version : dirsIn(name))
                p.remove(c, version, "plugin", "plugin/" + name.getFileName() + "/" + version.getFileName());
    }

    static void purgeAssets(Ctx c, Purge p) {
        for (Path version : dirsIn(cache(c, "wrapper", "assets")))
            if (!version.getFileName().toString().equals(c.wrapperVersion))
                p.remove(c, version, "asset set", "asset/" + version.getFileName());
    }

    static List<Path> filesIn(Path dir) {
        try (var s = Files.isDirectory(dir) ? Files.list(dir) : null) {
            return s == null ? List.of() : s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) { return List.of(); }
    }

    static LocalDate used(Ctx c, String key) {
        try {
            return LocalDate.parse(Files.readString(cache(c, "usage", key + ".used"), StandardCharsets.UTF_8).trim());
        } catch (IOException | RuntimeException e) { return null; }
    }

    static long treeSize(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    // ---- shared rendering --------------------------------------------------

    /**
     * Two aligned columns, so a listing whose entries vary wildly in length reads as a
     * table rather than a ragged column of annotations nobody can scan.
     */
    static void printAligned(List<String[]> rows) {
        int a = 0, b = 0;
        for (String[] r : rows) { a = Math.max(a, r[0].length()); b = Math.max(b, r[1].length()); }
        if (rows.isEmpty()) System.out.println("  (none)");
        for (String[] r : rows)
            System.out.println("  " + pad(r[0], a) + "  " + pad(r[1], b) + r[2]);
    }

    static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "K";
        return String.format("%.1fM", bytes / (1024.0 * 1024.0));
    }
}
