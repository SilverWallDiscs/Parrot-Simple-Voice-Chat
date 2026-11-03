package dev.silverwall.parrotsvc.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receives server-side microphone packet events from Simple Voice Chat. */
public final class SmartParrotVoicePlugin implements VoicechatPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("parrot_svc/voice");
    private static final AtomicLong RECEIVED_PACKETS = new AtomicLong();

    @Override
    public String getPluginId() {
        return "parrot_svc";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            byte[] opusFrame = event.getPacket().getOpusEncodedData();
            if (opusFrame != null && opusFrame.length > 0) {
                RECEIVED_PACKETS.incrementAndGet();
            }
        });
        LOGGER.info("Simple Voice Chat API connected; microphone packet listener registered");
    }

    public static long getReceivedPacketCount() {
        return RECEIVED_PACKETS.get();
    }
}
