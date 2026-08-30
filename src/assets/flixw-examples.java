import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders {@code ./flixw examples ...} -- a wrapper-owned companion asset, not a plugin.
 *
 * <p>{@code java flixw-examples.java <root> <javaExe> <compilerJar> <jvmOptCount>
 * [jvmOpt...] <helpText> <upstream> <verb> [args...]} -- the option count precedes the
 * options themselves so an arbitrary-length, already-tokenized list can sit between fixed
 * positions with no delimiter to collide with a real option string. {@code <helpText>} is
 * the compiler's own captured {@code --help} (or an empty string if none was captured),
 * used only to tell a value-taking verb flag from the example name that follows it.
 * {@code <upstream>} is {@code true} only when the pinned compiler is upstream Flix,
 * unoverridden by {@code FLIX_JAR} -- verified once in stage 0, which already knows the
 * lock's repository, rather than re-derived here from anything about the captured text.
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
        int upstreamAt = helpAt + 1;
        if (upstreamAt + 1 >= args.length) {
            System.err.println(protocolUsage());
            throw new Exit(87);
        }
        List<String> jvmOpts = List.of(args).subList(4, helpAt);
        String helpText = args[helpAt];
        boolean upstream = Boolean.parseBoolean(args[upstreamAt]);
        int verbAt = upstreamAt + 1;
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
            //
            // Every local, side-effect-free build verb is listed explicitly rather than
            // accepted as any word dispatch() has not seen -- an unbounded pass-through
            // would forward a typo to the compiler as readily as a real verb, one layer
            // later than the "unknown command" this asset can already give directly.
            // init is excluded on purpose: it creates a *new* project, and every verb here
            // is reached through discover()/known.contains(name), which already requires
            // the example to exist. release is excluded too: it pushes to GitHub using
            // the example's own manifest, an external, stateful action no other verb here
            // takes, and not something a generic relay should trigger by name alone. repl,
            // lsp and lsp-vscode are long-running/interactive rather than a batch command
            // with an exit code, which is the shape every other verb here shares.
            case "run", "check", "build", "build-classes", "build-jar", "build-fatjar",
                 "build-pkg", "clean", "doc", "format", "outdated", "eff-check", "eff-lock",
                 "test" ->
                dispatch(root, javaExe, compilerJar, jvmOpts, helpText, upstream, verb, rest);
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

    /** Bounds matching {@code flixw-help.java}'s own {@code probe} -- the same subprocess,
     *  the same reasons: real help is small and fast, and a JAR that is not the Flix
     *  compiler must not be able to wedge this on either count. */
    static final long PROBE_SECONDS = 30;
    static final int PROBE_CAP = 1 << 20;

    /**
     * A verb's own flags, added to the flat top-level set rather than replacing it, when
     * the pinned compiler actually has real per-command help to add anything from.
     *
     * <p>Stock Flix does not: every verb's {@code --help} echoes the identical top-level
     * screen ({@code flixw-help.java}'s {@code flix()} already relies on this exact
     * byte-equality to tell "no per-command help" from a real answer), so for it this
     * degrades to exactly today's flat {@link #valueTakingOptions}, unchanged. A fork with
     * real per-command help answers differently per verb, and only then is there anything a
     * per-verb probe could learn that the flat set does not already know -- a versioned,
     * hand-maintained schema of every Flix release's flags would carry that same fact for
     * versions nobody using this project has ever pinned; asking the exact jar in hand
     * costs one subprocess and is never wrong about it.
     *
     * <p>A union, not a replacement: many real CLIs document a subcommand's own flags in
     * its help and leave an inherited global one to the top level alone, the same reason
     * {@code --entrypoint} does not repeat under every command in flix/flix's own layout.
     * Replacing the flat set with the per-verb one whenever they differ at all would then
     * forget every global flag the per-verb screen simply did not re-list, and {@code
     * examples run --global VALUE <name>} would mistake {@code VALUE} for {@code <name>}.
     */
    static Set<String> verbValueTaking(Path javaExe, List<String> jvmOpts, Path jar,
                                        String verb, String helpText) {
        Set<String> top = valueTakingOptions(helpText);
        String perVerb = probe(javaExe, jvmOpts, jar, verb);
        if (perVerb == null || perVerb.strip().equals((helpText == null ? "" : helpText).strip()))
            return top;
        Set<String> union = new HashSet<>(top);
        union.addAll(valueTakingOptions(perVerb));
        return union;
    }

    /**
     * The compiler's answer to {@code <verb> --help}, or null if it cannot be had --
     * {@code flixw-help.java}'s own {@code probe}, duplicated rather than shared, since
     * nothing here loads another asset's classes. Takes the same {@code jvmOpts} the real
     * launch does: a fork needing one just to start (e.g. {@code --enable-preview}) must
     * not probe with a bare {@code java -jar} and silently fall back to the flat set for a
     * reason that has nothing to do with per-command help existing or not.
     */
    static String probe(Path javaExe, List<String> jvmOpts, Path jar, String verb) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe.toString());
            cmd.addAll(jvmOpts);
            cmd.add("-jar");
            cmd.add(jar.toString());
            cmd.add(verb);
            cmd.add("--help");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuffer b = new StringBuffer();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while (b.length() < PROBE_CAP && (n = in.read(buf)) > 0)
                        b.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    // Reaching the cap ends the child now, the same as runCapture's own
                    // reader: closing the pipe alone does not, since a writer that ignores
                    // the error keeps going and still costs the whole PROBE_SECONDS timeout
                    // below, for output already being discarded.
                    if (b.length() >= PROBE_CAP) { p.destroy(); p.destroyForcibly(); }
                } catch (IOException ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(1000);
            return b.toString().replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException | InterruptedException e) {
            return null;
        }
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
                         String helpText, boolean upstream, String verb, List<String> rest)
            throws IOException, InterruptedException {
        // examples run [flags] <name> [-- args]: flags meant for the compiler verb itself
        // (./flixw run --entrypoint Foo.main at the root) precede <name>, the same order
        // the root command already uses, rather than needing to hide behind the name.
        //
        // Only probed when there is a leading flag to disambiguate at all: the common case
        // (examples run cli-tool, nothing before <name>) never consults valueTaking, so
        // spawning a subprocess just to compute a set splitVerbFlags will not look at would
        // cost every invocation for the benefit of none of them.
        Set<String> valueTaking = (!rest.isEmpty() && rest.get(0).startsWith("-"))
            ? verbValueTaking(javaExe, jvmOpts, compilerJar, verb, helpText) : Set.of();
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
        // Unlike check/test, where a bare trailing word is a legitimate extra file to
        // compile, upstream run has no such reading -- the compiler rejects one outright,
        // so a missing -- here can only ever be an omission, never a real choice being
        // overridden. Insert it rather than making the caller retype the one thing this
        // position can mean; check/build/test are left exactly as typed. A token that
        // already starts with "-" is left alone even for run: it might already be "--", or
        // it might be a flag like --help that must reach the compiler unwrapped, not a bare
        // word to forward -- the same ambiguity stage 0's own autoRunBoundary declines.
        // Gated on upstream: a fork's run may define its own positional operand, and this
        // fact was verified against flix/flix alone, not against every fork or FLIX_JAR.
        if (upstream && "run".equals(verb) && !forward.isEmpty() && !forward.get(0).startsWith("-")) {
            List<String> withBoundary = new ArrayList<>();
            withBoundary.add("--");
            withBoundary.addAll(forward);
            forward = withBoundary;
        }

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
             + " <jvmOptCount> [jvmOpt...] <helpText> <upstream> <verb> [args...]";
    }

    static String usageText() {
        return "usage: ./flixw examples list"
             + "\n       or: ./flixw examples <verb> [flags] <name> [-- args]"
             + "\n       verbs: run check build build-classes build-jar build-fatjar"
             + " build-pkg clean doc format outdated eff-check eff-lock test";
    }
}
