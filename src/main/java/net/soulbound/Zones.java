package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Where things are in Moloch's realm: the world, the summit fire pit, the temple grounds and the arena. */
final class Zones {
    private final Soulbound plugin;
    String worldKey;
    int pitY, pitTop, templeRadius;
    double arenaX, arenaY, arenaZ, arenaRadius;

    Zones(Soulbound plugin) { this.plugin = plugin; load(); }

    void load() {
        FileConfiguration c = plugin.getConfig();
        worldKey = c.getString("moloch.world", "sacrifice:sacrifice");
        pitY = c.getInt("moloch.pit-y", 125);
        pitTop = pitY + 14;
        templeRadius = c.getInt("moloch.temple-radius", 110);
        arenaX = c.getDouble("moloch.arena.x", 0.5);
        arenaY = c.getDouble("moloch.arena.y", 62);
        arenaZ = c.getDouble("moloch.arena.z", 0.5);
        arenaRadius = c.getDouble("moloch.arena.radius", 34);
    }

    /** Moloch's realm, if it's loaded. */
    World world() {
        NamespacedKey key = NamespacedKey.fromString(worldKey);
        World w = key != null ? Bukkit.getWorld(key) : null;
        if (w == null) w = Bukkit.getWorld(worldKey);
        return w;
    }

    boolean inRealm(Location l) {
        World w = world();
        return w != null && l.getWorld() != null && l.getWorld().equals(w);
    }

    /** Standing in (or falling into) the summit fire pit: the 3x3 shaft above the fire. */
    boolean inPit(Location l) {
        if (!inRealm(l)) return false;
        int x = l.getBlockX(), z = l.getBlockZ(), y = l.getBlockY();
        return x >= -1 && x <= 1 && z >= -1 && z <= 1 && y >= pitY && y <= pitTop;
    }

    /** The temple, its plaza and moat. */
    boolean inTemple(Location l) {
        return inRealm(l) && Math.max(Math.abs(l.getX()), Math.abs(l.getZ())) <= templeRadius;
    }

    /** Inside the battle hall under the pyramid. */
    boolean inArena(Location l) {
        return inRealm(l) && Math.max(Math.abs(l.getX() - arenaX), Math.abs(l.getZ() - arenaZ)) <= arenaRadius
                && l.getY() >= arenaY - 3 && l.getY() <= 122;
    }

    Location arenaCentre() {
        World w = world();
        return w == null ? null : new Location(w, arenaX, arenaY, arenaZ);
    }

    Location pitCentre() {
        World w = world();
        return w == null ? null : new Location(w, 0.5, pitY + 0.2, 0.5);
    }
}
