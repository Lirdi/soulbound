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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        captures = new CaptureManager(this);
        getServer().getPluginManager().registerEvents(captures, this);
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
                sender.sendMessage(Component.text("Soulbound config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("/soulbound release  ·  let your captive go", NamedTextColor.GRAY));
                if (sender.hasPermission("soulbound.admin")) sender.sendMessage(Component.text("/soulbound reload  ·  reload config.yml", NamedTextColor.GRAY));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("soulbound.admin") ? List.of("release", "reload") : List.of("release");
        return List.of();
    }
}
