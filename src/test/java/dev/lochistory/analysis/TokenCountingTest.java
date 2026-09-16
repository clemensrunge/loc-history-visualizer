package dev.lochistory.analysis;

import dev.ctok.Ctok;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingType;
import dev.lochistory.model.CountingMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenCountingTest {
    @TempDir Path repository;

    @Test void countsCompleteCommittedTextAndReusesUnchangedBlobs() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        git("config", "core.autocrlf", "false");
        String first = "// héllo 世界\r\n\r\nint x = 1;\r\n<|endoftext|>";
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("src/a.cpp"), first);
        git("add", "."); git("commit", "-m", "first");
        Files.writeString(repository.resolve("src/b.txt"), "hello world\n");
        git("add", "."); git("commit", "-m", "second");
        var encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        int[] calls = {0};
        int[] claudeCalls = {0};
        var claude = Ctok.forVersion("4.8");
        TokenCounter counter = new TokenCounter() {
            public CountingMetric metric() { return CountingMetric.OPENAI_TOKENS; }
            public int count(String text) { calls[0]++; return encoding.countTokensOrdinary(text); }
        };
        TokenCounter claudeCounter = new TokenCounter() {
            public CountingMetric metric() { return CountingMetric.CLAUDE_TOKENS; }
            public int count(String text) { claudeCalls[0]++; return new ClaudeTokenCounter().count(text); }
        };
        var result = new LocHistoryAnalyzer(repository, List.of(counter, claudeCounter))
                .analyzeAll("HEAD", false, AnalysisProgress.NONE);
        var latest = result.snapshots().getLast();
        int expected = encoding.countTokensOrdinary(first);
        assertEquals(expected, latest.linesFor("src/a.cpp", true, CountingMetric.OPENAI_TOKENS));
        assertEquals(expected, result.snapshots().getFirst().linesFor("", false, CountingMetric.OPENAI_TOKENS));
        assertEquals(expected + encoding.countTokensOrdinary("hello world\n"),
                latest.linesFor("src", false, CountingMetric.OPENAI_TOKENS));
        assertEquals(4, latest.linesFor("src/a.cpp", true, CountingMetric.LOC));
        int expectedClaude = claude.contentTokenCount(first) + claude.contentTokenCount("hello world\n");
        assertEquals(expectedClaude, latest.linesFor("", false, CountingMetric.CLAUDE_TOKENS));
        assertEquals(claude.tokenCount(first) - claude.messageOverhead(),
                latest.linesFor("src/a.cpp", true, CountingMetric.CLAUDE_TOKENS));
        assertEquals(latest.linesFor("", false, CountingMetric.OPENAI_TOKENS) * 100.0 / expectedClaude,
                latest.percentage(CountingMetric.OPENAI_TOKENS, CountingMetric.CLAUDE_TOKENS));
        assertEquals(2, calls[0]);
        assertEquals(2, claudeCalls[0]);
        var defaultSnapshot = new LocHistoryAnalyzer(repository).snapshot("HEAD", false, AnalysisProgress.NONE);
        assertEquals(latest.metricsByFile(), defaultSnapshot.metricsByFile());
        assertTrue(new OpenAiTokenCounter().count(first) > 0);
    }

    private void git(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }
}
