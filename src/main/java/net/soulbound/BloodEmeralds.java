package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blood Emeralds: Moloch's currency, paid for in heads. Throw a villager head taken with the Spiked Cultist Axe into
 * the summit fire pit and it burns into one Blood Emerald - once a day (24 real hours) per player. Throw more and the
 * fire spits them back; nothing happens until the day is up. The Blood Priest takes them for his hat.
 */
final class BloodEmeralds implements Listener {
    static final long COOLDOWN_MS = 24L * 3600_000L;
    private static final int WATCH_TICKS = 20 * 15;   // how long a thrown head is followed to see if it lands in the fire

    private final Soulbound plugin;
    private final NamespacedKey key, lastKey;
    /** thrown villager heads being watched: item entity -> ticks left */
    private final Map<Item, Integer> watched = new HashMap<>();
    private BukkitTask task;

    BloodEmeralds(Soulbound plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "blood_emerald");
        lastKey = new NamespacedKey(plugin, "blood_offering_last");
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L); }
    void stop() {
        if (task != null) task.cancel();
        for (Item i : watched.keySet()) if (i.isValid()) i.setInvulnerable(false);
        watched.clear();
    }

    ItemStack create(int amount) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, Math.min(64, amount)));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Blood Emerald", TextColor.color(0xC4122A), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        // kept to short lines, so the lore wrapper never touches it (trades match on the exact item)
        m.lore(List.of(Component.text("Paid in heads, at Moloch's fire.", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, true)));
        m.setItemModel(new NamespacedKey("soulbound", "blood_emerald"));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean is(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    String adminGive(Player to, int amount) {
        give(to, create(amount));
        return "Gave " + to.getName() + " " + amount + " Blood Emerald" + (amount == 1 ? "" : "s") + ".";
    }

    // ---------------------------------------------------------------- the offering

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        CultistAxe axe = plugin.cultistAxe();
        Item item = e.getItemDrop();
        if (axe == null || !axe.isVillagerHead(item.getItemStack()) || plugin.zones() == null || !plugin.zones().inRealm(item.getLocation())) return;
        item.setInvulnerable(true);   // so the fire can't eat it before Moloch decides
        watched.put(item, WATCH_TICKS);
    }

    private void tick() {
        Iterator<Map.Entry<Item, Integer>> it = watched.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Item, Integer> en = it.next();
            Item item = en.getKey();
            if (!item.isValid()) { it.remove(); continue; }
            if (plugin.zones().inPitFire(item.getLocation())) { it.remove(); offer(item); continue; }
            int left = en.getValue() - 2;
            if (left <= 0) { item.setInvulnerable(false); it.remove(); } else en.setValue(left);
        }
    }

    private void offer(Item item) {
        UUID by = item.getThrower();
        Player p = by == null ? null : Bukkit.getPlayer(by);
        if (p == null) { item.setInvulnerable(false); return; }   // nobody to pay: it just burns
        ItemStack heads = item.getItemStack();
        item.remove();
        Location at = item.getLocation();
        long now = System.currentTimeMillis();
        long last = p.getPersistentDataContainer().getOrDefault(lastKey, PersistentDataType.LONG, 0L);

        if (now - last < COOLDOWN_MS) {   // already fed today: the fire spits it back
            give(p, heads);
            Duration d = Duration.ofMillis(COOLDOWN_MS - (now - last));
            p.sendActionBar(Component.text("Moloch has already fed from you today. Come back in " + d.toHours() + "h " + d.toMinutesPart() + "m.", NamedTextColor.RED));
            at.getWorld().playSound(at, "minecraft:block.fire.extinguish", 1f, 0.6f);
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 12, 0.3, 0.3, 0.3, 0.02);
            return;
        }

        if (heads.getAmount() > 1) { ItemStack rest = heads.clone(); rest.setAmount(heads.getAmount() - 1); give(p, rest); }   // one head a day
        p.getPersistentDataContainer().set(lastKey, PersistentDataType.LONG, now);
        give(p, create(1));
        at.getWorld().spawnParticle(Particle.FLAME, at, 40, 0.5, 0.8, 0.5, 0.06);
        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 30, 0.6, 1.0, 0.6, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0xC4122A), 1.6f));
        at.getWorld().playSound(at, "minecraft:entity.blaze.shoot", 1f, 0.5f);
        at.getWorld().playSound(at, "minecraft:entity.warden.heartbeat", 1f, 0.7f);
        p.showTitle(Title.title(Component.text("Moloch accepts", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("A Blood Emerald rises from the fire.", NamedTextColor.RED),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(700))));
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }
}
