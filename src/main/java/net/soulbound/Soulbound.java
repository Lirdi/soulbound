package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class Soulbound extends JavaPlugin {
    private CaptureManager captures;
    private LeashItem leash;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        leash = new LeashItem(this);
        leash.registerRecipe();
        captures = new CaptureManager(this, leash);
        getServer().getPluginManager().registerEvents(captures, this);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) { leash.unlockFor(e.getPlayer()); }
        }, this);
        for (Player p : getServer().getOnlinePlayers()) leash.unlockFor(p);
        captures.start();
        getLogger().info("Soulbound ready: weakened players can be captured with a lead.");
    }

    @Override
    public void onDisable() {
        if (captures != null) captures.stop();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "release" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Only players can release a captive."); return true; }
                Capture cap = captures.heldBy(p);
                if (cap == null) { p.sendMessage(Component.text("You're not holding anyone.", NamedTextColor.GRAY)); return true; }
                captures.end(cap, CaptureManager.End.LET_GO);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("soulbound.admin")) { sender.sendMessage(Component.text("You can't do that.", NamedTextColor.RED)); return true; }
                reloadConfig();
                captures.loadSettings();
                leash.load();
                leash.registerRecipe();
                sender.sendMessage(Component.text("Soulbound config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            case "give" -> {
                if (!sender.hasPermission("soulbound.admin")) { sender.sendMessage(Component.text("You can't do that.", NamedTextColor.RED)); return true; }
                Player target = args.length > 1 ? getServer().getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (target == null) { sender.sendMessage(Component.text("Who? /soulbound give <player> [amount]", NamedTextColor.GRAY)); return true; }
                int n = 1;
                if (args.length > 2) try { n = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) {}
                target.getInventory().addItem(leash.create(n));
                sender.sendMessage(Component.text("Gave " + n + " capture leash" + (n > 1 ? "es" : "") + " to " + target.getName() + ".", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("/soulbound release  ·  let your captive go", NamedTextColor.GRAY));
                if (sender.hasPermission("soulbound.admin")) {
                    sender.sendMessage(Component.text("/soulbound give <player> [amount]  ·  give capture leashes", NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("/soulbound reload  ·  reload config.yml", NamedTextColor.GRAY));
                }
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("soulbound.admin") ? List.of("release", "give", "reload") : List.of("release");
        return List.of();
    }
}
