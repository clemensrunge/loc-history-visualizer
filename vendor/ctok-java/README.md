# ctok-java

An offline Java 21 port of [ctok by Sander Land](https://github.com/sanderland/ctok), maintained by [Clemens Runge](https://github.com/clemensrunge/ctok-java). Based on Python ctok 1.3.0 at [commit ad78ea15](https://github.com/sanderland/ctok/tree/ad78ea15a1febf983b379475b20f5a2b0d2ebe76).

Verified on 2026-09-16: **330 Python tests passed** and **401,931 Python/Java comparisons matched exactly** across all three families. See [VALIDATION.md](VALIDATION.md) for inputs, commands, and execution environment. Java 21 bytecode was tested on JDK 25.

The library reconstructs Claude token **counts** for one user message. Its token boundaries are approximate, as in upstream. It makes no API calls and has no runtime dependencies. Vocabulary, byte fallback, witnesses, and Unicode classification/case tables are bundled in the JAR.

## Use

```java
import dev.ctok.Ctok;

Ctok tokenizer = Ctok.forVersion("4.8"); // cache and share this thread-safe instance
int messageTokens = tokenizer.tokenCount(sourceText);
int sourceTokens = tokenizer.contentTokenCount(sourceText);
```

`tokenCount` includes the fixed single-message frame and matches Python `ctok.token_count`. `contentTokenCount` subtracts only that fixed overhead; leading boundaries and trailing-newline absorption still follow the selected family. For source history metrics, choose one version and counting convention and store both with the metric. Summing counts for separate files is not equivalent to counting their concatenation.

Version routing matches upstream: `[3.0, 4.7)` → v3, `[4.7, 4.8)` → v4.7, and `4.8+` → v4.8 (including `"4.10"` and `"5"`). The default is `"3.0"`. Versions select a reconstructed family, not a live model or its current tokenizer.

Other API methods: `tokenize`, `normalize`, `markedStream`, `messageOverhead`, `family`, `pieces`, and `witness`. Returned collections are immutable. Null text or versions throw `NullPointerException`; unsupported versions throw `IllegalArgumentException`; missing witnesses throw `NoSuchElementException`. Lone UTF-16 surrogates become U+FFFD; valid surrogate pairs remain intact.

## Build and test

Use a full JDK 21 or later:

```sh
./gradlew test build
java -jar build/libs/ctok-java-1.3.0-java.1.jar 'hello, world' 4.8
```

The verification harness requires no test framework dependencies. `test` executes it through `verifyParity`. It checks API behavior, invalid input, immutable collections, and concurrent counting. Python parity data is generated separately to keep Python out of ordinary builds and runtime.

To reproduce the cross-language comparison, use the upstream checkout at the commit above. If a sibling checkout is not already available:

```sh
git clone https://github.com/sanderland/ctok.git ../ctok
git -C ../ctok switch --detach ad78ea15a1febf983b379475b20f5a2b0d2ebe76
```

Run with [uv](https://docs.astral.sh/uv/) and the reference Python version:

```sh
(cd ../ctok && UV_CACHE_DIR=/tmp/ctok-uv-cache uv sync --locked --python 3.12.14)
(cd ../ctok && UV_CACHE_DIR=/tmp/ctok-uv-cache uv run python -m pytest -n 2)
../ctok/.venv/bin/python tools/generate_parity.py ../ctok build/parity.bin
./gradlew test build -PparityFile=build/parity.bin
```

The comparison executes Python and Java on all four shipped corpora, the API regression rows, all non-surrogate BMP codepoints, deterministic random Unicode strings, newline ladders, and every vocabulary witness probe. It compares normalization, marked streams, counts, and complete rendered token lists for all three families. Standalone Java checks cover lone surrogates as well.

`tools/export_reference.py` regenerates the bundled binary vocabulary and Unicode tables using upstream's Python environment:

```sh
../ctok/.venv/bin/python tools/export_reference.py ../ctok
```

The binaries use big-endian integers and length-prefixed UTF-8 strings. Unicode classification, mark properties, and case mappings use the reference Python environment's tables (Python Unicode 15.0.0 and its locked `regex` dependency), including upstream's explicit Unicode 16 case overrides. NFC composition uses the JDK normalizer. Regenerate the tables and rerun parity when updating upstream or the Python environment.

## Future loc-history-visualizer integration

The project uses Gradle Kotlin DSL and emits Java 21 bytecode, matching loc-history-visualizer. It has no IntelliJ, UI, or logging dependencies. The counting path avoids rendering token strings, and its per-call caches are bounded by the input's distinct codepoints.

Publish locally with `./gradlew publishToMavenLocal`, then add `mavenLocal()` and `implementation("dev.ctok:ctok-java:1.3.0-java.1")` to the visualizer. A composite build is another option:

```kotlin
// settings.gradle.kts
includeBuild("../ctok-java")

// build.gradle.kts
implementation("dev.ctok:ctok-java:1.3.0-java.1")
```

The headless analyzer's existing `dev.lochistory.analysis.TokenCounter` has `metric()` and `count(String)`. A future Claude implementation can hold a shared `Ctok.forVersion("4.8")` instance and delegate `count(text)` to `contentTokenCount(text)`, with a new Claude entry in `CountingMetric`. Include this dependency's classes and `dev/ctok/*.bin` resources when building a self-contained CLI JAR. Keep the license, attribution notice, and provenance files under `META-INF/licenses/ctok/` in distributions. Integration into the visualizer is intentionally left for the later task.

Validation results: [VALIDATION.md](VALIDATION.md).

## License

MIT; see [LICENSE](LICENSE). The complete upstream MIT license and `Copyright (c) 2026 Sander Land` are retained alongside the Java port's copyright notice. The implementation, vocabulary, byte fallback, witness records, and regression examples derive from Sander Land's ctok; see [NOTICE](NOTICE) for source mapping and [PROVENANCE.json](PROVENANCE.json) for source hashes and model metadata.

The main, sources, and Javadoc JARs all include `LICENSE`, `NOTICE`, and `PROVENANCE.json` under `META-INF/licenses/ctok/`. Maven publication metadata also identifies the MIT license, upstream author, and Java port maintainer.
