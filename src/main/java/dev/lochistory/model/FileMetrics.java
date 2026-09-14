package dev.lochistory.model;

public record FileMetrics(int loc, int rloc) {
    public int value(boolean showRloc) {
        return showRloc ? rloc : loc;
    }
}
