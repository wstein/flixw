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
import java.util.Optional;
import java.util.Set;
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
import picocli.spec.CommandSpecDsl;

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
        List<String> jvmOpts = args.length > 3 ? List.of(args).subList(3, args.length) : List.of();

        if (topic == null) { overview(c); return; }
        switch (topic) {
            case "flix" -> flix(c, name, jvmOpts, false);
            // Internal spelling for ./flixw <verb> --help. Unknown upstream releases keep
            // their original flat screen instead of receiving a curation table not yet
            // verified against their source.
            case "flix-direct" -> flix(c, name, jvmOpts, true);
            case "wrapper" -> render(wrapperSpec(c));
            case "pin" -> render(pinSpec(c));
            case "info" -> render(infoSpec(c));
            case "doctor" -> render(doctorSpec(c));
            case "validate" -> render(validateSpec(c));
            case "plugin" -> plugin(c, name);
            case "task" -> task(c, name);
            case "completion" -> {
                if (name == null || name.equals("--help") || name.equals("-h")) render(completionSpec(c));
                else completion(c, name);
            }
            case "examples" -> {
                CommandSpec spec = examplesSpec(c);
                if (name != null && !name.equals("--help") && !name.equals("-h")) {
                    renderSub(spec, name, "examples", "help examples");
                    return;
                }
                render(spec);
            }
            case "local" -> {
                CommandSpec spec = localSpec(c);
                if (name != null && !name.equals("--help") && !name.equals("-h")) {
                    renderSub(spec, name, "local", "help local");
                    return;
                }
                render(spec);
            }
            case "examples-local" -> {
                CommandSpec spec = localSpec("./flixw examples local", false);
                if (name != null && !name.equals("--help") && !name.equals("-h")) {
                    renderSub(spec, name, "examples local", "help examples");
                    return;
                }
                render(spec);
            }
            default -> {
                if (c.words("compilerVerbs").contains(topic) || c.words("fallbackVerbs").contains(topic)) {
                    flix(c, topic, jvmOpts, true);
                    return;
                }
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
                    System.err.println("       topics: flix wrapper plugin task completion examples local");
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

    /**
     * Respects NO_COLOR (https://no-color.org) when present in the environment; otherwise
     * defers to picocli's tty-detection via Ansi.AUTO.
     */
    static Ansi ansi() {
        return ansi(System.getenv("NO_COLOR"));
    }

    static Ansi ansi(String noColor) {
        return noColor != null ? Ansi.OFF : Ansi.AUTO;
    }

    /** One renderer for every topic, so the topics cannot drift apart in appearance. */
    static void render(CommandSpec spec) {
        new CommandLine(spec).setColorScheme(CommandLine.Help.defaultColorScheme(ansi()))
                             .usage(System.out);
    }

    /**
     * Renders a subcommand's usage screen from its parent spec, or reports an unknown
     * subcommand diagnostic if not declared.
     */
    static void renderSub(CommandSpec parent, String name, String label, String repair) {
        String subName = name.startsWith("--help=") ? name.substring("--help=".length()) : name;
        CommandLine cmd = new CommandLine(parent)
            .setColorScheme(CommandLine.Help.defaultColorScheme(ansi()));
        CommandLine child = cmd.getSubcommands().get(subName);
        if (child != null) {
            child.setColorScheme(CommandLine.Help.defaultColorScheme(ansi())).usage(System.out);
            return;
        }
        System.err.println("flixw: no " + label + " subcommand " + q(subName));
        System.err.println("       run: ./flixw " + repair);
        throw new Exit(89);
    }

    /**
     * Renders the overview with its command list grouped by who answers each word.
     *
     * <p>Only the overview: every other topic has a single provider, so a heading there would
     * label a group of one and a colour would style the whole screen.
     */
    static void renderGrouped(CommandSpec spec, Ctx c) {
        CommandLine cl = new CommandLine(spec)
            .setColorScheme(CommandLine.Help.defaultColorScheme(ansi()));
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
                    .description("move this project to the newest published flixw, or to"
                                + " --upgrade <version>; --pre-release for one not yet promoted;"
                                + " --global refreshes the machine-wide cache instead of a project").build());
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
        System.out.println("  ./flixw help local [<subcommand>] local dependency overrides");
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

        for (String v : compilerVerbs) {
            // A curated spec gives full option/positional fidelity for this one verb; a bare
            // description string (or none at all) falls back to the regex-derived screen this
            // project already had, so an uncurated version or a fork loses nothing.
            CommandSpec fromSpec = specVerb(c, v);
            if (fromSpec != null) root.addSubcommand(v, new CommandLine(fromSpec));
            else sub(root, v, desc.getOrDefault(v, ""));
        }
        for (String v : c.words("wrapperVerbs")) {
            if (compilerVerbs.contains(v)) continue;
            if (v.equals("examples")) {
                CommandSpec ex = examplesSpec(c);
                ex.usageMessage().description("(wrapper) " + wrapperDesc(v));
                root.addSubcommand("examples", new CommandLine(ex));
            } else if (v.equals("local")) {
                CommandSpec loc = localSpec(c);
                loc.usageMessage().description("(wrapper) " + wrapperDesc(v));
                root.addSubcommand("local", new CommandLine(loc));
            } else {
                sub(root, v, "(wrapper) " + wrapperDesc(v));
            }
        }
        // Plugins from the lock: registered under their declared bare verb (if any) and
        // under the canonical `plugin <name>` namespace, so TAB-completion offers them.
        for (String[] r : c.rows("plugins")) {
            String bareVerb = r.length > 5 && !r[5].isEmpty() ? r[5] : null;
            String descText = r.length > 4 ? r[4] : "";
            if (bareVerb != null && !root.subcommands().containsKey(bareVerb))
                sub(root, bareVerb, descText);
            CommandLine pluginCmd = root.subcommands().get("plugin");
            if (pluginCmd != null && !pluginCmd.getSubcommands().containsKey(r[0]))
                sub(pluginCmd.getCommandSpec(), r[0], descText);
        }
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
        Optional<CommandSpec> curatedRoot = "true".equals(c.get("upstream"))
            ? loadSpec(c.get("compilerVersion")) : Optional.empty();
        if (curatedRoot.isPresent()) {
            for (OptionSpec opt : curatedRoot.get().options())
                try { root.addOption(opt); } catch (RuntimeException ignored) { }
        } else if (!c.get("helpFile").isEmpty()) {
            addOptions(root, readOrEmpty(c.get("helpFile")));
        }
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
            case "examples" -> "run, check, build or test an examples/<name> package against this project's compiler.";
            case "local" -> "override a declared GitHub dependency with an uncommitted local checkout.";
            case "wrapper" -> "--version, --upgrade, --install-jdk, --purge, --schema.";
            case "completion" -> "a TAB-completion script for bash, zsh, fish or pwsh.";
            default -> "";
        };
    }

    static CommandSpec wrapperSpec(Ctx c) {
        CommandSpec s = base("./flixw wrapper",
            "flixw " + c.get("flixwVersion") + " -- the wrapper's own commands.",
            "",
            "Manage the flixw wrapper installation, version, and cache.");
        s.usageMessage().customSynopsis("./flixw wrapper <flags>");
        wrapperOptions(s);
        s.usageMessage().footer("FLIX_JAR=<path> runs a local compiler build, unverified (see docs/CONTRACT.md).");
        return s;
    }

    static CommandSpec pinSpec(Ctx c) {
        CommandSpec s = base("./flixw pin",
            "Write .flixw/lock.toml: repository, compiler version and digest.",
            "",
            "Pins an unmodified stock flix.jar, fork, or local compiler build.");
        s.usageMessage().customSynopsis(
            "./flixw pin [<owner>/<repo>] [<version>] [--java <version>] [--editor-jar=copy|off]",
            "          or: ./flixw pin --local <path/to/flix.jar-or-checkout> | --stock",
            "          or: ./flixw pin <owner>/<repo>@<version>   (one token, a fork)",
            "          or: ./flixw pin --refresh   (rewrite the lock in this release's shape)");
        s.addPositional(PositionalParamSpec.builder()
            .paramLabel("[<owner>/<repo>]")
            .arity("0..1")
            .description("repository (default: flix/flix)").build());
        s.addPositional(PositionalParamSpec.builder()
            .paramLabel("[<version>]")
            .arity("0..1")
            .description("compiler version, e.g. 0.76.1 or owner/repo@version").build());
        s.addOption(OptionSpec.builder("--java")
            .paramLabel("<version>")
            .description("minimum Java version required for this project").build());
        s.addOption(OptionSpec.builder("--editor-jar")
            .paramLabel("<policy>")
            .description("editor integration: copy compiler jar or off (copy|off)").build());
        s.addOption(OptionSpec.builder("--local")
            .paramLabel("<path>")
            .description("point at a local compiler jar or build checkout").build());
        s.addOption(OptionSpec.builder("--stock")
            .description("revert --local to stock pinned compiler").build());
        s.addOption(OptionSpec.builder("--refresh")
            .description("rewrite .flixw/lock.toml in this wrapper's shape").build());
        return s;
    }

    static CommandSpec infoSpec(Ctx c) {
        CommandSpec s = base("./flixw info",
            "Report project, compiler, java and cache state.");
        s.usageMessage().customSynopsis("./flixw info [--verbose | -v]");
        s.addOption(OptionSpec.builder("-v", "--verbose")
            .description("list cached compilers, JDKs and assets").build());
        return s;
    }

    static CommandSpec doctorSpec(Ctx c) {
        CommandSpec s = base("./flixw doctor",
            "Inspect wrapper health and project setup with diagnostic verdicts.",
            "",
            "Runs all validation checks, reporting PASS/WARN/FAIL for each.");
        s.usageMessage().customSynopsis("./flixw doctor [--fix]");
        s.addOption(OptionSpec.builder("--fix")
            .description("automatically repair fixable issues (e.g. .gitattributes, refresh lock)").build());
        return s;
    }

    static CommandSpec validateSpec(Ctx c) {
        CommandSpec s = base("./flixw validate",
            "Run wrapper validation checks alone, for CI.");
        s.usageMessage().customSynopsis("./flixw validate");
        return s;
    }

    static CommandSpec completionSpec(Ctx c) {
        CommandSpec s = base("./flixw completion",
            "Emit TAB-completion script for bash, zsh, fish or pwsh.");
        s.usageMessage().customSynopsis("./flixw completion <bash|zsh|fish|pwsh>");
        s.addPositional(PositionalParamSpec.builder()
            .paramLabel("<shell>")
            .arity("1")
            .completionCandidates(List.of("bash", "zsh", "fish", "pwsh"))
            .description("shell to emit completion script for (bash, zsh, fish, pwsh)").build());
        return s;
    }

    /** The public command model for examples; {@code c} is currently unused and may be null in tests. */
    static CommandSpec examplesSpec(Ctx c) {
        CommandSpec s = base("./flixw examples",
            "Runs an examples/<name>/ package with this project's pinned compiler.");
        sub(s, "list", "lists discoverable examples.");
        for (String verb : List.of("run", "check", "build", "build-classes", "build-jar", "build-fatjar",
                                   "build-pkg", "clean", "doc", "format", "outdated", "eff-check", "eff-lock",
                                   "test")) {
            CommandSpec child = sub(s, verb, "runs Flix " + verb + " in an example package.");
            child.addPositional(PositionalParamSpec.builder().paramLabel("<name>")
                .description("the example directory name").build());
            child.usageMessage().customSynopsis("./flixw examples " + verb + " [flags] <name> [-- args]");
        }
        CommandSpec local = sub(s, "local", "runs an example against this project's local source.");
        local.usageMessage().customSynopsis("./flixw examples local <verb> <name> [-- args]");
        s.usageMessage().footer("Examples are separate packages under examples/, each with its own flix.toml.");
        return s;
    }

    /** The public command model for local overrides; {@code c} is currently unused and may be null in tests. */
    static CommandSpec localSpec(Ctx c) {
        return localSpec("./flixw local", true);
    }

    static CommandSpec localSpec(String name, boolean bookkeeping) {
        CommandSpec root = base(name, bookkeeping
            ? "Overrides a declared GitHub dependency with an uncommitted local checkout."
            : "Runs an example against this project's uncommitted local source.");
        if (bookkeeping) {
            CommandSpec add = sub(root, "add", "adds an override for a declared GitHub dependency.");
            add.addPositional(PositionalParamSpec.builder().paramLabel("<path>")
                .description("the local package checkout").build());
            add.usageMessage().footer("e.g. ../pkg; must be declared in flix.toml.");
            sub(root, "list", "lists active overrides.");
            CommandSpec remove = sub(root, "remove", "removes an active override.");
            remove.addPositional(PositionalParamSpec.builder().paramLabel("<coordinate>")
                .description("a declared github:<owner>/<repo> dependency").build());
            sub(root, "status", "reports whether active overrides still match their manifests.");
        }
        for (String verb : List.of("run", "check", "build", "build-jar", "build-fatjar", "build-pkg", "test", "doc")) {
            CommandSpec child = sub(root, verb, "runs Flix " + verb + " in a disposable overlay.");
            child.usageMessage().customSynopsis(name + " " + verb + " [-- args]");
        }
        root.usageMessage().footer(bookkeeping
            ? "State: .flixw/local/packages.toml is machine-local and gitignored.\n"
            + "Arguments after -- are forwarded unchanged to the compiler in a disposable overlay."
            : "Arguments after -- are forwarded unchanged to the compiler in a disposable overlay.");
        return root;
    }

    // ---- flix ----------------------------------------------------------------

    static void flix(Ctx c, String name, List<String> jvmOpts, boolean direct)
            throws IOException, InterruptedException {
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
        Probe probed = probe(c, name, jvmOpts);
        if (probed != null && probed.status == 0 && !probed.text.strip().equals(help.strip())) {
            System.out.println("Flix " + version + "  --  " + name);
            System.out.println();
            System.out.print(probed.text.endsWith("\n") ? probed.text : probed.text + "\n");
            return;
        }
        // A fork may deliberately reject its per-command help. Returning its status lets the
        // direct route fall through to the original compiler argv, preserving both output and
        // exit code instead of replacing a real failure with a successful screen.
        if (probed != null && probed.status != 0) throw new Exit(probed.status);

        Map<String, String> known = commands(help);
        if (!known.containsKey(name)) {
            System.err.println("flixw: Flix " + version + " lists no command " + q(name));
            System.err.println(known.isEmpty()
                ? "       flixw does not recognise this compiler's help layout;"
                + "\n       run: ./flixw help flix   (to see it unedited)"
                : "       known commands: " + String.join(" ", known.keySet()));
            throw new Exit(89);
        }

        // Layout alone proves neither provenance nor flag meaning; upstream protects forks,
        // and layout protects a future upstream parser change. Curation only omits options.
        // A curated spec is the same guarantee at higher fidelity, so it counts as curated too.
        CommandSpec fromSpec = specVerb(c, name);
        boolean curated = fromSpec != null
                       || (format(help).equals("scopt-v1") && "true".equals(c.get("upstream"))
                       && CURATED_UPSTREAM_VERSIONS.contains(version));
        if (direct && !curated) {
            System.out.print(help.endsWith("\n") ? help : help + "\n");
            return;
        }
        if (fromSpec != null) { render(fromSpec); return; }
        CommandSpec s = base("./flixw " + name,
            known.get(name).isEmpty() ? "(the compiler's help gives no description)"
                                      : known.get(name));
        addOptions(s, help, curated ? name : null, version);
        render(s);
    }

    /**
     * Flags read only through the compile-options bag ({@code Flix().setOptions(...)}),
     * which {@code init} and {@code clean} never construct at all. Before 0.76.1,
     * {@code build-pkg} did not either; 0.76.1 now checks a configured compiler before it
     * packages. Traced directly against flix/flix's {@code Main.scala}, not inferred from
     * {@code --help} text, which draws no distinction between them whatsoever. Every one of
     * these is grammatically global in the compiler's own scopt parser -- none of this is a
     * real per-command partition Flix defines -- so a verb loses one only when the source
     * proves it is never read there. The experimental {@code -X} flags that feed the same
     * {@code Options(...)} constructor belong here too -- {@code --Xlib}, {@code
     * --Xno-deprecated}, {@code --Xprint-phases}, {@code --Xsummary}, {@code
     * --Xsubeffecting}, {@code --Xnewmono}, {@code --Xverify} -- distinct from the {@code --Xbenchmark-*}
     * flags below, which are not.
     */
    static final Set<String> COMPILE_OPTIONS = Set.of(
        "--entrypoint", "--threads", "--top", "--Xlib", "--Xno-deprecated",
        "--Xprint-phases", "--Xsummary", "--Xsubeffecting", "--Xnewmono", "--Xverify");

    /** Flags read only when a command resolves dependencies via {@code Bootstrap.bootstrap},
     *  which {@code init} alone never calls. */
    static final Set<String> BOOTSTRAP_OPTIONS = Set.of("--github-token", "--no-install");

    /** {@code clean} resolves dependencies but never constructs a compiler instance. */
    static final Set<String> NON_COMPILING = Set.of("clean");

    /** Before 0.76.1, {@code build-pkg} never constructed a compiler instance either. */
    static final Set<String> PRE_0761_NON_COMPILING = Set.of("clean", "build-pkg");

    /** {@code --yes} answers a confirmation prompt {@code Bootstrap.release} alone asks. */
    static final String CONFIRMATION_VERB = "release";

    /** Read only with no command at all ({@code Command.None}) -- each one is checked in
     *  that branch specifically and nowhere a named verb's own handler runs, so none of
     *  them is ever applicable to any named verb, on any per-command screen. */
    static final Set<String> NO_COMMAND_OPTIONS = Set.of("--listen",
        "--Xbenchmark-code-size", "--Xbenchmark-incremental", "--Xbenchmark-phases",
        "--Xbenchmark-frontend", "--Xbenchmark-throughput");

    static boolean appliesToVerb(String flag, String verb, String version) {
        if (NO_COMMAND_OPTIONS.contains(flag)) return false;
        if (flag.equals("--yes")) return verb.equals(CONFIRMATION_VERB);
        if (verb.equals("init"))
            return !COMPILE_OPTIONS.contains(flag) && !BOOTSTRAP_OPTIONS.contains(flag);
        Set<String> nonCompiling = version.equals("0.76.1")
            ? NON_COMPILING : PRE_0761_NON_COMPILING;
        if (nonCompiling.contains(verb)) return !COMPILE_OPTIONS.contains(flag);
        return true;
    }

    /** Versions whose upstream Main.scala source the table above has been re-traced against. */
    static final Set<String> CURATED_UPSTREAM_VERSIONS = Set.of("0.75.3", "0.76.0", "0.76.1");

    /**
     * The curated spec for one compiler version, or empty when none has been authored --
     * the graceful-fallback half of the spec-driven path: an uncurated or custom build keeps
     * getting {@link #appliesToVerb}'s regex-derived screen exactly as before. A spec that
     * fails to parse is treated the same as a missing one rather than thrown, since a syntax
     * issue in a spec file must not turn every {@code help}/{@code completion} invocation into a
     * hard failure.
     *
     * <p>Loads on-demand: first from classpath resource {@code /specs/flix-<version>.picocli}
     * (bundled inside {@code picocli.jar}), falling back to {@code src/assets/picocli/flix-<version>.picocli}
     * if present on disk.
     */
    static Optional<CommandSpec> loadSpec(String version) {
        if (version == null || version.isEmpty()) return Optional.empty();
        String name = "flix-" + version + ".picocli";
        // 1. Check classpath resource (bundled in picocli.jar under /specs/)
        try (InputStream in = flixwhelp.class.getResourceAsStream("/specs/" + name)) {
            if (in != null) {
                String dsl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return Optional.of(CommandSpecDsl.parse(dsl));
            }
        } catch (Exception ignored) { }

        // 2. Check local workspace asset file if running from source checkout
        try {
            String prop = System.getProperty("flixw.specs.dir");
            if (prop != null && !prop.isBlank()) {
                Path disk = Paths.get(prop, name);
                if (Files.isRegularFile(disk)) {
                    String dsl = Files.readString(disk, StandardCharsets.UTF_8);
                    return Optional.of(CommandSpecDsl.parse(dsl));
                }
            }
            Path cur = Paths.get("").toAbsolutePath();
            for (int i = 0; i < 8 && cur != null; i++) {
                Path disk = cur.resolve("src/assets/picocli").resolve(name);
                if (Files.isRegularFile(disk)) {
                    String dsl = Files.readString(disk, StandardCharsets.UTF_8);
                    return Optional.of(CommandSpecDsl.parse(dsl));
                }
                cur = cur.getParent();
            }
        } catch (Exception ignored) { }

        return Optional.empty();
    }

    /**
     * The spec-derived model for one compiler verb, gated the same way {@link
     * #appliesToVerb}'s table is: only when this run has already established the pinned
     * compiler is genuinely upstream at that exact version, never for {@code FLIX_JAR}, a
     * fork, or a selected local compiler, none of which this spec was authored against.
     */
    static CommandSpec specVerb(Ctx c, String name) {
        if (!"true".equals(c.get("upstream"))) return null;
        return loadSpec(c.get("compilerVersion"))
            .map(s -> s.subcommands().get(name))
            .map(CommandLine::getCommandSpec)
            .orElse(null);
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
        addOptions(s, help, null, "");
    }

    /** {@code verb} narrows to {@link #appliesToVerb}; null keeps every captured option,
     *  which is what the root tree (shared by completion and the top-level screen) needs --
     *  a completer must offer everything a bare {@code ./flixw <verb>} accepts, not one
     *  command's curated subset. */
    static void addOptions(CommandSpec s, String help, String verb, String version) {
        for (Map.Entry<String, String[]> e : options(help).entrySet()) {
            String[] o = e.getValue();
            if (verb != null && !appliesToVerb(e.getKey(), verb, version)) continue;
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
    record Probe(int status, String text) { }

    static Probe probe(Ctx c, String name, List<String> jvmOpts)
            throws IOException, InterruptedException {
        String javaExe = c.get("javaExe"), jar = c.get("compilerJar");
        if (javaExe.isEmpty() || jar.isEmpty()) return null;
        List<String> cmd = new ArrayList<>(List.of(javaExe));
        cmd.addAll(jvmOpts);
        cmd.addAll(List.of("-jar", jar, name, "--help"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
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
                // Reaching the cap ends the child now, the same as runCapture's own reader:
                // closing the pipe alone does not, since a writer that ignores the error
                // keeps going and still costs the whole PROBE_SECONDS timeout below, for
                // output already being discarded.
                if (b.length() >= PROBE_CAP) { p.destroy(); p.destroyForcibly(); }
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
        // flix() compares this against the cached --help byte-for-byte to tell "no
        // per-command help, same top-level text repeated" from a real answer. That cache
        // went through captureHelp's own \r\n normalization (a Windows JVM's own
        // System.lineSeparator()); without the same normalization here the two would
        // differ on Windows even when the compiler said the same thing twice.
        return new Probe(p.exitValue(), b.toString().replace("\r\n", "\n").replace('\r', '\n'));
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
        if (name == null || name.equals("--help") || name.equals("-h")) {
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
        if (name == null || name.equals("--help") || name.equals("-h")) {
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
            // Native Fish generation, from the same tree help renders. PowerShell has no
            // picocli generator, so it remains the one model-walking implementation.
            case "fish" -> System.out.print(AutoComplete.fish("flixw", new CommandLine(spec)));
            case "pwsh" -> pwsh(spec);
            default -> {
                System.err.println("flixw: unknown shell " + q(shell));
                throw new Exit(89);
            }
        }
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
        for (CommandLine sub : spec.subcommands().values()) {
            for (String subName : sub.getSubcommands().keySet())
                words.append(",'").append(subName).append('\'');
            for (OptionSpec subOpt : sub.getCommandSpec().options())
                for (String n : subOpt.names()) words.append(",'").append(n).append('\'');
        }
        System.out.println("Register-ArgumentCompleter -Native -CommandName flixw,flixw.cmd"
                         + " -ScriptBlock {");
        System.out.println("    param($wordToComplete, $commandAst, $cursorPosition)");
        System.out.println("    @(" + words + ") |");
        System.out.println("        Where-Object { $_ -like \"$wordToComplete*\" } |");
        System.out.println("        ForEach-Object { [System.Management.Automation"
                         + ".CompletionResult]::new($_, $_, 'ParameterValue', $_) }");
        System.out.println("}");
    }

    static String readOrEmpty(String path) {
        try { return Files.readString(Paths.get(path), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    static String q(String s) { return "'" + s + "'"; }
}
