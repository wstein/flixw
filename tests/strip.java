import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Produces the stage 0 that ships: the same program with its commentary removed.
 *
 * <p>{@code java tests/strip.java src/flixw.java <version> > flixw.java}
 *
 * <p>Every adopting project commits {@code .flixw/flixw.java} into its own repository, and
 * a third of that file is prose written for whoever audits flixw itself. That audience
 * reads the documented source on the website or in the repository, both of which the
 * generated header names; the vendored copy is there to be executed and digest-checked.
 * So the comments stay where they are useful and leave where they are only weight.
 *
 * <p><b>This must be a pure function of its input.</b> The published artifact and the
 * readable one are different files, which is only acceptable while anyone can regenerate
 * the first from the second and compare digests: that is what keeps "read it before you
 * trust it" true. Nothing here may consult the clock, the environment or the filesystem
 * beyond the one file named, and {@code tests/lint.sh} checks that two runs agree.
 *
 * <p>The scanner is a state machine rather than a regex because Java's comment syntax
 * cannot be matched by one. Three things in this very repository break the naive version:
 * {@code "https://..."} is not a line comment, {@code '\''} is not an unterminated
 * literal, and {@code // inside """ or ''': find the close} is a comment that mentions a
 * text-block delimiter -- reading that as an opener swallows the rest of the file.
 */
final class strip {
    private strip() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java tests/strip.java <source.java> <version>");
            System.exit(2);
        }
        String src = Files.readString(Paths.get(args[0]), StandardCharsets.UTF_8);
        System.out.print(header(args[1]) + strip(src));
    }

    /**
     * The one comment that survives, and the only thing here that is not in the input.
     *
     * <p>It has one job: tell a reader of a vendored copy where the version they are
     * looking at is documented, and that what they are holding is derived rather than
     * authored. Naming the release rather than "latest" matters -- the reader is auditing
     * the bytes in front of them, not whatever the project has since become.
     */
    static String header(String version) {
        return """
            // flixw %s -- stage 0. GENERATED: this is the documented source with its
            // comments removed, which is why it reads as bare mechanism.
            //
            // The commentary is the security story -- why each check exists, and which
            // cheaper option was rejected. Read it before trusting this file with a
            // download:
            //
            //   https://wstein.github.io/flixw/          docs, and the lock schema
            //   https://github.com/wstein/flixw          the source this was made from
            //
            // Reproducible on purpose: `java tests/strip.java src/flixw.java %s` at tag
            // v%s regenerates this file byte for byte, so the readable source and the
            // running one can be checked against each other rather than taken on trust.
            """.formatted(version, version, version);
    }

    /** Comment-free source: literals verbatim, comment-only lines gone, blank runs collapsed. */
    static String strip(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;          // drop, keep the newline
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                if (end < 0) throw new IllegalStateException("unterminated block comment");
                // A block comment spanning lines leaves the newlines behind it, so the code
                // after it keeps the line it was written on rather than sliding upward.
                for (int k = i; k < end + 2; k++) if (src.charAt(k) == '\n') out.append('\n');
                i = end + 2;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                if (end < 0) throw new IllegalStateException("unterminated text block");
                out.append(src, i, end + 3);
                i = end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c) j += src.charAt(j) == '\\' ? 2 : 1;
                if (j >= n) throw new IllegalStateException("unterminated literal at " + i);
                out.append(src, i, j + 1);
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return tidy(out.toString());
    }

    /**
     * Removes the holes stripping leaves: trailing space where a comment was, lines that
     * held nothing else, and the runs of blanks those two produce.
     *
     * <p>One blank line is kept where there were several, rather than none. This file is
     * read in diffs -- an upgrade shows up as a change to it in somebody's project -- and
     * a wall of undifferentiated statements makes that harder to review than it needs to
     * be, for a saving of a few hundred bytes.
     */
    static String tidy(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean lastBlank = false;
        boolean first = true;
        for (String line : s.split("\n", -1)) {
            String trimmed = line.stripTrailing();
            boolean blank = trimmed.isEmpty();
            if (blank && (lastBlank || first)) continue;
            out.append(trimmed).append('\n');
            lastBlank = blank;
            first = false;
        }
        // A trailing blank line survives the loop above as one newline too many.
        while (out.length() > 1 && out.charAt(out.length() - 2) == '\n') out.setLength(out.length() - 1);
        return out.toString();
    }
}
