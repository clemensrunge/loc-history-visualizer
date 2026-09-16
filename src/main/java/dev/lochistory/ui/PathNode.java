package dev.lochistory.ui;

import dev.lochistory.model.CountingMetric;

record PathNode(String name, String path, boolean file, int loc, int rloc, int count, CountingMetric metric) {
    int lines() {
        return count;
    }

    @Override
    public String toString() {
        return name + "  —  " + String.format("%,d", lines()) + (" " + metric);
    }
}
