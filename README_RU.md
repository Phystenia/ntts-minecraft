<p align="center">
  <img src="assets/phystenia-ntts-banner.png" alt="Phystenia NTTS" width="900">
</p>

<p align="center">
  <sub><code>ЯЗЫК:</code></sub><br>
  <a href="README.md"><img src="https://github.com/cryals/ACatalog/blob/main/data/flag/english.webp?raw=true" alt="English README" width="24" height="16"></a>
  <a href="README_RU.md"><img src="https://github.com/cryals/ACatalog/blob/main/data/flag/russia.webp?raw=true" alt="Русский README" width="24" height="16"></a>
</p>

<p align="center">
  <b>Озвучивание игрового чата Minecraft через Simple Voice Chat.</b><br>
  Форк Phystenia проекта <a href="https://github.com/Nonne46/ntts-voicechat">Nonne46/ntts-voicechat</a> с изменениями для проекта.
</p>

NTTS озвучивает сообщения Minecraft через [/N/TTS](https://ntts.fdev.team/) и воспроизводит их с помощью [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). NTTS устанавливается на сервер, а Simple Voice Chat — на сервер и клиентам игроков.

## Возможности

- Локальное или глобальное воспроизведение речи.
- Голоса и эффекты по умолчанию, случайные и выбранные игроком.
- Команды оператора для управления модом, очередью и игроками.
- Кеш в памяти и необязательный кеш на диске.
- Необязательный локальный endpoint метрик Prometheus для Grafana.

## Совместимость

Сборки Fabric и Forge доступны для Minecraft 1.19.2, 1.20.1, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.11, 26.1.2 и 26.2. NeoForge 21.1.248 поддерживается только для Minecraft 1.21.1.

## Установка

1. Установите подходящий загрузчик Fabric, Forge или NeoForge.
2. Установите Simple Voice Chat на сервер и клиентам.
3. Поместите подходящий JAR NTTS в серверный каталог `mods/`.
4. Один раз запустите сервер, чтобы создать `config/ntts.properties`.
5. Настройте токен, затем перезапустите сервер или выполните `/ntts reload`.

Токен доступен на [странице /N/TTS в Boosty](https://boosty.to/ntts). Рекомендуется передавать его через переменную окружения `NTTS_TOKEN`, а не хранить в конфигурационном файле.

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

- `mode=local` воспроизводит речь рядом с отправителем, `mode=global` — для всех.
- `voice_mode=static` использует голос по умолчанию, `voice_mode=random` назначает временный случайный голос.
- `disk_cache_enabled=true` включает кеш в `config/ntts-cache`.
- Prometheus выключен по умолчанию. При `metrics_enabled=true` `GET /metrics` слушает только loopback. `metrics_port=0` выбирает свободный порт; внешний адрес задавайте только осознанно.

## Команды

```text
/set_speaker <speaker>
/set_effect <effect|none>

/ntts help
/ntts status
/ntts enable|disable|reload
/ntts get <key>
/ntts set <key> <value>
/ntts reset <key|all>
/ntts test
/ntts say <message>
/ntts player ...
/ntts queue clear
```

Полный синтаксис доступен через `/ntts help`.

## Prometheus / Grafana

```yaml
scrape_configs:
  - job_name: ntts
    static_configs:
      - targets: ["127.0.0.1:9464"]
```

Для PromQL используйте, например, `ntts_request_duration_seconds_bucket`, `ntts_queue_wait_duration_seconds_bucket`, `rate(ntts_requests_total{result="completed"}[5m])`, `ntts_rate_limit_events_total` и `ntts_tts_available`.

## Сборка

Для полной матрицы сборки установите JDK 21 и JDK 25 или 26:

```bash
scripts/build.sh                 # все загрузчики и версии
scripts/build.sh fabric          # все версии Fabric
scripts/build.sh forge 1.20.1    # Forge для Minecraft 1.20.1
scripts/build.sh neoforge 1.21.1 # NeoForge 21.1.248
```

Готовые JAR-файлы появятся в `dist/`.

## Лицензия

История репозитория до коммита [`c17169ad393d403b9603b95871d5afe9c4d29d7f`](https://github.com/Phystenia/ntts-minecraft/commit/c17169ad393d403b9603b95871d5afe9c4d29d7f) распространяется по [MIT](LICENSE-MIT.TXT). Начиная с этого коммита данный форк Phystenia является проприетарным и распространяется по [лицензии Phystenia Project](LICENSE.TXT). Для отдельных файлов могут действовать сторонние лицензии.
