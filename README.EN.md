# NTTS for Minecraft

[Русская версия](README.md)

NTTS turns Minecraft chat messages into speech through [/N/TTS](https://ntts.fdev.team/) and plays them using [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat).

The project supports Fabric, Forge, and NeoForge. NTTS is installed on the server, while Simple Voice Chat is required on both the server and clients.

## Features

- Global or proximity-based speech playback.
- Default or random initial voice assignment.
- Player-selectable voices and effects.
- Server-wide default voice and effect.
- Operator commands for runtime and player management.

## Supported versions

- 1.19.2
- 1.20.1
- 1.21.1
- 1.21.4
- 1.21.5
- 1.21.8
- 1.21.11
- 26.1.2
- 26.2

Separate Fabric and Forge builds are available for every version. A NeoForge 21.1.248 build is also available for Minecraft 1.21.1 under [Releases](https://github.com/Nonne46/ntts-voicechat/releases).

## Installation

1. Install the matching Fabric, Forge, or NeoForge loader.
2. Install Simple Voice Chat on the server and client.
3. Place the matching NTTS jar in the server's `mods/` directory.
4. Start the server to create `config/ntts.properties`.
5. Configure the token, then restart the server or run `/ntts reload`.

Tokens are available from the [/N/TTS Boosty page](https://boosty.to/ntts). Prefer the `NTTS_TOKEN` environment variable over storing a token in the configuration file.

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

- `mode=local` — speech is audible near the sender.
- `mode=global` — every player hears generated speech.
- `voice_mode=static` — give unconfigured players the default voice.
- `voice_mode=random` — give unconfigured players a random voice until NTTS is reloaded.
- `memory_cache_mb` and `cache_ttl_minutes` bound the in-memory LRU cache of decoded PCM audio.
- `disk_cache_enabled=true` enables persistent caching in `config/ntts-cache`; it is disabled by default.
- TTS is skipped when no Simple Voice Chat listener is connected, and matching concurrent phrases share one request.
- `metrics_enabled=true` enables `GET /metrics` for Prometheus. It is disabled by default and listens on `127.0.0.1` when enabled.
- `metrics_port=0` selects a free ephemeral port; the selected port is written to the log and `/ntts status`. Set `metrics_bind_address` to an external address only intentionally.

A voice chosen with `/set_speaker` always takes priority over the initial assignment.

## Commands

Players can use:

```text
/set_speaker <speaker>
/set_effect <effect|none>
```

Main operator commands:

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

Run `/ntts help` for the complete command syntax.

## Prometheus/Grafana

Add this target to `prometheus.yml` on the Minecraft host:

```yaml
scrape_configs:
  - job_name: ntts
    static_configs:
      - targets: ["127.0.0.1:9464"]
```

Basic Grafana panels can use these PromQL expressions:

```text
ntts_request_duration_seconds_bucket
ntts_queue_wait_duration_seconds_bucket
rate(ntts_requests_total{result="completed"}[5m])
rate(ntts_requests_total{result=~"failed|dropped|rejected"}[5m])
ntts_rate_limit_events_total
ntts_quota_exhausted
ntts_tts_available
```

## Building

Install JDK 21 and JDK 25 or 26 for the complete build matrix:

```bash
scripts/build.sh                 # every loader and version
scripts/build.sh fabric          # every Fabric version
scripts/build.sh forge 1.20.1    # Forge for Minecraft 1.20.1
scripts/build.sh neoforge 1.21.1 # NeoForge 21.1.248
```

Built jars are written to `dist/`.

## License

[MIT](LICENSE) © 2026 Nonne
