package dev.lochistory.analysis;

public interface AnalysisProgress {
    AnalysisProgress NONE = new AnalysisProgress() {};

    default void update(double fraction, String message) {}

    default boolean isCancelled() {
        return false;
    }
}
