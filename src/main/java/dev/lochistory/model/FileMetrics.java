package dev.lochistory.model;

import java.util.Map;

/** Counts keyed by metric so additional token providers do not change snapshot structure. */
public record FileMetrics(Map<CountingMetric, Integer> counts) {
    public FileMetrics { counts = Map.copyOf(counts); }
    public FileMetrics(int loc, int rloc) { this(Map.of(CountingMetric.LOC, loc, CountingMetric.RLOC, rloc)); }
    public int loc() { return value(CountingMetric.LOC); }
    public int rloc() { return value(CountingMetric.RLOC); }
    public int value(boolean rloc) { return value(rloc ? CountingMetric.RLOC : CountingMetric.LOC); }
    public int value(CountingMetric metric) { return counts.getOrDefault(metric, 0); }
}
