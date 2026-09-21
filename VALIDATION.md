## 0.4.5 — 2026-09-20

- APK assets include npm @sigstore/protobuf-specs/dist/__generated__/envelope.js. The default underscore-directory filter was removed.
- Native ARM64 launchers built with Android NDK r29; opencode 1.18.31, npm/npx 11.19.1 and curl 8.22.0 run from PATH.
- Build, JVM tests and lint passed (work/package-tools-build.log).
- PackageToolsTest passed on realme C75: actual npm installation of jsonc-parser@3.3.1, dependency execution returning 42, and HTTPS via curl. First background run hit realme's process freezer; foreground rerun passed in 13.661 seconds (work/package-tools-device-test.log).
- Independent isolated Bun 1.4.2 install/import passed. The historical ChildProcess.kill error was not reproduced; it is not claimed fixed.
- User plugin configuration and credentials were not edited. Full plugin login/rotation is outside these checks.
# Проверки Pocket OpenCode

## 0.3.3-alpha — русский и английский в Android-части

194 собственных текста собраны в `UiText.kt`: обычные Android-экраны, Compose, кнопки и подсказки, уведомление службы, статусы ядра и сообщения об ошибках. Для других языков используется английский. Язык поступает из штатной cookie `oc_locale` оригинального GUI, проверяется по списку поддерживаемых OpenCode значений и сохраняется в приватных настройках приложения. При загрузке локального HTML сохранённый выбор применяется до запуска GUI. JavaScript-интерфейс к Android не добавлен. Активный WebView и ядро при смене языка не перезапускаются; статус хранит ключ перевода вместо уже переведённой строки.

- Финальная сборка APK, тестового APK, 7 JVM-тестов и Android Lint прошли (`work/i18n-final-build.log`). JVM-проверки включают соответствие параметров всех переводов, форматирование пользовательских значений, выбор запасного языка и проверку cookie.
- `LanguageSyncTest` на realme C75: **OK (1 test), 9.593 с** (`work/i18n-device-verified-test.log`). Проверены английский, русский и английский для `de`, подписи и accessibility-текст кнопки, сохранение того же объекта API и отсутствие полной перезагрузки WebView. Сигнал cookie имитируется тестом; отдельно выполнена проверка через настоящий список языков GUI.
- Первоначальные прогоны выявили гонки в тесте: cookie могла существовать до инициализации контекста языка, а стартовая страница могла восстановить прежний SPA-маршрут после фиксации пути. Тест теперь ждёт инициализации GUI и проверяет отсутствие полной перезагрузки через маркер окна. Первый прогон также частично проходил при заблокированном телефоне.
- Через **Settings → General → Language** на телефоне переключены английский и русский. Проверены английская верхняя панель, настройка среды и экран встроенных инструментов; горизонтального переполнения GUI нет. После принудительного закрытия приложения и повторного запуска язык GUI и Android-панели остался русским. Доказательства чтения UI: `work/i18n-english.xml`, `work/i18n-en-runtime.xml`, `work/i18n-en-tools.xml`, `work/i18n-ru-restarted.xml`.
- На момент финальной проверки выбран встроенный режим. Полный переход между ядрами для проверки языка отдельно не выполнялся; подготовка HTML с сохранённой локалью проверена в тесте и при повторном запуске.

Установлена версия **0.3.3-alpha**, versionCode 8. Ядро OpenCode остаётся 1.17.9. Запросы к моделям не отправлялись, авторизация провайдеров не менялась. После проверки оставлен русский язык.

## 0.3.2-alpha — встроенная авторизация ChatGPT/Codex

В окружении встроенного ядра и в launcher Termux значение `OPENCODE_DISABLE_DEFAULT_PLUGINS` изменено на `false`. Это возвращает штатные встроенные плагины авторизации, включая Codex. Payload ядра не изменён.

`assembleDebug`, JVM-тесты и Android Lint прошли. APK версии 0.3.2-alpha (versionCode 7) установлен поверх предыдущего на realme C75. В работающем оригинальном GUI, режим Termux, проверен диалог «Подключить OpenAI»: доступны `ChatGPT Pro/Plus (browser)`, `ChatGPT Pro/Plus (headless)` и API-ключ. Экран выбора оставлен открытым для пользователя. Вход в аккаунт, получение токенов и запросы к модели не выполнялись. Авторизация в режиме встроенного ядра отдельно на устройстве не проверялась.

## 0.3.1-alpha — постоянная кнопка нового чата

В Android-панель добавлена кнопка **+ Чат**, независимая от условий отображения бокового меню upstream GUI. Проект определяется по текущему маршруту WebView, в том числе после переходов внутри GUI; Android Intent не используется как запасной устаревший путь. Быстрые повторные нажатия блокируются на время создания сессии. Если проект не выбран, предлагается открыть проекты. Payload ядра Termux не изменён и повторно не копируется при этом обновлении GUI.

`NewChatTest` на realme C75 / Android 15, Termux: **OK (1 test)**. Проверены видимость кнопки, ровно одна новая сессия при двойном нажатии, сохранение исходной сессии, создание чата в другом проекте после навигации WebView при старом Android Intent, наличие редактора и отсутствие горизонтального переполнения/экрана ошибки. Платные запросы не отправлялись. При первом прогоне callback evaluateJavascript был потерян во время полной навигации; ожидание загрузки в тесте теперь допускает повтор такой проверки. Итоговый прогон завершился без ошибок.

`assembleDebug`, `assembleDebugAndroidTest`, JVM-тесты и Android Lint прошли. Версия 0.3.1-alpha, versionCode 6 установлена на телефон. Старые чаты не удалялись.

## 0.3.0-alpha — приоритет Termux

На realme C75 / Android 15 установлена совместимая сборка Termux 0.118.3 из официальных GitHub Releases. Google Play `googleplay.2026.06.21` не содержит RUN_COMMAND; это подтверждено манифестом установленного пакета. Замена выполнена с согласия пользователя; данные Pocket OpenCode сохранены.

Проверки на устройстве:

- `TermuxIntegrationTest`: **OK (2 tests), 12,983 с**. В режиме «Автоматически» запущено реальное ядро в Termux, `/path` возвращает его HOME, Git берётся из Termux PREFIX. ZIP с UTF-8 файлом импортирован и экспортирован без изменений; повторный импорт не перезаписывает существующий проект.
- `AgentLoopTest`, `pocketBackend=termux`: **OK (1 test), 22,805 с**. Настоящий агент с локальной тестовой моделью запросил разрешения на write/bash, создал hello.js и выполнил Node.js, Git и ripgrep. Проверены HOME и путь Git именно из Termux, вывод инструментов и содержимое файлов. Тестовая сессия и проект удалены, платных запросов нет.
- `AgentLoopTest`, встроенная среда: **OK (1 test), 43,346 с**. После добавления второго окружения встроенный агент продолжает выполнять ту же задачу. Этот тест выполнен до последних изменений только в настройке Termux; не является отдельной проверкой телефона с физически отсутствующим Termux.
- Последняя сборка `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`: успешно. JVM: 3 проверки Workspace и 2 проверки локальной передачи (неверный токен/маршрут не раскрывают данные; авторизованная передача сохраняет байты).

Во время подготовки свежего Termux обнаружена несовместимость старого OpenSSL из bootstrap с новыми curl/Node.js. Для устройства установлен проверенный пакет OpenSSL 3.6.3 из закреплённого runtime.lock.json. В мастере подключения добавлена проверка фактического запуска утилит; при недостающих/неработающих инструментах их установка выполняется через apt вместе с OpenSSL. Полная автоматическая установка на второй чистой системе отдельно не прогонялась.

История чатов и ключи не синхронизируются между окружениями. Копирование проекта переносит только файлы. LSP/плагины/произвольные дополнительные языковые цепочки не входят в проведённые проверки. Исходный GUI OpenCode не менялся; новый экран настройки среды и Android-инструменты добавлены в оболочку.

## Исправление 0.2.2-alpha — инструмент bash

Подтверждена ошибка переноса standalone-модулей Bun: `import("wrapper.js", {with:{type:"wasm"}})` в обычном Bun возвращал имя JS-файла вместо экспортированного пути WASM. Tree-sitter пытался компилировать JavaScript как WebAssembly и падал до выполнения команды. В извлечении ядра восстановлены четыре таких импорта; WASM-файлы и проверки разрешений не изменялись.

На realme C75 / Android 15 обновлённый `AgentLoopTest` завершился **OK (1 test)**, 36,142 с. Настоящий OpenCode получает вызовы инструментов от локального имитатора модели, запрашивает разрешение на запись и отдельно на `bash`, создаёт `hello.js`, затем через **инструмент агента bash** выполняет `node hello.js`, `git --version`, `rg` и запись контрольного файла. Проверены статусы инструментов, вывод Node/Git и содержимое файла `bash-ok.txt`. Прямой запуск через `Engine.command` для этой проверки не используется. Временные проект и сессия удаляются; платные модельные запросы не отправляются.

Android-сборка, JVM-тесты и Lint прошли. Установленная версия: 0.2.2-alpha, versionCode 4. История старых ошибок в пользовательских сессиях сохраняется.

## Исправление 0.2.1-alpha — падение после первого сообщения

На реальном диалоге с Big Pickle выявлен `TypeError: getLogicalScrollOffset is not a function` в виртуализированном списке сообщений. Причина — скрипт изолированной сборки GUI не перенёс `patchedDependencies` из upstream OpenCode. Пустой чат из первоначального WebUiTest не задействовал этот код.

Скрипт теперь переносит и применяет четыре штатных UI-патча: SolidJS, TanStack Solid Virtual, TanStack Virtual Core и Pierre Trees. Lockfile обновлён. WebUiTest дополнен созданием настоящих сообщений через shell API и проверкой интерфейса после асинхронного изменения размеров списка. Повторный запрос к платной модели для этой проверки не нужен.

Проверено на realme C75 / Android 15: **OK (1 test)**, 23,902 с. Тест проверяет успешное исполнение команды API, появление элементов диалога, сохранение редактора запроса и отсутствие экрана ошибки после обновления размеров. Вывод shell в родном GUI свёрнут, поэтому тест проверяет результат команды через API и отображение элементов диалога, а не требует видимого текста свёрнутого вывода.

Также повторно открыт исходный пользовательский диалог: сообщение «Ку» и сохранённый ответ Big Pickle «Привет! Чем могу помочь?» отображаются без ошибки. Новый запрос модели не отправлялся. Версия 0.2.1-alpha (versionCode 3) установлена на телефон. Android-сборка, JVM-тесты и Lint завершились успешно.

## Обновление 0.2.0-alpha — родной GUI (17 сентября 2026)

Основной интерфейс заменён на production-сборку настоящих `packages/app` и `packages/ui` OpenCode v1.17.9. Она включена в APK и работает через Android WebView с локальным ядром. Исходные компоненты, стили и экран настроек OpenCode не переписывались. Android-экран сохраняет функции импорта/экспорта и управления средой.

`WebUiTest` на realme C75 / Android 15: **OK (1 test)**, 10,606 с. Тест запускает приложение, открывает созданный проект через настоящую сессию ядра, дожидается загрузки родного интерфейса, проверяет адрес выбранного проекта, наличие редактора запроса и отсутствие горизонтального переполнения страницы. Платные модельные запросы не отправлялись. Исправлен переход к проекту: ссылка на пустой маршрут сессии заменена открытием конкретной сессии.

Android-сборка, инструментальный тестовый APK, JVM-тесты и Lint собраны успешно. Lint: 0 ошибок, 13 предупреждений (включая использование JavaScript в WebView и устаревшие API отступов). Включение JavaScript нужно для штатного GUI; доступ WebView к произвольным file/content URL отключён.

Далее сохранены результаты проверки встроенного ядра версии 0.1.0. Их не следует считать полным повторным тестированием всех функций GUI 0.2.0. Отдельные сценарии OAuth, вложений, внешних ссылок и Termux с новым GUI не проверялись.

Дата: 16 сентября 2026 года.

## Реальное устройство

realme C75 (RMX3941), Android 15, ARM64, страницы памяти 4 КБ, SELinux Enforcing. Root и Termux не использовались. Проверенный APK установлен на телефон через USB.

Финальный запуск AndroidJUnitRunner: **OK (3 tests)**, 43,958 с.

- `AgentLoopTest`: настоящее ядро OpenCode получает потоковый ответ от локального тестового провайдера, запрашивает разрешение на запись, после подтверждения создаёт `hello.js`, выдаёт ответ в диалоге; Node.js исполняет созданный файл. Тестовая модель имитирует ответы API; платная внешняя модель не проверялась.
- `EngineIntegrationTest`: запускает встроенное ядро, проверяет Bun, Git, Node.js и ripgrep, создаёт сессию, выполняет shell-команду с записью файла и выводом `42`, останавливает и повторно запускает ядро, проверяет сохранённые историю и файл.
- `GitCloneTest`: клонирует публичный репозиторий `guysoft/opencode-termux` по HTTPS, проверяет README и запускает npm 11.19.1. Тестовый клон удаляется после проверки.

Во время проверки исправлены устаревавшие после обновления APK ссылки на нативные библиотеки и гонка остановки/запуска ядра. После этих исправлений все три инструментальных теста прошли одним запуском.

## Сборка и локальные проверки

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`: успешно.
- JVM: 3 теста Workspace — ZIP/Unicode, ограничения распаковки и защита от обхода путей.
- Android Lint: 0 ошибок, 8 предупреждений; в том числе targetSdk 35, отсутствие x86_64 и атрибуты для новых Android. Сетевые проверки новых версий зависимостей отключены; проверки корректности включены.
- LDPlayer 9 / Android 9: интерфейс и запуск Bun проверены. ARM64-трансляция LDPlayer конфликтовала с окружением системных shell-утилит; полный набор тестов на нём не проходит. Основная проверка выполнена на реальном ARM64-телефоне.

## Границы первой версии

Это APK для ручной установки, подписанный отладочным ключом, не магазинный релиз. Минимальный Android — 9; фактически полный сценарий проверен на Android 15 с 4-КБ страницами. Устройства с 16-КБ страницами не проверялись.

Модель работает через интернет/API. Интерфейс поддерживает API-ключи, без OAuth-входа через подписку. Ключи хранит сам OpenCode в приватных данных приложения; отдельно шифруется только пароль локального сервера. Произвольные нативные расширения, LSP, Docker и настольные сборочные цепочки не поддерживаются. Android может остановить среду при нехватке ресурсов.

Импорт и экспорт ZIP проверены на уровне Workspace; все комбинации системных файловых провайдеров и UI-сценариев не тестировались. Режим подключения к Termux реализован, но отдельная установленная среда Termux в эту проверку не входила.
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
