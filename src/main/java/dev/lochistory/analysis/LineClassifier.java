package dev.lochistory.analysis;

import java.util.Locale;
import java.util.Set;

final class LineClassifier {
    private static final Set<String> HASH_COMMENT = Set.of(
            "py", "pyw", "sh", "bash", "zsh", "fish", "rb", "pl", "pm", "r", "yaml", "yml", "toml");
    private static final Set<String> C_STYLE = Set.of(
            "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "m", "mm", "java", "kt", "kts",
            "js", "jsx", "ts", "tsx", "cs", "go", "rs", "swift", "scala", "css", "scss", "less");
    private static final Set<String> SQL_STYLE = Set.of("sql", "lua", "hs");
    private static final Set<String> XML_STYLE = Set.of("xml", "html", "htm", "svg", "vue", "svelte");

    private LineClassifier() {}

    static boolean hasCode(String path, String line, State state) {
        String extension = extension(path);
        if (XML_STYLE.contains(extension)) return scanBlock(line, state, "<!--", "-->", null);
        if (C_STYLE.contains(extension)) return scanBlock(line, state, "/*", "*/", "//");
        String trimmed = line.stripLeading();
        if (trimmed.isBlank()) return false;
        if (HASH_COMMENT.contains(extension)) return !trimmed.startsWith("#");
        if (SQL_STYLE.contains(extension)) return !trimmed.startsWith("--");
        return true;
    }

    private static boolean scanBlock(String line, State state, String blockStart, String blockEnd,
                                     String singleLine) {
        int index = 0;
        boolean code = false;
        while (index < line.length()) {
            if (state.inBlock) {
                int end = line.indexOf(blockEnd, index);
                if (end < 0) return code;
                state.inBlock = false;
                index = end + blockEnd.length();
                continue;
            }
            while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
            if (index >= line.length()) return code;
            if (singleLine != null && line.startsWith(singleLine, index)) return code;
            if (line.startsWith(blockStart, index)) {
                state.inBlock = true;
                index += blockStart.length();
                continue;
            }
            code = true;
            int nextBlock = line.indexOf(blockStart, index);
            int nextSingle = singleLine == null ? -1 : line.indexOf(singleLine, index);
            if (nextBlock < 0 && nextSingle < 0) return true;
            if (nextSingle >= 0 && (nextBlock < 0 || nextSingle < nextBlock)) return true;
            index = nextBlock;
        }
        return code;
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot <= slash ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static final class State {
        private boolean inBlock;
    }
}
