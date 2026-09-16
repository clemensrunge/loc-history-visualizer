package dev.lochistory.ui;

import dev.lochistory.model.CountingMetric;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.JBColor;
import dev.lochistory.analysis.GitCommandException;
import dev.lochistory.analysis.AnalysisProgress;
import dev.lochistory.analysis.LocHistoryAnalyzer;
import dev.lochistory.analysis.GitIgnoreRule;
import dev.lochistory.model.HistoryResult;
import dev.lochistory.model.LocSnapshot;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class LocHistoryPanel extends JPanel {
    private int oldTooltipDelay;
    private int oldReshowDelay;
    private final Project project;
    private final JComboBox<String> branches = new JComboBox<>();
    private final JSpinner maximumCommits = new JSpinner(new SpinnerNumberModel(60, 1, 999_999, 10));
    private final JSpinner sampleEvery = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JButton analyze = new JButton("Analyze");
    private final RunGreenButton analyzeAll = new RunGreenButton("Analyze All");
    private final JCheckBox respectGitIgnore = new JCheckBox("Respect .gitignore", true);
    private final JComboBox<String> excludedTypes = new JComboBox<>();
    private final JComboBox<CountingMetric> metric = new JComboBox<>(CountingMetric.values());
    private final JBLabel status = new JBLabel("Finding Git branches…");
    private final JTree tree = new JTree(new DefaultMutableTreeNode("No history loaded"));
    private final LocChartPanel chart = new LocChartPanel();
    private final LocTreemapPanel treemap = new LocTreemapPanel();
    private HistoryResult result;
    private HistoryResult rawResult;
    private HistoryResult visualResult;
    private boolean busy;
    private LocSnapshot diffFrom;
    private LocSnapshot diffTo;
    private final Set<String> excludedExtensions = new TreeSet<>();
    private final Set<String> excludedDirectories = new TreeSet<>();
    private List<GitIgnoreRule> gitIgnoreRules = List.of();
    private boolean updatingExcludedTypes;
    private boolean lastAnalysisAll;

    LocHistoryPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        JSpinner.NumberEditor commitEditor = new JSpinner.NumberEditor(maximumCommits, "0");
        commitEditor.getTextField().setColumns(6);
        maximumCommits.setEditor(commitEditor);
        metric.setSelectedItem(CountingMetric.RLOC);
        add(buildToolbar(), BorderLayout.NORTH);

        tree.setRootVisible(true);
        tree.addTreeSelectionListener(this::selectionChanged);
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeShowTreeMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeShowTreeMenu(event); }
        });
        JSplitPane visualSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treemap, chart);
        visualSplit.setResizeWeight(0.58);
        visualSplit.setBorder(BorderFactory.createEmptyBorder());
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(tree), visualSplit);
        mainSplit.setResizeWeight(0.28);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        add(mainSplit, BorderLayout.CENTER);
        treemap.setSelectionListener(path -> chart.showSeries(result.snapshots(), path));
        treemap.setExcludeListener(this::excludeFileType);
        chart.setExcludeListener(this::excludeFileType);
        chart.setRangeListener((from, to) -> {
            diffFrom = visualSnapshot(from);
            diffTo = visualSnapshot(to);
            treemap.showDiff(diffFrom, diffTo, selectedMetric(), excludedDirectories);
        }, () -> {
            diffFrom = null;
            diffTo = null;
            if (result != null && !result.snapshots().isEmpty()) {
                treemap.showSnapshot(visualResult.snapshots().get(visualResult.snapshots().size() - 1),
                        selectedMetric(), excludedDirectories);
            }
        });

        analyze.addActionListener(event -> {
            analyzeAll.clearHighlight();
            analyzeSelectedBranch();
        });
        analyzeAll.addActionListener(event -> {
            analyzeAll.clearHighlight();
            analyzeEntireBranch();
        });
        metric.addActionListener(event -> refreshVisuals());
        respectGitIgnore.addActionListener(event -> {
            refreshExcludedTypesDropdown();
            if (rawResult != null && !busy) startAnalysis(lastAnalysisAll);
        });
        respectGitIgnore.setVisible(false);
        refreshExcludedTypesDropdown();
        excludedTypes.addActionListener(event -> removeSelectedExclusion());
        loadBranches();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ToolTipManager manager = ToolTipManager.sharedInstance();
        oldTooltipDelay = manager.getInitialDelay();
        oldReshowDelay = manager.getReshowDelay();
        manager.setInitialDelay(100);
        manager.setReshowDelay(20);
    }

    @Override
    public void removeNotify() {
        ToolTipManager manager = ToolTipManager.sharedInstance();
        manager.setInitialDelay(oldTooltipDelay);
        manager.setReshowDelay(oldReshowDelay);
        super.removeNotify();
    }

    private JComponent buildToolbar() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        branches.setPrototypeDisplayValue("origin/a-long-feature-branch");
        excludedTypes.setPrototypeDisplayValue("Excluded: 999 (manual 99 + .gitignore 900)");
        JBLabel branchLabel = new JBLabel("Branch:");
        explain(branchLabel, "Select the local or remote Git branch to inspect. Analysis never checks out or changes the branch.");
        explain(branches, "The Git branch whose first-parent commit history will be analyzed without changing your working tree.");
        controls.add(branchLabel);
        controls.add(branches);
        JBLabel commitsLabel = new JBLabel("Commits:");
        explain(commitsLabel, "Maximum number of historical snapshots to include in the graph.");
        explain(maximumCommits, "Maximum sampled commits to analyze. Larger values provide more history but take longer.");
        controls.add(commitsLabel);
        controls.add(maximumCommits);
        JBLabel sampleLabel = new JBLabel("Sample every:");
        explain(sampleLabel, "Controls the distance between analyzed commits on the branch's first-parent history.");
        explain(sampleEvery, "Analyze every Nth commit. Increase this to cover a longer period with less processing.");
        explain(analyze, "Read the selected branch's commits and calculate LOC, RLOC, OpenAI tokens and Claude tokens for every included file and folder.");
        explain(analyzeAll, "Analyze every first-parent commit on the selected branch back to its initial commit. This runs in the background and can be cancelled, but large histories may take substantial CPU time.");
        explain(respectGitIgnore, "Apply all repository and nested .gitignore rules. Changing this after analysis automatically reruns the last analysis mode. Dot-directories such as .github and .cache are always excluded.");
        explain(excludedTypes, "Shows manually excluded file types and directories plus active .gitignore rules. Select a manual exclusion to remove it; .gitignore entries are controlled by the checkbox.");
        explain(metric, "Choose physical LOC, RLOC excluding blank and comment-only lines, OpenAI source tokens using o200k_base, or Claude 4.8+ tokens (including Sonnet 5 and Fable 5), using ctok-java’s reconstructed 4.8+ family. Fixed message overhead is excluded. ctok does not model Opus 5’s free trailing ASCII whitespace. This switches the tree, treemap, graph, totals, and statistics.");
        controls.add(sampleLabel);
        controls.add(sampleEvery);
        controls.add(analyze);
        controls.add(analyzeAll);
        controls.add(respectGitIgnore);
        controls.add(excludedTypes);
        controls.add(metric);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(controls, BorderLayout.NORTH);
        explain(status, "Shows analysis progress, sampled commits, the selected metric total, and the latest commit, followed by RLOC/LOC percentage, OpenAI tokens (jtokkit o200k_base), Claude tokens (ctok-java reconstructed 4.8+ family, including Fable), and OpenAI/Claude percentage for the latest analyzed commit. Token totals sum per-file content counts and exclude fixed message overhead.");
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
        wrapper.add(status, BorderLayout.SOUTH);
        return wrapper;
    }

    private static void explain(JComponent component, String text) {
        component.setToolTipText("<html><div width='360'>" + text + "</div></html>");
        for (Component child : component.getComponents()) {
            if (child instanceof JComponent nested) explain(nested, text);
        }
    }

    private LocHistoryAnalyzer analyzer() throws GitCommandException {
        String basePath = project.getBasePath();
        if (basePath == null) throw new GitCommandException("This project has no local base directory");
        return new LocHistoryAnalyzer(Path.of(basePath));
    }

    private void loadBranches() {
        setBusy(true, "Finding Git branches…");
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Find Git Branches", false) {
            private List<String> found = List.of();
            private String current = "";
            private Exception failure;
            private boolean gitIgnoreFound;
            private List<GitIgnoreRule> loadedRules = List.of();

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    LocHistoryAnalyzer analyzer = analyzer();
                    AnalysisProgress progress = adapt(indicator);
                    found = analyzer.branches(progress);
                    current = analyzer.currentBranch(progress);
                    gitIgnoreFound = analyzer.hasGitIgnore();
                    loadedRules = analyzer.gitIgnoreRules();
                } catch (Exception e) {
                    failure = e;
                }
            }

            @Override
            public void onFinished() {
                branches.removeAllItems();
                respectGitIgnore.setVisible(gitIgnoreFound);
                gitIgnoreRules = loadedRules;
                refreshExcludedTypesDropdown();
                found.forEach(branches::addItem);
                if (!current.isBlank()) branches.setSelectedItem(current);
                if (failure != null) {
                    setBusy(false, "Not a readable Git repository: " + failure.getMessage());
                } else if (found.isEmpty()) {
                    setBusy(false, "No branches with commits found");
                } else {
                    setBusy(false, found.size() + " branches found. Choose one and click Analyze.");
                }
            }
        });
    }

    private void analyzeSelectedBranch() {
        startAnalysis(false);
    }

    private void analyzeEntireBranch() {
        startAnalysis(true);
    }

    private void startAnalysis(boolean allCommits) {
        String branch = (String) branches.getSelectedItem();
        if (branch == null) return;
        int limit = (Integer) maximumCommits.getValue();
        int stride = (Integer) sampleEvery.getValue();
        boolean useGitIgnore = respectGitIgnore.isVisible() && respectGitIgnore.isSelected();
        setBusy(true, (allCommits ? "Analyzing complete history of " : "Analyzing ") + branch + "…");

        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                allCommits ? "Analyze Complete LOC History" : "Analyze LOC History", true) {
            private HistoryResult loaded;
            private Exception failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    LocHistoryAnalyzer analyzer = analyzer();
                    loaded = allCommits
                            ? analyzer.analyzeAll(branch, useGitIgnore, adapt(indicator))
                            : analyzer.analyze(branch, limit, stride, useGitIgnore, adapt(indicator));
                } catch (Exception e) {
                    failure = e;
                }
            }

            @Override
            public void onFinished() {
                if (failure != null) {
                    setBusy(false, "Analysis failed: " + failure.getMessage());
                    return;
                }
                rawResult = loaded;
                lastAnalysisAll = allCommits;
                visualResult = applyTypeExclusions(loaded);
                result = applyExclusions(loaded);
                if (allCommits) maximumCommits.setValue(Math.max(1, loaded.snapshots().size()));
                diffFrom = null;
                diffTo = null;
                chart.clearRange();
                if (loaded.snapshots().isEmpty()) {
                    setBusy(false, "No commits found on " + branch);
                    return;
                }
                refreshVisuals();
                setBusy(false, summaryText());
            }
        });
    }

    private void selectionChanged(TreeSelectionEvent event) {
        if (result == null) return;
        Object selected = tree.getLastSelectedPathComponent();
        if (selected instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof PathNode path) {
            chart.showSeries(result.snapshots(), path);
        }
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        analyze.setEnabled(!busy && branches.getItemCount() > 0);
        analyzeAll.setEnabled(!busy && branches.getItemCount() > 0);
        branches.setEnabled(!busy);
        maximumCommits.setEnabled(!busy);
        sampleEvery.setEnabled(!busy);
        respectGitIgnore.setEnabled(!busy);
        excludedTypes.setEnabled(!busy);
        status.setText(message);
    }

    private void maybeShowTreeMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        TreePath selection = tree.getPathForLocation(event.getX(), event.getY());
        if (selection == null) return;
        tree.setSelectionPath(selection);
        Object value = ((DefaultMutableTreeNode) selection.getLastPathComponent()).getUserObject();
        if (!(value instanceof PathNode node) || (!node.file() && node.path().isEmpty())) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem exclude = new JMenuItem(exclusionActionLabel(
                node, !node.file() && excludedDirectories.contains(node.path())));
        exclude.addActionListener(ignored -> excludeFileType(node));
        menu.add(exclude);
        menu.show(tree, event.getX(), event.getY());
    }

    private void excludeFileType(PathNode node) {
        boolean changed;
        if (node.file()) {
            changed = excludedExtensions.add(extensionOf(node.path()));
        } else if (excludedDirectories.contains(node.path())) {
            changed = excludedDirectories.remove(node.path());
        } else {
            changed = excludedDirectories.add(node.path());
        }
        if (changed) {
            refreshExcludedTypesDropdown();
            applyExclusionsAndRefresh(node.file() ? "" : node.path());
        }
    }

    private void removeSelectedExclusion() {
        if (updatingExcludedTypes || excludedTypes.getSelectedIndex() <= 0) return;
        String selected = (String) excludedTypes.getSelectedItem();
        if (selected.startsWith("Remove type: ")) {
            String label = selected.substring("Remove type: ".length());
            excludedExtensions.remove("files without an extension".equals(label) ? "" : label.substring(2));
        } else if (selected.startsWith("Remove directory: ")) {
            excludedDirectories.remove(selected.substring("Remove directory: ".length()));
        } else {
            updatingExcludedTypes = true;
            excludedTypes.setSelectedIndex(0);
            updatingExcludedTypes = false;
            return;
        }
        refreshExcludedTypesDropdown();
        applyExclusionsAndRefresh("");
    }

    private void refreshExcludedTypesDropdown() {
        updatingExcludedTypes = true;
        excludedTypes.removeAllItems();
        int manualCount = excludedExtensions.size() + excludedDirectories.size();
        int ruleCount = respectGitIgnore.isSelected() ? gitIgnoreRules.size() : 0;
        excludedTypes.addItem("Excluded: " + (manualCount + ruleCount) +
                " (manual " + manualCount + " + .gitignore " + ruleCount + ")");
        for (String extension : excludedExtensions) {
            excludedTypes.addItem("Remove type: " + (extension.isEmpty() ? "files without an extension" : "*." + extension));
        }
        for (String directory : excludedDirectories) excludedTypes.addItem("Remove directory: " + directory);
        excludedTypes.addItem("──────── .gitignore rules ────────");
        if (gitIgnoreRules.isEmpty()) excludedTypes.addItem("  (no exclusion rules found)");
        else gitIgnoreRules.forEach(rule -> excludedTypes.addItem("  " + rule.displayText()));
        excludedTypes.setSelectedIndex(0);
        updatingExcludedTypes = false;
    }

    private void applyExclusionsAndRefresh(String preferredPath) {
        if (rawResult == null) return;
        visualResult = applyTypeExclusions(rawResult);
        result = applyExclusions(rawResult);
        diffFrom = null;
        diffTo = null;
        chart.clearRange();
        refreshVisuals();
        selectTreePath(preferredPath);
    }

    private void selectTreePath(String path) {
        Object rootValue = tree.getModel().getRoot();
        if (!(rootValue instanceof DefaultMutableTreeNode root)) return;
        java.util.Enumeration<?> nodes = root.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object candidate = nodes.nextElement();
            if (candidate instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof PathNode value
                    && value.path().equals(path)) {
                tree.setSelectionPath(new TreePath(node.getPath()));
                tree.scrollPathToVisible(new TreePath(node.getPath()));
                return;
            }
        }
    }

    private HistoryResult applyExclusions(HistoryResult source) {
        HistoryResult typeFiltered = applyTypeExclusions(source);
        if (excludedDirectories.isEmpty()) return typeFiltered;
        List<LocSnapshot> snapshots = typeFiltered.snapshots().stream().map(snapshot -> {
            LinkedHashMap<String, dev.lochistory.model.FileMetrics> included = new LinkedHashMap<>();
            snapshot.metricsByFile().forEach((path, metrics) -> {
                boolean excluded = excludedDirectories.stream()
                        .anyMatch(directory -> path.equals(directory) || path.startsWith(directory + "/"));
                if (!excluded) included.put(path, metrics);
            });
            return new LocSnapshot(snapshot.commit(), included);
        }).toList();
        return new HistoryResult(source.branch(), snapshots);
    }

    private HistoryResult applyTypeExclusions(HistoryResult source) {
        if (excludedExtensions.isEmpty()) return source;
        List<LocSnapshot> snapshots = source.snapshots().stream().map(snapshot -> {
            LinkedHashMap<String, dev.lochistory.model.FileMetrics> included = new LinkedHashMap<>();
            snapshot.metricsByFile().forEach((path, metrics) -> {
                if (!excludedExtensions.contains(extensionOf(path))) included.put(path, metrics);
            });
            return new LocSnapshot(snapshot.commit(), included);
        }).toList();
        return new HistoryResult(source.branch(), snapshots);
    }

    private LocSnapshot visualSnapshot(LocSnapshot countedSnapshot) {
        if (visualResult == null) return countedSnapshot;
        return visualResult.snapshots().stream()
                .filter(snapshot -> snapshot.commit().hash().equals(countedSnapshot.commit().hash()))
                .findFirst().orElse(countedSnapshot);
    }

    static String fileTypeLabel(String path) {
        String extension = extensionOf(path);
        return extension.isEmpty() ? "files without an extension" : "*." + extension;
    }

    static String exclusionActionLabel(PathNode node, boolean currentlyExcluded) {
        if (node.file()) return "Exclude file type " + fileTypeLabel(node.path());
        return (currentlyExcluded ? "Include directory " : "Exclude directory ") + node.path();
    }

    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash + 1 || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private CountingMetric selectedMetric() { return (CountingMetric) metric.getSelectedItem(); }

    private void refreshVisuals() {
        if (result == null || result.snapshots().isEmpty()) return;
        CountingMetric selected = selectedMetric();
        LocSnapshot latest = result.snapshots().get(result.snapshots().size() - 1);
        LocSnapshot visualLatest = visualResult.snapshots().get(visualResult.snapshots().size() - 1);
        DefaultMutableTreeNode root = LocTreeBuilder.build(latest, visualLatest, selected, excludedDirectories);
        tree.setModel(new javax.swing.tree.DefaultTreeModel(root));
        if (diffFrom != null && diffTo != null) treemap.showDiff(diffFrom, diffTo, selected, excludedDirectories);
        else treemap.showSnapshot(visualLatest, selected, excludedDirectories);
        tree.setSelectionRow(0);
        tree.expandRow(0);
        if (!busy) status.setText(summaryText());
    }

    private String summaryText() {
        LocSnapshot latest = result.snapshots().get(result.snapshots().size() - 1);
        CountingMetric selected = selectedMetric();
        return result.snapshots().size() + " commits · " +
                String.format("%,d %s at %s · RLOC/LOC: %.1f%% · OpenAI tokens: %,d · %s: %,d · OpenAI/Claude: %.1f%%",
                        latest.linesFor("", false, selected), selected, latest.commit().shortHash(),
                        latest.percentage(CountingMetric.RLOC, CountingMetric.LOC),
                        latest.linesFor("", false, CountingMetric.OPENAI_TOKENS),
                        CountingMetric.CLAUDE_TOKENS, latest.linesFor("", false, CountingMetric.CLAUDE_TOKENS),
                        latest.percentage(CountingMetric.OPENAI_TOKENS, CountingMetric.CLAUDE_TOKENS));
    }

    private static AnalysisProgress adapt(ProgressIndicator indicator) {
        return new AnalysisProgress() {
            @Override
            public void update(double fraction, String message) {
                indicator.setFraction(fraction);
                indicator.setText2(message);
            }

            @Override
            public boolean isCancelled() {
                return indicator.isCanceled();
            }
        };
    }

    private static final class RunGreenButton extends JButton {
        private static final Color RUN_GREEN = JBColor.namedColor(
                "RunWidget.runIconColor", new JBColor(new Color(53, 153, 74), new Color(73, 156, 84)));

        private boolean highlighted = true;

        private RunGreenButton(String text) {
            super(text);
            setOpaque(false);
            setContentAreaFilled(false);
            setForeground(Color.WHITE);
        }

        private void clearHighlight() {
            if (!highlighted) return;
            highlighted = false;
            setContentAreaFilled(true);
            Color normalForeground = UIManager.getColor("Button.foreground");
            if (normalForeground != null) setForeground(normalForeground);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!highlighted) {
                super.paintComponent(graphics);
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = getModel().isPressed() ? RUN_GREEN.darker()
                        : getModel().isRollover() ? RUN_GREEN.brighter() : RUN_GREEN;
                g.setColor(isEnabled() ? color : new JBColor(new Color(145, 165, 149), new Color(72, 86, 75)));
                int inset = 3;
                g.fillRoundRect(inset, inset, Math.max(0, getWidth() - inset * 2),
                        Math.max(0, getHeight() - inset * 2), 7, 7);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
