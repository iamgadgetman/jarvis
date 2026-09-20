package com.gadgetman.jarvis.vanilla.platform;

import com.gadgetman.jarvis.core.platform.Items;
import com.gadgetman.jarvis.core.world.Item;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Core's {@link Items}, and the conversions between its item stacks and the game's. */
public final class VanillaItems implements Items {

    /** Where an item's core marker is kept: a string in the stack's custom data. */
    public static final String MARKER_KEY = "jarvis_marker";

    private final MinecraftServer server;

    public VanillaItems(MinecraftServer server) {
        this.server = server;
    }

    public static net.minecraft.world.item.Item item(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return key == null ? null : BuiltInRegistries.ITEM.getValue(key);
    }

    public static String id(net.minecraft.world.item.Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** Core's view of a stack. Empty stacks read as {@link Item#EMPTY}. */
    public static Item toItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Item.EMPTY;
        Map<String, Integer> enchants = new HashMap<>();
        ItemEnchantments ench = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        for (var entry : ench.entrySet()) {
            enchants.put(entry.getKey().getRegisteredName(), entry.getIntValue());
        }
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        String name = custom == null ? null : custom.getString();
        List<String> lore = List.of();
        ItemLore loreComponent = stack.get(DataComponents.LORE);
        if (loreComponent != null) {
            lore = new ArrayList<>();
            for (Component line : loreComponent.lines()) lore.add(line.getString());
        }
        boolean unbreakable = stack.has(DataComponents.UNBREAKABLE);
        String marker = null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null) marker = data.copyTag().getStringOr(MARKER_KEY, null);
        return new Item(id(stack.getItem()), stack.getCount(), enchants, name, lore, marker, unbreakable);
    }

    public ItemStack toStack(Item item) {
        return toStack(server, item);
    }

    public static ItemStack toStack(MinecraftServer server, Item item) {
        if (item == null || item.isEmpty()) return ItemStack.EMPTY;
        net.minecraft.world.item.Item kind = item(item.id());
        if (kind == null || kind == net.minecraft.world.item.Items.AIR) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(kind, item.count());
        if (item.displayName() != null) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(item.displayName()));
            // A blank name means "say nothing": menu filler. Without this the
            // client draws an empty tooltip box that follows the cursor.
            if (item.displayName().isBlank()) {
                stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
            }
        }
        if (!item.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : item.lore()) lines.add(Component.literal(line));
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (item.unbreakable()) stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        if (item.marker() != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString(MARKER_KEY, item.marker());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        if (!item.enchants().isEmpty() && server != null) {
            var registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            EnchantmentHelper.updateEnchantments(stack, mutable -> {
                for (Map.Entry<String, Integer> e : item.enchants().entrySet()) {
                    Identifier key = Identifier.tryParse(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey());
                    if (key == null) continue;
                    Optional<Holder.Reference<Enchantment>> holder = registry.get(ResourceKey.create(Registries.ENCHANTMENT, key));
                    holder.ifPresent(h -> mutable.set(h, e.getValue()));
                }
            });
        }
        return stack;
    }

    public static List<Item> toItems(Iterable<ItemStack> stacks) {
        List<Item> out = new ArrayList<>();
        for (ItemStack s : stacks) out.add(toItem(s));
        return out;
    }

    // ---- Items ----

    @Override
    public boolean isEdible(String id) {
        net.minecraft.world.item.Item i = item(id);
        return i != null && i.components().has(DataComponents.FOOD);
    }

    @Override
    public int maxStackSize(String id) {
        net.minecraft.world.item.Item i = item(id);
        return i == null ? 64 : i.getDefaultMaxStackSize();
    }

    @Override
    public Optional<String> resolve(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String s = name.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        Identifier key = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return Optional.empty();
        return Optional.of(key.toString());
    }
}
