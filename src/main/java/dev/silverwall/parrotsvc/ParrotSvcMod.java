package dev.silverwall.parrotsvc;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.server.command.CommandManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParrotSvcMod implements ModInitializer {
    public static final String MOD_ID = "parrot_svc";
    public static final String SMART_TAG = "parrot_svc_smart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
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
                .then(CommandManager.literal("info").executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal("§bParrot + Simple Voice Chat §7| §fVoice packets received: §e" + dev.silverwall.parrotsvc.voice.SmartParrotVoicePlugin.getReceivedPacketCount()), false);
                    return Command.SINGLE_SUCCESS;
                }))
            )
        );
        LOGGER.info("Parrot + Simple Voice Chat initialized");
    }
}
