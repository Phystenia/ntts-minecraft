# NTTS для Minecraft

[English version](README.EN.md)

NTTS озвучивает сообщения игрового чата через [/N/TTS](https://ntts.fdev.team/) и воспроизводит их с помощью [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).

Проект поддерживает Fabric, Forge и NeoForge. NTTS устанавливается на сервер, а Simple Voice Chat должен быть установлен на сервере и у игроков.

## Возможности

- Глобальное или локальное воспроизведение речи.
- Стандартное или случайное начальное назначение голоса.
- Персональные голоса и эффекты для игроков.
- Серверный голос и эффект по умолчанию.
- Команды для управления модом и настройками игроков.

## Поддерживаемые версии

- 1.19.2
- 1.20.1
- 1.21.1
- 1.21.4
- 1.21.5
- 1.21.8
- 1.21.11
- 26.1.2
- 26.2

Для каждой версии доступны отдельные сборки Fabric и Forge. Для Minecraft 1.21.1 также доступна сборка NeoForge 21.1.248 на странице [Releases](https://github.com/Nonne46/ntts-voicechat/releases).

## Установка

1. Установите подходящий загрузчик Fabric, Forge или NeoForge.
2. Установите Simple Voice Chat на сервер и клиент.
3. Поместите соответствующий JAR-файл NTTS в серверный каталог `mods/`.
4. Запустите сервер, чтобы создать `config/ntts.properties`.
5. Укажите токен и перезапустите сервер либо выполните `/ntts reload`.

Токен можно получить на [странице /N/TTS в Boosty](https://boosty.to/ntts). Рекомендуется передавать его через переменную окружения `NTTS_TOKEN`, а не хранить в файле конфигурации.

## Настройка

```properties
enabled=true
token=
mode=local
voice_mode=random
default_speaker=narrator_d3
effect=
allow_player_effects=true
max_text_length=300
memory_cache_enabled=true
memory_cache_mb=16
cache_ttl_minutes=1440
disk_cache_enabled=false
disk_cache_directory=config/ntts-cache
disk_cache_mb=256
metrics_enabled=false
metrics_bind_address=127.0.0.1
metrics_port=9464
```

- `mode=local` — речь слышна рядом с отправителем.
- `mode=global` — речь слышат все игроки.
- `voice_mode=static` — игроки без настройки получают голос по умолчанию.
- `voice_mode=random` — игроки без настройки получают случайный голос до перезагрузки NTTS.
- `memory_cache_mb` и `cache_ttl_minutes` ограничивают LRU-кеш готового PCM-аудио в памяти.
- `disk_cache_enabled=true` включает постоянный кеш в `config/ntts-cache`; по умолчанию он выключен.
- TTS не вызывается, если нет подключённых слушателей Simple Voice Chat; совпадающие одновременные фразы объединяются в один запрос.
- `metrics_enabled=true` включает `GET /metrics` для Prometheus. По умолчанию endpoint выключен и после включения слушает только `127.0.0.1`.
- `metrics_port=0` выбирает свободный ephemeral-порт; фактический порт появляется в логе и `/ntts status`. Внешний bind задавайте только осознанно через `metrics_bind_address`.

Голос, выбранный командой `/set_speaker`, всегда имеет приоритет над начальным назначением.

## Команды

Игроки могут использовать:

```text
/set_speaker <speaker>
/set_effect <effect|none>
```

Основные команды оператора:

```text
/ntts help
/ntts status
/ntts enable
/ntts disable
/ntts reload
/ntts get <key>
/ntts set <key> <value>
/ntts reset <key|all>
/ntts test
/ntts say <message>
/ntts player ...
/ntts queue clear
```

Полный список и синтаксис доступны через `/ntts help`.

## Prometheus/Grafana

Добавьте в `prometheus.yml` на машине Minecraft:

```yaml
scrape_configs:
  - job_name: ntts
    static_configs:
      - targets: ["127.0.0.1:9464"]
```

Для базовых Grafana-панелей используйте PromQL:

```text
ntts_request_duration_seconds_bucket
ntts_queue_wait_duration_seconds_bucket
rate(ntts_requests_total{result="completed"}[5m])
rate(ntts_requests_total{result=~"failed|dropped|rejected"}[5m])
ntts_rate_limit_events_total
ntts_quota_exhausted
ntts_tts_available
```

## Сборка

Для полной сборки установите JDK 21 и JDK 25 или 26:

```bash
scripts/build.sh                 # все загрузчики и версии
scripts/build.sh fabric          # все версии Fabric
scripts/build.sh forge 1.20.1    # Forge для Minecraft 1.20.1
scripts/build.sh neoforge 1.21.1 # NeoForge 21.1.248
```

Готовые файлы появятся в каталоге `dist/`.

## Лицензия

[MIT](LICENSE) © 2026 Nonne
