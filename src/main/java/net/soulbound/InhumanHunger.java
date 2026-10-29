package net.soulbound;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The inhuman's food bar is the sickly green one the Hunger effect shows.
 *
 * They carry a hidden, endless Hunger effect (no particles, no icon) purely for the look: the extra hunger it would
 * cause is cancelled, so their food drains at the normal rate and the bar shakes when it's low, as usual. Milk and
 * totems leave it alone, it's back the moment they respawn, and a check every second puts it back if anything else
 * clears it. Real Hunger (rotten flesh, husks, pufferfish) still drains them as it should while it lasts.
 */
final class InhumanHunger implements Listener {
    private final Soulbound plugin;
    /** When a real Hunger effect (not ours) runs out, per player. */
    private final Map<UUID, Long> realUntil = new HashMap<>();
    private boolean applying;

    InhumanHunger(Soulbound plugin) { this.plugin = plugin; }

    /** Switched off for now: anyone still carrying the hidden Hunger has it taken off (see start()). */
    private boolean on() { return false; }

    private boolean inhuman(Player p) { return plugin.inhuman() != null && plugin.inhuman().is(p); }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (on() && inhuman(p)) apply(p);
                else strip(p);
            }
        }, 40L, 20L);
    }

    private static boolean ours(PotionEffect e) {
        return e != null && e.getType().equals(PotionEffectType.HUNGER) && e.isInfinite() && !e.hasParticles() && !e.hasIcon();
    }

    private boolean realActive(Player p) {
        Long t = realUntil.get(p.getUniqueId());
        if (t == null) return false;
        if (t < System.currentTimeMillis()) { realUntil.remove(p.getUniqueId()); return false; }
        return true;
    }

    /** Give them the hidden Hunger, unless they already have Hunger of some kind. */
    void apply(Player p) {
        if (!on() || p.isDead() || p.hasPotionEffect(PotionEffectType.HUNGER)) return;
        applying = true;
        try { p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, PotionEffect.INFINITE_DURATION, 0, true, false, false)); }
        finally { applying = false; }
    }

    /** Take the hidden Hunger away (cured, or the feature turned off). Real Hunger is left alone. */
    void strip(Player p) {
        if (!ours(p.getPotionEffect(PotionEffectType.HUNGER))) return;
        applying = true;
        try { p.removePotionEffect(PotionEffectType.HUNGER); }
        finally { applying = false; }
    }

    /** Called when someone turns inhuman or is cured. */
    void changed(Player p, boolean inhuman) {
        if (inhuman) apply(p); else strip(p);
    }

    // ---------------------------------------------------------------- keeping it

    /** The hidden Hunger makes no one hungrier; real Hunger still does. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExhaust(EntityExhaustionEvent e) {
        if (e.getExhaustionReason() != EntityExhaustionEvent.ExhaustionReason.HUNGER_EFFECT) return;
        if (!(e.getEntity() instanceof Player p) || !inhuman(p) || realActive(p)) return;
        if (ours(p.getPotionEffect(PotionEffectType.HUNGER))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (applying || !(e.getEntity() instanceof Player p) || !PotionEffectType.HUNGER.equals(e.getModifiedType())) return;
        if (!on() || !inhuman(p)) return;
        var action = e.getAction();
        var cause = e.getCause();
        // real Hunger arriving: let it drain them for as long as it would have
        if ((action == EntityPotionEffectEvent.Action.ADDED || action == EntityPotionEffectEvent.Action.CHANGED)
                && cause != EntityPotionEffectEvent.Cause.PLUGIN && e.getNewEffect() != null && !e.getNewEffect().isInfinite()) {
            realUntil.put(p.getUniqueId(), System.currentTimeMillis() + e.getNewEffect().getDuration() * 50L);
            return;
        }
        // milk and totems don't wash the green out
        if ((action == EntityPotionEffectEvent.Action.CLEARED || action == EntityPotionEffectEvent.Action.REMOVED)
                && (cause == EntityPotionEffectEvent.Cause.MILK || cause == EntityPotionEffectEvent.Cause.TOTEM)) {
            if (ours(e.getOldEffect())) { e.setCancelled(true); return; }
            // a real Hunger is being washed off: put ours back straight after
            realUntil.remove(p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && inhuman(p)) apply(p); });
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        realUntil.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline() && inhuman(p)) apply(p); });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (on() && inhuman(p)) apply(p); else strip(p);
        });
    }
}
