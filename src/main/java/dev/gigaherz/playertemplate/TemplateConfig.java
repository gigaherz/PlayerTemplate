package dev.gigaherz.playertemplate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.items.ItemHandlerHelper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TemplateConfig
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<ItemEntry> items = new ArrayList<>();

    public static Iterable<ItemEntry> getItems()
    {
        return Collections.unmodifiableList(items);
    }

    public record ItemEntry(int slot, ItemStack stack) {}

    public static final Codec<ItemEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("slot").forGetter(ItemEntry::slot),
            ItemStack.CODEC.fieldOf("stack").forGetter(ItemEntry::stack)
    ).apply(instance, ItemEntry::new));

    public static final Codec<List<ItemEntry>> CONFIG_CODEC = ENTRY_CODEC.listOf();

    private static Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path makePath()
    {
        return FMLPaths.CONFIGDIR.get().resolve("player_template.json");
    }

    public static boolean load()
    {
        try
        {
            var path = makePath();

            if (Files.exists(path))
            {
                JsonElement config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonElement.class);

                var result = CONFIG_CODEC.decode(JsonOps.INSTANCE, config).get();
                return result.map(
                        ok -> {
                            items = ok.getFirst();

                            return true;
                        },
                        error -> {
                            items = new ArrayList<>();

                            LOGGER.error("Error loading playertemplate config!");

                            return false;
                        }
                );
            }

            return true; // nothing to load
        }
        catch (IOException e)
        {
            LOGGER.error("Error loading playertemplate config", e);

            return false;
        }
    }

    public static void save()
    {
        try
        {
            var path = makePath();

            if (!Files.exists(path))
            {
                Files.createDirectories(path.getParent());
            }

            var result = CONFIG_CODEC.encodeStart(JsonOps.INSTANCE, items);

            var json = result.result().get();

            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public static  void applyTemplate(Iterable<? extends Player> players, boolean wipeExistingInventory)
    {
        for(var player : players)
        {
            player.getCapability(PlayerTemplateMod.TEMPLATE).ifPresent(template -> {
                applyTemplate(template, player, wipeExistingInventory);
            });
        }
    }

    public static void applyTemplate(PlayerTemplateMod.TemplateCapability template, Player player, boolean wipeExistingInventory)
    {
        LOGGER.info("Applying template to " + player.getDisplayName().getString() + " (" + player.getStringUUID() + ")");

        if (wipeExistingInventory)
        {
            player.getInventory().clearContent();
        }

        for(var itemEntry : TemplateConfig.getItems())
        {
            var slot = itemEntry.slot();
            var stack = itemEntry.stack().copy();

            var inv = player.getInventory();
            var existing = inv.getItem(slot);
            if (existing.getCount() > 0)
            {
                ItemHandlerHelper.giveItemToPlayer(player, stack);
            }
            else
            {
                inv.setItem(slot, stack);
            }
        }

        template.given = true;
    }

    public static void saveTemplate(ServerPlayer player)
    {
        var items = new ArrayList<ItemEntry>();
        var inv = player.getInventory();
        for(int i=0;i<inv.getContainerSize();i++)
        {
            var stack = inv.getItem(i);
            if (stack.getCount() > 0)
            {
                items.add(new ItemEntry(i, stack));
            }
        }
        TemplateConfig.items = items;
        save();
    }
}
