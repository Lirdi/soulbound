package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Peacekeeper: a great, still vex that keeps watch by the Pearly Gates and trades with whoever comes up to Heaven.
 * He sells the Breath of Eden, the Heart of Light (the only cure for the inhuman, who can never come up here to buy it:
 * someone has to bring it down to them), splash potions of Levitation and the frames for a Gate of Heaven.
 * Placed with /sb world peacekeeper, removed with /sb world peacekeeper remove.
 */
final class Peacekeeper implements Listener {
    private final Soulbound plugin;
    private final NamespacedKey key;
    private final Set<UUID> keepers = new HashSet<>();
    private final Map<UUID, Location> homes = new HashMap<>();
    private final Map<UUID, Long> lastSaid = new HashMap<>();
    private final Set<Merchant> open = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private int tick;

    Peacekeeper(Soulbound plugin) { this.plugin = plugin; this.key = new NamespacedKey(plugin, "peacekeeper"); }

    void start() {
        for (World w : Bukkit.getWorlds()) for (Vex v : w.getEntitiesByClass(Vex.class)) if (is(v)) track(v);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 2L);
    }

    boolean is(Entity e) { return e instanceof Vex && e.getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    private void track(Vex v) { keepers.add(v.getUniqueId()); homes.putIfAbsent(v.getUniqueId(), v.getLocation()); }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) { for (Entity en : e.getEntities()) if (is(en)) track((Vex) en); }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) { for (Entity en : e.getEntities()) keepers.remove(en.getUniqueId()); }

    // ---------------------------------------------------------------- placing

    String spawn(Player at) {
        Location l = at.getLocation().add(0, 0.6, 0);
        double scale = plugin.getConfig().getDouble("peacekeeper.scale", 2.2);
        Vex v = l.getWorld().spawn(l, Vex.class, x -> {
            x.customName(Component.text("Peacekeeper", NamedTextColor.WHITE, TextDecoration.BOLD));
            x.setCustomNameVisible(true);
            x.setAI(false);
            x.setGravity(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setPersistent(true);
            x.setRemoveWhenFarAway(false);
            x.setCollidable(false);
            var sc = x.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(scale);
            x.getEquipment().setItemInMainHand(new ItemStack(Material.LILY_OF_THE_VALLEY));
            x.getEquipment().setItemInMainHandDropChance(0f);
            x.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        keepers.add(v.getUniqueId());
        homes.put(v.getUniqueId(), l);
        return "The Peacekeeper keeps watch where you stood.";
    }

    String removeNear(Player p) {
        Vex best = null;
        double bd = 10 * 10;
        for (Vex v : p.getWorld().getEntitiesByClass(Vex.class)) {
            if (!is(v)) continue;
            double d = v.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = v; }
        }
        if (best == null) return "No Peacekeeper within 10 blocks.";
        keepers.remove(best.getUniqueId());
        homes.remove(best.getUniqueId());
        best.remove();
        return "The Peacekeeper is gone.";
    }

    // ---------------------------------------------------------------- watching: a slow hover, turning to whoever is near

    private void tickAll() {
        tick++;
        Iterator<UUID> it = keepers.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Vex v) || !v.isValid()) { it.remove(); continue; }
            Location home = homes.computeIfAbsent(id, k -> v.getLocation());
            Location now = v.getLocation();
            Player best = null;
            double bd = 16 * 16;
            for (Player p : v.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                double d = p.getLocation().distanceSquared(now);
                if (d < bd) { bd = d; best = p; }
            }
            float yaw = now.getYaw(), pitch = 0;
            if (best != null) {
                Vector dir = best.getEyeLocation().toVector().subtract(v.getEyeLocation().toVector());
                float want = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
                yaw = yaw + wrap(want - yaw) * 0.3f;
                pitch = (float) Math.toDegrees(-Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ()))) * 0.5f;
            }
            Location to = home.clone().add(0, Math.sin(tick * 0.08) * 0.18, 0);
            to.setYaw(yaw); to.setPitch(pitch);
            v.teleport(to);
            v.setBodyYaw(yaw);
            if (tick % 20 == 0) v.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, v.getLocation().add(0, 0.8, 0), 2, 0.4, 0.4, 0.4, 0.01);
        }
    }

    private static float wrap(float a) { a %= 360f; if (a >= 180f) a -= 360f; if (a < -180f) a += 360f; return a; }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) { if (is(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) { if (is(e.getEntity())) e.setCancelled(true); }

    // ---------------------------------------------------------------- trading

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!is(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        openFor(e.getPlayer());
    }

    private ItemStack levitation() {
        ItemStack it = new ItemStack(Material.SPLASH_POTION);
        PotionMeta m = (PotionMeta) it.getItemMeta();
        int secs = plugin.getConfig().getInt("peacekeeper.levitation-seconds", 20);
        m.addCustomEffect(new PotionEffect(PotionEffectType.LEVITATION, secs * 20, 0), true);
        m.setColor(Color.fromRGB(0xDDF4FF));
        m.customName(Component.text("Splash Potion of Levitation", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(m);
        return it;
    }

    private static MerchantRecipe deal(ItemStack out, ItemStack... pay) {
        MerchantRecipe r = new MerchantRecipe(out, 0, Integer.MAX_VALUE, false);
        for (ItemStack p : pay) r.addIngredient(p);
        return r;
    }

    private void openFor(Player p) {
        var c = plugin.getConfig();
        ItemStack tear = plugin.quantum().material(QuantumGear.Mat.TEAR);
        List<MerchantRecipe> list = new ArrayList<>();
        ItemStack tears = tear.clone(); tears.setAmount(Math.max(1, c.getInt("peacekeeper.price.breath-of-eden-tears", 2)));
        list.add(deal(plugin.eden().create(), tears));
        list.add(deal(plugin.grace().heart(), new ItemStack(Material.DRAGON_EGG, Math.max(1, c.getInt("peacekeeper.price.heart-of-light-eggs", 1)))));
        list.add(deal(levitation(), new ItemStack(Material.DIAMOND, Math.max(1, Math.min(64, c.getInt("peacekeeper.price.levitation-diamonds", 10))))));
        ItemStack oneTear = tear.clone(); oneTear.setAmount(Math.max(1, c.getInt("peacekeeper.price.gate-frames-tears", 1)));
        list.add(deal(new ItemStack(Material.END_PORTAL_FRAME, Math.max(1, c.getInt("peacekeeper.gate-frames", 3))), oneTear));
        Merchant m = Bukkit.createMerchant();
        m.setRecipes(list);
        open.add(m);
        p.openInventory(MenuType.MERCHANT.builder().merchant(m).title(Component.text("Peacekeeper", NamedTextColor.WHITE)).build(p));
        p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", 1f, 1.2f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPurchase(io.papermc.paper.event.player.PlayerPurchaseEvent e) {
        if (!(e.getPlayer().getOpenInventory().getTopInventory() instanceof org.bukkit.inventory.MerchantInventory inv) || !open.contains(inv.getMerchant())) return;
        ItemStack out = e.getTrade().getResult();
        String line;
        if (plugin.eden().is(out)) line = "Breathe easy. The air up here was made for you too.";
        else if (plugin.grace().isHeart(out)) line = "This isn't for you. Find someone who's lost their way, and give it to them. Nobody comes back alone.";
        else if (out.getType() == Material.SPLASH_POTION) line = "Up is the only direction that matters here.";
        else if (out.getType() == Material.END_PORTAL_FRAME) line = "Build your own way in. He likes visitors more than He lets on.";
        else return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastSaid.get(p.getUniqueId());
        if (last != null && now - last < 4000) return;
        lastSaid.put(p.getUniqueId(), now);
        p.sendMessage(Component.text("The Peacekeeper says: ", NamedTextColor.GOLD, TextDecoration.ITALIC)
                .append(Component.text(line, NamedTextColor.WHITE, TextDecoration.ITALIC)));
        p.playSound(p.getLocation(), "minecraft:block.amethyst_block.resonate", 0.8f, 1.5f);
    }
}
