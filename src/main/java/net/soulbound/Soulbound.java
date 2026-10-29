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
    private FesteringFlesh festering;
    private ThiefKey thiefKey;
    private KrakenBeard kraken;
    private Cute cute;
    private GodGear godGear;
    private QuantumGear quantum;
    private GodManager god;
    private HeavenAir heavenAir;
    private HeavenBuilder heaven;
    private HaloItem halo;
    private MolochGear gear;
    private BloodPriest priest;
    private Archangels archangels;
    private Inhuman inhuman;
    private InhumanHearts inhumanHearts;
    private InhumanHunger inhumanHunger;
    private LoreFixer loreFixer;
    private Grace grace;
    private EdenMask eden;
    private Zephyrs zephyrs;
    private Peacekeeper peacekeeper;
    private IslandGuard island;
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
        // one-off: Archangel ghasts were too rare
        if (!getConfig().getBoolean("migrated.archangel-ghasts-more", false)) {
            if (getConfig().getDouble("archangels.ghasts.chance", 0.2) == 0.08) getConfig().set("archangels.ghasts.chance", 0.2);
            if (getConfig().getInt("archangels.ghasts.max-per-player", 3) == 2) getConfig().set("archangels.ghasts.max-per-player", 3);
            getConfig().set("migrated.archangel-ghasts-more", true);
            saveConfig();
        }
        // one-off: blood moons went from 24 hours to 1
        if (!getConfig().getBoolean("migrated.blood-moon-1h", false)) {
            if (getConfig().getInt("boss.blood-moon-hours", 24) == 24) getConfig().set("boss.blood-moon-hours", 1);
            getConfig().set("migrated.blood-moon-1h", true);
            saveConfig();
        }
        // one-off: no more cooldown before a freed player can be captured again
        if (!getConfig().getBoolean("migrated.capture-no-immunity", false)) {
            getConfig().set("capture.immunity-seconds", 0);
            getConfig().set("migrated.capture-no-immunity", true);
            saveConfig();
        }
        // one-off: a human sacrifice now feeds 40% of Moloch's hunger (was 12%)
        if (!getConfig().getBoolean("migrated.sacrifice-feeds-40", false)) {
            getConfig().set("hunger.per-sacrifice", 40);
            getConfig().set("migrated.sacrifice-feeds-40", true);
            saveConfig();
        }
        // one-off: offering points are now 0.1% each (the data pack scaled its points up to match; redstone got cheaper)
        if (!getConfig().getBoolean("migrated.offer-points-0.1", false)) {
            if (getConfig().getDouble("hunger.per-offering-point", 0.1) == 0.5) getConfig().set("hunger.per-offering-point", 0.1);
            getConfig().set("migrated.offer-points-0.1", true);
            saveConfig();
        }
        // one-off: no more Demons (burning phantoms) in Moloch's fight
        if (!getConfig().getBoolean("migrated.no-demons", false)) {
            getConfig().set("boss.demons", false);
            getConfig().set("migrated.no-demons", true);
            saveConfig();
        }
        // one-off: at most 2 Archangel ghasts per player (was 3)
        if (!getConfig().getBoolean("migrated.archangels-2", false)) {
            if (getConfig().getInt("archangels.ghasts.max-per-player", 2) == 3) getConfig().set("archangels.ghasts.max-per-player", 2);
            getConfig().set("migrated.archangels-2", true);
            saveConfig();
        }
        // one-off: Moloch's Regalia makes you 1.5x bigger again (a config left at 1.1 is brought back up)
        if (!getConfig().getBoolean("migrated.regalia-scale-15", false)) {
            if (getConfig().getDouble("boss.regalia-set-scale", 1.5) < 1.5) getConfig().set("boss.regalia-set-scale", 1.5);
            getConfig().set("migrated.regalia-scale-15", true);
            saveConfig();
        }
        // one-off: 3 minutes before an escaped player can be captured again
        if (!getConfig().getBoolean("migrated.capture-immunity-180", false)) {
            if (getConfig().getInt("capture.immunity-seconds", 180) == 0) getConfig().set("capture.immunity-seconds", 180);
            getConfig().set("migrated.capture-immunity-180", true);
            saveConfig();
        }
        // one-off: fewer Zephyrs (one at a time, about one a minute)
        if (!getConfig().getBoolean("migrated.zephyrs-fewer", false)) {
            if (getConfig().getDouble("zephyrs.chance", 0.08) == 0.25) getConfig().set("zephyrs.chance", 0.08);
            if (getConfig().getInt("zephyrs.max-per-player", 1) == 2) getConfig().set("zephyrs.max-per-player", 1);
            getConfig().set("migrated.zephyrs-fewer", true);
            saveConfig();
        }
        // one-off: Moloch's Regalia drops more often (15% -> 35%)
        if (!getConfig().getBoolean("migrated.regalia-35", false)) {
            if (getConfig().getDouble("boss.regalia-drop-chance", 0.35) == 0.15) getConfig().set("boss.regalia-drop-chance", 0.35);
            getConfig().set("migrated.regalia-35", true);
            saveConfig();
        }
        // one-off: the capture leash is now the Cultist's Shackles
        if (!getConfig().getBoolean("migrated.cultists-shackles", false)) {
            if ("Bondage Leash".equals(getConfig().getString("leash.name", "Bondage Leash"))) getConfig().set("leash.name", "Cultist's Shackles");
            getConfig().set("migrated.cultists-shackles", true);
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
        priest = new BloodPriest(this, captures, leash);
        cracks = new ScreenCrack(this);
        god = new GodManager(this, store);
        heavenAir = new HeavenAir(this, god);
        heaven = new HeavenBuilder(this, store);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(captures, this);
        pm.registerEvents(priest, this);
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
        festering = new FesteringFlesh(this);
        pm.registerEvents(festering, this);
        thiefKey = new ThiefKey(this);
        pm.registerEvents(thiefKey, this);
        kraken = new KrakenBeard(this);
        pm.registerEvents(kraken, this);
        kraken.start();
        cute = new Cute(this);
        pm.registerEvents(cute, this);
        cute.registerRecipes();
        cute.start();
        archangels = new Archangels(this);
        pm.registerEvents(archangels, this);
        archangels.start();
        island = new IslandGuard(this);
        pm.registerEvents(island, this);
        island.start();
        pm.registerEvents(new HeavenBeds(this), this);
        pm.registerEvents(new RealmStructures(this), this);
        pm.registerEvents(new RoadGuard(this), this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try { new SoulboundPlaceholders(this).register(); getLogger().info("PlaceholderAPI found: %soulbound_...% placeholders registered."); }
            catch (Throwable t) { getLogger().warning("Couldn't register placeholders: " + t); }
        }
        loreFixer = new LoreFixer(this);
        pm.registerEvents(loreFixer, this);
        GlowPortal glowPortal = new GlowPortal(this);
        pm.registerEvents(glowPortal, this);
        glowPortal.start();
        pm.registerEvents(new WorldEgg(this), this);
        inhumanHearts = new InhumanHearts(this);   // off for now: not started
        inhuman = new Inhuman(this, captures);
        pm.registerEvents(inhuman, this);
        inhuman.start();
        inhumanHunger = new InhumanHunger(this);
        pm.registerEvents(inhumanHunger, this);
        inhumanHunger.start();
        grace = new Grace(this);
        eden = new EdenMask(this);
        zephyrs = new Zephyrs(this);
        pm.registerEvents(zephyrs, this);
        zephyrs.start();
        peacekeeper = new Peacekeeper(this);
        pm.registerEvents(peacekeeper, this);
        peacekeeper.start();
        pm.registerEvents(grace, this);
        SbCommand sb = new SbCommand(this);
        pm.registerEvents(sb, this);
        if (getCommand("soulbound") != null) { getCommand("soulbound").setExecutor(sb); getCommand("soulbound").setTabCompleter(sb); }
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
        heavenAir.start();
        godMusic = new GodMusic(this, god);
        godMusic.start();
        heaven.start();
        halo.start();
        gear.start();
        priest.start();
        // Moloch's realm: a lower monster cap than the vanilla 70, so the terraces aren't swarming
        getServer().getScheduler().runTaskTimer(this, () -> {
            org.bukkit.World realm = zones.world();
            if (realm == null) return;
            int cap = getConfig().getInt("moloch.monster-limit", 30);
            if (cap >= 0 && realm.getSpawnLimit(org.bukkit.entity.SpawnCategory.MONSTER) != cap) realm.setSpawnLimit(org.bukkit.entity.SpawnCategory.MONSTER, cap);
        }, 40L, 600L);
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
        if (heavenAir != null) heavenAir.stop();
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
    BloodPriest priest() { return priest; }
    Tartare tartare() { return tartare; }
    FesteringFlesh festering() { return festering; }
    ThiefKey thiefKey() { return thiefKey; }
    Store store() { return store; }
    GodManager god() { return god; }
    Archangels archangels() { return archangels; }
    Inhuman inhuman() { return inhuman; }
    InhumanHearts inhumanHearts() { return inhumanHearts; }
    InhumanHunger inhumanHunger() { return inhumanHunger; }
    LoreFixer lore() { return loreFixer; }
    Grace grace() { return grace; }
    EdenMask eden() { return eden; }
    Zephyrs zephyrs() { return zephyrs; }
    Peacekeeper peacekeeper() { return peacekeeper; }
    IslandGuard island() { return island; }
    CaptureManager captures() { return captures; }
    LeashItem leash() { return leash; }
    Cute cute() { return cute; }
    KrakenBeard kraken() { return kraken; }
    Parasite parasite() { return parasite; }
    CurseManager curses() { return curses; }
    GodGear godGear() { return godGear; }
    QuantumGear quantum() { return quantum; }
    public ScreenCrack cracks() { return cracks; }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("moloch")) {
            String hint = args.length > 0 ? NEW_FORM.get(args[0].toLowerCase(java.util.Locale.ROOT)) : null;
            if (hint != null && sender.hasPermission("soulbound.admin"))
                sender.sendMessage(Component.text("Tip: this lives under " + hint + " now (the old command still works).", NamedTextColor.DARK_GRAY));
            return moloch(sender, args);
        }
        if (command.getName().equalsIgnoreCase("god")) {
            if (sender instanceof Player p && args.length > 0) god.choose(p, args[0]);
            return true;
        }
        return soulbound(sender, args);
    }

    private boolean soulbound(CommandSender sender, String[] args) {
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
                for (int i = 0; i < n; i++) target.getInventory().addItem(leash.create(1));
                sender.sendMessage(Component.text("Gave " + n + " " + getConfig().getString("leash.name", "Cultist's Shackles") + " to " + target.getName() + ".", NamedTextColor.GREEN));
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

    private static final java.util.Map<String, String> NEW_FORM = java.util.Map.ofEntries(
            java.util.Map.entry("awaken", "/sb moloch awaken"), java.util.Map.entry("stop", "/sb moloch stop"),
            java.util.Map.entry("hunger", "/sb moloch hunger"), java.util.Map.entry("bloodmoon", "/sb moloch bloodmoon"),
            java.util.Map.entry("curse", "/sb player <name> curse"), java.util.Map.entry("uncurse", "/sb player <name> uncurse"),
            java.util.Map.entry("inhuman", "/sb player <name> inhuman"), java.util.Map.entry("resetlimits", "/sb player <name> resetlimits"),
            java.util.Map.entry("gear", "/sb give <player> <item>  (or /sb items)"), java.util.Map.entry("halo", "/sb give <player> halo"),
            java.util.Map.entry("priest", "/sb world priest"), java.util.Map.entry("gate", "/sb world gate"), java.util.Map.entry("temple", "/sb world temple"),
            java.util.Map.entry("setarrival", "/sb world setarrival"), java.util.Map.entry("god", "/sb god test"), java.util.Map.entry("heaven", "/sb god heaven"),
            java.util.Map.entry("reload", "/sb reload"));

    boolean legacyMoloch(CommandSender sender, String[] args) { return moloch(sender, args); }

    /** /sb release (and the old /soulbound forms) for players. */
    boolean legacySoulbound(CommandSender sender, String[] args) { return soulbound(sender, args); }

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
                if (sub.equals("uncurse")) {
                    curses.lift(t);
                    sacrifices.forgetVictim(t.getUniqueId());   // and they can be offered again straight away
                    sender.sendMessage(Component.text("Lifted " + t.getName() + "'s curse; they can be offered again.", NamedTextColor.GREEN));
                    return true;
                }
                int mins = getConfig().getInt("curse.minutes", 45);
                if (args.length > 2) try { mins = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
                curses.curse(t, mins);
                sender.sendMessage(Component.text("Cursed " + t.getName() + " for " + mins + " minutes.", NamedTextColor.GREEN));
                return true;
            }
            case "resetlimits" -> {
                if (!admin(sender)) return true;
                if (args.length > 1) {
                    org.bukkit.OfflinePlayer t = getServer().getOfflinePlayerIfCached(args[1]);
                    if (t == null) { sender.sendMessage(Component.text("No player called " + args[1] + ".", NamedTextColor.RED)); return true; }
                    sacrifices.resetLimits(t.getUniqueId());
                    sender.sendMessage(Component.text("Cleared " + t.getName() + "'s sacrifice cooldown and daily count.", NamedTextColor.GREEN));
                } else {
                    sacrifices.resetLimits(null);
                    sender.sendMessage(Component.text("Cleared every sacrifice cooldown and today's limits.", NamedTextColor.GREEN));
                }
                return true;
            }
            case "priest" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) { sender.sendMessage(Component.text("Stand where he should stand.", NamedTextColor.RED)); return true; }
                boolean remove = args.length > 1 && args[1].equalsIgnoreCase("remove");
                sender.sendMessage(Component.text(remove ? priest.removeNear(p) : priest.spawn(p), NamedTextColor.GREEN));
                return true;
            }
            case "inhuman" -> {
                if (!admin(sender)) return true;
                Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : null;
                if (t == null) { sender.sendMessage(Component.text("/moloch inhuman <player> [off]", NamedTextColor.GRAY)); return true; }
                boolean off = args.length > 2 && args[2].equalsIgnoreCase("off");
                inhuman.set(t, !off);
                sender.sendMessage(Component.text(t.getName() + (off ? " is human again." : " is no longer human."), NamedTextColor.GREEN));
                return true;
            }
            case "temple" -> {
                if (!admin(sender)) return true;
                sender.sendMessage(Component.text(portals.templeWorks(), NamedTextColor.GREEN));
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
                if (t == null || args.length < 3) { sender.sendMessage(Component.text("/moloch gear <player> <horns|skull|viking|kraken|hood|cudgel|chains|cute [catears|bunnyears|flowercrown|bubblegut|brain|chef|wizard|tophat|prospector|lab|labcoat|labpants|labshoes]|parasite|cortex|tartare|set|helmet|chestplate|leggings|boots|vestments [piece]|quantum [piece|tools|sword|pickaxe|axe|shovel|hoe|core|ember|tear]>", NamedTextColor.GRAY)); return true; }
                if (args[2].equalsIgnoreCase("hood") || args[2].equalsIgnoreCase("cudgel")) { sender.sendMessage(Component.text(captures.items.adminGive(t, args[2]), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("chains")) { for (int i = 0; i < 4; i++) t.getInventory().addItem(leash.create(1)); sender.sendMessage(Component.text("Gave " + t.getName() + " 4 " + getConfig().getString("leash.name", "Cultist's Shackles") + ".", NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("cute")) { sender.sendMessage(Component.text(cute.adminGive(t, args.length > 3 ? args[3] : "all"), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("kraken")) { sender.sendMessage(Component.text(kraken.adminGive(t), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("heart")) { t.getInventory().addItem(inhuman.heart()); sender.sendMessage(Component.text("Gave " + t.getName() + " the Heart of Darkness.", NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("tartare")) { sender.sendMessage(Component.text(tartare.adminGive(t, 16), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("cortex")) { sender.sendMessage(Component.text(parasite.adminGiveCrown(t), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("parasite")) { sender.sendMessage(Component.text(parasite.adminGive(t), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("quantum")) { sender.sendMessage(Component.text(quantum.adminGive(t, args.length > 3 ? args[3] : "set"), NamedTextColor.GREEN)); return true; }
                if (args[2].equalsIgnoreCase("vestments")) { sender.sendMessage(Component.text(godGear.adminGive(t, args.length > 3 ? args[3] : "set"), NamedTextColor.GREEN)); return true; }
                sender.sendMessage(Component.text(gear.adminGive(t, args[2]), NamedTextColor.GREEN));
                return true;
            }
            case "carry" -> {
                if (!admin(sender)) return true;
                java.util.List<String> keys = java.util.List.of("pose", "height", "sideways", "forward", "turn");
                if (args.length < 3 || !keys.contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    StringBuilder now = new StringBuilder("/moloch carry <pose|height|sideways|forward|turn> <value>  ·  now:");
                    for (String k : keys) now.append(' ').append(k).append('=').append(getConfig().get("capture.carry." + k, "default"));
                    sender.sendMessage(Component.text(now.toString(), NamedTextColor.GRAY));
                    return true;
                }
                String k = args[1].toLowerCase(java.util.Locale.ROOT);
                if (k.equals("pose")) getConfig().set("capture.carry.pose", args[2].toUpperCase(java.util.Locale.ROOT));
                else try { getConfig().set("capture.carry." + k, Double.parseDouble(args[2])); }
                     catch (NumberFormatException ex) { sender.sendMessage(Component.text("That needs a number.", NamedTextColor.RED)); return true; }
                saveConfig();
                captures.loadSettings();
                sender.sendMessage(Component.text("Carry " + k + " set to " + args[2] + ". Pick someone up again to see it.", NamedTextColor.GREEN));
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
                    sender.sendMessage(Component.text("Admin: /moloch awaken | stop | hunger <n> | curse <player> [min] | uncurse <player> | resetlimits [player] | priest [remove] | inhuman <player> [off] | gate [here] | setarrival | reload", NamedTextColor.GRAY));
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
        priest.load();
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
            if (args.length == 1) return admin ? List.of("awaken", "stop", "hunger", "curse", "uncurse", "resetlimits", "priest", "inhuman", "gate", "temple", "carry", "setarrival", "god", "heaven", "halo", "gear", "bloodmoon", "leave", "reload") : List.of("leave");
            return null;   // player names
        }
        if (args.length == 1) return admin ? List.of("release", "give", "reload") : List.of("release");
        return List.of();
    }
}
