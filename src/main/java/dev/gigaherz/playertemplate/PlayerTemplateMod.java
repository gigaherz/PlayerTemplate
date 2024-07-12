package dev.gigaherz.playertemplate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Collection;
import java.util.HashSet;
import java.util.function.Supplier;

@Mod(PlayerTemplateMod.MODID)
public class PlayerTemplateMod
{
    public static final String MODID = "playertemplate";

    public PlayerTemplateMod(IEventBus modEventBus)
    {
        modEventBus.addListener(this::setup);

        ATTACHMENT_TYPES.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(this::command);
        NeoForge.EVENT_BUS.addListener(this::playerLoggedIn);
    }

    private void setup(FMLCommonSetupEvent event)
    {
        TemplateConfig.load();
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TEMPLATES = (ctx, builder) -> {
        var keys = TemplateConfig.itemSetNames();
        return SharedSuggestionProvider.suggest(keys, builder);
    };
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TEMPLATES_DEFAULT = (ctx, builder) -> {
        var keys = new HashSet<>(TemplateConfig.itemSetNames());
        keys.add("default");
        return SharedSuggestionProvider.suggest(keys, builder);
    };
    private void command(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("playertemplate")
                        .requires(cs -> cs.hasPermission(Commands.LEVEL_GAMEMASTERS)) //permission
                        .then(Commands.literal("give")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .then(Commands.argument("template", StringArgumentType.word())
                                                .suggests(SUGGEST_TEMPLATES)
                                                .executes(ctx -> doApplyCommand(ctx,
                                                        EntityArgument.getPlayers(ctx, "players"),
                                                        StringArgumentType.getString(ctx, "template"),
                                                        false)))
                                        .executes(ctx -> doApplyCommand(ctx,
                                                EntityArgument.getPlayers(ctx, "players"),
                                                "default",
                                                false))))
                        .then(Commands.literal("replace")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .then(Commands.argument("template", StringArgumentType.word())
                                                .suggests(SUGGEST_TEMPLATES)
                                                .executes(ctx -> doApplyCommand(ctx,
                                                        EntityArgument.getPlayers(ctx, "players"),
                                                        StringArgumentType.getString(ctx, "template"),
                                                        true)))
                                        .executes(ctx -> doApplyCommand(ctx,
                                                EntityArgument.getPlayers(ctx, "players"),
                                                "default",
                                                true))))
                        .then(Commands.literal("reload").executes(this::doReloadCommand))
                        .then(Commands.literal("save")
                                .then(Commands.argument("template", StringArgumentType.word())
                                        .suggests(SUGGEST_TEMPLATES_DEFAULT)
                                        .executes(ctx -> doSaveCommand(ctx,
                                                ctx.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(ctx, "template"))))
                                .executes(ctx -> doSaveCommand(ctx,
                                        ctx.getSource().getPlayerOrException(),
                                        "default")))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("template", StringArgumentType.word())
                                        .suggests(SUGGEST_TEMPLATES)
                                        .executes(ctx -> doRemoveCommand(ctx,
                                                StringArgumentType.getString(ctx, "template"))))));
    }

    private int doRemoveCommand(CommandContext<CommandSourceStack> ctx, String template)
    {
        if (TemplateConfig.removeTemplate(template))
        {
            ctx.getSource().sendSuccess(()-> Component.translatable("text.playertemplate.remove.success"), true);
            return 0;
        }
        else
        {
            ctx.getSource().sendFailure(Component.translatable("text.playertemplate.remove.failure.not_found"));
            return -1;
        }
    }

    private int doReloadCommand(CommandContext<CommandSourceStack> ctx)
    {
        if (TemplateConfig.load())
        {
            ctx.getSource().sendSuccess(()-> Component.translatable("text.playertemplate.reload.success"), true);
            return 0;
        }
        else
        {
            ctx.getSource().sendFailure(Component.translatable("text.playertemplate.reload.failure"));
            return -1;
        }
    }

    private int doSaveCommand(CommandContext<CommandSourceStack> ctx, ServerPlayer player, String template)
    {
        TemplateConfig.saveTemplate(player, template);
        ctx.getSource().sendSuccess(()-> Component.translatable("text.playertemplate.save.success"), true);
        return 0;
    }

    private int doApplyCommand(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players, String template, boolean wipe)
    {
        if (TemplateConfig.applyTemplate(players, wipe, template))
        {
            ctx.getSource().sendSuccess(()-> Component.translatable("text.playertemplate.apply.success", players.size()), false);
            return 0;
        }
        else
        {
            ctx.getSource().sendFailure(Component.translatable("text.playertemplate.apply.failure", players.size()));
            return -1;
        }
    }

    private void playerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event)
    {
        var player = event.getEntity();
        if (player.level().isClientSide) return;
        if (player instanceof FakePlayer) return;
        if (player instanceof ServerPlayer sp)
        {
            var template = player.getData(TEMPLATE);
            if (!template.given)
            {
                TemplateConfig.autoApplyDefault(template, sp);
            }
        }
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);


    public static final Codec<TemplateCapability> CODEC = RecordCodecBuilder.create(inst ->
            inst.group(Codec.BOOL.fieldOf("given").forGetter(t -> t.given))
                    .apply(inst, TemplateCapability::new));
    public static final Supplier<AttachmentType<TemplateCapability>> TEMPLATE = ATTACHMENT_TYPES.register(
            "template", () -> AttachmentType
                    .builder(TemplateCapability::new)
                    .serialize(CODEC)
                    .copyOnDeath()
                    .build()
    );

    static class TemplateCapability
    {
        public boolean given;

        public TemplateCapability(IAttachmentHolder holder)
        {
        }

        public TemplateCapability(boolean given)
        {
            this.given = given;
        }
    }
}
