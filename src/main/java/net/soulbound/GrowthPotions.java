package net.soulbound;

import io.papermc.paper.potion.PotionMix;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Moloch's brewing, in a normal brewing stand. The Tumour is the base, the way nether wart is in vanilla.
 *
 *   Water Bottle + Tumour            = Fleshy Potion (no effect, like an Awkward Potion)
 *   Fleshy Potion + Bone Meal        = Potion of Growth
 *   + Glowstone                      = Growth II
 *   + Tumour Seed                    = longer (stands in for redstone: the server has no natural redstone)
 *   + Cloud Cotton                   = Potion of Shrinking (Heaven's crop: light as air)
 *   + Gunpowder / + Dragon's Breath  = splash / lingering
 *
 * Growth: bigger (+25% / +50%), a little more reach, knockback resistance, and Slowness I for as long as it lasts.
 * Shrinking: smaller (-25% / -40%) and Weakness I. Splash and lingering Growth also push crops (tumours included) and
 * saplings on, and turn young animals into adults. Two in a minute makes you sick and a tumour tears loose. In a Blood
 * Moon they last twice as long. Milk or death ends them.
 *
 * The potions carry their side effect (Slowness/Weakness) as a real potion effect, so vanilla handles the tooltip,
 * the colour, splash intensity and lingering clouds; the plugin adds the size change on top and times it.
 */
final class GrowthPotions implements Listener {
    enum Kind { FLESHY, GROWTH, SHRINK }

    private static final Material[] FORMS = {Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION};
    private static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.SWEET_BERRY_BUSH, Material.MELON_STEM, Material.PUMPKIN_STEM, Material.TORCHFLOWER_CROP);
    private static final Color FLESHY = Color.fromRGB(0xC98A80), GROWTH = Color.fromRGB(0xD9506A), SHRINK = Color.fromRGB(0x7E6E9C);

    private final Soulbound plugin;
    private final NamespacedKey kindKey, levelKey, longKey, untilKey, stateKey, lastKey;
    private final NamespacedKey scaleKey, reachEKey, reachBKey, kbKey;
    private final Set<UUID> tracked = new HashSet<>();
    /** lingering clouds of ours: cloud -> {kind, level, long} */
    private final Map<UUID, int[]> clouds = new HashMap<>();
    private final List<NamespacedKey> mixes = new ArrayList<>();
    private BukkitTask task;

    GrowthPotions(Soulbound plugin) {
        this.plugin = plugin;
        kindKey = new NamespacedKey(plugin, "moloch_potion");
        levelKey = new NamespacedKey(plugin, "moloch_potion_level");
        longKey = new NamespacedKey(plugin, "moloch_potion_long");
        untilKey = new NamespacedKey(plugin, "growth_until");
        stateKey = new NamespacedKey(plugin, "growth_state");
        lastKey = new NamespacedKey(plugin, "growth_last_drink");
        scaleKey = new NamespacedKey(plugin, "growth_scale");
        reachEKey = new NamespacedKey(plugin, "growth_reach_entity");
        reachBKey = new NamespacedKey(plugin, "growth_reach_block");
        kbKey = new NamespacedKey(plugin, "growth_knockback");
    }

    void start() {
        registerMixes();
        for (Player p : Bukkit.getOnlinePlayers()) check(p);
        for (World w : Bukkit.getWorlds()) for (LivingEntity e : w.getLivingEntities()) if (!(e instanceof Player)) check(e);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        for (NamespacedKey k : mixes) Bukkit.getPotionBrewer().removePotionMix(k);
        mixes.clear();
    }

    // ---------------------------------------------------------------- the potions

    static int baseTicks(int level, boolean lng) { return level >= 2 ? 1800 : lng ? 9600 : 3600; }   // 1:30, 8:00, 3:00

    private static String time(int ticks) { int s = ticks / 20; return (s / 60) + ":" + String.format("%02d", s % 60); }

    private static Component line(String s, NamedTextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }

    ItemStack potion(Kind k, int level, boolean lng, Material form) {
        ItemStack it = new ItemStack(form);
        PotionMeta m = (PotionMeta) it.getItemMeta();
        String prefix = form == Material.SPLASH_POTION ? "Splash " : form == Material.LINGERING_POTION ? "Lingering " : "";
        List<Component> lore = new ArrayList<>();
        if (k == Kind.FLESHY) {
            m.displayName(line(prefix + "Fleshy Potion", NamedTextColor.WHITE));   // potions take their name from their contents, so item_name is ignored
            m.setColor(FLESHY);
            lore.add(Component.text("It's thick. It moves.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true));
        } else {
            boolean g = k == Kind.GROWTH;
            int t = baseTicks(level, lng), shown = form == Material.LINGERING_POTION ? t / 4 : t;
            m.displayName(line(prefix + "Potion of " + (g ? "Growth" : "Shrinking"), NamedTextColor.WHITE));
            m.setColor(g ? GROWTH : SHRINK);
            m.addCustomEffect(new PotionEffect(g ? PotionEffectType.SLOWNESS : PotionEffectType.WEAKNESS, t, 0, false, true, true), true);
            lore.add(line((g ? "Growth" : "Shrinking") + (level >= 2 ? " II" : "") + " (" + time(shown) + ")", NamedTextColor.BLUE));
            if (g) {
                lore.add(line(level >= 2 ? "+50% Size" : "+25% Size", NamedTextColor.BLUE));
                lore.add(line(level >= 2 ? "+1 Reach" : "+0.5 Reach", NamedTextColor.BLUE));
                lore.add(line(level >= 2 ? "+50% Knockback Resistance" : "+30% Knockback Resistance", NamedTextColor.BLUE));
                if (form != Material.POTION) lore.add(line("Grows crops, saplings and young animals.", NamedTextColor.GRAY));
                lore.add(Component.text("Brewed from a tumour.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true));
            } else {
                lore.add(line(level >= 2 ? "-40% Size" : "-25% Size", NamedTextColor.BLUE));
                lore.add(Component.text("Whatever it was, it's smaller now.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true));
            }
        }
        m.lore(lore);
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(kindKey, PersistentDataType.STRING, k.name());
        pdc.set(levelKey, PersistentDataType.INTEGER, level);
        pdc.set(longKey, PersistentDataType.BYTE, (byte) (lng ? 1 : 0));
        it.setItemMeta(m);
        return it;
    }

    Kind kind(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Kind.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private int level(ItemStack it) { Integer i = it.getItemMeta().getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER); return i == null ? 1 : i; }
    private boolean isLong(ItemStack it) { Byte b = it.getItemMeta().getPersistentDataContainer().get(longKey, PersistentDataType.BYTE); return b != null && b == 1; }

    private Predicate<ItemStack> is(Kind k, int level, boolean lng, Material form) {
        return it -> it != null && it.getType() == form && kind(it) == k && level(it) == level && isLong(it) == lng;
    }

    String adminGive(Player to) {
        List<ItemStack> l = List.of(potion(Kind.FLESHY, 1, false, Material.POTION),
                potion(Kind.GROWTH, 1, false, Material.POTION), potion(Kind.GROWTH, 2, false, Material.POTION), potion(Kind.GROWTH, 1, true, Material.POTION),
                potion(Kind.GROWTH, 1, false, Material.SPLASH_POTION), potion(Kind.GROWTH, 1, false, Material.LINGERING_POTION),
                potion(Kind.SHRINK, 1, false, Material.POTION), potion(Kind.SHRINK, 1, false, Material.SPLASH_POTION));
        for (ItemStack it : l) for (ItemStack left : to.getInventory().addItem(it).values()) to.getWorld().dropItem(to.getLocation(), left);
        return "Gave " + to.getName() + " the Growth and Shrinking potions.";
    }

    // ---------------------------------------------------------------- brewing

    /** fills a brewing stand for one step of the chain, for testing */
    String fillStand(Block b, int stage) {
        if (!(b.getState() instanceof org.bukkit.block.BrewingStand bs)) return "That isn't a brewing stand.";
        ItemStack water = new ItemStack(Material.POTION);
        PotionMeta wm = (PotionMeta) water.getItemMeta(); wm.setBasePotionType(PotionType.WATER); water.setItemMeta(wm);
        ItemStack in, ing;
        switch (stage) {
            case 1 -> { in = water; ing = plugin.tumourCrop().tumour(1); }
            case 2 -> { in = potion(Kind.FLESHY, 1, false, Material.POTION); ing = new ItemStack(Material.BONE_MEAL); }
            case 3 -> { in = potion(Kind.GROWTH, 1, false, Material.POTION); ing = plugin.tumourCrop().seed(1); }
            case 5 -> { in = potion(Kind.GROWTH, 1, false, Material.POTION); ing = plugin.cloudCotton().cotton(1); }
            default -> { in = potion(Kind.GROWTH, 1, false, Material.POTION); ing = new ItemStack(Material.GUNPOWDER); }
        }
        var inv = bs.getInventory();
        for (int i = 0; i < 3; i++) inv.setItem(i, in.clone());
        inv.setIngredient(ing);
        inv.setFuel(new ItemStack(Material.BLAZE_POWDER, 64));
        return "Filled the stand for step " + stage + ".";
    }

    /** the input bottle can be matched by a predicate, but the ingredient has to be an exact item or a material:
     *  Paper only lets those into the brewing stand's ingredient slot */
    private void mix(String name, ItemStack result, Predicate<ItemStack> input, RecipeChoice ingredient) {
        NamespacedKey key = new NamespacedKey(plugin, "brew_" + name);
        Bukkit.getPotionBrewer().removePotionMix(key);   // a reload leaves the old one behind
        Bukkit.getPotionBrewer().addPotionMix(new PotionMix(key, result, PotionMix.createPredicateChoice(input), ingredient));
        mixes.add(key);
    }

    private static RecipeChoice type(Material m) { return new RecipeChoice.MaterialChoice(m); }

    private void registerMixes() {
        Predicate<ItemStack> water = it -> it != null && it.getType() == Material.POTION && kind(it) == null
                && it.getItemMeta() instanceof PotionMeta pm && pm.getBasePotionType() == PotionType.WATER && !pm.hasCustomEffects();
        mix("fleshy", potion(Kind.FLESHY, 1, false, Material.POTION), water, new RecipeChoice.ExactChoice(plugin.tumourCrop().tumour(1)));
        RecipeChoice seed = new RecipeChoice.ExactChoice(plugin.tumourCrop().seed(1));
        RecipeChoice cotton = new RecipeChoice.ExactChoice(plugin.cloudCotton().cotton(1));
        int[][] variants = {{1, 0}, {2, 0}, {1, 1}};
        for (Material f : FORMS) {
            String fn = f.name().toLowerCase();
            mix("growth_" + fn, potion(Kind.GROWTH, 1, false, f), is(Kind.FLESHY, 1, false, f), type(Material.BONE_MEAL));
            containers("fleshy", Kind.FLESHY, 1, false, f);
            for (Kind k : new Kind[]{Kind.GROWTH, Kind.SHRINK}) for (int[] v : variants) {
                int lvl = v[0]; boolean lng = v[1] == 1;
                String n = k.name().toLowerCase() + "_" + lvl + (lng ? "_long" : "") + "_" + fn;
                Predicate<ItemStack> in = is(k, lvl, lng, f);
                if (lvl == 1 && !lng) {
                    mix(n + "_glowstone", potion(k, 2, false, f), in, type(Material.GLOWSTONE_DUST));
                    mix(n + "_seed", potion(k, 1, true, f), in, seed);
                }
                if (k == Kind.GROWTH) mix(n + "_cotton", potion(Kind.SHRINK, lvl, lng, f), in, cotton);
                containers(k.name().toLowerCase() + "_" + lvl + (lng ? "_long" : ""), k, lvl, lng, f);
            }
        }
        plugin.getLogger().info("Registered " + mixes.size() + " Growth potion brews.");
    }

    /** potion -> splash with gunpowder, splash -> lingering with dragon's breath */
    private void containers(String n, Kind k, int lvl, boolean lng, Material f) {
        if (f == Material.POTION) mix(n + "_splash", potion(k, lvl, lng, Material.SPLASH_POTION), is(k, lvl, lng, f), type(Material.GUNPOWDER));
        if (f == Material.SPLASH_POTION) mix(n + "_lingering", potion(k, lvl, lng, Material.LINGERING_POTION), is(k, lvl, lng, f), type(Material.DRAGON_BREATH));
    }

    // ---------------------------------------------------------------- growing and shrinking a body

    private static void mod(LivingEntity e, Attribute a, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai == null) return;
        if (ai.getModifier(key) != null) ai.removeModifier(key);
        if (amount != 0) ai.addModifier(new AttributeModifier(key, amount, op, EquipmentSlotGroup.ANY));
    }

    private void removeMods(LivingEntity e) {
        mod(e, Attribute.SCALE, scaleKey, 0, AttributeModifier.Operation.ADD_SCALAR);
        mod(e, Attribute.ENTITY_INTERACTION_RANGE, reachEKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        mod(e, Attribute.BLOCK_INTERACTION_RANGE, reachBKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        mod(e, Attribute.KNOCKBACK_RESISTANCE, kbKey, 0, AttributeModifier.Operation.ADD_NUMBER);
    }

    /** bosses and other mobs the plugin has already resized are left alone */
    private static boolean resizable(LivingEntity e) {
        if (e instanceof Player) return true;
        if (e instanceof org.bukkit.entity.Boss) return false;
        AttributeInstance s = e.getAttribute(Attribute.SCALE);
        return s != null && Math.abs(s.getBaseValue() - 1.0) < 1e-6;
    }

    void apply(LivingEntity e, Kind k, int level, int ticks) {
        if (!e.isValid() || k == Kind.FLESHY || !resizable(e)) return;
        if (plugin.boss() != null && plugin.boss().bloodMoon()) ticks *= 2;
        boolean g = k == Kind.GROWTH;
        long now = System.currentTimeMillis(), until = now + ticks * 50L;
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        String state = k.name() + ":" + level, was = pdc.get(stateKey, PersistentDataType.STRING);
        Long cur = pdc.get(untilKey, PersistentDataType.LONG);
        if (state.equals(was) && cur != null && cur > until) until = cur;   // a stronger dose already running keeps its time
        // growing and shrinking cancel each other out: the newest dose wins
        removeMods(e);
        mod(e, Attribute.SCALE, scaleKey, g ? (level >= 2 ? 0.5 : 0.25) : (level >= 2 ? -0.4 : -0.25), AttributeModifier.Operation.ADD_SCALAR);
        if (g) {
            double reach = level >= 2 ? 1.0 : 0.5;
            mod(e, Attribute.ENTITY_INTERACTION_RANGE, reachEKey, reach, AttributeModifier.Operation.ADD_NUMBER);
            mod(e, Attribute.BLOCK_INTERACTION_RANGE, reachBKey, reach, AttributeModifier.Operation.ADD_NUMBER);
            mod(e, Attribute.KNOCKBACK_RESISTANCE, kbKey, level >= 2 ? 0.5 : 0.3, AttributeModifier.Operation.ADD_NUMBER);
        }
        pdc.set(untilKey, PersistentDataType.LONG, until);
        pdc.set(stateKey, PersistentDataType.STRING, state);
        tracked.add(e.getUniqueId());
        // the side effect lasts exactly as long as the change, and its timer is the one the player sees
        PotionEffectType side = g ? PotionEffectType.SLOWNESS : PotionEffectType.WEAKNESS;
        PotionEffectType other = g ? PotionEffectType.WEAKNESS : PotionEffectType.SLOWNESS;
        e.removePotionEffect(side);
        e.addPotionEffect(new PotionEffect(side, (int) ((until - now) / 50), 0, false, true, true));
        if (was != null && !was.startsWith(k.name())) e.removePotionEffect(other);
        e.getWorld().playSound(e.getLocation(), g ? Sound.ENTITY_SLIME_SQUISH : Sound.ENTITY_SLIME_SQUISH_SMALL, 0.8f, g ? 0.5f : 1.6f);
        if (e instanceof Player p) p.sendActionBar(Component.text(g ? "You swell." : "You shrink.", g ? NamedTextColor.RED : NamedTextColor.LIGHT_PURPLE));
    }

    void clear(LivingEntity e) {
        removeMods(e);
        e.getPersistentDataContainer().remove(untilKey);
        e.getPersistentDataContainer().remove(stateKey);
        tracked.remove(e.getUniqueId());
    }

    private void check(LivingEntity e) {
        Long until = e.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (until == null) return;
        if (until <= System.currentTimeMillis()) clear(e); else tracked.add(e.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(tracked)) {
            Entity en = Bukkit.getEntity(id);
            if (!(en instanceof LivingEntity le) || !le.isValid()) { tracked.remove(id); continue; }   // checked again when it loads
            Long until = le.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
            if (until == null || until <= now) {
                clear(le);
                if (le instanceof Player p) p.sendActionBar(Component.text("You're back to your own size.", NamedTextColor.GRAY));
            }
        }
        // lingering Growth keeps working the ground under it
        for (var it = clouds.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Entity en = Bukkit.getEntity(e.getKey());
            if (!(en instanceof AreaEffectCloud c) || !c.isValid()) { it.remove(); continue; }
            if (e.getValue()[0] == Kind.GROWTH.ordinal()) growAround(c.getLocation(), Math.max(1, (int) Math.ceil(c.getRadius())), 1, 0.08);
        }
    }

    // ---------------------------------------------------------------- drinking

    @EventHandler(ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (e.getItem().getType() == Material.MILK_BUCKET) { clear(p); return; }
        Kind k = kind(e.getItem());
        if (k == null || k == Kind.FLESHY) return;
        int lvl = level(e.getItem()), ticks = baseTicks(lvl, isLong(e.getItem()));
        long now = System.currentTimeMillis();
        Long last = p.getPersistentDataContainer().get(lastKey, PersistentDataType.LONG);
        p.getPersistentDataContainer().set(lastKey, PersistentDataType.LONG, now);
        boolean overdose = last != null && now - last < 60_000;
        Bukkit.getScheduler().runTask(plugin, () -> {   // after vanilla has applied the potion's own effect
            apply(p, k, lvl, ticks);
            if (overdose) overdose(p);
        });
    }

    private void overdose(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 200, 0));
        Location l = p.getLocation();
        p.getWorld().dropItemNaturally(l, plugin.tumourCrop().tumour(1));
        p.getWorld().playSound(l, Sound.ENTITY_SLIME_SQUISH, 1f, 0.4f);
        p.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 1, 0), 24, 0.3, 0.4, 0.3, Material.NETHER_WART_BLOCK.createBlockData());
        p.sendActionBar(Component.text("Too much, too fast. Something tears loose.", NamedTextColor.DARK_RED));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { clear(e.getPlayer()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { check(e.getPlayer()); }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof LivingEntity le) check(le);
    }

    // ---------------------------------------------------------------- splash and lingering

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ItemStack it = e.getPotion().getItem();
        Kind k = kind(it);
        if (k == null || k == Kind.FLESHY) return;
        int lvl = level(it), ticks = baseTicks(lvl, isLong(it));
        for (LivingEntity le : e.getAffectedEntities()) {
            double in = e.getIntensity(le);
            int t = (int) Math.round(ticks * in);
            if (t < 20) continue;
            if (k == Kind.GROWTH && le instanceof org.bukkit.entity.Ageable ag && !ag.isAdult()) ag.setAdult();
            Bukkit.getScheduler().runTask(plugin, () -> apply(le, k, lvl, t));
        }
        if (k == Kind.GROWTH) growAround(e.getPotion().getLocation(), 3, lvl, 1.0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLinger(LingeringPotionSplashEvent e) {
        ItemStack it = e.getEntity().getItem();
        Kind k = kind(it);
        if (k == null || k == Kind.FLESHY) return;
        clouds.put(e.getAreaEffectCloud().getUniqueId(), new int[]{k.ordinal(), level(it), isLong(it) ? 1 : 0});
    }

    @EventHandler(ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent e) {
        int[] s = clouds.get(e.getEntity().getUniqueId());
        if (s == null) return;
        Kind k = Kind.values()[s[0]];
        int lvl = s[1], t = baseTicks(lvl, s[2] == 1) / 4;
        for (LivingEntity le : e.getAffectedEntities()) {
            if (k == Kind.GROWTH && le instanceof org.bukkit.entity.Ageable ag && !ag.isAdult()) ag.setAdult();
            Bukkit.getScheduler().runTask(plugin, () -> apply(le, k, lvl, t));
        }
    }

    // ---------------------------------------------------------------- the ground

    private void growAround(Location c, int r, int stages, double chance) {
        World w = c.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r + 1) continue;
            for (int dy = -1; dy <= 1; dy++) {
                if (chance < 1 && rnd.nextDouble() > chance) continue;
                grow(w.getBlockAt(cx + dx, cy + dy, cz + dz), stages);
            }
        }
    }

    private void grow(Block b, int n) {
        if (plugin.tumourCrop().grow(b, n) || plugin.cloudCotton().grow(b, n)) { puff(b); return; }
        Material m = b.getType();
        BlockData d = b.getBlockData();
        if (CROPS.contains(m) && d instanceof org.bukkit.block.data.Ageable a && a.getAge() < a.getMaximumAge()) {
            a.setAge(Math.min(a.getMaximumAge(), a.getAge() + n));
            b.setBlockData(a);
            puff(b);
        } else if (Tag.SAPLINGS.isTagged(m) || m == Material.CRIMSON_FUNGUS || m == Material.WARPED_FUNGUS) {
            for (int i = 0; i < n && b.getType() == m; i++) b.applyBoneMeal(BlockFace.UP);
            puff(b);
        }
    }

    private static void puff(Block b) {
        b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(.5, .5, .5), 6, .3, .3, .3, 0);
    }
}
