import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders {@code ./flixw local ...} -- a wrapper-owned companion asset, not a plugin.
 *
 * <p>
 * {@code java flixw-local.java <root> <javaExe> <compilerJar> <jvmOptCount>
 * [jvmOpt...] <mode> <verb> [args...]} -- the option count precedes the options
 * themselves, the same convention {@code flixw-examples.java} uses, so an
 * arbitrary-length, already-tokenized list can sit between fixed positions with
 * no delimiter to collide with a real option string. {@code <mode>} is always
 * {@code standalone} for now: overrides come from {@code .flixw/local/packages.toml}.
 *
 * <p>
 * The mechanism this asset automates is proven, not invented: Flix's own
 * package
 * resolver reads a dependency from {@code lib/github/<owner>/<repo>/<version>/}
 * beside a
 * consumer's declared {@code flix.toml} dependency, before ever reaching the
 * network --
 * pre-seed that exact path with a locally built {@code .fpkg} and its source
 * manifest, and
 * the resolver uses it with zero {@code flix.toml} edits, real or temporary.
 * Version
 * pinning is checked strictly (a mismatch is not silently accepted), a bare
 * {@code .fpkg}
 * outside that exact hierarchy is ignored, and the dependency must already be
 * declared --
 * none of that is worked around here; a real, local package is not a manifest
 * edit and
 * does not get to bypass what a real, remote one could not.
 *
 * <p>
 * Every overlay is a disposable temporary directory, deleted when the launched
 * command
 * exits; nothing here is a build system, and nothing about the real project
 * directory --
 * its own {@code lib/}, {@code artifact/}, or committed manifest -- is ever
 * written to.
 * v1 is cacheless on purpose: a repeated run rebuilds every overridden package
 * fresh, the
 * conservative default until the mechanism has seen real use.
 */
final class flixwlocal {
    private flixwlocal() {
    }

    static final Pattern COORDINATE = Pattern.compile("github:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    /**
     * Every verb that changes nothing but a disposable overlay's working directory
     * --
     * the same "verb-agnostic dispatch" {@code flixw-examples.java} already relies
     * on.
     * {@code init} is excluded (it creates a new project; every path here already
     * requires one to exist), {@code release} is excluded (it pushes to GitHub
     * using the
     * overridden package's own manifest, an external side effect nothing else here
     * has),
     * and the interactive/server verbs (repl, lsp, lsp-vscode) have no
     * exit-code-and-done
     * shape to relay through a disposable overlay that is deleted the moment it
     * returns.
     */
    static final List<String> OVERLAY_VERBS = List.of(
            "run", "check", "build", "build-jar", "build-fatjar", "build-pkg", "test", "doc");

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /**
     * What {@code System.exit} used to do, scoped to this asset -- including
     * forwarding
     * the launched verb's own exit code, which is not a FLIXWnnn diagnostic at all.
     */
    static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    public static int run(String[] args) throws Exception {
        try {
            body(args);
            return 0;
        } catch (Exit e) {
            return e.code;
        }
    }

    static void body(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println(protocolUsage());
            throw new Exit(87);
        }
        Path root = Paths.get(args[0]);
        Path javaExe = Paths.get(args[1]);
        Path compilerJar = Paths.get(args[2]);
        int optCount = Integer.parseInt(args[3]);
        int modeAt = 4 + optCount;
        if (modeAt + 1 >= args.length) {
            System.err.println(protocolUsage());
            throw new Exit(87);
        }
        List<String> jvmOpts = List.of(args).subList(4, modeAt);
        String mode = args[modeAt];
        int verbAt = modeAt + 1;
        String verb = args[verbAt];
        List<String> rest = List.of(args).subList(verbAt + 1, args.length);

        if (!mode.equals("standalone")) {
            System.err.println(protocolUsage());
            throw new Exit(87);
        }
        switch (verb) {
            case "add" -> add(root, rest);
            case "list" -> list(root, rest);
            case "remove" -> remove(root, rest);
            case "status" -> status(root, rest);
            default -> {
                requireOverlayVerb(verb);
                requireCompiler(javaExe, compilerJar);
                Map<String, String> overrides = readOverrides(root);
                if (overrides.isEmpty()) {
                    System.err.println("flixw local: no overrides -- run: ./flixw local add <path>");
                    throw new Exit(89);
                }
                runOverlay(root, root, javaExe, compilerJar, jvmOpts, overrides, verb, rest);
            }
        }
    }

    static void requireCompiler(Path javaExe, Path compilerJar) {
        if (javaExe.toString().isEmpty() || compilerJar.toString().isEmpty()) {
            System.err.println("flixw local: needs a pinned, reachable compiler"
                             + "\n       run: ./flixw pin <version>");
            throw new Exit(89);
        }
    }

    static void requireOverlayVerb(String verb) {
        if (!OVERLAY_VERBS.contains(verb)) {
            System.err.println("flixw local: unknown command " + q(verb));
            System.err.println(usageText());
            throw new Exit(89);
        }
    }

    // ---- the standalone override list: .flixw/local/packages.toml -------------

    static Path packagesFile(Path root) {
        return root.resolve(".flixw").resolve("local").resolve("packages.toml");
    }

    /**
     * Line-based, not a TOML parser -- the same reason {@code lock.toml} used to be
     * read
     * by hand before {@code tomlScan} existed, except this format is one table
     * repeated,
     * never nested, so a full scanner would cost more than it would ever be asked
     * to read.
     */
    /**
     * Fails closed, not empty, on anything this writer would not have produced itself:
     * a corrupted or hand-edited file that silently read as "no overrides" would then
     * be overwritten by the next {@code add}/{@code remove}, discarding whatever the
     * corruption had not already destroyed. A missing file is the one case that legitimately
     * means no overrides -- everything else this can see fails loudly instead, naming the
     * repair.
     */
    static Map<String, String> readOverrides(Path root) {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = packagesFile(root);
        if (!Files.isRegularFile(f))
            return out;
        Pattern header = Pattern.compile("^\\[overrides\\.\"([^\"]+)\"\\]$");
        // Escaped-quote aware: a path containing a literal `"` or `\` round-trips through
        // writeOverrides' own escaping, so the reader has to undo the same encoding rather
        // than stopping at the first quote character, escaped or not.
        Pattern path = Pattern.compile("^path\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"$");
        String coordinate = null;
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("flixw local: cannot read " + f + ": " + e.getMessage());
            throw new Exit(89);
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            Matcher h = header.matcher(line);
            if (h.matches()) {
                if (!COORDINATE.matcher(h.group(1)).matches()) {
                    System.err.println("flixw local: " + f + " names " + q(h.group(1))
                            + ", not a github:<owner>/<repo> coordinate");
                    System.err.println("       repair or remove the file by hand, or run: "
                            + "./flixw local remove " + q(h.group(1)));
                    throw new Exit(89);
                }
                coordinate = h.group(1);
                continue;
            }
            Matcher p = path.matcher(line);
            if (p.matches() && coordinate != null) {
                out.put(coordinate, tomlUnescape(p.group(1)));
                coordinate = null;
                continue;
            }
            System.err.println("flixw local: " + f + " does not parse at " + q(raw));
            System.err.println("       repair or remove the file by hand");
            throw new Exit(89);
        }
        if (coordinate != null) {
            System.err.println("flixw local: " + f + " ends with " + q(coordinate)
                    + " and no path -- repair or remove the file by hand");
            throw new Exit(89);
        }
        return out;
    }

    static void writeOverrides(Path root, Map<String, String> overrides) throws IOException {
        StringBuilder b = new StringBuilder(
                "# Written by ./flixw local. Machine-specific; never committed (.flixw/.gitignore).\n");
        for (var e : overrides.entrySet()) {
            b.append("\n[overrides.\"").append(e.getKey()).append("\"]\n");
            b.append("path = \"").append(tomlEscape(e.getValue())).append("\"\n");
        }
        Path f = packagesFile(root);
        Files.createDirectories(f.getParent());
        Path tmp = Files.createTempFile(f.getParent(), ".packages-", ".part");
        Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A basic TOML string escapes exactly two characters; multi-line and unicode escapes
     *  are never produced by {@link #writeOverrides} and are not accepted back either. */
    static String tomlEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String tomlUnescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
            else b.append(c);
        }
        return b.toString();
    }

    static void add(Path root, List<String> rest) throws IOException {
        if (rest.size() != 1) {
            System.err.println("flixw local: add takes exactly one path");
            System.err.println(usageText());
            throw new Exit(87);
        }
        Path pkgPath = Paths.get(rest.get(0)).toAbsolutePath().normalize();
        if (pkgPath.equals(root.toAbsolutePath().normalize())) {
            System.err.println("flixw local: cannot override this project with itself");
            throw new Exit(89);
        }
        Path pkgToml = pkgPath.resolve("flix.toml");
        if (!Files.isRegularFile(pkgToml)) {
            System.err.println("flixw local: " + q(rest.get(0)) + " has no flix.toml");
            throw new Exit(89);
        }
        String pkgText = Files.readString(pkgToml, StandardCharsets.UTF_8);
        String coordinate = packageField(pkgText, "repository");
        String pkgVersion = packageField(pkgText, "version");
        if (coordinate == null) {
            System.err.println("flixw local: " + pkgToml + " declares no [package] repository");
            throw new Exit(89);
        }
        if (!COORDINATE.matcher(coordinate).matches()) {
            System.err.println("flixw local: " + q(coordinate)
                    + " is not a github:<owner>/<repo> coordinate");
            throw new Exit(89);
        }
        String ownerRepo = coordinate.substring("github:".length());
        String owner = ownerRepo.substring(0, ownerRepo.indexOf('/'));
        String repoName = ownerRepo.substring(ownerRepo.indexOf('/') + 1);
        if (!safeSegment(owner) || !safeSegment(repoName) || pkgVersion == null || !safeSegment(pkgVersion)) {
            System.err.println("flixw local: " + q(coordinate) + " or its version "
                    + q(pkgVersion) + " is not safe to use as a cache path");
            throw new Exit(89);
        }

        Path rootToml = root.resolve("flix.toml");
        String rootText = Files.isRegularFile(rootToml)
                ? Files.readString(rootToml, StandardCharsets.UTF_8)
                : "";
        String wantVersion = dependencyVersion(rootText, coordinate);
        if (wantVersion == null) {
            System.err.println("flixw local: this project does not declare a dependency on "
                    + q(coordinate));
            System.err.println("       add it under flix.toml's [dependencies] first");
            throw new Exit(89);
        }
        if (!wantVersion.equals(pkgVersion)) {
            System.err.println("flixw local: version mismatch -- this project depends on "
                    + coordinate + " " + wantVersion + ", but " + rest.get(0)
                    + " is at " + pkgVersion);
            System.err.println("       Flix's own resolver checks the version strictly;"
                    + " align the two before overriding");
            throw new Exit(89);
        }

        Map<String, String> overrides = readOverrides(root);
        if (overrides.containsKey(coordinate)) {
            System.err.println("flixw local: " + q(coordinate) + " is already overridden --"
                    + " ./flixw local remove " + q(coordinate) + " first");
            throw new Exit(89);
        }
        // Override-to-override edges are refused in v1 rather than silently built wrong:
        // buildPackage() builds each overridden package standalone, in its own checkout's
        // dependency cache, so an override that itself depends on another override would
        // resolve that dependency's *remote*, committed version -- the opposite of what
        // adding it was for, and indistinguishable from working until inspected closely.
        for (var e : overrides.entrySet()) {
            if (dependencyVersion(pkgText, e.getKey()) != null) {
                System.err.println("flixw local: " + q(rest.get(0)) + " depends on "
                        + e.getKey() + ", which is already a local override");
                System.err.println("       building it standalone would resolve that dependency's"
                        + " remote version, not the override -- not supported in v1");
                throw new Exit(89);
            }
            String existingPkgText = Files.readString(
                    Paths.get(e.getValue()).resolve("flix.toml"), StandardCharsets.UTF_8);
            if (dependencyVersion(existingPkgText, coordinate) != null) {
                System.err.println("flixw local: " + e.getKey() + ", already a local override, depends on "
                        + coordinate);
                System.err.println("       building " + e.getKey() + " standalone would resolve "
                        + coordinate + "'s remote version, not this override -- not supported in v1");
                throw new Exit(89);
            }
        }
        overrides.put(coordinate, pkgPath.toString());
        writeOverrides(root, overrides);
        System.err.println("flixw local: added " + coordinate + " -> " + pkgPath);
    }

    static void list(Path root, List<String> rest) {
        if (!rest.isEmpty()) {
            System.err.println("flixw local: list takes no arguments");
            throw new Exit(87);
        }
        Map<String, String> overrides = readOverrides(root);
        if (overrides.isEmpty()) {
            System.out.println("(no local overrides)");
            return;
        }
        for (var e : overrides.entrySet())
            System.out.println(e.getKey() + "  " + e.getValue() + "  " + gitState(Paths.get(e.getValue())));
    }

    static void remove(Path root, List<String> rest) throws IOException {
        if (rest.size() != 1) {
            System.err.println("flixw local: remove takes exactly one coordinate");
            throw new Exit(87);
        }
        Map<String, String> overrides = readOverrides(root);
        if (overrides.remove(rest.get(0)) == null) {
            System.err.println("flixw local: no override for " + q(rest.get(0)));
            System.err.println("       known: " + String.join(" ", overrides.keySet()));
            throw new Exit(89);
        }
        writeOverrides(root, overrides);
        System.err.println("flixw local: removed " + rest.get(0));
    }

    /**
     * Beyond {@code list}: whether each override still points at something real,
     * still
     * declares the coordinate it was added under, and still sits at the version
     * this
     * project actually depends on -- three ways an override can go stale with
     * nothing
     * else here noticing, since none of it is re-checked between one
     * {@code local run}
     * and the next.
     */
    static void status(Path root, List<String> rest) throws IOException {
        if (!rest.isEmpty()) {
            System.err.println("flixw local: status takes no arguments");
            throw new Exit(87);
        }
        Map<String, String> overrides = readOverrides(root);
        if (overrides.isEmpty()) {
            System.out.println("(no local overrides)");
            return;
        }
        Path rootToml = root.resolve("flix.toml");
        String rootText = Files.isRegularFile(rootToml)
                ? Files.readString(rootToml, StandardCharsets.UTF_8)
                : "";
        for (var e : overrides.entrySet()) {
            String coordinate = e.getKey();
            Path p = Paths.get(e.getValue());
            System.out.println(coordinate + " -> " + p);
            System.out.println("  commit   " + gitState(p));
            Path pkgToml = p.resolve("flix.toml");
            if (!Files.isRegularFile(pkgToml)) {
                System.out.println("  warn     path no longer has a flix.toml");
                continue;
            }
            String pkgText = Files.readString(pkgToml, StandardCharsets.UTF_8);
            String pkgVersion = packageField(pkgText, "version");
            String wantVersion = dependencyVersion(rootText, coordinate);
            System.out.println("  version  " + pkgVersion);
            if (wantVersion == null)
                System.out.println("  warn     this project no longer declares this dependency");
            else if (!wantVersion.equals(pkgVersion))
                System.out.println("  warn     this project wants " + wantVersion
                        + ", the local package is at " + pkgVersion);
            else
                System.out.println("  ok       matches what this project declares");
        }
    }

    static String gitState(Path p) {
        try {
            String head = runQuiet(p, "git", "-C", p.toString(), "rev-parse", "--short", "HEAD");
            if (head.isEmpty())
                return "(not a git repository)";
            String dirty = runQuiet(p, "git", "-C", p.toString(), "status", "--porcelain");
            return head + (dirty.isBlank() ? "" : "-dirty");
        } catch (IOException | InterruptedException e) {
            return "(unknown)";
        }
    }

    static String runQuiet(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).directory(dir.toFile()).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        p.waitFor();
        return p.exitValue() == 0 ? out : "";
    }

    // ---- flix.toml reading: two fields, one table, nothing else ---------------

    /**
     * The text of one top-level table, from its {@code [name]} header to the next
     * top-level header or end of file -- not a general TOML parser (this asset cannot
     * call stage 0's own {@code tomlScan}; each companion asset is compiled and loaded
     * in isolation, so there is nothing to share it with), but enough to stop a
     * same-named field in the wrong table from being read as this one's. A dotted or
     * quoted table header (`[a.b]`, `["a b"]`) is not attempted -- flix.toml uses neither
     * for `[package]` or `[dependencies]`.
     */
    static String tableBlock(String text, String tableName) {
        Matcher header = Pattern.compile("(?m)^\\[" + Pattern.quote(tableName) + "\\]\\s*$").matcher(text);
        if (!header.find()) return "";
        Matcher next = Pattern.compile("(?m)^\\[").matcher(text);
        next.region(header.end(), text.length());
        return text.substring(header.end(), next.find() ? next.start() : text.length());
    }

    /**
     * {@code [package]}'s {@code name}/{@code repository}/{@code version} -- a bare,
     * unindented {@code key = "value"} line, which is the only shape this needs to read:
     * none of the three is ever legally written as an inline-table value.
     */
    static String packageField(String text, String key) {
        Matcher m = Pattern.compile("(?m)^" + key + "\\s*=\\s*\"([^\"]*)\"$").matcher(tableBlock(text, "package"));
        return m.find() ? m.group(1) : null;
    }

    /**
     * A {@code [dependencies]} entry's version, in either shape Flix accepts: a bare
     * string, or an inline table naming {@code version} among other fields (a security
     * context, most often). Both are one physical line in every manifest this project has
     * ever seen; wrapped inline tables are not attempted.
     */
    static String dependencyVersion(String text, String coordinate) {
        Matcher m = Pattern.compile("(?m)^\"" + Pattern.quote(coordinate) + "\"\\s*=\\s*(?:\"([^\"]+)\""
                + "|\\{[^}]*\\bversion\\s*=\\s*\"([^\"]+)\"[^}]*\\})")
                .matcher(tableBlock(text, "dependencies"));
        if (!m.find())
            return null;
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    // ---- the overlay: a disposable directory, deleted when the verb returns ---

    static void runOverlay(Path root, Path consumerDir, Path javaExe, Path compilerJar,
            List<String> jvmOpts, Map<String, String> overrides,
            String verb, List<String> rest) throws IOException, InterruptedException {
        Path overlay = Files.createTempDirectory("flixw-local-");
        try {
            stageConsumer(consumerDir, overlay);
            // The consumer's own cache covers its direct dependencies; an overridden local
            // package's transitive dependencies are resolved in *its* checkout, not the
            // consumer's, so its cache is seeded too -- otherwise a package with its own
            // dependencies would need the network merely because it is being overridden.
            seedExistingCache(consumerDir, overlay, overrides.keySet());
            for (String pkgPath : overrides.values())
                seedExistingCache(Paths.get(pkgPath), overlay, overrides.keySet());
            for (var e : overrides.entrySet()) {
                Path pkgPath = Paths.get(e.getValue());
                Path fpkg = buildPackage(pkgPath, javaExe, compilerJar, jvmOpts);
                try {
                    seedOverride(overlay, e.getKey(), fpkg, pkgPath);
                } finally {
                    // fpkg lives in its own private temp file, not pkgPath's own artifact/ --
                    // see buildPackage. Nothing else references it once seeded.
                    Files.deleteIfExists(fpkg);
                }
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toString());
            cmd.addAll(jvmOpts);
            cmd.add("-jar");
            cmd.add(compilerJar.toString());
            cmd.add(verb);
            cmd.addAll(rest);
            Process p = new ProcessBuilder(cmd).directory(overlay.toFile()).inheritIO().start();
            throw new Exit(p.waitFor());
        } finally {
            deleteRecursive(overlay);
        }
    }

    /**
     * Only what a package needs to compile: sources, tests if any, and its own
     * manifest.
     * Nothing about the wrapper is copied -- the overlay is launched by handing the
     * already-verified compiler jar straight to {@code java}, never through another
     * {@code ./flixw} the way this asset's shell-script prototype needed to.
     */
    static void stageConsumer(Path consumerDir, Path overlay) throws IOException {
        copyTree(consumerDir.resolve("src"), overlay.resolve("src"));
        if (Files.isDirectory(consumerDir.resolve("test")))
            copyTree(consumerDir.resolve("test"), overlay.resolve("test"));
        Files.copy(consumerDir.resolve("flix.toml"), overlay.resolve("flix.toml"));
    }

    /**
     * Whatever the consumer already resolved for a dependency that is not itself
     * being
     * overridden -- so a project with several dependencies does not re-download
     * every one
     * of them just because one is local for this run. Covers both Flix's own
     * dependency cache ({@code lib/github/}) and Maven's ({@code lib/cache/}), the two
     * directories a real project's own {@code lib/} was seen holding.
     */
    static void seedExistingCache(Path consumerDir, Path overlay, java.util.Set<String> overridden)
            throws IOException {
        Path srcMaven = consumerDir.resolve("lib").resolve("cache");
        if (Files.isDirectory(srcMaven))
            copyTree(srcMaven, overlay.resolve("lib").resolve("cache"));
        Path srcGithub = consumerDir.resolve("lib").resolve("github");
        if (!Files.isDirectory(srcGithub))
            return;
        Path dstGithub = overlay.resolve("lib").resolve("github");
        try (Stream<Path> owners = Files.list(srcGithub)) {
            for (Path ownerDir : owners.toList()) {
                try (Stream<Path> repos = Files.list(ownerDir)) {
                    for (Path repoDir : repos.toList()) {
                        String coordinate = "github:" + ownerDir.getFileName() + "/" + repoDir.getFileName();
                        if (overridden.contains(coordinate))
                            continue;
                        copyTree(repoDir, dstGithub.resolve(ownerDir.getFileName().toString())
                                .resolve(repoDir.getFileName().toString()));
                    }
                }
            }
        }
    }

    /**
     * {@code build-pkg}, in a private, disposable copy of the package -- never in
     * {@code pkgPath} itself. Building in place would mean deleting the package owner's
     * own {@code artifact/*.fpkg} first (needed so "exactly one" below is meaningful),
     * racing a concurrent build the owner might have running, and writing into a tree
     * this feature's whole point is to leave untouched -- doubly so once an override
     * target can itself be the invoking project root. The workspace gets its own copy of
     * {@code pkgPath}'s already-resolved dependency cache (its transitive Flix and Maven
     * dependencies), the same way {@link #stageConsumer}/{@link #seedExistingCache} seed
     * the outer overlay, so building it does not need the network merely because it is
     * being copied rather than built in place.
     *
     * <p>Returns a private temporary file, not a path under {@code pkgPath}'s own
     * {@code artifact/} -- the caller deletes it once {@link #seedOverride} has copied
     * it into the outer overlay.
     */
    static Path buildPackage(Path pkgPath, Path javaExe, Path compilerJar, List<String> jvmOpts)
            throws IOException, InterruptedException {
        Path workspace = Files.createTempDirectory("flixw-local-pkg-");
        try {
            stageConsumer(pkgPath, workspace);
            seedExistingCache(pkgPath, workspace, java.util.Set.of());
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toString());
            cmd.addAll(jvmOpts);
            cmd.add("-jar");
            cmd.add(compilerJar.toString());
            cmd.add("build-pkg");
            Process p = new ProcessBuilder(cmd).directory(workspace.toFile()).inheritIO().start();
            int rc = p.waitFor();
            if (rc != 0) {
                System.err.println("flixw local: building " + pkgPath + " failed (exit " + rc + ")");
                throw new Exit(89);
            }
            Path artifact = workspace.resolve("artifact");
            List<Path> fpkgs;
            try (Stream<Path> s = Files.isDirectory(artifact) ? Files.list(artifact) : Stream.empty()) {
                fpkgs = s.filter(f -> f.getFileName().toString().endsWith(".fpkg")).toList();
            }
            if (fpkgs.size() != 1) {
                System.err.println("flixw local: expected exactly one .fpkg building " + pkgPath
                        + ", found " + fpkgs.size());
                throw new Exit(89);
            }
            Path out = Files.createTempFile("flixw-local-fpkg-", ".fpkg");
            Files.copy(fpkgs.get(0), out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } finally {
            deleteRecursive(workspace);
        }
    }

    /**
     * {@code lib/github/<owner>/<repo>/<version>/<repo>-<version>.fpkg} beside its
     * own
     * {@code .toml} -- the exact hierarchy Flix's resolver reads a cached
     * dependency from,
     * proven against a real compiler before this asset existed at all (see
     * {@code docs/CONTRACT.md}'s "Local overrides" section for what that proved and
     * did
     * not). Never a bare {@code lib/*.fpkg}: that shape is confirmed ignored.
     */
    static void seedOverride(Path overlay, String coordinate, Path fpkg, Path pkgPath) throws IOException {
        // Re-validated here, not trusted from the caller: this is the one place a
        // coordinate and a version become filesystem path segments, so it is the one
        // place that has to refuse a `..` regardless of how far upstream it was already
        // checked -- the same reasoning validPluginName in stage 0 is checked at every
        // entry point rather than once at write time.
        if (!COORDINATE.matcher(coordinate).matches())
            throw new Exit(89);
        String rest = coordinate.substring("github:".length());
        String owner = rest.substring(0, rest.indexOf('/'));
        String repoName = rest.substring(rest.indexOf('/') + 1);
        String pkgText = Files.readString(pkgPath.resolve("flix.toml"), StandardCharsets.UTF_8);
        String version = packageField(pkgText, "version");
        if (!safeSegment(owner) || !safeSegment(repoName) || version == null || !safeSegment(version)) {
            System.err.println("flixw local: refusing to seed " + q(coordinate)
                             + " -- not a safe path segment");
            throw new Exit(89);
        }
        Path dir = overlay.resolve("lib").resolve("github").resolve(owner).resolve(repoName).resolve(version);
        Files.createDirectories(dir);
        Files.copy(fpkg, dir.resolve(repoName + "-" + version + ".fpkg"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(pkgPath.resolve("flix.toml"), dir.resolve(repoName + "-" + version + ".toml"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** A single path segment, not a path: no separator of either flavor, and not a
     *  traversal token -- {@code Path.resolve(".."}} climbs a directory even though
     *  {@code ".."} alone matches {@code COORDINATE}'s character class. */
    static boolean safeSegment(String s) {
        return !s.isEmpty() && !s.contains("/") && !s.contains("\\") && !s.equals(".") && !s.equals("..");
    }

    static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src))
            return;
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p))
                    Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static void deleteRecursive(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    static String q(String s) {
        return "'" + s + "'";
    }

    static String protocolUsage() {
        return "usage: java flixw-local.java <root> <javaExe> <compilerJar>"
                + " <jvmOptCount> [jvmOpt...] <mode> <verb> [args...]";
    }

    static String usageText() {
        return "usage: ./flixw local add <path> | list | remove <coordinate> | status"
             + "\n       or: ./flixw local <verb> [-- args]"
             + "\n       verbs: " + String.join(" ", OVERLAY_VERBS);
    }
}
