package dev.lochistory.analysis;

import dev.lochistory.model.CountingMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkingTreeTest {
    @TempDir Path repository;

    @Test void countsDiskContentsAndNewFilesWithoutChangingIndex() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Files.writeString(repository.resolve("a.cpp"), "int a;\n");
        Files.writeString(repository.resolve("deleted.txt"), "gone\n");
        Files.writeString(repository.resolve("ignored.txt"), "tracked but ignored later\n");
        git("add", ".");
        git("commit", "-m", "base");
        Files.writeString(repository.resolve("a.cpp"), "int staged;\nint staged2;\n");
        git("add", "a.cpp");
        String current = "// comment\r\nint local;\r\n\r\nint last;";
        Files.writeString(repository.resolve("a.cpp"), current);
        Files.delete(repository.resolve("deleted.txt"));
        Files.writeString(repository.resolve("new.txt"), "new\n");
        Files.writeString(repository.resolve(".gitignore"), "ignored.txt\nignored-new.txt\n");
        Files.writeString(repository.resolve("ignored-new.txt"), "ignored\n");
        Files.write(repository.resolve("binary.bin"), new byte[]{65, 0, 66});
        Files.createDirectories(repository.resolve("build"));
        Files.writeString(repository.resolve("build/generated.txt"), "excluded\n");
        String before = git("status", "--porcelain=v1");
        var analyzer = new LocHistoryAnalyzer(repository);
        var local = analyzer.workingTreeSnapshot(AnalysisProgress.NONE);
        assertEquals(java.util.Set.of("a.cpp", "new.txt"), local.metricsByFile().keySet());
        assertEquals(4, local.linesFor("a.cpp", true, CountingMetric.LOC));
        assertEquals(2, local.linesFor("a.cpp", true, CountingMetric.RLOC));
        assertEquals(new OpenAiTokenCounter().count(current), local.linesFor("a.cpp", true, CountingMetric.OPENAI_TOKENS));
        assertEquals(new ClaudeTokenCounter().count(current), local.linesFor("a.cpp", true, CountingMetric.CLAUDE_TOKENS));
        assertEquals(5, local.linesFor("", false));
        assertEquals(before, git("status", "--porcelain=v1"));
        assertEquals(2, analyzer.snapshot("HEAD", AnalysisProgress.NONE).linesFor("", false));
    }

    @Test void appendsLocalPointOnlyWhileDirtyAndDetectsFurtherEdits() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Files.writeString(repository.resolve("a.txt"), "first\n");
        git("add", ".");
        git("commit", "-m", "first");
        var analyzer = new LocHistoryAnalyzer(repository, List.of());
        var history = analyzer.analyze("HEAD", 1, 1, AnalysisProgress.NONE);
        assertSame(history, analyzer.appendWorkingTree(history, AnalysisProgress.NONE));
        Files.writeString(repository.resolve("a.txt"), "first\nsecond\n");
        String revision = analyzer.workingTreeRevision(AnalysisProgress.NONE);
        var dirty = analyzer.appendWorkingTree(history, AnalysisProgress.NONE);
        assertEquals(2, dirty.snapshots().size());
        assertEquals(history.snapshots().getFirst(), dirty.snapshots().getFirst());
        assertEquals("WORKTREE", dirty.snapshots().getLast().commit().hash());
        assertEquals(2, dirty.snapshots().getLast().linesFor("", false));
        Files.writeString(repository.resolve("a.txt"), "first\nsecond\nthird\n");
        assertNotEquals(revision, analyzer.workingTreeRevision(AnalysisProgress.NONE));
        git("add", ".");
        git("commit", "-m", "local changes");
        var clean = analyzer.analyze("HEAD", 2, 1, AnalysisProgress.NONE);
        assertSame(clean, analyzer.appendWorkingTree(clean, AnalysisProgress.NONE));
        assertEquals(3, clean.snapshots().getLast().linesFor("", false));
    }

    private String git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
