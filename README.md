# Opencode-PE

**OpenCode Pocket Edition** — an unofficial Android app that runs OpenCode on your phone, with the original web interface, a built-in command environment, and optional Termux integration. No PC or root required. AI providers still require an internet connection.

## Features

- Original OpenCode interface for chats, models, providers, permissions, and settings.
- Embedded OpenCode 1.18.31 with Bun, Node.js, Git, npm, ripgrep, curl, Python, and pip.
- Optional Termux backend: run the agent and its tools in your existing Termux environment.
- Project creation, Git cloning, ZIP import/export, and Android file access controls.
- OpenCode plugins and MCP configuration; compatibility depends on the packages and selected runtime.
- OpenCode interface languages, with English and Russian translations for the Android screens.

## Requirements and setup

Android 9 or newer on ARM64. Current version: **0.4.11-alpha**. The app still uses the display name Pocket OpenCode and package ID `dev.pocketopencode`.

1. Download the ARM64 APK from [Releases](https://github.com/Yozh2709/Opencode-PE/releases), or build it using the instructions below. Public alpha APKs use the development signing key.
2. Start with the embedded environment, or connect a compatible official Termux installation through **Settings → Runtime**. Termux needs its command permission and external-app access enabled; the setup screen guides you through this.
3. Connect your provider in OpenCode, open a project, and start a chat.

Embedded and Termux modes keep separate projects, credentials, and chat histories. Switching environments does not automatically migrate them.

## Build on Windows

Install Node.js, curl, tar, JDK 21+, Android SDK platform 36 / build-tools 36.0.0, and Android NDK r29. Set `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_NDK_HOME` for your installation.

```powershell
node scripts/prepare-runtime.mjs C:/path/to/runtime-cache
./gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Runtime archives and native libraries are downloaded and verified using `runtime.lock.json`; generated payloads and local signing keys are excluded from Git.

## Current limitations

This is an alpha. Android may stop background processes. Embedded tools are subject to Android execution restrictions: desktop binaries, arbitrary native Python extensions, Docker, and full desktop build toolchains are not generally supported. Termux offers a broader package environment, but does not make every desktop dependency compatible.

The local server listens on loopback and uses a random password protected by Android Keystore. Provider credentials are stored by OpenCode in the selected environment. Uninstalling the app deletes its private data; export projects first.

See [validation notes](VALIDATION.md) for prior checks. The latest UI changes were built successfully; full device regression testing for 0.4.11 remains pending.

## Upstream projects

Built around [OpenCode](https://github.com/anomalyco/opencode), [Bun](https://github.com/oven-sh/bun), and packages from [Termux](https://github.com/termux/termux-packages). This is an independent project, not an official OpenCode or Termux app. See [third-party notices](THIRD_PARTY_NOTICES.md) and [licenses](licenses/).
