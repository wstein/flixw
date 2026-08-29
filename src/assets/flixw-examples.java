import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders {@code ./flixw examples ...} -- a wrapper-owned companion asset, not a plugin.
 *
 * <p>{@code java flixw-examples.java <root> <javaExe> <compilerJar> <verb> [args...]}
 *
 * <p>Runs one of a project's {@code examples/<name>/} directories as its own consumer
 * package, against the root project's already-selected, already-verified Java and
 * compiler. Stage 0 hands over exactly those three resolved paths; this asset re-derives
 * nothing about Java selection, compiler acquisition or digest verification -- that
 * judgment stays in stage 0, the same condition under which any of these assets are
 * allowed to exist at all.
 *
 * <p>Shipped and verified with flixw itself -- fetched, digest-checked and cached the
 * exact way {@code flixw-help.java} is, warmed by {@code wrapper --upgrade} -- so there is
 * no separate install step and no "unaudited third-party code" warning the way a plugin
 * invocation would carry. An {@code examples/<name>/} directory is a real, separate Flix
 * package with its own manifest and dependencies (typically on a *released* build of the
 * root project, not its local source); this asset only ever changes the compiler's working
 * directory into it; it never touches the root project's own lock or lockstep compiler.
 */
final class flixwexamples {
    private flixwexamples() {}

    /** A single path segment, lowercase-hyphen -- the same shape flixw itself requires of
     *  a plugin name, applied here so a directory listing is safe to print verbatim. A
     *  name failing this just does not appear in `examples list`; degrade, don't brick. */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /** What {@code System.exit} used to do, scoped to this asset -- including forwarding
     *  the launched example's own exit code, which is not a FLIXWnnn diagnostic at all. */
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
        if (args.length < 4) {
            System.err.println("usage: java flixw-examples.java <root> <javaExe>"
                             + " <compilerJar> <verb> [args...]");
            throw new Exit(87);
        }
        Path root = Paths.get(args[0]);
        Path javaExe = Paths.get(args[1]);
        Path compilerJar = Paths.get(args[2]);
        String verb = args[3];
        List<String> rest = List.of(args).subList(4, args.length);

        switch (verb) {
            case "list" -> list(root);
            // Verb-agnostic on purpose: dispatch only ever changes the compiler's working
            // directory and forwards what follows <name>, so "what does build's artifact
            // location mean here" has the same answer it does for the root project --
            // Flix's own convention, examples/<name>/build/, needs nothing from this asset.
            // "test" means what it says for a package with its own @Test defs, not Cargo's
            // run-the-example-as-a-test-of-the-root-package sense -- there is no such sense
            // here, since examples is its own namespace rather than a flag on `run`.
            case "run", "check", "build", "test" -> dispatch(root, javaExe, compilerJar, verb, rest);
            default -> {
                System.err.println("flixw examples: unknown command " + q(verb));
                System.err.println(usageText());
                throw new Exit(89);
            }
        }
    }

    /**
     * The real, canonical {@code examples/} path -- and where a symlinked {@code examples/}
     * itself, not only a symlinked child of it, is caught. Canonicalizing the directory and
     * then only ever comparing a child against it passes trivially when both have already
     * resolved through the same escaping symlink; the directory itself has to be checked
     * against the real project root separately. {@link #discover} routes through this too:
     * a first draft called it only from {@link #dispatch}, so {@code list} enumerated
     * straight through an escaping symlink and printed the target directory's contents --
     * a real information leak, caught by actually pointing {@code examples/} at {@code /tmp}
     * and watching {@code list} print its contents, not by inspection.
     */
    static Path realExamplesDir(Path root) throws IOException {
        Path exDir = root.resolve("examples");
        if (!Files.isDirectory(exDir)) return exDir;
        Path real = exDir.toRealPath();
        Path realRoot = root.toRealPath();
        if (!real.startsWith(realRoot)) {
            System.err.println("flixw examples: 'examples' escapes the project root (symlink?)");
            throw new Exit(89);
        }
        return real;
    }

    static List<String> discover(Path root) throws IOException {
        Path exDir = realExamplesDir(root);
        if (!Files.isDirectory(exDir)) return List.of();
        try (Stream<Path> s = Files.list(exDir)) {
            return s.filter(p -> Files.isRegularFile(p.resolve("flix.toml")))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> NAME.matcher(n).matches())
                    .sorted()
                    .toList();
        }
    }

    static void list(Path root) throws IOException {
        List<String> names = discover(root);
        if (names.isEmpty()) System.out.println("(no examples under examples/)");
        else names.forEach(System.out::println);
    }

    static void dispatch(Path root, Path javaExe, Path compilerJar, String verb, List<String> rest)
            throws IOException, InterruptedException {
        if (rest.isEmpty()) {
            System.err.println("flixw examples: " + verb + " needs a name -- known: "
                             + String.join(" ", discover(root)));
            throw new Exit(87);
        }
        String name = rest.get(0);
        // Everything after <name> is forwarded verbatim, INCLUDING a leading "--". Flix's
        // own `run` rejects trailing words as unsupported "file arguments" unless "--"
        // introduces them (verified against a real compiler: `run foo` refuses to run at
        // all, `run -- foo` delivers foo to Sys.Env.Env.getArgs()) -- so stripping it here
        // would silently break the one thing this command exists for.
        List<String> forward = rest.subList(1, rest.size());

        List<String> known = discover(root);
        if (!known.contains(name)) {
            System.err.println("flixw examples: no example " + q(name) + " -- known: "
                             + String.join(" ", known));
            throw new Exit(89);
        }

        // `name` is only ever accepted once it has been proven equal to something discover()
        // already listed, never taken as a raw path -- but re-derive and re-check containment
        // anyway, the same defence flixw's own plugin name handling uses, in case a symlink
        // under examples/ points outside it.
        Path exDir = realExamplesDir(root);
        Path dir = exDir.resolve(name).toRealPath();
        if (!dir.startsWith(exDir)) {
            System.err.println("flixw examples: " + q(name) + " escapes examples/ (symlink?)");
            throw new Exit(89);
        }

        List<String> cmd = new ArrayList<>(List.of(javaExe.toString(), "-jar",
                                                    compilerJar.toString(), verb));
        cmd.addAll(forward);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        throw new Exit(p.waitFor());
    }

    static String q(String s) { return "'" + s + "'"; }

    static String usageText() {
        return "usage: ./flixw examples list"
             + "\n       or: ./flixw examples run|check|build|test <name> [-- args]";
    }
}
