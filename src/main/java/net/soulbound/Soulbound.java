package net.soulbound;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class Soulbound extends JavaPlugin {
    private Store store;
    private Zones zones;
    private LeashItem leash;
    private CaptureManager captures;
    private CurseManager curses;
    private HungerMeter hunger;
    private MolochBoss boss;
    private SacrificeManager sacrifices;
    private PortalManager portals;
    private SummitDrums drums;
    private MolochMusic music;
    private GodMusic godMusic;
    private StrongholdGate gate;
    private ArenaSeal seal;
    private Parasite parasite;
    private BloodMoonBeasts beasts;
    private Tartare tartare;
    private KrakenBeard kraken;
    private Cute cute;
    private GodGear godGear;
    private QuantumGear quantum;
    private GodManager god;
    private HeavenBuilder heaven;
    private HaloItem halo;
    private MolochGear gear;
    private ScreenCrack cracks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // one-off: his scream's nausea went from 6 to 11 seconds; bump configs still on the old default
        if (!getConfig().getBoolean("migrated.scream-nausea-11", false)) {
            if (getConfig().getInt("boss.scream-nausea-seconds", 6) == 6) getConfig().set("boss.scream-nausea-seconds", 11);
            getConfig().set("migrated.scream-nausea-11", true);
            saveConfig();
        }
        // one-off: blood moons went from 24 hours to 1
        if (!getConfig().getBoolean("migrated.blood-moon-1h", false)) {
            if (getConfig().getInt("boss.blood-moon-hours", 24) == 24) getConfig().set("boss.blood-moon-hours", 1);
            getConfig().set("migrated.blood-moon-1h", true);
            saveConfig();
        }
        store = new Store(this);
        zones = new Zones(this);
        leash = new LeashItem(this);
        leash.registerRecipe();
        captures = new CaptureManager(this, leash);
        curses = new CurseManager(this, store);
        boss = new MolochBoss(this, store, zones);
        hunger = new HungerMeter(this, store);
        sacrifices = new SacrificeManager(this, store, captures, curses, hunger, zones);
        portals = new PortalManager(this, store, zones, captures);
        drums = new SummitDrums(this, zones);
        music = new MolochMusic(this);
        halo = new HaloItem(this, store);
        gear = new MolochGear(this, store);
        cracks = new ScreenCrack(this);
        god = new GodManager(this, store);
        heaven = new HeavenBuilder(this, store);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(captures, this);
        pm.registerEvents(curses, this);
        pm.registerEvents(hunger, this);
        pm.registerEvents(boss, this);
        pm.registerEvents(sacrifices, this);
        pm.registerEvents(portals, this);
        pm.registerEvents(god, this);
        pm.registerEvents(heaven, this);
        pm.registerEvents(halo, this);
        pm.registerEvents(gear, this);
        godGear = new GodGear(this, store);
        pm.registerEvents(godGear, this);
        godGear.start();
        quantum = new QuantumGear(this);
        pm.registerEvents(quantum, this);
        quantum.registerRecipes();
        quantum.start();
        gate = new StrongholdGate(this, store);
        pm.registerEvents(gate, this);
        gate.start();
        seal = new ArenaSeal(this, store);
        pm.registerEvents(seal, this);
        seal.start();
        parasite = new Parasite(this);
        pm.registerEvents(parasite, this);
        parasite.start();
        beasts = new BloodMoonBeasts(this);
        pm.registerEvents(beasts, this);
        beasts.start();
        tartare = new Tartare(this);
        pm.registerEvents(tartare, this);
        tartare.registerRecipe();
        kraken = new KrakenBeard(this);
        pm.registerEvents(kraken, this);
        kraken.start();
        cute = new Cute(this);
        pm.registerEvents(cute, this);
        cute.registerRecipes();
        cute.start();
        pm.registerEvents(new Archangels(this), this);
        pm.registerEvents(new Listener() {
            @EventHandler public void onJoin(PlayerJoinEvent e) { leash.unlockFor(e.getPlayer()); }
        }, this);
        for (Player p : getServer().getOnlinePlayers()) leash.unlockFor(p);

        captures.start();
        curses.start();
        boss.start();
        hunger.start();
        sacrifices.start();
        portals.start();
        drums.start();
        music.start();
        god.start();
        godMusic = new GodMusic(this, god);
        godMusic.start();
        heaven.start();
        halo.start();
        gear.start();
        if (zones.world() == null) getLogger().warning("Moloch's realm '" + zones.worldKey + "' isn't loaded. Install the Sacrifice data pack, or set moloch.world in config.yml.");
        getLogger().info("Soulbound ready: captures, sacrifices, Moloch's curse, the Hunger meter and Moloch himself.");
    }

    @Override
    public void onDisable() {
        if (sacrifices != null) sacrifices.stop();
        if (drums != null) drums.stop();
        if (music != null) music.stop();
        if (gate != null) gate.stop();
        if (seal != null) seal.stop();
        if (parasite != null) parasite.stop();
        if (beasts != null) beasts.stop();
        if (kraken != null) kraken.stop();
        if (cute != null) cute.stop();
        if (godGear != null) godGear.stop();
        if (quantum != null) quantum.stop();
        if (godMusic != null) godMusic.stop();
        if (god != null) god.stop();
        if (heaven != null) heaven.stop();
        if (halo != null) halo.stop();
        if (gear != null) gear.stop();
        if (captures != null) captures.stop();
        if (boss != null) boss.stop();
        if (hunger != null) hunger.stop();
        if (curses != null) curses.stop();
        if (store != null) store.save();
    }

    Zones zones() { return zones; }
    HungerMeter hunger() { return hunger; }
    MolochBoss boss() { return boss; }
    HaloItem halo() { return halo; }
    MolochGear gear() { return gear; }
    GodGear godGear() { return godGear; }
    public ScreenCrack cracks() { return cracks; }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("moloch")) return moloch(sender, args);
        if (command.getName().equalsIgnoreCase("god")) {
            if (sender instanceof Player p && args.length > 0) god.choose(p, args[0]);
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "release" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Only players can release a captive."); return true; }
                Capture cap = captures.heldBy(p);
                if (cap == null) { p.sendMessage(Component.text("You're not holding anyone.", NamedTextColor.GRAY)); return true; }
                if (cap.ritual) { p.sendMessage(Component.text("The ritual has begun. It can't be undone now.", NamedTextColor.RED)); return true; }
                captures.end(cap, CaptureManager.End.LET_GO);
                return true;
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                reload();
                sender.sendMessage(Component.text("Soulbound config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            case "give" -> {
                if (!admin(sender)) return true;
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

    private boolean moloch(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "leave" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                seal.leave(p);
                return true;
            }
            case "awaken" -> {
                if (!admin(sender)) return true;
                if (boss.active()) { sender.sendMessage(Component.text("He's already awake.", NamedTextColor.GRAY)); return true; }
                String no = boss.cantBegin();
                if (no != null) { sender.sendMessage(Component.text(no, NamedTextColor.RED)); return true; }
                boss.begin();
                return true;
            }
            case "stop" -> {
                if (!admin(sender)) return true;
                boss.abort();
                return true;
            }
            case "hunger" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) { sender.sendMessage(Component.text("/moloch hunger <0-100>", NamedTextColor.GRAY)); return true; }
                try { hunger.set(Double.parseDouble(args[1])); } catch (NumberFormatException ex) { sender.sendMessage(Component.text("A number from 0 to 100.", NamedTextColor.RED)); return true; }
                sender.sendMessage(Component.text("Hunger set to " + Math.round(hunger.value()) + ".", NamedTextColor.GREEN));
                return true;
            }
            case "curse", "uncurse" -> {
                if (!admin(sender)) return true;
                Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : null;
                if (t == null) { sender.sendMessage(Component.text("/moloch " + sub + " <player>" + (sub.equals("curse") ? " [minutes]" : ""), NamedTextColor.GRAY)); return true; }
                if (sub.equals("uncurse")) { curses.lift(t); sender.sendMessage(Component.text("Lifted " + t.getName() + "'s curse.", NamedTextColor.GREEN)); return true; }
                int mins = getConfig().getInt("curse.minutes", 45);
                if (args.length > 2) try { mins = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
                curses.curse(t, mins);
                sender.sendMessage(Component.text("Cursed " + t.getName() + " for " + mins + " minutes.", NamedTextColor.GREEN));
                return true;
            }
            case "gate" -> {
                if (!admin(sender)) return true;
                int x = getConfig().getInt("portals.gate.x", 0), z = getConfig().getInt("portals.gate.z", -120);
                if (args.length > 1 && args[1].equalsIgnoreCase("here")) {
                    if (!(sender instanceof Player p) || !zones.inRealm(p.getLocation())) { sender.sendMessage(Component.text("Stand in Moloch's realm to use /moloch gate here.", NamedTextColor.RED)); return true; }
                    x = p.getLocation().getBlockX(); z = p.getLocation().getBlockZ();
                    getConfig().set("portals.gate.x", x); getConfig().set("portals.gate.z", z); saveConfig();
                }
                sender.sendMessage(Component.text(portals.buildGate(x, z), NamedTextColor.GREEN));
                return true;
            }
            case "gear" -> {
                if (!admin(sender)) return true;
                Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : null;
                if (t == null || args.length < 3) { sender.sendMessage(Component.text("/moloch gear <player> <horns|skull|viking|kraken|cute [catears|bunnyears|flowercrown|bubblegut|brain|lab|labcoat|labpants|labshoes]|parasite|cortex|tartare|set|helmet|chestplate|leggings|boots|vestments [piece]|quantum [piece]>", NamedTextColor.GRAY)); return true; }
                if (args[2].equalsIgnoreCase("cute")) { sender.sendMessage(Component.text(cute.adminGive(t, args.length > 3 ? args[3] : "all"), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("kraken")) { sender.sendMessage(Component.text(kraken.adminGive(t), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("tartare")) { sender.sendMessage(Component.text(tartare.adminGive(t, 16), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("cortex")) { sender.sendMessage(Component.text(parasite.adminGiveCrown(t), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("parasite")) { sender.sendMessage(Component.text(parasite.adminGive(t), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("quantum")) { sender.sendMessage(Component.text(quantum.adminGive(t, args.length > 3 ? args[3] : "set"), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("vestments")) { sender.sendMessage(Component.text(godGear.adminGive(t, args.length > 3 ? args[3] : "set"), NamedTextColor.GREEN)); return true; }
                sender.sendMessage(Component.text(gear.adminGive(t, args[2]), NamedTextColor.GREEN));
                return true;
            }
            case "bloodmoon" -> {
                if (!admin(sender)) return true;
                double h = getConfig().getInt("boss.blood-moon-hours", 1);
                if (args.length > 1) { if (args[1].equalsIgnoreCase("off")) h = 0; else try { h = Double.parseDouble(args[1]); } catch (NumberFormatException ignored) {} }
                boss.setBloodMoon(h);
                sender.sendMessage(Component.text(h > 0 ? "Blood moon for " + h + " hours." : "Blood moon ended.", NamedTextColor.GREEN));
                return true;
            }
            case "halo" -> {
                if (!admin(sender)) return true;
                Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (t == null) { sender.sendMessage(Component.text("/moloch halo <player>", NamedTextColor.GRAY)); return true; }
                halo.give(t, null);
                sender.sendMessage(Component.text("Gave " + t.getName() + " a Halo.", NamedTextColor.GREEN));
                return true;
            }
            case "heaven" -> {
                if (!admin(sender)) return true;
                sender.sendMessage(Component.text(heaven.rebuild(), NamedTextColor.GREEN));
                return true;
            }
            case "god" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                sender.sendMessage(Component.text(god.test(p), NamedTextColor.GREEN));
                return true;
            }
            case "setarrival" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p) || !zones.inRealm(p.getLocation())) { sender.sendMessage(Component.text("Stand where players should arrive, in Moloch's realm, facing the way they should face.", NamedTextColor.RED)); return true; }
                portals.setArrival(p.getLocation());
                sender.sendMessage(Component.text("Portals now lead here.", NamedTextColor.GREEN));
                return true;
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                reload();
                sender.sendMessage(Component.text("Moloch config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Moloch is " + boss.status() + ".", NamedTextColor.DARK_RED));
                sender.sendMessage(Component.text("Hunger: " + Math.round(hunger.value()) + " / 100", NamedTextColor.RED));
                if (hunger.awakenAt() > 0) sender.sendMessage(Component.text("He rises " + hunger.whenText() + ".", NamedTextColor.RED));
                if (sender instanceof Player p && curses.secondsLeft(p) > 0)
                    sender.sendMessage(Component.text("You're cursed for " + CurseManager.clock(curses.secondsLeft(p)) + " more.", NamedTextColor.DARK_PURPLE));
                if (sender.hasPermission("soulbound.admin"))
                    sender.sendMessage(Component.text("Admin: /moloch awaken | stop | hunger <n> | curse <player> [min] | uncurse <player> | gate [here] | setarrival | reload", NamedTextColor.GRAY));
                return true;
            }
        }
    }

    private void reload() {
        reloadConfig();
        zones.load();
        captures.loadSettings();
        curses.loadSettings();
        hunger.loadSettings();
        boss.loadSettings();
        sacrifices.loadSettings();
        portals.loadSettings();
        drums.loadSettings();
        god.loadSettings();
        gear.loadSettings();
        seal.loadSettings();
        parasite.loadSettings();
        beasts.loadSettings();
        leash.load();
        leash.registerRecipe();
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("soulbound.admin")) return true;
        s.sendMessage(Component.text("You can't do that.", NamedTextColor.RED));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission("soulbound.admin");
        if (command.getName().equalsIgnoreCase("god")) return args.length == 1 ? List.of("challenge", "lost", "forgive", "look") : List.of();
        if (command.getName().equalsIgnoreCase("moloch")) {
            if (args.length == 1) return admin ? List.of("awaken", "stop", "hunger", "curse", "uncurse", "gate", "setarrival", "god", "heaven", "halo", "gear", "bloodmoon", "leave", "reload") : List.of("leave");
            return null;   // player names
        }
        if (args.length == 1) return admin ? List.of("release", "give", "reload") : List.of("release");
        return List.of();
    }
}
