# Environment Setup Guide

| Status | v1 (2026-11-05), written while setting up the first development machine |
|---|---|

## 1. Required tools

| Tool | Version | Why |
|---|---|---|
| JDK | Temurin **21** LTS (21.0.13+9 used) | Runs Gradle and AGP. All modules target Java 17 bytecode/API (`-Xjdk-release=17` in `:domain`); AGP 9.5 needs JDK ≥ 17 |
| Gradle | via wrapper (`gradlew`), 9.8.2 | AGP 9.5 needs Gradle ≥ 9.6.0 |
| Android SDK command-line tools | `commandlinetools-win-15859902` | `sdkmanager` for platform + build tools |
| Android SDK packages | `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0` | compileSdk 37 (current AndroidX Navigation/Compose require ≥ 37); targetSdk stays 36; AGP 9.5 minimum build tools |
| Git | ≥ 2.40 | — |

An emulator is **not** required (or recommended on 8 GB RAM machines). Use a physical device with USB debugging.

## 2. Windows install (no admin rights needed)
Everything lives under `%USERPROFILE%\dev\`. Every download is verified against the publisher's SHA-256 **before** extraction.

```bash
# Git Bash
D=$HOME/dev; mkdir -p $D/downloads/{jdk,cmdline-tools}
# JDK: checksum from https://api.adoptium.net/v3/assets/latest/21/hotspot?os=windows&architecture=x64&image_type=jdk
curl -L -C - -o $D/downloads/jdk/temurin21.zip <binary.package.link>
echo "<binary.package.checksum>  $D/downloads/jdk/temurin21.zip" | sha256sum -c
# Android command-line tools: file name + checksum from https://developer.android.com/studio#command-tools
curl -L -o $D/downloads/cmdline-tools/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-win-<build>_latest.zip
echo "<sha256>  $D/downloads/cmdline-tools/cmdline-tools.zip" | sha256sum -c
unzip -q $D/downloads/jdk/temurin21.zip -d $D && mv $D/jdk-21.* $D/jdk-21
mkdir -p $D/android-sdk/cmdline-tools && unzip -q $D/downloads/cmdline-tools/cmdline-tools.zip -d $D/android-sdk/cmdline-tools \
  && mv $D/android-sdk/cmdline-tools/cmdline-tools $D/android-sdk/cmdline-tools/latest
```
Slow or unstable connections: `curl -C -` resumes partial downloads. If a checksum fails, re-download. **Never** extract an unverified archive.

Then set (user environment variables):
```
JAVA_HOME        = %USERPROFILE%\dev\jdk-21
ANDROID_HOME     = %USERPROFILE%\dev\android-sdk
PATH            += %JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools
```
Install SDK packages and accept licenses (review them):
```
sdkmanager --sdk_root=%ANDROID_HOME% "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
sdkmanager --sdk_root=%ANDROID_HOME% --licenses
```
Create `local.properties` in the repo root (git-ignored) using **forward slashes**: `sdk.dir=C:/Users/<you>/dev/android-sdk` (unescaped backslashes are silently dropped by the properties format).

## 3. Build & verify
```
gradlew check          # money-safety rule + self-tests, unit tests, coverage verification
gradlew :domain:test   # domain engine only (needs only the JDK)
gradlew :domain:koverHtmlReport
```

## 4. Device
Enable Developer options → USB debugging. Check with `adb devices`.

## 5. Disk budget (observed figures recorded in PROGRESS.md as they are measured)
JDK ≈ 0.3 GB · SDK packages ≈ 1–2 GB · Gradle distributions + caches ≈ 2–4 GB.
