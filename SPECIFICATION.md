# Спецификация NTTS VoiceChat

Статус: implemented
Целевая платформа: Minecraft 1.21.1 / NeoForge 21.1.248  
Java: 21  
Mod ID: `ntts`

## 1. Назначение

Мод преобразует сообщения игроков Minecraft в синтезированную речь и передаёт готовое аудио игрокам через Simple Voice Chat.

Мод не должен реализовывать собственный голосовой транспорт: Minecraft отвечает за события сервера, внешний TTS-сервис — за синтез, Simple Voice Chat — за доставку звука.

## 2. Архитектура

```text
Minecraft chat event
        |
        v
NeoForge adapter / command layer
        |
        v
NttsPluginCore
        |
        v
bounded synthesis queue
        |
        v
NttsClient -> HTTPS TTS service
        |
        v
WAV -> PCM conversion
        |
        v
Simple Voice Chat AudioPlayer
```

Общий код должен оставаться в `common/`. NeoForge-специфичный код должен находиться в отдельном модуле `neoforge/`. Существующий `forge/` модуль нельзя переименовывать или заменять.

## 3. Функциональные требования

### FR-1. Обработка чата

Мод должен перехватывать сообщения серверного чата, проверять конфигурацию и ставить запрос на синтез в фоновую очередь.

Запрос не должен блокировать основной поток Minecraft.

### FR-2. Синтез речи

Клиент TTS должен отправлять HTTPS GET-запрос на:

```text
https://ntts.fdev.team/api/v1/tts
```

Параметры запроса:

- `speaker` — выбранный голос;
- `text` — очищенный текст;
- `effect` — необязательный эффект;
- `ext=wav` — формат результата;
- `use_48k=1` — частота дискретизации.

Авторизация выполняется через `Authorization: Bearer <token>`.

### FR-3. Подготовка текста

Перед отправкой необходимо:

- удалить неподдерживаемые символы;
- нормализовать пробелы;
- ограничить длину сообщения;
- отбрасывать пустой результат;
- учитывать лимит провайдера и квоту токена.

### FR-4. Выбор голоса

Поддерживаются:

- голос сервера по умолчанию;
- случайный голос для игрока;
- персональный голос игрока;
- административное назначение голоса игроку;
- блокировка изменения голоса игроком.

### FR-5. Режимы воспроизведения

```properties
mode=local
```

`local` — звук слышит только автор сообщения.

```properties
mode=global
```

`global` — звук слышат все игроки онлайн на момент постановки запроса.

### FR-6. Эффекты

Мод должен получать список доступных эффектов, разрешать выбрать эффект игроку и поддерживать серверный эффект по умолчанию.

Администратор должен иметь возможность запретить игрокам выбирать собственные эффекты.

### FR-7. Конфигурация

Основной файл:

```text
config/ntts.properties
```

Поддерживаемые ключи:

```properties
enabled=true
memory_cache_enabled=true
memory_cache_mb=16
cache_ttl_minutes=1440
disk_cache_enabled=false
disk_cache_directory=config/ntts-cache
disk_cache_mb=256
mode=local
voice_mode=random
default_speaker=narrator_d3
effect=
allow_player_effects=true
max_text_length=300
```

Токен может быть задан в конфигурации или через переменную окружения `NTTS_TOKEN`. Переменная окружения имеет приоритет.

### FR-8. Команды

Обязательные команды:

```text
/ntts help
/ntts status
/ntts enable
/ntts disable
/ntts reload
/ntts test
/ntts say <message>
/ntts get <key>
/ntts set <key> <value>
/ntts reset <key|all>
/ntts queue clear
/ntts player inspect <player>
/ntts player voice set <players> <voice>
/ntts player voice reset <players>
/ntts player voice lock <players>
/ntts player voice unlock <players>
/set_speaker <voice>
/set_effect <effect|none>
```

Административные команды должны требовать права оператора. Пользовательские команды должны работать только для игрока, если команда не поддерживает серверный источник.

### FR-9. Состояние игроков

Данные должны сохраняться в JSON-файлах:

```text
config/ntts_speaker_data.json
config/ntts_effect_data.json
config/ntts_player_admin.json
```

Мод должен корректно переживать отсутствие, повреждение или пустое содержимое этих файлов.

## 4. Очередь и отказоустойчивость

- Один фоновый worker для синтеза.
- Максимум 32 ожидающих запроса.
- При переполнении новые запросы отклоняются без остановки сервера.
- Таймаут подключения: 10 секунд.
- Таймаут чтения ответа: 60 секунд.
- Временные ошибки сервиса повторяются ограниченное число раз.
- HTTP 401/403 переводит клиент в состояние `INVALID`.
- HTTP 429 переводит клиент в состояние `RATE_LIMITED`.
- HTTP 5xx и сетевые ошибки переводят клиент в состояние `UNAVAILABLE` с backoff.
- При остановке сервера очередь очищается, а worker завершается.

## 4.1. Кеширование и дедупликация

Мод обязан использовать два независимых уровня кеширования.

### Memory-cache

В памяти хранится LRU-кеш PCM-аудио. Ключ строится из нормализованного текста,
голоса, эффекта, формата и sample rate. Токен не входит в ключ и не должен
попадать в имя или содержимое кеш-файла.

Кеш потокобезопасен, ограничен по размеру и TTL. Просроченные записи не выдаются,
а ошибки кеша не должны останавливать сервер.

### Disk-cache

Постоянный кеш выключен по умолчанию. Файлы именуются SHA-256 ключа синтеза;
исходный текст и токен не должны сохраняться в имени файла, логах или диагностике.
Запись выполняется через временный файл и atomic move. Формат, размер, TTL и
целостность проверяются при чтении. Повреждённые записи игнорируются без падения.

### In-flight дедупликация

Если одинаковый синтез уже выполняется, новый HTTP-запрос не создаётся. Новый
получатель присоединяется к pending request и получает тот же результат после
завершения генерации. Запрещено блокировать единственный synthesis worker через
`Future.get()` или эквивалентное ожидание. Pending-запись всегда удаляется после
успеха или ошибки.

## 4.2. Проверка слушателей

До постановки синтеза в очередь мод обязан проверить получателей:

- в `local` — активное voice-chat соединение говорящего;
- в `global` — хотя бы одно активное соединение среди целевых игроков.

Если слушателей нет, HTTP-запрос к TTS API не выполняется, а счётчик
`skippedNoListeners` увеличивается. Перед воспроизведением соединение проверяется
повторно, потому что игрок мог выйти во время синтеза.

## 4.3. Наблюдаемость

`/ntts status` должен показывать без секретов:

- HTTP requests;
- memory-cache hits/misses/evictions;
- disk-cache hits/misses;
- in-flight deduplicated requests;
- skipped-no-listeners;
- completed/failed/dropped/rejected requests;
- среднюю задержку синтеза;
- размер очереди и число активных задач.

## 4.4. HTTP metrics endpoint для Prometheus/Grafana

Мод должен предоставлять опциональный HTTP endpoint формата Prometheus для сбора
метрик Grafana/Prometheus. Endpoint запускается в отдельном daemon-потоке и никогда
не выполняет блокирующие операции на основном потоке Minecraft или synthesis worker.

### Конфигурация

В `config/ntts.properties` добавить:

```properties
metrics_enabled=false
metrics_bind_address=127.0.0.1
metrics_port=9464
```

`metrics_port=0` означает автоматический выбор свободного порта; фактически выбранный
порт должен быть записан в лог. Порт должен быть в диапазоне `1024..65535`, если не
используется `0`. Значение `metrics_bind_address` по умолчанию `127.0.0.1`; bind на
внешний интерфейс разрешается только явной настройкой.

Endpoint: `GET /metrics`. Для неизвестного пути возвращать `404`, для другого метода
`405`. Ответ должен иметь `Content-Type: text/plain; version=0.0.4` и корректный
Prometheus text exposition format. При остановке или ошибке запуска HTTP-сервера мод
не должен падать: ошибка фиксируется в логах и `/ntts status`.

### Обязательные метрики

Имена и типы должны оставаться стабильными:

```text
ntts_up gauge
ntts_tts_available gauge
ntts_queue_waiting gauge
ntts_queue_active gauge
ntts_queue_capacity gauge
ntts_requests_total counter{result="accepted|completed|failed|dropped|rejected"}
ntts_http_requests_total counter{result="success|failure",status_class="2xx|4xx|5xx|network|timeout"}
ntts_request_duration_seconds histogram
ntts_queue_wait_duration_seconds histogram
ntts_cache_requests_total counter{cache="memory|disk",result="hit|miss"}
ntts_cache_evictions_total counter{cache="memory"}
ntts_inflight_deduplicated_total counter
ntts_skipped_no_listeners_total counter
ntts_rate_limit_events_total counter{source="http|provider"}
ntts_quota_exhausted gauge
ntts_quota_characters_used gauge
ntts_quota_characters_limit gauge
ntts_last_success_timestamp_seconds gauge
ntts_last_failure_timestamp_seconds gauge
ntts_uptime_seconds gauge
```

Для HTTP и request duration использовать buckets, достаточные для различения быстрых
кешированных ответов и медленного TTS, например `0.05, 0.1, 0.25, 0.5, 1, 2.5,
5, 10, 30, 60, +Inf`. Не включать в labels голос, эффект, текст, UUID игрока, token,
URL запроса или произвольное сообщение: cardinality должна быть ограничена.

### Состояния и лимиты

`ntts_up=1` означает, что metrics endpoint запущен и способен ответить. Отдельно
`ntts_tts_available=1` означает готовность TTS-клиента; `0` — `INVALID`,
`RATE_LIMITED`, `QUOTA_EXHAUSTED`, `UNAVAILABLE` или отсутствие credentials.
`ntts_quota_characters_used` и `ntts_quota_characters_limit` не должны раскрывать
token. Если провайдер не отдаёт значение, публиковать `0` и состояние доступности,
а не выдуманное число.

Счётчики должны отражать причины упора в ограничения: HTTP 429, исчерпание квоты,
переполнение bounded queue и отклонённые задачи. Время последнего успеха/ошибки
публиковать в Unix seconds; до первого события значение `0`.

### Lifecycle и эксплуатация

- запускать endpoint после загрузки конфигурации;
- при `/ntts reload` безопасно применять изменения bind address/port, не оставляя
  старый listener;
- при остановке сервера закрывать listener и executor;
- не создавать новый HTTP server на каждый scrape;
- scrape должен быть быстрым и не обращаться к TTS API;
- добавить README-пример подключения Prometheus и базовые Grafana-панели: latency,
  queue, success/error rate, rate limit/quota, TTS availability.

### Критерии приёмки metrics endpoint

- при `metrics_enabled=true` `GET /metrics` возвращает валидный exposition format;
- порт и bind address читаются из `ntts.properties`, значения валидируются;
- endpoint не блокирует Minecraft, TTS worker и не выполняет сетевых TTS-запросов;
- все обязательные метрики присутствуют и обновляются после synthetic/cache/error
  сценариев;
- секреты, тексты и player identifiers отсутствуют в ответе;
- занятый порт обрабатывается без падения мода;
- lifecycle и reload не оставляют потоки/listeners;
- добавлены unit-тесты и NeoForge/Fabric/Forge сборки проходят.

## 5. Аудиопоток

1. TTS-сервис возвращает WAV.
2. `PcmAudio` декодирует WAV в PCM signed 16-bit little-endian.
3. `VoicechatServerApi` создаёт audio channel.
4. `AudioPlayer` получает PCM-сэмплы.
5. После окончания воспроизведения encoder закрывается.

Клиенты не должны напрямую обращаться к TTS-сервису. Токен хранится только на сервере.

## 6. NeoForge-модуль

Необходимо создать:

```text
neoforge/
  build.gradle
  src/main/java/com/ntts/neoforge/NeoForgeNttsMod.java
  src/main/java/com/ntts/neoforge/NeoForgeNttsPlugin.java
  src/main/java/com/ntts/neoforge/NeoForgeEventRegistration.java
  src/main/resources/META-INF/neoforge.mods.toml
```

NeoForge-модуль должен реализовать:

- `@Mod("ntts")` entrypoint;
- регистрацию Simple Voice Chat plugin;
- обработку сообщений чата;
- регистрацию Brigadier-команд;
- события запуска и остановки сервера;
- получение списка `ServerPlayer`;
- NeoForge metadata и зависимости.

Forge API (`net.minecraftforge.*`) нельзя использовать в `neoforge/`.

## 7. Gradle и версии

Целевые параметры:

```text
minecraft_version=1.21.1
neoforge_version=21.1.248
java_version=21
```

Профиль NeoForge должен быть отдельным от текущего Forge-профиля `1.21.1`. Сборка NeoForge не должна изменять поведение Fabric и Forge.

Рекомендуемые задачи:

```powershell
.\gradlew.bat :common:build
.\gradlew.bat :neoforge:build
.\gradlew.bat :neoforge:runServer
.\gradlew.bat :neoforge:runClient
```

## 8. Безопасность

- Не выводить токен в лог.
- Не включать токен в JAR, исходники или Git.
- Использовать только HTTPS.
- Ограничивать длину пользовательского текста.
- Не выполнять текст как команду Minecraft.
- Не отправлять TTS-запросы от имени клиента.

## 9. Критерии приёмки

Реализация считается готовой, если:

- NeoForge-мод собирается с версией `21.1.248`;
- мод загружается на Minecraft 1.21.1 без Forge-зависимостей;
- Simple Voice Chat обнаруживает NTTS plugin;
- `/ntts status` отображает состояние токена и очереди;
- сообщение игрока преобразуется в речь;
- работают режимы `local` и `global`;
- работают выбор голоса и эффекта;
- команды оператора защищены правами;
- данные и конфигурация сохраняются после перезапуска;
- ошибки TTS не останавливают Minecraft-сервер;
- одинаковые параллельные фразы выполняют только один HTTP-запрос;
- при отсутствии voice-chat слушателей HTTP-запрос не выполняется;
- TTL и лимит memory-cache соблюдаются;
- опциональный disk-cache переживает перезапуск и не содержит секретов;
- `/ntts status` показывает метрики кеша и дедупликации;
- текущие Fabric и Forge-сборки продолжают собираться.

## 10. План реализации

1. Зафиксировать NeoForge MDK и совместимый Simple Voice Chat artifact.
2. Добавить NeoForge Gradle-модуль и version profile.
3. Создать NeoForge entrypoint и metadata.
4. Перенести обработчик чата, команды и события сервера.
5. Подключить общий `NttsPluginCore`.
6. Проверить регистрацию Voice Chat plugin и воспроизведение PCM.
7. Добавить smoke-тесты сервера и команд.
8. Проверить конфигурацию, очередь, ошибки, rate limit и остановку сервера.
9. Добавить listener pre-check, in-flight дедупликацию и memory-cache TTL.
10. Добавить опциональный disk-cache с атомарной записью и ограничением размера.
11. Добавить метрики кеша, пропусков и дедупликации в `/ntts status`.
12. Написать unit-тесты для ключей, TTL, LRU, deduplication и повреждённых записей.
13. Проверить NeoForge server smoke-test и регрессию Fabric/Forge.
14. Обновить CI, README и список поддерживаемых сборок.

