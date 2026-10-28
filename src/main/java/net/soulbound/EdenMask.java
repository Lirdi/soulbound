package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The Breath of Eden: a clear glass mask fed by a gold tube from a tiny caged Earth, on small gold wings. Worn on the
 * head, it lets you breathe Heaven's air: no Mining Fatigue out on the Seven Heavens (and no Slow Falling, unless
 * heaven-air.mask-keeps-slow-falling is on). It guards your head like a netherite helmet.
 */
final class EdenMask {
    private final Soulbound plugin;
    private final NamespacedKey key;

    EdenMask(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "breath_of_eden"); }

    ItemStack create() {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Breath of Eden", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("The first air there ever was, kept in glass.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, true),
                Component.text("Worn on the head", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("Breathe easy in the Heavens: no Mining Fatigue, no Slow Falling.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "breath_of_eden"));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setEquipSound(Sound.ITEM_ARMOR_EQUIP_GOLD);
        eq.setSwappable(true);
        eq.setDispensable(true);
        m.setEquippable(eq);
        // as tough as a netherite helmet
        m.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, "eden_armor"), 3, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        m.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(plugin, "eden_toughness"), 3, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        m.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(plugin, "eden_kb"), 0.1, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    boolean wearing(Player p) { return is(p.getInventory().getHelmet()); }
}
