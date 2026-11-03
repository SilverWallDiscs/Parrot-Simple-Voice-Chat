package dev.silverwall.parrotsvc;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.silverwall.parrotsvc.voice.SmartParrotVoicePlugin;
import dev.silverwall.parrotsvc.voice.SpeechRecognitionService;
import dev.silverwall.parrotsvc.voice.VoiceMemory;
import dev.silverwall.parrotsvc.voice.ParrotMemoryStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParrotSvcMod implements ModInitializer {
    public static final String MOD_ID = "parrot_svc";
    public static final String SMART_TAG = "parrot_svc_smart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final java.util.List<String> PHRASE_SUGGESTIONS = java.util.List.of(
            "tengo hambre", "tengo mucha hambre", "quiero comer", "quiero comida", "dame comida",
            "tengo sed", "quiero agua", "dame agua", "quiero una fruta", "esto está rico", "quiero más",
            "hola", "hola amigo", "buenos días", "buenas noches", "cómo estás", "estoy bien",
            "qué haces", "dónde estás", "ven aquí", "mírame", "escúchame", "vamos juntos",
            "qué bonito", "te quiero", "estoy feliz", "estoy triste", "tengo miedo", "estoy aburrido",
            "estoy cansado", "quiero dormir", "tengo sueño", "estoy enojado", "no me gusta",
            "eso me gusta", "qué divertido", "soy inteligente", "soy un loro inteligente",
            "qué es eso", "qué estás haciendo", "qué pasó", "quién eres", "quiero investigar",
            "vamos a mirar", "eso es interesante", "he encontrado algo", "mira lo que encontré",
            "no entiendo", "ya entendí", "déjame pensar", "quiero volar", "vamos a volar",
            "quiero jugar", "juega conmigo", "quiero compañía", "no te vayas", "espera por mí",
            "sígueme", "vamos a casa", "quiero descansar", "déjame tranquilo", "no me molestes",
            "no fui yo", "yo no hice nada", "fue culpa tuya", "tengo un secreto",
            "no se lo digas a nadie", "soy inocente", "qué vergüenza", "otra vez tú",
            "soy el jefe", "hola mi dueño", "te estaba esperando", "te extrañé", "volviste",
            "quédate conmigo", "quiero estar contigo", "vamos a pasear", "gracias amigo",
            "eres mi mejor amigo", "me gusta estar contigo", "hasta mañana", "cuídate mucho"
    );
    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(SmartParrotVoicePlugin::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            VoiceMemory.initialize(FabricLoader.getInstance().getConfigDir());
            SpeechRecognitionService.initialize(FabricLoader.getInstance().getConfigDir());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SmartParrotVoicePlugin.shutdown());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (VoiceMemory.getConsentState(handler.player.getUuid())
                    == ParrotMemoryStore.ConsentState.UNKNOWN) {
                sendConsentPrompt(handler.player);
            }
        });
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
                        context.getSource().sendFeedback(() -> Text.literal("§aThe nearest Smart Parrot repeated its latest saved phrase."), false);
                        return Command.SINGLE_SUCCESS;
                    }
                    context.getSource().sendError(Text.literal("No nearby Smart Parrot has a saved phrase, or voice chat is unavailable."));
                    return 0;
                }))
                .then(CommandManager.literal("words").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    var words = VoiceMemory.getLearnedWordsNearest(player);
                    String message = words.isEmpty() ? "No words learned by a nearby Smart Parrot yet."
                            : "Learned words (" + words.size() + "): " + String.join(", ", words);
                    context.getSource().sendFeedback(() -> Text.literal("§b" + message), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("phrases").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    var phrases = VoiceMemory.getLearnedPhrasesNearest(player);
                    String message = phrases.isEmpty() ? "No saved phrases for a nearby Smart Parrot yet."
                            : "Saved phrases: " + String.join(" | ", phrases);
                    context.getSource().sendFeedback(() -> Text.literal("§b" + message), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("suggestions").executes(context -> {
                    for (int start = 0; start < PHRASE_SUGGESTIONS.size(); start += 15) {
                        int end = Math.min(PHRASE_SUGGESTIONS.size(), start + 15);
                        String chunk = String.join(", ", PHRASE_SUGGESTIONS.subList(start, end));
                        context.getSource().sendFeedback(() -> Text.literal("§dPhrase ideas: §f" + chunk), false);
                    }
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§7These are teaching prompts, not built-in voice lines. The parrot learns their audio from consenting speakers."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("tryphrase")
                    .then(CommandManager.argument("phrase", StringArgumentType.greedyString()).executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        String phrase = StringArgumentType.getString(context, "phrase");
                        VoiceMemory.PhraseTryResult result = VoiceMemory.tryPhraseNearest(
                                player, SmartParrotVoicePlugin.getVoicechatApi(), phrase);
                        if (result.played()) {
                            context.getSource().sendFeedback(() -> Text.literal("§a" + result.message()), false);
                            return Command.SINGLE_SUCCESS;
                        }
                        context.getSource().sendError(Text.literal("§c" + result.message()));
                        return 0;
                    })))
                .then(CommandManager.literal("repeatword")
                    .then(CommandManager.argument("word", StringArgumentType.word()).executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        String word = StringArgumentType.getString(context, "word");
                        boolean played = VoiceMemory.repeatWordNearest(
                                player, SmartParrotVoicePlugin.getVoicechatApi(), word);
                        if (played) {
                            context.getSource().sendFeedback(() -> Text.literal("§aSmart Parrot repeated: " + word), false);
                            return Command.SINGLE_SUCCESS;
                        }
                        context.getSource().sendError(Text.literal("That nearby Smart Parrot has not learned this word yet."));
                        return 0;
                    })))
                .then(CommandManager.literal("info").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§bParrot + Simple Voice Chat §7| §fPackets: §e"
                                    + SmartParrotVoicePlugin.getReceivedPacketCount()
                                    + " §7| §fCaptures: §e" + VoiceMemory.getActiveSessionCount()
                                    + " §7| §fRAM: §e" + VoiceMemory.getLearnedWordCountNearest(player)
                                    + "/" + VoiceMemory.getMaxWordsPerParrot() + " words"
                                    + " §7| §fSaved phrases: §e" + VoiceMemory.getLearnedPhraseCountNearest(player)
                                    + " §7| §fConsenting players: §e" + VoiceMemory.getConsentingPlayerCount()
                                    + " §7| §fSpeech model: §e" + SpeechRecognitionService.getStatus()), false);
                    return Command.SINGLE_SUCCESS;
                }))
            );

            dispatcher.register(CommandManager.literal("parrotvoice")
                .then(CommandManager.literal("accept").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    VoiceMemory.setConsent(player.getUuid(), true);
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§aConsent saved. Smart Parrots may now learn your words and voice. Your learned clips remain in each parrot's compressed memory while you are offline. Use /parrotvoice revoke to erase your contributions."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("decline").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    VoiceMemory.setConsent(player.getUuid(), false);
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§cConsent declined. Voice learning is disabled and any clips previously contributed by you have been erased."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("revoke").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    VoiceMemory.setConsent(player.getUuid(), false);
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§eConsent revoked. Your active capture and all stored voice clips and phrases containing your voice have been erased from Smart Parrot memories."), false);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("consent").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    boolean enabled = VoiceMemory.toggleConsent(player.getUuid());
                    if (enabled) {
                        context.getSource().sendFeedback(() -> Text.literal(
                                "§aConsent enabled. Your learned voice may remain in Smart Parrot memory while you are offline. Use /parrotvoice revoke to erase it."), false);
                    } else {
                        context.getSource().sendFeedback(() -> Text.literal(
                                "§eConsent disabled and your stored voice contributions were erased."), false);
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(CommandManager.literal("status").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    String state = switch (VoiceMemory.getConsentState(player.getUuid())) {
                        case UNKNOWN -> "not decided";
                        case ACCEPTED -> "accepted";
                        case DECLINED -> "declined";
                    };
                    context.getSource().sendFeedback(() -> Text.literal(
                            "§bClient language: §f" + player.getClientOptions().language()
                                    + " §7| §bSpeech recognition: §f" + SpeechRecognitionService.getStatus()
                                    + " §7| §bConsent: §f" + state
                                    + " §7| §bParrot memory: §fcompressed and persistent"), false);
                    return Command.SINGLE_SUCCESS;
                }))
            );
        });
        LOGGER.info("Parrot + Simple Voice Chat initialized");
    }

    private static void sendConsentPrompt(ServerPlayerEntity player) {
        MutableText message = Text.literal(
                "§e¿Quieres dar consentimiento para que §bParrot + Simple Voice Chat§e utilice tu voz para dar más vida a los loros de Minecraft? Tu voz se procesará localmente en el servidor, sin enviarse a servicios externos, para que aprendan palabras y frases y puedan repetirlas. Las grabaciones se guardarán de forma permanente y comprimida en la memoria individual del loro, incluso cuando te desconectes. Puedes retirar el consentimiento y borrar tus grabaciones con /parrotvoice revoke.\n");
        message.append(Text.literal("[Sí]").styled(style -> style
                .withColor(Formatting.GREEN)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/parrotvoice accept"))));
        message.append(Text.literal("  "));
        message.append(Text.literal("[No]").styled(style -> style
                .withColor(Formatting.RED)
                .withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/parrotvoice decline"))));
        player.sendMessage(message, false);
    }
}
