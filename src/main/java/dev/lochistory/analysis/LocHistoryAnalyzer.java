package dev.lochistory.analysis;

import dev.lochistory.model.CommitInfo;
import dev.lochistory.model.CountingMetric;
import dev.lochistory.model.FileMetrics;
import dev.lochistory.model.HistoryResult;
import dev.lochistory.model.LocSnapshot;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocHistoryAnalyzer {
    private static final Pattern GREP_LINE = Pattern.compile("^[^:]+:(.*?):(\\d+):(.*)$");
    private static final Pattern EXCLUDED_PATH = Pattern.compile(
            "(^|/)(?:\\.[^/]+|build|out|target|node_modules|vendor|dist|cmake-build-[^/]*)(?:/|$)");

    private final List<TokenCounter> tokenCounters;
    private final GitCommandRunner git;
    private final Path repository;

    public LocHistoryAnalyzer(Path repository) {
        this(repository, List.of(new OpenAiTokenCounter(), new ClaudeTokenCounter()));
    }

    public LocHistoryAnalyzer(Path repository, List<TokenCounter> tokenCounters) {
        this.tokenCounters = List.copyOf(tokenCounters);
        this.repository = repository;
        git = new GitCommandRunner(repository);
    }

    public boolean hasGitIgnore() {
        try {
            return !git.run(AnalysisProgress.NONE, "ls-files", "--cached", "--others", "--exclude-standard",
                    "--", ".gitignore", "**/.gitignore").isBlank();
        } catch (GitCommandException ignored) {
            return Files.isRegularFile(repository.resolve(".gitignore"));
        }
    }

    public List<GitIgnoreRule> gitIgnoreRules() {
        try {
            String rootText = git.run(AnalysisProgress.NONE, "rev-parse", "--show-toplevel").trim();
            if (rootText.isEmpty()) return List.of();
            Path root = Path.of(rootText).toAbsolutePath().normalize();
            String listed = git.run(AnalysisProgress.NONE, "ls-files", "--full-name", "--cached", "--others",
                    "--exclude-standard", "--", ".gitignore", "**/.gitignore");
            List<GitIgnoreRule> rules = new ArrayList<>();
            for (String relative : listed.lines().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList()) {
                Path file = root.resolve(relative).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
                for (String line : Files.readAllLines(file)) {
                    String pattern = line.trim();
                    if (!pattern.isEmpty() && !pattern.startsWith("#") && !pattern.startsWith("!")) {
                        rules.add(new GitIgnoreRule(relative, pattern));
                    }
                }
            }
            return List.copyOf(rules);
        } catch (GitCommandException | IOException ignored) {
            return List.of();
        }
    }

    public List<String> branches(AnalysisProgress progress) throws GitCommandException {
        String output = git.run(progress, "for-each-ref", "--format=%(refname:short)",
                "refs/heads", "refs/remotes");
        return output.lines()
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !name.endsWith("/HEAD"))
                .distinct()
                .sorted()
                .toList();
    }

    public String currentBranch(AnalysisProgress progress) throws GitCommandException {
        return git.run(progress, "branch", "--show-current").trim();
    }

    public HistoryResult analyze(String branch, int maximumCommits, int sampleEvery,
                                 AnalysisProgress progress) throws GitCommandException {
        return analyze(branch, maximumCommits, sampleEvery, hasGitIgnore(), progress);
    }

    public HistoryResult analyze(String branch, int maximumCommits, int sampleEvery,
                                 boolean respectGitIgnore, AnalysisProgress progress) throws GitCommandException {
        List<CommitInfo> all = readCommits(branch, maximumCommits * sampleEvery, progress);
        List<CommitInfo> sampledNewestFirst = new ArrayList<>();
        for (int i = 0; i < all.size() && sampledNewestFirst.size() < maximumCommits; i += sampleEvery) {
            sampledNewestFirst.add(all.get(i));
        }

        return analyzeCommits(branch, sampledNewestFirst, respectGitIgnore, progress);
    }

    public HistoryResult analyzeAll(String branch, boolean respectGitIgnore,
                                    AnalysisProgress progress) throws GitCommandException {
        return analyzeCommits(branch, readCommits(branch, 0, progress), respectGitIgnore, progress);
    }

    private HistoryResult analyzeCommits(String branch, List<CommitInfo> sampledNewestFirst,
                                         boolean respectGitIgnore, AnalysisProgress progress)
            throws GitCommandException {
        List<LocSnapshot> snapshots = new ArrayList<>();
        Map<String, Map<CountingMetric, Integer>> tokenCache = new HashMap<>();
        for (int i = sampledNewestFirst.size() - 1; i >= 0; i--) {
            if (progress.isCancelled()) throw new GitCommandException("Analysis cancelled");
            progress.update((sampledNewestFirst.size() - 1 - i) / (double) Math.max(1, sampledNewestFirst.size()),
                    "Counting " + sampledNewestFirst.get(i).shortHash());
            CommitInfo commit = sampledNewestFirst.get(i);
            snapshots.add(new LocSnapshot(commit, countLines(commit.hash(), respectGitIgnore, progress, tokenCache)));
        }
        return new HistoryResult(branch, snapshots);
    }

    public LocSnapshot snapshot(String ref, AnalysisProgress progress) throws GitCommandException {
        return snapshot(ref, hasGitIgnore(), progress);
    }

    public LocSnapshot snapshot(String ref, boolean respectGitIgnore, AnalysisProgress progress) throws GitCommandException {
        List<CommitInfo> commits = readCommits(ref, 1, progress);
        if (commits.isEmpty()) throw new GitCommandException("No commit found for " + ref);
        CommitInfo commit = commits.get(0);
        return new LocSnapshot(commit, countLines(commit.hash(), respectGitIgnore, progress, new HashMap<>()));
    }

    private List<CommitInfo> readCommits(String branch, int limit, AnalysisProgress progress)
            throws GitCommandException {
        List<String> arguments = new ArrayList<>(List.of(
                "log", "--first-parent", "--format=%H%x09%ct%x09%s"));
        if (limit > 0) {
            arguments.add("-n");
            arguments.add(Integer.toString(limit));
        }
        arguments.add("--end-of-options");
        arguments.add(branch);
        String output = git.run(progress, arguments.toArray(String[]::new));
        List<CommitInfo> commits = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] fields = line.split("\\t", 3);
            if (fields.length == 3) {
                commits.add(new CommitInfo(fields[0], Instant.ofEpochSecond(Long.parseLong(fields[1])), fields[2]));
            }
        }
        return commits;
    }

    private Map<String, FileMetrics> countLines(String commit, boolean respectGitIgnore, AnalysisProgress progress,
                                                 Map<String, Map<CountingMetric, Integer>> tokenCache)
            throws GitCommandException {
        String output = git.run(progress, "grep", "-I", "-n", "-e", "^", commit, "--", ".");
        Map<String, MutableMetrics> counted = new LinkedHashMap<>();
        Map<String, LineClassifier.State> states = new HashMap<>();
        for (String line : output.lines().toList()) {
            Matcher matcher = GREP_LINE.matcher(line);
            if (!matcher.matches()) continue;
            String path = matcher.group(1);
            if (EXCLUDED_PATH.matcher(path).find()) continue;
            int number = Integer.parseInt(matcher.group(2));
            MutableMetrics value = counted.computeIfAbsent(path, ignored -> new MutableMetrics());
            value.loc = Math.max(value.loc, number);
            if (LineClassifier.hasCode(path, matcher.group(3),
                    states.computeIfAbsent(path, ignored -> new LineClassifier.State()))) value.rloc++;
        }
        if (respectGitIgnore && hasGitIgnore() && !counted.isEmpty()) {
            String input = String.join("\0", counted.keySet()) + "\0";
            String ignored = git.runWithInput(progress, input, "check-ignore", "--no-index", "-z", "--stdin");
            Set<String> ignoredPaths = new HashSet<>(List.of(ignored.split("\0")));
            ignoredPaths.forEach(counted::remove);
        }
        // Git blob IDs let unchanged files reuse token counts across historical commits.
        Map<String, String> blobs = new HashMap<>();
        for (String record : git.run(progress, "ls-tree", "-r", "-z", commit).split("\0")) {
            int tab = record.indexOf('\t');
            if (tab < 0) continue;
            String[] metadata = record.substring(0, tab).split(" ");
            if (metadata.length == 3 && metadata[1].equals("blob")) blobs.put(record.substring(tab + 1), metadata[2]);
        }
        Map<String, FileMetrics> result = new LinkedHashMap<>();
        for (var entry : counted.entrySet()) {
            if (progress.isCancelled()) throw new GitCommandException("Analysis cancelled");
            Map<CountingMetric, Integer> counts = new EnumMap<>(CountingMetric.class);
            counts.put(CountingMetric.LOC, entry.getValue().loc);
            counts.put(CountingMetric.RLOC, entry.getValue().rloc);
            String blob = blobs.get(entry.getKey());
            Map<CountingMetric, Integer> tokens = tokenCache.get(blob);
            if (tokens == null) {
                String text = git.run(progress, "show", commit + ":" + entry.getKey());
                tokens = new EnumMap<>(CountingMetric.class);
                for (TokenCounter counter : tokenCounters) tokens.put(counter.metric(), counter.count(text));
                if (blob != null) tokenCache.put(blob, Map.copyOf(tokens));
            }
            counts.putAll(tokens);
            result.put(entry.getKey(), new FileMetrics(counts));
        }
        return result;
    }

    private static final class MutableMetrics {
        private int loc;
        private int rloc;
    }
}
