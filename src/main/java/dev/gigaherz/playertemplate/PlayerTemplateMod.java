package dev.gigaherz.playertemplate;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashSet;

@Mod(PlayerTemplateMod.MODID)
public class PlayerTemplateMod
{
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MODID = "playertemplate";

    public PlayerTemplateMod()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::setup);
        modEventBus.addListener(this::capRegister);

        MinecraftForge.EVENT_BUS.addListener(this::command);
        MinecraftForge.EVENT_BUS.addListener(this::playerLoggedIn);
        MinecraftForge.EVENT_BUS.addListener(this::playerClone);
        MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, this::attach);

        //Make sure the mod being absent on the other network side does not cause the client to display the server as incompatible
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
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
            ctx.getSource().sendSuccess(Component.translatable("text.playertemplate.remove.success"), true);
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
            ctx.getSource().sendSuccess(Component.translatable("text.playertemplate.reload.success"), true);
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
        ctx.getSource().sendSuccess(Component.translatable("text.playertemplate.save.success"), true);
        return 0;
    }

    private int doApplyCommand(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players, String template, boolean wipe)
    {
        if (TemplateConfig.applyTemplate(players, wipe, template))
        {
            ctx.getSource().sendSuccess(Component.translatable("text.playertemplate.apply.success", players.size()), false);
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
        var player = event.getPlayer();
        if (player.level.isClientSide) return;
        if (player instanceof FakePlayer) return;
        if (player instanceof ServerPlayer sp)
        {
            player.getCapability(TEMPLATE).ifPresent(template -> {
                if (!template.given)
                {
                    TemplateConfig.autoApplyDefault(template, sp);
                }
            });
        }
    }

    private void attach(final AttachCapabilitiesEvent<Entity> event)
    {
        if (event.getObject() instanceof ServerPlayer sp)
        {
            event.addCapability(new ResourceLocation(MODID, "template"), new ICapabilitySerializable<CompoundTag>()
            {
                final TemplateCapability templateCapability = new TemplateCapability();
                final LazyOptional<TemplateCapability> lazyOptional = LazyOptional.of(() -> templateCapability);

                @Override
                public CompoundTag serializeNBT()
                {
                    var tag = new CompoundTag();
                    tag.putBoolean("given", templateCapability.given);
                    return tag;
                }

                @Override
                public void deserializeNBT(CompoundTag nbt)
                {
                    templateCapability.given = nbt.getBoolean("given");
                }

                @NotNull
                @Override
                public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side)
                {
                    return TEMPLATE.orEmpty(cap, lazyOptional);
                }
            });
        }
    }

    private void playerClone(PlayerEvent.Clone event)
    {
        var from = event.getOriginal();
        var to = event.getPlayer();

        if (from.level.isClientSide) return;

        from.reviveCaps();

        from.getCapability(TEMPLATE).ifPresent(template0 -> {
            to.getCapability(TEMPLATE).ifPresent(template1 -> {
                template1.copyFrom(template0);
            });
        });
    }

    private void capRegister(RegisterCapabilitiesEvent event)
    {
        event.register(TemplateCapability.class);
    }

    static final Capability<TemplateCapability> TEMPLATE = CapabilityManager.get(new CapabilityToken<>(){});

    static class TemplateCapability
    {
        public boolean given;

        public void copyFrom(TemplateCapability template0)
        {
            given = template0.given;
        }
    }
}
