# Спецификация NTTS VoiceChat

Статус: draft  
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
9. Обновить CI, README и список поддерживаемых сборок.

