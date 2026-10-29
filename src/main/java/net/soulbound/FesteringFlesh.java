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
 * fermented spider eyes. It fills anyone right up, but only the inhuman keep it down: everyone else is poisoned and
 * left starving (Poison and Hunger).
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
        if (!is(e.getItem())) return;
        Player p = e.getPlayer();
        justAte.put(p.getUniqueId(), System.currentTimeMillis());
        // it fills anyone up, but only the inhuman can keep it down: everyone else is poisoned and left starving
        var c = plugin.getConfig();
        if (plugin.inhuman() != null && plugin.inhuman().is(p)) {   // to the inhuman it's a feast, as good as a golden apple
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.REGENERATION,
                        20 * Math.max(1, c.getInt("festering-flesh.inhuman-regeneration-seconds", 5)), Math.max(0, c.getInt("festering-flesh.inhuman-regeneration-level", 2) - 1)));
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.RESISTANCE,
                        20 * Math.max(1, c.getInt("festering-flesh.inhuman-resistance-seconds", 120)), Math.max(0, c.getInt("festering-flesh.inhuman-resistance-level", 1) - 1)));
                p.sendActionBar(Component.text("Your body knits itself together.", NamedTextColor.DARK_RED));
            });
            return;
        }
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.POISON,
                    20 * Math.max(1, c.getInt("festering-flesh.human-poison-seconds", 6)), Math.max(0, c.getInt("festering-flesh.human-poison-level", 1) - 1)));
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.HUNGER,
                    20 * Math.max(1, c.getInt("festering-flesh.human-hunger-seconds", 30)), Math.max(0, c.getInt("festering-flesh.human-hunger-level", 2) - 1)));
            p.sendActionBar(Component.text("Your stomach turns.", NamedTextColor.DARK_GREEN));
        });
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
