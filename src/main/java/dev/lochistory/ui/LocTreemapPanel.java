package dev.lochistory.ui;

import dev.lochistory.model.CountingMetric;

import com.intellij.ui.JBColor;
import dev.lochistory.model.LocSnapshot;
import dev.lochistory.model.FileMetrics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Set;
import java.util.TreeSet;

final class LocTreemapPanel extends JPanel {
    private final List<Cell> cells = new ArrayList<>();
    private LocSnapshot snapshot;
    private Consumer<PathNode> selectionListener = ignored -> {};
    private Consumer<PathNode> excludeListener = ignored -> {};
    private CountingMetric metric = CountingMetric.RLOC;
    private LocSnapshot diffFrom;
    private LocSnapshot diffTo;
    private Set<String> collapsedDirectories = Set.of();

    LocTreemapPanel() {
        setPreferredSize(new Dimension(620, 330));
        setToolTipText("");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                Cell hit = hit(event.getPoint());
                if (hit == null) return;
                if (SwingUtilities.isRightMouseButton(event) && (hit.node.file() || !hit.node.path().isEmpty())) {
                    showExcludeMenu(event, hit.node);
                }
                else if (SwingUtilities.isLeftMouseButton(event)) selectionListener.accept(hit.node);
            }
        });
    }

    void setSelectionListener(Consumer<PathNode> listener) {
        selectionListener = listener;
    }

    void setExcludeListener(Consumer<PathNode> listener) {
        excludeListener = listener;
    }

    private void showExcludeMenu(MouseEvent event, PathNode node) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem exclude = new JMenuItem(LocHistoryPanel.exclusionActionLabel(
                node, !node.file() && collapsedDirectories.contains(node.path())));
        exclude.addActionListener(ignored -> excludeListener.accept(node));
        menu.add(exclude);
        menu.show(this, event.getX(), event.getY());
    }

    void showSnapshot(LocSnapshot value, CountingMetric displayMetric, Set<String> collapsed) {
        snapshot = value;
        metric = displayMetric;
        collapsedDirectories = Set.copyOf(collapsed);
        diffFrom = null;
        diffTo = null;
        repaint();
    }

    void showDiff(LocSnapshot from, LocSnapshot to, CountingMetric displayMetric, Set<String> collapsed) {
        snapshot = to;
        diffFrom = from;
        diffTo = to;
        metric = displayMetric;
        collapsedDirectories = Set.copyOf(collapsed);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics raw) {
        super.paintComponent(raw);
        cells.clear();
        if (snapshot == null || snapshot.metricsByFile().isEmpty()) return;
        boolean diff = diffFrom != null && diffTo != null;
        Item root = diff ? diffTree(diffFrom, diffTo, metric) : tree(snapshot, metric);
        int banner = diff ? 25 : 0;
        if (root.lines > 0) {
            layout(root, new Rectangle2D.Double(2, 2 + banner, Math.max(1, getWidth() - 4),
                    Math.max(1, getHeight() - 4 - banner)), diff);
        }
        Graphics2D g = (Graphics2D) raw.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (Cell cell : cells) drawCell(g, cell);
            if (diff) drawDiffBanner(g, root);
        } finally {
            g.dispose();
        }
    }

    private void drawCell(Graphics2D g, Cell cell) {
        Rectangle r = cell.bounds;
        if (r.width < 2 || r.height < 2) return;
        if (!cell.node.file()) {
            g.setColor(new JBColor(new Color(230, 232, 235), new Color(47, 49, 54)));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(JBColor.border());
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        } else {
            Color fileTypeColor = extensionColor(cell.node.path());
            Color color = cell.diff && cell.node.lines() < 0 ? desaturate(fileTypeColor) : fileTypeColor;
            g.setColor(color);
            g.fillRect(r.x + 1, r.y + 1, Math.max(0, r.width - 2), Math.max(0, r.height - 2));
            g.setColor(color.darker());
            g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
        }
        String label = cell.node.name() + " (" + (cell.diff ? signed(cell.node.lines()) : cell.node.lines()) + ")";
        g.setColor(cell.node.file() ? Color.WHITE : getForeground());
        if (r.height > r.width * 1.35 && r.width > 18 && r.height > 46) {
            Graphics2D vertical = (Graphics2D) g.create();
            vertical.setClip(r.x + 2, r.y + 2, r.width - 4, r.height - 4);
            vertical.translate(r.x + Math.min(16, r.width - 4), r.y + r.height - 4);
            vertical.rotate(-Math.PI / 2);
            vertical.drawString(label, 0, 0);
            vertical.dispose();
        } else if (r.width > 46 && r.height > 20) {
            Graphics2D horizontal = (Graphics2D) g.create();
            horizontal.setClip(r.x + 3, r.y + 2, r.width - 6, r.height - 4);
            horizontal.drawString(label, r.x + 4, r.y + 15);
            horizontal.dispose();
        }
    }

    private void layout(Item item, Rectangle2D.Double bounds, boolean diff) {
        Rectangle pixelBounds = bounds.getBounds();
        boolean collapsed = collapsedDirectories.contains(item.path);
        cells.add(new Cell(new PathNode(item.name + (collapsed ? " [excluded]" : ""), item.path,
                item.file, item.loc, item.rloc, item.count, metric), pixelBounds, diff));
        if (collapsed || item.children.isEmpty() || bounds.width < 5 || bounds.height < 5) return;
        double header = item.path.isEmpty() ? 0 : Math.min(19, bounds.height / 4);
        Rectangle2D.Double inside = new Rectangle2D.Double(bounds.x + 2, bounds.y + header + 2,
                Math.max(1, bounds.width - 4), Math.max(1, bounds.height - header - 4));
        List<Item> children = item.children.values().stream()
                .sorted(Comparator.comparingInt((Item child) -> child.lines).reversed()).toList();
        int total = children.stream().mapToInt(child -> child.lines).sum();
        double availableArea = inside.width * inside.height;
        List<WeightedItem> pending = children.stream()
                .map(child -> new WeightedItem(child, availableArea * child.lines / Math.max(1.0, total)))
                .toList();
        squarify(pending, inside, diff);
    }

    private void squarify(List<WeightedItem> items, Rectangle2D.Double bounds, boolean diff) {
        List<WeightedItem> row = new ArrayList<>();
        Rectangle2D.Double remaining = new Rectangle2D.Double(bounds.x, bounds.y, bounds.width, bounds.height);
        for (WeightedItem item : items) {
            double side = Math.max(1, Math.min(remaining.width, remaining.height));
            List<WeightedItem> candidate = new ArrayList<>(row);
            candidate.add(item);
            if (row.isEmpty() || worst(candidate, side) <= worst(row, side)) {
                row.add(item);
            } else {
                remaining = layoutRow(row, remaining, diff);
                row = new ArrayList<>();
                row.add(item);
            }
        }
        if (!row.isEmpty()) layoutRow(row, remaining, diff);
    }

    private Rectangle2D.Double layoutRow(List<WeightedItem> row, Rectangle2D.Double bounds, boolean diff) {
        double area = row.stream().mapToDouble(WeightedItem::area).sum();
        if (bounds.width >= bounds.height) {
            double width = Math.min(bounds.width, area / Math.max(1, bounds.height));
            double y = bounds.y;
            for (int i = 0; i < row.size(); i++) {
                double height = i == row.size() - 1 ? bounds.y + bounds.height - y : row.get(i).area / Math.max(1, width);
                layout(row.get(i).item, new Rectangle2D.Double(bounds.x, y, Math.max(1, width), Math.max(1, height)), diff);
                y += height;
            }
            return new Rectangle2D.Double(bounds.x + width, bounds.y, Math.max(0, bounds.width - width), bounds.height);
        }
        double height = Math.min(bounds.height, area / Math.max(1, bounds.width));
        double x = bounds.x;
        for (int i = 0; i < row.size(); i++) {
            double width = i == row.size() - 1 ? bounds.x + bounds.width - x : row.get(i).area / Math.max(1, height);
            layout(row.get(i).item, new Rectangle2D.Double(x, bounds.y, Math.max(1, width), Math.max(1, height)), diff);
            x += width;
        }
        return new Rectangle2D.Double(bounds.x, bounds.y + height, bounds.width, Math.max(0, bounds.height - height));
    }

    private static double worst(List<WeightedItem> row, double side) {
        if (row.isEmpty()) return Double.POSITIVE_INFINITY;
        double sum = row.stream().mapToDouble(WeightedItem::area).sum();
        double max = row.stream().mapToDouble(WeightedItem::area).max().orElse(1);
        double min = row.stream().mapToDouble(WeightedItem::area).min().orElse(1);
        double sideSquared = side * side;
        return Math.max(sideSquared * max / (sum * sum), sum * sum / (sideSquared * Math.max(1, min)));
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        Cell hit = hit(event.getPoint());
        if (hit == null) return null;
        if (hit.diff) {
            int resultLoc = diffTo.linesFor(hit.node.path(), hit.node.file(), false);
            int resultRloc = diffTo.linesFor(hit.node.path(), hit.node.file(), true);
            return "<html><b>" + hit.node.path() + "</b><br>LOC: <b>" + signed(hit.node.loc()) +
                    " → " + String.format("%,d", resultLoc) + "</b><br>RLOC: <b>" + signed(hit.node.rloc()) +
                    " → " + String.format("%,d", resultRloc) + "</b><br>" +
                    (metric == CountingMetric.LOC || metric == CountingMetric.RLOC ? "" :
                            metric + ": <b>" + signed(hit.node.lines()) + " → " +
                            String.format("%,d", diffTo.linesFor(hit.node.path(), hit.node.file(), metric)) + "</b><br>") +
                    diffFrom.commit().shortHash() + " → " + diffTo.commit().shortHash() + "</html>";
        }
        return "<html><b>" + hit.node.path() + "</b><br>" + String.format("%,d", hit.node.lines()) +
                (" " + hit.node.metric()) + "<br>RLOC/LOC: " +
                String.format("%.1f%%", hit.node.loc() == 0 ? 0 : hit.node.rloc() * 100.0 / hit.node.loc()) + "</html>";
    }

    private Cell hit(Point point) {
        for (int i = cells.size() - 1; i >= 0; i--) if (cells.get(i).bounds.contains(point)) return cells.get(i);
        return null;
    }

    private static Item tree(LocSnapshot snapshot, CountingMetric metric) {
        Item root = new Item("Project", "", false);
        snapshot.metricsByFile().forEach((path, metrics) -> {
            int lines = metrics.value(metric);
            String[] parts = path.split("/");
            Item current = root;
            current.lines += lines;
            current.loc += metrics.loc();
            current.rloc += metrics.rloc();
            current.count += lines;
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (full.length() > 0) full.append('/');
                full.append(parts[i]);
                boolean file = i == parts.length - 1;
                String name = parts[i];
                String key = (file ? "f:" : "d:") + name;
                String nodePath = full.toString();
                Item child = current.children.computeIfAbsent(key, ignored -> new Item(name, nodePath, file));
                child.lines += lines;
                child.loc += metrics.loc();
                child.rloc += metrics.rloc();
                child.count += lines;
                current = child;
            }
        });
        return root;
    }

    private static Item diffTree(LocSnapshot from, LocSnapshot to, CountingMetric metric) {
        Item root = new Item("Changes", "", false);
        Set<String> paths = new TreeSet<>(from.metricsByFile().keySet());
        paths.addAll(to.metricsByFile().keySet());
        for (String path : paths) {
            FileMetrics before = from.metricsByFile().getOrDefault(path, new FileMetrics(0, 0));
            FileMetrics after = to.metricsByFile().getOrDefault(path, new FileMetrics(0, 0));
            int deltaLoc = after.loc() - before.loc();
            int deltaRloc = after.rloc() - before.rloc();
            int shownDelta = after.value(metric) - before.value(metric);
            if (shownDelta == 0) continue;
            String[] parts = path.split("/");
            Item current = root;
            current.lines += Math.abs(shownDelta);
            current.loc += deltaLoc;
            current.rloc += deltaRloc;
            current.count += shownDelta;
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (full.length() > 0) full.append('/');
                full.append(parts[i]);
                boolean file = i == parts.length - 1;
                String name = parts[i];
                String key = (file ? "f:" : "d:") + name;
                String nodePath = full.toString();
                Item child = current.children.computeIfAbsent(key, ignored -> new Item(name, nodePath, file));
                child.lines += Math.abs(shownDelta);
                child.loc += deltaLoc;
                child.rloc += deltaRloc;
                child.count += shownDelta;
                current = child;
            }
        }
        return root;
    }

    private void drawDiffBanner(Graphics2D g, Item root) {
        g.setColor(getForeground());
        String labelMetric = metric.toString();
        int delta = root.count;
        String label = "Diff " + diffFrom.commit().shortHash() + " → " + diffTo.commit().shortHash() +
                "   Total: " + signed(delta) + " " + labelMetric +
                "   (normal color added, grayscale removed; area = absolute change)";
        g.drawString(label, 6, 18);
        if (root.lines == 0) {
            g.setColor(JBColor.GRAY);
            g.drawString("No " + labelMetric + " changes in this range", 6, 43);
        }
    }

    private static String signed(int value) {
        return (value > 0 ? "+" : "") + String.format("%,d", value);
    }

    private static Color extensionColor(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        float hue = Math.floorMod(extension.hashCode(), 360) / 360f;
        return Color.getHSBColor(hue, 0.72f, 0.72f);
    }

    private static Color desaturate(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return Color.getHSBColor(hsb[0], 0.06f, Math.max(0.58f, hsb[2]));
    }

    private record Cell(PathNode node, Rectangle bounds, boolean diff) {}
    private record WeightedItem(Item item, double area) {}

    private static final class Item {
        private final String name;
        private final String path;
        private final boolean file;
        private final Map<String, Item> children = new LinkedHashMap<>();
        private int lines;
        private int loc;
        private int rloc;
        private int count;
        private Item(String name, String path, boolean file) {
            this.name = name;
            this.path = path;
            this.file = file;
        }
    }
}
