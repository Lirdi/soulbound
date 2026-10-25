package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.UUID;

/**
 * God's Halo: a golden ring that floats above your head, worn in the helmet slot. The top damage dealer against God
 * takes it when he falls. Purely cosmetic, with a faint shimmer.
 */
final class HaloItem implements Listener {
    private final Soulbound plugin;
    private final Store store;
    private final NamespacedKey key;
    private BukkitTask task;
    private static final Particle.DustOptions SHINE = new Particle.DustOptions(Color.fromRGB(255, 226, 120), 0.8f);

    HaloItem(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        this.key = new NamespacedKey(plugin, "halo");
    }

    ItemStack create(String earnedBy) {
        ItemStack it = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Halo", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Taken from God himself.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, true),
                Component.text(earnedBy == null ? "Wear it on your head." : "Earned by " + earnedBy + ".", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "halo"));
        m.setMaxStackSize(1);
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        m.setEquippable(eq);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isHalo(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Give it now, or the next time they log in. */
    void award(UUID id, String name) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) { store.set("halo.pending." + id, name); store.save(); return; }
        give(p, name);
    }

    void give(Player p, String earnedBy) {
        for (ItemStack left : p.getInventory().addItem(create(earnedBy)).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1.2f);
        p.sendMessage(Component.text("You take God's Halo. Wear it with pride (or guilt).", NamedTextColor.GOLD));
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!isHalo(p.getInventory().getHelmet()) || p.isInvisible()) continue;
                p.getWorld().spawnParticle(Particle.DUST, p.getEyeLocation().add(0, 0.55, 0), 2, 0.18, 0.02, 0.18, 0, SHINE);
            }
        }, 40L, 15L);
    }

    void stop() { if (task != null) task.cancel(); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String path = "halo.pending." + e.getPlayer().getUniqueId();
        String earned = store.data.getString(path);
        if (earned == null) return;
        store.set(path, null); store.save();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (e.getPlayer().isOnline()) give(e.getPlayer(), earned); }, 40L);
    }
}
