package dev.lochistory.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record LocSnapshot(CommitInfo commit, Map<String, FileMetrics> metricsByFile) {
    public LocSnapshot {
        metricsByFile = Collections.unmodifiableMap(new LinkedHashMap<>(metricsByFile));
    }

    public int linesFor(String path, boolean file) {
        return linesFor(path, file, false);
    }

    public int linesFor(String path, boolean file, boolean rloc) {
        return linesFor(path, file, rloc ? CountingMetric.RLOC : CountingMetric.LOC);
    }

    public int linesFor(String path, boolean file, CountingMetric metric) {
        if (path.isEmpty()) return metricsByFile.values().stream().mapToInt(value -> value.value(metric)).sum();
        if (file) {
            FileMetrics value = metricsByFile.get(path);
            return value == null ? 0 : value.value(metric);
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        return metricsByFile.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .mapToInt(entry -> entry.getValue().value(metric))
                .sum();
    }

    public double standardDeviation(boolean rloc) {
        return standardDeviation(rloc ? CountingMetric.RLOC : CountingMetric.LOC);
    }

    public double standardDeviation(CountingMetric metric) {
        if (metricsByFile.isEmpty()) return 0;
        double average = metricsByFile.values().stream().mapToInt(value -> value.value(metric)).average().orElse(0);
        double variance = metricsByFile.values().stream()
                .mapToDouble(value -> Math.pow(value.value(metric) - average, 2)).average().orElse(0);
        return Math.sqrt(variance);
    }

    /** Generic percentage comparison, also suitable for future token providers. */
    public double percentage(CountingMetric numerator, CountingMetric denominator) {
        int total = linesFor("", false, denominator);
        return total == 0 ? 0 : linesFor("", false, numerator) * 100.0 / total;
    }
}
