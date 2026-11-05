# Spike: Release APK Size Impact of Import/Crypto Libraries

| Date | 2026-11-05 |
|---|---|
| Related | NFR-07 (download ≤ 40 MB), R-21, ADR-0011, 13-import-pipeline.md |
| Reproduce | Branch `spike/apk-size`: `bash spike/spike.sh` (raw output in `spike/results.txt`) |

## Method
For each candidate library, a probe class calls a real entry point (so R8 cannot strip the library) from a code path
that never runs in normal use. An R8-minified, resource-shrunk **release** APK was built per probe with AGP 9.5.0 / R8
on the dev machine. Lint was skipped (spike only). Sizes are APK file bytes (all ABIs).

## Results (measured)

| Build | APK bytes | Δ vs baseline | Notes |
|---|---|---|---|
| Baseline (current app, no probe) | 9,356,183 (8.9 MiB) | — | dex 2.04 MB; native libs (SQLCipher) 7.72 MB for 4 ABIs, of which arm64-v8a 2.12 MB |
| + BouncyCastle `bcprov-jdk18on` 1.86 (Argon2id only) | 9,387,269 | **+31 KB** | R8 keeps only the Argon2 classes |
| + PdfBox-Android 2.0.27.0 (text extraction) | 15,393,727 | **+6.04 MB** | Builds only with `-dontwarn com.gemalto.jp2.**` (optional JPEG 2000 decoder). ~4.6 MB of bundled resources (fonts, CMaps, glyph lists) + ~0.5 MB dex |
| + fastexcel-reader 0.20.2 | — | **Build failed** | R8: missing `javax.xml.stream.*` (StAX API, not part of Android) and `com.github.luben.zstd.*` (optional codec in commons-compress) |

Estimate, not measured: with an Android App Bundle the Play Store delivers one ABI per device, so a typical arm64 download
is roughly baseline − (native libs of the 3 other ABIs) ≈ 9.36 − 5.60 ≈ **3.8 MB**, before any PDF support.
This is to be verified from Play Console size reports in Phase 9.

## Conclusions
1. **ADR-0011 holds:** Argon2id via BouncyCastle costs ~31 KB. The PBKDF2 fallback is not needed for size reasons.
2. **fastexcel-reader is not usable as-is on Android.** It depends on the StAX API, which Android does not ship.
   **Recommendation for Phase 6:** implement a small streaming XLSX reader on top of the platform's built-in
   `XmlPullParser` (XLSX = ZIP + `sharedStrings.xml` + `sheetN.xml`). It needs no dependency, can apply the zip-bomb guards
   in 13-import-pipeline.md §2 directly, and is fully testable with fixtures. Bundling a StAX API jar was considered and
   rejected: it adds a `javax.*` API surface Android does not provide, plus an unused zstd dependency tree.
3. **PdfBox-Android works, at +6 MB.** That is acceptable under NFR-07 (40 MB). Phase 6 should test whether unused font
   resources can be excluded without breaking text extraction for the verified statement layouts, and keep the JP2 `-dontwarn` rule.
   PDF import is P1, so the cost lands only when that feature ships.
4. Baseline size is dominated by SQLCipher native libraries. AAB per-ABI delivery handles this, so no action is needed.

## Not measured here (device required)
SQLCipher query/open performance (NFR-02..05). This waits for the physical test device (follow-up in PROGRESS.md).
