package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The Blade of Potential ("Twin Worlds"): a greatsword made of two Eggs of Potential fused end to end.
 * Unbreakable, no enchantment shimmer. Starts as strong as a netherite sword, with Smite III. Its potential is grown at
 * an anvil: blade + a nether star = +1 Sharpness (Sharpness and Smite sit together on it), up to Sharpness VIII.
 * Grindstones won't strip it. Admin-given for now (no recipe).
 */
final class PotentialBlade implements Listener {
    static final int MAX_SHARPNESS = 8;
    static final int STAR_COST = 5;      // levels per nether star

    private final Soulbound plugin;
    private final NamespacedKey key;

    PotentialBlade(Soulbound plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "potential_blade");
    }

    ItemStack item() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);   // netherite sword damage and speed, untouched
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Blade of Potential", TextColor.color(0x4FA8E8), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.setItemModel(new NamespacedKey("soulbound", "potential_blade"));
        m.addEnchant(Enchantment.SMITE, 3, true);
        m.setEnchantmentGlintOverride(false);
        m.setUnbreakable(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        lore(m);
        it.setItemMeta(m);
        return it;
    }

    private void lore(ItemMeta m) {
        int sharp = m.getEnchantLevel(Enchantment.SHARPNESS);
        m.lore(List.of(
                Component.text("Two worlds, one edge.", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, true),
                Component.text("Potential: " + sharp + " / " + MAX_SHARPNESS, sharp >= MAX_SHARPNESS ? NamedTextColor.GOLD : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(sharp >= MAX_SHARPNESS ? "Fulfilled." : "Anvil + a nether star: +1 Sharpness.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    String adminGive(Player to) {
        for (ItemStack left : to.getInventory().addItem(item()).values()) to.getWorld().dropItem(to.getLocation(), left);
        return "Gave " + to.getName() + " the Blade of Potential.";
    }

    // ---------------------------------------------------------------- the anvil: a nether star for each level of Sharpness

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack blade = e.getInventory().getFirstItem(), star = e.getInventory().getSecondItem();
        if (!is(blade)) return;
        if (star == null || star.getType().isAir()) return;   // renaming alone is fine
        if (star.getType() != Material.NETHER_STAR) { e.setResult(null); return; }   // only stars feed it
        int sharp = blade.getItemMeta().getEnchantLevel(Enchantment.SHARPNESS);
        if (sharp >= MAX_SHARPNESS) { e.setResult(null); return; }
        ItemStack out = blade.clone();
        ItemMeta m = out.getItemMeta();
        m.addEnchant(Enchantment.SHARPNESS, sharp + 1, true);   // sits beside Smite, past the vanilla cap
        m.setEnchantmentGlintOverride(false);
        String rename = e.getView().getRenameText();
        if (rename != null && !rename.isBlank() && !rename.equals(plainName(blade))) m.displayName(Component.text(rename));
        lore(m);
        out.setItemMeta(m);
        e.setResult(out);
        AnvilView view = e.getView();
        view.setRepairCost(STAR_COST);
        view.setRepairItemCountCost(1);   // one star, not the whole stack
        Bukkit.getScheduler().runTask(plugin, () -> { view.setRepairCost(STAR_COST); view.setRepairItemCountCost(1); });
    }

    private static String plainName(ItemStack it) {
        Component n = it.getItemMeta().displayName();
        return n == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(n);
    }

    /** A grindstone would strip the Sharpness the stars paid for. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrindstone(PrepareGrindstoneEvent e) {
        if (is(e.getInventory().getUpperItem()) || is(e.getInventory().getLowerItem())) e.setResult(null);
    }
}
