package dev.lochistory.ui;

import com.intellij.ui.JBColor;
import dev.lochistory.model.LocSnapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class LocChartPanel extends JPanel {
    private static final int LEFT = 82;
    private static final int RIGHT = 18;
    private static final int TOP = 28;
    private static final int BOTTOM = 40;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private List<LocSnapshot> snapshots = List.of();
    private String path = "";
    private boolean file;
    private String title = "Project";
    private boolean showRloc = true;
    private int hoverIndex = -1;
    private int rangeStart = -1;
    private int rangeEnd = -1;
    private BiConsumer<LocSnapshot, LocSnapshot> rangeListener = (from, to) -> {};
    private Runnable rangeClearListener = () -> {};
    private Consumer<PathNode> excludeListener = ignored -> {};

    LocChartPanel() {
        setPreferredSize(new Dimension(520, 270));
        setMinimumSize(new Dimension(260, 170));
        setToolTipText("");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                hoverIndex = nearestIndex(event.getX());
                repaint();
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (rangeStart < 0) return;
                rangeEnd = nearestIndex(event.getX());
                fireRange();
                repaint();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) return;
                rangeStart = nearestIndex(event.getX());
                rangeEnd = rangeStart;
                repaint();
            }

            @Override public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event)) {
                    showExcludeMenu(event);
                    return;
                }
                rangeEnd = nearestIndex(event.getX());
                if (rangeStart == rangeEnd) {
                    rangeStart = -1;
                    rangeEnd = -1;
                    rangeClearListener.run();
                } else {
                    fireRange();
                }
                repaint();
            }

            @Override public void mouseExited(MouseEvent event) {
                hoverIndex = -1;
                repaint();
            }
        });
    }

    void setRangeListener(BiConsumer<LocSnapshot, LocSnapshot> listener, Runnable clearListener) {
        rangeListener = listener;
        rangeClearListener = clearListener;
    }

    void setExcludeListener(Consumer<PathNode> listener) {
        excludeListener = listener;
    }

    private void showExcludeMenu(MouseEvent event) {
        if (!file || path.isEmpty()) return;
        PathNode node = new PathNode(path.substring(path.lastIndexOf('/') + 1), path, true, 0, 0, showRloc);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem exclude = new JMenuItem("Exclude " + LocHistoryPanel.fileTypeLabel(path));
        exclude.addActionListener(ignored -> excludeListener.accept(node));
        menu.add(exclude);
        menu.show(this, event.getX(), event.getY());
    }

    void clearRange() {
        rangeStart = -1;
        rangeEnd = -1;
        repaint();
    }

    void showSeries(List<LocSnapshot> values, PathNode selected) {
        snapshots = values;
        path = selected.path();
        file = selected.file();
        title = selected.path().isEmpty() ? "Project" : selected.path();
        showRloc = selected.showRloc();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics raw) {
        super.paintComponent(raw);
        Graphics2D g = (Graphics2D) raw.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int plotWidth = Math.max(1, getWidth() - LEFT - RIGHT);
            int plotHeight = Math.max(1, getHeight() - TOP - BOTTOM);
            g.setColor(getForeground());
            g.drawString(title, LEFT, 17);
            if (snapshots.isEmpty()) {
                g.setColor(JBColor.GRAY);
                g.drawString("Analyze a branch to see history", LEFT, TOP + 30);
                return;
            }

            int observedMax = snapshots.stream().mapToInt(s -> s.linesFor(path, file, showRloc)).max().orElse(0);
            NiceScale scale = niceScale(observedMax, plotHeight);
            int max = scale.maximum();
            drawSelectedRange(g, plotWidth, plotHeight);
            drawYGrid(g, plotWidth, plotHeight, scale);
            g.setColor(JBColor.border());
            g.drawLine(LEFT, TOP, LEFT, TOP + plotHeight);
            g.drawLine(LEFT, TOP + plotHeight, LEFT + plotWidth, TOP + plotHeight);
            drawDateTicks(g, plotWidth, plotHeight);

            int previousX = -1;
            int previousY = -1;
            g.setColor(new JBColor(new Color(39, 110, 241), new Color(86, 156, 214)));
            g.setStroke(new BasicStroke(2.2f));
            for (int i = 0; i < snapshots.size(); i++) {
                int x = xAt(i, plotWidth);
                int count = snapshots.get(i).linesFor(path, file, showRloc);
                int y = TOP + plotHeight - (int) Math.round(count * plotHeight / (double) max);
                if (previousX >= 0) g.drawLine(previousX, previousY, x, y);
                g.fillOval(x - 3, y - 3, 7, 7);
                previousX = x;
                previousY = y;
            }
            drawHoverLine(g, plotWidth, plotHeight);

        } finally {
            g.dispose();
        }
    }

    private void drawSelectedRange(Graphics2D g, int plotWidth, int plotHeight) {
        if (rangeStart < 0 || rangeEnd < 0 || rangeStart == rangeEnd) return;
        int fromX = xAt(Math.min(rangeStart, rangeEnd), plotWidth);
        int toX = xAt(Math.max(rangeStart, rangeEnd), plotWidth);
        g.setColor(new JBColor(new Color(70, 140, 220, 28), new Color(80, 160, 240, 35)));
        g.fillRect(fromX, TOP, Math.max(1, toX - fromX), plotHeight);
    }

    private void drawHoverLine(Graphics2D g, int plotWidth, int plotHeight) {
        if (hoverIndex < 0 || hoverIndex >= snapshots.size()) return;
        int x = xAt(hoverIndex, plotWidth);
        g.setColor(new JBColor(new Color(80, 80, 80, 90), new Color(210, 210, 210, 90)));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(x, TOP, x, TOP + plotHeight);
    }

    private void drawYGrid(Graphics2D g, int plotWidth, int plotHeight, NiceScale scale) {
        FontMetrics metrics = g.getFontMetrics();
        for (int value = 0; value <= scale.maximum(); value += scale.step()) {
            int y = TOP + plotHeight - (int) Math.round(value * plotHeight / (double) scale.maximum());
            g.setColor(new JBColor(new Color(225, 225, 225), new Color(50, 52, 55)));
            g.drawLine(LEFT, y, LEFT + plotWidth, y);
            g.setColor(getForeground());
            String label = String.format("%,d", value);
            g.drawString(label, LEFT - metrics.stringWidth(label) - 7, y + metrics.getAscent() / 2 - 1);
        }
    }

    private static NiceScale niceScale(int observedMaximum, int plotHeight) {
        if (observedMaximum <= 0) return new NiceScale(2, 1);
        int desiredIntervals = Math.max(3, Math.min(8, plotHeight / 55));
        double roughStep = observedMaximum / (double) desiredIntervals;
        double magnitude = Math.pow(10, Math.floor(Math.log10(roughStep)));
        double normalized = roughStep / magnitude;
        double nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        int step = Math.max(1, (int) Math.round(nice * magnitude));
        int maximum = (int) (Math.ceil(observedMaximum / (double) step) * step);
        if (maximum <= observedMaximum) maximum += step;
        return new NiceScale(maximum, step);
    }

    private void drawDateTicks(Graphics2D g, int plotWidth, int plotHeight) {
        int tickCount = Math.min(snapshots.size(), Math.max(2, plotWidth / 120 + 1));
        int previousIndex = -1;
        for (int tick = 0; tick < tickCount; tick++) {
            int index = tickCount == 1 ? 0 : (int) Math.round(tick * (snapshots.size() - 1.0) / (tickCount - 1));
            if (index == previousIndex) continue;
            previousIndex = index;
            int x = xAt(index, plotWidth);
            g.setColor(new JBColor(new Color(225, 225, 225), new Color(50, 52, 55)));
            g.drawLine(x, TOP, x, TOP + plotHeight);
            g.setColor(getForeground());
            String date = DATE.format(snapshots.get(index).commit().time());
            int labelWidth = g.getFontMetrics().stringWidth(date);
            int labelX = Math.max(LEFT, Math.min(LEFT + plotWidth - labelWidth, x - labelWidth / 2));
            g.drawString(date, labelX, getHeight() - 12);
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (snapshots.isEmpty()) return null;
        int index = nearestIndex(event.getX());
        LocSnapshot value = snapshots.get(index);
        String range = "";
        if (rangeStart >= 0 && rangeEnd >= 0 && rangeStart != rangeEnd) {
            LocSnapshot from = snapshots.get(Math.min(rangeStart, rangeEnd));
            LocSnapshot to = snapshots.get(Math.max(rangeStart, rangeEnd));
            int locDelta = to.linesFor("", false, false) - from.linesFor("", false, false);
            int rlocDelta = to.linesFor("", false, true) - from.linesFor("", false, true);
            range = "<hr>Selected range: " + from.commit().shortHash() + " → " + to.commit().shortHash() +
                    "<br><b>Total LOC: " + signed(locDelta) + " → " +
                    String.format("%,d", to.linesFor("", false, false)) + "</b>" +
                    "<br>Total RLOC: " + signed(rlocDelta) + " → " +
                    String.format("%,d", to.linesFor("", false, true));
        }
        return "<html><b>" + String.format("%,d", value.linesFor(path, file, showRloc)) +
                (showRloc ? " RLOC" : " LOC") + "</b><br>" +
                DATE.format(value.commit().time()) + " · " + value.commit().shortHash() + "<br>" +
                escape(value.commit().subject()) + range + "</html>";
    }

    private int nearestIndex(int mouseX) {
        if (snapshots.isEmpty()) return -1;
        int plotWidth = Math.max(1, getWidth() - LEFT - RIGHT);
        int index = snapshots.size() == 1 ? 0 : (int) Math.round(
                (mouseX - LEFT) * (snapshots.size() - 1) / (double) plotWidth);
        return Math.max(0, Math.min(snapshots.size() - 1, index));
    }

    private void fireRange() {
        if (rangeStart < 0 || rangeEnd < 0 || rangeStart == rangeEnd || snapshots.isEmpty()) return;
        rangeListener.accept(snapshots.get(Math.min(rangeStart, rangeEnd)),
                snapshots.get(Math.max(rangeStart, rangeEnd)));
    }

    private static String signed(int value) {
        return (value > 0 ? "+" : "") + String.format("%,d", value);
    }

    private int xAt(int index, int plotWidth) {
        if (snapshots.size() == 1) return LEFT + plotWidth / 2;
        return LEFT + (int) Math.round(index * plotWidth / (double) (snapshots.size() - 1));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record NiceScale(int maximum, int step) {}
}
