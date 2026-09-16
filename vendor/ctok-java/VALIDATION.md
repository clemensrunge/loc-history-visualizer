# Validation report

Validated on 2026-09-16 against Python ctok 1.3.0, upstream commit `ad78ea15a1febf983b379475b20f5a2b0d2ebe76`.

- Python: CPython 3.12.14, Unicode 15.0.0, regex 2026.8.31; **330 tests passed** (13.47 seconds from the upstream directory).
- Java: JetBrains Runtime JDK 25.0.4, Gradle 9.1.0, compiled with `--release 21`; **build succeeded**.
- Java API checks: version routing, invalid inputs, lone surrogates, literal bracket escaping, immutable collections, witness access, and concurrent counting passed.
- Cross-language verification: **401,931 cases passed with zero mismatches**. Each case checks normalization, marked stream, token count, and the complete rendered token list. Families tested: 3.0, 4.7, and 4.8.

All shipped corpus documents were included:

| Fixture | Documents |
| --- | ---: |
| multipl_e.jsonl.gz | 22 |
| rosetta.jsonl.gz | 1,741 |
| rosetta_holdout.jsonl.gz | 250 |
| udhr.jsonl.gz | 501 |

Additional inputs: upstream API regression rows, every vocabulary witness probe, all 63,488 non-surrogate BMP codepoints, 3,000 seeded random Unicode strings, 150 trailing-newline lengths on five prefixes, and explicit normalization/notation edge cases. Lone surrogates are separately checked directly in Java because ordinary UTF-8 parity transport cannot represent them.

Commands:

```sh
# From the original ctok checkout
UV_CACHE_DIR=/tmp/ctok-uv-cache uv run python -m pytest -n 2

# From ctok-java
../ctok/.venv/bin/python tools/generate_parity.py ../ctok build/parity.bin
JAVA_HOME=/home/crun/.local/share/JetBrains/Toolbox/apps/clion/jbr \
GRADLE_USER_HOME=/home/crun/claude/loc-history-visualizer/.gradle-user-home \
./gradlew test build -PparityFile=build/parity.bin
```

The reference binary remains in `build/parity.bin` (excluded from Git) and can be regenerated using the checked-in script. Ordinary builds need neither Python nor this file. Upstream's Python tests also passed its gates against the recorded model counts. These comparisons establish parity with the reconstruction; they do not establish exact live-model token boundaries or counts for every possible input.

NFC uses the host JDK's Unicode normalizer. The checked-in classification/case tables remove most Unicode-version dependencies; rerun parity when changing JDKs or updating upstream. Bytecode targets Java 21, while the execution tests in this report used JDK 25.
