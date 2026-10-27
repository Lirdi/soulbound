package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** The capture leash: a lead wrapped in iron chains, with its own look, name and recipe. */
final class LeashItem {
    private final Soulbound plugin;
    final NamespacedKey tagKey, recipeKey;
    private String name;
    private List<String> lore = new ArrayList<>();
    private boolean requireSpecial, consumeOnCapture;

    LeashItem(Soulbound plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "capture_leash");
        this.recipeKey = new NamespacedKey(plugin, "capture_leash");
        load();
    }

    void load() {
        FileConfiguration c = plugin.getConfig();
        name = c.getString("leash.name", "Cultist's Shackles");
        lore = c.getStringList("leash.lore");
        requireSpecial = c.getBoolean("leash.require-special", true);
        consumeOnCapture = c.getBoolean("leash.consume-on-capture", true);
    }

    boolean consumeOnCapture() { return consumeOnCapture; }

    /** Can this item be used to capture someone? */
    boolean captures(ItemStack item) {
        if (item == null || item.getType() != Material.LEAD) return false;
        return !requireSpecial || isSpecial(item);
    }

    boolean isSpecial(ItemStack item) {
        if (item == null || item.getType() != Material.LEAD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    ItemStack create(int amount) {
        ItemStack item = new ItemStack(Material.LEAD, 1);   // one per stack: each has its own wear
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) lines.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true));
        if (!lines.isEmpty()) meta.lore(lines);
        meta.setItemModel(new NamespacedKey("soulbound", "capture_leash"));
        meta.setEnchantmentGlintOverride(false);
        meta.setMaxStackSize(1);
        if (meta instanceof org.bukkit.inventory.meta.Damageable dm) dm.setMaxDamage(plugin.getConfig().getInt("leash.uses", 5));
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Eight iron chains around a lead. */
    void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        Material chain = Material.matchMaterial("minecraft:iron_chain");
        if (chain == null) chain = Material.matchMaterial("minecraft:chain");
        if (chain == null) { plugin.getLogger().warning("No iron chain item found; the leash recipe is off."); return; }
        ShapedRecipe r = new ShapedRecipe(recipeKey, create(1));
        r.shape("CCC", "CLC", "CCC");
        r.setIngredient('C', chain);
        r.setIngredient('L', Material.LEAD);
        Bukkit.addRecipe(r);
    }

    void unlockFor(Player p) { p.discoverRecipe(recipeKey); }
}
