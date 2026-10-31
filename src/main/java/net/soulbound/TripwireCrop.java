package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A custom crop drawn by the resource pack over tripwire states vanilla never shows.
 *
 * Every crop is a tripwire block that is disarmed AND powered (vanilla only sets "disarmed" for the instant shears
 * break a wire, so those states never reach a client) with no north/south connection. "attached" picks the crop
 * (false: the tumour, true: Cloud Cotton) and east/west carry the growth stage, 0-3. Every other tripwire state keeps
 * its vanilla string model, so normal string and tripwire traps are untouched.
 *
 * The plugin keeps each chunk's crops in the chunk's PDC (packed position + stage), grows a few every 5 seconds
 * (twice as fast in a Blood Moon, scaled by random_tick_speed), stops vanilla reshaping or pressing them, and handles
 * planting, the crop's own fertiliser and harvest. Subclasses supply the look, the soil, the items and the drops.
 */
abstract class TripwireCrop implements Listener {
    static final int RIPE = 3;
    private static final double GROW_CHANCE = 1.0 / 60;   // per 5-second pass, per stage: ~5 minutes a stage

    protected final Soulbound plugin;
    private final boolean attachedBit;
    private final NamespacedKey chunkKey;
    /** loaded crops: world uid + chunk key -> (packed pos -> stage) */
    private final Map<String, Map<Integer, Integer>> crops = new HashMap<>();
    private BukkitTask task;

    TripwireCrop(Soulbound plugin, boolean attachedBit, String chunkKey) {
        this.plugin = plugin;
        this.attachedBit = attachedBit;
        this.chunkKey = new NamespacedKey(plugin, chunkKey);
    }

    // ---------------------------------------------------------------- what each crop decides

    abstract ItemStack seed(int n);
    abstract boolean isSeed(ItemStack it);
    /** the ground it takes root in */
    abstract boolean soil(Material m);
    abstract String wrongSoilMessage();
    /** the item that pushes it a stage when used on it (rotten flesh, bone meal...) */
    abstract boolean isFertiliser(ItemStack it);
    abstract void fertilised(Block b);
    abstract void planted(Block b);
    /** drops for a ripe crop (an unripe one gives back one seed) */
    abstract void dropRipe(Block b, Location at);
    /** a ripe crop near a player, now and then: a heartbeat, a sparkle */
    abstract void ambient(Player p, Block ripe);
    /** chance per pass that a ripe crop spreads to bare soil next to it */
    double spreadChance() { return 0; }
    void spread(Block b) {}

    void start() {
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) loadChunk(c);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) saveChunk(c);
    }

    // ---------------------------------------------------------------- the block

    /** the tripwire state for a stage of this crop */
    BlockData state(int stage) {
        Tripwire t = (Tripwire) Material.TRIPWIRE.createBlockData();
        t.setDisarmed(true); t.setPowered(true); t.setAttached(attachedBit);
        t.setFace(BlockFace.NORTH, false); t.setFace(BlockFace.SOUTH, false);
        t.setFace(BlockFace.EAST, (stage & 1) != 0); t.setFace(BlockFace.WEST, (stage & 2) != 0);
        return t;
    }

    /** the stage this block shows, or -1 if it isn't this crop */
    int stageOf(BlockData d) {
        if (!(d instanceof Tripwire t) || !t.isDisarmed() || !t.isPowered() || t.isAttached() != attachedBit || t.hasFace(BlockFace.NORTH) || t.hasFace(BlockFace.SOUTH)) return -1;
        return (t.hasFace(BlockFace.EAST) ? 1 : 0) + (t.hasFace(BlockFace.WEST) ? 2 : 0);
    }

    boolean isCrop(Block b) { return b.getType() == Material.TRIPWIRE && stageOf(b.getBlockData()) >= 0; }

    private static String ck(Chunk c) { return c.getWorld().getUID() + ":" + c.getChunkKey(); }
    private static int pack(Block b) { return ((b.getY() + 2048) << 8) | ((b.getZ() & 15) << 4) | (b.getX() & 15); }
    private static Block unpack(Chunk c, int p) { return c.getBlock(p & 15, (p >> 8) - 2048, (p >> 4) & 15); }

    private Map<Integer, Integer> of(Chunk c) { return crops.computeIfAbsent(ck(c), k -> new HashMap<>()); }

    private boolean tracked(Block b) {
        Map<Integer, Integer> m = crops.get(ck(b.getChunk()));
        return m != null && m.containsKey(pack(b));
    }

    protected void set(Block b, int stage) {
        b.setBlockData(state(stage), false);
        of(b.getChunk()).put(pack(b), stage);
        saveChunk(b.getChunk());
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
        for (World w : Bukkit.getWorlds()) {
            // like vanilla crops, growth follows the world's random tick speed (3 is normal; 0 stops it)
            Integer rts = w.getGameRuleValue(org.bukkit.GameRule.RANDOM_TICK_SPEED);
            double scale = Math.max(0, rts == null ? 3 : rts) / 3.0, worldGrow = Math.min(1.0, grow * scale);
            for (Chunk c : w.getLoadedChunks()) {
                Map<Integer, Integer> m = crops.get(ck(c));
                if (m == null || m.isEmpty()) continue;
                boolean dirty = false;
                for (var it = m.entrySet().iterator(); it.hasNext(); ) {
                    var e = it.next();
                    Block b = unpack(c, e.getKey());
                    if (b.getType() != Material.TRIPWIRE) { it.remove(); dirty = true; continue; }   // gone some way we didn't see
                    int stage = e.getValue();
                    if (stageOf(b.getBlockData()) != stage) b.setBlockData(state(stage), false);        // something reshaped it: put it back
                    if (!soil(b.getRelative(BlockFace.DOWN).getType())) continue;                       // no soil, no growth
                    if (stage < RIPE) {
                        if (r.nextDouble() < worldGrow) { e.setValue(stage + 1); b.setBlockData(state(stage + 1), false); dirty = true; }
                    } else if (spreadChance() > 0 && r.nextDouble() < spreadChance() * scale) spread.add(b);
                }
                if (dirty) saveChunk(c);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (r.nextInt(3) != 0) continue;
            Block near = nearestRipe(p);
            if (near != null) ambient(p, near);
        }
        for (Block b : spread) spread(b);
    }

    private Block nearestRipe(Player p) {
        Chunk pc = p.getLocation().getChunk();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!p.getWorld().isChunkLoaded(pc.getX() + dx, pc.getZ() + dz)) continue;
            Map<Integer, Integer> m = crops.get(ck(p.getWorld().getChunkAt(pc.getX() + dx, pc.getZ() + dz)));
            if (m == null) continue;
            for (var e : m.entrySet()) if (e.getValue() >= RIPE) {
                Block b = unpack(p.getWorld().getChunkAt(pc.getX() + dx, pc.getZ() + dz), e.getKey());
                if (b.getLocation().distanceSquared(p.getLocation()) < 144) return b;
            }
        }
        return null;
    }

    /** push a crop on by up to n stages (a Growth potion); true if it grew */
    boolean grow(Block b, int n) {
        if (!isCrop(b)) return false;
        int st = stageOf(b.getBlockData());
        if (st >= RIPE) return false;
        set(b, Math.min(RIPE, st + n));
        return true;
    }

    // ---------------------------------------------------------------- planting, and the fertiliser

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
        if (st >= 0 && b.getType() == Material.TRIPWIRE && isFertiliser(hand)) {
            e.setCancelled(true);
            if (!tracked(b)) of(b.getChunk()).put(pack(b), st);
            if (st < RIPE) {
                set(b, st + 1);
                if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
                fertilised(b);
            }
            return;
        }
        if (!isSeed(hand) || e.getBlockFace() != BlockFace.UP) return;
        e.setCancelled(true);
        Block above = b.getRelative(BlockFace.UP);
        if (!soil(b.getType())) { p.sendActionBar(Component.text(wrongSoilMessage(), NamedTextColor.RED)); return; }
        if (!above.getType().isAir()) return;
        set(above, 0);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        planted(above);
    }

    /** mobs walking through: same as a player stepping in it */
    @EventHandler(ignoreCancelled = true)
    public void onEntityStep(EntityInteractEvent e) {
        if (stageOf(e.getBlock().getBlockData()) >= 0) e.setCancelled(true);
    }

    /** vanilla would reshape it to connect to string next door; keep it a crop */
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (isCrop(e.getBlock())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- harvest

    private void harvest(Block b, boolean drop) {
        int st = stageOf(b.getBlockData());
        if (st < 0) return;
        Integer saved = crops.getOrDefault(ck(b.getChunk()), Map.of()).get(pack(b));
        if (saved != null) st = saved;
        forget(b); saveChunk(b.getChunk());
        if (!drop) return;
        Location at = b.getLocation().add(.5, .3, .5);
        if (st >= RIPE) dropRipe(b, at);
        else b.getWorld().dropItemNaturally(at, seed(1));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isCrop(b)) return;
        e.setDropItems(false);
        harvest(b, e.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {   // water washing it away, like crops
        Block to = e.getToBlock();
        if (isCrop(to)) { harvest(to, true); to.setType(Material.AIR, false); }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) { for (Block b : e.getBlocks()) if (isCrop(b)) { harvest(b, true); b.setType(Material.AIR, false); } }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(BlockPistonRetractEvent e) { for (Block b : e.getBlocks()) if (isCrop(b)) { harvest(b, true); b.setType(Material.AIR, false); } }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { exploded(e.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { exploded(e.blockList()); }

    /** an explosion would also drop string: take the crops out of the blast list and break them ourselves */
    private void exploded(List<Block> list) {
        for (var it = list.iterator(); it.hasNext(); ) {
            Block b = it.next();
            if (isCrop(b)) { harvest(b, true); b.setType(Material.AIR, false); it.remove(); }
        }
    }

    static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }
}
