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
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

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

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java -cp picocli.jar flixw-help.java"
                             + " <context-file> [<topic> [<name>]]");
            System.exit(87);
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
                System.err.println("       topics: flix wrapper plugin task");
                System.exit(89);
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

    /** An option row in either layout: a short form, a long form, or both, then prose. */
    static final Pattern OPTION_ENTRY = Pattern.compile(
        "^\\s*(?:-([A-Za-z0-9])(?:[, ]\\s*)?)?(--[A-Za-z][A-Za-z0-9-]*)?"
      + "(?:\\s+<([^>]*)>)?\\s\\s+(\\S.*)$");

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
        for (String line : help.split("\n", -1)) {
            if (line.startsWith("Command:") || line.isBlank()) continue;
            Matcher m = OPTION_ENTRY.matcher(line.replace('\t', ' '));
            if (!m.matches()) continue;
            String shortOpt = m.group(1), longOpt = m.group(2);
            if (shortOpt == null && longOpt == null) continue;
            String key = longOpt != null ? longOpt : "-" + shortOpt;
            out.put(key, new String[] {
                shortOpt == null ? "" : "-" + shortOpt,
                longOpt == null ? "" : longOpt,
                m.group(3) == null ? "" : m.group(3),
                collapse(m.group(4)) });
        }
        return out;
    }

    /** Wrapped prose onto one line; a description is a sentence, not a layout. */
    static String collapse(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }

    // ---- rendering ----------------------------------------------------------

    /** One renderer for every topic, so the topics cannot drift apart in appearance. */
    static void render(CommandSpec spec) {
        new CommandLine(spec).setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.AUTO))
                             .usage(System.out);
    }

    static CommandSpec base(String name, String... description) {
        CommandSpec s = CommandSpec.create().name(name);
        s.usageMessage().description(description).abbreviateSynopsis(true);
        return s;
    }

    static void sub(CommandSpec parent, String name, String description) {
        CommandSpec child = CommandSpec.create().name(name);
        child.usageMessage().description(description == null || description.isEmpty()
                                         ? "" : description);
        parent.addSubcommand(name, new CommandLine(child));
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
        List<String> compilerVerbs = c.words("compilerVerbs");
        Map<String, String> desc = c.get("helpFile").isEmpty()
            ? Map.of() : commands(readOrEmpty(c.get("helpFile")));

        CommandSpec root = base("./flixw",
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
        render(root);

        System.out.println();
        System.out.println("  ./flixw help flix [<command>]    the pinned compiler's own help");
        System.out.println("  ./flixw help wrapper             the wrapper's own reference");
        System.out.println("  ./flixw help plugin [<name>]     installed plugins");
        System.out.println("  ./flixw help task [<name>]       this project's tasks");
        System.out.println("  ./flixw -- --help                stock Flix help, unedited");
    }

    static String wrapperDesc(String verb) {
        return switch (verb) {
            case "pin" -> "write .flixw/lock.toml: repository, version and digest.";
            case "info" -> "project, compiler, java and cache state.";
            case "doctor" -> "info plus every check, with a verdict; --fix repairs.";
            case "validate" -> "the checks alone, for CI.";
            case "help" -> "this table.";
            case "plugin" -> "install, list, remove and run verified third-party commands.";
            case "task" -> ".flixw/tasks.toml's aliases.";
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
        s.addOption(OptionSpec.builder("--version").description("the wrapper version").build());
        s.addOption(OptionSpec.builder("--upgrade")
                    .description("move this project to the newest published flixw").build());
        s.addOption(OptionSpec.builder("--install-jdk")
                    .description("fetch a verified Temurin into the cache").build());
        s.addOption(OptionSpec.builder("--purge")
                    .description("delete cache entries unused for N days").build());
        s.addOption(OptionSpec.builder("--schema")
                    .description("the JSON Schema for .flixw/lock.toml").build());
        s.addOption(OptionSpec.builder("--completion")
                    .description("a TAB-completion script for bash, zsh, fish or pwsh").build());
        return s;
    }

    // ---- flix ----------------------------------------------------------------

    static void flix(Ctx c, String name) throws IOException, InterruptedException {
        String path = c.get("helpFile");
        if (path.isEmpty()) {
            System.err.println("flixw: no compiler help has been captured for this project");
            System.err.println("       run: ./flixw pin <version>   (then any compiler verb once)");
            System.exit(89);
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
            System.exit(89);
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
        System.exit(89);
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
        System.exit(89);
    }

    // ---- generated fish completion ------------------------------------------

    /**
     * A fish completion generated from the pinned compiler's own help: verbs with their
     * descriptions, and the compiler's options.
     *
     * <p>This is the enriched counterpart to {@code completion fish}, which stays
     * static and project-independent by design -- it reads {@code .flixw/local/verbs} at TAB
     * time so it cannot go stale at the next {@code pin}. What it cannot do is carry
     * descriptions or options, because those are not in that note. This one can, at the price
     * of being generated for one pinned compiler and needing regeneration after a re-pin.
     *
     * <p><b>The approach is gencomp's; the parse is not.</b> Deriving fish completions by
     * scanning a program's {@code --help} is exactly what {@code gencomp} does, and its output
     * shape is the right one -- {@code __fish_use_subcommand} for the verb position,
     * {@code __fish_seen_subcommand_from} for what follows, {@code -f} to keep filenames out
     * of the verb slot. Its parser is where it breaks on Flix: a generic "commands?" section
     * detector matches the line {@code Command: init} itself and skips it, so every entry it
     * emits is the first word of the *description* on the next line -- {@code creates},
     * {@code checks}, {@code builds} five times over, and not one real verb. Measured against
     * a real compiler, not assumed. The layout-specific parse in this file is what fixes it,
     * and it is also why the picocli layout is handled properly rather than accidentally.
     *
     * <p>Two things gencomp does not do are added here because the parsed data supports them:
     * an option that takes a value is marked {@code -r}, so fish stops offering the next verb
     * where an argument belongs, and descriptions are escaped rather than assumed quote-free.
     */
    static void completion(Ctx c, String shell) {
        if (!"fish".equals(shell)) {
            System.err.println("flixw: help completion generates fish only");
            System.err.println("       for bash, zsh, fish or pwsh: ./flixw completion <shell>");
            System.exit(89);
        }
        String help = c.get("helpFile").isEmpty() ? "" : readOrEmpty(c.get("helpFile"));
        Map<String, String> cmds = commands(help);
        List<String> compilerVerbs = c.words("compilerVerbs");

        System.out.println("# fish completion for flixw, generated from Flix "
                         + c.get("compilerVersion") + "'s own help.");
        System.out.println("# Regenerate after a re-pin:  ./flixw help completion fish");
        System.out.println("# Matched on the command's base name, so this one registration covers");
        System.out.println("# `flixw`, `./flixw` and an absolute path alike.");
        System.out.println();

        for (String v : compilerVerbs)
            System.out.println("complete -f -c flixw -n __fish_use_subcommand -a "
                             + fq(v) + " -d " + fq(cmds.getOrDefault(v, "")));
        for (String v : c.words("wrapperVerbs"))
            if (!compilerVerbs.contains(v))
                System.out.println("complete -f -c flixw -n __fish_use_subcommand -a "
                                 + fq(v) + " -d " + fq("(wrapper) " + wrapperDesc(v)));

        System.out.println();
        for (String[] o : options(help).values()) {
            StringBuilder b = new StringBuilder("complete -c flixw");
            if (!o[0].isEmpty()) b.append(" -s ").append(fq(o[0].substring(1)));
            if (!o[1].isEmpty()) b.append(" -l ").append(fq(o[1].substring(2)));
            // -r where the compiler documented a value: without it fish keeps offering verbs
            // in the slot an argument belongs in, which reads as the completion being broken.
            if (!o[2].isEmpty()) b.append(" -r");
            b.append(" -d ").append(fq(o[3]));
            System.out.println(b);
        }
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
