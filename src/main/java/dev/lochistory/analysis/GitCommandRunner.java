package dev.lochistory.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GitCommandRunner {
    private final Path repository;

    public GitCommandRunner(Path repository) {
        this.repository = repository;
    }

    public String run(AnalysisProgress progress, String... arguments) throws GitCommandException {
        return runWithInput(progress, null, arguments);
    }

    public String runWithInput(AnalysisProgress progress, String input, String... arguments) throws GitCommandException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(arguments));

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            try (ExecutorService readers = Executors.newFixedThreadPool(2)) {
                Future<byte[]> stdoutRead = readers.submit(() -> process.getInputStream().readAllBytes());
                Future<byte[]> stderrRead = readers.submit(() -> process.getErrorStream().readAllBytes());
                if (input != null) process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (progress.isCancelled()) {
                        process.destroyForcibly();
                        throw new GitCommandException("Analysis cancelled");
                    }
                }
                String stdout = new String(stdoutRead.get(), StandardCharsets.UTF_8);
                String stderr = new String(stderrRead.get(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0 && process.exitValue() != 1) {
                    throw new GitCommandException(stderr.isEmpty() ? "Git exited with " + process.exitValue() : stderr);
                }
                return stdout;
            }
        } catch (IOException e) {
            throw new GitCommandException("Could not run Git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandException("Git command interrupted");
        } catch (ExecutionException e) {
            throw new GitCommandException("Could not read Git output: " + e.getCause().getMessage());
        }
    }
}
