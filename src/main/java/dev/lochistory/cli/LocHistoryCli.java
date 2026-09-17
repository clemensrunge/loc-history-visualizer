package dev.lochistory.cli;

import dev.lochistory.analysis.AnalysisProgress;
import dev.lochistory.analysis.GitCommandException;
import dev.lochistory.analysis.LocHistoryAnalyzer;
import dev.lochistory.model.HistoryResult;
import dev.lochistory.model.LocSnapshot;
import dev.lochistory.model.FileMetrics;
import dev.lochistory.model.CountingMetric;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LocHistoryCli {
    private LocHistoryCli() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Options options = Options.parse(args);
            if (options.help) {
                usage(out);
                return 0;
            }
            LocHistoryAnalyzer analyzer = new LocHistoryAnalyzer(options.repository);
            if (options.listBranches) {
                analyzer.branches(AnalysisProgress.NONE).forEach(out::println);
                return 0;
            }
            String branch = options.workingTree ? "HEAD" : options.branch;
            if (branch == null || branch.isBlank()) branch = analyzer.currentBranch(AnalysisProgress.NONE);
            if (branch.isBlank()) throw new IllegalArgumentException("Detached HEAD: specify --branch <name>");
            AnalysisProgress progress = options.quiet ? AnalysisProgress.NONE : new StderrProgress(err);
            HistoryResult result = options.workingTree
                    ? new HistoryResult("working tree", List.of(analyzer.workingTreeSnapshot(progress)))
                    : analyzer.analyze(branch, options.commits, options.sampleEvery, progress);
            LocSnapshot base = !options.format.equals("tsv") && options.compare != null
                    ? analyzer.snapshot(options.compare, AnalysisProgress.NONE) : null;
            String report = switch (options.format) {
                case "markdown" -> renderMarkdown(result, base);
                case "text" -> renderText(result, base);
                default -> renderTsv(result);
            };
            if (options.check != null) {
                String existing = Files.exists(options.check) ? Files.readString(options.check) : "";
                if (!existing.equals(report)) {
                    err.println("LOC report is stale: " + options.check);
                    return 3;
                }
            } else if (options.output != null) {
                Path parent = options.output.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(options.output, report, StandardCharsets.UTF_8);
            } else {
                out.print(report);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            usage(err);
            return 2;
        } catch (GitCommandException e) {
            err.println("git error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("I/O error: " + e.getMessage());
            return 1;
        }
    }

    private static String renderTsv(HistoryResult result) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        out.println("branch\tcommit\ttimestamp\tpath\tkind\trloc\tloc\trloc_percent\topenai_tokens\tclaude_tokens");
        for (LocSnapshot snapshot : result.snapshots()) {
            List<String> folders = collectFolders(snapshot);
            emit(out, result.branch(), snapshot, ".", "folder",
                    snapshot.linesFor("", false, true), snapshot.linesFor("", false, false),
                    snapshot.linesFor("", false, CountingMetric.OPENAI_TOKENS),
                    snapshot.linesFor("", false, CountingMetric.CLAUDE_TOKENS));
            folders.forEach(path -> emit(out, result.branch(), snapshot, path, "folder",
                    snapshot.linesFor(path, false, true), snapshot.linesFor(path, false, false),
                    snapshot.linesFor(path, false, CountingMetric.OPENAI_TOKENS),
                    snapshot.linesFor(path, false, CountingMetric.CLAUDE_TOKENS)));
            snapshot.metricsByFile().entrySet().stream().sorted(MapEntryComparator.INSTANCE)
                    .forEach(entry -> emit(out, result.branch(), snapshot, entry.getKey(), "file",
                            entry.getValue().rloc(), entry.getValue().loc(),
                            entry.getValue().value(CountingMetric.OPENAI_TOKENS),
                            entry.getValue().value(CountingMetric.CLAUDE_TOKENS)));
        }
        if (!result.snapshots().isEmpty()) {
            LocSnapshot latest = result.snapshots().get(result.snapshots().size() - 1);
            emit(out, result.branch(), latest, ".", "summary",
                    latest.linesFor("", false, CountingMetric.RLOC),
                    latest.linesFor("", false, CountingMetric.LOC),
                    latest.linesFor("", false, CountingMetric.OPENAI_TOKENS),
                    latest.linesFor("", false, CountingMetric.CLAUDE_TOKENS));
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String renderMarkdown(HistoryResult result, LocSnapshot base) {
        StringBuilder text = new StringBuilder("# Lines of code history\n\n");
        text.append("Generated by LOC History Visualizer for `").append(md(result.branch())).append("`. ")
                .append("Counts are physical lines in Git-tracked text files.\n\n");
        if (result.snapshots().isEmpty()) return text.append("No commits found.\n").toString();
        LocSnapshot latest = result.snapshots().get(result.snapshots().size() - 1);
        int total = latest.linesFor("", false);
        text.append("## Current total\n\n**").append(String.format("%,d", total)).append(" LOC** at `")
                .append(latest.commit().shortHash()).append("`");
        if (base != null) {
            int old = base.linesFor("", false);
            text.append(" (delta from `").append(base.commit().shortHash()).append("`: **")
                    .append(signed(total - old)).append("**)");
        }
        text.append("\n\n## History\n\n| Commit | Date | LOC | Change |\n|---|---:|---:|---:|\n");
        Integer previous = null;
        for (LocSnapshot snapshot : result.snapshots()) {
            int count = snapshot.linesFor("", false);
            text.append("| `").append(snapshot.commit().shortHash()).append("` | ")
                    .append(snapshot.commit().time().toString(), 0, 10).append(" | ")
                    .append(String.format("%,d", count)).append(" | ")
                    .append(previous == null ? "—" : signed(count - previous)).append(" |\n");
            previous = count;
        }
        text.append("\n## Latest folders\n\n| Folder | LOC | Share |").append(base == null ? "" : " Delta |").append("\n")
                .append(base == null ? "|---|---:|---:|\n" : "|---|---:|---:|---:|\n");
        topFolders(latest).forEach(folder -> {
            int count = latest.linesFor(folder, false);
            text.append("| `").append(md(folder)).append("` | ").append(String.format("%,d", count))
                    .append(" | ").append(String.format("%.1f%%", count * 100.0 / Math.max(1, total))).append(" |");
            if (base != null) text.append(' ').append(signed(count - base.linesFor(folder, false))).append(" |");
            text.append('\n');
        });
        text.append("\n## Summary\n\nProject totals at `").append(latest.commit().shortHash()).append("`.");
        if (base != null) {
            text.append(" Deltas are latest minus `").append(base.commit().shortHash()).append("`.");
        }
        text.append("\n\n| Metric | Total |").append(base == null ? "" : " Base | Delta |")
                .append('\n').append(base == null ? "|---|---:|\n" : "|---|---:|---:|---:|\n");
        for (CountingMetric metric : CountingMetric.values()) {
            int count = latest.linesFor("", false, metric);
            text.append("| ").append(metric).append(" | ").append(String.format("%,d", count)).append(" |");
            if (base != null) {
                int old = base.linesFor("", false, metric);
                text.append(' ').append(String.format("%,d", old)).append(" | ")
                        .append(signed(count - old)).append(" |");
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String renderText(HistoryResult result, LocSnapshot base) {
        StringBuilder text = new StringBuilder("Lines of code history\n\n");
        text.append("Branch: ").append(clean(result.branch())).append('\n')
                .append("Counts are physical lines in Git-tracked text files.\n\n");
        if (result.snapshots().isEmpty()) return text.append("No commits found.\n").toString();
        LocSnapshot latest = result.snapshots().get(result.snapshots().size() - 1);
        int total = latest.linesFor("", false);
        text.append("Current total\n").append(String.format("%,d", total)).append(" LOC at ")
                .append(latest.commit().shortHash());
        if (base != null) text.append(" (delta from ").append(base.commit().shortHash()).append(": ")
                .append(signed(total - base.linesFor("", false))).append(')');
        text.append("\n\nHistory\n");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Commit", "Date", "LOC", "Change"});
        Integer previous = null;
        for (LocSnapshot snapshot : result.snapshots()) {
            int count = snapshot.linesFor("", false);
            rows.add(new String[]{snapshot.commit().shortHash(), snapshot.commit().time().toString().substring(0, 10),
                    String.format("%,d", count), previous == null ? "—" : signed(count - previous)});
            previous = count;
        }
        appendTextTable(text, rows);
        text.append("\nLatest folders\n");
        rows = new ArrayList<>();
        rows.add(base == null ? new String[]{"Folder", "LOC", "Share"}
                : new String[]{"Folder", "LOC", "Share", "Delta"});
        for (String folder : topFolders(latest)) {
            int count = latest.linesFor(folder, false);
            String share = String.format("%.1f%%", count * 100.0 / Math.max(1, total));
            rows.add(base == null ? new String[]{clean(folder), String.format("%,d", count), share}
                    : new String[]{clean(folder), String.format("%,d", count), share,
                        signed(count - base.linesFor(folder, false))});
        }
        appendTextTable(text, rows);
        text.append("\nSummary\nProject totals at ").append(latest.commit().shortHash()).append(".\n");
        if (base != null) text.append("Deltas are latest minus ").append(base.commit().shortHash()).append(".\n");
        text.append('\n');
        rows = new ArrayList<>();
        rows.add(base == null ? new String[]{"Metric", "Total"} : new String[]{"Metric", "Total", "Base", "Delta"});
        for (CountingMetric metric : CountingMetric.values()) {
            int count = latest.linesFor("", false, metric);
            if (base == null) rows.add(new String[]{metric.toString(), String.format("%,d", count)});
            else {
                int old = base.linesFor("", false, metric);
                rows.add(new String[]{metric.toString(), String.format("%,d", count),
                        String.format("%,d", old), signed(count - old)});
            }
        }
        appendTextTable(text, rows);
        return text.toString();
    }

    private static void appendTextTable(StringBuilder text, List<String[]> rows) {
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int column = 0; column < widths.length; column++)
                widths[column] = Math.max(widths[column], row[column].length());
        }
        for (int index = 0; index < rows.size(); index++) {
            String[] row = rows.get(index);
            for (int column = 0; column < widths.length; column++) {
                if (column > 0) text.append("  ");
                int padding = widths[column] - row[column].length();
                if (column > 0) text.append(" ".repeat(padding));
                text.append(row[column]);
                if (column == 0) text.append(" ".repeat(padding));
            }
            text.append('\n');
            if (index == 0) {
                for (int column = 0; column < widths.length; column++) {
                    if (column > 0) text.append("  ");
                    text.append("-".repeat(widths[column]));
                }
                text.append('\n');
            }
        }
    }

    private static List<String> topFolders(LocSnapshot snapshot) {
        return collectFolders(snapshot).stream().filter(path -> !path.contains("/"))
                .sorted(Comparator.comparingInt((String path) -> snapshot.linesFor(path, false)).reversed())
                .limit(20).toList();
    }

    private static String signed(int number) {
        return (number > 0 ? "+" : "") + String.format("%,d", number);
    }

    private static String md(String value) {
        return value.replace("|", "\\|").replace("`", "\\`");
    }

    private static List<String> collectFolders(LocSnapshot snapshot) {
        java.util.Set<String> folders = new java.util.TreeSet<>();
        for (String file : snapshot.metricsByFile().keySet()) {
            int slash = file.lastIndexOf('/');
            while (slash >= 0) {
                folders.add(file.substring(0, slash));
                slash = file.lastIndexOf('/', slash - 1);
            }
        }
        return new ArrayList<>(folders);
    }

    private static void emit(PrintStream out, String branch, LocSnapshot snapshot,
                             String path, String kind, int rloc, int loc, int openaiTokens, int claudeTokens) {
        out.printf("%s\t%s\t%s\t%s\t%s\t%d\t%d\t%.2f\t%d\t%d%n", clean(branch), snapshot.commit().hash(),
                DateTimeFormatter.ISO_INSTANT.format(snapshot.commit().time()), clean(path), kind, rloc, loc,
                loc == 0 ? 0 : rloc * 100.0 / loc, openaiTokens, claudeTokens);
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void usage(PrintStream stream) {
        stream.println("Usage: loc-history [--repo PATH] [--branch NAME] [--commits N] [--sample-every N]");
        stream.println("                   [--format tsv|markdown|text] [--compare REF] [--output FILE|--check FILE] [--quiet]");
        stream.println("       loc-history [--repo PATH] --list-branches");
        stream.println("Writes tab-separated folder and file LOC records to stdout.");
        stream.println("Use --format text for readable console tables; --compare applies to text and markdown.");
        stream.println("       loc-history [--repo PATH] --working-tree [--compare REF] [--format text|markdown|tsv]");
        stream.println("Working-tree mode defaults to text output and comparison against HEAD.");
    }

    private static final class StderrProgress implements AnalysisProgress {
        private final PrintStream err;
        private StderrProgress(PrintStream err) { this.err = err; }
        @Override public void update(double fraction, String message) { err.print("\r" + message + "   "); }
    }

    private enum MapEntryComparator implements Comparator<java.util.Map.Entry<String, FileMetrics>> {
        INSTANCE;
        @Override public int compare(java.util.Map.Entry<String, FileMetrics> left,
                                     java.util.Map.Entry<String, FileMetrics> right) {
            return left.getKey().compareTo(right.getKey());
        }
    }

    private static final class Options {
        private Path repository = Path.of(".").toAbsolutePath().normalize();
        private String branch;
        private int commits = 60;
        private int sampleEvery = 1;
        private boolean listBranches;
        private boolean quiet;
        private boolean help;
        private boolean workingTree;
        private String format = "tsv";
        private String compare;
        private Path output;
        private Path check;

        private static Options parse(String[] args) {
            Options value = new Options();
            boolean formatSpecified = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--repo" -> value.repository = Path.of(requireValue(args, ++i, "--repo")).toAbsolutePath().normalize();
                    case "--branch" -> value.branch = requireValue(args, ++i, "--branch");
                    case "--commits" -> value.commits = positive(requireValue(args, ++i, "--commits"), "--commits");
                    case "--sample-every" -> value.sampleEvery = positive(requireValue(args, ++i, "--sample-every"), "--sample-every");
                    case "--list-branches" -> value.listBranches = true;
                    case "--quiet" -> value.quiet = true;
                    case "--working-tree" -> value.workingTree = true;
                    case "--format" -> { value.format = requireValue(args, ++i, "--format"); formatSpecified = true; }
                    case "--compare" -> value.compare = requireValue(args, ++i, "--compare");
                    case "--output" -> value.output = Path.of(requireValue(args, ++i, "--output"));
                    case "--check" -> value.check = Path.of(requireValue(args, ++i, "--check"));
                    case "-h", "--help" -> value.help = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (value.workingTree) {
                if (value.branch != null) throw new IllegalArgumentException("--branch cannot be combined with --working-tree; use --compare for the base");
                if (!formatSpecified) value.format = "text";
                if (value.compare == null) value.compare = "HEAD";
            }
            if (!value.format.equals("tsv") && !value.format.equals("markdown") && !value.format.equals("text"))
                throw new IllegalArgumentException("--format must be tsv, markdown, or text");
            if (value.output != null && value.check != null)
                throw new IllegalArgumentException("Use only one of --output or --check");
            return value;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " needs a value");
            return args[index];
        }

        private static int positive(String raw, String option) {
            try {
                int value = Integer.parseInt(raw);
                if (value < 1) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " must be a positive integer");
            }
        }
    }
}
