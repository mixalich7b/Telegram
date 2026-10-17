# Master → feat/wg-embed: план разрешения merge

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Завершить текущий merge с сохранением изменений Telegram 13.0.2 и всей WireGuard/AmneziaWG-интеграции.

**Architecture:** Принять выделение upstream `tgcalls` в submodule. Перенести наши изменения на закреплённую версию библиотеки; рекомендуемый способ хранения — патчи в основном репозитории и воспроизводимая подготовка исправленного дерева вне JNI sources. Сборка и static guards должны использовать одно и то же исправленное дерево.

**Tech Stack:** Android Java, C++17/CMake, Gradle, Go 1.24.4, Git submodules.

**Spec:** `AGENTS.md`, `docs/tunnel-integration.md`, `docs/tunnel-testing.md`, `docs/tunnel-ui-configuration.md`; фактические стороны текущего merge.

## Зафиксированное состояние

- Ветка: `feat/wg-embed`; HEAD: `46dcdf0971a73f99fc93bbb503ae980985178a45`.
- MERGE_HEAD = master: `f32ffeec763f0bdbf4bec25b52d654f77bff733d` — 13.0.2 (7175).
- Общая база: `dc780e81ed1261c369c27870e8e0999a1eb0b600`.
- Фактический входящий диапазон: 11 first-parent коммитов, 15 с учётом боковых веток. Поэтому работать от MERGE_HEAD и merge-base, а не от условного `master~12`.
- 16 неразрешённых файлов: 13 modify/delete в `voip/tgcalls`, один CMake, две иконки. Большое число `D` в status объясняется переносом библиотеки.
- Новый submodule `TMessagesProj/jni/tgcalls` закреплён на `ccbb57965020768c94f5b205ea08547a7e0fe49d`, но пока не инициализирован. Точное соответствие новых C++ API старым ещё не проверено.
- Также не инициализированы `wallet-engine`, `third_party/boost-regex`, `third_party/libprisma`.
- `tlottie`: индекс ожидает `31f1b542f88e7b4be9a01e749920d857535fc715`, рабочая копия находится на `92df98dc209bc39b1e567ec74a8c86a0af5239de`; внутренних изменений `git status --short` не показывает.
- При подготовке плана исходники, индекс и состояние merge не изменялись; добавлен только этот документ. Сборка не запускалась.

## Global Constraints

- Не вводить Android `VpnService`, `/dev/tun`, device-level VPN или silent direct fallback.
- Сохранить один `libtg-tunnel-go.so`; native target filters: `tmessages.49`, `tg-wg`, `tg-awg`.
- Сохранить Keystore storage, route mutual exclusion, lifecycle queue и generation-bound handles.
- Сохранить все пять native VoIP-путей, tunnel DNS, UDP/P2P policy, raw reflector framing и TLS через `SSLAdapter`.
- SOCKS5 CONNECT обязателен для обычного proxy-for-calls; UDP ASSOCIATE остаётся неподдержанным.
- Не записывать секреты в исходники, патчи или план; не передавать Telegram/signing параметры через `-P`.
- Единственная каноническая команда проверки этого merge: `./gradlew --no-daemon tunnelPackageDebug`.
- Не прерывать долгую Gradle-сборку. Не коммитить промежуточное состояние незавершённого merge.

## Review Focus

1. Guards читают старые файлы, а CMake компилирует новый чистый upstream: исключить ложную успешную проверку.
2. Native/WASM pump меняет конфигурацию во время звонка: descriptor route policy должна сохраняться.
3. Одновременные download TCP-попытки, включая ошибку IPv6: не перезапускать runtime при успешной или ещё ожидающей попытке.
4. Отложенный callback WEB carrier после включения туннеля: не восстановить обычный proxy и не сбросить tunnel route.
5. Чистая копия репозитория: патчи должны применяться без незакоммиченных изменений submodule и без заранее подготовленного дерева.

## 1. Сохранить исходное состояние и получить новую базу tgcalls

**Файлы:** `.gitmodules`, gitlinks в индексе, текущие конфликтные файлы.

- [x] Сохранить вне репозитория список `git ls-files -u`, status, staged/unstaged diff, копии конфликтных файлов и текущего индекса. Не отменять merge и не пересоздавать его поверх текущей работы.
- [x] Повторно проверить status каждого затрагиваемого submodule; сохранить обнаруженные локальные изменения до переключения ревизий.
- [x] Инициализировать входящие submodules на ревизиях индекса, привести чистый `tlottie` к его ожидаемому SHA. Не использовать `--remote` и не выбирать последние версии зависимостей.
- [x] Для 13 modify/delete файлов сравнить три содержательных версии: файл из merge-base, файл из HEAD и соответствующий файл `tgcalls/tgcalls/...` нового submodule. Экспортировать наш diff от merge-base как исходный материал переноса.
- [x] Проверить также изменения нового upstream в `RawTcpSocket`, `ReflectorRelayPortFactory`, `InstanceV2PumpImpl`, дескрипторах и фабриках: они участвуют в маршрутизации, даже если Git не показывает конфликта.

**Результат:** карта соответствия API и полный набор переносимых изменений. До чтения нового submodule нельзя считать перенос механическим переименованием путей.

## 2. Сделать изменения tgcalls воспроизводимыми

**Создать:** `Tools/prepare-tgcalls.py`, `patches/tgcalls/series`, `patches/tgcalls/0001-tunnel-routing.patch`, `patches/tgcalls/README.md`.

**Изменить:** `TMessagesProj/build.gradle`, `TMessagesProj/jni/CMakeLists.txt`, `TMessagesProj/jni/voip/CMakeLists.txt`.

**Контракт:** `prepare-tgcalls.py --source <submodule> --output <build-dir> --series <series-file>` готовит дерево с подкаталогом `tgcalls/`, возвращает ненулевой код при несовместимой базе/патче. Gradle task `prepareTunnelTgcalls` предоставляет `TMessagesProj/build/generated/tunnel-tgcalls`; CMake использует его через `TG_TGCALLS_SOURCE_DIR`. Все пути относительны корню репозитория, абсолютные пути разработчика не фиксируются.

- [x] Закрепить в manifest/README ожидаемый upstream SHA. Патч должен содержать перенос правок 13 файлов и добавление `TunnelPacketSocketFactory.cpp/.h`.
- [x] Подготовку делать из чистой закреплённой базы, с проверкой применимости патча; не изменять checkout submodule и не подхватывать его незакоммиченные правки. Повторный запуск должен быть идемпотентным; разные ABI не должны одновременно менять общее дерево.
- [x] Связать подготовку с `verifyTunnelStaticGuards` и всеми Android native configure/build входами, включая обычные assemble-задачи. Учитывать SHA, патчи и скрипт как inputs. Отсутствующее/неверное дерево — ошибка, без перехода на чистый upstream.
- [x] В конфликтном CMake принять новый список upstream sources, заменить корень tgcalls на подготовленное дерево и добавить `TunnelPacketSocketFactory.cpp` ровно один раз. Проверить также Android platform sources и весь блок v2wasm за пределами маркеров конфликта.
- [x] Согласовать include roots для JNI и всех tgcalls targets. В частности, прямой include `tgcalls/tgcalls/group/GroupInstanceCustomImpl.h` в `org_telegram_messenger_voip_Instance.cpp` должен читать исправленную версию, как и `Instance.h`/`GroupInstanceImpl.h`. Не смешивать оригинальные и исправленные определения структур.
- [x] Добавить проверку подготовки в каноническую цепочку: чистый output, повторный запуск, изменение patch input, несовпадение SHA и неприменимый patch. Последние два сценария обязаны завершаться ошибкой; submodule остаётся чистым.

**Почему этот вариант:** изменения хранятся в основном репозитории, не требуется публикация отдельного fork. Простые локальные правки внутри submodule не воспроизводятся после clone. Fork с опубликованным исправленным SHA допустим как альтернативная стратегия, но не является предпосылкой этого плана.

## 3. Перенести политику звонков на новые исходники

**Файлы внутри подготовленного дерева и соответствующего патча:**

| Группа | Файлы относительно `tgcalls/` | Сохраняемое поведение |
|---|---|---|
| Контракт | `Instance.h`, `TunnelPacketSocketFactory.cpp`, `TunnelPacketSocketFactory.h` | Tunnel protocol marker; TCP/UDP/DNS bridge; generation handles; полная запись TCP; raw reflector; TLS |
| Legacy private | `NetworkManager.cpp`, `NetworkManager.h` | Synthetic network/socket factories; tunnel P2P UDP; relay-only при запрете P2P |
| V2 custom | `v2/NativeNetworkingImpl.cpp`, `v2/NativeNetworkingImpl.h`, `v2/ReflectorPort.cpp` | Tunnel TCP/UDP, отсутствие underlying device factory, SOCKS5 CONNECT и raw framing |
| V2 reference | `v2/InstanceV2ReferenceImpl.cpp` | Tunnel DNS/socket factories, TCP TURN с `?transport=tcp`, корректный ICE filter |
| Native/WASM pump | `v2wasm/CallCoreHost.cpp`, `v2wasm/CallCoreHost.h` | Descriptor proxy и P2P policy на создании и reconfigure |
| Group/live | `group/GroupInstanceCustomImpl.cpp`, `group/GroupInstanceImpl.h`, `group/GroupNetworkManager.cpp`, `group/GroupNetworkManager.h` | Передача proxy из descriptor, tunnel sockets/DNS, обычные proxy restrictions |

- [x] Переносить наши изменения на новые реализации upstream, сохраняя входящие исправления. Не заменять новые файлы целиком версиями из HEAD.
- [x] Сверить Java/JNI bridge: `messenger/voip/Instance.java`, `NativeInstance.java`, `VoIPService.java`, `ui/Stories/LivePlayer.java`, `jni/voip/org_telegram_messenger_voip_Instance.cpp`.
- [x] Сохранить WebRTC-правки основного репозитория в `basic_packet_socket_factory.cc`, `socket_adapters.cc/.h`, `socks5_handshake.h`; согласовать их с новым `ReflectorPort`.
- [x] Перенести guards на реально компилируемое дерево, сохранить проверки всех пяти путей и обоих вызовов pump route policy. Проверять, что CMake не использует старые `voip/tgcalls` и непатченные upstream implementations.
- [x] После переноса удалить 13 старых конфликтных файлов и перенести два не конфликтовавших `TunnelPacketSocketFactory` в патч. Не оставлять старое дерево как источник ложноположительных guards.

**Результат:** каждый выбранный tunnel route остаётся fail-closed; ordinary proxy и tunnel-for-calls=false сохраняют прежнюю семантику.

## 4. Разрешить ресурсы и проверить бесконфликтные участки

**Файлы:** `TMessagesProj/src/main/res/drawable/mini_gram_16.xml`, `mini_gram_72.xml`; tgnet, WEB proxy и UI-файлы ниже.

- [x] Принять обе иконки из MERGE_HEAD. Diff HEAD против merge-base для них содержит только whitespace; содержательных локальных изменений для сохранения нет.
- [x] Проверить auto-merge в `jni/tgnet/ConnectionsManager.cpp/.h`, `Defines.h`, `DownloadRequestBudget.h`, `TgNetWrapper.cpp`, Java `tgnet/ConnectionsManager.java`. Сохранить 8 download connections и новый byte budget, а также tunnel lifecycle callbacks и отдельное состояние tunnel route.
- [x] В `TunnelControllerTest.java` добавить сценарий восьми параллельных попыток: ошибки части адресов не перезапускают runtime, пока остались pending либо есть success; группа без успеха запускает recovery после завершения попыток. Выполнение входит в общую каноническую проверку.
- [ ] Проверить `WebProxyTransport.java`, `NetworkRouteSettings.java`, `ConnectionsManager.java`: новые Base64/WebView callbacks не обходят tunnel gating, stop и блокировку delayed rotation. Сохранить guards на startup/setProxySettings и проверить сценарий позднего callback вручную.
- [x] Проверить изменения `LoginActivity.java` и `LaunchActivity.java`: доступ к proxy/tunnel до авторизации сохраняется. Сверить остальной UI и Keystore restrictions с `AGENTS.md`.
- [x] Сохранить входящие LE Audio изменения в `VoIPService`/`VoipAudioManager`; изменения аудиоустройства не должны менять выбранный сетевой маршрут.

## 5. Обновить документацию и проверить весь merge

**Файлы:** `AGENTS.md`, `docs/tunnel-integration.md`, `docs/tunnel-testing.md`, `patches/tgcalls/README.md`; `docs/tunnel-ui-configuration.md` — только если при адаптации изменится UI.

- [x] Обновить code map, описание подготовленного дерева, обновления upstream SHA/patch series и guards. Зафиксировать, что изменения submodule вручную не являются поставляемой интеграцией.
- [x] Проверить отсутствие unmerged entries (`git ls-files -u`), маркеров конфликтов в изменённых исходниках и ошибок `git diff --check` / `git diff --cached --check`. Использовать явные пути для staging, не `git add .`.
- [x] Запустить ровно одну каноническую команду: `./gradlew --no-daemon tunnelPackageDebug`. Не запускать дополнительно `tunnelCheck` или его внутренние задачи. При реальной ошибке исправить причину и повторить ту же команду.
- [x] Проверить фактические результаты JVM/Go/host C++ tests, static guards, native compilation и APK packaging; убедиться, что подготовленное дерево используется в compiler inputs. В APK — один Go runtime и три ожидаемые tunnel libraries на поддерживаемых ABI.
- [ ] Выполнить доступные device smoke tests: WG и AWG; private P2P on/off; relay TCP/UDP; group/live; tunnel-for-calls off для новой сессии; недоступный сервер; Wi-Fi/LTE; параллельные загрузки; обычный SOCKS5; WEB callback после включения tunnel. Для DNS/direct leak проверки использовать packet capture/server logs. Недоступные device проверки явно отметить как невыполненные.
- [x] Перед merge commit проверить итоговый diff относительно обоих родителей, чистоту submodules, отсутствие generated tree/секретов в staging и доступность всех закреплённых gitlinks. После завершения проверок создать один merge commit с обоими исходными родителями.

**Критерий завершения:** конфликтов нет, все локальные доработки представлены в коммитируемых файлах/патчах, каноническая сборка проходит, ручные проверки и их ограничения отражены в отчёте. Удаление конфликтных маркеров само по себе завершением не считается.

## Результат выполнения — 2026-10-17

- 16 конфликтов разрешены; старые VoIP-копии удалены после переноса всех 15 локально изменённых/добавленных файлов в patch series.
- Подготовка проверена девятью тестами, включая запуск внутри родительского Git-репозитория. Имена `Tools/` согласованы с фактическим регистром отслеживаемого каталога для Linux.
- Независимый review не выявил замечаний. Чистая подготовка в отдельном временном каталоге дала те же 269 файлов; compiler inputs используют исправленные реализации всех пяти VoIP-путей.
- `./gradlew --no-daemon tunnelPackageDebug`: **BUILD SUCCESSFUL**, 10m 21s; 72 JVM-теста без ошибок, Go/host C++/preparation/static checks и APK packaging прошли.
- Все четыре ABI собраны. APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`.
- Ручные проверки звонков, DNS/direct leaks и поздних WebView callbacks не выполнялись: `adb devices -l` не показал подключённых устройств. Соответствующие пункты выше оставлены незакрытыми; static/code review не заменяет проверку трафика на устройстве.
- Входящие generated wallet wrappers и wallet patch содержат upstream whitespace-предупреждения; локальные изменения разрешения конфликтов проходят `git diff --check` при проверке их исходников. Исходные upstream-файлы не переформатировались.
- Состояние до исправлений и полный build log сохранены вне репозитория в `/private/tmp/telegram-merge-20261017`.
