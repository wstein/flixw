import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        if (args.length != 1) {
            System.err.println("usage: java flixw-inspect.java <context-file>");
            System.exit(87);
        }
        Ctx c = read(Paths.get(args[0]));
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
