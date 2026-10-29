package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Festering Flesh: dried, crawling meat with flies round it, sold by the Blood Priest for 13 rotten flesh and 3
 * fermented spider eyes. Disgusting to look at, but it fills you right up, and (unlike rotten flesh) it doesn't make
 * you sick.
 */
final class FesteringFlesh implements Listener {
    private final Soulbound plugin;
    private final NamespacedKey key;
    /** Who has just eaten one, so the rotten-flesh Hunger that comes with the bite can be stopped. */
    private final Map<UUID, Long> justAte = new HashMap<>();

    FesteringFlesh(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "festering_flesh"); }

    ItemStack create(int amount) {
        ItemStack it = new ItemStack(Material.ROTTEN_FLESH, amount);   // can't be cooked or smelted into anything
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Festering Flesh", TextColor.color(0x9C8A44)).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Found in a ditch.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true),
                Component.text("It had been there a while.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)));
        m.setItemModel(new NamespacedKey("soulbound", "festering_flesh"));
        FoodComponent food = m.getFood();
        food.setNutrition(Math.max(1, plugin.getConfig().getInt("festering-flesh.hunger", 10)));
        food.setSaturation((float) plugin.getConfig().getDouble("festering-flesh.saturation", 14.0));
        m.setFood(food);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        if (is(e.getItem())) justAte.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /** Rotten flesh's chance of Hunger doesn't come with this. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSick(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityPotionEffectEvent.Cause.FOOD) return;
        if (!PotionEffectType.HUNGER.equals(e.getModifiedType())) return;
        Long t = justAte.get(p.getUniqueId());
        if (t != null && System.currentTimeMillis() - t < 1000) e.setCancelled(true);
    }
}
