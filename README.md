# LOC History Visualizer for CLion

Track LOC and RLOC across Git commits and branches with a file/folder treemap, history graph, range diffs, exclusions, and a headless CI reporter.

## Run it

Install the plugin ZIP through **CLion → Settings → Plugins → ⚙ → Install Plugin from Disk**, restart CLion, open a Git project, then choose **View → Tool Windows → LOC History** and click **Analyze All**.

To run a development CLion instance instead (requires JDK 21 and Git):

```bash
./gradlew runIde
```

| Full repository view | Commit-range diff |
|---|---|
| ![LOC tree, treemap, and history graph](pictures/loc-vis-full.jpg) | ![LOC changes between selected commits](pictures/loc-vis-diff.jpg) |

## What it does

- Analyzes local and remote branch history without checking out or changing the working tree.
- **Analyze** samples a configurable number of first-parent commits; **Analyze All** walks back to the initial commit and fills in the discovered commit count.
- Tracks physical **LOC** and **RLOC** (nonblank, non-comment lines) with a live global toggle.
- Shows aggregate counts in a folder tree, file-type-colored squarified treemap, and interactive history graph.
- Displays the current RLOC/LOC percentage and per-file standard deviation.
- Uses rounded Y-axis scales, grid lines, responsive tooltips, date ticks, and a commit-snapping hover guide.
- Drag between commits in the graph to show an absolute-change treemap. Additions retain their file-type color; removals use its desaturated form. Tooltips show signed changes and resulting totals.
- Right-click a file to exclude its complete file type from every snapshot and statistic.
- Right-click a folder to exclude it from counts and graphs while retaining one collapsed `[excluded]` footprint in the treemap; right-click it again to include it.
- Lists manual exclusions and `.gitignore` rules in the toolbar dropdown. Selecting a manual entry removes that exclusion.
- Respects root and nested `.gitignore` rules by default. Toggling this setting reruns the previous analysis mode so every view stays consistent.
- Always omits dot-directories such as `.git`, `.github`, `.idea`, and `.cache`.
- Performs analysis in a cancellable background task.

## Build

Build the installable CLion plugin:

```bash
./gradlew buildPlugin
```

The ZIP is created under `build/distributions/`.

Build and run the standalone, dependency-free command-line analyzer:

```bash
./gradlew cliJar
java -jar build/libs/loc-history-visualizer-0.8.3-cli.jar \
  --repo . --branch HEAD --commits 100
```

The CLI writes TSV by default, including RLOC, LOC, and RLOC percentage for each file and folder. Generate a Markdown dashboard with a base-ref comparison using:

```bash
java -jar build/libs/loc-history-visualizer-0.8.3-cli.jar \
  --repo . --branch HEAD --compare origin/main \
  --format markdown --output LOC_HISTORY.md
```

Use `--check LOC_HISTORY.md` instead of `--output` to exit with status `3` when a committed report is stale. List available refs with `--list-branches`; use `--sample-every N` to cover a longer period with fewer snapshots.

## CI integration

The included [GitHub Actions workflow](.github/workflows/loc-history.yml):

- Builds the headless analyzer.
- Adds LOC/RLOC deltas to pull-request job summaries.
- Refreshes and commits `LOC_HISTORY.md` after pushes to `main` when the report changed.

The workflow checks out full history (`fetch-depth: 0`) so comparisons and complete reports are available.

## Counting and exclusions

LOC counts every physical line in Git-tracked text files. RLOC removes blank and comment-only lines for common C/C++, JVM, JavaScript/TypeScript, scripting, SQL, and XML-style languages. Binary files are ignored.

Analysis reads immutable commits through the Git CLI; it never modifies the checkout. In addition to dot-directories, common generated/vendor directories such as `build`, `out`, `target`, `node_modules`, `vendor`, `dist`, `.gradle`, and `cmake-build-*` are excluded by default.

The implementation does not depend on CLion C/C++ PSI APIs, so it can count every text language and works with both Nova and Classic language engines.
