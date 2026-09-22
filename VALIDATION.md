# Opencode-PE validation notes

## 0.4.13 — app identity and updates

- Renamed the Android app label, native tools header and notification title to Opencode. Package ID and signing configuration are unchanged.
- Converted the original MIT-licensed OpenCode favicon geometry into adaptive Android icon resources; the notification uses a monochrome version.
- Added manual update checks in Settings and the native Runtime screen, plus optional daily launch checks. Only newer published releases with uploaded ARM64 APK assets from this repository are eligible, including alpha versions.
- AppUpdatesTest passed three tests covering numeric/prerelease ordering, unordered releases, drafts, incomplete/missing assets, downgrade prevention and foreign download URLs. JavaScript syntax validation passed. No device was connected for UI verification.

## 0.4.12 — Android close_range compatibility

- User trace from Tecno Spark 8C, Android 11, ARM64, kernel 4.14.199 identified `SIGSYS / SYS_SECCOMP / __NR_close_range` even for `bun --version`.
- The pinned Android Bun imports `syscall@LIBC`. A bundled preload library implements close_range through fd enumeration and close/fcntl, forwarding all other syscall arguments and kernel errors unchanged. Unsupported UNSHARE returns ENOSYS rather than pretending to succeed. No seccomp policy is disabled.
- `python3 scripts/test-bun-compat.py` passed on Linux: the baseline is killed by an actual seccomp TRAP; the preloaded implementation passes fd bounds, CLOEXEC, closing, invalid flags/ranges, unsupported unsharing, six-argument mmap forwarding, errno, and fork/exec checks.
- Android ARM64 native build with NDK r29 passed. APK build, JVM tests and Android lint passed. No Android device was connected; confirmation of the actual Bun launch on the affected phone remains pending.
- Diagnostics are now readable/copyable from the native Runtime screen before the web interface starts. The Termux runtime directory revision is incremented so existing installations receive the compatibility library.

## GitHub Actions — 2026-09-22

Clean Windows runner build [35729949591](https://github.com/Yozh2709/Opencode-PE/actions/runs/35729949591) passed: pinned runtime preparation, APK build, JVM tests, Android lint, expected signing-certificate verification, and artifact upload. No device tests were run. This manual verification run intentionally skipped release publication; publication is enabled for matching version tags.

The initial run exposed two packages removed from the rolling Termux repository. All 35 original package archives were preserved in a release asset and verified against the existing lockfile hashes. Clean builds now use that archive without changing package versions.

## 0.4.5 — 2026-09-20

- APK assets include npm @sigstore/protobuf-specs/dist/__generated__/envelope.js. The default underscore-directory filter was removed.
- Native ARM64 launchers built with Android NDK r29; opencode 1.18.31, npm/npx 11.19.1 and curl 8.22.0 run from PATH.
- Build, JVM tests and lint passed (work/package-tools-build.log).
- PackageToolsTest passed on realme C75: actual npm installation of jsonc-parser@3.3.1, dependency execution returning 42, and HTTPS via curl. First background run hit realme's process freezer; foreground rerun passed in 13.661 seconds (work/package-tools-device-test.log).
- Independent isolated Bun 1.4.2 install/import passed. The historical ChildProcess.kill error was not reproduced; it is not claimed fixed.
- User plugin configuration and credentials were not edited. Full plugin login/rotation is outside these checks.
## Earlier releases: 0.1.0–0.3.3

These are historical checks, not a full regression run against the current release. Tests used a realme C75 (RMX3941), Android 15, ARM64, 4 KB memory pages, without root. Local mock providers drove agent tests without paid model requests.

- **0.3.3:** Android UI localization follows the OpenCode locale cookie. APK builds, seven JVM tests, and lint passed. LanguageSyncTest passed in 9.593 seconds, covering English, Russian, fallback for German, accessibility labels, API continuity, and no WebView reload. Manual language changes and persistence after restart were verified. Full runtime switching was not separately retested.
- **0.3.2:** Restored default authentication plugins in both runtimes. Build, JVM tests, and lint passed. The Termux OpenAI provider dialog exposed browser login, headless login, and API-key options. Full OAuth login and embedded authentication were not tested.
- **0.3.1:** Added a persistent new-chat button. NewChatTest passed in Termux, checking double-tap protection, preservation of existing sessions, project selection after in-app navigation, composer visibility, and no horizontal overflow. Builds, JVM tests, and lint passed.
- **0.3.0:** Tested official Termux 0.118.3 integration. TermuxIntegrationTest passed two tests in 12.983 seconds, covering runtime paths and ZIP transfer without overwriting existing projects. AgentLoopTest passed in Termux (22.805 seconds) and embedded mode (43.346 seconds), exercising permissions, file writes, Node.js, Git, and ripgrep. Builds, JVM tests, and lint passed. An OpenSSL mismatch in the fresh Termux bootstrap required updating OpenSSL; setup now checks that tools actually run. Setup on a second clean device was not tested. Credentials and chat history remain separate between runtimes.
- **0.2.2:** Fixed extracted Bun wrapper imports that caused tree-sitter to read JavaScript as WebAssembly. AgentLoopTest passed in 36.142 seconds, including separate write/bash permissions and actual tool execution. Build, JVM tests, and lint passed.
- **0.2.1:** Fixed missing upstream UI dependency patches that crashed populated chats. WebUiTest passed in 23.902 seconds, checking message rendering, composer persistence, and asynchronous layout changes. An existing conversation rendered correctly without a new model request. Build, JVM tests, and lint passed.
- **0.2.0:** Integrated the original OpenCode web interface. WebUiTest passed in 10.606 seconds, checking the project route, composer, and horizontal layout. Builds, JVM tests, and lint passed with zero lint errors and 13 warnings. OAuth, attachments, external links, and Termux with the new GUI were not covered in that run.
- **0.1.0:** Three device tests passed together in 43.958 seconds: AgentLoopTest, EngineIntegrationTest, and GitCloneTest. Coverage included real embedded tools, permission-controlled file creation, persistence after core restart, HTTPS cloning, and npm. Build and JVM checks passed; lint reported zero errors and eight warnings. LDPlayer displayed the UI and ran Bun, but ARM64 translation prevented the full suite from passing. Devices with 16 KB pages were not tested.

Early builds were debug-signed APKs for manual installation. Native extensions, LSPs, Docker, arbitrary desktop toolchains, and all Android document-provider combinations were outside the tested scope. Local logs and device screenshots referenced below are development artifacts and are not included in this repository.

## 0.4.7 — 2026-09-20

- Added a native permission panel for the currently visible web session, with request details, allow-once and reject actions. Permissions are never granted automatically.
- Build and Android lint passed (work/permission-panel-build.log).
- AgentLoopTest passed on realme C75 in 98.477 seconds: a local mock model drove the real core, the visible native permission button approved fixture write/bash requests, and both tools completed. No paid model calls (work/permission-panel-device-test.log).
- Installed 0.4.7-alpha; removed the test APK after verification.
## 0.4.8 — 2026-09-20

- Compact chat toolbar with a settings entry available inside sessions. Runtime and Android tools moved into the upstream settings navigation.
- Permission requests prefer the upstream OpenCode dock; the fallback now lives in the web composer using OpenCode styles, with deny/always/once actions. No automatic approval is enabled by this change.
- New-chat action resolves the project of modern server/session routes through the API.
- Build and lint passed. AgentLoopTest passed on realme C75 in 77.018 seconds: settings opened from the session with auto-accept enabled as a control (not switched on), both Android settings entries were present, and permission buttons allowed the real write/bash tools to complete using a local mock model.
- Screenshot: work/permission-web-verified.png. Test log: work/mobile-style-device-test-final.log. Installed 0.4.8-alpha and removed the test APK.
## 0.4.9 — 2026-09-20

- Bundled pinned Termux Python 3.14.6 and pip 26.2.1 plus missing native dependencies. Python native files are installed from the APK; standard library and pip assets use the private runtime prefix.
- PythonToolsTest passed on realme C75 in 36.877 seconds: python/python3 and pip/pip3, SSL, SQLite, ctypes, compression modules, subprocess via sys.executable, HTTPS pip install of packaging==25.0 into an isolated fixture, and execution of the installed dependency. Fixture removed afterwards. No model calls.
- Verified sys.prefix and default site-packages point to Pocket OpenCode's private usr directory, not Termux.
- Build and lint passed (work/python-build.log); device test: work/python-device-test.log.
- APK: 213,064,494 bytes, +9,981,830 bytes over 0.4.8. Installed on phone; test package removed. Native third-party Python extensions and compilation toolchains are not covered by this validation.
## 0.4.10 — 2026-09-21

- Confirmed stale UI after backgrounding: the Termux core had completed the reply and was idle while the web UI still showed Thinking. A stream reconnect alone did not recover the missing reply.
- MainActivity now reloads the current authenticated local web route after returning from a stopped activity, so messages/status are fetched again. The core is not restarted by this lifecycle handler and no prompt is resubmitted. Backend changes and new project intents retain their own navigation flow.
- Device regression testing is intentionally left to the user at their request. Build log: work/resume-sync-build.log.
## 0.4.11 — 2026-09-21

- Replaced the system-font settings glyph with OpenCode's settings-gear vector at 22dp inside a 44dp touch target.
- Runtime and Files & Android use upstream terminal/folder icons and settings navigation styles, grouped in one row on phone-sized screens.
- APK build passed (work/settings-icons-final-build.log). No device interaction/regression tests run; user is handling verification.

## 0.4.14 — 2026-09-22

- Added in-app APK downloads through Android DownloadManager, visible percentage and size, cancellation/retry, and persisted download recovery. Available updates appear as a blue arrow in the chat header.
- Installation uses Android's installer and a private FileProvider URI after checking APK size, available GitHub SHA-256 digest, package ID, newer version and matching signing certificate.
- APK build, 13 JVM tests and Android lint passed. UpdateDownloadTest passed on realme C75: real DownloadManager transfer from a local fixture, progress completion, controller recreation, invalid APK rejection and cancellation cleanup. No model calls.
- Installed the same-signed 0.4.14-alpha APK over the existing app. End-to-end installation of a future production release through the new UI remains to be verified when one is available.
