<p align="center">
  <img src="assets/phystenia-ntts-banner.png" alt="Phystenia NTTS" width="900">
</p>

<p align="center">
  <sub><code>LANGUAGE:</code></sub><br>
  <a href="README.md"><img src="https://github.com/cryals/ACatalog/blob/main/data/flag/english.webp?raw=true" alt="English README" width="24" height="16"></a>
  <a href="README_RU.md"><img src="https://github.com/cryals/ACatalog/blob/main/data/flag/russia.webp?raw=true" alt="Russian README" width="24" height="16"></a>
</p>

<p align="center">
  <b>Text-to-speech for Minecraft chat with Simple Voice Chat.</b><br>
  A Phystenia fork of <a href="https://github.com/Nonne46/ntts-voicechat">Nonne46/ntts-voicechat</a>, maintained with project-specific changes.
</p>

NTTS converts Minecraft chat messages to speech via [/N/TTS](https://ntts.fdev.team/) and plays them through [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat). Install NTTS on the server; install Simple Voice Chat on both the server and players' clients.

## Features

- Proximity or global speech playback.
- Default, random, and player-selected voices and effects.
- Operator commands for runtime, queue, and player settings.
- Memory and optional disk caching.
- Optional local Prometheus metrics endpoint for Grafana dashboards.

## Compatibility

Fabric and Forge builds are available for Minecraft 1.19.2, 1.20.1, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.11, 26.1.2, and 26.2. NeoForge 21.1.248 is supported only for Minecraft 1.21.1.

## Installation

1. Install the matching Fabric, Forge, or NeoForge loader.
2. Install Simple Voice Chat on the server and clients.
3. Put the matching NTTS JAR in the server's `mods/` directory.
4. Start the server once to create `config/ntts.properties`.
5. Configure the token, then restart the server or run `/ntts reload`.

Get a token from [/N/TTS on Boosty](https://boosty.to/ntts). Prefer the `NTTS_TOKEN` environment variable instead of storing a token in the configuration file.

## Configuration

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

- `mode=local` plays speech near the sender; `mode=global` plays it to everyone.
- `voice_mode=static` uses the default voice; `voice_mode=random` assigns a temporary random voice.
- `disk_cache_enabled=true` enables the cache in `config/ntts-cache`.
- Prometheus is disabled by default. With `metrics_enabled=true`, `GET /metrics` listens on loopback only. Set `metrics_port=0` to select a free port; use an external bind address only intentionally.

## Commands

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

Run `/ntts help` for full syntax.

## Prometheus / Grafana

```yaml
scrape_configs:
  - job_name: ntts
    static_configs:
      - targets: ["127.0.0.1:9464"]
```

Useful PromQL metrics include `ntts_request_duration_seconds_bucket`, `ntts_queue_wait_duration_seconds_bucket`, `rate(ntts_requests_total{result="completed"}[5m])`, `ntts_rate_limit_events_total`, and `ntts_tts_available`.

## Building

Install JDK 21 and JDK 25 or 26 for the complete build matrix:

```bash
scripts/build.sh                 # all loaders and versions
scripts/build.sh fabric          # all Fabric versions
scripts/build.sh forge 1.20.1    # Forge for Minecraft 1.20.1
scripts/build.sh neoforge 1.21.1 # NeoForge 21.1.248
```

Built JARs are written to `dist/`.

## License

The repository history before commit [`c17169ad393d403b9603b95871d5afe9c4d29d7f`](https://github.com/Phystenia/ntts-minecraft/commit/c17169ad393d403b9603b95871d5afe9c4d29d7f) is licensed under [MIT](LICENSE-MIT.TXT). Starting with that commit, this Phystenia fork is proprietary and distributed under the [Phystenia Project License](LICENSE.TXT). Some files may carry separate third-party licenses.
