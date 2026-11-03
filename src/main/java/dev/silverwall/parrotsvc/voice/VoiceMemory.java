package dev.silverwall.parrotsvc.voice;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import dev.silverwall.parrotsvc.ParrotSvcMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

/** Captures only players who have explicitly accepted the persistent voice-memory prompt. */
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
    private static final long END_OF_PHRASE_NANOS = 600_000_000L;
    private static final double LEARN_RADIUS = 12.0;
    private static final float PLAYBACK_DISTANCE = 20.0F;
    private static final float PITCH_RATIO = 1.28F;

    private static final Map<UUID, CaptureSession> SESSIONS = new ConcurrentHashMap<>();
    private static volatile int maxWordsPerParrot = 150;

    private VoiceMemory() {
    }

    public static void initialize(Path configDir) {
        Path directory = configDir.resolve("parrot-svc");
        Path configFile = directory.resolve("voice-memory.properties");
        Properties properties = new Properties();
        try {
            Files.createDirectories(directory);
            if (Files.isRegularFile(configFile)) {
                try (InputStream input = Files.newInputStream(configFile)) {
                    properties.load(input);
                }
            }
            String configured = properties.getProperty("max-words-per-parrot", "150");
            int parsed;
            try {
                parsed = Integer.parseInt(configured.trim());
            } catch (NumberFormatException exception) {
                parsed = 150;
            }
            maxWordsPerParrot = Math.max(1, Math.min(5000, parsed));
            if (!Integer.toString(maxWordsPerParrot).equals(configured.trim())) {
                properties.setProperty("max-words-per-parrot", Integer.toString(maxWordsPerParrot));
                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output, "Parrot + Simple Voice Chat settings");
                }
            } else if (!Files.exists(configFile)) {
                properties.setProperty("max-words-per-parrot", Integer.toString(maxWordsPerParrot));
                try (OutputStream output = Files.newOutputStream(configFile)) {
                    properties.store(output, "Parrot + Simple Voice Chat settings");
                }
            }
        } catch (IOException exception) {
            maxWordsPerParrot = 150;
            LOGGER.warn("Could not load {}; using default memory settings", configFile, exception);
        }
        ParrotMemoryStore.initialize(configDir, maxWordsPerParrot);
    }

    public static int getMaxWordsPerParrot() {
        return maxWordsPerParrot;
    }

    public static ParrotMemoryStore.ConsentState getConsentState(UUID playerId) {
        return ParrotMemoryStore.getConsentState(playerId);
    }

    public static boolean isConsenting(UUID playerId) {
        return ParrotMemoryStore.isConsenting(playerId);
    }

    public static void setConsent(UUID playerId, boolean accepted) {
        ParrotMemoryStore.setConsent(playerId, accepted
                ? ParrotMemoryStore.ConsentState.ACCEPTED
                : ParrotMemoryStore.ConsentState.DECLINED);
        if (!accepted) {
            closeSession(playerId);
        }
    }

    /** Existing command remains a convenient toggle; /parrotvoice revoke is explicit. */
    public static boolean toggleConsent(UUID playerId) {
        boolean accepted = !isConsenting(playerId);
        setConsent(playerId, accepted);
        return accepted;
    }

    public static int getActiveSessionCount() {
        return SESSIONS.size();
    }

    public static int getConsentingPlayerCount() {
        return ParrotMemoryStore.getConsentingPlayerCount();
    }

    public static int getLearnedWordCountNearest(ServerPlayerEntity player) {
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        return parrot == null ? 0 : ParrotMemoryStore.getWordCount(parrot.getUuid());
    }

    public static int getLearnedPhraseCountNearest(ServerPlayerEntity player) {
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        return parrot == null ? 0 : ParrotMemoryStore.getPhraseCount(parrot.getUuid());
    }

    public static void acceptPacket(UUID speakerId, VoicechatServerApi api, byte[] opusFrame) {
        if (!isConsenting(speakerId) || opusFrame == null || opusFrame.length == 0 || api == null) {
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
            if (!isConsenting(speakerId)) {
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
                    if (elapsedNanos > 30_000_000L) {
                        int missingSamples = (int) Math.max(0L, elapsedSamples - decoded.length);
                        session.appendSilence(Math.min(missingSamples, SAMPLE_RATE / 3));
                    }
                    session.append(decoded);
                    session.lastPacketNanos = packetNow;
                    Arrays.fill(decoded, (short) 0);
                }
            } catch (Throwable exception) {
                SESSIONS.remove(speakerId, session);
                session.close();
                LOGGER.warn("Could not decode a microphone packet from {}", speakerId, exception);
            }
        }
    }

    /** Called on the server thread. Consent and parrot memory survive player disconnects. */
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
                if (samples.length >= MIN_PHRASE_SAMPLES && isConsenting(speakerId)) {
                    finished.add(new FinishedPhrase(speakerId, samples));
                } else {
                    Arrays.fill(samples, (short) 0);
                }
            }
        }

        for (FinishedPhrase phrase : finished) {
            learnAndRepeat(server, api, phrase.speakerId(), phrase.samples());
        }
    }

    private static void learnAndRepeat(
            MinecraftServer server, VoicechatServerApi api, UUID speakerId, short[] samples
    ) {
        if (api == null || !isConsenting(speakerId)) {
            Arrays.fill(samples, (short) 0);
            return;
        }
        ServerPlayerEntity speaker = server.getPlayerManager().getPlayer(speakerId);
        if (speaker == null) {
            Arrays.fill(samples, (short) 0);
            return;
        }

        ParrotEntity parrot = findNearestSmartParrot(speaker, LEARN_RADIUS);
        if (parrot == null) {
            Arrays.fill(samples, (short) 0);
            return;
        }

        UUID parrotId = parrot.getUuid();
        UUID entityOwnerId = parrot.getOwnerUuid();

        String clientLanguage = speaker.getClientOptions().language();
        if (SpeechRecognitionService.supportsLanguage(clientLanguage)) {
            short[] phraseForSegmentation = samples.clone();
            boolean queued = SpeechRecognitionService.recognizeSpanishAsync(clientLanguage, samples, recognized ->
                    server.execute(() -> {
                        List<short[]> wordAudio = new ArrayList<>();
                        try {
                            if (!isConsenting(speakerId) || recognized.isEmpty()) {
                                return;
                            }
                            List<String> words = new ArrayList<>();
                            for (SpeechRecognitionService.WordSegment segment : recognized) {
                                short[] extracted = extractWord(phraseForSegmentation, segment);
                                if (extracted == null) {
                                    continue;
                                }
                                words.add(segment.word());
                                wordAudio.add(extracted);
                            }
                            if (!words.isEmpty()) {
                                ParrotMemoryStore.learnUtterance(
                                        parrotId, speakerId, entityOwnerId, words, wordAudio, phraseForSegmentation);
                                LOGGER.info("Smart Parrot {} learned {} word(s) from {}",
                                        parrotId, words.size(), speakerId);

                                // Prefer a clean saved phrase. If the current speaker is a visitor,
                                // fall back to per-word playback so owner recordings win each word.
                                short[] parrotReply = null;
                                ParrotMemoryStore.PhrasePlayback savedPhrase =
                                        ParrotMemoryStore.getPhrase(parrotId, String.join(" ", words));
                                if (savedPhrase != null && !savedPhrase.foreign()
                                        && savedPhrase.samples() != null) {
                                    parrotReply = savedPhrase.samples();
                                } else {
                                    if (savedPhrase != null && savedPhrase.samples() != null) {
                                        Arrays.fill(savedPhrase.samples(), (short) 0);
                                    }
                                    parrotReply = ParrotMemoryStore.composeWords(parrotId, words);
                                }
                                if (parrotReply != null) {
                                    try {
                                        if (api != null && parrot.isAlive()) {
                                            play(parrot, parrotReply, api);
                                        }
                                    } finally {
                                        Arrays.fill(parrotReply, (short) 0);
                                    }
                                }
                            }
                        } finally {
                            wordAudio.forEach(clip -> Arrays.fill(clip, (short) 0));
                            Arrays.fill(phraseForSegmentation, (short) 0);
                        }
                    }));
            if (!queued) {
                Arrays.fill(phraseForSegmentation, (short) 0);
            }
        }

        LOGGER.debug("Smart parrot {} heard a {} ms voice phrase from {}",
                parrotId, Math.round(samples.length * 1000.0 / SAMPLE_RATE), speakerId);
        Arrays.fill(samples, (short) 0);
    }

    public static boolean repeatNearest(ServerPlayerEntity player, VoicechatServerApi api) {
        if (api == null) return false;
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) return false;
        ParrotMemoryStore.PhrasePlayback phrase = ParrotMemoryStore.getLatestPhrase(parrot.getUuid());
        if (phrase == null || phrase.samples() == null) return false;
        play(parrot, phrase.samples(), api);
        Arrays.fill(phrase.samples(), (short) 0);
        return true;
    }

    public static boolean repeatWordNearest(ServerPlayerEntity player, VoicechatServerApi api, String requestedWord) {
        if (api == null) return false;
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) return false;
        short[] samples = ParrotMemoryStore.getWordAudio(parrot.getUuid(), requestedWord);
        if (samples == null) return false;
        play(parrot, samples, api);
        Arrays.fill(samples, (short) 0);
        return true;
    }

    public static List<String> getLearnedWordsNearest(ServerPlayerEntity player) {
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        return parrot == null ? List.of() : ParrotMemoryStore.getWords(parrot.getUuid());
    }

    public static List<String> getLearnedPhrasesNearest(ServerPlayerEntity player) {
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        return parrot == null ? List.of() : ParrotMemoryStore.getPhrases(parrot.getUuid());
    }

    /** Plays an exact saved phrase first; otherwise assembles it from this parrot's word recordings. */
    public static PhraseTryResult tryPhraseNearest(
            ServerPlayerEntity player, VoicechatServerApi api, String requestedPhrase
    ) {
        if (api == null) return new PhraseTryResult(false, "Simple Voice Chat is unavailable.");
        ParrotEntity parrot = findNearestSmartParrot(player, 24.0);
        if (parrot == null) return new PhraseTryResult(false, "No Smart Parrot is close enough (24 blocks).");
        if (requestedPhrase == null || requestedPhrase.isBlank())
            return new PhraseTryResult(false, "Enter a phrase to compose.");

        String[] rawWords = requestedPhrase.trim().split("\\s+");
        if (rawWords.length > 16)
            return new PhraseTryResult(false, "A test phrase can contain at most 16 words.");
        List<String> words = Arrays.stream(rawWords).map(VoiceMemory::normalizeWord)
                .filter(word -> !word.isBlank()).toList();
        if (words.isEmpty()) return new PhraseTryResult(false, "No valid words were found in that phrase.");

        ParrotMemoryStore.PhrasePlayback savedPhrase =
                ParrotMemoryStore.getPhrase(parrot.getUuid(), String.join(" ", words));
        if (savedPhrase != null && savedPhrase.samples() != null) {
            play(parrot, savedPhrase.samples(), api);
            Arrays.fill(savedPhrase.samples(), (short) 0);
            String origin = savedPhrase.fluent() ? "natural one-piece recording"
                    : "phrase assembled from " + savedPhrase.fragments() + " fragments";
            String extra = savedPhrase.foreign() ? " (E: another player contributed)" : "";
            return new PhraseTryResult(true, "Played saved " + origin + extra + ": " + String.join(" ", words));
        }

        List<String> missing = new ArrayList<>();
        for (String word : words) {
            short[] sample = ParrotMemoryStore.getWordAudio(parrot.getUuid(), word);
            if (sample == null) {
                missing.add(word);
            } else {
                Arrays.fill(sample, (short) 0);
            }
        }
        if (!missing.isEmpty()) {
            return new PhraseTryResult(false, "This parrot has not learned: "
                    + String.join(", ", new LinkedHashSet<>(missing)));
        }

        short[] composed = ParrotMemoryStore.composeWords(parrot.getUuid(), words);
        if (composed == null) {
            return new PhraseTryResult(false, "Could not assemble those recordings into an 8-second phrase.");
        }
        play(parrot, composed, api);
        Arrays.fill(composed, (short) 0);
        return new PhraseTryResult(true, "Composed from the parrot's saved word recordings: "
                + String.join(" ", words));
    }

    public record PhraseTryResult(boolean played, String message) {}

    private static short[] extractWord(short[] phrase, SpeechRecognitionService.WordSegment segment) {
        double startSeconds = Math.max(0.0, segment.startSeconds());
        double endSeconds = Math.max(startSeconds, segment.endSeconds());
        int start = Math.max(0, (int) Math.floor(startSeconds * SAMPLE_RATE) - WORD_START_PADDING);
        int end = Math.min(phrase.length, (int) Math.ceil(endSeconds * SAMPLE_RATE) + WORD_END_PADDING);
        int length = end - start;
        if (length < MIN_WORD_SAMPLES || length > MAX_WORD_SAMPLES) return null;
        return Arrays.copyOfRange(phrase, start, end);
    }

    private static ParrotEntity findNearestSmartParrot(ServerPlayerEntity player, double radius) {
        Box searchBox = player.getBoundingBox().expand(radius);
        return player.getWorld().getEntitiesByClass(
                        ParrotEntity.class, searchBox,
                        entity -> entity.isAlive() && entity.getCommandTags().contains(ParrotSvcMod.SMART_TAG))
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(player)))
                .orElse(null);
    }

    private static String normalizeWord(String word) {
        if (word == null) return "";
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
            if (channel == null) return;
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
            if (encoder != null && !encoder.isClosed()) encoder.close();
            if (channel != null && !channel.isClosed()) channel.flush();
            LOGGER.warn("Could not play a learned phrase from smart parrot {}", parrot.getUuid(), exception);
        }
    }

    /** Disconnect only closes live microphone capture. The parrot keeps its persisted clips. */
    public static void onPlayerDisconnect(UUID playerId) {
        closeSession(playerId);
    }

    private static void closeSession(UUID playerId) {
        CaptureSession session = SESSIONS.remove(playerId);
        if (session != null) {
            synchronized (session) {
                session.close();
            }
        }
    }

    /** Server shutdown closes active capture buffers but deliberately preserves parrot memory on disk. */
    public static void clear() {
        for (Map.Entry<UUID, CaptureSession> entry : SESSIONS.entrySet()) {
            closeSession(entry.getKey());
        }
        ParrotMemoryStore.clearCache();
    }

    private record FinishedPhrase(UUID speakerId, short[] samples) {}

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
            if (amount <= 0) return;
            ensureCapacity(size + amount);
            System.arraycopy(decoded, 0, samples, size, amount);
            size += amount;
        }

        private void appendSilence(int count) {
            int amount = Math.min(count, MAX_PHRASE_SAMPLES - size);
            if (amount <= 0) return;
            ensureCapacity(size + amount);
            Arrays.fill(samples, size, size + amount, (short) 0);
            size += amount;
        }

        private void ensureCapacity(int required) {
            if (required <= samples.length) return;
            int capacity = Math.min(MAX_PHRASE_SAMPLES, Math.max(required, samples.length * 2));
            samples = Arrays.copyOf(samples, capacity);
        }

        private short[] copySamples() {
            return Arrays.copyOf(samples, size);
        }

        private void close() {
            if (!decoder.isClosed()) decoder.close();
            Arrays.fill(samples, (short) 0);
            samples = new short[0];
            size = 0;
        }
    }
}
