package com.example.simplelogin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * /home - a 5-slot home GUI (chest menu, works with vanilla clients).
 *
 * Layout (3 rows): row 1 = five beds (teleport), row 2 = five dyes under them.
 * Gray dye = empty slot, click it to set a home at your current position.
 * Green dye = home set. Right-click it, then left-click to confirm deleting.
 */
public class HomeMod implements ModInitializer {

    private static final int MAX_HOMES = 5;
    private static final int[] BED_SLOTS = {11, 12, 13, 14, 15};
    private static final int[] DYE_SLOTS = {20, 21, 22, 23, 24};
    private static final int INFO_SLOT = 4;
    /** Each home gets its own bed colour; an empty home shows a light gray bed. */
    private static final String[] BED_COLORS = {"orange", "pink", "black", "green", "blue"};

    /** Plain data holder (public fields so Gson handles it on any version). */
    public static class Home {
        public String dimension;
        public double x, y, z;
        public float yRot, xRot;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, List<Home>>>() {}.getType();
    private static Map<String, List<Home>> data = new HashMap<>();
    private static Path file;

    @Override
    public void onInitialize() {
        file = FabricLoader.getInstance().getConfigDir().resolve("simplelogin").resolve("homes.json");
        load();
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, sel) -> register(dispatcher));
    }

    // ------------------------------------------------------------ storage

    private static void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    Map<String, List<Home>> loaded = GSON.fromJson(r, DATA_TYPE);
                    if (loaded != null) data = loaded;
                }
            }
        } catch (Exception e) {
            SimpleLoginMod.LOGGER.error("Could not read homes.json", e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(data, w);
            }
        } catch (IOException e) {
            SimpleLoginMod.LOGGER.error("Could not write homes.json", e);
        }
    }

    /** Always returns a list of exactly MAX_HOMES entries (null = empty slot). */
    private static List<Home> homesOf(UUID id) {
        List<Home> list = data.computeIfAbsent(id.toString(), k -> new ArrayList<>());
        while (list.size() < MAX_HOMES) list.add(null);
        return list;
    }

    // ------------------------------------------------------------ command

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("home").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            ServerPlayer player = src.getPlayerOrException();
            if (!SimpleLoginMod.isAuthenticated(player)) {
                src.sendFailure(Component.literal("You must log in first."));
                return 0;
            }
            player.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new HomeMenu(id, inv),
                    Component.literal("Homes of " + player.getName().getString()).withStyle(ChatFormatting.YELLOW)));
            return 1;
        }));
    }

    // ------------------------------------------------------------ item lookup
    // Looked up by registry id so this does not depend on field names in Items.

    private static final Map<String, Item> ITEM_CACHE = new HashMap<>();
    private static boolean warned = false;

    private static Item item(String path, String... keywords) {
        Item cached = ITEM_CACHE.get(path);
        if (cached != null) return cached;
        Item found = null;
        for (Item it : BuiltInRegistries.ITEM) {
            if (BuiltInRegistries.ITEM.getKey(it).toString().equals("minecraft:" + path)) {
                found = it;
                break;
            }
        }
        if (found == null) {
            for (Item it : BuiltInRegistries.ITEM) {
                String id = BuiltInRegistries.ITEM.getKey(it).toString();
                boolean all = true;
                for (String k : keywords) if (!id.contains(k)) { all = false; break; }
                if (all) { found = it; break; }
            }
        }
        if (found == null) {
            if (!warned) {
                warned = true;
                SimpleLoginMod.LOGGER.warn("/home: item minecraft:{} not found, using paper instead.", path);
            }
            found = Items.PAPER;
        }
        ITEM_CACHE.put(path, found);
        return found;
    }

    // ------------------------------------------------------------ items

    private static Component text(String s, ChatFormatting color) {
        return Component.literal(s).withStyle(style -> style.withColor(color).withItalic(false));
    }

    private static ItemStack named(Item item, Component name, Component... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (lore.length > 0) stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
        return stack;
    }

    private static ItemStack pane() {
        return named(item("gray_stained_glass_pane", "gray", "glass_pane"), Component.literal(" "));
    }

    // ------------------------------------------------------------ menu

    private static class HomeMenu extends ChestMenu {
        private final Container container;
        private int confirming = -1;

        HomeMenu(int containerId, Inventory inv) {
            this(containerId, inv, new SimpleContainer(27));
        }

        private HomeMenu(int containerId, Inventory inv, Container container) {
            super(net.minecraft.world.inventory.MenuType.GENERIC_9x3, containerId, inv, container, 3);
            this.container = container;
            // Replace the 27 chest slots with slots that trigger our actions and hold no items.
            for (int i = 0; i < 27; i++) {
                this.slots.set(i, new ActionSlot(this, container, i));
            }
            refresh(inv.player);
        }

        private void refresh(Player player) {
            List<Home> homes = homesOf(player.getUUID());
            int used = 0;
            for (Home h : homes) if (h != null) used++;

            for (int i = 0; i < 27; i++) container.setItem(i, pane());
            container.setItem(INFO_SLOT, named(Items.PAPER,
                    text("Homes: " + used + "/" + MAX_HOMES + " used", ChatFormatting.YELLOW),
                    text((MAX_HOMES - used) + " slot(s) free", ChatFormatting.GRAY)));

            for (int i = 0; i < MAX_HOMES; i++) {
                Home h = homes.get(i);
                if (h == null) {
                    container.setItem(BED_SLOTS[i], named(item("light_gray_bed", "gray", "bed"),
                            text("Home " + (i + 1) + " (not set)", ChatFormatting.RED),
                            text("Click the dye below to set it here", ChatFormatting.GRAY)));
                    container.setItem(DYE_SLOTS[i], named(item("gray_dye", "gray", "dye"),
                            text("Set home " + (i + 1), ChatFormatting.GRAY),
                            text("Click to save your current position", ChatFormatting.DARK_GRAY)));
                } else {
                    Component where = text(String.format("%s  %.0f, %.0f, %.0f",
                            shortDim(h.dimension), h.x, h.y, h.z), ChatFormatting.GRAY);
                    container.setItem(BED_SLOTS[i], named(item(BED_COLORS[i] + "_bed", BED_COLORS[i], "bed"),
                            text("Home " + (i + 1), ChatFormatting.GREEN),
                            where,
                            text("Click to teleport to the home", ChatFormatting.YELLOW)));
                    if (confirming == i) {
                        container.setItem(DYE_SLOTS[i], named(item("green_dye", "green", "dye"),
                                text("Delete home " + (i + 1) + "?", ChatFormatting.RED),
                                text("Click again to confirm delete", ChatFormatting.RED),
                                text("Click anything else to cancel", ChatFormatting.GRAY)));
                    } else {
                        container.setItem(DYE_SLOTS[i], named(item("green_dye", "green", "dye"),
                                text("Home " + (i + 1) + " is set", ChatFormatting.GREEN),
                                text("Click to delete", ChatFormatting.DARK_GRAY)));
                    }
                }
            }
        }

        private static String shortDim(String dim) {
            if (dim == null) return "?";
            // dim looks like "ResourceKey[minecraft:dimension / minecraft:overworld]"
            String d = dim.substring(dim.lastIndexOf('/') + 1).replace("]", "").trim();
            int idx = d.indexOf(':');
            return idx >= 0 ? d.substring(idx + 1) : d;
        }

        /** Called (server side) when a player tries to pick up from one of the GUI slots. */
        void onSlotClick(Player player, int slot) {
            if (!(player instanceof ServerPlayer sp)) return;
            if (!SimpleLoginMod.isAuthenticated(sp)) {
                sp.closeContainer();
                return;
            }
            handle(sp, slot);
            refresh(sp);
        }

        private void handle(ServerPlayer sp, int slot) {
            List<Home> homes = homesOf(sp.getUUID());

            for (int i = 0; i < MAX_HOMES; i++) {
                if (slot == BED_SLOTS[i]) {
                    confirming = -1;
                    Home h = homes.get(i);
                    if (h == null) {
                        sp.sendSystemMessage(text("Home " + (i + 1) + " is not set. Click the dye below it to set it.", ChatFormatting.RED));
                    } else {
                        teleport(sp, h, i);
                    }
                    return;
                }
                if (slot == DYE_SLOTS[i]) {
                    Home h = homes.get(i);
                    if (h == null) {
                        confirming = -1;
                        setHome(sp, homes, i);
                    } else if (confirming == i) {
                        homes.set(i, null);
                        save();
                        confirming = -1;
                        sp.sendSystemMessage(text("Home " + (i + 1) + " deleted. Slot freed.", ChatFormatting.YELLOW));
                    } else {
                        confirming = i;
                        sp.sendSystemMessage(text("Delete home " + (i + 1) + "? Click the green dye again to confirm, or click anything else to cancel.", ChatFormatting.RED));
                    }
                    return;
                }
            }
            confirming = -1; // clicked anything else: cancel pending delete
        }

        private void setHome(ServerPlayer sp, List<Home> homes, int i) {
            Home h = new Home();
            h.dimension = sp.level().dimension().toString();
            h.x = sp.getX();
            h.y = sp.getY();
            h.z = sp.getZ();
            h.yRot = sp.getYRot();
            h.xRot = sp.getXRot();
            homes.set(i, h);
            save();
            sp.sendSystemMessage(text(String.format("Home %d set at %.0f, %.0f, %.0f.", i + 1, h.x, h.y, h.z), ChatFormatting.GREEN));
        }

        private void teleport(ServerPlayer sp, Home h, int i) {
            ServerLevel level = null;
            for (ServerLevel candidate : sp.level().getServer().getAllLevels()) {
                if (candidate.dimension().toString().equals(h.dimension)) {
                    level = candidate;
                    break;
                }
            }
            if (level == null) {
                sp.sendSystemMessage(text("That dimension is not available.", ChatFormatting.RED));
                return;
            }
            sp.closeContainer();
            sp.teleportTo(level, h.x, h.y, h.z, Set.of(), h.yRot, h.xRot, true);
            sp.sendSystemMessage(text("Teleported to home " + (i + 1) + ".", ChatFormatting.GREEN));
        }
    }

    /** A display-only slot: picking it up runs the menu action instead of moving an item. */
    private static class ActionSlot extends Slot {
        private final HomeMenu menu;
        private final int slotIndex;

        ActionSlot(HomeMenu menu, Container container, int slotIndex) {
            super(container, slotIndex, 0, 0);
            this.menu = menu;
            this.slotIndex = slotIndex;
        }

        @Override
        public boolean mayPickup(Player player) {
            menu.onSlotClick(player, slotIndex);
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
