package dev.gigaherz.playertemplate;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.datafixers.types.templates.Tag;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.*;
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
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.NetworkConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

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

    private void command(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(
                LiteralArgumentBuilder.<CommandSourceStack>literal("playertemplate")
                        .requires(cs->cs.hasPermission(Commands.GAMEMASTERS)) //permission
                        .then(Commands.literal("apply")
                                .then(Commands.argument("players", EntityArgument.players())
                                        .then(Commands.literal("wipe")
                                                .executes(ctx -> {
                                                    var players = EntityArgument.getPlayers(ctx, "players");
                                                    TemplateConfig.applyTemplate(players, true);
                                                    ctx.getSource().sendSuccess(new TextComponent("Player Template Applied to " + players.size() + " players."), false);
                                                    return 0;
                                                }))
                                        .executes(ctx -> {
                                            var players = EntityArgument.getPlayers(ctx, "players");
                                            TemplateConfig.applyTemplate(players, false);
                                            ctx.getSource().sendSuccess(new TextComponent("Player Template Applied to " + players.size() + " players."), false);
                                            return 0;
                                        })
                                )
                                .then(Commands.literal("wipe")
                                        .executes(ctx -> {
                                            TemplateConfig.applyTemplate(List.of(ctx.getSource().getPlayerOrException()), true);
                                            ctx.getSource().sendSuccess(new TextComponent("Player Template Applied."), false);
                                            return 0;
                                        })
                                )
                                .executes(ctx -> {
                                    TemplateConfig.applyTemplate(List.of(ctx.getSource().getPlayerOrException()), false);
                                    ctx.getSource().sendSuccess(new TextComponent("Player Template Applied."), false);
                                    return 0;
                                })
                        )
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    if (TemplateConfig.load())
                                    {
                                        ctx.getSource().sendSuccess(new TextComponent("Player Template Reloaded."), true);
                                        return 0;
                                    }
                                    else
                                    {
                                        ctx.getSource().sendFailure(new TextComponent("Reload failed!"));
                                        return -1;
                                    }
                                })
                        )
                        .then(Commands.literal("save")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> {
                                            TemplateConfig.saveTemplate(EntityArgument.getPlayer(ctx, "player"));
                                            ctx.getSource().sendSuccess(new TextComponent("Player Template Saved."), true);
                                            return 0;
                                        })
                                )
                                .executes(ctx -> {
                                    TemplateConfig.saveTemplate(ctx.getSource().getPlayerOrException());
                                    ctx.getSource().sendSuccess(new TextComponent("Player Template Saved."), true);
                                    return 0;
                                })
                        )

        );
    }

    private void playerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event)
    {
        var player = event.getPlayer();
        if (player.level.isClientSide) return;

        player.getCapability(TEMPLATE).ifPresent(template -> {
                if (!template.given)
                {
                    TemplateConfig.applyTemplate(template, player, false);
                }
        });
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
