package com.yourname.jarvis.npc.custom;

import com.yourname.jarvis.Jarvis;
import com.yourname.jarvis.npc.provider.INPCProvider;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * Manages equipment for custom NPCs using Bukkit API.
 * Since FakePlayer uses a Villager, we use Bukkit's EntityEquipment interface.
 */
public class NPCEquipment {

    private final Jarvis plugin;
    private final FakePlayer fakePlayer;

    private final Map<INPCProvider.EquipmentSlot, ItemStack> equipment = new EnumMap<>(INPCProvider.EquipmentSlot.class);

    public NPCEquipment(Jarvis plugin, PacketManager packetManager, FakePlayer fakePlayer) {
        this.plugin = plugin;
        this.fakePlayer = fakePlayer;
    }

    /**
     * Set equipment in a slot.
     */
    public void setEquipment(INPCProvider.EquipmentSlot slot, ItemStack item) {
        equipment.put(slot, item != null ? item.clone() : null);
        applyToEntity(slot, item);
    }

    /**
     * Get equipment from a slot.
     */
    public ItemStack getEquipment(INPCProvider.EquipmentSlot slot) {
        ItemStack item = equipment.get(slot);
        return item != null ? item.clone() : null;
    }

    /**
     * Set the held item (main hand).
     */
    public void setHeldItem(ItemStack item) {
        setEquipment(INPCProvider.EquipmentSlot.HAND, item);
    }

    /**
     * Get the held item (main hand).
     */
    public ItemStack getHeldItem() {
        return getEquipment(INPCProvider.EquipmentSlot.HAND);
    }

    /**
     * Apply equipment to the entity.
     */
    private void applyToEntity(INPCProvider.EquipmentSlot slot, ItemStack item) {
        if (!fakePlayer.isSpawned()) return;

        if (fakePlayer.getEntity() instanceof LivingEntity living) {
            EntityEquipment entityEquip = living.getEquipment();
            if (entityEquip == null) return;

            switch (slot) {
                case HAND -> entityEquip.setItemInMainHand(item);
                case OFF_HAND -> entityEquip.setItemInOffHand(item);
                case HEAD -> entityEquip.setHelmet(item);
                case CHEST -> entityEquip.setChestplate(item);
                case LEGS -> entityEquip.setLeggings(item);
                case FEET -> entityEquip.setBoots(item);
            }
        }
    }

    /**
     * Send all equipment to a new viewer (for compatibility).
     */
    public void sendAllEquipment(org.bukkit.entity.Player viewer) {
        // With Bukkit entities, equipment is automatically visible
        // This method exists for API compatibility
    }

    /**
     * Clear all equipment.
     */
    public void clearAll() {
        for (INPCProvider.EquipmentSlot slot : INPCProvider.EquipmentSlot.values()) {
            setEquipment(slot, null);
        }
    }
}
