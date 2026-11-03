package dev.silverwall.parrotsvc;

import com.mojang.brigadier.Command;
import dev.silverwall.parrotsvc.voice.SmartParrotVoicePlugin;
import dev.silverwall.parrotsvc.voice.VoiceMemory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParrotSvcMod implements ModInitializer {
    public static final String MOD_ID = "parrot_svc";
    public static final String SMART_TAG = "parrot_svc_smart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(SmartParrotVoicePlugin::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SmartParrotVoicePlugin.shutdown());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                VoiceMemory.onPlayerDisconnect(handler.player.getUuid()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("parrotdebug")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("summon").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    ParrotEntity parrot = EntityType.PARROT.create(player.getWorld());
                    if (parrot == null) {
                        context.getSource().sendError(Text.literal("Could not create parrot."));
                        return 0;
                    }
                    parrot.refreshPositionAndAngles(player.getX() + 1.0, player.getY(), player.getZ(), player.getYaw(), 0.0F);
                    parrot.addCommandTag(SMART_TAG);
                    parrot.setCustomName(Text.literal("§bSmart Parrot"));
                    parrot.setCustomNameVisible(true);
                    player.getServerWorld().spawnEntity(parrot);
                    context.getSource().sendFeedback(() -> Text.literal("§aSmart Parrot summoned. UUID: " + parrot.getUuid()), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("repeat").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    boolean played = VoiceMemory.repeatNearest(player, SmartParrotVoicePlugin.getVoicechatApi());
                    if (played) {
                        context.getSource().sendFeedback(() -> Text.literal("§aThe nearest Smart Parrot repeated its latest learned phrase."), false);
                        return Command.SINGLE_SUCCESS;
                    }
                    context.getSource().sendError(Text.literal("No Smart Parrot with a learned phrase is close enough, or voice chat is unavailable."));
                    return 0;
                }))
                .then(CommandManager.literal("info").executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§bParrot + Simple Voice Chat §7| §fPackets: §e"
                                    + SmartParrotVoicePlugin.getReceivedPacketCount()
                                    + " §7| §fActive captures: §e" + VoiceMemory.getActiveSessionCount()
                                    + " §7| §fRAM phrases: §e" + VoiceMemory.getLearnedClipCount()
                                    + " §7| §fConsenting players: §e" + VoiceMemory.getConsentingPlayerCount()), false);
                    return Command.SINGLE_SUCCESS;
                }))
            );

            dispatcher.register(CommandManager.literal("parrotvoice")
                .then(CommandManager.literal("consent").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    boolean enabled = VoiceMemory.toggleConsent(player.getUuid());
                    if (enabled) {
                        context.getSource().sendFeedback(() -> Text.literal(
                                "§aVoice learning enabled. Nearby Smart Parrots may temporarily decode and repeat your voice. Audio stays in RAM and is cleared when you disconnect or the server stops."), false);
                    } else {
                        context.getSource().sendFeedback(() -> Text.literal(
                                "§eVoice learning disabled. Your active capture and phrases learned from you were erased."), false);
                    }
                    return Command.SINGLE_SUCCESS;
                }))
            );
        });
        LOGGER.info("Parrot + Simple Voice Chat initialized");
    }
}
