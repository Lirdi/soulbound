package net.soulbound;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * God's fight turns the Heavens' clock (night while He holds court, dawn as the Days begin). Only the main island
 * should see that: anyone out on the other islands always sees a clear midday sky, and gets the real sky back the moment
 * they come within reach of the main island.
 */
final class OuterDaylight implements Listener {
    private final Soulbound plugin;
    /** Players whose sky we've pinned, so we only ever undo our own. */
    private final Set<UUID> pinned = new HashSet<>();

    OuterDaylight(Soulbound plugin) { this.plugin = plugin; }

    void start() { plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L); }

    /** Is this player on (or over) the main island, where God's fight is seen and heard? */
    static boolean onMainIsland(Soulbound plugin, Player p) {
        if (p.getWorld().getEnvironment() != World.Environment.THE_END) return false;
        double r = plugin.getConfig().getDouble("god.main-island-radius", 200);
        double x = p.getLocation().getX(), z = p.getLocation().getZ();
        return x * x + z * z <= r * r;
    }

    private void tick() {
        boolean on = plugin.getConfig().getBoolean("heaven.outer-islands-always-day", true);
        long noon = plugin.getConfig().getLong("heaven.outer-islands-time", 6000);
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean pin = on && p.getWorld().getEnvironment() == World.Environment.THE_END && !onMainIsland(plugin, p)
                    && p.getGameMode() != GameMode.SPECTATOR;
            if (pin) {
                if (p.isPlayerTimeRelative() || p.getPlayerTime() % 24000 != noon % 24000) p.setPlayerTime(noon, false);
                pinned.add(p.getUniqueId());
            } else if (pinned.remove(p.getUniqueId())) {
                p.resetPlayerTime();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (pinned.remove(e.getPlayer().getUniqueId())) e.getPlayer().resetPlayerTime();
    }
}
