package net.soulbound;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI values for scoreboards, tab lists and chat (only registered when PlaceholderAPI is installed):
 *   %soulbound_state%           Human / Graced / Inhuman (coloured)
 *   %soulbound_realm%           Overworld / Nether / Heaven / Moloch's Realm (coloured), or the world's name
 *   %soulbound_moloch_hunger%   Moloch's hunger, 0-100
 *   %soulbound_moloch%          Sleeping / Rising / AWAKE (coloured)
 *   %soulbound_god%             Waiting / Fighting / Resting (after His first defeat), coloured
 */
final class SoulboundPlaceholders extends PlaceholderExpansion {
    private final Soulbound plugin;

    SoulboundPlaceholders(Soulbound plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "soulbound"; }
    @Override public String getAuthor() { return "Soulbound"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer off, String id) {
        Player p = off == null ? null : off.getPlayer();
        switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "state" -> {
                if (p == null) return "";
                if (plugin.inhuman() != null && plugin.inhuman().is(p)) return plugin.getConfig().getString("placeholders.inhuman", "&#B3122EInhuman");
                if (plugin.grace() != null && plugin.grace().is(p)) return plugin.getConfig().getString("placeholders.graced", "&#F0D987Graced");
                return plugin.getConfig().getString("placeholders.human", "&fHuman");
            }
            case "realm" -> {
                if (p == null) return "";
                World w = p.getWorld();
                if (plugin.zones() != null && plugin.zones().inRealm(p.getLocation())) return plugin.getConfig().getString("placeholders.realm-sacrifice", "&#C83232Moloch's Realm");
                return switch (w.getEnvironment()) {
                    case NORMAL -> plugin.getConfig().getString("placeholders.realm-overworld", "&#4FE04FOverworld");
                    case NETHER -> plugin.getConfig().getString("placeholders.realm-nether", "&#F07A6ANether");
                    case THE_END -> plugin.getConfig().getString("placeholders.realm-heaven", "&#F0D987Heaven");
                    default -> "&f" + w.getName();
                };
            }
            case "moloch_hunger" -> {
                return plugin.hunger() == null ? "0" : String.valueOf((int) Math.floor(plugin.hunger().value()));
            }
            case "moloch" -> {
                if (plugin.boss() != null && plugin.boss().active()) return "&#C83232&lAWAKE";
                if (plugin.hunger() != null && plugin.hunger().awakenAt() > 0) return "&#F07A6ARising";
                return "&7Sleeping";
            }
            case "god" -> {
                if (plugin.god() != null && plugin.god().fighting()) return "&#F0D987&lFighting";
                return plugin.store().data.getBoolean("god.defeated-once", false) ? "&7Resting" : "&7Waiting";
            }
            default -> { return null; }
        }
    }
}
