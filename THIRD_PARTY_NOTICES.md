# Third-party components

Opencode-PE (Pocket OpenCode) is an unofficial client and Android host for OpenCode. It is not affiliated with Anomaly, Bun, or Termux.

- OpenCode 1.18.31: https://github.com/anomalyco/opencode/tree/v1.18.31 — MIT. The module graph is recovered from the artifact below; loader adaptations relocate asset/module paths and restore ordinary JS imports for compiled wrappers exporting WASM paths.
- The official GUI is extracted from the pinned OpenCode 1.18.31 release. Mobile integration uses pocket-mobile.js and pocket-mobile.css. The settings gear and launcher artwork come from the upstream MIT-licensed UI (launcher source: packages/ui/src/assets/favicon/favicon.svg, adapted to Android vector/adaptive icon resources). Dependencies retain their own licenses.
- Android artifact v0.2.1: https://github.com/guysoft/opencode-termux/releases/tag/v0.2.1 — MIT build scripts and patches. Its old Bun executable is discarded; native OpenTUI and C++ support libraries are retained.
- Bun 1.4.2 official Android: https://github.com/oven-sh/bun/releases/tag/bun-v1.4.2 — MIT, with bundled third-party components including JavaScriptCore/WebKit; see upstream LICENSE and third-party notices.
- Termux packages: https://github.com/termux/termux-packages — package recipes. Each package retains its own license. Exact downloaded packages, versions and SHA-256 hashes are recorded in runtime/sources.json. In particular Git is GPL-2.0; Node.js is MIT with third-party components; ripgrep is MIT/Unlicense; ICU is Unicode; OpenSSL is Apache-2.0.
- AndroidX / Jetpack Compose — Apache-2.0; Kotlin / kotlinx.coroutines — Apache-2.0; OkHttp — Apache-2.0.
- npm and its dependencies retain their included LICENSE files in the runtime assets.

Native payloads retain their upstream licenses. Corresponding source packages and required notices must accompany redistribution as required by each component's license. Exact package versions and download hashes are recorded in runtime.lock.json and the generated runtime/sources.json.
