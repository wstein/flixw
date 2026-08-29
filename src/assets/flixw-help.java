import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Model.UsageMessageSpec;

/**
 * Renders {@code ./flixw help}: a wrapper-owned companion asset, not a plugin.
 *
 * <p>{@code java -cp picocli.jar flixw-help.java <context-file> <topic> [<name>]}
 *
 * <p>It re-gathers nothing. Stage 0 hands it a context file holding what it already computed
 * on the way here -- the compiler's captured help text, the verb sets it dispatches on, the
 * project's plugins and tasks -- and this asset formats that. Receiving gathered state rather
 * than rescanning is the condition under which anything is allowed to leave stage 0 at all:
 * a second scanner would be free to disagree with the one that actually routes commands, and
 * the table a person reads would be the one that never runs.
 *
 * <p><b>picocli renders; it does not parse.</b> flixw is not a picocli application and its
 * argument handling stays in stage 0, where it is auditable without a dependency. What picocli
 * is used for here is the one thing a hand-rolled renderer does badly: laying out a command
 * tree with wrapped, aligned descriptions across four sources -- the compiler, the wrapper,
 * plugins and tasks -- so they read as one help system rather than four.
 *
 * <p><b>The compiler's own words are never rewritten.</b> Flix owns its help text the way it
 * owns its diagnostics. flixw frames it and says where it came from; the text itself passes
 * through untouched, so a layout flixw misreads still shows a reader what the compiler
 * actually said instead of a confident summary of something else.
 */
final class flixwhelp {
    private flixwhelp() {}

    /** A per-command probe is one subprocess against a JAR stage 0 has already verified. */
    static final long PROBE_SECONDS = 30;
    static final int PROBE_CAP = 1 << 20;

    /**
     * Standalone entry: the code {@link #run} returns becomes the process's.
     *
     * <p>Kept so the asset still works when launched as a program -- which for the installer is
     * the documented bootstrap, and for the others is how a source launch runs them.
     */
    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    /**
     * The real entry point, returning what it would have exited with.
     *
     * <p>An asset used to end by calling {@code System.exit}, which is correct for a program and
     * fatal for a library: the wrapper now loads these in its own JVM, where an exit would take
     * the wrapper down mid-command and skip whatever it still had to clean up. So the exits
     * became a control-flow signal that stops at this boundary, and the code travels back as a
     * value the way any other result does.
     */
    public static int run(String[] args) throws Exception {
        try {
            body(args);
            return 0;
        } catch (Exit e) {
            return e.code;
        }
    }

    /** What {@code System.exit} used to do, scoped to this asset. */
    static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code) {
            // No message, no stack: it is a jump, not a failure, and filling one in for every
            // usage error would cost more than the check that raised it.
            super(null, null, false, false);
            this.code = code;
        }
    }

    private static void body(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java -cp picocli.jar flixw-help.java"
                             + " <context-file> [<topic> [<name>]]");
            throw new Exit(87);
        }
        Ctx c = Ctx.read(Paths.get(args[0]));
        String topic = args.length > 1 ? args[1] : null;
        String name = args.length > 2 ? args[2] : null;

        if (topic == null) { overview(c); return; }
        switch (topic) {
            case "flix" -> flix(c, name);
            case "wrapper" -> render(wrapperSpec(c));
            case "plugin" -> plugin(c, name);
            case "task" -> task(c, name);
            case "completion" -> completion(c, name);
            default -> {
                System.err.println("flixw: no help topic " + q(topic));
                // pin, info, doctor and validate are commands, not topics -- they are
                // documented together under "wrapper" rather than one topic each, which is
                // exactly the distinction someone typing `help pin` has not made yet. Naming
                // the redirect is cheaper than leaving them to rediscover it from the list.
                if (c.words("wrapperVerbs").contains(topic))
                    System.err.println("       " + q(topic) + " is a wrapper verb, not a help"
                                     + " topic -- run: ./flixw " + topic + " --help"
                                     + "   or: ./flixw help wrapper");
                else
                    System.err.println("       topics: flix wrapper plugin task completion");
                throw new Exit(89);
            }
        }
    }

    // ---- the context stage 0 hands over -------------------------------------

    /**
     * Key/value lines, then blank-line-separated {@code section:} blocks of tab-separated
     * rows -- the same shape {@code flixw-inspect.java} is given, deliberately, so there is
     * one context format to learn rather than one per asset.
     */
    static final class Ctx {
        final Map<String, String> kv = new LinkedHashMap<>();
        final Map<String, List<String[]>> sections = new LinkedHashMap<>();

        static Ctx read(Path p) throws IOException {
            Ctx c = new Ctx();
            String section = null;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (line.endsWith(":") && !line.contains("=")) {
                    section = line.substring(0, line.length() - 1);
                    c.sections.put(section, new ArrayList<>());
                } else if (section != null) {
                    String[] row = line.split("\t", -1);
                    for (int i = 0; i < row.length; i++) row[i] = unesc(row[i]);
                    c.sections.get(section).add(row);
                } else {
                    int eq = line.indexOf('=');
                    if (eq > 0) c.kv.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            return c;
        }

        /**
         * Reverses stage 0's {@code esc}. A task command is an arbitrary shell string and may
         * hold a tab or a newline, which would otherwise split one row into two or shift every
         * field after it; the two routines are a pair and have to change together.
         */
        static String unesc(String s) {
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch != '\\' || i + 1 >= s.length()) { out.append(ch); continue; }
                char next = s.charAt(++i);
                out.append(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            }
            return out.toString();
        }

        String get(String k) { return kv.getOrDefault(k, ""); }
        List<String[]> rows(String s) { return sections.getOrDefault(s, List.of()); }
        List<String> words(String k) {
            String v = get(k);
            return v.isBlank() ? List.of() : List.of(v.split(" +"));
        }
    }

    // ---- compiler help formats ----------------------------------------------

    /**
     * Which help layout this text is, by shape rather than by compiler version.
     *
     * <p>Version sniffing would be wrong twice over: a fork may ship either layout at any
     * version, and the layout is the only thing that actually decides whether a parse
     * succeeds. The shape is right there in the bytes, so it is what gets asked.
     */
    static String format(String help) {
        if (help.contains("\nCommand: ")) return "scopt-v1";
        if (help.contains("\nCommands:")) return "picocli-v1";
        return "raw";
    }

    /** scopt: {@code Command: check} and then its indented prose. */
    static final Pattern SCOPT_ENTRY = Pattern.compile(
        "(?m)^Command:\\s+([A-Za-z][A-Za-z0-9_-]*)[^\\n]*\\n((?:[ \\t]+[^\\n]*\\n?)*)");

    /** picocli: an indented two-column row inside the {@code Commands:} block. */
    static final Pattern PICOCLI_ENTRY = Pattern.compile("^ {2}([a-z][a-z0-9_-]*)(?:\\s\\s+(.*))?$");

    /**
     * An option row in either layout: a short form, a long form, or both, then prose.
     *
     * <p>Two things here are for picocli's layout rather than scopt's, and both were found by
     * running a fork's real help through it. Its parameter is attached with {@code =} instead
     * of a space, and requiring the space dropped every value-taking option on the floor --
     * from the help screen and from the generated completions with it, which is where a
     * value-taking option matters most. And its prose is optional, because a name long enough
     * to fill the column pushes the description onto the next line entirely; that row is an
     * option with its description still to come, not a non-match.
     */
    static final Pattern OPTION_ENTRY = Pattern.compile(
        "^(\\s*)(?:-([A-Za-z0-9])(?:[, ]\\s*)?)?(--[A-Za-z][A-Za-z0-9-]*)?"
      + "(?:[\\s=]+<([^>]*)>)?(?:\\s\\s+(\\S.*))?$");

    /**
     * Command name to description, in the order the compiler listed them.
     *
     * <p>Empty for {@code raw}, and that is a result rather than a failure. An unrecognised
     * layout means flixw does not know what the commands are, and a hopeful regex over
     * unknown prose is how a help screen starts inventing them: run the widely used
     * {@code gencomp} against Flix and every entry it produces is the first word of a
     * *description* -- {@code creates}, {@code checks}, {@code builds} five times -- because
     * its section detector consumes the very line the command name is on. Saying nothing is
     * the better failure.
     */
    static Map<String, String> commands(String help) {
        Map<String, String> out = new LinkedHashMap<>();
        String fmt = format(help);
        if (fmt.equals("scopt-v1")) {
            Matcher m = SCOPT_ENTRY.matcher(help);
            while (m.find()) out.put(m.group(1), collapse(m.group(2)));
        } else if (fmt.equals("picocli-v1")) {
            boolean inBlock = false;
            for (String line : help.split("\n", -1)) {
                if (!inBlock) { inBlock = line.startsWith("Commands:"); continue; }
                Matcher m = PICOCLI_ENTRY.matcher(line);
                if (m.find()) out.put(m.group(1), collapse(m.group(2)));
                else if (!line.isBlank() && !line.startsWith("   ")) break;
            }
        }
        return out;
    }

    /** An option's spelling to its description, for both layouts alike. */
    static Map<String, String[]> options(String help) {
        Map<String, String[]> out = new LinkedHashMap<>();
        String key = null;                  // the row still open for continuation lines
        String[] row = null;
        StringBuilder prose = new StringBuilder();
        int indent = 0;
        for (String raw : help.split("\n", -1)) {
            String line = raw.replace('\t', ' ');
            Matcher m = OPTION_ENTRY.matcher(line);
            boolean isOption = m.matches() && (m.group(2) != null || m.group(3) != null);
            if (isOption) {
                if (key != null) out.put(key, finish(row, prose));
                indent = m.group(1).length();
                String shortOpt = m.group(2), longOpt = m.group(3);
                key = longOpt != null ? longOpt : "-" + shortOpt;
                row = new String[] { shortOpt == null ? "" : "-" + shortOpt,
                                     longOpt == null ? "" : longOpt,
                                     m.group(4) == null ? "" : m.group(4), "" };
                prose = new StringBuilder(m.group(5) == null ? "" : m.group(5));
            } else if (key != null && !line.isBlank() && leading(line) > indent
                       && !line.startsWith("Command:")) {
                // Wrapped prose, which picocli indents past the description column. Anything
                // at or left of the option's own indent has left the block -- a `Commands:`
                // heading, the next section -- and swallowing it would append a command list
                // to whichever option happened to be last.
                prose.append(' ').append(line.trim());
            } else if (key != null && !line.isBlank()) {
                out.put(key, finish(row, prose));
                key = null;
            }
        }
        if (key != null) out.put(key, finish(row, prose));
        return out;
    }

    /** Seals a row with its prose collapsed onto one line. */
    static String[] finish(String[] row, StringBuilder prose) {
        row[3] = collapse(prose.toString());
        return row;
    }

    /** Leading spaces, which is how a continuation line is told from a new section. */
    static int leading(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    /** Wrapped prose onto one line; a description is a sentence, not a layout. */
    static String collapse(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    // ---- rendering ----------------------------------------------------------

    /** One renderer for every topic, so the topics cannot drift apart in appearance. */
    static void render(CommandSpec spec) {
        new CommandLine(spec).setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.AUTO))
                             .usage(System.out);
    }

    /**
     * Renders the overview with its command list grouped by who answers each word.
     *
     * <p>Only the overview: every other topic has a single provider, so a heading there would
     * label a group of one and a colour would style the whole screen.
     */
    static void renderGrouped(CommandSpec spec, Ctx c) {
        CommandLine cl = new CommandLine(spec)
            .setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.AUTO));
        cl.getHelpSectionMap().put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
                                   help -> commandList(help, c));
        // Our groups carry their own headings, so picocli's single "Commands:"
        // would announce the first of them and then be contradicted by the rest.
        cl.getHelpSectionMap().put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING,
                                   help -> "");
        cl.usage(System.out);
    }

    /**
     * The command list, grouped by provider, with the minority coloured.
     *
     * <p>picocli emits one flat list under a single style, and flat is what hides the routing
     * model: {@code run} is the compiler's for good, {@code doctor} is the wrapper's only until
     * Flix implements the word. The headings say which is which, and they say it on a monochrome
     * terminal too -- colour reinforces the grouping, it never carries it alone. That also
     * retires the {@code (wrapper)} prefix each of those descriptions used to open with, since
     * the heading now states what the prefix was repeating.
     *
     * <p>The groups were briefly coloured as well -- the minority tinted, compiler verbs left
     * alone. The headings turned out to do the whole job, so the colour was reinforcing a
     * distinction the reader could already see, at the price of a terminal-detection path,
     * a {@code NO_COLOR} path, and a case that could not run on Windows at all because
     * picocli reads Git Bash as a pseudo-TTY. Removed rather than kept as decoration.
     */
    static String commandList(CommandLine.Help help, Ctx c) {
        List<String> compilerVerbs = c.words("compilerVerbs");
        List<String[]> compiler = new ArrayList<>(), wrapper = new ArrayList<>();
        for (Map.Entry<String, CommandLine> e : help.commandSpec().subcommands().entrySet()) {
            String[] d = e.getValue().getCommandSpec().usageMessage().description();
            // tree() prefixes wrapper descriptions; the heading replaces it.
            String text = d.length == 0 ? "" : d[0].replaceFirst("^\\(wrapper\\) ", "");
            (compilerVerbs.contains(e.getKey()) ? compiler : wrapper)
                .add(new String[] { e.getKey(), text });
        }
        List<String[]> plugins = new ArrayList<>(), tasks = new ArrayList<>();
        // What it is for, not which version it is: a version is state and `./flixw info -v`
        // reports it. The text is the plugin's own, read from its jar manifest at install
        // time and recorded in the lock -- so a plugin that declares nothing simply has no
        // description, rather than flixw inventing one or running the plugin to ask.
        // A plugin that declared a verb is listed as that verb, because that is what a
        // reader would type; one that did not keeps the long form, which always works.
        for (String[] r : c.rows("plugins")) {
            String verb = r.length > 5 && !r[5].isEmpty() ? r[5] : "plugin " + r[0];
            plugins.add(new String[] { verb, r.length > 4 ? r[4] : "" });
        }
        for (String[] r : c.rows("tasks"))
            tasks.add(new String[] { "task " + r[0], r.length > 1 ? r[1] : "" });

        // One column width across every group, so the groups read as one table that happens
        // to have headings rather than four tables that happen to be adjacent.
        int w = 0;
        for (List<String[]> g : List.of(compiler, wrapper, plugins, tasks))
            for (String[] r : g) w = Math.max(w, r[0].length());

        int width = help.commandSpec().usageMessage().width();
        StringBuilder out = new StringBuilder();
        group(out, width, w, "Compiler commands:", compiler);
        group(out, width, w, "Wrapper commands:", wrapper);
        group(out, width, w, "Plugin commands:", plugins);
        group(out, width, w, "Task commands:", tasks);
        return out.toString();
    }

    /** One heading and its rows; nothing at all when the group is empty. */
    static void group(StringBuilder out, int width, int w,
                      String heading, List<String[]> rows) {
        if (rows.isEmpty()) return;
        out.append(heading).append('\n');
        for (String[] r : rows) {
            String pad = " ".repeat(w - r[0].length());
            String head = "  " + r[0] + pad + "  ";
            for (String line : wrap(r[1], Math.max(20, width - w - 4))) {
                out.append(head).append(line).append('\n');
                head = "  " + " ".repeat(w) + "  ";       // continuation lines hang under it
            }
            if (r[1].isEmpty()) out.append(head).append('\n');
        }
        out.append('\n');
    }

    /** Greedy word wrap; a description is a sentence and the terminal decides how wide. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    static CommandSpec base(String name, String... description) {
        CommandSpec s = CommandSpec.create().name(name);
        s.usageMessage().description(description).abbreviateSynopsis(true);
        return s;
    }

    static CommandSpec sub(CommandSpec parent, String name, String description) {
        CommandSpec child = CommandSpec.create().name(name);
        child.usageMessage().description(description == null || description.isEmpty()
                                         ? "" : description);
        parent.addSubcommand(name, new CommandLine(child));
        return child;
    }

    /**
     * The flags {@code ./flixw wrapper} answers, on whichever spec asked for them.
     *
     * <p>Shared by the {@code help wrapper} screen and by the entry in the command tree,
     * because the tree is what the completions are generated from: listed in one and not
     * the other means TAB offers a flag the help does not document, or the reverse.
     */
    static void wrapperOptions(CommandSpec s) {
        s.addOption(OptionSpec.builder("--version").description("the wrapper version").build());
        s.addOption(OptionSpec.builder("--upgrade")
                    .description("move this project to the newest published flixw, or to --upgrade <version>").build());
        s.addOption(OptionSpec.builder("--install-jdk")
                    .description("fetch a verified Temurin into the cache").build());
        s.addOption(OptionSpec.builder("--purge")
                    .description("delete cache entries unused for N days").build());
        s.addOption(OptionSpec.builder("--schema")
                    .description("the JSON Schema for .flixw/lock.toml").build());
    }

    /**
     * The combined overview: every command this project would actually dispatch, grouped by
     * who answers it.
     *
     * <p>The grouping is the part flixw can be authoritative about and the compiler cannot.
     * Dispatch is compiler-first, so which side answers a word depends on the pinned
     * compiler's verb set, and a reader looking at two separate help screens has no way to
     * work out which of them is currently winning.
     */
    static void overview(Ctx c) {
        renderGrouped(tree(c, "./flixw"), c);

        System.out.println();
        System.out.println("  ./flixw help flix [<command>]    the pinned compiler's own help");
        System.out.println("  ./flixw help wrapper             the wrapper's own reference");
        System.out.println("  ./flixw help plugin [<name>]     installed plugins");
        System.out.println("  ./flixw help task [<name>]       this project's tasks");
        System.out.println("  ./flixw -- --help                stock Flix help, unedited");
    }

    /**
     * The command tree this project would actually dispatch, as one picocli model.
     *
     * <p>Built once and used twice -- to render {@code help}, and as the model picocli's own
     * {@code AutoComplete} turns into a bash/zsh completion. That sharing is the point: a
     * completion generated from a different tree than the help screen describes is a
     * completion that disagrees with the documentation on the same terminal.
     */
    static CommandSpec tree(Ctx c, String name) {
        List<String> compilerVerbs = c.words("compilerVerbs");
        Map<String, String> desc = c.get("helpFile").isEmpty()
            ? Map.of() : commands(readOrEmpty(c.get("helpFile")));

        CommandSpec root = base(name,
            "flixw " + c.get("flixwVersion") + " -- repository-local Flix bootstrap.",
            "",
            c.get("compilerVersion").isEmpty()
                ? "No compiler pinned yet. Run: ./flixw pin <version>"
                : "Pinned compiler: Flix " + c.get("compilerVersion") + ".",
            "Dispatch is compiler-first: a word the compiler implements goes to the compiler,"
          + " so the wrapper's own verbs retire by themselves as Flix grows.");

        for (String v : compilerVerbs) sub(root, v, desc.getOrDefault(v, ""));
        for (String v : c.words("wrapperVerbs"))
            if (!compilerVerbs.contains(v)) sub(root, v, "(wrapper) " + wrapperDesc(v));
        // `wrapper` and `completion` are words a user types and neither is in WRAPPER_VERBS:
        // the first is a namespace of flags, the second is answered before that table is
        // consulted at all. Both were therefore absent from this screen while the offline
        // fallback listed them, so the renderer with the whole model showed strictly less
        // than the one with none of it. Same compiler-first guard as any other word.
        // Both carry their own arguments into the tree, because the tree is the model the
        // completions come from: a word with no arguments completes to nothing after it.
        if (!compilerVerbs.contains("wrapper"))
            wrapperOptions(sub(root, "wrapper", "(wrapper) " + wrapperDesc("wrapper")));
        if (!compilerVerbs.contains("completion"))
            sub(root, "completion", "(wrapper) " + wrapperDesc("completion"))
                .addPositional(PositionalParamSpec.builder().paramLabel("<shell>")
                    .completionCandidates(List.of("bash", "zsh", "fish", "pwsh"))
                    .description("the shell to emit a script for").build());
        if (!c.get("helpFile").isEmpty()) addOptions(root, readOrEmpty(c.get("helpFile")));
        return root;
    }

    static String wrapperDesc(String verb) {
        return switch (verb) {
            case "pin" -> "write .flixw/lock.toml: repository, version and digest.";
            case "info" -> "project, compiler, java and cache state.";
            case "doctor" -> "info plus every check, with a verdict; --fix repairs.";
            case "validate" -> "the checks alone, for CI.";
            case "help" -> "this table.";
            case "plugin" -> "install, upgrade, list, remove and run verified third-party commands.";
            case "task" -> ".flixw/tasks.toml's aliases.";
            case "examples" -> "run or check an examples/<name> package against this project's compiler.";
            case "wrapper" -> "--version, --upgrade, --install-jdk, --purge, --schema.";
            case "completion" -> "a TAB-completion script for bash, zsh, fish or pwsh.";
            default -> "";
        };
    }

    static CommandSpec wrapperSpec(Ctx c) {
        CommandSpec s = base("./flixw",
            "flixw " + c.get("flixwVersion") + " -- the wrapper's own commands.",
            "",
            "These are answered by the wrapper unless the pinned compiler implements the same"
          + " word, in which case the compiler wins and the wrapper's version is deprecated.",
            "pin, info, doctor, validate and help stay in the wrapper permanently: they are"
          + " what a fresh clone needs before anything else can be trusted to run at all.");
        for (String v : c.words("wrapperVerbs")) sub(s, v, wrapperDesc(v));
        // Shared with the command tree rather than repeated. This list said `--completion`
        // for as long as it was repeated: the flag became `./flixw completion <shell>`, and
        // this screen went on advertising an operation stage 0 answers with FLIXW008.
        wrapperOptions(s);
        sub(s, "completion", "a TAB-completion script for bash, zsh, fish or pwsh")
            .addPositional(PositionalParamSpec.builder().paramLabel("<shell>")
                .completionCandidates(List.of("bash", "zsh", "fish", "pwsh"))
                .description("the shell to emit a script for").build());
        return s;
    }

    // ---- flix ----------------------------------------------------------------

    static void flix(Ctx c, String name) throws IOException, InterruptedException {
        String path = c.get("helpFile");
        if (path.isEmpty()) {
            System.err.println("flixw: no compiler help has been captured for this project");
            System.err.println("       run: ./flixw pin <version>   (then any compiler verb once)");
            throw new Exit(89);
        }
        String help = readOrEmpty(path);
        String version = c.get("compilerVersion");
        if (name == null) { flixOverview(c, help, version); return; }

        // The probe, and why it is a comparison rather than an exit-status check: scopt does
        // not reject `check --help`, it prints the *top-level* help and exits 0. Trusting the
        // exit status would render that under a "check" heading and call it per-command help,
        // forever. Byte-equality against the text already held is the honest test, and it
        // needs no knowledge of the layout -- so a future picocli-based Flix, which does have
        // real per-command help, starts working here with no change to flixw at all.
        String probed = probe(c.get("javaExe"), c.get("compilerJar"), name);
        if (probed != null && !probed.strip().equals(help.strip())) {
            System.out.println("Flix " + version + "  --  " + name);
            System.out.println();
            System.out.print(probed.endsWith("\n") ? probed : probed + "\n");
            return;
        }

        Map<String, String> known = commands(help);
        if (!known.containsKey(name)) {
            System.err.println("flixw: Flix " + version + " lists no command " + q(name));
            System.err.println(known.isEmpty()
                ? "       flixw does not recognise this compiler's help layout;"
                + "\n       run: ./flixw help flix   (to see it unedited)"
                : "       known commands: " + String.join(" ", known.keySet()));
            throw new Exit(89);
        }

        CommandSpec s = base("./flixw " + name,
            known.get(name).isEmpty() ? "(the compiler's help gives no description)"
                                      : known.get(name),
            "",
            "Flix " + version + " publishes only a top-level --help, so the line above is all"
          + " it documents for this command. `./flixw -- " + name + " --help` reaches the"
          + " compiler directly and prints that same top-level screen.");
        addOptions(s, help);
        render(s);
    }

    /**
     * The compiler's own help, verbatim.
     *
     * <p>Not re-rendered, and that is the rule rather than a shortcut. Raw {@code --help} is
     * for showing what the compiler actually says; it is not a source to generate behaviour
     * from. Re-laying it out would put flixw's parse between the reader and the words it is
     * quoting, so a layout flixw misread would show a confident summary of something else --
     * and this is the one screen whose entire job is to be the compiler's, unedited.
     */
    static void flixOverview(Ctx c, String help, String version) {
        System.out.println("Flix " + version + " -- the pinned compiler's own help, as captured"
                         + " (" + format(help) + ").");
        System.out.println("For it straight from the compiler instead: ./flixw -- --help");
        System.out.println();
        System.out.print(help.endsWith("\n") ? help : help + "\n");
    }

    static void addOptions(CommandSpec s, String help) {
        for (String[] o : options(help).values()) {
            List<String> names = new ArrayList<>();
            if (!o[0].isEmpty()) names.add(o[0]);
            if (!o[1].isEmpty()) names.add(o[1]);
            OptionSpec.Builder b = OptionSpec.builder(names.toArray(new String[0]))
                                             .description(o[3]);
            if (!o[2].isEmpty()) b.paramLabel("<" + o[2] + ">").arity("1");
            try { s.addOption(b.build()); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * The compiler's answer to {@code <command> --help}, or null if it cannot be had.
     *
     * <p>The read runs on its own thread and the wait is on the process, not on the stream.
     * Reading first and timing out afterwards looks equivalent and is not: a child that
     * starts, writes nothing and never exits blocks in {@code read} forever, so the timeout
     * is never reached and {@code help flix check} hangs with no output and no way out but
     * a signal. {@code FLIX_JAR} can point at any jar at all, which is exactly how a program
     * that behaves like that gets here.
     */
    static String probe(String javaExe, String jar, String name)
            throws IOException, InterruptedException {
        if (javaExe.isEmpty() || jar.isEmpty()) return null;
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-jar", jar, name, "--help");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // StringBuffer, not StringBuilder: two threads touch it, and if the join below times
        // out they touch it at once. The synchronisation is the point, not an accident.
        StringBuffer b = new StringBuffer();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while (b.length() < PROBE_CAP && (n = in.read(buf)) > 0)
                    b.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        });
        reader.setDaemon(true);                  // never keeps the JVM alive past the answer
        reader.start();
        if (!p.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        // The child is gone; the reader has at most a buffer left to drain.
        reader.join(1000);
        return b.toString();
    }

    // ---- plugins and tasks ----------------------------------------------------

    /**
     * Static metadata only. A plugin is third-party code, and running it to find out what it
     * does is the thing the trust boundary exists to prevent: {@code help} must stay safe to
     * type. Everything here comes from the lock and the cache directory. A plugin that wants
     * to describe itself further can still be asked directly, with the usual warning, via
     * {@code ./flixw plugin <name> --help}.
     */
    static void plugin(Ctx c, String name) {
        List<String[]> rows = c.rows("plugins");
        if (name == null) {
            CommandSpec s = base("./flixw plugin",
                "Verified third-party commands, installed explicitly and re-hashed on every"
              + " run.",
                "",
                rows.isEmpty() ? "None installed in this project."
                               : "Namespaced on purpose: a plugin can never collide with a"
                               + " compiler verb, another plugin, or a future wrapper verb.");
            for (String[] r : rows)
                sub(s, r[0], r.length > 1 ? "version " + r[1] : "");
            s.addOption(OptionSpec.builder("install").description(
                "plugin install <name> <version> <url> [--sha256 <digest>]").build());
            s.addOption(OptionSpec.builder("upgrade")
                        .description("plugin upgrade [<name>] -- move to the newest release").build());
            s.addOption(OptionSpec.builder("list").description("installed plugins").build());
            s.addOption(OptionSpec.builder("remove").description("remove one").build());
            render(s);
            return;
        }
        for (String[] r : rows) {
            if (!r[0].equals(name)) continue;
            System.out.println("plugin " + name);
            System.out.println();
            if (r.length > 1) System.out.println("  version   " + r[1]);
            if (r.length > 2) System.out.println("  sha256    " + r[2]);
            if (r.length > 3 && !r[3].isEmpty()) System.out.println("  source    " + r[3]);
            System.out.println();
            System.out.println("  run:      ./flixw plugin " + name + " [args...]");
            System.out.println("  This is third-party code, not audited by flixw. Its bytes are");
            System.out.println("  re-hashed against the digest above on every single run.");
            return;
        }
        System.err.println("flixw: no plugin " + q(name) + " in this project's lock");
        System.err.println("       run: ./flixw plugin list");
        throw new Exit(89);
    }

    /**
     * Tasks are safe to describe in full: unlike a plugin, a task is a shell string in a file
     * the project already committed, so printing it verbatim reveals nothing that a reader
     * could not get from {@code cat}, and hiding it would only make the wrapper look like it
     * was running something it would not show.
     */
    static void task(Ctx c, String name) {
        List<String[]> rows = c.rows("tasks");
        if (name == null) {
            CommandSpec s = base("./flixw task",
                ".flixw/tasks.toml -- npm-`scripts`-style aliases for this project.",
                "",
                rows.isEmpty() ? "No tasks defined in this project."
                               : "Never fetched and never verified, because there is nothing to"
                               + " verify: the file is committed alongside the code it builds.");
            for (String[] r : rows) sub(s, r[0], r.length > 1 ? r[1] : "");
            render(s);
            return;
        }
        for (String[] r : rows) {
            if (!r[0].equals(name)) continue;
            System.out.println("task " + name);
            System.out.println();
            System.out.println("  runs:  " + (r.length > 1 ? r[1] : ""));
            System.out.println("  run:   ./flixw task " + name + " [args...]");
            return;
        }
        System.err.println("flixw: no task " + q(name) + " in .flixw/tasks.toml");
        System.err.println("       run: ./flixw task   (to list them)");
        throw new Exit(89);
    }

    // ---- the completers ------------------------------------------------------

    /**
     * One completer per shell, all from the one {@link #tree} the help screen renders.
     *
     * <p>The script is a snapshot of the pinned compiler and must be regenerated after a
     * re-pin. An earlier design emitted a static script that read its candidates at TAB time
     * from a note, so it never went stale -- but a note holds bare verb names, which is why
     * it could carry neither a description nor an option. This trades staleness for the
     * thing a completion is actually for.
     *
     * <p><b>gencomp's shape, not gencomp's parse.</b> Deriving completions by scanning a
     * program's {@code --help} is what {@code gencomp} does, and its output shape is right --
     * {@code __fish_use_subcommand} for the verb slot, {@code -f} to keep filenames out of
     * it. Its parse is where it breaks on Flix: a generic {@code commands?} section detector
     * matches the line {@code Command: init} itself and skips it, so every entry it emits is
     * the first word of the description below -- {@code creates}, {@code checks},
     * {@code builds} five times, and not one real verb. Measured against a real 0.75.3, not
     * assumed. Working from a model rather than from prose is what avoids that whole class
     * of error, which is the argument for the {@code CommandSpec} in the first place.
     */
    static void completion(Ctx c, String shell) {
        CommandSpec spec = tree(c, "flixw");
        switch (shell) {
            // picocli's own generator, from the same tree `help` renders. One script serves
            // both shells. Writing a second bash generator beside a maintained one would be
            // inventing work and the two would drift.
            case "bash", "zsh" -> System.out.print(AutoComplete.bash("flixw", new CommandLine(spec)));
            // picocli generates neither of these, so they are flixw code walking the same
            // model rather than a second parse of anything. That is the whole reason the tree
            // exists as a value: four shells, one description of what the commands are.
            case "fish" -> fish(spec);
            case "pwsh" -> pwsh(spec);
            default -> {
                System.err.println("flixw: unknown shell " + q(shell));
                throw new Exit(89);
            }
        }
    }

    /**
     * fish, walking the command tree.
     *
     * <p>fish matches on the command's <em>base name</em>, so one registration covers
     * {@code flixw}, {@code ./flixw} and an absolute path alike -- bash matches the word as
     * typed and needs both spellings, which is why only this one gets away with a single
     * {@code -c}. Value-taking options are marked {@code -r} so fish stops offering verbs
     * where an argument belongs.
     */
    static void fish(CommandSpec spec) {
        System.out.println("# flixw TAB completion for fish, generated from this project's"
                         + " pinned compiler.");
        System.out.println("# Regenerate after a re-pin:  ./flixw completion fish");
        System.out.println();
        for (Map.Entry<String, CommandLine> e : spec.subcommands().entrySet())
            System.out.println("complete -f -c flixw -n __fish_use_subcommand -a "
                             + fq(e.getKey()) + " -d " + fq(describe(e.getValue().getCommandSpec())));
        System.out.println();
        for (OptionSpec o : spec.options()) System.out.println(fishOption(o, null));
        // A subcommand's own arguments, scoped to it. Without this the tree is walked one
        // level deep and `./flixw wrapper <TAB>` offers nothing at all -- the word completes
        // and then stops, which reads as "this takes no arguments" rather than "the
        // generator did not look".
        for (Map.Entry<String, CommandLine> e : spec.subcommands().entrySet()) {
            CommandSpec child = e.getValue().getCommandSpec();
            String seen = "__fish_seen_subcommand_from " + e.getKey();
            for (OptionSpec o : child.options()) System.out.println(fishOption(o, seen));
            for (PositionalParamSpec pp : child.positionalParameters()) {
                if (pp.completionCandidates() == null) continue;
                StringBuilder cand = new StringBuilder();
                for (String v : pp.completionCandidates()) {
                    if (cand.length() > 0) cand.append(' ');
                    cand.append(v);
                }
                if (cand.length() > 0)
                    System.out.println("complete -f -c flixw -n " + fq(seen) + " -a "
                                     + fq(cand.toString()) + " -d " + fq(describe(pp)));
            }
        }
    }

    /** One fish `complete` line for an option, optionally scoped to a subcommand. */
    static String fishOption(OptionSpec o, String seen) {
        StringBuilder b = new StringBuilder("complete -c flixw");
        if (seen != null) b.append(" -n ").append(fq(seen));
        for (String n : o.names()) {
            if (n.startsWith("--")) b.append(" -l ").append(fq(n.substring(2)));
            else if (n.length() == 2) b.append(" -s ").append(fq(n.substring(1)));
        }
        if (o.arity().max() > 0) b.append(" -r");
        return b.append(" -d ").append(fq(describe(o))).toString();
    }

    /**
     * PowerShell, walking the same tree.
     *
     * <p>Registered against {@code flixw.cmd}, which is the trampoline a Windows shell
     * actually invokes; there is no {@code .ps1} to attach to, and there deliberately is not
     * one -- a {@code .ps1} cannot be run as a bare command from {@code cmd.exe} or a build
     * tool, and an execution policy can make it administratively unrunnable.
     */
    static void pwsh(CommandSpec spec) {
        System.out.println("# flixw TAB completion for PowerShell, generated from this"
                         + " project's pinned compiler.");
        System.out.println("# Regenerate after a re-pin:  ./flixw completion pwsh");
        System.out.println();
        StringBuilder words = new StringBuilder();
        for (String k : spec.subcommands().keySet()) {
            if (words.length() > 0) words.append(',');
            words.append('\'').append(k).append('\'');
        }
        for (OptionSpec o : spec.options())
            for (String n : o.names()) words.append(",'").append(n).append('\'');
        System.out.println("Register-ArgumentCompleter -Native -CommandName flixw,flixw.cmd"
                         + " -ScriptBlock {");
        System.out.println("    param($wordToComplete, $commandAst, $cursorPosition)");
        System.out.println("    @(" + words + ") |");
        System.out.println("        Where-Object { $_ -like \"$wordToComplete*\" } |");
        System.out.println("        ForEach-Object { [System.Management.Automation"
                         + ".CompletionResult]::new($_, $_, 'ParameterValue', $_) }");
        System.out.println("}");
    }

    /** A one-line description, or empty; picocli models it as an array of lines. */
    static String describe(CommandSpec s) {
        String[] d = s.usageMessage().description();
        return d == null || d.length == 0 ? "" : collapse(d[0]);
    }

    static String describe(OptionSpec o) {
        String[] d = o.description();
        return d == null || d.length == 0 ? "" : collapse(d[0]);
    }

    static String describe(PositionalParamSpec p) {
        String[] d = p.description();
        return d == null || d.length == 0 ? "" : collapse(d[0]);
    }

    /**
     * A fish single-quoted literal.
     *
     * <p>Inside single quotes fish expands nothing, so only the quote and the backslash need
     * escaping -- but they do need it: a description carrying an apostrophe would otherwise
     * end the literal and leave the rest of the sentence to be executed as fish source. Flix
     * ships one already ({@code "that dependencies respect the 'effects.lock' file."}), so
     * this is a live case rather than a hypothetical one.
     */
    static String fq(String s) {
        return "'" + (s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }

    static String readOrEmpty(String path) {
        try { return Files.readString(Paths.get(path), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    static String q(String s) { return "'" + s + "'"; }
}
