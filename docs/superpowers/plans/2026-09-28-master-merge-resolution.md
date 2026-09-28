# Master → feat/wg-embed: план разрешения merge

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Завершить текущий merge трёх коммитов master, сохранив обновления Telegram и маршрутизацию WireGuard/AmneziaWG, включая новые пути звонков.

**Architecture:** Принимать новую структуру master и переносить в неё политику маршрутов текущей ветки. `NetworkRouteSettings` управляет взаимным исключением proxy/tunnel; `TunnelManager` и native tunnel socket API сохраняют приоритет и fail-closed. Проверять также автоматически слитые изменения, особенно создание VoIP-сессий.

**Tech Stack:** Android Java, Gradle, JNI/CMake, C++17/WebRTC, Go netstack, WAMR.

**Spec:** `AGENTS.md`, `docs/tunnel-integration.md`, `docs/tunnel-ui-configuration.md`, `docs/tunnel-testing.md`; запрос пользователя — план исправления текущего merge.

## Исходное состояние на 2026-09-28

- Ветка: `feat/wg-embed`; HEAD: `9d32e2470`; MERGE_HEAD/master: `dc780e81e`; merge-base: `9552e5541`.
- Входят ровно три коммита: `2ad0f47d3` (submodules), `c84801762` (12.10.4 / 7099), `dc780e81e` (12.10.5 / 7105).
- 10 unmerged-файлов, 23 блока маркеров; 14 блоков находятся в `ProxyListActivity.java`.
- Staged diff затрагивает 6581 файл. Большой объём удалений включает emoji и vendored native-код; это не тысячи отдельных конфликтов.
- `absl` и `wamr` не инициализированы. Checkout `tlottie` и `media` отстаёт от gitlink в индексе; локальных изменений внутри этих двух подмодулей при осмотре нет.
- Этот документ — единственное изменение при подготовке плана. Конфликты и индекс не исправлялись; сборка не запускалась.

## Global Constraints

- Не применять целиком `ours`/`theirs` к ветке или крупным смешанным файлам. Сопоставлять base, HEAD и MERGE_HEAD.
- Продолжать существующий merge в этом checkout; не abort/reset/rebase и не создавать промежуточные обычные коммиты до разрешения всего индекса.
- Не вводить `VpnService`, `Builder.establish()`, `/dev/tun` или device-level VPN.
- При включённом туннеле выбранный для него трафик не должен уходить напрямую, включая DNS, UDP, TCP, P2P и новые VoIP-версии. Отключённый tunnel-for-calls остаётся явным прямым режимом.
- Сохранить Keystore, запрет plaintext fallback, ограничения устройств без безопасного хранилища, serial lifecycle queue, поколения socket handles и complete-frame TCP writes.
- Сохранить одну `libtg-tunnel-go.so`, цели `tmessages.49`, `tg-wg`, `tg-awg`, существующий Go pin `github.com/amnezia-vpn/amneziawg-go v0.2.18` и требование `go 1.24.4`.
- Не включать реальные ключи, endpoints, Telegram APP_ID/APP_HASH и signing credentials в изменения или отчёты.
- Единственная каноническая команда проверки этой работы: `./gradlew --no-daemon tunnelPackageDebug`; не добавлять `-P`. Не останавливать долгую Gradle-сборку.

## Review Focus

1. `proxy` вычислен, но вместо него передаётся `null`: проверка фактического аргумента `Instance.makeInstance`, задача 3.
2. Новая версия звонка создаёт прямые сокеты: контроль registered versions 18/19 и factory/DNS/ICE политики, задача 5.
3. Отложенная proxy rotation или закрытие error dialog отключает туннель: защитные условия и сценарии переключения, задачи 2–3.
4. P2P выключен либо TURN доступен только по TCP: relay-only, отсутствие прямых ICE-кандидатов и сохранение `?transport=tcp`, задачи 4–5.
5. Runtime остановлен или сменилось подключение во время звонка: никаких direct retry, старые handles не оживают; проверки и device smoke, задача 6.

## Задача 1. Зафиксировать исходное состояние и подготовить зависимости

**Files:** `.gitmodules`, gitlinks `TMessagesProj/jni/third_party/{absl,wamr}`, `TMessagesProj/jni/tlottie`, `TMessagesProj_Modules/media`.

- [ ] Перед правками сохранить вне репозитория staged/unstaged binary diff, `git ls-files -u`, список untracked-файлов и SHA HEAD/MERGE_HEAD. Не включать приватные конфиги в коммит.
- [ ] Повторить `git status --short` внутри меняемых подмодулей: при появлении пользовательских правок сохранить их до обновления checkout.
- [ ] Выполнить `git submodule sync --recursive` и `git submodule update --init --recursive` до зафиксированных gitlink-коммитов, без `--remote` и `--force`.
- [ ] Проверить `git submodule status`: отсутствуют `-` и `+` у необходимых зависимостей. Ожидаемые gitlinks: absl `54fac219c4ef0bc379dfffb0b8098725d77ac81b`, wamr `25bd7eb63e828e4bd242cc9b38d260b4b31c6605`, tlottie `92df98dc209bc39b1e567ec74a8c86a0af5239de`, media `c430d207677071b1873f9f18266d55ec45722180`.

## Задача 2. Объединить Java route policy и новый пакет proxy

**Files:** `TMessagesProj/src/main/java/org/telegram/messenger/{NetworkRouteSettings,ProxyRotationController}.java`, `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`, `TMessagesProj/src/main/java/org/telegram/ui/{LaunchActivity,ProxySettingsActivity}.java`.

**Interfaces:** Сохранить `NetworkRouteSettings.enableProxy(SharedConfig.ProxyInfo)`, `disableProxy()`, `TunnelManager.applyTunnelSettingsForAccount(int)` и `ConnectionsManager.setProxySettings(boolean, ProxySettings)`. Тип `ProxySettings` теперь из `org.telegram.utils.proxy`.

- [ ] Перенести импорты всех трёх proxy-классов на `org.telegram.utils.proxy.*`, включая неконфликтующий `NetworkRouteSettings.java` и `VoIPService.java`; сохранить импорты `TunnelManager`.
- [ ] В `ProxyRotationController` оставить вызов `NetworkRouteSettings.enableProxy(info)` и проверки tunnel state перед отложенной проверкой/переключением. Не дублировать запись настроек и уведомления из master.
- [ ] В конфликте `LaunchActivity` сохранить `!TunnelManager.isUserEnabled()` и сброс обычного proxy-for-calls. Закрытие старого proxy error dialog не должно менять активный tunnel route.
- [ ] В `ProxySettingsActivity` совместить новые импорты с существующими tunnel guards и переключением через route policy; проверить реальные usages перед удалением импортов.
- [ ] Проверить автоматически слитые `ApplicationLoader`, `SharedConfig`, `ConnectionsManager` и `utils/proxy/WebProxyTransport`: восстановление настроек, сохранение `proxy_type`, остановку WEB carrier, приоритет туннеля для всех accounts.
- [ ] Проверка группы: `rg -n 'org\.telegram\.proxy' TMessagesProj/src` не находит старых импортов. Не заменять вслепую `org.telegram.localization.*`: перенос одного класса не означает перенос всего пакета.

## Задача 3. Сохранить UI и передачу маршрута в VoIP

**Files:** `TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java`, `TMessagesProj/src/main/res/values/strings.xml`, `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java`.

- [ ] В 14 блоках `ProxyListActivity` сохранить tunnel rows, профильные операции, `Use Tunnel For Calls` и SOCKS5-only `Use Proxy For Calls`. Master удаляет обычный proxy-for-calls, но для этой ветки AGENTS.md требует сохранить его.
- [ ] Согласовать `updateRows`, обработчики нажатий, bind/payload updates, stable ids, `isEnabled`, `getItemViewType`; не ограничиваться сохранением объявления полей.
- [ ] Сохранить ограничения WEB rotation, переключение через `NetworkRouteSettings`, импорт/QR/manual с общей валидацией, подтверждение удаления и остановку активного удаляемого профиля.
- [ ] В `strings.xml` сохранить tunnel/WireGuard/AmneziaWG и обе строки proxy-for-calls из HEAD вместе с остальными ресурсами master. Проверить XML и отсутствие повторяющихся имён string.
- [ ] В `VoIPService` сохранить вычисление `Instance.Proxy`: tunnel имеет приоритет, обычный SOCKS5 разрешён только без активного туннеля; tunnel-for-calls off не включает обычный proxy автоматически.
- [ ] Исправить автоматически слитый вызов `Instance.makeInstance(..., endpoints, null, ...)` на передачу вычисленного `proxy`. Одного сохранения конфликтного блока недостаточно.
- [ ] Расширить static guards в `TMessagesProj/build.gradle`: проверять передачу `proxy` в вызов, а не только наличие `PROTOCOL_TUNNEL` в файле. Включить сценарии tunnel on/off, SOCKS5/MTPROTO/WEB и сохранение Telegram P2P policy в имеющиеся `TunnelVoipRoutingTest.java` / `WireGuardVoipRoutingTest.java` там, где они относятся к чистой Java policy.

## Задача 4. Согласовать CMake и существующие native-пути

**Files:** `TMessagesProj/jni/voip/CMakeLists.txt`, `TMessagesProj/jni/voip/tgcalls/{group/GroupInstanceCustomImpl.cpp,v2/InstanceV2ReferenceImpl.cpp}`. Аудит: `TMessagesProj/jni/CMakeLists.txt`, `TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp`, `tgcalls/{NetworkManager.cpp,group/GroupNetworkManager.cpp,group/GroupInstanceImpl.h,v2/NativeNetworkingImpl.cpp,v2/ReflectorRelayPortFactory.cpp,v2/ReflectorPort.cpp}` относительно каталога `jni/voip`.

- [ ] В VoIP CMake принять обновлённый список исходников master, добавить `voip/tgcalls/TunnelPacketSocketFactory.cpp` ровно один раз; не дублировать `Message`, `InstanceImpl` и group sources из старого конфликтного блока.
- [ ] Сохранить C++17, Abseil/WAMR и новые зависимости master вместе с tunnel JNI/link configuration. Проверить Gradle target filters и единственный Go runtime.
- [ ] В `GroupInstanceCustomImpl` принять новые callback signatures без `std::shared_ptr<PlatformContext>`, сохранить `_proxy`, его инициализацию и передачу в `GroupNetworkManager`. Сопоставить descriptor, streaming arguments и JNI callbacks.
- [ ] В `InstanceV2ReferenceImpl` сохранить tunnel socket/network/DNS factories, базовые типы указателей, обработку обычных proxy, P2P/relay-only и TCP TURN URL.
- [ ] Добавить новый пятый аргумент `resolveRemoteCandidateIp` в оба создаваемых `ReflectorRelayPortFactory` в reference path. Использовать upstream custom parameter; в tunnel branch underlying device socket factory остаётся `nullptr`.
- [ ] Сохранить upstream validation ICE servers и защиту от ошибочного STUN на reflector при отсутствии STUN-сервера; не отключать P2P или действительные STUN-серверы только из-за включённого туннеля.
- [ ] Проследить новый remote-candidate resolution до resolver: в tunnel режиме DNS идёт через tunnel bridge. Проверить все callers обновлённого конструктора, а также legacy, V2 custom, group и live paths.
- [ ] Проверка группы: static guards контролируют tunnel DNS, отсутствие `set_proxy` для tunnel, relay-only при P2P off, reflector framing и TLS через `SSLAdapter`; несовместимые upstream API исправляются в рамках задачи до итоговой сборки.

## Задача 5. Поддержать новый зарегистрированный VoIP-путь 18/19

**Files:** `TMessagesProj/jni/voip/tgcalls/v2wasm/{CallCoreHost.cpp,CallCoreHost.h,InstanceV2PumpImpl.cpp}`, `TMessagesProj/build.gradle`. Аудит: `v2wasm/ReferenceCallCore.cpp`, `v2/InstanceV2CompatImpl.cpp`, `group/GroupInstanceReferenceImpl.cpp`, `org_telegram_messenger_voip_Instance.cpp` относительно `jni/voip`.

**Выявлено:** `InstanceV2PumpImpl` зарегистрирован на `__aarch64__` для 18.0.0/19.0.0. `CallCoreHost` не сохраняет `descriptor.proxy`; `executePcCreate` безусловно создаёт BasicPacketSocketFactory/BasicNetworkManager. `ReferenceCallCore` отбрасывает TCP ICE servers. Это отдельная семантическая несовместимость вне merge-маркеров.

- [ ] Сохранить `descriptor.proxy` в `CallCoreHost` и использовать полиморфные типы socket/network factory, как в reference path.
- [ ] В `executePcCreate(json11::Json const &command)` добавить tunnel factories и async DNS resolver, tunnel-safe reflector factory и route policy для обычного SOCKS5. Для tunnel не вызывать `BasicPortAllocator::set_proxy`.
- [ ] Для proxy/tunnel режима формировать или корректировать ICE server configuration на host стороне из `_rtcServers`, сохранив TCP TURN и `?transport=tcp`, проверку credentials и hostname policy. Не полагаться на уже отфильтрованный `command["iceServers"]`.
- [ ] Применить P2P/relay-only ограничения из descriptor на host стороне: команды core не могут расширить разрешённый маршрут. Сохранить STUN reflector guard и working P2P с корректным STUN.
- [ ] Оставить маршрут и credentials на host стороне; не менять ABI или embedded WASM ради выбора сокетов. Одинаковая host policy должна действовать для native core 18 и embedded core 19.
- [ ] Добавить guards для сохранения descriptor proxy, tunnel factory/DNS selection, TCP TURN и P2P ограничений в новом host. Не считать одни текстовые guards доказательством отсутствия сетевых утечек.
- [ ] Проверить reachability `InstanceV2CompatImpl` и `GroupInstanceReferenceImpl`: присутствие в CMake не равно использованию Android runtime. Если обнаружится достижимый альтернативный путь — адаптировать его до завершения merge; если недостижимый — зафиксировать это в документации без ненужной переделки.

## Задача 6. Проверить итог и подготовить завершение merge

**Files:** `TMessagesProj/build.gradle`, `AGENTS.md`, `docs/{tunnel-integration,tunnel-ui-configuration,tunnel-testing}.md`; остальные файлы только по найденным регрессиям.

- [ ] Обновить актуальное имя пакета proxy в AGENTS.md и документации. Добавить новый host в native code map, перечень аудита, coverage и smoke matrix. Исторические планы не переписывать.
- [ ] Сопоставить итог с обоими родителями: изменения master сохранены, tunnel hooks в автоматически слитых Java/JNI/tgnet файлах не потеряны. Массовые удаления emoji/native dependencies проверить как часть upstream reorganization, не восстанавливать целиком.
- [ ] Проверить отсутствие конфликтных маркеров и `git diff --check`; адресно добавить разрешённые файлы в индекс. `git diff --name-only --diff-filter=U` должен быть пуст. Не использовать бездумный `git add .`.
- [ ] Выполнить `./gradlew --no-daemon tunnelPackageDebug`. Ожидается BUILD SUCCESS с JVM/Go/static guards/native/APK verification. Дополнительные низкоуровневые Gradle-команды не подменяют эту проверку; повтор нужен только после исправления выявленных ошибок.
- [ ] Device smoke при наличии устройства и тестовых серверов: WG и AWG, до авторизации, переключение proxy/tunnel, сбой startup и смена сети, private P2P on/off, relay UDP/TCP, group/conference/live, версии 18/19 на arm64, tunnel-for-calls off и SOCKS5-for-calls. При включённом tunnel-for-calls capture не должен показывать прямого трафика к relay/peer или системного DNS для этих соединений.
- [ ] Если device/server проверки недоступны, явно отметить их как невыполненные; успешная сборка не доказывает сетевой fail-closed на устройстве.
- [ ] Завершать merge одним merge-коммитом после review и требуемых проверок в рамках разрешённого пользователем выполнения. Проверить обоих родителей merge-коммита; push не входит в текущий запрос на план.

## Порядок и критерий готовности

Выполнять последовательно: зависимости → Java policy → UI/VoIP bridge → существующий native → новый host → общая проверка. UI/resources механически проще, но основная зона риска — native API и новые зарегистрированные VoIP-версии.

Готово к завершению merge, когда unmerged index пуст, master functionality и tunnel invariants сохранены, каноническая сборка прошла, а результаты и ограничения runtime-проверок записаны. До реализации этот план не является заявлением об исправленном коде.

## Результат выполнения

- Разрешены все 23 конфликтных блока, обновлены подмодули и proxy imports.
- Восстановлены UI proxy-for-calls и передача proxy в Java/JNI.
- Туннельные socket/network/DNS factories добавлены в host 18/19, включая
  повторную конфигурацию; legacy reflector больше не получает device factory.
- Исправлено сохранение TCP host-кандидатов при P2P off в legacy/V2 custom.
- При ревью обнаружен отсутствующий SOCKS5 CONNECT в WebRTC. Восстановлена
  обработка для обычного TCP и raw reflector; обычные proxy исключают UDP relay.
  Добавлены проверяемый handshake и host C++17 regression tests.
- Итоговый `./gradlew --no-daemon tunnelPackageDebug`: BUILD SUCCESSFUL,
  1m 53s; JVM: 70 tests, 0 failures/errors; Go, static guards, SOCKS5 tests,
  четыре ABI и APK packaging verification прошли.
- SOCKS5-тесты также прошли с UBSan. ASan на этом macOS завис в инициализации
  runtime до main (подтверждено sample); зависший тест остановлен.
- Независимый reviewer сообщил две проблемы, исправленные выше, затем был
  остановлен лимитом сервиса. Оставшийся review выполнен автором.
- Device/server smoke и packet capture не выполнялись: сетевое поведение на
  реальном сервере требует отдельной проверки.
