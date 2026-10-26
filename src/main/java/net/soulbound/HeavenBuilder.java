package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.scheduler.BukkitTask;

/**
 * Builds Heaven's landmarks into the End: the Pearly Gates beside the obsidian platform where players arrive, and
 * God's Throne with a ring of white columns around the exit portal (clearing any blossom trees from the middle).
 * Each is built once per End world; reset the End and they're built again.
 */
final class HeavenBuilder implements org.bukkit.event.Listener {
    private final Soulbound plugin;
    private final Store store;
    private BukkitTask task;

    HeavenBuilder(Soulbound plugin, Store store) { this.plugin = plugin; this.store = store; }

    private BukkitTask fontTask;
    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tryBuild, 100L, 1200L);
        // the game puts the bedrock back whenever the dragon dies or is respawned, so keep the font painted
        fontTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> { World w = end(); if (w != null) font(w, false); }, 120L, 100L);
    }
    void stop() { if (task != null) task.cancel(); if (fontTask != null) fontTask.cancel(); }

    private World end() {
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.THE_END) return w;
        return null;
    }

    private void tryBuild() {
        if (!plugin.getConfig().getBoolean("god.build-heaven", true)) return;
        World w = end();
        if (w == null) return;
        String uid = w.getUID().toString();
        if (!uid.equals(store.data.getString("heaven.gates"))) { gates(w); store.set("heaven.gates", uid); store.save(); }
        if (!uid.equals(store.data.getString("heaven.throne"))) {
            // wait until the exit portal is there, so the throne sits around it
            if (portalY(w) < 0) return;
            throne(w);
            store.set("heaven.throne", uid); store.save();
        }
        if (!uid.equals(store.data.getString("heaven.pillars"))) { pillars(w); store.set("heaven.pillars", uid); store.save(); }
    }

    /** Rebuild both now (admin). */
    String rebuild() {
        World w = end();
        if (w == null) return "The End isn't loaded.";
        gates(w); throne(w); pillars(w); font(w, true);
        store.set("heaven.gates", w.getUID().toString()); store.set("heaven.throne", w.getUID().toString()); store.save();
        return "Heaven's gates and God's throne are built.";
    }

    // ---------------------------------------------------------------- the Pearly Gates

    /** The players' arrival platform is at 100 48 0; the gates stand on its island side (towards 0 0). */
    private void gates(World w) {
        int gx = 96, y0 = 49;
        // a clear way through, and a white path
        for (int x = 92; x <= 97; x++) for (int z = -2; z <= 2; z++) {
            for (int y = y0; y <= y0 + 7; y++) set(w, x, y, z, Material.AIR.createBlockData());
            set(w, x, y0 - 1, z, (z == 0 ? Material.GOLD_BLOCK : Material.SMOOTH_QUARTZ).createBlockData());
        }
        for (int side : new int[]{-3, 3}) {
            set(w, gx, y0, side, Material.CHISELED_QUARTZ_BLOCK.createBlockData());
            for (int y = y0 + 1; y <= y0 + 8; y++) {
                BlockData d = (y == y0 + 3 || y == y0 + 6 ? Material.GOLD_BLOCK : Material.QUARTZ_PILLAR).createBlockData();
                if (d instanceof Orientable o) o.setAxis(org.bukkit.Axis.Y);
                set(w, gx, y, side, d);
            }
            set(w, gx, y0 + 9, side, Material.GOLD_BLOCK.createBlockData());
            set(w, gx, y0 + 10, side, Material.LANTERN.createBlockData());
            // the gates themselves, swung open towards the island
            for (int y = y0; y <= y0 + 5; y++) for (int x = gx - 3; x <= gx - 1; x++) {
                BlockData pane = Material.WHITE_STAINED_GLASS_PANE.createBlockData();
                if (pane instanceof MultipleFacing mf) { mf.setFace(BlockFace.EAST, true); mf.setFace(BlockFace.WEST, true); }
                set(w, x, y, side - Integer.signum(side), pane);
            }
        }
        // the arch, with a pearl at its crown
        for (int z = -3; z <= 3; z++) set(w, gx, y0 + 9, z, (Math.abs(z) == 3 ? Material.GOLD_BLOCK : Material.QUARTZ_BRICKS).createBlockData());
        for (int z = -1; z <= 1; z++) set(w, gx, y0 + 10, z, Material.QUARTZ_BRICKS.createBlockData());
        set(w, gx, y0 + 11, 0, Material.SEA_LANTERN.createBlockData());
        set(w, gx, y0 + 8, 0, Material.CHISELED_QUARTZ_BLOCK.createBlockData());
        BlockData hang = Material.LANTERN.createBlockData();
        if (hang instanceof Lantern l) l.setHanging(true);
        set(w, gx, y0 + 7, 0, hang);
    }

    // ---------------------------------------------------------------- the Pillars of Heaven

    /**
     * The ten obsidian pillars become white marble columns banded with gold (the crystals and their cages stay).
     * Positions follow the game's own layout: radius 42, evenly spaced. Run again whenever the dragon is respawned,
     * because respawning rebuilds them in obsidian.
     */
    void pillars(World w) {
        for (int i = 0; i < 10; i++) {
            double a = 2.0 * (-Math.PI + (Math.PI / 10.0) * i);
            int cx = (int) Math.floor(42.0 * Math.cos(a)), cz = (int) Math.floor(42.0 * Math.sin(a));
            for (int x = cx - 6; x <= cx + 6; x++) for (int z = cz - 6; z <= cz + 6; z++) for (int y = 30; y <= 112; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType() != Material.OBSIDIAN) continue;
                boolean edge = Math.abs(Math.hypot(x - cx, z - cz)) >= 1.5 && isRim(w, x, y, z);
                Material m = y % 9 == 0 ? Material.GOLD_BLOCK
                        : b.getRelative(0, 1, 0).getType() != Material.OBSIDIAN ? Material.CHISELED_QUARTZ_BLOCK
                        : edge && (x + z) % 2 == 0 ? Material.QUARTZ_PILLAR : Material.SMOOTH_QUARTZ;
                b.setBlockData(m.createBlockData(), false);
            }
        }
    }

    private static boolean isRim(World w, int x, int y, int z) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            if (w.getBlockAt(x, y, z).getRelative(f).getType().isAir()) return true;
        return false;
    }

    /** A respawned dragon means freshly rebuilt obsidian pillars: turn them to marble again. */
    @org.bukkit.event.EventHandler
    public void onDragon(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (e.getEntityType() != org.bukkit.entity.EntityType.ENDER_DRAGON || e.getLocation().getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (!plugin.getConfig().getBoolean("god.build-heaven", true)) return;
        World w = e.getLocation().getWorld();
        Bukkit.getScheduler().runTaskLater(plugin, () -> pillars(w), 100L);
    }

    // ---------------------------------------------------------------- the Holy Font

    /**
     * The exit portal's bedrock podium becomes a font of white marble and gold: a marble basin with four gold sockets,
     * a fluted column with a gold cap where the torches were (now End rods), and a step of quartz around it. The portal
     * itself glows gold through the resource pack. Only bedrock and the podium's torches are ever replaced.
     */
    private boolean fontOn() { return plugin.getConfig().getBoolean("god.build-heaven", true) && plugin.getConfig().getBoolean("god.holy-font", true); }

    /** The podium's centre at portal level, or null if it isn't loaded or can't be found. */
    private int[] podium(World w) {
        org.bukkit.boss.DragonBattle b = w.getEnderDragonBattle();
        org.bukkit.Location l = b == null ? null : b.getEndPortalLocation();
        int x = l == null ? 0 : l.getBlockX(), z = l == null ? 0 : l.getBlockZ(), y0 = l == null ? 64 : l.getBlockY();
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int lo = l == null ? 1 : y0 - 5, hi = l == null ? 120 : y0 + 5;
        for (int y = hi; y >= lo; y--) {
            if (rimBlock(w.getBlockAt(x + 3, y, z)) && rimBlock(w.getBlockAt(x - 3, y, z)) && rimBlock(w.getBlockAt(x, y, z + 3))
                    && capBlock(w.getBlockAt(x, y + 3, z))) return new int[]{x, y, z};
        }
        return null;
    }
    private static boolean rimBlock(Block b) { Material m = b.getType(); return m == Material.BEDROCK || m == Material.SMOOTH_QUARTZ || m == Material.GOLD_BLOCK; }
    private static boolean capBlock(Block b) { Material m = b.getType(); return m == Material.BEDROCK || m == Material.GOLD_BLOCK; }
    private static boolean socket(int dx, int dz) { return (Math.abs(dx) == 3 && dz == 0) || (dx == 0 && Math.abs(dz) == 3); }

    void font(World w, boolean force) {
        if (!fontOn()) return;
        int[] o = podium(w);
        if (o == null) return;
        int x = o[0], y = o[1], z = o[2];
        boolean needed = force;
        for (int dy = -1; dy <= 3 && !needed; dy++) for (int dx = -3; dx <= 3 && !needed; dx++) for (int dz = -3; dz <= 3 && !needed; dz++) {
            Material m = w.getBlockAt(x + dx, y + dy, z + dz).getType();
            if (m == Material.BEDROCK || m == Material.WALL_TORCH) needed = true;
        }
        if (!needed) return;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) for (int dy = -1; dy <= 3; dy++) {
            Block b = w.getBlockAt(x + dx, y + dy, z + dz);
            Material m = b.getType();
            if (dx == 0 && dz == 0) {
                if (m != Material.BEDROCK) continue;
                BlockData d = (dy == 0 ? Material.CHISELED_QUARTZ_BLOCK : dy == 3 ? Material.GOLD_BLOCK : Material.QUARTZ_PILLAR).createBlockData();
                if (d instanceof Orientable or) or.setAxis(org.bukkit.Axis.Y);
                b.setBlockData(d, false);
                continue;
            }
            if (m == Material.WALL_TORCH) {
                BlockData rod = Material.END_ROD.createBlockData();
                if (rod instanceof org.bukkit.block.data.Directional rd && b.getBlockData() instanceof org.bukkit.block.data.Directional td) rd.setFacing(td.getFacing());
                b.setBlockData(rod, false);
                continue;
            }
            if (m != Material.BEDROCK) continue;
            Material to = dy == 0 && socket(dx, dz) ? Material.GOLD_BLOCK : Material.SMOOTH_QUARTZ;
            b.setBlockData(to.createBlockData(), false);
        }
        // a quartz step round the basin, with four little lamps on the diagonals
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            double d = Math.hypot(dx, dz);
            if (d < 3.6 || d > 4.6) continue;
            Block b = w.getBlockAt(x + dx, y, z + dz);
            if (!b.getType().isAir() || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
            if (Math.abs(dx) == 3 && Math.abs(dz) == 3) {
                b.setBlockData(Material.CHISELED_QUARTZ_BLOCK.createBlockData(), false);
                Block top = b.getRelative(0, 1, 0);
                if (top.getType().isAir()) top.setBlockData(Material.END_ROD.createBlockData(), false);
            } else {
                BlockData slab = Material.SMOOTH_QUARTZ_SLAB.createBlockData();
                b.setBlockData(slab, false);
            }
        }
    }

    /**
     * The font's sockets are gold, not bedrock, so vanilla won't let you set an End crystal on them. Place it for the
     * player, then let the game check for all four and start the respawn, exactly as it would on bedrock.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onSocket(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        org.bukkit.inventory.ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.END_CRYSTAL) return;
        Block b = e.getClickedBlock();
        World w = b.getWorld();
        if (w.getEnvironment() != World.Environment.THE_END || b.getType() != Material.GOLD_BLOCK) return;
        int[] o = podium(w);
        if (o == null || b.getY() != o[1] || !socket(b.getX() - o[0], b.getZ() - o[2])) return;
        e.setCancelled(true);
        if (!b.getRelative(0, 1, 0).getType().isAir()) return;
        org.bukkit.Location at = b.getLocation().add(0.5, 1, 0.5);
        if (!w.getNearbyEntitiesByType(org.bukkit.entity.EnderCrystal.class, at, 0.9).isEmpty()) return;
        w.spawn(at, org.bukkit.entity.EnderCrystal.class, c -> c.setShowingBottom(false));
        if (e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
        org.bukkit.boss.DragonBattle battle = w.getEnderDragonBattle();
        if (battle != null) battle.initiateRespawn();
    }

    // ---------------------------------------------------------------- God's Throne

    private int portalY(World w) {
        for (int y = 120; y > 0; y--) {
            Material m = w.getBlockAt(0, y, 0).getType();
            if (m == Material.BEDROCK || (m == Material.GOLD_BLOCK && w.getBlockAt(0, y - 1, 0).getType() == Material.QUARTZ_PILLAR)) return y;   // bedrock, or the font's gold cap
        }
        return -1;
    }

    /** The ground at a column: the first solid block from above, ignoring trees and flowers. */
    private int ground(World w, int x, int z) {
        for (int y = 120; y > 1; y--) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m.isAir() || !m.isSolid() || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.BEE_NEST) continue;
            return y;
        }
        return 60;
    }

    private void throne(World w) {
        // clear the blossom trees from the middle of the island
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = 40; y <= 120; y++) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.BEE_NEST || m == Material.PINK_PETALS
                    || m == Material.CHORUS_PLANT || m == Material.CHORUS_FLOWER)
                set(w, x, y, z, Material.AIR.createBlockData());
        }
        // a ring of eight white columns around the portal
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int x = (int) Math.round(Math.cos(a) * 12), z = (int) Math.round(Math.sin(a) * 12);
            if (z <= -10 && Math.abs(x) <= 4) continue;   // the throne stands here
            int g = ground(w, x, z);
            set(w, x, g + 1, z, Material.CHISELED_QUARTZ_BLOCK.createBlockData());
            for (int y = g + 2; y <= g + 8; y++) {
                BlockData d = Material.QUARTZ_PILLAR.createBlockData();
                if (d instanceof Orientable o) o.setAxis(org.bukkit.Axis.Y);
                set(w, x, y, z, d);
            }
            set(w, x, g + 9, z, Material.GOLD_BLOCK.createBlockData());
            BlockData fire = Material.CAMPFIRE.createBlockData();
            if (fire instanceof Lightable l) l.setLit(true);
            set(w, x, g + 10, z, fire);
        }
        // the throne, north of the portal, facing it
        int g = ground(w, 0, -17);
        store.set("heaven.seat", "0.5," + (g + 3.5) + ",-16.5");   // where God sits
        for (int x = -5; x <= 5; x++) for (int z = -21; z <= -14; z++) {
            for (int y = g + 1; y <= g + 16; y++) set(w, x, y, z, Material.AIR.createBlockData());
            set(w, x, g, z, Material.SMOOTH_QUARTZ.createBlockData());
            for (int y = g - 1; y >= g - 4; y--) if (!w.getBlockAt(x, y, z).getType().isSolid()) set(w, x, y, z, Material.SMOOTH_QUARTZ.createBlockData());
        }
        for (int x = -4; x <= 4; x++) for (int z = -20; z <= -15; z++) set(w, x, g + 1, z, (z == -15 ? Material.SMOOTH_QUARTZ_STAIRS : Material.SMOOTH_QUARTZ).createBlockData());
        for (int x = -3; x <= 3; x++) for (int z = -20; z <= -16; z++) set(w, x, g + 2, z, Material.QUARTZ_BRICKS.createBlockData());
        // the seat
        for (int x = -2; x <= 2; x++) {
            BlockData st = Material.QUARTZ_STAIRS.createBlockData();
            if (st instanceof Stairs s) s.setFacing(BlockFace.NORTH);
            set(w, x, g + 3, -17, st);
            set(w, x, g + 3, -18, Material.QUARTZ_BLOCK.createBlockData());
        }
        // the back, tall and gold-edged, crowned with a pearl
        for (int x = -3; x <= 3; x++) for (int y = g + 3; y <= g + 12; y++)
            set(w, x, y, -19, (Math.abs(x) == 3 || y == g + 12 ? Material.GOLD_BLOCK : (y % 3 == 0 ? Material.CHISELED_QUARTZ_BLOCK : Material.QUARTZ_BLOCK)).createBlockData());
        for (int x = -1; x <= 1; x++) set(w, x, g + 13, -19, Material.GOLD_BLOCK.createBlockData());
        set(w, 0, g + 14, -19, Material.SEA_LANTERN.createBlockData());
        // armrests
        for (int side : new int[]{-3, 3}) {
            for (int z = -18; z <= -16; z++) set(w, side, g + 3, z, Material.QUARTZ_PILLAR.createBlockData());
            set(w, side, g + 4, -16, Material.GOLD_BLOCK.createBlockData());
        }
    }

    private static void set(World w, int x, int y, int z, BlockData d) {
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m == Material.BEDROCK || m == Material.END_PORTAL || m == Material.END_GATEWAY) return;   // never touch the exit portal or gateways
        b.setBlockData(d, false);
    }
}
