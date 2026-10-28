package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Orientable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.util.BoundingBox;

import java.time.Duration;

/**
 * Portals and Moloch's realm, which takes the Nether's place.
 *
 * Any portal in the Overworld leads to one fixed arrival point at the start of the Sacred Way, facing the temple
 * (no 1:8 travel, and no portals generated in the realm). The only way back is the Exit Gate behind the arrival
 * point, which returns each player to the Overworld portal they came through. Portals can't be lit in the realm,
 * mobs and items can't use portals at all, and a captor takes their captive through with them.
 */
final class PortalManager implements Listener {
    private final Soulbound plugin;
    private final Store store;
    private final Zones zones;
    private final CaptureManager captures;

    private boolean enabled;
    private double arrX, arrY, arrZ;
    private float arrYaw;
    private int gateX, gateZ;
    private BoundingBox gateBox;
    /** The Exit Gates on the east, south and west sides (the north one is gateBox). */
    private final java.util.List<BoundingBox> sideGates = new java.util.ArrayList<>();

    PortalManager(Soulbound plugin, Store store, Zones zones, CaptureManager captures) {
        this.plugin = plugin; this.store = store; this.zones = zones; this.captures = captures;
        loadSettings();
    }

    void loadSettings() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("portals.enabled", true);
        arrX = c.getDouble("portals.arrival.x", 0.5);
        arrY = c.getDouble("portals.arrival.y", 61);
        arrZ = c.getDouble("portals.arrival.z", -113.5);
        arrYaw = (float) c.getDouble("portals.arrival.yaw", 0);
        gateX = c.getInt("portals.gate.x", 0);
        gateZ = c.getInt("portals.gate.z", -120);
        sideGates.clear();
        for (TempleWorks.Side sd : TempleWorks.Side.values()) {
            String k = "portals.gates." + sd.name().toLowerCase(java.util.Locale.ROOT);
            if (store.data.isInt(k + ".floor")) sideGates.add(box(store.data.getInt(k + ".x"), store.data.getInt(k + ".floor"), store.data.getInt(k + ".z")));
        }
        if (store.data.isInt("portals.gate-floor")) {
            int f = store.data.getInt("portals.gate-floor");
            gateBox = new BoundingBox(store.data.getInt("portals.gate-x", gateX) - 3, f - 1, store.data.getInt("portals.gate-z", gateZ) - 1,
                    store.data.getInt("portals.gate-x", gateX) + 4, f + 9, store.data.getInt("portals.gate-z", gateZ) + 2);
        }
    }

    /** On start: raise the Exit Gate the first time the realm is there. */
    void start() {
        if (!enabled || zones.world() == null) return;
        // a realm we haven't worked on yet (first start, or the dimension was reset): the Exit Gate, the stairways,
        // the causeways and the side gates all go up again
        String uid = zones.world().getUID().toString();
        boolean fresh = !uid.equals(store.data.getString("temple.realm-uid"));
        if (fresh || gateBox == null) plugin.getLogger().info(buildGate(gateX, gateZ));
        if (fresh || !store.data.getBoolean("temple.works-v1", false)) plugin.getLogger().info(templeWorks());
        store.set("temple.realm-uid", uid);
        store.save();
    }

    /**
     * Rebuild the pyramid's four stairways as real stairs, and give the east, south and west sides a causeway out to
     * their own arrival point and Exit Gate. Done once; /moloch temple does it again.
     */
    String templeWorks() {
        World w = zones.world();
        if (w == null) return "Moloch's realm isn't loaded.";
        TempleWorks t = new TempleWorks(w);
        for (TempleWorks.Side sd : TempleWorks.Side.values()) {
            t.stairs(sd);
            if (sd == TempleWorks.Side.NORTH) continue;   // the Sacred Way already leads to the north stairs
            t.causeway(sd, TempleWorks.FOOT + 3, 124);
            int[] g = TempleWorks.gate(sd);
            buildSideGate(sd, g[0], g[1]);
        }
        store.set("temple.works-v1", true);
        store.save();
        return "Moloch's pyramid: four climbable stairways, and arrivals and Exit Gates on every side.";
    }

    private static BoundingBox box(int x, int floor, int z) { return new BoundingBox(x - 4, floor - 1, z - 4, x + 5, floor + 9, z + 5); }

    /** An Exit Gate facing out along a side: the same gate as the north one, turned to face the pyramid. */
    private void buildSideGate(TempleWorks.Side sd, int gx, int gz) {
        World w = zones.world();
        int floor = TempleWorks.PLAZA_Y;
        boolean alongX = sd == TempleWorks.Side.NORTH || sd == TempleWorks.Side.SOUTH;   // the portal plane runs across the way
        java.util.function.BiFunction<Integer, Integer, int[]> P = (u, v) -> alongX ? new int[]{gx + u, gz + v} : new int[]{gx + v, gz + u};
        for (int u = -3; u <= 3; u++) for (int v = -2; v <= 2; v++) {
            int[] q = P.apply(u, v);
            for (int y = floor + 1; y <= floor + 8; y++) set(w, q[0], y, q[1], Material.AIR.createBlockData());
            set(w, q[0], floor, q[1], (Math.abs(u) == 3 || Math.abs(v) == 2 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS).createBlockData());
            for (int y = floor - 1; y >= floor - 3; y--) if (!w.getBlockAt(q[0], y, q[1]).getType().isSolid()) set(w, q[0], y, q[1], Material.POLISHED_BLACKSTONE.createBlockData());
        }
        for (int u = -2; u <= 2; u++) for (int y = floor; y <= floor + 5; y++) {
            int[] q = P.apply(u, 0);
            boolean edge = Math.abs(u) == 2 || y == floor || y == floor + 5;
            if (edge) set(w, q[0], y, q[1], Material.OBSIDIAN.createBlockData());
            else {
                BlockData portal = Material.NETHER_PORTAL.createBlockData();
                if (portal instanceof Orientable o) o.setAxis(alongX ? Axis.X : Axis.Z);
                set(w, q[0], y, q[1], portal);
            }
        }
        for (int side : new int[]{-3, 3}) {
            int[] q = P.apply(side, 0);
            for (int y = floor + 1; y <= floor + 5; y++) set(w, q[0], y, q[1], Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
            set(w, q[0], floor + 6, q[1], Material.GOLD_BLOCK.createBlockData());
            BlockData fire = Material.SOUL_CAMPFIRE.createBlockData();
            if (fire instanceof Lightable l) l.setLit(true);
            set(w, q[0], floor + 7, q[1], fire);
        }
        for (int u = -2; u <= 2; u++) { int[] q = P.apply(u, 0); set(w, q[0], floor + 6, q[1], (u == 0 ? Material.GILDED_BLACKSTONE : Material.CHISELED_POLISHED_BLACKSTONE).createBlockData()); }
        int[] top = P.apply(0, 0);
        set(w, top[0], floor + 7, top[1], Material.GOLD_BLOCK.createBlockData());
        String k = "portals.gates." + sd.name().toLowerCase(java.util.Locale.ROOT);
        store.set(k + ".floor", floor); store.set(k + ".x", gx); store.set(k + ".z", gz); store.save();
        sideGates.add(box(gx, floor, gz));
    }

    // ---------------------------------------------------------------- the arrival point

    /** Where everyone arrives: the start of the Sacred Way, facing the temple, lifted onto solid ground if needed. */
    Location arrival() { return arrival(TempleWorks.Side.NORTH); }

    /** The arrival point on one side of the pyramid: the configured (north) one, turned a quarter at a time. */
    Location arrival(TempleWorks.Side side) {
        World w = zones.world();
        if (w == null) return null;
        if (side != TempleWorks.Side.NORTH && !store.data.isInt("portals.gates." + side.name().toLowerCase(java.util.Locale.ROOT) + ".floor")) side = TempleWorks.Side.NORTH;
        int k = side.ordinal();   // quarter turns clockwise from north
        double x = arrX, z = arrZ;
        for (int i = 0; i < k; i++) { double nx = -z, nz = x; x = nx; z = nz; }
        Location l = new Location(w, x, arrY, z, arrYaw + 90f * k, 0);
        return safe(l);
    }

    /** Which side of the pyramid an Overworld portal leads to: the way it lies from the world spawn. */
    private static TempleWorks.Side sideFor(Location from) {
        Location sp = from.getWorld().getSpawnLocation();
        double dx = from.getX() - sp.getX(), dz = from.getZ() - sp.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? TempleWorks.Side.EAST : TempleWorks.Side.WEST;
        return dz > 0 ? TempleWorks.Side.SOUTH : TempleWorks.Side.NORTH;
    }

    /** The nearest spot at or above/below this one with solid ground and two blocks of air. */
    private static Location safe(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX(), z = l.getBlockZ(), y0 = l.getBlockY();
        for (int dy = 0; dy <= 24; dy++) {
            for (int s : new int[]{dy, -dy}) {
                int y = y0 + s;
                if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) continue;
                Block feet = w.getBlockAt(x, y, z);
                if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.isLiquid()
                        && feet.getRelative(0, -1, 0).getType().isSolid()) {
                    Location out = l.clone();
                    out.setY(y);
                    return out;
                }
                if (dy == 0) break;
            }
        }
        return l;
    }

    // ---------------------------------------------------------------- travelling

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!enabled || e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        Player p = e.getPlayer();
        World from = e.getFrom().getWorld();
        if (from == null) return;
        boolean inRealm = zones.inRealm(e.getFrom());
        if (!inRealm && from.getEnvironment() != World.Environment.NORMAL) return;   // the vanilla Nether or End: leave it be
        e.setCancelled(true);
        if (captures.isCaptive(p)) {   // a captive can't slip away through a portal
            p.sendActionBar(Component.text("You're bound. The portal won't take you.", NamedTextColor.RED));
            return;
        }
        Location fromSpot = e.getFrom().clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (inRealm) leaveRealm(p);
            else enterRealm(p, fromSpot);
        });
    }

    private void enterRealm(Player p, Location fromSpot) {
        Location to = arrival(sideFor(fromSpot));
        if (to == null) {
            p.sendActionBar(Component.text("The portal is cold. Moloch's realm isn't loaded.", NamedTextColor.GRAY));
            return;
        }
        store.set("portals.return." + p.getUniqueId(), fromSpot);
        store.save();
        if (!captures.carryThrough(p, to)) return;
        p.setPortalCooldown(200);
        p.playSound(p.getLocation(), "minecraft:block.portal.travel", 0.4f, 0.8f);
        p.playSound(p.getLocation(), "moloch:drums", 0.6f, 1f);
        boolean first = !store.data.getBoolean("portals.visited." + p.getUniqueId(), false);
        if (first) { store.set("portals.visited." + p.getUniqueId(), true); store.save(); }
        if (first) {
            // only the very first visit gets a title, and a small one: subtitle-sized text, not the giant banner
            p.showTitle(Title.title(Component.empty(),
                    Component.text("The Realm of Moloch", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2500), Duration.ofMillis(800))));
            p.sendMessage(Component.text("Follow the Sacred Way to his temple. He is always hungry.", NamedTextColor.GOLD));
            p.sendMessage(Component.text("The Exit Gate behind you is the only way back out.", NamedTextColor.GRAY));
        } else {
            p.sendActionBar(Component.text("The Realm of Moloch", NamedTextColor.DARK_RED));
        }
    }

    private void leaveRealm(Player p) {
        Location back = store.data.getLocation("portals.return." + p.getUniqueId());
        if (back == null || back.getWorld() == null) {
            back = p.getRespawnLocation();
            if (back == null) back = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        if (!captures.carryThrough(p, back)) return;
        p.setPortalCooldown(200);   // you land in your own portal: this stops it sending you straight back
        p.playSound(p.getLocation(), "minecraft:block.portal.travel", 0.4f, 1.2f);
        p.sendActionBar(Component.text("You leave the realm of Moloch.", NamedTextColor.GRAY));
    }

    /** Mobs, items and minecarts never use portals: nothing is farmed or smuggled through. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!enabled || e.getPortalType() != PortalType.NETHER) return;
        World from = e.getFrom().getWorld();
        if (from != null && (zones.inRealm(e.getFrom()) || from.getEnvironment() == World.Environment.NORMAL)) e.setCancelled(true);
    }

    /** No new portals in the realm: the Exit Gate is the only way out. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent e) {
        if (!enabled || !zones.inRealm(new Location(e.getWorld(), 0, 0, 0))) return;
        e.setCancelled(true);
        if (e.getEntity() instanceof Player p) p.sendActionBar(Component.text("Moloch's realm won't let you open a way out. Use the Exit Gate.", NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- the Exit Gate

    /**
     * Build the Exit Gate: an obsidian portal (3 wide, 4 tall) framed by blackstone pillars with gold caps and
     * soul fires, a gilded lintel, and a paved base, standing across the Sacred Way behind the arrival point.
     */
    String buildGate(int gx, int gz) {
        World w = zones.world();
        if (w == null) return "Moloch's realm isn't loaded, so the Exit Gate can't be built yet.";
        int floor = groundAt(w, gx, gz);
        // clear the space and pave the base
        for (int x = gx - 3; x <= gx + 3; x++) for (int z = gz - 2; z <= gz + 2; z++) {
            for (int y = floor + 1; y <= floor + 8; y++) set(w, x, y, z, Material.AIR.createBlockData());
            set(w, x, floor, z, (x == gx - 3 || x == gx + 3 || z == gz - 2 || z == gz + 2 ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS).createBlockData());
            for (int y = floor - 1; y >= floor - 3; y--) if (!w.getBlockAt(x, y, z).getType().isSolid()) set(w, x, y, z, Material.POLISHED_BLACKSTONE.createBlockData());
        }
        // the obsidian frame and the portal itself
        for (int x = gx - 2; x <= gx + 2; x++) for (int y = floor; y <= floor + 5; y++) {
            boolean edge = x == gx - 2 || x == gx + 2 || y == floor || y == floor + 5;
            if (edge) set(w, x, y, gz, Material.OBSIDIAN.createBlockData());
            else {
                BlockData portal = Material.NETHER_PORTAL.createBlockData();
                if (portal instanceof Orientable o) o.setAxis(Axis.X);
                set(w, x, y, gz, portal);
            }
        }
        // pillars with gold caps and soul fires either side
        for (int side : new int[]{-3, 3}) {
            for (int y = floor + 1; y <= floor + 5; y++) set(w, gx + side, y, gz, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
            set(w, gx + side, floor + 6, gz, Material.GOLD_BLOCK.createBlockData());
            BlockData fire = Material.SOUL_CAMPFIRE.createBlockData();
            if (fire instanceof Lightable l) l.setLit(true);
            set(w, gx + side, floor + 7, gz, fire);
        }
        // the lintel
        for (int x = gx - 2; x <= gx + 2; x++) set(w, x, floor + 6, gz, (x == gx ? Material.GILDED_BLACKSTONE : Material.CHISELED_POLISHED_BLACKSTONE).createBlockData());
        set(w, gx, floor + 7, gz, Material.GOLD_BLOCK.createBlockData());

        store.set("portals.gate-floor", floor);
        store.set("portals.gate-x", gx);
        store.set("portals.gate-z", gz);
        store.save();
        gateBox = new BoundingBox(gx - 3, floor - 1, gz - 1, gx + 4, floor + 9, gz + 2);
        return "The Exit Gate stands at " + gx + " " + (floor + 1) + " " + gz + " in Moloch's realm.";
    }

    private static int groundAt(World w, int x, int z) {
        for (int y = Math.min(w.getMaxHeight() - 1, 140); y > w.getMinHeight(); y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && !b.isLiquid()) return y;
        }
        return 60;
    }

    private static void set(World w, int x, int y, int z, BlockData d) { w.getBlockAt(x, y, z).setBlockData(d, false); }

    private boolean inGate(Block b) {
        if (!zones.inRealm(b.getLocation())) return false;
        double x = b.getX() + 0.5, y = b.getY() + 0.5, z = b.getZ() + 0.5;
        if (gateBox != null && gateBox.contains(x, y, z)) return true;
        for (BoundingBox g : sideGates) if (g.contains(x, y, z)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (inGate(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (inGate(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::inGate); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::inGate); }

    // ---------------------------------------------------------------- admin

    /** Move the arrival point to where an admin is standing (and facing), and save it. */
    void setArrival(Location l) {
        FileConfiguration c = plugin.getConfig();
        c.set("portals.arrival.x", l.getX());
        c.set("portals.arrival.y", l.getY());
        c.set("portals.arrival.z", l.getZ());
        c.set("portals.arrival.yaw", (double) l.getYaw());
        plugin.saveConfig();
        loadSettings();
    }
}
