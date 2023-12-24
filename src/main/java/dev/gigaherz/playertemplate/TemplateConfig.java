package dev.gigaherz.playertemplate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TemplateConfig
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static Map<String, List<ItemEntry>> itemSets = new HashMap<>();

    public static Set<String> itemSetNames()
    {
        return itemSets.keySet();
    }

    public record ItemEntry(int slot, ItemStack stack)
    {
    }

    public static final Codec<ItemEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("slot").forGetter(ItemEntry::slot),
            ItemStack.CODEC.fieldOf("stack").forGetter(ItemEntry::stack)
    ).apply(instance, ItemEntry::new));

    public static final Codec<Map<String, List<ItemEntry>>> CONFIG_CODEC = Codec.either(
                    ENTRY_CODEC.listOf(),
                    Codec.unboundedMap(Codec.STRING, ENTRY_CODEC.listOf()))
            .xmap(either -> either.map(
                            list -> Map.of("default", list),
                            map -> map
                    ), Either::right);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
                            itemSets = new HashMap<>(ok.getFirst());

                            return true;
                        },
                        error -> {
                            itemSets = new HashMap<>();

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

            var result = CONFIG_CODEC.encodeStart(JsonOps.INSTANCE, itemSets);

            var json = result.result().orElseThrow();

            Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);

            LOGGER.info("Saved player templates.");

            debugMessage = true;
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public static boolean applyTemplate(Collection<ServerPlayer> players, boolean wipeExistingInventory, String itemSet)
    {
        var set = itemSets.get(itemSet);
        if (set == null)
        {
            LOGGER.info("Could not apply template " + itemSet + " because it does not exist.");
            return false;
        }

        LOGGER.info("Applying template to " + players.size() + " players...");

        for (var player : players)
        {
            var template = player.getData(PlayerTemplateMod.TEMPLATE);
            applyTemplate(template, player, wipeExistingInventory, set);
        }

        return true;
    }

    private static boolean debugMessage = false;
    public static void autoApplyDefault(PlayerTemplateMod.TemplateCapability template, ServerPlayer player)
    {
        var set = itemSets.get("default");
        if (set == null)
        {
            if (!debugMessage)
            {
                LOGGER.info("Could not apply default template because it does not exist.");
                debugMessage = true;
            }
            return;
        }

        LOGGER.info("Applying template to " + player.getDisplayName().getString() + " (" + player.getStringUUID() + ")...");

        applyTemplate(template, player, false, set);
    }

    private static void applyTemplate(PlayerTemplateMod.TemplateCapability template, Player player, boolean wipeExistingInventory, List<ItemEntry> set)
    {
        if (wipeExistingInventory)
        {
            player.getInventory().clearContent();
        }

        for (var itemEntry : set)
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

    public static void saveTemplate(ServerPlayer player, String itemSet)
    {
        var items = new ArrayList<ItemEntry>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++)
        {
            var stack = inv.getItem(i);
            if (stack.getCount() > 0)
            {
                items.add(new ItemEntry(i, stack));
            }
        }

        TemplateConfig.itemSets.put(itemSet, items);
        save();
    }

    public static boolean removeTemplate(String itemSet)
    {
        if (itemSets.remove(itemSet) == null)
            return false;

        save();
        return true;
    }
}
