package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/** The Kraken Beard: a deep-sea growth over the lower face, a fringe of drifting tentacles with glowing tips.
 *  Underwater it breathes for you and lights the dark (Conduit Power). */
final class KrakenBeard implements Listener {
    private static final int FRAMES = 6;
    private final Soulbound plugin;
    private final NamespacedKey key;
    private BukkitTask anim, task;
    private int frame;

    KrakenBeard(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "kraken_beard"); }

    void start() {
        anim = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 20L, 4L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }
    void stop() { if (anim != null) anim.cancel(); if (task != null) task.cancel(); }

    ItemStack create() {
        ItemStack it = new ItemStack(Material.ECHO_SHARD);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Kraken Beard", NamedTextColor.DARK_AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("It smells of the deep. It moves when you don't.", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, true),
                Component.text("Worn on the head", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
                Component.text("Underwater: Conduit Power (breathe, see, dig)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "kraken_beard_f0"));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setEquipSound(Sound.INTENTIONALLY_EMPTY);
        eq.setSwappable(true);
        eq.setDispensable(true);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    String adminGive(Player t) {
        t.getInventory().addItem(create()).values().forEach(i -> t.getWorld().dropItem(t.getLocation(), i));
        return "Gave " + t.getName() + " a Kraken Beard.";
    }

    private void animate() {
        frame = (frame + 1) % FRAMES;
        NamespacedKey want = new NamespacedKey("soulbound", "kraken_beard_f" + frame);
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack h = p.getInventory().getHelmet();
            if (!is(h)) continue;
            ItemMeta m = h.getItemMeta();
            if (want.equals(m.getItemModel())) continue;
            m.setItemModel(want);
            h.setItemMeta(m);
            p.getInventory().setHelmet(h);
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers())
            if (is(p.getInventory().getHelmet()) && p.isInWater())
                p.addPotionEffect(new PotionEffect(PotionEffectType.CONDUIT_POWER, 260, 0, true, false, true));
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (is(it)) { e.getInventory().setResult(null); return; }
    }
}
