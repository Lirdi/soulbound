package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The Blade of Potential ("Twin Worlds"): a greatsword made of two Eggs of Potential fused end to end, the upper one drawn
 * to a point with its ice cap at the tip. Hits as hard as the Quantum Blade, swings at a sword's speed, and carries
 * Knockback V. Admin-given for now (no recipe).
 */
final class PotentialBlade {
    static final double DAMAGE = 9;      // the Quantum Blade's: 10 attack damage in all
    static final double SPEED = -2.4;    // a golden sword's swing (1.6 a second)
    static final int KNOCKBACK = 5;
    static final int DURABILITY = 2800;

    private final Soulbound plugin;
    private final NamespacedKey key;

    PotentialBlade(Soulbound plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "potential_blade");
    }

    ItemStack item() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Blade of Potential", TextColor.color(0x4FA8E8), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Two worlds, one edge.", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, true),
                Component.text("Twin Worlds", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "potential_blade"));
        // setting any modifier replaces the netherite defaults, so damage and speed are given in full
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(plugin, "potential_blade_damage"), DAMAGE, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey(plugin, "potential_blade_speed"), SPEED, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addEnchant(Enchantment.KNOCKBACK, KNOCKBACK, true);   // past the vanilla cap of II
        if (m instanceof Damageable d) d.setMaxDamage(DURABILITY);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    String adminGive(Player to) {
        for (ItemStack left : to.getInventory().addItem(item()).values()) to.getWorld().dropItem(to.getLocation(), left);
        return "Gave " + to.getName() + " the Blade of Potential.";
    }
}
