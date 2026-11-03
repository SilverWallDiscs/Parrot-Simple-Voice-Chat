package dev.silverwall.parrotsvc.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receives, decodes and temporarily learns opted-in microphone audio. */
public final class SmartParrotVoicePlugin implements VoicechatPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("parrot_svc/voice");
    private static final AtomicLong RECEIVED_PACKETS = new AtomicLong();
    private static volatile VoicechatServerApi voicechatApi;

    @Override
    public String getPluginId() {
        return "parrot_svc";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            byte[] opusFrame = event.getPacket().getOpusEncodedData();
            if (opusFrame == null || opusFrame.length == 0) {
                return;
            }

            RECEIVED_PACKETS.incrementAndGet();
            voicechatApi = event.getVoicechat();

            if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
                return;
            }
            VoiceMemory.acceptPacket(
                    event.getSenderConnection().getPlayer().getUuid(),
                    voicechatApi,
                    opusFrame);
        });
        LOGGER.info("Simple Voice Chat API connected; consent-based Opus decoding registered");
    }

    public static void tick(MinecraftServer server) {
        VoiceMemory.tick(server, voicechatApi);
    }

    public static VoicechatServerApi getVoicechatApi() {
        return voicechatApi;
    }

    public static long getReceivedPacketCount() {
        return RECEIVED_PACKETS.get();
    }

    public static void shutdown() {
        VoiceMemory.clear();
        voicechatApi = null;
    }
}
