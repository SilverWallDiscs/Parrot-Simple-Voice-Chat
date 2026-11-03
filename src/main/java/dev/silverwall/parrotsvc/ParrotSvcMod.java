package dev.silverwall.parrotsvc;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParrotSvcMod implements ModInitializer {
    public static final String MOD_ID = "parrot_svc";
    public static final String SMART_TAG = "parrot_svc_smart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("parrotdebug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("summon").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    Parrot parrot = EntityType.PARROT.create(player.serverLevel());
                    if (parrot == null) {
                        context.getSource().sendFailure(Component.literal("Could not create parrot."));
                        return 0;
                    }
                    parrot.moveTo(player.getX() + 1.0, player.getY(), player.getZ(), player.getYRot(), 0.0F);
                    parrot.addTag(SMART_TAG);
                    parrot.setCustomName(Component.literal("§bSmart Parrot"));
                    parrot.setCustomNameVisible(true);
                    player.serverLevel().addFreshEntity(parrot);
                    context.getSource().sendSuccess(() -> Component.literal("§aSmart Parrot summoned. UUID: " + parrot.getUUID()), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("info").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§bParrot + Simple Voice Chat §7| §fVoice packets received: §e" + dev.silverwall.parrotsvc.voice.SmartParrotVoicePlugin.getReceivedPacketCount()), false);
                    return Command.SINGLE_SUCCESS;
                }))
            )
        );
        LOGGER.info("Parrot + Simple Voice Chat initialized");
    }
}
