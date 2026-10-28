package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Carried Home": out on the Heaven islands a bed doesn't explode. You lie down, doze off, and God carries you back to
 * his island, setting you down in front of the exit portal. Nobody gets to live out on the clouds.
 */
final class HeavenBeds implements Listener {
    private static final List<String> LINES = List.of(
            "You'll catch your death out there. In you come.",
            "Can't have you sleeping on a cloud. You'll fall through.",
            "There. Much better. Don't snore.",
            "Bedtime's at my house, child.",
            "Sleep near the throne. I'll keep the light on.",
            "You looked cold. Don't tell anyone I did this.");
    private final Soulbound plugin;
    private final Set<UUID> dozing = new HashSet<>();

    HeavenBeds(Soulbound plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBed(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();
        if (b == null || !Tag.BEDS.isTagged(b.getType()) || p.isSneaking()) return;
        if (b.getWorld().getEnvironment() != World.Environment.THE_END || !plugin.getConfig().getBoolean("heaven-beds.carried-home", true)) return;
        double r = plugin.getConfig().getDouble("island.radius", 200);
        double x = b.getX() + 0.5, z = b.getZ() + 0.5;
        if (x * x + z * z < r * r) return;   // the main island keeps the vanilla rules
        e.setCancelled(true);                // no explosion
        if (!dozing.add(p.getUniqueId())) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 6, false, false, false));
        p.playSound(p.getLocation(), "minecraft:block.wool.place", 1f, 0.7f);
        p.sendActionBar(Component.text("You doze off among the clouds...", NamedTextColor.GRAY, TextDecoration.ITALIC));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            dozing.remove(p.getUniqueId());
            if (!p.isOnline() || p.isDead() || p.getWorld() != b.getWorld()) return;
            World w = b.getWorld();
            int y = w.getHighestBlockYAt(0, 8) + 1;
            Location home = new Location(w, 0.5, y, 8.5, 180f, 0f);
            p.teleport(home);
            p.setFallDistance(0);
            p.playSound(home, "minecraft:block.amethyst_block.chime", 1f, 0.6f);
            w.spawnParticle(org.bukkit.Particle.END_ROD, home.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.03);
            p.sendMessage(Component.text("God: ", NamedTextColor.GOLD).append(Component.text(LINES.get(ThreadLocalRandom.current().nextInt(LINES.size())), NamedTextColor.WHITE)));
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) grant(p, "minecraft:end/carried_home");
        }, 50L);
    }

    private static void grant(Player p, String id) {
        NamespacedKey k = NamespacedKey.fromString(id);
        Advancement a = k == null ? null : Bukkit.getAdvancement(k);
        if (a == null) return;
        AdvancementProgress pr = p.getAdvancementProgress(a);
        for (String c : pr.getRemainingCriteria()) pr.awardCriteria(c);
    }
}
