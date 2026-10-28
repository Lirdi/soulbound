package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The World Egg that appears on God's island after each of His defeats: the first player to touch it (a click or a hit,
 * either hand) takes it straight into their inventory instead of watching it teleport away, the server hears about it,
 * and they earn "Atlas". Only on God's island; an egg someone has set down elsewhere behaves as usual.
 */
final class WorldEgg implements Listener {
    private final Soulbound plugin;

    WorldEgg(Soulbound plugin) { this.plugin = plugin; }

    private boolean godsEgg(Block b) {
        if (b == null || b.getType() != Material.DRAGON_EGG || b.getWorld().getEnvironment() != World.Environment.THE_END) return false;
        double r = plugin.getConfig().getDouble("world-egg.radius", 40);
        return (double) b.getX() * b.getX() + (double) b.getZ() * b.getZ() <= r * r;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTouch(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return;
        Block b = e.getClickedBlock();
        if (!godsEgg(b)) return;
        e.setCancelled(true);   // no teleporting away
        take(e.getPlayer(), b);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (!godsEgg(e.getBlock())) return;
        e.setCancelled(true);
        take(e.getPlayer(), e.getBlock());
    }

    private void take(Player p, Block b) {
        if (p.getGameMode() == GameMode.SPECTATOR || b.getType() != Material.DRAGON_EGG) return;
        b.setType(Material.AIR, false);
        for (ItemStack left : p.getInventory().addItem(new ItemStack(Material.DRAGON_EGG)).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.getWorld().spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 0.5, 0.5), 60, 0.4, 0.6, 0.4, 0.08);
        p.getWorld().playSound(b.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1.1f);
        p.getWorld().playSound(b.getLocation(), "minecraft:block.beacon.power_select", 1.5f, 1.4f);
        Bukkit.broadcast(Component.text(p.getName(), NamedTextColor.GOLD).append(Component.text(" claimed the World Egg.", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        NamespacedKey k = NamespacedKey.minecraft("end/atlas");
        org.bukkit.advancement.Advancement a = Bukkit.getAdvancement(k);
        if (a != null) { var pr = p.getAdvancementProgress(a); for (String c : pr.getRemainingCriteria()) pr.awardCriteria(c); }
    }
}
