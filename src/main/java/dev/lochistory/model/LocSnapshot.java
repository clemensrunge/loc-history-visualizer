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
        if (path.isEmpty()) return metricsByFile.values().stream().mapToInt(value -> value.value(rloc)).sum();
        if (file) {
            FileMetrics value = metricsByFile.get(path);
            return value == null ? 0 : value.value(rloc);
        }
        String prefix = path.endsWith("/") ? path : path + "/";
        return metricsByFile.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .mapToInt(entry -> entry.getValue().value(rloc))
                .sum();
    }

    public double standardDeviation(boolean rloc) {
        if (metricsByFile.isEmpty()) return 0;
        double average = metricsByFile.values().stream().mapToInt(value -> value.value(rloc)).average().orElse(0);
        double variance = metricsByFile.values().stream()
                .mapToDouble(value -> Math.pow(value.value(rloc) - average, 2)).average().orElse(0);
        return Math.sqrt(variance);
    }
}
