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
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tryBuild, 20L, 100L);   // every 5 seconds: build as soon as the portal is there
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
        // whenever the game puts obsidian back on a tower (a respawn, a reset), turn it to wax again
        else if (obsidianShowing(w)) pillars(w);
        // the Rotunda and the Ten Candles came later: build them once on a Heaven made before them
        if (!uid.equals(store.data.getString("heaven.rotunda")) && podium(w) != null) {
            throne(w); pillars(w);
            store.set("heaven.rotunda", uid); store.save();
        }
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
     * The Ten Candles of God: each crystal pillar becomes a giant ivory wax candle, wax running down its sides and
     * pooled round the top, standing in a gold holder with a wide dish; the crystal on top is its flame. Run again
     * whenever the dragon is respawned (respawning rebuilds them in obsidian). Works on obsidian pillars and on the
     * older marble ones.
     */
    void pillars(World w) {
        for (int i = 0; i < 10; i++) {
            double a = 2.0 * (-Math.PI + (Math.PI / 10.0) * i);
            int cx = (int) Math.floor(42.0 * Math.cos(a)), cz = (int) Math.floor(42.0 * Math.sin(a));
            if (!w.isChunkLoaded(cx >> 4, cz >> 4)) w.getChunkAt(cx >> 4, cz >> 4).load();
            // the top: the bedrock the crystal stands on
            int top = -1;
            for (int y = 120; y > 30; y--) if (w.getBlockAt(cx, y, cz).getType() == Material.BEDROCK) { top = y - 1; break; }
            if (top < 0 || !pillarBlock(w.getBlockAt(cx, top, cz).getType())) continue;
            int r = 0;
            while (r < 7 && pillarBlock(w.getBlockAt(cx + r + 1, top, cz).getType())) r++;
            // the ground just outside it (ignoring a holder from an earlier run)
            int g = 40;
            for (int y = top; y > 1; y--) {
                Material m = w.getBlockAt(cx + r + 3, y, cz).getType();
                if (m.isAir() || !m.isSolid() || m == Material.GOLD_BLOCK || m == Material.SMOOTH_QUARTZ_SLAB || m == Material.WHITE_CARPET) continue;
                g = y + 1; break;
            }
            for (int y = Math.max(1, g - 6); y <= top; y++)
                for (int x = cx - r - 1; x <= cx + r + 1; x++) for (int z = cz - r - 1; z <= cz + r + 1; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!pillarBlock(b.getType())) continue;
                    double d = Math.hypot(x - cx, z - cz);
                    if (d > r + 0.5) continue;
                    boolean rim = d > r - 0.6;
                    int drip = (hash(x, z) % 10) - 2;
                    Material m = (y == top || (rim && (y == top - 1 || drip >= top - y))) ? Material.WHITE_CONCRETE : Material.BONE_BLOCK;
                    b.setBlockData(m.createBlockData(), false);
                }
            // a melted, uneven lip round the top
            for (int x = cx - r; x <= cx + r; x++) for (int z = cz - r; z <= cz + r; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > r + 0.5 || d <= r - 0.6 || hash(x, z) % 3 == 0) continue;
                Block b = w.getBlockAt(x, top + 1, z);
                if (b.getType().isAir()) b.setBlockData(Material.WHITE_CONCRETE.createBlockData(), false);
            }
            // the gold holder: a cup hugging the base, a wide dish, a few puddles of spilt wax
            int R = r + 4;
            for (int x = cx - R; x <= cx + R; x++) for (int z = cz - R; z <= cz + R; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d <= r + 0.5 || d > r + 3.5) continue;
                if (d <= r + 1.5) { setIfFree(w, x, g, z, Material.GOLD_BLOCK.createBlockData()); setIfFree(w, x, g + 1, z, Material.GOLD_BLOCK.createBlockData()); }
                else if (d <= r + 2.5) setIfFree(w, x, g, z, Material.GOLD_BLOCK.createBlockData());
                else {
                    setIfFree(w, x, g, z, Material.SMOOTH_QUARTZ_SLAB.createBlockData());
                    if (hash(x, z) % 6 == 0) setIfFree(w, x, g + 1, z, Material.WHITE_CARPET.createBlockData());
                }
            }
        }
    }

    /** Any obsidian left on a tower whose chunk is loaded? (Cheap: one column per tower.) */
    private static boolean obsidianShowing(World w) {
        for (int i = 0; i < 10; i++) {
            double a = 2.0 * (-Math.PI + (Math.PI / 10.0) * i);
            int cx = (int) Math.floor(42.0 * Math.cos(a)), cz = (int) Math.floor(42.0 * Math.sin(a));
            if (!w.isChunkLoaded(cx >> 4, cz >> 4)) continue;
            for (int y = 120; y > 30; y--) if (w.getBlockAt(cx, y, cz).getType() == Material.OBSIDIAN) return true;
        }
        return false;
    }

    private static boolean pillarBlock(Material m) {
        return m == Material.OBSIDIAN || m == Material.SMOOTH_QUARTZ || m == Material.QUARTZ_PILLAR || m == Material.CHISELED_QUARTZ_BLOCK
                || m == Material.GOLD_BLOCK || m == Material.BONE_BLOCK || m == Material.WHITE_CONCRETE;
    }

    private static int hash(int x, int z) { return ((x * 73856093) ^ (z * 19349663)) & 0xffff; }

    /** Only into air, plants and loose stuff: never through the island or into something built. */
    private static void setIfFree(World w, int x, int y, int z, BlockData d) {
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m.isAir() || !m.isSolid() || m == Material.GOLD_BLOCK || m == Material.SMOOTH_QUARTZ_SLAB || m == Material.END_STONE)
            if (m != Material.BEDROCK && m != Material.END_PORTAL && m != Material.END_GATEWAY && m != Material.IRON_BARS) b.setBlockData(d, false);
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
        // the game raises the new towers over a few seconds; re-marble them as they go up so the obsidian barely shows
        for (long t : new long[]{10L, 30L, 60L, 100L, 160L, 240L}) Bukkit.getScheduler().runTaskLater(plugin, () -> pillars(w), t);
    }

    /** Someone arrives in the End: build Heaven straight away if it isn't built yet. */
    @org.bukkit.event.EventHandler
    public void onArrive(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        if (e.getPlayer().getWorld().getEnvironment() == World.Environment.THE_END) Bukkit.getScheduler().runTaskLater(plugin, this::tryBuild, 10L);
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
        rotunda(w);
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

    // ---------------------------------------------------------------- the Rotunda of Heaven

    private static boolean throneZone(int x, int z) { return Math.abs(x) <= 7 && z >= -23 && z <= -11; }

    /**
     * Around the Holy Font: a sunburst floor of white marble with gold rays, a ring of sixteen marble columns with gold
     * capitals and glowing finials, joined overhead by a marble entablature hung with lanterns (open where the throne
     * stands); and the Processional Way, a marble road with a gold line and lamp posts, out east to the Pearly Gates.
     */
    private void rotunda(World w) {
        int[] pod = podium(w);
        int Y = pod != null ? pod[1] - 1 : ground(w, 8, 8);
        // the floor: cleared above, built up underneath
        for (int x = -19; x <= 19; x++) for (int z = -19; z <= 19; z++) {
            double d = Math.hypot(x, z);
            if (d > 18.5 || d <= 6.5 || throneZone(x, z)) continue;
            for (int y = Y + 1; y <= Y + 16; y++) set(w, x, y, z, Material.AIR.createBlockData());
            for (int y = Y - 1; y >= Y - 15 && !w.getBlockAt(x, y, z).getType().isSolid(); y--) set(w, x, y, z, Material.SMOOTH_QUARTZ.createBlockData());
            double ang = Math.atan2(z, x);
            int rays = d > 8.5 ? 16 : 8;
            boolean ray = false;
            for (int k = 0; k < rays && !ray; k++) {
                double th = k * 2 * Math.PI / rays;
                ray = d * Math.abs(Math.sin(ang - th)) < 0.5 && Math.cos(ang - th) > 0;
            }
            Material m = (d > 7.5 && d <= 8.5) || d > 16.5 ? Material.CHISELED_QUARTZ_BLOCK : ray && d <= 16.5 ? Material.GOLD_BLOCK : Material.SMOOTH_QUARTZ;
            set(w, x, Y, z, m.createBlockData());
        }
        // the columns
        java.util.Set<Long> cols = new java.util.HashSet<>();
        for (int k = 0; k < 16; k++) {
            double a = (k + 0.5) * 2 * Math.PI / 16;
            int x = (int) Math.round(15 * Math.cos(a)), z = (int) Math.round(15 * Math.sin(a));
            if (throneZone(x, z)) continue;
            cols.add(((long) x << 32) ^ (z & 0xffffffffL));
            set(w, x, Y + 1, z, Material.CHISELED_QUARTZ_BLOCK.createBlockData());
            for (int y = Y + 2; y <= Y + 8; y++) set(w, x, y, z, pillarY());
            set(w, x, Y + 9, z, Material.GOLD_BLOCK.createBlockData());
            set(w, x, Y + 12, z, Material.END_ROD.createBlockData());
        }
        // the entablature
        for (int x = -17; x <= 17; x++) for (int z = -17; z <= 17; z++) {
            double d = Math.hypot(x, z);
            if (d <= 14.4 || d > 15.6 || (Math.abs(x) <= 8 && z < -10)) continue;
            set(w, x, Y + 10, z, Material.QUARTZ_BRICKS.createBlockData());
            boolean col = cols.contains(((long) x << 32) ^ (z & 0xffffffffL));
            set(w, x, Y + 11, z, (col ? Material.GOLD_BLOCK : Material.SMOOTH_QUARTZ_SLAB).createBlockData());
        }
        for (int k = 0; k < 16; k++) {
            double a = (k + 1) * 2 * Math.PI / 16;
            int x = (int) Math.round(15 * Math.cos(a)), z = (int) Math.round(15 * Math.sin(a));
            if (throneZone(x, z) || (Math.abs(x) <= 8 && z < -10) || w.getBlockAt(x, Y + 10, z).getType() != Material.QUARTZ_BRICKS) continue;
            BlockData lamp = Material.LANTERN.createBlockData();
            if (lamp instanceof Lantern l) l.setHanging(true);
            set(w, x, Y + 9, z, lamp);
        }
        // the Processional Way, east to the gates (their road starts at x 92, one below the gates' floor at y 49)
        int x0 = 19, x1 = 91, yEnd = 48;
        // one of the Ten Candles stands right on the road (the first pillar, due east): the road rings round it
        int ccx = (int) Math.floor(42.0 * Math.cos(-2 * Math.PI)), ccz = (int) Math.floor(42.0 * Math.sin(-2 * Math.PI));
        int[] ys = new int[x1 - x0 + 2];
        for (int x = x0; x <= x1 + 1; x++) ys[x - x0] = (int) Math.round(Y + (yEnd - Y) * (double) (x - x0) / (x1 + 1 - x0));
        for (int x = x0; x <= x1; x++) {
            int y = ys[x - x0], yn = ys[x - x0 + 1];
            for (int z = -2; z <= 2; z++) {
                if (Math.hypot(x - ccx, z - ccz) <= 9.5) continue;
                for (int yy = y + 1; yy <= y + 5; yy++) set(w, x, yy, z, Material.AIR.createBlockData());
                for (int yy = y - 1; yy >= y - 15 && !w.getBlockAt(x, yy, z).getType().isSolid(); yy--) set(w, x, yy, z, Material.SMOOTH_QUARTZ.createBlockData());
                set(w, x, y, z, (Math.abs(z) == 2 ? Material.QUARTZ_BRICKS : z == 0 ? Material.GOLD_BLOCK : Material.SMOOTH_QUARTZ).createBlockData());
                if (yn == y - 1 && x + 1 <= x1) set(w, x + 1, y, z, Material.SMOOTH_QUARTZ_SLAB.createBlockData());   // a half step down
                if (yn == y + 1) set(w, x, y + 1, z, Material.SMOOTH_QUARTZ_SLAB.createBlockData());                // a half step up
            }
            if ((x - x0) % 8 == 0 && Math.abs(x - ccx) > 11) for (int z : new int[]{-3, 3}) {
                set(w, x, y + 1, z, Material.CHISELED_QUARTZ_BLOCK.createBlockData());
                set(w, x, y + 2, z, pillarY()); set(w, x, y + 3, z, pillarY());
                set(w, x, y + 4, z, Material.LANTERN.createBlockData());
                for (int yy = y; yy >= y - 15 && !w.getBlockAt(x, yy, z).getType().isSolid(); yy--) set(w, x, yy, z, Material.SMOOTH_QUARTZ.createBlockData());
            }
        }
        ringRoad(w, ccx, ccz, ys, x0);
    }

    /** The rest of rotunda(): the ring of road round the candle that stands on the Way. */
    private void ringRoad(World w, int ccx, int ccz, int[] ys, int x0) {
        for (int x = ccx - 11; x <= ccx + 11; x++) for (int z = ccz - 11; z <= ccz + 11; z++) {
            double d = Math.hypot(x - ccx, z - ccz);
            if (d <= 9.0 || d > 11.0 || x - x0 < 0 || x - x0 >= ys.length) continue;
            int y = ys[x - x0];
            for (int yy = y + 1; yy <= y + 5; yy++) set(w, x, yy, z, Material.AIR.createBlockData());
            for (int yy = y - 1; yy >= y - 15 && !w.getBlockAt(x, yy, z).getType().isSolid(); yy--) set(w, x, yy, z, Material.SMOOTH_QUARTZ.createBlockData());
            set(w, x, y, z, (d > 10.3 || d <= 9.4 ? Material.QUARTZ_BRICKS : Material.SMOOTH_QUARTZ).createBlockData());
        }
    }

    private static BlockData pillarY() {
        BlockData d = Material.QUARTZ_PILLAR.createBlockData();
        if (d instanceof Orientable o) o.setAxis(org.bukkit.Axis.Y);
        return d;
    }

    private static void set(World w, int x, int y, int z, BlockData d) {
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m == Material.BEDROCK || m == Material.END_PORTAL || m == Material.END_GATEWAY) return;   // never touch the exit portal or gateways
        b.setBlockData(d, false);
    }
}
