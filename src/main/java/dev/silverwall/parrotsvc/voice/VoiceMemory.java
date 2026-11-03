package dev.silverwall.parrotsvc.voice;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import dev.silverwall.parrotsvc.ParrotSvcMod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short-lived, consent-based voice memory. Audio never leaves RAM and is cleared
 * when its speaker disconnects or the server stops.
 */
public final class VoiceMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger("parrot_svc/memory");
    private static final int SAMPLE_RATE = 48_000;
    private static final int MAX_PHRASE_SAMPLES = SAMPLE_RATE * 6;
    private static final int MIN_PHRASE_SAMPLES = SAMPLE_RATE / 5;
    private static final int MIN_WORD_SAMPLES = SAMPLE_RATE / 12;
    private static final int MAX_WORD_SAMPLES = (int) (SAMPLE_RATE * 1.5);
    private static final int WORD_START_PADDING = SAMPLE_RATE / 30;
    private static final int WORD_END_PADDING = SAMPLE_RATE / 20;
    private static final int MAX_ACTIVE_SESSIONS = 32;
    private static final int MAX_STORED_CLIPS = 64;
    private static final long MAX_STORED_SAMPLES = 2_000_000L;
    // 150 distinct words per parrot by default; the global audio budget is a
    // final safety ceiling across all parrots (240 seconds of 48 kHz mono PCM).
    private static final int DEFAULT_MAX_WORDS_PER_PARROT = 150;
    private static final long MAX_STORED_WORD_SAMPLES = SAMPLE_RATE * 240L;
    private static volatile int maxWordsPerParrot = DEFAULT_MAX_WORDS_PER_PARROT;
    private static final long END_OF_PHRASE_NANOS = 600_000_000L;
    private static final double LEARN_RADIUS = 12.0;
    private static final float PLAYBACK_DISTANCE = 20.0F;
    private static final float PITCH_RATIO = 1.28F;

    private static final Map<UUID, CaptureSession> SESSIONS = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> CONSENTING = ConcurrentHashMap.newKeySet();
    private static final List<LearnedClip> LEARNED = new ArrayList<>();
    private static final List<LearnedWord> LEARNED_WORDS = new ArrayList<>();
    private static long storedSamples;
    private static long storedWordSamples;

    private VoiceMemory() {
    }

    /** Loads the server-side word-memory limit, creating its config on first start. */
    public static void initialize(Path configDir) {
        Path directory = configDir.resolve("parrot-svc");
        Path configFile = directory.resolve("voice-memory.properties");
        Properties properties = new Properties();
        boolean writeConfig = false;

        try {
            Files.createDirectories(directory);
            if (Files.exists(configFile)) {
                try (InputStream input = Files.newInputStream(configFile)) {
                    properties.load(input);
                }
            } else {
                writeConfig = true;
            }

            String key = "max-words-per-parrot";
            String configured = properties.getProperty(key);
            int parsed = DEFAULT_MAX_WORDS_PER_PARROT;
            if (configured != null) {
                try {
                    parsed = Integer.parseInt(configured.trim());
                } catch (NumberFormatException exception) {
                    LOGGER.warn("Invalid {} value '{}' in {}; using {}", key, configured, configFile,
                            DEFAULT_MAX_WORDS_PER_PARROT);
                    writeConfig = true;
                }
            } else {
                writeConfig = true;
            }

            int bounded = Math.max(1, Math.min(5000, parsed));
            if (configured == null || !configured.trim().equals(Integer.toString(bounded))) {
                properties.setProperty(key, Integer.toString(bounded));
                writeConfig = true;
            }
            maxWordsPerParrot = bounded;

            if (writeConfig) {
                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output, "Parrot + Simple Voice Chat voice memory settings");
                }
            }
            LOGGER.info("Smart Parrot word memory limit: {} distinct words per parrot", maxWordsPerParrot);
        } catch (IOException exception) {
            maxWordsPerParrot = DEFAULT_MAX_WORDS_PER_PARROT;
            LOGGER.warn("Could not load {}; using the default limit of {} words per parrot",
                    configFile, DEFAULT_MAX_WORDS_PER_PARROT, exception);
        }
    }

    public static int getMaxWordsPerParrot() {
        return maxWordsPerParrot;
    }

    public static boolean toggleConsent(UUID playerId) {
        if (CONSENTING.remove(playerId)) {
            CaptureSession session = SESSIONS.remove(playerId);
            if (session != null) {
                synchronized (session) {
                    session.close();
                }
            }
            discardPlayerAudio(playerId);
            return false;
        }
        CONSENTING.add(playerId);
        return true;
    }

    public static boolean isConsenting(UUID playerId) {
        return CONSENTING.contains(playerId);
    }

    public static int getLearnedClipCount() {
        synchronized (LEARNED) {
            return LEARNED.size();
        }
    }

    public static int getLearnedWordCount() {
        synchronized (LEARNED_WORDS) {
            return LEARNED_WORDS.size();
        }
    }

    public static int getActiveSessionCount() {
        return SESSIONS.size();
    }

    public static int getConsentingPlayerCount() {
        return CONSENTING.size();
    }

    public static void acceptPacket(UUID speakerId, VoicechatServerApi api, byte[] opusFrame) {
        if (!CONSENTING.contains(speakerId) || opusFrame == null || opusFrame.length == 0) {
            return;
        }

        CaptureSession session = SESSIONS.computeIfAbsent(speakerId, ignored -> {
            if (SESSIONS.size() >= MAX_ACTIVE_SESSIONS) {
                return null;
            }
            OpusDecoder decoder = api.createDecoder();
            return decoder == null ? null : new CaptureSession(decoder);
        });
        if (session == null) {
            return;
        }

        synchronized (session) {
            if (!CONSENTING.contains(speakerId)) {
                SESSIONS.remove(speakerId, session);
                session.close();
                return;
            }
            try {
                short[] decoded = session.decoder.decode(opusFrame);
                if (decoded != null && decoded.length > 0) {
                    long packetNow = System.nanoTime();
                    long elapsedNanos = packetNow - session.lastPacketNanos;
                    long elapsedSamples = elapsedNanos * SAMPLE_RATE / 1_000_000_000L;

                    // Voice activation can omit silent packets. Reinsert a bounded
                    // approximation of those gaps for word-level alignment.
                    if (elapsedNanos > 30_000_000L) {
                        int missingSamples = (int) Math.max(0L, elapsedSamples - decoded.length);
                        session.appendSilence(Math.min(missingSamples, SAMPLE_RATE / 3));
                    }

                    session.append(decoded);
                    session.lastPacketNanos = packetNow;
                }
            } catch (Throwable exception) {
                SESSIONS.remove(speakerId, session);
                session.close();
                LOGGER.warn("Could not decode a microphone packet from {}", speakerId, exception);
            }
        }
    }

    /** Called from Fabric's server tick, never from the voice network thread. */
    public static void tick(MinecraftServer server, VoicechatServerApi api) {
        long now = System.nanoTime();
        List<FinishedPhrase> finished = new ArrayList<>();

        for (Map.Entry<UUID, CaptureSession> entry : SESSIONS.entrySet()) {
            UUID speakerId = entry.getKey();
            CaptureSession session = entry.getValue();
            synchronized (session) {
                if (now - session.lastPacketNanos < END_OF_PHRASE_NANOS) {
                    continue;
                }
                if (!SESSIONS.remove(speakerId, session)) {
                    continue;
                }
                short[] samples = session.copySamples();
                session.close();
                if (samples.length >= MIN_PHRASE_SAMPLES && CONSENTING.contains(speakerId)) {
                    finished.add(new FinishedPhrase(speakerId, samples));
                } else {
                    Arrays.fill(samples, (short) 0);
                }
            }
        }

        for (FinishedPhrase phrase : finished) {
            learnAndRepeat(server, api, phrase.speakerId(), phrase.samples());
        }

        // Close orphaned sessions if their player has left or consent was revoked.
        for (Map.Entry<UUID, CaptureSession> entry : SESSIONS.entrySet()) {
            if (server.getPlayerManager().getPlayer(entry.getKey()) == null || !CONSENTING.contains(entry.getKey())) {
                closeSession(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void learnAndRepeat(MinecraftServer server, VoicechatServerApi api, UUID speakerId, short[] samples) {
        if (api == null) {
            return;
        }
        ServerPlayerEntity speaker = server.getPlayerManager().getPlayer(speakerId);
        if (speaker == null) {
            return;
        }

        ParrotEntity parrot = findNearestSmartParrot(speaker, LEARN_RADIUS);
        if (parrot == null) {
            return;
        }

        UUID parrotId = parrot.getUuid();
        remember(parrotId, speakerId, samples);
        play(parrot, samples, api);

        // Minecraft's normal client-options packet already contains each player's
        // selected language. No client-side copy of this mod is required.
        String clientLanguage = speaker.getClientOptions().language();
        if (SpeechRecognitionService.supportsLanguage(clientLanguage)) {
            short[] phraseForSegmentation = samples.clone();
            boolean queued = SpeechRecognitionService.recognizeSpanishAsync(clientLanguage, samples, recognized ->
                    server.execute(() -> {
                        try {
                            if (!CONSENTING.contains(speakerId)) {
                                return;
                            }
                            ServerPlayerEntity currentSpeaker = server.getPlayerManager().getPlayer(speakerId);
                            if (currentSpeaker == null) {
                                return;
                            }
                            ParrotEntity currentParrot = findNearestSmartParrot(currentSpeaker, LEARN_RADIUS);
                            if (currentParrot == null || !currentParrot.getUuid().equals(parrotId)) {
                                return;
                            }

                            int learned = 0;
                            for (SpeechRecognitionService.WordSegment segment : recognized) {
                                short[] wordAudio = extractWord(phraseForSegmentation, segment);
                                if (wordAudio == null) {
                                    continue;
                                }
                                rememberWord(parrotId, speakerId, segment.word(), wordAudio);
                                Arrays.fill(wordAudio, (short) 0);
                                learned++;
                            }
                            if (learned > 0) {
                                LOGGER.info("Smart Parrot {} learned {} Spanish word clip(s) from {}",
                                        parrotId, learned, speakerId);
                            }
                        } finally {
                            Arrays.fill(phraseForSegmentation, (short) 0);
                        }
                    }));
            if (!queued) {
                Arrays.fill(phraseForSegmentation, (short) 0);
            }
        }

        LOGGER.debug("Smart parrot {} learned a {} ms voice phrase from {}",
                parrotId, Math.round(samples.length * 1000.0 / SAMPLE_RATE), speakerId);
        Arrays.fill(samples, (short) 0);
    }

    public static boolean repeatNearest(ServerPlayerEntity player, VoicechatServerApi api) {
        if (api == null) {
            return false;
        }
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) {
            return false;
        }
        short[] samples = getLatestClip(parrot.getUuid());
        if (samples == null) {
            return false;
        }
        play(parrot, samples, api);
        Arrays.fill(samples, (short) 0);
        return true;
    }

    public static boolean repeatWordNearest(ServerPlayerEntity player, VoicechatServerApi api, String requestedWord) {
        if (api == null) {
            return false;
        }
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) {
            return false;
        }
        short[] samples = getLatestWordClip(parrot.getUuid(), normalizeWord(requestedWord));
        if (samples == null) {
            return false;
        }
        play(parrot, samples, api);
        Arrays.fill(samples, (short) 0);
        return true;
    }

    public static List<String> getLearnedWordsNearest(ServerPlayerEntity player) {
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) {
            return List.of();
        }
        synchronized (LEARNED_WORDS) {
            LinkedHashSet<String> words = new LinkedHashSet<>();
            for (LearnedWord word : LEARNED_WORDS) {
                if (word.parrotId().equals(parrot.getUuid())) {
                    words.add(word.word());
                }
            }
            return List.copyOf(words);
        }
    }

    /**
     * Builds a test phrase by concatenating the most recently learned recording
     * for each requested word. This is a test composer, not a text-to-speech system.
     */
    public static PhraseTryResult tryPhraseNearest(
            ServerPlayerEntity player,
            VoicechatServerApi api,
            String requestedPhrase
    ) {
        if (api == null) {
            return new PhraseTryResult(false, "Simple Voice Chat is unavailable.");
        }

        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) {
            return new PhraseTryResult(false, "No Smart Parrot is close enough (24 blocks).");
        }

        if (requestedPhrase == null || requestedPhrase.isBlank()) {
            return new PhraseTryResult(false, "Enter a phrase to compose.");
        }

        String[] rawWords = requestedPhrase.trim().split("\\s+");
        if (rawWords.length > 16) {
            return new PhraseTryResult(false, "A test phrase can contain at most 16 words.");
        }

        List<String> words = new ArrayList<>(rawWords.length);
        for (String rawWord : rawWords) {
            String normalized = normalizeWord(rawWord);
            if (!normalized.isBlank()) {
                words.add(normalized);
            }
        }
        if (words.isEmpty()) {
            return new PhraseTryResult(false, "No valid words were found in that phrase.");
        }

        List<short[]> clips = new ArrayList<>(words.size());
        List<String> missing = new ArrayList<>();
        synchronized (LEARNED_WORDS) {
            for (String word : words) {
                short[] latest = null;
                for (int i = LEARNED_WORDS.size() - 1; i >= 0; i--) {
                    LearnedWord learned = LEARNED_WORDS.get(i);
                    if (learned.parrotId().equals(parrot.getUuid()) && learned.word().equals(word)) {
                        latest = Arrays.copyOf(learned.samples(), learned.samples().length);
                        break;
                    }
                }
                if (latest == null) {
                    missing.add(word);
                } else {
                    clips.add(latest);
                }
            }
        }

        if (!missing.isEmpty()) {
            clips.forEach(clip -> Arrays.fill(clip, (short) 0));
            return new PhraseTryResult(false, "This parrot has not learned: " + String.join(", ", new LinkedHashSet<>(missing)));
        }

        final int separatorSamples = SAMPLE_RATE / 20; // 50 ms between words
        long totalSamples = (long) separatorSamples * Math.max(0, clips.size() - 1);
        for (short[] clip : clips) {
            totalSamples += clip.length;
        }
        if (totalSamples > SAMPLE_RATE * 8L) {
            clips.forEach(clip -> Arrays.fill(clip, (short) 0));
            return new PhraseTryResult(false, "That composed phrase would be longer than 8 seconds.");
        }

        short[] composed = new short[(int) totalSamples];
        int offset = 0;
        for (int i = 0; i < clips.size(); i++) {
            short[] clip = clips.get(i);
            System.arraycopy(clip, 0, composed, offset, clip.length);
            offset += clip.length;
            Arrays.fill(clip, (short) 0);
            if (i < clips.size() - 1) {
                offset += separatorSamples; // the new array is already silent here
            }
        }

        play(parrot, composed, api);
        Arrays.fill(composed, (short) 0);
        return new PhraseTryResult(true, "Composed " + words.size() + " learned word clip(s): " + String.join(" ", words));
    }

    public record PhraseTryResult(boolean played, String message) {
    }

    private static short[] extractWord(short[] phrase, SpeechRecognitionService.WordSegment segment) {
        double startSeconds = Math.max(0.0, segment.startSeconds());
        double endSeconds = Math.max(startSeconds, segment.endSeconds());
        int start = Math.max(0, (int) Math.floor(startSeconds * SAMPLE_RATE) - WORD_START_PADDING);
        int end = Math.min(phrase.length, (int) Math.ceil(endSeconds * SAMPLE_RATE) + WORD_END_PADDING);
        int length = end - start;
        if (length < MIN_WORD_SAMPLES || length > MAX_WORD_SAMPLES) {
            return null;
        }
        return Arrays.copyOfRange(phrase, start, end);
    }

    private static ParrotEntity findNearestSmartParrot(ServerPlayerEntity player, double radius) {
        Box searchBox = player.getBoundingBox().expand(radius);
        return player.getWorld().getEntitiesByClass(
                        ParrotEntity.class,
                        searchBox,
                        entity -> entity.isAlive() && entity.getCommandTags().contains(ParrotSvcMod.SMART_TAG))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player)))
                .orElse(null);
    }

    private static void remember(UUID parrotId, UUID speakerId, short[] samples) {
        short[] copy = Arrays.copyOf(samples, samples.length);
        synchronized (LEARNED) {
            while (!LEARNED.isEmpty()
                    && (LEARNED.size() >= MAX_STORED_CLIPS || storedSamples + copy.length > MAX_STORED_SAMPLES)) {
                LearnedClip removed = LEARNED.remove(0);
                storedSamples -= removed.samples().length;
            }
            if (copy.length <= MAX_STORED_SAMPLES) {
                LEARNED.add(new LearnedClip(parrotId, speakerId, copy));
                storedSamples += copy.length;
            }
        }
    }

    private static short[] getLatestClip(UUID parrotId) {
        synchronized (LEARNED) {
            for (int i = LEARNED.size() - 1; i >= 0; i--) {
                LearnedClip clip = LEARNED.get(i);
                if (clip.parrotId().equals(parrotId)) {
                    return Arrays.copyOf(clip.samples(), clip.samples().length);
                }
            }
        }
        return null;
    }

    private static short[] getLatestWordClip(UUID parrotId, String word) {
        synchronized (LEARNED_WORDS) {
            for (int i = LEARNED_WORDS.size() - 1; i >= 0; i--) {
                LearnedWord clip = LEARNED_WORDS.get(i);
                if (clip.parrotId().equals(parrotId) && clip.word().equals(word)) {
                    return Arrays.copyOf(clip.samples(), clip.samples().length);
                }
            }
        }
        return null;
    }

    private static void rememberWord(UUID parrotId, UUID speakerId, String rawWord, short[] samples) {
        String word = normalizeWord(rawWord);
        if (word.isBlank() || samples.length < MIN_WORD_SAMPLES || samples.length > MAX_WORD_SAMPLES) {
            return;
        }

        short[] copy = Arrays.copyOf(samples, samples.length);
        synchronized (LEARNED_WORDS) {
            // Keep only the newest recording for a given normalized word and parrot.
            for (int i = LEARNED_WORDS.size() - 1; i >= 0; i--) {
                LearnedWord existing = LEARNED_WORDS.get(i);
                if (existing.parrotId().equals(parrotId) && existing.word().equals(word)) {
                    removeLearnedWordAt(i);
                }
            }

            int wordsForParrot = 0;
            for (LearnedWord learned : LEARNED_WORDS) {
                if (learned.parrotId().equals(parrotId)) {
                    wordsForParrot++;
                }
            }
            while (wordsForParrot >= maxWordsPerParrot) {
                int oldestForParrot = -1;
                for (int i = 0; i < LEARNED_WORDS.size(); i++) {
                    if (LEARNED_WORDS.get(i).parrotId().equals(parrotId)) {
                        oldestForParrot = i;
                        break;
                    }
                }
                if (oldestForParrot < 0) {
                    break;
                }
                removeLearnedWordAt(oldestForParrot);
                wordsForParrot--;
            }

            // Keep memory bounded across the whole server, even with many parrots.
            while (!LEARNED_WORDS.isEmpty() && storedWordSamples + copy.length > MAX_STORED_WORD_SAMPLES) {
                removeLearnedWordAt(0);
            }

            if (copy.length <= MAX_STORED_WORD_SAMPLES) {
                LEARNED_WORDS.add(new LearnedWord(parrotId, speakerId, word, copy));
                storedWordSamples += copy.length;
            } else {
                Arrays.fill(copy, (short) 0);
            }
        }
    }

    /** Must be called while holding LEARNED_WORDS. */
    private static void removeLearnedWordAt(int index) {
        LearnedWord removed = LEARNED_WORDS.remove(index);
        storedWordSamples -= removed.samples().length;
        Arrays.fill(removed.samples(), (short) 0);
    }

    private static String normalizeWord(String word) {
        if (word == null) {
            return "";
        }
        // Ignore Spanish accent marks for storage and matching, but preserve ñ as a letter.
        String lower = word.toLowerCase(Locale.ROOT).trim().replace("ñ", "\uE000");
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace("\uE000", "ñ")
                .replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private static void play(ParrotEntity parrot, short[] originalSamples, VoicechatServerApi api) {
        EntityAudioChannel channel = null;
        OpusEncoder encoder = null;
        try {
            channel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromEntity(parrot));
            if (channel == null) {
                return;
            }
            channel.setDistance(PLAYBACK_DISTANCE);
            encoder = api.createEncoder();
            if (encoder == null) {
                channel.flush();
                return;
            }

            short[] parrotVoice = ParrotVoiceProcessor.process(originalSamples);
            AudioPlayer audioPlayer = api.createAudioPlayer(channel, encoder, parrotVoice);
            OpusEncoder finalEncoder = encoder;
            EntityAudioChannel finalChannel = channel;
            audioPlayer.setOnStopped(() -> {
                try {
                    finalEncoder.close();
                } finally {
                    finalChannel.flush();
                }
            });
            audioPlayer.startPlaying();
        } catch (Throwable exception) {
            if (encoder != null && !encoder.isClosed()) {
                encoder.close();
            }
            if (channel != null && !channel.isClosed()) {
                channel.flush();
            }
            LOGGER.warn("Could not play a learned phrase from smart parrot {}", parrot.getUuid(), exception);
        }
    }


    public static void onPlayerDisconnect(UUID playerId) {
        CONSENTING.remove(playerId);
        discardPlayerAudio(playerId);
        CaptureSession session = SESSIONS.remove(playerId);
        if (session != null) {
            synchronized (session) {
                session.close();
            }
        }
    }

    private static void discardPlayerAudio(UUID playerId) {
        synchronized (LEARNED) {
            LEARNED.removeIf(clip -> {
                if (clip.speakerId().equals(playerId)) {
                    storedSamples -= clip.samples().length;
                    Arrays.fill(clip.samples(), (short) 0);
                    return true;
                }
                return false;
            });
        }
        synchronized (LEARNED_WORDS) {
            LEARNED_WORDS.removeIf(clip -> {
                if (clip.speakerId().equals(playerId)) {
                    storedWordSamples -= clip.samples().length;
                    Arrays.fill(clip.samples(), (short) 0);
                    return true;
                }
                return false;
            });
        }
    }

    private static void closeSession(UUID playerId, CaptureSession session) {
        if (SESSIONS.remove(playerId, session)) {
            synchronized (session) {
                session.close();
            }
        }
    }

    public static void clear() {
        for (Map.Entry<UUID, CaptureSession> entry : SESSIONS.entrySet()) {
            closeSession(entry.getKey(), entry.getValue());
        }
        CONSENTING.clear();
        synchronized (LEARNED) {
            LEARNED.forEach(clip -> Arrays.fill(clip.samples(), (short) 0));
            LEARNED.clear();
            storedSamples = 0;
        }
        synchronized (LEARNED_WORDS) {
            LEARNED_WORDS.forEach(clip -> Arrays.fill(clip.samples(), (short) 0));
            LEARNED_WORDS.clear();
            storedWordSamples = 0;
        }
    }

    private record FinishedPhrase(UUID speakerId, short[] samples) {
    }

    private record LearnedClip(UUID parrotId, UUID speakerId, short[] samples) {
    }

    private record LearnedWord(UUID parrotId, UUID speakerId, String word, short[] samples) {
    }

    private static final class CaptureSession {
        private final OpusDecoder decoder;
        private short[] samples = new short[9_600];
        private int size;
        private long lastPacketNanos = System.nanoTime();

        private CaptureSession(OpusDecoder decoder) {
            this.decoder = decoder;
        }

        private void append(short[] decoded) {
            int amount = Math.min(decoded.length, MAX_PHRASE_SAMPLES - size);
            if (amount <= 0) {
                return;
            }
            ensureCapacity(size + amount);
            System.arraycopy(decoded, 0, samples, size, amount);
            size += amount;
        }

        private void appendSilence(int count) {
            int amount = Math.min(count, MAX_PHRASE_SAMPLES - size);
            if (amount <= 0) {
                return;
            }
            ensureCapacity(size + amount);
            Arrays.fill(samples, size, size + amount, (short) 0);
            size += amount;
        }

        private void ensureCapacity(int required) {
            if (required > samples.length) {
                int capacity = Math.min(MAX_PHRASE_SAMPLES, Math.max(required, samples.length * 2));
                samples = Arrays.copyOf(samples, capacity);
            }
        }

        private short[] copySamples() {
            return Arrays.copyOf(samples, size);
        }

        private void close() {
            if (!decoder.isClosed()) {
                decoder.close();
            }
            Arrays.fill(samples, (short) 0);
            samples = new short[0];
            size = 0;
        }
    }
}
