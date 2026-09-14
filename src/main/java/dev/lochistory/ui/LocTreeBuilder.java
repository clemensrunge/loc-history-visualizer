package dev.lochistory.ui;

import dev.lochistory.model.LocSnapshot;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

final class LocTreeBuilder {
    private LocTreeBuilder() {}

    static DefaultMutableTreeNode build(LocSnapshot snapshot, LocSnapshot rollupSource,
                                        boolean showRloc, Set<String> collapsedDirectories) {
        MutablePath root = new MutablePath("Project", "", false);
        snapshot.metricsByFile().forEach((path, metrics) -> insert(root, path, metrics.loc(), metrics.rloc()));
        collapsedDirectories.forEach(path -> insertCollapsed(root, path,
                rollupSource.linesFor(path, false, false), rollupSource.linesFor(path, false, true)));
        return freeze(root, showRloc, collapsedDirectories);
    }

    private static void insertCollapsed(MutablePath root, String path, int loc, int rloc) {
        MutablePath current = root;
        StringBuilder full = new StringBuilder();
        for (String part : path.split("/")) {
            if (full.length() > 0) full.append('/');
            full.append(part);
            String nodePath = full.toString();
            current = current.children.computeIfAbsent("0:" + part,
                    ignored -> new MutablePath(part, nodePath, false));
        }
        current.loc = loc;
        current.rloc = rloc;
    }

    private static void insert(MutablePath root, String path, int loc, int rloc) {
        String[] parts = path.split("/");
        MutablePath current = root;
        current.loc += loc;
        current.rloc += rloc;
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (full.length() > 0) full.append('/');
            full.append(parts[i]);
            boolean file = i == parts.length - 1;
            String name = parts[i];
            String key = (file ? "1:" : "0:") + name;
            final String nodePath = full.toString();
            MutablePath child = current.children.computeIfAbsent(key,
                    ignored -> new MutablePath(name, nodePath, file));
            child.loc += loc;
            child.rloc += rloc;
            current = child;
        }
    }

    private static DefaultMutableTreeNode freeze(MutablePath value, boolean showRloc,
                                                 Set<String> collapsedDirectories) {
        boolean collapsed = collapsedDirectories.contains(value.path);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                new PathNode(value.name + (collapsed ? " [excluded]" : ""), value.path,
                        value.file, value.loc, value.rloc, showRloc));
        if (!collapsed) value.children.values().forEach(child -> node.add(freeze(child, showRloc, collapsedDirectories)));
        return node;
    }

    private static final class MutablePath {
        private final String name;
        private final String path;
        private final boolean file;
        private final Map<String, MutablePath> children = new TreeMap<>();
        private int loc;
        private int rloc;

        private MutablePath(String name, String path, boolean file) {
            this.name = name;
            this.path = path;
            this.file = file;
        }
    }
}
