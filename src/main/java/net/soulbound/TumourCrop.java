package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The tumour crop: a lumpy 3D mass of flesh that grows out of soul soil or soul sand, like nether wart.
 *
 * It is a tripwire block in one of four states vanilla never shows (disarmed AND powered, not attached, no north/south
 * connection); the resource pack draws those four as the mass at each growth stage (east/west encode the stage).
 * Every other tripwire state keeps its vanilla string model, so normal string and tripwire traps are untouched.
 *
 * The plugin keeps each chunk's crops in the chunk's PDC (packed position + stage), grows a few every 5 seconds
 * (twice as fast in a Blood Moon), stops vanilla reshaping or pressing them, and handles harvest: a ripe mass drops
 * 2-4 Tumours and 1-2 Tumour Seeds, an unripe one its seed. Rotten flesh on a crop pushes it a stage. Left ripe, a
 * mass sometimes spreads to bare soul soil next to it.
 */
final class TumourCrop implements Listener {
    static final int RIPE = 3;
    private static final double GROW_CHANCE = 1.0 / 60;      // per 5-second pass, per stage: ~5 minutes a stage
    private static final double SPREAD_CHANCE = 1.0 / 900;   // per pass while ripe: about once an hour and a quarter

    private final Soulbound plugin;
    private final NamespacedKey seedKey, tumourKey, chunkKey;
    /** loaded crops: world uid + chunk key -> (packed pos -> stage) */
    private final Map<String, Map<Integer, Integer>> crops = new HashMap<>();
    private BukkitTask task;

    TumourCrop(Soulbound plugin) {
        this.plugin = plugin;
        seedKey = new NamespacedKey(plugin, "tumour_seed");
        tumourKey = new NamespacedKey(plugin, "tumour");
        chunkKey = new NamespacedKey(plugin, "tumour_crops");
    }

    void start() {
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) loadChunk(c);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) saveChunk(c);
    }

    // ---------------------------------------------------------------- items

    ItemStack seed(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Tumour Seed", TextColor.color(0xD9707E), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Plant on soul soil or soul sand.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Rotten flesh makes it grow.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(new NamespacedKey("soulbound", "tumour_seed"));
        m.getPersistentDataContainer().set(seedKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack tumour(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Tumour", TextColor.color(0xD9707E), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Still warm.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)));
        m.setItemModel(new NamespacedKey("soulbound", "tumour"));
        m.getPersistentDataContainer().set(tumourKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isSeed(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(seedKey, PersistentDataType.BYTE); }
    boolean isTumour(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(tumourKey, PersistentDataType.BYTE); }

    String adminGive(Player to, int seeds) {
        give(to, seed(seeds)); give(to, tumour(4));
        return "Gave " + to.getName() + " " + seeds + " Tumour Seeds and 4 Tumours.";
    }

    // ---------------------------------------------------------------- the block

    /** the tripwire state for a stage: disarmed + powered (never seen in vanilla), east/west carry the stage */
    static BlockData state(int stage) {
        Tripwire t = (Tripwire) Material.TRIPWIRE.createBlockData();
        t.setDisarmed(true); t.setPowered(true); t.setAttached(false);
        t.setFace(BlockFace.NORTH, false); t.setFace(BlockFace.SOUTH, false);
        t.setFace(BlockFace.EAST, (stage & 1) != 0); t.setFace(BlockFace.WEST, (stage & 2) != 0);
        return t;
    }

    /** the stage this block shows, or -1 if it isn't a tumour */
    static int stageOf(BlockData d) {
        if (!(d instanceof Tripwire t) || !t.isDisarmed() || !t.isPowered() || t.isAttached() || t.hasFace(BlockFace.NORTH) || t.hasFace(BlockFace.SOUTH)) return -1;
        return (t.hasFace(BlockFace.EAST) ? 1 : 0) + (t.hasFace(BlockFace.WEST) ? 2 : 0);
    }

    private static boolean soil(Material m) { return m == Material.SOUL_SOIL || m == Material.SOUL_SAND; }

    private static String ck(Chunk c) { return c.getWorld().getUID() + ":" + c.getChunkKey(); }
    private static int pack(Block b) { return ((b.getY() + 2048) << 8) | ((b.getZ() & 15) << 4) | (b.getX() & 15); }
    private static Block unpack(Chunk c, int p) { return c.getBlock(p & 15, (p >> 8) - 2048, (p >> 4) & 15); }

    private Map<Integer, Integer> of(Chunk c) { return crops.computeIfAbsent(ck(c), k -> new HashMap<>()); }

    private boolean tracked(Block b) {
        Map<Integer, Integer> m = crops.get(ck(b.getChunk()));
        return m != null && m.containsKey(pack(b));
    }

    private void set(Block b, int stage) {
        b.setBlockData(state(stage), false);
        of(b.getChunk()).put(pack(b), stage);
    }

    private void forget(Block b) {
        Map<Integer, Integer> m = crops.get(ck(b.getChunk()));
        if (m != null) m.remove(pack(b));
    }

    // ---------------------------------------------------------------- saving, per chunk

    private void loadChunk(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(chunkKey, PersistentDataType.INTEGER_ARRAY);
        if (a == null || a.length == 0) return;
        Map<Integer, Integer> m = of(c);
        for (int i = 0; i + 1 < a.length; i += 2) m.put(a[i], a[i + 1]);
    }

    private void saveChunk(Chunk c) {
        Map<Integer, Integer> m = crops.get(ck(c));
        if (m == null || m.isEmpty()) { c.getPersistentDataContainer().remove(chunkKey); return; }
        int[] a = new int[m.size() * 2]; int i = 0;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) { a[i++] = e.getKey(); a[i++] = e.getValue(); }
        c.getPersistentDataContainer().set(chunkKey, PersistentDataType.INTEGER_ARRAY, a);
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent e) { loadChunk(e.getChunk()); }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent e) { saveChunk(e.getChunk()); crops.remove(ck(e.getChunk())); }

    // ---------------------------------------------------------------- growing

    private void tick() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean moon = plugin.boss() != null && plugin.boss().bloodMoon();
        double grow = GROW_CHANCE * (moon ? 2 : 1);
        List<Block> spread = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) {
            Map<Integer, Integer> m = crops.get(ck(c));
            if (m == null || m.isEmpty()) continue;
            boolean dirty = false;
            for (var it = m.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Block b = unpack(c, e.getKey());
                int shown = stageOf(b.getBlockData()), stage = e.getValue();
                if (b.getType() != Material.TRIPWIRE) { it.remove(); dirty = true; continue; }   // gone some way we didn't see
                if (shown != stage) { b.setBlockData(state(stage), false); }                       // something reshaped it: put it back
                if (!soil(b.getRelative(BlockFace.DOWN).getType())) continue;                       // no soil, no growth
                if (stage < RIPE) {
                    if (r.nextDouble() < grow) { e.setValue(stage + 1); b.setBlockData(state(stage + 1), false); dirty = true; }
                } else if (r.nextDouble() < SPREAD_CHANCE) spread.add(b);
            }
            if (dirty) saveChunk(c);
        }
        for (Block b : spread) {   // a ripe mass reaches into bare soul soil beside it
            BlockFace f = new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}[r.nextInt(4)];
            Block ground = b.getRelative(BlockFace.DOWN).getRelative(f), above = ground.getRelative(BlockFace.UP);
            if (soil(ground.getType()) && above.getType().isAir()) {
                set(above, 0); saveChunk(above.getChunk());
                above.getWorld().spawnParticle(Particle.BLOCK, above.getLocation().add(.5, .2, .5), 10, .25, .1, .25, Material.NETHER_WART_BLOCK.createBlockData());
            }
        }
    }

    // ---------------------------------------------------------------- planting, and rotten flesh

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() == Action.PHYSICAL) {   // stepping on it: tripwire would flip to "pressed" - not this one
            if (stageOf(e.getClickedBlock().getBlockData()) >= 0) e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;   // a claim (or another plugin) said no
        Player p = e.getPlayer(); ItemStack hand = p.getInventory().getItemInMainHand();
        Block b = e.getClickedBlock();
        int st = stageOf(b.getBlockData());
        if (st >= 0 && hand.getType() == Material.ROTTEN_FLESH) {
            e.setCancelled(true);
            if (!tracked(b)) of(b.getChunk()).put(pack(b), st);
            if (st < RIPE) {
                set(b, st + 1); saveChunk(b.getChunk());
                if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
                b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(.5, .3, .5), 14, .3, .2, .3, Material.NETHER_WART_BLOCK.createBlockData());
                b.getWorld().playSound(b.getLocation(), Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.6f);
            }
            return;
        }
        if (!isSeed(hand) || e.getBlockFace() != BlockFace.UP) return;
        e.setCancelled(true);
        Block above = b.getRelative(BlockFace.UP);
        if (!soil(b.getType())) { p.sendActionBar(Component.text("Tumours only take root in soul soil or soul sand.", NamedTextColor.RED)); return; }
        if (!above.getType().isAir()) return;
        set(above, 0); saveChunk(above.getChunk());
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        above.getWorld().playSound(above.getLocation(), Sound.BLOCK_HONEY_BLOCK_PLACE, 0.8f, 0.6f);
    }

    /** mobs walking through: same as a player stepping in it */
    @EventHandler(ignoreCancelled = true)
    public void onEntityStep(EntityInteractEvent e) {
        if (stageOf(e.getBlock().getBlockData()) >= 0) e.setCancelled(true);
    }

    /** vanilla would reshape it to connect to string next door; keep it a tumour */
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.TRIPWIRE && stageOf(b.getBlockData()) >= 0) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- harvest

    private void harvest(Block b, boolean drop) {
        int st = stageOf(b.getBlockData());
        if (st < 0) return;
        Integer saved = crops.getOrDefault(ck(b.getChunk()), Map.of()).get(pack(b));
        if (saved != null) st = saved;
        forget(b); saveChunk(b.getChunk());
        if (!drop) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = b.getLocation().add(.5, .3, .5);
        if (st >= RIPE) {
            b.getWorld().dropItemNaturally(at, tumour(2 + r.nextInt(3)));
            b.getWorld().dropItemNaturally(at, seed(1 + r.nextInt(2)));
            b.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 1f, 0.5f);
            b.getWorld().spawnParticle(Particle.BLOCK, at, 20, .3, .3, .3, Material.NETHER_WART_BLOCK.createBlockData());
        } else b.getWorld().dropItemNaturally(at, seed(1));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.TRIPWIRE || stageOf(b.getBlockData()) < 0) return;
        e.setDropItems(false);
        harvest(b, e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {   // water washing it away, like crops
        Block to = e.getToBlock();
        if (to.getType() == Material.TRIPWIRE && stageOf(to.getBlockData()) >= 0) { harvest(to, true); to.setType(Material.AIR, false); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (b.getType() == Material.TRIPWIRE && stageOf(b.getBlockData()) >= 0) { harvest(b, true); b.setType(Material.AIR, false); } }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (b.getType() == Material.TRIPWIRE && stageOf(b.getBlockData()) >= 0) { harvest(b, true); b.setType(Material.AIR, false); } }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { exploded(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { exploded(e.blockList()); }

    /** an explosion would also drop string: take the tumours out of the blast list and break them ourselves */
    private void exploded(List<Block> list) {
        for (var it = list.iterator(); it.hasNext(); ) {
            Block b = it.next();
            if (b.getType() == Material.TRIPWIRE && stageOf(b.getBlockData()) >= 0) { harvest(b, true); b.setType(Material.AIR, false); it.remove(); }
        }
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }
}
