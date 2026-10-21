package com.ntts.common;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public abstract class NttsPluginCore implements VoicechatPlugin {
    public static final String MOD_ID = "ntts";

    private static final String CATEGORY_ID = "ntts_cat";
    private static final String DEFAULT_SPEAKER = "narrator_d3";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "NTTS_TOKEN";
    private static final int DEFAULT_MAXIMUM_TEXT_LENGTH = 300;
    private static final int MAXIMUM_CONFIGURED_TEXT_LENGTH = 2_000;
    private static final int QUEUE_CAPACITY = 32;
    private static final int DEFAULT_MEMORY_CACHE_MEGABYTES = 16;
    private static final int DEFAULT_CACHE_TTL_MINUTES = 1_440;
    private static final int DEFAULT_DISK_CACHE_MEGABYTES = 256;
    private static final int MAXIMUM_CACHE_MEGABYTES = 1_024;
    private static final int MAXIMUM_CACHE_TTL_MINUTES = 43_200;
    private static final List<String> HTTP_STATUS_CLASSES = List.of(
            "2xx", "4xx", "5xx", "network", "timeout"
    );
    private static final int NO_FUZZY_MATCH = Integer.MAX_VALUE;
    private static final int SEARCH_TERM_PENALTY = 5;
    private static final List<String> CONFIGURATION_KEYS = List.of(
            "enabled",
            "mode",
            "voice_mode",
            "default_speaker",
            "effect",
            "allow_player_effects",
            "max_text_length",
            "memory_cache_enabled",
            "memory_cache_mb",
            "cache_ttl_minutes",
            "disk_cache_enabled",
            "disk_cache_directory",
            "disk_cache_mb",
            "metrics_enabled",
            "metrics_bind_address",
            "metrics_port"
    );
    private static final Path CONFIG_FILE = Path.of("config", "ntts.properties");
    private static final Path DEFAULT_DISK_CACHE_DIRECTORY = Path.of("config", "ntts-cache");
    private static final Path SPEAKER_DATA_FILE = Path.of("config", "ntts_speaker_data.json");
    private static final Path EFFECT_DATA_FILE = Path.of("config", "ntts_effect_data.json");
    private static final Path PLAYER_ADMIN_DATA_FILE = Path.of("config", "ntts_player_admin.json");

    protected final Logger logger = LoggerFactory.getLogger("ntts-plugin");

    private final Map<UUID, String> speakerData = new HashMap<>();
    private final Map<UUID, String> effectData = new HashMap<>();
    private final Map<UUID, String> adminSpeakerData = new HashMap<>();
    private final Set<UUID> speakerLocks = new HashSet<>();
    private final Map<UUID, String> randomSpeakerData = new HashMap<>();
    private final ConcurrentMap<String, PendingSynthesis> inFlightSyntheses = new ConcurrentHashMap<>();
    private volatile AudioCache audioCache = new AudioCache(
            true,
            megabytesToBytes(DEFAULT_MEMORY_CACHE_MEGABYTES),
            minutesToMillis(DEFAULT_CACHE_TTL_MINUTES)
    );
    private volatile DiskAudioCache diskAudioCache = new DiskAudioCache(
            false,
            DEFAULT_DISK_CACHE_DIRECTORY,
            megabytesToBytes(DEFAULT_DISK_CACHE_MEGABYTES),
            minutesToMillis(DEFAULT_CACHE_TTL_MINUTES),
            logger
    );
    private final ThreadPoolExecutor synthesisExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "ntts-synthesis");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicLong acceptedRequests = new AtomicLong();
    private final AtomicLong completedRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong droppedRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cacheEvictions = new AtomicLong();
    private final AtomicLong diskCacheHits = new AtomicLong();
    private final AtomicLong diskCacheMisses = new AtomicLong();
    private final AtomicLong deduplicatedRequests = new AtomicLong();
    private final AtomicLong skippedNoListeners = new AtomicLong();
    private final AtomicLong httpRequests = new AtomicLong();
    private final AtomicLong timedRequests = new AtomicLong();
    private final AtomicLong totalLatencyMillis = new AtomicLong();
    private final Map<String, AtomicLong> successfulHttpRequests = createHttpCounters();
    private final Map<String, AtomicLong> failedHttpRequests = createHttpCounters();
    private final PrometheusHistogram requestDuration = new PrometheusHistogram();
    private final PrometheusHistogram queueWaitDuration = new PrometheusHistogram();
    private final AtomicLong rateLimitHttpEvents = new AtomicLong();
    private final AtomicLong rateLimitProviderEvents = new AtomicLong();
    private final AtomicLong lastSuccessTimestampSeconds = new AtomicLong();
    private final AtomicLong lastFailureTimestampSeconds = new AtomicLong();
    private final long metricsStartedAtNanos = System.nanoTime();

    private volatile List<String> speakers = Collections.emptyList();
    private volatile List<SuggestionEntry> speakerSuggestionEntries = Collections.emptyList();
    private volatile Map<String, String> speakerSuggestionLabels = Collections.emptyMap();
    private volatile List<String> effects = Collections.emptyList();
    private volatile VoicechatApi voicechatApi;
    private volatile VoicechatServerApi voicechatServerApi;
    private volatile NttsClient nttsClient = new NttsClient("", logger, this::recordHttpRequest);
    private volatile String ttsMode = "local";
    private volatile String voiceMode = "random";
    private volatile String defaultSpeaker = DEFAULT_SPEAKER;
    private volatile String defaultEffect = "";
    private volatile String lastFailure = "none";
    private volatile int maximumTextLength = DEFAULT_MAXIMUM_TEXT_LENGTH;
    private volatile boolean memoryCacheEnabled = true;
    private volatile int memoryCacheMegabytes = DEFAULT_MEMORY_CACHE_MEGABYTES;
    private volatile int cacheTtlMinutes = DEFAULT_CACHE_TTL_MINUTES;
    private volatile boolean diskCacheEnabled;
    private volatile Path diskCacheDirectory = DEFAULT_DISK_CACHE_DIRECTORY;
    private volatile int diskCacheMegabytes = DEFAULT_DISK_CACHE_MEGABYTES;
    private volatile boolean runtimeEnabled;
    private volatile boolean playerEffectsEnabled = true;
    private volatile boolean serverReady;
    private volatile boolean metricsEnabled;
    private volatile String metricsBindAddress = MetricsConfiguration.DEFAULT_BIND_ADDRESS;
    private volatile int metricsPort = MetricsConfiguration.DEFAULT_PORT;
    private final PrometheusMetricsServer metricsServer = new PrometheusMetricsServer(
            logger,
            this::renderPrometheusMetrics
    );

    @Override
    public final String getPluginId() {
        return MOD_ID;
    }

    @Override
    public final void initialize(VoicechatApi api) {
        voicechatApi = api;
        reloadConfiguration();
        registerPlatformEvents();
        logger.info("Initialized /N/TTS voice chat plugin");
    }

    protected abstract void registerPlatformEvents();

    @Override
    public final void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            voicechatServerApi = event.getVoicechat();
            serverReady = true;
            VolumeCategory category = voicechatServerApi.volumeCategoryBuilder()
                    .setId(CATEGORY_ID)
                    .setName("/N/TTS Volume")
                    .setDescription("The volume of all /N/TTS voices")
                    .build();
            voicechatServerApi.registerVolumeCategory(category);
        });
    }

    protected final void handleChat(Object nativePlayer, Collection<?> nativeOnlinePlayers, String message) {
        queueSpeech(nativePlayer, nativeOnlinePlayers, message);
    }

    protected final boolean queueSpeech(
            Object nativePlayer,
            Collection<?> nativeOnlinePlayers,
            String message
    ) {
        VoicechatApi currentApi = voicechatApi;
        NttsClient currentClient = nttsClient;
        VoicechatServerApi currentServerApi = voicechatServerApi;
        if (!runtimeEnabled || !serverReady || currentApi == null || currentServerApi == null) {
            return false;
        }
        NttsClient.ProviderState providerState = currentClient.getState();
        if (providerState != NttsClient.ProviderState.READY) {
            if (providerState == NttsClient.ProviderState.RATE_LIMITED
                    || providerState == NttsClient.ProviderState.QUOTA_EXHAUSTED) {
                droppedRequests.incrementAndGet();
                rateLimitProviderEvents.incrementAndGet();
                recordFailureTimestamp();
            }
            return false;
        }

        int requestCharacterLimit = currentClient.getRequestCharacterLimit(maximumTextLength);
        String normalizedText = NttsClient.normalizeText(message, requestCharacterLimit);
        if (normalizedText.isEmpty()) {
            return false;
        }

        ServerPlayer speakerPlayer = currentApi.fromServerPlayer(nativePlayer);
        if (speakerPlayer == null) {
            return false;
        }

        String capturedMode = ttsMode;
        List<ServerPlayer> globalTargets = Collections.emptyList();
        if ("global".equals(capturedMode) && nativeOnlinePlayers != null) {
            globalTargets = new ArrayList<>();
            for (Object nativeTarget : nativeOnlinePlayers) {
                ServerPlayer target = currentApi.fromServerPlayer(nativeTarget);
                if (target != null && currentServerApi.getConnectionOf(target) != null) {
                    globalTargets.add(target);
                }
            }
        }
        if ("global".equals(capturedMode) ? globalTargets.isEmpty()
                : currentServerApi.getConnectionOf(speakerPlayer) == null) {
            skippedNoListeners.incrementAndGet();
            return false;
        }

        UUID playerId = speakerPlayer.getUuid();
        String speaker = selectSpeaker(playerId);
        String effect = selectEffect(playerId);
        PlaybackRequest request = new PlaybackRequest(speakerPlayer, globalTargets, capturedMode);
        String cacheKey = synthesisKey(normalizedText, speaker, effect);
        return enqueueSynthesis(
                cacheKey,
                currentClient,
                normalizedText,
                speaker,
                effect,
                request,
                System.nanoTime()
        );
    }

    protected final List<String> getSpeakerSuggestions(String query) {
        return fuzzySuggestions(speakerSuggestionEntries, query);
    }

    protected final String getSpeakerSuggestionLabel(String speakerId) {
        return speakerSuggestionLabels.getOrDefault(speakerId, speakerId);
    }

    protected final List<String> getEffectSuggestions(String query) {
        List<SuggestionEntry> availableEffects = new ArrayList<>(effects.size() + 1);
        availableEffects.add(SuggestionEntry.fromValue("none"));
        for (String effect : effects) {
            availableEffects.add(SuggestionEntry.fromValue(effect));
        }
        return fuzzySuggestions(availableEffects, query);
    }

    protected final List<String> getConfigurationKeySuggestions(String query, boolean includeAll) {
        List<SuggestionEntry> entries = new ArrayList<>();
        for (String key : CONFIGURATION_KEYS) {
            entries.add(SuggestionEntry.fromValue(key));
        }
        if (includeAll) {
            entries.add(SuggestionEntry.fromValue("all"));
        }
        return fuzzySuggestions(entries, query);
    }

    protected final List<String> getConfigurationValueSuggestions(String key, String query) {
        List<SuggestionEntry> entries = new ArrayList<>();
        switch (normalizeConfigurationKey(key)) {
            case "enabled":
            case "allow_player_effects":
            case "memory_cache_enabled":
            case "disk_cache_enabled":
            case "metrics_enabled":
                entries.add(SuggestionEntry.fromValue("true"));
                entries.add(SuggestionEntry.fromValue("false"));
                break;
            case "mode":
                entries.add(SuggestionEntry.fromValue("local"));
                entries.add(SuggestionEntry.fromValue("global"));
                break;
            case "voice_mode":
                entries.add(SuggestionEntry.fromValue("random"));
                entries.add(SuggestionEntry.fromValue("static"));
                break;
            case "default_speaker":
                return getSpeakerSuggestions(query);
            case "effect":
                return getEffectSuggestions(query);
            case "max_text_length":
                entries.add(SuggestionEntry.fromValue("100"));
                entries.add(SuggestionEntry.fromValue("300"));
                entries.add(SuggestionEntry.fromValue("500"));
                break;
            case "memory_cache_mb":
                entries.add(SuggestionEntry.fromValue("16"));
                entries.add(SuggestionEntry.fromValue("64"));
                entries.add(SuggestionEntry.fromValue("256"));
                break;
            case "cache_ttl_minutes":
                entries.add(SuggestionEntry.fromValue("60"));
                entries.add(SuggestionEntry.fromValue("1440"));
                break;
            case "disk_cache_mb":
                entries.add(SuggestionEntry.fromValue("256"));
                entries.add(SuggestionEntry.fromValue("512"));
                break;
            case "disk_cache_directory":
                entries.add(SuggestionEntry.fromValue("config/ntts-cache"));
                break;
            case "metrics_bind_address":
                entries.add(SuggestionEntry.fromValue(MetricsConfiguration.DEFAULT_BIND_ADDRESS));
                entries.add(SuggestionEntry.fromValue("0.0.0.0"));
                break;
            case "metrics_port":
                entries.add(SuggestionEntry.fromValue(Integer.toString(MetricsConfiguration.DEFAULT_PORT)));
                entries.add(SuggestionEntry.fromValue("0"));
                break;
            default:
                return Collections.emptyList();
        }
        return fuzzySuggestions(entries, query);
    }

    protected final String getConfiguredValue(String key) {
        String normalizedKey = normalizeConfigurationKey(key);
        if (!CONFIGURATION_KEYS.contains(normalizedKey)) {
            return null;
        }
        try {
            Properties config = readConfig();
            String value = config.getProperty(
                    normalizedKey,
                    defaultConfig().getProperty(normalizedKey, "")
            ).trim();
            return "effect".equals(normalizedKey) && value.isEmpty() ? "none" : value;
        } catch (IOException exception) {
            logger.error("Failed to read /N/TTS configuration", exception);
            return null;
        }
    }

    protected final synchronized boolean setConfiguredValue(String key, String value) {
        String normalizedKey = normalizeConfigurationKey(key);
        if (!CONFIGURATION_KEYS.contains(normalizedKey)) {
            return false;
        }
        try {
            String normalizedValue = validateConfigurationValue(normalizedKey, value);
            Properties config = readConfig();
            Properties backup = copyProperties(config);
            config.setProperty(normalizedKey, normalizedValue);
            return storeAndReloadConfiguration(config, backup);
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn("Rejected /N/TTS configuration update for '{}': {}", normalizedKey, exception.getMessage());
            return false;
        }
    }

    protected final synchronized boolean resetConfiguredValue(String key) {
        String normalizedKey = normalizeConfigurationKey(key);
        boolean resetAll = "all".equals(normalizedKey);
        if (!resetAll && !CONFIGURATION_KEYS.contains(normalizedKey)) {
            return false;
        }
        try {
            Properties config = readConfig();
            Properties backup = copyProperties(config);
            Properties defaults = defaultConfig();
            if (resetAll) {
                for (String configurableKey : CONFIGURATION_KEYS) {
                    config.setProperty(configurableKey, defaults.getProperty(configurableKey));
                }
            } else {
                config.setProperty(normalizedKey, defaults.getProperty(normalizedKey));
            }
            return storeAndReloadConfiguration(config, backup);
        } catch (IOException exception) {
            logger.error("Failed to reset /N/TTS configuration", exception);
            return false;
        }
    }

    protected final synchronized boolean setSpeaker(UUID playerId, String speakerId) {
        if (speakerLocks.contains(playerId)
                || speakerId == null
                || speakerId.isBlank()
                || !speakers.contains(speakerId)) {
            return false;
        }
        if (adminSpeakerData.remove(playerId) != null) {
            savePlayerAdministrationData();
        }
        speakerData.put(playerId, speakerId);
        randomSpeakerData.remove(playerId);
        return true;
    }

    protected final synchronized boolean isSpeakerLocked(UUID playerId) {
        return speakerLocks.contains(playerId);
    }

    protected final synchronized boolean setAdminSpeakers(
            Collection<UUID> playerIds,
            String speakerId
    ) {
        if (speakerId == null || speakerId.isBlank() || !speakers.contains(speakerId)) {
            return false;
        }
        for (UUID playerId : playerIds) {
            adminSpeakerData.put(playerId, speakerId);
            randomSpeakerData.remove(playerId);
        }
        savePlayerAdministrationData();
        return true;
    }

    protected final synchronized void resetAdminSpeakers(Collection<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            adminSpeakerData.remove(playerId);
            speakerData.remove(playerId);
            randomSpeakerData.remove(playerId);
        }
        savePlayerData(SPEAKER_DATA_FILE, speakerData, "speaker selections");
        savePlayerAdministrationData();
    }

    protected final synchronized void setSpeakersLocked(
            Collection<UUID> playerIds,
            boolean locked
    ) {
        if (locked) {
            speakerLocks.addAll(playerIds);
        } else {
            speakerLocks.removeAll(playerIds);
        }
        savePlayerAdministrationData();
    }

    protected final synchronized String getPlayerStateLine(UUID playerId) {
        String personal = speakerData.getOrDefault(playerId, "none");
        String override = adminSpeakerData.getOrDefault(playerId, "none");
        String effect = playerEffectsEnabled
                ? effectData.getOrDefault(playerId, defaultEffect.isEmpty() ? "none" : defaultEffect)
                : defaultEffect.isEmpty() ? "none" : defaultEffect;
        return "voice: " + selectSpeaker(playerId)
                + " | personal: " + personal
                + " | operator override: " + override
                + " | locked: " + yesNo(speakerLocks.contains(playerId))
                + " | effect: " + effect;
    }

    protected final boolean arePlayerEffectsEnabled() {
        return playerEffectsEnabled;
    }

    protected final boolean setEffect(UUID playerId, String effectId) {
        if (!playerEffectsEnabled) {
            return false;
        }
        if (effectId == null || effectId.isBlank() || "none".equalsIgnoreCase(effectId)) {
            effectData.remove(playerId);
            return true;
        }
        if (!effects.contains(effectId)) {
            return false;
        }
        effectData.put(playerId, effectId);
        return true;
    }

    private String selectSpeaker(UUID playerId) {
        String adminSpeaker = adminSpeakerData.get(playerId);
        if (adminSpeaker != null) {
            return adminSpeaker;
        }
        String personalSpeaker = speakerData.get(playerId);
        if (personalSpeaker != null) {
            return personalSpeaker;
        }
        if (!"random".equals(voiceMode)) {
            return defaultSpeaker;
        }
        String assigned = randomSpeakerData.get(playerId);
        if (assigned != null) {
            return assigned;
        }
        List<String> availableSpeakers = speakers;
        if (availableSpeakers.isEmpty()) {
            return defaultSpeaker;
        }
        String selected = availableSpeakers.get(ThreadLocalRandom.current().nextInt(availableSpeakers.size()));
        randomSpeakerData.put(playerId, selected);
        return selected;
    }

    private String selectEffect(UUID playerId) {
        if (!playerEffectsEnabled) {
            return defaultEffect;
        }
        String selected = effectData.get(playerId);
        if (selected == null) {
            return defaultEffect;
        }
        List<String> availableEffects = effects;
        if (!availableEffects.isEmpty() && !availableEffects.contains(selected)) {
            return defaultEffect;
        }
        return selected;
    }

    protected final List<String> getOverviewLines() {
        return List.of(
                "NTTS - " + getServiceDisplayName(),
                "Playback: " + titleCase(ttsMode)
                        + " | Initial voice: " + titleCase(voiceMode)
                        + " | Player effects: " + (playerEffectsEnabled ? "On" : "Off"),
                "Use /ntts help for commands or /ntts status for diagnostics."
        );
    }

    protected final List<String> getHelpLines() {
        return List.of(
                "NTTS operator commands",
                "Service: /ntts status, /ntts enable, /ntts disable, /ntts reload",
                "Configuration:",
                "  /ntts get <key>",
                "  /ntts set <key> <value>",
                "  /ntts reset <key|all>",
                "Speech: /ntts test, /ntts say <message>",
                "Players:",
                "  /ntts player inspect <player>",
                "  /ntts player voice set <players> <voice>",
                "  /ntts player voice reset|lock|unlock <players>",
                "Queue: /ntts queue clear",
                "Player commands: /set_speaker <voice>, /set_effect <effect|none>"
        );
    }

    protected final List<String> getStatusLines() {
        NttsClient.ProviderState providerState = nttsClient.getState();
        int waiting = synthesisExecutor.getQueue().size();
        int active = synthesisExecutor.getActiveCount();
        long timed = timedRequests.get();
        long averageLatency = timed == 0L ? 0L : totalLatencyMillis.get() / timed;
        List<String> lines = new ArrayList<>();
        lines.add("NTTS status");
        lines.add("Service: " + getServiceDisplayName());
        lines.add("Provider: " + providerDisplayName(providerState));
        lines.add("Playback: " + titleCase(ttsMode)
                + " | Initial voice: " + titleCase(voiceMode)
                + " (default: " + defaultSpeaker + ")");
        lines.add("Effects: " + (playerEffectsEnabled ? "Player choice enabled" : "Server default only")
                + " (default: " + (defaultEffect.isEmpty() ? "none" : defaultEffect) + ")");
        lines.add(waiting == 0 && active == 0
                ? "Queue: Idle"
                : "Queue: " + waiting + " waiting, " + active + " active (capacity " + QUEUE_CAPACITY + ")");
        lines.add("Requests: " + completedRequests.get() + " completed, "
                + failedRequests.get() + " failed, "
                + droppedRequests.get() + " dropped, "
                + rejectedRequests.get() + " rejected");
        lines.add("HTTP: " + httpRequests.get() + " requests | In-flight: "
                + inFlightSyntheses.size() + " active, " + deduplicatedRequests.get() + " deduplicated");
        lines.add("Memory cache: " + cacheHits.get() + " hits, " + cacheMisses.get()
                + " misses, " + cacheEvictions.get() + " evictions (" + memoryCacheMegabytes + " MB, TTL "
                + cacheTtlMinutes + " min)");
        lines.add("Disk cache: " + (diskCacheEnabled ? "enabled" : "disabled") + ", "
                + diskCacheHits.get() + " hits, " + diskCacheMisses.get() + " misses, "
                + diskCacheMegabytes + " MB");
        lines.add("Skipped: " + skippedNoListeners.get() + " without voice-chat listeners");
        if (timed > 0L) {
            lines.add("Performance: " + averageLatency + " ms average synthesis latency");
        }
        lines.add("Limits: " + nttsClient.getSafeLimitSummary());
        lines.add("Metrics: " + (metricsEnabled ? metricsServer.getStatus() : "disabled"));
        if (!"none".equals(lastFailure)) {
            lines.add("Last issue: " + titleCase(lastFailure));
        }
        return Collections.unmodifiableList(lines);
    }

    private String getServiceDisplayName() {
        NttsClient.ProviderState providerState = nttsClient.getState();
        if (providerState == NttsClient.ProviderState.UNCONFIGURED) {
            return "Setup required (token not configured)";
        }
        if (providerState == NttsClient.ProviderState.INVALID) {
            return "Unavailable (token invalid or expired)";
        }
        if (!runtimeEnabled) {
            return "Disabled";
        }
        if (!serverReady) {
            return "Starting (waiting for Simple Voice Chat)";
        }
        return providerState == NttsClient.ProviderState.READY
                ? "Ready"
                : providerDisplayName(providerState);
    }

    private static String providerDisplayName(NttsClient.ProviderState state) {
        switch (state) {
            case UNCONFIGURED:
                return "Token not configured";
            case READY:
                return "Connected";
            case INVALID:
                return "Token invalid or expired";
            case RATE_LIMITED:
                return "Rate limited (waiting to retry)";
            case QUOTA_EXHAUSTED:
                return "Quota exhausted";
            case UNAVAILABLE:
                return "Temporarily unavailable";
            default:
                return titleCase(state.displayName());
        }
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String readable = value.trim().replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    protected final boolean setRuntimeEnabled(boolean enabled) {
        if (enabled) {
            NttsClient.ProviderState providerState = nttsClient.getState();
            if (providerState == NttsClient.ProviderState.UNCONFIGURED
                    || providerState == NttsClient.ProviderState.INVALID) {
                return false;
            }
        } else {
            clearPendingRequests();
        }
        runtimeEnabled = enabled;
        return true;
    }

    protected final int clearPendingRequests() {
        int removed = synthesisExecutor.getQueue().size() + inFlightSyntheses.size();
        synthesisExecutor.getQueue().clear();
        for (PendingSynthesis pending : inFlightSyntheses.values()) {
            pending.complete();
        }
        inFlightSyntheses.clear();
        return removed;
    }

    protected final synchronized boolean reloadConfiguration() {
        try {
            Properties config = readConfig();
            String configuredMode = config.getProperty("mode", "local").trim();
            if (!"global".equals(configuredMode) && !"local".equals(configuredMode)) {
                throw new IllegalArgumentException("mode must be 'global' or 'local'");
            }

            String configuredVoiceMode = config.getProperty("voice_mode", "random").trim();
            if (!"static".equals(configuredVoiceMode) && !"random".equals(configuredVoiceMode)) {
                throw new IllegalArgumentException("voice_mode must be 'static' or 'random'");
            }
            String configuredDefaultSpeaker = config.getProperty("default_speaker", DEFAULT_SPEAKER).trim();
            if (configuredDefaultSpeaker.isEmpty()) {
                throw new IllegalArgumentException("default_speaker must not be empty");
            }
            String configuredEffect = config.getProperty("effect", "").trim();
            boolean configuredPlayerEffects = parseBooleanProperty(
                    config,
                    "allow_player_effects",
                    true
            );
            boolean configuredEnabled = parseBooleanProperty(config, "enabled", true);

            int configuredMaximum = Integer.parseInt(
                    config.getProperty("max_text_length", Integer.toString(DEFAULT_MAXIMUM_TEXT_LENGTH)).trim());
            if (configuredMaximum < 1 || configuredMaximum > MAXIMUM_CONFIGURED_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                        "max_text_length must be between 1 and " + MAXIMUM_CONFIGURED_TEXT_LENGTH);
            }
            boolean configuredMemoryCacheEnabled = parseBooleanProperty(config, "memory_cache_enabled", true);
            int configuredMemoryCacheMegabytes = parseBoundedIntegerProperty(
                    config,
                    "memory_cache_mb",
                    DEFAULT_MEMORY_CACHE_MEGABYTES,
                    1,
                    MAXIMUM_CACHE_MEGABYTES
            );
            int configuredCacheTtlMinutes = parseBoundedIntegerProperty(
                    config,
                    "cache_ttl_minutes",
                    DEFAULT_CACHE_TTL_MINUTES,
                    1,
                    MAXIMUM_CACHE_TTL_MINUTES
            );
            boolean configuredDiskCacheEnabled = parseBooleanProperty(config, "disk_cache_enabled", false);
            Path configuredDiskCacheDirectory = parseDiskCacheDirectory(
                    config.getProperty("disk_cache_directory", "config/ntts-cache")
            );
            int configuredDiskCacheMegabytes = parseBoundedIntegerProperty(
                    config,
                    "disk_cache_mb",
                    DEFAULT_DISK_CACHE_MEGABYTES,
                    1,
                    MAXIMUM_CACHE_MEGABYTES
            );
            MetricsConfiguration configuredMetrics = MetricsConfiguration.from(config);

            String environmentToken = System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
            String token = environmentToken == null || environmentToken.isBlank()
                    ? config.getProperty("token", "")
                    : environmentToken;
            NttsClient replacementClient = new NttsClient(token, logger, this::recordHttpRequest);

            ttsMode = configuredMode;
            voiceMode = configuredVoiceMode;
            defaultSpeaker = configuredDefaultSpeaker;
            defaultEffect = configuredEffect;
            playerEffectsEnabled = configuredPlayerEffects;
            randomSpeakerData.clear();
            maximumTextLength = configuredMaximum;
            memoryCacheEnabled = configuredMemoryCacheEnabled;
            memoryCacheMegabytes = configuredMemoryCacheMegabytes;
            cacheTtlMinutes = configuredCacheTtlMinutes;
            diskCacheEnabled = configuredDiskCacheEnabled;
            diskCacheDirectory = configuredDiskCacheDirectory;
            diskCacheMegabytes = configuredDiskCacheMegabytes;
            metricsEnabled = configuredMetrics.isEnabled();
            metricsBindAddress = configuredMetrics.getBindAddress();
            metricsPort = configuredMetrics.getPort();
            audioCache = new AudioCache(
                    configuredMemoryCacheEnabled,
                    megabytesToBytes(configuredMemoryCacheMegabytes),
                    minutesToMillis(configuredCacheTtlMinutes)
            );
            diskAudioCache = new DiskAudioCache(
                    configuredDiskCacheEnabled,
                    configuredDiskCacheDirectory,
                    megabytesToBytes(configuredDiskCacheMegabytes),
                    minutesToMillis(configuredCacheTtlMinutes),
                    logger
            );
            nttsClient = replacementClient;
            metricsServer.apply(configuredMetrics);
            runtimeEnabled = configuredEnabled
                    && replacementClient.getState() != NttsClient.ProviderState.UNCONFIGURED;
            lastFailure = replacementClient.getState() == NttsClient.ProviderState.UNCONFIGURED
                    ? "credential-unconfigured"
                    : "none";
            speakers = Collections.emptyList();
            speakerSuggestionEntries = Collections.emptyList();
            speakerSuggestionLabels = Collections.emptyMap();
            effects = Collections.emptyList();
            if (replacementClient.canRequest()) {
                submitBackground(() -> {
                    replacementClient.refreshTokenInfo();
                    fetchCatalogs(replacementClient);
                });
            }
            logger.info(
                    "Loaded /N/TTS configuration: enabled={}, mode={}, voiceMode={}, playerEffects={}, maxTextLength={}, memoryCache={}MB, diskCache={}, metrics={}, credentialSource={}",
                    runtimeEnabled,
                    ttsMode,
                    voiceMode,
                    playerEffectsEnabled,
                    maximumTextLength,
                    memoryCacheMegabytes,
                    diskCacheEnabled,
                    metricsEnabled ? metricsBindAddress + ":" + metricsPort : "disabled",
                    environmentToken == null || environmentToken.isBlank() ? "config" : "environment"
            );
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            runtimeEnabled = false;
            lastFailure = "configuration-invalid";
            logger.error("Invalid /N/TTS configuration; NTTS has been disabled: {}", exception.getMessage());
            return false;
        }
    }

    protected final void loadSpeakerData() {
        loadPlayerData(SPEAKER_DATA_FILE, speakerData, "speaker selections");
        loadPlayerData(EFFECT_DATA_FILE, effectData, "effect selections");
        loadPlayerAdministrationData();
    }

    protected final void saveSpeakerData() {
        savePlayerData(SPEAKER_DATA_FILE, speakerData, "speaker selections");
        savePlayerData(EFFECT_DATA_FILE, effectData, "effect selections");
        savePlayerAdministrationData();
    }

    private void loadPlayerData(Path file, Map<UUID, String> destination, String description) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<UUID, String>>() { }.getType();
            Map<UUID, String> data = new Gson().fromJson(json, type);
            if (data != null) {
                destination.clear();
                destination.putAll(data);
            }
        } catch (Exception exception) {
            logger.error("Failed to load /N/TTS " + description, exception);
        }
    }

    private void savePlayerData(Path file, Map<UUID, String> data, String description) {
        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            createConfigDirectory();
            Files.writeString(
                    temporaryFile,
                    new Gson().toJson(data),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporaryFile,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException ignored) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.error("Failed to save /N/TTS " + description, exception);
        }
    }

    private synchronized void loadPlayerAdministrationData() {
        if (!Files.isRegularFile(PLAYER_ADMIN_DATA_FILE)) {
            return;
        }
        try {
            PlayerAdministrationData data = new Gson().fromJson(
                    Files.readString(PLAYER_ADMIN_DATA_FILE, StandardCharsets.UTF_8),
                    PlayerAdministrationData.class
            );
            adminSpeakerData.clear();
            speakerLocks.clear();
            if (data != null && data.speakerOverrides != null) {
                adminSpeakerData.putAll(data.speakerOverrides);
            }
            if (data != null && data.speakerLocks != null) {
                speakerLocks.addAll(data.speakerLocks);
            }
        } catch (Exception exception) {
            logger.error("Failed to load /N/TTS player administration data", exception);
        }
    }

    private synchronized void savePlayerAdministrationData() {
        PlayerAdministrationData data = new PlayerAdministrationData(
                new HashMap<>(adminSpeakerData),
                new HashSet<>(speakerLocks)
        );
        Path temporaryFile = PLAYER_ADMIN_DATA_FILE.resolveSibling(
                PLAYER_ADMIN_DATA_FILE.getFileName() + ".tmp"
        );
        try {
            createConfigDirectory();
            Files.writeString(
                    temporaryFile,
                    new Gson().toJson(data),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporaryFile,
                        PLAYER_ADMIN_DATA_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException ignored) {
                Files.move(
                        temporaryFile,
                        PLAYER_ADMIN_DATA_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            logger.error("Failed to save /N/TTS player administration data", exception);
        }
    }

    protected final void stopServer() {
        serverReady = false;
        voicechatServerApi = null;
        metricsServer.close();
        clearPendingRequests();
        saveSpeakerData();
    }

    private boolean enqueueSynthesis(
            String cacheKey,
            NttsClient client,
            String text,
            String speaker,
            String effect,
            PlaybackRequest request,
            long queuedAtNanos
    ) {
        while (true) {
            PendingSynthesis pending = new PendingSynthesis(request);
            PendingSynthesis existing = inFlightSyntheses.putIfAbsent(cacheKey, pending);
            if (existing == null) {
                if (submitSynthesis(() -> synthesizePending(
                        cacheKey,
                        client,
                        text,
                        speaker,
                        effect,
                        pending,
                        queuedAtNanos
                ))) {
                    return true;
                }
                inFlightSyntheses.remove(cacheKey, pending);
                return false;
            }
            if (existing.add(request)) {
                acceptedRequests.incrementAndGet();
                deduplicatedRequests.incrementAndGet();
                return true;
            }
            inFlightSyntheses.remove(cacheKey, existing);
        }
    }

    private void synthesizePending(
            String cacheKey,
            NttsClient client,
            String text,
            String speaker,
            String effect,
            PendingSynthesis pending,
            long queuedAtNanos
    ) {
        long startedAt = System.nanoTime();
        queueWaitDuration.observe(TimeUnit.NANOSECONDS.toMillis(startedAt - queuedAtNanos) / 1_000D);
        short[] samples = null;
        try {
            samples = audioCache.get(cacheKey);
            if (samples != null) {
                cacheHits.incrementAndGet();
            } else {
                cacheMisses.incrementAndGet();
                samples = diskAudioCache.get(cacheKey);
                if (samples != null) {
                    diskCacheHits.incrementAndGet();
                    cacheEvictions.addAndGet(audioCache.put(cacheKey, samples));
                } else {
                    diskCacheMisses.incrementAndGet();
                    httpRequests.incrementAndGet();
                    NttsClient.SynthesisResult result = client.generateTts(text, speaker, effect);
                    if (!result.isSuccess()) {
                        if (result.getState() == NttsClient.ProviderState.RATE_LIMITED
                                || result.getState() == NttsClient.ProviderState.QUOTA_EXHAUSTED) {
                            droppedRequests.incrementAndGet();
                            rateLimitProviderEvents.incrementAndGet();
                            recordFailureTimestamp();
                        } else {
                            failedRequests.incrementAndGet();
                            lastFailure = result.getState().displayName();
                            recordFailureTimestamp();
                        }
                        return;
                    }
                    samples = PcmAudio.fromWav(result.getAudio());
                    if (samples.length == 0) {
                        failedRequests.incrementAndGet();
                        lastFailure = "empty-audio";
                        recordFailureTimestamp();
                        return;
                    }
                    cacheEvictions.addAndGet(audioCache.put(cacheKey, samples));
                    diskAudioCache.put(cacheKey, samples);
                }
            }

            if (!serverReady || voicechatServerApi == null) {
                failedRequests.incrementAndGet();
                lastFailure = "server-unavailable";
                recordFailureTimestamp();
                return;
            }
            List<PlaybackRequest> requests = pending.complete();
            inFlightSyntheses.remove(cacheKey, pending);
            for (PlaybackRequest request : requests) {
                try {
                    if ("global".equals(request.mode)) {
                        playGlobally(request.globalTargets, samples);
                    } else if (voicechatServerApi.getConnectionOf(request.speakerPlayer) != null) {
                        playLocally(request.speakerPlayer, samples);
                    } else {
                        skippedNoListeners.incrementAndGet();
                        continue;
                    }
                    completedRequests.incrementAndGet();
                    lastSuccessTimestampSeconds.set(epochSeconds());
                } catch (RuntimeException exception) {
                    failedRequests.incrementAndGet();
                    lastFailure = "playback-unavailable";
                    recordFailureTimestamp();
                    logger.debug("Skipped unavailable /N/TTS playback target", exception);
                }
            }
            lastFailure = "none";
        } catch (Exception exception) {
            failedRequests.incrementAndGet();
            lastFailure = "audio-processing";
            recordFailureTimestamp();
            logger.error("Failed to process /N/TTS audio", exception);
        } finally {
            inFlightSyntheses.remove(cacheKey, pending);
            pending.complete();
            totalLatencyMillis.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            timedRequests.incrementAndGet();
            requestDuration.observe(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) / 1_000D);
        }
    }

    private void playGlobally(Collection<ServerPlayer> targets, short[] samples) {
        VoicechatServerApi serverApi = voicechatServerApi;
        if (serverApi == null) {
            return;
        }

        for (ServerPlayer target : targets) {
            try {
                VoicechatConnection connection = serverApi.getConnectionOf(target);
                if (connection == null) {
                    continue;
                }
                AudioChannel channel = serverApi.createStaticAudioChannel(
                        UUID.randomUUID(),
                        target.getServerLevel(),
                        connection
                );
                startPlayer(serverApi, channel, samples);
            } catch (RuntimeException exception) {
                logger.debug("Skipped an unavailable /N/TTS listener", exception);
            }
        }
    }

    private void playLocally(ServerPlayer speaker, short[] samples) {
        VoicechatServerApi serverApi = voicechatServerApi;
        if (serverApi == null) {
            return;
        }
        EntityAudioChannel channel = serverApi.createEntityAudioChannel(UUID.randomUUID(), speaker);
        if (channel != null) {
            channel.setDistance(16.0F);
        }
        startPlayer(serverApi, channel, samples);
    }

    private void startPlayer(VoicechatServerApi serverApi, AudioChannel channel, short[] samples) {
        if (channel == null) {
            return;
        }
        channel.setCategory(CATEGORY_ID);
        OpusEncoder encoder = serverApi.createEncoder(OpusEncoderMode.AUDIO);
        AudioPlayer player = serverApi.createAudioPlayer(channel, encoder, samples);
        player.setOnStopped(encoder::close);
        player.startPlaying();
    }

    private boolean submitSynthesis(Runnable task) {
        try {
            synthesisExecutor.execute(task);
            acceptedRequests.incrementAndGet();
            return true;
        } catch (RejectedExecutionException exception) {
            long rejected = rejectedRequests.incrementAndGet();
            lastFailure = "queue-full";
            recordFailureTimestamp();
            if (rejected == 1L || rejected % 100L == 0L) {
                logger.warn("/N/TTS synthesis queue is full; rejected {} request(s)", rejected);
            }
            return false;
        }
    }

    private void submitBackground(Runnable task) {
        try {
            synthesisExecutor.execute(task);
        } catch (RejectedExecutionException exception) {
            logger.warn("Could not schedule /N/TTS background task because the queue is full");
        }
    }

    private void fetchCatalogs(NttsClient client) {
        Map<String, LinkedHashSet<String>> speakerSearchTerms = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> speakerVoiceNames = new LinkedHashMap<>();
        for (NttsClient.Voice voice : client.getVoices()) {
            for (String speaker : voice.getSpeakers()) {
                LinkedHashSet<String> terms = speakerSearchTerms.computeIfAbsent(
                        speaker,
                        ignored -> new LinkedHashSet<>()
                );
                addTermAndParts(terms, voice.getName());
                addTermAndParts(terms, speaker);
                if (voice.getName() != null && !voice.getName().isBlank()) {
                    speakerVoiceNames.computeIfAbsent(speaker, ignored -> new LinkedHashSet<>())
                            .add(voice.getName().trim());
                }
            }
        }
        List<String> loadedSpeakers = new ArrayList<>(speakerSearchTerms.keySet());
        Collections.sort(loadedSpeakers);
        List<SuggestionEntry> loadedSpeakerSuggestions = new ArrayList<>(loadedSpeakers.size());
        Map<String, String> loadedSpeakerLabels = new LinkedHashMap<>();
        for (String speaker : loadedSpeakers) {
            loadedSpeakerSuggestions.add(new SuggestionEntry(
                    speaker,
                    new ArrayList<>(speakerSearchTerms.get(speaker))
            ));
            Set<String> voiceNames = speakerVoiceNames.get(speaker);
            loadedSpeakerLabels.put(
                    speaker,
                    voiceNames == null || voiceNames.isEmpty()
                            ? speaker
                            : String.join(", ", voiceNames)
            );
        }

        List<String> loadedEffects = new ArrayList<>(client.getEffects());
        Collections.sort(loadedEffects);
        if (client != nttsClient) {
            return;
        }
        speakers = Collections.unmodifiableList(loadedSpeakers);
        speakerSuggestionEntries = Collections.unmodifiableList(loadedSpeakerSuggestions);
        speakerSuggestionLabels = Collections.unmodifiableMap(loadedSpeakerLabels);
        effects = Collections.unmodifiableList(loadedEffects);
        if (!defaultEffect.isEmpty() && !loadedEffects.contains(defaultEffect)) {
            logger.warn("Configured /N/TTS effect '{}' is unavailable; using no effect", defaultEffect);
            defaultEffect = "";
        }
        logger.info(
                "Loaded {} /N/TTS speakers and {} effects",
                loadedSpeakers.size(),
                loadedEffects.size()
        );
    }

    private static void addTermAndParts(LinkedHashSet<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        terms.add(normalized);
        for (String part : normalized.split("[\\s_]+")) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
    }

    private static List<String> fuzzySuggestions(List<SuggestionEntry> values, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SuggestionEntry> ranked = new ArrayList<>();
        Map<SuggestionEntry, Integer> scores = new HashMap<>();
        for (SuggestionEntry value : values) {
            int score = needle.isEmpty() ? 0 : value.score(needle);
            if (score != NO_FUZZY_MATCH) {
                ranked.add(value);
                scores.put(value, score);
            }
        }
        ranked.sort((left, right) -> {
            int scoreComparison = Integer.compare(scores.get(left), scores.get(right));
            return scoreComparison != 0
                    ? scoreComparison
                    : left.value.compareToIgnoreCase(right.value);
        });
        int resultSize = Math.min(ranked.size(), 20);
        List<String> results = new ArrayList<>(resultSize);
        for (int index = 0; index < resultSize; index++) {
            results.add(ranked.get(index).value);
        }
        return Collections.unmodifiableList(results);
    }

    private static int fuzzyScore(String candidate, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        String haystack = candidate.trim().toLowerCase(Locale.ROOT);
        if (haystack.equals(needle)) {
            return 0;
        }
        if (haystack.startsWith(needle)) {
            return 10 + Math.min(50, haystack.length() - needle.length());
        }
        int containedAt = haystack.indexOf(needle);
        if (containedAt >= 0) {
            return 100 + containedAt * 2 + Math.min(50, haystack.length() - needle.length());
        }

        int maximumDistance;
        if (needle.length() <= 2) {
            maximumDistance = 0;
        } else if (needle.length() <= 4) {
            maximumDistance = 1;
        } else if (needle.length() <= 7) {
            maximumDistance = 2;
        } else {
            maximumDistance = 3;
        }
        if (maximumDistance == 0 || Math.abs(haystack.length() - needle.length()) > maximumDistance) {
            return NO_FUZZY_MATCH;
        }
        int distance = levenshteinDistance(haystack, needle);
        return distance <= maximumDistance
                ? 200 + distance * 20 + Math.abs(haystack.length() - needle.length())
                : NO_FUZZY_MATCH;
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        substitution
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String normalizeConfigurationKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private String validateConfigurationValue(String key, String value) {
        String normalizedValue = value == null ? "" : value.trim();
        switch (key) {
            case "enabled":
            case "allow_player_effects":
            case "memory_cache_enabled":
            case "disk_cache_enabled":
            case "metrics_enabled":
                if (!"true".equalsIgnoreCase(normalizedValue)
                        && !"false".equalsIgnoreCase(normalizedValue)) {
                    throw new IllegalArgumentException(key + " must be 'true' or 'false'");
                }
                return normalizedValue.toLowerCase(Locale.ROOT);
            case "mode":
                if (!"global".equals(normalizedValue) && !"local".equals(normalizedValue)) {
                    throw new IllegalArgumentException("mode must be 'global' or 'local'");
                }
                return normalizedValue;
            case "voice_mode":
                if (!"static".equals(normalizedValue) && !"random".equals(normalizedValue)) {
                    throw new IllegalArgumentException("voice_mode must be 'static' or 'random'");
                }
                return normalizedValue;
            case "default_speaker":
                if (normalizedValue.isEmpty()
                        || (!speakers.isEmpty() && !speakers.contains(normalizedValue))) {
                    throw new IllegalArgumentException("unknown default speaker");
                }
                return normalizedValue;
            case "effect":
                if ("none".equalsIgnoreCase(normalizedValue)) {
                    return "";
                }
                if (!normalizedValue.isEmpty()
                        && !effects.isEmpty()
                        && !effects.contains(normalizedValue)) {
                    throw new IllegalArgumentException("unknown effect");
                }
                return normalizedValue;
            case "max_text_length":
                int configuredMaximum = Integer.parseInt(normalizedValue);
                if (configuredMaximum < 1 || configuredMaximum > MAXIMUM_CONFIGURED_TEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            "max_text_length must be between 1 and " + MAXIMUM_CONFIGURED_TEXT_LENGTH);
                }
                return Integer.toString(configuredMaximum);
            case "memory_cache_mb":
            case "disk_cache_mb":
                return Integer.toString(parseBoundedInteger(
                        normalizedValue,
                        key,
                        1,
                        MAXIMUM_CACHE_MEGABYTES
                ));
            case "cache_ttl_minutes":
                return Integer.toString(parseBoundedInteger(
                        normalizedValue,
                        key,
                        1,
                        MAXIMUM_CACHE_TTL_MINUTES
                ));
            case "disk_cache_directory":
                return parseDiskCacheDirectory(normalizedValue).toString();
            case "metrics_bind_address":
                Properties metricsProperties = new Properties();
                metricsProperties.setProperty("metrics_bind_address", normalizedValue);
                metricsProperties.setProperty("metrics_port", Integer.toString(metricsPort));
                return MetricsConfiguration.from(metricsProperties).getBindAddress();
            case "metrics_port":
                return Integer.toString(MetricsConfiguration.parsePort(normalizedValue));
            default:
                throw new IllegalArgumentException("unknown configuration key");
        }
    }

    private static boolean parseBooleanProperty(Properties config, String key, boolean defaultValue) {
        String value = config.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be 'true' or 'false'");
        }
        return Boolean.parseBoolean(value);
    }

    private static int parseBoundedIntegerProperty(
            Properties config,
            String key,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        return parseBoundedInteger(
                config.getProperty(key, Integer.toString(defaultValue)).trim(),
                key,
                minimum,
                maximum
        );
    }

    private static int parseBoundedInteger(String value, String key, int minimum, int maximum) {
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static Path parseDiskCacheDirectory(String value) {
        Path configured = Path.of(value == null ? "" : value.trim()).normalize();
        Path configDirectory = Path.of("config");
        if (configured.toString().isEmpty() || configured.isAbsolute() || !configured.startsWith(configDirectory)) {
            throw new IllegalArgumentException("disk_cache_directory must be a relative path under config/");
        }
        return configured;
    }

    private static long megabytesToBytes(int megabytes) {
        return megabytes * 1024L * 1024L;
    }

    private static long minutesToMillis(int minutes) {
        return TimeUnit.MINUTES.toMillis(minutes);
    }

    private static String synthesisKey(String text, String speaker, String effect) {
        String source = speaker + '\u0000' + effect + '\u0000' + "wav" + '\u0000' + "48000" + '\u0000' + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private boolean storeAndReloadConfiguration(Properties updated, Properties backup) throws IOException {
        writeConfig(updated);
        if (reloadConfiguration()) {
            return true;
        }
        logger.warn("Rolling back rejected /N/TTS configuration update");
        writeConfig(backup);
        reloadConfiguration();
        return false;
    }

    private Properties readConfig() throws IOException {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            Properties defaults = defaultConfig();
            saveConfig(defaults);
            return defaults;
        }

        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
            config.load(input);
        }
        return config;
    }

    private Properties defaultConfig() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        properties.setProperty("token", "");
        properties.setProperty("mode", "local");
        properties.setProperty("voice_mode", "random");
        properties.setProperty("default_speaker", DEFAULT_SPEAKER);
        properties.setProperty("effect", "");
        properties.setProperty("allow_player_effects", "true");
        properties.setProperty("max_text_length", Integer.toString(DEFAULT_MAXIMUM_TEXT_LENGTH));
        properties.setProperty("memory_cache_enabled", "true");
        properties.setProperty("memory_cache_mb", Integer.toString(DEFAULT_MEMORY_CACHE_MEGABYTES));
        properties.setProperty("cache_ttl_minutes", Integer.toString(DEFAULT_CACHE_TTL_MINUTES));
        properties.setProperty("disk_cache_enabled", "false");
        properties.setProperty("disk_cache_directory", "config/ntts-cache");
        properties.setProperty("disk_cache_mb", Integer.toString(DEFAULT_DISK_CACHE_MEGABYTES));
        properties.setProperty("metrics_enabled", Boolean.toString(MetricsConfiguration.DEFAULT_ENABLED));
        properties.setProperty("metrics_bind_address", MetricsConfiguration.DEFAULT_BIND_ADDRESS);
        properties.setProperty("metrics_port", Integer.toString(MetricsConfiguration.DEFAULT_PORT));
        return properties;
    }

    private void saveConfig(Properties properties) throws IOException {
        createConfigDirectory();
        try (OutputStream output = Files.newOutputStream(
                CONFIG_FILE,
                StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "/N/TTS configuration; NTTS_TOKEN overrides token");
        }
    }

    private void writeConfig(Properties properties) throws IOException {
        createConfigDirectory();
        Path temporaryFile = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "/N/TTS configuration; NTTS_TOKEN overrides token");
        }
        try {
            Files.move(
                    temporaryFile,
                    CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException ignored) {
            Files.move(temporaryFile, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void createConfigDirectory() throws IOException {
        Files.createDirectories(CONFIG_FILE.getParent());
    }

    private String renderPrometheusMetrics() {
        StringBuilder output = new StringBuilder(4_096);
        NttsClient currentClient = nttsClient;
        NttsClient.ProviderState providerState = currentClient.getState();

        appendGauge(output, "ntts_up", metricsServer.isRunning() ? 1L : 0L);
        appendGauge(output, "ntts_tts_available", providerState == NttsClient.ProviderState.READY ? 1L : 0L);
        appendGauge(output, "ntts_queue_waiting", synthesisExecutor.getQueue().size());
        appendGauge(output, "ntts_queue_active", synthesisExecutor.getActiveCount());
        appendGauge(output, "ntts_queue_capacity", QUEUE_CAPACITY);

        appendMetricType(output, "ntts_requests_total", "counter");
        appendLabeledSample(output, "ntts_requests_total", "result", "accepted", acceptedRequests.get());
        appendLabeledSample(output, "ntts_requests_total", "result", "completed", completedRequests.get());
        appendLabeledSample(output, "ntts_requests_total", "result", "failed", failedRequests.get());
        appendLabeledSample(output, "ntts_requests_total", "result", "dropped", droppedRequests.get());
        appendLabeledSample(output, "ntts_requests_total", "result", "rejected", rejectedRequests.get());

        appendMetricType(output, "ntts_http_requests_total", "counter");
        for (String statusClass : HTTP_STATUS_CLASSES) {
            appendHttpCounter(output, "success", statusClass, successfulHttpRequests.get(statusClass).get());
            appendHttpCounter(output, "failure", statusClass, failedHttpRequests.get(statusClass).get());
        }

        appendMetricType(output, "ntts_request_duration_seconds", "histogram");
        requestDuration.append(output, "ntts_request_duration_seconds");
        appendMetricType(output, "ntts_queue_wait_duration_seconds", "histogram");
        queueWaitDuration.append(output, "ntts_queue_wait_duration_seconds");

        appendMetricType(output, "ntts_cache_requests_total", "counter");
        appendCacheCounter(output, "memory", "hit", cacheHits.get());
        appendCacheCounter(output, "memory", "miss", cacheMisses.get());
        appendCacheCounter(output, "disk", "hit", diskCacheHits.get());
        appendCacheCounter(output, "disk", "miss", diskCacheMisses.get());
        appendMetricType(output, "ntts_cache_evictions_total", "counter");
        appendLabeledSample(output, "ntts_cache_evictions_total", "cache", "memory", cacheEvictions.get());
        appendMetricType(output, "ntts_inflight_deduplicated_total", "counter");
        appendCounterValue(output, "ntts_inflight_deduplicated_total", deduplicatedRequests.get());
        appendMetricType(output, "ntts_skipped_no_listeners_total", "counter");
        appendCounterValue(output, "ntts_skipped_no_listeners_total", skippedNoListeners.get());
        appendMetricType(output, "ntts_rate_limit_events_total", "counter");
        appendLabeledSample(output, "ntts_rate_limit_events_total", "source", "http", rateLimitHttpEvents.get());
        appendLabeledSample(output, "ntts_rate_limit_events_total", "source", "provider", rateLimitProviderEvents.get());

        appendGauge(output, "ntts_quota_exhausted", providerState == NttsClient.ProviderState.QUOTA_EXHAUSTED
                || currentClient.isQuotaExhausted() ? 1L : 0L);
        appendGauge(output, "ntts_quota_characters_used", currentClient.getQuotaCharactersUsed());
        appendGauge(output, "ntts_quota_characters_limit", currentClient.getQuotaCharactersLimit());
        appendGauge(output, "ntts_last_success_timestamp_seconds", lastSuccessTimestampSeconds.get());
        appendGauge(output, "ntts_last_failure_timestamp_seconds", lastFailureTimestampSeconds.get());
        appendGauge(output, "ntts_uptime_seconds", TimeUnit.NANOSECONDS.toSeconds(
                System.nanoTime() - metricsStartedAtNanos
        ));
        return output.toString();
    }

    private void recordHttpRequest(String statusClass, boolean success, boolean rateLimited) {
        String boundedStatusClass = HTTP_STATUS_CLASSES.contains(statusClass) ? statusClass : "network";
        Map<String, AtomicLong> counters = success ? successfulHttpRequests : failedHttpRequests;
        counters.get(boundedStatusClass).incrementAndGet();
        if (rateLimited) {
            rateLimitHttpEvents.incrementAndGet();
            recordFailureTimestamp();
        }
    }

    private static Map<String, AtomicLong> createHttpCounters() {
        Map<String, AtomicLong> counters = new LinkedHashMap<>();
        for (String statusClass : HTTP_STATUS_CLASSES) {
            counters.put(statusClass, new AtomicLong());
        }
        return Collections.unmodifiableMap(counters);
    }

    private static void appendGauge(StringBuilder output, String name, long value) {
        output.append("# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(value).append('\n');
    }

    private static void appendMetricType(StringBuilder output, String name, String type) {
        output.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void appendLabeledSample(
            StringBuilder output,
            String name,
            String labelName,
            String labelValue,
            long value
    ) {
        output.append(name).append('{').append(labelName).append("=\"")
                .append(labelValue).append("\"} ").append(value).append('\n');
    }

    private static void appendCounterValue(StringBuilder output, String name, long value) {
        output.append(name).append(' ').append(value).append('\n');
    }

    private static void appendHttpCounter(StringBuilder output, String result, String statusClass, long value) {
        output.append("ntts_http_requests_total{result=\"").append(result)
                .append("\",status_class=\"").append(statusClass).append("\"} ")
                .append(value).append('\n');
    }

    private static void appendCacheCounter(StringBuilder output, String cache, String result, long value) {
        output.append("ntts_cache_requests_total{cache=\"").append(cache)
                .append("\",result=\"").append(result).append("\"} ")
                .append(value).append('\n');
    }

    private static long epochSeconds() {
        return System.currentTimeMillis() / 1_000L;
    }

    private void recordFailureTimestamp() {
        lastFailureTimestampSeconds.set(epochSeconds());
    }

    private static final class PlayerAdministrationData {
        private Map<UUID, String> speakerOverrides;
        private Set<UUID> speakerLocks;

        private PlayerAdministrationData() {
            speakerOverrides = new HashMap<>();
            speakerLocks = new HashSet<>();
        }

        private PlayerAdministrationData(
                Map<UUID, String> speakerOverrides,
                Set<UUID> speakerLocks
        ) {
            this.speakerOverrides = speakerOverrides;
            this.speakerLocks = speakerLocks;
        }
    }

    private static final class SuggestionEntry {
        private final String value;
        private final List<String> searchTerms;

        private SuggestionEntry(String value, List<String> searchTerms) {
            this.value = value;
            this.searchTerms = searchTerms;
        }

        private static SuggestionEntry fromValue(String value) {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            addTermAndParts(terms, value);
            return new SuggestionEntry(value, new ArrayList<>(terms));
        }

        private int score(String needle) {
            int best = fuzzyScore(value, needle);
            for (String term : searchTerms) {
                int termScore = fuzzyScore(term, needle);
                if (termScore != NO_FUZZY_MATCH) {
                    best = Math.min(best, termScore + SEARCH_TERM_PENALTY);
                }
            }
            return best;
        }
    }

    private static final class PlaybackRequest {
        private final ServerPlayer speakerPlayer;
        private final List<ServerPlayer> globalTargets;
        private final String mode;

        private PlaybackRequest(ServerPlayer speakerPlayer, List<ServerPlayer> globalTargets, String mode) {
            this.speakerPlayer = speakerPlayer;
            this.globalTargets = List.copyOf(globalTargets);
            this.mode = mode;
        }
    }

    private static final class PendingSynthesis {
        private final List<PlaybackRequest> requests = new ArrayList<>();
        private boolean completed;

        private PendingSynthesis(PlaybackRequest initialRequest) {
            requests.add(initialRequest);
        }

        private synchronized boolean add(PlaybackRequest request) {
            if (completed) {
                return false;
            }
            requests.add(request);
            return true;
        }

        private synchronized List<PlaybackRequest> complete() {
            if (completed) {
                return Collections.emptyList();
            }
            completed = true;
            List<PlaybackRequest> completedRequests = new ArrayList<>(requests);
            requests.clear();
            return completedRequests;
        }
    }
}
