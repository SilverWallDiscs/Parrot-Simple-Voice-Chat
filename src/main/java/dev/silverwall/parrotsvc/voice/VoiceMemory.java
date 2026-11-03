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
import java.util.List;
import java.util.Map;
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
    private static final int MAX_ACTIVE_SESSIONS = 32;
    private static final int MAX_STORED_CLIPS = 64;
    private static final long MAX_STORED_SAMPLES = 2_000_000L;
    private static final long END_OF_PHRASE_NANOS = 600_000_000L;
    private static final double LEARN_RADIUS = 12.0;
    private static final float PLAYBACK_DISTANCE = 20.0F;
    private static final float PITCH_RATIO = 1.28F;

    private static final Map<UUID, CaptureSession> SESSIONS = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> CONSENTING = ConcurrentHashMap.newKeySet();
    private static final List<LearnedClip> LEARNED = new ArrayList<>();
    private static long storedSamples;

    private VoiceMemory() {
    }

    public static boolean toggleConsent(UUID playerId) {
        if (CONSENTING.remove(playerId)) {
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
            try {
                short[] decoded = session.decoder.decode(opusFrame);
                if (decoded != null && decoded.length > 0) {
                    session.append(decoded);
                    session.lastPacketNanos = System.nanoTime();
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

        remember(parrot.getUuid(), speakerId, samples);
        play(parrot, samples, api);
        LOGGER.debug("Smart parrot {} learned a {} ms voice phrase from {}",
                parrot.getUuid(), Math.round(samples.length * 1000.0 / SAMPLE_RATE), speakerId);
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
        return true;
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

            short[] parrotVoice = raisePitch(originalSamples);
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

    /**
     * Simple resampling raises the voice by about four semitones. It intentionally
     * gives the familiar faster, higher parrot sound without storing another file.
     */
    private static short[] raisePitch(short[] input) {
        int outputLength = Math.max(1, (int) (input.length / PITCH_RATIO));
        short[] output = new short[outputLength];
        for (int i = 0; i < outputLength; i++) {
            float sourcePosition = i * PITCH_RATIO;
            int left = Math.min((int) sourcePosition, input.length - 1);
            int right = Math.min(left + 1, input.length - 1);
            float fraction = sourcePosition - left;
            float sample = input[left] + (input[right] - input[left]) * fraction;
            float vibrato = 1.0F + (float) Math.sin(i * (2.0 * Math.PI * 6.5 / SAMPLE_RATE)) * 0.025F;
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * vibrato));
        }
        return output;
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
            LEARNED.clear();
            storedSamples = 0;
        }
    }

    private record FinishedPhrase(UUID speakerId, short[] samples) {
    }

    private record LearnedClip(UUID parrotId, UUID speakerId, short[] samples) {
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
            int required = size + amount;
            if (required > samples.length) {
                int capacity = Math.min(MAX_PHRASE_SAMPLES, Math.max(required, samples.length * 2));
                samples = Arrays.copyOf(samples, capacity);
            }
            System.arraycopy(decoded, 0, samples, size, amount);
            size += amount;
        }

        private short[] copySamples() {
            return Arrays.copyOf(samples, size);
        }

        private void close() {
            if (!decoder.isClosed()) {
                decoder.close();
            }
            samples = new short[0];
            size = 0;
        }
    }
}
