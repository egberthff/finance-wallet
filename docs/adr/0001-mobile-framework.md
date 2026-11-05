# ADR-0001: Native Android with Kotlin and Jetpack Compose

- **Status:** **Accepted** (2026-11-05). Approved by owner with Phase 1
- **Requirements:** NFR-02..07, UX-01, LOC-01, SEC-01

## Context
Revision 1 proposed React Native/Expo, mainly to reach iOS from a Windows machine. The owner dropped iOS (ADR-0009), which removes that benefit and leaves only the cost of a cross-platform runtime: memory, APK size, and the JS/native boundary.

## Decision
Build a native Android app in Kotlin with Jetpack Compose and Material 3, using coroutines/Flow and Hilt. The financial engine is a pure Kotlin/JVM `:domain` module with no Android dependency, so it can be tested with only a JDK and can become Kotlin Multiplatform later if iOS returns.

## Alternatives considered
React Native + Expo (rev. 1; now scores lower on memory/size/native APIs). Flutter (good performance but extra engine and Dart-only toolchain). Scores in docs/05-technology-evaluation.md.

## Consequences
- **Pro:** Best performance, memory and APK size on low-end devices.
- **Pro:** First-party Keystore, BiometricPrompt, SAF, WorkManager.
- **Con:** Requires installing a JDK and the Android SDK (needed for any Android build anyway).
- **Con:** A future iOS app would need KMP/Compose MP work for the UI.
