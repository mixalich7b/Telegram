# План разрешения merge upstream master

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Завершить текущий merge, сохранив изменения Telegram 12.10.3 и маршрутизацию WireGuard/AmneziaWG.

**Architecture:** Принять upstream-модель `org.telegram.proxy.ProxySettings` и WEB-прокси. Сохранить `NetworkRouteSettings` как слой переключения маршрутов, приоритет туннеля в `ConnectionsManager` и native tunnel bridge. TLS ClientHello оставить в новых upstream-файлах `TLSHello.cpp/.h`.

**Tech Stack:** Java, Android, C++, JNI, Go, Gradle 8.13 / AGP 8.13.2.

**Spec:** `AGENTS.md`, `docs/tunnel-integration.md`, `docs/tunnel-ui-configuration.md`, `docs/tunnel-testing.md`; запрос пользователя ограничен анализом и планом.

## Исходное состояние

Анализ выполнен 2026-09-22 без разрешения конфликтов и изменения индекса:

- Ветка: `feat/wg-embed`.
- `HEAD`: `7d1af8980` — `Merge branch 'master' into feat/wg-embed`.
- `MERGE_HEAD`: `9552e5541e1274b9557c9832b204dbfcaf44b3dc` — `update to 12.10.3 (7089)`.
- Общая база: `62b56a07ca7e30e39f7fd00a6728d6bbd716ca1c`.
- 8 unmerged-файлов, 16 конфликтных блоков; все конфликты текстовые, обе стороны изменили файл.
- Маркеры incoming называют ветку `master`. Основание плана — конкретный `MERGE_HEAD`; сетевое обновление upstream для анализа не требуется.
- Сборка и тесты не запускались. Ниже перечислены обнаруженные несовместимости и проверки, которые ещё предстоит выполнить.

## Обязательные ограничения

- При включённом туннеле сохранять fail-closed для выбранного tgnet/VoIP-трафика и для всех аккаунтов.
- Не вводить VpnService, TUN, прямой fallback или вторую Go runtime.
- Сохранить `libtg-tunnel-go.so`, targets `tmessages.49`, `tg-wg`, `tg-awg`, поколения socket handles и учёт параллельных попыток TCP.
- Не переносить lifecycle native runtime на UI thread и не менять правила reconnect/backoff.
- Сохранить шифрование профилей Android Keystore, ограничения неподдерживаемых устройств, импорт/QR и доступ к настройкам до авторизации.
- Переключения proxy/tunnel выполнять через `NetworkRouteSettings`; отключение туннеля не включает прокси обратно.
- Не добавлять реальные ключи, endpoints и Telegram credentials в изменения или вывод.
- Продолжать существующий merge в этом checkout. Не создавать промежуточные коммиты в незавершённом merge и не выполнять массовое `checkout --ours/--theirs` или `git add .`.

## Карта конфликтов

Номера строк относятся к состоянию до разрешения конфликтов. Java-пути в таблице указаны относительно `TMessagesProj/src/main/java/org/telegram/`.

| Файл | Блоки | Причина и решение |
|---|---:|---|
| `TMessagesProj/jni/tgnet/ConnectionSocket.cpp:49` | 1 | В одном блоке туннельный bridge и удалённая upstream встроенная реализация TLS. Сохранить `TunnelBridge`, `TunnelSocketState`, close/write/relay helpers до конца namespace на строке 222. Удалить старые `get_y2`, `get_double_x`, генераторы ключей и `class TlsHello`; использовать уже добавленные `TLSHello.h/.cpp` и вызов `TLSHello::getDefault()`. |
| `messenger/AndroidUtilities.java:4740` | 1 | Новый обработчик получает объект `settings`, старые `address/port/user/password/secret` здесь больше не подходят. Использовать `NetworkRouteSettings.enableProxy(new SharedConfig.ProxyInfo(settings))`. Сохранить upstream разбор/отображение WEB-ссылок. |
| `messenger/ProxyRotationController.java:73` | 1 | Сохранить `NetworkRouteSettings.enableProxy(info)` и одно уведомление `proxyChangedByRotation`. Upstream-проверки доступности через `proxyInfo.settings` оставить. Не возвращать прямую запись route prefs в rotation controller. |
| `tgnet/ConnectionsManager.java:48,640,1011` | 3 | Объединить imports. Принять `ProxySettings.fromSharedPreferences` и новую сигнатуру `setProxySettings(boolean, ProxySettings)`. В init и применении настроек сохранить приоритет `TunnelManager.applyTunnelSettingsForAccount`; WEB transport не должен запускаться до решения о туннельном маршруте. |
| `ui/LaunchActivity.java:6081` | 1 | Сохранить `if (!TunnelManager.isUserEnabled())` при закрытии ошибки proxy; внутри перейти на `ConnectionsManager.setProxySettings(false, null)`. При активном туннеле не сбрасывать маршрут. |
| `ui/ProxyListActivity.java:59,589,666,691,1094,1169` | 6 | Объединить imports; сохранить все tunnel rows/handlers; убрать дублирующие prefs/native-вызовы upstream, оставив policy layer. Перейти с `info.secret` на `info.settings`. Объединить tunnel rows с условием upstream, скрывающим rotation для WEB. Calls row скрывать при туннеле и для типов, не поддерживающих SOCKS calls. |
| `ui/ProxySettingsActivity.java:57,241` | 2 | Принять builder `ProxySettings`, тип WEB и нормализацию host. Сохранить `routeChanged` и централизованное включение через policy layer. Для редактирования выключенного текущего proxy записывать `currentProxyInfo.settings.toSharedPreferences(editor)` без включения маршрута. |
| `build.gradle:133` | 1 | Добавить upstream-блок namespace для media libraries внутрь существующего `subprojects`, сохранив фильтрацию JLatexMath assets и все canonical tunnel tasks. Одна завершающая скобка `subprojects`. |

## Обнаруженные проблемы вне маркеров

1. **Подтверждённая несовместимость Java API:** `NetworkRouteSettings.java:23–33,43` обращается к удалённым полям `ProxyInfo.address/port/username/password/secret` и старому шестипараметрическому `setProxySettings`. `SharedConfig.ProxyInfo` теперь содержит `settings`, а `ConnectionsManager` принимает два параметра. Одного снятия восьми конфликтов недостаточно для компиляции.
2. **Lifecycle WEB-прокси:** автоматически слитый `setProxySettings` запускает `WebProxyTransport.start` перед циклом аккаунтов, в котором находится tunnel guard. `NetworkRouteSettings.enableTunnel` сейчас вообще не останавливает WEB transport. Нужна согласованная остановка активного WEB carrier при переходе на туннель и запрет его запуска при активном/заблокированном туннеле. Остановка не должна временно переводить native маршрут в direct.
3. **Тип proxy теперь существенен:** запись старого набора prefs потеряет `proxy_type`; проверка только пустоты secret недостаточна для различения SOCKS5/WEB. Для обычных proxy calls использовать явный SOCKS5 type, сохраняя приоритет tunnel-for-calls.
4. **Зависимости сборки:** не инициализированы `TMessagesProj_Modules/media`, `TMessagesProj/jni/td`, `TMessagesProj/jni/third_party/boringssl`. `tlottie` checkout остаётся на `3ce946c9e`, а индекс требует `31f1b542f`. Это не дополнительные merge-конфликты, но препятствия подготовке сборки.
5. **Native/build миграция:** upstream перенёс prebuilt libraries в `jni/prebuild/lib/<ABI>`, обновил include paths, Gradle/AGP, перешёл на Media3 и удалил `libtgvoip`/`InstanceImplLegacy`. Эти изменения слиты автоматически; требуется совместная проверка с JNI-туннелями. Удалённый `InstanceImplLegacy` не равен сохраняемому `tgcalls/NetworkManager.cpp` из списка обязательного аудита.
6. **Другие локальные изменения:** `LoginActivity`, `PhotoViewer`, `SecretMediaViewer` изменялись обеими сторонами. В рабочем дереве видны сохранённые настройки до входа и swipe distance/velocity hooks; требуется краткая регрессионная проверка после Media3 migration.

## Review Focus

- Холодный старт с enabled tunnel и сохранённым WEB proxy: не запускается основной WEB carrier, каждый аккаунт остаётся в tunnel/blocked.
- Переключение WEB → WG/AWG во время соединения: WEB carrier прекращает работу, нет переходного direct fallback.
- Изменение выключенного proxy при активном туннеле: профиль сохраняется с правильным `proxy_type`, туннель остаётся включён.
- Отложенный callback rotation / закрытие proxy error после включения туннеля: маршрут не переключается обратно на proxy/direct.
- Неудачный TCP dial одного адреса, параллельный успешный dial другого и устаревший handle: сохраняются существующие generation/backoff/fail-closed гарантии.

## Порядок выполнения

### 1. Подготовить зависимости и объединить build.gradle

**Файлы:** `build.gradle`; проверить `.gitmodules`, `settings.gradle`, `TMessagesProj/build.gradle`, `TMessagesProj_App*/build.gradle`, `TMessagesProj/jni/CMakeLists.txt`, `TMessagesProj/jni/voip/CMakeLists.txt`.

- [ ] Повторно проверить `HEAD`, `MERGE_HEAD`, `git ls-files -u` перед правками; если пользователь продолжил merge, адаптировать план к новому состоянию.
- [ ] Сохранить canonical tasks и объединить `subprojects` согласно таблице. Сохранить upstream Gradle 8.13, AGP 8.13.2 и Media3 modules.
- [ ] Проверить локальные изменения submodules перед checkout. Для чистых зависимостей выполнить `git submodule update --init --recursive`; не использовать `--force`. Не подменять gitlink в индексе старым checkout `tlottie`.
- [ ] Проверить присутствие media `core_settings.gradle`, headers BoringSSL/TD и prebuilt libraries для ABI сборки; не копировать старые бинарники на новые пути вслепую.

**Результат:** конфигурация сохраняет upstream build migration и tunnel command contract; зависимости соответствуют gitlinks merge.

### 2. Разрешить native-конфликт

**Файлы:** `TMessagesProj/jni/tgnet/ConnectionSocket.cpp`; проверить `ConnectionSocket.h`, `TLSHello.cpp/.h`, `TMessagesProj/jni/CMakeLists.txt`.

- [ ] Сохранить tunnel helpers из начала конфликтного блока, удалить только перенесённую TLS-реализацию и маркеры.
- [ ] Проверить сохранность `configureSecretTransport`, ранней tunnel-ветки в `openConnection`, `open/finish/fail/closeTunnelConnection`, callbacks начала/окончания попытки и generation checks.
- [ ] Сохранить `TLSHello.cpp` в CMake и upstream вызов `TLSHello::getDefault()`. В `ConnectionSocket.cpp` не должно остаться `class TlsHello` и копий key-generation helpers.
- [ ] Проверить diff относительно обеих сторон: incoming TLS extraction сохранён, tunnel transport не утрачен; `ConnectionSocket.h` с tunnel declarations уже присутствует и не требует замены upstream-файлом целиком.

**Проверка:** native compilation в единой финальной команде из шага 5; существующие lifecycle/parallel-dial тесты и static guards должны сохраниться.

### 3. Адаптировать policy layer и ConnectionsManager вместе

**Файлы:** `TMessagesProj/src/main/java/org/telegram/messenger/NetworkRouteSettings.java`, `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`; при необходимости lifecycle hooks `TunnelManager.java`; проверить `org/telegram/proxy/WebProxyTransport.java`, `WebProxyConnectionTester.java` и `messenger/voip/VoIPService.java`.

- [ ] В `enableProxy` заменить ручную запись полей на `proxyInfo.settings.toSharedPreferences(editor)` и вызов на `ConnectionsManager.setProxySettings(true, proxyInfo.settings)`. В `disableProxy` использовать `ConnectionsManager.setProxySettings(false, null)`.
- [ ] Сохранить отключение туннеля при осознанном включении proxy. Отключать proxy-for-calls для `settings.getType() != ProxySettings.Type.SOCKS5`; не восстанавливать его автоматически.
- [ ] В `init` применять tunnel route прежде proxy branch. Только если tunnel route не применён, запускать WEB transport или устанавливать SOCKS5/MTPROTO настройки.
- [ ] В `setProxySettings` исключить запуск WEB carrier при `TunnelManager.isUserEnabled()`, сохранив применение tunnel/blocked для каждого аккаунта. Не делать ранний return, теряющий существующий account refresh. Сохранить upstream localhost blocked endpoint при ошибке запуска WEB вне tunnel mode.
- [ ] При включении туннеля останавливать основной `WebProxyTransport` без вызова native direct reset. Сохранить сериализацию lifecycle runtime; не добавлять параллельные start/stop туннеля.
- [ ] Отдельно проверить диагностический WEB tester: у него собственная очередь и `connectionTestInstance`. Не путать проверку proxy с основным Telegram transport; завершённый или отложенный probe не должен включать proxy, выключать tunnel или оставлять основной carrier. Если потребуется отмена queued checks, завершать callbacks с failure и очищать `checking` состояния.
- [ ] В `VoIPService` при чтении обычных proxy prefs учитывать явный SOCKS5 type; сохранить ветки `routeVoipViaTunnel` и `!tunnelEnabled`, `PROTOCOL_TUNNEL`, deliberate direct при выключенном tunnel-for-calls.
- [ ] Проверить cold start для WG/AWG с сохранённым WEB proxy, ошибки старта туннеля и нескольких аккаунтов. Ожидание: основной WEB carrier не создаётся, native route остаётся tunnel/blocked. Проверить WEB → tunnel: carrier остановлен, обычный proxy и rotation выключены.

**Контракт для UI:** `NetworkRouteSettings.enableProxy(SharedConfig.ProxyInfo)` и `disableProxy()` сохраняют публичные сигнатуры; единственный ordinary-proxy runtime API — `ConnectionsManager.setProxySettings(boolean, ProxySettings)`.

### 4. Объединить UI и rotation с новым API

**Файлы:** оставшиеся пять конфликтующих Java UI/messenger-файлов из таблицы, включая `LaunchActivity` и `ProxyRotationController`.

- [ ] Применить решения таблицы для AndroidUtilities, LaunchActivity, ProxySettingsActivity и rotation. Каждое действие должно один раз проходить через policy layer и отправлять ожидаемое уведомление без дубликатов.
- [ ] В ProxyListActivity сохранить все tunnel import/QR/profile/delete/calls branches; объединить imports. Для выбора proxy использовать `info.settings`, убрать прямое применение upstream proxy-настроек рядом с `NetworkRouteSettings.enableProxy`.
- [ ] Условие rotation оставить upstream (`currentProxy.settings.getType() != ProxySettings.Type.WEB`) вместе с tunnel rows. Условие ordinary calls: `!useWireGuardSettings && (SharedConfig.currentProxy == null || SharedConfig.currentProxy.settings.getType() == ProxySettings.Type.SOCKS5)`.
- [ ] Проверить порядок `useProxySettings` → policy change → `updateRows`: `updateRows` читает prefs и не должен затереть новое значение переключателя до сохранения. Проверить включение/выключение ordinary proxy из каждого исходного режима.
- [ ] Проверить добавление SOCKS5/MTPROTO/WEB и переход по соответствующей ссылке при активном туннеле: явное включение proxy отключает tunnel; редактирование выключенного proxy только сохраняет `settings`, включая type.
- [ ] Проверить отложенный rotation callback и закрытие proxy error после включения tunnel: tunnel сохраняется, rotation не возобновляется. Проверить удаление активного tunnel с подтверждением и управление stale enabled state без Keystore.

**Результат:** все 16 блоков разрешены семантически; tunnel UI и upstream WEB UI доступны и согласованы.

### 5. Проверить автоматически слитые изменения, документацию и весь merge

**Файлы проверки:** `TMessagesProj/build.gradle`, `TMessagesProj_App/build.gradle`, `messenger/voip/Instance.java`, `NativeInstance.java`, `VoIPService.java`, JNI `org_telegram_messenger_voip_Instance.cpp`, `tgcalls/NetworkManager.cpp`, `v2/NativeNetworkingImpl.cpp`, `v2/InstanceV2ReferenceImpl.cpp`, `group/GroupNetworkManager.cpp`, `TunnelPacketSocketFactory.cpp`, `ui/Stories/LivePlayer.java`, `ui/LoginActivity.java`, `ui/PhotoViewer.java`, `ui/SecretMediaViewer.java`.

- [ ] Проверить removal libtgvoip без потери актуальных tunnel-enabled WebRTC paths, JNI protocol fields, P2P/relay/group/live routing, tunnel DNS, TLS wrapping и raw reflector framing.
- [ ] Проверить все обычные `setProxySettings` call sites и обращения к ProxyInfo: старой сигнатуры и удалённых полей быть не должно. Проверить ресурсы tunnel UI после upstream изменения генерации strings.
- [ ] Дополнить static guards для нового API, сохранения `proxy_type`, приоритета tunnel перед WEB startup и остановки основного WEB carrier. Не ограничиваться существующей проверкой наличия `TunnelManager.applyTunnelSettingsForAccount` где-либо в файле: она не проверяет порядок исполнения.
- [ ] Сохранить покрытие `TunnelControllerTest`, `TunnelVoipRoutingTest`, `WireGuardVoipRoutingTest`, `WireGuardSettingsTest`, parser/profile/userspace tests. Для изменённой маршрутизации добавить проверки из Review Focus в доступный JVM harness; Android UI/WebView lifecycle проверить на устройстве и явно отметить непроверенное. Не вводить новый emulator pipeline ради merge.
- [ ] Обновить `docs/tunnel-integration.md`, `docs/tunnel-ui-configuration.md`, `docs/tunnel-testing.md`: WEB type, взаимное исключение, переходы, новые проверки. Уточнить соответствующие инварианты `AGENTS.md` для WEB carrier/ordinary calls, если вводятся явные требования.
- [ ] Просмотреть итоговый diff относительно `HEAD` и `MERGE_HEAD`; проверить сохранение локальных swipe distance/velocity изменений и login entry. Удалить все conflict markers, выполнить `git diff --check`. Добавить разрешённые файлы в индекс точными путями; убедиться, что `git ls-files -u` пуст.
- [ ] Запустить одну каноническую полную проверку:

```bash
./gradlew --no-daemon tunnelPackageDebug
```

Без `-P` credentials/signing overrides и без дополнительного отдельного запуска `tunnelCheck`. Эта команда включает JVM/Go/static guards/native APK verification и APK build. Дождаться завершения; не прерывать долгий Gradle build.

- [ ] Устранить обнаруженные ошибки в пределах merge и повторить каноническую проверку только после исправлений. Зафиксировать фактический результат; не считать отсутствие conflict markers доказательством работоспособности.
- [ ] При доступном устройстве: до входа, WG/AWG, WEB/SOCKS5/MTPROTO ↔ tunnel, рестарт приложения, недоступный сервер, смена сети, private P2P/relay/group/live calls, toggle calls только для новых сессий; отдельно фото/видео swipe и PiP. Серверные/packet-capture результаты не заявлять без выполнения.

**Готовность к завершению merge:** unmerged index пуст, сборка и проверки успешны, diff сохраняет обе функциональности, документация согласована. Выполнение merge commit не входит в текущий запрос на анализ и план.

## Результат реализации — 2026-09-23

- Все 8 файлов / 16 конфликтных блоков разрешены; связанные вызовы proxy API,
  lifecycle WEB carrier, UI, rotation и ordinary VoIP адаптированы.
- Инициализированы pinned submodules TD, BoringSSL, Media3; checkout tlottie
  обновлён до gitlink merge. Служебный `.kotlin/` добавлен в `.gitignore`.
- Добавлены два JVM-теста повторных обновлений маршрута для всех аккаунтов
  при сбое туннеля и отсутствии профиля. Усилены static guards и обновлены docs.
- Независимое ревью не выявило ошибок слияния в проверенных местах.
- `./gradlew --no-daemon tunnelPackageDebug`: **BUILD SUCCESSFUL**, 13m 49s,
  316 выполненных задач. JVM: **70 тестов, 0 failures, 0 errors, 0 skipped**;
  Go-тесты, static guards, четыре native ABI и APK packaging verification прошли.
- APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`.
- Проверки на устройстве, реальные WG/AWG-серверы и активные звонки не выполнялись.
  Они остаются ручной проверкой; успешная сборка не заменяет их.
- Merge-коммит не создавался. Разрешение конфликтов и связанные изменения
  подготовлены в индексе текущего merge.
