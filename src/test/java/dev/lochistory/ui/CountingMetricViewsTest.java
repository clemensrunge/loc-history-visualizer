package dev.lochistory.ui;

import dev.lochistory.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CountingMetricViewsTest {
    @ParameterizedTest
    @EnumSource(value = CountingMetric.class, names = {"OPENAI_TOKENS", "CLAUDE_TOKENS"})
    void treeUsesTokenCountsIncludingCollapsedFootprints(CountingMetric metric) {
        LocSnapshot included = snapshot(10);
        LocSnapshot footprint = new LocSnapshot(included.commit(), Map.of(
                "src/a.txt", metrics(10), "excluded/b.txt", metrics(30)));
        var tree = LocTreeBuilder.build(included, footprint, metric, Set.of("excluded"));
        assertEquals(10, ((PathNode) tree.getUserObject()).lines());
        var excluded = (javax.swing.tree.DefaultMutableTreeNode) tree.getChildAt(0);
        assertEquals("excluded", ((PathNode) excluded.getUserObject()).path());
        assertEquals(30, ((PathNode) excluded.getUserObject()).lines());
        assertEquals(0, excluded.getChildCount());
    }

    @ParameterizedTest
    @EnumSource(value = CountingMetric.class, names = {"OPENAI_TOKENS", "CLAUDE_TOKENS"})
    void treemapShowsTokenChangesEvenWhenLineCountsStayTheSame(CountingMetric metric) {
        var panel = new LocTreemapPanel();
        panel.setSize(620, 330);
        panel.showDiff(snapshot(20), snapshot(10), metric, Set.of());
        var image = new BufferedImage(620, 330, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { panel.paint(graphics); } finally { graphics.dispose(); }
        String tooltip = panel.getToolTipText(new MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0, 0, 200, 150, 0, false));
        assertNotNull(tooltip);
        assertTrue(tooltip.contains(metric + ": <b>-10 → 10"), tooltip);
    }

    private static FileMetrics metrics(int tokens) {
        return new FileMetrics(Map.of(CountingMetric.LOC, 4, CountingMetric.RLOC, 3,
                CountingMetric.OPENAI_TOKENS, tokens, CountingMetric.CLAUDE_TOKENS, tokens));
    }
    private static LocSnapshot snapshot(int tokens) {
        return new LocSnapshot(new CommitInfo("0123456789", Instant.EPOCH, "test"), Map.of("src/a.txt", metrics(tokens)));
    }
}
