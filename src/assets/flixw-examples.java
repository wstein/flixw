import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders {@code ./flixw examples ...} -- a wrapper-owned companion asset, not a plugin.
 *
 * <p>{@code java flixw-examples.java <root> <javaExe> <compilerJar> <jvmOptCount>
 * [jvmOpt...] <helpText> <verb> [args...]} -- the option count precedes the options
 * themselves so an arbitrary-length, already-tokenized list can sit between fixed
 * positions with no delimiter to collide with a real option string. {@code <helpText>} is
 * the compiler's own captured {@code --help} (or an empty string if none was captured),
 * used only to tell a value-taking verb flag from the example name that follows it.
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
        if (args.length < 5) {
            System.err.println(protocolUsage());
            throw new Exit(87);
        }
        Path root = Paths.get(args[0]);
        Path javaExe = Paths.get(args[1]);
        Path compilerJar = Paths.get(args[2]);
        int optCount = Integer.parseInt(args[3]);
        int helpAt = 4 + optCount;
        if (helpAt + 1 >= args.length) {
            System.err.println(protocolUsage());
            throw new Exit(87);
        }
        List<String> jvmOpts = List.of(args).subList(4, helpAt);
        Set<String> valueTaking = valueTakingOptions(args[helpAt]);
        int verbAt = helpAt + 1;
        String verb = args[verbAt];
        List<String> rest = List.of(args).subList(verbAt + 1, args.length);

        switch (verb) {
            case "list" -> list(root);
            // Verb-agnostic on purpose: dispatch only ever changes the compiler's working
            // directory and forwards what follows <name>, so "what does build's artifact
            // location mean here" has the same answer it does for the root project --
            // Flix's own convention, examples/<name>/build/, needs nothing from this asset.
            // "test" means what it says for a package with its own @Test defs, not Cargo's
            // run-the-example-as-a-test-of-the-root-package sense -- there is no such sense
            // here, since examples is its own namespace rather than a flag on `run`.
            case "run", "check", "build", "test" ->
                dispatch(root, javaExe, compilerJar, jvmOpts, valueTaking, verb, rest);
            default -> {
                System.err.println("flixw examples: unknown command " + q(verb));
                System.err.println(usageText());
                throw new Exit(89);
            }
        }
    }

    /**
     * An option row in either compiler help layout: a short form, a long form, or both,
     * optionally followed by a {@code <value>} placeholder. The same shape
     * {@code flixw-help.java}'s {@code OPTION_ENTRY} matches, trimmed to what this asset
     * needs -- whether a spelling takes a value -- since it has no reason to also collect
     * descriptions or handle wrapped continuation lines the way the help renderer does.
     */
    static final Pattern OPTION_ENTRY = Pattern.compile(
        "^(?:-([A-Za-z0-9])(?:[, ]\\s*)?)?(--[A-Za-z][A-Za-z0-9-]*)?"
      + "(?:[\\s=]+<([^>]*)>)?(?:\\s\\s+(\\S.*))?$");

    /**
     * Every spelling of a compiler flag that takes a value, both short and long, from its
     * captured {@code --help}. Best-effort: an empty or unparseable capture yields an empty
     * set, which just means every leading {@code -}-token in {@code examples run [flags]
     * <name>} is treated as zero-arity -- the same degrade-not-brick answer verb capture
     * itself gives when a help screen cannot be parsed at all.
     */
    static Set<String> valueTakingOptions(String help) {
        Set<String> out = new HashSet<>();
        if (help == null || help.isEmpty()) return out;
        for (String raw : help.split("\n", -1)) {
            String line = raw.replace('\t', ' ').replaceFirst("^\\s+", "");
            Matcher m = OPTION_ENTRY.matcher(line);
            if (!m.matches()) continue;
            String shortOpt = m.group(1), longOpt = m.group(2), value = m.group(3);
            if ((shortOpt == null && longOpt == null) || value == null) continue;
            if (shortOpt != null) out.add("-" + shortOpt);
            if (longOpt != null) out.add(longOpt);
        }
        return out;
    }

    /**
     * Peels leading verb flags off {@code rest}, stopping at the first token that is not
     * one -- which is {@code <name>} -- or at a bare {@code --}, which can never be a flag
     * and always starts the forwarded, untouched half of the command line.
     *
     * <p>A flag not found in {@code valueTaking} is treated as zero-arity rather than
     * refused: guessing wrong on an unrecognised flag is no worse than today's behaviour,
     * where any leading {@code -}-token is mistaken for {@code <name>} outright, and stopping
     * to ask would fail a command line that stock Flix will explain perfectly well itself
     * the moment it actually runs.
     */
    static List<List<String>> splitVerbFlags(List<String> rest, Set<String> valueTaking) {
        List<String> flags = new ArrayList<>();
        int i = 0;
        while (i < rest.size() && rest.get(i).startsWith("-") && !rest.get(i).equals("--")) {
            String tok = rest.get(i++);
            flags.add(tok);
            if (valueTaking.contains(tok) && i < rest.size()) flags.add(rest.get(i++));
        }
        return List.of(flags, rest.subList(i, rest.size()));
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

    static void dispatch(Path root, Path javaExe, Path compilerJar, List<String> jvmOpts,
                         Set<String> valueTaking, String verb, List<String> rest)
            throws IOException, InterruptedException {
        // examples run [flags] <name> [-- args]: flags meant for the compiler verb itself
        // (./flixw run --entrypoint Foo.main at the root) precede <name>, the same order
        // the root command already uses, rather than needing to hide behind the name.
        List<List<String>> split = splitVerbFlags(rest, valueTaking);
        List<String> verbFlags = split.get(0), afterFlags = split.get(1);
        if (afterFlags.isEmpty()) {
            // "examples run --help" alone -- nothing left over to be <name> once --help/-h
            // is peeled off as a flag. Contradicting "never intercepted, in any position"
            // for the one flag that needs no example directory to answer is worse than the
            // small inconsistency of running it from root instead of an example's own
            // directory, which --help cannot tell apart anyway.
            if (verbFlags.contains("--help") || verbFlags.contains("-h"))
                launch(root, javaExe, compilerJar, jvmOpts, verb, verbFlags, List.of());
            System.err.println("flixw examples: " + verb + " needs a name -- known: "
                             + String.join(" ", discover(root)));
            throw new Exit(87);
        }
        String name = afterFlags.get(0);
        // Everything after <name> is forwarded verbatim, INCLUDING a leading "--". Flix's
        // own `run` rejects trailing words as unsupported "file arguments" unless "--"
        // introduces them (verified against a real compiler: `run foo` refuses to run at
        // all, `run -- foo` delivers foo to Sys.Env.Env.getArgs()) -- so stripping it here
        // would silently break the one thing this command exists for.
        List<String> forward = afterFlags.subList(1, afterFlags.size());

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

        launch(dir, javaExe, compilerJar, jvmOpts, verb, verbFlags, forward);
    }

    /**
     * Same shape stage 0's own {@code launch()} uses for the root project's compiler: options
     * between the executable and {@code -jar}, so "options for the compiler JVM" means the
     * same thing here as it does for {@code ./flixw run} -- already validated and tokenized by
     * stage 0's {@code jvmOpts()}, never re-parsed from a raw string in this asset. Never
     * returns: the compiler's exit code becomes this process's own either way.
     */
    static void launch(Path dir, Path javaExe, Path compilerJar, List<String> jvmOpts,
                        String verb, List<String> verbFlags, List<String> forward)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(jvmOpts);
        cmd.add("-jar");
        cmd.add(compilerJar.toString());
        cmd.add(verb);
        cmd.addAll(verbFlags);
        cmd.addAll(forward);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        throw new Exit(p.waitFor());
    }

    static String q(String s) { return "'" + s + "'"; }

    static String protocolUsage() {
        return "usage: java flixw-examples.java <root> <javaExe> <compilerJar>"
             + " <jvmOptCount> [jvmOpt...] <helpText> <verb> [args...]";
    }

    static String usageText() {
        return "usage: ./flixw examples list"
             + "\n       or: ./flixw examples run|check|build|test [flags] <name> [-- args]";
    }
}
