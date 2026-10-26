package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Gate of Heaven: every stronghold's End portal room is rebuilt the first time it loads. White marble and gold walls
 * with fluted columns, a gilded ceiling, a dark tiled floor with a gold aisle, holy-water fonts where the lava pools were,
 * hanging lanterns, candles, an angel spawner in place of the silverfish, and God's great stone face carved into the far
 * wall, watching the portal with glowing eyes. The twelve frames and the portal itself are never touched.
 * Anyone who walks in is greeted (or warned) by God.
 */
final class StrongholdGate implements Listener {
    private static final Set<Material> STONE = EnumSet.of(Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
            Material.CHISELED_STONE_BRICKS, Material.INFESTED_STONE_BRICKS, Material.INFESTED_MOSSY_STONE_BRICKS,
            Material.INFESTED_CRACKED_STONE_BRICKS, Material.INFESTED_CHISELED_STONE_BRICKS, Material.STONE, Material.INFESTED_STONE,
            Material.COBBLESTONE, Material.INFESTED_COBBLESTONE, Material.SMOOTH_STONE);
    // God's face on the far wall, top row first (lx 1..9): H hair, M marble skin, G glowing eyes, N nose, B beard
    private static final String[] FACE = {"HHHHHHHHH", "HMMMMMMMH", "HMGGMGGMH", "BMMMNMMMB", "BBMBBBMBB", "BBBBBBBBB"};
    private static final String[] LINES = {
            "Ah. Someone found the door. Wipe your feet.",
            "I can see you, you know. I always could.",
            "Turn back. ...No? Fine. Come up, then.",
            "Every soul that passes this gate is weighed. Yours looks... light.",
            "Oh good, another one. Mind the lava. I put it there on purpose."};

    private final Soulbound plugin;
    private final Store store;
    private final Set<String> done = new HashSet<>();
    private final List<String> rooms = new ArrayList<>();
    private final Map<UUID, Long> greeted = new HashMap<>();
    private BukkitTask task;

    StrongholdGate(Soulbound plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        done.addAll(store.data.getStringList("stronghold.done"));
        rooms.addAll(store.data.getStringList("stronghold.rooms"));
    }

    private boolean on() { return plugin.getConfig().getBoolean("stronghold.gate-of-heaven", true); }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::greet, 40L, 20L); }
    void stop() { if (task != null) task.cancel(); }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        if (!on() || e.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        for (GeneratedStructure gs : e.getChunk().getStructures(Structure.STRONGHOLD)) {
            for (StructurePiece piece : gs.getPieces()) {
                BoundingBox bb = piece.getBoundingBox();
                long dx = Math.round(bb.getWidthX()), dy = Math.round(bb.getHeight()), dz = Math.round(bb.getWidthZ());
                boolean portalRoom = (dy == 8 || dy == 7) && ((dx >= 10 && dx <= 11 && dz >= 15 && dz <= 16) || (dz >= 10 && dz <= 11 && dx >= 15 && dx <= 16));
                if (!portalRoom) continue;
                String key = e.getWorld().getName() + ";" + (int) bb.getMinX() + ";" + (int) bb.getMinY() + ";" + (int) bb.getMinZ();
                if (done.contains(key)) continue;
                World w = e.getWorld();
                BoundingBox box = bb.clone();
                Bukkit.getScheduler().runTask(plugin, () -> tryRebuild(w, box, key));
            }
        }
    }

    private void tryRebuild(World w, BoundingBox bb, String key) {
        if (done.contains(key)) return;
        int x0 = (int) Math.floor(bb.getMinX()), y0 = (int) Math.floor(bb.getMinY()), z0 = (int) Math.floor(bb.getMinZ());
        int x1 = (int) Math.ceil(bb.getMaxX()), y1 = (int) Math.ceil(bb.getMaxY()), z1 = (int) Math.ceil(bb.getMaxZ());
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++)
            if (!w.isChunkGenerated(cx, cz)) return;   // the rest of the room isn't there yet: a later chunk load will try again
        // the twelve frames give the portal's centre exactly
        int n = 0; double sx = 0, sy = 0, sz = 0;
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
            if (w.getBlockAt(x, y, z).getType() == Material.END_PORTAL_FRAME) { n++; sx += x; sy += y; sz += z; }
        done.add(key);
        store.set("stronghold.done", new ArrayList<>(done));
        if (n < 12) { store.save(); return; }
        int cx = (int) Math.round(sx / n), cy = (int) Math.round(sy / n), cz = (int) Math.round(sz / n);
        // which way the room runs: from the door towards the far wall, along its long side, towards the portal
        double mx = (bb.getMinX() + bb.getMaxX()) / 2, mz = (bb.getMinZ() + bb.getMaxZ()) / 2;
        int fx = 0, fz = 0;
        if (bb.getWidthZ() > bb.getWidthX()) fz = cz + 0.5 > mz ? 1 : -1; else fx = cx + 0.5 > mx ? 1 : -1;
        int lxX = fz != 0 ? 1 : 0, lxZ = fx != 0 ? 1 : 0;   // the room's width runs across the long side
        rebuild(w, cx, cy, cz, fx, fz, lxX, lxZ);
        // remember the room so visitors can be greeted
        int[] a = at(cx, cy, cz, fx, fz, lxX, lxZ, 0, 0, 0), b = at(cx, cy, cz, fx, fz, lxX, lxZ, 10, 7, 15);
        rooms.add(w.getName() + ";" + Math.min(a[0], b[0]) + ";" + Math.min(a[1], b[1]) + ";" + Math.min(a[2], b[2]) + ";"
                + Math.max(a[0], b[0]) + ";" + Math.max(a[1], b[1]) + ";" + Math.max(a[2], b[2]));
        store.set("stronghold.rooms", rooms);
        store.save();
        plugin.getLogger().info("The Gate of Heaven: rebuilt a stronghold portal room at " + cx + " " + cy + " " + cz + ".");
    }

    /** Room coordinates (lx across 0..10, ly up 0..7, lz from the door 0..15) to the world; the portal's centre is (5, 3, 10). */
    private static int[] at(int cx, int cy, int cz, int fx, int fz, int lxX, int lxZ, int lx, int ly, int lz) {
        return new int[]{cx + (lz - 10) * fx + (lx - 5) * lxX, cy + (ly - 3), cz + (lz - 10) * fz + (lx - 5) * lxZ};
    }

    private void rebuild(World w, int cx, int cy, int cz, int fx, int fz, int lxX, int lxZ) {
        for (int lx = 0; lx <= 10; lx++) for (int ly = 0; ly <= 7; ly++) for (int lz = 0; lz <= 15; lz++) {
            int[] p = at(cx, cy, cz, fx, fz, lxX, lxZ, lx, ly, lz);
            Block b = w.getBlockAt(p[0], p[1], p[2]);
            Material m = b.getType();
            if (m == Material.END_PORTAL_FRAME || m == Material.END_PORTAL) continue;
            // God's face fills the far wall
            if (lz == 15 && lx >= 1 && lx <= 9 && ly >= 1 && ly <= 6) {
                char c = FACE[6 - ly].charAt(lx - 1);
                put(b, switch (c) {
                    case 'H' -> Material.WHITE_CONCRETE;
                    case 'G' -> Material.GLOWSTONE;
                    case 'N' -> Material.END_STONE_BRICKS;
                    case 'B' -> Material.LIGHT_GRAY_CONCRETE;
                    default -> Material.END_STONE;
                });
                continue;
            }
            // clear the ledge in front of the face so his brow shows
            if (lz == 14 && ly == 6 && lx >= 2 && lx <= 8 && STONE.contains(m)) { put(b, Material.AIR); continue; }
            if (m == Material.LAVA && ly == 1 && lz <= 4) { put(b, Material.WATER); continue; }   // fonts of holy water by the door
            if (m == Material.SPAWNER) { angels(b); continue; }
            if (m == Material.STONE_BRICK_STAIRS || m == Material.STONE_BRICK_SLAB || m == Material.STONE_BRICK_WALL
                    || m == Material.COBBLESTONE_STAIRS || m == Material.COBBLESTONE_SLAB) {
                String s = b.getBlockData().getAsString().replace("stone_brick_stairs", "quartz_stairs").replace("cobblestone_stairs", "quartz_stairs")
                        .replace("stone_brick_slab", "quartz_slab").replace("cobblestone_slab", "quartz_slab").replace("stone_brick_wall", "diorite_wall");
                try { b.setBlockData(Bukkit.createBlockData(s), false); } catch (IllegalArgumentException ignored) { }
                continue;
            }
            if (!STONE.contains(m)) continue;
            boolean shell = lx == 0 || lx == 10 || lz == 0 || lz == 15 || ly == 0 || ly == 7;
            if (ly == 0) put(b, lx == 5 && lz <= 3 ? Material.GOLD_BLOCK : (lx == 4 || lx == 6) && lz <= 3 ? Material.SMOOTH_QUARTZ : Material.DEEPSLATE_TILES);
            else if (ly == 7) put(b, Material.PURPUR_BLOCK);
            else if (shell) {
                boolean column = (lx == 0 || lx == 10) ? lz % 2 == 0 : lx % 2 == 1;
                if (column) { BlockData d = Material.PURPUR_PILLAR.createBlockData(); if (d instanceof Orientable o) o.setAxis(org.bukkit.Axis.Y); b.setBlockData(d, false); }
                else put(b, Material.QUARTZ_BRICKS);
            }
            else put(b, ly == 6 ? Material.CHISELED_QUARTZ_BLOCK : Material.SMOOTH_QUARTZ);
        }
        // lanterns hanging on chains from the gilded ceiling
        for (int[] l : new int[][]{{2, 7}, {8, 7}, {2, 13}, {8, 13}}) {
            int[] chain = at(cx, cy, cz, fx, fz, lxX, lxZ, l[0], 6, l[1]), lamp = at(cx, cy, cz, fx, fz, lxX, lxZ, l[0], 5, l[1]);
            Block cb = w.getBlockAt(chain[0], chain[1], chain[2]), lb = w.getBlockAt(lamp[0], lamp[1], lamp[2]);
            if (!cb.getType().isAir() || !lb.getType().isAir()) continue;
            BlockData ch = Material.IRON_CHAIN.createBlockData(); if (ch instanceof Orientable o) o.setAxis(org.bukkit.Axis.Y);
            cb.setBlockData(ch, false);
            BlockData ln = Material.LANTERN.createBlockData(); if (ln instanceof Lantern lt) lt.setHanging(true);
            lb.setBlockData(ln, false);
        }
        // candles wherever there's a free spot on a solid surface near the altar steps and the corners
        for (int[] c : new int[][]{{2, 2, 4}, {8, 2, 4}, {1, 1, 14}, {9, 1, 14}, {3, 4, 8}, {7, 4, 8}, {3, 4, 12}, {7, 4, 12}, {1, 1, 6}, {9, 1, 6}}) {
            int[] p = at(cx, cy, cz, fx, fz, lxX, lxZ, c[0], c[1], c[2]);
            Block b = w.getBlockAt(p[0], p[1], p[2]);
            if (!b.getType().isAir() || !b.getRelative(0, -1, 0).getType().isSolid()) continue;
            BlockData d = Bukkit.createBlockData("minecraft:white_candle[candles=3,lit=true,waterlogged=false]");
            b.setBlockData(d, false);
        }
    }

    private static void put(Block b, Material m) { b.setBlockData(m.createBlockData(), false); }

    /** The silverfish spawner becomes a guardian: angels, a few at a time. */
    private void angels(Block b) {
        if (!plugin.getConfig().getBoolean("stronghold.angel-spawner", true)) return;
        if (!(b.getState() instanceof CreatureSpawner s)) return;
        s.setSpawnedType(EntityType.VEX);
        s.setSpawnCount(1);
        s.setMaxNearbyEntities(2);
        s.setMaxSpawnDelay(600);
        s.setMinSpawnDelay(300);
        s.setDelay(300);
        s.setRequiredPlayerRange(12);
        s.update(true, false);
    }

    /** Whoever walks in hears from God (once every ten minutes each). */
    private void greet() {
        if (!on() || rooms.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long last = greeted.get(p.getUniqueId());
            if (last != null && now - last < 600_000L) continue;
            String wn = p.getWorld().getName();
            for (String r : rooms) {
                String[] s = r.split(";");
                if (!s[0].equals(wn)) continue;
                double x = p.getX(), y = p.getY(), z = p.getZ();
                if (x < Integer.parseInt(s[1]) || x > Integer.parseInt(s[4]) + 1 || y < Integer.parseInt(s[2]) || y > Integer.parseInt(s[5]) + 1
                        || z < Integer.parseInt(s[3]) || z > Integer.parseInt(s[6]) + 1) continue;
                greeted.put(p.getUniqueId(), now);
                p.showTitle(Title.title(Component.text("THE GATE OF HEAVEN", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("He is watching.", NamedTextColor.YELLOW, TextDecoration.ITALIC),
                        Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(3), Duration.ofMillis(1200))));
                p.playSound(p.getLocation(), "minecraft:entity.ender_dragon.growl", 0.7f, 0.8f);
                String line = LINES[(int) (Math.random() * LINES.length)];
                Bukkit.getScheduler().runTaskLater(plugin, () -> p.sendMessage(
                        Component.text("God: ", NamedTextColor.GOLD, TextDecoration.BOLD).append(Component.text(line, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))), 50L);
                break;
            }
        }
    }
}
