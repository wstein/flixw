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
import picocli.CommandLine.Model.ArgGroupSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.HelpSectionSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Model.UsageMessageSpec;
import cliwerk.spec.CommandSpecDsl;

/**
 * Renders {@code ./flixw help} and generates {@code ./flixw completion <shell>}, from one
 * shared command-tree model: a wrapper-owned companion asset, not a plugin.
 *
 * <p>{@code java -cp picocli.jar flixw-cli.java <context-file> <topic> <name> <help-flag> [<jvm-token>...]}
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
final class flixwcli {
    private flixwcli() {}

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

    /**
     * The version of the context file this renderer reads; stage 0 writes {@code contract=2}
     * as its first line. Absent means contract 0, the unversioned layout: comma-separated
     * {@code fallbackVerbs} and unescaped values. A higher number is refused (see {@link #body}).
     * Adding a key that older readers can ignore does not move it; changing what an existing
     * key means does.
     */
    static final int CONTRACT = 2;

    private static void body(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java -cp picocli.jar flixw-cli.java"
                             + " <context-file> <topic> <name> <help-flag> [<jvm-token>...]");
            throw new Exit(87);
        }
        Ctx c = Ctx.read(Paths.get(args[0]));
        if (c.contract < 0 || c.contract > CONTRACT) {
            // A newer stage 0 wrote this, in a shape this renderer cannot read. Stage 0 takes the
            // non-zero status as "no rendering" and prints its own resident help instead.
            System.err.println("flixw: stage 0 sent context contract " + c.get("contract")
                + "; this renderer reads contract " + CONTRACT + " and, for contract 0 (no"
                + " field), the layout before versioning");
            System.err.println("       stage 0 and its companion assets are from different flixw"
                + " releases; run: ./flixw wrapper --upgrade");
            throw new Exit(87);
        }
        String topic = args.length > 1 ? args[1] : null;
        String name = args.length > 2 ? args[2] : null;
        List<String> jvmOpts = args.length > 3 ? List.of(args).subList(3, args.length) : List.of();

        String helpFlag = null;
        if (c.contract >= 2) {
            // Fixed fields precede opaque, already validated JVM tokens. No token heuristics.
            topic = topic == null || topic.isEmpty() ? null : topic;
            name = name == null || name.isEmpty() ? null : name;
            helpFlag = args.length > 3 && !args[3].isEmpty() ? args[3] : null;
            jvmOpts = args.length > 4 ? List.of(args).subList(4, args.length) : List.of();
        } else {
            if (isHelpFlag(topic) && !"--help".equals(topic) && !"-h".equals(topic)) {
                helpFlag = topic;
                topic = null;
            } else if (isHelpFlag(name) && !"--help".equals(name) && !"-h".equals(name)) {
                helpFlag = name;
                name = null;
            } else if (!jvmOpts.isEmpty() && isHelpFlag(jvmOpts.get(0))) {
                // compilerVerbHelp sends the literal flag the user typed (--help/-h) as this
                // trailing positional, on top of the topic and name it already set -- so it is
                // not a real JVM option and must not reach probe()'s subprocess. A literal
                // --help/-h is dropped outright: name is already set, so it is redundant. Only
                // an --Xhelp-style flag becomes helpFlag, the same as the topic/name cases above.
                if (!"--help".equals(jvmOpts.get(0)) && !"-h".equals(jvmOpts.get(0)))
                    helpFlag = jvmOpts.get(0);
                jvmOpts = jvmOpts.subList(1, jvmOpts.size());
            }
        }

        if (topic == null) { overview(c, helpFlag); return; }
        switch (topic) {
            case "flix", "flix-direct" -> flix(c, name, jvmOpts, helpFlag);
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
                    flix(c, topic, jvmOpts, helpFlag);
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
        /** The {@code contract} field: 0 when absent, -1 when it is not a number. */
        int contract;

        static Ctx read(Path p) throws IOException {
            Ctx c = new Ctx();
            String section = null;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // A row has a tab; a task such as "echo Next steps:" ends in a colon too.
                if (line.endsWith(":") && !line.contains("=") && !line.contains("\t")) {
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
            String v = c.kv.get("contract");
            try { c.contract = v == null ? 0 : Integer.parseInt(v); }
            catch (NumberFormatException e) { c.contract = -1; }
            // Contract 0 wrote values raw; from 1 on they are escaped like the rows.
            if (c.contract >= 1) c.kv.replaceAll((k, val) -> unesc(val));
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
            // Contract 0 wrote fallbackVerbs comma-separated, so it is read either way.
            return v.isBlank() ? List.of() : List.of(v.trim().split(contract == 0 ? "[ ,]+" : " +"));
        }

        /**
         * Word to owner ({@code compiler}, {@code wrapper} or {@code plugin}), in the order stage 0
         * lists them. Read from stage 0's {@code owners} key, which is its own routing decision
         * and so includes the early-dispatch and forced-backend exceptions; this class decides
         * nothing about who answers a word.
         *
         * <p>A stage 0 that predates the key sends only the verb lists, and the same map is then
         * derived from them as it was before the key existed: the compiler first, then the
         * wrapper's verbs, {@code wrapper} and {@code completion}, then a declared plugin verb
         * nothing else claimed.
         */
        Map<String, String> owners() {
            Map<String, String> m = new LinkedHashMap<>();
            if (kv.containsKey("owners")) {
                for (String t : words("owners")) {
                    int eq = t.lastIndexOf('=');
                    if (eq > 0) m.put(t.substring(0, eq), t.substring(eq + 1));
                }
                return m;
            }
            List<String> cv = words("compilerVerbs");
            for (String v : cv) m.put(v, "compiler");
            for (String v : words("wrapperVerbs")) m.putIfAbsent(v, "wrapper");
            m.put("wrapper", "wrapper");
            m.putIfAbsent("completion", "wrapper");
            for (String[] r : rows("plugins"))
                if (r.length > 5 && !r[5].isEmpty()) m.putIfAbsent(r[5], "plugin");
            return m;
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

    static boolean isHelpFlag(String arg) {
        return arg != null && ("--help".equals(arg) || "-h".equals(arg) || arg.matches("^--[A-Za-z0-9_-]*help$"));
    }

    // ---- rendering ----------------------------------------------------------

    /**
     * Respects NO_COLOR (https://no-color.org) when present in the environment; otherwise
     * {@code System.console() != null}, not {@code Ansi.AUTO}. picocli's own heuristic
     * mistakes Git Bash on Windows for an ANSI terminal -- TERM=xterm is set there even
     * under a redirected, non-interactive CI runner -- which is exactly the failure
     * "help is terminal-escape free" exists to catch, and it is not hypothetical: escape
     * codes reached that test's own output once Ansi.AUTO's guess turned out wrong for a
     * real Windows runner. System.console() is the same redirected-output test stage 0
     * already uses for the editor-jar default and does not share that false positive.
     */
    static Ansi ansi() {
        return ansi(System.getenv("NO_COLOR"));
    }

    static Ansi ansi(String noColor) {
        if (noColor != null) return Ansi.OFF;
        return System.console() != null ? Ansi.ON : Ansi.OFF;
    }

    /**
     * picocli's own option/positional table indents a wrapped continuation line two spaces
     * past its column by default; every render entry point installs 0 here instead, so a
     * long description wraps flush with its own column rather than stair-stepping right on
     * every line it takes.
     */
    static void noWrapIndent(CommandLine cl) {
        cl.getHelpSectionMap().put(UsageMessageSpec.SECTION_KEY_OPTION_LIST, help -> {
            CommandLine.Help.Layout layout = help.createDefaultLayout();
            layout.textTable().indentWrappedLines = 0;
            return help.optionList(layout, help.createDefaultOptionSort(), help.createDefaultParamLabelRenderer());
        });
        cl.getHelpSectionMap().put(UsageMessageSpec.SECTION_KEY_PARAMETER_LIST, help -> {
            CommandLine.Help.Layout layout = help.createDefaultLayout();
            layout.textTable().indentWrappedLines = 0;
            return help.parameterList(layout, help.createDefaultParamLabelRenderer());
        });
    }

    /** One renderer for every topic, so the topics cannot drift apart in appearance. */
    static void render(CommandSpec spec) {
        render(spec, null);
    }

    static void render(CommandSpec spec, String helpFlag) {
        CommandLine cl = new CommandLine(spec)
            .setColorScheme(CommandLine.Help.defaultColorScheme(ansi()));
        noWrapIndent(cl);
        if (helpFlag != null) {
            String section = spec.findHelpSectionForOption(helpFlag).orElse("experimental");
            String exp = new CommandLine.Help(spec, cl.getColorScheme()).renderHelpSection(section);
            if (exp != null && !exp.isBlank()) {
                System.out.print(exp.endsWith("\n") ? exp : exp + "\n");
                return;
            }
            HelpSectionSpec secSpec = spec.helpSectionSpec(section);
            String empty = (secSpec != null && secSpec.emptyMessage() != null && !secSpec.emptyMessage().isEmpty())
                ? secSpec.emptyMessage() : "No experimental options for this command.";
            System.out.println(empty.endsWith("\n") ? empty.stripTrailing() : empty);
            return;
        }
        cl.usage(System.out);
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
            child.setColorScheme(CommandLine.Help.defaultColorScheme(ansi()));
            noWrapIndent(child);
            child.usage(System.out);
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
        noWrapIndent(cl);
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
        Map<String, String> owners = c.owners();
        List<String[]> compiler = new ArrayList<>(), wrapper = new ArrayList<>();
        for (Map.Entry<String, CommandLine> e : help.commandSpec().subcommands().entrySet()) {
            String[] d = e.getValue().getCommandSpec().usageMessage().description();
            // tree() prefixes wrapper descriptions; the heading replaces it.
            String text = d.length == 0 ? "" : d[0].replaceFirst("^\\(wrapper\\) ", "");
            String owner = owners.getOrDefault(e.getKey(), "wrapper");
            if (!"plugin".equals(owner))                       // listed below, by what it runs
                ("compiler".equals(owner) ? compiler : wrapper).add(new String[] { e.getKey(), text });
        }
        List<String[]> plugins = new ArrayList<>(), tasks = new ArrayList<>();
        // What it is for, not which version it is: a version is state and `./flixw info -v`
        // reports it. The text is the plugin's own, read from its jar manifest at install
        // time and recorded in the lock -- so a plugin that declares nothing simply has no
        // description, rather than flixw inventing one or running the plugin to ask.
        // A plugin that declared a verb is listed as that verb, because that is what a
        // reader would type; one that did not keeps the long form, which always works.
        for (String[] r : c.rows("plugins")) {
            String verb = r.length > 5 && "plugin".equals(owners.get(r[5])) ? r[5] : "plugin " + r[0];
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
        overview(c, null);
    }

    static void overview(Ctx c, String helpFlag) {
        CommandSpec t = tree(c, "./flixw");
        if (helpFlag != null) {
            render(t, helpFlag);
            return;
        }
        renderGrouped(t, c);

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
        return tree(c, name, true);
    }

    /**
     * {@link #tree(Ctx, String)}, with or without this project's own task names.
     *
     * <p>A generated completion script is a snapshot that outlives the file it was read from:
     * {@code .flixw/tasks.toml} is hand-edited far more often than the compiler is re-pinned,
     * and a name baked in at generation time would go stale without anything saying so. So the
     * completions are built with {@code projectTasks} false -- the {@code task} node is
     * there, its names are not, and {@code ./flixw task} lists them at the moment of asking.
     * {@code help} reads the file as it is now and passes true.
     */
    static CommandSpec tree(Ctx c, String name, boolean projectTasks) {
        return tree(c, name, projectTasks, false);
    }

    /**
     * {@link #tree(Ctx, String, boolean)} with a failure policy for a curated spec that is
     * present and unusable (malformed, or colliding with what the tree already holds).
     *
     * <p>{@code strict} is for output that outlives the run: a completion script built from half
     * a model is installed into a shell and found out days later, so it throws {@link SpecError}
     * and writes nothing. Help is read once and by a person, so it warns on stderr and renders
     * what it has, which for a verb means the compiler's own help. A spec that is merely
     * <em>absent</em> is neither: that is the ordinary case, and says nothing.
     */
    static CommandSpec tree(Ctx c, String name, boolean projectTasks, boolean strict) {
        List<String> compilerVerbs = c.words("compilerVerbs");
        Map<String, String> owners = c.owners();
        Optional<CommandSpec> curatedRoot = Optional.empty();
        boolean specBroken = false;
        if ("true".equals(c.get("upstream"))) {
            try { curatedRoot = loadSpec(c.get("compilerVersion")); }
            catch (SpecError e) {
                if (strict) throw e;
                warnSpec(e);
                specBroken = true;
            }
        }

        CommandSpec root = base(name,
            "flixw " + c.get("flixwVersion") + " -- repository-local Flix bootstrap.",
            "",
            c.get("compilerVersion").isEmpty()
                ? "No compiler pinned yet. Run: ./flixw pin <version>"
                : "Pinned compiler: Flix " + c.get("compilerVersion") + ".",
            "Dispatch is compiler-first: a word the compiler implements goes to the compiler,"
          + " so the wrapper's own verbs retire by themselves as Flix grows.");

        for (String v : compilerVerbs) {
            if (!"compiler".equals(owners.get(v))) continue;   // stage 0 routes it elsewhere
            // A curated spec gives full option/positional fidelity for this one verb.
            CommandSpec fromSpec = specBroken ? null : specVerb(c, v);
            if (fromSpec != null) root.addSubcommand(v, new CommandLine(fromSpec));
            else sub(root, v, "");
        }
        for (Map.Entry<String, String> e : owners.entrySet()) {
            String v = e.getKey();
            if (!"wrapper".equals(e.getValue())) continue;
            CommandSpec own = v.equals("task") ? taskSpec(c, projectTasks) : wrapperVerbSpec(c, v);
            if (own != null) attachWrapper(root, v, own);
            else sub(root, v, "(wrapper) " + wrapperDesc(v));
        }
        // Plugins from the lock: registered under their declared bare verb when stage 0 routes
        // that verb to the plugin, and always under the canonical `plugin <name>` namespace,
        // so TAB-completion offers them. `wrapper` and `completion` are not in WRAPPER_VERBS
        // (a namespace of flags, and a word answered before that table); stage 0 lists both
        // as wrapper-owned unless the compiler claims `completion`, and each carries its own
        // arguments, because a word with none completes to nothing after it.
        for (String[] r : c.rows("plugins")) {
            String bareVerb = r.length > 5 && !r[5].isEmpty() ? r[5] : null;
            String descText = r.length > 4 ? r[4] : "";
            if (bareVerb != null && "plugin".equals(owners.get(bareVerb))
                && !root.subcommands().containsKey(bareVerb))
                sub(root, bareVerb, descText);
            CommandLine pluginCmd = root.subcommands().get("plugin");
            if (pluginCmd != null && !pluginCmd.getSubcommands().containsKey(r[0]))
                sub(pluginCmd.getCommandSpec(), r[0], descText);
        }
        if (curatedRoot.isPresent()) composeChecked(c, root, curatedRoot.get(), strict);
        if (!root.optionsMap().containsKey("--Xhelp")) {
            root.addOption(OptionSpec.builder("--Xhelp")
                .usageHelp(true)
                .helpSection("experimental")
                .description("shows the experimental options for this command.")
                .build());
        }
        return root;
    }

    /** A curated spec that is present and unusable; see {@link #loadSpec}. */
    static final class SpecError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SpecError(String message, Throwable cause) { super(message, cause); }
    }

    static String specLabel(String version, String file) {
        String f = file.isEmpty() ? specFileForVersion(version) : file;
        return "curated command spec " + f + " (Flix " + version + ")";
    }

    /**
     * The exception's type and the first line of its message. The type is not decoration: for
     * I/O failures the message alone is a bare path ({@code AccessDeniedException}) or a byte
     * count ({@code MalformedInputException}), which says nothing about what went wrong.
     */
    static String reason(Throwable t) {
        String m = t.getMessage();
        String first = m == null || m.isBlank() ? "" : m.strip().lines().findFirst().orElse("");
        String type = t.getClass().getSimpleName();
        return first.isEmpty() ? type : type + ": " + first;
    }

    /**
     * What repairs a broken curated spec. It ships inside the release's {@code flixw-specs.jar},
     * so only a release with a corrected one helps; a damaged cached copy is re-fetched by its
     * digest without anyone asking. {@code FLIX_JAR} sidesteps the spec but is not a repair.
     */
    static final String SPEC_REPAIR = "       run: ./flixw wrapper --upgrade   (once a newer release carries a corrected spec)";

    /** Help's half of the policy: say what is wrong, then carry on without the spec. */
    static void warnSpec(SpecError e) {
        System.err.println("flixw: warning: " + e.getMessage());
        System.err.println("       help is rendered without it, from the compiler's own help;"
                         + " completion refuses to run until it is fixed.");
        System.err.println(SPEC_REPAIR);
    }

    /**
     * {@link #compose} plus the failure policy: a clash fails a strict (completion) build and is
     * a warning otherwise. Split out because no shipped spec clashes with the tree today -- the
     * root holds no options of its own before this runs -- so {@code tree} cannot reach the
     * branch and the unit check drives it here with a root that does.
     */
    static void composeChecked(Ctx c, CommandSpec root, CommandSpec curated, boolean strict) {
        List<String> clashes = compose(root, curated);
        if (clashes.isEmpty()) return;
        SpecError e = new SpecError(specLabel(c.get("compilerVersion"), "")
            + " collides with the command tree: " + String.join("; ", clashes), null);
        if (strict) throw e;
        warnSpec(e);
    }

    /**
     * Copies the curated root's options, groups and help sections onto {@code root}, and returns
     * what could not be copied, one line each; empty means everything arrived.
     *
     * <p>Collision policy, deterministic and not configurable: an option already on {@code root}
     * wins, in the order the spec declares them, and the curated one is reported rather than
     * dropped silently or allowed to replace it. The same option object arriving twice (an
     * option that is both listed and a group member) is not a collision. A group that picocli
     * refuses is reported with picocli's own reason. Flattening, not {@code CommandSpecMerger}:
     * the merger's namespace semantics (which subcommand an option belongs to) were not
     * checked against this tree, and copying onto the root is what this has always done.
     */
    static List<String> compose(CommandSpec root, CommandSpec cs) {
        List<String> problems = new ArrayList<>();
        for (OptionSpec opt : cs.options()) addCurated(root, opt, problems);
        for (ArgGroupSpec grp : cs.argGroups()) {
            try { root.addArgGroup(grp); }
            catch (RuntimeException e) { problems.add("argument group: " + reason(e)); }
        }
        for (HelpSectionSpec sec : cs.helpSectionSpecs().values()) root.addHelpSectionSpec(sec);
        for (String secName : cs.helpSections()) {
            Optional<OptionSpec> trig = cs.findHelpSectionTrigger(secName);
            if (trig.isPresent()) addCurated(root, trig.get(), problems);
        }
        return problems;
    }

    private static void addCurated(CommandSpec root, OptionSpec opt, List<String> problems) {
        for (String n : opt.names()) {
            OptionSpec have = root.findOption(n);
            if (have == opt) return;
            if (have != null) {
                problems.add("option " + n + " is already defined; the existing one is kept");
                return;
            }
        }
        try { root.addOption(opt); }
        catch (RuntimeException e) { problems.add("option " + opt.longestName() + ": " + reason(e)); }
    }

    /**
     * The model of one wrapper verb, the one factory behind both its own help screen and its
     * node in {@link #tree}; {@code null} for a verb with no arguments of its own to describe.
     *
     * <p>Two copies of a verb's arguments are two chances to disagree, and they did: the
     * screens had the flags and the tree, which the completions come from, had empty nodes.
     * {@code task} is not here because its model depends on whether names are wanted; see
     * {@link #taskSpec}.
     */
    static CommandSpec wrapperVerbSpec(Ctx c, String verb) {
        return switch (verb) {
            case "pin" -> pinSpec(c);
            case "info" -> infoSpec(c);
            case "doctor" -> doctorSpec(c);
            case "validate" -> validateSpec(c);
            case "wrapper" -> wrapperSpec(c);
            case "completion" -> completionSpec(c);
            case "examples" -> examplesSpec(c);
            case "local" -> localSpec(c);
            default -> null;
        };
    }

    /** Hangs a verb's own model under {@code root}, described the way the tree describes wrapper verbs. */
    static void attachWrapper(CommandSpec root, String verb, CommandSpec own) {
        own.usageMessage().description("(wrapper) " + wrapperDesc(verb));
        root.addSubcommand(verb, new CommandLine(own));
    }

    /**
     * {@code ./flixw task}: one subcommand per name in {@code .flixw/tasks.toml}, or none.
     * Shared by {@code help task} and the tree's {@code task} node.
     */
    static CommandSpec taskSpec(Ctx c, boolean names) {
        List<String[]> rows = c.rows("tasks");
        CommandSpec s = base("./flixw task",
            ".flixw/tasks.toml -- npm-`scripts`-style aliases for this project.",
            "",
            rows.isEmpty() ? "No tasks defined in this project."
                           : "Never fetched and never verified, because there is nothing to"
                           + " verify: the file is committed alongside the code it builds.");
        if (names)
            for (String[] r : rows) sub(s, r[0], r.length > 1 ? r[1] : "");
        return s;
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
            "./flixw pin [<owner>/<repo>] [<version>] [--java <version>] [--editor-jar=copy|off] [--fetch]",
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
            .description("compiler version, e.g. 0.79.0 or owner/repo@version").build());
        // stage 0 reads `--java <version>` and `--local <path>` as two words and
        // `--editor-jar=copy|off` as one token (parsePin); an arity of 0 here would let the
        // shells offer them as bare flags, and `--editor-jar copy` is refused with FLIXW008.
        s.parser().separator(" ");
        s.addOption(OptionSpec.builder("--java")
            .paramLabel("<version>").arity("1")
            .description("minimum Java version required for this project").build());
        s.addOption(OptionSpec.builder("--editor-jar=copy", "--editor-jar=off")
            .description("editor integration: copy the compiler jar or off").build());
        s.addOption(OptionSpec.builder("--local")
            .paramLabel("<path>").arity("1")
            .description("point at a local compiler jar or build checkout").build());
        s.addOption(OptionSpec.builder("--stock")
            .description("revert --local to stock pinned compiler").build());
        s.addOption(OptionSpec.builder("--refresh")
            .description("rewrite .flixw/lock.toml in this wrapper's shape").build());
        s.addOption(OptionSpec.builder("--fetch")
            .description("download the compiler even when the cache already has it").build());
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
        // The same list flixw-examples.java's VERBS relays; UnitCheck holds the two together.
        for (String verb : List.of("run", "check", "build", "build-classes", "build-jar", "build-fatjar",
                                   "build-pkg", "clean", "doc", "format", "outdated", "stat", "install",
                                   "remove", "upgrade", "eff-check", "eff-lock", "test")) {
            CommandSpec child = sub(s, verb, "runs Flix " + verb + " in an example package.");
            child.addPositional(PositionalParamSpec.builder().paramLabel("<name>")
                .description("the example directory name").build());
            child.usageMessage().customSynopsis("./flixw examples " + verb + " [flags] <name> [-- args]");
        }
        CommandSpec local = sub(s, "local", "runs an example against this project's local source.");
        local.usageMessage().customSynopsis("./flixw examples local <verb> <name> [-- args]");
        // The verbs are the ones `./flixw local` runs in an overlay, so a completion after
        // `examples local` offers them instead of an empty node.
        for (Map.Entry<String, CommandLine> verb
                 : localSpec("./flixw examples local", false).subcommands().entrySet())
            local.addSubcommand(verb.getKey(), verb.getValue());
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

    static void flix(Ctx c, String name, List<String> jvmOpts, String helpFlag)
            throws IOException, InterruptedException {
        String path = c.get("helpFile");
        if (path.isEmpty()) {
            System.err.println("flixw: no compiler help has been captured for this project");
            System.err.println("       run: ./flixw pin <version>   (then any compiler verb once)");
            throw new Exit(89);
        }
        String help = readOrEmpty(path);
        String version = c.get("compilerVersion");
        if (name == null) {
            if (helpFlag != null) {
                render(tree(c, "./flixw"), helpFlag);
                return;
            }
            flixOverview(c, help, version);
            return;
        }

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

        CommandSpec fromSpec = null;
        try { fromSpec = specVerb(c, name); }
        catch (SpecError e) { warnSpec(e); }   // falls through to the compiler's raw help below
        if (fromSpec != null) { render(fromSpec, helpFlag); return; }

        List<String> compilerVerbs = c.words("compilerVerbs");
        if (!compilerVerbs.contains(name) && !c.words("fallbackVerbs").contains(name)) {
            System.err.println("flixw: Flix " + version + " lists no command " + q(name));
            System.err.println(compilerVerbs.isEmpty()
                ? "       flixw does not recognise this compiler's help layout;"
                + "\n       run: ./flixw help flix   (to see it unedited)"
                : "       known commands: " + String.join(" ", compilerVerbs));
            throw new Exit(89);
        }

        System.out.print(help.endsWith("\n") ? help : help + "\n");
    }

    static final Set<String> SPECS = Set.of(
        "flix-0.60.0.picocli", "flix-0.67.0.picocli", "flix-0.67.1.picocli",
        "flix-0.68.0.picocli", "flix-0.73.0.picocli", "flix-0.75.2.picocli",
        "flix-0.75.3.picocli", "flix-0.76.0.picocli", "flix-0.76.2.picocli",
        "flix-0.77.0.picocli", "flix-0.79.0.picocli");

    static String specFileForVersion(String version) {
        if (version == null || version.isEmpty()) return null;
        String exact = "flix-" + version + ".picocli";
        if (SPECS.contains(exact)) return exact;
        if (version.equals("0.76.1")) return "flix-0.76.0.picocli";
        if (version.equals("0.78.0")) return "flix-0.77.0.picocli";
        if (version.startsWith("0.73.") || version.startsWith("0.74.") || version.equals("0.75.0") || version.equals("0.75.1"))
            return "flix-0.73.0.picocli";
        if (version.startsWith("0.68.") || version.startsWith("0.69.") || version.startsWith("0.70.") || version.startsWith("0.71.") || version.startsWith("0.72."))
            return "flix-0.68.0.picocli";
        if (version.equals("0.67.2")) return "flix-0.67.1.picocli";
        if (version.startsWith("0.60.") || version.startsWith("0.61.") || version.startsWith("0.62.") || version.startsWith("0.63.") || version.startsWith("0.64.") || version.startsWith("0.65.") || version.startsWith("0.66."))
            return "flix-0.60.0.picocli";
        return exact;
    }

    /**
     * The curated spec for one compiler version, or empty when none has been authored.
     *
     * <p>Empty means <em>absent</em>: no file for this version, or no source to look in. A spec
     * that is there and cannot be read or parsed throws {@link SpecError} instead, naming the
     * file, the version and the parser's own reason; callers decide what a broken spec costs
     * ({@link #tree(Ctx, String, boolean, boolean)}).
     *
     * <p>Read from {@code /specs/} on the class path, which stage 0 fills with this release's
     * own {@code flixw-specs.jar} -- fetched and digest-verified like every other asset -- or,
     * for this repository's unit checks, from the directory {@code -Dflixw.specs.dir} names.
     *
     * <p>There used to be a third source: {@code src/assets/picocli/} found by walking up from
     * the working directory. It is gone because it was the only one that ever worked. No
     * release published a {@code /specs/} resource, and the suite's scratch projects live
     * inside this checkout, so the walk found the source tree every time and hid that an
     * installed project had never rendered a curated screen at all.
     */
    static Optional<CommandSpec> loadSpec(String version) {
        return loadSpec(version, flixwcli.class::getResourceAsStream);
    }

    /**
     * {@link #loadSpec(String)} with the class-path lookup passed in, so a test can stand in
     * for {@code flixw-specs.jar} without building one.
     *
     * <p>Both routes classify alike: nothing at the name is absent; anything there that is not
     * a regular, readable, valid UTF-8 file that parses is broken. Decoding is strict because a
     * lenient decode would substitute U+FFFD, parse whatever is left and ship a screen from
     * damaged text. A link whose target is gone is present (it is named) and so broken.
     */
    static Optional<CommandSpec> loadSpec(String version,
                                          java.util.function.Function<String, InputStream> resource) {
        String name = specFileForVersion(version);
        if (name == null) return Optional.empty();
        String from = "flixw-specs.jar";
        try {
            byte[] raw;
            try (InputStream in = resource.apply("/specs/" + name)) {
                raw = in == null ? null : in.readAllBytes();
            }
            if (raw == null) {
                String dir = System.getProperty("flixw.specs.dir");
                Path disk = dir == null || dir.isBlank() ? null : Paths.get(dir, name);
                if (disk == null || !Files.exists(disk, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    return Optional.empty();
                from = "flixw.specs.dir";
                raw = Files.readAllBytes(disk);   // a directory or a dangling link throws here
            }
            return Optional.of(CommandSpecDsl.parse(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw)).toString()));
        } catch (Exception e) {
            // Present and unusable, which is not the same as absent: absent returned above.
            throw new SpecError(specLabel(version, name) + " in " + from + " is unusable: " + reason(e), e);
        }
    }

    /**
     * The spec-derived model for one compiler verb, gated by provenance: only when
     * this run has already established the pinned compiler is genuinely upstream
     * at that exact version, never for {@code FLIX_JAR}, a fork, or a selected
     * local compiler, none of which this spec was authored against.
     */
    static CommandSpec specVerb(Ctx c, String name) {
        if (!"true".equals(c.get("upstream"))) return null;
        return loadSpec(c.get("compilerVersion"))
            // The DSL names its root "flix", matching the real compiler -- correct for
            // src/assets/picocli/*.picocli's own header comment, wrong for a screen someone
            // reaches by typing "./flixw". Renaming the root before descending to the
            // subcommand makes its qualifiedName() read "./flixw <verb>"; the fresh parse
            // this call already did means nothing else observes the rename.
            .map(s -> s.name("./flixw"))
            .map(s -> s.subcommands().get(name))
            .map(CommandLine::getCommandSpec)
            // --help/--version short-circuit before any verb-specific code runs, so every
            // verb takes them in reality even though the DSL's mixinStandardHelpOptions is
            // declared once, at the root, and (unlike the curated options above it) is not
            // inherited by subcommands. No verb block declares either explicitly, so this
            // never collides with one that does.
            .map(spec -> spec.mixinStandardHelpOptions(true))
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
            render(taskSpec(c, true));
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
        CommandSpec spec;
        try { spec = tree(c, "flixw", false, true); }
        catch (SpecError e) {
            System.err.println("flixw: " + e.getMessage());
            System.err.println("       no completion script was written: one generated without the"
                             + " spec would be missing options and not say so.");
            System.err.println(SPEC_REPAIR);
            throw new Exit(89);
        }
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
     *
     * <p>The script holds one table row per command path, written from the model, and the
     * completer resolves the active path from {@code $commandAst}: the words before the
     * cursor, with an option that takes a value consuming the word after it. A row lists the
     * node's subcommands, its options and the options it inherits, so a word is offered after
     * its own command only. No verb name is written into the logic, and no project task
     * name into the table (see {@link #tree(Ctx, String, boolean, boolean)}).
     */
    static void pwsh(CommandSpec spec) {
        System.out.println("# flixw TAB completion for PowerShell, generated from this"
                         + " project's pinned compiler.");
        System.out.println("# Regenerate after a re-pin:  ./flixw completion pwsh");
        System.out.println();
        System.out.println("# One row per command path. c: candidates (subcommands, positional values),");
        System.out.println("# o: options, v: the options of o that take a value.");
        // Ordinal, like picocli and the bash script: a Hashtable literal ignores case, so
        // `flixw DOCTOR` would otherwise resolve to the doctor node.
        System.out.println("$global:flixwNodes = [System.Collections.Hashtable]::new("
                         + "[System.StringComparer]::Ordinal)");
        pwshNode("", spec);
        System.out.println("Register-ArgumentCompleter -Native -CommandName flixw,flixw.cmd"
                         + " -ScriptBlock {");
        System.out.println("    param($wordToComplete, $commandAst, $cursorPosition)");
        System.out.println("    $node = ''");
        System.out.println("    $skip = $false");
        System.out.println("    $elements = @($commandAst.CommandElements)");
        System.out.println("    for ($i = 1; $i -lt $elements.Count; $i++) {");
        System.out.println("        $e = $elements[$i]");
        System.out.println("        if ($e.Extent.EndOffset -ge $cursorPosition) { break }   # the word being completed");
        System.out.println("        $t = if ($e -is [System.Management.Automation.Language.StringConstantExpressionAst])"
                         + " { $e.Value } else { $e.Extent.Text }");
        System.out.println("        if ($skip) { $skip = $false; continue }                  # an option's value");
        System.out.println("        if ($t -ceq '--') { return }                             # forwarded to the compiler");
        System.out.println("        if ($t.StartsWith('-')) {");
        System.out.println("            if (@($global:flixwNodes[$node].v) -ccontains $t) { $skip = $true }");
        System.out.println("            continue");
        System.out.println("        }");
        System.out.println("        $next = if ($node) { \"$node $t\" } else { $t }");
        System.out.println("        if ($global:flixwNodes.ContainsKey($next)) { $node = $next }");
        System.out.println("    }");
        System.out.println("    if ($skip) { return }                                        # an option's value is next");
        System.out.println("    $prefix = $wordToComplete.Trim([char]39, [char]34)");
        System.out.println("    @($global:flixwNodes[$node].c) + @($global:flixwNodes[$node].o) |");
        System.out.println("        Select-Object -Unique |");
        System.out.println("        Where-Object { $_.StartsWith($prefix, [System.StringComparison]::Ordinal) } |");
        System.out.println("        ForEach-Object { [System.Management.Automation"
                         + ".CompletionResult]::new($_, $_, 'ParameterValue', $_) }");
        System.out.println("}");
    }

    /** One table row for {@code spec} at {@code path}, then one for each subcommand. */
    private static void pwshNode(String path, CommandSpec spec) {
        List<String> children = new ArrayList<>(spec.subcommands().keySet());
        for (PositionalParamSpec p : spec.positionalParameters()) {
            Iterable<String> values = p.completionCandidates();
            if (values != null) for (String v : values) children.add(v);
        }
        // picocli copies an option scoped INHERIT into every subcommand when it is added (in
        // either order, at any depth), so spec.options() already holds the inherited ones.
        Map<String, Boolean> options = new LinkedHashMap<>();
        for (OptionSpec o : spec.options()) pwshOption(options, o);
        StringBuilder opts = new StringBuilder(), valued = new StringBuilder();
        for (Map.Entry<String, Boolean> e : options.entrySet()) {
            pwshWord(opts, e.getKey());
            if (e.getValue()) pwshWord(valued, e.getKey());
        }
        StringBuilder kids = new StringBuilder();
        for (String k : children) pwshWord(kids, k);
        System.out.println("$global:flixwNodes[" + q(path.replace("'", "''")) + "] = @{ c = @(" + kids + "); o = @(" + opts
                         + "); v = @(" + valued + ") }");
        for (Map.Entry<String, CommandLine> e : spec.subcommands().entrySet())
            pwshNode(path.isEmpty() ? e.getKey() : path + " " + e.getKey(),
                     e.getValue().getCommandSpec());
    }

    private static void pwshOption(Map<String, Boolean> into, OptionSpec o) {
        if (o.hidden()) return;
        for (String n : o.names()) into.put(n, o.arity().min() > 0);
    }

    private static void pwshWord(StringBuilder into, String word) {
        if (into.length() > 0) into.append(',');
        into.append(q(word.replace("'", "''")));
    }

    static String readOrEmpty(String path) {
        try { return Files.readString(Paths.get(path), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    static String q(String s) { return "'" + s + "'"; }
}
