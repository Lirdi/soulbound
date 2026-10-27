package net.soulbound;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Blood Priest: a robed figure (a mannequin wearing the pack's soulbound:entity/blood_priest skin) who turns to
 * watch whoever comes near and sells the tools of an abduction for gold: the Hood, the Cultist's Cudgel and the
 * Cultist's Shackles. Spawned with /moloch priest, removed with /moloch priest remove.
 */
final class BloodPriest implements Listener {
    private final Soulbound plugin;
    private final CaptureManager captures;
    private final LeashItem leash;
    private final NamespacedKey key;
    private final Set<UUID> priests = new HashSet<>();
    private int hoodPrice, cudgelPrice, shacklesPrice;
    private double lookRange;
    private List<String> sacrificeLines;
    private String sacrificeBroadcast, firstLine;

    BloodPriest(Soulbound plugin, CaptureManager captures, LeashItem leash) {
        this.plugin = plugin;
        this.captures = captures;
        this.leash = leash;
        this.key = new NamespacedKey(plugin, "blood_priest");
        load();
    }

    void load() {
        FileConfiguration c = plugin.getConfig();
        hoodPrice = c.getInt("priest.price.hood", 6);
        cudgelPrice = c.getInt("priest.price.cudgel", 10);
        shacklesPrice = c.getInt("priest.price.shackles", 16);
        lookRange = c.getDouble("priest.look-range", 16);
        sacrificeLines = c.getStringList("priest.sacrifice-lines");
        if (sacrificeLines.isEmpty()) sacrificeLines = List.of(
                "I've kept a lock of hair from every one of them. Want to see?",
                "Sleep well. If you hear chains in the night, don't check.",
                "You'll dream about this. He'll be in the dream too.");
        firstLine = c.getString("priest.first-sacrifice-line", "Look at your hands. Now look at me. Now we're the same.");
        sacrificeBroadcast = c.getString("priest.sacrifice-broadcast", "The Blood Priest smiles.");
    }

    void start() {
        for (World w : Bukkit.getWorlds())
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) if (isPriest(m)) priests.add(m.getUniqueId());
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    boolean isPriest(Entity e) {
        return e instanceof Mannequin && e.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (isPriest(en)) priests.add(en.getUniqueId());
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) priests.remove(en.getUniqueId());
    }

    // ---------------------------------------------------------------- summoning

    String spawn(Player at) {
        Location l = at.getLocation();
        Mannequin priest = l.getWorld().spawn(l, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile()
                    .skinPatch(p -> p.body(new NamespacedKey("soulbound", "entity/blood_priest")).model(PlayerTextures.SkinModel.CLASSIC))
                    .build());
            m.customName(Component.text("Blood Priest", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text("Trades for gold", NamedTextColor.GOLD));
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        priests.add(priest.getUniqueId());
        return "The Blood Priest stands where you stood.";
    }

    String removeNear(Player p) {
        Mannequin best = null;
        double bd = 8 * 8;
        for (Mannequin m : p.getWorld().getEntitiesByClass(Mannequin.class)) {
            if (!isPriest(m)) continue;
            double d = m.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = m; }
        }
        if (best == null) return "No Blood Priest within 8 blocks.";
        priests.remove(best.getUniqueId());
        best.remove();
        return "The Blood Priest is gone.";
    }

    // ---------------------------------------------------------------- watching

    private void tick() {
        Iterator<UUID> it = priests.iterator();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (!(e instanceof Mannequin m) || !m.isValid()) { it.remove(); continue; }
            Location here = m.getLocation();
            Player best = null;
            double bd = lookRange * lookRange;
            for (Player p : m.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                double d = p.getLocation().distanceSquared(here);
                if (d < bd) { bd = d; best = p; }
            }
            if (best == null) continue;
            Vector dir = best.getEyeLocation().toVector().subtract(m.getEyeLocation().toVector());
            float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            float pitch = (float) Math.toDegrees(-Math.atan2(dir.getY(), Math.hypot(dir.getX(), dir.getZ())));
            float cy = here.getYaw(), cp = here.getPitch();
            float ny = cy + wrap(yaw - cy) * 0.35f, np = cp + (pitch - cp) * 0.35f;   // turn smoothly, not snap
            m.setRotation(ny, np);
            float body = m.getBodyYaw(), diff = wrap(ny - body);
            if (Math.abs(diff) > 35) m.setBodyYaw(body + diff - Math.copySign(35f, diff));   // the body follows the head
        }
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    // ---------------------------------------------------------------- after a sacrifice

    /** A couple of seconds after the fire takes them, the Priest whispers to the captor (his signature line the first time); everyone sees him smile. */
    void onSacrifice(Player captor, String victimName, boolean first) {
        String pick = first && firstLine != null && !firstLine.isBlank() ? firstLine
                : sacrificeLines.isEmpty() ? null
                : sacrificeLines.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(sacrificeLines.size()));
        if (pick == null) return;
        String line = pick.replace("%player%", captor.getName()).replace("%victim%", victimName);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (captor.isOnline()) {
                captor.sendMessage(Component.text("The Blood Priest whispers: ", NamedTextColor.DARK_RED, TextDecoration.ITALIC)
                        .append(Component.text(line, NamedTextColor.RED, TextDecoration.ITALIC)));
                captor.playSound(captor.getLocation(), "minecraft:entity.evoker.ambient", 0.7f, 0.55f);
            }
            if (sacrificeBroadcast != null && !sacrificeBroadcast.isBlank())
                Bukkit.broadcast(Component.text(sacrificeBroadcast, NamedTextColor.DARK_RED, TextDecoration.ITALIC));
        }, 50L);
    }

    // ---------------------------------------------------------------- trading

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        if (!isPriest(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        open(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (isPriest(e.getEntity())) e.setCancelled(true);
    }

    private void open(Player p) {
        Merchant m = Bukkit.createMerchant();
        List<MerchantRecipe> list = new ArrayList<>();
        list.add(recipe(captures.items.create(AbductionItems.Kind.HOOD, 1), hoodPrice));
        list.add(recipe(captures.items.create(AbductionItems.Kind.CUDGEL, 1), cudgelPrice));
        list.add(recipe(leash.create(1), shacklesPrice));
        m.setRecipes(list);
        p.openInventory(MenuType.MERCHANT.builder().merchant(m).title(Component.text("Blood Priest", NamedTextColor.DARK_RED)).build(p));
        p.playSound(p.getLocation(), "minecraft:entity.evoker.ambient", 0.8f, 0.7f);
    }

    /** Gold ingots up to a stack; past that, the price is asked in gold blocks. */
    private static MerchantRecipe recipe(ItemStack out, int gold) {
        MerchantRecipe r = new MerchantRecipe(out, 0, Integer.MAX_VALUE, false);
        if (gold > 64) r.addIngredient(new ItemStack(Material.GOLD_BLOCK, Math.min(64, (gold + 8) / 9)));
        else r.addIngredient(new ItemStack(Material.GOLD_INGOT, Math.max(1, gold)));
        return r;
    }
}
