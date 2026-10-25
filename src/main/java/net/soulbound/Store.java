package net.soulbound;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/** Everything that must survive a restart: the Hunger meter, curses, sacrifice records, the next awakening. */
final class Store {
    private final Soulbound plugin;
    private final File file;
    final YamlConfiguration data;
    private boolean dirty;

    Store(Soulbound plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    void set(String path, Object value) { data.set(path, value); dirty = true; }

    void save() {
        if (!dirty) return;
        try { plugin.getDataFolder().mkdirs(); data.save(file); dirty = false; }
        catch (IOException ex) { plugin.getLogger().warning("Couldn't save data.yml: " + ex.getMessage()); }
    }
}
