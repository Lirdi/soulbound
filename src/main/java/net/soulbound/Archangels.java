package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Shulkers in the End are Archangels: one hit from their bolt of judgement kills. */
public final class Archangels implements Listener {
    private final Soulbound plugin;
    private final Set<UUID> judged = new HashSet<>();

    public Archangels(Soulbound plugin) { this.plugin = plugin; }

    private boolean on() { return plugin.getConfig().getBoolean("archangels.instant-kill", true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBolt(EntityDamageByEntityEvent e) {
        if (!on() || !(e.getDamager() instanceof ShulkerBullet) || !(e.getEntity() instanceof Player p)) return;
        if (p.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        judged.add(p.getUniqueId());
        // More than any armour or enchantment can soak; a Totem of Undying still saves you if the config allows it.
        e.setDamage(1_000_000);
        if (!plugin.getConfig().getBoolean("archangels.totems-save", true)) {
            p.getInventory().remove(org.bukkit.Material.TOTEM_OF_UNDYING);
            if (p.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING)
                p.getInventory().setItemInOffHand(null);
        }
        p.getWorld().strikeLightningEffect(p.getLocation());
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 60, 0.4, 0.9, 0.4, 0.15);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 1.6f);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> judged.remove(p.getUniqueId()), 5L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (!judged.remove(e.getPlayer().getUniqueId())) return;
        e.deathMessage(Component.text(e.getPlayer().getName() + " was judged by an Archangel", NamedTextColor.GOLD));
    }
}
