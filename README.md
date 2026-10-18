# M3U8 → TV

Android-приложение, которое играет HLS-ссылки (`.m3u8`) и передаёт их на телевизор
или приставку по DLNA/UPnP.

Главная идея: **телевизор сам по себе бесполезен для большинства ссылок**. Он умеет
запросить плейлист, но не пришлёт `Referer`, `Cookie` или нужный `User-Agent`, которые
требуют CDN. Поэтому приложение умеет работать в двух режимах:

| Режим | Что происходит | Когда использовать |
|---|---|---|
| **Напрямую** | Телевизору уходит исходная ссылка | Ссылка открытая, без заголовков. Задержка минимальная. |
| **Через телефон** | Телефон поднимает HTTP-сервер, переписывает плейлист и отдаёт сегменты сам | Ссылка требует заголовков, есть редиректы, сложные вложенные плейлисты. |

---

## Возможности

- Поиск DLNA-рендереров в локальной сети (SSDP `M-SEARCH` + пассивное слушание `NOTIFY`).
- Управление транспортом по UPnP: `SetAVTransportURI` → `Play` / `Pause` / `Stop`,
  опрос `GetTransportInfo` и `GetPositionInfo`, громкость через `RenderingControl`.
- **Локальный HLS-прокси**: переписывает мастер- и медиаплейлисты, AES-128 ключи,
  `EXT-X-MAP`, `EXT-X-MEDIA`, LL-HLS `EXT-X-PART`/`EXT-X-PRELOAD-HINT`,
  content steering — всё это идёт через телефон с нужными заголовками.
- Свои HTTP-заголовки: `User-Agent`, `Referer`, `Cookie`, `Origin`.
- Воспроизведение на самом телефоне через Media3 / ExoPlayer.
- Приём ссылки из браузера (`.m3u8` → «Открыть с помощью») и из меню «Поделиться».
- История последних 20 ссылок вместе с заголовками.
- Фоновый сервис: поиск и передача продолжаются, пока экран выключен.

## Почему без UPnP-библиотеки

Cling / jUPnP — тяжёлые и в значительной части заброшенные, а здесь нужна ровно одна
вещь: discovery + AVTransport. Реализация целиком в проекте — около 500 строк, без
зависимости, которую придётся обновлять, и без лишних 3–4 МБ в APK.

Зависимости минимальны: `androidx.media3` (ExoPlayer), `okhttp`, `nanohttpd`,
Compose. Больше ничего.

---

## Сборка

Требуется JDK 17 и Android SDK (platform 35, build-tools 35).

```bash
export ANDROID_HOME=/path/to/android-sdk      # в local.properties
./gradlew assembleDebug                      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                    # → app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest                  # юнит-тесты логики
```

Готовые APK лежат в `dist/`:

| Файл | Размер | Что это |
|---|---|---|
| `dist/app-release.apk` | 2.8 МБ | релиз, R8 + shrinkResources, подписан ключом `m3u8dlna-release.jks` |
| `dist/app-release-unminified.apk` | 13.9 МБ | тот же релиз без сжатия — запасной вариант, если R8 что-то сломает |
| `dist/app-debug.apk` | 20.5 МБ | debug-сборка |

Ключ подписи лежит в проекте (`m3u8dlna-release.jks`, пароль `m3u8dlna`) — нужен, чтобы
обновления приложения ставились поверх существующей установки: Android не принимает
обновление, подписанное другим ключом. Если делаете форк — поменяйте свой.

`local.properties` указывает на ваш SDK; в репозитории его быть не должно.

## Установка и запуск

1. Соберите или возьмите `app-debug.apk`.
2. Установите на телефон: `adb install -r app-debug.apk`.
3. Телефон и телевизор — **в одной Wi-Fi сети** (гостевая изоляция в кафе/офисе
   обычно блокирует и SSDP, и поток между устройствами).
3. Вставьте ссылку, нажмите «Найти», выберите устройство и режим.

---

## Как это устроено

```
┌──────────┐   SSDP M-SEARCH / NOTIFY        ┌────────────────┐
│  SSDP    │ ◄─────────────────────────────► │  Телевизор     │
└──────────┘                                 └────────────────┘
      │ LOCATION: /description.xml                  ▲
      ▼                                            │ SOAP AVTransport
┌──────────────────────────────────────────────────┴──────┐
│  DlnaHub: кэш устройств, опрос транспорта, громкость    │
│                                                          │
│  M3u8ProxyServer (nanohttpd)  ──►  CDN с заголовками    │
│   GET /p/<token>/index.m3u8  →  переписанный плейлист   │
│   GET /p/<token>/r.m3u8?u=…  →  вложенный плейлист     │
│   GET /p/<token>/r.ts?u=…    →  сегмент (с Range)       │
└──────────────────────────────────────────────────────────┘
```

Ключевые файлы:

| Файл | Роль |
|---|---|
| `proxy/PlaylistRewriter.kt` | Чистая логика переписывания плейлиста. Покрыта тестами. |
| `proxy/M3u8ProxyServer.kt` | HTTP-сервер: маршрутизация, заголовки, Range, MIME. |
| `dlna/SsdpDiscovery.kt` | Multicast-поиск + фоновый `NOTIFY`. |
| `dlna/DeviceDescriptionParser.kt` | Разбор `description.xml`, выбор вложенного рендерера. |
| `dlna/AvTransportClient.kt` | SOAP-вызовы AVTransport и RenderingControl. |
| `dlna/DlnaHub.kt` | Склейка: discovery + прокси + опрос состояния. |
| `player/LocalPlayer.kt` | ExoPlayer с теми же заголовками. |

## Проверено

- `assembleDebug` и `assembleRelease` — собираются, APK производится, релиз подписан
  (проверено `apksigner verify`), манифест корректен: `minSdk 24`, `targetSdk 35`,
  все запрошенные разрешения на месте, intent-фильтры `VIEW .m3u8` / `VIEW`+HLS-MIME /
  `SEND text/plain` присутствуют.
- `testDebugUnitTest` — **38 тестов, 0 падений**: переписывание плейлиста (относительные
  и абсолютные URI, AES-128 ключи, `EXT-X-MAP`/`EXT-X-MEDIA`, LL-HLS `PART`/`PRELOAD-HINT`,
  content steering, `skd://`, `data:`, BOM, CRLF, `EXT-X-DEFINE`, битая ссылка),
  разбор SSDP-ответов и `NOTIFY` (включая `byebye` и фильтр типов устройств),
  SOAP-экранирование и разбор fault, разбор описания устройства с вложенным
  рендерером, MIME-типы, разбор времени.

Не проверено на живом железе: реальный ТВ в сети и реальный CDN. Протоколы
соответствуют спецификациям, но первый прогон стоит проверить со своим телевизором.

Что было поймано тестами и ревью по ходу разработки (чтобы было видно, что тесты
не декоративные): обёртка MediaServer «украла» AVTransport у вложенного рендерера;
сервисы в `description.xml` лежат внутри `<serviceList>`, а не прямо под `<device>`;
`MulticastSocket.joinGroup` на Android имеет разные перегрузки для 1 и 2 аргументов.

Отдельно про R8: `ViewModel` создаётся рефлексией, а AndroidX отдаёт keep-правило только
для конструктора с `SavedStateHandle`. Без своего правила в `proguard-rules.pro` R8
вырезает конструктор `MainViewModel(Application)` и приложение падает на запуске с
`NoSuchMethodException`. Правило добавлено, а в собранном APK проверено, что конструктор
на месте и остался `public`.

## Ограничения

- Только HTTP(S)-потоки. DRM (Widevine/PlayReady) не поддерживается — ключи с
  `skd://` намеренно не трогаются.
- `EXT-X-SESSION-DATA`/`EXT-X-DEFINE` с IMPORT-переменными не вычисляются.
- IPv4 в локальной сети. Если телефон только на IPv6, прокси не поднимется.
- Поток не перезаписывается: приложение не пишет на диск.

## Разрешения

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
(нужен для MulticastLock при SSDP), `WAKE_LOCK`, `FOREGROUND_SERVICE(_CONNECTED_DEVICE)`,
`POST_NOTIFICATIONS`, `ACCESS_LOCAL_NETWORK` (Android 16+, на старых версиях игнорируется).
